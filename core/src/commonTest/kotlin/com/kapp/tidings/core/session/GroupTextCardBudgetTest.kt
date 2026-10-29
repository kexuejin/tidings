// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.DecodeResult
import com.kapp.tidings.core.protocol.Limits
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.transport.InMemoryDedupStore
import com.kapp.tidings.core.transport.InMemoryMessageStore
import com.kapp.tidings.core.transport.InMemoryQueueStore
import com.kapp.tidings.core.transport.Messenger
import com.kapp.tidings.core.transport.PeerRegistry
import com.kapp.tidings.core.transport.SendOutcome
import com.kapp.tidings.core.transport.UdpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 卡片预算的**发送侧不变量**：超限一律拒绝、绝不截断（AGENTS §2.2 的
 * 「发送侧不产出注定被拒的帧」）。
 *
 * 为什么不是截断：卡片是一个 JSON 对象，截断之后它就**解不了** —— 「把截断的卡片
 * 发出去」等于自己生产一帧注定被对端判 bad-payload 的帧，而发送侧拿不到任何错误。
 * 所以超限必须在发送动作处**大声失败**（抛出），由调用方（宿主驱动）回错误事件给
 * 插件、让插件瘦身重发 —— 那是产品的错误路径；这里只负责把「注定被拒的帧」挡在
 * 线路与本地历史之外。
 *
 * 拒绝必须发生在**落库之前**：先落库再抛，本地历史里就多了一条永远不会有人收到的
 * 卡片消息（界面显示已发送、对端什么都没有 —— 本仓最贵的那类缺陷）。
 */
class GroupTextCardBudgetTest {

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
        caps = MsgTypes.MOBILE_CAPS,
    )

    /**
     * 只建不发：transport / messenger 不 start —— 本文件的用例要么在变体构造处
     * 抛出，要么成员表只有自己（扇出对空目标集直接返回 SENT），都不碰网络。
     */
    private fun rig(): Pair<NodeOutbound, InMemoryMessageStore> {
        val store = InMemoryMessageStore()
        val outbound = NodeOutbound(
            messenger = Messenger(
                transport = UdpTransport(port = 0, bindHost = "127.0.0.1"),
                registry = PeerRegistry("self"),
                selfNodeId = "self",
                queueStore = InMemoryQueueStore(),
                dedupStore = InMemoryDedupStore(),
            ),
            messageStore = store,
            selfId = { "self" },
            selfProfile = { profile("self") },
        )
        return outbound to store
    }

    private fun oversizedCard(): JsonObject = buildJsonObject {
        put("title", "汉".repeat(Limits.CARD_BYTES)) // 每字 3 字节，必超 24 KiB
    }

    @Test
    fun variantsRefuseAnOversizedCard() {
        assertFailsWith<IllegalArgumentException>("超限卡片必须在变体构造处大声失败") {
            groupTextVariants(
                from = "self",
                text = "看板",
                groupId = "grp-1",
                groupRev = 1,
                card = oversizedCard(),
            )
        }
    }

    /** 超限卡片：发送动作整体拒绝，且**本地历史一行都不落**。 */
    @Test
    fun sendGroupTextRefusesAnOversizedCardWithoutPersisting() = runBlocking {
        val (outbound, store) = rig()
        assertFailsWith<IllegalArgumentException> {
            outbound.sendGroupText(
                text = "看板",
                groupId = "grp-1",
                groupRev = 1,
                members = listOf("self"),
                card = oversizedCard(),
            )
        }
        assertEquals(
            0,
            store.since("group:grp-1", 0, 10).size,
            "被拒的卡片不许进本地历史：先落库再抛 = 界面显示已发送、对端什么都没有",
        )
    }

    /** 预算内的卡片正常发送并落库，落库的那份（全量变体）必须可被 decodeTcp 重放。 */
    @Test
    fun aCardWithinBudgetSendsAndLandsInHistory() = runBlocking {
        val (outbound, store) = rig()
        val card = buildJsonObject {
            put("type", "team-task-board")
            put("cardId", "task-89")
            put("rev", 3)
        }
        val outcome = outbound.sendGroupText(
            text = "看板",
            groupId = "grp-1",
            groupRev = 1,
            members = listOf("self"),
            card = card,
        )
        assertEquals(SendOutcome.SENT, outcome, "单人群（只有自己）直接 SENT")
        val rows = store.since("group:grp-1", 0, 10)
        assertEquals(1, rows.size, "本地历史只落一条")
        assertTrue(
            Codec.decodeTcp(rows.single().envelopeJson) is DecodeResult.Ok,
            "落库的全量变体必须能被队列重放路径解码（decodeTcp），否则补发时会被当坏帧删掉",
        )
        val landed = (Codec.decodeTcp(rows.single().envelopeJson) as DecodeResult.Ok).env.payload.jsonObject
        assertEquals(
            "task-89",
            ((landed["card"] as? JsonObject ?: fail("落库帧必须带卡片：$landed"))["cardId"] as JsonPrimitive).content,
        )
    }
}
