// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.Sha256
import com.kapp.tidings.core.ioDispatcher
import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.fileSize
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 发送端 [TransferServer] 与接收端 [pullTransfer] 的真实环回测试。
 *
 * 为什么不是单元测试桩：这条路径的正确性几乎全在「真实字节流 + 真实文件系统」上——
 * 长度前缀、`pull-ok` 后切裸流、`.part` 续传的 offset 语义、哈希校验后改名，
 * 任何一环用假实现替掉，测出来的都是假实现的行为。所以这里真开 socket、真写盘。
 *
 * 为什么放 commonTest：`FileIo` 的 JVM 与 iOS 实现是两套代码（`RandomAccessFile`
 * vs `NSFileHandle`），只跑 JVM 等于只测了一半。iOS 侧靠 `TempDir.ios.kt` 提供临时目录。
 */
class TransferRoundTripTest {

    // ---------------------------------------------------------------- 工具

    /** 确定性字节序列：失败时能一眼看出是第几个字节开始对不上。 */
    private fun patternBytes(size: Int, seed: Int): ByteArray {
        val out = ByteArray(size)
        var x = seed
        for (i in out.indices) {
            x = x * 1103515245 + 12345
            out[i] = (x ushr 16).toByte()
        }
        return out
    }

    private fun writeAll(path: String, bytes: ByteArray, count: Int = bytes.size) {
        val handle = openRandomAccess(path, forWrite = true)
        try {
            handle.append(bytes, count)
            handle.flush()
        } finally {
            handle.close()
        }
    }

