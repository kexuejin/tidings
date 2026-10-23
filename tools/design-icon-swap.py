#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把图标库调用换成原型 stroke 资源（`Icons.Outlined.X` → `Res.drawable.ic_x`）。

## 为什么需要它

`design-icon-vectorize.py` 只是把原型的 62 个 stroke symbol 变成磁盘上的 vector；
**"还原原型"要求代码真的用它们**。在此之前，代码侧 90 处用的是 Material 图标库花字
（`Icons.Outlined.Folder` 等）——语义对、笔触语言不同（fill vs stroke），
正是 `icon-layer.md` 说的"整套换掉还看不出来"。

## 安全设计

* **默认 dry-run**；`--apply` 才写，写前逐个文件留 `.bak-icons` 备份。
* **只改干净文件**（`git status --porcelain` 里没有标记的）——对面会话在途的文件一律跳过，
  冲突交给它的提交边界处理。
* **只替换能一一对应的映射**（`design-icon-map.py` 的语义桥同源）；`Icons.AutoMirrored.*`
  只在目标是方向性图标（arrow/back/forward）时替换，其余保留（镜像语义不能丢）。
* **替换后要处理 import**：加 `Res.drawable.*` 需要
  `import com.kapp.tidings.generated.resources.Res` 与对应资源名；删掉不再用的 `Icons` import 交给
  仓里既有的 `unused-imports.py`（有门禁，别手工猜）。
* 每处替换都计数，`--expect N` 不符即拒绝写盘（防止正则比预期多/少匹配）。

用法：
    python3 scaffold/tools/design-icon-swap.py --dry            # 看会改什么
    python3 scaffold/tools/design-icon-swap.py --apply          # 真改（自动备份）
    python3 scaffold/tools/design-icon-swap.py --file <path>    # 只处理一个文件
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

# 库图标名 → 原型 stroke 资源名（与 design-icon-map.py 的 SEMANTIC 同源）
LIB_TO_IC = {
    "Folder": "ic_folder", "FolderOpen": "ic_folder", "Search": "ic_search",
    "OpenInNew": "ic_external", "ContentCopy": "ic_copy", "Menu": "ic_list",
    "ViewList": "ic_list", "InsertDriveFile": "ic_file", "Description": "ic_file",
    "Crop": "ic_scissors", "ContentCut": "ic_scissors", "Chat": "ic_chat_bubble",
    "Refresh": "ic_refresh", "MoreVert": "ic_more", "Link": "ic_link",
    "Image": "ic_image", "FileDownload": "ic_down", "Download": "ic_down",
    "FileUpload": "ic_up", "Upload": "ic_up", "EmojiEmotions": "ic_smile",
    "Info": "ic_info", "Visibility": "ic_eye", "Security": "ic_shield",
    "Shield": "ic_shield", "Notifications": "ic_bell", "GridView": "ic_grid",
    "DarkMode": "ic_moon", "Nightlight": "ic_moon", "LightMode": "ic_sun",
    "WbSunny": "ic_sun", "Inventory": "ic_hdd", "Inventory2": "ic_hdd",
    "HardDrive": "ic_hdd", "Storage": "ic_hdd", "Terminal": "ic_terminal",
    "Send": "ic_send", "TouchApp": "ic_nudge", "TextFields": "ic_text_select",
    "ChevronRight": "ic_chev", "KeyboardArrowRight": "ic_chev",
    "ExpandMore": "ic_chev", "ArrowBack": "ic_arrow_back", "Close": "ic_close",
    "Warning": "ic_warn", "ErrorOutline": "ic_alert", "Lock": "ic_lock",
    "Person": "ic_user", "AccountCircle": "ic_user", "Key": "ic_key",
    "VpnKey": "ic_key", "Wifi": "ic_wifi", "BatteryStd": "ic_batt",
    "Delete": "ic_trash", "DeleteOutline": "ic_trash", "FilterList": "ic_filter",
    "Sort": "ic_sort", "VolumeUp": "ic_speaker", "Inbox": "ic_drop",
    "WebAsset": "ic_window", "OpenInFull": "ic_window", "Add": "ic_add_node",
    "Remove": "ic_minus", "Check": "ic_check_double", "DoneAll": "ic_check_double",
    "Schedule": "ic_clock_pending", "AccessTime": "ic_clock_pending",
    "SmartToy": "ic_bot", "Bot": "ic_bot", "Groups": "ic_groups",
    "People": "ic_groups", "Lan": "ic_lan_search", "QrCodeScanner": "ic_qr_code_scanner",
    "QrCode2": "ic_qr_code_scanner", "Settings": "ic_settings",
    "AttachFile": "ic_attachment", "EmojiEmotionsOutlined": "ic_smile",
    "Monitor": "ic_screen_share", "ScreenShare": "ic_screen_share",
    "PanTool": "ic_hand", "Draw": "ic_hand", "Mouse": "ic_capture_select",
    "CropFree": "ic_capture_rect", "BlurOn": "ic_capture_mosaic",
    # —— 以下为 2026-10-10 兜底扫描报出的缺口，逐个回原型核对后补 ——
    # 截图工具栏「箭头」= 原型 symbol i-capture-arrow（CAP_TOOLS 第 3 项）
    "ArrowRightAlt": "ic_capture_arrow",
    # 截图工具栏「马赛克」：原型 i-capture-mosaic，与 BlurOn 同一 symbol
    "Grain": "ic_capture_mosaic",
    # 桌面导航「节点」= 原型 DVIEWS 里 icon:'lan' ⇒ i-lan
    "Devices": "ic_lan_search",
    # 「建群」= 原型 `ic('group') 建群`（relay.html:2864）⇒ i-group
    "GroupAdd": "ic_groups", "Group": "ic_groups",
    # 会话切换箭头 = 原型 i-chevd（向下 chevron）
    "KeyboardArrowDown": "ic_chevd", "ExpandMore": "ic_chevd",
}

