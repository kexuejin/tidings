// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.session

import com.kapp.tenon.core.ioDispatcher
import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.AgentEventAttachment
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.DEFAULT_TIMINGS
import com.kapp.tenon.core.protocol.Envelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.kapp.tenon.core.protocol.IpAddressUtils
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.MsgTypes
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.Protocol
import com.kapp.tenon.core.protocol.ScreenTcpFrame
import com.kapp.tenon.core.protocol.Timings
import com.kapp.tenon.core.protocol.makeEnvelope
import io.ktor.network.sockets.Socket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import com.kapp.tenon.core.transport.DIRECTION_IN
import com.kapp.tenon.core.transport.DedupStore
import com.kapp.tenon.core.transport.DatagramTransport
import com.kapp.tenon.core.transport.FileChannelRouter
import com.kapp.tenon.core.transport.FileChannelNegotiation
import com.kapp.tenon.core.transport.ChunkFileTarget
import com.kapp.tenon.core.transport.DiscoveryEngine
import com.kapp.tenon.core.transport.PresenceIngest
import com.kapp.tenon.core.transport.SYNTHETIC_NICK_PREFIX
import com.kapp.tenon.core.transport.InMemoryDedupStore
import com.kapp.tenon.core.transport.InMemoryMessageStore
import com.kapp.tenon.core.transport.InMemoryQueueStore
import com.kapp.tenon.core.transport.ManualPeer
import com.kapp.tenon.core.transport.MessageStatus
import com.kapp.tenon.core.transport.MessageStore
import com.kapp.tenon.core.transport.Messenger
import com.kapp.tenon.core.transport.NetworkWatch
import com.kapp.tenon.core.transport.PrunedMessage
import com.kapp.tenon.core.transport.startPlatformNetworkWatch
import com.kapp.tenon.core.transport.PeerEvent
import com.kapp.tenon.core.transport.PeerRecord
import com.kapp.tenon.core.transport.PeerRegistry
import com.kapp.tenon.core.transport.PeerEndpoint
import com.kapp.tenon.core.transport.KnownPeerStore
import com.kapp.tenon.core.transport.PeerEndpointStore
import com.kapp.tenon.core.transport.InMemoryPeerEndpointStore
import com.kapp.tenon.core.transport.InMemoryPeerPinStore
import com.kapp.tenon.core.transport.LinkClient
import com.kapp.tenon.core.transport.LinkServer
import com.kapp.tenon.core.transport.LinkSnapshot
import com.kapp.tenon.core.transport.LinkTransport
import com.kapp.tenon.core.transport.PeerPinStore
import com.kapp.tenon.core.transport.PlaintextLinkTransport
import com.kapp.tenon.core.crypto.TlsPin
import com.kapp.tenon.core.transport.CrossNetworkFileListener
import com.kapp.tenon.core.transport.CrossNetworkRawDialer
import com.kapp.tenon.core.transport.CrossNetworkTransferPlanner
import com.kapp.tenon.core.transport.CrossNetworkTransferPolicy
import com.kapp.tenon.core.transport.PullAttemptTransport
import com.kapp.tenon.core.transport.ScreenChannelHost
import com.kapp.tenon.core.transport.ScreenChannelNegotiation
import com.kapp.tenon.core.transport.ScreenWire
import com.kapp.tenon.core.transport.ScreenWireOpener
import com.kapp.tenon.core.transport.crossNetworkScreenPort
import com.kapp.tenon.core.transport.plainScreenWireOpener
import com.kapp.tenon.core.transport.readFirstScreenOpen
import com.kapp.tenon.core.transport.createCrossNetworkFileListener
import com.kapp.tenon.core.transport.createCrossNetworkRawDialer
import com.kapp.tenon.core.transport.ReliableSendDiagnostics
import com.kapp.tenon.core.transport.createPlatformLinkTransport
import com.kapp.tenon.core.transport.crossNetworkFilePort
import com.kapp.tenon.core.transport.LocalFile
import com.kapp.tenon.core.transport.TransferEvent
import com.kapp.tenon.core.transport.TransferManager
import com.kapp.tenon.core.transport.OutgoingTransfer
import com.kapp.tenon.core.transport.IncomingOffer
import com.kapp.tenon.core.transport.PartFile
import com.kapp.tenon.core.transport.QueueStore
import com.kapp.tenon.core.transport.QueuedMessage
import com.kapp.tenon.core.transport.SendOutcome
import com.kapp.tenon.core.transport.StoredMessage
import com.kapp.tenon.core.transport.TcpFramedChannel
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
 * 一个 Tenon 节点的运行时：把 transport / registry / discovery / messenger / store
 * 装配成"可直接用"的对象。
 *
 * 这是 `composeApp`（手机 UI）与 `agentNode`（本机常驻 agent 侧节点）**唯一**的共用入口 ——
 * 两边都不直接 new `UdpTransport` / [Messenger]，因为装配顺序（先订阅再 start）与
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
/**
 * luciola 模式下顶替 legacy Discovery 的哑实现（D12）。
 *
 * **发现动作**全是 no-op（发现由 extraDiscovery(luciola) 承担，NodeRuntime 的其余
 * 代码仍会调这些入口），但 sweep 不能跟着哑：legacy Discovery 的 sweepLoop 是
 * 注册表唯一的 `lastSeen > offlineAfter` 翻面器，哑掉之后对端静默死亡时「在线」
 * 永不翻转，休眠横幅（全部 hostNode 离线才出现）在 Android 上不可达——真机
 * SIGSTOP 宿主 5 分钟，UI 仍「在线」、link.json dialFailures 一路涨（A22 第八轮）。
 * 桌面保留 legacy Discovery 自带 sweep，两端不对称正是从这里开始的。
 * 与 legacy 的 sweepLoop 同判据同节奏（sweepInterval 节奏、offlineAfter 阈值）。
 */
