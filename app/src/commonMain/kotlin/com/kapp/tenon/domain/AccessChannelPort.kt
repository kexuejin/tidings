// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EventRouteReasons
import com.kapp.tenon.core.protocol.InboxUnresolved
import com.kapp.tenon.domain.model.PeerUi

/**
 * **接入面**（谁在跟这台机器说话）的宿主只读端口。
 *
 * ## 为什么是端口，而不是直接读状态
 *
 * 「接入与通道」页要的两块事实都不在 `:app` 的切片里：
 *
 * | 事实 | 真源 |
 * |---|---|
 * | 外部桥（`dsh-tenon`）最近取过件没有、注入了几条、投不出去是为什么 | 宿主进程的 `/v1/inbox` 注入台账（`InboxInjectionTrace`） |
 * | 这个节点在本机信任库里有没有 pin | 宿主 `PeerRegistry` |
 *
 * 它们都在另一侧（`agentNode` / `orchestration`），`:app` 不许 import 那边的类型
 * （`domainComposeFreeGate` 同一条边界纪律）。所以只留一个**只读**窄口，由桌面壳注入。
 *
 * ## 手机端为 null ⇒ 整块不画，而不是画个空壳
 *
 * 桥是**电脑上**的常驻进程，手机读不到它的判定台账；给一个 `bridge() == null` 的空壳、
 * 或者更糟「桥在线」的绿灯，都是让界面比事实更满（本仓最贵的一类缺陷）。
 */
interface AccessChannelPort {

    /**
     * 桥的注入判定读数。null = **这台壳读不到**（手机端常态 / 宿主没起）。
     *
     * 注意它与「读到了但是空窗口」是两件事：后者返回 [BridgeChannelUi.isEmpty] 为真的对象，
     * 页面据此说「还没有任何 @ 消息被判定过」，而不是「读不到」。
     */
    fun bridge(): BridgeChannelUi?

    /**
     * 这个节点在本机信任库里的 pin（wire 形态 `sha256:<hex>`）。null = **没有**条目（未配对）。
     *
     * 可读性判据是**这个端口本身在不在**（手机为 null ⇒ 整块不画），不是 [bridge]：
     * pin 表由宿主的 `NodeRuntime` 持有、与传输同寿命，而 [bridge] 读的是控制面的台账，
     * 两者由不同的对象提供 —— 拿 bridge 去判 pin 可读性，会把「控制面没起但信任库读得到」
     * 误当成「读不到」，白白藏掉一条能用的信息。
     */
    fun pinOf(nodeId: String): String? = null

    /**
     * 事件路由的判定计数：`原因 token → 次数`，宿主进程序内累计。null = **读不到**。
     *
     * 与 [bridge] 是**两条独立的通道**，谁也代替不了谁：
     *
     * - [bridge] 管**入站** —— 外部桥把 @ 消息注进一个既有会话；
     * - 这个管**出站** —— 本地会话的事件该投进哪个群。
     *
     * 用户看到的症状几乎一样（「群里什么都没有」），但成因住在两个模块、排障顺序也不同。
     * 合成一个读数会让「桥没跑」与「会话没绑群」互相盖住。
     *
     * 返回**原始计数**，不在这里过滤：哪些原因值得给用户看是展示口径，归投影管
     * （`accessChannelUi`）。端口只负责如实读数。
     */
    fun routingCounts(): Map<String, Long>? = null
}

