#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""原型 PNG ↔ @Preview 渲染 PNG 的并排图与 contact sheet 生成器。

## 为什么需要它

「原型侧 vs 代码侧」的像素对比一直缺一个进仓的工具：2026-11-01 的 ui-polish
审计用 25 张 contact sheet 做过全量人审，但拼图配方只留在
`docs/plans/2026-10-13-r2-verification.md:60` 的一行 magick 命令里，没有工具化 ——
每一轮视觉对齐都要现场重发明一次。本工具把那条配方固化：

    左绿 = 原型（prototype/screenshots/relay/<shot>.png）
    右棕 = 代码（screenshotTest reference / rendered 最新帧）

配对关系来自配对台账 `docs/plans/2026-10-10-relay-screen-pairing-ledger.md`
（屏 id → 原型 shot 列表 → 现有 Preview 列的函数名），与
`design-preview-pairing.py` 消费同一份台账，不另立口径。

**只拼图，不判红** —— 判定由人看（并排的目的是给「真实适配问题」的裁决供证），
机器判红归 designScreenDiffGate / check-design-palette。

## 用法

    python3 tools/design-parity-sheet.py --list                 # 配对清单（屏 → 原型帧 × 代码帧）
    python3 tools/design-parity-sheet.py                        # 全量并排图 + 按域 contact sheet
    python3 tools/design-parity-sheet.py --domain chat          # 只出一个域
    python3 tools/design-parity-sheet.py --screen m-cabinet     # 只出一屏

产物默认在 `build/parity/`（gitignored）：`pairs/<屏>__<shot>__<帧>.png` 与
`sheets/<域>.png`。会话里用 Read 工具直接看图即可。
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEDGER = "docs/plans/2026-10-10-relay-screen-pairing-ledger.md"
PROTO_SHOTS = "prototype/screenshots/relay"
# 代码帧取最新渲染（updateDebugScreenshotTest 的产物）；没有的帧回落到已提交参考图。
RENDERED = "androidApp/build/outputs/screenshotTest-results/preview/debug/rendered/com/kapp/tenon/domain/screenshot"
# 参考图根（rebrand tidings→tenon 时参考图未搬家，新旧包路径并存，两个都搜）。
REFERENCE_PKG = "androidApp/src/screenshotTestDebug/reference/com/kapp"
OUT = "build/parity"

# 台账「现有 Preview」列里真正的函数名（带（注解）也认得）。`(未列帧)` 等标记自然落空。
PREVIEW_NAME = re.compile(r"\b([A-Z][A-Za-z0-9]+(?:Preview|Host))\b")
SHOT_ID = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)+")

# 按屏 id 前缀分域拼 sheet（与配对台账的分组一致，宁粗勿细）。
DOMAINS = [
    ("conversations", ("m-home", "m-gsearch", "d-search", "d-empty", "d-nosel", "d-nomatch", "d-home-more")),
    ("chat", ("m-chat", "m-hist", "m-screencard", "m-sendfolder", "m-folderin", "m-msgmenu",
              "m-groupcreate", "m-createws", "m-groupinfo", "d-chat", "d-histsearch", "d-groupcreate",
              "d-emoji", "d-folder", "d-resume")),
    ("nodes", ("m-node", "m-addnode", "m-myqr", "d-nodes")),
    ("cabinet", ("m-cabinet", "d-cabinet", "d-files", "d-filesempty")),
    ("agents", ("m-agents", "m-agentdetail", "m-workspacedetail", "m-session-detail",
                "d-agents", "d-agentdetail", "d-workspacedetail", "d-wsgrant", "d-grantqr")),
    ("settings", ("m-settings", "m-set", "m-insets", "d-settings", "d-transferlog")),
    ("remote", ("d-reqscreen", "d-sharescreen", "d-viewscreen", "d-capture", "d-drop")),
    ("tray", ("d-tray",)),
]


def domain_of(screen_id: str) -> str:
    for name, prefixes in DOMAINS:
        if any(screen_id.startswith(p) for p in prefixes):
            return name
    return "misc"


def parse_ledger(path: str):
    """台账 → [(屏id, [shot id…], [Preview 函数名…])]。跳过 ⚠️/❌ 里无帧的行由调用方过滤。"""
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if not line.startswith("| m") and not line.startswith("| d"):
                continue
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) < 5:
                continue
            screen_id, label, shots_cell, _, previews_cell = cells[0], cells[1], cells[2], cells[3], cells[4]
            shots = [s for s in SHOT_ID.findall(shots_cell) if not s.startswith("com/")][:6]
            previews = []
            seen = set()
            for name in PREVIEW_NAME.findall(previews_cell):
                if name not in seen:
                    seen.add(name)
                    previews.append(name)
            rows.append((screen_id, label, shots, previews))
    return rows


def is_dark_shot(shot: str) -> bool:
    """原型帧名带 -dark / -Dark 即深色档（m15b-home-dark、d21-chat-group-dark…）。"""
    return "dark" in shot.lower()


