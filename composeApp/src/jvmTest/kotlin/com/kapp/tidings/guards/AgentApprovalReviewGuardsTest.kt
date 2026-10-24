// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T21 界面 review 修复的**接线级守卫**（这些点都在 Composable 内部，单测够不到）。
 *
 * 每条都对应一个"用户能感知、门禁抓不到"的问题：聊天里盲批高危、过期审批仍可点、
 * 看板启动失败无限转圈、空态说谎、远程查看冻结帧/1:1 不可平移/共享中挡死界面、
 * 审批行摘要把屏撑爆、授权清单印 wire 值……
 *
 * 守卫只问"这一行还在不在"，所以每条都必须能区分对错：下面的
 * [guardDetectsEveryRemovedWiring] 在**内存里**逐条删掉那一行再跑一遍，任一未报即红。
 */
class AgentApprovalReviewGuardsTest {

    private data class Wiring(val file: String, val needle: String, val why: String)

    private val chatScreen = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt"
    private val chatApproval = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatApproval.kt"
    private val console = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentConsoleScreen.kt"
    private val sessionDetail = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentSessionDetail.kt"
    private val workspaceDetail = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/WorkspaceDetailView.kt"
    private val board = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentWorkspaceBoard.kt"
    private val cards = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/agents/AgentApprovalCards.kt"
    private val remoteView = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/RemoteViewSessionDialog.kt"

    private val wirings = listOf(
        Wiring(chatScreen, "isPendingApproval(it, approvalsNow)", "聊天审批横幅必须先滤掉过期（过期仍可点是 P1）"),
        Wiring(chatScreen, "onRespondChoice = onRespondChoice", "聊天要接三参处置，否则 G/免审入口在聊天里永远不出现"),
        Wiring(chatApproval, "AgentApprovalRow(", "聊天必须复用看板那一行（否则又是两套审批 UI：无摘要无 L3 确认）"),
        Wiring(console, "isPendingApproval(it, now)", "打开聊天时挑会话的待批集合也必须滤过期"),
        Wiring(workspaceDetail, "isPendingApproval(it, now)", "工作区挑活跃会话同理"),
        Wiring(sessionDetail, "agent_approval_history_title", "会话详情要把历史单独成段（标题：审批记录）"),
        Wiring(sessionDetail, "includeExpiredPending = true", "已过期的审批也要在历史里留痕（否则凭空消失）"),
        Wiring(board, "ConversationFailedEmptyState(", "节点启动失败必须报原因 + 给出路，不能无限转圈"),
        Wiring(board, "agent_empty_agents_only_title", "空态要区分「没有 agent」与「有 agent 但没有工作区」"),
        Wiring(cards, "agent_approval_allow_session_hint", "免审必须说清范围，否则一点就是不可逆的空白放行"),
        Wiring(cards, "heightIn(min = 48.dp)", "审批行的按钮触达不许低于 48dp"),
        Wiring(remoteView, "Popup(", "共享中必须是非模态条，模态会挡住整个 App"),
        Wiring(remoteView, "STALE_FRAME_AFTER_MS", "冻结帧要有「画面暂未更新」，否则静止画面 = 实时画面"),
        Wiring(remoteView, "detectDragGestures", "100% 档必须能平移，否则只能看中心一块"),
        Wiring(remoteView, "selectable(", "缩放分段的选中态要进语义树"),
    )

    /** 现存文案里不许再出现原始会话 id（旧聊天横幅的「bash · sess-…」）。 */
    @Test
    fun chatBannerNoLongerPrintsTheRawSessionId() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(chatApproval))
        assertTrue(
            "approval.sessionId" !in text,
            "聊天审批行不许印原始 sessionId：那不是用户看得懂的信息，还会让人以为在批一个会话",
        )
    }

    @Test
    fun everyReviewedFixIsStillWired() {
        val missing = missing(wirings) { GuardSourceScan.read(it) }
        assertTrue(missing.isEmpty(), missing.joinToString("；") { "缺失：" + it.why })
    }

    /**
     * 守卫必须能区分对错：把每条接线那一段文字在**内存里**删掉，必须各报一次。
     * 不为了验证守卫去改生产文件（热点文件正被别人改）。
     */
    @Test
    fun guardDetectsEveryRemovedWiring() {
        wirings.forEach { wiring ->
            val broken = missing(listOf(wiring)) { file ->
                if (file == wiring.file) GuardSourceScan.read(file).replace(wiring.needle, "") else GuardSourceScan.read(file)
            }
            assertEquals(
                listOf(wiring.file),
                broken.map { it.file },
                "删掉这条接线后守卫却没报 —— 它守不住任何东西：" + wiring.why,
            )
        }
    }

    private fun missing(checks: List<Wiring>, read: (String) -> String): List<Wiring> =
        checks.filter { check ->
            !GuardSourceScan.stripComments(read(check.file)).contains(check.needle)
        }
}
