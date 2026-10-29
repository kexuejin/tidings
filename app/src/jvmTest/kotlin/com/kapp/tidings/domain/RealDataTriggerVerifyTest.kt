// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 用桌面端真实数据，验证 `@dsh-teahouse-mobile` 到底能不能触发。 */
class RealDataTriggerVerifyTest {

    private val KINDS = setOf("dsh")

    private val self = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"

    private fun realCtx() = SenderIdentityContext(
        selfNodeId = self,
        selfNick = "demo-mac-97",
        group = GroupMeta(
            groupId = "a6badc8f-e81c-44d7-a0d8-6ad31d9843ef",
            name = "teahouse-mobile 协同群",
            members = listOf(self),
            rev = 6,
            updatedBy = self,
            updatedTs = 0L,
            virtualAgents = listOf(
                VirtualAgentMember(
                    agentId = "DSH",
                    hostNodeId = self,
                    name = "dsh-teahouse-mobile",
                    defaultWsId = "ws-e9b18063d183",
                ),
            ),
        ),
        agentKindsByHost = mapOf(self to setOf("dsh")),
    )

    /** 群里的 agentKinds 必须含 `dsh`（触发正则按它匹配）。 */
    @Test
    fun theGroupAgentKindsContainTheKind() {
        val kinds = agentKindsInGroup(realCtx())
        assertEquals(setOf("dsh"), kinds, "agentKinds 必须来自入驻声明")
    }

    /** **插入的 token 必须能触发** —— 这是「@ 了却叫不醒」的判据。 */
    @Test
    fun theMentionTokenActuallyTriggersTheAgent() {
        val ctx = realCtx()
        val token = groupParticipantRows(ctx).agents.single().mentionToken
        assertEquals("@dsh-teahouse-mobile", token)
        assertTrue(
            RelayController.shouldTriggerAgent(
                text = "$token test",
                groupId = "g-1",
                isWorkspaceGroup = false,
                repliedToIsAgent = false,
                agentKinds = agentKindsInGroup(ctx),
            ),
            "插进去的 token 必须能触发 —— 否则就是反复出现的「@ 了没反应」",
        )
    }
}
