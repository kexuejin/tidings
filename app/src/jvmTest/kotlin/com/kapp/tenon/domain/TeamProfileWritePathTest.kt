// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.declarationFor
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * 团队档案写路径（[applyTeamProfile]）的判据。
 *
 * 钉的是 B 档那三条承诺 —— 少一条，团队就又退回「名字等于群名、一队只能一群」：
 *
 * 1. **一份名册多群复用**：同一支队伍进两个群，两群各一条席位、各自执行点，档案只有一份；
 * 2. **改名一次生效**：改档案名字 ⇒ 所有引用群的名字同时变（不是各改各的）；
 * 3. **半途失败不留半张网**：任一目标群的名册会撞 token ⇒ **整体拒绝**，档案与席位都不动。
 *
 * 以及两条边界：档案化席位不许被群内整表替换（两个写入口 = 两份名册）；
 * 解散只摘团队形态、不删席位（那个人还在群里、还能被 `@`）。
 */
class TeamProfileWritePathTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val peer = "11111111-1111-4111-8111-111111111111"

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(self),
        scope = scope,
        version = "relay-test",
        agentStore = InMemoryAgentStore(),
    )

    private class MemorySettingsStore(private val nodeId: String) : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = nodeId,
            nick = "我的桌面",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }

    /**
     * 三个群：
     * - `g1` 干净，本机已有 `dsh` 席位（改名/名册重建都从这里改）；
     * - `g2` 干净但**没有**本机席位（验证「把团队加进群」会就地入驻）；
     * - `g3` 有个对端席位叫 `coder`（验证名册撞 token 时整体拒绝）。
     */
    private fun seed(controller: RelayController) {
        val groups = listOf(
            GroupMeta(
                groupId = "g1",
                name = "项目研发群",
                members = listOf(self, peer),
                rev = 4,
                updatedBy = peer,
                updatedTs = 1_000L,
                virtualAgents = listOf(declarationFor("dsh", self, "dsh-lead", "ws-a", previous = null)),
            ),
            GroupMeta(
                groupId = "g2",
                name = "测试群",
                members = listOf(self),
                rev = 2,
                updatedBy = self,
                updatedTs = 1_000L,
                virtualAgents = null,
            ),
            GroupMeta(
                groupId = "g3",
                name = "已有 coder 的群",
                members = listOf(self, peer),
                rev = 1,
                updatedBy = peer,
                updatedTs = 1_000L,
                virtualAgents = listOf(
                    VirtualAgentMember(agentId = "codex", hostNodeId = peer, name = "coder"),
                ),
            ),
        )
        controller.handlePersist(controller.state.value.settings.copy(groups = groups))
    }

    private fun groupOf(controller: RelayController, groupId: String): GroupMeta =
        controller.state.value.settings.groups.first { it.groupId == groupId }

    private fun seatOf(controller: RelayController, groupId: String): VirtualAgentMember? =
        groupOf(controller, groupId).virtualAgents.orEmpty()
            .firstOrNull { it.hostNodeId == self && it.agentId == "dsh" }

    private val roster = listOf(
        TeamMemberDraft(agentId = "m-coder", name = "dev", role = "编码"),
        TeamMemberDraft(agentId = "m-qa", name = "qa", role = "测试"),
    )

    private fun createCommand(
        groupIds: List<String> = listOf("g1", "g2"),
        name: String = "开发团队",
        members: List<TeamMemberDraft> = roster,
        teamId: String = "tm-test",
    ) = TeamProfileCommand.Create(
        name = name,
        leaderAgentId = "dsh",
        members = members,
        groupIds = groupIds,
        teamId = teamId,
        actor = DeclarationActor.Human(self),
    )

    // ------------------------------------------------------------ 一份名册多群复用

    @Test
    fun oneProfileBacksTheSameTeamInSeveralGroups() {
        val controller = controller().also { seed(it) }
        val result = controller.applyTeamProfile(createCommand())
        assertTrue(result is TeamProfileResult.Applied, "建档应当成功，实际 $result")
        assertEquals(listOf("g1", "g2"), result.groupIds)
        assertTrue(result.failedGroupIds.isEmpty(), "两个群都该写成功")

        // 档案只有一份，名册是它的。
        val profiles = controller.state.value.settings.teamProfiles
        assertEquals(1, profiles.size, "多群复用靠一份档案，不是每个群一份")
        assertEquals("tm-test", profiles.single().teamId)
        assertEquals("开发团队", profiles.single().name)
        assertEquals(2, profiles.single().members.size)

        // 两个群各一条席位，指向同一份档案、同一份快照。
        for (groupId in listOf("g1", "g2")) {
            val seat = assertNotNull(seatOf(controller, groupId), "$groupId 里该有领队席位")
            assertEquals(EntityTypes.TEAM, seat.entityType)
            assertEquals("tm-test", seat.teamId)
            assertEquals("开发团队", seat.teamName)
            assertEquals(listOf("dev", "qa"), seat.teamMembers.map { it.name })
        }
        // 执行点是**每群事实**：g1 原有 ws-a 不能被档案抹掉，g2 没有就是没有。
        assertEquals("ws-a", seatOf(controller, "g1")?.defaultWsId)
        assertNull(seatOf(controller, "g2")?.defaultWsId)

        // 每群各自 rev+1、各自一行审计（跨群不是一次提交）。
        assertEquals(5, groupOf(controller, "g1").rev)
        assertEquals(3, groupOf(controller, "g2").rev)
        val audit = controller.declarationAuditLog.recent()
        assertEquals(2, audit.size, "两个群各一行")
        assertTrue(audit.all { it.command == "team.attach" })
    }

    // ------------------------------------------------------------ 改名一次生效

    @Test
    fun renamingTheProfileRenamesItInEveryGroup() {
        val controller = controller().also { seed(it) }
        controller.applyTeamProfile(createCommand())

        val renamed = controller.applyTeamProfile(
            TeamProfileCommand.Update(
                teamId = "tm-test",
                name = "测试团队",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(renamed is TeamProfileResult.Applied, "改名应当成功，实际 $renamed")
        assertEquals("测试团队", renamed.name)

        // 所有引用群同时变 —— 不是"改完 A 群还要再改 B 群"。
        for (groupId in listOf("g1", "g2")) {
            assertEquals(
                "测试团队",
                seatOf(controller, groupId)?.teamName,
                "$groupId 没有跟着改名：改名一次生效破了",
            )
        }
        // 档案 rev 只 +1（名字改一次，不是每群各一次）。
        assertEquals(2, controller.state.value.settings.teamProfiles.single().rev)
        // 身份键不受改名影响。
        assertEquals("dsh", seatOf(controller, "g1")?.agentId)
        assertEquals("dsh-lead", seatOf(controller, "g1")?.name, "团队名不是 @ token，不许顶掉领队席位名")
    }

    @Test
    fun renamingKeepsEveryGroupRevisionAdvancing() {
        val controller = controller().also { seed(it) }
        controller.applyTeamProfile(createCommand())
        val before = listOf("g1", "g2").associateWith { groupOf(controller, it).rev }

        controller.applyTeamProfile(
            TeamProfileCommand.Update(teamId = "tm-test", name = "平台团队", actor = DeclarationActor.Human(self)),
        )

        for (groupId in before.keys) {
            assertEquals(
                before.getValue(groupId) + 1,
                groupOf(controller, groupId).rev,
                "$groupId 的快照变了却没推进 rev —— 对端会当成没变而丢弃",
            )
        }
    }

    // ------------------------------------------------------------ 半途失败不留半张网

    @Test
    fun aTokenCollisionInAnyTargetGroupRejectsTheWholeThing() {
        val controller = controller().also { seed(it) }
        val g1Rev = groupOf(controller, "g1").rev

        // g3 里已有对端席位叫 `coder`；名册里的名字撞上它。
        val result = controller.applyTeamProfile(
            createCommand(
                groupIds = listOf("g1", "g3"),
                members = listOf(TeamMemberDraft(agentId = "m-coder", name = "coder", role = "编码")),
            ),
        )
        assertTrue(result is TeamProfileResult.Rejected, "撞 token 必须整体拒绝，实际 $result")
        assertEquals(DeclarationRejection.ROSTER_INVALID, result.reason)

        assertTrue(controller.state.value.settings.teamProfiles.isEmpty(), "拒绝之后不许留下档案")
        assertEquals(g1Rev, groupOf(controller, "g1").rev, "g1 什么都不许改")
        assertNull(seatOf(controller, "g1")?.teamId, "g1 席位不许被挂上档案引用")
        assertTrue(controller.declarationAuditLog.recent().isEmpty(), "没落盘就不许有审计行")
    }

    // ------------------------------------------------------------ 加进更多群

    @Test
    fun addingAnExistingTeamToAnotherGroupCreatesTheLeaderSeatThere() {
        val controller = controller().also { seed(it) }
        controller.applyTeamProfile(createCommand(groupIds = listOf("g1")))
        assertNull(seatOf(controller, "g2"), "前提：g2 里还没有这个席位")

        val added = controller.applyTeamProfile(
            TeamProfileCommand.AddToGroups(
                teamId = "tm-test",
                groupIds = listOf("g2"),
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(added is TeamProfileResult.Applied, "加群应当成功，实际 $added")
        val seat = assertNotNull(seatOf(controller, "g2"), "「群添加一个团队」应当就地入驻领队席位")
        assertEquals("tm-test", seat.teamId)
        assertEquals("开发团队", seat.teamName)
        assertEquals(EntityTypes.TEAM, seat.entityType)
        // 名册不重填：直接沿用档案那一份。
        assertEquals(listOf("dev", "qa"), seat.teamMembers.map { it.name })
    }

    // ------------------------------------------------------------ 两个写入口的收口

    @Test
    fun theGroupLevelRosterEditorRefusesAProfileBackedSeat() {
        val controller = controller().also { seed(it) }
        controller.applyTeamProfile(createCommand())
        val revBefore = groupOf(controller, "g1").rev

        val result = controller.applyDeclaration(
            GroupDeclarationCommand.SetTeamRoster(
                groupId = "g1",
                agentId = "dsh",
                roster = listOf(TeamMemberDraft(agentId = "m-x", name = "solo", role = "编码")),
                actor = DeclarationActor.Human(self),
            ),
        )
        assertEquals(
            DeclarationResult.Rejected(DeclarationRejection.TEAM_PROFILE_BACKED),
            result,
            "档案化团队的名册归档案：群内整表替换必须拒绝，否则两处各写一份",
        )
        assertEquals(revBefore, groupOf(controller, "g1").rev, "拒绝不许推进 rev")
        assertEquals(
            listOf("dev", "qa"),
            seatOf(controller, "g1")?.teamMembers?.map { it.name },
            "被拒的写入不许动名册",
        )
    }

    // ------------------------------------------------------------ 解散与缺档

    @Test
    fun dissolvingDetachesEveryGroupButKeepsTheSeats() {
        val controller = controller().also { seed(it) }
        controller.applyTeamProfile(createCommand())

        val dissolved = controller.applyTeamProfile(
            TeamProfileCommand.Dissolve(teamId = "tm-test", actor = DeclarationActor.Human(self)),
        )
        assertTrue(dissolved is TeamProfileResult.Applied, "解散应当成功，实际 $dissolved")
        assertTrue(controller.state.value.settings.teamProfiles.isEmpty(), "档案要删掉")

        for (groupId in listOf("g1", "g2")) {
            val seat = assertNotNull(seatOf(controller, groupId), "解散团队不删席位 —— 那个人还在群里")
            assertNull(seat.teamId)
            assertNull(seat.teamName)
            assertEquals(EntityTypes.SINGLE, seat.entityType)
            assertTrue(seat.teamMembers.isEmpty())
        }
    }

    @Test
    fun anUnknownTeamIdIsRejectedInsteadOfCreatingAGhost() {
        val controller = controller().also { seed(it) }
        val result = controller.applyTeamProfile(
            TeamProfileCommand.Update(teamId = "tm-nope", name = "幽灵团队", actor = DeclarationActor.Human(self)),
        )
        assertEquals(TeamProfileResult.Rejected(DeclarationRejection.TEAM_UNKNOWN), result)
        assertTrue(controller.state.value.settings.teamProfiles.isEmpty())
    }

    @Test
    fun aTeamWithoutMembersIsNotATeam() {
        val controller = controller().also { seed(it) }
        val result = controller.applyTeamProfile(createCommand(members = emptyList()))
        assertEquals(TeamProfileResult.Rejected(DeclarationRejection.ARGUMENT_INVALID), result)
    }

    @Test
    fun anOverlongTeamNameIsRejected() {
        val controller = controller().also { seed(it) }
        val result = controller.applyTeamProfile(createCommand(name = "x".repeat(64)))
        assertEquals(TeamProfileResult.Rejected(DeclarationRejection.NAME_INVALID), result)
    }

    // ------------------------------------------------------------ 界面入口

    /**
     * 界面入口（`saveTeamProfileEntry`）：新建只进一个群，之后再改一次名并加进第二个群。
     *
     * 钉的是「编辑 = 先 Update 再加群」那条顺序：改名对所有引用群生效，**并且**
     * 新群拿到的已经是改后的名字（快照从档案投影，不是从旧值抄）。
     */
    @Test
    fun savingFromTheUiRenamesAndAddsGroupsInOneAction() {
        val controller = controller().also { seed(it) }
        controller.saveTeamProfileEntry(null, "开发团队", "dsh", roster, listOf("g1"))
        val profile = assertNotNull(
            controller.state.value.settings.teamProfiles.singleOrNull(),
            "界面新建应当落一份档案",
        )
        assertEquals("开发团队", seatOf(controller, "g1")?.teamName)

        controller.saveTeamProfileEntry(profile.teamId, "交付团队", "dsh", roster, listOf("g2"))

        assertEquals("交付团队", controller.state.value.settings.teamProfiles.single().name)
        // 改名对所有引用群生效，新加入的群拿到的是改后的名字。
        assertEquals("交付团队", seatOf(controller, "g1")?.teamName)
        assertEquals("交付团队", seatOf(controller, "g2")?.teamName)
        // 横幅如实报**这次真的写下去的群数**：改名重建了 g1，新增写入了 g2 ⇒ 2。
        val banner = assertNotNull(controller.state.value.banner).message
        assertEquals(BannerMessage.TeamProfileSaved("交付团队", 2), banner)
    }

    /**
     * 「添加团队」（群侧入口）：整支队伍一次进群，组员随档案过来 —— 并且横幅报的是
     * 这一群写成功，不是「档案存在」。
     */
    @Test
    fun attachingAnExistingTeamBringsTheWholeRoster() {
        val controller = controller().also { seed(it) }
        controller.saveTeamProfileEntry(null, "开发团队", "dsh", roster, listOf("g1"))
        val teamId = controller.state.value.settings.teamProfiles.single().teamId

        controller.attachTeamToGroupEntry(teamId, "g2")

        val seat = assertNotNull(seatOf(controller, "g2"), "加入后该群要有领队席位")
        assertEquals(EntityTypes.TEAM, seat.entityType)
        assertEquals(teamId, seat.teamId)
        // 组员随档案过来：不需要在群里一个个加人。
        assertEquals(listOf("dev", "qa"), seat.teamMembers.map { it.name })
        val banner = assertNotNull(controller.state.value.banner).message
        assertEquals(BannerMessage.TeamProfileSaved("开发团队", 1), banner)
    }

    /** 坏输入走警告位、且什么都没落：绿色横幅会把「没有保存」说成「保存了」。 */
    @Test
    fun aBlankTeamNameFromTheUiIsReportedAsAWarningAndWritesNothing() {
        val controller = controller().also { seed(it) }
        controller.saveTeamProfileEntry(null, "  ", "dsh", roster, listOf("g1"))

        assertTrue(controller.state.value.settings.teamProfiles.isEmpty())
        val banner = assertNotNull(controller.state.value.banner)
        assertEquals(BannerTone.Warn, banner.tone)
        assertEquals(BannerMessage.TeamProfileRejected, banner.message)
    }
}
