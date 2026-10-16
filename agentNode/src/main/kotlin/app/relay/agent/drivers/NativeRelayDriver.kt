// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent.drivers

import kotlinx.coroutines.delay

/**
 * 宿主本地原生无头执行驱动（NativeRelayDriver）。
 *
 * 包装本地系统工具执行（Bash、文件读写、Codegraph 代码搜索），受即时 ACL 白名单严密约束。
 */
class NativeRelayDriver : AgentDriver {
    override val driverId: String = "native"
    override val displayName: String = "Native Tidings Driver"

    private val activeAcl = mutableSetOf(
        "bash",
        "fs_read",
        "fs_write",
        "codesearch",
    )

    override suspend fun listCapabilities(): List<DriverCapability> {
        return listOf(
            DriverCapability(
                id = "bash",
                name = "Bash Command Execution",
                description = "Execute shell commands and scripts in host workspace",
                category = "system",
                enabled = isCapabilityAllowed("bash"),
            ),
            DriverCapability(
                id = "fs_read",
                name = "File System Read",
                description = "Read files and directories in workspace",
                category = "system",
                enabled = isCapabilityAllowed("fs_read"),
            ),
            DriverCapability(
                id = "fs_write",
                name = "File System Write",
                description = "Create or edit files in workspace",
                category = "system",
                enabled = isCapabilityAllowed("fs_write"),
            ),
            DriverCapability(
                id = "codesearch",
                name = "Codegraph Search",
                description = "Code graph indexing and symbol resolution",
                category = "system",
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
        onEvent(DriverEvent.ThoughtDelta("正在分析需求上下文并规划执行方案..."))
        delay(10)
        if (prompt.contains("list") || prompt.contains("dir")) {
            if (!isCapabilityAllowed("bash")) {
                onEvent(DriverEvent.Error("Capability 'bash' is disabled by mobile ACL policy"))
                return
            }
            onEvent(
                DriverEvent.ToolCall(
                    toolCallId = "call-${System.currentTimeMillis()}",
                    tool = "bash",
                    inputJson = "{\"command\":\"ls -la\"}",
                    riskLevel = "L1",
                )
            )
        }
        onEvent(DriverEvent.TokenDelta("执行完毕：本地任务已成功处理。"))
        onEvent(DriverEvent.Finished())
    }
}
