#!/usr/bin/env python3
"""环境依赖测试普查：把「会因机器不同而绿/红」的测试找出来并分类。

为什么要有它：这类缺陷在 CI 上表现为"偶发红"，在别人机器上表现为"我这绿了"，
两者都不会指向真正的根因。判据必须能区分 A/B/C/D 四类（见 --help 的分类口径），
而不是"用了 NetworkInterface 就算环境依赖"——临时目录、withTimeout 都是合法用法。

用法：
  python3 tools/env-test-scan.py                 # 全仓普查，输出 Markdown
  python3 tools/env-test-scan.py --module core   # 只看一个模块
  python3 tools/env-test-scan.py --check         # 只报 A/B 两类（会让测试说谎的）
  python3 tools/env-test-scan.py --json          # 机器可读
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys

TEST_GLOBS = ("Test",)

# 断言上下文里出现这些 = 结论依赖机器状态（A 类）
ENV_IN_ASSERT = [
    re.compile(r"NetworkInterface|getNetworkInterfaces"),
    re.compile(r"InetAddress\.getByName|getAllByName|InetAddress\.getLocalHost"),
    re.compile(r'System\.getProperty\("os\.name"\)|System\.getenv'),
    re.compile(r'user\.home|user\.dir'),
    re.compile(r"255\.255\.255\.255"),
    re.compile(r"currentTimeMillis\(\)|Instant\.now|LocalDate\.now|ZoneId\.systemDefault"),
]

# 真环境依赖（D 类）：需要 socket/进程才能测
REAL_ENV = [
    re.compile(r"ServerSocket|DatagramSocket|DatagramChannel|SelectorManager|aSocket\("),
    re.compile(r"ProcessBuilder|Runtime\.getRuntime"),
    re.compile(r"NetworkInterface|getNetworkInterfaces"),
]

# 时序赌输赢（B 类）：断言前用固定 delay 同步
DELAY = re.compile(r"\bdelay\(\s*(\d+)")
ASSERT = re.compile(r"\b(assertEquals|assertTrue|assertFalse|assertNull|assertNotNull|assertContains|assertContentEquals)\b")

# 可能不存在的事实（C 类）：直接读机器上"有没有"
MAYBE_ABSENT = [
    re.compile(r'File\("(/[^"]*)"\)'),
    re.compile(r'Files\.exists|Path\("/'),
]

BANNED_HINT = re.compile(r"TODO|FIXME")


def strip_comments(src: str) -> str:
    """去掉 // 与 /* */ 注释，但**保留字符串内容**（否则 https:// 会被腰斩）。

    为什么必须先剥：KDoc 里经常引用旧实现的代码片段（例如「原来这里用
    currentTimeMillis()」），不剥就会把注释当成活代码，制造误报。
    """
    out = []
    i = 0
    n = len(src)
    in_str = False
    in_block = False
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            if c == "\n":
                out.append("\n")
            i += 1
            continue
        if in_str:
            if c == "\\":
                out.append(c)
                if i + 1 < n:
                    out.append(src[i + 1])
                i += 2
                continue
            if c == "\"":
                in_str = False
            out.append(c)
            i += 1
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "\"":
            in_str = True
        out.append(c)
        i += 1
    return "".join(out)


def is_test_file(path: str) -> bool:
    base = os.path.basename(path)
    return (base.endswith("Test.kt") or base.endswith("Tests.kt")) and "/src/" in path


def scan_file(path: str) -> dict:
    src = strip_comments(open(path, encoding="utf-8").read())
    lines = src.splitlines()
    asserts = [i for i, ln in enumerate(lines) if ASSERT.search(ln)]
    signals = {"A": [], "B": [], "C": [], "D": []}

    # A：断言行附近 ±6 行出现环境取值
    for ai in asserts:
        window = "\n".join(lines[max(0, ai - 6): ai + 7])
        for pat in ENV_IN_ASSERT:
            m = pat.search(window)
            if m:
                signals["A"].append(f"L{ai + 1}: {m.group(0)}")
                break

    # B：断言之前 25 行内出现固定 delay
    for ai in asserts:
        for j in range(max(0, ai - 25), ai):
            m = DELAY.search(lines[j])
            if m and int(m.group(1)) >= 10:
                signals["B"].append(f"L{j + 1}: delay({m.group(1)}) → L{ai + 1}")
                break

    # C：读绝对路径 / 判断存在性
    for i, ln in enumerate(lines):
        for pat in MAYBE_ABSENT:
            m = pat.search(ln)
            if m:
                signals["C"].append(f"L{i + 1}: {m.group(0)[:40]}")
                break

    # D：真环境能力
    for i, ln in enumerate(lines):
        for pat in REAL_ENV:
            m = pat.search(ln)
            if m:
                signals["D"].append(f"L{i + 1}: {m.group(0)}")
                break

    return {
        "file": path,
        "module": path.split("/src/")[0],
        "counts": {k: len(v) for k, v in signals.items()},
        "signals": {k: v[:3] for k, v in signals.items()},
    }


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--module")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--check", action="store_true", help="只把 A/B 类非空视为问题（退出码 1）")
    ap.add_argument("--allowlist", default="tools/env-test-allowlist.txt",
                    help="已评审并接受的例外：每行 路径 | A/B | 理由（# 开头为注释）")
    args = ap.parse_args(argv[1:])

    allowed = {"A": {}, "B": {}}
    if os.path.exists(args.allowlist):
        for line in open(args.allowlist, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = [x.strip() for x in line.split("|")]
            if len(parts) < 2:
                continue
            path, cat = parts[0], parts[1].upper()
            if cat in allowed:
                allowed[cat][os.path.normpath(path)] = parts[2] if len(parts) > 2 else ""

    rows = []
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", ".agents")]
        for f in files:
            if not f.endswith(".kt"):
                continue
            p = os.path.normpath(os.path.join(root, f))
            if not is_test_file(p):
                continue
            if args.module and not p.startswith(args.module + "/"):
                continue
            rows.append(scan_file(p))

    for r in rows:
        for cat in ("A", "B"):
            if r["file"] in allowed[cat]:
                r["counts"][cat] = 0
                r["signals"][cat] = ["allowlisted: " + allowed[cat][r["file"]]]
    hard = [r for r in rows if r["counts"]["A"] or r["counts"]["B"]]
    if args.json:
        print(json.dumps({"total": len(rows), "hard": hard}, ensure_ascii=False, indent=1))
    else:
        print(f"扫描 {len(rows)} 个测试文件；A/B 类（会让测试说谎）{len(hard)} 个。")
        for r in sorted(hard, key=lambda x: -(x["counts"]["A"] * 2 + x["counts"]["B"])):
            print(f"  [{r['module']}] {r['file']}  A={r['counts']['A']} B={r['counts']['B']} C={r['counts']['C']} D={r['counts']['D']}")
            for cat in ("A", "B"):
                for s in r["signals"][cat]:
                    print(f"      {cat} {s}")
    if args.check and hard:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
