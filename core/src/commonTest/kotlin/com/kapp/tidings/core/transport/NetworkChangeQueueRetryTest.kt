// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.DecodeResult
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.Timings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **换网之后，积压必须重试一次** —— 包括被上一次失败判成离线、又没有候选地址的对端。
 *
 * ## 真机形状（问题 5）
 *
 * 一次发送失败会 `markOffline`。此后 `sendAwaitAck` 里那句
 * `!online && candidates.isEmpty() ⇒ 一个包都不发` 就把这个对端**永久静音**了 ——
 * 而失败是在**上一张网**下发生的：换到另一张网之后，同一个地址可能又通了。
 * 稳态的 15s 定时重试也只碰 `online || 有候选` 的对端，所以这个状态**没有任何出口**：
 * 队列只涨不降，界面上对方一直「离线」，用户点重发也只是再入队一条。
 *
 * 判据必须能区分两种行为（AGENTS §4）：**换网前一个包都不该发，换网后必须发出去**。
 * 只断言"最后收到了"是分不出"定时重试碰巧发了"和"换网触发"的。
 */
class NetworkChangeQueueRetryTest {

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 0,
        host = "127.0.0.1",
        platform = "linux",
        tcpPort = 20000,
        ver = "relay-test",
        caps = listOf(MsgTypes.CAP_MOBILE_TEXT),
    )

    @Test
    fun anOfflinePeerWithoutCandidatesIsSilentUntilTheNetworkChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val watcher = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val senderTransport = UdpTransport(port = 0, bindHost = "127.0.0.1")
        val registry = PeerRegistry("self")
        val queueStore = InMemoryQueueStore()
        val messenger = Messenger(
            transport = senderTransport,
            registry = registry,
            selfNodeId = "self",
            queueStore = queueStore,
            dedupStore = InMemoryDedupStore(),
            timings = Timings(ackRetrySchedule = listOf(50L), tcpAckTimeout = 50L),
        )
        watcher.start()
        senderTransport.start()
        messenger.start(scope)
        val seenIds = mutableListOf<String>()
        scope.launch {
            watcher.incoming.collect { datagram ->
                val decoded = Codec.decode(datagram.bytes)
                if (decoded is DecodeResult.Ok) seenIds += decoded.env.id
            }
        }
        delay(100) // 订阅先于发送（没有订阅者时老化报文会被丢掉）
        try {
            // 对端在上次那张网下被判离线，且**没有候选池**（配对码只给了一个地址的常见情形）。
            registry.restoreOffline(profile("peer-1"), "127.0.0.1", watcher.boundPort, 1L)
            val sent = messenger.sendUserMessage(
                "peer-1",
                com.kapp.tidings.core.protocol.makeEnvelope(
                    MsgTypes.MSG,
                    "self",
                    Codec.textPayload("卡在队列里的一句话"),
                ),
            )
            assertEquals(SendOutcome.QUEUED, sent, "对端离线且没有候选 ⇒ 直接入队")
            assertEquals(1, queueStore.pendingAll().size)

            // 稳态重试：**一个包都不发**（这是旧行为，也是问题 5 的静音点）。
            messenger.flushQueue("peer-1")
            delay(200)
            assertTrue(
                seenIds.isEmpty(),
                "稳态下对「离线 + 无候选」的对端不该发包（否则这条测试就分不出换网触发）：${seenIds.size} 个包",
            )
            assertEquals(1, queueStore.pendingAll().size, "没发出去就不许把队列清掉")

            // 换网：上一张网下的离线结论作废 ⇒ 必须真的试一次当前端点。
            val msgId = queueStore.pendingAll().single().msgId
            messenger.retryQueuedAfterNetworkChange()
            withTimeout(5_000) {
                while (seenIds.none { it == msgId }) delay(10)
            }
            assertEquals(
                true,
                seenIds.contains(msgId),
                "换网后必须按原信封重试（同一个 msgId）：${seenIds}",
            )
            // 对端仍然没回 ACK（这里没人 ACK）⇒ 队列照旧留着，不许假装成功。
            assertEquals(
                1,
                queueStore.pendingAll().size,
                "没 ACK 就不许出队：失败要如实留着等下一张网",
            )
        } finally {
            messenger.stop()
            senderTransport.stop()
            watcher.stop()
            scope.cancel()
        }
    }
}
