#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""R67–R79 群聊夹具（e2e_group.py）。

夹具扮演**一台电脑**——必要时两台：A 是建群时就选中的成员，B 是后来被「邀请新成员」
拉进来的那个。自己这边跑两个 UDP 对端 + 两个 TCP 帧服务端（两台"电脑"各有自己的
tcpPort，避免手机按 ip:port 把两台合成一台），然后：

  1. 本地自检（`--dry-run` 也跑，不碰网络、不碰设备）；
  2. 用 uiautomator dump 出来的 **bounds** 驱动界面建群 → 断言手机发出的 `group{op:"info"}`；
  3. 读会话列表与群会话页：群名、`x/y 人在线`、`n 位成员`、群信息入口、**没有**「戳一碰」，
     并拿单聊做对照（那一拍才是"群有什么不一样"的判据）；
  4. 收/发 `msg{kind:"group-text"}`：入站严格白名单、发言人署名、逐成员单播扇出
     （N 个成员 N 条帧、**同一个信封 id**）；
  5. `group{op:"need"}` 的触发与**防往返风暴**（GroupNeedTracker：被拒过的 rev 不再追问）；
  6. 用入站 `info` 改群名、用界面邀请成员/移出成员/把自己移出，断言通知行与读数。

跑法：
    python3 scripts/e2e/e2e_group.py --dry-run                  # 不碰设备、不碰网络，退出码 0
    scripts/relay-liveness.sh --quiet && python3 scripts/e2e/e2e_group.py --secs 420
    python3 scripts/e2e/e2e_group.py --skip-ui --secs 120       # 只跑协议面（不用界面、不用 adb）

