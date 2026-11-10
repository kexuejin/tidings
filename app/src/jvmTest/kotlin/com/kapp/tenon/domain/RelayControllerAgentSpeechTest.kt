// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentSenderId
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 验证 [RelayController.broadcastAgentSpeech] 的拟人化发言闭环（P0b）：
 * 1. 本地聊天记录先落库再发帧，防两端不一致；
 * 2. `senderId` 必须经 [AgentSenderId.of] 归一化；
 * 3. `mine` 恒为 false（由 agent 名义发言，不能冒充机主）；
 * 4. 空发言提前抑制，不生成幽灵消息。
 */
class RelayControllerAgentSpeechTest {

    private val scope = CoroutineScope(Job())
    private val selfNodeId = "11111111-0000-4000-8000-000000000001"
    private val memberNodeId = "22222222-0000-4000-8000-000000000002"

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                nodeId = selfNodeId,
                udpPort = 0,
                tcpPort = 0,
                persistMessages = false,
                persistMessagesDecided = true,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    @Test
    fun broadcastAgentSpeechAppendsLocalAndFormatsSenderId() = runBlocking {
        val controller = controller()
        val groupId = controller.createGroup("协作群", listOf(memberNodeId))!!
        val convId = ConvId.group(groupId)

        val testText = "Implementation complete, cross-review requested."
        controller.broadcastAgentSpeech(groupId, "coder", testText)

        val messages = messagesWith(controller.state.value.toChatSlice(), convId)
        val speechMsg = messages.firstOrNull { it.text == testText }
        assertNotNull(speechMsg, "本地聊天记录必须立即落库")
        assertFalse(speechMsg.mine, "不是机主发送，mine 必须为 false")
        val expectedSender = AgentSenderId.of(selfNodeId, "coder")
        assertEquals(expectedSender, speechMsg.senderId, "senderId 必须是单一来源 AgentSenderId.of(selfNodeId, agentId)")

        // 验证空发言静默抑制，不向本地落库
        val countBefore = messages.size
        controller.broadcastAgentSpeech(groupId, "coder", "   ")
        val countAfter = messagesWith(controller.state.value.toChatSlice(), convId).size
        assertEquals(countBefore, countAfter, "空白发言必须提前被抑制，绝不写入空气泡")
    }
}
