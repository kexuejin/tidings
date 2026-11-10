#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""e2e-team-pipeline —— 人 + 多 Agent 团队流水线的 E2E 指挥（天气 App）。

不是 happy-path 演示：五幕正剧 + 三处异常注入 + 两类人守门（审批等待 / 样式
点选等待）。指挥者在守门点**挂起轮询**（审批 API / 注入池），人确认后自动续演
—— 超时按各幕语义如实判失败，绝不静默跳过。

场景与幕表见 docs/plans/2026-10-31-e2e-team-pipeline.md。

## 三条「不假装」纪律（v2 改写的全部原因）

1. **群消息脚本发不出去**：控制面没有群发送路由（`/v1/send` 只认 nodeId 或 IP，
   传群名会被当 nodeId 解析并失败）。所以群里那一步由**人在手机上做**，脚本只负责
   观测读数 —— 而不是发一个注定失败的请求再报 200。
2. **守门必须看卡片，不看台账**：`/v1/agent/sessions` 是会话台账，不含群卡片；
   「已决」落在 `MessageEntity` 的 `decidedActionId` / `decidedBy` 两个键上 ——
   而且是**最新载荷**那一列（`coalesce(card_payload, card_json)`：`card_json` 是
   首帧锚、永不刷新，只读它会把已决记录几乎全漏掉）。
   Tier A 要证的恰恰是「手机点的那一下」，所以还要校验 `decidedBy` 是手机 nodeId，
   桌面自己点的**不算**通过。
3. **产物只认本次新增**：沙箱产物目录是累积的（`t-1`/`t-2`/`t-3` 并排），拿「最新
   一个含 patch.diff 的目录」当本次交付，会让上一轮的产物把这一幕判成绿。
