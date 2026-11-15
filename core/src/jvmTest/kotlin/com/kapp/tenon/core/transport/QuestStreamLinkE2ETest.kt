// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.protocol.makeEnvelope
import com.kapp.tenon.core.session.NodeRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.kapp.tenon.core.testing.ManualDiscovery
import com.kapp.tenon.core.testing.udpTestTransport

/**
 * 支线日志流三帧（Phase 1）的端到端（两个真实 NodeRuntime 跑在回环上）：
 *
 *  · **双向都要通**：订阅端（拨号侧，[LinkClient]）的 `stream-sub` / `stream-cancel`
 *    上行到宿主；宿主（接听侧，[LinkServer]）的 `stream-chunk` 下行到订阅端 ——
 *    同一条长连接的两个方向，两个出口各管一半。
 *  · **fail-closed**：没有任何加密通道可达（无长连接、transport 非 luciola 信道）
 *    时发送必须返回 `false`，且**一个 UDP 包都不许漏出去**（严禁 UDP 回退；
 *    接收侧的 OVERSIZE 兜底见 `StreamFrameBudgetGuardTest`）。
 *
 * 30s 等待 = 观测预算不是断言（§2.7-6），与 [LinkK1Test] 同款。
 */
class QuestStreamLinkE2ETest {

    private fun profile(nodeId: String, caps: List<String>) = Profile(
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
        caps = caps,
    )

