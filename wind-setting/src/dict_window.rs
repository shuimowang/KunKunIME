//! 词库管理页，共用设置主窗口与 AppState。

use std::cell::RefCell;
use std::rc::Rc;

use windui::prelude::*;

use crate::pages::dict::{category_nav::build_category_nav, view::build_page_for};
use crate::state::AppState;

/// 在固定主导航右侧显示当前范围的分类与词表。
pub fn build_content(app: Rc<RefCell<AppState>>) -> Element {
    let mgr = app.borrow().dict_mgr.clone();
    let app_menu = app.clone();
    let back = app.clone();
    let back_button = Element::button("返回上页")
        .small()
        .outline_soft()
        .tooltip("回到进入词库前的设置页")
        .on_click(move |_| back.borrow().return_to_settings());
    let sidebar = build_category_nav(&mgr, back_button)
        .on_context_menu(move || crate::pages::config_import::dictionary_import_menu(&app_menu));

    let body = Element::row()
        .fill()
        .weight(1.0)
        .child(sidebar)
        .child(
            Element::leaf()
                .width(1)
                .height_match()
                .bg_role(Role::Divider),
        )
        .child(build_page_for(mgr.clone()).weight(1.0));

    let current = app.clone();
    let body = body.visible_when(move || !current.borrow().awaiting_dict_target());
    let loading = Element::label("正在载入目标词库…")
        .fill()
        .weight(1.0)
        .text_align(Align::Center)
        .fg_role(Role::TextMuted)
        .visible_when(move || app.borrow().awaiting_dict_target());
    Element::col().fill().child(body).child(loading)
}

#[cfg(test)]
mod tests {
    use super::*;
    use windui::core::Tree;
    use windui::event::{MouseButton, PointerEvent, PointerKind};
    use windui::geometry::{Point, Size};

    const W: i32 = crate::MAIN_MIN_W - crate::shell::NAV_W - 1;
    const H: i32 = 600;

    fn build_tree(app: &Rc<RefCell<AppState>>) -> Tree {
        let mut tree = Tree::new();
        let root = build_content(app.clone())
            .width(W)
            .height(H)
            .build(&mut tree);
        tree.root = Some(root);
        tree.layout_root(Size::new(W, H), &mut windui::text::NullTextEngine);
        tree
    }

    /// 接线守护：在**真实词库窗**的左栏空白处右击，快速导入菜单要真的弹出来。
    ///
    /// 只测菜单内容（`config_import` 里那两条）挡不住本仓最容易犯的错：菜单忘了接、
    /// 或接到了右侧表格上（那里已有行菜单，两者会打架）—— 那些测试照样全绿。
    #[test]
    fn right_clicking_the_sidebar_opens_the_import_menu() {
        let _g = crate::rpc::ENV_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        unsafe { std::env::set_var("WIND_RPC_MOCK", "1") };
        let app = Rc::new(RefCell::new(AppState::empty()));
        let mut tree = build_tree(&app);

        // 分类栏空白区域与设置侧栏使用相同的导入菜单。
        let (mut hover, mut capture) = (None, None);
        let res = tree.dispatch_pointer(
            PointerEvent::single(PointerKind::Down, Point::new(100, 300), MouseButton::Right),
            &mut hover,
            &mut capture,
        );
        let menu = res.menu.expect("右击左栏空白应弹出快速导入菜单");
        let labels: Vec<String> = menu
            .items
            .iter()
            .filter(|i| !i.separator)
            .map(|i| i.label.clone())
            .collect();
        assert_eq!(
            labels,
            vec!["从剪贴板导入配置", "导入文件…"],
            "词库窗与主窗必须是同一份菜单（`import_menu_items`），实得 {labels:?}"
        );
    }

    /// 右击 (x, y)，返回弹出菜单的项（分隔线记作「─」）。
    fn right_click(tree: &mut Tree, x: i32, y: i32) -> Vec<String> {
        let (mut hover, mut capture) = (None, None);
        let res = tree.dispatch_pointer(
            PointerEvent::single(PointerKind::Down, Point::new(x, y), MouseButton::Right),
            &mut hover,
            &mut capture,
        );
        res.menu
            .map(|m| {
                m.items
                    .iter()
                    .map(|i| {
                        if i.separator {
                            "─".into()
                        } else {
                            i.label.clone()
                        }
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    #[test]
    fn unified_window_dictionary_uses_one_context_sidebar() {
        use windui::core::NodeId;
        fn visible_label(tree: &Tree, id: NodeId, text: &str) -> Option<NodeId> {
            let node = tree.get(id)?;
            if !node.effective_visible() {
                return None;
            }
            if node.widget.display_text().as_deref() == Some(text) {
                return Some(id);
            }
            node.children
                .iter()
                .find_map(|child| visible_label(tree, *child, text))
        }

        let _g = crate::rpc::ENV_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        unsafe { std::env::set_var("WIND_RPC_MOCK", "1") };
        let app = Rc::new(RefCell::new(AppState::empty()));
        let mut tree = build_tree(&app);
        let root = tree.root.unwrap();
        let body = tree.get(root).unwrap().children[0];
        let columns = &tree.get(body).unwrap().children;
        assert_eq!(columns.len(), 3, "分类栏、分隔线、词表");
        let sidebar = columns[0];
        assert_eq!(
            tree.abs_bounds(sidebar).w,
            crate::pages::dict::category_nav::CATNAV_W
        );
        assert!(
            tree.abs_bounds(columns[2]).w >= 400,
            "最小宽度下表格保留至少 400 DIP"
        );

        for name in ["词库范围", "系统短语"] {
            let label = visible_label(&tree, sidebar, name).expect(name);
            let bounds = tree.abs_bounds(label);
            let labels = right_click(&mut tree, bounds.x + 2, bounds.y + bounds.h / 2);
            assert_eq!(labels, ["打开数据目录"], "{name}");
        }
        unsafe { std::env::remove_var("WIND_RPC_MOCK") };
    }
}
