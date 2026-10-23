#!/usr/bin/env python3
"""
「声明了可空回调、调用点却没传，导致整块静默不画」的常驻守卫。

## 守的是什么

本仓组件用可空回调表达「宿主给不给得出这个能力」：

    onAddDevice: (() -> Unit)? = null

调用方不传 ⇒ null ⇒ 组件里 `if (x != null) { 整块 }` 跳过 ⇒ 功能消失。
**没有编译错、没有异常、没有空态**，用户只觉得「这个功能没做」。

真机现场：`onAddExecDevice` 在 `DesktopShell` 从未传下去，
「添加设备」入口整行不画。当时的守卫测试叫 `PassesAllThreeCallbacks`，
却只断言了两个 —— 名字比内容诚实，它照样绿了。

## 判据（收窄到只有一种形态）

**只报被用作「整块渲染开关」的参数**：

    if (param != null) { ... }   ← 漏传则整块不画
    if (param == null) return    ← 同上
    param?.let { ... }           ← 那块同样画不出来

其余可空回调（有就用、没有就降级）**不报** —— 那是合法的可选能力。
收窄是必需的：不收窄会报出 13 处，其中 12 处是设计上就该漏传的
（手机壳不接桌面桥、`onBack = null` 表示没有返回出口）。误报会让人直接把门禁关掉。

## 两次写错的判据（留作戒）

1. **按测试名含数字判断断言数**：`theTwoStoresShareOneDriverAndMigrateOnce` 里的 Two
   指两个 store，不是两条断言。12 处命中全是误报 —— 名字里的数字描述**场景**。
2. **用「活参数」豁免漏传**：`AgentConsoleScreen.onAddExecDevice` 在组件内出现两次
   （声明 + 向下透传），被判成活参数而豁免 —— 但它是中间层，真正该传它的是
   `DesktopShell`。活参数只说明它被用了，不说明每个该传的点都传了。

退出码：0 = 通过；1 = 有漏传。
"""

import re
import sys
import pathlib

ROOTS = ["composeApp/src", "desktopApp/src/main"]
SKIP_FUNS = {"remember", "derivedStateOf", "LaunchedEffect", "key"}

# **显式豁免**：设计上就该漏传的调用点。每条都必须写清「为什么这是对的」，
# 因为豁免是这个门禁唯一的放松口 —— 不写理由就等于把它关掉。
#
# 判据：这些参数在**那个壳**上有别的进入路径，或那个壳整体不接这套能力。
EXEMPT = {
    # 手机壳不接「执行名单」这套宿主台账桥：手机连的是别人的机器，
    # 那里的台账是对方主权，配不了（见 AgentExecDeviceWiringGuardTest 的同名纪律）。
    ("composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt", "AgentConsoleScreen", "onToggleExecDevice"),
    # 桌面壳用 `localDetailWsId` / `localDetailSessionId` 内部状态进详情与返回，
    # 不经过这三个导航回调（导航回调是给手机壳的路由用的）。
    ("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt", "AgentConsoleScreen", "onLeaveSessionDetail"),
    ("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt", "AgentConsoleScreen", "onLeaveWorkspaceDetail"),
    ("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt", "AgentConsoleScreen", "onOpenSessionDetail"),
    # 桌面设置页的「输入法自检」入口在 `DesktopSettingsSidebar` 里（遍历全部 nav 项），
    # 不走子页的 `onOpenImeSmoke` 回调 —— 那条是手机端从设置首页跳子页用的。
    ("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopHosting.kt", "SettingsDiagSubpage", "onOpenImeSmoke"),
}


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def function_signature(txt: str, fn: str):
    """返回 (签名, 函数体)，找不到则 (None, None)。"""
    m = re.search(r"fun\s+" + fn + r"\s*\(", txt)
    if m is None:
        return None, None
    start = m.end()
    depth, i = 1, start
    while i < len(txt) and depth > 0:
        if txt[i] == "(": depth += 1
        elif txt[i] == ")": depth -= 1
        i += 1
    sig = txt[start:i]
    brace = txt.find("{", i)
    if brace < 0:
        return sig, ""
    depth, j = 0, brace
    while j < len(txt):
        if txt[j] == "{": depth += 1
        elif txt[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    return sig, txt[brace:j]


def nullable_callbacks(sig: str) -> set:
    return set(re.findall(r"(\w+)\s*:\s*\(\([^)]*\)\s*->[^)]*\)\?\s*=\s*null", sig))


def render_gates(cbs: set, body: str) -> set:
    """其中被用作「整块渲染开关」的那些。"""
    out = set()
    for name in cbs:
        n = re.escape(name)
        if re.search(r"if\s*\(\s*" + n + r"\s*!=\s*null", body):
            out.add(name)
        elif re.search(r"if\s*\(\s*" + n + r"\s*==\s*null\s*\)", body):
            out.add(name)
        elif re.search(n + r"\?\.let", body):
            out.add(name)
    return out


def collect(files, owner):
    """fn -> (全部可空回调, 作为渲染开关的那些)；owner 记录定义所在文件。"""
    table = {}
    for f in files:
        txt = strip_comments(read(f))
        for m in re.finditer(r"fun\s+(\w+)\s*\(", txt):
            fn = m.group(1)
            if fn in SKIP_FUNS:
                continue
            sig, body = function_signature(txt, fn)
            if sig is None:
                continue
            cbs = nullable_callbacks(sig)
            if not cbs:
                continue
            prev, gates = table.get(fn, (set(), set()))
            table[fn] = (prev | cbs, gates | render_gates(cbs, body))
            owner.setdefault(fn, str(f))
    return table


def main() -> int:
    files = []
    for r in ROOTS:
        files += [q for q in pathlib.Path(r).rglob("*.kt")]

    owner = {}
    table = collect(files, owner)
    problems = []

    for target in files:
        txt = strip_comments(read(target))
        for fn, (cbs, gates) in table.items():
            if not gates:
                continue
            for m in re.finditer(r"\b" + fn + r"\s*\(", txt):
                head = txt[max(0, m.start() - 12):m.start()]
                if re.search(r"fun\s+$", head):
                    continue
                start = m.end()
                depth, i = 1, start
                while i < len(txt) and depth > 0:
                    if txt[i] == "(": depth += 1
                    elif txt[i] == ")": depth -= 1
                    i += 1
                args = txt[start:i]
                named = set(re.findall(r"(\w+)\s*=", args))
                passed = named & cbs
                if not passed:
                    continue  # 这个壳整体不接这套能力（设计）
                # **同文件内调用不算「宿主漏传」**：那是组件在内部复用自己。
                # 反例：`MineCabinetView` 出现在 `CabinetScreen` 里 → 曾被报成漏传 `onBack`，
                # 实际是同一个屏内部的嵌套调用，`onBack` 由更外层给。
                if owner.get(fn) == str(target):
                    continue
                missing = {
                    m for m in (gates - passed)
                    if (str(target), fn, m) not in EXEMPT
                }
                if missing:
                    problems.append((str(target), fn, sorted(missing)))

    unique = []
    seen = set()
    for item in problems:
        key = (item[0], item[1], tuple(item[2]))
        if key not in seen:
            seen.add(key)
            unique.append(item)

    if not unique:
        print("可空回调接线门禁通过：没有『渲染开关漏传』的入口")
        return 0

    print("可空回调接线门禁失败 —— 这些渲染开关漏传，对应整块会在界面上静默消失：")
    for f, fn, missing in unique:
        print(f"  · {fn}  漏 {missing}")
        print(f"      {f}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
