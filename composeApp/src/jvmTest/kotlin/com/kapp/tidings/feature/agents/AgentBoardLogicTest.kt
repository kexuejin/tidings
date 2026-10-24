// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.feature.agents

import com.kapp.tidings.domain.model.AgentApprovalUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Agent 看板这轮修复引入的纯函数判据。
 *
 * 每条都对应一个「此前靠组合体内联逻辑、只有截图能看见」的缺陷：
 * L3 一键批准无确认、过期审批计入待批数、处置后卡片无声消失、
 * 事件流截断 20 条无展开、会话详情新事件追加屏外。
 * 反向验证记录：把 [shouldConfirmApproval] 改回恒 false（旧行为），
 * `l3RequiresAConfirmCard` 变红；把 [visibleApprovalRows] 的 pending 分支
 * 改回不过滤过期，`expiredPendingIsHidden` 变红。
 */
class AgentBoardLogicTest {

    // ---- shouldConfirmApproval：只有 L3 需要确认卡 ----

    @Test
    fun l3RequiresAConfirmCard() {
        assertTrue(shouldConfirmApproval("L3"), "高危批准必须先过确认，不能一键放行")
    }

    @Test
    fun lowRiskAndUnratedStayOneTap() {
        assertFalse(shouldConfirmApproval("L1"))
        assertFalse(shouldConfirmApproval("L2"))
        assertFalse(shouldConfirmApproval(RISK_UNRATED), "未分级≠高危：确认卡只花在确定的危险上")
    }

    // ---- resolveRiskLevel：声明优先；猜只往高猜；猜不出就未分级 ----

    @Test
    fun declaredRiskLevelWinsAndIsNormalized() {
        assertEquals("L2", resolveRiskLevel(risk = "l2", tool = "bash"), "对端声明了就信对端，工具名不再参与")
    }

    @Test
    fun heuristicsOnlyGuessHigh() {
        assertEquals("L3", resolveRiskLevel(risk = null, tool = "RunBashCommand"))
        assertEquals("L2", resolveRiskLevel(risk = null, tool = "file_write"))
    }

    @Test
    fun unknownToolsAreUnratedNeverInventedAsL1() {
        assertEquals(RISK_UNRATED, resolveRiskLevel(risk = null, tool = "calendar_lookup"))
        assertEquals(RISK_UNRATED, resolveRiskLevel(risk = null, tool = null))
    }

    // ---- 有效期与待批口径：isApprovalExpired 是唯一判据 ----

    @Test
    fun expiryJudgementCoversAllThreeCases() {
        val approval = AgentApprovalUi(approvalId = "a", sessionId = "s", peerId = "p", expiresAt = 1_000L)
        assertFalse(isApprovalExpired(approval, now = 999L), "还没到有效期")
        assertFalse(isApprovalExpired(approval, now = 1_000L), "恰好到期那一刻仍可处置")
        assertTrue(isApprovalExpired(approval, now = 1_001L))
        assertFalse(isApprovalExpired(approval.copy(expiresAt = 0L), now = Long.MAX_VALUE), "没有有效期就永不过期")
    }

    @Test
    fun expiredPendingIsHidden() {
        val pending = AgentApprovalUi(approvalId = "live", sessionId = "s", peerId = "p")
        val expired = AgentApprovalUi(
            approvalId = "gone", sessionId = "s", peerId = "p",
            expiresAt = 1_000L, status = "pending",
        )
        val rows = visibleApprovalRows(listOf(pending, expired), now = 2_000L, lingerDismissed = emptySet())
        assertEquals(listOf(pending), rows, "过期 pending 不画也不计数")
    }

    @Test
    fun decidedRowsLingerUntilDismissed() {
        val decided = AgentApprovalUi(approvalId = "d", sessionId = "s", peerId = "p", status = "approved")
        val kept = visibleApprovalRows(listOf(decided), now = 0L, lingerDismissed = emptySet())
        assertEquals(listOf(decided), kept, "处置后 2 秒留痕窗口内仍然可见（与被删不可区分是原缺陷）")
        val gone = visibleApprovalRows(listOf(decided), now = 0L, lingerDismissed = setOf("d"))
        assertTrue(gone.isEmpty(), "留痕窗口结束（进 lingerDismissed）后退场")
    }

    // ---- shouldAutoFollow：近底跟随、上翻不打扰 ----

    @Test
    fun autoFollowOnlyNearTheBottom() {
        assertTrue(shouldAutoFollow(lastVisible = 1_000, max = 1_000, threshold = AUTO_FOLLOW_THRESHOLD_PX), "贴底必须跟随")
        assertTrue(shouldAutoFollow(lastVisible = 700, max = 1_000, threshold = 480), "离底不到阈值算仍在底部")
        assertFalse(shouldAutoFollow(lastVisible = 0, max = 1_000, threshold = 480), "用户往上翻了就不打扰")
    }

    @Test
    fun contentShorterThanViewportAlwaysFollows() {
        assertTrue(shouldAutoFollow(lastVisible = 0, max = 0, threshold = 480), "内容不足一屏（无滚动条）按跟随处理")
    }

    // ---- visibleEventCount：基础窗口 20，每点一次「显示更早」多 50 ----

    @Test
    fun eventWindowStartsAtTwentyAndGrowsInFiftySteps() {
        assertEquals(20, visibleEventCount(total = 100, revealed = 0), "默认只画最近 20 条（旧行为到此为止）")
        assertEquals(70, visibleEventCount(total = 100, revealed = 50), "每点一次多展开 50 条")
        assertEquals(100, visibleEventCount(total = 100, revealed = 500), "到顶后不再超出总数")
        assertEquals(7, visibleEventCount(total = 7, revealed = 0), "不足一窗时全画")
    }

    @Test
    fun eventWindowDefendsAgainstNegativeRevealState() {
        assertEquals(20, visibleEventCount(total = 100, revealed = -1), "负数防御：按未展开处理")
    }
}
