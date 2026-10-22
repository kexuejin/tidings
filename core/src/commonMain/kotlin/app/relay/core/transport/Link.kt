// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.createCoreLock
import app.relay.core.crypto.TlsPin
import app.relay.core.ioDispatcher
import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.IpAddressUtils
import app.relay.core.protocol.LinkFrame
import app.relay.core.protocol.LinkFrames
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.TcpFrame
import app.relay.core.protocol.TcpFrameDecode
import app.relay.core.protocol.TcpFrameTypes
import app.relay.core.protocol.TcpFrames
import app.relay.core.withLock
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlinx.serialization.builtins.serializer

/**
 * 长连接（K1）：手机 → 主机的一条 TCP 连接，主机经它下行文本。
 *
 * 设计见 docs/cross-network-keepalive-design.md。三条边界写死在代码里：
 *  · **K1 明文只允许局域网**（[IpAddressUtils.isLanAddress]：私网 ∨ 本机同 /64 ∨ link-local/ULA ——
 *    家庭 LAN 的 IPv6 常是 GUA，**GUA ≠ 公网可达**）；公网等 K2 的 TLS；
 *  · **不做多路复用**：这条连接只跑控制与文本，文件/屏幕仍走原路径；
 *  · **连接层可插拔**：[LinkTransport] 是 K2 的接缝，TLS + 双向 pin 换一个实现即可，
 *    pin 校验的入参就是 task-13 的 [TlsPin]。
 */

/** 一条已经建立的长连接：字节进、字节出。K1 明文，K2 换成 TLS —— 同一个接口。 */
interface LinkConnection {
    val remoteHost: String
    val remotePort: Int
    val isSecure: Boolean

    /**
     * 这条连接**确定是明文**吗？（task-40 真机）
     *
     * 默认 **false**，只有明文实现才置 true。为什么要有这个正向标记：task-32 的地址复查
     * （[LinkServer]/[LinkClient] 的 enforcePlaintextPolicy）原本按 `!isSecure` 判断"是不是明文"，
     * 真机实测**TLS 连接也被它摘掉了**（两端 close=recheck，每摘一次手机就重拨一次，linkConnected
     * 周期性跳）。与其继续猜某个路径上 `isSecure` 为什么不是 true，不如把判据换成**正向事实**：
     * 只有"这条连接自己声明是明文"才参与地址复查；取不到/不认识一律**不摘**（保守方向）。
     *
     * 注意与接受时的裁决（[linkDenyReason]）分工不同：接受时按 `!isSecure` 是**fail-closed**
     * （认不出安全就不许从公网明文接进来），复查时按 [isPlaintext] 是**fail-safe**（认不出就别摘）。
     */
    val isPlaintext: Boolean get() = false

    /**
     * 对端证书的 SPKI pin（K2 双向固定用）。明文连接是 null。
     *
     * 为什么放在连接上：握手发生在 [LinkTransport.accept] 里，而对端的 nodeId 要等首帧
     * hello 才知道 —— 所以"这条连接来自谁、它的 pin 是多少"只能由连接自己带上来，
     * 再由 [LinkServer] 拿 registry 里存的 pin 去比对（fail-closed）。
     */
    val peerPin: TlsPin? get() = null

    /** 写一帧**已经分好帧**的字节（[LinkFrames.encode] 或 msg 帧）。 */
    suspend fun send(frame: ByteArray)

    /** 读一帧的**帧体**（长度前缀已剥掉）；连接结束/出错返回 null。 */
    suspend fun receiveFrame(): ByteArray?

    fun close()
}

/**
 * 连接层工厂 —— **K2 的接缝**。
 *
 * K1 只有 [PlaintextLinkTransport]。K2 的 TLS 实现要做的事：connect 时完成 TLS 握手并用
 * [expectedPin] 校验对端证书的 SPKI；accept 时作为 TLS 服务端出示自己的证书、并校验对端
 * （双向固定）。[isSecure] 用于「明文只允许局域网」这条策略判断。
 */
interface LinkTransport {
    val isSecure: Boolean

    suspend fun connect(host: String, port: Int, expectedPin: TlsPin?, timeoutMs: Long): LinkConnection?

    suspend fun accept(
        socket: Socket,
        read: ByteReadChannel,
        write: ByteWriteChannel,
        remoteHost: String,
        remotePort: Int,
        expectedPin: TlsPin?,
    ): LinkConnection?

    /**
     * 平台能不能**作为 TLS 服务端**在独立端口上监听（K2）。
     *
     * 为什么不在 [accept] 里做：那条路把 Ktor 已经打开的通道交进来（K1 的 17879），而 TLS
     * 需要先握手才能拿到通道 —— 它必须在自己的监听器上 accept，再交给 [LinkServer]。
     * 明文实现返回 false ⇒ 只有局域网能建连（公网被拒，fail-closed）。
     */
    val canServeTls: Boolean get() = false

    /**
     * 本机身份的 TLS pin（SPKI 指纹）—— 没有身份/明文实现返回 null（task-38）。
     *
     * 用途：把本机的 pin **随资料广播出去**，让对端在配对时就固定正确的 pin，
     * 而不是靠 TOFU 首次固定（那个有"攻击者抢先声明同一 nodeId"的竞态）。
     * pin 是公钥指纹，公开无妨（它进二维码本来就是公开的）。
     */
    val localPin: TlsPin? get() = null

    /**
     * 本机身份证书的**到期时刻**（毫秒）。没有身份 / 明文实现返回 null。
     *
     * 为什么要暴露它：证书会自动续签、密钥保留，所以**配对关系不会因为到期而断** ——
     * 但「快到期 / 已续签」这件事从来不告诉用户。真出问题时只表现为「突然连不上」，
     * 而那时用户没有任何可看的读数。这个字段就是那个读数。
     *
     * 只读：续签是自动的，界面上不提供手动重新签发的入口（轮换意味着所有已配对设备
     * 要重新配对，代价大于收益）。
     */
    val localCertNotAfterMillis: Long? get() = null

    /**
     * 在 [port] 上接受 TLS 长连接；每条**已完成握手**的连接交给 [onConnection]。
     *
     * @return true = 监听已开始；false = 这个平台/端口起不来（调用方按"没有 TLS"处理）。
     */
    suspend fun startServer(port: Int, onConnection: suspend (LinkConnection) -> Unit): Boolean = false

    suspend fun stopServer() {}

    /**
     * 最近一次 [connect] 失败的原因（**只读诊断**，task-32 ③）。
     *
     * 并发拨号时可能被互相覆盖 —— 它只用来回答"拨了为什么连不上"，不参与任何判定。
     * null = 上次成功、或还没拨过。
     */
    val lastError: String? get() = null
}

/** K1 的明文实现：直接把已打开的通道包一层，不做任何加密。 */
class PlaintextLinkTransport : LinkTransport {

    override val isSecure: Boolean = false

    @Volatile
    private var error: String? = null

