// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.nowMillis
import app.relay.core.protocol.Codec
import app.relay.core.protocol.DecodeResult
import app.relay.core.protocol.DEFAULT_TIMINGS
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.Protocol
import app.relay.core.protocol.Timings
import app.relay.core.protocol.makeEnvelope
import app.relay.core.protocol.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * 手动节点：跨网段 / VPN（Tailscale、ZeroTier）/ 本机联调时直填 ip:port。
 *
 * **`port` 是 UDP 口，不是 TCP 口。** 发现层全程只走 UDP（entry / alive / presence / exit），
 * 这个 port 最终落到 `transport.send(bytes, host, port)`，也就是 `DatagramSocket.send`。
 *
 * 写成 TCP 口不会有任何报错：桌面茶话间是 `TCP 17879` + `UDP 17878` 两个不同的监听，
 * 往只开了 TCP 的 17879 发 UDP，内核回一个 ICMP port unreachable，而未 connect 的
 * `DatagramSocket` 收下这个错误后只是让**下一次** receive 失败 —— 发送侧毫无感觉，
 * 接收侧根本没人在 UDP 上听，于是**对端一声不吭**，与"网络不通"完全无法区分。
 * 本机联调最容易踩：顺手把旁边那个正在 LISTEN 的 TCP 端口填进来。
 */
data class ManualPeer(val host: String, val udpPort: Int)

/**
 * 发现层：entry / alive / exit / presence。
 *
 * 对齐 TS `src/main/net/discovery.ts`。P0/P1 只实现真正需要的那部分：
 * 主动 entry、对 entry 回 alive（带抖动与去重）、收 alive、周期 presence、定期 sweep、收 exit、
 * 收 `profile` 资料变更、以及**资料版本失配时用定向 entry 换回全量资料**（见 [handlePresence]）。
 *
 * 仍然不做的两项（理由写在 [MsgTypes.UNHANDLED] 的 KDoc 里，并且 `onDatagram` 里有显式分支，
 * 不让它们悄悄掉出 `when`）：gossip 摘要 `peers` 与网段分享 `scan-ranges` ——
 * 手机端是叶子节点，不做节点表散播、也不扫描网段。
 *
 * **手机端不做扫描**（不逐个 IP 探活），但**会广播** —— 广播是"让对方主动发现你"的唯一路径。
 *
 * 这里曾经写成"手机端不发广播，只向已配置节点单播"，后果是真机可见的：只要用户在
 * 「发现」页手动加过任何一个节点，本机就再也不广播，同网段其他节点再也发现不了它；
 * 而它自己也会在 `offlineAfter` 之后，把那些**没手动配过、只是被动握过手**的节点判成离线，
 * 于是回复消息全部进排队。真机复现并定位（见 docs/adr/ADR-008）。
 *
 * 修复后的发送侧与上游 TS `src/main/net/discovery.ts` 一致：**广播与单播是并列的，不是二选一**。
 * 省掉的只是逐 IP 扫描；Android 的 `CHANGE_WIFI_MULTICAST_STATE` 仍未声明（发送广播不需要它，
 * 接收广播在省电模式下会被过滤 —— 这条留待 mDNS 自动发现 P4 落地时一并验证）。
 */
