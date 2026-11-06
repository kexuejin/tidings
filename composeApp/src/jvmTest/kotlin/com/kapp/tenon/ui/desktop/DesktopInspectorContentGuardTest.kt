// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.desktop

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WP-D3 的「只共享一半」防线（AGENTS §2.6）：inspector 是**外壳**，内容必须复用
 * feature 层既有组件（NodeDetailBody / FileDetailPane / AgentApprovalsSection /
 * AgentExecDeviceChecklist / peerHeaderLine）。ui/desktop 里不允许出现第二份
 * 内容实现 —— 装配帧（desk-conversations-wide / desk-agents-wide）与
 * componentSpecs 登记是它的组件级证据。
 *
 * 判据可区分对错：把 NodeDetail 的分组行/详情行抄进 DesktopInspectorPane 之类
 * 的壳文件（哪怕只是「看起来更快」），本测试立刻红。
 */
class DesktopInspectorContentGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /** inspector 容器与它的调用点只许引用 feature 层组件与既有 widget，不自带内容。 */
    @Test
    fun inspectorPaneIsShellOnly() {
        val pane = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopInspectorPane.kt")
        // 容器里只许有标题行 + 槽；feature 层内容组件的绘制原语（DetailRow/DetailCard/
        // NodeStatePill/FileCardUi 这类内容件）不许出现在壳文件里。
        for (contentSymbol in listOf("DetailRow(", "DetailCard(", "NodeStatePill(", "FileCardUi")) {
            assertFalse(
                pane.contains(contentSymbol),
                "DesktopInspectorPane 里出现了内容组件 [$contentSymbol] —— inspector 是外壳，" +
                    "内容必须引用 feature 层组件（AGENTS §2.6：只共享一半是最常见失败形态）",
            )
        }
        assertTrue(
            pane.contains("content: @Composable () -> Unit"),
            "DesktopInspectorPane 缺内容槽 —— 没有槽的容器会长成第二份内容",
        )
    }

    /**
     * 装配点引用的都是 feature 层组件：审批面（AgentApprovalsSection /
     * AgentExecDeviceChecklist）与会话信息（peerHeaderLine）。
     *
     * 回退方式：把装配换成壳内自绘的审批行 ⇒ 本条红。
     */
    @Test
    fun wideAssemblyReferencesFeatureComponents() {
        val shell = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopShell.kt")
        assertTrue(
            shell.contains("AgentApprovalsSection("),
            "Wide 工作区视图的审批面没有复用 AgentApprovalsSection —— A5 的常驻面必须是既有组件",
        )
        val convPane = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopConversationsPane.kt")
        assertTrue(
            convPane.contains("peerHeaderLine("),
            "Wide 会话视图的 inspector 信息行没有复用 peerHeaderLine —— 别再拼第二份地址行",
        )
    }

    /** T-G3：实时输出面板挂在 Agents inspector（不落库的那一半可见性）。 */
    @Test
    fun liveLogPanelIsMountedAndEphemeralEventsAreFiltered() {
        val shell = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopShell.kt")
        assertTrue(
            shell.contains("AgentLiveLogPanel("),
            "Agents inspector 没挂实时输出面板 —— delta 类「不落库」后就没有任何可见处了",
        )
        val widgets = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentSessionWidgets.kt")
        assertTrue(
            widgets.contains("shouldPersist("),
            "会话详情事件列表没有过滤瞬态事件 —— 活着可见、重启消失的两副面孔",
        )
        val domain = live("app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerAgentComponent.kt")
        assertTrue(
            domain.contains("shouldPersist("),
            "域层 saveEvent 没有过滤瞬态事件 —— 不变量 3「日志不存库」被打破的写入口",
        )
    }
}
