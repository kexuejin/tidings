// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.kapp.tidings.core.testutil.awaitAfterSend
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-38：对端资料里带了 `tlsPin` ⇒ 发现层必须把它交给上层固定（**配对带来的 pin**）。
 *
 * 这是"竞态窗口消失"的入口那一步：宿主收到 `entry`/`alive`/`profile` 里的 pin 就固定下来，
 * 之后第一次 TLS 连接按它校验（见 `LinkAcceptPolicyTest` 的两条 pairing 用例）。
 *
 * 负控：**形状不对的 pin 不许交上去** —— 坏值静默变成"信任的 pin"比不固定更糟。
 */
class DiscoveryPinLearningTest {

    private fun profile(nodeId: String, tlsPin: String?) = Profile(
        nodeId = nodeId,
        nick = "n",
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "192.168.2.140",
        platform = "linux",
        tcpPort = 17879,
        ver = "relay-0.1.0",
        caps = emptyList(),
        tlsPin = tlsPin,
    )

    @Test
    fun aPinInTheProfileIsHandedToTheHostAndABadShapeIsNot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transportA = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val transportB = UdpTransport(port = 0, bindHost = "127.0.0.1")
        transportA.start()
        transportB.start()

        val learned = mutableListOf<Pair<String, String>>()
        val discoveryA = Discovery(
            transport = transportA,
            registry = PeerRegistry("node-a"),
            selfProfile = { profile("node-a", null) },
            onPeerPin = { nodeId, pin -> learned += nodeId to pin },
        )
        discoveryA.start(scope)

        val goodPin = "sha256:" + "a".repeat(64)
        suspend fun sendEntry(profile: Profile) {
            transportB.send(
                Codec.encodeToBytes(
                    makeEnvelope(MsgTypes.ENTRY, profile.nodeId, Codec.profilePayload(profile)),
                ),
                "127.0.0.1",
                transportA.boundPort,
            )
        }

        try {
            // 重发而不是只发一次：SharedFlow 没有 replay，订阅之前的帧会被直接丢掉
            // （与 DiscoveryGossipDeliveryTest 同一条纪律，避免全量跑时偶发红）。
            awaitAfterSend(
                what = "资料里的 pin 必须交给上层固定",
                send = { sendEntry(profile("node-b", goodPin)) },
            ) { learned.isNotEmpty() }
            assertEquals(listOf("node-b" to goodPin), learned, "资料里的 pin 必须交给上层固定")

            // 负控：形状不对的 pin（TlsPin.parse 不认）**不许**交上去。
            // 不能"发 50 次坏 pin 再 sleep 一会儿然后断言没学到"——那只是还没到。
            // 在坏 pin 之后发一条**好** pin，等好 pin 被学到：同一个 UDP 流有序，
            // 好 pin 到了就证明坏 pin 那一帧早已被处理过（该丢的已经丢了）。
            learned.clear()
            sendEntry(profile("node-c-bad", "not-a-pin"))
            awaitAfterSend(
                what = "坏形状的 pin 之后连好 pin 都没被学到，无法证明坏 pin 已被处理",
                send = { sendEntry(profile("node-good", goodPin)) },
            ) { learned.isNotEmpty() }
            assertEquals(
                listOf("node-good" to goodPin),
                learned,
                "坏形状的 pin 绝不许变成「信任的 pin」：$learned",
            )
        } finally {
            discoveryA.stop()
            transportA.stop()
            transportB.stop()
            scope.cancel()
        }
    }
}
