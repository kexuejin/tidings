// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.domain.model.AgentArtifactKey
import com.kapp.tenon.domain.model.AgentArtifactUi
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.domain.model.WorkspaceKey
import com.kapp.tenon.domain.model.WorkspaceUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 智能体中心卡片流投影（Phase B1）的行为钉子。
 *
 * 覆盖决策稿 §3 的合并规则（sameAgent 归一并卡、跨宿主不并、组员不出卡）、§4 的
 * Urgent-First（卡序与卡内状态行序）、审批到群的两次归因（席位绑定 wsId 才写群名，
 * 归因不了只报全局计数）与空态。
 */
class AgentHubProjectionTest {

    private val self = "self-node-0001"
    private val now = 1_000_000_000L

    private fun hostPeer(nodeId: String, online: Boolean = true) = PeerUi(
        nodeId = nodeId,
        nick = nodeId,
        platform = "",
        caps = listOf(MsgTypes.CAP_AGENT),
        online = online,
    )

    private fun seat(
        agentId: String,
        hostNodeId: String,
        name: String,
        defaultWsId: String? = null,
        teamMembers: List<TeamMemberInfo> = emptyList(),
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = hostNodeId,
        name = name,
        entityType = if (teamMembers.isEmpty()) EntityTypes.SINGLE else EntityTypes.TEAM,
        teamMembers = teamMembers,
        defaultWsId = defaultWsId,
    )

    private fun session(
        peerId: String,
        sessionId: String,
        agentId: String? = null,
        agentKind: String? = null,
        state: String = "idle",
        wsId: String? = null,
        wsTitle: String? = null,
        updatedAt: Long = 0L,
        hostOrigin: String? = null,
    ) = AgentSessionUi(
        peerId = peerId,
        sessionId = sessionId,
        agentId = agentId,
        agentKind = agentKind,
        state = state,
        wsId = wsId,
        wsTitle = wsTitle,
        updatedAt = updatedAt,
        hostOrigin = hostOrigin,
    )

    private fun declared(
        peerId: String,
        agentKey: String?,
        wsId: String,
        wsTitle: String? = null,
        at: Long = 0L,
    ) = WorkspaceUi(
        peerId = peerId,
        agentKey = agentKey,
        wsId = wsId,
        wsTitle = wsTitle,
        wsPath = null,
        agentKind = null,
        online = null,
        sessionCount = null,
        at = at,
    )

    private fun approval(
        peerId: String,
        sessionId: String,
        approvalId: String = "appr-1",
    ) = AgentApprovalUi(
        approvalId = approvalId,
        sessionId = sessionId,
        peerId = peerId,
        expiresAt = now + 60_000L,
    )

    /** 一件产物元数据（只有元数据，没有二进制 —— 与 `AgentArtifactUi` 同取舍）。 */
    private fun artifact(
        peerId: String,
        sessionId: String,
        artifactId: String,
        name: String,
        at: Long,
    ) = AgentArtifactUi(
        artifactId = artifactId,
        sessionId = sessionId,
        peerId = peerId,
        name = name,
        at = at,
    )

    /** 一个绑定席位的工作区群：席位由 [seats] 给出（构造点在测试里是安全的，守卫只扫生产源码）。 */
    private fun group(groupId: String, name: String, seats: List<VirtualAgentMember>) =
        GroupMeta.create(groupId, name, members = emptyList(), selfId = self, updatedTs = 1L)
            .copy(virtualAgents = seats)

    private fun state(
        peers: List<PeerUi> = emptyList(),
        groups: List<GroupMeta> = emptyList(),
        sessions: List<AgentSessionUi> = emptyList(),
        workspaces: List<WorkspaceUi> = emptyList(),
        approvals: List<AgentApprovalUi> = emptyList(),
        artifacts: List<AgentArtifactUi> = emptyList(),
    ): RelayUiState {
        val settings = RelaySettings.firstRun().copy(nodeId = self, groups = groups)
        return RelayUiState(
            settings = settings,
            peers = peers,
            agentSessions = sessions.associate { AgentSessionKey(it.peerId, it.sessionId) to it },
            agentWorkspaces = workspaces.associate { WorkspaceKey(it.peerId, it.agentKey, it.wsId) to it },
            agentApprovals = approvals.associateBy { it.approvalId },
            agentArtifacts = artifacts.associateBy { AgentArtifactKey(it.peerId, it.artifactId) },
        )
    }

