// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 触发式执行通道的**接线守卫**（提案 §7.1：零副作用靠结构不靠提示词）。
 *
 * 这些事编译期都不会红，只在真机上以「触发任务悄悄建了 worktree / 一句 prompt
 * 被分诊成工单 / 说好只读却写了盘」的形式出现：
 * 1. 通道函数体不许引用 WorktreeSandbox / DesktopStageChain / activeTickets——
 *    「没有那条路径」才是零副作用，注释里写了「只读」不算数；
 * 2. 写工具拦截（allowsTool）必须在通道体里；
 * 3. 触发通道不许调用 CognitiveFlow.classify——分诊对触发任务是「只读」本身，
 *    调它等于让一句 prompt 决定能不能写盘；
 * 4. webhook listener 只做持久化桥（ingest），编排消费必须走表驱动循环——
 *    listeners 同步 fan-out 不是编排流。
 */
class TriggerChannelWiringGuardTest {

    private val executor = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/TriggerExecutor.kt"),
    )

    private val drain = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/TriggerEventDrain.kt"),
    )

    private val extension = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopExtension.kt"),
    )

    private fun executeBody(): String {
        val start = executor.indexOf("suspend fun execute(")
        assertTrue(start >= 0, "找不到 TriggerExecutor.execute 的签名")
        val end = executor.indexOf("private suspend fun publishResultCard(")
        assertTrue(end > start, "找不到 execute 的结束标记（publishResultCard）")
        return executor.substring(start, end)
    }

    @Test
    fun theTriggerChannelNeverTouchesWorktreesOrTickets() {
        val body = executeBody()
        assertTrue(
            !executor.contains("WorktreeSandbox") && !executor.contains("DesktopStageChain"),
            "触发通道的源文件里不许出现 worktree / 责任链——零副作用靠的是「根本没有那条路径」",
        )
        assertTrue(
            !executor.contains("activeTickets"),
            "触发任务不许进工单台账（那是 Deliver 流的账本）",
        )
        // 文件级判定：通道里每一处 wsPath 赋值都必须是 null（非空 wsPath 会让
        // SandboxedCliDriver 建临时 worktree——哪怕只错一处就是全通道破防）。
        val wsPathAssignments = Regex("wsPath\\s*=\\s*[^,)]+").findAll(executor).toList()
        assertTrue(wsPathAssignments.isNotEmpty(), "触发通道必须显式给出 wsPath（不许依赖默认值隐式建 worktree）")
        wsPathAssignments.forEach { match ->
            assertTrue(
                match.value.replace(Regex("\\s+"), " ").startsWith("wsPath = null"),
                "通道里的 wsPath 赋值必须为 null，实际：「" + match.value + "」",
            )
        }
    }

    @Test
    fun theTriggerChannelNeverClassifies() {
        val body = executeBody()
        assertTrue(
            !executor.contains("CognitiveFlow.classify"),
            "触发通道不许分诊：classify 的两态语义会把触发任务判成 Discuss 或 Deliver——" +
                "触发式任务的分诊就是「只读」本身",
        )
        assertTrue(
            !drain.contains("CognitiveFlow.classify"),
            "消费循环同样不许分诊",
        )
        assertTrue(
            executor.contains("CognitiveFlow.allowsTool"),
            "写工具拦截必须在通道体里（提示词是请求，代码才是约束）",
        )
        assertTrue(
            executor.contains("CognitiveFlow.readOnlyPrompt"),
            "只读前导必须来自 CognitiveFlow（唯一事实源）",
        )
    }

    @Test
    fun untrustedDataIsWrappedByTheHostNotTheTemplate() {
        assertTrue(
            executor.contains("<untrusted_inbound_data") && executor.contains("wrapUntrusted"),
            "不可信数据包裹的宿主入口必须在 TriggerPrompts（§7.1：插件作者不得自行拼接正文）",
        )
        assertTrue(
            executor.contains("Limits.AGENT_PROMPT"),
            "插值后的 prompt 预算必须引用 Limits（文档不写第二份数值）",
        )
    }

    @Test
    fun theWebhookListenerIsOnlyAPersistenceBridge() {
        // listener 体：从 addListener 到 triggerQueue 赋值之间只许 ingest + 翻译字段。
        val start = extension.indexOf("sourceWebhooks.addListener")
        assertTrue(start >= 0, "找不到 webhook listener 接线")
        val end = extension.indexOf("triggerQueue = queue", start)
        assertTrue(end > start, "找不到 listener 体的结束标记")
        val listenerBody = extension.substring(start, end)
        assertTrue(listenerBody.contains("queue.ingest("), "listener 必须把事件送进持久化队列")
        assertTrue(
            !listenerBody.contains("executeCliTask") && !listenerBody.contains("handlePrompt"),
            "listener 体里不许直接派单——编排消费走 TriggerEventDrain 的表驱动循环（§9 阶段二）",
        )
        assertTrue(
            !listenerBody.contains("publishTicketCard"),
            "listener 体里不许发卡",
        )
    }

    @Test
    fun theConsumerLoopIsTableDrivenNotListenerDriven() {
        assertTrue(
            drain.contains("pendingEvents") && drain.contains("updateEventState"),
            "消费循环必须从持久化台账拉 pending 行并翻终态（重启恢复与幂等靠它）",
        )
        assertTrue(
            drain.contains("TriggerVerdict.Skip"),
            "filter 前置过滤的 skipped 出口必须存在（§7.2：未命中规则的事件如实记账）",
        )
    }
}
