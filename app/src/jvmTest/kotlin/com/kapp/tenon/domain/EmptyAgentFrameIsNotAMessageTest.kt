// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **零正文的 agent 事件帧不进会话流**（真机 A22：「会话列表还显示没聊过，这个错了吧」）。
 *
 * ## 真身
 *
 * agent 的事件流里有一批只有结构、没有正文的帧（工具调用开始/结束、空增量）。它们过去
 * 照常 append 成 `MessageEntity` 的一行：气泡靠 `MessageBubble` 的空气泡防线看不见，
 * 但**会话列表预览**取的是"最新一条"—— 最新一条是空帧时预览就是 null，
 * 会话行于是写「还没聊过，打个招呼」，而上面躺着上百条真消息。
 *
 * 判据只有一条（`MessageUi.hasRenderableContent`），写入/读回/摘要三处共用；
 * 这里钉写入侧，`SqlDelightMessageStoreSummaryTest` 钉读回与摘要。
 *
 * 反向验证：去掉 `handleAppendMessage` 开头那行 return，本用例变红。
 */
class EmptyAgentFrameIsNotAMessageTest {

    private val peer = "peer-a"

    @Test
    fun 零正文的消息不产生气泡也不计入未读() = runBlocking {
        val controller = controller()

        controller.appendMessage(
            MessageUi(id = "e1", peerId = peer, mine = false, text = "", at = 1_000L),
            incoming = true,
        )

        val thread = controller.state.value.messages[peer].orEmpty()
        assertTrue(thread.isEmpty(), "零正文的事件帧不该成为一条消息（界面会多出空气泡、列表预览会被它占住）")
        assertTrue(
            (controller.state.value.unreadByPeer[peer] ?: 0) == 0,
            "空帧也不该计未读 —— 用户点进去什么也看不到",
        )
    }

    @Test
    fun 有文件卡的消息仍然照常落进会话() = runBlocking {
        val controller = controller()

        controller.appendMessage(
            MessageUi(
                id = "f1",
                peerId = peer,
                mine = false,
                text = "",
                at = 1_000L,
                file = com.kapp.tenon.domain.model.FileCardUi(
                    transferId = "t-1",
                    name = "patch.diff",
                    size = 166L,
                ),
            ),
            incoming = true,
        )

        assertTrue(
            controller.state.value.messages[peer].orEmpty().any { it.id == "f1" },
            "有正文以外内容的消息（文件卡/卡片/通知）不能被当成空帧丢掉",
        )
    }

    private fun CoroutineScope.controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(peer),
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
