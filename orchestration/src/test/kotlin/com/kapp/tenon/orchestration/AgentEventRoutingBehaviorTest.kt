// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.EventRouteReasons
import com.kapp.tenon.domain.InMemoryAgentStore
import com.kapp.tenon.domain.InMemoryMessageStore
import com.kapp.tenon.domain.InMemorySettingsStore
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * 事件 → 群的路由（规范 §3 不变量 1）：**台账优先，三键反查只接受唯一解**。
 *
 * ## 为什么这条要行为级测
 *
 * 串群是本仓最贵的一类缺陷：它不抛异常、不写错误日志，症状只是「甲仓库的活出现在
 * 乙仓库的群里」。而它的成因是**一个目录绑了两个群**时把同一条事件复制 N 份。
 * 所以判据必须能同时给出两件事：
 *  - 台账命中时**只**投那一个群（哪怕目录还绑着别的群）；
 *  - 台账没命中、目录又反查出**多个**群时，一个都不投（而不是投最高的那个）。
 *
 * 每个负向断言都配同批次的正向证据：`singleWorkspaceMatchIsStillDelivered` 证明这条
 * 路径**会**投 —— 否则「两个群时返回空」在任何空实现上都成立，是空集假绿。
 *
 * 反向探针：把 `groupsForSession` 的第 1 层（台账）摘掉 ⇒ 第 1、2 条红；
 * 把 `matched.size > 1` 的阻断改成 `take(1)` ⇒ 第 4 条红（会投进第一个群）。
 */
class AgentEventRoutingBehaviorTest {

    private val workspacePath = "/Users/someone/work/teahouse-mobile"

    private fun harness(): RelayController {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return RelayController(
            platform = InMemoryAppPlatform(),
            store = InMemorySettingsStore(
                RelaySettings.firstRun().copy(
                    udpPort = 0, tcpPort = 0,
                    persistMessages = false, persistMessagesDecided = true,
                ),
            ),
            messageStore = InMemoryMessageStore(),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
    }

    /** 两个群绑同一个目录 —— 正是串群的现场。 */
    private fun twoGroupsOnOneDirectory(controller: RelayController): Pair<String, String> {
        val a = controller.createGroup("项目协同群", listOf("peer-a"), description = "目录 " + workspacePath)!!
        val b = controller.createGroup("deepseek 群", listOf("peer-b"), description = "目录 " + workspacePath)!!
        return a to b
    }

    private fun forwarder(controller: RelayController, ledger: Map<String, String>) =
        AgentEventForwarder(
            controller = controller,
            scope = CoroutineScope(Dispatchers.Unconfined),
            currentRuntime = { null },
            registryRef = { null },
            conversationOf = { sessionId -> ledger[sessionId] },
        )

    private fun route(
        controller: RelayController,
        ledger: Map<String, String>,
        sessionId: String = "sess-1",
        wsPath: String? = workspacePath,
    ): List<String> = forwarder(controller, ledger)
        .groupsForSession(controller.state.value, sessionId, wsPath, null, null)
        .map { it.groupId }

    @Test
    fun ledgerBindingWinsOverDirectoryHeuristics() {
        val controller = harness()
        val (a, _) = twoGroupsOnOneDirectory(controller)
        // 在 A 群派的单：台账说 sess-1 属于 A。
        val routed = route(controller, mapOf("sess-1" to ConvId.group(a)))
        assertEquals(listOf(a), routed, "台账命中必须只投那一个群 —— 目录还绑着 B 群也不许跟过去")
    }

    @Test
    fun ledgerBindingToAGroupThatIsGoneIsNotRerouted() {
        val controller = harness()
        // **只**有一个群匹配这个目录：这样「摘掉台账层」就会真的投出去，探针才咬得住。
        val surviving = controller.createGroup("项目协同群", listOf("peer-a"), description = "目录 " + workspacePath)!!
        val routed = route(controller, mapOf("sess-1" to ConvId.group("group-that-left")))
        assertEquals(
            emptyList(), routed,
            "台账指向已不存在的群 ⇒ 阻断。回落三键会把这条会话塞进「目录碰巧对得上」的群（" + surviving + "）",
        )
    }

    @Test
    fun ledgerBindingToOneToOneIsNotForwardedToAnyGroup() {
        val controller = harness()
        val nearby = controller.createGroup("项目协同群", listOf("peer-a"), description = "目录 " + workspacePath)!!
        // 私聊的会话键就是对方 nodeId（没有 group: 前缀）。
        val routed = route(controller, mapOf("sess-1" to "peer-a"))
        assertEquals(emptyList(), routed, "私聊会话的事件不该进群（目录唯一匹配了 " + nearby + " 也不许）")
    }

    @Test
    fun twoGroupsOnTheSameDirectoryIsRefusedInsteadOfDuplicated() {
        val controller = harness()
        twoGroupsOnOneDirectory(controller)
        val f = forwarder(controller, emptyMap())
        val routed = f.groupsForSession(controller.state.value, "sess-unbound", workspacePath, null, null)
        assertEquals(emptyList(), routed, "一个目录绑两个群时必须阻断 —— 投两份就是串群")
        val diag = f.eventDiagnostics.snapshot()
        assertTrue(
            diag.containsKey(EventRouteReasons.AMBIGUOUS_GROUP),
            "阻断必须留下原因（接入与通道页要读这个计数）：" + diag,
        )
        // 参数（hits=N）只进诊断行，**不进聚合键**：否则 hits=2 与 hits=3 会裂成两行
        // 同名不同数的成因，而页面正好要在这一档上给出「一个目录绑了几个群」。
        assertTrue(
            diag.keys.none { it.startsWith(EventRouteReasons.AMBIGUOUS_GROUP + " ") },
            "参数混进了聚合键，按原因聚合在这最该聚合的一档上失效：" + diag,
        )
    }

    @Test
    fun singleWorkspaceMatchIsStillDelivered() {
        val controller = harness()
        val only = controller.createGroup("项目协同群", listOf("peer-a"), description = "目录 " + workspacePath)!!
        val routed = route(controller, emptyMap())
        assertEquals(listOf(only), routed, "唯一命中照旧投递 —— 这是上一条「阻断」不是「全不投」的正向证据")
    }

    @Test
    fun unboundSessionWithNoMatchingGroupIsSilent() {
        val controller = harness()
        controller.createGroup("别的群", listOf("peer-a"), description = "目录 /elsewhere")
        val f = forwarder(controller, emptyMap())
        val routed = f.groupsForSession(controller.state.value, "sess-x", workspacePath, null, null)
        assertEquals(emptyList(), routed)
        // 「没匹配上」与「匹配到多个所以拒投」是两件事：这里不该被记成后者。
        // 逐事件的 `no-conv-bound` 由调用点记（它才拿得到事件类型与游标），不在本函数里。
        assertTrue(
            f.eventDiagnostics.snapshot().keys.none { it.startsWith(EventRouteReasons.AMBIGUOUS_GROUP) },
            "零命中被误记成歧义：" + f.eventDiagnostics.snapshot(),
        )
    }
}
