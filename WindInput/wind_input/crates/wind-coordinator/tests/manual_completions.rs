//! The settings RPC and the typing engine must use the same completion records.
use serde_json::json;
use std::{
    path::{Path, PathBuf},
    process::Command,
    sync::Arc,
};
use wind_bridge::handler::{KeyAction, KeyEventData, MessageHandler};
use wind_config::Config;
use wind_coordinator::Coordinator;
use wind_ipc::protocol::{EVENT_KEY_DOWN, EVENT_KEY_UP, MOD_SHIFT};
use wind_store::{Store, completion::CompletionKind};
use wind_webdata::WebDataRpc;

fn tap(c: &Coordinator, vk: u32, mods: u32) -> KeyAction {
    let key = KeyEventData {
        key_code: vk,
        modifiers: mods,
        event_type: EVENT_KEY_DOWN,
        scan_code: 0,
        toggles: 0,
        event_seq: 0,
        prev_char: 0,
        seq_char: 0,
    };
    let response = c.handle_key_event(&key);
    c.handle_key_event(&KeyEventData {
        event_type: EVENT_KEY_UP,
        ..key
    });
    response
}
fn type_ascii(c: &Coordinator, text: &str) {
    for b in text.bytes() {
        let (vk, mods) = match b {
            b'a'..=b'z' => (u32::from(b.to_ascii_uppercase()), 0),
            b'@' => (u32::from(b'2'), MOD_SHIFT),
            b'.' => (0xbe, 0),
            b':' => (0xba, MOD_SHIFT),
            b'/' => (0xbf, 0),
            _ => panic!("unsupported test key {b}"),
        };
        tap(c, vk, mods);
    }
}
fn select(c: &Coordinator, wanted: &str) {
    let words = c.debug_all_candidate_texts();
    let index = words
        .iter()
        .take(7)
        .position(|s| s == wanted)
        .unwrap_or_else(|| panic!("missing {wanted} in {words:?}"));
    let response = tap(
        c,
        if index == 0 {
            0x20
        } else {
            u32::from(b'1') + index as u32
        },
        0,
    );
    match response {
        KeyAction::InsertText { text, .. }
        | KeyAction::CommitThenDeferComposition {
            commit_text: text, ..
        } => assert_eq!(text, wanted),
        other => panic!("unexpected commit for {wanted}: {other:?}"),
    }
}

#[test]
fn manual_completions_are_selectable_with_history_recording_disabled() {
    let env_key = "KUNKUN_MANUAL_COMPLETION_TEST";
    let Some(raw) = std::env::var_os(env_key) else {
        let dir =
            std::env::temp_dir().join(format!("kunkun-manual-completion-{}", std::process::id()));
        std::fs::create_dir(&dir).unwrap();
        std::fs::write(dir.join("portable_mode"), "").unwrap();
        let child = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "manual_completions_are_selectable_with_history_recording_disabled",
                "--nocapture",
            ])
            .env(env_key, &dir)
            .env("KUNKUN_INSTALL_ROOT", &dir)
            .status();
        std::fs::remove_dir_all(dir).unwrap();
        assert!(child.unwrap().success());
        return;
    };
    let dir = PathBuf::from(raw);
    assert_eq!(Config::user_config_dir(), Some(dir.join("userdata")));
    let data = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../data");
    let mut cfg = Config::load(Some(&data)).unwrap();
    cfg.schema.active = "pinyin".into();
    cfg.input.email.enabled = true;
    cfg.input.url.enabled = true;
    cfg.input.url.history_enabled = false;
    cfg.keys
        .key_actions
        .insert("semicolon".into(), "mix:quick_mix".into());
    let q = cfg
        .schema
        .mix_modes
        .iter()
        .find(|m| m.id == "quick_mix")
        .unwrap();
    assert!(
        !q.members
            .iter()
            .any(|m| m == wind_quick_input::MEMBER_HISTORY)
    );

    let store = Arc::new(Store::open(&dir.join("test.redb")).unwrap());
    let c = Coordinator::new_headless_with_store(cfg, Some(&data), store.clone());
    store
        .record_completion(CompletionKind::UrlHistory, "https://docs.old.test/history")
        .unwrap();
    store
        .record_completion(CompletionKind::QuickHistory, "ProjectOldAuto")
        .unwrap();

    for (method, raw, normal) in [
        ("mailSuffix.add", "@Example.ORG", "example.org"),
        (
            "urlHistory.add",
            "docs.example.org/help",
            "https://docs.example.org/help",
        ),
        ("quickHistory.add", "ProjectAlpha", "ProjectAlpha"),
        ("quickHistory.add", "请查收附件", "请查收附件"),
    ] {
        let result = c.web_data_rpc(method, &json!({"text":raw})).unwrap();
        assert_eq!(result, json!({"ok":true,"added":true,"text":normal}));
        assert_eq!(
            c.web_data_rpc(method, &json!({"text":raw})).unwrap()["added"],
            false
        );
    }
    type_ascii(&c, "demo@exa");
    select(&c, "demo@example.org");
    type_ascii(&c, "https://docs.");
    assert!(
        !c.debug_all_candidate_texts()
            .iter()
            .any(|w| w == "https://docs.old.test/history")
    );
    select(&c, "https://docs.example.org/help");
    type_ascii(&c, "https://private.test");
    tap(&c, 0x0d, 0);
    assert!(
        store
            .get_completion(CompletionKind::UrlHistory, "https://private.test")
            .unwrap()
            .is_none()
    );

    tap(&c, 0xba, 0);
    type_ascii(&c, "proj");
    assert!(
        !c.debug_all_candidate_texts()
            .iter()
            .any(|w| w == "ProjectOldAuto")
    );
    select(&c, "ProjectAlpha");
    tap(&c, 0xba, 0);
    type_ascii(&c, "qingcha");
    select(&c, "请查收附件");

    c.web_data_rpc("quickHistory.delete", &json!({"text":"请查收附件"}))
        .unwrap();
    tap(&c, 0xba, 0);
    type_ascii(&c, "qingcha");
    assert!(
        !c.debug_all_candidate_texts()
            .iter()
            .any(|w| w == "请查收附件")
    );
}
