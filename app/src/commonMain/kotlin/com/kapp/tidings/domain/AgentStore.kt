// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.AgentArtifactKey
import com.kapp.tidings.domain.model.AgentArtifactUi
import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.model.AgentEventUi
import com.kapp.tidings.domain.model.AgentQueueTaskUi
import com.kapp.tidings.domain.model.AgentRunKey
import com.kapp.tidings.domain.model.AgentRunUi
import com.kapp.tidings.domain.model.AgentSessionKey
import com.kapp.tidings.domain.model.AgentSessionUi
import com.kapp.tidings.domain.model.GrantUi
import com.kapp.tidings.domain.model.WorkspaceKey
import com.kapp.tidings.domain.model.WorkspaceUi

/**
 * PPE-1 §9 Agent 独立存储抽象：与普通聊天消息表严格隔离。
 *
 * ## 为什么不止 session / event
 *
 * §6.1/§6.2 的会话与事件从一开始就落盘了，§6.3–§6.7 的四类帧却只有内存态。其中
 * **审批的处置结果**是本端产生的、对端无从重算的状态：用户点完「允许」，`agent-ack` 发出去、
 * 面板改成 `approved` —— 一重启又回到「待审批」，而对端早已不再重发那条审批。四类的键与
 * 运行时那几个 map 的键一一对应，落盘与恢复才不会换一套口径。
 */
interface AgentStore {
    fun loadSessions(): Map<AgentSessionKey, AgentSessionUi>

    /**
     * 读回事件流：**每个会话只返回最近 [limitPerSession] 条**，不是全量。
     *
     * 契约定成这个形状而不是「全量」，是因为恢复路径与收帧路径必须是同一个口径：
     * 收帧侧 mergeAgentEvent 的 max 只留最近那批，恢复侧若返回全量，那份全量就再也不会
     * 被裁掉（裁剪只在 merge 时发生，恢复不经过 merge）—— 真机现场是 57,644 条事件常驻。
     *
     * 调用方的 `limitPerSession` 应当就是 RelayController.MAX_AGENT_EVENTS：两端同一个来源，
     * 不另起常量，否则改一处漏一处就会出现「收帧留 200、恢复留别的数」这种半截上限。
     *
     * 组内按 cursor 升序返回（与 mergeAgentEvent 的输出同形，UI 直接按这个顺序画）。
     * 窗口**必须包含每个会话的最新那条** —— 会话行的最后活动时刻靠它（见 RelayUiState）。
     */
    fun loadEvents(limitPerSession: Int): Map<AgentSessionKey, List<AgentEventUi>>

    /** 审批按 `approvalId` 索引（§6.5 把它定义成全局唯一的请求标识）。 */
    fun loadApprovals(): Map<String, AgentApprovalUi>

    /** 队列任务按 `taskId` 索引（§6.3）。 */
    fun loadTasks(): Map<String, AgentQueueTaskUi>

    /** 执行按 `(peerId, runId)` 索引 —— `runId` 由各节点自行分配，会跨节点重名。 */
    fun loadRuns(): Map<AgentRunKey, AgentRunUi>

    /** 产物按 `(peerId, artifactId)` 索引，理由同 [AgentRunKey]。 */
    fun loadArtifacts(): Map<AgentArtifactKey, AgentArtifactUi>

    /**
     * 工作区宣告按 `(peerId, agentKey, wsId)` 索引（PPE-1 增补 D3）。
     *
     * 键里带 agentKey 的理由同 [WorkspaceKey] 的 KDoc：`wsId` 只在同一 Agent 内唯一。
     */
    fun loadWorkspaces(): Map<WorkspaceKey, WorkspaceUi>

    /**
     * 授权结论按 `grantKeyOf` 索引（PPE-1 增补 D7）；空串后缀 = 整台设备那一档。
     *
     * 这里**只**装宿主真的发过帧的条目 —— 缺一格的含义是「沉默」，不是「拒绝」。
     */
    fun loadGrants(): Map<String, GrantUi>

    fun saveSession(session: AgentSessionUi)
    fun saveEvent(event: AgentEventUi)
    fun saveApproval(approval: AgentApprovalUi)
    fun saveTask(task: AgentQueueTaskUi)

    /** `dequeue` 与本地取消都要把它移走（§6.3）；持久层不删，重启后它又会冒出来。 */
    fun deleteTask(peerId: String, taskId: String)

    fun saveRun(run: AgentRunUi)
    fun saveArtifact(artifact: AgentArtifactUi)

