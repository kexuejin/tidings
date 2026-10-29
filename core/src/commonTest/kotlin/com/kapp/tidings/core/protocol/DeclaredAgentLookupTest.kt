// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「这一行 agent 对应哪条 `virtualAgents` 声明」—— 决定移除按钮画不画。
 *
 * 真机症状：列表里能看到 `dsh-teahouse-mobile`，但它旁边**没有移除按钮**。
 * 原因是匹配时拿 `name` 去比 `SenderIdentity.id`，而 id 的形态是 `agent:<host>:<kind>`。
 */
class DeclaredAgentLookupTest {

    private val self = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"
    private val ws = "ws-e9b18063d183"

    private fun declared(
        host: String = self,
        agentId: String = "DSH",
        name: String = "dsh-teahouse-mobile",
        defaultWsId: String? = ws,
    ) = VirtualAgentMember(agentId = agentId, hostNodeId = host, name = name, defaultWsId = defaultWsId)

    /** **真机那一对必须对上** —— id 是 `agent:<host>:dsh`，声明是 host + `DSH`。 */
    @Test
    fun aDeclaredAgentIsFoundFromItsSenderId() {
        assertEquals(
            ws,
            declaredWorkspaceOfSender(
                senderId = "agent:$self:dsh",
                declaredAgents = listOf(declared()),
                hostNodeId = self,
            ),
        )
    }

    /** 别的宿主的声明不算（撤回是机主主权）。 */
    @Test
    fun anotherHostsDeclarationDoesNotMatch() {
        assertNull(
            declaredWorkspaceOfSender(
                senderId = "agent:$self:dsh",
                declaredAgents = listOf(declared(host = "node-other")),
                hostNodeId = self,
            ),
        )
    }

    /** 没绑工作区的声明撤回不了（`wsId` 为 null 就是 null）。 */
    @Test
    fun aDeclarationWithoutWorkspaceYieldsNull() {
        assertNull(
            declaredWorkspaceOfSender(
                senderId = "agent:$self:dsh",
                declaredAgents = listOf(declared(defaultWsId = null)),
                hostNodeId = self,
            ),
        )
    }

    /** 不是 agent 的 id（普通节点）⇒ null。 */
    @Test
    fun aPlainNodeIdYieldsNull() {
        assertNull(declaredWorkspaceOfSender(self, listOf(declared()), self))
    }

    /** 没有声明 ⇒ null。 */
    @Test
    fun noDeclarationsYieldNull() {
        assertNull(declaredWorkspaceOfSender("agent:$self:dsh", null, self))
        assertNull(declaredWorkspaceOfSender("agent:$self:dsh", emptyList(), self))
    }

    /** 不同的 agent 不串（codex 的声明不该匹配 dsh 的行）。 */
    @Test
    fun aDifferentAgentDoesNotMatch() {
        assertNull(
            declaredWorkspaceOfSender(
                senderId = "agent:$self:codex",
                declaredAgents = listOf(declared(agentId = "DSH", name = "dsh-teahouse-mobile")),
                hostNodeId = self,
            ),
        )
    }
}
