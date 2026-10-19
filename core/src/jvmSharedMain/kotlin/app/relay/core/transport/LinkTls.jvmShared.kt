// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.TlsPin
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * K2 的连接层：**TLS + 双向 pin**（JVM 与 Android 共用这一份，Conscrypt 就是 JSSE）。
 *
 * 设计依据 docs/public-transport-security.md（task-13 的裁决）：
 *  · 自签 IP 证书（SAN 含本机全部地址），私钥持久化；
 *  · 双向固定：客户端验服务端证书的 SPKI == 配对时存下的 pin；服务端验客户端证书的 SPKI
 *    与 registry 里存的 pin 一致（后者在 LinkServer.register 里做，因为 nodeId 要等 hello）；
 *  · fail-closed：pin 不匹配**直接失败**，绝不回退明文；
 *  · 自动续签：证书快到期或 SAN 缺当前地址时，用**同一把私钥**重签 ⇒ pin(SPKI) 不变。
 *
 * 明文实现是 [PlaintextLinkTransport]（K1）；iOS 的 TLS 实现见 iosMain（尚未落地，公网被拒）。
 */
class TlsLinkTransport(
    private val identityDir: () -> String,
    private val addresses: () -> List<String>,
) : LinkTransport {

    override val isSecure: Boolean = true

    override val canServeTls: Boolean = true

    private val identity: LocalTlsIdentity by lazy { LocalTlsIdentity.ensure(identityDir(), addresses()) }

    @Volatile
    private var error: String? = null

    /** 只读诊断（task-32 ③）：区分"连不上""握手失败""pin 不匹配"—— 从前它们都是同一个 null。 */
    override val lastError: String? get() = error

    /** 本机的 pin（SPKI）。它本来就要进配对二维码，所以公开无妨 —— 测试与诊断也用它。 */
    override val localPin: TlsPin get() = identity.pin

    /** TLS 监听**实际绑上**的端口（传 0 时由系统分配；未监听为 null）。 */
    @Volatile
    var boundServerPort: Int? = null
        private set

    private var serverJob: Job? = null
    private var serverSocket: SSLServerSocket? = null

    // F1（task-39）跨网文件的**原始字节流**监听：与上面那套分帧监听并存、互不影响。
    // 命名刻意用 rawServer* 前缀，与 startServer 的 serverJob/serverSocket 不撞。
    private var rawServerJob: Job? = null
    private var rawServerSocket: SSLServerSocket? = null

    /** [startRawServer] **实际绑上**的端口（传 0 时由系统分配）；未监听为 null。 */
    @Volatile
    var rawServerBoundPort: Int? = null
        private set
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun connect(host: String, port: Int, expectedPin: TlsPin?, timeoutMs: Long): LinkConnection? =
        withContext(Dispatchers.IO) {
            runCatching {
                val context = clientContext()
                val socket = context.socketFactory.createSocket() as SSLSocket
                // **选网**（task-85 真身）：Android 只把**默认网络**的路由装进主表，于是
                // 默认网络是 WiFi、而那个 WiFi 的 v6 出不去时，发往公网 v6 的包无路可走 ——
                // 真机 `nc -6` 报 `Network is unreachable`（不是超时，是内核没有路由）。
                //
                // binder 在**建 socket 之后、connect 之前**回答"该绑哪张网"，这里只负责绑。
                // 默认实现（桌面 / iOS）返回 null ⇒ 一个字都不改，行为与以前完全一致。
                //
                // 绑失败不拦路：宁可用默认网络再失败一次，也不要因为选网出错而让整条路径挂掉。
                val binder = NetworkBinderHolder.current()
                val targetNetwork = runCatching { binder.networkFor(host) }.getOrNull()
                if (targetNetwork != null) {
                    runCatching { binder.bind(socket, targetNetwork) }
                }
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                } catch (t: Throwable) {
                    error = "connect-failed"
                    throw t
                }
                socket.soTimeout = timeoutMs.toInt()
                try {
                    socket.startHandshake()
                } catch (t: Throwable) {
                    error = "tls-handshake-failed"
                    runCatching { socket.close() }
                    throw t
                }
                val peer = peerCertificatePin(socket)
                // fail-closed：给了 pin 就必须匹配；对不上就断开（调用方退回原路径，绝不降级明文）。
                if (expectedPin != null && peer?.wire != expectedPin.wire) {
                    error = "pin-mismatch"
                    runCatching { socket.close() }
                    return@runCatching null
                }
                error = null
                // **握手超时只该管握手**（与 [connectRaw] 同一条纪律，那里早就归零了）。
                // 留着 5 秒的 soTimeout，空闲时 inputStream.read 会抛 SocketTimeoutException，
                // 而 [TlsLinkConnection.receiveFrame] 用 runCatching{}.getOrNull() 把**任何**异常
                // 都折成 null —— pump 于是把它当成"对端关了"（peer-closed），关连接并重拨。
                // 真机症状：跨网长连接每 5.4 秒被"对端主动断开"一次、linkConnected 周期性
                // True/False 跳；而一发消息（有字节、读不超时）就稳。空闲判定交给应用层的
                // 45 秒 deadAfterMs。0 = 不超时。
                socket.soTimeout = 0
                TlsLinkConnection(socket, host, port, peer)
            }.getOrNull()
        }

    /**
     * F1（task-39）：与 [connect] **同一套 TLS 身份 + 双向 pin**，但交出**原始字节流**。
     *
     * 为什么需要它：文件的数据阶段是裸流（`pull-ok` 之后不受帧上限约束），
     * [LinkConnection] 只有分帧的 send/receiveFrame —— 把裸流塞进分帧就是协议变更（方案 A），
     * 设计文档已否。跨网真正要的是"同一套 TLS 身份/pin 之下的原始字节流"。
     *
     * fail-closed 与 [connect] 逐条一致：pin 给了就必须匹配，对不上直接断开、**绝不降级明文**。
     */
    suspend fun connectRaw(host: String, port: Int, expectedPin: TlsPin?, timeoutMs: Long): RawPullConnection? =
        withContext(Dispatchers.IO) {
            runCatching {
                val context = clientContext()
                val socket = context.socketFactory.createSocket() as SSLSocket
                // **选网**（task-85 真身）：Android 只把**默认网络**的路由装进主表，于是
                // 默认网络是 WiFi、而那个 WiFi 的 v6 出不去时，发往公网 v6 的包无路可走 ——
                // 真机 `nc -6` 报 `Network is unreachable`（不是超时，是内核没有路由）。
                //
                // binder 在**建 socket 之后、connect 之前**回答"该绑哪张网"，这里只负责绑。
                // 默认实现（桌面 / iOS）返回 null ⇒ 一个字都不改，行为与以前完全一致。
                //
                // 绑失败不拦路：宁可用默认网络再失败一次，也不要因为选网出错而让整条路径挂掉。
                val binder = NetworkBinderHolder.current()
                val targetNetwork = runCatching { binder.networkFor(host) }.getOrNull()
                if (targetNetwork != null) {
                    runCatching { binder.bind(socket, targetNetwork) }
                }
                try {
                    socket.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                } catch (t: Throwable) {
                    error = "connect-failed"
                    throw t
                }
                socket.soTimeout = timeoutMs.toInt()
                try {
                    socket.startHandshake()
                } catch (t: Throwable) {
                    error = "tls-handshake-failed"
                    runCatching { socket.close() }
                    throw t
                }
                val peer = peerCertificatePin(socket)
                if (expectedPin != null && peer?.wire != expectedPin.wire) {
                    error = "pin-mismatch"
                    runCatching { socket.close() }
                    return@runCatching null
                }
                error = null
                // 握手超时只该管握手：裸流阶段一次空闲可能超过它（对端在算哈希、等用户点确认），
                // 真正的空闲判定在应用层（PullOptions.idleTimeoutMs = 60s）。0 = 不超时。
                socket.soTimeout = 0
                RawTlsConnection(socket, peer)
            }.getOrNull()
        }

    /**
     * F1（task-39）：在 [port] 上接受**原始字节流**的 TLS 连接（跨网文件拉取）。
     *
     * 与 [startServer] 的唯一区别是交出去的东西：[startServer] 给的是**分帧**的
     * [LinkConnection]（长连接用），这里给的是 [RawPullConnection]（裸字节流，文件的数据
     * 阶段要用）。TLS 服务端身份、双向固定、`needClientAuth` 与 fail-closed 纪律**同一套**，
     * 所以不另写一份监听逻辑。
     *
     * 裸流阶段的 `soTimeout` 归零：一次空闲可能超过握手超时（对端在算哈希、等用户点确认），
     * 空闲判定交给应用层（`PullOptions.idleTimeoutMs`）。
     *
     * @return 实际绑上的端口；**null = 起不来**（端口被占 / 身份不可用）⇒ 调用方按
     *         「这条路径不可用」处理并退回局域网原路径，**绝不降级明文**。
     */
    suspend fun startRawServer(
        port: Int,
        onConnection: suspend (RawPullConnection, remoteHost: String, remotePort: Int) -> Unit,
    ): Int? {
        rawServerJob?.let { return rawServerBoundPort }
        val context = runCatching { serverContext() }.getOrNull() ?: return null
        val bound = runCatching {
            (context.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
                needClientAuth = true
            }
        }.getOrNull() ?: return null
        rawServerSocket = bound
        rawServerBoundPort = bound.localPort
        rawServerJob = serverScope.launch {
            while (isActive) {
                val socket = runCatching { bound.accept() as SSLSocket }.getOrNull() ?: continue
                launch {
                    runCatching {
                        socket.startHandshake()
                        val peer = peerCertificatePin(socket)
                        socket.soTimeout = 0
                        val host = socket.inetAddress?.hostAddress ?: ""
                        onConnection(RawTlsConnection(socket, peer), host, socket.port)
                    }.onFailure { runCatching { socket.close() } }
                }
            }
        }
        return bound.localPort
    }

    /** 关掉跨网文件的原始监听（不动 [stopServer] 那套分帧监听）。 */
    suspend fun stopRawServer() {
        rawServerJob?.cancel()
        rawServerJob = null
        runCatching { rawServerSocket?.close() }
        rawServerSocket = null
        rawServerBoundPort = null
    }

    /** K2 用 [startServer] 那条路；这条（Ktor 通道交接）只服务 K1 的 17879。 */
    override suspend fun accept(
        socket: Socket,
        read: ByteReadChannel,
        write: ByteWriteChannel,
        remoteHost: String,
        remotePort: Int,
        expectedPin: TlsPin?,
    ): LinkConnection? = null

    override suspend fun startServer(port: Int, onConnection: suspend (LinkConnection) -> Unit): Boolean {
        if (serverJob != null) return true
        val context = runCatching { serverContext() }.getOrNull() ?: return false
        val bound = runCatching {
            (context.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
                needClientAuth = true
            }
        }.getOrNull() ?: return false
        serverSocket = bound
        boundServerPort = bound.localPort
        serverJob = serverScope.launch {
            while (isActive) {
                val socket = runCatching { bound.accept() as SSLSocket }.getOrNull() ?: continue
                launch {
                    runCatching {
                        socket.startHandshake()
                        val peer = peerCertificatePin(socket)
                        val host = socket.inetAddress?.hostAddress ?: ""
                        onConnection(TlsLinkConnection(socket, host, socket.port, peer))
                    }.onFailure { runCatching { socket.close() } }
                }
            }
        }
        return true
    }

    override suspend fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundServerPort = null
    }

    private fun clientContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(
            keyManagers(),
            arrayOf(permissiveTrustManager),
            SecureRandom(),
        )
    }

    private fun serverContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(
            keyManagers(),
            arrayOf(permissiveTrustManager),
            SecureRandom(),
        )
    }

    private fun keyManagers(): Array<javax.net.ssl.KeyManager> {
        val password = IDENTITY_PASSWORD
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry("identity", identity.keyPair.private, password, arrayOf(identity.certificate))
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, password) }
            .keyManagers
    }

    /**
     * 信任管理器**一律接受**，真正的判断在握手之后用 SPKI pin 做。
     *
     * 为什么不能交给默认信任管理器：自签证书链上没有任何可信 CA，默认实现会直接拒绝，
     * 于是永远走不到"比对 pin"那一步。接受 + 立刻 pin 校验是自签场景的标准做法 ——
     * 安全性来自 pin，不来自链。
     */
    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun peerCertificatePin(socket: SSLSocket): TlsPin? = runCatching {
        val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate ?: return null
        TlsPin.ofSpki(cert.publicKey.encoded)
    }.getOrNull()

    private companion object {
        val IDENTITY_PASSWORD = "relay-link".toCharArray()
    }
}