    private fun hubAgentsOf(state: RelayUiState): List<AgentHubUi> =
        hubAgents(state.toAgentSlice(), state.toNodeSlice(), state.toSettingsSlice(), now)

    private fun detailOf(state: RelayUiState, hostNodeId: String, agentId: String): AgentHubDetailUi? =
        agentHubDetail(state.toAgentSlice(), state.toNodeSlice(), state.toSettingsSlice(), hostNodeId, agentId, now)

    // ---- 空态 ----

    @Test
    fun emptySlicesProduceNoCards() {
        assertTrue(hubAgentsOf(state()).isEmpty(), "什么都没上报时一张卡都不该有")
    }

    // ---- 合并规则（决策稿 §3） ----

    @Test
    fun sameAgentPrecisionMergesIntoOneCardPerHost() {
        // 同一宿主：会话自报 `dsh-029bbbf3`、宣告写 `DSH` —— sameAgent 判同一实体，一张卡。
        val s = state(
            peers = listOf(hostPeer("host-1")),
            sessions = listOf(
                session("host-1", "s-1", agentId = "dsh-029bbbf3", agentKind = "DSH", wsId = "ws-a"),
            ),
            workspaces = listOf(declared("host-1", "DSH", "ws-b", wsTitle = "另一个目录")),
        )
        val cards = hubAgentsOf(s)
        assertEquals(1, cards.size, "sameAgent 归一后同宿主只有一个实体")
        assertEquals("dsh-029bbbf3", cards.single().agentKey, "聚类代表优先取自 agentId 来源")
        assertEquals(2, cards.single().workspaceCount, "两个目录都挂在这一张卡上")
    }

    @Test
    fun sameKindOnDifferentHostsNeverMerges() {
        val s = state(
            peers = listOf(hostPeer("host-1"), hostPeer("host-2")),
            sessions = listOf(
                session("host-1", "s-1", agentKind = "DSH", wsId = "ws-a"),
                session("host-2", "s-2", agentKind = "DSH", wsId = "ws-a"),
            ),
        )
        val cards = hubAgentsOf(s)
        assertEquals(2, cards.size, "执行点是设备绑定的：宿主不同就不并卡")
        assertEquals(setOf("host-1", "host-2"), cards.map { it.hostNodeId }.toSet())
    }

    @Test
    fun teamMembersNeverGetTheirOwnCard() {
        val team = seat(
            agentId = "DSH",
            hostNodeId = "host-1",
            name = "领队",
            teamMembers = listOf(
                TeamMemberInfo(agentId = "member-a", name = "组员A", role = "审查"),
                TeamMemberInfo(agentId = "member-b", name = "组员B", role = "执行"),
            ),
        )
        val s = state(
            peers = listOf(hostPeer("host-1")),
            groups = listOf(group("g-1", "协同群", listOf(team))),
        )
        val cards = hubAgentsOf(s)
        assertEquals(1, cards.size, "对外单一窗口：只有领队一张卡")
        val card = cards.single()
        assertTrue(card.isTeam, "席位是团队形态")
        assertEquals(3, card.teamSize, "团队人数 = 领队 1 + 组员 2")
        assertFalse(
            cards.any { it.agentKey == "member-a" || it.agentKey == "member-b" },
            "组员不生成独立卡",
        )
    }

