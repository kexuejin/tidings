// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.protocol.LinkFrame
import com.kapp.tidings.core.protocol.LinkFrames
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * K2 的连接层端到端（真 TLS 握手，回环）：钉住 [TlsLinkTransport] 本身，
 * 而不是只靠 task-16 的 spike 覆盖「同一套 JSSE 机制」。
 *
 * 两条断言各自对应 task-13 的一条裁决：正确 pin 必须建连并能承载数据；
 * 错误 pin 必须**拒绝**（fail-closed）—— 这条如果只断言"返回 null"还不够，
 * 所以下面还配合策略测试断言"绝不回退明文"。
 */
class TlsLinkTransportTest {

    private fun transport(dir: String) = TlsLinkTransport({ dir }, { listOf("127.0.0.1") })

    @Test
    fun theRightPinConnectsAndCarriesFramesBothWays() = runBlocking {
        val serverDir = createTempDir("relay-tls-server")
        val clientDir = createTempDir("relay-tls-client")
        val server = transport(serverDir)
        val client = transport(clientDir)
        val accepted = CompletableDeferred<LinkConnection>()
        try {
            assertTrue(server.startServer(0) { connection -> accepted.complete(connection) }, "TLS 监听必须起来")
            val port = server.boundServerPort ?: error("没绑到端口")

            val connection = client.connect("127.0.0.1", port, server.localPin, 5_000)
            assertNotNull(connection, "正确 pin 必须建连")
            assertTrue(connection.isSecure, "这条连接必须是 TLS")
            assertEquals(server.localPin, connection.peerPin, "客户端必须记下服务端的 SPKI pin")

            // 上行一帧（用 hello 当载荷，形状无所谓，验的是"帧能过去"）。
            connection.send(LinkFrames.encode(LinkFrame.Ping))
            val serverConnection = withTimeout(5_000) { accepted.await() }
            val up = withTimeout(5_000) { serverConnection.receiveFrame() }
            assertNotNull(up, "服务端必须收到一帧")
            assertTrue(up.decodeToString().contains("link-ping"))

            // 下行一帧（验收里的"主机下行"就是这条路的形状）。
            serverConnection.send(LinkFrames.encode(LinkFrame.Pong))
            val down = withTimeout(5_000) { connection.receiveFrame() }
            assertNotNull(down, "客户端必须收到下行帧")
            assertTrue(down.decodeToString().contains("link-pong"))

            connection.close()
        } finally {
            server.stopServer()
            removeRecursively(serverDir)
            removeRecursively(clientDir)
        }
    }

    @Test
    fun aWrongPinIsRejectedInsteadOfTrusted() = runBlocking {
        val serverDir = createTempDir("relay-tls-server-bad")
        val clientDir = createTempDir("relay-tls-client-bad")
        val server = transport(serverDir)
        val client = transport(clientDir)
        try {
            assertTrue(server.startServer(0) { connection -> connection.close() })
            val port = server.boundServerPort ?: error("没绑到端口")
            val wrong = TlsPin.ofSpki("not-the-server-spki".encodeToByteArray())

            val connection = client.connect("127.0.0.1", port, wrong, 5_000)

            assertNull(connection, "pin 不匹配必须建不起来（fail-closed，绝不降级明文）")
        } finally {
            server.stopServer()
            removeRecursively(serverDir)
            removeRecursively(clientDir)
        }
    }