/** TLS 连接：4 字节大端长度前缀 + JSON，与明文/TCP 回退完全同形。 */
private class TlsLinkConnection(
    private val socket: SSLSocket,
    override val remoteHost: String,
    override val remotePort: Int,
    override val peerPin: TlsPin?,
) : LinkConnection {

    override val isSecure: Boolean = true

    private val writeLock = Mutex()

    override suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            socket.outputStream.write(frame)
            socket.outputStream.flush()
        }
    }

    override suspend fun receiveFrame(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val input = socket.inputStream
            val header = ByteArray(4)
            readFully(input, header)
            val len = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            if (len <= 0 || len > MAX_FRAME_BYTES) return@runCatching null
            val body = ByteArray(len)
            readFully(input, body)
            body
        }.getOrNull()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    /**
     * 读满 [buffer]；**只有真的读不动了才算断开**。
     *
     * 这是一层**防御纵深**，不是主要修复：真正的修复是 [TlsLinkTransport.connect] 里把
     * 活连接的 soTimeout 归零（单独撤掉它能复现真机缺陷，单独撤掉这里不能 —— 因为那时
     * socket 层已经不会再超时了）。
     *
     * 为什么仍然留着：只要读超时还是一种**可能**，它就会被 runCatching 折成 null，而 pump
     * 把 null 读成"对端关了这条连接" ⇒ 关连接、退避、重拨。流里没有"读超时"这回事，
     * 只有"还没来"，所以这里必须继续读，而不是上报断开。
     *
     * 反过来，read <= 0（EOF）与真正的 IO 异常才是断开，照旧上报 null。
     */
    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = try {
                input.read(buffer, offset, buffer.size - offset)
            } catch (_: SocketTimeoutException) {
                // 空闲，不是断开：继续等这一帧剩下的字节。
                continue
            }
            if (read <= 0) throw IllegalStateException("连接已关闭")
            offset += read
        }
    }

    private companion object {
        const val MAX_FRAME_BYTES: Int = 64 * 1024
    }
}

