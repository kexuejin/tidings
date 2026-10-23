#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""UX 记分板：一条命令打印「界面与体验」这一轮的可数量指标。

## 它为什么存在

本仓的颜色与间距一直很精致，是因为它们有能判红的门禁（配色对账、token 消费率）。
而「发送后多久出现气泡」「界面渲染了几个原始标识符」这类体验事实此前没有口径 ——
于是「改好了没有」只能靠记忆。这个脚本把那些事实变成可数的量。

## 用法

    python3 tools/ux-scoreboard.py              # 打印表格
    python3 tools/ux-scoreboard.py --json       # 机器可读
    python3 tools/ux-scoreboard.py --check      # 与基线比，变差则退出码 1
    python3 tools/ux-scoreboard.py --self-test  # 量具自检（剥注释器的已知答案）

## 口径纪律（这一节是被独立验证者逼出来的，别删）

**指标只有两类，混淆它们就是撒谎：**

1. **可数事实**（leak.* / tz.* / fb.* / cjk.* / ui.dead_button / conv.state_fields）
   —— 源码里数得出来的东西，可以当判据。
2. **静态顺序代理**（proxy.*）—— 只回答「A 的首次出现是否早于 B」。它**不是**行为断言：
   把代码放进一个永不执行的分支也能让它变 1。**这类指标只能看趋势，不许用来签收。**
   真正的判据是闸门式单测（假 send 挂起 → 放行前断言气泡已存在）。

**剥注释是这套量具的地基，而它比看上去难。** 两种已知翻车形态：
- 不剥块注释 ⇒ 注释里写一句「空按钮」的样式就把指标刷红（假红）；
- 字符串里的双斜线把活代码截断 ⇒ 同一行里的死按钮看不见（假绿，而且能被人为藏掉）。

所以这里用一个状态机（code / line / block / string / raw / char），**只删注释、保留字符串
内容**（因为 UUID 指标要看得见 text 赋值里的字符串）。--self-test 把已知答案钉死；
改这个函数必须让自检仍绿。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

SCAN_ROOTS = ["composeApp/src", "desktopApp/src", "core/src"]
SKIP_PATH_MARKERS = ["/jvmTest/", "/commonTest/", "/test/", "/androidHostTest/", "/iosTest/"]

# 空实现按钮的白名单：**每条都必须带理由**，否则白名单会变成"把缺陷藏起来"的地方
DEAD_BUTTON_WHITELIST = {
    "ui/components/ImageViewerDialog.kt": "阻止点击图片区域触发关闭（点击穿透拦截，不是入口）",
    "feature/chat/ChatBubbles.kt": "长按菜单的宿主：onClick 空、onLongClick 才是入口",
    "feature/conversations/ConversationsOverflowItems.kt": "菜单项 enabled = false，刻意不可点",
}

DEAD_BUTTON_RE = r"onClick\s*=\s*\{\s*\}"

# 组件层自己就调用 Material3 原语 / 自己用动画与触感 —— 那是它存在的意义，不该计入"待迁移 / 已采用"。
# 这里用**目录**而不是单个文件名：按文件名排除的洞是"把原语写进第二个包装文件就永不被计数"。
COMPONENT_LAYER_SUFFIXES = (
    "ui/components/AppButtons.kt",
    "ui/components/PressableSurface.kt",
)

# 裸按钮原语族。**必须补全** —— 只数 4 种时，"迁移完成"可以靠改写成没被覆盖的一族达成：
# 本仓已经在用 FilledIconButton / FilledTonalButton / FilterChip，漏掉它们等于给了一个后门。
RAW_BUTTON_PATTERNS = (
    r"(?<![A-Za-z])Button\(",
    r"(?<![A-Za-z])OutlinedButton\(",
    r"(?<![A-Za-z])IconButton\(",
    r"(?<![A-Za-z])TextButton\(",
    r"(?<![A-Za-z])FilledButton\(",
    r"(?<![A-Za-z])FilledTonalButton\(",
    r"(?<![A-Za-z])FilledIconButton\(",
    r"(?<![A-Za-z])OutlinedIconButton\(",
    r"(?<![A-Za-z])ElevatedButton\(",
    r"(?<![A-Za-z])FilterChip\(",
    r"(?<![A-Za-z])AssistChip\(",
    r"(?<![A-Za-z])SuggestionChip\(",
)

