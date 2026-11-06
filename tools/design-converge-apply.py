#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把已裁决的收敛映射**属性感知地**应用到原型 CSS（skill §2.5 步骤 3 的实现）。

与 `design-converge.py`（只读出表）成对：本工具是唯一允许改设计源的脚本。

安全设计（每一条都对应一次真实事故或真实风险）：

* **默认 dry-run**，`--apply` 才写；写前自动留 `.bak-<sha前8位>`。
* **只改第一个 `<style>` 块内的声明值**，块外的 HTML/JS 一个字节不重排
  （上一版把闭标签组当"剩余全文"，吞掉了 20 万字符 —— 靠备份救回，
  教训：任何改设计源的脚本必须先过"结构不变量"才允许落盘）。
* **落盘前不变量自检**：`<style>`/`</style>`/`<script>` 计数、CSS 花括号配平、
  `const SCREENS` 存在、文件以 `</html>` 结尾、长度变化 ≤ 替换字符差之和。
  任何一条不满足 → 拒绝写盘并退出 2。
* **属性上下文**：`18px` 在 font-size 语境→16，在 width 语境**不动**；
  多值声明（`padding: 9px 11px`）按括号外空白拆成单值逐个映射。
* **钉 sha**：基线里记 appliedTo，重复跑同一映射是幂等的（值已不在 mapping 键里）。

映射表 = 本产品的裁决结果（docs/plans/2026-10-09-relay-spacing-convergence-rulings.md，
用户 2026-10-09 授权「这些按你推荐的来」）。搬到别的产品时改 MAPPINGS 即可。
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import design_contract as dc  # noqa: E402  只用它的 scan_base 口径

DESIGN = "prototype/relay.html"

# 值映射：组 → (属性正则, {旧值: 新值})。组间互斥，逐组应用。
# 裁决 #字号：半像素族归整数、13/15→14、17/18→16；9.5/19/31 留待并排后定。
# 裁决 #4/#7：间距 18→16、22→24；#5 的 20px 不动（并排后定）。
# 裁决 圆角：10→12；6/2/14 留待并排后定。
# 裁决 字重：650→600；400×2 留（上下文并入，单独判）。
MAPPINGS = {
    # 第二轮（2026-10-10，docs/plans/2026-10-10-relay-convergence-round2-rulings.md §一；
    # 用户「这两个你都按推荐的来」= ⚠A/⚠B 与 §一 11 条全授权）。
    # 19px→16 而非清单草稿写的 18：第一轮已把 18px 从字号阶梯清掉，
    # 复活动态刚消灭的档位会被自己的棘轮抓住 —— 就近取**现存**档。
    "font-size": (
        r"font-size\s*:\s*|--fs-[a-z0-9-]+\s*:\s*",
        {"10px": "11px", "9.5px": "11px", "19px": "16px"},
    ),
    "spacing": (
        r"(?:padding|margin)(?:-(?:top|right|bottom|left))?\s*:\s*|"
        r"(?:row-|column-)?gap\s*:\s*|--pad[a-z-]*\s*:\s*",
        {"20px": "16px", "26px": "24px"},
    ),
    "radius": (
        r"border-radius\s*:\s*|--r-[a-z]+\s*:\s*",
        {"14px": "12px"},
    ),
    "weight": (
        r"font-weight\s*:\s*|--fw-w\s*:\s*",
        {},  # 700×17 留待字阶批：代码 dialog 标题实际是 medium(500)，差得更深，不在本轮
    ),
}

# 精确整串替换（各出现一次，数量断言不符即拒写）：⚠B 拍板 bubble=12（代码权威源），
# 手机 --r-bubble:18px 与桌面覆盖 --r-bubble:16px 两处都归 12。
# 不走 radius 映射：全局 16→12 会误伤 --r-card:16px（card=large 槽，合法值）。
TARGETED = [
    ("--r-bubble:18px", "--r-bubble:12px", 1),
    ("--r-bubble:16px", "--r-bubble:12px", 1),
]


class Unsafe(Exception):
    pass


def split_top(val: str):
    parts, buf, depth = [], [], 0
    for ch in val:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == " " and depth == 0:
            if buf:
                parts.append("".join(buf)); buf = []
            continue
        buf.append(ch)
    if buf:
        parts.append("".join(buf))
    return parts


