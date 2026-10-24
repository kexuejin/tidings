// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import com.kapp.tidings.testutil.awaitUntil

/**
 * 乐观插入的状态**跨重启**必须收尾（回归护栏，任务 D-4）。
 *
 * 乐观插入引入了一个进程内状态 [DeliveryState.Sending]。进程都换了一个，那次发送必然
 * 已经结束（成功或失败），无从恢复 —— 留着它等于让用户看着一条永远转下去的消息，
 * 而界面上只有 Failed 才挂「重发」出口。这条测试走**真实的恢复流水线**
 * （落盘 → 新控制器 → handleRestoreMessages），不是直接调 toRestored。
 *
 * 文件卡那半条守的是另一件事：卡片**根本不落盘**（[MessageUi.toStorable] 会剥掉
 * file），所以乐观插入的 [TransferState.Offered] 不会在重启后复活成一张点不动的卡片。
 */
class RelayControllerOptimisticRestoreTest {

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val lin = "11111111-1111-4111-8111-111111111111"

    @Test
    fun anOptimisticGroupBubbleIsFailedAfterRestart() = runBlocking {
        val store = InMemoryMessageStore()
        val first = controller(this, store)
        first.appendMessage(
            MessageUi(
                id = "local-1",
                peerId = "group:g1",
                mine = true,
                text = "乐观插入的群消息",
                at = 1L,
                delivery = DeliveryState.Sending,
                senderId = self,
            ),
        )
        awaitUntil("恢复或发送后的状态落地") { store.load()["group:g1"]?.any { it.id == "local-1" } == true }

        val second = controller(this, store)
        second.messagesCtl.handleRestoreMessages().join()

        val restored = second.state.value.messagesWith("group:g1").single()
        assertEquals(
            DeliveryState.Failed,
            restored.delivery,
            "重启后不能还停在「发送中」—— 那次发送已经结束，界面必须给出可重试的终态",
        )
    }

    @Test
    fun anOfferedFileCardDoesNotSurviveRestart() = runBlocking {
        val store = InMemoryMessageStore()
        val first = controller(this, store)
        first.appendMessage(
            MessageUi(
                id = "card-1",
                peerId = lin,
                mine = true,
                text = "",
                at = 1L,
                file = FileCardUi(
                    transferId = "pending-m-1",
                    name = "a.pdf",
                    size = 100L,
                    mine = true,
                    state = TransferState.Offered,
                    msgId = "m-1",
                ),
            ),
        )
        awaitUntil("文件卡落盘之后又被读回来") { store.load()[lin]?.any { it.id == "card-1" } == true }

        // 落盘那一刻卡片就该被剥掉：磁盘上不该留下一个传输管理器不认的 transferId。
        val persisted = store.load()[lin].orEmpty().single { it.id == "card-1" }
        assertNull(persisted.file, "文件卡不落盘：Offered 不能跨进程残留成一张点不动的卡片")

        val second = controller(this, store)
        second.messagesCtl.handleRestoreMessages().join()

        val restored = second.state.value.messagesWith(lin).single { it.id == "card-1" }
        assertNull(restored.file, "重启后不该复活一张 Offered 卡片")
        assertNull(restored.delivery, "文件卡没有投递态，不该凭空长出转圈或红字")
    }

    private fun controller(scope: CoroutineScope, store: MessageStore): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = object : SettingsStore {
            override fun load(): RelaySettings? = RelaySettings(
                nodeId = self,
                udpPort = 0,
                tcpPort = 0,
                persistMessages = true,
                // 决定位必须显式给：只有「已经决定过」的 true 才不会被迁移逻辑当成老库。
                persistMessagesDecided = true,
            )

            override fun save(settings: RelaySettings) = Unit
        },
        scope = scope,
        version = "relay-test",
        messageStore = store,
    )
}