# **原型里根本没有对应 symbol** 的库图标 —— 登记为「代码特有」，不再当缺口报。
# 判据不是"我想不出映射"，而是"回原型逐 symbol 找过，确实没有这个语义"。
# 每条都要能指出原型侧的差异，否则就是没查。
CODE_ONLY = {
    "Mic": "原型 sprite 无 mic symbol（63 个 symbol 里无；「录音」只在 fixture 文案里出现）。"
           "代码侧 ic_mic.xml 是 fill 语言的自绘资源，属代码特有语义",
    "Stop": "原型无停止符号：录音弹窗是代码特有交互（VoiceRecordDialog），原型未画该状态",
    "PlayArrow": "同上：录音试听是代码特有交互，原型未画",
    "Undo": "**与原型不一致**：原型截图工具栏只有 5 项（重新框选/矩形/箭头/文字/马赛克）"
            "+ 发送/复制/取消，**没有撤销**（relay.html:4597-4611 的 CAP_TOOLS）。"
            "代码多了 Undo（CaptureOverlayView.kt:404）⇒ 应记入偏差账本，不是补图标",
    "DragHandle": "**与原型不一致**：原型输入区手柄是 `.cmp .grip i` = 28×3px 圆角实心条"
                  "（relay.html:1163-1164），不是 24×24 线性图标；代码用 DragHandle(size 14dp)"
                  "⇒ 形状与尺寸都不同，应记入偏差账本",
}
# 方向性图标：AutoMirrored 前缀要保留语义 → 只有这些允许在 AutoMirrored 形态下替换
DIRECTIONAL = {"ArrowBack", "ArrowForward", "Send", "ChevronRight", "KeyboardArrowRight",
               "OpenInNew", "FileDownload", "FileUpload", "InsertDriveFile", "Label",
               "List", "Menu", "ExitToApp", "TrendingFlat", "KeyboardArrowLeft",
               "ArrowRightAlt"}

# `Icon(imageVector = Icons.X, …)` 与 `Icon(painter = Icons.X, …)` 都要连**调用形状**一起改：
# `Res.drawable.*` 的类型是 DrawableResource，不是 ImageVector —— 只换值会编译不过
# （试过一次，编译器报的类型错误就是判据）。
ICON_CALL = re.compile(
    r"(?P<kw>imageVector|painter)\s*=\s*"
    r"Icons\.(?P<auto>AutoMirrored\.)?(?:Filled|Outlined)\.(?P<name>\w+)")
# 位置参数形态：`Icon(Icons.X, contentDescription = …)` —— Icon 的第一个位置参数是 imageVector
POSITIONAL = re.compile(
    r"(?<![\w.])Icons\.(?P<auto>AutoMirrored\.)?(?:Filled|Outlined)\.(?P<name>\w+)(?=\s*[,)])")
# 兜底扫描用：任何 `Icons[.AutoMirrored].{Filled|Outlined}.Name` 引用（不管后跟什么）。
# 替换和跳过都没覆盖到、但**本来能映射**的引用要报出来 —— "工具没看见"不等于"不需要改"。
ANY_ICON = re.compile(
    r"(?<![\w.])Icons\.(?P<auto>AutoMirrored\.)?(?:Filled|Outlined)\.(?P<name>\w+)")
