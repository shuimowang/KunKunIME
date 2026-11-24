//! Personal dictionary operations exposed to a local MCP client.
use super::Backend;
use anyhow::{Context, Result, bail, ensure};
use serde::Deserialize;
use serde_json::{Map, Value, json};
use wind_store::wdict::{WordIo, escape_text_field, export_words_wdict, unescape_text_field};

const BATCH: usize = 500;
const TEXT_MAX: usize = 10_000;
const CODE_MAX: usize = 128;
const DEFAULT_WEIGHT: i32 = 1200;

fn tool(
    name: &str,
    description: &str,
    properties: Value,
    required: &[&str],
    read: bool,
    destructive: bool,
) -> Value {
    json!({
        "name": name, "description": description,
        "inputSchema": {"type":"object","properties":properties,"required":required,"additionalProperties":false},
        "annotations":{"readOnlyHint":read,"destructiveHint":destructive,"idempotentHint":name != "dictionary_create_library","openWorldHint":false}
    })
}

pub(super) fn tools() -> Vec<Value> {
    let scheme = json!({"type":"string","enum":["pinyin","shuangpin","english"],"default":"pinyin","description":"全拼与双拼共用拼音个人词库。"});
    let library = json!({"type":"string","minLength":1,"maxLength":64,"description":"分库名称；省略此项表示主词库。"});
    let code = json!({"type":"string","minLength":1,"maxLength":CODE_MAX,"description":"词库编码。拼音与双拼均用全拼，如 xi an；多音人名可显式指定。"});
    let text = json!({"type":"string","minLength":1,"maxLength":TEXT_MAX});
    let weight = json!({"type":"integer","minimum":i32::MIN,"maximum":i32::MAX});
    let import_rows = json!({"type":"array","minItems":1,"maxItems":BATCH,"items":{"type":"object","properties":{"text":text,"code":code,"weight":weight},"required":["text"],"additionalProperties":false}});
    vec![
        tool(
            "dictionary_list_libraries",
            "列出方案的主词库及个人分库。搜索或写入分库时使用 name 字段。",
            json!({"schema":scheme}),
            &[],
            true,
            false,
        ),
        tool(
            "dictionary_search",
            "分页浏览个人词条，或按词语或编码搜索；offset 从 0 开始。返回词库实际使用的编码和词语，可用于精确删除。",
            json!({"schema":scheme,"library":library,"query":{"type":"string"},"offset":{"type":"integer","minimum":0,"maximum":u32::MAX},"limit":{"type":"integer","minimum":1,"maximum":200,"default":50}}),
            &[],
            true,
            false,
        ),
        tool(
            "dictionary_import",
            "将 1 至 500 个词一次合并到主词库。拼音、双拼可省略 code 自动生成全拼，英文须给出 code。默认权重 1200；同码同词导入合并较大权重，用 dictionary_set_entry 可降低权重。会报告被忽略或隐藏的条目。",
            json!({"schema":scheme,"entries":import_rows}),
            &["entries"],
            false,
            false,
        ),
        tool(
            "dictionary_set_entry",
            "添加一个个人词条，或精确修改其权重；按编码和词语两项定位。支持主词库及现有分库。",
            json!({"schema":scheme,"library":library,"code":code,"text":text,"weight":weight}),
            &["code", "text", "weight"],
            false,
            true,
        ),
        tool(
            "dictionary_remove",
            "一次提交删除列出的确切（编码、词语）对。同码的其他候选保留。建议先搜索，直接使用查询结果中的 code 和 text。",
            json!({"schema":scheme,"library":library,"entries":{"type":"array","minItems":1,"maxItems":BATCH,"items":{"type":"object","properties":{"code":code,"text":text},"required":["code","text"],"additionalProperties":false}}}),
            &["entries"],
            false,
            true,
        ),
        tool(
            "dictionary_create_library",
            "创建新的个人分库；已有同名分库会报错。可同时导入 1 至 500 个初始词条，规则同 dictionary_import；不传 entries 则创建空库。",
            json!({"schema":scheme,"library":{"type":"string","minLength":1,"maxLength":64},"entries":import_rows}),
            &["library"],
            false,
            false,
        ),
    ]
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Row {
    text: String,
    #[serde(default)]
    code: Option<String>,
    #[serde(default)]
    weight: Option<i32>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Key {
    code: String,
    text: String,
}

fn string<'a>(args: &'a Map<String, Value>, key: &str) -> Result<Option<&'a str>> {
    args.get(key)
        .map(|v| v.as_str().with_context(|| format!("{key} 必须是字符串")))
        .transpose()
}

fn fields(args: &Map<String, Value>, allowed: &[&str]) -> Result<()> {
    if let Some(key) = args
        .keys()
        .find(|k| k.as_str() != "schema" && !allowed.contains(&k.as_str()))
    {
        bail!("不支持的参数：{key}");
    }
    Ok(())
}

