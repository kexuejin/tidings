// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.InMemoryAgentStore
import com.kapp.tenon.domain.InMemoryMessageStore
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.domain.SettingsStore
import com.kapp.tenon.platform.InMemoryAppPlatform
import java.io.File
import java.net.DatagramSocket
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * **团队执行的协议层回环**（团队执行方案 §4.2/§4.5/§4.6/§9 调度行）：
 * 同一 JVM 里起**真节点**（真 UDP/TCP 端口、真握手、真群帧），B 发一句
 * `@coder …`，断言只有**席位宿主**进团队分支、blocked 卡**全群可见**。
 *
 * 与 [TeamDispatchBehaviorTest] 的分工：那边钉「编排器入口的行为矩阵」（直调
 * handlePrompt），这边钉「帧真的过线」（AGENTS §4：回执/动作判据必须先问
 * 「这条帧真的过线了吗」）——路由判据、群帧投递、卡的广播可见性都只在这层存在。
 *
 * 判据：
 * - **只有目标宿主执行**：B（目标外成员）与 C（第二席位宿主）的 onAgentPrompt
 *   录制器**恒空**（有界负观测窗，不是没断言）；A 收到入站后进团队分支；
 * - **blocked 卡全群可见**：未配置 ⇒ 卡在 A 落库**且 B 真的收到**（「A 有卡」与
 *   「B 有卡」是两个独立的量，缺一个就是另一个静默丢失现场）；
 * - **未授权 ⇒ blocked 卡、无 working 卡**（不执行，也不静默）；
 * - **旧发送端兼容**（§4.6 关键路径）：B 发的是**纯文本**（无 agentIntent、无新键，
 *   与旧版本线上字节相同），新宿主自己从群元数据算 token 池 ⇒ 仍能执行。
 *
 * ## 诚实不覆盖
 *
 * - 「多宿主下旧判据会让路 ⇒ 没人执行」的**完整**翻转需要 A 侧 remoteAgentPresent
 *   = true（agent-workspace 状态来自线上的 agent 帧，`:orchestration` 测试够不到
 *   `:app` 的 internal 恢复口）——五档判定矩阵已在 `TeamPromptRoutingTest` 的
 *   route() 层钉住；本文件钉的是线上可达的另一半：目标宿主执行 + 非目标恒不执行。
 * - 真驱动链跑起来（fake ControlPlane + worktree）不在本层 —— 由 StageChain/
 *   ReviewPipeline 既有单测与真机验收（ACCEPTANCE §10f）覆盖。
 */
class TeamDispatchTwoNodeTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------ 装置

    private object Ports {
        private const val FIRST = 24_000 // 与 :app jvmTest 的 21000 段错开，并行跑不撞
        private const val STRIDE = 8
        private const val BLOCKS = 1_000
        private val cursor = AtomicInteger(0)

        /** 从内核临时端口段之下的固定区间取一块；逐协议真 bind 探测，被占就换下一块。 */
        fun freeUdpBase(): Int {
            for (attempt in 0 until BLOCKS) {
                val base = FIRST + cursor.getAndIncrement().mod(BLOCKS) * STRIDE
                val udp = runCatching { DatagramSocket(base) }.getOrNull() ?: continue
                udp.close()
                val tcpFree = (1..4).all { offset ->
                    runCatching { ServerSocket(base + offset) }.getOrNull()?.also { it.close() } != null
                }
                if (tcpFree) return base
            }
            error("双节点端口池用尽：$BLOCKS 块里没有可用的 UDP/TCP 端口组")
        }
    }

    private class SeededSettingsStore(private val base: RelaySettings, private val meta: GroupMeta) : SettingsStore {
        override fun load(): RelaySettings = base.copy(groups = listOf(meta))
        override fun save(settings: RelaySettings) { /* 测试期间不落盘 */ }
    }

    /** onAgentPrompt 录制器：谁真的被派单了，一眼可辨（「没派单」必须是可断言的负观测）。 */
    private class PromptRecorder {
        val invocations = CopyOnWriteArrayList<Pair<String, String?>>() // (prompt, mentionedKey)
        val hook: suspend (String, String, String, String?, String?) -> Unit = { _, _, prompt, _, mentioned ->
            invocations.add(prompt to mentioned)
        }
    }

    /** 节点 0 恒为席位宿主（挂编排器）；其余节点可选挂录制器。 */
    private class Cluster(
        val nodes: List<RelayController>,
        val ports: List<Int>,
        val orchestrator: DesktopWorkerOrchestrator,
        private val convId: String,
        private val tmp: File,
    ) {
        fun cardsOf(index: Int) = nodes[index].state.value.messages[convId].orEmpty().filter { it.card != null }

        /**
         * 该节点会话里的**卡面文本**（card JSON 的字符串形态）。断言一律在文本上进行 ——
         * `MessageUi.card` 是 JsonObject，`"…" in card` 是 containsKey 语义，不是子串匹配。
         */
        fun cardTextsOf(index: Int): List<String> =
            cardsOf(index).map { it.card?.toString().orEmpty() }

        /** 诊断转储（同 TeamDispatchBehaviorTest 的 debugRows 形态）：失败时看得见每一行。 */
        fun dumpOf(index: Int): String =
            nodes[index].state.value.messages[convId].orEmpty()
                .joinToString(" || ") { "card=" + (it.card != null) + " notice=" + it.notice + " text=" + it.text.take(30) }

        /** 全部 start + 全连接握手；返回 nodeId 列表（与节点下标对应）。 */
        fun startAllAndConnect(): List<String> {
            nodes.forEach { it.start() }
            val ids = nodes.map { n -> TestAwait.value("节点起来", TIMEOUT) { nodeRunning(n) } }
            for (i in nodes.indices) for (j in nodes.indices) {
                if (i != j) nodes[i].addManualPeer("127.0.0.1", ports[j].toString())
            }
            TestAwait.condition("全连接互见", TIMEOUT) {
                nodes.forEachIndexed { i, _ ->
                    nodes.forEachIndexed { j, _ ->
                        if (i != j && nodes[i].state.value.peers.none { it.nodeId == ids[j] }) return@condition false
                    }
                }
                true
            }
            return ids
        }

        private fun nodeRunning(c: RelayController): String? =
            if (c.state.value.network.phase.name == "Running") c.state.value.selfNodeId else null

        suspend fun stopAll() {
            nodes.forEach { runCatching { it.stop() } }
            tmp.deleteRecursively()
        }
    }

    private fun buildCluster(
        nodeCount: Int,
        seatsOf: (nodeIds: List<String>) -> List<VirtualAgentMember>,
        withProfile: Boolean,
        recorders: Map<Int, PromptRecorder> = emptyMap(),
    ): Cluster {
        val bases = List(nodeCount) {
            RelaySettings.firstRun().copy(persistMessages = false, persistMessagesDecided = true)
        }
        val nodeIds = bases.map { it.nodeId }
        val meta = GroupMeta(
            groupId = groupId, name = "团队群",
            members = nodeIds, rev = 2,
            updatedBy = nodeIds[0], updatedTs = 1,
            ownerId = nodeIds[0], adminIds = emptyList(),
            virtualAgents = seatsOf(nodeIds),
        )
        val portList = mutableListOf<Int>()
        val controllers = bases.mapIndexed { index, base ->
            val port = Ports.freeUdpBase()
            portList.add(port)
            RelayController(
                platform = InMemoryAppPlatform(),
                store = SeededSettingsStore(base.copy(udpPort = port, tcpPort = port + 1), meta),
                messageStore = InMemoryMessageStore(),
                agentStore = InMemoryAgentStore(),
                scope = scope,
            ).also { c -> recorders[index]?.let { r -> c.onAgentPrompt = r.hook } }
        }
        // 席位宿主（node 0）挂编排器 —— 生产接线形状（DesktopExtension 把 onAgentPrompt
        // 接到 handlePrompt；这里的 lambda 就是那条接缝的测试版）。
        val tmp = Files.createTempDirectory("team-exec-2n").toFile()
        val store = TeamExecutionStore(File(tmp, "team-execution.json"))
        if (withProfile) {
            val verdict = store.save(
                TeamExecutionProfile(
                    dirId = "ws-1", leaderAgentId = "dsh-lead",
                    updatedAt = 0,
                    members = listOf(
                        TeamExecutionMemberSpec("m-coder", TeamExecutionKinds.WORKER, "codex"),
                        TeamExecutionMemberSpec("m-reviewer", TeamExecutionKinds.REVIEWER, "claude"),
                    ),
                ),
            )
            assertTrue(verdict is TeamExecutionSaveVerdict.Saved, "前置：执行档必须能落盘（host=" + nodeIds[0] + "）")
        }
        val orch = DesktopWorkerOrchestrator(
            planeProvider = { null },
            controller = controllers[0],
            scope = scope,
            teamExecutionStore = store,
            // D16：执行档键的 dirId 一半 —— 测试档案都落在 dir-1/ws-1 这条指针上。
            groupDirIdOf = { "ws-1" },
        )
        controllers[0].onAgentPrompt = { conv, senderPeerId, prompt, replyToId, mentioned ->
            orch.handlePrompt(
                convId = conv,
                senderPeerId = senderPeerId,
                prompt = prompt,
                replyToId = replyToId,
                wsId = null,
                mentionedAgentKey = mentioned,
            )
        }
        return Cluster(controllers, portList, orch, convId, tmp)
    }

    // ------------------------------------------------------------ 群元数据

    private val groupId = "g-team"
    private val convId = ConvId.group(groupId)

    private fun teamSeat(hostNodeId: String) = VirtualAgentMember(
        agentId = "dsh-lead",
        hostNodeId = hostNodeId,
        name = "lead-seat",
        entityType = EntityTypes.TEAM,
        teamMembers = listOf(
            TeamMemberInfo("m-coder", "coder", "编码"),
            TeamMemberInfo("m-reviewer", "reviewer", "审查"),
        ),
        defaultWsId = "ws-1",
    )

    /** 第二台 agent 宿主的**单兵**席位（多宿主场景：它不是任何组员 token 的目标）。 */
    private fun singleSeat(hostNodeId: String) = VirtualAgentMember(
        agentId = "codex-c",
        hostNodeId = hostNodeId,
        name = "codex-peer",
        entityType = EntityTypes.SINGLE,
        defaultWsId = "ws-c",
    )

    // ------------------------------------------------------------ 用例

    /** 等一张满足条件的卡并返回它的文本；超时把该节点的消息行一起带上（失败可诊断）。 */
    private fun awaitCard(cluster: Cluster, index: Int, what: String, predicate: (String) -> Boolean): String {
        try {
            TestAwait.condition(what, TIMEOUT) { cluster.cardTextsOf(index).any(predicate) }
        } catch (e: AssertionError) {
            throw AssertionError(e.message + " | rows(node " + index + ")=" + cluster.dumpOf(index), e)
        }
        return cluster.cardTextsOf(index).first(predicate)
    }

    @Test
    fun aPeerMemberMentionReachesTheSeatHostAndTheBlockCardIsVisibleToTheWholeGroup() = runBlocking {
        val recB = PromptRecorder()
        val cluster = buildCluster(
            nodeCount = 2,
            seatsOf = { ids -> listOf(teamSeat(ids[0])) },
            withProfile = false,
            recorders = mapOf(1 to recB),
        )
        try {
            cluster.startAllAndConnect()
            // **旧发送端形状**：纯文本、无 agentIntent、无 mentions —— 线上字节与旧版本相同；
            // 新宿主仍能从群元数据算出组员 token 并执行（§4.6 兼容矩阵的关键一格）。
            cluster.nodes[1].sendToGroup(groupId, "@coder fix the login timeout")

            // 席位宿主进了团队分支：未配置 ⇒ blocked 卡（绝不回落 codex 单棒）。
            val cardOnA = awaitCard(cluster, 0, "A 出团队 blocked 卡") { "Team dispatch blocked" in it }
            assertTrue("not configured" in cardOnA, "卡面写明未配置：$cardOnA")
            assertTrue(
                cluster.cardTextsOf(0).none { "working" in it },
                "不许有 working 卡 —— 静默回落 codex 是 §2 要堵的洞：${cluster.cardTextsOf(0)}",
            )

            // **全群可见**：B 真的收到了那张卡（「A 有卡」≠「B 有卡」，两个量分开断言）。
            awaitCard(cluster, 1, "B 收到同一张 blocked 卡") { "Team dispatch blocked" in it }

            // 非目标宿主恒不派单：B 的 onAgentPrompt 没被调过（目标宿主是 A）。
            TestAwait.quiet("B 恒不派单", QUIET_WINDOW) { recB.invocations.isNotEmpty() }
        } finally {
            cluster.stopAll()
        }
    }

    @Test
    fun anUnauthorizedMemberMentionIsBlockedOnTheHostAndVisibleToTheSender() = runBlocking {
        val recB = PromptRecorder()
        val cluster = buildCluster(
            nodeCount = 2,
            seatsOf = { ids -> listOf(teamSeat(ids[0])) },
            withProfile = true,
            recorders = mapOf(1 to recB),
        )
        try {
            cluster.startAllAndConnect()
            cluster.nodes[1].sendToGroup(groupId, "@coder fix the login timeout")

            // 配了执行档但发送者无权 ⇒ 授权拒绝卡（不是未配置卡 —— 两类原因必须可区分）。
            awaitCard(cluster, 0, "A 出授权拒绝卡") {
                "Team dispatch blocked" in it && "not authorized" in it
            }
            // 发送者真的看得到拒绝 —— 「点了没反应」是本项目明令禁止的静默形态。
            awaitCard(cluster, 1, "B 收到授权拒绝卡") {
                "Team dispatch blocked" in it && "not authorized" in it
            }
            assertTrue(
                cluster.cardTextsOf(1).none { "working" in it },
                "未授权不许有任何执行迹象：${cluster.cardTextsOf(1)}",
            )
            TestAwait.quiet("B 恒不派单", QUIET_WINDOW) { recB.invocations.isNotEmpty() }
        } finally {
            cluster.stopAll()
        }
    }

    /** 多宿主：群里第二台 agent 宿主 C 有自己的单兵席位，`@coder`（A 的团队组员）只落在 A。 */
    @Test
    fun withASecondAgentHostInTheGroupOnlyTheTargetSeatHostDispatches() = runBlocking {
        val recB = PromptRecorder()
        val recC = PromptRecorder()
        val cluster = buildCluster(
            nodeCount = 3,
            seatsOf = { ids -> listOf(teamSeat(ids[0]), singleSeat(ids[2])) },
            withProfile = true,
            recorders = mapOf(1 to recB, 2 to recC),
        )
        try {
            cluster.startAllAndConnect()
            cluster.nodes[1].sendToGroup(groupId, "@coder fix the login timeout")

            // 目标宿主 A 执行（进团队分支；B 无权 ⇒ blocked 卡可见）。
            awaitCard(cluster, 0, "A 出卡") { "Team dispatch blocked" in it }
            // 非目标宿主 B、C 恒不派单 —— 旧判据「多宿主互相让路」下这里会变成 A 也不执行
            // （判定矩阵的翻转在 TeamPromptRoutingTest；这里钉线上可达的执行面）。
            // 注意「没派单」的判据是 **onAgentPrompt 录制器恒空**，不是「C 没有卡」：
            // A 的 blocked 卡本来就广播全群，C 收到广播副本是正确行为 —— 卡面区分不了
            // 「自己派单」与「收到广播」（AGENTS §4：断言必须能区分两者）。
            TestAwait.quiet("B 恒不派单", QUIET_WINDOW) { recB.invocations.isNotEmpty() }
            TestAwait.quiet("C 恒不派单", QUIET_WINDOW) { recC.invocations.isNotEmpty() }
        } finally {
            cluster.stopAll()
        }
    }

    private companion object {
        /** 观测预算（AGENTS §2.7-6）：真 UDP 握手在全量串行跑里会变慢，给足。 */
        const val TIMEOUT = 30_000L

        /** 有界负观测窗：「这段窗口内确实没派单」，不是固定 delay 盲等。 */
        const val QUIET_WINDOW = 2_000L
    }
}
