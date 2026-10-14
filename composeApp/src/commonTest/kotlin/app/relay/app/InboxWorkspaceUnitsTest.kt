// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.relay.app.model.AgentApprovalUi
import app.relay.app.model.AgentArtifactKey
import app.relay.app.model.AgentArtifactUi
import app.relay.app.model.AgentEventUi
import app.relay.app.model.AgentQueueTaskUi
import app.relay.app.model.AgentRunKey
import app.relay.app.model.AgentRunUi
import app.relay.app.model.AgentSessionKey
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.PeerUi
import app.relay.app.model.WorkspaceKey
import app.relay.app.model.WorkspaceUi

/**
 * 消息页「工作区」段的取数。
 *
 * 这一段今天与工作区看板**共用同一张卡**（`ui/components/WorkspaceUnitCard.kt`），
 * 所以这里钉的不是"画得对不对"，而是**卡片要的那几个字段是否与看板同一口径**：
 * 活跃会话挑哪一条、计数按什么维度、名字与摘要怎么降级。两处一旦分叉，同一个 Agent
 * 在消息页与看板就会写出不同的会话名与不同的数字 —— 这类分叉界面测试看不出来。
 */
class InboxWorkspaceUnitsTest {

    private val peerA = "peer-a"

    private fun agentPeer(nodeId: String, nick: String = nodeId) = PeerUi(
        nodeId = nodeId,
        nick = nick,
        online = true,
        caps = listOf(MsgTypes.CAP_AGENT),
    )

    private fun session(
        id: String,
        state: String? = null,
        alias: String? = null,
        agentKind: String? = "claude",
        title: String? = null,
        updatedAt: Long = 0L,
        hostUpdatedAt: Long? = null,
    ) = AgentSessionUi(
        peerId = peerA,
        sessionId = id,
        alias = alias,
        agentKind = agentKind,
        state = state,
        title = title,
        updatedAt = updatedAt,
        hostUpdatedAt = hostUpdatedAt,
    )

    private fun state(
        sessions: List<AgentSessionUi> = emptyList(),
        events: Map<AgentSessionKey, List<AgentEventUi>> = emptyMap(),
        approvals: List<AgentApprovalUi> = emptyList(),
        tasks: Map<String, AgentQueueTaskUi> = emptyMap(),
        runs: Map<AgentRunKey, AgentRunUi> = emptyMap(),
        artifacts: Map<AgentArtifactKey, AgentArtifactUi> = emptyMap(),
        workspaces: List<WorkspaceUi> = emptyList(),
    ) = RelayUiState(
        peers = listOf(agentPeer(peerA, "开发机")),
        agentSessions = sessions.associateBy { AgentSessionKey(peerA, it.sessionId) },
        agentEvents = events,
        agentApprovals = approvals.associateBy { it.approvalId },
        agentTasks = tasks,
        agentRuns = runs,
        agentArtifacts = artifacts,
        agentWorkspaces = workspaces.associateBy { WorkspaceKey(peerA, it.agentKey, it.wsId) },
    )

    private fun RelayUiState.firstUnit(): WorkspaceUnitUi =
        inboxWorkspaceUnits().groups.single().units.single()

    @Test
    fun theOnlyUnitIsTheAgentKindOfTheNode() {
        val unit = state(sessions = listOf(session("s1"))).firstUnit()
        assertEquals(peerA, unit.peerId)
        assertEquals("claude", unit.agentKind)
    }