class Discovery(
    private val transport: UdpTransport,
    private val registry: PeerRegistry,
    /**
     * 本节点身份。是函数不是值：`NodeRuntime.start()` 会在绑定失败后**改写**自己对外
     * 声明的 `tcpPort`（见 `NodeRuntime.tcpBound`），而 Discovery 必须广播改写后的那一份
     * —— 广播一个自己没拿到的端口，对端会照着连，连上的是别人。
     */
    private val selfProfile: () -> Profile,
    private val timings: Timings = DEFAULT_TIMINGS,
    private val manualPeers: List<ManualPeer> = emptyList(),
    private val broadcastHost: String = "255.255.255.255",
    private val broadcastPort: Int = Protocol.DEFAULT_UDP_PORT,
    /**
     * 各网卡自己的子网广播地址，由平台层提供；空 = 只发受限广播。
     *
     * 受限广播在 TUN 环境下会被代理的更具体路由（`0.0.0.0/1`）接走，而子网广播有
     * 直连路由、抢不走 —— 详见 [broadcastTargets] 的 `subnetBroadcasts` 参数说明。
     */
    private val subnetBroadcasts: () -> List<String> = { emptyList() },
    /**
     * 发现层认得、但要交给**应用层**处理的 gossip 帧（目前只有 `scan-ranges`）。
     *
     * 必须在这里投递：`scan-ranges` 不在 [MsgTypes.RELIABLE] 里，所以
     * `Messenger.onDatagram`（只投递可靠帧与 ACK）不会收它；发现层再把它当"有意不处理"
     * 丢掉的话，`RelayControllerMessages` 的 `handleOnScanRangesReceived` 就是一段
     * **永远不可达**的代码 —— 对端分享过来的网段永远不会被吸收，也不会再回发。
     * 的真机读数正是如此：夹具发了 `10.99.195.0/24`，手机落盘的
     * `settings.json` 里始终只有它自己手动加的那一条。
     */
    private val onGossip: (suspend (Envelope) -> Unit)? = null,
    /**
     * 对端资料里带了它的 TLS pin 时的去处（task-38）—— 主机据此**在配对时就固定**正确的 pin，
     * 不再依赖 TOFU 的首次固定（那个有首次使用竞态）。
     */
    private val onPeerPin: (String, String) -> Unit = { _, _ -> },
) {

    private var scope: CoroutineScope? = null
    private val jobs = mutableListOf<Job>()
    private val lastAliveReplyAt = HashMap<String, Long>()
    private var presenceSeq = 0

    /**
     * 上次因"资料版本失配"给某节点补发 entry 的时刻（见 [handlePresence]）。
     *
     * 与 `lastAliveReplyAt` 分开记：那个管的是"回 alive 的去重窗口"，这个是"主动问资料"，
     * 两件事的节流口径不同（前者防开机风暴，后者防版本号横跳）。
     */
    private val lastProfileRefreshAt = HashMap<String, Long>()

    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        // 每条报文单独兜底：一条坏帧（或应用层的 gossip 处理器抛异常）绝不能把整个发现层
        // 的收包协程带走 —— 那会让本机从此收不到任何 presence/entry/alive，表现就是
        // 「换了网之后永远不自愈」（task-28 真机的症状之一）。
        jobs += scope.launch { transport.incoming.collect { runCatching { onDatagram(it) } } }
        jobs += scope.launch { presenceLoop() }
        jobs += scope.launch { sweepLoop() }
    }

    fun stop() {
        for (job in jobs) job.cancel()
        jobs.clear()
        scope = null
    }

    /**
     * 主动握手：广播 entry **并且**给每个手动节点、以及**已经学到过的在线节点**单播一份
     * （对齐 TS `discovery.ts:120-123` 的 `start()`）。三者是并列的：配了手动节点不代表
     * 网段里没有别的节点。
     *
     * 第三项（`registry.values()`）是换网段时补上的：广播是受限广播（`255.255.255.255`），
     * 只在**新**网段里有效；而"学到过但没手动配过"的节点（比如 agentNode 跑在 17891，
     * 不在广播端口 17878 上）永远收不到广播。少了这一项，换网之后这类节点要等下一次
     * presence（最多 `presenceInterval` = 30 秒）才知道我的新地址 —— 和
     * "心跳漏掉被动握手的节点"是同一条规则。
     */
    suspend fun announce() {
        val envelope = makeEnvelope(
            MsgTypes.ENTRY,
            selfProfile().nodeId,
            Codec.profilePayload(selfProfile()),
        )
        val bytes = Codec.encodeToBytes(envelope)
        for ((host, port) in broadcastTargets(
            broadcastHost = broadcastHost,
            broadcastPort = broadcastPort,
            manualPeers = manualPeers,
            knownPeers = registry.values(),
            // 候选端点也要发：手机只留移动网时，它给桌面发 presence 必须能到达桌面的**公网**
            // 地址，否则桌面永远学不到手机的运营商地址（task-20 那条死锁）。
            candidateEndpoints = candidateTargets(),
            subnetBroadcasts = subnetBroadcasts(),
        )) {
            sendRaw(bytes, host, port)
        }
    }

    /** 只向一个节点握手（手机端的主路径：知道电脑 IP 即可建立双向在线）。 */
    suspend fun announceTo(host: String, port: Int = Protocol.DEFAULT_UDP_PORT) {
        val envelope = makeEnvelope(
            MsgTypes.ENTRY,
            selfProfile().nodeId,
            Codec.profilePayload(selfProfile()),
        )
        sendRaw(Codec.encodeToBytes(envelope), host, port)
    }

    /**
     * 告别：广播一条 `exit`，对端收到就把我们标成离线（[onDatagram] 的 `MsgTypes.EXIT`
     * 分支走 `registry.markOffline`），不必等 `Timings.offlineAfter`（90 秒）的 presence 超时。
     *
     * 载荷是**空对象**，这是刻意的：`entry`/`alive` 靠载荷里的 profile 传递资料，而 `exit`
     * 只是一个告别 —— 两端都不读它的载荷（我们的接收端只用信封的 `from`；TS 侧对它的校验
     * 就是 `isRecord(payload)`）。
     *
     * 与 [announce] 用同一组目标：广播 + 手动节点 + 学到过的在线节点。少任何一类，
     * 那一类对端就要等满 presence 超时才知道我们走了。
     */
    suspend fun announceExit() {
        val envelope = makeEnvelope(MsgTypes.EXIT, selfProfile().nodeId, JsonObject(emptyMap()))
        val bytes = Codec.encodeToBytes(envelope)
        for ((host, port) in broadcastTargets(
            broadcastHost = broadcastHost,
            broadcastPort = broadcastPort,
            manualPeers = manualPeers,
            knownPeers = registry.values(),
            // 候选端点也要发：手机只留移动网时，它给桌面发 presence 必须能到达桌面的**公网**
            // 地址，否则桌面永远学不到手机的运营商地址（task-20 那条死锁）。
            candidateEndpoints = candidateTargets(),
            subnetBroadcasts = subnetBroadcasts(),
        )) {
            sendRaw(bytes, host, port)
        }
    }

    /** @return false = 这个包没交出去（见 UdpTransport.send）。 */
    private suspend fun sendRaw(bytes: ByteArray, host: String, port: Int): Boolean =
        transport.send(bytes, host, port)

    /**
     * 周期 presence 的收件人：广播地址 + 手动节点 + **registry 里在线的节点**
     * （对齐 TS `discovery.ts:229-237` 的 `sendPresence()`）。
     *
     * 第三项是这次真机缺陷的修复点：对方主动来握手、而我没手动加过它时，它只存在于
     * registry 里 —— 这条单播是它唯一能收到的心跳。漏掉它 → `offlineAfter`（90 秒）后
     * 被我方判离线 → 发给它的消息全部进排队而不是发出去。
     */
    private suspend fun presenceLoop() {
        // 连续几轮一个包都没交出去 ⇒ 判定发现泵已停摆（socket 被关/坏），重建 transport。
        var deadRounds = 0
        while (true) {
            delay(timings.presenceInterval)
            val sent = presenceRound()
            deadRounds = if (sent > 0) 0 else deadRounds + 1
            if (deadRounds >= STALLED_ROUNDS_BEFORE_RESTART) {
                deadRounds = 0
                runCatching { transport.restart() }
            }
        }
    }

    /**
     * 一个心跳周期：发 presence（广播 + 手动 + 在线节点 + 候选），再给候选补一帧 entry。
     *
     * @return 这一轮**真正交出去**的包数。0 = socket 已经发不出任何东西（task-29 的静默停摆）。
     */
    private suspend fun presenceRound(): Int {
        val envelope = makeEnvelope(
            MsgTypes.PRESENCE,
            selfProfile().nodeId,
            Codec.presencePayload(presenceSeq++, selfProfile().profileRev),
        )
        val bytes = Codec.encodeToBytes(envelope)
        val candidates = candidateTargets()
        var sent = 0
        for ((host, port) in broadcastTargets(
                broadcastHost = broadcastHost,
                broadcastPort = broadcastPort,
                manualPeers = manualPeers,
                knownPeers = registry.values(),
                // 候选端点也要发：手机只留移动网时，它给桌面发 presence 必须能到达桌面的**公网**
                // 地址，否则桌面永远学不到手机的运营商地址（task-20 那条死锁）。
            candidateEndpoints = candidates,
        )) {
            if (sendRaw(bytes, host, port)) sent++
        }
            // 候选地址上再补一帧 **entry**（带全量资料）。
            //
            // 为什么不能只发 presence：presence 不带 profile，收端在「已在线但源地址变了」或
            // 「离线且没有资料」这两种情况下都会拒绝它（PeerRegistry 的漂移规则），只能靠收端
            // 反向发 entry、我们再回 alive 来刷新 —— 而那条**反向 UDP** 在运营商侧可能被过滤。
            // task-21 真机就是这一档：候选生效、ip 学到了，但 online 一直是 False、队列不动。
            // entry 带全量资料，收端用 confirmed = true **直接采纳**新地址（连 online 一起翻回来），
            // 不依赖任何反向路径。
        if (candidates.isNotEmpty()) {
            val entry = makeEnvelope(
                MsgTypes.ENTRY,
                selfProfile().nodeId,
                Codec.profilePayload(selfProfile()),
            )
            val entryBytes = Codec.encodeToBytes(entry)
            for ((host, port) in candidates) if (sendRaw(entryBytes, host, port)) sent++
        }
        return sent
    }

    private suspend fun sweepLoop() {
        while (true) {
            delay(timings.sweepInterval)
            registry.sweep(timings.offlineAfter)
        }
    }

    private suspend fun onDatagram(datagram: ReceivedDatagram) {
        val decoded = Codec.decode(datagram.bytes)
        if (decoded !is DecodeResult.Ok) return
        val envelope = decoded.env
        if (envelope.from == selfProfile().nodeId) return
        // 任何一条入站报文都在告诉我们「它现在从这个地址可达」——收进候选池。
        // 不改当前地址（漂移规则仍由 touch 把关），只让出站重试多一条能打的路（task-28）。
        registry.rememberSource(envelope.from, datagram.host, datagram.port)
        when (envelope.type) {
            MsgTypes.ENTRY -> handleEntry(envelope, datagram)
            MsgTypes.ALIVE -> handleAlive(envelope, datagram)
            MsgTypes.PROFILE -> handleProfile(envelope, datagram)
            MsgTypes.EXIT -> registry.markOffline(envelope.from)
            MsgTypes.PRESENCE -> handlePresence(envelope, datagram)
            // `peers` 是"认得、有意不处理"（理由见 `MsgTypes.UNHANDLED`，我们不做节点表散播）。
            // `scan-ranges` **不是**这一类：应用层有处理它的代码（`handleOnScanRangesReceived`），
            // 只是它不在可靠集里、Messenger 那条路收不到，所以必须从这里投递上去。
            MsgTypes.PEERS -> Unit
            MsgTypes.SCAN_RANGES -> onGossip?.invoke(envelope)
        }
    }

    /**
     * 资料里带了 `tlsPin` 就交给上层固定（task-38）。
     *
     * 形状不对（`TlsPin.parse` 不认）就当**没有**这个键：坏值绝不静默变成"信任的 pin"。
     */
    private fun learnPin(profile: Profile) {
        val raw = profile.tlsPin ?: return
        if (app.relay.core.crypto.TlsPin.parse(raw) == null) return
        onPeerPin(profile.nodeId, raw)
    }

    /**
     * **把对端档案里声明的全部地址学进候选池。**
     *
     * 候选地址原先只有两个来源：配对码给的那一池、以及「刚从这个地址收到过报文」
     * （`PeerRegistry.rememberSource`）。两者都要求**先有一条能通的路径**。
     * 而对端换成 VPN（Tailscale / ZeroTier）之后，广播不跨网段，那条路径就不存在了：
     *
     * 1. 局域网里互通，双方各自记下对方的局域网地址；
     * 2. 一方切到 VPN，旧地址不可达；
     * 3. 广播到不了 VPN 段，双方都收不到对方的包；
     * 4. `rememberSource` 也触发不了（它要收到包才记）。
     *
     * 死锁。真机现场：红米半小时零收包，而本机 ping 它的 Tailscale 地址几秒就通 ——
     * 网络通，是协议不知道怎么走。
     *
     * 档案里带上全部地址后，**任意一条通路收到过一次 profile** 就能把对端的所有地址
     * 灌进候选池；出站重试按序尝试，VPN 那条自然能命中。不依赖广播跨网。
     *
     * 端口用报文自己的 `datagram.port` —— 与 `rememberSource` 同一口径。
     */
    private fun learnAddresses(profile: Profile, datagram: ReceivedDatagram) {
        val declared = profile.addresses.orEmpty()
        if (declared.isEmpty()) return
        for (host in declared) {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) continue
            registry.rememberSource(profile.nodeId, trimmed, datagram.port)
        }
    }

    private suspend fun handleEntry(envelope: Envelope, datagram: ReceivedDatagram) {
        val profile = profileOf(envelope) ?: return
        learnPin(profile)
        learnAddresses(profile, datagram)
        registry.touch(
            envelope.from,
            datagram.host,
            datagram.port,
            profile,
            envelope.ts,
            confirmed = true,
        )
        scheduleAliveReply(envelope.from, datagram.host, datagram.port, probeIdOf(envelope))
    }

    private suspend fun handleAlive(envelope: Envelope, datagram: ReceivedDatagram) {
        val profile = profileOf(envelope) ?: return
        learnPin(profile)
        learnAddresses(profile, datagram)
        registry.touch(envelope.from, datagram.host, datagram.port, profile, envelope.ts, confirmed = true)
    }

    /**
     * 资料变更帧（协议 §6.1 表：`profile` = 昵称/公司/团队/头像变更的广播或单播）。
     *
     * 与 [handleAlive] 走同一条路，但**不传 `confirmed`**：这帧是对方主动上门、不是我们
     * 定向握手换回来的，所以要不要采纳交给 `PeerRegistry` 的新旧判据（`profileRev` 更大
     * 就收，相同版本才需要确认）。这样既能让"改了昵称"立刻生效，又不会因为一次乱序或
     * 伪造的 UDP 包把资料钉回旧版本 —— 后者正是 `profileConfirmationOnly` 那道封印在防的事。
     */
    private suspend fun handleProfile(envelope: Envelope, datagram: ReceivedDatagram) {
        val profile = profileOf(envelope) ?: return
        learnPin(profile)
        learnAddresses(profile, datagram)
        registry.touch(envelope.from, datagram.host, datagram.port, profile, envelope.ts)
    }

    /**
     * 心跳：续期，并在**资料版本对不上**时用一次定向 `entry` 把对方的最新资料换回来。
     *
     * 协议 §6.1：「`presence` 携带 `profileRev`：收端发现与本地缓存版本不一致 → 单播 `entry`，
     * 对方按 §6.1 回 `alive`（带全量资料）即完成刷新。零新增报文类型。」这一条是**必须的**：
     * 心跳本身不带资料，没有这次刷新，对端改了昵称/端口/能力之后，本机就会永远停在旧的那份上
     * 并按旧端口去连 —— 机器没换、用的人换了，正是它要修的那种漂移（需求 F-DISC-7）。
     *
     * **"从没见过它的资料"（`profileRevOf` 返回 -1）也算对不上，同样要问。** 这条是真机验出来的：
     * 被真机抓出来的：本机重启后节点表是空的（它是内存表），而对方**不会再发
     * `entry`** —— 它只在启动/换网段时握手一次，之后每个周期只发 `presence`。于是"等它的 entry"
     * 等于永远等不到：会话列表里那个人的名字一直停在 nodeId 前 8 位，直到对方重启。
     * 协议 §6.2 的原话是「与本地缓存版本不一致 → 单播 `entry`」，"本地没有缓存"正是不一致。
     *
     * **源地址漂移也算「对不上」。** 这条是跨网真机验出来的：手机在「局域网 IPv6 ↔
     * 运营商 IPv6」之间切换时，`PeerRegistry.touch` 会拒绝在线节点的地址漂移，而心跳
     * 又不带资料 ⇒ 旧代码里 `knownRev == theirRev` 直接 return，本机永远停在旧地址上、
     * 发给它的消息全进队列。现在只要 `touch` 没采纳（地址漂移/未知节点）就问一次资料。
     *
     * 唯一的克制：同一节点两次刷新至少隔 [PROFILE_REFRESH_MIN_INTERVAL_MS]（每节点
     * 10 秒），否则一个版本号乱跳的对端能让本机每个心跳周期都发一次 entry。收件地址取的是
     * **数据报的源地址**而不是载荷里的字段，所以不存在被伪造成反射放大器的问题。
     */
    private suspend fun handlePresence(envelope: Envelope, datagram: ReceivedDatagram) {
        // touch 返回 null 有两种「我们其实认得它、但它换了源地址」的情况：
        //  · 它已经在线、源地址变了（PeerRegistry 拒绝在线节点漂移）；
        //  · 它离线后换了地址，而 presence **不带资料**（换地址必须带完整资料）。
        // 两种都必须**主动要一份资料**：定向 entry → 对方回 alive（confirmed = true）→ 新地址被采纳。
        // 不这么做的话，节点在「局域网 IPv6 ↔ 运营商 IPv6」之间切换时，本机永远停在旧地址上，
        // 发给它的消息只会进队列 —— 真机现场：网络层双向可达（nc rc=0）、/v1/peers 甚至出现过
        // 运营商地址，queue.log 却从 15 涨到 18，切回 WiFi 才清空。
        val accepted = registry.touch(envelope.from, datagram.host, datagram.port, null, envelope.ts)
        val theirRev = profileRevOf(envelope) ?: return
        val knownRev = registry.profileRevOf(envelope.from)
        // 版本一致、且源地址也被采纳：这条心跳没有新信息。
        if (knownRev == theirRev && accepted != null) return
        val now = nowMillis()
        val last = lastProfileRefreshAt[envelope.from]
        if (last != null && now - last < PROFILE_REFRESH_MIN_INTERVAL_MS) return
        lastProfileRefreshAt[envelope.from] = now
        val entry = makeEnvelope(
            MsgTypes.ENTRY,
            selfProfile().nodeId,
            Codec.profilePayload(selfProfile(), newId()),
        )
        sendRaw(Codec.encodeToBytes(entry), datagram.host, datagram.port)
    }

    /**
     * 回 alive。两条路径的区别是刻意的（TS `scheduleAliveReply`）：
     * - 定向 probe（带 probeId）→ 固定 `directedReplyInterval` 后回，不再去重；
     * - 普通 entry → 抖动窗口内随机延迟，且同一节点在 `aliveDedupWindow` 内只回一次
     *   （防开机风暴时整网同时应答）。
     */
    private fun scheduleAliveReply(nodeId: String, host: String, port: Int, probeId: String?) {
        val now = nowMillis()
        val directed = probeId != null
        if (!directed) {
            val last = lastAliveReplyAt[nodeId]
            if (last != null && now - last < timings.aliveDedupWindow) return
            lastAliveReplyAt[nodeId] = now
        }
        val delayMs = if (directed) {
            timings.directedReplyInterval
        } else {
            val window = minOf(
                timings.entryReplyJitterBase + (registry.onlineCount() / 100) * 1_000L,
                timings.entryReplyJitterMax,
            )
            Random.nextLong(0L, maxOf(window, 1L))
        }
        val active = scope ?: return
        active.launch {
            delay(delayMs)
            val alive = makeEnvelope(
                MsgTypes.ALIVE,
                selfProfile().nodeId,
                Codec.profilePayload(selfProfile(), probeId),
            )
            sendRaw(Codec.encodeToBytes(alive), host, port)
            lastAliveReplyAt[nodeId] = nowMillis()
        }
    }

    /** 全部已存节点的候选端点，摊平成 (host, port) 列表。 */
    private fun candidateTargets(): List<Pair<String, Int>> =
        registry.allCandidates().values.flatten().map { it.host to it.port }

    private fun profileOf(envelope: Envelope): Profile? {
        val payload = envelope.payload as? JsonObject ?: return null
        val element = payload["profile"] ?: return null
        return runCatching {
            Codec.wireJson.decodeFromJsonElement(Profile.serializer(), element)
        }.getOrNull()
    }

    private fun probeIdOf(envelope: Envelope): String? {
        val payload = envelope.payload as? JsonObject ?: return null
        val primitive = payload["probeId"] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    /** `presence` 载荷里的 `profileRev`（协议 §6.1：心跳只带版本号，不带资料本身）。 */
    private fun profileRevOf(envelope: Envelope): Int? {
        val payload = envelope.payload as? JsonObject ?: return null
        val primitive = payload["profileRev"] as? JsonPrimitive ?: return null
        return primitive.content.toIntOrNull()
    }

    private companion object {
        /**
         * 同一节点两次"资料版本失配 → 补一次 entry"之间的最短间隔。
         *
         * 协议定的是每节点 10 秒。没有它，一个 profileRev 反复横跳（或时钟回拨）的
         * 对端能让本机每个心跳周期都发一次 entry —— 那是自己给自己造广播风暴。
         */
        const val PROFILE_REFRESH_MIN_INTERVAL_MS = 10_000L

        /**
         * 连续几轮"一个包都没发出去"就把 transport 重建一次。
         *
         * 2 轮 = 2 × presenceInterval（默认 60 秒）：一轮里连广播都发不出去，只可能是
         * socket 已经不可用（UDP 发送失败是内核层面的，不存在"网络不好所以没交出去"）。
         * 取 2 而不是 1，是为了不在一次瞬时错误上就重建连接。
         */
        const val STALLED_ROUNDS_BEFORE_RESTART = 2
    }
}

