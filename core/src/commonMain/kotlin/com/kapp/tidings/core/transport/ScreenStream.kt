// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.FileTransfer
import com.kapp.tidings.core.protocol.Limits
import com.kapp.tidings.core.protocol.ScreenImageMetadata
import com.kapp.tidings.core.protocol.ScreenMode
import com.kapp.tidings.core.protocol.ScreenTcpFrame
import com.kapp.tidings.core.protocol.TcpFrames
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import com.kapp.tidings.core.ioDispatcher
import com.kapp.tidings.core.protocol.TcpFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

/**
 * 屏幕通道的传输抽象：两条字节流 + 关闭句柄。
 *
 * 明文 socket（局域网原路径）与跨网 TLS 的 [RawPullConnection]（`docs/cross-network-file-screen-design.md`
 * 选项 B：独立 TCP 连接、原样复用 screen-* 协议、TLS/双向 pin 由拨号层提供）都适配它 ——
 * screen-* 协议只在这两条流上跑,不关心底下有没有加密。
 */
class ScreenWire(
    val read: ByteReadChannel,
    val write: ByteWriteChannel,
    val close: () -> Unit,
)


/**
 * 一段投屏会话的**可复核读数**（帧数 / 帧率 / 延迟 / 字节数），每 [WINDOW_MS] 打一行。
 *
 * 为什么要在生产代码里留这行日志：goal 第 (4) 步的判据是「真机连续 5 分钟帧率/延迟
 * 不低于 legacy 的 90%」，而这两条通路上**没有任何别的可观测口径** —— 不打印，
 * 判据就只能靠肉眼看画面流畅度。rx 侧的 latency 是「发 screen-next → 收齐这一帧」，
 * 正是用户能感觉到的那个量；tx 侧是「采样 → 写完」。
 */
class ScreenStats(private val tag: String) {
    private var frames = 0
    private var bytes = 0L
    private var windowStart = com.kapp.tidings.core.nowMillis()
    private var windowFrames = 0
    private var latSumMs = 0L
    private var latMaxMs = 0L

    fun onFrame(bytes: Long, latencyMs: Long) {
        frames += 1
        windowFrames += 1
        this.bytes += bytes
        latSumMs += latencyMs
        if (latencyMs > latMaxMs) latMaxMs = latencyMs
        val now = com.kapp.tidings.core.nowMillis()
        val elapsed = now - windowStart
        if (elapsed < WINDOW_MS) return
        val secs = elapsed / 1000.0
        println(
            "[screen] " + tag + " frames=" + frames +
                " fps=" + fmt(windowFrames / secs) +
                " latAvg=" + fmt(latSumMs.toDouble() / windowFrames) + "ms" +
                " latMax=" + latMaxMs + "ms" +
                " bytes=" + bytes,
        )
        windowStart = now
        windowFrames = 0
        latSumMs = 0
        latMaxMs = 0
    }

    private fun fmt(value: Double): String {
        val scaled = (value * 100).toLong()
        return (scaled / 100).toString() + "." + ((scaled % 100) + 100).toString().substring(1)
    }

    private companion object {
        /** 真机 5 分钟验收：10s 一行既够密集，也不会把日志刷爆。 */
        const val WINDOW_MS = 10_000L
    }
}

/** 一次连接尝试：`open()` 返回 null = 这条路不通（调用方按序试下一条）。 */
fun interface ScreenWireOpener {
    suspend fun open(): ScreenWire?
}

/**
 * 明文直连尝试（局域网原路径,今天的行为逐字保留）。
 *
 * selector 的生命周期跟着 wire：连上后由 [ScreenWire.close] 收,连不上当场收。
 */