    override val lastError: String? get() = error

    override suspend fun connect(host: String, port: Int, expectedPin: TlsPin?, timeoutMs: Long): LinkConnection? =
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                val selector = SelectorManager(ioDispatcher)
                val socket = aSocket(selector).tcp().connect(InetSocketAddress(host, port))
                PlaintextLinkConnection(
                    socket = socket,
                    read = socket.openReadChannel(),
                    write = socket.openWriteChannel(autoFlush = true),
                    remoteHost = host,
                    remotePort = port,
                )
            }.onSuccess { error = null }
                .onFailure { error = "connect-failed" }
                .getOrNull()
        } ?: run { error = "connect-timeout"; null }

    override suspend fun accept(
        socket: Socket,
        read: ByteReadChannel,
        write: ByteWriteChannel,
        remoteHost: String,
        remotePort: Int,
        expectedPin: TlsPin?,
    ): LinkConnection = PlaintextLinkConnection(socket, read, write, remoteHost, remotePort)
}

/** 明文连接：4 字节大端长度前缀 + JSON，与 TCP 回退完全同形。 */
private class PlaintextLinkConnection(
    private val socket: Socket,
    private val read: ByteReadChannel,
    private val write: ByteWriteChannel,
    override val remoteHost: String,
    override val remotePort: Int,
) : LinkConnection {

    override val isSecure: Boolean = false

    /** 明文实现：**唯一**会把自己标成明文的连接类型（见 [LinkConnection.isPlaintext]）。 */
    override val isPlaintext: Boolean = true

    private val writeLock = Mutex()

    override suspend fun send(frame: ByteArray) {
        writeLock.withLock { write.writeFully(frame) }
    }

    override suspend fun receiveFrame(): ByteArray? {
        val header = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES)
        return runCatching {
            read.readFully(header, 0, header.size)
            val len = TcpFrame.decodeLength(header)
            if (len <= 0 || len > MAX_FRAME_BYTES) return null
            val body = ByteArray(len)
            read.readFully(body, 0, len)
            body
        }.getOrNull()
    }

    override fun close() {
        // 关 socket 而不是关写通道：ktor 3 的 ByteWriteChannel 没有非挂起的 close()，
        // 而 socket.close() 会把读写两条通道一起收掉（与 TcpFramedChannel 同一条纪律）。
        runCatching { socket.close() }
    }

    private companion object {
        /** 与 TCP 回退同一个上限（TS MAX_FRAME = 64 KiB）。 */
        const val MAX_FRAME_BYTES: Int = 64 * 1024
    }
}

/**
 * 主机侧：接受并持有下行连接。
 *
 * 鉴权是硬要求（文档 §6）：不能凭「谁连上来」就信任 —— 只有 [isTrusted] 认可的 nodeId
 * 才接受，否则任意人都能占用某台手机的下行通道。K1 的默认判据是「registry 里已知」
 * （握过手），宿主可以传入更严的授信列表（K2 再收紧）。
 */
interface LinkDownlink {
    fun isConnected(peerId: String): Boolean

    /** 下行一条信封并等 msg-ack；false = 这条连接不可用（调用方立即退回 UDP/TCP）。 */
    suspend fun send(peerId: String, envelope: Envelope): Boolean
}

