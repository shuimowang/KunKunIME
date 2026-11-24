//! 邮箱和网址在输入规则里提供入口，模式开关与条目列表共享词库页。
use crate::pages::dict::spec::LogicalTab;
use crate::state::AppState;
use windui::prelude::*;

pub(crate) struct Rule {
    pub key: &'static str,
    pub manage: &'static str,
    pub options: &'static str,
    pub tab: LogicalTab,
}

const RULES: [Rule; 2] = [
    Rule {
        key: "input.email.enabled",
        manage: "管理邮箱后缀",
        options: "预置后缀设置",
        tab: LogicalTab::MailSuffix,
    },
    Rule {
        key: "input.url.enabled",
        manage: "管理常用网址",
        options: "前缀与历史",
        tab: LogicalTab::UrlHistory,
    },
];

pub(crate) fn for_key(key: &str) -> Option<&'static Rule> {
    RULES.iter().find(|rule| rule.key == key)
}

impl Rule {
    pub fn link(&self, state: &AppState) -> Element {
        let (tab, jump, target) = (state.tab, state.jump, self.tab);
        Element::button(self.manage)
            .small()
            .outline_soft()
            .on_click(move |_| {
                crate::search::jump::request(
                    tab,
                    jump,
                    crate::search::JumpTarget::Dictionary(Some(target)),
                );
            })
    }
}

/// 绑定已由设置清单登记的同一组 signal、写入器和对话框。
pub(crate) fn controls(state: &AppState) -> Element {
    let mgr = state.dict_mgr.clone();
    let visible = mgr.clone();
    let mut rules = Element::col().width_match();
    let mut available = Vec::new();
    for rule in &RULES {
        let Some(item) = state.manifest.get(rule.key) else {
            continue;
        };
        let Some(&enabled) = state.fields.bool_signals.borrow().get(rule.key) else {
            continue;
        };
        let current = mgr.clone();
        let category = rule.tab.category();
        available.push(category);
        let mut row = Element::row()
            .width_match()
            .cross(Align::Center)
            .spacing(10)
            .child(Element::label(&item.label).font_size(14.0).weight(1.0))
            .child(Element::switch(enabled));
        if let Some(&open) = state.fields.dialog_opens.borrow().get(rule.key) {
            row = row.child(
                Element::button(rule.options)
                    .small()
                    .outline_soft()
                    .on_click(move |_| open.set(true)),
            );
        }
        rules = rules.child(row.visible_when(move || current.current_category() == category));
    }
    rules.visible_when(move || available.contains(&visible.current_category()))
}
