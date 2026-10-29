// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import kotlinx.coroutines.delay

/**
 * 宿主本地原生驱动（NativeRelayDriver）。
 *
 * **它当前没有挂载执行运行时**：`executeTask` 既不启动进程、也不读写文件，
 * 只是把"我收到了这条 prompt"回报成事件。能力清单与 ACL 仍然是真实存在的接缝
 * （ACL 状态在宿主 [DriverRegistry]，本驱动经 [aclAllows] 谓词读它），但"能力被允许"
 * 不等于"这次真的执行了"。
 *
 * 因此这里**不许**产出任何完成断言（驱动诚信，见 `DriverHonestyTest` /
 * `NativeRelayDriverHonestyTest`）：以前无条件回一句「执行完毕：本地任务已成功处理。」
 * 就是「为没发生过的活报完成」，用户在群里看到的正是这句。
 *
 * 会话生命周期三个成员如实宣告不支持：没有运行时的驱动开不出会话、接不了操作，
 * [queryProgress] 如实回 null（没有进度可报，不是失败）。
 */
class NativeRelayDriver(
    /** 宿主侧 ACL 谓词（[DriverRegistry] 注入）；驱动自己不持有名单。 */
    private val aclAllows: (String) -> Boolean = { true },
) : AgentDriver {
    override val driverId: String = "native"
    override val displayName: String = "Native Tidings Driver"

    override suspend fun listCapabilities(): List<DriverCapability> {
        return listOf(
            DriverCapability(
                id = "bash",
                name = "Bash Command Execution",
                description = "Execute shell commands and scripts in host workspace",
                category = "system",
                enabled = aclAllows("bash"),
            ),
            DriverCapability(
                id = "fs_read",
                name = "File System Read",
                description = "Read files and directories in workspace",
                category = "system",
                enabled = aclAllows("fs_read"),
            ),
            DriverCapability(
                id = "fs_write",
                name = "File System Write",
                description = "Create or edit files in workspace",
                category = "system",
                enabled = aclAllows("fs_write"),
            ),
            DriverCapability(
                id = "codesearch",
                name = "Codegraph Search",
                description = "Code graph indexing and symbol resolution",
                category = "system",
                enabled = aclAllows("codesearch"),
            ),
        )
    }

    override suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle {
        throw UnsupportedOperationException(
            "native driver has no execution runtime; it cannot open sessions (execution goes through the host bridge)",
        )
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
            if (!aclAllows("bash")) {
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

    override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) {
        throw UnsupportedOperationException(
            "native driver has no execution runtime; it cannot accept card actions",
        )
    }

    override suspend fun queryProgress(sessionId: String): TaskProgressSnapshot? {
        // null = 没有可报告的进度（契约语义），不是错误：本驱动没有任务在跑。
        return null
    }
}
