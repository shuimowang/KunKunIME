//! Agent-facing settings. Tool discovery exposes the contract, not the user's values.
use anyhow::{Context, Result, bail, ensure};
use serde_json::{Map, Value, json};
use std::collections::BTreeSet;

#[derive(Clone, Copy)]
enum Rule {
    Bool,
    Int(i64, i64),
    Enum(&'static [&'static str]),
    Keys(&'static [&'static str]),
    Domains,
    Prefixes,
}

use Rule::{Bool, Domains, Enum, Int, Keys, Prefixes};

// Only expose settings that are meaningful without a GUI. The tests check these
// keys against the core registry and the bounds/options of the settings window.
const FIELDS: &[(&str, &str, Rule)] = &[
    (
        "schema.pinyin.separator",
        "拼音音节分隔符",
        Enum(&["auto", "quote", "backtick", "none"]),
    ),
    ("schema.pinyin.single_char", "拼音只出单字", Bool),
    ("schema.pinyin.abbrev.enabled", "拼音简拼", Bool),
    ("schema.pinyin.fuzzy.enabled", "拼音模糊音", Bool),
    ("schema.pinyin.grammar.enabled", "拼音整句语法模型", Bool),
    ("schema.pinyin.frequency.enabled", "拼音调频", Bool),
    ("schema.pinyin.auto_learn.enabled", "拼音自动学习", Bool),
    (
        "schema.pinyin.completion.min_syllables",
        "触发拼音词组补全的最少音节数",
        Int(1, 6),
    ),
    (
        "schema.pinyin.english_merge.enable",
        "拼音状态下显示英文候选",
        Bool,
    ),
    (
        "schema.pinyin.english_merge.min_length",
        "拼音状态英文候选的最短输入长度",
        Int(0, 10),
    ),
    (
        "schema.pinyin.english_merge.max_count",
        "拼音状态最多显示的英文候选数",
        Int(1, 10),
    ),
    (
        "schema.english.case_follow_input",
        "英文补全方案按输入调整大小写",
        Bool,
    ),
    (
        "schema.english.commit_space",
        "英文补全方案上屏后自动加空格",
        Bool,
    ),
    ("input.default.chinese_mode", "默认使用中文输入状态", Bool),
    ("input.default.chinese_punct", "默认使用中文标点", Bool),
    ("input.symbol.smart_mode", "中文标点智能符号", Bool),
    (
        "input.association.kind",
        "下一词联想类型",
        Enum(&["off", "word", "smart"]),
    ),
    (
        "input.association.mode",
        "联想一次或持续联想",
        Enum(&["one_shot", "continuous"]),
    ),
    ("input.association.max_count", "联想候选上限", Int(1, 20)),
    (
        "input.association.select_keys_commit",
        "用二三候选键选联想词",
        Bool,
    ),
    (
        "input.association.select_keys_as_punctuation",
        "联想时将选词键作为标点",
        Bool,
    ),
    ("input.emoji.enabled", "Emoji 和 OpenCC 候选", Bool),
    ("input.phrase.prefix_match", "按命令短语编码前缀匹配", Bool),
    (
        "input.phrase.min_prefix",
        "命令短语前缀的最短长度",
        Int(1, 8),
    ),
    ("input.email.enabled", "邮箱输入模式", Bool),
    (
        "input.email.suffixes",
        "有序的预置邮箱域名列表（整项替换，可为空）",
        Domains,
    ),
    ("input.url.enabled", "网址输入模式", Bool),
    (
        "input.url.prefixes",
        "有序的网址触发前缀（整项替换，可为空）",
        Prefixes,
    ),
    ("input.url.history_enabled", "自动记录输入过的网址", Bool),
    (
        "input.url.history_max",
        "网址历史上限（0 为不限制）",
        Int(0, 10000),
    ),
    (
        "keys.select_key_groups",
        "次选/三选快捷键组（整项替换，空列表为关闭）",
        Keys(&["semicolon_quote", "comma_period", "lrshift", "lrctrl"]),
    ),
    (
        "keys.toggle_mode_keys",
        "切换中英文的单键（整项替换）",
        Keys(&["lshift", "rshift", "lctrl", "rctrl", "capslock"]),
    ),
    ("keys.commit_on_switch", "切换中英文时将编码上屏", Bool),
    ("keys.ctrl_space_toggle", "用 Ctrl+空格切换中英文", Bool),
    ("ui.candidate.per_page", "每页候选数", Int(2, 10)),
    (
        "ui.candidate.layout",
        "候选窗排列",
        Enum(&["horizontal", "vertical"]),
    ),
    ("ui.toolbar.visible", "显示浮动工具栏", Bool),
];

