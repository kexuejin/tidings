# SPDX-License-Identifier: GPL-3.0-only

#!/usr/bin/env python3
"""挂住一条 WebSocket 长连接，看它在指定时长里会不会被谁悄悄掐掉。

零依赖。用途是验收「跨网通路能不能长期挂着」——这是 4G 真机之外
本机就能验、而且必须在真机之前验掉的一项：中间任何一环（边缘代理、
隧道、agentNode 的空闲回收）都可能静默断链，而断链在手机上表现为
「过一会儿就不会发消息了」，不查根本发现不了。

    python3 tools/ws-soak.py "wss://host/relay-ws/v1/dgram" --minutes 30

**为什么复用 `ws-relay-probe.py` 而不是再抄一遍 RFC 6455**：
自己写的探针会和服务端共享同一个误解（子协议回显那次就是两个实现都错、
本机怎么测都测不出来）。握手和分帧一律 import 现成的，只在这里加「时间维度」。

保活信号用**客户端主动发 PING、等 PONG**：
它不往协议里塞任何东西（不会在桌面节点的在线列表里留下假节点），
又能拿到真实的应用层往返延迟。服务端每 30 秒也会发一次 PING，一并计数。
"""

import importlib.util
import os
import socket
import struct
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("probe", os.path.join(_HERE, "ws-relay-probe.py"))
probe = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(probe)

OP_PING, OP_PONG = probe.OP_PING, probe.OP_PONG


def main():
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--token", default=os.environ.get("RELAY_AGENT_TOKEN", ""))
    ap.add_argument("--minutes", type=float, default=30.0)
    ap.add_argument("--ping-every", type=float, default=60.0, help="客户端主动 PING 的间隔")
    ap.add_argument("--read-timeout", type=float, default=15.0,
                    help="单次读的等待上限。设小了会在数据到达一半时丢掉半帧，"
                         "把「网络有停顿」读成「分帧错乱」")
    ap.add_argument("--connect-timeout", type=float, default=20.0)
    ap.add_argument("-4", dest="family", action="store_const", const=probe.socket.AF_INET,
                    help="把客户端钉死在 IPv4")
    ap.add_argument("-6", dest="family", action="store_const", const=probe.socket.AF_INET6,
                    help="把客户端钉死在 IPv6。双栈机器上要验 IPv6-only 就必须显式加")
    ap.add_argument("--connect-to", metavar="HOST:PORT",
                    help="实际拨到这个地址，SNI 与 Host 仍取自 URL。"
                         "配合 net-shim 就能把弱网插在「探针 → 公网」这一段，"
                         "验的是「坏网络 + 长期挂着」而不是回环上的假象")
    args = ap.parse_args()

    sock = probe.connect(args.url, args.token, args.connect_timeout,
                         family=args.family, connect_to=args.connect_to)
    print("已连上 %s，挂 %.0f 分钟" % (args.url, args.minutes), flush=True)

    total = args.minutes * 60.0
    t0 = time.time()
    deadline = t0 + total
    next_ping = t0 + args.ping_every
    next_report = t0 + 60.0

    srv_pings = 0        # 收到服务端的 PING（我们回了 PONG）
    cli_pongs = 0        # 收到我们 PING 的 PONG
    rtts = []
    sent_ping_at = None  # 最近一次主动 PING 的发出时刻，用于算 RTT
    disconnected = None
    close_note = None

    while time.time() < deadline:
        now = time.time()
        if now >= next_ping:
            sock.sendall(probe.mask_frame(OP_PING, b"soak"))
            sent_ping_at = now
            next_ping += args.ping_every
        if now >= next_report:
            elapsed = now - t0
            last = rtts[-1] if rtts else float("nan")
            avg = sum(rtts) / len(rtts) if rtts else float("nan")
            print("[%6.0fs] 服务端 PING %d 次 / 我们的 PONG %d 次 / 最近 RTT %.0fms / 平均 %.0fms"
                  % (elapsed, srv_pings, cli_pongs, last * 1000, avg * 1000), flush=True)
            next_report += 60.0

        sock.settimeout(args.read_timeout)
        try:
            frame = probe.read_frame(sock)
        except (socket.timeout, TimeoutError):
            continue          # 没消息 = 链路还活着，继续等
        except OSError as exc:
            disconnected = time.time() - t0
            close_note = "读失败：%s" % exc
            break
        if frame is None:
            disconnected = time.time() - t0
            close_note = "对端关闭了连接"
            break

        fin, opcode, payload = frame
        if opcode == OP_PING:
            sock.sendall(probe.mask_frame(OP_PONG, payload))
            srv_pings += 1
        elif opcode == OP_PONG:
            cli_pongs += 1
            if sent_ping_at is not None:
                rtts.append(time.time() - sent_ping_at)
                sent_ping_at = None
        elif opcode == OP_CLOSE:
            code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else None
            disconnected = time.time() - t0
            close_note = "收到 CLOSE，code=%s reason=%s" % (
                code, payload[2:].decode("utf-8", "replace"))
            break
        else:
            print("[%6.0fs] 收到业务帧 opcode=0x%X %d 字节"
                  % (time.time() - t0, opcode, len(payload)), flush=True)

    survived = (disconnected if disconnected is not None else time.time() - t0)
    print("-" * 60)
    print("存活 %.0f 秒 / 计划 %.0f 秒" % (survived, total))
    print("服务端 PING %d 次（均已回 PONG）" % srv_pings)
    print("我们的 PING 得到 PONG %d 次" % cli_pongs)
    if rtts:
        print("RTT 最小 %.0fms / 平均 %.0fms / 最大 %.0fms"
              % (min(rtts) * 1000, sum(rtts) / len(rtts) * 1000, max(rtts) * 1000))
    if close_note:
        print("断链：%s" % close_note)
        print("结论：❌ 没撑住 —— 在 %.0f 秒时被断开" % survived)
        return 1
    print("结论：✅ 撑满了全程，期间没有一次断链或空闲超时")
    try:
        sock.close()
    except OSError:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())