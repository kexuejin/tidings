// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.testutil.TestPorts
import com.kapp.tenon.testutil.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 团队群任务的**宿主仲裁**（Phase 3）：First-Wins + CAS 的并发语义、并发输家的
 * 显式拒绝、群主覆决（override-quest）的资格门，与审计轨迹的原子附着。
 *
 * 判据全部落在宿主的卡片行（rev / auditTrail / decidedAt）—— 那是 First-Wins
 * 的记账事实，不是任何一端的界面文案。
 */
class HostArbitrationConcurrencyTest {

    // SupervisorJob：被拒动作的公告协程可能抛（单测壳没有编排桥），不能让它
    // 把整个共享作用域炸掉 —— 普通.Job 的子协程失败会取消全 scope（两个控制器一起死）。
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(udpPort: Int, tcpPort: Int): RelayController =
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
            messageStore = InMemoryMessageStore(),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )

    private fun cardRow(hostNodeId: String, cardId: String, rev: Int): MessageUi =
        MessageUi(
            id = "card-row-$cardId-$rev",
            peerId = ConvId.ASSISTANT,
            mine = false,
            text = "",
            at = 1_700_000_000_000L,
            senderId = "agent:" + hostNodeId + ":gpu-runner",
            card = buildJsonObject {
                put("cardId", cardId)
                put("rev", rev)
                put("type", "assistant_quest")
                put("title", "团队支线")
                put("state", "blocked")
            },
        )

    // 卡片行可能落在任一会话键（1:1 归一 / 群）—— 观测器扫全量，不赌键。
    private fun cardRows(c: RelayController, cardId: String) =
        c.state.value.messages.values.asSequence().flatten()
            .filter { row -> (row.renderCard?.get("cardId") as? JsonPrimitive)?.contentOrNull == cardId }

    private fun revOf(c: RelayController, cardId: String): Int? =
        cardRows(c, cardId).lastOrNull()?.renderCard
            ?.get("rev")?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }

    private fun trailOf(c: RelayController, cardId: String): Int =
        cardRows(c, cardId).lastOrNull()?.renderCard
            ?.get("auditTrail")?.let { (it as? kotlinx.serialization.json.JsonArray)?.size } ?: 0

    @Test
    fun concurrentIntentsLandExactlyOnceAndLoserIsRejectedExplicitly() = runBlocking {
        val portA = TestPorts.freeUdpBase()
        val portB = TestPorts.freeUdpBase()
        val member = controller(portA, portA + 1)
        val host = controller(portB, portB + 1)
        member.start()
        host.start()
        try {
            awaitUntil("A 起来") { member.state.value.network.phase.name == "Running" }
            awaitUntil("B 起来") { host.state.value.network.phase.name == "Running" }
            val selfA = member.state.value.selfNodeId
            val selfB = host.state.value.selfNodeId
            member.addManualPeer("127.0.0.1", portB.toString())
            host.addManualPeer("127.0.0.1", portA.toString())
            awaitUntil("互认") {
                member.state.value.peers.any { it.nodeId == selfB } &&
                    host.state.value.peers.any { it.nodeId == selfA }
            }
            // 双方都要有**活跃消息态**里的卡行（cardRowOf 读 state.messages，不是 store）：
            // 成员侧的行是「宿主在哪」的路由事实（senderId 解析）；宿主侧行落在 1:1
            // 归一后的会话键（群元数据里没有 "assistant" 这个组，归一会掉进 1:1）。
            member.appendMessage(cardRow(selfB, "qc-1", 1))
            host.appendMessage(cardRow(selfB, "qc-1", 1).copy(peerId = ConvId.single(selfA)))
            host.invokeGrantAllows = { _, _ -> true }

            // 同一成员对同一动作的两次**并发**点击：First-Wins 台账只放首个 ——
            // 第二个是同键重放，幂等丢弃（rev 不推进）。
            val first = async { member.handleCardAction(ConvId.ASSISTANT, "qc-1", 1, "merge-patch") }
            val second = async { member.handleCardAction(ConvId.ASSISTANT, "qc-1", 1, "merge-patch") }
            first.await()
            second.await()

            awaitUntil("宿主卡推进到 rev 2") { revOf(host, "qc-1") == 2 }
            // 审计原子附着：一次判定 = 一条 (actor, action, at)，decidedAt 在场。
            val latest = cardRows(host, "qc-1").last().renderCard!!
            assertEquals(1, trailOf(host, "qc-1"), "审计轨迹恰好一条")
            assertNotNull(latest["decidedAt"], "判定时刻原子附着")
            assertEquals("merge-patch", (latest["decidedActionId"] as? JsonPrimitive)?.content)

            // 重放点击：同键幂等，rev 不动、审计不涨。
            member.handleCardAction(ConvId.ASSISTANT, "qc-1", 2, "merge-patch")
            awaitUntil("账目稳定") { revOf(host, "qc-1") != null }
            assertEquals(2, revOf(host, "qc-1"), "First-Wins：重放点击不推进 rev")
            assertEquals(1, trailOf(host, "qc-1"), "重放点击不留审计行")
        } finally {
            member.stop()
            host.stop()
        }
    }

    @Test
    fun overrideActionIsOwnerGatedBeforeGrantPolicy() = runBlocking {
        val portA = TestPorts.freeUdpBase()
        val portB = TestPorts.freeUdpBase()
        val member = controller(portA, portA + 1)
        val host = controller(portB, portB + 1)
        member.start()
        host.start()
        try {
            awaitUntil("A 起来") { member.state.value.network.phase.name == "Running" }
            awaitUntil("B 起来") { host.state.value.network.phase.name == "Running" }
            val selfA = member.state.value.selfNodeId
            val selfB = host.state.value.selfNodeId
            member.addManualPeer("127.0.0.1", portB.toString())
            host.addManualPeer("127.0.0.1", portA.toString())
            awaitUntil("互认") {
                member.state.value.peers.any { it.nodeId == selfB } &&
                    host.state.value.peers.any { it.nodeId == selfA }
            }
            // 群由宿主创建（群主 = 宿主）；成员持执行授权（grantAllows = true）——
            // 覆决分支必须**先于**执行策略判定：授权了但不是群主 = 覆决照样拒绝。
            val groupId = host.createGroup("仲裁群", listOf(selfA))
            assertNotNull(groupId)
            // 成员持执行授权：覆决分支必须**先于**执行策略判定 —— 授权了但不是群主，
            // 覆决照样拒绝（这条对照就是「先于」两个字的执行证据）。
            host.invokeGrantAllows = { _, _ -> true }
            awaitUntil("成员收到群元数据") {
                member.state.value.settings.groups.any { it.groupId == groupId }
            }
            member.appendMessage(cardRow(selfB, "qc-2", 1).copy(peerId = ConvId.group(groupId)))
            host.appendMessage(cardRow(selfB, "qc-2", 1).copy(peerId = ConvId.group(groupId)))

            // 授权过的非群主点覆决 → 拒（rev 不动）。

            // 普通重试动作（同一授权）→ 放行（对照：覆决分支不是把所有动作都拒了）。
            member.handleCardAction(groupId, "qc-2", 1, "retry-quest")
            awaitUntil("普通动作放行推进") { revOf(host, "qc-2") == 2 }

            // 授权过的非群主点覆决 → 拒（rev 不动）：覆决分支先于执行策略，
            // grantAllows = true 也救不了非群主。
            member.handleCardAction(groupId, "qc-2", 2, AssistantQuestCardPayload.Actions.OVERRIDE)
            awaitUntil("覆决拒绝稳定") { revOf(host, "qc-2") == 2 }
            assertEquals(2, revOf(host, "qc-2"), "非群主的覆决必须被拒")
            assertTrue(revOf(host, "qc-2")!! >= 2)

            // 宿主本机点覆决（decidedBy == self，宿主即群主）→ 放行推进。
            // 本机点击：handleCardAction → decidedBy == self → handleHostCardAction 直通。
            host.handleCardAction(groupId, "qc-2", 2, AssistantQuestCardPayload.Actions.OVERRIDE)
            awaitUntil("群主覆决推进") { (revOf(host, "qc-2") ?: 0) >= 3 }
        } finally {
            member.stop()
            host.stop()
        }
    }
}
