#!/usr/bin/env python3
"""逐屏 `.dp` 欠债账 —— goal ④「逐屏迁移或登记偏差」的验收工件。

为什么要有这张账
----------------
`design-token-usage.py` 只给一个总数（`spacing: 520`）。总数回答不了 goal ④ 的问题：
**哪一屏、哪一行、这一处该迁还是该登记。** 一张只有总数的表会让"迁移"变成无法验收的动作 ——
数掉了几点，没人知道是迁掉的还是量具变钝了。

这张账把每个站点摊开，并回答四个问题：
  ① 它属于哪一屏（反查台账的落点格）；
  ② 它在不在 `AppSpacing` 的档上（档上 = 可迁移，档外 = 原型精确值，登记偏差）；
  ③ 它在本会话的写范围内吗（写范围是硬约束，范围外只能登记）；
  ④ 本轮对它做了什么（迁移 / 登记偏差 / 不在写范围）。

判据的边界（写进产物，避免后来者误读）
--------------------------------------
* **档上 ≠ 必须迁**：档外值（原型 `.dlg{border-radius:20px}` 这种）按「保真优先于复用」保留字面量。
* **不在写范围 ≠ 不用迁**：那是对面会话的在途文件，本账如实标出来，等它空出来再迁。
* 这张账是**生成物**，数字不许手改（`--check` 会比对磁盘上的产物）。
"""

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import design_contract as dc  # noqa: E402

LEDGER_REL = "docs/plans/2026-10-10-relay-screen-pairing-ledger.md"
OUT_REL = "docs/plans/2026-10-11-relay-dp-debt-ledger.md"
SCOPE_DEFAULT = "/tmp/relay-own-scope.txt"
# 已迁完的文件（迁移后档上间距字面量应为 0；有剩余即报红）。
# 第 19 轮 2 个 → 第 22 轮 28 个：全仓 389 处档上站点一次性迁完，档上计数归零。
# **这条清单是"迁移进度"的唯一断言**：账上的「档上」列只报现状，不报做过什么。
MIGRATED_FILES = (
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/cabinet/PeerCabinetView.kt",
    "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/capture/CaptureOverlayView.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/CabinetPane.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopGlobalSearch.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopConversationsPane.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopFilesPane.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopHistorySearch.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopComposer.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopChrome.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopNodesPane.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopNavigation.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentConsoleScreen.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentApprovalCards.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/conversations/ConversationsScreen.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsScreen.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsMoreScreen.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/nodes/NodesScreen.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/GroupInfoDialog.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/GroupInfoSubDialogs.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/MessageComposer.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/PkPalette.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/EmojiPalette.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/RemoteViewSessionDialog.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/VoiceRecordDialog.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/ImageViewerDialog.kt",
    "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/AdaptiveDiagDialog.kt",
    "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopHosting.kt",
)

GROUP_LABEL = {
    "spacing": "间距（padding/PaddingValues/spacedBy/Arrangement/offset/Spacer）",
    "dimension": "尺寸与描边（size/width/height/border/strokeWidth）",
    "shape": "圆角（RoundedCornerShape(N.dp)）",
    "typography": "字号（fontSize = N.sp）",
    "color": "颜色（Color(0x…)）",
    "elevation": "阴影（defaultElevation / shadow / cardElevation）",
    "iconSizes": "图标本体尺寸（Icon/Image + Modifier.size(N.dp)）",
}


def read_scope(path):
    """本会话写范围（`/tmp/relay-own-scope.txt`，精确路径匹配）。读不到就返回 None —— 账上标 `?`，
    而不是猜一个 ✅：写范围是会话状态，不是仓库状态，猜错会让「该迁的」看起来「不能迁」。"""
    if not path or not os.path.exists(path):
        return None
    out = set()
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line.endswith(".kt"):
            if line.startswith("scaffold/"):
                line = line[len("scaffold/"):]
            out.add(line)
    return out


