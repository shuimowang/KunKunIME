//! 方案包（.wpkg / .zip）预览、导入、删除的纯文件层。
//!
//! 包格式、穿越守卫、解压限额、版本门禁、共享文件保留全在主仓 `wind-transfer`，
//! 这里只做**参数投影**与两条手机端语义（宿主不要自己再判一遍）：
//!
//! - **config_patch 本期不应用**：核心 `import_package` 本就不把它落盘，这里只在预览里
//!   报 `ignored_config_patch`，让界面写「本版本不会应用」；不拒绝整包。
//! - **覆盖自带方案**：包里的方案 id 在只读层（APK 自带）已有同名文件时，导入会在用户层
//!   写一份同名文件盖住自带的（用户层优先）。预览里列在 `overrides_builtin`，界面要醒目写明并二次确认。
//!
//! - **手机端解压上限更低**：核心允许解压后 256MB，导入时整包先读入内存，手机上与输入法同进程，
//!   内存紧张时可能被系统杀掉，所以在预览 / 导入前按声明大小先挡在 [`MAX_UNPACKED_BYTES`]。
//!   （核心逐条目导入时会反复重开 zip 找条目，条目多时是 O(n²)；在主仓 wind-transfer 里，这里不碰。）
//!
//! 入参 / 出参的目录由 [`crate::WindCore`] 从核心取（见 `MobileCore::scheme_dirs`），
//! 本模块不碰进程级状态，所以测试用临时目录即可，不必起引擎。

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use wind_mobile::wind_transfer::merge::Strategy;
use wind_mobile::wind_transfer::scheme;

/// 手机端方案包解压后总量上限（核心自己的上限是 256MB）。
pub const MAX_UNPACKED_BYTES: u64 = 128 * 1024 * 1024;

/// 预览结果：只读，不落盘。字符串列表里的路径都是 schemas 根相对路径。
#[derive(Debug, Clone, uniffi::Record)]
pub struct SchemePreviewInfo {
    /// 包标题（`package.toml` 的 `title`，缺省空串）。分发者提供的任意文本，只能当纯文本渲染
    pub title: String,
    /// 包说明（多行纯文本，缺省空串）
    pub description: String,
    /// 包内的方案 id（根下 `*.schema.toml`）
    pub schema_ids: Vec<String>,
    /// 将新增的文件
    pub will_add: Vec<String>,
    /// 用户层已有同名文件的条目（`replace=false` 时跳过，`true` 时覆盖）
    pub conflicts: Vec<String>,
    /// 引用的系统（APK 自带）资源——包里没带，运行时取自带的
    pub system_refs: Vec<String>,
    /// 缺失的引用：装上也可能用不了，界面要醒目提示
    pub missing: Vec<String>,
    /// 包内方案 id 与 APK 自带方案同名：导入会覆盖自带方案（用户层优先），需二次确认
    pub overrides_builtin: Vec<String>,
    /// 包内会写进用户层、且与只读层（APK 自带）**同路径**的资源文件（不含方案文件本身）：
    /// 导入后它们会遮住自带词库，要醒目提示并二次确认；删除 / 恢复自带版本时一并清掉
    pub overrides_builtin_files: Vec<String>,
    /// 包带 `config_patch.toml`：本版本不应用
    pub ignored_config_patch: bool,
}

/// 导入结果。
#[derive(Debug, Clone, uniffi::Record)]
pub struct SchemeImportInfo {
    /// 实际写入的文件
    pub imported: Vec<String>,
    /// 因已存在而跳过的文件（`replace=false`）
    pub conflicts: Vec<String>,
    /// 包内的方案 id（用于结果页「立即启用」）
    pub schema_ids: Vec<String>,
}

