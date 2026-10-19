// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.nowMillis
import app.relay.core.protocol.Codec
import app.relay.core.protocol.DecodeResult
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.Timings
import app.relay.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 资料漂移的两条纠正路径（协议 §6.1、需求 F-DISC-7）。
 *
 * 为什么要单独立一个测试：这两条路以前**都不存在** —— `profile` 帧掉出 `Discovery` 的
 * `when` 被静默丢弃，`presence` 只看心跳不看 `profileRev`。后果是真机可见的：
 * 对端改了昵称、换了 TCP 端口或增删了能力之后，本机永远停在旧的那份资料上，
 * 并且**按旧端口去连**。用户看到的是"他明明改了名字，我这边还是老名字"。
 *
 * 两条路各钉一件事：
 * 1. `profile` 帧（对方主动上门）→ 采纳更新的 `profileRev`；
 * 2. `presence` 里 `profileRev` 与本地缓存不一致（心跳只带版本号、不带资料）
 *    → 本机单播一次定向 `entry`，对方回 `alive` 带全量资料，刷新完成。
 *
 * 用两个真实 `UdpTransport` 绑在 127.0.0.1 上跑，不做假传输：地址写错、载荷形状不对
 * 这类失败只有在真收发时才暴露。这里是核心协议层；同一件事在真机上的复现与修法见
 * `design/runtime-evidence/scan-a15-android-permission.txt` 同批的
 * `profile-refresh-android-restart.txt`（Pixel 6 SERIAL-TEST-01）。
 */
class DiscoveryProfileRefreshTest {

    private fun profile(nodeId: String, rev: Int, nick: String) = Profile(
        nodeId = nodeId,
        nick = nick,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = rev,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = emptyList(),
    )

    @Test
    fun aProfileFrameWithANewerRevisionIsAdopted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transportA = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val transportB = UdpTransport(port = 0, bindHost = "127.0.0.1")
        transportA.start()
        transportB.start()

        val registryB = PeerRegistry("node-b")
        // A 的资料是可变的：这条测试要的就是"同一台机器、用的人换了"。
        var profileA = profile("node-a", rev = 0, nick = "旧名字")
        val discoveryB = Discovery(
            transport = transportB,
            registry = registryB,
            selfProfile = { profile("node-b", rev = 0, nick = "b") },
            broadcastHost = "127.0.0.1",
            broadcastPort = transportA.boundPort,
        )
        discoveryB.start(scope)

