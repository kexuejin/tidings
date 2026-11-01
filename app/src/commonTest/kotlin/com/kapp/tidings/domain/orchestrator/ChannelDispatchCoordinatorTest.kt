// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.orchestrator

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.transport.SendOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 协同调度协调器判据（升级轮 T-2c，设计 §5.1 / D-025① 命名）：
 *
 * 1. **节点选择**：
 *    - 空标签集合 ⇒ 返回 null；
 *    - 单标签匹配 ⇒ 在线优先，同在线态按 nodeId 字典序钉确定性；
 *    - 多标签交集 ⇒ 严格要求节点同时拥有全部 requiredTags；
 *    - 无交集/无匹配 ⇒ 返回 null；
 * 2. **派单门控**：
 *    - 目标离线 ⇒ 返回 TargetOffline；
 *    - 目标缺失 agc1 能力 ⇒ 返回 MissingCapability；
 *    - 发送成功 ⇒ 返回 Dispatched 并加入活跃追踪；
 * 3. **任务打断**：
 *    - 成功打断活跃任务返回 true；对未登记任务返回 false。
 */
class ChannelDispatchCoordinatorTest {

    private val nodeTags = mutableMapOf<String, List<String>>()

    private fun queryNodeIdsWithTag(tag: String): List<String> =
        nodeTags.entries.filter { it.value.contains(tag) }.map { it.key }

    @Test
    fun resolveExecutionNodeWithEmptyTagsReturnsNull() = runTest {
        val coordinator = DefaultChannelDispatchCoordinator(
            queryNodeIdsWithTag = ::queryNodeIdsWithTag,
            isOnline = { true },
            hasCapability = { _, _ -> true },
            sendChannel = { _, _ -> SendOutcome.SENT },
        )
        assertNull(coordinator.resolveExecutionNode(emptySet()), "空标签需求应直接返回 null")
    }

    @Test
    fun resolveExecutionNodeSelectsOnlineNodeWithLexicographicalTieBreaking() = runTest {
        // node-a 离线，node-b 与 node-c 在线，均挂上 coder
        nodeTags["node-a"] = listOf("coder")
        nodeTags["node-c"] = listOf("coder")
        nodeTags["node-b"] = listOf("coder")

        val onlinePeers = setOf("node-b", "node-c")
        val coordinator = DefaultChannelDispatchCoordinator(
            queryNodeIdsWithTag = ::queryNodeIdsWithTag,
            isOnline = { it in onlinePeers },
            hasCapability = { _, _ -> true },
            sendChannel = { _, _ -> SendOutcome.SENT },
        )

        val selected = coordinator.resolveExecutionNode(setOf("coder"))
        // 在线优先（淘汰 node-a），在 node-b 与 node-c 之间字典序选 node-b
        assertEquals("node-b", selected, "应优先选择在线节点，并在候选平局时按字典序选中 node-b")
    }

    @Test
    fun resolveExecutionNodeMatchesIntersectionOfMultipleTags() = runTest {
        // node-1 只有 coder，node-2 有 coder+kotlin，node-3 只有 kotlin
        nodeTags["node-1"] = listOf("coder")
        nodeTags["node-2"] = listOf("coder", "kotlin")
        nodeTags["node-3"] = listOf("kotlin")

        val coordinator = DefaultChannelDispatchCoordinator(
            queryNodeIdsWithTag = ::queryNodeIdsWithTag,
            isOnline = { true },
            hasCapability = { _, _ -> true },
            sendChannel = { _, _ -> SendOutcome.SENT },
        )

        val selected = coordinator.resolveExecutionNode(setOf("coder", "kotlin"))
        assertEquals("node-2", selected, "多标签需求必须全部满足，只有 node-2 具备完整能力")
    }

