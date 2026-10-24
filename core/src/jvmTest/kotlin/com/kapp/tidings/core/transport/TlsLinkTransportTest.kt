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

            // 上界只防挂死，不表达「handshake 应该多快」：全量并行（六个模块的测试 JVM
            // 抢 CPU）时 5s 会被握手开销吃掉，看起来像 pin 不对。20s 仍是有界等待。
            val connection = client.connect("127.0.0.1", port, server.localPin, 20_000)
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
            // **不拿墙上时钟作基准**：到期读数必须逐毫秒等于证书文件自己的 notAfter。
            // 原先写的是 notAfter > System.currentTimeMillis() —— 时钟被拨快一年、
            // 或测试机时区/时间不对时，这条会以"产品缺陷"的形态变红，而证书其实没问题。
            val cert = readCertificate(dir)
            assertEquals(cert.notAfter.time, notAfter, "到期读数必须来自证书本身，而不是另记的副本")
            assertTrue(notAfter > cert.notBefore.time, "notAfter 必须晚于 notBefore")

            // 重签：删证书、留私钥 ⇒ 新证书应有自己的 notAfter（同密钥，时间可能相同到秒，
            // 所以这里不断言"必须不同"，只断言它仍然是一个合法读数）。
            java.io.File(dir, "link-cert.der").delete()
            val second = transport(dir)
            assertNotNull(second.localCertNotAfterMillis, "重签后仍要有读数")
        } finally {
            removeRecursively(dir)
        }
    }

    /**
     * **重签判据的两个方向**：证书剩余寿命在 7 天窗口内 ⇒ 重签（pin 不变）；
     * 窗口外 ⇒ 原样保留。
     *
     * ## 为什么要有这条合成用例
     *
     * 上面那条只证明"读数是真读数"，证不了"判据本身对不对"。而判据
     * （`notAfter - now > 7 天`）是**唯一会因时间推进而改变产品行为**的地方：
     * 写错方向就会变成"证书还很久才到期却被反复重签"（pin 虽不变，但每次都要写盘，
     * 且 SAN 检查被绕过）或"快过期了却不重签"（真机到期那天所有公网连接同时失效）。
     *
     * 做法：同一把私钥、只改证书的 notBefore/notAfter 写回身份目录 ——
     * 判据的输入被精确控制，不需要等 365 天。两个方向都用"离窗口很远"的偏移
     * （1 天 vs 300 天），所以本机时钟有几小时甚至几天的偏差也不改变结论。
     */
    @Test
    fun theRenewWindowFlipsInBothDirections() {
        val dir = createTempDir("relay-tls-renew-window")
        try {
            val first = transport(dir)
            val pinBefore = first.localPin
            val keyDer = java.io.File(dir, "link-key.pk8").readBytes()
            val publicDer = java.io.File(dir, "link-pub.spki").readBytes()
            val privateKey = java.security.KeyFactory.getInstance("EC")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyDer))
            val publicKey = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(publicDer))

            fun writeCert(notBeforeMillis: Long, notAfterMillis: Long) {
                val der = LinkCertificateDer.selfSigned(
                    spki = publicDer,
                    serial = ByteArray(16) { (it + 1).toByte() },
                    sanIps = listOfNotNull(LinkCertificateDer.ipBytes("127.0.0.1")),
                    notBeforeMillis = notBeforeMillis,
                    notAfterMillis = notAfterMillis,
                    sign = { tbs ->
                        java.security.Signature.getInstance("SHA256withECDSA").run {
                            initSign(privateKey)
                            update(tbs)
                            sign()
                        }
                    },
                )
                java.io.File(dir, "link-cert.der").writeBytes(der)
            }

            // 时间基准取**身份自己**的 notBefore（首次签发时它已经是"现在 − 5 分钟"），
            // 于是这条用例里不再出现 System.currentTimeMillis() —— 墙上时钟被拨动也不影响判据。
            val now = readCertificate(dir).notBefore.time + 300_000L
            val oneDay = 86_400_000L

            // ① 只剩 1 天（< 7 天窗口）⇒ 必须重签：读数应当跃迁到新签的 +365 天。
            writeCert(now - 300_000L, now + oneDay)
            val renewed = transport(dir)
            val renewedAfter = assertNotNull(renewed.localCertNotAfterMillis, "重签后必须有读数")
            assertTrue(
                renewedAfter - (now + oneDay) > 300L * oneDay,
                "剩余寿命 1 天（在 7 天续签窗口内）却没有重签 —— 证书到期那一刻公网连接会集体失效",
            )
            assertEquals(pinBefore, renewed.localPin, "用同一把私钥重签，pin 必须不变（否则要重新配对）")

            // ② 还剩 300 天（远在窗口外）⇒ 不许重签：读数必须**原样**是那张合成证书的值。
            // 注意 X.509 的时间是**秒级**（我写进去的是毫秒），所以基准取"写回文件后读回来的
            // 那张证书"，而不是内存里那个毫秒数 —— 否则断言会在最后三位上假红。
            writeCert(now - 300_000L, now + 300L * oneDay)
            val craftedInFile = readCertificate(dir).notAfter.time
            val kept = transport(dir)
            assertEquals(
                craftedInFile,
                kept.localCertNotAfterMillis,
                "证书还有 300 天却被重签 —— 判据方向反了",
            )
        } finally {
            removeRecursively(dir)
        }
    }

    /** 读出身份目录里那张证书（测试用：不经过产品代码，避免"自己验自己"）。 */
    private fun readCertificate(dir: String): java.security.cert.X509Certificate =
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(java.io.File(dir, "link-cert.der").inputStream())
            as java.security.cert.X509Certificate
}
