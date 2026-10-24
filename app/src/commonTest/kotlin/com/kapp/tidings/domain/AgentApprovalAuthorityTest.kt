// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.AgentGrantAccess
import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.model.AgentSessionKey
import com.kapp.tidings.domain.model.AgentSessionUi
import com.kapp.tidings.domain.model.GrantUi
import com.kapp.tidings.domain.model.PeerUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 客户端「这条审批能不能处置」的唯一入口（[mayDecideApproval] / [canApproveAgentWork]）。
 *
 * 修复前这条规则有四份手抄，其中**看板那份写错成「只挡 RO」**：ADMIN（未开委托审批）
 * 甚至没有任何授权记录的人，在看板上都能点「批准」，而同一个用户在会话详情里看到的是
 * 「只读（无权审批）」。所以这里钉的不是"某个函数返回什么"，而是**四档授权 × 本机/远端**
 * 的完整真值表 —— 任何一档错都意味着一处 UI 会邀请用户去点一个他没权做的事。
 *
 * 反向验证：把 [mayDecideApproval] 换回看板旧判据（`access != ro` 即允许），
 * [adminWithoutDelegationIsReadOnly]、[noGrantIsReadOnly] 与 [expiredIsReadOnly] 变红。
 */
class AgentApprovalAuthorityTest {

    private val peerId = "phone-1"
    private val selfId = "self-node"
    private val agentKey = "dsh-cli"
    private val wsId = "ws-alpha"
    private val sessionId = "sess-1"
    private val now = 1_000_000L

    private fun approval(
        expiresAt: Long = now + 60_000L,
        // 本机宿主那一档要让审批的 peerId 就是本机节点，否则 `isLocalHost` 判不出来。
        ownerPeerId: String = peerId,
    ) = AgentApprovalUi(
        approvalId = "appr-1",
        sessionId = sessionId,
        peerId = ownerPeerId,
        expiresAt = expiresAt,
    )

    private fun state(
        grant: GrantUi? = null,
        peer: PeerUi = PeerUi(nodeId = peerId, nick = "同事的手机", online = true),
        selfIdOverride: String = selfId,
    ): RelayUiState {
        val session = AgentSessionUi(
            peerId = peerId,
            sessionId = sessionId,
            agentKind = agentKey,
            wsId = wsId,
        )
        return RelayUiState(
            settings = RelaySettings(nodeId = selfIdOverride),
            peers = listOf(peer),
            agentSessions = mapOf(AgentSessionKey(peerId, sessionId) to session),
            agentGrants = grant?.let { mapOf(grantKeyOf(peerId, it.agentKey, it.wsId) to it) }.orEmpty(),
        )
    }

    private fun grant(
        access: String,
        delegatedApproval: Boolean = false,
        isMyDevice: Boolean = false,
        scope: String = "agent",
        key: String? = agentKey,
        ws: String? = null,
    ) = GrantUi(
        peerId = peerId,
        agentKey = key,
        scope = scope,
        state = "approved",
        at = 0L,
        wsId = ws,
        access = access,
        isMyDevice = isMyDevice,
        delegatedApproval = delegatedApproval,
    )

    @Test
    fun adminWithoutDelegationIsReadOnly() {
        assertFalse(
            state(grant(AgentGrantAccess.ADMIN, delegatedApproval = false)).mayDecideApproval(approval(), now),
            "ADMIN 没开委托审批：客户端不许画「批准」（修复前看板正是在这里放行了）",
        )
    }

    @Test
    fun adminWithDelegationMayDecide() {
        assertTrue(
            state(grant(AgentGrantAccess.ADMIN, delegatedApproval = true)).mayDecideApproval(approval(), now),
        )
    }

    @Test
    fun noGrantAndReadOnlyAndReadWriteAreAllReadOnly() {
        assertFalse(state().mayDecideApproval(approval(), now), "没有任何授权记录 ⇒ 只读（不猜）")
        assertFalse(state(grant(AgentGrantAccess.RO)).mayDecideApproval(approval(), now))
        assertFalse(state(grant(AgentGrantAccess.RW)).mayDecideApproval(approval(), now), "能派活 ≠ 能批准")
    }

    @Test
    fun ownerAndMyDeviceMayDecide() {
        assertTrue(state(grant(AgentGrantAccess.OWNER)).mayDecideApproval(approval(), now))
        assertTrue(
            state(grant(AgentGrantAccess.RW, isMyDevice = true)).mayDecideApproval(approval(), now),
            "「我的设备」无需更高档位",
        )
    }

    @Test
    fun localHostAlwaysMayDecide() {
        val onMyOwnHost = state(peer = PeerUi(nodeId = selfId, nick = "本机", online = true))
        assertTrue(
            onMyOwnHost.mayDecideApproval(approval(ownerPeerId = selfId), now),
            "本机宿主对自己的 agent 有全部权限",
        )

        val loopback = state(peer = PeerUi(nodeId = peerId, nick = "回环", online = true, ip = "127.0.0.1"))
        assertTrue(loopback.mayDecideApproval(approval(), now), "回环地址同样算本机")
    }

    @Test
    fun expiredIsReadOnlyEvenForTheHost() {
        val host = state(peer = PeerUi(nodeId = selfId, nick = "本机", online = true))
        assertFalse(
            host.mayDecideApproval(approval(expiresAt = now - 1L, ownerPeerId = selfId), now),
            "过期后连本机也不该画按钮 —— 否则「过期」只是后端不肯执行、界面上还在邀请用户点",
        )
        assertTrue(
            host.mayDecideApproval(approval(expiresAt = 0L, ownerPeerId = selfId), now),
            "expiresAt=0 = 对端没声明，按永不过期",
        )
    }

    @Test
    fun workLevelEntryAgreesWithPerApprovalEntry() {
        // 工作区权限卡与逐条审批必须同值：修复前权限卡说 OWNER，审批卡却说只读（或反过来）。
        val ownerState = state(grant(AgentGrantAccess.OWNER))
        assertTrue(ownerState.canApproveAgentWork(peerId, agentKey, wsId))
        assertEquals(
            ownerState.canApproveAgentWork(peerId, agentKey, wsId),
            ownerState.mayDecideApproval(approval(), now),
            "同一条授权下，两个入口的结论必须一致",
        )
        assertFalse(state().canApproveAgentWork(peerId, agentKey, wsId))
    }
}
