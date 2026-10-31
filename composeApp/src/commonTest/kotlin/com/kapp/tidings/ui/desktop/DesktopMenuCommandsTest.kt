// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 系统菜单栏那 6 条命令与键盘处理必须一一对应（2026-10-18 的菜单栏改造）。
 *
 * ## 为什么这几条要单测
 *
 * 菜单栏从「窗口内自绘 16 条」换成「系统 MenuBar 6 条」之后，**印出来的组合键**
 * （`KeyShortcut`，由 `desktopApp` 的 `DesktopSystemMenuBar` 声明）与**解析器**
 * （[desktopShortcutFor]，由 `DesktopShell` 的键盘处理使用）成了两份代码。
 * 它们一旦分叉，症状是「菜单上印着 ⌘N，按下去什么也不发生」—— 这正是这个枚举
 * 当初存在的原因（见 `DesktopShortcuts.kt` 的 KDoc）。所以这里把 6 条钉住：
 * 菜单印的每一个组合都必须解析得出同一个动作。
 *
 * ## 另外两个缺陷的证据
 *
 * · 缺陷③（导入文件没选中会话时静默无效）：判据收敛到 [canImportFiles] 一处，
 *   菜单项与工具栏按钮共用；下面的测试钉住它的三态。
 * · 缺陷②（新建会话两条入口两种行为）：结构性证据是 `DesktopShell` 里那个唯一的
 *   `newConversation` lambda（快捷键与工具栏都调它，系统菜单栏在窗口层用
 *   `requestNodesView()` + 同一个宿主动作表达同一条路）；这里钉住的是它的快捷键半边
 *   —— ⌘N 必须解析成 `NewConversation`，而不是被别的分支先截走。
 */
class DesktopMenuCommandsTest {

    /** 主修饰键按下的解析（macOS ⌘ / 其它平台 Ctrl 在 [desktopShortcutFor] 里是同一个入参）。 */
    private fun primary(key: String, shift: Boolean = false, alt: Boolean = false) =
        desktopShortcutFor(key = key, isPrimaryModifier = true, isShift = shift, isAlt = alt)

    @Test
    fun theSixSystemMenuItemsEachResolveToARealHandler() {
        assertEquals(DesktopShortcut.NewConversation, primary("n"), "新建会话")
        assertEquals(DesktopShortcut.ImportFiles, primary("o"), "导入文件")
        assertEquals(DesktopShortcut.OpenSettings, primary(","), "设置")
        assertEquals(DesktopShortcut.ScreenCapture, primary("a", shift = true), "截图")
        assertEquals(DesktopShortcut.ExportDiagnostics, primary("d", alt = true), "诊断包")
        assertEquals(DesktopShortcut.Quit, primary("q"), "退出")
    }

    @Test
    fun theSixItemsAreSixDistinctActions() {
        // 六条挤成五条（或两条同动作）时，菜单上会出现两个印着同一个组合的条目 ——
        // 用户按下去只能到其中一个。
        val actions = listOf(
            primary("n"),
            primary("o"),
            primary(","),
            primary("a", shift = true),
            primary("d", alt = true),
            primary("q"),
        )
        assertEquals(6, actions.toSet().size, "6 条菜单项必须是 6 个不同动作：$actions")
    }

    @Test
    fun noMenuShortcutFiresWithoutThePrimaryModifier() {
        // 裸键绝不能触发：否则在输入框里打 n / o / , 就会顺手新建会话或导入文件。
        listOf("n", "o", ",", "a", "d", "q", "f", "k").forEach { key ->
            assertNull(
                desktopShortcutFor(key = key, isPrimaryModifier = false, isShift = true, isAlt = true),
                "$key 不该在没有主修饰键时命中任何命令",
            )
        }
    }

    @Test
    fun findAndSearchShortcutsResolveToFocusSearch() {
        assertEquals(DesktopShortcut.FocusSearch, primary("f"), "⌘F 聚焦搜索")
        assertEquals(DesktopShortcut.FocusSearch, primary("k"), "⌘K 聚焦搜索")
        assertEquals("f", desktopShortcutKeySpec(Key.F))
        assertEquals("k", desktopShortcutKeySpec(Key.K))
    }

    @Test
    fun importIsDisabledUntilAConversationIsSelected() {
        // 缺陷③：导入文件的实现是「把文件发给当前选中的会话」，没选中时按下去静默无效。
        assertFalse(canImportFiles(null), "没选中会话 = 不能导入")
        assertFalse(canImportFiles(""), "空串不是会话 id")
        assertFalse(canImportFiles("   "), "空白也不是")
        assertTrue(canImportFiles("peer-1"), "选中了就能按")
    }

    // ── WP-D4（N1/N2）：菜单五组的「印出的组合必须解析得出同一动作」全比对 ──

