// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.desktop

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.transport.PeerRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 桌面宿主包装:开关语义、身份与配对密钥的落盘(重启后同一身份、同一 secret)。 */
class DesktopLuciolaHostTest {

    private fun profile(id: String) = Profile(
        nodeId = id, nick = id, company = "", dept = "", team = "", avatar = 1,
        profileRev = 1, host = "localhost", platform = "jvm", tcpPort = 0, ver = "1",
        caps = emptyList(),
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun tempDir(): String = Files.createTempDirectory("tidings-luciola").toString()

    @Test
    fun disabledHostProvidesNothing() {
        val host = DesktopLuciolaHost({ tempDir() }, enabled = false)
        host.configure(profile("off"), 17878)
        assertEquals(null, host.pairingInfo())
        assertEquals(null, host.transportFactory())
        assertEquals(null, host.discoveryFactory())
        assertFalse(host.pairPeer("127.0.0.1", 17878, com.kapp.tidings.domain.LuciolaQrInfo.of(ByteArray(32), ByteArray(16))!!))
    }

    @Test
    fun identityAndPairSecretSurviveRestart() {
        val dir = tempDir()
        val first = DesktopLuciolaHost({ dir }, enabled = true)
        first.configure(profile("node-desk"), 17878)
        val info = assertNotNull(first.pairingInfo(), "启用后必须有可印的配对信息")
        assertTrue(File(dir, "luciola/ed25519-seed.hex").exists(), "身份 seed 必须落盘")
        assertTrue(File(dir, "luciola/state.json").exists(), "信任库/密钥必须落盘")

        val second = DesktopLuciolaHost({ dir }, enabled = true)
        second.configure(profile("node-desk"), 17878)
        val again = assertNotNull(second.pairingInfo())
        assertEquals(info.publicKey, again.publicKey, "重启后身份必须一致")
        assertEquals(info.secret, again.secret, "重启后配对密钥必须一致(否则已扫的码全失效)")
    }

    /**
     * 桌面宿主包装的端到端验收：两个真实宿主 + 真实 TCP 回环。
     *
     * **当前跳过**:同一流程已在 core 的 `LuciolaSecureChannelTest` 里跑通(直接构
     * 传输,不经包装);包装这条路径在本机跑不通,原因未定位 —— 见下方 ignore 说明。
     */
    @Test
    fun twoDesktopHostsPairOverEncryptedChannelAndExchangeFrames() = runBlocking {
        val hostPort = freePort()
        val phonePort = freePort()
        val host = DesktopLuciolaHost({ tempDir() }, enabled = true).apply { configure(profile("node-host"), hostPort) }
        val phone = DesktopLuciolaHost({ tempDir() }, enabled = true).apply { configure(profile("node-phone"), phonePort) }

        val info = assertNotNull(host.pairingInfo())
        assertTrue(phone.pairPeer("127.0.0.1", hostPort, info), "扫码后必须存下主机的 pin 与 secret")

        val hostRegistry = PeerRegistry("node-host").apply {
            touch("node-phone", "127.0.0.1", phonePort, profile("node-phone"))
        }
        val phoneRegistry = PeerRegistry("node-phone").apply {
            touch("node-host", "127.0.0.1", hostPort, profile("node-host"))
        }
        val hostTransport = assertNotNull(host.transportFactory())(hostRegistry)
        val phoneTransport = assertNotNull(phone.transportFactory())(phoneRegistry)
        hostTransport.start()
        phoneTransport.start()
        try {
            awaitTrue(timeoutMs = 15_000, pollMs = 100) {
                phoneTransport.requestPairing("127.0.0.1", hostPort, "node-phone")
            }
            // 帧必须由 Codec 产生:手写 JSON 过不了协议的字段校验(传输层解码不到 from
            // 就会把整帧丢掉 —— 这一步正好也被这次调试证实了)。
            val frame = Codec.encodeToBytes(
                makeEnvelope(MsgTypes.MSG, "node-phone", Codec.textPayload("paired-by-scan")),
            )
            val atHost = async { withTimeout(15_000) { hostTransport.incoming.first() } }
            awaitTrue(timeoutMs = 15_000, pollMs = 100) {
                if (atHost.isCompleted) true else { phoneTransport.send(frame, "127.0.0.1", hostPort); false }
            }
            assertContentEquals(frame, atHost.await().bytes, "配对后业务帧必须走加密信道到达主机")
        } finally {
            hostTransport.stop()
            phoneTransport.stop()
        }
    }

    @Test
    fun scannedHostInfoCanBeStoredAndFactoriesComeOnline() {
        val phoneDir = tempDir()
        val phone = DesktopLuciolaHost({ phoneDir }, enabled = true)
        phone.configure(profile("node-phone"), freePort())
        assertTrue(phone.transportFactory() != null && phone.discoveryFactory() != null)

        val hostDir = tempDir()
        val host = DesktopLuciolaHost({ hostDir }, enabled = true)
        val hostPort = freePort()
        host.configure(profile("node-host"), hostPort)
        val hostInfo = assertNotNull(host.pairingInfo())

        assertTrue(phone.pairPeer("127.0.0.1", hostPort, hostInfo), "扫到主机信息必须能存下")

        // 同一目录重启后,配对记录必须还在(pairPeer 成功而不是再次失败)。
        val revived = DesktopLuciolaHost({ phoneDir }, enabled = true)
        revived.configure(profile("node-phone"), 17879)
        assertTrue(revived.pairPeer("127.0.0.1", hostPort, hostInfo))
    }
}
