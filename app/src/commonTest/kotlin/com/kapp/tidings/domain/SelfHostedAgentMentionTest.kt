// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **本机的 agent 在群里必须能被 @。**
 *
 * 真身（用户截图）：群 `deepseek-harness 协同群` 里输入 `@`，候选只有人类成员，
 * 没有任何 agent。数据全都在 —— 宿主 `a1b2c3d4-…` 有 127 条 agent 会话、16 个工作区，
 * 而且它就是群成员。
 *
 * 真正的原因：**那台宿主就是桌面端自己**（`diag/link.json` 的 `self` 正是 `a1b2c3d4-…`）。
 * `resolveNodeIdentity` 对自己标 `isSelf = true`，而 `canMention = !isSelf` ——
 * 于是「我自己的 agent」被当成「我自己」排除掉了。
 *
 * 但这两件事不是一回事：
 * - 人是自己 ⇒ 给自己发 @ 没意义（**不该出现在候选里**）；
 * - 自己那台机器上的 agent ⇒ 它是**另一个人格**，在群里说话时是独立的发送者，
 *   用户完全需要 @ 它（这正是触发它干活的入口）。
 */
class SelfHostedAgentMentionTest {

    private val self = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"
    private val other = "f6a7b8c9-ede7-4ed1-84a4-4941d21cf81d"

    private fun ctx(): SenderIdentityContext {
        val group = GroupMeta(
            groupId = "g-1",
            name = "deepseek-harness 协同群",
            members = listOf(self, other),
            rev = 1,
            updatedBy = self,
            updatedTs = 0L,
            // **本机的 agent 入驻了这个群**（用户点过「邀请新智能体入驻」）。
            // 群里的 agent 唯一来源是这条声明 —— 光「宿主有 kind」不算入驻。
            virtualAgents = listOf(
                VirtualAgentMember(
                    agentId = "dsh",
                    hostNodeId = self,
                    name = "dsh-teahouse-mobile",
                    defaultWsId = "ws-teahouse",
                ),
            ),
        )
        return SenderIdentityContext(
            selfNodeId = self,
            selfNick = "demo-mac-97",
            peers = emptyList(),
            group = group,
            // 桌面端自己就是 agent 宿主（kind 存的是大写 DSH）。
            agentKindsByHost = mapOf(self to setOf("DSH")),
            agentAliasesByHost = mapOf(self to "DSH"),
            agentSenderIds = setOf("agent:$self:dsh"),
        )
    }

    /** 本机托管的 agent 必须出现在群 @ 候选里。 */
    @Test
    fun theAgentHostedOnThisDeviceIsMentionable() {
        val rows = groupParticipantRows(ctx())
        assertTrue(
            rows.agents.any { it.canMention },
            "桌面端自己就是 agent 宿主，它托管的 agent 必须能被 @；实得 agents=${rows.agents.map { it.mentionToken }}",
        )
    }

    /**
     * **修这条不能把「@ 自己」也放出来**。
     *
     * `humans` 那段照旧含自己（群成员表里本来就有本机），但 `canMention` 必须是 false ——
     * 上层的 `.filter { it.canMention }` 会把它筛掉。
     *
     * 注：本机**作为人**在 humans 里是正确行为（成员面板要显示「我」）；
     * 真正要保证的是「它进不了 @ 候选」，判据就看 canMention。
     */
    @Test
    fun theHumanSelfIsStillNotMentionable() {
        val rows = groupParticipantRows(ctx())
        val selfHuman = rows.humans.firstOrNull { it.isSelf }
        assertTrue(selfHuman != null, "本机应当作为人在成员列表里")
        assertTrue(!selfHuman!!.canMention, "但本机（人）不该进 @ 候选")
    }
}
