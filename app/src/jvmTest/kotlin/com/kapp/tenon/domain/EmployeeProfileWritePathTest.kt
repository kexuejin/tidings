// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.AgentDuties
import com.kapp.tenon.core.protocol.GroupMeta
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
 * 员工档案写路径（[applyEmployeeProfile]）的判据。
 *
 * 钉的是「**员工先创建，群只负责添加**」这条语义 —— 少一条，员工就又退回
 * 「没有对象、名字按群派生、改名只能撤回重入驻」：
 *
 * 1. **可以先建员工、一个群都不进**：档案是独立的一份对象，不是席位的副产物；
 * 2. **一份档案多群复用**：同一员工进两个群，两群各一条席位，档案只有一份；
 * 3. **改名一次生效**：改档案名 ⇒ 所有引用群的显示名同时变，而 `@` token**不漂**
 *    （token 一漂，群里所有人的输入习惯就断）；
 * 4. **撞名是别名、不是拒绝**：员工 token 在某群被占用 ⇒ 该群用 `token-2`，
 *    结果里如实标出来（`aliasedGroupIds`），不静默；
 * 5. **删档案不踢人**：`Forget` 只摘员工引用，席位与名字都保留。
 */
class EmployeeProfileWritePathTest {

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
     * - `g1` 本机已有 `dsh` 席位 `dsh-lead`（验证「已有席位的名字不被档案抢走」）；
     * - `g2` 干净、没有本机席位（验证就地入驻 + 新席位用档案 token）；
     * - `g3` 对端席位叫 `coder`（验证撞名走别名而不是拒绝）。
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

    private fun createCommand(
        groupIds: List<String> = listOf("g1", "g2"),
        name: String = "老王",
        token: String? = null,
        employeeId: String = "emp-test",
        agentId: String = "dsh",
    ) = EmployeeProfileCommand.Create(
        name = name,
        agentId = agentId,
        groupIds = groupIds,
        token = token,
        employeeId = employeeId,
        actor = DeclarationActor.Human(self),
    )

    // ------------------------------------------------------------ 先建员工，再决定进哪个群

    @Test
    fun anEmployeeCanExistBeforeJoiningAnyGroup() {
        val controller = controller().also { seed(it) }
        val result = controller.applyEmployeeProfile(createCommand(groupIds = emptyList()))
        assertTrue(result is EmployeeProfileResult.Applied, "建档应当成功，实际 $result")
        assertTrue(result.groupIds.isEmpty(), "没给群就不该进任何群")

        val profile = assertNotNull(controller.state.value.settings.employeeProfiles.singleOrNull())
        assertEquals("emp-test", profile.employeeId)
        assertEquals("dsh", profile.agentId)
        assertEquals("老王", profile.name)
        // 纯中文名 slug 之后是空串 ⇒ 必须回落 kind-职责，不能产出打不出来的空 token。
        assertEquals("dsh-assistant", profile.token, "中文名要回落 kind-职责，不是空 token")

        // 群里一条席位都没动 —— 建档与进群是两件事。
        assertNull(seatOf(controller, "g1")?.employeeId)
        assertEquals(4, groupOf(controller, "g1").rev, "建档不该改群 rev")
    }

    // ------------------------------------------------------------ 一份档案多群复用

