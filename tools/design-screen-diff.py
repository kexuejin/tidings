#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""逐屏还原度差集：原型该屏的取值集合 vs 代码落点的取值集合。

## 为什么需要它

逐屏比对以前是手工的：读 CSS → 读 Kotlin → 手写一张对照表。一屏一轮，台账有多少屏就是多少轮。
而两个提取器其实早就存在：

  - `design-converge.py` 已经在读原型的 CSS 声明（`iter_declarations` 拆简写、认五个维度）
  - `design_contract.py` 已经在读代码的 token 权威源

缺的只是**按屏 join**。这个工具补的就是这一跳，而且**不需要写 class ↔ Compose 组件映射表**：

  原型侧：派发表 `'m-cabinet':screenCabinetMine` → 渲染函数体 → 它用到的 class 集合
          （递归跟进它调用的 helper，因为 `topbar()` / `card()` 生成的 chrome 不在字面量里）
  代码侧：台账「代码落点」→ 该 .kt（再跟进它调用的 `<Name>View(` 一跳，例如
          `CabinetScreen.kt` → `PeerCabinetView.kt`）→ 该文件里出现的取值集合

然后逐维做**集合差集**。它回答的是「这一屏的字号/间距/圆角/字重谱系对得上吗」，
不回答「哪个元素错了」——但它把「有没有偏差、偏差在哪几个值上」变成一秒钟的事，
人只需要去看那几个值。

## 它抓得到什么（实测）

  m-cabinet  原型 radius {4,7,9,12,50%}   代码 {}                 ← 整维未处理
  m-cabinet  原型 spacing 有 9/10/11/13/14  代码 .dp 只有 {4,8,16,20,28,32,44}

## 它抓不到什么（已知边界，别把它当万灵药）

  - **值对得上 ≠ 用对地方**。两边都有 14，不等于原型用 14 的那一处代码也用了 14。
    定位到值之后仍要人看那一处。
  - 图上「像不像」它一个字都答不了——那是并排签收（人）与参考图目视。
  - 原型里 `calc()` / 百分比 / `auto` 这类算不出数的值只记账不比对。

## 用法

    python3 scaffold/tools/design-screen-diff.py --root scaffold --screen m-cabinet
    python3 scaffold/tools/design-screen-diff.py --root scaffold --all --markdown docs/plans/...
    python3 scaffold/tools/design-screen-diff.py --root scaffold --check     # 棘轮：差集只许变小
    python3 scaffold/tools/design-screen-diff.py --root scaffold --baseline
    python3 scaffold/tools/design-screen-diff.py --self-test
"""

import argparse
import hashlib
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

CODE_ROOT = "."
DESIGN_SOURCES = ["prototype/relay.html"]
LEDGER_REL = "docs/plans/2026-10-10-relay-screen-pairing-ledger.md"
BASELINE_REL = "tools/design-screen-diff-baseline.json"

# 落点相对这个目录
LANDING_PREFIX = "composeApp/src/commonMain/kotlin/app/relay/"

# 维度 → (CSS 属性, 容差)。与 design-converge.py 同一套维度名，便于并排读。
DIMENSIONS = {
    # 只收 padding / gap：Compose 用 Spacer 与 padding 表达间距，**不照抄 margin**；
    # 原型还常用内联 `style="height:10px"` 做卡片间距，那不在 CSS 里、扫描看不见。
    # 把 margin 算进来会让差集里永远挂着一批代码侧无法对应的值（实测 -3/1/3/5/7 全来自 margin）。
    "spacing": ("padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
                "gap", "row-gap", "column-gap"),
    "radius": ("border-radius", "border-top-left-radius", "border-top-right-radius",
               "border-bottom-left-radius", "border-bottom-right-radius"),
    "font-size": ("font-size",),
    "font-weight": ("font-weight",),
}
DIM_ORDER = ("font-size", "font-weight", "spacing", "radius")

FONT_WEIGHTS = {
    "Thin": 100, "ExtraLight": 200, "Light": 300, "Normal": 400, "Medium": 500,
    "SemiBold": 600, "Bold": 700, "ExtraBold": 800, "Black": 900,
}

PX = re.compile(r"^(-?\d+(?:\.\d+)?)px$")
DP_LIT = re.compile(r"(\d+(?:\.\d+)?)\.dp\b")
SP_LIT = re.compile(r"(\d+(?:\.\d+)?)\.sp\b")
SHAPE_LIT = re.compile(r"RoundedCornerShape\(\s*(\d+(?:\.\d+)?)\.dp")

# ShapeRoles.kt 里的圆角角色属性（val X: Shape = RoundedCornerShape(N.dp)）；键名降首字母，
# 与 design_contract._parse_object_dp 的产物形态一致。
SHAPE_ROLE_PROP = re.compile(
    r"val\s+(\w+)\s*:\s*\w+\s*=\s*RoundedCornerShape\(\s*(\d+(?:\.\d+)?)\.dp\s*\)"
)
# **第十九个洞**：药丸圆角没有数字对应物。原型写 `border-radius:999px`（`--r-chip`），
# Compose 的惯用写法是 `CircleShape` —— 量具只认数字圆角，于是**恒挂一条「只原型 999」**。
# 实测 33 屏报这一条（m-chat / m-msgmenu / m-agents / d-cabinet / d-nodes …），
# 全是同一个单位换算缺口，不是 33 处视觉偏差。
CIRCLE_SHAPE = re.compile(r"\bCircleShape\b")
# **第五十一个洞**：按钮字重同样没有数字对应物。M3 的 `Button`/`OutlinedButton`/`TextButton`
# 默认吃 `labelLarge`（本仓字重 500），而原型 `.btn{font-weight:500}`（`relay.html:353-355`）
# 是**显式声明**。量具的字重分支只认写出来的 `FontWeight.X`，继承来的 500 进不了代码集合 ⇒
# 只要哪一屏真用了 `.btn`，就恒挂一条「只原型 500」。`d-nodes` 就是被这条咬住的：
# W4 照原型把行名字重从 Medium(500) 改成 SemiBold(600) 之后，那个**偶然掩盖**缺口的 500 没了，
# 于是「只原型 500」浮出来 —— 报的是量具的口径不对称，不是代码退化。
# 注意 `Button(` 前面的 `(?<![\w.])`：`IconButton(` 的 `n` 是词字符，不许命中。
BUTTON_CALL = re.compile(
    r"(?<![\w.])(?:OutlinedButton|TextButton|FilledTonalButton|ElevatedButton|Button)\s*\(")
VAR_USE = re.compile(r"var\(\s*(--[\w-]+)\s*\)")
# 间距语境的调用。原型侧的 margin 已经丢掉，这里也必须只认间距，两边才是同一种量。
# **第二十三个洞**：`PaddingValues(...)` 不在名单里。列表的内容内边距在 Compose 里
# 只能写成 `contentPadding = PaddingValues(start = 14.dp, top = 12.dp, …)`
# —— 那正是原型 `.msgs{padding:12px 14px 6px}` 的对应物，量具却对它完全瞎：
# 实测 `ChatScreen.kt:411` 的 `PaddingValues(horizontal = 16.dp, vertical = 12.dp)`
# 一个值都不进代码集合，于是原型那三个值被整片报成「只原型」。
SPACING_CALLS = re.compile(
    r"\b(?:padding|spacedBy|PaddingValues)\s*\(((?:[^()]|\([^()]*\))*)\)"
    r"|\bSpacer\s*\(\s*Modifier\s*\.\s*(?:width|height)\s*\(((?:[^()]|\([^()]*\))*)\)")
# **第二十六个洞**：`val sp = LocalAppSpacing.current` 之后写 `sp.lg14` 是 Compose 里的惯用写法，
# 量具原来只认全名 `LocalAppSpacing.current.lg14` ⇒ 作者一起别名，这个切片里的间距值
# **全部看不见**。实测把 `DesktopPreferences.kt` 的 13 处字面量迁成档位之后，
# `d-settings-desk` 从 11 处涨到 18 处、代码侧从 {2,4,6,8,10,12,14,16} 变成空 ——
# **迁移本身把值从量具里抹掉了**，方向正好和门禁想要的相反。
LOCAL_SP_ALIAS = re.compile(r"\bval\s+(\w+)\s*=\s*LocalAppSpacing\.current\b")
# 派发表里两种写法：`'m-cabinet':screenCabinetMine`（函数引用，**后面没有括号**）
# 与 `'d-search':()=>screenDSearch('协议')`（箭头包装）。尾括号必须是可选的，
# 否则整个派发表一条都匹配不上（实测踩过）。
DISPATCH = re.compile(r"'([a-z0-9][\w-]*)'\s*:\s*(?:\(\s*\)\s*=>\s*)?([A-Za-z_$][\w$]*)")
FN_DEF = re.compile(r"^function\s+([A-Za-z_$][\w$]*)\s*\(", re.M)
# 箭头函数 helper：`const shareChip = i => `<span class="cbchip${…}">`。
# 早期只认 `function name(`，于是 shareChip 的 class 字面量从未进过集合 ⇒
# `.cbchip{padding:5px 11px;border-radius:8px}` 对任何用到它的屏都不可见。
FN_ARROW = re.compile(
    r"^(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:\([^)]*\)|[A-Za-z_$][\w$]*)\s*=>", re.M
)
CLASS_LIT = re.compile(r'class="([^"]*)"')
HELPER_CALL = re.compile(r"\b([a-z_$][\w$]*)\s*\(")
# 模板插值：`class="cbchip${sel?' on':''}"`。里面那个 `on` 是**真 class**，不能丢。
INTERP = re.compile(r"\$\{.*?\}")
# **第二十八个洞**：`proto_values` 以前只按"class 集合"判命中，量的是"这个 class 出现过的
# 规则"，不是"这个元素上最终算出来的值"。两个后果：
#   ① `.btn{padding:var(--pad-btn)}` 与 `.btn.sm{padding:6px 10px}` 都命中（`btn`/`sm`
#      都在集合里），可浏览器里 `.btn.sm` 特异性更高，`.btn` 的 padding **根本没生效** ——
#      实测 `d-transferlog` 因此凭空挂着「只原型 7、12」两个值，而这一屏一个 `.btn` 都没有。
#   ② `.hint code` 只要 `hint` 在本屏就命中，可本屏的 `.hint` 里**没有 `<code>` 元素** ——
#      实测 `d-transferlog` 因此挂着「只原型 4」（padding 1px 4px / border-radius 4px）。
# 修法：先按**元素**（标签名 + class 集合）算，只认"简单复合选择器"（标签名 + class，
# 无组合子/伪类/属性），逐元素按（变体层级, 特异性, 书写顺序）决胜；带组合子的选择器
# 仍走下面的 class 集合模型（见 §3.17 的已知残余）。
ELEMENT_LIT = re.compile(r"<([a-z][a-z0-9-]*)([^<>]*)")
CLASS_ATTR = re.compile(r"""class\s*=\s*(?:"([^"]*)"|'([^']*)')""")
# 简单复合选择器：可选标签名 + 至少一个 class，别无他物（`.btn` / `.btn.sm` / `button.btn`）
SIMPLE_SEL = re.compile(r"^\s*([a-z][a-z0-9-]*)?((?:\.[\w-]+)+)\s*$")
CALL_ANY = re.compile(r"\b([A-Z]\w{2,})\s*\(")
VIEW_CALL = re.compile(r"\b([A-Z]\w*(?:View|Pane|Screen|Dialog|Shell|Overlay|Window|Host))\s*\(")
# 屏级后缀：一个以它结尾的函数就是"画一屏/一个壳"的东西，不是行组件。
# `landing_slices` 用它做"落点文件里一个屏级函数都没选中时补回来"，
# `_is_parts_file` 用它判"这个文件是零件文件还是又一个屏"。
SCREEN_LEVEL_SUFFIX = ("Screen", "Pane", "View", "Shell", "Host", "Window", "Overlay")
# 第四十四个洞的判据：`feature/<x>/` 是"一屏一个目录"，`ui/` 是"一个目录装一堆屏"。
# 第 2 跳只允许从前者出发（见 `landing_slices` 里 `_hop` 的注释）。
_FEATURE_MARK = os.path.join("commonMain", "kotlin", "app", "relay", "feature") + os.sep
# **不能加行首锚定**（`^\s*` + re.M）：原型把多条声明压在一行
# （`:root` 里 `--fs-body:14px; --fs-bub:14px; --r-card:16px; --r-bubble:12px; --r-ctl:12px;`），
# 锚定后**只有每行第一条**被抓到，`--r-card`/`--r-ctl`/`--padc`/`--fs-ctitle`/`--fs-btn`/
# `--pad-row`/`--pad-btn`/`--fs-hint`/`--fs-row1`/`--fs-row2`/`--status` 全部落进 unresolved
# 桶后被静默丢弃 ⇒ 差集是欠报数。实测 `:root` 只解析出 24 个变量。
# 去掉锚定后实测 `--fs-body:14px; --fs-bub:14px; --r-card:16px; --r-bubble:12px; --r-ctl:12px;`
# 五个全中；而用途行 `border-radius:var(--r-chip);` 不误抓（`--r-chip` 后面是 `)` 不是 `:`）。
#
# 第二条同类坑：**最后一条声明可以没有分号**（块尾）。原来要求 `;` 收尾，
# 于是 `:root{--a:1px; --b:2px; --c:3px}` 只抓到 `--a`/`--b`，`--c` 静默丢失；
# `:root{--a:1px}` 更是一条都抓不到。所以收尾改成 `(?:;|$)`，并用 `[^;\n]+?` 防止
# 跨行吞掉下一条（`re.M` 让 `$` 在行尾也成立）。
VAR_DEF = re.compile(r"(--[\w-]+)\s*:\s*([^;\n]+?)\s*(?:;|$)", re.M)
KT_PROP = re.compile(r"val\s+(\w+)\s*:\s*Dp\s*=\s*(\d+(?:\.\d+)?)\.dp")
KT_ROLE = re.compile(r"(\w+)\s*=\s*TextStyle\((.*?)\n\s*\),", re.S)
KT_SHAPE = re.compile(r"(\w+)\s*=\s*(?:androidx\.compose\.foundation\.shape\.)?RoundedCornerShape\((\d+(?:\.\d+)?)\.dp\)")


class DiffError(RuntimeError):
    pass


