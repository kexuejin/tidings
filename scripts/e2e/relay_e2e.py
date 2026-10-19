#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""捎话（relay）真机 e2e 夹具共享库。

这是 `scripts/relay-screen-fixture.py`（投屏夹具，已在 Pixel 6 上 PASS）的泛化抽取：
协议编解码、UDP 握手、TCP 分帧、adb 驱动、读数断言，全部照抄那份夹具的线上形状。
**协议层不要另起一套** —— 它是踩过坑才对的那一套。抽出来的每条不变量都带注释说明
"错了会怎样"，因为那些坑的现象都是无声的（丢帧、等你超时，而不是报错）。

CLI 契约（所有夹具一致，由 `main_wrapper()` 提供）：

    python3 scripts/e2e/e2e_xxx.py --phone <手机IP> [--host-ip <本机LAN IP>]
                                   [--secs N] [--dry-run]

输出契约：先逐项读数（`[EVID]` 行是证据，`[ ok ]` / `[FAIL]` 行是断言），
最后**恰好一行**：

    result PASS <总检查数>
    result FAIL <总检查数> failed=<逗号分隔的失败名单>

退出码：0=PASS，1=FAIL，2=用法/环境错误（例如 `adb devices` 里没有设备）。

`--dry-run` 只做参数与纯本地自检：**不碰网络、不碰设备**，退出码 0。
没有它，别人在没插手机时没法确认自己没把夹具写崩。

