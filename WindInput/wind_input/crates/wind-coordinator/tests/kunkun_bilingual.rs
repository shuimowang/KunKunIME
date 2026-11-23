//! Use the shipped Chinese/English defaults and a disposable user profile. No Windows
//! service, TSF registration or live desktop connection is needed for this test.
use std::path::{Path, PathBuf};
use std::process::Command;
use wind_bridge::handler::{KeyAction, KeyEventData, MessageHandler};
use wind_config::Config;
use wind_coordinator::Coordinator;
use wind_ipc::protocol::{EVENT_KEY_DOWN, EVENT_KEY_UP, MOD_SHIFT};
use wind_webdata::WebDataRpc;

fn key(vk: u32, modifiers: u32) -> KeyEventData {
    KeyEventData {
        key_code: vk,
        scan_code: 0,
        modifiers,
        event_type: EVENT_KEY_DOWN,
        toggles: 0,
        event_seq: 0,
        prev_char: 0,
        seq_char: 0,
    }
}

fn tap(c: &Coordinator, vk: u32) -> KeyAction {
    let down = key(vk, 0);
    let action = c.handle_key_event(&down);
    let up = KeyEventData {
        event_type: EVENT_KEY_UP,
        ..down
    };
    c.handle_key_event(&up);
    action
}

fn select(c: &Coordinator, spelling: &str, word: &str, committed: &str) {
    for ch in spelling.bytes() {
        tap(c, ch.to_ascii_uppercase().into());
    }
    let candidates = c.debug_all_candidate_texts();
    let page = &candidates[..candidates.len().min(7)];
    let index = page
        .iter()
        .position(|w| w == word)
        .unwrap_or_else(|| panic!("{spelling}: expected {word:?} among {page:?}"));
    let vk = if index == 0 {
        0x20
    } else {
        u32::from(b'1') + index as u32
    };
    match tap(c, vk) {
        KeyAction::InsertText { text, .. }
        | KeyAction::CommitThenDeferComposition {
            commit_text: text, ..
        } => assert_eq!(text, committed),
        other => panic!(
            "{spelling}: expected {word:?} at {} on {page:?}, got {other:?}",
            index + 1
        ),
    }
    println!("{spelling} -> {committed:?} (candidate {})", index + 1);
}

#[test]
fn pinyin_english_and_literal_typing() {
    let engine = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
    let fixture_env = "KUNKUN_BILINGUAL_TEST_CHILD";
    if std::env::var_os(fixture_env).is_none() {
        let tmp = std::env::temp_dir().join(format!("kunkun-bilingual-{}", std::process::id()));
        std::fs::create_dir(&tmp).unwrap();
        std::fs::write(tmp.join("portable_mode"), "").unwrap();
        std::fs::create_dir(tmp.join("data_custom")).unwrap();
        for file in ["config.toml", "custom.toml"] {
            std::fs::copy(
                engine.join("data_custom").join(file),
                tmp.join("data_custom").join(file),
            )
            .unwrap();
        }
        let status = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "pinyin_english_and_literal_typing",
                "--nocapture",
            ])
            .env("KUNKUN_INSTALL_ROOT", &tmp)
            .env(fixture_env, &tmp)
            .status();
        std::fs::remove_dir_all(tmp).unwrap();
        assert!(status.unwrap().success());
        return;
    }

    let fixture = PathBuf::from(std::env::var_os(fixture_env).unwrap());
    assert!(fixture.join("portable_mode").is_file());
    assert_eq!(Config::user_config_dir(), Some(fixture.join("userdata")));
    let data = engine.join("data");
    let config = Config::load(Some(&data)).unwrap();
    assert_eq!(config.schema.active, "pinyin");
    assert_eq!(config.schema.available, ["pinyin", "shuangpin", "english"]);
    let c = Coordinator::new_headless(config, Some(&data));
    for (spelling, expected) in [("", "；"), ("ni", "你；")] {
        for ch in spelling.bytes() {
            tap(&c, u32::from(ch.to_ascii_uppercase()));
        }
        match tap(&c, wind_keys::keymap::VK_SEMICOLON) {
            KeyAction::InsertText { text, .. }
            | KeyAction::CommitThenDeferComposition {
                commit_text: text, ..
            } => {
                assert_eq!(text, expected);
            }
            other => {
                panic!("Expected a single semicolon press to commit {expected:?}, got {other:?}")
            }
        }
    }
    select(&c, "beijingdaxue", "北京大学", "北京大学");
    select(&c, "github", "GitHub", "GitHub");
    select(&c, "templa", "template", "template");
    select(&c, "haoyong", "好用", "好用");

    c.handle_key_event(&key(0x1b, 0));
    let (state, _) = c.handle_toggle_mode();
    assert!(!state.unwrap().chinese_mode);
    for (vk, mods) in [
        (0x47, MOD_SHIFT),
        (0x49, 0),
        (0x54, 0),
        (0x20, 0),
        (0x31, 0),
        (0xBE, 0),
    ] {
        assert!(matches!(
            c.handle_key_event(&key(vk, mods)),
            KeyAction::PassThrough | KeyAction::NotHandled
        ));
    }
    assert!(c.debug_input_buffer().is_empty());
    println!(
        "Literal English mode passes letters, uppercase, spaces, digits and punctuation to the host"
    );
    assert!(c.handle_toggle_mode().0.unwrap().chinese_mode);

    let response = c
        .web_data_rpc("schema.setActive", &serde_json::json!({"id":"english"}))
        .unwrap();
    assert_eq!(response["ok"], true);
    select(&c, "keyboar", "keyboard", "keyboard ");
    select(&c, "hello", "hello", "hello ");
    select(&c, "templa", "template", "template ");
}