/** 桥的注入判定读数（UI 形状；与宿主 `InboxInjectionTrace.Snapshot` 逐字段对齐）。 */
data class BridgeChannelUi(
    /** 统计窗口容量（页面「近 N 条」的 N）。 */
    val windowCapacity: Int,
    /** 桥最近一次来取件的时刻；0 = 从未（等价于「桥没在跑」）。 */
    val lastPollAt: Long,
    /** 最近一条「@ 到本机 agent」的判定时刻；0 = 从来没有过。 */
    val lastAddressedAt: Long,
    /** 最近一条**真的能投**的判定时刻；0 = 装上桥之后一次都没成功。 */
    val lastInjectableAt: Long,
    val addressedCount: Int,
    val resolvedCount: Int,
    val unresolvedCount: Int,
    /** 投不出去的成因（稳定 token，见 [InboxUnresolved]），多的排前面。 */
    val causes: List<BridgeCauseUi>,
) {
    /** 窗口里一条判定都没有 —— 页面画空态，不画 `0 / 0 / 0` 这种像读数的东西。 */
    val isEmpty: Boolean get() = addressedCount == 0
}

/** 一条未解析成因。`reason` 是协议 token，文案在资源层查表（查不到就照原样显示）。 */
data class BridgeCauseUi(val reason: String, val count: Int)

/**
 * 桥那块该画哪一档（**入参非空**；「读不到」是 `null`，由类型系统保证调用方必须分开处理）。
 *
 * 三档而不是「空 / 非空」两档：[NeverPolled] 是**故障**（桥没在跑或地址不对），
 * [Empty] 是**正常空态**（桥来取过件，只是还没有任何 @ 消息）。合成一档就会用
 * 「还没收到证据」冒充「收到了坏消息」，而这两句要说的话完全不同。
 *
 * 判据放这里而不是 Composable 里：页面写不出单测，判据住在 Composable 里就只能靠
 * 源码字符串守 —— 格式化或改名即假红。
 */
enum class BridgeBlockState {
    /** 桥一次都没来取过件 —— 故障态（页面用错误色单独成句）。 */
    NeverPolled,

    /** 取过件，但窗口里一条 @ 到本机 agent 的消息都没有 —— 正常空态。 */
    Empty,

    /** 有判定：给统计与成因。 */
    Judged,
}

/** @see BridgeBlockState */
fun bridgeBlockStateOf(bridge: BridgeChannelUi): BridgeBlockState = when {
    // `lastPollAt <= 0` 就是「从未取件」。用 `<=` 而不是 `==`：时钟回拨时宁可说
    // 「没取过件」，也不能画一个负数时刻的相对时间（那会显示成「很久以后」）。
    bridge.lastPollAt <= 0L -> BridgeBlockState.NeverPolled
    bridge.isEmpty -> BridgeBlockState.Empty
    else -> BridgeBlockState.Judged
}

/**
 * 一个节点的配对三态。
 *
 * [Unknown] **只能来自「读不到信任库」**，不能来自「没配过」—— 后者是 [Unpaired]。
 * 手机壳的信任库在桌面宿主上（`AccessChannelPort` 为 null），它说「未配对」就是给界面
 * 装了一盏反向的假红灯：用户会去查一台根本没配错的机器。
 */
enum class PairingState {
    /** 信任库里有这个节点的 pin（[pinPrefix] 非空）。 */
    Paired,

    /** 读得到信任库，里面确实没有它。 */
    Unpaired,

    /** 读不到信任库 —— 不下结论，页面整段不画。 */
    Unknown,
}

/**
 * 配对三态。
 *
 * @param pinPrefix [AccessNodeRowUi.pinPrefix]：已投影过的短指纹，null = 信任库里没有。
 * @param trustStoreReadable 这一端读得到信任库吗（端口为 null ⇒ false）。
 */
fun pairingStateOf(pinPrefix: String?, trustStoreReadable: Boolean): PairingState = when {
    // pin 本身就是「已配对」的证据：它只可能是从信任库读出来的，此时 `trustStoreReadable`
    // 必然为 true。先判它，是为了让「读得到但没配过」与「配了」不会因为参数顺序而颠倒。
    pinPrefix != null -> PairingState.Paired
    trustStoreReadable -> PairingState.Unpaired
    else -> PairingState.Unknown
}

