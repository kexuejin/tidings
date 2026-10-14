#!/usr/bin/env python3
"""把 desktopApp 的 DesktopShell.kt 单体按顶层声明切成 9 个文件。

纯机械拆分：除了 import 头复制与顶层 private 放宽为 internal，
声明文本一个字节都不改。拆分后 `git diff` 若出现第三类差异，
说明这个脚本被改坏了——先修脚本，不要接受那份 diff。

用法：
    python3 scripts/split-desktop-shell.py <单文件路径>

依赖：文件以 `package` + import 块开头，之后是顶层声明。
"""
import re
import sys

# 顶层声明 -> 目标文件。必须覆盖文件里的每一个顶层声明。
DECLARATIONS = {
    # 壳：视图枚举、动作表、壳本身
    "DesktopView": "DesktopShell.kt",
    "DesktopShellActions": "DesktopShell.kt",
    "DesktopShell": "DesktopShell.kt",
    # 菜单栏 / 标题栏 / 工具栏
    "DesktopMenuBar": "DesktopChrome.kt",
    "MenuGroup": "DesktopChrome.kt",
    "DesktopMenu": "DesktopChrome.kt",
    "MenuItem": "DesktopChrome.kt",
    "MenuLabel": "DesktopChrome.kt",
    "DesktopTitleBar": "DesktopChrome.kt",
    "DesktopToolbar": "DesktopChrome.kt",
    "ViewChip": "DesktopChrome.kt",
    "ToolbarButton": "DesktopChrome.kt",
    # 会话侧栏 / 聊天区
    "ConversationsPane": "DesktopConversationsPane.kt",
    "ChatPane": "DesktopConversationsPane.kt",
    "DesktopChatHeader": "DesktopConversationsPane.kt",
    "peerHeaderLine": "DesktopConversationsPane.kt",
    "HeadChip": "DesktopConversationsPane.kt",
    "ConversationSidebar": "DesktopConversationsPane.kt",
    "ConversationRow": "DesktopConversationsPane.kt",
    # 文件
    "FileRow": "DesktopFilesPane.kt",
    "FilesPane": "DesktopFilesPane.kt",
    "FileRowCard": "DesktopFilesPane.kt",
    "FileCardUi.stateLabel": "DesktopFilesPane.kt",
    # 节点
    "NodesPane": "DesktopNodesPane.kt",
    "SelfNodeIdStrip": "DesktopNodesPane.kt",
    # 导航
    "NavigationRail": "DesktopNavigation.kt",
    "RailButton": "DesktopNavigation.kt",
    "CompactNavigationBar": "DesktopNavigation.kt",
    "desktopViewLabel": "DesktopNavigation.kt",
    "desktopViewIcon": "DesktopNavigation.kt",
    "VerticalDividerLine": "DesktopNavigation.kt",
    # 状态栏
    "DesktopStatusBar": "DesktopStatusBar.kt",
    "StatusBarAction": "DesktopStatusBar.kt",
    "phaseLabel": "DesktopStatusBar.kt",
    "controlPlaneLabel": "DesktopStatusBar.kt",
    # 输入区
    "DesktopComposer": "DesktopComposer.kt",
    "Button2": "DesktopComposer.kt",
    "EMOJI_PALETTE": "DesktopComposer.kt",
    "EmojiPopup": "DesktopComposer.kt",
    "ComposeByteMode.hint": "DesktopComposer.kt",
}

# 顶层声明头的匹配：允许注解行、修饰符、扩展接收者、嵌套名字。
DECL_RE = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:(?:private|internal|public|expect|actual|inline|value|const|lateinit|open|abstract|sealed|data|enum|annotation|companion)\s+)*"
    r"(?:fun|val|var|class|object|interface|typealias)\s+"
    r"(?:<[^>]*>\s*)?"
    r"(?:[\w.<>]+(?:\s*\.\s*[\w.<>]+)*)\s*\("
    r"|^(?:(?:private|internal|public|expect|actual|const|lateinit|open|abstract|sealed|data|enum|annotation|companion)\s+)*"
    r"(?:fun|val|var|class|object|interface|typealias|enum class|annotation class|value class)\s+"
    r"(?:<[^>]*>\s*)?"
    r"(?:[\w.]+)"
)

PRIVATE_TOP_LEVEL_RE = re.compile(r"^private (fun|val|var|const val|class|object|enum class|interface) ")


def split_header(lines):
    """package + import 区域（含其后的空行）的长度。"""
    i = 0
    while i < len(lines) and not lines[i].startswith("import "):
        i += 1
    while i < len(lines) and (lines[i].startswith("import ") or lines[i].strip() == ""):
        i += 1
    return i