    // 60s = 观测预算不是断言（§2.7-6）：真链路测试在稳定性门第 1 轮（冷 JVM +
    // 全量串行）偶发超窗、第 2/3 轮恒绿 —— 与 LinkK1Test 同一档冷启动放宽。
    private suspend fun awaitTrue(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    /**
     * 发送类断言的**断链容忍**形状（稳定性门满载实测：链路会在测试中途被
     * 对端的 dead-after 判死或重拨退避打断 —— 单次「发送成功」不足以推出
     * 「会到达」）。判据 = **投递到达**；每 1s 节流重发（接收端按 seq/幂等键
     * 去重，重发无害），连接断了就等 LinkClient 的重拨，预算是观测上限
     * 不是同步赌注（§2.7-6）。
     */
    private suspend fun awaitDelivered(
        what: String,
        isConnected: () -> Boolean,
        send: suspend () -> Boolean,
        delivered: () -> Boolean,
        timeoutMs: Long = 90_000,
    ) {
        var lastSendAt = 0L
        withTimeout(timeoutMs) {
            while (true) {
                if (delivered()) return@withTimeout
                val now = System.currentTimeMillis()
                if (isConnected() && now - lastSendAt > 1_000) {
                    send()
                    lastSendAt = now
                }
                delay(50)
            }
        }
    }

    private fun payloadOf(envelope: Envelope): JsonObject =
        envelope.payload as? JsonObject ?: error("payload 不是对象")

    @Test
    fun streamSubAndChunkAndCancelTraverseTheLinkBothWays() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val discoveries = mutableMapOf<String, ManualDiscovery>()
        val host = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = profile("stream-host", listOf(MsgTypes.CAP_LINK)),
            udpPort = 0,
            bindHost = "127.0.0.1",
            tcpPort = 0,
            extraDiscoveryFactory = { registry, _ ->
                ManualDiscovery(registry).also { discoveries["stream-host"] = it }
            },
        )
        val phone = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = profile("stream-phone", emptyList()),
            udpPort = 0,
            bindHost = "127.0.0.1",
            tcpPort = 0,
            extraDiscoveryFactory = { registry, _ ->
                ManualDiscovery(registry).also { discoveries["stream-phone"] = it }
            },
        )
        phone.linkEnabled = true
        host.start(scope)
        phone.start(scope)
        var diagAt = 0L
        try {
            discoveries.getValue(host.profile.nodeId)
                .introduce(phone.profile.nodeId, "127.0.0.1", phone.boundPort, phone.profile)
            discoveries.getValue(phone.profile.nodeId)
                .introduce(host.profile.nodeId, "127.0.0.1", host.boundPort, host.profile)
            try {
                awaitTrue("长连接没建起来") {
                    if (System.currentTimeMillis() - diagAt > 10_000) {
                        diagAt = System.currentTimeMillis()
                        println("[stream-e2e-diag] phone dialStats=" + phone.linkClient?.dialStats() +
                            " closeReasons=" + phone.linkClient?.closeReasons() +
                            " closeCounts=" + phone.linkClient?.closeCounts() +
                            " hostPeers=" + host.getPeer("stream-phone")?.let { it.ip + ":" + it.udpPort })
                    }
                    phone.linkClient?.isConnected("stream-host") == true &&
                        host.linkServer?.isConnected("stream-phone") == true
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                println("[stream-e2e-diag] LINK TIMEOUT: phone dialStats=" + phone.linkClient?.dialStats() +
                    " closeReasons=" + phone.linkClient?.closeReasons() +
                    " closeCounts=" + phone.linkClient?.closeCounts() +
                    " redial=" + phone.linkClient?.redialNow("stream-host"))
                throw e
            }

            // ---- 上行：订阅端（拨号侧 LinkClient）的 sub / cancel 要到宿主 ----
            val subReceived = CompletableDeferred<Envelope>()
            val cancelReceived = CompletableDeferred<Envelope>()
            val hostWatch = scope.launch {
                host.inbound.collect { envelope ->
                    when (envelope.type) {
                        MsgTypes.STREAM_SUB -> subReceived.complete(envelope)
                        MsgTypes.STREAM_CANCEL -> cancelReceived.complete(envelope)
                    }
                }
            }
            awaitDelivered(
                "sub 上行到达宿主（中途断链等重拨后重发）",
                isConnected = { phone.linkClient?.isConnected("stream-host") == true },
                send = { phone.sendStreamSub("stream-host", "q-1", sinceSeq = null) },
                delivered = { subReceived.isCompleted },
            )
            val sub = subReceived.await()
            assertEquals("stream-phone", sub.from)
            assertEquals("q-1", payloadOf(sub)["questId"]?.jsonPrimitive?.content)

            awaitDelivered(
                "cancel 上行到达宿主",
                isConnected = { phone.linkClient?.isConnected("stream-host") == true },
                send = { phone.sendStreamCancel("stream-host", "q-1") },
                delivered = { cancelReceived.isCompleted },
            )
            val cancel = cancelReceived.await()
            assertEquals("q-1", payloadOf(cancel)["questId"]?.jsonPrimitive?.content)
            hostWatch.cancel()

            // ---- 下行：宿主（接听侧 LinkServer）的 chunk 要到订阅端 ----
            val chunkReceived = CompletableDeferred<Envelope>()
            val phoneWatch = scope.launch {
                phone.inbound.collect { envelope ->
                    if (envelope.type == MsgTypes.STREAM_CHUNK &&
                        envelope.from == "stream-host" &&
                        payloadOf(envelope)["questId"]?.jsonPrimitive?.content == "q-1"
                    ) {
                        chunkReceived.complete(envelope)
                    }
                }
            }
            awaitDelivered(
                "chunk 下行到达订阅端（接听侧 LinkServer.send 等.msg-ack，满载下单次可能超窗重试）",
                isConnected = { host.linkServer?.isConnected("stream-phone") == true },
                send = { host.sendStreamChunk("stream-phone", "q-1", seq = 7, rawText = "build ok") },
                delivered = { chunkReceived.isCompleted },
            )
            val chunk = chunkReceived.await()
            assertEquals(7L, payloadOf(chunk)["seq"]?.jsonPrimitive?.longOrNull)
            assertEquals("build ok", payloadOf(chunk)["text"]?.jsonPrimitive?.content)
            phoneWatch.cancel()
        } finally {
            phone.stop()
            host.stop()
            scope.cancel()
        }
    }

    @Test
    fun noChannelMeansFailClosedAndNothingLeaksOverUdp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val discoveries = mutableMapOf<String, ManualDiscovery>()
        val host = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = profile("fc-host", listOf(MsgTypes.CAP_LINK)),
            udpPort = 0,
            bindHost = "127.0.0.1",
            tcpPort = 0,
            extraDiscoveryFactory = { registry, _ ->
                ManualDiscovery(registry).also { discoveries["fc-host"] = it }
            },
        )
        val phone = NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
            profile = profile("fc-phone", emptyList()),
            udpPort = 0,
            bindHost = "127.0.0.1",
            tcpPort = 0,
            extraDiscoveryFactory = { registry, _ ->
                ManualDiscovery(registry).also { discoveries["fc-phone"] = it }
            },
        )
        // 刻意**不开** linkEnabled：两条机互相认识（UDP 握手），但没有任何加密通道。
        host.start(scope)
        phone.start(scope)
        try {
            discoveries.getValue(host.profile.nodeId)
                .introduce(phone.profile.nodeId, "127.0.0.1", phone.boundPort, phone.profile)
            discoveries.getValue(phone.profile.nodeId)
                .introduce(host.profile.nodeId, "127.0.0.1", host.boundPort, host.profile)
            awaitTrue("UDP 握手要先完成") { phone.getPeer("fc-host") != null && host.getPeer("fc-phone") != null }

            val leaked = CompletableDeferred<Envelope>()
            val hostWatch = scope.launch {
                host.inbound.collect { envelope ->
                    if (envelope.type == MsgTypes.STREAM_SUB) leaked.complete(envelope)
                }
            }
            assertFalse(phone.sendStreamSub("fc-host", "q-1", null), "没有任何加密通道必须 fail-closed")
            assertFalse(phone.sendStreamChunk("fc-host", "q-1", 1, "x"), "chunk 同一条纪律")
            delay(300)
            assertTrue(leaked.isActive, "严禁 UDP 回退：宿主一个流帧都不该收到")
            hostWatch.cancel()
        } finally {
            phone.stop()
            host.stop()
            scope.cancel()
        }
    }
}
