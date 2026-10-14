# SPDX-License-Identifier: GPL-3.0-only

#!/usr/bin/env python3
"""在两个 TCP 端点之间插一段「坏网络」，用来验跨网链路对弱网的耐受。

零依赖。macOS 上没有 `tc netem`，`pfctl` 的 dummynet 又要 root，
所以用一层 Python 转发代替：读一段、睡一段、写一段。

    probe ──► net-shim ──► agentNode ──► 桌面节点

**先读这一条再对数字**：这里的 `--delay-ms` 是**单向**延迟，
一轮往返是它的两倍。所以要 300ms RTT 就传 `--delay-ms 150`。

**关于丢包**：这里**注入不了真丢包**。真丢包发生在 TCP 重传里，
要模拟得有 raw socket（root + 越狱式折腾），而重传对应用层的表现就是「卡一会儿」，
所以本工具用 `--stall-ms`（周期性长停顿，等价于弱网/拥塞时的 bufferbloat）
去覆盖这一类症状。它验的是「应用能不能扛住卡顿」，不是「TCP 重传对不对」。

为什么要自己写而不是拿现成工具：弱网验收需要和 `ws-relay-probe.py` 串在同一条
链路上按同一套判读口径跑（字节数 + 摘要），现成工具给不了这个组合。
"""

import argparse
import random
import signal
import socket
import sys
import threading
import time

CHUNK = 65536


def install_signal_handlers():
    """把 SIGTERM 也当成「我要出统计了」。

    为什么必须加：后台起 shim 时（`nohup python3 tools/net-shim.py … &`，
    脚本里非交互 shell 没有 job control）bash 会把子进程的 SIGINT/SIGQUIT
    设成 SIG_IGN，于是 `kill -INT` 打过去毫无反应；等真正用得上的 SIGTERM 到达时，
    进程直接死掉，`finally` 里的 `--stats` 统计**一个字节都写不出来**。
    退出前唯一能拿到的量化证据就这么没了 —— 而「注了多少停顿」正是这条
    验收要判的东西。这里让 SIGTERM 抛 KeyboardInterrupt 走进同一条收尾路径。
    """

    def _to_keyboard_interrupt(signum, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, _to_keyboard_interrupt)


def pump(src, dst, name, delay_ms, jitter_ms, stall_ms, stall_prob, stats):
    """一个方向的转发。方向不对称也要能跑，所以两个方向各一个线程。"""
    try:
        while True:
            buf = src.recv(CHUNK)
            if not buf:
                break
            wait = delay_ms / 1000.0
            if jitter_ms:
                wait += random.uniform(0, jitter_ms / 1000.0)
            if stall_ms and random.random() < stall_prob:
                wait += stall_ms / 1000.0
                stats["stalls"] += 1
            if wait > 0:
                time.sleep(wait)
            dst.sendall(buf)
            stats["bytes"] += len(buf)
            stats["chunks"] += 1
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def serve_one(client, cfg, stats):
    upstream = socket.create_connection((cfg.host, cfg.port))
    upstream.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    threads = [
        threading.Thread(
            target=pump,
            args=(client, upstream, "客户端→上游", cfg.delay_ms, cfg.jitter_ms,
                  cfg.stall_ms, cfg.stall_prob, stats),
            daemon=True,
        ),
        threading.Thread(
            target=pump,
            args=(upstream, client, "上游→客户端", cfg.delay_ms, cfg.jitter_ms,
                  cfg.stall_ms, cfg.stall_prob, stats),
            daemon=True,
        ),
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    for s in (client, upstream):
        try:
            s.close()
        except OSError:
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="127.0.0.1:8899", help="本地监听地址")
    ap.add_argument("--target", required=True, help="真实目标 host:port")
    ap.add_argument("--delay-ms", type=float, default=0.0, help="**单向**固定延迟")
    ap.add_argument("--jitter-ms", type=float, default=0.0, help="额外的随机抖动上限")
    ap.add_argument("--stall-ms", type=float, default=0.0, help="偶发长停顿的时长")
    ap.add_argument("--stall-prob", type=float, default=0.0, help="每个数据块触发停顿的概率")
    ap.add_argument("--stats", help="结束时把这些统计写到这个文件")
    args = ap.parse_args()
    install_signal_handlers()

    host, _, port = args.target.rpartition(":")
    args.host, args.port = host, int(port)

    listen_host, _, listen_port = args.listen.rpartition(":")
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((listen_host, int(listen_port)))
    srv.listen(16)

    print("net-shim: 监听 %s → %s（单向 %gms + 抖动 %gms + 停顿 %gms@%.0f%%，RTT≈%gms）"
          % (args.listen, args.target, args.delay_ms, args.jitter_ms,
             args.stall_ms, args.stall_prob * 100, args.delay_ms * 2),
          flush=True)

    total = {"bytes": 0, "chunks": 0, "stalls": 0}
    t0 = time.time()
    try:
        while True:
            client, _ = srv.accept()
            # 单条连接炸掉不能带走整个 shim：否则一次客户端异常就把后续用例全废了，
            # 而且现象是「连接被重置」，会被误判成链路问题。
            try:
                serve_one(client, args, total)
            except Exception as exc:  # noqa: BLE001 — 诊断信息比吞掉更重要
                print("net-shim: 连接异常 %s" % exc, file=sys.stderr, flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        elapsed = max(0.001, time.time() - t0)
        line = "net-shim: 转发 %d 字节 / %d 块，触发停顿 %d 次，历时 %.1fs（%.1f KB/s）\n" % (
            total["bytes"], total["chunks"], total["stalls"], elapsed,
            total["bytes"] / elapsed / 1024.0,
        )
        print(line, end="", flush=True)
        if args.stats:
            with open(args.stats, "a", encoding="utf-8") as fh:
                fh.write(line)
        srv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())