//! 词库管理页，共用设置主窗口与 AppState。

use std::cell::RefCell;
use std::rc::Rc;

use windui::prelude::*;

use crate::pages::dict::sidebar::build_sidebar;
use crate::pages::dict::{build_dict_dialogs, view::build_page_for};
use crate::state::AppState;

/// 数据域、类别与词条表共占整个工作区，返回时恢复上次的设置页面。
pub fn build_content(app: Rc<RefCell<AppState>>) -> Element {
    let mgr = app.borrow().dict_mgr.clone();
    let vdivider = || {
        Element::leaf()
            .width(1)
            .height_match()
            .bg_role(Role::Divider)
    };

    let app_menu = app.clone();
    let back = app.clone();
    let back_button = Element::button("← 设置")
        .small()
        .outline_soft()
        .on_click(move |_| back.borrow().return_to_settings());
    let sidebar = build_sidebar(&mgr, back_button)
        .on_context_menu(move || crate::pages::config_import::dictionary_import_menu(&app_menu));

    let body = Element::row()
        .fill()
        .weight(1.0)
        .child(sidebar)
        .child(vdivider())
        .child(crate::pages::dict::category_nav::build_category_nav(&mgr))
        .child(vdivider())
        // 右侧沿用词库页原有的子标签/工具栏/表格/分页。显式 `weight(1.0)` 而不只靠
        // `fill()`：在 row 里没有权重的话它按内容宽度收缩，表格会缩成窄窄一条。
        .child(build_page_for(mgr.clone()).weight(1.0));

    let current = app.clone();
    let body = body.visible_when(move || !current.borrow().awaiting_dict_target());
    let loading = Element::label("正在载入目标词库…")
        .fill()
        .weight(1.0)
        .text_align(Align::Center)
        .fg_role(Role::TextMuted)
        .visible_when(move || app.borrow().awaiting_dict_target());
    let main_col = Element::col()
        .fill()
        .child(crate::shell::build_titlebar("词库管理"))
        .child(Element::divider())
        .child(body)
        .child(loading);

    Element::stack()
        .fill()
        .bg_role(Role::Bg)
        .child(main_col)
        .child(build_dict_dialogs(&mgr))
}

#[cfg(test)]
mod tests {
    use super::*;
    use windui::core::Tree;
    use windui::event::{MouseButton, PointerEvent, PointerKind};
    use windui::geometry::{Point, Size};

    const W: i32 = 800;
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

        // x=100 在左栏内（宽 200），y=300 已在条目列表下方的空白处。
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

    /// 接线守护：三栏各就各位，左栏条目与中栏节点右击各弹自己的菜单（而不是左栏空白处那份
    /// 快速导入菜单，也不是什么都不弹）。
    #[test]
    fn three_columns_and_their_context_menus() {
        let _g = crate::rpc::ENV_LOCK
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        unsafe { std::env::set_var("WIND_RPC_MOCK", "1") };
        let app = Rc::new(RefCell::new(AppState::empty()));
        let mut tree = build_tree(&app);

        // 根 stack → 主列 [标题栏, 分隔线, 主体]；主体 [左栏, 竖线, 中栏, 竖线, 右栏]
        let root = tree.root.unwrap();
        let main_col = tree.get(root).unwrap().children[0];
        let body = tree.get(main_col).unwrap().children[2];
        let cols = tree.get(body).unwrap().children.clone();
        assert_eq!(cols.len(), 5, "数据域 | 类别 | 内容");
        let side = tree.abs_bounds(cols[0]);
        let nav = tree.abs_bounds(cols[2]);
        assert_eq!(nav.w, crate::pages::dict::category_nav::CATNAV_W);
        assert!(tree.abs_bounds(cols[4]).w > 300, "内容区要有宽度");

        // 左栏第一项（短语）：上内边距 10 + 分组标题 30 之后的第一行
        let item_h = crate::pages::dict::sidebar::ITEM_H;
        let labels = right_click(&mut tree, side.x + 60, side.y + 10 + 30 + item_h / 2);
        assert_eq!(labels, ["打开数据目录"]);

        // 中栏第一项（系统短语）：上内边距 10 + 分组标题 30 + 间距 2 之后的第一行。系统短语只读，
        // 没有编辑与导入导出，只剩打开数据目录。
        let labels = right_click(&mut tree, nav.x + 60, nav.y + 10 + 30 + 2 + item_h / 2);
        assert_eq!(labels, ["打开数据目录"]);
        unsafe { std::env::remove_var("WIND_RPC_MOCK") };
    }
}