MIGRATED_BUTTON_PATTERNS = (
    r"(?<![A-Za-z])AppButton\(",
    r"(?<![A-Za-z])AppOutlinedButton\(",
    r"(?<![A-Za-z])AppTextButton\(",
    r"(?<![A-Za-z])AppIconButton\(",
)


def repo_root() -> str:
    """扫描根。UX_SCOREBOARD_ROOT 可覆盖 —— 用来对**改动前的内容**取基线。

    为什么需要它：量具修好之后，旧基线是用旧量具量的，两者不可比。
    要拿一份诚实的基线，就得用**新量具**去量**改动前的内容**（git archive 出来的树），
    而不是把改动后的现状记成基线 —— 那等于把已修的东西算成"本来就该有"。
    """
    override = os.environ.get("UX_SCOREBOARD_ROOT")
    if override:
        return override
    here = os.path.dirname(os.path.abspath(__file__))
    cur = here
    for _ in range(6):
        if os.path.isfile(os.path.join(cur, "settings.gradle.kts")):
            return cur
        cur = os.path.dirname(cur)
    return os.path.dirname(here)


ROOT = repo_root()


def strip_comments(source: str) -> str:
    """删掉 Kotlin 源码里的注释，**保留字符串字面量内容**；长度与换行位置不变。

    为什么保留字符串：UUID 指标要匹配字符串**里面**的内容，把字符串一起抹掉就永远量不到。
    为什么必须过状态机：字符串里的双斜线不是注释起点；直接 split 会把同一行的活代码切掉，
    缺陷于是"消失"。
    """
    out = []
    index = 0
    size = len(source)
    state = "code"
    newline = chr(10)
    while index < size:
        char = source[index]
        following = source[index + 1] if index + 1 < size else ""
        if state == "code":
            if char == "/" and following == "/":
                state = "line"
                out.append("  ")
                index += 2
                continue
            if char == "/" and following == "*":
                state = "block"
                out.append("  ")
                index += 2
                continue
            if source.startswith('"""', index):
                state = "raw"
                out.append('"""')
                index += 3
                continue
            if char == '"':
                state = "string"
                out.append(char)
                index += 1
                continue
            if char == "'":
                state = "char"
                out.append(char)
                index += 1
                continue
            out.append(char)
            index += 1
            continue
        if state == "line":
            if char == newline:
                state = "code"
                out.append(newline)
                index += 1
                continue
            out.append(" ")
            index += 1
            continue
        if state == "block":
            if char == "*" and following == "/":
                state = "code"
                out.append("  ")
                index += 2
                continue
            out.append(newline if char == newline else " ")
            index += 1
            continue
        if state == "string":
            if char == "\\":
                out.append(source[index:index + 2])
                index += 2
                continue
            if char == '"':
                state = "code"
                out.append(char)
                index += 1
                continue
            if char == newline:
                state = "code"
                out.append(newline)
                index += 1
                continue
            out.append(char)
            index += 1
            continue
        if state == "raw":
            if source.startswith('"""', index):
                state = "code"
                out.append('"""')
                index += 3
                continue
            out.append(char)
            index += 1
            continue
        if state == "char":
            if char == "\\":
                out.append(source[index:index + 2])
                index += 2
                continue
            if char == "'":
                state = "code"
                out.append(char)
                index += 1
                continue
            if char == newline:
                state = "code"
                out.append(newline)
                index += 1
                continue
            out.append(char)
            index += 1
            continue
    return "".join(out)


def is_production(path: str) -> bool:
    return not any(marker in path for marker in SKIP_PATH_MARKERS)


