// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.domain

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.AgentAckKinds
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.makeEnvelope
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.domain.model.AgentApprovalDecision
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.platform.MdnsAdvertiser
import com.kapp.tenon.platform.MdnsBrowser
import com.kapp.tenon.platform.NetworkMonitor
import com.kapp.tenon.platform.NetworkSnapshot
import com.kapp.tenon.platform.Transport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import com.kapp.tenon.testutil.awaitUntil
import com.kapp.tenon.domain.udpTestTransport

/**
 * 审批处置（`respondApproval` → `agent-ack`）的**发送侧诚实性**：
 *
 * 1. 对端声明了 `agq1`、ack 发送抛异常 ⇒ 本地「已批准」必须**回滚**成待审批 + 横幅
 *    （旧实现 `runCatching` 吞掉异常，本地挂着对端永远不知道的「已批准」——
 *    「发送成功 ≠ 对方知道」在审批入口的同款现场）；
 * 2. 同一握手下 ack 正常送达 ⇒ 状态保持、无横幅（对照组：证明回滚只属于异常路径）。
 *
 * 为什么要有 [RelayControllerAgentComponent.ackSenderForTest]：`sendUserMessage` 把
 * 可预期的失败全折进返回值（SENT / QUEUED），真实传输造不出「抛异常」这条路；
 * 注入接缝让回滚不变量可以被钉住（变不红的测试等于没测）。
 * 无 CAP（不声明 agq1）分支与无运行时分支在
 * [RelayControllerAgentP1Test] 里覆盖（不需要真实握手）。
 */
class RelayControllerApprovalResponseTest {

    private lateinit var scope: CoroutineScope
    private lateinit var agentRuntime: NodeRuntime
    private lateinit var controller: RelayController

    private val clientNodeId = "11111111-0000-4000-8000-000000000001"
    private val agentNodeId = "22222222-0000-4000-8000-000000000002"

