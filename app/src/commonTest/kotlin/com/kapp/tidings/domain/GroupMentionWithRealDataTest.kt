// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 用**真机库里的真实形态**复现「群里 @ 不到 agent」。
 *
 * 数据取自桌面库（`AgentSessionEntity` / `settings.json`）：
 * - 宿主 `a1b2c3d4-…` 有 127 条 agent 会话、16 个工作区，kind = `DSH`；
 * - 它确实是 `deepseek-harness 协同群` 的成员；
 * - 群里 agent 消息的 senderId 是**三段式** `agent:a1b2c3d4-…:dsh`（417 条）。
 *
 * 这些都不缺。所以如果候选仍然为空，缺口一定在别的判据上 —— 这条测试就是为了把
 * 「数据齐全但派生不出候选」这件事钉在 jvmTest 里，而不是只在真机上看见。
 */
class GroupMentionWithRealDataTest {

    private val host = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"
    private val self = "1de883c7-4bca-4d62-a71c-5d32111656ca"

    private fun ctx(
        agentKindsByHost: Map<String, Set<String>> = mapOf(host to setOf("DSH")),
        agentAliasesByHost: Map<String, String> = mapOf(host to "DSH"),
        agentSenderIds: Set<String> = setOf("agent:$host:dsh"),
    ): SenderIdentityContext {
        val group = GroupMeta(
            groupId = "g-1",
            name = "deepseek-harness 协同群",
            members = listOf(host, self),
            rev = 1,
            updatedBy = self,
            updatedTs = 0L,
            // 入驻声明：群里的 agent 唯一来源。kind 保留真机的大写形态（DSH），
            // 这条夹具的价值就是钉住「大写 kind 也是常态」。
            virtualAgents = listOf(
                VirtualAgentMember(
                    agentId = "DSH",
                    hostNodeId = host,
                    name = "dsh-teahouse-mobile",
                    wsId = "ws-teahouse",
                ),
            ),
        )
        return SenderIdentityContext(
            selfNodeId = self,
            selfNick = "我自己",
            peers = listOf(
                // 与真机一致：这台机器声明了 agent 能力（caps 含 ag1），昵称是机器名。
                PeerUi(
                    nodeId = host,
                    nick = "demo-mac-97",
                    caps = listOf(com.kapp.tidings.core.protocol.MsgTypes.CAP_AGENT),
                ),
            ),
            group = group,
            agentKindsByHost = agentKindsByHost,
            agentAliasesByHost = agentAliasesByHost,
            agentSenderIds = agentSenderIds,
        )
    }

    /**
     * **kind 是大写 `DSH` 时也必须产出候选。**
     *
     * 真机库里 `AgentSessionEntity.agent_kind` 存的是 `DSH`（大写，127 条），
     * 而 `dsh` 只有 2 条 —— 大写才是常态。任何对大写的敏感判据都会让候选整排消失。
     */
    @Test
    fun anUppercaseKindStillYieldsAMentionCandidate() {
        val rows = groupParticipantRows(ctx())
        assertTrue(
            rows.agents.any { it.canMention },
            "宿主是群成员、且有 127 条 agent 会话，候选里必须有它；实得 agents=${rows.agents.map { it.mentionToken }}",
        )
    }

    /**
     * **三段式 senderId 也必须能补出候选**（宿主枚举失败时的兜底路径）。
     *
     * 真机库里这个群有 417 条 `agent:<host>:dsh` 形态的消息。
     */
    @Test
    fun aThreePartSenderIdAlsoYieldsACandidate() {
        // 故意把宿主枚举清空，只留消息里的 senderId —— 模拟「群元数据里没有那台宿主」。
        val rows = groupParticipantRows(ctx(agentKindsByHost = emptyMap(), agentAliasesByHost = emptyMap()))
        assertTrue(
            rows.agents.isNotEmpty(),
            "三段式 senderId 是兜底路径，不能因为宿主没枚举就整排消失",
        )
    }
}
