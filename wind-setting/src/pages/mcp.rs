//! 本机 MCP 接入说明。页面只复制连接信息，客户端负责添加服务。

use windui::prelude::*;

use crate::widgets::{page, section, setting_row};

fn codex_command(exe: &str) -> String {
    let mut command = String::from("codex mcp add kunkun-ime -- '");
    for ch in exe.chars() {
        command.push(ch);
        // PowerShell 也将弯单引号识别为引号。逐个加倍才能保持路径原值。
        if matches!(ch, '\'' | '\u{2018}' | '\u{2019}' | '\u{201a}' | '\u{201b}') {
            command.push(ch);
        }
    }
    command.push_str("' mcp");
    command
}

fn client_json(exe: &str) -> String {
    let config = serde_json::json!({
        "mcpServers": {
            "kunkun-ime": { "command": exe, "args": ["mcp"] }
        }
    });
    format!("{config:#}")
}

fn copy_button(label: &str, text: Option<String>, success: &'static str) -> Element {
    let button = Element::button(label).small().outline_soft();
    match text {
        Some(text) => button.on_click(move |ctx| {
            ctx.clipboard_set(&text);
            if ctx.clipboard_get().as_deref() == Some(text.as_str()) {
                ctx.toast_ok(success);
            } else {
                ctx.toast_err("无法写入剪贴板，请重试");
            }
        }),
        None => button.enabled(false),
    }
}

fn paragraph(text: &str) -> Element {
    Element::label(text)
        .width_match()
        .font_size(13.0)
        .fg_role(Role::Text)
}

pub(crate) fn build() -> Element {
    let exe = std::env::current_exe().ok();
    build_for(exe.as_deref().and_then(|p| p.to_str()))
}

fn build_for(exe: Option<&str>) -> Element {
    let intro = Element::col()
        .width_match()
        .padding_xy(0, 9)
        .spacing(6)
        .child(paragraph("MCP 是让 Codex 等 AI 助手连接本机输入法的接口。你可以让助手批量加词、精确删除指定候选，或查询和修改输入设置。"))
        .child(Element::label("助手查询到的词条和设置值会交给你使用的 AI 客户端。")
            .width_match().font_size(12.5).fg_role(Role::TextMuted));

    let path = exe.unwrap_or("无法读取当前路径，请在客户端选择本机的 KunKunIME.exe。");
    let launch = Element::col()
        .width_match()
        .padding_xy(0, 8)
        .spacing(4)
        .child(
            Element::label("本机程序")
                .font_size(13.0)
                .fg_role(Role::TextMuted),
        )
        .child(paragraph(path).tooltip(path))
        .child(
            Element::label("连接方式：stdio（标准输入输出）　启动参数：mcp")
                .font_size(12.5)
                .fg_role(Role::TextMuted),
        );
    let codex = setting_row(
        "Codex",
        Some("将命令粘贴到 PowerShell 运行，再打开一个新的 Codex 对话。"),
        copy_button(
            "复制 Codex 命令",
            exe.map(codex_command),
            "已复制 Codex 命令",
        ),
    );
    let other = setting_row(
        "其他 MCP 客户端",
        Some("将 JSON 中的 kunkun-ime 条目加入客户端的 mcpServers 配置。"),
        copy_button("复制客户端 JSON", exe.map(client_json), "已复制客户端 JSON"),
    );

    let examples = Element::col()
        .width_match()
        .padding_xy(0, 9)
        .spacing(7)
        .child(paragraph("「用困困输入法查看目前每页显示几个候选词。」"))
        .child(paragraph(
            "「创建一个叫星河人物的拼音分库，加入林溪、林夕和沈舟。」",
        ))
        .child(paragraph("「把每页候选数改成 8 个，再读回来确认。」"));

    page(
        Element::col()
            .width_match()
            .spacing(18)
            .child(section("AI 助手（MCP）", vec![intro]))
            .child(section("连接 AI 客户端", vec![launch, codex, other]))
            .child(section("接入后可以这样说", vec![examples])),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{cell::RefCell, rc::Rc};
    use windui::core::{ClipboardProvider, NodeId, Tree};
    use windui::event::{MouseButton, PointerEvent, PointerKind};
    use windui::geometry::{Point, Size};

    const EXE: &str = r"C:\我的 程序\O'Neil\A’B\$env`test\KunKunIME.exe";

    #[test]
    fn mcp_guide_preserves_install_path_in_both_client_formats() {
        assert_eq!(
            codex_command(EXE),
            r"codex mcp add kunkun-ime -- 'C:\我的 程序\O''Neil\A’’B\$env`test\KunKunIME.exe' mcp"
        );
        let value: serde_json::Value = serde_json::from_str(&client_json(EXE)).unwrap();
        assert_eq!(
            value,
            serde_json::json!({"mcpServers": {"kunkun-ime": {"command": EXE, "args": ["mcp"]}}})
        );
    }

    #[test]
    fn mcp_guide_page_buttons_copy_to_the_client_clipboard() {
        struct Clip(Rc<RefCell<String>>);
        impl ClipboardProvider for Clip {
            fn get_text(&self) -> Option<String> {
                Some(self.0.borrow().clone())
            }
            fn set_text(&self, text: &str) {
                *self.0.borrow_mut() = text.to_owned();
            }
        }

        fn label(tree: &Tree, id: NodeId, title: &str) -> Option<NodeId> {
            let node = tree.get(id)?;
            if node.widget.display_text().as_deref() == Some(title) {
                return Some(id);
            }
            node.children
                .iter()
                .find_map(|&child| label(tree, child, title))
        }

        let mut tree = Tree::new();
        let root = build_for(Some(EXE)).build(&mut tree);
        tree.root = Some(root);
        tree.layout_root(Size::new(680, 800), &mut windui::text::NullTextEngine);
        let clipboard = Rc::new(RefCell::new(String::new()));
        tree.clipboard = Some(Box::new(Clip(clipboard.clone())));
        for (row_title, expected, feedback) in [
            ("Codex", codex_command(EXE), "已复制 Codex 命令"),
            ("其他 MCP 客户端", client_json(EXE), "已复制客户端 JSON"),
        ] {
            let title = label(&tree, root, row_title).unwrap();
            let left = tree.get(title).unwrap().parent.unwrap();
            let row = tree.get(left).unwrap().parent.unwrap();
            let button = *tree.get(row).unwrap().children.last().unwrap();
            let b = tree.abs_bounds(button);
            let p = Point::new(b.x + b.w / 2, b.y + b.h / 2);
            let (mut hover, mut capture) = (None, None);
            for kind in [PointerKind::Down, PointerKind::Up] {
                let r = tree.dispatch_pointer(
                    PointerEvent::single(kind, p, MouseButton::Left),
                    &mut hover,
                    &mut capture,
                );
                if kind == PointerKind::Up {
                    assert_eq!(r.toast.unwrap().text, feedback);
                }
            }
            assert_eq!(*clipboard.borrow(), expected);
        }
    }
}
