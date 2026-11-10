// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 榫卯 Tenon contributors

package com.kapp.tenon.feature.agents

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工作区看板**容器语言**的守卫：设备组头 / 工作区行必须是和会话行同一套「白卡 + 圆角 + 投影」，
 * 而分组层次靠**排版**，不靠再套一层盒子。
 *
 * 为什么只能源码级守：这两种偏差都只在渲染帧里看得见，而渲染帧只进截图基线（改基线是另一件事）。
 * 这里把"用的是哪套容器"钉在源码上：`LocalCardShape.current` / `relayCardShadow()` / 内边距 token
 * 一缺就红；设备组头出现尺寸字面量也红。
 *
 * 反向验证：`guardDetectsEachRemovedPiece` 在**内存里**逐条删掉对应那段再跑同一个判据，
 * 必须各报一次 —— 不为了验证守卫去改生产文件。
 */
class AgentBoardContainerGuardTest {

    private val board =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentWorkspaceBoard.kt"

    /** 取 `marker` 起到下一个 `@Composable`（或文件尾）之间的活代码。 */
    private fun slice(text: String, marker: String): String {
        val start = text.indexOf(marker)
        if (start < 0) return ""
        val next = text.indexOf("@Composable", start + marker.length)
        return if (next < 0) text.substring(start) else text.substring(start, next)
    }

    /**
     * 聚焦卡接线段：从 `focusPeerId` 派生行起、到 `AgentApprovalsSection(`（紧随其后的
     * 下一段）止 —— 恰好罩住「派生 + 调用」，不把同一函数后面工作区行的合法
     * `onOpenWorkspace` 调用裹进来。
     */
    private fun focusWiringSlice(boardSource: String): String {
        val start = boardSource.indexOf("val focusPeerId")
        if (start < 0) return ""
        val end = boardSource.indexOf("AgentApprovalsSection(", start)
        return if (end < 0) boardSource.substring(start) else boardSource.substring(start, end)
    }

    /** 容器语言的判据（对"设备组头"与"Agent 层"两段分别成立）。 */
    private fun containerHoles(text: String): List<String> {
        val groupHead = slice(text, "private fun BoardGroupHead(")
        val agentHead = slice(text, "private fun AgentSectionHead(")
        val holes = mutableListOf<String>()
        if (groupHead.isEmpty()) holes += "锚点：BoardGroupHead"
        if (agentHead.isEmpty()) holes += "锚点：AgentSectionHead"
        // 设备组头 = 与会话行同一张卡
        listOf(
            "LocalCardShape.current" to "设备组头要用全局卡片圆角",
            "relayCardShadow()" to "设备组头要用全仓自绘投影（与会话行同一口径）",
            "padding(horizontal = sp.lg, vertical = sp.md)" to "内边距走 spacing token",
            "MaterialTheme.colorScheme.surface" to "卡片底色用 surface，不是裸页面底色",
        ).forEach { (needle, why) -> if (!groupHead.contains(needle)) holes += why + "（缺 " + needle + "）" }
        if (Regex("\\.dp\\b").containsMatchIn(groupHead)) {
            holes += "设备组头不许写尺寸字面量（.dp），一律走 token"
        }
        // Agent 层 = 排版层级，不是容器层级
        listOf("LocalCardShape.current", "relayCardShadow()").forEach { needle ->
            if (agentHead.contains(needle)) holes += "Agent 层再套卡就变成三层并列盒子（不该有 " + needle + "）"
        }
        if (!agentHead.contains("sp.lg")) holes += "Agent 层缩进要对齐卡内边距（sp.lg）"
        if (!agentHead.contains("MaterialTheme.typography.labelMedium")) holes += "Agent 身份键要留在 labelMedium 排版层级"
        // 工作区行 = 共享卡片组件，不许在板里重画
        if (!text.contains("InboxUnitRow(")) holes += "工作区行必须复用共享卡片组件"
        if (Regex("private fun BoardWorkspaceRow\\(").containsMatchIn(text)) holes += "板里不许另造工作区行组件"
        return holes
    }

