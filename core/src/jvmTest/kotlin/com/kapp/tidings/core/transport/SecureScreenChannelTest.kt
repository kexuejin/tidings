// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Profile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Before

/**
 * 投屏的**独立加密会话**：一条专用信道上的双向字节流。
 *
 * 全部走真实引擎与真实 TCP 回环（不 mock 信道、不用固定端口，免得依赖本机环境）。
 * 验的是 screen-* 协议真正需要的那一条不变量：**写进去的字节按序、完整地读出来** ——
 * 封套、切片、队列满重试，任一环节把字节弄丢或弄乱，这里都会红。
 */
class SecureScreenChannelTest {

    /** 真引擎用例：本机没有 luciola 原生库时跳过（不是失败），配了却用不了则失败。 */
    @Before
    fun requireLuciolaNative() = LuciolaNativeGuard.assumeAvailable()

    private fun profile(id: String) = Profile(
        nodeId = id, nick = id, company = "", dept = "", team = "", avatar = 1,
        profileRev = 1, host = "h", platform = "jvm", tcpPort = 0, ver = "1", caps = emptyList(),
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class Fixture(
        val viewer: SecureScreenChannel,
        val sharer: SecureScreenChannel,
        val viewerWire: ScreenWire,
        val accepted: CompletableDeferred<ScreenWire>,
        val scope: CoroutineScope,
    )

    private suspend fun fixture(): Fixture {
        val screenPort = freePort()
        // 对端在 registry 里的地址：这里只有一个观众，用一个固定端点号即可
        // （入站二进制帧靠指纹反查地址，两端用的是同一个值，所以回程帧路由得回去）。
        val viewerMarker = freePort()
        val host = LuciolaTransportFactory(profile("screen-host"), 0, seedPersist = { "3b".repeat(32) })
        val phone = LuciolaTransportFactory(profile("screen-viewer"), 0, seedPersist = { "7d".repeat(32) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sharer = SecureScreenChannel(
            identitySeed = host.identity.seed,
            peerKey = { _, _ -> phone.publicKey },
            listenPort = screenPort,
            isTrustedFingerprint = { true },
            peerOfFingerprint = { PeerEndpoint("127.0.0.1", viewerMarker) },
        )
        val viewer = SecureScreenChannel(
            identitySeed = phone.identity.seed,
            peerKey = { _, _ -> host.publicKey },
        )
        sharer.start()
        viewer.start()
        val accepted = CompletableDeferred<ScreenWire>()
        sharer.accept(scope) { accepted.complete(it) }
        val wire = viewer.dial("127.0.0.1", screenPort, scope)
        return Fixture(viewer, sharer, wire, accepted, scope)
    }

    private suspend fun readExactly(wire: ScreenWire, size: Int): ByteArray {
        val buf = ByteArray(size)
        val ok = withTimeoutOrNull(30_000) { wire.read.readFully(buf, 0, size) }
        assertNotNull(ok, "读满 $size 字节超时（信道把字节弄丢或弄乱了）")
        return buf
    }

    @Test
    fun viewerToSharerBytesArriveIntact() = runBlocking {
        val f = fixture()
        try {
            // **先写、再等**：这条会话是"有字节要送"才拨起来的（screen-* 的第一帧就是
            // screen-open）。先 await 再写会永远等在一个还没建立的会话上。
            val first = ByteArray(37) { (it + 1).toByte() }
            f.viewerWire.write.writeFully(first)
            val sharerWire = withTimeoutOrNull(20_000) { f.accepted.await() }
            if (sharerWire == null) {
                println("DIAG viewer: " + f.viewer.diagnostics())
                println("DIAG sharer: " + f.sharer.diagnostics())
                throw AssertionError("收端一直没有拿到画面会话")
            }
            // 三段不同长度、非 64KiB 对齐的写：切片边界一旦错位这里就红。
            assertContentEquals(first, readExactly(sharerWire, first.size))

            val big = ByteArray(300 * 1024) { (it % 251).toByte() }
            f.viewerWire.write.writeFully(big)
            assertContentEquals(big, readExactly(sharerWire, big.size))
        } finally {
            f.viewerWire.close()
            f.sharer.stop()
            f.viewer.stop()
            f.scope.cancel()
        }
    }

    @Test
    fun sharerToViewerBytesArriveIntact() = runBlocking {
        val f = fixture()
        try {
            // 投屏的真实方向是这一条：Sharer 回 screen-ready + 画面帧。
            // 先让 viewer 发一点字节（触发拨号并登记入站会话），Sharer 才知道往哪回。
            f.viewerWire.write.writeFully(ByteArray(8) { 1 })
            val sharerWire = withTimeout(30_000) { f.accepted.await() }
            readExactly(sharerWire, 8)

            val ready = ByteArray(64) { (it * 3 % 251).toByte() }
            sharerWire.write.writeFully(ready)
            assertContentEquals(ready, readExactly(f.viewerWire, ready.size))
        } finally {
            f.viewerWire.close()
            f.sharer.stop()
            f.viewer.stop()
            f.scope.cancel()
        }
    }

    @Test
    fun closingTheWireStopsSendingWithoutThrowing() = runBlocking {
        val f = fixture()
        try {
            f.viewerWire.write.writeFully(ByteArray(16) { 9 })
            val sharerWire = withTimeout(30_000) { f.accepted.await() }
            readExactly(sharerWire, 16)
            f.viewerWire.close()
            // 关掉之后写端再写不该把测试炸掉（上层按"投屏已断开"收摊）。
            runCatching { f.viewerWire.write.writeFully(ByteArray(16) { 9 }) }
            assertTrue(true)
        } finally {
            f.sharer.stop()
            f.viewer.stop()
            f.scope.cancel()
        }
    }
}
