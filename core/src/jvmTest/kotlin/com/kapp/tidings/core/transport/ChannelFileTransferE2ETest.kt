// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.Sha256
import com.kapp.tidings.core.fs.fileSize
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.testing.createTempDir
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * goal 第 (3) 步的**替代验收**：真机走不通（宿主控制面有个独立 bug 拖死了 16 个 handler
 * 线程，见下），这里用两条**真实 luciola 加密信道**（TCP 回环、真实配对、真实 Ed25519）
 * 把"≥50MB 文件经信道传完且 sha256 一致"跑出来。
 *
 * 它与真机的差别只有"跨机器/真实网卡"，不含任何 mock：信道是真的、分块窗口是真的、
 * 落盘与 sha 校验是真的。
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class ChannelFileTransferE2ETest {

    private fun profile(id: String) = Profile(
        nodeId = id, nick = id, company = "", dept = "", team = "", avatar = 1,
        profileRev = 1, host = "h", platform = "jvm", tcpPort = 0, ver = "1", caps = emptyList(),
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun shaOf(path: String): String {
        val sha = Sha256()
        val f = openRandomAccess(path, forWrite = false)
        try {
            val buf = ByteArray(256 * 1024)
            var off = 0L
            while (true) {
                val n = f.read(buf, off, buf.size)
                if (n <= 0) break
                sha.update(buf, 0, n)
                off += n
            }
        } finally {
            f.close()
        }
        return sha.hexDigest()
    }

    @Test
    fun fiftyMegabyteFileCrossesEncryptedChannelWithMatchingSha() = runBlocking {
        val hostPort = freePort()
        val phonePort = freePort()
        val host = LuciolaTransportFactory(profile("host"), hostPort, seedPersist = { "3a".repeat(32) })
        val phone = LuciolaTransportFactory(profile("phone"), phonePort, seedPersist = { "7c".repeat(32) })
        phone.pairPeer(
            "127.0.0.1", hostPort, host.publicKey,
            secret = kotlin.io.encoding.Base64.UrlSafe.decode(host.pairSecretBase64()),
        )

        val hostRegistry = PeerRegistry("host").apply { touch("phone", "127.0.0.1", phonePort, profile("phone")) }
        val phoneRegistry = PeerRegistry("phone").apply { touch("host", "127.0.0.1", hostPort, profile("host")) }
        val hostTransport = host.createTransport(hostRegistry)
        val phoneTransport = phone.createTransport(phoneRegistry)
        hostTransport.start()
        phoneTransport.start()

        // 50 MiB 源文件 + 一条真实信道出口
        val srcDir = createTempDir("relay-e2e-src")
        val dstDir = createTempDir("relay-e2e-dst")
        val srcPath = pathJoin(srcDir, "big.bin")
        val total = 50L * 1024 * 1024
        run {
            val f = openRandomAccess(srcPath, forWrite = true)
            try {
                val chunk = ByteArray(1024 * 1024)
                var written = 0L
                var seed = 0
                while (written < total) {
                    for (i in chunk.indices) chunk[i] = ((written + i + seed) % 251).toByte()
                    val n = minOf(chunk.size.toLong(), total - written).toInt()
                    f.append(chunk, n)
                    written += n
                    seed += 7
                }
                f.flush()
            } finally {
                f.close()
            }
        }
        val srcSha = shaOf(srcPath)

        // 计数器：把"卡在哪一侧"从猜测变成读数（测试侧包装，生产代码零改动）。
        val hostOut = java.util.concurrent.atomic.AtomicLong()
        val hostRejected = java.util.concurrent.atomic.AtomicLong()
        val hostIn = java.util.concurrent.atomic.AtomicLong()
        val phoneOut = java.util.concurrent.atomic.AtomicLong()
        val phoneRejected = java.util.concurrent.atomic.AtomicLong()
        val phoneIn = java.util.concurrent.atomic.AtomicLong()
        val hostRouter = FileChannelRouter(sendToPeer = { _, bytes ->
            val ok = hostTransport.send(bytes, "127.0.0.1", phonePort)
            if (ok) hostOut.incrementAndGet() else hostRejected.incrementAndGet()
            ok
        })
        val phoneRouter = FileChannelRouter(sendToPeer = { _, bytes ->
            val ok = phoneTransport.send(bytes, "127.0.0.1", hostPort)
            if (ok) phoneOut.incrementAndGet() else phoneRejected.incrementAndGet()
            ok
        })
        // 两台互相对接:把对方的入站帧喂进自己的路由器
        val hostPump = async {
            hostTransport.incoming.collect {
                hostIn.incrementAndGet()
                // 收帧协程**绝不允许因为一次 IO 异常死掉**：它一死，入站缓冲填满、
                // Rust 回调线程被永久阻塞，整条信道静默 —— 现象是"ACK 停住、谁也没报错"。
                runCatching { hostRouter.onBinaryFrame(it.bytes) }
                    .onFailure { t -> println("HOST-PUMP-FRAME-ERROR " + t) }
            }
        }
        val phonePump = async {
            phoneTransport.incoming.collect {
                phoneIn.incrementAndGet()
                runCatching { phoneRouter.onBinaryFrame(it.bytes) }
                    .onFailure { t -> println("PHONE-PUMP-FRAME-ERROR " + t) }
            }
        }

        try {
            withTimeout(20_000) {
                while (!phoneTransport.requestPairing("127.0.0.1", hostPort, "phone")) delay(50)
            }
            withTimeout(20_000) { phoneTransport.pairingResults.first() }

            // 收端登记(phone 收 host 推)
            assertTrue(
                phoneRouter.registerInbound(
                    peerId = "host", transferId = "t-50mb", fileId = "f1",
                    target = ChunkFileTarget("big.bin", total, srcSha), root = dstDir,
                ),
                "登记必须成功(路径不越界)",
            )

            val started = System.nanoTime()
            // 进度探针：每 500ms 打一次已确认字节数 —— 卡在哪一侧一眼就能看出来
            // （确认不动 = 收端没落盘/没回 ACK；确认在涨而文件没出现 = 转正失败）。
            val probe = async {
                var last = -1L
                var lastChangeAt = System.currentTimeMillis()
                repeat(600) {
                    val cur = hostRouter.ackedUpTo("t-50mb", "f1")
                    if (cur != last) {
                        println("PROGRESS acked=" + cur + " / " + total)
                        last = cur
                        lastChangeAt = System.currentTimeMillis()
                    }
                    if (cur >= total) return@async
                    // 停滞 15s 立刻把全部线程栈打出来并判失败：这里要的是**证据**,
                    // 不是再等 180s 后一句 "Timed out"。
                    if (System.currentTimeMillis() - lastChangeAt > 15_000) {
                        println("=== STALL acked=" + cur + " ===")
                        println(
                            "host out=" + hostOut.get() + " rejected=" + hostRejected.get() +
                                " in=" + hostIn.get() +
                                " | phone out=" + phoneOut.get() + " rejected=" + phoneRejected.get() +
                                " in=" + phoneIn.get(),
                        )
                        val partPath = pathJoin(dstDir, "big.bin.part")
                        println("part bytes on disk=" + (com.kapp.tidings.core.fs.fileSize(partPath) ?: -1L))
                        println(
                            "transport host: blockedEmits=" + hostTransport.blockedEmits +
                                " emitFailures=" + hostTransport.emitFailures +
                                " | phone: blockedEmits=" + phoneTransport.blockedEmits +
                                " emitFailures=" + phoneTransport.emitFailures,
                        )
                        println("session host: " + hostTransport.diagnostics())
                        println("session phone: " + phoneTransport.diagnostics())
                        println("state host: " + hostTransport.stateHistory())
                        println("state phone: " + phoneTransport.stateHistory())
                        println(
                            "rejected host=" + hostTransport.rejectedInbound + " listenErr=" + hostTransport.listenError +
                                " | phone rejected=" + phoneTransport.rejectedInbound + " listenErr=" + phoneTransport.listenError,
                        )
                        throw AssertionError("传输停滞:acked=" + cur + "/" + total)
                    }
                    delay(500)
                }
            }
            val sent = withTimeout(180_000) {
                hostRouter.sendFile(
                    peerId = "phone", transferId = "t-50mb", fileId = "f1",
                    source = DiskFileChunkSource(srcPath),
                )
            }
            probe.cancel()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val dstPath = pathJoin(dstDir, "big.bin")

            // 等落盘转正(发送端报满量后,收端可能还在写最后一块)
            withTimeout(60_000) {
                while (!com.kapp.tidings.core.fs.fileExists(dstPath)) delay(100)
            }
            assertEquals(total, sent, "发送端必须确认全量")
            assertEquals(total, fileSize(dstPath), "落盘大小必须一致")
            assertEquals(srcSha, shaOf(dstPath), "sha256 必须一致")

            val mbps = total.toDouble() / (1024.0 * 1024.0) / (elapsedMs / 1000.0)
            println(
                "CHANNEL_FILE_E2E size=" + (total / 1024 / 1024) + "MB elapsed=" + elapsedMs +
                    "ms mbps=" + String.format("%.2f", mbps) + " sha=" + srcSha.take(16),
            )
        } finally {
            hostPump.cancel()
            phonePump.cancel()
            hostTransport.stop()
            phoneTransport.stop()
            // **停干净**是可复核的：两个端口都必须能被重新绑上。绑不上就说明
            // stop() 之后还有 Rust 链路线程攥着监听（那会污染同一 JVM 里的后续用例）。
            for (port in listOf(hostPort, phonePort)) {
                val deadline = System.currentTimeMillis() + 5_000
                var bound = false
                while (System.currentTimeMillis() < deadline) {
                    bound = runCatching { ServerSocket(port).use { } }.isSuccess
                    if (bound) break
                    delay(100)
                }
                assertTrue(bound, "stop() 之后 " + port + " 必须释放")
            }
        }
    }
}
