#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""relay_e2e 夹具：共享文件柜（§2 I 段 R88/R89/R92/R93/R94）与网段探测（R90/R91）。

覆盖行
------
* **R88** 共享文件柜：本机共享开关 / 目录 / 权限三档。
* **R89** 共享文件柜：浏览对端目录 / 面包屑 / 下载。
* **R90** `scan-ranges`：CIDR 网段扫描探活。
* **R91** `scan-ranges`：入站自动吸收对端网段。
* **R92** `avatar` 自定义头像：**只覆盖「协议层没有宣告面」这一半**（见下）。
* **R93** `update` 信封（有意不做）。
* **R94** `peers` 信封（有意不做）。

R88/R89 是**诚实的负向 / 回归夹具**
------------------------------------
共享文件柜（`share`，cap `shr1`）在 relay 侧**没有实现**。这不是推断，是本夹具逐条核过的：

1. `composeApp/src/commonMain/kotlin/com/kapp/tidings/app/RelayController.kt:739`
   `internal val CHAT_INBOUND_TYPES: Set<String> = setOf(MsgTypes.MSG)` —— 只有 `msg`；
   `app/RelayControllerMessages.kt:229` `if (envelope.type !in CHAT_INBOUND_TYPES) return`
   ⇒ 入站 `share` 帧在传输层被 ACK 之后，在控制器层被静默丢掉。
2. `app/RelayControllerCabinet.kt:65-113 handleLoadCabinetPath` 在真机（`runtime != null`）
   分支里只有 `_state.update { it.copy(cabinet = it.cabinet.copy(loading = false)) }`，
   注释写着「在真实节点运行时下通过 NodeRuntime 发送 share:list」，
   但**一行发送代码都没有** ⇒ 手机永远不会发 `share:list`。
3. `core/protocol/Codec.kt:836 decodeShareMessage` 全仓**零调用点**（`ShareMessage` 的
   五种载荷只在它自己内部被构造）⇒ `share` 编解码是死代码。
4. `app/RelayControllerCabinet.kt:129-132 handleDownloadCabinetEntries` 只发一条
   `BannerMessage.CabinetDownloadStarted` 横幅，**不下载任何东西、不发 `share:get`**。
5. 协议本身是完整的：`core/protocol/SharePayload.kt` 定义了
   `ShareOps{list,list-ok,deny,get,put}` 与 `ShareDenyReasons{off,no-perm,not-found,too-deep,busy,gone}`，
   `MsgTypes.HANDLED` 含 `SHARE`，`Codec.kt:214` 路由到 `validateShare(o)` —— 校验在，
   只是没有一个调用点去用它。

所以本夹具对 R88/R89 断言的是**当前的真实行为**（帧被丢掉、手机从不发 `share:list`、
对端文件柜永远是「共 0 项」），并把「开关/权限三档/面包屑/下载真正生效」这些**功能面
判据明确标成不可覆盖**（见 `--dry-run` 输出与最终报告）。**没有任何一条断言是为错误的
理由通过的**：每一条都写出它读的是哪个可观测面、以及为什么那个面能证明它。

覆盖不了的（如实列出，不造假）
------------------------------
* **R89 的「下载」** —— `handleDownloadCabinetEntries` 只弹横幅，且真机下列表恒为空，
  连一个可勾选的条目都没有 ⇒ 没有可驱动的入口。
* **R89 的「面包屑 / 进入子目录」** —— 需要 `share:list-ok` 回填 entries，前提不存在。
* **R88 的「权限三档真的生效」** —— 判定权限生效要对端发起 `share:list` 才看得到；
  对端发起后手机不回任何东西 ⇒ 只能断言三个按钮**存在**，不能断言它们生效。
* **R92 的「设置里第三档点了生效」** —— `RelaySettings.caps()` 对 `CAP_AVATAR` 没有分支、
  设置页没有入口 ⇒ 只能断言 caps 里恒无 `av1`。
* **R90 的「跨网段真的发现了新节点」** —— 探活只证明广播发出去了；对方是否应答是
  被扫主机的事，不是手机的判据。

