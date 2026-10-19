// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.session

import app.relay.core.ioDispatcher
import app.relay.core.nowMillis
import app.relay.core.protocol.AgentEventAttachment
import app.relay.core.protocol.Codec
import app.relay.core.protocol.DEFAULT_TIMINGS
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.IpAddressUtils
import app.relay.core.protocol.GroupMeta
import app.relay.core.protocol.MsgTypes
import kotlin.concurrent.Volatile
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
import app.relay.core.transport.SYNTHETIC_NICK_PREFIX
import app.relay.core.transport.InMemoryDedupStore
import app.relay.core.transport.InMemoryMessageStore
import app.relay.core.transport.InMemoryQueueStore
import app.relay.core.transport.ManualPeer
import app.relay.core.transport.MessageStatus
import app.relay.core.transport.MessageStore
import app.relay.core.transport.Messenger
import app.relay.core.transport.NetworkWatch
import app.relay.core.transport.PrunedMessage
import app.relay.core.transport.startPlatformNetworkWatch
import app.relay.core.transport.PeerEvent
import app.relay.core.transport.PeerRecord
import app.relay.core.transport.PeerRegistry
import app.relay.core.transport.PeerEndpoint
import app.relay.core.transport.KnownPeerStore
import app.relay.core.transport.PeerEndpointStore
import app.relay.core.transport.InMemoryPeerEndpointStore
import app.relay.core.transport.InMemoryPeerPinStore
import app.relay.core.transport.LinkClient
import app.relay.core.transport.LinkServer
import app.relay.core.transport.LinkSnapshot
import app.relay.core.transport.LinkTransport
import app.relay.core.transport.PeerPinStore
import app.relay.core.transport.PlaintextLinkTransport
import app.relay.core.crypto.TlsPin
import app.relay.core.transport.CrossNetworkFileListener
import app.relay.core.transport.CrossNetworkRawDialer
import app.relay.core.transport.CrossNetworkTransferPlanner
import app.relay.core.transport.CrossNetworkTransferPolicy
import app.relay.core.transport.createCrossNetworkFileListener
import app.relay.core.transport.createCrossNetworkRawDialer
import app.relay.core.transport.ReliableSendDiagnostics
import app.relay.core.transport.createPlatformLinkTransport
import app.relay.core.transport.crossNetworkFilePort
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
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
    /**
     * 每个节点学到的候选端点（配对码里那一池地址）。默认内存实现，宿主应当传落盘的那份。
     * 换网之后要靠它们把心跳/消息送到对端的另一个地址，见 [setPeerEndpoints]。
     */
    private val peerEndpointStore: PeerEndpointStore = InMemoryPeerEndpointStore(),
    /** 每个节点记住的 TLS pin（配对码里的 tlsPin），公网长连接的信任根。 */
    private val peerPinStore: PeerPinStore = InMemoryPeerPinStore(),
    /**
     * 「已知对端」的落盘（task-80 ①）。默认 null = 不持久化（测试与预览的行为与以前一致）。
     *
     * 宿主（`RelayController`）会传 `FileKnownPeerStore`：**重启后身份还在**，
     * 只是以离线态出现。没有它，手机 kill 一次之后节点表里那台 Mac 就只剩
     * `node-a1b2c3d4` 这个合成名（真机缺陷）。
     */
    private val knownPeerStore: KnownPeerStore? = null,
    /**
     * 长连接的连接层（K2 的接缝）。K1 默认明文 [PlaintextLinkTransport]；K2 换成
     * TLS + 双向 pin 的实现即可，本类一行不用改。
     */
    /** 本机自签身份的落盘目录（K2 的证书/私钥）。 */
    linkIdentityDir: () -> String = { "." },
    /**
     * 本机当前全部地址。两个用途：
     *  · 进证书 SAN（对端按地址连过来时证书必须匹配）；
     *  · task-40 的地址复查判"对端是不是同一段局域网"（家庭 LAN 的 IPv6 常是 GUA）。
     */
    private val localAddresses: () -> List<String> = { emptyList() },
    /** 连接层（K2 的接缝）。默认取平台实现：JVM/Android = TLS，iOS = 明文（公网被策略拒绝）。 */
    private val linkTransport: LinkTransport = createPlatformLinkTransport(linkIdentityDir, localAddresses),
    /**
     * F1（task-39）：跨网文件的**原始字节流**拨号与监听 —— 与 [linkTransport] 同一套
     * 自签身份与 SPKI pin（身份按 [linkIdentityDir] 落盘，所以是同一把私钥/同一张证书）。
     *
     * 为什么不用 [linkTransport] 自己那两条：[LinkConnection] 只有分帧的 send/receiveFrame，
     * 而文件的数据阶段是裸流（见 docs/cross-network-file-screen-design.md 的 §0.1）。
     */
    private val crossNetworkRawDialer: CrossNetworkRawDialer =
        createCrossNetworkRawDialer(linkIdentityDir, localAddresses),
    private val crossNetworkFileListener: CrossNetworkFileListener =
        createCrossNetworkFileListener(linkIdentityDir, localAddresses),
    private val onOpenScreen: suspend (Socket, ScreenTcpFrame.Open, String, ByteReadChannel, ByteWriteChannel) -> Boolean =
        { _, _, _, _, _ -> false },
) {

    private val transport = UdpTransport(udpPort, bindHost)
    private val registry = PeerRegistry(profile.nodeId)
    private val tcp: TcpFramedChannel? = if (enableTcp) TcpFramedChannel() else null

    /**
     * 长连接开关。
     *
     * ## 它**不再是一个用户设置**（见 RelaySettings.linkDownlink 的 KDoc）
     *
     * 曾经它是设置页里的「跨网通道」开关、默认关。那个设计的两个前提后来都塌了：
     *  1. 关掉并不"回滚到可用状态"，而是**不拨号** —— 只剩等对端主动连入，而移动网 / NAT
     *     下基本等不到，表现为"怎么都连不上"；
     *  2. 它当初存在的理由是"公网明文出网"这个风险，而那条早已由 K2（TLS + 双向 pin，
     *     公网没 pin 就不拨，fail-closed）守住 —— 安全边界在 [LinkClient.linkTargets]，
     *     与这个开关无关。
     *
     * 所以现在是**恒为 true**；保留这个属性是因为回滚/测试仍需要一个显式的关断点
     * （[LinkClient] 的 `enabled()` 是这个属性的读点）。
     */
    @Volatile
    var linkEnabled: Boolean = true

    internal var linkServer: LinkServer? = null
        private set
    internal var linkClient: LinkClient? = null
        private set

    /**
     * K3 ①：平台网络监听的取消函数 + 本运行时在 [NetworkWatch] 上的订阅取消函数。
     *
     * 两个都必须在 [stop] 里收掉：restart() 会反复 start/stop，不退订就会越订越多 ——
     * 每个已经死掉的运行时还会被叫一次，而它的 scope 已经没了。
     */
    private var networkWatchCancel: (() -> Unit)? = null
    private var networkListenerCancel: (() -> Unit)? = null

    /**
     * 广播用的自身档案（task-38）：在设置里的档案上补**本机 TLS pin**。
     *
     * 为什么在这里补而不是进 `RelaySettings`：pin 来自**传输层**（身份生成之后才有），
     * 设置层看不到它。取不到（明文实现/无身份）就保留原值（null）—— 对端于是走 TOFU，
     * 并且**如实记为降级路径**。
     */
    private val selfProfile: Profile
        get() = profile.copy(tlsPin = linkTransport.localPin?.wire ?: profile.tlsPin)

    private val discovery = Discovery(
        transport,
        registry,
        // task-38：把自己的 TLS pin 随资料带出去（旧端忽略未知键 ⇒ 兼容）。
        // 用 selfProfile 而不是 profile：pin 来自传输层（身份生成之后才有），不是设置里存的。
        { selfProfile },
        timings,
        manualPeers,
        // `scan-ranges` 这类 gossip 帧不进可靠通道，只能由发现层递上来（见 `Discovery.onGossip`）。
        // 直接投 `_inbound`：它**不是**一条聊天记录，不该进 messageStore。
        onGossip = { _inbound.tryEmit(it) },
        // task-38：对端资料里带 tlsPin ⇒ **配对时**就把它的 pin 固定下来（来源标 pairing），
        // 不再依赖 TOFU 的首次固定 —— 那个有"攻击者抢先声明 nodeId"的首次使用竞态。
        onPeerPin = { nodeId, pin -> setPeerPin(nodeId, pin, PeerRegistry.PIN_SOURCE_PAIRING) },
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

    /**
     * 运行时的**自有 scope**：心跳、重试、收包、长连接都跑在这里，而不是调用方传进来的那个。
     *
     * 为什么必须自有（task-29 真机根因）：调用方（Compose / Activity）传进来的 scope 会在
     * 界面销毁或重组时被取消，而 `runtime` 对象还在 —— `RelayController.start()` 见到
     * `runtime != null` 就直接 return，于是**所有泵静默死掉、再也没有人重启它们**。
     * 真机表现：进程活着、保活前台服务在跑、logcat 无异常、`/proc/net/tcp6` 里一条连接都没有、
     * 对端 `lastSeen` 永远停在某一刻。运行时的生命周期只该由 [start]/[stop] 管。
     */
    private var ownScope: CoroutineScope? = null
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
     * 长连接的**只读**快照（诊断用，task-26 ③）：对端 nodeId → 承载类型（`"tls"` / `"plaintext"`），
     * 外加本端当前长连接总数。
     *
     * 为什么需要它：真机上"下行到底走的哪条路"此前只能靠临时挡网络去猜（K2 的 17880 在跨网
     * 窗口里一直没看到 ESTABLISHED，无法区分"没连上"还是"连上了但没被用"）。这是纯读操作，
     * 不触发任何动作，也不进协议。
     */
    fun linkSnapshot(): LinkSnapshot {
        val carriers = LinkedHashMap<String, String>()
        // 先本端发起的，再本端接受的 —— 同一对端两边都有时，以**接受侧**（承载下行的那条）为准。
        linkClient?.linkCarriers()?.let { carriers.putAll(it) }
        linkServer?.linkCarriers()?.let { carriers.putAll(it) }
        return LinkSnapshot(
            carriers = carriers,
            total = carriers.size,
            // 拨号诊断只在**发起侧**（客户端）有意义：它回答"为什么没拨/拨了为什么连不上"。
            dial = linkClient?.dialStats() ?: emptyMap(),
            // 拒绝原因只在**接受侧**（服务端）有意义：它回答"对端连进来了，我为什么拒"。
            denials = linkServer?.denyReasons() ?: emptyMap(),
            // 连接结束原因（task-40 ①）：两侧都可能关，接受侧优先（它才是承载下行的那条）。
            closes = (linkClient?.closeReasons() ?: emptyMap()) + (linkServer?.closeReasons() ?: emptyMap()),
            closeCounts = (linkClient?.closeCounts() ?: emptyMap()) + (linkServer?.closeCounts() ?: emptyMap()),
            closeDetails = (linkClient?.closeDetails() ?: emptyMap()) + (linkServer?.closeDetails() ?: emptyMap()),
            // pin 来源（task-38）：registry 是唯一的存放处（两侧都从它读）。
            pinSources = registry.allPinSources(),
        )
    }

    /**
     * 已知对端的**去抖落盘**（task-80 ①）。
     *
     * 为什么必须去抖：`registry` 的 `Updated` 事件在**每一帧**入站时都会发（心跳、presence、
     * 消息都算）。直接写盘等于每帧一次 IO，而这份数据是"想起来它是谁"，晚一两秒完全没关系。
     *
     * 做法是最朴素的合并：置一个脏标记，由一个后台协程每 [KNOWN_PEER_FLUSH_MS] 落一次；
     * 没有变化就什么都不做。不用 `debounce` 这类算子是为了让"最后一次变更一定会被写下去"
     * 这件事在代码里**能一眼看出来**（尾部定时器而不是流算子）。
     */
    private fun startKnownPeerPersistence(runScope: CoroutineScope) {
        val store = knownPeerStore ?: return
        val dirty = java.util.concurrent.atomic.AtomicBoolean(false)
        registry.addListener { event ->
            // Online 也要记：它意味着"这台设备确实存在且可达过"，比任何静态资料都硬。
            if (event is PeerEvent.Updated || event is PeerEvent.Online) dirty.set(true)
        }
        runScope.launch {
            while (isActive) {
                delay(KNOWN_PEER_FLUSH_MS)
                if (!dirty.compareAndSet(true, false)) continue
                runCatching {
                    store.save(
                        registry.values().map { record ->
                            app.relay.core.persist.KnownPeer(
                                profile = record.profile,
                                ip = record.ip,
                                udpPort = record.udpPort,
                                lastSeen = record.lastSeen,
                            )
                        },
                    )
                }
            }
        }
    }

    private companion object {
        /**
         * 已知对端的落盘间隔。1.5 秒：
         *  - 短到 kill 之前几乎一定已经落过（用户点"强制停止"前通常有这点时间）；
         *  - 长到不会把心跳帧变成磁盘写入（否则一个在线群体每秒几十次 IO）。
         */
        const val KNOWN_PEER_FLUSH_MS: Long = 1_500L
    }
    /**
     * 记下某个节点的**候选端点**（配对码里那一池地址）并落盘。
     *
     * 为什么要有这一条：registry 里每个 peer 只有一个"当前地址"（最后一次被采纳的那个），
     * 而配对码给的是一池地址。手机从局域网切到移动网时，当前地址可能已经不可达，
     * 但对端的公网地址明明通 —— 把池子存下来，心跳与发送才有兜底（见 PeerEndpointStore 的 KDoc）。
     *
     * 按 nodeId 覆盖写：重新配对同一个 nodeId 就是"刷新它那一池地址"，不是新增一条身份。
     */
    fun setPeerEndpoints(nodeId: String, endpoints: List<PeerEndpoint>) {
        if (nodeId.isBlank() || endpoints.isEmpty()) return
        registry.setCandidates(nodeId, endpoints)
        runCatching { peerEndpointStore.save(registry.allCandidates()) }
    }

    /** 某个节点存下来的候选端点（诊断/测试用）。 */
    fun peerEndpointsOf(nodeId: String): List<PeerEndpoint> = registry.candidatesOf(nodeId)

    /**
     * 记下对端的 TLS pin（配对码里那一枚）并落盘。
     *
     * K2 的信任根：手机侧的公网长连接**有 pin 才建**（fail-closed）。校验一律走
     * `TlsPin.parse`，这里不自己解析。
     */
    fun setPeerPin(nodeId: String, pin: String?, source: String = PeerRegistry.PIN_SOURCE_PAIRING) {
        if (nodeId.isBlank()) return
        registry.setPin(nodeId, pin, source)
        runCatching {
            peerPinStore.save(registry.allPins())
            peerPinStore.saveSources(registry.allPinSources())
        }
    }

    /** 对端的 TLS pin（诊断/测试用）。 */
    fun pinOfPeer(nodeId: String): String? = registry.pinOf(nodeId)

    /**
     * 本机的 TLS pin（SPKI 指纹）。
     *
     * 为什么要有这一条（task-86 真机）：二维码是**主机→手机单向**的信任传递通道，
     * 主机必须把自己的 pin 印进码里；否则手机 `pinOf(peerId)` 为 null，
     * 而公网长连接的拨号目标要求 `pin != null`（fail-closed）⇒ 手机只剩局域网明文目标，
     * 在蜂窝上一直 `connect-timeout`。
     *
     * 取不到（明文实现/身份还没生成）时返回 null —— 那时二维码里就没有 pin，
     * 手机退回 TOFU/局域网路径（**如实记为降级**，不假装有 pin）。
     */
    fun localTlsPin(): String? = linkTransport.localPin?.wire

    /**
     * 记住一个**扫码配对**来的对端，并给它一条可拨的最小档案。
     *
     * 为什么必须有这一条（task-86 真机）：二维码是**主机→手机单向**的，手机扫完只知道
     * 对端的 nodeId / 地址 / pin；而长连接的拨号目标要求「有 profile 且声明了 `lnk1`」
     * （`LinkClient.linkTargets` 第一句就 `return emptyList()`）。于是手机 `peers` 为空、
     * 一个目标都拨不出去；主机那边又因为手机没宣告 `lnk1` 不拨 ⇒ **双方都不拨**，
     * 界面永远显示「没有回应」（真机现象：`linkEnabled=true` 但 `linkTotal=0`）。
     *
     * 这里按**端口布局约定**补出最小档案：`tcpPort = udpPort + 1`、`tlsPort = udpPort + 2`
     * （与 [Protocol.DEFAULT_TCP_PORT] 的布局一致）。caps 只声明 `lnk1` —— 对端真实能力
     * 等它自己的 profile 到了，会被 [PeerRegistry.touch] 按 profileRev 顶替。
     */
    fun rememberPairedPeer(
        nodeId: String,
        nick: String?,
        host: String,
        udpPort: Int,
        tlsPin: String?,
    ) {
        if (nodeId.isBlank() || host.isBlank() || udpPort !in 1..65535) return
        val profile = Profile.mobile(
            nodeId = nodeId,
            // 占位名（真名还没拿到时）。前缀与识别判据共用 `SYNTHETIC_NICK_PREFIX` ——
            // 一旦对端送来真实 profile，`PeerRegistry.touch` 会把它顶替掉（`isSyntheticNick`）。
            nick = nick?.takeIf { it.isNotBlank() } ?: (SYNTHETIC_NICK_PREFIX + nodeId.take(8)),
            host = host,
            tcpPort = (udpPort + 1).coerceAtMost(65535),
            tlsPort = (udpPort + 2).coerceAtMost(65535),
            tlsPin = tlsPin,
            ver = "",
            caps = listOf(MsgTypes.CAP_LINK),
        )
        // confirmed = true：定向配对（扫码），允许在 profileRev 相同时接受（与 entry 同语义）。
        registry.touch(nodeId, host, udpPort, profile, confirmed = true)
    }

    /**
     * 启动运行时。
     *
     * 顺序是刻意的：**先订阅、再开泵**。Discovery 与 Messenger 都要收 `transport.incoming`，
     * 而 SharedFlow 的 `extraBufferCapacity` 只是缓冲，真正的保证是订阅先于 bind ——
     * 否则开机瞬间的 `alive` 会掉。
     */
    suspend fun start(callerScope: CoroutineScope) {
        if (this.scope != null) return
        // 自有的运行 scope（见 [ownScope]）：`callerScope` 只保留在签名里做兼容，
        // 不再决定运行时的生死 —— 它一被取消，整套泵就永久静默。
        val runScope = ownScope ?: CoroutineScope(SupervisorJob() + ioDispatcher).also { ownScope = it }
        scope = runScope
        // 上一次学到的候选端点先装回 registry —— 换网之后要能立刻用公网地址发心跳，
        // 不能等下一次配对（那可能永远不来）。
        for ((nodeId, endpoints) in peerEndpointStore.load()) {
            registry.setCandidates(nodeId, endpoints)
        }
        // **已知对端**先装回来，一律以**离线**态（task-80 ①）。
        //
        // 为什么必须在 discovery.start 之前：启动瞬间 registry 是空的，节点表会先画成一排
        // `node-xxxx`（合成名来自已落盘的消息历史），等重新发现到对端才恢复真名 ——
        // 那一段"名字消失"用户看得很清楚。先喂进去，界面第一帧就是对的。
        //
        // 为什么是离线：蜂窝下它确实不可达。恢复成在线会让消息进一条通不了的队列，
        // 用户看到的"已发送"是假的；"没有回应"才是当下的事实。
        knownPeerStore?.load()?.forEach { known ->
            registry.restoreOffline(known.profile, known.ip, known.udpPort, known.lastSeen)
        }
        startKnownPeerPersistence(runScope)
        // 同理，上一次配对学到的 pin 也先装回来：公网长连接的信任根跨重启必须还在。
        // 来源一起装回来 —— 否则重启后 linkPinSource 就再也看不出"这枚其实是 TOFU 学来的"。
        val pinSources = peerPinStore.loadSources()
        for ((nodeId, pin) in peerPinStore.load()) {
            registry.setPin(nodeId, pin, pinSources[nodeId] ?: PeerRegistry.PIN_SOURCE_UNKNOWN)
        }
        discovery.start(runScope)
        messenger.start(runScope)

        // 长连接（K1）：主机侧接受、发起侧按开关连出去；同一个运行时两个角色都在。
        val links = LinkServer(
            registry = registry,
            // 17879 的交接路径永远是明文；TLS 的连接由 startServer 直接交给 handle。
            acceptTransport = PlaintextLinkTransport(),
            scope = runScope,
            // 连接上收到的文本走与 TCP 回退同一条投递纪律（去重 + 投递）。
            receiveMessage = { envelope, remoteAddress ->
                messenger.acceptTcpMessage(envelope, remoteAddress)
            },
            // 首次固定（TOFU）学到手机的 pin：落盘，重启后仍然认得它（task-36）。
            // **来源标成 tofu**：这是降级路径（有首次使用竞态），必须可诊断（task-38 底线③）。
            onPinLearned = { nodeId, pin -> setPeerPin(nodeId, pin, PeerRegistry.PIN_SOURCE_TOFU) },
            // task-40：地址复查要判"对端是不是同一段局域网"（家庭 LAN 的 IPv6 常是 GUA）。
            localAddresses = localAddresses,
        )
        linkServer = links
        // 主机下行优先走长连接（连着就用它），失败立即退回 UDP/TCP —— 见 Messenger 的 KDoc。
        messenger.linkDownlink = links
        linkClient = LinkClient(
            registry = registry,
            transport = linkTransport,
            scope = runScope,
            selfNodeId = { profile.nodeId },
            enabled = { linkEnabled },
            onMessage = { envelope, remoteAddress ->
                messenger.acceptTcpMessage(envelope, remoteAddress)
            },
            // K3 ③：Doze/省电窗口里心跳降频（idleNow 由平台层装进 NetworkWatch；
            // 没人装就是 false ⇒ 行为与以前一字不差）。
            idleNow = { NetworkWatch.isIdleNow() },
            // task-40：地址复查要判"对端是不是同一段局域网"（家庭 LAN 的 IPv6 常是 GUA）。
            localAddresses = localAddresses,
        ).also { it.start() }
        // task-64：链路半死（冷却期）时**重拨一条新长连接**（17880 实测可达），而不是回退 17879
        // （蜂窝侧实测两个族都不通，最可能是路由器上针对 17879 的规则把流量引到了别的设备）。
        // 边界：只对**公网对端**这么做 —— 局域网里 17879 是通的，那里保留原回退；
        // 也**不硬编码禁用** 17879：用户清掉路由器规则后，它应当自然回到可用。
        messenger.linkRedial = { peerId -> linkClient?.redialNow(peerId) ?: false }
        messenger.peerIsPublic = { peerId ->
            val ip = registry.get(peerId)?.ip
            ip != null && !IpAddressUtils.isLanAddress(ip, localAddresses())
        }
        // K3 ①：网络一变**立刻**重拨（不等退避）并排空一次队列。
        // 平台监听在这里装：iOS 走 actual 的 NWPathMonitor；Android 的真实信号由
        // composeApp 平台层那条既有的 registerDefaultNetworkCallback 转发进 NetworkWatch
        // （它手里才有 Context，本模块的 jvmShared 源集不能引用 android.*）。
        networkWatchCancel = startPlatformNetworkWatch()
        // task-63 追加：**长连接从不可用恢复为可用时也 nudge**（不只在换网时）。
        // 蜂窝下链路半死/重建常常**没有换网信号**（接口与 IP 都没变，只是那条 TCP 死了），
        // 靠退避最慢要等一分钟；这里用一个 2s 的小轮询看「有没有对端刚从断开变成连着」。
        // 成本是一次集合拷贝（几台设备），收益是恢复口径跟着 K3 的 ≤10s 走。
        runScope.launch {
            var previousLinks = emptySet<String>()
            while (isActive) {
                delay(2_000L)
                val current = runCatching { linkClient?.connectedPeers() ?: emptySet() }.getOrDefault(emptySet())
                if ((current - previousLinks).isNotEmpty()) {
                    runCatching { transfer?.retryQueuedOffersNow() }
                }
                previousLinks = current
            }
        }
        networkListenerCancel = NetworkWatch.onChanged {
            requestLinkReconnectNow()
            // task-63：换网**立刻**重试排队中的报价 —— 退避上限 60s 对不上 K3「换网 ≤10s 恢复」
            // 的口径，而这里只是把到点的重试提前触发一轮（幂等键保证不会投出两份）。
            runScope.launch { transfer?.retryQueuedOffersNow() }
        }
        // K2：平台能作为 TLS 服务端时，在 profile 声明的 tlsPort 上监听。
        // 独立端口，**不复用 17879** —— 老对端的 4 字节长度前缀与 TLS 的 0x16 混在一起是事故。
        if (linkTransport.canServeTls) {
            val tlsPort = profile.tlsPort
            if (tlsPort != null && tlsPort in 1..65535) {
                linkTransport.startServer(tlsPort) { connection -> links.handle(connection) }
            }
        }

        // 文件传输管理器：与 TCP 监听器同生共死。
        // 必须在开泵之前创建 —— 泵里会把入站 file-ctl 直接交给它。
        val manager = if (enableFileTransfer) {
            TransferManager(
                selfId = profile.nodeId,
                scope = runScope,
                tcpPort = tcpPort,
                downloadDir = downloadDir,
                sendReliable = { peerId, payload ->
                    messenger.sendUserMessage(
                        peerId,
                        makeEnvelope(MsgTypes.FILE_CTL, profile.nodeId, payload),
                    ) == SendOutcome.SENT
                },
                // task-62 关联补丁：把「这一次」的逐端点诊断绑到那条 transfer 上 ——
                // 全局 lastSend 会被随后的成功投递覆盖（真机里 z1/z2 失败、z3 成功，
                // 于是失败那两次的现场被 z3 盖掉，读出来反而误导）。
                sendReliableDiagnostics = { messenger.lastReliableSend() },
                // task-63：报价走**快速**可靠发送（最短退避、不入消息队列）—— 蜂窝下长连接会抖
                // 是常态，报价不该在交互路径上等满「候选数 × 满退避」（真机实测 48s）；
                // 失败的报价由 manager 自己排队重试（状态在 /v1/transfers 里如实呈现）。
                sendReliableQuick = { peerId, payload ->
                    messenger.sendReliableNoQueue(
                        peerId,
                        makeEnvelope(MsgTypes.FILE_CTL, profile.nodeId, payload),
                        quick = true,
                    )
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
                // 首帧是长连接 hello：交给 LinkServer（鉴权 + 只许局域网 + 采纳地址）。
                openLink = { socket, hello, host, port, read, write ->
                    linkServer?.open(socket, hello, host, port, read, write) == true
                },
                // F1（task-39）：跨网文件。开关与跨网文本下行**共用同一个** [linkEnabled]；
                // 关掉时计划里只剩局域网那条（可回滚）。公网必须 TLS + 已存 pin（fail-closed）。
                crossNetwork = CrossNetworkTransferPlanner { peerId, direct ->
                    val record = registry.get(peerId) ?: return@CrossNetworkTransferPlanner null
                    val pin = pinOfPeer(peerId)?.let { TlsPin.parse(it) }
                    CrossNetworkTransferPolicy(
                        enabled = linkEnabled,
                        canDialTls = crossNetworkRawDialer.canDialTls,
                    ).planWithConnector(
                        direct = direct,
                        candidates = registry.candidatesOf(peerId),
                        tlsPort = record.profile.tlsPort,
                        hasPin = pin != null,
                        // 5s 拨号超时：公网握手失败要快速退回局域网那条，不能挂住整次拉取。
                        connectRaw = { host, port ->
                            crossNetworkRawDialer.connect(host, port, pin, 5_000L)
                        },
                    )
                },
                // 主机侧入口：独立 TLS 监听（对端按 tlsPort + 1 来连）。起不来就只是没这条入口。
                crossNetworkListener = crossNetworkFileListener,
                crossNetworkFilePort = crossNetworkFilePort(profile.tlsPort),
            )
        } else {
            null
        }
        transfer = manager
        transferPump = manager?.let { m ->
            runScope.launch { m.events.collect { _transfers.tryEmit(it) } }
        }

        pump = runScope.launch {
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
        networkListenerCancel?.invoke()
        networkListenerCancel = null
        networkWatchCancel?.invoke()
        networkWatchCancel = null
        linkClient?.stop()
        linkClient = null
        linkServer?.close()
        linkServer = null
        runCatching { linkTransport.stopServer() }
        messenger.linkDownlink = null
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
        // 自有 scope 随 stop() 一起收掉；下一次 start() 会建一个新的。
        ownScope?.cancel()
        ownScope = null
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

    /**
     * **登记即返回**的报价（task-62）：不等 ACK 就把 transferId 交出去，投递在后台跑。
     *
     * 存在的理由：`/v1/send-file` 原来在请求线程上 `runBlocking { offerFiles(...) }`，
     * 对端不可达时要等完整退避 + TCP 兜底（几十秒）⇒ 调用方超时。
     *
     * 投递结果从两处读：① [offerDeliveries]（只读状态，控制面的 `/v1/transfers` 用它）；
     * ② [transfers] 事件流。**没有读出口就不许用 async** —— 那等于把「发不出去」变成
     * 「看起来发出去了」。
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
    ): String? = transfer?.offerFilesAsync(peerId, files, purpose, msgId, groupId, groupRev, tableText, isFolder, folderRootName)

    /**
     * **网络一变就立刻重试排队的报价**（task-63 追加）：接在既有的换网监听上。
     *
     * 为什么不只靠退避：退避上限 60s，而 K1/K3 的换网恢复口径是 ≤10s —— 报价不该比长连接慢
     * 一个数量级。提前触发是安全的：幂等键（transferId + seq）保证不会投出两份。
     */
    suspend fun retryQueuedOffersNow() {
        transfer?.retryQueuedOffersNow()
    }

    /** 只读出口（task-62）：最近若干次报价的投递状态。 */
    suspend fun offerDeliveries(): List<TransferManager.OfferDelivery> = transfer?.offerDeliveries().orEmpty()

    /**
     * 只读诊断（task-62）：最近一次**可靠投递**逐端点等了多久（与 `link*` 同口径）。
     *
     * 它回答的是「为什么没送到」：哪个端点 no-ack、各花多久、有没有回退 TCP、长连接当时通不通。
     */
    fun lastReliableSendDiagnostics(): ReliableSendDiagnostics? = messenger.lastReliableSend()

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

    /**
     * 网络路径变了：**立刻**重拨长连接（不等退避），并排空一次离线队列（K3 ①）。
     *
     * 为什么必须清退避：拨号是每 ≤2s 一次的循环，但每个对端带 1s→60s 的退避
     * （nextAttemptAt，上限 60s）。换网（Wi-Fi ↔ 蜂窝）时旧连接已经无效，而退避可能还压着
     * 几十秒 —— 不清它，每次切换 ≤10s 恢复就做不到。[LinkClient.requestImmediateDial]
     * 只清退避；真正建连仍由下一个 tick 完成（对端不可达时照样按 connectTimeout 失败并重新退避，
     * 这是诚实的）。
     *
     * 队列 flush 与重拨分开：重拨只是把路铺好，积压的消息要靠 flush 才真的上路
     * （Messenger.flushQueue 保序：第一个未 ACK 就停，剩下的等下次上线）。
     *
     * 重连成功后 LinkClient 会**重发 link 身份帧**（它本来就是每次建连的第一步），
     * 所以这里不需要额外再写一遍身份。
     */
    fun requestLinkReconnectNow() {
        val runScope = scope ?: return
        linkClient?.requestImmediateDial()
        runScope.launch {
            for (peerId in messenger.pendingAll().map { it.peerId }.distinct()) {
                runCatching { messenger.flushQueue(peerId) }
            }
        }
    }

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
        replyTo: String? = null,
        /**
         * 真正的说话者（可选）。信封 `from` 永远是**发送这台设备**，而这台设备可能在代别人
         * 说话（DSH agent 事件由桌面端转发）—— 不传它，手机端就会把 agent 说的话显示成
         * 「桌面端发的」，而桌面端自己是按 `agent:<kind>` 落库的，两端不一致。
         */
        senderId: String? = null,
        /**
         * 思考过程（可选）。见 `GroupTextMsgPayload.reasoning` 的 KDoc。
         *
         * 带它进群是「过程 + 最终回复都有」的前提：不带的话，同一个群里桌面端的气泡
         * 有思考折叠块、手机端没有。超长由 [NodeOutbound.sendGroupText] 截断。
         */
        reasoning: String? = null,) =
        outbound.sendGroupText(text, groupId, groupRev, members, mentions, replyTo, senderId, reasoning)

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
        ref: String? = null,
        /** 附件元数据（task-45）：**默认 null ⇒ 既有调用点一行不用改**。闸门在控制面（按对端 att1）。 */
        attachment: AgentEventAttachment? = null,) =
        outbound.sendAgentEvent(peerId, sessionId, cursor, eventType, summary, body, ref, attachment)

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

    suspend fun sendAgentApproval(
        peerId: String,
        approvalId: String,
        sessionId: String,
        tool: String? = null,
        summary: String? = null,
        expiresAt: Long,
        choices: String? = null,
        riskLevel: String? = null,
        diff: String? = null,
    ) = outbound.sendAgentApproval(peerId, approvalId, sessionId, tool, summary, expiresAt, choices, riskLevel, diff)

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
    suspend fun sendAgentGrantRequest(
        peerId: String,
        agentKey: String? = null,
        wsId: String? = null,
        access: String? = null,
        reason: String? = null,
        at: Long? = null,
    ) = outbound.sendAgentGrantRequest(peerId, agentKey, wsId, access, reason, at)

    /** PPE-1 增补 D7：授权决定（宿主 → 设备）。 */
    suspend fun sendAgentGrant(
        peerId: String,
        state: String,
        scope: String,
        agentKey: String? = null,
        wsId: String? = null,
        access: String? = null,
        isMyDevice: Boolean? = null,
        delegatedApproval: Boolean? = null,
        at: Long? = null,
    ) = outbound.sendAgentGrant(peerId, state, scope, agentKey, wsId, access, isMyDevice, delegatedApproval, at)

    /** PPE-1 增补 D7：授权清单条目（宿主 → 设备）。 */
    suspend fun sendAgentGrantList(
        peerId: String,
        scope: String,
        agentKey: String? = null,
        wsId: String? = null,
        access: String? = null,
        total: Long? = null,
        at: Long? = null,
    ) = outbound.sendAgentGrantList(peerId, scope, agentKey, wsId, access, total, at)

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

    /**
     * 周期维护：剪掉离线队列/去重表里过期的条目，并**裁剪信封日志**。
     *
     * 信封日志（`stores/messages.log`）的裁剪**不是**「待发」语义，所以它不进返回值 ——
     * 返回值是「哪些待发消息被放弃了」（上层据此把气泡标成失败）；日志里被裁掉的行只影响
     * `GET /v1/inbox?since=` 能回溯多远。
     *
     * 为什么必须在这里做：这份日志是追加写的，之前没有任何上限 —— 真机桌面端一天就长到
     * 12.9 万行 / 72 MB，而同一时刻消息表只有 1235 条。`FileMessageStore.trim` 早就写好了，
     * 只是**没人调用**。
     */
    fun prune(now: Long = nowMillis()): List<PrunedMessage> {
        // 上限与队列**分开取值**（见 `Timings.messageLogTtl` / `messageLogMaxRows` 的 KDoc）。
        messageStore.trim(now, timings.messageLogTtl, timings.messageLogMaxRows)
        return messenger.prune(now)
    }

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
