#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""ARP-9 的测量工具：静默 `runCatching` 普查。

## 它是什么，以及它**不是**什么

这是**报告工具，不是门禁**。区别很实：

- 门禁问「有没有违规」，红了就得改；这个问「存量长什么样」，用来决定**要不要**动手。
- 如果把它接成门禁（「每处 runCatching 都要 onFailure」），第一天就会有三百多条
  待办，然后所有人学会无视它 —— 计划书 ARP-9 特意写了「防『永远红的守卫』」。

结论写死在 `docs/2026-11-04-arp9-silent-catch-report.md` 里：375 处 `runCatching`、
其中 357 处静默，远大于「≤20 处逐处补记账」的阈值，所以**只出报告、不批量改码**。
这个脚本存在的意义是让下次再问这个问题时，几秒钟就能拿到同一口径的数字，而不是回家
重写一遍正则。

## 口径

只看**静默**：`runCatching { … }` 之后既没有 `.onFailure { … }`，也没有把异常交给
上层的。分三类：

1. `丢弃` —— 语句式调用，结果根本不接（异常彻底消失，连默认值都没用上）；
2. `链式` —— `.getOrNull()` / `.getOrDefault(x)` / `.getOrElse { … }`，
   失败被**有意**折成默认值；
3. `有 onFailure` —— 不算静默，只统计不列举。

再按**宿主函数名**粗分「写 / 读 / 其他」：`save`/`write`/`persist` 里的静默失败
代价最高（用户以为存了），`load`/`get` 里的静默往往就是「还没有这个文件」。
这条划分是**启发式**（按名字猜语义），所以报告里只用来排序，不用来定罪。

## 用法

    python3 tools/silent-catch-report.py                 # 默认范围，Markdown 到 stdout
    python3 tools/silent-catch-report.py --list 写        # 列出「写」类每一处
    python3 tools/silent-catch-report.py --roots core/src/commonMain/kotlin/com/kapp/tenon/core/transport
"""

from __future__ import annotations

import argparse
import collections
import os
import re
import sys

# 计划书 ARP-9 划定的范围：传输层、持久层、以及 app 侧存储路径。
DEFAULT_ROOTS = [
    "core/src/commonMain/kotlin/com/kapp/tenon/core/transport",
    "core/src/commonMain/kotlin/com/kapp/tenon/core/persist",
    "core/src/jvmSharedMain/kotlin/com/kapp/tenon/core",
    "app/src/commonMain/kotlin/com/kapp/tenon/domain",
    "app/src/commonMain/kotlin/com/kapp/tenon/db",
    "app/src/jvmMain/kotlin/com/kapp/tenon/domain",
    "app/src/jvmSharedMain/kotlin/com/kapp/tenon/domain",
    "app/src/androidMain/kotlin/com/kapp/tenon/domain",
    "app/src/iosMain/kotlin/com/kapp/tenon/domain",
]

WRITE_WORDS = re.compile(
    r"(save|write|store|persist|delete|remove|clear|flush|append|close|rename|"
    r"mkdir|put|commit|update|insert|publish|send|emit|mark)",
    re.I,
)
READ_WORDS = re.compile(
    r"(load|read|exists|parse|decode|get|find|list|open|scan|resolve|query|select|count)",
    re.I,
)
FUN_DECL = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.]+\.)?([A-Za-z0-9_]+)\s*\(")


def strip_comments(text: str) -> str:
    """抹掉注释、保留字符串（与 `GuardSourceScan.stripComments` 同口径，换行保留）。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            out.append("  ")
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            if i < n:
                out.append("  ")
                i += 2
        elif c == '"':
            out.append(c)
            i += 1
            while i < n:
                if text[i] == "\\" and i + 1 < n:
                    out.append(text[i])
                    out.append(text[i + 1])
                    i += 2
                    continue
                out.append(text[i])
                if text[i] == '"':
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def find_close(text: str, i: int) -> int:
    """从 `text[i]`（一个 `{` 或 `(`）起，返回配平后的位置；`-1` 表示没闭合。"""
    depth = 0
    n = len(text)
    j = i
    while j < n:
        c = text[j]
        if c == '"':
            j += 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            j += 1
            continue
        if c in "({":
            depth += 1
        elif c in ")}":
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return -1


def chain_after(text: str, end: int) -> str:
    """取 `runCatching{…}` 之后**同一表达式**上的链式调用（跨行 `.foo()` 也算）。"""
    parts = []
    q = end + 1
    n = len(text)
    while q < n:
        e = text.find("\n", q)
        if e < 0:
            break
        line = text[q:e].strip()
        if line.startswith("."):
            parts.append(line)
            q = e + 1
        elif line == "":
            q = e + 1
        else:
            break
    return "".join(parts)


