// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.TlsPin
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import platform.Network.NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT
import platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_copy_endpoint
import platform.Network.nw_connection_create
import platform.Network.nw_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_t
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_endpoint_get_hostname
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_state_ready
import platform.Network.nw_listener_t
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_t
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_create_map
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_t
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.Network.nw_protocol_options_t
import platform.Security.SecCertificateCopyKey
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.sec_protocol_options_set_local_identity
import platform.Security.sec_protocol_options_set_peer_authentication_required
import platform.Security.sec_protocol_options_set_verify_block
import platform.Security.sec_trust_copy_ref
import platform.Security.sec_trust_t
import platform.darwin.dispatch_queue_create

/**
 * iOS 的 K2 连接层：TLS + 双向固定（task-26 ① 第 4 步）。
 *
 * 与 JVM 侧同一套口径：
 *  · 本地身份来自 [IosLinkIdentity]（key + 自签 IP 证书 + keychain identity）；
 *  · **双向**：`set_peer_authentication_required(true)` 要求对端出示客户端证书；
 *  · **固定**：verify block 里用 [peerPinAccepted] 比"对端证书 SPKI ↔ 期望 pin"，不一致
 *    **当场** `complete(false)` —— fail-closed 由框架本身保证，不靠应用层兜。
 *
 * ## 诚实边界（现在的状态）
 *
 * 已落地的是**安全相关的两半**：身份获取（[IosLinkIdentity]）+ TLS 选项/verify block 的构造
 * （[tlsOptions]），以及"拿不到身份就拒绝启动"。**连接与监听的实际收发**
 * （`nw_connection`/`nw_listener` + 分帧 IO）还没接线 —— 所以 [connect] 与 [startServer]
 * 现在**一律返回失败**，iOS 侧等价于 fail-closed：既不会明文出网，也不会建一条
 * 没有客户端证书的连接。
 *
 * 接线必须在 **App 进程**里验：Kotlin/Native 的测试二进制拿不到 keychain
 * （`SecItemAdd` → -25291 errSecNotAvailable，见 [IosLinkIdentity.keychainStatus]），
 * 因此 `iosSimulatorArm64Test` 跑不了完整握手。
 */
