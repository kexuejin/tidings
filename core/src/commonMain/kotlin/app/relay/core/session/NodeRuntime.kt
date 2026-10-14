// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.session

import app.relay.core.nowMillis
import app.relay.core.protocol.Codec
import app.relay.core.protocol.DEFAULT_TIMINGS
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.GroupMeta
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.Protocol
import app.relay.core.protocol.ScreenTcpFrame
import app.relay.core.protocol.Timings
import app.relay.core.protocol.makeEnvelope
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import app.relay.core.transport.DIRECTION_IN
import app.relay.core.transport.DedupStore
import app.relay.core.transport.Discovery
import app.relay.core.transport.InMemoryDedupStore
import app.relay.core.transport.InMemoryMessageStore
import app.relay.core.transport.InMemoryQueueStore
import app.relay.core.transport.ManualPeer
import app.relay.core.transport.MessageStatus
import app.relay.core.transport.MessageStore
import app.relay.core.transport.Messenger
import app.relay.core.transport.PeerEvent
import app.relay.core.transport.PeerRecord
import app.relay.core.transport.PeerRegistry
import app.relay.core.transport.PeerEndpoint
import app.relay.core.transport.LocalFile
import app.relay.core.transport.TransferEvent
import app.relay.core.transport.TransferManager
import app.relay.core.transport.OutgoingTransfer
import app.relay.core.transport.IncomingOffer
import app.relay.core.transport.PartFile
import app.relay.core.transport.QueueStore
import app.relay.core.transport.QueuedMessage
import app.relay.core.transport.SendOutcome
import app.relay.core.transport.StoredMessage
import app.relay.core.transport.TcpFramedChannel
import app.relay.core.transport.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一个 Tidings 节点的运行时：把 transport / registry / discovery / messenger / store
 * 装配成"可直接用"的对象。
 *
 * 这是 `composeApp`（手机 UI）与 `agentNode`（本机常驻 agent 侧节点）**唯一**的共用入口 ——
 * 两边都不直接 new [UdpTransport] / [Messenger]，因为装配顺序（先订阅再 start）与
 * 存储注入是需要统一保证的。
 *
 * ## 桌面与对端节点互联
 *
 * 本运行时兼容点对点协议标准：
 * - 主动方向：[announceTo] 单播 `entry`，对端 `discovery.scheduleAliveReply` 会回 `alive`；
 * - 被动方向：对端也可通过手动节点配置来连接我们。
 *
 * ## 存储
 *
 * 默认注入内存实现（见 `transport/Stores.kt` 的设计说明）。真正落盘（SQLDelight）是
 * 后续替换，接口不变，因此本类不需要知道实现。
 *
 * @param profile 本节点身份。手机端请用 [Profile.mobile]（platform 伪装 linux、caps 不含 upd1）。
 * @param udpPort UDP 监听端口；传 0 表示由系统分配（测试用），实际端口读 [boundPort]。
 * @param bindHost 绑定地址。默认全网卡；只想被本机访问时传 `127.0.0.1`。
 * @param enableTcp 是否启用 TCP 回退（>1200 字节的文本）。关闭后超长文本直接判定失败并入队。
 * @param tcpPort 文件传输与可靠投递的 TCP 监听端口。**同一台机器上跑第二个节点时必须改**
 *   —— 默认桌面端监听 `17879`，撞上它不是"收不到文件"而是"投递错人"（见 [tcpBound]）。
 *   绑定结果读 [tcpBound]，不要假设它会成功。
 */
