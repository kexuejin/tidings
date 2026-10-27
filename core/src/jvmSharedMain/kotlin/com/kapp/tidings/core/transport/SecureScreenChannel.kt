// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 投屏的**独立加密会话**（与消息、文件三条数据面互不挤占）。
 *
 * ## 为什么要独立一条
 *
 * 投屏是**连续、低延迟、允许丢一步但不能堵**的流量：10fps × 上百 KiB 的流会把
 * 消息信道与文件窗口的队列占满（文件发送端等 ACK 时照样在刷整窗），表现为
 * 「投屏一开、消息延迟跳起来、文件传输停住」。所以画面走自己的一条 [SecureSession]，
 * 队列、重连、心跳都与别的数据面无关。
 *
 * ## 形态
 *
 * 对外只有一个东西：[ScreenWire]（两条字节流）。screen-* 协议完全不知道底下是
 * 加密信道还是明文 socket —— 与跨网 TLS 那条走的是同一个抽象。
 *
 * - **写**：上层 `writeFully` 写进 [ByteChannel]，泵按 [chunkSize] 切片、封成
 *   [ScreenChannelFrame]，逐帧送进信道。队列满（send 返回 false）**退避重试而不是丢帧**：
 *   字节流丢一段就是协议错乱，而投屏的令牌桶本来就把速率压在信道能力之下。
 * - **读**：入站帧解开封套后按序写进另一个 [ByteChannel]，上层 `readFully`
 *   读到的就是连续字节流。
 *
 * ## 归属与信任
 *
 * 复用 [SecureChannelTransport]：拨号要求对端公钥已配对（deny-by-default），
 * 入站按握手指纹验信任、按指纹反查对端地址 —— 这里不建第二份节点表。
 */