    /**
     * PPE-1 增补 D1/D6 上线后的阶段二分组：行的身份 = `(agentId ?? agentKind, wsId)`。
     *
     * 同一个 Agent 的两个工作区各成一行；没带 wsId 的会话进「未归属」那一行（合法的一等
     * 状态，不许编一个默认工作区收容它）。wsId 一旦存在，段头的单位词从「Agent」翻到
     * 「工作区」（countsWorkspaces）。
     */
    @Test
    fun workspaceRowsSplitByAgentAndWorkspaceId() {
        fun wsSession(id: String, wsId: String?, wsTitle: String?, agentId: String? = "dsh-main") =
            AgentSessionUi(
                peerId = peerA, sessionId = id, agentKind = "DSH", agentId = agentId,
                wsId = wsId, wsTitle = wsTitle, updatedAt = 1L,
            )
        val s = state(
            sessions = listOf(
                wsSession("s-a", "ws-1", "teahouse-mobile"),
                wsSession("s-b", "ws-1", "teahouse-mobile"),
                wsSession("s-c", "ws-2", "kapp-lib"),
                wsSession("s-d", null, null),
            ),
        )
        val result = s.inboxWorkspaceUnits()
        val units = result.groups.single().units
        assertEquals(3, units.size, "两个工作区 + 一个未归属 = 三行")
        val byKey = units.associateBy { it.wsId ?: "<none>" }
        assertEquals(2, byKey.getValue("ws-1").sessionCount)
        assertEquals("teahouse-mobile", byKey.getValue("ws-1").wsTitle)
        assertEquals(1, byKey.getValue("ws-2").sessionCount)
        assertEquals("kapp-lib", byKey.getValue("ws-2").wsTitle)
        assertNull(byKey.getValue("<none>").wsId, "未归属行的 wsId 保持 null，不编默认工作区")
        assertTrue(result.countsWorkspaces)
        assertEquals(2, result.workspaceCount)
        assertEquals(1, result.agentCount)

        // agentId 把同类两个实例分开（D6 的存在理由）；没报 agentId 时按 agentKind 合并（退化）。
        val twoAgents = state(
            sessions = listOf(
                wsSession("s-x", "ws-1", "a", agentId = "dsh-1"),
                wsSession("s-y", "ws-1", "a", agentId = "dsh-2"),
            ),
        ).inboxWorkspaceUnits().groups.single().units
        assertEquals(2, twoAgents.size, "同工作区不同 agentId 是两行")
    }

    @Test
    fun activeSessionPrefersTheOneWithAPendingApprovalEvenIfItIsIdle() {
        // busy 的更新，待审批的那条更旧 —— 挑的必须是待审批那条：
        // "有一项等你批"比"正在干活"更需要被看见（看板原文口径）。
        val unit = state(
            sessions = listOf(
                session("s-busy", state = "busy", updatedAt = 9_000L),
                session("s-approve", state = "idle", updatedAt = 1_000L),
            ),
            approvals = listOf(
                AgentApprovalUi(approvalId = "a1", sessionId = "s-approve", peerId = peerA, status = "pending"),
            ),
        ).firstUnit()
        assertEquals("s-approve", unit.active?.sessionId)
    }

    @Test
    fun activeSessionFallsBackToBusyAndThenToTheMostRecentlyUpdated() {
        val busy = state(
            sessions = listOf(
                session("s-old-busy", state = "busy", updatedAt = 1_000L),
                session("s-new-idle", state = "idle", updatedAt = 9_000L),
            ),
        ).firstUnit()
        // 没有待审批时 busy 压过"更新时间更近"（看板逐字一致）。
        assertEquals("s-old-busy", busy.active?.sessionId)

        val newest = state(
            sessions = listOf(
                session("s-old", state = "done", updatedAt = 1_000L),
                session("s-new", state = "idle", updatedAt = 9_000L),
            ),
        ).firstUnit()
        assertEquals("s-new", newest.active?.sessionId)
    }

    /**
     * 「最关键的那条会话」必须按**宿主活动时刻**挑，不是按"本端收到帧的时刻"。
     *
     * 历史分页一页 20 条走同一个 TCP tick 到达，`updatedAt` 全落在同一毫秒 ——
     * 那时卡片主位那句会话名会随 map 插入序乱跳，而用户会拿它跟 DSH 里看到的那条对照
     * （原话「跟 dsh 上显示的不一样，不是最新的」）。
     */
    @Test
    fun theActiveSessionFollowsHostActivityWhenTheFramesArriveTogether() {
        val unit = state(
            sessions = listOf(
                // 三条同时刻到达：只有宿主活动时刻能分出谁最该在卡片主位。
                session("s-oldest", state = "idle", updatedAt = 5_000L, hostUpdatedAt = 1_000L),
                session("s-newest", state = "idle", updatedAt = 5_000L, hostUpdatedAt = 9_000L),
                session("s-middle", state = "idle", updatedAt = 5_000L, hostUpdatedAt = 4_000L),
            ),
        ).firstUnit()

        assertEquals("s-newest", unit.active?.sessionId, "卡片主位要跟宿主的活动序一致")
    }