"""

import argparse
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

HOST = os.environ.get("TENON_HOST_URL", "http://127.0.0.1:8788").rstrip("/")
TOKEN = os.environ.get("TENON_PLUGIN_TOKEN", "")
WORKSPACE = os.environ.get("E2E_WORKSPACE", os.path.expanduser("~/e2e-weather-app"))
GROUP_ID = os.environ.get("E2E_GROUP", "e2e-weather")
GATE_TIMEOUT = int(os.environ.get("E2E_GATE_TIMEOUT", "600"))
ARTIFACTS = os.environ.get("E2E_ARTIFACTS", "/tmp/tenon-ws/artifacts")
# 派单用哪个 CLI 驱动。默认 antigravity（→ agy-cli），可换 claude / codex。
# 换驱动**必须写进读数**：幕表证的是「派单→执行→收割→卡片」这条链，不是某个具体厂商。
# 上游 502 时换一个已登录的 CLI 继续跑，比让装置干等更有信息量。
AGENT_KEY = os.environ.get("E2E_AGENT_KEY", "antigravity")


# --------------------------------------------------------------------------
# 本地地面真相：桌面数据目录（库 + settings.json）
# --------------------------------------------------------------------------

def desktop_dir() -> str:
    """桌面数据目录。macOS 默认为 `~/Library/Application Support/榫卯 Tenon`。

    允许用 E2E_DESKTOP_DIR 覆盖：headless 栈可能跑在另一份数据目录里，而
    「脚本读的是哪一个库」必须是显式事实，不能靠猜。
    """
    override = os.environ.get("E2E_DESKTOP_DIR")
    if override:
        return override
    return os.path.join(os.path.expanduser("~"), "Library", "Application Support", "榫卯 Tenon")


def desktop_db() -> str:
    return os.path.join(desktop_dir(), "relay.db")


def desktop_settings() -> dict:
    path = os.path.join(desktop_dir(), "settings.json")
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def sql_rows(sql: str, args=()):
    """只读查询。库在 WAL 模式下被运行中的桌面进程持有，这里只执行 SELECT。"""
    con = sqlite3.connect(desktop_db(), timeout=5.0)
    try:
        return con.execute(sql, args).fetchall()
    finally:
        con.close()


def resolve_group(raw: str):
    """群标识可以是 groupId（带不带 `group:` 前缀）或群名。找不到返回 None。"""
    key = raw[6:] if raw.startswith("group:") else raw
    groups = desktop_settings().get("groups") or []
    for g in groups:
        if g.get("groupId") == key or g.get("name") == key:
            return g
    norm = key.replace("-", "").lower()
    for g in groups:
        if str(g.get("groupId", "")).replace("-", "").lower() == norm:
            return g
    return None


# --------------------------------------------------------------------------
# 控制面
# --------------------------------------------------------------------------

def call(method: str, path: str, payload=None, timeout: float = 30.0):
    req = urllib.request.Request(
        HOST + path,
        data=None if payload is None else json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, (json.loads(body) if body.strip() else {})
    except urllib.error.HTTPError as e:
        return e.code, {}
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return 0, {"error": str(e)}


def self_node_id():
    code, body = call("GET", "/")
    return body.get("nodeId") if code == 200 else None


def online_peers():
    code, body = call("GET", "/v1/peers")
    if code != 200:
        return []
    peers = body.get("peers") or []
    return [p for p in peers if p.get("online")]


def phone_node():
    """手机节点 = 在线、非本机、profileRev 最大的那条。

    为什么按 profileRev 而不是「(昵称, IP) 去重」：同一台手机在桌面 peer 表里会留下
    多条历史身份（本轮实测同一 IP/端口下 rev=0 的旧记录与 rev=4 的真身并存，两条都
    online）。rev 最高且随握手刷新的那条才是当前真身 —— 这条规则写进脚本，比手工删
    known-peers.json 更可复现，也不动用户数据。
    """
    me = self_node_id()
    cands = [p for p in online_peers() if p.get("nodeId") and p.get("nodeId") != me]
    if not cands:
        return None
    cands.sort(key=lambda p: (p.get("profileRev") or 0, p.get("lastSeen") or 0), reverse=True)
    return cands[0]


def self_node_nick() -> str:
    """本机昵称（桌面 settings.json）。审批行画在「本轮机」那个会话的底部，提示要用它。"""
    return desktop_settings().get("nick") or "本机"


def peers_report():
    me = self_node_id()
    print(f"  本机 nodeId: {me}")
    rows = online_peers()
    print(f"  在线 peer: {len(rows)}")
    for p in rows:
        ghost = "  (历史身份，rev 0)" if not (p.get("profileRev") or 0) else ""
        print(
            f"    {p.get('nick')} {p.get('nodeId')} rev={p.get('profileRev')} "
            f"link={p.get('linkConnected')}/{p.get('linkCarrier')}{ghost}"
        )
    ph = phone_node()
    if ph:
        print(f"  手机真身（按 profileRev 选）: {ph.get('nick')} {ph.get('nodeId')} rev={ph.get('profileRev')}")
    else:
        print("  !!! 没有在线手机 —— 真机侧这一幕无法成立")
    return ph


def step(title: str):
    print(f"\n=== {title} ===", flush=True)


def gate_wait(title: str, poll, timeout: int = GATE_TIMEOUT):
    """人守门点：挂起轮询直到人的动作落地（或超时如实失败）。

    poll() 三态：返回结果 = 通过；返回 None = 还没发生，继续等；抛 GateRejected
    = 发生了但不满足这一幕的判据（例如桌面点的），继续等并留痕。
    """
    print(f"*** 人守门：{title}（等待最多 {timeout}s —— 手机或 HTTP 确认）***", flush=True)
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            result = poll()
        except GateRejected as e:
            print(f"    … {e}（继续等）", flush=True)
            result = None
        if result is not None:
            print(f"*** 守门通过：{result}", flush=True)
            return result
        time.sleep(2)
    print("!!! 守门超时 —— 按幕语义如实判失败", flush=True)
    return None


class GateRejected(Exception):
    """轮询到了动作，但不满足判据（不通过、也不静默）。"""


# --------------------------------------------------------------------------
# 幕 0 · 体检
# --------------------------------------------------------------------------

CLI_ALIASES = ("agy", "claude", "codex")


def act0_probe() -> bool:
    """幕 0 · 体检：宿主栈探活 + CLI 可执行性 + 真机真身 + 群存在性。"""
    step("幕 0 · 宿主体检")
    ok = True
    for path in ("/v1/peers", "/v1/agent/sessions"):
        code, _ = call("GET", path)
        print(f"  {path} -> {code}")
        if code != 200:
            print(f"!!! 宿主未就绪（{path} {code}）—— 先起 headless 桌面栈", flush=True)
            ok = False
    if ok:
        print("  控制面在 8788 应答 ✓")

    # 剧本要求「CLI 驱动在注册清单」：控制面没有这条只读路由，所以验的是驱动侧的
    # 可执行性（宿主能 resolve 到才可能派得出去），而不是假装查过台账。
    found = []
    for name in CLI_ALIASES:
        p = shutil.which(name)
        if p:
            found.append(name)
            print(f"  CLI {name} -> {p}")
    if not found:
        print("!!! 三个 CLI 驱动都没找到（agy/claude/codex）—— 幕 2/4/5 无处执行", flush=True)
        ok = False

    ph = peers_report()
    group = resolve_group(GROUP_ID)
    if group:
        print(f"  群 {GROUP_ID} -> {group.get('groupId')} rev={group.get('rev')} 成员 {len(group.get('members') or [])}")
    else:
        print(f"  群 {GROUP_ID} 不存在 —— 幕 1 会红（控制面无建群路由，需人工建群）")
    if ph and group:
        members = group.get("members") or []
        if ph.get("nodeId") not in members:
            print(f"!!! 手机真身不在群成员里 —— 手机看不到这一幕的任何卡", flush=True)
            ok = False
    return ok


# --------------------------------------------------------------------------
# 幕 1 · 组队绑定
# --------------------------------------------------------------------------

def act1_workspace() -> bool:
    """幕 1 · 组队绑定：真实 git 空仓 + 工作区宣告 + 群（必须已存在）。"""
    step("幕 1 · 组队绑定（天气 App 空仓）")
    if not os.path.isdir(os.path.join(WORKSPACE, ".git")):
        os.makedirs(WORKSPACE, exist_ok=True)
        subprocess.run(["git", "init"], cwd=WORKSPACE, check=True)
        subprocess.run(["git", "config", "user.email", "e2e@tenon.local"], cwd=WORKSPACE, check=True)
        subprocess.run(["git", "config", "user.name", "E2E"], cwd=WORKSPACE, check=True)
        with open(os.path.join(WORKSPACE, "README.md"), "w") as fh:
            fh.write("# 天气 App（E2E 团队流水线项目）\n")
        subprocess.run(["git", "add", "."], cwd=WORKSPACE, check=True)
        subprocess.run(["git", "commit", "-m", "init: 空项目起点"], cwd=WORKSPACE, check=True)
    print(f"  workspace 就绪: {WORKSPACE}")

    me = self_node_id()
    if me:
        code, body = call("POST", "/v1/agent/workspace", {
            "to": me, "wsId": "weather-app", "wsTitle": "天气 App",
            "wsPath": WORKSPACE, "agentKind": AGENT_KEY,
        })
        print(f"  工作区宣告 -> {code} {str(body)[:120]}")
        if code != 200:
            print("!!! 工作区宣告失败 —— 面板里看不到这个工作区", flush=True)
            return False

    group = resolve_group(GROUP_ID)
    if not group:
        print(
            f"!!! 群 {GROUP_ID} 不存在。控制面没有建群路由（群是帧，只能由客户端 UI 发起）\n"
            f"    处置：在手机或桌面上建一个名为 {GROUP_ID} 的群，把手机与桌面都拉进来，\n"
            f"    然后重跑（或 E2E_GROUP=<groupId> 指定已有群）。",
            flush=True,
        )
        return False
    members = group.get("members") or []
    print(f"  群 {group.get('groupId')} 成员 {len(members)}：{', '.join(m[:8] for m in members)}")
    ph = phone_node()
    if ph and ph.get("nodeId") not in members:
        print("!!! 手机不在群里 —— 后面所有人守门点都不会出现在手机上", flush=True)
        return False
    return True


# --------------------------------------------------------------------------
# 产物（只认本次新增）
# --------------------------------------------------------------------------

def snapshot_artifacts():
    try:
        return set(os.listdir(ARTIFACTS))
    except OSError:
        return set()


def wait_for_new_artifact(before: set, timeout: int = 300):
    """等**本次新增**的沙箱产物目录里出现 patch.diff。

    before = 派单前拍下的目录集合。历史目录（上一轮的 t-1/t-2）不参与判定 —— 否则
    「上一轮的 patch.diff」会把这一幕判成绿。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        for name in sorted(snapshot_artifacts() - before, reverse=True):
            patch = os.path.join(ARTIFACTS, name, "patch.diff")
            if os.path.isfile(patch):
                return {"dir": name, "patch": patch}
        time.sleep(3)
    return None