class LinkServer(
    private val registry: PeerRegistry,
    /**
     * **K1 交接路径**用的连接层：那条路永远来自 17879 的明文监听器（[TransferServer]），
     * 所以这里固定是明文实现 —— 与"公网要不要 TLS"是两件事。TLS 的连接由
     * [LinkTransport.startServer] 直接交给 [handle]。
     */
    private val acceptTransport: LinkTransport = PlaintextLinkTransport(),
    private val scope: CoroutineScope,
    /** 授信判据；默认「registry 里已知」。 */
    private val isTrusted: (String) -> Boolean = { false },
    /**
     * 首次固定（TOFU）学到对端 pin 时的去处 —— 宿主把它落盘，重启后仍然认得这台设备。
     *
     * 为什么需要：二维码是**主机→手机单向**的，手机扫完只拿到**主机**的 pin；主机要验手机的
     * 客户端证书，却从来没有过手机的 pin（task-36 真机：手机侧 attempts=22/failures=20、
     * lastError=hello-rejected，就是被这条 pin-missing 拒的）。
     */
    private val onPinLearned: (String, String) -> Unit = { _, _ -> },
    /**
     * 本机当前地址（task-40）：地址复查要用它判"对端是不是同一段局域网"。
     *
     * 家庭 LAN 的 IPv6 常是 GUA（真机 `2001:db8:2020:cb44::/64`），光看地址分类会当成公网；
     * 与本机同 /64 才算局域网。取不到（空表）时不做同前缀推断（保守）。
     */
    private val localAddresses: () -> List<String> = { emptyList() },
    /** 这条连接上收到的 `msg` 帧的去处（与 TCP 回退同一条投递纪律：去重 + 投递）。 */
    private val receiveMessage: suspend (Envelope, String) -> Boolean = { _, _ -> false },
    private val heartbeatMs: Long = DEFAULT_HEARTBEAT_MS,
    private val deadAfterMs: Long = DEFAULT_DEAD_AFTER_MS,
    private val ackTimeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS,
) : LinkDownlink {

    private val lock = createCoreLock()
    private val links = HashMap<String, LinkConnection>()

    /** key = "peerId|msgId"。 */
    private val pendingAcks = HashMap<String, CompletableDeferred<Boolean>>()

    /** nodeId → 最近一次拒绝这条长连接的原因（只读诊断，task-36 ①）。 */
    private val denyReasons = HashMap<String, String>()

    /**
     * nodeId → 最近一次**连接结束**的原因（只读诊断，task-40 ①）。
     *
     * `dead-timeout` = 本端 45s 没收到任何字节；`peer-closed` = 对端关了这条连接。
     * 与 [closeCounts] 配合就能回答真机上最关键的那个问题：**谁先关的**。
     */
    private val closeReasons = HashMap<String, String>()

    /** nodeId → 最近一次"我们主动关"的**判据两元组**（task-40：isPlaintext / ip / local）。 */
    private val closeDetails = HashMap<String, String>()

    /** nodeId → **本端主动**关掉连接的次数（replaced / recheck / stopped）。 */
    private val closeCounts = HashMap<String, Int>()

    override fun isConnected(peerId: String): Boolean = lock.withLock { links.containsKey(peerId) }

    init {
        // 地址一变（换网）就**重新裁决**：公网对端上不许留明文长连接（task-13 裁决）。
        //
        // 真机现场（task-32）：手机从 WiFi 切到移动网后，主机仍把那条 **WiFi 期**建立的明文连接
        // 挂在 links 里（对端地址此时已经是运营商地址），诊断于是显示 linkCarrier=plaintext，
        // 一直到 45s 判死才消失。也就是说"公网对端有一条明文通道"这件事在这段窗口里是成立的 ——
        // 判定只在 accept 那一刻做过，地址后来变了却没人复查。
        registry.addListener { event ->
            if (event is PeerEvent.Updated) enforcePlaintextPolicy(event.record)
        }
    }

    /** 对端地址已经是公网时，立刻关掉并摘掉它那条明文连接。 */
    private fun enforcePlaintextPolicy(record: PeerRecord) {
        val nodeId = record.profile.nodeId
        val connection = lock.withLock {
            val current = links[nodeId] ?: return
            // **只**摘"自己声明是明文"的连接（task-40 真机：TLS 连接曾被这里摘掉，
            // 两端 close=recheck、每摘一次手机就重拨一次）。取不到明文标记一律**不摘**。
            val locals = localAddresses()
            if (!plaintextLinkForbidden(current.isPlaintext, record.ip, locals)) return
            closeDetails[nodeId] = "isPlaintext=" + current.isPlaintext + " ip=" + record.ip +
                " local=" + locals.joinToString(",")
            links.remove(nodeId)
            current
        }
        recordLocalClose(nodeId)
        recordClose(nodeId, CLOSE_RECHECK)
        runCatching { connection.close() }
    }

    fun connectedPeers(): Set<String> = lock.withLock { links.keys.toSet() }

    /** 记一次连接结束（只读诊断，task-40 ①）。 */
    private fun recordClose(nodeId: String, reason: String) {
        lock.withLock { closeReasons[nodeId] = reason }
    }

    /**
     * 泵自己醒来时写的原因（dead-timeout / peer-closed）—— **不覆盖**我们主动关的原因
     * （replaced / recheck / stopped）：旧连接的泵是被我们 close 之后才醒的，覆盖会把
     * "谁先关的"指错。
     */
    private fun recordPumpClose(nodeId: String, reason: String) {
        lock.withLock {
            val existing = closeReasons[nodeId]
            if (existing != CLOSE_REPLACED && existing != CLOSE_RECHECK && existing != CLOSE_STOPPED) {
                closeReasons[nodeId] = reason
            }
        }
    }

    /** 记一次**本端主动**关闭（replaced / recheck / stopped）。 */
    private fun recordLocalClose(nodeId: String) {
        lock.withLock { closeCounts[nodeId] = (closeCounts[nodeId] ?: 0) + 1 }
    }

    /** 只读诊断：nodeId → 最近一次连接结束的原因（`dead-timeout` / `peer-closed`）。 */
    fun closeReasons(): Map<String, String> = lock.withLock { closeReasons.toMap() }

    /** 只读诊断：nodeId → 本端主动关闭次数（replaced / recheck / stopped）。 */
    fun closeCounts(): Map<String, Int> = lock.withLock { closeCounts.toMap() }

    /** 只读诊断：nodeId → 最近一次主动关闭的判据两元组（task-40）。 */
    fun closeDetails(): Map<String, String> = lock.withLock { closeDetails.toMap() }

    /**
     * 只读诊断：每个 nodeId 最近一次被拒的原因（task-36 ①）。
     *
     * 取值与**判定**同源（[linkDenyDiagnostic]）：`unknown-peer` / `bad-version` / `not-lan` /
     * `pin-missing` / `pin-mismatch`。没有它，手机侧只能看到笼统的 `hello-rejected`，
     * 分不清是"没存 pin""pin 不匹配"还是"根本不认识这台设备"。
     */
    fun denyReasons(): Map<String, String> = lock.withLock { denyReasons.toMap() }

    /**
     * 只读诊断：当前活着的长连接及其**承载类型**（`"tls"` / `"plaintext"`）。
     *
     * 存在的理由（task-26 ③）："下行到底走的哪条路"必须可观测，否则只能靠临时挡网络去猜。
     * 只读、不触发任何动作、不改协议。
     */
    fun linkCarriers(): Map<String, String> = lock.withLock {
        links.mapValues { (_, connection) -> if (connection.isSecure) "tls" else "plaintext" }
    }

    /** 停机：关掉所有连接（pump 会因为读到 null 自行退出）。 */
    fun close() {
        val closing = lock.withLock {
            val all = links.values.toList()
            links.clear()
            all
        }
        for (connection in closing) runCatching { connection.close() }
    }

    /**
     * TCP 监听器的首帧是 link 时调用（与 screen-open 同一条交接路径）。
     *
     * @return true = 这条 socket 的所有权已交给长连接（[TransferServer] 不得再关它）。
     */
    suspend fun open(
        socket: Socket,
        hello: LinkFrame.Hello,
        remoteHost: String,
        remotePort: Int,
        read: ByteReadChannel,
        write: ByteWriteChannel,
    ): Boolean {
        val connection = runCatching {
            acceptTransport.accept(socket, read, write, remoteHost, remotePort, null)
        }.getOrNull() ?: return false
        return register(connection, hello)
    }

    /**
     * 平台 TLS 监听器（[LinkTransport.startServer]）把一条**已握手**的连接交上来。
     *
     * 与 [open] 的差别只有一处：首帧 hello 要由这里自己读 —— TLS 的监听器在 accept 时
     * 还没有任何帧，而 K1 那条路是 [TransferServer] 先读到了 hello 才交过来的。
     */
    suspend fun handle(connection: LinkConnection) {
        val body = withTimeoutOrNull(HELLO_TIMEOUT_MS) { connection.receiveFrame() }
        val frame = body?.let { TcpFrames.decodeBody(it.decodeToString()) }
        val hello = (frame as? TcpFrameDecode.Link)?.frame as? LinkFrame.Hello
        if (hello == null || !register(connection, hello)) {
            runCatching { connection.close() }
        }
    }

    /** 鉴权、策略、接管 —— [open] 与 [handle] 共用这一段。 */
    private suspend fun register(connection: LinkConnection, hello: LinkFrame.Hello): Boolean {
        // 判据用**这条连接自己**的 isSecure / remoteHost，不是服务的连接层 —— 同一台主机上，
        // 17879 的明文与 tlsPort 的 TLS 是两条并存的路。裁决表见 [linkDenyReason]。
        val known = registry.get(hello.nodeId) != null || isTrusted(hello.nodeId)
        val storedPin = registry.pinOf(hello.nodeId)
        val diagnostic = linkDenyDiagnostic(
            hello = hello,
            remoteHost = connection.remoteHost,
            isSecure = connection.isSecure,
            known = known,
            storedPin = storedPin,
            peerPin = connection.peerPin,
            // task-53：接受侧与拨号/复查**共用同一条局域网判据**（同 /64 的 GUA 也算局域网）。
            localAddresses = localAddresses(),
        )
        // **首次固定（TOFU）**：主机侧没有手机的 pin 是常态 —— 二维码是主机→手机单向的，
        // 手机扫完只拿到主机的 pin（task-36 真机：手机 attempts=22/failures=20、
        // lastError=hello-rejected，就是被 pin-missing 拒的）。
        // 这里把对端**客户端证书**的 SPKI 固定下来；此后必须一致（pin-mismatch ⇒ 拒绝）。
        // 这是 fail-closed 的"首次固定"，不是"不验"：没有证书（peerPin=null）照样拒。
        // peerPin 是接口属性（自定义 getter），必须落到局部变量才能安全判空。
        val peerPin = connection.peerPin
        if (diagnostic == DENY_PIN_MISSING && known && peerPin != null) {
            // **来源标成 tofu**（task-38 底线③）：这是降级路径 —— 首次见到谁就用谁的证书，
            // 存在"攻击者抢在真手机之前声明同一 nodeId"的竞态。让它在诊断里看得见。
            registry.setPin(hello.nodeId, peerPin.wire, PeerRegistry.PIN_SOURCE_TOFU)
            runCatching { onPinLearned(hello.nodeId, peerPin.wire) }
            lock.withLock { denyReasons.remove(hello.nodeId) }
        } else if (diagnostic != null) {
            lock.withLock { denyReasons[hello.nodeId] = diagnostic }
            runCatching {
                connection.send(LinkFrames.encode(LinkFrame.Deny(linkDenyReasonFor(diagnostic))))
            }
            return false
        }

        // 同 nodeId 重复连接：保留最新、关闭旧的（避免两条路同时下行造成重复）。
        val previous = lock.withLock { links.put(hello.nodeId, connection) }
        if (previous != null) {
            recordLocalClose(hello.nodeId)
            recordClose(hello.nodeId, CLOSE_REPLACED)
            runCatching { previous.close() }
        }

        // 收益最大的一步：连接的对端地址就是这台手机现在可达的地址。
        registry.setLinkedAddress(hello.nodeId, connection.remoteHost)

        runCatching { connection.send(LinkFrames.encode(LinkFrame.Ok)) }
        scope.launch { pump(hello.nodeId, connection) }
        return true
    }

    private suspend fun pump(nodeId: String, connection: LinkConnection) {
        // 与客户端侧同一条：**有数据免心跳**（这一轮刚收发过就跳过这次 Ping）。
        var lastActivity = app.relay.core.nowMillis()
        var lastPingSent = 0L
        val heartbeat = scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                val now = app.relay.core.nowMillis()
                // 与客户端侧同一条：免心跳 + **保底**（距上次 Ping ≥ 2 轮必发）。
                if (now - lastActivity < heartbeatMs && now - lastPingSent < heartbeatMs * 2) continue
                runCatching { connection.send(LinkFrames.encode(LinkFrame.Ping)) }
                lastPingSent = now
            }
        }
        try {
            while (true) {
                // 45s 没有任何字节 = 判死。写成功不代表对方活着（NAT 回收后的半开连接）。
                // 用 closedByPeer 把"对端关了"与"判死超时"分开 —— task-40 的现场就靠这个区分。
                var closedByPeer = false
                val body = withTimeoutOrNull(deadAfterMs) {
                    val frame = connection.receiveFrame()
                    if (frame == null) closedByPeer = true
                    frame
                }
                if (body == null) {
                    // 只有"没被我们主动关过"才写原因：否则会把 replaced/recheck/stopped 覆盖成
                    // peer-closed（旧连接的 pump 是被我们 close 之后才醒的），诊断就指错了人。
                    recordPumpClose(nodeId, if (closedByPeer) CLOSE_PEER_CLOSED else CLOSE_DEAD_TIMEOUT)
                    break
                }
                lastActivity = app.relay.core.nowMillis()
                when (val decoded = TcpFrames.decodeBody(body.decodeToString())) {
                    is TcpFrameDecode.Link -> if (decoded.frame == LinkFrame.Ping) {
                        runCatching { connection.send(LinkFrames.encode(LinkFrame.Pong)) }
                        // 同上（服务端那段 KDoc 写了完整死锁）：心跳到了就让节点回到在线。
                        registry.setLinkedAddress(nodeId, connection.remoteHost)
                    }

                    is TcpFrameDecode.MsgAck -> settleAck(nodeId, decoded.ackFor)

                    is TcpFrameDecode.Msg -> {
                        // 投递前**重新采纳**连接的对端地址：UDP 侧的 presence 可能把 registry 里的
                        // ip 改回另一个地址，而 acceptTcpMessage 会把"地址对不上"的帧当漂移丢掉。
                        // 长连接是刚证过的可达路径，它的对端地址优先（K1 报告里标痕的那条）。
                        registry.setLinkedAddress(nodeId, connection.remoteHost)
                        val ok = runCatching { receiveMessage(decoded.envelope, connection.remoteHost) }
                            .getOrDefault(false)
                        runCatching { connection.send(msgAckFrameBytes(decoded.envelope.id)) }
                        if (!ok) Unit
                    }

                    else -> Unit
                }
            }
        } finally {
            heartbeat.cancel()
            lock.withLock { if (links[nodeId] === connection) links.remove(nodeId) }
            runCatching { connection.close() }
        }
    }

    private fun settleAck(peerId: String, ackFor: String) {
        val deferred = lock.withLock { pendingAcks.remove(peerId + "|" + ackFor) }
        deferred?.complete(true)
    }

    override suspend fun send(peerId: String, envelope: Envelope): Boolean {
        val connection = lock.withLock { links[peerId] } ?: return false
        val key = peerId + "|" + envelope.id
        val deferred = CompletableDeferred<Boolean>()
        lock.withLock { pendingAcks[key] = deferred }
        return try {
            if (runCatching { connection.send(msgFrameBytes(envelope)) }.isFailure) return false
            withTimeoutOrNull(ackTimeoutMs) { deferred.await() } == true
        } finally {
            lock.withLock { pendingAcks.remove(key) }
        }
    }

    companion object {
        const val DEFAULT_HEARTBEAT_MS: Long = 15_000
        const val DEFAULT_DEAD_AFTER_MS: Long = 45_000
        const val DEFAULT_ACK_TIMEOUT_MS: Long = 5_000

        /** TLS 监听器交上来的连接：等首帧 hello 的上限（对端连上却不说话 = 丢弃）。 */
        const val HELLO_TIMEOUT_MS: Long = 5_000
    }
}

