// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import com.kapp.tidings.core.ioDispatcher
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
 * 全部在 [com.kapp.tidings.core.transport.Discovery] 与 [com.kapp.tidings.core.transport.Messenger] 里，
 * 这样传输层可以被单测直接替换成假的。
 *
 * 用 ktor-network 而不是 `expect/actual` + `java.net.DatagramSocket`：一份实现同时跑
 * JVM / Android / iOS，省掉两份 java.net 文件和一份 Kotlin/Native POSIX 套接字代码。
 */
class UdpTransport(
    private val port: Int,
    private val bindHost: String = "0.0.0.0",
) : DatagramTransport {
    /**
     * selector / scope 都在 [start] 里创建、在 [stop] 里销毁 —— **不再是一次性的**。
     *
     * 为什么必须可重建（task-29 真机）：手机侧的发现泵会彻底静默（没有异常、没有 TCP、
     * 对端 lastSeen 不再更新），而 socket 一旦被关或坏掉，旧写法`send` 会抛异常、
     * 被 `runCatching` 吞掉，于是**永远静默**。可重建 + 发送结果回传，才能自愈。
     */
    private var selector: SelectorManager? = null
    private var scope: CoroutineScope? = null

    private var socket: BoundDatagramSocket? = null
    private var pump: Job? = null

    private val _incoming = MutableSharedFlow<ReceivedDatagram>(extraBufferCapacity = 256)

    /** 入站报文流。必须在 [start] 之前订阅，否则早期报文会被丢进 extraBufferCapacity。 */
    override val incoming: SharedFlow<ReceivedDatagram> = _incoming

    /** 实际绑定的端口（传 0 时由系统分配，测试用）。 */
    override var boundPort: Int = port
        private set

    val isRunning: Boolean get() = socket != null

    override suspend fun start() {
        if (socket != null) return
        // broadcast = true 不是优化，是**必需**的：受限广播（255.255.255.255）在
        // SO_BROADCAST 没打开时会被内核直接拒收，JVM 的 DatagramChannel 抛
        // `BindException: Permission denied`。而 [Discovery] 的发送是 best-effort
        // （`runCatching` 吞掉异常），于是局域网广播会**静默地一条都发不出去**：
        // 症状是两台设备永远互相「离线」，只有手动填过地址的单播还能通
        // （真机上就是这样暴露的，见 docs/adr/ADR-010）。回归测试见
        // `UdpTransportBroadcastTest`。
        val sel = SelectorManager(ioDispatcher)
        val runScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        val bound = aSocket(sel).udp()
            .bind(InetSocketAddress(bindHost, port)) { broadcast = true }
        selector = sel
        scope = runScope
        socket = bound
        boundPort = (bound.localAddress as? InetSocketAddress)?.port ?: port
        pump = runScope.launch {
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

    /**
     * 发一条裸报文。地址就是收包时看到的地址，不做任何解析。
     *
     * @return false = 这个包**没有交出去**（socket 没起来、已关、或内核拒绝）。
     *   调用方据此自愈 —— 从前这里是 `error(...)`/抛异常，被发送侧的 `runCatching`
     *   吞掉之后，一台设备的发现泵可以静默地彻底停摆（task-29 真机现场）。
     */
    override suspend fun send(bytes: ByteArray, host: String, port: Int): Boolean {
        val s = socket ?: return false
        // 地址族守卫：v4 绑定的 socket 发 v6 目标（或反过来），Ktor 的错误**异步**落在
        // 通道上——本次 send 仍返回 true，错误在下一次 send 才炸出来。多端点序列
        // （队列自愈「先试当前地址、再试候选」）于是被第一个坏端点毒化：第二个端点的
        // 报文根本没进内核，症状是 loopback 发送报 NoRouteToHost。这里先短路，不碰通道。
        // 例外：`::` 是双栈绑定（默认配置下 v4 映射可达），两种族都放行 —— 将来默认
        // 绑定翻成 `::` 时不需要改这里。真正的双栈默认值切换要过真机广播验证，未做。
        val bindIsDualStack = bindHost == "::"
        if (!bindIsDualStack && host.contains(':') != bindHost.contains(':')) {
            lastSendError = "address-family-mismatch: bound=$bindHost target=$host"
            return false
        }
        val attempt = runCatching {
            val buf = Buffer().apply { write(bytes) }
            s.send(Datagram(buf, InetSocketAddress(host, port)))
            lastSendError = null
            true
        }
        if (attempt.isSuccess) return true
        // 单次重发解「毒」：上一次 send 留在通道里的异步错误会在本次调用上先行炸出
        // （同一族目标也会遇到，例如无路由的 v4 目标），报文本体还没交出去。
        // 重发一次；再失败就是本次真实的失败，如实返回。
        val retry = runCatching {
            val buf = Buffer().apply { write(bytes) }
            s.send(Datagram(buf, InetSocketAddress(host, port)))
            true
        }
        return if (retry.isSuccess) {
            lastSendError = null
            true
        } else {
            // 如实记下失败原因：调用方（与回归测试）要能区分「没开 SO_BROADCAST 被内核拒绝」
            // 和「本机根本没有广播路由」——前者是缺陷，后者是环境。只返回 false 时两者不可分。
            val e2 = retry.exceptionOrNull()!!
            lastSendError = e2::class.simpleName + ": " + (e2.message ?: "")
            false
        }
    }

    /** 最近一次 [send] 失败的原因（成功时为 null）。**不要**在失败后忘记清空 —— 见 send 的实现。 */
    var lastSendError: String? = null
        private set

    override fun stop() {
        pump?.cancel()
        pump = null
        runCatching { socket?.close() }
        socket = null
        scope?.cancel()
        scope = null
        runCatching { selector?.close() }
        selector = null
    }

    /**
     * 自愈：把 socket / selector / scope 整体重建（等价 stop + start，幂等）。
     *
     * 只在"连续几轮一个包都发不出去"时由 [Discovery] 调用 —— 正常路径永远不会走到，
     * 所以它不会打断一条健康的连接（入站报文流 [incoming] 是外部持有的 SharedFlow，
     * 重建期间照旧收）。
     */
    override suspend fun restart() {
        stop()
        start()
    }
}
