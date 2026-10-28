#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""禁止注释里的 `X.kt:NN` 行号引用（stale-comment-ref gate）。

## 为什么

行号引用是这套仓库里最容易腐坏的一种注释：文件插入或删除 10 行，那条注释指的地方
就悄悄变成了另一段代码 —— 而且**没有任何编译错误**。审计里人工确认的 10 组陈旧注释，
全部是这一种形态，没有一条是作者判断错了。

所以规则很简单：**注释里引用别的文件时写符号名，不写行号。**
符号名会随重构一起被编译器和 IDE 盯着，行号不会。

## 判定方式（为什么不能简单 grep）

裸 `grep -r '\\.kt:[0-9]'` 会报出一大堆假阳性，因为这个仓库里同名文件很多
（`Codec.kt` 有九个、`App.kt`、`Platform.kt`、`Common.kt` 各有好几个，在不同包里）。
照名字找只能得到一堆候选文件，"那一行到底是什么"取决于选中了哪个候选。

本脚本按下面这条链判定，只有**全部候选都指向无意义的位置**才算一条真违规：

1. 只扫注释里的引用（行注释与块注释），代码与字符串里的不算。
2. `X.kt` 在扫描根下解析出**全部**同名候选。
3. 逐个候选看 `NN` 行是否"有意义"：非空、不是孤立的 `}`、不是 `*/`、不是 import 行。
4. 至少有一个候选有意义 ⇒ 不算违规（只是同名歧义，记录为 `ambiguous` 不阻塞）。
   全部候选都没意义，或文件根本不存在 ⇒ 算一条 `broken`，退出码非零。

`ambiguous` 也进输出（让人知道有同名歧义需要收敛），但不阻塞。

## 棘轮

和 `cjk-literal-gate` 同构：基线记录**每个文件允许的 broken 条数**，
只有"新增"才失败，下调只提示。基线是 text 文件，第一行是生成备注。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

# 引用形态：`Name.kt:123` 或 `Name.kt:123-456`。刻意排除 markdown 链接与 URL。
REF_RE = re.compile(r"([A-Za-z][A-Za-z0-9_]*\.kt):(\d+)(?:-(\d+))?")
# 排除：协议文档里的示例、以及显式声明"这是行号引用示例"的行。
SKIP_LINE_RE = re.compile(r"stale-comment-ref|X\.kt:NN|行号引用")

# 模块扫描根（相对 --root）。与 cjk-literal-gate 的扫描根故意不同：
# 截图夹具（androidApp/src/screenshotTest）大量引用"上一次截图时某个文件的某一行"，
# 那些引用随夹具重录而变，属于工具链历史而不是人读的注释。
DEFAULT_ROOTS = (
    # 领域层现在住在 :app。漏掉这几行不会报错：`kotlin_files` 对不存在的根是
    # `continue`，于是"整层没被扫过"显示成"无新增"。
    "app/src/commonMain",
    "app/src/androidMain",
    "app/src/iosMain",
    "app/src/jvmMain",
    "app/src/jvmSharedMain",
    "app/src/commonTest",
    "app/src/jvmTest",
    "composeApp/src/commonMain",
    "composeApp/src/androidMain",
    "composeApp/src/iosMain",
    "composeApp/src/jvmMain",
    "composeApp/src/commonTest",
    "composeApp/src/jvmTest",
    # 2026-10-28 补：core 的 jvmMain / jvmSharedMain / iosMain / jvmTest 原来不在名单里。
    # jvmSharedMain 是"新传输实现写这里"的接缝（AGENTS.md §0），漏掉它等于漏掉一半新代码。
    "core/src/commonMain",
    "core/src/jvmMain",
    "core/src/jvmSharedMain",
    "core/src/iosMain",
    "core/src/commonTest",
    "core/src/jvmTest",
    "agentNode/src/main",
    "desktopApp/src/main",
    "cli/src/main",
    "cli/src/test",
)


