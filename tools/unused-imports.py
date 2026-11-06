#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""找出（并可删除）Kotlin 源文件里没有任何用处的 import。

为什么需要它：Kotlin 编译器**不会**为未使用的 import 报警告 —— 它只在 IDE 里灰掉。
于是「把一个 1,709 行的文件拆成 9 个」这种搬运会留下成片的死 import，而没有任何
门禁看得见。本仓真出过：9 个桌面 pane 文件里积了 711 条。

判据很朴素：import 引进来的那个**简单名**，在文件正文（import 行之外）里
按词边界出现过吗？没出现过，这条 import 就不可能在做任何事。

唯一的例外是**委托属性约定**：`by` 用到的 `getValue` / `setValue` 是隐式调用，
名字根本不会出现在正文里，所以它们在 IMPLICIT 集合里被跳过。

用法：
    python3 tools/unused-imports.py            # 只报告
    python3 tools/unused-imports.py --check    # 有就非零退出（CI 用）
    python3 tools/unused-imports.py --fix      # 就地删掉
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$")

# 靠 Kotlin 约定隐式调用、名字不出现在正文里的 import。
IMPLICIT = frozenset({"getValue", "setValue", "provideDelegate"})


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def body_without_imports(text: str) -> str:
    return "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("import ")
    )


def unused_in(text: str) -> list[str]:
    """这个文件里没用的 import 语句（整行原文，按出现顺序）。"""
    body = body_without_imports(text)
    out: list[str] = []
    for line in text.splitlines():
        m = IMPORT_RE.match(line)
        if not m:
            continue
        fq, alias = m.group(1), m.group(2)
        if fq.endswith(".*"):  # 通配 import 没法判，跳过
            continue
        name = alias or fq.rsplit(".", 1)[-1]
        if name in IMPLICIT:
            continue
        if not re.search(r"\b" + re.escape(name) + r"\b", body):
            out.append(line)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=None, help="仓库根（默认按脚本位置推断）")
    ap.add_argument("--check", action="store_true", help="有未使用的 import 就非零退出")
    ap.add_argument("--fix", action="store_true", help="就地删掉")
    args = ap.parse_args()

    root = Path(args.root).resolve() if args.root else repo_root()
    src = root
    if not src.is_dir():
        print(f"找不到源码根：{src}", file=sys.stderr)
        return 2

    files = 0
    total = 0
    for path in sorted(src.rglob("*.kt")):
        parts = path.parts
        if any(p.startswith(".") or p == "build" for p in parts):
            continue
        text = path.read_text("utf-8")
        dead = unused_in(text)
        if not dead:
            continue
        files += 1
        total += len(dead)
        rel = path.relative_to(root)
        if not args.fix:
            print(f"{rel}  {len(dead)} 条")
            for line in dead:
                print(f"    {line}")
        else:
            drop = set(dead)
            kept = [line for line in text.splitlines() if line not in drop]
            path.write_text("\n".join(kept) + "\n", "utf-8")
            print(f"{rel}  删掉 {len(dead)} 条")

    if not total:
        print("OK：没有未使用的 import。")
        return 0

    verb = "删掉" if args.fix else "发现"
    print(f"\n{verb} {total} 条未使用的 import，涉及 {files} 个文件。")
    if args.check and not args.fix:
        print("FAIL：Kotlin 编译器不会为未使用的 import 报警告，所以要有这条门禁。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
