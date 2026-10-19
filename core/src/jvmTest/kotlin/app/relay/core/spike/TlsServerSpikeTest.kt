// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.spike

import app.relay.core.crypto.TlsPin
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ============================ SPIKE，不是生产代码 ============================
 *
 * task-13 的 P2 前置门槛：**JVM 侧能不能做"服务端 TLS + 自签 IP 证书 + SPKI 固定"**。
 * 结论要可执行，所以这里真的起一个 SSLServerSocket 并完成一次 **双向** 握手
 * （捎话两端都会监听，服务端 TLS 是必需项，不只是客户端）。
 *
 * 这段代码只存在于 jvmTest，删掉它不影响任何生产路径（见 `docs/spike-kmp-tls.md`）。
 *
 * 证书是**手写 DER**（照搬 DS-Bot 的 `local-certificate.js` 形状）而不是用
 * `sun.security.x509`：后者在 Android 上不存在，而这个形状必须三端都能用。
 */
class TlsServerSpikeTest {

    private val loopback4 = listOf("127.0.0.1")
    private val loopback6 = listOf("::1")

    @Test
    fun jvmCanServeMutualTlsWithSelfSignedIpCertAndSpkiPinning() {
        val server = newIdentity("relay-spike-server", loopback4 + loopback6)
        val client = newIdentity("relay-spike-client", loopback4)

        // 双向固定：服务端只认客户端这张 SPKI，客户端只认服务端那张。
        val serverCtx = sslContext(server, peerPin = client.pin)
        val clientCtx = sslContext(client, peerPin = server.pin)

        val serverSocket = serverCtx.serverSocketFactory
            .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        serverSocket.needClientAuth = true // 服务端要求客户端出示证书 = mutual
        val port = serverSocket.localPort
        val payload = "跨公网也要看得见的明文之外".encodeToByteArray()

        val received = ByteArray(payload.size)
        val serverJob = thread(name = "spike-server") {
            runCatching {
                serverSocket.accept().use { raw ->
                    val socket = raw as SSLSocket
                    socket.startHandshake()
                    val input = socket.inputStream
                    var off = 0
                    while (off < received.size) {
                        val n = input.read(received, off, received.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    socket.outputStream.write(received, 0, off)
                    socket.outputStream.flush()
                }
            }
        }

        try {
            val socket = clientCtx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
            socket.use {
                it.startHandshake()
                it.outputStream.write(payload)
                it.outputStream.flush()
                val echo = it.inputStream.readExactly(payload.size)
                assertContentEquals(payload, echo, "TLS 通道必须原样往返（含多字节 UTF-8）")
            }
        } finally {
            runCatching { serverSocket.close() }
            serverJob.join(5_000)
        }
        assertContentEquals(payload, received, "服务端确实收到了字节（不是本地自嗨）")
    }

    @Test
    fun aWrongPinFailsTheHandshakeInsteadOfSilentlyTrusting() {
        val server = newIdentity("relay-spike-server", loopback4)
        val client = newIdentity("relay-spike-client", loopback4)
        val other = newIdentity("relay-spike-other", loopback4)

        val serverCtx = sslContext(server, peerPin = client.pin)
        // 客户端拿的是**别人**的 pin：必须握手失败，而不是"没验上但继续"。
        val clientCtx = sslContext(client, peerPin = other.pin)

        val serverSocket = serverCtx.serverSocketFactory
            .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        serverSocket.needClientAuth = true
        val port = serverSocket.localPort
        val serverJob = thread(name = "spike-server-badpin") {
            runCatching { (serverSocket.accept() as SSLSocket).startHandshake() }
        }
        try {
            val socket = clientCtx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
            socket.use {
                assertFailsWith<SSLHandshakeException>("pin 不匹配必须握手失败（fail-closed）") {
                    it.startHandshake()
                }
            }
        } finally {
            runCatching { serverSocket.close() }
            serverJob.join(5_000)
        }
    }

    @Test
    fun theGeneratedCertificateCarriesTheIpSanAndThePinIsTheSpkiHash() {
        val id = newIdentity("relay-spike-san", listOf("127.0.0.1", "::1"))
        // SAN 里必须有这两个 IP，否则系统按地址连过来会判"证书不匹配"。
        // JDK 会把 ::1 归一成 0:0:0:0:0:0:0:1，所以按 InetAddress 比而不是比字面量。
        val ipSans = id.cert.subjectAlternativeNames
            .orEmpty()
            .filter { it[0] == 7 } // type 7 = iPAddress
            .map { InetAddress.getByName(it[1] as String) }
        assertEquals(2, ipSans.size, "SAN 必须有两条 iPAddress")
        assertTrue(ipSans.any { it.hostAddress == "127.0.0.1" }, "缺 IPv4 回环")
        assertTrue(ipSans.any { it.isLoopbackAddress && it.hostAddress!!.contains(':') }, "缺 IPv6 回环")
        // pin 是 SPKI 的 sha256，不是整张证书的指纹：重签（私钥不变）后 pin 不变。
        assertEquals(TlsPin.ofSpki(id.keyPair.public.encoded), id.pin)
    }

    // ---------------------------------------------------------------- spike 脚手架

    private class Identity(
        val keyPair: KeyPair,
        val cert: X509Certificate,
        val certDer: ByteArray,
    ) {
        val pin: TlsPin get() = TlsPin.ofSpki(keyPair.public.encoded)
    }

    private fun newIdentity(cn: String, addresses: List<String>): Identity {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val der = selfSignedCertificateDer(keyPair, cn, addresses, System.currentTimeMillis())
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        return Identity(keyPair, cert, der)
    }

    private fun sslContext(id: Identity, peerPin: TlsPin): SSLContext {
        val password = "spike".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry("identity", id.keyPair.private, password, arrayOf(id.cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, password) }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = verify(chain)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = verify(chain)
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            private fun verify(chain: Array<X509Certificate>) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("对端没有出示证书")
                if (!peerPin.matches(leaf.publicKey.encoded)) {
                    throw CertificateException("SPKI 与固定值不匹配")
                }
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(trust), SecureRandom())
        }
    }