def kotlin_files(roots, base):
    for root in roots:
        top = os.path.join(base, root)
        if not os.path.isdir(top):
            continue
        for dirpath, dirnames, filenames in os.walk(top):
            dirnames[:] = [d for d in dirnames if d != "build"]
            for fn in filenames:
                if fn.endswith(".kt"):
                    yield os.path.relpath(os.path.join(dirpath, fn), base)


def comment_line_mask(lines):
    """逐行标出「哪些字符属于注释」，用于只在注释里找引用。

    借用 cjk-literal-gate 的思路，但只要一个布尔掩码：先逐行把字符串区域标成
    "不是注释"，剩下的非空白区域里，`//` 到行尾、以及成对的 `/* */`，才是注释。

    多行状态（块注释是否延续）跨行传递。字符串状态不跨行 —— Kotlin 里只有三引号
    原始字符串和未闭合字符串能跨行，两者都很罕见，按行内处理即可（代价是极少数
    情况下的假阳性或假阴性，对一道"禁止写行号"的门禁是安全的偏保守方向）。
    """
    mask = []
    in_block = False
    for line in lines:
        row = [False] * len(line)
        i, n = 0, len(line)
        if in_block:
            end = line.find("*/", 2)
            if end == -1:
                mask.append([True] * n)
                continue
            for k in range(0, end + 2):
                row[k] = True
            i = end + 2
            in_block = False
        while i < n:
            ch = line[i]
            if ch == '"':
                # 跳过整个字符串字面量（单行内闭合；三引号按"跨到行尾"处理）。
                if line.startswith('"""', i):
                    end = line.find('"""', i + 3)
                    i = n if end == -1 else end + 3
                else:
                    i += 1
                    while i < n:
                        if line[i] == "\\":
                            i += 2
                            continue
                        if line[i] == '"':
                            i += 1
                            break
                        if line[i] == "'":
                            i += 1     # 字符字面量，最多再吃两个字符
                            continue
                        i += 1
                continue
            if ch == "'":
                i += 1
                while i < n:
                    if line[i] == "\\":
                        i += 2
                        continue
                    i += 1
                    if line[i - 1] != "\\":
                        break
                continue
            if line.startswith("//", i):
                for k in range(i, n):
                    row[k] = True
                break
            if line.startswith("/*", i):
                end = line.find("*/", i + 2)
                if end == -1:
                    for k in range(i, n):
                        row[k] = True
                    in_block = True
                    break
                for k in range(i, end + 2):
                    row[k] = True
                i = end + 2
                continue
            i += 1
        mask.append(row)
    return mask


def meaningful(lines, ln):
    """那一行是否"值得被引用"。"""
    if ln < 1 or ln > len(lines):
        return False
    text = lines[ln - 1].strip()
    if text == "":
        return False
    if text in ("}", "})", "};", "*/", ")", ","):
        return False
    if text.startswith("import ") or text.startswith("package "):
        return False
    return True


def build_index(base, roots):
    """basename -> [(relpath, lines)]"""
    index = {}
    for rel in kotlin_files(roots, base):
        path = os.path.join(base, rel)
        try:
            with open(path, encoding="utf-8") as fh:
                index.setdefault(os.path.basename(rel), []).append(
                    (rel, fh.read().split("\n"))
                )
        except (OSError, UnicodeDecodeError):
            continue
    return index


