// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import app.relay.agent.AgentGrantStore
import app.relay.agent.GrantReplyOutcome
import app.relay.core.protocol.AgentGrantAccess
import app.relay.core.protocol.AgentGrantScopes
import app.relay.core.protocol.AgentGrantStates
import app.relay.core.protocol.MsgTypes
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 「授信设备」面板的四个决定：写台账 + 回帧。
 *
 * 守的是修之前那条真机缺陷：面板只调 AgentGrantStore（本机台账），发 agent-grant 帧的
 * 逻辑埋在 ControlPlane.writeGrants 的 grantReply 分支里，界面够不到 —— 用户在设置里点完
 * 「设为我的设备」，手机侧四档状态纹丝不动。
 *
 * 两条不变量：
 *  1. **每个决定都真的发了一帧**（面板动作 → GrantReplySender.send），四个动作逐个钉；
 *  2. **对端不支持时不假装成功**：返回 Unsupported，且台账照写（决定是本机事实，
 *     帧发不出去也不该把人的按钮丢掉）。
 */
class TrustedDeviceDecisionsTest {

    private val file: File = File.createTempFile("relay-desktop-grants-", ".json").apply { delete() }

    /** 假出口：记录每一条决定，并允许测试指定结局（默认"已送达"）。 */
    private class FakeReplySender(
        var outcome: (GrantDecision) -> GrantReplyOutcome = { GrantReplyOutcome.Sent(it.nodeId) },
    ) : GrantReplySender {
        val sent = mutableListOf<GrantDecision>()

        override suspend fun send(decision: GrantDecision): GrantReplyOutcome {
            sent += decision
            return outcome(decision)
        }
    }

    @AfterTest
    fun cleanup() {
        runCatching { file.delete() }
    }

    @Test
    fun markAsMyDeviceWritesLedgerAndSendsApprovedFrame() = runBlocking {
        val store = AgentGrantStore(file)
        val sender = FakeReplySender()
        val decisions = TrustedDeviceDecisions(store, sender)

        val outcome = decisions.markAsMyDevice("phone-1")

        assertTrue(outcome is GrantReplyOutcome.Sent, "对端在（声明了 agr1）时必须如实报已发送：$outcome")
        val grant = store.snapshot().grants.single()
        assertEquals("phone-1", grant.nodeId)
        assertEquals(AgentGrantScopes.ALL, grant.scope)
        assertEquals(AgentGrantAccess.OWNER, grant.access)
        assertTrue(grant.isMyDevice, "「设为我的设备」必须记 isMyDevice")
        assertTrue(grant.delegatedApproval)

        // 面板动作 → 真的发了一帧。这正是修之前缺的那一步。
        val frame = sender.sent.single()
        assertEquals("phone-1", frame.nodeId)
        assertEquals(AgentGrantStates.APPROVED, frame.state)
        assertEquals(AgentGrantScopes.ALL, frame.scope)
        assertEquals(AgentGrantAccess.OWNER, frame.access)
        assertEquals(true, frame.isMyDevice)
        assertEquals(true, frame.delegatedApproval)
    }

    @Test
    fun unsupportedPeerStillWritesLedgerButDoesNotClaimSent() = runBlocking {
        val store = AgentGrantStore(file)
        val sender = FakeReplySender { GrantReplyOutcome.Unsupported(it.nodeId, MsgTypes.CAP_AGENT_GRANT) }
        val decisions = TrustedDeviceDecisions(store, sender)

        val outcome = decisions.markAsMyDevice("old-phone")

        assertTrue(
            outcome is GrantReplyOutcome.Unsupported,
            "对端不认识 agr1 时必须如实说「没发」，不许假装成功：$outcome",
        )
        // 台账照写：决定是本机事实，帧发不出去也不该丢（对方升级后再申请会拿到清单）。
        assertEquals(1, store.snapshot().grants.size, "帧没发出去也要留住人按过的决定")
        assertEquals(1, sender.sent.size, "仍然要尝试发一次，才知道对方不支持")
    }

