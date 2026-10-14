#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""节点发现 e2e 真机夹具：mDNS 广播/浏览、手动节点、资料刷新、告别帧。

覆盖 `docs/plans/2026-10-09-relay-e2e-coverage-matrix.md` §2 B 段「发现与节点」R16–R27：

  R16 首启绑定端口 + 状态卡 + 同步条   `CA feature/nodes/NodesScreen.kt:212-248`  entry → alive      A1
  R17 mDNS 服务广播                    `CA feature/nodes/NodesScreen.kt:237`      （Bonjour 层）     A3 / I3
  R18 mDNS 浏览发现（局域网节点列表）  `CA feature/nodes/NodesScreen.kt:299-310`  entry / alive      A3
  R19 手动填地址添加节点               `CA feature/nodes/NodesScreen.kt:387`      entry              A4
  R20 已保存节点的在线/离线自己变      `CA feature/nodes/NodesScreen.kt:839-841`  alive/exit/offlineAfter  A4 / A12
  R21 清理不可达                       `CA feature/nodes/NodesScreen.kt:363`      （无）             A4
  R22 同一台机器不同时出现在两组       `CA feature/nodes/NodesScreen.kt:310/349`  （无）             A4
  R23 对端改昵称/资料后本端跟上        `CA feature/chat/ChatScreen.kt:648/659`    presence → entry   A13c
  R24 节点 ID 稳定派生 8 位 hex        `CA feature/settings/SettingsScreen.kt:492`（无）            —
  R25 VPN 警告横幅                     `CA feature/nodes/NodesScreen.kt:280`      （无）             —
  R26 对端告别帧 exit 立即标离线       （无独立入口，桌面退出时）                 exit               A12 / C4
  R27 手机端不告别（90 s 超时兜底）    `CA app/RelayController.kt`（无生命周期钩子）offlineAfter=90s  A12

矩阵给 R24 引的 `NodesScreen.kt:677 nodes_recognized` 是「添加节点」弹层里「已识别」那张
卡的标题，与节点 ID 派生无关（真实机制是 `RelayUiState.kt:88 displayName` 的
`nodeId.take(8)` 兜底 + `SettingsScreen.kt:492` 的「节点 xxxxxxxx」芯片），本夹具按后者验。
矩阵给 R23 引的 `ChatScreen.kt:479` 也已经漂到 648/659（`peer.displayName`）。

## 抄进来的验收条目原文（ACCEPTANCE.md）

- **A1**（`:50-60`，已跑 2026-10-07）：装包点图标；期望「节点」页状态**正在监听端口…** →
  本机地址出现 `192.168.x.x:17878`、状态变「正常」、顶部同步条从「广播已停」变绿；
  反例是停在「启动失败 · 见下方提示」并写出真原因（`UDP 端口 17878 没能监听
  （Address already in use）`），点「重新监听」应能起来。
- **A3**（`:76-80`）：两台手机都开着捎话时几秒内各自出现在对方「局域网发现」里；已知未验：
  手机端只做发送侧广播、不声明 `CHANGE_WIFI_MULTICAST_STATE`，省电模式会过滤广播帧
  ⇒ 要锁屏 2 分钟复验。
- **A4**（`:82-85`）：「节点 → 填对方地址」输 `ip` 或 `ip:port`（默认 17878）；期望保存后
  出现在「已保存的节点（管理）」里、在线/离线状态会自己变、**同一台机器不会同时出现在
  「局域网发现」和「已保存」两组里**。
- **A12**（`:154-158`）：直接杀手机端进程，对端约 **90 秒**（`Timings.offlineAfter`）内变
  离线；正常退出会尽力广播 `exit`，但**手机端没有生命周期钩子**，所以手机侧这条告别帧
  实际发不出去 —— 90 秒超时才是手机的真实路径，**这是已知现状不是缺陷**。
- **A13c**（`:184-194`，已跑 2026-10-07）：对端改显示名（`profileRev`+1）、不发消息只等
  心跳，期望几十秒内（presence 周期 + 节流 10 秒）换过来，不需重启/重新握手。
  真机证据 `EV profile-refresh-android-restart.txt`：冷启动 6 秒行标题是 `aeb471f0`
  （nodeId 前 8 位），46 秒后变成 `证据节点`。
- **C4**（`:461-466`）：桌面正常退出后手机端**立即**把桌面标成离线，不用等 90 秒
  （`exit` 广播生效）；反例是仍要等约 90 秒。
- **I3**（`:322-340`，广播与浏览两半都通过）：主机 `dns-sd -B _relay._udp local.` 列出
  手机/模拟器的服务实例；`dns-sd -L <label> _relay._udp` 解析到 host:port；浏览那半的
  **负控**是主机跑 `dns-sd -R "fake-bonjour-only" _relay._udp local. 17999`（**只有
  Bonjour 广播、零 UDP presence**），几秒后节点页「局域网发现」出现 `fake-bonjour-only`
  带可点「连接」；对照 `TOTAL_UDP_PACKETS_ON_17999=0` 证明只能是被 mDNS 发现的。

## 这个夹具装成什么

一台**桌面端**（`platform=linux`，协议里没有 mobile）：绑一个**临时 UDP 端口**当对端，
握手上线、被手动加进手机节点表、改自己的昵称触发资料刷新、最后发 `exit` 告别。
手机侧只被 adb 驱动（uiautomator dump 求坐标，绝不目测）。

    # 不插手机也能确认自己没写崩（不碰网络、不碰设备，退出码 0）：
    python3 scripts/e2e/e2e_discovery.py --dry-run

    # 真机（默认 --secs 300，发现层要等 presence 周期与 mDNS 收敛）：
    python3 scripts/e2e/e2e_discovery.py --phone 192.168.2.22 --host-ip 192.168.2.82

    # 只验协议层（不注入按键、不点按）：
    python3 scripts/e2e/e2e_discovery.py --skip-ui

    # 额外验 90 s 存在性超时（要 --secs >= 150，默认不跑）：
    python3 scripts/e2e/e2e_discovery.py --check-sweep --secs 200

退出码 0=PASS / 1=FAIL / 2=用法或环境错误；结尾恰好一行 `result ...`。

## 已知的覆盖边界（别把这几条算成"验过了"）

1. **R18 的广播侧**（手机收 UDP 广播帧 → 局域网发现）本夹具造不出来：夹具往
   `255.255.255.255:17878` 广播的话，得先占用主机 17878（Teahouse.app 常占），而且
   手机端省电模式会过滤广播帧（A3 的已知未验项）。这里验的是**浏览侧**（mDNS 负控，
   与 I3 同一手法）与**单播侧**（手动节点）。广播侧仍要人工锁屏 2 分钟复验。
2. **R27 的 90 s 兜底**在夹具这一侧不可观测：夹具没有 `PeerRegistry`/`sweep()`，
   它只能验「手机端**不**发 `exit`」。90 s 翻转那半由 `e2e_messaging.py`（等 90 s）
   按矩阵 §9 负责；本夹具的 `--check-sweep` 验的是同一常量在**手机侧**的表现
   （夹具装死 → 手机 90 s 后把夹具标离线）。
3. **R25 的正面分支**要手机上真挂着 VPN 才会出现。夹具先读系统状态（`ip link` 的
   `tun*/ppp*/wg*`），再断言「横幅出现 ⟺ 有 VPN 接口」——没 VPN 时只能验"不该出现"。
