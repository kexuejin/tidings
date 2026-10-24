// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「本会话免审」三参处置（`allowSession`）全链接线的源码级守卫。
 *
 * ## 为什么必须有这条守卫
 *
 * 这条回调要穿过**五跳**：组合根（手机 `App.kt` / 桌面 `DesktopShell.kt`）→
 * `AgentConsoleScreen` → 三个子视图（会话详情 / 工作区详情 / 工作区看板）→
 * `AgentApprovalsSection` → 按钮。上一轮补到第三跳就停了：`AgentConsoleScreen` 的参数、
 * 三个视图的形参、`AgentApprovalsSection` 的按钮全都写好了，**只有组合根那一跳没接** ——
 * 于是免审按钮在真机上一次都不出现，而所有纯函数测试全绿（它们测不到接线）。
 *
 * 类型系统在这里帮不上忙：多出来的参数有默认值 `null`，"不传"与"传了 null"编译结果
 * 一模一样。只能读源码问一句「这一跳到底传了没有」。
 *
 * ## 为什么按调用块取，而不是全文 contains
 *
 * "文件里出现过 `onRespondChoice`"区分不出对错：参数声明、KDoc、另一处调用都会命中。
 * 要问的是**这个调用**里有没有那一行（见 [GuardSourceScan] 的两条纪律）。
 * 剥注释是必须的 —— 这些文件的注释里正当地重复着同一串名字。
 */
class AgentApprovalWiringGuardTest {

    private val app = "composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt"
    private val desktopShell = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt"
    private val consoleScreen =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentConsoleScreen.kt"
    private val sessionDetail =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentSessionDetail.kt"
    private val workspaceDetail =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/WorkspaceDetailView.kt"
    private val workspaceBoard =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentWorkspaceBoard.kt"
    private val approvalCards =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentApprovalCards.kt"
    private val sessionWidgets =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentSessionWidgets.kt"
    private val domainWorkspaceUnits =
        "app/src/commonMain/kotlin/com/kapp/tidings/domain/InboxWorkspaceUnits.kt"

    /** 一跳 = 「在 file 的 marker 调用里必须看见 needle」，缺一即红。 */
    private data class Hop(val file: String, val marker: String, val needle: String, val why: String)

    private val hops: List<Hop> = listOf(
        Hop(
            app, "AgentConsoleScreen(", "onRespondChoice = controller::respondApproval",
            "手机组合根必须把 controller 的三参 respondApproval 接进 AgentConsoleScreen —— " +
                "上一轮就是漏了这一跳，免审按钮永远不出现。",
        ),
        Hop(
            desktopShell, "AgentConsoleScreen(", "onRespondChoice = controller::respondApproval",
            "桌面壳同样要接三参：只接两参的壳上免审入口永久缺失。",
        ),
        Hop(
            consoleScreen, "SessionDetailView(", "onRespondChoice = onRespondChoice",
            "AgentConsoleScreen 要把 onRespondChoice 穿进会话详情（否则二级页不画免审按钮）。",
        ),
        Hop(
            consoleScreen, "WorkspaceDetailView(", "onRespondChoice = onRespondChoice",
            "AgentConsoleScreen 要把 onRespondChoice 穿进工作区详情。",
        ),
        Hop(
            consoleScreen, "WorkspaceBoardView(", "onRespondChoice = onRespondChoice",
            "AgentConsoleScreen 要把 onRespondChoice 穿进工作区看板。",
        ),
        Hop(
            sessionDetail, "AgentApprovalsSection(", "onRespondChoice = onRespondChoice",
            "会话详情要把三参处置交给审批段（只传两参 ⇒ 按钮不画）。",
        ),
        Hop(
            workspaceDetail, "AgentApprovalsSection(", "onRespondChoice = onRespondChoice",
            "工作区详情要把三参处置交给审批段。",
        ),
        Hop(
            workspaceBoard, "AgentApprovalsSection(", "onRespondChoice = onRespondChoice",
            "看板要把三参处置交给审批段。",
        ),
    )