internal class SweepOnlyDiscoveryEngine(
    private val registry: PeerRegistry,
    private val timings: Timings,
    /**
     * 定向心跳：给已知节点各发一帧 presence。返回 `(targets, sent)`：
     * targets = 这一轮**试图**投递的地址数，sent = 真正交出去的包数。
     *
     * 为什么必须有它：这个引擎的由来是「发现交给新传输，legacy 不再打局域网广播」，
     * 但它的 `announce*` 全是空实现 —— 于是**连单播心跳也一起没了**，而
     * [PeerRegistry.sweep] 照跑。真机读数（2026-11-10 16:4x，Pixel 6 前台、进程活着、
     * UDP 可达）：`lastSeen` 一路涨到 104.7 秒，宿主把手机判成 `online=false`，
     * 队列 `left=44` 只涨不降，界面挂「宿主设备睡眠」横幅；只有重启 App（触发一次
     * announce）才恢复。
     *
     * 为什么要区分 targets 与 sent：两者都为 0 是「本机还没有任何对端」的**常态**，
     * 而 targets>0 且 sent=0 是「socket 发不出任何东西」的**故障**（task-29 的静默
     * 停摆）—— 只有后者才该触发 [healTransport]。合在一个 Int 里这两种状态无法区分，
     * 空闲节点会被误判成泵停摆、传输面被反复重启。
     */
    private val heartbeat: (suspend () -> Pair<Int, Int>)? = null,
    /**
     * 心跳连续多轮「有目标却一个包都没交出去」时重建传输面。
     *
     * 这条自愈原来住在 legacy `Discovery.presenceLoop`（task-29：socket 被关/坏之后
     * `send` 静默失败，没有任何人重建它）；legacy 删除后它跟着搬家到这里 —— 心跳循环
     * 的下一个主人。null = 不自愈（纯观察的引擎）。
     */
    private val healTransport: (suspend () -> Unit)? = null,
    /**
     * 该节点此刻是否还有活跃长连接。sweep 拿它做豁免：连着就不因「没收到帧」翻离线。
     * 真机读数里 `linkConnected=true` 与 `online=false` 同时成立，界面据此说宿主睡眠。
     */
    private val stillReachable: (String) -> Boolean = { false },
) : DiscoveryEngine {
    private var sweepJob: Job? = null
    private var heartbeatJob: Job? = null
    override fun start(scope: CoroutineScope) {
        sweepJob = scope.launch {
            while (isActive) {
                delay(timings.sweepInterval)
                registry.sweep(timings.offlineAfter, stillReachable)
            }
        }
        // 心跳与 sweep 分开起：两者失败的后果不同（心跳漏一轮只是慢 30 秒翻正，
        // sweep 停摆则「静默死亡永不翻面」），合在一个循环里会让一个异常带走另一个。
        val beat = heartbeat ?: return
        val heal = healTransport
        heartbeatJob = scope.launch {
            // **启动即发一轮**。等一个 presenceInterval 再发（默认 30 秒）意味着：节点起来
            // 之后半分钟里对端完全不知道我们回来了 —— 真机上表现为"重连要等一会儿"，
            // 测试里表现为 `addManualPeer` 之后一直看不见对方。
            var deadRounds = 0
            suspend fun runRound() {
                val (targets, sent) = runCatching { beat() }.getOrDefault(0 to 0)
                // targets>0 且 sent=0 才算「泵停摆」：targets=0 是空闲节点的常态。
                deadRounds = if (sent > 0 || targets == 0) 0 else deadRounds + 1
                // 一轮一行只在**异常**时打：sent=0 说明这一轮一个包都没交出去（收端因此收不到
                // 心跳、`lastSeen` 会一路涨到 offlineAfter），restart 会短暂关闭接收泵。
                // 真机上「双方互相判离线」的锯齿只能靠这两行区分「没发」「发不出去」「发出去对方不收」。
                if (targets > 0 && sent == 0) println("[presence] sent=0 dead=$deadRounds")
                if (heal != null && deadRounds >= STALLED_ROUNDS_BEFORE_RESTART) {
                    deadRounds = 0
                    println("[presence] transport restart (no packet left for 2 rounds)")
                    runCatching { heal() }
                }
            }
            runRound()
            while (isActive) {
                delay(timings.presenceInterval)
                runRound()
            }
        }
    }
    override fun stop() {
        sweepJob?.cancel()
        sweepJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    override suspend fun announce() {}
    override suspend fun announceTo(host: String, port: Int) {}
    override suspend fun announceExit() {}

    private companion object {
        /**
         * 连续几轮"有目标却一个包都没交出去"就把 transport 重建一次。
         *
         * 2 轮 = 2 × presenceInterval（默认 60 秒）：目标本身是 `canReach == true` 的
         * 端点（有已连上的会话 / 无准入概念的回环），`send` 在这里失败不会是"网络不好"
         * 这种解释，只可能是本机这一侧写不出去。
         * 取 2 而不是 1，是为了不在一次瞬时错误上就重建连接。
         *
         * 判据的**覆盖边界**要清楚：没有会话的端点不计入 targets（它们是 `skipped`），
         * 所以"一台没有任何会话的节点 socket 全坏"这种情况这里不会触发重建 —— 那时
         * 也没有东西可投递，重建只会在下一轮把会话又断一次。真正需要救的是"明明有
         * 会话却发不出去"，这条判据正好盯着它。
         */
        const val STALLED_ROUNDS_BEFORE_RESTART = 2
    }
}

class NodeRuntime(
    /**
     * 本节点身份。**可写**，因为 `start()` 会把其中的 `tcpPort` 改写为**真正绑上的**那个端口
     * （见 [tcpBound]）：对外声明一个别人正在用的端口，后果是消息**投递错人**。
     */
    var profile: Profile,
    udpPort: Int = Protocol.DEFAULT_UDP_PORT,
    bindHost: String = "0.0.0.0",
    private val timings: Timings = DEFAULT_TIMINGS,
    /**
     * 用户手动添加的节点地址（只有 host:port —— 没有 nodeId，是一条**地址便条**）。
     *
     * ## 为什么它必须由心跳直接发包
     *
     * legacy 发现活着的时候，这些地址由 `Discovery` 的 presence 轮次负责投递；legacy 删掉
     * 之后如果没人接手，用户填进去的地址就是**一句空话**（他在设置里看到"已添加"，对端
     * 永远收不到我们的任何帧）。[sendPresenceHeartbeat] 因此单独遍历一遍这份列表：
     * registry 里的对端按 nodeId 发，手动地址按 host:port 发。
     *
     * 判据用 [DatagramTransport.dialable]（有凭据就拨），是否算作停摆证据仍看
     * [DatagramTransport.canReach] —— 见 [sendPresenceHeartbeat] 里两个判据的分工。
     */
    private val manualPeers: List<ManualPeer> = emptyList(),
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
     * 修剪陈旧身份时的**保留名单**（数据卫生，见 [REGISTRY_PRUNE_MS]）：群成员、
     * 用户手动维护的地址要认领。默认 `{ false }` = 只要不在线超过窗口就剪
     * （测试与无群宿主的行为）。
     */
    private val keepNode: (nodeId: String) -> Boolean = { false },
    /**
     * 提供时 Messenger 的传输面换成它（认证加密信道）。**必需** —— 没有默认值，也不许为 null。
     *
     * 曾经它的默认值 null 表示「完全 legacy(iOS / 未迁移宿主)」，于是两端各自决定跑哪条路：
     * 手机（`experimentalLuciola` 默认开）走加密信道、桌面（`TENON_LUCIOLA` 默认关）走明文 UDP，
     * 消息能通、在线状态却互相骗人。参数类型收成非空 + 无默认值，是为了让「忘了接 luciola」
     * 变成**编译错误**而不是一条静默降级路径。
     *
     * 是**工厂而不是实例**:luciola 的入站帧要按 `from` 查到对端地址才递得进
     * Messenger,而 registry 由本类自己创建(见 [registry])。宿主传
     * `{ registry -> factory.createTransport(registry) }`。
     */
    private val messengerTransportFactory: (PeerRegistry) -> DatagramTransport,
    /**
     * 是否启用**信道文件数据面**（`fch1` 协商通过时由宿主传 true）。
     *
     * 为什么在运行时这一层判断：路由器要同时知道「传输面怎么发帧」与「registry 里
     * 对端是谁」，那两样只有本类有。false = 不启用（逐字回到 legacy）。
     */
    private val fileChannelEnabled: Boolean = false,
    /**
     * 画面信道（luciola）宿主；null = 没有画面信道，投屏继续走 legacy（17879/17882）。
     *
     * 只有 `fileChannelEnabled`（luciola 模式）+ 档案里有 `screenPort` + 本参数非空
     * 三件齐了才会真的起监听**并**把新通路排进 Viewer 的计划 —— 缺一件就整体退回
     * legacy，绝不出现"声明了 scr1 却没人听画面端口"。
     */
    private val screenChannelHostFactory: ((PeerRegistry) -> ScreenChannelHost)? = null,
    /**
     * 画面信道的监听端口（宿主定，必须与 [screenChannelHost] 起的那条会话一致）。
     *
     * 由宿主传入而不是运行时自己猜：端口是**绑定结果**，猜错就是"我广播了一个没人听的
     * 端口"。null = 本机不提供画面信道（手机端按平台能力决定）。
     */
    private val screenChannelPort: Int? = null,
    /**
     * 额外发现引擎(阶段二 B-3:luciola 桥接)。与主 Discovery 并存:
     * registry 按 nodeId 去重;luciola 补 IPv6 多播/防 TUN 的发现覆盖,
     * legacy 继续承载 msg 帧(阶段三切 SecureChannel 后移除)。
     * 仅 JVM/Android 宿主传入 LuciolaDiscoveryEngine;iOS 传 null(legacy)。
     */
    private val extraDiscovery: DiscoveryEngine? = null,
    /**
     * 额外发现引擎的**工厂**版本（与 [extraDiscovery] 二选一,工厂优先）。
     *
     * 为什么需要:luciola 的发现把 found/updated/lost 直接写进 [registry],而 registry
     * 是本类自己创建的 —— 宿主拿不到实例,只能拿工厂（同 [messengerTransportFactory]）。
     */
    private val extraDiscoveryFactory: ((PeerRegistry, () -> Profile) -> DiscoveryEngine)? = null,
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
    /**
     * **对外宣告的地址**（进 `Profile.addresses`），默认与 [localAddresses] 相同。
     *
     * ## 为什么必须是两个列表而不是一个
     *
     * [localAddresses] 还承担「判断对端是不是同一段局域网」的职责（`Link.linkTargets`）。
     * 平台层刻意**过滤掉隧道网卡**（`utun` / `feth` / `tailscale`）—— 因为那些地址发不出
     * UDP 广播，留在列表里会让同网段判断选错路。
     *
     * 但 [Profile.addresses] 的用途**不是广播**，而是**单播告诉已配对的对端「该往哪儿拨」**。
     * Tailscale 的 `100.x` 恰恰只能靠单播到达 —— 沿用那份过滤后的列表，等于宣告的地址里
     * 永远没有隧道地址，跨网死锁照旧（真机：红米只拿到 `192.168.2.82`，而它走 VPN，拨不通）。
     *
     * 所以拆成两个：同网段判断用窄的，宣告用宽的。
     */
    private val advertisedAddresses: () -> List<String> = localAddresses,
    /**
     * 各网卡自己的**子网广播地址**（`192.168.2.255` 这类），默认空 = 只发受限广播。
     *
     * ## 为什么要单独一份
     *
     * 受限广播（`255.255.255.255`）没有具体路由条目，落到默认路由。而代理软件开 TUN 时
     * 会装 `0.0.0.0/1` + `128.0.0.0/1`（比 `/0` 更具体）⇒ **受限广播被整条接进代理的
     * 虚拟口**，局域网里一个对端都收不到。
     *
     * 真机现场：FClash 开 TUN 时 Tailscale / ZeroTier 照常能用（它们走 `100.64/10` 这类
     * 更具体的路由），**只有 Tenon 失联** —— 红米 8 次拨号全失败、队列堆到 594 条。
     *
     * 子网广播有直连路由（`192.168.2.0/24`，前缀更长），抢不走。
     */
    private val subnetBroadcasts: () -> List<String> = { emptyList() },
    /**
     * **该不该为一个 peer 的发送失败排队。** 返回 false ⇒ 那一帧不入队。
     *
     * 由上层（它掌握 known-peers）回答「这台设备还可能回来吗」。默认 null = 不判断，
     * 行为与加这个参数之前一字不差。
     *
     * 为什么需要：真机上队列里有 165 条是把群元数据发给一台**已消失**设备的，而且它还在
     * 某个群的成员表里 —— 每一轮群广播都给它添一条，永远发不出去、也永远新鲜到碰不到 TTL。
     */
    private val acceptQueueFor: ((String) -> Boolean)? = null,
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
    /**
     * 跨网屏幕监听（`tlsPort + 2`,与文件的 `tlsPort + 1` 同族）：独立实例、独立端口,
     * 首帧 `screen-open` 交给 [onOpenScreenWire]。起不来只是没有这条跨网入口,
     * 局域网原路径（[onOpenScreen]）不受影响。
     */
    private val crossNetworkScreenListener: CrossNetworkFileListener =
        createCrossNetworkFileListener(linkIdentityDir, localAddresses),
    private val onOpenScreen: suspend (Socket, ScreenTcpFrame.Open, String, ByteReadChannel, ByteWriteChannel) -> Boolean =
        { _, _, _, _, _ -> false },
    /** 跨网 TLS 监听的首帧投屏交接（通道是 TLS 解密后的裸流,协议与明文路完全一致）。 */
    private val onOpenScreenWire: suspend (ScreenTcpFrame.Open, ScreenWire) -> Boolean =
        { _, _ -> false },
) {

    // registry 必须先于 transport:luciola 工厂需要它来解析入站帧的对端地址。
    private val registry = PeerRegistry(profile.nodeId)

    // 传输面**只有一条**：luciola 加密信道。曾经这里是
    // `messengerTransportFactory?.invoke(registry) ?: UdpTransport(...)` —— 那个 `?:` 就是
    // 「手机跑安全信道、桌面跑 legacy」的入口：两端各自决定，谁也不知道对方跑的是哪条路。
    private val transport: DatagramTransport = messengerTransportFactory.invoke(registry)

    // 额外发现:实例优先,其次工厂(两者都空 = 纯 legacy)。
    private val resolvedExtraDiscovery: DiscoveryEngine? =
        extraDiscovery ?: extraDiscoveryFactory?.invoke(registry, { selfProfile })
    private val tcp: TcpFramedChannel? = if (enableTcp) TcpFramedChannel() else null

    /**
     * 画面信道宿主（`start` 里按 [screenChannelHostFactory] + 本运行时的 registry 建）。
     *
     * 为什么是工厂而不是实例：宿主对象需要 `PeerRegistry`（指纹 → 端点归属），而
     * registry 是本运行时自己建的 —— 与 `messengerTransportFactory` 同一个理由。
     */
    private var screenChannelHost: ScreenChannelHost? = null

    /**
     * 信道文件数据路由器（仅 `fileChannelEnabled` 时存在）。
     *
     * 发送面绑到「registry 查端点 → transport.send」：加密信道是 TCP，端口用对端的
     * `luciolaPort`（发现登记时已归一到 registry 的 udpPort，见 LuciolaDiscoveryEngine）。
     */
    val fileChannel: FileChannelRouter? =
        if (fileChannelEnabled) {
            FileChannelRouter(
                sendToPeer = { peerId, bytes ->
                    val record = registry.get(peerId)
                    if (record == null || !record.online) {
                        false
                    } else {
                        transport.send(bytes, record.ip, record.udpPort)
                    }
                },
            )
        } else {
            null
        }


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
        get() = profile.copy(
            tlsPin = linkTransport.localPin?.wire ?: profile.tlsPin,
            // 把**信道的监听端口**告诉对端:发现引擎上报的端口可能是临时端口,
            // 不能拿来拨号(真机实测 17878 vs 52213)。只有一条传输形态，所以不再有条件。
            luciolaPort = transport.boundPort.takeIf { it > 0 },
            // **把本机全部可路由地址带出去**（含 Tailscale / ZeroTier 段）。
            //
            // 对端原先只能从「发现包的源地址」学地址，而广播不跨 VPN 网段 ⇒
            // 一方换网后双方都记着对方不可达的旧地址、且都收不到对方的包 ⇒
            // 永远互相学习不到新地址（真机：红米 30 分钟零收包，而 ping 它 2.2s 通）。
            //
            // 带上全部地址后，任意一条通路收到过一次 profile 就能打破死锁。
            addresses = advertisedAddresses().takeIf { it.isNotEmpty() } ?: profile.addresses,
            // 画面信道的端口：只在真的会起监听时才广播（否则就是对端一直等握手）。
            screenPort = screenChannelPort
                ?.takeIf { screenChannelHost != null && it in 1..65535 && fileChannelEnabled },
        )

    /**
     * 发现引擎：**只有** sweep + 定向心跳（发现由 luciola 引擎承担）。
     *
     * 曾经这里有一对分支 `if (legacyDiscoveryEnabled) Discovery(...) else SweepOnly...`。
     * 删掉前一支的同时也删掉了 `legacyDiscoveryActive` 属性 —— 它存在的唯一理由是让控制面
     * 区分「对端关机」与「对端还在老传输上」，而本机现在**不可能**在老传输上；
     * 剩下那一半判断（对端有没有 luciola 能力）由 `posesLuciolaCaps(peer)` 直接回答，
     * 中间夹一个恒 false 的本机标志只会让读代码的人以为还有第二种形态存在。
     */
    private val discovery: DiscoveryEngine = SweepOnlyDiscoveryEngine(
        registry, timings,
        heartbeat = ::sendPresenceHeartbeat,
        // task-29 的自愈搬家：心跳连续两轮「有目标却一个包都没交出去」时重建传输面。
        // 原来住 legacy `Discovery.presenceLoop`，legacy 删除后跟着心跳循环一起到这里。
        healTransport = { transport.restart() },
        // 延迟求值：engine 在本属性初始化时就构造，而 `messenger.linkDownlink` 要到
        // `start()` 才被赋值 —— lambda 只在 sweep 那一轮才读，读到的永远是当时的值。
        //
        // **判据必须与 `/v1/peers` 同源**：控制面报的 `linkConnected` 来自
        // `linkSnapshot().carrierOf()`（发起侧 + 接受侧的 carriers 合并），而
        // `linkDownlink.isConnected()` 只看接受侧的 `links` 表。真机上这两条对同一个
        // peerId 给出相反结论（2026-11-10 17:3x：接口 `linkConnected=true carrier=plaintext`
        // 与翻面日志 `link=false` 同时成立，而 `linkCloseCount=0`、`linkDialAttempts=0`
        // 说明链路根本没断过）。一个对端有两个"在不在"的事实来源，判据就该只有一个。
        stillReachable = { peerId -> linkSnapshot().carrierOf(peerId) != null },
    )

    /**
     * 定向心跳：给 registry 里每个已知节点各发一帧**带全量资料**的 entry。
     *
     * 只发单播，不打局域网广播 —— 发现仍归新传输（这是 [SweepOnlyDiscoveryEngine]
     * 存在的理由）；这里补的是「我方还活着」这一句，收端据此刷新 `lastSeen`。
     *
     * ## 为什么是 ENTRY 而不是 PRESENCE
     *
     * 收端对 PRESENCE 是有条件的：`Discovery.handlePresence` 在「本机已把它判成离线」
     * 或「源地址变了但我们没有它的资料」时会**拒绝**它 —— 而那正是本心跳要救的情形
     * （对端已经把我们判离线了）。ENTRY 带全量资料，收端 `confirmed = true` 直接采纳
     * 并把 online 一起翻回来，不依赖任何反向 UDP 路径。
     *
     * 这条取舍在 `Discovery.presenceRound` 里已有先例：对候选端点补的就是 ENTRY。
     *
     * ## 为什么给**判成离线**的节点也发
     *
     * 离线是本地推断，不是事实。真机样本里对端 11 秒前才发过帧、注册表却写着
     * `online=false`；只给在线节点发，等于让这个错误的推断自我固化。
     *
     * @return `(targets, sent)`：**可送达**目标数与真正交出去的包数。
     *   `targets=0` = 眼下没有能发的对端（空闲或未配对，常态）；`targets>0 && sent=0` =
     *   有准入却一个包都发不出去（泵停摆，心跳循环据此触发传输面自愈）。
     *   未配对的历史端点只计入 `skipped`，不算停摆 —— 否则一台只剩离线旧身份的节点
     *   每两轮就重建一次传输面，把已经连入的会话全断掉。
     *   **手动地址**按 [DatagramTransport.dialable] 决定拨不拨，是否计入 targets 仍看
     *   [DatagramTransport.canReach]：裸 UDP 上两者都恒真（send 失败只可能是本机 socket
     *   坏了，正是 task-29 的原始判据），安全信道里没有会话的手动地址则只拨不计数 ——
     *   那是「对方不可达」，不是本机泵死。
     */
    private suspend fun sendPresenceHeartbeat(): Pair<Int, Int> {
        // 与 [announceTo] 同一条帧：两处各写一遍 payload 迟早不一致（比如漏掉 luciolaPort）。
        val bytes = entryBytes()
        val selfId = profile.nodeId
        var sent = 0
        var targets = 0
        var linked = 0
        var skipped = 0
        val failed = mutableListOf<String>()
        val tried = mutableSetOf<String>()
        for (record in registry.values()) {
            val peerId = record.profile.nodeId
            if (peerId == selfId) continue
            // **有链路的不发**。理由有两条，缺一条都会让这段代码变成摆设：
            //  · 语义上：链路活着本身就是「我还活着」的证据，sweep 已按同一条判据豁免它们；
            //  · 事实上：安全信道的会话键是 `ip:tcpPort`（真机 `dialLastTarget=192.168.2.82:17879`），
            //    拿 `ip:udpPort` 去 send 只会得到 `send failed connected=false` —— 真机读数里
            //    12 个目标全部如此，心跳等于一行都没发出去，日志还写着「发了」。
            if (linkSnapshot().carrierOf(peerId) != null) {
                linked++
                continue
            }
            if (record.ip.isBlank() || record.udpPort !in 1..65535) continue
            val address = "${record.ip}:${record.udpPort}"
            if (!transport.canReach(record.ip, record.udpPort)) {
                skipped++
                continue
            }
            tried += address
            targets++
            if (transport.send(bytes, record.ip, record.udpPort)) sent++ else failed += address
        }
        // 手动地址：只有 host:port，进不了 registry（那里按 nodeId 索引），所以按地址直发。
        // 漏掉这一段 = 用户手动添加的节点从此收不到我们的任何帧（legacy 发现删掉后的空洞）。
        //
        // 两个判据必须分开，它们回答的是不同问题：
        // - **拨不拨** ⇒ `dialable`：有配对凭据就值得试一次。用户填地址要的就是「现在没会话
        //   也拨一下」，用 `canReach` 过滤等于把手动添加关掉（会话一断就再没人拨它）；
        // - **算不算停摆** ⇒ `canReach`：只有「此刻本该能交付」的端点失败才归本机泵。
        //   裸 UDP 恒为真（send 失败只可能是 socket 坏了 —— 这是 task-29 的原始判据）；
        //   安全信道里没有会话的手动地址不算（那是对方不可达，不是本机泵死）。
        var manualTried = 0
        for (peer in manualPeers) {
            if (peer.udpPort !in 1..65535 || peer.host.isBlank()) {
                failed += "manual-invalid:${peer.host}:${peer.udpPort}"
                continue
            }
            val address = "${peer.host}:${peer.udpPort}"
            if (!transport.dialable(peer.host, peer.udpPort)) {
                skipped++
                continue
            }
            tried += address
            manualTried++
            if (transport.canReach(peer.host, peer.udpPort)) targets++
            if (transport.send(bytes, peer.host, peer.udpPort)) sent++ else failed += address
        }
        // 候选端点也要发（task-21 ①）：对端换网后 registry 里那份可能已经不可达，
        // 而候选池里的新地址（VPN / 运营商）可能是唯一通路。ENTRY 带全量资料，收端
        // confirmed=true 直接采纳（连 online 一起翻回来），不依赖任何反向路径 ——
        // 这是「双方互相判离线」死锁的出口。这个原来住 legacy `presenceRound`，
        // legacy 删除后跟着心跳循环搬家。与已发地址重合的候选跳过（同一帧发两遍只会
        // 制造重复日志，对协议没有增益）。
        for ((peerId, endpoints) in registry.allCandidates()) {
            if (peerId == selfId) continue
            if (linkSnapshot().carrierOf(peerId) != null) continue
            for (endpoint in endpoints) {
                if (endpoint.host.isBlank() || endpoint.port !in 1..65535) continue
                val key = "${endpoint.host}:${endpoint.port}"
                if (key in tried) continue
                if (!transport.canReach(endpoint.host, endpoint.port)) {
                    skipped++
                    continue
                }
                tried += key
                targets++
                if (transport.send(bytes, endpoint.host, endpoint.port)) sent++ else failed += key
            }
        }
        // 只在**没发全**时出声：全发出去是常态，逐轮一行会把日志淹掉；
        // 而「有目标却一个都没出去」正是心跳变成摆设的样子，必须留下痕迹。
        if (targets > 0 && sent < targets || failed.isNotEmpty()) {
            println(
                "[heartbeat] entry targets=$targets sent=$sent manual=$manualTried" +
                    " linked=$linked skipped=$skipped failed=${failed.take(8)}",
            )
        } else if (targets == 0 && (skipped > 0 || linked > 0 || manualTried > 0)) {
            // 全是「不可投递」或「走链路」的轮次不发包，但**不能一声不响**：A31 之后
            // `targets=0` 成了稳态（历史配对端点没有会话），日志里若只剩「什么都没发生」，
            // 事后分不清是心跳在跑而目标都不可达，还是心跳循环根本没起来。一分钟最多一行。
            val now = nowMillis()
            if (now - lastIdleHeartbeatLog >= IDLE_HEARTBEAT_LOG_MS) {
                lastIdleHeartbeatLog = now
                println("[heartbeat] idle linked=$linked skipped=$skipped manual=$manualTried")
            }
        }
        return targets to sent
    }

    /** 上一行 `[heartbeat] idle` 的时间（限流用，避免每轮刷屏）。 */
    private var lastIdleHeartbeatLog = 0L
    internal val messenger = Messenger(
        transport = transport,
        registry = registry,
        selfNodeId = profile.nodeId,
        queueStore = queueStore,
        dedupStore = dedupStore,
        timings = timings,
        tcpFallback = tcp,
        acceptQueueFor = acceptQueueFor,
        preferTransport = true,
    )
    private val outbound = NodeOutbound(
        messenger = messenger,
        messageStore = messageStore,
        selfId = { profile.nodeId },
        selfProfile = { selfProfile },
        // 能力门控收成一条问句：NodeOutbound 不认识 NodeRuntime，逐成员门控才能被单测覆盖。
        peerDeclares = { peerId, cap -> peerDeclares(peerId, cap) },
    )


    private val _inbound = MutableSharedFlow<Envelope>(extraBufferCapacity = 1024)
        /** 实时交接挤掉计数（诊断字段）：丢的是实时订阅者那一跳，messageStore 已先落。 */
        val handOffDropped = atomic(0L)

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
     * 扫码后向对端发配对自证(luciola)。false = 本节点没跑加密信道/该对端码里没有密钥。
     */
    suspend fun requestLuciolaPairing(host: String, port: Int): Boolean =
        transport.requestPairing(host, port, profile.nodeId)

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
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private fun startKnownPeerPersistence(runScope: CoroutineScope) {
        val store = knownPeerStore ?: return
        // `java.util.concurrent.atomic` 是 JVM-only —— 它在 commonMain 里会让
        // **iOS 编译不过**（Unresolved reference 'java'）。用 stdlib 的跨平台原子类型。
        val dirty = kotlin.concurrent.atomics.AtomicBoolean(false)
        registry.addListener { event ->
            // Online 也要记：它意味着"这台设备确实存在且可达过"，比任何静态资料都硬。
            // 跨平台 AtomicBoolean 的写是 `store`（JVM 版叫 `set`）。
            // Removed 也要标脏：用户删掉一个节点后，文件里那条必须跟着消失，
            // 否则重启时 restoreOffline 会把它原样放回来（「删了没用」的现场）。
            if (event is PeerEvent.Updated || event is PeerEvent.Online || event is PeerEvent.Removed) {
                dirty.store(true)
            }
        }
        runScope.launch {
            while (isActive) {
                delay(KNOWN_PEER_FLUSH_MS)
                if (!dirty.compareAndSet(true, false)) continue
                runCatching { store.save(knownPeersSnapshot()) }
            }
        }
    }

    /**
     * 当前 registry 的「已知对端」快照（落盘用）。
     *
     * 抽成函数是为了让「删除节点」那条路与后台定时落盘**写的是同一份形状** ——
     * 两处各拼一遍字段，迟早一处漏字段（known-peers 里少一列，重启后那台设备就只剩 nodeId）。
     */
    private fun knownPeersSnapshot(): List<com.kapp.tenon.core.persist.KnownPeer> =
        registry.values().map { record ->
            com.kapp.tenon.core.persist.KnownPeer(
                profile = record.profile,
                ip = record.ip,
                udpPort = record.udpPort,
                lastSeen = record.lastSeen,
            )
        }

    /**
     * 把一条节点从**本机**节点表里删掉（用户显式动作）。
     *
     * 与 [PeerRegistry.forget] 的分工：那边只动内存；这里把四份落盘一起缩掉，而且
     * **立刻**写，不等 1.5 秒的合并窗口 —— 用户在按下确认后马上杀进程，也应该得到
     * 「删掉了」的结果，而不是重启后它又回来了。
     *
     * 注意：删完不等于「拉黑」。它下次在局域网里出现时，发现流程会重新学到它；
     * 要拒绝一台设备是另一件事（信任库），不在本函数的语义里。
     *
     * @return 这条节点此前是否存在过。
     */
    fun forgetPeer(nodeId: String): Boolean {
        if (!registry.forget(nodeId)) return false
        runCatching { knownPeerStore?.save(knownPeersSnapshot()) }
        runCatching { peerEndpointStore.save(registry.allCandidates()) }
        runCatching {
            peerPinStore.save(registry.allPins())
            peerPinStore.saveSources(registry.allPinSources())
        }
        return true
    }

    private companion object {
        /**
         * 已知对端的落盘间隔。1.5 秒：
         *  - 短到 kill 之前几乎一定已经落过（用户点"强制停止"前通常有这点时间）；
         *  - 长到不会把心跳帧变成磁盘写入（否则一个在线群体每秒几十次 IO）。
         */
        const val KNOWN_PEER_FLUSH_MS: Long = 1_500L

        /**
         * 离线身份的修剪窗口（7 天）：超过这么久没露面且不在保留名单里的节点条目，
         * 启动时连 pin/候选池一起剪掉（[keepNode] 认领的除外）。
         *
         * 为什么是 7 天而不是更短：群成员之外的对端，7 天没见大概率是退役设备/一次性
         * 身份；也不至于把"出长差回来"的同事剪掉。剪了不是拉黑 —— 设备再出现时发现
         * 流程会原样把它学回来。
         */
        const val REGISTRY_PRUNE_MS: Long = 7L * 24 * 60 * 60 * 1000

        /**
         * `[heartbeat] idle` 的限流窗口（60 秒）。
         *
         * 这一行只在**一轮没发任何包**（全是走链路/不可投递的端点）时出现，用来证明
         * 心跳循环还在跑；比 presenceInterval（默认 30 秒）长一倍，稳态下最多两轮一行。
         */
        const val IDLE_HEARTBEAT_LOG_MS: Long = 60_000L
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
     * 全部对端的候选地址 —— 诊断用（`GET /v1/candidates`）。
     *
     * 与 `/v1/peers` 的分工：那里只有「正在用的那一个」地址，看不出「还有哪些路可以试」。
     * 排查「换了网就连不上」时，**先看这里有没有对方的 VPN 地址** —— 没有就说明死锁未破，
     * 需要一次显式握手（`POST /v1/announce`）。
     */
    fun candidateEndpoints(): Map<String, List<PeerEndpoint>> = registry.allCandidates()

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
     * 屏幕通道的连接计划（Viewer 侧）：局域网明文直连第一、跨网 TLS+pin 候选其后 ——
     * 与 F1 文件拉取（TransferManager 的 planner）同一条纪律、同一个 [CrossNetworkTransferPolicy]。
     *
     * 空表 = 一条都排不出来（对端不在 registry）：调用方按「对端不在线」处理。
     * `canDialTls=false`（iOS 的 raw TLS 未落地）⇒ 只剩明文直连,公网场景连不上并如实
     * 报 timeout —— **不降级明文出公网**（fail-closed）。
     */
    fun screenWireAttempts(peerId: String): List<ScreenWireOpener> {
        val record = registry.get(peerId) ?: return emptyList()
        val pin = pinOfPeer(peerId)?.let { TlsPin.parse(it) }
        val plan = CrossNetworkTransferPolicy(
            enabled = linkEnabled,
            canDialTls = crossNetworkRawDialer.canDialTls,
        ).planScreen(
            direct = PeerEndpoint(record.ip, record.profile.tcpPort),
            candidates = registry.candidatesOf(peerId),
            tlsPort = record.profile.tlsPort,
            hasPin = pin != null,
            localAddresses = localAddresses(),
        )
        val legacy = plan.map { attempt ->
            when (attempt.transport) {
                PullAttemptTransport.Plaintext -> plainScreenWireOpener(attempt.host, attempt.port)
                // 5s 拨号超时：公网握手失败要快速试下一条候选,不能挂住整场连接。
                PullAttemptTransport.Tls -> ScreenWireOpener {
                    crossNetworkRawDialer.connect(attempt.host, attempt.port, pin, 5_000L)
                        ?.let { conn ->
                            ScreenWire(conn.read, conn.write) { runCatching { conn.close() } }
                        }
                }
            }
        }
        // 新数据面排在**最前**：协商通过就优先走加密画面会话，失败自然落到 legacy
        // 的那几条（ScreenReceiver 按序试，"握上手"才算这条通）。
        //
        // **按对端候选地址逐条尝试**（不只局域网那一个）：画面信道要替代 17882 那条
        // 跨网 TLS 入口，就必须能走公网地址 —— 只拨 `record.ip` 的话，一旦退役 17882，
        // 蜂窝/跨网场景就彻底没有投屏了（真机先把这条补上，再谈退役）。
        // 候选顺序沿用 registry 的池子（同一条纪律见 CrossNetworkTransferPolicy），
        // 局域网地址天然排在前面。
        val host = screenChannelHost
        val peer = record.profile
        val screenPort = peer.screenPort
        // 分级声明（D-018）：self 侧用画面信道的**本地事实**（host 是否起来），
        // 不再回看广播 caps —— scr1 已停止广播。
        if (host != null && screenPort != null &&
            ScreenChannelNegotiation.canUseChannel(
                selfChannelAvailable = true,
                peer = peer,
            )
        ) {
            val addresses = buildList {
                add(record.ip)
                registry.candidatesOf(peerId).forEach { c ->
                    if (c.host.isNotBlank() && c.host != record.ip) add(c.host)
                }
            }
            return addresses.map { addr -> host.opener(addr, screenPort) } + legacy
        }
        return legacy
    }

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
     * 本机身份证书的到期时刻（毫秒）；没有身份 / 明文实现返回 null。
     *
     * 与 [localTlsPin] 同一档：都是「本机身份」的只读读数 —— 区别只是这个给诊断页看，
     * 那个进配对二维码。
     */
    fun localCertNotAfterMillis(): Long? = linkTransport.localCertNotAfterMillis

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
        // 同理，上一次配对学到的 pin 也先装回来：公网长连接的信任根跨重启必须还在。
        // 来源一起装回来 —— 否则重启后 linkPinSource 就再也看不出"这枚其实是 TOFU 学来的"。
        val pinSources = peerPinStore.loadSources()
        for ((nodeId, pin) in peerPinStore.load()) {
            registry.setPin(nodeId, pin, pinSources[nodeId] ?: PeerRegistry.PIN_SOURCE_UNKNOWN)
        }
        // 数据卫生：恢复回来的名录里剪掉久未露面的离线身份（重装换过身份的手机、
        // 联调/夹具留下的一次性节点 —— 真机上节点表与建群成员选择器被它们堆到 40+ 条）。
        // 放在 restore/pin 恢复之后、拨号循环之前：剪完的名单就是本次运行拨号的底册。
        // 设备再出现时发现流程会原样学回来，所以这只影响"多久没见就不再显示"。
        //
        // 修剪**不动 pin**（见 PeerRegistry.pruneOfflineOlderThan 的 KDoc）：所以下面只把
        // 名录与候选池落盘，pin 表保持原样 —— 别在这里顺手 save 一遍 pin，那会把
        // "过期 = 撤销信任"重新写回来。
        val pruned = registry.pruneOfflineOlderThan(REGISTRY_PRUNE_MS, keepNode)
        if (pruned.isNotEmpty()) {
            println("[registry] pruned " + pruned.size + " stale offline identities" +
                " (older than " + REGISTRY_PRUNE_MS + "ms)")
            // 两份落盘跟着缩，不等合并窗口 —— 否则重启时被剪的身份又原样回来。
            runCatching { knownPeerStore?.save(knownPeersSnapshot()) }
            runCatching { peerEndpointStore.save(registry.allCandidates()) }
        }
        startKnownPeerPersistence(runScope)
        // 信道数据帧（文件/投屏字节）在 **Messenger 之前**分流：它们是二进制帧，
        // 交给 Messenger 的 Envelope 解码只会被判成坏帧丢掉；而一条已经被认领的
        // TDCH/TDCA 帧不应该再进可靠消息管线（它本来就不需要 ACK/去重）。
        fileChannel?.let { router ->
            runScope.launch {
                transport.incoming.collect { datagram ->
                    runCatching { router.onBinaryFrame(datagram.bytes) }
                }
            }
        }
        // 画面信道宿主先建起来：`selfProfile` 要按"它到底建没建"决定广播不广播 screenPort
        // （广播一个没人听的端口，对端会一直等 screen-ready）。
        screenChannelHost = screenChannelHostFactory?.let { factory ->
            runCatching { factory(registry) }
                .onFailure { t -> println("[luciola] screen channel host failed: " + t) }
                .getOrNull()
        }

        // 入站发现帧的登记器（`entry` / `alive` / `presence` / `profile` / `exit`）。
        //
        // legacy `Discovery` 删掉之后，这几类帧的处理必须有人接手：定向心跳
        // （`sendPresenceHeartbeat`）与手动添加的节点都会发 `entry`，收端如果没人处理，
        // 它就在 transport 层静默落地 —— 双方节点表里永远没有对方，而日志一片安静。
        // 这就是 `TwoNode*` 那批用例在本轮改动里集体超时暴露出来的东西。
        val presenceIngest = PresenceIngest(
            transport = transport,
            registry = registry,
            scope = runScope,
            timings = timings,
            selfProfile = { selfProfile },
            // task-38：对端资料里带 tlsPin ⇒ 配对时就把它的 pin 固定下来（来源标 pairing），
            // 不再依赖 TOFU 的首次固定 —— 那个有"攻击者抢先声明 nodeId"的首次使用竞态。
            onPeerPin = { nodeId, pin -> setPeerPin(nodeId, pin, PeerRegistry.PIN_SOURCE_PAIRING) },
            // `scan-ranges` 这类 gossip 帧不进可靠通道，只能由原始入口递上来。
            // 直接投 `_inbound`：它**不是**一条聊天记录，不该进 messageStore。
            onGossip = { envelope -> _inbound.tryEmit(envelope) },
        )
        runScope.launch {
            transport.incoming.collect { datagram ->
                runCatching { presenceIngest.onDatagram(datagram) }
            }
        }

        discovery.start(runScope)
        resolvedExtraDiscovery?.start(runScope)
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
            // **消息积压同样要重试**。上一次 `markOffline` 是在上一张网下作出的；而离线 +
            // 没有候选池的对端在稳态重试（`retryPendingQueues`）里一次都不会被尝试 ——
            // 症状是换网后队列只涨不降、界面一直显示对方离线（见 Messenger 那条的 KDoc）。
            runScope.launch { runCatching { messenger.retryQueuedAfterNetworkChange() } }
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
                // 信道文件数据面：**只有** 启用 + 对端声明 fch1 才接管。
                // 接管 = 登记到路由器且**不发 pull**；否则返回 false，走 legacy 拨号拉取。
                registerChannelInbound = fileChannel?.let { router ->
                    register@ { offer, saveDir, onProgress, onCompleted, onFailed ->
                        val peerCaps = registry.get(offer.peerId)?.profile?.caps
                        // 分级声明（D-018）：self 侧用**数据面事实**（fileChannelEnabled），
                        // 不再回看广播 caps —— fch1 已停止广播，广播里没有不代表本端解不动。
                        val allows = FileChannelNegotiation.canUseChannel(
                            selfChannelAvailable = fileChannelEnabled,
                            peerCaps = peerCaps,
                            // offer 是**从信道里推过来的**：对端已经用这条数据面把字节发出来了，
                            // 这就是最硬的「解得动」证据。D-018 摘掉 fch1 广播后，caps 这条对
                            // 新端恒为 false（两端都不广播），只看 caps 会让文件永远协商失败、
                            // 退回已退役的 legacy 拨号端口（真机：patch.diff 传输失败：网络连接中断）。
                            peerPushedOverChannel = true,
                        )
                        if (!allows) {
                            // 不接管 ⇒ 上层回退 legacy 拨号。这条必须留下原因：
                            // "对端没声明 fch1" 与 "我自己没声明" 是两种完全不同的故障。
                            println(
                                "[luciola] channel inbound declined: selfFch1=" +
                                    fileChannelEnabled +
                                    " peerCaps=" + peerCaps,
                            )
                            return@register false
                        }

                        var registered = 0
                        for (file in offer.files) {
                            if (file.isDir) continue
                            val ok = router.registerInbound(
                                peerId = offer.peerId,
                                transferId = offer.transferId,
                                fileId = file.fileId,
                                // 信道模式先只校验长度；sha 由 done 帧给（后续步骤）。
                                target = ChunkFileTarget(file.relPath, file.size, null),
                                root = saveDir,
                                onProgress = { done, total -> onProgress(file.fileId, done, total) },
                                onCompleted = { path -> onCompleted(file.fileId, path) },
                                onFailure = { reason -> onFailed(file.fileId, reason) },
                            )
                            if (ok) registered++
                        }
                        if (registered == 0) {
                            println(
                                "[luciola] channel inbound registered 0 files: transfer=" +
                                    offer.transferId.take(8) + " files=" + offer.files.size,
                            )
                        }
                        registered > 0
                    }
                },
                // 出站：对端 accept 之后我们**推**。sha 由对端按长度校验（与 legacy 同一纪律）。
                pushFileOnChannel = fileChannel?.let { router ->
                    push@ { transferId, peerId, files, onProgress ->
                        println(
                            "[luciola] channel push start transfer=" + transferId.take(8) +
                                " peer=" + peerId.take(8) + " files=" + files.size,
                        )
                        for (file in files) {
                            val pushed = router.sendFile(
                                peerId = peerId,
                                transferId = transferId,
                                fileId = file.fileId,
                                source = com.kapp.tenon.core.transport.DiskFileChunkSource(file.absPath),
                                onProgress = { done -> onProgress(file.fileId, done, file.size) },
                            )
                            println(
                                "[luciola] channel push file=" + file.fileId.take(8) +
                                    " sent=" + pushed + "/" + file.size,
                            )
                            if (pushed < file.size) {
                                throw IllegalStateException("channel push incomplete for " + file.fileId)
                            }
                        }
                    }
                },
                // stall 看门狗的两根探针： caps 声明与现实不符时（对端降级回 legacy 而本端
                // 还拿着它声明过 fch1 的旧资料），登记上信道的收器一个字节都等不到 ——
                // 看门狗据此回退 legacy 拨号，文件不再死在「两端互等」上。
                channelInboundHasBytes = fileChannel?.let { router ->
                    { transferId: String -> router.hasReceivedBytes(transferId) }
                },
                channelForgetInbound = fileChannel?.let { router ->
                    { transferId: String -> router.forget(transferId) }
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
                        localAddresses = localAddresses(),
                    )
                },
                // 主机侧入口：独立 TLS 监听（对端按 tlsPort + 1 来连）。起不来就只是没这条入口。
                crossNetworkListener = crossNetworkFileListener,
                crossNetworkFilePort = crossNetworkFilePort(profile.tlsPort),
                // luciola 信道模式：**不创建** 17879 明文监听与 17881 跨网 TLS 监听
                // （它们只是两个可被扫到的端口）。旧协议的入站在这个模式下不存在 ——
                // 发现也已经是 luciola 的，老对端找不到我们，谈不上「回退到 legacy 端口收」。
                legacyTcpListener = !fileChannelEnabled,
            )
        } else {
            null
        }
        transfer = manager
        transferPump = manager?.let { m ->
            runScope.launch { m.events.collect { _transfers.tryEmit(it) } }
        }

        // 跨网屏幕监听（Sharer 侧,`tlsPort + 2`）：进来的 TLS 裸流先读首帧,
        // 是 `screen-open` 且宿主认领（会话/token 校验在 onOpenScreenWire 里）才交接,
        // 否则关掉 —— 与明文监听（TransferServer 首帧分发）同一套纪律。
        // 起不来（平台不支持 / 端口被占）只是**没有跨网入口**,局域网原路径不受影响。
        // 画面信道（luciola）：独立会话，一帧都不走 17879/17882。
        // 与 legacy 监听**互不替代地并存**：开关/协商任一不满足时 legacy 那条仍然是投屏的路。
        val screenHost = screenChannelHost
        val secureScreenPort = screenChannelPort?.takeIf { it in 1..65535 }
        if (fileChannelEnabled && screenHost != null && secureScreenPort != null) {
            runCatching {
                screenHost.startListening { wire ->
                    // 首帧是 screen-open（与两条 legacy 通路同一套解码与 token 校验）。
                    runScope.launch {
                        val open = readFirstScreenOpen(wire.read)
                        if (open == null || !onOpenScreenWire(open, wire)) {
                            runCatching { wire.close() }
                        }
                    }
                }
            }.onFailure { t -> println("[luciola] screen channel listen failed: " + t) }
        }
        // luciola 信道模式下**不创建** 17882（跨网投屏 TLS）。
        //
        // 与 17879/17881 同一条纪律：那三个端口在信道模式下没有存在意义，留着只是多一个
        // 可被扫到的入口。画面走 [screenChannelHost] 的独立加密会话，且它的 opener
        // 已按对端候选地址逐条尝试（见 screenWireAttempts），所以跨网投屏不再依赖 17882。
        val screenPort = crossNetworkScreenPort(profile.tlsPort)
        if (!fileChannelEnabled && screenPort != null && crossNetworkScreenListener.canServe) {
            runCatching {
                crossNetworkScreenListener.start(screenPort) { conn ->
                    val wire = ScreenWire(conn.read, conn.write) { runCatching { conn.close() } }
                    val open = readFirstScreenOpen(conn.read)
                    if (open == null || !onOpenScreenWire(open, wire)) {
                        wire.close()
                    }
                }
            }
        }

        pump = runScope.launch {
            messenger.incoming.collect { envelope ->
                // **不写 messageStore**：投屏信令（screen 的 request/accept/reject/end）、
                // 共享文件柜（share）以及全部 agent-* 扩展帧是会话与机器控制帧，不是普通聊天记录 ——
                // 与撤回帧同一纪律。写进去会让它出现在 /v1/inbox 的增量回放里，对消费方就是一批
                // 没人认领的噪声帧；投屏与 agent 会话机各由专门的存储维护，这里只读 _inbound。
                if (envelope.type != MsgTypes.SCREEN &&
                    envelope.type != MsgTypes.SHARE &&
                    !envelope.type.startsWith("agent-") &&
                    // 身份恢复 L2 是控制帧：合并入库走应用层的消息实体路径，
                    // 写进 envelope 日志只会给 /v1/inbox 添噪声。
                    envelope.type != MsgTypes.RECOVERY_REQ &&
                    envelope.type != MsgTypes.RECOVERY_DATA
                ) {
                    messageStore.append(
                        peerId = envelope.from,
                        direction = DIRECTION_IN,
                        envelopeJson = Codec.encode(envelope),
                        at = nowMillis(),
                    )
                }
                if (envelope.type == MsgTypes.FILE_CTL) transfer?.onEnvelope(envelope)
                if (!_inbound.tryEmit(envelope)) {
                    // 实时订阅者侧的交接挤掉：messageStore 已先落（离线回放不丢），
                    // 但这条帧不会到达本会话的实时订阅者。如实计数暴露，不静默 ——
                    // 「交接挤掉」与「帧没到」在外部同形，诊断字段是唯一区分。
                    handOffDropped.incrementAndGet()
                    println("[inbound] hand-off buffer full, dropped " + envelope.id)
                }
            }
        }
        // 绑 TCP 端口要放在订阅之后：端口一开，桌面端就可能连进来拉文件。
        // 返回值**必须**留下：见 [tcpBound]。丢掉它等于让"端口没拿到"永远没人知道，
        // 而资料里那个 tcpPort 照样会广播出去。
        // 注意：这里判的是**真的绑上了端口**，不是「管理器起来了」。
        // 信道模式（legacyTcpListener = false）下 manager.start() 仍返回 true（数据传输
        // 走 fch1），但 `boundPort` 是 null —— 那种情况不许宣称自己绑着 TCP。
        val started = manager?.start() == true
        tcpBound = started && manager?.boundPort != null
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
        // **socket 起来之后立刻发一轮心跳。**
        //
        // 心跳协程在 `discovery.start` 时就开跑了，而那时 `transport.start()` 还没执行
        // （它在 start() 的最末尾，因为要先绑 TCP、改写 tcpPort）—— 于是第一轮 `send`
        // 全部 `socket == null` 失败，日志是 `[heartbeat] entry targets=5 sent=0`。
        // 之后要等一个 `presenceInterval`（默认 30 秒）才有第二轮：真机上表现为
        // 「节点起来后半分钟别人才看得见」，测试里表现为握手窗口刚好耗光。
        runCatching { sendPresenceHeartbeat() }
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
        runCatching { crossNetworkScreenListener.stop() }
        runCatching { screenChannelHost?.stopListening() }
        screenChannelHost = null
        tcpBound = false
        discovery.stop()
        resolvedExtraDiscovery?.stop()
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
     * （见 [com.kapp.tenon.core.transport.TransferManager.offerFiles]）。
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
    suspend fun announce() {
        discovery.announce()
        resolvedExtraDiscovery?.announce()
        // **自己必须真的发一轮**。与 [announceTo] 同一条教训：原先只转发给发现引擎，
        // 而 `discovery`（SweepOnly）的 announce 是 no-op、`extraDiscovery` 是可选的
        // luciola 信标 —— 换网重播就死在这里：控制面 `onNetworkChanged` 明明调了
        // announce，线上却一个包都不出去，对端要等满一个心跳周期（30s）才重新看到
        // 本机（`RelayControllerNetworkTest` 抓住的真回归）。
        // luciola 世界里「广播」就是定向单播：给全部已知对端与手动地址各发一帧
        // 带全量资料的 entry，与心跳共用同一条口径。
        sendPresenceHeartbeat()
    }

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

    /**
     * 只向一个地址握手 —— 手机端主路径（知道电脑 IP 即可建立双向在线）。
     *
     * luciola 模式下 [discovery] 是 no-op 哑实现，**必须同时转发给 [resolvedExtraDiscovery]**：
     * 定向宣告（扫码 / 填地址 / 按 IP 解析对端）走的是这一条，漏了它，对端信标里的
     * 完整 profile 永远进不了 registry —— 节点表会一直停在 `node-xxxx` 合成名上，
     * 而消息数据面因为按配对密钥直拨仍然畅通，症状极像「只是显示问题」。
     */
    suspend fun announceTo(host: String, port: Int = Protocol.DEFAULT_UDP_PORT) {
        discovery.announceTo(host, port)
        resolvedExtraDiscovery?.announceTo(host, port)
        // **自己必须真的发一条 entry。**
        //
        // 原先这里只转发给两个发现引擎：`discovery` 是 no-op 哑实现，`extraDiscovery` 是
        // luciola 信标引擎 —— 它"拨号"而不是"发 entry"，而且可能根本不存在（测试平台、
        // 还没有原生库的宿主）。结果就是扫码 / 手动填地址之后**一个包都没出去**：
        // 用户看到"正在等回应"，对端那边什么都没发生（本轮 `TwoNode*` 用例集体超时）。
        // 定向告知是协议里最基础的一条，不该依赖可选组件。
        sendEntryTo(host, port)
    }

    /** 造一条带全量资料的 `entry` 帧（心跳与定向宣告共用，两处口径必须一致）。 */
    private fun entryBytes(): ByteArray {
        val self = selfProfile
        return Codec.encodeToBytes(
            makeEnvelope(MsgTypes.ENTRY, self.nodeId, Codec.profilePayload(self)),
        )
    }

    private suspend fun sendEntryTo(host: String, port: Int): Boolean =
        transport.send(entryBytes(), host, port)

    /**
     * 告别：给已知节点与手动地址各发一帧 `exit`，让对端立刻把我们标成离线，
     * 而不是等 presence 超时（`offlineAfter` 90 秒里发过去的消息全部进排队）。
     *
     * 只在**真的要离开**时调（退出进程 / 主动停节点），不要在 [stop] 里无条件发：
     * `restart()` 会先停后起，发了就是一次"离线→在线"的假抖动。
     *
     * 发送面原来住 legacy `Discovery.announceExit`（广播 + 单播）；广播已随 legacy
     * 删除，单播这一半搬到这里 —— 漏掉它，「退出的一端已经走了、对端还当它在线」
     * 的老毛病原样回归（`PresenceExitTest` 钉住这条与空载荷的线格式）。
     */
    suspend fun announceExit() {
        discovery.announceExit()
        resolvedExtraDiscovery?.announceExit()
        // 载荷必须是**空对象**：entry/alive 靠载荷传资料，exit 不传；两端都不读它，
        // 往里塞东西会让两端的线格式悄悄分叉（TS 侧校验就是 isRecord(payload)）。
        val bytes = Codec.encodeToBytes(
            makeEnvelope(MsgTypes.EXIT, profile.nodeId, JsonObject(emptyMap())),
        )
        for (record in registry.values()) {
            if (record.profile.nodeId == profile.nodeId) continue
            if (record.ip.isBlank() || record.udpPort !in 1..65535) continue
            transport.send(bytes, record.ip, record.udpPort)
        }
        for (peer in manualPeers) {
            if (peer.udpPort !in 1..65535 || peer.host.isBlank()) continue
            transport.send(bytes, peer.host, peer.udpPort)
        }
    }

    suspend fun sendText(peerId: String, text: String) = outbound.sendText(peerId, text)

    /** 身份恢复 L2：向对端要历史（ACK 不到 = 旧版或离线，Q5）。 */
    suspend fun sendRecoveryReq(peerId: String): Boolean = outbound.sendRecoveryReq(peerId)

    /** 身份恢复 L2：给恢复方发一批历史（fire-and-forget，不入队）。 */
    suspend fun sendRecoveryData(peerId: String, batch: Int, more: Boolean, messages: List<JsonElement>): Boolean =
        outbound.sendRecoveryData(peerId, batch, more, JsonArray(messages))

    suspend fun sendTextWithEnvelope(peerId: String, text: String) = outbound.sendTextWithEnvelope(peerId, text)

    /**
     * 1:1 带卡片的文本（`dispatchDirectInvoke` 的反馈面）：单聊帧不做字段白名单，
     * 卡片挂在同一帧上即可到达发起方。详见 [NodeOutbound.sendDirectText]。
     */
    suspend fun sendDirectText(
        peerId: String,
        text: String,
        card: JsonObject? = null,
        envelopeId: String? = null,
        senderId: String? = null,
    ) = outbound.sendDirectText(peerId, text, card, envelopeId, senderId)

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
        /** 这次发送用的信封 id（见 `NodeOutbound.sendGroupText`）；不传就生成一个。 */
        envelopeId: String? = null,
        /**
         * 这条消息属于哪个 agent 会话（可选，与 [agentCursor] 成对）。带上它，
         * 接收端才能画出「任务分段线」—— 在此之前那条线只存在于桌面端本地。
         */
        agentSessionId: String? = null,
        /** 会话内游标（可选，与 [agentSessionId] 配对）。 */
        agentCursor: Long? = null,
        /** 会话标题（可选）。分段线在对端显示「任务：xxx」的唯一来源。 */
        agentSessionTitle: String? = null,
        /** 人类 → 插件的单帧原子意图（可选）。见 `NodeOutbound.sendGroupText`。 */
        agentIntent: JsonObject? = null,
        /** 插件 → 全群的执行状态（可选，四态）。 */
        agentState: String? = null,
        /** 声明式卡片负载（可选）。超限在发送侧拒绝（抛出），绝不截断。 */
        card: JsonObject? = null,) =
        // **位置参数**：调用点的实参顺序必须与 `NodeOutbound.sendGroupText` 的形参表
        // 一致（senderId, agentSessionId, agentCursor, agentSessionTitle, agentIntent,
        // agentState, card, envelopeId）—— 本签名里 envelopeId 排在 senderId 之后，
        // 但**实参按出站形参序排列**，两处一旦顺序不同就编译失败。
        // 这里用位置而不是命名，是为了让两处一旦顺序不同就编译失败 —— 命名参数反而
        // 会把这个约束藏起来。
        outbound.sendGroupText(
            text, groupId, groupRev, members, mentions, replyTo, senderId,
            agentSessionId, agentCursor, agentSessionTitle, agentIntent, agentState, card, envelopeId,
        )

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

    /** M4 跨端派单网道（规格 §6.2）：请求帧，点对点、不经群聊。 */
    suspend fun sendAgentInvoke(peerId: String,
        invokeId: String,
        op: String,
        agentId: String,
        requestedBy: String,
        deadlineMs: Long,
        prompt: String? = null,
        actionId: String? = null,
        cardId: String? = null,
        cardRev: Int? = null,
        decidedBy: String? = null,
        sessionId: String? = null,
        groupId: String? = null,
        groupRev: Int? = null,
        wsId: String? = null,
        convId: String? = null,
        at: Long? = null,) = outbound.sendAgentInvoke(
        peerId, invokeId, op, agentId, requestedBy, deadlineMs, prompt, actionId, cardId,
        cardRev, decidedBy, sessionId, groupId, groupRev, wsId, convId = convId, at = at,
    )

    /** `agent-invoke` 的应答帧（cap `agi1`）。 */
    suspend fun sendAgentInvokeResult(peerId: String,
        invokeId: String,
        ok: Boolean,
        status: String,
        summary: String? = null,
        error: String? = null,
        card: JsonObject? = null,
        at: Long? = null,) = outbound.sendAgentInvokeResult(peerId, invokeId, ok, status, summary, error, card, at)

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
        mime: String? = null,
        sha256: String? = null,
        summary: String? = null,): SendOutcome {
        // M7-T3 cap 门控（决策包 §2.2 方案 C）：对端没声明 `agf1` 就**不发**新键 ——
        // 旧端 hasOnlyKeys 会整帧拒收 + 队列重传（静默丢失的「过一会儿才到」形态，永远差一条）。
        val verified = registry.get(peerId)?.profile?.caps?.contains(MsgTypes.CAP_AGENT_ARTIFACT_VERIFIED) == true
        return outbound.sendAgentArtifact(
            peerId, sessionId, artifactId, name, fileId, bytes, mime,
            sha256 = sha256.takeIf { verified },
            summary = summary.takeIf { verified },
        )
    }

    /** 发一条 Channel 委派（M7-T2）。对端 caps 门控在调用方（发起侧编排器，查 `agc1`）。 */
    suspend fun sendAgentChannel(
        peerId: String,
        channelId: String,
        op: String,
        fromAgent: String,
        task: String,
        toAgent: String? = null,
        targetTag: String? = null,
        expectedResult: String? = null,
        resources: List<String>? = null,
        tools: List<String>? = null,
        sourceMessageIds: List<String>? = null,
        deadlineMs: Long? = null,
        sessionId: String? = null,
        groupId: String? = null,
        groupRev: Long? = null,
        at: Long? = null,): SendOutcome = outbound.sendAgentChannel(
        peerId, channelId, op, fromAgent, task, toAgent, targetTag, expectedResult,
        resources, tools, sourceMessageIds, deadlineMs, sessionId, groupId, groupRev, at,
    )

    /** 发一条 Channel 委派应答（M7-T2）——回给刚发来 `agent-channel` 的那个对端。 */
    suspend fun sendAgentChannelResult(
        peerId: String,
        channelId: String,
        ok: Boolean,
        status: String,
        summary: String? = null,
        artifactIds: List<String>? = null,
        error: String? = null,
        at: Long? = null,): SendOutcome = outbound.sendAgentChannelResult(
        peerId, channelId, ok, status, summary, artifactIds, error, at,
    )

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
        pickPeerRecordForHost(registry.values(), host, port, onlineOnly = true)?.let { return it }
        // 只剩离线记录 ⇒ 这台机器的账可能已经换了身份（重装/身份轮换后旧 nodeId 还挂在
        // 同一串 IP 上）。真机现场（验证轮抓到）：按 IP 投文件被解析到一条陈旧 nodeId，
        // offer 发进虚空；正确做法是先向地址要一次**当前**身份，再退回离线记录 ——
        // 排队语义由发送侧的离线队列承接，这里不把"记录离线"当成解析的终点。
        val targetPort = port ?: Protocol.DEFAULT_UDP_PORT
        // 定向握手**必须真的发一帧 entry**。原先这里只调两个发现引擎的 announceTo：
        // `discovery`（SweepOnly）是 no-op、`resolvedExtraDiscovery` 是可选的 luciola 信标 ——
        // 于是一个包都不出去，下面 3 秒窗口里的轮询等一个永远不会来的注册
        // （agentNode 的 `/v1/send`、`/v1/send-file` 全线 peer-unreachable 就是这条）。
        // 与 [announceTo] 同一条修复：定向告知是协议里最基础的一条，不依赖可选组件。
        announceTo(host, targetPort)
        val live = withTimeoutOrNull(timeoutMs) {
            var found: PeerRecord? = null
            while (found == null) {
                found = pickPeerRecordForHost(registry.values(), host, port, onlineOnly = true)
                if (found == null) delay(100L)
            }
            found
        }
        live?.let { return it }
        return pickPeerRecordForHost(registry.values(), host, port, onlineOnly = false)
    }

    fun peerDeclares(peerId: String, cap: String): Boolean =
        getPeer(peerId)?.profile?.caps?.contains(cap) == true

    /**
     * 「重发」：按**原信封 id** 重放一条已发过的消息（用户点了失败气泡上的重发）。
     *
     * 这里只是转发到 [outbound]；两条路（队列里还有原信封 ⇒ 原样重放 / 否则用原 id 重建
     * 并打 `resend=true`）与"为什么不许换新 id"都写在那边的 KDoc 里。
     *
     * 调用方要点：**传原来那条消息的 `id` 与 `peerId`**（`MessageUi.id` / `MessageUi.peerId`），
     * 群聊还要把 `groupId` / `groupRev` / `members` 一起给。新 id 发一条 = 对方收到两条。
     */
    suspend fun replayOutgoing(request: OutgoingReplay): SendOutcome = outbound.replayOutgoing(request)

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

