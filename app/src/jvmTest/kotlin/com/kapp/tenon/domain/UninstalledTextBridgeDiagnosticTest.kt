// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「文案桥未装」的可观测判据（[RelayUiTexts] 事故的钉子）。
 *
 * 事故现场：桌面壳从不经 `RelayApp` 组装，`controller.uiTexts` 一直是 [NoUiTexts]，
 * 每句文案都空 —— 于是 agent 状态广播走到「空正文不发帧」闸口被**静默抑制**，
 * 本端一切正常、手机端 0 条。修复不是让空文案发声，而是让「桥没装」这个事实
 * 在抑制发生时**必须被看见**：计数只进不退 + 日志一行。
 *
 * 判据三条，缺一条这个闸就还是哑的：
 * 1. 桥未装 + 空正文 ⇒ 计数 0 → 1（抑制被记录）；
 * 2. 再触发 ⇒ 仍是 1（进程内一次性，不刷屏）；
 * 3. 装了桥（哪怕某句返回空）⇒ 计数保持 0（不误报）。
 */
class UninstalledTextBridgeDiagnosticTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

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

    /** 宿主托管的群（self 是成员），broadcastAgentState 的前置条件。 */
    private fun hostedGroup(host: RelayController): String =
        host.createGroup("mesh", listOf("11111111-1111-4111-8111-111111111111"))!!

    /** 测试要过的那道闸在 runtime 判空之后；注一个不绑端口的运行时即可。 */
    private fun attachRuntime(host: RelayController) {
        host.runtime = NodeRuntime(
            profile = Profile.agent(
                nodeId = host.state.value.selfNodeId,
                nick = "test-host", host = "127.0.0.1",
                ver = "relay-test", platform = "linux",
            ),
            udpPort = 0,
            bindHost = "127.0.0.1",
        )
    }

    @Test
    fun `suppression without bridge is recorded once`() = runBlocking {
        val host = controller()
        val groupId = hostedGroup(host)
        attachRuntime(host)

        assertEquals(0, host.invokeCtl.uninstalledTextBridgeSuppressions, "开工时桥未装、未发生抑制 ⇒ 0")
        host.invokeCtl.broadcastAgentState(groupId, "gpu-runner", "failed", "")
        assertEquals(1, host.invokeCtl.uninstalledTextBridgeSuppressions, "空正文被抑制 ⇒ 必须留下读数（原来这里是静默的）")

        host.invokeCtl.broadcastAgentState(groupId, "gpu-runner", "failed", "")
        assertEquals(1, host.invokeCtl.uninstalledTextBridgeSuppressions, "进程内一次性：再触发不叠加")
    }

    @Test
    fun `installed bridge never reported even when a phrase is empty`() = runBlocking {
        val host = controller()
        host.uiTexts = EmptyTexts
        val groupId = hostedGroup(host)
        attachRuntime(host)

        host.invokeCtl.broadcastAgentState(groupId, "gpu-runner", "failed", "")
        assertEquals(0, host.invokeCtl.uninstalledTextBridgeSuppressions, "桥装了（哪怕全返回空）⇒ 不得误报成「桥未装」")
    }

    /** 装了桥但每句都空：与「没装桥」必须可区分（误报会把正常壳标成坏壳）。 */
    private object EmptyTexts : RelayUiTexts {
        override suspend fun screenCardText(record: com.kapp.tenon.domain.model.ScreenRecordUi): String = ""
        override suspend fun nudgeNotice(): String = ""
        override suspend fun workspaceGreeting(agentKind: String, workspaceTitle: String): String = ""
        override suspend fun workspaceSessionBrief(sessionTitle: String, busy: Boolean): String = ""
        override suspend fun invokeTimeoutTitle(agentName: String): String = ""
        override suspend fun invokeActionRetryLabel(): String = ""
        override suspend fun invokeActionFallbackLabel(): String = ""
        override suspend fun tagNoMatchTitle(tag: String): String = ""
        override suspend fun agentUnavailableNotice(agentName: String): String = ""
        override suspend fun agentNotCompletedNotice(agentName: String): String = ""
        override suspend fun agentActionDeniedNotice(actor: String, agentName: String): String = ""
        override suspend fun agentEventThinkingOnlyFallback(): String = ""
    }
}
