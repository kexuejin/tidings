// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.Envelope
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.session.sendGroupTextFanOut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 群扇出的**并发度**、总预算，以及「预算到点时重放的是**每个成员自己那份**信封」。
 *
 * 旧实现逐成员串行 await：一个离线成员要等完整退避 + TCP 兜底，4 人群 3 人离线就是三倍。
 * 断言「总耗时 < 成员数 × 退避」是**时间赌注** —— 机器一忙就假绿或假红。这里改断言
 * **同时启动的成员数**：闸门把每个成员都卡在半路，串行实现下并发峰值恒为 1。
 *
 * 预算那条守的是**不丢消息**：超预算的成员必须先把同一条信封重新入队，再被取消。
 * 先取消后入队的话，那个成员会死在入队之前 —— 就是本仓最贵的那类静默丢失。
 *
 * 最后一条（[eachMemberIsRequeuedWithItsOwnVariant]）是 `gta1` 门控的下半场：载荷按成员
 * 不同（legacy 不带扩展键），所以**重放时也不能拿统一那一份塞回去** —— 否则老端在补发时
 * 再被整帧拒收一次，用户看到的是「重发了还是没到」。
 */
class GroupFanOutConcurrencyTest {

    /** 每个成员一份信封，id 就是成员名 —— 重放断言能一眼看出拿的是不是"他自己那份"。 */
    private fun envelopeOf(member: String): Envelope =
        makeEnvelope(MsgTypes.MSG, "self", Codec.textPayload(member), id = member, ts = 1L)

