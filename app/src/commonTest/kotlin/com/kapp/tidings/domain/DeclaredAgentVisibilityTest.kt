// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 群里**声明过**的 agent 必须出现在成员面板里。
 *
 * ## 真机现场
 *
 * 用户在群详情点了「邀请新智能体入驻」，`settings.json` 里 `virtualAgents` 立刻有了那条记录：
 *
 *     {'agentId':'DSH','hostNodeId':'a1b2c3d4-…','name':'dsh-teahouse-mobile','wsId':'ws-e9b18…'}
 *
 * 但群详情的智能体列表**没变**。原因是 `groupParticipantRows` 只从
 * `members × 对端自报的 kind` 枚举 agent，**从未读过 `virtualAgents`** ——
 * 入驻写入的声明在渲染路径上是不可见的，用户看到的就是「点了没反应」。
 */
class DeclaredAgentVisibilityTest {

    private val self = "node-self"
    private val host = "node-host"

    private fun ctx(
        declared: List<VirtualAgentMember>,
        members: List<String> = listOf(self),
        kinds: Map<String, Set<String>> = emptyMap(),
    ): SenderIdentityContext {
        val group = GroupMeta(
            groupId = "g-1",
            name = "群",
            members = members,
            rev = 1,
            updatedBy = self,
            updatedTs = 0L,
            virtualAgents = declared.ifEmpty { null },
        )
        return SenderIdentityContext(
            selfNodeId = self,
            selfNick = "我",
            peers = emptyList(),
            group = group,
            agentSenderIds = emptySet(),
            agentKindsByHost = kinds,
        )
    }

    /**
     * **入驻后列表里要有它**，即使宿主没自报 kind、甚至不在 members 里。
     */
    @Test
    fun aDeclaredAgentAppearsEvenWithoutReportedKinds() {
        val rows = groupParticipantRows(
            ctx(
                declared = listOf(
                    VirtualAgentMember(
                        agentId = "DSH",
                        hostNodeId = host,
                        name = "dsh-teahouse-mobile",
                        defaultWsId = "ws-e9b18063d183",
                    ),
                ),
            ),
        )
        assertTrue(
            rows.agents.isNotEmpty(),
            "声明过的 agent 没出现在列表里 —— 用户会以为入驻没生效",
        )
    }

    /** 没有声明也没有上报时，agent 列表为空（不要凭空造）。 */
    @Test
    fun nothingDeclaredYieldsNoAgents() {
        val rows = groupParticipantRows(ctx(declared = emptyList()))
        assertTrue(rows.agents.isEmpty(), "没有声明时不该凭空出现 agent")
    }
}