def code_frame_for(fun: str, want_dark: bool = False) -> str | None:
    """函数名 → 最新一帧代码 PNG。优先 rendered（当前状态），回落 reference（已提交基线）。

    参考图目录同时存在新旧两个包路径（rebrand tidings→tenon 时参考图未搬家），
    两个都搜；rendered 目录只有新路径。"""
    roots = [RENDERED] + sorted(glob.glob(os.path.join(REPO, REFERENCE_PKG, "*", "domain", "screenshot")))
    for root in roots:
        hits = sorted(glob.glob(os.path.join(root, "**", f"{fun}_*.png"), recursive=True))
        if not hits:
            continue
        if want_dark:
            # 深色原型帧优先配 Dark 命名的代码帧；没有 Dark 变体就如实回落
            # （回落比配错主题诚实 —— 并排图两侧主题不一致会立刻被看出来）。
            dark_hits = [h for h in hits if "dark" in os.path.basename(h).lower()]
            if dark_hits:
                return dark_hits[-1]
            return None
        light_hits = [h for h in hits if "dark" not in os.path.basename(h).lower()]
        return (light_hits or hits)[-1]
    return None


def proto_frame_for(shot: str) -> str | None:
    p = os.path.join(REPO, PROTO_SHOTS, f"{shot}.png")
    return p if os.path.exists(p) else None


def magick(args: list) -> None:
    r = subprocess.run(["magick", *args], capture_output=True, text=True)
    if r.returncode != 0:
        print(f"[magick] FAIL {' '.join(args[:6])}…: {r.stderr.strip()[:200]}", file=sys.stderr)


def make_pair(proto: str, code: str, out: str) -> bool:
    """并排：左绿原型 / 右棕代码，等高 x900（r2-verification.md:60 的配方）。"""
    if not (os.path.exists(proto) and os.path.exists(code)):
        return False
    magick([proto, "-resize", "x900", "-bordercolor", "green", "-border", "3",
            code, "-resize", "x900", "-bordercolor", "saddlebrown", "-border", "3",
            "+append", out])
    return True


def make_sheet(pair_paths: list, out: str, cols: int = 2) -> None:
    if not pair_paths:
        return
    # 两列网格：每行左右两张并排图，图间留白；行高统一 480 便于横向扫读。
    tile = []
    for p in pair_paths[:24]:  # 一张 sheet 塞 24 对是扫读上限，多了说明该拆域
        tile += [p, "-resize", "x480"]
    args = ["montage", *tile, "-tile", f"{cols}x", "-geometry", "+6+6", "-background", "#DDDDDD", out]
    magick(args)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--list", action="store_true", help="只打印配对清单")
    ap.add_argument("--domain", default=None, help="只出一个域（conversations/chat/nodes/cabinet/agents/settings/remote/tray/misc）")
    ap.add_argument("--screen", default=None, help="只出一屏（屏 id）")
    ap.add_argument("--out", default=OUT, help="产物目录")
    args = ap.parse_args()

    rows = parse_ledger(os.path.join(REPO, LEDGER))
    if args.screen:
        rows = [r for r in rows if r[0] == args.screen]

    if args.list:
        for screen_id, label, shots, previews in rows:
            if not shots or not previews:
                continue
            print(f"{screen_id:22s} {label:12s} shots={len(shots)} previews={len(previews)}")
        return 0

    os.makedirs(os.path.join(REPO, args.out, "pairs"), exist_ok=True)
    os.makedirs(os.path.join(REPO, args.out, "sheets"), exist_ok=True)
    by_domain: dict = {}
    missing = []
    for screen_id, label, shots, previews in rows:
        if args.domain and domain_of(screen_id) != args.domain:
            continue
        pairs_made = []
        for shot in shots:
            proto = proto_frame_for(shot)
            if proto is None:
                continue
            for fun in previews:
                code = code_frame_for(fun, want_dark=is_dark_shot(shot))
                if code is None:
                    missing.append(f"{screen_id}: 帧函数 {fun} 找不到渲染图"
                                   + ("（深色档无 Dark 命名的变体帧）" if is_dark_shot(shot) else ""))
                    continue
                out = os.path.join(args.out, "pairs", f"{screen_id}__{shot}__{fun}.png")
                if make_pair(proto, code, out):
                    pairs_made.append(out)
        if pairs_made:
            by_domain.setdefault(domain_of(screen_id), []).extend(pairs_made)

    for domain, paths in sorted(by_domain.items()):
        sheet = os.path.join(args.out, "sheets", f"{domain}.png")
        make_sheet(paths, sheet)
        print(f"[sheet] {domain}: {len(paths)} 对 → {sheet}")

    if missing:
        print(f"[缺帧] {len(missing)} 条（预览函数名对不上渲染产物，多为 Popup/宿主轨登记行）：")
        for m in sorted(set(missing))[:12]:
            print(f"  · {m}")
    total = sum(len(v) for v in by_domain.values())
    print(f"[done] 并排图 {total} 张，域 sheet {len(by_domain)} 张，产物在 {args.out}/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
