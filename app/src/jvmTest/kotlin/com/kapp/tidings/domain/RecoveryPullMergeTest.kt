// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 身份恢复 L2 的合并侧（方案 §6.3 / Q11）：
 *  · 批次按消息 id 幂等合入 —— 同一批发两遍，会话里只有一份；
 *  · `mine` 翻转 —— 桌面副本的视角与恢复方相反，不翻转会让"谁说的"整体颠倒；
 *  · 成套性 —— `peerId` 与请求方会话键对不上的条目按坏批丢弃。
 */
class RecoveryPullMergeTest {

    private val peerA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(),
        scope = CoroutineScope(Job()),
    )

    private fun dataEnvelope(from: String, messages: List<MessageUi>): Envelope {
        val elements = messages.map { Codec.wireJson.encodeToJsonElement(MessageUi.serializer(), it) }
        return Envelope(
            v = Protocol.VERSION,
            type = MsgTypes.RECOVERY_DATA,
            id = "recovery-" + System.nanoTime(),
            from = from,
            ts = 1L,
            payload = buildJsonObject {
                put("batch", 0)
                put("more", false)
                put("messages", kotlinx.serialization.json.JsonArray(elements))
            },
        )
    }

    private fun desktopCopy(id: String, mine: Boolean) = MessageUi(
        id = id,
        peerId = peerA,
        mine = mine,
        text = "hello-$id",
        at = 1_000L + id.hashCode().mod(100),
    )

    @Test
    fun mergesWithMineFlippedAndDedupsById() = runBlocking {
        val controller = controller()
        try {
            // 桌面副本：两条是桌面发的（mine=true），一条是恢复方发的（mine=false）。
            val batch = listOf(
                desktopCopy("m-1", mine = true),
                desktopCopy("m-2", mine = true),
                desktopCopy("m-3", mine = false),
            )
            controller.messagesCtl.handleOnRecoveryData(dataEnvelope(peerA, batch))
            val thread = controller.core.threads.getValue(peerA)
            assertEquals(3, thread.size, "第一批三条全部合入")
            // 视角翻转：桌面发的（mine=true）到恢复方这边必须是「对方的」（mine=false）；
            // 恢复方自己发的（桌面副本 mine=false）恢复回来必须是 mine=true。
            assertEquals(false, thread.first { it.id == "m-1" }.mine, "桌面发的消息在恢复方是别人的")
            assertEquals(true, thread.first { it.id == "m-3" }.mine, "自己发的消息恢复回来还是自己的")

            // 同一批再发一遍（fire-and-forget 的重复帧）：id 去重，不产生第二份。
            controller.messagesCtl.handleOnRecoveryData(dataEnvelope(peerA, batch))
            assertEquals(3, controller.core.threads.getValue(peerA).size, "重复批必须幂等")
        } finally {
            controller.scope.cancel()
        }
    }

    @Test
    fun dropsEntriesWhoseConversationDoesNotMatchTheRequester() = runBlocking {
        val controller = controller()
        try {
            val batch = listOf(
                desktopCopy("m-ok", mine = true),
                desktopCopy("m-evil", mine = true).copy(peerId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            )
            controller.messagesCtl.handleOnRecoveryData(dataEnvelope(peerA, batch))
            val thread = controller.core.threads.getValue(peerA)
            assertEquals(listOf("m-ok"), thread.map { it.id }, "会话键对不上的条目不合入")
            assertEquals(
                0,
                controller.core.threads.count { it.key == "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" },
                "不得凭空长出别的会话",
            )
        } finally {
            controller.scope.cancel()
        }
    }
}
