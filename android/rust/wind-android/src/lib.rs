//! Android 绑定层：把 [`wind_mobile`] 的移动端门面投影成 UniFFI 类型。
//!
//! # 这一层只做投影
//!
//! 输入法的实际行为——三层配置加载、已装方案扫描、预热闸门、候选窗口、吃键判定——
//! 全在主仓的 `wind-mobile` 里，那些逻辑换成 iOS 也成立，不该住在名字里带 android 的
//! crate 中。本 crate 只负责三件 Android 专属的事：
//!
//! 1. **类型投影**：核心的 Rust 类型 ↔ UniFFI 的 Record/Enum（跨 FFI 的表达能力受限）
//! 2. **日志落地**：tracing → logcat
//! 3. **产物形态**：`cdylib` 与 Kotlin 绑定生成器
//!
//! 判据很简单：**写下的东西如果换成 iOS 仍然要写一遍，它就该在 wind-mobile 里。**
//!
//! # 通道形状
//!
//! - 正向：`key_down(vk)` → 同步 [`KeyResult`]（上屏文本/组合区走按键返回值）
//! - 反向：核心的泵线程 → [`UiEventListener`] 回调（候选/状态/提示）
//! - 候选：推送帧 [`CandidateUpdate`] 只给**当页**，列表本身由 Kotlin 按窗口
//!   [`WindCore::candidates`] 拉、按绝对下标 [`WindCore::select_candidate`] 选
//!
//! 详见 `wind_mobile` 的模块文档——通道语义与踩过的坑都记在那里，这里不复述。

use std::path::{Path, PathBuf};
use std::sync::Arc;

use wind_mobile::{
    CandidateFrame, CandidateWindow as CoreCandidateWindow, EditOp as CoreEditOp,
    InputStatus as CoreStatus, KeyOutcome, KeySource as CoreKeySource, LayoutEntry, MobileCommand,
    MobileCore, MobileEventSink, PressKey as CorePressKey, SchemaEntry,
    TextSegment as CoreTextSegment,
};

pub mod scheme;
pub use scheme::{SchemeDeleteInfo, SchemeImportInfo, SchemePreviewInfo};

uniffi::setup_scaffolding!();

/// 初始化 Rust 日志 → logcat（tag: WindRust）。重复调用无害。
///
/// 默认 `info`；调更细可在 Kotlin 侧传 "wind_coordinator=debug" 之类的过滤串。
#[uniffi::export]
pub fn init_logging(filter: String) {
    #[cfg(target_os = "android")]
    {
        use tracing_subscriber::layer::SubscriberExt;
        use tracing_subscriber::util::SubscriberInitExt;
        let filter = if filter.is_empty() {
            "info".to_string()
        } else {
            filter
        };
        let _ = tracing_subscriber::registry()
            .with(tracing_subscriber::EnvFilter::new(filter))
            .with(tracing_android::layer("WindRust").expect("android tracing layer"))
            .try_init();
    }
    #[cfg(not(target_os = "android"))]
    let _ = filter;
}

/// 一次编辑操作（[`wind_mobile::EditOp`] 的 FFI 投影）。
///
/// UniFFI 的枚举载荷支持有限，此处摊平成带 `kind` 判别位的 Record：
/// Kotlin 侧一个 `when(kind)` 分派到对应的 `InputConnection` 调用。
#[derive(Debug, uniffi::Record)]
pub struct EditOp {
    /// 0=Commit 1=SetComposition 2=DeleteBackward 3=ReplaceBackward 4=MoveCursor
    pub kind: u8,
    /// Commit/SetComposition/ReplaceBackward 的文本
    pub text: String,
    /// SetComposition 的插入符（字符数）；DeleteBackward/ReplaceBackward 的字符数
    pub count: u32,
    /// MoveCursor 的位移（正右负左）
    pub delta: i32,
}

/// 按键事件的同步结果。
///
/// 从「上屏文本 + 组合区」这种**三元组投影**改成**指令流**：前者只装得下 InsertText 族，
/// 智能符号配对、回退替换、配对跳出这些动作在移动端被静默丢弃（不报错、不掉键，
/// 单纯功能没有）。指令流由核心的穷尽映射产出，新增动作会在核心编译期报错。
#[derive(Debug, Default, uniffi::Record)]
pub struct KeyResult {
    /// 是否被引擎消费（false = 宿主执行默认行为，吃键契约与桌面一致）
    pub consumed: bool,
    /// 按序执行的编辑指令
    pub ops: Vec<EditOp>,
    /// 模式是否变化（宿主据此刷新指示器；具体状态走推送通道）
    pub mode_changed: bool,
}

impl From<KeyOutcome> for KeyResult {
    fn from(o: KeyOutcome) -> Self {
        Self {
            consumed: o.consumed,
            // TimingHint 在移动端直接忽略：ops 里已含等价降级序列
            // （见 wind-host `TimingHint` 的说明）。
            ops: o.ops.into_iter().map(EditOp::from).collect(),
            mode_changed: o.mode_changed,
        }
    }
}

impl From<CoreEditOp> for EditOp {
    fn from(op: CoreEditOp) -> Self {
        match op {
            CoreEditOp::Commit(text) => Self {
                kind: 0,
                text,
                count: 0,
                delta: 0,
            },
            CoreEditOp::SetComposition { text, caret } => Self {
                kind: 1,
                text,
                count: caret as u32,
                delta: 0,
            },
            CoreEditOp::DeleteBackward { count } => Self {
                kind: 2,
                text: String::new(),
                count: count as u32,
                delta: 0,
            },
            CoreEditOp::ReplaceBackward { count, text } => Self {
                kind: 3,
                text,
                count: count as u32,
                delta: 0,
            },
            CoreEditOp::MoveCursor { delta } => Self {
                kind: 4,
                text: String::new(),
                count: 0,
                delta,
            },
        }
    }
}

/// 候选帧：编码栏与候选栏一次渲染所需的全部数据。
#[derive(Debug, uniffi::Record)]
pub struct CandidateUpdate {
    /// 编码区串（五笔码 / 拼音串），编码栏正文
    pub preedit: String,
    /// 编码区插入符位置：`preedit` 内的**字节**偏移（恒在字符边界）
    pub preedit_caret: u32,
    /// 模式指示文本（五/拼/英/符…），编码栏左侧角标；空 = 不显示
    pub mode_label: String,
    /// 是否在「打字中」：有组合串且不是联想态
    pub composing: bool,
    /// 当前页是否有候选。候选文本不在帧里，需要时经 [`WindCore::candidates`] 拉取
    pub has_candidates: bool,
    /// 键盘选中项（页内下标），空格上屏目标
    pub selected: u32,
    /// 当前页（1 起）
    pub page: u32,
    /// 总页数
    pub total_pages: u32,
}

impl From<CandidateFrame> for CandidateUpdate {
    fn from(f: CandidateFrame) -> Self {
        Self {
            preedit: f.preedit,
            preedit_caret: f.preedit_caret as u32,
            mode_label: f.mode_label,
            composing: f.composing,
            has_candidates: f.has_candidates,
            selected: f.selected as u32,
            page: f.page as u32,
            total_pages: f.total_pages as u32,
        }
    }
}