    @Test
    fun oneProfileBacksTheSameEmployeeInSeveralGroups() {
        val controller = controller().also { seed(it) }
        val result = controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1", "g2")))
        assertTrue(result is EmployeeProfileResult.Applied, "建档加群应当成功，实际 $result")
        assertEquals(listOf("g1", "g2"), result.groupIds)
        assertTrue(result.failedGroupIds.isEmpty(), "两个群都该写成功")

        // 档案只有一份。
        val profiles = controller.state.value.settings.employeeProfiles
        assertEquals(1, profiles.size, "多群复用靠一份档案，不是每个群一份")

        // 两个群各一条席位，指向同一份档案。
        for (groupId in listOf("g1", "g2")) {
            val seat = assertNotNull(seatOf(controller, groupId), "$groupId 里该有这条席位")
            assertEquals("emp-test", seat.employeeId, "$groupId 的席位没挂上档案引用")
            assertEquals("老王", seat.employeeName, "$groupId 的席位没写上员工名快照")
        }
        // 执行点是每群事实：g1 原有 ws-a 不被抹掉，g2 没有就是没有。
        assertEquals("ws-a", seatOf(controller, "g1")?.defaultWsId)
        assertNull(seatOf(controller, "g2")?.defaultWsId)
        // 已有席位保留它现在的名字（那可能就是用户在用的 @ token）。
        assertEquals("dsh-lead", seatOf(controller, "g1")?.name)
        // 新席位用档案 token。
        assertEquals("dsh-assistant", seatOf(controller, "g2")?.name)

        // g1 的席位名与档案 token 不同 ⇒ 本群别名，必须如实标出来。
        assertEquals(listOf("g1"), result.aliasedGroupIds)
    }

    // ------------------------------------------------------------ 撞名 = 本群别名

    @Test
    fun aTokenClashBecomesALocalAliasInsteadOfARejection() {
        val controller = controller().also { seed(it) }
        val result = controller.applyEmployeeProfile(
            createCommand(groupIds = listOf("g3"), token = "coder"),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "撞名不该让整个建档失败，实际 $result")
        assertEquals(listOf("g3"), result.aliasedGroupIds, "别名群必须被标出来，不能静默")

        val seat = assertNotNull(seatOf(controller, "g3"))
        assertEquals("emp-test", seat.employeeId)
        assertEquals("coder-2", seat.name, "撞名时用别名（token-2），不是拒绝入驻")
        assertEquals("老王", seat.employeeName, "员工名与 @ token 是两件事")
        // 对端那条同名席位一条都不许动。
        assertEquals(
            "coder",
            groupOf(controller, "g3").virtualAgents.orEmpty().first { it.hostNodeId == peer }.name,
        )
    }

    // ------------------------------------------------------------ 改名一次生效、token 不漂

    @Test
    fun renamingTheEmployeeUpdatesEverySnapshotWithoutTouchingTokens() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1", "g2")))
        val tokenBefore = controller.state.value.settings.employeeProfiles.single().token
        val seatNamesBefore = listOf("g1", "g2").associateWith { seatOf(controller, it)?.name }

        val renamed = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-test",
                name = "前端小王",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(renamed is EmployeeProfileResult.Applied, "改名应当成功，实际 $renamed")
        assertEquals("前端小王", renamed.name)