    /** 落一条工作区宣告（增补 D3）。**零会话的工作区也必须落** —— 那是这帧存在的唯一理由。 */
    fun saveWorkspace(workspace: WorkspaceUi)

    /** 落一条授权结论（增补 D7）。`revoked` 也要落，否则重启后已授权会复活。 */
    fun saveGrant(grant: GrantUi)

    fun clear()
}

class InMemoryAgentStore : AgentStore {
    private val sessions = mutableMapOf<AgentSessionKey, AgentSessionUi>()
    private val events = mutableMapOf<AgentSessionKey, MutableList<AgentEventUi>>()
    private val approvals = mutableMapOf<String, AgentApprovalUi>()
    private val tasks = mutableMapOf<String, AgentQueueTaskUi>()
    private val runs = mutableMapOf<AgentRunKey, AgentRunUi>()
    private val artifacts = mutableMapOf<AgentArtifactKey, AgentArtifactUi>()
    private val workspaces = mutableMapOf<WorkspaceKey, WorkspaceUi>()
    private val grants = mutableMapOf<String, GrantUi>()

    override fun loadSessions(): Map<AgentSessionKey, AgentSessionUi> = sessions.toMap()
    /**
     * 与 SQL 实现同语义：每个会话只给最近 [limitPerSession] 条（按 cursor 取尾部）。
     *
     * 内存实现里本来是「写进来多少就留着多少」，但 saveEvent 不会裁剪，而恢复路径读到的东西
     * 会直接进 UI 状态。两个实现的返回值形状必须一致，否则同一个测试在两份 store 上给出
     * 两种结论 —— 那种假绿比没测更糟。
     */
    override fun loadEvents(limitPerSession: Int): Map<AgentSessionKey, List<AgentEventUi>> {
        val limit = limitPerSession.coerceAtLeast(0)
        return events.mapValues { (_, list) -> list.sortedBy { it.cursor }.takeLast(limit) }
            .filterValues { it.isNotEmpty() }
    }
    override fun loadApprovals(): Map<String, AgentApprovalUi> = approvals.toMap()
    override fun loadTasks(): Map<String, AgentQueueTaskUi> = tasks.toMap()
    override fun loadRuns(): Map<AgentRunKey, AgentRunUi> = runs.toMap()
    override fun loadArtifacts(): Map<AgentArtifactKey, AgentArtifactUi> = artifacts.toMap()
    override fun loadWorkspaces(): Map<WorkspaceKey, WorkspaceUi> = workspaces.toMap()
    override fun loadGrants(): Map<String, GrantUi> = grants.toMap()

    override fun saveSession(session: AgentSessionUi) {
        sessions[AgentSessionKey(session.peerId, session.sessionId)] = session
    }

    override fun saveEvent(event: AgentEventUi) {
        val key = AgentSessionKey(event.peerId, event.sessionId)
        val list = events.getOrPut(key) { mutableListOf() }
        if (list.none { it.cursor == event.cursor }) {
            list.add(event)
            list.sortBy { it.cursor }
        }
    }

    override fun saveApproval(approval: AgentApprovalUi) {
        approvals[approval.approvalId] = approval
    }

    override fun saveTask(task: AgentQueueTaskUi) {
        tasks[task.taskId] = task
    }

    override fun deleteTask(peerId: String, taskId: String) {
        // 与 SQL 实现同语义：必须带 peerId 对上才删 —— 两个 peer 的 taskId 可能撞名。
        if (tasks[taskId]?.peerId == peerId) tasks.remove(taskId)
    }

    override fun saveRun(run: AgentRunUi) {
        runs[AgentRunKey(run.peerId, run.runId)] = run
    }

    override fun saveArtifact(artifact: AgentArtifactUi) {
        artifacts[AgentArtifactKey(artifact.peerId, artifact.artifactId)] = artifact
    }

    override fun saveWorkspace(workspace: WorkspaceUi) {
        workspaces[WorkspaceKey(workspace.peerId, workspace.agentKey, workspace.wsId)] = workspace
    }

    override fun saveGrant(grant: GrantUi) {
        grants[grantKeyOf(grant.peerId, grant.agentKey, grant.wsId)] = grant
    }

    override fun clear() {
        sessions.clear()
        events.clear()
        approvals.clear()
        tasks.clear()
        runs.clear()
        artifacts.clear()
        // 增补 D3/D7 的两张表同样归零：clear() 的语义是「这个设备的 agent 数据全清」，
        // 漏掉这两张会让用户清完还看得见旧工作区与旧授权 —— 那是最像「按钮坏了」的一种残影。
        workspaces.clear()
        grants.clear()
    }
}

