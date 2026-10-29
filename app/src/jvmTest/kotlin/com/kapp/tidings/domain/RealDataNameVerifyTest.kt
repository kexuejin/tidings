// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.GroupMeta
import com.kapp.tidings.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 用**真机 settings.json 的原样形态**验证「一个 agent 只有一个名字」。
 *
 * ## 现场
 *
 * 群详情显示 `dsh-teahouse-mobile`，而聊天页的**气泡署名**显示 `dsh` ——
 * 同一个 agent 两个名字，用户直接问「这是不是同一个」。
 *
 * 根因：气泡那条路径（`resolveSenderIdentity` 的三段式分支）用 `==` 比较，
 * 而声明里 `agentId` 是**大写 `DSH`**（入驻时按 agentKind 兜底写入），
 * 消息 id 里是**小写 `dsh`** ⇒ 匹配失败 ⇒ 退回 agentId 原样。
 */
class RealDataNameVerifyTest {

    private val self = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"

    /** 真机 settings.json 里那条入驻声明，逐字复制。 */
    private fun realCtx(): SenderIdentityContext {
        val group = GroupMeta(
            groupId = "a6badc8f-e81c-44d7-a0d8-6ad31d9843ef",
            name = "teahouse-mobile 协同群",
            members = listOf(self, "f6a7b8c9-ede7-4ed1-84a4-4941d21cf81d"),
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
        )
        // 真机 agentKindsByHost 里是**小写** dsh（RelayUiState 会 lowercase）。
        return SenderIdentityContext(
            selfNodeId = self,
            selfNick = "demo-mac-97",
            group = group,
            agentKindsByHost = mapOf(self to setOf("dsh")),
        )
    }

    /** 群详情那一栏（`resolveAgentOfHost` 路径）。 */
    @Test
    fun theGroupListShowsTheExposeName() {
        val rows = groupParticipantRows(realCtx())
        assertEquals(1, rows.agents.size, "只有声明的那一个")
        val agent = rows.agents.single()
        assertEquals("dsh-teahouse-mobile", agent.displayName, "群详情显示名")
        assertEquals("@dsh-teahouse-mobile", agent.mentionToken, "@ 要打的")
    }

    /**
     * **气泡署名也必须用声明名，且大小写不敏感。**
     *
     * 这条路径（三段式）原先用 `==`：`"DSH" == "dsh"` 为 false ⇒ 退回 agentId。
     */
    @Test
    fun theBubbleSirNameUsesTheExposeNameIgnoringCase() {
        val identity = resolveSenderIdentity("agent:$self:dsh", realCtx())
        assertEquals("dsh-teahouse-mobile", identity?.displayName, "气泡署名必须用声明名")
        assertEquals("@dsh-teahouse-mobile", identity?.mentionToken)
    }

    /**
     * 债#4：老形态 `agent:dsh`（无 host 段）**不再被解析** —— 写侧只产三段式，
     * 读侧的兼容分支已删。谁把两段式分支加回来，这条红。
     */
    @Test
    fun theLegacySenderShapeIsNoLongerResolved() {
        assertEquals(null, resolveSenderIdentity("agent:dsh", realCtx()))
    }
}
