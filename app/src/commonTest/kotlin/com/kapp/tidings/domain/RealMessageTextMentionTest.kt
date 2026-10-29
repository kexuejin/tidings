// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.core.protocol.mentionedVirtualAgent
import kotlin.test.Test
import kotlin.test.assertTrue

/** 用**真机上实际发出的那条文本**验证命中（包括双空格这种现实形态）。 */
class RealMessageTextMentionTest {

    private val meta = GroupMeta(
        groupId = "a6badc8f-e81c-44d7-a0d8-6ad31d9843ef",
        name = "teahouse-mobile 协同群",
        members = listOf(
            "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6",
            "f6a7b8c9-ede7-4ed1-84a4-4941d21cf81d",
        ),
        rev = 9,
        updatedBy = "a1b2c3d4",
        updatedTs = 1_000L,
        ownerId = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6",
        virtualAgents = listOf(
            VirtualAgentMember(
                agentId = "DSH",
                name = "dsh-teahouse-mobile",
                hostNodeId = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6",
                defaultWsId = "ws-e9b18063d183",
            ),
        ),
    )

    private val tokens = meta.virtualAgents.orEmpty()
        .flatMap { a -> listOfNotNull(a.name, a.agentId) }

    @Test
    fun theDoubleSpaceFormStillResolves() {
        val text = "@dsh-teahouse-mobile  unauth-test"
        assertTrue(
            meta.mentionedVirtualAgent(text, tokens) != null,
            "真机输入产生了双空格；命中判据必须容忍它",
        )
    }

    @Test
    fun theSingleSpaceFormResolves() {
        assertTrue(meta.mentionedVirtualAgent("@dsh-teahouse-mobile unauth-test", tokens) != null)
    }

    @Test
    fun theExactRowThatWasSentResolves() {
        // 从桌面库读出的那一行（去掉首尾空白后）。
        assertTrue(meta.mentionedVirtualAgent("@dsh-teahouse-mobile  grtflow", tokens) != null)
    }
}
