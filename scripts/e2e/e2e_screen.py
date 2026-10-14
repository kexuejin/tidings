#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""投屏（screen）e2e 真机夹具：把既有的 scripts/relay-screen-fixture.py 挂到统一 CLI。

覆盖 ACCEPTANCE A17 / 覆盖矩阵 R97（C5）：屏幕协助双向 —— 手机当 Sharer（--mode viewer）
与手机当 Viewer（--mode sharer）。§3 的 M22/M23/M24（iOS ReplayKit 广播、桌面端 GUI 入口、
permission-denied 生产路径）只能人工，不在本夹具范围内。

**移植，不是重写**：协议层与行为序列逐条照抄 scripts/relay-screen-fixture.py（那份已在
Pixel 6 上跑通，EV screen-mirroring-android.txt）。只换外壳：

  * CLI 走共享库 R.main_wrapper（--phone/--host-ip/--secs/--dry-run）；原件的位置参数
    `mode viewer|sharer|watch` 用 extra_args 保留成 `--mode`，默认 watch（run_all.py 按固定
    命令行跑夹具、**不传 --mode**，所以默认必须是那个不需要人工介入的模式）。
  * 结论行改用共享库 R.report()：真机跑完**恰好一行** `result PASS <n>` /
    `result FAIL <n> failed=<逗号分隔>`；退出码 0=PASS / 1=功能 FAIL / 2=用法或环境错误。
  * UDP 侧收发与 ACK 改用共享库 R.UdpPeer（对 T_SCREEN 这类可靠帧自动回 ACK，与原件
    handle() 里"先回 ack 再处理"是同一条协议纪律）。顺带修掉原件 viewer 模式里
    "主循环和 listen_loop 两个线程同时 recvfrom 同一个 socket"的抢包竞态。
  * TCP 侧分帧用共享库 R.tcp_frame / R.read_tcp_frame / R.recv_exact（同样是 4 字节大端
    长度前缀 + UTF-8 JSON）。**sharer 模式的 TCP 服务端仍照抄原件自己起**：共享库的
    TcpFrameServer 只认 msg / msg-ack，screen-* 一族它既不解也不回。
  * 握手用 R.UdpPeer.handshake(probe=True)（带 probeId 的定向 entry，手机固定 1s 回 alive），
    原件是不带 probeId 的裸 entry 轮询 —— 线上语义一样（entry → alive/profile），更快更稳。

PARTIAL → FAIL 的映射（与原件的唯一语义分歧，理由如下）

  原件结论行是 `result PASS|PARTIAL|FAIL frames N`，三态：
      viewer：frames ≥ 10 → PASS，0 < frames < 10 → PARTIAL，0 → FAIL
      sharer：frames ≥ 20 → PASS，0 < frames < 20 → PARTIAL，0 → FAIL
  夹具契约（docs/plans/2026-10-09-relay-e2e-fixture-program.md §1.1）只允许 PASS/FAIL 两态，
  且这里是 fail-closed："收到过几帧"绝不能算合格，只有实测帧数达标才是 PASS。所以：

      frames ≥ 阈值  → PASS
      frames <  阈值 → FAIL   ← 原件判 PARTIAL 的那一段与判 FAIL 的那一段合并到这里

  失败名单里必须写清**是哪一项帧数不足**，所以帧数那条 check 的名字自带读数：

      frames-short(<实测>/<阈值>)    ← 未达标（含 0 帧）
      frames-ok(<实测>/<阈值>)       ← 达标

  例：viewer 收到 3 帧 ⇒ `result FAIL … failed=frames-short(3/10)`（原件在这里判 PARTIAL）。
  阈值照抄原件（viewer 10 / sharer 20），不调松、不加第三个态。
  另有 --dry-run 下一条 `screen-partial-maps-to-fail` 断言把这条映射钉住（见 _local_checks）。

  第二处 fail-closed：原件在"没握上手 / 没等到 accept / 没等到手机的 screen.request"这三条
  路径上**直接 return，一行 result 都不打**（进程仍退 0，看日志才知道失败）。本夹具把每一种
  落空都变成一条显式 check（并补一条 `frames-short(0/<阈值>)`），于是它们自然进 FAIL 名单。

三种模式

  watch  —— 只握手并断言对端声明了 rv1/rvs1（默认；不需要人在手机上点任何东西）
  viewer —— 手机当 Sharer：夹具请求看手机屏 → 手机上先点 app 内「同意共享」、再点系统投屏
            授权 → 授权后夹具连手机 TCP 收流（≥10 帧合格）。
  sharer —— 手机当 Viewer：夹具先发一条消息把**本夹具**的会话顶到会话列表最上面（nodeId 每
            进程一换，旧会话指向死节点），人在那一行点「屏幕协助」，夹具回 accept 并用假
            JPEG 泵流（≥20 帧合格）。发起入口在手机 UI 里，夹具不替人决定点不点；加了
            --ui-assist 时夹具会按 dump 的 bounds 代点"最上面那行 + 屏幕协助"。

    # 不插手机也能确认自己没写崩（不碰网络、不碰设备，退出码 0）：
    python3 scripts/e2e/e2e_screen.py --dry-run

    # 先验对端 caps（最省事的一条，无人值守也安全）：
    python3 scripts/e2e/e2e_screen.py --mode watch --phone 192.168.2.22 --host-ip 192.168.2.82

    # 验手机当 Sharer 的全链路（MediaProjection）：
    python3 scripts/e2e/e2e_screen.py --mode viewer --secs 120
    # 无人值守时让夹具按 uiautomator dump 的 bounds 代点两个授权按钮：
    python3 scripts/e2e/e2e_screen.py --mode viewer --secs 120 --ui-assist

    # 验手机当 Viewer（夹具先发消息把会话顶上来；--ui-assist 代点那一行 + 「屏幕协助」）：
    python3 scripts/e2e/e2e_screen.py --mode sharer --secs 120 --ui-assist