def _next_starts_decl(lines, i):
    """从 i+1 起找下一条非空行，判断它是不是新的顶层声明头。

    必须跳过 KDoc、行注释和注解：声明前面的
    「`/** ... */` + `@Composable`」属于**下一条**声明，
    直接看下一行会把这段注释误判成当前块的结尾。
    """
    j = i + 1
    n = len(lines)
    while j < n:
        s = lines[j].strip()
        if s == "" or s.startswith("//") or s.startswith("*") or s.startswith("/*"):
            if s.startswith("/*") and not s.endswith("*/"):
                # 多行 KDoc：一直跳到闭合为止
                while j < n and "*/" not in lines[j]:
                    j += 1
            j += 1
            continue
        if s.startswith("@"):
            # 注解可能带跨行参数，括号配平后再继续找
            while j < n:
                depth = 0
                for ch in lines[j]:
                    if ch in "([{":
                        depth += 1
                    elif ch in ")]}":
                        depth -= 1
                j += 1
                if depth <= 0:
                    break
            continue
        return DECL_RE.match(lines[j]) is not None
    return False


def top_level_blocks(lines, start):
    """用括号深度切出顶层声明块，返回 [(起始下标, 结束下标)]（含尾）。

    关键规则：一个块只有在「已经见过声明头」且「括号深度回到 0」时才结束。
    否则 KDoc / 注解会被当成独立块切出去，声明本身就成了没有名字的孤儿。
    """
    depth = 0
    blocks = []
    begin = None
    seen_decl = False
    in_block_comment = False
    i = start
    n = len(lines)
    while i < n:
        if lines[i].strip() == "" and depth == 0 and begin is None:
            i += 1
            continue
        if begin is None:
            begin = i
            seen_decl = False
        if DECL_RE.match(lines[i]) is not None:
            seen_decl = True
        j = 0
        line = lines[i]
        while j < len(line):
            if in_block_comment:
                if line.startswith("*/", j):
                    in_block_comment = False
                    j += 2
                    continue
                j += 1
                continue
            if line.startswith("//", j):
                break
            if line.startswith("/*", j):
                in_block_comment = True
                j += 2
                continue
            c = line[j]
            if c == '"':
                j += 1
                while j < len(line):
                    if line[j] == "\\":
                        j += 2
                        continue
                    if line[j] == '"':
                        j += 1
                        break
                    j += 1
                continue
            if c in "([{":
                depth += 1
            elif c in ")]}":
                depth -= 1
            j += 1
        # 只有「下一条非空行是新的顶层声明」或「到文件末尾」才收块。
        # 不能只看深度回 0：`private fun f(): String =` 这种表达式体的头一行
        # 深度就是 0，下一行 `    if (...) ...` 才真正开始函数体。
        if seen_decl and depth == 0 and _next_starts_decl(lines, i):
            end = i
            while end > begin and lines[end].strip() == "":
                end -= 1
            blocks.append((begin, end))
            begin = None
            seen_decl = False
            i = end + 1
            continue
        i += 1
    if begin is not None:
        end = n - 1
        while end > begin and lines[end].strip() == "":
            end -= 1
        blocks.append((begin, end))
    return blocks


def decl_name(block_lines):
    """取声明名（不含修饰符），用于查映射表。"""
    for line in block_lines:
        if line.startswith((" ", "\t")):
            continue
        m = re.search(
            r"\b(?:fun|val|var|class|object|interface|typealias)\s+"
            r"(?:<[^>]*>\s*)?"
            r"([\w.]+(?:<[^>]*>)?)",
            line,
        )
        if m:
            return m.group(1)
    return None


def main():
    path = sys.argv[1]
    out_dir = path.rsplit("/", 1)[0]
    lines = open(path).read().split("\n")
    header_len = split_header(lines)
    header = "\n".join(lines[:header_len]).rstrip("\n")

    blocks = top_level_blocks(lines, header_len)
    grouped = {}
    unknown = []
    for a, b in blocks:
        body = lines[a : b + 1]
        if not any(line.strip() for line in body):
            continue
        name = decl_name(body)
        target = DECLARATIONS.get(name)
        if target is None:
            unknown.append((a + 1, b, name, body[:3]))
            continue
        widened = []
        for line in body:
            m = PRIVATE_TOP_LEVEL_RE.match(line)
            if m:
                line = "internal " + line[len("private "):]
            widened.append(line)
        grouped.setdefault(target, []).append("\n".join(widened))

    if unknown:
        for a, b, name, head in unknown:
            print("UNMAPPED", a, b, name, head, file=sys.stderr)
        sys.exit("映射表漏了声明，拒绝写出（宁可什么都不改，也不要悄悄丢代码）")

    total = 0
    for target in sorted(grouped):
        text = header + "\n\n" + "\n\n".join(grouped[target]) + "\n"
        out = f"{out_dir}/{target}"
        open(out, "w").write(text)
        n = text.count("\n")
        total += n
        print(f"{target:34s} {n:5d} 行  {len(grouped[target]):2d} 声明")
    print(f"合计 {total} 行 / {sum(len(v) for v in grouped.values())} 声明（原文件 {len(lines)} 行）")


if __name__ == "__main__":
    main()