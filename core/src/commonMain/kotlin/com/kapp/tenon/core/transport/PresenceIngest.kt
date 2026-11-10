// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.DecodeResult
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.Timings
import com.kapp.tenon.core.protocol.makeEnvelope
import com.kapp.tenon.core.protocol.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * **入站发现帧的登记器** —— legacy `Discovery` 唯一值得留下的那一半。
 *
 * ## 它从哪来、为什么保留
 *
 * 删掉 legacy 发现时，`Discovery` 里的东西分两类：
 *
 * - **要删的**：UDP 广播/多播播报、扫描网段、broadcastTargets、三张广播地址表 ——
 *   那些是「没有连接的时代，靠广播互相找」的手段，luciola 发现引擎已经取代。
 * - **必须留下的**：收到 `entry` / `alive` / `presence` / `profile` / `exit` 之后
 *   **怎么把它登记进 registry、怎么回 alive、资料版本对不上时怎么换回来**。
 *
 * 第二类是协议事实，与"用什么方式收到"无关。删掉它不会让系统更现代，只会让
 * 「手动添加的节点」「扫码定向拨号」这两条路径**静默失效**：我们发出 entry，对端
 * 收到了，然后没有任何代码处理它 —— 双方节点表里永远没有对方。
 *
 * ## 与 `Discovery` 的两处差别
 *
 * 1. 不自己调 `sweep`：离线判定归 `PeerRegistry.sweep`，节奏由 [SweepOnlyDiscoveryEngine]
 *    按 `offlineAfter` 驱动。这里只负责"收到就登记"。
 * 2. 不播报：`announce*` 全部不存在。需要主动告知对端时，由 `NodeRuntime` 的定向心跳
 *    （`sendPresenceHeartbeat`）发 entry。
 */