/// 引擎当前模式真值。**数据，不是界面**——工具栏画几个格、放哪、什么图标由 Kotlin 决定。
#[derive(Debug, uniffi::Record)]
pub struct InputStatus {
    pub chinese_mode: bool,
    /// 键是否按拉丁字母直出：英文方案、中文模式关闭、或不可输入（密码框等）
    pub latin_input: bool,
    /// 方案短称：中文态取方案 icon_label（"五"/"拼"），英文态为 "英"/大写锁定 "A"
    pub icon_label: String,
    pub full_width: bool,
    pub chinese_punct: bool,
    pub s2t_enabled: bool,
    /// 是否该展示简繁开关（用户未启用简繁功能时为 false）
    pub s2t_shown: bool,
}

impl From<CoreStatus> for InputStatus {
    fn from(s: CoreStatus) -> Self {
        Self {
            chinese_mode: s.chinese_mode,
            latin_input: s.latin_input,
            icon_label: s.icon_label,
            full_width: s.full_width,
            chinese_punct: s.chinese_punct,
            s2t_enabled: s.s2t_enabled,
            s2t_shown: s.s2t_shown,
        }
    }
}

/// 输入方案条目（方案选择器的数据源）
#[derive(Debug, uniffi::Record)]
pub struct SchemaInfo {
    /// 方案 id（wubi86 / pinyin …）
    pub id: String,
    /// 显示名（方案文件 `[schema] name`）
    pub name: String,
    /// 短称（"五"/"拼"）
    pub icon_label: String,
    pub active: bool,
}

/// Kotlin 可下达的引擎命令。
///
/// 刻意是一张**手工挑选的小表**而非桌面菜单的镜像：后者含截图、打开目录、诊断 HUD、
/// 语言栏图标等一批桌面专属项，暴露给移动端只会制造调不通的入口。
/// 新增移动端要用的开关时，先在 [`wind_mobile::MobileCommand`] 加一条，再在此投影。
#[derive(Debug, uniffi::Enum)]
pub enum EngineCommand {
    /// 切到英文模式（中→英）
    English,
    /// 选中第 N 个方案（下标对齐 [`WindCore::schemas`]），并回到中文模式（英→中）
    SelectSchema { index: u32 },
    /// 中文标点 ↔ 英文标点
    TogglePunct,
    /// 全角 ↔ 半角
    ToggleWidth,
    /// 简入繁出开关
    ToggleS2t,
    /// 重载用户配置（设置页改完即时生效）
    ReloadConfig,
    /// 设置双拼布局（`id` 取自 [`WindCore::shuangpin_layouts`]）
    SetShuangpinLayout { id: String },
}

impl From<EngineCommand> for MobileCommand {
    fn from(cmd: EngineCommand) -> Self {
        match cmd {
            EngineCommand::English => MobileCommand::English,
            EngineCommand::SelectSchema { index } => MobileCommand::SelectSchema(index as usize),
            EngineCommand::TogglePunct => MobileCommand::TogglePunct,
            EngineCommand::ToggleWidth => MobileCommand::ToggleWidth,
            EngineCommand::ToggleS2t => MobileCommand::ToggleS2t,
            EngineCommand::ReloadConfig => MobileCommand::ReloadConfig,
            EngineCommand::SetShuangpinLayout { id } => MobileCommand::SetShuangpinLayout(id),
        }
    }
}

/// 按键来源（见 [`wind_mobile::KeySource`]）。
#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum KeySource {
    Touch,
    Physical,
}

impl From<KeySource> for CoreKeySource {
    fn from(s: KeySource) -> Self {
        match s {
            KeySource::Touch => CoreKeySource::Touch,
            KeySource::Physical => CoreKeySource::Physical,
        }
    }
}

/// 宿主报上来的按键（见 [`wind_mobile::PressKey`]）。
///
/// `Char.ch` 取首个字符；空串或多字符视为不接（`press` 返回未吃键、无编辑指令）。
#[derive(Debug, Clone, uniffi::Enum)]
pub enum PressKey {
    Char { ch: String, shifted: bool },
    Space,
    Backspace,
    Enter,
}

/// 候选全量的一个窗口（滚动候选栏 / 展开面板的数据源）。
///
/// 推送帧 [`CandidateUpdate`] 给的是**当页**候选，够画编码栏与页码，但装不下一条
/// 可滚动的长列表。宿主要更多时按 `(offset, limit)` 来拉——核心侧另有硬上限。
#[derive(Debug, uniffi::Record)]
pub struct CandidateWindow {
    pub items: Vec<String>,
    /// 窗口起点（原样回送）
    pub offset: u32,
    /// **全量**候选总数：Kotlin 据此判断还要不要续取
    pub total: u32,
    /// 当前高亮候选的**绝对**下标（空格上屏的目标）
    pub selected: u32,
    /// 与 items 一一对应的注释灰字（码表剩余编码、拼音候选反查码…）；
    /// 只有 [`WindCore::candidates_with_comments`] 填，否则为空
    pub comments: Vec<String>,
    /// 与 items 一一对应的显示用分段正文；某条为空 = 按 items 那条纯文字画。
    /// 目前只有正确拼音提示：items「苹果」→「苹」「(ping)」「果」，提示段 role = `pinyin_hint`
    pub text_segments: Vec<Vec<TextSegment>>,
    /// 本次拉取触发了核心扩容重建：前面已取到的候选可能变了，宿主须整份重取已显示的范围
    pub rebuilt: bool,
}

/// 分段正文的一段：`role` 为角色名（如 `pinyin_hint`），`None` = 正文。字号与正文相同，按角色取色。
#[derive(Debug, uniffi::Record)]
pub struct TextSegment {
    pub text: String,
    pub role: Option<String>,
}

impl From<CoreTextSegment> for TextSegment {
    fn from(s: CoreTextSegment) -> Self {
        Self {
            text: s.text,
            role: s.role,
        }
    }
}

impl From<CoreCandidateWindow> for CandidateWindow {
    fn from(w: CoreCandidateWindow) -> Self {
        Self {
            items: w.items,
            offset: w.offset as u32,
            total: w.total as u32,
            selected: w.selected as u32,
            comments: w.comments,
            text_segments: w
                .text_segments
                .into_iter()
                .map(|segs| segs.into_iter().map(TextSegment::from).collect())
                .collect(),
            rebuilt: w.rebuilt,
        }
    }
}

/// 一个双拼布局。
#[derive(Debug, uniffi::Record)]
pub struct LayoutInfo {
    /// 文件名 stem，即写回 [`EngineCommand::SetShuangpinLayout`] 的值
    pub id: String,
    /// 显示名（布局文件 `[meta].name`），如「小鹤双拼」
    pub name: String,
    pub active: bool,
}

impl From<LayoutEntry> for LayoutInfo {
    fn from(l: LayoutEntry) -> Self {
        Self {
            id: l.id,
            name: l.name,
            active: l.active,
        }
    }
}