约定
----
见 `scripts/e2e/README.md`：退出码 0=PASS / 1=FAIL / 2=用法或环境错误；非 dry-run 在最后
打印**恰好一行** `result PASS <n>` 或 `result FAIL <n> failed=a,b,c`；`--dry-run` 完全不碰
网络与设备、也不打印 `result` 行。所有界面操作都走 uiautomator dump 解析出的 bounds，
从不使用猜的像素坐标。
"""

import argparse  # noqa: F401  (由 relay_e2e.main_wrapper 的 extra_args 使用)
import json
import os
import random
import re
import socket
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay_e2e as R  # noqa: E402

# ---------------------------------------------------------------------------
# 协议常量（出处见各条的注释）
# ---------------------------------------------------------------------------

T_SHARE = R.T_SHARE                    # "share"
T_SCAN_RANGES = R.T_SCAN_RANGES        # "scan-ranges"
T_UPDATE = R.T_UPDATE                  # "update"
T_PEERS = R.T_PEERS                    # "peers"

# core/protocol/SharePayload.kt: ShareOps
SHARE_OPS = ("list", "list-ok", "deny", "get", "put")
# core/protocol/SharePayload.kt: ShareDenyReasons
SHARE_DENY_REASONS = ("off", "no-perm", "not-found", "too-deep", "busy", "gone")
# 默认权限的三种取值（CabinetScreen 的三个 Button 就是拿这三个字符串比对选中态）
SHARE_MODES = ("off", "read", "write")
# core/protocol/Limits.kt:110
SCAN_RANGES_PER_PACKET = 10
# core/protocol/CidrUtils.kt:14
SCAN_MAX_HOSTS = 1024
# core/protocol/Limits.kt:40
LIMIT_ID = 64

# UI 文案（composeResources/values/strings.xml，逐条给行号）
UI_CABINET = "文件柜"                       # L619 screen_cabinet
UI_SETTINGS = "设置"                        # L8 screen_settings
UI_MINE_TITLE = "我的文件柜"                  # L621 cabinet_mine_title
UI_SUB_ACTIVE = "正在共享 · 同事按下面的权限访问"   # L622
UI_SUB_INACTIVE = "还没有开启，同事看不到任何内容"  # L623
UI_STOP_SHARE = "停止共享"                    # L624 cabinet_stop_share
UI_NOT_CONFIGURED = "你还没有开文件柜"           # L625
UI_PICK_DIR = "选择共享目录"                   # L627 cabinet_pick_dir
UI_SHARE_DIR_TITLE = "共享目录"                # L628 cabinet_share_dir_title
UI_MODE_OFF = "不共享"                       # L633
UI_MODE_READ = "只读"                        # L634
UI_MODE_WRITE = "可读可传"                    # L635
UI_PERM_TITLE = "默认权限"                    # L632
UI_GRANTS_TITLE = "单独设过的同事"              # L639
UI_RECENT_TITLE = "最近有人放进来"              # L643
UI_BADGE_READONLY = "只读"                   # L650 cabinet_perm_badge_readonly
UI_BADGE_UPLOAD = "可上传"                    # L649
UI_PEERS_TITLE = "同事的文件柜"                # L648 cabinet_peers_title（入口栏通用标题）
UI_HOME_MARKER = "资料 v"                     # L487 设置首页资料卡的行尾标签（整行可点）
UI_BACK = "返回"                             # action_back（只有设置**子页**才有返回键）
UI_CONN_ROW = "连接"                          # L285 set_conn_title（首页那一行；网段卡在它里面）
UI_DOWNLOAD_COUNT = "下载 %d 项"              # L661 cabinet_download_count
UI_CIDR_TITLE = "网段探测与同步"                # L668 cidr_section_title
UI_CIDR_LABEL = "添加网段 (CIDR)"              # L670 cidr_input_label
UI_CIDR_ADD = "添加网段"                      # L672 cidr_add_action
UI_CIDR_SCAN = "立即探测"                     # L673 cidr_scan_now
UI_CIDR_SCANNING = "正在探测…"                 # L674
UI_CIDR_PROGRESS = "正在扫描"                  # L675 cidr_scan_progress 的前缀
UI_CIDR_EMPTY = "暂未配置扫描网段"               # L676
UI_CIDR_SOURCE_SELF = "手动添加"               # L677 cidr_source_self
UI_CIDR_SOURCE_PEER = "来自对端 %s"            # L678 cidr_source_peer
UI_CIDR_DELETE = "删除"                       # L679

# CabinetScreen 里写死的那个"共享目录"，不是真的目录选择器
HARDCODED_ROOT = "~/Documents/Cabinet"
PEER_TITLE_SUFFIX = "的文件柜"                 # L647 cabinet_peer_title = "%1$s的文件柜"


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------


def _budget(args, cap):
    """`--secs` 是预算，不是死刑线：取两者较小值但至少 1 秒。"""
    return max(1.0, min(float(cap), float(args.secs)))


def phase(title):
    print("\n== %s ==" % title)
    sys.stdout.flush()


def settle(secs=0.8):
    time.sleep(secs)


def visible(limit=420):
    texts = [t for t in R.ui_texts() if t]
    return " | ".join(texts)[:limit] or "(空)"


def _screen_box():
    """从 dump 里所有节点的 bounds 推出屏幕范围（不猜像素）。"""
    xs = []
    ys = []
    for n in R.ui_all():
        b = getattr(n, "bounds", None)
        if b:
            xs += [b[0], b[2]]
            ys += [b[1], b[3]]
    if not xs:
        return None
    return (min(xs), min(ys), max(xs), max(ys))


def scroll_down(frac_top=0.80, frac_bottom=0.30):
    """在屏幕水平中心竖直上滑 = 向下翻内容。坐标由 dump 推出的屏幕范围算。"""
    box = _screen_box()
    if not box:
        return False
    x = (box[0] + box[2]) // 2
    y1 = box[1] + int((box[3] - box[1]) * frac_top)
    y2 = box[1] + int((box[3] - box[1]) * frac_bottom)
    R.adb("shell", "input", "swipe", str(x), str(y1), str(x), str(y2), "300")
    settle(0.5)
    return True


def scroll_up(frac_top=0.30, frac_bottom=0.80):
    box = _screen_box()
    if not box:
        return False
    x = (box[0] + box[2]) // 2
    y1 = box[1] + int((box[3] - box[1]) * frac_top)
    y2 = box[1] + int((box[3] - box[1]) * frac_bottom)
    R.adb("shell", "input", "swipe", str(x), str(y1), str(x), str(y2), "300")
    settle(0.5)
    return True


def scroll_to(text, max_swipes=14, exact=True, timeout=1.0, args=None):
    """LazyColumn 只组合可视区，所以先找、找不到再向下翻。"""
    node = R.find_ui(text=text, exact=exact, timeout=timeout)
    if node:
        return node
    cap = _budget(args, 24) if args else 24
    deadline = time.time() + cap
    for _ in range(max_swipes):
        if time.time() > deadline:
            break
        if not scroll_down():
            break
        node = R.find_ui(text=text, exact=exact, timeout=0.7)
        if node:
            return node
    return None


def tap_text(label, args=None, exact=True, bottom=False, timeout=4.0, scroll=False):
    node = scroll_to(label, args=args) if scroll else R.find_ui(
        text=label, exact=exact, bottom=bottom, timeout=timeout)
    if not node:
        return None
    R.tap_node(node)
    return node


def _edit_containing(label, timeout=4.0):
    """找到包含某个浮动标签的那个 EditText（label 画在输入框内部）。"""
    lab = R.find_ui(text=label, exact=False, timeout=timeout)
    cands = [n for n in R.ui_all() if "EditText" in (n.cls or "") and n.bounds]
    if lab is not None and lab.bounds:
        cx, cy = lab.center
        for n in cands:
            b = n.bounds
            if b[0] <= cx <= b[2] and b[1] <= cy <= b[3]:
                return n
    return cands[0] if cands else None


def _read_edit_like(node):
    """回读输入框内容：取中心离原节点最近的那个 EditText。"""
    cands = [n for n in R.ui_all() if "EditText" in (n.cls or "") and n.bounds]
    if not cands:
        return ""
    if node is not None and node.bounds:
        cx, cy = node.center
        cands.sort(key=lambda n: abs(n.center[0] - cx) + abs(n.center[1] - cy))
    return cands[0].text or ""


def type_into(node, text, tries=6):
    """往输入框里打字并**回读**确认（adb shell input text 会随机丢字符）。"""
    got = ""
    if node is None:
        return False, got
    for _ in range(tries):
        R.tap_node(node)
        settle(0.4)
        R.clear_focused_text()
        R.adb_input_text(text)
        settle(0.4)
        got = _read_edit_like(node)
        if got.strip() == text:
            return True, got
    return False, got


def ime_off(tag):
    """收掉软键盘（打完字之后**必须**收）。

    为什么必须收（2026-10-10 share run4 的四条红）：`type_into` 之后 IME 是开着的，
    窗口被压扁 ⇒ 网段列表那一行与底部整宽按钮「立即探测」都落到可视区之外
    （LazyColumn 不组合屏幕外的行），而 `scroll_down()` 的起手点 y=0.80*屏高 正落在
    键盘上 —— 那几下 swipe 不滚动页面，反而被 Gboard 当**滑行输入**把 "by" 打进输入框。
    真机读数（/tmp/probe_cidr_kbd.py，同一页同一行）：

        键盘开着   → 行=None（只剩输入框里那个同名字符串）、底部按钮=None、
                     scroll_down 后屏上多出 '192.168.2.80/30 by'
        press_back → 行=(258,1517)、底部按钮=(540,1705)、'手动添加' 也回来了

    `keyevent 111`（ESC）收不掉，只能 `press_back()`（R19 的结论）。
    """
    if R.ime_shown(timeout=3):
        R.log("%s：软键盘还开着，先按返回收掉（ESC 收不掉）" % tag)
        R.press_back()
        settle(0.8)
    return not R.ime_shown(timeout=2)


def _cidr_row(cidr, max_swipes=4, args=None):
    """在网段列表里找那一行 —— **排除输入框**。

    `find_ui(text=cidr, exact=True)` 会先撞上输入框里刚打进去的同一个字符串
    （真机实测：键盘开着时那一下返回的是 `cls=EditText` 的节点），那样
    `r90-cidr-added-to-range-list` 就退化成「输入框里有字」的假绿。行是 TextView。
    """
    def hit():
        for n in R.ui_all():
            if (n.text or "").strip() == cidr and "EditText" not in (n.cls or ""):
                return n
        return None

    node = hit()
    if node is not None:
        return node
    cap = _budget(args, 24) if args else 24
    deadline = time.time() + cap
    for _ in range(max_swipes):
        if time.time() > deadline:
            break
        if not scroll_down():
            break
        node = hit()
        if node is not None:
            return node
    return None



def _row_source_label(cidr, label, args=None, dy=120):
    """滚到那一行，再在**这一行的 y 带**里找来源文案。

    2026-10-10 share run7 的两条红（`r90-range-source-labelled-self`、
    `r91-range-source-labelled-from-peer`）都出自这里：来源文案是行的**第二行**，
    而刚手动添加 / 刚吸收的条目排在列表**最后**、贴着视口下沿 ⇒ 整屏 `find_ui`
    在 dump 里找不到它（同一份 dump 里上面那行 `/30` 的「来自对端 kexuejin」就在屏上，
    说明渲染没问题，是读的位置不对）。所以先 `_cidr_row` 滚到那一行，
    再只认它上下 `dy` 内的同名节点。
    """
    row = _cidr_row(cidr, max_swipes=6, args=args)
    if row is None:
        return False, "列表里找不到 %r 那一行" % cidr
    # 2026-10-10 全量套件 run5 的现场：`192.168.2.80/29` 那一行读到了（y=1550），
    # 但它的**第二行**（来源文案）在滚动区下沿被裁掉了 ⇒ dump 里没有。所以不是
    # 「y 太大」这一个阈值能判的：找不到就把那一行往上滚一点再看，最多 4 轮。
    for _ in range(4):
        for n in R.ui_all():
            if (n.text or "").strip() == label and abs(n.center[1] - row.center[1]) <= dy:
                return True, "%r（y=%d）的来源文案是 %r（y=%d）" % (
                    cidr, row.center[1], label, n.center[1])
        if not scroll_down():
            break
        settle(0.4)
        row = _cidr_row(cidr, max_swipes=3, args=args) or row
    return False, "%r（y=%d）附近没有来源文案 %r（往上滚 4 次仍没有）；当前可见：%s" % (
        cidr, row.center[1], label, visible(200))

def _tap_top_back():
    """点页面左上角那个没有 text/desc 的 IconButton（PeerCabinetView 的返回箭头）。"""
    box = _screen_box()
    if not box:
        return None
    ymax = box[1] + (box[3] - box[1]) // 6
    cands = [n for n in R.ui_all()
             if n.clickable and not (n.text or "") and not (n.desc or "")
             and n.bounds and n.bounds[1] <= ymax]
    if not cands:
        return None
    cands.sort(key=lambda n: (n.bounds[0], n.bounds[1]))
    R.tap_node(cands[0])
    return cands[0].center


_CIDR_TEXT_RE = re.compile(r"^\d{1,3}(?:\.\d{1,3}){3}/\d{1,2}$")


def _all_cidr_rows():
    """当前 dump 里所有像网段的那几行（排除输入框；也排除「例如 192.168.2.0/24」这种提示）。"""
    out = []
    for n in R.ui_all():
        t = (n.text or "").strip()
        if _CIDR_TEXT_RE.match(t) and "EditText" not in (n.cls or ""):
            out.append(t)
    return out


def _clear_all_ranges(args=None, max_rounds=8):
    """把网段列表清空，返回删掉的条目。

    2026-10-10 全量套件 run5 的现场：手机上攒了 3 条**上一次跑留下的**网段
    （两条 `10.99.x.0/24`，254 台主机各一条），`立即探测` 会把它们全扫一遍
    ⇒ R90 要等的「单播 entry 打到本机」在 15s 窗口里根本轮不到（同一轮
    `r90-range-source-labelled-self` 也红：我们那条 /29 被挤到列表最下面、
    第二行贴在下沿）。所以 R90 开始前先把列表清空，只留本次要加的那一条。
    这些条目本来就是夹具历轮加的（`source=peer` 的会由对端重新分享）。
    """
    removed = []
    for _ in range(max_rounds):
        rows = _all_cidr_rows()
        if not rows:
            break
        progressed = False
        for c in rows:
            if _tap_delete_near(c, args=args):
                removed.append(c)
                progressed = True
                settle(0.4)
        if not progressed:
            break
    return removed


def _tap_delete_near(cidr, args=None):
    """点某一行网段的「删除」（按 bounds 与那一行重叠挑，不猜坐标）。

    用 `_cidr_row`（排除输入框）而不是 `find_ui`：输入框里可能正好留着同一个字符串
    （`type_into` 打的），那样会挑到输入框、删不掉任何东西 —— 2026-10-10 share run4
    的收尾两条 `**没删掉** 网段 …` 就是这么来的。行也可能在**上面**（列表记住滚动位置），
    所以先往下找两屏、再回顶找一次。
    """
    row = _cidr_row(cidr, max_swipes=2, args=args)
    if row is None:
        for _ in range(3):
            if not scroll_up():
                break
            row = _cidr_row(cidr, max_swipes=2, args=args)
            if row is not None:
                break
    if row is None or not row.bounds:
        return False
    y1, y2 = row.bounds[1], row.bounds[3]
    best = None
    for n in R.ui_all():
        if (n.text or "") != UI_CIDR_DELETE or not n.bounds:
            continue
        if n.bounds[3] < y1 - 140 or n.bounds[1] > y2 + 140:
            continue
        if best is None or abs(n.center[1] - row.center[1]) < abs(best.center[1] - row.center[1]):
            best = n
    if best is None:
        return False
    R.tap_node(best)
    settle(0.6)
    return True


# ---------------------------------------------------------------------------
# CidrUtils 的 Python 镜像（core/protocol/CidrUtils.kt:22-83）
# ---------------------------------------------------------------------------


def format_ipv4(addr):
    return "%d.%d.%d.%d" % ((addr >> 24) & 0xFF, (addr >> 16) & 0xFF,
                            (addr >> 8) & 0xFF, addr & 0xFF)


def parse_cidr_base(text):
    """对齐 `CidrUtils.parseCidrBase`：前缀只收 8..30，hostCount = 2^host_bits - 2。"""
    if not isinstance(text, str):
        return None
    parts = text.strip().split("/")
    if len(parts) != 2:
        return None
    try:
        prefix = int(parts[1])
    except ValueError:
        return None
    if prefix < 8 or prefix > 30:
        return None
    octets = parts[0].split(".")
    if len(octets) != 4:
        return None
    try:
        o = [int(x) for x in octets]
    except ValueError:
        return None
    if any(v < 0 or v > 255 for v in o):
        return None
    host_bits = 32 - prefix
    host_count = 0 if host_bits >= 31 else (1 << host_bits) - 2
    base = (o[0] << 24) | (o[1] << 16) | (o[2] << 8) | o[3]
    mask = (0xFFFFFFFF << host_bits) & 0xFFFFFFFF
    return (prefix, base & mask, host_count)


def normalize_cidr(text):
    parsed = parse_cidr_base(text)
    if not parsed:
        return None
    return "%s/%d" % (format_ipv4(parsed[1]), parsed[0])


def cidr_hosts(text):
    parsed = parse_cidr_base(text)
    if not parsed:
        return None
    _, network, count = parsed
    if count <= 0 or count > SCAN_MAX_HOSTS:
        return None
    return [format_ipv4(network + i) for i in range(1, count + 1)]


def cidr_for_host(host_ip):
    """给一个主机地址，返回包含它的 /29（prefix 必须 ≥8，见 CidrUtils）。

    为什么是 /29 而不是"最小的 /30"：局域网里常驻的第三方 relay 原型节点
    （2026-10-10 现场是 `kexuejin`）分享的正是 `192.168.2.80/30` —— 与本机同段时，
    /30 会被手机**先**当作 `source="peer"` 条目吸收（「来自对端 kexuejin」），
    夹具再手动添加同一个 CIDR 只会撞上"已存在"分支（`handleAddScanRange` 只弹
    `ScanRangeAdded` 就 return）⇒ 既没有新的 self 行，`r90-range-source-labelled-self`
    也就无从判起。换一个**不同的** CIDR（/29）就不撞了。
    """
    o = [int(x) for x in host_ip.split(".")]
    return "%d.%d.%d.%d/29" % (o[0], o[1], o[2], o[3] & 0xF8)


# ---------------------------------------------------------------------------
# 载荷构造 + 本地校验镜像
# ---------------------------------------------------------------------------


def share_list_payload(req_id, path="", offset=0, snapshot_id=None):
    p = {"op": "list", "reqId": req_id, "path": path, "offset": offset}
    if snapshot_id is not None:
        p["snapshotId"] = snapshot_id
    return p


def validate_share_local(payload):
    """镜像 `Codec.validateShare`（Codec.kt:805+）。返回 None 或原因串。"""
    if not isinstance(payload, dict):
        return "not-object"
    op = payload.get("op")
    if not isinstance(op, str):
        return "op-not-string"
    req = payload.get("reqId")
    if not isinstance(req, str) or not req or len(req) > LIMIT_ID:
        return "reqId-bad"
    if op == "list":
        off = payload.get("offset")
        if not isinstance(off, int) or isinstance(off, bool) or off < 0:
            return "list-offset-bad"
        if "snapshotId" in payload and not isinstance(payload["snapshotId"], str):
            return "list-snapshotId-bad"
        return None
    return None if op in SHARE_OPS else "op-unknown"


def scan_ranges_payload(cidrs, added_at=None):
    """线上形状 = ScanRangesPayload(ranges=[ScanRangeSummary(cidr, addedAt)])。

    两个键一个都不能多：`Codec.decodeScanRanges`（Codec.kt:899-906）走的是
    `wireJson.decodeFromJsonElement`，而 wireJson 没开 `ignoreUnknownKeys`
    ⇒ 多一个键直接抛异常、整条消息返回 null。
    """
    ts = int(added_at if added_at is not None else R.now_ms())
    return {"ranges": [{"cidr": c, "addedAt": ts} for c in cidrs]}


def validate_scan_ranges_local(payload):
    """镜像 `Codec.validateScanRanges`（Codec.kt:468-478）。"""
    if not isinstance(payload, dict):
        return "not-object"
    ranges = payload.get("ranges")
    if not isinstance(ranges, list):
        return "ranges-not-array"
    if not ranges:
        return "ranges-empty"
    if len(ranges) > SCAN_RANGES_PER_PACKET:
        return "ranges-over-%d" % SCAN_RANGES_PER_PACKET
    for r in ranges:
        if not isinstance(r, dict):
            return "range-not-object"
        if set(r.keys()) != {"cidr", "addedAt"}:
            return "range-extra-or-missing-keys"
        cidr = r.get("cidr")
        if not isinstance(cidr, str) or not cidr or len(cidr) > 45:
            return "range-cidr-bad"
        if normalize_cidr(cidr) is None:
            return "range-cidr-does-not-normalize"
        added = r.get("addedAt")
        if not isinstance(added, int) or isinstance(added, bool) or added <= 0:
            return "range-addedAt-not-positive"
    return None


# ---------------------------------------------------------------------------
# 本地自检（--dry-run 的全部内容）
# ---------------------------------------------------------------------------


def local_checks(args):
    cidr = args.cidr or cidr_for_host(args.host_ip)
    hosts = cidr_hosts(cidr) or []

    # --- share:list 的形状 -------------------------------------------------
    ok_share = share_list_payload("req-abcdef01")
    R.check("local-share-list-payload-mirrors-validator",
            validate_share_local(ok_share) is None,
            "share:list 的线上形状 = op/reqId/path/offset，过 Codec.validateShare 的镜像")

    no_req = {"op": "list", "path": "", "offset": 0}
    R.check("local-share-list-needs-reqid",
            validate_share_local(no_req) == "reqId-bad",
            "没有 reqId（或 reqId 超过 Limits.ID=%d）→ validateShare 直接 false" % LIMIT_ID)

    R.check("local-share-offset-must-be-nonneg",
            validate_share_local(share_list_payload("req-1", offset=-1)) == "list-offset-bad",
            "offset < 0 → 拒（Codec.kt:810-811）")

    R.check("local-share-ops-and-deny-reasons",
            SHARE_OPS == ("list", "list-ok", "deny", "get", "put")
            and SHARE_DENY_REASONS == ("off", "no-perm", "not-found", "too-deep", "busy", "gone"),
            "ShareOps / ShareDenyReasons 与 SharePayload.kt 逐个对齐；"
            "本夹具只发 'list'，也只可能等到 'list-ok'/'deny'")

    # --- scan-ranges 的形状 ------------------------------------------------
    ok_sr = scan_ranges_payload([cidr], added_at=1)
    R.check("local-scan-ranges-payload-accepted",
            validate_scan_ranges_local(ok_sr) is None,
            "scan-ranges 的线上形状 = {\"ranges\":[{\"cidr\":…,\"addedAt\":…}]}，过校验镜像")

    R.check("local-scan-ranges-addedat-must-be-positive",
            validate_scan_ranges_local(scan_ranges_payload([cidr], added_at=0))
            == "range-addedAt-not-positive",
            "addedAt 必须 > 0（Codec.kt:475-476）；所以夹具不能拿 0 当占位符")

    R.check("local-scan-ranges-over-packet-cap",
            validate_scan_ranges_local({"ranges": [{"cidr": "10.%d.%d.0/24" % (i // 250, i % 250),
                                                    "addedAt": 1} for i in range(11)]})
            == "ranges-over-10",
            "一片最多 %d 条（Limits.kt:110 SCAN_RANGES_PER_PACKET）" % SCAN_RANGES_PER_PACKET)

    R.check("local-scan-range-summary-has-exactly-two-keys",
            all(set(r.keys()) == {"cidr", "addedAt"} for r in ok_sr["ranges"]),
            "ScanRangeSummary 只有 cidr/addedAt 两个字段；decodeScanRanges 用的是不容"
            "未知键的 wireJson（Codec.kt:899-906），多一个键整条返回 null")

    # --- CidrUtils 镜像 ----------------------------------------------------
    R.check("local-cidr-mirror-matches-cidrutils",
            normalize_cidr("192.168.2.55/24") == "192.168.2.0/24"
            and normalize_cidr("192.168.2.82/32") is None
            and normalize_cidr("192.168.2.82/31") is None
            and normalize_cidr("10.0.0.0/7") is None
            and normalize_cidr("garbage") is None,
            "前缀只收 8..30（CidrUtils.kt:30）⇒ **/31 和 /32 都是非法的**，"
            "所以夹具不能拿 /32 去钉单台主机")

    R.check("local-cidr-host-count-matches",
            len(cidr_hosts("192.168.2.0/24") or []) == 254
            and (cidr_hosts("192.168.2.0/24") or [""])[0] == "192.168.2.1"
            and (cidr_hosts("192.168.2.0/24") or [""])[-1] == "192.168.2.254",
            "hostCount = 2^host_bits - 2（CidrUtils.kt:38），展开时跳过网络地址与广播地址")

    R.check("local-cidr-for-host-contains-host",
            args.host_ip in hosts,
            "--host-ip=%s 落在本次网段 %s 的主机表里（%d 个）—— R90 的探活广播"
            "只有打到这里，本机的 UDP sink 才收得到" % (args.host_ip, cidr, len(hosts)))

    # --- 信封分层 ----------------------------------------------------------
    R.check("local-reliable-covers-share-and-update-not-peers",
            T_SHARE in R.RELIABLE_TYPES and T_UPDATE in R.RELIABLE_TYPES
            and T_PEERS not in R.RELIABLE_TYPES,
            "share/update 在 RELIABLE_TYPES 里（会拿到 ACK），peers 不在 —— "
            "这正是 R89 的「有 ACK 但没回应」与 R94 的「连 ACK 都没有」的分界")

    R.check("local-share-frame-would-pass-validatepayload",
            validate_share_local(ok_share) is None
            and T_SHARE in R.RELIABLE_TYPES,
            "⇒ 手机收到这条 share 帧会：1) 信封+载荷校验通过（Codec.kt:214 → validateShare）；"
            "2) handleReliable 无条件先回 ACK（Messenger.kt:142）；"
            "3) 投给控制器后被 CHAT_INBOUND_TYPES 挡掉（RelayControllerMessages.kt:229）")

    R.log("本次网段 = %s（--udp-listen=%d，sink 绑定失败则跳过线上读数）"
          % (cidr, args.udp_listen))


# ---------------------------------------------------------------------------
# UDP sink：收网段探活广播（R90 的线上判据）
# ---------------------------------------------------------------------------


class UdpSink(object):
    """只收不回的 UDP 监听端。

    `Discovery.announceTo(host, settings.udpPort)`（transport/Discovery.kt:127-134）
    把一条**裸 entry**（payload 是 profile，没有 probeId）发到 `host:udpPort`。
    共享库的 `UdpPeer` 绑的是随机端口，收不到；所以 R90 需要一个绑在
    `settings.udpPort`（默认 17878）上的独立 socket。
    """

    def __init__(self, port, bind="0.0.0.0", name="scan-sink"):
        self.port = port
        self.bind = bind
        self.name = name
        self.sock = None
        self.error = None
        self._frames = []
        self._garbage = []
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thr = None

    def start(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # 记下每个数据报的**目的地址**。`announceTo(host, udpPort)` 是单播
        # （目的 = 本机 IP），而周期性 `announce()` 是广播（目的 = 255.255.255.255
        # 或子网广播）—— 两者在 recvfrom 里长得一模一样。2026-10-10 share run4 里
        # `r90-scan-announces-entry-to-this-host` 就是被周期性广播**假绿**的：
        # 那一刻扫描按钮根本没找到（`r90-scan-button-present` 红），却 ok 了。
        # BSD/macOS 用 IP_RECVDSTADDR（Linux 上叫 IP_PKTINFO）把目的地址塞进 cmsg。
        self.dst_supported = False
        try:
            s.setsockopt(socket.IPPROTO_IP, socket.IP_RECVDSTADDR, 1)
            self.dst_supported = True
        except OSError as exc:
            self.dst_error = str(exc)
        try:
            s.bind((self.bind, self.port))
        except OSError as exc:
            self.error = str(exc)
            try:
                s.close()
            except OSError:
                pass
            return False
        s.settimeout(0.4)
        self.sock = s
        self._thr = threading.Thread(target=self._loop, name=self.name, daemon=True)
        self._thr.start()
        return True

    def _loop(self):
        while not self._stop.is_set():
            dst = None
            try:
                if self.dst_supported:
                    data, anc, _flags, addr = self.sock.recvmsg(
                        R.UDP_MAX_INBOUND, socket.CMSG_SPACE(4))
                    for level, typ, cmsg in anc:
                        if (level == socket.IPPROTO_IP
                                and typ == socket.IP_RECVDSTADDR and len(cmsg) >= 4):
                            dst = socket.inet_ntoa(cmsg[:4])
                else:
                    data, addr = self.sock.recvfrom(R.UDP_MAX_INBOUND)
            except socket.timeout:
                continue
            except OSError:
                break
            ts = time.time()
            try:
                obj = json.loads(data.decode("utf-8", "replace"))
            except ValueError:
                with self._lock:
                    self._garbage.append((ts, addr, data[:64]))
                continue
            with self._lock:
                self._frames.append((ts, addr, obj, dst))

    def stop(self):
        self._stop.set()
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass

    def frames(self, mtype=None, since=0.0):
        if self.sock is None:
            return []
        with self._lock:
            snap = list(self._frames)
        return [(ts, addr, obj, dst) for (ts, addr, obj, dst) in snap
                if ts >= since and (mtype is None or obj.get("type") == mtype)]

    def wait(self, pred, timeout, interval=0.05):
        """等一个满足 [pred] 的包；[pred] 收到 `(obj, dst)`。

        `dst` 是该包的目的地址（`IP_RECVDSTADDR`，拿不到时为 None）——
        R90 靠它把「单播给本机」与「周期性广播」分开。
        """
        if self.sock is None:
            return None
        deadline = time.time() + timeout
        seen = 0
        while True:
            with self._lock:
                snap = list(self._frames)
            for ts, addr, obj, dst in snap[seen:]:
                if pred(obj, dst):
                    return (ts, addr, obj, dst)
            seen = len(snap)
            if time.time() > deadline:
                return None
            time.sleep(interval)


# ---------------------------------------------------------------------------
# UI 阶段
# ---------------------------------------------------------------------------


def _leave_pages(nav_hint, max_back=3):
    """回到**带底部导航的根页**再找导航项。

    2026-10-10 share run6 的一串红全出自这里：上一轮 screen 夹具把手机留在了**聊天页**
    （现场 dump 里可见「屏幕协助已结束 (连接已断开)」「投屏夹具：请点右上角「屏幕协助」」
    「说点什么…」），而聊天页**没有底部导航** ⇒ `r88-nav-cabinet-reachable` /
    `r90-nav-settings-reachable` 一红带一片（R90 的界面断言全跟着红）。
    返回键在根页上会被 `BackHandler` 吃掉/把 app 退到后台，所以只在**看不到导航项**时才按，
    且按完立刻复查。
    """
    for _ in range(max_back):
        if R.find_ui(text=nav_hint, exact=True, bottom=True, timeout=1.0) is not None:
            return True
        R.press_back()
        settle(0.6)
    return R.find_ui(text=nav_hint, exact=True, bottom=True, timeout=2.0) is not None


def open_cabinet(args):
    R.ensure_app_foreground()
    _leave_pages(UI_CABINET)
    node = R.find_ui(text=UI_CABINET, exact=True, bottom=True, timeout=_budget(args, 8))
    if node is None:
        R.check("r88-nav-cabinet-reachable", False,
                "底部导航里找不到 %r；当前可见：%s" % (UI_CABINET, visible()))
        return False
    R.tap_node(node)
    settle(1.2)
    R.check("r88-nav-cabinet-reachable", True,
            "点底部导航 %r（bounds 中心 %s）进入文件柜页" % (UI_CABINET, node.center))
    # 文件柜是 LazyColumn，切回来时**保留上次的滚动位置**；标题「我的文件柜」在最上面，
    # 不先滚回顶部就会读不到（2026-10-09 全量套件 run2 的 r88-mine-cabinet-title 就是这么红的）。
    if R.find_ui(text=UI_MINE_TITLE, exact=True, timeout=1.0) is None:
        for _ in range(3):
            if not scroll_up():
                break
            if R.find_ui(text=UI_MINE_TITLE, exact=True, timeout=1.0):
                break
    # 滚回顶部还是没有：多半是**停在对端文件柜视图**里（上一轮结束时留下的状态；
    # 或者 R89 相位看完对端柜没退回来）。那一页没有「我的文件柜」标题，系统返回键
    # 也退不出，只有左上角那个没有文字/desc 的返回箭头能回去。
    # 2026-10-10 share run4 的 r88-mine-cabinet-title 就是这么红的 —— 日志里紧接着
    # 出现「从对端文件柜退回我的文件柜：已点左上返回箭头 (96, 224)」。
    if R.find_ui(text=UI_MINE_TITLE, exact=True, timeout=1.0) is None:
        back = _tap_top_back()
        if back is not None:
            R.log("文件柜页上找不到「%s」，点左上返回箭头 %s 退回根页" % (UI_MINE_TITLE, back))
            settle(0.9)
    R.check("r88-mine-cabinet-title", R.find_ui(text=UI_MINE_TITLE, exact=True, timeout=3.0) is not None,
            "页面标题是「%s」；当前可见：%s" % (UI_MINE_TITLE, visible(240)))
    return True


def open_settings(args):
    R.ensure_app_foreground()
    _leave_pages(UI_SETTINGS)
    node = R.find_ui(text=UI_SETTINGS, exact=True, bottom=True, timeout=_budget(args, 8))
    if node is None:
        R.check("r90-nav-settings-reachable", False,
                "底部导航里找不到 %r；当前可见：%s" % (UI_SETTINGS, visible()))
        return False
    R.tap_node(node)
    settle(1.2)
    return R.check("r90-nav-settings-reachable", True,
                   "点底部导航 %r（bounds 中心 %s）进入设置页" % (UI_SETTINGS, node.center))


def open_settings_conn(args):
    """设置 tab → **「连接」子页**（网段探测卡只在这里，首页上没有）。

    两件事都会让 R90 全条假红（run2 就是：`r90-*` 7 条一起红，证据里屏幕停在设置**首页**）：
      1. 设置 tab 会停在上次进过的子页（messaging 夹具为了开「消息落盘」进过
         「数据与存储」）⇒ 用「首页有资料卡 `资料 vN`、子页没有」把它们分开；
      2. 网段卡在 `SettingsScreen.kt:566 SettingsConnectionSubpage` 里，
         首页只有分组标签「连接与通知」和导航行（`set_conn_title` = 「连接」）。

    **不要靠 content-desc「返回」认子页**：`ui/components/TopBars.kt:83` 的返回箭头
    `contentDescription = null`（真机 dump 里那个 IconButton 没有任何 text/desc），
    所以「找不到返回键 ⇒ 不是子页」这条推理是错的 —— 2026-10-10 的探针就撞上了：
    页面明明停在「连接」子页（屏上就是网段探测卡），却被判成"既不是首页也不是子页"
    直接返回 False，把 R90 的界面断言全部带红。改成**按内容认**：有网段探测卡就是子页。
    """
    if not open_settings(args):
        return False
    for _ in range(3):
        if R.find_ui(text=UI_CIDR_TITLE, exact=False, timeout=1.0):
            return True                      # 已经在「连接」子页上
        if R.find_ui(text=UI_HOME_MARKER, exact=False, timeout=1.0):
            break                            # 在设置首页
        # 既不是首页也不是「连接」子页 ⇒ 停在别的子页上，退回去。
        R.log("设置 tab 停在别的子页上（既没有 %r 也没有 %r）；当前可见：%s"
              % (UI_HOME_MARKER, UI_CIDR_TITLE, visible(160)))
        R.press_back()
        settle(0.8)
    row = R.find_ui(text=UI_CONN_ROW, exact=True, timeout=4.0)
    if row is None:
        R.log("设置首页上找不到 %r 那一行；当前可见：%s" % (UI_CONN_ROW, visible(200)))
        return False
    R.tap_node(row)
    settle(1.0)
    return R.find_ui(text=UI_CIDR_TITLE, exact=False, timeout=5.0) is not None


def cabinet_phase(args, peer, prof, caps):
    """R88（开关 / 目录 / 权限三档）+ R89（对端文件柜视图）。"""
    phase("R88 我的文件柜：共享开关 / 目录 / 权限三档")
    if args.skip_ui:
        R.log("--skip-ui：R88/R89 的界面断言全部跳过")
        return False
    if not open_cabinet(args):
        return False

    has_root = R.find_ui(text=UI_SUB_ACTIVE, exact=True, timeout=1.5) is not None
    if not has_root:
        R.check("r88-not-configured-state-shown",
                R.find_ui(text=UI_NOT_CONFIGURED, timeout=3.0) is not None
                and R.find_ui(text=UI_PICK_DIR, timeout=2.0) is not None,
                "shareRoot=null 时画的是「%s」+「%s」（CabinetScreen.kt:251-275）"
                % (UI_NOT_CONFIGURED, UI_PICK_DIR))
        R.check("r88-no-share-dir-card-before-root",
                R.find_ui(text=UI_SHARE_DIR_TITLE, timeout=1.5) is None
                and R.find_ui(text=UI_PERM_TITLE, timeout=1.0) is None,
                "还没设根目录，所以「共享目录」卡与三档默认权限都还没画出来")

        btn = R.find_ui(text=UI_PICK_DIR, timeout=3.0)
        if btn is not None:
            R.tap_node(btn)
            settle(1.2)
        R.check("r88-share-root-is-a-hardcoded-literal",
                R.find_ui(text=HARDCODED_ROOT, timeout=3.0) is not None,
                "点「%s」之后「共享目录」直接显示 %r —— CabinetScreen 的 onClick 就是"
                " `onSetShareRoot(\"~/Documents/Cabinet\")`，**没有目录选择器**，"
                "连系统 SAF 都没走（对比 R34 的 file picker 在 e2e_files.py 里是另一条路）"
                % (UI_PICK_DIR, HARDCODED_ROOT))
    else:
        R.log("设备本次已经处在「正在共享」状态（shareRoot 非空）——"
              "「还没开文件柜」那两条本次不成立，不判失败，也不当作通过")

    R.check("r88-perm-three-modes-present",
            all(R.find_ui(text=t, exact=True, timeout=1.0) is not None
                for t in (UI_MODE_OFF, UI_MODE_READ, UI_MODE_WRITE)),
            "默认权限三档「%s」/「%s」/「%s」都在（CabinetScreen.kt:306-330 的三个 Button）。"
            "**只断言存在**：选中态是背景色，dump 里读不到；而且权限是否真的生效要对端发"
            " share:list 才看得出来，那条路不存在（见 r89-* ）"
            % (UI_MODE_OFF, UI_MODE_READ, UI_MODE_WRITE))
    R.check("r88-stop-share-button-present",
            R.find_ui(text=UI_STOP_SHARE, timeout=2.0) is not None,
            "有了根目录才画「%s」" % UI_STOP_SHARE)
    R.log("页面上还有「%s」与「%s」两张卡；单独授权与最近动态本次不驱动（都要对端"
          "真的访问过文件柜才有人）"
          % (R.find_ui(text=UI_GRANTS_TITLE, timeout=1.0) is not None and UI_GRANTS_TITLE or "(未渲染)",
             R.find_ui(text=UI_RECENT_TITLE, timeout=1.0) is not None and UI_RECENT_TITLE or "(未渲染)"))

    # 设过根目录之后，手机应当重新广播带 shr1 的 profile。
    t0 = time.time()
    caps2 = list(caps)
    deadline = time.time() + _budget(args, 20)
    while time.time() < deadline:
        p = peer.phone_profile
        if isinstance(p, dict):
            c = list(p.get("caps") or [])
            if c != list(caps):
                caps2 = c
                break
            if R.CAP_SHARE in c:
                caps2 = c
                break
        time.sleep(0.5)
    R.check("r88-shr1-appears-after-share-root-set", R.CAP_SHARE in caps2,
            "设过共享目录（%.1fs 后读到的 caps=%s）%s %s —— RelaySettings.kt:196-201 "
            "isShareEnabled() 转真 ⇒ :213 才 add(CAP_SHARE)"
            % (time.time() - t0, caps2, "含" if R.CAP_SHARE in caps2 else "**仍不含**", R.CAP_SHARE))

    phase("R89 浏览对端文件柜 —— 本机从不发 share:list")
    R.check("r89-phone-never-sent-any-share-frame",
            peer.frames(mtype=T_SHARE) == [],
            "从握手到现在，手机一个 share 帧都没发过 —— handleLoadCabinetPath 的真机分支"
            "（RelayControllerCabinet.kt:104-112）只有一行 `loading = false`")

    card = R.find_ui(text=args.nick, exact=True, timeout=3.0)
    if card is None:
        R.log("「我的文件柜」里没有列出对端（state.humanPeers.filter{online} 为空，"
              "CabinetScreen.kt:188）—— R89 的对端视图断言本次驱动不了")
        return True

    texts = [t for t in R.ui_texts() if t]
    digit_titles = [t for t in texts if t.endswith(PEER_TITLE_SUFFIX)
                    and t[:-len(PEER_TITLE_SUFFIX)].isdigit()]
    nick_title = args.nick + PEER_TITLE_SUFFIX
    R.check("r89-peer-bar-title-uses-generic-title",
            R.find_ui(text=UI_PEERS_TITLE, exact=True, timeout=2.0) is not None
            and not digit_titles and nick_title not in texts,
            "对端入口栏的标题是**通用文案** %r，不是把人数塞进 `%%1$s的文件柜` ——"
            " CabinetScreen.kt:226-231 用的是 cabinet_peers_title，:421 的对端视图才用"
            " cabinet_peer_title + peerName。本次 dump：数字标题=%r，%r 在不在=%s"
            "（这条原来是**断言缺陷**的（『标题把人数字塞进去了』），2026-10-09 产品改好后"
            "翻成断言正确行为）"
            % (UI_PEERS_TITLE, digit_titles[:3], nick_title, nick_title in texts))

    R.tap_node(card)
    settle(1.2)
    R.check("r89-peer-cabinet-view-title-uses-peer-name",
            R.find_ui(text=nick_title, timeout=3.0) is not None,
            "进对端视图后标题是 %r（CabinetScreen.kt:421 用法正确）" % nick_title)
    zero = R.find_ui(text="共 0 项", exact=True, timeout=3.0)
    R.check("r89-peer-cabinet-is-always-empty",
            zero is not None,
            "「共 %%1$d 项」读的是 cabinet.totalEntries，真机分支从来不回填 ⇒ 恒为 0。"
            "这一条同时是 R89 的**功能面不可覆盖**的证据：没有 list-ok 就没有条目，"
            "没有条目就没有面包屑下一层、没有可勾选的文件、也没有可下载的东西")
    R.check("r89-peer-cabinet-readonly-badge",
            R.find_ui(text=UI_BADGE_READONLY, timeout=2.0) is not None,
            "权限徽章默认「%s」（currentPerm 不是 \"write\" 时取 readonly，"
            "CabinetScreen.kt:425）" % UI_BADGE_READONLY)
    R.check("r89-no-download-affordance-without-entries",
            not [t for t in texts if t.startswith("下载 ")],
            "没有条目就没有「%s」可点 —— handleDownloadCabinetEntries 本身也只弹"
            " CabinetDownloadStarted 横幅，一个字节都不传"
            "（RelayControllerCabinet.kt:129-132）" % (UI_DOWNLOAD_COUNT % 0))
    return True


def share_wire_phase(args, peer):
    """R89 的线上诚实读数 + R93/R94。"""
    phase("R89 入站 share 帧：拿到 ACK，然后什么都不发生")
    req = "req-%s" % R.uniq_marker("", 8)
    env_share = peer.env(T_SHARE, share_list_payload(req, path="", offset=0))
    peer.send(env_share)
    ack = peer.wait_ack(env_share["id"], _budget(args, 8))
    R.check("r89-inbound-share-list-is-acked-by-transport", ack is not None,
            "share 在 RELIABLE_TYPES 里 ⇒ handleReliable 无条件先回 ACK"
            "（Messenger.kt:142），**与它有没有实现无关**")

    reply = peer.wait(lambda e: e.get("type") == T_SHARE
                      and (e.get("payload") or {}).get("reqId") == req, _budget(args, 8))
    R.check("r89-inbound-share-list-gets-no-reply", reply is None,
            "%ds 内没有 share{reqId=%s} 的任何回应 —— 既没有 list-ok 也没有 deny。"
            "RelayControllerMessages.kt:229 `if (envelope.type !in CHAT_INBOUND_TYPES) return`，"
            "而 CHAT_INBOUND_TYPES 只有 msg（RelayController.kt:739）。"
            "协议里连 ShareDenyReasons.off 都没人发：拒绝也要有人写代码"
            % (int(_budget(args, 8)), req[:14]))

    R.log("R89 的结论：共享文件柜在 relay 侧是**只有 UI 壳**的功能 —— "
          "编解码器（Codec.kt:836）零调用点、控制器入站分支不存在、"
          "下载只有横幅。矩阵 R88/R89 标成「只有截图门禁，无行为单测」是准确的")

    phase("R93/R94 update 与 peers：已知但有意不处理")
    env_upd = peer.env(T_UPDATE, {})
    peer.send(env_upd)
    R.check("r93-update-envelope-is-acked-then-dropped",
            peer.wait_ack(env_upd["id"], _budget(args, 8)) is not None,
            "update 在 RELIABLE_TYPES 里（先拿 ACK）同时在 UNHANDLED 里"
            "（Discovery.onDatagram 显式丢弃）—— 契约把它列为「有意不做」，"
            "这条断言钉住的是「不会被当成未知类型连 ACK 都不回」")
    env_peers = peer.env(T_PEERS, {})
    peer.send(env_peers)
    R.check("r94-peers-envelope-is-neither-acked-nor-handled",
            peer.wait_ack(env_peers["id"], 3.0) is None,
            "peers 既不在 RELIABLE_TYPES（不回 ACK）也不在 HANDLED（不处理）——"
            "gossip 在 relay 侧就是关掉的")


def cidr_phase(args, peer, sink, prof):
    """R90（网段探活）+ R91（入站吸收）。"""
    phase("R90 scan-ranges：CIDR 网段探活")
    cidr = args.cidr or cidr_for_host(args.host_ip)
    hosts = cidr_hosts(cidr) or []
    R.log("本次网段 = %s，主机表 %d 个，本机 %s 在表里：%s"
          % (cidr, len(hosts), args.host_ip, args.host_ip in hosts))

    if args.skip_ui:
        R.log("--skip-ui：R90/R91 的界面断言跳过（R91 的线上读数照跑）")
    else:
        on_conn = open_settings_conn(args)
        if not on_conn:
            R.log("R90 的界面路径没走到「%s」子页 —— 下面几条会各自失败（每条都带当前可见文本）"
                  % UI_CONN_ROW)
        node = scroll_to(UI_CIDR_TITLE, args=args) if on_conn else None
        R.check("r90-settings-cidr-section-reachable", node is not None,
                "设置 →「%s」子页里滚到「%s」区块；当前可见：%s"
                % (UI_CONN_ROW, UI_CIDR_TITLE, visible(220)))

        if on_conn:
            gone = _clear_all_ranges(args=args)
            if gone:
                R.log("R90 开始前清掉残留网段 %d 条：%s（否则「%s」要把它们全扫一遍）"
                      % (len(gone), gone, UI_CIDR_SCAN))
            scroll_to(UI_CIDR_TITLE, args=args)

        field = _edit_containing(UI_CIDR_LABEL) if on_conn else None
        R.check("r90-cidr-input-found-by-label",
                field is not None,
                "按「%s」的 bounds 定位到包住它的那个 EditText（%s）"
                % (UI_CIDR_LABEL, field.center if field else None))
        typed, got = type_into(field, cidr) if field is not None else (False, None)
        R.check("r90-typed-cidr-reads-back", typed,
                "输入框回读 == %r（adb shell input text 会随机丢字符，必须回读）；"
                "本次实读 %r" % (cidr, got))

        btn = R.find_ui(text=UI_CIDR_ADD, exact=True, timeout=3.0) if on_conn else None
        if btn is not None:
            R.tap_node(btn)
            settle(1.0)
        # 打完字键盘还开着 ⇒ 下面所有列表/底部按钮的读法都会落空（见 ime_off 的现场）。
        if on_conn:
            ime_off("R90 读列表之前")
        R.check("r90-cidr-added-to-range-list",
                on_conn and _cidr_row(cidr, max_swipes=4, args=args) is not None,
                "列表里出现了 %s（handleAddScanRange 存 ScanRangeEntry(source=\"self\")）；"
                "当前可见：%s" % (cidr, visible(200)))
        ok_self, why_self = (_row_source_label(cidr, UI_CIDR_SOURCE_SELF, args=args)
                             if on_conn else (False, "没走到「%s」子页" % UI_CONN_ROW))
        R.check("r90-range-source-labelled-self", ok_self,
                "来源文案是「%s」（读的是那一行的 y 带，不是整屏）—— %s"
                % (UI_CIDR_SOURCE_SELF, why_self))

        t_scan = time.time()
        scan_btn = R.find_ui(text=UI_CIDR_SCAN, exact=True, bottom=True, timeout=3.0) if on_conn else None
        R.check("r90-scan-button-present", scan_btn is not None,
                "底部那个整宽 Button 文案「%s」（scanProgress != null 时会禁用）"
                % UI_CIDR_SCAN)
        if scan_btn is not None:
            R.tap_node(scan_btn)
        prog = R.find_ui(text=UI_CIDR_PROGRESS, exact=False,
                         timeout=_budget(args, 10)) if scan_btn is not None else None
        R.check("r90-scan-progress-text-appears", prog is not None,
                "出现进度文案（%s <网段>: n / N (发现 k)，strings.xml:675）—— "
                "handleTriggerCidrScan 每台主机写一次，扫完 2s 才清空。"
                "本次读数：%r" % (UI_CIDR_PROGRESS, (prog.text if prog else None)))
        R.log("触发扫描后 %.1fs 内读到进度行" % (time.time() - t_scan))

    # 线上读数：探活广播有没有真的发出来。
    #
    # 判据必须区分**单播**与**广播**：手机平时每 30s 就 `announce()` 一次广播
    # （目的 255.255.255.255 / 子网广播），本 sink 也收得到；而 R90 要证的
    # `announceTo(host, udpPort)` 是**逐台单播**（目的 = 本机 IP）。
    # 2026-10-10 share run4 这条就是被周期性广播假绿的（那一刻扫描按钮都没找到）。
    # sink 用 IP_RECVDSTADDR 记下每个包的目的地址，这里要求它是本机 IP。
    if sink is not None and sink.sock is not None:
        host_ip = args.host_ip

        def _unicast_entry(o, dst):
            if o.get("type") != R.T_ENTRY:
                return False
            # 目的地址读不到时只能退回「收到就算」（并在 detail 里写明）。
            return (not sink.dst_supported) or dst == host_ip

        hit = sink.wait(_unicast_entry, _budget(args, 30))
        if hit is None and sink.dst_supported:
            # 退一步：看看有没有**广播**的 entry 混进来，好把「没发单播」与
            # 「压根没收到」分开（两者对产品的结论不同）。
            bcast = sink.wait(lambda o, _dst: o.get("type") == R.T_ENTRY, 1.0)
            if bcast is not None:
                R.log("只收到广播 entry（目的 %r），没有发给本机 %s 的单播 entry"
                      % (bcast[3], host_ip))
        R.check("r90-scan-announces-entry-to-this-host", hit is not None,
                "本机 :%d 收到手机**单播**给 %s 的 entry —— Discovery.announceTo(host, udpPort)"
                "（transport/Discovery.kt:127-134）逐台发裸 entry。"
                "没收到就说明探活没打到本机（网段里没有本机 IP / udpPort 不对 / 没触发）；"
                "目的地址可读=%s，本次实读 %r"
                % (args.udp_listen, host_ip, sink.dst_supported, hit[3] if hit else None))
        if hit is not None:
            p = ((hit[2].get("payload") or {}).get("profile") or {})
            R.check("r90-announce-entry-carries-phone-profile",
                    p.get("nodeId") == prof.get("nodeId") and bool(p.get("nick")),
                    "那条 entry 的 payload.profile 就是手机自己的资料"
                    "（nick=%r nodeId=%s）" % (p.get("nick"), str(p.get("nodeId"))[:8]))
    else:
        R.log("UDP sink 没能绑上 :%d（%s）—— R90 的线上读数本次跳过，"
              "只留下界面断言。**跳过不是通过**"
              % (args.udp_listen, (sink.error if sink else "未创建")))

    phase("R91 scan-ranges 入站自动吸收对端网段")
    foreign = args.peer_cidr or ("10.99.%d.0/24" % random.randint(1, 254))
    payload = scan_ranges_payload([foreign])
    env_sr = peer.env(T_SCAN_RANGES, payload)
    # **不要等 ACK**：`scan-ranges` 是 gossip 帧，**不在** RELIABLE_TYPES 里
    # （`core/src/commonMain/kotlin/com/kapp/tidings/core/protocol/MessageTypes.kt:58-63`
    # = MSG/FILE_CTL/GROUP/AVATAR/UPDATE/SHARE/SCREEN/AGENT_*，夹具自己的镜像
    # `scripts/e2e/relay_e2e.py:78-82` 同样不含它）⇒ 手机**有意**不回 ACK，和 `peers`
    # 一样。2026-10-09 run2 的 `r91-inbound-scan-ranges-acked` 是**照着一个错前提写的**
    # 断言（原 detail 还写着"在 RELIABLE_TYPES 里 ⇒ 无条件 ACK"），那条红不是产品缺陷。
    # 真正可观测的验收信号是下面三条：吸收进列表、来源标成对端、以及随后回发。
    R.check("r91-scan-ranges-is-gossip-not-reliable",
            T_SCAN_RANGES not in R.RELIABLE_TYPES and T_PEERS not in R.RELIABLE_TYPES,
            "scan-ranges 与 peers 同为 gossip 帧、不要求 ACK；等 ACK 会永远等不到")
    peer.send(env_sr)
    settle(0.5)
    if not args.skip_ui:
        ime_off("R91 读列表之前")
    R.check("r91-inbound-cidr-absorbed-into-list",
            _cidr_row(foreign, max_swipes=10, args=args) is not None,
            "%s 出现在设置页的网段列表里 —— handleOnScanRangesReceived 把它存成"
            " source=\"peer\" 的条目（RelayControllerCidr.kt）；当前可见：%s"
            % (foreign, visible(200)))
    ok_peer, why_peer = _row_source_label(foreign, UI_CIDR_SOURCE_PEER % args.nick, args=args)
    R.check("r91-range-source-labelled-from-peer", ok_peer,
            "来源文案是「%s」—— sourceNick 取的是 peerNick，所以夹具自己的 nick 必须"
            "出现在这里；对不上就是真的对不上 —— %s"
            % (UI_CIDR_SOURCE_PEER % args.nick, why_peer))
    # 「吸收后立刻回发」是**错前提**，2026-10-10 run5 实测红：等 15s 一条都没来。
    # 上游 RangeSync 只在三种时机分享网段（`/tmp/th_asar/out/main/index.js:7940-8005`
    # + `diagnostics-CdkN792t.js:65-68`）：启动/本机新增网段（`scheduleShareSoon`，
    # 抖动 `scanRangeShareInitialMin/Max` = **2–10 分钟**）、对端 online 事件
    # （`scheduleShareTo`）、以及 `scanRangeShareInterval` = **60 分钟**周期兜底。
    # 入站 handle 只 `acceptRanges`（index.js:8034），**不** scheduleShareSoon ⇒
    # "吸收后 15s 内回发"在参考实现里同样不会发生。
    # 真正可复现的触发点是**对端上线**：`composeApp/src/commonMain/kotlin/com/kapp/tidings/app/RelayController.kt:438`
    # 在 `PeerEvent.Updated` 里 `shareScanRangesToPeer(nodeId)`（无抖动），
    # 把本机全部网段 —— **含刚吸收的那条** —— 单播给该对端。
    # 所以先 `exit`（手机立刻 `registry.markOffline`）再 `entry(alive=True)` 造出这次上线事件。
    peer.send_exit()
    settle(2.5)
    peer.send_entry(alive=True)
    R.check("r91-reshare-on-peer-online-includes-absorbed-range",
            peer.wait(lambda e: e.get("type") == T_SCAN_RANGES
                      and foreign in json.dumps(e.get("payload") or {}),
                      _budget(args, 15)) is not None,
            "对端重新上线时，手机把本机全部网段（含刚吸收的 %s）单播给该对端 —— "
            "RelayController.kt:438 的 PeerEvent.Updated → shareScanRangesToPeer(nodeId)；"
            "上游 RangeSync 同样只在 registry online 时 scheduleShareTo，"
            "吸收本身不触发分享（初始分享抖动 2–10 分钟、周期兜底 60 分钟）" % foreign)
    return cidr, foreign


def cleanup(args, peer, cidr, foreign):
    """收尾尽力而为：失败只记日志，不算断言（不掩盖、也不虚报）。"""
    phase("收尾（尽力而为）")
    if not args.skip_ui:
        ime_off("收尾删网段之前")
        for c in (cidr, foreign):
            ok = _tap_delete_near(c, args=args)
            R.log("%s 网段 %s" % ("已删除" if ok else "**没删掉**", c))
        if not args.keep_cabinet_root:
            if open_cabinet(args):
                back = _tap_top_back()
                R.log("从对端文件柜退回我的文件柜：%s" % ("已点左上返回箭头 %s" % (back,) if back else "**没找到返回箭头**"))
                settle(0.8)
                stop = R.find_ui(text=UI_STOP_SHARE, exact=True, timeout=3.0)
                if stop is not None:
                    R.tap_node(stop)
                    settle(1.0)
                    R.log("已点「%s」还原共享开关" % UI_STOP_SHARE)
                else:
                    R.log("**没找到「%s」**——共享根目录可能留在设备上，"
                          "下次跑 --dry-run 无关，但真机复跑时 R88 会走"
                          "「设备已处在正在共享状态」那条分支" % UI_STOP_SHARE)
    peer.send_exit()
    R.log("已发 exit（payload {}）让手机在 ~2s 内把我们翻成离线")


# ---------------------------------------------------------------------------
# 主体
# ---------------------------------------------------------------------------


def run(args):
    sink = None
    peer = None
    try:
        phase("本地自检（不碰网络、不碰设备）")
        local_checks(args)
        R.log("本地断言 %d 条" % len(R._CHECKS))

        if args.dry_run:
            print("\n[dry-run] 不碰网络、不碰设备：跳过 adb、握手、界面驱动与 UDP sink。")
            print("[dry-run] 真机将执行：握手(caps 里查 shr1/av1) → 文件柜页"
                  "(未开柜态/三档权限/停止共享/写死的 ~/Documents/Cabinet) → "
                  "设根目录后等 caps 重新广播出 shr1 → 对端文件柜视图(标题把人数当名字/"
                  "共 0 项/只读徽章/无下载入口) → 入站 share:list(有 ACK、无回应) → "
                  "update(有 ACK 被丢)/peers(无 ACK) → 设置页加网段+立即探测"
                  "(进度行 + 本机 :%d 收到 entry) → 入站 scan-ranges 被吸收并回发 → "
                  "尽力收尾(删网段/还原共享开关/发 exit)。" % args.udp_listen)
            print("[dry-run] 不可覆盖（功能不存在）：R89 的下载与面包屑下一层、"
                  "R88 的权限真的生效、R92 的设置入口、R90 的「跨网段真的发现了新节点」。")
            return 0

        phase("设备与环境")
        R.require_device()
        # 握手前必须把应用拉到前台：**只有前台时手机才回 alive**（真机实测：先前的相位
        # 若点到「打开」把外部查看器拉起来，应用退到后台，紧接着的握手就 15s 超时、
        # `handshake-alive` 红 —— 2026-10-09 全量 run1 里 share 就是这么红的，而它前面的
        # messaging/discovery/files 都因为各自先拉到前台而正常）。
        R.ensure_app_foreground(timeout=15)

        phase("起 UDP sink（R90 的线上判据）")
        sink = UdpSink(args.udp_listen, name="scan-sink")
        if sink.start():
            R.log("UDP sink 已绑在 0.0.0.0:%d" % args.udp_listen)
        else:
            R.log("UDP sink 绑定 :%d 失败：%s —— R90 的线上断言会跳过"
                  % (args.udp_listen, sink.error))

        phase("起对端（UdpPeer）")
        peer = R.UdpPeer(args.phone_ip, args.phone_udp, nick=args.nick,
                         host=args.host_ip).start()
        R.log("对端 nick=%r 本机 %s:%d nodeId=%s"
              % (args.nick, args.host_ip, peer.port, peer.node_id[:8]))

        return _body(args, peer, sink)
    finally:
        if peer is not None:
            peer.stop()
        if sink is not None:
            sink.stop()


def _body(args, peer, sink):
    phase("握手")
    prof = peer.handshake(secs=_budget(args, 20), probe=True)
    if not R.check("handshake-alive", prof is not None,
                   "15s 内没握上手 —— ver 必须 ≤16 字符（Limits.VER），"
                   "platform 只能 win/mac/linux，profile 任一字段超限整帧被拒"):
        return None
    caps = list(prof.get("caps") or [])
    R.log("手机 profile nick=%r platform=%s tcpPort=%s ver=%r caps=%s"
          % (prof.get("nick"), prof.get("platform"), prof.get("tcpPort"),
             prof.get("ver"), caps))

    phase("caps：共享文件柜与头像的宣告面")
    shr1_before = R.CAP_SHARE in caps
    if shr1_before:
        R.log("caps 里已经有 %s —— 这台设备之前被设过共享目录，"
              "「默认不宣告 shr1」本次不成立（不判失败、也不当作通过）" % R.CAP_SHARE)
    else:
        R.check("r88-shr1-not-advertised-before-share-root", True,
                "caps=%s 里没有 %s —— RelaySettings.kt:162 shareRoot 默认 null ⇒"
                " :196-201 isShareEnabled() 为假 ⇒ :213 不加 CAP_SHARE。"
                "矩阵 §2 索引表把 shr1 记成「条件（isShareEnabled()）」是对的"
                % (caps, R.CAP_SHARE))
    R.check("r92-av1-never-advertised-in-caps", R.CAP_AVATAR not in caps,
            "caps 里没有 %s —— RelaySettings.caps() 对 av1 根本没有分支，"
            "所以「自定义头像」连协议宣告面都不存在（R92 的 UI 入口那一半"
            "本次无法覆盖：设置里第三档点了不生效，没有可断言的机器读数）"
            % R.CAP_AVATAR)

    ok = cabinet_phase(args, peer, prof, caps)
    share_wire_phase(args, peer)

    cidr = foreign = None
    if ok or args.skip_ui:
        cidr, foreign = cidr_phase(args, peer, sink, prof)
    else:
        R.log("文件柜页没打开 —— R90 需要设置页，仍然继续；但 R89 的界面断言本次缺失")
        cidr, foreign = cidr_phase(args, peer, sink, prof)

    cleanup(args, peer, cidr or args.cidr or cidr_for_host(args.host_ip), foreign or "-")
    return None


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def extra_args(ap):
    ap.add_argument("--udp-listen", type=int, default=R.UDP_PORT,
                    help="本夹具用来收网段探活广播的 UDP 端口（默认 %d，即手机 "
                         "settings.udpPort；被占用时 R90 的线上断言自动跳过）" % R.UDP_PORT)
    ap.add_argument("--cidr", default=None,
                    help="R90 要添加并扫描的网段；缺省按 --host-ip 推一个含本机的 /30"
                         "（CidrUtils 只收前缀 8..30，/32 是非法值）")
    ap.add_argument("--peer-cidr", default=None,
                    help="R91 冒充对端下发的网段；缺省随机 10.99.N.0/24")
    ap.add_argument("--skip-ui", action="store_true",
                    help="只跑线上断言，完全不做界面驱动")
    ap.add_argument("--keep-cabinet-root", action="store_true",
                    help="收尾时不点「停止共享」还原共享开关")
    ap.add_argument("--nick", default=R.DEFAULT_NICK, help="本夹具的对端昵称")
    ap.set_defaults(secs=420)


if __name__ == "__main__":
    sys.exit(R.main_wrapper(run, description=__doc__, extra_args=extra_args))
