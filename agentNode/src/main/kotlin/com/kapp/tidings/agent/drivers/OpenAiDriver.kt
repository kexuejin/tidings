// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

/**
 * OpenAI / Ollama / vLLM 兼容格式驱动（OpenAiDriver）。
 */
class OpenAiDriver(
    private val endpointUrl: String = "http://127.0.0.1:11434/v1",
    private val model: String = "qwen2.5-coder",
) : AgentDriver {
    override val driverId: String = "openai"
    override val displayName: String = "OpenAI-Compatible Driver (Ollama/vLLM)"

    private val activeAcl = mutableSetOf(
        "filesystem",
        "bash",
    )

    override suspend fun listCapabilities(): List<DriverCapability> {
        return listOf(
            DriverCapability(
                id = "filesystem",
                name = "File Context Provider",
                description = "Inject file contents into chat completions",
                category = "system",
                enabled = isCapabilityAllowed("filesystem"),
            ),
            DriverCapability(
                id = "bash",
                name = "Command Invocation",
                description = "Function calling for shell execution",
                category = "system",
                enabled = isCapabilityAllowed("bash"),
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
        onEvent(DriverEvent.ThoughtDelta("Parsing prompt for function calls..."))
        onEvent(DriverEvent.TokenDelta("OpenAI driver stream response."))
        onEvent(DriverEvent.Finished())
    }
}