    @Test
    fun boardUsesTheSameContainerLanguageAsTheRestOfTheApp() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(board))
        val holes = containerHoles(text)
        assertTrue(holes.isEmpty(), holes.joinToString("；"))
    }

    /**
     * 判据必须能区分对错：逐条把对应的字删掉，`containerHoles` 必须各报一次。
     */
    @Test
    fun guardDetectsEachRemovedPiece() {
        val base = GuardSourceScan.stripComments(GuardSourceScan.read(board))
        val cases = listOf(
            "private fun BoardGroupHead(" to "LocalCardShape.current",
            "private fun BoardGroupHead(" to "relayCardShadow()",
            "private fun BoardGroupHead(" to "padding(horizontal = sp.lg, vertical = sp.md)",
            "private fun BoardGroupHead(" to "MaterialTheme.colorScheme.surface",
            "private fun AgentSectionHead(" to "sp.lg",
            "private fun AgentSectionHead(" to "MaterialTheme.typography.labelMedium",
        )
        cases.forEach { (marker, needle) ->
            val target = slice(base, marker)
            assertTrue(target.contains(needle), "夹具前提：这一段的源码里本来有 " + needle)
            val broken = base.replace(target, target.replace(needle, ""))
            assertFalse(
                containerHoles(broken).isEmpty(),
                "把 " + marker + " 里的 " + needle + " 删掉后判据没报 —— 它守不住这一条",
            )
        }
        // 反向：Agent 层加上卡片形状必须被报出来（这一条守卫的是"别套盒子"）。
        val agentHead = slice(base, "private fun AgentSectionHead(")
        val nested = base.replace(agentHead, agentHead.replace("Row(", "Row(modifier = Modifier.background(MaterialTheme.colorScheme.surface, LocalCardShape.current))"))
        assertTrue(
            containerHoles(nested).any { it.contains("Agent 层再套卡") },
            "Agent 层套上卡片形状时必须变红",
        )
    }

    /**
     * T-2e 待审批**置顶聚焦卡**的接线纪律（原型 screenAgents 置顶条）：
     *
     * ① 计数走页头同一把尺子（`pendingApprovalCount`，一个数只有一个来源）——
     *    在这里数第二遍（如对 lingering 行再 `.count { }`）= 两把尺子，迟早分叉；
     * ② 直达目标从**待批项所在宿主**来（审批行的 peerId），不是「第一个 agent」；
     * ③ 「立即审批」按原型语义进**聊天**（`onSelectAgent` + `onOpenChat` 成对），
     *    不进工作区详情 —— 逐条处置在聊天/审批队列里，不在二级工作区中心。
     *
     * 为什么只能源码级守：这三条都是调用点选择，行为测试够不到（无 Compose UI
     * 测试基建）；截图帧画不出「点按后去了哪」。
     *
     * 反向验证：在内存里把 `onOpenChat(` 换成 `onOpenWorkspace(`、把
     * `pendingCount = totalPending` 换成字面量、把 peerId 派生换成
     * `agents.first()`，判据必须各红一次（不改正生产文件）。
     */
    @Test
    fun pinnedApprovalFocusCardWiringFollowsThePrototype() {
        val board = GuardSourceScan.stripComments(GuardSourceScan.read(this.board))
        val call = focusWiringSlice(board)
        assertTrue(call.contains("val focusPeerId"), "看板上找不到聚焦卡的直达目标派生 —— task-20 的接线没了")
        assertTrue(
            call.contains("PinnedApprovalFocusCard("),
            "接线段里没有置顶聚焦卡的调用 —— task-20 的接线没了",
        )

        assertTrue(
            call.contains("pendingCount = totalPending"),
            "聚焦卡计数没有用页头同一把尺子（totalPending = pendingApprovalCount）",
        )
        assertTrue(
            call.contains("firstOrNull { it.status == \"pending\" }?.peerId"),
            "直达目标没有从待批项所在宿主派生（审批行的 peerId）",
        )
        assertTrue(
            call.contains("onSelectAgent(focusPeerId)") && call.contains("onOpenChat(focusPeerId, null)"),
            "「立即审批」没有按原型语义选中会话并进聊天（onSelectAgent + onOpenChat 成对）",
        )
        assertFalse(
            call.contains("onOpenWorkspace("),
            "「立即审批」进了工作区详情 —— 处置在审批队列/聊天里，二级中心不是直达目的地",
        )

        // 内存变异反向验证（每条判据都要能区分对错）。
        val brokenChat = board.replace(
            "onSelectAgent(focusPeerId)\n                onOpenChat(focusPeerId, null)",
            "onOpenWorkspace(focusPeerId, null, null)",
        )
        assertFalse(
            wiringHolds(brokenChat),
            "把进聊天改成进工作区详情后判据没报 —— ③ 守不住",
        )
        val brokenRuler = board.replace("pendingCount = totalPending", "pendingCount = 3")
        assertFalse(
            wiringHolds(brokenRuler),
            "计数换字面量后判据没报 —— ① 守不住",
        )
        val brokenTarget = board.replace(
            "firstOrNull { it.status == \"pending\" }?.peerId",
            "agents.firstOrNull()?.nodeId",
        )
        assertFalse(
            wiringHolds(brokenTarget),
            "直达目标换成第一个 agent 后判据没报 —— ② 守不住",
        )
    }

    /** 接线判据（供正向与内存变异共用）；切片同正向，派生行起、审批段止。 */
    private fun wiringHolds(boardSource: String): Boolean {
        val call = focusWiringSlice(boardSource)
        return call.contains("val focusPeerId") &&
            call.contains("PinnedApprovalFocusCard(") &&
            call.contains("pendingCount = totalPending") &&
            call.contains("firstOrNull { it.status == \"pending\" }?.peerId") &&
            call.contains("onSelectAgent(focusPeerId)") &&
            call.contains("onOpenChat(focusPeerId, null)") &&
            !call.contains("onOpenWorkspace(")
    }
}
