// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.domain.model.FileCardUi
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.PeerUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 一句消息**归谁**的钉子（真机 A22 的三个用户提问：agent 的话归属谁 / 文件卡没归属 /
 * 会话里"看起来都是对端发的"）。
 *
 * 真身：气泡渲染过去写死了
 * `group != null ? resolveSenderIdentity(senderId) : resolveNodeIdentity(peer.nodeId)`，
 * 而且单聊的 `senderName` 恒为 null —— 于是 1:1 直派会话里，agent 以
 * `agent:<宿主>:dsh` 署名发回的「Ticket accepted…」「改动已就绪…」全被算成
 * **对端那台设备**，界面上一个名字都没有。
 *
 * 反向验证：把 `ChatSenderAttribution` 退回上面那一行写法，
 * `一个单聊里由 agent 署名的回复归给 agent 不归给对端设备` 与
 * `单聊里的文件卡归给对端的 agent 身份` 两条会变红。
 */
class ChatSenderAttributionTest {

    private val self = "self-node-0001"
    private val host = "demo-mac-97-node"
    private val friend = "friend-phone-node"

    private fun peer(nodeId: String, nick: String, isAgent: Boolean = false) = PeerUi(
        nodeId = nodeId,
        nick = nick,
        remark = "",
        online = true,
        avatar = -1,
        caps = if (isAgent) listOf(MsgTypes.CAP_AGENT) else emptyList(),
    )

    /** 桌面宿主：跑着 dsh。`bareNodeIdIsHuman` 的两种口径由调用点各给一份。 */
    private fun ctx(peers: List<PeerUi> = listOf(peer(host, "demo-mac-97", isAgent = true))) =
        SenderIdentityContext(
            selfNodeId = self,
            selfNick = "我自己",
            peers = peers,
            group = null,
            agentKindsByHost = mapOf(host to setOf("dsh")),
            agentAliasesByHost = mapOf(host to "DSH"),
            agentSenderIds = setOf("agent:$host:dsh"),
        )

    private fun msg(
        senderId: String?,
        text: String = "改动已就绪：1 个文件（+1 −0）",
        file: FileCardUi? = null,
        mine: Boolean = false,
    ) = MessageUi(
        id = "m-" + (senderId ?: "none") + (file?.name ?: ""),
        peerId = host,
        mine = mine,
        text = text,
        at = 1_000L,
        senderId = senderId,
        file = file,
    )

    private fun identityOf(
        message: MessageUi,
        isGroup: Boolean = false,
        peerNodeId: String = host,
        base: SenderIdentityContext = ctx(),
    ) = ChatSenderAttribution.identityOf(
        message = message,
        isGroup = isGroup,
        peerNodeId = peerNodeId,
        identityCtx = base,
        bubbleCtx = base.copy(bareNodeIdIsHuman = true),
    )

    @Test
    fun 一个单聊里由agent署名的回复归给agent不归给对端设备() {
        val identity = assertNotNull(identityOf(msg(senderId = "agent:$host:dsh")))

        assertEquals("dsh", identity.displayName, "单聊里 agent 说的话必须署名 agent 自己")
        assertTrue(identity.isAgent)
        assertTrue(
            ChatSenderAttribution.showsName(msg(senderId = "agent:$host:dsh"), isGroup = false, identity = identity),
            "单聊里 agent 的消息必须露出名字 —— 否则一屏消息全都'没有归属'",
        )
    }

    @Test
    fun 单聊里人对端的普通消息不署名() {
        val message = msg(senderId = friend, text = "在吗")
        val identity = assertNotNull(identityOf(message, peerNodeId = friend, base = ctx()))

        assertFalse(identity.isAgent)
        assertFalse(
            ChatSenderAttribution.showsName(message, isGroup = false, identity = identity),
            "人对端不署名：会话标题已经写了他是谁，再署一次是同一件事的第二个说法",
        )
    }

    @Test
    fun 单聊里的文件卡归给对端的agent身份() {
        // 文件卡没有 senderId 的旧形态已经不成立（offer 落卡时带上了 peerId），
        // 这里钉的是"产物按机器/agent 身份解析"，而不是"人对端"那一档 ——
        // `bareNodeIdIsHuman` 会把 agent 宿主还原成人名，产物不该走那条。
        val message = msg(
            senderId = host,
            text = "",
            file = FileCardUi(transferId = "t-1", name = "patch.diff", size = 166L),
        )
        val identity = assertNotNull(identityOf(message))

        assertEquals("dsh", identity.displayName, "宿主上的产物归属它跑的 agent，不是机器名")
        assertTrue(identity.isAgent)
        assertTrue(
            ChatSenderAttribution.showsName(message, isGroup = false, identity = identity),
            "文件卡必须有归属 —— 用户看到的就是三张无主卡片",
        )
    }

    @Test
    fun 自己的消息恒无发送者() {
        val message = msg(senderId = "agent:$host:dsh", mine = true)
        assertEquals(null, identityOf(message))
        assertFalse(ChatSenderAttribution.showsName(message, isGroup = true, identity = null))
    }

    @Test
    fun 群聊里人对端必须署名() {
        val message = msg(senderId = friend, text = "周五聚餐")
        val identity = assertNotNull(identityOf(message, isGroup = true, peerNodeId = friend))

        assertTrue(
            ChatSenderAttribution.showsName(message, isGroup = true, identity = identity),
            "群里不署名就分不清是谁说的",
        )
    }

    @Test
    fun 没有senderId的老端帧归给对端的身份() {
        // 老端（协议里还没有 senderId）与设备级消息：只能按"对端是谁"来归。
        // 对端是 agent 宿主时，这个会话的对端身份就是那台机器上的 agent（`dsh`），
        // 而不是机器名 —— 与头部标题、文件卡同一个口径。
        val identity = assertNotNull(identityOf(msg(senderId = null, text = "老端发的一句话")))
        assertEquals("dsh", identity.displayName, "agent 宿主的会话里，设备级消息归属它的 agent")
        assertTrue(identity.isAgent)
    }

    @Test
    fun 人对端的老端帧仍然只显示人名且不署名() {
        val base = ctx(peers = listOf(peer(friend, "老周")))
        val message = msg(senderId = null, text = "老端发的一句话")
        val identity = assertNotNull(identityOf(message, peerNodeId = friend, base = base))

        assertEquals("老周", identity.displayName)
        assertFalse(identity.isAgent)
        assertFalse(ChatSenderAttribution.showsName(message, isGroup = false, identity = identity))
    }
}
