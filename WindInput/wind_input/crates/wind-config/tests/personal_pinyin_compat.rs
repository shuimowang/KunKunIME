use std::path::Path;
use wind_config::app_compat::{AppCompat, AssocPlaceholder, PlaceholderChar, WindowCtx};

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