fun plainScreenWireOpener(host: String, port: Int): ScreenWireOpener = ScreenWireOpener {
    val sm = SelectorManager(ioDispatcher)
    // **连不上必须返回 null，不能让异常穿出去。**
    // 契约写在 [ScreenWireOpener]：null = 这条路不通、调用方按序试下一条。
    // 而「这条不通」最常见的形态就是 ConnectException（对端没监听）——
    // luciola 模式退掉 17879 之后，局域网对端的明文尝试**必然**是这一条；
    // 让它抛出去，[ScreenReceiver] 的整个循环就断了，后续那条加密画面会话
    // 根本没机会被尝试（用户看到的是「投屏连接失败」，而其实新通路是好的）。
    val sock = runCatching {
        withTimeoutOrNull(Limits.SCREEN_CONNECT_TIMEOUT_MS) {
            aSocket(sm).tcp().connect(InetSocketAddress(host, port))
        }
    }.getOrNull()
    if (sock == null) {
        runCatching { sm.close() }
        return@ScreenWireOpener null
    }
    ScreenWire(
        read = sock.openReadChannel(),
        write = sock.openWriteChannel(autoFlush = true),
        close = {
            runCatching { sock.close() }
            runCatching { sm.close() }
        },
    )
}

/**
 * 读一条长度前缀帧并解出 screen-open —— 跨网 TLS 监听的首帧识别。
 *
 * 与 [TransferServer] 的首帧分发同一套解码；识别不了（非 screen 帧 / 坏长度）返回 null,
 * 调用方按「这条连接不是投屏」关闭。
 */
internal suspend fun readFirstScreenOpen(read: ByteReadChannel): ScreenTcpFrame.Open? {
    // 半截帧（对端连上就断）会让 readFully 抛 EOF 而不是返回 0 —— 那与本函数
    // "识别不了返回 null" 的契约不一致。这里自己收住，不把关连接的责任推给调用方。
    return try {
        val head = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES)
        read.readFully(head, 0, head.size)
        val len = TcpFrame.decodeLength(head)
        if (len <= 0 || len > FileTransfer.MAX_FRAME) return null
        val body = ByteArray(len)
        read.readFully(body, 0, len)
        val decoded = TcpFrames.decodeBody(body.decodeToString())
        (decoded as? com.kapp.tidings.core.protocol.TcpFrameDecode.Screen)?.screenFrame as? ScreenTcpFrame.Open
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        null
    }
}

/**
 * 自适应帧率调节器，对齐茶话间 ScreenRate。
 */
class ScreenRate {
    var mode: ScreenMode = ScreenMode.Auto
        private set

    var fps: Int = 10
        private set

    private var slow = 0
    private var stableSince: Long? = null
    /**
     * 上次降档时刻。**必须可空**：用 Long.MIN_VALUE 当哨兵时，nowMs - lastDown
     * 会溢出成负数，10 秒判据恒为 false —— 今天不可达（Auto 起步就是 10fps），
     * 但一旦允许低档起步，升档会永远不发生。null = 还没降过档，按不满足处理。
     */
    private var lastDown: Long? = null

    fun setMode(newMode: ScreenMode) {
        mode = newMode
        fps = when (newMode) {
            ScreenMode.Economy -> 3
            ScreenMode.Standard -> 5
            ScreenMode.Smooth, ScreenMode.Auto -> 10
        }
        slow = 0
        stableSince = null
        lastDown = null
    }

    fun observe(durationMs: Long, nowMs: Long) {
        if (mode != ScreenMode.Auto) return
        val threshold = ((1000.0 / fps) * 0.8).toLong()
        if (durationMs > threshold) {
            slow += 1
        } else {
            slow = 0
        }
        // 地板由下面的阶梯（10→5→3）自身守住；多余的 fps > 3 已删，免得读代码的人以为地板靠它。
        if (slow >= 3) {
            fps = if (fps == 10) 5 else 3
            slow = 0
            stableSince = null
            lastDown = nowMs
            return
        }
        val faster = if (fps == 3) 5 else 10
        val fasterThreshold = ((1000.0 / faster) * 0.6).toLong()
        if (fps == 10 || durationMs >= fasterThreshold) {
            stableSince = null
            return
        }
        if (stableSince == null) {
            stableSince = nowMs
        }
        val stable = stableSince ?: nowMs
        val sinceDown = lastDown?.let { nowMs - it }
        if (sinceDown != null && sinceDown >= 10_000L && nowMs - stable >= 10_000L) {
            fps = faster
            stableSince = null
            slow = 0
        }
    }
}

