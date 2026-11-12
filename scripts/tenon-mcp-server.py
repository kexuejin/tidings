#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""tenon-mcp-server —— 榫卯 Tenon 局域网协同网格的 Stdio MCP Server（形态 C）。

Antigravity 接入设计（RFC §3.3）：让 Antigravity（或任何 MCP 宿主）在执行复杂
编排时**主动**调用 Tenon 的局域网能力。配置落点：

    ~/.gemini/config/mcp_config.json：
    {
      "mcpServers": {
        "tenon": {
          "command": "python3",
          "args": ["/path/to/tenon-mcp-server.py"],
          "env": { "TENON_HOST_URL": "http://127.0.0.1:8788" }
        }
      }
    }

## 四个工具（RFC §3.3 清单，支持 tenon_* 规范命名）

- `tenon_fetch_node_capabilities`：当前局域网在线算力节点与能力标签
  （`GET /v1/peers` 的 MCP 包装）；
- `tenon_request_human_approval`：挂起等机主手机审批（`POST /v1/agent/hook/approval`，
  fail-closed：超时/不可达 = deny）；
- `tenon_send_group_card`：把一张结构化卡落进某个 hook 会话的台账
  （`POST /v1/agent/hook/card`，随会话分页到手机；预算 24 KiB 超限拒绝）；
- `tenon_ask_question`：向用户手机发起决策卡，直接收集多选回答并恢复执行。

## 协议