def is_returned(live: str, at: int) -> bool:
    """`= runCatching {…}` / `return runCatching {…}`：结果交给了调用方，不算丢弃。

    这条区分不是抠字眼：`fun load(): T? = runCatching { … }.getOrNull()` 与
    `fun save() { runCatching { … } }` 在字面上只差一个 `= `，但前者把失败
    变成了「没有」，后者把失败变成了「什么都没发生」—— 只有后者值得记一笔。
    """
    line_start = live.rfind("\n", 0, at) + 1
    head = live[line_start:at].rstrip()
    return head.endswith("=") or head.endswith("return")


def enclosing_fun(live: str, at: int) -> str:
    name = "(top-level)"
    for m in FUN_DECL.finditer(live, 0, at):
        name = m.group(1)
    return name


def classify_name(fn: str) -> str:
    if WRITE_WORDS.search(fn):
        return "写"
    if READ_WORDS.search(fn):
        return "读"
    return "其他"


def collect(roots: list[str]) -> list[dict]:
    rows = []
    for root in roots:
        if not os.path.isdir(root):
            print("[silent-catch-report] 跳过（不是目录）：" + root, file=sys.stderr)
            continue
        for dirpath, _dirnames, filenames in os.walk(root):
            if os.sep + "build" in dirpath + os.sep:
                continue
            for name in sorted(filenames):
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, name)
                with open(path, encoding="utf-8") as fh:
                    src = fh.read()
                live = strip_comments(src)
                pos = 0
                while True:
                    k = live.find("runCatching", pos)
                    if k < 0:
                        break
                    brace = live.find("{", k)
                    if brace < 0:
                        break
                    end = find_close(live, brace)
                    if end < 0:
                        break
                    pos = end + 1
                    chain = chain_after(live, end)
                    fn = enclosing_fun(live, k)
                    if "onFailure" in chain:
                        shape = "有 onFailure"
                    elif chain:
                        shape = "链式折默认值"
                    elif is_returned(live, k):
                        shape = "作为返回值"
                    else:
                        shape = "丢弃结果"
                    rows.append(
                        {
                            "path": path,
                            "line": src.count("\n", 0, k) + 1,
                            "fn": fn,
                            "kind": classify_name(fn),
                            "shape": shape,
                        }
                    )
    return rows


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="静默 runCatching 普查（报告工具，非门禁）")
    ap.add_argument("--roots", nargs="*", default=DEFAULT_ROOTS)
    ap.add_argument("--list", dest="list_kind", choices=["写", "读", "其他"], default=None)
    ap.add_argument("--json", action="store_true", help="按行输出 JSON（给后续处理用）")
    args = ap.parse_args(argv)

    rows = collect(args.roots)
    if not rows:
        # 空集合会让「没有 offender」恒真，所以这里必须响 —— 与源码门禁同一纪律。
        print("[silent-catch-report] 一处 runCatching 都没扫到 —— 路径过期了", file=sys.stderr)
        return 2

    if args.json:
        import json

        print(json.dumps(rows, ensure_ascii=False))
        return 0

    if args.list_kind:
        for r in rows:
            if r["kind"] == args.list_kind and r["shape"] != "有 onFailure":
                print(f"{r['path']}:{r['line']}\t{r['shape']}\tfun {r['fn']}")
        return 0

    by_shape = collections.Counter(r["shape"] for r in rows)
    # 「作为返回值」也是一种交接（调用方拿到了 Result），不算静默；
    # 「链式折默认值」是**有意**把失败折成默认值，单独记一行但不列进「该动手」。
    silent = [r for r in rows if r["shape"] in ("丢弃结果", "链式折默认值")]
    by_kind = collections.Counter(r["kind"] for r in silent)
    per_file = collections.Counter(r["path"] for r in silent)

    print("# 静默 runCatching 普查（ARP-9）\n")
    print(f"扫描根 {len(args.roots)} 个，`runCatching` 共 **{len(rows)}** 处。\n")
    print("| 形态 | 处数 |")
    print("| --- | --- |")
    for shape in ("有 onFailure", "链式折默认值", "作为返回值", "丢弃结果"):
        print(f"| {shape} | {by_shape.get(shape, 0)} |")
    print(f"\n静默合计 **{len(silent)}** 处；按宿主函数名粗分：")
    print("写 {} / 读 {} / 其他 {}。\n".format(by_kind.get("写", 0), by_kind.get("读", 0), by_kind.get("其他", 0)))
    print("| 文件 | 静默处数 |")
    print("| --- | --- |")
    for path, count in per_file.most_common(15):
        print(f"| `{path}` | {count} |")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
