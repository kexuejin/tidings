// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.MsgKinds
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.makeEnvelope
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.testutil.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `NO_WAKE` 的端到端探针（SPEC-ORCHESTRATION-2026-11-03 §7.4 的「四件事」）：
 * 宿主收到群里一条消息、真的走过 `handleOnInbound` 的落库与路由段（不是只调纯函数）后——
 *
 * 1. **零模型调用**：派单出口（`onAgentPrompt`）一次都不触发；
 * 2. **零新增消息行**：会话里只有用户那条消息，没有任何 agent 产物
 *    （未读只会来自消息行 —— 行为零增即未读零增）；
 * 3. **诊断环有且仅有一条 `NO_WAKE`**（经 `diagnosticLogSink` 捕获，宿主端落 `RelayLog` 环）；
 * 4. 反向对照：`@` 点名在同一条链上照常派单 —— 判据收窄没有把显式派单一起收掉。
 *
 * 为什么不用线上双节点回路：这条测试钉的是**路由决策**而不是帧投递（投递侧由
 * `ChannelHonestRejectionTest` 一族守着）；信封直接喂 `handleOnInbound` 与真机同一条
 * 代码路径（`BoardCardRemoteFlipTest` 同形夹具）。用户自己的消息**本来就要落库**
 * （落库先于路由），所以「零新增」断的是路由管线没有多写行，不是会话为空。
 */
class NoWakeDiagnosticProbeTest {
    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(persistMessages = false, persistMessagesDecided = true),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    private val peerNode = "22222222-2222-4222-8222-222222222222"

    /** 本机托管 agent 的工作区群：声明 ws + agentKind ⇒ 这个群「有 agent 在场」。 */
    private fun hostedGroup(host: RelayController): String {
        val groupId = host.createGroup("托管群", listOf(peerNode))!!
        host.recordLocalWorkspace(
            wsId = "ws-1", wsTitle = "WS", wsPath = null,
            agentKey = null, agentKind = "gpu-runner", online = true,
        )
        host.handleAddGroupAgent(groupId, "gpu-runner", null)
        return groupId
    }

    private fun groupText(id: String, from: String, groupId: String, text: String) = makeEnvelope(
        type = MsgTypes.MSG,
        id = id,
        from = from,
        payload = buildJsonObject {
            put("kind", MsgKinds.GROUP_TEXT)
            put("text", text)
            put("groupId", groupId)
            put("groupRev", 1)
        },
    )

    @Test
    fun chatterInAHostedGroupStaysSilentAndLogsExactlyOneNoWake() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        val dispatched = mutableListOf<String>()
        val logs = mutableListOf<Pair<String, String>>()
        host.onAgentPrompt = { _, _, prompt, _, _ -> dispatched.add(prompt) }
        host.diagnosticLogSink = { tag, msg -> logs.add(tag to msg) }

        host.messagesCtl.handleOnInbound(groupText("env-chatter-1", peerNode, groupId, "我先去吃饭"))

        assertTrue(dispatched.isEmpty(), "未唤醒 ⇒ 零模型调用（派单出口一次都不触发）")
        assertEquals(
            1, host.state.value.messages[ConvId.group(groupId)]?.size,
            "会话里只有用户那条闲聊 —— 唤醒管线没有写任何 agent 消息行（未读零增）",
        )
        assertEquals(1, logs.size, "诊断环有且仅有一条 NO_WAKE")
        assertEquals(RelayController.NO_WAKE_LOG_TAG, logs.single().first)
        assertEquals("groupId=$groupId", logs.single().second)
        host.stop()
    }

    @Test
    fun aWakeWithNobodyHomeIsNotANoWake() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host) // 声明了 agent，但 onAgentPrompt 不注入 ⇒ 本机没执行器
        val logs = mutableListOf<Pair<String, String>>()
        host.diagnosticLogSink = { tag, msg -> logs.add(tag to msg) }

        host.messagesCtl.handleOnInbound(groupText("env-nobody-1", peerNode, groupId, "@gpu-runner 帮我看看"))

        assertTrue(
            (host.state.value.messages[ConvId.group(groupId)]?.size ?: 0) >= 1,
            "用户那条 @ 照常落库（落库先于路由）；未授权提示可能另有一行本地 notice，不在本断言范围",
        )
        assertTrue(
            logs.none { it.first == RelayController.NO_WAKE_LOG_TAG },
            "「叫了没人应」不是 NO_WAKE（§7.2 记录条件）—— 它是另一个状态，不得混进诊断环",
        )
        host.stop()
    }

    @Test
    fun anExplicitMentionStillDispatchesThroughTheSameChain() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        val dispatched = mutableListOf<String>()
        val logs = mutableListOf<Pair<String, String>>()
        host.onAgentPrompt = { _, _, prompt, _, _ -> dispatched.add(prompt) }
        host.diagnosticLogSink = { tag, msg -> logs.add(tag to msg) }

        host.messagesCtl.handleOnInbound(groupText("env-mention-1", peerNode, groupId, "@gpu-runner 跑回归"))

        awaitUntil("显式点名照常派单") { dispatched.isNotEmpty() }
        assertEquals(
            "跑回归", dispatched.single(),
            "剥掉 @ 后的正文才是 prompt —— 判据收窄不许回退显式派单",
        )
        assertTrue(
            logs.none { it.first == RelayController.NO_WAKE_LOG_TAG },
            "被派单的消息不产生 NO_WAKE",
        )
        host.stop()
    }

    /**
     * D18：点名事实必须在剥 `@` 之前由路由点给出。编排层的「显式点名 ⇒ 工单」豁免
     * 拿的是这个参数，而不是从剥净的 prompt 里重判（那句判据曾经恒为 false）。
     */
    @Test
    fun anExplicitMentionCarriesTheAddressedKeyAlongsideTheStrippedPrompt() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        val prompts = mutableListOf<String>()
        val addressedKeys = mutableListOf<String?>()
        host.onAgentPrompt = { _, _, prompt, _, key ->
            prompts.add(prompt)
            addressedKeys.add(key)
        }

        host.messagesCtl.handleOnInbound(groupText("env-mention-key", peerNode, groupId, "@gpu-runner 跑回归"))

        awaitUntil("显式点名派单") { prompts.isNotEmpty() }
        assertEquals("跑回归", prompts.single(), "交给执行器的仍是剥净 @ 的正文")
        assertEquals(
            "gpu-runner", addressedKeys.single(),
            "点名的键必须随帧到达：丢了它，群工单句会被认知分诊改判成只读讨论（D18）",
        )
        host.stop()
    }
}