    // ---- 最小 DER / X.509 生成（EC P-256，SAN = iPAddress）----
    //
    // 与 DS-Bot 的 local-certificate.js 同形状：版本 v3、basicConstraints(CA=false,critical)、
    // keyUsage(digitalSignature,critical)、extKeyUsage(serverAuth)、subjectAltName(iPAddress)。

    private fun selfSignedCertificateDer(
        keyPair: KeyPair,
        cn: String,
        addresses: List<String>,
        now: Long,
    ): ByteArray {
        val algorithm = seq(hex("06082a8648ce3d040302")) // ecdsa-with-SHA256
        val name = seq(der(0x31, seq(hex("0603550403"), der(0x0c, cn.encodeToByteArray()))))
        fun extension(oid: String, value: ByteArray, critical: Boolean): ByteArray = seq(
            hex(oid),
            *(if (critical) arrayOf(hex("0101ff")) else emptyArray()),
            der(0x04, value),
        )
        val ipSans = addresses.map { der(0x87, ipBytes(it)) }
        val extensions = der(0xa3, seq(
            extension("0603551d13", seq(), critical = true),
            extension("0603551d0f", hex("03020780"), critical = true),
            extension("0603551d25", seq(hex("06082b06010505070301")), critical = false),
            extension("0603551d11", seq(*ipSans.toTypedArray()), critical = false),
        ))
        val serialBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        serialBytes[0] = (serialBytes[0].toInt() or 0x80).toByte()
        val serial = der(0x02, concat(byteArrayOf(0), serialBytes))
        val tbs = seq(
            der(0xa0, hex("020102")), // version [0] EXPLICIT INTEGER 2 = v3
            serial,
            algorithm,
            name,
            seq(asn1Time(now - 300_000L), asn1Time(now + 365L * 86_400_000L)),
            name,
            keyPair.public.encoded, // 已经是 X.509 SubjectPublicKeyInfo DER
            extensions,
        )
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(tbs)
        }.sign()
        return seq(tbs, algorithm, der(0x03, concat(byteArrayOf(0), signature)))
    }

    private fun ipBytes(address: String): ByteArray =
        InetAddress.getByName(address).address

    private fun asn1Time(epochMillis: Long): ByteArray {
        val utc = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneOffset.UTC)
        val y = utc.year
        val digits = "%04d%02d%02d%02d%02d%02d".format(y, utc.monthValue, utc.dayOfMonth, utc.hour, utc.minute, utc.second)
        return if (y < 2050) {
            der(0x17, (digits.substring(2) + "Z").encodeToByteArray()) // UTCTime
        } else {
            der(0x18, (digits + "Z").encodeToByteArray()) // GeneralizedTime
        }
    }

    private fun der(tag: Int, value: ByteArray): ByteArray {
        val out = ArrayList<Byte>(value.size + 6)
        out.add(tag.toByte())
        val length = value.size
        if (length < 128) {
            out.add(length.toByte())
        } else {
            var n = length
            val stack = ArrayList<Byte>()
            while (n > 0) {
                stack.add((n and 0xFF).toByte())
                n = n ushr 8
            }
            out.add((0x80 or stack.size).toByte())
            for (i in stack.indices.reversed()) out.add(stack[i])
        }
        for (b in value) out.add(b)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray): ByteArray = der(0x30, concat(*parts))

    private fun concat(vararg parts: ByteArray): ByteArray {
        val size = parts.sumOf { it.size }
        val out = ByteArray(size)
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = read(out, off, size - off)
            if (n <= 0) break
            off += n
        }
        assertNotNull(out, "读到的字节")
        return out.copyOf(off)
    }
}