impl From<SchemaEntry> for SchemaInfo {
    fn from(s: SchemaEntry) -> Self {
        Self {
            id: s.id,
            name: s.name,
            icon_label: s.icon_label,
            active: s.active,
        }
    }
}

/// 反向通道回调（核心的泵线程调用，**非主线程**，Kotlin 侧自行 post）
#[uniffi::export(with_foreign)]
pub trait UiEventListener: Send + Sync {
    /// 候选/编码区更新
    fn on_candidates(&self, update: CandidateUpdate);
    /// 候选清空/隐藏
    fn on_hide_candidates(&self);
    /// 模式真值变化（中英/标点/全半角/简繁/方案短称）
    fn on_status(&self, status: InputStatus);
    /// 一次性提示（方案切换/模式切换/词库就绪/错误）
    fn on_toast(&self, text: String);
}

/// 把核心的 [`MobileEventSink`] 接到 UniFFI 的外部实现回调上。
///
/// 这层适配存在的唯一理由是**方向**：`MobileEventSink` 是普通 Rust trait，
/// `UiEventListener` 是 UniFFI 导出的外部可实现 trait，两者不能是同一个类型
/// （否则 wind-mobile 就得依赖 uniffi，"接口层"退化成"Android 实现层"）。
struct ListenerSink(Arc<dyn UiEventListener>);

impl MobileEventSink for ListenerSink {
    fn on_candidates(&self, frame: CandidateFrame) {
        self.0.on_candidates(frame.into());
    }

    fn on_hide_candidates(&self) {
        self.0.on_hide_candidates();
    }

    fn on_status(&self, status: CoreStatus) {
        self.0.on_status(status.into());
    }

    fn on_toast(&self, text: String) {
        self.0.on_toast(text);
    }
}

/// 输入法核心会话：一个实例对应一个 InputMethodService 生命周期
#[derive(uniffi::Object)]
pub struct WindCore {
    core: Arc<MobileCore>,
    /// 方案导入 / 删除串行化：两者都是「改磁盘 → 刷新列表 → 重载」，交错会让刷新读到半截状态
    scheme_lock: std::sync::Mutex<()>,
    /// 方案覆盖文件（`schema_overrides/{id}.toml`）的读改写串行化：扩展词库开关与方案设置
    /// 写的是同一个文件，两个写入并行时后写的会把先写的落盘结果盖掉
    override_lock: std::sync::Mutex<()>,
}

impl WindCore {
    /// `(用户层 schemas, 只读层 schemas 列表)`
    fn scheme_dirs(&self) -> Result<(PathBuf, Vec<PathBuf>), ConfigError> {
        let (user, system) = self.core.scheme_dirs();
        let user = user.ok_or_else(|| ConfigError::Rejected {
            reason: "取不到用户数据目录".to_string(),
        })?;
        Ok((user, system))
    }

    fn all_schema_ids(&self) -> Vec<String> {
        self.core
            .installed_schemas()
            .into_iter()
            .map(|s| s.id)
            .collect()
    }

    /// 这次改动要使哪些方案的引擎缓存失效：方案文件换了，就是这些方案（混输成员由核心扇出）；
    /// 只要动了方案文件以外的资源（词库、拆字表……），引用它的是哪些方案要解析各方案才知道，
    /// 保守起见对所有已装方案失效——这是低频操作，重建在下次使用时才发生。
    fn affected_schemes(&self, schema_ids: &[String], files: &[String]) -> Vec<String> {
        let only_schema_files = files
            .iter()
            .all(|f| !f.contains('/') && f.ends_with(".schema.toml"));
        if only_schema_files {
            schema_ids.to_vec()
        } else {
            let mut all = self.all_schema_ids();
            all.extend(schema_ids.iter().cloned());
            all
        }
    }

    /// 方案设置的写口：与扩展词库开关共用覆盖文件锁；成功后重载配置（引擎已由核心失效）。
    fn write_schema_settings(
        &self,
        write: impl FnOnce(&MobileCore) -> Result<(), String>,
    ) -> Result<(), ConfigError> {
        {
            let _guard = self.override_lock.lock().unwrap_or_else(|e| e.into_inner());
            write(&self.core).map_err(|reason| ConfigError::Rejected { reason })?;
        }
        self.core.run_command(MobileCommand::ReloadConfig);
        Ok(())
    }

    /// 方案包导入 / 删除落盘后：使涉及的方案缓存失效、重扫已装方案，再重载配置让可选方案与引擎跟上。
    fn after_scheme_change(&self, ids: &[String]) {
        self.core.on_schemes_changed(ids);
        self.core.run_command(MobileCommand::ReloadConfig);
    }
}

#[uniffi::export]
impl WindCore {
    /// 构造核心。
    ///
    /// - `data_dir`: 出厂数据目录（schemas/ 所在，即 Android filesDir/data）
    /// - `user_root`: KunKunIME 0.132 核心的数据根（Android 私有 filesDir/rust）。
    ///   核心从这里派生 config/KunKunIME、data/KunKunIME、cache，并将用户词库存于根下。
    /// - `local_root`: 保留上游 UniFFI 五参数签名，避免 Kotlin 绑定漂移；0.132 核心
    ///   没有独立本地目录参数，此值不参与路径解析。宿主传 user_root/data/KunKunIME。
    /// - `schema`: **首装兜底**方案 id（如 "wubi86"）；用户上次选过的优先
    #[uniffi::constructor]
    pub fn new(
        data_dir: String,
        user_root: String,
        local_root: String,
        schema: String,
        listener: Arc<dyn UiEventListener>,
    ) -> Arc<Self> {
        // 当前共享核心仍是四参数 API。路径兼容只在 Android 边界完成，不改桌面核心。
        let _ = local_root;
        // KunKunIME 的 variant::install_root 已使用产品自己的变量名；0.132 移动门面
        // 仍设置 WIND_INSTALL_ROOT。构造前补上正确变量，使出厂配置剪枝与重置能读到 data。
        // 与核心 XDG 挂载相同：一个 App 进程只能有一个会话，且必须在后台单次初始化。
        let data_path = Path::new(&data_dir);
        match (data_path.file_name(), data_path.parent()) {
            (Some(name), Some(install_root)) if name == "data" => {
                unsafe { std::env::set_var("KUNKUN_INSTALL_ROOT", install_root) };
            }
            _ => {}
        }
        let core = MobileCore::new(
            data_path,
            Path::new(&user_root),
            &schema,
            Arc::new(ListenerSink(listener)),
        );
        Arc::new(Self {
            core,
            scheme_lock: std::sync::Mutex::new(()),
            override_lock: std::sync::Mutex::new(()),
        })
    }

    /// 按下键（vk = wind-keys VK 码，`modifiers` 为位掩码）。
    ///
    /// 抬起事件骨架期不喂（协调器按键链以 down 为主）。吃键判定在核心内部完成，
    /// K 侧**不要**自己判断哪些键该拦——`consumed=false` 就照系统默认行为走。
    pub fn key_down(&self, vk: u32, modifiers: u32) -> KeyResult {
        self.core.key_down(vk, modifiers).into()
    }

