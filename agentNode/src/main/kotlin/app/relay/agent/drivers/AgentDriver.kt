// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent.drivers

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

    /** 任务完成。 */
    data class Finished(
        val finishReason: String = "stop",
        val totalTokens: Int = 0,
    ) : DriverEvent

    /** 异常出错。 */
    data class Error(val message: String) : DriverEvent
}

/**
 * 通用 Agent 运行时驱动契约。
 *
 * 确立「PPE-1 统一总线 + 驱动适配器（Driver）」架构，实现底层引擎（Native / DS-Bot / Ollama / MCP）
 * 与上层 P2P 通信、手机交互界面完全解耦。
 */
interface AgentDriver {
    /** 驱动唯一标识符（如 "native", "ds-bot", "openai", "mcp"）。 */
    val driverId: String

    /** 驱动展示名称。 */
    val displayName: String

    /** 获取当前驱动支持的能力清单。 */
    suspend fun listCapabilities(): List<DriverCapability>

    /** 动态更新能力 ACL 白名单（手机端远程治理下发后即时生效）。 */
    suspend fun updateCapabilityAcl(enabledCapabilityIds: Set<String>)

    /** 检查指定能力当前是否被允许执行。 */
    fun isCapabilityAllowed(capabilityId: String): Boolean

    /**
     * 执行 Agent 任务并流式回调事件。
     */
    suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
    )
}
