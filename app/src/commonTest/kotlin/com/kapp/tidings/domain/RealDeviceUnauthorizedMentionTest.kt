// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.AgentExecAuthorizer
import com.kapp.tidings.core.protocol.AgentGrantPolicies
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.core.protocol.mentionedVirtualAgent
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **用真机上那组真实 id 复现整条判定链**（不是构造出来的假数据）。
 *
 * ## 为什么值得单独一个类
 *
 * `AgentNotAuthorizedNoticeTest` 用的是通用 id，验证的是「机制成立」。这里用的是
 * **真机上实际出现的那一组**（Mac / 红米 / 群 id / agent 名），验证的是「那一次到底
 * 判定成了什么」—— 两者失败方式不同：前者保证逻辑对，后者保证**这个具体 bug 修好了**。
 *
 * ## 真机现场（2026-10-23，可对照库里的数据）
 *
 *     groupId      a6badc8f-e81c-44d7-a0d8-6ad31d9843ef
 *     Mac(宿主)    a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6
 *     红米(发送者)  d2f4404a-2298-46a1-b1e0-05779f0b1490
 *     agent        dsh-teahouse-mobile / agentId DSH
 *     台账         该群请求策略 = request；红米**不在** grants 名单里
 *     消息         `@dsh-teahouse-mobile 可以继续`
 *
 * 结果：命中 mention ⇒ 授权 `MemberOnly` ⇒ `agentHint=null` ⇒ 桥接侧看不到 ⇒ 零回音。
 */
class RealDeviceUnauthorizedMentionTest {

    private val groupId = "a6badc8f-e81c-44d7-a0d8-6ad31d9843ef"
    private val macNode = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"
    private val redmiNode = "d2f4404a-2298-46a1-b1e0-05779f0b1490"

    private fun realGroup(grants: List<String> = emptyList()) = GroupMeta(
        groupId = groupId,
        name = "teahouse-mobile 协同群",
        members = listOf(macNode, redmiNode),
        rev = 9,
        updatedBy = macNode,
        updatedTs = 1_000L,
        ownerId = macNode,
        virtualAgents = listOf(
            VirtualAgentMember(
                agentId = "DSH",
                name = "dsh-teahouse-mobile",
                hostNodeId = macNode,
                wsId = "ws-e9b18063d183",
            ),
        ),
    )

    private fun tokensOf(meta: GroupMeta) =
        meta.virtualAgents.orEmpty().flatMap { a -> listOfNotNull(a.name, a.agentId) }

    private fun verdict(meta: GroupMeta, sender: String, granted: List<String> = emptyList()) =
        AgentExecAuthorizer.authorize(
            senderId = sender,
            group = meta,
            grantAllows = { it in granted },
            policy = AgentGrantPolicies.REQUEST,
            selfNodeId = macNode,
        )

    /**
     * **真机那一次：@ 命中，但红米不在授权名单 ⇒ 不可执行 ⇒ 该出提示。**
     *
     * 这是整个 goal 的起点断言：只要它变成 `permitsExecution()=true`，那条「没有执行」
     * 的提示就不该出现 —— 反之亦然。
     */
    @Test
    fun theRealRedmiMentionIsRecognizedButNotPermitted() {
        val meta = realGroup()
        val hit = meta.mentionedVirtualAgent("@dsh-teahouse-mobile 可以继续", tokensOf(meta))
        assertTrue(hit != null, "真机那条 @ 必须命中 —— 否则连提示都出不来")

        assertTrue(
            !AgentExecAuthorizer.run { verdict(meta, redmiNode).permitsExecution() },
            "红米不在名单里 ⇒ 不可执行（这正是那一次「零回音」的原因）",
        )
    }

    /** 授权之后同一条消息就该被执行 —— 说明提示的出现条件随台账变化而正确翻转。 */
    @Test
    fun grantingTheDeviceFlipsItToPermitted() {
        val meta = realGroup()
        assertTrue(
            AgentExecAuthorizer.run {
                verdict(meta, redmiNode, granted = listOf(redmiNode)).permitsExecution()
            },
            "批准之后必须放行 —— 否则「申请授权」这个入口等于白给",
        )
    }

    /** 宿主自己 @ 自己不需要授权（本来就在这台机器上）。 */
    @Test
    fun theHostItselfIsAlwaysPermitted() {
        val meta = realGroup()
        assertTrue(AgentExecAuthorizer.run { verdict(meta, macNode).permitsExecution() })
    }

    /** 群里没 @ 任何人时命中不了（闲聊不该触发出提示或执行）。 */
    @Test
    fun ordinaryGroupChatDoesNotMentionTheAgent() {
        val meta = realGroup()
        assertTrue(
            meta.mentionedVirtualAgent("这一轮把插件推进到两端能互相拨通", tokensOf(meta)) == null,
            "没有 @ 的群聊不该命中 agent",
        )
    }

    /**
     * **`@dsh`（短 token）也要能命中** —— 用户直觉打的就是它。
     *
     * 这条在真机上翻过一次车：只认 `name`（`dsh-teahouse-mobile`）时 `@dsh` 匹配不到 ⇒
     * `addressedToAgent=false` ⇒ 连 hint 都不产生。`agentId`（DSH）也要进 token 池。
     */
    @Test
    fun theShortTokenAlsoResolvesToTheSameAgent() {
        val meta = realGroup()
        val short = meta.mentionedVirtualAgent("@dsh 帮我看看", tokensOf(meta))
        assertTrue(short != null, "`@dsh` 必须能命中 —— 用户直觉打的就是它")
        assertTrue(short.name == "dsh-teahouse-mobile")
    }
}