4. **R17 的 `dns-sd` 读数**依赖 macOS 自带 Bonjour CLI；`--skip-ui` 时跳过。
"""

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay_e2e as R  # noqa: E402


# ---------------------------------------------------------------------------
# 常量：能抄的抄自源码，抄不动的一律在这里写清出处
# ---------------------------------------------------------------------------

# 手机侧 mDNS 服务类型（AndroidMdnsAdvertiser.SERVICE_TYPE，带尾点）；
# NsdManager.discoverServices / dns-sd 那一侧**不能**带尾点（AndroidMdnsBrowser.kt:173）。
MDNS_SERVICE_TYPE = "_relay._udp."
MDNS_BROWSE_TYPE = "_relay._udp"
MDNS_DOMAIN = "local."
MDNS_LABEL_MAX_BYTES = 63          # MdnsNaming.MDNS_LABEL_MAX_BYTES
MDNS_TOOL = "dns-sd"               # macOS 自带；没有就 EnvError（退出码 2，不是 FAIL）

# Discovery.PROFILE_REFRESH_MIN_INTERVAL_MS（决议 #317：每节点 10 秒）
PROFILE_REFRESH_MS = 10_000
# Timings.presenceInterval
PRESENCE_INTERVAL_MS = 30_000

# I3 的负控名：只有 Bonjour 广播、**零 UDP presence**，端口上没有任何监听者。
FAKE_MDNS_NAME = "fake-bonjour-only"
FAKE_MDNS_PORT = 17999

# R21 的「从来没有回应过」那一行：用 RFC 5737 TEST-NET-1，保证不是局域网里真存在的设备
# （192.168.2.254 有可能是别人的路由器/手机，误判成"有过回应"就会让这条断言假失败）。
BOGUS_PEER_DEFAULT = "192.0.2.99"
BOGUS_PEER_PORT = 17878

# 界面文案（composeResources/values/strings.xml）。这里只列夹具要找的那些，
# 每一条都带 key 名：改文案时按 key 去 strings.xml 改，别只改这里。
S_TITLE = "节点"                                # disc_title / screen_discovery
S_LOCAL_CARD = "本机"                           # nodes_local_card
S_LOCAL_ADDRESS = "本机地址"                     # nodes_local_address
S_LOCAL_DISCOVERY = "发现方式"                   # nodes_local_discovery
S_DISCOVERY_ON = "mDNS _relay._udp · 每 5 秒"    # nodes_discovery_on
S_LOCAL_STATUS = "状态"                         # nodes_local_status
S_STATE_OK = "正常 · 本机防火墙已放行"            # nodes_state_ok
S_STATE_STARTING = "正在监听端口…"                # nodes_state_starting
S_STATE_FAILED = "启动失败 · 见下方提示"           # nodes_state_failed
S_RETRY_START = "重新监听"                       # disc_retry_start
S_SYNCBAR_ON = "mDNS 广播中 · UDP "               # nodes_syncbar
S_SYNCBAR_OFF = "广播已停 · UDP "                 # nodes_syncbar_down
S_PEERS_ONLINE = "在线节点"                      # disc_peers_online
S_GROUP_LAN = "局域网发现"                       # nodes_group_lan
S_GROUP_SAVED = "已保存的节点（管理）"             # nodes_group_saved
S_PROFILE_CHIP = "资料 v"                       # SettingsScreen.kt:297 首页那张资料卡的行尾标签（整行可点）
S_PROFILE_TITLE = "我的资料"                     # SettingsScreen.kt:277 昵称为空时的兜底标题
S_ADD_MANUAL = "填对方地址"                       # nodes_add_manual_title
S_BACK = "返回"                                  # action_back（设置子页的返回键）
S_FIELD_HOST = "对方地址"                        # nodes_scan_field_host
S_PORT_LABEL = "端口"                            # nodes_port_label
S_RECOGNIZED = "已识别"                          # nodes_recognized
S_CONNECT = "连接"                               # nodes_scan_connect / disc_connect
S_MANUAL_ONLINE = "在线"                         # disc_manual_online
S_MANUAL_OFFLINE = "离线"                        # disc_manual_offline
S_MANUAL_UNREACHABLE = "没有回应"                 # disc_manual_unreachable
S_UNREACHABLE_HINT = "这个地址从来没有回应过"       # disc_manual_unreachable_hint
S_CLEANUP = "清理没有回应的"                      # disc_cleanup_unreachable
S_CLEANUP_DONE = "已清理"                        # disc_cleanup_done
S_REMOVE = "删除"                                # common_remove
S_VPN_WARNING = "检测到 VPN"                     # disc_vpn_warning
S_TAB_MESSAGES = "消息"                          # screen_conversations
S_TAB_NODES = "节点"                             # screen_discovery
S_TAB_SETTINGS = "设置"                          # screen_settings
S_SETTINGS_MARKER = "资料 v"                     # SettingsScreen.kt:487 「资料 v<n>」
S_NODE_ID_CHIP_RE = re.compile(r"^节点 ([0-9a-fA-F]{8})$")   # SettingsScreen.kt:492

STATUS_WORDS = (S_MANUAL_ONLINE, S_MANUAL_OFFLINE, S_MANUAL_UNREACHABLE)


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------

def phase(title):
    print("\n== %s ==" % title, flush=True)


def _budget(args, cap):
    """把 --secs 当成全局预算：返回不超过剩余预算的超时。"""
    return max(1.0, min(cap, float(args.secs)))


def uniq_nick(prefix="E2E"):
    """ASCII 昵称：`input text` 注不了中文，而这个名字要跟界面上的字对账。"""
    nick = prefix + R.uniq_marker("", 4)
    if len(nick) > R.LIMITS["nick"]:
        nick = nick[:R.LIMITS["nick"]]
    return nick


def is_uuid4(s):
    """UUIDv4：8-4-4-4-12，第 13 位是 4，第 17 位是 89ab。"""
    return bool(re.match(
        r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}"
        r"-[0-9a-fA-F]{12}$", s or ""))


def truncate_utf8(s, max_bytes):
    """按**字节**预算截断，不切碎一个码点（MdnsNaming.truncateUtf8 的镜像）。"""
    out = []
    used = 0
    for ch in s:
        b = len(ch.encode("utf-8"))
        if used + b > max_bytes:
            break
        out.append(ch)
        used += b
    return "".join(out)


def mdns_label(nick, node_id, max_bytes=MDNS_LABEL_MAX_BYTES):
    """`MdnsNaming.mdnsServiceLabel` 的镜像：`<昵称按字节截断>-<nodeId 字母数字前 6>`。"""
    suffix = "".join(c for c in (node_id or "") if c.isalnum())[:6] or "node"
    budget = max(0, max_bytes - len(suffix.encode("utf-8")) - 1)
    prefix = truncate_utf8(nick or "relay", budget) or "relay"
    return "%s-%s" % (prefix, suffix)


def dns_sd_unescape(s):
    """`dns-sd` 打印的实例名是转义过的：空格 `\\032`、UTF-8 字节 `\\228\\184\\173`。"""
    if not s:
        return s
    out = bytearray()
    i = 0
    while i < len(s):
        ch = s[i]
        if ch == "\\" and i + 3 < len(s) + 1:
            m = re.match(r"\\(\d{3})", s[i:])
            if m:
                out.append(int(m.group(1)) & 0xFF)
                i += 4
                continue
            if i + 1 < len(s):          # `\ ` 这类单字符转义
                out.extend(s[i + 1].encode("utf-8"))
                i += 2
                continue
        out.extend(ch.encode("utf-8"))
        i += 1
    return out.decode("utf-8", "replace")


def saved_row_text(host, port):
    """`ManualPeerRow` 渲染的那一行：`${entry.host}:${entry.port}`。"""
    return "%s:%d" % (host, int(port))


# ---------------------------------------------------------------------------
# macOS Bonjour 读数（R17 广播 / R18 浏览）
# ---------------------------------------------------------------------------

def _run_dns_sd(args, secs):
    proc = subprocess.Popen([MDNS_TOOL] + args, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    time.sleep(max(1.0, secs))
    proc.terminate()
    try:
        out = proc.communicate(timeout=4)[0]
    except subprocess.TimeoutExpired:
        proc.kill()
        out = proc.communicate()[0]
    return out or ""


def dns_sd_browse(secs):
    """`dns-sd -B _relay._udp local.` 跑 secs 秒，返回 [(Add|Rmv, 实例名)]。"""
    out = _run_dns_sd(["-B", MDNS_BROWSE_TYPE, MDNS_DOMAIN], secs)
    found = []
    for line in out.splitlines():
        if "_relay._udp" not in line:
            continue
        m = re.search(r"\b(Add|Rmv)\b", line)
        if not m:
            continue
        idx = line.find("_relay._udp")
        rest = line[idx + len("_relay._udp"):].lstrip(". \t")
        if rest.strip():
            found.append((m.group(1), rest.strip()))
    return found, out


def dns_sd_resolve(label, secs=6.0):
    """`dns-sd -L <label> _relay._udp local.` → (host, port) 或 (None, None)。"""
    out = _run_dns_sd(["-L", label, MDNS_BROWSE_TYPE, MDNS_DOMAIN], secs)
    m = re.search(r"can be reached at (\S+?):(\d+)", out)
    if not m:
        return None, None, out
    return m.group(1), int(m.group(2)), out


class MdnsRegistrar(object):
    """`dns-sd -R` 注册一个**只有 Bonjour、零 UDP** 的服务（I3 的负控）。"""

    def __init__(self, name, port):
        self.name = name
        self.port = int(port)
        self.proc = None

    def start(self):
        self.proc = subprocess.Popen(
            [MDNS_TOOL, "-R", self.name, MDNS_BROWSE_TYPE, MDNS_DOMAIN, str(self.port)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return self

    def stop(self):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.proc.kill()
            self.proc = None

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()
        return False


# ---------------------------------------------------------------------------
# UI：坐标一律来自 uiautomator dump 的 bounds
# ---------------------------------------------------------------------------

def _screen_brief(nodes, limit=12):
    """屏幕上的可见文本（失败时贴进 detail/日志：真机远程排障时这是唯一能看到的东西）。"""
    out = [(n.text or "") for n in nodes if (n.text or "").strip()]
    return " / ".join(out[:limit]) or "（无文本节点）"


def page_bounds(nodes):
    """dump 出来的可见区域（min/max of every node's bounds）—— 滑动的坐标系。"""
    xs1, ys1, xs2, ys2 = [], [], [], []
    for n in nodes:
        if not n.bounds:
            continue
        xs1.append(n.bounds[0])
        ys1.append(n.bounds[1])
        xs2.append(n.bounds[2])
        ys2.append(n.bounds[3])
    if not xs1:
        return None
    return min(xs1), min(ys1), max(xs2), max(ys2)