const DOMAIN_LABEL: &str = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
const MAX_TEXT_LIST: usize = 256;

impl Rule {
    fn schema(self) -> Value {
        match self {
            Bool => json!({"type":"boolean"}),
            Int(min, max) => json!({"type":"integer","minimum":min,"maximum":max}),
            Enum(values) => json!({"type":"string","enum":values}),
            Keys(values) => {
                json!({"type":"array","uniqueItems":true,"maxItems":values.len(),"items":{"type":"string","enum":values}})
            }
            Domains => {
                json!({"type":"array","uniqueItems":true,"maxItems":MAX_TEXT_LIST,"items":{"type":"string","maxLength":253,"pattern":format!("^(?:{DOMAIN_LABEL}\\.)+{DOMAIN_LABEL}$")}})
            }
            Prefixes => {
                json!({"type":"array","uniqueItems":true,"maxItems":MAX_TEXT_LIST,"items":{"type":"string","maxLength":64,"pattern":"^[a-z][a-z0-9.+:/-]*$"}})
            }
        }
    }

    fn accepts(self, value: &Value) -> bool {
        match self {
            Bool => value.is_boolean(),
            Int(min, max) => value.as_i64().is_some_and(|n| (min..=max).contains(&n)),
            Enum(values) => value.as_str().is_some_and(|s| values.contains(&s)),
            Keys(_) | Domains | Prefixes => {
                let Some(items) = value.as_array() else {
                    return false;
                };
                let limit = if let Keys(allowed) = self {
                    allowed.len()
                } else {
                    MAX_TEXT_LIST
                };
                let mut seen = BTreeSet::new();
                items.len() <= limit
                    && items.iter().all(|v| {
                        v.as_str().is_some_and(|s| {
                            seen.insert(s)
                                && match self {
                                    Keys(allowed) => allowed.contains(&s),
                                    Domains => valid_domain(s),
                                    Prefixes => {
                                        s.len() <= 64
                                            && s.as_bytes()
                                                .first()
                                                .is_some_and(u8::is_ascii_lowercase)
                                            && s.bytes().all(|c| {
                                                c.is_ascii_lowercase()
                                                    || c.is_ascii_digit()
                                                    || b".+:/-".contains(&c)
                                            })
                                    }
                                    _ => false,
                                }
                        })
                    })
            }
        }
    }
}

fn valid_domain(s: &str) -> bool {
    s.len() <= 253
        && s.contains('.')
        && s.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label
                    .bytes()
                    .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
        })
}

fn field(key: &str) -> Result<Rule> {
    FIELDS
        .iter()
        .find(|f| f.0 == key)
        .map(|f| f.2)
        .with_context(|| format!("未开放的设置 {key}，可调用 settings_list 查询可用键"))
}

pub(super) fn tools() -> Vec<Value> {
    let keys: Vec<_> = FIELDS.iter().map(|f| f.0).collect();
    let properties: Map<_, _> = FIELDS
        .iter()
        .map(|&(key, label, rule)| {
            let mut schema = rule.schema();
            schema["description"] = json!(label);
            (key.to_owned(), schema)
        })
        .collect();
    vec![
        json!({"name":"settings_list","description":"列出可管理的全局设置及取值规则。query 可按中文说明或英文键名查找。","inputSchema":{"type":"object","properties":{"query":{"type":"string","maxLength":128}},"additionalProperties":false},"annotations":{"readOnlyHint":true,"openWorldHint":false}}),
        json!({"name":"settings_get","description":"读取指定全局设置的当前配置值，保留原来的类型。","inputSchema":{"type":"object","properties":{"keys":{"type":"array","minItems":1,"maxItems":keys.len(),"uniqueItems":true,"items":{"type":"string","enum":keys}}},"required":["keys"],"additionalProperties":false},"annotations":{"readOnlyHint":true,"openWorldHint":false}}),
        json!({"name":"settings_set","description":"校验全部值，然后向输入法一次提交指定的全局设置。列表值会整项替换，追加前请先读取当前列表。needsRestart 表示是否需要重启才能完全生效。","inputSchema":{"type":"object","properties":{"values":{"type":"object","minProperties":1,"properties":properties,"additionalProperties":false}},"required":["values"],"additionalProperties":false},"annotations":{"readOnlyHint":false,"destructiveHint":true,"idempotentHint":true,"openWorldHint":false}}),
    ]
}