    @Test
    fun thereIsNoActiveSessionWhenTheNodeAnnouncedNothing() {
        // 声明了 ag1 却一条会话帧都没收到：仍然给一行，但**不许编**一条会话出来。
        val unit = state().firstUnit()
        assertNull(unit.agentKind)
        assertNull(unit.active)
        assertEquals(0, unit.sessionCount)
    }

    @Test
    fun activeTitlePrefersAliasAndSummaryFallsBackToTheSessionTitle() {
        val key = AgentSessionKey(peerA, "s1")
        val withSummary = state(
            sessions = listOf(session("s1", alias = "修登录", title = "会话标题", updatedAt = 1_000L)),
            events = mapOf(
                key to listOf(AgentEventUi(sessionId = "s1", peerId = peerA, cursor = 1, eventType = "note", summary = "摘要", body = "正文", at = 2_000L)),
            ),
        ).firstUnit()
        assertEquals("修登录", withSummary.active?.title)
        assertEquals("摘要", withSummary.active?.summary)
        assertEquals(2_000L, withSummary.active?.at)

        val bodyOnly = state(
            sessions = listOf(session("s1", title = "会话标题", updatedAt = 1_000L)),
            events = mapOf(
                key to listOf(AgentEventUi(sessionId = "s1", peerId = peerA, cursor = 1, eventType = "note", body = "正文", at = 2_000L)),
            ),
        ).firstUnit()
        assertEquals("正文", bodyOnly.active?.summary)

        // 没有事件时摘要退化到会话标题，时刻退化到会话的**上报时间** —— 底栏的「最新活动」
        // 不能因为缺事件就显示成 1970 年。
        val noEvent = state(sessions = listOf(session("s1", alias = null, title = "会话标题", updatedAt = 1_000L))).firstUnit()
        // 2026-10-14 改：主标题用三级回落（alias → title → sessionId）。此前这里断言的是
        // `s1` —— 那条把「漏掉 title」写成了契约，正是用户看到「只显示 session id」的原因。
        // 现在没有 alias 但有 title 时，标题就该是 title。
        assertEquals("会话标题", noEvent.active?.title)
        assertEquals("会话标题", noEvent.active?.summary)
        assertEquals(1_000L, noEvent.active?.at)
    }

    /**
     * 分页拉回来的**历史会话**（还没有任何事件）在卡片上必须说真话：
     * 「最新活动」是它在宿主侧最后一次动的时刻，**不是**这一页帧到达本端的时刻。
     *
     * 反例是这样产生的：用户点开一个三天没动过的工作区 —— 那一页 20 条帧确实是"刚刚"到的，
     * 用本端到达时刻就会让**越久没碰的工作区越写「最新活动 刚刚」**。而用户在 DSH 里看到的是
     * 「3 天前」，两边不一致正是他抱怨的那件事，只不过换了个字段暴露出来。
     */
    @Test
    fun aPagedSessionWithNoEventsShowsItsHostActivityNotWhenThePageArrived() {
        val unit = state(
            sessions = listOf(
                // 本端收帧时刻 = 现在（这一页刚回来），宿主动作时刻 = 三天前。
                session("s-paged", alias = "历史会话", updatedAt = 9_000_000L, hostUpdatedAt = 1_000L),
            ),
        ).firstUnit()

        assertEquals("历史会话", unit.active?.title)
        assertEquals(1_000L, unit.active?.at, "分页拉回的历史会话该说宿主动作时刻")
    }

