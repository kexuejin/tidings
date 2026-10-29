// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

/**
 * OpenAI / Ollama / vLLM 兼容格式驱动（OpenAiDriver）。
 */
class OpenAiDriver(
    private val endpointUrl: String = "http://127.0.0.1:11434/v1",
    private val model: String = "qwen2.5-coder",
    /** 宿主侧 ACL 谓词（[DriverRegistry] 注入）；驱动自己不持有名单。 */
    private val aclAllows: (String) -> Boolean = { true },
) : AgentDriver {
    override val driverId: String = "openai"
    override val displayName: String = "OpenAI-Compatible Driver (Ollama/vLLM)"

    override suspend fun listCapabilities(): List<DriverCapability> {
        return listOf(
            DriverCapability(
                id = "filesystem",
                name = "File Context Provider",
                description = "Inject file contents into chat completions",
                category = "system",
                enabled = aclAllows("filesystem"),
            ),
            DriverCapability(
                id = "bash",
                name = "Command Invocation",
                description = "Function calling for shell execution",
                category = "system",
                enabled = aclAllows("bash"),
            ),
        )
    }

    override suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle {
        throw UnsupportedOperationException(
            "openai-compatible driver has no session manager wired; endpoint $endpointUrl is never contacted",
        )
    }

    override suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
    ) {
        onEvent(DriverEvent.ThoughtDelta("Parsing prompt for function calls..."))
        onEvent(DriverEvent.TokenDelta("OpenAI driver stream response."))
        onEvent(DriverEvent.Finished())
    }

    override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) {
        throw UnsupportedOperationException(
            "openai-compatible driver has no card action path wired",
        )
    }

    override suspend fun queryProgress(sessionId: String): TaskProgressSnapshot? {
        // null = 没有可报告的进度（契约语义）：这条驱动没有任务台账。
        return null
    }
}
