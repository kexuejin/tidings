# SPDX-License-Identifier: GPL-3.0-only

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
agentNode WS 网关的探针。零依赖手写 RFC 6455，用来在没有手机端的情况下验证两条通路。

    # 日常聊天走的 UDP 通路，对真实桌面节点
    ws-relay-probe.py wss://host/relay-ws/v1/dgram --token "$TOKEN" --entry

    # TCP 字节管道（长文本 / 文件传输）
    ws-relay-probe.py wss://host/relay-ws/v1/pipe --token "$TOKEN" --payload "hello"

为什么手写而不用 websockets/websocat：
本机 python 环境不一定有这些包，而这个脚本是**排查通路**时唯一能立刻跑起来的东西。
通路出问题时"先确认客户端库行为"会让人误判成服务端坏了 —— 自己写的客户端没有这种二义性。

踩过的坑，写在这里免得再犯：**MASK 位在第二个字节的最高位**（byte0=FIN/RSV/OPCODE，byte1=MASK/LEN）。
读成 head[0] & 0x40 会把每个正常客户端帧都判成"未掩码"，现象是"握手成功、然后立刻被断开"。

另：client 的帧**必须**加掩码（RFC 6455 §5.3），服务端可以不加。手写服务端时反过来搞错，
客户端会安静地丢弃你的帧 —— 双向都可能踩，所以这个脚本和 agentNode 用的是同一套规则。
"""

import argparse
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import sys
import time
import uuid

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
OP_CONT, OP_TEXT, OP_BINARY, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def recv_line(sock):
    out = b""
    while not out.endswith(b"\r\n"):
        b = sock.recv(1)
        if not b:
            return None
        out += b
    return out[:-2].decode("utf-8", "replace")


def mask_frame(opcode, payload):
    """客户端帧必须掩码。"""
    n = len(payload)
    head = bytearray([0x80 | opcode])
    if n < 126:
        head.append(0x80 | n)
    elif n < 65536:
        head.append(0x80 | 126)
        head += struct.pack("!H", n)
    else:
        head.append(0x80 | 127)
        head += struct.pack("!Q", n)
    key = os.urandom(4)
    head += key
    masked = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
    return bytes(head) + masked


def read_frame(sock):
    """返回 (fin, opcode, payload)；None 表示对端断了。服务端帧不加掩码。"""
    head = recv_exact(sock, 2)
    if head is None:
        return None
    fin = bool(head[0] & 0x80)
    opcode = head[0] & 0x0F
    masked = bool(head[1] & 0x80)
    length = head[1] & 0x7F
    if length == 126:
        length = struct.unpack("!H", recv_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", recv_exact(sock, 8))[0]
    if masked:
        key = recv_exact(sock, 4)
    payload = recv_exact(sock, length) if length else b""
    if payload is None:
        return None
    if masked:
        payload = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
    return fin, opcode, payload


def connect(url, token, timeout, family=None, connect_to=None):
    """family 传 socket.AF_INET6 可以把客户端钉死在 IPv6 上。

    为什么需要它：`socket.create_connection` 走 getaddrinfo，拿到哪个族就用哪个，
    双栈机器上几乎总是先给 IPv4，于是「这条通路在 IPv6-only 网络上能不能用」
    在本机永远测不出来 —— 而 IPv6-only 恰恰是移动网络的常态。

    `connect_to`（"host:port"）把**实际拨号地址**换掉，而 SNI 与 `Host:` 头仍取自
    URL。这是为了让 net-shim 能插在「探针 → 公网」这一段：没有它的话，探针要么
    直连 Cloudflare（那 net-shim 白插了），要么连 127.0.0.1（那 TLS 校验直接失败）。
    等价于 `curl --resolve`。
    """
    secure = url.startswith("wss://")
    rest = url.split("://", 1)[1]
    hostport, _, path = rest.partition("/")
    path = "/" + path
    host, _, port = hostport.partition(":")
    dial_host, dial_port = host, int(port or (443 if secure else 80))
    if connect_to:
        ch, _, cp = connect_to.rpartition(":")
        dial_host, dial_port = ch or connect_to, int(cp)
    if family is None:
        raw = socket.create_connection((dial_host, dial_port), timeout=timeout)
    else:
        infos = socket.getaddrinfo(dial_host, dial_port, family, socket.SOCK_STREAM)
        if not infos:
            raise SystemExit("没有 %s 的地址：%s" % (socket.AddressFamily(family).name, dial_host))
        af, socktype, proto, _, sockaddr = infos[0]
        raw = socket.socket(af, socktype, proto)
        raw.settimeout(timeout)
        raw.connect(sockaddr)
    sock = ssl.create_default_context().wrap_socket(raw, server_hostname=host) if secure else raw
    key = base64.b64encode(os.urandom(16)).decode()
    lines = [
        "GET %s HTTP/1.1" % path,
        "Host: %s" % hostport,
        "Upgrade: websocket",
        "Connection: Upgrade",
        "Sec-WebSocket-Key: %s" % key,
        "Sec-WebSocket-Version: 13",
    ]
    if token:
        lines.append("Authorization: Bearer %s" % token)
    sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())

    status = recv_line(sock)
    if status is None:
        raise SystemExit("握手没读到状态行")
    headers = {}
    while True:
        line = recv_line(sock)
        if line is None:
            raise SystemExit("握手头没读完")
        if line == "":
            break
        k, _, v = line.partition(":")
        headers[k.strip().lower()] = v.strip()
    if "101" not in status:
        body = b""
        if "content-length" in headers:
            body = recv_exact(sock, int(headers["content-length"])) or b""
        raise SystemExit("升级被拒：%s\n%s" % (status, body.decode("utf-8", "replace")))
    if headers.get("sec-websocket-accept") != base64.b64encode(
        hashlib_sha1((key + GUID).encode())
    ).decode():
        raise SystemExit("Sec-WebSocket-Accept 对不上")
    return sock


def hashlib_sha1(data):
    import hashlib

    return hashlib.sha1(data).digest()


def entry_frame(tcp_port=17879, node_id="probe-0001"):
    """一条真的 entry 信封。UDP 报文是**裸 UTF-8 JSON、没有长度前缀**（只有 TCP 有 4 字节大端前缀）。

    两个不写出来就会踩的坑：
    1. `tcpPort` 必须是 1..65535 —— `Codec.validateProfile` 里 `if (tcpPort < 1 ...) return false`，
       校验不过的报文在 `onDatagram` 第一行就被丢掉，**对端一声不吭**，看起来像网络不通。
    2. entry 的回复是 ALIVE，延迟在 0..`entryReplyJitterMax`（8 秒）内随机抖动，
       所以等待窗口必须大于 8 秒，否则会把"还没到"读成"不会来"。
    """
    profile = {
        "nodeId": node_id,
        "nick": "WS探针",
        "company": "",
        "dept": "",
        "team": "",
        "avatar": -1,
        "profileRev": 1,
        "host": "ws-probe",
        "platform": "linux",
        "tcpPort": tcp_port,
        "ver": "0.0.0-probe",
        "caps": [],
    }
    env = {
        "v": 1,
        "type": "entry",
        "id": str(uuid.uuid4()),
        "from": node_id,
        "ts": int(time.time() * 1000),
        "payload": {"profile": profile},
    }
    return json.dumps(env, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def tcp_frame(body):
    """TCP 才有的 4 字节大端长度前缀（`TcpFrame.LENGTH_PREFIX_BYTES`）。

    UDP 是裸 JSON、**没有**这个前缀 —— 两条通路的线格式不一样，
    拿错一种去发，对端的表现都是"连上了然后突然断开"，很容易误判成隧道断流。
    """
    return struct.pack("!I", len(body)) + body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--token", default=os.environ.get("RELAY_AGENT_TOKEN", ""))
    ap.add_argument("--entry", action="store_true", help="发一条真 entry 帧（dgram 模式用）")
    ap.add_argument(
        "--node-id",
        default="probe-0001",
        help="entry 的 nodeId。**每次换一个**：对端按 nodeId 去重回复，"
        "同一个 id 重复发会拿到沉默，那是协议行为不是故障。",
    )
    ap.add_argument("--payload", default="", help="发这段字面字节")
    ap.add_argument("--file", help="发这个文件的内容")
    ap.add_argument("--timeout", type=float, default=12.0)
    ap.add_argument(
        "--expect-bytes",
        type=int,
        default=0,
        help="收满这么多下行字节就收工。**没有它就没法测传输耗时**："
             "对端不会主动关连接，探针只能一路等到 --timeout，"
             "于是「传完了」和「等到超时了」在耗时上长得一模一样",
    )
    ap.add_argument("--raw", action="store_true", help="把收到的字节按十六进制/UTF-8 原样打印")
    ap.add_argument(
        "--digest",
        action="store_true",
        help="算下行字节的 sha256 并与上行比对。**量具要先验一条已知答案**："
        "只看「下行多少字节」会把截断读成成功",
    )
    ap.add_argument("-4", dest="family", action="store_const", const=socket.AF_INET,
                    help="把客户端钉死在 IPv4")
    ap.add_argument("-6", dest="family", action="store_const", const=socket.AF_INET6,
                    help="把客户端钉死在 IPv6。**双栈机器上必须显式加**，"
                         "否则 create_connection 几乎总先给 IPv4，"
                         "「IPv6-only 网络上能不能用」在本机就永远测不出来")
    ap.add_argument("--connect-to", metavar="HOST:PORT",
                    help="实际拨到这个地址，SNI 与 Host 仍取自 URL（等价 curl --resolve）。"
                         "用来把 net-shim 插在「探针 → 公网」这一段")
    args = ap.parse_args()

    sock = connect(args.url, args.token, args.timeout, family=args.family,
                   connect_to=args.connect_to)
    fam = "IPv4" if args.family == socket.AF_INET else ("IPv6" if args.family == socket.AF_INET6 else "自动")
    peer = sock.getpeername()
    local = sock.getsockname()
    print("已连上 %s（%s；本地 %s → 对端 %s）"
          % (args.url, fam, "[%s]:%d" % local[:2] if fam == "IPv6" else "%s:%d" % local[:2],
             "[%s]:%d" % peer[:2] if fam == "IPv6" else "%s:%d" % peer[:2]))

    if args.file:
        with open(args.file, "rb") as fh:
            body = fh.read()
    elif args.entry:
        body = entry_frame(node_id=args.node_id)
    else:
        body = args.payload.encode("utf-8")
    if not body:
        raise SystemExit("没东西可发：用 --entry / --payload / --file")
    # 按端点补线格式：/v1/pipe 走 TCP 需要长度前缀，/v1/dgram 是裸 JSON。
    if args.entry:
        body = tcp_frame(body) if "/v1/pipe" in args.url else body
    sock.sendall(mask_frame(OP_BINARY, body))
    print("↑ 已发 %d 字节（二进制帧%s）" % (len(body), "，含 4 字节长度前缀" if args.entry and "/v1/pipe" in args.url else ""))

    deadline = time.time() + args.timeout
    got = 0
    sink = hashlib.sha256() if args.digest else None
    while time.time() < deadline:
        if args.expect_bytes and got >= args.expect_bytes:
            break
        sock.settimeout(max(0.5, deadline - time.time()))
        try:
            frame = read_frame(sock)
        except (socket.timeout, TimeoutError):
            break
        except OSError as exc:
            print("↓ 连接结束：%s" % exc)
            break
        if frame is None:
            print("↓ 对端关闭了连接")
            break
        _, opcode, payload = frame
        if opcode == OP_PING:
            sock.sendall(mask_frame(OP_PONG, payload))
            continue
        if opcode == OP_PONG:
            continue
        if opcode == OP_CLOSE:
            code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else None
            print("↓ 收到 CLOSE 帧，code=%s reason=%s" % (code, payload[2:].decode("utf-8", "replace")))
            break
        got += len(payload)
        if sink is not None:
            sink.update(payload)
        if args.raw:
            print("↓ %d 字节  hex=%s" % (len(payload), payload[:64].hex()))
            print("   text=%s" % payload[:400].decode("utf-8", "replace"))
        else:
            print("↓ %d 字节  %s" % (len(payload), payload[:400].decode("utf-8", "replace")))
    if sink is not None:
        up = hashlib.sha256(body).hexdigest()
        down = sink.hexdigest()
        print("上行 sha256 %s" % up)
        print("下行 sha256 %s" % down)
        print("回显一致：%s（上行 %d / 下行 %d 字节）" % (up == down, len(body), got))
    print("合计下行 %d 字节" % got)
    sock.close()


if __name__ == "__main__":
    main()
