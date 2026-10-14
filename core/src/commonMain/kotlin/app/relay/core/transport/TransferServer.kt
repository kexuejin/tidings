// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.crypto.Sha256
import app.relay.core.fs.openRandomAccess
import app.relay.core.ioDispatcher
import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.ErrFrame
import app.relay.core.protocol.FileTransfer
import app.relay.core.protocol.PullOkFrame
import app.relay.core.protocol.ScreenTcpFrame
import app.relay.core.protocol.TcpControlFrame
import app.relay.core.protocol.TcpFrame
import app.relay.core.protocol.TcpFrameDecode
import app.relay.core.protocol.TcpFrameTypes
import app.relay.core.protocol.TcpFrames
import app.relay.core.protocol.WaitFrame
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer

/** 一条可被对端拉取的文件。 */
data class OutgoingFile(
    val fileId: String,
    val absPath: String,
    val size: Long,
)

/**
 * 发送侧的可拉取清单。
 *
 * [resolve] 返回 null 就是拒绝对端：只有**已经被接受**的 transfer 才该返回非 null。
 * 这是发送侧唯一的授权点——对端拿到 transferId 并不等于有权拉。
 */
interface OutgoingLookup {
    fun resolve(transferId: String, fileId: String): OutgoingFile?

    /**
     * 对端在这条 TCP 连接上发来了 `msg` 帧（超长文本走 TCP 的入口）。
     * 返回 true 表示收下并回 ACK。
     */
    suspend fun receiveMessage(envelope: Envelope, remoteAddress: String): Boolean = false

    /**
     * 对端在这条 TCP 连接的首帧发来了 `screen-open`。
     * 返回 true 表示由投屏/协助处理器接管此 socket（[TransferServer] 退出对此 socket 的读取循环）。
     *
     * **[read]/[write] 是本端已经打开好的通道，处理器必须直接用它们。** 不能自己再调
     * `socket.openReadChannel()` / `socket.openWriteChannel()`：ktor 的 `SocketBase` 把读、写
     * 通道各自绑在 `writerJob` / `readerJob` 字段上，`attachForReading` / `attachForWriting`
     * 用 `compareAndSet(null, job)` 做**一次性**绑定，第二次调用直接抛
     * `IllegalStateException("reading|writing channel has already been set")`
     * （已用 `javap -p -c io.ktor.network.sockets.SocketBase` 在 ktor-network-jvm 3.3.1
     * 上实证；`Socket` 接口上也没有任何取回已绑定通道的方法）。
     *
     * 真机后果：处理器一进来就抛，异常顺着 [TransferServer.serve] 冒到监听循环被咽掉，
     * socket 随即被关，Viewer 侧看到 `reason:"disconnected"`、Sharer 侧报「连接超时/已断开」
     * （E2E，第三个根因）。
     */
    suspend fun openScreen(
        socket: Socket,
        open: ScreenTcpFrame.Open,
        remoteAddress: String,
        read: ByteReadChannel,
        write: ByteWriteChannel,
    ): Boolean = false

    /**
     * 对端是否声明了 `tw1`（能收 wait 帧）。
     *
     * 老端遇到不认识的帧类型会直接断链，所以 **wait 只能发给 tw1 对端**。
     */
    fun supportsWait(peerId: String): Boolean = false
}

/** 发送侧护栏。默认值与 TS `DEFAULT_SERVER_LIMITS` 一致。 */
data class TransferServerLimits(
    val maxConcurrentStreams: Int = 3,
    val maxConnections: Int = 256,
    val handshakeTimeoutMs: Long = 15_000L,
    val idleTimeoutMs: Long = 60_000L,
    val waitHeartbeatMs: Long = FileTransfer.PULL_WAIT_HEARTBEAT,
)

/** 发送侧对外事件。 */
sealed interface TransferServerEvent {
    /** 某文件已发出去 [sent] 字节（含续传起点之前的 offset）。 */
    data class Progress(
        val transferId: String,
        val fileId: String,
        val sent: Long,
        val size: Long,
    ) : TransferServerEvent

    /** 收到 `finish`，一次 transfer 结束。 */
    data class Served(val transferId: String) : TransferServerEvent

    /** 连接断开且该 transfer 已无在跑的流。 */
    data class Disconnected(val transferId: String) : TransferServerEvent