    @Test
    fun everyHopWiresTheThreeParamRespond() {
        val missing = missingHops(hops) { GuardSourceScan.read(it) }
        assertTrue(missing.isEmpty(), missing.joinToString("；") { hop -> "断开的一跳：" + hop.why })
    }

    /**
     * 守卫必须**能区分对错**：把任意一跳的那一行删掉，都要报出来。
     *
     * 做法是在内存里改文本（不碰工作树）—— 不为了验证一条守卫去改 `App.kt` 这种热点文件。
     */
    @Test
    fun guardDetectsEachMissingHop() {
        hops.forEach { hop ->
            val broken = missingHops(listOf(hop)) { file ->
                if (file == hop.file) GuardSourceScan.read(file).replace(hop.needle, "") else GuardSourceScan.read(file)
            }
            assertEquals(
                listOf(hop.file),
                broken.map { it.file },
                "把这一跳接线的代码删掉后守卫却没报 —— 它守不住任何东西：" + hop.why,
            )
        }
    }

    /** 按钮本身还有两道闸：三参必须能传进 section 的按钮，L3 必须先过确认卡。 */
    @Test
    fun sectionDrawsTheSessionAllowButtonAndKeepsTheL3Gate() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(approvalCards))
        assertTrue(
            text.contains("onRespondChoice(approval.approvalId, true, allowSession)"),
            "「本会话免审」/批准按钮必须把 allowSession 原样交给三参回调，" +
                "否则免审在按钮这一端就被降级成普通批准。",
        )
        assertTrue(
            text.contains("if (shouldConfirmApproval(riskLevel))"),
            "L3 的确认卡闸门不能丢：免审不能成为绕过确认的旁路。",
        )
    }

    /**
     * 「待批」这个数只有一个入口：五处消费点都必须调 `pendingApprovalCount(`。
     *
     * ## 为什么这也要守
     *
     * 同一条 issue 的另一半是「页头说 2 项、分段头说 3 项」—— 因为过期这条规则只在
     * 其中一处落地。修好之后，任何一处再退回手写 `status == "pending"` 都会重新长出
     * 第二把尺子，而单测抓不到（它们测的是那个函数本身，不是谁在调它）。
     *
     * 反向验证：把任意一处文件里的 `pendingApprovalCount(` 删掉（内存里改），本测试变红。
     */
    @Test
    fun everyPendingCountGoesThroughTheOneRuler() {
        val sites = listOf(
            workspaceBoard to "看板页头 / 分段头",
            workspaceDetail to "工作区详情（会话卡角标）",
            sessionDetail to "会话切换条",
            sessionWidgets to "会话选择器",
            domainWorkspaceUnits to "领域派生（工作区行 / 分段 / 尾巴那行）",
        )
        val missing = sites.filter { (file, why) ->
            !GuardSourceScan.stripComments(GuardSourceScan.read(file)).contains("pendingApprovalCount(")
        }
        assertTrue(
            missing.isEmpty(),
            missing.joinToString("；") { (_, why) -> "还在自己数待批（两把尺子）：" + why },
        )
    }

    private fun missingHops(hops: List<Hop>, read: (String) -> String): List<Hop> =
        hops.filter { hop ->
            val text = GuardSourceScan.stripComments(read(hop.file))
            val at = text.indexOf(hop.marker)
            at < 0 || !callSpan(text, at).contains(hop.needle)
        }

    /** 从 [fromIndex] 处的 `marker` 起，按平衡圆括号取出整个调用块。 */
    private fun callSpan(text: String, fromIndex: Int): String {
        val open = text.indexOf('(', fromIndex)
        if (open < 0) return ""
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(fromIndex, i + 1)
                }
            }
        }
        return text.substring(fromIndex)
    }
}