# 三元表达式：`imageVector = if (isFolder) Icons.Outlined.Folder else Icons.Outlined.Description`
# **两个分支必须一起换**——只换一个会造出 `ImageVector` 与 `Painter` 混用的表达式。
# 条件里允许一层括号（`if (!isX)` / `if (a || b)`）。
#
# 前导的命名参数 `imageVector = ` **必须一起吃进来**：`Icon(imageVector = …)` 的形参类型是
# `ImageVector`，只把两个分支换成 Painter 会留下
# `imageVector = if (…) painterResource(…) else painterResource(…)`
# ⇒ 编译报 `actual type is 'Painter', but 'ImageVector' was expected`（已实测，见
# DesktopTransferLogPane.kt:160）。吃进来之后改写成 `painter = `，形状与分支类型才一起变。
# 没有前导参数名（如 `icon = if (…) Icons.A else Icons.B` 里的位置用法）时 kw 为 None，
# **不得凭空补 `painter = `**——那会把一个正常的赋值改坏。
ICON_TERNARY = re.compile(
    r"(?:(?P<kw>imageVector|painter)\s*=\s*)?"
    r"if\s*\((?P<cond>[^()]*(?:\([^()]*\)[^()]*)*)\)\s*"
    r"(?P<a>Icons\.(?:AutoMirrored\.)?(?:Filled|Outlined)\.(?P<aname>\w+))\s*else\s+"
    r"(?P<b>Icons\.(?:AutoMirrored\.)?(?:Filled|Outlined)\.(?P<bname>\w+))")
SCAN_ROOTS = ["scaffold/composeApp/src/commonMain", "scaffold/composeApp/src/androidMain",
              "scaffold/composeApp/src/jvmMain", "scaffold/desktopApp/src/main"]
EXCLUDE = ("screenshotTest", "Test.kt", "PreviewState.kt", "/build/")


def dirty_files():
    """git 对未跟踪**目录**只报一行 `?? dir/` —— 收集时必须把目录路径也留下，
    否则循环里拿文件路径去比对永远不命中，把对面在途的文件当成干净文件改掉。
    （踩过：cabinet/ 整个目录未跟踪，却被判成可改。）"""
    out = subprocess.run(["git", "status", "--porcelain", "--untracked-files=all"],
                         capture_output=True, text=True).stdout
    paths = set()
    for line in out.splitlines():
        if len(line) < 4:
            continue
        paths.add(line[3:].strip())
    return paths


def is_dirty(rel: str, dirty) -> bool:
    if rel in dirty:
        return True
    # 目录级条目（`?? dir/`）也要认：加上 --untracked-files=all 后多数是文件，
    # 但已跟踪目录里混未跟踪文件时仍可能是目录。前缀匹配是保守判据。
    return any(d.endswith("/") and rel.startswith(d) for d in dirty)


