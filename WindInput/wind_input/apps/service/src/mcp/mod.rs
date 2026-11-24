//! Local MCP stdio adapter. Handshakes and tool discovery do not access the IME service.

mod dictionary;
mod settings;

use std::io::{self, BufRead, Read, Write};

use anyhow::Result;
use serde_json::{Map, Value, json};

const CURRENT: &str = "2026-07-28";
const LEGACY: [&str; 3] = ["2025-11-25", "2025-06-18", "2025-03-26"];
const META_VERSION: &str = "io.modelcontextprotocol/protocolVersion";
const META_CAPABILITIES: &str = "io.modelcontextprotocol/clientCapabilities";
const META_CLIENT: &str = "io.modelcontextprotocol/clientInfo";
const META_SERVER: &str = "io.modelcontextprotocol/serverInfo";
const MAX_LINE: u64 = 2 * 1024 * 1024;
type Backend<'a> = dyn FnMut(&str, Value) -> Result<Value> + 'a;

pub(crate) fn run(rpc: &mut Backend<'_>) -> Result<()> {
    serve(io::stdin().lock(), io::stdout().lock(), rpc)
}

fn serve(mut input: impl BufRead, mut output: impl Write, rpc: &mut Backend<'_>) -> Result<()> {
    let mut session = Session::new();
    let mut line = Vec::new();
    loop {
        line.clear();
        let read = (&mut input)
            .take(MAX_LINE + 1)
            .read_until(b'\n', &mut line)?;
        if read == 0 {
            return Ok(());
        }
        let reply = if read as u64 > MAX_LINE {
            if line.last() != Some(&b'\n') {
                input.skip_until(b'\n')?;
            }
            Some(error(&Value::Null, -32600, "MCP message exceeds 2 MiB"))
        } else {
            match serde_json::from_slice(&line) {
                Ok(message) => session.dispatch(&message, rpc),
                Err(_) => Some(error(&Value::Null, -32700, "Parse error")),
            }
        };
        if let Some(reply) = reply {
            let mut encoded = serde_json::to_vec(&reply)?;
            encoded.push(b'\n');
            if let Err(e) = output.write_all(&encoded).and_then(|()| output.flush()) {
                if e.kind() == io::ErrorKind::BrokenPipe {
                    return Ok(());
                }
                return Err(e.into());
            }
        }
    }
}

fn server_info() -> Value {
    json!({"name":"kunkun-ime", "version":env!("WIND_APP_VERSION")})
}

fn supported_versions() -> Vec<&'static str> {
    std::iter::once(CURRENT).chain(LEGACY).collect()
}

fn error(id: &Value, code: i32, message: &str) -> Value {
    json!({"jsonrpc":"2.0","id":id,"error":{"code":code,"message":message}})
}

fn invalid_params(id: &Value, message: &str) -> Value {
    error(id, -32602, message)
}

fn valid_client_info(info: &Value) -> bool {
    info["name"].is_string() && info["version"].is_string()
}

fn only(params: &Map<String, Value>, keys: &[&str]) -> bool {
    params
        .keys()
        .all(|key| key == "_meta" || keys.contains(&key.as_str()))
}

struct Session {
    legacy: Option<&'static str>,
    tools: Vec<Value>,
}

impl Session {
    fn new() -> Self {
        let mut tools = dictionary::tools();
        tools.extend(settings::tools());
        Self {
            legacy: None,
            tools,
        }
    }

    fn dispatch(&mut self, message: &Value, rpc: &mut Backend<'_>) -> Option<Value> {
        // Only the March 2025 revision requires JSON-RPC batching.
        if let Some(batch) = message.as_array() {
            if self.legacy != Some("2025-03-26") || batch.is_empty() || batch.len() > 64 {
                return Some(error(&Value::Null, -32600, "Invalid or unsupported batch"));
            }
            let replies: Vec<Value> = batch
                .iter()
                .filter_map(|item| self.single(item, rpc))
                .collect();
            return (!replies.is_empty()).then_some(Value::Array(replies));
        }
        self.single(message, rpc)
    }

