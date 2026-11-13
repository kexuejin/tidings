// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.domain

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.makeEnvelope
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.domain.model.ToolApprovalRuleUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.platform.MdnsAdvertiser
import com.kapp.tenon.platform.MdnsBrowser
import com.kapp.tenon.platform.NetworkMonitor
import com.kapp.tenon.platform.NetworkSnapshot
import com.kapp.tenon.platform.Transport
import com.kapp.tenon.testutil.awaitUntil
import com.kapp.tenon.domain.udpTestTransport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 免批规则读撤（本端增补 cap `atr1`）的**设备侧域层**判据：
 *
 * 1. 入站快照**整键覆盖**（合并会复活宿主刚撤掉的行 —— 对账只有快照一个来源）；
 * 2. 撤销**乐观翻行按身份四元组**（快照行带 grantedAt/grantedBy，构造的撤销请求不带
 *    —— 全对象等值会一个都删不掉，这条就是那次真 bug 的钉子）；
 * 3. 发送失败**回滚原行**（不丢 grantedAt/grantedBy —— 界面那行的时刻与「谁批的」
 *    是排查线索，回滚一个影子行等于抹掉证据）；
 * 4. 门控 `atr1`：对端不声明 = 0 且一个行都不动（对旧宿主的白等不发生）。
 *
 * agentNode 侧的就地应答（读回快照 / 撤后回新快照）在
 * `agentNode` 的 `ControlPlaneToolRulesTest`；协议字节在 `:core` 的 golden vectors。
 */
class RelayControllerToolApprovalRulesTest {

    private lateinit var scope: CoroutineScope
    private lateinit var agentRuntime: NodeRuntime
    private lateinit var controller: RelayController

    private val clientNodeId = "11111111-0000-4000-8000-000000000001"
    private val agentNodeId = "22222222-0000-4000-8000-000000000002"
    private val legacyNodeId = "33333333-0000-4000-8000-000000000003"

    private val row = ToolApprovalRuleUi(
        nodeId = agentNodeId,
        tool = "bash",
        agentKey = "dsh-main",
        wsId = null,
        grantedAt = 1_000L,
        grantedBy = agentNodeId,
    )

    /** 注一份单行快照（与线上同一条路：handleOnAgentToolRulesList 就是入站唯一入口）。 */
    private fun injectSnapshot(at: Long = nowMillis()) {
        controller.messagesCtl.handleOnInbound(
            makeEnvelope(
                type = MsgTypes.AGENT_TOOL_RULES_LIST,
                id = "env-rules-$at",
                from = agentNodeId,
                ts = at,
                payload = Codec.agentToolRulesListPayload(
                    total = 1,
                    rules = listOf(
                        com.kapp.tenon.core.protocol.AgentToolRuleRowPayload(
                            nodeId = row.nodeId,
                            tool = row.tool,
                            agentKey = row.agentKey,
                            wsId = row.wsId,
                            grantedAt = row.grantedAt,
                            grantedBy = row.grantedBy,
                        ),
                    ),
                    at = at,
                ),
            ),
        )
    }

    /**
     * 读**根状态**（切片是 WhileSubscribed —— 没有收集者时 `.value` 是过时的初值；
     * 生产路径上界面订阅着它，所以 [RelayToolApprovalsSource] 读 slice 是对的）。
     */
    private fun rulesOnController(): List<ToolApprovalRuleUi> =
        controller.state.value.toolApprovalRules[agentNodeId].orEmpty()

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

        controller = RelayController(
            platform = InMemoryAppPlatform(
                networkMonitor = StaticNetworkMonitor,
                mdnsAdvertiser = NoopAdvertiser,
                mdnsBrowser = NoopBrowser,
            ),
            store = ToolRulesTestSettingsStore(
                clientNodeId = clientNodeId,
                manualPeers = listOf(ManualPeerEntry("127.0.0.1", agentRuntime.boundPort)),
            ),
            scope = scope,
            version = "relay-test",
        )
        controller.start()

