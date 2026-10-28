// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uniffi.luciola_uniffi.SecureSession
import uniffi.luciola_uniffi.SecureSessionListener
import uniffi.luciola_uniffi.identityFromSeed
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before

/**
 * 文件/投屏数据面迁移的**可行性量具**：直接压 luciola 的 SecureSession，
 * 用 64KB 帧测回环吞吐与 64 帧有界队列的行为。
 *
 * 这个用例**不改生产代码**，只为回答设计问题：
 *  - 8MB/64KB 帧在单条信道上要多久（决定分块大小与窗口）；
 *  - send 返回 false（队列满）时的退避节奏是否够用。
 */
class LuciolaChannelThroughputSpikeTest {

    /** 真引擎用例：本机没有 luciola 原生库时跳过（不是失败），配了却用不了则失败。 */
    @Before
    fun requireLuciolaNative() = LuciolaNativeGuard.assumeAvailable()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    @Test
    fun sixtyFourKbFramesOverSecureSession() = runBlocking {
        val seedClient = ByteArray(32) { 7 }
        val seedServer = ByteArray(32) { 9 }
        val clientIdentity = identityFromSeed(seedClient)
        val serverIdentity = identityFromSeed(seedServer)
        val port = freePort()

        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        val frames = 192 // 12 MB
        val totalBytes = (chunk.size.toLong() * frames)
        val received = AtomicLong(0)
        val done = CompletableDeferred<Unit>()

        val server = SecureSession(seedServer)
        server.listen(port.toUShort(), null, object : SecureSessionListener {
            override fun onState(state: String, detail: String?) {}
            override fun onMessage(message: ByteArray, peerFingerprint: String, remoteAddr: String?) {
                val now = received.addAndGet(message.size.toLong())
                if (now >= totalBytes && !done.isCompleted) done.complete(Unit)
            }
        })

        val client = SecureSession(seedClient)
        client.connect(
            listOf("127.0.0.1"), port.toUShort(), serverIdentity.publicKey, false,
            object : SecureSessionListener {
                override fun onState(state: String, detail: String?) {}
                override fun onMessage(message: ByteArray, peerFingerprint: String, remoteAddr: String?) {}
            },
        )
        withTimeout(15_000) {
            while (!client.isConnected()) delay(20)
        }

        var queueFullRetries = 0
        val started = System.nanoTime()
        for (i in 0 until frames) {
            while (!client.send(chunk)) {
                queueFullRetries++
                delay(1)
            }
        }
        withTimeout(60_000) { done.await() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val mbps = totalBytes.toDouble() / (1024.0 * 1024.0) / (elapsedMs / 1000.0)
        println(
            "THROUGHPUT frames=$frames chunk=64KB total=" + (totalBytes / 1024 / 1024) +
                "MB elapsed=" + elapsedMs + "ms mbps=" + String.format("%.2f", mbps) +
                " queueFullRetries=" + queueFullRetries,
        )
        assertEquals(totalBytes, received.get())
        assertTrue(elapsedMs > 0)
        client.disconnect()
        server.disconnect()
    }
}
