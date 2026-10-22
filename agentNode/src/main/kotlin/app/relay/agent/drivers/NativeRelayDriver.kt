// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent.drivers

import kotlinx.coroutines.delay

/**
 * 宿主本地原生驱动（NativeRelayDriver）。
 *
 * **它当前没有挂载执行运行时**：`executeTask` 既不启动进程、也不读写文件，
 * 只是把"我收到了这条 prompt"回报成事件。能力清单与 ACL 仍然是真实存在的接缝
 * （手机端远程治理下发后即时生效），但"能力被允许"不等于"这次真的执行了"。
 *
 * 因此这里**不许**产出任何完成断言（驱动诚信，见 `DriverHonestyTest` /
 * `NativeRelayDriverHonestyTest`）：以前无条件回一句「执行完毕：本地任务已成功处理。」
 * 就是「为没发生过的活报完成」，用户在群里看到的正是这句。
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
        // 先把"没有执行运行时"说清楚：思考流也是用户会读到的正文的一部分。
        onEvent(
            DriverEvent.ThoughtDelta(
                "本机 native 驱动没有挂载执行运行时：这次不会执行命令、不读写文件、不产出结果。",
            ),
        )
        delay(10)
        if (prompt.contains("list") || prompt.contains("dir")) {
            if (!isCapabilityAllowed("bash")) {
                onEvent(DriverEvent.Error("Capability 'bash' is disabled by mobile ACL policy"))
                return
            }
            // 卡片如实标「示例·未执行」：它只是说明"如果真跑会是什么命令"，
            // 不是一次已经发生的工具调用（原来的卡片看起来像真的跑过 ls）。
            onEvent(
                DriverEvent.ToolCall(
                    toolCallId = "call-${System.currentTimeMillis()}",
                    tool = "bash（示例·未执行）",
                    inputJson = "{\"command\":\"ls -la\"}",
                    riskLevel = "L1",
                )
            )
        }
        onEvent(
            DriverEvent.TokenDelta(
                "这条回复来自本机 native 驱动的兜底：**没有执行任何命令，也没有产出结果**。" +
                    "要真正干活，需要宿主上挂载真实执行运行时。",
            ),
        )
        // finishReason 也如实：这不是一次完成（stop / succeeded），是没有运行时可用。
        onEvent(DriverEvent.Finished(finishReason = "no-runtime", totalTokens = 0))
    }
}
