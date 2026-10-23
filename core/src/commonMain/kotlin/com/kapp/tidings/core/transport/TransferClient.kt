// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.crypto.Sha256
import com.kapp.tidings.core.crypto.TlsPin
import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.fileExists
import com.kapp.tidings.core.fs.fileSize
import com.kapp.tidings.core.fs.makeDirectories
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.renameFile
import com.kapp.tidings.core.fs.sanitizeRelativePath
import com.kapp.tidings.core.ioDispatcher
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.FileTransfer
import com.kapp.tidings.core.protocol.FinishFrame
import com.kapp.tidings.core.protocol.PullFrame
import com.kapp.tidings.core.protocol.TcpControlFrame
import com.kapp.tidings.core.protocol.TcpFrame
import com.kapp.tidings.core.protocol.TcpFrameDecode
import com.kapp.tidings.core.protocol.TcpFrameTypes
import com.kapp.tidings.core.protocol.TcpFrames
import com.kapp.tidings.core.protocol.WaitFrame
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * 接收侧要拉的一个文件。
 *
 * [relPath] 必须是已经 sanitize 过的相对路径（'/' 分隔）。调用方从 `FileCtlOffer` 的
 * `FileMeta.path` 拿到原始串后，先过 [sanitizeRelativePath]；这里再兜一次底。
 */
data class IncomingFilePlan(
    val fileId: String,
    val relPath: String,
    val size: Long,
    val isDir: Boolean = false,
)

/** 拉取阶段，逐字对齐 TS `PullStage`。 */
enum class PullStage { Connect, Connected, Prepare, Pull, Receive, Verify, Write, Complete }

/**
 * 续传用的半成品后缀。TS 用 `${finalPath}.part`，**不要改**。
 *
 * 提到文件顶层而不是留在 [PullSession] 的 companion 里，是因为清扫侧
 * （`TransferManager.sweepStaleParts`）必须用**同一个**后缀去认这些文件：
 * 两处各写一个字面量，改一处就会变成"清扫扫了个寂寞"。
 */
internal const val PART_SUFFIX: String = ".part"

/**
 * 取消句柄。
 *
 * 置 [canceled] 或关掉 [socket] 都能让拉取停下 —— 与 TS `cancelRef` 同形。
 * **取消不删 `.part`**：留着下次续传。
 */
class PullCancelRef {
    @Volatile
    var canceled: Boolean = false

    @Volatile
    var socket: Socket? = null

    /**
     * 跨网（F1）原始字节流那条路的连接；与 [socket] 二选一，由拉取会话挂上。
     *
     * 为什么要有它：取消一条正在裸流里阻塞的连接，光置 [canceled] 不够 —— 读要等
     * 到数据或空闲超时（60s）才回来。有了它，[cancelNow] 能立刻把 socket 关掉。
     */
    @Volatile
    var raw: RawPullConnection? = null

    /** 立刻取消：置标志 + 关掉两条路里活着的那个连接。 */
    fun cancelNow() {
        canceled = true
        runCatching { socket?.close() }
        runCatching { raw?.close() }
    }
}

/**
 * 一条**原始字节流**连接（F1 跨网文件的接缝）。
 *
 * ## 为什么不是 [LinkConnection]
 *
 * 文件的数据阶段是**裸流**：`pull-ok` 之后紧跟 `len` 字节，不受帧上限约束
 * （`FileTransfer.MAX_FRAME` 的 KDoc 逐字写明）。而 [LinkConnection] 只暴露
 * `send(frame)` / `receiveFrame()` —— 把裸流塞进分帧就是协议变更（方案 A），
 * 设计文档已经否掉。跨网真正需要的是「同一套 TLS 身份 + 双向 pin 之下的原始字节流」，
 * 所以这里刻意只有 read / write / close 三件事。
 */
interface RawPullConnection {
    val read: ByteReadChannel
    val write: ByteWriteChannel

    /** 对端证书的 SPKI pin（明文连接是 null）。诊断用，不参与判定。 */
    val peerPin: TlsPin? get() = null

    fun close()
}

/**
 * 拨一条原始字节流连接；null = 拨不通。
 *
 * 调用方（跨网策略）负责"该不该用 TLS、pin 是什么"；这里只负责把结果交出来。
 * 失败**绝不降级明文**：返回 null 就让这次拉取按 socket-error 失败。
 */