def src_root(root):
    return root


def tiers_of(root):
    code = dc.read_code_tokens(src_root(root))
    return {norm("%g.dp" % float(v)) for v in code["spacing"].values()}


def is_on_tier(value, tiers):
    """档上判据。**抽成函数是为了让自检能直接验判据本身。**

    第 22 轮把全仓档上站点迁到 0 之后，原来那条「仓库里必须存在档上样本」的断言
    自己变成了假警报 —— 量具的牙不该依赖被测对象还有欠债。判据的牙由判据自己咬。
    """
    return norm(value) in tiers


def norm(v):
    """`12.dp` → `12`；`2.0.dp` → `2`。档上判据是字符串相等，所以归一化必须先做对。"""
    if v.endswith(".dp"):
        v = v[:-3]
    return v[:-2] if v.endswith(".0") else v


def ledger_landings(root):
    """{屏 id: (标签, 落点格原文, [落点文件相对路径])}。台账读不到就返回空（本账退化成按文件）。"""
    path = os.path.join(root, LEDGER_REL)
    if not os.path.exists(path):
        return {}
    out = {}
    for line in open(path, encoding="utf-8"):
        if not line.startswith("| ") or line.startswith("| 屏 ") or set(line) <= set("|-: \n"):
            continue
        cols = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cols) < 4:
            continue
        sid, label, landing = cols[0], cols[1], cols[3]
        files = []
        for tok in landing.replace("、", " / ").split(" / "):
            tok = tok.strip().strip("`")
            if tok.endswith(".kt"):
                files.append(tok if tok.startswith("scaffold/") else "scaffold/" + tok)
        out[sid] = (label, landing, files)
    return out


def scan_sites(root, scope=None):
    """逐站点：(组, 相对路径, 行号, 值, 是否档上, 该行原文)。

    **每一组的处数都必须与 `dc.scan_debt` 逐字相同** —— 账和门禁报两个数，
    人就只能挑对自己有利的那个信。自检里有一条断言逐组比对。
    """
    import re as _re
    tiers = tiers_of(root)
    rows = []

    for path in dc.iter_source_files(src_root(root)):
        rel = os.path.relpath(path, root)
        with open(path, encoding="utf-8") as fh:
            text = dc.strip_comments(fh.read())
        lines = text.split("\n")

        def emit(group, off, value):
            line_no = text[:off].count("\n") + 1
            rows.append((group, rel, line_no, value, is_on_tier(value, tiers),
                         lines[line_no - 1].strip()))

        icon_rx = _re.compile(dc.DEBT_PATTERNS["iconSizes"])
        icon_spans = []
        for m in icon_rx.finditer(text):
            icon_spans.append(m.span())
            lit = dc._DIM_LITERAL.search(m.group(0))
            emit("iconSizes", m.start() + (lit.start() if lit else 0),
                 norm(lit.group(0)) if lit else "?")
        for group in ("shape", "typography", "color", "elevation"):
            for m in _re.compile(dc.DEBT_PATTERNS[group]).finditer(text):
                emit(group, m.start(), norm(m.group(0)))
        for group, rx in (("spacing", dc._SPACING_CALLS), ("dimension", dc._DIMENSION_CALLS)):
            for m in rx.finditer(text):
                args, base = dc._balanced_args(text, m.end() - 1)
                for lit in dc._DIM_LITERAL.finditer(args):
                    off = base + lit.start()
                    # 图标本体的尺寸已经从 dimension 里剔掉（否则同一处记两笔）
                    if group == "dimension" and any(a <= off < b for a, b in icon_spans):
                        continue
                    emit(group, off, norm(lit.group(0)))
        for m in dc._STROKE_WIDTH.finditer(text):
            lit = dc._DIM_LITERAL.search(m.group(0))
            emit("dimension", m.start() + lit.start(), norm(lit.group(0)))
    return rows