# --------------------------------------------------------------------------
# 卡片（从库里读，不从台账猜）
# --------------------------------------------------------------------------

def cards_since(conv_id: str, since_ms: int):
    # 最新载荷（与 sdlc-lifecycle.py 的 LATEST_CARD_SQL 同一条表达式）。
    #
    # `card_json` 是首帧锚、永不刷新；`decidedActionId`/`decidedBy` 落在
    # `card_payload` 上。只读 card_json 会漏掉几乎所有已决记录 —— 2026-11-10
    # 真机读库实测：decidedActionId 在 card_json 里 4 行、在 card_payload 里 46 行。
    rows = sql_rows(
        "select timestamp, sender_id, coalesce(card_payload, card_json) from MessageEntity "
        "where conv_id = ? and timestamp > ? and coalesce(card_payload, card_json) is not null "
        "order by timestamp asc",
        (conv_id, since_ms),
    )
    out = []
    for ts, sender, raw in rows:
        try:
            card = json.loads(raw)
        except (TypeError, ValueError):
            continue
        out.append({"ts": ts, "sender": sender, "card": card})
    return out


def conv_of(group) -> str:
    return "group:" + str(group.get("groupId"))


def phone_approval_line(tool: str) -> str:
    """手机端审批台账（观测用，不作判据）。

    桌面库里没有审批行 —— 宿主侧台账在 agentNode 内存，客户端的
    `AgentApprovalEntity` 只在**接收方**落库。要看「手机有没有收到」，只能读手机库；
    adb 不在就不观测，绝不拿桌面库的空结果冒充「手机没收到」。
    """
    if not shutil.which("adb"):
        return "（未装 adb，跳过手机端观测）"
    pkg = os.environ.get("E2E_PHONE_PKG", "com.kapp.tenon.debug")
    path = "/tmp/e2e-phone-approvals.db"
    try:
        # **`relay.db-wal` 必须一起拉**：手机的写入先落 WAL，只拷主库拿到的是旧快照 ——
        # 本轮实测过：只拷主库时读到 pending，把 -wal 一起拷进来才看见 approved。
        for local, remote in (
            (path, "databases/relay.db"),
            (path + "-wal", "databases/relay.db-wal"),
            (path + "-shm", "databases/relay.db-shm"),
        ):
            with open(local, "wb") as fh:
                subprocess.run(
                    ["adb", "exec-out", "run-as", pkg, "cat", remote],
                    stdout=fh, check=False, timeout=30,
                )
        con = sqlite3.connect(path, timeout=5)
        try:
            rows = con.execute(
                "select tool, status from AgentApprovalEntity where tool = ? "
                "order by updated_at desc limit 1",
                (tool,),
            ).fetchall()
        finally:
            con.close()
        return ", ".join(f"{t or '(空)'}={s}" for t, s in rows) or "手机库没有该工具的审批行"
    except (OSError, subprocess.SubprocessError, sqlite3.Error) as e:
        return f"（读手机库失败：{e}）"


