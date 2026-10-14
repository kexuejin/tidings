// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Limits
import app.relay.core.protocol.ScreenImageMetadata
import app.relay.core.protocol.ScreenMode
import app.relay.core.protocol.ScreenTcpFrame
import app.relay.core.protocol.TcpFrames
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
import app.relay.core.ioDispatcher
import app.relay.core.protocol.TcpFrame
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
 * 自适应帧率调节器，对齐茶话间 ScreenRate。
 */
class ScreenRate {
    var mode: ScreenMode = ScreenMode.Auto
        private set

    var fps: Int = 10
        private set

    private var slow = 0
    private var stableSince: Long? = null
    private var lastDown: Long = Long.MIN_VALUE

    fun setMode(newMode: ScreenMode) {
        mode = newMode
        fps = when (newMode) {
            ScreenMode.Economy -> 3
            ScreenMode.Standard -> 5
            ScreenMode.Smooth, ScreenMode.Auto -> 10
        }
        slow = 0
        stableSince = null
        lastDown = Long.MIN_VALUE
    }

    fun observe(durationMs: Long, nowMs: Long) {
        if (mode != ScreenMode.Auto) return
        val threshold = ((1000.0 / fps) * 0.8).toLong()
        if (durationMs > threshold) {
            slow += 1
        } else {
            slow = 0
        }
        if (slow >= 3 && fps > 3) {
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
        if (nowMs - lastDown >= 10_000L && nowMs - stable >= 10_000L) {
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
    private val socket: Socket,
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

    fun start() {
        creditedAt = app.relay.core.nowMillis()
        // 只在调用方没给通道时才自己开 —— 已绑定的 socket 上再开必抛。
        if (read == null) read = socket.openReadChannel()
        if (write == null) write = socket.openWriteChannel(autoFlush = true)

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
        if (len <= 0 || len > 64 * 1024) return null
        val body = ByteArray(len)
        rc.readFully(body, 0, len)
        val decoded = TcpFrames.decodeBody(body.decodeToString())
        if (decoded is app.relay.core.protocol.TcpFrameDecode.Screen && decoded.screenFrame is ScreenTcpFrame.Next) {
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

        val now = app.relay.core.nowMillis()
        val intervalElapsed = now - lastSampleAt
        if (intervalElapsed < Limits.SCREEN_MIN_FRAME_INTERVAL_MS) {
            delay(Limits.SCREEN_MIN_FRAME_INTERVAL_MS - intervalElapsed)
        }
        if (stopped) return

        lastSampleAt = app.relay.core.nowMillis()
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
        val curTime = app.relay.core.nowMillis()
        val delta = curTime - creditedAt
        creditedAt = curTime
        credit = min(Limits.SCREEN_MAX_FRAME_BYTES.toDouble(), credit + delta * Limits.SCREEN_MAX_BYTES_PER_SECOND / 1000.0)
        if (bytes.size > credit) {
            val waitMs = max(0L, ((bytes.size - credit) * 1000.0 / Limits.SCREEN_MAX_BYTES_PER_SECOND).toLong())
            if (waitMs > 0) delay(waitMs)
        }
        if (stopped) return

        val atWrite = app.relay.core.nowMillis()
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
            wc.writeFully(encodedHeader)
            wc.writeFully(bytes)
        }
        busy = false
    }

    fun stop() {
        if (stopped) return
        stopped = true
        pumpJob?.cancel()
        pumpJob = null
        runCatching { socket.close() }
    }

    private fun end(reason: String) {
        if (stopped) return
        stop()
        onEnd(reason)
    }
}

/**
 * 屏幕接收器（Viewer 侧）：
 * 建立 TCP 连接，发出 screen-open 帧，循环发出 screen-next 并接收解码 JPEG 帧。
 */
class ScreenReceiver(
    private val host: String,
    private val port: Int,
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
    private var socket: Socket? = null
    private var selector: SelectorManager? = null
    private var receiverJob: Job? = null

    val targetFps: Int get() = if (minimized) 3 else rate.fps

    fun start() {
        receiverJob = scope.launch(ioDispatcher) {
            try {
                val sm = SelectorManager(ioDispatcher).also { selector = it }
                val sock = withTimeoutOrNull(Limits.SCREEN_CONNECT_TIMEOUT_MS) {
                    aSocket(sm).tcp().connect(InetSocketAddress(host, port))
                }
                if (sock == null) {
                    end("timeout")
                    return@launch
                }
                socket = sock
                val read = sock.openReadChannel()
                val write = sock.openWriteChannel(autoFlush = true)

                // 连上后发送 screen-open 握手
                val openFrame = ScreenTcpFrame.Open(from = selfId, sessionId = sessionId, token = token)
                write.writeFully(TcpFrames.encode(openFrame))

                // 等待 screen-ready 帧
                val readyFrame = withTimeoutOrNull(Limits.SCREEN_CONNECT_TIMEOUT_MS) {
                    readNextControlFrame(read)
                }
                if (readyFrame !is ScreenTcpFrame.Ready || readyFrame.sessionId != sessionId) {
                    end("protocol-error")
                    return@launch
                }
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
                    val now = app.relay.core.nowMillis()
                    if (!minimized) {
                        rate.observe(now - sentAt, now)
                    }
                    onTarget(targetFps)

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
        val now = app.relay.core.nowMillis()
        val interval = max(0L, (1000.0 / targetFps).toLong() - (now - sentAt))
        if (interval > 0) delay(interval)
        if (stopped) return

        waiting = true
        sentAt = app.relay.core.nowMillis()
        sequence += 1
        val nextFrame = ScreenTcpFrame.Next(sessionId = sessionId, seq = sequence)
        write.writeFully(TcpFrames.encode(nextFrame))
    }

    private suspend fun readNextControlFrame(read: ByteReadChannel): ScreenTcpFrame? {
        val head = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES)
        read.readFully(head, 0, head.size)
        val len = TcpFrame.decodeLength(head)
        if (len <= 0 || len > 64 * 1024) return null
        val body = ByteArray(len)
        read.readFully(body, 0, len)
        val decoded = TcpFrames.decodeBody(body.decodeToString())
        if (decoded is app.relay.core.protocol.TcpFrameDecode.Screen) {
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
        runCatching { socket?.close() }
        socket = null
        runCatching { selector?.close() }
        selector = null
    }

    private fun end(reason: String) {
        if (stopped) return
        stop()
        onEnd(reason)
    }
}