/// 删除结果。
#[derive(Debug, Clone, uniffi::Record)]
pub struct SchemeDeleteInfo {
    pub deleted: Vec<String>,
    /// 被其它方案共享而保留的文件
    pub kept_shared: Vec<String>,
    /// 因只读层有同路径副本而删掉的用户层文件（被遮住的自带方案 / 资源因此恢复可见），
    /// 不含被删方案自己的方案文件。界面据此提示「同时恢复了 N 个自带文件」
    pub restored_builtin: Vec<String>,
    pub schema_ids: Vec<String>,
}

fn reason(e: impl std::fmt::Display) -> String {
    format!("{e:#}")
}

/// 只读预览。`system_dirs` = 只读层（APK 自带 / 定制）的 schemas 目录。
pub fn preview(
    package: &Path,
    user_dir: &Path,
    system_dirs: &[PathBuf],
) -> Result<SchemePreviewInfo, String> {
    check_unpacked_size(package)?;
    let p = scheme::preview_import(package, user_dir).map_err(reason)?;
    let mut schema_ids: Vec<String> = p
        .will_add
        .iter()
        .chain(p.conflicts.iter())
        .filter(|r| !r.contains('/'))
        .filter_map(|r| r.strip_suffix(".schema.toml"))
        .map(String::from)
        .collect();
    schema_ids.sort();
    let overrides_builtin = schema_ids
        .iter()
        .filter(|id| in_dirs(id, system_dirs))
        .cloned()
        .collect();
    let overrides_builtin_files = p
        .will_add
        .iter()
        .chain(p.conflicts.iter())
        .filter(|r| !(r.ends_with(".schema.toml") && !r.contains('/')))
        .filter(|r| system_dirs.iter().any(|d| d.join(r).is_file()))
        .cloned()
        .collect();
    Ok(SchemePreviewInfo {
        title: p.meta.package.title,
        description: p.meta.package.description,
        schema_ids,
        will_add: p.will_add,
        conflicts: p.conflicts,
        system_refs: p.system_refs,
        missing: p.missing,
        overrides_builtin,
        overrides_builtin_files,
        ignored_config_patch: p.config_patch.is_some(),
    })
}

/// 落盘。`replace=false` 即 Merge（已存在跳过）。先全部读入内存再逐文件 tmp+rename，
/// 校验失败零落盘。
pub fn import(package: &Path, user_dir: &Path, replace: bool) -> Result<SchemeImportInfo, String> {
    check_unpacked_size(package)?;
    std::fs::create_dir_all(user_dir).map_err(|e| format!("无法创建用户方案目录: {e}"))?;
    let strategy = if replace {
        Strategy::Replace
    } else {
        Strategy::Merge
    };
    let r = scheme::import_package_with_limit(package, user_dir, strategy, MAX_UNPACKED_BYTES)
        .map_err(reason)?;
    Ok(SchemeImportInfo {
        imported: r.imported,
        conflicts: r.conflicts,
        schema_ids: r.schema_ids,
    })
}

