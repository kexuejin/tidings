// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.workspace

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工作区 1:1 防重管理器（升级轮 T-2b，设计 §5.2 / D-025③ 命名）。
 *
 * 钉四件事：
 * 1. **防重的落点是数据库唯一索引**：同 (groupId, hostNodeId, wsPath) 的第二次绑定
 *    返回冲突失败（isPathBound 友好判定 + 索引兜底竞态），不覆盖第一条；
 * 2. **1:1 语义**：同群同宿主换路径 = 换绑定（旧路径解绑），不是双绑；
 * 3. 绑定读回字段逐项一致（gitBranch 是绑定时的快照）；
 * 4. 解绑后路径可用（可被任何群重新绑定）。
 *
 * 夹具走 JdbcSqliteDriver 内存库 + 真迁移（与 Migration18WorkspaceBindingTest 同款）——
 * 防重判据必须钉在**真的约束**上，mock 掉就只剩「我调了我的函数」。
 */
class WorkspaceBindingManagerTest {

    private fun manager(): WorkspaceBindingManager {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        com.kapp.tidings.db.FtsTolerantSchema().create(driver)
        val database = com.kapp.tidings.db.RelayDatabase(driver)
        return WorkspaceBindingManager(database)
    }

    @Test
    fun bindThenReadBackKeepsEveryField() {
        val m = manager()
        val result = m.bindWorkspace("g-1", "host-a", "/repo/tea", gitBranch = "main")
        assertTrue(result.isSuccess, "首绑成功")
        val binding = result.getOrNull()!!
        assertEquals("g-1", binding.groupId)
        assertEquals("host-a", binding.hostNodeId)
        assertEquals("/repo/tea", binding.wsPath)
        assertEquals("main", binding.gitBranch)
    }

    @Test
    fun samePathBoundByAnotherGroupIsRejected() {
        val m = manager()
        assertTrue(m.bindWorkspace("g-1", "host-a", "/repo/tea").isSuccess)
        val second = m.bindWorkspace("g-2", "host-a", "/repo/tea")
        assertTrue(second.isFailure, "同宿主同路径已被 g-1 绑定 ⇒ g-2 绑不上（1:1 防重）")
        // P3 补笔（S14 复核）：两级判定的失败**类型**各自可断言 —— 异群占路径 =
        // PathBoundByOther（带回占着的绑定），不是 Storage（那是磁盘/意外的档）。
        val failure = second.exceptionOrNull() as? WorkspaceBindException
        assertTrue(
            failure?.failure is WorkspaceBindFailure.PathBoundByOther,
            "失败类型必须是 PathBoundByOther（可区分性判据）：实际 " + second.exceptionOrNull(),
        )
        assertEquals(
            "g-1",
            (failure?.failure as? WorkspaceBindFailure.PathBoundByOther)?.binding?.groupId,
            "失败里带回的是**占着的那条**绑定（UI 说得出被哪个群占着）",
        )
        assertTrue(m.isPathBound("host-a", "/repo/tea"), "路径仍归 g-1")
    }

    @Test
    fun sameGroupRebindingWithNewPathReplacesTheOldOne() {
        val m = manager()
        assertTrue(m.bindWorkspace("g-1", "host-a", "/repo/tea").isSuccess)
        val rebinding = m.bindWorkspace("g-1", "host-a", "/repo/tea-v2", gitBranch = "feature/x")
        assertTrue(rebinding.isSuccess, "同群换路径 = 换绑定（1:1 语义，不是双绑）")
        assertTrue(!m.isPathBound("host-a", "/repo/tea"), "旧路径解绑")
        assertEquals("/repo/tea-v2", m.bindingOf("g-1", "host-a")?.wsPath)
        assertEquals("feature/x", m.bindingOf("g-1", "host-a")?.gitBranch)
    }

    @Test
    fun unbindFreesThePathForAnyone() {
        val m = manager()
        m.bindWorkspace("g-1", "host-a", "/repo/tea")
        m.unbind("g-1", "host-a")
        assertTrue(!m.isPathBound("host-a", "/repo/tea"), "解绑后路径释放")
        assertTrue(
            m.bindWorkspace("g-9", "host-a", "/repo/tea").isSuccess,
            "释放后的路径可被任何群重新绑定",
        )
    }

    @Test
    fun differentHostsCanBindTheSameRelativePath() {
        val m = manager()
        assertTrue(m.bindWorkspace("g-1", "host-a", "/repo/tea").isSuccess)
        assertTrue(
            m.bindWorkspace("g-2", "host-b", "/repo/tea").isSuccess,
            "唯一索引是 (groupId, hostNodeId, wsPath)：不同宿主的同名路径互不相干",
        )
    }
}
