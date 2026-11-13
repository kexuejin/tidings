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
 * 编制唯一写路径（[applyDeclaration]）的判据。
 *
 * 方案 §6 的六条验收里，本类钉前五条里不依赖真机的部分：
 * - **入口等价**：同一次团队编制，走旧入口（`handleSetGroupTeamDeclaration`）与走命令，
 *   落库结果逐字段相同、rev 各 +1 一次、审计各一行；
 * - **并发**：`baseRev` 不匹配 ⇒ [DeclarationResult.Conflict]，落盘与审计都不动；
 * - **agent 边界**：agent 发起必须带 `baseRev`；替别的宿主声明一律拒；
 * - **改名不改身份**：`UpdateSeat` 只换 `name`，`agentId` / 团队字段 / 白名单不动；
 * - **拒绝不落盘**：任何 [DeclarationResult.Rejected] 之后 rev 与席位数都原样。
 */
class GroupDeclarationCommandTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val peer = "11111111-1111-4111-8111-111111111111"
    private val peer2 = "22222222-2222-4222-8222-222222222222"

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
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }

    /** 与 `TeamDeclarationWritePathTest` 同一份种子：本机两条席位 + 两条外来声明，rev = 4。 */
    private fun seed(controller: RelayController): GroupMeta {
        val meta = GroupMeta(
            groupId = "g1",
            name = "项目研发群",
            members = listOf(self, peer, peer2),
            rev = 4,
            updatedBy = peer,
            updatedTs = 1_000L,
            ownerId = peer,
            adminIds = emptyList(),
            virtualAgents = listOf(
                declarationFor("dsh", self, "dsh-ws-a", "ws-a", previous = null),
                declarationFor("codex", self, "codex-ws-b", "ws-b", previous = null),
                VirtualAgentMember(agentId = "codex", hostNodeId = peer, name = "codex-peer", defaultWsId = "ws-peer"),
                VirtualAgentMember(agentId = "opencode", hostNodeId = peer2, name = "opencode-peer2", defaultWsId = "ws-peer2"),
            ),
        )
        controller.handlePersist(controller.state.value.settings.copy(groups = listOf(meta)))
        return meta
    }

    private fun groupOf(controller: RelayController): GroupMeta =
        controller.state.value.settings.groups.first { it.groupId == "g1" }

    private val roster = listOf(
        TeamMemberDraft(agentId = "m-coder", name = "coder", role = "编码"),
        TeamMemberDraft(agentId = "m-reviewer", name = "reviewer", role = "审查"),
    )

    private fun humanCommand(
        roster: List<TeamMemberDraft>? = this.roster,
        baseRev: Int? = null,
        agentId: String = "dsh",
    ) = GroupDeclarationCommand.SetTeamRoster(
        groupId = "g1",
        agentId = agentId,
        roster = roster,
        baseRev = baseRev,
        actor = DeclarationActor.Human(self),
    )

    // ------------------------------------------------------------ 入口等价

    @Test
    fun commandAndLegacyEntryProduceTheSameGroup() {
        val legacy = controller().also { seed(it) }
        val viaCommand = controller().also { seed(it) }

        legacy.handleSetGroupTeamDeclaration("g1", "dsh", roster)
        val applied = viaCommand.applyDeclaration(humanCommand())
        assertTrue(applied is DeclarationResult.Applied, "命令应当被接受，实际 $applied")

        val a = groupOf(legacy)
        val b = groupOf(viaCommand)
        assertEquals(a.rev, b.rev, "同一变更必须推进同样的 rev")
        assertEquals(5, b.rev)
        assertEquals(a.updatedBy, b.updatedBy)
        // 逐席位比对（updatedTs 两边各自取 now，不参与等价）。
        assertEquals(
            a.virtualAgents.orEmpty().map { it.copy() }.toString(),
            b.virtualAgents.orEmpty().map { it.copy() }.toString(),
            "两个入口的落库结果必须一致（席位顺序与字段都算）",
        )
        assertEquals(1, legacy.declarationAuditLog.recent().size)
        assertEquals(1, viaCommand.declarationAuditLog.recent().size)
        assertEquals("roster.set", viaCommand.declarationAuditLog.recent().single().command)
    }

    @Test
    fun closingATeamGoesBackToSingleAndIsAuditedAsClear() {
        val controller = controller().also { seed(it) }
        controller.applyDeclaration(humanCommand())
        val closed = controller.applyDeclaration(humanCommand(roster = null))
        assertTrue(closed is DeclarationResult.Applied)
        val seat = groupOf(controller).virtualAgents.orEmpty().first { it.defaultWsId == "ws-a" }
        assertEquals(EntityTypes.SINGLE, seat.entityType)
        assertTrue(seat.teamMembers.isEmpty())
        assertEquals(
            listOf("roster.set", "roster.clear"),
            controller.declarationAuditLog.recent().map { it.command },
        )
    }

    // ------------------------------------------------------------ 并发

    @Test
    fun staleBaseRevIsRejectedWithoutTouchingTheGroup() {
        val controller = controller().also { seed(it) }
        val before = groupOf(controller)

        val result = controller.applyDeclaration(humanCommand(baseRev = before.rev - 1))

        assertTrue(result is DeclarationResult.Conflict, "基线过期必须报冲突，实际 $result")
        assertEquals(before.rev - 1, (result as DeclarationResult.Conflict).baseRev)
        assertEquals(before.rev, result.actualRev)
        assertEquals(before, groupOf(controller), "冲突时一个字段都不许改")
        assertTrue(controller.declarationAuditLog.recent().isEmpty(), "没落盘就不许有审计行")
    }

    @Test
    fun matchingBaseRevIsAcceptedAndAdvancesExactlyOnce() {
        val controller = controller().also { seed(it) }
        val result = controller.applyDeclaration(humanCommand(baseRev = 4))
        assertTrue(result is DeclarationResult.Applied)
        assertEquals(5, result.rev)
        // 同一基线再提交一次 = 已过期，不得二次推进。
        val again = controller.applyDeclaration(humanCommand(baseRev = 4))
        assertTrue(again is DeclarationResult.Conflict)
        assertEquals(5, groupOf(controller).rev)
    }

    // ------------------------------------------------------------ agent 边界

    private fun agentCommand(
        actorAgentId: String = "dsh",
        hostNodeId: String = self,
        baseRev: Int? = null,
    ) = GroupDeclarationCommand.SetTeamRoster(
        groupId = "g1",
        agentId = "dsh",
        roster = roster,
        baseRev = baseRev,
        actor = DeclarationActor.Agent(hostNodeId = hostNodeId, agentId = actorAgentId),
        origin = DeclarationOrigin.AGENT_TOOL,
    )

    @Test
    fun agentWithoutBaseRevIsRejected() {
        val controller = controller().also { seed(it) }
        val result = controller.applyDeclaration(agentCommand())
        assertEquals(
            DeclarationResult.Rejected(DeclarationRejection.BASE_REV_REQUIRED),
            result,
            "智能体看不到界面版本，不带基线的写入等于静默覆盖",
        )
        assertEquals(4, groupOf(controller).rev)
    }

    @Test
    fun agentDeclaringAnotherHostIsRejected() {
        val controller = controller().also { seed(it) }
        val result = controller.applyDeclaration(agentCommand(hostNodeId = peer, baseRev = 4))
        assertEquals(DeclarationResult.Rejected(DeclarationRejection.NOT_MEMBER), result)
        assertEquals(4, groupOf(controller).rev)
    }

    @Test
    fun agentWithNoSeatInTheGroupIsRejected() {
        val controller = controller().also { seed(it) }
        val result = controller.applyDeclaration(agentCommand(actorAgentId = "gemini", baseRev = 4))
        assertEquals(DeclarationResult.Rejected(DeclarationRejection.NOT_MEMBER), result)
        assertEquals(4, groupOf(controller).rev)
    }

    @Test
    fun agentWithBaselineAndItsOwnSeatIsAccepted() {
        val controller = controller().also { seed(it) }
        val result = controller.applyDeclaration(agentCommand(baseRev = 4))
        assertTrue(result is DeclarationResult.Applied, "自己的席位 + 正确基线 ⇒ 放行，实际 $result")
        val audit = controller.declarationAuditLog.recent().single()
        assertEquals("dsh", audit.actorAgentId, "审计要记清是哪个 agent 改的")
        assertEquals(DeclarationOrigin.AGENT_TOOL, audit.origin)
        assertEquals(5, audit.rev)
    }

    // ------------------------------------------------------------ 编辑与入驻

    @Test
    fun renamingASeatKeepsItsIdentityAndTeamFields() {
        val controller = controller().also { seed(it) }
        controller.applyDeclaration(humanCommand())

        val result = controller.applyDeclaration(
            GroupDeclarationCommand.UpdateSeat(
                groupId = "g1",
                agentId = "dsh",
                name = "dsh-coder",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(result is DeclarationResult.Applied, "改名应当被接受，实际 $result")
        val seat = groupOf(controller).virtualAgents.orEmpty().first { it.defaultWsId == "ws-a" }
        assertEquals("dsh-coder", seat.name)
        assertEquals("dsh", seat.agentId, "改名不许改身份键")
        assertEquals("ws-a", seat.defaultWsId, "改名不许动项目目录投影")
        assertEquals(EntityTypes.TEAM, seat.entityType, "改名不许拆团队")
        assertEquals(2, seat.teamMembers.size)
    }

    @Test
    fun renamingToATakenTokenIsRejected() {
        val controller = controller().also { seed(it) }
        val before = groupOf(controller)
        val result = controller.applyDeclaration(
            GroupDeclarationCommand.UpdateSeat(
                groupId = "g1",
                agentId = "dsh",
                name = "codex-peer",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertEquals(DeclarationRejection.NAME_INVALID, (result as DeclarationResult.Rejected).reason)
        assertEquals(before, groupOf(controller))
    }

    /**
     * 改名**不替换名册** ⇒ 组员名仍在用，领队不许抢走它。
     *
     * 这条是「陈旧 token」口径的边界：团队编制时旧组员名算陈旧（正被整份替换），
     * 改名时不算 —— 两个场景共用一把池子就会放行「领队改成 coder」，
     * 于是同一串字母既是组员又是领队，`@` 落到谁身上没有答案。
     */
    @Test
    fun renamingALeaderToItsOwnTeamMemberTokenIsRejected() {
        val controller = controller().also { seed(it) }
        controller.applyDeclaration(humanCommand())
        val result = controller.applyDeclaration(
            GroupDeclarationCommand.UpdateSeat(
                groupId = "g1",
                agentId = "dsh",
                name = "coder",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertEquals(DeclarationRejection.NAME_INVALID, (result as DeclarationResult.Rejected).reason)
        val seat = groupOf(controller).virtualAgents.orEmpty().first { it.defaultWsId == "ws-a" }
        assertEquals("dsh-ws-a", seat.name, "被拒之后名字必须原样")
    }

    @Test
    fun updatingAMissingSeatJoinsIt() {
        val controller = controller().also { seed(it) }
        val result = controller.applyDeclaration(
            GroupDeclarationCommand.UpdateSeat(
                groupId = "g1",
                agentId = "gemini",
                name = "gemini-qa",
                agentKind = "gemini",
                duty = "测试",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(result is DeclarationResult.Applied, "席位不存在 ⇒ 按入驻处理，实际 $result")
        val seat = groupOf(controller).virtualAgents.orEmpty()
            .first { it.agentId == "gemini" && it.hostNodeId == self }
        assertEquals("gemini-qa", seat.name)
    }

    @Test
    fun leavingSeatKeepsForeignDeclarationsUntouched() {
        val controller = controller().also { seed(it) }
        val foreignBefore = groupOf(controller).virtualAgents.orEmpty().filter { it.hostNodeId != self }

        val result = controller.applyDeclaration(
            GroupDeclarationCommand.LeaveSeat(
                groupId = "g1",
                agentId = "codex",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(result is DeclarationResult.Applied)
        val after = groupOf(controller)
        assertTrue(
            after.virtualAgents.orEmpty().none { it.hostNodeId == self && it.agentId == "codex" },
            "本机那条席位要被摘掉",
        )
        assertEquals(foreignBefore, after.virtualAgents.orEmpty().filter { it.hostNodeId != self })
        assertEquals(1, controller.declarationAuditLog.recent().size)
        assertNull(
            controller.declarationAuditLog.recent().single().actorAgentId,
            "人类发起的命令在审计里没有 agentId",
        )
    }

    @Test
    fun joinIsIdempotentOnTheSeatNameButNotOnTheRev() {
        val controller = controller().also { seed(it) }
        val join = GroupDeclarationCommand.JoinSeat(
            groupId = "g1",
            agentId = "gemini",
            agentKind = "gemini",
            duty = "测试",
            actor = DeclarationActor.Human(self),
        )
        assertTrue(controller.applyDeclaration(join) is DeclarationResult.Applied)
        val first = groupOf(controller).virtualAgents.orEmpty().first { it.agentId == "gemini" }
        assertTrue(controller.applyDeclaration(join) is DeclarationResult.Applied)
        val seats = groupOf(controller).virtualAgents.orEmpty().filter { it.agentId == "gemini" }
        assertEquals(1, seats.size, "重复入驻不许长出第二条席位")
        assertEquals(first.name, seats.single().name, "重复入驻不许换名（@ token 不漂）")
        assertEquals(6, groupOf(controller).rev, "两次命令 = 两次 rev+1（幂等靠 rev，不靠去重窗口）")
    }

    @Test
    fun unknownGroupAndNonMemberAreRejected() {
        val controller = controller().also { seed(it) }
        val unknown = controller.applyDeclaration(
            GroupDeclarationCommand.SetTeamRoster(
                groupId = "g-none",
                agentId = "dsh",
                roster = roster,
                actor = DeclarationActor.Human(self),
            ),
        )
        assertEquals(DeclarationRejection.GROUP_UNKNOWN, (unknown as DeclarationResult.Rejected).reason)

        val outsider = controller.applyDeclaration(
            GroupDeclarationCommand.SetTeamRoster(
                groupId = "g1",
                agentId = "dsh",
                roster = roster,
                actor = DeclarationActor.Human("99999999-9999-4999-8999-999999999999"),
            ),
        )
        assertEquals(DeclarationRejection.NOT_MEMBER, (outsider as DeclarationResult.Rejected).reason)
        assertEquals(4, groupOf(controller).rev)
    }

    /**
     * **撞别的席位 = 本群别名，不是拒绝**（与改写前相反的判据）：`codex-peer` 是群主
     * 那台机器上的席位名，团队里的 `codex-peer` 在本群只能叫 `codex-peer-2`。
     *
     * 反向探针：把 `planTeamRoster` 里的 `resolveNames` 换回 `rejected(ROSTER_INVALID)`，
     * 这条会红 —— 那正是「别人先占了名字就把整支团队挡在门外」的老行为。
     */
    @Test
    fun rosterThatCollidesWithAnotherSeatBecomesALocalAlias() {
        val controller = controller().also { seed(it) }
        val result = controller.applyDeclaration(
            humanCommand(roster = listOf(TeamMemberDraft(agentId = "m-x", name = "codex-peer", role = "编码"))),
        )
        assertTrue(result is DeclarationResult.Applied, "撞名不再是拒绝理由，实际 $result")

        val seat = assertNotNull(
            groupOf(controller).virtualAgents.orEmpty().firstOrNull { it.agentId == "dsh" },
            "席位必须还在",
        )
        assertEquals(listOf("codex-peer-2"), seat.teamMembers.map { it.name })

        // 别名只改自己那一行，**别人的席位一个字都不许动**。
        val other = assertNotNull(groupOf(controller).virtualAgents.orEmpty().firstOrNull { it.name == "codex-peer" })
        assertEquals("codex-peer", other.name)
    }
}
