// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.AgentExecAuthorizer
import com.kapp.tidings.core.protocol.AgentExecPolicies
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.core.protocol.mentionedVirtualAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **「@ 到了 agent 但没有执行权」必须能被识别出来，而不是静默丢弃。**
 *
 * ## 真机现场
 *
 * 红米在群里发 `@dsh-teahouse-mobile 可以继续`，消息正常显示，然后**什么都没发生** ——
 * 没有执行、也没有任何提示。用户只能以为 agent 收到了在忙，或者坏了。
 *
 * 而系统其实知道确切原因：`AgentGrantEntity` 是空的 ⇒ `AgentExecAuthorizer` 返回
 * `MemberOnly` ⇒ `permitsExecution()=false` ⇒ `agentHint=null` ⇒ 桥接侧永远看不到这条。
 *
 * ## 这条测的是判据本身
 *
 * `unathorizedAgentMention` 需要整个 `RelayController`，这里不构造它 —— 那会把 3000 行的
 * 控制器拖进单测。测的是**它依赖的两级判据**：mention 命中（token 匹配）与授权档位。
 * 两者一致才会产生提示，任一处不同都会让「提示说没权、其实有权」。
 */
class AgentNotAuthorizedNoticeTest {

    private val selfNode = "node-self"
    private val phoneNode = "node-phone"
    private val agentHost = "node-host"

    private fun group(withAgent: Boolean = true) = GroupMeta(
        groupId = "g-1",
        name = "teahouse-mobile 协同群",
        ownerId = agentHost,
        members = listOf(selfNode, agentHost, phoneNode),
        rev = 1,
        updatedBy = agentHost,
        updatedTs = 1_000L,
        virtualAgents = if (withAgent) {
            listOf(
                VirtualAgentMember(
                    agentId = "DSH",
                    name = "dsh-teahouse-mobile",
                    hostNodeId = agentHost,
                    wsId = "ws-1",
                ),
            )
        } else {
            emptyList()
        },
    )

    private fun tokens(g: GroupMeta) =
        g.virtualAgents.orEmpty().flatMap { a -> listOfNotNull(a.name, a.agentId) }

    /**
     * **非宿主、非名单成员 @ 了 agent ⇒ 命中 mention，但授权档位不放行。**
     *
     * 这时才该出「未执行」的提示。两个条件缺一不可。
     */
    @Test
    fun aPlainMemberMentioningTheAgentIsBlocked() {
        val g = group()
        val hit = g.mentionedVirtualAgent("@dsh-teahouse-mobile 可以继续", tokens(g))
        assertEquals("dsh-teahouse-mobile", hit?.name, "长 token 必须命中")

        val verdict = AgentExecAuthorizer.authorize(
            senderId = phoneNode,
            group = g,
            grantAllows = null,
            policy = null,
            selfNodeId = selfNode,
        )
        assertTrue(
            !AgentExecAuthorizer.run { verdict.permitsExecution() },
            "普通成员没被授权 ⇒ 不可执行（这正是真机上那条 @ 的处境）",
        )
    }

    /**
     * **agent 宿主自己 @ 它 ⇒ 可执行，不该出提示。**
     *
     * 这条钉住「提示不会误伤正常路径」：否则用户在自己机器上 @ 自己也会看到「你没权限」。
     */
    @Test
    fun theAgentHostItselfIsAllowed() {
        val g = group()
        val verdict = AgentExecAuthorizer.authorize(
            senderId = agentHost,
            group = g,
            selfNodeId = selfNode,
        )
        assertTrue(
            AgentExecAuthorizer.run { verdict.permitsExecution() },
            "宿主自己 @ 自己必须放行",
        )
    }

    /** `ANY_MEMBER` 档下普通成员也可执行 ⇒ 不该出提示。 */
    @Test
    fun anyMemberPolicyAllowsAPlainMember() {
        val g = group()
        val verdict = AgentExecAuthorizer.authorize(
            senderId = phoneNode,
            group = g,
            policy = AgentExecPolicies.ANY_MEMBER,
            selfNodeId = selfNode,
        )
        assertTrue(AgentExecAuthorizer.run { verdict.permitsExecution() })
    }

    /**
     * **没有 @ 到任何 agent ⇒ 不出提示。**
     *
     * 群里别人之间的闲聊不该被这条提示打扰 —— 那会是每句话后面挂一句「未执行」。
     */
    @Test
    fun aMessageThatDoesNotMentionAnyAgentIsSilent() {
        val g = group()
        assertNull(
            g.mentionedVirtualAgent("今天天气不错", tokens(g)),
            "没 @ 任何人时不该命中 ⇒ 也就不出提示",
        )
    }

    /** 群里没有声明任何 agent ⇒ 命中不了，也不出提示。 */
    @Test
    fun aGroupWithoutVirtualAgentsNeverNotifies() {
        val g = group(withAgent = false)
        assertTrue(tokens(g).isEmpty())
        assertNull(g.mentionedVirtualAgent("@dsh-teahouse-mobile 你好", tokens(g)))
    }
}
