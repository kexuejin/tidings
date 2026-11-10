// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D13 命名规则的钉子：席位 `@token` = `kind + 职责`，**不含目录**。
 *
 * 反向验证：把 `base` 退回「agentId 优先 + wsTitle 拼接」的旧实现，
 * 第一组断言立刻红（`dsh-029bbbf3-coder` ≠ `dsh-coder`）。
 */
class AgentMemberNameD13Test {

    @Test
    fun `token is kind plus duty, never the per-machine instance id or the directory`() {
        // kind 优先；实例 id（dsh-029bbbf3）不进名字。
        assertEquals("dsh-coder", AgentMemberName.base("dsh", "dsh-029bbbf3", "coder"))
        assertEquals("gemini-lead", AgentMemberName.base("gemini", "gemini-x", "lead"))
        // kind 缺失时才退 agentId。
        assertEquals("gemini-lead", AgentMemberName.base(null, "gemini", "lead"))
        // 职责留空 = 只用 kind（「通用」档）。
        assertEquals("dsh", AgentMemberName.base("dsh", "dsh-029bbbf3", ""))
        assertEquals("dsh", AgentMemberName.base("dsh", "dsh-029bbbf3", null))
        // 全空兜底。
        assertEquals("agent", AgentMemberName.base(null, null, null))
    }

    @Test
    fun `duty is normalized through the same slug rules`() {
        assertEquals("dsh-coder", AgentMemberName.base("dsh", null, "CodeR"))
        // slug 的既有语义：下划线/空格转 `-`（与 token 同一套，不许第二份归一化）。
        assertEquals("dsh-code-r", AgentMemberName.base("dsh", null, " code_r "))
    }

    @Test
    fun `conflict suffix still applies to duty names`() {
        assertEquals("dsh-coder-2", AgentMemberName.unique("dsh-coder", setOf("dsh-coder")))
        assertEquals(
            "dsh-coder-3",
            AgentMemberName.unique("dsh-coder", setOf("dsh-coder", "dsh-coder-2")),
        )
    }

    @Test
    fun `duty domain has a single definition and out-of-domain values fall back`() {
        assertEquals(8, AgentDuties.ALL.size)
        assertEquals(AgentDuties.ALL.size, AgentDuties.ALL.toSet().size)
        assertEquals("lead", AgentDuties.normalize("lead"))
        // 取值域外的拼写（大小写不同 / 未知值 / null）一律回落单兵默认 ——
        // 不发明第四种拼写。
        assertEquals("assistant", AgentDuties.normalize("Coder"))
        assertEquals("assistant", AgentDuties.normalize("manager"))
        assertEquals("assistant", AgentDuties.normalize(null))
        assertEquals("assistant", AgentDuties.normalize("  "))
        assertTrue(AgentDuties.DEFAULT_SINGLE in AgentDuties.ALL)
        assertTrue(AgentDuties.DEFAULT_LEADER in AgentDuties.ALL)
    }
}