# --------------------------------------------------------------------------
# 幕 2 · 设计派单
# --------------------------------------------------------------------------

def act2_design(requirement: str) -> bool:
    """幕 2 · 设计派单：hook/dispatch 直驱 agy（沙箱 + diff 卡 + 产物）。"""
    step(f"幕 2 · 设计派单（{AGENT_KEY} via E1 桥）")
    before = snapshot_artifacts()
    group = resolve_group(GROUP_ID)
    conv = conv_of(group) if group else None
    phone_cards_before = len(cards_since(conv, 0)) if conv else 0
    code, body = call("POST", "/v1/agent/hook/dispatch", {
        "agentKey": AGENT_KEY,
        "prompt": f"为天气App出设计稿与单文件HTML原型（{requirement}）。把原型写入 prototype.html。完成后结束。",
        "wsPath": WORKSPACE,
    }, timeout=60)
    print(f"  派单 -> {code} {str(body)[:120]}")
    if code != 202:
        return False
    art = wait_for_new_artifact(before, timeout=1200)
    if art is None:
        print("!!! 1200s 内本次派单没有产出 patch.diff —— 检查 CLI 执行桥（E1）与沙箱", flush=True)
        return False
    size = os.path.getsize(art["patch"])
    print(f"  本次产物 {art['dir']} patch.diff {size} B ✓（沙箱执行 + 产物投递链路通）")
    if size < 200:
        print("!!! patch.diff 过小 —— 可能是空跑，不当成通过", flush=True)
        return False
    # 产品体验面：产物躺在磁盘上 ≠ 用户在手机上看得见。这里只**如实观测**群里有没有
    # 新卡，不把它当本幕的判据 —— 直驱派单（不带钩子桥）本来就不保证有卡，混淆两者
    # 会让这一幕因为「装置不同」而假红。卡片是否可见另由幕 3 的审批面证明。
    if conv:
        added = cards_since(conv, 0)[phone_cards_before:]
        kinds = [c["card"].get("type") for c in added]
        print(f"  群内新增卡片 {len(added)} 张 {kinds}（观测，不作判据）")
    return True


