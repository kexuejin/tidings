// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.ui.desktop

import androidx.compose.ui.input.key.Key

/**
 * 桌面**系统菜单栏**与键盘共同使用的那些动作。
 *
 * 这个枚举之所以存在，是因为菜单栏曾经**只印字不接线**：`⌘N` / `⌘O` / `⌘⇧E` / `⌘⇧C` /
 * `⌘R` / `⌘,` / `⌘Q` / `⌥⌘D` 全都印在菜单上，但整个 desktopApp 里除了输入框的回车发送，
 * 没有任何一处处理修饰键。用户照着菜单按下去什么都不会发生 —— 这比不印快捷键更糟。
 *
 * 现在每个组合都在这里有一条真实分支。**印出来的那一份**现在由
 * `desktopApp` 窗口里的 Compose `MenuBar` 用 `KeyShortcut` 声明（macOS 进屏幕顶部、
 * Win/Linux 进窗口菜单），枚举里那个手写的 `display` 字符串随之删除：让系统去印，
 * 而不是让代码再维护一份可能与解析器分叉的副本。
 * 两者是否一致由 `DesktopShortcutsTest` 钉住 —— 菜单印的组合必须解析得出同一个动作。
 */
public enum class DesktopShortcut {
    NewConversation,
    ImportFiles,
    OpenAgents,
    CopyNodeId,
    ToggleTheme,
    RefreshNodes,
    OpenSettings,
    Quit,
    ExportDiagnostics,
    ViewConversations,
    ViewFiles,
    ViewCabinet,
    ViewNodes,
    ViewAgents,
    ScreenCapture,
}

/**
 * 「导入文件」能不能按（缺陷③）—— **唯一判据**。
 *
 * 导入文件的实现是「把文件发给**当前选中的会话**」（`controller.pickAndSendFiles(peerId)`），
 * 没有选中会话时按下去静默无效。所以两处入口共用这一个函数：
 *   · 系统菜单栏那一项 `enabled = canImportFiles(host.selectedPeerId)`；
 *   · 工具栏那颗按钮 `enabled = canImportFiles(selectedPeerId)`。
 * 两处同一个函数，才不会出现"菜单灰着、按钮亮着"这种自相矛盾。
 *
 * 空白 id 也算没有 —— 与 `PeerWindowRegistry.open` 对空白 id 的判据同一条。
 */
public fun canImportFiles(selectedPeerId: String?): Boolean = !selectedPeerId.isNullOrBlank()

/**
 * 把一次按键解析成一个动作；不认识就返回 null，调用方让事件继续往下传。
 *
 * [key] 用小写键名（`"n"`、`"o"`、`"e"`、`"c"`、`"d"`、`"r"`、`"q"`、`","`、`"1"`…`"5"`），
 * 好让这个判断不依赖 Compose 的 `Key`，能在没有 Compose 测试设施的单元测试里直接跑。
 *
 * [isPrimaryModifier] 是平台主修饰键（macOS 的 ⌘，其余平台的 Ctrl）。没有它一律不认 ——
 * 否则一个裸 `n` 在输入框里打字就会顺手新建一个会话。
 *
 * `⇧` 与 `⌥` 互斥：两个都按下不匹配任何分支。复制本机 ID 特意要求 `⇧`，
 * 这样 `⌘C` 仍然是系统复制、能透传给输入框。
 */
public fun desktopShortcutFor(
    key: String,
    isPrimaryModifier: Boolean,
    isShift: Boolean = false,
    isAlt: Boolean = false,
): DesktopShortcut? {
    if (!isPrimaryModifier) return null
    if (isShift && isAlt) return null
    if (isShift) {
        return when (key) {
            "e" -> DesktopShortcut.OpenAgents
            "c" -> DesktopShortcut.CopyNodeId
            "d" -> DesktopShortcut.ToggleTheme
            "a" -> DesktopShortcut.ScreenCapture
            else -> null
        }
    }
    if (isAlt) {
        return when (key) {
            "d" -> DesktopShortcut.ExportDiagnostics
            else -> null
        }
    }
    return when (key) {
        "n" -> DesktopShortcut.NewConversation
        "o" -> DesktopShortcut.ImportFiles
        "r" -> DesktopShortcut.RefreshNodes
        "," -> DesktopShortcut.OpenSettings
        "q" -> DesktopShortcut.Quit
        "1" -> DesktopShortcut.ViewConversations
        "2" -> DesktopShortcut.ViewFiles
        "3" -> DesktopShortcut.ViewCabinet
        "4" -> DesktopShortcut.ViewNodes
        "5" -> DesktopShortcut.ViewAgents
        else -> null
    }
}

/**
 * Compose 的 `Key` 到 [desktopShortcutFor] 认的小写键名的桥。
 *
 * 不在表里的键返回 null —— 宁可什么都不做，也不要猜。
 */
public fun desktopShortcutKeySpec(key: Key): String? = when (key) {
    Key.N -> "n"
    Key.O -> "o"
    Key.E -> "e"
    Key.C -> "c"
    Key.D -> "d"
    Key.R -> "r"
    Key.Q -> "q"
    Key.Comma -> ","
    Key.One -> "1"
    Key.Two -> "2"
    Key.Three -> "3"
    Key.Four -> "4"
    Key.Five -> "5"
    else -> null
}
