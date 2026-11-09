// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 只读通道执行前判据的记账规则（提案 §7.1 第 3 条）。
 *
 * 这里钉的不是「白名单长什么样」——那是 `CognitiveFlow.allowsTool` 的事，
 * 判据以纯函数传进来。这里钉的是**拒绝怎么记**，因为判负要用它：
 * 1. 只记**第一个**越界工具（判负依据，后面的会稀释结论）；
 * 2. 无名调用也记（fail-closed，不能因为没名字就变成「没有越界」）；
 * 3. 放行过的调用不留痕（否则一次只读之后永远判负）。
 */
class ReadOnlyToolPolicyTest {

    private val allowList = setOf("read_file", "list_dir", "grep")

    private fun policy() = ReadOnlyToolPolicy { tool ->
        tool?.trim()?.lowercase()?.let { it in allowList } == true
    }

    @Test
    fun anAllowListedToolIsAdmittedWithoutLeavingATrace() {
        val p = policy()
        assertTrue(p.admit("read_file"))
        assertTrue(p.admit("grep"))
        assertNull(p.deniedTool(), "只读调用不许留下越界记录——否则任务永远判负")
    }

    @Test
    fun aToolOutsideTheListIsDeniedAndRemembered() {
        val p = policy()
        assertFalse(p.admit("run_command"), "白名单外必须拒绝")
        assertEquals("run_command", p.deniedTool(), "拒绝要记下来，任务收场时据此判负")
    }

    @Test
    fun theFirstDenialWins() {
        val p = policy()
        p.admit("git_push")
        p.admit("rm_rf")
        assertEquals(
            "git_push",
            p.deniedTool(),
            "判负依据必须是**第一个**越界工具：换成最后一个会让结论跟着模型的重试顺序漂",
        )
    }

    @Test
    fun anUnnamedCallIsDeniedAndRecordedAsUnnamed() {
        val p = policy()
        assertFalse(p.admit(null), "无名调用 fail-closed（无法证明它是读）")
        assertEquals("(unnamed tool)", p.deniedTool())
        assertFalse(policy().admit("   "), "空白工具名同样不放行")
    }

    @Test
    fun theDenialRecordIsTrimmed() {
        val p = policy()
        assertFalse(p.admit("  git push \n"))
        assertEquals("git push", p.deniedTool(), "记录里的工具名要干净（它会进结果卡与台账）")
    }
}
