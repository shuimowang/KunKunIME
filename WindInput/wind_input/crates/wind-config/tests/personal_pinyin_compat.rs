use std::path::Path;
use wind_config::app_compat::{AppCompat, AssocPlaceholder, PlaceholderChar, WindowCtx};

#[test]
fn quicker_uses_host_render_by_default_and_honors_user_opt_out() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
    let data = root.join("data");
    let custom = root.join("data_custom");
    let hosts = |user: Option<&Path>| {
        AppCompat::load_layered(Some(&data), Some(&custom), user).host_render_processes()
    };
    let has_quicker =
        |hosts: &[String]| hosts.iter().any(|p| p.eq_ignore_ascii_case("Quicker.exe"));
    assert!(has_quicker(&hosts(None)));

    let user = std::env::temp_dir().join(format!("kunkun-compat-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir(&user).unwrap();
    let rule = user.join("compat.toml");
    std::fs::write(
        &rule,
        "[[apps]]\nprocess = \"Quicker.exe\"\nhost_render = false\n",
    )
    .unwrap();
    let overridden = hosts(Some(&user));
    std::fs::remove_file(rule).unwrap();
    std::fs::remove_dir(user).unwrap();
    assert!(!has_quicker(&overridden));
}

#[test]
fn codex_commits_immediately_even_before_the_tsf_window_class_arrives() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
    let compat = AppCompat::load_layered(
        Some(&root.join("data")),
        Some(&root.join("data_custom")),
        None,
    );
    let rule = |process, class| {
        compat.resolve(&WindowCtx {
            process,
            class,
            title: "",
        })
    };

    for class in ["", "Chrome_WidgetWin_1"] {
        let codex = rule("ChatGPT.exe", class);
        assert_eq!(
            codex.rule.as_ref().and_then(|r| r.assoc_placeholder),
            Some(AssocPlaceholder::None),
            "the first commit after reconnect must also end the composition",
        );
    }

    let codex = rule("ChatGPT.exe", "Chrome_WidgetWin_1");
    assert_eq!(
        codex.rule.as_ref().and_then(|r| r.composition_placeholder),
        Some(PlaceholderChar::Blank),
    );
    let other = rule("Code.exe", "Chrome_WidgetWin_1");
    assert_eq!(other.rule.as_ref().and_then(|r| r.assoc_placeholder), None);
}