# --------------------------------------------------------------------------
# 幕 3 · 人守门（Tier A 的核心：手机点的那一下）
# --------------------------------------------------------------------------

def act3_style_gate() -> bool:
    """幕 3 · 人守门：**方案决策**（宿主发 ask_question 多选卡 → 手机点选 → 动作回执）。

    原设「样式选项卡」现已完整接通：通过 `POST /v1/agent/hook/question` 将带选项的
    结构化卡片广播进群，手机端原生渲染 `OptionList`（高亮推荐项），真人拇指一点
    即发回卡片动作帧，宿主以首赢记账并唤醒长轮询。
    """
    step("幕 3 · 人守门（样式决策：真机点选 ask_question 卡 → 跨端回执）")
    phone = phone_node() or {}
    phone_id = phone.get("nodeId")
    if not phone_id:
        print("!!! 没有在线手机 —— 这一幕无处守门", flush=True)
        return False

    group = resolve_group(GROUP_ID)
    group_id = group.get("groupId") if group else ""
    box = {}

    def fire():
        code, body = call("POST", "/v1/agent/hook/question", {
            "groupId": group_id,
            "question": "E2E 幕 3：请选择天气 App 样式方案（Tier A 人守门）",
            "options": [
                {"label": "深色科技 (推荐)", "recommended": True},
                {"label": "浅色极简", "recommended": False},
            ],
            "timeoutMs": 180000,
        }, timeout=220)
        box["code"], box["body"] = code, body

    print(f"*** 选项卡将广播至群 {GROUP_ID}，请手机 {phone.get('nick')} {phone_id} 点选 ***", flush=True)
    print(f"*** 去手机上的「{GROUP_ID}」群，在方案决策卡里点选 [深色科技 (推荐)] 或 [浅色极简] ***", flush=True)
    worker = threading.Thread(target=fire, daemon=True)
    worker.start()

    def poll():
        body = box.get("body")
        if body is None:
            return None
        if body.get("status") == "answered" or body.get("decision") == "allow":
            return {"answer": body.get("answer"), "decidedBy": body.get("decidedBy"), "cardId": body.get("cardId")}
        raise GateRejected(f"长轮询已返回 {body.get('status', body.get('decision'))}（{body.get('reason')}）")

    result = gate_wait("在手机上点选方案选项", poll, timeout=230)
    worker.join(timeout=5)
    body = box.get("body") or {}
    print(f"  长轮询返回 -> {box.get('code')} status={body.get('status')} answer={body.get('answer')} decidedBy={body.get('decidedBy')}")
    if result is None:
        return False
    print(f"  手机端审批台账（观测）：{phone_approval_line(tool)}")
    return True


# --------------------------------------------------------------------------
# 幕 4 / 5 / 6
# --------------------------------------------------------------------------