fn validate_field(value: &str, name: &str, max: usize) -> Result<()> {
    ensure!(
        !value.is_empty() && value == value.trim() && value.chars().count() <= max,
        "{name} 请填写 1 至 {max} 个字符，首尾不留空格"
    );
    Ok(())
}

fn code_for_write(schema: &str, code: &str) -> Result<String> {
    validate_field(code, "code", CODE_MAX)?;
    ensure!(!code.chars().any(char::is_control), "code 不能含控制字符");
    if schema == "english" {
        return Ok(code.to_owned());
    }
    let spaced = code.to_lowercase().replace('ü', "v").replace('\'', " ");
    ensure!(
        spaced.bytes().all(|b| b.is_ascii_lowercase() || b == b' '),
        "拼音编码请用无声调全拼，音节间可加空格"
    );
    let syllables: Vec<_> = spaced.split_whitespace().collect();
    ensure!(!syllables.is_empty(), "缺少拼音音节");
    Ok(syllables.join(" "))
}

fn import_content(schema: &str, entries: &Value, rpc: &mut Backend<'_>) -> Result<String> {
    let entries = entries.as_array().context("entries 必须是数组")?;
    ensure!(
        (1..=BATCH).contains(&entries.len()),
        "每批须含 1 至 {BATCH} 条"
    );
    let mut words = Vec::with_capacity(entries.len());
    let mut missing = Vec::new();
    for (i, v) in entries.iter().enumerate() {
        let row: Row = serde_json::from_value(v.clone())
            .with_context(|| format!("第 {} 条的字段不正确", i + 1))?;
        for k in ["code", "weight"] {
            ensure!(
                !v.get(k).is_some_and(Value::is_null),
                "第 {} 条的 {k} 不能为 null，可省略该字段",
                i + 1
            );
        }
        validate_field(&row.text, "text", TEXT_MAX)?;
        ensure!(
            !row.text.chars().any(char::is_control),
            "导入的词条须为单行且不含控制字符"
        );
        let code = match row.code {
            Some(code) => code_for_write(schema, &code)?,
            None => {
                ensure!(schema != "english", "英文词条须提供 code");
                missing.push(i);
                String::new()
            }
        };
        words.push(WordIo {
            text: row.text,
            code,
            weight: row.weight.unwrap_or(DEFAULT_WEIGHT),
            ..Default::default()
        });
    }
    if !missing.is_empty() {
        let texts: Vec<_> = missing
            .iter()
            .map(|&i| escape_text_field(&words[i].text))
            .collect();
        let encoded = rpc("dict.encodeWords", json!({"schemaId":schema,"texts":texts}))?;
        let codes = encoded["codes"]
            .as_array()
            .context("输入法没有返回编码结果")?;
        ensure!(
            codes.len() == missing.len(),
            "输入法返回的编码数量不匹配，未写入"
        );
        for (&i, code) in missing.iter().zip(codes) {
            words[i].code =
                code_for_write(schema, code.as_str().unwrap_or("")).with_context(|| {
                    format!("第 {} 个词条无法自动编码，请显式提供 code；未写入", i + 1)
                })?;
        }
    }
    // 使用与备份相同的转义/解析规则，词条正文不能被误认成文件头或额外数据段。
    let content = export_words_wdict(&words, &chrono::Utc::now().to_rfc3339());
    let params = json!({"schemaId":schema,"content":content,"sections":["userWords"]});
    let preview = rpc("dict.previewImport", params)?;
    let section = preview["sections"]
        .as_array()
        .and_then(|sections| sections.iter().find(|s| s["key"] == "userWords"))
        .context("导入预览缺少用户词库段，未写入")?;
    ensure!(
        section["count"].as_u64() == Some(words.len() as u64)
            && section["skipped"].as_u64() == Some(0),
        "部分词条未通过导入校验，未写入。请检查词语和拼音：{}",
        section["rejectedSamples"]
    );
    Ok(content)
}