退出码：0=PASS，1=FAIL（`result FAIL <n> failed=<名字,…>`），2=用法或环境错误。
覆盖矩阵：`docs/plans/2026-10-09-relay-e2e-coverage-matrix.md` §2 G 段 R67–R79。
**不覆盖**的几行连同原因见 [ROWS_NOT_COVERED]，每次运行都会打印出来。
"""

import json
import os
import re
import sys
import threading
import time
import uuid

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import relay_e2e as R  # noqa: E402

# ---------------------------------------------------------------------------
# 常量 —— 逐条抄自实现，改一个就会静默丢帧
# ---------------------------------------------------------------------------

PHONE_LONG_MIN_BYTES = 1200
FIXTURE_LONG_LEN = 1400

# Codec.kt:73-77 `GROUP_TEXT_KEYS`：group-text 的**严格**字段白名单，多一个键整帧丢弃
# （Codec.kt:343-347 `hasOnlyKeys`；金样本向量 neg-group-text-extra-field 就是这条）。
GROUP_TEXT_KEYS = frozenset(
    ("kind", "text", "groupId", "groupRev", "mentions", "resend", "replyTo"))
# Codec.kt:578-583 `groupInfoPayload` 的线上形状 = {"op":…, "group":{…}}。
GROUP_INFO_KEYS = frozenset(("op", "group"))
# GroupMeta.kt:140-143 `GroupOps` 只有两个 op。协议里**没有** op:"update"。
GROUP_OPS = ("info", "need")
# Limits.kt:52 GROUP_NAME / Limits.kt:107 GROUP_MAX_MEMBERS。
GROUP_NAME_LIMIT = 32
GROUP_MAX_MEMBERS = 200
# Codec.validateGroup 要求的必备键（Codec.kt:244-299）。
GROUP_INFO_REQUIRED = ("groupId", "name", "members", "rev", "updatedBy", "updatedTs")

# 界面文案（composeResources/values/strings.xml）。夹具靠这些字找节点，改文案要一起改。
TXT_MORE_ACTION = "更多"          # conv_more_action (:36)，IconButton 的 content-desc
TXT_NEW_GROUP = "建群"            # conv_new_group (:44 附近 / ConversationsScreen.kt:208 菜单项)
TXT_GROUP_NAME = "群名"           # conv_group_name (:44)
TXT_MEMBERS_HINT = "选成员（自己一定在里面）"   # conv_group_members_hint (:45)
TXT_CREATE = "创建"               # action_create (:47)
TXT_CONFIRM = "确定"              # common_confirm (:17)
TXT_CANCEL = "取消"               # common_cancel
TXT_CLOSE = "关闭"                # common_close (:19)
TXT_INFO_TITLE = "群信息"          # chat_group_info_title (:114)，content-desc
TXT_INFO_SUB = "成员与权限由群主与管理员管理"    # chat_group_info_sub (:115)
TXT_INFO_HINT = "群元数据只在成员之间同步"      # chat_group_info_hint (:122)
TXT_MEMBERS_HEADER = "成员（"       # chat_group_members_header (:121)
TXT_ADD_MEMBER = "邀请新成员"        # chat_group_add_member (:123)
TXT_ADD_TITLE = "邀请成员加入群聊"    # chat_group_add_title (:125)
TXT_REMOVE_MEMBER = "移出"          # chat_group_remove_member (:124)
TXT_REMOVE_CONFIRM = "确认将该成员移出群聊？"   # chat_group_confirm_remove (:127)
TXT_EDIT_META = "编辑群公告与简介"    # chat_group_edit_meta (:612)
TXT_NOTICE_RENAMED = "群名改了"      # chat_group_renamed
TXT_NOTICE_MEMBERS = "群成员有变化"   # chat_group_members_changed
TXT_NOTICE_REMOVED = "你已被移出这个群"  # chat_group_removed
TXT_MENTION_ALL = "@所有人"         # chat_mention_all 的菜单项（MessageComposer.kt:333）
TXT_NUDGE = "戳一碰"               # chat_nudge，单聊有、群聊没有（ChatScreen.kt:367-370）
TXT_GROUP_ID_LABEL = "groupId"     # chat_group_id_label (:120)

# 与 relay_e2e.py `adb_input_text` 同一份字符集（逐键 keyevent 能可靠注入的范围）。
ADB_SAFE_RE = re.compile(r"^[A-Za-z0-9._@%+=:/-]+$")
# RelayController.kt:770 MENTION_ALL —— mentions[] 里代表"所有人"的那个值。
MENTION_ALL = "@all"

LIMIT_ONLINE = "人在线"

# 每次运行都打印一遍，免得有人从 result 行推断覆盖面。
ROWS_NOT_COVERED = (
    ("R75 群图片 10MB 上限",
     "协议列是 `file-ctl` 带 groupId/groupRev，要造一份 >10MB 的真实图片并走 offer→chunk→ack 全链；"
     "那是 e2e_files.py 的地盘（`TransferAutoAcceptTest` 已覆盖判定本身），本夹具只碰 group/group-text。"),
    ("R76 群文件发送（逐成员扇出）",
     "同上：需要 file-ctl 的传输状态机与真实文件；本夹具不发文件。"),
    ("R78 群角色矩阵 / RBAC（gr1）",
     "能力未声明（`RelaySettings.caps()` 没有 gr1 分支），GroupMerge.kt:35-51 明说只实现"
     "「只有群主/管理员能改群、普通成员只能只增不减地邀请」这一句话子集 ⇒ 没有可断言的用户可见入口。"),
    ("R79 自定义群头像（av1）",
     "`caps()` 里没有 av1（矩阵 §2 R79 自己也这么写），avatar 信封无入口 ⇒ 「功能面完全没有任何覆盖」"
     "不是夹具补得上的。"),
    ("R73 的一半：改群名",
     "界面上没有改群名的入口：GroupInfoDialog 只能编辑简介/公告（:214），"
     "RelayControllerGroups.kt:154-199 的 handleUpdateGroupMeta **不碰 group.name** ⇒ "
     "改名只有接收侧，本夹具用入站 info 覆盖；矩阵写的 `group{op:update}` 在协议里不存在"
     "（GroupMeta.kt:140-143 只有 info/need）。"),
    ("R77 的一半：「有人@我」的通知闸门与 @ 高亮",
     "通知判据在 NotificationPolicy.kt:44-52（notifyMentionOnly + mentionsSelf），真机确定性验它要"
     "dumpsys notification + 改设置；界面侧**没有** @ 高亮（MessageUi 没有 mentioned 字段，"
     "全仓 grep mentioned 只命中 NotificationPolicy / RelayControllerMessages:274 / RelayControllerPk:108）。"
     "本夹具覆盖：@ 菜单入口、草稿插入 @all、带 mentions 的入站 group-text 不被白名单丢掉。"),
)

# 只在界面上读得到的断言（--skip-ui 时整批跳过，会在 [EVID] 里点名）。
UI_CHECKS = (
    "group-create-list-reachable/…（R67 建群整拍）",
    "group-conv-row-visible/…（R68 会话列表读数）",
    "group-header-…/group-composer-no-nudge（R68/R70 群会话页读数）",
    "group-text-bubble-rendered/…（R69/R70 入站渲染）",
    "group-info-…/group-add-…/group-remove-…（R71/R72/R74 界面那几拍）",
    "mention-menu-opens-on-at/…（R77 @ 入口）",
)


# ---------------------------------------------------------------------------
# 输出与读数
# ---------------------------------------------------------------------------

def phase(title):
    print("\n== %s ==" % title, flush=True)


def _budget(args, cap):
    """等待窗口的上界：既不超过夹具自己的 cap，也不超过 --secs。"""
    return max(1.0, min(float(cap), float(args.secs)))


def _skip(names, why):
    """跳过的断言**不计入** result 的 n —— 所以必须在这里点名，否则等于悄悄放过去。"""
    R.log("跳过 %d 组断言（%s）：%s" % (len(names), why, "；".join(names)))


def _log_screen(title, nodes=None):
    nodes = nodes if nodes is not None else R.ui_all()
    R.log("%s：%s" % (title, " | ".join(R.ui_texts(nodes))[:900]))
    return nodes


def _since(frames, ts):
    return [f for f in frames if f["ts"] >= ts]


def _inbox(udp_links, tcp_links, since=0.0):
    """两个 UDP 对端 + 两个 TCP 服务端的收件箱合成一条时间线（按收到时间排序）。

    TCP 侧的信封在 `obj["envelope"]` 里（relay_e2e.py `TcpFrameServer._serve` 的帧形状），
    UDP 侧本身就是信封。手机对**小载荷**走 UDP（`Messenger.sendUserMessage` 的 1200 字节
    判据），群 info 与短 group-text 都是小载荷 ⇒ 只盯 TCP 会一条群帧都看不到。
    """
    out = []
    for tag, peer in udp_links:
        for ts, _addr, env in peer.frames(since=since):
            out.append({"ts": ts, "via": tag, "env": env})
    for tag, srv in tcp_links:
        with srv._lock:
            rows = list(srv.frames_in)
        for ts, _addr, obj in rows:
            if ts < since or not isinstance(obj, dict):
                continue
            env = obj.get("envelope")
            if isinstance(env, dict):
                out.append({"ts": ts, "via": tag, "env": env})
    out.sort(key=lambda f: f["ts"])
    return out


def _dedup(frames):
    """按 (链路, 信封 id) 去重。

    手机没等到 ACK 会按 `Timings.ackRetrySchedule` 重传**同一个信封**（id 不变），
    不去重就会把"重传三次"读成"发了三条"。
    """
    seen = set()
    out = []
    for f in frames:
        key = (f["via"], f["env"].get("id"))
        if key in seen:
            continue
        seen.add(key)
        out.append(f)
    return out


def _payload(f):
    pl = f["env"].get("payload")
    return pl if isinstance(pl, dict) else {}


def _select(frames, mtype=None, kind=None, sender=None, group_id=None, text=None):
    out = []
    for f in frames:
        env = f["env"]
        if mtype is not None and env.get("type") != mtype:
            continue
        pl = _payload(f)
        if kind is not None and pl.get("kind") != kind:
            continue
        if sender is not None and env.get("from") != sender:
            continue
        if group_id is not None and pl.get("groupId") != group_id:
            continue
        if text is not None and pl.get("text") != text:
            continue
        out.append(f)
    return out


def _group_frames(frames, op=None, group_id=None, sender=None):
    """`group` 帧（信封 type == "group"），按 op / groupId / 发送者过滤。

    `payload.group` 是整份 GroupMeta 快照；`payload.groupId` 只出现在 op:"need" 上。
    """
    out = []
    for f in _select(frames, mtype=R.T_GROUP, sender=sender):
        pl = _payload(f)
        if op is not None and pl.get("op") != op:
            continue
        g = pl.get("group")
        if op == "info":
            if not isinstance(g, dict):
                continue
            if group_id is not None and g.get("groupId") != group_id:
                continue
        elif group_id is not None and pl.get("groupId") != group_id:
            continue
        out.append(f)
    return out


def _meta_of(f):
    return _payload(f).get("group") or {}


def _ratio(online, total):
    """`chat_group_online` 的渲染值：`%1$d/%2$d 人在线`。

    分母是成员总数，分子是 **`nodeId != self` 且 online 的成员数** ——
    RelayUiState.memberOnlineCount(:781-782) 刻意不数自己。
    """
    return "%d/%d %s" % (online, total, LIMIT_ONLINE)


def _peer_of(via):
    """链路标签 → 对端名（"udpA"/"tcpB" → "A"/"B"）。"""
    return via[-1]


# ---------------------------------------------------------------------------
# 界面：找节点、点它、验效果
# ---------------------------------------------------------------------------

def _cands_by_label(nodes, label, desc=False):
    if desc:
        return [n for n in nodes if n.desc == label]
    return [n for n in nodes if n.text == label]


def _pick_button(nodes, label, desc=False):
    """找按钮节点：优先 clickable 的候选，否则找**盖住那段文字、面积最小**的可点节点。

    Compose 里 `Modifier.clickable` 会 merge descendants，按钮的 enabled 状态落在
    clickable 那个节点上；里面的 Text 子节点在 dump 里**恒为 `enabled=true`**。
    2026-10-09 真机：建群对话框群名还没填时，`创建` 的文字节点 `enabled=True`，
    而产品侧 `GroupCreateDialog.kt:76 val canConfirm = name.isNotBlank() && chosen.isNotEmpty()`、
    `:144 enabled = canConfirm` 本该是灰的 —— 原来的「clickable 优先、否则取第一个」
    于是把灰的读成亮的（全量套件 run2 的 `group-create-confirm-disabled-before-input`）。
    所以没有 clickable 候选时，取「矩形盖住该文字、面积最小」的可点节点当按钮本体，
    它的 `enabled` 才是真值。
    """
    cands = _cands_by_label(nodes, label, desc=desc)
    if not cands:
        return None
    for n in cands:
        if n.clickable:
            return n
    best = None
    for c in cands:
        if not c.bounds:
            continue
        for n in nodes:
            if not (n.clickable and n.bounds):
                continue
            if (n.bounds[0] <= c.bounds[0] and n.bounds[1] <= c.bounds[1]
                    and n.bounds[2] >= c.bounds[2] and n.bounds[3] >= c.bounds[3]):
                area = (n.bounds[2] - n.bounds[0]) * (n.bounds[3] - n.bounds[1])
                if best is None or area < best[0]:
                    best = (area, n)
    return best[1] if best else cands[0]


def _find(label, desc=False, timeout=4.0):
    """轮询找节点（界面渲染有延迟，不用 sleep 固定值）。"""
    deadline = time.time() + float(timeout)
    while True:
        node = _pick_button(R.ui_all(), label, desc=desc)
        if node:
            return node
        if time.time() >= deadline:
            return None
        time.sleep(0.5)


def _tap(label, desc=False, timeout=4.0):
    node = _find(label, desc=desc, timeout=timeout)
    if not node:
        R.log("找不到可点的节点：%r（desc=%s）" % (label, desc))
        return None
    R.tap_node(node)
    R.log("tap %r @%s（cls=%s clickable=%s enabled=%s）"
          % (label, node.center, node.cls.split(".")[-1], node.clickable, node.enabled))
    return node


def _dialog_band(nodes, title_labels, button_labels):
    """用对话框自己的标题与底部按钮夹出它的 (y_band, x_band)。

    x 带是关键：背景的会话列表行、聊天气泡署名都是同名的点击目标，而且**也是 clickable**，
    但它们从屏幕左边缘起排（列表 16dp、气泡 8dp），落不进对话框的内框 ⇒ x 带能把它们挡掉。
    """
    title = None
    for lab in title_labels:
        title = _pick_button(nodes, lab)
        if title:
            break
    button = None
    for lab in button_labels:
        button = _pick_button(nodes, lab)
        if button:
            break
    if title is None or button is None:
        return None, None, "锚点缺失：title=%r button=%r" % (title, button)
    y_band = (min(title.center[1], button.center[1]) - 8,
              max(title.center[1], button.center[1]) + 8)
    x_band = (min(title.bounds[0], button.bounds[0]) - 8,
              max(title.bounds[2], button.bounds[2]) + 8)
    return y_band, x_band, "y=%s x=%s" % (y_band, x_band)


def _dialog_row_candidates(nodes, label, title_labels, button_labels):
    """对话框里「昵称包含 label」的行，按可信度排序；返回 (候选, 明细)。

    昵称用**子串**匹配：Compose 把行的语义合并到 clickable 节点上时，多段文本会连成一条
    （"nA1234, 在线"），精确匹配会一条都找不到。候选先排 clickable 的 —— 对话框里的成员行
    是 clickable 的。即便如此仍有歧义（dump 可能同时包含对话框与背后的窗口，而背后的
    会话列表行既 clickable 又同文本），所以调用方一律**点一个、验收一次**。
    """
    y_band, x_band, why = _dialog_band(nodes, title_labels, button_labels)
    if y_band is None:
        return [], why
    cands = [n for n in nodes
             if (label in n.text or label in n.desc)
             and y_band[0] <= n.center[1] <= y_band[1]
             and x_band[0] <= n.center[0] <= x_band[1]]
    cands.sort(key=lambda n: (0 if n.clickable else 1, -n.center[1]))
    detail = "%s 候选=%s" % (
        why, ["%s%r@%s" % ("●" if n.clickable else "○", n.text[:18], n.center) for n in cands] or "无")
    return cands, detail


def _enabled(label, desc=False):
    """按钮亮着没有。复选框的勾选态在 dump 里读不到（UiNode 没有 checked 属性），
    但按钮的 enabled 读得到 —— 它就是 `canConfirm` 的判据。"""
    node = _pick_button(R.ui_all(), label, desc=desc)
    return bool(node and node.enabled)


def _tap_row_until(row_label, verify, title_labels, button_labels, timeout=12.0, tries=4):
    """点对话框里的一行，点完用 verify() 验收；不对就换下一个候选。"""
    deadline = time.time() + float(timeout)
    tried = []
    detail = ""
    for _ in range(tries):
        if time.time() >= deadline:
            break
        cands, detail = _dialog_row_candidates(R.ui_all(), row_label, title_labels, button_labels)
        R.log("对话框行候选 %r：%s" % (row_label, detail))
        cands = [c for c in cands if c.center not in tried]
        if not cands:
            return False, tried, detail
        node = cands[0]
        tried.append(node.center)
        R.log("tap 行 %r @%s（第 %d 个候选）" % (row_label, node.center, len(tried)))
        R.tap_node(node)
        time.sleep(1.0)
        if verify():
            return True, node.center, ""
        time.sleep(0.4)
    return False, tried, detail


def _tap_until(label, verify, timeout=8.0, y_band=None, x_band=None, order="bottom",
               tries=3, desc=False):
    """点一个（可能重名的）节点，直到 [verify] 为真；多候选时逐个试。

    同一文本在屏上出现两次是有真实场景的：群信息对话框里**每个**成员行都有一个「移出」，
    而"确认移出"对话框里也有一个「移出」，两者同时在树里。群信息的成员按 `group.members`
    顺序渲染（手机建群 = [自己] + 选中的成员，邀请只是往后追加），自己那行没有「移出」
    （GroupInfoDialog.kt:309-318）⇒ order="top" 拿到的就是第一个非自己的成员。与其赌，
    不如点一个、验收一次、不对再点下一个。返回 (ok, 点过的坐标, 说明)。
    """
    deadline = time.time() + float(timeout)
    tried = []
    for _ in range(tries):
        if time.time() >= deadline:
            break
        cands = _cands_by_label(R.ui_all(), label, desc=desc)
        if y_band:
            cands = [n for n in cands if y_band[0] <= n.center[1] <= y_band[1]]
        if x_band:
            cands = [n for n in cands if x_band[0] <= n.center[0] <= x_band[1]]
        if order == "top":
            cands.sort(key=lambda n: n.center[1])
        else:
            cands.sort(key=lambda n: -n.center[1])
        cands = [c for c in cands if c.center not in tried]
        if not cands:
            return False, tried, "带内没有 %r 的候选（y=%s x=%s）" % (label, y_band, x_band)
        node = cands[0]
        tried.append(node.center)
        R.log("tap %r @%s（第 %d 个候选）" % (label, node.center, len(tried)))
        R.tap_node(node)
        time.sleep(1.0)
        if verify():
            return True, tried, ""
        time.sleep(0.5)
    return False, tried, "点了 %d 个候选都没生效" % len(tried)


def _pick_field(nodes, anchor_labels):
    """选对话框里那个 EditText：离对话框独有锚点最近的那个。

    为什么不用 `R.chat_field_text()`：它拿的是 dump 里**第一个** EditText，而对话框
    弹在会话页上时背景里可能还有 composer —— 谁先谁后由 dump 顺序决定，不是判据。
    """
    anchor = None
    for lab in anchor_labels:
        anchor = _pick_button(nodes, lab)
        if anchor:
            break
    fields = [n for n in nodes if "EditText" in n.cls]
    if anchor is None or not fields:
        return None, "EditText=%d 锚点=%s" % (len(fields), anchor)
    field = min(fields, key=lambda n: abs(n.center[1] - anchor.center[1]))
    return field, "EditText=%d 锚点%r@y=%d 选中@%s" % (len(fields), anchor.text, anchor.center[1], field.center)


def _type_into_dialog_field(anchor_labels, text, tries=6):
    """把 text 注入对话框里的输入框，并**回读校验**（`input text` 会吞字符、还会吞连字符）。"""
    got = None
    detail = ""
    for attempt in range(1, tries + 1):
        field, detail = _pick_field(R.ui_all(), anchor_labels)
        R.log("对话框输入框（第 %d/%d 次）：%s" % (attempt, tries, detail))
        if field is None:
            return False, None, detail
        R.tap_node(field)
        time.sleep(0.5)
        field2, _d = _pick_field(R.ui_all(), anchor_labels)
        if field2 is not None and field2.text:
            R.clear_focused_text(len(field2.text) + 4)
            time.sleep(0.3)
        R.adb_input_text(text)
        time.sleep(0.6)
        field3, _d3 = _pick_field(R.ui_all(), anchor_labels)
        got = field3.text if field3 else None
        if got == text:
            return True, got, detail
        R.log("input text 吞字：想打 %r，框里是 %r（清空重打）" % (text, got))
    return False, got, "回读不一致：%r != %r" % (got, text)


# ---------------------------------------------------------------------------
# 本地自检（--dry-run 也跑：不碰网络、不碰设备）
# ---------------------------------------------------------------------------

def _local_self_checks(args, group_name, node_a):
    short_text = R.uniq_marker("g")
    long_text = R.long_text(FIXTURE_LONG_LEN, tail_marker=R.uniq_marker("z"))
    # relay_e2e 自己的 11 条（信封形状、文本上限、profile 长度、TCP 分帧、可靠帧集合）。
    R.local_self_checks(short_text, long_text, tcp_port=args.tcp_port, host=args.host_ip)

    # 群名要经 adb `input text` 打进对话框：只有 [A-Za-z0-9._@%+=:] 可靠（relay_e2e.py:558-575），
    # 而 handleCreateGroup 会把 > Limits.GROUP_NAME=32 的名字拒成 GroupNameTooLong
    # （RelayControllerGroups.kt:36-40）。
    name_ok = bool(ADB_SAFE_RE.match(group_name)) and len(group_name) <= GROUP_NAME_LIMIT
    R.check("group-name-adb-injectable", name_ok,
            "%r len=%d<=%d" % (group_name, len(group_name), GROUP_NAME_LIMIT))

    gid = "fixgrp" + uuid.uuid4().hex[:8]
    payload = {"kind": R.K_GROUP_TEXT, "text": "probe", "groupId": gid, "groupRev": 1}
    extra = sorted(set(payload) - GROUP_TEXT_KEYS)
    R.check("group-text-payload-within-whitelist", not extra,
            "keys=%s 白名单=%s（多一个键整帧丢弃：Codec.kt:73-77 + 343-347）"
            % (sorted(payload), sorted(GROUP_TEXT_KEYS)))

    meta = {"groupId": gid, "name": group_name, "members": ["selfNode", node_a], "rev": 1,
            "updatedBy": "selfNode", "updatedTs": R.now_ms(), "ownerId": "selfNode",
            "adminIds": []}
    missing = [k for k in GROUP_INFO_REQUIRED if k not in meta]
    paired = ("ownerId" in meta) == ("adminIds" in meta)
    shape_ok = (not missing and meta["rev"] >= 1 and meta["updatedTs"] > 0
                and bool(meta["members"]) and len(meta["members"]) <= GROUP_MAX_MEMBERS
                and meta["ownerId"] in meta["members"] and paired
                and len(meta["name"]) <= GROUP_NAME_LIMIT)
    R.check("group-info-payload-shape", shape_ok,
            "必备键缺=%s rev=%d ownerId∈members=%s ownerId/adminIds 成对=%s"
            % (missing or "无", meta["rev"], meta["ownerId"] in meta["members"], paired))

    # ConvId.groupIdOf 用 removePrefix：群 id 可以含冒号，任何键都不能 split(':')（ConvId.kt:24-33）。
    conv = "group:" + "a:b"
    R.check("group-conv-key-not-split", conv[len("group:"):] == "a:b" and conv.startswith("group:"),
            "%r → removePrefix → %r" % (conv, conv[len("group:"):]))

    env = R.envelope(R.T_MSG, payload, node_id=node_a)
    n = len(R.encode(env).encode("utf-8"))
    R.check("group-frame-fits-udp", n <= R.UDP_MAX_PAYLOAD,
            "%dB<=%dB ⇒ group/group-text 这种小载荷走 UDP 单播（Messenger.sendUserMessage）"
            % (n, R.UDP_MAX_PAYLOAD))


# ---------------------------------------------------------------------------
# 各拍
# ---------------------------------------------------------------------------

class _PeerHeartbeat(object):
    """周期性给对端发 `alive`，把手机侧的 `lastSeen` 续上。

    为什么**必须**要有它：手机给**离线**成员发群消息时一个字节都不发 ——
    `Messenger.sendAwaitAck`（`core/src/commonMain/kotlin/com/kapp/tidings/core/transport/Messenger.kt:219`）
    的循环第一句是 `if (!record.online) break` ⇒ `attempted=false` ⇒ `sendUserMessage` 直接
    `queueStore.enqueue(...)` 并把状态标成 `queued`（`:183-186`），等对端上线由
    `flushQueue(peerId)`（`:243`）补发。而 `Discovery` 每 `presenceInterval`（30s）扫一次、
    把 `now - lastSeen > offlineAfter`（**90 秒**，`Discovery.kt:138`）的节点判离线
    （`PeerRegistry.kt:155-159`）。discovery 夹具的 `AliveResponder`
    （`scripts/e2e/e2e_discovery.py:725`）是**回**手机的 `entry`，group 夹具里没有对应的东西
    ⇒ group run4 的扇出相位上 A 早被扫成离线（同轮 R72 群信息对话框现场 `0/2 人在线`、
    成员 A 显示「离线」），`group-send-fanout-recipients` 收到的成员=[] 就是这么来的。

    `alive` 帧在手机侧走 `Discovery.handleAlive` → `registry.touch(...)`（`Discovery.kt:196`），
    源地址不变、`profileRev` 不变 ⇒ 资料不换但 `lastSeen`/`online` 一定刷新
    （`PeerRegistry.kt:132-138`）。
    """

    def __init__(self, peers, interval=20.0):
        self._peers = list(peers)
        self._interval = float(interval)
        self._stop = threading.Event()
        self._thread = None

    def start(self):
        self._thread = threading.Thread(target=self._run, name="peer-heartbeat")
        self._thread.daemon = True
        self._thread.start()
        return self

    def _run(self):
        while not self._stop.wait(self._interval):
            for peer in self._peers:
                try:
                    peer.send_entry(alive=True)
                except Exception as exc:            # 对端已收摊（socket 关了）就直接退出
                    R.log("心跳发失败了（%s），线程退出" % exc)
                    return

    def stop(self):
        self._stop.set()


def _refresh_presence(st, wait=1.0):
    """发消息**之前**先把对端的 `lastSeen` 续上（离线对端一个字节都收不到，见 _PeerHeartbeat）。"""
    for _tag, peer in st.get("udp_links") or []:
        try:
            peer.send_entry(alive=True)
        except Exception as exc:
            R.log("续在线状态失败：%s" % exc)
        time.sleep(0.15)
    time.sleep(wait)


def _handshake(args, st):
    if not args.skip_ui:
        # --skip-ui 连 adb 都不碰：UDP 握手拿到的 profile 里就有手机的 nodeId，协议面够用。
        phase("设备与环境")
        R.require_device()
        pid = R.app_pid()
        R.log("com.kapp.tidings pid=%s" % pid)
        if not pid:
            R.log("应用没在跑，拉起 %s" % R.LAUNCH_ACTIVITY)
            R.ensure_app_foreground()
            pid = R.app_pid()
        R.check("app-foreground-started", bool(pid) and int(pid) > 0, "pid=%s" % pid)
        R.log("前台 activity=%s" % R.foreground_activity())

    phase("起对端（UDP×2 + TCP×2）与握手")
    tcp_a = R.TcpFrameServer(port=args.tcp_port, name="tcp-a").start()
    tcp_b = R.TcpFrameServer(port=args.tcp_port_b, name="tcp-b").start()
    peer_a = R.UdpPeer(args.phone_ip, args.phone_udp, nick=st["nick_a"], tcp_port=args.tcp_port,
                       host=args.host_ip, node_id=st["node_a"]).start()
    peer_b = R.UdpPeer(args.phone_ip, args.phone_udp, nick=st["nick_b"], tcp_port=args.tcp_port_b,
                       host=args.host_ip, node_id=st["node_b"]).start()
    st["peer_a"], st["peer_b"] = peer_a, peer_b
    st["udp_links"] = [("udpA", peer_a), ("udpB", peer_b)]
    st["tcp_links"] = [("tcpA", tcp_a), ("tcpB", tcp_b)]
    st["tcp"] = [tcp_a, tcp_b]

    ok_a = peer_a.handshake(secs=_budget(args, 15))
    R.check("handshake-alive", ok_a, "A 的 probe entry 拿到了 alive（不带 probeId 的 entry 会被 10s 去重窗口吞掉）")
    if not ok_a:
        return False
    prof = peer_a.phone_profile or {}
    st["phone_id"] = prof.get("nodeId") or ""
    R.log("手机 profile：nodeId=%s nick=%r tcpPort=%s platform=%s ver=%r caps=%s"
          % (st["phone_id"][:8], prof.get("nick"), prof.get("tcpPort"), prof.get("platform"),
             prof.get("ver"), prof.get("caps")))
    R.check("handshake-phone-text-cap", R.CAP_MOBILE_TEXT in (prof.get("caps") or []),
            "caps=%s" % (prof.get("caps"),))
    R.check("handshake-phone-tcp-port", int(prof.get("tcpPort") or 0) > 0,
            "tcpPort=%s（TCP 连这个，不是 UDP 17878）" % prof.get("tcpPort"))
    R.check("handshake-phone-node-id", len(st["phone_id"]) > 0, "nodeId=%r" % st["phone_id"])

    ok_b = peer_b.handshake(secs=_budget(args, 15))
    R.check("handshake-second-peer-alive", ok_b, "B 也要进节点表 —— 「邀请新成员」那一拍靠它")
    st["have_b"] = ok_b
    return True


def _wait_info(args, st, since, timeout=25.0, settle=1.0):
    """等手机发出的 `group{op:"info"}`（建群/加成员/移成员都是这条）。

    建群那一拍要**先**把帧收到、group_id 才知道，所以 group_id 为空时不过滤。

    `settle`：手机对**每个成员**单播同一个信封（`sendGroupInfo` 逐个发、id 不变），两份副本
    到达差几十毫秒，而 `fleet.wait` 一命中就返回 ⇒ 只看得见先到的那一份。run5 的
    `group-add-fanout-to-new-member` 读出 `via=['udpA']` 就是这么来的（产品侧
    `RelayControllerGroups.kt:97-101` 的 `recipients = updatedMeta.recipientsExcluding(self)`
    确实包含 B）。命中后再等一拍、把同一批帧重收一遍，才算看清"发给了谁"。
    """
    fleet = st["fleet"]
    gid = st.get("group_id") or None

    def pred(_frames=None):
        hits = _group_frames(fleet.frames(since=since), op="info",
                             group_id=gid, sender=st["phone_id"])
        return hits or None

    hits = fleet.wait(pred, timeout=_budget(args, timeout))
    if hits:
        time.sleep(settle)
        seen = {(f["via"], f["env"].get("id")) for f in hits}
        for f in _group_frames(fleet.frames(since=since), op="info",
                               group_id=gid, sender=st["phone_id"]):
            if (f["via"], f["env"].get("id")) not in seen:
                hits.append(f)
    return hits


def _create_group_ui(args, st):
    phase("R67 建群（群名 + 成员多选）")
    if not R.goto_messages_list():
        R.check("group-create-list-reachable", False, "回不到会话列表")
        return False
    R.check("group-create-list-reachable", True, "会话列表可见")
    # 「建群」是「更多」菜单里的**第二级**入口（ConversationsScreen.kt:193-215）：
    # 矩阵写的 CA:208 `conv_new_group` 就是那个菜单项，但得先点「更多」。
    node = _tap(TXT_MORE_ACTION, desc=True, timeout=4.0) or _tap(TXT_MORE_ACTION, timeout=2.0)
    if not node:
        R.check("group-create-menu-reachable", False, "找不到 content-desc/text=更多")
        return False
    time.sleep(0.6)
    if not _tap(TXT_NEW_GROUP, timeout=4.0):
        R.check("group-create-menu-reachable", False, "菜单里没有「建群」")
        return False
    R.check("group-create-menu-reachable", True, "更多 → 建群")
    time.sleep(0.8)

    nodes = R.ui_all()
    _log_screen("建群对话框", nodes)
    dialog_ok = _pick_button(nodes, TXT_MEMBERS_HINT) is not None
    R.check("group-create-dialog-open", dialog_ok, "锚点「%s」" % TXT_MEMBERS_HINT)
    if not dialog_ok:
        return False
    # canConfirm = name.isNotBlank() && chosen.isNotEmpty()（GroupCreateDialog.kt:76）
    create0 = _pick_button(nodes, TXT_CREATE)
    R.check("group-create-confirm-disabled-before-input",
            create0 is not None and not create0.enabled,
            "创建节点存在=%s enabled=%s" % (create0 is not None,
                                          create0.enabled if create0 else None))
    if create0 is None:
        return False

    ok, landed, detail = _type_into_dialog_field((TXT_GROUP_NAME, TXT_MEMBERS_HINT), st["group_name"])
    R.check("group-create-name-typed", ok, "%s 回读=%r" % (detail, landed))
    if not ok:
        return False
    create1 = _pick_button(R.ui_all(), TXT_CREATE)
    R.check("group-create-needs-member",
            create1 is not None and not create1.enabled,
            "只填了名字还不够（chosen 为空 ⇒ canConfirm 假）")

    # 勾选成员 = 点整行（GroupCreateDialog.kt:109-111 的 Row.clickable，复选框
    # onCheckedChange=null）。勾没勾上读不到（UiNode 没有 checked），所以用「创建」的
    # enabled 当判据；没收到效果的那次点击会去试下一个候选。
    ok, used, detail = _tap_row_until(st["nick_a"], lambda: _enabled(TXT_CREATE),
                                      (TXT_MEMBERS_HINT,), (TXT_CREATE, TXT_CANCEL))
    R.check("group-create-peer-row-located", ok, "点过 %s；%s" % (used, detail))
    if not ok:
        return False
    R.check("group-create-confirm-enabled-after-input", _enabled(TXT_CREATE),
            "名字 + 至少一个成员才亮（GroupCreateDialog.kt:76 canConfirm）")

    t0 = time.time()
    if not _tap(TXT_CREATE, timeout=3.0):
        R.check("group-create-tapped", False, "点不到「创建」")
        return False
    hits = _wait_info(args, st, t0)
    R.check("group-create-info-received", bool(hits), "等 group{op:info}")
    if not hits:
        return False
    meta = _meta_of(hits[0])
    st["meta"] = meta
    st["group_id"] = meta.get("groupId") or ""
    st["rev"] = int(meta.get("rev") or 0)
    R.log("建群 info：groupId=%s name=%r rev=%s members=%d ownerId=%s updatedBy=%s"
          % (st["group_id"], meta.get("name"), meta.get("rev"), len(meta.get("members") or []),
             meta.get("ownerId"), meta.get("updatedBy")))
    R.check("group-create-meta-name", meta.get("name") == st["group_name"],
            "name=%r 期望=%r" % (meta.get("name"), st["group_name"]))
    members = set(meta.get("members") or [])
    R.check("group-create-meta-members", members == {st["phone_id"], st["node_a"]},
            "members=%s（期望手机 + A）" % sorted(members))
    R.check("group-create-meta-first-rev", int(meta.get("rev") or 0) == 1, "rev=%s" % meta.get("rev"))
    R.check("group-create-meta-owner",
            meta.get("ownerId") == st["phone_id"] and meta.get("updatedBy") == st["phone_id"],
            "ownerId=%s updatedBy=%s（建群者是手机）" % (meta.get("ownerId"), meta.get("updatedBy")))
    R.check("group-create-meta-required-keys",
            all(k in meta for k in GROUP_INFO_REQUIRED),
            "缺=%s" % [k for k in GROUP_INFO_REQUIRED if k not in meta])
    R.log("本条 info 我们自动回了 ACK（RELIABLE_TYPES 含 group）：acked=%s"
          % (hits[0]["env"]["id"] in st["peer_a"].acked))
    return True


def _start_heartbeat(st, interval=20.0):
    """起对端心跳（见 `_PeerHeartbeat`）。

    起点放在**入站相位之后**：那个相位故意让 A 发 `exit` 离线、再用定向 `entry` 拉回来
    （`group-offline-numerator` / `group-reonline-numerator`），心跳掺进去会跟它的读数打架。
    之后的每一拍都要求成员**在线**（群消息只发给在线成员），所以从这里一直续到收尾。
    """
    hb = _PeerHeartbeat([p for _tag, p in st.get("udp_links") or []], interval=interval).start()
    st["heartbeat"] = hb
    R.log("已起对端心跳（每 %.0fs 一条 alive）：离线成员收不到群消息 —— "
          "Messenger.sendAwaitAck 对离线对端直接入队、一个字节都不发（Messenger.kt:219）" % interval)
    return hb


def _protocol_admission(args, st):
    """--skip-ui：不用界面，直接把手机「拉进群」（GroupMerge.decide 的 local==null 分支）。"""
    phase("R67（协议面）被拉进群：入站 info + selfId ∈ members ⇒ Apply")
    gid = "fixgrp" + uuid.uuid4().hex[:8]
    meta = {"groupId": gid, "name": st["group_name"], "rev": 1,
            "members": [st["phone_id"], st["node_a"], st["node_b"]],
            "updatedBy": st["phone_id"], "updatedTs": R.now_ms(),
            "creatorId": st["phone_id"], "creatorIp": "", "ownerId": st["phone_id"],
            "adminIds": []}
    st["group_id"] = gid
    st["rev"] = 1
    st["meta"] = meta
    env = st["peer_a"].env(R.T_GROUP, {"op": "info", "group": meta})
    st["peer_a"].send(env)
    t0 = time.time()
    acked = st["peer_a"].wait_ack(env["id"], _budget(args, 8))
    R.log("被拉进群这条 info 被 ACK 了=%s（ACK 是无条件的，只证明帧到了手机）" % acked)
    hits = _group_frames(st["fleet"].frames(since=t0), op="need", sender=st["phone_id"])
    R.check("group-admission-no-need", not hits,
            "手机本地没有这个群时应当**收下**（local==null 且 selfId∈members ⇒ Apply），不该回 need")
    return True


def _conv_row(title, scrolls=20):
    """在会话列表里定位标题为 `title` 的那一行节点（先滑回顶部，再往下找）。

    2026-10-10 的真机现场：会话列表会**记住上次滚动位置**，而新建的群按 ts 排在最前 ——
    上一轮夹具 `scroll_to_text`（只会往下滑）把列表留在半中间时，新群正好在视口**上方**，
    只往下找永远找不到。run2 的 `group-chat-opens`、`group-conv-online-ratio`、
    `group-offline/reonline-numerator` 就是这么红的（探针 `/tmp/probe_convlist4.log`：
    滑回顶部后 `grpd290e5` 就在第一屏）。
    """
    R.goto_messages_list()
    # 2026-10-10：先在当前视口里找（1s 短探测），找不到才回顶重找 —— 回顶要 12–34s，
    # 而 `_poll_row` 每 0.6s 就调一次本函数，无条件回顶会把轮询预算全吃掉。
    node = R.find_ui(text=title, exact=True, timeout=1.0)
    if node is None:
        R.scroll_list_to_top()
        node = R.find_ui(text=title, exact=True, timeout=1.0)
    if node is None:
        node = R.scroll_to_text(title, max_swipes=scrolls, timeout=2.5)
    return node


def _row_has(row, label, dy=110):
    """`label` 是否落在 `row` 这一行里（按 y 距离判定）。

    为什么不能整屏 `ui_has`：会话列表上方有通知横幅（「『grpd290e5』建好了，正在通知 1 位成员。」），
    它会把**群名**和比例类判据一起咬成假绿（run2 的 `group-conv-row-visible` 就是这么过的）。
    """
    if row is None or not row.center:
        return False
    for n in R.ui_all():
        t = (n.text or "").strip()
        if t != label or not n.center:
            continue
        if abs(n.center[1] - row.center[1]) <= dy:
            return True
    return False


def _poll_row(title, predicate, secs):
    """轮询「那一行是否满足 predicate」，命中返回该行节点，超时返回 None。"""
    t0 = time.time()
    while time.time() - t0 < secs:
        row = _conv_row(title)
        if row is not None and predicate(row):
            return row
        time.sleep(0.6)
    return None


_MEMBER_COUNT_RE = re.compile(r"^\s*(\d+)\s*位成员\s*$")


def _member_counts(nodes=None):
    """屏上「n 位成员」这种**独立成行**的文本（`ChatHeader` 的第二行）。

    不能整屏 `ui_has("位成员")`：应用级通知横幅「『grp19fcdb』建好了，正在通知 1 位成员。」
    在**单聊页上也浮着**（2026-10-10 group run4 现场：`direct-header-not-group` 被它咬红，
    而单聊页其实没有成员数）。整行匹配把横幅排除掉，同时比 `ui_has("2 位成员")` 精确
    （后者会被任意正文里的同串命中）。
    """
    out = []
    for t in R.ui_texts(nodes):
        m = _MEMBER_COUNT_RE.match(t or "")
        if m:
            out.append(int(m.group(1)))
    return out


def _list_reading(args, st):
    phase("R68 群会话归位 group:<id>（会话列表读数）")
    if not R.goto_messages_list():
        R.check("group-conv-list-reachable", False, "回不到会话列表")
        return
    row = _conv_row(st["group_name"])
    nodes = R.ui_all()
    _log_screen("会话列表", nodes)
    texts = R.ui_texts(nodes)
    R.check("group-conv-row-visible", row is not None,
            "列表里有群名 %r 的那一行（可见：%s）" % (st["group_name"], R.visible_texts(nodes)))
    R.check("group-conv-online-ratio", _row_has(row, _ratio(1, 2)),
            "那一行里有 %r（分子不含自己：RelayUiState.memberOnlineCount）" % _ratio(1, 2))
    R.check("group-conv-no-raw-conv-key",
            not any(t.startswith("group:") for t in texts),
            "屏上不该出现裸的 group:<id>（ConvId.groupIdOf 那次 bug 就是把群键当节点 id 查）")

    # A 装死：`exit` 是让手机把对端翻成离线**最快**的一条路（不用等 90s 存在性超时）。
    st["peer_a"].send_exit()
    off = _poll_row(st["group_name"], lambda r: _row_has(r, _ratio(0, 2)), _budget(args, 8))
    R.check("group-offline-numerator", off is not None,
            "A 发 exit 后那一行的分子应落到 0（%r）" % _ratio(0, 2))
    st["peer_a"].send_entry(probe=True)
    back = _poll_row(st["group_name"], lambda r: _row_has(r, _ratio(1, 2)), _budget(args, 10))
    R.check("group-reonline-numerator", back is not None,
            "A 再 probe entry 后那一行回到 %r" % _ratio(1, 2))


def _chat_page_reading(args, st):
    phase("R68/R70 群会话页读数 + 群特有的界面差异")
    ok = R.open_chat(st["group_name"])
    R.check("group-chat-opens", ok, "open_chat(%r)" % st["group_name"])
    if not ok:
        return
    nodes = _log_screen("群会话页", R.ui_all())
    R.check("group-header-member-count", 2 in _member_counts(nodes),
            "ChatScreen.kt:683-684（整行匹配「n 位成员」，横幅里的「…通知 1 位成员。」不算）")
    R.check("group-header-online-ratio", R.ui_has(_ratio(1, 2), nodes), "ChatScreen.kt:671-679")
    R.check("group-header-info-entry", _pick_button(nodes, TXT_INFO_TITLE, desc=True) is not None,
            "content-desc=%r（ChatScreen.kt:715-720）" % TXT_INFO_TITLE)
    # onNudge = onNudge?.takeIf { group == null && !peer.isAgent }（ChatScreen.kt:367-370）
    R.check("group-composer-no-nudge", not R.ui_has(TXT_NUDGE, nodes),
            "群聊里不该有 content-desc=%r" % TXT_NUDGE)

    # 对照：单聊里应有「戳一碰」，且不该有「n 位成员」。
    ok = R.open_chat(st["nick_a"])
    R.check("direct-chat-opens", ok, "open_chat(%r)" % st["nick_a"])
    if ok:
        dn = _log_screen("单聊（对照）", R.ui_all())
        R.check("direct-composer-has-nudge", R.ui_has(TXT_NUDGE, dn),
                "单聊有 content-desc=%r（判别组差异）" % TXT_NUDGE)
        R.check("direct-header-not-group", not _member_counts(dn),
                "单聊不该出现「n 位成员」（整行匹配）")
    R.open_chat(st["group_name"])


def _inbound_phase(args, st):
    phase("R69/R70 收群消息（入站 group-text 白名单 + 归位 + 署名）")
    if not R.open_chat(st["group_name"]):
        R.check("group-inbound-chat-opened", False, "打不开群会话")
        return
    marker = R.uniq_marker("in")
    t0 = time.time()
    # 同 rev（groupRev == 本地 rev）：Codec 通过、GroupMerge 无关、也不该触发 need。
    env = st["peer_a"].env(R.T_MSG, {"kind": R.K_GROUP_TEXT, "text": marker,
                                    "groupId": st["group_id"], "groupRev": st["rev"]})
    R.log("发入站 group-text text=%r groupId=%s groupRev=%s payload keys=%s"
          % (marker, st["group_id"], st["rev"], sorted(_payload({"env": env}))))
    st["peer_a"].send(env)
    st["peer_a"].wait_ack(env["id"], _budget(args, 6))
    R.check("group-text-bubble-rendered",
            R.find_ui(text=marker, timeout=_budget(args, 10)) is not None,
            "群会话里出现 %r" % marker)
    R.check("group-text-sender-signature",
            R.find_ui(text=st["nick_a"], timeout=_budget(args, 6)) is not None,
            "群气泡署名用 senderName（ChatScreen.kt:438-442）⇒ 屏幕上应出现 A 的昵称 %r" % st["nick_a"])
    need = st["fleet"].wait(
        lambda fr: _group_frames(_since(fr, t0), op="need", sender=st["phone_id"], group_id=st["group_id"]) or None,
        timeout=3.0)
    R.check("group-text-no-need-at-equal-rev", not need,
            "groupRev == 本地 rev ⇒ syncGroupMetaIfNeeded 不发 need（判据是严格 >）")

    # 归位：同一条消息不该落进跟 A 的单聊。
    if R.open_chat(st["nick_a"]):
        R.check("group-text-not-in-direct-thread",
                not R.ui_has(marker, R.ui_all()),
                "群消息按 group:<id> 入库（RelayControllerMessages.kt:252），单聊里不该有它")
    R.open_chat(st["group_name"])

    # mentions 一起走一遍白名单：mentions 是白名单内的键，带它不该被丢。
    marker2 = R.uniq_marker("me")
    env2 = st["peer_a"].env(R.T_MSG, {"kind": R.K_GROUP_TEXT, "text": marker2,
                                     "groupId": st["group_id"], "groupRev": st["rev"],
                                     "mentions": [MENTION_ALL]})
    st["peer_a"].send(env2)
    R.check("group-text-with-mentions-rendered",
            R.find_ui(text=marker2, timeout=_budget(args, 10)) is not None,
            "mentions=%r 在白名单内（Codec.kt:335-341），整帧不该被丢" % [MENTION_ALL])
    R.log("mentions 只影响通知（NotificationPolicy.kt:44-52 mentionsSelf），**界面没有 @ 高亮**："
          "本条消息在气泡上与普通群消息完全一样")


def _need_phase(args, st):
    phase("R71 op:need 索取 + 防往返风暴（GroupNeedTracker）")
    add = st["fleet"]
    base = st["rev"]
    path = st["group_id"]
    phone = st["phone_id"]

    def send_probe(rev, tag):
        marker = R.uniq_marker(tag)
        env = st["peer_a"].env(R.T_MSG, {"kind": R.K_GROUP_TEXT, "text": marker,
                                        "groupId": path, "groupRev": rev})
        st["peer_a"].send(env)
        return marker, env

    def wait_need(since, timeout=8.0):
        return add.wait(
            lambda fr: _group_frames(_since(fr, since), op="need", sender=phone, group_id=path) or None,
            timeout=_budget(args, timeout))

    # (1) 本地 rev=N，收到 rev=N+1 的群消息 ⇒ 必须发一次 need(N+1)。
    t0 = time.time()
    send_probe(base + 1, "nd")
    need1 = wait_need(t0)
    R.check("group-need-on-newer-rev", need1 is not None,
            "groupRev=%d > 本地 %d ⇒ 发 group{op:need}" % (base + 1, base))
    if not need1:
        return
    R.check("group-need-carries-group-id",
            _payload(need1[0]).get("groupId") == path,
            "need 载荷=%s" % json.dumps(_payload(need1[0]), ensure_ascii=False))

    # (2) 让手机**主动拒收**一份 info：A 是普通成员却改群名 ⇒ Reject("member-may-only-invite")，
    #     onGroupInfo 的 Reject 分支会 noteRequested(rev)（RelayControllerGroups.kt:271-273）。
    rej_rev = base + 2
    bad = _mirror(st["meta"], rev=rej_rev, name=st["group_name"] + "x",
                  updatedBy=st["node_a"], updatedTs=R.now_ms())
    st["peer_a"].send(st["peer_a"].env(R.T_GROUP, {"op": "info", "group": bad}))
    time.sleep(1.5)
    R.check("group-info-from-member-rejected-silently",
            R.find_ui(text=st["group_name"] + "x", timeout=2.0) is None,
            "普通成员改名会被 GroupMerge 拒（member-may-only-invite），界面**不该有任何反应**")

    # (3) 同一条 rev 再来一次群消息：tracker 已经记过这个 rev ⇒ **不再追问**。
    t1 = time.time()
    send_probe(rej_rev, "st")
    need2 = wait_need(t1, timeout=5.0)
    R.check("group-need-dedup-no-storm", not need2,
            "同一 rev 不重复要（GroupMerge.kt:167-205 的 requested 游标；没有它就是每条群消息白跑一个来回）")

    # (4) 更高的 rev 仍然要问 —— 证明 (3) 不是"need 整个坏了"。
    t2 = time.time()
    send_probe(rej_rev + 1, "hi")
    need3 = wait_need(t2)
    R.check("group-need-resumes-on-higher-rev", need3 is not None,
            "rev=%d > 记过的最高 %d ⇒ 仍要追问" % (rej_rev + 1, rej_rev))
    st["need_rev"] = rej_rev + 1


def _mirror(meta, **changes):
    """照抄手机发来的 meta dict，只改指定字段。

    **必须照抄**：`GroupMerge.sameManagementIdentity`（GroupMerge.kt:161-165）要求
    creatorIp/creatorId/adminSecretHash/adminHint 两侧完全一致（null 归一成空串），
    自己重造一份 meta 很容易漏一个键 —— 那样会被以 management-identity 拒收，
    而症状和"对方没收到"一模一样：手机上什么都不发生。
    """
    out = dict(meta)
    out.update(changes)
    return out


def _rename_phase(args, st):
    phase("R71/R73 入站 info 改群名（改名的唯一路径）+ R74 群名改了")
    since = time.time()
    if st.get("need_rev") is None:
        # 前序 `_need_phase` 第 (1) 步没跑成（手机没回 need）就提前 return 了，`need_rev` 从未设置。
        # 原来这里直接 `int(st["need_rev"])` ⇒ KeyError 把整个夹具崩成 traceback（退出码 2），
        # 掩盖了真正的失败原因。现在把它记成一条**显式失败**，继续跑后面的相位。
        R.check("group-rename-rev-cursor-known", False,
                "前序 _need_phase 没测出 rev 游标（`group-need-on-newer-rev` 那条失败是根因）；"
                "改名相位无从构造 rev，退回到已知的 st['rev']=%s 继续" % st.get("rev"))
        rev = int(st.get("rev") or 1) + 1
    else:
        rev = int(st["need_rev"]) + 1
    # `group-rename-notice-shown` 找的是**群会话时间线里**的一条 NoticeKind.GroupRenamed
    # （`chat_group_renamed` = 「群名改了」）。2026-10-10 run3 现场：这条断言原来直接跑，
    # 而前序 `_inbound_phase` 因为 `chat_title` 读错（读到群标题第二行「2 位成员」）没能打开群会话，
    # 于是三条改名读数一起假红；产品侧其实改成了（落盘 `ren1ccb03` rev 5）。
    # 所以先真的站到**改名前**那条群会话上，再发 info、再看 notice。
    if not R.open_chat(st["group_name"]):
        R.check("group-rename-chat-opened", False,
                "改名前的群会话打不开（找不到 %r）—— 后面三条改名读数都会跟着假红" % st["group_name"])
    # updatedBy = 手机（群主）⇒ actorRole == Owner ⇒ Apply（GroupMerge.kt:138）。
    meta = _mirror(st["meta"], name=st["group_name2"], rev=rev,
                   updatedBy=st["phone_id"], updatedTs=R.now_ms())
    env = st["peer_a"].env(R.T_GROUP, {"op": "info", "group": meta})
    st["peer_a"].send(env)
    R.log("发入站 info rev=%d name=%r updatedBy=手机（群主）" % (rev, st["group_name2"]))
    R.check("group-rename-info-acked", st["peer_a"].wait_ack(env["id"], _budget(args, 8)),
            "可靠帧无条件回 ACK —— 只证明帧到了手机，真正的判据是下面的界面读数")
    R.check("group-rename-notice-shown",
            R.find_ui(text=TXT_NOTICE_RENAMED, timeout=_budget(args, 10)) is not None,
            "NoticeKind.GroupRenamed → chat_group_renamed（ChatScreen.kt:985）")
    st["meta"] = meta
    st["rev"] = rev
    st["name"] = st["group_name2"]
    if R.goto_messages_list():
        # 列表会记住滚动位置 ⇒ 按标题把那一行找出来再看，不要整屏 `ui_has`
        # （旧群那一行与通知横幅都咬过判据，见 §2.1）。
        R.check("group-rename-row-title", _conv_row(st["group_name2"]) is not None,
                "会话列表里应出现新群名 %r（convTitle 走 groupOfConv）" % st["group_name2"])
        R.check("group-rename-old-title-gone", _conv_row(st["group_name"]) is None,
                "旧群名 %r 不该还在列表里" % st["group_name"])
    R.open_chat(st["name"])


def _fanout_phase(args, st, expect):
    """R69 发群消息：逐成员单播扇出，N 个成员 N 条帧、**同一个信封 id**。"""
    phase("R69 群消息发送（扇出给 %s）" % "、".join(expect))
    _refresh_presence(st)
    if not R.open_chat(st["name"]):
        R.check("group-send-chat-opened", False, "打不开群会话")
        return
    marker = R.uniq_marker("out")
    t0 = time.time()
    landed = R.chat_send(marker)
    R.check("group-send-landed", landed == marker, "框里=%r 期望=%r" % (landed, marker))
    if landed != marker:
        return
    hits = st["fleet"].wait(
        lambda fr: _select(_since(fr, t0), mtype=R.T_MSG, kind=R.K_GROUP_TEXT,
                           sender=st["phone_id"], text=marker, group_id=st["group_id"]) or None,
        timeout=_budget(args, 15)) or []
    vias = sorted({_peer_of(f["via"]) for f in hits})
    R.check("group-send-fanout-recipients", vias == sorted(expect),
            "收到的成员=%s 期望=%s（NodeRuntime.sendGroupText 逐成员单播）" % (vias, sorted(expect)))
    R.check("group-send-one-envelope-per-fanout",
            len({f["env"]["id"] for f in hits}) == 1 and len(hits) == len(expect),
            "帧数=%d 不同信封 id=%d（KDoc：同一 id/ts/载荷扇出 N 份，不做逐成员回执）"
            % (len(hits), len({f["env"]["id"] for f in hits})))
    if hits:
        pl = _payload(hits[0])
        R.check("group-send-payload-whitelist", set(pl) <= GROUP_TEXT_KEYS,
                "keys=%s" % sorted(pl))
        R.check("group-send-payload-fields",
                pl.get("groupId") == st["group_id"] and int(pl.get("groupRev") or -1) == st["rev"]
                and pl.get("text") == landed,
                "groupId=%s groupRev=%s text=%r" % (pl.get("groupId"), pl.get("groupRev"), pl.get("text")))
        R.log("扇出帧 id=%s members=%s via=%s"
              % (hits[0]["env"]["id"][:8], sorted({f["via"] for f in hits}), [f["via"] for f in hits]))


def _info_dialog_open(args, st, tag):
    """打开群信息对话框并读第一屏。返回 (ok, nodes)。"""
    node = _find(TXT_INFO_TITLE, desc=True, timeout=5.0)
    if not node:
        R.check("group-info-dialog-open", False, "找不到 content-desc=%r" % TXT_INFO_TITLE)
        return False, []
    R.tap_node(node)
    time.sleep(1.0)
    nodes = _log_screen("群信息对话框（%s）" % tag, R.ui_all())
    ok = _pick_button(nodes, TXT_INFO_SUB) is not None or R.ui_has(TXT_INFO_HINT, nodes)
    R.check("group-info-dialog-open", ok, "锚点「%s」/「%s」" % (TXT_INFO_SUB, TXT_INFO_HINT))
    return ok, nodes


def _add_member_phase(args, st):
    phase("R72 邀请新成员（UI 驱动）+ R71 info 扇出给新成员")
    _refresh_presence(st)
    if not R.open_chat(st["name"]):
        R.check("group-add-chat-opened", False, "打不开群会话")
        return
    ok, nodes = _info_dialog_open(args, st, "邀请前")
    if not ok:
        return
    R.check("group-info-members-header-before", R.ui_has(TXT_MEMBERS_HEADER + "2）", nodes),
            "邀请前 成员（2）")
    R.check("group-info-shows-group-id", R.ui_has(st["group_id"], nodes),
            "groupId 原文应可见（chat_group_id_label）；群键是 group:<id>，屏上不该出现裸前缀")
    R.check("group-info-edit-meta-entry", _pick_button(nodes, TXT_EDIT_META) is not None,
            "有「编辑群公告与简介」；**没有改群名的输入**（改名只有入站 info 一条路）")
    if not _tap(TXT_ADD_MEMBER, timeout=4.0):
        R.check("group-add-dialog-open", False, "点不到「邀请新成员」")
        return
    time.sleep(0.9)
    nodes = _log_screen("邀请成员对话框", R.ui_all())
    R.check("group-add-dialog-open", _pick_button(nodes, TXT_ADD_TITLE) is not None,
            "锚点「%s」" % TXT_ADD_TITLE)
    # candidates = peers.filter { it.nodeId !in group.members }（GroupInfoDialog.kt:342-344）。
    # 负面断言只看**对话框带内** A 的出现次数：A 的昵称在背后聊天气泡的署名里也有。
    y_band, x_band, why = _dialog_band(nodes, (TXT_ADD_TITLE,), (TXT_CONFIRM, TXT_CANCEL))
    in_band_a = [] if y_band is None else [
        n for n in nodes
        if st["nick_a"] in n.text and y_band[0] <= n.center[1] <= y_band[1]
        and x_band[0] <= n.center[0] <= x_band[1]]
    row_b, detail_b = _dialog_row_candidates(nodes, st["nick_b"], (TXT_ADD_TITLE,),
                                             (TXT_CONFIRM, TXT_CANCEL))
    R.check("group-add-lists-only-non-members",
            bool(row_b) and not in_band_a,
            "候选里应有 B=%r、不该有已在群里的 A=%r（带内 A 出现 %d 次；%s）"
            % (st["nick_b"], st["nick_a"], len(in_band_a), detail_b))
    confirm0 = _pick_button(nodes, TXT_CONFIRM)
    R.check("group-add-confirm-disabled-before-select",
            confirm0 is not None and not confirm0.enabled,
            "enabled=%s（GroupInfoDialog.kt:398 chosen.isNotEmpty()）"
            % (confirm0.enabled if confirm0 else None))
    t0 = time.time()
    ok_row, used, detail = _tap_row_until(st["nick_b"], lambda: _enabled(TXT_CONFIRM),
                                          (TXT_ADD_TITLE,), (TXT_CONFIRM, TXT_CANCEL))
    R.check("group-add-peer-row-located", ok_row, "点过 %s；%s" % (used, detail))
    if not ok_row:
        return
    R.check("group-add-confirm-enabled-after-select", _enabled(TXT_CONFIRM),
            "勾上一个候选才亮")
    if not _tap(TXT_CONFIRM, timeout=3.0):
        R.check("group-add-tapped", False, "点不到「确定」")
        return
    hits = _wait_info(args, st, t0)
    R.check("group-add-info-broadcast", bool(hits), "手机建群后对成员逐个 sendGroupInfo")
    if not hits:
        return
    meta = _meta_of(hits[0])
    st["meta"] = meta
    st["rev"] = int(meta.get("rev") or 0)
    members = set(meta.get("members") or [])
    R.check("group-add-meta-members",
            members == {st["phone_id"], st["node_a"], st["node_b"]},
            "members=%s rev=%s" % (sorted(members), meta.get("rev")))
    R.check("group-add-fanout-to-new-member",
            {_peer_of(f["via"]) for f in hits} >= {"A", "B"},
            "被邀请者 B 也要收到（recipients = updatedMeta.recipientsExcluding(self)，"
            "RelayControllerGroups.kt:97-101）via=%s" % [f["via"] for f in hits])
    nodes = R.ui_all()
    R.check("group-info-members-header-after", R.ui_has(TXT_MEMBERS_HEADER + "3）", nodes),
            "对话框里的成员数应变成 3")
    _tap(TXT_CLOSE, timeout=3.0)
    time.sleep(0.8)
    # 通知行在**群会话时间线**里，群信息对话框盖着时 dump 里读不到（run5 就在对话框还开着
    # 的时候读了一次，红）。关掉对话框再读。
    R.check("group-add-notice-shown",
            R.find_ui(text=TXT_NOTICE_MEMBERS, timeout=_budget(args, 8)) is not None,
            "NoticeKind.GroupMembersChanged → chat_group_members_changed"
            "（ChatScreen.kt:1014 NoticeLine，控制器在 RelayControllerGroups.kt:99 notice(...)）")
    nodes = R.ui_all()
    R.check("group-header-member-count-after-add", 3 in _member_counts(nodes),
            "ChatHeader 的「n 位成员」（整行匹配）")
    R.check("group-header-online-ratio-after-add", R.ui_has(_ratio(2, 3), nodes),
            "A、B 都在线 ⇒ %r" % _ratio(2, 3))


def _mention_phase(args, st):
    phase("R77 群聊的「@」入口（群成员列表非空才出现）")
    if not R.open_chat(st["name"]):
        R.check("mention-chat-opened", False, "打不开群会话")
        return
    # 这里**不能**用 `chat_type_verified("@")`：打出 `@` 会打开 Compose 的 DropdownMenu
    # （MessageComposer.kt:326-364，一个 Popup 窗口）⇒ uiautomator dump 里**只剩那个窗口**，
    # 输入框（EditText）整条从 dump 里消失（2026-10-10 实测：打 @ 前 EditText 出现 1 次、
    # 打 @ 后 0 次，屏上是 `@所有人` / `@fixB9449` 两条菜单项）。`chat_type_verified` 靠回读
    # 框里的文本判成功，于是每轮都判「input text 吞字」，run3–run5 的
    # `mention-menu-opens-on-at` 就是这么红的。产品其实是对的：'@' 打进去了、菜单也弹了。
    comp = R.chat_composer(timeout=5.0)
    if not comp:
        R.check("mention-menu-opens-on-at", False, "找不到输入框（不在聊天页？）")
        return
    R.tap_node(comp)
    time.sleep(0.5)
    R.chat_clear()
    R.adb_input_text("@")
    time.sleep(1.0)
    node = R.find_ui(text=TXT_MENTION_ALL, timeout=4.0)
    R.check("mention-menu-opens-on-at", node is not None,
            "打出 @ 后应弹菜单项 %r（MessageComposer.kt:326-364；"
            "注意菜单一开，输入框就从 dump 里消失了）" % TXT_MENTION_ALL)
    if not node:
        R.chat_clear()
        return
    R.tap_node(node)
    time.sleep(0.6)
    draft = R.chat_field_text()
    R.check("mention-all-inserts-draft", bool(draft) and "@all" in draft,
            "点「@所有人」后草稿=%r（插入的是字面量 all + 空格）" % draft)
    # 线上文本要按**产品 trim 之后**的比：`handleSendToGroup` 第一句就是
    # `val body = text.trim()`（RelayControllerGroups.kt:251）⇒ 草稿尾随的那个空格
    # 不会上线。2026-10-10 run6：草稿读到 `'@all '`、手机落盘 `"text": "@all"`，
    # 这条判据于是红了一次（唯一一条）。
    sent = (draft or "").strip()
    t0 = time.time()
    if not R.chat_tap_send():
        R.check("mention-draft-sent", False, "点不到发送")
        return
    hits = st["fleet"].wait(
        lambda fr: _select(_since(fr, t0), mtype=R.T_MSG, kind=R.K_GROUP_TEXT,
                           sender=st["phone_id"], text=sent) or None,
        timeout=_budget(args, 12)) or []
    R.check("mention-draft-sent-as-group-text", bool(hits),
            "草稿 trim 后作为 group-text 上线（RelayControllerGroups.kt:251 val body = text.trim()）：%r" % sent)
    if hits:
        pl = _payload(hits[0])
        R.check("mention-payload-whitelist-clean", set(pl) <= GROUP_TEXT_KEYS, "keys=%s" % sorted(pl))
        R.log("线上 mentions=%r —— UI 路径**从不产出** mentions：ChatScreen 的 "
              "onSend 只有 (targetId, text, replyTo)（ChatScreen.kt:180/516-521），"
              "sendToConversation 也没有这个参数（RelayController.kt:135-142）。"
              "所以 @all 只是正文里的字面量；「有人@我」的判据是 mentions 数组（RelayController.kt:770）。"
              % pl.get("mentions"))


def _remove_member_phase(args, st):
    phase("R72 移出成员（UI 驱动）+ R74 群成员有变化")
    if not R.open_chat(st["name"]):
        R.check("group-remove-chat-opened", False, "打不开群会话")
        return
    ok, nodes = _info_dialog_open(args, st, "移出前")
    if not ok:
        return
    R.check("group-info-members-header-before-remove", R.ui_has(TXT_MEMBERS_HEADER + "3）", nodes),
            "移出前 成员（3）")
    y_band, x_band, why = _dialog_band(nodes, (TXT_INFO_SUB,), (TXT_CLOSE,))
    if y_band is None:
        R.check("group-remove-confirm-open", False, why)
        return
    # 成员按 group.members 顺序渲染（手机建群 = [手机, A]，邀请 B 只是追加）⇒ 自上而下的
    # 「移出」依次是 A、B；自己那行没有「移出」（GroupInfoDialog.kt:309-318）。
    t0 = time.time()
    ok, used, why = _tap_until(TXT_REMOVE_MEMBER,
                               verify=lambda: R.find_ui(text=TXT_REMOVE_CONFIRM, timeout=1.0) is not None,
                               y_band=y_band, x_band=x_band, order="top")
    R.check("group-remove-confirm-open", ok, "点了 %s（%s）" % (used, why))
    if not ok:
        return
    y_band2, x_band2, why2 = _dialog_band(R.ui_all(), (TXT_REMOVE_CONFIRM,), (TXT_CANCEL,))
    if y_band2 is None:
        R.check("group-remove-confirmed", False, why2)
        return
    ok2, used2, why2 = _tap_until(TXT_REMOVE_MEMBER,
                                  verify=lambda: R.ui_has(TXT_MEMBERS_HEADER + "2）"),
                                  y_band=y_band2, x_band=x_band2)
    R.check("group-remove-confirmed", ok2, "点了 %s（%s）" % (used2, why2))
    hits = _wait_info(args, st, t0)
    R.check("group-remove-info-broadcast", bool(hits), "移除后手机的 info")
    if hits:
        meta = _meta_of(hits[0])
        st["meta"] = meta
        st["rev"] = int(meta.get("rev") or 0)
        members = set(meta.get("members") or [])
        R.check("group-remove-meta-without-removed",
                members == {st["phone_id"], st["node_b"]},
                "members=%s rev=%s（A 已不在）" % (sorted(members), meta.get("rev")))
        R.check("group-remove-info-reaches-removed-peer",
                _peer_of(hits[0]["via"]) == "A",
                "被移出者也要收到：notifyList = oldMembers.filter{ it != self }"
                "（RelayControllerGroups.kt:145）⇒ A 收到这份新 meta via=%s" % hits[0]["via"])
    _tap(TXT_CLOSE, timeout=3.0)
    time.sleep(1.0)
    nodes = R.ui_all()
    R.check("group-remove-notice-shown", R.ui_has(TXT_NOTICE_MEMBERS, nodes),
            "NoticeKind.GroupMembersChanged 通知行")
    R.check("group-remove-header-count", 2 in _member_counts(nodes), "ChatHeader 应回到 2 位成员（整行匹配）")
    R.check("group-remove-header-ratio", R.ui_has(_ratio(1, 2), nodes),
            "A 被移出后群里剩手机 + B ⇒ %r" % _ratio(1, 2))


_REV_RE = re.compile(r"^\s*rev\s+(\d+)\s*$")


def _ui_group_rev(nodes=None):
    """从群信息对话框里读 `rev N`（`GroupInfoDialog` 的元数据行）。读不到返回 None。"""
    for t in R.ui_texts(nodes):
        m = _REV_RE.match(t or "")
        if m:
            return int(m.group(1))
    return None


def _refresh_rev_from_ui(args, st):
    """把 `st["rev"]` 对齐到**手机当前真实的**群 rev（从群信息对话框读）。

    为什么需要：夹具只有在**观测到**手机发的 `group{op:"info"}` 时才会更新 `st["rev"]`
    （`_wait_info`），而手机对**离线**成员是入队不发的（§5.7）—— group run4 里
    `group-remove-info-broadcast` 收了 0 条帧（会话列表上写着「有 7 条消息还在等对方上线」），
    于是 `st["rev"]` 停在 5，`_removed_self_phase` 发出去的「自己被移出」是 rev=6，
    而手机早被 UI 移出 A 推到了 rev=7 ⇒ `GroupMerge.decide` 判「不更新」，
    `group-removed-notice-shown` / `group-removed-ratio` 两条跟着红。
    现在改为发之前先读一次界面上的 rev（`群信息 → rev N → 关闭`），读不到就保留原值并记一笔。
    """
    if not R.open_chat(st["name"]):
        R.log("读群 rev 前打不开群会话，沿用 st['rev']=%s" % st.get("rev"))
        return st.get("rev")
    node = _find(TXT_INFO_TITLE, desc=True, timeout=4.0)
    if not node:
        R.log("找不到群信息入口，沿用 st['rev']=%s" % st.get("rev"))
        return st.get("rev")
    R.tap_node(node)
    time.sleep(1.0)
    nodes = R.ui_all()
    rev = _ui_group_rev(nodes)
    _tap(TXT_CLOSE, timeout=3.0)
    time.sleep(0.6)
    if rev is None:
        R.log("群信息对话框里没有 `rev N` 行，沿用 st['rev']=%s（可见文字见上一条 EVID）"
              % st.get("rev"))
        return st.get("rev")
    R.log("界面读数：群 rev=%s（夹具先前记的是 %s）" % (rev, st.get("rev")))
    st["rev"] = max(int(st.get("rev") or 0), rev)
    return st["rev"]


def _removed_self_phase(args, st):
    phase("R74 自己被移出（GroupMerge.Removed）")
    _refresh_rev_from_ui(args, st)
    since = time.time()
    rev = int(st["rev"]) + 1
    # `ownerId` 必须落在 `members` 里，否则这一帧**在校验阶段就被丢掉**：`Codec.validateGroup`
    # 的 `if (ownerId !in members) return false`（Codec.kt:273）⇒ 手机连 ACK 都不回。
    # run5 就是这么红的：mirror 只换了 members=[B]、`ownerId` 还留着手机自己 ⇒ 帧被丢，
    # 手机落盘仍是 rev 7（`adb exec-out run-as com.kapp.tidings cat files/relay/settings.json`
    # 里 `69a6cbfd… rev 7 members=[手机, fixB9449b917c6]`），`group-removed-notice-shown`
    # 与 `group-removed-ratio` 两条一起红。
    # 权限仍按 `updatedBy` 判（GroupMerge.kt:112 `val actor = next.updatedBy`）⇒ 发起者写手机
    # （本地群的 owner），手机才会走 `Removed` 分支（GroupMerge.kt:126-129）。
    meta = _mirror(st["meta"], members=[st["node_b"]], rev=rev, ownerId=st["node_b"],
                   adminIds=[], updatedBy=st["phone_id"], updatedTs=R.now_ms())
    # 从 **B**（现成员）发，不从刚被移出的 A 发：`syncGroupMetaIfNeeded` 只看载荷里的
    # `updatedBy`，但站在 A 的 socket 上发一份「A 已被移出」的元数据不符合现场。
    env = st["peer_b"].env(R.T_GROUP, {"op": "info", "group": meta})
    st["peer_b"].send(env)
    R.log("发入站 info rev=%d members=[B]（自己被移出）" % rev)
    R.check("group-removed-notice-shown",
            R.find_ui(text=TXT_NOTICE_REMOVED, timeout=_budget(args, 10)) is not None,
            "GroupMerge.Removed → NoticeKind.RemovedFromGroup（ChatScreen.kt:986）")
    st["meta"] = meta
    st["rev"] = rev
    if R.goto_messages_list():
        nodes = _log_screen("被移出后的会话列表", R.ui_all())
        t0 = time.time()
        R.check("group-removed-still-listed", R.ui_has(st["name"], nodes),
                "群**不该被删掉**：GroupMerge.kt:13-21 的 KDoc 说得很明白，"
                "会话列表还留着群名与历史（%s）" % st["group_id"])
        R.check("group-removed-ratio", R.ui_has(_ratio(1, 1), nodes),
                "members=[B] 且 B 在线 ⇒ %r" % _ratio(1, 1))
        R.log("半径 %.1fs 内读到 %d 条文本" % (time.time() - t0, len(R.ui_texts(nodes))))


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def _body(args):
    st = {
        "group_name": R.uniq_marker("grp"),
        "group_name2": R.uniq_marker("ren"),
        "nick_a": R.uniq_marker("nA"),
        "nick_b": R.uniq_marker("nB"),
        "node_a": "fixA" + uuid.uuid4().hex[:10],
        "node_b": "fixB" + uuid.uuid4().hex[:10],
        "group_id": "",
        "rev": 0,
        "meta": {},
        "name": "",
        "have_b": False,
        "fleet": None,
    }
    st["name"] = st["group_name"]

    class Fleet(object):
        """两个 UDP 对端 + 两个 TCP 服务端的读数层。"""

        def frames(self, since=0.0):
            return _dedup(_inbox(st["udp_links"], st["tcp_links"], since=since))

        def wait(self, pred, timeout, interval=0.4):
            deadline = time.time() + float(timeout)
            last = None
            while True:
                frames = self.frames()
                last = pred(frames)
                if last:
                    return last
                if time.time() >= deadline:
                    return None
                time.sleep(interval)

    st["fleet"] = Fleet()

    if not _handshake(args, st):
        return None

    try:
        if args.skip_ui:
            if not _protocol_admission(args, st):
                return None
            _need_phase(args, st)
            _rename_phase(args, st)
            _fanout_phase(args, st, ("A", "B"))
            _skip(UI_CHECKS, "--skip-ui：这些断言只能在界面上读")
            return None

        if not _create_group_ui(args, st):
            _skip(("group-conv-*/group-header-*/group-text-*/group-info-*/group-add-*/"
                   "group-remove-*/group-removed-*/mention-*"),
                  "R67 建群这一拍没成，后面的界面读数都依赖它")
            return None
        st["name"] = st["group_name"]
        _list_reading(args, st)
        _chat_page_reading(args, st)
        _inbound_phase(args, st)
        _start_heartbeat(st)
        _need_phase(args, st)
        _rename_phase(args, st)
        _fanout_phase(args, st, ("A",))
        if not st["have_b"]:
            _skip(("group-add-*", "group-remove-*", "group-removed-*", "mention-*",
                   "group-send-fanout-recipients（两个成员那一轮）"),
                  "第二个对端 B 没握上手")
            return None
        _add_member_phase(args, st)
        _fanout_phase(args, st, ("A", "B"))
        _mention_phase(args, st)
        _remove_member_phase(args, st)
        _removed_self_phase(args, st)
        phase("收尾")
        st["peer_a"].send_exit()
        st["peer_b"].send_exit()
        R.log("已发 exit（手机 ~2s 内把两台对端置为离线）。本夹具不改手机设置、不删群："
              "留下的测试群名是 %r（协议里没有删群/退群的 op，GroupMeta.kt:140-143 只有 info/need）"
              % st["name"])
        return None
    finally:
        hb = st.get("heartbeat")
        if hb is not None:
            hb.stop()
            R.log("对端心跳已停")
        for tag, peer in st.get("udp_links") or []:
            peer.stop()
        for srv in st.get("tcp") or []:
            srv.stop()
        R.log("对端已停（udpA/udpB/tcpA/tcpB）")
        R.log("本次读数：groupId=%s name=%r rev=%s" % (st.get("group_id"), st.get("name"), st.get("rev")))


def run(args):
    marker = R.uniq_marker("g")
    long_text = R.long_text(FIXTURE_LONG_LEN, tail_marker=R.uniq_marker("z"))
    group_name = R.uniq_marker("grp")
    node_a = "fixA" + uuid.uuid4().hex[:10]

    phase("本地自检（不碰设备、不碰网络）")
    _local_self_checks(args, group_name, node_a)
    R.log("本地自检的群名标记=%r（真机阶段会按同一规则另生成一轮：群名 grp*/ren*、"
          "对端昵称 nA*/nB*，都只用 [A-Za-z0-9._@%%+=:]）" % group_name)
    for row, why in ROWS_NOT_COVERED:
        R.log("不覆盖 %s：%s" % (row, why))

    if args.dry_run:
        print("\n[dry-run] 不碰网络、不碰设备：只跑了本地自检，按规范**不打 result 行**", flush=True)
        print("[dry-run] 真机跑法：scripts/relay-liveness.sh --quiet && python3 %s --secs 420"
              % os.path.basename(__file__), flush=True)
        print("[dry-run] 真机将执行：握手两台对端（UDP）→ 界面建群 → 读会话列表/群会话页（含单聊对照）"
              " → 入站 group-text（白名单/署名/归位）→ need 与防风暴 → 入站 info 改名 → "
              "逐成员扇出（1 个成员/2 个成员各一轮）→ 邀请 B → @ 入口 → 移出 A → 自己被移出",
              flush=True)
        print("[dry-run] 命令行只做静态校验；本模式不碰 adb（%s）、不碰 %s 的 UDP/TCP"
              % (R.PKG, args.phone), flush=True)
        return 0
    return _body(args)


def extra_args(ap):
    ap.add_argument("--tcp-port", type=int, default=R.TCP_PORT_DEFAULT,
                    help="对端 A 的 TCP 监听端口（写进 profile.tcpPort），默认 %(default)s")
    ap.add_argument("--tcp-port-b", type=int, default=R.TCP_PORT_DEFAULT + 1,
                    help="对端 B 的 TCP 监听端口。两台对端故意用不同端口："
                         "同 ip:port 有被手机按地址合并成一台的风险，默认 %(default)s")
    ap.add_argument("--skip-ui", action="store_true",
                    help="只跑协议面：不用 adb/uiautomator，改用入站 info 把手机拉进群"
                         "（GroupMerge 的 local==null 分支），界面断言整批跳过并点名")
    ap.set_defaults(secs=420)


if __name__ == "__main__":
    sys.exit(R.main_wrapper(run, description=__doc__, extra_args=extra_args))
