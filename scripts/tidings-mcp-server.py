#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""tidings-mcp-server —— Tidings 局域网协同网格的 Stdio MCP Server（形态 C）。

Antigravity 接入设计（RFC §3.3）：让 Antigravity（或任何 MCP 宿主）在执行复杂
编排时**主动**调用 Tidings 的局域网能力。配置落点：

    ~/.gemini/config/mcp_config.json：
    {
      "mcpServers": {
        "tidings": {
          "command": "python3",
          "args": ["/path/to/tidings-mcp-server.py"],
          "env": { "TIDINGS_HOST_URL": "http://127.0.0.1:8788" }
        }
      }
    }

## 三个工具（RFC §3.3 清单）

- `tidings_fetch_node_capabilities`：当前局域网在线算力节点与能力标签
  （`GET /v1/peers` 的 MCP 包装）；
- `tidings_request_human_approval`：挂起等机主手机审批（`POST /v1/agent/hook/approval`，
  fail-closed：超时/不可达 = deny）；
- `tidings_send_group_card`：把一张结构化卡落进某个 hook 会话的台账
  （`POST /v1/agent/hook/card`，随会话分页到手机；预算 24 KiB 超限拒绝）。

## 协议

MCP 的 stdio 形态：每行一个 JSON-RPC 2.0 消息。实现 initialize / notifications/
initialized / ping / tools/list / tools/call —— 标准库实现，零第三方依赖。
"""

import json
import os
import sys
import urllib.error
import urllib.request

HOST_URL = os.environ.get("TIDINGS_HOST_URL", "http://127.0.0.1:8788").rstrip("/")
SERVER_NAME = "tidings"
SERVER_VERSION = "1.0.0"

TOOLS = [
    {
        "name": "tidings_fetch_node_capabilities",
        "description": (
            "List online Tidings LAN nodes and their capability/hardware tags "
            "(e.g. gpu:rtx4090, macos). Use it to pick a target node before dispatching work."
        ),
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "tidings_request_human_approval",
        "description": (
            "Ask the owner's phone for approval of a risky action. Blocks until the owner "
            "decides or the timeout expires. FAIL-CLOSED: unreachable host or timeout => deny. "
            "Requires an announced Antigravity session (conversationId) for the approval card."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "conversationId": {"type": "string", "description": "Antigravity conversation id"},
                "tool": {"type": "string", "description": "Tool name, e.g. git push"},
                "summary": {"type": "string", "description": "One-line human-readable summary"},
                "timeoutMs": {"type": "integer", "description": "Wait budget, 1000..180000, default 60000"},
            },
            "required": ["tool", "summary"],
        },
    },
    {
        "name": "tidings_send_group_card",
        "description": (
            "Record a structured card ( declarative card JSON, type/cardId/rev, 24KiB budget) "
            "into a Tidings agent session; it becomes visible on the owner's phone session view. "
            "Requires an announced session."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "conversationId": {"type": "string"},
                "card": {"type": "string", "description": "Card JSON as a string"},
            },
            "required": ["conversationId", "card"],
        },
    },
]


def bearer() -> str:
    token = os.environ.get("TIDINGS_PLUGIN_TOKEN")
    if token:
        return token.strip()
    for candidate in (
        os.path.expanduser("~/.tidings/plugins/plugin-token"),
        os.path.expanduser("~/Library/Application Support/捎话 Tidings/plugins/plugin-token"),
    ):
        try:
            with open(candidate, "r", encoding="utf-8") as fh:
                content = fh.read().strip()
                if content:
                    return content
        except OSError:
            continue
    return ""


def call(method: str, path: str, payload=None, timeout: float = 90.0):
    """宿主回环 HTTP：返回 (ok, parsed)。安全面（审批）失败一律 fail-closed。"""
    req = urllib.request.Request(
        HOST_URL + path,
        data=None if payload is None else json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + bearer()},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return True, (json.loads(body) if body.strip() else {})
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as exc:
        return False, {"error": str(exc)}


def tool_fetch_capabilities(_args: dict) -> dict:
    ok, payload = call("GET", "/v1/peers")
    if not ok:
        return {"isError": True, "content": [{"type": "text", "text": "host unreachable: " + json.dumps(payload, ensure_ascii=False)}]}
    return {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False, indent=2)}]}


def tool_request_approval(args: dict) -> dict:
    ok, payload = call("POST", "/v1/agent/hook/approval", {
        "conversationId": args.get("conversationId", ""),
        "tool": args.get("tool"),
        "summary": args.get("summary"),
        "timeoutMs": min(max(int(args.get("timeoutMs", 60000)), 1000), 180000),
    })
    if not ok:
        # fail-closed：审批面不可达 = 拒绝，绝不把"联系不上"读成"默认同意"。
        return {"content": [{"type": "text", "text": json.dumps({"decision": "deny", "reason": "host unreachable (fail-closed)"}, ensure_ascii=False)}]}
    return {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}]}


def tool_send_card(args: dict) -> dict:
    ok, payload = call("POST", "/v1/agent/hook/card", {
        "conversationId": args.get("conversationId", ""),
        "card": args.get("card", ""),
    })
    if not ok:
        return {"isError": True, "content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}]}
    return {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}]}


HANDLERS = {
    "tidings_fetch_node_capabilities": tool_fetch_capabilities,
    "tidings_request_human_approval": tool_request_approval,
    "tidings_send_group_card": tool_send_card,
}


def dispatch(msg: dict) -> dict | None:
    """一条 JSON-RPC 消息 → 应答（notification 返回 None）。"""
    method = msg.get("method", "")
    msg_id = msg.get("id")
    if method == "initialize":
        return {
            "jsonrpc": "2.0", "id": msg_id,
            "result": {
                "protocolVersion": msg.get("params", {}).get("protocolVersion", "2024-11-05"),
                "capabilities": {"tools": {}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            },
        }
    if method == "notifications/initialized" or method.startswith("notifications/"):
        return None
    if method == "ping":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": TOOLS}}
    if method == "tools/call":
        params = msg.get("params", {})
        handler = HANDLERS.get(params.get("name", ""))
        if handler is None:
            return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32602, "message": "unknown tool"}}
        result = handler(params.get("arguments", {}) or {})
        return {"jsonrpc": "2.0", "id": msg_id, "result": result}
    if msg_id is not None:
        return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32601, "message": "method not found: " + method}}
    return None


def main() -> int:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        reply = dispatch(msg)
        if reply is not None:
            sys.stdout.write(json.dumps(reply, ensure_ascii=False))
            sys.stdout.write("\n")
            sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