/**
 * 发起侧：对每个「声明了 lnk1、且地址在局域网内」的对端维持一条长连接。
 *
 * 退避 1s..60s ±20% 抖动；网络切换/对端重启都会表现为连接断开 → 重新走退避。
 * 只连**局域网地址**：公网要等 K2（TLS + 双向 pin）——这是 lead 裁决的 K1 边界。
 */
class LinkClient(
    private val registry: PeerRegistry,
    /** 平台连接层（K2：JVM/Android 是 TLS）。 */
    private val transport: LinkTransport,
    /** 局域网明文路径 —— 与 [transport] 并存：公网走 TLS，私网可以继续走明文（K1 不失效）。 */
    private val plaintextTransport: LinkTransport = PlaintextLinkTransport(),
    private val scope: CoroutineScope,
    private val selfNodeId: () -> String,
    /** 开关（默认关）。关掉后不建立任何连接，行为回到今天的 UDP+TCP+队列。 */
    private val enabled: () -> Boolean,
    /**
     * 设备此刻是否处于 **Doze/低电耗** 窗口（K3 降频用）。默认 `{ false }` —— **不改变既有行为**。
     *
     * 为什么要它：Doze 里唤醒会掉电且可能被系统拒绝；此时降频（而不是停发）比"硬发"更省，
     * 也让"有数据免心跳"和"空闲才 Ping"能按同一套判据收敛。
     */
    private val idleNow: () -> Boolean = { false },
    /** 本机当前地址（task-40）：与主机侧同一条用途（判对端是否同段局域网）。 */
    private val localAddresses: () -> List<String> = { emptyList() },
    /** 这条连接上收到的 `msg` 帧的去处（手机侧的下行文本）。 */
    private val onMessage: suspend (Envelope, String) -> Boolean = { _, _ -> false },
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val connectTimeoutMs: Long = 5_000,
    private val heartbeatMs: Long = LinkServer.DEFAULT_HEARTBEAT_MS,
    private val deadAfterMs: Long = LinkServer.DEFAULT_DEAD_AFTER_MS,
) {

    private val lock = createCoreLock()
    private val links = HashMap<String, LinkConnection>()

    /** nodeId → 下一次允许重连的时刻（退避）。 */
    private val nextAttemptAt = HashMap<String, Long>()
    private val attemptCount = HashMap<String, Int>()
    private val connecting = HashSet<String>()

    /** nodeId → 最近一次连接结束的原因（只读诊断，task-40 ①）。 */
    private val closeReasons = HashMap<String, String>()

    /** nodeId → 最近一次"我们主动关"的判据两元组（isPlaintext / ip / local）。 */
    private val closeDetails = HashMap<String, String>()

    /** nodeId → 本端主动关掉连接的次数（recheck / stopped）。 */
    private val closeCounts = HashMap<String, Int>()

    // ---- 拨号诊断（只读，task-32 ③）：回答"没拨"还是"拨了连不上" ----
    private val dialAttempts = HashMap<String, Int>()
    private val dialFailures = HashMap<String, Int>()
    private val dialLastTarget = HashMap<String, String>()
    private val dialLastError = HashMap<String, String?>()
    private val dialSkipReason = HashMap<String, String>()

    private var job: Job? = null

    fun isConnected(peerId: String): Boolean = lock.withLock { links.containsKey(peerId) }

    fun connectedPeers(): Set<String> = lock.withLock { links.keys.toSet() }

    init {
        // 与主机侧同一条不变量：对端地址变成公网之后，本端也不许再挂着一条明文连接（task-32）。
        registry.addListener { event ->
            if (event is PeerEvent.Updated) enforcePlaintextPolicy(event.record)
        }
    }

    private fun enforcePlaintextPolicy(record: PeerRecord) {
        val nodeId = record.profile.nodeId
        val connection = lock.withLock {
            val current = links[nodeId] ?: return
            // **只**摘"自己声明是明文"的连接（task-40 真机：TLS 连接曾被这里摘掉，
            // 两端 close=recheck、每摘一次手机就重拨一次）。取不到明文标记一律**不摘**。
            val locals = localAddresses()
            if (!plaintextLinkForbidden(current.isPlaintext, record.ip, locals)) return
            closeDetails[nodeId] = "isPlaintext=" + current.isPlaintext + " ip=" + record.ip +
                " local=" + locals.joinToString(",")
            links.remove(nodeId)
            current
        }
        recordLocalClose(nodeId)
        recordClose(nodeId, CLOSE_RECHECK)
        runCatching { connection.close() }
    }

    /** 只读诊断：本端主动维持的长连接及其承载类型（见 [LinkServer.linkCarriers]）。 */
    fun linkCarriers(): Map<String, String> = lock.withLock {
        links.mapValues { (_, connection) -> if (connection.isSecure) "tls" else "plaintext" }
    }

    /**
     * 只读诊断：每个**考虑过**的对端的拨号统计（含"为什么没拨"）。
     *
     * 真机现场（task-32 ③）：`linkConnected=false` 到底是"策略跳过、压根没拨"还是"拨了连不上"，
     * 光看连接状态分不清。这里把两者分开：`attempts=0` + `skipReason` = 没拨；
     * `attempts>0` + `failures>0` + `lastError` = 拨了但失败。
     */
    fun dialStats(): Map<String, LinkDialStats> = lock.withLock {
        val peers = HashSet<String>()
        peers += dialAttempts.keys
        peers += dialSkipReason.keys
        peers.associateWith { peerId ->
            LinkDialStats(
                attempts = dialAttempts[peerId] ?: 0,
                failures = dialFailures[peerId] ?: 0,
                lastTarget = dialLastTarget[peerId],
                lastError = dialLastError[peerId],
                skipReason = dialSkipReason[peerId],
            )
        }
    }

    /**
     * 立刻允许重拨**所有**对端：清掉退避表（`nextAttemptAt`）。
     *
     * 为什么必须有（K3）：换网时现在只做 UI 快照 + UDP 重播，**没人清长连接的退避** ——
     * 而退避上限是 60 秒，于是"换网后 ≤10s 恢复"根本做不到。网络变化信号到达时调用它，
     * 下一个 tick（≤2s）就会重新拨号。
     *
     * 语义：只清退避，不主动发起拨号、不重置已建立的连接（那些由 pump/断线逻辑管）。
     */
    fun requestImmediateDial() {
        lock.withLock { nextAttemptAt.clear() }
    }

    /**
     * **立刻**为某个对端拨一次，并等出结果（task-64）。
     *
     * 与 [requestImmediateDial] 的分工：那个只清退避、真正建连等下一个 tick（≤2s）；
     * 这个**当场拨**并返回是否连上 —— 因为调用方（可靠发送）正卡在"这条消息怎么送出去"上，
     * 等不了下一个 tick。
     *
     * 为什么需要它：真机实测蜂窝侧 **17879（明文 TCP 回退）不可达**、而 **17880（TLS 长连接）可达**，
     * 于是「链路半死」时唯一可能成功的路是**重拨一条新长连接**，而不是回退 17879。
     *
     * 复用 tick 那条路径（[linkTargets] + [connectOnce]）⇒ 拨号目标顺序、TLS pin 校验、退避记账
     * 全都与常规拨号逐字一致；失败时 [connectOnce] 自己会记退避（诚实，不假装）。
     *
     * @return true = 这条链路现在可用。
     */
    suspend fun redialNow(peerId: String): Boolean {
        if (isConnected(peerId)) return true
        val record = registry.get(peerId) ?: return false
        val targets = linkTargets(record)
        if (targets.isEmpty()) return false
        val busy = lock.withLock {
            if (peerId in connecting) {
                true
            } else {
                connecting += peerId
                // 这次是「现在就拨」：清掉可能压着的退避，否则 connectOnce 会先退避再试。
                nextAttemptAt.remove(peerId)
                false
            }
        }
        if (busy) return false
        connectOnce(peerId, targets)
        return isConnected(peerId)
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(tickMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        val closing = lock.withLock {
            // **必须是副本（Pair），不能是 `entries.toList()`**：Kotlin/Native 的 `Map.Entry`
            // 是**指向底层 map 的视图**，下面 `links.clear()` 之后再去读 key/value 就会抛
            // `ConcurrentModificationException: The backing map has been modified after this
            // entry was obtained`（iOS 模拟器测试 3/3 复现；JVM 的 HashMap 迭代宽松，所以只
            // 在 Kotlin/Native 暴露）。`.map { it.key to it.value }` 取的是**值引用**，clear 之后仍然有效。
            val all = links.map { (peerId, connection) -> peerId to connection }
            links.clear()
            all
        }
        for ((peerId, connection) in closing) {
            lock.withLock { closeCounts[peerId] = (closeCounts[peerId] ?: 0) + 1 }
            runCatching { connection.close() }
        }
    }

    /** 记一次连接结束（只读诊断，task-40 ①）。 */
    private fun recordClose(peerId: String, reason: String) {
        lock.withLock { closeReasons[peerId] = reason }
    }

    /** 泵自己醒来时写的原因；**不覆盖**我们主动关的原因（见主机侧同名函数）。 */
    private fun recordPumpClose(peerId: String, reason: String) {
        lock.withLock {
            val existing = closeReasons[peerId]
            if (existing != CLOSE_REPLACED && existing != CLOSE_RECHECK && existing != CLOSE_STOPPED) {
                closeReasons[peerId] = reason
            }
        }
    }

    /** 记一次**本端主动**关闭（recheck / stopped）。 */
    private fun recordLocalClose(peerId: String) {
        lock.withLock { closeCounts[peerId] = (closeCounts[peerId] ?: 0) + 1 }
    }

    /** 只读诊断：nodeId → 最近一次连接结束的原因（dead-timeout / peer-closed）。 */
    fun closeReasons(): Map<String, String> = lock.withLock { closeReasons.toMap() }

    /** 只读诊断：nodeId → 本端主动关闭次数（recheck / stopped）。 */
    fun closeCounts(): Map<String, Int> = lock.withLock { closeCounts.toMap() }

    /** 只读诊断：nodeId → 最近一次主动关闭的判据两元组（task-40）。 */
    fun closeDetails(): Map<String, String> = lock.withLock { closeDetails.toMap() }

    private suspend fun tick() {
        if (!enabled()) {
            // 开关关掉：立即断开，回到原路径。
            if (isConnectedOrConnecting()) stop()
            return
        }
        val now = app.relay.core.nowMillis()
        for (record in registry.values()) {
            val targets = linkTargets(record)
            if (targets.isEmpty()) {
                // 没拨：把"为什么没拨"记下来（否则真机上只能看到 attempts=0）。
                lock.withLock { dialSkipReason[record.profile.nodeId] = skipReason(record) }
                continue
            }
            lock.withLock { dialSkipReason.remove(record.profile.nodeId) }
            val peerId = record.profile.nodeId
            if (isConnected(peerId)) continue
            val ready = lock.withLock {
                if (peerId in connecting) return@withLock false
                val at = nextAttemptAt[peerId] ?: 0L
                if (now < at) return@withLock false
                connecting += peerId
                true
            }
            if (!ready) continue
            scope.launch { runCatching { connectOnce(peerId, targets) } }
        }
    }

    /** 一次连接的目标：地址、端口、以及要不要验 pin。 */
    private data class LinkTarget(
        val peerId: String,
        val host: String,
        val port: Int,
        val pin: String?,
        val secure: Boolean,
    )

    /**
     * 该给这个对端**按序**试哪些地址，每个地址按策略决定端口与是否 TLS。
     *
     * 为什么必须遍历候选池（task-27 真机）：手机只留移动网时，registry 里的"当前地址"是上一次
     * 学到的**局域网 IPv4**（移动网不可达），而配对码存下来的**公网 IPv6** 才是通的。
     * 只按当前地址拨号 ⇒ 一个包都发不出去（Mac 的 17880 一条 ESTABLISHED 都没有）。
     * 候选来源与 Messenger.endpointsFor **同源**：当前地址在前，然后才是候选池。
     *
     * 端口策略（lead 裁决）：
     *  · **公网** ⇒ [Profile.tlsPort] + TLS，且**必须有 pin**；没 pin/TLS 就不试
     *    （fail-closed，绝不退回明文）；
     *  · **私网** ⇒ 明文 tcpPort（K1 的局域网路径继续有效）。
     */
    private fun linkTargets(record: PeerRecord): List<LinkTarget> {
        val peerId = record.profile.nodeId
        if (MsgTypes.CAP_LINK !in record.profile.caps) return emptyList()
        val pin = registry.pinOf(peerId)
        val tlsPort = record.profile.tlsPort
        val hosts = LinkedHashSet<String>()
        if (record.ip.isNotBlank()) hosts += record.ip
        for (candidate in registry.candidatesOf(peerId)) {
            if (candidate.host.isNotBlank()) hosts += candidate.host
        }
        val out = ArrayList<LinkTarget>(hosts.size)
        for (host in hosts) {
            // task-53：与复查侧**同一条判据** [IpAddressUtils.isLanAddress] —— 家里 LAN 是
            // GUA-/64（中国宽带最常见）时，只认 isPrivateOrLocal 会让 K1 一个目标都不拨，
            // 等于把 K1 静默关掉。
            if (IpAddressUtils.isLanAddress(host, localAddresses())) {
                out += LinkTarget(peerId, host, record.profile.tcpPort, null, secure = false)
            } else if (transport.isSecure && pin != null && tlsPort != null) {
                out += LinkTarget(peerId, host, tlsPort, pin, secure = true)
            }
        }
        return out
    }

    /**
     * 为什么这个对端一个拨号目标都没有（诊断用）。
     *
     * 判据必须与 [linkTargets] 保持一致 —— 两处不一致的话，诊断会把"没拨"的原因说错。
     */
    private fun skipReason(record: PeerRecord): String {
        val peerId = record.profile.nodeId
        if (MsgTypes.CAP_LINK !in record.profile.caps) return "peer-has-no-lnk1"
        val hosts = buildList {
            if (record.ip.isNotBlank()) add(record.ip)
            registry.candidatesOf(peerId).forEach { if (it.host.isNotBlank()) add(it.host) }
        }
        if (!transport.isSecure) {
            // 与 [linkTargets] 同一判据（task-53），否则诊断会把"没拨"的原因说错。
            return if (hosts.any { IpAddressUtils.isLanAddress(it, localAddresses()) }) {
                "no-target"
            } else {
                "public-address-needs-tls"
            }
        }
        if (registry.pinOf(peerId) == null) return "no-pin"
        if (record.profile.tlsPort == null) return "peer-has-no-tls-port"
        return "no-target"
    }

    private fun isConnectedOrConnecting(): Boolean =
        lock.withLock { links.isNotEmpty() || connecting.isNotEmpty() }

    /**
     * 按序试 [targets]，第一个建起来的就用它；全都失败才退避。
     *
     * 为什么"按序试完"而不是"每个 tick 只试一个"：移动网下当前地址（局域网）必然超时，
     * 只试一个的话每个 tick 都白等一次 connectTimeout，候选永远轮不到（task-27 真机现场）。
     */
    private suspend fun connectOnce(peerId: String, targets: List<LinkTarget>) {
        try {
            for (target in targets) {
                if (tryConnect(target)) return
            }
            backoff(peerId)
        } finally {
            lock.withLock { connecting -= peerId }
        }
    }

    /** @return true = 建起来了（且 [pump] 已经跑完，即这条连接已经断开）。 */
    private suspend fun tryConnect(target: LinkTarget): Boolean {
        val peerId = target.peerId
        val transportUsed = if (target.secure) transport else plaintextTransport
        lock.withLock {
            dialAttempts[peerId] = (dialAttempts[peerId] ?: 0) + 1
            dialLastTarget[peerId] = target.host + ":" + target.port
        }
        // K2：把配对时存下的 pin 交给连接层 —— 证书固定在这里发生（验不过就返回 null，
        // 调用方继续试下一个候选，**不会**降级成明文）。
        val connection = transportUsed.connect(
            host = target.host,
            port = target.port,
            expectedPin = TlsPin.parse(target.pin),
            timeoutMs = connectTimeoutMs,
        ) ?: return recordDialFailure(peerId, transportUsed.lastError ?: "connect-failed")
        connection.send(LinkFrames.encode(LinkFrame.Hello(selfNodeId())))
        val helloBody = withTimeoutOrNull(connectTimeoutMs) { connection.receiveFrame() }
        val accepted = helloBody != null &&
            (TcpFrames.decodeBody(helloBody.decodeToString()) as? TcpFrameDecode.Link)?.frame == LinkFrame.Ok
        if (!accepted) {
            runCatching { connection.close() }
            // 连上了但握手/接受失败：多半是被 link-deny（鉴权/策略）或对端没接管这条连接。
            return recordDialFailure(peerId, "hello-rejected")
        }
        lock.withLock {
            links[peerId] = connection
            nextAttemptAt.remove(peerId)
            attemptCount.remove(peerId)
        }
        // 候选上建连成功 ⇒ 它就是这台主机现在可达的地址（验收要求：registry 当前地址跟着更新）。
        registry.setLinkedAddress(peerId, target.host)
        // **把上一次的失败原因清掉**。它记的是「这一次拨号成没成」，而这里已经成了 ——
        // 不清的话诊断会同时给出 `connected=true` 与 `lastError=connect-timeout`
        // （真机换网重连之后就是这个形态），看的人只会以为链路还在坏。
        // 历史不是丢掉：失败**次数**留在 `dialFailures` 里，那个才是累计量。
        lock.withLock { dialLastError.remove(peerId) }
        pump(peerId, connection)
        return true
    }

    /** 记一次拨号失败（只读诊断），并返回 false 让调用方继续试下一个候选。 */
    private fun recordDialFailure(peerId: String, error: String): Boolean {
        lock.withLock {
            dialFailures[peerId] = (dialFailures[peerId] ?: 0) + 1
            dialLastError[peerId] = error
        }
        return false
    }

    private suspend fun pump(peerId: String, connection: LinkConnection) {
        // 最近一次收发时刻：**有数据就免心跳**（NAT 映射已经被真实流量刷新过了）。
        var lastActivity = app.relay.core.nowMillis()
        // 上一次**发出** Ping 的时刻：0 表示还没发过（第一轮必发）。
        var lastPingSent = 0L
        val heartbeat = scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                val now = app.relay.core.nowMillis()
                val sinceActivity = now - lastActivity
                val sincePing = now - lastPingSent
                // 「有数据免心跳」只在**刚有过数据**时生效；并且**必须有保底**：
                // 只要距上次 Ping 已经 ≥ 2 轮（2×heartbeatMs = 30s < deadAfterMs 45s）就一定发。
                //
                // 为什么保底是必需的（task-40 真机）：免心跳会让"一直收到对端 Ping"的那一侧
                // lastActivity 被不断刷新，于是它**永远不发自己的 Ping**；对端因此在本侧
                // deadAfterMs 内收不到任何字节 ⇒ 判死、关连接 ⇒ 对端重拨 —— 表现就是
                // linkConnected 周期性 True/False 跳。
                if (sinceActivity < heartbeatMs && sincePing < heartbeatMs * 2) continue
                // Doze/低电耗窗口：降频（默认 false ⇒ 既有行为一字不变）。
                if (idleNow()) continue
                runCatching { connection.send(LinkFrames.encode(LinkFrame.Ping)) }
                lastPingSent = now
            }
        }
        try {
            while (true) {
                var closedByPeer = false
                val body = withTimeoutOrNull(deadAfterMs) {
                    val frame = connection.receiveFrame()
                    if (frame == null) closedByPeer = true
                    frame
                }
                if (body == null) {
                    recordPumpClose(peerId, if (closedByPeer) CLOSE_PEER_CLOSED else CLOSE_DEAD_TIMEOUT)
                    break
                }
                lastActivity = app.relay.core.nowMillis()
                when (val decoded = TcpFrames.decodeBody(body.decodeToString())) {
                    is TcpFrameDecode.Link -> if (decoded.frame == LinkFrame.Ping) {
                        runCatching { connection.send(LinkFrames.encode(LinkFrame.Pong)) }
                        // **心跳也是可达性证据，而且比 UDP 数据报更强**：它证明的是一条已经
                        // 握手过、现在还活着的 TCP。这里必须顺手刷新 presence —— 否则「UDP 到
                        // 不了（WiFi 客户端隔离 / 入站被挡）+ 没有 Msg 流经链路（两端都在排队）」
                        // 会死锁：sweep 判离线 → 双方都排队 → 没有 Msg → 永远不刷新 → 一直离线。
                        //
                        // 真机现场（relay-diag-20261020-075417）：transport=Wifi，同一台对端
                        // linkConnected=true / dialFailures=0 / closeCount=0，而 online=false；
                        // 用户体感是「移动网能收、连上 WiFi 收不到、断开又能收」（断 WiFi 会走
                        // NetworkWatch 的换网分支重拨 + 排空队列，于是积压一次全到）。
                        registry.setLinkedAddress(peerId, connection.remoteHost)
                    }

                    is TcpFrameDecode.Msg -> {
                        // 同主机侧：投递前先采纳连接的对端地址，避免被 UDP 侧刷成别的地址后
                        // 被 acceptTcpMessage 当漂移丢掉。
                        registry.setLinkedAddress(peerId, connection.remoteHost)
                        runCatching { onMessage(decoded.envelope, connection.remoteHost) }
                        runCatching { connection.send(msgAckFrameBytes(decoded.envelope.id)) }
                    }

                    else -> Unit
                }
            }
        } finally {
            heartbeat.cancel()
            lock.withLock { if (links[peerId] === connection) links.remove(peerId) }
            runCatching { connection.close() }
            backoff(peerId)
        }
    }

    /** 1s、2s、4s…上限 60s，±20% 抖动（避免所有设备同一秒重连）。 */
    private fun backoff(peerId: String) {
        val attempt = lock.withLock {
            val n = (attemptCount[peerId] ?: 0) + 1
            attemptCount[peerId] = n
            n
        }
        val base = minOf(1_000L shl minOf(attempt - 1, 6), MAX_BACKOFF_MS)
        val jitter = (base / 5) * ((app.relay.core.nowMillis() % 1000) / 1000)
        lock.withLock { nextAttemptAt[peerId] = app.relay.core.nowMillis() + base + jitter }
    }

    companion object {
        const val DEFAULT_TICK_MS: Long = 2_000
        const val MAX_BACKOFF_MS: Long = 60_000
    }
}