typealias RawPullConnector = suspend (host: String, port: Int) -> RawPullConnection?

class PullOptions(
    val host: String,
    val port: Int,
    val selfId: String,
    val transferId: String,
    val files: List<IncomingFilePlan>,
    val saveDir: String,
    val onPhase: ((PullStage) -> Unit)? = null,
    /**
     * 每收到一段（或续传时读到已有 `.part`）就报一次增量字节数。
     *
     * 带上 [fileId]：一次 transfer 可以有多个文件，只给增量的话调用方没法把进度归到哪一条上
     * （TS 的调用方自己记「当前文件」，我们这里是顺序读、没有别的回调可挂，所以直接带上）。
     */
    val onProgress: ((fileId: String, delta: Long) -> Unit)? = null,
    /** 收到 `wait` 且还没开始供流 → true；`pull-ok` 到达 → false。 */
    val onQueued: ((Boolean) -> Unit)? = null,
    val cancelRef: PullCancelRef = PullCancelRef(),
    /**
     * 原始连接的拨号口（F1 跨网文件）。**null = 走今天的明文 ktor socket 路径，逐字不变。**
     *
     * 跨网时由调用方注入「TLS + 双向 pin」的实现（`connectRaw` 的小工厂）；这里只负责用它，
     * 不做任何策略判断 —— "该不该跨网、试哪个地址、要不要 TLS" 全在
     * [CrossNetworkTransferPolicy] 里（纯逻辑、可单测）。
     */
    val connectRaw: RawPullConnector? = null,
    /**
     * 策略排好的尝试顺序（F1）。**null = 只用 [host]/[port] 走明文，逐字等于今天的行为。**
     *
     * 非 null 时 [host]/[port] 只作兜底与诊断，真正的拨号顺序看这张表
     * （第一条通常是局域网明文，后面是公网 TLS）。
     */
    val attempts: List<PullAttempt>? = null,
    /**
     * 计划路径里**每一次明文尝试**的连接超时（毫秒）；null = 不设（今天的行为，交给系统）。
     *
     * 为什么要有它：手机在蜂窝上拨一个私网地址会一直等到系统级超时（可达 75s），
     * 后面的公网尝试全被它挡住 —— 那正是"跨网文件传不动"的现场形态。
     */
    val connectTimeoutMs: Long? = 5_000L,
    val idleTimeoutMs: Long = FileTransfer.PULL_IDLE_TIMEOUT,
    /** 只有对端声明了 `tw1` 才允许发 `wait` 帧。未知能力一律当老端。 */
    val supportsWait: Boolean = false,
    val waitHeartbeatMs: Long = FileTransfer.PULL_WAIT_HEARTBEAT,
)

/**
 * 拉取失败。
 *
 * [reason] 与 TS `pullTransfer` 的 fail reason 逐字一致，方便两端日志对得上：
 * `hash-mismatch` / `size-mismatch` / `path-escape` / `part-read-error` / `write-error` /
 * `canceled` / `closed` / `timeout` / `socket-error` / `peer:<reason>`。
 */
class PullException(
    val reason: String,
    val stage: PullStage,
) : Exception(reason)

/**
 * 逐文件把对端的文件拉到本地。
 *
 * 逐条对齐 TS `src/main/net/transfer.ts` 的 `pullTransfer`：
 * - **拉取式**：发送方从不推，接收方连它的 `tcpPort` 发 `pull`，`pull-ok` 之后紧跟 `len` 字节裸流；
 * - **断点续传**：`<final>.part` 已存在的字节数就是 offset，先把它读一遍喂给哈希，再从 offset 续拉；
 * - **校验后转正**：整文件 SHA-256 与 `done` 帧的 `sha256` 相等才把 `.part` 改名成最终名，
 *   且**不覆盖**已有文件（[dedupeTargetPath]）；
 * - `offset === 0` 走单遍；`offset > 0` 的预哈希期间，若对端支持 `tw1` 就发 `wait` 保活，
 *   否则（老端）**先断开、等预哈希完成再重连** —— 老端只认 15s 握手超时，预哈希超时它会直接踢人。
 *
 * 结构上这里用顺序读写而不是 TS 的事件回调：ktor 的 `readFully`/`writeFully` 天然带背压，
 * 读到就写、写完再读，等价于 TS 那套 `socket.pause()` + `stream.once('drain')` 的组合，
 * 因此不需要移植 `resumeSocket()` 那处防死锁补丁。行为（帧序、失败原因、`.part` 去留）保持一致。
 */
