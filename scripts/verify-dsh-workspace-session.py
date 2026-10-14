#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify-dsh-workspace-session.py

把一份**真实** DSH 会话（`~/.dsh/sessions/<workspace-slug>/<sessionId>/session.v4.jsonl.zstd`）
读出来，经真实运行的 agentNode 控制面宣告并流式投递到在线邻居（手机），再把
「agentNode 台账」与「手机端 relay.db」对回同一份数据，最后可选地做真机界面取证。

为什么 sessionId / alias / title 都不能写死（2026-10-13 修）：
- 旧版把 `DSH_SESSION_DIR` 钉死成 `…/4dfa20fe-b078-4876-b019-a5bdd5845e0f`（该目录早已不存在）、
  `sessionId` 写死成 `dsh-real-ws-1`、`alias` 写死成 `dsh-teahouse-debugger`、
  `title` 拼成 `真实DSH: <标题前 24 字>`。它证明的是「协议能传三个常量」，
  **不是**「真实 DSH 的会话名能落到两端」—— 这正是三面（DSH 面板 / dsh-tidings 面板 / 手机）
  会话名对不上的原因之一，量具本身把真名丢掉了。
- 现在：`sessionId` = DSH 会话目录名；`alias` = 真实 `session/title` 派生出的短展示名
  （首行、去首尾空白、`<= 24` 字符，超出加省略号，与面板 `agentHeadline` 的取值口径一致）；
  `title` = 真实标题**全文**（不截断、不加前缀）。
- 旧版还依赖 python 的 `zstandard`（本机三个 python 都没装 ⇒ 脚本一运行就
  `ModuleNotFoundError`），现在优先用 `zstd` CLI，仅在缺 CLI 时才回退到该模块。

退出码：全部断言通过 = 0；任一断言失败 = 2（旧版无论发生什么都打印「100% SUCCESS」，
所以它不能当证据用）。真机没连、或界面没聚焦到工作区控制台 ⇒ 记 `skip` 并打印原因，
不计为通过也不计为失败。
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

AGENT_NODE_URL = os.environ.get("AGENT_NODE_URL", "http://127.0.0.1:8788")
WORKSPACE = Path(__file__).resolve().parents[1]
ALIAS_LIMIT = 24          # 短展示名上限（协议里 AGENT_ALIAS 上限 64，这里留足余量）
DEFAULT_LIMIT = 20        # 投递事件条数上限
DB_FILE = "/tmp/relay-verify.db"
SHOT_FILE = "/tmp/relay-verify-shot.png"


