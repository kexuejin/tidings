// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.makeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 支线日志流枢纽（Phase 1）的双角色行为钉子：
 * 宿主侧的合批/补档/多订阅者生命周期，与订阅侧的对账/去重/失败落账。
 *
 * 时序纪律（§2.7-6）：合批节拍是**观测预算**不是断言 —— flushIntervalMs 调小 +
 * awaitUntil 轮询，绝不赌 delay 一次到位。
 */
class QuestStreamHubTest {

    private class Senders {
        data class Chunk(val peer: String, val questId: String, val seq: Long, val text: String)
        val subs = mutableListOf<Triple<String, String, Long>>()
        val chunks = mutableListOf<Chunk>()
        val cancels = mutableListOf<Pair<String, String>>()
        var subResult = true
        var chunkResult = true

        fun hub(scope: CoroutineScope, resolver: (String) -> String? = { null }): QuestStreamHub =
            QuestStreamHub(
                scope = scope,
                flushIntervalMs = 20,
                sendSub = { peer, questId, since ->
                    subs.add(Triple(peer, questId, since)); subResult
                },
                sendChunk = { peer, questId, seq, text ->
                    chunks.add(Chunk(peer, questId, seq, text)); chunkResult
                },
                sendCancel = { peer, questId ->
                    cancels.add(peer to questId); true
                },
                questHostResolver = resolver,
            )

        fun chunksFor(peer: String, questId: String): List<Chunk> =
            chunks.filter { it.peer == peer && it.questId == questId }
    }