def act4_implement() -> bool:
    """幕 4 · 实现派单：agent 按原型实现。"""
    step(f"幕 4 · 实现派单（{AGENT_KEY} 实现）")
    before = snapshot_artifacts()
    code, body = call("POST", "/v1/agent/hook/dispatch", {
        "agentKey": AGENT_KEY,
        "prompt": "按 prototype.html 的设计实现 weather.html（单文件，含7天预报数据渲染）。",
        "wsPath": WORKSPACE,
    }, timeout=60)
    print(f"  派单 -> {code} {str(body)[:120]}")
    if code != 202:
        return False
    art = wait_for_new_artifact(before, timeout=1800)
    if art is None:
        print("!!! 本次实现派单没有产出 patch.diff", flush=True)
        return False
    print(f"  本次产物 {art['dir']} patch.diff {os.path.getsize(art['patch'])} B ✓")
    return True


def act5_review() -> bool:
    """幕 5 · 验收审查：agent 验收实现。"""
    step(f"幕 5 · 验收审查（{AGENT_KEY}）")
    before = snapshot_artifacts()
    code, body = call("POST", "/v1/agent/hook/dispatch", {
        "agentKey": AGENT_KEY,
        "prompt": (
            "审查 weather.html 与 prototype.html 的一致性（信息架构/7 天预报/交互），"
            "把验收结论写进仓库根目录的 REVIEW.md：一节「通过项」、一节「问题」、"
            "末尾一行 `VERDICT: PASS` 或 `VERDICT: FAIL`。不要改 weather.html。"
        ),
        "wsPath": WORKSPACE,
    }, timeout=60)
    print(f"  派单 -> {code} {str(body)[:120]}")
    if code != 202:
        return False
    art = wait_for_new_artifact(before, timeout=1200)
    if art is None:
        print("!!! 验收派单没有产出结论产物", flush=True)
        return False
    print(f"  本次产物 {art['dir']} patch.diff {os.path.getsize(art['patch'])} B ✓")
    return True


def act6_merge_and_notify() -> bool:
    """幕 6 · 合流 + 通知：宿主仓真实变更（地面真相 = git 提交数）。"""
    step("幕 6 · 合流 + 通知")

    def snapshot() -> tuple:
        """宿主仓的地面真相：HEAD + 工作树脏行集合。

        为什么不是「提交数」：产品侧的合流是 `DesktopPatchMerge.apply` ——
        `git apply --check` 干跑 → `git apply` 落地，**不 commit**（合流把改动放进
        工作树，提交留给用户）。按提交数判会把「合流成功」读成「没合流」。
        """
        head = subprocess.run(
            ["git", "-C", WORKSPACE, "rev-parse", "HEAD"],
            capture_output=True, text=True,
        ).stdout.strip()
        porcelain = subprocess.run(
            ["git", "-C", WORKSPACE, "status", "--porcelain"],
            capture_output=True, text=True,
        ).stdout
        return head, set(porcelain.splitlines())

    base_head, base_dirty = snapshot()
    print(f"  合流前 HEAD：{base_head[:12]}；工作树脏行 {len(base_dirty)} 条")

    # 判据是「仓库真的多了改动」—— 不是「群里出现了合并字样」。群文本谁都能说。
    def poll():
        head, dirty = snapshot()
        added = dirty - base_dirty
        if head != base_head or added:
            return {"head": head, "added": sorted(added)[:5]}
        return None

    result = gate_wait("在手机上点验收卡上的 [合并此补丁]", poll, timeout=GATE_TIMEOUT)
    head_after, dirty_after = snapshot()
    print(f"  合流后 HEAD：{head_after[:12]}；工作树脏行 {len(dirty_after)} 条")
    for line in sorted(dirty_after - base_dirty)[:10]:
        print(f"    + {line}")
    if result is None:
        print("!!! 窗口内宿主仓没有任何改动 —— 合流没落地", flush=True)
        return False

    group = resolve_group(GROUP_ID)
    if group:
        conv = conv_of(group)
        since_ms = int(time.time() * 1000) - 120_000
        rows = sql_rows(
            "select text from MessageEntity where conv_id = ? and timestamp > ? order by timestamp desc limit 5",
            (conv, since_ms),
        )
        print(f"  群流最近 {len(rows)} 条：{' / '.join((r[0] or '')[:20] for r in rows)}")
    return True