        for (groupId in listOf("g1", "g2")) {
            assertEquals("前端小王", seatOf(controller, groupId)?.employeeName, "$groupId 没跟着改名")
            assertEquals(seatNamesBefore[groupId], seatOf(controller, groupId)?.name, "@ token 不许跟着显示名漂")
        }
        // 档案 rev 只 +1（名字改一次，不是每群各一次）。
        assertEquals(2, controller.state.value.settings.employeeProfiles.single().rev)
        assertEquals(tokenBefore, controller.state.value.settings.employeeProfiles.single().token)
        assertEquals("dsh", seatOf(controller, "g1")?.agentId, "身份键不受改名影响")
    }

    @Test
    fun changingTheTokenExplicitlyRewritesEverySeatName() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1", "g2")))

        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-test",
                token = "coder-x",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "改 token 应当成功，实际 $result")
        assertEquals("coder-x", controller.state.value.settings.employeeProfiles.single().token)
        // 显式改 token 是对所有引用群的显式重建：席位名跟着改（这与「改名」相反）。
        for (groupId in listOf("g1", "g2")) {
            assertEquals("coder-x", seatOf(controller, groupId)?.name, "$groupId 的席位名没跟着 token 改")
        }
    }

    @Test
    fun aDutyChangeDoesNotTouchSeats() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1")))
        val nameBefore = seatOf(controller, "g1")?.name
        val revBefore = groupOf(controller, "g1").rev

        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-test",
                duty = "reviewer",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "改职责应当成功，实际 $result")
        assertEquals("reviewer", controller.state.value.settings.employeeProfiles.single().duty)
        assertEquals(nameBefore, seatOf(controller, "g1")?.name, "改职责不该动席位名")
        // 职责进档案就要重建快照（对端要能显示），所以 rev 仍会推进 —— 但只推一次。
        assertEquals(revBefore + 1, groupOf(controller, "g1").rev)
    }

    @Test
    fun dutyFallsBackToTheSingleDefaultInsteadOfNull() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = emptyList()))
        assertEquals(
            AgentDuties.DEFAULT_SINGLE,
            controller.state.value.settings.employeeProfiles.single().duty,
            "职责缺省必须归一成单兵默认，不能留 null 让下游各猜一遍",
        )
    }

    // ------------------------------------------------------------ 加群 / 删档案

    @Test
    fun anExistingProfileCanBeAddedToMoreGroupsLater() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1")))
        assertNull(seatOf(controller, "g2"), "前提：g2 还没有席位")

        val added = controller.applyEmployeeProfile(
            EmployeeProfileCommand.AddToGroups(
                employeeId = "emp-test",
                groupIds = listOf("g2"),
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(added is EmployeeProfileResult.Applied, "加群应当成功，实际 $added")
        assertEquals(listOf("g2"), added.groupIds)
        assertEquals("emp-test", seatOf(controller, "g2")?.employeeId, "后加的群也要挂上同一个档案")
        assertEquals(1, controller.state.value.settings.employeeProfiles.size, "加群不该复制档案")
    }

    @Test
    fun forgettingAnEmployeeKeepsTheSeatsAndTheirNames() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1", "g2")))
        val g2Name = seatOf(controller, "g2")?.name

        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Forget(
                employeeId = "emp-test",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "删档案应当成功，实际 $result")
        assertEquals(listOf("g1", "g2"), result.groupIds)

        assertTrue(controller.state.value.settings.employeeProfiles.isEmpty(), "档案该没了")
        // 席位与名字都保留 —— 删档案不等于把人踢出群。
        for (groupId in listOf("g1", "g2")) {
            val seat = assertNotNull(seatOf(controller, groupId), "$groupId 的席位不该被删")
            assertNull(seat.employeeId, "$groupId 的档案引用该摘掉")
            assertNull(seat.employeeName, "$groupId 的员工名快照该摘掉")
        }
        assertEquals(g2Name, seatOf(controller, "g2")?.name, "名字不跟着档案一起消失")
    }

    // ------------------------------------------------------------ 档案里的短 id ≠ 席位上的实例 id

    /**
     * 加一个群：本机席位用的是**完整实例 id**（`dsh-029bbbf3`），而档案里可能只存短 id `dsh`。
     *
     * 这不是编出来的状态：登记时的 `agentId` 取自卡片聚类的代表键（可能来自会话自报的短
     * `dsh`），而席位在入驻时写的是 agent 自报的完整 id。于是「按字符串找席位」会找不到 ——
     * 改名到不了这个群、注销摘不掉这条引用，而横幅照样是绿的。
     */
    private fun withLongIdSeat(controller: RelayController, groupId: String = "g4"): RelayController {
        val group = GroupMeta(
            groupId = groupId,
            name = "实例 id 群",
            members = listOf(self),
            rev = 1,
            updatedBy = self,
            updatedTs = 1_000L,
            virtualAgents = listOf(
                VirtualAgentMember(agentId = "dsh-029bbbf3", hostNodeId = self, name = "dsh-lead"),
            ),
        )
        controller.handlePersist(
            controller.state.value.settings.copy(
                groups = controller.state.value.settings.groups + group,
            ),
        )
        return controller
    }

    private fun localSeatsIn(controller: RelayController, groupId: String): List<VirtualAgentMember> =
        groupOf(controller, groupId).virtualAgents.orEmpty().filter { it.hostNodeId == self }

    /** 那条用实例 id 的席位（按 id 精确取，别拿 `single()` —— 多一条时报的是「列表有两条」）。 */
    private fun longIdSeat(controller: RelayController, groupId: String = "g4"): VirtualAgentMember? =
        localSeatsIn(controller, groupId).firstOrNull { it.agentId == "dsh-029bbbf3" }

    @Test
    fun aShortIdProfileAttachesToOneExistingInstanceIdSeat() {
        val controller = controller().also { seed(it); withLongIdSeat(it) }

        val created = controller.applyEmployeeProfile(createCommand(groupIds = listOf("g4")))
        assertTrue(created is EmployeeProfileResult.Applied, "挂档案应当成功，实际 $created")
        assertTrue(created.failedGroupIds.isEmpty(), "实例 id 那条席位该被认出来，不是「席位不存在」")

        val seats = localSeatsIn(controller, "g4")
        assertEquals(1, seats.size, "同一个 agent 在一个群里不许变成两条席位（短 id 一条、实例 id 一条）")
        val seat = assertNotNull(longIdSeat(controller), "要改的是既有那条实例 id 席位，不是新建一条 dsh")
        assertEquals("dsh-029bbbf3", seat.agentId)
        assertEquals("emp-test", seat.employeeId, "既有席位没挂上档案引用")
    }

    @Test
    fun renamingReachesASeatThatUsesTheLongInstanceId() {
        val controller = controller().also { seed(it); withLongIdSeat(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g4")))

        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-test",
                name = "老王",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(result is EmployeeProfileResult.Applied, "改名应当成功，实际 $result")
        assertTrue(result.failedGroupIds.isEmpty(), "短 id 档案改不到实例 id 席位 ⇒ 这个群永远收不到改名")
        assertEquals("老王", assertNotNull(longIdSeat(controller)).employeeName, "改名没到实例 id 那条席位")
    }

    @Test
    fun forgettingDetachesASeatThatUsesTheLongInstanceId() {
        val controller = controller().also { seed(it); withLongIdSeat(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g4")))
        // 前置：引用确实挂在实例 id 那条席位上（否则下面的 assertNull 是在断言「本来就是 null」，
        // 探针改坏写入路径也照样绿）。
        assertEquals(
            "emp-test",
            assertNotNull(longIdSeat(controller)).employeeId,
            "前置不成立：这条用例测不到注销",
        )

        controller.forgetEmployeeProfileEntry("emp-test")

        val seat = assertNotNull(longIdSeat(controller), "注销不许把那条实例 id 席位删掉")
        assertNull(seat.employeeId, "注销必须摘掉实例 id 席位上的引用，否则档案没了、席位还指着它")
        assertNull(seat.employeeName, "快照也要摘掉，否则手机端一直显示一个已经不存在的员工")
        val banner = assertNotNull(controller.state.value.banner)
        assertEquals(BannerTone.Info, banner.tone, "全摘干净了就不该报「有群没摘掉」")
    }

    // ------------------------------------------------------------ 界面入口：注销

    /** 注销走界面入口：档案真的没了，横幅说出是谁 —— 删除是个需要确认的动作，不能默默成功。 */
    @Test
    fun forgettingFromTheUiRemovesTheProfileAndNamesItInTheBanner() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = listOf("g1", "g2")))

        controller.forgetEmployeeProfileEntry("emp-test")

        assertTrue(controller.state.value.settings.employeeProfiles.isEmpty(), "档案该没了")
        val banner = assertNotNull(controller.state.value.banner)
        assertEquals(BannerTone.Info, banner.tone)
        assertEquals(BannerMessage.EmployeeProfileForgotten("老王"), banner.message)
    }

    @Test
    fun forgettingAnUnknownEmployeeIsReportedAsAWarning() {
        val controller = controller().also { seed(it) }

        controller.forgetEmployeeProfileEntry("emp-nope")

        val banner = assertNotNull(controller.state.value.banner)
        assertEquals(BannerTone.Warn, banner.tone)
        assertEquals(BannerMessage.EmployeeProfileRejected, banner.message)
    }

    /**
     * 有群没摘干净：横幅必须**报数**，不许把半成功说成全绿。
     *
     * 构造的是一个真实可达的角落：群里那条带档案引用的席位在对端宿主上（席位被复制过去、
     * 或我们的席位刚被别处摘掉时会出现）。本机没有可摘的席位 ⇒ 那个群失败，
     * 而那些群的 `employeeName` 快照还留着，手机会继续显示旧名 —— 用户得知道去哪儿收尾。
     */
    @Test
    fun aGroupThatCannotBeDetachedIsReportedWithItsCount() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = emptyList()))
        val foreignGroup = GroupMeta(
            groupId = "g5",
            name = "对端席位群",
            members = listOf(self, peer),
            rev = 1,
            updatedBy = peer,
            updatedTs = 1_000L,
            virtualAgents = listOf(
                VirtualAgentMember(
                    agentId = "codex",
                    hostNodeId = peer,
                    name = "coder",
                    employeeId = "emp-test",
                ),
            ),
        )
        controller.handlePersist(
            controller.state.value.settings.copy(
                groups = controller.state.value.settings.groups + foreignGroup,
            ),
        )

        controller.forgetEmployeeProfileEntry("emp-test")

        val banner = assertNotNull(controller.state.value.banner)
        assertEquals(BannerTone.Warn, banner.tone, "有一个群没摘掉，就不能走信息位")
        assertEquals(
            BannerMessage.EmployeeProfileForgotten("老王", failedGroupCount = 1),
            banner.message,
        )
    }

    // ------------------------------------------------------------ 入驻也要带上档案

    /**
     * 「群信息 → 邀请智能体入驻」是**唯一**能把已有员工加进第二个群的入口
     * （员工详情页只有名字 / 职责 / 谁可调用，没有加群）。所以这条路上的席位必须当场写上
     * 员工引用与名字快照 —— 少一个症状就是「宿主显示员工名、手机还是 token」，
     * 而且之后的改名**永远到不了这个群**（重建快照是按 `employeeId` 找引用群的）。
     */
    @Test
    fun joiningAGroupAdoptsTheExistingProfile() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(
            createCommand(groupIds = listOf("g1"), name = "前端小王", token = "dsh-coder"),
        )

        val join = controller.applyDeclaration(
            GroupDeclarationCommand.JoinSeat(
                groupId = "g2",
                agentId = "dsh",
                agentKind = "dsh",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertTrue(join is DeclarationResult.Applied, "入驻应当成功，实际 $join")

        val seat = assertNotNull(seatOf(controller, "g2"))
        assertEquals("emp-test", seat.employeeId, "入驻没挂档案引用 ⇒ 这个群永远收不到改名")
        assertEquals("前端小王", seat.employeeName, "手机只读快照，不写快照就是两端两个名字")
        assertEquals("dsh-coder", seat.name, "新席位用档案 token，不是 kind-职责")

        // 改名按 `employeeId` 找引用群 —— 这条席位现在在册，必须跟着变。
        controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-test",
                name = "老王",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertEquals("老王", seatOf(controller, "g2")?.employeeName, "改名没到第二个群")
        assertEquals("dsh-coder", seatOf(controller, "g2")?.name, "改名不许动 @ token")
    }

    @Test
    fun joiningWithATakenTokenGetsALocalAliasNotADerivedName() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = emptyList(), token = "coder"))

        controller.applyDeclaration(
            GroupDeclarationCommand.JoinSeat(
                groupId = "g3",
                agentId = "dsh",
                agentKind = "dsh",
                actor = DeclarationActor.Human(self),
            ),
        )
        val seat = assertNotNull(seatOf(controller, "g3"))
        assertEquals("coder-2", seat.name, "撞名给本群别名，不是拒绝、也不是回头用 kind-职责")
        assertEquals("emp-test", seat.employeeId)
        assertEquals(
            "coder",
            groupOf(controller, "g3").virtualAgents.orEmpty().first { it.hostNodeId == peer }.name,
            "对端那条同名席位一条都不许动",
        )
    }

    @Test
    fun reJoiningKeepsTheSeatNameTheGroupAlreadyUses() {
        val controller = controller().also { seed(it) }
        // g1 早就有 `dsh-lead` 这条席位 —— 群里 @ 的就是这个名字。
        controller.applyEmployeeProfile(createCommand(groupIds = emptyList(), token = "dsh-coder"))
        controller.applyDeclaration(
            GroupDeclarationCommand.JoinSeat(
                groupId = "g1",
                agentId = "dsh",
                agentKind = "dsh",
                actor = DeclarationActor.Human(self),
            ),
        )
        val seat = assertNotNull(seatOf(controller, "g1"))
        assertEquals("dsh-lead", seat.name, "已有席位的 @ token 不许被档案改掉（输入习惯会断）")
        assertEquals("emp-test", seat.employeeId, "名字照旧，但引用要补上")
        assertEquals("老王", seat.employeeName, "快照也要补上，否则手机读不到这个人叫什么")
    }

    /** 档案 token 脏了（非归一形态）不许直接进席位名：`@` 后面要打的是 ASCII 短名。 */
    @Test
    fun aDirtyProfileTokenFallsBackToTheDerivedSeatName() {
        val dirty = EmployeeProfile("emp-x", "dsh", "前端小王", token = "小王 coder")
        assertEquals("dsh-assistant", dirty.seatToken("dsh", AgentDuties.DEFAULT_SINGLE))
        assertEquals("dsh-coder", dirty.copy(token = "dsh-coder").seatToken("dsh", "reviewer"))
    }

    // ------------------------------------------------------------ 拒绝面

    @Test
    fun aSecondProfileForTheSameAgentIsRejected() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = emptyList()))
        val second = controller.applyEmployeeProfile(
            createCommand(groupIds = emptyList(), name = "小李", employeeId = "emp-2"),
        )
        assertEquals(
            DeclarationRejection.EMPLOYEE_EXISTS,
            (second as? EmployeeProfileResult.Rejected)?.reason,
            "一个 agent 只能有一份档案；要改名走 Update",
        )
    }

    @Test
    fun updatingAnUnknownProfileIsRejected() {
        val controller = controller().also { seed(it) }
        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-nope",
                name = "谁",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertEquals(
            DeclarationRejection.EMPLOYEE_UNKNOWN,
            (result as? EmployeeProfileResult.Rejected)?.reason,
        )
    }

    @Test
    fun anExplicitTokenClashingWithAnotherProfileIsRejected() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(
            createCommand(groupIds = emptyList(), token = "dsh-a", employeeId = "emp-a"),
        )
        val clash = controller.applyEmployeeProfile(
            createCommand(groupIds = emptyList(), name = "小李", token = "dsh-a", employeeId = "emp-b", agentId = "codex"),
        )
        assertEquals(
            DeclarationRejection.NAME_INVALID,
            (clash as? EmployeeProfileResult.Rejected)?.reason,
            "本机两份档案不能共用一个 @ token",
        )
    }

    @Test
    fun anEmptyNameIsRejected() {
        val controller = controller().also { seed(it) }
        val result = controller.applyEmployeeProfile(
            createCommand(groupIds = emptyList(), name = "   "),
        )
        assertEquals(
            DeclarationRejection.NAME_INVALID,
            (result as? EmployeeProfileResult.Rejected)?.reason,
        )
    }

    @Test
    fun anEmptyUpdateIsRejected() {
        val controller = controller().also { seed(it) }
        controller.applyEmployeeProfile(createCommand(groupIds = emptyList()))
        val result = controller.applyEmployeeProfile(
            EmployeeProfileCommand.Update(
                employeeId = "emp-test",
                actor = DeclarationActor.Human(self),
            ),
        )
        assertEquals(
            DeclarationRejection.ARGUMENT_INVALID,
            (result as? EmployeeProfileResult.Rejected)?.reason,
            "一个字段都没给的更新是调用方拼错了，不许静默成功",
        )
    }
}
