//! Android FFI 的离线全拼 / 英文真实词库验收。
//!
//! Linux 宿主与 Android 同用 XDG 路径。直接读取单仓的 WindInput/data，或显式指定
//! KUNKUN_IME_TEST_DATA 指向 prepare-assets.py 生成的 assets/data。必需数据缺失即失败，
//! 不依赖五笔、不下载第三方仓库、不把“跳过”算作通过。
//!
//! 核心含进程级目录与配置缓存，因此整个场景在一个测试、一个核心中顺序执行。
#![cfg(target_os = "linux")]

use std::path::{Path, PathBuf};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use wind_android::{
    CandidateUpdate, ConfigValue, EngineCommand, InputStatus, KeyResult, KeySource, PressKey,
    UiEventListener, WindCore,
};

#[derive(Default)]
struct Recorder {
    candidates: Mutex<Vec<(String, bool)>>,
    changed: Condvar,
}

impl UiEventListener for Recorder {
    fn on_candidates(&self, update: CandidateUpdate) {
        self.candidates
            .lock()
            .unwrap()
            .push((update.preedit, update.has_candidates));
        self.changed.notify_all();
    }
    fn on_hide_candidates(&self) {}
    fn on_status(&self, _status: InputStatus) {}
    fn on_toast(&self, _text: String) {}
}

impl Recorder {
    fn expect_composing_candidates(&self) {
        let frames = self.candidates.lock().unwrap();
        let (frames, _) = self
            .changed
            .wait_timeout_while(frames, Duration::from_secs(10), |frames| {
                !frames.iter().any(|(preedit, has)| *has && !preedit.is_empty())
            })
            .unwrap();
        assert!(
            frames.iter().any(|(preedit, has)| *has && !preedit.is_empty()),
            "真实按键必须通过异步通道产生组合串和候选，实得 {frames:?}",
        );
    }
}

fn source_data() -> PathBuf {
    let path = std::env::var_os("KUNKUN_IME_TEST_DATA")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../WindInput/data")
        });
    for relative in [
        "config.toml",
        "schemas/pinyin.schema.toml",
        "schemas/pinyin/rime_frost.dict.yaml",
        "schemas/pinyin/cn_dicts/8105.dict.yaml",
        "schemas/pinyin/cn_dicts/41448.dict.yaml",
        "schemas/pinyin/cn_dicts/base.dict.yaml",
        "schemas/pinyin/cn_dicts/ext.dict.yaml",
        "schemas/pinyin/cn_dicts/others.dict.yaml",
        "schemas/pinyin/cn_dicts/corrections.dict.yaml",
        "schemas/english.schema.toml",
        "schemas/english/en.dict.yaml",
        "schemas/english/en_ext.dict.yaml",
    ] {
        assert!(
            path.join(relative).is_file(),
            "缺少真实离线输入数据 {}；请检出完整仓库并生成 Android assets（不得跳过本测试）",
            path.join(relative).display(),
        );
    }
    path
}

fn copy_tree(source: &Path, target: &Path) {
    std::fs::create_dir_all(target).unwrap();
    for entry in std::fs::read_dir(source).unwrap() {
        let entry = entry.unwrap();
        let to = target.join(entry.file_name());
        if entry.file_type().unwrap().is_dir() {
            copy_tree(&entry.path(), &to);
        } else {
            assert!(entry.file_type().unwrap().is_file(), "数据树只能含常规文件");
            std::fs::copy(entry.path(), to).unwrap();
        }
    }
}

fn type_touch(core: &WindCore, text: &str) {
    for ch in text.chars() {
        let result = core.press(
            PressKey::Char {
                ch: ch.to_string(),
                shifted: false,
            },
            KeySource::Touch,
        );
        assert!(result.consumed, "核心未消费字母 {ch:?}：{result:?}");
    }
}

fn committed(result: &KeyResult) -> String {
    result
        .ops
        .iter()
        .filter(|op| op.kind == 0)
        .map(|op| op.text.as_str())
        .collect()
}

fn choose_schema(core: &WindCore, id: &str) {
    let index = core
        .schemas()
        .iter()
        .position(|schema| schema.id == id)
        .unwrap_or_else(|| panic!("缺少必需方案 {id}"));
    core.run_command(EngineCommand::SelectSchema {
        index: index as u32,
    });
    assert!(core.schemas().iter().any(|s| s.id == id && s.active));
}