MCP 的 stdio 形态：每行一个 JSON-RPC 2.0 消息。实现 initialize / notifications/
initialized / ping / tools/list / tools/call —— 标准库实现，零第三方依赖。
"""

import json
import os
import sys
import urllib.error
import urllib.request

HOST_URL = os.environ.get("TENON_HOST_URL", "http://127.0.0.1:8788").rstrip("/")
SERVER_NAME = "tenon"
SERVER_VERSION = "1.0.0"

# 只读工具的强边界：必须显式注入工作区根；没有就 fail-closed，绝不拿进程 cwd 猜。
WORKSPACE_ROOT = os.environ.get("TENON_MCP_WORKSPACE", "").strip()

# 这些名字一旦可读，等于把宿主凭据/密钥交给读不可信仓库的模型。
FORBIDDEN_PATH_PARTS = (
    "/.tenon/", "/.ssh/", "/.aws/", "/.gnupg/", "/.config/", "/Library/Keychains/",
    "plugin-token", "credentials", "id_rsa", "id_ed25519", ".env",
)

TOOLS = [
    {
        "name": "tenon_fetch_node_capabilities",
        "description": (
            "List online Tenon LAN nodes and their capability/hardware tags "
            "(e.g. gpu:rtx4090, macos). Use it to pick a target node before dispatching work."
        ),
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "tenon_request_human_approval",
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
        "name": "tenon_send_group_card",
        "description": (
            "Record a structured card ( declarative card JSON, type/cardId/rev, 24KiB budget) "
            "into a Tenon agent session; it becomes visible on the owner's phone session view. "
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
    {
        "name": "tenon_ask_question",
        "description": (
            "Ask the user a multiple-choice question on their phone or in the group chat. "
            "Blocks until the user selects an option or timeout expires. "
            "Renders a native OptionList card on mobile."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "question": {"type": "string", "description": "The question or title to present"},
                "options": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "List of choice labels, e.g. ['选项一', '选项二']",
                },
                "groupId": {"type": "string", "description": "Optional group ID to broadcast card to"},
                "conversationId": {"type": "string", "description": "Optional conversation ID"},
                "timeoutMs": {"type": "integer", "description": "Wait budget in ms, 1000..300000, default 180000"},
            },
            "required": ["question", "options"],
        },
    },
    {
        "name": "tenon_fs_read",
        "description": "Read file contents within the current project/workspace. Read-only with strict path traversal checks.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Relative or absolute file path"},
                "maxBytes": {"type": "integer", "description": "Max bytes to read (default 32768)"},
            },
            "required": ["path"],
        },
    },
    {
        "name": "tenon_git_diff",
        "description": "Get git diff for the current workspace. Read-only.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "cached": {"type": "boolean", "description": "Whether to get staged diff (--cached)"},
            },
            "required": [],
        },
    },
    {
        "name": "tenon_terminal_exec",
        "description": "Execute a terminal command with host-mediated safety and exit code monitoring. Subject to approval.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Command and arguments, e.g. ['git', 'status']",
                },
                "workdir": {"type": "string", "description": "Optional working directory relative to workspace"},
                "timeoutSec": {"type": "integer", "description": "Command timeout in seconds (default 60)"},
            },
            "required": ["command"],
        },
    },
]


def bearer() -> str:
    token = os.environ.get("TENON_PLUGIN_TOKEN")
    if token:
        return token.strip()
    for candidate in (
        os.path.expanduser("~/.tenon/plugins/plugin-token"),
        os.path.expanduser("~/Library/Application Support/榫卯 Tenon/plugins/plugin-token"),
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
    approval_payload = {
        "conversationId": args.get("conversationId", ""),
        "tool": args.get("tool"),
        "summary": args.get("summary"),
        "timeoutMs": min(max(int(args.get("timeoutMs", 60000)), 1000), 180000),
    }
    # 宿主派单注入的 Tenon sessionId（提案 §9 阶段三对齐键）：有就透传，
    # 审批卡归到派单会话；独立跑没有这个 env，宿主回退合成池键（旧行为）。
    hook_session = (os.environ.get("TENON_HOOK_SESSION_ID") or "").strip()
    if hook_session:
        approval_payload["sessionId"] = hook_session
    ok, payload = call("POST", "/v1/agent/hook/approval", approval_payload)
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


def tool_ask_question(args: dict) -> dict:
    timeout_ms = min(max(int(args.get("timeoutMs", 180000)), 1000), 300000)
    ok, payload = call("POST", "/v1/agent/hook/question", {
        "question": args.get("question"),
        "options": args.get("options", []),
        "conversationId": args.get("conversationId", ""),
        "groupId": args.get("groupId", ""),
        "timeoutMs": timeout_ms,
    }, timeout=(timeout_ms / 1000.0) + 10.0)
    if not ok:
        return {"content": [{"type": "text", "text": json.dumps({"status": "timeout", "decision": "deny", "reason": "host unreachable (fail-closed)"}, ensure_ascii=False)}]}
    return {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}]}


def _confined_path(path: str) -> str | None:
    """把请求路径解析到工作区根内；越界/没有根/命中敏感名字一律 None。"""
    if not WORKSPACE_ROOT or not isinstance(path, str) or not path.strip() or "\x00" in path:
        return None
    root = os.path.realpath(WORKSPACE_ROOT)
    raw = path.strip()
    candidate = raw if os.path.isabs(raw) else os.path.join(root, raw)
    resolved = os.path.realpath(candidate)
    try:
        inside = os.path.commonpath([root, resolved]) == root
    except ValueError:
        return None
    if not inside:
        return None
    probe = "/" + resolved.replace(os.sep, "/").strip("/") + "/"
    if any(part in probe for part in FORBIDDEN_PATH_PARTS):
        return None
    return resolved


def tool_fs_read(args: dict) -> dict:
    try:
        max_bytes = int(args.get("maxBytes", 32768))
    except (TypeError, ValueError):
        max_bytes = 32768
    max_bytes = max(1, min(max_bytes, 65536))
    resolved = _confined_path(args.get("path", ""))
    if resolved is None:
        return {"isError": True, "content": [{"type": "text", "text": "path rejected: outside workspace or forbidden"}]}
    if not os.path.isfile(resolved):
        return {"isError": True, "content": [{"type": "text", "text": f"file not found: {args.get('path', '')}"}]}
    try:
        with open(resolved, "r", encoding="utf-8", errors="replace") as fh:
            data = fh.read(max_bytes)
        return {"content": [{"type": "text", "text": data}]}
    except OSError as exc:
        return {"isError": True, "content": [{"type": "text", "text": f"read error: {exc}"}]}


def tool_git_diff(args: dict) -> dict:
    import subprocess
    root = os.path.realpath(WORKSPACE_ROOT) if WORKSPACE_ROOT else ""
    if not root or not os.path.isdir(root):
        return {"isError": True, "content": [{"type": "text", "text": "workspace root is not set"}]}
    cmd = ["git", "-C", root, "diff", "--no-ext-diff", "--no-textconv"]
    if args.get("cached"):
        cmd.append("--cached")
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=10.0)
        if res.returncode != 0:
            return {"isError": True, "content": [{"type": "text", "text": "git diff failed: " + (res.stderr or "").strip()[:4000]}]}
        output = res.stdout
        if len(output) > 65536:
            output = output[:65536] + "\n... [TRUNCATED]"
        return {"content": [{"type": "text", "text": output}]}
    except Exception as exc:
        return {"isError": True, "content": [{"type": "text", "text": f"git diff failed: {exc}"}]}


def _approval_decision(resp: dict) -> tuple[bool, str]:
    """解析 tool_request_approval 的返回；只有明确 allow 才算批准（fail-closed）。"""
    if not isinstance(resp, dict) or resp.get("isError"):
        return False, "approval unavailable or errored"
    for item in resp.get("content") or []:
        text = item.get("text") if isinstance(item, dict) else None
        if not isinstance(text, str):
            continue
        try:
            body = json.loads(text)
        except (TypeError, ValueError):
            continue
        if not isinstance(body, dict):
            continue
        decision = str(body.get("decision") or body.get("status") or "").strip().lower()
        if decision in ("allow", "allowed", "approved"):
            return True, decision
        return False, str(body.get("reason") or decision or "denied")
    return False, "approval response malformed (fail-closed)"


def tool_terminal_exec(args: dict) -> dict:
    import subprocess
    # 宿主中介执行必须有明确工作区；没有 root 时绝不在 MCP 进程 cwd 里执行。
    if not WORKSPACE_ROOT or not os.path.isdir(WORKSPACE_ROOT):
        return {"isError": True, "content": [{"type": "text", "text": "workspace root is not configured; terminal exec refused"}]}
    cmd = args.get("command")
    if not cmd or not isinstance(cmd, list) or not all(isinstance(c, str) for c in cmd):
        return {"isError": True, "content": [{"type": "text", "text": "command must be a non-empty string array"}]}
    root = os.path.realpath(WORKSPACE_ROOT)
    workdir = args.get("workdir")
    target_dir = root
    if workdir:
        confined = _confined_path(workdir)
        if not confined or not os.path.isdir(confined):
            return {"isError": True, "content": [{"type": "text", "text": f"workdir rejected or not a directory: {workdir}"}]}
        target_dir = confined

    approval_resp = tool_request_approval({
        "tool": " ".join(cmd),
        "summary": "terminal/exec: " + " ".join(cmd[:5]),
        "riskLevel": "high",
    })
    allowed, reason = _approval_decision(approval_resp)
    if not allowed:
        return {
            "isError": True,
            "content": [{"type": "text", "text": "terminal execution denied: " + reason}],
        }

    try:
        timeout_sec = min(max(int(args.get("timeoutSec", 60)), 1), 300)
    except (TypeError, ValueError):
        timeout_sec = 60
    try:
        proc = subprocess.run(
            cmd,
            cwd=target_dir,
            capture_output=True,
            text=True,
            timeout=timeout_sec,
        )
        return {
            "content": [{
                "type": "text",
                "text": json.dumps({
                    "exitCode": proc.returncode,
                    "stdout": (proc.stdout or "")[:32768],
                    "stderr": (proc.stderr or "")[:16384],
                }, ensure_ascii=False),
            }],
        }
    except subprocess.TimeoutExpired:
        return {"isError": True, "content": [{"type": "text", "text": f"command timed out after {timeout_sec}s"}]}
    except Exception as exc:
        return {"isError": True, "content": [{"type": "text", "text": f"execution failed: {exc}"}]}


HANDLERS = {
    "tenon_fetch_node_capabilities": tool_fetch_capabilities,
    "tenon_request_human_approval": tool_request_approval,
    "tenon_send_group_card": tool_send_card,
    "tenon_ask_question": tool_ask_question,
    "tenon_fs_read": tool_fs_read,
    "tenon_git_diff": tool_git_diff,
    "tenon_terminal_exec": tool_terminal_exec,
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
        try:
            result = handler(params.get("arguments", {}) or {})
        except Exception as exc:
            # 工具参数异常不能打崩 stdio 服务：回成工具错误，保持 fail-closed。
            result = {"isError": True, "content": [{"type": "text", "text": f"tool error: {exc}"}]}
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