    /** 端口绑定失败等非致命错误。绑定失败**不抛异常**，只发这个事件。 */
    data class DiagnosticError(val message: String) : TransferServerEvent
}

/**
 * 入站 TCP 监听器：在一个端口上同时服务长文本（`msg`）与文件拉取（`pull`）。
 *
 * 逐条对齐 TS `src/main/net/transfer.ts` 的 `TransferServer`：
 * - **拉取式**：发送方不推，只等对端 `pull`；`pull-ok` 之后紧跟裸字节流；
 * - 首帧是 `screen-open` 的连接交给 [OutgoingLookup.openScreen]：返回 true 表示投屏/协助
 *   处理器接管此 socket（服务端退出读取循环），返回 false（没有待接入的会话、token 不符）
 *   则断链 —— 等价于 TS `openScreen` 返回 null 时 destroy；
 * - 非 `pull` 的帧、以及正在忙时的 `pull`，一律回 `err reason:"busy"`；
 * - `offset === 0` 走**边发边哈希**的快路径（整文件预读会让 UI 长时间显示 0B）；
 *   `offset > 0` 走续传路径：另起一条整文件哈希流，数据流与哈希**都**结束才发 `done`。
 *
 * 自持 [SelectorManager]，由持有者调 [stop] 释放。
 */
