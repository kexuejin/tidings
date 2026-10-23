// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **对端档案里声明的地址必须进候选池。**
 *
 * ## 真机现场（2026-10-22）
 *
 * 红米 30 分钟一个包都没到本机（`lastSeen` 停在半小时前、队列只涨不降），
 * 而本机 `ping` 它的 Tailscale 地址 **2.2 秒就通** —— 网络通，是协议不知道怎么走。
 *
 * 死锁的形状：候选地址原先只来自「配对码」和「刚收到过报文的源地址」，
 * 两者都要求先有一条能通的路径；而对端切到 VPN 后广播不跨网段，
 * 那条路径就没了 —— 于是谁也学不到谁的新地址。
 *
 * 修法：档案里带上本机全部地址，收到即灌进候选池。
 */
class DiscoveryLearnsDeclaredAddressesTest {

    private fun profile(nodeId: String, addresses: List<String>?) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = 17879,
        addresses = addresses,
        ver = "relay-0.1.0",
        caps = emptyList(),
    )

    /**
     * **核心判据：档案里声明的 VPN 地址必须出现在候选池里。**
     *
     * 这里用 `100.66.238.121`（Tailscale 段）而不是真实的报文源地址 `127.0.0.1`，
     * 正是为了证明「这条地址不是从包从哪来推出来的」——
     * 它只能来自档案本身。
     */
    @Test
    fun theDeclaredAddressesLandInTheCandidatePool() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transportA = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val transportB = UdpTransport(port = 0, bindHost = "127.0.0.1")
        transportA.start()
        transportB.start()
        val registryB = PeerRegistry("node-b")
        val discoveryB = Discovery(
            transport = transportB,
            registry = registryB,
            selfProfile = { profile("node-b", null) },
            broadcastHost = "127.0.0.1",
            broadcastPort = transportA.boundPort,
        )
        discoveryB.start(scope)
        try {
            val declared = listOf("100.66.238.121", "192.168.2.82")
            val profileA = profile("node-a", declared)
            var learned: List<String> = emptyList()
            repeat(40) {
                transportA.send(
                    Codec.encodeToBytes(
                        makeEnvelope(MsgTypes.ENTRY, "node-a", Codec.profilePayload(profileA)),
                    ),
                    "127.0.0.1",
                    transportB.boundPort,
                )
                delay(60)
                learned = registryB.candidatesOf("node-a").map { it.host }
                if (learned.containsAll(declared)) return@repeat
            }
            assertTrue(
                learned.containsAll(declared),
                "档案声明的地址必须进候选池（否则换网后永远连不上）；实得=" + learned,
            )
        } finally {
            discoveryB.stop()
            transportA.stop()
            transportB.stop()
            scope.cancel()
        }
    }

    /** 没有声明地址时不得凭空造候选（老端不认这个键，行为必须一字不变）。 */
    @Test
    fun aProfileWithoutAddressesAddsNoCandidates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transportA = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val transportB = UdpTransport(port = 0, bindHost = "127.0.0.1")
        transportA.start()
        transportB.start()
        val registryB = PeerRegistry("node-b")
        val discoveryB = Discovery(
            transport = transportB,
            registry = registryB,
            selfProfile = { profile("node-b", null) },
            broadcastHost = "127.0.0.1",
            broadcastPort = transportA.boundPort,
        )
        discoveryB.start(scope)
        try {
            val profileA = profile("node-a", null)
            repeat(30) {
                transportA.send(
                    Codec.encodeToBytes(
                        makeEnvelope(MsgTypes.ENTRY, "node-a", Codec.profilePayload(profileA)),
                    ),
                    "127.0.0.1",
                    transportB.boundPort,
                )
                delay(50)
            }
            assertTrue(registryB.get("node-a") != null, "资料本身要被采纳")
            // rememberSource 仍会记下真实源地址，但**不得**多出档案里没有的地址。
            assertTrue(
                registryB.candidatesOf("node-a").none { it.host.startsWith("100.") },
                "没有声明地址时不该凭空造候选，实得=" + registryB.candidatesOf("node-a"),
            )
        } finally {
            discoveryB.stop()
            transportA.stop()
            transportB.stop()
            scope.cancel()
        }
    }
}
