// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @ token 的拼法 —— 它必须等于「用户打进输入框能叫到它的那串」。
 *
 * 真机现场：群详情主行显示 `DSH`，入驻时生成的名字是 `dsh-teahouse-mobile`，
 * 用户看不出这两者是同一个 agent。
 * 而 token 曾用 **wsId** 拼过（`@dsh-ws-e9b18063d183`）—— 一串内部 id，不可能打出来。
 */
class AgentMentionTokenTest {

    /** 正常形态：agent 名 + 工作区名。 */
    @Test
    fun tokenJoinsAgentAndWorkspaceName() {
        assertEquals("dsh-teahouse-mobile", AgentMemberName.token("dsh", "teahouse-mobile"))
    }

    /** 没有工作区时只用 agent 名（不补一个 `-`）。 */
    @Test
    fun tokenWithoutWorkspaceIsJustTheAgent() {
        assertEquals("dsh", AgentMemberName.token("dsh", null))
        assertEquals("dsh", AgentMemberName.token("dsh", "  "))
    }

    /**
     * **归一化**：token 里不能留空格/下划线（那串打不进输入框）。
     */
    @Test
    fun tokenNormalizesSpacesAndUnderscores() {
        assertEquals("dsh-teahouse-mobile", AgentMemberName.token("DSH", "Teahouse Mobile"))
        assertEquals("dsh-a-b", AgentMemberName.token("dsh", "a_b"))
    }

    /** 全空时给一个确定的兜底（不是空串）。 */
    @Test
    fun tokenFallsBackToAgent() {
        assertEquals("agent", AgentMemberName.token(null, null))
    }
}