        try {
            // 先让 B 通过正常握手认识 A（rev 0）。**按真实节奏重发**：握手/资料帧在真机上都是
            // 周期性的，单发一次在满量并行（`:core:allTests` 两套一起跑）的负载下会因丢包/调度
            // 而间歇红 —— 「单独跑绿」不能作为结论留着它。
            sendUntil(
                send = {
                    transportA.send(
                        Codec.encodeToBytes(
                            makeEnvelope(MsgTypes.ENTRY, "node-a", Codec.profilePayload(profileA)),
                        ),
                        "127.0.0.1",
                        transportB.boundPort,
                    )
                },
                what = "B 没有记下 A 的资料（rev 0）",
                condition = { registryB.get("node-a")?.profile?.nick == "旧名字" },
            )

            // A 改了昵称：rev 1，发 profile 上门（同样按节奏重发）。
            profileA = profile("node-a", rev = 1, nick = "新名字")
            sendUntil(
                send = {
                    transportA.send(
                        Codec.encodeToBytes(
                            makeEnvelope(MsgTypes.PROFILE, "node-a", Codec.profilePayload(profileA)),
                        ),
                        "127.0.0.1",
                        transportB.boundPort,
                    )
                },
                what = "B 收到 profile 帧却没有采纳新资料（这帧以前是静默丢弃的）",
                condition = { registryB.get("node-a")?.profile?.nick == "新名字" },
            )
            assertEquals(1, registryB.get("node-a")?.profile?.profileRev)
        } finally {
            discoveryB.stop()
            transportA.stop()
            transportB.stop()
            scope.cancel()
        }
    }

    /**
     * 重启后的那条路：**本地根本没有这个节点的资料**（`profileRevOf` = -1）时，心跳同样要问。
     *
     * 这条是 2026-10-07 在 Pixel 6 上抓出来的真缺陷，不是假想：手机重启后节点表是空的（内存表），
     * 而对端**只在启动/换网段时握手一次**，之后每个周期只发 `presence`。第一版在 `knownRev == -1`
     * 时直接返回（理由是"该等它的 entry"），于是那个人的名字永远停在 nodeId 前 8 位 ——
     * 实测等到 55 秒、跨过两个心跳周期都没恢复，直到对端重启。
     *
     * 证据同样是那个**出站的 entry**：只看"最后资料对了"分不清是这次问回来的还是别的帧带来的。
     */
    @Test
    fun aPresenceFromAPeerWeHaveNoProfileForAlsoMakesUsAsk() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transportA = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val transportB = UdpTransport(port = 0, bindHost = "127.0.0.1")
        transportA.start()
        transportB.start()

        val registryB = PeerRegistry("node-b")
        val profileA = profile("node-a", rev = 3, nick = "重启后才认识的人")
        // A 是一台真的 Discovery：它要负责"收到 entry 就回 alive（带全量资料）"。
        // 抖动窗口调小：默认 0..2s 的 reply jitter 会让这条在压测下**间歇红**
        // （同文件后面那条已经踩过一次，这里同样处理 —— 间歇红不是「单独跑绿」能解释的）。
        val fastReply = Timings(entryReplyJitterBase = 10, entryReplyJitterMax = 10)
        val discoveryA = Discovery(
            transport = transportA,
            registry = PeerRegistry("node-a"),
            selfProfile = { profileA },
            timings = fastReply,
            broadcastHost = "127.0.0.1",
            broadcastPort = 65534,
        )
        discoveryA.start(scope)

        val discoveryB = Discovery(
            transport = transportB,
            registry = registryB,
            selfProfile = { profile("node-b", rev = 0, nick = "b") },
            timings = fastReply,
            broadcastHost = "127.0.0.1",
            broadcastPort = transportA.boundPort,
        )
        discoveryB.start(scope)

        val sentByB = mutableListOf<Envelope>()
        val watcher = scope.launch {
            transportA.incoming.collect { datagram ->
                val decoded = Codec.decode(datagram.bytes)
                if (decoded is DecodeResult.Ok) sentByB += decoded.env
            }
        }

        try {
            // 前提：B 对 A 一无所知（这就是"重启后"的状态）。
            assertEquals(-1, registryB.profileRevOf("node-a"))

            // A 只发心跳 —— 它不会主动再发一次 entry；心跳按真实节奏重复发。
            sendUntil(
                send = {
                    transportA.send(
                        Codec.encodeToBytes(makeEnvelope(MsgTypes.PRESENCE, "node-a", Codec.presencePayload(1, 3))),
                        "127.0.0.1",
                        transportB.boundPort,
                    )
                },
                what = "B 没有这个节点的资料，收到心跳却不去问（重启后名字永远回不来）",
                condition = { sentByB.any { it.type == MsgTypes.ENTRY && it.from == "node-b" } },
            )
            awaitTrue(what = "A 回了 alive，但 B 没采纳资料") {
                registryB.get("node-a")?.profile?.nick == "重启后才认识的人"
            }
            assertEquals(3, registryB.get("node-a")?.profile?.profileRev)
        } finally {
            watcher.cancel()
            discoveryA.stop()
            discoveryB.stop()
            transportA.stop()
            transportB.stop()
            scope.cancel()
        }
    }

    @Test
    fun aPresenceWithADifferentRevisionMakesUsAskForTheFullProfile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transportA = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val transportB = UdpTransport(port = 0, bindHost = "127.0.0.1")
        transportA.start()
        transportB.start()

        val registryB = PeerRegistry("node-b")
        var profileA = profile("node-a", rev = 0, nick = "旧名字")
        // 抖动窗口调小：A 的 alive 回包默认带 0..2s jitter，满量并行下会让下面「等 B 采纳」
        // 的那两步间歇红（同族三条测试统一处理）。
        val fastReply = Timings(entryReplyJitterBase = 10, entryReplyJitterMax = 10)
        // A 自己就是一台 Discovery：它要负责"收到 entry 就回 alive（带全量资料）"。
        val discoveryA = Discovery(
            transport = transportA,
            registry = PeerRegistry("node-a"),
            selfProfile = { profileA },
            timings = fastReply,
            broadcastHost = "127.0.0.1",
            broadcastPort = transportB.boundPort,
        )
        discoveryA.start(scope)

        val discoveryB = Discovery(
            transport = transportB,
            registry = registryB,
            selfProfile = { profile("node-b", rev = 0, nick = "b") },
            timings = fastReply,
            broadcastHost = "127.0.0.1",
            broadcastPort = transportA.boundPort,
        )
        discoveryB.start(scope)

        // 看 B 到底发了什么：这条路径的**证据是那个出站的 entry**，
        // 只看"最后资料对了"会分不清是刷新生效了还是碰巧别的帧带过来的。
        val sentByB = mutableListOf<Envelope>()
        val watcher = scope.launch {
            transportA.incoming.collect { datagram ->
                val decoded = Codec.decode(datagram.bytes)
                if (decoded is DecodeResult.Ok) sentByB += decoded.env
            }
        }

        try {
            // 同样按真实节奏重复发：entry 与心跳在真机上都是周期性的，单发一次会间歇红。
            sendUntil(
                send = {
                    transportA.send(
                        Codec.encodeToBytes(
                            makeEnvelope(MsgTypes.ENTRY, "node-a", Codec.profilePayload(profileA)),
                        ),
                        "127.0.0.1",
                        transportB.boundPort,
                    )
                },
                what = "B 没有记下 A 的资料（rev 0）",
                condition = { registryB.get("node-a")?.profile?.nick == "旧名字" },
            )

            // A 升到 rev 1，但只发心跳（心跳不带资料，只带版本号）。
            profileA = profile("node-a", rev = 1, nick = "新名字")
            sendUntil(
                send = {
                    transportA.send(
                        Codec.encodeToBytes(makeEnvelope(MsgTypes.PRESENCE, "node-a", Codec.presencePayload(1, 1))),
                        "127.0.0.1",
                        transportB.boundPort,
                    )
                },
                what = "B 看到 profileRev 失配却没有单播 entry 去换资料",
                condition = { sentByB.any { it.type == MsgTypes.ENTRY && it.from == "node-b" } },
            )
            awaitTrue(what = "A 回了 alive，但 B 没采纳新资料") {
                registryB.get("node-a")?.profile?.nick == "新名字"
            }
        } finally {
            watcher.cancel()
            discoveryA.stop()
            discoveryB.stop()
            transportA.stop()
            transportB.stop()
            scope.cancel()
        }
    }

    /**
     * 跨网真机（task-19）：手机在「局域网 IPv6 ↔ 运营商 IPv6」之间切换时，源地址变了、
     * 但资料版本没变（profileRev 还是那个）。旧代码里 knownRev == theirRev 直接 return，
     * 而 PeerRegistry.touch 又拒绝在线节点的地址漂移 ⇒ 本机永远停在旧地址上，
     * 发给它的消息全进队列。真机现场：网络层双向可达（nc rc=0）、/v1/peers 甚至出现过
     * 运营商地址，queue.log 却从 15 涨到 18，切回 WiFi 才清空。
     *
     * 修法：只要 touch 没采纳（地址漂移/未知节点）就问一次资料 —— 定向 entry → 对方回
     * alive（confirmed = true）→ 新地址被采纳。
     *
     * 测试里「旧地址」是给 registryB 种的一个合成值（127.0.0.9）：macOS 的 lo0 只配了
     * 127.0.0.1，绑不了 127.0.0.2；而漂移判定只比 registry 里的 (ip, udpPort) 与数据报
     * 源地址，种一个假旧地址就能精确走到同一条分支（真机上是 IP 变了，这里是 IP+端口都变）。
     */
    @Test
    fun aPresenceFromANewAddressMakesUsAskSoTheMovedPeerBecomesReachableAgain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transportA = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val transportB = UdpTransport(port = 0, bindHost = "127.0.0.1")
        transportA.start()
        transportB.start()

        val profileA = profile("node-a", rev = 3, nick = "换网的手机")
        // A 是一台真的 Discovery：它收到 entry 会回 alive（带全量资料），回包源地址就是它
        // 当前所在的那个地址。抖动窗口调小，让 alive 立刻回来（默认 0..2s，压测下会 flaky）。
        val discoveryA = Discovery(
            transport = transportA,
            registry = PeerRegistry("node-a"),
            selfProfile = { profileA },
            timings = Timings(entryReplyJitterBase = 10, entryReplyJitterMax = 10),
            broadcastHost = "127.0.0.1",
            broadcastPort = 65534,
        )
        discoveryA.start(scope)

        val registryB = PeerRegistry("node-b")
        val discoveryB = Discovery(
            transport = transportB,
            registry = registryB,
            selfProfile = { profile("node-b", rev = 0, nick = "b") },
            broadcastHost = "127.0.0.1",
            broadcastPort = 65534,
        )
        discoveryB.start(scope)

        val askedAddresses = mutableListOf<String>()
        val watcher = scope.launch {
            transportA.incoming.collect { datagram ->
                val decoded = Codec.decode(datagram.bytes)
                if (decoded is DecodeResult.Ok &&
                    decoded.env.type == MsgTypes.ENTRY &&
                    decoded.env.from == "node-b"
                ) {
                    askedAddresses += datagram.host
                }
            }
        }

        try {
            // 前提：B 认为 A 在线，但停在**旧地址**上（真机里那是局域网 IPv6）。
            registryB.touch(
                "node-a", "127.0.0.9", 17878, profileA, profileTs = 1L, confirmed = true,
            )
            assertTrue(registryB.get("node-a")!!.online, "前提：A 在 B 眼里是在线的")
            assertEquals("127.0.0.9", registryB.get("node-a")!!.ip)

            // A 换了出口地址，资料版本没变，只发心跳 —— 这正是手机切网时的行为。
            // 心跳按真实节奏重复发（单发一次在满量门禁下会间歇红）。
            sendUntil(
                send = {
                    transportA.send(
                        Codec.encodeToBytes(makeEnvelope(MsgTypes.PRESENCE, "node-a", Codec.presencePayload(1, 3))),
                        "127.0.0.1",
                        transportB.boundPort,
                    )
                },
                what = "B 没有因为地址变了而重新问资料（旧代码在这里直接 return）",
                condition = { askedAddresses.contains("127.0.0.1") },
            )
            awaitTrue(what = "B 没有采纳新地址（发给它的消息仍会送去旧地址）") {
                registryB.get("node-a")?.ip == "127.0.0.1"
            }
            assertTrue(registryB.get("node-a")!!.online, "采纳新地址后必须仍是在线")
        } finally {
            watcher.cancel()
            discoveryA.stop()
            discoveryB.stop()
            transportA.stop()
            transportB.stop()
            scope.cancel()
        }
    }

    /**
     * task-21 ①：心跳周期里，候选地址上要额外发一帧 **entry**（带全量资料）。
     *
     * 真机现场：候选路径生效（主机 /v1/peers 的 ip 变成了手机的运营商地址），但 online 一直
     * False、队列不动。原因是只发 presence 时，收端在"离线且没有资料"这一档会拒绝它，只能靠
     * 收端反向发 entry、我们再回 alive —— 而那条反向 UDP 在运营商侧可能被过滤。entry 带全量
     * 资料，收端 confirmed=true 直接采纳（连 online 一起翻回来），不依赖任何反向路径。
     */
    @Test
    fun aPresenceCycleAlsoAnnouncesWithAFullProfileToCandidateAddresses() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val self = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val peerSide = UdpTransport(port = 0, bindHost = "127.0.0.1")
        self.start()
        peerSide.start()

        val registry = PeerRegistry("node-a")
        // 对端的候选地址（测试里用 peerSide 的回环地址冒充它的公网地址）。
        registry.setCandidates("node-b", listOf(PeerEndpoint("127.0.0.1", peerSide.boundPort)))

        val discovery = Discovery(
            transport = self,
            registry = registry,
            selfProfile = { profile("node-a", 0, "a") },
            timings = Timings(presenceInterval = 40, sweepInterval = 60_000),
            broadcastHost = "127.0.0.1",
            broadcastPort = 65534,
        )
        discovery.start(scope)

        val seen = mutableListOf<Envelope>()
        val watcher = scope.launch {
            peerSide.incoming.collect { datagram ->
                val decoded = Codec.decode(datagram.bytes)
                if (decoded is DecodeResult.Ok) seen += decoded.env
            }
        }

        try {
            awaitTrue(what = "候选地址上没有收到 entry（收端就永远无法 confirmed 采纳新地址）") {
                seen.any { it.type == MsgTypes.ENTRY && it.from == "node-a" }
            }
            assertTrue(
                seen.any { it.type == MsgTypes.PRESENCE && it.from == "node-a" },
                "presence 那条路也不能丢：${seen.map { it.type }}",
            )
        } finally {
            watcher.cancel()
            discovery.stop()
            self.stop()
            peerSide.stop()
            scope.cancel()
        }
    }

    /**
     * 按真实节奏**周期性**重发某个刺激帧（心跳/握手/资料帧），直到 [condition] 成立
     * （超时就带着 [what] 断言失败）。名字不再叫 `sendPresenceUntil`：这一族里被重发的
     * 不只心跳 —— 只要是**真机上本来就会周期性重发**的帧，都该按那个节奏来。
     *
     * 为什么不「单发一次 + 等 3 秒」：心跳在真机上本来就是周期性的，而单发一次在**满量门禁**
     * （几百个测试并行）的负载下会因丢包/调度而间歇红 —— 那是**测试没按真实节奏来**，不是产品问题。
     * 间歇红必须改成确定性的，不能用「单独跑绿」当结论。
     */
    private suspend fun sendUntil(
        send: suspend () -> Unit,
        timeoutMs: Long = 8_000,
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = nowMillis() + timeoutMs
        while (nowMillis() < deadline) {
            if (condition()) return
            send()
            delay(100)
        }
        assertTrue(condition(), what)
    }

    /** 轮询到条件成立为止；超时就带着 [what] 断言失败（比挂死或裸超时好读）。 */
    private suspend fun awaitTrue(timeoutMs: Long = 3_000, what: String, condition: () -> Boolean) {
        repeat((timeoutMs / 20).toInt()) {
            if (condition()) return
            delay(20)
        }
        assertTrue(condition(), what)
    }
}
