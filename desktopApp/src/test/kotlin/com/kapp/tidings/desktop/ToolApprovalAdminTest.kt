// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.agent.AgentToolApprovalStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「免批规则」区块的撤销：**撤掉的必须恰好是界面上那一行**。
 *
 * 修的是这样一条缺陷：`AgentToolApprovalStore` 只有写入口（审批行按「始终允许」），
 * 界面上既看不见也撤不回 —— 想收回只能删 `agent-tool-approvals.json` 整张表，
 * 连别的工具的规则一起没了。
 *
 * 三条不变量：
 *  1. **逐条撤**：撤掉 `bash` 不动同设备的 `git`，也不动别的 agent 的 `bash`；
 *  2. **`agentKey = null` 的两个含义不许混**：它在 `Rule` 里是「覆盖所有 agent」，
 *     在 `revoke()` 里是「不按 agent 过滤」。拿前者调后者会顺手删掉别的 agent 的规则，
 *     界面上却只消失了这一行 —— 那是对着用户做的假账；
 *  3. **撤完就真的重新问**：内存与落盘都干净（重启后也不复活）。
 *
 * 反向验证：把 [ToolApprovalAdmin.revoke] 换成
 * `store.revoke(row.nodeId, row.agentKey, row.tool)` ⇒
 * [revokingTheAllAgentsRowLeavesAgentSpecificRulesAlone] 变红（同 device 同 tool 的
 * `dsh-cli` 规则被一起删掉）。还原后回绿。
 */
class ToolApprovalAdminTest {

    private val file: File = File.createTempFile("relay-desktop-tool-approvals-", ".json").apply { delete() }

    @AfterTest
    fun cleanup() {
        runCatching { file.delete() }
    }

    private fun store() = AgentToolApprovalStore(file)

    private fun adminOf(store: AgentToolApprovalStore) = ToolApprovalAdmin(store)

    @Test
    fun rowsMirrorTheStoreNewestFirst() {
        val store = store()
        store.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)
        store.remember(nodeId = "phone-1", agentKey = "codex", tool = "git", at = 30L)

        val rows = adminOf(store).rows()

        assertEquals(listOf("git", "bash"), rows.map { it.tool }, "快照按授权时间降序，界面顺序与之一致")
        assertEquals("codex", rows.first().agentKey)
    }

    @Test
    fun revokingOneRowLeavesOtherToolsAndAgentsAlone() {
        val store = store()
        store.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)
        store.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "git", at = 20L)
        store.remember(nodeId = "phone-1", agentKey = "codex", tool = "bash", at = 30L)
        val admin = adminOf(store)

        val target = admin.rows().first { it.tool == "bash" && it.agentKey == "dsh-cli" }
        assertEquals(1, admin.revoke(target), "撤一条就只该少一条")

        assertFalse(store.isAlwaysApproved("phone-1", "dsh-cli", "bash"), "撤掉的那条要重新问")
        assertTrue(store.isAlwaysApproved("phone-1", "dsh-cli", "git"), "同 agent 的别的工具不受影响")
        assertTrue(store.isAlwaysApproved("phone-1", "codex", "bash"), "别的 agent 的同名工具不受影响")
        assertEquals(2, admin.rows().size)
    }

    /**
     * 这条是 `revokeExactly` 存在的全部理由。
     *
     * `agentKey = null` 的那一行说的是「这台设备上**所有** agent 的该工具都免批」；
     * 撤销它时必须只删这一条规则本身 —— 同设备、同工具、但按 agent 记的规则是另外几条，
     * 它们还在列表上，就不能被顺手删掉。
     */
    @Test
    fun revokingTheAllAgentsRowLeavesAgentSpecificRulesAlone() {
        val store = store()
        store.remember(nodeId = "phone-1", agentKey = null, tool = "bash", at = 10L)
        store.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 20L)
        store.remember(nodeId = "phone-2", agentKey = null, tool = "bash", at = 30L)
        val admin = adminOf(store)

        val wildcard = admin.rows().first { it.nodeId == "phone-1" && it.agentKey == null }
        assertEquals(1, admin.revoke(wildcard), "只删「覆盖所有 agent」的那一条")

        assertTrue(
            store.isAlwaysApproved("phone-1", "dsh-cli", "bash"),
            "按 agent 记的那条还在列表上，就必须还在表里 —— 否则界面显示的和实际生效的是两件事",
        )
        assertTrue(store.isAlwaysApproved("phone-2", null, "bash"), "别的设备的规则不受影响")
        assertEquals(2, admin.rows().size)
    }

    @Test
    fun revokingTheLastRuleMakesTheToolAskAgainEvenAfterARestart() {
        val store = store()
        store.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)
        assertEquals(1, adminOf(store).revoke(adminOf(store).rows().single()))

        assertFalse(store.isAlwaysApproved("phone-1", "dsh-cli", "bash"), "撤完立刻生效")
        assertFalse(
            store().isAlwaysApproved("phone-1", "dsh-cli", "bash"),
            "落盘也干净：重启后不许复活（复活等于用户撤了个寂寞）",
        )
    }

    @Test
    fun revokingARowThatIsAlreadyGoneReportsZeroInsteadOfFailing() {
        val store = store()
        store.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)
        val admin = adminOf(store)
        val row = admin.rows().single()

        assertEquals(1, admin.revoke(row))
        // 第二次点（或对端刚撤过、界面还没刷）：如实说 0 条，不抛、不假装成功。
        assertEquals(0, admin.revoke(row))
        assertNull(admin.rows().firstOrNull())
    }

    @Test
    fun changeListenerFiresOnBothWriteAndRevoke() {
        val store = store()
        var fired = 0
        val off = store.addChangeListener { fired++ }

        // 对端按「始终允许」走的是 remember：设置页开着也要看见它冒出来。
        store.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "bash", at = 10L)
        assertEquals(1, fired, "新规则落表要通知（否则页面开着也看不到刚产生的规则）")

        adminOf(store).revoke(adminOf(store).rows().single())
        assertEquals(2, fired, "撤销也要通知")

        off()
        store.remember(nodeId = "phone-1", agentKey = "dsh-cli", tool = "git", at = 20L)
        assertEquals(2, fired, "注销之后不再回调")
    }
}
