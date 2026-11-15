// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.QuestStatus
import com.kapp.tenon.core.protocol.toProtocolAgentState
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.testutil.TestPorts
import com.kapp.tenon.testutil.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 多端状态增量对账（Phase 2）的双节点验收：长时间离线的设备亮屏后，
 * **只靠一帧清册**把落后的 quest 卡片推进到宿主现役档位。
 *
 * 走真实 UDP 回环（`RelayController` 两实例 + 手动节点）——「帧真的过线了」
 * 是判据的一半（AGENTS §4：绕过编解码的对账测试是假绿）。
 */
class QuestSyncMultiDeviceTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(udpPort: Int, tcpPort: Int, store: MessageStore): RelayController =
        RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(
                RelaySettings.firstRun().copy(
                    udpPort = udpPort,
                    tcpPort = tcpPort,
                    persistMessages = false,
                    persistMessagesDecided = true,
                ),
            ),
            messageStore = store,
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )

    private fun questRow(nodeId: String, hostNodeId: String, rev: Long, payload: String?): MessageUi =
        MessageUi(
            id = "quest-row-$nodeId-$rev",
            peerId = ConvId.ASSISTANT,
            mine = false,
            text = "",
            at = 1_700_000_000_000L,
            senderId = "agent:" + hostNodeId + ":assistant",
            questId = nodeId,
            questRev = rev,
            cardPayload = payload?.let { raw ->
                runCatching { MessageStore.json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            },
            agentState = if (rev >= 5) "COMPLETED" else "RUNNING",
        )

    @Test
    fun staleDeviceAdvancesToHostRevViaOneSyncPull() = runBlocking {
        val portA = TestPorts.freeUdpBase()
        val portB = TestPorts.freeUdpBase()
        val storeA = InMemoryMessageStore()
        val storeB = InMemoryMessageStore()
        val stale = controller(portA, portA + 1, storeA)
        val host = controller(portB, portB + 1, storeB)
        stale.start()
        host.start()
        try {
            awaitUntil("A 起来") { stale.state.value.network.phase.name == "Running" }
            awaitUntil("B 起来") { host.state.value.network.phase.name == "Running" }
            val selfA = stale.state.value.selfNodeId
            val selfB = host.state.value.selfNodeId
            stale.addManualPeer("127.0.0.1", portB.toString())
            host.addManualPeer("127.0.0.1", portA.toString())
            awaitUntil("A 看见 B") { stale.state.value.peers.any { it.nodeId == selfB } }
            awaitUntil("B 看见 A") { host.state.value.peers.any { it.nodeId == selfA } }

            // 档位：A 持 q-1@3（落后），B 持 q-1@5（现役）；q-2 只有 B 有（A 清单外
            // —— 本次对账按方案只对已知档位回，不发现新单）。
            val card5 = buildJsonObject { put("cardId", "q-1"); put("rev", 5); put("state", "COMPLETED") }.toString()
            val card3 = buildJsonObject { put("cardId", "q-1"); put("rev", 3) }.toString()
            storeA.save(mapOf(ConvId.ASSISTANT to listOf(questRow("q-1", selfB, 3, card3))))
            storeB.save(mapOf(ConvId.ASSISTANT to listOf(questRow("q-1", selfB, 5, card5))))

            val rowsBefore = storeA.load().values.sumOf { it.size }

            // 亮屏对账：A 报 [q-1@3]，B 仅回更新的快照页，A 原位推进。
            stale.questSyncPull()

            awaitUntil("A 的 q-1 必须推进到宿主现役 rev 5") {
                storeA.questCardRow("q-1")?.questRev == 5L
            }
            val advanced = storeA.questCardRow("q-1")!!
            assertEquals("COMPLETED", advanced.agentState, "状态随快照推进")
            assertNotNull(advanced.cardPayload)
            assertTrue(
                advanced.cardPayload!!.contains("\"rev\":5"),
                "载荷必须是 B 的现役整包，不是文本拼接",
            )
            assertEquals(
                rowsBefore,
                storeA.load().values.sumOf { it.size },
                "对账走原位推进（updateCardContent），绝不追加聊天行",
            )
            // 幂等：重复 pull（同档位）不再有任何变化。
            stale.questSyncPull()
            awaitUntil("对账完成") { storeA.questCardRow("q-1")?.questRev == 5L }
            assertEquals(5L, storeA.questCardRow("q-1")?.questRev)
        } finally {
            stale.stop()
            host.stop()
        }
    }
    /**
     * 便签翻面 rev 门（code review #3）：FAILED(rev=1) 翻面后，迟到的 RUNNING(rev=0)
     * 通知必须被同 quest rev 门丢弃——无门的翻面会把卡打回进行中并经 persistThreads
     * 覆盖 DB 的新 rev（deliverWithRetry 的重发每次都是新信封 id，传输去重拦不住）。
     * 真控制器直发（本地翻面路径与 wire 路径共用同一入口）。
     */
    @Test
    fun staleNoteNoticeDoesNotRegressTheFlippedCard(): Unit = runBlocking {
        val store = InMemoryMessageStore()
        // 本用例断言落 store——persist 开启（共享 helper 刻意 false，此处内联覆盖）。
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(
                RelaySettings.firstRun().copy(
                    udpPort = TestPorts.freeUdpBase(),
                    tcpPort = TestPorts.freeUdpBase() + 1,
                    persistMessages = true,
                    persistMessagesDecided = true,
                ),
            ),
            messageStore = store,
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
        controller.start()
        try {
            awaitUntil("起来") { controller.state.value.network.phase.name == "Running" }
            val self = controller.state.value.selfNodeId
            val hostId = "host-node-0001"
            // 派单便签（mine，带 clientToken，未绑定 quest）。
            val note = MessageUi(
                id = "note-1",
                peerId = ConvId.ASSISTANT,
                mine = true,
                text = "quesprob-retry",
                at = 1_700_000_000_000L,
                clientToken = "note-token-1",
            )
            // 派单便签的真实注入路径（机主本地追加，公开入口）。
            controller.appendMessage(note)
            // FAILED(rev=1) 翻面。
            controller.handleAssistantQuestNotice(
                com.kapp.tenon.core.protocol.AssistantQuestNotice(
                    questId = "q-retry", title = "quesprob-retry", status = QuestStatus.FAILED,
                    summary = "failed", convId = ConvId.ASSISTANT, clientToken = "note-token-1", rev = 1,
                ),
            )
            awaitUntil("FAILED 翻面落 store") {
                store.load().values.flatten().any { it.clientToken == "note-token-1" && it.questRev == 1L }
            }
            val flipped = store.load().values.flatten().first { it.clientToken == "note-token-1" }
            assertEquals(QuestStatus.FAILED.toProtocolAgentState(), flipped.agentState)
            assertEquals(1L, flipped.questRev)
            // 迟到 RUNNING(rev=0)：同 quest rev 门必须丢弃（卡保持 FAILED）。
            controller.handleAssistantQuestNotice(
                com.kapp.tenon.core.protocol.AssistantQuestNotice(
                    questId = "q-retry", title = "quesprob-retry", status = QuestStatus.RUNNING,
                    summary = "late", convId = ConvId.ASSISTANT, clientToken = "note-token-1", rev = 0,
                ),
            )
            awaitUntil("迟到 RUNNING 被 rev 门丢弃（卡保持 FAILED@1）") {
                val row = store.load().values.flatten().firstOrNull { it.clientToken == "note-token-1" }
                row != null && row.questRev == 1L && row.agentState == QuestStatus.FAILED.toProtocolAgentState()
            }
            val after = store.load().values.flatten().first { it.clientToken == "note-token-1" }
            assertEquals(QuestStatus.FAILED.toProtocolAgentState(), after.agentState)
            assertEquals(1L, after.questRev)
        } finally {
            // scope 由 @AfterTest 生命周期管理（同类其他用例同款）
        }

}

}
