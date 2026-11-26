//! 导入的社区方案里，被智能档过滤的生僻字必须仍能打出来（手机端出厂 `always_append = true`）。
//!
//! 背景（B1）：98 五笔导入后抽样 1619 个单字，智能档下 362 个（约 22%）出不来——不是导入
//! 丢了数据，是「同码有常用字就滤掉生僻字」且手机没有翻页键去放宽。样例用社区 98 五笔
//! （`WindInputCodeTable/schemas/wubi98`）现打包导入：`amu` 码位上 贡（常用）与 貢（繁体，
//! 表外）同码，`abue` 的 䔹、`bnh` 的 卩 同理。
//!
//! ⚠️ `config_set` 改 `input.filter_mode` 之后必须再发 `ReloadConfig` 才生效；漏了会得到
//! 「各档结果一样」的假象。

use std::io::Write;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use wind_android::{
    CandidateUpdate, ConfigValue, EngineCommand, InputStatus, KeySource, PressKey, UiEventListener,
    WindCore,
};

struct Nop;
impl UiEventListener for Nop {
    fn on_candidates(&self, _u: CandidateUpdate) {}
    fn on_hide_candidates(&self) {}
    fn on_status(&self, _s: InputStatus) {}
    fn on_toast(&self, _t: String) {}
}

/// WindCore 用进程级环境变量定位用户层，同一进程内的测试要串行。
static LOCK: Mutex<()> = Mutex::new(());

fn manifest() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn sample98() -> Option<PathBuf> {
    let p = manifest().join("../../../WindInputCodeTable/schemas/wubi98");
    if p.join("wubi98.schema.toml").exists() {
        return Some(p);
    }
    if std::env::var_os("CI").is_some() {
        eprintln!(
            "SKIPPED(依赖 98 五笔样例，不代表通过): 找不到 {}",
            p.display()
        );
        return None;
    }
    panic!(
        "找不到 98 五笔样例 {}：工作区应并列存放 WindInputCodeTable",
        p.display()
    );
}

fn import98(src: &std::path::Path) -> Arc<WindCore> {
    let layer = std::env::temp_dir().join("wind-android-append-userlayer");
    let _ = std::fs::create_dir_all(&layer);
    let conf = std::env::temp_dir().join("wind-android-append-datadir.conf");
    std::fs::write(&conf, layer.to_string_lossy().as_bytes()).unwrap();
    let _ = std::fs::remove_file(layer.join("config.toml"));
    // SAFETY: 调用方持 LOCK
    unsafe { std::env::set_var("WIND_DATADIR_CONF", &conf) };

    let root = std::env::temp_dir().join("wind-android-append-root");
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).unwrap();
    let core = WindCore::new(
        manifest()
            .join("../../app/src/main/assets/data")
            .to_string_lossy()
            .into_owned(),
        root.to_string_lossy().into_owned(),
        root.join("local").to_string_lossy().into_owned(),
        "wubi86".into(),
        Arc::new(Nop),
    );
    core.focus_gained();

    let pkg = root.join("wubi98.wpkg");
    let mut zw = zip::ZipWriter::new(std::fs::File::create(&pkg).unwrap());
    let o = zip::write::SimpleFileOptions::default();
    zw.start_file("package.toml", o).unwrap();
    zw.write_all(b"[package]\nformat_version = 2\ntitle = \"98\"\n")
        .unwrap();
    for rel in [
        "wubi98.schema.toml",
        "wubi98_pinyin.schema.toml",
        "wubi98/wubi98_cidian.dict.yaml",
    ] {
        zw.start_file(rel, o).unwrap();
        zw.write_all(&std::fs::read(src.join(rel)).unwrap())
            .unwrap();
    }
    zw.finish().unwrap();
    core.scheme_import(pkg.to_string_lossy().into_owned(), true)
        .expect("导入 98 五笔");
    std::thread::sleep(std::time::Duration::from_millis(500));
    let idx = core
        .schemas()
        .iter()
        .position(|s| s.id == "wubi98")
        .expect("方案列表应有 wubi98");
    core.run_command(EngineCommand::SelectSchema { index: idx as u32 });
    std::thread::sleep(std::time::Duration::from_millis(1500));
    core
}

fn set_mode(core: &WindCore, mode: &str) {
    core.config_set(
        "input.filter_mode".into(),
        ConfigValue::Str { v: mode.into() },
    )
    .unwrap();
    core.run_command(EngineCommand::ReloadConfig);
    std::thread::sleep(std::time::Duration::from_millis(1500));
}

fn items(core: &WindCore, code: &str) -> Vec<String> {
    for ch in code.chars() {
        core.press(
            PressKey::Char {
                ch: ch.to_string(),
                shifted: false,
            },
            KeySource::Touch,
        );
    }
    let v = core.candidates(0, 400).items;
    for _ in code.chars() {
        core.press(PressKey::Backspace, KeySource::Touch);
    }
    v
}

#[test]
fn smart_mode_appends_filtered_rare_chars_after_normal_ones() {
    let Some(src) = sample98() else { return };
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let core = import98(&src);
    set_mode(&core, "smart");

    let amu = items(&core, "amu");
    assert_eq!(
        amu.first().map(String::as_str),
        Some("贡"),
        "常用字仍排第一: {amu:?}"
    );
    assert_eq!(
        amu.last().map(String::as_str),
        Some("貢"),
        "被滤的繁体字沉底但必须在: {amu:?}"
    );
    assert_eq!(items(&core, "abue").last().map(String::as_str), Some("䔹"));
    assert_eq!(items(&core, "bnh").last().map(String::as_str), Some("卩"));
}

#[test]
fn general_mode_still_hides_them_and_all_mode_has_no_duplicates() {
    let Some(src) = sample98() else { return };
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let core = import98(&src);

    set_mode(&core, "general");
    assert!(
        !items(&core, "amu").contains(&"貢".to_string()),
        "常用字档要稳定只出常用字"
    );

    set_mode(&core, "gb18030");
    let all = items(&core, "amu");
    assert_eq!(
        all.iter().filter(|t| *t == "貢").count(),
        1,
        "全部字符档不得重复追加: {all:?}"
    );
}