/** 连接结束原因（只读诊断口径，task-40 ①）。 */
internal const val CLOSE_DEAD_TIMEOUT = "dead-timeout"
internal const val CLOSE_PEER_CLOSED = "peer-closed"
internal const val CLOSE_REPLACED = "replaced"
internal const val CLOSE_RECHECK = "recheck"
internal const val CLOSE_STOPPED = "stopped"

/**
 * 公网对端上**不许存在明文长连接**（task-13 裁决；task-32 真机复验）。
 *
 * 抽成顶层函数是刻意的：接受时的裁决（[linkDenyReason]）与"地址变了之后复查"
 * （[LinkServer]/[LinkClient] 的 enforcePlaintextPolicy）必须用**同一条判据** ——
 * 两处各写一遍，迟早有一处忘记改。
 */
internal fun plaintextLinkForbidden(
    isPlaintext: Boolean,
    peerAddress: String,
    localAddresses: List<String> = emptyList(),
): Boolean = isPlaintext && !IpAddressUtils.isLanAddress(peerAddress, localAddresses)

/** 拒绝原因的**诊断码**（与 `/v1/peers` 的 `linkDenyReason`、手机 diag 同口径）。 */
internal const val DENY_BAD_VERSION = "bad-version"
internal const val DENY_UNKNOWN_PEER = "unknown-peer"
internal const val DENY_NOT_LAN = "not-lan"
internal const val DENY_PIN_MISSING = "pin-missing"
internal const val DENY_PIN_MISMATCH = "pin-mismatch"