    /// 按键：字符/空格/退格/回车，翻译与英文自动空格都在核心里。
    ///
    /// 返回的 `ops` 已含补的空格与标点换位，宿主按序执行即可；`consumed=false` 时
    /// 执行 `ops` 后走默认行为。
    ///
    /// **前置条件**：宿主处于直出（密码框等 `directInput`）时**不得调用**——核心的
    /// `latin_input()` 含密码框强制英文，调用会让按键进编码缓冲而不是直接上屏。
    pub fn press(&self, key: PressKey, source: KeySource) -> KeyResult {
        let key = match key {
            PressKey::Char { ch, shifted } => {
                let mut it = ch.chars();
                match (it.next(), it.next()) {
                    (Some(ch), None) => CorePressKey::Char { ch, shifted },
                    _ => return KeyResult::default(),
                }
            }
            PressKey::Space => CorePressKey::Space,
            PressKey::Backspace => CorePressKey::Backspace,
            PressKey::Enter => CorePressKey::Enter,
        };
        self.core.press(key, source.into()).into()
    }

    /// 「原样上屏」键（九宫数字页的数字、句点、左列符号等）：快捷输入等文本缓冲模式下
    /// 交给引擎进缓冲；其余情况 `consumed=false`，宿主照旧原样上屏（有编码先顶首选）。
    /// 前置条件同 [`Self::press`]：直出时不得调用。
    pub fn press_literal(&self, text: String) -> KeyResult {
        self.core.press_literal(&text).into()
    }

    /// 英文词后是否自动补空格（默认开）。
    pub fn set_auto_space(&self, on: bool) {
        self.core.set_auto_space(on);
    }

    /// 触摸键盘的 `;` 是否进快捷输入（默认关：当普通标点）。物理键盘不受影响。
    pub fn set_touch_quick_trigger(&self, on: bool) {
        self.core.set_touch_quick_trigger(on);
    }

    /// 焦点获得（对应 onStartInput）。
    ///
    /// 词库预热在核心构造时就已启动（不在这里），所以本调用是廉价的。
    pub fn focus_gained(&self) {
        self.core.focus_gained();
    }

    /// 报告光标前的正文（不含组合区），联想据此把逐字上屏拆开的词凑回来。
    /// 光标被挪动或正文被别人改过之后报（onUpdateSelection）、换输入框时报一次；
    /// 自己上屏的不必报，核心已按下发的编辑指令跟上。读不到报空串。
    pub fn set_preceding_text(&self, text: String) {
        self.core.set_preceding_text(text);
    }

    /// 暂停 / 恢复学习（无痕）。宿主每次 onStartInput 都要按当前输入框显式传一次：
    /// `IME_FLAG_NO_PERSONALIZED_LEARNING` 或密码框传 true，其余传 false——开关不落盘，
    /// 不传就会把上一个无痕框的暂停带进普通输入框。暂停期间照常出候选，只是什么都不记
    /// （词频、造词、联想、统计、上屏历史）；用户在设置页手动加词不受影响。
    pub fn set_learning_suspended(&self, on: bool) {
        self.core.set_learning_suspended(on);
    }

    /// 焦点丢失（对应 onFinishInput）。语义 = 真正离开，清输入态。
    pub fn focus_lost(&self) {
        self.core.focus_lost();
    }

    /// 下达引擎命令。状态变化经 [`UiEventListener::on_status`] 异步回报，不在此处同步返回
    /// ——同一个变化也可能由按键或焦点事件引发，只留推送一条路，K 侧才不会有两份真值。
    pub fn run_command(&self, cmd: EngineCommand) {
        self.core.run_command(cmd.into());
    }

    /// 可选方案列表。下标即 [`EngineCommand::SelectSchema`] 的 `index`
    /// ——顺序取自引擎管理器，与协调器内部的选择下标同源，K 侧不要自行排序。
    pub fn schemas(&self) -> Vec<SchemaInfo> {
        self.core
            .schemas()
            .into_iter()
            .map(SchemaInfo::from)
            .collect()
    }

    /// 活跃方案此刻的手动音节分隔符（`"'"` 或 `` "`" ``）；`None` = 没有，K 侧不该摆「分词」键。
    /// 随方案、`separator` 设置、五笔整句开关变化，每次要用时再问，别缓存。纯读。
    pub fn separator_key(&self) -> Option<String> {
        self.core.separator_key().map(|c| c.to_string())
    }

    /// 拉取候选全量的一个窗口。**纯读**，不改状态，可随时调。
    ///
    /// 别在每次按键都拉一大段：序列化与 K 侧的文字测量都在主线程上。
    /// 起手拉够铺满一两屏即可，用户滚到尾部再续取。
    pub fn candidates(&self, offset: u32, limit: u32) -> CandidateWindow {
        self.core.candidates(offset as usize, limit as usize).into()
    }

    /// 同 [`Self::candidates`]，另带每条候选的注释灰字（与电脑版候选窗同一份模板）。
    pub fn candidates_with_comments(&self, offset: u32, limit: u32) -> CandidateWindow {
        self.core
            .candidates_with_comments(offset as usize, limit as usize)
            .into()
    }

    /// 按**绝对下标**选词，返回编辑指令流（与 [`Self::key_down`] 同一种形状）。
    ///
    /// `index` 是全量候选里的绝对下标，不是页内的——取自 [`Self::candidates`] 拉到的
    /// 窗口。取代此前「合成数字键」的做法：那条路把选词限死在页内 1-9，第 10 个及
    /// 以后永远点不到，候选栏的滚动也就失去了意义。
    pub fn select_candidate(&self, index: u32) -> KeyResult {
        self.core.select_candidate(index as usize).into()
    }

    /// 同 [`Self::select_candidate`]，但英文组码选词后**不补空格**。
    /// 宿主紧接着自己上屏表情 / 符号 / 数字键盘字符时用。
    pub fn select_candidate_plain(&self, index: u32) -> KeyResult {
        self.core.select_candidate_plain(index as usize).into()
    }

    /// 首单元的几种读法（展开页左栏画它）：同一串字母的不同切分、模糊音兄弟、只取声母。
    /// 没得选 / 非全拼 / 无编码时为空。不改编码，只影响候选。
    pub fn leading_readings(&self) -> Vec<LeadingReadingInfo> {
        self.core
            .leading_readings()
            .into_iter()
            .map(|r| LeadingReadingInfo {
                label: r.label,
                head: r.head,
                orig_len: r.orig_len as u32,
                selected: r.selected,
            })
            .collect()
    }

    /// 选定首单元的一种读法，候选按它重算。读法已过期（编码变了）时返回空。
    pub fn set_leading_reading(&self, head: String, orig_len: u32) -> Option<KeyResult> {
        self.core
            .set_leading_reading(&head, orig_len as usize)
            .map(Into::into)
    }

