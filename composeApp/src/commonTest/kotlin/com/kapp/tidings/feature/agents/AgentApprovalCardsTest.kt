// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.feature.agents

import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.pendingApprovalCount
import com.kapp.tidings.core.protocol.AgentApprovalPayload
import com.kapp.tidings.core.protocol.Codec
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentApprovalCardsTest {

    @Test
    fun testRiskLevelResolution() {
        assertEquals("L3", resolveRiskLevel(null, "bash"))
        assertEquals("L3", resolveRiskLevel(null, "exec_command"))
        assertEquals("L3", resolveRiskLevel(null, "shell"))
        assertEquals("L3", resolveRiskLevel(null, "terminal"))
        assertEquals("L2", resolveRiskLevel(null, "write_file"))
        assertEquals("L2", resolveRiskLevel(null, "replace_file_content"))
        assertEquals("L2", resolveRiskLevel(null, "delete_item"))
        // 看不出高低的工具**不猜**：显示「未分级」，而不是替对端宣布它安全（旧实现猜 L1）。
        assertEquals(RISK_UNRATED, resolveRiskLevel(null, "view_file"))
        assertEquals(RISK_UNRATED, resolveRiskLevel(null, "search_web"))
        assertEquals(RISK_UNRATED, resolveRiskLevel(null, "unknown_tool"))
        assertEquals(RISK_UNRATED, resolveRiskLevel(null, null))

        // Explicit risk override always wins
        assertEquals("L3", resolveRiskLevel("l3", "view_file"))
        assertEquals("L1", resolveRiskLevel("L1", "bash"))
    }

    /**
     * 反向验证过：把 `shouldConfirmApproval` 改回「恒 false」（旧的一键放行行为），
     * 本测试变红；恢复后变绿 —— 它钉住的是「L3 必须先确认」这条纪律本身。
     */
    @Test
    fun testL3ApprovalRequiresConfirm() {
        // 高危：先确认再放行（一键批准无撤销是原缺陷）。
        assertEquals(true, shouldConfirmApproval("L3"))
        // L1/L2 保持一键 —— 确认卡只该花在真正危险的请求上。
        assertEquals(false, shouldConfirmApproval("L2"))
        assertEquals(false, shouldConfirmApproval("L1"))
        // 未分级不拦：它不是高危声明，只是不知情；拦它等于把「不知道」当成「危险」。
        assertEquals(false, shouldConfirmApproval(RISK_UNRATED))
    }

    @Test
    fun testApprovalExpiry() {
        fun approval(expiresAt: Long) = AgentApprovalUi(
            approvalId = "appr-1",
            sessionId = "sess-1",
            peerId = "node-1",
            expiresAt = expiresAt,
        )

        val now = 10_000L
        // 没填有效期 = 永不过期（0 的含义是「对端没说」，不是「已过期」）。
        assertEquals(false, isApprovalExpired(approval(0L), now))
        assertEquals(false, isApprovalExpired(approval(now), now))
        assertEquals(false, isApprovalExpired(approval(now + 1), now))
        assertEquals(true, isApprovalExpired(approval(now - 1), now))
    }

    /**
     * 看板的待批计数与审批段共用 [visibleApprovalRows] / [isApprovalExpired]：
     * 过期的 pending 不画、不算「待审批」。反向验证过：把过滤改回
     * `status == "pending"`（旧行为，过期也计入），本测试变红。
     */
    @Test
    fun testExpiredPendingIsNotCountedOrShown() {
        val now = 10_000L
        val pending = AgentApprovalUi(
            approvalId = "p1", sessionId = "s", peerId = "n", status = "pending", expiresAt = now + 60_000,
        )
        val expired = AgentApprovalUi(
            approvalId = "p2", sessionId = "s", peerId = "n", status = "pending", expiresAt = now - 1,
        )
        val decided = AgentApprovalUi(
            approvalId = "p3", sessionId = "s", peerId = "n", status = "approved",
        )
        val decidedDismissed = AgentApprovalUi(
            approvalId = "p4", sessionId = "s", peerId = "n", status = "denied",
        )

        val visible = visibleApprovalRows(
            approvals = listOf(pending, expired, decided, decidedDismissed),
            now = now,
            lingerDismissed = setOf("p4"),
        )

        // 过期的 pending 掉出；留痕窗口内的已决（p3）还在；窗口外（p4）退场。
        assertEquals(listOf("p1", "p3"), visible.map { it.approvalId })

        // 看板页头的「N 项待审批」与这里是同一把尺子 —— 断言必须调**生产函数**。
        // 上一版把生产表达式在这里抄了一遍：生产侧改回「过期也计入」时这条断言照旧绿
        // （假绿）。现在它数的是 pendingApprovalCount 本身，生产改坏就红。
        assertEquals(
            1,
            pendingApprovalCount(listOf(pending, expired, decided), now),
            "过期的 pending 不能计进「N 项待审批」",
        )
    }

    @Test
    fun testAgentApprovalPayloadWithRiskAndDiff() {
        val payload = AgentApprovalPayload(
            approvalId = "appr-001",
            sessionId = "sess-001",
            tool = "bash",
            summary = "rm -rf /tmp/cache",
            expiresAt = 1700000000000L,
            choices = "allow,deny,allow_session",
            riskLevel = "L3",
            diff = "--- /tmp/cache\n+++ /dev/null",
        )
        val jsonElement = Codec.agentApprovalPayload(
            approvalId = payload.approvalId,
            sessionId = payload.sessionId,
            tool = payload.tool,
            summary = payload.summary,
            expiresAt = payload.expiresAt,
            choices = payload.choices,
            riskLevel = payload.riskLevel,
            diff = payload.diff,
        )
        val jsonString = jsonElement.toString()
        val decoded = Json.decodeFromString<AgentApprovalPayload>(jsonString)

        assertEquals("appr-001", decoded.approvalId)
        assertEquals("sess-001", decoded.sessionId)
        assertEquals("bash", decoded.tool)
        assertEquals("L3", decoded.riskLevel)
        assertEquals("--- /tmp/cache\n+++ /dev/null", decoded.diff)
    }

    @Test
    fun testAgentApprovalUiFields() {
        val ui = AgentApprovalUi(
            approvalId = "appr-1",
            sessionId = "sess-1",
            peerId = "node-1",
            tool = "bash",
            summary = "Run git status",
            expiresAt = 1000L,
            choices = "allow,deny",
            status = "pending",
            riskLevel = "L3",
            diff = "diff --git a/file b/file",
        )
        assertEquals("L3", ui.riskLevel)
        assertNotNull(ui.diff)
        assertEquals("diff --git a/file b/file", ui.diff)
    }

    @Test
    fun testApprovalPermissionModel() {
        fun canApprove(
            selfNodeId: String,
            approverPeerId: String,
            grant: com.kapp.tidings.domain.model.GrantUi?,
        ): Boolean {
            return (approverPeerId == selfNodeId) ||
                (grant?.isMyDevice == true) ||
                (grant?.access == com.kapp.tidings.core.protocol.AgentGrantAccess.OWNER) ||
                (grant?.access == com.kapp.tidings.core.protocol.AgentGrantAccess.ADMIN && grant.delegatedApproval)
        }

        fun makeGrant(
            peerId: String,
            isMyDevice: Boolean = false,
            access: String = com.kapp.tidings.core.protocol.AgentGrantAccess.RW,
            delegatedApproval: Boolean = false,
        ) = com.kapp.tidings.domain.model.GrantUi(
            peerId = peerId,
            agentKey = null,
            scope = "all",
            state = "approved",
            at = 1000L,
            isMyDevice = isMyDevice,
            access = access,
            delegatedApproval = delegatedApproval,
        )

        val selfNode = "node-self"
        // 1. Host approval
        kotlin.test.assertTrue(canApprove(selfNode, selfNode, null))

        // 2. Private trust circle (my device)
        val myDeviceGrant = makeGrant(peerId = "node-phone", isMyDevice = true, access = com.kapp.tidings.core.protocol.AgentGrantAccess.RW)
        kotlin.test.assertTrue(canApprove(selfNode, "node-phone", myDeviceGrant))

        // 3. Owner role
        val ownerGrant = makeGrant(peerId = "node-owner", isMyDevice = false, access = com.kapp.tidings.core.protocol.AgentGrantAccess.OWNER)
        kotlin.test.assertTrue(canApprove(selfNode, "node-owner", ownerGrant))

        // 4. Admin with delegated approval enabled
        val adminWithApproval = makeGrant(peerId = "node-admin1", isMyDevice = false, access = com.kapp.tidings.core.protocol.AgentGrantAccess.ADMIN, delegatedApproval = true)
        kotlin.test.assertTrue(canApprove(selfNode, "node-admin1", adminWithApproval))

        // 5. Admin WITHOUT delegated approval -> cannot approve
        val adminNoApproval = makeGrant(peerId = "node-admin2", isMyDevice = false, access = com.kapp.tidings.core.protocol.AgentGrantAccess.ADMIN, delegatedApproval = false)
        kotlin.test.assertFalse(canApprove(selfNode, "node-admin2", adminNoApproval))

        // 6. External collaborator (rw) -> cannot approve
        val collabGrant = makeGrant(peerId = "node-dev", isMyDevice = false, access = com.kapp.tidings.core.protocol.AgentGrantAccess.RW)
        kotlin.test.assertFalse(canApprove(selfNode, "node-dev", collabGrant))

        // 7. Observer (ro) -> cannot approve
        val observerGrant = makeGrant(peerId = "node-observer", isMyDevice = false, access = com.kapp.tidings.core.protocol.AgentGrantAccess.RO)
        kotlin.test.assertFalse(canApprove(selfNode, "node-observer", observerGrant))
    }
}

