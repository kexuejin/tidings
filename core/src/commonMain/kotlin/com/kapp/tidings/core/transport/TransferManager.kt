// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.fs.makeDirectories
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.sanitizeRelativePath
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.FileCtlOffer
import com.kapp.tidings.core.protocol.FileCtlOps
import com.kapp.tidings.core.protocol.FileCtlSimple
import com.kapp.tidings.core.protocol.FileMeta
import com.kapp.tidings.core.protocol.FilePurpose
import com.kapp.tidings.core.protocol.FileTransfer
import com.kapp.tidings.core.protocol.LinkFrame
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.ScreenTcpFrame
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.newId
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 见 [TransferManager.scheduleDirectRetry]：兜底重发 `direct` 的等待时间。 */
private const val DIRECT_RETRY_MS: Long = 2_000L


/**
 * 文件传输的双向门面。
 *
 * 一个 `TransferManager` 同时是发送方和接收方，因为协议本来就是双向对称的：
 *
 * - **发送**：`offerFiles` 用 UDP 发 `file-ctl offer`（可靠帧，会 ACK），对方 `accept` 后
 *   把该 transfer 标记为可拉取，对方再开 TCP 来 `pull`——由内部的 [TransferServer] 服务。
 * - **接收**：入站的 `file-ctl` 信封从 [onEnvelope] 进来，按 `seq`/`total` 重组；
 *   图片且在阈值以内自动收（私聊 `IMG_AUTO_ACCEPT` 20MB / 群聊 `GROUP_IMG_AUTO_ACCEPT` 10MB），
 *   其余等用户点头；两种都先发 [TransferEvent.Offered]，
 *   然后 `accept` + 用 [pullTransfer] 主动去拉。
 *
 * 为什么单独一层：`core` 不认识 UI 类型，也不知道"下载目录"在哪，更不该直接引用
 * `PeerRegistry`。这三件事分别由 `downloadDir` / `peerAddress` / `sendReliable` 三个
 * 回调注入，`NodeRuntime` 负责提供。这样 `TransferManager` 能在测试里独立驱动。
 *
 * **TCP 监听**：`start()` 会把 [TransferServer] 绑到 [tcpPort]。手机必须监听这一端口，
 * 否则不仅收不到文件，连桌面端发来的 >800 字节长文本（走 TCP 回退）也会失败。
 */
/**
 * 表格文字按**字节**截断，并如实报告"截过"。
 *
 * 上限是 `TABLE_TEXT_LIMIT_BYTES`（4096，与 TCP 控制帧体同限），而切点必须落在 UTF-8
 * 字符边界上 —— 按字符数截断在中文表格上会切出比上限更大的帧，按字节硬切则会切出半个汉字，
 * 收端拿到的是一段乱码文字，比截断本身更难查。
 *
 * 返回 `(上线用的文本, 是否截断)`，第二个值只有**真的截过**才是 `true`，其余都是 `null`：
 * 没截断就**不带这个键**。老端按 `hasOnlyKeys` 严格比对 key 集合，多一个键整帧丢弃 ——
 * 一个"顺手写上 `false`"就会让所有普通文件报价都多一个键。收端读的是 `== true`，
 * 缺键与 `false` 行为完全一样，所以省略是安全的。
 */
internal fun truncateTableText(text: String?): Pair<String?, Boolean?> {
    if (text == null) return null to null
    val bytes = text.encodeToByteArray()
    val limit = FileTransfer.TABLE_TEXT_LIMIT_BYTES
    if (bytes.size <= limit) return text to null
    var end = limit
    // 退到最后一个 UTF-8 起始字节（续字节的高两位是 10）。
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.decodeToString(0, end) to true
}

/** 等 accept 时的轮询间隔。 */
private const val ACCEPT_GRACE_POLL_MS: Long = 50L

/**
 * 「先回 accept、再 pull」的竞态兜底（task-61 真机发现）。
 *
 * ## 现场
 *
 * 接收方 `accept()` 是 `sendSimple(ACCEPT)` 之后**立刻** `pull()` —— 两条不同的承载
 * （accept 走可靠 UDP / 长连接，pull 走新开的 TCP/TLS），谁先到没有保证。pull 先到时，
 * 发送侧看到的是「transfer 已知、但 accepted 还是 false」，于是回 `err(not-found)`，
 * 接收方 UI 上就是 `peer:not-found` + 0 字节 `.part`（真机实测：2 次成功、1 次这个）。
 *
 * ## 规矩
 *
 *  · **只等「已知但还没 accepted」**：完全不认识的 transferId 立刻返回 null ——
 *    把「没有这个传输」也拖成几秒是另一种坏（对端白等、还掩盖了真正的错误）；
 *  · 等满 [graceMs] 就放弃（2.5s 远小于接收方 60s 空闲超时，足够 accept 赶到）；
 *  · 抽成纯函数是为了**可单测**：`sleep`/`now` 可注入 ⇒ 测试不用真等 2.5 秒，
 *    也能钉住「认识的才等、不认识的立刻失败」这两条。
 */
internal suspend fun resolveAfterAcceptGrace(
    graceMs: Long,
    isKnown: () -> Boolean,
    resolve: () -> OutgoingFile?,
    sleep: suspend (Long) -> Unit = { delay(it) },
    now: () -> Long = { nowMillis() },
): OutgoingFile? {
    resolve()?.let { return it }
    if (!isKnown()) return null
    val deadline = now() + graceMs
    while (now() < deadline) {
        sleep(ACCEPT_GRACE_POLL_MS)
        resolve()?.let { return it }
    }
    return null
}

