// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「这条群消息 @ 的是哪个 agent」的判定。
 *
 * 用户把语义定死了：**添加 agent 进群时，要么它绑定了某个 workspace，要么没有绑定。**
 * 两种都是一等状态，判定必须覆盖到，不能只测一种。
 */
class VirtualAgentMentionTest {

    private fun meta(vararg agents: VirtualAgentMember) = GroupMeta(
        groupId = "g-1",
        name = "测试群",
        members = listOf("host-a", "self"),
        rev = 1,
        updatedBy = "self",
        updatedTs = 0L,
        virtualAgents = agents.toList(),
    )

    private fun agent(name: String, wsId: String? = null) = VirtualAgentMember(
        agentId = name, hostNodeId = "host-a", name = name, wsId = wsId,
    )

    /**
     * **绑了 workspace 的 agent**：@ 到它时，必须能拿到 wsId ——
     * 注入方据此投给该工作区的会话。
     */
    @Test
    fun aWorkspaceBoundAgentCarriesItsWsId() {
        val m = meta(agent("dsh-teahouse", wsId = "ws-42"))
        val hit = m.mentionedVirtualAgent("@dsh-teahouse 看一下构建", listOf("dsh-teahouse"))
        assertEquals("ws-42", hit?.wsId, "绑定的工作区必须带出来")
    }

    /**
     * **没绑 workspace 的 agent 同样可以被 @** —— 这是一等状态，不是缺失。
     *
     * wsId == null 时注入方投给它自己的会话（不带工作区的那个）。
     */
    @Test
    fun anUnboundAgentIsStillMentionable() {
        val m = meta(agent("dsh"))
        val hit = m.mentionedVirtualAgent("@dsh 你好", listOf("dsh"))
        assertEquals("dsh", hit?.name)
        assertNull(hit?.wsId, "没绑定就是 null，不能编一个工作区出来")
    }

    /** 没 @ 到任何 agent ⇒ null（调用方据此不注入）。 */
    @Test
    fun noMentionYieldsNull() {
        val m = meta(agent("dsh"))
        assertNull(m.mentionedVirtualAgent("随便说点什么", listOf("dsh")))
    }

    /**
     * **长 token 优先**：`@dsh-teahouse` 不能被 `@dsh` 抢先命中。
     *
     * 同一台宿主上装两个 agent 时，短名是长名的前缀 —— 先匹配短的会投给错的 agent。
     */
    @Test
    fun aLongerTokenWins() {
        val m = meta(agent("dsh"), agent("dsh-teahouse", wsId = "ws-1"))
        val hit = m.mentionedVirtualAgent("@dsh-teahouse 干活", listOf("dsh", "dsh-teahouse"))
        assertEquals("dsh-teahouse", hit?.name)
    }

    /** `@dsh` 不该命中 `@dsh2`（token 后还有标识符字符）。 */
    @Test
    fun aPrefixOfALongerWordDoesNotMatch() {
        val m = meta(agent("dsh"))
        assertNull(m.mentionedVirtualAgent("@dsh2 你好", listOf("dsh")))
    }

    /** 大小写不敏感（`@DSH` 与 `@dsh` 同一个人）。 */
    @Test
    fun mentionIsCaseInsensitive() {
        val m = meta(agent("dsh"))
        assertTrue(m.mentionedVirtualAgent("@DSH 你好", listOf("dsh")) != null)
    }

    /** 群没有虚拟 agent（纯人集群）⇒ 恒 null，不抛。 */
    @Test
    fun aGroupWithoutVirtualAgentsYieldsNull() {
        assertNull(meta().mentionedVirtualAgent("@dsh 你好", listOf("dsh")))
    }
    // ---------------------------------------------------------------- 中文紧跟

    /**
     * **中文紧跟 @token 也要命中。**
     *
     * 真机现场：中文群里用户必然写 `@dsh你好`（不加空格），
     * 而 `isLetterOrDigit` 对汉字返回 true ⇒ 整条 @ 被判成「另一个 token」⇒
     * agentHint 恒为 null，用户看到的是「@ 了但没反应」。
     */
    @Test
    fun aChineseCharacterRightAfterTheTokenStillMatches() {
        val m = meta(agent("dsh"))
        val hit = m.mentionedVirtualAgent("@dsh你好", listOf("dsh"))
        assertEquals("dsh", hit?.name)
    }

    /** 中文标点紧跟也算命中（用户会写 `@dsh，帮我看下`）。 */
    @Test
    fun aChinesePunctuationRightAfterTheTokenStillMatches() {
        val m = meta(agent("dsh"))
        assertNotNull(m.mentionedVirtualAgent("@dsh，帮我看下", listOf("dsh")))
    }

    /**
     * **但 ASCII 标识符字符仍然算「另一个 token」**（`@dsh2` ≠ `@dsh`）。
     *
     * 放宽只针对非 ASCII —— 那条防误命中的纪律不能跟着一起松掉。
     */
    @Test
    fun anAsciiIdentifierAfterTheTokenIsStillAnotherToken() {
        val m = meta(agent("dsh"))
        assertNull(m.mentionedVirtualAgent("@dsh2 你好", listOf("dsh")))
        assertNull(m.mentionedVirtualAgent("@dsh_x 你好", listOf("dsh")))
    }
}
