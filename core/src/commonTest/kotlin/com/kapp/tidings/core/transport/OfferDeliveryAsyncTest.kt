// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 报价的**异步投递 + 只读出口**（task-62）。
 *
 * 真机症状：`POST /v1/send-file` 会阻塞到客户端超时（20/25s），因为请求路径上
 * `runBlocking { offerFiles(...) }` 要**逐片等 ACK**（对端不可达时走完整退避 + TCP 兜底）。
 *
 * 这条测试钉住修法的两条硬要求：
 *  1. `offerFilesAsync` **不等网络就返回** transferId（用一个永远不完成的 sendReliable 证明）；
 *  2. 投递状态**可读**（offering → ack-timeout）—— 没有这个出口的 async 等于把
 *     「发不出去」变成「看起来发出去了」。
 */
class OfferDeliveryAsyncTest {

    private val dirs = mutableListOf<String>()

    /**
     * 测试自己的 scope —— **不能用 `runBlocking` 的 scope**：
     * manager 的退避重试会挂一个 `delay` 在它上面，测试会被那个 delay 拖到超时。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanup() {
        scope.cancel()
        dirs.forEach { removeRecursively(it) }
        dirs.clear()
    }

    private fun dir(): String = createTempDir("relay-offer-async").also { dirs += it }

    @Test
    fun theAsyncOfferReturnsBeforeTheAckAndReportsItsOutcome() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val manager = TransferManager(
            selfId = "self",
            scope = scope,
            tcpPort = 0,
            downloadDir = { dir() },
            sendReliable = { _, _ ->
                gate.await()
                false
            },
            // task-63：失败之后会入队重试 —— 这条测试只关心「入队那一刻如实说了什么」，
            // 所以把退避拉到很大，让它在测试窗口内不重试（重试由下一条测试钉）。
            offerRetryBackoffMs = 60_000L,
            peerAddress = { null },
        )
        val id = manager.offerFilesAsync("peer-1", listOf(LocalFile("/tmp/a.bin", "a.bin", 16L)))
        assertNotNull(id, "登记即返回：不该等网络")

        val offering = manager.offerDeliveries().single { it.transferId == id }
        assertEquals("offering", offering.state, "投递还没完成时状态必须如实是 offering")

        gate.complete(Unit)
        withTimeout(3_000) {
            while (manager.offerDeliveries().single { it.transferId == id }.state == "offering") delay(20)
        }
        val after = manager.offerDeliveries().single { it.transferId == id }
        // task-63 起，失败的报价不再记 ack-timeout 就完事，而是**入队等恢复** —— 状态必须如实。
        assertEquals(
            "queued-awaiting-reconnect",
            after.state,
            "sendReliable=false ⇒ 如实报已入队等恢复，而不是假装成功、也不是丢掉",
        )
        assertTrue(after.detail?.contains("未拿到 ACK") == true, "要留下为什么：" + after.detail)
        assertTrue(after.elapsedMs >= 0L)
    }

    /**
     * **失败的报价入队并自动补发**（task-63）：蜂窝下长连接会抖是常态，报价不该丢。
     *
     * 形状与消息队列同源：幂等键（transferId + seq）+ 水位（attempts/预算）+ 失败释放。
     * 这条钉：第一次投递失败 ⇒ 状态如实是 `queued-awaiting-reconnect`；重试成功 ⇒ `offered`。
     */
    @Test
    fun aFailedOfferIsQueuedAndRetriedUntilItSucceeds() = runBlocking {
        var attempts = 0
        val manager = TransferManager(
            selfId = "self",
            scope = scope,
            tcpPort = 0,
            downloadDir = { dir() },
            sendReliable = { _, _ -> error("报价必须走 quick 那条路") },
            sendReliableQuick = { _, _ ->
                attempts++
                attempts >= 2
            },
            offerRetryBackoffMs = 20L,
            offerDeliveryBudgetMs = 5_000L,
            peerAddress = { null },
        )
        val id = manager.offerFilesAsync("peer-1", listOf(LocalFile("/tmp/a.bin", "a.bin", 16L)))!!
        // 第一次失败之后必须**如实入队**（不是 ack-timeout 就完事）。
        val queued = awaitState(manager, id) { it != "offering" }
        assertEquals("queued-awaiting-reconnect", queued.state, "失败的报价必须入队等恢复，而不是丢掉")
        // 重试成功之后转 offered，且 attempts 如实增长。
        val done = awaitState(manager, id) { it == "offered" }
        assertEquals("offered", done.state)
        assertTrue(done.attempts >= 2, "应当记下尝试次数：" + done.attempts)
    }

