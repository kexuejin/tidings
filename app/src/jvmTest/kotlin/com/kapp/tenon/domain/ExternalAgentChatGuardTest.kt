// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.nowMillis
import com.kapp.tenon.core.protocol.Envelope
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.domain.model.NoticeKind
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalAgentChatGuardTest {

    private val scope = CoroutineScope(Job())

    private fun createController(allowExternalPrivateChat: Boolean): RelayController {
        val store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = 0,
                tcpPort = 0,
                persistMessages = false,
                persistMessagesDecided = true,
                allowExternalPrivateChat = allowExternalPrivateChat,
            ),
        )
        return RelayController(
            platform = InMemoryAppPlatform(),
            store = store,
            messageStore = InMemoryMessageStore(),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
    }

    @Test
    fun humanDesktopNodeSendingOneOnOneNeverBlockedEvenIfItAdvertisesAgentCap() = runBlocking {
        val controller = createController(allowExternalPrivateChat = false)
        val desktopNodeId = "desktop-peer-node-1"

        val envelope = Envelope(
            v = 1,
            type = MsgTypes.MSG,
            id = "msg-human-1",
            from = desktopNodeId,
            ts = nowMillis(),
            payload = buildJsonObject {
                put("text", "Hello, please review this PR")
            },
        )

        controller.messagesCtl.handleOnInbound(envelope)

        val messages = controller.state.value.messages[desktopNodeId].orEmpty()
        assertTrue(messages.any { it.text == "Hello, please review this PR" }, "普通 1:1 私聊必须正常落库")
        assertFalse(
            messages.any { it.notice == NoticeKind.ExternalChatDisabled || it.notice == NoticeKind.AgentNotAuthorized },
            "人类桌面 1:1 绝不得被误判为外部 Agent 私聊，绝不产生错误 notice",
        )
        controller.stop()
    }

    @Test
    fun externalAgentChatBlockedAndProducesExternalChatDisabledNoticeWhenSwitchIsOff() = runBlocking {
        val controller = createController(allowExternalPrivateChat = false)
        val externalAgentConvId = "agent:remote-node-2/coder"

        controller.recordLocalWorkspace(
            wsId = "ws-local",
            wsTitle = "Local Workspace",
            wsPath = null,
            agentKey = null,
            agentKind = "coder",
            online = true,
        )

        var prompted = false
        controller.onAgentPrompt = { _, _, _, _, _ -> prompted = true }

        val envelope = Envelope(
            v = 1,
            type = MsgTypes.MSG,
            id = "msg-external-agent-1",
            from = externalAgentConvId,
            ts = nowMillis(),
            payload = buildJsonObject {
                put("text", "@coder analyze repo")
            },
        )

        controller.messagesCtl.handleOnInbound(envelope)

        val messages = controller.state.value.messages[externalAgentConvId].orEmpty()
        assertTrue(
            messages.any { it.notice == NoticeKind.ExternalChatDisabled },
            "未开启开关时外部 Agent 单聊必须被拦截，且落专用 NoticeKind.ExternalChatDisabled",
        )
        assertFalse(
            messages.any { it.notice == NoticeKind.AgentNotAuthorized },
            "绝不复用群权限的 AgentNotAuthorized",
        )
        assertFalse(prompted, "被拦截的外部单聊绝不向本机 Agent 派单")
        controller.stop()
    }

    @Test
    fun externalAgentChatDispatchedWhenSwitchIsOn() = runBlocking {
        val controller = createController(allowExternalPrivateChat = true)
        val externalAgentConvId = "agent:remote-node-3/coder"

        controller.recordLocalWorkspace(
            wsId = "ws-local-2",
            wsTitle = "Local Workspace 2",
            wsPath = null,
            agentKey = null,
            agentKind = "coder",
            online = true,
        )

        var promptReceived: String? = null
        controller.onAgentPrompt = { _, _, prompt, _, _ -> promptReceived = prompt }

        val envelope = Envelope(
            v = 1,
            type = MsgTypes.MSG,
            id = "msg-external-agent-2",
            from = externalAgentConvId,
            ts = nowMillis(),
            payload = buildJsonObject {
                put("text", "@coder build release")
            },
        )

        controller.messagesCtl.handleOnInbound(envelope)

        val messages = controller.state.value.messages[externalAgentConvId].orEmpty()
        assertFalse(
            messages.any { it.notice == NoticeKind.ExternalChatDisabled },
            "开关开启时外部 Agent 单聊不被拦截",
        )
        controller.stop()
    }
}