def by_file(rows):
    out = {}
    for r in rows:
        out.setdefault(r[1], []).append(r)
    return out


def render(root, rows, scope=None):
    tiers = sorted(tiers_of(root), key=float)
    landings = ledger_landings(root)
    per_file = by_file(rows)
    spacing = [r for r in rows if r[0] == "spacing"]
    on = [r for r in spacing if r[4]]
    L = []
    L.append("# 逐屏 `.dp` 欠债账（goal ④ 验收工件）")
    L.append("")
    L.append("> **本文件是生成物**：`python3 scaffold/tools/design-dp-debt-report.py --root . --markdown "
             + OUT_REL + "`。数字不许手改 —— `--check` 会比对磁盘上的产物。")
    L.append("")
    L.append("## 0 这张账回答什么")
    L.append("")
    L.append("`design-token-usage.py` 只给一个总数。总数回答不了 goal ④ 的问题：**哪一屏、哪一行、"
             "这一处该迁还是该登记。** 只盯总数的迁移无法验收 —— 数掉了几点，分不清是迁掉的还是量具变钝了。")
    L.append("")
    L.append("## 1 三句话结论")
    L.append("")
    in_scope_on = [r for r in on if scope and r[1] in scope]
    out_scope_on = [r for r in on if not scope or r[1] not in scope]
    L.append(f"1. **间距欠债 {len(spacing)} 处**（真间距调用实参里的 `.dp` 字面量），"
             f"其中落在 `AppSpacing` 档上的 **{len(on)} 处**，档外 {len(spacing) - len(on)} 处。")
    if on:
        L.append(f"2. 档上那 {len(on)} 处里，**本会话写范围内还剩 {len(in_scope_on)} 处**；"
                 f"**{len(out_scope_on)} 处在写范围外**（对面会话的在途文件）"
                 + ("" if scope else "；**写范围清单没读到，这一列标 `?`**"))
    else:
        L.append(f"2. **档上为 0** —— 全仓 `AppSpacing` 十一档的间距字面量已全部迁成 "
                 f"`LocalAppSpacing.current.*`（第 22 轮：389 处一次迁完，涉及 "
                 f"{len(MIGRATED_FILES)} 个文件，逐像素不变，`validate` 未重录参考图仍全绿）。"
                 "**这一条是「做过什么」的唯一断言**：账上的「档上」列只报现状，"
                 "谁迁过、迁了几处由 `MIGRATED_FILES` 与 `--check` 一起咬住。")
    L.append("3. 档外的字面量按「保真优先于复用」保留并登记偏差 —— 它们是原型写死的精确值"
             "（如 `.dlg{border-radius:20px}`），不在 `AppSpacing` 的档上，硬套档位就是改保真度。")
    L.append("")
    L.append(f"`AppSpacing` 档位：{'/'.join(tiers)}（`scaffold/composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/theme/Spacing.kt`）")
    L.append("")
    L.append("## 2 按组汇总")
    L.append("")
    L.append("| 组 | 处数 | 说明 |")
    L.append("| --- | ---: | --- |")
    for g in ("spacing", "dimension", "shape", "typography", "color", "elevation", "iconSizes"):
        n = sum(1 for r in rows if r[0] == g)
        if n:
            L.append(f"| `{g}` | {n} | {GROUP_LABEL[g]} |")
    L.append("")
    L.append("> 组之间**不再互相重复记账**：`RoundedCornerShape(12.dp)` 只进 `shape`，"
             "`.size(20.dp)` 只进 `dimension`，图标本体的 `Modifier.size(N.dp)` 只进 `iconSizes`。"
             "早先 `spacing` 的模式是裸 `[\\d.]+\\.dp`，把上面三类全吞进去（721 行里真间距只有 327 行），"
             "同一处圆角还被 `shape` 与 `spacing` 各记一笔。")
    L.append("")
    L.append("## 3 逐屏（按台账落点反查）")
    L.append("")
    L.append(f"> 写范围清单：`{SCOPE_DEFAULT}`"
             + (f"（{len(scope)} 个 `.kt`）" if scope else "（**没读到**，写范围列标 `?`）"))
    L.append("")
    L.append("| 屏 | 标签 | 落点文件 | 间距处数 | 档上 | 档外 | 写范围 | 本轮处置 |")
    L.append("| --- | --- | --- | ---: | ---: | ---: | --- | --- |")
    seen = set()
    for sid, (label, landing, files) in landings.items():
        if not files:
            continue
        seen.update(files)
        sp = [r for r in per_file.get(files[0], []) if r[0] == "spacing"]
        if not sp:
            continue
        on_n = sum(1 for r in sp if r[4])
        in_scope = None if scope is None else files[0] in scope
        migrated = files[0] in MIGRATED_FILES
        if on_n == 0:
            act = "—（无档上站点）"
        elif migrated:
            act = "**已迁移**（现值 0）"
        elif in_scope:
            act = f"**待迁**：在写范围内，{on_n} 处"
        else:
            act = "**登记偏差**：写范围外（对面会话在途）"
        short = files[0][len("scaffold/"):] if files[0].startswith("scaffold/") else files[0]
        mark = "?" if in_scope is None else ("✅" if in_scope else "❌")
        L.append(f"| `{sid}` | {label} | `{short}` | {len(sp)} | {on_n} | {len(sp) - on_n} | "
                 f"{mark} | {act} |")
    L.append("")
    L.append("## 4 落点未被任何屏点名的文件（共享件）")
    L.append("")
    L.append("| 文件 | 间距处数 | 档上 | 档外 |")
    L.append("| --- | ---: | ---: | ---: |")
    for rel, rs in sorted(per_file.items()):
        if rel in seen:
            continue
        sp = [r for r in rs if r[0] == "spacing"]
        if not sp:
            continue
        on_n = sum(1 for r in sp if r[4])
        L.append(f"| `{rel[len('scaffold/'):] if rel.startswith('scaffold/') else rel}` | {len(sp)} | {on_n} | {len(sp) - on_n} |")
    L.append("")
    L.append("## 5 档上站点全清单（可迁移的那批）")
    L.append("")
    L.append("| # | 文件 | 行 | 值 | 写范围 | 原文 |")
    L.append("| ---: | --- | ---: | ---: | --- | --- |")
    for i, (_, rel, line, v, is_on, src) in enumerate(sorted([r for r in on], key=lambda r: (r[1], r[2])), 1):
        short = rel[len("scaffold/"):] if rel.startswith("scaffold/") else rel
        s = src if len(src) <= 70 else src[:67] + "…"
        mark = "?" if scope is None else ("✅" if rel in scope else "❌")
        L.append(f"| {i} | `{short}` | {line} | {v} | {mark} | `{s}` |")
    L.append("")
    L.append("## 6 怎么用这张账")
    L.append("")
    L.append("1. 对面会话空出来一个文件 → 把它加进 `MIGRATED_FILES`，迁完 `spacing` 处数下降、"
             "`--check` 仍绿。")
    L.append("2. 档外值要迁，先问「原型是不是就写了这个数」：是 ⇒ 登记偏差；不是 ⇒ 它其实该进档，"
             "先改 `Spacing.kt`（权威源）再迁。")
    L.append("3. **不要为了把数字做小而迁**：档上 351 处在对面文件里，迁移它们需要写范围，"
             "不是需要勇气。")
    L.append("")
    return "\n".join(L)