def apply_mappings(css: str):
    """逐组替换；返回 (新css, 每组替换数, 替换明细列表)。"""
    counts, detail = {}, []
    for group, (prop_pat, mapping) in MAPPINGS.items():
        def rep(m, mapping=mapping, group=group):
            head, val = m.group(1), m.group(2)
            toks = split_top(val.strip())
            changed = False
            for i, t in enumerate(toks):
                if t in mapping:
                    detail.append((group, t, mapping[t]))
                    toks[i] = mapping[t]; changed = True
            if not changed:
                return m.group(0)
            lead = val[: len(val) - len(val.lstrip())]
            return head + lead + " ".join(toks)
        # 两个捕获组：group(1)=属性名+冒号，group(2)=值。**不能再包一层外括号** ——
        # 上一版 `((?:pat)(val))` 让 group(1) 变成整个匹配（含旧值），
        # 替换结果 = 旧匹配+新值，把旧值留在原地；不变量自检（长度只减不增）拦下了它。
        new_css, _n = re.subn(r"(" + prop_pat + r")([^;{}\n]+)", rep, css)
        css = new_css
        counts[group] = sum(1 for g, _, _ in detail if g == group)
    return css, counts, detail


def invariants(before: str, after: str, applied_groups: int):
    """结构不变量。返回问题列表（空 = 可写盘）。"""
    problems = []
    for tag in ("<style", "</style>", "<script", "</script>"):
        b, a = before.lower().count(tag), after.lower().count(tag)
        if b != a:
            problems.append(f"{tag} 计数 {b} → {a}")
    bs = before[before.index("<style"): before.index("</style>")]
    as_ = after[after.index("<style"): after.index("</style>")]
    if bs.count("{") != bs.count("}") or as_.count("{") != as_.count("}"):
        problems.append("CSS 花括号不配平")
    if bs.count("{") != as_.count("{"):
        problems.append(f"CSS 规则数 {bs.count('{')} → {as_.count('{')}（本工具不该增删规则）")
    for probe in ("const SCREENS", "</html>", "sessionPickerHTML", "function topbar"):
        if probe in before and probe not in after:
            problems.append(f"结构件丢失：{probe}")
    if not after.rstrip().endswith("</html>"):
        problems.append("文件不再以 </html> 结尾")
    shrink = len(before) - len(after)
    if abs(shrink) > applied_groups * 8 + 16:
        problems.append(f"长度差 {shrink} 与替换规模不符（疑似吞块）")
    return problems


def main():
    ap = argparse.ArgumentParser(description="原型 CSS 收敛应用器（默认 dry-run）")
    ap.add_argument("--repo", default=".")
    ap.add_argument("--apply", action="store_true", help="真正写盘（先留 .bak）")
    args = ap.parse_args()
    path = os.path.join(args.repo, DESIGN)
    raw = open(path, encoding="utf-8").read()
    sha = hashlib.sha256(raw.encode()).hexdigest()[:12]
    m = re.search(r"(<style[^>]*>)(.*?)(</style>)", raw, re.S)
    if not m:
        print("找不到 <style> 块", file=sys.stderr); return 2
    new_css, counts, detail = apply_mappings(m.group(2))
    for old, new, want in TARGETED:
        got = new_css.count(old)
        if got != want:
            print(f"精确替换 {old!r} 期望 {want} 处，实际 {got} 处 —— 拒绝写盘", file=sys.stderr)
            return 2
        new_css = new_css.replace(old, new)
        detail.append(("targeted", old, new))
        counts["targeted"] = counts.get("targeted", 0) + want
    total = sum(counts.values())
    after = raw[: m.start(2)] + new_css + raw[m.end(2):]   # 只替换块内，块外原样
    problems = invariants(raw, after, total)
    print(f"设计源 {DESIGN} @ sha {sha}")
    for g, n in counts.items():
        print(f"  {g}: {n} 处")
    uniq = {}
    for g, old, new in detail:
        uniq[(g, old, new)] = uniq.get((g, old, new), 0) + 1
    for (g, old, new), n in sorted(uniq.items()):
        print(f"    {g:10s} {old:>8} → {new:<8} ×{n}")
    if problems:
        print("不变量自检失败，拒绝写盘：")
        for p in problems:
            print("  -", p)
        return 2
    print(f"不变量自检通过（长度差 {len(raw) - len(after)}，全在替换差内）")
    if not args.apply:
        print("dry-run：未写盘。加 --apply 生效。")
        return 0
    bak = f"{path}.bak-{sha}"
    if not os.path.exists(bak):
        os.rename(path, bak)
        open(path, "w", encoding="utf-8").write(after)
        print(f"已写盘；备份 {bak}")
    else:
        print(f"备份已存在（{bak}），拒绝覆盖 —— 先人工确认再动", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
