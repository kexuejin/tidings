// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.transport.LocalFile
import com.kapp.tidings.core.transport.TransferEvent
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 文件卡的**发送时序**：卡片必须在报价返回之前就出现，且报价回来后只有一张。
 *
 * 用户看到的「发送后界面没有出现发送中」在文件这条路上是「连卡片都不出现」：
 * 旧实现要等 [NodeRuntime.offerFiles] 跑完（每个成员一遍 ACK + TCP 兜底）才插卡片。
 * 这条测试用闸门把报价卡在半路，在放行之前断言卡片已经在列表里。
 *
 * 另一半是**登记一致性**：报价返回后卡片换成真实 transferId，而
 * transferConvs / transferMessages 两张表必须指向同一条消息 —— 否则后续进度事件
 * 找不到卡片，会**另插一张**（界面上一份文件变两张）。进度那条用例就是守它的。
 */
class RelayControllerFileCardOptimisticTest {

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val lin = "11111111-1111-4111-8111-111111111111"
    private val zhou = "22222222-2222-4222-8222-222222222222"

    @Test
    fun fileCardAppearsBeforeOfferReturns() = runBlocking {
        val controller = controller()
        val gate = CompletableDeferred<Unit>()

        val job = launch {
            controller.transfersCtl.handleOfferFilesWith(lin, listOf(file("a.pdf", 2048L))) { _, _, _, _ ->
                gate.await()
                "t-real-1"
            }
        }

        // 闸门还没放行：卡片就必须已经在了。
        val card = awaitCard(controller, lin)
        assertEquals(TransferState.Offered, card.file?.state)
        assertEquals("a.pdf", card.file?.name)
        assertEquals(1, controller.state.value.messagesWith(lin).size, "放行前就该有且只有这一张卡片")

        gate.complete(Unit)
        job.join()

        val cards = controller.state.value.messagesWith(lin).filter { it.file != null }
        assertEquals(1, cards.size, "就地改写后仍只有一张：不能留两张（乐观的 + 真实的）")
        assertEquals("t-real-1", cards.single().file?.transferId)
        // 登记与卡片必须指向同一条消息。
        assertEquals(cards.single().id, controller.core.transferMessages["t-real-1"])
        assertEquals(lin, controller.core.transferConvs["t-real-1"])
        assertTrue(
            controller.core.transferMessages.keys.none { it.startsWith("pending-") },
            "临时键必须清掉，否则进度事件可能认到一张过期的卡片",
        )
    }

    @Test
    fun progressAfterCommitUpdatesTheSameCard() = runBlocking {
        val controller = controller()
        controller.transfersCtl.handleOfferFilesWith(lin, listOf(file("a.pdf", 100L))) { _, _, _, _ -> "t-1" }

        controller.transfersCtl.handleOnTransfer(
            TransferEvent.Progress(
                transferId = "t-1",
                peerId = lin,
                fileId = "f-1",
                done = 40,
                total = 100,
                incoming = false,
            ),
        )

        val cards = controller.state.value.messagesWith(lin).filter { it.file != null }
        assertEquals(1, cards.size, "进度事件必须更新原来那张，不能另插一张")
        assertEquals(TransferState.Active, cards.single().file?.state)
        assertEquals(40L, cards.single().file?.done)
    }

    @Test
    fun fileCardBecomesFailedWhenOfferReturnsNull() = runBlocking {
        val controller = controller()
        controller.transfersCtl.handleOfferFilesWith(lin, listOf(file("a.pdf", 100L))) { _, _, _, _ -> null }

        val cards = controller.state.value.messagesWith(lin).filter { it.file != null }
        assertEquals(1, cards.size, "报价失败时卡片要留下并转成失败，不能凭空消失")
        assertEquals(TransferState.Failed, cards.single().file?.state)
    }

    @Test
    fun groupFileOfferDrawsOneCardAndRegistersEveryMember() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("上线小组", listOf(lin, zhou))!!
        val convId = ConvId.group(groupId)
        val offered = mutableListOf<String>()

        controller.transfersCtl.handleOfferFilesWith(convId, listOf(file("a.pdf", 100L))) { member, _, _, _ ->
            offered += member
            "t-" + member.take(4)
        }

        val cards = controller.state.value.messagesWith(convId).filter { it.file != null }
        assertEquals(1, cards.size, "群文件只画一张卡片")
        assertEquals(listOf(lin, zhou).sorted(), offered.sorted(), "每个成员都要收到一次报价")
        assertEquals(cards.single().id, controller.core.transferMessages["t-" + lin.take(4)])
        assertEquals(cards.single().id, controller.core.transferMessages["t-" + zhou.take(4)])
    }

    private fun file(name: String, size: Long) = LocalFile(path = name, name = name, size = size)

    private suspend fun awaitCard(controller: RelayController, convId: String): MessageUi {
        var found: MessageUi? = null
        withTimeout(5_000) {
            while (found == null) {
                found = controller.state.value.messagesWith(convId).firstOrNull { it.file != null }
                if (found == null) delay(5)
            }
        }
        return found ?: error("等待的文件卡片没有出现")
    }

    private fun CoroutineScope.controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(self),
        scope = this,
        version = "relay-test",
    )

    private class MemorySettingsStore(private val nodeId: String) : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = nodeId,
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }
}