    @Test
    fun resolveExecutionNodeReturnsNullWhenNoNodeSatisfiesAllTags() = runTest {
        nodeTags["node-1"] = listOf("coder")
        nodeTags["node-2"] = listOf("kotlin")

        val coordinator = DefaultChannelDispatchCoordinator(
            queryNodeIdsWithTag = ::queryNodeIdsWithTag,
            isOnline = { true },
            hasCapability = { _, _ -> true },
            sendChannel = { _, _ -> SendOutcome.SENT },
        )

        val selected = coordinator.resolveExecutionNode(setOf("coder", "kotlin"))
        assertNull(selected, "没有任何节点同时具备两项标签时应返回 null")
    }

    @Test
    fun dispatchToOfflineNodeReturnsTargetOffline() = runTest {
        val coordinator = DefaultChannelDispatchCoordinator(
            queryNodeIdsWithTag = ::queryNodeIdsWithTag,
            isOnline = { false },
            hasCapability = { _, _ -> true },
            sendChannel = { _, _ -> SendOutcome.SENT },
        )

        val payload = TaskDispatchPayload(
            channelId = "c-1",
            fromAgent = "agent:lead",
            task = "实现虚拟列表",
        )
        val outcome = coordinator.dispatchToNode("node-target", payload)
        assertTrue(outcome is DispatchOutcome.TargetOffline, "目标离线应返回 TargetOffline")
        assertEquals("node-target", outcome.targetNodeId)
    }

    @Test
    fun dispatchToNodeWithoutCapabilityReturnsMissingCapability() = runTest {
        val coordinator = DefaultChannelDispatchCoordinator(
            queryNodeIdsWithTag = ::queryNodeIdsWithTag,
            isOnline = { true },
            hasCapability = { _, cap -> cap != MsgTypes.CAP_AGENT_CHANNEL },
            sendChannel = { _, _ -> SendOutcome.SENT },
        )

        val payload = TaskDispatchPayload(
            channelId = "c-2",
            fromAgent = "agent:lead",
            task = "执行回归测试",
        )
        val outcome = coordinator.dispatchToNode("node-target", payload)
        assertTrue(outcome is DispatchOutcome.MissingCapability, "目标缺失 agc1 能力应拦截")
        assertEquals(MsgTypes.CAP_AGENT_CHANNEL, outcome.missingCap)
    }

    @Test
    fun dispatchToNodeSuccessTracksActiveTaskAndAllowsAbort() = runTest {
        val coordinator = DefaultChannelDispatchCoordinator(
            queryNodeIdsWithTag = ::queryNodeIdsWithTag,
            isOnline = { true },
            hasCapability = { _, cap -> cap == MsgTypes.CAP_AGENT_CHANNEL },
            sendChannel = { _, _ -> SendOutcome.SENT },
        )

        val payload = TaskDispatchPayload(
            channelId = "c-3",
            fromAgent = "agent:lead",
            task = "架构分析",
        )
        val outcome = coordinator.dispatchToNode("node-worker", payload)
        assertTrue(outcome is DispatchOutcome.Dispatched, "正常派单应返回 Dispatched")
        assertEquals("c-3", outcome.channelId)

        // 中止任务验证
        assertTrue(coordinator.abortTask("c-3"), "已登记的任务应成功打断")
        assertFalse(coordinator.abortTask("c-3"), "重复打断或未登记任务应返回 false")
    }

    @Test
    fun dispatchToNodePropagatesSendFailure() = runTest {
        val coordinator = DefaultChannelDispatchCoordinator(
            queryNodeIdsWithTag = ::queryNodeIdsWithTag,
            isOnline = { true },
            hasCapability = { _, _ -> true },
            sendChannel = { _, _ -> throw RuntimeException("network-error") },
        )

        val payload = TaskDispatchPayload(
            channelId = "c-4",
            fromAgent = "agent:lead",
            task = "后台索引重建",
        )
        val outcome = coordinator.dispatchToNode("node-worker", payload)
        assertTrue(outcome is DispatchOutcome.SendFailed, "底层异常应转化为 SendFailed")
        assertTrue(outcome.error.contains("network-error"))
    }
}
