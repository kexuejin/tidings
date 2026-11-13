// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.settings

import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.domain.SettingsStore
import com.kapp.tenon.domain.model.ToolApprovalRuleUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.platform.MdnsAdvertiser
import com.kapp.tenon.platform.MdnsBrowser
import com.kapp.tenon.platform.NetworkMonitor
import com.kapp.tenon.platform.NetworkSnapshot
import com.kapp.tenon.platform.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 跨端免批表缝（[RelayToolApprovalsSource]）的投影判据 —— 撤与读的**域层**行为在
 * `:app` 的 `RelayControllerToolApprovalRulesTest`（有真传输），agentNode 的就地应答在
 * `ControlPlaneToolRulesTest`。这里只钉三件缝自己的事：
 *
 * 1. `snapshotRows` 读的是**注入的观察面**（宿主 nodeId 键下的那份快照），
 *    宿主没报 `grantedAt` 时兜 0（显示层当"未知时刻"，不猜）；
 * 2. `addChangeListener` 只在**这一份宿主的 rules 等值变化**时发
 *    （别的宿主的快照变化不该让这个区块白刷）；
 * 3. 撤销经 controller 转发；本机没有运行时 = 0（界面那行回执是「没撤掉」，不是假成功）。
 */
class RelayToolApprovalsSourceTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: RelayController
    private lateinit var sliceFlow: MutableStateFlow<AgentSlice>

    private val hostNodeId = "22222222-0000-4000-8000-000000000002"
    private val otherNodeId = "33333333-0000-4000-8000-000000000003"

    private val row = ToolApprovalRuleUi(
        nodeId = hostNodeId,
        tool = "bash",
        agentKey = "dsh-main",
        wsId = null,
        grantedAt = null,
        grantedBy = hostNodeId,
    )

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        sliceFlow = MutableStateFlow(AgentSlice())
        controller = RelayController(
            platform = InMemoryAppPlatform(
                networkMonitor = StaticNetworkMonitor,
                mdnsAdvertiser = NoopAdvertiser,
                mdnsBrowser = NoopBrowser,
            ),
            store = SettingsStoreFixture,
            scope = scope,
            version = "relay-test",
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun source(): RelayToolApprovalsSource =
        RelayToolApprovalsSource(controller, hostNodeId, scope, sliceFlow = sliceFlow)

    @Test
    fun snapshotRowsProjectTheHostKeyedSlice() {
        sliceFlow.value = AgentSlice(toolApprovalRules = mapOf(hostNodeId to listOf(row)))
        val rows = source().snapshotRows()
        assertEquals(1, rows.size)
        assertEquals(hostNodeId, rows[0].nodeId)
        assertEquals("bash", rows[0].tool)
        assertEquals("dsh-main", rows[0].agentKey)
        assertEquals(0L, rows[0].grantedAt, "宿主没报 grantedAt ⇒ 兜 0（未知时刻），不猜时间")
        // 别的宿主的快照不进这一份 source 的视野。
        sliceFlow.value = AgentSlice(toolApprovalRules = mapOf(otherNodeId to listOf(row.copy(nodeId = otherNodeId))))
        assertTrue(source().snapshotRows().isEmpty(), "按宿主分键：别人的快照不串台")
    }

    @Test
    fun changeListenerFiresOnlyOnThisHostsRuleChanges() {
        runBlocking {
        val fired = MutableStateFlow(0)
        val off = source().addChangeListener { fired.value++ }
        try {
            sliceFlow.value = AgentSlice(toolApprovalRules = mapOf(hostNodeId to listOf(row)))
            withTimeout(10_000) {
                fired.first { it >= 1 }
            }
            val afterFirst = fired.value
            // 别的宿主的快照变化不该发（区块不白刷）。「不发」没法直接等 —— 用**确定会
            // 发生的事件**做探针（AGENTS §4：不用固定 delay 赌同步）：紧跟着发一次这份
            // 宿主的真变更，若无关变更也发了，探针到达时计数必然是 afterFirst + 2。
            sliceFlow.value = AgentSlice(
                toolApprovalRules = mapOf(
                    hostNodeId to listOf(row),
                    otherNodeId to listOf(row.copy(nodeId = otherNodeId)),
                ),
            )
            sliceFlow.value = AgentSlice(toolApprovalRules = mapOf(hostNodeId to emptyList()))
            withTimeout(10_000) {
                assertEquals(
                    afterFirst + 1,
                    fired.first { it > afterFirst },
                    "探针只应带来一次通知 —— 别的宿主的快照变化发了通知就会被这条抓出来",
                )
            }
        } finally {
            off()
        }
        }
    }

    @Test
    fun readStateDistinguishesNotLoadedFromEmpty() {
        // 快照没到过 ≠ 没有规则：界面的空表文案只在「快照到过且为空」时说前半句。
        assertTrue(!source().hasLoadedSnapshot(), "快照没到过 ⇒ hasLoadedSnapshot=false")
        assertTrue(!source().lastReadFailed(), "没读过 ⇒ 不算读失败")
        // 读失败记账（切片里有这个宿主）⇒ 界面画「读不到」。
        sliceFlow.value = AgentSlice(toolRulesReadFailed = setOf(hostNodeId))
        assertTrue(source().lastReadFailed(), "读失败记账被投影出来")
        assertTrue(!source().hasLoadedSnapshot(), "读失败 ≠ 快照到过")
        // 快照到了（哪怕空表）⇒ 两个状态都翻过去。
        sliceFlow.value = AgentSlice(toolApprovalRules = mapOf(hostNodeId to emptyList()))
        assertTrue(source().hasLoadedSnapshot(), "空表也是「快照到过」")
        assertTrue(!source().lastReadFailed(), "快照到达即摘读失败记账")
    }

    @Test
    fun revokeWithoutRuntimeReturnsZeroAndChangesNothing() {
        sliceFlow.value = AgentSlice(toolApprovalRules = mapOf(hostNodeId to listOf(row)))
        // 本机没有运行时（未连网）⇒ 域层门控返回 0，行原地不动 —— 界面回执说「没撤掉」。
        assertEquals(0, source().revokeExactly(nodeId = hostNodeId, agentKey = "dsh-main", wsId = null, tool = "bash"))
        assertEquals(1, source().snapshotRows().size, "没发出去就没有任何翻行")
    }
}

private object SettingsStoreFixture : SettingsStore {
    var current: RelaySettings? = RelaySettings(nodeId = "11111111-0000-4000-8000-000000000001", nick = "我的手机")
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