class TransferServer(
    private val scope: CoroutineScope,
    private val lookup: OutgoingLookup,
    private val bindAddress: String? = null,
    private val limits: TransferServerLimits = TransferServerLimits(),
) {

    private val _events = MutableSharedFlow<TransferServerEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 进度/完成/诊断事件。订阅者慢时丢最旧的，不反压发送路径。 */
    val events: SharedFlow<TransferServerEvent> = _events.asSharedFlow()

    private var selector: SelectorManager? = null
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    /**
     * 实际绑上的端口；未启动时为 null。
     *
     * 传 0 给 [start] 表示"由系统分配"（测试用），此时**只有这里**知道真实端口 ——
     * 与 [UdpTransport.boundPort] 同一个约定。测试里不要再用
     * "先绑 0 拿到端口、关掉、再绑同一个端口"的写法：那两步之间端口可能被抢走或留在
     * TIME_WAIT 里，症状是随机的一条 `绑定失败`（本项目在 iOS 模拟器上真的撞到过）。
     */
    var boundPort: Int? = null
        private set

    private val sockets = mutableSetOf<Socket>()
    private val socketsLock = Mutex()

    /** 每个 transfer 还在跑的流数量。到 0 时发 [TransferServerEvent.Disconnected]。 */
    private val activeTransfers = mutableMapOf<String, Int>()
    private val activeLock = Mutex()

    /** 同时最多 [TransferServerLimits.maxConcurrentStreams] 条文件流在跑。 */
    private val streamSlots = Semaphore(limits.maxConcurrentStreams)

    /**
     * 绑定 [port] 并开始收连接。传 0 表示由系统分配，实际端口读 [boundPort]。
     *
     * 返回 false 表示绑定失败（端口被占等），此时会发一条
     * [TransferServerEvent.DiagnosticError] 但**不抛异常**——监听器失败不该把整个节点带崩。
     */
    suspend fun start(port: Int): Boolean {
        if (server != null) return true
        val sel = SelectorManager(ioDispatcher)
        selector = sel
        val bound = try {
            aSocket(sel).tcp().bind(InetSocketAddress(bindAddress ?: "0.0.0.0", port))
        } catch (e: Exception) {
            sel.close()
            selector = null
            _events.tryEmit(
                TransferServerEvent.DiagnosticError(
                    e.message ?: e::class.simpleName ?: "TCP 监听绑定失败",
                ),
            )
            return false
        }
        server = bound
        boundPort = (bound.localAddress as? InetSocketAddress)?.port ?: port
        acceptJob = scope.launch { acceptLoop(bound) }
        return true
    }

    /** 该 transfer 是否还有流在跑。 */
    suspend fun isTransferActive(transferId: String): Boolean =
        activeLock.withLock { (activeTransfers[transferId] ?: 0) > 0 }

    /** 关掉监听与所有在跑连接。 */
    suspend fun stop() {
        acceptJob?.cancelAndJoin()
        acceptJob = null
        runCatching { server?.close() }
        server = null
        boundPort = null
        val open = socketsLock.withLock {
            val copy = sockets.toList()
            sockets.clear()
            copy
        }
        open.forEach { runCatching { it.close() } }
        selector?.let { runCatching { it.close() } }
        selector = null
    }

    private suspend fun acceptLoop(bound: ServerSocket) {
        while (scope.isActive) {
            val socket = try {
                bound.accept()
            } catch (e: Exception) {
                if (!scope.isActive || e is kotlinx.coroutines.CancellationException) {
                    return
                }
                _events.tryEmit(
                    TransferServerEvent.DiagnosticError(
                        e.message ?: e::class.simpleName ?: "TCP accept 异常",
                    ),
                )
                delay(50)
                continue
            }
            val count = socketsLock.withLock { sockets.size }
            if (count >= limits.maxConnections) {
                // 超过连接上限直接丢弃：这条连接连握手都不给，避免被一条连接占住一个槽。
                runCatching { socket.close() }
                continue
            }
            socketsLock.withLock { sockets += socket }
            scope.launch {
                var handedOff = false
                try {
                    handedOff = serve(socket)
                } catch (_: Exception) {
                    // 对端提前关闭 / 帧非法：单条连接失败不影响监听器。
                } finally {
                    if (handedOff) {
                        // 所有权已交给投屏处理器（等价于 TS 把 socket 交给 `screenFrames` 回调）。
                        // 这里绝不能关它 —— 此前无条件 `socket.close()` 会把刚接管的连接
                        // 当场掐断，症状是 ScreenSender 一启动就断。但仍要在它真正关闭后
                        // 把它从 [sockets] 摘掉，否则每个投屏会话都留下一条死连接（泄漏）；
                        // TS 的等价物是 `socket.on("close", () => this.sockets.delete(socket))`。
                        scope.launch {
                            runCatching { socket.socketContext.join() }
                            socketsLock.withLock { sockets -= socket }
                        }
                    } else {
                        socketsLock.withLock { sockets -= socket }
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }

    /**
     * 服务一条入站连接。返回 true 表示这条 socket 的所有权已交给
     * [OutgoingLookup.openScreen]（调用方**不得**再关它）。
     */
    private suspend fun serve(socket: Socket): Boolean {
        // TS 在这里 `socket.setNoDelay(true)`。ktor 3.3.1 的 SocketOptions 只在
        // connect()/listen() 时传入，accept 下来的 Socket 没有任何可写选项的入口
        // （SocketOptions 里也没有 tcpNoDelay 字段），所以这一项拿不到，只能不做。
        // 影响仅为小帧的延迟量级，分帧与正确性不受影响——不是静默忽略，是明确的取舍。
        val read = socket.openReadChannel()
        val write = socket.openWriteChannel(autoFlush = true)
        val writeLock = Mutex()

        var firstFrame = true
        while (true) {
            // 首帧等 handshakeTimeout，之后按 idleTimeout 收——与 TS 的两段超时一致。
            val timeout = if (firstFrame) limits.handshakeTimeoutMs else limits.idleTimeoutMs
            val text = withTimeoutOrNull(timeout) { readFrame(read) } ?: return false
            val decoded = TcpFrames.decodeBody(text)
            // 必须先留下"这一帧是不是首帧"再清标志：下面的 screen-open 分支要拿它判断。
            // TS 参考实现（`FrameReader` 回调里 `firstFrame && frame.type === "screen-open"`
            // 判断通过之后才 `firstFrame = false`）是在 **判断之后** 才清标志；此前这里先清后判，
            // 条件恒为 false ⇒ `lookup.openScreen` 永远不被调用，每条入站 screen-open
            // 连接都被 `return` 掉。真机 E2E 的表现是：Viewer 已经收到 accept、TCP 也连上了
            // （手机侧能抓到来自 192.168.2.82 的 TIME_WAIT），却始终等不到 `screen-ready`，
            // 于是先报「连接超时」、再报「连接已断开」（实测）。
            val isFirstFrame = firstFrame
            firstFrame = false

            when (decoded) {
                is TcpFrameDecode.Screen -> {
                    val frame = decoded.screenFrame
                    if (isFirstFrame && frame is ScreenTcpFrame.Open) {
                        val host = hostOfRemoteAddress(socket.remoteAddress.toString())
                        // 把已打开的 read/write 一并交出去：socket 的通道是**一次性绑定**的，
                        // 处理器自己再开必然抛 IllegalStateException（见 OutgoingLookup.openScreen）。
                        val handled = lookup.openScreen(socket, frame, host, read, write)
                        if (handled) return true
                    }
                    return false
                }

                is TcpFrameDecode.Msg -> {
                    val ok = runCatching {
                        // 必须是裸地址：registry 里存的就是裸地址，形状对不上会把每一帧
                        // 都当成地址漂移拒收（见 [hostOfRemoteAddress]）。
                        lookup.receiveMessage(decoded.envelope, hostOfRemoteAddress(socket.remoteAddress.toString()))
                    }.getOrDefault(false)
                    writeLock.withLock {
                        if (ok) {
                            writeFrame(write, MsgAckBody(decoded.envelope.id))
                        } else {
                            writeObject(write, ErrFrame(type = TcpFrameTypes.ERR, reason = "bad-msg"))
                        }
                    }
                }

                is TcpFrameDecode.MsgAck -> return false

                is TcpFrameDecode.Rejected -> return false

                is TcpFrameDecode.Control -> when (val frame = decoded.frame) {
                    is TcpControlFrame.Finish -> {
                        _events.tryEmit(TransferServerEvent.Served(frame.transferId))
                    }

                    is TcpControlFrame.Pull -> {
                        val file = lookup.resolve(frame.transferId, frame.fileId)
                        if (file == null) {
                            writeLock.withLock { writeObject(write, ErrFrame(type = TcpFrameTypes.ERR, reason = "not-found")) }
                        } else if (frame.offset > file.size) {
                            writeLock.withLock { writeObject(write, ErrFrame(type = TcpFrameTypes.ERR, reason = "bad-offset")) }
                        } else {
                            servePull(socket, read, write, writeLock, frame, file)
                        }
                    }

                    // pull-ok / done / err / wait 是**接收方**的帧，发送方收到就静默忽略。
                    //
                    // 这里绝不能回 err：续传时接收方**先**发一个 wait（这会把本端
                    // 15s 握手空闲推到 60s），然后才预哈希、才发 pull。若对 wait 回 err，
                    // 接收方会在 awaitPullOk 里收到 `peer:busy` 而整条续传失败。
                    // TS `transfer.ts:281-284` 正是这么写的：只有 frame 本身是 pull 且
                    // busy 时才回 busy，其余一律 return 不回应。
                    //
                    // 本实现不需要 TS 的 `busy` 标志：servePull 是挂起调用，同一连接内的
                    // 后续帧会留在 socket 缓冲里排队，天然串行。
                    else -> Unit
                }
            }
        }
    }

    /**
     * 服务一次 `pull`。
     *
     * [socket]/[read] 目前只用于诊断与后续扩展；真正的读写都在 [write] 上。
     */
    private suspend fun servePull(
        socket: Socket,
        read: ByteReadChannel,
        write: ByteWriteChannel,
        writeLock: Mutex,
        pull: TcpControlFrame.Pull,
        file: OutgoingFile,
    ) {
        val transferId = pull.transferId
        val wantsWait = lookup.supportsWait(pull.from)

        // 续传（offset > 0）时先抢流槽；排队期间要发 wait，否则发送方 15s 握手超时会把连接掐掉。
        val queuedHeartbeat = if (wantsWait && pull.offset > 0) {
            startWaitHeartbeat(write, writeLock)
        } else {
            null
        }

        streamSlots.withPermit {
            queuedHeartbeat?.cancel()
            bumpActive(transferId, +1)
            try {
                writeLock.withLock {
                    writeObject(write, PullOkFrame(type = TcpFrameTypes.PULL_OK, fileId = file.fileId, len = file.size - pull.offset))
                }

                if (pull.offset == 0L) {
                    // 快路径：一遍读完就发，同时算摘要。
                    val digest = streamAndHash(write, writeLock, transferId, file, 0L)
                    writeLock.withLock {
                        writeFrame(write, DoneBody(file.fileId, digest))
                    }
                } else {
                    // 续传路径：另起一条整文件哈希流，数据流与哈希都结束才发 done。
                    val hashDeferred = scope.async(ioDispatcher) { hashFile(file.absPath) }
                    // 关键顺序：wait 心跳只能在裸流发完之后才开。
                    // 数据面是"pull-ok 之后紧跟 len 字节裸流"，接收方正照着 len 数字节；
                    // 此时插一个 wait 帧进去会被当成文件内容吃掉，后面所有帧全部错位。
                    // TS 也是这么做的：startWaitHeartbeat 挂在数据流的 'end' 上（transfer.ts:377-382）。
                    streamOnly(write, writeLock, transferId, file, pull.offset)
                    val hashTailHeartbeat =
                        if (wantsWait && !hashDeferred.isCompleted) {
                            startWaitHeartbeat(write, writeLock)
                        } else {
                            null
                        }
                    try {
                        val digest = hashDeferred.await()
                        writeLock.withLock {
                            writeFrame(write, DoneBody(file.fileId, digest))
                        }
                    } finally {
                        hashTailHeartbeat?.cancel()
                    }
                }
            } catch (_: Exception) {
                // 对端断开：什么都不用回，连接马上会被关掉。
            } finally {
                bumpActive(transferId, -1)
            }
        }
        // read/socket 目前不参与数据面，保留参数是为了将来加"发送方主动取消"时不必改签名。
        @Suppress("UNUSED_EXPRESSION")
        read
        @Suppress("UNUSED_EXPRESSION")
        socket
    }

    /** offset == 0 的快路径：边发边哈希，返回整文件摘要。 */
    private suspend fun streamAndHash(
        write: ByteWriteChannel,
        writeLock: Mutex,
        transferId: String,
        file: OutgoingFile,
        offset: Long,
    ): String {
        val handle = withContext(ioDispatcher) { openRandomAccess(file.absPath, forWrite = false) }
        val sha = Sha256()
        try {
            val buf = ByteArray(CHUNK)
            var pos = offset
            while (pos < file.size) {
                val want = minOf(CHUNK.toLong(), file.size - pos).toInt()
                val n = withContext(ioDispatcher) { handle.read(buf, pos, want) }
                if (n <= 0) break
                sha.update(buf, 0, n)
                writeLock.withLock { write.writeFully(buf, 0, n) }
                pos += n
                _events.tryEmit(TransferServerEvent.Progress(transferId, file.fileId, pos, file.size))
            }
            return sha.hexDigest()
        } finally {
            withContext(ioDispatcher) { runCatching { handle.close() } }
        }
    }

    /** 只发数据不算哈希（续传路径的哈希由另一条流负责）。 */
    private suspend fun streamOnly(
        write: ByteWriteChannel,
        writeLock: Mutex,
        transferId: String,
        file: OutgoingFile,
        offset: Long,
    ) {
        val handle = withContext(ioDispatcher) { openRandomAccess(file.absPath, forWrite = false) }
        try {
            val buf = ByteArray(CHUNK)
            var pos = offset
            while (pos < file.size) {
                val want = minOf(CHUNK.toLong(), file.size - pos).toInt()
                val n = withContext(ioDispatcher) { handle.read(buf, pos, want) }
                if (n <= 0) break
                writeLock.withLock { write.writeFully(buf, 0, n) }
                pos += n
                _events.tryEmit(TransferServerEvent.Progress(transferId, file.fileId, pos, file.size))
            }
        } finally {
            withContext(ioDispatcher) { runCatching { handle.close() } }
        }
    }

    /** 整文件摘要，用于续传路径。 */
    private fun hashFile(path: String): String {
        val handle = openRandomAccess(path, forWrite = false)
        try {
            val sha = Sha256()
            val buf = ByteArray(CHUNK)
            var pos = 0L
            while (true) {
                val n = handle.read(buf, pos, buf.size)
                if (n <= 0) break
                sha.update(buf, 0, n)
                pos += n
            }
            return sha.hexDigest()
        } finally {
            runCatching { handle.close() }
        }
    }

    /**
     * 周期发 `wait`。返回的 Job 由调用方 cancel。
     *
     * 立刻先发一个：这一个才是把对端 15s 握手空闲推到 60s 的那个。
     */
    private fun startWaitHeartbeat(
        write: ByteWriteChannel,
        writeLock: Mutex,
    ): Job = scope.launch {
        while (isActive) {
            writeLock.withLock { runCatching { writeObject(write, WaitFrame(type = TcpFrameTypes.WAIT)) } }
            kotlinx.coroutines.delay(limits.waitHeartbeatMs)
        }
    }

    private suspend fun bumpActive(transferId: String, delta: Int) {
        val nowZero = activeLock.withLock {
            val next = (activeTransfers[transferId] ?: 0) + delta
            if (next <= 0) {
                activeTransfers.remove(transferId)
                true
            } else {
                activeTransfers[transferId] = next
                false
            }
        }
        if (nowZero && delta < 0) {
            _events.tryEmit(TransferServerEvent.Disconnected(transferId))
        }
    }

    /** 读一帧；长度前缀非法或对端提前关闭都返回 null。 */
    private suspend fun readFrame(read: ByteReadChannel): String? {
        val header = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES)
        read.readFully(header, 0, TcpFrame.LENGTH_PREFIX_BYTES)
        val len = TcpFrame.decodeLength(header)
        if (len <= 0 || len > FileTransfer.MAX_FRAME) return null
        val body = ByteArray(len)
        read.readFully(body, 0, len)
        return body.decodeToString()
    }

    /** 写一帧（加长度前缀）。 */
    /** 写一个帧对象（`ErrFrame`/`PullOkFrame`/`WaitFrame` 等），自动补长度前缀。 */
    private suspend fun writeObject(write: ByteWriteChannel, frame: Any) {
        writeFrame(write, TcpFrames.encodeBody(frame))
    }

    private suspend fun writeFrame(write: ByteWriteChannel, body: String) {
        write.writeFully(TcpFrame.encode(body.encodeToByteArray()))
    }

    /** `{"type":"msg-ack","ackFor":…}`：key 顺序与 TS 一致。 */
    private fun MsgAckBody(ackFor: String): String =
        """{"type":"${TcpFrameTypes.MSG_ACK}","ackFor":${jsonString(ackFor)}}"""

    private fun DoneBody(fileId: String, sha256: String): String =
        TcpFrames.encodeBody(app.relay.core.protocol.DoneFrame(type = TcpFrameTypes.DONE, fileId = fileId, sha256 = sha256))

    private fun jsonString(value: String): String = Codec.wireJson.encodeToString(String.serializer(), value)

    private companion object {
        /** 文件读写块大小。64 KiB 与 TS 的流块大小同量级，够大也够省内存。 */
        const val CHUNK: Int = 64 * 1024
    }
}

/**
 * 从 ktor 的 `socket.remoteAddress.toString()` 里取出**裸主机地址**。
 *
 * `SocketAddress.toString()` 给的是 `host/ip:port` 这一族形状，实测是
 * `/127.0.0.1:55106`（IPv6 带方括号：`/[::1]:55106`）。而 `PeerRegistry` 里存的
 * 是 `192.168.2.22` 这种裸地址 —— 直接把前者递下去，`Messenger.acceptTcpMessage`
 * 的 `known.ip != remoteAddress` 必然成立，**每一帧 TCP 长文本都会被当成「地址漂移」
 * 拒收**：对端只回一个 `err`，发送方等不到 `msg-ack`，用户看到的是「已排队」。
 * 真机上就是这么坏的。
 *
 * 单独成函数是为了能**不开 socket** 就被单测钉住：这个 bug 的全部内容就是一次字符串
 * 形状判断，它不该需要一张网卡才能复现。
 */
internal fun hostOfRemoteAddress(raw: String): String {
    // 两个平台给的形状**不一样**，这条是 iOS 测试目标替我们发现的：
    //   JVM    ：`/127.0.0.1:55106`（ktor 的 `InetSocketAddress.toString()`）
    //   Native ：`InetSocketAddress(127.0.0.1:55106)`（同上，但没有斜杠前缀）
    // 只处理前者会让 iOS 上每一帧 TCP 文本继续被当成「地址漂移」拒收 —— 也就是
    // 这个函数本来要修的那个缺陷，在另一个平台上原样复现。
    val unwrapped = raw.trim().removePrefix("InetSocketAddress(").removeSuffix(")")
    // "hostname/1.2.3.4:5678" 里 `/` 之后才是字面地址；没有 `/` 时 substringAfterLast 原样返回。
    val afterSlash = unwrapped.substringAfterLast('/')
    if (afterSlash.startsWith("[")) {
        val end = afterSlash.indexOf(']')
        if (end > 0) return afterSlash.substring(1, end).removeIpv4MappedPrefix()
    }
    return afterSlash.substringBeforeLast(':').removeIpv4MappedPrefix()
}

/** `::ffff:192.168.2.22` 与 `192.168.2.22` 是同一个地址，资料里存的是后者。 */
private fun String.removeIpv4MappedPrefix(): String =
    if (startsWith("::ffff:") && contains('.')) removePrefix("::ffff:") else this
