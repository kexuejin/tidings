// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.AgentSenderId
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.platform.InMemoryAppPlatform
import com.kapp.tidings.testutil.awaitUntil
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * **Layer 4：双节点 P2P 内存仿真**（规格 §8，M4 核心验收）—— 跨端派单网道的全回路。
 *
 * 与 [TwoNodeDeliveryTest] 同一形状：同一 JVM 两个真 [RelayController]，各绑真实
 * UDP/TCP 端口、回环互认，然后走**协议本身** —— 不是调用对方的函数。
 *
 * ## 三个被钉住的断言（规格 §6.2 / §7.3-1 / §7.3-2）
 *
 * 1. **跨端派单到达**：A（Leader）派单 → `agent-invoke` 帧走点对点网道到 B（宿主）→
 *    B 的执行器真的拿到 prompt → `agent-invoke-result` 回传 → A 的租约翻 Done。
 * 2. **【核心】群消息库零条跨端中间通信文本**：两端的群存储里，本回路新增的行
 *    **全部是看板卡**（`card != null`），没有一行纯文本。全群人类只看 Leader 发的
 *    那一张汇总看板卡（§6.1 唯一对外发言人）。
 * 3. **断连超时**：B 停机后派单，A 的租约在 `deadlineMs` 到期翻 Timeout，
 *    超时卡带 [重试]/[降级为本地执行] 两个动作进群。
 *
 * ## 「零刷屏」判据为什么能区分「没产生」与「被判坏帧删掉」（AGENTS §4）
 *
 * 「B 的库里没有那条文本」本身有歧义：可能是 A 根本没发，也可能是发了被 B 整帧丢弃。
 * 拆开它的是**执行证据**：B 的假执行器逐字捕获了 prompt —— 若 prompt 走过群文本，
 * 那会是一条**合法**帧（白名单内的 group-text），合法帧必落库，B 的库里就会出现
 * 一行 `card == null` 的文本 —— 与断言矛盾。所以「执行器捕获到了 prompt」+
 * 「两端库中零文本行」合起来才等于「文本从未被生产」，两个条件缺一不可。
 *
 * ## 为什么宿主是群主
 *
 * `GroupMerge` 的收端权限矩阵：普通成员改群只许「只增不减地邀新人」，B 以普通成员
 * 身份声明自己的 agent 座位会被 A 拒收。所以本仿真让 **B 建群**（宿主 = 群主），
 * A 作为成员拿到底座后派单 —— 这同时钉住了 §6.3 的 GrantedDevice 档：
 * A 在 B 的可信设备面板里批过执行权（`invokeGrantAllows` 桥），不是靠群角色放行。
 */
class TwoNodeAgentInvokeTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun crossHostDispatchReachesTheHostAndNoGroupTextCrosses() = runBlocking {
        val (leader, host, groupId) = hostedGroup()
        try {
            // B 的执行器：逐字捕获 prompt（「到达」的执行证据），回一张插件卡。
            val executedPrompt = java.util.concurrent.atomic.AtomicReference<String?>(null)
            host.invokeExecutor = object : com.kapp.tidings.domain.AgentInvokeExecutor {
                override suspend fun execute(request: com.kapp.tidings.domain.AgentInvokeTaskRequest): com.kapp.tidings.domain.AgentInvokeExecution {
                    executedPrompt.set(request.prompt)
                    return com.kapp.tidings.domain.AgentInvokeExecution.done(
                        summary = "regression green",
                        cardJsonText = buildJsonObject {
                            put("cardId", "plugin-card-1")
                            put("rev", 1)
                            put("title", "回归完成")
                            put("progress", 100)
                        }.toString(),
                    )
                }
            }

            val convA = ConvId.group(groupId)
            val convB = ConvId.group(groupId)
            val beforeA = leader.state.value.messages[convA].orEmpty().size
            val beforeB = host.state.value.messages[convB].orEmpty().size

            val prompt = "run-the-regression-suite"
            val invokeId = leader.dispatchCrossHostInvoke(
                groupId = groupId, agentId = "gpu-runner", prompt = prompt,
                wsId = null, deadlineMs = 30_000L,
            )
            assertNotNull(invokeId, "群里有这个 agent 的声明 ⇒ 派单必须受理")

            // 1) 跨端到达 + 结果回传：租约翻 Done。
            awaitUntil("A 的租约翻 Done（结果帧回传了）") {
                leader.state.value.agentInvokes[invokeId]?.state == InvokeLeaseStates.DONE
            }
            assertEquals(
                prompt, executedPrompt.get(),
                "B 的执行器必须逐字拿到 prompt —— 这是「任务真的跨端到达」的执行证据",
            )
            val lease = leader.state.value.agentInvokes[invokeId]!!
            assertEquals("regression green", lease.summary, "摘要随结果帧回传")

            // 2) 看板卡两端各一行、同一个信封 id；Leader 是唯一对外发言人（§6.1）。
            val cardId = "invoke-" + invokeId
            awaitUntil("B 收到 Leader 的看板卡") { host.rows(groupId).any { it.cardId() == cardId } }
            awaitUntil("A 本机落库看板卡") { leader.rows(groupId).any { it.cardId() == cardId } }
            val cardOnA = leader.rows(groupId).first { it.cardId() == cardId }
            val cardOnB = host.rows(groupId).first { it.cardId() == cardId }
            assertEquals(cardOnA.id, cardOnB.id, "本机落库与线上同帧同 id（补发与去重按信封 id 对账）")
            assertEquals(
                selfOf(leader), hostOf(cardOnA.senderId!!),
                "看板卡的署名宿主必须是 Leader（A），不是被派单的 B —— 全群只听 Leader 发言",
            )
            assertEquals("回归完成", cardOnB.card!!.strOf("title"), "组员卡片的内容原样收编进看板")

            // 3)【核心】零刷屏：本回路在两端新增的行全部是卡，没有一行纯文本。
            val newRowsA = leader.rows(groupId).drop(beforeA)
            val newRowsB = host.rows(groupId).drop(beforeB)
            assertTrue(
                newRowsA.isNotEmpty() && newRowsA.all { it.card != null },
                "A 的群流新增行必须全是看板卡，混进纯文本就是刷屏（§6.2）：${newRowsA.filter { it.card == null }.map { it.text }}",
            )
            assertTrue(
                newRowsB.isNotEmpty() && newRowsB.all { it.card != null },
                "B 的群流新增行必须全是看板卡：${newRowsB.filter { it.card == null }.map { it.text }}",
            )
            for (rows in listOf(leader.rows(groupId), host.rows(groupId))) {
                assertTrue(
                    rows.none { it.card == null && (it.text?.contains(prompt) == true) },
                    "prompt 不得以任何文本形态出现在群流里（执行证据证明它到达过）",
                )
            }
        } finally {
            leader.stop()
            host.stop()
        }
    }

    @Test
    fun aDisconnectedHostFlipsTheLeaseToTimeoutAtTheDeadline() = runBlocking {
        val (leader, host, groupId) = hostedGroup()
        try {
            val deadlineMs = 1_500L
            // 断连：宿主停机（不是「派单被拒」，是「失联」—— 租约为它而生）。
            host.stop()

            val invokeId = leader.dispatchCrossHostInvoke(
                groupId = groupId, agentId = "gpu-runner", prompt = "long job", wsId = null,
                deadlineMs = deadlineMs,
            )
            assertNotNull(invokeId, "派单照发、租约照记：发送失败也有一条可超时的租约，而不是无声消失")
            assertEquals(
                InvokeLeaseStates.RUNNING, leader.state.value.agentInvokes[invokeId]?.state,
                "未到期限不许提前翻（观测预算不判状态）",
            )

            awaitUntil("deadline 到期后租约翻 Timeout（§7.3-1）") {
                leader.state.value.agentInvokes[invokeId]?.state == InvokeLeaseStates.TIMEOUT
            }
            val lease = leader.state.value.agentInvokes[invokeId]!!
            assertTrue(
                lease.finishedAt != null && lease.finishedAt!! >= lease.createdAt + deadlineMs,
                "翻转时刻不得早于期限（到期巡检，1s 粒度 ⇒ 至多迟到一个 tick）",
            )

            // 超时卡进群：带 [重试]/[降级为本地执行] 两个动作（§7.3-1）。
            val cardId = "invoke-" + invokeId
            awaitUntil("超时看板卡进群") { leader.rows(groupId).any { it.cardId() == cardId } }
            val timeoutCard = leader.rows(groupId).first { it.cardId() == cardId }.card!!
            val actions = (timeoutCard["actions"] as? List<*>)?.mapNotNull { (it as? JsonObject)?.strOf("actionId") }
            assertEquals(
                listOf(InvokeBoardActions.RETRY, InvokeBoardActions.FALLBACK_LOCAL),
                actions,
                "超时卡必须带重试与降级两个动作",
            )
        } finally {
            leader.stop()
            // host 已在断言前停过：幂等，不需要也不再能通信。
        }
    }

    @Test
    fun theFirstCardDecisionWinsAndLaterClicksAreDroppedIdempotently() = runBlocking {
        val (leader, host, groupId) = hostedGroup()
        try {
            host.invokeExecutor = object : com.kapp.tidings.domain.AgentInvokeExecutor {
                override suspend fun execute(request: com.kapp.tidings.domain.AgentInvokeTaskRequest) =
                    com.kapp.tidings.domain.AgentInvokeExecution.done(summary = "needs approval", cardJsonText = null)
            }
            val invokeId = leader.dispatchCrossHostInvoke(
                groupId = groupId, agentId = "gpu-runner", prompt = "deploy", wsId = null, deadlineMs = 30_000L,
            )
            assertNotNull(invokeId)
            val cardId = "invoke-" + invokeId
            awaitUntil("看板卡到达 B") { host.rows(groupId).any { it.cardId() == cardId } }
            awaitUntil("看板卡落库 A") { leader.rows(groupId).any { it.cardId() == cardId } }

            val selfA = selfOf(leader)
            val selfB = selfOf(host)

            // B 端点击：卡片宿主在 A ⇒ op=action 帧直达 A（§7.3-2 的跨端通路）。
            val rev1 = leader.rows(groupId).first { it.cardId() == cardId }.card!!.intOf("rev")!!
            host.handleCardAction(groupId, cardId, rev1, "approve")
            awaitUntil("A 记下首个判定并广播 rev+1") {
                leader.rows(groupId).any { it.cardId() == cardId && it.card!!.intOf("rev") == rev1 + 1 }
            }
            awaitUntil("B 收到全群广播的判定结果") {
                host.rows(groupId).any { it.cardId() == cardId && it.card!!.intOf("rev") == rev1 + 1 }
            }
            val decided = leader.rows(groupId).first { it.cardId() == cardId && it.card!!.intOf("rev") == rev1 + 1 }
            assertEquals("approve", decided.card!!.strOf("decidedActionId"), "判定动作随卡广播")
            val expectedB = leader.state.value.settings.groups.first().memberNicks?.get(selfB) ?: selfB
            assertEquals(expectedB, decided.card!!.strOf("decidedBy"), "批准人显示名来自群成员昵称（认不出时兜 nodeId）")

            // A 本机晚到点击同一动作：幂等丢弃 —— 台账只认首个判定。
            leader.handleCardAction(groupId, cardId, rev1 + 1, "approve")
            // A 再点一个**不同**的动作：它必须生效 —— 它的到来证明通道活着、
            // 且它之前的「丢弃」不是消息卡住（丢弃与卡住在此处分得开）。
            leader.handleCardAction(groupId, cardId, rev1 + 1, "deny")
            awaitUntil("第二个不同动作正常生效（rev+2）") {
                leader.rows(groupId).any { it.cardId() == cardId && it.card!!.intOf("rev") == rev1 + 2 }
            }
            awaitUntil("deny 的判定也广播到 B") {
                host.rows(groupId).any { it.cardId() == cardId && it.card!!.intOf("rev") == rev1 + 2 }
            }

            val revs = leader.rows(groupId).filter { it.cardId() == cardId }.map { it.card!!.intOf("rev")!! }
            assertEquals(listOf(rev1, rev1 + 1, rev1 + 2), revs, "原位刷新 = 同 cardId 的追加式新行，不许有第 4 行")
            assertEquals(
                1,
                leader.rows(groupId).count { it.cardId() == cardId && it.card!!.strOf("decidedActionId") == "approve" },
                "approve 判定恰好广播一次：晚到的并发点击被幂等丢弃（§7.3-2）",
            )
            val ledger = leader.invokeCtl.actionLedger
            assertEquals(selfB, ledger.decidedBy(cardId, "approve"), "approve 的判定人是 B（首个 agent-ack）")
            assertEquals(selfA, ledger.decidedBy(cardId, "deny"), "deny 的判定人是 A（本机点击）")
        } finally {
            leader.stop()
            host.stop()
        }
    }

    // ---------------------------------------------------------------- 装置

    /**
     * 起两个真节点并搭好「宿主建群 + 声明 agent 座位」的底座：
     * B（宿主，群主）建群拉 A 进来，注册本地工作区后声明进群 —— A 拿到
     * `virtualAgents`（座位宿主 = B）后才有可派单的对象。
     * A 在 B 的可信设备面板里批过执行权（§6.3 GrantedDevice 档的桥）。
     */
    private suspend fun hostedGroup(): Triple<RelayController, RelayController, String> {
        val portA = freePort()
        val portB = freePort()
        val leader = controller(portA, portA + 1)
        val host = controller(portB, portB + 1)
        leader.start()
        host.start()
        awaitUntil("A 起来") { leader.state.value.network.phase.name == "Running" }
        awaitUntil("B 起来") { host.state.value.network.phase.name == "Running" }
        val selfA = selfOf(leader)
        val selfB = selfOf(host)

        leader.addManualPeer("127.0.0.1", portB.toString())
        host.addManualPeer("127.0.0.1", portA.toString())
        awaitUntil("A 看见 B") { leader.state.value.peers.any { it.nodeId == selfB } }
        awaitUntil("B 看见 A") { host.state.value.peers.any { it.nodeId == selfA } }

        // B 建群（群主 = 宿主），把 A 拉进来。
        val groupId = host.createGroup("GPU 团队", listOf(selfA))
        assertNotNull(groupId, "建群必须成功")
        awaitUntil("A 收到群元数据") { leader.state.value.settings.groups.any { it.groupId == groupId } }

        // B 注册本地工作区并把它的 agent 声明进群（rev+1 广播，A 以群主身份收下）。
        host.recordLocalWorkspace(
            wsId = "ws-gpu", wsTitle = "GPU box", wsPath = null,
            agentKey = null, agentKind = "gpu-runner", online = true,
        )
        host.handleSetGroupAgentWorkspaces(groupId, listOf("ws-gpu"))
        awaitUntil("A 收到 agent 座位声明") {
            leader.state.value.settings.groups.first { it.groupId == groupId }
                .virtualAgents.orEmpty().any { it.agentId == "gpu-runner" && it.hostNodeId == selfB }
        }
        // §6.3：A 已在 B 的可信设备面板里批过执行权（GrantedDevice 档）。
        host.invokeGrantAllows = { _, nodeId -> nodeId == selfA }
        return Triple(leader, host, groupId)
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

    private fun selfOf(c: RelayController): String = c.state.value.selfNodeId

    private fun RelayController.rows(groupId: String) =
        state.value.messages[ConvId.group(groupId)].orEmpty()

    private fun hostOf(senderId: String): String? = AgentSenderId.parse(senderId)?.first

    // ---- MessageUi / JsonObject 的小读取器 ----

    private fun com.kapp.tidings.domain.model.MessageUi.cardId(): String? = card?.strOf("cardId")

    private fun JsonObject.strOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.intOf(key: String): Int? = strOf(key)?.toIntOrNull()

    /** 端口 0 由系统分配；取一个当前空闲的固定号（与 TwoNodeDeliveryTest 同一条纪律）。 */
    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
}
