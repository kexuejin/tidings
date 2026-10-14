// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.FileCtlOffer
import app.relay.core.protocol.FileCtlOps
import app.relay.core.protocol.FileCtlSimple
import app.relay.core.protocol.FileMeta
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.makeEnvelope
import app.relay.core.testing.createTempDir
import app.relay.core.testing.removeRecursively
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 对端发来 `file-ctl{op:decline|cancel}` 时，**接收侧**的收尾。
 *
 * 为什么单独立一个文件：[onPeerKilled] 一开始只查两张表 —— 出站的 `outgoing` 与
 * 自己还没点过头的 `offers`。而一份**已经点了「接收」**的传输根本不在那两张表里：
 * 它在 `acceptedOffers`（已接受、还没开拉）或 `pulling`（正在拉）。于是"对方取消了"
 * 这件事在接收侧曾经**没有任何反应** —— 卡片继续转圈、`.part` 继续长大，
 * 用户只能等它自己空闲超时（60s）才变成失败卡。发送侧点了取消，接收侧却不知道。
 *
 * 这里钉的是三条契约，不是实现细节：
 * 1. 已接受 / 正在拉的入站传输被对方取消时，必须像本地放弃一样收尾：
 *    拉取停掉（[TransferManager.activeIncomingIds] 里消失）并报 [TransferEvent.Declined]；
 * 2. 还没点过头的 offer 被取消，仍然要消失并报 Declined（原有行为，不许回归）；
 * 3. 我们发出去的报价被对方拒收/取消，同样要报 Declined（原有行为，不许回归）。
 *
 * 第 1 条今天靠 [TransferManager.pull] 的 catch 分支报事件 —— 它只认 `cancelRef.canceled`，
 * 所以 [onPeerKilled] 的修法就是**真的去按那个取消句柄**，而不是另发一个事件冒充。
 */
class TransferPeerCancelTest {

    // ---------------------------------------------------------------- 工具

    /** 起了就只接受连接、永不回应：让一次拉取停在"在途"，不需要真发文件。 */
    private class StallingServer(private val scope: CoroutineScope) {
        // `Dispatchers.IO` 在 Kotlin/Native 上是 internal（本仓 coroutines 版本如此），
        // commonTest 会被编到 iOS 目标 ⇒ 用 `Dispatchers.Default`（本目录其它测试一致）。
        private val selector = SelectorManager(Dispatchers.Default)
        private var server: ServerSocket? = null
        private var socket: Socket? = null

        /** @return 绑到的端口（绑 0 让系统分配，再读回来）。 */
        suspend fun start(): Int {
            val bound = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
            server = bound
            val port = (bound.localAddress as InetSocketAddress).port
            // 只接受连接，既不读也不回：拉取会挂在"等第一帧数据"上，正好停在在途。
            scope.launch { socket = bound.accept() }
            return port
        }

        fun stop() {
            runCatching { socket?.close() }
            runCatching { server?.close() }
            runCatching { selector.close() }
        }
    }

    private fun newManager(scope: CoroutineScope, dir: String, port: Int?): TransferManager =
        TransferManager(
            selfId = "self-node",
            scope = scope,
            tcpPort = 0,
            downloadDir = { dir },
            sendReliable = { _, _: JsonElement -> true },
            peerAddress = { peerId -> port?.let { PeerEndpoint("127.0.0.1", it) } },
            peerSupportsDirect = { false },
        )

    private fun offerEnvelope(transferId: String, size: Long): Envelope = makeEnvelope(
        type = MsgTypes.FILE_CTL,
        from = "peer-node",
        payload = Codec.wireJson.encodeToJsonElement(
            FileCtlOffer.serializer(),
            FileCtlOffer(
                op = FileCtlOps.OFFER,
                transferId = transferId,
                seq = 1,
                total = 1,
                files = listOf(FileMeta(fileId = "file-1", path = "a.bin", size = size)),
                totalSize = size,
                fileCount = 1,
                rootName = "a.bin",
            ),
        ),
    )