/**
 * 长连接接受裁决的**唯一**判据：null = 接受；否则是**诊断码**。
 *
 * 为什么返回诊断码而不是线上原因：线上原因只有两个取值（协议兼容），而"被拒"这件事有五种
 * 完全不同的成因（版本 / 不认识 / 明文出网 / 没存 pin / pin 不匹配），修法各不相同。
 * task-36 真机就是被这一条挡住的：手机侧只能看到笼统的 `hello-rejected`，
 * 而真相是主机侧 `pin-missing`（二维码是单向的，主机从来没有手机的 pin）。
 */
internal fun linkDenyDiagnostic(
    hello: LinkFrame.Hello,
    remoteHost: String,
    isSecure: Boolean,
    known: Boolean,
    storedPin: String?,
    peerPin: TlsPin?,
    /** 本机当前地址：判"对端是不是同一段局域网"（task-53，与拨号/复查同一判据）。 */
    localAddresses: List<String> = emptyList(),
): String? = when {
    // 协议版本不对：连"它说的是哪套协议"都不确定，直接拒
    hello.ver != LinkFrames.VERSION -> DENY_BAD_VERSION
    // 不在授信/已知列表里：任意人都不能占用下行通道
    !known -> DENY_UNKNOWN_PEER
    // 明文只允许局域网。**接受时**按 `!isSecure` 判（fail-closed：认不出安全就不许从公网明文
    // 接进来）；地址复查那侧按 LinkConnection.isPlaintext 判（fail-safe：认不出明文就不摘）。
    // 两个方向相反是刻意的 —— 见 LinkConnection.isPlaintext 的 KDoc（task-40 真机）。
    (!isSecure && !IpAddressUtils.isLanAddress(remoteHost, localAddresses)) -> DENY_NOT_LAN
    // TLS 必须双向固定：主机侧没存过对方 pin（首次固定见 LinkServer.register 的 TOFU）
    isSecure && storedPin == null -> DENY_PIN_MISSING
    // 存过 pin 就必须一致 —— 验不过**不回退明文**（fail-closed）
    isSecure && peerPin?.wire != storedPin -> DENY_PIN_MISMATCH
    else -> null
}

