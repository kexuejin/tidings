#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""团队编制（声明与展示）真机夹具（e2e_team_roster.py）。

夹具扮演**一台电脑**（对端 A），以**普通成员**身份在手机建的群里逐步改写自己的
agent 声明 —— 这正是合并主权分支（`GroupMerge.memberMayRedeclareSelf`）的线上形状：

  rev+2  自声明席位（单兵，带 defaultWsId）      → 手机 apply；旧版本会整帧拒收
                                                   （这就是 P0-0.2 修的 F2 的线上证据）
  rev+3  同席位升为团队（3 名组员）              → 手机 apply；群信息出「团队 (3)」徽标、
                                                   会话列表出「团队 3 ▸」折叠药丸
  rev+4  越预算（9 名组员 > TEAM_MEMBERS_MAX=8） → 手机**整帧拒收**（validateGroup
                                                   bad-payload）：rev 不动、界面无变化、不崩
  rev+5  关闭团队（single + 空组员）             → 手机 apply；徽标与药丸消失

全程判据：`closeCount` 不新增、群名与会话行不受影响。展开态还要点成员行 ——
判据是**草稿被预填 `@<leader> `**（不是组员名；ConversationsScreen.kt 的
onMentionMember 写的就是 leader token）。

跑法：
    python3 scripts/e2e/e2e_team_roster.py --dry-run              # 不碰设备、不碰网络
    scripts/relay-liveness.sh --quiet && python3 scripts/e2e/e2e_team_roster.py --secs 420
    python3 scripts/e2e/e2e_team_roster.py --skip-ui --secs 120   # 只跑协议面