/**
 * 屏幕发送器（Sharer 侧）：
 * 响应 screen-next 帧并以限流配额发送 screen-frame + JPEG 裸流。
 *
 * **[readChannel]/[writeChannel] 由调用方注入**，默认 null 时退回到自己开通道的行为
 * （仅适用于这条 socket 没被别处占用的场景）。入站 `screen-open` 交接来的 socket 必须
 * 把监听器已经打开的通道传进来：ktor 的 `SocketBase` 用 `compareAndSet(null, job)` 把读、写
 * 通道**一次性**绑在 `writerJob`/`readerJob` 上，第二次 `openReadChannel()`/`openWriteChannel()`
 * 直接抛 `IllegalStateException("reading|writing channel has already been set")`
 * （ktor-network-jvm 3.3.1 `javap -p -c` 实证）。真机 E2E 里，Sharer 一收到
 * 首帧就抛这个异常、socket 随即被关，Viewer 侧表现为 `reason:"disconnected"`。
 */
class ScreenSender(
    /** 传输通道（明文 socket 或跨网 TLS 都是它）；本类只在 stop 时负责关。 */
    val wire: ScreenWire,
    private val sessionId: String,
    private val sample: suspend (seq: Long) -> ByteArray?,
    private val onEnd: (reason: String) -> Unit,
    private val scope: CoroutineScope,
    private val readChannel: ByteReadChannel? = null,
    private val writeChannel: ByteWriteChannel? = null,
) {
    private var stopped = false
    private var busy = false
    private var sequence = 0L
    private var lastSampleAt = 0L
    private var credit = Limits.SCREEN_MAX_FRAME_BYTES.toDouble()
    private var creditedAt = 0L
    private var read: ByteReadChannel? = readChannel
    private var write: ByteWriteChannel? = writeChannel
    private var pumpJob: Job? = null

    /** 发送侧读数（10s 一行，见 [ScreenStats]）。 */
    private val stats = ScreenStats("tx")

    fun start() {
        creditedAt = com.kapp.tidings.core.nowMillis()
        // 只在调用方没给通道时才自己开 —— 已绑定的 socket 上再开必抛。
        if (read == null) read = wire.read
        if (write == null) write = wire.write

        pumpJob = scope.launch(ioDispatcher) {
            try {
                // 发送 screen-ready 握手帧
                val readyFrame = ScreenTcpFrame.Ready(sessionId = sessionId)
                write?.writeFully(TcpFrames.encode(readyFrame))

                while (isActive && !stopped) {
                    val frame = withTimeoutOrNull(Limits.SCREEN_IDLE_TIMEOUT_MS) {
                        readScreenNextFrame()
                    }
                    if (frame == null) {
                        end("timeout")
                        return@launch
                    }
                    handleNext(frame)
                }
            } catch (_: CancellationException) {
                // ignore
            } catch (_: Exception) {
                end("disconnected")
            }
        }
    }

    private suspend fun readScreenNextFrame(): ScreenTcpFrame.Next? {
        val rc = read ?: return null
        val head = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES)
        rc.readFully(head, 0, head.size)
        val len = TcpFrame.decodeLength(head)
        if (len <= 0 || len > FileTransfer.MAX_FRAME) return null
        val body = ByteArray(len)
        rc.readFully(body, 0, len)
        val decoded = TcpFrames.decodeBody(body.decodeToString())
        if (decoded is com.kapp.tidings.core.protocol.TcpFrameDecode.Screen && decoded.screenFrame is ScreenTcpFrame.Next) {
            return decoded.screenFrame
        }
        return null
    }

    private suspend fun handleNext(frame: ScreenTcpFrame.Next) {
        if (stopped) return
        if (frame.sessionId != sessionId || frame.seq != sequence + 1 || busy) {
            end("protocol-error")
            return
        }
        busy = true
        sequence = frame.seq

        val now = com.kapp.tidings.core.nowMillis()
        val intervalElapsed = now - lastSampleAt
        if (intervalElapsed < Limits.SCREEN_MIN_FRAME_INTERVAL_MS) {
            delay(Limits.SCREEN_MIN_FRAME_INTERVAL_MS - intervalElapsed)
        }
        if (stopped) return

        lastSampleAt = com.kapp.tidings.core.nowMillis()
        val bytes = sample(sequence)
        if (bytes == null) {
            end("capture-ended")
            return
        }
        val size = ScreenImageMetadata.screenImageSize(bytes)
        if (size == null) {
            end("capture-ended")
            return
        }

        // 令牌桶限流检查
        val curTime = com.kapp.tidings.core.nowMillis()
        val delta = curTime - creditedAt
        creditedAt = curTime
        credit = min(Limits.SCREEN_MAX_FRAME_BYTES.toDouble(), credit + delta * Limits.SCREEN_MAX_BYTES_PER_SECOND / 1000.0)
        if (bytes.size > credit) {
            val waitMs = max(0L, ((bytes.size - credit) * 1000.0 / Limits.SCREEN_MAX_BYTES_PER_SECOND).toLong())
            if (waitMs > 0) delay(waitMs)
        }
        if (stopped) return

        val atWrite = com.kapp.tidings.core.nowMillis()
        val d2 = atWrite - creditedAt
        creditedAt = atWrite
        credit = min(Limits.SCREEN_MAX_FRAME_BYTES.toDouble(), credit + d2 * Limits.SCREEN_MAX_BYTES_PER_SECOND / 1000.0) - bytes.size

        val header = ScreenTcpFrame.Frame(
            sessionId = sessionId,
            seq = sequence,
            width = size.width,
            height = size.height,
            len = bytes.size,
        )
        val encodedHeader = TcpFrames.encode(header)
        val wc = write
        if (wc != null) {
            // **一次写完，不拆两次 writeFully。**
            // 明文 socket 上这是两次 write(2)（同一个报文段，几乎无代价）；但加密信道上
            // 每次 writeFully 都是一个**独立的加密帧 + 一次 flush**，拆两次就是每帧多一次
            // 往返的排队机会 —— 真机实测投屏 p50 因此多出 ~15-20ms。
            // 拼一拼只是多一次 ≤ 数十字节的拷贝，换来的是每帧少一个信道帧。
            val combined = ByteArray(encodedHeader.size + bytes.size)
            encodedHeader.copyInto(combined, 0)
            bytes.copyInto(combined, encodedHeader.size)
            wc.writeFully(combined)
        }
        stats.onFrame(bytes.size.toLong(), com.kapp.tidings.core.nowMillis() - lastSampleAt)
        busy = false
    }

    fun stop() {
        if (stopped) return
        stopped = true
        pumpJob?.cancel()
        pumpJob = null
        runCatching { wire.close() }
    }

    private fun end(reason: String) {
        if (stopped) return
        stop()
        onEnd(reason)
    }
}

