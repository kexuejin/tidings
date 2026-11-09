// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.ControlPlane
import com.kapp.tenon.agent.executeCliTask
import com.kapp.tenon.agent.executePluginTask
import com.kapp.tenon.agent.pluginForAgent
import com.kapp.tenon.agent.plugins.TriggerPromptKeys
import com.kapp.tenon.agent.resolveCliDriver
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.Limits
import com.kapp.tenon.domain.TriggerEventRecord
import com.kapp.tenon.domain.CognitiveFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicReference

/**
 * 触发式任务的**独立只读执行通道**（触发式编排提案 §7.1 / §9 阶段二）。
 *
 * ## 结构零副作用（不靠提示词自觉）
 *
 * 与 `runDiscussion`（认知研讨流）同一口径，三条硬约束落在这个函数体里：
 * 1. **零 worktree / 零工单**：函数体不引用 `WorktreeSandbox` / `DesktopStageChain`、
 *    不进 `activeTickets`；CLI 执行走 `wsPath = null`（`SandboxedCliDriver` 的路由
 *    规则：非空 wsPath 才建临时 worktree，null 走裸驱动）；
 * 2. **不经过 `CognitiveFlow.classify()`**：触发式任务的分诊就是「只读」本身，
 *    调 classify 等于让一句 prompt 决定能不能写盘；
 * 3. **写工具拦截**：`tool.started` 事件过 `CognitiveFlow.allowsTool` 白名单
 *    （非白名单 fail-closed，无工具名的调用同样不放行），越界即把任务翻失败
 *    ——提示词只是请求，代码才是约束。
 *
 * ## 拦截分两层：执行前（有 hook 时）与事后（总是）
 *
 * `tool.started` 是「已经开始执行」的通知，不是「请求批准」。只靠它，越界的那个
 * 调用**已经落地**了 —— 这是本通道一开始的真实保证边界。
 *
 * 现在多了一层：装了 hook 的 CLI 会在工具执行**之前** POST
 * `/v1/agent/hook/approval`，`CliRunRequest.toolPolicy` 把同一份白名单交给
 * `executeCliTask` 登记到派单会话上（`ControlPlane.readOnlyToolPolicies`），
 * 审批路由据此**拒绝**，工具根本没跑。两层判据同源，不会各判一套。
 *
 * 仍然如实存在的边界：
 *  - **没有 hook 就没有执行前**。驱动不上报 PreToolUse（或插件 leader 走 HTTP 桥，
 *    那条路没有 hook 通道）时只剩事后判负 —— `executePluginTask` 因此**不收**
 *    `toolPolicy`：给它一个永远读不到的白名单，比没有更糟（看起来拦了，其实没有）。
 *  - 判负口径两层一致：**越界即失败**。拦住了不等于没发生 —— 「这条只读通道被
 *    试图写盘」要进结果卡与台账，静默放过会让卡面看起来一切正常。
 *  - 所以本通道的真实保证仍是「零 worktree + 只读前导 + 不可信数据包裹 +
 *    （有 hook 时）执行前拒绝 + 事后判负」，**不是**「写盘不可能」。
 * 见 `docs/AGENT-TRIGGER-AND-PLUGIN-SPECIFICATION.md` §0 矩阵「触发 prompt 分诊链路」行。
 *
 * ## 提示词注入防御（§7.1）
 *
 * 外部事件正文插值后由**宿主**整体包进 `<untrusted_inbound_data>` 再交给模型；
 * 只读前导（`CognitiveFlow.readOnlyPrompt`）+ 不可信数据包裹是两层，缺一不可。
 * 包裹前每个字段（含 source 属性、换行）都要转义：裸拼正文时一句
 * `</untrusted_inbound_data>` 就能提前闭合围栏，让后面的正文伪装成宿主指令。
 *
 * ## 结果回投（§5.2 targetConvId）
 *
 * 结果卡只走**群**通道（`broadcastChainCard` → rev 串行分配 + P2P 广播）。
 * 1:1 会话不在此通道支持范围：现行 1:1 卡片只落本机库不随帧外发（见
 * `broadcastBoardCard` 的 1:1 分支），把结果投进一个对端看不见的会话等于
 * 「发送侧成功、接收侧什么都没有」——注册入口因此只放行群会话（C10 校验）。
 */