#[test]
fn offline_full_pinyin_english_and_private_persistence() {
    let source = source_data();
    let temp = tempfile::tempdir().unwrap();
    let data = temp.path().join("data");
    copy_tree(&source, &data);
    let root = temp.path().join("rust");
    std::fs::create_dir_all(&root).unwrap();
    // 不继承开发机器的自定义用户目录；测试在唯一进程中且先于任何核心初始化。
    unsafe {
        std::env::set_var(
            "KUNKUN_DATADIR_CONF",
            temp.path().join("absent-datadir.conf"),
        );
    }
    let recorder = Arc::new(Recorder::default());
    let core = WindCore::new(
        data.to_string_lossy().into_owned(),
        root.to_string_lossy().into_owned(),
        root.join("data/KunKunIME").to_string_lossy().into_owned(),
        "pinyin".into(),
        recorder.clone(),
    );
    assert_eq!(
        std::env::var_os("KUNKUN_INSTALL_ROOT"),
        Some(temp.path().as_os_str().to_owned()),
        "Android 适配必须设置用户 fork 实际读取的安装根变量",
    );
    core.focus_gained();
    choose_schema(&core, "pinyin");

    // 点候选必须提交真实汉字，证明全拼词库与 UniFFI 编辑指令都已接通。
    type_touch(&core, "nihao");
    recorder.expect_composing_candidates();
    let candidates = core.candidates(0, 128).items;
    let hello = candidates
        .iter()
        .position(|text| text == "你好")
        .unwrap_or_else(|| panic!("nihao 必须包含真实候选“你好”，实得 {candidates:?}"));
    assert_eq!(committed(&core.select_candidate(hello as u32)), "你好");

    // 换输入框后第二轮仍有候选；删除末尾误按字母再用空格提交。
    core.focus_lost();
    core.focus_gained();
    recorder.candidates.lock().unwrap().clear();
    type_touch(&core, "nihaox");
    assert!(core.press(PressKey::Backspace, KeySource::Touch).consumed);
    recorder.expect_composing_candidates();
    let result = core.press(PressKey::Space, KeySource::Touch);
    assert_eq!(committed(&result), "你好", "全拼空格须提交汉字");

    // 英文必须出现词库补全；只有原样输出字母不足以证明英文核心已加载。
    core.focus_lost();
    core.focus_gained();
    choose_schema(&core, "english");
    type_touch(&core, "beau");
    let candidates = core.candidates(0, 128).items;
    let completion = candidates
        .iter()
        .position(|text| text.to_ascii_lowercase().starts_with("beau") && text.len() > 4)
        .unwrap_or_else(|| panic!("英文真实词库必须给出 beau 补全，实得 {candidates:?}"));
    assert_eq!(
        committed(&core.select_candidate(completion as u32)).trim_end(),
        candidates[completion],
    );
    core.focus_lost();
    core.focus_gained();
    type_touch(&core, "hello");
    assert_eq!(
        committed(&core.press(PressKey::Space, KeySource::Touch)),
        "hello ",
        "触摸英文空格应提交原文并自动补空格",
    );

    // Android 设置写盘必须与 Kotlin DataDirs / LogLevel / 重置入口同址。
    core.config_set(
        "debug.log_level".into(),
        ConfigValue::Str { v: "error".into() },
    )
    .expect("日志设置必须能写入用户层");
    let config_path = root.join("config/KunKunIME/config.toml");
    let saved: toml::Value = std::fs::read_to_string(&config_path)
        .expect("用户配置必须落在产品真实目录，而不是外部目录或 config/WindInput")
        .parse()
        .unwrap();
    assert_eq!(saved["debug"]["log_level"].as_str(), Some("error"));
    assert!(!root.join("config.toml").exists());
    assert!(!root.join("config/WindInput").exists());

    // 恢复出厂值应删除用户覆盖：这会用到 KUNKUN_INSTALL_ROOT/data 的真实默认配置。
    let defaults: toml::Value = core.config_defaults().parse().unwrap();
    let level = defaults["debug"]["log_level"].as_str().unwrap();
    core.config_set(
        "debug.log_level".into(),
        ConfigValue::Str { v: level.into() },
    )
    .unwrap();
    let saved: toml::Value = std::fs::read_to_string(&config_path)
        .unwrap_or_default()
        .parse()
        .unwrap();
    assert!(saved.get("debug").and_then(|v| v.get("log_level")).is_none());

    // 手动用户词必须进入可写存储，并能从同一核心读回与导出。
    core.user_word_add(
        "pinyin".into(),
        "kun kun shu ru".into(),
        "困困输入".into(),
    )
    .expect("用户词库必须可写");
    let words = core.user_words("pinyin".into(), "困困输入".into(), 0, 20);
    assert!(words.items.iter().any(|word| word.text == "困困输入"));
    assert!(core.dict_export("pinyin".into()).unwrap().contains("困困输入"));
    assert!(root.join("data").is_dir(), "用户词文本须留在私有数据根中");
    core.focus_lost();
}
