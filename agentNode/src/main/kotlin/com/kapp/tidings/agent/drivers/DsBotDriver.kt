// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * **本地演示驱动（模拟器）—— 不联网、不调用任何模型或工具。**
 *
 * ## 它不是什么（驱动诚信整改）
 *
 * 这里原来写着「对接 DeepSeek 推理流与 DS-Bot 自主工具调用」，还有 endpointUrl(127.0.0.1:11434)
 * 与 model(deepseek-r1) 两个字段 —— 但 executeTask 里**从来没有一行网络调用**：只有两次
 * delay(15) 与固定文案，工具卡片（websearch/codesearch/bash）背后也没有任何执行代码。
 * 结果是：真用户从手机发一条 prompt，收到的是「已在当前工作区完成分析与协同处理，任务管道
 * 正常执行完毕」——**为没发生过的活报完成**。界面比事实更多，比少更坏。
 *
 * ## 现在的口径
 *
 * · 正文如实说明这是演示、没有执行真实任务；
 * · 工具卡片保留（它是演示的一部分），但工具名上**明确标为示例·未执行**；
 * · totalTokens 报 0（没有模型调用，就不该有 token 数）；
 * · 它**不会被默认选中**：真用户 prompt 走 native，只有显式开发开关才进这里
 *   （见 selectDriverIdForUserPrompt）。
 *
 * 真要接上 endpoint 是独立功能（要自己的设计与验收），不在这次整改里。
 */
class DsBotDriver(
    private val endpointUrl: String = "http://127.0.0.1:11434",
    private val model: String = "deepseek-r1",
    /** 宿主侧 ACL 谓词（[DriverRegistry] 注入）；驱动自己不持有名单。 */
    private val aclAllows: (String) -> Boolean = { true },
) : AgentDriver {
    override val driverId: String = "ds-bot"
    override val displayName: String = "演示驱动（未连接）"

    override suspend fun listCapabilities(): List<DriverCapability> {
        return listOf(
            DriverCapability(
                id = "bash",
                name = "DeepSeek Shell Execution",
                description = "Execute shell commands via DS-Bot environment",
                category = "system",
                enabled = aclAllows("bash"),
            ),
            DriverCapability(
                id = "websearch",
                name = "DeepSeek Web Search",
                description = "Search web using DeepSeek retrieval system",
                category = "model",
                enabled = aclAllows("websearch"),
            ),
            DriverCapability(
                id = "codesearch",
                name = "Code Reasoning",
                description = "DeepSeek R1 semantic code reasoning",
                category = "model",
                enabled = aclAllows("codesearch"),
            ),
        )
    }

    override suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle {
        throw UnsupportedOperationException(
            "demo driver is a local simulator; it does not manage sessions (endpoint $endpointUrl is never contacted)",
        )
    }

    override suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
    ) {
        val trimmed = prompt.trim()
        onEvent(DriverEvent.ThoughtDelta("（演示）收到指令（" + trimmed.length + " 字符）。本驱动不联网、不调用模型、不执行工具。"))
        delay(15)
        onEvent(DriverEvent.ThoughtDelta("（演示）下面这些「工具调用」只是示例卡片，标为未执行。"))
        delay(15)

        val toolCallsMade = mutableListOf<String>()

        if (trimmed.contains("search", ignoreCase = true) || trimmed.contains("find", ignoreCase = true) ||
            trimmed.contains("查") || trimmed.contains("搜")
        ) {
            if (aclAllows("websearch")) {
                val toolId = "ds-call-" + nextToolCallId()
                toolCallsMade.add("websearch")
                onEvent(
                    DriverEvent.ToolCall(
                        toolCallId = toolId,
                        tool = "websearch（示例·未执行）",
                        inputJson = queryJson(trimmed.take(30)),
                        riskLevel = "L1",
                    )
                )
            } else if (aclAllows("codesearch")) {
                val toolId = "ds-call-" + nextToolCallId()
                toolCallsMade.add("codesearch")
                onEvent(
                    DriverEvent.ToolCall(
                        toolCallId = toolId,
                        tool = "codesearch（示例·未执行）",
                        inputJson = queryJson(trimmed.take(30)),
                        riskLevel = "L1",
                    )
                )
            }
        } else if (trimmed.contains("test", ignoreCase = true) || trimmed.contains("测") ||
            trimmed.contains("build", ignoreCase = true) || trimmed.contains("编")
        ) {
            if (aclAllows("bash")) {
                val toolId = "ds-call-" + nextToolCallId()
                toolCallsMade.add("bash")
                onEvent(
                    DriverEvent.ToolCall(
                        toolCallId = toolId,
                        tool = "bash（示例·未执行）",
                        inputJson = "{\"command\":\"./gradlew test\"}",
                        riskLevel = "L2",
                    )
                )
            }
        } else if (trimmed.contains("git", ignoreCase = true) || trimmed.contains("status", ignoreCase = true) ||
            trimmed.contains("状态")
        ) {
            if (aclAllows("bash")) {
                val toolId = "ds-call-" + nextToolCallId()
                toolCallsMade.add("bash")
                onEvent(
                    DriverEvent.ToolCall(
                        toolCallId = toolId,
                        tool = "bash（示例·未执行）",
                        inputJson = "{\"command\":\"git status --short\"}",
                        riskLevel = "L1",
                    )
                )
            }
        }

        delay(15)
        val toolSummary = if (toolCallsMade.isNotEmpty()) "（已调用 " + toolCallsMade.joinToString(", ") + "）" else ""
        // 正文如实说明这是演示：**不许**出现「已完成 / 正常执行完毕」这类完成断言
        // （驱动诚信整改，见类 KDoc）。
        onEvent(
            DriverEvent.TokenDelta(
                "（演示驱动 · 未连接）这条回复是本地模拟的固定文案：**没有执行任何真实任务**，" +
                    "也没有调用模型或工具。" + toolSummary + "\n" +
                    "要真正干活，请把 prompt 交给真实驱动（native）。",
            ),
        )
        onEvent(DriverEvent.Finished(finishReason = "stop", totalTokens = 0))
    }

    override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) {
        throw UnsupportedOperationException(
            "demo driver is a local simulator; it does not accept card actions",
        )
    }

    override suspend fun queryProgress(sessionId: String): TaskProgressSnapshot? {
        // null = 没有可报告的进度（契约语义）：模拟器没有任何在跑的任务。
        return null
    }
}