class NodeRuntime(
    /**
     * 本节点身份。**可写**，因为 `start()` 会把其中的 `tcpPort` 改写为**真正绑上的**那个端口
     * （见 [tcpBound]）：对外声明一个别人正在用的端口，后果是消息**投递错人**。
     */
    var profile: Profile,
    udpPort: Int = Protocol.DEFAULT_UDP_PORT,
    bindHost: String = "0.0.0.0",
    private val timings: Timings = DEFAULT_TIMINGS,
    manualPeers: List<ManualPeer> = emptyList(),
    private val queueStore: QueueStore = InMemoryQueueStore(),
    private val dedupStore: DedupStore = InMemoryDedupStore(),
    private val messageStore: MessageStore = InMemoryMessageStore(),
    enableTcp: Boolean = true,
    private val tcpPort: Int = Protocol.DEFAULT_TCP_PORT,
    /**
     * 收到文件的落盘根目录。是函数不是值 —— 用户随时可能改设置。
     * 默认落到缓存目录之外的当前目录，调用方（composeApp / agentNode）应当传入真实目录。
     */
    private val downloadDir: () -> String = { "." },
    private val enableFileTransfer: Boolean = true,
    private val onOpenScreen: suspend (Socket, ScreenTcpFrame.Open, String, ByteReadChannel, ByteWriteChannel) -> Boolean =
        { _, _, _, _, _ -> false },
) {

    private val transport = UdpTransport(udpPort, bindHost)
    private val registry = PeerRegistry(profile.nodeId)
    private val tcp: TcpFramedChannel? = if (enableTcp) TcpFramedChannel() else null
    private val discovery = Discovery(
        transport,
        registry,
        { profile },
        timings,
        manualPeers,
        // `scan-ranges` 这类 gossip 帧不进可靠通道，只能由发现层递上来（见 `Discovery.onGossip`）。
        // 直接投 `_inbound`：它**不是**一条聊天记录，不该进 messageStore。
        onGossip = { _inbound.tryEmit(it) },
    )
    internal val messenger = Messenger(
        transport = transport,
        registry = registry,
        selfNodeId = profile.nodeId,
        queueStore = queueStore,
        dedupStore = dedupStore,
        timings = timings,
        tcpFallback = tcp,
    )
    private val outbound = NodeOutbound(
        messenger = messenger,
        messageStore = messageStore,
        selfId = { profile.nodeId },
        selfProfile = { profile },
        resolvePeer = this,
    )


    private val _inbound = MutableSharedFlow<Envelope>(extraBufferCapacity = 256)

    /** 已通过校验、已去重的入站消息（`msg` 帧）。UI 与 agentNode 都从这里取。 */
    val inbound: SharedFlow<Envelope> = _inbound

    /** 出站消息状态变化（`sent` / `queued`）。 */
    val status: SharedFlow<MessageStatus> = messenger.status

    /** 节点表变化（上线 / 资料更新）。 */
    val peers: SharedFlow<PeerEvent> = registry.events

    private val _transfers = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 128)

    /**
     * 文件传输事件（收到报价 / 进度 / 完成 / 失败 / 被拒）。
     *
     * 由 [TransferManager] 转发而来；本类自己持有这条流，所以节点重启后订阅者不必重新订阅。
     */
    val transfers: SharedFlow<TransferEvent> = _transfers

    private var transfer: TransferManager? = null
    private var transferPump: Job? = null

    private var scope: CoroutineScope? = null
    private var pump: Job? = null

    /** 实际绑定的 UDP 端口。`udpPort` 传 0 时在 [start] 之后才有效。 */
    val boundPort: Int get() = transport.boundPort

    /**
     * 实际绑定的 **TCP** 端口（文件传输与可靠投递），[start] 之前为 null。
     *
     * 与 [boundPort]（UDP）不是一回事，别混。声明端口被占时 [TransferManager] 会退到系统
     * 分配的端口，而 [profile] 里广播的就是**这里**的值 —— 这正是"广播的端点必须是我们
     * 真的绑上的那一个"能被断言的前提。
     */
    val tcpBoundPort: Int? get() = transfer?.boundPort

    /**
     * TCP 监听器是否真的绑上了 [tcpPort]。
     *
     * 这个属性存在的唯一理由是**那个失败曾经是静默的**：`start()` 原来写
     * `manager?.start()`，把 `TransferServer.start` 的返回值直接丢掉，于是
     * "我要的 TCP 端口没拿到"这件事没有任何出口。真机上撞到的后果不是降级而是
     * **投递错人**：同一台机器上其他客户端已经占着 `17879`，agentNode 却照样在自己的
     * 资料里声明 `tcpPort = 17879`，对端按 `(ip, tcpPort)` 连过来时连到的是**其他客户端**，
     * 一条发给 Agent 的消息会落进人类的聊天窗口（实测）。
     *
     * `false` 有两种成因，调用方要结合 `enableFileTransfer` 判断：
     * 关掉了文件传输（没有监听器），或者端口被占、绑定失败。
     *
     * 绑定失败时 [profile] 里的 `tcpPort` 会被**改写为真正绑上的那一个**
     * （[TransferManager] 在声明端口被占时会退到系统分配的端口），而不是继续对外声明
     * 一个别人正在用的端口。
     */
    var tcpBound: Boolean = false
        private set

    val nodeId: String get() = profile.nodeId

    /** 当前节点表快照（在线优先，其次昵称）。 */
    fun peerList(): List<PeerRecord> = registry.list()

    fun getPeer(nodeId: String): PeerRecord? = registry.get(nodeId)

    /**
     * 启动运行时。
     *
     * 顺序是刻意的：**先订阅、再开泵**。Discovery 与 Messenger 都要收 `transport.incoming`，
     * 而 SharedFlow 的 `extraBufferCapacity` 只是缓冲，真正的保证是订阅先于 bind ——
     * 否则开机瞬间的 `alive` 会掉。
     */
    suspend fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        discovery.start(scope)
        messenger.start(scope)

        // 文件传输管理器：与 TCP 监听器同生共死。
        // 必须在开泵之前创建 —— 泵里会把入站 file-ctl 直接交给它。
        val manager = if (enableFileTransfer) {
            TransferManager(
                selfId = profile.nodeId,
                scope = scope,
                tcpPort = tcpPort,
                downloadDir = downloadDir,
                sendReliable = { peerId, payload ->
                    messenger.sendUserMessage(
                        peerId,
                        makeEnvelope(MsgTypes.FILE_CTL, profile.nodeId, payload),
                    ) == SendOutcome.SENT
                },
                // 拉取走的是对端的 TCP 监听端口，不是 UDP 端口。
                // 这两个数字长得像但用途完全不同：UDP 端口收可靠帧，TCP 端口供流。
                // 拿 udpPort 去连会得到 connection refused，表现是「点了接收但对方没收到 pull」。
                peerAddress = { peerId ->
                    registry.get(peerId)?.let { PeerEndpoint(it.ip, it.profile.tcpPort) }
                },
                peerSupportsWait = { peerId ->
                    registry.get(peerId)?.profile?.caps?.contains(MsgTypes.CAP_MOBILE_TEXT) == true
                },
                // 对端声明 `fd1` 才发 `direct`（请求它免确认收下）。桌面端这个键跟着自己的
                // 「允许同事直接发送文件」开关走，所以关掉时我们一条都不发 —— 决定权在收端。
                peerSupportsDirect = { peerId ->
                    registry.get(peerId)?.profile?.caps?.contains(MsgTypes.CAP_FILE_DIRECT) == true
                },
                // TCP 回退的长文本（>800 字节）也走这条监听器，收下后与 UDP 路径共用投递纪律。
                receiveMessage = { envelope, remoteAddress ->
                    messenger.acceptTcpMessage(envelope, remoteAddress)
                },
                openScreen = { socket, open, remoteAddress, read, write ->
                    onOpenScreen(socket, open, remoteAddress, read, write)
                },
            )
        } else {
            null
        }
        transfer = manager
        transferPump = manager?.let { m ->
            scope.launch { m.events.collect { _transfers.tryEmit(it) } }
        }

        pump = scope.launch {
            messenger.incoming.collect { envelope ->
                // **不写 messageStore**：投屏信令（screen 的 request/accept/reject/end）、
                // 共享文件柜（share）以及全部 agent-* 扩展帧是会话与机器控制帧，不是普通聊天记录 ——
                // 与撤回帧同一纪律。写进去会让它出现在 /v1/inbox 的增量回放里，对消费方就是一批
                // 没人认领的噪声帧；投屏与 agent 会话机各由专门的存储维护，这里只读 _inbound。
                if (envelope.type != MsgTypes.SCREEN &&
                    envelope.type != MsgTypes.SHARE &&
                    !envelope.type.startsWith("agent-")
                ) {
                    messageStore.append(
                        peerId = envelope.from,
                        direction = DIRECTION_IN,
                        envelopeJson = Codec.encode(envelope),
                        at = nowMillis(),
                    )
                }
                if (envelope.type == MsgTypes.FILE_CTL) transfer?.onEnvelope(envelope)
                _inbound.tryEmit(envelope)
            }
        }
        // 绑 TCP 端口要放在订阅之后：端口一开，桌面端就可能连进来拉文件。
        // 返回值**必须**留下：见 [tcpBound]。丢掉它等于让"端口没拿到"永远没人知道，
        // 而资料里那个 tcpPort 照样会广播出去。
        tcpBound = manager?.start() ?: false
        if (tcpBound) {
            // 声明一个自己没拿到的端口，后果不是"收不到"而是**投递错人**：对端照着我们
            // 广播出去的 tcpPort 去连，连上的是占着那个端口的另一个程序。
            // 所以资料里那个值必须换成**真正绑上的**那一个。
            //
            // 这里**不能**写 `copy(tcpPort = 0)` 表示"我没有 TCP"：协议没有这种表达，
            // [Codec.validateProfile] 要求 `tcpPort` 在 `1..65535`，声明 0 会让对端直接拒收
            // 整份资料，症状是握手超时 `peer-unreachable` —— 比端口撞车更难查。
            // 绑定失败时 [TransferManager] 已经退到系统分配的端口，这里如实上报即可。
            val bound = manager?.boundPort
            if (bound != null && bound != profile.tcpPort) {
                profile = profile.copy(tcpPort = bound)
            }
        }
        transport.start()
    }

    /**
     * 停掉运行时并释放端口。
     *
     * 是 suspend 的：文件传输监听器必须先真正关掉，端口才算释放 ——
     * 否则 `restart()`（改资料/改端口后重建节点）会撞上自己上一次的 TCP 绑定。
     */
    suspend fun stop() {
        pump?.cancel()
        pump = null
        transferPump?.cancel()
        transferPump = null
        transfer?.stop()
        transfer = null
        tcpBound = false
        discovery.stop()
        messenger.stop()
        transport.stop()
        tcp?.close()
        scope = null
    }

    // ---------------------------------------------------------------- 文件传输

    /**
     * 发一批文件给对端。
     *
     * 只是发出报价（`file-ctl offer`），对端接受后才真正开始传；返回 transferId，
     * 报价发送失败返回 null。进度与结果从 [transfers] 取。
     *
     * [groupId]/[groupRev] 非空表示群聊媒体：收端据此把内联上限从 20MB 收到 10MB
     * （见 [app.relay.core.transport.TransferManager.offerFiles]）。
     *
     * [tableText] 是表格图片的文字视图（`tbl1`），超上限时由传输层截断并标记。
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
    ): String? = transfer?.offerFiles(peerId, files, purpose, msgId, groupId, groupRev, tableText, isFolder, folderRootName)

    /** 接受一个收到的文件报价，随即开始拉取。 */
    fun acceptTransfer(transferId: String) {
        transfer?.accept(transferId)
    }

    /** 续传失败的入站传输。 */
    fun resumeTransfer(transferId: String) {
        transfer?.resumeTransfer(transferId)
    }

    /** 拒绝一个收到的文件报价（也会用于撤回自己发出的报价）。 */
    fun declineTransfer(transferId: String) {
        transfer?.decline(transferId)
    }

    /** 撤回自己发出的、还没被接受的报价。 */
    suspend fun cancelTransfer(transferId: String) {
        transfer?.cancelOutgoing(transferId)
    }

    /**
     * 放弃一个**入站**传输（对端发给我们的那份）。
     *
     * 与 [cancelTransfer] 是相反方向：那个撤的是我们发出的报价，这个停的是我们正在拉的流。
     * 媒体撤回要用它 —— 协议要求收到撤回时「取消进行中的 TCP 拉取并清理 `.part`」，
     * 清理动作由 [TransferManager.cancelIncoming] 的 `discardParts` 一并完成。
     *
     * @return 有没有这么一个在途的入站传输（false = 已经结束了，撤回该被忽略）。
     */
    suspend fun cancelIncoming(transferId: String): Boolean =
        transfer?.cancelIncoming(transferId) ?: false

    suspend fun pendingOutgoingTransfers(): List<OutgoingTransfer> =
        transfer?.pendingOutgoing().orEmpty()

    suspend fun pendingTransferOffers(): List<IncomingOffer> = transfer?.pendingOffers().orEmpty()

    /** 用户明确放弃一个半成品下载时才删 `.part`；普通失败保留它以便续传。 */
    fun discardPartial(path: String) {
        transfer?.discardPart(path)
    }

    /**
     * 删一个 [leftoverParts] 交出来的残片 —— 参数是**已经带 `.part` 的全路径**。
     *
     * 与 [discardPartial] 差一层坐标系：那个收的是正式文件名。别把 [leftoverParts]
     * 的 `path` 直接喂给 [discardPartial]，后缀会被叠成 `.part.part`，结果什么都没删。
     */
    fun discardPartFile(partPath: String) {
        transfer?.discardPartFile(partPath)
    }

    fun partialSize(path: String): Long = transfer?.existingPartSize(path) ?: 0L

    /**
     * 磁盘上现在躺着哪些 `.part` 残片。
     *
     * 只读，不触发清扫 —— 开机时 [TransferManager.start] 已经扫过一遍（无在途传输认领的残片当场删掉），
     * 所以这里读到的正是「扫不掉或这轮新留下的」：运行中失败会保留残片以便续传，
     * 它们就是给用户一个「放弃续传、把空间要回来」出口的对象。
     */
    fun leftoverParts(): List<PartFile> = transfer?.leftoverParts().orEmpty()

    /** 主动握手：向广播地址或所有手动节点单播 `entry`。 */
    suspend fun announce() = discovery.announce()

    /** 只向一个地址握手 —— 手机端主路径（知道电脑 IP 即可建立双向在线）。 */
    suspend fun announceTo(host: String, port: Int = Protocol.DEFAULT_UDP_PORT) =
        discovery.announceTo(host, port)

    /**
     * 告别：广播 `exit`，让对端立刻把我们标成离线，而不是等 presence 超时。
     *
     * 只在**真的要离开**时调（退出进程 / 主动停节点），不要在 [stop] 里无条件发：
     * `restart()` 会先停后起，发了就是一次"离线→在线"的假抖动。
     */
    suspend fun announceExit() = discovery.announceExit()

    suspend fun sendText(peerId: String, text: String) = outbound.sendText(peerId, text)

    suspend fun sendTextWithEnvelope(peerId: String, text: String) = outbound.sendTextWithEnvelope(peerId, text)

    suspend fun sendRecall(peerId: String,
        targetId: String,
        groupId: String? = null,
        groupRev: Int? = null,) = outbound.sendRecall(peerId, targetId, groupId, groupRev)

    suspend fun sendGroupText(text: String,
        groupId: String,
        groupRev: Int,
        members: List<String>,
        mentions: List<String>? = null,
        replyTo: String? = null,) = outbound.sendGroupText(text, groupId, groupRev, members, mentions, replyTo)

    suspend fun sendPk(peerId: String,
        game: String,
        value: String,
        groupId: String? = null,
        groupRev: Int? = null,) = outbound.sendPk(peerId, game, value, groupId, groupRev)

    suspend fun sendGroupPk(game: String,
        value: String,
        groupId: String,
        groupRev: Int,
        members: List<String>,) = outbound.sendGroupPk(game, value, groupId, groupRev, members)

    suspend fun sendGroupInfo(peerId: String, meta: GroupMeta) = outbound.sendGroupInfo(peerId, meta)

    suspend fun sendGroupInfoReply(peerId: String, meta: GroupMeta) = outbound.sendGroupInfoReply(peerId, meta)

    suspend fun sendGroupNeed(peerId: String, groupId: String) = outbound.sendGroupNeed(peerId, groupId)

    suspend fun sendReliableNoQueue(peerId: String, envelope: Envelope) = outbound.sendReliableNoQueue(peerId, envelope)

    suspend fun sendNudge(peerId: String) = outbound.sendNudge(peerId)

    @Suppress("LongParameterList")
    suspend fun sendAgentSession(peerId: String,
        sessionId: String,
        alias: String? = null,
        agentKind: String? = null,
        state: String? = null,
        title: String? = null,
        startedAt: Long? = null,
        agentId: String? = null,
        wsId: String? = null,
        wsTitle: String? = null,
        wsPath: String? = null,
        updatedAt: Long? = null,
        origin: String? = null,
        blank: Boolean? = null,) = outbound.sendAgentSession(
        peerId, sessionId, alias, agentKind, state, title, startedAt,
        agentId, wsId, wsTitle, wsPath, updatedAt, origin, blank,
    )

    suspend fun sendAgentEvent(peerId: String,
        sessionId: String,
        cursor: Long,
        eventType: String,
        summary: String? = null,
        body: String? = null,
        ref: String? = null,) = outbound.sendAgentEvent(peerId, sessionId, cursor, eventType, summary, body, ref)

    suspend fun sendAgentQueue(peerId: String,
        taskId: String,
        sessionId: String? = null,
        op: String,
        prompt: String? = null,
        queuedAt: Long? = null,) = outbound.sendAgentQueue(peerId, taskId, sessionId, op, prompt, queuedAt)

    suspend fun sendAgentRun(peerId: String,
        sessionId: String,
        runId: String,
        state: String,
        at: Long? = null,
        note: String? = null,) = outbound.sendAgentRun(peerId, sessionId, runId, state, at, note)

    suspend fun sendAgentApproval(peerId: String,
        approvalId: String,
        sessionId: String,
        tool: String? = null,
        summary: String? = null,
        expiresAt: Long,
        choices: String? = null,) = outbound.sendAgentApproval(peerId, approvalId, sessionId, tool, summary, expiresAt, choices)

    suspend fun sendAgentCancel(peerId: String,
        sessionId: String,
        runId: String? = null,
        reason: String? = null,) = outbound.sendAgentCancel(peerId, sessionId, runId, reason)

    suspend fun sendAgentAck(peerId: String,
        ackOf: String,
        kind: String,
        at: Long? = null,) = outbound.sendAgentAck(peerId, ackOf, kind, at)

    suspend fun sendAgentArtifact(peerId: String,
        sessionId: String,
        artifactId: String,
        name: String,
        fileId: String? = null,
        bytes: Long? = null,
        mime: String? = null,) = outbound.sendAgentArtifact(peerId, sessionId, artifactId, name, fileId, bytes, mime)

    /** PPE-1 增补 D3：工作区宣告（可靠但不入队；返回值 = 对端有没有 ACK）。 */
    @Suppress("LongParameterList")
    suspend fun sendAgentWorkspace(peerId: String,
        wsId: String,
        wsTitle: String? = null,
        wsPath: String? = null,
        agentId: String? = null,
        agentKind: String? = null,
        online: Boolean? = null,
        sessionCount: Long? = null,
        at: Long? = null,
        order: Long? = null,): Boolean = outbound.sendAgentWorkspace(
        peerId, wsId, wsTitle, wsPath, agentId, agentKind, online, sessionCount, at, order,
    )

    /** PPE-1 增补 D8：向宿主要一页会话清单（设备 → 宿主）。 */
    suspend fun sendAgentSessionPage(peerId: String,
        wsId: String,
        offset: Long? = null,
        limit: Long? = null,
        at: Long? = null,): Boolean = outbound.sendAgentSessionPage(peerId, wsId, offset, limit, at)

    /** PPE-1 增补 D7：授权申请（设备 → 宿主）。 */
    suspend fun sendAgentGrantRequest(peerId: String,
        agentKey: String? = null,
        reason: String? = null,
        at: Long? = null,) = outbound.sendAgentGrantRequest(peerId, agentKey, reason, at)

    /** PPE-1 增补 D7：授权决定（宿主 → 设备）。 */
    suspend fun sendAgentGrant(peerId: String,
        state: String,
        scope: String,
        agentKey: String? = null,
        at: Long? = null,) = outbound.sendAgentGrant(peerId, state, scope, agentKey, at)

    /** PPE-1 增补 D7：授权清单条目（宿主 → 设备）。 */
    suspend fun sendAgentGrantList(peerId: String,
        scope: String,
        agentKey: String? = null,
        total: Long? = null,
        at: Long? = null,) = outbound.sendAgentGrantList(peerId, scope, agentKey, total, at)

    suspend fun sendAvatarGet(peerId: String, hash: String) = outbound.sendAvatarGet(peerId, hash)

    suspend fun sendAvatarData(peerId: String, hash: String, dataBase64: String) = outbound.sendAvatarData(peerId, hash, dataBase64)

    suspend fun sendAvatarMiss(peerId: String, hash: String) = outbound.sendAvatarMiss(peerId, hash)

    /**
     * 按地址找节点：先查节点表，没有再单播 `entry` 并轮询等待对方回 `alive`。
     *
     * 这是 agentNode 的 `POST /v1/send` 能把 `{"to":"192.168.1.5"}` 这种"电脑 IP"
     * 解析成 nodeId 的关键 —— 调用方（宿主 agent 插件 / Codex / Claude）不该知道 nodeId。
     *
     * @param port 给了就**必须**匹配上这个 UDP 口才算命中。同一台机器上可以同时跑多个
     *   节点（如桌面端 17878 与 agentNode 17891 常驻同一台主机），只按 IP 挑会把消息
     *   投给另一个节点 —— 而投错节点在协议层是静默的，对方不认这个 nodeId 就不回 ACK，
     *   调用方只看到一个没头没尾的失败。传 `null` 表示"调用方没这个信息"，退回只按 IP。
     * @return 3 秒内握手成功则返回记录，否则 null（调用方映射为 404/502）。
     */
    suspend fun resolvePeerByHost(
        host: String,
        port: Int? = null,
        timeoutMs: Long = 3_000L,
    ): PeerRecord? {
        fun pick(): PeerRecord? {
            // 同地址可能有多条记录（设备换了网卡、nodeId 换了、或者测试机里两个节点
            // 共用一个口）。先排掉已离线的：投给一条离线记录注定等不到 ACK，调用方只会
            // 看到"已入队等上线"，而真正在线的那条就永远收不到这条消息。
            val atHost = registry.values().filter { it.ip == host }
                .sortedByDescending { it.online }
            return if (port == null) atHost.firstOrNull() else atHost.firstOrNull { it.udpPort == port }
        }

        pick()?.let { return it }
        discovery.announceTo(host, port ?: Protocol.DEFAULT_UDP_PORT)
        return withTimeoutOrNull(timeoutMs) {
            var found: PeerRecord? = null
            while (found == null) {
                found = pick()
                if (found == null) delay(100L)
            }
            found
        }
    }

    fun peerDeclares(peerId: String, cap: String): Boolean =
        getPeer(peerId)?.profile?.caps?.contains(cap) == true

    /**
     * 把一条原信封重新入队（**手工补发 / 迁移老数据**时调用）。
     *
     * 正常路径不需要它：队列自己落盘（`core/persist/FileStores.kt` 的 `FileQueueStore`），
     * 应用重启时文件被读回来，这条消息已经在队列里了。留这个入口是为了
     * ①测试能钉住"重放的是同一条信封"；②将来若要支持从别处导入历史消息。
     *
     * @param createdAt 最初入队的时刻，用它保住重放顺序与 TTL 起点。
     * @return 信封能否解码；`false` 表示这份数据已经没救，上层应改成 failed。
     */
    suspend fun requeueOutgoing(peerId: String, envelopeJson: String, createdAt: Long): Boolean =
        messenger.requeue(peerId, envelopeJson, createdAt)

    /**
     * 给一个 **IP 地址**发文本：先握手解析出 nodeId，再发。
     *
     * @throws IllegalStateException 3 秒内没能与目标建立握手。
     */
    suspend fun sendTextToHost(
        host: String,
        text: String,
        port: Int? = null,
    ): SendOutcome {
        val record = resolvePeerByHost(host, port)
            ?: error("无法在 3 秒内与 $host 建立握手（对方设备是否已启动？）")
        return sendText(record.profile.nodeId, text)
    }

    /** 跨会话增量拉取，供 agentNode 的 `GET /v1/inbox?since=` 使用。 */
    fun inbox(sinceAt: Long, limit: Int = 200): List<StoredMessage> =
        messageStore.sinceAll(sinceAt, limit)

    /** 单会话增量拉取（UI 用）。 */
    fun history(peerId: String, sinceSeq: Long, limit: Int = 200): List<StoredMessage> =
        messageStore.since(peerId, sinceSeq, limit)

    /** 剪枝离线队列与去重表；返回被剪掉的条目（上层据此把消息标为 failed）。 */
    fun prune(now: Long = nowMillis()) = messenger.prune(now)

    fun pendingCount(): Int = messenger.pendingCount()

    /** 分对端的待发条数，供会话列表显示"离线暂存"提示。 */
    fun pendingByPeer(): Map<String, Int> = messenger.pendingByPeer()

    /**
     * 全部待发项（含信封原文），供启动时重建 UI 的「已排队」展示态。
     *
     * 队列是 core 的，它知道哪条还在等；上层只负责把它显示成什么样子。
     */
    fun pendingOutgoingMessages(): List<QueuedMessage> = messenger.pendingAll()
}

/**
 * [NodeRuntime.sendTextWithEnvelope] 的返回值：投递结果 + 这条信封的身份。
 *
 * [envelopeId] 就是 core 状态事件 `MessageStatus.msgId` 用的那个 id，上层要拿它当消息主键
 * 才能把后来的 "sent" 对上这条消息。
 *
 * [envelopeJson] **不是给上层做持久化用的**（队列自己落盘，见 `core/persist/PersistStores`）；
 * 它在这里是因为 core 自己要用同一份 JSON 写 agentNode 的 inbox 日志，
 * 而那个写入发生在 [NodeOutbound.sendTextWithEnvelope] 内部。
 */
data class OutgoingText(
    val outcome: SendOutcome,
    val envelopeId: String,
    val envelopeJson: String,
)
