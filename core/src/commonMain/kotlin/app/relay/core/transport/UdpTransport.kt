// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import app.relay.core.ioDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write

/**
 * 一条入站 UDP 报文。
 *
 * [bytes] 是载荷本身（不含任何分帧）；[host]/[port] 是发送方地址 —— 茶话间
 * 用「回包发回 rinfo 的地址」而不是注册表里的地址，所以这两个字段必须原样带上来。
 */
data class ReceivedDatagram(
    val bytes: ByteArray,
    val host: String,
    val port: Int,
) {
    // ByteArray 的 equals 是引用比较，data class 生成的版本会骗人，这里显式写清楚。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedDatagram) return false
        return host == other.host && port == other.port && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = (host.hashCode() * 31 + port) * 31 + bytes.contentHashCode()

    override fun toString(): String = "ReceivedDatagram($host:$port, ${bytes.size}B)"
}

/**
 * UDP 传输通道（protocol §2）。
 *
 * 刻意只做三件事：绑定、收发裸字节、把报文投进 Flow。协议语义（entry/alive/msg/ack）
 * 全部在 [app.relay.core.transport.Discovery] 与 [app.relay.core.transport.Messenger] 里，
 * 这样传输层可以被单测直接替换成假的。
 *
 * 用 ktor-network 而不是 `expect/actual` + `java.net.DatagramSocket`：一份实现同时跑
 * JVM / Android / iOS，省掉两份 java.net 文件和一份 Kotlin/Native POSIX 套接字代码。
 */
class UdpTransport(
    private val port: Int,
    private val bindHost: String = "0.0.0.0",
) {
    private val selector = SelectorManager(ioDispatcher)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private var socket: BoundDatagramSocket? = null
    private var pump: Job? = null

    private val _incoming = MutableSharedFlow<ReceivedDatagram>(extraBufferCapacity = 256)

    /** 入站报文流。必须在 [start] 之前订阅，否则早期报文会被丢进 extraBufferCapacity。 */
    val incoming: SharedFlow<ReceivedDatagram> = _incoming

    /** 实际绑定的端口（传 0 时由系统分配，测试用）。 */
    var boundPort: Int = port
        private set

    val isRunning: Boolean get() = socket != null

    suspend fun start() {
        if (socket != null) return
        // broadcast = true 不是优化，是**必需**的：受限广播（255.255.255.255）在
        // SO_BROADCAST 没打开时会被内核直接拒收，JVM 的 DatagramChannel 抛
        // `BindException: Permission denied`。而 [Discovery] 的发送是 best-effort
        // （`runCatching` 吞掉异常），于是局域网广播会**静默地一条都发不出去**：
        // 症状是两台设备永远互相「离线」，只有手动填过地址的单播还能通
        // （真机上就是这样暴露的，见 docs/adr/ADR-010）。回归测试见
        // `UdpTransportBroadcastTest`。
        val bound = aSocket(selector).udp()
            .bind(InetSocketAddress(bindHost, port)) { broadcast = true }
        socket = bound
        boundPort = (bound.localAddress as? InetSocketAddress)?.port ?: port
        pump = scope.launch {
            try {
                for (dg in bound.incoming) {
                    val from = dg.address as? InetSocketAddress ?: continue
                    val bytes = dg.packet.readByteArray()
                    _incoming.emit(ReceivedDatagram(bytes, literalHost(from), from.port))
                }
            } catch (_: ClosedReceiveChannelException) {
                // 正常关闭路径。
            }
        }
    }

    /** 发一条裸报文。地址就是收包时看到的地址，不做任何解析。 */
    suspend fun send(bytes: ByteArray, host: String, port: Int) {
        val s = socket ?: error("UdpTransport 尚未 start()")
        val buf = Buffer().apply { write(bytes) }
        s.send(Datagram(buf, InetSocketAddress(host, port)))
    }

    fun stop() {
        pump?.cancel()
        pump = null
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
        runCatching { selector.close() }
    }
}
