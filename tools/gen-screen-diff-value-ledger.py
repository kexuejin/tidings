# -*- coding: utf-8 -*-
"""把 design-screen-diff.py 的 806 个「对不上的值」逐条摊成一张账本。

这不是新量具，是把既有量具的输出换个粒度打印：`design-screen-diff.py` 按
「屏|维度」报集合差集，本脚本按「屏|维度|侧|值」报一行一条，并按简报 §4
的档外取值判定政策给出机械归档，供人工逐条复核。

用法（从仓库根跑）：
    python3 tools/gen-screen-diff-value-ledger.py \
        > docs/plans/2026-10-12-screen-diff-value-ledger.md
"""
import collections
import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIFF = os.path.join(ROOT, "tools", "design-screen-diff.py")

DIM_ORDER = ["spacing", "radius", "font-size", "font-weight"]

# 归档名（顺序即汇总表列序）
CLS = [
    "code-就近snap",       # code 侧值离 proto 侧最近值 ≤2 —— 政策② 可接受
    "code-档外偏差",       # 3~5 —— 政策③/④ 需人核
    "code-原型没有",       # >5 —— 政策③ 缺陷候选
    "proto-被snap",        # proto 侧值离 code 侧最近值 ≤2 —— 政策②
    "proto-代码侧没有",    # >2 —— 真缺口 或 M3 控件族盲区
    "量具盲区-代码侧空",   # 该屏该维度 code 侧取值集合为空
    "原型该维度无取值",    # 该屏该维度 proto 侧为空（整屏/控件族新增）
]


def load_diff():
    spec = importlib.util.spec_from_file_location("_dsd", DIFF)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def num(s):
    try:
        return float(s)
    except ValueError:
        return None


def nearest(v, other):
    n = num(v)
    best, bd = None, None
    for o in other:
        m = num(o)
        if n is None or m is None:
            continue
        d = abs(n - m)
        if bd is None or d < bd:
            best, bd = o, d
    return best, bd


def classify(side, v, other):
    """side: 'code' 或 'proto'；other: 另一侧取值集合。"""
    if not other:
        return ("量具盲区-代码侧空" if side == "code" else "原型该维度无取值"), "", ""
    best, d = nearest(v, other)
    if best is None:  # 非数值取值（理论上不出现）
        return ("code-档外偏差" if side == "code" else "proto-代码侧没有"), "?", "?"
    if side == "code":
        if d <= 2:
            return "code-就近snap", best, "%g" % d
        if d <= 5:
            return "code-档外偏差", best, "%g" % d
        return "code-原型没有", best, "%g" % d
    if d <= 2:
        return "proto-被snap", best, "%g" % d
    return "proto-代码侧没有", best, "%g" % d


