#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""scripts/e2e/run_all.py —— 全功能 E2E 夹具的统一入口。

契约：docs/plans/2026-10-09-relay-e2e-fixture-program.md §1
语义：逐个跑 `scripts/e2e/e2e_*.py`，汇总退出码。README §3.2 里那段 shell
循环就是它的临时替身，本文件与那段循环**语义一致**，只是多了汇总表和超时。

退出码约定（与单个夹具对齐，见 relay_e2e.py 顶部）：
  0  全部 PASS
  1  至少一个夹具 FAIL（功能问题）
  2  用法 / 环境问题：没设备、对端忙、夹具超时、夹具自己崩了

退出码 2 不是功能失败 —— 这正是「门禁全绿 ≠ 功能可用」那条教训的另一面：
环境没准备好时报 FAIL 会让人去查一个不存在的 bug。

**只读**：本文件只调用既有夹具，不写任何被测状态，也不改既有文件。
"""

import argparse
import os
import re
import subprocess
import sys
import threading
import time

E2E_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(E2E_DIR))
LIVENESS = os.path.join(REPO_ROOT, "scripts", "relay-liveness.sh")

DEFAULT_PHONE = "192.168.2.22"
DEFAULT_HOST_IP = "192.168.2.82"
# 套件的端口**基准**（这里不 import 共享库：run_all 只跑子进程）。每个夹具再往下各走
# 2 个端口（`--tcp-port` 与 `--tcp-port-b`），所以任何两个夹具都不会撞在一起。
#
# 为什么不从协议默认值 17879 起步：避免与本地常驻进程占用 17878/UDP 与
# 17879/TCP 产生冲突。基准若取 17879，第一个夹具（messaging）就会
# EADDRINUSE，而 run_all 把它记成 env-error。
DEFAULT_TCP_PORT = 17880

# 派活顺序（矩阵 §9）：先把「共享库 + 消息」这条地基跑通，再铺其余。
# 没在表里的夹具按名字排在后面 —— 新增夹具不该要求同时改这个文件。
PREFERRED_ORDER = [
    "messaging",
    "discovery",
    "files",
    "media",
    "group",
    "share",
    "agent",
    "search",
    "screen",
]

# 对端忙时的等待：`relay-liveness.sh` 返回 1 = 对面正在写。
LIVENESS_IDLE = 0
LIVENESS_BUSY = 1


def feature_of(path):
    """`e2e_messaging.py` → `messaging`。"""
    stem = os.path.basename(path)[len("e2e_"):] if os.path.basename(path).startswith("e2e_") else os.path.basename(path)
    return stem[:-3] if stem.endswith(".py") else stem


def discover():
    """按派活顺序返回夹具路径。只认 `e2e_*.py`，`relay_e2e.py`（共享库）与
    `run_all.py` 自己都不会被当成夹具。"""
    names = sorted(
        f for f in os.listdir(E2E_DIR)
        if f.startswith("e2e_") and f.endswith(".py")
    )
    def key(f):
        feat = feature_of(f)
        return (PREFERRED_ORDER.index(feat) if feat in PREFERRED_ORDER else len(PREFERRED_ORDER), feat)
    return [os.path.join(E2E_DIR, f) for f in sorted(names, key=key)]


def run_liveness(wait_secs):
    """跑 `scripts/relay-liveness.sh --quiet`。

    返回 (ok, detail)。对端忙时轮询到 wait_secs 为止 —— 外部节点
    常占 UDP 17878 / TCP 17879，若冲突会导致握手失败。
    """
    if not os.path.exists(LIVENESS):
        return True, "liveness 脚本不存在，跳过（%s）" % LIVENESS
    deadline = time.time() + max(0, wait_secs)
    while True:
        try:
            p = subprocess.run(
                ["bash", LIVENESS, "--quiet"],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, timeout=60)
        except subprocess.TimeoutExpired:
            return False, "relay-liveness.sh 自己超时"
        except OSError as e:
            return False, "跑不了 relay-liveness.sh：%s" % e
        rc = p.returncode
        out = (p.stdout or "").strip()
        if rc == LIVENESS_IDLE:
            return True, out or "对面空闲"
        if rc == LIVENESS_BUSY:
            if time.time() >= deadline:
                return False, "对端正忙（%s）。等它停，或加 --wait-busy" % (out or "近 600s 有写入")
            time.sleep(5)
            continue
        return False, "relay-liveness.sh 退出码 %d（用法错误）：%s" % (rc, out)


def parse_result(line):
    """解析结尾那行契约行：

        result PASS <总检查数>
        result FAIL <总检查数> failed=<逗号分隔>

    返回 (verdict, detail)；不是 result 行返回 (None, "")。
    """
    parts = line.split()
    if len(parts) < 3 or parts[0] != "result":
        return None, ""
    return parts[1], " ".join(parts[2:])


_HELP_CACHE = {}


def fixture_flags(path):
    """该夹具 `--help` 里出现的选项集合（结果缓存）。

    契约只钉死了 --phone / --host-ip / --secs / --dry-run 四个；其余选项
    （--tcp-port、--skip-ui…）各夹具自行增删。盲目透传会让**不认**的那个夹具按
    「用法错误」exit 2 —— 那是把环境问题伪装成夹具缺陷，正是本文件开头那条
    「退出码 2 不是功能失败」要防的事。所以先问它认什么，再只发它认的。

    夹具崩了 / 没有 --help ⇒ 返回空集，一个可选选项都不转发，退回契约里的最小
    命令行。这是可接受的降级：夹具仍会跑，只是拿不到额外的环境参数。
    """
    if path not in _HELP_CACHE:
        try:
            proc = subprocess.run(
                [sys.executable, path, "--help"],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, timeout=60, cwd=REPO_ROOT)
            out = proc.stdout or ""
        except (OSError, subprocess.SubprocessError):
            out = ""
        _HELP_CACHE[path] = set(re.findall(r"--[a-z][a-z0-9-]*", out))
    return _HELP_CACHE[path]


def run_fixture(path, args, timeout, port=None, port_b=None):
    """跑一个夹具，把它的输出原样透传，并捞回结尾的 result 行。"""
    cmd = [sys.executable, path,
           "--phone", args.phone,
           "--host-ip", args.host_ip,
           "--secs", str(args.secs)]
    if args.dry_run:
        cmd.append("--dry-run")

    wanted = []
    if port:
        wanted.append(("--tcp-port", str(port)))
    if port_b:
        wanted.append(("--tcp-port-b", str(port_b)))
    if args.skip_ui:
        wanted.append(("--skip-ui", None))
    skipped = []
    if wanted:
        known = fixture_flags(path)
        for flag, value in wanted:
            if flag not in known:
                skipped.append(flag)
                continue
            cmd.append(flag)
            if value is not None:
                cmd.append(value)
        if skipped:
            print("    [skip] %s 不认 %s，未转发"
                  % (os.path.basename(path), " ".join(skipped)), flush=True)

    t0 = time.time()
    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1, cwd=REPO_ROOT)
    timed_out = {"v": False}

    def watchdog():
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out["v"] = True
            try:
                proc.kill()
            except OSError:
                pass

    th = threading.Thread(target=watchdog, daemon=True)
    th.start()

    verdict, detail = None, ""
    for raw in proc.stdout:
        line = raw.rstrip("\n")
        print("    " + line, flush=True)
        if line.startswith("result "):
            verdict, detail = parse_result(line)
    rc = proc.wait()
    th.join(timeout=1)

    return {
        "rc": rc,
        "verdict": verdict,
        "detail": detail,
        "secs": time.time() - t0,
        "timed_out": timed_out["v"],
    }


def main():
    ap = argparse.ArgumentParser(
        description="逐个跑 scripts/e2e/e2e_*.py 并汇总",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--phone", default=DEFAULT_PHONE, help="手机 IP[:UDP端口]，默认 %(default)s")
    ap.add_argument("--host-ip", default=DEFAULT_HOST_IP, help="本机 LAN IP，默认 %(default)s")
    ap.add_argument("--secs", type=int, default=90, help="传给每个夹具的预算秒数，默认 %(default)s")
    ap.add_argument("--timeout", type=int, default=900,
                    help="单个夹具的墙钟上限（超时按环境错误 2 计），默认 %(default)s")
    ap.add_argument("--wait-busy", type=int, default=0,
                    help="对端忙时最多等多少秒，默认不等待")
    ap.add_argument("--only", action="append", metavar="FEATURE",
                    help="只跑指定功能（可重复），例如 --only messaging --only screen")
    ap.add_argument("--skip-liveness", action="store_true",
                    help="跳过对端空闲前置检查（确认对端未在运行再用）")
    ap.add_argument("--dry-run", action="store_true",
                    help="每个夹具都加 --dry-run：不碰网络、不碰设备")
    ap.add_argument("--tcp-port", type=int, default=None,
                    help="第 1 个夹具的对端 TCP 端口（默认从 %d 起，往后每个夹具 +2）。"
                         "17878/17879 被占用时才需要改" % DEFAULT_TCP_PORT)
    ap.add_argument("--skip-ui", action="store_true",
                    help="转发给认得这个选项的夹具：只跑网络层，不往设备注入按键")
    ap.add_argument("--list", action="store_true", help="只列出会跑的夹具顺序")
    args = ap.parse_args()

    fixtures = discover()
    if args.only:
        wanted = set(args.only)
        fixtures = [f for f in fixtures if feature_of(f) in wanted]
        missing = wanted - {feature_of(f) for f in fixtures}
        if missing:
            print("env-error: 没有这些夹具：%s" % ",".join(sorted(missing)), file=sys.stderr)
            return 2
    if not fixtures:
        print("env-error: scripts/e2e/ 下没有 e2e_*.py", file=sys.stderr)
        return 2

    if args.list:
        for f in fixtures:
            print(feature_of(f))
        return 0

    mode = "dry-run（不碰网络与设备）" if args.dry_run else "真机"
    print("== run_all：%d 个夹具，模式 %s ==" % (len(fixtures), mode), flush=True)

    if not args.dry_run and not args.skip_liveness:
        print("[PRECOND] relay-liveness.sh --quiet", flush=True)
        ok, detail = run_liveness(args.wait_busy)
        if not ok:
            print("env-error: %s" % detail, file=sys.stderr)
            return 2
        print("[PRECOND] 对端空闲：%s" % detail, flush=True)
    elif args.skip_liveness and not args.dry_run:
        print("[PRECOND] 已按要求跳过对端空闲检查", flush=True)

    rows = []
    # 每个夹具一组**自己的**端口：`--tcp-port` 与（认得的）`--tcp-port-b` 都从基准往上
    # 各走 2 个。2026-10-09 的 run1 里 group 就是被上一个夹具留在 17880 上的监听挡死的
    # ——`env-error: TCP 0.0.0.0:17880 绑不上（[Errno 48] Address already in use）`，
    # 而那是**环境冲突**（退出码 2），不该混进功能失败里。
    base_port = args.tcp_port if args.tcp_port else DEFAULT_TCP_PORT
    for i, path in enumerate(fixtures):
        feat = feature_of(path)
        port = base_port + 2 * i
        port_b = port + 1
        print("\n=== %s (%s) ===" % (feat, os.path.basename(path)), flush=True)
        print("    [ports] 本轮给它的对端 TCP 端口基准 %d（认得 --tcp-port-b 的给 %d）"
              % (port, port_b), flush=True)
        r = run_fixture(path, args, args.timeout, port=port, port_b=port_b)
        rows.append((feat, r))
        if r["timed_out"]:
            print("--- %s 超时（%ds）" % (feat, args.timeout), flush=True)
        else:
            print("--- %s 退出码 %d，%.1fs" % (feat, r["rc"], r["secs"]), flush=True)

    print("\n== 汇总 ==")
    print("%-12s %-8s %-7s %s" % ("夹具", "结论", "退出码", "用时"))
    bad, env_bad = set(), set()
    for feat, r in rows:
        if r["timed_out"]:
            verdict = "TIMEOUT"
        elif r["rc"] == 2:
            verdict = "ENVERR"
        elif args.dry_run:
            # dry-run 绝不产出 PASS：夹具只跑了参数/环境自检，没碰设备、没碰网络。
            # 所以这一支必须排在 result 行之前 —— 有夹具在 dry-run 里照样打 result
            # （e2e_discovery.py 就是），原来的顺序会让一次「什么都没验」的干跑在
            # 汇总里显示成 PASS，并让末尾打印「9/9 PASS」。2026-10-09 实测如此。
            verdict = "DRYRUN" if r["rc"] == 0 else "FAIL?"
        elif r["verdict"]:
            # 契约 result 行在手：以它为准，但退出码非 0 也算失败 ——
            # 静默分歧（说 PASS 却退出 1）比直接失败更坑，两者都不放过。
            verdict = r["verdict"]
        elif r["rc"] == 0:
            verdict = "PASS?"       # 退出 0 但没打 result 行：算过，但看得见
        else:
            verdict = "FAIL?"

        if verdict in ("TIMEOUT", "ENVERR"):
            env_bad.add(feat)
        elif verdict in ("FAIL", "FAIL?") or r["rc"] != 0:
            bad.add(feat)

        detail = ("  " + r["detail"]) if r["detail"] and verdict not in ("PASS",) else ""
        print("%-12s %-8s %-7d %.1fs%s" % (feat, verdict, r["rc"], r["secs"], detail))

    passed = len(rows) - len(bad | env_bad)
    if args.dry_run:
        # 干跑不许出现「N/N PASS」这种会被截图引用的字样：它一个功能都没验。
        print("\n%d/%d 只跑了本地自检（dry-run：不碰网络、不碰设备，"
              "不代表任何功能通过）" % (passed, len(rows)))
    else:
        print("\n%d/%d PASS" % (passed, len(rows)))
    if env_bad:
        print("env-error: %s" % ",".join(env_bad), file=sys.stderr)
        return 2
    if bad:
        print("failed: %s" % ",".join(bad), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
