// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * agentNode 进程内的工作区台账（PPE-1 增补 D3，`agent-workspace`）。
 *
 * ## 它补的是哪一档
 *
 * `agent-session` 的 ws 键只到"会话带着工作区身份来"——**工作区存在但还没有任何会话**
 * 这一档（父稿 §1「未归属会话是合法的一等状态」的对偶：空工作区只能靠显式宣告），
 * 只有 `agent-workspace` 帧能表达。没有这张表，面板的「第三面」在宿主刚启动、
 * 还没开会话时是空的，而工作区明明在。
 *
 * ## 与 AgentSessionRegistry 的关系
 *
 * 同一个进程里两份台账，**不合并**：会话台账的键是 `(nodeId, sessionId)`、活的是
 * "会话的当前态"；这里的键是 `(nodeId, agentKey, wsId)`（父稿 §1 的工作区键），
 * 活的是"工作区的当前态"。派生关系（工作区的会话数）在读取侧现算
 * （[mergeSessionCounts]），不留两份会漂移的真源 —— 与 SessionRecord.synthesized
 * "派生信号不存字段"同一纪律。
 *
 * 只活在内存里（有意，与会话台账同取舍）：工作区列表是**当前态**视图，
 * 跨重启累积的表会越长越假；长期留存是客户端 `relay.db` 的职责。
 *
 * @param maxWorkspaces 台账上限；超出按 updatedAt 淘汰最老的（防无界增长）。
 */
class AgentWorkspaceRegistry(private val maxWorkspaces: Int = 200) {

    /** 一个工作区的当前态。字段与 D3 帧一一对应（父稿 §2.2 的键表）。 */
    data class WorkspaceRecord(
        val nodeId: String,
        /** Agent 身份键 = `agentId ?? agentKind`（父稿 D6）；null = 对端没报，不猜。 */
        val agentKey: String?,
        val wsId: String,
        val wsTitle: String?,
        val wsPath: String?,
        /** 该工作区的**主要** agentKind（零会话时类型徽标的来源之一）。 */
        val agentKind: String?,
        /** 宿主自报的在线态；null = 没报（UI 不许拿"缺省"当"在线"）。 */
        val online: Boolean?,
        /** 宿主自报的会话数；手机端自己数的优先（[mergeSessionCounts]）。 */
        val sessionCount: Long?,
        val updatedAt: Long,
    )

    private data class Key(val nodeId: String, val agentKey: String?, val wsId: String)

    private val workspaces = ConcurrentHashMap<Key, WorkspaceRecord>()

    /**
     * 记下一次工作区宣告（出站或入站）。
     *
     * 同键重投就地合并：非空字段覆盖、空字段保留旧值（改名帧没带 path 时不该把 path 抹掉），
     * 与 `recordSession` 同一合并纪律。
     */
    fun record(
        nodeId: String,
        agentKey: String?,
        wsId: String,
        wsTitle: String? = null,
        wsPath: String? = null,
        agentKind: String? = null,
        online: Boolean? = null,
        sessionCount: Long? = null,
        outbound: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        val key = Key(nodeId, agentKey, wsId)
        val old = workspaces[key]
        // outbound 标记**或**运算：与 SessionRecord 的两个 announced 标记同理，
        // 但这里只有一列 —— 工作区没有"两侧各自宣告"的语义分叉需求，
        // 面板要区分时看 updatedAt 与来源即可，多一列就多一个能说谎的地方。
        workspaces[key] = WorkspaceRecord(
            nodeId = nodeId,
            agentKey = agentKey ?: old?.agentKey,
            wsId = wsId,
            wsTitle = wsTitle ?: old?.wsTitle,
            wsPath = wsPath ?: old?.wsPath,
            agentKind = agentKind ?: old?.agentKind,
            online = online ?: old?.online,
            sessionCount = sessionCount ?: old?.sessionCount,
            updatedAt = maxOf(at, old?.updatedAt ?: at),
        )
        evictIfNeeded()
    }

    fun workspaces(): List<WorkspaceRecord> =
        workspaces.values.sortedByDescending { it.updatedAt }

    fun workspacesOf(nodeId: String): List<WorkspaceRecord> =
        workspaces().filter { it.nodeId == nodeId }

    fun eventCount(): Int = workspaces.size

    /**
     * 用会话台账给工作区行**现算**会话数（自报数只在对端没会话数据时兜底）。
     * 返回派生后的副本，台账本身不被改写 —— 真源只有一份。
     */
    fun mergeSessionCounts(sessions: AgentSessionRegistry): List<WorkspaceRecord> =
        workspaces().map { w ->
            val counted = sessions.countWorkspaces(w.nodeId, w.agentKey, w.wsId).toLong()
            if (counted > 0L || w.sessionCount == null) w.copy(sessionCount = counted) else w
        }

    private fun evictIfNeeded() {
        if (workspaces.size <= maxWorkspaces) return
        val doomed = workspaces.values.sortedBy { it.updatedAt }.take(workspaces.size - maxWorkspaces)
        doomed.forEach { workspaces.remove(Key(it.nodeId, it.agentKey, it.wsId)) }
    }
}
