// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.desktop

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 会话行的**桌面密度档**：一条实现、两个密度（AGENTS §2.6 参数化，不复制第二份行）。
 *
 * ## 守的是什么
 *
 * 原型给桌面窗画了一档更密的列表（`prototype/relay.html` 的 `.winset .li`：
 * `min-height:40px;padding:5px 8px;gap:8px`，标题 12px），但 `ConversationRow` 此前只有
 * 手机密度（52dp 行高 / 32dp 头像 / 9×7 内边距），桌面侧栏 300px 宽里每行占高与手机
 * 完全一致 —— 「拉大的手机应用」观感的第一来源（桌面 UI 整改方案 L2）。
 *
 * 密度走**参数化**（`dense: Boolean = false`），手机不传即手机帧零变化；桌面
 * `ConversationSidebar` 是唯一传 `dense = true` 的生产调用方。密度经
 * `ConversationListPanel` 透传 —— 容器也是共享组件，绕过它传给行等于把桌面侧的
 * 分叉重新长回容器层。
 *
 * ## 为什么只能源码守卫
 *
 * 密度分支是 Composable 内部的取值分派，渲染证据由截图帧承担（双端 × 双密度四帧）；
 * 「桌面调用点真的传了 dense」这条**接线**渲染不出来，与 `DesktopComposerFocusGuardTest`
 * 同一取舍。
 */
class ConversationRowDensityGuardTest {

    private val rowPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/ConversationRow.kt"
    private val panelPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/ConversationListPanel.kt"
    private val sidebarPath = "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopConversationWidgets.kt"

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /**
     * 行组件必须真的有 dense 分派，且三个密度敏感处都要落在分支里：
     * 行高、头像、内边距。任何一处漏接，桌面侧栏的行就长回手机密度。
     *
     * 回退方式：把任一分支的 \`if (dense)\` 改回单一取值 ⇒ 本条红。
     */
    @Test
    fun rowBranchesRowHeightAvatarAndPaddingOnDensity() {
        val row = live(rowPath)
        assertTrue(
            row.contains("dense: Boolean = false"),
            "ConversationRow 缺 dense 参数（状态提升，参数接原语；手机默认 false = 手机帧零变化）",
        )
        for (needle in listOf("if (dense)", "denseRowMinHeight", "denseAvatar")) {
            assertTrue(
                needle in row,
                "行密度分支缺 [$needle] —— 行高/头像/内边距三处必须都落在 dense 分派里",
            )
        }
    }

    /**
     * 密度必须**经容器透传**，不许桌面绕过共享容器直接把行重排一遍。
     *
     * 回退方式：删掉 [ConversationListPanel] 的 dense 形参或透传实参 ⇒ 本条红。
     */
    @Test
    fun panelForwardsDensityToTheRow() {
        val panel = live(panelPath)
        assertTrue(
            panel.contains("dense: Boolean = false"),
            "ConversationListPanel 缺 dense 形参 —— 密度是行的参数，容器必须把它运下去",
        )
        assertTrue(
            panel.contains("dense = dense"),
            "容器收到 dense 却没透传给行 —— 分叉从容器层重新长回来了",
        )
    }

    /**
     * 桌面侧栏的密度**默认开**（桌面壳层的决定，生产调用方不必逐处传），
     * 并经共享面板透传到行。
     *
     * 回退方式：把 ConversationSidebar 的默认值改回 false，或摘掉透传实参 ⇒ 本条红。
     */
    @Test
    fun desktopSidebarDefaultsToDenseAndForwardsIt() {
        val sidebar = live(sidebarPath)
        assertTrue(
            sidebar.contains("dense: Boolean = true"),
            "桌面侧栏密度默认值不是 true —— 桌面壳层没开密度档，WP-D2 的接线断在最后一步",
        )
        assertTrue(
            sidebar.contains("ConversationListPanel(") && sidebar.contains("dense = dense"),
            "桌面侧栏收到 dense 却没透传给共享面板 —— 分叉从壳层重新长回来了",
        )
    }

    /**
     * 手机壳**不传** dense（默认值让信息最少的一侧直接调对）。
     *
     * 回退方式：给 ConversationsScreen 的面板调用加 dense = true ⇒ 手机帧变密，本条红。
     */
    @Test
    fun phoneShellDoesNotPassDensity() {
        val panelCall = GuardSourceScan.bracedBlockAfter(
            live("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/conversations/ConversationsScreen.kt"),
            "ConversationListPanel(",
        )
        assertFalse(
            panelCall.contains("dense"),
            "手机壳在给会话行传密度 —— 手机帧零变化这条红线被这处实参破坏",
        )
    }

    /**
     * 240.dp 字面量收编进 [DesktopMetrics]（整改方案 L6 顺手清）：Medium 侧栏宽度
     * 与 inspector 最小宽度都是壳的度量，必须住在同一个对象里，不许再写字面量。
     *
     * 回退方式：把 Medium 分支的实参改回 240.dp 字面量 ⇒ 本条红。
     */
    @Test
    fun mediumSidebarWidthLivesInDesktopMetrics() {
        val pane = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopConversationsPane.kt")
        assertFalse(
            pane.contains("width(240.dp)"),
            "Medium 侧栏还是 240.dp 字面量 —— 欠债没清（DesktopMetrics.MEDIUM_SIDEBAR_PX）",
        )
        val metrics = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopLayout.kt")
        assertTrue(
            metrics.contains("MEDIUM_SIDEBAR_PX") && metrics.contains("INSPECTOR_MIN_PX"),
            "DesktopMetrics 缺 MEDIUM_SIDEBAR_PX / INSPECTOR_MIN_PX —— WP-D3 要消费的最小宽度常量没预备",
        )
    }
}