def die(msg):
    print("错误: " + msg, file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------- 断言台账

class Checks:
    """把每条断言打印出来并记账：失败不中断（要一次看到全部问题），最后统一给退出码。"""

    def __init__(self, dry=False):
        self.dry = dry
        self.passed = 0
        self.failed = []
        self.skipped = []

    def ok(self, name, detail=""):
        self.passed += 1
        print("  ok    " + name + (("  —— " + detail) if detail else ""))
        return True

    def fail(self, name, detail=""):
        self.failed.append(name)
        print("  FAIL  " + name + (("  —— " + detail) if detail else ""))
        return False

    def skip(self, name, why):
        self.skipped.append(name)
        print("  skip  " + name + "  —— " + why)
        return False

    def expect(self, cond, name, detail=""):
        return self.ok(name, detail) if cond else self.fail(name, detail)

    def finish(self):
        tail = ""
        if self.skipped:
            tail = "，%d 项跳过（%s）" % (len(self.skipped), "；".join(self.skipped))
        if self.dry:
            # dry-run 也要给真退出码：它证明的是「派生出来的值对不对」，
            # 派生错了却回 0，等于把一个红结果说成绿的。
            print("\n-------- dry-run：以上是从真实数据派生出的结果，未发送任何帧 --------")
        if self.failed:
            print("\n================ 验证未通过：%d 项失败，%d 项通过%s ================"
                  % (len(self.failed), self.passed, tail))
            return 2
        print("\n================ 验证通过：%d 项通过%s ================" % (self.passed, tail))
        return 0


# ---------------------------------------------------------------- HTTP

def http(method, path, payload=None, timeout=5):
    url = AGENT_NODE_URL + path
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={"Content-Type": "application/json"} if data is not None else {},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8")
    return json.loads(body) if body.strip() else {}


# ---------------------------------------------------------------- DSH 会话读取

def slug_for(workspace):
    """`/workspace/src/teahouse-mobile` -> `--Volumes-Kapp-source-teahouse-mobile--`。"""
    return "--" + str(workspace).strip("/").replace("/", "-") + "--"


def session_root(workspace):
    return Path(os.path.expanduser("~/.dsh/sessions")) / slug_for(workspace)


def pick_session(root, explicit):
    if explicit:
        d = Path(os.path.expanduser(explicit))
        if not (d / "session.v4.jsonl.zstd").exists():
            die("%s 里没有 session.v4.jsonl.zstd（--session 指的是会话目录，不是 workspace）" % d)
        return d
    if not root.is_dir():
        die("找不到 %s（这不是 DSH 用过的 workspace？用 --workspace 指定）" % root)
    dirs = [d for d in sorted(root.iterdir()) if (d / "session.v4.jsonl.zstd").exists()]
    if not dirs:
        die("%s 下没有任何会话文件" % root)
    return max(dirs, key=lambda d: (d / "session.v4.jsonl.zstd").stat().st_mtime)


def read_lines(path):
    """解压 session.v4.jsonl.zstd 并逐行解析；优先 zstd CLI，缺了才用 python 模块。"""
    if shutil.which("zstd"):
        proc = subprocess.run(["zstd", "-dc", str(path)], capture_output=True)
        if proc.returncode != 0:
            die("zstd -dc 失败: " + proc.stderr.decode("utf-8", "replace").strip())
        raw = proc.stdout
    else:
        try:
            import zstandard  # type: ignore
        except ImportError:
            die("需要 zstd CLI（brew install zstd）或 python 的 zstandard 模块")
        with open(path, "rb") as f:
            with zstandard.ZstdDecompressor().stream_reader(f) as reader:
                raw = reader.read()
    out = []
    for line in raw.decode("utf-8", "replace").split("\n"):
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except ValueError:
            continue
    return out


def derive_title(lines, fallback):
    """真名：`session/title`；没有就退到第一条像人说的用户消息首行；再没有才用会话 id。"""
    for o in lines:
        if o.get("type") == "session/title":
            title = (o.get("data") or {}).get("title")
            if isinstance(title, str) and title.strip():
                return title.strip()
    for o in lines:
        if o.get("type") != "user/message":
            continue
        content = (o.get("data") or {}).get("content") or []
        text = ""
        if isinstance(content, list) and content and isinstance(content[0], dict):
            text = content[0].get("text") or ""
        text = text.strip()
        if not text or text.startswith("<system-reminder>") or text.startswith("Current runtime"):
            continue
        return text.splitlines()[0].strip()
    return fallback


def derive_alias(title):
    """短展示名 = 标题首行、去首尾空白、<= ALIAS_LIMIT 字符（超出加省略号）。

    与手机端/面板的取值口径一致：alias 落在**主标题**位，所以它得是一行能看全的名字；
    title 是完整标题，落在副行。两者都从同一份真实标题派生，不是两个编造的值。
    """
    line = next((s.strip() for s in title.splitlines() if s.strip()), title.strip())
    if len(line) <= ALIAS_LIMIT:
        return line
    return line[: ALIAS_LIMIT - 1] + "…"


def extract_events(lines, session_id, limit):
    """把 DSH 会话里的行压成 (eventType, summary, body, ref) 四元组。"""
    events = []

    def push(row):
        if len(events) < limit:
            events.append(row)

    for o in lines:
        data = o.get("data") or {}
        t = o.get("type")
        if t == "session":
            push(("session.created", "会话创建", "workspace=" + str(WORKSPACE), None))
        elif t == "user/message":
            content = data.get("content") or []
            text = ""
            if isinstance(content, list) and content and isinstance(content[0], dict):
                text = content[0].get("text") or ""
            if text.strip() and not text.startswith("<system-reminder>") and not text.startswith("Current runtime"):
                push(("user.message", "用户任务", text[:300], None))
        elif t == "tool/call":
            push(("tool.started", "调用工具: " + str(data.get("name", "tool")),
                  str(data.get("arguments", ""))[:250], data.get("callId") or None))
        elif t == "tool/result":
            call_id = (((data.get("message") or {}).get("source") or {}).get("callId")) or ""
            push(("tool.completed", "工具执行完毕", "callId=" + call_id, call_id or None))
        elif t == "turn/end":
            reason = ((data.get("reason") or {}).get("kind")) or "completed"
            push(("turn.end", "轮次结束 (%s)" % reason, "轮次正常执行完毕", None))
        if len(events) >= limit:
            break
    return events


# ---------------------------------------------------------------- adb

def adb_serial(explicit):
    """--serial / $ANDROID_SERIAL / 恰好一台在线设备；都不成立返回 None。"""
    if explicit:
        return explicit
    env = os.environ.get("ANDROID_SERIAL")
    if env:
        return env
    proc = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    devices = []
    for line in proc.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices[0] if len(devices) == 1 else None


def run(cmd, binary=False):
    """binary=True 用于拉二进制（relay.db / 截图）：text=True 会在 0x8- 这种字节上抛 UnicodeDecodeError。"""
    return subprocess.run(cmd, capture_output=True, text=not binary)


def ui_dump_xml(serial):
    run(["adb", "-s", serial, "shell", "uiautomator", "dump", "/sdcard/relay-verify-ui.xml"])
    return run(["adb", "-s", serial, "exec-out", "cat", "/sdcard/relay-verify-ui.xml"]).stdout


# ---------------------------------------------------------------- 主流程

def main(argv=None):
    ap = argparse.ArgumentParser(description="真实 DSH 会话 -> agentNode -> 手机 全链路验证")
    ap.add_argument("--workspace", default=str(WORKSPACE), help="DSH 工作区路径（默认本仓库根）")
    ap.add_argument("--session", default=None, help="DSH 会话目录（默认取该工作区最新的一个）")
    ap.add_argument("--serial", default=None, help="adb 设备序列号（默认 $ANDROID_SERIAL 或唯一设备）")
    ap.add_argument("--limit", type=int, default=DEFAULT_LIMIT, help="投递事件条数上限（默认 20）")
    ap.add_argument("--ui", action="store_true", help="额外做真机界面取证（默认跳过）")
    ap.add_argument("--dry-run", action="store_true", help="只读真实数据并打印将要发送的载荷，不发帧")
    args = ap.parse_args(argv)

    checks = Checks(dry=args.dry_run)
    workspace = Path(os.path.expanduser(args.workspace)).resolve()
    root = session_root(workspace)
    session_dir = pick_session(root, args.session)
    session_id = session_dir.name
    archive = session_dir / "session.v4.jsonl.zstd"

    # ---- 第一步：先证明控制面/邻居是活的（dry-run 时只报告，不要求）
    print("=================================================================")
    print("【第一步】读取 agentNode 状态与在线邻居")
    print("=================================================================")
    print("  控制面: " + AGENT_NODE_URL)
    try:
        peers_data = http("GET", "/v1/peers")
    except (urllib.error.URLError, OSError) as exc:
        print("  (agentNode 取不到: %s)" % exc)
        checks.skip("控制面在线", "agentNode 未运行")
        peers = []
    else:
        peers = peers_data.get("peers", []) or []
        print("  邻居数: %d" % len(peers))
        for p in peers:
            print("   - %s (%s) ip=%s online=%s"
                  % (p.get("nick"), p.get("nodeId"), p.get("ip"), p.get("online")))
        checks.expect(bool(peers), "至少有一个已发现的邻居")
    self_id = None
    if not checks.failed:
        try:
            health = http("GET", "/healthz")
            self_id = health.get("nodeId")
            print("  本机 agentNode: %s (%s)" % (self_id, health.get("nick")))
        except (urllib.error.URLError, OSError) as exc:
            print("  (healthz 取不到: %s)" % exc)
        checks.expect(bool(self_id), "控制面自报了本机 nodeId（手机端那一行 node_id 就是它）")
    phone_peer = next((p for p in peers if p.get("online")), None)
    if phone_peer is None and not args.dry_run:
        checks.fail("有在线邻居可投递", "没有 online=true 的邻居：先在手机上打开 App 让它握手")
        return checks.finish()
    phone_node_id = phone_peer["nodeId"] if phone_peer else None
    if phone_node_id:
        print("  目标节点: %s (%s)" % (phone_node_id, phone_peer.get("nick")))

    # ---- 第二步：从真实 DSH 会话派生 sessionId / alias / title
    print("\n=================================================================")
    print("【第二步】从真实 DSH 会话派生 sessionId / alias / title")
    print("=================================================================")
    print("  会话目录: " + str(session_dir))
    print("  会话文件: %s (%.0f bytes)" % (archive, archive.stat().st_size))
    lines = read_lines(archive)
    checks.expect(bool(lines), "会话文件解压后有事件行", "%d 行" % len(lines))
    title = derive_title(lines, session_id)
    alias = derive_alias(title)
    events = extract_events(lines, session_id, args.limit)
    print("  sessionId = " + session_id + "   <- DSH 会话目录名")
    print("  alias     = " + alias + "   <- 真实 title 的短展示名")
    print("  title     = " + title + "   <- 真实 title 全文")
    print("  事件       = %d 条（上限 %d）" % (len(events), args.limit))
    checks.expect(session_id not in ("dsh-real-ws-1", ""), "sessionId 来自真实会话目录，不是写死的常量")
    checks.expect(alias != "dsh-teahouse-debugger", "alias 来自真实标题，不是写死的常量")
    checks.expect(len(alias) <= ALIAS_LIMIT, "alias 不超过 %d 字符" % ALIAS_LIMIT, "%d 字符" % len(alias))
    checks.expect(title in ("", None) or not title.startswith("真实DSH:"), "title 不加「真实DSH:」前缀")
    checks.expect(len(events) > 0, "至少抽出一条事件")

    # PPE-1 增补 D6（2026-10-13 裁决）：agentKind = agent 宿主/驱动类型（DSH），不是模型名。
    # 增补 D1：工作区三键。wsId 用 sha256(路径) 前 12 位 hex（稳定、可重算、不拿路径原文当 id）；
    # wsTitle 取路径末段（这是**宿主自己**的工作区，不是"UI 拿 path 末段猜对端的"那一条禁令）。
    ws_id = "ws-" + hashlib.sha256(str(workspace).encode("utf-8")).hexdigest()[:12]
    session_payload = {
        "to": phone_node_id,
        "sessionId": session_id,
        "alias": alias,
        "agentKind": "DSH",
        "agentId": "dsh-cli",
        "state": "busy",
        "title": title,
        "wsId": ws_id,
        "wsTitle": Path(workspace).name,
        "wsPath": str(workspace),
    }
    event_payloads = [
        {"to": phone_node_id, "sessionId": session_id, "cursor": i,
         "eventType": ev, "summary": sm, "body": bd, "ref": rf}
        for i, (ev, sm, bd, rf) in enumerate(events, start=1)
    ]

    if args.dry_run:
        print("\n  [dry-run] 会话帧: " + json.dumps(session_payload, ensure_ascii=False))
        for p in event_payloads[:3]:
            print("  [dry-run] 事件帧: " + json.dumps(p, ensure_ascii=False))
        if len(event_payloads) > 3:
            print("  [dry-run] … 其余 %d 条同理" % (len(event_payloads) - 3))
        checks.skip("agentNode 台账回读", "dry-run 未发帧")
        checks.skip("手机 relay.db 回读", "dry-run 未发帧")
        return checks.finish()

    # ---- 第三步：宣告 agent-session
    print("\n=================================================================")
    print("【第三步】经控制面宣告 Agent 会话（agent-session）")
    print("=================================================================")
    res = http("POST", "/v1/agent/session", session_payload)
    print("  宣告结果: " + json.dumps(res, ensure_ascii=False))
    checks.expect(res.get("ok") is not False, "控制面接受了会话帧", json.dumps(res, ensure_ascii=False))
    time.sleep(0.5)

    # ---- 第四步：流式投递真实事件
    print("\n=================================================================")
    print("【第四步】流式投递真实事件序列（agent-event）")
    print("=================================================================")
    delivered = 0
    for payload in event_payloads:
        res = http("POST", "/v1/agent/event", payload)
        if res.get("ok") is not False:
            delivered += 1
        print("  事件 #%d [%s] %s -> %s"
              % (payload["cursor"], payload["eventType"], payload["summary"], res.get("status", res)))
        time.sleep(0.1)
    checks.expect(delivered == len(event_payloads), "所有事件都被控制面接受",
                  "%d/%d" % (delivered, len(event_payloads)))

    http("POST", "/v1/agent/session", {"to": phone_node_id, "sessionId": session_id, "state": "done"})
    time.sleep(1.0)

    # ---- 第五步：agentNode 台账回读（含 nodeId 维）
    print("\n=================================================================")
    print("【第五步】agentNode 台账回读")
    print("=================================================================")
    query = urllib.parse.urlencode({"sessionId": session_id, "nodeId": phone_node_id})
    ledger = http("GET", "/v1/agent/sessions?" + query)
    rows = ledger.get("sessions", []) or []
    ledger_events = ledger.get("events", []) or []
    print("  点名 (sessionId + nodeId) 查到会话 %d 条、事件 %d 条" % (len(rows), len(ledger_events)))
    if rows:
        row = rows[0]
        print("   - nodeId=%s state=%s alias=%s title=%s"
              % (row.get("nodeId"), row.get("state"), row.get("alias"), row.get("title")))
    checks.expect(len(rows) == 1, "台账里恰好一条这条会话", "%d 条" % len(rows))
    if rows:
        row = rows[0]
        checks.expect(row.get("nodeId") == phone_node_id, "台账行的 nodeId 是目标手机",
                      str(row.get("nodeId")))
        checks.expect(row.get("sessionId") == session_id, "台账行的 sessionId 是那份真实会话")
        checks.expect(row.get("alias") == alias, "台账行的 alias 就是真实标题派生值", str(row.get("alias")))
        checks.expect(row.get("title") == title, "台账行的 title 是真实标题全文")
        checks.expect(row.get("state") == "done", "收尾状态已置 done", str(row.get("state")))
        # PPE-1 增补 D1/D6 的台账回读：四个新键必须逐字往返（发送方 → 台账）。
        checks.expect(row.get("agentId") == "dsh-cli", "台账行的 agentId 已落地", str(row.get("agentId")))
        checks.expect(row.get("wsId") == ws_id, "台账行的 wsId 已落地", str(row.get("wsId")))
        checks.expect(row.get("wsTitle") == Path(workspace).name, "台账行的 wsTitle 已落地", str(row.get("wsTitle")))
        checks.expect(row.get("wsPath") == str(workspace), "台账行的 wsPath 已落地", str(row.get("wsPath")))
    # 台账/库里的事件是**累积**的：同一份会话被验证过多次（或 --limit 小于总事件数）时，
    # 对面存着的会比本次投递多。所以判据是「本次投递的每个 cursor 都在对面」，
    # 不是「两边条数相等」——2026-10-13 用 --limit 3 复跑时，这条等式曾误报 5 vs 3。
    delivered = {p["cursor"] for p in event_payloads}
    have = {e.get("cursor") for e in ledger_events}
    checks.expect(delivered <= have, "台账覆盖了本次投递的全部 cursor",
                  "投递 %d 条，台账 %d 条；缺 %s"
                  % (len(delivered), len(have), sorted(delivered - have)))

    # ---- 第六步：手机端 relay.db 回读
    print("\n=================================================================")
    print("【第六步】手机端 relay.db 回读（真机）")
    print("=================================================================")
    serial = adb_serial(args.serial)
    if not serial:
        checks.skip("手机 relay.db 回读", "没有可用的 adb 设备（--serial 或 $ANDROID_SERIAL）")
    elif not shutil.which("sqlite3"):
        checks.skip("手机 relay.db 回读", "本机没有 sqlite3")
    else:
        print("  adb 设备: " + serial)
        pull = run(["adb", "-s", serial, "exec-out", "run-as", "app.relay", "cat", "databases/relay.db"],
                   binary=True)
        Path(DB_FILE).write_bytes(pull.stdout)
        checks.expect(len(pull.stdout) > 0, "把手机数据库拉到了本机", "%d bytes" % len(pull.stdout))

        def sql(statement):
            return run(["sqlite3", DB_FILE, statement]).stdout.strip()

        # 手机侧落库是**异步**的（handleOnAgentSession → scope.launch → persistMutex），
        # 而"投出去被 ACK"只证明帧到了对端进程，不证明它已经写进磁盘。
        # 固定 sleep 之后读一次 = 把量具自己测成红（2026-10-14 实测：20 条里只读到 13 条、
        # state 还停在 busy）。所以这里**轮询到满足或超时**，超时才按实际读数判。
        def sql_until(statement, want, timeout_s=20.0):
            """轮询执行 statement 直到输出含 want（或超时），返回最后一次读数。"""
            deadline = time.time() + timeout_s
            seen = ""
            while time.time() < deadline:
                seen = sql(statement)
                if want in seen:
                    return seen
                time.sleep(0.5)
            return seen

        session_row = sql_until(
            "SELECT node_id, session_id, alias, state, title, agent_id, ws_id, ws_title, ws_path "
            "FROM AgentSessionEntity WHERE session_id='%s';" % session_id,
            "|done|")
        print("  AgentSessionEntity: " + (session_row or "(空)"))
        # 事件条数同样要等：先轮询到"至少本次投递的条数"再读，避免数到一半。
        sql_until("SELECT count(*) FROM AgentEventEntity WHERE session_id='%s';" % session_id,
                  "%d" % len(event_payloads), timeout_s=15.0)
        n_events = sql("SELECT count(*) FROM AgentEventEntity WHERE session_id='%s';" % session_id)
        print("  AgentEventEntity 条数: " + (n_events or "0"))
        sample = sql("SELECT cursor, event_type, summary FROM AgentEventEntity "
                     "WHERE session_id='%s' ORDER BY cursor ASC LIMIT 3;" % session_id)
        print("  事件采样(前3): " + (sample or "(空)"))
        fields = session_row.split("|") if session_row else []
        checks.expect(len(fields) == 9, "手机库里这条会话在", session_row or "没有这一行")
        if len(fields) == 9:
            # 方向性（2026-10-13 实测踩到）：两端的 `node_id` 都存**对面**。
            # agentNode 台账那行是 `nodeId = 手机`（发出时的目标），手机 relay.db 这行
            # 是 `node_id = 本机 agentNode`（收到时的来源）。所以这里比的是 self_id，
            # 拿 phone_node_id 去比必然对不上。
            if self_id:
                checks.expect(fields[0] == self_id, "手机库行的 node_id 是来源节点（本机 agentNode）",
                              "%s vs self=%s" % (fields[0], self_id))
            else:
                checks.skip("手机库行的 node_id 是来源节点", "没取到 /healthz 的自报 nodeId")
            checks.expect(fields[2] == alias, "手机库行的 alias 是真实标题派生值", fields[2])
            checks.expect(fields[4] == title, "手机库行的 title 是真实标题全文")
            checks.expect(fields[3] == "done", "手机库行的 state 已同步到 done", fields[3])
            # PPE-1 增补 D1/D6 的手机侧回读：四个新列逐字落库（cap aws1 协商成功的证明）。
            checks.expect(fields[5] == "dsh-cli", "手机库行的 agent_id 已落库", str(fields[5]))
            checks.expect(fields[6] == ws_id, "手机库行的 ws_id 已落库", str(fields[6]))
            checks.expect(fields[7] == Path(workspace).name, "手机库行的 ws_title 已落库", str(fields[7]))
            checks.expect(fields[8] == str(workspace), "手机库行的 ws_path 已落库", str(fields[8]))
        db_cursors = {int(c) for c in
                      sql("SELECT cursor FROM AgentEventEntity WHERE session_id='%s';" % session_id).splitlines()
                      if c.strip()}
        checks.expect(delivered <= db_cursors, "手机库覆盖了本次投递的全部 cursor",
                      "投递 %d 条，库里 %s 条；缺 %s"
                      % (len(delivered), n_events, sorted(delivered - db_cursors)))

    # ---- 第六步之二：PPE-1 增补 D3（agent-workspace）与 D7（授权）的端到端
    print("\n=================================================================")
    print("【第六步之二】工作区宣告（D3）与授权回路（D7）")
    print("=================================================================")

    def http_status(method, path, payload=None):
        """带状态码的 http()：urllib 对非 2xx 抛 HTTPError，而这一段的被测面**就是** 403/404。
        把异常折成 (code, body) 返回，让断言能看见"被拦了"这个事实本身。"""
        url = AGENT_NODE_URL + path
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            url, data=data, method=method,
            headers={"Content-Type": "application/json"} if data is not None else {},
        )
        try:
            # 超时**必须大于** ACK 重试窗口（Timings.ackRetrySchedule = 1+2+4 = 7s）：
            # 对端不认识这帧时控制面会等满整轮才回 not-acked，5 秒会在客户端先 socket.timeout
            # —— 量具把自己测成红（2026-10-14 首跑就是这个现象）。
            with urllib.request.urlopen(req, timeout=20) as resp:
                raw = resp.read().decode("utf-8")
                return resp.status, (json.loads(raw) if raw.strip() else {})
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", "replace")
            try:
                return exc.code, json.loads(raw)
            except ValueError:
                return exc.code, {"_raw": raw[:200]}

    # D3：宣告一个**空工作区**（有 wsId、零会话）—— 这正是 agent-workspace 帧唯一能表达的档，
    # 用 agent-session 的 ws 键测不出它（会话帧的工作区必须有会话才带得动）。
    empty_ws_id = "ws-" + hashlib.sha256((str(workspace) + "#empty").encode("utf-8")).hexdigest()[:12]
    ws_payload = {
        "to": phone_node_id, "wsId": empty_ws_id,
        "wsTitle": "空工作区（D3 探针）", "wsPath": str(workspace) + "/probe-empty",
        # agentId 必须带：归组键 = agentId ?? agentKind，会话侧报的是 agentId，
        # 工作区帧只报 agentKind 会让同一个工作区在手机上被拆成两行（本轮测试判红过）。
        "agentId": "dsh-cli", "agentKind": "DSH", "online": True, "sessionCount": 0,
    }
    code, res = http_status("POST", "/v1/agent/workspace", ws_payload)
    print("  宣告结果: %d %s" % (code, json.dumps(res, ensure_ascii=False)))
    # status 三档都合法：sent（对端认识这帧并 ACK）/ not-acked（对端是不认识它的旧版本 ——
    # 状态快照**故意**不入队、不标离线，见 ReliableNoQueueTest）/ queued（对端离线）。
    # 判红只该发生在"控制面自己没接这个路由"（404）或"帧被我们自己的自检拒收"（400）。
    checks.expect(code == 200 and res.get("status") in ("sent", "not-acked", "queued"),
                  "控制面接受了工作区帧", "%d %s" % (code, json.dumps(res, ensure_ascii=False)))
    if res.get("status") == "not-acked":
        checks.skip("手机 ACK 了工作区帧", "对端版本还不认识 agent-workspace（旧 APK）；台账侧照常验")
    elif res.get("status") == "sent":
        checks.ok("手机 ACK 了工作区帧（对端已升级到认识 D3 的版本）")
    time.sleep(0.5)
    listed = http("GET", "/v1/agent/workspaces")
    rows = listed.get("workspaces", []) or []
    hit = next((r for r in rows if r.get("wsId") == empty_ws_id), None)
    checks.expect(hit is not None, "工作区台账（GET /v1/agent/workspaces）里有这一行",
                  "共 %d 行" % len(rows))
    if hit:
        checks.expect(hit.get("wsTitle") == "空工作区（D3 探针）", "台账行的 wsTitle 逐字往返", str(hit.get("wsTitle")))
        checks.expect(hit.get("online") is True, "台账行的 online 是布尔 true 而不是字符串", repr(hit.get("online")))
    # 手机侧落库（AgentWorkspaceEntity 由手机端写；run-as 能读就跑，读不到就 skip）。
    if serial and shutil.which("sqlite3"):
        pull = run(["adb", "-s", serial, "exec-out", "run-as", "app.relay", "cat", "databases/relay.db"],
                   binary=True)
        if pull.stdout:
            Path(DB_FILE).write_bytes(pull.stdout)
            got = run(["sqlite3", DB_FILE,
                       "SELECT ws_id, ws_title FROM AgentWorkspaceEntity WHERE ws_id='%s';" % empty_ws_id]).stdout.strip()
            if "no such table" in got:
                checks.skip("手机库出现这一行工作区", "手机端还没有 AgentWorkspaceEntity（未升级）")
            elif got:
                checks.expect(empty_ws_id in got, "手机库 AgentWorkspaceEntity 有这一行", got)
            else:
                checks.skip("手机库出现这一行工作区", "库里查不到（宣告帧还没被手机消费或离线）")

    # D7：授权三档策略 + 白名单 + 撤销的**控制面回读**（执行点判据在 agentNode 的
    # ControlPlaneTest 里守，这里只验"策略真的落盘、读回来还是它"——那才是面板与桌面端依赖的事实）。
    grants_before = http("GET", "/v1/agent/grants")
    checks.expect(grants_before.get("policy") == "auto",
                  "默认策略是 auto（零门控、零迁移的出厂档）", str(grants_before.get("policy")))
    try:
        code, put = http_status("PUT", "/v1/agent/grants", {"policy": "byDevice"})
        checks.expect(code == 200 and put.get("policy") == "byDevice",
                      "PUT /v1/agent/grants 接受了策略改写",
                      "%d %s" % (code, json.dumps(put, ensure_ascii=False)))
        code, mid = http_status("GET", "/v1/agent/grants")
        checks.expect(mid.get("policy") == "byDevice", "策略读回来还是 byDevice（落盘生效）",
                      str(mid.get("policy")))
        # byDevice + 空白名单 ⇒ 会话宣告必须 403（执行点：数据根本不出门）。
        code, blocked = http_status("POST", "/v1/agent/session",
                                    {"to": phone_node_id, "sessionId": "s-d7-probe", "agentKind": "DSH"})
        checks.expect(code == 403 and blocked.get("error") == "not-granted",
                      "未授权设备的 agent 帧被拦（没授权 = 没数据）",
                      "%d %s" % (code, json.dumps(blocked, ensure_ascii=False)))
        # 批准 → 再发必须成功（策略是门，不是墙）。
        http_status("PUT", "/v1/agent/grants", {"grant": {"nodeId": phone_node_id, "scope": "all"}})
        code, allowed = http_status("POST", "/v1/agent/session",
                                    {"to": phone_node_id, "sessionId": "s-d7-probe", "agentKind": "DSH"})
        checks.expect(code == 200 and allowed.get("status") in ("sent", "queued"),
                      "批准后同一条请求成功", "%d %s" % (code, json.dumps(allowed, ensure_ascii=False)))
    finally:
        # **必须恢复 auto 并清掉探针授权**：这个脚本跑在用户日常在用的节点上，
        # 把策略留在 byDevice 会让下一次真机验证莫名其妙收不到帧，留一条探针白名单
        # 等于给那台手机开了永久后门 —— 测试不许给环境留副作用。
        http_status("PUT", "/v1/agent/grants", {"revoke": {"nodeId": phone_node_id, "scope": "all"}})
        http_status("PUT", "/v1/agent/grants", {"policy": "auto"})
        code, after = http_status("GET", "/v1/agent/grants")
        checks.expect(after.get("policy") == "auto" and not (after.get("grants") or []),
                      "收尾已恢复 auto 且清空探针授权（不留环境副作用）",
                      json.dumps({k: after.get(k) for k in ("policy", "grants")}, ensure_ascii=False))

    # ---- 第七步：真机界面取证（可选）
    print("\n=================================================================")
    print("【第七步】真机界面取证")
    print("=================================================================")
    if not args.ui:
        checks.skip("手机界面出现该会话名", "未加 --ui（盲点坐标点击不可靠，默认不点）")
    elif not serial:
        checks.skip("手机界面出现该会话名", "没有可用的 adb 设备")
    else:
        xml = ui_dump_xml(serial)
        if alias not in xml:
            # 可能只是没停在控制台那一屏：拉起 App 再 dump 一次，仍没有才算 skip（不是 FAIL）。
            brief = run(["adb", "-s", serial, "shell", "cmd", "package", "resolve-activity",
                         "--brief", "app.relay"]).stdout.strip().splitlines()
            if brief:
                run(["adb", "-s", serial, "shell", "am", "start", "-n", brief[-1]])
                time.sleep(1.5)
                xml = ui_dump_xml(serial)
        shot = run(["adb", "-s", serial, "exec-out", "screencap", "-p"], binary=True)
        Path(SHOT_FILE).write_bytes(shot.stdout)
        print("  截图: %s (%d bytes)" % (SHOT_FILE, len(shot.stdout)))
        if alias in xml:
            checks.ok("手机界面出现了 alias「%s」" % alias)
        elif session_id in xml:
            checks.ok("手机界面出现了 sessionId（%s 没进画面）" % session_id)
        else:
            checks.skip("手机界面出现该会话名",
                        "当前界面没有它，截图在 %s（在手机上手动打开工作区控制台后重跑）" % SHOT_FILE)

    return checks.finish()


if __name__ == "__main__":
    sys.exit(main())