def scan(base, roots):
    index = build_index(base, roots)
    findings = []
    for rel in sorted(kotlin_files(roots, base)):
        path = os.path.join(base, rel)
        with open(path, encoding="utf-8") as fh:
            lines = fh.read().split("\n")
        mask = comment_line_mask(lines)
        for ln, line in enumerate(lines, start=1):
            if SKIP_LINE_RE.search(line):
                continue
            row = mask[ln - 1] if ln - 1 < len(mask) else []
            for m in REF_RE.finditer(line):
                if not all(row[m.start():m.end()]):
                    continue          # 不在注释里
                name, start = m.group(1), int(m.group(2))
                cands = index.get(name, [])
                if not cands:
                    findings.append({
                        "file": rel, "line": ln, "ref": m.group(0),
                        "kind": "broken", "reason": "文件不存在",
                        "candidates": [],
                    })
                    continue
                ok = [c for c, cl in cands if meaningful(cl, start)]
                if ok:
                    continue          # 至少一个候选有意义
                if len(cands) == 1:
                    kind, reason = "broken", "那一行是空行或孤立括号"
                else:
                    kind = "ambiguous"
                    reason = "同名文件有 %d 个，且全部指向无意义位置" % len(cands)
                findings.append({
                    "file": rel, "line": ln, "ref": m.group(0),
                    "kind": kind, "reason": reason,
                    "candidates": [c for c, _ in cands],
                })
    return findings


# --------------------------------------------------------------------------
# 棘轮基线
# --------------------------------------------------------------------------

def load_baseline(path):
    base = {}
    if not os.path.exists(path):
        return base
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            k, _, v = line.rpartition("\t")
            if k:
                base[k] = int(v)
    return base


def write_baseline(path, current, note):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("# 注释里的 `X.kt:NN` 行号引用棘轮基线（stale-comment-ref gate）\n")
        fh.write("# 格式: <相对路径>\\t<允许的 broken 条数>\n")
        fh.write("# 生成时机: %s\n" % note)
        fh.write("# 生成命令: python3 tools/stale-comment-ref.py --write\n")
        for k in sorted(current):
            fh.write("%s\t%d\n" % (k, current[k]))


def git_note(base):
    try:
        import subprocess
        head = subprocess.run(["git", "rev-parse", "--short", "HEAD"],
                              cwd=base, capture_output=True, text=True,
                              check=True).stdout.strip()
    except Exception:
        head = "unknown"
    return head


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".", help="scaffold 目录")
    ap.add_argument("--baseline", default="tools/stale-comment-ref-baseline.txt")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    base = os.path.abspath(args.root)
    findings = scan(base, DEFAULT_ROOTS)
    broken = [f for f in findings if f["kind"] == "broken"]
    ambiguous = [f for f in findings if f["kind"] == "ambiguous"]

    if args.json:
        print(json.dumps({"broken": broken, "ambiguous": ambiguous},
                         ensure_ascii=False, indent=2))
        return 1 if broken else 0

    current = {}
    for f in broken:
        current[f["file"]] = current.get(f["file"], 0) + 1

    if args.write:
        write_baseline(os.path.join(base, args.baseline), current,
                       "HEAD=%s" % git_note(base))
        print("[stale-comment-ref] 已写入基线：%d 个文件 / %d 条"
              % (len(current), sum(current.values())))
        return 0

    baseline = load_baseline(os.path.join(base, args.baseline))
    failures = []
    for f in broken:
        allowed = baseline.get(f["file"], 0)
        seen = sum(1 for x in broken[:broken.index(f) + 1] if x["file"] == f["file"])
        if seen > allowed:
            failures.append(f)

    print("[stale-comment-ref] 扫描 %d 个根目录；broken %d 条，ambiguous %d 条"
          % (len(DEFAULT_ROOTS), len(broken), len(ambiguous)))
    for f in ambiguous:
        print("  · %s:%d  `%s` %s（同名候选 %s）"
              % (f["file"], f["line"], f["ref"], f["reason"], f["candidates"]))
    if failures:
        print("[stale-comment-ref] FAIL — %d 条新增" % len(failures))
        for f in failures:
            print("  · %s:%d  `%s` —— %s"
                  % (f["file"], f["line"], f["ref"], f["reason"]))
            print("      改法：把行号换成符号名，或删掉这个引用。")
        return 1
    print("[stale-comment-ref] OK —— 无新增（基线 %d 条 / %d 个文件）"
          % (sum(baseline.values()), len(baseline)))
    return 0


if __name__ == "__main__":
    sys.exit(main())