/**
 * 一次播报要把同一份字节发给哪些 `host:port`。
 *
 * 抽成顶层纯函数是刻意的：这里曾经出过一个真机可见的缺陷 —— 广播与单播被写成
 * `if (manualPeers.isEmpty()) 广播 else 只发手动节点` 的二选一，于是"配置过一个手动节点"
 * 就让本机彻底停止广播、同时也不再给被动握过手的节点发心跳。纯函数能让"广播永远在列表里"
 * 这条不变量被单测直接钉住，不需要真的开 socket（见 `DiscoveryTargetsTest`）。
 *
 * 广播地址与某个手动节点 / 已知节点重合时会重复发一份，这是无害的：
 * `PeerRegistry.touch` 是幂等的，上游 TS 同样不去重（`discovery.ts:234` 注释）。
 *
 * `knownPeers` 传 registry 的全量即可 —— 「只在线的才发」这条过滤放在函数内部，
 * 调用点不再各自记得过滤（曾经正因为把这条规则留在调用点，才写漏了一整条心跳路径）。
 */
internal fun broadcastTargets(
    broadcastHost: String,
    broadcastPort: Int,
    manualPeers: List<ManualPeer>,
    knownPeers: List<PeerRecord>,
    /**
     * 各节点存下来的**候选端点**（配对码里那一池地址），由调用方从 registry 摊平。
     *
     * 为什么心跳也要发候选：手机只留移动网时，桌面在 registry 里的地址可能是它上一次
     * 被采纳的局域网地址（不可达）；而配对码里的公网 IPv6 明明通。心跳如果只发"当前地址"，
     * 手机就永远送不出 presence、桌面也就永远学不到手机的运营商地址 —— 两边互相显示离线。
     */
    candidateEndpoints: List<Pair<String, Int>> = emptyList(),
    /**
     * 各网卡自己的**子网广播地址**（家用网段那类 `x.x.x.255`）。
     *
     * 受限广播 `255.255.255.255` **没有具体路由条目**，只能落到默认路由；而代理软件
     * 开 TUN 增强模式时会装 `0.0.0.0/1` + `128.0.0.0/1`（比 `/0` 更具体），受限广播
     * 因此被整条接进代理的虚拟口，局域网里一个对端都收不到。
     *
     * 表现极具迷惑性：Tailscale / ZeroTier 照常能用（走 `100.64/10` 这类更具体的
     * 路由），**只有 Tidings 失联**。用户的疑问是「没关之前能正常通过
     * Tailscale/ZeroTier 访问，为什么会对 Tidings 有影响」—— 因为两者走的不是同一条路。
     *
     * 子网广播有直连路由（该网段的 /24，前缀更长），抢不走，所以它是 TUN 环境下
     * 唯一还能出网的广播形态。顺带覆盖多网卡：LAN + ZeroTier + Tailscale 同时有时，
     * 原先只在一个网段广播。
     */
    subnetBroadcasts: List<String> = emptyList(),
): List<Pair<String, Int>> {
    val targets = mutableListOf(broadcastHost to broadcastPort)
    for (host in subnetBroadcasts) {
        if (host.isNotBlank() && host != broadcastHost) targets += host to broadcastPort
    }
    for (peer in manualPeers) targets += peer.host to peer.udpPort
    for (record in knownPeers) {
        if (record.online) targets += record.ip to record.udpPort
    }
    targets += candidateEndpoints
    return targets
}