    @Test
    fun countsArePerPeerSoTwoKindsOfTheSameNodeAgree() {
        // 计数按 `peerId`、不按本行的会话集 —— 与看板逐字一致：一个节点只画一张卡时
        // 这两者没有区别，但一个节点上报两种 agentKind 时会画出两行，两行必须报同一个数。
        val ui = state(
            sessions = listOf(
                session("s1", agentKind = "claude"),
                session("s2", agentKind = "codex"),
            ),
            tasks = mapOf("t1" to AgentQueueTaskUi(taskId = "t1", sessionId = "s1", peerId = peerA, op = "run")),
            runs = mapOf(
                AgentRunKey(peerA, "r1") to AgentRunUi(runId = "r1", sessionId = "s2", peerId = peerA, state = "succeeded"),
            ),
            artifacts = mapOf(
                AgentArtifactKey(peerA, "f1") to AgentArtifactUi(artifactId = "f1", sessionId = "s1", peerId = peerA, name = "diff.patch"),
            ),
        ).inboxWorkspaceUnits()

        val units = ui.groups.single().units
        assertEquals(2, units.size)
        units.forEach { unit ->
            assertEquals(1, unit.queueCount)
            assertEquals(1, unit.runCount)
            assertEquals(1, unit.artifactCount)
        }
    }

    // ------------------------------------- PPE-1 增补 D3：空工作区行（2026-10-14）

    /**
     * 造一条宣告。`agentId` 与 `agentKind` 分开传是**刻意的**：归组键是 `agentId ?? agentKind`，
     * 而会话侧报的是 agentId —— 夹具只给 agentKind 就构造出了"两侧键不同"的错误形状
     * （第一版这么写，把同一个工作区拆成了两行，正是这条测试要防的）。
     */
    private fun workspace(
        wsId: String,
        agentId: String? = null,
        agentKind: String? = null,
        title: String? = null,
        at: Long = 0L,
    ) = WorkspaceUi(
        peerId = peerA,
        agentKey = agentId ?: agentKind,
        agentId = agentId,
        wsId = wsId,
        wsTitle = title,
        wsPath = "/src/" + wsId,
        agentKind = agentKind,
        online = true,
        sessionCount = 0,
        at = at,
    )

    /**
     * **空工作区行**（只有 `agent-workspace` 宣告、一条会话都没有）。
     *
     * 这一条测的是 D3 存在的**唯一理由**（父稿 §2.2）：只从 `agentSessions` 派生的世界里，
     * "工作区已存在但还没有任何会话"根本画不出来 —— 那段目录在界面上整行消失。
     * 所以这里断言它**出现了**，且 `declaredOnly = true`（空态文案与"从未带 wsId"那一档
     * 的手势不同：前者该写「已宣告 · 暂无会话」，后者是「尚未收到工作区宣告」）。
     */
    @Test
    fun aWorkspaceWithNoSessionsStillGetsItsOwnRow() {
        val units = state(workspaces = listOf(workspace("ws-empty", agentKind = "DSH", title = "空工作区"))).inboxWorkspaceUnits()
            .groups.single().units
        assertEquals(1, units.size, "零会话的宣告工作区必须占一行 —— 否则 D3 白收")
        val unit = units.single()
        assertEquals("ws-empty", unit.wsId)
        assertEquals("空工作区", unit.wsTitle)
        assertTrue(unit.declaredOnly, "这一行只来自宣告帧，没有会话")
        assertEquals(0, unit.sessionCount)
        assertNull(unit.active, "没有会话 ⇒ 没有活跃会话快照（屏上写「暂无会话」）")
    }

    /**
     * 同一 `(agentKey, wsId)` 的**会话行与宣告行必须合成一行**，不是两行。
     *
     * 宿主既宣告了工作区、又在其中开了会话时，两个来源会同时命中同一个键
     * （`inboxWorkspaceUnits()` 的并集派生）。合成失败的症状是界面上同一个目录出现两次，
     * 而用户无从知道哪一行才是它。
     */
    @Test
    fun declarationAndSessionForTheSameWorkspaceMergeIntoOneRow() {
        val units = state(
            sessions = listOf(
                AgentSessionUi(
                    peerId = peerA, sessionId = "s-1", agentKind = "DSH", agentId = "dsh-cli",
                    wsId = "ws-1", wsTitle = "工作区一", state = "busy", updatedAt = 10L,
                ),
            ),
            // 宣告帧也带 agentId（与会话侧同键）—— D3 帧的 agentId 是本次实现补上的，
            // 少了它两侧归组键不同，同一工作区会被拆成两行（这条测试就是判它红的）。
            workspaces = listOf(workspace("ws-1", agentId = "dsh-cli", agentKind = "DSH", title = "工作区一")),
        ).inboxWorkspaceUnits().groups.single().units
        assertEquals(1, units.size, "同一工作区的会话与宣告必须合成一行，实际 $units")
        val unit = units.single()
        assertEquals(1, unit.sessionCount, "会话数按会话侧算")
        assertFalse(unit.declaredOnly, "有会话 ⇒ 不是空工作区行")
        assertEquals("s-1", unit.active?.sessionId)
    }