/// 删除用户层的方案文件及其私有资源。只读层文件永不删；`keep_ids` 里其它方案引用的文件保留。
///
/// 例外（共享保护的放行）：被保留的用户层文件在只读层有**同路径**副本，**且**引用它的其它方案
/// 全是只读层（自带）方案——删掉用户副本后它们引用的仍是同一路径，只是回落到自带版本。
/// 没有这条例外，被自带混输引用的自带方案（如 `pinyin` 被 `wubi86_pinyin` 引用）一旦被覆盖就永远
/// 「恢复」不掉。只要有任何**用户层**方案（用户导入的，或覆盖了自带的）还引用它，就照常保留：
/// 否则删一个不相干的方案会把用户刻意做的覆盖悄悄恢复掉。
pub fn delete(
    id: &str,
    user_dir: &Path,
    system_dirs: &[PathBuf],
    keep_ids: &[String],
) -> Result<SchemeDeleteInfo, String> {
    if !user_dir.is_dir() {
        return Err("没有可删除的用户方案".to_string());
    }
    // 在删除之前收集：用户层方案（方案文件在用户层）引用到的全部用户层文件
    let mut user_referenced: HashSet<String> = HashSet::new();
    for kid in keep_ids.iter().filter(|k| *k != id) {
        if !user_dir.join(format!("{kid}.schema.toml")).is_file() {
            continue;
        }
        if let Ok(plan) = scheme::collect_package_files(kid, user_dir, system_dirs, None, false) {
            user_referenced.extend(plan.pack.into_iter().map(|(rel, _)| rel));
        }
    }
    let r = scheme::delete_package(id, user_dir, system_dirs, keep_ids).map_err(reason)?;
    let mut deleted = r.deleted;
    let mut schema_ids = r.schema_ids;
    let mut kept_shared = Vec::new();
    let mut restored_builtin = Vec::new();
    let own_schema = format!("{id}.schema.toml");
    for rel in r.kept_shared {
        let shadows_builtin = system_dirs.iter().any(|d| d.join(&rel).is_file());
        if shadows_builtin && !user_referenced.contains(&rel) {
            std::fs::remove_file(user_dir.join(&rel))
                .map_err(|e| format!("删除 {rel} 失败: {e}"))?;
            if let Some(sid) = rel
                .strip_suffix(".schema.toml")
                .filter(|_| !rel.contains('/'))
            {
                schema_ids.push(sid.to_string());
            }
            prune_empty_dirs(user_dir, &rel);
            if rel != own_schema {
                restored_builtin.push(rel.clone());
            }
            deleted.push(rel);
        } else {
            kept_shared.push(rel);
        }
    }
    Ok(SchemeDeleteInfo {
        deleted,
        kept_shared,
        restored_builtin,
        schema_ids,
    })
}

/// 自底向上清掉 `rel` 所在的空目录（止于 `user_dir`）。
fn prune_empty_dirs(user_dir: &Path, rel: &str) {
    let mut dir = user_dir.join(rel).parent().map(Path::to_path_buf);
    while let Some(d) = dir {
        if d == user_dir || std::fs::remove_dir(&d).is_err() {
            break;
        }
        dir = d.parent().map(Path::to_path_buf);
    }
}

/// 按 zip 中央目录里**声明**的大小挡掉超过手机端上限的包（撒谎的包由核心的逐条有界读取兜底）。
fn check_unpacked_size(package: &Path) -> Result<(), String> {
    let file = std::fs::File::open(package).map_err(|e| format!("无法打开方案包: {e}"))?;
    // 打不开 zip 的错误留给核心给出统一的文案
    let Ok(mut archive) = zip::ZipArchive::new(file) else {
        return Ok(());
    };
    let mut declared = 0u64;
    for i in 0..archive.len() {
        // 读不出条目信息的包直接拒绝，不能跳过——跳过等于让它绕开上限
        let f = archive
            .by_index_raw(i)
            .map_err(|e| format!("方案包条目损坏（第 {i} 项）: {e}"))?;
        declared = declared.saturating_add(f.size());
    }
    if declared > MAX_UNPACKED_BYTES {
        return Err(format!(
            "方案包解压后约 {} MB，超过手机端上限 {} MB（导入时要整包读入内存，手机上与输入法同进程，内存紧张可能被系统杀掉）",
            declared >> 20,
            MAX_UNPACKED_BYTES >> 20
        ));
    }
    Ok(())
}

/// 方案 id 在哪几层有文件：`(用户层, 只读层)`。设置页据此决定显示「删除」还是「恢复自带版本」。
pub fn layers_of(id: &str, user_dir: Option<&Path>, system_dirs: &[PathBuf]) -> (bool, bool) {
    let user = user_dir.is_some_and(|d| d.join(format!("{id}.schema.toml")).is_file());
    (user, in_dirs(id, system_dirs))
}

fn in_dirs(id: &str, dirs: &[PathBuf]) -> bool {
    dirs.iter()
        .any(|d| d.join(format!("{id}.schema.toml")).is_file())
}
