// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.agent.plugins.PluginManifest
import com.kapp.tidings.agent.plugins.PluginRegistry
import com.kapp.tidings.core.protocol.AgentChannelPayload
import com.kapp.tidings.core.protocol.TeamMemberInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * M7-T2：Channel 编排器的声明式互斥与租约（决策包 §1.5）。
 *
 * 钉五件事：
 * 1. **声明同一资源的任务串行化**（先 offer 先跑：前一个不放锁后一个不开始）；
 * 2. 没声明资源的任务**不进锁**（天然并行，互不等待）；
 * 3. 同一 `channelId` 重复投递就地忽略（幂等）；
 * 4. **assign 租约**：排队超 `deadlineMs` 如实回 `timeout`，且锁照放（出队）；
 * 5. 没承接者如实答 no-plugin、执行异常如实答 error（诚信回执，绝不静默）。
 *
 * 时序纪律（AGENTS.md 测试纪律）：**零阻塞 latch、零同步性 delay** —— 半成品的
 * `CountDownLatch.await()` 正是把 Unconfined 内联执行调用线程这件事变成了挂死
 * 整个测试 JVM（上一轮基线线程栈钉死在它上面 43 分钟）。这里的等待全部是
 * **真挂起原语**：观察用 `Channel.receive`（有界超时），同步点用
 * `CompletableDeferred`；Unconfined 下的内联执行意味着「offer 返回 ⇒ 已跑到
 * 首个挂起点」—— 断言等待的都是因果上必然的事件，不是赌时序。
 * （唯一一处 `delay(300)` 是 transfer 测试里被测行为本身——「一个要跑一会儿的活」，
 * 不是同步手段。）
 */
class ChannelOrchestratorTest {

    private val scope = CoroutineScope(Job() + kotlinx.coroutines.Dispatchers.Unconfined)

    /** 编排器只把 Entry 当**不透明句柄**传给 execute —— 夹具构造最小形状即可。 */
    private fun fixtureEntry(): PluginRegistry.Entry =
        PluginRegistry.Entry(
            manifest = PluginManifest(
                pluginId = "worker",
                name = "worker",
                leader = TeamMemberInfo(agentId = "w1", name = "worker", role = "leader"),
            ),
            baseUrl = "http://127.0.0.1:1",
            httpPort = 1,
            agentSenderIds = emptyList(),
            registeredAt = 0,
        )

    private fun payload(
        channelId: String,
        resources: List<String> = emptyList(),
        op: String = "assign",
        deadlineMs: Long? = 60000,
    ) = AgentChannelPayload(
        channelId = channelId, op = op, fromAgent = "caller-agent", toAgent = "worker-agent",
        task = "do things", resources = resources, deadlineMs = deadlineMs,
    )

    @Test
    fun sameResourceTasksRunOneAfterTheOtherInFairOrder() = runBlocking {
        val c1CanFinish = CompletableDeferred<Unit>()
        val c2Started = Channel<Unit>(1)
        val replies = Channel<Pair<String, Boolean>>(8)
        val orchestrator = ChannelOrchestrator(
            resolve = { fixtureEntry() },
            execute = { _, p ->
                if (p.channelId == "c-1") {
                    c1CanFinish.await() // 挂起不放锁：锁还攥在 c-1 手里。
                }
                if (p.channelId == "c-2") c2Started.send(Unit)
                ChannelOrchestrator.Outcome(ok = true, summary = p.channelId)
            },
            sendResult = { _, channelId, ok, _, _, _, _ -> replies.send(channelId to ok) },
            scope = scope,
        )

        // Unconfined：offer 内联跑到 execute 的首个挂起点（c1CanFinish.await）才返回。
        orchestrator.offer(payload("c-1", resources = listOf("gpu")), fromNode = "peer-a")
        orchestrator.offer(payload("c-2", resources = listOf("gpu")), fromNode = "peer-a")
        // c-2 的 offer 内联到 gpu 锁的挂起点才让出（Unconfined 同步语义）⇒ offer 返回时
        // 状态已定：要么越权拿到锁并跑完（信号已在通道），要么挂在排队（通道必空）。
        // 「还没跑到」这个第三态不存在 —— 不需要再赌 200ms 二次探测（envDependentTestGate
        // B 类：固定 delay 赌同步）。
        val leaked = c2Started.tryReceive().isSuccess
        assertTrue(!leaked, "c-1 放锁前 c-2 不许开始（互斥失效）")
        c1CanFinish.complete(Unit)
        withTimeout(5_000) { c2Started.receive() }
        assertEquals(
            listOf("c-1" to true, "c-2" to true),
            listOf(withTimeout(5_000) { replies.receive() }, withTimeout(5_000) { replies.receive() }),
            "同资源 FIFO：先 offer 先跑，一条接一条，两笔都成功回执",
        )
    }