/**
 * 事件路由的读数（UI 形状）。
 *
 * `causes` 只含「没进群」的成因（[EventRouteReasons.BLOCKING]）：`dispatch` 与两条
 * `duplicate-*` 是正常路径，混进来只会把真问题淹掉。
 */
data class RoutingChannelUi(
    /** 按「次数降序 → 词表次序」排；同一原因恒定一行（聚合键是稳定 token，不带参数）。 */
    val causes: List<RoutingCauseUi>,
) {
    /**
     * 读到了、但一条阻断都没有 —— 这是个**好消息**（事件都找到了去处）。
     *
     * 与 `routing == null`（读不到，手机端常态）必须分开：前者说「没有事件被拦下」，
     * 后者只能闭嘴。把「没有证据」画成「一切正常」是本仓最贵的那类缺陷。
     */
    val isEmpty: Boolean get() = causes.isEmpty()
}

/** 一条路由成因。`count` 用 Long：宿主侧是进程内累计，长时间运行不会溢出到负数。 */
data class RoutingCauseUi(val reason: String, val count: Long)

/** 接入与通道页的一行节点。 */
data class AccessNodeRowUi(
    val nodeId: String,
    val displayName: String,
    /** `ip:port`；空串 = 不知道地址（还没握手过）。 */
    val address: String,
    val online: Boolean,
    /** 最近一次收到这个节点的帧；0 = 从来没收到过。 */
    val lastSeenAt: Long,
    /**
     * 本机信任库里这个节点的 pin 前缀（`AB:CD:EF:01` 形态）；null = **没有**这条。
     *
     * 为什么值得单独一列：两侧信任库为空时，presence 通、链路也通，但**每一帧都被
     * `send refused (no session / unpaired)` 拒掉** —— 现象是「消息发出去了、对面没反应」。
     * 页面上看得见「未配对」，比日志里翻 `skipReason=no-pin` 便宜得多。
     */
    val pinPrefix: String?,
)

/** 整页的投影。 */
data class AccessChannelUi(
    val selfNick: String,
    val selfNodeId: String,
    /** 本机监听端口（`RelaySettings.udpPort`，luciola 传输端口）。 */
    val listeningPort: Int,
    /** 除自己以外的节点，在线优先、最近见到的在前。 */
    val nodes: List<AccessNodeRowUi>,
    /** null = 这台壳读不到桥（手机），或宿主没起控制面。 */
    val bridge: BridgeChannelUi?,
    /**
     * 这一页上显示为**已配对**的节点数（只数 [nodes] 里这些，不是信任库的总条数）；读不到时为
     * null，页面不画这一句。
     *
     * 为什么是「可见节点里的」而不是「信任库总条数」：信任库在 `:app` 这一层只以
     * `nodeId → pin` 的单点查询形式暴露，没有枚举能力；拿一个节点列表去冒充「总条数」，
     * 在「配过 3 台、今天只开 1 台」时就变成低报 —— 而文案会把低报说成「信任库里有 1 台」。
     */
    val pairedCount: Int?,
    /** null = 这台壳读不到路由计数（手机），或宿主没起事件转发。 */
    val routing: RoutingChannelUi? = null,
)

/**
 * 整页投影（纯函数，可单测）。
 *
 * `now` 不参与计算：页面的「X 前」用 `relativeTime(at, nowMillis())` 在渲染时算，
 * 传进来只会让「同一份状态两处算时间」。
 *
 * @param pins nodeId → pin；端口为 null 时传 null（此时 [AccessChannelUi.pairedCount] 也是 null）
 */