class TransferManager(
    private val selfId: String,
    private val scope: CoroutineScope,
    private val tcpPort: Int,
    /** 收到文件的落盘根目录。每次调用都可能不同（用户改设置），所以是函数不是值。 */
    private val downloadDir: () -> String,
    /** 发一条可靠 UDP 帧（`file-ctl`）。返回 false 表示发送失败。 */
    private val sendReliable: suspend (peerId: String, payload: JsonElement) -> Boolean,
    /**
     * 读**最近一次可靠投递的逐端点诊断**（task-62 的关联补丁）。
     *
     * 为什么要单独一个回调：`sendReliable` 只回一个 Boolean，而「卡在哪个端点、等了多久」
     * 在 Messenger 手里。真机证据：`/v1/transfers` 里 z1/z2 是 ack-timeout，但全局 `lastSend`
     * 显示的是**最后一次成功**的 z3 —— 没有这条关联，读出来的诊断会被当成失败那几次的，
     * **反而误导排查**（这正是当初「先不做关联」那条边界被真实数据推翻的地方）。
     */
    private val sendReliableDiagnostics: () -> ReliableSendDiagnostics? = { null },
    /**
     * 报价用的**快速**可靠发送（task-63）：只走最短退避、不入消息队列。
     *
     * 为什么报价要快：蜂窝下长连接会抖是**常态**，而报价在满退避下要等「候选数 × 每端点
     * 满退避 + TCP」（真机实测 48s）。快速失败 + 由报价方自己排队重试才是对的形状 ——
     * 默认与 [sendReliable] 相同 ⇒ 既有行为逐字不变。
     */
    private val sendReliableQuick: suspend (peerId: String, payload: JsonElement) -> Boolean = sendReliable,
    /** 报价失败后的重试退避（毫秒，逐次翻倍）。测试注入小值。 */
    private val offerRetryBackoffMs: Long = 2_000L,
    /** 报价投递的**总预算**（毫秒）：超了记 `gave-up`，不再重试。 */
    private val offerDeliveryBudgetMs: Long = 120_000L,
    /**
     * **单次尝试**的上限（毫秒，task-63 追加）。
     *
     * 为什么必须有：quickSchedule 只是把「每个端点的退避」缩短，但**端点数是会长的** ——
     * 真机实测：6 个候选 × 快试 + TCP 兜底再遍历一遍候选 ⇒ 一次尝试仍可能几十秒，
     * 于是 `/v1/transfers` 上一直停在 `offering`，读的人以为卡住。8s 之内必须给出一个结论
     * （成功 / 入队），剩下的交给退避重试与 nudge。
     */
    private val offerAttemptTimeoutMs: Long = 8_000L,
    /** 查对端地址；查不到返回 null。 */
    private val peerAddress: (peerId: String) -> PeerEndpoint?,
    /**
     * F1（task-39）：这次拉取该试哪些连接（局域网直连 + 可选公网 TLS）。
     *
     * **null = 今天的行为**（单条明文直连，逐字不变）—— 这就是「关掉开关可回滚」的落点。
     * 候选池、`tlsPort`、pin 都在 `NodeRuntime` 手里，所以由它把这几样闭包进来，
     * 这里只问一句「这个 peer 该怎么拨」。
     */
    private val crossNetwork: CrossNetworkTransferPlanner? = null,
    /**
     * **字节搬运**的数据面（第 1 步只抽缝，默认就是今天的明文实现）。
     *
     * 传 null = `LegacyTcpDataPlane`，行为逐字不变；加密信道的数据面（下一步）
     * 只要实现 [FileDataPlane] 就能换进来，本类不关心字节从哪条连接来
     * （续传/校验/去重/取消仍由数据面实现负责，见 [FileDataPlane] 的 KDoc）。
     */
    private val dataPlane: FileDataPlane? = null,
    /**
     * 信道数据面的**接管缝**（`fch1` 协商通过时由宿主注入）。
     *
     * 为什么是函数而不是路由器类型：`FileChannelRouter` 在 jvmShared（平台侧），
     * 而本类是 commonMain —— 类型引不进来。宿主把"登记一次入站 transfer"绑上来即可。
     *
     * 返回 true = **已接管**（调用方必须**不发 pull**：字节会由信道推进来）；
     * 返回 false = 没接管（未启用 / 路径越界），调用方走 legacy 拨号拉取。
     */
    private val registerChannelInbound: ((IncomingOffer, saveDir: String) -> Boolean)? = null,
    /**
     * 信道数据面的**出站缝**（与 [registerChannelInbound] 成对由宿主注入）。
     *
     * 对端 `accept` 之后调用：把本机的每个文件按窗口推到对端，返回已推字节数。
     * null = 今天的行为（对端自己拨号来拉）。
     */
    private val pushFileOnChannel: (suspend (transferId: String, peerId: String, files: List<OutgoingFile>) -> Unit)? = null,
    /**
     * F1（task-39）：跨网文件的**主机侧原始 TLS 监听**（方案 B 的入口）。
     *
     * null = 不起这条入口 ⇒ 行为与今天一字不差（局域网原路径）。平台不支持 TLS 服务端
     * （iOS，task-26 ① 落地前）或端口被占时，[CrossNetworkFileListener.start] 返回 null
     * —— 那时**只是没有这条入口**，绝不降级明文。
     */
    private val crossNetworkListener: CrossNetworkFileListener? = null,
    /**
     * 跨网文件监听端口（对端按「对端 tlsPort + 1」来连，见 [crossNetworkFilePort]）。
     * null = 不起这条入口。
     */
    private val crossNetworkFilePort: Int? = null,
    /**
     * 是否创建 **legacy 监听**（明文 tcpPort 与跨网 tlsPort + 1）。
     *
     * luciola 信道模式（fch1）下置 false：文件字节走加密信道，这两条入口没有存在
     * 意义，留着只是多两个可被扫到、可被老协议误连的端口。关掉之后 [start] 依然
     * 返回 true —— 「管理器可用」不等于「我绑了 17879」，真实端口看 [boundPort]
     * （此处为 null）。
     */
    private val legacyTcpListener: Boolean = true,
    /** 对端是否声明了 `tw1`。只有声明者才允许收 `wait` 帧。 */
    private val peerSupportsWait: (peerId: String) -> Boolean = { false },
    /**
     * 对端是否声明了 `fd1`（[com.kapp.tidings.core.protocol.MsgTypes.CAP_FILE_DIRECT]）。
     *
     * 声明者表示"允许发送方请求免确认接收"，于是 [offerFiles] 会在 `offer` 之后再补一条
     * `file-ctl direct`。**这只是请求**：收端的本地开关说了算，被拒时对方什么都不做，
     * 文件卡照常停在"待接收"。
     */
    private val peerSupportsDirect: (peerId: String) -> Boolean = { false },
    /**
     * TCP 监听器收到 `msg` 帧时的去处（协议 §8：>800 字节文本走 TCP）。
     * 返回 true 表示已收下，调用方据此回 `msg-ack`。
     */
    private val receiveMessage: suspend (Envelope, String) -> Boolean = { _, _ -> false },
    /**
     * TCP 监听器首帧收到 `screen-open` 时的去处（屏幕协助接入点）。
     * `read`/`write` 是监听器已经打开好的通道，处理器必须直接用，不能再自己开
     * （ktor 的 socket 通道是一次性绑定的，见 [OutgoingLookup.openScreen]）。
     */
    private val openScreen: suspend (Socket, ScreenTcpFrame.Open, String, ByteReadChannel, ByteWriteChannel) -> Boolean =
        { _, _, _, _, _ -> false },
    /**
     * TCP 监听器首帧收到长连接 hello 时的去处（K1 手机→主机下行通道的接入点）。
     * 与 [openScreen] 同一套交接纪律：返回 true 表示所有权已交出去。
     */
    private val openLink: suspend (Socket, LinkFrame.Hello, String, Int, ByteReadChannel, ByteWriteChannel) -> Boolean =
        { _, _, _, _, _, _ -> false },
    bindAddress: String? = null,
    limits: TransferServerLimits = TransferServerLimits(),
) {

    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    private val stateLock = Mutex()
    private val outgoing = mutableMapOf<String, OutgoingTransfer>()

    /**
     * 报价的**投递状态**（task-62 只读出口）：transferId → 状态，环形上限 [MAX_TRACKED_DELIVERIES]。
     *
     * 为什么必须有它：async 之后「发出去了没有」不能再靠 HTTP 响应回答 —— 没有这个出口，
     * async 就等于把「发不出去」变成「看起来发出去了」。
     */
    private val deliveries = LinkedHashMap<String, OfferDelivery>()
    private val partials = mutableMapOf<String, PartialOffer>()
    private val offers = mutableMapOf<String, IncomingOffer>()
    private val acceptedOffers = mutableMapOf<String, IncomingOffer>()

    /**
     * 正在拉的入站传输，按 transferId 登记。
     *
     * 存在的理由只有一个：让"用户放弃"（[cancelIncoming]）和"停机"（[stop]）有东西可取消 ——
     * 在此之前 `pull()` 里的 [PullCancelRef] 是个匿名局部量，谁也没法从外面停下一次拉取，
     * 也没人知道它的 `.part` 该删哪些。
     */
    private val pulling = mutableMapOf<String, ActiveIncomingPull>()

    /** 上一次 [start] 的 `.part` 清扫结果；[start] 之前为 null。 */
    @Volatile
    var stalePartSweep: PartSweepReport? = null
        private set

    /** 上一次 [abandonPending] 的结果；[stop] 里会自动走一次，所以停机后必然可读。 */
    @Volatile
    var lastAbandon: PendingAbandonReport? = null
        private set

    /**
     * F1：跨网文件监听**实际绑上**的端口（诊断/测试用）。null = 这条入口没起来
     * （没注入监听器 / 平台不支持 / 端口被占）。
     */
    @Volatile
    var crossNetworkBoundPort: Int? = null
        private set

    private val server = TransferServer(
        scope = scope,
        lookup = object : OutgoingLookup {
            override fun resolve(transferId: String, fileId: String): OutgoingFile? {
                val transfer = outgoing[transferId] ?: return null
                if (!transfer.accepted) return null
                return transfer.files.firstOrNull { it.fileId == fileId }
            }

            /**
             * task-61 真机竞态：接收方「先回 accept、紧接着 pull」走**两条不同的承载**，
             * pull 可能先到。这里只对「已知但还没 accepted」等一小会儿；
             * 完全不认识的 transferId 立刻失败（不把「没有这个传输」拖成几秒）。
             */
            override suspend fun resolveAwaitingAccept(
                transferId: String,
                fileId: String,
                graceMs: Long,
            ): OutgoingFile? = resolveAfterAcceptGrace(
                graceMs = graceMs,
                isKnown = { outgoing[transferId] != null },
                resolve = { resolve(transferId, fileId) },
            )

            /** 拒绝时的可归因字段：真机上正是这一串决定「是竞态还是真没有这个传输」。 */
            override fun describeRejection(transferId: String, fileId: String): String {
                val transfer = outgoing[transferId]
                return when {
                    transfer == null -> "transfer=unknown file=" + fileId
                    !transfer.accepted -> "transfer=known accepted=false file=" + fileId
                    transfer.files.none { it.fileId == fileId } -> "transfer=known accepted=true file=unknown"
                    else -> "transfer=known accepted=true file=" + fileId
                }
            }

            override fun supportsWait(peerId: String): Boolean = peerSupportsWait(peerId)

            override suspend fun receiveMessage(envelope: Envelope, remoteAddress: String): Boolean =
                this@TransferManager.receiveMessage(envelope, remoteAddress)

            override suspend fun openScreen(
                socket: Socket,
                open: ScreenTcpFrame.Open,
                remoteAddress: String,
                read: ByteReadChannel,
                write: ByteWriteChannel,
            ): Boolean = this@TransferManager.openScreen(socket, open, remoteAddress, read, write)

            override suspend fun openLink(
                socket: Socket,
                hello: LinkFrame.Hello,
                remoteHost: String,
                remotePort: Int,
                read: ByteReadChannel,
                write: ByteWriteChannel,
            ): Boolean = this@TransferManager.openLink(socket, hello, remoteHost, remotePort, read, write)
        },
        bindAddress = bindAddress,
        limits = limits,
    )

    private var started = false
    private var eventJob: Job? = null

    /**
     * 实际绑上的 TCP 端口；[start] 之前为 null。
     *
     * 与 [TransferServer.boundPort] 同一个理由：`tcpPort = 0` 时**只有监听器自己**知道
     * 系统分配了哪个端口，外面想连它就必须能读到这个值，否则只能退回"先绑 0、关掉、
     * 再绑同一个端口"那种会随机失败的写法。
     */
    val boundPort: Int? get() = server.boundPort

    /**
     * 绑 TCP 端口并开始消费服务端事件。返回 false 表示连系统分配的端口都没绑上。
     *
     * 声明的 [tcpPort] 被占时会**退到系统分配的端口**（传 0），真实端口读 [boundPort]。
     * 这一步是必须的：协议里**没有**"我没有 TCP"这种表达 —— [Codec.validateProfile] 要求
     * `tcpPort` 落在 `1..65535`，所以对外声明 0 会让对端**直接拒收整份资料**（症状是
     * 握手超时 `peer-unreachable`，比端口撞车更难查）。能守住的唯一不变量是：
     * **广播出去的端口必须是我们真的绑上的那一个**。
     */
    suspend fun start(): Boolean {
        if (started) return true
        started = true
        // 开机第一件事：清掉上一进程留下的 `.part`，结果留在 [stalePartSweep] 里。
        //
        // 为什么要在这里自动做，而不是等上层来调 [sweepStaleParts]：core 的传输计划是
        // **进程内**的（`offers` / `outgoing` 两个内存 map），重启后没有任何一帧能认领这些
        // 半截文件 —— 它们既不会被续传，也不会被转正，只会一直占着磁盘。审计里说的
        // "`.part` 残留零出口"就是这件事。放在绑端口之前，顺序上也不可能删到在途文件。
        stalePartSweep = sweepStaleParts()
        if (legacyTcpListener) {
            if (!server.start(tcpPort) && (tcpPort == 0 || !server.start(0))) {
                started = false
                return false
            }
            // F1：跨网文件的独立 TLS 监听（与明文监听并存、互不影响）。
            // 起不来（平台不支持 / 端口被占）就只是**没有这条入口** —— 局域网原路径不受影响。
            val listener = crossNetworkListener
            val filePort = crossNetworkFilePort
            if (listener != null && filePort != null) {
                crossNetworkBoundPort = listener.start(filePort) { connection ->
                    // 协议逐字复用：这里只负责「连接从哪来」，服务端逻辑全在 servePullFrame。
                    runCatching { server.servePullFrame(connection.read, connection.write) }
                    runCatching { connection.close() }
                }
            }
        } else {
            // luciola 信道模式：**一帧都不从这两个端口进出**。
            // boundPort 保持 null，上层据此如实呈现「没有 legacy 入口」，
            // 而不是绑上了却装作没有。
            crossNetworkBoundPort = null
        }
        eventJob = scope.launch {
            server.events.collect { event ->
                when (event) {
                    is TransferServerEvent.Served ->
                        _events.tryEmit(
                            TransferEvent.Completed(
                                transferId = event.transferId,
                                peerId = peerOf(event.transferId),
                                incoming = false,
                                savedPaths = emptyList(),
                            ),
                        )

                    is TransferServerEvent.Disconnected ->
                        _events.tryEmit(
                            TransferEvent.Failed(
                                transferId = event.transferId,
                                peerId = peerOf(event.transferId),
                                incoming = false,
                                reason = "disconnected",
                            ),
                        )

                    is TransferServerEvent.DiagnosticError ->
                        _events.tryEmit(
                            TransferEvent.Failed(
                                transferId = "",
                                peerId = "",
                                incoming = false,
                                reason = event.message,
                            ),
                        )

                    is TransferServerEvent.Progress ->
                        _events.tryEmit(
                            TransferEvent.Progress(
                                transferId = event.transferId,
                                peerId = peerOf(event.transferId),
                                fileId = event.fileId,
                                done = event.sent,
                                total = event.size,
                                incoming = false,
                            ),
                        )
                }
            }
        }
        // 这里原来无条件再 server.start(tcpPort) 一次（幂等：已绑上就直接返回 true）。
        // 信道模式下不能走这一句 —— 它会把刚才刻意不建的明文监听又建起来。
        return if (legacyTcpListener) server.start(tcpPort) else true
    }

    suspend fun stop() {
        // 停之前把待处理项**显式作废并把清单交出去**（[abandonPending]）。
        //
        // 这些表随进程消失，但上层的会话是持久化的：不发这条通知，"待接收"的卡片就会
        // 永远停在那里 —— 正是审计里"重启后待处理 offer 没有出路"的另一半。
        // 放在这里（而不是等上层自己调）是为了**不依赖新接线**：宿主今天就会调 `stop()`，
        // 于是"显式作废"当场生效；放在收发泵关闭之前，也让这条通知赶在会话收摊前发出。
        abandonPending(ABANDON_REASON_SHUTDOWN)
        // 在途拉取也要停：以前 `pull()` 的协程没有任何人取消它，`stop()` 之后它们会继续
        // 读写 socket，直到 60s 空闲超时。`.part` 留着，下一次 [start] 的
        // 清扫会处理掉它 —— 跨进程续传本来就不存在（计划表是内存的）。
        cancelActivePulls(discardParts = false)
        eventJob?.cancel()
        eventJob = null
        server.stop()
        runCatching { crossNetworkListener?.stop() }
        crossNetworkBoundPort = null
        started = false
    }

    // ---------------------------------------------------------------- 发送方

    /**
     * 发起一次发送。返回 transferId；失败返回 null。
     *
     * offer 按 `OFFER_FILES_PER_PACKET`（6）分片，同一 transferId 共用 `seq`/`total`。
     * 普通文件带 24 小时认领期；图片/贴纸/更新包不带。
     *
     * [groupId]/[groupRev] 非空表示这是**群聊媒体的一次点对点投递**（协议 §3）。它改变的是
     * 收端的判据：群图的内联上限只有私聊的一半（10MB，见 [FileTransfer.GROUP_IMG_AUTO_ACCEPT]）。
     * 以前这两个字段只有收端在用（[IncomingOffer] 读得到），发端恒发 null —— 于是本客户端
     * **永远发不出一条协议认可的群图**：带了群 id 的图会被收端当成私聊图自动收下。
     *
     * [tableText] 是表格图片的文字视图。超上限时**在这里**按 UTF-8 边界截断，
     * 并把 `tableTextTruncated` 置真 —— 截断规则属于这条帧，不该指望每个调用方都记得。
     */
    /**
     * **同步**报价：逐片等 ACK，失败即返回 null（不排队）。
     *
     * task-63 起**报价有两条路，行为不同、且是有意的**：
     *  · 这条（[offerFiles]）：调用方是**桌面 UI 的发送路径**（它需要一个即时的成功/失败来画卡片），
     *    所以保留「等 ACK + 失败返回 null」；它跑在 UI 的协程里，不阻塞请求线程；
     *  · [offerFilesAsync]：调用方是**控制面的 HTTP 路由**（不能阻塞请求线程），
     *    登记即返回 + 后台快速投递 + 失败入队重试（状态在 [offerDeliveries] 里如实呈现）。
     *
     * 两条路都**不丢**：同步路失败时调用方拿得到 null（可以重试）；异步路自己入队重试。
     * 之所以不把同步路也改成入队：那会改变 UI 的即时成功/失败语义，而 UI 的调用点在
     * `composeApp`（本轮 scope 之外）—— 迁移时要一起改，别只改这里。
     */
    suspend fun offerFiles(
        peerId: String,
        files: List<LocalFile>,
        purpose: String? = null,
        msgId: String? = null,
        groupId: String? = null,
        groupRev: Int? = null,
        tableText: String? = null,
        isFolder: Boolean = false,
        folderRootName: String? = null,
    ): String? {
        val prepared = prepareOffer(peerId, files, purpose, msgId, groupId, groupRev, tableText, isFolder, folderRootName)
            ?: return null
        val started = nowMillis()
        val outcome = deliverOffer(prepared)
        recordDelivery(
            prepared = prepared,
            state = if (outcome.ok) "offered" else "ack-timeout",
            elapsedMs = nowMillis() - started,
            detail = if (outcome.ok) null else "sendReliable=false（清单没有等到 ACK）",
            lastSendSummary = outcome.lastSendSummary,
        )
        if (!outcome.ok) {
            stateLock.withLock { outgoing.remove(prepared.transferId) }
            return null
        }
        return prepared.transferId
    }

    /**
     * 请求对端免确认接收（`file-ctl direct`）。
     *
     * 为什么放在 `offer` **之后**而不是合成一条：协议里 `direct` 是独立 op，桌面端
     * `onDirectRequest` 要求那条 transfer 已经存在且状态是 `offering`
     * （`files.ts:1006`）。`sendReliable` 每条都等到对端 ACK 才返回，所以清单一定先落地。
     */
    private suspend fun sendDirect(peerId: String, transferId: String) {
        sendSimple(peerId, FileCtlOps.DIRECT, transferId)
    }

    /**
     * 兜底重发一次 `direct`。
     *
     * 存在的理由是一条真实存在的竞态：桌面端在**回完 ACK 之后**才把信封交给
     * `files.ts` 的 handler（`messenger.ts` 的 `handle` 里先 `udp.send(ack)` 再分发），
     * 所以"我们收到 offer 的 ACK"并不严格等于"对方已经建好了那条 transfer"。
     * 对方此时会因为 `row.status !== 'offering'` 直接返回，而我们没有第二次机会。
     *
     * 一次、两秒、且只在还没被接受时发 —— 不引入无限重试，也不新增状态。
     */
    private fun scheduleDirectRetry(peerId: String, transferId: String) {
        scope.launch {
            delay(DIRECT_RETRY_MS)
            val stillPending = stateLock.withLock {
                val transfer = outgoing[transferId]
                transfer != null && !transfer.accepted
            }
            if (stillPending) sendDirect(peerId, transferId)
        }
    }

    /** 主动撤回一次还没被接受的发送。 */
    suspend fun cancelOutgoing(transferId: String) {
        val transfer = stateLock.withLock { outgoing.remove(transferId) } ?: return
        sendSimple(transfer.peerId, FileCtlOps.CANCEL, transferId)
    }

    /** 已发出、尚未收到 `accept` 的传输。 */
    suspend fun pendingOutgoing(): List<OutgoingTransfer> = pendingTransfers().outgoing

    // ---------------------------------------------------------------- 接收方

    /** 收到一份完整 offer、等用户决定的传输。 */
    suspend fun pendingOffers(): List<IncomingOffer> = pendingTransfers().offers

    /**
     * 同意接收：先回 `accept`，再开 TCP 去拉。
     *
     * ## 24 小时窗口（协议承诺 vs 此前无人执行）
     *
     * `FILE_OFFER_TTL` 一直写在 `expiresAt` 里，**却没有任何一处拿它比较过** ——
     * 于是隔天再点「接收」照样发 accept，然后必然失败（对方那侧的报价早就不在了），
     * 而界面上只有一句来路不明的错误。用户看到的是"这软件点了没反应"。
     *
     * 现在的行为：**报价已过期就不发 accept**，直接把这条标成失败并给出可归因的
     * [EXPIRED_REASON]。不动 `expiresAt` 的语义、也不改协议 —— 这是纯本端判据。
     *
     * 注意 `expiresAt == null` 表示**没有窗口**（当前媒体类报价就是这样，见生成处
     * 的 `if (prepared.isMedia) null else …`），不是"已过期"。
     */
    fun accept(transferId: String) {
        scope.launch {
            val offer = stateLock.withLock {
                val off = offers.remove(transferId) ?: acceptedOffers[transferId]
                if (off != null) acceptedOffers[transferId] = off
                off
            } ?: return@launch
            val expiresAt = offer.expiresAt
            if (expiresAt != null && nowMillis() > expiresAt) {
                _events.tryEmit(
                    TransferEvent.Failed(
                        transferId = transferId,
                        peerId = offer.peerId,
                        incoming = true,
                        reason = EXPIRED_REASON,
                    ),
                )
                return@launch
            }
            sendSimple(offer.peerId, FileCtlOps.ACCEPT, transferId)
            // 信道模式：登记后由路由器收字节（不发 pull）；否则走 legacy 拨号拉取。
            if (registerOnChannel(offer)) return@launch
            pull(offer)
        }
    }

    /**
     * 把一次入站 transfer 登记到信道路由器。返回 true = 已接管（调用方**不要**再 pull）。
     *
     * 两种不接管的情形，都必须 **fallback 到 legacy**而不是静默失败：
     *  - 没传路由器（未开 fch1 / 未开实验信道）；
     *  - 某个文件的相对路径越界（`path-escape`）—— 那是拒绝，不是改走 legacy。
     */
    private fun registerOnChannel(offer: IncomingOffer): Boolean {
        val register = registerChannelInbound ?: return false
        // 目录由本类算（与 legacy 拉取同一个公式），宿主只负责登记 —— 这样
        // "信道模式的文件落到别处"这类问题在结构上就不可能发生。
        return register(offer, saveDirFor(offer))
    }

    /**
     * 一次入站 transfer 的落盘目录（`<downloadDir>/<rootName>`，rootName 先过路径净化）。
     *
     * 抽出来给信道接管缝用：host 侧登记时要与 legacy 拨号拉取**写同一个目录**，
     * 否则同一个 transfer 在两条路径下会落到两个地方（用户看到"文件传完了但找不到"）。
     */
    internal fun saveDirFor(offer: IncomingOffer): String {
        val root = downloadDir()
        makeDirectories(root)
        val safeRoot = sanitizeRelativePath(offer.rootName) ?: offer.transferId
        val saveDir = pathJoin(root, safeRoot)
        makeDirectories(saveDir)
        return saveDir
    }

    /** 续传失败的入站传输。对齐 files.ts，仅用于入站 Failed 续拉。 */
    fun resumeTransfer(transferId: String) {
        scope.launch {
            val offer = stateLock.withLock { acceptedOffers[transferId] } ?: return@launch
            pull(offer)
        }
    }

    /** 拒收：回 `decline`。 */
    fun decline(transferId: String) {
        scope.launch {
            val offer = stateLock.withLock { offers.remove(transferId) } ?: return@launch
            sendSimple(offer.peerId, FileCtlOps.DECLINE, transferId)
            _events.tryEmit(TransferEvent.Declined(transferId, offer.peerId))
        }
    }

    /** 收到 `file-ctl` 信封的入口，由 `NodeRuntime` 的入站泵调用。 */
    fun onEnvelope(envelope: Envelope) {
        if (envelope.type != MsgTypes.FILE_CTL) return
        val payload = envelope.payload as? JsonObject ?: return
        val op = (payload["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        when (op) {
            FileCtlOps.OFFER -> onOffer(envelope.from, payload)
            FileCtlOps.ACCEPT -> onAccept(payload)
            FileCtlOps.DECLINE -> onPeerKilled(payload)
            FileCtlOps.CANCEL -> onPeerKilled(payload)
            FileCtlOps.DIRECT -> onDirectRequest(payload)
            else -> Unit
        }
    }

    /**
     * 对端请求我们**免确认**接收（`op:'direct'`）。
     *
     * **这里是本文件踩过的一个真缺陷**：`direct` 原本和 `decline`/`cancel` 一起走
     * [onSimple]，于是"请求免确认"被当成"对方拒收"处理 —— 挂起的接收邀请会被删掉、
     * 还发一条 `Declined` 事件，用户看到的是"对方拒收了"，而对方其实在等你收下。
     * 触发条件是**对方声明了 `fd1` 且自己的免确认开关是打开的**（桌面端默认如此），
     * 也就是说这条错误路径在真机上比正确的路径更容易走到。
     *
     * 现在故意**什么都不做**：本客户端不声明 `fd1`（见 [MsgTypes.CAP_FILE_DIRECT]），
     * 按协议对端就不该给我们发这一条；真收到了也只是把邀请留在「待接收」，
     * 由用户自己点。把"是否免确认落盘"这个决定留在人手里，是手机端的取舍。
     */
    private fun onDirectRequest(payload: JsonObject) {
        // 只做一次解码校验：形状不对的信封连"已知但忽略"都不算，直接丢。
        decodeSimple(payload)
    }

    private fun onOffer(peerId: String, payload: JsonObject) {
        val offer = runCatching {
            Codec.wireJson.decodeFromJsonElement(FileCtlOffer.serializer(), payload)
        }.getOrNull() ?: return
        if (offer.files.isEmpty()) return

        scope.launch {
            val assembled = stateLock.withLock {
                val partial = partials.getOrPut(offer.transferId) {
                    PartialOffer(
                        transferId = offer.transferId,
                        peerId = peerId,
                        rootName = offer.rootName,
                        expected = offer.fileCount,
                        totalSize = offer.totalSize,
                        purpose = offer.purpose,
                        msgId = offer.msgId,
                        expiresAt = offer.expiresAt,
                        groupId = offer.groupId,
                        groupRev = offer.groupRev,
                        tableText = offer.tableText,
                        tableTextTruncated = offer.tableTextTruncated,
                        isFolder = offer.isFolder,
                    )
                }
                partial.files += offer.files
                // 分片可能乱序到达，凑齐 fileCount 才算完整；`seq == total` 只说明这是最后一片。
                if (partial.files.size < partial.expected) return@withLock null
                partials.remove(offer.transferId)
                partial.toOffer()
            } ?: return@launch

            if (assembled.files.isEmpty()) return@launch

            // 群聊图片的内联上限只有一半：`file-ctl offer` 带 `groupId` 就是一次
            // 群聊媒体的点对点投递（协议 §3），超限按普通文件走手动接收。以前这里恒用 20MB，
            // 于是 `GROUP_IMG_AUTO_ACCEPT` 是个没人读的常量：群里的 15MB 图会被自动收下，
            // 而发送端按协议根本没把它标成免确认图片。
            //
            // 协议 §3 对"`groupId` + `purpose` 且总量超 10MB"的入站 offer 写的是**拒绝**；
            // 这里选择退化成手动领取而不是拒收：这条上限的意图是"超限群图按普通文件手动接收"，
            // 拒收会让一条本来能收的图彻底收不到。差别只在"合规但更宽松"，且只对不合规的
            // 发送端可见。
            val autoAcceptLimit = if (assembled.groupId != null) {
                FileTransfer.GROUP_IMG_AUTO_ACCEPT
            } else {
                FileTransfer.IMG_AUTO_ACCEPT
            }
            val autoAccept = assembled.purpose == FilePurpose.IMAGE &&
                assembled.totalSize <= autoAcceptLimit
            if (!autoAccept) {
                stateLock.withLock { offers[assembled.transferId] = assembled }
            } else {
                // 自动接收也要登记进 `acceptedOffers`：`pull()` 用这张表当"这次拉取还被
                // 需要吗"的凭据（对方撤回、本地放弃都会先把它摘掉），`resumeTransfer()`
                // 也只认它。不登记的话，一张自动接收失败的图片会有一张点不动的「继续」。
                stateLock.withLock { acceptedOffers[assembled.transferId] = assembled }
            }
            // 自动接收也要先报一次 Offered：UI 要靠 rootName/totalSize 才能画出文件卡片，
            // 否则收到的图片在聊天流里第一次露面时是个没有名字的进度条。
            // 先登记再发事件，避免 UI 收到事件立刻 accept 时查不到这份 offer。
            _events.tryEmit(TransferEvent.Offered(assembled))
            if (autoAccept) {
                sendSimple(assembled.peerId, FileCtlOps.ACCEPT, assembled.transferId)
                // 信道模式：登记后由路由器收字节（不发 pull）；否则走 legacy 拨号拉取。
                if (!registerOnChannel(assembled)) pull(assembled)
            }
        }
    }

    private fun onAccept(payload: JsonObject) {
        val simple = decodeSimple(payload) ?: return
        scope.launch {
            val transfer = stateLock.withLock {
                outgoing[simple.transferId]?.also { it.accepted = true }
            }
            // 信道模式：对方接下之后**我们推**字节（它不会再拨 17879/17881 来拉）。
            // 失败只记一条诊断，不把已经 accept 的传输判死 —— 用户重试 / 对端也会回 cancel。
            val push = pushFileOnChannel
            if (transfer != null && push != null) {
                runCatching { push(transfer.transferId, transfer.peerId, transfer.files) }
                    .onFailure { println("[luciola] push failed " + simple.transferId + ": " + it) }
            }
        }
    }

    /**
     * 对端终结了这次传输：`decline`（拒收我们发的）或 `cancel`（撤回它发来的）。
     *
     * 两种 op 的**本地处理相同** —— 我们这边那张卡都该消失并记一条事件。区分只对
     * 对端有意义，所以这里不再透传 `op`（上一版透传了却没用，白留一个可能传错的参数）。
     *
     * **一份传输在我们这边可能停在三个阶段**，所以要查三张表（每张表查完就返回，
     * 一次 `cancel` 只可能命中一处）：
     * 1. `outgoing` —— 我们发出去的报价被对方拒收；
     * 2. `offers` —— 对方发来的、还没点过头的来件被撤回；
     * 3. `acceptedOffers` / `pulling` —— 已经点了「接收」、正等着拉或正在拉的被撤回。
     *
     * 前两版各漏一半：第 2 项从 `offers` 里静默删掉，第 3 项**两张表都没查**。
     * 界面上的卡片完全是事件驱动的（`handleOnTransfer` 只认 [TransferEvent]），
     * 所以漏报事件的后果是卡片**永远留在聊天里**：对方早就撤回了，你点「接收」
     * 也不再有任何反应 —— `offers` 里那条已经不在了，只剩一张死卡。
     */
    private fun onPeerKilled(payload: JsonObject) {
        val simple = decodeSimple(payload) ?: return
        scope.launch {
            val outgoing = stateLock.withLock { outgoing.remove(simple.transferId) }
            if (outgoing != null) {
                _events.tryEmit(TransferEvent.Declined(simple.transferId, outgoing.peerId))
                return@launch
            }
            val pending = stateLock.withLock { offers.remove(simple.transferId) }
            if (pending != null) {
                _events.tryEmit(TransferEvent.Declined(simple.transferId, pending.peerId))
                return@launch
            }
            val accepted = stateLock.withLock { acceptedOffers.remove(simple.transferId) }
            val active = stateLock.withLock { pulling[simple.transferId] }
            if (accepted == null && active == null) return@launch
            if (active != null) {
                // 在途那次拉取要真的停下来：`pull` 的 catch 只认 `cancelRef.canceled`，
                // 由它去报 Declined —— 「对方取消」与「本地放弃」共用同一套收尾。
                active.cancelRef.canceled = true
                runCatching { active.cancelRef.socket?.close() }
                active.partPaths.forEach { discardPartFile(it) }
            } else {
                // 已接受但拉取还没登记上来（`accept()` 的协程还在路上）：这里没人会替我们报。
                _events.tryEmit(TransferEvent.Declined(simple.transferId, accepted?.peerId ?: ""))
            }
        }
    }

    /**
     * 出站 transfer 的对端。
     *
     * `TransferServerEvent` 只带 transferId（服务端不认识 peerId），所以从登记表回查。
     * 登记表在 [stateLock] 下读写，这里也必须持锁 —— 与 `accept`/`decline` 并发时不持锁就是竞态。
     */
    private suspend fun peerOf(transferId: String): String =
        stateLock.withLock { outgoing[transferId]?.peerId } ?: ""

    private suspend fun pull(offer: IncomingOffer) {
        // 只拉还在 `acceptedOffers` 里的：取消（对方 `cancel` 或本地放弃）就是把它摘掉，
        // 而 `accept()` 的协程与取消可能赛跑 —— 不挡这一下，被撤回的传输会重新拉起来，
        // 那张已经作废的卡片会跟着 Progress 事件"复活"。
        if (!stateLock.withLock { acceptedOffers.containsKey(offer.transferId) }) return
        val endpoint = peerAddress(offer.peerId)
        if (endpoint == null) {
            _events.tryEmit(
                TransferEvent.Failed(
                    transferId = offer.transferId,
                    peerId = offer.peerId,
                    incoming = true,
                    reason = "peer-offline",
                ),
            )
            return
        }
        val root = downloadDir()
        makeDirectories(root)
        val safeRoot = sanitizeRelativePath(offer.rootName) ?: offer.transferId
        val saveDir = pathJoin(root, safeRoot)
        makeDirectories(saveDir)

        val totals = offer.files.associate { it.fileId to it.size }
        val received = mutableMapOf<String, Long>()
        val saved = mutableListOf<String>()

        // 登记这次拉取：`cancelIncoming` 要靠它拿到取消句柄与"该删哪些 `.part`"的清单。
        val cancelRef = PullCancelRef()
        val active = ActiveIncomingPull(
            cancelRef = cancelRef,
            partPaths = offer.files.filterNot { it.isDir }.map { pathJoin(saveDir, it.relPath) + PART_SUFFIX },
        )
        stateLock.withLock { pulling[offer.transferId] = active }

        // F1：宿主给的跨网计划。空表当「没有计划」——否则会一条都不试就直接失败。
        val plan = crossNetwork?.plan(offer.peerId, endpoint)
        val attempts = plan?.attempts?.takeIf { it.isNotEmpty() }
        try {
            (dataPlane ?: LegacyTcpDataPlane()).pull(
                PullOptions(
                    host = endpoint.host,
                    port = endpoint.port,
                    selfId = selfId,
                    transferId = offer.transferId,
                    files = offer.files,
                    saveDir = saveDir,
                    cancelRef = cancelRef,
                    attempts = attempts,
                    connectRaw = plan?.connectRaw,
                    onProgress = { fileId, delta ->
                        val done = (received[fileId] ?: 0L) + delta
                        received[fileId] = done
                        _events.tryEmit(
                            TransferEvent.Progress(
                                transferId = offer.transferId,
                                peerId = offer.peerId,
                                fileId = fileId,
                                done = done,
                                total = totals[fileId] ?: 0L,
                                incoming = true,
                            ),
                        )
                    },
                    supportsWait = peerSupportsWait(offer.peerId),
                ),
            )
            offer.files.forEach { saved += pathJoin(saveDir, it.relPath) }
            stateLock.withLock { acceptedOffers.remove(offer.transferId) }
            _events.tryEmit(
                TransferEvent.Completed(
                    transferId = offer.transferId,
                    peerId = offer.peerId,
                    incoming = true,
                    savedPaths = saved,
                ),
            )
        } catch (e: PullException) {
            // 本地取消（用户放弃 / 停机）不该报成"失败"：那条 `.part` 是我们自己删的，
            // 也不是网络出了问题。上层看到 Declined 才会把卡片从"下载中"收掉，
            // 而不是画一张红色的失败卡让人以为传输出错了。
            if (cancelRef.canceled) {
                _events.tryEmit(TransferEvent.Declined(offer.transferId, offer.peerId))
            } else {
                _events.tryEmit(
                    TransferEvent.Failed(
                        transferId = offer.transferId,
                        peerId = offer.peerId,
                        incoming = true,
                        reason = e.reason,
                    ),
                )
            }
        } finally {
            stateLock.withLock { if (pulling[offer.transferId] === active) pulling.remove(offer.transferId) }
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private suspend fun sendSimple(peerId: String, op: String, transferId: String) {
        val payload = Codec.wireJson.encodeToJsonElement(
            FileCtlSimple.serializer(),
            FileCtlSimple(op = op, transferId = transferId),
        )
        sendReliable(peerId, payload)
    }

    private fun decodeSimple(payload: JsonObject): FileCtlSimple? =
        runCatching { Codec.wireJson.decodeFromJsonElement(FileCtlSimple.serializer(), payload) }.getOrNull()

    /** 分片重组中的 offer。 */
    private class PartialOffer(
        val transferId: String,
        val peerId: String,
        val rootName: String,
        val expected: Int,
        val totalSize: Long,
        val purpose: String?,
        val msgId: String?,
        val expiresAt: Long?,
        val groupId: String?,
        val groupRev: Int?,
        val tableText: String?,
        val tableTextTruncated: Boolean?,
        val isFolder: Boolean? = null,
    ) {
        val files = mutableListOf<FileMeta>()

        fun toOffer(): IncomingOffer? {
            val plans = files.mapNotNull { meta ->
                val rel = sanitizeRelativePath(meta.path) ?: return@mapNotNull null
                IncomingFilePlan(
                    fileId = meta.fileId,
                    relPath = rel,
                    size = meta.size,
                    isDir = meta.isDir == true,
                )
            }
            if (plans.isEmpty()) return null
            return IncomingOffer(
                transferId = transferId,
                peerId = peerId,
                rootName = rootName,
                files = plans,
                totalSize = totalSize,
                purpose = purpose,
                msgId = msgId,
                expiresAt = expiresAt,
                groupId = groupId,
                groupRev = groupRev,
                tableText = tableText,
                tableTextTruncated = tableTextTruncated,
                isFolder = isFolder == true,
            )
        }
    }

    /**
     * 扫一遍下载目录，删掉**没有任何在途传输认领**的 `.part`，返回清单。
     *
     * [start] 会自动调一次；上层也可以在用户点"清理残留"时再调。
     * [keep] 是额外的白名单（传 `.part` 全路径，与 [PartSweepReport.deleted] 同一个形状）。
     * **在途拉取的 `.part` 永远受保护**，不用调用方记得传进来 —— 少一个"忘了传就删掉正在写的文件"
     * 的机会。
     *
     * ## "过期"在本层是怎么定义的
     *
     * 这里**不看时间戳**，看的是"有没有人能认领它"：本层的传输计划（`offers` / `outgoing`）
     * 是进程内的内存表，重启即空，所以**进程启动时存在的任何一个 `.part` 都永远不会被续传、
     * 也永远不会被转正**——它已经与任何可能的 transferId 失去关联，删掉不损失任何可恢复的信息。
     * 这比"按文件年龄删"更保守：年龄删不掉"用户三分钟前刚取消、但本来还能续传"的那种，
     * 又可能留下"一天前但仍有活拉取在写"的那种。
     *
     * ## 故意不扫的根目录（返回 `scanned = false`）
     *
     * - 空串 / `.` / `..`：根就是进程 CWD，`.part` 这个后缀太普通（浏览器、下载器都用它），
     *   在其中删文件等于替用户清理**别人**的残留。宿主没配下载目录（[NodeRuntime] 的默认值
     *   就是 `.`）时，宁可什么都不做。
     * - 相对路径：同上。
     *
     * 深度与条目数都有上限（[SWEEP_MAX_DEPTH] / [SWEEP_MAX_ENTRIES]），配置错误不会变成
     * 一次全盘遍历。
     */
    suspend fun sweepStaleParts(keep: Set<String> = emptySet()): PartSweepReport =
        TransferPartSweeper.sweepStaleParts(downloadDir(), keep, protectedParts(), ::discardPartFile)

    /**
     * 用户明确放弃一份入站传输。
     *
     * 两条形态都覆盖，因为界面上"放弃"这个动作对两者是同一个按钮：
     * - 还挂在"待接收"的邀请：摘掉、回一条 `decline`、发 [TransferEvent.Declined]；
     * - 正在拉的：置取消标志 + 关掉 socket（否则读要等满 60s 空闲超时），失败路径会发
     *   [TransferEvent.Declined] 而不是 Failed（见 `pull`）。
     *
     * [discardParts] 默认删掉这次传输的 `.part`。"普通失败留着续传"不适用于这里：
     * 用户点了放弃，就不该再留半成品。删的那一条路径就是 [discardPart]。
     *
     * @return 这个 transferId 是否真的存在（两边都查不到 → false，上层别记一条假动作）。
     */
    suspend fun cancelIncoming(transferId: String, discardParts: Boolean = true): Boolean {
        val pending = stateLock.withLock { offers.remove(transferId) ?: acceptedOffers.remove(transferId) }
        val active = stateLock.withLock { pulling[transferId] }
        if (pending == null && active == null) return false
        if (pending != null) {
            sendSimple(pending.peerId, FileCtlOps.DECLINE, transferId)
            _events.tryEmit(TransferEvent.Declined(transferId, pending.peerId))
        }
        if (active != null) {
            active.cancelRef.canceled = true
            runCatching { active.cancelRef.socket?.close() }
            if (discardParts) active.partPaths.forEach { discardPartFile(it) }
        }
        return true
    }

    /** 正在拉的入站传输 id。 */
    suspend fun activeIncomingIds(): Set<String> = stateLock.withLock { pulling.keys.toSet() }

    /** 待处理项快照（同一把锁），等价于 [pendingOffers] + [pendingOutgoing] 一起读。 */
    suspend fun pendingTransfers(): PendingTransfers = stateLock.withLock {
        PendingTransfers(offers.values.toList(), outgoing.values.toList())
    }

    /** 重启对账：本进程真正挂着的待处理项 + 盘上剩下的半成品。 */
    suspend fun backlog(): TransferBacklog = TransferBacklog(
        offers = pendingOffers(),
        outgoing = pendingOutgoing(),
        leftoverParts = leftoverParts(),
    )

    /**
     * 显式作废所有待处理传输，并把清单交回上层。
     *
     * **为什么是"作废"而不是"恢复"**：恢复需要把 transferId / 文件计划持久化下来，
     * 而本层的这两张表是内存的。没有可恢复之物时，另一条出路就是"显式作废并把结果
     * 告知上层" —— 这里做的是后者：入站邀请发 [TransferEvent.Declined]，出站报价发
     * [TransferEvent.Failed]。`[stop]` 会自动以 [ABANDON_REASON_SHUTDOWN] 调一次。
     */
    suspend fun abandonPending(reason: String = ABANDON_REASON_SHUTDOWN): PendingAbandonReport {
        val drained = stateLock.withLock {
            val snapshot = PendingTransfers(offers.values.toList(), outgoing.values.toList())
            offers.clear()
            outgoing.clear()
            snapshot
        }
        drained.offers.forEach { _events.tryEmit(TransferEvent.Declined(it.transferId, it.peerId)) }
        drained.outgoing.forEach {
            _events.tryEmit(
                TransferEvent.Failed(
                    transferId = it.transferId,
                    peerId = it.peerId,
                    incoming = false,
                    reason = reason,
                ),
            )
        }
        return PendingAbandonReport(reason = reason, offers = drained.offers, outgoing = drained.outgoing)
            .also { lastAbandon = it }
    }

    /** 删掉半截的 `.part`（用户明确放弃时用；普通失败要留着续传）。
     *  参数是**正式文件的路径**（与 [existingPartSize] 同一个坐标系），后缀由本方法补上。 */
    fun discardPart(path: String) = TransferPartSweeper.discardPart(path)

    /** 删一个**已经带 `.part` 后缀**的全路径（与 [discardPart] 差一层坐标系）。 */
    fun discardPartFile(partPath: String) = TransferPartSweeper.discardPartFile(partPath)

    /** 目标文件已存在时的大小，UI 想显示"续传中"可以问它。 */
    fun existingPartSize(path: String): Long = TransferPartSweeper.existingPartSize(path)

    /** 现在盘上还剩哪些半截文件（只读，不删）。 */
    fun leftoverParts(): List<PartFile> = TransferPartSweeper.leftoverParts(downloadDir)

    /** 正在拉的传输将来会写的那些 `.part` —— 清扫时无条件保护。 */
    private suspend fun protectedParts(): Set<String> =
        stateLock.withLock { pulling.values.flatMap { it.partPaths }.toSet() }

    /** 取消所有在途拉取。[discardParts] 为真时连 `.part` 一起删（用户放弃）；停机时留着。 */
    private suspend fun cancelActivePulls(discardParts: Boolean) {
        val active = stateLock.withLock {
            val snapshot = pulling.values.toList()
            pulling.clear()
            snapshot
        }
        active.forEach {
            it.cancelRef.canceled = true
            runCatching { it.cancelRef.socket?.close() }
            if (discardParts) it.partPaths.forEach { path -> discardPartFile(path) }
        }
    }

    /** 一次在途拉取的登记项。 */
    private class ActiveIncomingPull(
        val cancelRef: PullCancelRef,
        /** 这次拉取会写的 `.part` 全路径（目录项不算）。 */
        val partPaths: List<String>,
    )


    /**
     * **登记即返回**（task-62）：先登记 transfer、立刻把 transferId 交出去，投递（逐片等 ACK）
     * 放到后台 —— 请求路径上不再等网络。
     *
     * 为什么可以：`offerFiles` 本来就在**发之前**把 transfer 登记进 `outgoing`，所以「登记」与
     * 「投递」是两件事；协议要求的是**投递**要可靠（收端拿到完整 offer 才能 accept），不是
     * 「HTTP 调用方必须等到 ACK」。
     *
     * 投递结果不在这里回，而是：① 进 [offerDeliveries] 的只读状态（`/v1/transfers`）；
     * ② 走既有的 `TransferEvent` 流。**没有读出口的 async 等于把「发不出去」变成
     * 「看起来发出去了」** —— 所以这两个出口是这条改动的硬要求。
     */
    suspend fun offerFilesAsync(
        peerId: String,
        files: List<LocalFile>,
        purpose: String? = null,
        msgId: String? = null,
        groupId: String? = null,
        groupRev: Int? = null,
        tableText: String? = null,
        isFolder: Boolean = false,
        folderRootName: String? = null,
    ): String? {
        val prepared = prepareOffer(peerId, files, purpose, msgId, groupId, groupRev, tableText, isFolder, folderRootName)
            ?: return null
        recordDelivery(prepared, state = "offering", elapsedMs = 0L, detail = null)
        scope.launch {
            val started = nowMillis()
            // 单次尝试有界（见 offerAttemptTimeoutMs）：到点就按「没拿到 ACK」处理，
            // 状态立刻变成 queued-awaiting-reconnect，而不是让读的人看几十秒的 offering。
            val outcome = withTimeoutOrNull(offerAttemptTimeoutMs) { deliverOffer(prepared, quick = true) }
                ?: DeliverOutcome(ok = false, lastSendSummary = sendReliableDiagnostics()?.summary())
            if (outcome.ok) {
                recordDelivery(
                    prepared = prepared,
                    state = "offered",
                    elapsedMs = nowMillis() - started,
                    detail = null,
                    lastSendSummary = outcome.lastSendSummary,
                    attempts = 1,
                    firstAttemptSummary = outcome.lastSendSummary,
                )
                return@launch
            }
            // task-63：**失败的报价不再丢掉** —— 长连接抖动是常态。这里入队（保留 transfer
            // 登记，等对端恢复后补发），状态如实写 `queued-awaiting-reconnect`；
            // 重试由 scheduleOfferRetry 用退避驱动，总预算用尽才记 `gave-up`。
            recordDelivery(
                prepared = prepared,
                state = "queued-awaiting-reconnect",
                elapsedMs = nowMillis() - started,
                detail = "第一次投递未拿到 ACK，已入队等对端恢复（退避重试；换网会立刻重试）",
                lastSendSummary = outcome.lastSendSummary,
                attempts = 1,
                firstAttemptSummary = outcome.lastSendSummary,
            )
            val queued = QueuedOffer(prepared, startedAt = started, attempt = 1, job = null)
            stateLock.withLock { queuedOffers[prepared.transferId] = queued }
            scheduleOfferRetry(queued, trigger = "backoff")
        }
        return prepared.transferId
    }

    /** 报价里「登记 + 组装」的部分：**不碰网络**，同步就能完成（sync/async 两条路共用）。 */
    private class PreparedOffer(
        val transferId: String,
        val peerId: String,
        val rootName: String,
        val totalSize: Long,
        val metas: List<FileMeta>,
        val chunks: List<List<FileMeta>>,
        val purpose: String?,
        val msgId: String?,
        val groupId: String?,
        val groupRev: Int?,
        val isMedia: Boolean,
        val wireTableText: String?,
        /** 只有**真的截过**才是 true，其余是 null（没截断就不带这个键 —— 老端按 key 集合严格比对）。 */
        val tableTruncated: Boolean?,
        val isFolder: Boolean,
    )

    private suspend fun prepareOffer(
        peerId: String,
        files: List<LocalFile>,
        purpose: String?,
        msgId: String?,
        groupId: String?,
        groupRev: Int?,
        tableText: String?,
        isFolder: Boolean,
        folderRootName: String?,
    ): PreparedOffer? {
        if (files.isEmpty() || files.size > FileTransfer.MAX_FILES_PER_TRANSFER) return null
        if (files.any { it.size < 0 }) return null

        val transferId = newId()
        val outgoingFiles = files.map { OutgoingFile(fileId = newId(), absPath = it.path, size = it.size) }
        val metas = files.zip(outgoingFiles) { local, out -> FileMeta(fileId = out.fileId, path = local.name, size = local.size) }
        val totalSize = files.sumOf { it.size }
        val rootName = folderRootName ?: if (isFolder && files.size > 1) files.first().name + " 等 " + files.size + " 个文件" else files.first().name
        val isMedia = purpose == FilePurpose.IMAGE || purpose == FilePurpose.STICKER || purpose == FilePurpose.UPDATE
        val (wireTableText, tableTruncated) = truncateTableText(tableText)

        stateLock.withLock {
            outgoing[transferId] = OutgoingTransfer(
                transferId = transferId,
                peerId = peerId,
                rootName = rootName,
                files = outgoingFiles,
                totalSize = totalSize,
            )
        }
        return PreparedOffer(
            transferId = transferId,
            peerId = peerId,
            rootName = rootName,
            totalSize = totalSize,
            metas = metas,
            chunks = metas.chunked(FileTransfer.OFFER_FILES_PER_PACKET),
            purpose = purpose,
            msgId = msgId,
            groupId = groupId,
            groupRev = groupRev,
            isMedia = isMedia,
            wireTableText = wireTableText,
            tableTruncated = tableTruncated,
            isFolder = isFolder,
        )
    }

    /** 逐片发 offer 并**等 ACK**（同步路径的全部耗时都在这里），然后请求 direct。 */
    private suspend fun deliverOffer(prepared: PreparedOffer, quick: Boolean = false): DeliverOutcome {
        // quick = 报价那条路：只走最短退避（失败得快而可见，重试由报价方排队驱动）。
        val send: suspend (String, JsonElement) -> Boolean = if (quick) sendReliableQuick else sendReliable
        for ((index, chunk) in prepared.chunks.withIndex()) {
            val offer = FileCtlOffer(
                op = FileCtlOps.OFFER,
                transferId = prepared.transferId,
                msgId = prepared.msgId,
                seq = index + 1,
                total = prepared.chunks.size,
                files = chunk,
                totalSize = prepared.totalSize,
                fileCount = prepared.metas.size,
                rootName = prepared.rootName,
                purpose = prepared.purpose,
                expiresAt = if (prepared.isMedia) null else nowMillis() + FileTransfer.FILE_OFFER_TTL,
                tableText = prepared.wireTableText,
                tableTextTruncated = prepared.tableTruncated,
                groupId = prepared.groupId,
                groupRev = prepared.groupRev,
                isFolder = if (prepared.isFolder) true else null,
            )
            val payload = Codec.wireJson.encodeToJsonElement(FileCtlOffer.serializer(), offer)
            if (!send(prepared.peerId, payload)) {
                // 失败时把**这一次**的逐端点诊断取出来绑到这条 transfer 上 —— 全局 lastSend
                // 会被随后的成功投递覆盖（真机里 z1/z2 就是这样被 z3 的诊断盖住的）。
                return DeliverOutcome(ok = false, lastSendSummary = sendReliableDiagnostics()?.summary())
            }
        }
        // 清单发完（每条都等到 ACK）之后才请求免确认：晚一步的原因见 sendDirect 的注释。
        if (peerSupportsDirect(prepared.peerId)) {
            sendDirect(prepared.peerId, prepared.transferId)
            scheduleDirectRetry(prepared.peerId, prepared.transferId)
        }
        return DeliverOutcome(ok = true, lastSendSummary = sendReliableDiagnostics()?.summary())
    }

    /** 一次投递的结果：成功与否 + **这一次**的逐端点诊断摘要（可能为 null）。 */
    private data class DeliverOutcome(val ok: Boolean, val lastSendSummary: String?)

    /**
     * 记一次报价投递状态。**必须 suspend**：`deliveries` 是普通 `LinkedHashMap`，所有访问都要在
     * [stateLock] 里 —— 这里曾经是裸写，而 [offerDeliveries] 在锁内**迭代**它，两者在不同线程上
     * 并发（投递协程 / 退避重试 / nudge / `/v1/transfers` 读口）⇒ 满量并行下 jvmTest 报
     * `ConcurrentModificationException: LinkedHashIterator`（出现率 ~1/3）。**别用测试重试掩盖它。**
     */
    private suspend fun recordDelivery(
        prepared: PreparedOffer,
        state: String,
        elapsedMs: Long,
        detail: String?,
        lastSendSummary: String? = null,
        attempts: Int = 1,
        /**
         * **首次**尝试的逐端点诊断（task-65）。与 [lastSendSummary] **各留各的、不合成**。
         *
         * 为什么必须有：真机四次里两次是「第 2/3 次才成功」，而成功那次的诊断写着 `sent-via-link`
         * ⇒ **第一次为什么失败完全看不出来**（`attempts=3` 只说试了几次）⇒ 首次成功率 50% 这个数
         * 没有可行动的方向。留下首次的诊断，才能分清「首次是链路 5s ACK 超时」还是「UDP 无应答」。
         */
        firstAttemptSummary: String? = null,
    ) {
        stateLock.withLock {
            // 首次的诊断**跨重试保留**：调用方通常只在第一次传它，后续重试传 null。
            val firstSummary = firstAttemptSummary ?: deliveries[prepared.transferId]?.firstAttemptSummary
            deliveries.remove(prepared.transferId)
            deliveries[prepared.transferId] = OfferDelivery(
                transferId = prepared.transferId,
                peerId = prepared.peerId,
                rootName = prepared.rootName,
                totalSize = prepared.totalSize,
                state = state,
                elapsedMs = elapsedMs,
                detail = detail,
                lastSendSummary = lastSendSummary,
                attempts = attempts,
                firstAttemptSummary = firstSummary,
            )
            while (deliveries.size > MAX_TRACKED_DELIVERIES) {
                val oldest = deliveries.keys.firstOrNull() ?: break
                deliveries.remove(oldest)
            }
        }
    }

    /**
     * 只读出口（task-62）：最近若干次报价的投递状态；`accepted` 取自当前登记表。
     *
     * 读 `outgoing` 必须拿锁（它是普通 MutableMap，跨线程裸读不安全）⇒ 这是个 suspend 函数；
     * 控制面在 `runBlocking` 里调它，而这一步**不碰网络**，仍是毫秒级。
     */
    suspend fun offerDeliveries(): List<OfferDelivery> = stateLock.withLock {
        deliveries.values.map { delivery ->
            delivery.copy(accepted = outgoing[delivery.transferId]?.accepted == true)
        }
    }


    /**
     * 报价失败的**退避重试**（task-63）：失败不丢，等对端恢复后自动补发。
     *
     * 形状与消息队列同源：**幂等键**（transferId + seq，对端按 (transferId, seq) 组装，
     * 重投是覆盖不是新增）、**水位**（attempts + 起始时刻算总预算）、**失败释放**（不占用、
     * 下次接着试）。差别是它在报价方自己手里，不借消息队列 —— 报价不是一条聊天记录。
     *
     * 总预算 [offerDeliveryBudgetMs] 用尽 ⇒ `gave-up` 并摘掉 transfer（如实告诉读的人
     * 「这次报价最终没送到」，而不是永远停在「等恢复」）。
     */
    /**
     * 排队中的报价：重试所需的一切。
     *
     * 为什么要有这个对象：**网络一变就要立刻重试**（[retryQueuedOffersNow]），而它需要
     * 「取消待触发的退避 Job + 拿到这次重试要用的 PreparedOffer」—— 这些原先都在闭包里，nudge 够不到。
     */
    private class QueuedOffer(
        val prepared: PreparedOffer,
        val startedAt: Long,
        var attempt: Int,
        var job: Job?,
    )

    /** transferId → 排队中的报价（只在 `queued-awaiting-reconnect` 期间存在）。 */
    /**
     * transferId → 排队中的报价。与其它共享状态一样用 [stateLock] 保护 ——
     * commonMain 里没有 ConcurrentHashMap（iOS 目标），不能图省事用 java.util。
     */
    private val queuedOffers = mutableMapOf<String, QueuedOffer>()

    /**
     * **网络一变就立刻重试**（task-63 追加）：宿主在换网 / 长连接恢复时调它。
     *
     * 为什么值得接：退避上限 60s ⇒ 抖动的报价最慢要等一分钟，而 K1/K3 的换网恢复口径是
     * **≤10s** —— 报价不该比长连接慢一个数量级。这里只把「到点的重试」提前触发一轮：
     * 幂等键（transferId + seq）保证提前重试与退避重试**不会投出两份**。
     */
    suspend fun retryQueuedOffersNow() {
        val queued = stateLock.withLock { queuedOffers.values.toList() }
        queued.forEach { q ->
            q.job?.cancel()
            // 只是把到点的重试提前触发一轮；job 引用本身的竞争是无害的（只用于取消）。
            q.job = scope.launch { attemptOfferRetry(q, trigger = "nudge") }
        }
    }

    private fun scheduleOfferRetry(queued: QueuedOffer, trigger: String) {
        val backoff = minOf(offerRetryBackoffMs shl (queued.attempt - 1), OFFER_RETRY_MAX_BACKOFF_MS)
        queued.job = scope.launch {
            delay(backoff)
            attemptOfferRetry(queued, trigger)
        }
    }

    /**
     * 试一轮重试。[trigger] 会写进 detail：`backoff`（退避到点）或 `nudge`（换网触发）——
     * 读的人据此知道「这次是等到的还是被推的」（task-63 的可诊断要求）。
     */
    private suspend fun attemptOfferRetry(queued: QueuedOffer, trigger: String) {
        val prepared = queued.prepared
        // 期间若已被成功投递（或已被摘掉）就不再重试。
        val stillQueued = stateLock.withLock { deliveries[prepared.transferId]?.state == "queued-awaiting-reconnect" }
        if (!stillQueued) return
        val elapsed = nowMillis() - queued.startedAt
        if (elapsed >= offerDeliveryBudgetMs) {
            recordDelivery(
                prepared = prepared,
                state = "gave-up",
                elapsedMs = elapsed,
                detail = "重试预算用尽（" + offerDeliveryBudgetMs + "ms，尝试 " + queued.attempt + " 次）",
                attempts = queued.attempt,
            )
            stateLock.withLock {
                queuedOffers.remove(prepared.transferId)
                outgoing.remove(prepared.transferId)
            }
            return
        }
        val outcome = withTimeoutOrNull(offerAttemptTimeoutMs) { deliverOffer(prepared, quick = true) }
            ?: DeliverOutcome(ok = false, lastSendSummary = sendReliableDiagnostics()?.summary())
        val next = queued.attempt + 1
        queued.attempt = next
        if (outcome.ok) {
            recordDelivery(
                prepared = prepared,
                state = "offered",
                elapsedMs = nowMillis() - queued.startedAt,
                detail = "第 " + next + " 次投递成功（" + trigger + "）",
                lastSendSummary = outcome.lastSendSummary,
                attempts = next,
            )
            stateLock.withLock { queuedOffers.remove(prepared.transferId) }
            return
        }
        recordDelivery(
            prepared = prepared,
            state = "queued-awaiting-reconnect",
            elapsedMs = nowMillis() - queued.startedAt,
            detail = "第 " + next + " 次投递仍未拿到 ACK（" + trigger + "），继续等对端恢复",
            lastSendSummary = outcome.lastSendSummary,
            attempts = next,
        )
        scheduleOfferRetry(queued, trigger = "backoff")
    }
    /** 一次报价投递的**只读**状态（task-62 的 `/v1/transfers` 出口）。 */
    data class OfferDelivery(
        val transferId: String,
        val peerId: String,
        val rootName: String,
        val totalSize: Long,
        /**
         * offering / offered / queued-awaiting-reconnect / gave-up / ack-timeout（同步路径）。
         *
         * `queued-awaiting-reconnect` 是 task-63 加的：报价失败不再丢，而是入队等对端恢复；
         * 重试预算用尽才记 `gave-up`（那时 transfer 会被摘掉）。
         */
        val state: String,
        val elapsedMs: Long,
        /** 已经尝试投递过几次（含首次）。 */
        val attempts: Int = 1,
        val detail: String? = null,
        /**
         * **这一次**投递的逐端点诊断摘要（task-62 关联补丁）。
         *
         * 与全局 `lastSend` 的区别：全局那个是「最近一次可靠投递」，会被随后的成功投递覆盖 ——
         * 真机上 z1/z2 失败、z3 成功，全局只看到 z3，于是失败那两次的现场被盖掉。
         */
        val lastSendSummary: String? = null,
        /**
         * **首次**尝试的逐端点诊断（task-65）。与 [lastSendSummary] **各留各的**，不合成一个。
         *
         * 读法：`firstAttemptSummary` 说「为什么第一次没成」，`lastSendSummary` 说「最后那次怎么样」；
         * 两次都一样时才是「一次就通」。合成一个字段的话，下次又有人把其中一个读成另一个。
         */
        val firstAttemptSummary: String? = null,
        /** 对端有没有真的来拉（登记表里的 accepted）。 */
        val accepted: Boolean = false,
    )

    private companion object {
        /**
         * 报价**已过 24 小时窗口**（`FILE_OFFER_TTL`）时，接收端不再发 accept 的原因码。
         *
         * 与其它失败原因一样是**机器码**：UI 层负责翻译成人话（见
         * `com.kapp.tidings.domain.fileFailureReason`），core 不该知道中文怎么写。
         */
        const val EXPIRED_REASON = "offer-expired"

        /** 只读状态里最多留多少次报价（环形，防长跑泄漏）。 */
        const val MAX_TRACKED_DELIVERIES = 64
        /** 报价重试退避的上限（逐次翻倍，到这里就不再拉长）。 */
        const val OFFER_RETRY_MAX_BACKOFF_MS = 60_000L
    }
}