    @Test
    fun seatDisplayNameWinsOverTheMachineIdentityKey() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            groups = listOf(group("g-1", "协同群", listOf(seat("DSH", "host-1", name = "小榫")))),
            sessions = listOf(session("host-1", "s-1", agentId = "dsh-029bbbf3")),
        )
        val card = hubAgentsOf(s).single()
        assertEquals("小榫", card.displayName, "显示名取席位名（用户在群里 @ 的那个）")
        assertEquals("dsh-029bbbf3", card.agentKey, "身份键仍是归一后的机器键")
    }

    @Test
    fun seatServingTwoGroupsCountsTwo() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            groups = listOf(
                group("g-1", "群一", listOf(seat("DSH", "host-1", name = "小榫"))),
                group("g-2", "群二", listOf(seat("DSH", "host-1", name = "小榫"))),
            ),
        )
        assertEquals(2, hubAgentsOf(s).single().groupCount, "服务群数 = 席位落在几个群（去重）")
    }

    // ---- Urgent-First（决策稿 §4） ----

    @Test
    fun cardsOrderByUrgentFirst() {
        val busy = session("host-busy", "s-busy", agentKind = "DSH", state = "busy", updatedAt = now - 1_000)
        val idle = session("host-idle", "s-idle", agentKind = "DSH", state = "idle", updatedAt = now - 2_000)
        val pendingHost = session("host-pending", "s-pending", agentKind = "DSH", state = "idle", updatedAt = now - 3_000)
        val s = state(
            peers = listOf(hostPeer("host-busy"), hostPeer("host-idle"), hostPeer("host-pending")),
            sessions = listOf(busy, idle, pendingHost),
            approvals = listOf(approval("host-pending", "s-pending")),
        )
        val order = hubAgentsOf(s).map { it.hostNodeId }
        assertEquals(
            listOf("host-pending", "host-busy", "host-idle"),
            order,
            "卡序 Urgent-First：待审批 > 执行中 > 最近",
        )
    }

    @Test
    fun rowsInsideACardFollowUrgentFirst() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            sessions = listOf(
                session("host-1", "s-busy", agentKind = "DSH", state = "busy", wsId = "ws-1", updatedAt = now - 1_000),
            ),
            approvals = listOf(approval("host-1", "s-busy")),
        )
        val rows = hubAgentsOf(s).single().rows
        assertEquals(
            listOf(AgentHubRowKind.PENDING, AgentHubRowKind.BUSY, AgentHubRowKind.RECENT),
            rows.map { it.kind },
            "卡内状态行：审批 > 执行中 > 最近",
        )
        assertEquals(1, rows.first().pendingCount)
        assertEquals("busy", rows.first { it.kind == AgentHubRowKind.BUSY }.state)
    }

    @Test
    fun staleBusyDoesNotLightTheRunningRow() {
        // 僵死 busy（宿主异常退出漏推收尾帧）：与看板同一条 staleness 判据折回 idle。
        val stale = session(
            "host-1", "s-stale",
            agentKind = "DSH", state = "busy",
            updatedAt = now - 40 * 60 * 1000L,
        )
        val s = state(peers = listOf(hostPeer("host-1")), sessions = listOf(stale))
        val rows = hubAgentsOf(s).single().rows
        assertFalse(rows.any { it.kind == AgentHubRowKind.BUSY }, "僵死 busy 不点亮「执行中」")
        assertEquals(
            "idle",
            rows.single { it.kind == AgentHubRowKind.RECENT }.state,
            "最近行的态按看板同口径折回 idle",
        )
    }

    // ---- 审批归因（决策稿 §4：能归因写群名，归因不了只报全局计数） ----

    @Test
    fun pendingApprovalAttributesToTheGroupWhoseSeatBindsTheWorkspace() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            groups = listOf(group("g-1", "协同群", listOf(seat("DSH", "host-1", name = "小榫", defaultWsId = "ws-1")))),
            sessions = listOf(session("host-1", "s-1", agentId = "dsh-029bbbf3", wsId = "ws-1")),
            approvals = listOf(approval("host-1", "s-1")),
        )
        val pendingRow = hubAgentsOf(s).single().rows.first()
        assertEquals(AgentHubRowKind.PENDING, pendingRow.kind)
        assertEquals(1, pendingRow.pendingCount)
        assertEquals(listOf("协同群"), pendingRow.pendingGroupNames, "席位绑定了这个工作区 ⇒ 写群名")
    }

    @Test
    fun pendingWithoutWorkspaceBindingShowsGlobalCountOnly() {
        // 直通宿主私聊（会话没有 wsId）：计数要报，群名不编造（决策稿 §4）。
        val s = state(
            peers = listOf(hostPeer("host-1")),
            groups = listOf(group("g-1", "协同群", listOf(seat("DSH", "host-1", name = "小榫")))),
            sessions = listOf(session("host-1", "s-1", agentId = "dsh-029bbbf3")),
            approvals = listOf(approval("host-1", "s-1")),
        )
        val pendingRow = hubAgentsOf(s).single().rows.first()
        assertEquals(1, pendingRow.pendingCount)
        assertTrue(pendingRow.pendingGroupNames.isEmpty(), "归因不了 ⇒ 只显示全局计数，不写群名")
    }

    @Test
    fun pendingDoesNotBorrowAnotherHostsSeatBinding() {
        // 群把 ws-1 绑给了**别的宿主**的席位：host-1 的审批不能借这个名字（不编造归属）。
        val s = state(
            peers = listOf(hostPeer("host-1"), hostPeer("host-2")),
            groups = listOf(group("g-1", "协同群", listOf(seat("DSH", "host-2", name = "别人家的", defaultWsId = "ws-1")))),
            sessions = listOf(session("host-1", "s-1", agentId = "dsh-029bbbf3", wsId = "ws-1")),
            approvals = listOf(approval("host-1", "s-1")),
        )
        val card = hubAgentsOf(s).single { it.hostNodeId == "host-1" }
        assertEquals(1, card.pendingCount)
        assertTrue(
            card.rows.first().pendingGroupNames.isEmpty(),
            "席位宿主不同 ⇒ 不写群名",
        )
        assertEquals(0, card.groupCount, "别的宿主的席位不算本实体的服务群")
    }

    @Test
    fun approvalWhoseSessionIsUnknownLightsNoCard() {
        // join 不上会话的审批：宁可不上卡（看板组头仍会报它），也不猜它是哪个 agent 的。
        val s = state(
            peers = listOf(hostPeer("host-1")),
            sessions = listOf(session("host-1", "s-1", agentKind = "DSH")),
            approvals = listOf(approval("host-1", "s-unknown", approvalId = "appr-x")),
        )
        assertTrue(
            hubAgentsOf(s).all { it.pendingCount == 0 },
            "join 不上的审批不落到任何卡上",
        )
    }

    // ---- 下钻目标 ----

    @Test
    fun primaryWorkspaceIsTheMostRecentlyActiveOne() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            sessions = listOf(
                session("host-1", "s-1", agentId = "dsh", wsId = "ws-old", wsTitle = "旧目录", updatedAt = now - 60_000),
            ),
            workspaces = listOf(declared("host-1", "dsh", "ws-new", wsTitle = "新目录", at = now - 1_000)),
        )
        val card = hubAgentsOf(s).single()
        assertEquals("ws-new", card.primaryWsId, "下钻目标 = 最近活动的那一个目录")
        assertEquals("新目录", card.primaryWsTitle)
    }

    @Test
    fun unnamedWorkspaceKeepsNullTitle() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            workspaces = listOf(declared("host-1", "dsh", "ws-x")),
        )
        val card = hubAgentsOf(s).single()
        assertEquals("ws-x", card.primaryWsId)
        assertNull(card.primaryWsTitle, "未命名就是未命名，不拿 wsId 顶替")
    }

    // ---- 详情投影（Phase B4，决策稿 §3a） ----

    @Test
    fun detailReturnsNullForUnknownHostOrEntity() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            sessions = listOf(session("host-1", "s-1", agentKind = "DSH")),
        )
        assertNull(detailOf(s, "ghost-host", "DSH"), "宿主不在编制里 ⇒ 实体不存在")
        assertNull(detailOf(s, "host-1", "opencode"), "宿主在、但没有匹配身份的聚类 ⇒ 实体不存在")
    }

    @Test
    fun detailMatchesBlankAgentIdToTheUnreportedEntity() {
        // 卡片的身份键可为 null（「未上报类型」），而导航路由只能传字符串：
        // 空串要能落回 null 身份的那一档（同一个实体，不是第二个）。
        val s = state(
            peers = listOf(hostPeer("host-1")),
            sessions = listOf(session("host-1", "s-1", agentId = null, agentKind = null)),
        )
        val detail = detailOf(s, "host-1", "")
        assertNotNull(detail, "空串按「未上报类型」那一档匹配")
        assertNull(detail.agentKey, "没有身份上报就是 null，不编键")
        assertEquals(1, detail.sessions.size)
    }

    @Test
    fun detailCarriesIdentityTeamRosterAndHostName() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            groups = listOf(
                group(
                    "g-1", "协同群",
                    listOf(
                        seat(
                            "DSH", "host-1", name = "领队",
                            teamMembers = listOf(
                                TeamMemberInfo(agentId = "member-a", name = "组员A", role = "审查"),
                                TeamMemberInfo(agentId = "member-b", name = "组员B", role = "执行"),
                            ),
                        ),
                    ),
                ),
                group(
                    "g-2", "另一个群",
                    listOf(
                        seat(
                            "DSH", "host-1", name = "领队",
                            teamMembers = listOf(
                                TeamMemberInfo(agentId = "member-a", name = "组员A", role = "审查"),
                                TeamMemberInfo(agentId = "member-b", name = "组员B", role = "执行"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val detail = detailOf(s, "host-1", "DSH")!!
        assertTrue(detail.isTeam)
        assertEquals(3, detail.teamSize, "团队人数 = 领队 1 + 组员 2（与卡片同一来源）")
        assertEquals(
            listOf("member-a", "member-b"),
            detail.teamMembers.map { it.agentId },
            "同一支队伍在两个群里重复声明：清单按 agentId 去重，不翻倍",
        )
        assertEquals("领队", detail.displayName)
    }

    @Test
    fun detailGroupRowsSeparateResolvedFromUnexposedExecutionPoints() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            groups = listOf(
                group("g-1", "已绑定群", listOf(seat("DSH", "host-1", name = "小榫", defaultWsId = "ws-1"))),
                group("g-2", "未暴露群", listOf(seat("DSH", "host-1", name = "小榫"))),
            ),
            workspaces = listOf(declared("host-1", "DSH", "ws-1", wsTitle = "主目录")),
        )
        val detail = detailOf(s, "host-1", "DSH")!!
        assertEquals(2, detail.servingGroups.size, "席位在两个群里 ⇒ 两行")
        val bound = detail.servingGroups.single { it.groupId == "g-1" }
        assertEquals("ws-1", bound.defaultWsId, "席位声明了执行点 ⇒ 已暴露")
        assertEquals("主目录", bound.resolvedWsTitle, "目录名与 workspaceOfGroup 同源解析")
        val unexposed = detail.servingGroups.single { it.groupId == "g-2" }
        assertNull(unexposed.defaultWsId, "席位没声明执行点 ⇒ 未暴露")
        assertNull(unexposed.resolvedWsTitle)
    }

    @Test
    fun detailAttributesPendingApprovalsPerGroupAndPassesRowsThrough() {
        val approval = approval("host-1", "s-1", approvalId = "appr-1")
        val s = state(
            peers = listOf(hostPeer("host-1")),
            groups = listOf(
                group("g-1", "已绑定群", listOf(seat("DSH", "host-1", name = "小榫", defaultWsId = "ws-1"))),
                group("g-2", "未暴露群", listOf(seat("DSH", "host-1", name = "小榫"))),
            ),
            sessions = listOf(session("host-1", "s-1", agentId = "dsh-029bbbf3", wsId = "ws-1")),
            workspaces = listOf(declared("host-1", "DSH", "ws-1", wsTitle = "主目录")),
            approvals = listOf(approval),
        )
        val detail = detailOf(s, "host-1", "DSH")!!
        assertEquals(1, detail.servingGroups.single { it.groupId == "g-1" }.pendingCount, "待批归因到绑定群")
        assertEquals(0, detail.servingGroups.single { it.groupId == "g-2" }.pendingCount)
        assertEquals(listOf(approval), detail.approvals, "审批行原样透传（不滤、不改写）")
    }

    @Test
    fun detailOnlyContainsThisEntity() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            groups = listOf(
                group("g-1", "群一", listOf(seat("DSH", "host-1", name = "小榫"))),
                group("g-2", "群二", listOf(seat("opencode", "host-1", name = "码手"))),
            ),
            sessions = listOf(
                session("host-1", "s-dsh", agentId = "dsh-029bbbf3", wsId = "ws-a", wsTitle = "A 目录"),
                session("host-1", "s-oc", agentId = "opencode-1", wsId = "ws-b", wsTitle = "B 目录"),
            ),
        )
        val dsh = detailOf(s, "host-1", "dsh")!!
        assertEquals(listOf("g-1"), dsh.servingGroups.map { it.groupId }, "只含本实体的席位群")
        assertEquals(listOf("ws-a"), dsh.workspaces.map { it.wsId }, "只含本实体的目录")
        assertEquals(listOf("s-dsh"), dsh.sessions.map { it.sessionId }, "只含本实体的会话")
        val oc = detailOf(s, "host-1", "opencode")!!
        assertEquals(listOf("g-2"), oc.servingGroups.map { it.groupId })
        assertEquals(listOf("ws-b"), oc.workspaces.map { it.wsId })
    }

    @Test
    fun detailWorkspacesCarryVisibleSessionCountsAndHideSubagentSessions() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            sessions = listOf(
                session("host-1", "s-1", agentId = "dsh", wsId = "ws-a", wsTitle = "A 目录", updatedAt = now - 1_000),
                session("host-1", "s-2", agentId = "dsh", wsId = "ws-a", wsTitle = "A 目录", updatedAt = now - 2_000),
                session("host-1", "s-sub", agentId = "dsh", wsId = "ws-a", hostOrigin = "subagent"),
            ),
            workspaces = listOf(declared("host-1", "dsh", "ws-a", wsTitle = "A 目录")),
        )
        val detail = detailOf(s, "host-1", "dsh")!!
        val row = detail.workspaces.single()
        assertEquals(2, row.sessionCount, "会话数按本端可见口径数（子代理不算）")
        assertEquals("A 目录", row.wsTitle)
        assertEquals(
            listOf("s-1", "s-2"),
            detail.sessions.map { it.sessionId },
            "会话列表走 visibleInDsh 口径，且最新活动在前",
        )
    }

    @Test
    fun detailSessionListIsCappedAtTheTopFew() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            sessions = (0 until AGENT_HUB_DETAIL_SESSION_LIMIT + 3).map { n ->
                session("host-1", "s-%02d".format(n), agentId = "dsh", updatedAt = now - n * 1_000L)
            },
        )
        val detail = detailOf(s, "host-1", "dsh")!!
        assertEquals(
            (0 until AGENT_HUB_DETAIL_SESSION_LIMIT).map { n -> "s-%02d".format(n) },
            detail.sessions.map { it.sessionId },
            "只留最近活动的 top 若干（上限 = AGENT_HUB_DETAIL_SESSION_LIMIT）",
        )
    }

    // ---- 最近交付标签（UX 规范 §2.5②）----
    //
    // 产物帧自带的是 `peerId` + `sessionId`，**没有 agent 身份**。所以这几条钉的是
    // 「归因只走会话→聚类那一张表」，而不是产物自己再解析一遍身份。

    @Test
    fun latestArtifactLandsOnTheOwningCardAndNewestWins() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            sessions = listOf(
                session("host-1", "s-1", agentId = "dsh", wsId = "ws-a"),
                session("host-1", "s-2", agentId = "dsh", wsId = "ws-a"),
            ),
            artifacts = listOf(
                artifact("host-1", "s-1", "a-old", "old.md", at = now - 5_000),
                artifact("host-1", "s-2", "a-new", "design.html", at = now - 1_000),
            ),
        )
        val card = hubAgentsOf(s).single()
        val tag = assertNotNull(card.latestArtifact, "名下有产物时卡片必须挂上「最近交付」")
        assertEquals("design.html", tag.name, "取**最近收到**的那件，不是先到的")
        assertEquals("s-2", tag.sessionId, "标签指向产物所属的那条会话")
        assertEquals(now - 1_000, tag.at, "时刻用本端收到的时刻，不是文件名或会话顺序")
    }

    @Test
    fun artifactsFromOtherHostsOrInvisibleSessionsNeverAttach() {
        val s = state(
            peers = listOf(hostPeer("host-1"), hostPeer("host-2")),
            sessions = listOf(
                session("host-1", "s-1", agentId = "dsh"),
                // 子代理静默会话：visibleInDsh 之外 —— 它的产物不该冒到卡片上。
                session("host-1", "s-sub", agentId = "dsh", hostOrigin = "subagent"),
                session("host-2", "s-9", agentId = "dsh"),
            ),
            artifacts = listOf(
                artifact("host-2", "s-9", "a-2", "other-host.html", at = now - 1_000),
                artifact("host-1", "s-sub", "a-sub", "subagent.md", at = now - 500),
            ),
        )
        val cards = hubAgentsOf(s)
        assertNull(
            cards.single { it.hostNodeId == "host-1" }.latestArtifact,
            "别的宿主的产物、子代理会话里的产物都不该挂到这张卡上",
        )
        assertEquals(
            "other-host.html",
            cards.single { it.hostNodeId == "host-2" }.latestArtifact?.name,
            "产物只挂在自己的宿主上",
        )
    }

    @Test
    fun noArtifactsMeansNoTag() {
        val s = state(
            peers = listOf(hostPeer("host-1")),
            sessions = listOf(session("host-1", "s-1", agentId = "dsh")),
        )
        assertNull(hubAgentsOf(s).single().latestArtifact, "一件产物都没有时不许编一个标签出来")
    }
}
