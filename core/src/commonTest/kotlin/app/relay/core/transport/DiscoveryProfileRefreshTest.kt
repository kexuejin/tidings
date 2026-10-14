// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import app.relay.core.protocol.Codec
import app.relay.core.protocol.DecodeResult
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
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
            // 先让 B 通过一次正常握手认识 A（rev 0）。
            transportA.send(
                Codec.encodeToBytes(
                    makeEnvelope(MsgTypes.ENTRY, "node-a", Codec.profilePayload(profileA)),
                ),
                "127.0.0.1",
                transportB.boundPort,
            )
            awaitTrue(what = "B 没有记下 A 的资料（rev 0）") {
                registryB.get("node-a")?.profile?.nick == "旧名字"
            }

            // A 改了昵称：rev 1，发一帧 profile 上门。
            profileA = profile("node-a", rev = 1, nick = "新名字")
            transportA.send(
                Codec.encodeToBytes(
                    makeEnvelope(MsgTypes.PROFILE, "node-a", Codec.profilePayload(profileA)),
                ),
                "127.0.0.1",
                transportB.boundPort,
            )
            awaitTrue(what = "B 收到 profile 帧却没有采纳新资料（这帧以前是静默丢弃的）") {
                registryB.get("node-a")?.profile?.nick == "新名字"
            }
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
        val discoveryA = Discovery(
            transport = transportA,
            registry = PeerRegistry("node-a"),
            selfProfile = { profileA },
            broadcastHost = "127.0.0.1",
            broadcastPort = 65534,
        )
        discoveryA.start(scope)

        val discoveryB = Discovery(
            transport = transportB,
            registry = registryB,
            selfProfile = { profile("node-b", rev = 0, nick = "b") },
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

            // A 只发心跳 —— 它不会主动再发一次 entry。
            transportA.send(
                Codec.encodeToBytes(makeEnvelope(MsgTypes.PRESENCE, "node-a", Codec.presencePayload(1, 3))),
                "127.0.0.1",
                transportB.boundPort,
            )

            awaitTrue(what = "B 没有这个节点的资料，收到心跳却不去问（重启后名字永远回不来）") {
                sentByB.any { it.type == MsgTypes.ENTRY && it.from == "node-b" }
            }
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
        // A 自己就是一台 Discovery：它要负责"收到 entry 就回 alive（带全量资料）"。
        val discoveryA = Discovery(
            transport = transportA,
            registry = PeerRegistry("node-a"),
            selfProfile = { profileA },
            broadcastHost = "127.0.0.1",
            broadcastPort = transportB.boundPort,
        )
        discoveryA.start(scope)

        val discoveryB = Discovery(
            transport = transportB,
            registry = registryB,
            selfProfile = { profile("node-b", rev = 0, nick = "b") },
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
            transportA.send(
                Codec.encodeToBytes(
                    makeEnvelope(MsgTypes.ENTRY, "node-a", Codec.profilePayload(profileA)),
                ),
                "127.0.0.1",
                transportB.boundPort,
            )
            awaitTrue(what = "B 没有记下 A 的资料（rev 0）") {
                registryB.get("node-a")?.profile?.nick == "旧名字"
            }

            // A 升到 rev 1，但只发心跳（心跳不带资料，只带版本号）。
            profileA = profile("node-a", rev = 1, nick = "新名字")
            transportA.send(
                Codec.encodeToBytes(makeEnvelope(MsgTypes.PRESENCE, "node-a", Codec.presencePayload(1, 1))),
                "127.0.0.1",
                transportB.boundPort,
            )

            awaitTrue(what = "B 看到 profileRev 失配却没有单播 entry 去换资料") {
                sentByB.any { it.type == MsgTypes.ENTRY && it.from == "node-b" }
            }
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

    /** 轮询到条件成立为止；超时就带着 [what] 断言失败（比挂死或裸超时好读）。 */
    private suspend fun awaitTrue(timeoutMs: Long = 3_000, what: String, condition: () -> Boolean) {
        repeat((timeoutMs / 20).toInt()) {
            if (condition()) return
            delay(20)
        }
        assertTrue(condition(), what)
    }
}
