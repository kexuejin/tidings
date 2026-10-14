#!/usr/bin/env python3
"""配对台账的 Preview 列有没有说谎 —— 读函数体，不看函数名。

台账的「现有 Preview」列是**按名字匹配**出来的。名字会撒谎：`SettingsBannerPhonePreview`
渲染的是手机设置屏，却被填进「设置窗·身份与连接」（桌面）。本工具把每个 `@Preview`
函数的**函数体**解析出来，取出它真正调用的屏级 Composable，再与台账那一行的「代码落点」
比对，逐条判定 本屏 / 跨域 / 函数不存在。

只读。不写台账，不改代码。
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import sys

BASELINE = "tools/design-preview-pairing-baseline.json"
# 严重度阶梯：只许降不许升。跨域以上算"说了谎"。
SEVERITY = {"本屏": 0, "无法判定": 1, "声明无帧": 2, "跨域": 2, "函数不存在": 3, "空列": 3}
LIE = 2

PREVIEW_GLOBS = (
    "androidApp/src/screenshotTest/kotlin",
    "androidApp/src/screenshotTestDebug/kotlin",
)
PREVIEW_FILE_RE = re.compile(r"^ScreenshotPreviews.*\.kt$")
# ⚠ 已知边界（**未修**，2026-10-12 由 W1 实锤，待收口批次一起改）：
# 本工具两处（`screen_functions` 与 `preview_armed`）取函数体用的都是
# `src[本帧声明之后 : 下一个 FUNC_RE 命中之前]`，**不是本帧自己的花括号**。两个后果：
#   ① 下一个函数的 KDoc（块注释）落在本帧 body 里 —— 已由 `_strip_comments` 剥块注释
#      治掉（KDoc 里的 `` `X.kt:475 FilesPane(` `` 这种**引用文本**会被 COMPOSABLE_RE
#      当成真调用，曾让量具把 `FilesPane` 的闸门算到一个根本没渲染它的帧头上）；
#   ② 下一个函数的**函数体**仍在 ⇒ **夹在两个预览之间的私有 helper** 体内的
#      Composable 调用会被算到**前一个预览**头上。实测 W1 的
#      `DeskPane(state, peerId, content)`（只有 `ChatDeskPanePreview` 一处调用）
#      让 `ChatInsetsPhonePreview` 白背了 3 条 `ChatScreen` 欠武装，而它实际只调
#      `ConversationsScreen`；W1 把 helper 内联后假阳性消失。
# **判据**：报出来的帧名与该帧实际调用的 Composable 对不上时，先去找它后面那个 helper。
# **彻底修法**：改成花括号配平取 body（本文件已有 `_match(s, i, open, close)` 可复用），
# 但那会**同时改变落点扫描** ⇒ 会让在途五组的全部证据失效，
# 必须等五组停下、与 `SectionCard` 圆角一起在收口批次做。
FUNC_RE = re.compile(r"@Preview\([^)]*\)[\s\S]{0,200}?\bfun\s+(\w+)\s*\(")
# 屏级可渲染物：XxxScreen / XxxPane / XxxDialog / XxxShell / XxxView / XxxHost / XxxWindow / XxxOverlay / XxxSubpage / XxxPage
# `Subpage` 是后补的：`SettingsProfileSubpage` / `SettingsConnectionSubpage` 是货真价实的屏级
# Composable（`SettingsScreen.kt` 里的独立子页），词表里没有它时，一个渲染它的预览会被判成
# 「函数体里没有屏级 Composable」，或更坏——只剩 `StateSlotScreen` 一个候选而被判跨域。
# `Page` 是 2026-10-12 补的，同一个洞：第 33 轮新建的 `SetAvatarPage`（`SetAvatarPage.kt` 的独立
# 整页上传指引）是屏级 Composable，词表缺 `Page` 时 `SetAvatarPagePreview` 被判「无法判定」——
# 一个画对了的帧被记成"没人知道它画什么"。
# `Chrome` / `Panel` 也是 2026-10-12 补的，同一个洞的第三、第四例：第 33 轮把设置窗外壳抽成
# `ui/desktop/DesktopSettingsChrome.kt` 的 `DesktopSettingsChrome(...)` 之后，
#   `DeskSettingsIdentityPreview` 的函数体是 `DesktopSettingsChrome(selected = …) { SettingsProfileSubpage(…) }`
#   —— 工具只认出 `SettingsProfileSubpage`，want={DesktopSettingsChrome, SettingsScreen} 无交集 ⇒ 判「跨域」；
#   `DeskSettingsDeskPreview` 的函数体是 `DesktopSettingsChrome(…) { DesktopPrefsPanel(host = …) }`
#   —— 两个名字全在词表外 ⇒ rendered=∅ ⇒ 判「无法判定」。
# 两帧都画对了，是词表看不见它们。词表漏一个后缀 = 一整族帧被判说谎，这是这个洞反复出现的形状。
# `Sheet` 是 2026-10-12 补的第五例：W2 新建的 `AddNodeSheetScanPhonePreview`（`ScreenshotPreviews.kt:845`）
# 函数体是 `AddNodeSheet(…)`，而 `AddNodeSheet` 是 `feature/nodes/NodesScreen.kt` 里的底部弹层屏级
# Composable ⇒ 词表缺 `Sheet` 时 rendered=∅，`m-addnode` 那一行被判「无法判定」。
# 实测（加后缀前后各跑一次 `--markdown` 逐行比）：**只改 1 行**
# （`m-addnode | AddNodeSheetScanPhonePreview` 无法判定 → 本屏），**零新增跨域**；
# 全仓 rendered 里以 `Sheet` 结尾的名字只有 `AddNodeSheet` 与 `MyQrSheet` 两个 ⇒ 安全。
COMPOSABLE_RE = re.compile(
    r"\b([A-Z][A-Za-z0-9]*(?:Screen|Pane|Dialog|Shell|View|Host|Window|Overlay|Bar|Subpage|Page"
    r"|Chrome|Panel|Sheet))\s*\("
)
LEDGER = "docs/plans/2026-10-10-relay-screen-pairing-ledger.md"
# 台账 Preview 列里两种**声明式**写法：它们说的是"这一屏没有代码侧帧"，
# 不是一个函数名。识别它们之前，整段中文说明会被按逗号切成好几"个"函数名，
# 于是每一段都判「函数不存在」——把一句诚实的说明判成了谎话。
NO_PREVIEW = "无本屏预览"
HOST_TRACK = "走宿主面"
# Preview 列空时的两种判定（2026-10-12 修，独立验证者发现）：
#   旧版 `ok = all(v == "本屏" for ... verdicts)` 对**空列表**恒为 True ⇒ 台账 Preview 列
#   空着的行被记 ✅，且 `collect()` 不为它产出任何条目 ⇒ 棘轮也看不见它。
#   "空过不是配对成功"：一列名字都没有的行，既没有本屏预览，也没有一句诚实声明。
DECLARED_NO_FRAME = "声明无帧"   # 格子明写「无本屏预览（原因）」或「走宿主面 B7」= 诚实声明，但不是配对证据
BLANK_CELL = "空列"              # 格子什么都没写 = 连声明都没有，按最坏记
# 台账里"这一屏没有代码侧帧"的写法不止一种（`无本屏预览（…）`、`走宿主面 B7`、
# `—（全稿无帧；…）`、`—（无帧；…已实现、未视觉覆盖）`）。只认前两种会把后两种
# 判成「空列」——把一句诚实说明当谎话，与 NO_PREVIEW 的注释里那条理由同形。
NO_FRAME_MARKERS = (NO_PREVIEW, HOST_TRACK, "全稿无帧", "无帧")
# 尾随中文/英文说明的括号。`preview_cell_names()` 先剥它再切逗号——理由里的逗号
# 不是函数名分隔符（见该函数 docstring）。
_PAREN_RE = re.compile(r"[（(][^（()）]*[)）]")


def scan_previews(root):
    """{函数名: (文件, {它渲染的屏级 Composable})}"""
    out = {}
    for rel in PREVIEW_GLOBS:
        d = os.path.join(root, rel)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if not PREVIEW_FILE_RE.match(name):
                continue
            src = open(os.path.join(d, name), encoding="utf-8").read()
            hits = list(FUNC_RE.finditer(src))
            for i, m in enumerate(hits):
                end = hits[i + 1].start() if i + 1 < len(hits) else len(src)
                body = _strip_comments(src[m.end():end])
                out[m.group(1)] = (name, set(COMPOSABLE_RE.findall(body)))
    return out


# ── 夹具欠武装（第四个量具洞的机械化） ───────────────────────────────
# 一张图是一份证据，证据要过两道关：①被测量的东西在不在（夹具/锚点回答）
# ②它有没有进入画面（画幅/视口回答）。这里机械化的是**第①道的一个分支**：
# 可空参数不传 ⇒ 默认 null ⇒ 依赖它的那一支根本不渲染。
# 实测两次：`DesktopTransferLogPane` 的 `onOpenFile`（行尾「打开」一次没拍下来）、
# `PeerCabinetView` 的 `onUploadToCabinet`（上传按钮一次没拍下来）。
# 两边的门禁都绿 —— 因为出问题的不是判据，是**夹具没把参数给全**。
APP_GLOBS = ("composeApp/src", "desktopApp/src")
SKIP_DIRS = ("/build/", "/test/", "/androidTest/", "screenshotTest")
FUN_DEF_RE = re.compile(r"fun\s+([A-Z]\w*)\s*\(")
# 调用链要用**宽**正则：`TransferRecordRow` 不以 Screen/Pane/... 结尾，
# 用 COMPOSABLE_RE 找调用会漏掉它，于是挂在它身上的闸门整条链都看不见
# （实测 `DesktopTransferLogPane → TransferRecordRow` 的 `onOpenFile` 就这么漏的）。
CALL_RE = re.compile(r"\b([A-Z]\w*)\s*\(")


def _match(s, i, open_ch, close_ch):
    """从 s[i]（应为 open_ch）出发返回配对括号之后的下标。"""
    depth, j = 0, i
    while j < len(s):
        if s[j] == open_ch:
            depth += 1
        elif s[j] == close_ch:
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    return len(s)


def _strip_comments(s):
    """去掉 `//` 行注释与 `/* … */` 块注释（含 `/** … */` KDoc），但**不动字符串字面量里**的 `//`（如 `"https://…"`）。

    为什么必须去（`//`）：静态解析器按顶层逗号切参数、再要求每一段以 `标识符 =` 开头。
    参数表里夹一段多行 `//` 注释时，注释会跟**后面那个参数**粘成同一段，于是
    那个参数匹配不到 `x =`，被算成"没传"——作者写了一句解释，量具就报一处假的
    「夹具欠武装」。注释写在参数表里是完全正常的 Kotlin 写法，
    **修法方向是让量具学会它，不是让作者别写注释。**

    为什么还必须去块注释（**第 61 个洞**，2026-10-12 Lead 实锤）：逐帧取 body 用的是
    `src[本帧声明之后 : 下一个函数声明之前]`，**下一个函数的 KDoc 整段落在本帧 body 里**。
    KDoc 是块注释，旧实现只认 `//` ⇒ KDoc 原样留下，里面的 `` `DesktopShell.kt:475 FilesPane(` ``
    这种**引用文本**会被 `COMPOSABLE_RE` 命中，于是量具把 `FilesPane` 的可空闸门算到
    一个**根本没渲染它**的帧头上，报出两条假阳
    （`DeskConversationsPaneNoMatchPreview|FilesPane|onCancelTransfer` / `|onResumeTransfer`）。
    实证：该帧 body 里 `FilesPane` 的命中上下文逐字是 KDoc 文本
    「直调 [FilesPane]，Expanded 双栏。… `DesktopShell.kt:475 FilesPane(` 的 `:488 onResumeTransfer = …`」；
    而 `ConversationsPane` 签名里**没有**这两个参数、`FilesPane(` 全仓只在 `ui/desktop/DesktopShell.kt:475` 被调一次。
    """
    out, i, n = [], 0, len(s)
    in_str = False
    while i < n:
        ch = s[i]
        if in_str:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(s[i + 1])
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n and s[i + 1] == "*":
            # 块注释（含 /** … */ KDoc）：跨行，必须整体吃掉，否则里面的引用文本
            # （如 `` `DesktopShell.kt:475 FilesPane(` ``）会被 COMPOSABLE_RE 当成真调用。
            j = s.find("*/", i + 2)
            if j < 0:
                break
            out.append(" ")
            i = j + 2
            continue
        if ch == "/" and i + 1 < n and s[i + 1] == "/":
            j = s.find("\n", i)
            if j < 0:
                break
            out.append("\n")
            i = j + 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _split_top(s):
    """按顶层逗号切开（不切进括号/尖括号里）。

    坑：Kotlin 的箭头 `->` 里那个 `>` 会被当成尖括号的收尾，深度一路减到负数，
    后面所有顶层逗号都不再算顶层 —— 于是整个参数表只切出一条。
    """
    s = s.replace("->", "\u0001")
    out, depth, cur = [], 0, []
    for ch in s:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if cur:
        out.append("".join(cur))
    return ["".join(x).replace("\u0001", "->") for x in out]


def scan_composables(root):
    """{函数名: {"gates": 会挡渲染的可空参数, "params": 声明的全部参数, "calls": 它调用的函数}}。

    只有**在函数体里真的被当闸门用**的可空参数才算：`onX != null` / `onX == null` /
    `onX?.`。光声明成可空不算 —— 那会把一大批从不挡渲染的回调算成欠武装。

    索引里必须包含**所有**大写开头的函数，不能只收 `*Screen/*Pane/...`：
    闸门常常挂在子组件上（`DesktopTransferLogPane` 自己从不判空，是它调用的
    `TransferRecordRow` 在 `if (onOpenFile != null)`），只收屏级名字就看不见它。
    """
    out = {}
    for rel in APP_GLOBS:
        base = os.path.join(root, rel)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirs, files in os.walk(base):
            if any(skip in dirpath + "/" for skip in SKIP_DIRS):
                continue
            for fn in files:
                if not fn.endswith(".kt"):
                    continue
                src = open(os.path.join(dirpath, fn), encoding="utf-8").read()
                for m in FUN_DEF_RE.finditer(src):
                    name = m.group(1)
                    pend = _match(src, m.end() - 1, "(", ")")
                    params = _split_top(src[m.end():pend - 1])
                    braces = src.find("{", pend)
                    if braces < 0:
                        continue
                    bend = _match(src, braces, "{", "}")
                    body = src[braces:bend]
                    pnames, optional, tested = set(), set(), set()
                    for raw in params:
                        # ⚠ 已知边界（**未修**，2026-10-13 由 R2-W2 实锤，待收口批次一起改）：
                        # 这里**没有**剥注释。一个形参前面若有 KDoc，`raw` 就是
                        # `"...说明文字。\n     */\n    onScanQr: (() -> Unit)? = null"`，
                        # `re.match(r"\s*(\w+)\s*:")` 从 `"` 起匹配不上 ⇒ 该形参被整个丢掉：
                        # 名字不进 `pnames`、`? = null` 不进 `optional` ⇒ 依赖它的那一支
                        # 永远不会被算成欠武装。
                        # 实测 `AddNodeSheet`（`feature/nodes/NodesScreen.kt:1181`）：工具只认出
                        # `optional = {'sheetState'}`，而 `onScanQr` / `onImportQrFromImage` /
                        # `onConnectNamed` 三个**带 KDoc 的可空回调**全部丢失
                        # （`onConnect`/`onDismiss`/`initialHost`/`initialPort` 这些不带 KDoc
                        # 的正常认出，`initialPort` 前面那段 KDoc 把 `initialHost` 也吞了）。
                        # **修法**：`raw = _strip_comments(raw)` 后再 match（一行）。
                        # **为何不现在改**：它会让 `optional` 集合变大 ⇒ 欠武装条目增多 ⇒
                        # 在途各组的「夹具欠武装 N 处无新增」证据串全部失效，与上面
                        # `FUNC_RE` 的花括号配平同一个理由，必须等五组停下再做。
                        pm = re.match(r"\s*(\w+)\s*:", raw)
                        if not pm:
                            continue
                        pn = pm.group(1)
                        pnames.add(pn)
                        if re.search(r"\?\s*=\s*null\s*$", raw.strip()):
                            optional.add(pn)
                        if re.search(rf"\b{re.escape(pn)}\s*[!=]=\s*null", body) or \
                           re.search(rf"\b{re.escape(pn)}\?\.", body):
                            tested.add(pn)
                    out[name] = {"nullTested": tested, "optional": optional, "params": pnames,
                                 "calls": set(CALL_RE.findall(body))}
    return out


def reachable_tested(comp, table, depth=3):
    """闭包内**被当闸门判过空**的参数并集（顺着 `calls` 往下走）。

    判空与"有没有默认值"是两件事：闸门常常判在子组件里，而子组件的那个参数
    是**必填**的（`TransferRecordRow(onOpenFile: ((String) -> Unit)?)` 没有 `= null`），
    真正"默认 null"的是根组件的同名参数（`DesktopTransferLogPane.onOpenFile = null`）。
    所以闭包收"判过空的"，再与根的"可空且默认 null"求交。
    """
    seen, frontier, tested = {comp}, [comp], set()
    for _ in range(depth):
        nxt = []
        for n in frontier:
            info = table.get(n)
            if not info:
                continue
            tested |= info["nullTested"]
            for c in info["calls"]:
                if c in table and c not in seen:
                    seen.add(c)
                    nxt.append(c)
        frontier = nxt
        if not frontier:
            break
    return tested


def preview_armed(root, composables):
    """{preview: {Composable: 没传的闸门参数集合}}"""
    out = {}
    for rel in PREVIEW_GLOBS:
        d = os.path.join(root, rel)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if not PREVIEW_FILE_RE.match(name):
                continue
            src = open(os.path.join(d, name), encoding="utf-8").read()
            hits = list(FUNC_RE.finditer(src))
            for i, m in enumerate(hits):
                end = hits[i + 1].start() if i + 1 < len(hits) else len(src)
                body = _strip_comments(src[m.end():end])
                missing = {}
                for cm in COMPOSABLE_RE.finditer(body):
                    comp = cm.group(1)
                    if comp not in composables:
                        continue
                    pend = _match(body, cm.end() - 1, "(", ")")
                    passed = {a.group(1) for a in
                              (re.match(r"\s*(\w+)\s*=", x) for x in _split_top(body[cm.end():pend - 1]))
                              if a}
                    # 闸门可能挂在子组件上，而参数是**根组件**声明并转发下去的：
                    # 只有根也声明了这个参数，预览才有可能传给它，才算欠武装。
                    gates = reachable_tested(comp, composables) & composables[comp]["optional"]
                    miss = gates - passed
                    if miss:
                        missing[comp] = miss
                if missing:
                    out[m.group(1)] = missing
    return out


def under_armed_keys(armed):
    """棘轮键：`{preview}|{Composable}|{参数}`。"""
    return sorted(f"{p}|{c}|{a}" for p, m in armed.items() for c, args in m.items() for a in args)


def read_ledger(path):
    rows = []
    for line in open(path, encoding="utf-8"):
        if not line.startswith(("| m-", "| d-")):
            continue
        c = [x.strip() for x in line.strip().strip("|").split("|")]
        if len(c) < 7:
            continue
        # **已撤的屏不参与配对判定**（2026-10-13 修）：原型里删掉的屏，台账上仍留一行
        # 诚实记录「这一屏没了、为什么没了」，但它没有帧、也不该有帧 ——
        # 判它「函数不存在」是把一条已经处理完的事变成永久的红，而且会让 --check
        # 永远退 1，真正的退化混在里面就没人看了。
        # 判据：**末列**以「已撤」开头。中间某格写「已撤」是在讲别的事
        # （比如「`NodesRowMenuPhonePreview` 已撤」说的是一个帧被删了，屏还在）。
        if c[-1].startswith("已撤"):
            continue
        rows.append({"id": c[0], "label": c[1], "shots": c[2], "landing": c[3],
                     "previews": preview_cell_names(c[4]),
                     "previewCell": c[4],
                     "tier": c[5], "conflict": c[6]})
    return rows


def preview_cell_names(cell):
    """Preview 列 → 函数名列表。

    **声明式写法不算函数名**：`无本屏预览（…原因…）` 与 `走宿主面 B7（…）` 说的是
    "这一屏没有代码侧帧"，如果照逗号切，原因里的每一段中文都会变成一个"函数名"，
    然后判成「函数不存在」——把一句诚实的说明判成谎话，还顺带把真缺帧的行
    从"要人裁决"变成"工具已经报过红了"，两种后果都比漏掉更坏。

    **尾随中文说明要先剥掉再按逗号切**（2026-10-12 修）：台账里诚实写下的
    `SetAvatarPageDarkPreview（第 33 轮新建…，从而…；reference 待重录）` 这类格子，
    括号里的理由本身带逗号，先切就把它劈成两个"函数名"，
    于是--check 报"函数不存在"假红，而对应的 reference PNG 其实躺在磁盘上。
    先剥括号、再切逗号，顺序不能反。
    """
    if NO_PREVIEW in cell or HOST_TRACK in cell:
        return []
    cleaned = cell
    for _ in range(4):  # 括号可能套括号，剥到不动为止
        nxt = _PAREN_RE.sub("", cleaned)
        if nxt == cleaned:
            break
        cleaned = nxt
    return [p.strip() for p in re.split(r"[,/]", cleaned) if p.strip() and p.strip() not in ("—", "-", "无")]


def expected_from_landing(landing):
    """从「代码落点」推出这一行该由哪些 Composable 渲染。"""
    want = set()
    for tok in re.split(r"[/\s]+", landing):
        tok = tok.strip().rstrip(",")
        if tok.endswith(".kt"):
            want.add(os.path.basename(tok)[:-3])
        elif re.fullmatch(r"[A-Z][A-Za-z0-9]*", tok):
            want.add(tok)
    return want


def judge(row, previews):
    want = expected_from_landing(row["landing"])
    if not row["previews"]:
        # 一列名字都没有：先看它是「诚实声明这一屏没有代码侧帧」还是「什么都没写」。
        cell = row.get("previewCell") or ""
        raw = cell.strip()
        # 「空列」只留给**真的一无所有**的格子：连一句说明都没写。
        # 只要格子里有正文（哪怕全在括号里，如 `—（与 m-chat 是同一屏…无独立帧）`），
        # 就算「声明无帧」——有人对"为什么没有帧"负责，与一声不吭不是一回事。
        # NO_FRAME_MARKERS 保留在文件里作为已知写法的索引（下面这条判定不依赖它）。
        if not raw or raw in ("—", "-", "无"):
            return want, [("(空列)", BLANK_CELL, set())]
        return want, [("(未列帧)", DECLARED_NO_FRAME, set())]
    verdicts = []
    for p in row["previews"]:
        if p not in previews:
            verdicts.append((p, "函数不存在", set()))
            continue
        _f, rendered = previews[p]
        if rendered & want:
            verdicts.append((p, "本屏", rendered))
        elif not rendered:
            # 函数体里没有屏级 Composable —— 可能是走同文件里的 helper 间接渲染，
            # 不能就此判它跨域；判"无法判定"并把函数体片段留给人看。
            verdicts.append((p, "无法判定", rendered))
        else:
            verdicts.append((p, "跨域", rendered))
    return want, verdicts


def collect(rows, previews):
    """{(屏, Preview): 判定}"""
    out = {}
    for row in rows:
        _want, verdicts = judge(row, previews)
        for p, v, _rendered in verdicts:
            out[f"{row['id']}|{p}"] = v
    return out


def load_baseline(path):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def ratchet(current, base):
    """返回 (回归, 新增的坏, 变好的)。回归 = 严重度升高。"""
    bentries = base.get("entries") or {}
    regress, newbad, improved = [], [], []
    for key, verdict in sorted(current.items()):
        now = SEVERITY.get(verdict, 3)
        if key in bentries:
            was = SEVERITY.get(bentries[key], 3)
            if now > was:
                regress.append((key, bentries[key], verdict))
            elif now < was:
                improved.append((key, bentries[key], verdict))
        elif now >= LIE:
            newbad.append((key, verdict))
    return regress, newbad, improved


def assert_(cond, msg):
    if not cond:
        raise AssertionError(f"自检：{msg}")


def self_test():
    """已知答案：四种判定各来一个，判错就说明量具本身坏了。"""
    previews = {
        "CabinetRealPreview": ("X.kt", {"CabinetScreen"}),          # 本屏
        "ChatWrongPreview":   ("X.kt", {"ChatScreen"}),             # 跨域
        "HelperOnlyPreview":  ("X.kt", set()),                      # 无法判定
    }
    row = {"id": "t", "landing": "feature/cabinet/CabinetScreen.kt",
           "previews": ["CabinetRealPreview", "ChatWrongPreview", "HelperOnlyPreview", "NoSuchPreview"]}
    _want, v = judge(row, previews)
    got = {p: s for p, s, _ in v}
    expect = {"CabinetRealPreview": "本屏", "ChatWrongPreview": "跨域",
              "HelperOnlyPreview": "无法判定", "NoSuchPreview": "函数不存在"}
    fails = [f"{k}: 期望 {expect[k]} 实得 {got.get(k)}" for k in expect if got.get(k) != expect[k]]
    # 声明式写法必须被认成"没有帧"，而不是一串函数名。
    # 这是**真发生过的**：把 7 行缺帧原因写进 Preview 列之后，原因里每一段中文
    # 都被切成一个"函数名"判「函数不存在」，工具反过来指责了一句诚实的说明。
    decl = [
        ("**无本屏预览**（结构缺口 V9：`SettingsMoreScreen`，不是 1:1 可配对）", []),
        ("走宿主面 B2（AWT 路径，Layoutlib 到不了）", []),
        ("SettingsProfilePhonePreview", ["SettingsProfilePhonePreview"]),
        ("ChatPhonePreview, ConversationsGroupPhonePreview", ["ChatPhonePreview", "ConversationsGroupPhonePreview"]),
    ]
    for cell, want in decl:
        gotp = preview_cell_names(cell)
        if gotp != want:
            fails.append(f"Preview 列 {cell[:20]!r} 期望 {want} 实得 {gotp}")
    # 屏级词表要认得 `Subpage`：不认得时 `SettingsProfileSubpage` 进不了 rendered，
    # 一个正确的帧会因为"函数体里没有屏级 Composable"或"只剩 StateSlotScreen"被判跨域。
    if not COMPOSABLE_RE.search("SettingsProfileSubpage("):
        fails.append("屏级词表必须认得 Subpage 结尾的 Composable")
    try:
        _armed_selftest()
    except AssertionError as e:
        fails.append(str(e))
    if fails:
        print("FAIL — 自检不通过：\n  " + "\n  ".join(fails), file=sys.stderr)
        return 1
    print("自检通过：本屏 / 跨域 / 无法判定 / 函数名写错 / 声明式缺帧 / 夹具欠武装（含子组件闸门）全部正确")
    return 0


def _armed_selftest():
    # 夹具欠武装：闸门挂在**子组件**上，参数由根组件声明并转发下去 ——
    # 预览只传根的参数。这个形状就是 `DesktopTransferLogPane → TransferRecordRow`
    # 与 `CabinetScreen → PeerCabinetView` 的真实形状。
    table = {
        "RootScreen": {"nullTested": set(), "optional": {"state", "onOpenFile"},
                       "params": {"state", "onOpenFile"}, "calls": {"RowItem"}},
        # 子组件的这个参数**必填**（没有 = null），判空却判在它身上 ——
        # 这就是 `TransferRecordRow` 的真实形状
        "RowItem": {"nullTested": {"onOpenFile"}, "optional": set(),
                    "params": {"record", "onOpenFile"}, "calls": set()},
        "OtherScreen": {"nullTested": {"onBack"}, "optional": {"onBack"},
                        "params": {"onBack"}, "calls": set()},
    }
    assert_(reachable_tested("RootScreen", table) == {"onOpenFile"},
            "子组件上的判空必须能顺着调用链找到")
    assert_(reachable_tested("RootScreen", table) & table["RootScreen"]["optional"] == {"onOpenFile"},
            "根的参数可空且默认 null 才算欠武装")
    assert_(not ({"onBack"} & table["RootScreen"]["optional"]),
            "根没声明的参数不算欠武装（预览根本传不进去）")

    # 参数表里夹注释：解析器按顶层逗号切参数、再要求每段以 `标识符 =` 开头，
    # 夹在参数之间的多行 `//` 注释会把**后面那个参数**粘进注释段 ⇒ 它被算成
    # "没传" ⇒ 假报一处「夹具欠武装」。注释写在参数表里是完全正常的 Kotlin 写法，
    # 修法方向是让量具学会它，不是让作者别写注释。
    src = """
        RootScreen(
            state = s,
            // 这里解释一下为什么显式传，而不是留 null
            // 第二行注释也一样
            onOpenFile = {},
        )
    """
    args = src[src.index("(") + 1: src.rindex(")")]

    def _passed(text):
        return {a.group(1) for a in
                (re.match(r"\s*(\w+)\s*=", x) for x in _split_top(text)) if a}

    assert_(_passed(_strip_comments(args)) == {"state", "onOpenFile"},
            f"参数表里的 // 注释不许吞掉它后面那个参数（实得 {sorted(_passed(_strip_comments(args)))}）")
    # 反向先验：**不去注释时本来就该吞掉** —— 否则这条自检量的是空气。
    assert_("onOpenFile" not in _passed(args),
            "反向先验失败：不去注释时本来就该吞掉这个参数，说明这段样例没构造出那个形状")
    # 字符串里的 // 不许被当注释切掉（`"https://…"`）
    kept = _strip_comments('x = "https://a.b/c" // 尾注释')
    assert_("https://a.b/c" in kept and "尾注释" not in kept,
            f"字符串字面量里的 // 不是注释（实得 {kept!r}）")
    # 块注释（含 /** … */ KDoc）必须整体剥掉。形状取自真实事故（第 61 个洞）：
    # 逐帧 body = `本帧声明之后 : 下一个函数声明之前`，**下一个函数的 KDoc 整段在 body 里**，
    # 而 KDoc 里会写 `` `DesktopShell.kt:475 FilesPane(` `` 这种引用文本。
    block = ('Foo(\n    a = 1,\n)\n\n/**\n'
             ' * 直调 [BarPane]，Expanded 双栏。\n'
             ' * `Shell.kt:475 FilesPane(` 的 `:488 onResumeTransfer = …` 也必须传。\n'
             ' */\nfun next() {}\n')
    stripped_block = _strip_comments(block)
    assert_("FilesPane" not in stripped_block and "BarPane" not in stripped_block,
            f"下一帧的 KDoc 不许算进本帧 body（实得 {stripped_block!r}）")
    assert_("a = 1" in stripped_block,
            f"剥块注释不许吃掉真正的参数（实得 {stripped_block!r}）")
    # 反向先验：不剥块注释时本来就该留下 —— 否则这条自检量的是空气。
    assert_("FilesPane" in block,
            "反向先验失败：样例里本来就该有 FilesPane 这个引用文本")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true", help="有跨域/缺失就返回 1")
    ap.add_argument("--markdown", help="把核对表写到这个文件")
    ap.add_argument("--baseline", action="store_true", help="把当前判定记成棘轮基线")
    ap.add_argument("--reason", help="重记基线时若比旧基线更差，必须给理由")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = args.root
    previews = scan_previews(root)
    if not previews:
        print("FAIL — 没扫到任何 @Preview，路径或正则不对", file=sys.stderr)
        return 2
    ledger_path = os.path.join(root, LEDGER)
    if not os.path.exists(ledger_path):
        print(f"FAIL — 台账不存在：{ledger_path}", file=sys.stderr)
        return 2
    rows = read_ledger(ledger_path)

    composables = scan_composables(root)
    armed = preview_armed(root, composables)
    armed_keys = under_armed_keys(armed)

    lines = ["# 配对台账 Preview 列核对（按函数体，不按名字）", "",
             f"扫到 {len(previews)} 个 `@Preview` 函数、台账 {len(rows)} 行。", "",
             "| 屏 | 代码落点该渲染 | 台账列的 Preview | 判定 | 它实际渲染 |",
             "|---|---|---|---|---|"]
    bad = 0
    for row in rows:
        want, verdicts = judge(row, previews)
        cells = []
        for p, v, rendered in verdicts:
            if v in ("跨域", "函数不存在", BLANK_CELL):
                bad += 1
            cells.append(f"`{p}` → **{v}**" + (f"（{', '.join(sorted(rendered))}）" if v == "跨域" else ""))
        ok = all(v == "本屏" for _, v, _ in verdicts)
        # 声明无帧 / 无法判定 ⇒ ⚠️（不是错，但**不是配对成功的证据**）；
        # 空列与跨域/写错 ⇒ ❌。旧版把空列算 ✅，见 BLANK_CELL 的注释。
        soft = any(v in ("无法判定", DECLARED_NO_FRAME) for _, v, _ in verdicts)
        hard = any(v in ("跨域", "函数不存在", BLANK_CELL) for _, v, _ in verdicts)
        lines.append(f"| {row['id']} | {', '.join(sorted(want)) or '—'} | {'<br>'.join(cells)} | "
                     f"{'✅' if ok else ('❌' if hard else ('⚠️' if soft else '❌'))} | |")

    lines += ["", "## 夹具欠武装（可空闸门参数没传 ⇒ 依赖它的那一支不会渲染）", "",
              f"共 {len(armed_keys)} 处。这是**证据的第二道关**："
              "夹具设了、锚点命中、门禁绿，图里却没有那一支。", "",
              "| 预览 | 渲染的组件 | 没传的闸门参数 |", "|---|---|---|"]
    for k in armed_keys:
        pv, comp, arg = k.split("|")
        lines.append(f"| `{pv}` | `{comp}` | `{arg}` |")
    lines.append("")

    report = "\n".join(lines) + "\n"
    if args.markdown:
        open(args.markdown, "w", encoding="utf-8").write(report)
        print(f"核对表已写入 {args.markdown}")

    current = collect(rows, previews)
    base_path = os.path.join(root, BASELINE)

    if args.baseline:
        old = load_baseline(base_path)
        regress = []
        if old:
            regress, _nb, _im = ratchet(current, old)
        grown = []
        if old:
            old_armed = set(old.get("underArmed") or [])
            grown = [k for k in armed_keys if k not in old_armed]
        if (regress or grown) and not args.reason:
            if regress:
                print(f"FAIL — 重记基线会记下 {len(regress)} 条比旧基线更差的判定：")
                for key, was, now in regress[:10]:
                    print(f"  · {key}：{was} → {now}")
            if grown:
                print(f"FAIL — 重记基线会记下 {len(grown)} 处新的夹具欠武装"
                      f"（可空闸门参数没传，依赖它的那一支不会渲染）：")
                for k in grown[:10]:
                    print(f"  · {k}")
            print("  先确认是台账改错了还是真退化了；确属要接受，用 --reason 写明理由。")
            return 1
        blob = {
            "note": "配对台账 Preview 列的棘轮基线：只许变好。已知坏的是历史欠债，不是许可。",
            "generatedAt": datetime.datetime.now().isoformat(timespec="seconds"),
            "rowCount": len(rows),
            "entries": dict(sorted(current.items())),
            # 夹具欠武装：可空闸门参数没传 ⇒ 依赖它的那一支不会渲染。
            # 这是历史欠债，不是许可；只许变少。
            "underArmed": armed_keys,
        }
        if args.reason:
            blob["reason"] = args.reason
        with open(base_path, "w", encoding="utf-8") as fh:
            json.dump(blob, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
        lies = sum(1 for v in current.values() if SEVERITY.get(v, 3) >= LIE)
        print(f"基线已写入 {BASELINE}：{len(current)} 条判定，其中 {lies} 条是跨域/写错（记为欠债）；"
              f"夹具欠武装 {len(armed_keys)} 处")
        return 0

    if args.check:
        base = load_baseline(base_path)
        if base is None:
            print(f"FAIL — 没有棘轮基线（{BASELINE}）；先 --baseline 记一次。", file=sys.stderr)
            return 1
        regress, newbad, improved = ratchet(current, base)
        for key, was, now in improved:
            print(f"  ↑ 变好：{key}：{was} → {now}")
        if "underArmed" not in base:
            print("FAIL — 基线里没有 underArmed 段（旧基线），夹具欠武装不受棘轮管；"
                  "先 --baseline 重记。", file=sys.stderr)
            return 1
        grown = [k for k in armed_keys if k not in set(base.get("underArmed") or [])]
        if grown:
            print(f"FAIL — {len(grown)} 处新的夹具欠武装（可空闸门参数没传，"
                  f"依赖它的那一支根本不会渲染，图里也就没有它）：", file=sys.stderr)
            for k in grown[:20]:
                print(f"  · {k}", file=sys.stderr)
            print("  给预览补上这个参数；若这一支本来就不该进图，"
                  "说明为什么（并接受这一处欠债）。", file=sys.stderr)
            return 1
        if regress:
            print(f"FAIL — {len(regress)} 条判定比基线更差（本屏/无法判定 掉成 跨域/写错）：")
            for key, was, now in regress[:20]:
                print(f"  · {key}：{was} → {now}")
            return 1
        if newbad:
            print(f"FAIL — {len(newbad)} 条新写进台账的判定不是「本屏」"
                  f"（跨域 / 函数名写错 / 空列 / 新声明为无帧）：")
            for key, now in newbad[:20]:
                print(f"  · {key}：{now}")
            return 1
        lies = sum(1 for v in current.values() if SEVERITY.get(v, 3) >= LIE)
        print(f"OK — {len(current)} 条判定无退化（基线里 {lies} 条跨域/写错/空列/声明无帧"
              f"是历史欠债，只许变少）；"
              f"夹具欠武装 {len(armed_keys)} 处无新增")
        return 0

    # 报告模式**不判红**：写 `FAIL` 却 return 0 是"看起来对"的典型 ——
    # 输出说失败、退出码说成功，门禁看退出码，于是永远绿。
    if bad:
        print(f"注意 — {bad} 条 Preview 列渲染的是别的屏、写错函数名、或**空列**"
              f"（空列不是配对成功）；报告模式不判红，要判红请用 --check。")
    else:
        print(f"OK — {len(rows)} 行的 Preview 列全部是本屏（声明无帧的行另见核对表 ⚠️）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
