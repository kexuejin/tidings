// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.model.AgentApprovalUi
import com.kapp.tenon.domain.model.AgentSessionKey
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.WorkspaceKey
import com.kapp.tenon.domain.model.WorkspaceUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 团队视图投影的行为钉子（不变量 5 的「团队」维）。
 *
 * 它回答的是「这个团队有谁、各自在本团队里叫什么、本群还有几件待批」。
 *
 * ## 反向探针（每条判据都必须能区分对错）
 *
 * - 团队键去掉 `leaderAgentId`（只按 `groupId` 归并）⇒
 *   `twoTeamsInOneGroupStayTwoCards` 会合成一张、成员表混在一起；
 * - `pendingCountByGroup` 改成数「成员名下待批合计」⇒
 *   `pendingIsCountedPerGroupNotPerMember` 变红（另一群的待批被算进来）；
 * - 领队不进成员表 ⇒ `leaderComesFirstInTheMemberList` 变红；
 * - 单兵席位放行 ⇒ `soloSeatIsNotATeam` 变红；
 * - 孤儿判据反过来（`isNotEmpty()` 当空）：`aProfileWithNoSeatAnywhereIsAnOrphan` 与
 *   `aProfileSeatedInAnyGroupIsNotAnOrphan` 同时红 —— 一边把入驻的画成孤儿、一边把孤儿藏起来。
 */
class AgentTeamProjectionTest {

    private val self = "self-node-0001"
    private val other = "host-other-0002"
    private val now = 1_700_000_000_000L

    private fun seat(
        agentId: String,
        name: String,
        hostNodeId: String = self,
        defaultWsId: String? = null,
        teamMembers: List<TeamMemberInfo> = emptyList(),
        teamId: String? = null,
        teamName: String? = null,
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = hostNodeId,
        name = name,
        entityType = if (teamMembers.isEmpty()) EntityTypes.SINGLE else EntityTypes.TEAM,
        teamMembers = teamMembers,
        defaultWsId = defaultWsId,
        teamId = teamId,
        teamName = teamName,
    )

    private fun group(groupId: String, name: String, seats: List<VirtualAgentMember>) =
        GroupMeta.create(groupId, name, members = emptyList(), selfId = self, updatedTs = 1L)
            .copy(virtualAgents = seats)

    private fun session(peerId: String, sessionId: String, wsId: String?, at: Long) = AgentSessionUi(
        peerId = peerId,
        sessionId = sessionId,
        agentId = "dsh",
        wsId = wsId,
        updatedAt = at,
    )

    private fun approval(id: String, peerId: String, sessionId: String) =
        AgentApprovalUi(approvalId = id, sessionId = sessionId, peerId = peerId)

    private fun state(
        groups: List<GroupMeta>,
        sessions: List<AgentSessionUi> = emptyList(),
        approvals: List<AgentApprovalUi> = emptyList(),
        workspaces: List<WorkspaceUi> = emptyList(),
        teamProfiles: List<TeamProfile> = emptyList(),
    ): RelayUiState {
        val settings = RelaySettings.firstRun().copy(
            nodeId = self,
            groups = groups,
            teamProfiles = teamProfiles,
        )
        return RelayUiState(
            settings = settings,
            peers = emptyList(),
            agentSessions = sessions.associateBy { AgentSessionKey(it.peerId, it.sessionId) },
            agentApprovals = approvals.associateBy { it.approvalId },
            agentWorkspaces = workspaces.associate { WorkspaceKey(it.peerId, it.agentKey, it.wsId) to it },
        )
    }

    private fun profile(
        teamId: String,
        name: String,
        members: List<TeamMemberInfo> = emptyList(),
    ) = TeamProfile(
        teamId = teamId,
        name = name,
        leaderAgentId = "dsh-a",
        members = members,
        rev = 1,
    )

    private fun orphansOf(state: RelayUiState) = orphanTeams(state.toSettingsSlice())

    private fun teamsOf(state: RelayUiState) =
        agentTeams(state.toAgentSlice(), state.toNodeSlice(), state.toSettingsSlice(), now)