def iter_files(exts):
    for scan_root in SCAN_ROOTS:
        abs_root = os.path.join(ROOT, scan_root)
        if not os.path.isdir(abs_root):
            continue
        for dirpath, _dirnames, filenames in os.walk(abs_root):
            for name in filenames:
                if name.endswith(exts):
                    full = os.path.join(dirpath, name)
                    if is_production(full):
                        yield full


def rel(path: str) -> str:
    return os.path.relpath(path, ROOT)


def read_code(path: str):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return strip_comments(handle.read())
    except (OSError, UnicodeDecodeError):
        return None


def read_raw(path: str):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except (OSError, UnicodeDecodeError):
        return None


def grep_count(pattern: str, exts=(".kt",), strip: bool = True, exclude=()) -> int:
    """数命中**行**数。[exclude] 是相对路径后缀，用来排除"被授权使用该原语"的文件。

    为什么需要 exclude：按钮族的包装文件**自己就调用** Material3 的 Button/OutlinedButton…
    —— 那是它存在的意义。若不排除，"裸按钮调用点"这个指标永远到不了 0（至少留 4 处），
    于是它无法作为"迁移完成"的判据。
    """
    rx = re.compile(pattern)
    hits = 0
    for path in iter_files(exts):
        if any(rel(path).endswith(suffix) for suffix in exclude):
            continue
        text = read_code(path) if strip else read_raw(path)
        if text is None:
            continue
        hits += sum(1 for line in text.splitlines() if rx.search(line))
    return hits


def grep_files(pattern: str) -> int:
    rx = re.compile(pattern)
    found = set()
    for path in iter_files((".kt",)):
        text = read_code(path)
        if text and rx.search(text):
            found.add(path)
    return len(found)


def dead_button_count() -> int:
    """白名单外的空实现按钮处数。正则允许任意空白，含无空格写法。"""
    rx = re.compile(DEAD_BUTTON_RE)
    hits = 0
    for path in iter_files((".kt",)):
        text = read_code(path)
        if text is None:
            continue
        matched = len(rx.findall(text))
        if matched == 0:
            continue
        key = rel(path).replace("composeApp/src/commonMain/kotlin/com/kapp/tidings/", "")
        if key in DEAD_BUTTON_WHITELIST:
            continue
        hits += matched
    return hits


def dead_button_hits() -> list:
    """逐处列出，给报告用（白名单外的）。"""
    rx = re.compile(DEAD_BUTTON_RE)
    rows = []
    for path in iter_files((".kt",)):
        text = read_code(path)
        if text is None:
            continue
        for number, line in enumerate(text.splitlines(), start=1):
            if rx.search(line):
                key = rel(path).replace("composeApp/src/commonMain/kotlin/com/kapp/tidings/", "")
                rows.append((key, number, key in DEAD_BUTTON_WHITELIST))
    return rows


def read_baseline_note_count(file_name: str, key: str) -> int:
    """棘轮文件里某个文件的条数。

    文件**不在**棘轮里 = 0 处欠债（不是"测不到"）—— 棘轮只记有欠债的文件，
    资源化做完并下调基线之后那一行就该消失。所以"没找到条目"返回 0，
    只有"棘轮文件本身不存在"才返回 -1。
    """
    path = os.path.join(ROOT, "tools", file_name)
    if not os.path.isfile(path):
        return -1
    try:
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                parts = line.split()
                if len(parts) >= 2 and parts[0].isdigit() and key in parts[1]:
                    return int(parts[0])
    except OSError:
        return -1
    return 0