def scroll_page(down=True, nodes=None, step_div=3):
    """滑动一屏。起止点由 dump 的可见区域算出来（不目测）。

    **起手点不能贴着底边**：页面最底下是导航栏（`消息/文件柜/节点/Agent/设置`，
    标签在 y≈2191-2231），从那里按下去的那一下 swipe 会被它吃掉、页面纹丝不动。
    `page_bounds` 把系统栏也算进可见区域（dump 里是 `(0, 0, 1080, 2400)`），
    原来的 7/8 处 = y2100 正好落在导航栏的触摸区 —— 2026-10-09 run2 与 run3 的
    R19「节点页上找不到 '填对方地址' 入口」就是这么来的（那一行在页面最下面，
    靠 `scan_page_for` 滑下去找，而三次 swipe 一次都没滚）。真机实测：
    1920→480 能滚，「添加节点 / 已保存的节点（管理）/ 填对方地址」立刻出现；
    2100→300 不能滚。所以起手压到 4/5。

    **一下滑一屏会滑过整段内容**：原来的 1920→480 是 1440px，而节点页的
    「局域网发现」卡片（mDNS 行 + 节点行）在首屏折叠线以下只有一屏高的窗口 ——
    真机复现（`/tmp/probe_r18e.py`，2026-10-09 run5）：step 0 只能看到节标题
    `局域网发现（3）`；按下一次 1440px 之后，屏上直接是 `已保存的节点（管理）（1）`
    —— 中间那一整张卡片（mDNS 行 `fake-bonjour-only` + `192.168.2.82:17999`）
    **在任何一次 dump 里都没被组合过**，于是 R18 的扫描 25s 也判不到它。
    所以步长压到 1/3 屏（2400/3 = 800px，实测 1900→1100 稳稳落在卡片上），
    由调用方多滑几次补回覆盖：`max_scrolls_plus` 已按 2 倍放宽。
    """
    nodes = nodes if nodes is not None else R.ui_all()
    box = page_bounds(nodes)
    if not box:
        return False
    x1, y1, x2, y2 = box
    span = max(1, y2 - y1)
    step = max(200, span // max(1, int(step_div)))
    x = (x1 + x2) // 2
    if down:
        sy, ey = y1 + span * 4 // 5, y1 + span * 4 // 5 - step
    else:
        sy, ey = y1 + span // 5, y1 + span // 5 + step
    R.adb("shell", "input", "swipe", str(x), str(sy), str(x), str(ey), "300")
    time.sleep(0.5)
    return True


def scroll_to_top(max_scrolls=6):
    """滚回顶部。步长只有 1/3 屏，所以次数比「一屏一步」时要多一倍（6 次 ≈ 2 屏）。"""
    for _ in range(max_scrolls):
        scroll_page(down=False)
    return R.ui_all()


def _settle_mdns_row(node, nodes, tries=3):
    """确保 mDNS 那一行**整行**都在同一屏上（名字 + 它下面的 `host:port`）。

    名字刚冒出视口下沿时，卡片 bounds 被截断，`host:port` 那行**根本不在 dump 里**：
    2026-10-09 真机探针（`/tmp/probe_r18f.py`）读到卡片 `bounds=[42,1854][1038,2064]`、
    名字 `[84,2026][378,2064]`，而 `grep -c 17999 /tmp/r18f.xml` = **0** ——
    run7 的 `R18 mdns-browse-shows-host-and-port` 就是这样红的（detail 是空串）。
    半步下推（span//6 = 400px）把整行挪到屏里；整步 1/3 屏会滑过整张卡片，所以不用它。
    """
    for _ in range(tries):
        if not node:
            return None, nodes
        if str(FAKE_MDNS_PORT) in " ".join(str(t) for t in R.ui_texts(nodes)):
            return node, nodes
        scroll_page(down=True, nodes=nodes, step_div=6)
        time.sleep(0.9)
        nodes = R.ui_all()
        node = R.find_node(nodes, text=FAKE_MDNS_NAME, exact=False)
    complete = (node is not None
                and str(FAKE_MDNS_PORT) in " ".join(str(t) for t in R.ui_texts(nodes)))
    return (node if complete else None), nodes


def scan_page_for(text, exact=True, scrolls=3, to_top=True):
    """整页找一段文字（先回到顶，再逐屏下滑）。返回 (找到的节点, 扫过的文字集合)。

    `to_top=False` 给「调用方刚用 `goto_nodes_page()` 核对过自己在顶部」的场合用：
    回顶是 6 次盲滑（约 3s），紧接着再扫一遍纯属浪费 —— R18 的 25s 预算里这就够
    把第一轮挤掉（2026-10-09 探针实测一轮 24.7s）。
    """
    seen = set()
    nodes = scroll_to_top() if to_top else R.ui_all()
    for _ in range(max_scrolls_plus(scrolls)):
        for t in R.ui_texts(nodes):
            seen.add(t)
        n = R.find_node(nodes, text=text, exact=exact)
        if n:
            return n, seen
        if not scroll_page(down=True, nodes=nodes):
            break
        nodes = R.ui_all()
    for t in R.ui_texts(nodes):
        seen.add(t)
    return R.find_node(nodes, text=text, exact=exact), seen


def max_scrolls_plus(scrolls):
    """步长是 1/3 屏 ⇒ 步数按 2 倍给，才与「一屏一步」的覆盖一样。"""
    return max(1, int(scrolls)) * 2 + 1


def wait_text(text, exact=True, timeout=8.0, scrolls=0):
    """等一段文字出现；scrolls>0 时顺带下滑找。返回 (节点, 最后一次 dump)。"""
    deadline = time.monotonic() + max(0.0, timeout)
    nodes = []
    left = max(0, int(scrolls))
    while True:
        nodes = R.ui_all()
        n = R.find_node(nodes, text=text, exact=exact)
        if n:
            return n, nodes
        if left > 0:
            scroll_page(down=True, nodes=nodes)
            left -= 1
        if time.monotonic() >= deadline:
            return None, nodes
        time.sleep(0.6)


def value_beside(nodes, label):
    """`InfoRow(label, value)` 是两个 Text：取同一行里 label 右边那个（靠 bounds 判断）。"""
    lab = R.find_node(nodes, text=label)
    if not lab or not lab.bounds:
        return None
    h = max(24, lab.bounds[3] - lab.bounds[1])
    best = None
    for n in nodes:
        if n is lab or not n.bounds or not n.text:
            continue
        if n.bounds[0] < lab.bounds[2]:
            continue
        if abs(n.center[1] - lab.center[1]) > h:
            continue
        if best is None or n.bounds[0] < best.bounds[0]:
            best = n
    return best


def status_beside(nodes, anchor_text):
    """手动节点那一行的处境文案：与 `host:port` 同一**格**、最近的那个状态词。

    容差不能用地址那一行的**单行高度**：`ManualPeerRow`（NodesScreen.kt:829-849）是
    `Column` 里上下两行 —— 地址（bodySmall）在上、处境（labelSmall）在下，处境不是
    「同一行」而是「同一格」里的第二行。真机实测 `192.168.2.82:52999`：
    地址 `bounds=(121,1523,410,1560)` ⇒ h=37、cy=1541；处境 `cy=1580` ⇒ Δ=39 > 37，
    就差这 2 个像素 —— run5 里 R20/R26/R21 四条 `处境=None` 全是这么来的
    （在此之前 R19 从没跑通过，所以这几条 UI 判据一次都没被执行过）。
    所以容差放到两倍行高，并仍旧取**最近**的一个：相邻两条手动节点之间隔着一个
    `padding(vertical = 4.dp)` 的行距，最近的那个必然是本条自己的处境。
    """
    anc = R.find_node(nodes, text=anchor_text, exact=False)
    if not anc or not anc.bounds:
        return None
    h = max(28, anc.bounds[3] - anc.bounds[1])
    tol = h * 2
    best = None
    best_d = None
    for n in nodes:
        if n.text not in STATUS_WORDS or not n.bounds:
            continue
        d = abs(n.center[1] - anc.center[1])
        if d > tol:
            continue
        if best is None or d < best_d:
            best, best_d = n, d
    return best


def goto_tab(label, marker, timeout=8.0):
    """切到某个顶级页签（页签在底栏，`bottom=True` 才不会点到同名的页面标题）。"""
    if R.find_ui(text=marker, timeout=1.0):
        return True
    if not R.tap_text(label, bottom=True, timeout=timeout):
        R.log("找不到页签 %r" % label)
        return False
    return R.find_ui(text=marker, timeout=timeout) is not None


def goto_nodes_page(attempts=3):
    """切到节点页，且**滚回顶部**（「本机」状态卡在最上面）。

    节点页会记住上次的滚动位置（和文件柜那个 LazyColumn 一样）：上一次跑完、
    或者人手在上面滑过之后，切回来时页面还停在下半部分，「本机」那张卡在屏幕外
    ⇒ `goto_tab` 的判据找不到它。2026-10-09 run4 的 R16 六条全红就是这么来的
    （本机地址=None / 状态=None / 发现方式=None / 在线节点=None，其实只是没滚到顶）。
    所以：页签点了还看不到就滚回顶部再看一次。

    **点了页签不一定切过去**：2026-10-09 的 R18 探针里真出现过 `tap '节点' at
    (540, 2211)` 之后页面仍是消息页（`goto_nodes_page()` 返回 False，紧接着
    `scan_page_for` 在消息页上白扫了 21s）。所以这里改成「重试 + 每次都核对
    判据」，并且每轮失败都打印屏上可见文字 —— 上面那些扫描函数都假定页面是对的，
    判错了只会得到一个看不懂的「找不到」。返回 False 时调用方**不要**接着扫。
    """
    for i in range(max(1, int(attempts))):
        if R.find_ui(text=S_LOCAL_CARD, timeout=1.0) is not None:
            return True
        goto_tab(S_TAB_NODES, S_LOCAL_CARD)
        if R.find_ui(text=S_LOCAL_CARD, timeout=4.0) is not None:
            return True
        scroll_to_top()
        if R.find_ui(text=S_LOCAL_CARD, timeout=2.0) is not None:
            return True
        R.log("第 %d/%d 次切节点页失败（没看到 %r）；屏上：%s"
              % (i + 1, attempts, S_LOCAL_CARD, _screen_brief(R.ui_all())))
    return False


def goto_settings_page(max_back=3):
    """切到设置 tab 的**首页**。返回 True = 屏上能看到「资料 v<n>」。

    设置 tab 会停在上次进过的子页：messaging 夹具为了开「消息落盘」会进
    「数据与存储」，于是 discovery 的 R24 点完 tab 看到的是那个子页、找不到
    「资料 v」而假红（2026-10-09 全量套件 run2 的 R24 就是这么红的）。
    判别方法：设置**首页**没有返回键（`App.kt:342` 调 `SettingsScreen` 不传
    `onBack`，默认 null），子页有（`SettingsScreen.kt:192/205 onBack = { subpage = null }`）
    ⇒ 「屏上有『返回』」就等于「停在子页」，按一下返回就回首页。
    """
    for attempt in range(max_back + 1):
        if R.find_ui(text=S_SETTINGS_MARKER, exact=False, timeout=1.0):
            return True
        if not R.tap_text(S_TAB_SETTINGS, bottom=True, timeout=8.0 if attempt == 0 else 2.5):
            return False
        if R.find_ui(text=S_SETTINGS_MARKER, exact=False, timeout=2.5):
            return True
        if R.find_ui(desc=S_BACK, timeout=1.0) is None:
            R.log("设置 tab 上既没有 %r 也没有返回键；当前可见：%s"
                  % (S_SETTINGS_MARKER, R.visible_texts(limit=30)))
            return False
        R.log("设置 tab 停在子页上（有返回键）—— 返回首页再试")
        R.press_back()
        time.sleep(0.9)
    return R.find_ui(text=S_SETTINGS_MARKER, exact=False, timeout=1.0) is not None


def syncbar_ports(nodes=None):
    """顶部同步条 `mDNS 广播中 · UDP %d / TCP %d` → (healthy, udp, tcp)。"""
    nodes = nodes if nodes is not None else R.ui_all()
    for n in nodes:
        t = n.text or ""
        if t.startswith(S_SYNCBAR_ON) or t.startswith(S_SYNCBAR_OFF):
            m = re.search(r"UDP (\d+) / TCP (\d+)", t)
            if m:
                return t.startswith(S_SYNCBAR_ON), int(m.group(1)), int(m.group(2)), t
            return t.startswith(S_SYNCBAR_ON), None, None, t
    return None, None, None, None


def settings_node_chip():
    """设置页那张卡上的「节点 xxxxxxxx」芯片（SettingsScreen.kt:492）。"""
    for t in R.ui_texts():
        m = S_NODE_ID_CHIP_RE.match((t or "").strip())
        if m:
            return m.group(1)
    return None


def phone_net_state():
    """手机上的 VPN 线索：`ip -o link` 里的 tun/ppp/wg 接口。"""
    links = R.adb("shell", "ip", "-o", "link", "show")
    ifaces = re.findall(r"^\s*\d+:\s+([^:@\s]+)", links, re.M)
    tun = [i for i in ifaces if re.match(r"^(tun|ppp|wg)\d*$", i)]
    return ifaces, tun


def fill_host_field(text, tries=3):
    """往弹层的「对方地址」框里注入文本，并回读对账（adb input 会掉字）。"""
    for attempt in range(tries):
        field = R.find_ui(cls="EditText", timeout=4.0)
        if not field:
            R.log("弹层里找不到 EditText（第 %d 次）" % (attempt + 1))
            continue
        R.tap_node(field)
        R.clear_focused_text(64)
        R.adb_input_text(text)
        time.sleep(0.4)
        back = R.find_ui(cls="EditText", timeout=2.0)
        got = (back.text if back else "") or ""
        R.log("host 框注入 %r → 回读 %r" % (text, got))
        if got.replace(" ", "") == text:
            return True
    return False


def _connect_cands():
    """弹层里可点的「连接」候选（按中心点从下往上排）。"""
    nodes = R.ui_all()
    cands = [n for n in nodes
             if (n.text == S_CONNECT or n.desc == S_CONNECT) and n.center and n.enabled]
    clickable = [n for n in cands if n.clickable]
    cands = clickable or cands
    cands.sort(key=lambda n: n.center[1], reverse=True)
    return cands


def tap_sheet_connect(max_tries=3):
    """点弹层里的「连接」。

    **软键盘会把按钮盖住**：`adb_input_text` 之后键盘弹起，dump 里按钮的坐标还是
    老坐标（真机实测 y=1948..2001），可那一下 tap 落在键盘上 —— 什么都不发生。
    2026-10-09 run3/run4 的 R19「填 `ip:port` → 连接 这条路没走通」连按三次都是
    这个原因：探针里 tap 完 6 秒，手机一条 `entry` 都没发、`settings.json` 的
    `manualPeers` 也没动；而按下面的顺序做（收键盘 → 需要时在弹层里上滑一次 →
    再点），`manualPeers` 立刻多出 `{"host":"192.168.2.82","port":<刚填的端口>}`。

    顺序不能省：
      ① 收键盘用 `press_back()`（弹层**不会**被它收掉，真机验过：收完
         `ime_shown=False` 而「对方地址」还在屏上）；
      ② 收完键盘弹层会回到半展开态，按钮被裁在屏幕最下沿（实测
         `bounds=(42,2366,1038,2400)`，文字被裁掉 ⇒ 按文字根本找不到它），
         得在弹层里向上滑一次把 `(503,2082,577,2135)` 那一段露出来；
      ③ 只能按文字找：那颗按钮的 clickable 落在父节点上、父节点**没有文字**，
         所以 `n.clickable` 常常是 False，`_connect_cands` 因此对 clickable
         做"有则优先、无则照用"。
    """
    for _ in range(max_tries):
        if R.ime_shown(timeout=3):
            R.log("「连接」前先收键盘（它会盖住按钮）")
            R.press_back()
            time.sleep(1.0)
        cands = _connect_cands()
        if not cands:
            R.adb("shell", "input", "swipe", "540", "2000", "540", "1200", "300")
            time.sleep(0.9)
            cands = _connect_cands()
        if not cands:
            R.log("找不到可点的 %r" % S_CONNECT)
            return False
        node = cands[0]
        R.log("tap %r at %s（候选 %d 个，取最靠下的）" % (S_CONNECT, node.center, len(cands)))
        R.tap_node(node)
        time.sleep(1.2)
        if not R.find_ui(text=S_FIELD_HOST, timeout=1.0):
            return True
    return False


def add_manual_peer(host, port):
    """R19 的界面路径：节点页 → 「填对方地址」→ 填 `ip:port` → 连接。"""
    nodes = scroll_to_top()
    entry = R.find_node(nodes, text=S_ADD_MANUAL)
    seen = set(R.ui_texts(nodes))
    if not entry:
        entry, more = scan_page_for(S_ADD_MANUAL)
        seen |= set(more)
    if not entry:
        # 找不到就把整页扫过的文字全打出来：这样才能分清「根本没进节点页」
        # 与「进了节点页但那一行没渲染/没滚到」（run2 只打一句「找不到入口」，
        # 分不清是哪种，白跑一轮）。
        R.log("节点页上找不到 %r 入口；整页扫过的文字：%s"
              % (S_ADD_MANUAL, " | ".join(sorted(t for t in seen if t))))
        R.log("（判据：节点页应能看到 %r/%r/%r/%r）"
              % (S_LOCAL_CARD, S_GROUP_LAN, S_GROUP_SAVED, "添加节点"))
        return False
    R.tap_node(entry)
    if not R.find_ui(text=S_FIELD_HOST, timeout=6.0):
        R.log("添加节点弹层没打开")
        return False
    if not fill_host_field(saved_row_text(host, port)):
        return False
    return tap_sheet_connect()


def remove_saved_row(host, port):
    """收尾：把夹具自己那一行删掉（`common_remove`），免得下次跑多出一行「没有回应」。"""
    text = saved_row_text(host, port)
    anchor, _ = wait_text(text, timeout=8.0, scrolls=3)
    if not anchor:
        return False
    nodes = R.ui_all()                      # wait_text 滑过页，必须重新 dump 才有新坐标
    anchor = R.find_node(nodes, text=text, exact=False)
    if not anchor or not anchor.bounds:
        return False
    h = max(28, anchor.bounds[3] - anchor.bounds[1])
    for n in nodes:
        if n.text == S_REMOVE and n.bounds and abs(n.center[1] - anchor.center[1]) <= h:
            R.tap_node(n)
            time.sleep(1.0)
            return True
    return False


# ---------------------------------------------------------------------------
# 对端替身：手机发 entry 过来要回 alive（Discovery 不会替夹具回）
# ---------------------------------------------------------------------------

class AliveResponder(threading.Thread):
    """收到手机的 `entry` 就回一条 `alive`（带当时那份资料）。

    为什么必须有它：手机 `addManualPeer` 里 `rt.announceTo(host, port)` 会**立刻**发一条
    单播 `entry`，而 `UdpPeer` 只对 RELIABLE_TYPES 自动回 ACK —— entry 不是可靠帧。
    不回这条 alive，手动节点就永远停在「没有回应」。
    """

    def __init__(self, peer, profile_holder, delay=0.3):
        threading.Thread.__init__(self, name="alive-responder")
        self.daemon = True
        self.peer = peer
        self.holder = profile_holder
        self.delay = float(delay)
        self.replied = []          # [(ts, addr, probe_id)]
        self._stop = threading.Event()

    def stop(self):
        self._stop.set()

    def run(self):
        since = time.time()
        while not self._stop.is_set():
            try:
                for ts, addr, env in self.peer.frames(mtype=R.T_ENTRY, since=since):
                    # `frames(since=…)` 是**闭**下界（`ts < since` 才跳过）⇒ 游标必须
                    # 挪过这条帧自己的 ts，否则同一条 entry 每轮都会被再取出来一次、
                    # 每 0.5s 回一条 alive。2026-10-09 run6 现场：从第一条 entry 起
                    # 全程回了 **391** 条 alive，把手机侧的手动节点永久钉在「在线」，
                    # 于是 R26 的「exit 后立即离线」永远判红（那一条是夹具的锅，
                    # 不是产品没处理 exit）。
                    since = ts + 1e-6
                    probe = (env.get("payload") or {}).get("probeId")
                    self._reply(addr, probe)
            except Exception as e:            # 后台线程不许把夹具带崩
                R.log("alive-responder 出错：%r" % (e,))
            time.sleep(0.2)

    def _reply(self, addr, probe_id):
        time.sleep(self.delay)
        prof = self.holder.get("profile")
        if not prof:
            return
        payload = R.entry_payload(prof, probe_id) if probe_id else R.entry_payload(prof)
        self.peer.send(self.peer.env(R.T_ALIVE, payload), addr=addr)
        self.replied.append((time.time(), addr, probe_id))
        R.log("udp-> alive 回给 %s（probeId=%s）" % (addr, (probe_id or "-")[:8]))


# ---------------------------------------------------------------------------
# 纯本地断言（--dry-run 只跑这一半）
# ---------------------------------------------------------------------------

def should_refresh(known_rev, their_rev):
    """`Discovery.handlePresence` 的镜像：rev 相等就不刷新，未知节点（-1）也算失配。"""
    return known_rev != their_rev


def display_name_fallback(nick, host, ip, node_id):
    """`RelayUiState.kt:88 PeerUi.displayName` 的最后一级兜底。"""
    return nick or host or ip or (node_id or "")[:8]


def manual_match(entry_host, entry_port, peers):
    """`ManualPeerStatus` 的镜像：`(地址, UDP 端口)` 两个都比，端口 0 不算失配。"""
    def port_ok(p):
        return p.get("udpPort") == entry_port or p.get("udpPort") == 0
    for p in peers:
        if p.get("ip") == entry_host and port_ok(p):
            return p
    for p in peers:
        if p.get("host") == entry_host and port_ok(p):
            return p
    return None


def local_discovery_checks(args, nick):
    """发现层特有的本地断言：信封形状、mDNS 命名、两组归属、文案对账。"""
    # --- presence / entry / exit 的线上形状（R20 / R23 / R26） ---
    pres = R.envelope(R.T_PRESENCE, {"seq": 1, "profileRev": 3})
    check_ok = R.check
    check_ok("L-presence-carries-seq-and-rev",
             set((pres.get("payload") or {}).keys()) == {"seq", "profileRev"},
             "payload=%s" % json.dumps(pres.get("payload"), ensure_ascii=False))
    check_ok("L-presence-rev-mismatch-triggers-refresh",
             should_refresh(0, 1) and should_refresh(-1, 0) and not should_refresh(7, 7),
             "相等不刷新；未知节点 profileRevOf=-1 也算失配")
    check_ok("L-presence-refresh-throttle-10s",
             PROFILE_REFRESH_MS == 10_000 and PROFILE_REFRESH_MS < PRESENCE_INTERVAL_MS,
             "PROFILE_REFRESH_MIN_INTERVAL_MS=%d（决议 #317）< presenceInterval=%d"
             % (PROFILE_REFRESH_MS, PRESENCE_INTERVAL_MS))

    ex = R.envelope(R.T_EXIT, {})
    check_ok("L-exit-payload-is-empty",
             (ex.get("payload") or {}) == {} and '"payload":{}' in R.encode(ex),
             "Discovery 只认类型不看内容：%s" % R.encode(ex))
    check_ok("L-exit-not-reliable-no-ack",
             R.T_EXIT not in R.RELIABLE_TYPES,
             "exit 不在 RELIABLE_TYPES 里 ⇒ 手机不该等 ACK，夹具也不该回")
    check_ok("L-offline-after-is-90s",
             R.OFFLINE_AFTER_MS == 90000 and R.OFFLINE_AFTER_MS > R.ALIVE_DEDUP_MS,
             "Timings.offlineAfter=%d（R20/R27 的那 90 秒）" % R.OFFLINE_AFTER_MS)

    # --- mDNS 命名与服务类型（R17 / R18） ---
    check_ok("L-mdns-service-type-two-spellings",
             MDNS_SERVICE_TYPE == "_relay._udp." and MDNS_BROWSE_TYPE == "_relay._udp",
             "advertiser 带尾点、NsdManager.discoverServices/dns-sd 不带（AndroidMdnsBrowser.kt:173）")
    node_id = R.FIXTURE_NODE
    label = mdns_label(nick, node_id)
    want_suffix = "".join(c for c in node_id if c.isalnum())[:6]
    check_ok("L-mdns-label-suffix-from-nodeid",
             label.endswith("-" + want_suffix) and len(want_suffix) == 6,
             "label=%r suffix=%r（MdnsNaming.mdnsServiceLabel）" % (label, want_suffix))
    cn = "证据节点" * 8                       # 32 个中文字 = 96 字节，必然触发截断
    cn_label = mdns_label(cn, node_id)
    check_ok("L-mdns-label-within-63-bytes",
             len(cn_label.encode("utf-8")) <= MDNS_LABEL_MAX_BYTES,
             "%dB <= %dB（超了 Bonjour 直接注册失败）"
             % (len(cn_label.encode("utf-8")), MDNS_LABEL_MAX_BYTES))
    odd = truncate_utf8(cn, 7)                # 7 字节切在第三个汉字中间
    check_ok("L-mdns-label-truncation-is-utf8-clean",
             len(odd.encode("utf-8")) <= 7 and odd.encode("utf-8").decode("utf-8") == odd,
             "%r 截到 %dB，仍是完整 UTF-8" % (odd, len(odd.encode("utf-8"))))
    check_ok("L-dns-sd-unescape-space",
             dns_sd_unescape("fake\\032bonjour-only") == "fake bonjour-only",
             "dns-sd 把空格印成 \\\\032，实例名对账前必须解转义")
    check_ok("L-dns-sd-unescape-utf8",
             dns_sd_unescape("\\228\\184\\173") == "中",
             "dns-sd 把 UTF-8 逐字节印成 \\\\DDD")

    # --- R24：nodeId 本身是 UUIDv4，界面上取前 8 位 ---
    check_ok("L-nodeid-is-uuid4", is_uuid4(node_id), "nodeId=%s（RelaySettings.firstRun → newId）" % node_id)
    check_ok("L-nodeid-prefix-8-is-hex",
             bool(re.match(r"^[0-9a-f]{8}$", node_id[:8].lower())),
             "界面兜底取 nodeId.take(8)=%s" % node_id[:8])
    check_ok("L-nodeid-display-stable",
             display_name_fallback("", "", "", node_id) == node_id[:8]
             and display_name_fallback("n", "", "", node_id) == "n",
             "空昵称/主机/IP 才退到 nodeId 前 8 位（RelayUiState.kt:88）")

    # --- R22：局域网组排除已保存的 nodeId ---
    peers = [{"nodeId": "a" * 8, "ip": "10.0.0.1"}, {"nodeId": "b" * 8, "ip": "10.0.0.2"}]
    saved = {"a" * 8}
    lan = [p["nodeId"] for p in peers if p["nodeId"] not in saved]
    check_ok("L-manual-lan-exclusion", lan == ["b" * 8],
             "lanPeers = peers.filter { it.nodeId !in savedNodeIds }（NodesScreen.kt:299-310）")

    # --- R19/R20/R21：手动节点的匹配与三种处境 ---
    m = manual_match("192.168.2.82", 54321,
                     [{"ip": "192.168.2.82", "udpPort": 54321, "online": True}])
    no_port = manual_match("192.168.2.82", 17878,
                           [{"ip": "192.168.2.82", "udpPort": 54321, "online": True}])
    unknown_port = manual_match("192.168.2.82", 54321,
                                [{"ip": "192.168.2.82", "udpPort": 0, "online": True}])
    check_ok("L-manual-match-needs-udp-port",
             m is not None and no_port is None and unknown_port is not None,
             "只看 IP 会把「填错端口」误判成有过回应；udpPort==0 时不许说「没有回应」")
    check_ok("L-saved-row-label-shape",
             saved_row_text("192.168.2.82", 54321) == "192.168.2.82:54321",
             "ManualPeerRow 渲染 ${entry.host}:${entry.port}")

    # --- 夹具自己的参数与昵称 ---
    check_ok("L-fixture-nick-within-limit",
             bool(nick) and len(nick) <= R.LIMITS["nick"] and nick.isascii(),
             "nick=%r（界面要对账，必须能 adb 注进去）" % nick)
    check_ok("L-bogus-peer-is-not-the-fixture-host",
             R.is_ipv4(args.bogus_peer) and args.bogus_peer != args.host_ip,
             "R21 那一行必须是「从来没有回应过」的地址：%s" % args.bogus_peer)
    check_ok("L-fake-mdns-name-is-ascii",
             FAKE_MDNS_NAME.isascii() and not FAKE_MDNS_NAME.endswith("."),
             "R18 负控名 %r（dns-sd -R 的实例名）" % FAKE_MDNS_NAME)
    check_ok("L-fixture-profile-shape",
             R.profile_caps(nick=nick, tcp_port=args.tcp_port, host=args.host_ip)
             .get("platform") == "linux",
             "夹具声明成桌面端：手机才会把 tcpPort 那一列用起来")


# ---------------------------------------------------------------------------
# 真机各阶段
# ---------------------------------------------------------------------------

def phase_env(args):
    phase("设备与环境")
    R.require_device()
    if not args.skip_ui and not shutil.which(MDNS_TOOL):
        raise R.EnvError("找不到 %s（macOS 自带 Bonjour CLI）；要么装上、要么 --skip-ui"
                         % MDNS_TOOL)
    pid = R.app_pid()
    R.log("app.relay pid=%s" % (pid or "(not running)"))
    if not pid:
        R.log("app.relay 没在跑，先拉起来")
        R.adb("shell", "am", "start", "-n", R.LAUNCH_ACTIVITY)
        time.sleep(3.0)
    if not args.skip_ui:
        R.ensure_app_foreground(timeout=_budget(args, 15))


def phase_handshake(args, peer, holder):
    """R16 的协议半边：定向 entry → 手机 alive（1 s 定向回复、带全量资料）。"""
    phase("协议层：定向握手 entry → alive")
    probe = str(uuid.uuid4())
    t0 = time.time()
    peer.send(peer.env(R.T_ENTRY, R.entry_payload(holder["profile"], probe)))
    alive = peer.wait(lambda e: e.get("type") == R.T_ALIVE
                      and (e.get("payload") or {}).get("probeId") == probe,
                      timeout=_budget(args, 8.0))
    dt = time.time() - t0
    R.check("R16 phone-replies-alive-to-directed-entry", alive is not None,
            "%.1fs 内收到 alive（probeId=%s）" % (dt, probe[:8]))
    prof = ((alive or {}).get("payload") or {}).get("profile") or {}
    R.check("R16 alive-echoes-probe-id",
            bool((alive or {}).get("payload", {}).get("probeId") == probe),
            "定向回复必须原样带回 probeId（手机侧 directedReplyInterval=1s、不去重）")
    R.check("R16 alive-carries-full-profile",
            all(k in prof for k in ("nodeId", "nick", "host", "platform", "tcpPort", "ver", "caps")),
            "keys=%s" % ",".join(sorted(prof.keys())))
    R.check("R16 alive-from-matches-profile-nodeid",
            bool(prof.get("nodeId")) and prof.get("nodeId") == (alive or {}).get("from"),
            "from=%s nodeId=%s" % ((alive or {}).get("from", "")[:8], str(prof.get("nodeId"))[:8]))
    R.check("R24 phone-nodeid-is-uuid4", is_uuid4(prof.get("nodeId") or ""),
            "nodeId=%s" % prof.get("nodeId"))
    R.check("R16 phone-profile-rev-present",
            isinstance(prof.get("profileRev"), int) and prof.get("profileRev") >= 0,
            "profileRev=%r（R23 要拿它做失配判据）" % prof.get("profileRev"))
    R.check("R16 phone-declares-mobile-text-cap",
            R.CAP_MOBILE_TEXT in (prof.get("caps") or []),
            "caps=%s" % ",".join(prof.get("caps") or []))
    return prof


def phase_nodes_card(args, prof):
    """R16 状态卡 + 同步条；R25 VPN 横幅。返回 (nodes, syncbar_udp)。"""
    phase("节点页：状态卡 / 同步条 / VPN 横幅（R16、R25）")
    ok_page = goto_nodes_page()
    R.check("R16 nodes-page-reachable", ok_page, "底部页签 %r → 找到 %r" % (S_TAB_NODES, S_LOCAL_CARD))
    nodes = R.ui_all()

    healthy, udp_port, tcp_port, bar = syncbar_ports(nodes)
    R.log("同步条：%r" % bar)
    R.check("R16 syncbar-broadcasting",
            healthy is True, "同步条=%r（healthy = Running && discoverable）" % bar)
    R.check("R16 syncbar-shows-udp-and-tcp",
            bool(udp_port) and bool(tcp_port),
            "UDP=%s TCP=%s" % (udp_port, tcp_port))

    addr = value_beside(nodes, S_LOCAL_ADDRESS)
    status = value_beside(nodes, S_LOCAL_STATUS)
    discovery = value_beside(nodes, S_LOCAL_DISCOVERY)
    peers = value_beside(nodes, S_PEERS_ONLINE)
    R.log("本机地址=%r 状态=%r 发现方式=%r 在线节点=%r"
          % (addr.text if addr else None, status.text if status else None,
             discovery.text if discovery else None, peers.text if peers else None))

    R.check("R16 local-address-is-ip-and-udp-port",
            bool(addr) and R.is_ipv4((addr.text or "").split(":")[0])
            and (addr.text or "").endswith(":%s" % udp_port),
            "本机地址=%r 与同步条 UDP=%s 对账（A1 的真机证据是 192.168.2.22:17878）"
            % (addr.text if addr else None, udp_port))
    R.check("R16 local-address-is-the-phone-we-talked-to",
            bool(addr) and (addr.text or "").split(":")[0] == args.phone_ip,
            "本机地址 IP=%r vs --phone %s"
            % ((addr.text or "").split(":")[0] if addr else None, args.phone_ip))

    if status and status.text == S_STATE_FAILED:
        R.log("状态是「%s」—— 按 A1 的写法点一次「%s」再判" % (S_STATE_FAILED, S_RETRY_START))
        R.tap_text(S_RETRY_START, timeout=4.0)
        time.sleep(3.0)
        nodes = R.ui_all()
        status = value_beside(nodes, S_LOCAL_STATUS)
        R.log("重试后状态=%r" % (status.text if status else None))
    R.check("R16 local-status-ok",
            bool(status) and status.text == S_STATE_OK,
            "状态=%r（期望 %r；%r 是 A1 的反例）"
            % (status.text if status else None, S_STATE_OK, S_STATE_FAILED))
    R.check("R16 discovery-mode-on",
            bool(discovery) and discovery.text == S_DISCOVERY_ON,
            "发现方式=%r" % (discovery.text if discovery else None))
    R.check("R16 peers-online-counter-parses",
            bool(peers) and bool(re.match(r"^\d+ / \d+$", (peers.text or "").strip())),
            "在线节点=%r（A1 证据是 1 / 1）" % (peers.text if peers else None))

    ifaces, tun = phone_net_state()
    vpn = bool(tun)
    R.log("手机接口=%s VPN 接口=%s" % (",".join(ifaces), tun or "无"))
    R.check("R25 vpn-banner-matches-system-state",
            R.ui_has(S_VPN_WARNING, nodes) == vpn,
            "横幅在=%s，系统有 VPN 接口=%s（无 VPN 时只能验「不该出现」这一半）"
            % (R.ui_has(S_VPN_WARNING, nodes), vpn))
    return nodes, udp_port, prof


def phase_mdns(args, prof, nick):
    """R17 广播读数（dns-sd -B / -L）；R18 浏览侧负控（dns-sd -R 一个假服务）。"""
    phase("mDNS：广播读数（R17）")
    phone_node = prof.get("nodeId") or ""
    phone_nick = prof.get("nick") or ""
    want = mdns_label(phone_nick, phone_node)
    R.log("按 MdnsNaming 推算手机的实例名：%r（nick=%r nodeId=%s）"
          % (want, phone_nick, phone_node[:8]))
    found, raw = dns_sd_browse(_budget(args, 6.0))
    adds = [dns_sd_unescape(n) for (op, n) in found if op == "Add"]
    R.log("dns-sd -B 看到 %d 个 Add：%s" % (len(adds), adds[:6]))
    suffix = "-" + want.rsplit("-", 1)[-1]
    hit = [a for a in adds if a == want] or [a for a in adds if a.endswith(suffix)]
    R.check("R17 mdns-instance-advertised", bool(hit),
            "实例名里要有一个以 %r 结尾的：看到 %s" % (suffix, adds[:6]))
    R.check("R17 mdns-label-matches-naming-rule", want in adds,
            "推算 %r，实际 %s（不一致就说明 MdnsNaming 与手机侧实现分叉了）" % (want, adds[:6]))
    if hit:
        host, port, raw_l = dns_sd_resolve(hit[0], _budget(args, 6.0))
        R.log("dns-sd -L %r → %s:%s" % (hit[0], host, port))
        R.check("R17 mdns-resolves-to-phone", host is not None,
                "解析出 %s:%s" % (host, port))
        R.check("R17 mdns-port-is-udp-port",
                port is not None and port == args.phone_udp,
                "mDNS 广播的是绑定的 UDP 口：%s vs --phone %s" % (port, args.phone_udp))

    phase("mDNS：浏览侧（R18，I3 的负控手法）")
    # 必须先回到**节点页**：R24 在 R17/R18 之前把界面切到了设置页（它的芯片只在
    # 设置子页上），而 mDNS 发现的服务只画在节点页的「局域网发现」里 ——
    # run2/run3 的 R18 就是在设置页上扫了 25s，手机其实早把服务解析出来了
    # （logcat：`mDNS service resolved: fake-bonjour-only at 192.168.2.82:17999`）。
    if not args.skip_ui:
        R.log("R18 前先切回节点页：%s" % goto_nodes_page())
    with MdnsRegistrar(FAKE_MDNS_NAME, FAKE_MDNS_PORT) as reg:
        R.log("已注册只有 Bonjour、零 UDP 的 %r@%d" % (FAKE_MDNS_NAME, FAKE_MDNS_PORT))
        node, seen, nodes = None, set(), []
        # 一轮 `scan_page_for(scrolls=2)` ≈ 15-18s（步长 1/3 屏、每步都要重新 dump；
        # 2026-10-09 探针实测 24.7s，那时还多做了 6 次回顶盲滑），给两轮的余量。
        deadline = time.monotonic() + _budget(args, 45.0)
        first = True
        while time.monotonic() < deadline:
            # 先看首屏：少数情况下「局域网发现」的卡片内容已经在折叠线以上，一次
            # dump 就够。注意**一次 dump 看不到不等于没有** —— 卡片内容通常在折叠线
            # 以下、LazyColumn 还没组合它，得滑下去才看得到（必须滑下去）。
            node = R.find_ui(text=FAKE_MDNS_NAME, exact=False, timeout=1.0)
            if node:
                nodes = R.ui_all()      # 找到就立刻取一份当时的 dump：后面的端口/按钮
                node, nodes = _settle_mdns_row(node, nodes)   # 判据要对同一屏做
                if node:
                    break               # 名字在下沿时端口行还不在 dump 里，_settle_* 会半步下推
            node, seen_now = scan_page_for(FAKE_MDNS_NAME, exact=False, scrolls=2,
                                           to_top=first)
            first = False
            seen |= seen_now
            if node:
                nodes = R.ui_all()
                node, nodes = _settle_mdns_row(node, nodes)
                if node:
                    break
            time.sleep(1.5)
        else:
            nodes = R.ui_all()
        nodes = nodes or R.ui_all()
        R.check("R18 mdns-browse-lists-bonjour-only-service", node is not None,
                "局域网发现里应出现 %r（它没有任何 UDP presence，只能是被 mDNS 发现的）；"
                "扫过的文字：%s"
                % (FAKE_MDNS_NAME, " | ".join(sorted(t for t in seen if t))[:400] or "（没扫到任何文字）"))
        if node:
            node = R.find_node(nodes, text=FAKE_MDNS_NAME, exact=False) or node
            row = " ".join(sorted(t for t in R.ui_texts(nodes) if str(FAKE_MDNS_PORT) in str(t)))
            R.check("R18 mdns-browse-shows-host-and-port",
                    str(FAKE_MDNS_PORT) in row,
                    "同一屏里能看到端口 %d：%r" % (FAKE_MDNS_PORT, row))
            near = [n for n in nodes
                    if (n.text == S_CONNECT or n.desc == S_CONNECT) and n.center and node.center
                    and abs(n.center[1] - node.center[1]) <= 120]
            R.check("R18 mdns-browse-row-has-connect", bool(near),
                    "I3 的判据是那一行带一个可点的「%s」：行 y=%s 附近找到 %d 个"
                    % (S_CONNECT, node.center[1] if node.center else None, len(near)))
        reg.stop()


def open_profile_subpage(timeout=8.0):
    """设置首页 →「我的资料」子页；进得去返回 True。

    「节点 xxxxxxxx」芯片**只在子页上**：`SettingsScreen.kt:492` 属于
    `SettingsProfileSubpage`，而设置首页那张卡（`SettingsScreen.kt:297`）只有
    `资料 vN`、没有节点芯片。第一版夹具把两者当成同一屏，于是先读到首页的
    `资料 v4`、却永远读不到芯片（真机 R24 就是这么假红的）。
    """
    for attempt in range(2):
        nodes = scroll_to_top() if attempt == 0 else R.ui_all()
        hit = R.find_node(nodes, text=S_PROFILE_CHIP, exact=False)
        if not hit:
            hit = R.find_node(nodes, text=S_PROFILE_TITLE)
        if not hit:
            return False
        R.tap_node(hit)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if settings_node_chip():
                return True
            time.sleep(0.5)
    return False


def phase_settings_node_id(args, prof):
    """R24：设置页的「节点 xxxxxxxx」芯片 == 握手学到的 nodeId 前 8 位，且来回切页稳定。"""
    phase("设置页：节点 ID 芯片（R24）")
    if not goto_settings_page():
        R.check("R24 settings-page-reachable", False, "底部页签 %r 没进去" % S_TAB_SETTINGS)
        return
    want = (prof.get("nodeId") or "")[:8]
    reached = open_profile_subpage()
    R.check("R24 profile-subpage-reachable", reached,
            "设置首页 →「%s」子页（芯片只在子页上）" % S_PROFILE_TITLE)
    chip = settings_node_chip()
    rev = None
    for t in R.ui_texts():
        m = re.match(r"^资料 v(\d+)$", (t or "").strip())
        if m:
            rev = int(m.group(1))
    R.log("资料子页：节点芯片=%r 资料 v%r（期望 v%s）" % (chip, rev, prof.get("profileRev")))
    R.check("R24 settings-shows-nodeid-8hex",
            bool(chip) and bool(want) and chip.lower() == want.lower(),
            "芯片=%r vs nodeId[:8]=%r" % (chip, want))
    R.check("R24 settings-shows-same-profile-rev",
            rev == prof.get("profileRev"),
            "界面 v%r vs 握手 profileRev=%r" % (rev, prof.get("profileRev")))
    if not R.tap_text(S_TAB_MESSAGES, bottom=True, timeout=6.0):
        R.log("回消息页失败，跳过稳定性复读")
        return
    time.sleep(1.0)
    if not goto_settings_page():
        return
    open_profile_subpage()
    chip2 = settings_node_chip()
    R.check("R24 nodeid-chip-stable-across-revisit",
            bool(chip) and chip2 == chip,
            "两次读到的芯片：%r / %r" % (chip, chip2))


def phase_manual_add(args, peer, holder, nick):
    """R19 添加 → R22 两组互斥 → R20/R26 exit 立即离线、再 entry 回到在线。"""
    phase("手动节点：添加与两组归属（R19、R22）")
    if not goto_nodes_page():
        R.check("R19 nodes-page-reachable", False, "节点页进不去")
        return None
    before = len(peer.frames(mtype=R.T_ENTRY))
    if not add_manual_peer(args.host_ip, peer.port):
        R.check("R19 manual-add-flow-completes", False,
                "填 %s → 连接 这条路没走通" % saved_row_text(args.host_ip, peer.port))
        return None
    R.check("R19 manual-add-flow-completes", True,
            "填 %s 并点了「%s」" % (saved_row_text(args.host_ip, peer.port), S_CONNECT))

    total_after = len(peer.frames(mtype=R.T_ENTRY))
    R.check("R19 phone-announces-entry-to-new-manual-peer",
            total_after > before,
            "手机 addManualPeer 里会立刻 announceTo(host, port)；entry 从 %d 涨到 %d"
            % (before, total_after))
    R.log("手机发来的 entry：累计 %d 条（本轮新增 %d）" % (total_after, total_after - before))

    row = saved_row_text(args.host_ip, peer.port)
    anchor, nodes = wait_text(row, timeout=_budget(args, 12.0), scrolls=2)
    R.check("R19 saved-row-appears", anchor is not None,
            "「%s」里应有 %r" % (S_GROUP_SAVED, row))
    if not anchor:
        return None
    status = status_beside(nodes, row)
    R.check("R20 saved-row-shows-online-after-alive",
            bool(status) and status.text == S_MANUAL_ONLINE,
            "处境=%r（我们回了 alive，手机 touch 后应是在线）"
            % (status.text if status else None))

    # R22：同一台机器不许同时出现在两组里。
    # 已保存行渲染的是 host:port（不是昵称），局域网那组渲染的是昵称 —— 所以
    # 「整页都找不到昵称」就是「它没被算进局域网发现」。
    nick_node, seen = scan_page_for(nick, scrolls=3)
    R.check("R22 saved-peer-not-in-lan-group", nick_node is None,
            "整页（%d 段文字）都找不到昵称 %r ⇒ 它只在已保存那一组" % (len(seen), nick))
    R.check("R22 saved-row-still-present-while-checking", any(row in t for t in seen),
            "同一轮扫描里 %r 在页面上" % row)

    phase("告别帧 exit：立即离线，再 entry 回到在线（R20、R26）")
    t_exit = time.time()
    peer.send_exit()
    status = None
    deadline = time.monotonic() + _budget(args, 6.0)
    while time.monotonic() < deadline:
        nodes = R.ui_all()
        status = status_beside(nodes, row)
        if status and status.text == S_MANUAL_OFFLINE:
            break
        time.sleep(0.8)
    dt = time.time() - t_exit
    R.check("R26 exit-marks-offline-immediately",
            bool(status) and status.text == S_MANUAL_OFFLINE,
            "%.1fs 后处境=%r（期望 %r；A12/C4 的反例是要等 90 秒）"
            % (dt, status.text if status else None, S_MANUAL_OFFLINE))
    R.check("R26 offline-not-via-90s-timeout", dt < float(R.OFFLINE_AFTER_MS) / 1000.0 - 10,
            "%.1fs < %.0fs（走的是 exit 分支，不是 sweep）"
            % (dt, R.OFFLINE_AFTER_MS / 1000.0))

    probe = str(uuid.uuid4())
    peer.send(peer.env(R.T_ENTRY, R.entry_payload(holder["profile"], probe)))
    peer.wait(lambda e: e.get("type") == R.T_ALIVE
              and (e.get("payload") or {}).get("probeId") == probe, timeout=_budget(args, 6.0))
    status = None
    deadline = time.monotonic() + _budget(args, 8.0)
    while time.monotonic() < deadline:
        nodes = R.ui_all()
        status = status_beside(nodes, row)
        if status and status.text == S_MANUAL_ONLINE:
            break
        time.sleep(0.8)
    R.check("R26 re-entry-marks-online-again",
            bool(status) and status.text == S_MANUAL_ONLINE,
            "再次 entry+alive 后处境=%r" % (status.text if status else None))
    return row


def phase_profile_refresh(args, peer, holder, nick, row, prof):
    """R23：presence 的 profileRev 失配 → 手机单播 entry（10 s 节流）；界面昵称跟上。

    方向要说清：**手机**收到我们那条 profileRev 失配的 `presence` 之后，单播一条带
    `probeId` 的 `entry` 给我们（`Discovery.handlePresence`），我们回 `alive` 带上新
    资料，手机 `PeerRegistry` 才把我们的昵称换掉 —— 换掉之后会话列表那一行的标题跟着变，
    这正是 A13c 记录下来的观测量（`aeb471f0` → `证据节点`）。
    """
    phase("资料刷新：presence profileRev 失配 → 单播 entry（R23）")
    phone_node = (prof or {}).get("nodeId") or ""
    nick2 = uniq_nick("E2ENew")
    t0 = time.time()
    holder["profile"] = R.profile_caps(nick=nick2, tcp_port=args.tcp_port,
                                       host=args.host_ip, node_id=R.FIXTURE_NODE,
                                       profile_rev=1)
    peer.send(peer.env(R.T_PRESENCE, {"seq": 1, "profileRev": 1}))
    got = peer.wait(lambda e: e.get("type") == R.T_ENTRY
                    and (e.get("payload") or {}).get("probeId"),
                    timeout=_budget(args, 8.0))
    refresh_at = time.time()
    R.check("R23 rev-mismatch-triggers-unicast-entry-with-probe", got is not None,
            "发 presence(rev=1) 后收到带 probeId 的单播 entry（%.1fs）" % (refresh_at - t0))
    entry_prof = ((got or {}).get("payload") or {}).get("profile") or {}
    R.check("R23 refresh-entry-carries-phone-own-profile",
            bool(got) and got.get("from") == phone_node
            and entry_prof.get("profileRev") == (prof or {}).get("profileRev"),
            "entry.from=%s（期望手机 %s）profileRev=%r（期望 %r）"
            % ((got or {}).get("from", "")[:8], phone_node[:8],
               entry_prof.get("profileRev"), (prof or {}).get("profileRev")))
    time.sleep(1.5)                         # 让我们的 alive 回到手机、registry 换完资料

    phase("资料刷新：10 秒节流（R23）")
    before = len(peer.frames(mtype=R.T_ENTRY))
    peer.send(peer.env(R.T_PRESENCE, {"seq": 2, "profileRev": 2}))
    peer.wait(lambda e: e.get("type") == R.T_ENTRY
              and (e.get("payload") or {}).get("probeId"), timeout=4.0)
    after = len(peer.frames(mtype=R.T_ENTRY))
    R.check("R23 refresh-throttled-within-10s", after == before,
            "10 秒窗口内再发 presence 不该再触发 entry（前 %d 后 %d）" % (before, after))

    if args.skip_ui:
        return
    phase("资料刷新：界面昵称跟上（R23 的 A13c 观测量）")
    marker = R.uniq_marker("d")
    peer.send_text("ping " + marker)
    if not R.goto_messages_list(max_back=5):
        R.check("R23 chat-list-reachable", False, "回不到消息列表")
        return
    cur, nodes = wait_text(nick2, timeout=_budget(args, 10.0), scrolls=2)
    R.check("R23 chat-row-shows-current-nick", cur is not None,
            "会话列表里应有一行标题是手机此刻记着的昵称 %r" % nick2)

    nick3 = uniq_nick("E2ELate")
    holder["profile"] = R.profile_caps(nick=nick3, tcp_port=args.tcp_port,
                                       host=args.host_ip, node_id=R.FIXTURE_NODE,
                                       profile_rev=2)
    wait = (refresh_at + PROFILE_REFRESH_MS / 1000.0 + 0.8) - time.time()
    if wait > 0:
        R.log("等过节流窗口：再睡 %.1fs（决议 #317：每节点 %d ms）" % (wait, PROFILE_REFRESH_MS))
        time.sleep(wait)
    t0 = time.time()
    peer.send(peer.env(R.T_PRESENCE, {"seq": 3, "profileRev": 2}))
    got2 = peer.wait(lambda e: e.get("type") == R.T_ENTRY
                     and (e.get("payload") or {}).get("probeId"), timeout=_budget(args, 8.0))
    R.check("R23 second-refresh-after-throttle-window", got2 is not None,
            "等过节流窗口后 rev=2 应再触发一次 entry（%.1fs）" % (time.time() - t0))
    time.sleep(2.0)
    fresh, nodes = wait_text(nick3, timeout=_budget(args, 12.0), scrolls=2)
    R.check("R23 chat-row-follows-new-nick", fresh is not None,
            "行标题应变成 %r（A13c 的证据就是这样从 aeb471f0 变成「证据节点」）" % nick3)
    gone, _ = scan_page_for(nick2, scrolls=2)
    R.check("R23 old-nick-no-longer-shown", gone is None,
            "换过名字之后整页不该再出现旧昵称 %r（换的是同一行，不是多出一行）" % nick2)


def phase_cleanup(args, row):
    """R21：加一个从没回应过的地址 → 提示 + 清理按钮 → 点掉它。"""
    phase("清理不可达（R21）")
    bogus = saved_row_text(args.bogus_peer, BOGUS_PEER_PORT)
    if not goto_nodes_page():
        R.check("R21 nodes-page-reachable", False, "节点页进不去")
        return
    if not add_manual_peer(args.bogus_peer, BOGUS_PEER_PORT):
        R.check("R21 bogus-peer-added", False, "加不进 %r" % bogus)
        return
    anchor, nodes = wait_text(bogus, timeout=_budget(args, 12.0), scrolls=2)
    R.check("R21 bogus-peer-row-appears", anchor is not None, "已保存组里应有 %r" % bogus)
    status = status_beside(nodes, bogus) if anchor else None
    R.check("R21 bogus-peer-marked-unreachable",
            bool(status) and status.text == S_MANUAL_UNREACHABLE,
            "处境=%r（期望 %r）" % (status.text if status else None, S_MANUAL_UNREACHABLE))
    hint = R.find_ui(text=S_UNREACHABLE_HINT, exact=False, timeout=3.0)
    R.check("R21 unreachable-hint-shown", hint is not None, "%r 应出现" % S_UNREACHABLE_HINT)
    btn = R.find_ui(text=S_CLEANUP, exact=False, timeout=3.0)
    R.check("R21 cleanup-button-counted",
            bool(btn) and "1" in (btn.text or ""),
            "按钮=%r（文案带计数：清理没有回应的（1））" % (btn.text if btn else None))
    if btn:
        R.tap_node(btn)
        time.sleep(1.5)
        gone, seen = scan_page_for(bogus, scrolls=3)
        R.check("R21 cleanup-removes-unreachable-row", gone is None,
                "点完之后整页都不该再有 %r" % bogus)
        R.check("R21 cleanup-keeps-online-row", any(row in t for t in seen),
                "夹具自己那一行 %r 必须留着（有过回应的不许被清）" % row)


def phase_sweep(args, peer, row):
    """可选：夹具装死 → 手机 90 s 存在性超时把夹具标离线（R20 的 offlineAfter 那一半）。"""
    phase("存在性超时 90 s（R20，--check-sweep）")
    if args.secs < 150:
        R.log("--secs=%d < 150，跳过（这条要等 %d 秒）" % (args.secs, R.OFFLINE_AFTER_MS // 1000))
        return
    R.log("夹具装死（quiet=True）：不回 ACK、不回 alive，等价于对端消失")
    peer.quiet = True
    t0 = time.time()
    status = None
    deadline = time.monotonic() + float(R.OFFLINE_AFTER_MS) / 1000.0 + 25.0
    while time.monotonic() < deadline:
        nodes = R.ui_all()
        status = status_beside(nodes, row)
        if status and status.text == S_MANUAL_OFFLINE:
            break
        time.sleep(5.0)
    dt = time.time() - t0
    peer.quiet = False
    R.check("R20 sweep-marks-offline-after-90s",
            bool(status) and status.text == S_MANUAL_OFFLINE,
            "%.0fs 后处境=%r（期望 %r，Timings.offlineAfter=%d）"
            % (dt, status.text if status else None, S_MANUAL_OFFLINE, R.OFFLINE_AFTER_MS))
    R.check("R20 sweep-not-early", dt >= 60.0,
            "%.0fs ≥ 60s（不许提前判离线）" % dt)


def phase_phone_no_exit(args, peer, prof):
    """R27：手机端没有生命周期钩子 ⇒ 杀进程也不会发 exit（90 s 兜底那半不在夹具侧）。"""
    phase("手机端不告别（R27）")
    phone_node = prof.get("nodeId") or ""
    before = peer.frames(mtype=R.T_EXIT)
    R.check("R27 phone-never-sent-exit-during-session", not before,
            "整场只在开头收到过 exit 的话才怪 —— 本轮 inbound exit=%d 条" % len(before))
    R.log("am force-stop %s（A12 的杀进程手法）" % R.PKG)
    R.adb("shell", "am", "force-stop", R.PKG)
    time.sleep(float(args.exit_wait))
    exits = peer.frames(mtype=R.T_EXIT, since=time.time() - float(args.exit_wait) - 1.0)
    from_phone = [e for (_, _, e) in exits if e.get("from") == phone_node]
    R.check("R27 phone-sends-no-exit-after-kill", not from_phone,
            "杀掉后 %.0fs 内收到手机 exit %d 条（期望 0：手机侧没有生命周期钩子）"
            % (float(args.exit_wait), len(from_phone)))
    R.check("R27 offline-after-window-is-the-documented-fallback",
            R.OFFLINE_AFTER_MS == 90000,
            "手机的真实路径是 %d 秒存在性超时（A12 明说这是现状不是缺陷）" % R.OFFLINE_AFTER_MS)


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def run(args):
    # ---------------------------------------------------------------- 本地自检
    nick = uniq_nick("E2E")
    marker = R.uniq_marker("d")
    long_text = R.long_text(1400, tail_marker=R.uniq_marker("zt"))
    phase("本地自检（不碰网络、不碰设备）")
    R.local_self_checks(marker, long_text, nick=nick, tcp_port=args.tcp_port,
                        host=args.host_ip)
    local_discovery_checks(args, nick)
    R.log("fixture nick=%r nodeId=%s host=%s bogus=%s:%d"
          % (nick, R.FIXTURE_NODE[:8], args.host_ip, args.bogus_peer, BOGUS_PEER_PORT))

    if args.dry_run:
        print("\n[dry-run] 不碰网络、不碰设备：跳过 adb、UDP、dns-sd 与全部点按。", flush=True)
        print("[dry-run] 真机将执行：定向握手(R16) → 节点页状态卡/同步条/VPN(R16/R25) → "
              "dns-sd -B/-L 广播读数(R17) → dns-sd -R 假服务验浏览(R18) → 设置页节点 ID 芯片(R24) → "
              "填地址加节点(R19) → 两组互斥(R22) → exit 立即离线再 entry 回来(R20/R26) → "
              "presence 失配触发资料刷新 + 10 s 节流 + 界面昵称跟上(R23) → 清理不可达(R21) → "
              "force-stop 验手机不告别(R27)。", flush=True)
        print("[dry-run] 以上都是本地自检，没碰设备，因此**不打 result 行**："
              "干跑的 result 行会被 run_all.py 当成「真跑通过」（2026-10-09 实测到过），"
              "而其余 8 个夹具在 dry-run 下都不打。", flush=True)
        return 0

    # ---------------------------------------------------------------- 环境
    phase_env(args)

    # ---------------------------------------------------------------- 对端替身
    holder = {}
    peer = R.UdpPeer(args.phone_ip, args.phone_udp, nick=nick,
                     tcp_port=args.tcp_port, host=args.host_ip)
    holder["profile"] = peer.profile
    responder = AliveResponder(peer, holder)
    peer.start()
    responder.start()
    R.log("夹具对端：%s:%d（UDP 临时口），profile.host=%s tcpPort=%d"
          % (args.host_ip, peer.port, args.host_ip, args.tcp_port))
    try:
        prof = phase_handshake(args, peer, holder)
        if not prof:
            R.log("握不上手：后面的 UI 断言都会连带失败，但继续跑完以便看读数")
        else:
            phase_nodes_card(args, prof)
            phase_settings_node_id(args, prof)
            if not args.skip_ui:
                phase_mdns(args, prof, nick)
            else:
                R.log("--skip-ui：跳过 mDNS 读数与全部点按")
        if args.skip_ui:
            row = None
        else:
            row = phase_manual_add(args, peer, holder, nick)
            phase_profile_refresh(args, peer, holder, nick, row, prof or {})
            if row:
                phase_cleanup(args, row)
                phase_sweep(args, peer, row)
        # 收尾必须排在 force-stop 之前：应用被杀掉之后点不动任何东西。
        if row and not args.skip_ui:
            if remove_saved_row(args.host_ip, peer.port):
                R.log("收尾：已删掉夹具自己那一行 %r" % row)
            else:
                R.log("收尾：没删掉 %r（下次跑会在已保存组里留一行「没有回应」）" % row)
        phase_phone_no_exit(args, peer, prof or {})
        if not args.skip_ui:
            R.log("收尾：把手机上的 app.relay 重新拉起来（刚才 force-stop 过）")
            R.adb("shell", "am", "start", "-n", R.LAUNCH_ACTIVITY)
    finally:
        responder.stop()
        peer.stop()
    return R.report()


def extra_args(ap):
    ap.add_argument("--tcp-port", type=int, default=R.TCP_PORT_DEFAULT,
                    help="夹具 profile 里声明的 tcpPort（发现层不连它），默认 %(default)s")
    ap.add_argument("--bogus-peer", default=BOGUS_PEER_DEFAULT,
                    help="R21「从来没有回应过」那一行的地址（默认 TEST-NET-1，不会撞上真设备）")
    ap.add_argument("--exit-wait", type=float, default=12.0,
                    help="R27 杀进程后观察手机有没有发 exit 的秒数，默认 %(default)s")
    ap.add_argument("--skip-ui", action="store_true",
                    help="只跑协议层与本地断言：不注入按键、不点按、不读 dns-sd")
    ap.add_argument("--check-sweep", action="store_true",
                    help="额外验 90 s 存在性超时（要 --secs >= 150）")
    ap.set_defaults(secs=300)      # 发现层要等 presence 周期与 mDNS 收敛


if __name__ == "__main__":
    sys.exit(R.main_wrapper(run, description=__doc__, extra_args=extra_args))
