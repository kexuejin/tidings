// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain.orchestrator

import com.kapp.tenon.core.persist.PeerTagFileStore
import com.kapp.tenon.core.persist.PeerTagRouter
import com.kapp.tenon.core.protocol.ChannelOps
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.transport.SendOutcome

/** 派单载荷（升级轮 T-2c，设计 §5.1）。 */
data class TaskDispatchPayload(
    val channelId: String,
    val fromAgent: String,
    val task: String,
    val op: String = ChannelOps.ASSIGN,
    val targetTag: String? = null,
    val toAgent: String? = null,
    val expectedResult: String? = null,
    val resources: List<String>? = null,
    val tools: List<String>? = null,
    val sourceMessageIds: List<String>? = null,
    val deadlineMs: Long = 180_000L,
    val sessionId: String? = null,
    val groupId: String? = null,
    val groupRev: Long? = null,
)

/** 派单返回结果（判据可区分，UI 据此给出精准卡片）。 */
sealed class DispatchOutcome {
    /** 派单成功发出，进入租约等待。 */
    data class Dispatched(val channelId: String, val targetNodeId: String) : DispatchOutcome()

    /** 目标节点离线。 */
    data class TargetOffline(val targetNodeId: String) : DispatchOutcome()

    /** 目标节点不具备 `agc1`（跨端 Channel 委派）能力。 */
    data class MissingCapability(val targetNodeId: String, val missingCap: String) : DispatchOutcome()

    /** 底层传输失败（网络断开 / 写入管道拒绝）。 */
    data class SendFailed(val error: String) : DispatchOutcome()
}

/**
 * 发起侧协同协调器（升级轮 T-2c，设计 §5.1；命名 D-025①）。
 *
 * ## 职责分工（D-025②）
 * 1. **节点选择**：封装 [PeerTagRouter.resolve]，基于 [PeerTagFileStore] 提供的本地便条与在线状态做确定性裁决；
 * 2. **跨端派单**：封装对端 `agc1` caps 门控与 `sendAgentChannel` 发送门（op=assign，租约必填）；
 * 3. **任务打断**：维护活跃派单追踪，支持中止。
 *
 * 领域层零 Compose 依赖（遵守 AGENTS.md 边界）。
 */
interface ChannelDispatchCoordinator {
    /** 根据算力/模型需求标签选择匹配的在线节点（null = 无匹配 ⇒ 柔性提示卡）。 */
    suspend fun resolveExecutionNode(requiredTags: Set<String>): String?

    /** 按既有发送门（对端 caps agc1）分发任务到目标节点宿主。 */
    suspend fun dispatchToNode(
        targetNodeId: String,
        taskPayload: TaskDispatchPayload,
    ): DispatchOutcome

    /** 取消当前正在执行的任务 (打断执行)。 */
    suspend fun abortTask(taskId: String): Boolean
}

/**
 * [ChannelDispatchCoordinator] 的标准实现。
 *
 * 采用函数注入解耦运行时依赖，避免对文件系统与网络传输产生硬耦合，便于单测与跨端复用。
 */
class DefaultChannelDispatchCoordinator(
    private val queryNodeIdsWithTag: (tag: String) -> List<String>,
    private val isOnline: (nodeId: String) -> Boolean,
    private val hasCapability: (nodeId: String, cap: String) -> Boolean,
    private val sendChannel: suspend (targetNodeId: String, payload: TaskDispatchPayload) -> SendOutcome?,
) : ChannelDispatchCoordinator {

    /** 便捷构造器：直接绑定已有的 [PeerTagFileStore]。 */
    constructor(
        tagStore: PeerTagFileStore,
        isOnline: (nodeId: String) -> Boolean,
        hasCapability: (nodeId: String, cap: String) -> Boolean,
        sendChannel: suspend (targetNodeId: String, payload: TaskDispatchPayload) -> SendOutcome?,
    ) : this(
        queryNodeIdsWithTag = tagStore::nodeIdsWithTag,
        isOnline = isOnline,
        hasCapability = hasCapability,
        sendChannel = sendChannel,
    )

    private val activeTasks = mutableSetOf<String>()

    override suspend fun resolveExecutionNode(requiredTags: Set<String>): String? {
        if (requiredTags.isEmpty()) return null

        // 标签匹配：寻找同时具备所有 requiredTags 的候选节点集合
        val candidateLists = requiredTags.map { tag -> queryNodeIdsWithTag(tag).toSet() }
        val intersection = candidateLists.reduceOrNull { acc, set -> acc.intersect(set) } ?: emptySet()
        if (intersection.isEmpty()) return null

        // 选第一个标签作为主路由标签，结合在线优先与 nodeId 字典序钉确定性
        val primaryTag = requiredTags.first()
        return PeerTagRouter.resolve(
            tag = primaryTag,
            candidatesWithTag = intersection.toList(),
            isOnline = isOnline,
        )
    }

    override suspend fun dispatchToNode(
        targetNodeId: String,
        taskPayload: TaskDispatchPayload,
    ): DispatchOutcome {
        if (!isOnline(targetNodeId)) {
            return DispatchOutcome.TargetOffline(targetNodeId)
        }
        if (!hasCapability(targetNodeId, MsgTypes.CAP_AGENT_CHANNEL)) {
            return DispatchOutcome.MissingCapability(targetNodeId, MsgTypes.CAP_AGENT_CHANNEL)
        }

        val sendResult = runCatching { sendChannel(targetNodeId, taskPayload) }
        val outcome = sendResult.getOrNull()
        return when (outcome) {
            SendOutcome.SENT, SendOutcome.QUEUED -> {
                activeTasks.add(taskPayload.channelId)
                DispatchOutcome.Dispatched(taskPayload.channelId, targetNodeId)
            }
            null -> {
                val errorMsg = sendResult.exceptionOrNull()?.message ?: "send returned null"
                DispatchOutcome.SendFailed(errorMsg)
            }
        }
    }

    override suspend fun abortTask(taskId: String): Boolean {
        return activeTasks.remove(taskId)
    }
}
