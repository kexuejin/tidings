#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""消息 e2e 真机夹具：短文本(UDP) / 长文本自动切 TCP / 离线队列补发。

覆盖 ACCEPTANCE.md 的 A5 / A6 / A12（C2 的文本+长文本双向往返包含在内）。
夹具自己装成"那台电脑"：UDP 握手、收消息、对每条可靠帧回 ACK，必要时装死
（`quiet`）来模拟对端离线；长文本那条走 `profile.tcpPort` 上的 TCP 分帧通道。

    # 不插手机也能确认自己没写崩：
    python3 scripts/e2e/e2e_messaging.py --dry-run

    # 真机：
    python3 scripts/e2e/e2e_messaging.py --phone 192.168.2.22 --host-ip 192.168.2.82

    # 只测网络层，不动 UI（不注入按键、不点按）：
    python3 scripts/e2e/e2e_messaging.py --skip-ui

退出码 0=PASS / 1=FAIL / 2=用法或环境错误；结尾恰好一行 `result ...`。
"""

import json
import os
import socket
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay_e2e as R  # noqa: E402


# 手机当发送端时的长文本**下界**（字节）。
# 判据不是"文本超过 800 字节"，而是**编码后的整个信封超过 UDP_MAX_PAYLOAD=1200**：
# Messenger.sendUserMessage 比的是 `Codec.encodeToBytes(envelope).size > 1200`。
# 信封本身约 165 字节，所以文本 ≥1200B ⇒ 信封 ≥1365B ⇒ 必走 TCP（且 <4096 的文本硬上限）。
# 之所以只给下界而不是给固定串：`input text` 一趟要吞掉约 10%~15%（真机实测），
# 逐字对账只会得到假失败；夹具改成"灌到够长为上，然后断言**发出去的那份 == 框里那份**"。
PHONE_LONG_MIN_BYTES = 1200
# 夹具当发送端的长文本：同样要保证信封 > 1200 字节（1400 字符 ⇒ 信封约 1565 字节）。
FIXTURE_LONG_LEN = 1400


def _budget(args, cap):
    """把 --secs 当成全局预算：返回不超过剩余预算的超时。"""
    return max(1.0, min(cap, float(args.secs)))


def phase(title):
    print("\n== %s ==" % title, flush=True)


def run(args):
    # ---------------------------------------------------------------- 本地自检
    short_marker = R.uniq_marker("a5")
    long_tail = R.uniq_marker("zt")
    long_text = R.long_text(FIXTURE_LONG_LEN, tail_marker=long_tail)
    phone_short = R.uniq_marker("c2")
    nick = "E2E" + R.uniq_marker("", 4)
    phase("本地自检（不碰设备）")
    short_env, long_env = R.local_self_checks(
        short_marker, long_text, nick=nick, tcp_port=args.tcp_port, host=args.host_ip)
    R.log("fixture short_text=%r env=%dB" % (short_marker, len(R.encode(short_env))))
    R.log("fixture long_text=%dB env=%dB tail=%r" % (
        R.utf8_len(long_text), len(R.encode(long_env)), long_tail))
    R.log("phone   long_text≥%dB（走 UI 注入，用来验手机侧的 TCP 回退）" % PHONE_LONG_MIN_BYTES)

    if args.dry_run:
        print("\n[dry-run] 不碰网络、不碰设备：跳过 adb 与握手/收发。", flush=True)
        print("[dry-run] 真机将执行：握手 → 夹具发短文本(UDP)/长文本(TCP) → "
              "手机 UI 发短文本/长文本 → 离线入队 3 条并补发 → 杀进程验离线。", flush=True)
        return 0

    # ---------------------------------------------------------------- 环境
    phase("设备与环境")
    R.require_device()
    pid = R.app_pid()
    R.log("com.kapp.tidings pid=%s" % (pid or "(not running)"))
    if not pid:
        R.log("com.kapp.tidings 没在跑，先拉起来")
        R.ensure_app_foreground()

    # ---------------------------------------------------------------- 起服务
    phase("起对端（UDP + TCP）")
    tcp = R.TcpFrameServer(port=args.tcp_port).start()
    R.log("TCP 监听 :%d（写进夹具 profile.tcpPort）" % args.tcp_port)
    peer = R.UdpPeer(args.phone_ip, args.phone_udp, nick=nick,
                     tcp_port=args.tcp_port, host=args.host_ip).start()
    R.log("UDP 绑定 :%d；夹具 nodeId=%s nick=%s" % (peer.port, R.FIXTURE_NODE, nick))

    rc = 0
    try:
        rc = _body(args, peer, tcp, nick, short_marker, long_text, long_tail, phone_short)
    finally:
        peer.stop()
        tcp.stop()
    return rc


def _dump_tcp(tcp, title):
    """把 TCP 服务端收到的**所有**帧打进 EVID（不只打匹配上的那条）。

    真出问题时，"哪一帧到了、长什么样"比"匹配到没有"有用得多。
    """
    with tcp._lock:
        got = list(tcp.frames_in)
    R.log("%s：TCP 共收到 %d 帧" % (title, len(got)))
    for ts, addr, obj in got:
        env = obj.get("envelope") or {}
        body = (env.get("payload") or {}).get("text")
        R.log("  tcp< %s from=%s env.type=%s env.id=%s payload.kind=%s text=%s" % (
            obj.get("type"), addr[0], env.get("type"), str(env.get("id"))[:8],
            (env.get("payload") or {}).get("kind"),
            ("%dB %r" % (R.utf8_len(body), body[:24] + "…")) if body else "-"))


def _body(args, peer, tcp, nick, short_marker, long_text, long_tail, phone_short):
    # ---------------------------------------------------------------- 握手
    phase("握手（定向 entry，固定 1s 回 alive）")
    prof = peer.handshake(secs=_budget(args, 20), probe=True)
    if not R.check("handshake-alive", prof is not None,
                   "手机 alive 里的 profile" + ("" if prof else "：%ds 内没握上手" % min(args.secs, 20))):
        R.log("提示：整帧被拒最常见的原因是 profile.ver 超过 16 字符或 host 为空")
        return None
    phone_caps = prof.get("caps") or []
    R.log("手机 profile nick=%r platform=%s tcpPort=%s caps=%s ver=%r" % (
        prof.get("nick"), prof.get("platform"), prof.get("tcpPort"),
        ",".join(phone_caps), prof.get("ver")))
    R.check("handshake-phone-declares-text-cap", R.CAP_MOBILE_TEXT in phone_caps,
            "caps=%s（没有 %s 就收发不了文本）" % (",".join(phone_caps), R.CAP_MOBILE_TEXT))
    phone_tcp_port = int(prof.get("tcpPort") or R.TCP_PORT_DEFAULT)
    R.check("handshake-phone-tcp-port", 0 < phone_tcp_port < 65536,
            "手机 tcpPort=%d（长文本要连它）" % phone_tcp_port)

    # ------------------------------------------- A5 收：夹具 → 手机，短文本走 UDP
    phase("A5 短文本（夹具 → 手机，UDP）")
    env = peer.env(R.T_MSG, {"kind": R.K_TEXT, "text": short_marker})
    R.log("UDP → 手机 entry 走 17878；本帧 %dB" % len(R.encode(env)))
    peer.send(env)
    t0 = time.time()
    ack = peer.wait_ack(env["id"], 8.0)
    R.check("a5-recv-short-text-acked-udp", ack is not None,
            "ackFor=%s in %.2fs（等不到 ACK 手机会重传并入队）" % (
                env["id"][:8], time.time() - t0 if ack else -1))

    # ------------------------------------------- A5 收：夹具 → 手机，长文本走 TCP
    phase("A5 长文本（夹具 → 手机，TCP 分帧）")
    lenv = peer.env(R.T_MSG, {"kind": R.K_TEXT, "text": long_text})
    R.log("信封 %dB > UDP_MAX_PAYLOAD=%d ⇒ 走 TCP；连 %s:%d" % (
        len(R.encode(lenv)), R.UDP_MAX_PAYLOAD, args.phone_ip, phone_tcp_port))
    tcp_ack = None
    try:
        conn = socket.create_connection((args.phone_ip, phone_tcp_port), timeout=8.0)
        try:
            conn.settimeout(R.TCP_ACK_TIMEOUT_MS / 1000.0 + 2.0)
            conn.sendall(R.tcp_msg_frame(lenv))
            R.log("TCP → {\"type\":\"msg\",\"envelope\":…}（裸信封会被判非法帧）")
            t0 = time.time()
            frame = R.read_tcp_frame(conn, time.monotonic() + R.TCP_ACK_TIMEOUT_MS / 1000.0 + 1.0)
            tcp_ack = frame
            R.log("TCP ← %s（%.2fs）" % (json.dumps(frame, ensure_ascii=False)[:120],
                                        time.time() - t0))
        finally:
            conn.close()
    except OSError as e:
        R.log("TCP 连接失败：%s" % e)
    ok = bool(tcp_ack and tcp_ack.get("type") == "msg-ack"
              and tcp_ack.get("ackFor") == lenv["id"])
    R.check("a5-recv-long-text-acked-tcp", ok,
            "msg-ack ackFor=%s" % (lenv["id"][:8]) if ok
            else "拿到 %r（等不到 msg-ack，手机侧只会显示「已排队」）" % (tcp_ack,))

    # ------------------------------------------- 手机 UI：两条都真的落进了会话
    if not args.skip_ui:
        phase("A5 收：真机 UI 上确认两条都到了（%s）" % nick)
        R.ensure_app_foreground()
        opened = R.open_chat(nick)
        R.check("ui-open-chat-with-fixture", opened,
                "会话列表里点开 %r" % nick if opened else "会话列表里没找到 %r" % nick)
        if opened:
            # 长文本是后发的 ⇒ 它在底部、在可视区里；短文本已经被顶出屏幕。
            # 会话页只组合可视区内的消息，所以"找不到短文本"通常是"没滚上去"。
            nodes = R.ui_all()
            R.check("a5-phone-ui-shows-long-text-tail", R.ui_has(long_tail, nodes),
                    "tail=%r（完整送达才可能出现；AC+TCP 分帧任意一处截断都不会有它）" % long_tail)
            if not R.ui_has(short_marker, nodes):
                R.log("可视区里没有 %r，往上滚一屏再看" % short_marker)
                R.chat_scroll_up()
                nodes = R.ui_all()
            R.check("a5-phone-ui-shows-short-text", R.ui_has(short_marker, nodes),
                    "聊天页可见 %r" % short_marker)

        # --------------------------------------- C2 发：手机 → 夹具，短文本走 UDP
        phase("C2 短文本（手机 → 夹具，UDP）")
        # `input text` 会随机吞字符（真机实测 8 字符约 4 成概率少一个），
        # 所以 chat_send 回读校验 + 清空重打，并把**实际落框**的那串还回来；
        # 后面一律用 landed 去匹配收到的帧 —— 用"我打算打的字符串"匹配会假失败。
        landed = R.chat_send(phone_short)
        R.check("ui-compose-and-send-short", landed == phone_short,
                "输入框实际落字 %r（想要 %r）并点了发送键" % (landed, phone_short))
        got = peer.wait(lambda e: e.get("type") == R.T_MSG
                        and (e.get("payload") or {}).get("text") == (landed or phone_short),
                        15.0)
        R.check("c2-phone-short-text-received-udp", got is not None,
                "收到 kind=%r text=%r" % (
                    (got or {}).get("payload", {}).get("kind"), landed))
        R.check("c2-phone-short-text-kind-and-no-resend",
                bool(got) and (got["payload"].get("kind") == R.K_TEXT
                               and not got["payload"].get("resend")),
                "首次发送不该带 resend")

        # --------------------------------------- C2 发：手机 → 夹具，长文本走 TCP
        phase("C2 长文本（手机 → 夹具 ≥%dB ⇒ 手机侧自动切 TCP）" % PHONE_LONG_MIN_BYTES)
        t0 = time.time()
        landed_long = R.chat_send_long(PHONE_LONG_MIN_BYTES)
        R.check("ui-compose-and-send-long", bool(landed_long),
                "输入框灌到 %dB 并点了发送键（%.1fs）"
                % (R.utf8_len(landed_long or ""), time.time() - t0))
        R.check("c2-phone-long-text-over-udp-budget",
                R.utf8_len(landed_long or "") >= PHONE_LONG_MIN_BYTES,
                "%dB ≥ %dB ⇒ 信封必超 UDP_MAX_PAYLOAD=%d，只能走 TCP"
                % (R.utf8_len(landed_long or ""), PHONE_LONG_MIN_BYTES, R.UDP_MAX_PAYLOAD))
        t1 = time.time()
        env_l, frame_l = tcp.wait_msg(text=landed_long, timeout=_budget(args, 30))
        # 完整性：对端收到的必须和手机输入框里当时那份**逐字节相同**。
        recv_text = ((env_l or {}).get("payload") or {}).get("text")
        R.check("c2-phone-long-text-intact",
                recv_text is not None and recv_text == landed_long,
                "收到 %dB / 框里 %dB，逐字节%s" % (
                    R.utf8_len(recv_text or ""), R.utf8_len(landed_long or ""),
                    "一致" if recv_text == landed_long else "不一致"))
        R.log("TCP 收到长文本帧（%.1fs，首帧 %.1fs）" % (time.time() - t0, t1 - t0))
        _dump_tcp(tcp, "C2 阶段结束")
        R.check("c2-phone-long-text-received-tcp", env_l is not None,
                "信封 type=%s text=%dB" % ((env_l or {}).get("type"),
                                           R.utf8_len(landed_long or "")))
        R.check("c2-tcp-frame-shape-is-msg-envelope",
                bool(frame_l) and frame_l.get("type") == "msg"
                and isinstance(frame_l.get("envelope"), dict)
                and frame_l["envelope"].get("v") == R.PROTOCOL_VERSION,
                "帧 = {\"type\":\"msg\",\"envelope\":{…}}，信封 v=1")

        # --------------------------------------- A6 离线入队 → 上线补发
        phase("A6 离线队列：装死 → UI 发 3 条 → 重新上线补发")
        peer.send_exit()
        time.sleep(1.0)
        peer.quiet = True                      # 不回 ACK，模拟对端离线/不可达
        tcp.quiet = True                       # TCP 回退路径也要一起装死
        R.log("夹具已发 exit(payload={}) 并进入 quiet（UDP 不回 ACK、TCP 不回 msg-ack）")
        queued = []
        for i in (1, 2, 3):
            q = R.uniq_marker("q%d" % i, 4)
            got_q = R.chat_send(q)             # 回读校验：必须一字不差地进框再发
            R.check("a6-compose-queued-%d" % i, got_q == q,
                    "第 %d 条落框 %r（想要 %r）" % (i, got_q, q))
            queued.append(got_q or q)
            time.sleep(0.8)
        R.log("UI 发了 3 条：%s" % ",".join(queued))
        time.sleep(3.0)
        nodes = R.ui_all()
        R.log("离线态聊天页可见文本：%s" % " | ".join(R.ui_texts(nodes)[:16]))
        R.log("离线横幅(离线)=%s" % R.ui_has("离线", nodes))
        R.log("排队标记(排队/等待)=%s" % (R.ui_has("排队", nodes) or R.ui_has("等待", nodes)))
        # 离线期间对端不该收到任何一条（收到一条就说明"离线"没真的生效，本轮 A6 无效）
        leaked = [e for (_, _, e) in peer.frames(R.T_MSG)
                  if (e.get("payload") or {}).get("text") in queued]
        R.check("a6-nothing-delivered-while-offline", not leaked,
                "quiet 期间对端收到 %d/3 条（应为 0）" % len(leaked))

        t_reonline = time.time()
        peer.quiet = False
        tcp.quiet = False
        prof2 = peer.handshake(secs=_budget(args, 15), probe=True)
        R.check("a6-reonline-handshake", prof2 is not None, "重新 entry 后手机回 alive")
        # 补发的唯一触发点是 PeerEvent.Online（Messenger.start 里挂的 registry listener）
        resent = []
        deadline = time.monotonic() + _budget(args, 30)
        while time.monotonic() < deadline and len(resent) < 3:
            resent = [e for (_, _, e) in peer.frames(R.T_MSG, since=t_reonline)
                      if (e.get("payload") or {}).get("text") in queued]
            if len(resent) < 3:
                time.sleep(0.25)
        order = [(e.get("payload") or {}).get("text") for e in resent]
        R.check("a6-offline-queue-flushed-3", len(resent) == 3,
                "收到 %d/3 条：%s" % (len(resent), ",".join(order)))
        R.check("a6-offline-queue-order-preserved", order == queued,
                "补发顺序 %s（入队顺序 %s；listByPeer 按 created 升序、首条未 ACK 就 return）"
                % (",".join(order), ",".join(queued)))
        R.check("a6-resend-flag-set",
                bool(resent) and all((e.get("payload") or {}).get("resend") is True for e in resent),
                "补发帧带 resend=true（withResendFlag 只在 text/group-text 上加）")
        R.check("a6-resend-keeps-envelope-id-shape",
                all(isinstance(e.get("id"), str) and len(e.get("id")) > 0 for e in resent),
                "重放不重建信封：id 沿用原队列项")

    # ---------------------------------------------------------------- A12
    phase("A12 杀进程后的离线判定")
    exit_seen = len([1 for (_, _, e) in peer.inbox if e.get("type") == R.T_EXIT])
    R.log("本轮收到的 phone exit 告别帧数=%d（手机端没有生命周期钩子，实际发不出，"
          "90s 存在性超时才是它的真实路径 —— 已知现状不是缺陷）" % exit_seen)
    R.adb("shell", "am", "force-stop", R.PKG)
    time.sleep(2.0)
    R.log("已 am force-stop %s" % R.PKG)
    t_kill = time.time()
    peer.send_entry(probe=True)
    time.sleep(5.0)
    alive = [e for (_, _, e) in peer.frames(R.T_ALIVE, since=t_kill)]
    R.check("a12-no-alive-after-force-stop", not alive,
            "杀进程后 %ds 内无 alive（应靠 offlineAfter=90s 的存在性超时判离线）" % 5
            if not alive else "杀进程后竟然还回了 %d 条 alive" % len(alive))
    restored = R.ensure_app_foreground(timeout=15)
    R.check("a12-app-restored-after-kill", bool(restored), "前台恢复为 %s" % restored)
    return None


def extra_args(ap):
    ap.add_argument("--tcp-port", type=int, default=R.TCP_PORT_DEFAULT,
                    help="夹具 TCP 监听端口（写进 profile.tcpPort），默认 %(default)s；"
                         "17879 被其他进程占用时换一个")
    ap.add_argument("--skip-ui", action="store_true",
                    help="只测网络层：不 dump UI、不注入按键（手机当发送端那几条会被跳过）")
    ap.set_defaults(secs=300)


if __name__ == "__main__":
    sys.exit(R.main_wrapper(run, description=__doc__, extra_args=extra_args))