# ── 原型侧 ────────────────────────────────────────────────────────
def _load_converge():
    """复用 design-converge.py 的 CSS 解析，不重写第二份。"""
    path = os.path.join(HERE, "design-converge.py")
    spec = importlib.util.spec_from_file_location("_dc_converge", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def read_prototype(repo):
    """返回 (html, css, meta)。meta 钉在 sha 上——设计源在被别人改。"""
    paths = [os.path.join(repo, p) for p in DESIGN_SOURCES]
    for p in paths:
        if os.path.exists(p):
            html = open(p, encoding="utf-8").read()
            m = re.search(r"<style[^>]*>(.*?)</style>", html, re.S)
            if not m:
                raise DiffError(f"{p} 里找不到 <style> 块")
            css = re.sub(r"/\*.*?\*/", "", m.group(1), flags=re.S)
            meta = {
                "path": DESIGN_SOURCES[0],
                "sha256": hashlib.sha256(html.encode("utf-8")).hexdigest()[:12],
                "bytes": len(html.encode("utf-8")),
            }
            return html, css, meta
    raise DiffError(f"设计源不存在：{paths}")


def var_scopes(css):
    """返回 (root_vars, win_vars)。

    原型把移动端默认写在 `:root`，桌面端在 `.win` 里覆盖（`--r-card` 16→12、`--padc`
    10px 14px 14px→8px 13px 13px 等）。**用错作用域会让每个桌面屏的间距整体偏一档**，
    所以按屏的 form 选作用域，不能只取一份。
    """
    root, win = {}, {}
    for sel, body in re.findall(r"([^{}]*)\{([^{}]*)\}", css):
        sels = [s.strip() for s in sel.split(",")]
        if ":root" not in sels and ".win" not in sels:
            continue
        bucket = root if ":root" in sels else win
        for k, v in VAR_DEF.findall(body):
            bucket[k] = v.strip()
    return root, win


def expand_vars(val, variables):
    """`var(--padc)` → `10px 14px 14px`。嵌套的 var 迭代展开（原型最深两层）。"""
    out = val
    for _ in range(6):
        m = VAR_USE.search(out)
        if not m:
            break
        name = m.group(1)
        if name not in variables:
            break
        out = out[:m.start()] + variables[name] + out[m.end():]
    return out


def screen_functions(html):
    """派发表 `'m-cabinet':screenCabinetMine` → {屏 id: 渲染函数名}。"""
    out = {}
    for sid, fn in DISPATCH.findall(html):
        out.setdefault(sid, fn)
    if not out:
        raise DiffError("原型里找不到派发表（形如 'm-cabinet':screenCabinetMine）")
    return out


def screen_forms(html):
    """SCREENS 数组里的 form —— 决定用 :root 还是 .win 的变量作用域。"""
    out = {}
    for m in re.finditer(r"\{id:'([\w-]+)',\s*form:'(\w+)'", html):
        out[m.group(1)] = m.group(2)
    return out


def _scan_body(html, i):
    """从 `{`（或 `=>` 之后）取函数体：块体走花括号配对，表达式体扫到深度 0 的 `;`。

    表达式体基本都是模板字面量（`i => `<span class="cbchip…">``）。反引号里**不数括号** ——
    模板里的 `${…}` 会把深度搅乱，扫到第一个 `}` 就截断，于是 class 字面量又丢了。
    """
    j = i
    while j < len(html) and html[j] in " \t\r\n":
        j += 1
    if j < len(html) and html[j] == "{":
        depth, k = 0, j
        while k < len(html):
            c = html[k]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return html[j:k + 1]
            k += 1
        return None
    depth, tick, k = 0, False, j
    while k < len(html):
        c = html[k]
        if c == "`":
            tick = not tick
        elif not tick:
            if c in "([{":
                depth += 1
            elif c in ")]}":
                depth -= 1
            elif depth == 0 and c == ";":
                return html[j:k]
        k += 1
    return None


def fn_bodies(html):
    """{函数名: 函数体}。花括号配对取体，避免 `function a(){...}` 里嵌 `{}` 时截断。"""
    out = {}
    spans = [(m.group(1), html.index("{", m.end())) for m in FN_DEF.finditer(html)]
    spans += [(m.group(1), m.end()) for m in FN_ARROW.finditer(html)]
    for name, i in spans:
        body = _scan_body(html, i)
        if body:
            out.setdefault(name, body)
    return out


def _class_tokens(lit):
    """`class="…"` 字面量 → 静态 class 集合（含插值里单引号字符串中的真 class）。

    `class="cbfrow${sel?' sel':''}"` → `{cbfrow, sel}`；
    `class="cbchip${cls?' '+cls:''}"` → `{cbchip}`（`cls` 是变量名，被 `$'\"{}` 滤掉）。
    """
    for interp in INTERP.findall(lit):
        lit = lit.replace(interp, " ")
        for q in re.findall(r"'([^']*)'", interp):
            lit += " " + q
    out = set()
    for tok in lit.split():
        tok = tok.strip()
        if tok and not re.search(r"[$'\"{}]", tok):
            out.add(tok)
    return out


def collect_elements(bodies, fn, depth=2):
    """该渲染函数闭包里的**元素**集合：`(标签名, frozenset(class))`。

    与 `collect_classes` 跟同一批 helper（否则 `topbar()` / `card()` / `ic()` 生成的元素
    一个都看不见），但保留"哪些 class 长在同一个元素上"这个信息 —— 第 28 洞要的就是它。
    """
    elements, done = set(), set()
    stack = [(fn, depth)]
    while stack:
        name, d = stack.pop()
        body = bodies.get(name)
        if body is None or name in done:
            continue
        done.add(name)
        for m in ELEMENT_LIT.finditer(body):
            tag, attrs = m.group(1), m.group(2)
            cls = set()
            for a in CLASS_ATTR.finditer(attrs):
                cls |= _class_tokens(a.group(1) or a.group(2) or "")
            elements.add((tag, frozenset(cls)))
        if d > 0:
            for call in HELPER_CALL.findall(body):
                if call in bodies and call not in done:
                    stack.append((call, d - 1))
            for cand in bodies:
                if cand in done or len(cand) < 4:
                    continue
                if re.search(r"(?<![\w$])" + re.escape(cand) + r"(?![\w$])", body):
                    stack.append((cand, d - 1))
    return elements


def collect_classes(bodies, fn, depth=2):
    """该渲染函数用到的 class 集合，**递归跟进它调用的 helper**。

    这一步不能省：`topbar('文件柜', …)` 生成的是 `<div class="topbar">`，字面量里根本没有，
    只扫函数体就会漏掉整个顶栏（实测漏掉 `.topbar h1` 的 24px，于是差集里凭空多出一个
    "代码有 22 没有 24" 的假缺口）。
    """
    classes, done = set(), set()
    stack = [(fn, depth)]
    while stack:
        name, d = stack.pop()
        body = bodies.get(name)
        if body is None or name in done:
            continue
        done.add(name)
        for lit in CLASS_LIT.findall(body):
            # class="cbfrow${sel?' sel':''}" / class="topbar${cls?' '+cls:''}"
            # 静态前缀要留下（`cbfrow` / `topbar`），插值里的**单引号字符串**也要留下
            # （`${r.sel?' sel':''}` → `sel`，`${r.on?' on':''}` → `on`，由 GENERIC_TOKENS 过滤）。
            # 早期实现把整个含 `${` 的 token 丢掉，于是 `shareChip()` 生成的
            # `class="cbchip${…}"` 里的 `cbchip` 从未进过集合 ⇒ `.cbchip{border-radius:8px}`
            # 对任何用到它的屏都不可见（实测 m-cabinet 的 proto radius 因此少了 8）。
            classes |= _class_tokens(lit)
        if d > 0:
            for call in HELPER_CALL.findall(body):
                if call in bodies and call not in done:
                    stack.append((call, d - 1))
            # **第二十四个洞**：helper 还可以是**回调引用**，不是调用。
            # `byRecent(CONVS).map(convRow)` 里的 `convRow` 后面没有括号，`HELPER_CALL`
            # 看不见它 ⇒ 会话行的整个 class 集合（`.tag` / `.t1` / `.t2` / `.rt` /
            # `.tgroup`）从未进过 `m-home-recent` ⇒ `.tag{padding:1.5px 6px}` 的 1.5
            # 与 `.tag{font-weight:600}` 全部凭空消失，报出来的是"代码多了 1.5/600"。
            # 判据：函数体里**以整词出现**的任何已知 helper 名都算跟进（`.map(convRow)`
            # 与 `onclick="go('m-chat')"` 之外不会有别的整词碰撞 —— 名字都带驼峰或下划线）。
            # **第四十三个洞**：`len(cand) < 4` 这条护栏把短 helper 名一并挡掉了，
            # 而渲染链上恰好全是短名：`msgsOf = list => list.map(msg).join('')` 里的
            # `msg` 只有 3 个字符 ⇒ `msg` 从未被跟进 ⇒ `.msg` / `.mav` / `.bub` /
            # `.sender` / `.notice` / `.daysep` 整个消息渲染层的 class 一个都没进过
            # `m-chat` / `m-chat-solo` / `m-chat-group`（实测集合 44 → 53）。护栏的本意是
            # 防「属性访问撞名」，所以改成**排除前面带 `.` 的出现**（`m.msg` 不算），
            # 而不是按长度一刀切。`ic`（2 字符）也放行了：它的体里没有 class 字面量，
            # 跟进它不改变任何结论，但少一条特例。
            for cand in bodies:
                if cand in done or len(cand) < 2:
                    continue
                if re.search(r"(?<![\w$.])" + re.escape(cand) + r"(?![\w$])", body):
                    stack.append((cand, d - 1))
    return classes


def _sel_parts(sel):
    """把一个选择器拆成复合段：后代空格与 `>` / `+` / `~` 都算组合子。"""
    return [p for p in re.split(r"[>+~]|\s+", sel.strip()) if p]


def _compound_ok(part, by_tag, all_cls):
    """一个**纯元素段**（`code` / `h3` / `svg` / `img` / `input`）在本屏闭包里有没有对应元素。

    第 28 洞的后代那一半：`.hint code` 的 token 只有 `hint`（元素选择器对 `SEL_CLASS`
    不可见），于是只要本屏有 `.hint` 就命中 —— 可本屏的 `.hint` 里根本没有 `<code>`
    （全文件的 `<code>` 都在 `.annot` 注解块里，那是明确不实现的东西）。

    **只判纯元素段，带 class 的段一律放行**：`class="topbar${cls?' '+cls:''}"` 这种
    动态拼出来的 class（`set` / `on` / `path` / `mono`）静态扫不到，硬判会凭空造出假阴性
    （实测 `.topbar.set .tbinner` 22 处、`.kv .v.path` 6 处、`.hdmore.on .menu` 5 处）。
    元素名没有动态一说，所以这一段可以放心判。
    """
    p = part.strip()
    if not re.match(r"^[a-z][a-z0-9-]*$", p):
        return True
    return bool(by_tag.get(p))


def proto_values(css, variables, classes, variant=None, elements=None, settings=False):
    """该屏 class 集合上的原型取值，逐维归一成数值集合。算不出数的就进 unparsed。

    **第 27 洞**：这里以前只是"并集"——命中的声明全部并进取值集合。可原型大量用
    `.win`（桌面窗口外壳）与 `.winset`（设置档窗口）做**层叠覆盖**，浏览器里基线值是被
    覆盖掉的，并集却把两条都算成"这一屏该有的值"。于是 `form:'desktop'` 的屏报出来的
    全是 `.grphead{padding:14px 4px 6px}` 这种**基规则**，而真正生效的
    `.win .grphead{padding:12px 3px 5px}` 一条都进不来 —— 越改越对、数字越难看。
    修法：先按 `variant` 给每条声明定层级（`_selector_rank`），再对
    「去掉变体前缀的选择器 + 属性」做**覆盖**（同键只留层级最高的那条，同级后写的赢）。

    **第 28 洞**：上面那个"键"仍然是**选择器字符串**，所以 `.btn` 与 `.btn.sm` 是两个键、
    两条都留下 —— 可浏览器里它们是**同一个元素上的竞争**，`.btn.sm` 特异性更高，
    `.btn{padding:var(--pad-btn)}` 根本没生效。于是 `d-transferlog` 这一屏（一个 `.btn`
    都没有，只有 `.btn.sm`）凭空挂着「只原型 7、12」。
    修法：`elements` 非空时，**简单复合选择器**（标签名 + class，无组合子/伪类/属性）
    改走"逐元素 + 特异性"模型：对每个元素、每个属性，只留（变体层级, 特异性, 书写顺序）
    最高的那一条。带组合子的选择器（`.tlist li` / `.hint code` / `.li.ghost .t1`）
    仍走 class 集合模型 —— 那半边是已知残余，见文档 §3.17。
    """
    conv = _load_converge()
    prop2dim = {}
    for dim, props in DIMENSIONS.items():
        for p in props:
            prop2dim.setdefault(p, []).append(dim)
    values = {d: set() for d in DIMENSIONS}
    unparsed = {d: set() for d in DIMENSIONS}
    # 逐元素模型的索引：标签名 → 该标签出现过的 class 集合
    by_tag, all_cls = {}, []
    for etag, ecls in (elements or ()):
        by_tag.setdefault(etag, []).append(ecls)
        all_cls.append(ecls)
    winners = {}   # class 集合模型：键 = (去变体前缀的选择器, 属性)
    per_elem = {}  # 逐元素模型：键 = (标签名, class 集合, 属性)
    for sel, prop, val in conv.iter_declarations(css):
        dims = prop2dim.get(prop)
        if not dims:
            continue
        best = None
        for one in (x.strip() for x in sel.split(",")):
            r = _selector_rank(one, classes, variant, settings)
            if r is not None and (best is None or r > best[0]):
                best = (r, one)
        if best is None:
            continue
        rank, one = best
        bare = _strip_variant(one, variant, settings)
        simple = SIMPLE_SEL.match(bare) if elements else None
        if simple is not None:
            tag = simple.group(1)
            toks = tuple(sorted(SEL_CLASS.findall(simple.group(2))))
            # 特异性：(class 数, 元素名数) —— 同元素同属性只留最高的，同级后写的赢
            spec = (len(toks), 1 if tag else 0)
            hit = False
            for ecls in (by_tag.get(tag, ()) if tag else all_cls):
                if not all(t in ecls for t in toks):
                    continue
                hit = True
                key = (tag or "*", ecls, prop)
                prev = per_elem.get(key)
                if prev is None or (rank, spec) >= (prev[0], prev[1]):
                    per_elem[key] = (rank, spec, val)
            if hit:
                continue
            # 没有任何元素静态配得上（多半是 `class="topbar${cls?' '+cls:''}"` 这种动态 class）——
            # **退回 class 集合模型**：宁可多算，也不要为了消一个假阳性造出一个假阴性
        key = (bare, prop)
        if elements and not all(
                _compound_ok(p, by_tag, all_cls) for p in _sel_parts(bare)):
            # 第 28 洞的后代那一半：`.hint code` 里的 `code` 在本屏闭包里没有对应元素
            continue
        prev = winners.get(key)
        if prev is None or rank >= prev[0]:
            winners[key] = (rank, val)

    def absorb(prop, val):
        dims = prop2dim.get(prop)
        if not dims:
            return
        val = expand_vars(val, variables)
        for dim in dims:
            for raw in conv._values_of(prop, val):
                raw = raw.strip()
                if dim == "font-weight":
                    n = _num(raw)
                    (values if n is not None else unparsed)[dim].add(
                        n if n is not None else raw)
                    continue
                m = PX.match(raw)
                if m:
                    values[dim].add(float(m.group(1)))
                elif _num(raw) is not None:
                    values[dim].add(float(raw))
                else:
                    unparsed[dim].add(raw)

    for (_key, prop), (_rank, val) in winners.items():
        absorb(prop, val)
    for (_etag, _ecls, prop), (_rank, _spec, val) in per_elem.items():
        absorb(prop, val)
    return values, unparsed


SEL_CLASS = re.compile(r"\.([\w-]+)")
# 变体 / 外壳作用域：这些选择器画的是**另一种渲染上下文**（大字号、深色、桌面窗口外壳），
# 不是本屏的基线值。它们的 class 会因为外壳 helper 被收进本屏集合，闭合判据挡不住 ——
# `body.font-lg .device .topbar h1{font-size:31px}` 就是这样混进来的。
VARIANT_SEL = re.compile(r"^\s*(?:body|html)\b|\.win\b|\.device\b|data-theme|data-bare")

# 原型外壳 / 注解：**不是这一屏的基线值，而且明确不实现**。
# `.annot` 是原型里的设计说明块（「为什么是四张卡、不是一页设置。」那一大段），
# `design-converge.py` 早就把它列进 `CHROME_SELECTORS` 了，但本工具没跟上 ——
# 于是 `.annot b{font-weight:600}` / `.annot code{border-radius:4px}` /
# `.annot .mono{font-size:11px}` 这类规则被算成「本屏该有的值」。
# **它为什么躲得过闭合判据**：`SEL_CLASS` 只认 `.` 开头的 class，元素选择器
# （`code` / `b` / `span`）对它不可见 ⇒ `.annot code` 的 token 只有 `annot`，
# 而 `annot` 确实在本屏 class 集合里 ⇒ 判为命中。实测 `m-cabinet` 命中 65 条声明里
# 有 10 条来自 `.annot`（spacing 6 / font-size 2 / font-weight 1 / radius 1）。
CHROME_TOKENS = ("#stage", "#chrome", ".capdiv", ".annot", "#gallery", ".dnav", ".drawer")


def _is_chrome(sel):
    """选择器里出现外壳 / 注解块 ⇒ 不算本屏的取值。"""
    for t in CHROME_TOKENS:
        if re.search(r"(?<![\w-])" + re.escape(t) + r"(?![\w-])", sel):
            return True
    return False


# 本屏变体前缀：`.win` 是桌面窗口外壳，`.winset` 是**设置档**窗口（多一个 `winset` class）。
# 它们的规则**是这一屏的取值**（浏览器里覆盖掉基线），只是以前被 `VARIANT_SEL` 整段挡掉了。
#
# **第五十二洞：`.win` 与 `.winset` 的作用域不一样。** 原型 `win()` 里
# `const set = s.tier === 'settings' ? ' winset' : '';` —— `.winset` 只加在 tier 为
# `settings` 的那一档外壳上（FORMS 里只有 `{id:'settings', tier:'settings'}` 一条）。
# 以前这里把两者一视同仁（`LEADING_VARIANT` 不分），于是 `.winset .li{gap:8px}` 这条
# **设置窗专属**的规则被算进了每一个桌面屏 —— 实测 `d-nodes` 的 `.li{gap:10px}` 被它
# 覆盖掉，而代码侧真有 10，报出一条「只代码 10」的假阳性（差集里挂了好几轮）。
# 现在只有 settings 档那一屏认 `.winset`，其余桌面屏只认 `.win`。
LEADING_VARIANT = re.compile(r"^\s*\.(win|winset)\b\s*")
ANY_VARIANT = re.compile(r"\.(?:win|winset)\b")


def _strip_variant(sel, variant, settings=False):
    """去掉前导的变体 token，得到层叠比较用的"同一选择器"键。

    只去掉**本屏真的认**的那些前缀：非 settings 档的屏不许把 `.winset` 当外壳，
    否则 `.winset .li` 会被剥成 `.li` 去和基线 `.li` 抢键 —— 那正是第五十二洞。
    """
    if variant != "win":
        return sel.strip()
    out = sel
    while True:
        m = LEADING_VARIANT.match(out)
        if not m:
            break
        if m.group(1) == "winset" and not settings:
            break
        out = out[m.end():]
    return out.strip()


SETTINGS_TIER_CALLS = ("setTitlebar", "dsetPage")


def settings_tier_screens(bodies, fns):
    """哪些桌面屏渲染在 `.winset`（设置档）外壳里 —— 原型自己的规则的等价复现。

    原型里这件事**没有静态表**：`win(curScreen, curForm)` 按 `SIZEOF[curForm].tier`
    决定加不加 `.winset`，而屏 id → form 的对应只在截图脚本（`prototype/shoot-relay.py`
    的 shot 表第三列）里。relay.html 内部唯一可靠的等价判据是：设置档那几屏的渲染函数体
    **都**调 `setTitlebar()` + `dsetPage(...)`，而这两个 helper 只有它们用
    （`:4343` transferlog / `:5160` settings / `:5196` settings-data /
    `:5218` settings-desk / `:5247` settings-ime）。实测 5/5 命中、其余 25 个桌面屏
    零误报。**原型的 `win()` 一旦改了这条规则，这里必须跟着改。**
    """
    out = set()
    for sid, fn in fns.items():
        body = bodies.get(fn) or ""
        if all(f"{c}(" in body for c in SETTINGS_TIER_CALLS):
            out.add(sid)
    return out


def _selector_rank(sel, classes, variant=None, settings=False):
    """该选择器对本屏的适用层级：None = 不适用，0 = 基线，1 = 本屏变体覆盖。

    判据三层：① 整页变体（`body.font-lg` / `html[data-theme=dark]` / `data-bare`）与
    设备外壳 `.device` 永远不是本屏基线；② 变体前缀只认**本屏那一档**（桌面屏认
    `.win`；`.winset` 只有 settings 档的屏才认 —— 见第五十二洞），且必须出现在选择器
    **开头**；③ 剩下的部分仍走闭合判据 —— 出现的每一个 class 都必须属于本屏。
    """
    if re.match(r"^\s*(?:body|html)\b", sel):
        return None
    if re.search(r"data-theme|data-bare", sel):
        return None
    if re.search(r"\.device\b", sel):
        return None
    rest = sel
    rank = 0
    while True:
        m = LEADING_VARIANT.match(rest)
        if not m:
            break
        if variant != "win":
            return None
        if m.group(1) == "winset" and not settings:
            # 设置窗专属外壳：不是这一屏的外壳，它的规则不许覆盖本屏基线
            return None
        rank = 1
        rest = rest[m.end():]
        if not rest.strip():
            # 选择器就是 `.win` / `.winset` 本身 —— 那是外壳根元素，不是本屏内容
            return None
    if ANY_VARIANT.search(rest):
        # 变体出现在中间（`.a .win .b`），本工具的层叠模型管不到，宁可不算
        return None
    if _is_chrome(sel):
        return None
    toks = SEL_CLASS.findall(rest)
    if not toks:
        return None
    return rank if all(t in classes for t in toks) else None


def _selector_hits(sel, classes):
    """选择器里出现的**每一个 class** 都必须属于本屏（基线档，不含任何变体）。

    只判"含某个 token"是不够的：`.cnt` 在 `.grphead` / `.viewtabs` / `.sidehead` / `.card>h3`
    里都有，本屏只用了 `.card>h3 .cnt`，只判包含就会把别的组件的规则算进来（实测凭空
    多出一个 500 字重、一个 `.card>h3 .r`）。闭合判据把这一类上下文串味挡掉。
    """
    return _selector_rank(sel, classes, None) is not None


def _num(raw):
    try:
        return float(raw)
    except (TypeError, ValueError):
        return None


# ── 代码侧 ────────────────────────────────────────────────────────
# 台账「代码落点」是一格**人写的散文**：可能是多个路径（`A.kt / B`）、可能带行号
# （`DesktopHosting.kt:240`）、可能带根提示（`desktopApp capture/CaptureOverlayView`）、
# 可能带括号注解（`desktopApp DesktopWindow（AWT 托盘）`）。工具必须容忍这些写法，
# 否则台账里会有一整批行直接 FAIL —— 那些行不是"没有落点"，是"落点写得不像路径"。
LANDING_ROOTS = (
    "composeApp/src/commonMain/kotlin/app/relay",
    "composeApp/src/androidMain/kotlin/app/relay",
    "desktopApp/src/main/kotlin/app/relay",
)


def _file_index(repo):
    idx = {}
    for root in LANDING_ROOTS:
        base = os.path.join(repo, root)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirs, files in os.walk(base):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                full = os.path.join(dirpath, f)
                rel = os.path.relpath(full, base)[:-3]
                idx.setdefault(rel, []).append(full)
                idx.setdefault(os.path.basename(f)[:-3], []).append(full)
    return idx


def _fun_index(repo, extra_dirs=()):
    """全仓函数索引：`{函数名: (路径, 函数体)}`。

    **第十五个洞**：代码侧原来只在「落点文件 + 同目录兄弟」里找函数，于是**跨目录的共享
    组件永远不可见**。`ui/components/TopBars.kt:54 LargeTitleBar` 给 35 屏供了 24sp 标题
    （`style = MaterialTheme.typography.headlineSmall`），扫描器不打开它 ⇒ 实测 **32 屏**
    被误报成 `font-size 只原型{24}` —— 报出来的是一个不存在的缺口，照着它改就是白改。

    原型侧 `collect_classes` 早就递归跟进自己的 helper（`depth=2`）；代码侧跟进自己的调用链
    是对称的做法，不是额外能力。

    **第四十三个洞**：`extra_dirs` 是**落点文件所在的目录**。
    原来这里只索引共享层 `ui/`，理由是"跟进 `feature/` 会把兄弟屏的组件并进来" ——
    那条理由对**兄弟屏**成立，对**同目录的零件文件**不成立：
    `landing_slices` 原有的"一跳"只认**文件名等于函数名**的兄弟
    （`CabinetScreen.kt` → `PeerCabinetView.kt`），于是 `SettingsPageChrome.kt`
    这种"按用途命名、里面装着一堆 `internal fun` 行组件"的文件**永远不被打开**。
    症状：第 24 轮把 `SettingsMoreScreen` 拆成六个子页之后，`m-set-notify` 的切片里
    只剩 `SettingsNotifySubpage` 自己 + 跨目录的 `SectionCard` ——
    它调用的 `SettingsPageScaffold` / `SettingSwitchRow` 供的间距、字号、圆角全部不可见，
    差集从一组暴涨成 `font-size 只原型{11,12,24}` ＋ `spacing 只原型{2,5,6,8,10,11,14}`。
    **这不是"代码变差了"，是拆分把一个一直存在的量具盲区从"被大文件掩盖"变成"暴露"。**
    判据：只开**落点文件自己那一层目录**，且只收**入口真的调用到的**函数
    （`landing_slices` 里的 `CALL_ANY` 过滤），不是整目录无差别并进来。
    """
    idx = {}
    # **共享层 `ui/` 一律索引**：跨目录跟进要的是"这一屏用到的公共件"（`ui/components/TopBars.kt`
    # 的 `LargeTitleBar`、`ui/components/Common.kt` 的 `AppBanner`）。
    # 无差别跟进整个 `feature/` 会把**兄弟屏**的组件并进来 —— 那是拿另一种错换这一种错，
    # 所以 feature 侧只开"本行落点文件所在的那一层"（见上面第四十三个洞）。
    roots = [os.path.join(repo, "composeApp/src/commonMain/kotlin/app/relay/ui")]
    roots += [os.path.abspath(d) for d in extra_dirs if d]
    for base in roots:
        if not os.path.isdir(base):
            continue
        for dirpath, _dirs, files in os.walk(base):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                full = os.path.join(dirpath, f)
                try:
                    src = open(full, encoding="utf-8").read()
                except OSError:
                    continue
                for nm, body in _fun_bodies(src).items():
                    idx.setdefault(nm, (full, body))
    return idx


def split_landing(cell):
    """一格里可能写了多个落点，分隔符是 ` / `、`、`、`+`。"""
    parts = re.split(r"\s+/\s+|、|\+", cell or "")
    return [p.strip() for p in parts if p.strip()]


def resolve_landing(repo, idx, part):
    """一格散文 → 一个或多个 .kt 路径；解析不了的如实返回空。"""
    # 台账里路径常写成 `code` 反引号包着，先剥掉
    clean = part.replace("`", " ").strip()
    clean = re.split(r"[（(]", clean)[0].strip()
    clean = re.sub(r":\d+.*$", "", clean).strip()
    if not clean:
        return []
    keys = [clean]
    tail = clean.split(" ", 1)[1] if " " in clean else clean
    keys += [tail, "desktop/" + tail, os.path.basename(tail)]
    keys = [k[:-3] if k.endswith(".kt") else k for k in keys]
    for k in keys:
        k = k.strip().strip("/")
        if k in idx:
            return idx[k]
    return []


CAMEL = re.compile(r"[A-Z]+(?![a-z])|[A-Z][a-z]*|[a-z]+|\d+")
# 消歧时要丢掉的通用词：它们两边都有，不区分是哪一屏
NAME_STOP = {"screen", "view", "pane", "dialog", "composable", "app", "relay", "ui", "kt"}
# **第二十一个洞**：通用词还不够狠。`_name_tokens("screenMScreenCard")` 只滤掉 `screen`
# 还剩 `{card, m}`，于是「取最高分」把 `FileCardView`（`{file, card}`）以 **1 分**选成入口，
# `m-screencard` 只量了 `FileCardView` + 它调用的两个预览件（**3 个切片**），
# 而这一屏真正画它的 `ChatScreen` 一个值都没进 —— 报出来的 19 处差集全是量具造出来的。
# **一分不算证据**：凡是两边都常见的结构性词，都不该决定"这是哪一屏"。
GENERIC_NAME_TOKENS = frozenset((
    "m", "d", "list", "row", "item", "card", "preview", "page", "bar", "box",
    "host", "window", "overlay", "content", "layout", "sheet", "header", "footer",
    "section", "chip", "badge", "tab", "field", "label",
))


def _name_tokens(name):
    return {t.lower() for t in CAMEL.findall(name)} - NAME_STOP - GENERIC_NAME_TOKENS


def _match_paren(src, i):
    """`src[i] == '('` 时返回配对的 `)` 下标；否则 -1。"""
    depth, j = 0, i
    while j < len(src):
        if src[j] == "(":
            depth += 1
        elif src[j] == ")":
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return -1


def _fun_bodies(src):
    """`fun Name(` → 函数体源码（花括号配对）。切片是为了**只量这一屏的那个函数**。

    **第十六个洞**：原来直接 `src.find("{", m.end())` 找函数体，但**默认参数里可以有花括号**
    —— `actions: @Composable () -> Unit = {}` 是极常见的槽位参数写法。于是切片停在参数表里：
    实测 `TopBars.kt` 的 `LargeTitleBar` 切片只有 **177** 字符，止于
    `actions: @Composable () -> Unit = {}`，**函数体一个字都没扫到** ⇒ 它供的 24sp 标题
    对 35 屏全部不可见，被报成 `font-size 只原型{24}`。
    所以先跳过**参数表**（括号配对）再找体的 `{`。
    """
    out = {}
    for m in re.finditer(r"fun\s+([A-Z]\w*)\s*\(", src):
        name = m.group(1)
        k = _match_paren(src, m.end() - 1)
        if k < 0:
            continue
        if re.match(r"\s*=", src[k + 1:]):        # 表达式体：取到本行末，别去抓下一个函数的花括号
            eol = src.find("\n", k)
            out[name] = src[m.start():eol if eol > 0 else len(src)]
            continue
        i = src.find("{", k)
        if i < 0:
            continue
        depth, j = 0, i
        while j < len(src):
            if src[j] == "{":
                depth += 1
            elif src[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        out[name] = src[m.start():j + 1]
    return out


def landing_files_of(repo, idx, landing):
    """台账落点格 → 它**点名的那几个文件**（解析后的绝对路径）。

    第三十六个洞要用它来判"这个切片是不是**另一屏的主体**"。
    """
    out = set()
    for part in split_landing(landing or ""):
        for hit in resolve_landing(repo, idx, part.strip()):
            out.add(os.path.abspath(hit))
    return out


def is_other_screen(path, name, exclude_files):
    """这个切片是不是**另一屏的主体**（第三十六个洞）。

    两个条件同时成立才算：
    ① 它住的文件是**别的台账行的落点文件**；
    ② **文件名与函数同名** —— `GroupCreateDialog.kt` 里的 `GroupCreateDialog`。

    第 ② 条是必需的，否则会把共享零件也判出去：`DesktopChrome.kt::ToolbarButton`
    与 `DesktopConversationsPane.kt::HeadChip` 都住在别的行的落点文件里，
    但它们是**同目录的共享零件**（文件名不是它们的名字），并进闭包是对的。
    实测按"只看文件"判会多排掉 12 条，按"文件 + 同名"判只排掉该排的 15 条。
    """
    return (os.path.abspath(path) in exclude_files
            and os.path.splitext(os.path.basename(path))[0] == name)


def _is_parts_file(path):
    """这个文件是不是**零件文件**（只装行组件/外壳，自己不画一屏）。

    判据：文件里**没有任何**以屏级后缀结尾的函数
    （`Screen` / `Pane` / `View` / `Shell` / `Host` / `Window` / `Overlay`）。
    `SettingsPageChrome.kt`（`SettingsPageScaffold` / `SettingRow` / `ThemeSegments` …）是零件文件；
    `ChatPane.kt`（`ChatPane`）、`DesktopConversationsPane.kt`（`ConversationsPane`）不是。

    它只用来给**第二跳**把关（见 `landing_slices` 第四十四个洞）：
    第二跳是为了让"落点文件委托出去的零件"还能看见零件自己调用的 `ui/` 共享件；
    但它**不能**顺着"同目录里的兄弟屏"再往下走 ——
    `ui/desktop/` 一个目录装一堆屏，`ChatPane` 不是任何一行的落点文件，
    于是旧判据会从它漏进 `DesktopComposer` → `EmojiPopup` / `PkPickerDialog`
    （实测 `d-empty` / `d-home-more` 因此凭空多出 `只代码 17/22/24`、`20`、`16`，
    正是第三十六洞的形状，只是入口换成了第二跳）。
    """
    try:
        src = open(path, encoding="utf-8").read()
    except OSError:
        return False
    return not any(n.endswith(SCREEN_LEVEL_SUFFIX) for n in _fun_bodies(src))


def landing_slices(repo, landing, render_fn, exclude=None):
    """台账落点 → **真正画这一屏的那个 Composable** 的源码切片。

    `exclude` 是**别的台账行的落点文件**集合（见 `landing_files_of`）。
    第三十六个洞：闭包会把"**从这一屏能打开的另一个屏**"并进本屏 ——
    `ConversationsScreen` 调 `GroupCreateDialog`，于是 `m-home-recent` 的切片里
    凭空多出建群弹层的 20/5/9/1 四个值，被报成"只代码"。原型 `screenHomeRecent`
    拍的是会话列表那一帧，弹层不在帧里；弹层是 `m-groupcreate` 这一行的事。

    落点文件常常同时放着多个屏的渲染函数：`CabinetScreen.kt` 里既有 `MineCabinetView`
    又有派发到 `PeerCabinetView.kt` 的调用。只按文件扫就会把**兄弟组件的取值并进本屏**
    （实测 `m-cabinet` 因此多出 titleMedium/titleLarge 的 16sp 与 22sp）。

    消歧用原型的渲染函数名：`screenCabinetMine` → {cabinet, mine}，与候选 Composable 名
    求词交集取最高分 —— `MineCabinetView` 得 2、`PeerCabinetView` 得 1、`CabinetScreen` 得 1。
    """
    exclude = set(exclude or ())
    idx = _file_index(repo)
    bases, unresolved = [], []
    for part in split_landing(landing):
        hits = resolve_landing(repo, idx, part)
        if hits:
            bases += hits
        else:
            unresolved.append(part)
    bases = sorted(set(bases))
    if not bases:
        return [], [{"file": f"未解析：{u}", "fun": "", "score": 0} for u in unresolved]

    want = _name_tokens(render_fn or "")
    cands = []
    for base in bases:
        src = open(base, encoding="utf-8").read()
        bodies = _fun_bodies(src)
        cands += [(base, n, b) for n, b in bodies.items()]
        d = os.path.dirname(base)
        for name in sorted(set(VIEW_CALL.findall(src))):
            if name in bodies:
                continue
            cand = os.path.join(d, name + ".kt")
            if os.path.exists(cand):
                other = open(cand, encoding="utf-8").read()
                ob = _fun_bodies(other)
                cands.append((cand, name, ob.get(name, other)))
                # **第十七个洞**：兄弟文件是**整份**收进来的，不是只收那个与文件名同名的函数。
                # 原来这里只有上面一行 `ob.get(name, other)`，于是该文件里的私有行组件
                # （`SmallButton` / `CabinetCheckbox` / `CrumbSeg` / `PermissionChip` / `PeerHeadAvatar` …）
                # 不进 `cands` ⇒ 不进 `by_name` ⇒ BFS 跟不进去 ⇒ 它们供的字号/间距/圆角
                # 对**所有委托到兄弟文件的屏**全部不可见，被报成"只原型"。
                # 实测 `m-cabinet-peer`：切片里只有 `PeerCabinetView` 一个函数，
                # `SmallButton` 的 12sp 与 5 处间距因此凭空消失。
                # 与 bases 文件的处理对称（上面 `cands += [... for n, b in bodies.items()]` 就是整份）。
                for on, ob_body in ob.items():
                    if on != name:
                        cands.append((cand, on, ob_body))

    # **全局**取最高分（同分并列全留），而不是逐文件各取一个：
    # `m-cabinet-peer` 的落点格写的是派发文件 `CabinetScreen.kt`，逐文件取就会把
    # `CabinetScreen`（同分 1）也选进来，于是把 `MineCabinetView` 的 22sp 并进了这一屏。
    # 全局取分能让 `PeerCabinetView`（得分 2）单独胜出。
    scored = [(len(want & _name_tokens(n)), p, n, b) for p, n, b in cands]
    best = max((sc for sc, _, _, _ in scored), default=0)
    picked = [(p, n, b) for sc, p, n, b in scored if sc == best and sc > 0]
    # 整文件兜底：连一个词都对不上，只能整份量。**这个模式已经很粗了**，
    # 第二跳（第四十四个洞）在这一模式下会顺着一堆同目录的兄弟屏往下走 ——
    # 实测 `d-empty` / `d-home-more` / `d-nosel`（落点都是 `ui/desktop/DesktopConversationsPane.kt`）
    # 会凭空多出 `只代码 17/22/24`、`20`、`16`（`EmojiPopup` / `PkPickerDialog` /
    # `DesktopGlobalSearchPanel` —— 从这一屏能打开的**别的**屏）。所以第二跳只在
    # **按名字选中了本屏那个函数**时才开：那时候我们知道自己量的是谁，深一层是在补零件；
    # 整文件兜底时我们并不知道，深一层就只是把噪声放大。
    whole_file_fallback = not picked
    if whole_file_fallback:             # 一个词都对不上：不猜，整文件量，但如实说明
        # **第四十七个洞**：落点格写了多份文件时，只量 `bases[0]` 会把同一屏**被拆出去的行组件**
        # 整份漏掉。实测 `m-home-recent`：`ConversationsScreen.kt` 按 500 行红线拆出
        # `ConversationRow.kt` / `FilterPill.kt` / `InboxWorkspaceSection.kt` 之后，行组件的
        # 字号/间距/圆角从这一屏的代码侧凭空消失，差集多出 9 处（`font-size 只原型{16}`、
        # `spacing 只原型{1,1.5,6,9,11}`、`radius 只原型{16,999}`）—— 差集是被量具抬起来的，
        # 不是还原度掉了。人既然在落点格里写了几份文件，整份量就该把这几份**都**并上；
        # 顺序沿用上面排好序的 `bases`，结果可复现。
        picked = [(p, os.path.basename(p), open(p, encoding="utf-8").read()) for p in bases]
    else:
        # 第三十六个洞：评分选中的若是**别的屏**，让给下一名，而不是把它算进本屏。
        # 只在"排掉之后还有东西"时才排 —— 一个都不剩就说明 `exclude` 判错了，
        # 这时宁可多量也不能让本屏变成一个空切片（空切片会让整屏的"只原型"爆表）。
        kept = [(p, n, b) for p, n, b in picked if not is_other_screen(p, n, exclude)]
        if kept:
            picked = kept

    # **第二十五个洞**：`picked` 里可能**一个屏级函数都没有** —— 名字分词把装饰件选中了，
    # 真正画这一屏的那个函数反而没进切片。实测 `d-settings-desk` / `d-settings-ime`：
    # 落点文件 `DesktopPreferences.kt` 里 `DesktopSettingsTitleBar`（`Bar` 结尾）与
    # `DesktopSettingsSidebar` 各拿到 1 分词而胜出，`DesktopPrefsPane`（真正画「桌面」这一页的）
    # 不在切片里 ⇒ 它供的字号全被算成"只代码"，而在文件里把它从 24sp 改成 11sp 之后
    # **数字纹丝不动**（量具根本没看那个函数）。判据：切片里连一个屏级函数都没有时，
    # 把落点文件里所有屏级函数补进来。
    # **只在"这个文件里一个都没有"时补** —— 无条件补会把 `m-cabinet-peer` 的 `CabinetScreen`
    # 拉回来，把 `MineCabinetView` 的 22sp 并进那一屏（见上面 :609-612 记的那次）。
    # 判据按**每个落点文件**分别算，且看的是"**同目录**里有没有屏级函数被选中"：
    # 兄弟文件是落点文件委托出去的那一个（第十七个洞就是这个形状），它已经进切片时不许再补。
    # · `d-settings-desk`：`DesktopPreferences.kt` 这一侧选中的只有 `…TitleBar`/`…Sidebar`（都非屏级），
    #   同目录也没有别的屏级函数 ⇒ 补 `DesktopPrefsPane`；
    #   另一个落点文件 `SettingsScreen.kt` 里 `SettingsScreen` 已选中 ⇒ 那个文件不动。
    # · `m-cabinet-peer`：落点 `CabinetScreen.kt`，同目录的 `PeerCabinetView.kt::PeerCabinetView`
    #   已选中（屏级）⇒ 不补 `CabinetScreen`，`MineCabinetView` 的 22sp 不会被并进来。
    have = {n for _, n, _ in picked}
    for bp in bases:
        d = os.path.dirname(bp)
        if any(os.path.dirname(p) == d and n.endswith(SCREEN_LEVEL_SUFFIX) for p, n, _ in picked):
            continue
        for on, ob_body in _fun_bodies(open(bp, encoding="utf-8").read()).items():
            if (on.endswith(SCREEN_LEVEL_SUFFIX) and on not in have
                    and not is_other_screen(bp, on, exclude)):
                have.add(on)
                picked.append((bp, on, ob_body))

    # 从入口出发，跟进它**调用到的**同文件 Composable（递归 3 层），把行组件收进来。
    # 只切入口函数会漏掉行组件；整文件量又会把兄弟屏并进来。
    by_name = {}
    for pth, nm, body in cands:
        if is_other_screen(pth, nm, exclude):   # 第三十六个洞：别的屏的主体不进本屏的调用图
            continue
        by_name.setdefault(nm, (pth, body))
    # **第十八个洞**：`picked` 的键不一定是函数名。整文件兜底时它是**文件名**
    # （`"CabinetScreen.kt"`），而 `by_name` 里只有函数名 ⇒ 下面 BFS 的
    # `by_name.get(nm, (None, ""))` 取到空串 ⇒ **整份文件里调用到的组件一个都跟不进来**。
    # 实测 `m-cabinet-peer`（落点格写派发文件、render 为空 ⇒ 走整文件兜底）：
    # `CabinetScreen.kt` 明明调了 `PeerCabinetView`，切片里却只有它自己 + 跨目录的 `PeerAvatar`，
    # 于是 `PeerCabinetView` 里 `SmallButton`/`CabinetCheckbox`/`CrumbSeg` 供的
    # 12sp、8px 圆角、5 处间距全部凭空消失，被报成"只原型"。
    # 与 :603-606 记的那次同族（那次是漏进 `picked`，这次是漏进 `by_name`）。
    for pth, nm, body in picked:
        by_name.setdefault(nm, (pth, body))
    # **跨目录跟进**：入口调用到的共享组件（`LargeTitleBar` 在 `ui/components/TopBars.kt`，
    # 不在落点目录里）。只跟**入口那一层真的调用到的**函数，不递归 —— 无差别递归会把
    # `ui/components/Common.kt` 整片并进每一屏，`只代码` 侧噪声暴涨，那是用另一种错换掉这一种错。
    #
    # 第四十三个洞：索引里要**多开落点文件自己那一层目录** ——
    # `SettingsPageChrome.kt` 这类"按用途命名"的零件文件不在 `ui/` 下、文件名也不等于任何
    # 函数名，原有的"同目录一跳"看不见它，于是整屏的间距/字号/圆角全被算成"只原型"。
    #
    # **第四十四个洞（试过、撤回）：不要为了"零件文件自己调用的共享件"再深一跳。**
    # 动机是真的：`SettingsPageScaffold`（第 1 跳拿到）调用的 `LargeTitleBar`（第 2 跳）
    # 因此看不见，`m-set-*` 的 24sp 标题被报成"只原型 24"。
    # 但**第二跳没有可靠的判据**：试过三种，每一种都立刻用另一种错换掉这一种 ——
    # ① 一律递归到不动点：59 组差集变形，`m-home-recent` 等 13 屏凭空多出 `只代码 24`
    #    （`MessageState` 那种**不在这一帧里**的空态分支 = 幽灵值）；
    # ② "第 2 跳只从落点目录里的零件出发"：`d-empty`/`d-home-more`/`d-nosel` 的落点
    #    是 `ui/desktop/DesktopConversationsPane.kt`，`ui/desktop/` 是**一个目录装一堆屏**，
    #    于是漏进 `EmojiPopup`/`PkPickerDialog`（`只代码 17/22/24`、`20`、`16`）；
    # ③ 再加"这个文件里没有屏级函数"+"整文件兜底时不跳"：`d-search`/`d-search-empty`
    #    照样漏（它们的落点也是那个共享 pane 文件）。
    # 根因：**"同目录"只在"一个目录就是一个屏"时等于"同一屏"**。
    # `feature/settings/` 满足，`ui/desktop/` 不满足，而判据拿不到这个区别。
    # 所以维持一跳：`font-size 只原型 24` 这条**已知的假阳性**留在基线里当欠债，
    # 比"再深一跳、把别的屏的分支并进来"更安全 —— 假阳性会让人白改，幽灵值会让人改错。
    # 与第十五、十七、十八、二十五、三十六、四十三洞同族：**跟进的深度决定了量具能看见多远**。
    landing_dirs = {os.path.dirname(b) for b in bases}
    gidx = _fun_index(repo, landing_dirs)
    cross = []
    # **必须迭代 `list(picked)` 的快照**：下面在循环里 `picked.append(...)`，
    # 直接 `for ... in picked` 会让 Python 的列表迭代器**把刚追加的元素也扫一遍** ——
    # 那就等于"递归到不动点"（第四十四个洞记的那个错），而不是"只跟一跳"。
    # 实测这个手误会让 25 组差集变成 60 组（`m-home-recent` 等 11 屏凭空多出 `只代码 24`）。
    def _hop(seed, allow_deep):
        """从 `seed` 里每个函数出发，跟进它调用的、索引里有的、不属于别屏的函数。

        返回**下一跳可出发的**函数列表（只有 `allow_deep` 时才非空）。
        """
        nxt = []
        for _p, _n, body in seed:
            for call in sorted(set(CALL_ANY.findall(body))):
                if call in by_name or call not in gidx:
                    continue
                if is_other_screen(gidx[call][0], call, exclude):
                    continue
                by_name[call] = gidx[call]
                cross.append(call)
                # 跟进来的共享组件**就是本屏的切片**，必须并进 `picked` —— 只放进 `by_name` 不够：
                # `m-home-recent` 的入口名是**文件名**（原型渲染函数名与候选 Composable 名词交集为 0
                # 时整文件量），BFS 从 `"ConversationsScreen.kt"` 这个不是函数名的键出发取不到任何调用，
                # `LargeTitleBar` 就停在 `by_name` 里永远进不了切片，24sp 照样被报成"只原型"。
                _gp, _gb = gidx[call]
                picked.append((_gp, call, _gb))
                # 第 2 跳的**出发条件**（四条同时成立），判据是**目录的性质**：
                # ① 它在 `feature/<x>/` 下 —— `feature/` 是"一屏一个目录"，
                #    而 `ui/` 是"一个目录装一堆屏"（`ui/desktop/` 里躺着十几个 pane）；
                # ② 它不是任何一行台账的落点文件（是"按用途命名的零件文件"，不是另一屏的主体）；
                # ③ 它那个文件里**没有屏级函数**（`_is_parts_file`）——
                #    有屏级函数就说明这个文件自己就是一屏，深进去会把兄弟屏并进来；
                # ④ 入口不是整文件兜底（那时候我们并不知道自己量的是谁）。
                # 为什么非要第 2 跳：`SettingsPageChrome.kt` 这类零件文件是"被调用才被打开"的，
                # 而 `SettingRow` 是被**第 1 跳才加进来的** `SettingSwitchRow` 调用的 ——
                # 一跳够不着它，它供的 600/12 就全部不可见（`m-set-*` 的 font-weight 差集整个来源）。
                if (allow_deep
                        and os.path.abspath(_gp) not in exclude
                        and _FEATURE_MARK in os.path.abspath(_gp)
                        and os.path.abspath(_gp) not in {os.path.abspath(b) for b in bases}
                        and _is_parts_file(_gp)):
                    nxt.append((_gp, call, _gb))
        return nxt

    frontier = _hop(list(picked), not whole_file_fallback)
    # 第 2/3 跳：只从"本屏自己的零件文件"继续往深里走（`_hop` 的注释记了四条判据）。
    # 为什么不是"一跳就停"：`SettingsCapsSubpage → SettingSwitchRow → SettingRow → TagPill`
    # 是**四层**，一跳连 `SettingRow` 都够不着，`TagPill` 供的 999 药丸与 600 权重就全不可见
    # （`m-set-caps` 的 `radius 只原型 999`、`font-weight 只原型 500` 就是这么来的）。
    # 为什么不是"递归到不动点"：那会把 `ui/` 共享件再往下跟，把**不在这一帧里**的分支并进来
    # （第四十四个洞记的 59 组变形）。**深度不是靠"再跳一次"定的，是靠"从哪出发"定的。**
    for _ in range(2):
        if not frontier:
            break
        frontier = _hop(frontier, True)
    seen = {n for _, n, _ in picked}
    frontier = [n for _, n, _ in picked]
    for _ in range(3):
        nxt = []
        for nm in frontier:
            body = by_name.get(nm, (None, ""))[1]
            for call in set(VIEW_CALL.findall(body)) | set(CALL_ANY.findall(body)):
                if call in by_name and call not in seen:
                    seen.add(call)
                    nxt.append(call)
        frontier = nxt
        if not frontier:
            break
    for nm in sorted(seen):
        if nm in by_name and not any(n == nm for _, n, _ in picked):
            pth, body = by_name[nm]
            picked.append((pth, nm, body))

    report = [{"file": os.path.relpath(p, repo), "fun": n, "score": sc}
              for sc, p, n, _ in scored]
    report += [{"file": f"未解析：{u}", "fun": "", "score": 0} for u in unresolved]
    # 跨目录跟进的共享组件单独列出来：它们不是"本屏的落点"，而是本屏**用到的**东西，
    # 报出来是为了让"这一屏的值是从哪来的"可追溯（否则下次又会怀疑差集为什么变少）。
    report += [{"file": os.path.relpath(gidx[n][0], repo), "fun": n, "score": -1}
               for n in cross]
    return picked, report


def landing_files(repo, landing):
    """台账「代码落点」→ 该 .kt，再**跟进一跳** `<Name>View(`。

    `m-cabinet-peer` 的落点写的是 `CabinetScreen.kt`，但真正画它的是 `PeerCabinetView.kt`
    （前者只是派发）。不跟进就会拿 `CabinetScreen.kt` 去比一个根本不是它的屏。
    """
    base = os.path.join(repo, LANDING_PREFIX, landing)
    if not os.path.exists(base):
        return []
    out = [base]
    d = os.path.dirname(base)
    src = open(base, encoding="utf-8").read()
    local = set(re.findall(r"fun\s+([A-Z]\w*)\s*\(", src))
    for name in sorted(set(VIEW_CALL.findall(src))):
        if name in local:
            continue
        cand = os.path.join(d, name + ".kt")
        if os.path.exists(cand):
            out.append(cand)
    return out


def code_tokens(repo):
    """代码的 token 权威源：AppSpacing / RelayTypography / RelayShapes。"""
    spacing, typo, shapes = {}, {}, {}
    p = os.path.join(repo, "composeApp/src/commonMain/kotlin/app/relay/ui/theme/Spacing.kt")
    if os.path.exists(p):
        spacing = {k: float(v) for k, v in KT_PROP.findall(open(p, encoding="utf-8").read())}
    p = os.path.join(repo, "composeApp/src/commonMain/kotlin/app/relay/ui/theme/AppTheme.kt")
    if os.path.exists(p):
        src = open(p, encoding="utf-8").read()
        for role, body in KT_ROLE.findall(src):
            fs = SP_LIT.search(body)
            fw = re.search(r"fontWeight\s*=\s*FontWeight\.(\w+)", body)
            if fs:
                typo[role] = {
                    "size": float(fs.group(1)),
                    "weight": float(FONT_WEIGHTS.get(fw.group(1), 400)) if fw else 400.0,
                }
        shapes = {role: float(v) for role, v in KT_SHAPE.findall(src)}
    # ShapeRoles（Avatar 9 / ListCard 11 / DayCell 7）是 M3 五槽之外的圆角角色，定义在
    # ShapeRoles.kt。不并进来，用角色 token 写的圆角在代码侧会凭空消失 —— 与第二十六个洞同族：
    # 量具只看得见自己看得见的形态。
    p = os.path.join(repo, 'composeApp/src/commonMain/kotlin/app/relay/ui/theme/ShapeRoles.kt')
    if os.path.exists(p):
        for role, v in SHAPE_ROLE_PROP.findall(open(p, encoding='utf-8').read()):
            shapes.setdefault(role[:1].lower() + role[1:], float(v))
    return spacing, typo, shapes


def _pill_matched(proto_radius, slices):
    """原型 `border-radius:999px` ↔ 代码 `CircleShape`（药丸没有数字对应物）。

    只在**原型也有 999** 时才认这一对：无条件把 `CircleShape` 记成 999 会反过来让
    给头像/状态点用的 CircleShape 凭空多出一个「只代码 999」（实测 21 屏）。
    """
    return 999.0 in proto_radius and any(CIRCLE_SHAPE.search(b) for _, _, b in slices)


def _button_weight_matched(proto_weight, slices, tokens):
    """原型 `.btn{font-weight:500}` ↔ 代码 M3 按钮继承的 `labelLarge` 字重。

    **第五十一个洞**，与 `_pill_matched` 同一个形状：M3 按钮不带 `FontWeight` 字面量，
    量具只认写出来的 `FontWeight.X`，于是继承来的字重永远进不了代码集合。

    只在**原型也有这个字重**时才认这一对 —— 与 `_pill_matched` 逐字同一条理由：
    无条件把按钮补上 `labelLarge` 的字重，会反过来让原型**没有** 500 的屏凭空多出
    「只代码 500」（实测 8 屏：m-nodes / m-nodes-empty / m-myqr / m-settings /
    d-capture / d-capture-tools / d-viewscreen …），那是用另一种假阳性换这一种。
    """
    if not any(BUTTON_CALL.search(b) for _, _, b in slices):
        return False
    label = tokens[1].get("labelLarge")
    if not label or "weight" not in label:
        return False
    return float(label["weight"]) in proto_weight


BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
# `//` 前必须是行首或空白：`"https://…"` 这种字符串里的 `//` 不能被当成注释起点。
LINE_COMMENT = re.compile(r"(?m)(?:^|\s)//[^\n]*")


def strip_comments(src):
    """去掉 Kotlin 注释。

    第三十七个洞：量具把**注释里写的 token 名**当成了代码取值。
    在 `GroupCreateDialog.kt` 里写了一句「M3 `AlertDialog` 默认取 `shapes.extraLarge`
    （本仓 28dp）」来解释为什么必须显式给 20dp —— 量具就真的把 28 记进了
    `m-groupcreate`/`d-groupcreate` 的圆角集合，两行各多一条「只代码 28」。
    **作者在注释里写清原委是对的，量具该学会不读注释。**
    """
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", src))


def code_values(slices, tokens):
    """切片里出现的取值集合。符号引用（`LocalAppSpacing.current.lg14`）解析成数值。"""
    spacing, typo, shapes = tokens
    values = {d: set() for d in DIMENSIONS}
    unparsed = {d: set() for d in DIMENSIONS}
    for _path, _fn, src in slices:
        src = strip_comments(src)
        # 这个切片把 CompositionLocal 起了什么别名（`val sp = LocalAppSpacing.current`）。
        aliases = set(LOCAL_SP_ALIAS.findall(src))

        # 间距：只认**间距语境的调用**。`Modifier.size(24.dp)` / `width(90.dp)` 是尺寸不是间距，
        # 一锅端会把尺寸列混进间距列（实测差集里冒出 90/44/36 这些明显是宽度和高度的值）。
        for g1, g2 in SPACING_CALLS.findall(src):
            expr = g1 or g2
            for n in DP_LIT.findall(expr):
                values["spacing"].add(float(n))
            for name in re.findall(r"LocalAppSpacing\.current\.(\w+)|AppSpacing\(\)\.(\w+)", expr):
                nm = name[0] or name[1]
                if nm in spacing:
                    values["spacing"].add(spacing[nm])
                else:
                    unparsed["spacing"].add(f"LocalAppSpacing.{nm}")
            for al in aliases:
                for nm in re.findall(r"(?<![\w.])" + re.escape(al) + r"\.(\w+)", expr):
                    if nm in spacing:
                        values["spacing"].add(spacing[nm])
                    else:
                        unparsed["spacing"].add(f"{al}.{nm}")

        # 圆角：只认 RoundedCornerShape 与 shapes.<role>
        for n in SHAPE_LIT.findall(src):
            values["radius"].add(float(n))
        # 三种写法都收：MaterialTheme.shapes.<slot> / RelayShapes.<slot> / ShapeRoles.<role>。
        # 后两种是 task-30 把圆角字面量 token 化之后的主流写法；旧模式只认小写 shapes.，
        # 于是「换成 token」会让这些值从代码侧消失、差集凭空变大（同第二十六个洞）。
        for m in re.finditer(r"(?<![\w.])(?:MaterialTheme\.shapes|RelayShapes|ShapeRoles)\.(\w+)", src):
            role = m.group(1)
            key = role if role in shapes else role[:1].lower() + role[1:]
            if key in shapes:
                values["radius"].add(shapes[key])
            else:
                unparsed["radius"].add(f"shapes.{role}")

        # 字号：排版角色（Compose 里字号总是显式来自某处）+ 裸 fontSize=
        for n in SP_LIT.findall(src):
            values["font-size"].add(float(n))
        for m in re.finditer(r"typography\.(\w+)", src):
            role = m.group(1)
            if role in typo:
                values["font-size"].add(typo[role]["size"])
            else:
                unparsed["font-size"].add(f"typography.{role}")

        # 字重：**只认显式写出来的**。排版角色自带的 400 不算 —— 原型侧是"只读声明"，
        # 继承来的 400 它扫不出来，算进来就是拿"声明"比"继承"（实测差集里恒挂一个 400）。
        for m in re.finditer(r"FontWeight\.(\w+)", src):
            name = m.group(1)
            if name in FONT_WEIGHTS:
                values["font-weight"].add(float(FONT_WEIGHTS[name]))
            else:
                unparsed["font-weight"].add(f"FontWeight.{name}")
    return values, unparsed


# ── 台账 ──────────────────────────────────────────────────────────
def read_ledger(path):
    """台账数据行 → [{id, label, landing}]。

    **已撤的屏不进差集**（第四十六个洞）：原型里删掉的屏，台账上仍留一行（诚实记录
    「这一屏没了」），但 `screen_functions()` 在派发表里当然找不到它 ⇒ 每一轮都报
    `__error__ 新增 ['原型派发表里没有这一屏']`，把一条**已经处理完的事**变成永久的红。
    判据：台账末列写着「已撤」的行 = 这一屏已从原型删除，两侧都无可比之物。
    （不删台账行 —— 删了就没人知道它曾经存在过、为什么没了。）
    """
    if not os.path.exists(path):
        raise DiffError(f"台账不存在：{path}")
    rows = []
    for line in open(path, encoding="utf-8"):
        if not line.startswith("| "):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 4 or not re.match(r"^[md]-[\w-]+$", cells[0]):
            continue
        if withdrawn(cells):
            continue
        rows.append({"id": cells[0], "label": cells[1], "landing": cells[3]})
    return rows


def withdrawn(cells):
    """台账这一行说的是「这屏已经撤了」吗。

    只认**末列**以「已撤」开头 —— 中间某格里出现「已撤」两个字是在讲别的事
    （比如「`NodesRowMenuPhonePreview` 已撤」说的是一个帧被删了，屏还在）。
    """
    return bool(cells) and cells[-1].startswith("已撤")


# ── 比对 ──────────────────────────────────────────────────────────
def _zero_is_absent(dim):
    """**第三十九个洞**：原型把「没有间距」写成一个**值**（`padding:0` / `gap:0`），
    Compose 把「没有间距」写成**不写那个参数**。于是删掉一处空话 `padding(0.dp)` /
    `Arrangement.spacedBy(0.dp)`，量具就报「原型有、代码没有的值变多了」——
    实测 36 屏各挂一条「只原型 0」。这是把「没有」读成了「有」。
    判据：**间距维度上 `0` 两侧都不参与差集**（其它维度照旧 —— 圆角 0 是真值）。
    """
    return dim == "spacing"


def diff_sets(p, c, dim):
    """差集的两个方向。`0` 在间距维度上等于「没写这个参数」，不算值。"""
    if _zero_is_absent(dim):
        p = {v for v in p if v != 0.0}
        c = {v for v in c if v != 0.0}
    return sorted(p - c), sorted(c - p)


def compare_one(repo, row, html, css, scopes, bodies, fns, forms, tokens, exclude=None,
                settings_screens=frozenset()):
    sid = row["id"]
    fn = fns.get(sid)
    if not fn:
        return {"screen": sid, "label": row["label"], "error": "原型派发表里没有这一屏"}
    form = forms.get(sid, "mobile")
    variables = dict(scopes[0])
    if form == "desktop":
        variables.update(scopes[1])
    classes = collect_classes(bodies, fn, depth=2)
    pv, pu = proto_values(
        css, variables, classes,
        variant="win" if form == "desktop" else None,
        elements=collect_elements(bodies, fn, depth=2),
        # 第五十二洞：`.winset` 只是**设置档**那几张屏的外壳，不是每个桌面屏的
        settings=sid in settings_screens)
    slices, cands = landing_slices(repo, row["landing"], fn, exclude=exclude)
    if not slices:
        return {"screen": sid, "label": row["label"], "error": f"落点不存在：{row['landing']}"}
    cv, cu = code_values(slices, tokens)
    # **第十九个洞**：药丸圆角没有数字对应物 —— 原型写 `border-radius:999px`（`--r-chip`），
    # Compose 的惯用写法是 `CircleShape`，量具只认数字圆角，于是**恒挂一条「只原型 999」**
    # （实测 33 屏：m-chat / m-msgmenu / m-agents / d-cabinet / d-nodes …，全是同一个单位缺口）。
    # 只在**原型也有 999** 时才认这一对：无条件把 `CircleShape` 记成 999 会反过来让
    # 给头像/状态点用的 CircleShape 凭空多出一个「只代码 999」（实测 21 屏）——
    # 那是用另一种假阳性换这一种。
    if _pill_matched(pv["radius"], slices):
        cv["radius"].add(999.0)
    # **第五十一个洞**：按钮继承字重（同上，同一类"没有数字对应物"的缺口）。
    if _button_weight_matched(pv["font-weight"], slices, tokens):
        cv["font-weight"].add(float(tokens[1]["labelLarge"]["weight"]))
    dims = {}
    for dim in DIM_ORDER:
        p, c = pv[dim], cv[dim]
        only_proto, only_code = diff_sets(p, c, dim)
        dims[dim] = {
            "proto": sorted(p),
            "code": sorted(c),
            "onlyProto": only_proto,
            "onlyCode": only_code,
            "unparsedProto": sorted(pu[dim]),
            "unparsedCode": sorted(cu[dim]),
        }
    return {
        "screen": sid,
        "label": row["label"],
        "form": form,
        "fn": fn,
        "landing": row["landing"],
        # 量的是**消歧后选中的那个 Composable**，不是整个落点文件；候选与得分一并留证，
        # 免得下一个人以为"落点文件里的东西都算这一屏"。
        "slices": [f"{os.path.relpath(p, repo)}::{n}" for p, n, _ in slices],
        "candidates": cands,
        "classes": sorted(classes),
        "dims": dims,
    }


def collect(repo, ledger=None, only=None):
    html, css, meta = read_prototype(repo)
    scopes = var_scopes(css)
    bodies = fn_bodies(html)
    fns = screen_functions(html)
    forms = screen_forms(html)
    tokens = code_tokens(repo)
    rows = read_ledger(os.path.join(repo, ledger or LEDGER_REL))
    # 第五十二洞：谁渲染在设置档窗口里（`.winset`）—— 按原型自己的 `win()` 规则复现
    settings_screens = settings_tier_screens(bodies, fns)
    # 第三十六个洞：**先按整本台账**算出"每一行点名了哪几个落点文件"，
    # 再给每一行排除掉"**别的行**点名的那些文件里、与文件名同名的那个 Composable"。
    # 必须用**整本**（不受 `only` 影响）：只量一屏时若只看被筛后的行，排除集就空了，
    # 同一屏在 `--screen` 与 `--all` 下会给出两个数。
    _idx = _file_index(repo)
    own_files = {r["id"]: landing_files_of(repo, _idx, r["landing"]) for r in rows}
    all_files = set().union(*own_files.values()) if own_files else set()

    def _exclude_for(sid):
        return all_files - own_files.get(sid, set())

    if only:
        rows = [r for r in rows if r["id"] in only]
    return {
        "meta": meta,
        "scopes": {"rootVars": len(scopes[0]), "winVars": len(scopes[1])},
        "rows": [compare_one(repo, r, html, css, scopes, bodies, fns, forms, tokens,
                             exclude=_exclude_for(r["id"]),
                             settings_screens=settings_screens)
                 for r in rows],
    }


def fmt(v):
    if isinstance(v, float) and v == int(v):
        return str(int(v))
    return str(v)


def render(result):
    out = []
    out.append(f"# 逐屏还原度差集（原型 `{result['meta']['path']}` @ {result['meta']['sha256']}）")
    out.append("")
    out.append("每一格是**集合差**：`只在原型` 里的值代码侧一个都没有，`只在代码` 反之。")
    out.append("两边都有的值不列。值对得上 ≠ 用对地方 —— 定位到值之后仍要人看那一处。")
    out.append("")
    bad = 0
    for row in result["rows"]:
        if row.get("error"):
            out.append(f"## {row['screen']} —— {row['error']}")
            out.append("")
            bad += 1
            continue
        n = sum(len(row["dims"][d]["onlyProto"]) + len(row["dims"][d]["onlyCode"])
                for d in DIM_ORDER)
        head = f"## {row['screen']} · {row['label']}"
        out.append(head if not n else f"{head} — **{n} 个值对不上**")
        out.append("")
        out.append(f"- 原型渲染函数 `{row['fn']}`（{row['form']}，变量作用域 "
                   f"`{'win' if row['form'] == 'desktop' else 'root'}`）")
        out.append(f"- 代码落点 `{row['landing']}`" +
                   (f" → 跟进 `{', '.join(os.path.basename(f) for f in row['files'][1:])}`"
                    if len(row["files"]) > 1 else ""))
        out.append("")
        out.append("| 维度 | 原型 | 代码 | 只在原型 | 只在代码 |")
        out.append("|---|---|---|---|---|")
        for dim in DIM_ORDER:
            d = row["dims"][dim]
            op = ", ".join(fmt(v) for v in d["onlyProto"]) or "—"
            oc = ", ".join(fmt(v) for v in d["onlyCode"]) or "—"
            out.append(f"| {dim} | {', '.join(fmt(v) for v in d['proto']) or '—'} "
                       f"| {', '.join(fmt(v) for v in d['code']) or '—'} | {op} | {oc} |")
        out.append("")
    out.append(f"共 {len(result['rows'])} 屏，{bad} 屏读不出来。")
    out.append("")
    return "\n".join(out)


# ── 棘轮 ──────────────────────────────────────────────────────────
def signature(result):
    """`{屏}|{维度}|{侧}` → 差集值列表。棘轮键。"""
    sig = {}
    for row in result["rows"]:
        if row.get("error"):
            sig[f"{row['screen']}|__error__"] = [row["error"]]
            continue
        for dim in DIM_ORDER:
            for side, key in (("onlyProto", "proto"), ("onlyCode", "code")):
                vals = row["dims"][dim][side]
                if vals:
                    sig[f"{row['screen']}|{dim}|{key}"] = [fmt(v) for v in vals]
    return sig


def ratchet(current, base):
    """差集只许变小。返回 (regress, improved)。"""
    regress, improved = [], []
    for key, vals in current.items():
        old = set((base.get("signature") or {}).get(key) or [])
        new = set(vals)
        if new - old:
            regress.append((key, sorted(new - old)))
        if old - new:
            improved.append((key, sorted(old - new)))
    return regress, improved


def load_baseline(path):
    if not os.path.exists(path):
        return None
    try:
        return json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise DiffError(f"基线读不出来：{path}（{e}）")


def write_baseline(path, result, reason=None):
    sig = signature(result)
    base = {
        "note": "逐屏差集棘轮：每个键的差集只许变小。--check 报「新增的值」。",
        "generatedAt": result["meta"]["sha256"],
        "rowCount": len(result["rows"]),
        "signature": sig,
    }
    if reason:
        base["reason"] = reason
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(base, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    return base


# ── 自检 ──────────────────────────────────────────────────────────
def self_test():
    """四种已知情形各一个答案。量具先验：先证明它错了会报红，再信它的绿。"""
    fails = []

    def expect(cond, msg):
        if not cond:
            fails.append(msg)

    # 1) var 展开：--padc 是简写，展开后必须还能拆成三个值
    expect(expand_vars("var(--padc)", {"--padc": "10px 14px 14px"}) == "10px 14px 14px",
           "自检：var() 必须展开成原值")
    conv = _load_converge()
    expect(conv._values_of("padding", expand_vars("var(--padc)", {"--padc": "10px 14px 14px"}))
           == ["10px", "14px", "14px"], "自检：展开后的简写必须拆开")

    # 2) 选择器命中：选择器里**每一个 class** 都要属于本屏，否则就是串味
    expect(_selector_hits(".cbfrow.sel", {"cbfrow", "sel"}),
           "自检：.cbfrow.sel 在本屏同时有 cbfrow 与 sel 时必须命中")
    expect(not _selector_hits(".cbfrow.sel", {"cbfrow"}),
           "自检：本屏没有 sel 时 .cbfrow.sel 不许命中（变体类串味）")
    expect(not _selector_hits(".cbfrowx", {"cbfrow"}), "自检：.cbfrowx 不许命中 cbfrow")
    # `.cnt` 在 .grphead / .viewtabs / .sidehead / .card>h3 里都有，本屏只用了后者 ——
    # 只判"含某个 token"会把别的组件的规则算进本屏（实测凭空多出一个 500 字重）
    expect(not _selector_hits(".grphead .cnt", {"cnt"}),
           "自检：.grphead .cnt 不许命中只用了 .card>h3 .cnt 的屏")
    expect(_selector_hits(".card>h3 .cnt", {"card", "cnt"}),
           "自检：.card>h3 .cnt 在本屏两者都有时必须命中")
    # 变体作用域：class 全在本屏集合里也照样排除（body.font-lg 画的是另一种上下文）
    expect(not _selector_hits("body.font-lg .device .topbar h1", {"topbar", "device", "font-lg"}),
           "自检：body.font-lg 变体作用域必须排除")
    expect(not _selector_hits("html[data-theme=dark] .card", {"card"}),
           "自检：深色变体作用域必须排除")
    # 外壳 / 注解块：元素选择器对 `SEL_CLASS` 不可见，`.annot code` 的 token 只有 `annot`，
    # 于是 `.annot b{font-weight:600}` / `.annot code{border-radius:4px}` 会被算成本屏该有的值。
    # `.annot` 是原型的设计说明块，**明确不实现** —— 量它就是在量一个永远对不上的东西。
    expect(not _selector_hits(".annot code", {"annot"}),
           "自检：.annot code 是注解块（不实现），不许算本屏取值")
    expect(not _selector_hits(".annot b", {"annot"}),
           "自检：.annot b 不许把 600 字重算进本屏")
    expect(not _selector_hits(".dnav .ni", {"dnav", "ni"}),
           "自检：底部导航外壳不许算本屏取值")
    expect(_selector_hits(".card>h3 .cnt", {"card", "cnt"}),
           "自检：非外壳选择器不许被误杀")

    # 2b) **第 27 洞：变体是层叠覆盖，不是并集。**
    # 桌面屏（`form:'desktop'`）渲染在 `.win` 窗口外壳里，`.win X` 的规则是**生效值**；
    # 手机屏渲染在 `.device` 里，一个 `.win` 都不该认。以前 `VARIANT_SEL` 把整段 `.win`
    # 挡掉、剩下的是被浏览器覆盖掉的基线，于是"越改越对、数字越难看"。
    expect(_selector_rank(".win .sw", {"sw"}, "win") == 1,
           "自检：桌面屏必须认 .win 前缀（否则真正生效的值一条都进不来）")
    expect(_selector_rank(".sw", {"sw"}, "win") == 0,
           "自检：无前缀的基线规则层级必须是 0")
    expect(_selector_rank(".win .sw", {"sw"}, None) is None,
           "自检：手机屏不许认 .win 前缀（那是桌面窗口外壳）")
    expect(_selector_rank(".winset .li", {"li"}, "win", settings=True) == 1,
           "自检：设置档屏必须认 .winset 前缀（那是它自己的外壳）")
    # **第五十二洞：`.winset` 只是设置档的外壳，不是每个桌面屏的外壳。**
    # 原型 `win()` 里 `const set = s.tier === 'settings' ? ' winset' : '';` —— 一视同仁
    # 会让 `.winset .li{gap:8px}` 覆盖掉所有桌面屏的基线 `.li{gap:10px}`，把真差集盖住。
    expect(_selector_rank(".winset .li", {"li"}, "win") is None,
           "自检：非设置档的桌面屏不许认 .winset（第五十二洞：它会把基线覆盖掉、盖住真差集）")
    expect(_strip_variant(".winset .li", "win") == ".winset .li",
           "自检：非设置档的屏不许把 .winset 剥成 .li 去抢基线键（第五十二洞）")
    expect(_strip_variant(".winset .li", "win", settings=True) == ".li",
           "自检：设置档的屏必须把 .winset 剥掉，否则覆盖对不上同一个键")
    _wcss = ".li{gap:10px}.winset .li{gap:8px}"
    _pwn, _ = proto_values(_wcss, {}, {"li"}, variant="win")
    expect(10.0 in _pwn["spacing"] and 8.0 not in _pwn["spacing"],
           "自检：非设置档桌面屏的 .li 必须取基线 10，不许被设置窗的 8 覆盖（第五十二洞）")
    _pws, _ = proto_values(_wcss, {}, {"li"}, variant="win", settings=True)
    expect(8.0 in _pws["spacing"] and 10.0 not in _pws["spacing"],
           "自检：设置档屏的 .li 必须取 .winset 的覆盖值 8")
    expect(_selector_rank(".win", {"win"}, "win") is None,
           "自检：`.win` 本身是外壳根元素，不是本屏内容")
    expect(_selector_rank(".device .topbar h1", {"topbar", "device"}, "win") is None,
           "自检：设备外壳 .device 永远不是本屏基线")
    expect(_strip_variant(".win .card>h3", "win") == ".card>h3",
           "自检：去变体前缀后必须与基线规则对上同一个键（否则覆盖不成立）")
    _vcss = ":root{--x:1px}.grphead{padding:14px 4px 6px}.win .grphead{padding:12px 3px 5px}"
    _pvw, _ = proto_values(_vcss, {}, {"grphead"}, variant="win")
    expect(14.0 not in _pvw["spacing"] and 4.0 not in _pvw["spacing"],
           "自检：被 .win 覆盖掉的基线 padding 不许再算成这一屏该有的值（并集就是第 27 洞）")
    expect(12.0 in _pvw["spacing"] and 3.0 in _pvw["spacing"],
           "自检：.win 的覆盖值必须进来")
    _pvm, _ = proto_values(_vcss, {}, {"grphead"}, variant=None)
    expect(14.0 in _pvm["spacing"] and 12.0 not in _pvm["spacing"],
           "自检：手机屏只取基线值，不许把 .win 的覆盖值算进来")

    # 2c) **第 28 洞：量的是"元素上算出来的值"，不是"class 出现过的规则"。**
    # ① 特异性：`.btn.sm` 与 `.btn` 长在**同一个元素**上，浏览器里 `.btn.sm` 赢，
    #    `.btn{padding:var(--pad-btn)}` 根本没生效 —— 键按选择器字符串分就会两条都留下。
    _scss = ":root{--pad-btn:7px 12px}.btn{padding:var(--pad-btn)}.btn.sm{padding:6px 10px}"
    _els = {("button", frozenset({"btn", "sm"}))}
    _scv, _ = proto_values(_scss, {"--pad-btn": "7px 12px"}, {"btn", "sm"}, elements=_els)
    expect(7.0 not in _scv["spacing"] and 12.0 not in _scv["spacing"],
           "自检：被 .btn.sm 特异性压掉的 .btn padding 不许算成这一屏该有的值（第 28 洞）")
    expect(6.0 in _scv["spacing"] and 10.0 in _scv["spacing"],
           "自检：.btn.sm 的特异性更高，它的 padding 必须进来")
    # 同一屏上真的有一颗纯 `.btn` 时，`.btn` 的 padding 仍然要算（别修成"一律不要"）
    _scv2, _ = proto_values(_scss, {"--pad-btn": "7px 12px"}, {"btn", "sm"},
                            elements={("button", frozenset({"btn", "sm"})),
                                      ("button", frozenset({"btn"}))})
    expect(7.0 in _scv2["spacing"],
           "自检：本屏真有纯 .btn 元素时，.btn 的 padding 必须仍然算进来")
    # ② 后代选择器：`.hint code` 的 token 只有 `hint`（元素选择器对 SEL_CLASS 不可见），
    #    于是只要本屏有 `.hint` 就算命中 —— 可本屏的 `.hint` 里**没有 `<code>`**。
    _dcss = ".hint code{padding:1px 4px;border-radius:4px}.hint{padding:2px}"
    _dev, _ = proto_values(_dcss, {}, {"hint"},
                           elements={("div", frozenset({"hint"}))})
    expect(4.0 not in _dev["spacing"] and 4.0 not in _dev["radius"],
           "自检：没有 <code> 子元素时 .hint code 不许命中（元素选择器不是 class）")
    # 反向：真有 <code> 时必须命中 —— 否则就是"为了消一个假阳性造出一个假阴性"
    _dev2, _ = proto_values(_dcss, {}, {"hint", "code"},
                            elements={("div", frozenset({"hint"})),
                                      ("code", frozenset({"code"}))})
    expect(2.0 in _dev2["spacing"],
           "自检：本屏确实有 .hint 时它的 padding 必须算进来")
    # `collect_elements` 必须跟进 helper（`topbar()` / `card()` / `ic()` 生成的是元素）
    _bodies = {"s": "return card(`<div class=\"tlist\"><li class=\"x\"></li></div>`)",
               "card": "function card(b){return `<div class=\"card\">${b}</div>`}"}
    _els2 = collect_elements(_bodies, "s", depth=2)
    expect(("div", frozenset({"card"})) in _els2 and ("li", frozenset({"x"})) in _els2,
           "自检：collect_elements 必须跟进 helper 拿到的元素（否则整个外壳都看不见）")

    # 3) 变量作用域：桌面屏必须拿到 .win 的覆盖值
    css = ":root{--r-card:16px;--padc:10px 14px 14px}.win{--r-card:12px;--padc:8px 13px 13px}"
    root, win = var_scopes(css)
    expect(root.get("--r-card") == "16px", "自检：root 的 --r-card 必须是 16px")
    expect(win.get("--r-card") == "12px", "自检：.win 的 --r-card 必须是 12px")
    v = dict(root)
    v.update(win)
    expect(expand_vars("var(--r-card)", v) == "12px", "自检：桌面屏必须用 .win 的作用域")
    # 3b) 同一行压多条声明：**每一条都要抓到**（第十个量具洞）
    # 原型 `:root` 里就是一行五个：
    # `--fs-body:14px; --fs-bub:14px; --r-card:16px; --r-bubble:12px; --r-ctl:12px;`
    # 旧的 VAR_DEF 带 `^\s*` + re.M 锚定，只抓每行第一条 —— 实测 `:root` 只解析出 24 个变量
    # （应 60），`--r-card`(16)/`--r-ctl`(12)/`--r-bubble`(12)/`--padc`/`--fs-ctitle`/`--fs-btn`/
    # `--pad-row`/`--pad-btn`/`--fs-hint`/`--fs-row1`/`--fs-row2`/`--status` 全丢，
    # 差集因此欠报 23 组 radius|proto（卡圆角 16px / 面板 28px 首次可见）。
    oneline = ":root{--a:1px; --b:2px; --c:3px}"
    r2, _ = var_scopes(oneline)
    expect(r2.get("--a") == "1px" and r2.get("--b") == "2px" and r2.get("--c") == "3px",
           "自检：同一行压多条 CSS 变量声明时必须每一条都抓到（行首锚定只会抓第一条）")
    # 反面：用途行不许被当成定义（`border-radius:var(--r-chip)` 里没有 `--x:`）
    r3, _ = var_scopes(":root{--a:1px}.card{border-radius:var(--r-chip)}")
    expect(r3 == {"--a": "1px"}, "自检：var() 用途行不许被当成变量定义")

    # 3c) helper 解析 + 模板插值（第十二、十三个量具洞）
    # 原型里 helper 有两种写法：`function topbar(...){}` 与 `const shareChip = i => `…``。
    # 早期 fn_bodies 只认前者 ⇒ 箭头 helper 的函数体从未被解析 ⇒
    # `class="cbchip${…}"` 里的 `cbchip` 进不了集合 ⇒ `.cbchip{padding:5px 11px;border-radius:8px}`
    # 对所有用到它的屏都不可见（实测 m-cabinet 的 proto radius 少了 8、spacing 少了 5/11）。
    _b = fn_bodies(
        'function a(){return shareChip(1) + `<div class="card">`;}\n'
        "const shareChip = i => `<span class=\"cbchip${i===1?' on':''}\">x</span>`;\n"
    )
    expect("a" in _b, "自检：function 声明的 helper 必须被解析")
    expect("shareChip" in _b, "自检：箭头函数 helper 必须被解析（否则它的 class 字面量全丢）")
    _c = collect_classes(_b, "a", 2)
    expect("card" in _c, "自检：被调 helper 的 class 字面量必须进集合")
    expect("cbchip" in _c, "自检：模板插值**之前**的静态 class 前缀必须留下")
    expect("on" in _c, "自检：插值里的静态 class（`' on'`）必须抽出来")
    expect(not any(re.search(r"[$'\"{}]", t) for t in _c),
           "自检：集合里不许出现 `'+cls:''}` 这种模板碎片")

    # 3c-bis) 别名（第二十六个量具洞）
    # `val sp = LocalAppSpacing.current` 之后写 `sp.lg14`：量具原来只认全名，
    # 一起别名这个切片里的间距值就全没了 —— 而**迁移到档位正是门禁想要的方向**，
    # 结果是"越改越看不见"。这一条自检就是钉住那个方向。
    _tok = ({"xs": 4.0, "sm6": 6.0, "sm": 8.0, "md10": 10.0, "lg14": 14.0, "lg": 16.0}, {}, {})
    _v, _ = code_values(
        [("x.kt", "f", "val sp = LocalAppSpacing.current\nColumn(Modifier.padding(sp.lg14)) {}\n")],
        _tok,
    )
    expect(_v["spacing"] == {14.0}, "自检：`val sp = LocalAppSpacing.current` 的别名必须解析成数值")
    _v2, _ = code_values([("x.kt", "f", "Column(Modifier.padding(sp.lg14)) {}\n")], _tok)
    expect(_v2["spacing"] == set(),
           "自检：**没有**别名声明时不许凭空认 `sp.lg14`（否则任何同名局部都会冒充档位）")
    _v3, _ = code_values(
        [("x.kt", "f", "val sp = LocalAppSpacing.current\nColumn(Modifier.padding(sp.nope)) {}\n")],
        _tok,
    )
    expect(_v3["spacing"] == set(), "自检：别名后面不是档位名时不许算数")

    # 3d) 默认参数里的花括号（第十六个量具洞）
    # `actions: @Composable () -> Unit = {}` 是极常见的槽位参数写法。旧实现直接
    # `src.find("{", m.end())`，花括号配对就停在**参数表**里 —— 实测 `TopBars.kt` 的
    # `LargeTitleBar` 切片只有 177 字符，止于 `= {}`，**函数体一个字都没扫到**，
    # 于是它供的 24sp 标题对 35 屏全部不可见、被报成 `font-size 只原型{24}`。
    _fb = _fun_bodies(
        "fun Slot(\n    title: String,\n    actions: @Composable () -> Unit = {},\n) {\n"
        "    Text(title, style = MaterialTheme.typography.headlineSmall)\n}\n"
    )
    expect("headlineSmall" in _fb.get("Slot", ""),
           "自检：默认参数里有 `= {}` 时函数体仍必须被完整切出（否则它的取值全丢）")
    _fe = _fun_bodies('fun A() = 1 + 2\nfun B() { Text("x") }\n')
    expect("B" not in _fe.get("A", ""),
           "自检：表达式体函数不许把下一个函数的花括号吞进来")

    # 3e) 代码侧必须能看见跨目录的共享组件（第十五个量具洞）
    # 代码侧原来只在「落点文件 + **同目录**兄弟」里找函数，跨目录的共享组件永远不可见。
    # 原型侧 `collect_classes` 早就递归跟进自己的 helper；代码侧跟进自己的调用链是对称的。
    import tempfile
    with tempfile.TemporaryDirectory() as _td:
        _rel = "composeApp/src/commonMain/kotlin/app/relay"
        _d = os.path.join(_td, _rel, "ui/components")
        os.makedirs(_d)
        with open(os.path.join(_d, "Bars.kt"), "w", encoding="utf-8") as _f:
            _f.write("fun SharedBar(t: String, actions: @Composable () -> Unit = {}) {\n"
                     "    Text(t, style = MaterialTheme.typography.headlineSmall)\n}\n")
        _fe = os.path.join(_td, _rel, "feature/x")
        os.makedirs(_fe)
        with open(os.path.join(_fe, "ScreenX.kt"), "w", encoding="utf-8") as _f:
            _f.write('fun ScreenX() {\n    SharedBar("t")\n}\n')
        _gi = _fun_index(_td)
        expect("SharedBar" in _gi,
               "自检：跨目录共享组件必须进函数索引（否则它的取值被算成「只原型」）")
        expect("headlineSmall" in _gi.get("SharedBar", ("", ""))[1],
               "自检：索引里的共享组件必须是**完整函数体**")
        # **接线**也要有牙：索引建好了、`landing_slices` 却不用它，等于没修。
        _picked, _ = landing_slices(_td, "feature/x/ScreenX.kt", "screenX")
        expect(any(n == "SharedBar" for _, n, _ in _picked),
               "自检：落点调用的共享组件必须真的进切片（只进索引不进切片等于没修）")

        # 3e-2) **第 43 洞：同目录的"按用途命名"的零件文件也要能跟进。**
        # 原有的"同目录一跳"只认**文件名等于函数名**的兄弟（`PeerCabinetView.kt::PeerCabinetView`），
        # 于是 `SettingsPageChrome.kt`（里面装着 `SettingsPageScaffold` / `SettingSwitchRow`）
        # 永远不被打开 ⇒ 整屏的间距/字号/圆角被算成"只原型"。
        # 两条判据都要有：① `_fun_index` 不传 `extra_dirs` 时**看不见**它（先证明盲区存在）；
        # ② `landing_slices` 走完流程后它**在切片里**（证明接线生效）。
        with open(os.path.join(_fe, "Chrome.kt"), "w", encoding="utf-8") as _f:
            _f.write("fun RowHelper() {\n    Modifier.padding(14.dp)\n}\n")
        with open(os.path.join(_fe, "ScreenX.kt"), "w", encoding="utf-8") as _f:
            _f.write('fun ScreenX() {\n    SharedBar("t")\n    RowHelper()\n}\n')
        expect("RowHelper" not in _fun_index(_td),
               "自检：只索引 ui/ 时，同目录按用途命名的零件文件**确实**看不见（先证明盲区存在）")
        expect("RowHelper" in _fun_index(_td, {_fe}),
               "自检：把落点文件所在目录交给索引后，同目录的零件文件必须进得来（第 43 洞）")
        _picked2, _ = landing_slices(_td, "feature/x/ScreenX.kt", "screenX")
        expect(any(n == "RowHelper" for _, n, _ in _picked2),
               "自检：同目录零件文件里的行组件必须真的进切片（只进索引不进切片等于没修）")
        expect("14.dp" in next(b for _, n, b in _picked2 if n == "RowHelper"),
               "自检：跟进来的行组件必须是**完整函数体**（否则它的取值还是丢）")
        # 反向：**不调用**的零件不许被无差别并进来（否则就是拿另一种错换这一种错）。
        with open(os.path.join(_fe, "Unused.kt"), "w", encoding="utf-8") as _f:
            _f.write("fun NotCalledHelper() {\n    Modifier.padding(99.dp)\n}\n")
        _picked3, _ = landing_slices(_td, "feature/x/ScreenX.kt", "screenX")
        expect(not any(n == "NotCalledHelper" for _, n, _ in _picked3),
               "自检：同目录里**入口没调用**的零件不许并进本屏切片（只跟调用链，不整目录搬）")

        # 3f) **第 36 洞：闭包不许把"从这一屏能打开的另一个屏"并进本屏。**
        # `ConversationsScreen` 调 `GroupCreateDialog`，而 `GroupCreateDialog.kt` 是
        # `m-groupcreate`/`d-groupcreate` 这两行的落点文件 —— 于是另外 15 行（`m-home-recent`
        # 等）的切片里凭空多出建群弹层的 20/5/9/1，被报成"只代码"。
        with open(os.path.join(_d, "Dialog.kt"), "w", encoding="utf-8") as _f:
            _f.write("fun Dialog() {\n    Modifier.clip(RoundedCornerShape(20.dp))\n}\n")
        with open(os.path.join(_d, "Helper.kt"), "w", encoding="utf-8") as _f:
            _f.write("fun ToolbarButton() {\n    Modifier.clip(RoundedCornerShape(7.dp))\n}\n")
        with open(os.path.join(_fe, "ScreenX.kt"), "w", encoding="utf-8") as _f:
            _f.write('fun ScreenX() {\n    SharedBar("t")\n    Dialog()\n    ToolbarButton()\n}\n')
        _exf = {os.path.abspath(os.path.join(_d, "Dialog.kt")),
                os.path.abspath(os.path.join(_d, "Helper.kt"))}
        _old, _ = landing_slices(_td, "feature/x/ScreenX.kt", "screenX")
        expect(any(n == "Dialog" for _, n, _ in _old),
               "自检：不给排除集时，闭包**确实**会把另一屏并进来（先证明机制存在）")
        _new, _ = landing_slices(_td, "feature/x/ScreenX.kt", "screenX", exclude=_exf)
        expect(not any(n == "Dialog" for _, n, _ in _new),
               "自检：另一屏的落点文件里**与文件同名**的 Composable 不许进本屏切片（第 36 洞）")
        # 反向：文件里**不同名**的零件是同目录共享件，排掉就是"为了消一个假阳性造出一个假阴性"。
        expect(any(n == "ToolbarButton" for _, n, _ in _new),
               "自检：另一屏的落点文件里**不同名**的共享零件必须留住（DesktopChrome.kt::ToolbarButton 就是这一类）")
        expect(is_other_screen(os.path.join(_d, "Dialog.kt"), "Dialog", _exf),
               "自检：is_other_screen 对『别的行的落点文件 + 与文件同名』必须为真")
        expect(not is_other_screen(os.path.join(_d, "Helper.kt"), "ToolbarButton", _exf),
               "自检：is_other_screen 对『别的行的落点文件 + 与文件不同名』必须为假")
        # 本行**自己**点名的文件永远不排（否则 `m-groupcreate` 自己也量不到弹层）
        expect(not is_other_screen(os.path.join(_d, "Dialog.kt"), "Dialog", set()),
               "自检：本行自己的落点文件不在排除集里时不许被排掉")

    # 3g) **第 37 洞：量具不许把注释里写的 token 名当成代码取值。**
    # 在 `GroupCreateDialog.kt` 里写「M3 `AlertDialog` 默认取 `shapes.extraLarge`（本仓 28dp）」
    # 解释为什么必须显式给 20dp —— 量具真把 28 记进了圆角集合，两行各多一条「只代码 28」。
    _ctok = ({}, {}, {"extraLarge": 28.0, "medium": 12.0})
    _csv, _ = code_values([("p", "f", "// 默认取 shapes.extraLarge（本仓 28dp）\n"
                                     "Modifier.clip(RoundedCornerShape(20.dp))")], _ctok)
    expect(28.0 not in _csv["radius"] and 20.0 in _csv["radius"],
           "自检：注释里的 shapes.extraLarge / 28dp 不许算成取值，真代码里的 20 必须算")
    _csv2, _ = code_values([("p", "f", "val u = \"https://x.example/a\"\n"
                                      "Modifier.padding(12.dp)")], _ctok)
    expect(12.0 in _csv2["spacing"],
           "自检：字符串里的 `//`（https://…）不许被当成注释起点，后面的真取值必须还在")

    # 4) 差集方向：两边都有 14 就不该进任何一侧
    pv, cv = {14.0, 16.0}, {14.0, 12.0}
    expect(sorted(pv - cv) == [16.0], "自检：只在原型必须是 {16}")
    expect(sorted(cv - pv) == [12.0], "自检：只在代码必须是 {12}")

    # 4b) 药丸圆角：原型 999 ↔ 代码 CircleShape，且只在原型也有 999 时才认
    _s = [("p", "f", "Modifier.clip(CircleShape)")]
    expect(_pill_matched({999.0}, _s), "自检：原型 999 + 代码 CircleShape 必须算配上")
    expect(not _pill_matched({12.0}, _s),
           "自检：原型没有 999 时，头像用的 CircleShape 不许凭空算成药丸（那会造假阳性）")
    expect(not _pill_matched({999.0}, [("p", "f", "RoundedCornerShape(12.dp)")]),
           "自检：原型有 999 而代码没写 CircleShape 时不许算配上（真缺口要留住）")

    # 4b2) 第五十一洞：按钮继承字重（M3 `Button` 吃 `labelLarge`），与药丸同一个形状。
    _toks = ({}, {"labelLarge": {"size": 14.0, "weight": 500.0}}, {})
    _bs = [("p", "f", "OutlinedButton(onClick = {}) {}")]
    expect(_button_weight_matched({500.0}, _bs, _toks),
           "自检：原型有 .btn 的 500、代码是 M3 按钮时必须算配上（继承字重也是字重）")
    expect(not _button_weight_matched({600.0}, _bs, _toks),
           "自检：原型没有 500 时不许给按钮凭空补上 labelLarge 的字重（那会造假阳性）")
    expect(not _button_weight_matched({500.0}, [("p", "f", "Text(\"hi\")")], _toks),
           "自检：代码里没有按钮调用时不许算配上（真缺口要留住）")
    expect(not BUTTON_CALL.search("IconButton(onClick = {})"),
           "自检：`IconButton(` 不许被当成 M3 按钮（词边界）")

    # 4c) 第 39 洞：间距维度上 `0` 等于「没写这个参数」，两侧都不许进差集；
    #     但**真缺口必须留住**，且这条规则不许蔓延到其它维度。
    expect(diff_sets({0.0, 14.0}, {14.0}, "spacing") == ([], []),
           "自检：原型写了 padding:0、代码干脆不写这个参数，不许报「只原型 0」")
    expect(diff_sets({0.0}, set(), "spacing") == ([], []),
           "自检：原型只有 0、代码什么都没有，不许报「只原型 0」")
    expect(diff_sets({0.0, 16.0}, {14.0}, "spacing") == ([16.0], [14.0]),
           "自检：`0` 之外的真缺口（原型 16、代码 14）必须照旧报出")
    expect(diff_sets({0.0}, set(), "radius") == ([0.0], []),
           "自检：`0 等于没写` 只对间距成立，圆角 0 是真值，不许被吞")

    # 5) 棘轮：差集变大要报红，变小要算改进
    base = {"signature": {"m-x|spacing|proto": ["9", "13"]}}
    regress, improved = ratchet({"m-x|spacing|proto": ["9", "13", "11"]}, base)
    expect(regress == [("m-x|spacing|proto", ["11"])], "自检：新增的差集值必须报红")
    regress, improved = ratchet({"m-x|spacing|proto": ["9"]}, base)
    expect(not regress and improved == [("m-x|spacing|proto", ["13"])],
           "自检：差集变小必须算改进且不报红")

    if fails:
        for f in fails:
            print("FAIL — " + f)
        return 1
    print("自检通过：var 展开 / 选择器命中 / 变体层叠 / 变量作用域 / 切片边界 / 注释剥离 / "
          "零即缺省 / 差集方向 / 棘轮 九种情形判定全部正确")
    return 0


# ── 入口 ──────────────────────────────────────────────────────────
def main(argv=None):
    ap = argparse.ArgumentParser(description="逐屏还原度差集（原型取值集合 vs 代码落点取值集合）")
    ap.add_argument("--root", default=".", help="仓库根（默认 .）")
    ap.add_argument("--ledger", default=None, help=f"配对台账（默认 {LEDGER_REL}）")
    ap.add_argument("--screen", action="append", default=None, help="只看这一屏，可重复")
    ap.add_argument("--all", action="store_true", help="全部屏")
    ap.add_argument("--markdown", default=None, help="把报告写到这个文件")
    ap.add_argument("--baseline", action="store_true", help="重记基线")
    ap.add_argument("--reason", default=None, help="重记基线时写明为什么可以更差")
    ap.add_argument("--check", action="store_true", help="棘轮：差集只许变小")
    ap.add_argument("--self-test", action="store_true", help="量具先验")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    repo = os.path.abspath(args.root)
    only = None if (args.all or not args.screen) else set(args.screen)
    try:
        result = collect(repo, args.ledger, only)
    except DiffError as e:
        print(f"FAIL — {e}")
        return 1

    if args.markdown:
        with open(os.path.join(repo, args.markdown), "w", encoding="utf-8") as f:
            f.write(render(result))
        print(f"[screen-diff] 报告已写入 {args.markdown}")

    base_path = os.path.join(repo, BASELINE_REL)

    if args.baseline:
        base = load_baseline(base_path)
        sig = signature(result)
        if base:
            regress, _ = ratchet_from_sig(sig, base)
            if regress and not args.reason:
                print(f"FAIL — 重记基线会记下 {len(regress)} 组比旧基线更大的差集：")
                for key, vals in regress[:10]:
                    print(f"  · {key} 新增 {vals}")
                print("先确认是原型/代码真变了还是落点选错了；确属要接受，用 --reason 写明理由。")
                return 1
        write_baseline(base_path, result, args.reason)
        print(f"[screen-diff] 基线已写入 {BASELINE_REL}："
              f"{len(sig)} 组差集，{result['meta']['sha256']}")
        return 0

    if args.check:
        base = load_baseline(base_path)
        if not base:
            print(f"FAIL — 没有基线（{BASELINE_REL}）。棘轮无基线就是没闸门，"
                  f"先跑 --baseline 记账再判。")
            return 1
        # **第四十五个洞**：设计源换了版本，基线就不再有可比性。
        # 实测（2026-10-12）：用户在 10:14 亲改原型（sha `4f46e83cfffd` → `c51db35449f9`），
        # 而基线仍记着旧稿的取值集合 ⇒ `--check` 报「40 组差集比基线更大」，
        # 看上去像代码退化了，实际是**拿旧稿的尺子量新稿的活**：
        # 逐条追下来 40 组里 39 组的"新增"都只是原型那一侧多/少了一个值。
        # 这比不判红更坏 —— 真正的退化会淹在这 40 条里没人看。
        # 判据：基线自陈的 `generatedAt`（= 记基线时原型的 sha 前 12 位）与当前不一致
        # ⇒ 拒绝判定，让人先重投影。**不许静默通过**（静默通过等于闸门消失）。
        if base.get("generatedAt") != result["meta"]["sha256"]:
            print(f"FAIL — 基线是拿**另一个版本的原型**记的，两边不可比：")
            print(f"  · 基线记于原型 sha {base.get('generatedAt')}")
            print(f"  · 当前原型   sha {result['meta']['sha256']}")
            print("设计源换了版本 ⇒ 差值集合整体位移，逐键比较没有意义。")
            print("先确认新稿就是权威源（人已冻结），再 `--baseline --reason \"…\"` 重投影。")
            return 1
        regress, improved = ratchet(signature(result), base)
        if regress:
            print(f"FAIL — {len(regress)} 组差集比基线更大（原型有、代码没有的值变多了）：")
            for key, vals in regress[:20]:
                print(f"  · {key} 新增 {vals}")
            print("差集只许变小。要么改代码/落点，要么确认原型真的新增了这一档再 --baseline。")
            return 1
        n = sum(len(v) for v in signature(result).values())
        print(f"[screen-diff] OK — {len(signature(result))} 组差集无扩大"
              f"（共 {n} 个对不上的值；基线 {len(base.get('signature') or {})} 组，"
              f"本轮改进 {len(improved)} 组）")
        return 0

    # 默认：打印
    rows = result["rows"]
    tot = 0
    for row in rows:
        if row.get("error"):
            print(f"{row['screen']:22s} FAIL — {row['error']}")
            continue
        parts = []
        for dim in DIM_ORDER:
            op = row["dims"][dim]["onlyProto"]
            oc = row["dims"][dim]["onlyCode"]
            if op or oc:
                parts.append(f"{dim} 只原型{{{','.join(fmt(v) for v in op)}}} "
                             f"只代码{{{','.join(fmt(v) for v in oc)}}}")
        n = sum(len(row["dims"][d]["onlyProto"]) + len(row["dims"][d]["onlyCode"])
                for d in DIM_ORDER)
        tot += n
        if parts:
            print(f"{row['screen']:22s} {n:3d} 处  " + "；".join(parts))
        else:
            print(f"{row['screen']:22s}   0 处  逐维取值集合一致")
    print(f"\n共 {len(rows)} 屏，{tot} 个值对不上。")
    return 0


def ratchet_from_sig(sig, base):
    regress, improved = [], []
    for key, vals in sig.items():
        old = set((base.get("signature") or {}).get(key) or [])
        new = set(vals)
        if new - old:
            regress.append((key, sorted(new - old)))
        if old - new:
            improved.append((key, sorted(old - new)))
    return regress, improved


if __name__ == "__main__":
    sys.exit(main())