/**
 * 本机的自签身份：EC P-256 私钥 + SAN 含本机地址的 X.509 证书 + 它的 SPKI pin。
 *
 * 私钥与证书分别落盘（PKCS#8 / DER）。**续签只用同一把私钥重签**，所以 pin 不变 ——
 * 这正是 task-13 裁决的「自动续签」：地址变了、证书过期了，都不需要用户重新配对。
 */
internal class LocalTlsIdentity(
    val keyPair: KeyPair,
    val certificate: X509Certificate,
    val pin: TlsPin,
) {
    companion object {
        private const val KEY_FILE = "link-key.pk8"
        private const val PUBLIC_FILE = "link-pub.spki"
        private const val CERT_FILE = "link-cert.der"
        private const val RENEW_BEFORE_MS = 7L * 24 * 3600 * 1000

        fun ensure(dir: String, addresses: List<String>): LocalTlsIdentity {
            val directory = File(dir).apply { mkdirs() }
            val keyFile = File(directory, KEY_FILE)
            val publicKeyFile = File(directory, PUBLIC_FILE)
            val certFile = File(directory, CERT_FILE)
            val addresses = addresses.filter { it.isNotBlank() }.distinct()

            val existingCert = runCatching { loadCertificate(certFile) }.getOrNull()
            // 私钥与公钥**分开存**：只丢证书时仍能用同一把密钥重签 ⇒ pin 不变。
            // 公钥若从证书里取，证书一丢就等于换密钥 —— 那正是"续签要重新配对"的坑。
            val existingKey = runCatching { loadKey(keyFile, publicKeyFile) }.getOrNull()

            if (existingKey != null && existingCert != null &&
                existingCert.notAfter.time - System.currentTimeMillis() > RENEW_BEFORE_MS &&
                addresses.all { san -> existingCert.subjectAlternativeNames.orEmpty().any { entry -> entry[1].toString() == san } }
            ) {
                return LocalTlsIdentity(existingKey, existingCert, TlsPin.ofSpki(existingKey.public.encoded))
            }

            // 续签或首次签发：密钥能留就留（pin 因此不变）。
            val keyPair = existingKey ?: generateKey()
            val der = selfSignedCertificateDer(keyPair, addresses, System.currentTimeMillis())
            runCatching { keyFile.writeBytes(keyPair.private.encoded) }
            runCatching { publicKeyFile.writeBytes(keyPair.public.encoded) }
            runCatching { certFile.writeBytes(der) }
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
            return LocalTlsIdentity(keyPair, certificate, TlsPin.ofSpki(keyPair.public.encoded))
        }

        private fun loadKey(file: File, publicFile: File): KeyPair? {
            if (!file.isFile || !publicFile.isFile) return null
            val factory = KeyFactory.getInstance("EC")
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(file.readBytes()))
            val publicKey = factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
            return KeyPair(publicKey, privateKey)
        }

        private fun loadCertificate(file: File): X509Certificate? {
            if (!file.isFile) return null
            return CertificateFactory.getInstance("X.509").generateCertificate(file.inputStream()) as X509Certificate
        }

        private fun generateKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        // ---- 自签证书：ASN.1 那一半在 commonMain（[LinkCertificateDer]）----
        //
        // JVM 只提供平台相关的三样：公钥 SPKI、序列号随机源、SHA256withECDSA 签名。
        // 这样 iOS 与 JVM 用的是**同一份字节布局** —— 否则两套实现漂移的直接后果是
        // pin（SPKI）对不上，而对不上就是"配过对的设备突然连不上"。

        private fun selfSignedCertificateDer(keyPair: KeyPair, addresses: List<String>, now: Long): ByteArray =
            LinkCertificateDer.selfSigned(
                spki = keyPair.public.encoded,
                serial = ByteArray(16).also { SecureRandom().nextBytes(it) },
                // 优先用**共享的纯 Kotlin 解析**（与 iOS 逐字节一致）；它不认主机名，
                // 于是回退到平台解析 —— 真机给的 localAddresses 都是字面 IP，回退只是兜底。
                sanIps = addresses.mapNotNull { address ->
                    LinkCertificateDer.ipBytes(address)
                        ?: runCatching { java.net.InetAddress.getByName(address).address }.getOrNull()
                },
                notBeforeMillis = now - 300_000L,
                notAfterMillis = now + 365L * 86_400_000L,
                sign = { tbs ->
                    Signature.getInstance("SHA256withECDSA").apply {
                        initSign(keyPair.private)
                        update(tbs)
                    }.sign()
                },
            )
    }
}
