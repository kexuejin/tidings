// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * 4. webhook 的持久化出口只做 ingest（且必须在 ACK 之前），编排消费必须走表驱动
 *    循环——listeners 同步 fan-out 不是编排流，也不承担持久化责任。
 */
class TriggerChannelWiringGuardTest {

    private val webhookRoute = GuardSourceScan.stripComments(
        GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/plugins/SourceWebhookRoute.kt"),
    )

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
        // 按**调用块**判，而不是全文 contains：文件里出现过 allowsTool 不代表
        // 它在通道体里被调用（可能挂在别的地方，通道本身裸奔）。
        assertTrue(
            executeBody().contains("CognitiveFlow.allowsTool"),
            "写工具拦截必须在 execute 的通道体里（提示词是请求，代码才是约束）",
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

    /**
     * 围栏的**结构**守卫：行为用例证明当前正文逃不出去，这里钉住「每个字段都过
     * 转义」这件事本身——有人把 `escapeUntrusted` 从某个字段上摘掉，行为用例
     * 只覆盖 detail，源码层这里立刻红。
     */
    @Test
    fun everyUntrustedFieldGoesThroughTheEscaper() {
        val start = executor.indexOf("fun wrapUntrusted(")
        assertTrue(start >= 0, "找不到 TriggerPrompts.wrapUntrusted")
        val end = executor.indexOf("fun clampInstruction(", start)
        assertTrue(end > start, "找不到 wrapUntrusted 的结束标记")
        val body = executor.substring(start, end)

        assertTrue(body.contains("escapeUntrusted("), "围栏里的字段必须先过转义函数")
        assertTrue(
            Regex("append\\(event\\.").findAll(body).none(),
            "事件字段不许裸 append 进围栏：一句 </untrusted_inbound_data> 就能提前闭合",
        )
        assertTrue(
            body.contains("escapeUntrusted(sourceId)"),
            "source 属性也是外部数据，同样要转义",
        )
        assertEquals(
            1,
            Regex("append\\(\"</untrusted_inbound_data>\"\\)").findAll(body).count(),
            "闭合标签只能由宿主写一次",
        )
    }

    @Test
    fun theWebhookPersistenceSinkIsOnlyAPersistenceBridge() {
        // 出口体：从 durableSink 到 triggerQueue 赋值之间只许 ingest + 字段翻译 +
        // 三态判定（Stored / Duplicate / Failed）。
        val start = extension.indexOf("sourceWebhooks.durableSink")
        assertTrue(start >= 0, "找不到 webhook 持久化出口接线（ACK 之前必须落盘）")
        val end = extension.indexOf("triggerQueue = queue", start)
        assertTrue(end > start, "找不到持久化出口体的结束标记")
        val sinkBody = extension.substring(start, end)
        assertTrue(sinkBody.contains("queue.ingest("), "持久化出口必须把事件送进持久化队列")
        assertTrue(sinkBody.contains("eventExists("), "ingest 的 false 必须用 eventExists 区分重放与插入失败")
        assertTrue(
            sinkBody.contains("WebhookPersistence.Stored") &&
                sinkBody.contains("WebhookPersistence.Duplicate") &&
                sinkBody.contains("WebhookPersistence.Failed"),
            "三态缺一不可：把落盘失败当重放，等于用成功码盖住丢事件",
        )
        assertTrue(
            !sinkBody.contains("executeCliTask") && !sinkBody.contains("handlePrompt"),
            "持久化出口里不许直接派单——编排消费走 TriggerEventDrain 的表驱动循环（§9 阶段二）",
        )
        assertTrue(
            !sinkBody.contains("publishTicketCard"),
            "持久化出口里不许发卡",
        )
    }

    /**
     * ACK 顺序的结构守卫：200 只能出现在持久化出口**之后**。
     *
     * 行为判据在 SourceWebhookRouteTest（落盘失败必须 503 且可重试）；这里按源码
     * 层再钉一条，防止有人把 sink 挪回异步 listener 之后还留着行为用例的假绿。
     */
    @Test
    fun webhookAckIsGatedOnDurablePersistence() {
        val handleAt = webhookRoute.indexOf("fun handleWebhook(")
        assertTrue(handleAt >= 0, "找不到 SourceWebhookRegistry.handleWebhook")
        val sinkAt = webhookRoute.indexOf("sink(normalizedSourceId, event)", handleAt)
        val finalAckAt = webhookRoute.lastIndexOf("return WebhookResult.Accepted(event)")
        assertTrue(sinkAt > handleAt, "handleWebhook 必须在 ACK 路径上同步调用持久化出口")
        assertTrue(
            finalAckAt > sinkAt,
            "最终 200 必须排在落盘之后（sinkAt=$sinkAt, finalAckAt=$finalAckAt）",
        )
        val rememberAt = webhookRoute.indexOf("val firstFanOut = rememberEvent(", handleAt)
        assertTrue(
            rememberAt > sinkAt,
            "内存幂等账必须记在落盘成功之后：先记账再落盘时，并发重投会在第一条" +
                "仍卡在 sink 里时直接 200 duplicate，落盘失败即永久丢失" +
                "（rememberAt=$rememberAt, sinkAt=$sinkAt）",
        )
        assertTrue(webhookRoute.contains("persist-failed"), "落盘失败必须有一个显式拒收出口")
        assertTrue(webhookRoute.contains("WebhookPersistence.Failed"), "落盘失败与重放必须是两档")
        assertTrue(webhookRoute.contains("WebhookPersistence.Duplicate"), "重启后的迟到重投必须算已受理")
        assertTrue(
            !extension.contains("sourceWebhooks.addListener"),
            "持久化不许挂在异步 listener 上：ACK 先回、listener 抛错被吞 = 事件永久丢失",
        )
    }

    /**
     * follow 循环与触发对账的「失败不静默停放」守卫。
     *
     * 这两处都不在编译期可判：followJob 里少一层 catch，整条跟随循环会被一拍异常
     * 杀死（此后运行时重启再也不重挂控制面，症状是「面板正常但 /v1 全空」）；
     * syncTriggersTick 在干活**之前**写指纹，fromManifests 一抛错就永久停在半同步
     * 状态——下一拍指纹相等直接 return。两者都是「看起来一切正常」的静默丢失。
     */
    @Test
    fun followTickFailuresAreIsolatedAndDoNotPoisonTheFingerprint() {
        val startAt = extension.indexOf("fun start()")
        assertTrue(startAt >= 0, "找不到 DesktopExtension.start")
        val stopAt = extension.indexOf("fun stop()", startAt)
        assertTrue(stopAt > startAt, "找不到 start 的结束标记")
        val startBody = extension.substring(startAt, stopAt)
        assertTrue(startBody.contains("catch (c: CancellationException)"), "单拍异常隔离必须单独放行取消")
        assertTrue(startBody.contains("throw c"), "取消不许被吞掉，否则已取消的协程继续循环")
        assertTrue(startBody.contains("catch (t: Throwable)"), "一拍失败不许杀死整个 followJob")
        assertTrue(
            startBody.contains("delay(FOLLOW_POLL_MS)") &&
                startBody.lastIndexOf("delay(FOLLOW_POLL_MS)") > startBody.lastIndexOf("catch (t: Throwable)"),
            "下一拍的调度必须排在 catch 之外：失败也要继续轮询重试",
        )

        val tickAt = extension.indexOf("private fun syncTriggersTick()")
        assertTrue(tickAt >= 0, "找不到 syncTriggersTick")
        val endAt = extension.indexOf("private fun detach()", tickAt)
        assertTrue(endAt > tickAt, "找不到 syncTriggersTick 的结束标记")
        val tickBody = extension.substring(tickAt, endAt)
        val syncAt = tickBody.indexOf("scheduler.syncCronTasks(")
        val fingerprintAt = tickBody.indexOf("lastTriggerFingerprint = fingerprint")
        assertTrue(syncAt >= 0, "对账必须真的同步 cron 任务")
        assertTrue(
            fingerprintAt > syncAt,
            "指纹必须在**整拍成功之后**才记；提前写等于把失败钉成稳态（syncAt=$syncAt, fingerprintAt=$fingerprintAt）",
        )
        assertTrue(tickBody.contains("lastTriggerFingerprint = null"), "对账失败必须清指纹，让下一拍重试")
    }

    /**
     * 对账式重建必须一次换整表：行为判据在 TriggerExecutorTest（Iterable 探测窗口
     * 内读侧只看到旧表），这里钉住接线——有人把 `replaceAll` 换回 `clear()` +
     * 逐条 `register()`，行为用例未必撞上那个窗口，源码层必须直接红。
     */
    @Test
    fun triggerRebuildSwapsTheWholeRouteTableAtOnce() {
        val tickAt = extension.indexOf("private fun syncTriggersTick()")
        assertTrue(tickAt >= 0, "找不到 syncTriggersTick")
        val endAt = extension.indexOf("private fun detach()", tickAt)
        assertTrue(endAt > tickAt, "找不到 syncTriggersTick 的结束标记")
        val tickBody = extension.substring(tickAt, endAt)
        assertTrue(
            tickBody.contains("triggerRegistry.replaceAll("),
            "整表重建必须走一次原子快照替换（clear()+register() 的空表窗口会误判已落盘事件）",
        )
        assertTrue(
            !tickBody.contains("triggerRegistry.clear()") && !tickBody.contains("triggerRegistry.register("),
            "对账重建不许拆成 clear + 逐条 register",
        )
        val registry = GuardSourceScan.stripComments(
            GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/TriggerRegistry.kt"),
        )
        assertTrue(registry.contains("@Volatile"), "读侧必须看 volatile 引用，否则换表对消费协程不可见")
        assertTrue(
            registry.contains("unmodifiableMap") || registry.contains("toMap()"),
            "换上去的快照必须不再被原地修改，否则「原子」只是名义上的",
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
        // 下面三条是本轮修的三个「事件永久丢失 / 双派」面，各钉一条结构判据。
        // 它们都不在编译期可判，只能靠源码层：删掉其中任何一行，测试就红。
        assertTrue(
            drain.contains("store.claimEvent("),
            "取走事件必须走原子认领（条件 UPDATE）——无判据的 updateEventState 会把" +
                "「没抢到」变成不可观测，结果是双派",
        )
        assertTrue(
            drain.contains("reclaimRunningEvents("),
            "启动时必须做崩溃恢复：遗留 running 行既不拉得到也永不 prune，不捞就是永久丢失",
        )
        // 顺序判据必须**按 dispatch 函数体**取，不能按整文件下标：`start()` 里也有
        // 一处 `scope.launch`，用全文下标比出来的先后毫无意义（这条一开始就写错过）。
        val dispatchAt = drain.indexOf("private suspend fun dispatch(")
        assertTrue(dispatchAt >= 0, "找不到 TriggerEventDrain.dispatch 的签名")
        val dispatchBody = drain.substring(dispatchAt)
        val claimAt = dispatchBody.indexOf("store.claimEvent(")
        val launchAt = dispatchBody.indexOf("scope.launch")
        assertTrue(
            claimAt >= 0 && launchAt >= 0 && claimAt < launchAt,
            "认领必须在 launch 之前：先派后认领等于没有认领（claimAt=$claimAt, launchAt=$launchAt）",
        )
        assertTrue(
            drain.contains("invokeOnCompletion"),
            "并发许可必须挂在 job 完成回调上——写在协程体里的 finally 在作用域已取消时不会执行，" +
                "许可被永久吃掉",
        )
    }
}