    @Test
    fun approveUsesRequestAccessAndSendsApprovedFrame() = runBlocking {
        val store = AgentGrantStore(file)
        val sender = FakeReplySender()
        val decisions = TrustedDeviceDecisions(store, sender)
        store.recordRequest(
            nodeId = "phone-2",
            agentKey = "dsh",
            wsId = "ws-1",
            access = AgentGrantAccess.RO,
            reason = "想看工作区",
            at = 1L,
        )
        val request = store.snapshot().requests.single()

        val outcome = decisions.approve(request, asMyDevice = false)

        assertTrue(outcome is GrantReplyOutcome.Sent)
        val grant = store.snapshot().grants.single()
        assertEquals(AgentGrantAccess.RO, grant.access, "申请里要的是只读，批准不能悄悄放大成读写")
        assertFalse(grant.isMyDevice)
        val frame = sender.sent.single()
        assertEquals(AgentGrantStates.APPROVED, frame.state)
        assertEquals(AgentGrantAccess.RO, frame.access)
        assertEquals(false, frame.isMyDevice)
    }

    @Test
    fun approveNormalNeverGrantsAboveRwEvenWhenTheRequestAskedForOwner() = runBlocking {
        val store = AgentGrantStore(file)
        val sender = FakeReplySender()
        val decisions = TrustedDeviceDecisions(store, sender)
        // 对端在申请里**自己写了 owner**（AgentInbound 把帧里的 access 原样收下）。
        store.recordRequest(
            nodeId = "phone-6",
            agentKey = "dsh",
            wsId = null,
            access = AgentGrantAccess.OWNER,
            reason = "要最高权限",
            at = 1L,
        )
        val request = store.snapshot().requests.single()

        val outcome = decisions.approve(request, asMyDevice = false)

        assertTrue(outcome is GrantReplyOutcome.Sent)
        assertEquals(
            AgentGrantAccess.RW,
            store.snapshot().grants.single().access,
            "「批准协同」不许被申请方自己挑到 owner（申请方挑权限 = 提权）",
        )
        assertEquals(
            AgentGrantAccess.RW,
            sender.sent.single().access,
            "回给设备的帧也必须是封顶后的档位 —— 否则手机侧看到的和台账不一致",
        )
    }

    @Test
    fun approveAsMyDeviceGrantsOwnerRegardlessOfTheRequestedLevel() = runBlocking {
        val store = AgentGrantStore(file)
        val sender = FakeReplySender()
        val decisions = TrustedDeviceDecisions(store, sender)
        store.recordRequest(
            nodeId = "phone-7",
            agentKey = "dsh",
            wsId = null,
            access = AgentGrantAccess.RO,
            reason = "只看一眼",
            at = 1L,
        )
        val request = store.snapshot().requests.single()

        decisions.approve(request, asMyDevice = true)

        assertEquals(
            AgentGrantAccess.OWNER,
            store.snapshot().grants.single().access,
            "「批准为我的设备」是显式的高权限档：按它自己的语义给 OWNER，不看申请里写的是哪一档",
        )
    }

    @Test
    fun theRequestCardShowsTheRequestedLevelAsWritten() {
        // 如实显示对端申请的是哪一档 —— 用户必须先看得见「我在批什么」，才谈得上同意。
        assertEquals(AgentGrantAccess.RO, requestedAccessCode(AgentGrantAccess.RO))
        assertEquals(AgentGrantAccess.ADMIN, requestedAccessCode(AgentGrantAccess.ADMIN))
        assertEquals(AgentGrantAccess.OWNER, requestedAccessCode(AgentGrantAccess.OWNER))
        assertEquals(AgentGrantAccess.RW, requestedAccessCode(null), "没写档位 = 与 AgentGrantStore 同口径的 rw")
        assertEquals(
            AgentGrantAccess.RW,
            requestedAccessCode("root"),
            "不认识的档不许原样显示：把脏值当事实，用户会以为对端申请的是 root",
        )
    }

