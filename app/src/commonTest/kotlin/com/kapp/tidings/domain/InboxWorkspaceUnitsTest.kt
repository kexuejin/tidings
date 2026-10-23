// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.model.AgentArtifactKey
import com.kapp.tidings.domain.model.AgentArtifactUi
import com.kapp.tidings.domain.model.AgentEventUi
import com.kapp.tidings.domain.model.AgentQueueTaskUi
import com.kapp.tidings.domain.model.AgentRunKey
import com.kapp.tidings.domain.model.AgentRunUi
import com.kapp.tidings.domain.model.AgentSessionKey
import com.kapp.tidings.domain.model.AgentSessionUi
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.domain.model.WorkspaceKey
import com.kapp.tidings.domain.model.WorkspaceUi

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

    @Test
    fun activeSessionIgnoresStaleBusyWhenOlderThanTimeout() {
        // 当 busy 会话距今已经超过 STALE_BUSY_TIMEOUT_MS，不再将其视为当前干活中，
        // 而是退回挑更新时间最近的那条（防止异常终止的僵死 busy 永远霸占看板）。
        val unit = state(
            sessions = listOf(
                session("s-stale-busy", state = "busy", updatedAt = 1_000L),
                session("s-new-idle", state = "idle", updatedAt = 1_000L + STALE_BUSY_TIMEOUT_MS + 60_000L),
            ),
        ).firstUnit()
        assertEquals("s-new-idle", unit.active?.sessionId)
    }

    @Test
    fun singleStaleBusySessionNormalizesEffectiveStateToIdle() {
        val unit = state(
            sessions = listOf(
                session("s-only-stale", state = "busy", updatedAt = 1_000L),
            ),
        ).inboxWorkspaceUnits(now = 1_000L + STALE_BUSY_TIMEOUT_MS + 60_000L).groups.single().units.single()
        assertEquals("s-only-stale", unit.active?.sessionId)
        assertEquals("idle", unit.active?.state)
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

    /**
     * 多 Agent 协同在同一个工作区下的消歧与共存：
     * 同一个 wsId 下，DSH 和 Gemini 作为不同的入驻 Agent 各自产生任务会话，
     * 派生层按 `(agentId ?? agentKind, wsId)` 分别归组，互不覆盖，且 key 唯一。
     */
    @Test
    fun multipleAgentsCanCoexistInTheSameWorkspaceDisambiguatedByAgentKey() {
        val s = state(
            sessions = listOf(
                AgentSessionUi(
                    peerId = peerA, sessionId = "s-dsh-1", agentKind = "DSH", agentId = "dsh-engine",
                    wsId = "ws-collab", wsTitle = "Collab Repo", alias = "DSH 重构任务", updatedAt = 2000L,
                ),
                AgentSessionUi(
                    peerId = peerA, sessionId = "s-gemini-1", agentKind = "Gemini", agentId = "gemini-reviewer",
                    wsId = "ws-collab", wsTitle = "Collab Repo", alias = "Gemini 架构审阅", updatedAt = 1000L,
                ),
            ),
        )
        val units = s.inboxWorkspaceUnits().groups.single().units
        assertEquals(2, units.size, "同一个工作区下的两个 Agent 会话各成独立单元")
        val dshUnit = units.first { it.agentKind == "dsh-engine" }
        val geminiUnit = units.first { it.agentKind == "gemini-reviewer" }
        assertEquals("ws-collab", dshUnit.wsId)
        assertEquals("ws-collab", geminiUnit.wsId)
        assertEquals("s-dsh-1", dshUnit.active?.sessionId)
        assertEquals("s-gemini-1", geminiUnit.active?.sessionId)

        // 验证 Compose LazyColumn key 格式完全正交无冲突
        val dshKey = "ws_${dshUnit.peerId}_${dshUnit.agentKind.orEmpty()}_${dshUnit.wsId.orEmpty()}"
        val geminiKey = "ws_${geminiUnit.peerId}_${geminiUnit.agentKind.orEmpty()}_${geminiUnit.wsId.orEmpty()}"
        assertTrue(dshKey != geminiKey, "同一工作区不同 Agent 派生出的列表键严格互斥: $dshKey vs $geminiKey")
    }
    /**
     * **回归（用户反馈「工作区最新会话显示错误」）**：卡片主位必须跟着**实时事件**走，
     * 不能只看宿主动作时刻。
     *
     * 真机形状：agent 正在连续干活（事件实时推、at 是刚刚），而 agent-session 帧
     * 的 hostUpdatedAt 停在很久以前；同时同一工作区里还有一条刚被分页拉回来、
     * hostUpdatedAt 很新的旧会话。
     *
     * 修前：inboxWorkspaceUnits 只按 sortActivityAt 排，sessions.firstOrNull() 挑到
     * 那条「旧、但对本端接收来说很新」的分页会话 —— 而 RelayUiState.agentSessionsOf
     * （会话详情/聊天第一条）一直是 max(sortActivityAt, lastEventAt)，于是同一个工作区
     * 在两屏显示两条不同的会话。
     */
    @Test
    fun theActiveSessionFollowsLiveEventsNotJustTheHostSessionFrame() {
        val chatted = AgentSessionKey(peerA, "s-chatty")
        val unit = state(
            sessions = listOf(
                // 正在干活的那条：会话帧很久没更新，但事件刚刚到。
                session("s-chatty", state = "idle", updatedAt = 1_000L, hostUpdatedAt = 1_000L),
                // 刚被分页拉回来的旧会话：宿主动作时刻很新（本端也在同一秒收到）。
                session("s-paged", state = "idle", updatedAt = 9_000_000L, hostUpdatedAt = 9_000_000L),
            ),
            events = mapOf(
                chatted to listOf(
                    AgentEventUi(
                        sessionId = "s-chatty",
                        peerId = peerA,
                        cursor = 1,
                        eventType = "note",
                        summary = "刚刚推的一条",
                        at = 9_500_000L,
                    ),
                ),
            ),
        ).firstUnit()

        assertEquals("s-chatty", unit.active?.sessionId, "事件实时到的那条才是最新会话")
        assertEquals(9_500_000L, unit.active?.at, "最新活动时刻取事件到达时刻")
        assertEquals("刚刚推的一条", unit.active?.summary)
    }

    /**
     * 同一把尺子还必须是**排序**尺子、不只是挑选尺子：多条都没有 busy/待批时，
     * sortedByLatestActivity 的键序列与 RelayUiState.agentSessionsOf 逐字一致
     * （活动降序 → startedAt 降序 → sessionId 降序）。
     */
    @Test
    fun latestActivityOrderingMatchesTheSessionListOrdering() {
        val a = AgentSessionUi(peerId = peerA, sessionId = "s-a", updatedAt = 1_000L)
        val b = AgentSessionUi(peerId = peerA, sessionId = "s-b", updatedAt = 2_000L)
        val c = AgentSessionUi(peerId = peerA, sessionId = "s-c", updatedAt = 3_000L)
        val ordered = listOf(a, b, c).sortedByLatestActivity { 0L }
        assertEquals(listOf("s-c", "s-b", "s-a"), ordered.map { it.sessionId })

        // 事件把一条更老的会话顶上来（同一处口径的排序侧）。
        val withEvent = listOf(a.copy(hostUpdatedAt = 1_000L), b.copy(hostUpdatedAt = 5_000L))
            .sortedByLatestActivity { s -> if (s.sessionId == "s-a") 9_000L else 0L }
        assertEquals(listOf("s-a", "s-b"), withEvent.map { it.sessionId })
    }

    // ---- 排序稳定性（真机反馈「工作区列表变来变去」）----

    /**
     * **回归**：无宿主序时，行序由**稳定键**（标题）决定，事件不断到达也不改变它。
     *
     * 真机形状：宿主桥从不发 `order`（库里 15/15 工作区的 host_order 是 NULL），于是第一把
     * 尺子全体并列，真正生效的是「待审批 → 最近活动」—— 而最近活动是实时事件驱动的，
     * 谁刚干活谁窜到最前。这条测试就是钉住「事件到达不重排」。
     */
    @Test
    fun workspaceRowsKeepTheTitleOrderWhileEventsKeepArriving() {
        fun wsSession(id: String, wsId: String, title: String) = AgentSessionUi(
            peerId = peerA, sessionId = id, agentKind = "DSH", agentId = "dsh-main",
            wsId = wsId, wsTitle = title, updatedAt = 1L,
        )
        fun event(sessionId: String, at: Long) = AgentEventUi(
            sessionId = sessionId, peerId = peerA, cursor = 1, eventType = "note", at = at,
        )
        fun orderAt(zetaEventAt: Long, alphaEventAt: Long): List<String?> = state(
            sessions = listOf(
                wsSession("s-zeta", "ws-zeta", "Zeta"),
                wsSession("s-alpha", "ws-alpha", "Alpha"),
            ),
            workspaces = listOf(
                workspace("ws-zeta", agentId = "dsh-main", agentKind = "DSH", title = "Zeta"),
                workspace("ws-alpha", agentId = "dsh-main", agentKind = "DSH", title = "Alpha"),
            ),
            events = mapOf(
                AgentSessionKey(peerA, "s-zeta") to listOf(event("s-zeta", zetaEventAt)),
                AgentSessionKey(peerA, "s-alpha") to listOf(event("s-alpha", alphaEventAt)),
            ),
        ).inboxWorkspaceUnits().groups.single().units.map { it.wsId }

        assertEquals(listOf("ws-alpha", "ws-zeta"), orderAt(1_000L, 1_000L), "无宿主序 ⇒ 按标题字典序")
        // Zeta 那边的事件一路往前（每次派生都比上一次新）：顺序必须还是 Alpha, Zeta。
        listOf(2_000L, 5_000L, 50_000L, 900_000L).forEach { at ->
            assertEquals(
                listOf("ws-alpha", "ws-zeta"),
                orderAt(at, 1_000L),
                "事件到达不该改变行序（zeta event at=$at）",
            )
        }
    }

    /**
     * 宿主序存在时**仍以它为准**（设计本意：DSH 里的顺序是用户自己拖出来的）。
     * 这里故意让宿主序与标题字典序**相反**，两种口径才分得开。
     */
    @Test
    fun hostOrderStillWinsOverTheTitleRuler() {
        val units = state(
            sessions = listOf(
                AgentSessionUi(
                    peerId = peerA, sessionId = "s-z", agentKind = "DSH", agentId = "dsh-main",
                    wsId = "ws-zeta", wsTitle = "Zeta", updatedAt = 1L,
                ),
                AgentSessionUi(
                    peerId = peerA, sessionId = "s-a", agentKind = "DSH", agentId = "dsh-main",
                    wsId = "ws-alpha", wsTitle = "Alpha", updatedAt = 1L,
                ),
            ),
            workspaces = listOf(
                workspace("ws-zeta", agentId = "dsh-main", agentKind = "DSH", title = "Zeta").copy(hostOrder = 0L),
                workspace("ws-alpha", agentId = "dsh-main", agentKind = "DSH", title = "Alpha").copy(hostOrder = 5L),
            ),
        ).inboxWorkspaceUnits().groups.single().units

        assertEquals(listOf("ws-zeta", "ws-alpha"), units.map { it.wsId }, "宿主序优先于标题")
    }

    /** 组间排序与行内排序必须**同一把尺子**：无宿主序时按组内最小的稳定名。 */
    @Test
    fun groupsFollowTheSameTitleRulerWhenHostOrderIsMissing() {
        val peerB = "peer-b"
        fun declared(peerId: String, wsId: String, title: String) = WorkspaceUi(
            peerId = peerId, agentKey = "DSH", wsId = wsId, wsTitle = title,
            wsPath = "/src/" + wsId, agentKind = "DSH", online = true, sessionCount = 0, at = 1L,
        )
        val s = RelayUiState(
            peers = listOf(agentPeer(peerA, "开发机"), agentPeer(peerB, "另一台")),
            agentWorkspaces = listOf(
                declared(peerA, "ws-zeta", "Zeta"),
                declared(peerB, "ws-alpha", "Alpha"),
            ).associateBy { WorkspaceKey(it.peerId, it.agentKey, it.wsId) },
        )
        assertEquals(
            listOf(peerB, peerA),
            s.inboxWorkspaceUnits().groups.map { it.peerId },
            "组间也按稳定名（Alpha 那一组在前），与行内同一把尺子",
        )
    }

    /** 稳定名的三段回落：标题 → 路径末段（两种分隔符）→ wsId。 */
    @Test
    fun workspaceSortNameFallsBackFromTitleToPathSegmentToId() {
        assertEquals("标题优先", workspaceSortName("标题优先", "/src/path-name", "ws-1"))
        assertEquals("path-name", workspaceSortName(null, "/src/path-name", "ws-1"))
        assertEquals("path-name", workspaceSortName("   ", "/src/path-name/", "ws-1"), "空白标题不算标题")
        assertEquals("path-name", workspaceSortName(null, "C:\\src\\path-name", "ws-1"), "Windows 反斜杠也要切")
        assertEquals("ws-1", workspaceSortName(null, null, "ws-1"))
        assertEquals("ws-1", workspaceSortName("", "", "ws-1"))
        assertEquals("", workspaceSortName(null, null, null))
    }

    /**
     * `RelayUiState.workspacesOf` 的无 order 回退必须与看板**同一把尺子**：
     * 此前它按"收到时刻倒序"（-at），每来一帧都可能重排，且与看板不同序。
     */
    @Test
    fun workspacesOfUsesTheSameStableRulerWhenHostOrderIsMissing() {
        fun declared(wsId: String, title: String, at: Long) = WorkspaceUi(
            peerId = peerA, agentKey = "DSH", wsId = wsId, wsTitle = title,
            wsPath = "/src/" + wsId, agentKind = "DSH", online = true, sessionCount = 0, at = at,
        )
        val s = RelayUiState(
            peers = listOf(agentPeer(peerA, "开发机")),
            agentWorkspaces = listOf(
                declared("ws-zeta", "Zeta", at = 9_000L),
                declared("ws-alpha", "Alpha", at = 1_000L),
            ).associateBy { WorkspaceKey(peerA, it.agentKey, it.wsId) },
        )
        // 旧口径（-at）会把 Zeta 排在前面；新口径按标题 = Alpha 在前。
        assertEquals(listOf("ws-alpha", "ws-zeta"), s.workspacesOf(peerA).map { it.wsId })
        // 再收一帧宣告（Zeta 的 at 变得更新）也不改顺序。
        val s2 = s.copy(
            agentWorkspaces = s.agentWorkspaces.mapValues { (_, w) ->
                if (w.wsId == "ws-zeta") w.copy(at = 99_000L) else w
            },
        )
        assertEquals(listOf("ws-alpha", "ws-zeta"), s2.workspacesOf(peerA).map { it.wsId })
    }

    // ------------------------------------------------- 空态判据（工作区页面空白）

    /**
     * **只有空壳行时，必须算「没有可视内容」。**
     *
     * 真身：派生层在「这台设备什么都没宣告、也一条会话帧都没收到」时仍造一行
     * 占位行（`unitKeys.isEmpty()` 时补 `WorkspaceRowKey(null, null)`），
     * 那一行 wsTitle=null / sessionCount=0 / active=null，界面上是**一张没有主体的卡**。
     *
     * 看板原来用 `!units.isEmpty` 判空态 ⇒ 这一行把空态挡掉 ⇒ 用户看到的正是
     * 「工作区页面空白，没有空屏效果」。判据改成 `hasVisibleContent` 后，
     * 空壳行不再挡空态。
     */
    @Test
    fun aShellOnlyRowDoesNotCountAsVisibleContent() {
        val shell = WorkspaceUnitUi(
            peerId = peerA,
            agentKind = null,
            wsId = null,
            wsTitle = null,
            sessionCount = 0,
            active = null,
        )
        val units = InboxWorkspaceUnitsUi(
            groups = listOf(
                WorkspaceUnitGroupUi(
                    peerId = peerA,
                    nodeName = "mac-mini",
                    platform = "macos",
                    online = true,
                    lastSeenAt = 0L,
                    agentCount = 1,
                    workspaceCount = 0,
                    units = listOf(shell),
                ),
            ),
            totalUnits = 1,
        )
        assertFalse(units.isEmpty, "派生层确实给了行 —— 这正是旧判据被它挡住的原因")
        assertFalse(units.hasVisibleContent, "空壳行没有可看的内容，不该挡掉空态")
    }

    /**
     * 有名的工作区行（哪怕 0 会话）**算**可视内容 —— 它是用户真的能看到的一个工作区。
     *
     * 这条防止修过头：不能为了让空态显示出来，把「刚宣告、还没会话」的工作区也判成空。
     */
    @Test
    fun aNamedWorkspaceRowCountsAsVisibleContent() {
        val ws = WorkspaceUnitUi(
            peerId = peerA,
            agentKind = "dsh",
            wsId = "ws-alpha",
            wsTitle = "Alpha",
            sessionCount = 0,
            active = null,
        )
        val units = InboxWorkspaceUnitsUi(
            groups = listOf(
                WorkspaceUnitGroupUi(
                    peerId = peerA,
                    nodeName = "mac-mini",
                    platform = "macos",
                    online = true,
                    lastSeenAt = 0L,
                    agentCount = 0,
                    workspaceCount = 1,
                    units = listOf(ws),
                ),
            ),
            totalUnits = 1,
        )
        assertTrue(units.hasVisibleContent, "有名字的工作区行是可视内容")
    }
}