/** 诊断码 → 回给对端的**线上**原因（协议里只有 `unknown-peer`/`not-lan` 两个取值，保持兼容）。 */
internal fun linkDenyReasonFor(diagnostic: String): String = when (diagnostic) {
    DENY_NOT_LAN -> LinkFrames.REASON_NOT_LAN
    else -> LinkFrames.REASON_UNKNOWN_PEER
}

/** 兼容旧调用点：直接给线上原因（裁决仍是 [linkDenyDiagnostic] 那一份）。 */
internal fun linkDenyReason(
    hello: LinkFrame.Hello,
    remoteHost: String,
    isSecure: Boolean,
    known: Boolean,
    storedPin: String?,
    peerPin: TlsPin?,
    localAddresses: List<String> = emptyList(),
): String? = linkDenyDiagnostic(hello, remoteHost, isSecure, known, storedPin, peerPin, localAddresses)
    ?.let { linkDenyReasonFor(it) }

/** msg 帧字节：与 TCP 回退完全同形（4 字节长度前缀 + {type:msg,envelope}）。 */
internal fun msgFrameBytes(envelope: Envelope): ByteArray =
    TcpFrame.encode(TcpFrames.encodeMsgFrame(envelope).encodeToByteArray())

/** msg-ack 帧字节：长连接上的送达确认与 TCP 回退逐字一致。 */
internal fun msgAckFrameBytes(ackFor: String): ByteArray =
    TcpFrame.encode(
        ("{\"type\":\"" + TcpFrameTypes.MSG_ACK + "\",\"ackFor\":" +
            Codec.wireJson.encodeToString(String.serializer(), ackFor) + "}").encodeToByteArray(),
    )

/** 由 [Codec] 直接编码一帧，供测试与调试使用。 */
internal fun encodeEnvelopeFrame(envelope: Envelope): ByteArray = msgFrameBytes(envelope)
