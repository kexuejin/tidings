// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.CodecScreen
import com.kapp.tidings.core.protocol.DecodeResult
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun relayProfile(nodeId: String) = Profile(
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
    ver = "relay-0.1.0",
    caps = listOf(MsgTypes.CAP_REMOTE_VIEW, MsgTypes.CAP_REMOTE_SHARE),
)

/**
 * `screen` 可靠帧的投递契约（2026-10-08 投屏接线批次）。
 *
 * 之前这里的行为是「只回 ACK、不投递」—— 注释写着"手机没有投屏能力，设计如此"，
 * 于是下游整条会话机（同意弹窗、token 握手、帧流）全是死代码，桌面↔桌面也一样不通。
 * 现在 screen 与 msg/file-ctl 走同一套「先 ACK → 去重 → 投递」纪律，但保留两条
 * 只有 screen 有的例外，这个测试钉的就是这三件事：
 *
 * 1. **正常路径**：来自已握手原地址的 `screen` 帧 → 回 ACK 且投进 `incoming`；
 * 2. **地址漂移不作答**：注册表里的 ip 与报文源地址不符 → **连 ACK 都不回**。
 *    这不是疏忽而是授权纪律：投屏的 accept 带一次性 token 建立 TCP 接入，
 *    对没验证过来源的帧回 ACK 等于告诉冒名者"这个节点活着、按原地址重试"。
 *    对端的重试最终会自己停下来（可靠帧有超时）。
 * 3. **重复帧只投一次**：同一 id 发两遍 → 两个 ACK（对端靠 ACK 停止重传），
 *    一次投递（会话机不能被重放帧弹两次同意框）。
 *
 * 与 `DiscoveryProfileRefreshTest` 同一理由用两个真 `UdpTransport` 绑回环，不做假传输：
 * 「ACK 回没回、回到哪个地址」只有真收发才暴露。
 */
class MessengerScreenDeliveryTest {

    private suspend fun awaitTrue(ms: Long = 2_000, what: String, cond: () -> Boolean) {
        val deadline = com.kapp.tidings.core.nowMillis() + ms
        while (com.kapp.tidings.core.nowMillis() < deadline) {
            if (cond()) return
            delay(20)
        }
        assertTrue(false, what)
    }

    private class Fixture(scope: CoroutineScope) {
        val peerTransport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val selfTransport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("node-b")
        val messenger: Messenger
        val delivered = mutableListOf<Envelope>()
        val acks = mutableListOf<String>()

        init {
            scope.launch { peerTransport.incoming.collect { dg ->
                val d = Codec.decode(dg.bytes)
                if (d is DecodeResult.Ok && d.env.type == MsgTypes.ACK) {
                    val ackFor = (d.env.payload as? JsonObject)?.get("ackFor")
                        as? JsonPrimitive
                    if (ackFor != null) acks += ackFor.content
                }
            } }
            messenger = Messenger(
                transport = selfTransport,
                registry = registry,
                selfNodeId = "node-b",
                queueStore = InMemoryQueueStore(),
                dedupStore = InMemoryDedupStore(),
            )
            scope.launch { messenger.incoming.collect { delivered += it } }
        }

        suspend fun start(peerIp: String) {
            peerTransport.start()
            selfTransport.start()
            registry.touch(
                "node-a", peerIp, peerTransport.boundPort,
                relayProfile("node-a"), profileTs = 1_000, confirmed = true,
            )
        }

        fun stop() {
            messenger.stop()
            peerTransport.stop()
            selfTransport.stop()
        }
    }

    @Test
    fun screenFromAHandshakedAddressIsAckedAndDelivered() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fx = Fixture(scope)
        fx.start(peerIp = "127.0.0.1")
        fx.messenger.start(scope)
        delay(150)

        try {
            val env = makeEnvelope(
                type = MsgTypes.SCREEN,
                from = "node-a",
                payload = CodecScreen.screenRequestPayload("11111111-1111-4111-8111-111111111111"),
                id = "scr-1",
            )
            fx.peerTransport.send(Codec.encodeToBytes(env), "127.0.0.1", fx.selfTransport.boundPort)

            awaitTrue(
                what = "已握手地址的 screen 帧没有被投递：会话机收不到 request，界面上就是「点了投屏，对方毫无反应」",
            ) { fx.delivered.any { it.id == "scr-1" && it.type == MsgTypes.SCREEN } }
            awaitTrue(what = "screen 帧必须回 ACK，否则对端按 RELIABLE 一路重传到队列剪枝") {
                fx.acks.contains("scr-1")
            }
        } finally {
            fx.stop()
            scope.cancel()
        }
    }

    @Test
    fun screenFromADriftedAddressIsSilentlyDropped() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fx = Fixture(scope)
        // 注册表里存的是别的地址：回环报文的真实源 127.0.0.1 与之不符 ⇒ 漂移。
        fx.start(peerIp = "10.9.9.9")
        fx.messenger.start(scope)
        delay(150)

        try {
            val env = makeEnvelope(
                type = MsgTypes.SCREEN,
                from = "node-a",
                payload = CodecScreen.screenRequestPayload("22222222-2222-4222-8222-222222222222"),
                id = "scr-2",
            )
            fx.peerTransport.send(Codec.encodeToBytes(env), "127.0.0.1", fx.selfTransport.boundPort)

            // 「不作答」没法断言"永远没有"，只能断言一个短窗口里没有；
            // 窗口与上面用例的等待同量级，够真路径跑三趟。
            delay(600)
            assertTrue(
                fx.delivered.none { it.id == "scr-2" },
                "漂移来源的 screen 帧被投递了：等于把未验证来源当已握手节点",
            )
            assertTrue(
                fx.acks.isEmpty(),
                "漂移来源的 screen 帧回了 ACK：告诉冒名者这个节点活着，违反 screen 的授权纪律",
            )
        } finally {
            fx.stop()
            scope.cancel()
        }
    }

    @Test
    fun duplicateScreenIsAckedTwiceButDeliveredOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fx = Fixture(scope)
        fx.start(peerIp = "127.0.0.1")
        fx.messenger.start(scope)
        delay(150)

        try {
            val env = makeEnvelope(
                type = MsgTypes.SCREEN,
                from = "node-a",
                payload = CodecScreen.screenRequestPayload("33333333-3333-4333-8333-333333333333"),
                id = "scr-3",
            )
            val bytes = Codec.encodeToBytes(env)
            fx.peerTransport.send(bytes, "127.0.0.1", fx.selfTransport.boundPort)
            awaitTrue(what = "第一帧没有被投递") { fx.delivered.any { it.id == "scr-3" } }
            fx.peerTransport.send(bytes, "127.0.0.1", fx.selfTransport.boundPort)
            awaitTrue(what = "重复帧也要回第二个 ACK（对端只认 ACK 停止重传）") { fx.acks.size >= 2 }
            delay(300)
            assertEquals(
                1, fx.delivered.count { it.id == "scr-3" },
                "重复帧被投递了两次：同意弹窗会被重放帧弹第二次",
            )
        } finally {
            fx.stop()
            scope.cancel()
        }
    }
}
