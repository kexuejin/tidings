// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.availableForRead
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
        // 入站也 Unconfined：它由 Rust 的回调线程驱动，喂进 ByteChannel 是纯内存拷贝，
        // 没有阻塞点，不该再排一次调度队列（IO 版本实测 rx 52ms / 4.8fps，明显更差）。
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
     * 出站泵。**必须跑在 Dispatchers.Unconfined 上**（试过 IO，更差）。
     *
     * Unconfined 会让泵在 `writeFully` resume 它的那条线程上内联跑完 —— 也就是采集
     * 线程。代价是「采样→写完」(tx) 这个读数把加密 + JNA + TCP 写的耗时也算进去了，
     * 真机上因此显示 22ms（明文通路 2ms），**看起来像「加密慢 20ms」**。
     *
     * **但那是真实的并行收益，不是浪费**。同一台机器实测（同一条链路、同一段共享）：
     *
     *   泵线程     rx 延迟   帧率
     *   Unconfined  39ms     9.3 fps
     *   IO          52ms     4.8 fps   ← 掉档：接收端自适应降到 5fps
     *
     * 放回 IO 后泵与采集变成串行，端到端反而更差、还触发 ScreenRate 降档。
     * 所以保留 Unconfined；要量「加密本身有多贵」请换一个隔离的量具（例如同一帧
     * 在两条通路上只测 enc+send 的那一段），不要用 tx 读数比。
     */
    /**
     * 出站泵：**把已到达的字节一次取完再 send**（一帧尽量只走一次 send）。
     *
     * ## 为什么这是关键
     *
     * 真机分段计时（\`txseg\` 的 wrap/send 拆分）给出：
     *
     * \`\`\`
     * wrap（封套拷贝）       31-39 µs/段   ← 可忽略
     * send（uniffi+JNA+入队） 3.4-3.9 ms/段  ← 全部代价在这里
     * \`\`\`
     *
     * 而一帧 49KiB 会被切成 ~7 段 ⇒ 7 × 3.5ms ≈ 24ms，正是投屏第二段 16-19ms 的主体。
     * （**同一个 send 在 Mac 上只要 130µs —— 慢 26 倍的根源是 Android 的 JNA 跨界**，
     * 这也解释了为什么老拿 Mac 的量具会得出错误结论。）
     *
     * 所以唯一有效的优化是**减少 send 次数**：把"这一次已经到达的字节"尽可能一次取完、
     * 一次 send，而不是按 \`readAvailable\` 的内部块（~7KB）切碎。
     *
     * ## 关键约束（四次失败换来的）
     *
     * - \`readAvailable(buffer, offset>0, len)\` 会**试图填满**，不能用来"继续读"
     *   （第 8a 轮失败原因）。这里每次读都用 \`offset == 0\` 的临时块。
     * - \`withTimeoutOrNull\` 在 Unconfined 上会让出、把泵弄死（第 5 轮失败原因）。
     *   这里只用 \`availableForRead\`（非挂起）判断"还有没有现成的"。
     * - 不做"凑够一帧"的等待：只取**已经到的**，取不到就回到正常的挂起读（那是唯一
     *   合法的等待点）。
     */
    private fun pumpOutbound(scope: CoroutineScope, channel: ByteChannel, host: String, port: Int): Job =
        scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            // **留出 8 字节封套头**：正文从 +8 起累加，发之前用 wrapInPlace 就地写头，
            // 省掉「再分配一个 49KiB 数组 + 拷一次」—— 真机实测那一步是 211µs/帧。
            val buffer = ByteArray(ScreenChannelFrame.HEADER_BYTES + chunkSize)
            val scratch = ByteArray(chunkSize)
            var filled = 0
            try {
                while (isActive) {
                    // 一次挂起读（offset 恒 0），拿到多少算多少。
                    val n = channel.readAvailable(scratch, 0, scratch.size)
                    if (n == -1) break
                    if (n <= 0) continue
                    scratch.copyInto(buffer, ScreenChannelFrame.HEADER_BYTES + filled, 0, n)
                    filled += n

                    // **把已经到达的剩余字节一次取完**（每次 offset 恒 0 的临时块 → 累加）。
                    // availableForRead 是**非挂起**的属性：拿到 0 就说明现在没有更多了，
                    // 不引入任何等待。这一循环把"一帧的 7 个内部块"收成一次 send。
                    while (true) {
                        val pending = channel.availableForRead
                        if (pending <= 0) break
                        val want = minOfOf(pending, scratch.size)
                        val more = channel.readAvailable(scratch, 0, want)
                        if (more <= 0) break
                        if (filled + more > buffer.size - ScreenChannelFrame.HEADER_BYTES) {
                            // 缓冲不够（不该发生：chunkSize 远大于一帧上限）——先发掉已有的。
                            sendOne(buffer, filled, host, port) ?: return@launch
                            filled = 0
                        }
                        scratch.copyInto(buffer, ScreenChannelFrame.HEADER_BYTES + filled, 0, more)
                        filled += more
                    }
                    sendOne(buffer, filled, host, port) ?: return@launch
                    filled = 0
                }
            } catch (_: CancellationException) {
                // 正常收摊
            }
        }

    /** 发一次；返回 false = 这条路已经死了（调用方收摊）。 */
    /** 发一次（正文从 [ScreenChannelFrame.HEADER_BYTES] 起，封套头就地写）。 */
    private suspend fun sendOne(buf: ByteArray, len: Int, host: String, port: Int): Boolean? {
        if (len <= 0) return true
        val wrapStart = System.nanoTime()
        // 取"正好这一段"的视图：send 需要一个独立 ByteArray，但用 copyOfRange 直接产出
        // HEADER+正文 的目标数组，**省掉"就地写头再整体拷一次"**（那一拷是 49KiB）。
        val total = ScreenChannelFrame.HEADER_BYTES + len
        val frame = ByteArray(total)
        buf.copyInto(frame, 0, ScreenChannelFrame.HEADER_BYTES, ScreenChannelFrame.HEADER_BYTES + len)
        ScreenChannelFrame.wrapInPlace(frame, 0, len)
        val wrapNs = System.nanoTime() - wrapStart
        var waited = 0L
        var backoffs = 0
        val sendStart = System.nanoTime()
        while (!transport.send(frame, host, port)) {
            backoffs += 1
            delay(SEND_BACKOFF_MS)
            waited += SEND_BACKOFF_MS
            if (waited > SEND_STALL_MS) return null
        }
        backoffStats.onCount(backoffs)
        sendStats.onSplit3(wrapNs / 1_000, (System.nanoTime() - sendStart) / 1_000, len.toLong())
        return true
    }

    private fun minOfOf(a: Int, b: Int): Int = if (a < b) a else b
    /**
     * **分段量具**：把「封套拷贝」与「加密 + JNA + TCP 写」分开计。
     *
     * 为什么要单独一个：上一轮拿「采样→写完」(tx) 去比较两条通路，结论连着错了两次
     * （先怪 ByteChannel 背压、再怪 Unconfined 内联）—— 因为那个读数里混着采集与
     * 并行的工作。只有这里的 `send` 这一段是"加密信道本身"的代价。
     */
    private val sendStats = SegmentStats("txseg")

    /** 退避统计（10s 一行）：每次 send 前的 delay(2) 次数。 */
    private val backoffStats = BackoffStats("txbackoff")

    private companion object {
        /** 上层读得慢时的背压上限：超过它这场投屏已经不实时了。 */
        const val DELIVER_TIMEOUT_MS = 2_000L
        const val SEND_BACKOFF_MS = 2L
        const val SEND_STALL_MS = 5_000L


    }
}