    @Test
    fun theAccessLabelShowsTheRequestedLevelNotTheOneWeIntendToGrant() {
        // 显示的是"对端申请的"，不是"我打算给的" —— 两者混在一起，用户看到的就是自己想看的那个。
        assertEquals("trusted_devices_access_owner", requestedAccessKey(AgentGrantAccess.OWNER))
        assertEquals("trusted_devices_access_admin", requestedAccessKey(AgentGrantAccess.ADMIN))
        assertEquals("trusted_devices_access_ro", requestedAccessKey(AgentGrantAccess.RO))
        assertEquals("trusted_devices_access_rw", requestedAccessKey(null))
        assertEquals(
            "trusted_devices_access_rw",
            requestedAccessKey("root"),
            "脏值按 rw 归一化显示（不许原样当事实）",
        )
    }

    @Test
    fun theCeilingShownMatchesWhatTheButtonActuallyGrants() = runBlocking {
        // 卡片上标的「最多授予 X」必须与按钮的实际封顶一致，否则又是一个「界面比事实更少」。
        assertEquals(AgentGrantAccess.RW, approveCeilingCode(asMyDevice = false))
        assertEquals(AgentGrantAccess.OWNER, approveCeilingCode(asMyDevice = true))

        val store = AgentGrantStore(file)
        val decisions = TrustedDeviceDecisions(store, FakeReplySender())
        store.recordRequest(
            nodeId = "phone-8",
            agentKey = "dsh",
            wsId = null,
            access = AgentGrantAccess.OWNER,
            reason = null,
            at = 1L,
        )
        decisions.approve(store.snapshot().requests.single(), asMyDevice = false)
        assertEquals(
            approveCeilingCode(asMyDevice = false),
            store.snapshot().grants.single().access,
            "卡片承诺的封顶与实际授出的档位必须一致",
        )
    }

    @Test
    fun rejectRemovesPendingRequestAndSendsDeniedFrame() = runBlocking {
        val store = AgentGrantStore(file)
        val sender = FakeReplySender()
        val decisions = TrustedDeviceDecisions(store, sender)
        store.recordRequest(nodeId = "phone-3", agentKey = "dsh", wsId = "ws-1", access = null, reason = null, at = 1L)
        val request = store.snapshot().requests.single()

        val outcome = decisions.reject(request)

        assertTrue(outcome is GrantReplyOutcome.Sent)
        assertTrue(store.snapshot().requests.isEmpty(), "拒绝后待办必须摘掉")
        assertTrue(store.snapshot().grants.isEmpty(), "拒绝不产生授权")
        // 「沉默 != 拒绝」：不告诉对面，手机侧会永远停在「已申请待批」。
        assertEquals(AgentGrantStates.DENIED, sender.sent.single().state)
    }

    @Test
    fun revokeClearsLedgerAndSendsRevokedFrame() = runBlocking {
        val store = AgentGrantStore(file)
        val sender = FakeReplySender()
        val decisions = TrustedDeviceDecisions(store, sender)
        store.grant(
            nodeId = "phone-4",
            scope = AgentGrantScopes.ALL,
            agentKey = null,
            grantedBy = "test",
            at = 1L,
        )

        val outcome = decisions.revoke("phone-4")

        assertTrue(outcome is GrantReplyOutcome.Sent)
        assertTrue(store.snapshot().grants.isEmpty(), "撤销必须清空该设备的所有授权")
        assertEquals(AgentGrantStates.REVOKED, sender.sent.single().state)
    }

    @Test
    fun notWiredSenderReportsFailureInsteadOfSuccess() = runBlocking {
        val decision = GrantDecision(nodeId = "phone-5", state = AgentGrantStates.APPROVED, scope = AgentGrantScopes.ALL)
        val outcome = GrantReplySender.NotWired.send(decision)
        assertTrue(outcome is GrantReplyOutcome.Failed, "没接线时必须如实报失败：$outcome")
    }
}
