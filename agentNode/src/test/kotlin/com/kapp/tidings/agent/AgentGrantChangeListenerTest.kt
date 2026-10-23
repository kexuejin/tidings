// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.core.protocol.AgentGrantPolicies
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **台账变更必须能被听见。**
 *
 * ## 为什么这条值得单独测
 *
 * 在加监听之前，台账是**纯拉取**的：桌面面板靠自己的 `snapshotEpoch` 手动递增刷新，
 * 而窗口标题与托盘徽标根本不看它。于是「主角机收到一条授权申请」这件事**永远不会冒到
 * 用户眼前** —— 他得先想到去设置里翻一页，才可能看见有人在等批准。
 *
 * 那正是真机上这条死路的最后一环：申请发得出来、台账记得下、面板也批得了，
 * **但没有人看得见有个东西在等**。
 *
 * ## 测的是「通知」，不是「数据」
 *
 * 数据落地本来就有测试。这里钉的是**回调有没有被触发** —— 没有它，UI 那一侧的新逻辑
 * 全部是死代码（编译通过、永远不执行）。
 */
class AgentGrantChangeListenerTest {

    /** 每个用例一个临时文件（与 `AgentGrantStoreTest` 同一做法）。 */
    private fun store(): AgentGrantStore =
        AgentGrantStore(File.createTempFile("grants-listener", ".json").apply { deleteOnExit() })

    /** 收到一条新申请 ⇒ 回调触发，且读到的是新数据。 */
    @Test
    fun aNewRequestNotifies() {
        val s = store()
        var fired = 0
        s.addChangeListener { fired++ }

        s.recordRequest(nodeId = "node-phone", agentKey = "dsh", reason = "请授权", at = 1_000L)

        assertEquals(1, fired, "新申请是最需要通知的那一次 —— 没有它，用户永远不知道有人在等")
        assertEquals(1, s.snapshot().requests.size)
    }

    /** 批准 ⇒ 既要通知，也要把申请从待办里摘掉（否则面板会一直显示「待批准」）。 */
    @Test
    fun approvingNotifiesAndClearsTheRequest() {
        val s = store()
        s.recordRequest(nodeId = "node-phone", agentKey = "dsh", reason = null, at = 1_000L)
        var fired = 0
        s.addChangeListener { fired++ }

        s.grant(
            nodeId = "node-phone",
            scope = "agent",
            agentKey = "dsh",
            grantedBy = "node-host",
            at = 2_000L,
        )

        assertTrue(fired > 0, "批准也是变更，徽标要跟着归零")
        assertTrue(
            s.snapshot().requests.isEmpty(),
            "批完必须从待办里摘掉 —— 否则那个「N 条待批准」永远不减少",
        )
        assertEquals(1, s.snapshot().grants.size)
    }

    /** 拒绝 ⇒ 通知 + 待办清空，但**不产生授权**。 */
    @Test
    fun rejectingNotifiesWithoutGranting() {
        val s = store()
        s.recordRequest(nodeId = "node-phone", agentKey = "dsh", reason = null, at = 1_000L)
        var fired = 0
        s.addChangeListener { fired++ }

        s.rejectRequest(nodeId = "node-phone", agentKey = "dsh")

        assertTrue(fired > 0)
        assertTrue(s.snapshot().requests.isEmpty())
        assertTrue(s.snapshot().grants.isEmpty(), "拒绝不该顺手授出权限")
    }

    /** 改策略 ⇒ 通知（面板上的当前档位要立刻反映）。 */
    @Test
    fun changingPolicyNotifies() {
        val s = store()
        var fired = 0
        s.addChangeListener { fired++ }

        assertTrue(s.setPolicy(AgentGrantPolicies.BY_DEVICE))

        assertTrue(fired > 0)
        assertEquals(AgentGrantPolicies.BY_DEVICE, s.snapshot().policy)
    }

    /** 注销之后不再被通知（否则窗口关掉后回调还会打到一个死掉的 state 上）。 */
    @Test
    fun theListenerCanBeRemoved() {
        val s = store()
        var fired = 0
        val off = s.addChangeListener { fired++ }
        off()

        s.recordRequest(nodeId = "node-x", agentKey = null, reason = null, at = 1L)

        assertEquals(0, fired, "注销之后不许再回调")
    }

    /** 一个监听者抛异常不能拖掉其余的（面板与托盘各自注册）。 */
    @Test
    fun oneBrokenListenerDoesNotStopTheOthers() {
        val s = store()
        var second = 0
        s.addChangeListener { error("boom") }
        s.addChangeListener { second++ }

        s.recordRequest(nodeId = "node-y", agentKey = null, reason = null, at = 1L)

        assertEquals(1, second, "前一个监听者炸了，后面的仍要收到 —— 否则一处 UI 出错会静默掉整条提醒")
    }
}