/**
 * 同一串主机地址上挑一条解析目标（[NodeRuntime.resolvePeerByHost] 的选取半，抽纯以便测试）。
 *
 * 排序口径：**在线优先，再按 lastSeen 新到旧** —— "这串 IP 现在是谁"的账，最近听到的那条
 * 最可信。同地址多条记录的来历：设备换了网卡、nodeId 轮换（重装/迁移）、测试机里两个节点
 * 共用一个口。旧实现只按 online 排、并列时落回 registry 插入序 ⇒ 会选中一条**陈旧**
 * 的离线身份（真机现场：按 IP 投文件被解析到重装前的旧 nodeId，offer 发进虚空）。
 *
 * [onlineOnly] = true 时离线记录一律不返回（调用方据此走"先握手再退回"那步）。
 */
internal fun pickPeerRecordForHost(
    records: List<PeerRecord>,
    host: String,
    port: Int?,
    onlineOnly: Boolean,
): PeerRecord? {
    val atHost = records.filter { it.ip == host }
        .sortedWith(compareByDescending<PeerRecord> { it.online }.thenByDescending { it.lastSeen })
    val candidate = if (port == null) atHost.firstOrNull() else atHost.firstOrNull { it.udpPort == port }
    return candidate?.takeIf { !onlineOnly || it.online }
}
