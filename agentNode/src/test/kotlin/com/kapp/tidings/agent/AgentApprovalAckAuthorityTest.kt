// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.core.protocol.AgentGrantAccess
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.makeEnvelope
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 宿主侧的**审批回执授权门禁**（P0：不能只靠客户端判据）。
 *
 * 客户端画不画按钮只是体验；真正的边界在宿主：收下一条 ack 之后**要不要应用**。
 * 这里钉四件事，每一条错了都会变成越权执行：
 *  1. ADMIN 且没开委托审批 ⇒ 拒绝（修复前：任何非 RO 都放行）；
 *  2. ADMIN 且开了委托审批 ⇒ 应用（不是"一律拒绝"）；
 *  3. OWNER / 本机设备 ⇒ 应用；
 *  4. 过期 ⇒ 拒绝（即使有权），且审计里能读出拒绝原因。
 *
 * 另外钉一条设计决定：`auto` 策略档**不**自动授予审批权（见 [AgentGrantStore.allowsApproval]
 * 的 KDoc）—— 用 scope=all 或显式 OWNER/ADMIN+委托来表达"可以代为批准"。
 *
 * 反向验证：把 [AgentGrantStore.allowsApproval] 里的 `canApproveAgentRequest` 换回修复前
 * 客户端那条「只挡 RO」判据（`access != RO` 即放行），
 * [adminWithoutDelegatedApprovalIsRejected] 与 [autoPolicyDoesNotOpenApproval] 变红；
 * 把 [AgentSessionRegistry.applyAckToApproval] 的过期分支删掉，[expiredRequestIsRejected] 变红。
 */
class AgentApprovalAckAuthorityTest {

    private val grantsFile: File = File.createTempFile("relay-grants-ack-", ".json").apply { delete() }
    private val peer = "phone-1"
    private val agentKey = "dsh-cli"
    private val wsId = "ws-alpha"
    private val sessionId = "sess-1"
    private val approvalId = "appr-1"

    @AfterTest
    fun cleanup() {
        runCatching { grantsFile.delete() }
    }

    private fun store(policy: String = "byDevice"): AgentGrantStore =
        AgentGrantStore(grantsFile).also { it.setPolicy(policy) }

    private fun registry(
        grants: AgentGrantStore,
        // 不取墙钟：`Long.MAX_VALUE` = 永不过期，`1L` = 必然过期。
        // 用 `now()` 造夹具会让测试随机器/时区漂（envDependentTestGate 要抓的正是这种）。
        expiresAt: Long = Long.MAX_VALUE,
    ): AgentSessionRegistry = AgentSessionRegistry().also { registry ->
        registry.recordSession(
            nodeId = peer,
            sessionId = sessionId,
            agentKind = agentKey,
            state = "busy",
            wsId = wsId,
            outbound = false,
        )
        // 宿主**发出**的审批：回执会从这台设备回来，由 key.nodeId 对上。
        registry.recordApproval(
            nodeId = peer,
            sessionId = sessionId,
            approvalId = approvalId,
            tool = "bash",
            summary = "rm -rf build/",
            expiresAt = expiresAt,
            outbound = true,
        )
        registry.approvalAuthority = approvalAuthorityFor(grants, registry)
    }

    private fun ack(registry: AgentSessionRegistry, kind: String = "approved") {
        registry.recordInbound(
            makeEnvelope(
                from = peer,
                type = MsgTypes.AGENT_ACK,
                payload = Codec.agentAckPayload(ackOf = approvalId, kind = kind),
            ),
        )
    }

    private fun status(registry: AgentSessionRegistry): String? =
        registry.approvals(peer, sessionId).first { it.approvalId == approvalId }.status

    private fun grant(
        grants: AgentGrantStore,
        access: String,
        delegatedApproval: Boolean = false,
        isMyDevice: Boolean = false,
        scope: String = "agent",
    ) {
        grants.grant(
            nodeId = peer,
            scope = scope,
            agentKey = if (scope == "all") null else agentKey,
            wsId = if (scope == "workspace") wsId else null,
            access = access,
            isMyDevice = isMyDevice,
            delegatedApproval = delegatedApproval,
            grantedBy = "host-owner",
            at = 0L,
        )
    }