    @Test
    fun everyMenuKeyCombinationResolvesToItsOwnAction() {
        // 应用组（设置/诊断包/退出）+ 文件组（新建/导入）+ 编辑组（⌘⇧C/⌘⇧D）
        // + 视图组（⌘1–5/⌘R/⌘F/⌘⇧A）+ 窗口组（⌘⇧N/⌘W）。
        assertEquals(DesktopShortcut.OpenSettings, primary(","), "设置 ⌘,")
        assertEquals(DesktopShortcut.ExportDiagnostics, primary("d", alt = true), "诊断包 ⌥⌘D")
        assertEquals(DesktopShortcut.Quit, primary("q"), "退出 ⌘Q")
        assertEquals(DesktopShortcut.NewConversation, primary("n"), "新建会话 ⌘N")
        assertEquals(DesktopShortcut.ImportFiles, primary("o"), "导入文件 ⌘O")
        assertEquals(DesktopShortcut.CopyNodeId, primary("c", shift = true), "复制本机 ID ⌘⇧C")
        assertEquals(DesktopShortcut.ToggleTheme, primary("d", shift = true), "切换深浅色 ⌘⇧D")
        assertEquals(DesktopShortcut.ViewConversations, primary("1"), "会话 ⌘1")
        assertEquals(DesktopShortcut.ViewFiles, primary("2"), "文件 ⌘2")
        assertEquals(DesktopShortcut.ViewCabinet, primary("3"), "文件柜 ⌘3")
        assertEquals(DesktopShortcut.ViewNodes, primary("4"), "节点 ⌘4")
        assertEquals(DesktopShortcut.ViewAgents, primary("5"), "工作区 ⌘5")
        assertEquals(DesktopShortcut.RefreshNodes, primary("r"), "刷新节点 ⌘R")
        assertEquals(DesktopShortcut.FocusSearch, primary("f"), "搜索 ⌘F")
        assertEquals(DesktopShortcut.ScreenCapture, primary("a", shift = true), "截屏协助 ⌘⇧A")
        assertEquals(DesktopShortcut.NewWindow, primary("n", shift = true), "新窗口 ⌘⇧N")
        assertEquals(DesktopShortcut.CloseWindow, primary("w"), "关闭窗口 ⌘W")
        assertEquals("w", desktopShortcutKeySpec(Key.W), "KeySpec 必须认得 W")
        assertEquals("n", desktopShortcutKeySpec(Key.N))
    }

    @Test
    fun menuCombinationsAreAllDistinctActions() {
        // 菜单上两个条目印同一个组合 = 用户按下去只能到其中一个（与六条版同一条判据的扩展）。
        val actions = listOf(
            primary(","), primary("d", alt = true), primary("q"),
            primary("n"), primary("o"),
            primary("c", shift = true), primary("d", shift = true),
            primary("1"), primary("2"), primary("3"), primary("4"), primary("5"),
            primary("r"), primary("f"), primary("a", shift = true),
            primary("n", shift = true), primary("w"),
        )
        assertEquals(17, actions.toSet().size, "17 个组合必须解析成 17 个不同动作：$actions")
    }

    // ── 反向验证的判据钉（每个新快捷键一条：摘掉对应分支 ⇒ 这里的断言红） ──

    @Test
    fun cmdWResolvesToCloseWindowOnlyViaTheWBranch() {
        // 反向验证锚点：摘掉解析器里的 "w" -> CloseWindow 分支 ⇒ 本断言红。
        assertEquals(DesktopShortcut.CloseWindow, primary("w"))
        // 不搭 Shift：⌘⇧W 不是任何命令（复制族才是 ⇧ 那一档）。
        assertNull(primary("w", shift = true), "⌘⇧W 不该命中任何命令")
    }

    @Test
    fun cmdShiftNResolvesToNewWindowOnlyViaTheShiftNBranch() {
        // 反向验证锚点：摘掉 isShift 分支里的 "n" -> NewWindow ⇒ 本断言红。
        // 它必须压过裸 ⌘N 的分支（Shift 先判）—— 否则「新窗口」会变成「新建会话」。
        assertEquals(DesktopShortcut.NewWindow, primary("n", shift = true))
    }

    @Test
    fun listOpenKeyJudgementCoversEnterAndCmdDown() {
        // 反向验证锚点：摘掉 isListOpenKey 的 enter / ⌘down 两支 ⇒ 本断言红。
        assertTrue(isListOpenKey(key = "enter", isPrimaryModifier = false), "裸 Enter 打开选中项")
        assertTrue(isListOpenKey(key = "down", isPrimaryModifier = true), "⌘↓ 打开选中项")
        assertFalse(isListOpenKey(key = "down", isPrimaryModifier = false), "裸 ↓ 是移动不是打开")
        assertFalse(isListOpenKey(key = "up", isPrimaryModifier = true), "⌘↑ 不是打开")
        // 打开目标：没有选中 = 列表头；选中在列 = 它自己；空列表 = null（不消费）。
        assertEquals("a", nextSelectionToOpen(null, listOf("a", "b")))
        assertEquals("b", nextSelectionToOpen("b", listOf("a", "b")))
        assertNull(nextSelectionToOpen("b", emptyList()))
    }
}