    @Test
    fun tasksWithoutDeclaredResourcesRunConcurrently() = runBlocking {
        val firstArrived = CompletableDeferred<Unit>()
        val resumeCA = CompletableDeferred<Unit>()
        val cbDone = CompletableDeferred<Unit>()
        val orchestrator = ChannelOrchestrator(
            resolve = { fixtureEntry() },
            execute = { _, p ->
                if (p.channelId == "c-a") {
                    firstArrived.complete(Unit)
                    resumeCA.await() // 第一笔挂在这里：不持任何锁，只等测试放行。
                }
                if (p.channelId == "c-b") cbDone.complete(Unit)
                ChannelOrchestrator.Outcome(ok = true, summary = p.channelId)
            },
            sendResult = { _, _, _, _, _, _, _ -> },
            scope = scope,
        )
        // Unconfined：offer(c-a) 内联跑到 resumeCA.await() 挂起。
        orchestrator.offer(payload("c-a"), fromNode = "peer-a")
        withTimeout(5_000) { firstArrived.await() }
        // 并行判据：c-a 还挂起时，无资源的 c-b 必须**内联跑完**。
        // 若实现错把所有任务串行化（比如将来有人加一把全局锁），c-b 的完成信号
        // 在 c-a 放行前永远到不了 ⇒ 断言超时红。
        orchestrator.offer(payload("c-b"), fromNode = "peer-a")
        withTimeout(5_000) { cbDone.await() }
        assertTrue(!resumeCA.isCompleted, "c-b 跑完时 c-a 仍应挂起（互不等待）")
        resumeCA.complete(Unit)
        Unit
    }

    @Test
    fun duplicateChannelIdIsIgnoredInPlace() = runBlocking {
        var executions = 0
        val orchestrator = ChannelOrchestrator(
            resolve = { fixtureEntry() },
            execute = { _, _ ->
                executions += 1
                ChannelOrchestrator.Outcome(ok = true)
            },
            sendResult = { _, _, _, _, _, _, _ -> },
            scope = scope,
        )
        orchestrator.offer(payload("c-dup"), fromNode = "peer-a")
        // Unconfined：offer 返回时第一笔已内联执行完毕。
        assertEquals(1, executions, "第一笔执行")
        orchestrator.offer(payload("c-dup"), fromNode = "peer-a")
        assertEquals(1, executions, "幂等：同一 channelId 只执行一次")
    }

    @Test
    fun queuedAssignExceedingDeadlineAnswersTimeoutAndReleasesTheLock() = runBlocking {
        val c1CanFinish = CompletableDeferred<Unit>()
        val replies = Channel<Triple<String, Boolean, String>>(8)
        val orchestrator = ChannelOrchestrator(
            resolve = { fixtureEntry() },
            execute = { _, p ->
                if (p.channelId == "c-1") c1CanFinish.await()
                ChannelOrchestrator.Outcome(ok = true, summary = p.channelId)
            },
            sendResult = { _, channelId, ok, status, _, _, _ ->
                replies.send(Triple(channelId, ok, status))
            },
            scope = scope,
        )
        orchestrator.offer(payload("c-1", resources = listOf("gpu")), fromNode = "peer-a")
        // c-2 只带 150ms 租约：排在 c-1 后面必超时。
        orchestrator.offer(
            payload("c-2", resources = listOf("gpu"), deadlineMs = 150),
            fromNode = "peer-a",
        )
        // c-3 用 60s 租约：若 c-2 超时后没放锁，c-3 的成功回执在测试窗口内到不了
        // ⇒ receive 超时红 —— 这正是「timeout 出队 = 锁已释放」的判别读数。
        orchestrator.offer(
            payload("c-3", resources = listOf("gpu"), deadlineMs = 60000),
            fromNode = "peer-a",
        )
        // 回执到达序由因果决定：c-2 的 timeout 在租约 150ms 后必然先到（c-1 与 c-3
        // 的回执都在测试放行 c1CanFinish 之后）—— 先读它，再放行，最后读两笔成功。
        val c2Reply = withTimeout(5_000) { replies.receive() }
        assertEquals("c-2", c2Reply.first, "第一条回执来自超时的 c-2")
        assertEquals(false, c2Reply.second, "超时 = 失败回执")
        assertEquals("timeout", c2Reply.third, "status 复用 InvokeResultStates.TIMEOUT")
        c1CanFinish.complete(Unit)
        // 放行之后的两笔成功回执，**谁先到不是因果事实**：c-1 的 send 与放锁、
        // c-3 的锁唤醒分属不同协程，相对次序是调度细节 —— 两向都实测出现过
        // （首版断言 c-1 先到，红在 actual=(c-3,done)；改成 c-3 先到后又在别的
        // 轮次红在 actual=(c-1,done)，约 3 轮 1 红）。所以只断言**集合**：
        // 两笔都到、都是 done —— 顺序不进断言。「锁已释放」的判别读数仍在：
        // c-3 排队在 gpu 锁后面，它的 done 能在窗口内到达本身就需要 c-1 先放锁，
        // 集合两笔齐 = 超时出队与放锁两件事都发生了。
        val successes = listOf(
            withTimeout(5_000) { replies.receive() },
            withTimeout(5_000) { replies.receive() },
        ).map { it.first to (it.second to it.third) }.toSet()
        assertEquals(
            setOf("c-3" to (true to "done"), "c-1" to (true to "done")),
            successes,
            "c-2 超时出队后 c-1 与 c-3 都正常完成（c-3 到达 = 锁已释放的判别读数）",
        )
        Unit
    }