    @Test
    fun everyMemberStartsBeforeAnyOfThemReturns() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var live = 0
        var peak = 0
        val job = launch {
            sendGroupTextFanOut(
                members = listOf("m1", "m2", "m3"),
                selfId = "self",
                budgetMs = 60_000L,
                envelopeFor = { envelopeOf(it) },
                requeue = { _, _, _ -> true },
                send = { _, _ ->
                    live++
                    if (live > peak) peak = live
                    gate.await()
                    live--
                    SendOutcome.SENT
                },
            )
        }
        // 等到三个成员都进过 send：串行实现下 live 永远到不了 3，这里会超时。
        withTimeout(5_000) {
            while (live < 3) delay(5)
        }
        assertEquals(3, peak, "三个成员必须在任何一个返回之前就已全部启动（串行实现下 peak 恒为 1）")
        gate.complete(Unit)
        job.join()
    }

    @Test
    fun membersPastTheBudgetAreRequeuedInsteadOfLost() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val requeued = mutableListOf<String>()
        val outcome = withTimeout(5_000) {
            sendGroupTextFanOut(
                members = listOf("fast", "slow"),
                selfId = "self",
                budgetMs = 200L,
                envelopeFor = { envelopeOf(it) },
                requeue = { member, json, _ ->
                    assertEquals(
                        Codec.encode(envelopeOf(member)),
                        json,
                        "重放的必须是这个成员那份原信封（同一个 id），不能重建",
                    )
                    requeued += member
                    true
                },
                send = { member, _ ->
                    if (member == "slow") {
                        gate.await()
                        SendOutcome.SENT
                    } else {
                        SendOutcome.SENT
                    }
                },
            )
        }
        assertEquals(SendOutcome.QUEUED, outcome)
        assertEquals(listOf("slow"), requeued, "超预算的成员必须先重新入队，否则那条消息对他就是静默丢失")
    }

    /**
     * 预算到点时，两个成员各自重放**自己那一份**：带扩展键的那份仍是带键的，
     * legacy 那份仍是不带的（同一 id）。用统一一份重放会让其中一边要么丢键、要么被拒。
     */
    @Test
    fun eachMemberIsRequeuedWithItsOwnVariant() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val requeued = mutableMapOf<String, String>()
        val attributed = makeEnvelope(
            MsgTypes.MSG,
            "self",
            Codec.groupTextPayload("hi", "g1", 1, senderId = "agent:dsh"),
            id = "same-id",
        )
        val plain = makeEnvelope(
            MsgTypes.MSG,
            "self",
            Codec.groupTextPayload("hi", "g1", 1),
            id = "same-id",
        )
        val outcome = withTimeout(5_000) {
            sendGroupTextFanOut(
                members = listOf("modern", "legacy"),
                selfId = "self",
                budgetMs = 150L,
                envelopeFor = { member -> if (member == "modern") attributed else plain },
                requeue = { member, json, _ ->
                    requeued[member] = json
                    true
                },
                send = { _, _ ->
                    gate.await()
                    SendOutcome.SENT
                },
            )
        }
        assertEquals(SendOutcome.QUEUED, outcome)
        assertEquals(setOf("modern", "legacy"), requeued.keys, "两个成员都超预算 ⇒ 两份都要入队")
        assertTrue(
            requeued.getValue("modern").contains("agent:dsh"),
            "声明了 gta1 的成员重放时仍带 senderId：${requeued["modern"]}",
        )
        assertTrue(
            !requeued.getValue("legacy").contains("senderId"),
            "legacy 成员重放时不许带上扩展键（否则补发那一次又被整帧拒收）：${requeued["legacy"]}",
        )
        assertEquals(attributed.id, plain.id)
    }

    @Test
    fun sentRequiresEveryMember() = runBlocking {
        val all = sendGroupTextFanOut(
            members = listOf("a", "b"),
            selfId = "self",
            budgetMs = 5_000L,
            envelopeFor = { envelopeOf(it) },
            requeue = { _, _, _ -> true },
            send = { _, _ -> SendOutcome.SENT },
        )
        assertEquals(SendOutcome.SENT, all)

        val some = sendGroupTextFanOut(
            members = listOf("a", "b"),
            selfId = "self",
            budgetMs = 5_000L,
            envelopeFor = { envelopeOf(it) },
            requeue = { _, _, _ -> true },
            send = { member, _ -> if (member == "a") SendOutcome.SENT else SendOutcome.QUEUED },
        )
        assertEquals(SendOutcome.QUEUED, some, "只要有一个成员没 ACK，整条就是 QUEUED")
    }

    @Test
    fun theSenderIsSkippedAndASoloGroupIsSent() = runBlocking {
        var calls = 0
        val outcome = sendGroupTextFanOut(
            members = listOf("self"),
            selfId = "self",
            budgetMs = 5_000L,
            envelopeFor = { envelopeOf(it) },
            requeue = { _, _, _ -> true },
            send = { _, _ ->
                calls++
                SendOutcome.QUEUED
            },
        )
        assertEquals(SendOutcome.SENT, outcome)
        assertEquals(0, calls, "只有自己的群不投递，也不该被算成 QUEUED")
    }
    /**
     * **成员挂死时，扇出必须仍然返回。**
     *
     * ## 真机现场
     *
     * 单聊正常、群聊三条消息**全部停在「发送中」超过 60 秒**，而红米手机**已经收到了** ——
     * 发送本身成功，卡的是「等所有成员结束」这件事。
     *
     * 根因：旧实现用 `coroutineScope` 包住扇出，而它的语义是**等所有子协程结束才返回**。
     * `withTimeoutOrNull` 超时只中断了 `await()`，**不会**让已经卡住的 `async` 结束
     * ⇒ `coroutineScope` 永远等下去 ⇒ 界面永远转圈。
     *
     * 这条用 `awaitCancellation()` 模拟一个永不返回的成员：预算到点后必须拿到 `QUEUED`。
     * 断言的是**能返回**，不是返回值本身。
     */
    @Test
    fun aHangingMemberDoesNotPreventTheFanOutFromReturning() = runBlocking {
        val requeued = mutableListOf<String>()
        val outcome = withTimeout(2_000) {
            sendGroupTextFanOut(
                members = listOf("self", "hang"),
                selfId = "self",
                budgetMs = 100L,
                envelopeFor = { m -> envelopeOf(m) },
                requeue = { member, _, _ ->
                    requeued += member
                    true
                },
            ) { _, _ ->
                awaitCancellation()
            }
        }
        assertEquals(SendOutcome.QUEUED, outcome, "预算到点必须返回 QUEUED")
        assertTrue(
            "hang" in requeued,
            "挂死的成员必须先入队再取消 —— 否则那条消息就静默丢了",
        )
    }
}