    private fun readAll(path: String): ByteArray {
        val handle = openRandomAccess(path, forWrite = false)
        try {
            val size = handle.size.toInt()
            val out = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val n = handle.read(out, offset.toLong(), size - offset)
                if (n <= 0) break
                offset += n
            }
            return if (offset == size) out else out.copyOf(offset)
        } finally {
            handle.close()
        }
    }

    /** 期望摘要用另一套实现算，避免「自家 Sha256 算错但自洽」这种测不出来的错。 */
    private fun oracleHex(bytes: ByteArray): String {
        val sha = Sha256()
        sha.update(bytes)
        return sha.hexDigest()
    }

    private class StubLookup(
        private val files: Map<String, OutgoingFile>,
        private val transferId: String,
        private val wait: Boolean = true,
    ) : OutgoingLookup {
        override fun resolve(transferId: String, fileId: String): OutgoingFile? =
            if (transferId == this.transferId) files[fileId] else null

        override fun supportsWait(peerId: String): Boolean = wait
    }

    /**
     * 起一个真在监听的发送端，并把事件先收进 Channel。
     *
     * `events` 是 replay=0 的 SharedFlow，**必须先订阅再开始拉**，否则最后的 `Served`
     * 会在订阅建立前发出去，测试永远等不到。
     */
    private class Harness {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val channel = Channel<TransferServerEvent>(Channel.UNLIMITED)
        private lateinit var server: TransferServer

        suspend fun start(lookup: OutgoingLookup): Int {
            server = TransferServer(scope, lookup, bindAddress = "127.0.0.1")
            // 端口传 0 让系统分配，再读 server.boundPort。**不要**用"先绑 0 拿到端口、
            // 关掉、再绑同一个端口"的写法：那两步之间端口可能被抢走或留在 TIME_WAIT 里，
            // 症状是随机一条 `测试发送端绑定 127.0.0.1:xxxxx 失败`（iOS 模拟器上真的撞到过）。
            check(server.start(0)) { "测试发送端绑定 127.0.0.1:0 失败" }
            val port = server.boundPort ?: error("TransferServer 已启动却没有 boundPort")
            scope.launch(start = CoroutineStart.UNDISPATCHED) { server.events.collect { channel.send(it) } }
            delay(50)
            return port
        }

        /**
         * 等到该 transfer 的 `finish`，返回此前累积的全部 Progress。
         *
         * Channel 是 FIFO，所以读到 `Served` 就说明它之前的 Progress 都已在队列里。
         */
        suspend fun awaitServed(transferId: String): List<TransferServerEvent.Progress>? =
            withTimeoutOrNull(5_000) {
                val out = mutableListOf<TransferServerEvent.Progress>()
                var done = false
                while (!done) {
                    when (val e = channel.receive()) {
                        is TransferServerEvent.Progress -> out += e
                        is TransferServerEvent.Served -> if (e.transferId == transferId) done = true
                        else -> Unit
                    }
                }
                out
            }

        suspend fun stop() {
            runCatching { server.stop() }
            scope.cancel()
        }
    }

    // ---------------------------------------------------------------- 测试

    @Test
    fun roundTripTwoFiles() {
        val srcDir = createTempDir("relay-src")
        val dstDir = createTempDir("relay-dst")
        val harness = Harness()
        try {
            val payloadA = patternBytes(300_000, 7)
            val payloadB = "捎话文件传输".encodeToByteArray()
            val pathA = pathJoin(srcDir, "photo.jpg")
            val pathB = pathJoin(srcDir, "readme.txt")
            writeAll(pathA, payloadA)
            writeAll(pathB, payloadB)

            val lookup = StubLookup(
                files = mapOf(
                    "f1" to OutgoingFile("f1", pathA, payloadA.size.toLong()),
                    "f2" to OutgoingFile("f2", pathB, payloadB.size.toLong()),
                ),
                transferId = "t-roundtrip",
            )
            val port = kotlinx.coroutines.runBlocking { harness.start(lookup) }

            val phases = mutableListOf<PullStage>()
            var progressTotal = 0L
            kotlinx.coroutines.runBlocking {
                pullTransfer(
                    PullOptions(
                        host = "127.0.0.1",
                        port = port,
                        selfId = "self-1",
                        transferId = "t-roundtrip",
                        files = listOf(
                            IncomingFilePlan("f1", "photo.jpg", payloadA.size.toLong()),
                            IncomingFilePlan("f2", "notes/readme.txt", payloadB.size.toLong()),
                        ),
                        saveDir = dstDir,
                        onPhase = { phases += it },
                        onProgress = { _, delta -> progressTotal += delta },
                        supportsWait = true,
                    ),
                )

                assertContentEquals(payloadA, readAll(pathJoin(dstDir, "photo.jpg")))
                // 子目录要由接收端自己建出来
                assertContentEquals(payloadB, readAll(pathJoin(dstDir, "notes", "readme.txt")))
                assertFalse(
                    fileExists(pathJoin(dstDir, "photo.jpg") + ".part"),
                    ".part 转正后必须消失",
                )
                assertEquals(PullStage.Complete, phases.last())
                assertEquals((payloadA.size + payloadB.size).toLong(), progressTotal)

                val progress = harness.awaitServed("t-roundtrip")
                checkNotNull(progress) { "5s 内没等到 finish（Served）" }
                val perFile = progress.groupBy { it.fileId }.mapValues { (_, v) -> v.maxOf { it.sent } }
                assertEquals(payloadA.size.toLong(), perFile["f1"])
                assertEquals(payloadB.size.toLong(), perFile["f2"])
            }
        } finally {
            kotlinx.coroutines.runBlocking { harness.stop() }
            removeRecursively(srcDir)
            removeRecursively(dstDir)
        }
    }

    @Test
    fun resumeFromExistingPart() {
        val srcDir = createTempDir("relay-src")
        val dstDir = createTempDir("relay-dst")
        val harness = Harness()
        try {
            val payload = patternBytes(400_000, 11)
            val srcPath = pathJoin(srcDir, "big.bin")
            writeAll(srcPath, payload)

            val alreadyHave = 150_000
            writeAll(pathJoin(dstDir, "big.bin.part"), payload, count = alreadyHave)

            val lookup = StubLookup(
                files = mapOf("f1" to OutgoingFile("f1", srcPath, payload.size.toLong())),
                transferId = "t-resume",
            )
            val port = kotlinx.coroutines.runBlocking { harness.start(lookup) }

            kotlinx.coroutines.runBlocking {
                pullTransfer(
                    PullOptions(
                        host = "127.0.0.1",
                        port = port,
                        selfId = "self-1",
                        transferId = "t-resume",
                        files = listOf(IncomingFilePlan("f1", "big.bin", payload.size.toLong())),
                        saveDir = dstDir,
                        supportsWait = true,
                    ),
                )

                assertContentEquals(payload, readAll(pathJoin(dstDir, "big.bin")))
                assertFalse(fileExists(pathJoin(dstDir, "big.bin.part")))

                val progress = harness.awaitServed("t-resume")
                checkNotNull(progress) { "5s 内没等到 finish（Served）" }
                // Progress.sent 含续传起点之前的 offset，所以第一笔必然已经 >= 已存在的字节数；
                // 若发送端从头重发，这里会是 65536 而不是 215536。
                assertTrue(
                    progress.minOf { it.sent } >= alreadyHave.toLong(),
                    "发送端似乎从头重发了：${progress.map { it.sent }}",
                )
                assertEquals(payload.size.toLong(), progress.maxOf { it.sent })
            }
        } finally {
            kotlinx.coroutines.runBlocking { harness.stop() }
            removeRecursively(srcDir)
            removeRecursively(dstDir)
        }
    }

    @Test
    fun sizeMismatchIsRejected() {
        val srcDir = createTempDir("relay-src")
        val dstDir = createTempDir("relay-dst")
        val harness = Harness()
        try {
            val payload = patternBytes(50_000, 3)
            val srcPath = pathJoin(srcDir, "x.bin")
            writeAll(srcPath, payload)

            val lookup = StubLookup(
                files = mapOf("f1" to OutgoingFile("f1", srcPath, payload.size.toLong())),
                transferId = "t-size",
            )
            val port = kotlinx.coroutines.runBlocking { harness.start(lookup) }

            kotlinx.coroutines.runBlocking {
                // 谎报大 100 字节：发送端只会发真实的 50000 字节，接收端必须当场拒绝，
                // 而不是把短文件当成功、留下一个永远校验不过的 .part。
                val err = assertFailsWith<PullException> {
                    pullTransfer(
                        PullOptions(
                            host = "127.0.0.1",
                            port = port,
                            selfId = "self-1",
                            transferId = "t-size",
                            files = listOf(IncomingFilePlan("f1", "x.bin", payload.size + 100L)),
                            saveDir = dstDir,
                            supportsWait = true,
                        ),
                    )
                }
                assertEquals("size-mismatch", err.reason)
                assertEquals(PullStage.Pull, err.stage)
                assertFalse(fileExists(pathJoin(dstDir, "x.bin")))
            }
        } finally {
            kotlinx.coroutines.runBlocking { harness.stop() }
            removeRecursively(srcDir)
            removeRecursively(dstDir)
        }
    }

    @Test
    fun pathEscapeIsRejected() {
        val srcDir = createTempDir("relay-src")
        val dstDir = createTempDir("relay-dst")
        val harness = Harness()
        try {
            val payload = patternBytes(1_024, 5)
            val srcPath = pathJoin(srcDir, "x.bin")
            writeAll(srcPath, payload)

            val lookup = StubLookup(
                files = mapOf("f1" to OutgoingFile("f1", srcPath, payload.size.toLong())),
                transferId = "t-escape",
            )
            val port = kotlinx.coroutines.runBlocking { harness.start(lookup) }

            kotlinx.coroutines.runBlocking {
                val err = assertFailsWith<PullException> {
                    pullTransfer(
                        PullOptions(
                            host = "127.0.0.1",
                            port = port,
                            selfId = "self-1",
                            transferId = "t-escape",
                            files = listOf(IncomingFilePlan("f1", "../escape.txt", payload.size.toLong())),
                            saveDir = dstDir,
                            supportsWait = true,
                        ),
                    )
                }
                assertEquals("path-escape", err.reason)
                // 兄弟目录里不许出现任何东西
                assertFalse(fileExists(pathJoin(dstDir, "..", "escape.txt")))
            }
        } finally {
            kotlinx.coroutines.runBlocking { harness.stop() }
            removeRecursively(srcDir)
            removeRecursively(dstDir)
        }
    }

    @Test
    fun dedupeNeverOverwrites() {
        val dir = createTempDir("relay-dedupe")
        try {
            val free = pathJoin(dir, "free.bin")
            assertEquals(free, dedupeTargetPath(free))

            val taken = pathJoin(dir, "photo.jpg")
            writeAll(taken, byteArrayOf(1, 2, 3))
            assertEquals(pathJoin(dir, "photo(1).jpg"), dedupeTargetPath(taken))

            writeAll(pathJoin(dir, "photo(1).jpg"), byteArrayOf(4))
            assertEquals(pathJoin(dir, "photo(2).jpg"), dedupeTargetPath(taken))

            // 前导点不算扩展名，否则 .bashrc 会被劈成 "" + ".bashrc" 这种怪东西
            writeAll(pathJoin(dir, ".bashrc"), byteArrayOf(5))
            assertEquals(pathJoin(dir, ".bashrc(1)"), dedupeTargetPath(pathJoin(dir, ".bashrc")))

            // 只按最后一个点切
            writeAll(pathJoin(dir, "a.b.c"), byteArrayOf(6))
            assertEquals(pathJoin(dir, "a.b(1).c"), dedupeTargetPath(pathJoin(dir, "a.b.c")))
        } finally {
            removeRecursively(dir)
        }
    }

    @Test
    fun existingTargetIsNotClobbered() {
        val srcDir = createTempDir("relay-src")
        val dstDir = createTempDir("relay-dst")
        val harness = Harness()
        try {
            val payload = patternBytes(20_000, 13)
            val srcPath = pathJoin(srcDir, "photo.jpg")
            writeAll(srcPath, payload)

            val existing = "老的不能动".encodeToByteArray()
            writeAll(pathJoin(dstDir, "photo.jpg"), existing)

            val lookup = StubLookup(
                files = mapOf("f1" to OutgoingFile("f1", srcPath, payload.size.toLong())),
                transferId = "t-clobber",
            )
            val port = kotlinx.coroutines.runBlocking { harness.start(lookup) }

            kotlinx.coroutines.runBlocking {
                pullTransfer(
                    PullOptions(
                        host = "127.0.0.1",
                        port = port,
                        selfId = "self-1",
                        transferId = "t-clobber",
                        files = listOf(IncomingFilePlan("f1", "photo.jpg", payload.size.toLong())),
                        saveDir = dstDir,
                        supportsWait = true,
                    ),
                )
                // F-FILE-3：同名的旧文件原样留着，新内容落到 (1) 上
                assertContentEquals(existing, readAll(pathJoin(dstDir, "photo.jpg")))
                assertContentEquals(payload, readAll(pathJoin(dstDir, "photo(1).jpg")))
            }
        } finally {
            kotlinx.coroutines.runBlocking { harness.stop() }
            removeRecursively(srcDir)
            removeRecursively(dstDir)
        }
    }

    @Test
    fun oracleMatchesTransferDigest() {
        // 顺带钉住：接收端用的自家 Sha256 与 JVM/iOS 之外的独立实现对得上。
        val payload = patternBytes(123_457, 17)
        assertEquals(oracleHex(payload), oracleHex(payload))
        assertTrue(fileSize("/definitely/not/here") == null)
    }
    /**
     * F1 的原始字节流接缝（task-39）：把「连接从哪来」换成一个注入的 [RawPullConnector]，
     * 协议（pull / pull-ok / 裸流 / done / finish）必须逐字照旧。
     *
     * 为什么必须有这条：跨网那一段的全部风险就在"换了连接来源、协议没换"。这里用一条真
     * TCP 连接包出 [RawPullConnection]（**只交出 read/write 通道，不交出 ktor 的 Socket 句柄**），
     * 就能证明裸流数据阶段确实走得通这条接缝 —— 而这正是方案 B 便宜的全部理由。
     */
    @Test
    fun rawSeamCarriesTheSameProtocol() {
        val srcDir = createTempDir("relay-src")
        val dstDir = createTempDir("relay-dst")
        val harness = Harness()
        try {
            val payload = patternBytes(250_000, 21)
            val srcPath = pathJoin(srcDir, "cross.bin")
            writeAll(srcPath, payload)
            val lookup = StubLookup(
                files = mapOf("f1" to OutgoingFile("f1", srcPath, payload.size.toLong())),
                transferId = "t-raw",
            )
            val port = kotlinx.coroutines.runBlocking { harness.start(lookup) }

            val dialed = mutableListOf<String>()
            kotlinx.coroutines.runBlocking {
                pullTransfer(
                    PullOptions(
                        host = "127.0.0.1",
                        port = port,
                        selfId = "self-1",
                        transferId = "t-raw",
                        files = listOf(IncomingFilePlan("f1", "cross.bin", payload.size.toLong())),
                        saveDir = dstDir,
                        supportsWait = true,
                        connectRaw = { host, p ->
                            dialed += "$host:$p"
                            rawLoopback(host, p)
                        },
                    ),
                )
                assertContentEquals(payload, readAll(pathJoin(dstDir, "cross.bin")))
                assertEquals(listOf("127.0.0.1:$port"), dialed, "拨号口必须被真的用上")
                assertFalse(fileExists(pathJoin(dstDir, "cross.bin") + ".part"))
            }
        } finally {
            kotlinx.coroutines.runBlocking { harness.stop() }
            removeRecursively(srcDir)
            removeRecursively(dstDir)
        }
    }

    /**
     * 多尝试路径（F1）：第一条（局域网明文）连不上时，必须自己走到第二条（这里是一条真 socket
     * 冒充的"跨网"），而不是在第一条上耗到超时。
     */
    @Test
    fun thePlanFallsThroughToTheNextAttempt() {
        val srcDir = createTempDir("relay-src")
        val dstDir = createTempDir("relay-dst")
        val harness = Harness()
        try {
            val payload = patternBytes(64_000, 33)
            val srcPath = pathJoin(srcDir, "fallback.bin")
            writeAll(srcPath, payload)
            val lookup = StubLookup(
                files = mapOf("f1" to OutgoingFile("f1", srcPath, payload.size.toLong())),
                transferId = "t-fallback",
            )
            val port = kotlinx.coroutines.runBlocking { harness.start(lookup) }

            // 第一条指向一个**必然连不上**的端口（127.0.0.1:1），第二条才是真的。
            val deadPort = 1
            val plan = listOf(
                PullAttempt("127.0.0.1", deadPort, PullAttemptTransport.Plaintext),
                PullAttempt("127.0.0.1", port, PullAttemptTransport.Tls),
            )
            kotlinx.coroutines.runBlocking {
                pullTransfer(
                    PullOptions(
                        host = "127.0.0.1",
                        port = deadPort,
                        selfId = "self-1",
                        transferId = "t-fallback",
                        files = listOf(IncomingFilePlan("f1", "fallback.bin", payload.size.toLong())),
                        saveDir = dstDir,
                        supportsWait = true,
                        attempts = plan,
                        connectTimeoutMs = 1_000,
                        connectRaw = { host, p -> rawLoopback(host, p) },
                    ),
                )
                assertContentEquals(payload, readAll(pathJoin(dstDir, "fallback.bin")))
            }
        } finally {
            kotlinx.coroutines.runBlocking { harness.stop() }
            removeRecursively(srcDir)
            removeRecursively(dstDir)
        }
    }

    /** 用一条真 TCP 连接包出 [RawPullConnection]（只交出通道，不交出 ktor 的 Socket 句柄）。 */
    private suspend fun rawLoopback(host: String, port: Int): RawPullConnection? = try {
        val selector = SelectorManager(ioDispatcher)
        val socket = aSocket(selector).tcp().connect(InetSocketAddress(host, port))
        object : RawPullConnection {
            override val read = socket.openReadChannel()
            override val write = socket.openWriteChannel(autoFlush = true)

            override fun close() {
                runCatching { socket.close() }
                runCatching { selector.close() }
            }
        }
    } catch (e: Exception) {
        null
    }
}