退出码 0=PASS / 1=FAIL / 2=用法或环境错误（手机不在 adb、TCP 端口被占等）；真机跑完结尾
恰好一行 `result ...`。--dry-run 按 scripts/e2e/run_all.py:238 的约定**不调 report()**
（否则 run_all 会把 DRYRUN 记成 PASS）。
"""

import json
import os
import socket
import struct
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay_e2e as R  # noqa: E402


# ---------------------------------------------------------------------------
# 0. 常量：身份与阈值照抄原件
# ---------------------------------------------------------------------------

# 夹具的身份。nick 与原件一致（"夹具桌面"）：真机验收记录里手机侧看到的邀请方就叫这个名字，
# 改了会让"这次 PASS 的是不是同一个对端"变得不可比。
NICK = "夹具桌面"

# 与原件 profile_caps(rvs1=True) 同序同集合：tw1 / mrec1 / agn1 恒在，rv1 = 能看别人的屏，
# rvs1 = 能把自己的屏共享出去。**两个都要有**：手机当 Viewer 时它只对声明了 rv1 的对端开放
# 「屏幕协助」入口；rv1/rvs1 在手机端是 RelaySettings.caps(remoteView=…) 动态加的。
FIXTURE_CAPS = [R.CAP_MOBILE_TEXT, R.CAP_MOBILE_RECORD, R.CAP_AGENT_SESSION,
                R.CAP_REMOTE_VIEW, R.CAP_REMOTE_SHARE]

FRAMES_VIEWER_MIN = 10          # 原件：viewer ≥10 PASS、>0 PARTIAL、0 FAIL
FRAMES_SHARER_MIN = 20          # 原件：sharer ≥20 PASS、>0 PARTIAL、0 FAIL
READER_MAX_FRAMES = 60          # 原件 pump_reader 的帧数上限
READER_BUDGET_S = 30.0          # 原件 pump_reader 的 30s 预算
SHARER_MAX_FRAMES = 40          # 原件 pump_sharer 的帧数上限
SHARER_ACCEPT_TIMEOUT_S = 25.0  # 原件 srv.settimeout(25)
SHARER_PUMP_BUDGET_S = 60.0     # 泵流总预算（原件靠 conn 阻塞读，这里给个上界）
HANDSHAKE_SECS = 15.0           # 原件 handshake 的 15s

# --ui-assist 要点的两个"同意"（默认由人点）：
#   app 内弹窗   strings.xml: screen_remote_view_consent_accept = "同意共享"
#   系统投屏授权 MediaProjection 的确认键，各 ROM/语言文案不同，列候选、按 uiautomator
#   dump 的 bounds 点。**任何情况下都不点这些**（宁可让人来点，也不要把"拒绝/取消"点掉）。
UI_APP_ACCEPT = "同意共享"
UI_SYSTEM_ACCEPT = ("开始录制", "开始投屏", "立即开始", "共享屏幕",
                    "Start now", "Start recording", "Share screen")
# 系统对话框的模式 Spinner：默认「共享一个应用」，下拉里的另一项是「共享整个屏幕」。
# 不换模式的话确认键是「下一步」、点下去进的是应用选择器（见 _ui_assist_pass 的注释）。
UI_MODE_APP = "共享一个应用"
UI_MODE_SCREEN = "共享整个屏幕"
UI_NEVER_TAP = ("拒绝", "取消", "停止", "Cancel", "Deny", "Stop")

# sharer 模式（手机当 Viewer）的发起入口在手机聊天页（ChatScreen.kt:769 的 contentDescription
# = screen_remote_view_action「屏幕协助」）。两条现场事实决定了夹具必须先"敲门"：
#   1. 手机的会话是**收到过消息**才建立的，而夹具 nodeId 每个进程都换（R.FIXTURE_NODE 是
#      uuid4）—— 上一轮留下的「夹具桌面」会话指向一个已经死掉的 nodeId，点它发的
#      screen.request 到不了本夹具（TCP 也就永远连不上）。
#   2. 会话列表按最近消息排序，所以"刚收到消息的那一行"就是本夹具的会话。
# 于是先发一条消息把它顶到列表最上面，再（--ui-assist）点最上面那一行 + 「屏幕协助」。
WAKE_TEXT = "投屏夹具：请点右上角「屏幕协助」"
WAKE_MARK = "投屏夹具"          # 列表预览会被省略号截断，只按开头这几个字找行
UI_SCREEN_ACTION = "屏幕协助"

# screen TCP 帧族（TcpFrameTypes.SCREEN），四种一个不多一个不少。
SCREEN_FRAME_TYPES = ("screen-open", "screen-ready", "screen-next", "screen-frame")

# 手机侧 ScreenImageMetadata 的走查常量（照抄，用来在本地把假 JPEG 先过一遍）。
JPEG_SOF_MARKERS = frozenset((0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                              0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF))
SCREEN_MAX_FRAME_BYTES = 512 * 1024   # Limits.SCREEN_MAX_FRAME_BYTES
SCREEN_MAX_EDGE = 1920                # Limits.SCREEN_MAX_EDGE
SCREEN_MAX_PIXELS = 2073600           # Limits.SCREEN_MAX_PIXELS

_PLAN = {
    "watch": "握手 → 读手机 profile → 断言 caps 里有 rv1/rvs1"
             "（手机端在设置里打开「屏幕协助」才会声明）",
    "viewer": "握手 → 发 screen.request → 手机上点「%s」+ 系统投屏授权"
              "（--ui-assist 则由夹具按 uiautomator bounds 代点）→ 连手机 TCP 发 "
              "screen-open → 逐帧 screen-next/screen-frame，≥%d 帧合格"
              % (UI_APP_ACCEPT, FRAMES_VIEWER_MIN),
    "sharer": "握手 → 人在手机聊天页点「屏幕协助」→ 夹具回 screen.accept → 监听 TCP 收 "
              "screen-open（核对 token/sessionId）→ 回 screen-ready → 按 screen-next 泵"
              "假 JPEG，≥%d 帧合格" % FRAMES_SHARER_MIN,
}


# ---------------------------------------------------------------------------
# 1. 协议编解码：照抄 relay-screen-fixture.py
# ---------------------------------------------------------------------------

def make_small_jpeg(w=64, h=48):
    """手工造一个最小合法 JPEG（SOF0 带 w/h + EOI），夹具用它泵"画面"。

    照抄原件：ScreenImageMetadata/screenImageSize 只走查 JPEG 的 marker 段（SOI、SOF 里的
    w/h、EOI），不解像素，所以不需要真编码器 —— 但必须是它的走查能认的形状。**不能乱改**：
    SOF 段长 0x11 是"3 个分量"的标准长度，改短了走查在 length < 7 或越界时直接返回 null，
    手机侧表现为整帧被丢、画面永远不出来。
    """
    sof = bytes([0xFF, 0xC0, 0x00, 0x11, 0x08]) + struct.pack(">HH", h, w) + bytes(
        [3, 1, 0x21, 0x01, 2, 0x11, 0x00, 3, 0x11, 0x00]
    )
    return bytes([0xFF, 0xD8]) + sof + bytes([0xFF, 0xD9])


def jpeg_sof_size(data):
    """照抄手机侧 ScreenImageMetadata.inspectJpeg：返回 (w, h) 或 None。

    这段"本地复刻"只服务于 --dry-run 的本地自检：让夹具在**不碰设备**的情况下就能确认自己
    造的假 JPEG 过得去手机那道走查（原件只靠真机跑一遍才知道）。
    """
    if len(data) < 4:
        return None
    if data[0] != 0xFF or data[1] != 0xD8:
        return None
    off = 2
    while off + 3 < len(data):
        while off < len(data) and data[off] != 0xFF:
            off += 1
        while off < len(data) and data[off] == 0xFF:
            off += 1
        if off >= len(data):
            return None
        marker = data[off]
        off += 1
        if marker == 0xD8 or marker == 0x01 or 0xD0 <= marker <= 0xD7:
            continue
        if marker == 0xD9 or marker == 0xDA or off + 2 > len(data):
            return None
        length = (data[off] << 8) | data[off + 1]
        if length < 2 or off + length > len(data):
            return None
        if marker in JPEG_SOF_MARKERS:
            if length < 7:
                return None
            h = (data[off + 3] << 8) | data[off + 4]
            w = (data[off + 5] << 8) | data[off + 6]
            return (w, h) if w > 0 and h > 0 else None
        off += length
    return None


# ---------------------------------------------------------------------------
# 2. PARTIAL → FAIL 的映射：一处定义，自检与结论都用它
# ---------------------------------------------------------------------------

def frames_ok(frames, need):
    """帧数是否达标。**唯一的合格判据**：原件判 PARTIAL 的那一段在这里就是 False。"""
    return frames >= need


def frames_check_name(frames, need):
    """帧数那条 check 的名字：失败时自带读数，一眼看出是哪一项不足。"""
    return "frames-%s(%d/%d)" % ("ok" if frames_ok(frames, need) else "short", frames, need)


def check_frames(kind, frames, need, note=""):
    """登记帧数那条 check（fail-closed：< 阈值一律 FAIL，没有第三态）。"""
    ok = frames_ok(frames, need)
    detail = "%s：实测 %d 帧 / 阈值 %d 帧" % (kind, frames, need)
    if note:
        detail += "；" + note
    if not ok:
        detail += "（原件的 PARTIAL 段在这里一律 FAIL）"
    return R.check(frames_check_name(frames, need), ok, detail)


# ---------------------------------------------------------------------------
# 3. 小工具
# ---------------------------------------------------------------------------

def _budget(args, cap):
    """把 --secs 当全局预算：返回不超过剩余预算的超时。"""
    return max(1.0, min(cap, float(args.secs)))


def phase(title):
    print("\n== %s ==" % title, flush=True)


def _drain_screen(peer, seen):
    """把 UdpPeer 收到的 screen 信封里没处理过的取出来（按信封 id 去重）。

    ACK 由 UdpPeer 的 auto_ack 负责（T_SCREEN 在 RELIABLE_TYPES 里），这里只管业务。
    """
    out = []
    for (_ts, _addr, env) in peer.frames(R.T_SCREEN):
        eid = env.get("id")
        if eid in seen:
            continue
        seen.add(eid)
        out.append(env)
    return out


def _ui_assist_pass(attempt):
    """--ui-assist：dump 一次 UI，按 **bounds 算出的 center** 点授权按钮。

    硬规矩：不许目测坐标 —— 投屏批次就是因为估坐标误触了返回键，把"没反应"记成了"功能坏了"。
    所以这里只走 R.ui_all() → R.find_node() → R.tap_node()，坐标全部来自 dump 的 bounds。
    找不到就什么都不点，继续等人来点（graceful degrade：系统弹窗不一定在 dump 的窗口里）。

    **系统授权链的真实形状**（2026-10-10 Pixel 6 / Android 15，`com.android.systemui`，
    探针 `/tmp/probe_consent_screen_mode.py` 逐步 dump 出来的；这一步原来是缺的，
    viewer 跑 150s 一直卡在「要与"捎话"共享屏幕吗？… 取消 | 下一步」）：

      1. app 内弹窗      「同意共享」（screen_remote_view_consent_accept）
      2. systemui 对话框 `screen_share_permission_dialog`，模式是一个 **Spinner**
         （`screen_share_mode_options`），默认文案「共享一个应用」⇒ 不换模式的话确认键是
         「下一步」，点下去进的是系统**应用选择器**（选择要分享的应用），不是投屏
      3. 点那个 Spinner ⇒ 下拉只有两项：「共享一个应用」/「共享整个屏幕」
      4. 选「共享整个屏幕」后，确认键变成 **「共享屏幕」**（`android:id/button1`），
         取消键 `button2` 恒为「取消」⇒ 只点确认键，任何「取消/拒绝」都不碰

    所以候选顺序必须是：app 同意 → 系统确认键 → 最后才是模式那两个标签。理由：
      * 选过「共享整个屏幕」之后，**Spinner 自己的文案也变成「共享整个屏幕」**，所以模式标签
        必须排在系统确认键「共享屏幕」**之后** —— 否则每轮都在"点开下拉 / 点已选中的项"之间
        来回打转，永远点不到「共享屏幕」（2026-10-10 现场：连点 8 次，bounds 在两处交替）。
      * 模式标签之间也要分先后：下拉没开时先点 Spinner 的当前文案（「共享一个应用」）把下拉
        打开，下一轮才轮得到下拉项（「共享整个屏幕」）。
    """
    nodes = R.ui_all()
    if not nodes:
        if attempt <= 3:
            R.log("ui-assist 第 %d 次：这次 dump 没解析出节点（系统弹窗可能不在当前窗口）" % attempt)
        return False
    for label in (UI_APP_ACCEPT,) + UI_SYSTEM_ACCEPT + (UI_MODE_SCREEN, UI_MODE_APP):
        for kw in ("text", "desc"):
            node = R.find_node(nodes, **{kw: label})
            if node is None or not node.enabled or not node.center:
                continue
            blob = "%s %s" % (node.text or "", node.desc or "")
            if any(bad in blob for bad in UI_NEVER_TAP):
                continue
            R.log("ui-assist 点 %r bounds=%s center=%s（坐标出自 uiautomator dump）"
                  % (label, node.bounds, node.center))
            R.tap_node(node)
            return True
    if attempt <= 3:
        R.log("ui-assist 第 %d 次：没找到授权按钮；当前可见文本=%s"
              % (attempt, " | ".join(R.ui_texts(nodes)[:12])))
    return False


def _ui_assist_sharer_pass(attempt):
    """--ui-assist（sharer）：打开**本夹具**的会话，点「屏幕协助」把 request 发出去。

    与 viewer 的 ui-assist 同一条纪律：坐标只来自 uiautomator dump，找不到就什么都不点
    （graceful degrade：人还可以自己点）。找行的办法见 WAKE_TEXT 的注释 —— 先按刚发出去
    那条消息的预览文字（WAKE_MARK）找，找不到再按 nick 找，取 **y 最小**（最上面）那一个：
    会话列表按最近消息排序，最上面那行才是"刚收到本夹具消息"的那个会话。
    """
    nodes = R.ui_all()
    if not nodes:
        if attempt <= 3:
            R.log("sharer ui-assist 第 %d 次：这次 dump 没解析出节点" % attempt)
        return False
    # 已经在投屏页上就**什么都别点**：那一页的标题行是「屏幕协助 · <nick>」，含有 NICK，
    # 2026-10-10 sharer run1 的第 2 次 assist 正是把它当成会话行点掉、把投屏页关了的。
    texts = R.ui_texts(nodes)
    if any(("等待对方同意" in t) or ("自动画质" in t) for t in texts):
        if attempt <= 3:
            R.log("sharer ui-assist 第 %d 次：已在投屏页（等对方接单），不再点" % attempt)
        return True
    # 会话列表**会记住滚动位置**：run2 现场 dump 里只剩一行 renba469a，本夹具那条在视口之上。
    R.goto_messages_list()
    R.scroll_list_to_top()
    nodes = R.ui_all() or nodes
    cands = []
    for needle in (WAKE_MARK, NICK):
        for n in nodes:
            blob = "%s %s" % (n.text or "", n.desc or "")
            # NICK 这一档要排掉投屏页标题那种「屏幕协助 · <nick>」形状（它含 NICK 但不含 WAKE_MARK）。
            if needle is NICK and "屏幕协助" in blob and WAKE_MARK not in blob:
                continue
            if needle in blob and n.center and n.enabled:
                cands.append((n.center[1], n.center[0], n, needle))
        if cands:
            break
    if not cands:
        if attempt <= 3:
            R.log("sharer ui-assist 第 %d 次：会话列表里找不到 %r / %r；当前可见文本=%s"
                  % (attempt, WAKE_MARK, NICK, " | ".join(R.ui_texts(nodes)[:12])))
        return False
    cands.sort()
    row = cands[0][2]
    R.log("sharer ui-assist 点会话行 %r bounds=%s center=%s（y 最小 = 最新）"
          % (row.text or row.desc, row.bounds, row.center))
    R.tap_node(row)
    time.sleep(1.2)
    nodes2 = R.ui_all()
    for kw in ("desc", "text"):
        node = R.find_node(nodes2, **{kw: UI_SCREEN_ACTION})
        if node is None or not node.enabled or not node.center:
            continue
        blob = "%s %s" % (node.text or "", node.desc or "")
        if any(bad in blob for bad in UI_NEVER_TAP):
            continue
        R.log("sharer ui-assist 点 %r bounds=%s center=%s（坐标出自 uiautomator dump）"
              % (UI_SCREEN_ACTION, node.bounds, node.center))
        R.tap_node(node)
        return True
    if attempt <= 3:
        R.log("sharer ui-assist 第 %d 次：进了会话页但没找到 %r；当前可见文本=%s"
              % (attempt, UI_SCREEN_ACTION, " | ".join(R.ui_texts(nodes2)[:12])))
    return False


# ---------------------------------------------------------------------------
# 4. 本地自检（--dry-run 只跑这一段：不碰网络、不碰设备、退出码 0）
# ---------------------------------------------------------------------------

def _tcp_frame_selftest():
    """四种 screen TCP 帧的形状 + 4 字节大端长度前缀（带 body 的那帧也要对）。"""
    bad = []
    for obj in ({"type": "screen-open", "from": R.FIXTURE_NODE, "sessionId": "s", "token": "t"},
                {"type": "screen-ready", "sessionId": "s"},
                {"type": "screen-next", "sessionId": "s", "seq": 1}):
        raw = R.tcp_frame(obj)
        (n,) = struct.unpack(">I", raw[:4])
        if n != len(raw) - 4 or R.parse_json(raw[4:]) != obj:
            bad.append(str(obj["type"]))
    body = make_small_jpeg(64, 48)
    head = {"type": "screen-frame", "sessionId": "s", "seq": 1,
            "width": 64, "height": 48, "len": len(body)}
    raw = R.tcp_frame(head, body)
    (n,) = struct.unpack(">I", raw[:4])
    if n != len(raw) - 4 - len(body) or R.parse_json(raw[4:4 + n]) != head:
        bad.append("screen-frame")
    if raw[4 + n:] != body:
        bad.append("screen-frame-body")
    if bad:
        return False, "形状不对：%s" % ",".join(bad)
    return True, "帧类型 %s；前缀 4B 大端；screen-frame 的 %dB 正文紧跟 JSON" % (
        ",".join(SCREEN_FRAME_TYPES), len(body))


def _jpeg_selftest():
    """夹具自己造的假 JPEG 必须过手机侧那道走查（ScreenImageMetadata）。"""
    bad = []
    for w in (64, 72, 80, 88, 96):
        data = make_small_jpeg(w, 48)
        if data[:2] != b"\xff\xd8" or data[-2:] != b"\xff\xd9":
            bad.append("%dx48:SOI/EOI" % w)
            continue
        size = jpeg_sof_size(data)
        if size != (w, 48):
            bad.append("%dx48:SOF=%r" % (w, size))
            continue
        if (len(data) > SCREEN_MAX_FRAME_BYTES or w > SCREEN_MAX_EDGE
                or w * 48 > SCREEN_MAX_PIXELS):
            bad.append("%dx48:超限" % w)
    if bad:
        return False, "不过走查：%s" % "; ".join(bad)
    return True, "5 种宽度都过走查（SOI + SOF0 的 w/h + EOI，单帧 %dB）" % len(make_small_jpeg(64, 48))


def _local_checks(args):
    """不碰网络、不碰设备的断言。真机跑也先跑这一段（读数进同一条 result 行）。"""
    phase("本地自检（不碰网络、不碰设备）")

    # 共享库自己的契约自检：信封形状、profile 长度硬上限、TCP 分帧往返 —— 投屏夹具骑在同一层上。
    R.local_self_checks("screen-local", R.long_text(R.TEXT_UDP_LIMIT + 400),
                        nick=NICK, tcp_port=args.tcp_port, host=args.host_ip,
                        caps=FIXTURE_CAPS)

    prof = R.profile_caps(nick=NICK, caps=FIXTURE_CAPS, tcp_port=args.tcp_port,
                          host=args.host_ip)
    R.check("fixture-profile-declares-rv1-rvs1",
            R.CAP_REMOTE_VIEW in prof["caps"] and R.CAP_REMOTE_SHARE in prof["caps"],
            "caps=%s（缺 rv1 手机不给我们「屏幕协助」入口；缺 rvs1 我们看不了它的屏）"
            % ",".join(prof["caps"]))
    R.check("fixture-profile-tcp-port-is-listen-port", prof["tcpPort"] == args.tcp_port,
            "profile.tcpPort=%s == 夹具监听端口 --tcp-port=%s（手机当 Viewer 时连它）"
            % (prof["tcpPort"], args.tcp_port))

    sid = str(uuid.uuid4())
    env = R.envelope(R.T_SCREEN, {"op": "request", "sessionId": sid})
    ack = R.ack_env(env)
    R.check("screen-reliable-frames-need-ack", R.T_SCREEN in R.RELIABLE_TYPES,
            "screen 在 RELIABLE_TYPES 里（不在就会被手机按 ackRetrySchedule 重传到入队）")
    R.check("screen-ack-for-matches-envelope-id",
            ack.get("type") == R.T_ACK
            and (ack.get("payload") or {}).get("ackFor") == env["id"],
            "ack.payload.ackFor == screen 信封 id（回错 id 等于没回）")
    R.check("screen-session-id-is-strict-uuid4", uuid.UUID(sid).version == 4,
            "手机端 isScreenSessionId 只收严格 UUIDv4（RelayControllerScreen.kt:78）；sid=%s" % sid)

    ok, detail = _tcp_frame_selftest()
    R.check("screen-tcp-frame-shapes", ok, detail)

    ok, detail = _jpeg_selftest()
    R.check("screen-jpeg-fixture-passes-phone-walk", ok, detail)

    # 把 PARTIAL → FAIL 的映射本身钉住：它是本文件唯一的语义分歧，不能被后来的改动悄悄放开。
    R.check("screen-partial-maps-to-fail",
            (not frames_ok(1, FRAMES_VIEWER_MIN)
             and not frames_ok(FRAMES_VIEWER_MIN - 1, FRAMES_VIEWER_MIN)
             and frames_ok(FRAMES_VIEWER_MIN, FRAMES_VIEWER_MIN)
             and not frames_ok(0, FRAMES_SHARER_MIN)
             and frames_ok(FRAMES_SHARER_MIN, FRAMES_SHARER_MIN)
             and frames_check_name(3, FRAMES_VIEWER_MIN) == "frames-short(3/10)"),
            "0 < 帧 < 阈值 一律 FAIL（原件的 PARTIAL 不许当 PASS）：frames-short(3/%d) / "
            "达标 frames-ok(%d/%d)；sharer 阈值 %d"
            % (FRAMES_VIEWER_MIN, FRAMES_VIEWER_MIN, FRAMES_VIEWER_MIN, FRAMES_SHARER_MIN))


# ---------------------------------------------------------------------------
# 5. 真机路径
# ---------------------------------------------------------------------------

def run(args):
    _local_checks(args)

    if args.dry_run:
        print("\n[dry-run] 不碰网络、不碰设备：跳过 adb、跳过握手/收发。", flush=True)
        print("[dry-run] 真机将执行（--mode %s）：%s" % (args.mode, _PLAN[args.mode]), flush=True)
        print("[dry-run] 结尾恰好一行 result；帧数不足一律 FAIL —— frames-short(<实测>/<阈值>)，"
              "没有 PARTIAL 第三态。", flush=True)
        return 0

    phase("设备与环境")
    R.require_device()
    pid = R.app_pid()
    R.log("app.relay pid=%s" % (pid or "(not running)"))
    # 无论进程在不在都拉到前台：① 后台 app 会被 Android 冻结（App Standby/Doze）⇒ UDP 收不到，
    # 握手必失败（2026-10-10 现场：pid 在、人停在启动器上，15s 一条 alive 都没有）；
    # ② viewer 的「同意共享」弹窗也要 app 在前台才会出现。
    R.ensure_app_foreground()

    phase("起对端（UDP 握手 + 可靠帧 ACK；--mode %s）" % args.mode)
    peer = R.UdpPeer(args.phone_ip, args.phone_udp, nick=NICK, caps=FIXTURE_CAPS,
                     tcp_port=args.tcp_port, host=args.host_ip).start()
    R.log("UDP 绑定 :%d；夹具 nodeId=%s nick=%s caps=%s" % (
        peer.port, R.FIXTURE_NODE, NICK, ",".join(FIXTURE_CAPS)))
    try:
        return _body(args, peer)
    finally:
        peer.stop()


def _body(args, peer):
    seen = set()
    budget = _budget(args, HANDSHAKE_SECS)
    phase("握手（定向 entry，带 probeId ⇒ 手机固定 1s 回 alive）")
    prof = peer.handshake(secs=budget, probe=True)
    R.check("handshake-alive", prof is not None,
            "拿到手机 alive 里的 profile" if prof else
            "%ds 内没握上手：手机没在线/不在同网段，或整帧被拒（profile.host 为空、ver > 16 字符）"
            % int(budget))
    if prof is None:
        if args.mode != "watch":
            check_frames(args.mode, 0, _frames_min(args.mode),
                         "没握上手，投屏信令发不出去也收不到")
        return None

    caps = prof.get("caps") or []
    R.log("手机 profile nick=%r platform=%s tcpPort=%s ver=%r caps=%s" % (
        prof.get("nick"), prof.get("platform"), prof.get("tcpPort"),
        prof.get("ver"), ",".join(caps)))

    if args.mode == "watch":
        return _run_watch(prof, caps)
    if args.mode == "viewer":
        return _run_viewer(args, peer, prof, seen)
    return _run_sharer(args, peer, prof, seen)


def _frames_min(mode):
    return FRAMES_SHARER_MIN if mode == "sharer" else FRAMES_VIEWER_MIN


def _run_watch(prof, caps):
    """watch：原件只报告不断言，契约要求结论行且检查数 > 0，所以把报告升级成两条断言。

    手机端 rv1/rvs1 由 RelaySettings.caps(remoteView=…, remoteShare=…) 动态声明 —— 设置里
    没打开「屏幕协助」就不声明，那正是这两条断言要抓的现状（不是缺陷，是能力没开）。
    """
    R.check("watch-phone-declares-rv1", R.CAP_REMOTE_VIEW in caps,
            "caps=%s：手机没声明 rv1 ⇒ 它不接受别人看它的屏（设置里「屏幕协助」没开？）"
            % ",".join(caps))
    R.check("watch-phone-declares-rvs1", R.CAP_REMOTE_SHARE in caps,
            "caps=%s：手机没声明 rvs1 ⇒ 它不会把自己的屏共享出去" % ",".join(caps))
    return None


def _wait_accept(args, peer, sid, seen):
    """viewer：等手机的 screen.accept。返回 (token, status)，status ∈ accept/reject/end/timeout。

    原件这里直接 recvfrom 且与 listen_loop 抢同一个 socket；现在统一从 UdpPeer 的 inbox 取。
    --ui-assist 每 1.5s dump 一次 UI 代点（坐标来自 bounds），默认不开、等人点。
    """
    deadline = time.monotonic() + float(args.secs)
    next_ui = 0.0
    attempt = 0
    while time.monotonic() < deadline:
        for env in _drain_screen(peer, seen):
            p = env.get("payload") or {}
            if p.get("sessionId") != sid:
                R.log("phone-screen(别的会话) %s" % json.dumps(p, ensure_ascii=False)[:120])
                continue
            R.log("phone-screen %s" % json.dumps(p, ensure_ascii=False))
            op = p.get("op")
            if op == "accept":
                return p.get("token"), "accept"
            if op in ("reject", "end"):
                return None, op
        if args.ui_assist and time.monotonic() >= next_ui:
            attempt += 1
            _ui_assist_pass(attempt)
            next_ui = time.monotonic() + 1.5
        time.sleep(0.2)
    return None, "timeout"


def _run_viewer(args, peer, prof, seen):
    """viewer：手机当 Sharer。发布 request → 等 accept → 连手机 TCP 收流。"""
    sid = str(uuid.uuid4())
    phase("viewer：请求看手机的屏（手机当 Sharer）")
    peer.send(peer.env(R.T_SCREEN, {"op": "request", "sessionId": sid}))
    R.log("UDP → screen.request sessionId=%s（手机侧 60s 内没人同意会回 reject(timeout)）" % sid)

    token, status = _wait_accept(args, peer, sid, seen)
    if status != "accept" or not token:
        R.check("viewer-consent-accept", False,
                "%ds 内没等到 screen.accept（收到 %s）；人工：在手机弹窗点「%s」，"
                "再在系统投屏授权上点确认（--ui-assist 可让夹具按 uiautomator bounds 代点）"
                % (int(float(args.secs)), status, UI_APP_ACCEPT))
        check_frames("viewer", 0, FRAMES_VIEWER_MIN, "会话没起来（%s），没进 TCP 收流" % status)
        return None
    R.check("viewer-consent-accept", True, "收到 screen.accept，token=%s…" % token[:8])

    out = pump_reader(args, prof, sid, token)
    note = "TCP 收到 %d 帧、%d 种尺寸" % (out["frames"], out["distinct"])
    if out["error"]:
        note += "；%s" % out["error"]
    check_frames("viewer", out["frames"], FRAMES_VIEWER_MIN, note)
    return None


def pump_reader(args, prof, session_id, token):
    """原件 pump_reader：连手机 TCP → screen-open → 读 ready → 逐帧 next/frame。

    上限与预算照抄原件（60 帧 / 30s）。返回值带上错误原因，好让 FAIL 那条 check 说明白
    是"没连上"、"没 ready"还是"收到几帧就断了"。
    """
    port = int(prof.get("tcpPort") or R.TCP_PORT_DEFAULT)
    out = {"frames": 0, "distinct": 0, "sizes": [], "error": ""}
    deadline = time.monotonic() + READER_BUDGET_S
    R.log("TCP → %s:%d screen-open（手机 profile.tcpPort）" % (args.phone_ip, port))
    try:
        conn = socket.create_connection((args.phone_ip, port), timeout=10.0)
    except OSError as e:
        out["error"] = "TCP 连接失败：%s" % e
        R.check("tcp-connect", False, out["error"])
        return out
    R.check("tcp-connect", True, "%s:%d" % (args.phone_ip, port))
    try:
        conn.sendall(R.tcp_frame({"type": "screen-open", "from": R.FIXTURE_NODE,
                                  "sessionId": session_id, "token": token}))
        head = R.read_tcp_frame(conn, deadline)
        R.log("TCP ← %s" % json.dumps(head, ensure_ascii=False)[:160])
        ready_ok = bool(head) and head.get("type") == "screen-ready" \
            and head.get("sessionId") == session_id
        R.check("tcp-ready-frame", ready_ok,
                "type=screen-ready 且 sessionId 一致" if ready_ok
                else "期望 {\"type\":\"screen-ready\",\"sessionId\":…}，实际 %r" % (head,))
        if not ready_ok:
            out["error"] = "没拿到 screen-ready"
            return out
        seq = 0
        sizes = []
        while time.monotonic() < deadline and out["frames"] < READER_MAX_FRAMES:
            seq += 1
            conn.sendall(R.tcp_frame({"type": "screen-next", "sessionId": session_id, "seq": seq}))
            head = R.read_tcp_frame(conn, deadline)
            if head is None:
                out["error"] = "等 screen-frame 超时/断链（已发 next seq=%d）" % seq
                break
            if head.get("type") != "screen-frame":
                out["error"] = "收到非 screen-frame：%s" % json.dumps(head, ensure_ascii=False)[:120]
                break
            body = R.recv_exact(conn, int(head.get("len") or 0), deadline)
            if body is None:
                out["error"] = "screen-frame 正文读不全（len=%s）" % head.get("len")
                break
            n = out["frames"] + 1
            out["frames"] = n
            sizes.append((head.get("width"), head.get("height"), head.get("len")))
            if n in (1, 2, 10, 30):
                R.log("frame#%d %sx%s %sB jpeg-magic=%s" % (
                    n, head.get("width"), head.get("height"), head.get("len"), body[:2].hex()))
    except OSError as e:
        out["error"] = "TCP 异常：%s" % e
    finally:
        try:
            conn.close()
        except OSError:
            pass
    out["sizes"] = sizes
    out["distinct"] = len(set(sizes))
    R.log("frames-total %d distinct-sizes %d sample=%s" % (
        out["frames"], out["distinct"], sizes[:3]))
    return out


def _listen_or_env_error(port):
    """sharer 的 TCP 监听。绑不上是**环境问题**（端口 17879 常被占用）⇒ 退出码 2，不是 FAIL。"""
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind(("0.0.0.0", int(port)))
    except OSError as e:
        srv.close()
        raise R.EnvError("TCP %d 绑不上（端口 17879 已被占用，换 --tcp-port）：%s" % (port, e))
    srv.listen(1)
    R.log("tcp-listen :%d（写进 profile.tcpPort，手机当 Viewer 时连它）" % port)
    return srv


def _run_sharer(args, peer, prof, seen):
    """sharer：手机当 Viewer。等手机的 screen.request → 回 accept → 泵假 JPEG。"""
    phase("sharer：等手机来请求看我们的屏（手机当 Viewer）")
    # 先"敲门"：发一条消息让手机为本夹具（当前这个 nodeId）建/顶起会话 —— 理由见 WAKE_TEXT。
    # 可靠帧，手机收到会回 ACK；这一步同时证明 UDP 到手机的链路是通的（后面 request 也走它）。
    env = peer.send_text(WAKE_TEXT)
    R.log("UDP → msg %r id=%s（nodeId=%s；把本夹具的会话顶到列表最上面）"
          % (WAKE_TEXT, env.get("id"), R.FIXTURE_NODE))
    time.sleep(1.0)
    if args.ui_assist:
        _ui_assist_sharer_pass(1)

    sid = None
    deadline = time.monotonic() + float(args.secs)
    next_ui = 0.0
    attempt = 1
    while time.monotonic() < deadline and sid is None:
        for env in _drain_screen(peer, seen):
            p = env.get("payload") or {}
            R.log("phone-screen %s" % json.dumps(p, ensure_ascii=False))
            if p.get("op") == "request":
                sid = p.get("sessionId")
        if sid is None and args.ui_assist and time.monotonic() >= next_ui:
            attempt += 1
            _ui_assist_sharer_pass(attempt)
            next_ui = time.monotonic() + 3.0
        if sid is None:
            time.sleep(0.2)
    if not sid:
        R.check("sharer-phone-screen-request", False,
                "%ds 内没收到手机的 screen.request；人工：在手机聊天页（对端 %r）点「屏幕协助」"
                % (int(float(args.secs)), NICK))
        check_frames("sharer", 0, FRAMES_SHARER_MIN, "会话没起来，没进 TCP 泵流")
        return None
    R.check("sharer-phone-screen-request", True, "sessionId=%s" % sid)

    # 先绑监听再回 accept：原件把 bind 放在 accept 之后（真机上手机处理 accept 有几十毫秒，
    # 来得及），提前绑只是为了"端口被占"能在发 accept 之前就报成环境错误。
    srv = _listen_or_env_error(args.tcp_port)
    try:
        token = uuid.uuid4().hex
        peer.send(peer.env(R.T_SCREEN, {"op": "accept", "sessionId": sid, "token": token}))
        R.log("UDP → screen.accept sessionId=%s token=%s…" % (sid, token[:8]))
        frames = pump_sharer(args, srv, sid, token)
    finally:
        try:
            srv.close()
        except OSError:
            pass
    check_frames("sharer", frames, FRAMES_SHARER_MIN,
                 "TCP 已按 screen-next 泵出 %d 帧假 JPEG" % frames)
    return None


def pump_sharer(args, srv, session_id, token):
    """原件 pump_sharer：等手机 screen-open（核对 token/sessionId）→ ready → 按 next 泵帧。

    为什么不用共享库的 TcpFrameServer：它只认 msg / msg-ack，screen-* 四种帧它既不解也不回，
    用它当 sharer 会表现为"手机连上了、一帧都没有"。所以这段 TCP 服务端照抄原件自己起，
    分帧仍走 R.tcp_frame / R.read_tcp_frame。
    """
    accept_budget = _budget(args, SHARER_ACCEPT_TIMEOUT_S)
    srv.settimeout(accept_budget)
    try:
        conn, addr = srv.accept()
    except socket.timeout:
        R.check("sharer-tcp-accept", False,
                "%ds 内手机没连进来（没收到 screen-open）" % int(accept_budget))
        return 0
    R.check("sharer-tcp-accept", True, "来自 %s" % (addr,))
    R.log("tcp-accepted %s" % (addr,))

    pump_deadline = time.monotonic() + _budget(args, SHARER_PUMP_BUDGET_S)
    sent = 0
    try:
        head = R.read_tcp_frame(conn, pump_deadline)
        R.log("TCP ← %s" % json.dumps(head, ensure_ascii=False)[:160])
        open_ok = bool(head) and head.get("type") == "screen-open" \
            and head.get("token") == token and head.get("sessionId") == session_id
        R.check("sharer-open-frame-matches-accept", open_ok,
                "screen-open 的 token/sessionId 与 accept 发出去的一致" if open_ok
                else "open-mismatch：期望 token=%s…/sessionId=%s，实际 %r"
                     % (token[:8], session_id, head))
        if not open_ok:
            return 0
        conn.sendall(R.tcp_frame({"type": "screen-ready", "sessionId": session_id}))
        while sent < SHARER_MAX_FRAMES and time.monotonic() < pump_deadline:
            nxt = R.read_tcp_frame(conn, pump_deadline)
            if nxt is None:
                R.log("pump-end 等 screen-next 超时/断链")
                break
            if nxt.get("type") != "screen-next" or nxt.get("seq") != sent + 1:
                R.log("unexpected %s（手机侧按 next 拉帧，seq 必须从 1 连续递增）"
                      % json.dumps(nxt, ensure_ascii=False)[:120])
                break
            w = 64 + (sent % 5) * 8
            j = make_small_jpeg(w, 48)
            conn.sendall(R.tcp_frame({"type": "screen-frame", "sessionId": session_id,
                                      "seq": nxt["seq"], "width": w, "height": 48,
                                      "len": len(j)}, j))
            sent += 1
            if sent in (1, 20):
                R.log("pump#%d frame-sent %dB %dx48" % (sent, len(j), w))
    except OSError as e:
        R.log("pump-end %r" % (e,))
    finally:
        try:
            conn.close()
        except OSError:
            pass
    R.log("frames-sent %d" % sent)
    return sent


# ---------------------------------------------------------------------------
# 6. CLI
# ---------------------------------------------------------------------------

def extra_args(ap):
    ap.add_argument("--mode", choices=["viewer", "sharer", "watch"], default="watch",
                    help="原件的位置参数 mode：viewer=看手机屏 / sharer=手机看我们的屏 / "
                         "watch=只握手看 caps；默认 %(default)s（run_all.py 不传 --mode，"
                         "默认必须是那个不需要人工介入的模式）")
    ap.add_argument("--tcp-port", type=int, default=R.TCP_PORT_DEFAULT,
                    help="夹具 TCP 监听端口（写进 profile.tcpPort），默认 %(default)s；"
                         "17879 被其他进程占用时换一个")
    ap.add_argument("--ui-assist", action="store_true",
                    help="viewer 模式：用 uiautomator dump 的 bounds 代点「%s」/系统投屏授权，"
                         "无人值守时用；默认关（原件是人在手机上点，坐标绝不目测）"
                         % UI_APP_ACCEPT)


if __name__ == "__main__":
    sys.exit(R.main_wrapper(run, description=__doc__, extra_args=extra_args))