internal class PresenceIngest(
    private val transport: DatagramTransport,
    private val registry: PeerRegistry,
    private val scope: CoroutineScope,
    private val timings: Timings,
    private val selfProfile: () -> Profile,
    /** 档案里带了合法 `tlsPin` 时回调（来源由调用方标记，通常是 pairing）。 */
    private val onPeerPin: (nodeId: String, pin: String) -> Unit = { _, _ -> },
    /** `scan-ranges` 这类 gossip 帧：它不在可靠集里，只能从这条原始入口递上去。 */
    private val onGossip: suspend (Envelope) -> Unit = {},
) {
    /** 同一节点最近一次回 alive 的时间（去重用，防开机风暴时整网同时应答）。 */
    private val lastAliveReplyAt = mutableMapOf<String, Long>()

    /** 同一节点最近一次「资料版本失配 → 补一次 entry」的时间。 */
    private val lastProfileRefreshAt = mutableMapOf<String, Long>()

    suspend fun onDatagram(datagram: ReceivedDatagram) {
        val decoded = Codec.decode(datagram.bytes)
        if (decoded !is DecodeResult.Ok) return
        val envelope = decoded.env
        if (envelope.from == selfProfile().nodeId) return
        // 帧里的资料优先，其次用节点表已存的那份：两者都带 luciolaPort 时才知道
        // 该往哪个端口发包。数据报源端口只是**没有资料的老端**的回退值。
        val profile = profileOf(envelope)
        val sendPort = sendingPortFor(profile ?: registry.get(envelope.from)?.profile, datagram.port)
        // 任何一条入站报文都在告诉我们「它现在从这个地址可达」—— 收进候选池。
        // 不改当前地址（漂移规则仍由 touch 把关），只让出站重试多一条能打的路。
        registry.rememberSource(envelope.from, datagram.host, sendPort)
        // 入站出声只留「不认识的节点」：已知节点的心跳是常态，逐帧打会把日志淹掉；
        // 而「它到了我却没登记过」是真异常（发现能把包送到、登记环节漏了）。
        if ((envelope.type == MsgTypes.PRESENCE || envelope.type == MsgTypes.ENTRY ||
                envelope.type == MsgTypes.ALIVE) &&
            registry.get(envelope.from) == null
        ) {
            println(
                "[presence] rx unknown from=${envelope.from.take(8)} " +
                    "type=${envelope.type} src=${datagram.host}:${datagram.port}",
            )
        }
        when (envelope.type) {
            MsgTypes.ENTRY -> handleEntry(envelope, datagram, profile, sendPort)
            MsgTypes.ALIVE -> handleAlive(envelope, datagram, profile, sendPort)
            MsgTypes.PROFILE -> handleProfile(envelope, datagram, profile, sendPort)
            MsgTypes.EXIT -> registry.markOffline(envelope.from)
            MsgTypes.PRESENCE -> handlePresence(envelope, datagram, sendPort)
            // `peers` 是"认得、有意不处理"（我们不做节点表散播）。
            // `scan-ranges` **不是**这一类：应用层有处理它的代码，只是它不在可靠集里，
            // Messenger 那条路收不到，所以必须从这里投递上去。
            MsgTypes.PEERS -> Unit
            MsgTypes.SCAN_RANGES -> onGossip(envelope)
        }
    }

    /**
     * 资料里带了 `tlsPin` 就交给上层固定。
     *
     * 形状不对（`TlsPin.parse` 不认）就当**没有**这个键：坏值绝不静默变成"信任的 pin"。
     */
    private fun learnPin(profile: Profile) {
        val raw = profile.tlsPin ?: return
        if (com.kapp.tenon.core.crypto.TlsPin.parse(raw) == null) return
        onPeerPin(profile.nodeId, raw)
    }

    /**
     * **把对端档案里声明的全部地址学进候选池。**
     *
     * 候选地址原先只有两个来源：配对码给的那一池、以及「刚从这个地址收到过报文」
     * （[PeerRegistry.rememberSource]）。两者都要求**先有一条能通的路径**。
     * 而对端换成 VPN（Tailscale / ZeroTier）之后，广播不跨网段，那条路径就不存在了：
     * 局域网互通 → 一方切 VPN → 旧地址不可达 → 谁也收不到谁的包 → 死锁。
     *
     * 档案里带上全部地址后，**任意一条通路收到过一次 profile** 就能把对端的所有地址
     * 灌进候选池；出站重试按序尝试，VPN 那条自然能命中。
     */
    private fun learnAddresses(profile: Profile, port: Int) {
        val declared = profile.addresses.orEmpty()
        if (declared.isEmpty()) return
        for (host in declared) {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) continue
            registry.rememberSource(profile.nodeId, trimmed, port)
        }
    }

    /** `entry` 带全量资料：收端直接采纳（`confirmed = true`），并回一条 alive。 */
    private suspend fun handleEntry(
        envelope: Envelope,
        datagram: ReceivedDatagram,
        profile: Profile?,
        sendPort: Int,
    ) {
        if (profile == null) return
        learnPin(profile)
        learnAddresses(profile, sendPort)
        registry.touch(
            envelope.from,
            datagram.host,
            sendPort,
            profile,
            envelope.ts,
            confirmed = true,
        )
        scheduleAliveReply(envelope.from, datagram.host, sendPort, probeIdOf(envelope))
    }

    private suspend fun handleAlive(
        envelope: Envelope,
        datagram: ReceivedDatagram,
        profile: Profile?,
        sendPort: Int,
    ) {
        if (profile == null) return
        learnPin(profile)
        learnAddresses(profile, sendPort)
        registry.touch(envelope.from, datagram.host, sendPort, profile, envelope.ts, confirmed = true)
    }

    /**
     * 资料变更帧（昵称/公司/团队/头像变更的广播或单播）。
     *
     * 与 [handleAlive] 走同一条路，但**不传 `confirmed`**：这帧是对方主动上门、不是我们
     * 定向握手换回来的，所以要不要采纳交给 `PeerRegistry` 的新旧判据（`profileRev` 更大
     * 就收，相同版本才需要确认）。这样既能让"改了昵称"立刻生效，又不会因为一次乱序或
     * 伪造的 UDP 包把资料钉回旧版本。
     */
    private suspend fun handleProfile(
        envelope: Envelope,
        datagram: ReceivedDatagram,
        profile: Profile?,
        sendPort: Int,
    ) {
        if (profile == null) return
        learnPin(profile)
        learnAddresses(profile, sendPort)
        registry.touch(envelope.from, datagram.host, sendPort, profile, envelope.ts)
    }

    /**
     * 心跳：续期，并在**资料版本对不上**时用一次定向 `entry` 把对方的最新资料换回来。
     *
     * 协议 §6.1：`presence` 携带 `profileRev`；收端发现与本地缓存版本不一致 → 单播 `entry`，
     * 对方回 `alive`（带全量资料）即完成刷新。零新增报文类型。
     *
     * 三种情况都算「对不上」，都必须问一次：
     *
     * 1. `profileRevOf == -1`（从没见过它的资料）—— 本机重启后节点表是空的，而对方
     *    **不会再发 entry**（它只在启动/换网段时握手一次），干等就是永远等不到；
     * 2. 版本号不同；
     * 3. **源地址漂移被 `touch` 拒收** —— 手机在「局域网 IPv6 ↔ 运营商 IPv6」之间切换时
     *    会走到这里，不问就永远停在旧地址上，消息全进队列（真机：queue.log 15→18）。
     *
     * 唯一的克制：同一节点两次刷新至少隔 [PROFILE_REFRESH_MIN_INTERVAL_MS]，
     * 否则一个版本号乱跳的对端能让本机每个心跳周期都发一次 entry。
     * 收件地址取**数据报的源地址**而不是载荷字段，所以不存在被伪造成反射放大器的问题。
     */
    private suspend fun handlePresence(
        envelope: Envelope,
        datagram: ReceivedDatagram,
        sendPort: Int,
    ) {
        val accepted = registry.touch(envelope.from, datagram.host, sendPort, null, envelope.ts)
        // 拒收要出声。`touch` 返回 null 意味着**这条心跳没有刷新 lastSeen**，而调用方
        // 看得到的只有「它过一会儿就判我离线」；真机两侧互相翻脸的锯齿，一半是这里静默丢弃造成的。
        if (accepted == null) {
            val known = registry.get(envelope.from)
            println(
                "[presence] rejected from=${envelope.from} src=${datagram.host}:${datagram.port} " +
                    "known=" + (known?.let { "${it.ip}:${it.udpPort} online=${it.online}" } ?: "none"),
            )
        }
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
        sendRaw(Codec.encodeToBytes(entry), datagram.host, sendPort)
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
        scope.launch {
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

    private suspend fun sendRaw(bytes: ByteArray, host: String, port: Int): Boolean =
        transport.send(bytes, host, port)

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
    }
}