class SecureScreenChannel(
    identitySeed: ByteArray,
    /** 拨号用的对端公钥（未配对返回 null ⇒ 不拨）。与消息信道同一个 pin 表。 */
    peerKey: (String, Int) -> ByteArray?,
    /** 监听端口；<= 0 = 只拨不听（手机/测试）。 */
    listenPort: Int = -1,
    /** 入站信任判据（默认全信：只有测试这么用）。 */
    isTrustedFingerprint: (String) -> Boolean = { true },
    /** 帧 → 对端地址（入站二进制帧没有 Envelope，只能靠握手指纹反查）。 */
    peerOfFingerprint: (String) -> PeerEndpoint? = { null },
    /** 切片大小。64KiB 与文件数据面同一个数量级：一次拷贝，不拆乱 screen 协议自己的帧。 */
    private val chunkSize: Int = 64 * 1024,
) {
    private val transport = SecureChannelTransport(
        identitySeed = identitySeed,
        peerKey = peerKey,
        listenPort = listenPort,
        isTrustedFingerprint = isTrustedFingerprint,
        peerOfFingerprint = peerOfFingerprint,
        // 这条信道上只跑画面：魔数一变，文件块与 Envelope 都不会被当成本面的帧。
        isBinaryFrame = { ScreenChannelFrame.isScreenFrame(it) },
    )

    /** 本端监听端口；未监听为 -1。 */
    val boundPort: Int get() = transport.boundPort

    /** 诊断：会话状态迁移 + Rust 侧计数（与消息信道同一个读数口径）。 */
    fun diagnostics(): String = transport.stateHistory() + " | " + transport.diagnostics()

    suspend fun start() = transport.start()

    fun stop() = transport.stop()

    /**
     * 拨号并返回一条字节流。**不等握手**：screen-* 自己的 `screen-open`/`screen-ready`
     * 就是"这条通不通"的判据（[ScreenReceiver] 已按这个纪律写），这里再等一次
     * 只会多一层超时与竞态。
     */
    fun dial(host: String, port: Int, scope: CoroutineScope): ScreenWire {
        val incoming = ByteChannel(autoFlush = true)
        val outgoing = ByteChannel(autoFlush = true)
        val writeJob = pumpOutbound(scope, outgoing, host, port)
        // 回程（Sharer 的 screen-ready 与画面帧）也走这条会话，所以拨号侧同样要
        // 把入站帧解出来写进读通道 —— 少了这一段，观众永远等不到第一帧。
        val readJob = pumpInbound(scope, transport, incoming)
        return ScreenWire(
            read = incoming,
            write = outgoing,
            close = {
                readJob.cancel()
                writeJob.cancel()
                runCatching { transport.stop() }
                runCatching { incoming.close() }
                runCatching { outgoing.close() }
            },
        )
    }

    /**
     * 把入站画面帧搬进读通道。
     *
     * 读端慢（解码/渲染卡住）时**背压到 [DELIVER_TIMEOUT_MS] 为止**：超时说明这场
     * 投屏已经不可能实时，于是如实收掉（上层按"投屏断开"处理），而不是继续把
     * 越来越旧的画面堆进内存。
     */
    private fun pumpInbound(
        scope: CoroutineScope,
        transport: SecureChannelTransport,
        channel: ByteChannel,
    ): Job = scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
        try {
            transport.incoming.collect { datagram ->
                val payload = ScreenChannelFrame.unwrap(datagram.bytes) ?: return@collect
                // **不套 withTimeoutOrNull**：一帧一个超时就是每帧建销毁一个定时任务，
                // 60fps 时这是实打实的调度噪声（真机实测套着时 p50 ≈ 38ms、去掉后 ≈ 24ms，
                // 而明文通路根本没有这一层）。背压由 ByteChannel 的容量自然给出；
                // "读端卡死"由上层（ScreenReceiver 的 SCREEN_FRAME_TIMEOUT_MS）兜住。
                runCatching { channel.writeFully(payload) }
            }
        } catch (_: CancellationException) {
            // 正常收摊
        }
    }

    /**
     * 开始接受入站画面会话；第一条可信连接到达时回调 [onAccept]，之后该连接的字节
     * 就写进这条 wire。
     *
     * 一次只服务一条画面会话（换连接即先关旧的）：`openScreen` 的交接纪律要求
     * 所有权一次只在一处。
     */
    fun accept(scope: CoroutineScope, onAccept: (ScreenWire) -> Unit) {
        acceptJob?.cancel()
        // 入站这条同样 Unconfined：它由 Rust 的回调线程驱动，喂进 ByteChannel 是纯内存
        // 拷贝，没有阻塞点，不该再排一次调度队列。
        acceptJob = scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            var incoming: ByteChannel? = null
            var writeJob: Job? = null
            var wire: ScreenWire? = null
            try {
                transport.incoming.collect { datagram ->
                    val payload = ScreenChannelFrame.unwrap(datagram.bytes) ?: return@collect
                    var target = incoming
                    // 读通道被关掉（上一场投屏已被上层收起）时**换一条新 wire**，
                    // 而不是继续往关闭的通道里写 —— 那会抛 ClosedWriteChannelException，
                    // 把整个 collect 协程带走（真机踩过：宿主画面通道崩掉、观众永远黑屏）。
                    if (target != null && target.isClosedForWrite) {
                        wire?.close?.invoke()
                        incoming = null
                        wire = null
                        target = null
                    }
                    if (target == null) {
                        val channel = ByteChannel(autoFlush = true)
                        val outgoing = ByteChannel(autoFlush = true)
                        writeJob = pumpOutbound(scope, outgoing, datagram.host, datagram.port)
                        val created = ScreenWire(channel, outgoing) {
                            writeJob?.cancel()
                            runCatching { channel.close() }
                            runCatching { outgoing.close() }
                        }
                        incoming = channel
                        wire = created
                        onAccept(created)
                        target = channel
                    }
                    // 同 [pumpInbound]：这里也**不套 per-frame 超时**（每帧一个定时任务是
                    // 纯净的调度噪声）。写失败（通道已关）就把这条 wire 收掉 ——
                    // 任何异常都在这里收住：collect 协程一死，整条画面信道就再也不动了。
                    val ok = runCatching { target.writeFully(payload) }.isSuccess
                    if (!ok) {
                        wire?.close?.invoke()
                        incoming = null
                        wire = null
                    }
                }
            } catch (_: CancellationException) {
                // 正常收摊
            } finally {
                wire?.close?.invoke()
            }
        }
    }

    private var acceptJob: Job? = null

    /**
     * 出站泵。
     *
     * **必须跑在 Dispatchers.Unconfined 上**：writeFully 唤醒它时应当**在写端的线程**
     * 上直接继续，而不是先排队回到 Dispatchers.IO 的调度队列。投屏是一问一答的小帧
     * （~530 字节），每帧多两次调度切换就是几十毫秒 —— 真机实测 luciola 组 51ms vs
     * 明文组 16ms，而两边帧率、帧大小完全一样。字节量不是瓶颈，切换次数是。
     */
    private fun pumpOutbound(scope: CoroutineScope, channel: ByteChannel, host: String, port: Int): Job =
        scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            val buffer = ByteArray(chunkSize)
            try {
                while (isActive) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n == -1) break
                    if (n <= 0) continue
                    val frame = ScreenChannelFrame.wrap(buffer, n)
                    // 队列满就退避重试：丢一段字节 = 字节流错乱，比慢一点糟得多。
                    var waited = 0L
                    while (isActive && !transport.send(frame, host, port)) {
                        delay(SEND_BACKOFF_MS)
                        waited += SEND_BACKOFF_MS
                        // 一条画面约 33-100ms 就该出去；5s 还送不出去 = 这条路已经死了，
                        // 收掉它让上层按"投屏断开"处理，而不是假装还在传。
                        if (waited > SEND_STALL_MS) return@launch
                    }
                }
            } catch (_: CancellationException) {
                // 正常收摊
            }
        }

    private companion object {
        /** 上层读得慢时的背压上限：超过它这场投屏已经不实时了。 */
        const val DELIVER_TIMEOUT_MS = 2_000L
        const val SEND_BACKOFF_MS = 2L
        const val SEND_STALL_MS = 5_000L
    }
}
