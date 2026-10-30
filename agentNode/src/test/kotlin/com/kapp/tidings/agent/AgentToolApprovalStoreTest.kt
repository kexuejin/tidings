// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 按 tool 名粒度的免批表。
 *
 * 钉四件事，每一条错了都会变成"替用户批准"：
 *  1. **粒度是 tool**：`bash` 免批不等于 `rm` 免批（本类存在的全部理由）；
 *  2. **粒度是设备**：A 按过不等于 B 不用问；
 *  3. **缺信息只能更严**：tool / nodeId 为空 ⇒ 永不命中；
 *  4. **落盘可读回**：重启后免批还在（否则用户每重启一次就要重点一遍）。
 *
 * 反向验证：把 [AgentToolApprovalStore.isAlwaysApproved] 里的 tool 比较删掉
 * （只按 nodeId + agentKey 命中），[alwaysIsScopedToTheToolNotTheAgent] 变红；
 * 把 nodeId 比较删掉，[anotherDevicesApprovalDoesNotCoverMine] 变红。
 */
class AgentToolApprovalStoreTest {

    private val file: File = File.createTempFile("relay-tool-approvals-", ".json").apply { delete() }

    @AfterTest
    fun cleanup() {
        runCatching { file.delete() }
    }

    private fun store() = AgentToolApprovalStore(file)

    @Test
    fun alwaysIsScopedToTheToolNotTheAgent() {
        val s = store()
        s.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)

        assertTrue(s.isAlwaysApproved("phone-1", "dsh-cli", "bash"), "按过的这一条要命中")
        assertFalse(
            s.isAlwaysApproved("phone-1", "dsh-cli", "rm"),
            "同一个 agent 的另一个工具必须照常问 —— 按 agent 免批等于把高危动作一并放行",
        )
    }

    @Test
    fun anotherDevicesApprovalDoesNotCoverMine() {
        val s = store()
        s.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)

        assertFalse(
            s.isAlwaysApproved("phone-2", "dsh-cli", "bash"),
            "免批是某个人的决定，不是整个群的默认值",
        )
    }

    @Test
    fun anotherAgentsApprovalDoesNotCoverMine() {
        val s = store()
        s.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)

        assertFalse(
            s.isAlwaysApproved("phone-1", "codex", "bash"),
            "tool 名相同但 agent 不同：那是另一个程序在跑命令",
        )
    }

    @Test
    fun missingInformationNeverMatches() {
        val s = store()
        s.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)

        assertFalse(s.isAlwaysApproved("phone-1", "dsh-cli", null), "tool 不知道 ⇒ 要问")
        assertFalse(s.isAlwaysApproved("phone-1", "dsh-cli", "  "), "空白 tool 名不是通配")
        assertFalse(s.isAlwaysApproved("", "dsh-cli", "bash"), "nodeId 不知道 ⇒ 要问")
        assertFalse(
            s.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "   ", at = 10L),
            "没有 tool 名的规则无法表达「只放行哪一个」，不许静默写成通配",
        )
    }

    @Test
    fun rulesSurviveARestart() {
        store().remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)

        assertTrue(
            store().isAlwaysApproved("phone-1", "dsh-cli", "bash"),
            "重启后免批还在，否则用户每启动一次就要重点一遍",
        )
    }

    @Test
    fun aBrokenFileMeansAskAgainNotApprove() {
        file.parentFile?.mkdirs()
        file.writeText("{ 这不是 JSON")
        val s = store()

        assertFalse(s.isAlwaysApproved("phone-1", "dsh-cli", "bash"), "读不回来 ⇒ 要问")
        assertEquals(0, s.snapshot().size)
    }

    @Test
    fun revokeIsScopedAndCountsWhatItRemoved() {
        val s = store()
        s.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)
        s.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "git", at = 20L)
        s.remember(nodeId = "phone-1", agentKey = "codex", tool = "bash", at = 30L)

        assertEquals(1, s.revoke("phone-1", agentKey = "dsh-cli", tool = "bash"))
        assertFalse(s.isAlwaysApproved("phone-1", "dsh-cli", "bash"))
        assertTrue(s.isAlwaysApproved("phone-1", "dsh-cli", "git"), "撤一条不动同 agent 的别的工具")
        assertTrue(s.isAlwaysApproved("phone-1", "codex", "bash"), "撤一条不动别的 agent")

        assertEquals(2, s.revoke("phone-1"), "按设备撤：把这条设备上剩下的全撤掉")
        assertFalse(s.isAlwaysApproved("phone-1", "codex", "bash"))
    }

    /**
     * [AgentToolApprovalStore.revokeExactly] 与 [AgentToolApprovalStore.revoke] 的差别，
     * 全在 `agentKey = null` 上：
     *
     * - `Rule.agentKey = null` ⇒ 这条规则**覆盖所有 agent**（是一条通配规则）；
     * - `revoke(agentKey = null)` ⇒ **不按 agent 过滤**（整批撤）。
     *
     * 面板逐条撤销时拿前者去调后者，会把同设备同工具的其它 agent 规则一起删掉 ——
     * 界面上只消失一行，实际少了好几行。方向是收紧（只会多问一次），但它让界面
     * 变成假账，所以逐条撤销必须有这个「三元逐字相等」的入口。
     */
    @Test
    fun revokeExactlyTellsTheWildcardAgentApartFromAnyAgent() {
        val s = store()
        s.remember(nodeId = "phone-1", agentKey = null, tool = "bash", at = 10L)
        s.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 20L)

        assertEquals(1, s.revokeExactly("phone-1", agentKey = null, tool = "bash"))
        assertTrue(
            s.isAlwaysApproved("phone-1", "dsh-cli", "bash"),
            "按 agent 记的那条是另一条规则，不在这次撤销范围内",
        )
        assertEquals(1, s.snapshot().size)

        assertEquals(1, s.revokeExactly("phone-1", agentKey = "dsh-cli", tool = "bash"))
        assertEquals(0, s.snapshot().size)
    }
}