    @BeforeTest
    fun setUp() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        agentRuntime = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = Profile.agent(
                nodeId = agentNodeId,
                nick = "Tenon Agent Bot",
                host = "test-agent-host",
                ver = "relay-0.1.0",
                platform = "mac",
            ),
            udpPort = 0,
            bindHost = "127.0.0.1",
        )
        agentRuntime.start(scope)

        val store = ApprovalTestSettingsStore(
            clientNodeId = clientNodeId,
            manualPeers = listOf(ManualPeerEntry("127.0.0.1", agentRuntime.boundPort)),
        )
        controller = RelayController(
            platform = InMemoryAppPlatform(
                networkMonitor = StaticNetworkMonitor,
                mdnsAdvertiser = NoopAdvertiser,
                mdnsBrowser = NoopBrowser,
            ),
            store = store,
            scope = scope,
            version = "relay-test",
        )
        controller.start()

        awaitUntil("客户端与 Agent 完成双向握手（对方要声明 agq1 才走真实发送分支）") {
            val peer = peer(agentNodeId, controller.state.value.toNodeSlice(), controller.state.value.toSettingsSlice())
            peer != null && peer.online &&
                controller.runtime?.peerDeclares(agentNodeId, MsgTypes.CAP_AGENT_QUEUE) == true
        }

        // **授权夹具，不是噪音**：客户端的审批预检已经 fail-closed（与 :core 同一判据），
        // 没有授权记录时 respondApproval 在预检处就返回，根本走不到"发送/回滚"这两条被测路径。
        // 真实产品里这条授权由宿主用 agent-grant 帧同步；这里用同一型帧注入，与线上同一条路。
        controller.messagesCtl.handleOnInbound(
            makeEnvelope(
                type = MsgTypes.AGENT_GRANT,
                id = "env-grant-owner",
                from = agentNodeId,
                ts = nowMillis(),
                payload = Codec.agentGrantPayload(state = "approved", scope = "all", access = "owner"),
            ),
        )
        awaitUntil("授权落到本端状态（否则下面两条测试都会在预检处静默返回）") {
            controller.state.value.agentGrants.isNotEmpty()
        }
    }

    @AfterTest
    fun tearDown() = runBlocking {
        controller.agentCtl.ackSenderForTest = null
        controller.stop()
        agentRuntime.stop()
        scope.cancel()
    }

    private fun injectApproval(approvalId: String) {
        controller.messagesCtl.handleOnInbound(
            makeEnvelope(
                type = MsgTypes.AGENT_APPROVAL,
                id = "env-$approvalId",
                from = agentNodeId,
                ts = nowMillis(),
                payload = Codec.agentApprovalPayload(
                    approvalId = approvalId,
                    sessionId = "sess-1",
                    tool = "bash",
                    summary = "rm -rf /tmp/cache",
                    expiresAt = nowMillis() + 60_000L,
                ),
            ),
        )
        assertEquals("pending", controller.state.value.agentApprovals[approvalId]?.status)
    }

    @Test
    fun ackSendFailureRollsBackDecisionAndShowsBanner() = runBlocking {
        injectApproval("appr-rollback")
        controller.agentCtl.ackSenderForTest = { _, _, _ ->
            throw IllegalStateException("注入：发送 agent-ack 抛异常")
        }

        controller.respondApproval("appr-rollback", true)

        // 乐观翻状态发生在调用线程，回滚 + 横幅发生在发送协程里 —— 轮询等它们发生。
        // 必须等**横幅**（onFailure 的最后一步）而不是只等状态回滚：两步之间有窗口，
        // 只等回滚会在 warn 落地前抢跑断言（本测试第一版就在这里偶发红过）。
        awaitUntil("本地状态回滚成待审批且横幅出现") {
            controller.state.value.agentApprovals["appr-rollback"]?.status == "pending" &&
                controller.state.value.banner?.message == BannerMessage.AgentAckSendFailed
        }
        assertEquals("pending", controller.state.value.agentApprovals["appr-rollback"]?.status)
    }

    @Test
    fun ackDeliveredKeepsDecisionWithoutBanner() = runBlocking {
        injectApproval("appr-delivered")
        // 「送达之后不许回滚」是一条**否定断言**：过去用 `delay(200)` 等一会儿再说"没回滚"，
        // 那是在赌发送协程已经跑完 —— 慢机器上会抢跑，快机器上白等 200ms。
        // 现在把发送那一腿换成**记账接缝**：它正常返回（永不抛）并把"被调用过"置位。
        // 于是因果被钉死：回滚只发生在发送抛错之后，发送没抛 ⇒ 回滚不可达；而 gate 证明
        // 这条腿真的被走到了（不是压根没发）。真实 runtime 上的 ack 送达由同目录的
        // RelayControllerAgentSessionTest 覆盖（那里等的是对端 inbox 里真的出现消息）。
        val sent = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        controller.agentCtl.ackSenderForTest = { _, _, _ ->
            sent.incrementAndGet()
            gate.complete(Unit)
        }

        controller.respondApproval("appr-delivered", true)

        // 等条件（发送腿被调用）而不是等时间；gate 永不完成时 withTimeout 会把测试判红。
        kotlinx.coroutines.withTimeout(5_000) { gate.await() }
        assertEquals(1, sent.get(), "ack 应当恰好发一次")
        assertEquals("approved", controller.state.value.agentApprovals["appr-delivered"]?.status)
        assertNull(controller.state.value.banner, "正常送达不该有横幅")
    }

    /**
     * 撤销「本会话免审」：客户端要发 `revoke_session`（不是再发一次 allow），并且只有在
     * ack 真发出去之后才把本地状态改成 revoked。
     *
     * 为什么用真实夹具：撤销走的是 `peerDeclares(CAP_AGENT_QUEUE)` 那条分支，
     * 没有握手就发不出去 —— 而这条链路正是"按钮存在但永远失败"的高发区。
     */
    @Test
    fun revokeSessionApprovalSendsRevokeAckAndFlipsState() = runBlocking {
        injectApproval("appr-revoke")
        // 先让它成为「会话免审」：这是对端自己的事实，经 agent-ack 回来推进本地状态。
        controller.messagesCtl.handleOnInbound(
            makeEnvelope(
                type = MsgTypes.AGENT_ACK,
                id = "env-ack-allow-session",
                from = agentNodeId,
                ts = nowMillis(),
                payload = Codec.agentAckPayload(ackOf = "appr-revoke", kind = "allow_session"),
            ),
        )
        awaitUntil("审批变成会话免审") {
            controller.state.value.agentApprovals["appr-revoke"]?.status == "allow_session"
        }

        val kinds = java.util.concurrent.CopyOnWriteArrayList<String>()
        controller.agentCtl.ackSenderForTest = { _, ackOf, kind -> kinds += ackOf + ":" + kind }

        controller.agentCtl.handleRevokeSessionApproval("sess-1")

        awaitUntil("撤销 ack 发出且本地状态落到 revoked") {
            kinds.isNotEmpty() && controller.state.value.agentApprovals["appr-revoke"]?.status == "revoked"
        }
        assertEquals(listOf("appr-revoke:revoke_session"), kinds.toList(), "撤销发的必须是一条 revoke_session ack")
    }

    /**
     * 「始终允许」（`always`）：客户端要发的那一条回执是 `kind=always`，**不是** `approved`。
     *
     * 为什么这条判据值钱：宿主侧只有收到 `always` 才会记免批规则（按 tool 名、跨会话）。
     * 客户端若把它悄悄降级成 `approved`，用户点的是「以后别再问」，得到的却是
     * 「这次批了、下次还问」—— 而界面上那一下看起来一模一样，事后也没有任何痕迹。
     * 发送侧不校验 `kind` 的取值域，这个差别只有线上那一条字符串能证明。
     */
    @Test
    fun alwaysDecisionSendsAlwaysAckAndApprovesTheCurrentRequest() = runBlocking {
        injectApproval("appr-always")
        val kinds = java.util.concurrent.CopyOnWriteArrayList<String>()
        controller.agentCtl.ackSenderForTest = { _, ackOf, kind -> kinds += ackOf + ":" + kind }

        controller.respondApproval("appr-always", AgentApprovalDecision.ALLOW_ALWAYS)

        awaitUntil("always 回执发出") { kinds.isNotEmpty() }
        assertEquals(
            listOf("appr-always:always"),
            kinds.toList(),
            "「始终允许」发的必须是一条 always ack —— 宿主靠它记免批规则",
        )
        // 本端如实显示「已批准」：用户点的这一下确实批准了当前请求；
        // 「以后不再问」是宿主侧的事实，本端没有那张表，也不发明一个自己并不掌握的状态。
        assertEquals("approved", controller.state.value.agentApprovals["appr-always"]?.status)
    }

    /**
     * 四态取值里的 `kind` 必须都在 [`AgentAckKinds`] 的白名单里。
     *
     * 这不是仪式：`allow_session` / `revoke_session` 曾经「在用但不在白名单」，于是回执
     * 在接收侧被整帧拒收，界面却显示已生效（本仓付过一次的代价）。枚举引用常量能防重抄，
     * 但防不住「常量本身漏进白名单」—— 那一条由这里钉住。
     */
    @Test
    fun everyDecisionKindIsInTheAckWhitelist() {
        AgentApprovalDecision.entries.forEach { decision ->
            kotlin.test.assertTrue(
                AgentAckKinds.isKnown(decision.ackKind),
                "取值 ${decision.name} 的 kind=「${decision.ackKind}」不在 AgentAckKinds.VALUES 里 —— " +
                    "对端会整帧拒收，而发送侧不会报任何错",
            )
        }
    }

    private class ApprovalTestSettingsStore(
        clientNodeId: String,
        manualPeers: List<ManualPeerEntry>,
    ) : SettingsStore {
        var current: RelaySettings? = RelaySettings(
            nodeId = clientNodeId,
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
            manualPeers = manualPeers,
        )
        override fun load(): RelaySettings? = current
        override fun save(settings: RelaySettings) { current = settings }
    }

    private object StaticNetworkMonitor : NetworkMonitor {
        override fun rawSnapshot(): NetworkSnapshot = NetworkSnapshot(
            online = true,
            transport = Transport.Wifi,
            localAddresses = listOf("127.0.0.1"),
            vpnActive = false,
        )
        override fun observe(onChange: (NetworkSnapshot) -> Unit): () -> Unit = {}
    }

    private object NoopAdvertiser : MdnsAdvertiser {
        override suspend fun start(serviceName: String, port: Int): Boolean = true
        override fun stop() = Unit
    }

    private object NoopBrowser : MdnsBrowser {
        override fun startBrowsing(
            onServiceFound: (String, String, Int) -> Unit,
            onServiceLost: (String) -> Unit,
        ) = Unit
        override fun stopBrowsing() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
