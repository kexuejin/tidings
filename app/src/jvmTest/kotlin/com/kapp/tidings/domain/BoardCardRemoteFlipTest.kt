// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.MsgKinds
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * **别的设备翻面，本机也得跟着翻** —— 卡片同步的接收侧。
 *
 * ## 为什么单列一份
 *
 * [BoardCardLocalPersistenceTest] 管的是「我点的合并，我自己得看见」；这份管的是
 * 用户问的另一半：「包括其他设备端」。两条路径不同：
 *
 * - 发送侧（本机点合并）：`broadcastBoardCard` 先在**本机**落库，再发帧；
 * - 接收侧（别人的设备点了合并）：帧从网络进来，走 `handleOnInbound` 的**入站拦截**，
 *   按 `cardId` 找槽位、按 `rev` 判新旧，替换那一行。
 *
 * 接收侧从前只有代码、没有判据。它的正确性靠两条不变量，缺一条都会在真机上表现为
 * 「对方说合并好了，我这边卡还停在待合并」：
 *
 * 1. **同 cardId ⇒ 原位替换**，不追加行（否则一个任务在群里变成两张卡）；
 * 2. **rev 不更大 ⇒ 丢弃**，不回退状态（网络乱序/重放时，旧帧不能把 `merged` 盖回
 *    `review_ready` —— 那会让用户以为补丁又没了）。
 *
 * 第 2 条尤其容易写错：丢弃要**连追加一起丢**，否则旧帧会以「新的一条消息」的形式
 * 冒出来，看起来像又发了一次合并请求。
 */
class BoardCardRemoteFlipTest {

    private val scope = CoroutineScope(Job())

    private val phoneNode = "22222222-2222-4222-8222-222222222222"

    private var seq = 0

    /** 未 start 的宿主：不起节点，收帧靠 `handleOnInbound` 直接喂 —— 与真机同一条代码路径。 */
    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = 0, tcpPort = 0,
                persistMessages = false, persistMessagesDecided = true,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    private fun cardOf(cardId: String, rev: Int, state: String): JsonObject = buildJsonObject {
        put("cardId", cardId)
        put("rev", rev)
        put("type", "diff-review")
        put("state", state)
        put("title", "repo 改好了，等你合并")
    }

    private fun RelayController.cardRows(groupId: String) =
        state.value.messages[ConvId.group(groupId)].orEmpty().filter { it.renderCard != null }

    /**
     * 从「另一台设备」送一帧群文本进来，载荷带卡片。
     *
     * `from` 用群里的另一台设备（`phoneNode`）—— 本机不给自己发卡，那样测的是回环不是接收。
     */
    private fun RelayController.inboundCard(
        groupId: String,
        card: JsonObject,
        text: String = "repo 改好了，等你合并",
    ) {
        val payload = buildJsonObject {
            put("kind", MsgKinds.GROUP_TEXT)
            put("groupId", groupId)
            put("text", text)
            put("card", card)
        }
        messagesCtl.handleOnInbound(
            makeEnvelope(
                type = MsgTypes.MSG,
                from = phoneNode,
                payload = payload,
                id = "env-flip-" + (seq++),
            ),
        )
    }

    @Test
    fun aFlipFrameFromAnotherDeviceReplacesTheSameRow() = runBlocking {
        val host = controller()
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!
        // 本机先有这张卡（首帧 rev=1，待合并）。
        host.invokeCtl.broadcastBoardCard(groupId, "gpu-runner", cardOf("diff-t1", 1, "review_ready"))
        assertEquals(1, host.cardRows(groupId).size, "前提：本机已有 1 行")

        // 远端点了合并 ⇒ rev=2 的翻面帧进来。
        host.inboundCard(groupId, cardOf("diff-t1", 2, "merged"))

        val rows = host.cardRows(groupId)
        assertEquals(1, rows.size, "同 cardId 的远端帧必须落在同一个槽位，不能变成两张卡")
        val card = assertNotNull(rows.single().renderCard)
        assertEquals(
            "merged",
            (card["state"] as? JsonPrimitive)?.content,
            "别人翻的面，我这边也要翻 —— 否则「对方说合好了，我还停待合并」",
        )
    }

    @Test
    fun aStaleFrameDoesNotRollTheCardBack() = runBlocking {
        val host = controller()
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!
        host.invokeCtl.broadcastBoardCard(groupId, "gpu-runner", cardOf("diff-t1", 1, "review_ready"))
        host.invokeCtl.broadcastBoardCard(groupId, "gpu-runner", cardOf("diff-t1", 1, "merged"))
        // 本机翻完，rev 已推进到 2。
        assertEquals(
            "2",
            (assertNotNull(host.cardRows(groupId).single().renderCard)["rev"] as? JsonPrimitive)?.content,
            "前提：本机卡已到 rev=2",
        )

        // 网络乱序：一帧 rev=1 的旧卡后到。
        host.inboundCard(groupId, cardOf("diff-t1", 1, "review_ready"))

        val rows = host.cardRows(groupId)
        assertEquals(1, rows.size, "旧帧连追加都不许 —— 否则看起来像又发了一次合并请求")
        val card = assertNotNull(rows.single().renderCard)
        assertEquals(
            "merged",
            (card["state"] as? JsonPrimitive)?.content,
            "旧帧不能把状态盖回去（乱序/重放时状态只许前进）",
        )
        assertEquals("2", (card["rev"] as? JsonPrimitive)?.content)
    }
}
