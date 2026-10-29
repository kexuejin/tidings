// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.AgentExecAuthorizer
import com.kapp.tidings.core.protocol.AgentGrantPolicies
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.core.protocol.mentionedVirtualAgent
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **发送者视角**：我 @ 了 agent，但我不在它的授权名单里 —— 我自己该看到一行说明。
 *
 * ## 为什么必须有这一侧
 *
 * 接收侧（宿主）那条提示是「你 @ 了我但没权」，它落在**对方的机器**上。
 * 而用户 @ 完盯着**自己的**屏幕 —— 只在对方机器上留一行字，等于他永远看不到为什么没反应。
 * **那依然是静默**，也就是这个 goal 要消灭的那个东西。
 *
 * ## 判据两侧同源
 *
 * 都用 `mentionedVirtualAgent` + `AgentExecAuthorizer`，只是 `senderId` 取自己。
 * 反过来：如果两侧各写一套，就会出现「宿主说没权、本人说有权」。
 */
class SenderSideUnauthorizedNoticeTest {

    private val selfNode = "node-phone"      // 发送者（非宿主）
    private val hostNode = "node-host"       // agent 宿主

    private fun group() = GroupMeta(
        groupId = "g-1",
        name = "teahouse-mobile 协同群",
        members = listOf(selfNode, hostNode),
        rev = 9,
        updatedBy = hostNode,
        updatedTs = 1_000L,
        ownerId = hostNode,
        virtualAgents = listOf(
            VirtualAgentMember(
                agentId = "DSH",
                name = "dsh-teahouse-mobile",
                hostNodeId = hostNode,
                defaultWsId = "ws-1",
            ),
        ),
    )

    private fun tokens(meta: GroupMeta) =
        meta.virtualAgents.orEmpty().flatMap { a -> listOfNotNull(a.name, a.agentId) }

    /**
     * **`selfNodeId` 传宿主，不传发送者。**
     *
     * 这是发送侧判定的关键：`AgentExecAuthorizer` 第一档是「本机用户免问」
     * （`sender == selfNodeId` ⇒ 直接放行）。若把发送者自己当成 self，同一个人既当发送者
     * 又当「本机用户」⇒ 永远有权 ⇒ 提示永远不出现（真机第一次跑就是这样）。
     */
    private fun verdict(meta: GroupMeta, sender: String, granted: List<String> = emptyList()) =
        AgentExecAuthorizer.authorize(
            senderId = sender,
            group = meta,
            grantAllows = { it in granted },
            policy = AgentGrantPolicies.REQUEST,
            selfNodeId = hostNode,
        )

    /**
     * **发送者自己 @ 了 agent、且自己不在名单 ⇒ 命中 + 不可执行 ⇒ 他自己该看到提示。**
     *
     * 这条与接收侧那条用的是同一个判定，只是 `senderId` 换成自己 —— 那正是「同源」。
     */
    @Test
    fun theSenderItselfIsUnauthorizedAndMentioned() {
        val meta = group()
        val hit = meta.mentionedVirtualAgent("@dsh-teahouse-mobile sender-notice", tokens(meta))
        assertNotNull(hit, "发送者打的长 token 必须命中")
        assertTrue(
            !AgentExecAuthorizer.run { verdict(meta, selfNode).permitsExecution() },
            "发送者不是宿主、也不在名单 ⇒ 不可执行（他自己该看到说明）",
        )
    }

    /** 批准之后，发送者自己不该再看到提示。 */
    @Test
    fun afterGrantTheSenderSeesNothing() {
        val meta = group()
        assertTrue(
            AgentExecAuthorizer.run {
                verdict(meta, selfNode, granted = listOf(selfNode)).permitsExecution()
            },
            "批准后放行 —— 否则提示会一直挂着，教用户忽略它",
        )
    }

    /**
     * **宿主自己 @ 不需要提示**（它本来就有权）。
     *
     * 与上面分开的理由：如果提示不看「谁发的」，宿主自己发群里的话也会挂一行「你没权限」——
     * 那是最典型的误伤。
     */
    @Test
    fun theHostMentioningItselfNeedsNoNotice() {
        val meta = group()
        assertTrue(
            AgentExecAuthorizer.run { verdict(meta, hostNode).permitsExecution() },
            "宿主自己 @ 自己必须放行，否则会误伤",
        )
    }

    /** 没有 @ 到 agent 的群聊不产生提示。 */
    @Test
    fun plainChatProducesNoNotice() {
        assertNull(group().mentionedVirtualAgent("这一轮把插件推进到能互相拨通", tokens(group())))
    }
}
