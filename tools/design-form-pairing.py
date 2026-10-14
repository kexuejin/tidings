#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""台账配对**形态**对账（屏的形态 ↔ 帧的形态）。

为什么需要它
------------
仓里已经有一把 `design-preview-pairing.py` 在看"台账的『现有 Preview』列里的帧名
是否真的存在、是否跨域"，但它**只看名字与落点**，不看**这张帧拍出来是什么形态**。
实测后果（2026-10-12，Lead 手工审计）：

    65 行台账里有 **5 行**是「桌面屏（`d-`）却只配了手机帧」，而 5 行的核对列都写着 ✅：

    d-reqscreen      (shot d30-reqscreen, reference 2304×1600)
    d-sharescreen    (shot d31-sharescreen, 2304×1600)
    d-viewscreen     (shot d32-viewscreen, 2304×1600)
        三行都填 RemoteViewConsentPhonePreview, RemoteViewSharingPhonePreview = 393×852
    d-settings-data  (shot d11b-settings-data, reference 1544×1280)
        填 SettingsMoreStoragePhonePreview, … = 393×1600
    d-emoji          (shot d17-emoji, reference 2304×1600)
        填 ChatEmojiPhonePreview

而代码侧这五屏**都有**桌面形态（`ui/desktop/DesktopShell.kt:547` 把
`RemoteViewSessionDialog` 画在桌面壳最外层；`desktopApp/.../DesktopHosting.kt:591`
逐字 `DesktopSettingsNav.DataStorage -> { SettingsDataSubpage(`；
`ui/desktop/DesktopComposer.kt:139/202/405` 的表情面板只缺一个打开接缝）。
⇒ **不是"没实现"，是"没证据"** —— 和 `m-cabinet-peer` 的「0 处」、`m-insets` 的假绿
同族：配对层拿了一张**别的形态**的帧去顶，并排板不开图看不出来。

它查三件事
----------
1. `unknown` —— 台账「现有 Preview」列里的帧名**在截图源集里找不到**。
   这一族本轮真发生过三次：Lead 自己写了 `DeskCabinetPanePeerPreview + Desk…`
   （用 `+` 分隔，工具只认逗号）、写了树里不存在的 `AddNodeSheetNamedPhonePreview`
   （W2 报告声称新建了、实际没有），以及 W2 把 `NodesRowMenuPhonePreview` 删掉后
   台账还留着名字。**报告与树的偏差，量具不会报；台账与树的偏差，这一查会报。**
2. `form` —— `d-` 开头的行**至少要有一张桌面宽度（> 600dp）的帧**；
   `m-` 开头的行**至少要有一张 ≤ 600dp 的帧**。没有帧的行（登记在案的缺口，
   例如 `d-tray`/`d-drop` 走宿主面、`m-msgmenu` 的 Popup 拍不出来）**不算违规**，
   但会单独报出来，免得"没有帧"和"形态不对"混在一起。
3. 汇总 —— 帧名解析出几个、其中多少张是桌面形态、多少行登记为无帧。

口径与坑（都是实测踩过的）
--------------------------
* 台账的「现有 Preview」列写的是**函数名**（`CabinetMinePhonePreview`），
  而 `@Preview(name = …)` 里是 **kebab 帧名**（`cabinet-mine-phone`）。两者不是一个
  东西 —— 按帧名去查会全查不到。
* `@Preview(…)` 与 `fun` 之间还有 `@Composable`、`@PreviewTest` 和 `private ` 修饰符，
  解析要放行它们。
* 台账那一列里大量出现**括号注释**（`…Preview（**生产形状档**：\`X.kt:451\` 传了
  \`onConnectNamed = { _,_,_ -> }\`）`），里面**含 ASCII 逗号** ⇒ 必须**先剥平衡括号
  再按逗号切**，否则会把注释里的参数切出来当帧名。
* 尺寸读 `widthDp`/`heightDp`；只有 `device = "spec:width=…dp,height=…dp"` 时从
  spec 里回退解析。两处都没有的帧记为"尺寸未知"，不参与形态判定但会被点名。

棘轮口径
--------
违规是**集合棘轮**，键是 `<屏id>|<kind>`：基线里记的是键集合，`--check` 只报
「比基线**新出现**的键」。修好一行 = 集合变小 = 永远不红；新配错一行 = 立刻红。

