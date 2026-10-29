#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""echo-team 参考插件 —— Tidings 进程外插件的完整最小实现。

零第三方依赖（只用 Python 标准库），它演示的是**契约**而不是能力：

  1. 在回环上起自己的 IPC 服务器（healthz / session/open / task/execute /
     task/action / task/queryProgress）；
  2. 带 TIDINGS_PLUGIN_TOKEN 向宿主控制面 POST /v1/plugin/register 注册
     （manifest.json 原样上报 + 自己的端口）；
  3. 收到派单后回一条**流式 NDJSON** 事件流（思考 → 正文 → 卡片 → 完成），
     卡片就是方案 §4.3 的声明式 JSON。

收到的任何 prompt 都被原样回声 —— 所以它能当两层用：
  - 插件作者抄契约的模板（把 echo 换成你的真实现）；
  - 宿主联调的探针（宿主发什么就该收到什么，任何丢帧都一眼可见）。

两种接入模式见 README.md；token 的三种来源（env / 插件旁文件 / 宿主数据目录）
见 find_token()。
"""

import json
import os
import re
import signal
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------- 插件身份

def load_manifest():
    with open(os.path.join(HERE, "manifest.json"), "r", encoding="utf-8") as f:
        return json.load(f)


def find_token():
    """token 的三种来源，按优先级（方案 §2.3）。

    1. 环境变量 TIDINGS_PLUGIN_TOKEN（托管模式：宿主拉起子进程时注入）；
    2. 插件目录旁的 token 文件（守护进程自带配置的一种形态）；
    3. 宿主数据目录下的受控文件 plugins/plugin-token（守护进程模式的默认来源，
       桌面端保证它存在且 0600）。
    """
    env = os.environ.get("TIDINGS_PLUGIN_TOKEN")
    if env:
        return env
    sibling = os.path.join(HERE, "plugin-token")
    if os.path.isfile(sibling):
        with open(sibling, "r", encoding="utf-8") as f:
            token = f.read().strip()
        if token:
            return token
    home = os.path.expanduser("~")
    candidates = [
        os.path.join(home, "Library", "Application Support", "捎话 Tidings", "plugins", "plugin-token"),
        os.path.join(os.environ.get("XDG_DATA_HOME", os.path.join(home, ".local", "share")),
                     "捎话 Tidings", "plugins", "plugin-token"),
    ]
    for path in candidates:
        if os.path.isfile(path):
            with open(path, "r", encoding="utf-8") as f:
                token = f.read().strip()
            if token:
                return token
    return None


def control_url():
    return os.environ.get("TIDINGS_CONTROL_URL", "http://127.0.0.1:8788").rstrip("/")


# ---------------------------------------------------------------- 注册

REGISTER_TIMEOUT = 90.0     # 宿主可能比插件晚起：注册在预算内重试，超了就如实退出
REGISTER_INTERVAL = 2.0


def http_post(url, body_bytes, headers=None, timeout=10):
    req = urllib.request.Request(url, data=body_bytes, method="POST")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8")


def register(manifest, port, token):
    """向宿主注册自己；失败（含 401）如实打日志 —— 宿主不会收留无 token 的插件。"""
    body = json.dumps({"manifest": manifest, "httpPort": port}).encode("utf-8")
    headers = {"Authorization": "Bearer " + token}
    deadline = time.time() + REGISTER_TIMEOUT
    while time.time() < deadline:
        try:
            status, text = http_post(control_url() + "/v1/plugin/register", body, headers)
            print("[echo-team-plugin] registered: HTTP %d %s" % (status, text[:200]), flush=True)
            return status == 200
        except urllib.error.HTTPError as e:
            print("[echo-team-plugin] register rejected: HTTP %d %s" % (e.code, e.read().decode("utf-8")[:200]), flush=True)
            if e.code in (400, 401, 403, 503):
                return False  # 被拒不是「还没就绪」，重试没有意义
            time.sleep(REGISTER_INTERVAL)
        except (urllib.error.URLError, OSError) as e:
            print("[echo-team-plugin] control plane not ready (%s), retrying..." % e, flush=True)
            time.sleep(REGISTER_INTERVAL)
    print("[echo-team-plugin] register timed out after %ss" % REGISTER_TIMEOUT, flush=True)
    return False


def unregister(plugin_id, token):
    try:
        http_post(
            control_url() + "/v1/plugin/unregister",
            json.dumps({"pluginId": plugin_id}).encode("utf-8"),
            {"Authorization": "Bearer " + token},
            timeout=3,
        )
    except Exception as e:  # 尽力而为：宿主的探测周期会自行把尸体翻成离线
        print("[echo-team-plugin] unregister failed (ignored): %s" % e, flush=True)


# ---------------------------------------------------------------- 会话与任务状态

LOCK = threading.Lock()
SESSIONS = {}       # sessionId -> {"progress": int, "state": str, "stage": str}
NEXT_SESSION = [0]


def event_line(kind, **fields):
    payload = {"type": kind}
    payload.update(fields)
    return json.dumps(payload, ensure_ascii=False)


def handle_session_open(payload):
    """开/续会话：接续时按契约复用既有身份（宿主侧 SessionHandle.resumed 随之置真）。"""
    resume = payload.get("resumeSessionId")
    with LOCK:
        if resume:
            session_id = resume
            SESSIONS.setdefault(session_id, {"progress": 0, "state": "running", "stage": "resumed"})
            resumed = True
        else:
            NEXT_SESSION[0] += 1
            session_id = "echo-session-%d" % NEXT_SESSION[0]
            SESSIONS[session_id] = {"progress": 0, "state": "running", "stage": "opened"}
            resumed = False
    return {"sessionId": session_id, "resumed": resumed, "createdAt": int(time.time() * 1000)}


def handle_execute(session_id, prompt):
    """回声任务：标准事件流（thought → token → card → finished），一行一个 JSON。

    prompt 含 "fail" 时回 error 事件 —— 给热拔/失败路径一个手动触发的开关。
    """
    with LOCK:
        SESSIONS.setdefault(session_id, {"progress": 0, "state": "running", "stage": "executing"})
        SESSIONS[session_id].update({"progress": 30, "state": "running", "stage": "thinking"})

    yield event_line("thought", delta="echo-team thinking about: %s" % prompt[:80])
    with LOCK:
        SESSIONS[session_id].update({"progress": 60, "stage": "composing"})

    if "fail" in prompt.lower():
        yield event_line("error", message="echo-team was asked to fail (prompt contains 'fail')")
        with LOCK:
            SESSIONS[session_id].update({"progress": 100, "state": "error", "stage": "failed"})
        yield event_line("finished", finishReason="error", totalTokens=0)
        return

    yield event_line("token", delta="echo: %s" % prompt)
    card = {
        "type": "team-task-board",
        "version": 1,
        "cardId": "echo-card-%s" % session_id,
        "rev": 1,
        "title": "Echo task",
        "progress": 100,
        "items": [
            {"id": "1", "label": "receive prompt", "status": "done", "assignee": "@echo-lead"},
            {"id": "2", "label": "echo back", "status": "done", "assignee": "@echo-coder"},
            {"id": "3", "label": "report card", "status": "done", "assignee": "@echo-tester"},
        ],
        "actions": [
            {"actionId": "echo-again", "label": "Echo again", "style": "primary"},
        ],
    }
    yield event_line("card", card=card)
    with LOCK:
        SESSIONS[session_id].update({"progress": 100, "state": "done", "stage": "done"})
    yield event_line("finished", finishReason="stop", totalTokens=0)


# ---------------------------------------------------------------- IPC 服务器

class IpcHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _read_json(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length > 0 else b"{}"
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {}

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_ndjson(self, lines):
        """流式事件：每行一个事件、逐行刷盘（宿主驱动逐行解析成 DriverEvent）。"""
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for line in lines:
            chunk = (line + "\n").encode("utf-8")
            self.wfile.write(("%x\r\n" % len(chunk)).encode("ascii"))
            self.wfile.write(chunk)
            self.wfile.write(b"\r\n")
            self.wfile.flush()
        self.wfile.write(b"0\r\n\r\n")

    def do_GET(self):
        if self.path.rstrip("/") == "/healthz":
            self._send_json(200, {"ok": True, "plugin": "echo-team"})
        else:
            self._send_json(404, {"error": "not-found"})

    def do_POST(self):
        payload = self._read_json()
        path = self.path.rstrip("/")
        if path == "/session/open":
            self._send_json(200, handle_session_open(payload))
        elif path == "/task/execute":
            session_id = str(payload.get("sessionId") or "")
            prompt = str(payload.get("prompt") or "")
            self._send_ndjson(handle_execute(session_id, prompt))
        elif path == "/task/action":
            print("[echo-team-plugin] action received: %s (decided by %s)" % (
                payload.get("actionId"), payload.get("decidedBy")), flush=True)
            self._send_json(200, {"ok": True})
        elif path == "/task/queryProgress":
            session_id = str(payload.get("sessionId") or "")
            with LOCK:
                snap = SESSIONS.get(session_id)
            if not snap:
                self._send_json(200, None)  # 契约：null = 没有可报告的进度
            else:
                self._send_json(200, {
                    "sessionId": session_id,
                    "state": snap["state"],
                    "stage": snap["stage"],
                    "progressPercent": snap["progress"],
                })
        else:
            self._send_json(404, {"error": "not-found"})

    def log_message(self, fmt, *args):
        print("[echo-team-plugin] " + fmt % args, flush=True)


def main():
    manifest = load_manifest()
    token = find_token()
    if not token:
        # fail-closed：没有 token 就不去敲门 —— 宿主反正会拒（§2.3 强制校验）。
        print("[echo-team-plugin] no token found (TIDINGS_PLUGIN_TOKEN / plugin-token file); cannot register", flush=True)
        return 2

    port = int(os.environ.get("TIDINGS_PLUGIN_PORT", "0"))
    server = ThreadingHTTPServer(("127.0.0.1", port), IpcHandler)
    server.daemon_threads = True
    bound_port = server.server_address[1]
    print("[echo-team-plugin] IPC listening on 127.0.0.1:%d" % bound_port, flush=True)

    if not register(manifest, bound_port, token):
        server.server_close()
        return 1

    def on_term(signum, frame):
        print("[echo-team-plugin] shutting down", flush=True)
        unregister(manifest["pluginId"], token)
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, on_term)
    signal.signal(signal.SIGINT, on_term)

    server.serve_forever()
    server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
