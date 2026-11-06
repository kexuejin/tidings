#!/usr/bin/env python3
"""投屏验收夹具：一台"看得懂 screen 的桌面节点"的线协议替身。

为什么是 Python 而不是现成部件：agentNode 的 AGENT_CAPS 里没有 rv1/rvs1（对 agent
投屏没有意义），桌面端要 GUI 驱动不可脚本化。这个夹具只做协议层：
握手（entry/alive/presence）→ 收 screen 信令 → 需要时按 screen-open 连对端 TCP 收帧。

两种模式：
  viewer  —— 向手机请求看它的屏（验手机当 Sharer：MediaProjection 全链路）
  sharer  —— 向手机发投屏邀请并在同意后用假 JPEG 泵流（验手机当 Viewer：投递+渲染）
  watch   —— 只握手并报告对端声明的 caps（先验 rvs1 广播有没有落地）

用法: python3 relay-screen-fixture.py <viewer|sharer|watch> --phone <ip[:port]> [--secs N]
证据行都带 [EVID] 前缀，直接贴进验收记录。
"""
import argparse
import json
import socket
import struct
import sys
import threading
import time
import uuid

UDP_PORT = 17878
TCP_PORT_DEFAULT = 17879
FIXTURE_NODE = str(uuid.uuid4())
FIXTURE_HOST = ""  # 填本机 LAN IP（entry 里 host 字段要非空字符串）


def now_ms():
    return int(time.time() * 1000)


def envelope(mtype, payload, mid=None):
    return {
        "v": 1,
        "type": mtype,
        "id": mid or str(uuid.uuid4()),
        "from": FIXTURE_NODE,
        "ts": now_ms(),
        "payload": payload,
    }


def profile_caps(rvs1=True):
    caps = ["tw1", "mrec1", "agn1", "rv1"]
    if rvs1:
        caps.append("rvs1")
    return {
        "nodeId": FIXTURE_NODE,
        "nick": "夹具桌面",
        "company": "",
        "dept": "",
        "team": "",
        "avatar": 0,
        "profileRev": 0,
        "host": FIXTURE_HOST,
        "platform": "linux",
        "tcpPort": TCP_PORT_DEFAULT,
        # ver 必须 ≤ LIMITS.ver = 16（TS diagnostics.ts 与 Kotlin Limits.VER 都是 16）。
        # 曾经写成 "relay-fixture-0.1"（17 字符）⇒ 手机侧 validateProfile 整帧拒收，
        # 连 entry 都握不上手，现象是「15s 内没握上手」而看不出任何别的原因。
        "ver": "fixture-0.1",
        "caps": caps,
    }


def send_entry(sock, host, port, alive=False):
    p = profile_caps()
    if alive:
        # 被动应答时按协议回 alive（对方 entry 进来我们回 alive，带全量资料）
        sock.sendto(json.dumps(envelope("alive", {"profile": p})).encode(), (host, port))
    else:
        sock.sendto(json.dumps(envelope("entry", {"profile": p})).encode(), (host, port))


def tcp_frame(obj, body=b""):
    text = json.dumps(obj)
    raw = text.encode()
    return struct.pack(">I", len(raw)) + raw + body


def make_small_jpeg(w=64, h=48):
    """手工造一个最小合法 JPEG（SOF0 带 w/h + EOI），夹具用它泵"画面"。

    ScreenImageMetadata/validateScreenFrame 只读 SOF 的头（marker 走查），不解像素，
    所以不需要真编码器 —— 但必须是它的走查能认的形状：FFD8、SOF 段、FFD9 收尾。
    """
    sof = bytes([0xFF, 0xC0, 0x00, 0x11, 0x08]) + struct.pack(">HH", h, w) + bytes(
        [3, 1, 0x21, 0x01, 2, 0x11, 0x00, 3, 0x11, 0x00]
    )
    return bytes([0xFF, 0xD8]) + sof + bytes([0xFF, 0xD9])