/**
 * 屏幕接收器（Viewer 侧）：
 * 依序尝试连接（局域网明文第一、跨网 TLS 候选其后，顺序由调用方按
 * [CrossNetworkTransferPolicy.planScreen] 排好），发出 screen-open 帧并等 screen-ready，
 * 循环发出 screen-next 并接收解码 JPEG 帧。
 *
 * 「这条尝试可用」的判据是**握手完成**（收到 screen-ready）而不是 TCP 连上 ——
 * TLS 候选的 pin 校验失败、旧端不认识这条端口，都表现为连不上或握手超时，
 * 此时试下一条而不是整场报 timeout。
 */
class ScreenReceiver(
    private val attempts: List<ScreenWireOpener>,
    private val selfId: String,
    private val sessionId: String,
    private val token: String,
    private val display: suspend (seq: Long, bytes: ByteArray, width: Int, height: Int) -> Unit,
    private val onReady: () -> Unit,
    private val onTarget: (fps: Int) -> Unit,
    private val onEnd: (reason: String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val rate = ScreenRate()
    private var stopped = false
    private var ready = false
    private var waiting = false
    private var sequence = 0L
    private var sentAt = 0L
    private var minimized = false
    private var wire: ScreenWire? = null
    private var receiverJob: Job? = null

    /** 接收侧读数（10s 一行，见 [ScreenStats]）。 */
    private val stats = ScreenStats("rx")

    val targetFps: Int get() = if (minimized) 3 else rate.fps

    fun start() {
        receiverJob = scope.launch(ioDispatcher) {
            try {
                var connected: ScreenWire? = null
                for (attempt in attempts) {
                    if (stopped) return@launch
                    val candidate = attempt.open() ?: continue
                    // 发 screen-open 并等 ready —— 只有握手过的这条才算数。
                    val ok = runCatching {
                        candidate.write.writeFully(TcpFrames.encode(ScreenTcpFrame.Open(from = selfId, sessionId = sessionId, token = token)))
                        withTimeoutOrNull(Limits.SCREEN_CONNECT_TIMEOUT_MS) {
                            readNextControlFrame(candidate.read)
                        } is ScreenTcpFrame.Ready
                    }.getOrDefault(false)
                    if (ok) {
                        connected = candidate
                        break
                    }
                    runCatching { candidate.close() }
                }
                if (connected == null) {
                    // 一条都没握上手：对端没监听（旧端/跨网未启用）或不在同一张网。
                    end("timeout")
                    return@launch
                }
                wire = connected
                val read = connected.read
                val write = connected.write
                ready = true
                onReady()

                // 主流循环：调度 request -> 读 frame 头 -> 读 raw JPEG -> 回调 display
                while (isActive && !stopped) {
                    scheduleNext(write)

                    // 读 screen-frame 头
                    val frameHeader = withTimeoutOrNull(Limits.SCREEN_FRAME_TIMEOUT_MS) {
                        readNextControlFrame(read)
                    }
                    if (frameHeader !is ScreenTcpFrame.Frame || frameHeader.sessionId != sessionId || frameHeader.seq != sequence) {
                        end("protocol-error")
                        return@launch
                    }

                    // 读裸流 JPEG
                    val rawJpeg = ByteArray(frameHeader.len)
                    withTimeoutOrNull(Limits.SCREEN_FRAME_TIMEOUT_MS) {
                        read.readFully(rawJpeg, 0, frameHeader.len)
                    } ?: run {
                        end("protocol-error")
                        return@launch
                    }

                    val verifiedSize = ScreenImageMetadata.screenImageSize(rawJpeg)
                    if (verifiedSize == null || verifiedSize.width != frameHeader.width || verifiedSize.height != frameHeader.height) {
                        end("protocol-error")
                        return@launch
                    }

                    waiting = false
                    val now = com.kapp.tidings.core.nowMillis()
                    val frameRttMs = now - sentAt
                    if (!minimized) {
                        rate.observe(frameRttMs, now)
                    }
                    onTarget(targetFps)
                    stats.onFrame(frameHeader.len.toLong(), frameRttMs)

                    display(frameHeader.seq, rawJpeg, verifiedSize.width, verifiedSize.height)
                }
            } catch (_: CancellationException) {
                // ignore
            } catch (_: Exception) {
                end("disconnected")
            }
        }
    }

    private suspend fun scheduleNext(write: ByteWriteChannel) {
        val now = com.kapp.tidings.core.nowMillis()
        val interval = max(0L, (1000.0 / targetFps).toLong() - (now - sentAt))
        if (interval > 0) delay(interval)
        if (stopped) return

        waiting = true
        sentAt = com.kapp.tidings.core.nowMillis()
        sequence += 1
        val nextFrame = ScreenTcpFrame.Next(sessionId = sessionId, seq = sequence)
        write.writeFully(TcpFrames.encode(nextFrame))
    }

    private suspend fun readNextControlFrame(read: ByteReadChannel): ScreenTcpFrame? {
        val head = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES)
        read.readFully(head, 0, head.size)
        val len = TcpFrame.decodeLength(head)
        if (len <= 0 || len > FileTransfer.MAX_FRAME) return null
        val body = ByteArray(len)
        read.readFully(body, 0, len)
        val decoded = TcpFrames.decodeBody(body.decodeToString())
        if (decoded is com.kapp.tidings.core.protocol.TcpFrameDecode.Screen) {
            return decoded.screenFrame
        }
        return null
    }

    fun setMode(mode: ScreenMode) {
        rate.setMode(mode)
        onTarget(targetFps)
    }

    fun setMinimized(value: Boolean) {
        minimized = value
        onTarget(targetFps)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        receiverJob?.cancel()
        receiverJob = null
        runCatching { wire?.close() }
        wire = null
    }

    private fun end(reason: String) {
        if (stopped) return
        stop()
        onEnd(reason)
    }
}