退出码：0=PASS，1=FAIL，2=用法/环境错误。建群流程复用 e2e_group 的夹具函数
（同一套 uiautomator 驱动 —— **不要复制**，AGENTS §2.6 同一条纪律）。
"""

import json
import os
import sys
import threading
import time
import uuid

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import relay_e2e as R  # noqa: E402
import e2e_group as G  # noqa: E402  —— 复用它的建群 UI 驱动与 meta 工具

# ---------------------------------------------------------------------------
# 常量 —— 逐条抄自实现，改一个就会静默丢帧
# ---------------------------------------------------------------------------

# Limits.kt 的三个预算常量（收端校验 P0-0.3）：越界的帧要被**整帧拒收**。
TEAM_MEMBERS_MAX = 8
ROLE_LIMIT = 32

# 会话列表的折叠药丸（team_folder_badge = "团队 %1$d" + "  ▸"）与展开标签行
# （TeamLabelRow = "@" + token、"N 个成员"）。群信息页徽标（team_roster_badge）。
PILL_TEXT_FMT = "团队 %d  \u25b8"     # 折叠态；展开态是 \u25be
INFO_BADGE_FMT = "团队 (%d)"
MEMBERS_SUFFIX = "个成员"

# 群信息页的入口（ChatScreen 头部；content-desc）。
TXT_INFO_TITLE = "群信息"

LEAD_AGENT_ID = "e2e-lead"
LEAD_TOKEN_FMT = "e2e-lead-%s"
WS_ID_FMT = "ws-e2e-%s"
PRESET_MEMBERS = [                      # 方案 §2.2 的经典研发组（名字, 角色）
    ("coder", "编码"),
    ("reviewer", "审查"),
    ("tester", "测试"),
]

GROUP_INFO_REQUIRED = ("groupId", "name", "members", "rev", "updatedBy", "updatedTs")


# ---------------------------------------------------------------------------
# 本地自检（dry-run 也跑）
# ---------------------------------------------------------------------------

def _local_self_checks(args, node_a):
    """纯本地：帧形状与镜像工具的判据。不碰网络、不碰设备。"""
    phase = lambda t: R.log("— %s" % t)
    phase("本地自检")
    assert TEAM_MEMBERS_MAX == 8, "TEAM_MEMBERS_MAX 与 Limits.kt 不同步"
    ws_id = WS_ID_FMT % "selfcheck"
    lead = LEAD_TOKEN_FMT % "selfcheck"
    seat = {
        "agentId": LEAD_AGENT_ID, "hostNodeId": node_a, "name": lead,
        "entityType": "single", "teamMembers": [], "defaultWsId": ws_id,
    }
    meta = {
        "groupId": "selfcheck", "name": "selfcheck", "rev": 2,
        "updatedBy": node_a, "updatedTs": R.now_ms(),
        "members": ["selfNode", node_a], "virtualAgents": [seat],
    }
    env = R.envelope(R.T_GROUP, {"op": "info", "group": meta}, node_id=node_a)
    assert env["type"] == R.T_GROUP, "信封 type"
    assert set(env["payload"].keys()) == {"op", "group"}, "group.info 载荷键"
    assert env["payload"]["group"]["virtualAgents"][0]["name"] == lead
    team_seat = dict(seat, entityType="team", teamMembers=[
        {"agentId": "m-" + n, "name": n, "role": r} for (n, r) in PRESET_MEMBERS])
    assert len(team_seat["teamMembers"]) == 3
    over = [dict(seat) for _ in range(TEAM_MEMBERS_MAX + 1)]
    assert len(over) > TEAM_MEMBERS_MAX, "越预算构造"
    R.log("自检通过：帧形状 / 预算构造 / 经典组员清单")


def _seat(entity_type, team_members, node_a, lead, ws_id):
    """一条本机席位声明。**只**由这个函数构造 —— 三次 rev 的声明逐字段可比。"""
    return {
        "agentId": LEAD_AGENT_ID,
        "hostNodeId": node_a,
        "name": lead,
        "entityType": entity_type,
        "teamMembers": team_members,
        "defaultWsId": ws_id,
    }


def _row_has_team_pill(title, member_count):
    """本会话行上有没有「团队 N」药丸（**行内作用域**）。

    全屏 find 会误伤别的群 —— 上一轮留下的团队群还挂着药丸，"药丸消失"的判据
    被它咬成假红。判据：先按群名找到会话行，再在**同一竖直带**内找药丸文本。
    """
    row = G._conv_row(title)
    if row is None or row.center is None:
        return None
    for n in R.ui_all():
        label = (n.text or "").strip()
        if label.startswith("团队 ") and n.center and abs(n.center[1] - row.center[1]) <= 40:
            return n
    return None


def _send_info(args, st, rev, virtual_agents, extra_changes=None):
    """以普通成员身份发一份 rev+ 的 info，返回是否拿到 ACK。

    **必须 mirror**（e2e_group._mirror 的同一条理由）：management-identity 四键
    两侧不完全一致就会被拒，症状与"对方没收到"一模一样。virtualAgents 整体替换
    —— 成员主权只许动 `hostNodeId == 自己` 的声明，本夹具的声明恰好只有这一条。
    """
    changes = {
        "rev": rev,
        "updatedBy": st["node_a"],
        "updatedTs": R.now_ms(),
        "virtualAgents": virtual_agents,
    }
    if extra_changes:
        changes.update(extra_changes)
    meta = G._mirror(st["meta"], **changes)
    env = st["peer_a"].env(R.T_GROUP, {"op": "info", "group": meta})
    st["peer_a"].send(env)
    acked = st["peer_a"].wait_ack(env["id"], G._budget(args, 8))
    R.log("发 info rev=%d entityType=%s 组员=%d acked=%s"
          % (rev, virtual_agents[0]["entityType"], len(virtual_agents[0]["teamMembers"]), acked))
    return acked


def _open_group_info(args, st):
    """打开群会话 → 群信息面板。成功返回 True（面板里已能读 ui_texts）。"""
    if not R.open_chat(st["group_name"]):
        R.check("team-chat-opened", False, "群会话打不开")
        return False
    # 「群信息」不是常驻图标：顶栏动作统一收进 ⋮ 溢出菜单（ChatHeader.kt 的
    # inlineActions 容量裁剪）。屏上有**两个**「更多功能」（顶栏 ⋮ 与输入栏 ➕），
    # 按 bounds 的 y 判别 —— 顶栏那颗在状态栏之下、内容区之上（y < 400）。
    # 进屏的转场/横幅动画会让第一次 dump 拍到半棵树 —— 轮询重试，不做单发。
    overflow = None
    deadline = time.time() + G._budget(args, 8)
    while overflow is None and time.time() < deadline:
        for n in R.ui_all():
            # 顶栏 ⋮ 是**图标**：文案在 content-desc（chat_more_actions），不是 text。
            label = ((n.text or "") + " " + (n.desc or "")).strip()
            if label == "更多功能" and n.center and n.center[1] < 400:
                overflow = n
                break
        if overflow is None:
            time.sleep(0.8)
    if overflow is None:
        # 旧形态兜底：群信息曾是常驻图标（content-desc=群信息）。
        direct = R.find_ui(desc=TXT_INFO_TITLE, timeout=2.0)
        if direct:
            R.tap_node(direct)
            time.sleep(1.2)
            return True
        R.check("team-info-entry", False, "找不到顶栏 ⋮（更多功能）")
        return False
    R.tap_node(overflow)
    time.sleep(0.8)
    item = R.find_ui(text=TXT_INFO_TITLE, timeout=G._budget(args, 5)) \
        or R.find_ui(desc=TXT_INFO_TITLE, timeout=2.0)
    if not item:
        R.check("team-info-entry", False, "⋮ 菜单里没有「群信息」")
        return False
    R.tap_node(item)
    time.sleep(1.2)
    return True


def _close_dialog():
    """关掉群信息面板（点「关闭」；找不到就退一格兜底）。"""
    if not R.tap_text("关闭", timeout=3.0):
        R.press_back()
    time.sleep(0.8)


def _declared_phase(args, st):
    """T1：成员宿主自声明席位（单兵）→ 手机 apply（旧版本这里整帧拒收）。"""
    phase = R.log
    phase("T1 成员宿主自声明席位（单兵）—— 合并主权的线上证据")
    lead = st["lead"]
    ws_id = st["ws_id"]
    rev = int(st["rev"]) + 1
    since = time.time()
    acked = _send_info(args, st, rev, [_seat("single", [], st["node_a"], lead, ws_id)])
    R.check("team-seat-declared-acked", bool(acked), "可靠帧无条件回 ACK")
    if not acked:
        return False
    st["rev"] = rev
    # 界面判据：群信息页出现这一行（副行 = mention token）。
    if not _open_group_info(args, st):
        return False
    ok = R.find_ui(text="@" + lead, timeout=G._budget(args, 8)) is not None
    R.check("team-seat-row-visible", ok, "群信息里出现 @%s（副行=可 @ 的 token）" % lead)
    R.check("team-seat-no-badge-yet",
            R.find_ui(text=INFO_BADGE_FMT % 3, timeout=1.0) is None,
            "单兵不该有团队徽标")
    _close_dialog()
    return ok


def _team_phase(args, st):
    """T2：同席位升为团队 → 徽标 + 折叠药丸 + 展开成员 + 点成员预填 @leader。"""
    phase = R.log
    phase("T2 席位升为团队（3 名组员）")
    lead = st["lead"]
    ws_id = st["ws_id"]
    rev = int(st["rev"]) + 1
    roster = [{"agentId": "m-" + n, "name": n, "role": r} for (n, r) in PRESET_MEMBERS]
    acked = _send_info(args, st, rev, [_seat("team", roster, st["node_a"], lead, ws_id)])
    R.check("team-declare-acked", bool(acked), "ACK")
    if not acked:
        return False
    st["rev"] = rev

    # 群信息页：「团队 (3)」徽标 + 副行 token。
    if not _open_group_info(args, st):
        return False
    badge_ok = R.find_ui(text=INFO_BADGE_FMT % 3, timeout=G._budget(args, 8)) is not None
    R.check("team-info-badge", badge_ok, "群信息 agent 行出现「团队 (3)」")
    R.check("team-info-token-still", R.find_ui(text="@" + lead, timeout=2.0) is not None,
            "@token 副行仍在（保存不改名 —— @ token 不能漂）")
    _close_dialog()
    if not badge_ok:
        return False

    # 会话列表：折叠药丸「团队 3 ▸」。
    if not R.goto_messages_list():
        R.check("team-list-reachable", False, "回不到会话列表")
        return False
    pill = _row_has_team_pill(st["group_name"], 3)
    R.check("team-pill-visible", pill is not None,
            "本会话行上出现「团队 3 ▸」（team_folder_badge=团队 N；行内作用域）")
    if not pill:
        return False

    # 展开：点药丸（独立热区，不冒泡到整行）→ 标签行 + 成员行。
    R.tap_node(pill)
    time.sleep(1.0)
    nodes = R.ui_all()
    texts = R.ui_texts(nodes)
    label_ok = ("@" + lead) in texts
    members_ok = (("%d %s" % (3, MEMBERS_SUFFIX)) in texts) and all(n in texts for (n, _r) in PRESET_MEMBERS)
    R.check("team-expanded-label", label_ok, "展开态标签行 @%s" % lead)
    R.check("team-expanded-members", members_ok,
            "展开态 3 名组员（coder/reviewer/tester + 角色）可见：%s"
            % [t for t in texts if t in ("coder", "reviewer", "tester", "编码", "审查", "测试")])
    if not members_ok:
        return False

    # 点成员行 coder：进群会话 + 草稿预填 "@<leader> "。
    R.tap_text("coder", timeout=4.0)
    time.sleep(1.2)
    nodes = R.ui_all()
    composer_ok = any(t.startswith("@" + lead) for t in R.ui_texts(nodes))
    R.check("team-member-click-prefills-leader", composer_ok,
            "点成员行后输入框预填 @%s（leader token，不是组员名）" % lead)
    # 回列表，收起展开态（给 T3/T4 一个干净的折叠面）。
    if not R.goto_messages_list():
        return False
    _pill = _row_has_team_pill(st["group_name"], 3)
    if _pill:
        R.tap_node(_pill)
    else:
        R.press_back()
    time.sleep(0.8)
    return True


def _overbudget_phase(args, st):
    """T3：越预算（9 名组员）→ 手机整帧拒收：rev 不动、界面无变化、不崩。"""
    phase = R.log
    phase("T3 越预算帧整帧拒收（9 组员 > TEAM_MEMBERS_MAX=8）")
    lead = st["lead"]
    ws_id = st["ws_id"]
    rev = int(st["rev"]) + 1
    roster = [{"agentId": "m-%d" % i, "name": "m%02d" % i, "role": "r"} for i in range(TEAM_MEMBERS_MAX + 1)]
    assert len(roster) > TEAM_MEMBERS_MAX
    acked = _send_info(args, st, rev, [_seat("team", roster, st["node_a"], lead, ws_id)])
    R.check("team-overbudget-no-ack", not acked,
            "越预算帧被收端 validateGroup 整帧拒收 ⇒ 永远等不到回执 —— "
            "发送侧「一切正常」而接收侧什么都没有的那类静默，判定靠下面两条 UI 无变化")
    time.sleep(1.5)
    # 界面判据：组员清单仍是预设的 3 名，第 9 个名字不出现；群没崩。
    if not _open_group_info(args, st):
        return False
    still = R.find_ui(text=INFO_BADGE_FMT % 3, timeout=G._budget(args, 6)) is not None
    R.check("team-overbudget-rejected", still,
            "越预算帧被拒后徽标仍是「团队 (3)」（rev 未前进，界面无变化）")
    intruder = R.find_ui(text="m08", timeout=2.0)
    R.check("team-overbudget-no-intruder", intruder is None,
            "第 9 名组员（m08）没有出现在界面上 —— 不是 mapNotNull 静默丢条目，是整帧拒收")
    _close_dialog()
    return still


def _close_phase(args, st):
    """T4：关闭团队（single + 空组员）→ 徽标与药丸消失。"""
    phase = R.log
    phase("T4 关闭团队（同一写路径反向）")
    lead = st["lead"]
    ws_id = st["ws_id"]
    rev = int(st["rev"]) + 1
    acked = _send_info(args, st, rev, [_seat("single", [], st["node_a"], lead, ws_id)])
    R.check("team-close-acked", bool(acked), "ACK")
    if not acked:
        return False
    st["rev"] = rev
    if not _open_group_info(args, st):
        return False
    gone = R.find_ui(text=INFO_BADGE_FMT % 3, timeout=G._budget(args, 6)) is None
    token_still = R.find_ui(text="@" + lead, timeout=2.0) is not None
    R.check("team-info-badge-gone", gone, "关闭后徽标消失")
    R.check("team-info-token-survives-close", token_still,
            "@ 仍然指向该 agent（方案 §2.2 的确认文案口径）")
    _close_dialog()
    if not R.goto_messages_list():
        return False
    pill_gone = _row_has_team_pill(st["group_name"], 3) is None
    R.check("team-pill-gone", pill_gone,
            "本会话行的折叠药丸消失（行内作用域；别的群的药丸不算）")
    return gone and pill_gone


def _read_link_diag():
    """读手机的 diag/link.json（run-as debug 包；读不到返回 (False, 原文)）。"""
    out = R.adb("exec-out", "run-as", R.PKG, "cat", "files/relay/diag/link.json")
    try:
        return True, json.loads(out)
    except Exception as exc:
        return False, "%s（%r）" % (exc, (out or "")[:120])


def _regression_phase(args, st):
    """回归：群行还在、群名没被改、closeCount 没新增。"""
    phase = R.log
    phase("回归：群行 / 群名 / 链路")
    row = G._conv_row(st["group_name"])
    R.check("team-regression-row-visible", row is not None,
            "群会话行仍在（列表回归）")
    R.check("team-regression-name-untouched",
            R.find_ui(text=st["group_name"], timeout=2.0) is not None,
            "群名未被本次任何 rev 改动")
    # closeCount：本夹具节点的链路读数（run-as 直读 diag/link.json；无 root 也能读）。
    ok, detail = _read_link_diag()
    if ok:
        close_total = sum(int(p.get("closeCount") or 0) for p in detail.get("peers", []))
        R.check("team-link-close-count-zero", close_total == 0,
                "closeCount 合计=%s（换网/断网/冷启动全程应为 0）" % close_total)
    else:
        R.check("team-link-close-count-zero", False, "diag/link.json 读不到：%s" % detail)


def _handshake(args, st):
    """单对端握手（成员主权只需要一台"电脑"）。"""
    if not args.skip_ui:
        R.log("设备与环境")
        R.require_device()
        pid = R.app_pid()
        if not pid:
            R.ensure_app_foreground()
            pid = R.app_pid()
        R.check("app-foreground-started", bool(pid) and int(pid) > 0, "pid=%s" % pid)

    R.log("起对端（UDP×1 + TCP×1）与握手")
    tcp_a = R.TcpFrameServer(port=args.tcp_port, name="tcp-a").start()
    peer_a = R.UdpPeer(args.phone_ip, args.phone_udp, nick=st["nick_a"], tcp_port=args.tcp_port,
                       host=args.host_ip, node_id=st["node_a"]).start()
    st["peer_a"] = peer_a
    st["udp_links"] = [("udpA", peer_a)]
    st["tcp_links"] = [("tcpA", tcp_a)]
    st["tcp"] = [tcp_a]
    ok = peer_a.handshake(secs=G._budget(args, 15))
    R.check("handshake-alive", ok, "对端 A 的 probe entry 拿到 alive")
    if not ok:
        return False
    prof = peer_a.phone_profile or {}
    st["phone_id"] = prof.get("nodeId") or ""
    R.log("手机 nodeId=%s tcpPort=%s" % (st["phone_id"][:8], prof.get("tcpPort")))
    R.check("handshake-phone-node-id", len(st["phone_id"]) > 0, "nodeId=%r" % st["phone_id"])
    return True


class _Fleet(object):
    """单对端的读数层（e2e_group.Fleet 的单节点版）。"""

    def __init__(self, st):
        self._st = st

    def frames(self, since=0.0):
        return G._dedup(G._inbox(self._st["udp_links"], self._st["tcp_links"], since=since))

    def wait(self, pred, timeout, interval=0.4):
        deadline = time.time() + float(timeout)
        last = None
        while True:
            last = pred(self.frames())
            if last:
                return last
            if time.time() >= deadline:
                return last or None
            time.sleep(interval)


def _body(args):
    marker = uuid.uuid4().hex[:6]
    st = {
        "group_name": R.uniq_marker("grp"),
        "nick_a": R.uniq_marker("nA"),
        "node_a": "fixA" + uuid.uuid4().hex[:10],
        "lead": LEAD_TOKEN_FMT % marker,
        "ws_id": WS_ID_FMT % marker,
        "group_id": "",
        "rev": 0,
        "meta": {},
        "phone_id": "",
        "fleet": None,
    }
    R.log("本轮标记：群名 %r、领队 token %r、wsId %r" % (st["group_name"], st["lead"], st["ws_id"]))
    for name, why in ROWS_NOT_COVERED:
        R.log("不覆盖 %s：%s" % (name, why))

    if not _handshake(args, st):
        return 1
    st["fleet"] = _Fleet(st)
    heartbeat = G._PeerHeartbeat([(st["udp_links"][0][0], st["peer_a"])]).start()
    try:
        # 建群：手机是群主，对端 A 是普通成员 —— 主权分支的舞台。
        if not G._create_group_ui(args, st):
            return 1
        G._start_heartbeat(st)
        if not _declared_phase(args, st):
            return 1
        if not _team_phase(args, st):
            return 1
        if not _overbudget_phase(args, st):
            return 1
        if not _close_phase(args, st):
            return 1
        _regression_phase(args, st)
    finally:
        heartbeat.stop()
    return None


ROWS_NOT_COVERED = (
    ("桌面端「团队编制」按钮的 UI 动作",
     "桌面 UI 自动化被 macOS TCC 挡住（osascript/screencapture 需权限）；桌面写路径由"
     "TeamDeclarationWritePathTest（含双节点端到端）与合并树全量绿覆盖，装机产物已按"
     "ACCEPTANCE §1b 核验新符号与资源"),
    ("成员宿主声明被旧版本拒收的对照",
     "需要一台旧构建对端 —— 兼容性窗口已在 PROTOCOL-EXTENSION §14.5 记录为已知限制"),
    ("多席位切换与 >8 人不可加的输入侧拦截",
     "输入侧由 UI 校验拦（TeamRosterDialogFormTest）；真机侧本夹具只验收端整帧拒收"),
)


def run(args):
    node_a = "fixA" + uuid.uuid4().hex[:10]
    R.log("本地自检：node=%s" % node_a[:12])
    _local_self_checks(args, node_a)
    if args.dry_run:
        print("\n[dry-run] 不碰网络、不碰设备：只跑了本地自检，按规范**不打 result 行**", flush=True)
        print("[dry-run] 真机跑法：scripts/relay-liveness.sh --quiet && python3 %s --secs 420"
              % os.path.basename(__file__), flush=True)
        print("[dry-run] 真机将执行：握手单对端 → 界面建群 → 成员自声明席位（主权分支）→ "
              "升团队（徽标/药丸/展开/预填）→ 越预算整帧拒收 → 关闭恢复单兵 → 回归读数", flush=True)
        return 0
    return _body(args)


def extra_args(ap):
    ap.add_argument("--tcp-port", type=int, default=R.TCP_PORT_DEFAULT,
                    help="对端 A 的 TCP 监听端口（写进 profile.tcpPort），默认 %(default)s")
    ap.add_argument("--skip-ui", action="store_true",
                    help="只跑协议面（不用界面、不用 adb）—— 本夹具的主判据全在 UI 上，"
                         "这个开关只为保持与其它夹具同形的用法契约，实际会失败")


def main():
    sys.exit(R.main_wrapper(run, description=__doc__, extra_args=extra_args))


if __name__ == "__main__":
    main()