    private fun simpleEnvelope(transferId: String, op: String): Envelope = makeEnvelope(
        type = MsgTypes.FILE_CTL,
        from = "peer-node",
        payload = Codec.wireJson.encodeToJsonElement(
            FileCtlSimple.serializer(),
            FileCtlSimple(op = op, transferId = transferId),
        ),
    )

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        val ok = withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(10)
            true
        } ?: false
        assertTrue(ok, "等待超时：$what")
    }

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

    // ---------------------------------------------------------------- 契约 1

    @Test
    fun peerCancelStopsAnInFlightIncomingPull() = runBlocking {
        val dir = createTempDir("relay-peer-cancel-pull")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = StallingServer(scope)
        val port = server.start()
        val manager = newManager(scope, dir, port)
        val transferId = "t-inflight"

        val events = Channel<TransferEvent>(Channel.UNLIMITED)
        val pump = scope.launch { manager.events.collect { events.send(it) } }
        try {
            manager.onEnvelope(offerEnvelope(transferId, size = 4096))
            awaitUntil("offer 进入待接收") { manager.pendingOffers().any { it.transferId == transferId } }

            manager.accept(transferId)
            awaitUntil("拉取真的开始了") { manager.activeIncomingIds().contains(transferId) }

            // 对方在拉取中途取消。
            manager.onEnvelope(simpleEnvelope(transferId, FileCtlOps.CANCEL))

            // 事件里的 peerId 也要对：串成别人会让 UI 把这张卡记到另一台设备头上。
            val declined = awaitEvent(events) {
                it is TransferEvent.Declined && it.transferId == transferId && it.peerId == "peer-node"
            }
            assertNotNull(declined, "对方取消了在途的入站传输，必须报 Declined（否则卡片一直转圈）")
            awaitUntil("在途拉取必须停下来") { manager.activeIncomingIds().isEmpty() }
            assertTrue(
                manager.pendingOffers().none { it.transferId == transferId },
                "取消之后不该还挂在待接收清单里",
            )
        } finally {
            pump.cancel()
            runCatching { manager.stop() }
            server.stop()
            scope.cancel()
            removeRecursively(dir)
        }
    }

    // ---------------------------------------------------------------- 契约 2

    @Test
    fun peerCancelStillDropsAPendingOffer() = runBlocking {
        val dir = createTempDir("relay-peer-cancel-pending")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = newManager(scope, dir, port = null)
        val transferId = "t-pending"

        val events = Channel<TransferEvent>(Channel.UNLIMITED)
        val pump = scope.launch { manager.events.collect { events.send(it) } }
        try {
            manager.onEnvelope(offerEnvelope(transferId, size = 12))
            awaitUntil("offer 进入待接收") { manager.pendingOffers().any { it.transferId == transferId } }

            manager.onEnvelope(simpleEnvelope(transferId, FileCtlOps.DECLINE))

            val declined = awaitEvent(events) { it is TransferEvent.Declined && it.transferId == transferId }
            assertNotNull(declined, "还没点过头的 offer 被取消，必须报 Declined")
            assertTrue(
                manager.pendingOffers().none { it.transferId == transferId },
                "被取消的 offer 必须从待接收清单里消失",
            )
        } finally {
            pump.cancel()
            runCatching { manager.stop() }
            scope.cancel()
            removeRecursively(dir)
        }
    }

    // ---------------------------------------------------------------- 契约 3

    @Test
    fun peerDeclineStillDropsOurOutgoingOffer() = runBlocking {
        val dir = createTempDir("relay-peer-cancel-outgoing")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = newManager(scope, dir, port = null)
        val file = "/tmp/relay-peer-cancel.bin"

        val events = Channel<TransferEvent>(Channel.UNLIMITED)
        val pump = scope.launch { manager.events.collect { events.send(it) } }
        try {
            val transferId = assertNotNull(
                manager.offerFiles("peer-node", listOf(LocalFile(path = file, name = "a.bin", size = 3L))),
                "发出去的报价必须拿得到 transferId",
            )
            awaitUntil("报价登记在出站表里") { manager.pendingTransfers().outgoing.any { it.transferId == transferId } }

            manager.onEnvelope(simpleEnvelope(transferId, FileCtlOps.DECLINE))

            val declined = awaitEvent(events) { it is TransferEvent.Declined && it.transferId == transferId }
            assertNotNull(declined, "对方拒收我们的报价，必须报 Declined")
            assertTrue(
                manager.pendingTransfers().outgoing.none { it.transferId == transferId },
                "被拒收的报价必须从出站表里消失",
            )
        } finally {
            pump.cancel()
            runCatching { manager.stop() }
            scope.cancel()
            removeRecursively(dir)
        }
    }
}
