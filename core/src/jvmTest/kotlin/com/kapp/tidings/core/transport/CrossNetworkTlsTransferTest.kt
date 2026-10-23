// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * F1 跨网文件传输的**主机侧 + 客户端侧端到端**（task-39，真 TLS、真 pin、真文件）。
 *
 * 为什么必须在 jvmTest 里真跑一遍：跨网这一段的技术主张是「**TLS 之上的裸字节流**能把现有
 * pull 协议原样跑完」—— 这条主张的任何一环（TLS 服务端、双向 pin、裸流数据阶段、
 * `pull-ok` 之后的字节数）用桩替掉，测出来的都是桩的行为。
 *
 * 链路形状与真机一致：
 *  手机侧 `connectRaw`（TLS + pin）→ 主机侧 `startRawServer` → `TransferServer.servePullFrame`
 *  中间**没有**任何协议改动：pull / pull-ok / 裸流 / done / finish 逐字照旧。
 */
class CrossNetworkTlsTransferTest {

    private fun patternBytes(size: Int, seed: Int): ByteArray {
        val out = ByteArray(size)
        var x = seed
        for (i in out.indices) {
            x = x * 1103515245 + 12345
            out[i] = (x ushr 16).toByte()
        }
        return out
    }

    private fun writeAll(path: String, bytes: ByteArray) {
        val handle = openRandomAccess(path, forWrite = true)
        try {
            handle.append(bytes, bytes.size)
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

    private class StubLookup(private val files: Map<String, OutgoingFile>, private val transferId: String) :
        OutgoingLookup {
        override fun resolve(transferId: String, fileId: String): OutgoingFile? =
            if (transferId == this.transferId) files[fileId] else null

        override fun supportsWait(peerId: String): Boolean = true
    }

    @Test
    fun aFileCrossesARealTlsRawConnection() = runBlocking {
        val srcDir = createTempDir("relay-xsrc")
        val dstDir = createTempDir("relay-xdst")
        val serverDir = createTempDir("relay-xtls-server")
        val clientDir = createTempDir("relay-xtls-client")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val serverTransport = TlsLinkTransport({ serverDir }, { listOf("127.0.0.1") })
        val clientTransport = TlsLinkTransport({ clientDir }, { listOf("127.0.0.1") })
        try {
            val payload = patternBytes(300_000, 91)
            val srcPath = pathJoin(srcDir, "cross-tls.bin")
            writeAll(srcPath, payload)
            val lookup = StubLookup(
                files = mapOf("f1" to OutgoingFile("f1", srcPath, payload.size.toLong())),
                transferId = "t-xnet",
            )
            // 主机侧：不绑明文监听，只在原始 TLS 通道上服务 pull —— 这正是跨网那条路。
            val transferServer = TransferServer(scope, lookup, bindAddress = "127.0.0.1")
            val serverOutcome = java.util.concurrent.atomic.AtomicReference("none")
            val port = serverTransport.startRawServer(0) { connection, _, _ ->
                val r = runCatching { transferServer.servePullFrame(connection.read, connection.write) }
                serverOutcome.set(
                    r.fold({ "handled=" + it }, { "threw=" + (it::class.simpleName ?: "?") + ": " + it.message }),
                )
                connection.close()
            }
            assertNotNull(port, "原始 TLS 监听必须起得来")

            runCatching {
            pullTransfer(
                PullOptions(
                    host = "127.0.0.1",
                    port = port,
                    selfId = "self-x",
                    transferId = "t-xnet",
                    files = listOf(IncomingFilePlan("f1", "cross-tls.bin", payload.size.toLong())),
                    saveDir = dstDir,
                    supportsWait = true,
                    attempts = listOf(PullAttempt("127.0.0.1", port, PullAttemptTransport.Tls)),
                    connectRaw = { host, p ->
                        // 与真机同形：pin 就是配对时存下的主机 pin。
                        clientTransport.connectRaw(host, p, serverTransport.localPin, 5_000)
                    },
                ),
            )
            }.onFailure { t ->
                // 失败时把服务端的结局一起打出来：`handled=false` = 首帧没被认出来（连接层问题），
                // `handled=true` 却客户端报 closed = **尾部字节没排空就关了 socket**（数据面问题）。
                println("cross-network pull failed: server=" + serverOutcome.get() + ", client=" + (t::class.simpleName) + ": " + t.message)
                throw t
            }

            assertContentEquals(payload, readAll(pathJoin(dstDir, "cross-tls.bin")), "跨网文件必须逐字节一致")
            assertFalse(fileExists(pathJoin(dstDir, "cross-tls.bin") + ".part"), ".part 转正后必须消失")
        } finally {
            runCatching { serverTransport.stopRawServer() }
            runCatching { serverTransport.stopServer() }
            runCatching { clientTransport.stopRawServer() }
            scope.cancel()
            removeRecursively(srcDir)
            removeRecursively(dstDir)
            removeRecursively(serverDir)
            removeRecursively(clientDir)
        }
    }

    @Test
    fun aWrongPinFailsClosedInsteadOfFallingBackToPlaintext() = runBlocking {
        val srcDir = createTempDir("relay-xsrc-bad")
        val dstDir = createTempDir("relay-xdst-bad")
        val serverDir = createTempDir("relay-xtls-server-bad")
        val clientDir = createTempDir("relay-xtls-client-bad")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val serverTransport = TlsLinkTransport({ serverDir }, { listOf("127.0.0.1") })
        val clientTransport = TlsLinkTransport({ clientDir }, { listOf("127.0.0.1") })
        try {
            val payload = patternBytes(32_000, 7)
            val srcPath = pathJoin(srcDir, "nope.bin")
            writeAll(srcPath, payload)
            val lookup = StubLookup(
                files = mapOf("f1" to OutgoingFile("f1", srcPath, payload.size.toLong())),
                transferId = "t-badpin",
            )
            val transferServer = TransferServer(scope, lookup, bindAddress = "127.0.0.1")
            val port = serverTransport.startRawServer(0) { connection, _, _ ->
                runCatching { transferServer.servePullFrame(connection.read, connection.write) }
                connection.close()
            }
            assertNotNull(port)
            val wrongPin = TlsPin.ofSpki("not-the-server-spki".encodeToByteArray())

            assertFailsWith<PullException> {
                pullTransfer(
                    PullOptions(
                        host = "127.0.0.1",
                        port = port,
                        selfId = "self-x",
                        transferId = "t-badpin",
                        files = listOf(IncomingFilePlan("f1", "nope.bin", payload.size.toLong())),
                        saveDir = dstDir,
                        supportsWait = true,
                        attempts = listOf(PullAttempt("127.0.0.1", port, PullAttemptTransport.Tls)),
                        connectRaw = { host, p -> clientTransport.connectRaw(host, p, wrongPin, 5_000) },
                    ),
                )
            }
            assertFalse(fileExists(pathJoin(dstDir, "nope.bin")), "pin 不匹配时一个字节都不该落盘")
        } finally {
            runCatching { serverTransport.stopRawServer() }
            runCatching { clientTransport.stopRawServer() }
            scope.cancel()
            removeRecursively(srcDir)
            removeRecursively(dstDir)
            removeRecursively(serverDir)
            removeRecursively(clientDir)
        }
    }
}