    /**
     * **总预算用尽 ⇒ gave-up**（task-63 的 (b)）：失败要快而可见，不许永远停在「等恢复」。
     */
    @Test
    fun anOfferThatNeverGetsThroughEventuallyGivesUp() = runBlocking {
        val manager = TransferManager(
            selfId = "self",
            scope = scope,
            tcpPort = 0,
            downloadDir = { dir() },
            sendReliable = { _, _ -> false },
            sendReliableQuick = { _, _ -> false },
            offerRetryBackoffMs = 20L,
            offerDeliveryBudgetMs = 80L,
            peerAddress = { null },
        )
        val id = manager.offerFilesAsync("peer-1", listOf(LocalFile("/tmp/a.bin", "a.bin", 16L)))!!
        val gaveUp = awaitState(manager, id) { it == "gave-up" }
        assertEquals("gave-up", gaveUp.state)
        assertTrue(gaveUp.detail?.contains("预算用尽") == true, "要写清为什么放弃：" + gaveUp.detail)
    }

    /**
     * **换网立刻重试**（task-63 追加）：退避被拉到 60s，所以这条只有在 nudge 生效时才会绿 ——
     * 而且 detail 必须能看出「这次是 nudge 触发的，不是退避到点」。
     */
    @Test
    fun aNudgeRetriesQueuedOffersImmediatelyAndSaysSo() = runBlocking {
        var attempts = 0
        val manager = TransferManager(
            selfId = "self",
            scope = scope,
            tcpPort = 0,
            downloadDir = { dir() },
            sendReliable = { _, _ -> false },
            sendReliableQuick = { _, _ ->
                attempts++
                attempts >= 2
            },
            offerRetryBackoffMs = 60_000L,
            offerDeliveryBudgetMs = 5_000L,
            peerAddress = { null },
        )
        val id = manager.offerFilesAsync("peer-1", listOf(LocalFile("/tmp/a.bin", "a.bin", 16L)))!!
        val queued = awaitState(manager, id, timeoutMs = 3_000L) { it == "queued-awaiting-reconnect" }
        assertEquals("queued-awaiting-reconnect", queued.state)
        // 换网触发：不等那 60s 退避。
        manager.retryQueuedOffersNow()
        val done = awaitState(manager, id, timeoutMs = 3_000L) { it == "offered" }
        assertEquals("offered", done.state)
        assertTrue(
            done.detail?.contains("nudge") == true,
            "detail 要能看出这次是 nudge 触发的：" + done.detail,
        )
    }

    /**
     * **单次尝试有上限**（task-63 追加）：quickSchedule 只是缩短每个端点的退避，但候选数是会长的
     * （真机：6 个候选 × 快试 + TCP 再遍历一遍 ⇒ 一次尝试几十秒，读的人以为卡在 offering）。
     * 这条钉：一次尝试到点就被切断并按「没拿到 ACK」处理 ⇒ 状态很快变 queued-awaiting-reconnect。
     */
    @Test
    fun asingleAttemptIsBoundedSoTheStateNeverSitsOnOffering() = runBlocking {
        val manager = TransferManager(
            selfId = "self",
            scope = scope,
            tcpPort = 0,
            downloadDir = { dir() },
            sendReliable = { _, _ -> false },
            // 永远不返回：模拟「链路半死、候选很多、TCP 兜底也挂住」。
            sendReliableQuick = { _, _ ->
                delay(60_000L)
                false
            },
            offerAttemptTimeoutMs = 120L,
            offerRetryBackoffMs = 60_000L,
            offerDeliveryBudgetMs = 5_000L,
            peerAddress = { null },
        )
        val id = manager.offerFilesAsync("peer-1", listOf(LocalFile("/tmp/a.bin", "a.bin", 16L)))!!
        // 上限 120ms ⇒ 等 500ms 再读一次就足够；不用轮询（轮询会与投递协程抢 stateLock）。
        delay(500L)
        val row = manager.offerDeliveries().single { it.transferId == id }
        assertEquals("queued-awaiting-reconnect", row.state, "一次尝试到点就必须给结论，不许停在 offering")
    }

