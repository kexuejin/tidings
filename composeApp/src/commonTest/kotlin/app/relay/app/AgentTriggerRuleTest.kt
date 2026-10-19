// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.MessageUi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「这条消息要不要触发宿主侧的 agent 执行」的判据。
 *
 * 判据只有一份（[RelayController.shouldTriggerAgent]），三个触发点都调它。这条测试钉的是
 * **它自己的语义**，因为这条规则改过一次、而且改之前是真机上看反过的：
 *
 * - 改之前：**1:1 要 `@dsh`**（点进跟宿主私聊还得再 @ 一遍），而**工作区群每条都触发**。
 * - 改之后：1:1 每条都触发；群要么 `@dsh` 点名、要么（工作区群里）**回复 agent 那条**。
 *
 * 为什么必须改：触发之后跑的是宿主本地的执行能力（默认驱动 `NativeRelayDriver`：`bash` /
 * 文件读写 / 代码搜索）。**最宽的触发原本挂在人最多的那个频道上**，而最直接的那条
 * （1:1 找 agent）反而要额外点名。真机实测：一条不带 @ 的群消息，1 秒后 `agent:native` 就回了。
 */
class AgentTriggerRuleTest {

    @Test
    fun aOneToOneMessageAlwaysTriggers() {
        // 1:1：进这个会话本身就是在指名 agent，没有第三方旁观。
        assertTrue(RelayController.shouldTriggerAgent("随便聊两句", groupId = null, isWorkspaceGroup = false, repliedToIsAgent = false))
        assertTrue(RelayController.shouldTriggerAgent("@dsh 跑一下测试", groupId = null, isWorkspaceGroup = false, repliedToIsAgent = false))
    }

    @Test
    fun anOrdinaryGroupNeedsTheMention() {
        assertTrue(RelayController.shouldTriggerAgent("@dsh 帮我看看", groupId = "g-1", isWorkspaceGroup = false, repliedToIsAgent = false))
        assertFalse(
            RelayController.shouldTriggerAgent("我先去吃饭", groupId = "g-1", isWorkspaceGroup = false, repliedToIsAgent = false),
            "普通群里闲聊不该拉起宿主的 bash / 文件读写",
        )
        // 「回复 agent 那条」在**普通群**里不算触发 —— 那是工作区群才有的接话语义。
        assertFalse(
            RelayController.shouldTriggerAgent("好", groupId = "g-1", isWorkspaceGroup = false, repliedToIsAgent = true),
            "普通群里的接话不该变成一次执行",
        )
    }

    @Test
    fun aWorkspaceGroupTriggersOnTheMentionOrOnAPickUp() {
        assertTrue(
            RelayController.shouldTriggerAgent("@dsh 继续", groupId = "ws-1", isWorkspaceGroup = true, repliedToIsAgent = false),
            "点名永远算",
        )
        assertTrue(
            RelayController.shouldTriggerAgent("那就按这个来吧", groupId = "ws-1", isWorkspaceGroup = true, repliedToIsAgent = true),
            "接 agent 的话不必再 @ 一遍 —— 否则收窄群规则就成了纯损失",
        )
        assertFalse(
            RelayController.shouldTriggerAgent("我先去吃饭", groupId = "ws-1", isWorkspaceGroup = true, repliedToIsAgent = false),
            "工作区群里**别人之间的闲聊**不该触发 —— 这条正是这次改掉的东西",
        )
    }

    @Test
    fun theMentionMatchIsCaseInsensitiveAndWordBounded() {
        assertTrue(RelayController.isMentioningDsh("@dsh 在吗"))
        assertTrue(RelayController.isMentioningDsh("@DSH 在吗"))
        assertTrue(RelayController.isMentioningDsh("hey @Dsh 在吗"))
        assertFalse(RelayController.isMentioningDsh("@dshx 这不是点名"), "词边界：@dshx 不是 @dsh")
        assertFalse(RelayController.isMentioningDsh("dsh 没有 @"))
    }

    @Test
    fun onlyAnAgentAuthoredMessageCountsAsTheReplyTarget() {
        fun msg(senderId: String?) = MessageUi(id = "m", peerId = "group:g-1", mine = false, text = "x", at = 1, senderId = senderId)
        assertTrue(RelayController.isAgentAuthored(msg("agent:dsh")))
        assertTrue(RelayController.isAgentAuthored(msg("agent:native")))
        assertFalse(RelayController.isAgentAuthored(msg("bd2d73c5-0ee0-4709-9600-02f650312115")), "真人 nodeId 不算")
        assertFalse(RelayController.isAgentAuthored(msg(null)))
        assertFalse(RelayController.isAgentAuthored(null), "找不到那条消息 = 不是接 agent 的话")
    }
}
