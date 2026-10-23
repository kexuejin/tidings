// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.makeDirectories
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.FileCtlOffer
import com.kapp.tidings.core.protocol.FileCtlOps
import com.kapp.tidings.core.protocol.FileMeta
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 传输的**生命周期出口**：残留 `.part`、重启后挂着的 offer / 出站报价、用户放弃。
 *
 * 为什么单独立一个文件：这三件事在审计里是同一个形状 —— API 都在，但**没有任何调用点**
 * （`discardPart` / `discardPartial` 零调用、`pendingOffers` / `pendingOutgoing` 只被
 * `NodeRuntime` 的 wrapper 转发而 wrapper 自己也没人调）。零调用点的 API 不是"以后会用"，
 * 是"这条路径今天根本不存在"：`.part` 只会越攒越多，重启后界面上的"待接收"卡片会永远停着。
 *
 * 这里钉的是三条**契约**，不是实现细节：
 * 1. 启动时无主的 `.part` 必须被清掉，而**同一目录里的正式文件与不是这个后缀的文件不能动**；
 * 2. 拿不准的根目录（`"."` / 相对路径）宁可不扫 —— 上层把下载目录配错时不能变成"删用户文件"；
 * 3. 停机时挂着的 offer / 出站报价必须**显式作废并告知上层**（事件 + 清单），
 *    用户点放弃时那条半成品必须真的消失。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferLifecycleTest {

    // ---------------------------------------------------------------- 工具

    private fun newManager(scope: CoroutineScope, dir: String): TransferManager = TransferManager(
        selfId = "self-node",
        scope = scope,
        tcpPort = 0,
        downloadDir = { dir },
        sendReliable = { _, _: JsonElement -> true },
        peerAddress = { null },
        peerSupportsDirect = { false },
    )

    private fun oneFile() = listOf(LocalFile(path = "/tmp/a.bin", name = "a.bin", size = 3L))

    private fun offerEnvelope(transferId: String): Envelope = makeEnvelope(
        type = MsgTypes.FILE_CTL,
        from = "peer-node",
        payload = Codec.wireJson.encodeToJsonElement(
            FileCtlOffer.serializer(),
            FileCtlOffer(
                op = FileCtlOps.OFFER,
                transferId = transferId,
                seq = 1,
                total = 1,
                files = listOf(FileMeta(fileId = "file-1", path = "a.bin", size = 3L)),
                totalSize = 3L,
                fileCount = 1,
                rootName = "a.bin",
            ),
        ),
    )

    private fun writeBytes(path: String, size: Int) {
        val handle = openRandomAccess(path, forWrite = true)
        handle.append(ByteArray(size) { 0x41 }, size)
        handle.flush()
        handle.close()
    }

    /** 轮询等待一个异步条件（真 socket 的测试用；`runTest` 那几个用 `advanceUntilIdle`）。 */
    private suspend fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        val ok = withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(10)
            true
        } ?: false
        assertTrue(ok, "等待超时：$what")
    }

    /** 从事件通道里等到第一条满足 [predicate] 的事件；超时返回 null。 */
    private suspend fun awaitEvent(
        channel: Channel<TransferEvent>,
        timeoutMs: Long = 5_000,
        predicate: (TransferEvent) -> Boolean,
    ): TransferEvent? {
        var hit: TransferEvent? = null
        withTimeoutOrNull(timeoutMs) {
            while (hit == null) {
                val event = channel.receive()
                if (predicate(event)) hit = event
            }
        }
        return hit
    }

    // ---------------------------------------------------------------- 启动清扫

    @Test
    fun startupSweepDeletesStalePartsAndKeepsEveryOtherFile() = runBlocking {
        val dir = createTempDir("relay-lifecycle-start")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = newManager(scope, dir)
        try {
            writeBytes(pathJoin(dir, "a.bin.part"), 10)
            makeDirectories(pathJoin(dir, "pkg"))
            writeBytes(pathJoin(dir, "pkg", "b.bin.part"), 5)
            writeBytes(pathJoin(dir, "keep.txt"), 3)
            // 后缀相近但不是半成品：`note.partial` 是用户自己的文件，不许碰。
            writeBytes(pathJoin(dir, "note.partial"), 4)
            assertEquals(2, manager.leftoverParts().size, "前提：两个无主的 .part")

            assertTrue(manager.start(), "TCP 监听必须绑得上（端口 0）")

            val report = assertNotNull(manager.stalePartSweep, "start() 必须留下清扫结果")
            assertTrue(report.scanned, "绝对路径的下载目录应当被扫")
            assertEquals(2, report.deleted.size, "两个无主 .part 都要删：${report.deleted}")
            assertEquals(15L, report.freedBytes, "释放的字节数按 fileSize 累加")
            assertTrue(report.failed.isEmpty(), "不该有删不掉的文件：${report.failed}")

            assertFalse(fileExists(pathJoin(dir, "a.bin.part")), "顶层半成品必须消失")
            assertFalse(fileExists(pathJoin(dir, "pkg", "b.bin.part")), "子目录里的也要消失")
            assertTrue(fileExists(pathJoin(dir, "keep.txt")), "正式文件不能动")
            assertTrue(fileExists(pathJoin(dir, "note.partial")), "后缀不同就不是我们的文件")
            assertTrue(manager.leftoverParts().isEmpty())
        } finally {
            manager.stop()
            scope.cancel()
            removeRecursively(dir)
        }
    }

    @Test
    fun sweepHonoursKeepListSoAnActivePullSurvives() = runTest {
        val dir = createTempDir("relay-lifecycle-keep")
        val alive = pathJoin(dir, "alive.bin.part")
        val dead = pathJoin(dir, "dead.bin.part")
        writeBytes(alive, 5)
        writeBytes(dead, 6)
        val manager = newManager(this, dir)

        val report = manager.sweepStaleParts(keep = setOf(alive))

        assertEquals(listOf(dead), report.deleted)
        assertEquals(6L, report.freedBytes)
        assertTrue(report.failed.isEmpty())
        assertTrue(fileExists(alive), "在途拉取将来会写的 .part 不能被自己的清扫删掉")
        assertFalse(fileExists(dead))
        removeRecursively(dir)
    }

    @Test
    fun sweepRefusesRootsItCannotVouchFor() = runTest {
        // `.` 就是进程 CWD：agentNode 起 NodeRuntime 时没配下载目录，默认值正是这个。
        // `.part` 这个后缀太普通（浏览器、下载器都在用），在里面删文件等于替用户清理别人的残留。
        val manager = newManager(this, ".")
        val report = manager.sweepStaleParts()

        assertFalse(report.scanned, "拿不准的根目录必须报 scanned=false，而不是报'很干净'")
        assertTrue(report.deleted.isEmpty())
        assertTrue(manager.leftoverParts().isEmpty(), "不扫的时候连读都不读")
        assertTrue(report.root.isNotEmpty(), "但要把当时的根目录原样交回，方便上层解释为什么没扫")
    }

    // ---------------------------------------------------------------- 重启对账

    @Test
    fun backlogReportsPendingItemsAndLeftoverParts() = runTest {
        val dir = createTempDir("relay-lifecycle-backlog")
        val ghost = pathJoin(dir, "ghost.bin.part")
        writeBytes(ghost, 7)
        val manager = newManager(this, dir)

        val outgoingId = assertNotNull(manager.offerFiles("peer-node", oneFile()))
        manager.onEnvelope(offerEnvelope("t-in"))
        advanceUntilIdle()

        val backlog = manager.backlog()
        assertEquals(setOf("t-in", outgoingId), backlog.pendingTransferIds, "两张内存表都要报")
        assertEquals(listOf(ghost), backlog.leftoverParts.map { it.path })
        assertEquals(7L, backlog.leftoverParts.single().size)
        assertFalse(backlog.isEmpty)

        // 上层对完账后清残留：同一份 `leftoverParts` 的路径就是清扫删掉的那些。
        val report = manager.sweepStaleParts()
        assertTrue(report.deleted.contains(ghost), "对账报出来的残留必须能被清掉：${report.deleted}")
        assertTrue(manager.backlog().leftoverParts.isEmpty())
        removeRecursively(dir)
    }

    // ---------------------------------------------------------------- 显式作废

    @Test
    fun stopExplicitlyVoidsPendingOffersAndOutgoingAndTellsTheUpperLayer() = runTest {
        val dir = createTempDir("relay-lifecycle-abandon")
        val manager = newManager(this, dir)
        val events = Channel<TransferEvent>(Channel.UNLIMITED)
        // UNDISPATCHED：先同步把订阅挂上，否则 SharedFlow(replay=0) 会把事件直接丢掉。
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.events.collect { events.send(it) }
        }

        val outgoingId = assertNotNull(manager.offerFiles("peer-node", oneFile()))
        manager.onEnvelope(offerEnvelope("t-in"))
        advanceUntilIdle()
        assertEquals(listOf("t-in"), manager.pendingOffers().map { it.transferId })
        assertEquals(listOf(outgoingId), manager.pendingOutgoing().map { it.transferId })

        manager.stop()
        advanceUntilIdle()

        assertTrue(manager.pendingOffers().isEmpty(), "停机后不能还挂着'待接收'")
        assertTrue(manager.pendingOutgoing().isEmpty(), "停机后不能还挂着'已发出、等对方接受'")

        val report = assertNotNull(manager.lastAbandon, "stop() 必须留下作废清单")
        assertEquals(ABANDON_REASON_SHUTDOWN, report.reason)
        assertEquals(setOf("t-in", outgoingId), report.transferIds)
        assertEquals(listOf("t-in"), report.offers.map { it.transferId })
        assertEquals(listOf(outgoingId), report.outgoing.map { it.transferId })
        assertFalse(report.isEmpty)

        // 清单是给"重启对账"用的；事件是给"当场就画着卡片"的会话用的。两者都要有。
        val declined = awaitEvent(events) { it is TransferEvent.Declined && it.transferId == "t-in" }
        assertNotNull(declined, "入站邀请作废要发 Declined，否则卡片永远停着")
        val failed = awaitEvent(events) {
            it is TransferEvent.Failed && it.transferId == outgoingId &&
                !it.incoming && it.reason == ABANDON_REASON_SHUTDOWN
        }
        assertNotNull(failed, "出站报价作废要发 Failed(incoming=false, reason=$ABANDON_REASON_SHUTDOWN)")
        collector.cancel()
        removeRecursively(dir)
    }

    @Test
    fun cancelIncomingRemovesAPendingOfferAndReportsTheVoid() = runTest {
        val dir = createTempDir("relay-lifecycle-cancel-pending")
        val manager = newManager(this, dir)
        val events = Channel<TransferEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.events.collect { events.send(it) }
        }
        manager.onEnvelope(offerEnvelope("t-pending"))
        advanceUntilIdle()

        assertTrue(manager.cancelIncoming("t-pending"), "存在才算放弃成功")
        advanceUntilIdle()

        assertTrue(manager.pendingOffers().isEmpty())
        assertNotNull(
            awaitEvent(events) { it is TransferEvent.Declined && it.transferId == "t-pending" },
            "用户放弃也要发 Declined（与拒收同一个出口）",
        )
        assertFalse(manager.cancelIncoming("t-pending"), "重复放弃要报 false，别记一条假动作")
        collector.cancel()
        removeRecursively(dir)
    }

    // ---------------------------------------------------------------- 在途拉取

    /**
     * 真 socket：对端只接受连接、**从不应答 `pull`**，于是拉取会停在等 `pull-ok` 上，
     * 但 `.part` 已经落盘 —— 正好是"用户放弃一个正在下载的文件"的现场。
     */
    @Test
    fun cancelIncomingDiscardsThePartOfARunningPull() = runBlocking {
        val dir = createTempDir("relay-lifecycle-cancel-active")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val selector = SelectorManager(Dispatchers.Default)
        val server = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
        val port = (server.localAddress as InetSocketAddress).port
        val accepted = mutableListOf<Socket>()
        scope.launch {
            while (true) {
                accepted += try {
                    server.accept()
                } catch (_: Exception) {
                    return@launch
                }
            }
        }
        val manager = TransferManager(
            selfId = "self-node",
            scope = scope,
            tcpPort = 0,
            downloadDir = { dir },
            sendReliable = { _, _: JsonElement -> true },
            peerAddress = { PeerEndpoint("127.0.0.1", port) },
            peerSupportsDirect = { false },
        )
        val events = Channel<TransferEvent>(Channel.UNLIMITED)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { manager.events.collect { events.send(it) } }
        try {
            manager.onEnvelope(offerEnvelope("t-run"))
            awaitUntil("offer 挂成待接收") { manager.pendingOffers().size == 1 }

            manager.accept("t-run")
            val part = pathJoin(dir, "a.bin", "a.bin.part")
            awaitUntil("拉取已开写 .part") { fileExists(part) }
            assertTrue(manager.activeIncomingIds().contains("t-run"), "在途拉取必须可被找到")

            assertTrue(manager.cancelIncoming("t-run"), "放弃一个在途拉取")

            assertFalse(fileExists(part), "用户放弃后不许留下半成品")
            awaitUntil("拉取已注销") { manager.activeIncomingIds().isEmpty() }
            // 本地取消不是网络失败：报 Declined（卡片收掉），而不是画一张红色的失败卡。
            assertNotNull(
                awaitEvent(events) { it is TransferEvent.Declined && it.transferId == "t-run" },
                "取消在途拉取要报 Declined 而不是 Failed",
            )
        } finally {
            manager.stop()
            runCatching { server.close() }
            accepted.forEach { runCatching { it.close() } }
            runCatching { selector.close() }
            scope.cancel()
            removeRecursively(dir)
        }
        Unit
    }
}