def duplicate_composable_count() -> int:
    """同名 Composable 同时存在于 ui/desktop/ 与非 desktop 的处数。

    AGENTS.md 2.6 的判据：一个 UI 元素只许有一处定义。桌面壳有独占结构时应当**参数化**
    共用同一份实现，而不是另写一份（真身：ConversationRow 两份导致同一列表两个样）。
    """
    rx = re.compile(r"^\s*(?:public |internal |private )?fun ([A-Z]\w*)\(", re.M)
    sides = {}
    for path in iter_files((".kt",)):
        rel_path = rel(path).replace(chr(92), "/")
        if "/feature/" not in rel_path and "/ui/" not in rel_path:
            continue
        text = read_code(path)
        if text is None:
            continue
        side = "desktop" if "/ui/desktop/" in rel_path else "shared"
        for match in rx.finditer(text):
            sides.setdefault(match.group(1), set()).add(side)
    return sum(1 for name, where in sides.items() if "desktop" in where and "shared" in where)


def component_spec_count() -> int:
    """design-spec.json 里 componentSpecs.components 的条数。读不出返回 -1。"""
    try:
        with open(os.path.join(ROOT, "design", "design-spec.json"), "r", encoding="utf-8") as handle:
            spec = json.load(handle)
    except (OSError, ValueError):
        return -1
    components = (spec.get("componentSpecs") or {}).get("components") or []
    return len(components)


def test_has(file_rel: str, pattern: str) -> int:
    """测试文件存在且命中 [pattern] ⇒ 1。量的是**证据存在**，不是源码形状。"""
    path = os.path.join(ROOT, file_rel)
    if not os.path.isfile(path):
        return 0
    text = read_raw(path)
    if text is None:
        return -1
    return 1 if re.search(pattern, text) else 0


def order_ok(file_rel: str, first_pattern: str, second_pattern: str) -> int:
    """静态顺序代理：A 的首次出现是否早于 B。**不是行为断言**。"""
    text = read_code(os.path.join(ROOT, file_rel))
    if text is None:
        return -1
    first = None
    second = None
    for index, line in enumerate(text.splitlines()):
        if first is None and re.search(first_pattern, line):
            first = index
        if second is None and re.search(second_pattern, line):
            second = index
    if first is None or second is None:
        return 0
    return 1 if first < second else 0


def function_body_has(file_rel: str, signature_pattern: str, body_pattern: str) -> int:
    """某个函数**体内**是否命中（按花括号配平切出），不是全文件命中。"""
    text = read_code(os.path.join(ROOT, file_rel))
    if text is None:
        return -1
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        if re.search(signature_pattern, line):
            start = index
            break
    if start is None:
        return 0
    depth = 0
    opened = False
    body = []
    for line in lines[start:]:
        for char in line:
            if char == "{":
                depth += 1
                opened = True
            elif char == "}":
                depth -= 1
        body.append(line)
        if opened and depth <= 0:
            break
    return 1 if re.search(body_pattern, "\n".join(body)) else 0


