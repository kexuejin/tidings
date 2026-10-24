// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.feature.agents

import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.pendingApprovalCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 主屏（Agent 看板 / 工作区详情）喂给审批段的行选择 + 待批计数 —— issue「处置留痕 2 秒」。
 *
 * ## 为什么这一层的测试不能只测 `AgentApprovalsSection`
 *
 * 审批段自己的留痕逻辑本来就是对的（`seenPending` + `lingerDismissed`）。缺陷在**调用方**：
 * 看板与工作区详情先把非 pending 滤掉再传进来，于是"处置后保留 2 秒"在生产里一次都走不到。
 * 纯函数 `visibleApprovalRows` 测一百遍也抓不到这件事 —— 要钉住的是**喂进去的那份列表**，
 * 所以断言站在 [approvalRowsFor] 上。
 *
 * 反向验证：把 [approvalRowsFor] 的 `else` 分支改回 `false`（= 只喂 pending，旧行为），
 * [decidedRowStaysForItsLingerWindow] 变红；把它的 pending 分支改成不过滤过期，
 * [expiredPendingIsNeitherShownNorCounted] 变红。
 */
class AgentBoardApprovalRowsTest {

    private val now = 10_000L

    private fun approval(
        id: String,
        status: String = "pending",
        peerId: String = "peer-1",
        sessionId: String = "sess-1",
        expiresAt: Long = 0L,
    ) = AgentApprovalUi(
        approvalId = id,
        sessionId = sessionId,
        peerId = peerId,
        status = status,
        expiresAt = expiresAt,
    )

    @Test
    fun decidedRowStaysForItsLingerWindow() {
        // 本屏见过它 pending（所以 id 进了 lingerIds）⇒ 已决之后**仍要喂给审批段**，
        // 由审批段计时 2 秒后收起。调用方先滤掉它，这段留痕就是死代码。
        val decided = approval("a1", status = "approved")
        val rows = approvalRowsFor(listOf(decided), now, lingerIds = setOf("a1"))
        assertEquals(listOf("a1"), rows.map { it.approvalId }, "刚处置的那条必须留在主屏的审批段里")
    }

    @Test
    fun decidedRowNeverSeenPendingIsNotShown() {
        // 磁盘恢复回来的历史已决项：本屏从没见过它 pending ⇒ 不进清单页（常驻历史是会话详情的行为）。
        val history = approval("h1", status = "denied")
        assertTrue(
            approvalRowsFor(listOf(history), now, lingerIds = emptySet()).isEmpty(),
            "从没在本屏 pending 过的历史已决项不许出现在看板上",
        )
    }

    @Test
    fun expiredPendingIsNeitherShownNorCounted() {
        val pending = approval("p1", expiresAt = now + 60_000L)
        val expired = approval("p2", expiresAt = now - 1L)
        val approvals = listOf(pending, expired)

        assertEquals(
            listOf("p1"),
            approvalRowsFor(approvals, now, lingerIds = emptySet()).map { it.approvalId },
            "过期的 pending 不画",
        )
        assertEquals(1, pendingApprovalCount(approvals, now), "过期的 pending 也不计数")
    }

    @Test
    fun scopeFiltersNarrowToPeerAndSessions() {
        val mine = approval("a1", peerId = "peer-1", sessionId = "s1")
        val otherPeer = approval("a2", peerId = "peer-2", sessionId = "s1")
        val otherSession = approval("a3", peerId = "peer-1", sessionId = "s9")
        val approvals = listOf(mine, otherPeer, otherSession)

        assertEquals(
            listOf("a1", "a3"),
            approvalRowsFor(approvals, now, lingerIds = emptySet(), peerId = "peer-1").map { it.approvalId },
            "peerId 收窄只看这台设备",
        )
        assertEquals(
            listOf("a1"),
            approvalRowsFor(approvals, now, lingerIds = emptySet(), peerId = "peer-1", sessionIds = setOf("s1"))
                .map { it.approvalId },
            "工作区详情按 peerId + 本区会话集收窄",
        )
        assertEquals(
            1,
            pendingApprovalCount(approvals, now, peerId = "peer-1", sessionId = "s9"),
            "会话切换条/选择器按单个 sessionId 收窄",
        )
    }

    @Test
    fun lingerOnlyAppliesWithinScope() {
        // 留痕不许越界：另一个工作区的已决行，就算 id 在 lingerIds 里也不能混进来。
        val foreign = approval("a1", status = "approved", peerId = "peer-2")
        assertTrue(
            approvalRowsFor(listOf(foreign), now, lingerIds = setOf("a1"), peerId = "peer-1").isEmpty(),
            "收窄条件下，留痕集合不能把别的设备的已决项带进来",
        )
    }
}
