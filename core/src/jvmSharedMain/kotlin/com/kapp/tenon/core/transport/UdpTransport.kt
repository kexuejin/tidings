// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import com.kapp.tenon.core.ioDispatcher
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
 * 裸 UDP 传输通道 —— **不是生产传输形态**，是回环传输。
 *
 * 生产只有一条路：luciola 的 [com.kapp.tenon.core.transport.SecureChannelTransport]
 * （Rust SecureSession，TCP 上的认证加密信道）。本类住在 jvmSharedMain，服务三类
 * JVM/Android 消费者：`InMemoryAppPlatform`（CLI 与测试用内存平台）、各模块测试的
 * `udpTestTransport()` 回环夹具、以及 core 自己的传输行为测试。它必须保持与
 * [DatagramTransport] 同形，这正是「接口同形、实现可换」那条纪律的锚点。
 *
 * 刻意只做三件事：绑定、收发裸字节、把报文投进 Flow。协议语义（entry/alive/msg/ack）
 * 全部在上层（PresenceIngest / Messenger），这样传输层可以被单测直接替换成假的。
 *
 * 用 ktor-network 而不是 `expect/actual` + `java.net.DatagramSocket`：一份实现同时跑
 * JVM / Android，省掉两份 java.net 文件。
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

    /**
     * 收包泵是否还活着（task-29 的第二种死法，真机确认）。
     *
     * 第一处修复管的是「socket 发不出去」——发送结果回传，presence 连续 0 交付就重建。
     * 但真机上还有**另一种静默死法**：网络切换（AP 重启/Wi-Fi 抖动）后 ktor 的
     * `bound.incoming` 通道**不抛异常地正常结束**，`for` 循环体面退出 —— socket 依然绑定、
     * 依然发得出去，可**收包永远停了**。表现：对端发来的消息本端收到并回了 ack，本端
     * 自己却永远等不到任何 ack（重试到天荒地老）；内核 UDP 计数器照常增长、NoPorts 不动，
     * 唯一症状是「双方都说对方不回话」。现有的「发送失败自愈」对此**永不触发**。
     *
     * 桥接方案：泵退出（无论哪条路径）就把 `pumpAlive` 置 false，[send] 据此如实返回
     * false —— 「收不了包的 transport 就是坏的」，让既有的「连续 0 交付 ⇒ [restart]」
     * 自愈路径接管，不需要新的监督者。
     */
    @Volatile
    private var pumpAlive: Boolean = false

    /**
     * 泵的世代号。[start] 每建一个泵推进一次；泵退出时只有「自己仍是当前世代」才允许
     * 把 [pumpAlive] 置 false。
     *
     * 没有这一层，[restart] 就是个竞态：[stop] 只 `pump?.cancel()`，并不等旧泵协程跑完，
     * 旧泵的 `finally { pumpAlive = false }` 可能晚于 [start] 的 `pumpAlive = true` 落地
     * （ioDispatcher 负载高时，全量重复跑实测撞到），把刚重建好的 transport 又判死
     * ⇒ [send] 恒 false、发现引擎的自愈白做一轮。真机上是「网络切换后自己永远
     * 收不到 ack」的同族症状，不是测试环境特有的抖动。
     */
    @Volatile
    private var pumpEpoch: Long = 0L

    private val _incoming = MutableSharedFlow<ReceivedDatagram>(extraBufferCapacity = 256)

    /** 入站报文流。必须在 [start] 之前订阅，否则早期报文会被丢进 extraBufferCapacity。 */
    override val incoming: SharedFlow<ReceivedDatagram> = _incoming

    /** 实际绑定的端口（传 0 时由系统分配，测试用）。 */
    override var boundPort: Int = port
        private set

    val isRunning: Boolean get() = socket != null

    override suspend fun start() {
        if (socket != null) return
        val epoch = ++pumpEpoch
        // broadcast = true 不是优化，是**必需**的：受限广播（255.255.255.255）在
        // SO_BROADCAST 没打开时会被内核直接拒收，JVM 的 DatagramChannel 抛
        // `BindException: Permission denied`。而 best-effort 发送是 `runCatching`
        // 吞掉异常的，于是广播会**静默地一条都发不出去**：症状是两台设备永远互相
        // 「离线」，只有手动填过地址的单播还能通（真机上就是这样暴露的，
        // 见 docs/adr/ADR-010）。回归测试见 `UdpTransportBroadcastTest`。
        val sel = SelectorManager(ioDispatcher)
        val runScope = CoroutineScope(SupervisorJob() + ioDispatcher)
        val bound = aSocket(sel).udp()
            .bind(InetSocketAddress(bindHost, port)) { broadcast = true }
        selector = sel
        scope = runScope
        socket = bound
        // 先置活再起泵：send() 的门禁不依赖协程调度器的首次执行（否则 start 之后
        // 到泵真正跑起来之前有个 send 恒 false 的窗口，presence 会白白记一轮死轮）。
        pumpAlive = true
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
            } finally {
                // 泵退出的**全部**路径都算「收包已死」：通道正常结束（task-29 的静默死法）、
                // 异常、取消（stop）。但只许当前世代置 false —— restart() 里旧泵的收尾
                // 可能迟到，不许把刚建好的那个泵一起判死。置 false 之后 [send] 会如实
                // 返回 false，由「连续 0 交付 ⇒ restart」的自愈路径重建整个 transport。
                onPumpExited(epoch)
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
        // 收包泵已死 ⇒ 整个 transport 判死（能发不能收的 UDP 通道对协议毫无用处：
        // 发出去的每一条都在等 ack，而 ack 永远进不来）。如实返回 false，
        // 让上层（presence 自愈 / Messenger 的队列语义）按「没交出去」处理。
        if (!pumpAlive) {
            lastSendError = "receive-pump-dead: incoming channel ended, transport needs restart"
            return false
        }
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
        // 显式判死，不等旧泵的 finally：stop 之后 socket 已经是 null，send 本来就会
        // 返回 false，但 pumpAlive 不该继续撒谎说泵活着。世代号不在这里推进 ——
        // 推进交给下一次 start，旧泵仍能凭「世代不符」被拒之门外。
        pumpAlive = false
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
     * 只在"连续几轮一个包都发不出去"时由发现引擎的自愈路径调用 —— 正常路径永远不会
     * 走到，所以它不会打断一条健康的连接（入站报文流 [incoming] 是外部持有的
     * SharedFlow，重建期间照旧收）。
     */
    override suspend fun restart() {
        stop()
        start()
    }

    /** 仅测试用：当前泵的世代号，用来确定性回放「旧泵 finally 迟到」。 */
    internal val pumpEpochForTest: Long get() = pumpEpoch

    /**
     * 泵退出的统一收尾：只许当前世代把 transport 判死。
     *
     * 抽成方法不是为了复用，是为了让 [restart] 那个竞态**能被确定性测到** ——
     * 赌调度顺序的用例会「单跑绿、全量红」，反而把缺陷藏起来。
     */
    internal fun onPumpExited(epoch: Long) {
        if (pumpEpoch == epoch) pumpAlive = false
    }

    /** 仅测试用：模拟「通道静默结束、socket 还在」的死泵状态（task-29 第二死法）。 */
    internal fun markReceivePumpDeadForTest() {
        pumpAlive = false
    }
}
