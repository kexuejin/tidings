// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.CodecGroup
import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.core.protocol.declarationFor
import com.kapp.tenon.core.protocol.makeEnvelope
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.testutil.TestPorts
import com.kapp.tenon.testutil.awaitUntil
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 团队编制写路径（`handleSetGroupTeamDeclaration` + `commitGroupMetaUpdate`）的钉子。
 *
 * 方案 §8 的验收判据，逐条落成断言：
 * - **领域单测**：保存 rev+1、目标席位变团队、同 agent 其它席位被清空、外来声明
 *   逐字段不变、关闭回单兵、多席位写对目标、席位缺失/校验失败给可读反馈；
 * - **广播回归（§4.2）**：保存后**广播产物**（编码→解码）仍含 teamMembers ——
 *   只断言本地 settings 不够，F1 的现场就是「本机落库有、发出去的帧没有」。
 *   反向验证：把 `virtualAgentsFor` 里的 previous 回填去掉，广播产物用例红。
 * - **合并（双节点）**：编辑者 = 普通成员，对端 apply（依赖 GroupMerge 的自声明
 *   主权分支）；编辑者改他人声明，对端拒。
 */
class TeamDeclarationWritePathTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val peer = "11111111-1111-4111-8111-111111111111"
    private val peer2 = "22222222-2222-4222-8222-222222222222"

    private fun controller(agentStore: InMemoryAgentStore? = null): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(self),
        scope = scope,
        version = "relay-test",
        agentStore = agentStore ?: InMemoryAgentStore(),
    )

    /**
     * 预置一台「带 agentId 的本机工作区行」的控制器。
     *
     * 生产里这一行来自 agentStore 的恢复路径（`handleRestoreAgentData` →
     * `loadWorkspaces`）——只有它携带非空 `agentId`，而 `virtualAgentsFor` 的重建
     * **只认非空 agentId 的行**。不铺这一行，广播重建根本不发生，F1 的回归用例会
     * 假绿（反向验证时先栽在这里）。
     */
    private fun controllerWithLocalAgentWorkspace(): RelayController {
        val agentStore = InMemoryAgentStore()
        agentStore.saveWorkspace(
            WorkspaceUi(
                peerId = self,
                agentKey = "dsh",
                agentId = "dsh",
                wsId = "ws-a",
                wsTitle = "ws-a 标题",
                wsPath = null,
                agentKind = "DSH",
                online = true,
                sessionCount = null,
                at = com.kapp.tenon.core.nowMillis(),
            ),
        )
        val controller = controller(agentStore)
        controller.agentCtl.handleRestoreAgentData()
        return controller
    }

    /** 双节点用例的真节点工厂（与 TwoNodeAgentInvokeTest 同一形状）。 */
    private fun node(udpPort: Int, tcpPort: Int): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = udpPort,
                tcpPort = tcpPort,
                persistMessages = false,
                persistMessagesDecided = true,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
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

    /** 手工铺一个群：本机两个席位（不同 agentId）+ 两个外来声明。rev = 4。 */
    private fun seededGroup(controller: RelayController): GroupMeta {
        val mine = listOf(
            declarationFor("dsh", self, "dsh-ws-a", "ws-a", previous = null),
            declarationFor("codex", self, "codex-ws-b", "ws-b", previous = null),
        )
        val foreign = listOf(
            VirtualAgentMember(agentId = "codex", hostNodeId = peer, name = "codex-peer", defaultWsId = "ws-peer"),
            VirtualAgentMember(agentId = "opencode", hostNodeId = peer2, name = "opencode-peer2", defaultWsId = "ws-peer2"),
        )
        val meta = GroupMeta(
            groupId = "g1",
            name = "项目研发群",
            members = listOf(self, peer, peer2),
            rev = 4,
            updatedBy = peer,
            updatedTs = 1_000L,
            ownerId = peer,
            adminIds = emptyList(),
            virtualAgents = mine + foreign,
        )
        controller.handlePersist(
            controller.state.value.settings.copy(groups = listOf(meta)),
        )
        return meta
    }

    private fun groupOf(controller: RelayController): GroupMeta =
        controller.state.value.settings.groups.first { it.groupId == "g1" }

    private val roster = listOf(
        TeamMemberDraft(agentId = "m-coder", name = "coder", role = "编码"),
        TeamMemberDraft(agentId = "m-reviewer", name = "reviewer", role = "审查"),
    )

    // ---------------------------------------------------------------- 领域单测

    @Test
    fun savingATeamBumpsRevAndWritesTheRosterToTheTargetSeat() {
        val controller = controller()
        seededGroup(controller)
        controller.handleSetGroupTeamDeclaration("g1", "dsh", roster)
        val meta = groupOf(controller)
        assertEquals(5, meta.rev, "一次保存 = 一次 rev+1")
        assertEquals(self, meta.updatedBy)
        val seat = meta.virtualAgents.orEmpty().first { it.defaultWsId == "ws-a" }
        assertEquals(EntityTypes.TEAM, seat.entityType)
        assertEquals(
            listOf("coder" to "编码", "reviewer" to "审查"),
            seat.teamMembers.map { it.name to it.role },
        )
    }

    /** §3.1：一个 agent 每群最多一个团队 —— 保存到席位 A 时清掉同 agent 其它席位。 */
    @Test
    fun savingToSeatAClearsTheSiblingSeatsOfTheSameAgent() {
        val controller = controller()
        seededGroup(controller)
        // 旧数据形态：同 agentId 两条席位（D4 之后一个 agent 一条；写路径仍要收敛旧数据）。
        val local = groupOf(controller)
        val siblingSeat = declarationFor("dsh", self, "dsh-ws-b", "ws-b", previous = null)
        controller.handlePersist(
            controller.state.value.settings.copy(
                groups = controller.state.value.settings.groups.map {
                    if (it.groupId == "g1") {
                        it.copy(
                            virtualAgents = it.virtualAgents.orEmpty() + siblingSeat,
                            rev = it.rev + 1,
                        )
                    } else {
                        it
                    }
                },
            ),
        )
        controller.handleSetGroupTeamDeclaration("g1", "dsh", roster)
        val sibling = groupOf(controller).virtualAgents.orEmpty().first { it.defaultWsId == "ws-b" }
        assertEquals(EntityTypes.SINGLE, sibling.entityType, "同 agent 其它席位清成单兵")
        assertTrue(sibling.teamMembers.isEmpty())
    }

    /** 其它宿主的声明逐字段原样保留（主权边界）。 */
    @Test
    fun foreignDeclarationsAreUntouched() {
        val controller = controller()
        val before = seededGroup(controller)
        controller.handleSetGroupTeamDeclaration("g1", "dsh", roster)
        val after = groupOf(controller)
        for (foreign in before.virtualAgents.orEmpty().filter { it.hostNodeId != self }) {
            assertEquals(
                foreign,
                after.virtualAgents.orEmpty().first { it.hostNodeId == foreign.hostNodeId && it.agentId == foreign.agentId },
                "外来声明逐字段不变",
            )
        }
    }

    /** 关闭 = 同一函数反向写：null 或空清单都回单兵。 */
    @Test
    fun closingWithNullRosterRestoresSingle() {
        val controller = controller()
        seededGroup(controller)
        controller.handleSetGroupTeamDeclaration("g1", "dsh", roster)
        controller.handleSetGroupTeamDeclaration("g1", "dsh", null)
        var seat = groupOf(controller).virtualAgents.orEmpty().first { it.defaultWsId == "ws-a" }
        assertEquals(EntityTypes.SINGLE, seat.entityType)
        assertTrue(seat.teamMembers.isEmpty())
        assertEquals(6, groupOf(controller).rev, "关闭也是一次 rev+1 广播")

        // 空清单与 null 同语义。
        controller.handleSetGroupTeamDeclaration("g1", "dsh", emptyList())
        seat = groupOf(controller).virtualAgents.orEmpty().first { it.defaultWsId == "ws-a" }
        assertEquals(EntityTypes.SINGLE, seat.entityType)
    }

    /** 多席位：写的是**选中的那个**（agentId 定位，D4），不是"第一条"。 */
    @Test
    fun multiSeatWritesGoToTheSelectedSeat() {
        val controller = controller()
        seededGroup(controller)
        controller.handleSetGroupTeamDeclaration("g1", "codex", listOf(TeamMemberDraft("m-t", "tester", "测试")))
        val meta = groupOf(controller)
        assertEquals(EntityTypes.TEAM, meta.virtualAgents.orEmpty().first { it.defaultWsId == "ws-b" }.entityType)
        assertEquals(EntityTypes.SINGLE, meta.virtualAgents.orEmpty().first { it.defaultWsId == "ws-a" }.entityType, "没选的席位不动")
    }

    /** 席位已撤（授权面板取消暴露）：可读反馈，元数据不动。 */
    @Test
    fun aVanishedSeatFailsWithReadableFeedback() {
        val controller = controller()
        seededGroup(controller)
        controller.handleSetGroupTeamDeclaration("g1", "ws-gone", roster)
        val banner = controller.state.value.banner?.message
        assertEquals(BannerMessage.TeamSeatMissing, banner, "实际：$banner")
        assertEquals(4, groupOf(controller).rev, "失败的保存不落库")
    }

    /** 校验失败（撞群内 @ token）：可读反馈，rev 不动 —— 不允许「存下去再说」。 */
    @Test
    fun anInvalidRosterIsRejectedBeforePersist() {
        val controller = controller()
        seededGroup(controller)
        // "codex" 是外来声明的 agentId —— 在 @ token 池里。
        controller.handleSetGroupTeamDeclaration("g1", "dsh", listOf(TeamMemberDraft("m-x", "codex", "编码")))
        assertEquals(BannerMessage.TeamRosterInvalid, controller.state.value.banner?.message)
        assertEquals(4, groupOf(controller).rev)
    }

    /** 撞**领队自己的 token** 也不许（影子领队）。 */
    @Test
    fun aMemberNameCollidingWithTheLeaderTokenIsRejected() {
        val controller = controller()
        seededGroup(controller)
        controller.handleSetGroupTeamDeclaration("g1", "dsh", listOf(TeamMemberDraft("m-x", "dsh-ws-a", "编码")))
        assertEquals(BannerMessage.TeamRosterInvalid, controller.state.value.banner?.message)
        assertEquals(4, groupOf(controller).rev)
    }

    /** 沿用被编辑席位自己的旧组员名不算冲突（正在被整体替换）。 */
    @Test
    fun reusingTheSeatsOwnPreviousMemberNameIsAllowed() {
        val controller = controller()
        seededGroup(controller)
        controller.handleSetGroupTeamDeclaration("g1", "dsh", roster)
        // 第二次保存：沿用同一个组员名（roster 里 coder 已是 ws-a 席位的旧组员）。
        controller.handleSetGroupTeamDeclaration(
            "g1", "dsh",
            listOf(TeamMemberDraft("m-coder", "coder", "编码"), TeamMemberDraft("m-new", "tester", "测试")),
        )
        assertEquals(EntityTypes.TEAM, groupOf(controller).virtualAgents.orEmpty().first { it.defaultWsId == "ws-a" }.entityType)
    }

    // ---------------------------------------------------------------- 广播回归（§4.2）

    /**
     * **广播产物**（编码→解码）仍含团队字段。这是 F1 的回归钉子：只断言本地 settings
     * 区分不了「发出去的帧没有团队」—— 真机现场正是本机落库有、对端什么都没有。
     *
     * 关键前提：本机注册了同一席位的工作区 ⇒ 广播路径的 `virtualAgentsFor` 会**真的
     * 重建**这条声明（previous 查找 → declarationFor 回填团队字段）。没有这一步，
     * 重建根本不发生，用例恒绿 —— 反向验证就是这么发现测试自己没在测东西的。
     */
    @Test
    fun theBroadcastProductStillCarriesTeamFields() = runBlocking {
        val controller = controllerWithLocalAgentWorkspace()
        seededGroup(controller)
        com.kapp.tenon.testutil.awaitUntil("本机工作区行已恢复（带 agentId）") {
            controller.state.value.agentWorkspaces.values.any { it.peerId == self && it.agentId == "dsh" && it.wsId == "ws-a" }
        }
        controller.handleSetGroupTeamDeclaration("g1", "dsh", roster)
        // 广播路径用的就是这一份（commitGroupMetaUpdate → groupMetaWithNicks → sendGroupInfo）。
        val broadcast = controller.groupMetaWithNicks(groupOf(controller))
        val decoded = assertNotNull(
            Codec.decodeGroupInfo(CodecGroup.groupInfoPayload(broadcast)),
            "广播产物必须能被收端解码",
        )
        val seat = assertNotNull(
            decoded.virtualAgents.orEmpty().firstOrNull { it.hostNodeId == self && it.defaultWsId == "ws-a" },
            "广播产物里必须有本机席位",
        )
        assertEquals(EntityTypes.TEAM, seat.entityType, "广播产物（解码后）entityType 必须是 team —— F1 的修法")
        assertEquals(
            listOf("coder" to "编码", "reviewer" to "审查"),
            seat.teamMembers.map { it.name to it.role },
            "广播产物（解码后）组员清单必须完整",
        )
        // 解码产物还要过收端校验：发送侧不产出注定被拒的帧。
        assertTrue(CodecGroup.validateGroupMeta(broadcast), "广播产物必须过收端校验")
    }

    /** 广播重建还要保留既有名字（@ token 不能漂）—— unique 拿全体名字当池连自己都算的旧缺陷。 */
    @Test
    fun rebroadcastingKeepsTheExistingMentionToken() = runBlocking {
        val controller = controllerWithLocalAgentWorkspace()
        seededGroup(controller)
        com.kapp.tenon.testutil.awaitUntil("本机工作区行已恢复（带 agentId）") {
            controller.state.value.agentWorkspaces.values.any { it.peerId == self && it.agentId == "dsh" && it.wsId == "ws-a" }
        }
        val nameBefore = groupOf(controller).virtualAgents.orEmpty().first { it.defaultWsId == "ws-a" }.name
        controller.handleSetGroupTeamDeclaration("g1", "dsh", roster)
        val broadcast = controller.groupMetaWithNicks(groupOf(controller))
        assertEquals(
            nameBefore,
            broadcast.virtualAgents.orEmpty().first { it.defaultWsId == "ws-a" }.name,
            "重建不得给既有席位换名字（换名 = 把别人正在打的 mention 打断）",
        )
    }

    /** 补发台账的 hasTeams 维（§4.2）：同 rev 从「无团队」升级为「有团队」要能补发一次。 */
    @Test
    fun sameRevUpgradeFromSingleToTeamGetsOneRebroadcast() {
        val sentRev = mutableMapOf<Pair<String, String>, Int>()
        val sentAgents = mutableMapOf<Pair<String, String>, Boolean>()
        val sentTeams = mutableMapOf<Pair<String, String>, Boolean>()
        val member = peer
        fun record(hasAgents: Boolean, hasTeams: Boolean) {
            sentRev[member to "g1"] = 7
            sentAgents[member to "g1"] = hasAgents
            sentTeams[member to "g1"] = hasTeams
        }
        record(hasAgents = true, hasTeams = false)
        // 同 rev、同内容 → 跳过（台账去重的本体）。
        val ledger: (String) -> Pair<String, String> = { it to "g1" }
        assertTrue(
            freshRecipients(
                listOf(member), "g1", 7, hasAgents = true, hasTeams = false,
                alreadySent = { sentRev[ledger(it)] },
                alreadySentHadAgents = { sentAgents[ledger(it)] },
                alreadySentHadTeams = { sentTeams[ledger(it)] },
            ).isEmpty(),
            "同 rev 同内容不补发",
        )
        // 同 rev、内容升级（单兵 → 团队）→ 补发一次。
        assertEquals(
            listOf(member),
            freshRecipients(
                listOf(member), "g1", 7, hasAgents = true, hasTeams = true,
                alreadySent = { sentRev[ledger(it)] },
                alreadySentHadAgents = { sentAgents[ledger(it)] },
                alreadySentHadTeams = { sentTeams[ledger(it)] },
            ),
            "同 rev 从无团队升级为有团队必须能补发",
        )
    }

    // ---------------------------------------------------------------- 双节点（合并主权端到端）

    /** 成员宿主保存团队 → 群主端 apply（依赖 §5.2 的合并主权分支，端到端走真协议）。 */
    @Test
    fun aMemberHostDeclaresATeamAndTheOwnerAppliesIt() = runBlocking {
        val (owner, member, groupId) = twoNodeGroup()
        try {
            val memberSelf = member.state.value.selfNodeId
            // 成员宿主先声明自己的席位（工作区授权 —— 以前这条路被收端拒收，§5.2 顺带修的存量缺陷）。
            member.recordLocalWorkspace(
                wsId = "ws-member", wsTitle = "member box", wsPath = null,
                agentKey = null, agentKind = "dsh", online = true,
            )
            // 入驻（D6）：席位随群落库（不再经「勾选 wsIds」的旧保存流）。
            member.handleAddGroupAgent(groupId, "dsh", null)
            awaitUntil("群主收到成员的席位声明") {
                owner.state.value.settings.groups.firstOrNull { it.groupId == groupId }
                    ?.virtualAgents.orEmpty().any { it.hostNodeId == memberSelf && it.agentId == "dsh" }
            }
            // 成员宿主保存团队编制 → 群主端 apply。
            member.handleSetGroupTeamDeclaration(
                groupId, "dsh",
                listOf(TeamMemberDraft("m-coder", "coder", "编码"), TeamMemberDraft("m-reviewer", "reviewer", "审查")),
            )
            awaitUntil("群主收到团队编制") {
                val seat = owner.state.value.settings.groups.firstOrNull { it.groupId == groupId }
                    ?.virtualAgents.orEmpty().firstOrNull { it.hostNodeId == memberSelf && it.agentId == "dsh" }
                seat != null && seat.entityType == EntityTypes.TEAM && seat.teamMembers.size == 2
            }
            val seen = owner.state.value.settings.groups.first { it.groupId == groupId }
                .virtualAgents.orEmpty().first { it.hostNodeId == memberSelf && it.agentId == "dsh" }
            assertEquals(listOf("coder", "reviewer"), seen.teamMembers.map { it.name })
        } finally {
            owner.stop()
            member.stop()
        }
    }

    /** 编辑者动**别人宿主**的声明：对端拒（收端权限矩阵不回归）。 */
    @Test
    fun aMemberTouchingAnotherHostsDeclarationIsRejectedByThePeer() = runBlocking {
        val (owner, member, groupId) = twoNodeGroup()
        try {
            val ownerSelf = owner.state.value.selfNodeId
            // 群主先声明自己的席位，成员据此拼一份"动过它"的 rev+1。
            owner.recordLocalWorkspace(
                wsId = "ws-owner", wsTitle = "owner box", wsPath = null,
                agentKey = null, agentKind = "dsh", online = true,
            )
            // 入驻（D6）：席位随群落库。
            owner.handleAddGroupAgent(groupId, "dsh", null)
            awaitUntil("成员收到群主的席位声明") {
                member.state.value.settings.groups.firstOrNull { it.groupId == groupId }
                    ?.virtualAgents.orEmpty().any { it.hostNodeId == ownerSelf && it.agentId == "dsh" }
            }
            val localAtMember = member.state.value.settings.groups.first { it.groupId == groupId }
            val tampered = localAtMember.copy(
                rev = localAtMember.rev + 1,
                updatedBy = member.state.value.selfNodeId,
                updatedTs = com.kapp.tenon.core.nowMillis(),
                virtualAgents = localAtMember.virtualAgents.orEmpty().map {
                    if (it.hostNodeId == ownerSelf) it.copy(name = "冒名顶替") else it
                },
            )
            member.groupsCtl.handleOnGroupInfo(
                makeEnvelope(com.kapp.tenon.core.protocol.MsgTypes.GROUP, member.state.value.selfNodeId, com.kapp.tenon.core.protocol.Codec.groupInfoPayload(tampered)),
                tampered,
            )
            // 成员本地的 rev 没被接受（拒收后 noteRequested 只记游标）——本地元数据仍是旧 rev。
            assertEquals(
                localAtMember.rev,
                member.state.value.settings.groups.first { it.groupId == groupId }.rev,
                "动别人声明的 rev+1 必须被拒收（member-may-only-invite / 自声明主权只覆盖自己）",
            )
        } finally {
            owner.stop()
            member.stop()
        }
    }

    // ---------------------------------------------------------------- 装置

    /** 起两个真节点：群主建群拉成员，成员在线。返回 groupId。 */
    private suspend fun twoNodeGroup(): Triple<RelayController, RelayController, String> {
        val portOwner = TestPorts.freeUdpBase()
        val portMember = TestPorts.freeUdpBase()
        val owner = node(portOwner, portOwner + 1)
        val member = node(portMember, portMember + 1)
        owner.start()
        member.start()
        awaitUntil("群主起来") { owner.state.value.network.phase.name == "Running" }
        awaitUntil("成员起来") { member.state.value.network.phase.name == "Running" }
        val selfOwner = owner.state.value.selfNodeId
        val selfMember = member.state.value.selfNodeId
        member.addManualPeer("127.0.0.1", portOwner.toString())
        owner.addManualPeer("127.0.0.1", portMember.toString())
        awaitUntil("互相看见") {
            owner.state.value.peers.any { it.nodeId == selfMember } &&
                member.state.value.peers.any { it.nodeId == selfOwner }
        }
        val groupId = assertNotNull(owner.createGroup("团队仿真群", listOf(selfMember)), "建群必须成功")
        awaitUntil("成员收到群元数据") { member.state.value.settings.groups.any { it.groupId == groupId } }
        return Triple(owner, member, groupId)
    }
}