class Fixture:
    def __init__(self, phone_ip, mode, secs, phone_udp=UDP_PORT):
        self.phone_ip = phone_ip
        self.phone_udp = phone_udp
        self.mode = mode
        self.secs = secs
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("0.0.0.0", 0))
        self.phone_profile = None
        self.screen_msgs = []
        self.stop = threading.Event()

    def log(self, *a):
        print("[EVID]", int(time.time()), *a, flush=True)

    def send(self, env):
        self.sock.sendto(json.dumps(env).encode(), (self.phone_ip, self.phone_udp))

    def send_screen(self, payload):
        self.send(envelope("screen", payload))

    def handshake(self):
        """反复 entry 直到拿到对端 alive/profile（手机在线时通常 <2s）。"""
        deadline = time.time() + 15
        while time.time() < deadline and not self.stop.is_set():
            send_entry(self.sock, self.phone_ip, self.phone_udp)
            self.sock.settimeout(1.0)
            try:
                while True:
                    data, addr = self.sock.recvfrom(4096)
                    self.handle(data, addr)
                    if self.phone_profile:
                        return True
            except socket.timeout:
                continue
        return False

    def handle(self, data, addr):
        try:
            env = json.loads(data.decode())
        except Exception:
            return
        t = env.get("type")
        if t == "ack":
            return
        if t == "alive" or (t == "profile"):
            prof = env.get("payload", {}).get("profile")
            if prof:
                self.phone_profile = prof
                self.log("phone-profile", json.dumps(prof, ensure_ascii=False))
        elif t == "screen":
            p = env.get("payload", {})
            self.screen_msgs.append((p, env.get("from")))
            # 协议纪律：可靠帧先回 ACK 再处理 —— 这里 ACK 了 request 的应答链由我们自己驱动，
            # 但对端发给我们的每条 screen 都要 ACK（否则手机侧重传到队列剪枝）。
            ack = envelope("ack", {"ackFor": env["id"]})
            self.sock.sendto(json.dumps(ack).encode(), addr)
            self.log("phone-screen", json.dumps(p, ensure_ascii=False))

    def pump_reader(self, session_id, token):
        """viewer 模式：连手机 TCP，screen-open → 收 ready → 发 next → 收帧。"""
        tcp = socket.create_connection((self.phone_ip, self.phone_profile.get("tcpPort", TCP_PORT_DEFAULT)), timeout=10)
        tcp.sendall(tcp_frame({"type": "screen-open", "from": FIXTURE_NODE, "sessionId": session_id, "token": token}))
        self.log("tcp-open-sent", session_id)
        buf = b""
        frames = 0
        sizes = []
        deadline = time.time() + 30

        def recvall(n):
            nonlocal buf
            while len(buf) < n:
                chunk = tcp.recv(65536)
                if not chunk:
                    raise EOFError()
                buf += chunk
            out, buf = buf[:n], buf[n:]
            return out

        # ready 帧
        (ln,) = struct.unpack(">I", recvall(4))
        ready = json.loads(recvall(ln))
        self.log("tcp-ready", json.dumps(ready))
        seq = 0
        try:
            while time.time() < deadline and frames < 60 and not self.stop.is_set():
                seq += 1
                tcp.sendall(tcp_frame({"type": "screen-next", "sessionId": session_id, "seq": seq}))
                (ln,) = struct.unpack(">I", recvall(4))
                head = json.loads(recvall(ln))
                if head.get("type") != "screen-frame":
                    self.log("tcp-non-frame", json.dumps(head))
                    break
                payload = recvall(head["len"])
                frames += 1
                sizes.append((head["width"], head["height"], head["len"]))
                if frames in (1, 2, 10, 30):
                    self.log(f"frame#{frames}", f"{head['width']}x{head['height']}", f"{head['len']}B",
                             f"jpeg-magic={payload[:2].hex()}")
        except (EOFError, socket.timeout, ConnectionResetError) as e:
            self.log("tcp-end", repr(e))
        finally:
            tcp.close()
        uniq = len(set(sizes))
        self.log("frames-total", frames, "distinct-sizes", uniq, "sample", sizes[:3])
        return frames

    def pump_sharer(self, session_id, token):
        """sharer 模式：监听 TCP，等手机 screen-open，然后按 next 泵假 JPEG。"""
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", TCP_PORT_DEFAULT))
        srv.listen(1)
        self.log("tcp-listen", TCP_PORT_DEFAULT)
        srv.settimeout(25)
        try:
            conn, addr = srv.accept()
        except socket.timeout:
            self.log("tcp-accept-timeout", "手机没有连进来")
            srv.close()
            return 0
        srv.close()
        self.log("tcp-accepted", str(addr))
        buf = b""

        def recvall(n):
            nonlocal buf
            while len(buf) < n:
                chunk = conn.recv(65536)
                if not chunk:
                    raise EOFError()
                buf += chunk
            out, buf = buf[:n], buf[n:]
            return out

        (ln,) = struct.unpack(">I", recvall(4))
        open_head = json.loads(recvall(ln))
        self.log("screen-open", json.dumps(open_head))
        if open_head.get("token") != token or open_head.get("sessionId") != session_id:
            self.log("open-mismatch", "token/sessionId 与 accept 发出去的不一致")
            conn.close()
            return 0
        conn.sendall(tcp_frame({"type": "screen-ready", "sessionId": session_id}))
        sent = 0
        try:
            while sent < 40 and not self.stop.is_set():
                (ln,) = struct.unpack(">I", recvall(4))
                nxt = json.loads(recvall(ln))
                if nxt.get("type") != "screen-next" or nxt.get("seq") != sent + 1:
                    self.log("unexpected", json.dumps(nxt))
                    break
                j = make_small_jpeg(64 + (sent % 5) * 8, 48)
                conn.sendall(tcp_frame(
                    {"type": "screen-frame", "sessionId": session_id, "seq": nxt["seq"],
                     "width": 64 + (sent % 5) * 8, "height": 48, "len": len(j)}, j))
                sent += 1
                if sent in (1, 20):
                    self.log(f"pump#{sent}", "frame-sent", len(j), "B")
        except (EOFError, ConnectionResetError) as e:
            self.log("pump-end", repr(e))
        finally:
            conn.close()
        self.log("frames-sent", sent)
        return sent

    def run_viewer(self):
        self.log("mode", "viewer（手机当 Sharer）")
        sid = str(uuid.uuid4())
        self.send_screen({"op": "request", "sessionId": sid})
        # 等 accept（人在手机上点系统弹窗 → 控制器回 accept 带 token）
        deadline = time.time() + self.secs
        token = None
        while time.time() < deadline:
            self.sock.settimeout(1.0)
            try:
                data, addr = self.sock.recvfrom(4096)
            except socket.timeout:
                continue
            env = json.loads(data.decode())
            if env.get("type") == "screen" and env["payload"].get("sessionId") == sid:
                ack = envelope("ack", {"ackFor": env["id"]})
                self.sock.sendto(json.dumps(ack).encode(), addr)
                p = env["payload"]
                self.log("phone-screen", json.dumps(p, ensure_ascii=False))
                if p.get("op") == "accept":
                    token = p["token"]
                    break
                if p.get("op") in ("reject", "end"):
                    self.log("FAILED", p)
                    return
        if not token:
            self.log("FAILED", f"{self.secs}s 内没等到 accept")
            return
        frames = self.pump_reader(sid, token)
        self.log("result", "PASS" if frames >= 10 else "PARTIAL" if frames > 0 else "FAIL",
                 "frames", frames)

    def run_sharer(self):
        self.log("mode", "sharer（手机当 Viewer）")
        sid = str(uuid.uuid4())
        token = uuid.uuid4().hex
        # 先起监听再邀请
        t = threading.Thread(target=lambda: (
            self.log("sharer-threads", "listener-start"),
        ))
        received = {}

        def listener():
            srv = socket.socket()
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("0.0.0.0", TCP_PORT_DEFAULT))
            srv.listen(1)
            self.log("tcp-listen", TCP_PORT_DEFAULT)
            srv.settimeout(60)
            try:
                conn, addr = srv.accept()
            except socket.timeout:
                self.log("FAILED", "手机没有连进来（没收到 screen-open）")
                received["frames"] = 0
                srv.close()
                return
            srv.close()
            received["frames"] = self.pump_sharer(sid, token)

        threading.Thread(target=listener, daemon=True).start()
        time.sleep(0.5)
        self.send_screen({"op": "request", "sessionId": sid})
        deadline = time.time() + self.secs
        while time.time() < deadline:
            self.sock.settimeout(1.0)
            try:
                data, addr = self.sock.recvfrom(4096)
            except socket.timeout:
                continue
            env = json.loads(data.decode())
            if env.get("type") == "screen" and env["payload"].get("sessionId") == sid:
                ack = envelope("ack", {"ackFor": env["id"]})
                self.sock.sendto(json.dumps(ack).encode(), addr)
                p = env["payload"]
                self.log("phone-screen", json.dumps(p, ensure_ascii=False))
                if p.get("op") == "accept":
                    self.log("NOTE", "sharer 模式下 accept 应由本机（夹具）发出；收到手机 accept 说明角色反了")
                # 等 listener 泵完
        self.stop.set()

    def run_sharer_full(self):
        """手机是 Viewer 时，邀请是手机发来的；本模式 = 收 request → 回 accept → 泵流。"""
        self.log("mode", "sharer-inbound（手机请求看我们的屏：验手机 Viewer 收流渲染）")
        deadline = time.time() + self.secs
        sid = None
        while time.time() < deadline and sid is None:
            self.sock.settimeout(1.0)
            try:
                data, addr = self.sock.recvfrom(4096)
            except socket.timeout:
                continue
            env = json.loads(data.decode())
            self.handle(data, addr)
            if env.get("type") == "screen" and env["payload"].get("op") == "request":
                sid = env["payload"]["sessionId"]
        if not sid:
            self.log("FAILED", f"{self.secs}s 内没有收到手机的 screen.request")
            return
        token = uuid.uuid4().hex
        self.send_screen({"op": "accept", "sessionId": sid, "token": token})
        self.log("accept-sent", sid)
        frames = self.pump_sharer(sid, token)
        self.log("result", "PASS" if frames >= 20 else "PARTIAL" if frames else "FAIL", "frames", frames)

    def run_watch(self):
        self.log("mode", "watch（只看对端 caps）")
        if self.handshake():
            caps = self.phone_profile.get("caps", [])
            self.log("caps", json.dumps(caps))
            has = {"rv1": "rv1" in caps, "rvs1": "rvs1" in caps}
            self.log("caps-check", has)
        else:
            self.log("FAILED", "15s 内没握上手（手机节点没在线/不在同网段）")

    def run(self):
        if self.mode != "watch":
            if not self.handshake():
                self.log("FAILED", "15s 内没握上手")
                return
            self.log("caps", json.dumps(self.phone_profile.get("caps", [])))
            # 后台继续收发：ACK 对端的 screen 帧由 handle 负责
            threading.Thread(target=self.listen_loop, daemon=True).start()
        if self.mode == "viewer":
            self.run_viewer()
        elif self.mode == "sharer":
            self.run_sharer_full()
        elif self.mode == "watch":
            self.run_watch()

    def listen_loop(self):
        self.sock.settimeout(0.5)
        while not self.stop.is_set():
            try:
                data, addr = self.sock.recvfrom(4096)
                self.handle(data, addr)
            except socket.timeout:
                continue
            except OSError:
                break


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["viewer", "sharer", "watch"])
    ap.add_argument("--phone", required=True, help="手机 IP（默认端口 17878）")
    ap.add_argument("--host-ip", default="192.168.2.82", help="本机 LAN IP，进 entry 的 host 字段")
    ap.add_argument("--secs", type=int, default=90)
    a = ap.parse_args()
    global FIXTURE_HOST
    FIXTURE_HOST = a.host_ip
    ip, _, port = a.phone.partition(":")
    f = Fixture(ip, a.mode, a.secs, int(port or UDP_PORT))
    f.run()


if __name__ == "__main__":
    main()