    @Test
    fun leaderComesFirstInTheMemberList() {
        val teams = teamsOf(
            state(
                groups = listOf(
                    group(
                        "g1", "项目协同群",
                        listOf(
                            seat(
                                "dsh-a", "墨查",
                                teamMembers = listOf(
                                    TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "架构领队"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, teams.size)
        val team = teams.single()
        assertEquals(2, team.teamSize)
        assertEquals("墨查", team.leaderName)
        assertTrue(team.members.first().isLeader, "领队必须在成员表第一行")
        assertEquals("小榫", team.members[1].name)
        assertEquals("架构领队", team.members[1].role)
        assertFalse(team.members[1].isLeader)
    }

    @Test
    fun soloSeatIsNotATeam() {
        val teams = teamsOf(
            state(
                groups = listOf(
                    group("g1", "项目协同群", listOf(seat("dsh-a", "独狼"))),
                ),
            ),
        )
        assertEquals(emptyList(), teams, "单兵席位没有组员，不是团队 —— 团队视图不该给它一张卡")
    }

    @Test
    fun twoTeamsInOneGroupStayTwoCards() {
        val teams = teamsOf(
            state(
                groups = listOf(
                    group(
                        "g1", "大群",
                        listOf(
                            seat("dsh-a", "墨查", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "榫一", role = "开发"))),
                            seat("dsh-c", "旁白", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-d", name = "榫二", role = "审查"))),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2, teams.size, "一个群两支队伍 = 两张卡；按 groupId 归并会把它们挤成一张")
        assertEquals(setOf("dsh-a", "dsh-c"), teams.map { it.leaderAgentId }.toSet())
    }

    @Test
    fun pendingIsCountedPerGroupNotPerMember() {
        // 同一个领队在两个群都有席位；A 群有一件待批，B 群没有。
        val a = seat("dsh-a", "墨查", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "开发")))
        val b = seat("dsh-a", "墨查", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "开发")))
        val st = state(
            groups = listOf(group("g1", "A 群", listOf(a)), group("g2", "B 群", listOf(b))),
            sessions = listOf(session(self, "s1", "ws-1", now - 1000)),
            approvals = listOf(approval("ap1", self, "s1")),
            workspaces = listOf(
                WorkspaceUi(
                    peerId = self, agentKey = "dsh", wsId = "ws-1", wsTitle = "仓库",
                    wsPath = null, agentKind = null, online = null, sessionCount = null, at = 0L,
                ),
            ),
        )
        // ws-1 绑给 A 群（席位声明的 defaultWsId）。
        val bound = st.copy(
            settings = st.settings.copy(
                groups = listOf(
                    a.copy(defaultWsId = "ws-1").let { group("g1", "A 群", listOf(it)) },
                    group("g2", "B 群", listOf(b)),
                ),
            ),
        )
        val teams = teamsOf(bound).associateBy { it.groupName }
        assertEquals(1, teams.getValue("A 群").pendingCount, "本群那一件要数进来")
        assertEquals(0, teams.getValue("B 群").pendingCount, "另一个群的待批不许算到本群头上")
    }

    @Test
    fun offlineLeaderHostIsReportedAsOffline() {
        val teams = teamsOf(
            state(
                groups = listOf(
                    group(
                        "g1", "远端群",
                        listOf(
                            seat(
                                "dsh-remote", "远程领队", hostNodeId = other,
                                teamMembers = listOf(TeamMemberInfo(agentId = "dsh-x", name = "组员", role = "开发")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val team = teams.single()
        assertFalse(team.online, "别的宿主不在节点表里 = 离线，不许当成「空闲」或「在线」")
    }

    @Test
    fun teamsWithMorePendingComeFirst() {
        val quiet = seat("dsh-a", "安静队", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "甲", role = "开发")))
        val noisy = seat("dsh-c", "忙碌队", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-d", name = "乙", role = "开发")))
        val st = state(
            groups = listOf(
                group("g1", "安静群", listOf(quiet.copy(defaultWsId = "ws-q"))),
                group("g2", "忙碌群", listOf(noisy.copy(defaultWsId = "ws-n"))),
            ),
            sessions = listOf(
                session(self, "s-q", "ws-q", now - 1000),
                session(self, "s-n1", "ws-n", now - 1000),
                session(self, "s-n2", "ws-n", now - 1000),
            ),
            approvals = listOf(approval("ap1", self, "s-n1"), approval("ap2", self, "s-n2")),
        )
        val teams = teamsOf(st)
        assertEquals("忙碌群", teams.first().groupName, "待批多的团队排前面（Urgent-First 换归因单位，不换口径）")
    }

    @Test
    fun unattributedApprovalIsNotChargedToAnyTeam() {
        // 会话没有 wsId（直通宿主私聊）：审批不属于任何团队。把它算给「第一个群」
        // 就是编造归属 —— 与事件路由 no-conv-bound 同一条纪律。
        val st = state(
            groups = listOf(
                group(
                    "g1", "唯一的群",
                    listOf(seat("dsh-a", "墨查", teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "开发")))),
                ),
            ),
            sessions = listOf(session(self, "s-chat", null, now - 1000)),
            approvals = listOf(approval("ap1", self, "s-chat")),
        )
        assertEquals(0, teamsOf(st).single().pendingCount, "归因不上的审批不许落到任何团队卡上")
    }

    @Test
    fun lastActivityTakesTheNewestAmongMembers() {
        val st = state(
            groups = listOf(
                group(
                    "g1", "项目协同群",
                    listOf(
                        seat(
                            "dsh-a", "墨查",
                            teamMembers = listOf(TeamMemberInfo(agentId = "dsh-b", name = "小榫", role = "开发")),
                        ),
                    ),
                ),
            ),
            sessions = listOf(
                session(self, "s-old", null, now - 90_000),
                session(self, "s-new", null, now - 5_000).copy(agentId = "dsh-b"),
            ),
        )
        assertEquals(now - 5_000, teamsOf(st).single().lastActivityAt, "成员里最新的那一份才是团队的活动时刻")
    }

    // ===== 团队详情（点团队行的第一落点）=====

    private fun roster() = listOf(TeamMemberInfo(agentId = "dsh-dev", name = "小榫", role = "开发"))

    private fun workspace(wsId: String, title: String) = WorkspaceUi(
        peerId = self, agentKey = "dsh", wsId = wsId, wsTitle = title,
        wsPath = null, agentKind = null, online = null, sessionCount = null, at = 0L,
    )

    @Test
    fun teamDetailMergesEveryGroupTheSameTeamSitsIn() {
        // 同一支队伍（同一个 teamId）进了两个群：详情要把两个群一次说清，
        // 而不是只画用户点进来的那一个。
        val a = seat("dsh-lead", "墨查", teamId = "tm-1", teamName = "支付改造组",
            teamMembers = roster(), defaultWsId = "ws-a")
        val b = seat("dsh-lead", "墨查", teamId = "tm-1", teamName = "支付改造组",
            teamMembers = roster(), defaultWsId = "ws-b")
        val st = state(
            groups = listOf(group("g1", "A 群", listOf(a)), group("g2", "B 群", listOf(b))),
            workspaces = listOf(workspace("ws-a", "仓库 A"), workspace("ws-b", "仓库 B")),
        )
        val teams = teamsOf(st)
        assertEquals(2, teams.size, "一个团队两个群 = 名录两行（每群一行，待批与目录按群算）")

        val detail = agentTeamDetail(teams, groupId = "g2", leaderAgentId = "dsh-lead")
            ?: error("团队键定位得到，详情不该是 null")
        assertEquals("支付改造组", detail.displayName, "标题用团队档案名，不落回群名")
        assertEquals("g2", detail.groupId)
        assertEquals("B 群", detail.groupName)
        assertEquals(listOf("g2", "g1"), detail.groups.map { it.groupId }, "定位群排第一，其余按群名钉住")
        assertEquals("仓库 B", detail.groups[0].wsTitle, "每行的目录是本群席位声明的那个")
        assertEquals("ws-b", detail.groups[0].wsId, "目录键也要给 —— 详情页靠它点进工作区")
        assertEquals(listOf("dsh-lead", "dsh-dev"), detail.members.map { it.agentId })
    }

    @Test
    fun teamDetailSumsPendingAcrossItsGroups() {
        // 待批是按群归因的；团队粒度要的是「这支队总共欠几件」，不是某一群的数。
        val a = seat("dsh-lead", "墨查", teamId = "tm-1", teamMembers = roster(), defaultWsId = "ws-a")
        val b = seat("dsh-lead", "墨查", teamId = "tm-1", teamMembers = roster(), defaultWsId = "ws-b")
        val st = state(
            groups = listOf(group("g1", "A 群", listOf(a)), group("g2", "B 群", listOf(b))),
            sessions = listOf(
                session(self, "s-a", "ws-a", now - 1000),
                session(self, "s-b", "ws-b", now - 1000),
            ),
            approvals = listOf(approval("ap1", self, "s-a"), approval("ap2", self, "s-b")),
        )
        val detail = agentTeamDetail(teamsOf(st), groupId = "g1", leaderAgentId = "dsh-lead")!!
        assertEquals(2, detail.pendingCount, "两群各一件 = 团队两件")
        assertEquals(mapOf("g1" to 1, "g2" to 1), detail.groups.associate { it.groupId to it.pendingCount })
    }

    @Test
    fun legacyTeamWithoutProfileKeepsOnlyItsOwnGroup() {
        // 没有 teamId 的旧团队：它本来就没有"跨群的第二个家"，不许按领队相同把两个群并起来
        // （那会把群 A 的队伍和群 B 的队伍说成同一支）。
        val a = seat("dsh-lead", "墨查", teamMembers = roster())
        val b = seat("dsh-lead", "墨查", teamMembers = roster())
        val st = state(groups = listOf(group("g1", "A 群", listOf(a)), group("g2", "B 群", listOf(b))))
        val detail = agentTeamDetail(teamsOf(st), groupId = "g1", leaderAgentId = "dsh-lead")!!
        assertEquals(listOf("g1"), detail.groups.map { it.groupId })
        assertEquals("A 群", detail.displayName, "没档案就没有独立名，标题回落群名")
    }

    @Test
    fun sameTeamIdOnAnotherHostIsNotMergedIn() {
        // teamId 是领队宿主本机分配的键，两端各自建过一支时可以撞字符串。
        // 把别的宿主那支队并进这一份，就会把两支人合成一张名册。
        val mine = seat("dsh-lead", "墨查", teamId = "tm-1", teamMembers = roster())
        val theirs = seat("dsh-lead", "墨查", hostNodeId = other, teamId = "tm-1", teamMembers = roster())
        val st = state(groups = listOf(group("g1", "我的群", listOf(mine)), group("g2", "对方群", listOf(theirs))))
        val detail = agentTeamDetail(teamsOf(st), groupId = "g1", leaderAgentId = "dsh-lead")!!
        assertEquals(listOf("g1"), detail.groups.map { it.groupId }, "跨宿主同 teamId 不是同一支队")
    }

    @Test
    fun teamDetailIsNullWhenTheSeatIsGone() {
        val st = state(
            groups = listOf(
                group("g1", "A 群", listOf(seat("dsh-lead", "墨查", teamMembers = roster()))),
            ),
        )
        val teams = teamsOf(st)
        assertNull(
            agentTeamDetail(teams, groupId = "g1", leaderAgentId = "dsh-other"),
            "席位撤回后键定位不到 = null（UI 画空态），不许落回最接近的那支队",
        )
        assertNull(agentTeamDetail(emptyList(), groupId = "g1", leaderAgentId = "dsh-lead"))
    }
    // ===== 未入驻任何群的档案（孤儿）=====

    @Test
    fun aProfileWithNoSeatAnywhereIsAnOrphan() {
        // 席位被逐群「移除」之后，名录里没有它那一行，但档案还在 —— 群信息的
        // 「添加团队」照样列着它。这条投影就是那个落点：不画出来，用户永远删不掉它。
        val st = state(
            groups = listOf(
                group("g1", "A 群", listOf(seat("dsh-a", "墨查", teamId = "tm-1", teamName = "在群的"))),
            ),
            teamProfiles = listOf(
                profile("tm-1", "在群的"),
                profile("tm-2", "已撤组", members = listOf(TeamMemberInfo("c1", "小榫", "开发"))),
            ),
        )
        val orphans = orphansOf(st)
        assertEquals(listOf("tm-2"), orphans.map { it.teamId })
        assertEquals("已撤组", orphans.single().name)
        assertEquals(2, orphans.single().memberCount, "人数 = 领队 1 + 组员数")
    }

    @Test
    fun aProfileSeatedInAnyGroupIsNotAnOrphan() {
        // 一支队伍进了两个群、在其中一个被移除：它还在另一个群里干活，
        // 不能在「未入驻」里再出现一次（同一个团队被画两处、其中一处说它没入驻）。
        val st = state(
            groups = listOf(
                group("g1", "A 群", listOf(seat("dsh-a", "墨查", teamId = "tm-1", teamName = "支付组"))),
                group("g2", "B 群", listOf(seat("dsh-b", "小榫", teamId = "tm-9"))),
            ),
            teamProfiles = listOf(profile("tm-1", "支付组")),
        )
        assertTrue(orphansOf(st).isEmpty(), "只要还有一处席位引用它，它就是在入驻状态")
    }

    @Test
    fun noProfilesMeansNoOrphans() {
        // 手机端读不到 `teamProfiles`（档案只住宿主机）：这是空列表，
        // 不是「所有团队都是孤儿」—— 读不到与判定过是两件事。
        assertTrue(orphansOf(state(groups = emptyList())).isEmpty())
    }

    @Test
    fun orphansAreSortedByName() {
        val st = state(
            groups = emptyList(),
            // ASCII 名字：这里钉的是「按名字升序」，不是中文排序规则（那会把断言变成
            // 一次对 Unicode 码位的断言，判据就跑偏了）。
            teamProfiles = listOf(profile("tm-1", "bravo"), profile("tm-2", "alpha")),
        )
        assertEquals(listOf("alpha", "bravo"), orphansOf(st).map { it.name }, "没有活动数据，按名字钉稳定序")
    }
}
