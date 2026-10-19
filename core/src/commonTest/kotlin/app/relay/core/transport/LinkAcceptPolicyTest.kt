// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.TlsPin
import app.relay.core.protocol.LinkFrame
import app.relay.core.protocol.LinkFrames
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task-32：**公网对端绝不允许明文长连接**（task-13 的硬约束）。
 *
 * 真机现场：手机从 WiFi 切到移动网之后，主机仍然挂着那条 **WiFi 期**建立的明文连接，
 * 诊断里 `linkCarrier=plaintext`、对端地址却已经是运营商地址 —— 判定只在 accept 那一刻
 * 做过，地址后来变了没人复查。这个文件同时钉住两半：
 *  · 接受时的裁决表（[linkDenyReason]）；
 *  · **地址变成公网之后**，那条明文连接必须被立刻关掉（`LinkServer.handle` + registry 事件）。
 */
class LinkAcceptPolicyTest {

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = listOf(MsgTypes.CAP_LINK),
    )

    private val publicAddress = "2001:db8:2020:8494:d46b:34ff:fe9c:069b"
    private val privateAddress = "192.168.2.140"
    private val pin = "sha256:" + "a".repeat(64)

    /** 内存连接：够跑 LinkServer.handle 的真实路径，又不需要开 socket。 */
    private class FakeConnection(
        override val remoteHost: String,
        override val isSecure: Boolean,
        override val isPlaintext: Boolean = false,
        override val remotePort: Int = 17879,
        override val peerPin: TlsPin? = null,
        private val inbound: MutableList<ByteArray> = mutableListOf(),
    ) : LinkConnection {
        val sent = mutableListOf<ByteArray>()
        var closed = false
        override suspend fun send(frame: ByteArray) { sent += frame }
        override suspend fun receiveFrame(): ByteArray? {
            // 首帧（Hello）之后**保持连接活着**：这一文件里的用例都在断言"注册/拒绝/是否被摘"，
            // 若这里立刻返回 null，pump 会在另一条协程上把连接摘掉 —— 断言与它赛跑，
            // 单跑侥幸绿、全量跑（负载高）就红（2026-10-18 实测）。
            if (inbound.isEmpty()) {
                delay(30_000)
                return null
            }
            return inbound.removeAt(0)
        }
        override fun close() { closed = true }
    }

    private fun helloFrames(nodeId: String): MutableList<ByteArray> =
        mutableListOf(LinkFrames.body(LinkFrame.Hello(nodeId)).encodeToByteArray())

    @Test
    fun aPlaintextConnectionFromAPublicAddressIsDenied() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("host")
        registry.touch("peer", privateAddress, 17878, profile("peer"), 1L, confirmed = true)
        val server = LinkServer(registry = registry, scope = scope)
        val connection = FakeConnection(remoteHost = publicAddress, isSecure = false, inbound = helloFrames("peer"))
        try {
            server.handle(connection)
            assertFalse(server.isConnected("peer"), "公网地址上的明文连接绝不能注册")
            assertTrue(
                connection.sent.any { it.decodeToString().contains(LinkFrames.REASON_NOT_LAN) },
                "必须回 link-deny(not-lan)：${connection.sent.map { it.decodeToString() }}",
            )
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun aPlaintextConnectionFromALanAddressIsAcceptedAndThenClosedOnceThePeerGoesPublic() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("host")
        registry.touch("peer", privateAddress, 17878, profile("peer"), 1L, confirmed = true)
        val server = LinkServer(registry = registry, scope = scope)
        // task-40：复查现在只看**正向明文标记**（isPlaintext），所以明文替身必须声明它 ——
        // 这正是真机上"TLS 连接被误摘"的修法：判据不再靠 !isSecure 反推。
        val connection = FakeConnection(
            remoteHost = privateAddress,
            isSecure = false,
            isPlaintext = true,
            inbound = helloFrames("peer"),
        )
        try {
            server.handle(connection)
            assertTrue(server.isConnected("peer"), "局域网明文必须能建（K1 不失效）")
            assertEquals("plaintext", server.linkCarriers()["peer"])

            // 对端换到移动网：地址变成公网 ⇒ 那条明文连接必须被立刻关掉，而不是等 45s 判死。
            registry.setActiveEndpoint("peer", publicAddress, 17878)
            assertFalse(server.isConnected("peer"), "对端地址变成公网后，明文连接必须被摘掉")
            assertTrue(connection.closed, "并且要真的关掉那条 socket")
            // task-40：主动关闭必须留下**判据两元组**，下一次真机一眼就能确认是不是这条。
            val detail = server.closeDetails()["peer"] ?: error("主动关闭必须记 detail")
            assertTrue(detail.contains("isPlaintext=true"), "detail 必须带明文标记：$detail")
            assertTrue(detail.contains("ip="), "detail 必须带对端地址：$detail")
        } finally {
            server.close()
            scope.cancel()
        }
    }

    /**
     * task-36 真机根因：**主机侧从来没有手机的 pin**（二维码是主机→手机单向的），
     * 于是双向固定把手机的 TLS 连接一律拒成 `pin-missing`（手机侧只看到笼统的 hello-rejected）。
     *
     * 修法是**首次固定（TOFU）**：第一次用对端客户端证书的 SPKI 固定下来，此后必须一致。
     * 这条测试同时钉住"第一次学到"和"第二次不匹配就拒"——后者才是 fail-closed 的那一半。
     */
    @Test
    fun theHostPinsTheFirstClientCertificateAndThenRequiresIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("host")
        registry.touch("peer", privateAddress, 17878, profile("peer"), 1L, confirmed = true)
        assertEquals(null, registry.pinOf("peer"), "前提：主机侧一开始没有这台设备的 pin")
        val learned = mutableListOf<Pair<String, String>>()
        val server = LinkServer(
            registry = registry,
            scope = scope,
            onPinLearned = { nodeId, pin -> learned += nodeId to pin },
        )
        val firstPin = TlsPin.parse("sha256:" + "a".repeat(64))!!
        val first = FakeConnection(
            remoteHost = privateAddress,
            isSecure = true,
            peerPin = firstPin,
            inbound = helloFrames("peer"),
        )
        try {
            server.handle(first)
            assertEquals(firstPin.wire, registry.pinOf("peer"), "第一次必须把对端证书 SPKI 固定下来")
            assertEquals(listOf("peer" to firstPin.wire), learned, "学到之后要交给宿主落盘")
            assertEquals(emptyMap(), server.denyReasons(), "TOFU 这次不算拒绝")
            // task-38 底线③：TOFU 是**降级路径**（有首次使用竞态），来源必须如实标出来，
            // 不能让"其实还在 TOFU"变成新的静默。
            assertEquals(
                PeerRegistry.PIN_SOURCE_TOFU,
                registry.pinSourceOf("peer"),
                "TOFU 学到的 pin 来源必须是 tofu",
            )

            // 第二次：换一枚 pin（冒充者）⇒ 必须拒，且原因说清是 pin 不匹配。
            val impostor = FakeConnection(
                remoteHost = privateAddress,
                isSecure = true,
                peerPin = TlsPin.parse("sha256:" + "b".repeat(64)),
                inbound = helloFrames("peer"),
            )
            server.handle(impostor)
            assertEquals("pin-mismatch", server.denyReasons()["peer"], "固定之后 pin 不一致必须拒")
            assertTrue(
                impostor.sent.any { it.decodeToString().contains("link-deny") },
                "必须回 link-deny：${impostor.sent.map { it.decodeToString() }}",
            )
            assertEquals(firstPin.wire, registry.pinOf("peer"), "被拒的连接不许改写已固定的 pin")
        } finally {
            server.close()
            scope.cancel()
        }
    }

    /**
     * task-53（接受侧）：**同 /64 的 GUA 明文必须能建** —— 与拨号侧、复查侧同一条判据。
     *
     * 三处不一致时的后果：家里 LAN 是 GUA-/64（中国宽带最常见）时，K1 一个目标都不拨、
     * 就算拨了也会在 accept 那一刻被拒成 `not-lan` ⇒ **K1 被静默关掉**。
     */
    @Test
    fun aPlaintextConnectionFromASameLanGuaIsAccepted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lanGua = "2001:db8:2020:cb44:31f4:ca9a:d4e2:a54a"
        val registry = PeerRegistry("host")
        registry.touch("peer", lanGua, 17878, profile("peer"), 1L, confirmed = true)
        val server = LinkServer(
            registry = registry,
            scope = scope,
            localAddresses = { listOf("2001:db8:2020:cb44:1400:fdbb:f0d0:7679", "192.168.2.82") },
        )
        val connection = FakeConnection(
            remoteHost = lanGua,
            isSecure = false,
            isPlaintext = true,
            inbound = helloFrames("peer"),
        )
        try {
            server.handle(connection)
            assertTrue(
                server.isConnected("peer"),
                "同 /64 的 GUA 明文必须能建（task-53：K1 不许被静默关掉）",
            )
            assertEquals(emptyMap(), server.denyReasons(), "不该有任何拒绝原因")
        } finally {
            server.close()
            scope.cancel()
        }
    }

    /**
     * task-38：**配对带来的 pin ⇒ 首次握手就按它校验，不再 TOFU**（竞态窗口消失）。
     *
     * 真手机在配对时把自己的 pin 随资料带过来（[Profile.tlsPin]），宿主存成 `pairing` 来源；
     * 于是第一次 TLS 连接就必须与该 pin 一致 —— "攻击者抢在真手机之前声明同一 nodeId"不再可能，
     * 因为没有任何"首次见到就信"的机会。
     */
    @Test
    fun aPairingPinIsVerifiedOnTheFirstHandshakeAndNoTofuHappens() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("host")
        registry.touch("peer", privateAddress, 17878, profile("peer"), 1L, confirmed = true)
        val pairingPin = TlsPin.parse(pin)!!
        // 配对时固定下来的 pin（来源 pairing）—— 不是 TOFU 学来的。
        registry.setPin("peer", pairingPin.wire, PeerRegistry.PIN_SOURCE_PAIRING)
        val learned = mutableListOf<Pair<String, String>>()
        val server = LinkServer(
            registry = registry,
            scope = scope,
            onPinLearned = { nodeId, p -> learned += nodeId to p },
        )
        val connection = FakeConnection(
            remoteHost = privateAddress,
            isSecure = true,
            peerPin = pairingPin,
            inbound = helloFrames("peer"),
        )
        try {
            server.handle(connection)
            assertTrue(server.isConnected("peer"), "证书与配对 pin 一致 ⇒ 必须放行")
            assertEquals(emptyList(), learned, "配对已有 pin ⇒ **不许**再走 TOFU 学一遍")
            assertEquals(
                PeerRegistry.PIN_SOURCE_PAIRING,
                registry.pinSourceOf("peer"),
                "来源必须还是 pairing（没有降级）",
            )
        } finally {
            server.close()
            scope.cancel()
        }
    }

    /** task-38：配对带来的 pin **不符就拒**（fail-closed，绝不退回 TOFU）。 */
    @Test
    fun aPairingPinRejectsAMismatchedCertificate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("host")
        registry.touch("peer", privateAddress, 17878, profile("peer"), 1L, confirmed = true)
        val pairingPin = TlsPin.parse(pin)!!
        registry.setPin("peer", pairingPin.wire, PeerRegistry.PIN_SOURCE_PAIRING)
        val learned = mutableListOf<Pair<String, String>>()
        val server = LinkServer(
            registry = registry,
            scope = scope,
            onPinLearned = { nodeId, p -> learned += nodeId to p },
        )
        val impostor = FakeConnection(
            remoteHost = privateAddress,
            isSecure = true,
            peerPin = TlsPin.parse("sha256:" + "b".repeat(64)),
            inbound = helloFrames("peer"),
        )
        try {
            server.handle(impostor)
            assertFalse(server.isConnected("peer"), "与配对 pin 不符必须拒")
            assertEquals("pin-mismatch", server.denyReasons()["peer"], "原因要说清是 pin 不匹配")
            assertEquals(emptyList(), learned, "被拒的连接不许触发 TOFU")
            assertEquals(pairingPin.wire, registry.pinOf("peer"), "被拒的连接不许改写配对 pin")
            assertEquals(
                PeerRegistry.PIN_SOURCE_PAIRING,
                registry.pinSourceOf("peer"),
                "来源不许被降级成 tofu",
            )
        } finally {
            server.close()
            scope.cancel()
        }
    }

    /** 拒绝原因的诊断码必须精确（task-36 ①）：手机侧只看到 hello-rejected，分不出五种成因。 */
    /**
     * task-40 真机场景（lead 要求必补）：**明文 + 本机同 /64 的 GUA ⇒ 不得被复查摘掉**。
     *
     * 家庭 LAN 的 IPv6 常是全局单播 GUA（真机 `2001:db8:2020:cb44::/64`），按地址分类"就是公网"，
     * 可它其实与本机同一段网。连接建立时 record.ip 是私网 IPv4（明文合法），之后跳到这个 GUA ⇒
     * 旧判据误判成"明文 + 公网对端"、把合法的局域网明文连接摘掉（两端 close=recheck，手机重拨循环）。
     */
    @Test
    fun aPlaintextLinkOnTheSameLanGuaIsNotClosedByTheRecheck() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("host")
        registry.touch("peer", privateAddress, 17878, profile("peer"), 1L, confirmed = true)
        val server = LinkServer(
            registry = registry,
            scope = scope,
            // 本机地址里有同 /64 的 GUA ⇒ 对端算"同一段局域网"。
            localAddresses = { listOf("2001:db8:2020:cb44:1400:fdbb:f0d0:7679", "192.168.2.82") },
        )
        val connection = FakeConnection(
            remoteHost = privateAddress,
            isSecure = false,
            isPlaintext = true,
            inbound = helloFrames("peer"),
        )
        try {
            server.handle(connection)
            assertTrue(server.isConnected("peer"), "前提：局域网明文必须能建（K1）")
            // 对端地址跳到同 /64 的 GUA —— 真机上就是这一跳把连接摘了。
            registry.setActiveEndpoint("peer", "2001:db8:2020:cb44:31f4:ca9a:d4e2:a54a", 17878)
            assertTrue(
                server.isConnected("peer"),
                "明文 + 本机同 /64 的 GUA 必须算局域网，绝不能被复查摘掉（task-40 真机）",
            )
            assertEquals(null, server.closeReasons()["peer"], "没有发生任何关闭")
        } finally {
            server.close()
            scope.cancel()
        }
    }

    /**
     * task-40 真机回归：**TLS 连接 + 对端地址是公网 GUA ⇒ 复查不得摘它**。
     *
     * 现场：两端 `close=recheck`、每摘一次手机就重拨一次（`linkConnected` 每 ~9–12s 跳）。
     * 根因是复查按 `!isSecure` 反推"是不是明文"——某个路径上这个反推不成立，于是 TLS 连接
     * 被当成明文摘掉。现在判据换成**正向事实** [LinkConnection.isPlaintext]（只有明文实现置 true），
     * 取不到/不认识一律**不摘**。
     */
    @Test
    fun aTlsLinkIsNeverClosedByTheAddressRecheckEvenWhenThePeerAddressIsPublic() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("host")
        registry.touch("peer", privateAddress, 17878, profile("peer"), 1L, confirmed = true)
        registry.setPin("peer", pin)
        val server = LinkServer(registry = registry, scope = scope)
        val connection = FakeConnection(
            remoteHost = privateAddress,
            isSecure = true,
            peerPin = TlsPin.parse(pin),
            inbound = helloFrames("peer"),
        )
        try {
            server.handle(connection)
            assertTrue(server.isConnected("peer"), "前提：TLS 连接必须注册成功")
            // 对端地址变成公网 GUA（真机上 2001:db8:… 就是这种，虽然它其实是本机 LAN 的 IPv6）。
            registry.setActiveEndpoint("peer", publicAddress, 17878)
            assertTrue(
                server.isConnected("peer"),
                "TLS 连接绝不能被地址复查摘掉（task-40：两端 close=recheck 就是这么来的）",
            )
            assertEquals(null, server.closeReasons()["peer"], "没有发生任何关闭")
            assertEquals(0, server.closeCounts()["peer"] ?: 0)
        } finally {
            server.close()
            scope.cancel()
        }
    }

    /** 反向：**明文**连接 + 对端地址是公网 ⇒ 仍然必须摘（K1 的边界不放松）。 */
    @Test
    fun aPlaintextLinkIsStillClosedWhenThePeerAddressBecomesPublic() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = PeerRegistry("host")
        registry.touch("peer", privateAddress, 17878, profile("peer"), 1L, confirmed = true)
        val server = LinkServer(registry = registry, scope = scope)
        val connection = FakeConnection(
            remoteHost = privateAddress,
            isSecure = false,
            isPlaintext = true,
            inbound = helloFrames("peer"),
        )
        try {
            server.handle(connection)
            assertTrue(server.isConnected("peer"), "前提：局域网明文连接必须能建（K1）")
            registry.setActiveEndpoint("peer", publicAddress, 17878)
            assertFalse(server.isConnected("peer"), "明文连接不许留在公网地址上")
            assertEquals(CLOSE_RECHECK, server.closeReasons()["peer"])
            assertTrue((server.closeCounts()["peer"] ?: 0) >= 1, "本端主动关闭必须计数")
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun theDenyDiagnosticNamesTheExactCause() {
        val hello = LinkFrame.Hello("peer")
        fun diag(
            remoteHost: String = privateAddress,
            isSecure: Boolean = false,
            known: Boolean = true,
            storedPin: String? = null,
            peerPin: TlsPin? = null,
        ) = linkDenyDiagnostic(hello, remoteHost, isSecure, known, storedPin, peerPin)

        assertEquals(null, diag(), "局域网明文必须允许")
        assertEquals(DENY_NOT_LAN, diag(remoteHost = publicAddress), "公网明文")
        assertEquals(DENY_UNKNOWN_PEER, diag(known = false), "不认识的设备")
        assertEquals(
            DENY_BAD_VERSION,
            linkDenyDiagnostic(LinkFrame.Hello("peer", ver = 99), privateAddress, false, true, null, null),
            "版本不对",
        )
        assertEquals(
            DENY_PIN_MISSING,
            diag(remoteHost = publicAddress, isSecure = true),
            "主机侧没存过对方 pin（task-36 真机就是这一条）",
        )
        assertEquals(
            DENY_PIN_MISMATCH,
            diag(remoteHost = publicAddress, isSecure = true, storedPin = pin, peerPin = TlsPin.parse("sha256:" + "b".repeat(64))),
            "pin 不匹配",
        )
        assertEquals(null, diag(remoteHost = publicAddress, isSecure = true, storedPin = pin, peerPin = TlsPin.parse(pin)), "公网 TLS + pin 匹配：允许")
    }

    @Test
    fun theAcceptDecisionTableMatchesTheSecurityRules() {
        val hello = LinkFrame.Hello("peer")
        fun decide(
            remoteHost: String = privateAddress,
            isSecure: Boolean = false,
            known: Boolean = true,
            storedPin: String? = null,
            peerPin: TlsPin? = null,
        ) = linkDenyReason(hello, remoteHost, isSecure, known, storedPin, peerPin)

        // 明文：只许局域网。
        assertEquals(null, decide(), "局域网明文必须允许")
        assertEquals(LinkFrames.REASON_NOT_LAN, decide(remoteHost = publicAddress), "公网明文必须拒")
        // TLS：公网允许，但必须双向固定。
        assertEquals(
            LinkFrames.REASON_UNKNOWN_PEER,
            decide(remoteHost = publicAddress, isSecure = true),
            "公网 TLS 但没存 pin：不许建（fail-closed）",
        )
        assertEquals(
            LinkFrames.REASON_UNKNOWN_PEER,
            decide(remoteHost = publicAddress, isSecure = true, storedPin = pin),
            "对端证书没带 pin：不许建",
        )
        assertEquals(
            LinkFrames.REASON_UNKNOWN_PEER,
            decide(
                remoteHost = publicAddress,
                isSecure = true,
                storedPin = pin,
                peerPin = TlsPin.parse("sha256:" + "b".repeat(64)),
            ),
            "pin 不匹配：不许建，也不回退明文",
        )
        assertEquals(
            null,
            decide(remoteHost = publicAddress, isSecure = true, storedPin = pin, peerPin = TlsPin.parse(pin)),
            "公网 TLS + pin 匹配：允许",
        )
        // 身份：不认识的 nodeId 一律拒（谁都不能占用下行通道）。
        assertEquals(LinkFrames.REASON_UNKNOWN_PEER, decide(known = false), "未知节点必须拒")
        assertEquals(
            LinkFrames.REASON_UNKNOWN_PEER,
            linkDenyReason(LinkFrame.Hello("peer", ver = 99), privateAddress, false, true, null, null),
            "协议版本不对必须拒",
        )
    }
}