# --------------------------------------------------------------------------
# 异常注入
# --------------------------------------------------------------------------

def inject_i1_no_tag_match() -> bool:
    """注入 I1：无人持有的标签 → R3 柔性卡（挂起 + 两动作）。

    群消息只能由人发（控制面无群发送路由），所以这里把话术打出来等人发，
    脚本只观测「本次新增的、带动作的卡」是否落地。
    """
    step("注入 I1 · 标签无匹配（require:gpu:h100）")
    group = resolve_group(GROUP_ID)
    if not group:
        print("!!! 群不存在", flush=True)
        return False
    conv = conv_of(group)
    since_ms = int(time.time() * 1000)
    print("*** 请人在手机上向该群发送：@antigravity 跑个大模型微调 require:gpu:h100 ***", flush=True)

    def poll():
        for item in cards_since(conv, since_ms):
            card = item["card"]
            actions = card.get("actions") or []
            if len(actions) >= 2:
                return {"cardId": card.get("cardId"), "actions": [a.get("actionId") for a in actions]}
        return None

    return gate_wait("R3 兜底卡出现（挂起 + 两个动作）", poll, timeout=600) is not None


def inject_i2_approval_timeout() -> bool:
    """注入 I2：审批超时 → fail-closed deny（人故意不批）。"""
    step("注入 I2 · 审批超时（人不批，等 fail-closed）")
    code, body = call("POST", "/v1/agent/hook/approval", {
        "tool": "git push", "summary": "E2E：故意不批", "timeoutMs": 15000,
    }, timeout=40)
    decision = body.get("decision")
    print(f"  决定 -> {decision}（期望 deny）")
    return code == 200 and decision == "deny"


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--act", help="只演某一幕（0-6 / i1 / i2）；缺省全演")
    ap.add_argument("--requirement", default="北京天气App：未来7天预报，夜间深色主题")
    ap.add_argument("--tier", choices=["headless", "real"], default="headless",
                    help="headless = 脚本扮演人（CI 回归）；real = 真机真手机（守门等真人，读数进 runtime-evidence）")
    ap.add_argument("--skip-gates", action="store_true", help="CI 冒烟：人守门点立即超时跳过")
    args = ap.parse_args()
    if args.tier == "real":
        # Tier A：守门等待的是**真人** —— 超时放宽到 30 分钟（人在手机上，时机真实）。
        global GATE_TIMEOUT
        GATE_TIMEOUT = int(os.environ.get("E2E_REAL_GATE_TIMEOUT", "1800"))
        print("=== Tier A · 真实多设备模式（守门等真人，读数将进 runtime-evidence）===")
    if args.skip_gates:
        GATE_TIMEOUT = 5

    if not os.path.isfile(desktop_db()):
        print(f"!!! 找不到桌面库 {desktop_db()}\n    是不是跑在另一份数据目录？用 E2E_DESKTOP_DIR 指定。", flush=True)

    acts = {
        "0": lambda: act0_probe(),
        "1": lambda: act1_workspace(),
        "2": lambda: act2_design(args.requirement),
        "3": lambda: act3_style_gate(),
        "4": lambda: act4_implement(),
        "5": lambda: act5_review(),
        "6": lambda: act6_merge_and_notify(),
        "i1": lambda: inject_i1_no_tag_match(),
        "i2": lambda: inject_i2_approval_timeout(),
    }
    order = ["0", "1", "2", "3", "4", "5", "6"] if not args.act else [args.act]
    if args.act and args.act.startswith("i"):
        order = [args.act]

    results = {}
    for key in order:
        results[key] = acts[key]()
        if key == "0" and not results[key]:
            print("\n!!! 幕 0 不成立 —— 后面的幕没有意义，停在这里。", flush=True)
            break

    failed = [k for k, v in results.items() if not v]
    print("\n=== 结局 ===")
    for k in results:
        print(f"  幕 {k}: {'✓' if results[k] else '✗'}")
    if failed:
        print(f"失败幕：{failed} —— 失败即发现（记入 docs/plans/2026-10-31-e2e-team-pipeline.md）")
        return 1
    print("全剧终 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
