// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 触发路由：**要不要叫 agent** 与 **谁执行** 是两件事。
 *
 * 真机现场（问题 4）：手机在群里 @dsh，桌面端 9 秒后回了
 * 「执行完毕：本地任务已成功处理。」—— 那台设备上的 `NativeRelayDriver` 什么都没做，
 * 却替远端的 DSH 报了一次完成。判据收窄后仍然要回答一个问题：**这条由谁跑？**
 *
 * 本测试钉的结论：
 * - 远端 agent 在场 ⇒ [AgentPromptRoute.Remote]，本机**不执行**（群消息已经送达它）；
 * - 只有本机 agent ⇒ Local；
 * - 都没有 ⇒ None（不许"没人也跑一遍"）；
 * - 路由**不会**因为"本机也有 agent"就两边都跑。
 *
 * 注意 [AgentPromptRoute.Remote] 只表示"消息已按群协议送达 agent 宿主"，
 * **不**表示"远端已经开始执行"（真正唤醒 DSH 需要宿主侧 prompt 注入桥，不在本协议内）。
 */
class AgentPromptRouteTest {

    private fun route(
        text: String = "@dsh 跑一下测试",
        groupId: String? = "g-1",
        isWorkspaceGroup: Boolean = false,
        repliedToIsAgent: Boolean = false,
        local: Boolean = false,
        remote: Boolean = true,
        kinds: Set<String> = setOf("dsh"),
    ): AgentPromptRoute = RelayController.agentPromptRoute(
        text = text,
        groupId = groupId,
        isWorkspaceGroup = isWorkspaceGroup,
        repliedToIsAgent = repliedToIsAgent,
        localAgentPresent = local,
        remoteAgentPresent = remote,
        agentKinds = kinds,
    )

    @Test
    fun aRemoteAgentTakesTheMessageAndTheLocalDeviceStaysOut() {
        assertEquals(AgentPromptRoute.Remote, route(local = true, remote = true))
        assertEquals(AgentPromptRoute.Remote, route(local = false, remote = true))
    }

    @Test
    fun aLocalAgentRunsOnlyWhenNoRemoteAgentIsPresent() {
        assertEquals(AgentPromptRoute.Local, route(local = true, remote = false))
    }

    @Test
    fun nobodyPresentMeansNoExecution() {
        assertEquals(AgentPromptRoute.None, route(local = false, remote = false))
    }

    /** 触发判据本身仍然唯一：普通群里不带 @ 的一句话，谁都不该被叫醒。 */
    @Test
    fun aPlainGroupMessageDoesNotWakeAnyone() {
        assertEquals(
            AgentPromptRoute.None,
            route(text = "我先去吃饭", local = true, remote = true),
        )
    }

    /** 手机端没有执行器（onAgentPrompt 恒 null）⇒ 永远不该出现 Local。 */
    @Test
    fun aDeviceWithoutAnExecutorNeverRunsLocally() {
        assertEquals(AgentPromptRoute.Remote, route(local = false, remote = true))
        assertEquals(AgentPromptRoute.None, route(text = "随便聊聊", groupId = null, local = false, remote = false))
    }

    /** 1:1：对端就是远端 agent ⇒ 本机不抢答；对端是真人时保持既有「1:1 触发本机」行为。 */
    @Test
    fun directMessagesKeepTheExistingRule() {
        assertEquals(
            AgentPromptRoute.Remote,
            route(text = "帮我看看", groupId = null, local = true, remote = true),
        )
        assertEquals(
            AgentPromptRoute.Local,
            route(text = "帮我看看", groupId = null, local = true, remote = false),
        )
    }

    /**
     * 触发面按这个会话里**有哪些 agent** 收：
     * 只有一个名为 dsh 的 agent 时，`@other` 不触发；`@DSH` 触发。
     */
    @Test
    fun onlyTheAgentsThatAreActuallyInTheConversationAreRecognised() {
        assertEquals(
            AgentPromptRoute.None,
            route(text = "@other 帮我看看"),
        )
        assertEquals(
            AgentPromptRoute.Remote,
            route(text = "@DsH 帮我看看"),
        )
    }
}