fun accessChannelUi(
    selfNodeId: String,
    selfNick: String,
    listeningPort: Int,
    peers: List<PeerUi>,
    bridge: BridgeChannelUi?,
    pins: ((String) -> String?)?,
    /** 端口 [AccessChannelPort.routingCounts] 的原样读数；null = 读不到。 */
    routingCounts: Map<String, Long>? = null,
): AccessChannelUi {
    val rows = peers
        .filter { it.nodeId != selfNodeId }
        .map { peer ->
            AccessNodeRowUi(
                nodeId = peer.nodeId,
                displayName = peer.displayName,
                address = if (peer.ip.isBlank()) "" else peer.ip + ":" + peer.udpPort,
                online = peer.online,
                lastSeenAt = peer.lastSeenAt,
                pinPrefix = pins?.invoke(peer.nodeId)?.let(::pinPrefixOf),
            )
        }
        // 在线优先；同档按最近见到排（0 = 从没见过排最后）。**不用状态里的顺序**：
        // 那是发现顺序，重启一次就变，用户会以为节点被重排了。
        .sortedWith(
            compareByDescending<AccessNodeRowUi> { it.online }
                .thenByDescending { it.lastSeenAt }
                .thenBy { it.displayName },
        )
    return AccessChannelUi(
        selfNick = selfNick,
        selfNodeId = selfNodeId,
        listeningPort = listeningPort,
        nodes = rows,
        bridge = bridge,
        // **与行上的判定同源**：数「这一页上显示为已配对的那几行」。
        // 用 `isNotBlank` 判会让一条形态不认识（不是十六进制）的 pin 在行上说「未配对」、
        // 在合计里说「已配对」—— 同一页两处口径不一致，用户只会信那个更乐观的。
        pairedCount = pins?.let { rows.count { row -> row.pinPrefix != null } },
        routing = routingUiOf(routingCounts),
    )
}

/**
 * 原始计数 → 页面要显示的那几行。
 *
 * 口径是**排除正常路径**而不是白名单取 BLOCKING：`EventRouteReasons` 以后加一档新成因时，
 * 页面会**自动**把它显示出来（排最后、文案认不出就原样显示），不需要改这里。
 * 白名单会静默丢掉新 token，而看不见的那一档正是「宿主比界面新」的证据。
 */
private fun routingUiOf(counts: Map<String, Long>?): RoutingChannelUi? {
    if (counts == null) return null
    val blocking = EventRouteReasons.BLOCKING
    val normal = EventRouteReasons.NORMAL
    val rows = counts.entries
        .filter { it.key.isNotBlank() && it.value > 0L && it.key !in normal }
        .map { RoutingCauseUi(reason = it.key, count = it.value) }
        // 次数降序（多的先看到）→ 词表次序（同次数时重要的在前）→ token（保证稳定）
        .sortedWith(
            compareByDescending<RoutingCauseUi> { it.count }
                .thenBy { row -> blocking.indexOf(row.reason).let { if (it < 0) blocking.size else it } }
                .thenBy { it.reason },
        )
    return RoutingChannelUi(causes = rows)
}

/**
 * pin（wire 形态 `sha256:<hex>`）→ 人看得懂的短指纹 `AB:CD:EF:01`。
 *
 * 返回 null = 这个值不是本仓认得的 pin 形态（空 / 前缀不对 / 不是十六进制）——
 * **不猜、不截一段乱码糊上去**：错的指纹比没有指纹更坏（用户会拿它去比对）。
 */
fun pinPrefixOf(pin: String?): String? {
    val raw = pin?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty()) return null
    // pin 的 wire 形态带 `sha256:` 前缀；也容忍只有十六进制的存量写法（不猜内容，只认长度与字符集）。
    val hex = raw.removePrefix(PIN_PREFIX)
    if (hex.length < PIN_PREFIX_BYTES * 2) return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return (0 until PIN_PREFIX_BYTES)
        .map { i -> hex.substring(i * 2, i * 2 + 2).uppercase() }
        .joinToString(":")
}

/** 本仓 pin 的 wire 前缀（`PeerRegistry` 落盘与二维码里都是这一份）。 */
private const val PIN_PREFIX: String = "sha256:"

/** 指纹显示几位字节（4 字节 = `AB:CD:EF:01`，够区分而不用挤满一行）。 */
private const val PIN_PREFIX_BYTES: Int = 4