    @Test
    fun missingPluginAnswersNoPluginHonestly() = runBlocking {
        val report = CompletableDeferred<Pair<Boolean, String?>>()
        val orchestrator = ChannelOrchestrator(
            resolve = { null },
            execute = { _, _ -> ChannelOrchestrator.Outcome(ok = true) },
            sendResult = { _, _, ok, status, _, _, _ -> report.complete(ok to status) },
            scope = scope,
        )
        orchestrator.offer(payload("c-none"), fromNode = "peer-a")
        val (ok, status) = withTimeout(5_000) { report.await() }
        assertEquals(false, ok, "没承接者 = 失败回执")
        assertEquals("no-plugin", status, "status 是 no-plugin（配置问题），不是 error（执行问题）")
    }

    @Test
    fun transferWithoutLeaseRunsBeyondAnyDeadline() = runBlocking {
        val finished = CompletableDeferred<Boolean>()
        // 活体用一个可编排的挂起点代替真实时长（envDependentTestGate B 类：固定 delay 赌
        // 同步）：transfer 没有租约 ⇒ 实现里没有任何 withTimeout 包住它 —— 任务挂多久都
        // 必须等到回执。判据是「存在性」，不是「多久」；若将来有人在 transfer 上误加租约，
        // 挂起点永远不会被预算放行 ⇒ 断言在回执缺席处红。
        val workCanProceed = CompletableDeferred<Unit>()
        val orchestrator = ChannelOrchestrator(
            resolve = { fixtureEntry() },
            execute = { _, _ ->
                workCanProceed.await()
                ChannelOrchestrator.Outcome(ok = true, summary = "transferred")
            },
            sendResult = { _, _, ok, _, _, _, _ -> finished.complete(ok) },
            scope = scope,
        )
        orchestrator.offer(
            payload("c-t", op = "transfer", deadlineMs = null),
            fromNode = "peer-a",
        )
        // Unconfined：offer 返回时任务已内联挂起在 workCanProceed —— 若实现误把 transfer
        // 包进租约，此刻回执已带着 timeout 出现。互斥正确 ⇒ finished 还活着且无回执。
        assertTrue(finished.isActive, "挂起中的 transfer 不许被任何预算掐断（回执未出现）")
        workCanProceed.complete(Unit)
        withTimeout(5_000) { assertTrue(finished.await(), "transfer 完成并有回执") }
    }

    @Test
    fun executionExceptionAnswersErrorHonestly() = runBlocking {
        val report = CompletableDeferred<Pair<Boolean, String?>>()
        val orchestrator = ChannelOrchestrator(
            resolve = { fixtureEntry() },
            execute = { _, _ -> error("plugin exploded") },
            sendResult = { _, _, ok, status, _, _, _ -> report.complete(ok to status) },
            scope = scope,
        )
        orchestrator.offer(payload("c-err"), fromNode = "peer-a")
        val (ok, status) = withTimeout(5_000) { report.await() }
        assertEquals(false, ok)
        assertEquals("error", status, "执行异常如实答 error，不静默死在无 handler 的 Job 里")
    }
}