    /// 候选长按菜单：按候选类型给出菜单项（文案、启用态与电脑版右键菜单同一份）。
    /// `index` 是绝对下标；无候选或越界时为空。
    pub fn candidate_menu(&self, index: u32) -> Vec<CandidateMenuItemInfo> {
        self.core
            .candidate_menu(index as usize)
            .into_iter()
            .map(|m| CandidateMenuItemInfo {
                label: m.label,
                action: m.action,
                enabled: m.enabled,
            })
            .collect()
    }

    /// 第 `index`（全量绝对下标）个候选的提示（编码 / 读音 / 释义…），与电脑版候选悬停气泡
    /// 同一份段模板。越界或全空返回空列表。长按候选时取来显示。
    pub fn candidate_tooltip(&self, index: u32) -> Vec<CandidateTooltipInfo> {
        self.core
            .candidate_tooltip(index as usize)
            .into_iter()
            .map(|s| CandidateTooltipInfo {
                title: s.title,
                inline: s.inline,
                lines: s.lines,
            })
            .collect()
    }

    /// 执行一项菜单动作（`move_top` / `delete` / `reset` …）；`copy` 由宿主自己做，这里返回 false。
    /// 执行后核心经 [`UiEventListener`] 推送新的候选。
    pub fn candidate_menu_run(&self, index: u32, action: String) -> bool {
        self.core.candidate_menu_run(index as usize, &action)
    }

    /// 可选双拼布局。清单由核心扫描目录得出，**K 侧不要硬编码**——
    /// 用户可以往 `schemas` 的 `shuangpin` 子目录里丢自己的布局文件，硬编码清单看不见它。
    pub fn shuangpin_layouts(&self) -> Vec<LayoutInfo> {
        self.core
            .shuangpin_layouts()
            .into_iter()
            .map(LayoutInfo::from)
            .collect()
    }

    // ── 设置页：配置读写 ─────────────────────────────────────────────
    //
    // 设置页的引擎项**只能走这里**：K 侧自己的 `filesDir/user/config.toml` 引擎根本不读，
    // 本版本核心的用户层在 `user_root/config/KunKunIME/config.toml`。

    /// 当前生效配置的全量快照（TOML 文本，四层合并 + 移动端强制项）。
    pub fn config_snapshot(&self) -> String {
        self.core.config_snapshot()
    }

    /// 出厂默认配置的全量快照（TOML 文本）。「恢复默认」与「和默认不同」的标注用它。
    pub fn config_defaults(&self) -> String {
        self.core.config_defaults()
    }

    /// 配置字段注册表：键 → 类型（+ 枚举值域）。K 侧加载设置清单时用它校验键名。
    pub fn config_registry(&self) -> Vec<ConfigField> {
        self.core
            .config_registry()
            .into_iter()
            .map(|f| ConfigField {
                key: f.key,
                ty: f.ty,
                options: f.options,
            })
            .collect()
    }