class TriggerExecutor(
    private val controller: com.kapp.tenon.domain.RelayController,
    private val planeProvider: () -> ControlPlane?,
) {

    /**
     * 任务执行出口：生产 = 先解析 CLI 驱动（`executeCliTask`），解析不到再按
     * agentId 匹配插件 leader（`pluginForAgent` → `executePluginTask`，经插件的
     * HTTP 桥）——内置席位与外部插件两条执行体平铺平等（提案 §4 统一 Tool Schema）；
     * 都解析不到如实报错。测试注入 fake。两条路径都 `wsPath = null`（零 worktree）。
     */
    internal var cliRunner: suspend (CliRunRequest) -> CliRunResult = { request ->
        val plane = planeProvider()
        if (plane == null) {
            CliRunResult(sessionId = null, status = "error", error = "control plane not attached")
        } else if (plane.resolveCliDriver(request.agentKey) != null) {
            val (sessionId, status, error) = plane.executeCliTask(
                agentKey = request.agentKey,
                prompt = request.prompt,
                wsPath = null,
                requestedSessionId = null,
                sessionTitle = request.title,
                convId = request.convId,
                toolPolicy = request.toolPolicy,
                onEvent = { eventType, _, summary, _ -> request.onEvent(eventType, summary) },
            )
            CliRunResult(sessionId, status, error)
        } else {
            val entry = plane.pluginForAgent(request.agentKey)
            if (entry == null) {
                CliRunResult(
                    sessionId = null, status = "error",
                    error = "no executor for \"" + request.agentKey + "\" (no cli driver, no online plugin leader)",
                )
            } else {
                val (sessionId, status, error) = plane.executePluginTask(
                    entry = entry,
                    rawPrompt = request.prompt,
                    wsPath = null,
                    requestedSessionId = null,
                    sessionTitle = request.title,
                    convId = request.convId,
                    onEvent = { eventType, _, summary, _ -> request.onEvent(eventType, summary) },
                )
                CliRunResult(sessionId, status, error)
            }
        }
    }

    data class CliRunRequest(
        val agentKey: String,
        val prompt: String,
        val title: String,
        /** Tenon 会话键（§9 阶段三对齐键）：审批卡按它归会话。 */
        val convId: String?,
        /**
         * 只读白名单的**执行前**判据（§7.1 第 3 条）：由 [execute] 填
         * `CognitiveFlow::allowsTool`，CLI 通路把它登记到派单会话上，hook 审批
         * 在工具落地之前据此拒绝。null = 不设限（非只读通道）。
         */
        val toolPolicy: ((String?) -> Boolean)? = null,
        /** (eventType, toolSummary)；消费方在此做只读白名单监控。 */
        val onEvent: (eventType: String, summary: String?) -> Unit,
    )

    data class CliRunResult(val sessionId: String?, val status: String, val error: String?)

    /** 触发任务执行结果。 */
    data class Outcome(val sessionId: String?, val done: Boolean, val error: String?)

    /**
     * 执行一次触发任务。
     *
     * @param instruction 已插值的任务指令（模板插值见 [TriggerPrompts.interpolate]；
     *   不含只读前导与不可信数据块——那两件事由本函数负责）。
     * @param sourceId 事件源标注（untrusted 块的 source 属性）；cron 触发传 null。
     * @param event 事件原文（cron 触发传 null）。
     * @param targetGroupId 结果卡回投的群 id（裸 groupId）；null = 不回投。
     */
    suspend fun execute(
        agentKey: String,
        title: String,
        instruction: String,
        sourceId: String?,
        event: TriggerEventRecord?,
        targetGroupId: String?,
        /** 回投会话的 Tenon 会话键（对齐键）：审批卡/回卡按它归位。 */
        convId: String? = null,
    ): Outcome {
        val prompt = TriggerPrompts.assemble(instruction, sourceId, event)
        val violated = AtomicReference<String?>(null)
        val run = cliRunner(
            CliRunRequest(
                agentKey = agentKey,
                prompt = prompt,
                title = title,
                convId = convId,
                // 执行前的那一半（§7.1 第 3 条）：同一份白名单，交给 CLI 通路登记到
                // 派单会话上，hook 审批在工具落地之前据此拒绝。提示词是请求，
                // 代码才是约束 —— 而「拦得住」比「事后判负」更接近约束。
                toolPolicy = { tool -> CognitiveFlow.allowsTool(tool) },
                onEvent = { eventType, summary ->
                    // 只读白名单检测（§7.1 第 3 条约束）：非白名单工具即越界。
                    // 记住**第一个**越界工具，run 结束后把任务判负。
                    //
                    // 这一层留着，不是与上面重复：它覆盖**没装 hook 的驱动**
                    // （没有 PreToolUse 就没有可拒绝的时机，只能事后判负），
                    // 也覆盖 hook 装了但没走审批路径的调用。两层判据同源
                    // （`CognitiveFlow.allowsTool`），不会各判一套。
                    if (eventType == "tool.started" && !CognitiveFlow.allowsTool(summary)) {
                        violated.compareAndSet(null, summary?.takeIf { it.isNotBlank() } ?: "(unnamed tool)")
                    }
                },
            ),
        )
        if (run.status == "error") {
            publishResultCard(targetGroupId, agentKey, title, done = false, error = run.error)
            return Outcome(run.sessionId, done = false, error = run.error)
        }
        violated.get()?.let { tool ->
            val message = "read-only trigger channel violated by tool \"" + tool + "\"; aborted"
            publishResultCard(targetGroupId, agentKey, title, done = false, error = message)
            return Outcome(run.sessionId, done = false, error = message)
        }
        publishResultCard(targetGroupId, agentKey, title, done = true, error = null)
        return Outcome(run.sessionId, done = true, error = null)
    }

    /** 结果卡回投（群通道）；卡体最小化，远低于 CARD_BYTES 硬闸。 */
    private suspend fun publishResultCard(
        targetGroupId: String?,
        agentId: String,
        title: String,
        done: Boolean,
        error: String?,
    ) {
        if (targetGroupId.isNullOrBlank()) return
        val card: JsonObject = buildJsonObject {
            put("cardId", "trigger-" + java.util.UUID.randomUUID().toString().take(8))
            put("rev", 1)
            put("type", "trigger-result")
            put("state", if (done) "completed" else "failed")
            put("title", title.take(80))
            if (done) {
                put("banner", "trigger task completed (read-only channel)")
            } else {
                put("banner", (error ?: "trigger task failed").take(160))
            }
        }
        val json = card.toString()
        if (json.encodeToByteArray().size > Limits.CARD_BYTES) {
            // 理论不达（卡体固定小形状）；闸门在这里是为了让「注定被拒的帧」根本不出手。
            return
        }
        controller.broadcastChainCard(ConvId.group(targetGroupId), agentId, json)
    }
}

