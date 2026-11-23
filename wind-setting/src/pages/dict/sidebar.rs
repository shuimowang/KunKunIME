//! 词库分类导航与应用兼容界面共用的导航行。

use windui::prelude::*;

use super::state::DictManagerState;

pub(crate) const ITEM_H: i32 = 36;
/// 让导航行避开滚动条。
pub(crate) const SIDE_PAD: i32 = 10;
pub(crate) const ITEM_PAD_X: i32 = 8;

/// 静态分组用浅色标题和延伸线，导航项用正文色和方向符。
pub(crate) fn group_title(text: &str) -> Element {
    Element::row()
        .width_match()
        .height(30)
        .cross(Align::Center)
        .padding_xy(ITEM_PAD_X, 0)
        .spacing(8)
        .child(
            Element::label(text)
                .font_size(11.5)
                .font_weight(600)
                .fg_role(Role::TextMuted)
                .max_lines(1),
        )
        .child(Element::divider().weight(1.0))
}

pub(crate) fn nav_chevron(active: bool) -> Element {
    Element::label(if active { "" } else { "›" })
        .width(10)
        .height(22)
        .font_size(19.0)
        .fg_role(Role::TextMuted)
}

impl DictManagerState {
    /// 左栏条目的右键菜单。右键先选中该条目（同中栏），菜单开着时右键另一条，高亮跟着走。
    pub fn domain_menu_items(&self, idx: usize) -> Vec<MenuItem> {
        if self.domain.get() != idx {
            self.domain.set(idx);
        }
        let m_dir = self.clone();
        let mut items = Vec::new();
        if self.schema_id_at(idx).is_some() {
            let m = self.clone();
            items.push(MenuItem::run(
                "词库统计…",
                move |_| m.open_stats_at(idx),
                false,
            ));
        }
        items.push(MenuItem::run(
            "打开数据目录",
            move |_| m_dir.open_data_dir_at(idx),
            false,
        ));
        if self.domain_purgeable(idx) {
            let m = self.clone();
            items.push(MenuItem::separator());
            items.push(
                MenuItem::run(
                    "删除此方案的用户数据…",
                    move |_| m.request_purge_schema(idx),
                    false,
                )
                .danger(),
            );
        }
        items
    }
}

/// 可选中的列表条目：选中态 = accent 淡底 + 左缘指示条 + 文字转强调色（主窗侧栏同款）。
///
/// 选中/未选中两套元素靠 `visible_when` 切换（windui 无响应式样式），所以选中态变化**不需要
/// 重建列表**——词库窗左栏、应用兼容窗的分类与进程列表共用这一份，保证各处选中效果一致。
///
/// `content(active)` 返回条目行本体（**不含**底色 / 点击 / 指示条，由这里统一加）；
/// `height` 是整行高度（指示条按它垂直居中）。
pub fn selectable(
    height: i32,
    is_selected: impl Fn() -> bool + Clone + 'static,
    on_click: impl Fn() + 'static,
    content: impl Fn(bool) -> Element,
) -> Element {
    // 左缘指示条作覆盖层，不占行内宽度 —— 选中/未选中 padding 一致，切换时文字不横移。
    // 「界面细节」`nav_indicator`：只在「竖条+淡底」下出现；「实心」只作用于主导航，
    // 这里同二级导航按「仅淡底」处理（见 docs/design/settings-look-prefs.md §4）。
    let li = crate::widgets::look().0[crate::look::NAV_INDICATOR];
    let sel_ind = is_selected.clone();
    let indicator = Element::row()
        .width_match()
        .height(height)
        .cross(Align::Center)
        .child(
            Element::leaf()
                .width(3)
                .height(14)
                .corner(1.5)
                .bg_role(Role::Accent),
        )
        .visible_when(move || sel_ind() && li.get() == crate::look::nav::BAR);

    let sel_on = is_selected.clone();
    let on = content(true)
        .bg_role_alpha(Role::Accent, windui::theme::SELECTED_ALPHA)
        .visible_when(move || sel_on());

    let off = content(false)
        .clickable()
        .on_click(move |_| on_click())
        .visible_when(move || !is_selected());

    Element::stack()
        .width_match()
        .height(height)
        .child(on)
        .child(off)
        .child(indicator)
}

#[cfg(test)]
mod tests {
    #[test]
    fn sidebar_padding_covers_scrollbar_gutter() {
        assert!(super::SIDE_PAD >= windui::core::scrollbar::occupied_w(0));
    }
}