def own_scope():
    """本会话登记过的写范围（`scripts/relay-own-start.sh` 维护）。

    **为什么必须有这个**：`is_dirty()` 只看 git status，而"在途"包含两类完全相反的
    情况 —— 对面正在写（不许碰）与本会话自己写了一半（本来就该我改）。只看 git 会把
    两者一起跳过 ⇒ 工具默认什么都不做（实测 `--dry` 报 0 文件 0 处），
    于是"图标消费率"永远停在原地，而每次运行都打印"0 处"看起来像"已经没有可换的了"。
    这是**用工具的空输出冒充完成**，和拿分母换好看同族。
    """
    p = os.environ.get("OWN_SCOPE_FILE", "/tmp/relay-own-scope.txt")
    out = set()
    if not os.path.isfile(p):
        return out
    for line in open(p, encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if line:
            out.add(line)
    return out


def in_mine(rel: str, mine) -> bool:
    return rel in mine or any(m.endswith("/") and rel.startswith(m) for m in mine)


def swap_in_text(text: str, allow_automirrored: bool, sigs=None):
    """→ (新文本, 替换明细[(lib 名, 资源名)], 跳过明细[(lib 名, 资源名, 行号, 原因)])。

    顺序：三元（两分支整体）→ 命名参数（连形状一起改）→ 位置参数（判上下文）
    → **兜底扫描**。兜底那遍是必须的：正则没匹配上的位置（如 `when` 分支里
    `X -> Icons.Chat` 后面跟的是换行而不是 `[,)]`）既不进替换也不进跳过，
    静默消失 —— 而"我没改它"和"它不需要改"是两件事，必须报出来。
    """
    detail, skipped, code_only_seen = [], [], []

    def resolve(auto: bool, name: str):
        ic = LIB_TO_IC.get(name)
        if not ic:
            return None
        if auto and not allow_automirrored:
            return None
        if auto and name not in DIRECTIONAL:
            return None
        return ic

    def rep_named(m):
        ic = resolve(bool(m.group("auto")), m.group("name"))
        if not ic:
            return m.group(0)
        detail.append((m.group("name"), ic))
        return f"painter = painterResource(Res.drawable.{ic})"

    def rep_ternary(m):
        """三元两个分支一起换。任一支映射不到（或 AutoMirrored 语义不允许）就整条不动——
        换一半比不换更坏。"""
        ic_a = resolve("AutoMirrored." in m.group("a"), m.group("aname"))
        ic_b = resolve("AutoMirrored." in m.group("b"), m.group("bname"))
        if not ic_a or not ic_b:
            return m.group(0)
        detail.append((m.group("aname"), ic_a))
        detail.append((m.group("bname"), ic_b))
        # 有前导参数名时连形状一起改（`imageVector = ` → `painter = `）；
        # 没有时保持位置用法，绝不能凭空补参数名。
        kw = m.group("kw")
        head = "painter = " if kw else ""
        return (f"{head}if ({m.group('cond')}) painterResource(Res.drawable.{ic_a}) "
                f"else painterResource(Res.drawable.{ic_b})")

    # 顺序要紧：三元先（两个分支整体处理），再命名参数，最后位置参数。
    # 三元若不先处理，位置参数那遍会只换 else 分支（已实测复现）。
    mid = ICON_TERNARY.sub(rep_ternary, text)
    mid = ICON_CALL.sub(rep_named, mid)

    def rep_pos(m):
        ic = resolve(bool(m.group("auto")), m.group("name"))
        if not ic:
            return m.group(0)
        ok, why = _pos_ok(mid, m.start())
        if ok and sigs:
            # 语法合法不等于类型合法：`StatusBarAction(label, icon: ImageVector, …)` 这类
            # 位置参数换成 Painter 必编译失败。编译器能抓，工具要能提前拦。
            callee, argidx = _enclosing_call(mid, m.start())
            bad, why2 = _param_is_imagevector_only(sigs, callee, argidx)
            if bad:
                ok, why = False, why2
        if not ok:
            # 表达式内部（`if (x) A else B` 的 B、`when { X -> A }` 的 A）：
            # 只换一个分支会造出 ImageVector 与 Painter 混用的表达式。
            # 编译不过是对的（类型错误就是判据），但文件已经被写坏了 —— 这里直接不换。
            skipped.append((m.group("name"), ic, mid.count("\n", 0, m.start()) + 1, why))
            return m.group(0)
        detail.append((m.group("name"), ic))
        return f"painterResource(Res.drawable.{ic})"

    new = POSITIONAL.sub(rep_pos, mid)

    # 兜底：替换与跳过都没覆盖到的引用一律报出来，分两类——
    # **本来能映射**（正则没覆盖）与**映射表里根本没有**（goal ③ 的映射表缺口）。
    # "工具没看见"和"不需要改"是两件事，只有后者允许静默。
    seen_skip = {(line, name) for name, _ic, line, _r in skipped}
    unmapped = []
    for m in ANY_ICON.finditer(new):
        name = m.group("name")
        line = new.count("\n", 0, m.start()) + 1
        if name not in LIB_TO_IC:
            # 已登记的「代码特有」不算缺口（回原型查过、确实没有这个语义），
            # 但仍要报出——"代码特有"是个决定，不是"没看见"。
            if name in CODE_ONLY:
                code_only_seen.append((name, line))
            else:
                unmapped.append((name, line))
            continue
        if (line, name) in seen_skip:
            continue
        # 报出的**原因**必须是真的。这里有两种"没换"：
        #   ① 正则确实没覆盖这个形态（`when` 里 `X -> Icons.Chat` 后面是换行）；
        #   ② 正则覆盖了，但 `AutoMirrored` 语义闸门把它拦下了（名字不在 DIRECTIONAL）。
        # ② 若也报成 ①，读者会去改函数签名/引入 IconSource —— 而真正的修法只是把名字
        # 加进 DIRECTIONAL 一行。实测踩过：`Icons.AutoMirrored.Outlined.ArrowRightAlt`
        # 明明在 LIB_TO_IC 里，却报"未匹配任何形态"。
        if m.group("auto") and name not in DIRECTIONAL:
            skipped.append((name, LIB_TO_IC[name], line,
                            f"AutoMirrored 形态被语义闸门拦下（{name} 不在 DIRECTIONAL）；"
                            f"若该图标确实有方向语义，把名字加进 DIRECTIONAL 即可，"
                            f"不需要改调用方"))
        else:
            skipped.append((name, LIB_TO_IC[name], line, "未匹配任何形态（正则未覆盖）"))
    return new, detail, skipped, unmapped, code_only_seen


def _scan_funcs(root_dirs):
    """扫出「本仓自己定义的函数 → 形参类型列表」，用于位置参数的**类型判据**。

    为什么需要它：位置参数那遍只看语法（`Icons.X` 后是不是 `,`/`)`），看不见**形参类型**。
    实测踩过：`StatusBarAction(label, icon: ImageVector, …)` 的第二个实参被换成
    `painterResource(...)` ⇒ 编译报 `actual type is 'Painter', but 'ImageVector' was expected`。
    语法上完全合法，类型上必坏 —— 这类洞**编译器能抓，工具不能**，所以工具要么自己看类型，
    要么把这类位置报出来交给人。

    只认本仓定义的函数（Material3 的 `Icon` 等外部符号不在索引里 ⇒ 判不了 ⇒ 放行），
    这是保守默认：**证明不了才放行，能证明就拦**。
    """
    sigs = {}
    for root in root_dirs:
        for dirpath, dirnames, filenames in os.walk(root):
            if "/build/" in dirpath or "/build" in dirnames:
                dirnames[:] = [d for d in dirnames if d != "build"]
            for fn in filenames:
                if not fn.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, fn)
                try:
                    src = open(path, encoding="utf-8", errors="replace").read()
                except OSError:
                    continue
                for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(", src):
                    i, depth = m.end() - 1, 0
                    while i < len(src):
                        if src[i] == "(":
                            depth += 1
                        elif src[i] == ")":
                            depth -= 1
                            if depth == 0:
                                break
                        i += 1
                    sigs.setdefault(m.group(1), []).append(src[m.end():i])
    return sigs


def _params_of(params_src: str):
    """按**顶层**逗号切形参（`onClick: () -> Unit` 里的逗号不算），取每个形参的类型。"""
    parts, depth, cur = [], 0, ""
    for ch in params_src:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    types = []
    for p in parts:
        p = p.split("=", 1)[0]          # 去掉默认值
        if ":" in p:
            types.append(p.split(":", 1)[1].strip())
        else:
            types.append("")
    return types


def _enclosing_call(s: str, i: int):
    """→ (被调函数名, 本实参是第几个) 或 (None, None)。

    从 `i` 往前找未配对的 `(`；再数它到 `i` 之间的**顶层**逗号个数即实参序号。
    """
    depth, j = 0, i - 1
    while j >= 0:
        c = s[j]
        if c == ")":
            depth += 1
        elif c == "(":
            if depth == 0:
                break
            depth -= 1
        j -= 1
    if j < 0:
        return None, None
    k = j - 1
    while k >= 0 and s[k].isspace():
        k -= 1
    m = re.search(r"([A-Za-z_][\w.]*)$", s[:k + 1])
    if not m:
        return None, None
    name = m.group(1).split(".")[-1]
    # 数实参序号：`(` 到 `i` 之间的顶层逗号
    seg, d2, idx = s[j + 1:i], 0, 0
    for ch in seg:
        if ch in "([{":
            d2 += 1
        elif ch in ")]}":
            d2 -= 1
        elif ch == "," and d2 == 0:
            idx += 1
    return name, idx


def _param_is_imagevector_only(sigs, callee: str, argidx: int):
    """该位置的形参是不是「只有 ImageVector、没有 Painter 重载」。

    → (是否必坏, 说明)。判不了（函数不在索引里 / 形参越界 / 类型没写）一律返回 False。
    """
    if callee not in sigs:
        return False, ""
    overloads = sigs[callee]
    if any(re.search(r"\bpainter\s*:\s*[\w.]*Painter", pr) for pr in overloads):
        return False, ""
    for pr in overloads:
        types = _params_of(pr)
        if argidx < len(types) and "ImageVector" in types[argidx]:
            return True, (f"形参类型是 ImageVector（{callee} 第 {argidx + 1} 个形参，"
                          f"且无 Painter 重载）⇒ 换 Painter 必编译失败；"
                          f"先改该函数的形参类型，或在此处保留库图标")
    return False, ""


def _pos_ok(s: str, i: int) -> tuple:
    """位置参数形态的上下文判据 → (可否替换, 不可替换的原因)。

    **空白要连换行一起跳过**：多行实参写法

        Icon(
            Icons.Outlined.Folder,
            …

    里 `Icons` 前一个非空白字符是 `(`（跨过换行），是合法位置参数；只跳过空格/制表符
    会把它误判成表达式内部而整批跳过（已实测：DesktopComposer/DesktopFilesPane/
    DesktopChrome/CaptureOverlayView/DesktopNodesPane 共 12 处假跳过）。

    真正要拒的是**表达式内部**：`when` 分支 `isImage -> Icons.Image` 的前一非空白字符是
    `>`，三元分支同理。判据就是"前一非空白字符必须是 `(`/`,`/`=` 之一"。
    **不要再用"同一行有 `->`"当第二条判据**——那会误伤 `x -> Icon(Icons.Folder, …)`
    这种分支里的合法调用（前一字符是 `(`，本就该放行）。
    """
    j = i - 1
    while j >= 0 and s[j].isspace():
        j -= 1
    prev = s[j] if j >= 0 else ""
    if prev not in "(,=":
        return False, f"表达式内部（前一非空白字符 {prev!r}）"
    return True, ""


def ensure_imports(text: str, ics):
    """补 Res / drawable 资源 import。资源名 import 形如
    `import com.kapp.tidings.generated.resources.<name>`（Compose Resources 生成物）。"""
    if not ics:
        return text, False
    changed = False
    # painterResource 来自 compose resources；仓里既有文件用的就是它（DesktopComposer 等）
    if "import org.jetbrains.compose.resources.painterResource" not in text and \
            "painterResource(" in text:
        anchor = re.search(r"^import .*$", text, re.M)
        text = (text[:anchor.start()]
                + "import org.jetbrains.compose.resources.painterResource\n"
                + text[anchor.start():])
        changed = True
    if "import com.kapp.tidings.generated.resources.Res" not in text:
        anchor = re.search(r"^import .*$", text, re.M)
        if anchor:
            text = text[:anchor.start()] + "import com.kapp.tidings.generated.resources.Res\n" + text[anchor.start():]
            changed = True
    for ic in sorted(set(ics)):
        line = f"import com.kapp.tidings.generated.resources.{ic}"
        if line not in text:
            # 插到 Res import 之后，保持分组
            m = re.search(r"^import app\.relay\.generated\.resources\.Res\n(?:import app\.relay\.generated\.resources\.\w+\n)*", text, re.M)
            if m:
                text = text[:m.end()] + line + "\n" + text[m.end():]
            else:
                text = text.replace("import com.kapp.tidings.generated.resources.Res\n",
                                    f"import com.kapp.tidings.generated.resources.Res\n{line}\n", 1)
            changed = True
    return text, changed


def self_test():
    """已知缺陷清单（每条都真实发生过；改动本文件后必须重跑）：

    1. **三元只换一半** —— `if (isFolder) Icons.Folder else Icons.Description`：
       `POSITIONAL` 要求 `Icons.X` 后紧跟 `[,)]`，`Folder` 后是空格 + `else` 不命中，
       而 `Description` 后是 `,` 命中 ⇒ 产生 `ImageVector` 与 `Painter` 混用的表达式。
    2. **表达式内部的位置调用** —— `when { X -> Icons.Chat }`（图标即数据，函数返回
       `ImageVector`）：整体替换会改掉返回类型，必须跳过并报出来。
    3. **AutoMirrored 语义** —— 非方向性图标带 AutoMirrored 前缀时不许替换。
    4. **命名参数要连调用形状一起改** —— `imageVector = Icons.X` → `painter = painterResource(…)`；
       只换值会编译不过（`DrawableResource` ≠ `ImageVector`）。
    """
    fails = []

    def check(label, got, want):
        if got != want:
            fails.append(f"{label}：期望 {want!r}，实际 {got!r}")

    # 1. 三元：两个分支一起换，且不留 Icons.*；**前导参数名也要跟着变成 `painter = `**
    t = "imageVector = if (isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,\n"
    new, detail, skipped, _un, _co = swap_in_text(t, True)
    check("三元替换处数", len(detail), 2)
    check("三元后残留 Icons.", "Icons." in new, False)
    check("三元跳过数", len(skipped), 0)
    check("三元结果", new.strip(),
          "painter = if (isFolder) painterResource(Res.drawable.ic_folder) "
          "else painterResource(Res.drawable.ic_file),")
    # 1a. 没有前导参数名时**不得凭空补** `painter = `（位置用法）
    t1a = "val ic = if (isFolder) Icons.Outlined.Folder else Icons.Outlined.Description\n"
    n1a, d1a, _, _un, _co = swap_in_text(t1a, True)
    check("三元无参数名时不补 painter =", n1a.startswith("val ic = if (isFolder)"), True)
    check("三元无参数名时不补（无 painter = ）", "painter =" in n1a, False)
    check("三元无参数名仍换两支", len(d1a), 2)

    # 1b. 三元里有一支映射不到 ⇒ 整条不动（换一半比不换更坏）
    t1b = "imageVector = if (x) Icons.Outlined.Folder else Icons.Outlined.NoSuchIcon,\n"
    n1b, d1b, _, _un, _co = swap_in_text(t1b, True)
    check("三元一支映射不到时不改", n1b, t1b)
    check("三元一支映射不到时 0 处", len(d1b), 0)

    # 1c. 三元条件含括号/取反
    t1c = "imageVector = if (!isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,\n"
    _, d1c, _, _un, _co = swap_in_text(t1c, True)
    check("三元条件含括号", len(d1c), 2)

    # 2. when 分支（图标即数据）必须跳过并报出，不能改
    t2 = "        DesktopView.Chat -> Icons.Outlined.Chat\n"
    n2, d2, s2, _un, _co = swap_in_text(t2, True)
    check("when 分支不改", n2, t2)
    check("when 分支 0 处", len(d2), 0)
    check("when 分支要报出", len(s2), 1)
    if s2:
        check("when 分支行号", s2[0][2], 1)

    # 3. AutoMirrored 非方向性 → 不换
    t3 = "    Icons.AutoMirrored.Outlined.Folder,\n"
    n3, d3, s3, _un, _co = swap_in_text(t3, True)
    check("AutoMirrored 非方向性不改", n3, t3)
    check("AutoMirrored 非方向性 0 处", len(d3), 0)
    # 但**必须报出**，且原因要指向真正的修法（DIRECTIONAL 闸门），不能报成
    # "正则未覆盖"——后者会把读者引去改调用方/引入 IconSource，而真正的修法只是加一个名字。
    # 实测踩过：`Icons.AutoMirrored.Outlined.ArrowRightAlt` 在 LIB_TO_IC 里却报"未匹配任何形态"。
    check("AutoMirrored 闸门要报出", len(s3), 1)
    if s3:
        check("AutoMirrored 闸门原因指向 DIRECTIONAL", "DIRECTIONAL" in s3[0][3], True)
    # 方向性 → 换
    t3b = "    Icons.AutoMirrored.Outlined.ArrowBack,\n"
    _, d3b, _, _un, _co = swap_in_text(t3b, True)
    check("AutoMirrored 方向性要换", len(d3b), 1)
    # ArrowRightAlt 有方向语义（截图工具栏「箭头」），必须能换 —— 漏登记会让它被静默跳过
    t3c = "    Icons.AutoMirrored.Outlined.ArrowRightAlt,\n"
    _, d3c, _, _un, _co = swap_in_text(t3c, True)
    check("ArrowRightAlt 方向性要换", len(d3c), 1)

    # 4. 命名参数连形状一起改
    t4 = "            imageVector = Icons.Outlined.Folder,\n"
    n4, d4, _, _un, _co = swap_in_text(t4, True)
    check("命名参数处数", len(d4), 1)
    check("命名参数结果", n4.strip(),
          "painter = painterResource(Res.drawable.ic_folder),")

    # 4b. **多行实参**（`Icon(\n  Icons.Folder,\n`）必须替换 —— 前一非空白字符跨换行是 `(`
    t4b = "Icon(\n    Icons.Outlined.Folder,\n    contentDescription = null,\n)\n"
    n4b, d4b, s4b, _, _co = swap_in_text(t4b, True)
    check("多行实参处数", len(d4b), 1)
    check("多行实参后残留 Icons.", "Icons." in n4b, False)
    check("多行实参不应被跳过", len(s4b), 0)

    # 4c. `when` 分支里的合法调用（`x -> Icon(Icons.Folder, …)`）要换：
    #     判据是"前一非空白字符是 `(`"，不能拿"同一行有 ->"去误伤
    t4c = "        X -> Icon(Icons.Outlined.Folder, contentDescription = null)\n"
    n4c, d4c, _, _, _co = swap_in_text(t4c, True)
    check("when 分支里的 Icon(...) 要换", len(d4c), 1)

    # 4d. 映射表缺口必须报出来（不静默）：`Icons.Outlined.Bluetooth` 不在 LIB_TO_IC
    #     也**不在 CODE_ONLY**（后者是"回原型查过、确实没有这个语义"的决定，不是缺口）。
    #     拿 CODE_ONLY 里的名字当缺口探针会永远不报 —— 这条先验本身也在测分类有没有走对。
    t4d = "    Icons.Outlined.Bluetooth,\n"
    _, d4d, _, u4d, _co = swap_in_text(t4d, True)
    check("缺映射不替换", len(d4d), 0)
    check("缺映射要报出", len(u4d), 1)
    if u4d:
        check("缺映射名字", u4d[0][0], "Bluetooth")

    # 4e. 「代码特有」（CODE_ONLY）与「缺映射」是**两类**，不能合并：
    #     前者有原型侧核对记录，后者是没查过 ⇒ 前者进 code_only_seen，后者进 unmapped。
    t4e = "    Icons.Outlined.Mic,\n"
    _, d4e, _, u4e, co4e = swap_in_text(t4e, True)
    check("代码特有不算缺口", len(u4e), 0)
    check("代码特有仍要报出", len(co4e), 1)
    if co4e:
        check("代码特有名字", co4e[0][0], "Mic")

    # 5. import 补齐：painterResource / Res / 资源名各一次，且幂等
    t5 = "package a\n\nimport androidx.compose.material.icons.Icons\n"
    n5, _ = ensure_imports(t5 + n4, ["ic_folder"])
    for want in ("import org.jetbrains.compose.resources.painterResource",
                 "import com.kapp.tidings.generated.resources.Res",
                 "import com.kapp.tidings.generated.resources.ic_folder"):
        check(f"import {want.rsplit('.', 1)[-1]}", want in n5, True)
    n5b, _ = ensure_imports(n5, ["ic_folder"])
    check("import 幂等", n5b.count("import com.kapp.tidings.generated.resources.ic_folder"), 1)

    if fails:
        print(f"self-test: FAILED（{len(fails)} 处）")
        for f in fails:
            print("  -", f)
        return 1
    print("self-test: OK（0 处）—— 覆盖 三元两分支/三元单支缺失/多行实参/when 分支里的合法调用/"
          "when 即数据/AutoMirrored/命名参数形状/映射表缺口必报/import 幂等")
    return 0


def main():
    ap = argparse.ArgumentParser(description="图标库调用 → 原型 stroke 资源")
    ap.add_argument("--repo", default=".")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--dry", action="store_true")
    ap.add_argument("--file")
    ap.add_argument("--include-dirty", action="store_true", help="也改对面在途文件（默认不改）")
    ap.add_argument("--mine-only", action="store_true",
                    help="只改本会话登记过的在途文件（OWN_SCOPE_FILE），对面在途一律跳过")
    ap.add_argument("--expect", type=int, help="预期替换处数，不符则拒写")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = os.path.abspath(args.repo)
    dirty = dirty_files()

    targets = []
    if args.file:
        targets = [args.file]
    else:
        for r in SCAN_ROOTS:
            for dp, _, fns in os.walk(os.path.join(root, r)):
                if "/build/" in dp + "/":
                    continue
                for fn in fns:
                    if fn.endswith(".kt"):
                        p = os.path.join(dp, fn)
                        rel = os.path.relpath(p, root)
                        if any(x in p for x in EXCLUDE):
                            continue
                        targets.append(rel)

    mine = own_scope()
    # 函数形参索引：位置参数那遍要靠它判断"这个位置能不能收 Painter"。
    # 扫全量源码（不只 targets）——被调函数常定义在别的文件里。
    sigs = _scan_funcs([os.path.join(root, r) for r in SCAN_ROOTS])
    total, files_changed, plan, all_skipped, all_unmapped = 0, [], [], [], []
    skipped_peer = []
    for rel in sorted(targets):
        if args.include_dirty:
            pass
        elif args.mine_only:
            # 只碰自己登记过的在途文件；对面在途（脏且不在我的范围）跳过并计数
            if is_dirty(rel, dirty) and not in_mine(rel, mine):
                skipped_peer.append(rel)
                continue
        elif is_dirty(rel, dirty):
            # 默认：脏文件一律跳过。**这会让默认运行什么都做不了**（自己的在途文件
            # 也脏）—— 所以下面要把它明确报出来，不能静默 0 处。
            skipped_peer.append(rel)
            continue
        p = os.path.join(root, rel)
        if not os.path.isfile(p):
            continue
        text = open(p, encoding="utf-8").read()
        new, detail, skipped, unmapped, code_only = swap_in_text(text, allow_automirrored=True, sigs=sigs)
        for name, ic, line, why in skipped:
            all_skipped.append((rel, name, ic, line, why))
        for name, line in unmapped:
            all_unmapped.append((rel, name, line))
        if not detail:
            continue
        new, imp = ensure_imports(new, [ic for _, ic in detail])
        plan.append((rel, len(detail), sorted({ic for _, ic in detail})))
        total += len(detail)
        if args.apply:
            bak = p + ".bak-icons"
            if not os.path.exists(bak):
                open(bak, "w", encoding="utf-8").write(text)
            open(p, "w", encoding="utf-8").write(new)
            files_changed.append(rel)

    if args.expect is not None and total != args.expect:
        print(f"替换处数 {total} ≠ 预期 {args.expect} —— 拒绝写盘", file=sys.stderr)
        return 2

    if skipped_peer and not (args.include_dirty or args.mine_only):
        print(f"（跳过 {len(skipped_peer)} 个在途文件——默认不碰脏文件。"
              f"要改自己登记过的那些加 --mine-only；要连对面的也改加 --include-dirty。）")
    print(f"{'已改' if args.apply else '将改'} {len(plan)} 个文件，替换 {total} 处：")
    for rel, n, ics in plan:
        print(f"  {n:>3} 处  {rel}  → {', '.join(ics)}")
    if all_skipped:
        print(f"\n跳过 {len(all_skipped)} 处（表达式内部：只换一个分支会造出 ImageVector/Painter 混用）：")
        for rel, name, ic, line, why in all_skipped:
            print(f"  {rel}:{line}  Icons.*.{name} → {ic}（{why}）")
        print("  ⇒ 这些位置要人工改成返回 DrawableResource / IconSource。")
    if all_unmapped:
        print(f"\n映射表缺口 {len(all_unmapped)} 处（LIB_TO_IC 里没有这个名字）：")
        for rel, name, line in all_unmapped:
            print(f"  {rel}:{line}  Icons.*.{name}")
        print("  ⇒ 要么补映射（若原型有对应 symbol），要么在图标映射表里登记为『代码特有』。")
    if not args.apply:
        print("\ndry-run；加 --apply 生效（自动备份 *.bak-icons）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
