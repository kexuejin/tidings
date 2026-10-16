// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent.drivers

import kotlinx.coroutines.delay

/**
 * DeepSeek Harness / DS-Bot 驱动适配器（DsBotDriver）。
 *
 * 对接 DeepSeek 推理流（自动拆分 <think> 标签为 ThoughtDelta）与 DS-Bot 自主工具调用。
 */
class DsBotDriver(
    private val endpointUrl: String = "http://127.0.0.1:11434",
    private val model: String = "deepseek-r1",
) : AgentDriver {
    override val driverId: String = "ds-bot"
    override val displayName: String = "DeepSeek Bot Driver"

    private val activeAcl = mutableSetOf(
        "bash",
        "websearch",
        "codesearch",
    )

    override suspend fun listCapabilities(): List<DriverCapability> {
        return listOf(
            DriverCapability(
                id = "bash",
                name = "DeepSeek Shell Execution",
                description = "Execute shell commands via DS-Bot environment",
                category = "system",
                enabled = isCapabilityAllowed("bash"),
            ),
            DriverCapability(
                id = "websearch",
                name = "DeepSeek Web Search",
                description = "Search web using DeepSeek retrieval system",
                category = "model",
                enabled = isCapabilityAllowed("websearch"),
            ),
            DriverCapability(
                id = "codesearch",
                name = "Code Reasoning",
                description = "DeepSeek R1 semantic code reasoning",
                category = "model",
                enabled = isCapabilityAllowed("codesearch"),
            ),
        )
    }

    override suspend fun updateCapabilityAcl(enabledCapabilityIds: Set<String>) {
        synchronized(activeAcl) {
            activeAcl.clear()
            activeAcl.addAll(enabledCapabilityIds)
        }
    }

    override fun isCapabilityAllowed(capabilityId: String): Boolean {
        return synchronized(activeAcl) {
            capabilityId in activeAcl
        }
    }

    override suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
    ) {
        // 模拟 DeepSeek R1 思考流与回答流解耦
        onEvent(DriverEvent.ThoughtDelta("思考中：解构用户意图，确定需要检查的代码与命令..."))
        delay(10)
        onEvent(DriverEvent.ThoughtDelta("评估命令安全性，执行风险级别确认..."))
        delay(10)

        if (prompt.contains("search") || prompt.contains("find")) {
            if (!isCapabilityAllowed("websearch")) {
                onEvent(DriverEvent.Error("Capability 'websearch' is disabled by mobile ACL policy"))
                return
            }
            onEvent(
                DriverEvent.ToolCall(
                    toolCallId = "ds-call-${System.currentTimeMillis()}",
                    tool = "websearch",
                    inputJson = "{\"query\":\"Kotlin Multiplatform P2P\"}",
                    riskLevel = "L1",
                )
            )
        }

        onEvent(DriverEvent.TokenDelta("你好！这是来自 DeepSeek Bot 的结构化解答。"))
        onEvent(DriverEvent.Finished(finishReason = "stop", totalTokens = 128))
    }
}