def metrics():
    """(key, 名称, 方向, 取值函数)。down=越小越好，up=越大越好，info=只记录。"""
    yield ("leak.term", "UI 字符串里的技术术语条数", "down",
           lambda: grep_count(r"(UDP |TCP |字节|LIKE |能力：|端口)", (".xml",), strip=False))
    yield ("leak.uuid", "渲染完整 UUID 的处数", "down",
           lambda: grep_count(r'text = "\$id'))
    yield ("leak.take8", "take(8) 命中处数（人语境应改人话）", "down",
           lambda: grep_count(r"\.take\(8\)"))
    yield ("tz.hardcoded", "时区硬编码处数", "down",
           lambda: grep_count(r"\+ 8 \* 60|8 \* 3_?600"))
    # 触感：只数**真实调用点**，且排除组件层自身。
    # 原语里声明了触感不等于界面在用触感 —— 数原语自身会让"我建了个没人用的东西"
    # 看起来像"功能已上线"（这正是之前那条 fb.haptic 的错）。
    yield ("fb.haptic", "触感反馈的真实调用点数（排除组件层）", "up",
           lambda: grep_count(r"performHapticFeedback", exclude=COMPONENT_LAYER_SUFFIXES))
    # 原先这里量的是 "PressableSurface 采用处数" —— **那个符号当时根本不存在**（全仓 0 命中）。
    # 量一个不存在的符号等于永远 0，会让人以为"有原语但没人用"，而事实是"没有原语"。
    yield ("ui.button_call_sites", "裸 Material3 按钮族调用点数（迁移分母，排除组件层）", "down",
           lambda: sum(grep_count(pat, exclude=COMPONENT_LAYER_SUFFIXES) for pat in RAW_BUTTON_PATTERNS))
    yield ("ui.migrated_button_calls", "已迁到按钮族的调用点数", "up",
           lambda: sum(grep_count(pat, exclude=COMPONENT_LAYER_SUFFIXES) for pat in MIGRATED_BUTTON_PATTERNS))
    # 方向是 info 而不是 down：它数的是**所有**内联 typography（按钮与正文都在内），
    # 而新增一块 UI 合法地会带上自己的字阶。把它当棘轮会**挡住正常开发** ——
    # 实测：B-2 的 loading/failed 文案与 A-3 的确认框正文一共加了 3 处，
    # 去掉建群对话框那颗按钮减了 1 处，净 +2。那是正常变化，不是欠债上涨。
    # 真正能判"按钮字号有没有收敛"的是下面两条（调用点数下降 + 迁移数上升）。
    yield ("ui.inline_typography", "[代理] 生产源码内联 typography 覆盖数（含正文，非按钮专属）", "info",
           lambda: grep_count(r"style\s*=\s*MaterialTheme\.typography\.(label|body|title)"))
    # 按压反馈：数**采用点**，不是"原语里有没有动画"。
    # 之前那条是假绿：4 处命中全在 PressableSurface.kt 自己体内（2 处还是 import 行），
    # 而它当时**一个调用点都没有** —— 指标说"有 4 处反馈"，界面上一处也没有。
    yield ("ui.press_feedback_adopted", "按压反馈的采用点数（排除原语自身）", "up",
           lambda: grep_count(r"(?<![A-Za-z])PressableSurface\(", exclude=COMPONENT_LAYER_SUFFIXES))
    yield ("fb.clickable_files", "含 clickable 的生产文件数（按压覆盖的分母）", "info",
           lambda: grep_files(r"clickable"))
    yield ("chat.separator", "消息时间/日期分隔实现处数", "up",
           lambda: grep_count(r"daysep|shouldShowSeparator|buildTimeline|TimelineSeparator"))
    yield ("conv.state_fields", "会话置顶/免打扰/草稿字段数（目标 3）", "up",
                      # 匹配 **任意以这三个词开头的属性名**：`pinnedConvIds` / `mutedConvIds` /
           # `draftByConv` 这类命名是自然写法，旧口径 `\b` 在 `pinned` 后就断了 ——
           # 于是「字段已经加上、量具仍报 0」，看起来像功能没做（量具缺口，不是代码问题）。
           lambda: grep_count(r"val (pinned|muted|draft)[A-Za-z]*\b"))
    yield ("ui.dead_button", "空实现按钮处数（白名单外）", "down", dead_button_count)
    # 读的是**棘轮文件**里的值，不是实时条数：实时条数只有 :cjkLiteralGate 的词法器能给。
    # 所以这条量的是"棘轮有没有被下调"——0 = 已资源化且基线已下调。别把它读成"源码里现在有几处"。
    yield ("cjk.ratchet_settings_home", "设置首页 CJK 棘轮值（0=已资源化且基线已下调）", "down",
           lambda: read_baseline_note_count("cjk-literal-baseline.txt", "SettingsHomeScreen.kt"))
    # componentSpecs 的棘轮。
    #
    # 为什么要有它：:designSpecProject 的 requiredTokens 校验**只在声明存在时**才有约束力 ——
    # 把一条规格删掉，门禁仍然是绿的（独立验证者实测：删掉 AppButtons 的规格，
    # --check 报"8 个组件规格"、EXIT=0）。于是"契约"可以靠**删除契约**来满足。
    # 这条指标把"规格条数"变成只增不减的数：删一条就变差。
    yield ("ci.component_specs", "组件级规格条数（只增不减：删规格=绕过契约）", "up",
           lambda: component_spec_count())
    # AGENTS.md 2.6 的判据：一个 UI 元素只许有一处定义。桌面壳有独占结构时应参数化共用，
    # 不该另写一份 —— 真身是 ConversationRow 两份，导致同一列表在两个壳里长两个样。
    yield ("ci.duplicate_composables", "跨壳重复的 Composable 处数（只许降）", "down",
           lambda: duplicate_composable_count())
    # ---- 发送时序：量的是「闸门式测试存在吗」，不是源码里的符号顺序 ----
    #
    # 为什么不量顺序：顺序代理会被重构骗过（把时序逻辑抽进一个 helper 之后，
    # 文件里 send 的首次出现就跑到 append 之前了 —— 代码是对的，指标却报 0），
    # 而且它会**反向塑造代码**：实现者为了让指标变绿去改函数命名。
    # 真正该量的是"有没有一条能判红的闸门测试"：闸门 = 假 send 挂起，
    # 放行前断言气泡已存在。没有闸门就只能断言终态，而终态区分不出乐观插入。
    yield ("test.gate_private", "闸门测试：私聊乐观插入", "up", lambda: test_has(
        "composeApp/src/jvmTest/kotlin/com/kapp/tidings/app/RelayControllerSendOptimisticTest.kt",
        r"CompletableDeferred"))
    yield ("test.gate_group", "闸门测试：群聊乐观插入", "up", lambda: test_has(
        "composeApp/src/jvmTest/kotlin/com/kapp/tidings/app/RelayControllerGroupSendOptimisticTest.kt",
        r"CompletableDeferred"))
    yield ("test.gate_file", "闸门测试：文件卡乐观插入", "up", lambda: test_has(
        "composeApp/src/jvmTest/kotlin/com/kapp/tidings/app/RelayControllerFileCardOptimisticTest.kt",
        r"CompletableDeferred"))
    yield ("test.fanout_concurrency", "并发峰值测试：群扇出（不赌耗时）", "up", lambda: test_has(
        "core/src/commonTest/kotlin/com/kapp/tidings/core/transport/GroupFanOutConcurrencyTest.kt",
        r"peak|live"))