        awaitUntil("客户端与 Agent 完成双向握手（撤销走真实发送分支需要它）") {
            val peer = peer(agentNodeId, controller.state.value.toNodeSlice(), controller.state.value.toSettingsSlice())
            peer != null && peer.online &&
                controller.runtime?.peerDeclares(agentNodeId, MsgTypes.CAP_AGENT_TOOL_RULES) == true
        }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { agentRuntime.stop() }
        scope.cancel()
    }

    @Test
    fun inboundSnapshotLandsKeyedAndOverwritesWhole() {
        injectSnapshot()
        assertEquals(listOf(row), rulesOnController(), "快照按宿主分键落位")

        // 第二份快照**没有**这行 ⇒ 整键覆盖后行消失（增量合并会复活宿主刚撤掉的行）。
        controller.messagesCtl.handleOnInbound(
            makeEnvelope(
                type = MsgTypes.AGENT_TOOL_RULES_LIST,
                id = "env-rules-empty",
                from = agentNodeId,
                ts = nowMillis(),
                payload = Codec.agentToolRulesListPayload(total = 0, rules = emptyList(), at = nowMillis()),
            ),
        )
        assertEquals(emptyList(), rulesOnController(), "整键覆盖：新快照说什么就是什么")
    }

    @Test
    fun revokeFlipsTheRowByIdentityAndDeliversTheFrame() {
        injectSnapshot()
        // 撤销请求带的是**影子行**（界面缝的 ToolApprovalRow 没有 grantedBy/grantedAt ——
        // 适配层构造的撤销请求就是不带这两个字段的）。全对象等值在这里会一个都删不掉
        // （快照行带着 grantedAt/grantedBy）—— 这条钉的就是那个真 bug。
        val shadow = row.copy(grantedAt = null, grantedBy = null)
        val removed = controller.revokeToolRule(agentNodeId, shadow)
        assertEquals(1, removed, "按身份四元组匹配：影子行也能对上快照里那一条")
        assertEquals(emptyList(), rulesOnController(), "乐观翻行立即生效（区块的「这一下必须立刻可见」）")
    }

    @Test
    fun revokeRollsBackTheOriginalRowWhenSendFails() = runBlocking {
        injectSnapshot()
        // 掐断发送（接缝，理由见 toolRulesRevokeSenderForTest 的 KDoc：真实传输造不出
        // 稳定的失败形态）⇒ 回滚必须把**原行**（带 grantedAt/grantedBy）放回来 ——
        // 回滚一个影子行等于抹掉「谁批的、什么时候批的」这条排查线索。
        controller.agentCtl.toolRulesRevokeSenderForTest = { _, _ -> false }
        try {
            val removed = controller.revokeToolRule(agentNodeId, row.copy(grantedAt = null, grantedBy = null))
            assertEquals(1, removed, "翻行发生在发送之前（乐观）")
            awaitUntil("发送失败后放回的是**原行**（带 grantedAt/grantedBy —— 回滚影子行等于抹掉排查线索）") {
                rulesOnController() == listOf(row)
            }
        } finally {
            controller.agentCtl.toolRulesRevokeSenderForTest = null
        }
    }

    @Test
    fun failedReadIsMarkedAndSnapshotArrivalClearsIt() = runBlocking {
        // 读送不出去（接缝）⇒ 记账；快照一到 ⇒ 摘掉。界面据此区分「读不到」与「没有规则」。
        controller.agentCtl.toolRulesReadSenderForTest = { _ -> false }
        try {
            controller.requestToolRules(agentNodeId)
            awaitUntil("读失败被记账") {
                agentNodeId in controller.state.value.toolRulesReadFailed
            }
        } finally {
            controller.agentCtl.toolRulesReadSenderForTest = null
        }
        // 同一宿主的快照到达（空表也算）⇒ 摘除。
        controller.messagesCtl.handleOnInbound(
            makeEnvelope(
                type = MsgTypes.AGENT_TOOL_RULES_LIST,
                id = "env-rules-clear",
                from = agentNodeId,
                ts = nowMillis(),
                payload = Codec.agentToolRulesListPayload(total = 0, rules = emptyList(), at = nowMillis()),
            ),
        )
        awaitUntil("快照到达即摘读失败记账") {
            agentNodeId !in controller.state.value.toolRulesReadFailed
        }
        assertEquals(emptyList(), controller.state.value.toolApprovalRules[agentNodeId].orEmpty())
    }

    @Test
    fun revokeWithoutTheCapMovesNothing() {
        // 一台**声明不了 atr1** 的对端（旧版 caps）+ 一个独立客户端：握手完成后门控
        // 依然拦 —— 门的判据是 caps，不是在线状态。行不动、帧不发。
        runBlocking {
            val legacyRuntime = NodeRuntime(
                messengerTransportFactory = udpTestTransport(),
                profile = Profile(
                    nodeId = legacyNodeId,
                    nick = "旧版设备",
                    company = "",
                    dept = "",
                    team = "",
                    avatar = -1,
                    profileRev = 0,
                    host = "legacy-host",
                    platform = "linux",
                    tcpPort = 0,
                    ver = "0.61.1",
                    caps = MsgTypes.MOBILE_CAPS - MsgTypes.CAP_AGENT_TOOL_RULES,
                ),
                udpPort = 0,
                bindHost = "127.0.0.1",
            )
            legacyRuntime.start(scope)
            try {
                val legacyController = RelayController(
                    platform = InMemoryAppPlatform(
                        networkMonitor = StaticNetworkMonitor,
                        mdnsAdvertiser = NoopAdvertiser,
                        mdnsBrowser = NoopBrowser,
                    ),
                    store = ToolRulesTestSettingsStore(
                        clientNodeId = "44444444-0000-4000-8000-000000000004",
                        manualPeers = listOf(ManualPeerEntry("127.0.0.1", legacyRuntime.boundPort)),
                    ),
                    scope = scope,
                    version = "relay-test",
                )
                legacyController.start()
                awaitUntil("客户端与旧版设备握手完成") {
                    val p = peer(legacyNodeId, legacyController.state.value.toNodeSlice(), legacyController.state.value.toSettingsSlice())
                    p != null && p.online
                }
                // 给旧对端键下注一份快照（入站唯一入口，与线上同一条路）。
                legacyController.messagesCtl.handleOnInbound(
                    makeEnvelope(
                        type = MsgTypes.AGENT_TOOL_RULES_LIST,
                        id = "env-rules-legacy",
                        from = legacyNodeId,
                        ts = nowMillis(),
                        payload = Codec.agentToolRulesListPayload(
                            total = 1,
                            rules = listOf(
                                com.kapp.tenon.core.protocol.AgentToolRuleRowPayload(
                                    nodeId = legacyNodeId,
                                    tool = "bash",
                                    grantedAt = 1_000L,
                                ),
                            ),
                            at = nowMillis(),
                        ),
                    ),
                )
                val legacyRule = ToolApprovalRuleUi(
                    nodeId = legacyNodeId, tool = "bash", agentKey = null,
                    wsId = null, grantedAt = 1_000L, grantedBy = null,
                )
                assertEquals(
                    0,
                    legacyController.revokeToolRule(legacyNodeId, legacyRule),
                    "对端不声明 atr1 ⇒ 门控拦下，返回 0",
                )
                assertEquals(
                    1,
                    legacyController.state.value.toolApprovalRules[legacyNodeId].orEmpty().size,
                    "门控没过时一个行都不动",
                )
            } finally {
                legacyRuntime.stop()
            }
        }
    }
    private class ToolRulesTestSettingsStore(
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
        override fun save(settings: RelaySettings) {
            current = settings
        }
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

}

