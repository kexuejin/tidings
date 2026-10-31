// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.workspace

import app.cash.sqldelight.db.SqlDriver
import com.kapp.tidings.db.RelayDatabase
import com.kapp.tidings.db.WorkspaceBindingEntity
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.newId

/**
 * 一次工作区绑定的快照（群 ↔ 宿主物理工作区）。
 *
 * `gitBranch` 是**绑定时刻**的分支快照 —— 不是实时状态（实时状态是 T-2c 执行链路的
 * `git status` 职责，管理器只管 1:1 防重与持久化）。
 */
data class WorkspaceBinding(
    val workspaceId: String,
    val groupId: String,
    val hostNodeId: String,
    val wsPath: String,
    val gitBranch: String,
    val boundAt: Long,
)

/** 绑定失败的两种原因（判据可区分 —— UI 据此说不同的人话）。 */
sealed class WorkspaceBindFailure {
    /** 路径已被**别的群**绑定（1:1 防重命中）。 */
    data class PathBoundByOther(val binding: WorkspaceBinding) : WorkspaceBindFailure()

    /** 写库失败（磁盘 / 约束之外的意外）。 */
    data class Storage(val cause: Throwable) : WorkspaceBindFailure()
}

/**
 * 工作区 1:1 防重管理器（升级轮 T-2b，设计 §5.2；命名 D-025③ —— 与 agentNode 的
 * `WorkspaceRegistry`（agent-workspace 帧台账）是两套东西：这里管 relay.db 的
 * `WorkspaceBindingEntity`（群 ↔ 宿主物理工作区绑定），后者管跨端宣告帧的台账）。
 *
 * ## 防重的落点
 *
 * `WorkspaceBindingEntity` 的 UNIQUE INDEX (groupId, hostNodeId, wsPath) ——
 * 同一宿主的同一条物理路径只能被一个群绑定。两级判定：
 *  1. **应用层先查**（[isPathBound]）：给调用方可区分的失败原因（PathBoundByOther
 *     带上占着的那条绑定，UI 说得出「被哪个群占着」）；
 *  2. **数据库索引兜底**：先查后插之间的竞态由 UNIQUE 约束炸掉（插入失败的
 *     Storage 分支），不靠时序运气。
 *
 * ## 1:1 语义
 *
 * 同群同宿主换路径 = **换绑定**（旧路径解绑、新路径立约束）—— 一个群在一个宿主上
 * 只有一个活动工作区；不同宿主的同名路径互不相干（索引含 hostNodeId）。
 */
class WorkspaceBindingManager(private val database: RelayDatabase) {

    private val queries = database.relayDatabaseQueries

    /**
     * 将群聊与具体的物理宿主工作区绑定。
     *
     * @param gitBranch 绑定时刻的分支快照（执行链路在沙箱里开 worktree 时的基准）。
     */
    fun bindWorkspace(
        groupId: String,
        hostNodeId: String,
        absolutePath: String,
        gitBranch: String = "main",
    ): Result<WorkspaceBinding> {
        // 1:1 语义第一半：同群同宿主的旧绑定先让位（换路径 = 换绑定，不是双绑）。
        queries.deleteWorkspaceBinding(groupId, hostNodeId)
        // 应用层友好判定：路径被**别的群**占着 ⇒ 失败并带回占着的那条。
        val holder = queries.selectWorkspaceBindingByHostAndPath(hostNodeId, absolutePath)
            .executeAsOneOrNull()?.toBinding()
        if (holder != null && holder.groupId != groupId) {
            return Result.failure(WorkspaceBindException(WorkspaceBindFailure.PathBoundByOther(holder)))
        }
        val binding = WorkspaceBinding(
            workspaceId = newBindingId(),
            groupId = groupId,
            hostNodeId = hostNodeId,
            wsPath = absolutePath,
            gitBranch = gitBranch,
            boundAt = nowMillis(),
        )
        return try {
            // 竞态兜底：先查后插之间有人插了同路径 ⇒ UNIQUE 索引炸进 Storage 分支。
            queries.insertWorkspaceBinding(
                workspaceId = binding.workspaceId,
                groupId = binding.groupId,
                hostNodeId = binding.hostNodeId,
                wsPath = binding.wsPath,
                gitBranch = binding.gitBranch,
                isHostActive = 1L,
                createdAt = binding.boundAt,
                updatedAt = binding.boundAt,
            )
            Result.success(binding)
        } catch (e: Exception) {
            Result.failure(WorkspaceBindException(WorkspaceBindFailure.Storage(e)))
        }
    }

    /** 当前路径是否已被绑定（防重的友好查询；竞态仍由索引兜底）。 */
    fun isPathBound(hostNodeId: String, absolutePath: String): Boolean =
        queries.selectWorkspaceBindingByHostAndPath(hostNodeId, absolutePath)
            .executeAsOneOrNull() != null

    /** 指定群在指定宿主上的绑定（没有 ⇒ null）。 */
    fun bindingOf(groupId: String, hostNodeId: String): WorkspaceBinding? =
        queries.selectWorkspaceBindingByGroupAndHost(groupId, hostNodeId)
            .executeAsOneOrNull()?.toBinding()

    /** 指定群的全部绑定（跨宿主 —— 群可以把不同任务派到不同宿主的工作区）。 */
    fun bindingsOf(groupId: String): List<WorkspaceBinding> =
        queries.selectWorkspaceBindingsByGroup(groupId).executeAsList().map { it.toBinding() }

    /** 解绑（群解散 / 工作区下线）。路径随之释放。 */
    fun unbind(groupId: String, hostNodeId: String) {
        queries.deleteWorkspaceBinding(groupId, hostNodeId)
    }

    /** 群删除时清掉它的全部绑定。 */
    fun unbindAll(groupId: String) {
        queries.deleteWorkspaceBindingsByGroup(groupId)
    }

    private fun WorkspaceBindingEntity.toBinding() = WorkspaceBinding(
        workspaceId = workspaceId,
        groupId = groupId,
        hostNodeId = hostNodeId,
        wsPath = wsPath,
        gitBranch = gitBranch,
        boundAt = createdAt,
    )

    private fun newBindingId(): String = "wsb-" + newId().take(20)
}

/** 绑定失败异常：[failure] 携带可区分的原因（PathBoundByOther / Storage）。 */
class WorkspaceBindException(val failure: WorkspaceBindFailure) :
    Exception(failure.describe())

private fun WorkspaceBindFailure.describe(): String = when (this) {
    is WorkspaceBindFailure.PathBoundByOther ->
        "workspace " + binding.wsPath + " on " + binding.hostNodeId +
            " is already bound to group " + binding.groupId
    is WorkspaceBindFailure.Storage ->
        "storage failure: " + cause.message
}