    @Test
    fun adminWithoutDelegatedApprovalIsRejected() {
        val grants = store().also { grant(it, AgentGrantAccess.ADMIN, delegatedApproval = false) }
        val registry = registry(grants)

        ack(registry, "approved")

        assertNull(status(registry), "ADMIN 没开委托审批：宿主必须拒绝应用这条回执")
        assertEquals(
            AgentSessionRegistry.AckOutcome.REJECTED_UNAUTHORIZED.wire,
            registry.ack(peer, approvalId)?.outcome,
            "审计里要留下拒绝原因，不能只是一句「收到过」",
        )
    }

    @Test
    fun adminWithDelegatedApprovalIsApplied() {
        val grants = store().also { grant(it, AgentGrantAccess.ADMIN, delegatedApproval = true) }
        val registry = registry(grants)

        ack(registry, "approved")

        assertEquals("approved", status(registry), "ADMIN + 委托审批是显式授予的代为处置权")
        assertEquals(
            AgentSessionRegistry.AckOutcome.APPLIED.wire,
            registry.ack(peer, approvalId)?.outcome,
        )
    }

    @Test
    fun ownerAndMyDeviceAreApplied() {
        val ownerRegistry = registry(store().also { grant(it, AgentGrantAccess.OWNER) })
        ack(ownerRegistry)
        assertEquals("approved", status(ownerRegistry))

        val myDeviceRegistry = registry(
            store().also { grant(it, AgentGrantAccess.RW, isMyDevice = true) },
        )
        ack(myDeviceRegistry)
        assertEquals("approved", status(myDeviceRegistry), "「我的设备」不需要更高档位就该能批")
    }

    @Test
    fun readWriteGrantIsNotEnoughToApprove() {
        val registry = registry(store().also { grant(it, AgentGrantAccess.RW) })
        ack(registry)
        assertNull(status(registry), "能对话/能派活 ≠ 能批准高危操作")
    }

    @Test
    fun expiredRequestIsRejected() {
        val grants = store().also { grant(it, AgentGrantAccess.OWNER) }
        val registry = registry(grants, expiresAt = 1L)

        ack(registry, "approved")

        assertNull(status(registry), "过期后连 OWNER 也不许再放行 —— 否则过期只是客户端的说法")
        assertEquals(
            AgentSessionRegistry.AckOutcome.REJECTED_EXPIRED.wire,
            registry.ack(peer, approvalId)?.outcome,
            "过期拒绝必须能被审计读出来（用户视角：点了没反应，至少日志里说得出为什么）",
        )
    }

    @Test
    fun autoPolicyDoesNotOpenApproval() {
        val registry = registry(store("auto"))
        ack(registry)
        assertNull(
            status(registry),
            "auto 档是「能看」的开放档，不是「能批准」的开放档；审批只认显式授权",
        )
    }

    /**
     * 撤销「本会话免审」：宿主必须认识 revoke_session，并把它落到 revoked。
     *
     * 这是 P0 同族的第二半：客户端新加的撤销按钮只发一条 ack；宿主不认识这个 kind 的话，
     * 按钮就是"点了没反应"（旧宿主会静默停在 allow_session —— 那是兼容行为，不是修复）。
     */
    @Test
    fun revokeSessionAckFlipsAllowSessionToRevoked() {
        val grants = store().also { grant(it, AgentGrantAccess.OWNER) }
        val registry = registry(grants)
        // 先让它成为「会话免审」：宿主先收到过一次 allow_session 回执。
        ack(registry, "allow_session")
        assertEquals("allow_session", status(registry))

        ack(registry, "revoke_session")

        assertEquals("revoked", status(registry), "撤销必须能被宿主执行，否则界面上的按钮是空的")
        assertEquals(
            AgentSessionRegistry.AckOutcome.APPLIED.wire,
            registry.ack(peer, approvalId)?.outcome,
        )
    }

    @Test
    fun unknownAckKindIsAuditedAndNotApplied() {
        val registry = registry(store().also { grant(it, AgentGrantAccess.OWNER) })
        ack(registry, kind = "bogus")
        assertNull(status(registry))
        assertEquals(
            AgentSessionRegistry.AckOutcome.UNKNOWN_KIND.wire,
            registry.ack(peer, approvalId)?.outcome,
        )
    }
}