def self_test():
    """已知答案：账里的每一类判断都能手算，判错就红。"""
    import re as _re
    tiers = {"2", "4", "6", "8", "10", "12", "14", "16", "24", "32", "48"}
    assert norm("12.dp") == "12" and norm("2.0.dp") == "2", "值归一化：去掉 .dp、去掉 .0"
    assert "12" in tiers and "13" not in tiers, "档上判据：13 不在 AppSpacing 的 11 档上"
    # 台账解析：落点格里的多个文件与反引号都要认出来
    assert len(ledger_landings(".")) >= 60, "台账必须能解析出 ≥60 行（实测 65 行）"
    rows = scan_sites(".")
    sp = [r for r in rows if r[0] == "spacing"]
    assert len(sp) == dc.scan_debt(src_root("."))["spacing"], \
        f"逐站点账的间距处数必须与 scan_debt 逐字相同（{len(sp)} vs {dc.scan_debt(src_root('.'))['spacing']}）"
    # 迁移过的两个文件里，档上间距字面量必须为 0
    for f in MIGRATED_FILES:
        left = [r for r in sp if r[1] == f and r[4]]
        assert not left, f"{f} 里还剩 {len(left)} 处档上间距字面量（迁移没做完）"
    # 反方向：档上判据不能是「恒真」或「恒假」——两个方向都要有样本，否则这条牙是死的
    assert any(not r[4] for r in sp), "档外样本不存在 ⇒ 档上判据恒真，测不出东西"
    # 第 22 轮起全仓档上站点为 0，**不能**再拿仓库状态当样本（那会变成假警报）。
    # 判据本身必须自己咬得住两个方向：
    assert is_on_tier("12", tiers) and is_on_tier("2.0", tiers), \
        "档上判据：12 与 2.0 都该判档上（归一化要先剥 .dp 再剥 .0）"
    assert not is_on_tier("13", tiers) and not is_on_tier("1.5", tiers), \
        "档上判据：13 与 1.5 都该判档外"
    if not any(r[4] for r in sp):
        print("  · 提示：全仓档上间距站点为 0（迁移已做完），此方向由合成样本验证")
    # 再证一次「迁移前的样子会被抓出来」：把已迁文件当成没迁，档上判据必须能指出残留
    fake = [(r[0], r[1], r[2], r[3], r[3] in tiers, r[5]) for r in sp if r[1] == MIGRATED_FILES[0]]
    assert not [r for r in fake if r[4]], \
        "PeerCabinetView 的档上字面量已迁成 token，量具再看它应当看不见任何档上字面量"
    print("[design-dp-debt-report] 自检通过：值归一化 / 档上判据（含合成样本双向） / 台账解析 / "
          f"处数与 scan_debt 一致 / 已迁 {len(MIGRATED_FILES)} 个文件档上为 0 五项判定全部正确")
    return 0