/**
 * 把用户输入安全地塞进 JSON（task-50 审查修）。
 *
 * 修之前这里是字符串插值：拼 `{"query":"…"}`。prompt 里只要有一个双引号或反斜杠，产出的就是
 * **非法 JSON**（`a"b` 会拼成 `{"query":"a"b"}`）—— 下游按 JSON 解析时要么丢字段、要么报错；
 * 而 `","x":"y` 这种输入还能**注入额外字段**。
 *
 * 用序列化器构造而不是手写转义：转义规则只有一份（与协议层同一个库），不会漂移。
 */
internal fun queryJson(query: String): String =
    buildJsonObject { put("query", JsonPrimitive(query)) }.toString()


/** 演示驱动的工具调用 id 计数器：同一毫秒内多次调用也不会撞（原来用 currentTimeMillis）。 */
private val toolCallSequence = java.util.concurrent.atomic.AtomicLong(0)

/** 进程内唯一的演示工具调用 id。 */
internal fun nextToolCallId(): Long = toolCallSequence.incrementAndGet()

/**
 * 真用户 prompt 该用哪个驱动。
 *
 * **默认 native（真实驱动）**：演示驱动（ds-bot）是本地模拟器、不联网、不执行工具，
 * 在用户不知情时把它当默认，等于对用户为没发生过的活报完成（诚信整改）。
 * 只有显式打开开发开关（JVM 参数 relay.driver.demo=1）才进演示驱动。
 */
fun selectDriverIdForUserPrompt(demoRequested: Boolean): String = if (demoRequested) "ds-bot" else "native"