pub(super) fn call(
    name: &str,
    args: &Map<String, Value>,
    rpc: &mut dyn FnMut(&str, Value) -> Result<Value>,
) -> Result<Value> {
    match name {
        "settings_list" => {
            ensure!(
                args.keys().all(|k| k == "query"),
                "settings_list 只接受 query 参数"
            );
            let q = match args.get("query") {
                Some(value) => value.as_str().context("query 必须是字符串")?,
                None => "",
            };
            ensure!(q.chars().count() <= 128, "query 最多 128 个字符");
            let q = q.trim().to_lowercase();
            let entries: Vec<_> = FIELDS
                .iter()
                .filter(|(k, title, _)| k.contains(&q) || title.to_lowercase().contains(&q))
                .map(|&(key, title, rule)| json!({"key":key,"title":title,"schema":rule.schema()}))
                .collect();
            Ok(json!({"settings":entries}))
        }
        "settings_get" => {
            ensure!(
                args.len() == 1 && args.contains_key("keys"),
                "settings_get 需要 keys 数组"
            );
            let keys = args["keys"].as_array().context("keys 必须是数组")?;
            ensure!(
                !keys.is_empty() && keys.len() <= FIELDS.len(),
                "keys 数量超出范围"
            );
            let mut requested = BTreeSet::new();
            for k in keys {
                let k = k.as_str().context("keys 中的每个元素必须是设置键名")?;
                field(k)?;
                ensure!(requested.insert(k), "重复的设置键 {k}");
            }
            let mut values = Map::new();
            for k in requested {
                let entry = rpc("config.getItem", json!({"key":k}))?;
                ensure!(
                    entry.get("key").and_then(Value::as_str) == Some(k),
                    "读取 {k} 时后台返回了其他设置键"
                );
                values.insert(
                    k.to_owned(),
                    entry.get("value").context("后台缺少设置值")?.clone(),
                );
            }
            Ok(json!({"values":values}))
        }
        "settings_set" => {
            ensure!(
                args.len() == 1 && args.contains_key("values"),
                "settings_set 需要 values 对象"
            );
            let values = args["values"]
                .as_object()
                .context("values 必须是设置键到新值的对象")?;
            ensure!(
                !values.is_empty() && values.len() <= FIELDS.len(),
                "values 需要包含有效的设置键"
            );
            let mut items = Vec::with_capacity(values.len());
            for (k, value) in values {
                let rule = field(k)?;
                ensure!(
                    rule.accepts(value),
                    "设置 {k} 的值无效；允许范围：{}",
                    rule.schema()
                );
                items.push(json!({"key":k,"value":value}));
            }
            let reply = rpc("config.setItems", json!({"items":items}))
                .context("设置提交未获成功回执，请先用 settings_get 核对当前值")?;
            let applied = reply["applied"]
                .as_u64()
                .context("后台未报告成功写入数量，请核对当前设置")?;
            let skipped = reply["skipped"]
                .as_array()
                .context("后台未报告跳过项，请核对当前设置")?;
            let restart = reply["needsRestart"]
                .as_bool()
                .context("后台未报告重启状态，请核对当前设置")?;
            ensure!(
                applied == values.len() as u64 && skipped.is_empty(),
                "设置未全部应用：后台确认 {applied}/{} 项，needsRestart={restart}，跳过项 {}。请先读取当前设置。",
                values.len(),
                reply["skipped"]
            );
            Ok(reply)
        }
        _ => bail!("未知的设置工具 {name}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(v: Value) -> Map<String, Value> {
        v.as_object().unwrap().clone()
    }

    #[test]
    fn catalog_tracks_core_types_and_settings_ui_limits() {
        use wind_config::config_schema::{FieldType, field as core_field, validate};
        let manifest: toml::Value = toml::from_str(include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../../wind-setting/src/assets/settings_manifest.toml"
        )))
        .unwrap();
        let items = manifest["items"].as_array().unwrap();
        let mut unique = BTreeSet::new();
        for &(key, _, rule) in FIELDS {
            assert!(unique.insert(key), "{key}");
            let ty = core_field(key).expect(key).ty;
            let ui = items
                .iter()
                .find(|entry| entry["key"].as_str() == Some(key))
                .expect(key);
            match rule {
                Bool => assert_eq!(ty, FieldType::Bool, "{key}"),
                Int(min, max) => {
                    assert_eq!(ty, FieldType::Int, "{key}");
                    if ui["type"].as_str() == Some("number_select") {
                        let choices: Vec<i64> = ui["options"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .map(|v| v["value"].as_str().unwrap().parse().unwrap())
                            .collect();
                        assert_eq!(choices, (min..=max).collect::<Vec<_>>(), "{key}");
                    } else {
                        assert_eq!(
                            ui.get("min").and_then(|v| v.as_integer()),
                            Some(min),
                            "{key}"
                        );
                        assert_eq!(
                            ui.get("max").and_then(|v| v.as_integer()),
                            Some(max),
                            "{key}"
                        );
                    }
                }
                Enum(choices) => {
                    assert!(matches!(ty, FieldType::Str | FieldType::Enum(_)), "{key}");
                    for &choice in choices {
                        assert!(
                            validate(key, &toml::Value::String(choice.to_owned())).is_ok(),
                            "{key}={choice}"
                        );
                    }
                }
                Keys(choices) => {
                    assert_eq!(ty, FieldType::StrList, "{key}");
                    let ui_choices: BTreeSet<_> = ui["options"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .map(|v| v["value"].as_str().unwrap())
                        .collect();
                    assert_eq!(ui_choices, choices.iter().copied().collect(), "{key}");
                }
                Domains | Prefixes => assert_eq!(ty, FieldType::StrList, "{key}"),
            }
        }
    }

    #[test]
    fn validates_entire_request_before_touching_the_backend() {
        let mut rpc =
            |_: &str, _: Value| -> Result<Value> { panic!("invalid request reached backend") };
        for values in [
            json!({"input.email.enabled":true, "keys.key_actions":{"a":"x"}}),
            json!({"input.email.enabled":true, "ui.candidate.per_page":11}),
            json!({"input.email.enabled":"true"}),
            json!({"ui.toolbar.visible":null}),
            json!({"ui.candidate.per_page":2.5}),
            json!({"schema.pinyin.separator":"semi"}),
            json!({"input.email.suffixes":["user@example.org"]}),
            json!({"input.url.prefixes":["file path"]}),
            json!({"keys.select_key_groups":["semicolon_quote","semicolon_quote"]}),
            json!({"keys.toggle_mode_keys":["rshift","any"]}),
            json!({}),
        ] {
            assert!(
                call("settings_set", &args(json!({"values":values})), &mut rpc).is_err(),
                "{values}"
            );
        }
        assert!(
            call(
                "settings_get",
                &args(json!({"keys":["ui.toolbar.visible","keys.key_actions"]})),
                &mut rpc
            )
            .is_err()
        );
        assert!(
            call(
                "settings_list",
                &args(json!({"query":"邮箱","raw":true})),
                &mut rpc
            )
            .is_err()
        );
    }

    #[test]
    fn discovery_is_local_reads_are_filtered_and_edits_are_one_submission() {
        let list = call(
            "settings_list",
            &args(json!({"query":"邮箱"})),
            &mut |_, _| panic!(),
        )
        .unwrap();
        assert!(
            list["settings"]
                .as_array()
                .unwrap()
                .iter()
                .any(|s| s["key"] == "input.email.enabled")
        );
        let result = call(
            "settings_get",
            &args(json!({"keys":["ui.toolbar.visible"]})),
            &mut |m, p| {
                assert_eq!(
                    (m, &p),
                    ("config.getItem", &json!({"key":"ui.toolbar.visible"}))
                );
                Ok(json!({"key":"ui.toolbar.visible","value":false,"other":"hidden"}))
            },
        )
        .unwrap();
        assert_eq!(result, json!({"values":{"ui.toolbar.visible":false}}));
        let values = json!({
            "input.email.suffixes":["example.org","xn--bcher-kva.example"],
            "input.url.prefixes":["www.","https"], "keys.select_key_groups":[], "ui.candidate.per_page":8
        });
        let mut requests = 0;
        let reply = call(
            "settings_set",
            &args(json!({"values":values})),
            &mut |m, p| {
                requests += 1;
                assert_eq!(m, "config.setItems");
                let items = p["items"].as_array().unwrap();
                assert_eq!(items.len(), 4);
                for item in items {
                    assert_eq!(item["value"], values[item["key"].as_str().unwrap()]);
                }
                Ok(json!({"applied":4, "skipped":[], "needsRestart":true}))
            },
        )
        .unwrap();
        assert_eq!(requests, 1);
        assert_eq!(reply["needsRestart"], true);
    }

    #[test]
    fn partial_backend_write_is_not_reported_as_success() {
        let error = call("settings_set", &args(json!({"values":{"input.email.enabled":true,"input.url.prefixes":[]}})), &mut |_,_| {
            Ok(json!({"applied":1,"skipped":[{"key":"input.url.prefixes","reason":"unavailable"}],"needsRestart":false}))
        }).unwrap_err().to_string();
        assert!(error.contains("1/2") && error.contains("input.url.prefixes"));
    }
}