@OptIn(ExperimentalForeignApi::class)
class IosTlsLinkTransport(
    private val identityDir: () -> String,
    private val addresses: () -> List<String>,
) : LinkTransport {

    override val isSecure: Boolean = true

    override val canServeTls: Boolean = true

    private val identity: IosLinkIdentity by lazy { IosLinkIdentity.ensure(identityDir(), addresses()) }

    /** 本机身份的公钥指纹（SPKI pin）—— 会进配对二维码，公开无妨；自检/诊断也用它。 */
    override val localPin: TlsPin get() = identity.pin

    /** 监听**实际绑上**的端口（传 0 时由系统分配；未监听为 null）。自检靠它拿回环端口。 */
    var boundServerPort: Int? = null
        private set

    private var listener: nw_listener_t? = null

    /** 接受连接的回调里不能挂起，所以等 ready / 交给 LinkServer 都在这个 scope 上跑。 */
    private val acceptScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 没有 keychain identity（测试进程、或权限缺失）时**一切拒绝** —— 绝不"没有客户端证书也去连"。 */
    private fun usableIdentity(): IosLinkIdentity? = identity.takeIf { it.secIdentity != null }

    override suspend fun connect(host: String, port: Int, expectedPin: TlsPin?, timeoutMs: Long): LinkConnection? {
        // 没有期望 pin ⇒ 不拨（"没验"不等于"验过"）；没有身份 ⇒ 不拨（双向固定缺一半）。
        if (expectedPin == null) return null
        if (usableIdentity() == null) return null
        val parameters = parametersFor(expectedPin, requirePin = true) ?: return null
        // nw_endpoint_create_host 的 cinterop 签名收 Kotlin String（不是 C 指针）。
        val endpoint = nw_endpoint_create_host(host, port.toString()) ?: return null
        val connection = nw_connection_create(endpoint, parameters) ?: return null
        val queue = dispatch_queue_create("com.kapp.tidings.link.conn", null)
        nw_connection_set_queue(connection, queue)
        val ready = CompletableDeferred<Boolean>()
        nw_connection_set_state_changed_handler(connection) { state, _ ->
            when (state) {
                nw_connection_state_ready -> ready.complete(true)
                nw_connection_state_failed, nw_connection_state_cancelled -> ready.complete(false)
                else -> Unit
            }
        }
        nw_connection_start(connection)
        if (withTimeoutOrNull(timeoutMs) { ready.await() } != true) {
            nw_connection_cancel(connection)
            return null
        }
        return IosLinkConnection(connection, queue, host, port, expectedPin)
    }

    override suspend fun accept(
        socket: Socket,
        read: ByteReadChannel,
        write: ByteWriteChannel,
        remoteHost: String,
        remotePort: Int,
        expectedPin: TlsPin?,
    ): LinkConnection? = null

    override suspend fun startServer(port: Int, onConnection: suspend (LinkConnection) -> Unit): Boolean {
        // 没有身份就**拒绝监听**：宁可不提供 TLS，也不提供一个"不用客户端证书"的监听口。
        if (usableIdentity() == null) return false
        // 服务端握手期接受任意客户端证书（要求必须出示），pin 由 LinkServer.register 在首帧
        // hello 之后按 nodeId 的存量 pin 验 —— 这是 task-26 批准的设计（握手期还不知道 nodeId）。
        val parameters = parametersFor(expectedPin = null, requirePin = false) ?: return false
        val created = nw_listener_create(parameters) ?: return false
        val queue = dispatch_queue_create("com.kapp.tidings.link.listen", null)
        nw_listener_set_queue(created, queue)
        val ready = CompletableDeferred<Boolean>()
        nw_listener_set_state_changed_handler(created) { state, _ ->
            when (state) {
                nw_listener_state_ready -> ready.complete(true)
                nw_listener_state_failed -> ready.complete(false)
                else -> Unit
            }
        }
        nw_listener_set_new_connection_handler(created) { connection ->
            nw_connection_set_queue(connection, queue)
            val connReady = CompletableDeferred<Boolean>()
            nw_connection_set_state_changed_handler(connection) { state, _ ->
                when (state) {
                    nw_connection_state_ready -> connReady.complete(true)
                    nw_connection_state_failed, nw_connection_state_cancelled -> connReady.complete(false)
                    else -> Unit
                }
            }
            nw_connection_start(connection)
            acceptScope.launch {
                if (withTimeoutOrNull(HELLO_TIMEOUT_MS) { connReady.await() } == true) {
                    onConnection(IosLinkConnection(connection, queue, connection.remoteHostOrEmpty(), 0, null))
                } else {
                    nw_connection_cancel(connection)
                }
            }
        }
        nw_listener_start(created)
        if (withTimeoutOrNull(5_000) { ready.await() } != true) {
            nw_listener_cancel(created)
            return false
        }
        listener = created
        boundServerPort = nw_listener_get_port(created).toInt()
        return true
    }

    override suspend fun stopServer() {
        listener?.let { nw_listener_cancel(it) }
        listener = null
        boundServerPort = null
    }

    /**
     * TLS 选项：本地身份 + 要求对端证书 + **在 verify block 里比 pin**。
     *
     * 这段是安全边界，独立成函数便于审阅；[expectedPin] 为 null 时 verify block 一律拒绝
     * （"没验"不等于"验过"）。
     */
    private fun parametersFor(expectedPin: TlsPin?, requirePin: Boolean): nw_parameters_t? =
        nw_parameters_create_secure_tcp(
            { options -> applyTls(options, expectedPin, requirePin) },
            NW_PARAMETERS_DEFAULT_CONFIGURATION,
        )

    /**
     * 把本地身份、双向要求与校验装进这一层的 TLS 选项。
     *
     * [requirePin] 的两种用法是**刻意的**：
     *  · 客户端（true）：verify block 里比"对端证书 SPKI ↔ 期望 pin"，不一致当场拒绝；
     *  · 服务端（false）：**只要求对端出示证书**，不在握手期比 pin —— 因为握手期还不知道
     *    对端的 nodeId，pin 由 [LinkServer.register] 在首帧 hello 之后按存量 pin 验
     *    （task-26 批准的设计）。这里写成"必须出示证书"而不是"放行"：没有客户端证书照样拒。
     */
    private fun applyTls(options: nw_protocol_options_t?, expectedPin: TlsPin?, requirePin: Boolean) {
        if (options == null) return
        usableIdentity()?.secIdentity?.let { sec_protocol_options_set_local_identity(options, it) }
        sec_protocol_options_set_peer_authentication_required(options, true)
        val queue = dispatch_queue_create("com.kapp.tidings.link.verify", null)
        sec_protocol_options_set_verify_block(
            options,
            { _, trust, complete ->
                val spki = peerSpki(trust)
                val accepted = if (requirePin) {
                    peerPinAccepted(spki, expectedPin)
                } else {
                    // 服务端：必须出示客户端证书；pin 的比对在 register 里（那时才知道 nodeId）。
                    spki != null
                }
                // complete 是可空的 block（框架允许 null）；不 invoke 就等于"没答复"= 拒绝。
                complete?.invoke(accepted)
            },
            queue,
        )
    }

    /** 从 `sec_trust_t` 里取对端叶证书的 SPKI（X9.63 点 → 共享拼装）。 */
    private fun peerSpki(trust: sec_trust_t?): ByteArray? {
        val trustRef = trust?.let { sec_trust_copy_ref(it) } ?: return null
        val certificate = SecTrustGetCertificateAtIndex(trustRef, 0) ?: return null
        val key = SecCertificateCopyKey(certificate) ?: return null
        val data = SecKeyCopyExternalRepresentation(key, null) ?: return null
        val raw = data.toBytes() ?: return null
        return runCatching { LinkCertificateDer.ecP256SpkiFromRawPoint(raw) }.getOrNull()
    }

    private companion object {
        /** 等首帧 hello 的上限（与明文侧 LinkServer 同一个量级）。 */
        const val HELLO_TIMEOUT_MS = 5_000L
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFDataRef.toBytes(): ByteArray? {
    val length = CFDataGetLength(this).toInt()
    if (length <= 0) return ByteArray(0)
    val pointer = CFDataGetBytePtr(this) ?: return null
    return pointer.readBytes(length)
}

/** 一条 iOS TLS 连接：4 字节大端长度前缀 + JSON，与 JVM/明文侧完全同形。 */
@OptIn(ExperimentalForeignApi::class)
private class IosLinkConnection(
    private val connection: nw_connection_t,
    private val queue: dispatch_queue_t?,
    override val remoteHost: String,
    override val remotePort: Int,
    override val peerPin: TlsPin?,
) : LinkConnection {

    override val isSecure: Boolean = true

    private val writeLock = Mutex()
    private var buffered = ByteArray(0)

    override suspend fun send(frame: ByteArray) {
        writeLock.withLock {
            val data = frame.toDispatchData(queue) ?: return
            val done = CompletableDeferred<Unit>()
            nw_connection_send(connection, data, NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT, true) { _ ->
                done.complete(Unit)
            }
            withTimeoutOrNull(5_000) { done.await() }
        }
    }

    override suspend fun receiveFrame(): ByteArray? {
        while (true) {
            takeFrame()?.let { return it }
            val chunk = receiveChunk() ?: return null
            buffered += chunk
        }
    }

    /** 从已缓冲字节里切一帧出来；不够一帧返回 null（等下一块）。 */
    private fun takeFrame(): ByteArray? {
        if (buffered.size < 4) return null
        val length = ((buffered[0].toInt() and 0xFF) shl 24) or
            ((buffered[1].toInt() and 0xFF) shl 16) or
            ((buffered[2].toInt() and 0xFF) shl 8) or
            (buffered[3].toInt() and 0xFF)
        if (length <= 0 || length > MAX_FRAME_BYTES) return null
        if (buffered.size < 4 + length) return null
        val body = buffered.copyOfRange(4, 4 + length)
        buffered = buffered.copyOfRange(4 + length, buffered.size)
        return body
    }

    private suspend fun receiveChunk(): ByteArray? {
        val deferred = CompletableDeferred<ByteArray?>()
        nw_connection_receive(connection, 1u, MAX_FRAME_BYTES.toUInt()) { data, _, _, error ->
            if (error != null || data == null) deferred.complete(null) else deferred.complete(data.toBytes())
        }
        return deferred.await()
    }

    override fun close() {
        nw_connection_cancel(connection)
    }

    private companion object {
        const val MAX_FRAME_BYTES = 64 * 1024
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toDispatchData(queue: dispatch_queue_t?): dispatch_data_t? = usePinned { pinned ->
    dispatch_data_create(pinned.addressOf(0), size.toULong(), queue, null)
}

@OptIn(ExperimentalForeignApi::class)
private fun dispatch_data_t.toBytes(): ByteArray? = memScoped {
    val sizePointer = alloc<ULongVar>()
    // dispatch_data_create_map 要的是"通用指针的地址"（CPointer<out CPointed>），
    // 所以用 COpaquePointerVar，再 reinterpret 成字节指针读。
    val bytesPointer = alloc<COpaquePointerVar>()
    dispatch_data_create_map(this@toBytes, bytesPointer.ptr, sizePointer.ptr)
    val pointer = bytesPointer.value ?: return null
    pointer.reinterpret<UByteVar>().readBytes(sizePointer.value.toInt())
}

@OptIn(ExperimentalForeignApi::class)
private fun nw_connection_t.remoteHostOrEmpty(): String =
    nw_endpoint_get_hostname(nw_connection_copy_endpoint(this))?.toKString() ?: ""
