//! 一次处理设置页当前分区中勾选的行。全部参数先校验，再交给单个存储目标提交。

use super::{WebDataRpc, store_text, str_param, user_dicts};
use anyhow::{anyhow, bail};
use serde_json::{Value, json};

pub(super) fn remove_selected<H: WebDataRpc + ?Sized>(
    h: &H,
    params: &Value,
) -> anyhow::Result<Value> {
    let target = str_param(params, "target")?;
    if !matches!(
        target,
        "dict.remove"
            | "userDicts.removeEntry"
            | "temp.remove"
            | "freq.delete"
            | "phrase.remove"
            | "shadow.removeRule"
    ) {
        bail!("不支持的批量删除目标：{target}");
    }
    let items = params
        .get("entries")
        .and_then(Value::as_array)
        .ok_or_else(|| anyhow!("entries 必须是数组"))?;
    if let Some(reason) = h.storage_readonly_reason() {
        bail!("{reason}");
    }
    let schema = if target == "phrase.remove" {
        String::new()
    } else {
        h.engine_mgr()
            .data_schema_id(str_param(params, "schemaId")?)
    };
    let rows = items
        .iter()
        .map(|row| {
            let code = str_param(row, "code")?;
            let text = str_param(row, "text")?;
            let cand_id = match row.get("candId") {
                None | Some(Value::Null) => None,
                Some(Value::String(id)) if target == "shadow.removeRule" => Some(id.as_str()),
                _ => bail!("candId 只用于候选调整且必须为字符串"),
            };
            Ok((code, text, cand_id))
        })
        .collect::<anyhow::Result<Vec<_>>>()?;
    let store = h.user_store().ok_or_else(|| anyhow!("无持久化存储"))?;

    let removed = if target == "shadow.removeRule" {
        // 候选调整列表直接返回规则的 word，不能按 UI 转义文本再处理。
        store.remove_shadow_rules(&schema, &rows)?
    } else {
        let decoded: Vec<_> = rows
            .iter()
            .map(|(code, text, _)| {
                let code = match target {
                    "dict.remove" | "temp.remove" | "freq.delete" => {
                        wind_store::wdict::split_spaced_code(code).0
                    }
                    _ => code.to_string(),
                };
                (code, store_text(text))
            })
            .collect();
        let keys: Vec<_> = decoded
            .iter()
            .map(|(code, text)| (code.as_str(), text.as_str()))
            .collect();
        match target {
            "dict.remove" => store.remove_user_words(&schema, &keys)?,
            "userDicts.removeEntry" => user_dicts::remove_entries(h, params, &keys)?,
            "temp.remove" => store.remove_temp_words(&schema, &keys)?,
            "freq.delete" => store.delete_selected_freq(&schema, &keys)?,
            "phrase.remove" => {
                let removed = store.remove_user_phrases(&keys)?;
                if removed != 0 {
                    h.restore_missing_system_phrases("批量删除短语");
                    h.rebuild_phrases();
                }
                removed
            }
            _ => unreachable!(),
        }
    };
    Ok(json!({ "ok": true, "removed": removed }))
}