def collect():
    rows = []
    for key, name, direction, fn in metrics():
        try:
            value = fn()
        except Exception as error:
            value = -1
            name = name + "（采集失败：" + type(error).__name__ + "）"
        rows.append({"key": key, "name": name, "direction": direction, "value": value})
    return rows


def baseline_path() -> str:
    return os.path.join(ROOT, "tools", "ux-scoreboard-baseline.json")


def load_baseline() -> dict:
    path = baseline_path()
    if not os.path.isfile(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def is_worse(direction: str, old: int, new: int) -> bool:
    if direction == "down":
        return new > old
    if direction == "up":
        return new < old
    return False


def print_table(rows, baseline) -> None:
    header = "%-46s %-5s %6s %6s  %s" % ("指标", "方向", "当前", "基线", "判定")
    print(header)
    print("-" * len(header))
    for row in rows:
        old = baseline.get(row["key"], {}).get("value")
        old_text = "-" if old is None else str(old)
        if old is None or row["value"] < 0:
            verdict = "（无基线）"
        elif is_worse(row["direction"], old, row["value"]):
            verdict = "变差"
        elif row["direction"] == "info":
            verdict = "记录"
        elif row["value"] == old:
            verdict = "持平"
        else:
            verdict = "变好"
        name = row["name"]
        if len(name) > 44:
            name = name[:43] + "…"
        print("%-46s %-5s %6d %6s  %s" % (name, row["direction"], row["value"], old_text, verdict))


SELF_TEST_CASES = [
    ("块注释里的空按钮不算", "/* onClick = { } */\n", DEAD_BUTTON_RE, False),
    ("字符串里的双斜线不截断后面的活代码",
     'Text("https://x") ; onClick = {}\n', DEAD_BUTTON_RE, True),
    ("行注释里的词不算", "val a = 1 // clearAllMessages\n", "clearAllMessages", False),
    ("字符串里的 UUID 模板看得见", 'text = "$id · 在线"\n', r'text = "\$id', True),
    ("字符串内容保留（emoji 断言）", 'Text("🤖 Agent Bot")\n', "🤖 Agent Bot", True),
    ("无空格的空按钮也算", "onClick = {},\n", DEAD_BUTTON_RE, True),
    ("字符串里的双斜线在空按钮之前也不影响", 'val s = "//" ; onClick = {}\n', DEAD_BUTTON_RE, True),
    ("多行块注释不吞掉后面的代码", "/* a\nb */\nonClick = {}\n", DEAD_BUTTON_RE, True),
    ("带占位注释的空按钮也算", "onClick = { /* File browser handle */ },\n", DEAD_BUTTON_RE, True),
    ("块注释在字符串之后正确开闭", 'val s = "x" /* c */ ; onClick = {}\n', DEAD_BUTTON_RE, True),
    # conv.state_fields 的口径：**前缀命名也要算**。旧口径 `\\b` 在 `pinned` 后就断了，
    # 于是「字段已加、量具报 0」—— 看起来像功能没做。这两例把这个坑钉住。
    ("置顶字段用前缀命名也要数到", 'val pinnedConvIds: List<String> = emptyList()\n',
     r"val (pinned|muted|draft)[A-Za-z]*\b", True),
    ("不相关字段不能被误数", 'val pinnedOther = 1\nval drafty = 2\n',
     r"val (pinned|muted|draft)[A-Za-z]*\b", True),
    ("完全无关的词不数", 'val unrelated = 1\n',
     r"val (pinned|muted|draft)[A-Za-z]*\b", False),
]


def self_test() -> int:
    failures = 0
    for label, source, pattern, expected in SELF_TEST_CASES:
        stripped = strip_comments(source)
        actual = re.search(pattern, stripped) is not None
        if actual != expected:
            failures += 1
            print("FAIL - " + label)
            print("  源码: " + source.replace("\n", "\\n"))
            print("  剥后: " + stripped.replace("\n", "\\n"))
            print("  期望 %s 实得 %s" % (expected, actual))
    if failures == 0:
        print("量具自检通过：%d 例" % len(SELF_TEST_CASES))
        return 0
    print("量具自检失败：%d/%d" % (failures, len(SELF_TEST_CASES)))
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description="UX 记分板")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--write-baseline", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--dead-buttons", action="store_true", help="逐处列出空实现按钮")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if args.dead_buttons:
        for key, number, whitelisted in dead_button_hits():
            mark = "（白名单）" if whitelisted else ""
            print("%s:%d %s" % (key, number, mark))
        return 0

    rows = collect()
    baseline = load_baseline()

    if args.write_baseline:
        payload = {
            "note": "UX 记分板基线（棘轮：只许变好）。重记必须有理由。proxy.* 只作趋势。",
            "values": {row["key"]: {"name": row["name"], "direction": row["direction"], "value": row["value"]} for row in rows},
        }
        with open(baseline_path(), "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
        print("已重记基线：" + rel(baseline_path()))
        return 0

    if args.json:
        print(json.dumps({"rows": rows, "baseline": baseline.get("values", {})}, ensure_ascii=False, indent=2))
    else:
        print_table(rows, baseline.get("values", {}))

    if args.check and baseline:
        regressions = [
            row for row in rows
            if row["key"] in baseline.get("values", {})
            and row["value"] >= 0
            and is_worse(row["direction"], baseline["values"][row["key"]]["value"], row["value"])
        ]
        if regressions:
            print("")
            print("变差：" + "、".join(row["name"] for row in regressions))
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