suspend fun pullTransfer(opts: PullOptions) {
    PullSession(opts).run()
}

/**
 * `.part` 转正时避免覆盖已有文件（F-FILE-3）。
 *
 * `name.ext` → `name(1).ext`，i 从 1 试到 999；都占了就用时间戳兜底。
 */
fun dedupeTargetPath(path: String): String {
    if (!fileExists(path)) return path

    val slash = path.lastIndexOf('/')
    val dir = if (slash >= 0) path.substring(0, slash + 1) else ""
    val base = if (slash >= 0) path.substring(slash + 1) else path
    // 只认"点在中间"的扩展名：`.bashrc` 这类前导点不当扩展名。
    val dot = base.lastIndexOf('.')
    val stem = if (dot > 0) base.substring(0, dot) else base
    val ext = if (dot > 0) base.substring(dot) else ""

    for (i in 1..999) {
        val candidate = "$dir$stem($i)$ext"
        if (!fileExists(candidate)) return candidate
    }
    return "$dir$stem(${nowMillis()})$ext"
}

/**
 * 一次拉取的会话状态。
 *
 * 写成类而不是一串局部变量，是因为续传路径要在中途换 socket（老端重连），
 * 而 [read]/[write] 得跟着换 —— 局部 val 表达不了这件事。
 */
private class PullSession(private val opts: PullOptions) {

    private val selector = SelectorManager(ioDispatcher)
    private val heartbeatScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeLock = Mutex()

    private var socket: Socket? = null
    /** 跨网那条路活着时的原始连接（与 [socket] 二选一）。 */
    private var raw: RawPullConnection? = null
    private var read: ByteReadChannel? = null
    private var write: ByteWriteChannel? = null

    private var stage: PullStage = PullStage.Connect

    /** 保存根目录。归一化掉重复分隔符和尾斜杠，好做前缀闸。 */
    private val root: String = pathJoin(opts.saveDir).trimEnd('/')

    suspend fun run() {
        try {
            connect()

            for (plan in opts.files) {
                checkCanceled()
                pullOne(plan)
            }

            // 队列空了：告诉对端这次 transfer 收工（TS 在 next() 里发 finish）。
            writeLock.withLock { writeObject(FinishFrame(type = TcpFrameTypes.FINISH, transferId = opts.transferId)) }
            phase(PullStage.Complete)
        } finally {
            heartbeatScope.cancel()
            closeSocket()
            runCatching { selector.close() }
        }
    }

    // ---------------------------------------------------------------- 连接

    private suspend fun connect() {
        phase(PullStage.Connect)
        // F1：策略排好的多尝试路径（第一条通常是局域网明文，后面是公网 TLS）。
        // attempts == null 时走下面那条**与今天逐字相同**的单条明文路径。
        val plan = opts.attempts
        if (plan != null) {
            // task-61：**每条尝试都留痕**（拨了哪个 host:port、哪种传输、耗时、成败）。
            // 真机上一次成功一次 timeout，而失败那次看不出拨的是哪个候选 —— 没有这段，
            // 「是不是拨了陈旧地址」只能猜。失败原因里带着它，UI 与日志都能读到。
            val tried = ArrayList<String>(plan.size)
            for (attempt in plan) {
                val started = nowMillis()
                val ok = when (attempt.transport) {
                    PullAttemptTransport.Plaintext ->
                        dialPlaintext(attempt.host, attempt.port, opts.connectTimeoutMs)
                    PullAttemptTransport.Tls -> dialRaw(attempt.host, attempt.port)
                }
                val elapsed = nowMillis() - started
                tried += attempt.host + ":" + attempt.port + " " +
                    (if (attempt.transport == PullAttemptTransport.Tls) "tls" else "plain") +
                    " " + elapsed + "ms" + (if (ok) " ok" else "")
                if (ok) {
                    phase(PullStage.Connected)
                    return
                }
            }
            fail("socket-error: tried " + tried.joinToString(" | "), PullStage.Connect)
        }
        // 跨网（F1）单条路径：调用方直接给一个原始拨号口（TLS + pin 在那一侧完成）。
        // 协议一模一样 —— pull / pull-ok / 裸流 / done 全部照旧，只是字节从哪来不同。
        if (opts.connectRaw != null) {
            if (!dialRaw(opts.host, opts.port)) fail("socket-error", PullStage.Connect)
            phase(PullStage.Connected)
            return
        }
        if (!dialPlaintext(opts.host, opts.port, null)) fail("socket-error", PullStage.Connect)
        phase(PullStage.Connected)
    }