    /// 写一个配置键到用户层。**只写盘**，生效要另发 [`EngineCommand::ReloadConfig`]。
    pub fn config_set(&self, key: String, value: ConfigValue) -> Result<(), ConfigError> {
        self.core
            .config_set(&key, value.into())
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 方案的附加词库（拼音细胞词库、五笔 Emoji / 行政区域…）及当前开关；不含主库。
    pub fn schema_dicts(&self, schema_id: String) -> Vec<SchemaDictItem> {
        self.core
            .schema_dicts(&schema_id)
            .into_iter()
            .map(|d| SchemaDictItem {
                id: d.id,
                label: d.label,
                description: d.description,
                enabled: d.enabled,
            })
            .collect()
    }

    /// 开 / 关一个附加词库：落盘到方案覆盖并即时生效（已加载的引擎热切换或重建）。
    pub fn set_schema_dict_enabled(
        &self,
        schema_id: String,
        dict_id: String,
        on: bool,
    ) -> Result<(), ConfigError> {
        let _guard = self.override_lock.lock().unwrap_or_else(|e| e.into_inner());
        self.core
            .set_schema_dict_enabled(&schema_id, &dict_id, on)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    // ── 设置页：单个方案的设置（方案详情页） ───────────────────────────

    /// 方案信息头与按方案类型筛好的设置项（自定义行为 / 码表覆盖 / 辅助码 / 整句输入 / 双拼布局）。
    /// 未知方案 → Rejected。数据落点与 PC 同一份 `schema_overrides/{id}.toml`。
    pub fn schema_settings(&self, schema_id: String) -> Result<SchemaSettingsInfo, ConfigError> {
        self.core
            .schema_settings(&schema_id)
            .map(SchemaSettingsInfo::from)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 写一项方案设置（值一律字符串：`"true"` / `"3"` / `"english"`），等于方案文件原值即删键。
    /// 写完使该方案引擎失效并重载配置。只接受 [`Self::schema_settings`] 列出的键。
    pub fn set_schema_setting(
        &self,
        schema_id: String,
        key: String,
        value: String,
    ) -> Result<(), ConfigError> {
        self.write_schema_settings(|core| core.set_schema_setting(&schema_id, &key, Some(&value)))
    }

    /// 恢复一项：删掉覆盖层的这个键，回到方案文件 / 全局的值。
    pub fn reset_schema_setting(&self, schema_id: String, key: String) -> Result<(), ConfigError> {
        self.write_schema_settings(|core| core.set_schema_setting(&schema_id, &key, None))
    }

    /// 全部恢复默认：删掉详情页管理的全部键。扩展词库开关等覆盖层里的其它内容保留。
    pub fn reset_schema_settings(&self, schema_id: String) -> Result<(), ConfigError> {
        self.write_schema_settings(|core| core.reset_schema_settings(&schema_id))
    }

    /// emoji 附加模块（内置 + 用户放进 `emoji/` 的表）及当前开关。
    pub fn emoji_modules(&self) -> Vec<EmojiModuleInfo> {
        self.core
            .emoji_modules()
            .into_iter()
            .map(|m| EmojiModuleInfo {
                id: m.id,
                label: m.label,
                description: m.description,
                user: m.user,
                enabled: m.enabled,
            })
            .collect()
    }

    /// 打开 / 关闭一个 emoji 附加模块。只写盘，生效要另发 [`EngineCommand::ReloadConfig`]。
    pub fn set_emoji_module(&self, id: String, on: bool) -> Result<(), ConfigError> {
        self.core
            .set_emoji_module(&id, on)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 全部已装方案（含没启用的）及启用状态（设置页「启用的方案」）。
    pub fn installed_schemas(&self) -> Vec<InstalledSchemaInfo> {
        let (user_dir, system_dirs) = self.core.scheme_dirs();
        self.core
            .installed_schemas()
            .into_iter()
            .map(|s| {
                let (user, builtin) = scheme::layers_of(&s.id, user_dir.as_deref(), &system_dirs);
                InstalledSchemaInfo {
                    id: s.id,
                    name: s.name,
                    enabled: s.enabled,
                    locked: s.locked,
                    user,
                    builtin,
                }
            })
            .collect()
    }

    /// 预览一个方案包（只读，不落盘）。`path` 是应用私有目录里的文件，不是 `content://`。
    pub fn scheme_preview(&self, path: String) -> Result<SchemePreviewInfo, ConfigError> {
        let (user, system) = self.scheme_dirs()?;
        scheme::preview(Path::new(&path), &user, &system)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 导入方案包。`replace=false` 时已存在的文件跳过。成功后使引擎缓存失效、刷新已装方案并重载配置。
    /// 包内 `config_patch.toml` 本版本不应用。导入与删除串行：同一时刻只跑一个。
    pub fn scheme_import(
        &self,
        path: String,
        replace: bool,
    ) -> Result<SchemeImportInfo, ConfigError> {
        let _guard = self.scheme_lock.lock().unwrap_or_else(|e| e.into_inner());
        let (user, _) = self.scheme_dirs()?;
        let r = scheme::import(Path::new(&path), &user, replace);
        // 中途失败也要刷新：落盘是逐文件的，失败时磁盘可能已经变了一部分，无从得知是哪些方案
        // 受影响，保守起见全部失效
        let ids = match &r {
            Ok(i) => self.affected_schemes(&i.schema_ids, &i.imported),
            Err(_) => self.all_schema_ids(),
        };
        self.after_scheme_change(&ids);
        r.map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 删除用户层方案（用户自己导入的，或覆盖了自带方案的那份——即「恢复自带版本」）。
    /// 共享文件保留，只读层永不删；用户层文件在只读层有同路径副本的不受共享保护。
    pub fn scheme_delete(&self, id: String) -> Result<SchemeDeleteInfo, ConfigError> {
        let _guard = self.scheme_lock.lock().unwrap_or_else(|e| e.into_inner());
        let (user, system) = self.scheme_dirs()?;
        let keep: Vec<String> = self
            .core
            .installed_schemas()
            .into_iter()
            .map(|s| s.id)
            .filter(|s| *s != id)
            .collect();
        let r = scheme::delete(&id, &user, &system, &keep);
        let ids = match &r {
            Ok(i) => {
                let mut changed = i.schema_ids.clone();
                changed.push(id);
                self.affected_schemes(&changed, &i.deleted)
            }
            Err(_) => self.all_schema_ids(),
        };
        self.after_scheme_change(&ids);
        r.map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 启用 / 停用一个已装方案。英文与最后一个中文方案关不掉（Rejected 里写原因）。
    /// 只写盘，生效要另发 [`EngineCommand::ReloadConfig`]。
    pub fn set_schema_enabled(&self, id: String, on: bool) -> Result<(), ConfigError> {
        self.core
            .set_schema_enabled(&id, on)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 候选提示（长按候选那张卡）的各段及当前开关，顺序即显示顺序。
    pub fn tooltip_sections(&self) -> Vec<TooltipSectionStateInfo> {
        self.core
            .tooltip_sections()
            .into_iter()
            .map(|s| TooltipSectionStateInfo {
                label: s.label,
                title: s.title,
                enabled: s.enabled,
            })
            .collect()
    }

    /// 打开 / 关闭一段候选提示（`label` 取 [`Self::tooltip_sections`] 给的原文）。
    /// 只写盘，生效要另发 [`EngineCommand::ReloadConfig`]。
    pub fn set_tooltip_section_enabled(&self, label: String, on: bool) -> Result<(), ConfigError> {
        self.core
            .set_tooltip_section_enabled(&label, on)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 快捷输入的候选来源（内置 quick_mix 的成员）：当前生效序与出厂序。
    /// 成员有无即开关、顺序即候选优先级；界面行序与保留未知成员的规则在 K 侧。
    pub fn quick_mix_members(&self) -> QuickMixMembersInfo {
        let m = self.core.quick_mix_members();
        QuickMixMembersInfo {
            found: m.current.is_some(),
            current: m.current.unwrap_or_default(),
            defaults: m.defaults,
        }
    }

    /// 整列写回快捷输入的候选来源（触发键等其余字段不动）。
    /// 只写盘，生效要另发 [`EngineCommand::ReloadConfig`]。
    pub fn set_quick_mix_members(&self, members: Vec<String>) -> Result<(), ConfigError> {
        self.core
            .set_quick_mix_members(members)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    // ── 设置页：输入统计 ─────────────────────────────────────────────

    /// 概览汇总；没有用户库时为 `None`。算法与电脑版设置页同一份。
    pub fn stats_summary(&self) -> Option<StatsSummaryInfo> {
        self.core.stats_summary().map(StatsSummaryInfo::from)
    }

    /// `[from, to]`（`YYYY-MM-DD`，含首尾）每天的字数，缺的日子补零。
    pub fn stats_daily(&self, from: String, to: String) -> Vec<DailyTotalInfo> {
        self.core
            .stats_daily(&from, &to)
            .into_iter()
            .map(|d| DailyTotalInfo {
                date: d.date,
                total: d.total,
                chinese: d.chinese,
                english: d.english,
            })
            .collect()
    }

    /// 清空全部输入统计。
    pub fn stats_clear(&self) -> Result<(), ConfigError> {
        self.core
            .stats_clear()
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    // ── 设置页：词库（用户词）─────────────────────────────────────────
    //
    // 与电脑版设置页「词库」同一份实现（主仓 wind-coordinator 的用户词模块）：拼音码的
    // ü 归一、音节边界求解、数据方案折叠（拼音族共用一个库）都在核心里，这里只做形状转换。

    /// 某方案的用户词一页。`query` 为空列全部（按编码序），否则编码前缀命中在前、
    /// 词条内容 / 编码中段包含在后。
    pub fn user_words(
        &self,
        schema_id: String,
        query: String,
        offset: u32,
        limit: u32,
    ) -> UserWordPageInfo {
        let page = self.core.user_words(&schema_id, &query, offset, limit);
        UserWordPageInfo {
            items: page
                .items
                .into_iter()
                .map(|w| UserWordInfo {
                    code: w.code,
                    text: w.text,
                    weight: w.weight,
                })
                .collect(),
            total: page.total,
        }
    }

    /// 手动加词。拼音码可带空格声明音节切分（`ni hao`）。
    pub fn user_word_add(
        &self,
        schema_id: String,
        code: String,
        text: String,
    ) -> Result<(), ConfigError> {
        self.core
            .user_word_add(&schema_id, &code, &text)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 删一条用户词；`code` 直接传列表里显示的码（带音节空格也行）。
    pub fn user_word_remove(
        &self,
        schema_id: String,
        code: String,
        text: String,
    ) -> Result<(), ConfigError> {
        self.core
            .user_word_remove(&schema_id, &code, &text)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 全部命令短语（系统 + 用户，按编码升序）。
    pub fn phrases(&self) -> Vec<PhraseInfo> {
        self.core
            .phrases()
            .into_iter()
            .map(|p| PhraseInfo {
                code: p.code,
                text: p.text,
                enabled: p.enabled,
                is_system: p.is_system,
                preview: p.preview,
            })
            .collect()
    }

    /// 开 / 关一条短语（出厂关着的系统短语在这里打开）。
    pub fn phrase_set_enabled(
        &self,
        code: String,
        text: String,
        enabled: bool,
    ) -> Result<(), ConfigError> {
        self.core
            .phrase_set_enabled(&code, &text, enabled)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 加一条用户短语。
    pub fn phrase_add(&self, code: String, text: String) -> Result<(), ConfigError> {
        self.core
            .phrase_add(&code, &text)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 删一条用户短语（系统短语删不掉，只能关）。
    pub fn phrase_remove(&self, code: String, text: String) -> Result<(), ConfigError> {
        self.core
            .phrase_remove(&code, &text)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 系统短语开关恢复出厂值，返回改动条数。
    pub fn phrases_restore_defaults(&self) -> Result<u64, ConfigError> {
        self.core
            .phrases_restore_defaults()
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 用户正在操作联想候选：`true` 暂停联想自动隐藏，`false` 松手后重新计时
    pub fn assoc_hold(&self, hold: bool) {
        self.core.assoc_hold(hold);
    }

    /// 联想学习数据（联想里选过的词 + 学到的「下一词」）的条数，所有方案合计。
    pub fn assoc_history_count(&self) -> u64 {
        self.core.assoc_history_count()
    }

    /// 清空全部方案的联想学习数据，返回删掉的条数。词频、用户词不动。
    pub fn assoc_history_clear(&self) -> Result<u64, ConfigError> {
        self.core
            .assoc_history_clear()
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 清空某方案的全部用户词，返回删掉的条数。
    pub fn user_words_clear(&self, schema_id: String) -> Result<u64, ConfigError> {
        self.core
            .user_words_clear(&schema_id)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 导出该方案的词库数据（个人词 / 临时词 / 词频 / 候选调整）为 .wdict 文本，与电脑版
    /// 「词库 → 导出」同一格式，电脑版能直接导入。宿主负责写到用户选的文件。
    pub fn dict_export(&self, schema_id: String) -> Result<String, ConfigError> {
        self.core
            .dict_export(&schema_id)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 统计方案的系统词库（条目、码长、重码、字集覆盖），回包为 JSON 文本，形态同电脑版
    /// 「词库统计」（`dict.systemStats`）。O(全表)，K 侧须在后台线程调用。
    /// 未知方案等 → Rejected(原因)。
    pub fn dict_stats(
        &self,
        schema_id: String,
        top_n: u32,
        refresh: bool,
    ) -> Result<String, ConfigError> {
        self.core
            .dict_stats(&schema_id, top_n, refresh)
            .map_err(|reason| ConfigError::Rejected { reason })
    }

    /// 导入词库文本：.wdict（电脑版 / 本机导出）或 Rime / TSV 词表；`replace` = 先清空再导入。
    /// 文件的引擎类型与方案不一致（五笔词库导入拼音）、只读模式 → Rejected(原因)。
    pub fn dict_import(
        &self,
        schema_id: String,
        content: String,
        replace: bool,
    ) -> Result<DictImportInfo, ConfigError> {
        let r = self
            .core
            .dict_import(&schema_id, &content, replace)
            .map_err(|reason| ConfigError::Rejected { reason })?;
        Ok(DictImportInfo {
            sections: r
                .sections
                .into_iter()
                .map(|s| DictImportSectionInfo {
                    key: s.key,
                    added: s.added,
                    updated: s.updated,
                    unchanged: s.unchanged,
                    imported: s.imported,
                    skipped: s.skipped,
                    rejected: s.rejected,
                })
                .collect(),
        })
    }
}

/// 配置字段（注册表一行）。`ty` ∈ bool / int / float / str / enum / str_list / map / struct_list。
#[derive(Debug, uniffi::Record)]
pub struct ConfigField {
    pub key: String,
    pub ty: String,
    /// `ty == "enum"` 时的值域
    pub options: Vec<String>,
}

/// 写入配置的值（[`wind_mobile::config_api::ConfigValue`] 的投影）。表格类（映射、结构列表）
/// 不在此列，那类项要专门的编辑器。
#[derive(Debug, uniffi::Enum)]
pub enum ConfigValue {
    Bool { v: bool },
    Int { v: i64 },
    Float { v: f64 },
    Str { v: String },
    StrList { v: Vec<String> },
}

impl From<ConfigValue> for wind_mobile::config_api::ConfigValue {
    fn from(v: ConfigValue) -> Self {
        use wind_mobile::config_api::ConfigValue as M;
        match v {
            ConfigValue::Bool { v } => M::Bool(v),
            ConfigValue::Int { v } => M::Int(v),
            ConfigValue::Float { v } => M::Float(v),
            ConfigValue::Str { v } => M::Str(v),
            ConfigValue::StrList { v } => M::StrList(v),
        }
    }
}

/// 设置写入失败（键不存在、类型不符、写盘失败），`reason` 是核心给的原文。
#[derive(Debug, uniffi::Error)]
pub enum ConfigError {
    Rejected { reason: String },
}

impl std::fmt::Display for ConfigError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ConfigError::Rejected { reason } => f.write_str(reason),
        }
    }
}

impl std::error::Error for ConfigError {}

/// 统计概览（[`wind_mobile::StatsSummary`] 的投影）。速度单位：字 / 分钟。
#[derive(Debug, uniffi::Record)]
pub struct StatsSummaryInfo {
    pub today_chars: u32,
    pub today_chinese: u32,
    pub today_english: u32,
    pub total_chars: u64,
    pub active_days: u64,
    pub daily_avg: u64,
    pub streak_current: u32,
    pub streak_max: u32,
    pub week_chars: u64,
    pub month_chars: u64,
    pub max_day_chars: u32,
    pub max_day_date: String,
    pub avg_code_len: f64,
    pub first_select_rate: f64,
    pub today_speed: u32,
    pub overall_speed: u32,
    pub max_speed: u32,
}

impl From<wind_mobile::StatsSummary> for StatsSummaryInfo {
    fn from(s: wind_mobile::StatsSummary) -> Self {
        Self {
            today_chars: s.today_chars,
            today_chinese: s.today_chinese,
            today_english: s.today_english,
            total_chars: s.total_chars,
            active_days: s.active_days,
            daily_avg: s.daily_avg,
            streak_current: s.streak_current,
            streak_max: s.streak_max,
            week_chars: s.week_chars,
            month_chars: s.month_chars,
            max_day_chars: s.max_day_chars,
            max_day_date: s.max_day_date,
            avg_code_len: s.avg_code_len,
            first_select_rate: s.first_select_rate,
            today_speed: s.today_speed,
            overall_speed: s.overall_speed,
            max_speed: s.max_speed,
        }
    }
}

/// 某一天的输入字数。
#[derive(Debug, uniffi::Record)]
pub struct DailyTotalInfo {
    /// `YYYY-MM-DD`
    pub date: String,
    pub total: u32,
    pub chinese: u32,
    pub english: u32,
}

/// 一条用户词。`code` 是显示码：拼音带音节空格（`ni hao`），码表原样。
#[derive(Debug, uniffi::Record)]
pub struct UserWordInfo {
    pub code: String,
    pub text: String,
    pub weight: i32,
}

/// 一条命令短语：系统短语只能开关，用户短语可删。
#[derive(Debug, uniffi::Record)]
pub struct PhraseInfo {
    pub code: String,
    pub text: String,
    pub enabled: bool,
    pub is_system: bool,
    /// 候选上显示的样子（日期按此刻展开、组展开成前几项）；求值落空为空串
    pub preview: String,
}

/// 用户词的一页与命中总数。
#[derive(Debug, uniffi::Record)]
pub struct UserWordPageInfo {
    pub items: Vec<UserWordInfo>,
    pub total: u64,
}

/// 词库导入结果的一段。用户词段（`userWords`）看 added / updated / unchanged，其余段看 imported；
/// skipped 是未导入的总数，rejected 是其中被拼音准入判为不合法的。
#[derive(Debug, uniffi::Record)]
pub struct DictImportSectionInfo {
    pub key: String,
    pub added: u64,
    pub updated: u64,
    pub unchanged: u64,
    pub imported: u64,
    pub skipped: u64,
    pub rejected: u64,
}

#[derive(Debug, uniffi::Record)]
pub struct DictImportInfo {
    pub sections: Vec<DictImportSectionInfo>,
}

/// 候选提示的一段：段名（`None` = 无标题）、是否与首行同行、原始行（不截断，宿主自己折行）。
#[derive(Debug, uniffi::Record)]
pub struct CandidateTooltipInfo {
    pub title: Option<String>,
    pub inline: bool,
    pub lines: Vec<String>,
}

/// 候选菜单的一项。`action` 是与核心约定的动作名，`label` 是显示文案。
/// 首单元的一种读法：`label` 是左栏显示的文字（双拼下是拼音），`head` 是写进查询的首单元形态
/// （双拼下是击键，回传核心时原样带上）；`orig_len` 是它占原始编码的字节数
#[derive(Debug, uniffi::Record)]
pub struct LeadingReadingInfo {
    pub label: String,
    pub head: String,
    pub orig_len: u32,
    pub selected: bool,
}

#[derive(Debug, uniffi::Record)]
pub struct CandidateMenuItemInfo {
    pub label: String,
    pub action: String,
    pub enabled: bool,
}

/// 一个已装方案：`locked` = 关不掉（英文是中英键切换的目标）。
#[derive(Debug, uniffi::Record)]
pub struct InstalledSchemaInfo {
    pub id: String,
    pub name: String,
    pub enabled: bool,
    pub locked: bool,
    /// 用户层有同名方案文件（用户导入的，或覆盖了自带方案的）：可删除 / 恢复自带版本
    pub user: bool,
    /// APK 自带层也有同名方案：`user && builtin` = 覆盖了自带（显示「恢复自带版本」），
    /// `!user && builtin` = 纯自带（不给删除）
    pub builtin: bool,
}

/// 候选提示的一段：`label` 是段名原文（开关时回传），`title` 是给人看的名字。
#[derive(Debug, uniffi::Record)]
pub struct TooltipSectionStateInfo {
    pub label: String,
    pub title: String,
    pub enabled: bool,
}

/// 快捷输入候选来源。`found = false`：配置里没有 quick_mix 实例（`current` 为空）。
#[derive(Debug, uniffi::Record)]
pub struct QuickMixMembersInfo {
    pub found: bool,
    pub current: Vec<String>,
    pub defaults: Vec<String>,
}

/// 一个 emoji 附加模块。`user` = 用户自己放进 `emoji/` 目录的表。
#[derive(Debug, uniffi::Record)]
pub struct EmojiModuleInfo {
    pub id: String,
    pub label: String,
    pub description: String,
    pub user: bool,
    pub enabled: bool,
}

/// 方案的一个附加词库。`id` 原样回传给 [`WindCore::set_schema_dict_enabled`]。
#[derive(Debug, uniffi::Record)]
pub struct SchemaDictItem {
    pub id: String,
    pub label: String,
    pub description: String,
    pub enabled: bool,
}

/// 方案设置项归哪张卡片（对应 PC 方案设置对话框的 TAB）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SchemaSettingGroup {
    /// 方案参数（双拼布局）
    Basic,
    /// 自定义行为：标点态 / 候选排列 / 是否加载短语
    Behavior,
    /// 方案覆盖：码表全局配置的方案级覆盖
    CodeTable,
    /// 辅助码：本方案是否启用
    AuxCode,
    /// 实验性功能：整句输入
    Experimental,
}

/// 方案设置项的值形态；值在 FFI 上一律是字符串。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SchemaSettingKind {
    Bool,
    Int,
    /// 取值在 `options` 里
    Choice,
}

/// 一项方案设置（[`wind_mobile::SchemaSetting`] 的投影）。
#[derive(Debug, uniffi::Record)]
pub struct SchemaSettingItem {
    /// 覆盖层点分路径，原样回传给 [`WindCore::set_schema_setting`]
    pub key: String,
    pub group: SchemaSettingGroup,
    pub kind: SchemaSettingKind,
    /// 当前生效值
    pub value: String,
    /// 恢复后的值（方案文件 ⊕ 全局基线）
    pub default_value: String,
    /// 用户覆盖层里有这个键
    pub overridden: bool,
    pub options: Vec<String>,
}

/// 方案信息头 + 设置项。`engine_type` ∈ pinyin / shuangpin / codetable / mixed / english。
#[derive(Debug, uniffi::Record)]
pub struct SchemaSettingsInfo {
    pub id: String,
    pub name: String,
    pub engine_type: String,
    pub version: String,
    pub author: String,
    pub description: String,
    pub items: Vec<SchemaSettingItem>,
}

impl From<wind_mobile::SchemaSettings> for SchemaSettingsInfo {
    fn from(s: wind_mobile::SchemaSettings) -> Self {
        use wind_mobile::{SchemaSettingGroup as G, SchemaSettingKind as K};
        Self {
            id: s.id,
            name: s.name,
            engine_type: s.engine_type,
            version: s.version,
            author: s.author,
            description: s.description,
            items: s
                .items
                .into_iter()
                .map(|i| SchemaSettingItem {
                    key: i.key,
                    group: match i.group {
                        G::Basic => SchemaSettingGroup::Basic,
                        G::Behavior => SchemaSettingGroup::Behavior,
                        G::CodeTable => SchemaSettingGroup::CodeTable,
                        G::AuxCode => SchemaSettingGroup::AuxCode,
                        G::Experimental => SchemaSettingGroup::Experimental,
                    },
                    kind: match i.kind {
                        K::Bool => SchemaSettingKind::Bool,
                        K::Int => SchemaSettingKind::Int,
                        K::Choice => SchemaSettingKind::Choice,
                    },
                    value: i.value,
                    default_value: i.default_value,
                    overridden: i.overridden,
                    options: i.options,
                })
                .collect(),
        }
    }
}