/** 触发 prompt 的组装与插值（§5.2 / §7.1）。 */
object TriggerPrompts {

    /**
     * `{{field}}` 插值：只认 [TriggerPromptKeys.ALLOWED] 的 6 个字段，未命中的占位符
     * **原样保留**（清单校验在注册时拒绝未知占位符；运行时不动它，宁可让模型看见
     * 一个没替换的字面量，也不猜语义）。
     *
     * 取值域与正则都不在这里重复定义：注册期的 `PluginRegistration.validate` 用
     * [TriggerPromptKeys] 判合法，运行期这里用同一份判替换。两处各写一份时，
     * 「清单收了、运行期不替换」或反过来「运行期替换了清单不许的字段」都能发生，
     * 而且两边测试各自绿。
     */
    fun interpolate(template: String, fields: Map<String, String>): String =
        TriggerPromptKeys.PLACEHOLDER.replace(template) { match ->
            val key = match.groupValues[1]
            if (key in TriggerPromptKeys.ALLOWED) fields[key] ?: "" else match.value
        }

    fun eventFields(event: TriggerEventRecord): Map<String, String> = mapOf(
        "id" to event.eventId,
        "type" to event.type,
        "subject" to event.subject,
        "summary" to event.summary,
        "detail" to event.detail,
        "link" to (event.link ?: ""),
    )

    /**
     * 不可信数据块（§7.1 唯一入口）：外部正文由宿主整体包裹后交给模型，
     * 插件作者不得自行拼接正文。内容全部来自事件记录本身。
     *
     * **每个字段都必须过 [escapeUntrusted]**：旧实现把正文裸拼进去，事件里一句
     * `</untrusted_inbound_data>` 就能提前闭合围栏，后面的正文在模型眼里变成
     * 「宿主指令」——一层写死的标签，等于没有围栏。换行同样要转义，否则正文
     * 里的 `\ndetail: ...` 能伪造新的字段行。source 属性同理（`" onload="`）。
     *
     * 注意边界：围栏是**降权**，不是隔离执行环境；真正的硬约束仍是只读通道与
     * 写工具白名单（§7.1 第 3 条）。
     */
    fun wrapUntrusted(sourceId: String, event: TriggerEventRecord): String = buildString {
        append("<untrusted_inbound_data source=\"").append(escapeUntrusted(sourceId)).append("\">\n")
        append(untrustedField("id", event.eventId))
        append(untrustedField("type", event.type))
        append(untrustedField("subject", event.subject))
        append(untrustedField("summary", event.summary))
        append(untrustedField("detail", event.detail))
        event.link?.let { append(untrustedField("link", it)) }
        append("</untrusted_inbound_data>")
    }

    private fun untrustedField(label: String, value: String): String =
        label + ": " + escapeUntrusted(value) + "\n"

    /**
     * 把不可信文本变成纯数据：XML/HTML 元字符实体化（`&` 必须先转，否则攻击者
     * 直接写 `&lt;` 就能绕过 `<` 的转义），CR/LF 变成可见的字面量，避免伪造字段行。
     *
     * 不做「删除」：删掉内容等于替模型改写事实，也会让排查时看不到原始注入尝试。
     */
    internal fun escapeUntrusted(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                else -> append(ch)
            }
        }
    }

    /** 插值后的 prompt 预算（§5.2：受 Limits.AGENT_PROMPT 约束）。 */
    fun clampInstruction(instruction: String): String {
        if (instruction.length <= Limits.AGENT_PROMPT) return instruction
        return instruction.take(Limits.AGENT_PROMPT) + "\n…(instruction clamped at " + Limits.AGENT_PROMPT + " chars)"
    }

    /**
     * 完整 prompt：只读前导（请求层）+ 任务指令 + 不可信数据块（包裹层）。
     * 顺序固定：指令在前、数据在后，正文里的恶意指令没有机会伪装成任务说明。
     */
    fun assemble(instruction: String, sourceId: String?, event: TriggerEventRecord?): String {
        val parts = mutableListOf(CognitiveFlow.readOnlyPrompt(clampInstruction(instruction)))
        if (event != null && !sourceId.isNullOrBlank()) {
            parts += wrapUntrusted(sourceId, event)
        }
        return parts.joinToString("\n\n")
    }
}