    private suspend fun awaitTrue(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    private fun streamSubEnvelope(from: String, questId: String, sinceSeq: Long?): com.kapp.tenon.core.protocol.Envelope {
        val payload = buildJsonObject {
            put("questId", questId)
            if (sinceSeq != null) put("sinceSeq", sinceSeq)
        }
        return makeEnvelope(MsgTypes.STREAM_SUB, from, payload)
    }

    private fun streamChunkEnvelope(from: String, questId: String, seq: Long, text: String): com.kapp.tenon.core.protocol.Envelope {
        val payload = buildJsonObject {
            put("questId", questId)
            put("seq", seq)
            put("text", text)
        }
        return makeEnvelope(MsgTypes.STREAM_CHUNK, from, payload)
    }

    @Test
    fun subscriberGetsReplayThenLiveBatches() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val senders = Senders()
        val hub = senders.hub(scope)
        try {
            // 订阅前进来的行：sub 到达时冲成存量（一拍内可能是一批或两批 —— 20ms
            // 节拍与行的到达时机有竞态，断言按「拼回去等于原文」写，不赌批边界）。
            hub.acceptLine("q-1", "line-1")
            hub.acceptLine("q-1", "line-2")
            assertTrue(senders.chunks.isEmpty(), "没订阅就不该有块发出（泵未启动）")

            hub.handleStreamSub(streamSubEnvelope("phone", "q-1", sinceSeq = null))
            awaitTrue("存量没补到") { senders.chunksFor("phone", "q-1").isNotEmpty() }
            val replayText = senders.chunksFor("phone", "q-1").joinToString("\n") { it.text }
            assertEquals(listOf("line-1", "line-2"), replayText.split('\n'), "存量必须补齐订阅前的产出")

            // 实时批：泵按节拍把新行冲出去（seq 严格大于存量）。
            hub.acceptLine("q-1", "line-3")
            awaitTrue("实时批没到") { senders.chunksFor("phone", "q-1").any { "line-3" in it.text } }
            val last = senders.chunksFor("phone", "q-1").last()
            assertEquals("line-3", last.text)
            assertTrue(last.seq > senders.chunksFor("phone", "q-1").first().seq, "seq 必须单调")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelStopsOnlyThatSubscriber() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val senders = Senders()
        val hub = senders.hub(scope)
        try {
            hub.handleStreamSub(streamSubEnvelope("phone", "q-1", null))
            hub.handleStreamSub(streamSubEnvelope("tablet", "q-1", null))
            hub.acceptLine("q-1", "hello")
            awaitTrue("双订阅者都该收到") {
                senders.chunksFor("phone", "q-1").isNotEmpty() && senders.chunksFor("tablet", "q-1").isNotEmpty()
            }

            hub.handleStreamCancel(makeEnvelope(MsgTypes.STREAM_CANCEL, "phone", buildJsonObject { put("questId", "q-1") }))
            val tabletCount = senders.chunksFor("tablet", "q-1").size
            hub.acceptLine("q-1", "after-cancel")
            awaitTrue("剩下的订阅者要继续收") { senders.chunksFor("tablet", "q-1").size > tabletCount }
            assertEquals(tabletCount, senders.chunksFor("phone", "q-1").size, "退订者不再收到任何块")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lastSubscriberLeavingStopsThePump() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val senders = Senders()
        val hub = senders.hub(scope)
        try {
            hub.handleStreamSub(streamSubEnvelope("phone", "q-1", null))
            hub.handleStreamCancel(makeEnvelope(MsgTypes.STREAM_CANCEL, "phone", buildJsonObject { put("questId", "q-1") }))
            val before = senders.chunks.size
            delay(120) // 两个泵节拍：没人订阅时不该有任何块出去
            assertEquals(before, senders.chunks.size, "没有订阅者时泵必须停（不是空转）")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun hostRingKeepsLastTwoThousandLines() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val senders = Senders()
        val hub = senders.hub(scope)
        try {
            hub.handleStreamSub(streamSubEnvelope("phone", "q-1", null))
            // 2100 行（每批 1 行）：环形缓冲要裁到 ≤ 2000 行。
            repeat(2_100) { hub.acceptLine("q-1", "line-$it") }
            awaitTrue("缓冲没跟上产量") { hub.localSnapshotLines("q-1").size >= 2_000 }
            delay(200)
            assertTrue(hub.localSnapshotLines("q-1").size <= QuestStreamHub.MAX_RING_LINES, "环形缓冲必须按行数封顶")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun subscriberSideDedupsBySeq() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val senders = Senders()
        val hub = senders.hub(scope)
        try {
            hub.handleStreamChunk(streamChunkEnvelope("host", "q-1", 1, "one"))
            hub.handleStreamChunk(streamChunkEnvelope("host", "q-1", 2, "two"))
            hub.handleStreamChunk(streamChunkEnvelope("host", "q-1", 2, "two-again"))
            awaitTrue("首两批没进缓冲") { hub.snapshotLines("q-1").isNotEmpty() }
            delay(50)
            assertEquals(listOf("one", "two"), hub.snapshotLines("q-1"), "迟到重复块按 seq 挡住")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reconcileSubscribesCancelsAndReportsUnavailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val senders = Senders()
        val hub = senders.hub(scope, resolver = { "host-node" })
        try {
            hub.setDesired("q-1", true)
            awaitTrue("sub 没发出去") { senders.subs.isNotEmpty() }
            awaitTrue("订阅成功要落 STREAMING") { hub.streamStateOf("q-1") == QuestStreamState.STREAMING }

            hub.setDesired("q-1", false)
            awaitTrue("cancel 没发出去") { senders.cancels.isNotEmpty() }
            awaitTrue("退订后要回 IDLE") { hub.streamStateOf("q-1") == QuestStreamState.IDLE }

            // sub 没有 ACK（链路不通/旧版）= UNAVAILABLE，绝不假装在流。
            senders.subResult = false
            hub.setDesired("q-2", true)
            awaitTrue("失败要落 UNAVAILABLE") { hub.streamStateOf("q-2") == QuestStreamState.UNAVAILABLE }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun resolverLocalHostMarksLocalWithoutProtocol() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val senders = Senders()
        val hub = senders.hub(scope, resolver = { QuestStreamHub.LOCAL_HOST })
        try {
            hub.setDesired("q-1", true)
            awaitTrue("LOCAL 状态没落") { hub.streamStateOf("q-1") == QuestStreamState.LOCAL }
            delay(80)
            assertTrue(senders.subs.isEmpty() && senders.chunks.isEmpty() && senders.cancels.isEmpty(), "本机任务不经协议")
        } finally {
            scope.cancel()
        }
    }
}