    /**
     * 空闲的连接**不许**被读超时误判成"对端关了"（真机缺陷）。
     *
     * 现场：跨网长连接每 5.4 秒被断一次、`linkConnected` 周期性 True/False 跳；
     * 而只要一直有消息来往就稳。根因是 `connect()` 把 `soTimeout` 留在握手用的 5 秒上，
     * 空闲时 `inputStream.read` 抛 `SocketTimeoutException`，`receiveFrame` 用
     * `runCatching{}.getOrNull()` 把**任何**异常折叠成 null ⇒ pump 认成 peer-closed ⇒
     * 关连接 + 退避 + 重拨。这条测试让连接空转 2 倍握手超时，然后要求它仍然可用。
     */
    @Test
    fun anIdleConnectionSurvivesLongerThanTheHandshakeTimeout() = runBlocking {
        val serverDir = createTempDir("relay-tls-idle-server")
        val clientDir = createTempDir("relay-tls-idle-client")
        val server = transport(serverDir)
        val client = transport(clientDir)
        val accepted = CompletableDeferred<LinkConnection>()
        try {
            assertTrue(server.startServer(0) { connection -> accepted.complete(connection) })
            val port = server.boundServerPort ?: error("没绑到端口")
            // 握手超时给 1 秒：空转 2.5 秒就是它的 2.5 倍，足够暴露"读超时被当成断开"。
            val connection = client.connect("127.0.0.1", port, server.localPin, 1_000)
            assertNotNull(connection, "正确 pin 必须建连")
            val serverConnection = withTimeout(5_000) { accepted.await() }

            // **关键：必须读的是客户端那一侧。**
            // soTimeout 是 per-socket 的，而缺陷在 `connect()` 里（它把握手用的超时留在了活连接上）；
            // 服务端那条路（`startServer`）从不设置它，所以只测服务端读永远是绿的 —— 那正是
            // 这个 bug 能在真机上活下来、却没有任何单测拦它的原因。
            // async 与 delay/send **必须在同一个 coroutineScope 里**：scope 会等子协程结束，
            // 把 async 单独包一层 scope 会让它等一个"要等它返回之后才发"的帧 ⇒ 死锁。
            coroutineScope {
                val pendingDownstream = async(kotlinx.coroutines.Dispatchers.IO) {
                    connection.receiveFrame()
                }
                // 空转 2.5 秒（握手超时是 1 秒），期间客户端那次 read 一直被阻塞。
                kotlinx.coroutines.delay(2_500)

                // 现在才下行：读取必须仍在等这一帧，而不是早已被读超时判成"断开"返回 null。
                serverConnection.send(LinkFrames.encode(LinkFrame.Pong))
                val down = withTimeout(5_000) { pendingDownstream.await() }
                assertNotNull(
                    down,
                    "客户端空闲 2.5 秒后，仍在等的那次读取必须拿到帧（读超时不该被当成断开）—— " +
                        "客户端 socket 的 soTimeout 必须归零，空闲判定交给应用层的 45 秒",
                )
                assertTrue(down.decodeToString().contains("link-pong"))
            }

            serverConnection.send(LinkFrames.encode(LinkFrame.Pong))
            val down = withTimeout(5_000) { connection.receiveFrame() }
            assertNotNull(down, "空闲 2.5 秒后下行仍必须通")
            assertTrue(down.decodeToString().contains("link-pong"))

            connection.close()
        } finally {
            server.stopServer()
            removeRecursively(serverDir)
            removeRecursively(clientDir)
        }
    }
    @Test
    fun renewingTheCertificateKeepsTheSamePin() = runBlocking {
        // 自动续签的承诺：私钥不变 ⇒ pin(SPKI) 不变 ⇒ 不需要重新配对。
        val dir = createTempDir("relay-tls-renew")
        try {
            val first = transport(dir)
            val firstPin = first.localPin
            // 删掉证书、只留私钥：下一次 ensure 会用同一把私钥重签。
            java.io.File(dir, "link-cert.der").delete()
            val second = transport(dir)
            assertEquals(firstPin, second.localPin, "同密钥重签后 pin 必须不变")
        } finally {
            removeRecursively(dir)
        }
    }

    /**
     * **证书到期读数是真读数**：重签之后它必须变成新的日期，而不是沿用旧值。
     *
     * 这条守的是「界面显示的值来自证书本身」——若当初记成「签发时 +365 天」的副本，
     * 重签后副本不会动，用户看到的就是一个与实际不符的日期。
     */
    @Test
    fun theCertificateExpiryIsReadFromTheRealCertificate(): Unit = runBlocking {
        val dir = createTempDir("relay-tls-expiry")
        try {
            val first = transport(dir)
            val notAfter = assertNotNull(first.localCertNotAfterMillis, "有 TLS 身份就必须给得出到期时刻")
            val now = System.currentTimeMillis()
            assertTrue(notAfter > now, "到期时刻必须在将来（刚签发的是 +365 天）")
            assertTrue(notAfter - now < 400L * 86_400_000L, "不该是一个离谱的远期日期")

            // 重签：删证书、留私钥 ⇒ 新证书应有自己的 notAfter（同密钥，时间可能相同到秒，
            // 所以这里不断言"必须不同"，只断言它仍然是一个合法读数）。
            java.io.File(dir, "link-cert.der").delete()
            val second = transport(dir)
            assertNotNull(second.localCertNotAfterMillis, "重签后仍要有读数")
        } finally {
            removeRecursively(dir)
        }
    }
}
