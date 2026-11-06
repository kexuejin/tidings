#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""antigravity-tidings-hook —— Antigravity（App/IDE/agy）↔ Tidings 宿主的 Lifecycle Hooks 桥。

Antigravity 接入设计（形态 B，RFC §3.2/§4）的宿主侧脚本：挂在
`~/.gemini/config/hooks.json`（或工作区 `.agents/hooks.json`）上，把四类生命周期
事件转发给本机 Tidings 控制面的回环 HTTP API：

- SessionStart / Stop  →  POST /v1/agent/hook/session   （手机看板「正在运行」）
- PreToolUse           →  POST /v1/agent/hook/approval  （免批表短路 + 手机双行审批；
                                                          fail-closed：超时/不可达 = deny）
- PreInvocation        →  POST /v1/agent/hook/inject    （拉走手机端注入池 → injectSteps）

## 安装（一次性）

1. 宿主跑着 Tidings 桌面端（控制面 127.0.0.1:8788）。
2. `hooks.json` 里每个事件挂本脚本（示例见 scripts/antigravity-hooks.json.example）；
   脚本自己从 stdin 的 JSON 里认 `event_name`，不用为每个事件配不同参数。
3. 凭证（二选一）：环境变量 `TIDINGS_PLUGIN_TOKEN`，或与插件同一条 0600 受控文件
   （宿主数据目录 `plugins/plugin-token`）。
4. 手机端配对在线。PreToolUse 的审批卡会推到手机；PreInvocation 的注入池由
   `POST /v1/agent/hook/inject-feed` 投喂（conversationId 或 wsPath 寻池）。

## 失败语义（与宿主同一哲学：fail-closed）

- PreToolUse 遇到「宿主不可达 / 超时 / 非 200」→ 输出 deny（审不过就拒，绝不静默放行）；
- PreInvocation / SessionStart 遇到错误 → 输出空 continue / 吞掉 —— 这两类失败只影响
  体验，不影响安全，不阻塞 Antigravity 自己跑。
"""

import json
import os
import sys
import urllib.error
import urllib.request

HOST_URL = (os.environ.get("TENON_HOST_URL") or os.environ.get("TIDINGS_HOST_URL") or "http://127.0.0.1:8788").rstrip("/")
TIMEOUT_SECONDS = float(os.environ.get("TENON_HOOK_TIMEOUT") or os.environ.get("TIDINGS_HOOK_TIMEOUT") or "90")


def bearer() -> str:
    """插件同款凭证：env 优先，缺省读宿主数据目录的 0600 受控文件。"""
    token = os.environ.get("TENON_PLUGIN_TOKEN") or os.environ.get("TIDINGS_PLUGIN_TOKEN")
    if token:
        return token.strip()
    for candidate in (
        os.path.expanduser("~/.tenon/plugins/plugin-token"),
        os.path.expanduser("~/Library/Application Support/榫卯 Tenon/plugins/plugin-token"),
        os.path.expanduser("~/.tenon/plugins/plugin-token"),
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


def post(path: str, payload: dict, timeout: float = TIMEOUT_SECONDS):
    """回环 POST；返回 (ok, parsed_json_or_none)。网络错误/非 200 都算不 ok。"""
    req = urllib.request.Request(
        HOST_URL + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + bearer()},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return True, (json.loads(body) if body.strip() else {})
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError):
        return False, None


def event_name(stdin_obj: dict, argv: list) -> str:
    """事件名：stdin 的 event_name 优先，argv --event 兜底（hooks.json 形状可能带参）。"""
    name = stdin_obj.get("event_name") or stdin_obj.get("event") or ""
    if name:
        return str(name).lower()
    for i, arg in enumerate(argv):
        if arg == "--event" and i + 1 < len(argv):
            return argv[i + 1].lower()
    return ""


def conversation_id(stdin_obj: dict) -> str:
    conv = stdin_obj.get("conversation_id") or stdin_obj.get("conversationId") or ""
    return str(conv)


def handle_session_start(stdin_obj: dict) -> dict:
    ws_paths = stdin_obj.get("workspace_paths") or stdin_obj.get("workspacePaths") or []
    if isinstance(ws_paths, str):
        ws_paths = [ws_paths]
    ok, _ = post("/v1/agent/hook/session", {
        "event": "start",
        "conversationId": conversation_id(stdin_obj),
        "wsPaths": ws_paths,
        "title": stdin_obj.get("title") or "Antigravity 会话",
    })
    # 会话可见性失败只影响体验：不阻塞 Antigravity 启动。
    return {}


def handle_session_stop(stdin_obj: dict) -> dict:
    post("/v1/agent/hook/session", {
        "event": "stop",
        "conversationId": conversation_id(stdin_obj),
    })
    return {}


def handle_pre_tool_use(stdin_obj: dict) -> dict:
    tool = stdin_obj.get("tool") or stdin_obj.get("tool_name") or ""
    cmd = stdin_obj.get("cmd") or stdin_obj.get("command") or stdin_obj.get("input") or ""
    summary = (" ".join(str(cmd).split())[:200] if cmd else None) or (str(tool) or None)
    ok, payload = post("/v1/agent/hook/approval", {
        "conversationId": conversation_id(stdin_obj),
        "tool": str(tool),
        "summary": summary,
        "riskLevel": "L3",
        "timeoutMs": int(TIMEOUT_SECONDS * 1000),
    })
    if not ok or not isinstance(payload, dict):
        # fail-closed：审不过就拒 —— 宿主不可达不是放行的理由。
        return {"decision": "deny", "reason": "tenon host unreachable (fail-closed)"}
    return {"decision": payload.get("decision", "deny"), "reason": payload.get("reason")}


def handle_pre_invocation(stdin_obj: dict) -> dict:
    ok, payload = post("/v1/agent/hook/inject", {"conversationId": conversation_id(stdin_obj)})
    if ok and isinstance(payload, dict) and payload.get("injectSteps"):
        return {"decision": "continue", "injectSteps": payload["injectSteps"]}
    # 池子空 / 宿主不可达：空注入，Antigravity 照常跑。
    return {"decision": "continue", "injectSteps": []}


def main() -> int:
    try:
        stdin_obj = json.loads(sys.stdin.read() or "{}")
    except json.JSONDecodeError:
        stdin_obj = {}
    event = event_name(stdin_obj, sys.argv)
    handlers = {
        "sessionstart": handle_session_start,
        "session_start": handle_session_start,
        "sessionstop": handle_session_stop,
        "session_stop": handle_session_stop,
        "pretooluse": handle_pre_tool_use,
        "pre_tool_use": handle_pre_tool_use,
        "preinvocation": handle_pre_invocation,
        "pre_invocation": handle_pre_invocation,
    }
    handler = handlers.get(event.replace("-", "_").lower()) or handlers.get(event.lower())
    out = handler(stdin_obj) if handler else {}
    sys.stdout.write(json.dumps(out))
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
