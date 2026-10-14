// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.fs.makeDirectories
import app.relay.core.fs.pathJoin
import app.relay.core.fs.sanitizeRelativePath
import app.relay.core.protocol.Codec
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.FileCtlOffer
import app.relay.core.protocol.FileCtlOps
import app.relay.core.protocol.FileCtlSimple
import app.relay.core.protocol.FileMeta
import app.relay.core.protocol.FilePurpose
import app.relay.core.protocol.FileTransfer
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.ScreenTcpFrame
import app.relay.core.nowMillis
import app.relay.core.protocol.newId
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
class TransferManager(
    private val selfId: String,
    private val scope: CoroutineScope,
    private val tcpPort: Int,
    /** 收到文件的落盘根目录。每次调用都可能不同（用户改设置），所以是函数不是值。 */
    private val downloadDir: () -> String,
    /** 发一条可靠 UDP 帧（`file-ctl`）。返回 false 表示发送失败。 */
    private val sendReliable: suspend (peerId: String, payload: JsonElement) -> Boolean,
    /** 查对端地址；查不到返回 null。 */
    private val peerAddress: (peerId: String) -> PeerEndpoint?,
    /** 对端是否声明了 `tw1`。只有声明者才允许收 `wait` 帧。 */
    private val peerSupportsWait: (peerId: String) -> Boolean = { false },
    /**
     * 对端是否声明了 `fd1`（[app.relay.core.protocol.MsgTypes.CAP_FILE_DIRECT]）。
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
    bindAddress: String? = null,
    limits: TransferServerLimits = TransferServerLimits(),
) {

    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    private val stateLock = Mutex()
    private val outgoing = mutableMapOf<String, OutgoingTransfer>()
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

    private val server = TransferServer(
        scope = scope,
        lookup = object : OutgoingLookup {
            override fun resolve(transferId: String, fileId: String): OutgoingFile? {
                val transfer = outgoing[transferId] ?: return null
                if (!transfer.accepted) return null
                return transfer.files.firstOrNull { it.fileId == fileId }
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
        if (!server.start(tcpPort) && (tcpPort == 0 || !server.start(0))) {
            started = false
            return false
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
        return server.start(tcpPort)
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
        if (files.isEmpty() || files.size > FileTransfer.MAX_FILES_PER_TRANSFER) return null
        if (files.any { it.size < 0 }) return null

        val transferId = newId()
        val outgoingFiles = files.map { OutgoingFile(fileId = newId(), absPath = it.path, size = it.size) }
        val metas = files.zip(outgoingFiles) { local, out -> FileMeta(fileId = out.fileId, path = local.name, size = local.size) }
        val totalSize = files.sumOf { it.size }
        val rootName = folderRootName ?: if (isFolder && files.size > 1) "${files.first().name} 等 ${files.size} 个文件" else files.first().name
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

        val chunks = metas.chunked(FileTransfer.OFFER_FILES_PER_PACKET)
        for ((index, chunk) in chunks.withIndex()) {
            val offer = FileCtlOffer(
                op = FileCtlOps.OFFER,
                transferId = transferId,
                msgId = msgId,
                seq = index + 1,
                total = chunks.size,
                files = chunk,
                totalSize = totalSize,
                fileCount = metas.size,
                rootName = rootName,
                purpose = purpose,
                expiresAt = if (isMedia) null else nowMillis() + FileTransfer.FILE_OFFER_TTL,
                tableText = wireTableText,
                tableTextTruncated = tableTruncated,
                groupId = groupId,
                groupRev = groupRev,
                isFolder = if (isFolder) true else null,
            )
            val payload = Codec.wireJson.encodeToJsonElement(FileCtlOffer.serializer(), offer)
            if (!sendReliable(peerId, payload)) {
                stateLock.withLock { outgoing.remove(transferId) }
                return null
            }
        }
        // 清单发完（每条都等到 ACK）之后才请求免确认：晚一步的原因见 requestDirect 的注释。
        if (peerSupportsDirect(peerId)) {
            sendDirect(peerId, transferId)
            scheduleDirectRetry(peerId, transferId)
        }
        return transferId
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

    /** 同意接收：先回 `accept`，再开 TCP 去拉。 */
    fun accept(transferId: String) {
        scope.launch {
            val offer = stateLock.withLock {
                val off = offers.remove(transferId) ?: acceptedOffers[transferId]
                if (off != null) acceptedOffers[transferId] = off
                off
            } ?: return@launch
            sendSimple(offer.peerId, FileCtlOps.ACCEPT, transferId)
            pull(offer)
        }
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
                pull(assembled)
            }
        }
    }

    private fun onAccept(payload: JsonObject) {
        val simple = decodeSimple(payload) ?: return
        scope.launch {
            stateLock.withLock {
                outgoing[simple.transferId]?.accepted = true
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

        try {
            pullTransfer(
                PullOptions(
                    host = endpoint.host,
                    port = endpoint.port,
                    selfId = selfId,
                    transferId = offer.transferId,
                    files = offer.files,
                    saveDir = saveDir,
                    cancelRef = cancelRef,
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
}

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
