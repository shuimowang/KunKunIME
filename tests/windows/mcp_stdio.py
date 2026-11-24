"""Exercise the packaged GUI-subsystem executable through a client's stdio pipes."""
import json
from pathlib import Path
import subprocess
import sys


def request(i, method, params):
    return {"jsonrpc": "2.0", "id": i, "method": method, "params": params}


def exchange(exe, messages):
    wire = "".join(json.dumps(message, ensure_ascii=False) + "\n" for message in messages)
    process = subprocess.run([str(exe), "mcp"], input=wire.encode("utf-8"),
                             capture_output=True, check=True, timeout=25)
    return [json.loads(line) for line in process.stdout.decode("utf-8").splitlines()]


def main(exe):
    init = {"protocolVersion": "2025-11-25", "capabilities": {},
            "clientInfo": {"name": "kunkun-ci", "version": "1"}}
    responses = exchange(exe, [
        request(1, "initialize", init),
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        request(2, "tools/list", {}),
        request(3, "tools/call", {"name": "settings_list", "arguments": {"query": "邮箱"}}),
    ])
    assert [r["id"] for r in responses] == [1, 2, 3]
    assert responses[0]["result"]["protocolVersion"] == init["protocolVersion"]
    names = {t["name"] for t in responses[1]["result"]["tools"]}
    assert {"settings_get", "settings_set", "dictionary_import", "dictionary_remove"} <= names
    result = responses[2]["result"]
    assert not result.get("isError") and "input.email.enabled" in result["content"][0]["text"]

    metadata = {"io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities": {}}
    discover, listed = exchange(exe, [request(4, "server/discover", {"_meta": metadata}),
                                      request(5, "tools/list", {"_meta": metadata})])
    assert discover["result"]["resultType"] == "complete"
    assert "2026-07-28" in discover["result"]["supportedVersions"]
    assert {t["name"] for t in listed["result"]["tools"]} == names
    print(f"MCP stdio verified with {len(names)} tools on both protocol paths")


if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve(strict=True))
