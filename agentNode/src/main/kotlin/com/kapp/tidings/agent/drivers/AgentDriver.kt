// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import kotlinx.serialization.Serializable

/**
 * 驱动层暴露的能力项元数据。
 */
@Serializable
data class DriverCapability(
    val id: String,
    val name: String,
    val description: String,
    val category: String, // "system", "mcp", "model"
    val enabled: Boolean = true,
)

/**
 * 驱动层向上层总线发送的标准化增量事件。
 */
sealed interface DriverEvent {
    /** 思考过程增量（PPE-1 思考流解耦）。 */
    data class ThoughtDelta(val delta: String) : DriverEvent

    /** 正式文本回答增量。 */
    data class TokenDelta(val delta: String) : DriverEvent

    /** 工具调用事件。 */
    data class ToolCall(
        val toolCallId: String,
        val tool: String,
        val inputJson: String,
        val riskLevel: String = "L2",
    ) : DriverEvent

    /**
     * 声明式卡片（方案 §4.3）：插件自定义的 JSON 负载，宿主原样透传，
     * 由 IM 侧按 Design Token 渲染。字节预算（`Limits.CARD_BYTES`）由**发送侧**
     * 的宿主在构群帧时把关 —— 超限拒绝不截断（截断的 JSON 解不了）。
     */
    data class Card(val cardJson: String) : DriverEvent

    /** 任务完成。 */
    data class Finished(
        val finishReason: String = "stop",
        val totalTokens: Int = 0,
    ) : DriverEvent

    /** 异常出错。 */
    data class Error(val message: String) : DriverEvent
}

/**
 * 一次会话的句柄（方案 §2.4 `openSession` 的定型返回值）。
 *
 * 插件会话的身份键就是 [sessionId]：后续 executeTask / handleAction / queryProgress
 * 都按它寻址。`resumed = true` 表示这是接续旧会话（[com.kapp.tidings.agent.drivers.AgentDriver.openSession]
 * 的 `resumeSessionId` 非空）。
 */
@Serializable
data class SessionHandle(
    val sessionId: String,
    val wsPath: String? = null,
    val resumed: Boolean = false,
    val createdAt: Long = 0L,
)

/**
 * 任务进度的点查快照（方案 §2.4 `queryProgress` 的定型返回值）。
 *
 * `state` 与控制面 `/v1/agent/run` 的取值同域（running / succeeded / failed / cancelled）；
 * null 的返回值语义是「这个会话当前没有可报告的进度」，不是失败。
 */
@Serializable
data class TaskProgressSnapshot(
    val sessionId: String,
    val state: String,
    val stage: String? = null,
    val progressPercent: Int? = null,
    val updatedAt: Long = 0L,
)

/**
 * 通用 Agent 运行时驱动契约。
 *
 * 确立「PPE-1 统一总线 + 驱动适配器（Driver）」架构，实现底层引擎（Native / 进程外插件）
 * 与上层 P2P 通信、手机交互界面完全解耦。
 *
 * ## 能力 ACL 不在本接口上（M2 收编）
 *
 * `updateCapabilityAcl` / `isCapabilityAllowed` 由**宿主**在驱动外层把关
 * （[DriverRegistry]，能力清单的允许名单是宿主状态），**不透传给插件** ——
 * 进程外插件驱动（ExternalPluginDriver）永远不会把 ACL 调用转发到插件进程。
 * 驱动只自报声明（[listCapabilities]，插件驱动读 manifest）；需要在自己的执行路径里
 * 尊重宿主名单的驱动，经构造注入的谓词读宿主状态，而不是自己持有名单。
 */
interface AgentDriver {
    /** 驱动唯一标识符（如 "native", "echo-team"）。 */
    val driverId: String

    /** 驱动展示名称。 */
    val displayName: String

    /** 获取当前驱动支持的能力清单（插件驱动读 manifest 的 capabilities 声明）。 */
    suspend fun listCapabilities(): List<DriverCapability>

    /**
     * 开启（或接续）一个会话。返回的 [SessionHandle.sessionId] 是后续三个成员的寻址键。
     * 不支持会话的驱动应当大声失败，而不是编一个空的句柄出来。
     */
    suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle

    /**
     * 执行 Agent 任务并流式回调事件。
     */
    suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
    )

    /**
     * 回传一次卡片操作（审批按钮等，方案 §5.3）。[decidedBy] 是操作者身份，
     * 宿主据此做审批防重；驱动透传给插件，不自行判定。
     */
    suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String)

    /**
     * 点查一个会话的任务进度；null = 当前没有可报告的进度（不是失败）。
     */
    suspend fun queryProgress(sessionId: String): TaskProgressSnapshot?
}