    fn single(&mut self, msg: &Value, rpc: &mut Backend<'_>) -> Option<Value> {
        let id = msg.get("id");
        let valid_id = id.filter(|v| v.is_string() || v.is_i64() || v.is_u64());
        let method = msg.get("method").and_then(Value::as_str);
        if msg["jsonrpc"] != "2.0" || method.is_none() || (id.is_some() && valid_id.is_none()) {
            return Some(error(
                valid_id.unwrap_or(&Value::Null),
                -32600,
                "Invalid Request",
            ));
        }
        // Notifications (including initialized and cancelled) never execute tools or receive a reply.
        let id = valid_id?;
        let method = method.unwrap();
        let empty = Map::new();
        let params = match msg.get("params") {
            None => &empty,
            Some(Value::Object(p)) => p,
            _ => return Some(invalid_params(id, "params must be an object")),
        };
        if params.get("_meta").is_some_and(|v| !v.is_object()) {
            return Some(invalid_params(id, "_meta must be an object"));
        }
        Some(
            self.request(id, method, params, rpc)
                .unwrap_or_else(|err| err),
        )
    }

    fn version(
        &mut self,
        id: &Value,
        method: &str,
        params: &Map<String, Value>,
    ) -> Result<&'static str, Value> {
        let meta = params.get("_meta").and_then(Value::as_object);
        let declared = meta.and_then(|m| m.get(META_VERSION));
        let modern = declared.is_some() || meta.is_some_and(|m| m.contains_key(META_CAPABILITIES));
        if modern || method == "server/discover" {
            let Some(requested) = declared.and_then(Value::as_str) else {
                return Err(invalid_params(
                    id,
                    "Missing or invalid _meta protocolVersion",
                ));
            };
            if requested != CURRENT {
                let mut reply = error(id, -32022, "Unsupported protocol version");
                reply["error"]["data"] =
                    json!({"supported":supported_versions(),"requested":requested});
                return Err(reply);
            }
            let meta = meta.unwrap();
            if !meta.get(META_CAPABILITIES).is_some_and(Value::is_object)
                || meta.get(META_CLIENT).is_some_and(|v| !valid_client_info(v))
            {
                return Err(invalid_params(id, "Missing or invalid MCP client metadata"));
            }
            Ok(CURRENT)
        } else if method == "initialize" {
            if self.legacy.is_some() {
                return Err(error(id, -32600, "Already initialized"));
            }
            let requested = params.get("protocolVersion").and_then(Value::as_str);
            if !only(params, &["protocolVersion", "capabilities", "clientInfo"])
                || requested.is_none()
                || !params.get("capabilities").is_some_and(Value::is_object)
                || !params.get("clientInfo").is_some_and(valid_client_info)
            {
                return Err(invalid_params(
                    id,
                    "Expected protocolVersion, capabilities and clientInfo",
                ));
            }
            let negotiated = LEGACY
                .into_iter()
                .find(|v| Some(*v) == requested)
                .unwrap_or(LEGACY[0]);
            self.legacy = Some(negotiated);
            Ok(negotiated)
        } else if method == "ping" {
            Ok(self.legacy.unwrap_or(LEGACY[0]))
        } else if let Some(v) = self.legacy {
            Ok(v)
        } else {
            return Err(invalid_params(
                id,
                "Initialize a legacy session or supply MCP request metadata",
            ));
        }
    }

    fn request(
        &mut self,
        id: &Value,
        method: &str,
        params: &Map<String, Value>,
        rpc: &mut Backend<'_>,
    ) -> Result<Value, Value> {
        let version = self.version(id, method, params)?;
        let mut result = match method {
            "initialize" if version != CURRENT => json!({
                "protocolVersion":version, "serverInfo":server_info(), "capabilities":{"tools":{}}
            }),
            "server/discover" if only(params, &[]) => json!({
                "supportedVersions":supported_versions(), "capabilities":{"tools":{}}
            }),
            "ping" if version != CURRENT && only(params, &[]) => json!({}),
            "tools/list" if only(params, &[]) => json!({"tools":self.tools}),
            "tools/call" => self.call_tool(id, params, version, rpc)?,
            "ping" if version != CURRENT => {
                return Err(invalid_params(id, "Unexpected ping parameters"));
            }
            "tools/list" | "server/discover" => {
                return Err(invalid_params(
                    id,
                    "Unexpected parameters or invalid cursor",
                ));
            }
            _ => return Err(error(id, -32601, "Method not found")),
        };
        if version == CURRENT {
            result["resultType"] = json!("complete");
            result["_meta"] = json!({(META_SERVER):server_info()});
        }
        Ok(json!({"jsonrpc":"2.0","id":id,"result":result}))
    }

    fn call_tool(
        &self,
        id: &Value,
        params: &Map<String, Value>,
        version: &str,
        rpc: &mut Backend<'_>,
    ) -> Result<Value, Value> {
        if !only(params, &["name", "arguments"]) {
            return Err(invalid_params(id, "Unexpected tools/call parameters"));
        }
        let Some(name) = params.get("name").and_then(Value::as_str) else {
            return Err(invalid_params(id, "Missing tool name"));
        };
        if !self.tools.iter().any(|t| t["name"] == name) {
            return Err(invalid_params(id, "Unknown tool"));
        }
        let empty = Map::new();
        let args = match params.get("arguments") {
            None => &empty,
            Some(Value::Object(p)) => p,
            _ => return Err(invalid_params(id, "arguments must be an object")),
        };
        let value = if name.starts_with("dictionary_") {
            dictionary::call(name, args, rpc)
        } else {
            settings::call(name, args, rpc)
        };
        Ok(match value {
            Ok(v) => {
                let mut result = json!({"content":[{"type":"text","text":v.to_string()}]});
                if version == CURRENT || (version != "2025-03-26" && v.is_object()) {
                    result["structuredContent"] = v;
                }
                result
            }
            Err(e) => json!({"isError":true, "content":[{"type":"text","text":e.to_string()}]}),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn simulate(bytes: &[u8]) -> Vec<Value> {
        let mut output = Vec::new();
        serve(bytes, &mut output, &mut |_, _| {
            panic!("unexpected backend call")
        })
        .unwrap();
        output
            .split(|&b| b == b'\n')
            .filter(|line| !line.is_empty())
            .map(|line| serde_json::from_slice(line).unwrap())
            .collect()
    }

    fn exchanges(messages: &[Value]) -> Vec<Value> {
        let input: Vec<_> = messages.iter().map(Value::to_string).collect();
        simulate(input.join("\n").as_bytes())
    }

    fn init(version: &str) -> Value {
        json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":version,"clientInfo":{"name":"test","version":"1"},"capabilities":{}
        }})
    }

    fn modern(id: i64, method: &str, mut params: Value) -> Value {
        params["_meta"] = json!({(META_VERSION):CURRENT,(META_CAPABILITIES):{}});
        json!({"jsonrpc":"2.0","id":id,"method":method,"params":params})
    }

    #[test]
    fn modern_requests_are_stateless_and_expose_only_registered_tools() {
        let out = exchanges(&[
            modern(0, "tools/list", json!({})),
            modern(1, "server/discover", json!({})),
            modern(
                2,
                "tools/call",
                json!({"name":"settings_list","arguments":{"query":"input.email.enabled"}}),
            ),
            modern(3, "tools/call", json!({"name":"config.setItems"})),
            modern(
                4,
                "tools/call",
                json!({"name":"settings_get","arguments":{"keys":[]}}),
            ),
        ]);
        assert!(
            out[0]["result"]["tools"]
                .as_array()
                .unwrap()
                .iter()
                .all(|t| {
                    let name = t["name"].as_str().unwrap();
                    name.starts_with("settings_") || name.starts_with("dictionary_")
                })
        );
        assert_eq!(out[0]["result"]["resultType"], "complete");
        assert_eq!(
            out[1]["result"]["supportedVersions"],
            json!(supported_versions())
        );
        assert_eq!(
            out[1]["result"]["_meta"][META_SERVER]["version"],
            env!("WIND_APP_VERSION")
        );
        let call = &out[2]["result"];
        let text: Value =
            serde_json::from_str(call["content"][0]["text"].as_str().unwrap()).unwrap();
        assert_eq!(text, call["structuredContent"]);
        assert_eq!(out[3]["error"]["code"], -32602);
        assert_eq!(out[4]["result"]["isError"], true);
    }

    #[test]
    fn validates_metadata_and_ignores_notifications_without_writes() {
        let mut future = modern(1, "tools/list", json!({}));
        future["params"]["_meta"][META_VERSION] = json!("2999-12-31");
        let mut missing = modern(2, "tools/list", json!({}));
        missing["params"]["_meta"]
            .as_object_mut()
            .unwrap()
            .remove(META_CAPABILITIES);
        let out = exchanges(&[
            json!({"jsonrpc":"2.0","method":"tools/call","params":{"name":"settings_set","arguments":{}}}),
            future,
            missing,
            modern(3, "config.setItems", json!({})),
            modern(4, "tools/list", json!({"cursor":"unknown"})),
        ]);
        assert_eq!(out.len(), 4);
        assert_eq!(out[0]["error"]["code"], -32022);
        assert_eq!(out[0]["error"]["data"]["requested"], "2999-12-31");
        assert_eq!(out[1]["error"]["code"], -32602);
        assert_eq!(out[2]["error"]["code"], -32601);
        assert_eq!(out[3]["error"]["code"], -32602);
    }

    #[test]
    fn legacy_initialize_list_and_march_batch() {
        let next = json!({"jsonrpc":"2.0","method":"notifications/initialized"});
        let ping = json!({"jsonrpc":"2.0","id":"p","method":"ping"});
        let list = json!({"jsonrpc":"2.0","id":2,"method":"tools/list"});
        for revision in LEGACY {
            let out = exchanges(&[init(revision), next.clone(), ping.clone(), list.clone()]);
            assert_eq!(out.len(), 3);
            assert_eq!(out[0]["result"]["protocolVersion"], revision);
            assert_eq!(out[1]["result"], json!({}));
            assert!(out[2]["result"]["tools"].is_array());
            assert!(out[2]["result"].get("resultType").is_none());
        }
        let out = exchanges(&[
            init(LEGACY[2]),
            json!([next.clone(), ping.clone()]),
            json!([next]),
        ]);
        assert_eq!(out.len(), 2);
        assert_eq!(out[1], json!([{"jsonrpc":"2.0","id":"p","result":{}}]));
        assert_eq!(
            exchanges(&[init("unknown")])[0]["result"]["protocolVersion"],
            LEGACY[0]
        );
    }

    #[test]
    fn rejects_oversized_or_malformed_input_and_preserves_newline_framing() {
        let mut bytes = vec![b'a'; MAX_LINE as usize + 500];
        bytes.extend_from_slice(b"\n{\xff}\n");
        bytes.extend_from_slice(
            modern(9, "server/discover", json!({}))
                .to_string()
                .as_bytes(),
        );
        bytes.extend_from_slice(b"\r\n");
        let out = simulate(&bytes);
        assert_eq!(out.len(), 3);
        assert_eq!(out[0]["error"]["code"], -32600);
        assert_eq!(out[1]["error"]["code"], -32700);
        assert_eq!(out[2]["id"], 9);
    }
}