只依赖 Python 3 标准库。
"""

import argparse
import html
import json
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import traceback
import uuid

# ---------------------------------------------------------------------------
# 0. 协议常量 —— 逐条抄自 core/.../protocol/Limits.kt + MessageTypes.kt
#    这些数字不是"大约"，是判据。改一个就会静默丢帧。
# ---------------------------------------------------------------------------

PROTOCOL_VERSION = 1               # Envelope.v 必须严格等于它，否则整帧 reason=version
UDP_PORT = 17878                   # Protocol.DEFAULT_UDP_PORT / 发现层默认端口
TCP_PORT_DEFAULT = 17879           # Protocol.DEFAULT_TCP_PORT / Profile.tcpPort 默认值
UDP_MAX_PAYLOAD = 1200             # 出站单包上限：encodeToBytes(env).size > 1200 ⇒ 走 TCP
UDP_MAX_INBOUND = 4096             # 入站硬上限，超了 reason=oversize
TEXT_UDP_LIMIT = 800               # 文本走 UDP 的上限（也是 UDP 入站校验文本长度的上限）
TEXT_TCP_LIMIT = 4096              # 文本硬上限（TCP 入站校验）
TCP_MAX_FRAME = 64 * 1024          # TcpFramedChannel.MAX_FRAME
TCP_ACK_TIMEOUT_MS = 7000          # TcpFramedChannel 等 msg-ack 的精确超时
ACK_RETRY_SCHEDULE_MS = (1000, 2000, 4000)   # Timings.ackRetrySchedule
OFFLINE_AFTER_MS = 90000           # Timings.offlineAfter：存在性超时（A12 的 90 秒）
DIRECTED_REPLY_MS = 1000           # Timings.directedReplyInterval：带 probeId 的回 alive 延迟
ALIVE_DEDUP_MS = 10000             # Timings.aliveDedupWindow：不带 probeId 时同节点只回一次

# Limits：字段长度硬上限。VER 那条是真机踩过的坑（见 profile_caps 注释）。
LIMITS = {
    "nick": 32, "company": 32, "dept": 32, "team": 32, "host": 64,
    "ver": 16, "caps": 16, "cap_item": 16,
    "type": 32, "id": 64, "from": 64, "ip": 45,
}

# MsgTypes 常量
T_ENTRY, T_ALIVE, T_EXIT, T_PRESENCE, T_PROFILE = "entry", "alive", "exit", "presence", "profile"
T_PEERS, T_SCAN_RANGES = "peers", "scan-ranges"
T_MSG, T_ACK = "msg", "ack"
T_FILE_CTL, T_GROUP, T_AVATAR, T_UPDATE, T_SHARE, T_SCREEN = \
    "file-ctl", "group", "avatar", "update", "share", "screen"

# MsgTypes.RELIABLE：**必须回 ACK 的帧类型**（ENTRY/ALIVE/EXIT/PRESENCE/PROFILE/ACK 不在内）。
# 漏掉一种的后果不是报错，是对方按 ackRetrySchedule 重传到队列剪枝 —— 看起来只是"慢"。
RELIABLE_TYPES = frozenset([
    T_MSG, T_FILE_CTL, T_GROUP, T_AVATAR, T_UPDATE, T_SHARE, T_SCREEN,
    "agent-session", "agent-event", "agent-queue", "agent-run",
    "agent-approval", "agent-cancel", "agent-ack",
])

# MsgKinds
K_TEXT, K_GROUP_TEXT, K_RECALL, K_NUDGE, K_PK = "text", "group-text", "recall", "nudge", "pk"

# caps 常量（Capabilities）
CAP_MOBILE_TEXT = "tw1"        # 手机能收发文本 —— 唯一一个手机默认声明的文本能力
CAP_MOBILE_RECORD = "mrec1"
CAP_AGENT_SESSION = "agn1"
CAP_AGENT = "ag1"
CAP_TABLE_TEXT = "tbl1"
CAP_FILE_DIRECT = "fd1"
CAP_SHARE = "shr1"
CAP_REMOTE_VIEW = "rv1"
CAP_REMOTE_SHARE = "rvs1"
CAP_AVATAR = "av1"
CAP_AGENT_QUEUE = "agq1"
# PPE-1 增补 D1/D2（2026-10-14）：能收 agent-session 的四个新可选键（aws1）。
CAP_AGENT_WORKSPACE = "aws1"
# PPE-1 增补 D7（2026-10-14）：认识三张 grant 帧，且宿主投递前按策略过滤（agr1）。
CAP_AGENT_GRANT = "agr1"
DEFAULT_CAPS = (CAP_MOBILE_TEXT, CAP_MOBILE_RECORD)

# Platforms.ALL 只有 win/mac/linux —— 协议里**没有 mobile**，手机端自己伪装成 linux。
PLATFORMS = ("win", "mac", "linux")

DEFAULT_PHONE = "192.168.2.22"     # Pixel 6 @ LAN
DEFAULT_HOST_IP = "192.168.2.82"   # 本机 Mac 的 LAN IP
DEFAULT_SERIAL = "SERIAL-TEST-01"  # Pixel 6 adb 序列号
DEFAULT_NICK = "E2E夹具"
PKG = "app.relay"
LAUNCH_ACTIVITY = "app.relay/.app.MainActivity"
# 2026-10-10：原来写 `/sdcard/relay-e2e-ui.xml`，而 Pixel 6 的模拟存储走 FUSE，
# 跑久/负载高时会整片打嗝 —— `cat: /sdcard/relay-e2e-ui.xml: Transport endpoint is
# not connected`，随后每一次 dump 都失败（读数会退化成"什么都找不到"）。改用
# `/data/local/tmp`（shell 可写、不经 FUSE），这类失败就没了。
REMOTE_UI_DUMP = "/data/local/tmp/relay-e2e-ui.xml"


# ---------------------------------------------------------------------------
# 1. 错误类型 + 读数/证据输出
# ---------------------------------------------------------------------------

class EnvError(Exception):
    """用法或环境错误 ⇒ 退出码 2（不是 FAIL）。例如没插手机、TCP 端口被占。"""


class FixtureError(Exception):
    """夹具自身的错误参数 ⇒ 退出码 2。例如让 adb 注入它注入不了的东西。"""


_CHECKS = []          # [(name, ok, detail)]
_VERBOSE = True


def log(msg):
    """证据行。以 `[EVID]` 开头，方便和断言行分开抓。"""
    if _VERBOSE:
        print("[EVID] %s" % msg, flush=True)


def check(name, ok, detail=""):
    """累积一条读数并立即打印。返回 bool，方便 `if not check(...): return`。"""
    ok = bool(ok)
    _CHECKS.append((name, ok, detail))
    line = "[ ok ] %s" % name if ok else "[FAIL] %s" % name
    if detail:
        line += " — %s" % detail
    print(line, flush=True)
    return ok


def report():
    """结尾的**恰好一行** result。返回进程退出码。"""
    total = len(_CHECKS)
    failed = [n for (n, ok, _) in _CHECKS if not ok]
    if failed:
        print("result FAIL %d failed=%s" % (total, ",".join(failed)), flush=True)
        return 1
    print("result PASS %d" % total, flush=True)
    return 0


# ---------------------------------------------------------------------------
# 2. 协议层 —— 照抄 relay-screen-fixture.py
# ---------------------------------------------------------------------------

FIXTURE_NODE = str(uuid.uuid4())   # 本夹具的 nodeId：一个进程一次，进程内稳定
FIXTURE_HOST = ""                  # 在 main_wrapper 里赋成 --host-ip；entry 里 host 必须非空


def set_host(ip):
    """设置夹具 profile 里的 host 字段。entry 里 host 为空字符串时手机会整帧看不明白。"""
    global FIXTURE_HOST
    if not isinstance(ip, str) or not ip.strip():
        raise FixtureError("host-ip 不能为空：entry 的 profile.host 必须是非空字符串")
    if len(ip) > LIMITS["host"]:
        raise FixtureError("host-ip 超过 %d 字符" % LIMITS["host"])
    FIXTURE_HOST = ip
    return FIXTURE_HOST


def now_ms():
    return int(time.time() * 1000)


def envelope(mtype, payload, mid=None, node_id=None, ts=None):
    """线上信封。字段顺序 v/type/id/from/ts/payload —— 六个字段一个都不能少。

    `v` 必须 == Protocol.VERSION(1)：手机侧对不上就整帧丢弃，连 reason 都只在日志里。
    `id` 必须每帧唯一：手机侧的 dedupStore 按它去重，重复 id 会被当重传（并回 ACK）。
    """
    if len(mtype) > LIMITS["type"]:
        raise FixtureError("type 超过 %d 字符：%r" % (LIMITS["type"], mtype))
    env = {
        "v": PROTOCOL_VERSION,
        "type": mtype,
        "id": mid or str(uuid.uuid4()),
        "from": node_id or FIXTURE_NODE,
        "ts": now_ms() if ts is None else int(ts),
        "payload": payload,
    }
    if len(env["from"]) > LIMITS["from"]:
        raise FixtureError("from 超过 %d 字符" % LIMITS["from"])
    return env


def encode(env):
    """紧凑 JSON（无空格）。手机侧 encodeDefaults=false 的等价物是不写默认值。"""
    return json.dumps(env, ensure_ascii=False, separators=(",", ":"))


def utf8_len(s):
    return len(s.encode("utf-8"))


def profile_caps(nick=DEFAULT_NICK, caps=None, tcp_port=TCP_PORT_DEFAULT, host=None,
                 platform="linux", ver="e2e-0.1", node_id=None, profile_rev=0, avatar=0):
    """夹具的对端资料。字段与 wire 形状逐条抄自投屏夹具的 `profile_caps`。

    **`ver` 必须 ≤ 16 字符**（Limits.VER）。投屏夹具的注释记着这个坑：曾经写成
    `"relay-fixture-0.1"`（17 字符）⇒ 手机侧 `validateProfile` 把**整帧**拒收，
    连 entry 都握不上手，而现象只是"15s 内没握上手"，看不出任何别的原因。
    所以这里把每个长度都做成硬校验，宁可抛 FixtureError 也不要现场抓瞎。

    `platform` 只能取 win/mac/linux：协议里没有 mobile（手机端自己伪装成 linux），
    夹具有意声明成桌面端，手机才会把 `tcpPort` 那一列的 TCP 通道用起来。
    """
    host = set_host(host) if host else FIXTURE_HOST
    if not host:
        raise FixtureError("profile_caps 需要 host：先 set_host(ip) 或传 host=")
    caps = list(DEFAULT_CAPS if caps is None else caps)

    if not nick or len(nick) > LIMITS["nick"]:
        raise FixtureError("nick 必须 1..%d 字符，实际 %d" % (LIMITS["nick"], len(nick)))
    if len(ver) > LIMITS["ver"]:
        raise FixtureError(
            "ver=%r 有 %d 字符，超过 Limits.VER=%d ⇒ 手机侧 validateProfile 整帧拒收，"
            "现象只是「握不上手」，看不出别的原因" % (ver, len(ver), LIMITS["ver"]))
    if platform not in PLATFORMS:
        raise FixtureError("platform 只能取 %s（协议里没有 mobile）" % (PLATFORMS,))
    if len(caps) > LIMITS["caps"]:
        raise FixtureError("caps 最多 %d 项" % LIMITS["caps"])
    for c in caps:
        if not c or len(c) > LIMITS["cap_item"]:
            raise FixtureError("cap 项必须 1..%d 字符：%r" % (LIMITS["cap_item"], c))
    if not (0 < int(tcp_port) < 65536):
        raise FixtureError("tcpPort 非法：%r" % (tcp_port,))

    return {
        "nodeId": node_id or FIXTURE_NODE,
        "nick": nick,
        "company": "",
        "dept": "",
        "team": "",
        "avatar": int(avatar),
        "profileRev": int(profile_rev),
        "host": host,
        "platform": platform,
        "tcpPort": int(tcp_port),
        "ver": ver,
        "caps": caps,
    }


def entry_payload(profile, probe_id=None):
    """`entry` 的载荷 = `{"profile": {...}}`；带 probeId 时是定向握手（见 send_entry）。"""
    payload = {"profile": profile}
    if probe_id is not None:
        payload["probeId"] = probe_id
    return payload


def send_entry(sock, host, port, alive=False, probe=False, profile=None):
    """发 entry（或 alive）握手帧。**线上形状与投屏夹具逐字节一致**。

    默认（probe=False）发 `{"profile": {...}}`：这是投屏夹具的形状，照抄不改。
    手机端收到后延迟 `Random(0, min(2000+onlineCount/100*1000, 8000))ms` 回 alive，
    且同一 nodeId 在 `aliveDedupWindow=10s` 内**只回一次**（防开机风暴）——
    所以裸 entry 的握手是 0–8 秒的抖动，重发还可能被去重吃掉。

    probe=True 时额外带上 `probeId`（Protocol 的定向探测）：手机端走
    `scheduleAliveReply` 的 directed 分支 —— **固定 1 秒回 alive，且绕过 10s 去重**。
    夹具要的是确定性，所以握手一律用 probe=True；probe=False 留给"复现手机真实入口路径"。
    """
    p = profile or profile_caps()
    pid = str(uuid.uuid4()) if probe else None
    mtype = T_ALIVE if alive else T_ENTRY
    return send_env(sock, envelope(mtype, entry_payload(p, pid)), host, port)


def send_env(sock, env, host, port):
    data = encode(env).encode("utf-8")
    sock.sendto(data, (host, int(port)))
    return env


def tcp_frame(obj, body=b""):
    """TCP 分帧：4 字节**大端**长度前缀 + UTF-8 JSON 正文（+ 可选 body）。

    TcpFrame.decodeLength 读的就是这个大端长度；写成小端或变长整数都会让对端
    把长度当正文解析，结果是"连上了但一帧都不认"。
    """
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(raw) > TCP_MAX_FRAME:
        raise FixtureError("TCP 帧超过 MAX_FRAME=%d" % TCP_MAX_FRAME)
    return struct.pack(">I", len(raw)) + raw + body


def tcp_msg_frame(env):
    """**最容易写错的一点**：TCP 上发的不是裸信封，而是 `{"type":"msg","envelope":…}`。

    裸信封会被对端 `decodeTcpFrameObject` 的白名单直接判非法帧（它对 `type` 只认
    msg / msg-ack / file-* 这些），现象是"TCP 连上了、帧也发出去了，就是没有任何反应"。
    """
    return tcp_frame({"type": T_MSG, "envelope": env})


def tcp_msg_ack(ack_for):
    """TCP 上的投递确认。只认 `{"type":"msg-ack","ackFor":<被确认的信封 id>}`。"""
    return tcp_frame({"type": "msg-ack", "ackFor": ack_for})


def ack_env(env_to_ack, node_id=None, addr=None, sock=None):
    """UDP 上的 ACK 信封：`{"ackFor": <被 ack 的 envelope.id>}`。"""
    n = envelope(T_ACK, {"ackFor": env_to_ack["id"]}, node_id=node_id)
    if sock is not None and addr is not None:
        send_env(sock, n, addr[0], addr[1])
    return n


def parse_json(data):
    try:
        return json.loads(data.decode("utf-8"))
    except Exception:
        return None


def recv_exact(conn, n, deadline):
    """从 TCP 连接精确读 n 字节；超时/对端关闭返回 None。"""
    buf = b""
    while len(buf) < n:
        remain = deadline - time.monotonic()
        if remain <= 0:
            return None
        conn.settimeout(max(0.05, min(remain, 5.0)))
        try:
            chunk = conn.recv(n - len(buf))
        except socket.timeout:
            return None
        except OSError:
            return None
        if not chunk:
            return None
        buf += chunk
    return buf


def read_tcp_frame(conn, deadline):
    """读一帧：4 字节大端长度 + 该长度的 UTF-8 JSON。返回 dict 或 None。"""
    header = recv_exact(conn, 4, deadline)
    if header is None:
        return None
    (n,) = struct.unpack(">I", header)
    if n <= 0 or n > TCP_MAX_FRAME:
        return None
    body = recv_exact(conn, n, deadline)
    if body is None:
        return None
    return parse_json(body)


# ---------------------------------------------------------------------------
# 3. adb 层
# ---------------------------------------------------------------------------

_SERIAL = None


def resolve_serial():
    """挑一台设备：RELAY_E2E_SERIAL > 默认序列号 > 唯一在线设备。"""
    global _SERIAL
    if _SERIAL:
        return _SERIAL
    env_serial = os.environ.get("RELAY_E2E_SERIAL")
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    devs = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devs.append(parts[0])
    if not devs:
        raise EnvError("adb devices 里没有 state=device 的设备；先插上手机并 `adb devices` 确认：\n%s"
                       % out.strip())
    if env_serial and env_serial in devs:
        _SERIAL = env_serial
    elif DEFAULT_SERIAL in devs:
        _SERIAL = DEFAULT_SERIAL
    elif len(devs) == 1:
        _SERIAL = devs[0]
    else:
        raise EnvError("多台设备在线，请设 RELAY_E2E_SERIAL 指定：%s" % devs)
    return _SERIAL


def adb(*args, **kw):
    """跑一条 adb 命令（自动带 -s <serial>）。返回 stdout 文本。

    失败时把 stderr 拼在后面 —— 但**不抛异常**：有些 adb 命令本来就允许失败
    （比如 uiautomator dump 撞上「could not get idle state」），调用方要能看到原文。
    """
    serial = kw.pop("serial", None) or resolve_serial()
    timeout = kw.pop("timeout", 30)
    binary = kw.pop("binary", False)
    cmd = ["adb", "-s", serial] + [str(a) for a in args]
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise EnvError("adb 超时（%ss）：%s" % (timeout, " ".join(cmd[3:])))
    if binary:
        return p.stdout or b""
    out = (p.stdout or b"").decode("utf-8", "replace")
    if p.returncode != 0:
        out += (p.stderr or b"").decode("utf-8", "replace")
    return out


def require_device():
    """确认设备在、顺便把型号/分辨率/进程读进证据。环境不对就抛 EnvError（退出码 2）。"""
    serial = resolve_serial()
    model = adb("shell", "getprop", "ro.product.model").strip()
    size = adb("shell", "wm", "size").strip().replace("\n", " ")
    if not model:
        raise EnvError("设备 %s 不响应 `getprop`" % serial)
    log("device serial=%s model=%s %s" % (serial, model, size))
    return serial


def app_pid():
    return adb("shell", "pidof", PKG).strip()


def _dump_is_ours(nodes):
    """这份 dump 是不是"我们关心的界面"（本 app 或 systemui 的系统对话框）。

    2026-10-10 全量套件 run7 的现场：`uiautomator dump` **成功**了，但内容是**启动器的
    应用抽屉**（`搜索 | Google 应用 | … | Chrome | Clash | 主屏幕`）—— 之前只在**重试**
    路径里检查前台，成功路径不检查，于是整段读数在抽屉上"成功"地跑完（group 相位
    `group-conv-row-visible` 就是这么红的，而同一份夹具单跑是 PASS 106）。
    判据用 dump 自己的 `package` 属性，比 `dumpsys` 的前台更贴近"这份读数是谁的"：
    投屏的系统授权对话框属于 `com.android.systemui`，也放行。
    """
    for n in nodes:
        pkg = n.pkg or ""
        if pkg == PKG or pkg.startswith("com.android.systemui"):
            return True
    return False


def uiautomator_dump(retries=2):
    """`uiautomator dump` 到设备文件再 cat 回来，解析成 UiNode 列表。

    **必须走 dump，不许目测坐标**：投屏批次就是因为估坐标误触了返回键，
    把"没反应"记成了"功能坏了"。dump 出来的 bounds 是唯一可信的坐标来源。
    """
    last = ""
    for attempt in range(retries + 1):
        adb("shell", "uiautomator", "dump", REMOTE_UI_DUMP)
        xml = adb("shell", "cat", REMOTE_UI_DUMP)
        if "<hierarchy" in xml:
            nodes = parse_ui_xml(xml)
            if nodes and _dump_is_ours(nodes):
                return nodes
            if nodes:
                last = "dump 内容不属于本 app（前台=%s，首个文本=%r）" % (
                    foreground_activity(), (nodes[0].text or "")[:40])
        if not last:
            last = xml.strip().replace("\n", " ")[:160]
        # 2026-10-10 全量套件 run4 的现场：`cat: /sdcard/relay-e2e-ui.xml: Transport
        # endpoint is not connected` 这种存储/`adbd` 打嗝之后，dump 会连着失败；此时
        # app 往往也已经不在前台（读数会变成启动器）。重试前先把它拉回前台 ——
        # 否则整段读数会在启动器上"成功"地继续跑（12 条红全是这么来的）。
        if attempt < retries:
            log("uiautomator_dump 这一份不能用（%s），先拉回前台再重试" % last[:120])
            ensure_app_foreground(timeout=6.0)
        time.sleep(0.6)
    log("uiautomator_dump 失败：%s" % last)
    return []


NODE_RE = re.compile(r"<node\b([^>]*?)/?>", re.S)
ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')
BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


class UiNode(object):
    """uiautomator 里一个可见节点。center 由 bounds 算出来 —— 不要猜。"""

    __slots__ = ("text", "desc", "cls", "bounds", "center", "clickable", "enabled", "pkg")

    def __init__(self, attrs):
        self.text = html.unescape(attrs.get("text", ""))
        self.desc = html.unescape(attrs.get("content-desc", ""))
        self.cls = attrs.get("class", "")
        self.pkg = attrs.get("package", "")
        self.clickable = attrs.get("clickable") == "true"
        self.enabled = attrs.get("enabled") == "true"
        m = BOUNDS_RE.search(attrs.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = (int(g) for g in m.groups())
            self.bounds = (x1, y1, x2, y2)
            self.center = ((x1 + x2) // 2, (y1 + y2) // 2)
        else:
            self.bounds = None
            self.center = None

    def __repr__(self):
        return "UiNode(%r, desc=%r, cls=%s, center=%s)" % (
            self.text, self.desc, self.cls.split(".")[-1], self.center)


def parse_ui_xml(xml):
    nodes = []
    for m in NODE_RE.finditer(xml):
        attrs = dict(ATTR_RE.findall(m.group(1)))
        n = UiNode(attrs)
        if n.center:
            nodes.append(n)
    return nodes


def ui_all():
    return uiautomator_dump()


def find_node(nodes, text=None, desc=None, cls=None, exact=True, bottom=False):
    """在 dump 结果里找节点。text/desc 任一命中即可（'发送' 是 content-desc，不是 text）。

    bottom=True 取最靠下的那个：底栏的「消息」和顶栏标题是同一段文字，
    不区分就会点到标题上，然后你会以为是"导航没生效"。
    """
    hits = []
    for n in nodes:
        if text is not None:
            if (n.text == text) if exact else (text in n.text):
                hits.append(n)
                continue
        if desc is not None:
            if (n.desc == desc) if exact else (desc in n.desc):
                hits.append(n)
                continue
        if cls is not None:
            if cls.lower() in n.cls.lower():
                hits.append(n)
    if not hits:
        return None
    if bottom:
        return max(hits, key=lambda n: n.center[1])
    return hits[0]


def find_ui(text=None, desc=None, cls=None, exact=True, bottom=False, timeout=0.0, interval=0.6):
    """按时间轮询 dump 直到出现（timeout=0 表示只查一次）。"""
    deadline = time.monotonic() + max(0.0, timeout)
    while True:
        node = find_node(ui_all(), text=text, desc=desc, cls=cls, exact=exact, bottom=bottom)
        if node or time.monotonic() >= deadline:
            return node
        time.sleep(interval)


def tap_xy(x, y):
    adb("shell", "input", "tap", str(int(x)), str(int(y)))


def tap_node(node):
    if not node or not node.center:
        return False
    tap_xy(*node.center)
    return True


def tap_text(label, exact=True, desc=False, bottom=False, timeout=4.0):
    """按文字（或 content-desc）找到节点、点它的 bounds 中心。找不到返回 False。"""
    node = find_ui(desc=label if desc else None, text=None if desc else label,
                   exact=exact, bottom=bottom, timeout=timeout)
    if not node:
        return False
    log("tap %r at %s (cls=%s)" % (label, node.center, node.cls.split(".")[-1]))
    return tap_node(node)


_ADB_SAFE_RE = re.compile(r"^[A-Za-z0-9._@%+=:/-]+$")

# 逐键注入的键码表（Pixel 6 / Android 16，2026-10-10 实测）。
#
# 为什么放弃 `input text`：run9 的 group 相位同一个 9 字符标记连打 8 次、**8 次全被吞字**
# （`group-send-landed` 就是这么红的：`框里=None 期望='out8bd135'`，日志里 8 行
# `input text 吞字：想打 'out8bd135'，框里是 'out8b135'/'out8d15'/…`），
# 而同一批字符串在 messaging 相位只吞 2 次就进去了 —— 吞字是**概率性**的，
# 夹具不能把「打了就当它进去了」当契约。
# 保真度实测（同一 9 字符标记各 10 次）：`input text` 整串 **5/10**；逐键 keyevent **10/10**。
#
# 分块大小也有讲究（短标记各 6 次 + 300 字符长文，一条命令塞多个 keycode）：
#   chunk=1  6/6 可靠、300 字符落地 300 一致
#   chunk=2  6/6 可靠、300 字符落地 300 一致   ← 取它（≈12 字符/秒）
#   chunk=3  2/6、落地 290 不一致
#   chunk=5  4/6、落地 271 不一致
#   chunk=8  1/6、落地 261 不一致
#   chunk=12 2/6、落地 265 不一致
# 即**只有 1 与 2 可靠**，chunk≥3 立刻开始丢 —— 不能靠"一条命令塞很多"提速。
_ADB_KEYEVENT_CHUNK = 2
_ADB_SHIFT = 59  # KEYCODE_SHIFT_LEFT

# 单键字符 -> keycode
_ADB_KEYCODES = {}
for _i, _c in enumerate("abcdefghijklmnopqrstuvwxyz"):
    _ADB_KEYCODES[_c] = 29 + _i          # KEYCODE_A = 29
for _i, _c in enumerate("0123456789"):
    _ADB_KEYCODES[_c] = 7 + _i           # KEYCODE_0 = 7
_ADB_KEYCODES.update({
    ".": 56,   # KEYCODE_PERIOD
    "@": 77,   # KEYCODE_AT
    "+": 81,   # KEYCODE_PLUS
    "=": 70,   # KEYCODE_EQUALS
    "-": 69,   # KEYCODE_MINUS
    "/": 76,   # KEYCODE_SLASH
})

# 需要 shift 的字符 -> 被 shift 的那个键，走 `input keycombination 59 <key>`。
# **不能用"符号键"代替**：实测 `input keyevent 227`（KEYCODE_PERCENT）一个字符都进不去
# （4/4 空），而 `input keycombination 59 12`（shift+5）4/4 正确。
_ADB_KEYCHORDS = {"%": "12", ":": "74", "_": "69"}
for _c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
    _ADB_KEYCHORDS[_c] = str(_ADB_KEYCODES[_c.lower()])

# 全字符集逐字符实测（keyevent chunk=2，各 1 次）：lower 26/26、UPPER 26/26、
# digits 10/10、punct 6/7（只有 `%` 挂）、extra(`-` `/`) 2/2 ⇒ 补齐 `%` 后 **全绿**。
# 大写走 shift+字母、`_` 走 shift+minus、`:` 走 shift+semicolon、`%` 走 shift+5。


def adb_input_text(s):
    """往当前焦点**逐键**注入文本（keyevent，保真度实测 100%）。

    取舍与实测数据记在上面 [_ADB_KEYCODES] / [_ADB_KEYEVENT_CHUNK] 的注释里。要点：
      * 只覆盖 `[A-Za-z0-9._@%+=:-/]` —— 中文会 NullPointerException（记在
        design/runtime-evidence/group-chat-acceptance.txt），其余 ASCII 没实测过，
        一律抛 [FixtureError]，把夹具钉在可靠范围内而不是现场碰运气；
      * **长文本不走这里**（12 字符/秒太慢），走 [_adb_input_text_fast]。
    """
    s = s or ""
    if not s:
        return True
    buf = []

    def flush():
        while buf:
            batch = buf[:_ADB_KEYEVENT_CHUNK]
            del buf[:len(batch)]
            adb("shell", "input", "keyevent", *batch)

    for ch in s:
        chord = _ADB_KEYCHORDS.get(ch)
        if chord is not None:
            flush()
            adb("shell", "input", "keycombination", str(_ADB_SHIFT), chord)
            continue
        code = _ADB_KEYCODES.get(ch)
        if code is None:
            raise FixtureError(
                "adb 逐键注入只覆盖 [A-Za-z0-9._@%%+=:-/]（全字符集实测 100% 保真），"
                "收到 %r；中文会 NullPointerException，其余 ASCII 未实测" % s)
        buf.append(str(code))
        if len(buf) >= _ADB_KEYEVENT_CHUNK:
            flush()
    flush()
    return True


def _adb_input_text_fast(s):
    """长文本专用的**快路**：安全段整段走 `input text`（≈31 字符/秒），**不保证保真**。

    只给 `_chunk_blob` 造的长文用：`chat_type_long` 的契约是"框里 ≥ N 字节"，
    而真正被断言的是"手机发出去的那份 == 框里当时那份"，吞字由它回读兜底
    （见 `chat_type_long` 的 KDoc）。
    短标记/群名/CIDR/查询串**必须**走 [adb_input_text]。
    """
    s = s or ""
    if not s:
        return True
    buf = []
    for ch in s:
        if ch in _ADB_KEYCHORDS or ch not in _ADB_KEYCODES:
            if _ADB_SAFE_RE.match(ch):
                buf.append(ch)
                continue
            raise FixtureError(
                "adb input text 只可靠注入 [A-Za-z0-9._@%%+=:-/]，收到 %r" % s)
        if ch in ("-", "/"):
            if buf:
                adb("shell", "input", "text", "".join(buf))
                buf = []
            input_keyevent(_ADB_KEYCODES[ch])
        else:
            buf.append(ch)
    if buf:
        adb("shell", "input", "text", "".join(buf))
    return True


def input_keyevent(code):
    adb("shell", "input", "keyevent", str(code))


def press_back():
    input_keyevent(4)


def ime_shown(timeout=15):
    """输入法是否正在显示（`dumpsys input_method` 的 mInputShown）。

    用处：**键盘会盖住屏幕下半部分的按钮** —— 点得到、但不生效，夹具就会把
    「被键盘压住」误判成「这个按钮坏了」。要按下面的按钮时先问一句，必要时
    `press_back()` 收键盘（列表/浮层上的检索框不是全屏页，返回键只收键盘）。
    读不到就返回 False（不把环境读数的失败当成产品行为）。
    """
    try:
        out = adb("shell", "dumpsys", "input_method", timeout=timeout) or ""
    except (EnvError, OSError):
        return False
    for line in out.splitlines():
        if "mInputShown" in line:
            return "mInputShown=true" in line.replace(" ", "")
    return False


def clear_focused_text(n=48):
    """清空输入框：MOVE_END + 若干次 DEL。不要把退格和返回搞混。

    与 `chat_clear` 同一套分块理由（见那里）：逐次调用慢 20 倍，一次塞太多又会跑过
    `adb()` 的 30s 超时 ⇒ EnvError。小字段（R19 的地址框是 64）现在分块后最多两次
    shell 调用，比逐次快一个数量级。
    """
    n = int(n)
    if n <= 0:
        return
    input_keyevent(123)  # KEYCODE_MOVE_END
    while n > 0:
        batch = min(_CLEAR_CHUNK, n)
        adb("shell", "input", "keyevent", *([str(67)] * batch))  # KEYCODE_DEL × batch
        n -= batch


def screencap(path):
    """真机截图（png）。用 exec-out 拿二进制，不要走 pull —— 少一次落盘。"""
    d = os.path.dirname(os.path.abspath(path))
    if d and not os.path.isdir(d):
        os.makedirs(d)
    data = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
    if not data or len(data) < 100:
        raise EnvError("screencap 没拿到图像数据（%d 字节）" % len(data or b""))
    with open(path, "wb") as f:
        f.write(data)
    log("screencap -> %s (%d bytes)" % (path, len(data)))
    return path


def ui_texts(nodes=None):
    """当前屏上所有 text + content-desc，供"这行到底显示了什么"的读数。"""
    nodes = nodes if nodes is not None else ui_all()
    out = []
    for n in nodes:
        if n.text:
            out.append(n.text)
        if n.desc:
            out.append(n.desc)
    return out


def ui_has(substr, nodes=None):
    return any(substr in t for t in ui_texts(nodes))


# --- 开关状态：只能从原始 XML 读 ------------------------------------------
# `UiNode.__slots__`(:461) 没有 checked/checkable，Compose 的 Switch 又不带
# content-desc ⇒ 想知道一个开关是开是关，必须自己再解析一次原始 dump。
CHECKABLE_ROW_TOLERANCE_PX = 160


def ui_xml_raw(retries=2):
    """`uiautomator dump` 后把原始 XML 拿回来（不是 UiNode 列表）。"""
    last = ""
    for _ in range(retries + 1):
        adb("shell", "uiautomator", "dump", REMOTE_UI_DUMP)
        xml = adb("shell", "cat", REMOTE_UI_DUMP)
        if "<hierarchy" in xml:
            return xml
        last = xml.strip().replace("\n", " ")[:160]
        time.sleep(0.6)
    log("ui_xml_raw 失败：%s" % last)
    return ""


def _raw_nodes(xml):
    """原始 XML → 带 checkable/checked/clickable/enabled 的字典列表。"""
    out = []
    for m in NODE_RE.finditer(xml):
        a = dict(ATTR_RE.findall(m.group(1)))
        b = BOUNDS_RE.search(a.get("bounds", ""))
        if not b:
            continue
        x1, y1, x2, y2 = (int(g) for g in b.groups())
        out.append({
            "text": html.unescape(a.get("text", "")),
            "desc": html.unescape(a.get("content-desc", "")),
            "checkable": a.get("checkable") == "true",
            "checked": a.get("checked") == "true",
            "clickable": a.get("clickable") == "true",
            "enabled": a.get("enabled") == "true",
            "bounds": (x1, y1, x2, y2),
            "center": ((x1 + x2) // 2, (y1 + y2) // 2),
        })
    return out


def switch_in_row(label, xml=None, tolerance=CHECKABLE_ROW_TOLERANCE_PX):
    """读「`label` 那一行、标签右侧」的开关。返回 dict（含 checked/center）或 None。

    Compose 的 `SettingSwitchRow` 把开关画成 `android.view.View`，只有
    `checkable="true"` 与 `checked` 两个属性可认；没有 content-desc，所以只能按
    「与标签同一水平行」这个几何关系找。
    """
    xml = ui_xml_raw() if xml is None else xml
    nodes = _raw_nodes(xml)
    labels = [n for n in nodes if n["text"] == label or n["desc"] == label]
    if not labels:
        return None
    lab = labels[0]
    ly = (lab["bounds"][1] + lab["bounds"][3]) // 2
    best = None
    for n in nodes:
        if not n["checkable"]:
            continue
        cy = (n["bounds"][1] + n["bounds"][3]) // 2
        if abs(cy - ly) > tolerance:
            continue
        if n["bounds"][0] < lab["bounds"][2]:      # 必须在标签右边
            continue
        if best is None or abs(cy - ly) < abs((best["bounds"][1] + best["bounds"][3]) // 2 - ly):
            best = n
    return best


def scroll_to_text(label, max_swipes=6, timeout=3.0):
    """内容上滑直到 `label` 可见（从页面顶部往下找）。返回节点或 None。"""
    node = find_ui(text=label, timeout=0.5)
    if node:
        return node
    for _ in range(max_swipes):
        adb("shell", "input", "swipe", "540", "1500", "540", "900", "250")
        time.sleep(0.5)
        node = find_ui(text=label, timeout=0.5)
        if node:
            return node
    return None


def goto_settings_more(timeout=8.0):
    """进「设置 → 数据与存储」子页，并把「消息落盘」滚进视口。返回是否到位。

    底栏 tab 与顶栏标题同名，`bottom=True` 才分得开（与 `goto_messages_list` 同一招）。
    设置 tab 会停在上次进过的子页，所以先按返回找「数据与存储」那一行。
    """
    if find_ui(text="消息落盘", timeout=0.4) or find_ui(text="清空聊天记录", timeout=0.4):
        return True                      # 已经在 SettingsMoreScreen
    tab = find_ui(text="设置", bottom=True, timeout=timeout)
    if not tab:
        # 可能还停在某个没有底栏的页（聊天页）：按返回再找一次
        for _ in range(2):
            press_back()
            time.sleep(0.8)
            tab = find_ui(text="设置", bottom=True, timeout=1.5)
            if tab:
                break
    if not tab:
        log("底栏找不到「设置」；当前可见：%s" % " | ".join(ui_texts()[:20]))
        return False
    tap_node(tab)
    time.sleep(1.0)
    for _ in range(3):
        if find_ui(text="数据与存储", timeout=1.0):
            break
        press_back()
        time.sleep(0.8)
    row = find_ui(text="数据与存储", timeout=timeout)
    if not row:
        log("设置首页找不到「数据与存储」；当前可见：%s" % " | ".join(ui_texts()[:20]))
        return False
    tap_node(row)
    time.sleep(1.2)
    return scroll_to_text("消息落盘") is not None


def ensure_messages_persisted(timeout=12.0):
    """确保设置里的「消息落盘」是**开**的（幂等）。返回 (ok, detail)。

    为什么夹具必须先做这一步：`persistMessages` 默认 `false`
    （`RelaySettings.kt:95`，注释写明「默认 false 保证老用户升级后行为不变」）；
    关着的时候 `persistThreads()` 第一行就 `if (!persistMessages) return`
    （`RelayControllerMessages.kt:364-365`），消息只活在内存里 —— 而检索
    （会话内 / 全局 / 会话列表）查的全是 DB（`RelayController.kt:157`、
    `RelayControllerMessages.kt:499/512/541`），于是恒 0 条。
    这不是缺陷，是隐私优先的默认值；夹具要做的是把前置摆好并**断言**它。
    """
    if not ensure_app_foreground(timeout=timeout):
        return False, "app.relay 没到前台"
    # 先把界面退回会话列表：聊天页里没有底栏，「设置」tab 根本不在 dump 里
    # （2026-10-09 run4 的前置就是这么红的）。
    if not goto_messages_list():
        return False, "退不回会话列表，找不到底栏"
    if not goto_settings_more(timeout=timeout):
        return False, "走不到「数据与存储」页"
    label = scroll_to_text("消息落盘")
    if not label:
        return False, "滚不到「消息落盘」行；当前可见：%s" % " | ".join(ui_texts()[:20])
    sw = switch_in_row("消息落盘")
    if sw is None:
        return False, "「消息落盘」那一行找不到 checkable 开关节点"
    if sw["checked"]:
        return True, "读回 checked=true（本来就开着）"
    tap_xy(*sw["center"])
    time.sleep(1.0)
    sw2 = switch_in_row("消息落盘")
    if sw2 is not None and sw2["checked"]:
        return True, "本次点开（checked=false → true）"
    log("点了「消息落盘」但读回仍是 %s" % (None if sw2 is None else sw2["checked"]))
    return False, "点了开关但读回没变成 true"


# ---------------------------------------------------------------------------
# 4. UDP 对端（夹具自己当"那台电脑"）
# ---------------------------------------------------------------------------

class UdpPeer(object):
    """夹具的 UDP 对端：绑一个临时端口、后台收包、对可靠帧自动回 ACK。

    为什么要一个类：后续每个功能夹具都要"装作一台电脑" —— 握手、收消息、回 ACK、
    必要时装死（模拟离线）。这里一次做对，别的夹具只写业务断言。

    三条不变量：
      * ACK 要**发回数据报的源地址**，不是发给 17878。手机侧等的是对自己发送端口的确认。
      * `quiet=True` 时不回 ACK 也不处理 —— 这是"对端离线/网络不可达"的唯一正确模拟方式
        （手机上并没有"飞行模式"开关可用，quiesce 自己的回包等价于对端消失）。
      * 握手用带 `probeId` 的定向 entry：固定 1s 回 alive，绕过 10s 去重窗口。
    """

    def __init__(self, phone_ip, phone_udp=UDP_PORT, nick=DEFAULT_NICK, caps=None,
                 tcp_port=TCP_PORT_DEFAULT, host=None, node_id=None, auto_ack=True):
        if not phone_ip:
            raise FixtureError("phone_ip 不能为空")
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", 0))
        sock.settimeout(0.5)
        self.sock = sock
        self.port = sock.getsockname()[1]
        self.phone = (phone_ip, int(phone_udp))
        self.node_id = node_id or FIXTURE_NODE
        self.nick = nick
        self.tcp_port = int(tcp_port)
        self.profile = profile_caps(nick=nick, caps=caps, tcp_port=tcp_port,
                                    host=host, node_id=self.node_id)
        self.auto_ack = bool(auto_ack)
        self.quiet = False                 # True ⇒ 装死（不回 ACK、不处理）
        self.inbox = []                    # [(recv_ts, addr, env)]
        self.phone_profiles = []           # [(recv_ts, profile)]
        self.acked = {}                    # env_id -> recv_ts
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = None

    # -- 收发 -------------------------------------------------------------
    def env(self, mtype, payload, mid=None):
        return envelope(mtype, payload, mid=mid, node_id=self.node_id)

    def send(self, env, addr=None):
        host, port = addr if addr else self.phone
        send_env(self.sock, env, host, port)
        return env

    def send_entry(self, alive=False, probe=True):
        pid = str(uuid.uuid4()) if probe else None
        mtype = T_ALIVE if alive else T_ENTRY
        return self.send(self.env(mtype, entry_payload(self.profile, pid)))

    def send_exit(self):
        """`exit` 的线上形状：payload 就是 `{}`（Discovery 只认类型，不看内容）。

        手机收到立刻 `registry.markOffline(from)` —— 这是让对端"变离线"最快、
        最确定的一条路（不用等 90 秒的存在性超时）。
        """
        return self.send(self.env(T_EXIT, {}))

    def send_text(self, text, resend=None):
        payload = {"kind": K_TEXT, "text": text}
        if resend is not None:
            payload["resend"] = bool(resend)
        return self.send(self.env(T_MSG, payload))

    # -- 后台收包 ---------------------------------------------------------
    def start(self):
        if self._thread:
            return self
        self._thread = threading.Thread(target=self._loop, name="udp-peer", daemon=True)
        self._thread.start()
        return self

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2.0)
            self._thread = None
        try:
            self.sock.close()
        except OSError:
            pass

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()
        return False

    def _loop(self):
        while not self._stop.is_set():
            try:
                data, addr = self.sock.recvfrom(UDP_MAX_INBOUND)
            except socket.timeout:
                continue
            except OSError as exc:
                # 静默 return 是个坏失败模式：2026-10-10 sharer run1 里收包线程就是**一声不响**
                # 停掉的 —— 之后 140s 一条帧都没进来（连手机每 30s 的 presence 广播都没有），
                # 而夹具只是"等不到 screen.request"，看起来像产品没发。至少留一行现场。
                if not self._stop.is_set():
                    log("udp 收包线程退出：%r（socket 被关？）" % (exc,))
                return
            try:
                env = parse_json(data)
                if not isinstance(env, dict) or "type" not in env:
                    log("udp<- 非信封 %d 字节 from=%s" % (len(data), addr))
                    continue
                ts = time.time()
                with self._lock:
                    if not self.quiet:
                        self.inbox.append((ts, addr, env))
                    if not self.quiet and env.get("type") in (T_ALIVE, T_PROFILE, T_ENTRY):
                        # `entry` 也带全量 profile（`Discovery.handleEntry` 就是靠它学资料的：
                        # `profileOf(envelope) ?: return`，Discovery.kt:212）。少了这一档，
                        # 「手机改了档案之后有没有重新广播」的判据永远看不到变化 —— 手机侧
                        # 主动广播走的正是 `entry`（`Discovery.announce()`，Discovery.kt:114-124），
                        # `alive` 只在回应我们的定向 entry 时出现。2026-10-10 全量套件 run3 的
                        # `r88-shr1-appears-after-share-root-set` 就是这么红的：手机确实广播了
                        # 一条带 shr1 的 entry（日志里有 `udp<- type=entry from=f6a7b8c9`），
                        # 而夹具没把它当 profile 收下。
                        prof = (env.get("payload") or {}).get("profile")
                        if isinstance(prof, dict):
                            self.phone_profiles.append((ts, prof))
                    if self.quiet:
                        continue
                if env.get("type") in RELIABLE_TYPES and self.auto_ack:
                    # 可靠帧 → 先回 ACK。手机侧等不到 ACK 就会按 ackRetrySchedule 重传，
                    # 然后落到"已排队"。漏回一条 ACK 的现象是"偶尔丢一条"，最难查。
                    ack_env(env, node_id=self.node_id, addr=addr, sock=self.sock)
                    with self._lock:
                        self.acked[env.get("id")] = ts
                    log("udp<- type=%s id=%s → ACK" % (env.get("type"), (env.get("id") or "")[:8]))
                else:
                    log("udp<- type=%s from=%s id=%s" % (
                        env.get("type"), (env.get("from") or "")[:8], (env.get("id") or "")[:8]))
            except Exception as exc:  # noqa: BLE001 —— 收包线程不许因为一帧畸形就死掉
                log("udp 收包线程处理这一帧时出错：%r（继续收）" % (exc,))

    # -- 读 ---------------------------------------------------------------
    def frames(self, mtype=None, text=None, resend=None, since=0.0):
        """已收到的信封（可按类型/正文/重传标记过滤）。since 是 time.time() 下界，**闭区间**。

        注意这个「闭」：自己维护游标时要把游标推进到 `ts` **之后**（`since = ts + 1e-6`），
        写成 `since = ts` 会让同一条帧每轮都被再取出来一次（discovery 的 AliveResponder
        就是这么把一条 entry 变成 391 条 alive 的，见那里的注释）。需要「只看新的」
        就用 `wait()`（它按索引推进）。
        """
        with self._lock:
            got = list(self.inbox)
        out = []
        for ts, addr, env in got:
            if ts < since:
                continue
            if mtype is not None and env.get("type") != mtype:
                continue
            if text is not None or resend is not None:
                p = env.get("payload") or {}
                if text is not None and p.get("text") != text:
                    continue
                if resend is not None and bool(p.get("resend")) != bool(resend):
                    continue
            out.append((ts, addr, env))
        return out

    def wait(self, pred, timeout, interval=0.05):
        """等一个满足 pred(env) 的信封（pred 里不关心的字段别写）。返回 env 或 None。"""
        deadline = time.monotonic() + max(0.0, timeout)
        seen = 0
        while True:
            with self._lock:
                got = list(self.inbox)
            for ts, addr, env in got[seen:]:
                if pred(env):
                    return env
            seen = len(got)
            if time.monotonic() >= deadline:
                return None
            time.sleep(interval)

    def wait_ack(self, env_id, timeout):
        """等某条信封的 ACK（UDP 侧）。ACK 是被 ackFor 认出来的，不是靠 type 猜的。"""
        env = self.wait(lambda e: e.get("type") == T_ACK
                        and (e.get("payload") or {}).get("ackFor") == env_id, timeout)
        return env

    def send_until_ack(self, env, tries=3, each=3.0):
        """发一条**可靠帧**并等 ACK，收不到就**原样重发**（同一个 id），返回 ACK 或 None。

        为什么必须有它：UDP 丢一条是常事（同一个夹具在 R90 里还收得到单播 presence、
        下一秒这一帧就没了），而对端是按 id 去重的 —— 重发不会造成重复处理。

        **只对 `RELIABLE_TYPES` 里的类型用**（`relay_e2e.py:78-82`，
        `MessageTypes.kt:58-63`）：`peers` / `scan-ranges` / `presence` 这些 gossip 帧
        对端**有意不回 ACK**，拿它去等只会每次都等满 `tries * each` 秒然后误报失败
        —— 2026-10-09 run2 的 `r91-inbound-scan-ranges-acked` 就是这么红的
        （R91 已改成「发一次 + 断言吸收/回发」）。
        """
        last = None
        for n in range(1, max(1, int(tries)) + 1):
            self.last_ack_attempts = n
            self.send(env)
            last = self.wait_ack(env["id"], each)
            if last is not None:
                return last
        return last

    @property
    def phone_profile(self):
        with self._lock:
            return self.phone_profiles[-1][1] if self.phone_profiles else None

    def handshake(self, secs=15, probe=True, interval=1.5):
        """握手：发 entry，等手机的 alive（带全量 profile）。返回 profile 或 None。

        默认 probe=True ⇒ 手机端固定 1s 回 alive（directed 分支，且绕过 10s 去重）；
        probe=False 走裸 entry，回包是 0–8s 随机抖动且 10s 内只回一次。
        """
        self.start()
        with self._lock:
            self.phone_profiles = []
        deadline = time.monotonic() + max(1.0, secs)
        while True:
            self.send_entry(probe=probe)
            prof = self.wait_phone_profile(timeout=min(interval, max(0.2, deadline - time.monotonic())))
            if prof:
                return prof
            if time.monotonic() >= deadline:
                return None

    def wait_phone_profile(self, timeout, since=None):
        deadline = time.monotonic() + max(0.0, timeout)
        while True:
            with self._lock:
                hist = list(self.phone_profiles)
            for ts, prof in hist:
                if since is None or ts > since:
                    return prof
            if time.monotonic() >= deadline:
                return None
            time.sleep(0.05)


# ---------------------------------------------------------------------------
# 5. TCP 帧服务端（手机的长文本 / 大帧会连到 profile.tcpPort）
# ---------------------------------------------------------------------------

class TcpFrameServer(object):
    """监听 `profile.tcpPort`，收 `{"type":"msg","envelope":…}` 并回 `msg-ack`。

    手机侧 `TcpFramedChannel` 连的是 **record.ip + record.profile.tcpPort**（不是 UDP 端口），
    发的帧是 `{"type":"msg","envelope":…}`（不是裸信封），只接受
    `{"type":"msg-ack","ackFor":<本信封 id>}`，等 ACK 的精确超时是 7000ms。
    这四条任何一条搞错，现象都是"已排队"—— 也就是没有任何现象。
    """

    def __init__(self, port=TCP_PORT_DEFAULT, bind="0.0.0.0", auto_ack=True, name="tcp"):
        self.port = int(port)
        self.bind = bind
        self.auto_ack = bool(auto_ack)
        self.quiet = False           # True ⇒ 收下帧但**不回 msg-ack**（对端离线的完整模拟）
        self.name = name
        self.frames_in = []          # [(ts, addr, obj)]
        self.acked = []
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = None
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind((bind, self.port))
        except OSError as e:
            sock.close()
            raise EnvError(
                "TCP %s:%d 绑不上（%s）。外部进程可能占用 TCP 17879 ——"
                " 先退出它，或用 --tcp-port 换一个（同时它会写进夹具 profile.tcpPort）"
                % (bind, self.port, e))
        sock.listen(8)
        sock.settimeout(0.5)
        self.sock = sock

    def start(self):
        if self._thread:
            return self
        self._thread = threading.Thread(target=self._loop, name=self.name, daemon=True)
        self._thread.start()
        return self

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2.0)
            self._thread = None
        try:
            self.sock.close()
        except OSError:
            pass

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()
        return False

    def _loop(self):
        while not self._stop.is_set():
            try:
                conn, addr = self.sock.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            threading.Thread(target=self._serve, args=(conn, addr), daemon=True).start()

    def _serve(self, conn, addr):
        log("%s<- 连接 from=%s:%s" % (self.name, addr[0], addr[1]))
        try:
            conn.settimeout(1.0)
            deadline = time.monotonic() + (TCP_ACK_TIMEOUT_MS / 1000.0) + 3.0
            while time.monotonic() < deadline and not self._stop.is_set():
                obj = read_tcp_frame(conn, deadline)
                if obj is None:
                    break
                with self._lock:
                    self.frames_in.append((time.time(), addr, obj))
                log("%s<- frame type=%s len=%d" % (
                    self.name, obj.get("type"),
                    len(json.dumps(obj, ensure_ascii=False))))
                if self.quiet:
                    # 装死：帧照收、照记账（这是"对端离线时到底有没有漏发"的证据），
                    # 但不回 msg-ack —— 发送方只能等到 7000ms 超时再入队。
                    log("%s<- quiet：收下帧但不回 msg-ack（模拟对端离线）" % self.name)
                    continue
                if self.auto_ack and obj.get("type") == T_MSG:
                    env = obj.get("envelope") or {}
                    eid = env.get("id")
                    if eid:
                        conn.sendall(tcp_msg_ack(eid))
                        with self._lock:
                            self.acked.append((time.time(), eid))
                        log("%s-> msg-ack ackFor=%s" % (self.name, eid[:8]))
        except OSError as e:
            log("%s<- OSError %s" % (self.name, e))
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def frames(self, mtype=None, text=None, since=0.0):
        with self._lock:
            got = list(self.frames_in)
        out = []
        for ts, addr, obj in got:
            if ts < since:
                continue
            if mtype is not None and obj.get("type") != mtype:
                continue
            if text is not None:
                env = obj.get("envelope") or {}
                if (env.get("payload") or {}).get("text") != text:
                    continue
            out.append((ts, addr, obj))
        return out

    def wait_msg(self, text=None, timeout=10.0):
        """等一帧 type=msg（可选正文匹配），返回 (envelope, frame) 或 (None, None)。"""
        deadline = time.monotonic() + max(0.0, timeout)
        seen = 0
        while True:
            with self._lock:
                got = list(self.frames_in)
            for ts, addr, obj in got[seen:]:
                if obj.get("type") != T_MSG:
                    continue
                env = obj.get("envelope") or {}
                if text is not None and (env.get("payload") or {}).get("text") != text:
                    continue
                return env, obj
            seen = len(got)
            if time.monotonic() >= deadline:
                return None, None
            time.sleep(0.05)

    def wait_ack_of(self, env_id, timeout=3.0):
        deadline = time.monotonic() + max(0.0, timeout)
        while True:
            with self._lock:
                if any(eid == env_id for _, eid in self.acked):
                    return True
            if time.monotonic() >= deadline:
                return False
            time.sleep(0.05)


# ---------------------------------------------------------------------------
# 6. app shell 导航（真机 UI 驱动）
#
# 手机端 AndroidManifest 只有 MAIN/LAUNCHER，**没有 deep link / share intent**，
# 所以"让手机自己发一条消息"只能走 UI 自动化。三条纪律：
#   * 坐标一律取自 uiautomator dump 的 bounds，绝不目测 —— 投屏批次就是因为目测坐标
#     误触了返回键，把"没反应"记成了"功能坏了"；
#   * 每次点击前重新 dump —— 键盘弹起会把输入框和发送键整体上移（y 从 2190 掉到 1361）；
#   * 输入文本只用 ASCII 字母数字（`input text` 注入不了中文，还会吃掉连字符）。
# 这几个函数与具体功能无关，后面的文件柜/撤回/戳一碰夹具都直接用。
# ---------------------------------------------------------------------------

_ACT_RE = re.compile(r"(?:topResumedActivity=|ResumedActivity: )ActivityRecord\{[^}]*?\s([\w.]+)/([\w.$]+)")


def foreground_activity():
    """当前前台 Activity，形如 `app.relay/.app.MainActivity`；拿不到返回 None。"""
    m = _ACT_RE.search(adb("shell", "dumpsys", "activity", "activities"))
    return "%s/%s" % (m.group(1), m.group(2)) if m else None


def ensure_app_foreground(timeout=10.0):
    """确保 app.relay 在前台（可能刚被 force-stop 过）。返回前台 Activity 或 None。"""
    deadline = time.monotonic() + timeout
    while True:
        act = foreground_activity()
        if act and act.startswith(PKG):
            return act
        adb("shell", "am", "start", "-n", LAUNCH_ACTIVITY)
        time.sleep(1.5)
        if time.monotonic() >= deadline:
            act = foreground_activity()
            return act if act and act.startswith(PKG) else None


def _app_in_foreground():
    """当前前台 Activity 是不是本 app（读 `dumpsys window`，不猜）。"""
    act = foreground_activity()
    return bool(act and act.startswith(PKG))


def goto_messages_list(max_back=5):
    """回到「消息」会话列表。底栏 tab 与顶栏标题同名，`bottom=True` 才分得开。

    2026-10-10 全量套件 run4 的教训：这个函数原来**无条件** `press_back()`，
    而它一旦在「会话列表根页」上被调用（dump 打嗝 ⇒ 看不到 `搜索会话`/底栏 tab），
    返回键就把 app 退出到启动器；紧接着夹具的滑动落在启动器上会**拉开应用抽屉**，
    之后所有读数都在抽屉上"成功"地跑（group 相位 12 条红全是这么来的）。
    现在：不在前台就先拉回前台；只有在**确实停在 app 内的子页**（前台是本 app
    但底栏不可见）时才按一次返回键。
    """
    for _ in range(max_back + 1):
        if not _app_in_foreground():
            log("goto_messages_list: app 不在前台，先拉回前台")
            ensure_app_foreground(timeout=8.0)
            time.sleep(1.0)
            continue
        nodes = ui_all()
        # 搜索浮层会**保留**顶栏的「N 个会话」标题，所以下面那两条"到列表了"的判据
        # 在浮层上同样成立（假绿）—— 而浮层里的行是搜索结果，不是会话列表。
        # 有「清除搜索」/「没有匹配…」就先清掉它（点清除 + 返回键）。
        # 搜索浮层：正常列表里"搜索会话"只是一个写着字的假框；浮层里同一个位置是
        # 一个**真正的 EditText**。查询串为空时浮层看起来和正常列表一模一样
        # （`N 个会话` 也还在 ⇒ 下面两条判据假绿），查询串非空时列表被过滤 ⇒
        # 要打开的会话行根本不在 dump 里。2026-10-10 全量套件 run6 的 group 相位
        # 8 条红就是这么来的（真机探针：清掉查询串后，同一个群行立刻出现在第一屏）。
        field = find_node(nodes, cls="EditText")
        # 只认**真的把列表过滤了**的两种情形：查询串非空、或屏上写着「没有匹配」。
        # 空查询的浮层（列表是完整的）不要动它 —— 否则每轮都去"清"一次，白跑。
        query = (field.text or "").strip() if (field is not None and field.center[1] < 900) else ""
        if query or find_node(nodes, text="没有匹配", exact=False):
            log("goto_messages_list: 搜索浮层把列表过滤了（查询串 %r），先清掉" % query[:30])
            clr = find_node(nodes, text="清除搜索", exact=True)
            if clr is not None:
                tap_node(clr)
                time.sleep(0.8)
            elif field is not None:
                tap_node(field)
                time.sleep(0.4)
                chat_clear()
                time.sleep(0.4)
            press_back()
            time.sleep(0.8)
        # 若在工作区看板（顶栏标着「工作区」），必须切回消息底栏
        ws_header = find_node(nodes, text="工作区", exact=True)
        is_ws_tab = ws_header and ws_header.center and ws_header.center[1] < 600
        if not is_ws_tab and (find_node(nodes, text="搜索会话", exact=False) or \
           find_node(nodes, text="个会话", exact=False)):
            return True
        tab = find_node(nodes, text="消息", bottom=True)
        if tab and tab.center and tab.center[1] > 1200:
            tap_node(tab)
            time.sleep(1.0)
            continue
        press_back()
        time.sleep(1.0)
    nodes = ui_all()
    ws_header = find_node(nodes, text="工作区", exact=True)
    is_ws_tab = ws_header and ws_header.center and ws_header.center[1] < 600
    return not is_ws_tab and bool(find_node(nodes, text="搜索会话", exact=False)
                or find_node(nodes, text="个会话", exact=False))


def chat_title(timeout=3.0):
    """聊天页顶部标题：与「返回」按钮**同一行**、且在它右边的第一个文本节点。

    为什么不用 `ui_has(nick)`：聊天页上还有通知横幅、消息正文、会话列表残留文本，
    `ui_has` 一律会命中（group 夹具的 `direct-header-not-group` 就是被横幅里的
    「位成员」咬红的）。这里只认「返回」那一行右边的文本。

    **最左的那个文本节点不是标题，是头像首字母**（2026-10-10 探针实测：与 `E2Eprobe2`
    的聊天页里，最左文本是 `'E'`）。所以先把长度 ≤ 2 的节点滤掉（头像、⚠ 之类），
    在剩下的里面取**最靠上**的那个（同高再取最左）。

    为什么是「最靠上」而不是「最左」：**群聊页的标题下面压着第二行**「N 位成员」
    （2026-10-10 group run3 现场：`ChatHeader` 里群名与成员数是同一列的两行，返回键垂直居中，
    两行都落在 ±60 的带子里，dump 顺序里 `'2 位成员'` 在前 ⇒ 三次 `open_chat('grp956562')`
    都判「打开的不是我要的会话」，把 `group-chat-opens` 与它后面整片群读数拖红）。
    按 y 排序后群名赢；单聊页只有一行，行为不变。
    """
    deadline = time.monotonic() + max(0.0, timeout)
    while True:
        nodes = ui_all()
        back = find_node(nodes, desc="返回")
        if back and back.center:
            band = [n for n in nodes
                    if n.text and n.center
                    and abs(n.center[1] - back.center[1]) <= 60
                    and n.center[0] > back.center[0]]
            if band:
                long_band = [n for n in band if len(n.text.strip()) > 2]
                pick = long_band or band
                pick.sort(key=lambda n: (n.center[1], n.center[0]))
                return pick[0].text
        if time.monotonic() >= deadline:
            return None
        time.sleep(0.4)


def _title_matches(title, nick):
    """顶部标题是否就是我们要的那个会话（长名会被省略号截断，所以两头都容一点）。"""
    if not title:
        return False
    t = title.strip().rstrip("…").strip()
    n = nick.strip()
    return t == n or t.startswith(n) or (t and n.startswith(t))


def visible_texts(nodes=None, limit=0):
    """当前可见的全部 text + content-desc（`limit=0` 不截断），失败现场用。"""
    texts = [t for t in ui_texts(nodes) if t]
    if limit > 0:
        texts = texts[:limit]
    return " | ".join(texts)


def _list_top_signature():
    """会话列表"最上面那一行"的签名（用于判断是否滑到顶了）。

    只取 y > 850 的前三个文本节点：850 以上是标题、搜索框与筛选 chips，
    它们在滚动时不动，拿进来会让签名永远不变。
    """
    try:
        ns = [n for n in ui_all()
              if (n.text or "").strip() and n.center[1] > 850 and "EditText" not in (n.cls or "")]
    except Exception:
        return None
    if not ns:
        return None
    ns.sort(key=lambda n: (n.center[1], n.center[0]))
    return tuple((n.text.strip()[:20], n.center[1] // 40) for n in ns[:3])


def scroll_list_to_top(max_swipes=24):
    """把会话列表滑回顶部（列表是 LazyColumn，**会记住上次的滚动位置**）。

    2026-10-10 的真机现场：整条链跑完后会话列表停在半中间（更早的夹具用 `scroll_to_text`
    只往下滑、滑完不回去），而**新建的群、刚收到的会话按 ts 排在最前面** ⇒ 它正好落在视口
    **上方**。`scroll_to_text` 只会往下找（docstring：「从页面顶部往下找」），于是永远找不到：
    group 夹具 `open_chat('grpd290e5')` 三次都报「会话列表里没有它」（`group-chat-opens`、
    `group-conv-online-ratio`、`group-offline/reonline-numerator` 一起红），messaging 夹具
    `open_chat('E2E9797')` 那 15 条级联也是同一条。探针实测（`/tmp/probe_convlist4.log`）：
    先滑回顶部，`grpd290e5` 与 `E2Eprobe1/2` 都在第一屏。

    滑动落在列表中部（`y 1200 → 1900`）而不是屏幕下沿：下沿是底部导航栏的触摸区，
    那一下会被吃掉（R19 的同一个坑）。
    """
    # 2026-10-10 全量套件 run6 的现场：`open_chat` 的下探预算涨到 20 屏之后，
    # 固定 8 次回滑（≈8 屏）**回不到顶部**了 —— 列表停在半中间，而"刚建好、还没发过
    # 消息"的群行在最上面，往下找永远够不到（group 相位 8 条红）。
    #
    # 2026-10-10 group run13 又栽在**判据本身**上：当时的实现是"每 4 拍取一次签名，
    # 签名不再变就认为到顶"，实测 **2/4 次停在真顶之外**（顶首行是 `kexuejin` 而不是
    # 真顶的 `Tidings Agent`，目标群行恰在其上方一格 ⇒ `open_chat` 三次都报"列表里没有它"，
    # `group-chat-opens` + `group-inbound-chat-opened` 一起红）。原因是**长列表滑动有惯性
    # 余量**：内容先被推到最底、签名在第 17/18 拍短暂稳定，判据就提前收手了。
    #
    # 现在的判据是**churn 稳健**的：粗采样每 3 拍取一次签名（签名变了就继续滑），
    # 一旦签名**不再变**就做一次**确认**——再滑一拍、多等一会儿再取一次签名，只有确认
    # 也判定"没动"才算真到顶（在顶上一拍是空操作）。这样既避开惯性余量造成的瞬时
    # 稳定，也不会每拍都 dump（实测单次 dump 2.36s，每拍都取会让"从底回顶"涨到 98s）。
    # 注意不能拿"某个早先记下的签名"当真顶基准：列表是活的，每隔几分钟就有新节点在
    # 最上面新建空线程，真顶签名自己会变。
    # 实测：该手机从底回顶要 19 拍；新判据 5/5 到真顶（旧判据 2/4 失败）。
    budget = max(1, int(max_swipes))
    prev = None
    i = 0
    while i < budget:
        adb("shell", "input", "swipe", "540", "1200", "540", "1900", "250")
        time.sleep(0.25)
        i += 1
        if i % 3:
            continue
        sig = _list_top_signature()
        if sig is None or sig != prev:
            prev = sig
            continue
        # 候选到顶：确认那一拍（多等一会儿，让惯性余量走完）
        adb("shell", "input", "swipe", "540", "1200", "540", "1900", "250")
        time.sleep(0.45)
        i += 1
        sig2 = _list_top_signature()
        if sig2 is not None and sig2 == sig:
            break
        prev = sig2
    time.sleep(0.4)


# 群会话行的标题形态：夹具自己生成的群名（`e2e_group.py` 的 `R.uniq_marker("grp")` /
# `("ren")`，只用 [A-Za-z0-9._@%+=:]）。整行匹配，所以顶部横幅
# 「「grpcd6ca8」建好了，正在通知 1 位成员。」不会被误收进来。
_GROUP_ROW_RE = re.compile(r"^(?:grp|ren)[0-9a-zA-Z._@%+=:]{4,}$")


def _visible_group_rows():
    """当前屏上所有群会话行的标题（`grp*` / `ren*`）。

    2026-10-10 group run11：`open_chat('grpcd6ca8')` 在 R68/R70 报"列表里没有"，而
    **同一轮**稍后 R73 又能找到同一行 ⇒ 需要区分"行真的不在列表"和"行在但没滑到"。
    只读当前这一屏（不做任何滑动），所以它反映的是"这一刻看得见什么"。
    """
    try:
        ns = ui_all()
    except Exception:
        return []
    rows = [n.text.strip() for n in ns
            if n.text and _GROUP_ROW_RE.match(n.text.strip())]
    return sorted(set(rows))


def _device_group_names():
    """手机落盘的群名（`files/relay/settings.json` 的 `groups`）。

    与 `_visible_group_rows()` 对照就能判定：群名在落盘里存在、却不在列表里 ⇒ 是
    列表/读数问题；两边都没有 ⇒ 群真的被改名或被别的轮次动过。
    `groups` 是**列表**（不是 dict），每条形如
    `{"groupId": …, "name": …, "rev": …, "members": [...]}`。
    """
    try:
        raw = adb("exec-out", "run-as", PKG, "cat", "files/relay/settings.json",
                  timeout=15)
        start = raw.find("{")
        if start < 0:
            return "读不到 settings.json（%s）" % raw.strip()[:120]
        data = json.loads(raw[start:])
    except Exception as e:  # noqa: BLE001 —— 取证函数，失败不能让夹具挂掉
        return "读 settings.json 失败：%s" % e
    groups = data.get("groups")
    if groups is None:
        return "settings.json 里没有 groups 键（keys=%s）" % sorted(data.keys())
    if isinstance(groups, dict):
        items = list(groups.values())
    else:
        items = list(groups)
    out = []
    for g in items:
        if isinstance(g, dict):
            out.append("%s(rev=%s,members=%s)"
                       % (g.get("name"), g.get("rev"),
                          len(g.get("members") or [])))
        else:
            out.append(str(g))
    return "%d 条：%s" % (len(out), ", ".join(out)) or "0 条"


def open_chat(nick, timeout=10.0, tries=3):
    """在会话列表里点开与 `nick` 的那一行，**并核对真的进去了**。

    2026-10-09 的教训：原来点完只验「屏幕上有输入框」，于是点到别的会话也算成功 ——
    group 夹具 run2 的 22 条红里大半是这么来的（`open_chat 'grpd3be34'` 实际打开的是
    mac mini 的单聊，之后整段读数都在别人的页上）。所以这里做四件事：
      1. 先把列表滑回**顶部**（`scroll_list_to_top`）—— 列表会记住上次滚动位置，而新会话
         排在最前，只往下找永远够不到（见 `scroll_list_to_top` 的 docstring）；
      2. 行不在视口里就继续往下滚动再找（LazyColumn 不组合屏幕外的行，会话一多必漏）；
      3. 点完核对顶部标题（`chat_title`），不对就返回重试；
      4. 失败时把当前可见文本**全**打出来（原来只打前 24 条，行一多就恰好截掉要找的那行）。
    """
    for attempt in range(1, tries + 1):
        if not goto_messages_list():
            log("open_chat: 回不到会话列表")
            return False
        # 2026-10-10：先看**当前视口**里有没有（短探测），没有再回顶重找。回顶本身要 12s
        # （已在顶部）到 34s（从底部），而 `open_chat` 被 `_poll_row` 之类的轮询反复调用 ——
        # 无条件回顶会把一次 1s 的查找变成 12s，还会吃掉轮询预算。行在视口里时回顶是多余的。
        node = find_ui(text=nick, exact=True, timeout=1.0)
        if node is None:
            scroll_list_to_top()
            node = find_ui(text=nick, exact=True, timeout=timeout if attempt == 1 else 4.0)
        if node is None:
            # 2026-10-10 全量套件 run4/group run8 的现场：手机会话列表已经攒到 51 个，
            # 而**每个新出现的节点都会在最上面新建一条空线程**（「还没聊过，打个招呼 |
            # 刚刚」），把"刚建好、还没发过消息"的群行一路往下挤。原来只往下找 6 屏
            # （≈24 行）够不到 ⇒ `group-chat-opens` / `group-inbound-chat-opened` 红。
            # 改成 20 屏（≈80 行），足够覆盖 50+ 会话。
            node = scroll_to_text(nick, max_swipes=20, timeout=2.0)
        if node is None:
            # 2026-10-10 group run11 的现场：这里原来直接 `return False`，于是
            # `tries=3` 的重试**根本没发生**（日志只出现「第 1/3 次」）。而同一轮里
            # 同一个群行稍后又能找到（R73 那次 `open_chat 'grpcd6ca8' at (359, 924)`），
            # 说明"一次找不到"不等于"行不在"。所以改成回到循环重试，并把**列表里所有
            # 群行**打出来 —— 用来区分"行真的不在列表"和"行在但够不到"。
            log("open_chat: 会话列表里没有 %r（第 %d/%d 次）；当前可见：%s"
                % (nick, attempt, tries, visible_texts()))
            log("open_chat: 本次列表里的群行 = %s" % _visible_group_rows())
            if attempt >= tries:
                log("open_chat: 重试 %d 次都找不到 %r；手机落盘的群 = %s"
                    % (tries, nick, _device_group_names()))
                return False
            time.sleep(0.8)
            continue
        log("open_chat %r at %s（第 %d/%d 次）" % (nick, node.center, attempt, tries))
        tap_node(node)
        time.sleep(1.2)
        if chat_composer(timeout=4.0) is None:
            log("open_chat: 点了 %r 却没进聊天页（屏上没有输入框）" % nick)
            continue
        title = chat_title(timeout=2.5)
        if _title_matches(title, nick):
            return True
        # 不要在这里盲目 `press_back()`：在会话列表根页上按返回键会把 app 退出到
        # 启动器（run4 现场）。循环开头的 `goto_messages_list()` 会按需返回一次，
        # 且它现在只在"前台是本 app 且底栏不可见"时才按。
        log("open_chat: 想开 %r，打开的是 %r —— 退回列表重试" % (nick, title))
        time.sleep(0.8)
    return False


def chat_composer(timeout=3.0):
    """聊天页的输入框（class=EditText）。找不到就说明不在聊天页。"""
    return find_ui(cls="EditText", timeout=timeout)


def chat_field_text(timeout=2.0):
    """读当前输入框里的文本（空态是 ''）。"""
    node = find_ui(cls="EditText", timeout=timeout)
    return node.text if node else None


# 一批 DEL 的上限。真机实测（Pixel 6）单个 KEYCODE_DEL 约 20~40ms，慢的时候近 100ms，
# 而 `adb()` 的超时是 30s：一批 120 个最多约 12s，留足余量。
_CLEAR_CHUNK = 120


def chat_clear(n=48):
    """清空输入框。

    整批 keyevent 合并成**一次** shell 调用：逐次调用会慢 20 倍。但一次塞太多也不行 ——
    长文本相位框里可能有两三千字符，一条 `input keyevent 67 67 …` 命令会直接跑过
    `adb()` 的 30s 超时，于是抛出 EnvError、整个夹具记成 ENVERR（退出码 2）。
    2026-10-09 全量套件 run2 的 messaging 就是这么红的（死在「长文本」那一步）。
    所以按 [_CLEAR_CHUNK] 个一批，一批一次 shell。
    """
    n = int(n)
    if n <= 0:
        return
    input_keyevent(123)  # KEYCODE_MOVE_END
    while n > 0:
        batch = min(_CLEAR_CHUNK, n)
        adb("shell", "input", "keyevent", *([str(67)] * batch))  # KEYCODE_DEL × batch
        n -= batch


# `input text` 的注入保真度是真机实测出来的，不是猜的（Pixel 6 / 2026-10-09）：
#   * 6 字符的 'q19970' 一字不差；8 字符的 'c2b4fad8' 落成 'c2b4ad8'（吞掉中间一个）；
#   * 200 字符随机串保留 90.5%，1300 字符随机串保留 86.2%；
#   * 1300 个重复的 'L' 只保留 46.2% —— **重复字符被吞得最狠**。
# 结论：注入之后**必须回读校验**，任何"打了就当它进去了"的夹具都会间歇性假失败。


def _chunk_blob(n, seed=0):
    """给长文本灌注用的字符块：避免连续重复字符（重复会被 input text 吞掉）。"""
    al = "abcdefghijklmnopqrstuvwxyz0123456789"
    out = []
    for i in range(n):
        c = al[(seed + i * 7) % len(al)]
        if out and c == out[-1]:
            c = al[(seed + i * 7 + 1) % len(al)]
        out.append(c)
    return "".join(out)


def chat_type_verified(text, tries=8):
    """短文本注入 + 回读校验，不符就清空重打。返回**实际落框**的文本，失败返回 None。

    短标记必须一字不差：夹具后面就是靠这个字符串在收到的帧里认人。
    """
    for attempt in range(1, tries + 1):
        field = chat_composer(timeout=5.0)
        if not field:
            log("chat_type_verified: 找不到输入框（不在聊天页？）")
            return None
        tap_node(field)
        time.sleep(0.5)
        cur = chat_field_text()
        if cur:
            chat_clear(len(cur) + 4)
            time.sleep(0.3)
        adb_input_text(text)
        time.sleep(0.5)
        got = chat_field_text()
        if got == text:
            return got
        log("input text 吞字：想打 %r，框里是 %r（第 %d/%d 次；清空重打）"
            % (text, got, attempt, tries))
    return None


def chat_type_long(min_bytes, chunk=1000, tries=12):
    """把输入框灌到 ≥ min_bytes 字节，返回框里**实际**的文本（失败返回 None）。

    长文本不去逐字对账：`input text` 一趟大概要吞掉 10%~15%（1300 字符一份约 8.6 秒），
    所以策略是"吞了就从末尾继续补" —— 光标在末尾，追加只会让框里的内容变长。
    夹具真正要断言的是**手机发出去的那份 == 框里当时那份**（完整性），
    而不是"框里 == 我打算灌的那份"（注入保真度，那本来就不是被测对象）。
    """
    field = chat_composer(timeout=6.0)
    if not field:
        log("chat_type_long: 找不到输入框（不在聊天页？）")
        return None
    tap_node(field)
    time.sleep(0.5)
    cur = chat_field_text()
    if cur:
        chat_clear(len(cur) + 4)
        time.sleep(0.3)
    got = ""
    for attempt in range(1, tries + 1):
        _adb_input_text_fast(_chunk_blob(chunk, seed=attempt))
        time.sleep(0.6)
        got = chat_field_text() or ""
        if utf8_len(got) >= min_bytes:
            log("长文本落框 %dB（目标 ≥%dB，第 %d 次灌注）" % (utf8_len(got), min_bytes, attempt))
            return got
        log("长文本落框 %dB < %dB，继续追加 %d 字符" % (utf8_len(got), min_bytes, chunk))
    log("长文本只灌到 %dB，没到 %dB" % (utf8_len(got), min_bytes))
    return got or None


def chat_tap_send(timeout=5.0):
    """点聊天页的发送键。它是 content-desc='发送' 的 View，不是 text。"""
    ok = tap_text("发送", desc=True, timeout=timeout) or tap_text("发送", desc=False, timeout=2.0)
    time.sleep(0.9)
    return bool(ok)


def chat_send(text, tries=8):
    """输入一条**短**文本并发送。返回实际发出的文本，失败返回 None。"""
    landed = chat_type_verified(text, tries=tries)
    if landed is None:
        return None
    return landed if chat_tap_send() else None


def chat_send_long(min_bytes, chunk=1000, tries=12):
    """灌一条**长**文本并发送。返回实际发出的文本（夹具拿它断言对端收到的完全一致）。"""
    landed = chat_type_long(min_bytes, chunk=chunk, tries=tries)
    if not landed:
        return None
    return landed if chat_tap_send() else None


def chat_scroll_up(pixels=760):
    """消息列表往上滚（露出更早的消息）：从中间往下拖。

    会话页只组合可视区内的消息，滚出屏幕的旧消息在 dump 里根本不存在 ——
    所以"没找到"经常是"没滚上去"，不是"没收到"。
    """
    adb("shell", "input", "swipe", "540", "900", "540", str(900 + int(pixels)), "300")
    time.sleep(1.0)


# ---------------------------------------------------------------------------
# 7. 本地自检（--dry-run 也跑这些：不碰网络、不碰设备）
# ---------------------------------------------------------------------------

def local_self_checks(short_text, long_text, nick=DEFAULT_NICK, tcp_port=TCP_PORT_DEFAULT,
                      host=None, caps=None):
    """纯本地断言：信封大小、文本上限、profile 长度、分帧往返。返回 (short_env, long_env)。"""
    host = host or FIXTURE_HOST or DEFAULT_HOST_IP
    short_env = envelope(T_MSG, {"kind": K_TEXT, "text": short_text})
    long_env = envelope(T_MSG, {"kind": K_TEXT, "text": long_text})
    short_bytes = len(encode(short_env).encode("utf-8"))
    long_bytes = len(encode(long_env).encode("utf-8"))

    check("envelope-has-v1", short_env.get("v") == PROTOCOL_VERSION,
          "v=%r（不对就整帧被丢）" % short_env.get("v"))
    check("envelope-fields-complete",
          all(k in short_env for k in ("v", "type", "id", "from", "ts", "payload")),
          "keys=%s" % ",".join(short_env.keys()))
    check("short-text-fits-udp-text-limit", utf8_len(short_text) <= TEXT_UDP_LIMIT,
          "%dB <= %dB" % (utf8_len(short_text), TEXT_UDP_LIMIT))
    check("short-envelope-fits-udp-packet", short_bytes <= UDP_MAX_PAYLOAD,
          "%dB <= %dB" % (short_bytes, UDP_MAX_PAYLOAD))
    check("long-text-over-udp-text-limit", utf8_len(long_text) > TEXT_UDP_LIMIT,
          "%dB > %dB（A5 要的就是它非走 TCP 不可）" % (utf8_len(long_text), TEXT_UDP_LIMIT))
    check("long-envelope-forces-tcp", long_bytes > UDP_MAX_PAYLOAD,
          "%dB > %dB" % (long_bytes, UDP_MAX_PAYLOAD))
    check("long-text-within-tcp-limit", utf8_len(long_text) <= TEXT_TCP_LIMIT,
          "%dB <= %dB" % (utf8_len(long_text), TEXT_TCP_LIMIT))

    try:
        prof = profile_caps(nick=nick, caps=caps, tcp_port=tcp_port, host=host)
        plen_ok = (len(prof["ver"]) <= LIMITS["ver"] and len(prof["nick"]) <= LIMITS["nick"]
                   and len(prof["host"]) <= LIMITS["host"] and len(prof["caps"]) <= LIMITS["caps"])
        check("profile-caps-within-limits", plen_ok,
              "ver=%r(%d) nick=%d host=%d caps=%s" % (
                  prof["ver"], len(prof["ver"]), len(prof["nick"]), len(prof["host"]),
                  ",".join(prof["caps"])))
        check("profile-platform-declared", prof["platform"] in PLATFORMS,
              "platform=%s（协议里没有 mobile）" % prof["platform"])
    except FixtureError as e:
        check("profile-caps-within-limits", False, str(e))

    fr = tcp_frame({"type": "probe", "n": 1})
    back = parse_json(fr[4:])
    check("tcp-frame-roundtrip", struct.unpack(">I", fr[:4])[0] == len(fr) - 4 and back == {"type": "probe", "n": 1},
          "4 字节大端长度前缀 + JSON")
    check("reliable-set-covers-msg", T_MSG in RELIABLE_TYPES,
          "RELIABLE=%d 类，漏一类就会让对方重传到入队" % len(RELIABLE_TYPES))
    return short_env, long_env


# ---------------------------------------------------------------------------
# 8. CLI 包装
# ---------------------------------------------------------------------------

def main_wrapper(fn, description="", extra_args=None):
    """统一 CLI + 统一异常收敛。

    所有夹具的 main 都是 `sys.exit(main_wrapper(run))`。`fn(args)` 正常返回 None
    或一个退出码；dry-run 由夹具自己判断（`if args.dry_run: return 0`）。
    """
    ap = argparse.ArgumentParser(
        description=description,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--phone", default=DEFAULT_PHONE,
                    help="手机 IP[:UDP端口]，默认 %(default)s")
    ap.add_argument("--host-ip", default=DEFAULT_HOST_IP,
                    help="本机 LAN IP（写进夹具 profile.host，必须非空），默认 %(default)s")
    ap.add_argument("--secs", type=int, default=90,
                    help="本夹具的总预算秒数（等待窗口的上界），默认 %(default)s")
    ap.add_argument("--dry-run", action="store_true",
                    help="只做参数与本地自检：不碰网络、不碰设备，退出码 0")
    if extra_args:
        extra_args(ap)
    args = ap.parse_args()

    try:
        args.phone_ip, args.phone_udp = split_host_port(args.phone, UDP_PORT)
        args.fixture_node = FIXTURE_NODE
        if not args.dry_run:
            set_host(args.host_ip)
            host_ok = is_ipv4(args.host_ip)
            log("host-ip=%s phone=%s:%d nodeId=%s" % (
                args.host_ip, args.phone_ip, args.phone_udp, FIXTURE_NODE[:8]))
            if not host_ok:
                raise EnvError("--host-ip 不是合法 IPv4：%r" % args.host_ip)
        rc = fn(args)
    except EnvError as e:
        print("env-error: %s" % e, file=sys.stderr, flush=True)
        return 2
    except FixtureError as e:
        print("fixture-error: %s" % e, file=sys.stderr, flush=True)
        return 2
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr, flush=True)
        return 2
    except Exception:
        traceback.print_exc()
        return 2
    if isinstance(rc, int):
        return rc
    return report()


def split_host_port(s, default_port):
    """`ip` / `ip:port` / `[v6]:port` → (ip, port)。照抄投屏夹具的 partition 做法。"""
    if not s:
        raise EnvError("--phone 不能为空")
    if s.startswith("["):
        i = s.find("]")
        if i < 0:
            raise EnvError("--phone 的 IPv6 写法少了 ]：%r" % s)
        ip = s[1:i]
        rest = s[i + 1:]
        port = int(rest[1:]) if rest.startswith(":") else default_port
        return ip, port
    ip, sep, port = s.partition(":")
    if sep:
        try:
            return ip, int(port)
        except ValueError:
            raise EnvError("--phone 端口不是数字：%r" % s)
    return ip, default_port


IPV4_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")


def is_ipv4(s):
    if not IPV4_RE.match(s or ""):
        return False
    return all(0 <= int(p) <= 255 for p in s.split("."))


def uniq_marker(prefix, n=6):
    """一个 adb 注入得进去、又不会撞上历史消息的标记（只用字母数字）。"""
    return "%s%s" % (prefix, uuid.uuid4().hex[:n])


def long_text(total, head="L", tail_marker=None):
    """造一条 >TEXT_UDP_LIMIT 的 ASCII 文本，尾巴带一个唯一标记（用来验完整性）。"""
    tail = tail_marker or uniq_marker("z")
    body = head * max(1, total - len(tail))
    return body + tail