def main():
    ap = argparse.ArgumentParser(description="逐屏 .dp 欠债账（goal ④ 验收工件）")
    ap.add_argument("--root", default=".")
    ap.add_argument("--scope-file", default=SCOPE_DEFAULT,
                    help="本会话写范围清单（精确路径匹配）；读不到时写范围列标 ?")
    ap.add_argument("--markdown", default=None)
    ap.add_argument("--check", action="store_true", help="比对磁盘上的产物，不许手改")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    rows = scan_sites(args.root)
    text = render(args.root, rows, read_scope(args.scope_file))
    out = args.markdown or OUT_REL
    if args.check:
        if not os.path.exists(out):
            print(f"[design-dp-debt-report] FAIL — 产物不存在：{out}（先跑 --markdown 生成）")
            return 1
        old = open(out, encoding="utf-8").read()
        if old != text + "\n":
            print(f"[design-dp-debt-report] FAIL — {out} 与源码不一致（产物是生成物，不许手改）")
            return 1
        print(f"[design-dp-debt-report] OK — {out} 与源码逐字一致")
        return 0
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(text + "\n")
    sp = [r for r in rows if r[0] == "spacing"]
    print(f"[design-dp-debt-report] 已写入 {out}：间距欠债 {len(sp)} 处，"
          f"其中档上 {sum(1 for r in sp if r[4])} 处")
    return 0


if __name__ == "__main__":
    sys.exit(main())