    /**
     * 宣告帧与**从未带过 wsId** 的会话是两类行，不能互相顶替。
     *
     * 后者（`wsId == null`）的含义是"这台设备有 agent，但我们不知道它有没有工作区"，
     * 屏上写「尚未收到工作区宣告」；前者是"工作区在、今天没活干"。合成一句就是拿一种
     * 情况的话去说另一种情况 —— 这正是 `declaredOnly` 要把它们分开的理由。
     */
    @Test
    fun declaredEmptyWorkspaceIsADifferentRowFromAnAgentWithoutAnyWorkspace() {
        val units = state(
            sessions = listOf(session("s-plain")), // agentKind = "claude"，没有任何 ws 键
            workspaces = listOf(workspace("ws-1", agentId = "dsh-cli", agentKind = "DSH", title = "有宣告的工作区")),
        ).inboxWorkspaceUnits().groups.single().units
        assertEquals(2, units.size, "两者是两行，实际 $units")
        val declared = units.first { it.wsId == "ws-1" }
        val blind = units.first { it.wsId == null }
        assertTrue(declared.declaredOnly)
        assertFalse(blind.declaredOnly, "没有 wsId 的行不是「空工作区」，是「不知道有没有工作区」")
        // 盲行**有**会话（它是从会话派生的）—— 这正是它与空工作区行的区别所在：
        // 一个是"有活干但没说在哪"，一个是"说了在哪但没活干"。
        assertNotNull(blind.active, "盲行是从会话派生的，它有活跃会话；这与空工作区行正相反")
    }

    // ---- 会话主标题：alias → title → null（2026-10-14 真机抓到两次）----

    /**
     * **DSH 推来的历史会话只带 `title`、不带 `alias`**，标题必须落在 title 上。
     *
     * 这条规则以前被抄了 4 遍（两处卡片、切换条、聊天页头），**每一遍都漏了 title**：
     * 于是那些会话在手机上显示成 `session-3f2a…` 这样的 id —— 用户原话「怎么只显示的是
     * session id，而不是会话的标题？」。现在只有一处实现（`AgentSessionUi.displayTitle`）。
     *
     * **末级是 `null`（"没有名字"），不是 `sessionId`**：确实有一批会话从没有过
     * `session/title` 事件（开了就结束、一句 prompt 都没发），折不出标题。对这批会话
     * 把身份键印给用户是编的，「未命名」才是如实的 —— DSH 自己在同一处境下也说
     * `session.untitled`（`ui-workspace/.../rows/Rows.tsx`）。本地化由调用方做：
     * `model/` 包里没有 `stringResource`，文案不许落在数据层。
     */
    @Test
    fun sessionTitleFallsBackAliasThenTitleThenNothing() {
        val base = AgentSessionUi(peerId = "p1", sessionId = "session-3f2a")
        assertNull(base.displayTitle, "alias 与 title 都没有 = 没有名字，不许拿 sessionId 顶替")

        val titled = base.copy(title = "把消息页签的工作区段接进 Compose")
        assertEquals("把消息页签的工作区段接进 Compose", titled.displayTitle, "有 title 就用 title —— 这正是 DSH 历史会话的形状")

        val aliased = titled.copy(alias = "refactor runner")
        assertEquals("refactor runner", aliased.displayTitle, "alias 优先于 title（它是人给的展示名）")

        // 空串与空白不算数：问了「有没有值」而不是「有没有键」。
        assertEquals("把消息页签的工作区段接进 Compose", titled.copy(alias = "").displayTitle)
        assertEquals("把消息页签的工作区段接进 Compose", titled.copy(alias = "   ").displayTitle)
        assertNull(base.copy(title = "  ").displayTitle, "title 是空白 = 还是没有名字")
    }
}