pub(super) fn call(name: &str, args: &Map<String, Value>, rpc: &mut Backend<'_>) -> Result<Value> {
    let schema = string(args, "schema")?.unwrap_or("pinyin");
    ensure!(
        matches!(schema, "pinyin" | "shuangpin" | "english"),
        "不支持的方案：{schema}"
    );
    let library = string(args, "library")?;
    if let Some(lib) = library {
        wind_store::user_dicts::validate_name(lib)?;
    }
    let mut params = json!({"schemaId":schema});
    if let Some(lib) = library {
        params["name"] = json!(lib);
    }
    match name {
        "dictionary_list_libraries" => {
            fields(args, &[])?;
            let libraries = rpc("userDicts.list", params)?;
            Ok(json!({"schema":schema,"main":{"label":"主词库"},"libraries":libraries}))
        }
        "dictionary_search" => {
            fields(args, &["library", "query", "limit", "offset"])?;
            let limit = args
                .get("limit")
                .map_or(Some(50), Value::as_u64)
                .context("limit 必须是整数")?;
            let offset = args
                .get("offset")
                .map_or(Some(0), Value::as_u64)
                .context("offset 必须是整数")?;
            ensure!(
                (1..=200).contains(&limit) && offset <= u32::MAX as u64,
                "limit 须在 1–200 之间，offset 须在 0–4294967295 之间"
            );
            params["query"] = json!(string(args, "query")?.unwrap_or(""));
            params["limit"] = json!(limit);
            params["offset"] = json!(offset);
            let mut page = rpc(
                if library.is_some() {
                    "userDicts.entries"
                } else {
                    "dict.listPaged"
                },
                params,
            )?;
            let total = page["total"].as_u64().context("返回的词条总数无效")?;
            let items = page["items"].as_array_mut().context("返回的词条列表无效")?;
            for item in &mut *items {
                let text = item["text"].as_str().context("返回的词条缺少 text")?;
                item["text"] = json!(unescape_text_field(text));
            }
            let next = offset + items.len() as u64;
            page["nextOffset"] = json!((next < total).then_some(next));
            Ok(page)
        }
        "dictionary_import" | "dictionary_create_library" => {
            let create = name == "dictionary_create_library";
            if create {
                fields(args, &["library", "entries"])?;
                ensure!(library.is_some(), "请指定新分库的 library 名称");
                if !args.contains_key("entries") {
                    return rpc("userDicts.create", params);
                }
            } else {
                fields(args, &["entries"])?;
            }
            let content =
                import_content(schema, args.get("entries").context("缺少 entries")?, rpc)?;
            params["content"] = Value::String(content);
            if create {
                rpc("userDicts.importAs", params)
            } else {
                params["sections"] = json!(["userWords"]);
                params["strategy"] = json!("merge");
                rpc("dict.import", params)
            }
        }
        "dictionary_set_entry" => {
            fields(args, &["library", "code", "text", "weight"])?;
            let code = code_for_write(schema, string(args, "code")?.context("缺少 code")?)?;
            let text = string(args, "text")?.context("缺少 text")?;
            validate_field(text, "text", TEXT_MAX)?;
            ensure!(
                !text.chars().any(char::is_control),
                "词条须为单行且不含控制字符"
            );
            let weight = args
                .get("weight")
                .and_then(Value::as_i64)
                .and_then(|v| i32::try_from(v).ok())
                .context("weight 须是 32 位整数")?;
            params["code"] = json!(code);
            params["text"] = json!(escape_text_field(text));
            params["weight"] = json!(weight);
            rpc(
                if library.is_some() {
                    "userDicts.addEntry"
                } else {
                    "dict.update"
                },
                params,
            )
        }
        "dictionary_remove" => {
            fields(args, &["library", "entries"])?;
            let rows = args
                .get("entries")
                .and_then(Value::as_array)
                .context("entries 必须是数组")?;
            ensure!(
                (1..=BATCH).contains(&rows.len()),
                "每批须含 1 至 {BATCH} 条"
            );
            let mut keys = Vec::with_capacity(rows.len());
            for (i, row) in rows.iter().enumerate() {
                let key: Key = serde_json::from_value(row.clone())
                    .with_context(|| format!("第 {} 条须提供 code 和 text", i + 1))?;
                validate_field(&key.code, "code", CODE_MAX)?;
                ensure!(
                    !key.code.chars().any(char::is_control),
                    "code 不能含控制字符"
                );
                ensure!(
                    !key.text.is_empty() && key.text.chars().count() <= TEXT_MAX,
                    "text 不能为空或过长"
                );
                keys.push(json!({"code":key.code,"text":escape_text_field(&key.text)}));
            }
            params["entries"] = json!(keys);
            params["target"] = json!(if library.is_some() {
                "userDicts.removeEntry"
            } else {
                "dict.remove"
            });
            rpc("dict.removeSelected", params)
        }
        _ => bail!("未知词库工具：{name}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;
    use std::process::Command;
    use std::sync::Arc;
    use wind_config::Config;
    use wind_coordinator::Coordinator;
    use wind_store::Store;
    use wind_webdata::WebDataRpc;

    #[test]
    fn invalid_or_cross_library_batches_never_write() {
        for (name, args) in [
            (
                "dictionary_import",
                json!({"library":"其他","entries":[{"text":"甲","code":"jia"}]}),
            ),
            (
                "dictionary_import",
                json!({"strategy":"replace","entries":[{"text":"甲","code":"jia"}]}),
            ),
            (
                "dictionary_import",
                json!({"schema":"english","entries":[{"text":"word"}]}),
            ),
            (
                "dictionary_remove",
                json!({"entries":[{"text":"甲","code":"jia"},{"code":"jia"}]}),
            ),
            ("dictionary_remove", json!({"entries":[]})),
            (
                "dictionary_remove",
                json!({"library":"../其他","entries":[{"text":"甲","code":"jia"}]}),
            ),
            ("dictionary_create_library", json!({"library":"D:/outside"})),
            ("dictionary_search", json!({"schema":"../../bad"})),
            ("dictionary_search", json!({"limit":0})),
        ] {
            let result = call(name, args.as_object().unwrap(), &mut |_, _| {
                panic!("should not contact backend")
            });
            assert!(result.is_err(), "{name}: {args}");
        }
    }

    #[test]
    fn dictionary_roundtrip() {
        // The engine resolves global configuration through process-wide paths. Use a fresh process
        // and a portable install so this test can never read or write the user's dictionary.
        const FIXTURE: &str = "KUNKUN_MCP_DICTIONARY_FIXTURE";
        let Some(dir) = std::env::var_os(FIXTURE) else {
            let dir = tempfile::tempdir().unwrap();
            std::fs::write(dir.path().join("portable_mode"), "").unwrap();
            let status = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "mcp::dictionary::tests::dictionary_roundtrip",
                    "--nocapture",
                ])
                .env(FIXTURE, dir.path())
                .env("KUNKUN_INSTALL_ROOT", dir.path())
                .env("KUNKUN_DATADIR_CONF", dir.path().join("test-data-dir.conf"))
                .status()
                .unwrap();
            assert!(status.success());
            return;
        };
        let dir = Path::new(&dir);
        assert_eq!(Config::user_config_dir(), Some(dir.join("userdata")));
        let data = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../data");
        let cfg = Config::load(Some(&data)).unwrap();
        let store = Arc::new(Store::open(dir.join("test.redb")).unwrap());
        let coord = Coordinator::new_headless_with_store(cfg, Some(&data), store);
        let mut writes = Vec::new();
        let mut run = |name: &str, params: Value| {
            call(name, params.as_object().unwrap(), &mut |m, p| {
                if !matches!(
                    m,
                    "dict.encodeWords"
                        | "dict.previewImport"
                        | "dict.listPaged"
                        | "userDicts.entries"
                        | "userDicts.list"
                ) {
                    writes.push(m.to_owned());
                }
                coord.web_data_rpc(m, &p)
            })
        };
        let entries = json!([{"text":"协议"},{"text":"写意","code":"xie yi","weight":842}]);
        let add = run("dictionary_import", json!({"entries":entries})).unwrap();
        assert_eq!(add["sections"][0]["added"], 2, "{add}");
        let repeat = run("dictionary_import", json!({"entries":entries})).unwrap();
        assert_eq!(repeat["sections"][0]["unchanged"], 2, "{repeat}");
        let list = run(
            "dictionary_search",
            json!({"schema":"shuangpin","query":"xie yi","limit":1}),
        )
        .unwrap();
        assert_eq!(list["total"], 2);
        assert_eq!(list["items"].as_array().unwrap().len(), 1);
        assert_eq!(list["nextOffset"], 1);
        run(
            "dictionary_create_library",
            json!({"library":"书中人物","entries":entries}),
        )
        .unwrap();
        // A repeated new-library request must preserve that library's original contents.
        assert!(
            run(
                "dictionary_create_library",
                json!({"library":"书中人物","entries":[{"text":"其他"}]})
            )
            .is_err()
        );
        let named = run("dictionary_list_libraries", json!({})).unwrap();
        assert!(
            named["libraries"]
                .as_array()
                .unwrap()
                .iter()
                .any(|lib| lib["name"] == "书中人物" && lib["entries"] == 2)
        );
        run(
            "dictionary_set_entry",
            json!({"library":"书中人物","text":"写意","code":"xie yi","weight":173}),
        )
        .unwrap();
        let page = run(
            "dictionary_search",
            json!({"library":"书中人物","query":"写意"}),
        )
        .unwrap();
        assert_eq!(page["items"][0]["weight"], 173);
        for library in [None, Some("书中人物")] {
            let mut remove = json!({"entries":[{"code":"xie yi","text":"协议"}]});
            let mut list = json!({"query":"xie yi"});
            if let Some(library) = library {
                remove["library"] = json!(library);
                list["library"] = json!(library);
            }
            let result = run("dictionary_remove", remove).unwrap();
            assert_eq!(result["removed"], 1);
            let select = run("dictionary_search", list).unwrap();
            assert_eq!(select["total"], 1, "{select}");
            assert_eq!(select["items"][0]["text"], "写意");
        }
        assert_eq!(
            writes
                .iter()
                .filter(|m| m.as_str() == "dict.removeSelected")
                .count(),
            2
        );
    }
}