    /**
     * **首次与最后一次的诊断各留各的**（task-65）。
     *
     * 真机现场：四次里两次是「第 2/3 次才成功」，而成功那次的诊断写着 `sent-via-link` ⇒
     * **第一次为什么失败完全看不出来**（`attempts=3` 只说试了几次）⇒ 首次成功率 50% 这个数
     * 没有可行动的方向。这条钉：首次的诊断要留下、且与最后一次**分开**（不许合成一个）。
     */
    @Test
    fun theFirstAttemptDiagnosticsSurviveAndStaySeparateFromTheLast() = runBlocking {
        var diagnosticsCalls = 0
        var attempts = 0
        val manager = TransferManager(
            selfId = "self",
            scope = scope,
            tcpPort = 0,
            downloadDir = { dir() },
            sendReliable = { _, _ -> false },
            sendReliableQuick = { _, _ ->
                attempts++
                attempts >= 2
            },
            sendReliableDiagnostics = {
                diagnosticsCalls++
                if (diagnosticsCalls <= 1) {
                    // 首次：端点全 no-ack ⇒ 这就是「首次为什么失败」的判据。
                    ReliableSendDiagnostics(
                        peerId = "peer-1",
                        envelopeId = "e-first",
                        outcome = "queued",
                        elapsedMs = 7_000L,
                        endpoints = listOf(ReliableEndpointAttempt("2001:db8::1", 17878, acked = false, elapsedMs = 6_800L)),
                        tcpTried = true,
                        tcpOk = false,
                        tcpElapsedMs = 1_200L,
                        linkConnected = true,
                        peerOnline = true,
                    )
                } else {
                    ReliableSendDiagnostics(
                        peerId = "peer-1",
                        envelopeId = "e-last",
                        outcome = "sent-via-link",
                        elapsedMs = 30L,
                        endpoints = emptyList(),
                        tcpTried = false,
                        tcpOk = false,
                        tcpElapsedMs = 0L,
                        linkConnected = true,
                        peerOnline = true,
                    )
                }
            },
            offerRetryBackoffMs = 20L,
            offerDeliveryBudgetMs = 5_000L,
            peerAddress = { null },
        )
        val id = manager.offerFilesAsync("peer-1", listOf(LocalFile("/tmp/a.bin", "a.bin", 16L)))!!
        val done = awaitState(manager, id, timeoutMs = 3_000L) { it == "offered" }
        assertEquals("offered", done.state)
        assertTrue(
            done.firstAttemptSummary?.contains("no-ack") == true,
            "首次诊断必须留下「首次为什么失败」：" + done.firstAttemptSummary,
        )
        assertTrue(
            done.lastSendSummary?.contains("sent-via-link") == true,
            "最后一次应当是成功那次：" + done.lastSendSummary,
        )
        assertNotEquals(
            done.firstAttemptSummary,
            done.lastSendSummary,
            "首次与最后一次**各留各的**，不许合成一个（否则下次又有人读错）",
        )
    }

    /** 轮询等某个状态出现（超时即失败）。 */
    private suspend fun awaitState(
        manager: TransferManager,
        transferId: String,
        timeoutMs: Long = 5_000L,
        predicate: (String) -> Boolean,
    ): TransferManager.OfferDelivery {
        val deadline = nowMillis() + timeoutMs
        while (nowMillis() < deadline) {
            val row = manager.offerDeliveries().firstOrNull { it.transferId == transferId }
            if (row != null && predicate(row.state)) return row
            delay(10)
        }
        error("超时等不到状态：" + manager.offerDeliveries().firstOrNull { it.transferId == transferId })
    }

    /**
     * **每一条 transfer 带自己的逐端点诊断**（task-62 关联补丁）。
     *
     * 真机现场：z1/z2 ack-timeout、z3 成功，而全局 lastSend 显示的是 z3（156ms、走长连接）——
     * 读的人拿它当失败那两次的现场，**反而误导**。所以诊断要绑在**这条 transfer** 上。
     */
    @Test
    fun theDeliveryCarriesItsOwnEndpointDiagnosticsNotTheGlobalLastOne() = runBlocking {
        val manager = TransferManager(
            selfId = "self",
            scope = scope,
            tcpPort = 0,
            downloadDir = { dir() },
            sendReliable = { _, _ -> false },
            sendReliableDiagnostics = {
                ReliableSendDiagnostics(
                    peerId = "peer-1",
                    envelopeId = "e1",
                    outcome = "queued",
                    elapsedMs = 17_500L,
                    endpoints = listOf(ReliableEndpointAttempt("192.168.2.140", 17878, acked = false, elapsedMs = 7_387L)),
                    tcpTried = true,
                    tcpOk = false,
                    tcpElapsedMs = 1_367L,
                    linkConnected = false,
                    peerOnline = false,
                )
            },
            offerRetryBackoffMs = 60_000L,
            peerAddress = { null },
        )
        val id = manager.offerFilesAsync("peer-1", listOf(LocalFile("/tmp/a.bin", "a.bin", 16L)))!!
        withTimeout(3_000) {
            while (manager.offerDeliveries().single { it.transferId == id }.state == "offering") delay(20)
        }
        val row = manager.offerDeliveries().single { it.transferId == id }
        assertEquals("queued-awaiting-reconnect", row.state, "task-63 起失败即入队（不再记 ack-timeout）")
        assertTrue(
            row.lastSendSummary?.contains("192.168.2.140:17878 no-ack 7387ms") == true,
            "这条 transfer 必须带上**它自己**那次的逐端点诊断：" + row.lastSendSummary,
        )
    }
}