    /**
     * 明文拨号；true = 已挂上（[attach]）。
     *
     * [timeoutMs] 为 null = 不设超时（今天的行为，交给操作系统）；计划路径会给一个短超时，
     * 否则"手机在蜂窝上拨一个私网地址"要等到系统级超时（可达 75s）才轮到后面的公网尝试。
     */
    private suspend fun dialPlaintext(host: String, port: Int, timeoutMs: Long?): Boolean = try {
        val fresh = if (timeoutMs == null) {
            aSocket(selector).tcp().connect(InetSocketAddress(host, port))
        } else {
            withTimeoutOrNull(timeoutMs) {
                aSocket(selector).tcp().connect(InetSocketAddress(host, port))
            }
        }
        if (fresh != null) {
            attach(fresh)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }

    /** 原始字节流拨号（TLS + 双向 pin 由 [RawPullConnector] 那一侧完成）。 */
    private suspend fun dialRaw(host: String, port: Int): Boolean {
        val connector = opts.connectRaw ?: return false
        val fresh = try {
            connector(host, port)
        } catch (e: Exception) {
            null
        } ?: return false
        attachRaw(fresh)
        return true
    }

    /** 挂上一条原始字节流连接（跨网路径）。取消句柄也一起挂，让取消能立刻断开。 */
    private fun attachRaw(fresh: RawPullConnection) {
        raw = fresh
        opts.cancelRef.raw = fresh
        read = fresh.read
        write = fresh.write
    }

    private fun attach(fresh: Socket) {
        socket = fresh
        opts.cancelRef.socket = fresh
        read = fresh.openReadChannel()
        write = fresh.openWriteChannel(autoFlush = true)
    }

    private fun closeSocket() {
        val closing = socket
        val closingRaw = raw
        socket = null
        raw = null
        read = null
        write = null
        opts.cancelRef.raw = null
        runCatching { closing?.close() }
        runCatching { closingRaw?.close() }
    }

    // ---------------------------------------------------------------- 单个文件

    private suspend fun pullOne(plan: IncomingFilePlan) {
        phase(PullStage.Prepare)

        val safe = sanitizeRelativePath(plan.relPath) ?: fail("path-escape")
        val finalPath = pathJoin(root, safe)
        // 最后一道闸。relPath 已经 sanitize 过（无 `..`、无前导 `/`），这里正常不会触发；
        // 留着是因为"对端给的路径"这件事本身就是不可信输入，多一道便宜的检查不亏。
        if (root.isNotEmpty() && !finalPath.startsWith("$root/")) fail("path-escape")

        if (plan.isDir) {
            if (!makeDirectories(finalPath)) fail("write-error")
            return
        }
        val parent = parentOf(finalPath)
        if (parent.isNotEmpty() && !makeDirectories(parent)) fail("write-error")

        val partPath = finalPath + PART_SUFFIX
        val offset = minOf(fileSize(partPath) ?: 0L, plan.size)
        if (offset > 0) opts.onProgress?.invoke(plan.fileId, offset)

        val sha = Sha256()

        if (offset > 0) {
            prepareResume(partPath, offset, sha)
        }

        phase(PullStage.Pull)
        val handle = try {
            withContext(ioDispatcher) { openRandomAccess(partPath, forWrite = true) }
        } catch (e: Exception) {
            fail("write-error")
        }

        try {
            receiveInto(handle, sha, plan, offset)
        } catch (e: PullException) {
            runCatching { handle.close() }
            throw e
        } catch (e: Exception) {
            runCatching { handle.close() }
            fail("socket-error")
        }
    }

    /**
     * 续传前的准备：把已有 `.part` 的 [offset] 字节喂给哈希。
     *
     * 两条路径的差别只有一个原因 —— 老端不能收 `wait`：
     * - 对端声明 `tw1`：先发一个 `wait`（这一个才是把对端 15s 握手空闲推到 60s 的），
     *   再起心跳，然后安心预哈希；
     * - 老端：预哈希期间对端会按 15s 握手超时踢人，所以**先断开**，等预哈希完成再重连。
     */
    private suspend fun prepareResume(partPath: String, offset: Long, sha: Sha256) {
        if (opts.supportsWait) {
            writeLock.withLock { runCatching { writeObject(WaitFrame(type = TcpFrameTypes.WAIT)) } }
        } else {
            closeSocket()
        }

        val heartbeat = if (opts.supportsWait) startWaitHeartbeat() else null
        try {
            hashExisting(partPath, offset, sha)
        } catch (e: Exception) {
            heartbeat?.cancelAndJoin()
            deleteFile(partPath)
            fail("part-read-error")
        }
        heartbeat?.cancelAndJoin()

        if (!opts.supportsWait) connect()
    }

    /** 预哈希期间周期性发 `wait`，别让对端的空闲计时器把我们判死。 */
    private fun startWaitHeartbeat(): Job = heartbeatScope.launch {
        while (isActive) {
            delay(opts.waitHeartbeatMs)
            writeLock.withLock { runCatching { writeObject(WaitFrame(type = TcpFrameTypes.WAIT)) } }
        }
    }

    private suspend fun hashExisting(path: String, count: Long, sha: Sha256) {
        val handle = withContext(ioDispatcher) { openRandomAccess(path, forWrite = false) }
        try {
            val buffer = ByteArray(CHUNK)
            var pos = 0L
            while (pos < count) {
                val want = minOf(CHUNK.toLong(), count - pos).toInt()
                val got = withContext(ioDispatcher) { handle.read(buffer, pos, want) }
                // 文件比记录的短：按读到的算，剩下的交给对端补（哈希对不上就会失败重来）。
                if (got <= 0) break
                sha.update(buffer, 0, got)
                pos += got
            }
        } finally {
            runCatching { withContext(ioDispatcher) { handle.close() } }
        }
    }

    private suspend fun receiveInto(
        handle: com.kapp.tidings.core.fs.RandomAccessFile,
        sha: Sha256,
        plan: IncomingFilePlan,
        offset: Long,
    ) {
        writeLock.withLock {
            writeObject(
                PullFrame(
                    type = TcpFrameTypes.PULL,
                    from = opts.selfId,
                    transferId = opts.transferId,
                    fileId = plan.fileId,
                    offset = offset,
                ),
            )
        }

        val expected = awaitPullOk(plan.size - offset)
        opts.onQueued?.invoke(false)
        phase(PullStage.Receive)

        val buffer = ByteArray(CHUNK)
        var remaining = expected
        while (remaining > 0) {
            val want = minOf(CHUNK.toLong(), remaining).toInt()
            readExact(buffer, want)
            sha.update(buffer, 0, want)
            withContext(ioDispatcher) { handle.append(buffer, want) }
            remaining -= want
            opts.onProgress?.invoke(plan.fileId, want.toLong())
        }
        withContext(ioDispatcher) { handle.flush() }

        phase(PullStage.Verify)
        val digest = awaitDone()
        if (sha.hexDigest() != digest) {
            runCatching { withContext(ioDispatcher) { handle.close() } }
            deleteFile(partPathOf(plan, handle))
            fail("hash-mismatch")
        }
        withContext(ioDispatcher) { handle.close() }

        phase(PullStage.Write)
        val finalPath = finalPathOf(plan)
        if (!renameFile(finalPath + PART_SUFFIX, dedupeTargetPath(finalPath))) {
            deleteFile(finalPath + PART_SUFFIX)
            fail("write-error")
        }
    }

    // 这两处是为了让 receiveInto 不必再算一遍路径；计算本身很便宜，但重复算容易写歪。
    private fun partPathOf(plan: IncomingFilePlan, @Suppress("UNUSED_PARAMETER") handle: Any): String =
        finalPathOf(plan) + PART_SUFFIX

    private fun finalPathOf(plan: IncomingFilePlan): String {
        val safe = sanitizeRelativePath(plan.relPath) ?: fail("path-escape")
        return pathJoin(root, safe)
    }

    /** 等 `pull-ok`。期间可能先收到 `wait`（对端在排队）——那正是 [PullOptions.onQueued] 的用途。 */
    private suspend fun awaitPullOk(left: Long): Long {
        while (true) {
            when (val frame = readControl()) {
                is TcpControlFrame.PullOk -> {
                    // 长度对不上就到此为止：继续读只会把后续文件的字节当成这个文件的。
                    if (frame.len != left) fail("size-mismatch")
                    return frame.len
                }
                TcpControlFrame.Wait -> opts.onQueued?.invoke(true)
                is TcpControlFrame.Err -> fail("peer:${frame.reason}")
                // 其余控制帧在接收侧都是噪音（TS 的 reader 同样只挑它认识的几个），忽略继续等。
                else -> Unit
            }
        }
    }

    /** 等 `done`。哈希收尾期间对端会继续发 `wait`。 */
    private suspend fun awaitDone(): String {
        while (true) {
            when (val frame = readControl()) {
                is TcpControlFrame.Done -> return frame.sha256
                TcpControlFrame.Wait -> Unit
                is TcpControlFrame.Err -> fail("peer:${frame.reason}")
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- 帧读写

    private suspend fun readControl(): TcpControlFrame {
        val header = ByteArray(TcpFrame.LENGTH_PREFIX_BYTES)
        readExact(header, TcpFrame.LENGTH_PREFIX_BYTES)
        val len = TcpFrame.decodeLength(header)
        if (len <= 0 || len > FileTransfer.MAX_FRAME) fail("bad-frame-length")
        val body = ByteArray(len)
        readExact(body, len)
        return when (val decoded = TcpFrames.decodeBody(body.decodeToString())) {
            is TcpFrameDecode.Control -> decoded.frame
            is TcpFrameDecode.Rejected -> fail(decoded.reason)
            else -> fail("bad-frame-shape")
        }
    }

    /** 读满 [count] 字节；每次读都重置空闲超时（等价 TS `socket.setTimeout(idleTimeoutMs)`）。 */
    private suspend fun readExact(buffer: ByteArray, count: Int) {
        val channel = read ?: fail("closed")
        val done = try {
            withTimeoutOrNull(opts.idleTimeoutMs) {
                channel.readFully(buffer, 0, count)
                true
            }
        } catch (e: Exception) {
            // 对端提前关闭 / 连接被重置：TS 的 'close' 事件在这里对应成 closed。
            fail("closed")
        }
        if (done != true) {
            // task-61：区分**等对端第一帧（pull-ok）**与**数据阶段卡住** —— 两者原先都叫
            // "timeout"，而真机上「0 字节 .part + timeout」到底是对端没应答还是传到一半断了，
            // 正是要靠这个区分才能归因。stage 由 phase() 推进：数据阶段是 Receive。
            fail(if (stage == PullStage.Receive) "timeout-data" else "timeout-waiting-pull-ok")
        }
    }

    private suspend fun writeObject(frame: Any) {
        val channel = write ?: fail("closed")
        channel.writeFully(TcpFrame.encode(TcpFrames.encodeBody(frame).encodeToByteArray()))
    }

    // ---------------------------------------------------------------- 状态

    private fun phase(next: PullStage) {
        stage = next
        opts.onPhase?.invoke(next)
    }

    private fun checkCanceled() {
        if (opts.cancelRef.canceled) fail("canceled")
        // ktor 3.3.1 的 Socket 没有 isClosed；连接关闭时 socketContext 这个 Job 会完成。
        if (socket?.socketContext?.isActive == false) fail("closed")
    }

    private fun fail(reason: String, stageOverride: PullStage = stage): Nothing {
        runCatching { socket?.close() }
        throw PullException(reason, stageOverride)
    }

    private fun parentOf(path: String): String {
        val cut = path.lastIndexOf('/')
        return if (cut > 0) path.substring(0, cut) else ""
    }

    private companion object {
        /** 与发送侧同一个量级的读写块。 */
        const val CHUNK: Int = 64 * 1024
    }
}