def main():
    mod = load_diff()
    sig = mod.signature(mod.collect(".", None, None))
    cells = collections.defaultdict(dict)
    for k, v in sig.items():
        if k.endswith("|__error__"):
            continue
        screen, dim, side = k.rsplit("|", 2)
        cells[(screen, dim)][side] = set(v)

    rows = []  # (screen, dim, side, value, nearest, dist, cls)
    for (screen, dim) in sorted(cells):
        cell = cells[(screen, dim)]
        proto = cell.get("proto", set())
        code = cell.get("code", set())
        for v in sorted(code - proto, key=lambda s: (num(s) is None, num(s) or 0)):
            c, b, d = classify("code", v, proto)
            rows.append((screen, dim, "代码", v, b, d, c))
        for v in sorted(proto - code, key=lambda s: (num(s) is None, num(s) or 0)):
            c, b, d = classify("proto", v, code)
            rows.append((screen, dim, "原型", v, b, d, c))

    total = len(rows)
    by_cls = collections.Counter(r[6] for r in rows)
    by_cls_dim = collections.defaultdict(collections.Counter)
    for r in rows:
        by_cls_dim[r[6]][r[1]] += 1

    out = sys.stdout
    w = out.write
    w("# design-screen-diff 逐值账本（%d 个对不上的值）\n\n" % total)
    w("> 由 `tools/gen-screen-diff-value-ledger.py` 生成，"
      "数据源是 `tools/design-screen-diff.py` 的 "
      "`signature(collect('.', None, None))`。\n"
      "> 复现：`python3 tools/gen-screen-diff-value-ledger.py`。\n\n")
    w("## 这份文件是什么\n\n")
    w("`design-screen-diff.py` 按「屏 + 维度」报**取值集合**的差集"
      "（`onlyProto` / `onlyCode`），一份 806 个值。\n"
      "它回答的是「这屏这个维度对不对得上」，**不回答**「这一个值该不该改」。\n"
      "本文件把 806 个值逐条摊平，并按 `docs/plans/2026-10-12-team-briefing.md` §4 的"
      "档外取值判定政策给出**机械归档**（只看数值距离，不看语义）。\n"
      "**归档是给人工复核用的分诊，不是判决**：`code-原型没有` 里的值绝大多数"
      "是 M3 控件族自带的内边距/shape，量具读不到原型侧的对应声明。\n\n")
    w("## 归档规则（机械，仅按数值距离）\n\n")
    w("| 归档 | 含义 | 判据 |\n|---|---|---|\n")
    w("| `code-就近snap` | 代码值离原型最近值 ≤2 —— 政策② 可接受 | `0 < d ≤ 2` |\n")
    w("| `code-档外偏差` | 代码值离原型最近值 3~5 —— 政策③/④ 需人核 | `2 < d ≤ 5` |\n")
    w("| `code-原型没有` | 代码值离原型最近值 >5 —— 政策③ 缺陷候选 | `d > 5` |\n")
    w("| `proto-被snap` | 原型值被代码侧就近值吸收 —— 政策② | `0 < d ≤ 2` |\n")
    w("| `proto-代码侧没有` | 原型值在代码侧找不到近值 —— 真缺口或 M3 盲区 | `d > 2` |\n")
    w("| `量具盲区-代码侧空` | 该屏该维度代码侧取值集合为空 | 集合为空 |\n")
    w("| `原型该维度无取值` | 该屏该维度原型侧为空（整屏新增/控件族新增） | 集合为空 |\n\n")

    w("## 汇总\n\n")
    w("| 归档 | " + " | ".join(DIM_ORDER) + " | 合计 |\n")
    w("|---|" + "---|" * (len(DIM_ORDER) + 1) + "\n")
    for c in CLS:
        cells_n = [by_cls_dim[c][d] for d in DIM_ORDER]
        w("| `%s` | %s | %d |\n" % (c, " | ".join(str(n) for n in cells_n), by_cls[c]))
    w("| **合计** | %s | **%d** |\n\n" % (
        " | ".join(str(sum(by_cls_dim[c][d] for c in CLS)) for d in DIM_ORDER), total))

    w("## 逐值明细\n\n")
    w("每屏一节。`另一侧最近值` 是机械找的最近值（`—` = 另一侧该维度为空）。\n\n")
    by_screen = collections.defaultdict(list)
    for r in rows:
        by_screen[r[0]].append(r)
    for screen in sorted(by_screen):
        rs = by_screen[screen]
        w("### %s（%d 个值）\n\n" % (screen, len(rs)))
        w("| 维度 | 侧 | 值 | 另一侧最近值 | 距离 | 归档 |\n|---|---|---|---|---|---|\n")
        for (sc, dim, side, v, b, d, c) in sorted(
                rs, key=lambda r: (DIM_ORDER.index(r[1]), r[2], num(r[3]) or 0)):
            w("| %s | %s | %s | %s | %s | `%s` |\n" %
              (dim, side, v, b or "—", d or "—", c))
        w("\n")

    sys.stderr.write("rows=%d classes=%s\n" % (total, dict(by_cls)))


if __name__ == "__main__":
    main()