用法
----
    python3 scaffold/tools/design-form-pairing.py --root .            # 只读报告
    python3 scaffold/tools/design-form-pairing.py --root . --check    # 门禁
    python3 scaffold/tools/design-form-pairing.py --root . --baseline # 有意接受后记账
    python3 scaffold/tools/design-form-pairing.py --self-test
"""
import argparse
import datetime
import json
import os
import re
import sys

# 与 `design-preview-pairing.py` 同口径：`--root` 是**仓库根**（默认 `.`）。
LEDGER = "docs/plans/2026-10-10-relay-screen-pairing-ledger.md"
SHOTS = "androidApp/src/screenshotTest/kotlin"
BASELINE = "tools/design-form-pairing-baseline.json"

# 桌面形态的下界。原型里手机帧一律 393/890 宽，桌面帧一律 900/1100/1544/2304 宽，
# 中间没有含糊地带。
DESKTOP_MIN_DP = 600

_OPEN = {"(": ")", "（": "）"}
_CLOSE = {")", "）"}
_PREVIEW_ANNOT = re.compile(r"@Preview\s*\(")
_FUN = re.compile(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)")
_ATTR_W = re.compile(r"widthDp\s*=\s*(\d+)")
_ATTR_H = re.compile(r"heightDp\s*=\s*(\d+)")
_DEVICE = re.compile(r'device\s*=\s*"([^"]*)"')
_SPEC_W = re.compile(r"width\s*=\s*(\d+)dp")
_SPEC_H = re.compile(r"height\s*=\s*(\d+)dp")
_FUNC_NAME = re.compile(r"^[A-Z][A-Za-z0-9_]*$")


def strip_parens(s):
    """剥掉所有（配平的）括号及其内容，两种括号都算。

    必须配平：台账注释里会出现 `…（**生产形状档**：传了 `f = { _,_,_ -> }`）` 这种
    带 ASCII 逗号的括号块，先按逗号切就会把参数切出来当帧名。
    """
    out = []
    depth = 0
    for ch in s:
        if ch in _OPEN:
            depth += 1
            continue
        if ch in _CLOSE:
            depth = max(0, depth - 1)
            continue
        if depth == 0:
            out.append(ch)
    return "".join(out)


def _close_paren(s, i):
    """`s[i]` 是 `(`，返回配对的 `)` 下标；找不到返回 -1。"""
    depth = 0
    while i < len(s):
        if s[i] == "(":
            depth += 1
        elif s[i] == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def parse_preview_source(text, fname="<mem>"):
    """从一个截图源文件里抽出每个 `@Preview` 的 (函数名, 宽, 高, 行号)。

    宽/高可能为 None（只给了 `device=` 且 spec 里没有，或两样都没给）。
    """
    found = []
    pos = 0
    while True:
        m = _PREVIEW_ANNOT.search(text, pos)
        if not m:
            break
        body_start = m.end() - 1  # 指向 '('
        body_end = _close_paren(text, body_start)
        if body_end < 0:
            break
        body = text[body_start + 1:body_end]
        # 注解散了之后，向后找挂着的 `fun`；中间若又出现一个 @Preview( 就说明这个
        # 注解没有挂到任何函数上（不该发生），停止。
        window = text[body_end + 1:body_end + 1 + 400]
        nxt = _PREVIEW_ANNOT.search(window)
        if nxt:
            window = window[:nxt.start()]
        fm = _FUN.search(window)
        pos = body_end + 1
        if not fm:
            continue
        name = fm.group(1)
        w = _ATTR_W.search(body)
        h = _ATTR_H.search(body)
        if not (w and h):
            dev = _DEVICE.search(body)
            if dev:
                sw = _SPEC_W.search(dev.group(1))
                sh = _SPEC_H.search(dev.group(1))
                if sw and not w:
                    w = sw
                if sh and not h:
                    h = sh
        line = text.count("\n", 0, m.start()) + 1
        found.append({
            "name": name,
            "w": int(w.group(1)) if w else None,
            "h": int(h.group(1)) if h else None,
            "file": fname,
            "line": line,
        })
    return found


def collect_previews(root):
    """读截图源集下所有 `*.kt`，返回 (name → 记录, 尺寸未知的名单)。"""
    d = os.path.join(root, SHOTS)
    by_name = {}
    unknown = []
    dup = []
    if not os.path.isdir(d):
        return by_name, unknown, dup
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(d, fn)
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        for rec in parse_preview_source(text, fn):
            if rec["name"] in by_name:
                dup.append(rec["name"])
                continue
            by_name[rec["name"]] = rec
            if rec["w"] is None or rec["h"] is None:
                unknown.append(rec)
    return by_name, unknown, dup


def parse_ledger(path):
    """返回 [{id, previews_cell, names, line}]，只认 8 列的屏行。"""
    rows = []
    with open(path, encoding="utf-8") as fh:
        for i, raw in enumerate(fh.read().split("\n"), 1):
            if not raw.startswith("|"):
                continue
            cells = [c.strip() for c in raw.strip().strip("|").split("|")]
            if len(cells) != 8:
                continue
            if not re.match(r"^[a-z0-9][a-z0-9-]*$", cells[0]):
                continue
            names = []
            for tok in strip_parens(cells[4]).split(","):
                tok = tok.strip().strip("`* ")
                if _FUNC_NAME.match(tok):
                    names.append(tok)
            rows.append({"id": cells[0], "cell": cells[4], "names": names, "line": i})
    return rows


def audit(root):
    """跑三查，返回 (违规 dict, 汇总 dict)。"""
    ledger_path = os.path.join(root, LEDGER)
    if not os.path.isfile(ledger_path):
        raise SystemExit(f"找不到台账：{LEDGER}（--root 要给仓库根）")
    previews, unknown, dup = collect_previews(root)
    rows = parse_ledger(ledger_path)

    violations = {}
    no_frame = []
    desktop_frames = sum(
        1 for r in previews.values()
        if r["w"] is not None and r["w"] > DESKTOP_MIN_DP
    )
    used = set()
    for row in rows:
        resolved = []
        for name in row["names"]:
            if name not in previews:
                violations[f"{row['id']}|unknown"] = (
                    f"台账第 {row['line']} 行：`{name}` 在 {SHOTS}/ 里找不到 —— "
                    f"要么名字写错/删了没同步，要么它根本不是预览函数名"
                )
                continue
            used.add(name)
            resolved.append(previews[name])
        if not resolved:
            no_frame.append(row["id"])
            continue
        wide = [r for r in resolved if r["w"] is not None and r["w"] > DESKTOP_MIN_DP]
        narrow = [r for r in resolved if r["w"] is not None and r["w"] <= DESKTOP_MIN_DP]
        if row["id"].startswith("d-") and not wide:
            sizes = ", ".join(
                f"{r['name']}={r['w'] or '?'}×{r['h'] or '?'}" for r in resolved
            )
            violations[f"{row['id']}|form"] = (
                f"台账第 {row['line']} 行：`d-` 开头（桌面屏）却只有手机形态的帧"
                f"（{sizes}）—— 桌面屏至少要有一张 >{DESKTOP_MIN_DP}dp 宽的帧"
            )
        if row["id"].startswith("m-") and not narrow:
            sizes = ", ".join(
                f"{r['name']}={r['w'] or '?'}×{r['h'] or '?'}" for r in resolved
            )
            violations[f"{row['id']}|form"] = (
                f"台账第 {row['line']} 行：`m-` 开头（手机屏）却只有桌面形态的帧"
                f"（{sizes}）—— 手机屏至少要有一张 <={DESKTOP_MIN_DP}dp 宽的帧"
            )

    summary = {
        "rows": len(rows),
        "previews": len(previews),
        "desktopFrames": desktop_frames,
        "phoneFrames": len(previews) - desktop_frames,
        "usedFrames": len(used),
        "noFrameRows": no_frame,
        "sizeUnknown": [f"{r['name']}（{r['file']}:{r['line']}）" for r in unknown],
        "duplicateNames": sorted(set(dup)),
    }
    return violations, summary


def self_test():
    """合成样本双向自检：判据、解析、括号剥离、形态分类。"""
    fails = []

    def chk(cond, what):
        if not cond:
            fails.append(what)

    # 1) 括号剥离（含嵌套与含 ASCII 逗号的括号块）
    chk(strip_parens("A（x,y）,B（z）") == "A,B", "剥括号：含逗号括号块")
    chk(strip_parens("A（x（y）z）,B") == "A,B", "剥括号：嵌套")
    chk(strip_parens("A,B") == "A,B", "剥括号：无括号不动")
    chk(
        strip_parens("AddNodeSheetFilledPhonePreview（传了 `f = { _,_,_ -> }`）,X") ==
        "AddNodeSheetFilledPhonePreview,X",
        "剥括号：`{ _,_,_ -> }` 里的逗号不许漏出来",
    )

    # 2) @Preview 解析：单行 / 多行 / 修饰符 / device spec
    src = (
        '@Preview(name = "a", widthDp = 393, heightDp = 852, locale = "zh-rCN")\n'
        "@PreviewTest\n"
        "@Composable\n"
        "private fun PhonePreview() {}\n"
        "\n"
        "@Preview(\n"
        '    name = "b",\n'
        "    widthDp = 1100,\n"
        "    heightDp = 720,\n"
        ")\n"
        "@Composable\n"
        "internal fun DeskPreview() {}\n"
        "\n"
        '@Preview(name = "c", device = "spec:width=1280dp,height=800dp")\n'
        "@Composable\n"
        "fun SpecPreview() {}\n"
    )
    recs = {r["name"]: r for r in parse_preview_source(src)}
    chk(set(recs) == {"PhonePreview", "DeskPreview", "SpecPreview"},
        f"解析：三个帧都要抽到，实得 {sorted(recs)}")
    chk(recs["PhonePreview"]["w"] == 393 and recs["PhonePreview"]["h"] == 852,
        "解析：单行 widthDp/heightDp")
    chk(recs["DeskPreview"]["w"] == 1100 and recs["DeskPreview"]["h"] == 720,
        "解析：多行 + @Composable + internal 修饰符")
    chk(recs["SpecPreview"]["w"] == 1280 and recs["SpecPreview"]["h"] == 800,
        "解析：device spec 回退")

    # 3) 台账行解析：8 列、逗号分隔、括号注释、非屏行要跳过
    led = (
        "| 屏 | 标签 | 原型截图 | 代码落点 | 现有 Preview | 验收档 | Preview 核对 | 相对 HEAD 已改 |\n"
        "| --- | --- | --- | --- | --- | --- | --- | --- |\n"
        "| d-x | 桌面屏 | `d01-x` | `ui/desktop/X.kt` | "
        "PhonePreview（**新帧**）, DeskPreview（传了 `f = { _,_,_ -> }`） | A | ✅ | — |\n"
        "| m-y | 手机屏 | `m01-y` | `feature/y/Y.kt` | —（全稿无帧；Popup 不可拍） | B | ❌ | — |\n"
    )
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False,
                                     encoding="utf-8") as fh:
        fh.write(led)
        tmp = fh.name
    try:
        rows = parse_ledger(tmp)
    finally:
        os.unlink(tmp)
    chk(len(rows) == 2, f"台账：只认 8 列屏行（表头与分隔行要跳过），实得 {len(rows)}")
    chk(rows[0]["names"] == ["PhonePreview", "DeskPreview"],
        f"台账：逗号分隔 + 剥括号，实得 {rows[0]['names']}")
    chk(rows[1]["names"] == [], f"台账：`—（…）` 应解析成 0 个帧名，实得 {rows[1]['names']}")

    # 4) 形态分类（判据本身，双向）
    def verdict(screen_id, frames):
        wide = [f for f in frames if f[0] is not None and f[0] > DESKTOP_MIN_DP]
        narrow = [f for f in frames if f[0] is not None and f[0] <= DESKTOP_MIN_DP]
        if screen_id.startswith("d-"):
            return "ok" if wide else "form"
        return "ok" if narrow else "form"

    chk(verdict("d-a", [(393, 852)]) == "form", "判据：桌面屏只给手机帧 = 违规")
    chk(verdict("d-a", [(393, 852), (1100, 720)]) == "ok", "判据：桌面屏有桌面帧 = 通过")
    chk(verdict("d-a", [(2304, 1600)]) == "ok", "判据：桌面屏给宽帧 = 通过")
    chk(verdict("m-a", [(1100, 720)]) == "form", "判据：手机屏只给桌面帧 = 违规")
    chk(verdict("m-a", [(393, 852)]) == "ok", "判据：手机屏给手机帧 = 通过")
    chk(verdict("d-a", [(None, None)]) == "form", "判据：尺寸未知不构成形态证据")

    if fails:
        print("FAIL — 量具自检失败 %d 项：" % len(fails), file=sys.stderr)
        for f in fails:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("OK — 量具自检通过（剥括号 4 项 / @Preview 解析 5 项 / 台账解析 3 项 / "
          "形态判据 6 项）")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--root", default=".", help="仓库根（默认 .）")
    ap.add_argument("--check", action="store_true", help="有比基线新的违规就返回 1")
    ap.add_argument("--baseline", action="store_true", help="把当前违规记成棘轮基线")
    ap.add_argument("--reason", help="重记基线时若比旧基线更差，必须给理由")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = args.root
    try:
        violations, summary = audit(root)
    except SystemExit as exc:
        print(f"FAIL — {exc}", file=sys.stderr)
        return 2

    print(f"台账 {LEDGER}：{summary['rows']} 屏行；"
          f"截图源集 {summary['previews']} 个帧"
          f"（桌面形态 {summary['desktopFrames']} / 手机形态 {summary['phoneFrames']}），"
          f"台账引用了其中 {summary['usedFrames']} 个")
    print(f"登记为「无帧」的行 {len(summary['noFrameRows'])} 个："
          f"{', '.join(summary['noFrameRows']) or '（无）'}")
    if summary["sizeUnknown"]:
        print(f"尺寸未知的帧 {len(summary['sizeUnknown'])} 个（不参与形态判定）："
              f"{', '.join(summary['sizeUnknown'])}")
    if summary["duplicateNames"]:
        print(f"重名的帧函数 {len(summary['duplicateNames'])} 个："
              f"{', '.join(summary['duplicateNames'])}")

    current = sorted(violations)
    if not current:
        print("形态对账：0 条违规（每个 `d-` 行都有桌面帧、每个 `m-` 行都有手机帧，"
              "且台账里的帧名全部存在）")
    else:
        print(f"\n形态对账：{len(current)} 条违规")
        for k in current:
            print(f"  - [{k.split('|')[1]}] {violations[k]}")

    base_path = os.path.join(root, BASELINE)
    if args.baseline:
        old = set()
        if os.path.isfile(base_path):
            with open(base_path, encoding="utf-8") as fh:
                old = set(json.load(fh).get("violations", []))
        new = set(current)
        if new - old and not args.reason:
            print(f"FAIL — 新基线比旧基线更差（新增 {len(new - old)} 条）；"
                  f"`--baseline` 必须给 `--reason`。", file=sys.stderr)
            return 2
        payload = {
            "generatedAt": datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
            "violations": current,
            "reason": args.reason or "无新增违规",
        }
        with open(base_path, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2, sort_keys=True)
            fh.write("\n")
        print(f"基线已写入 {BASELINE}：{len(current)} 条违规")
        return 0

    if args.check:
        if not os.path.isfile(base_path):
            print(f"FAIL — 没有棘轮基线（{BASELINE}）；先 --baseline 记一次。",
                  file=sys.stderr)
            return 2
        with open(base_path, encoding="utf-8") as fh:
            old = set(json.load(fh).get("violations", []))
        fresh = sorted(set(current) - old)
        fixed = sorted(old - set(current))
        if fresh:
            print(f"FAIL — 形态对账新增 {len(fresh)} 条违规（基线 {len(old)} 条）：",
                  file=sys.stderr)
            for k in fresh:
                print(f"  - [{k.split('|')[1]}] {violations.get(k, k)}", file=sys.stderr)
            return 1
        print(f"OK — 形态对账无退化（基线 {len(old)} 条，当前 {len(current)} 条；"
              f"只许变少）")
        if fixed:
            print(f"  比基线好了 {len(fixed)} 条：{', '.join(fixed)}"
                  f" —— 建议重记基线把账收掉")
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
