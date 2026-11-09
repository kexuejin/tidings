// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 「宿主自声明主权」合并分支（`GroupMerge.decide`，方案 §5.2）的测试矩阵。
 *
 * 收端视角的单测：`local` 是接收方落库的那份，`incoming` 是成员宿主（普通成员）
 * 广播来的 rev+1。规则一共四条 —— own 增/删/改通过；foreign 动不得；其余字段
 * 一致才放行；null 与空列表等价、顺序不敏感。
 *
 * 兼容性事实（如实钉住）：旧版本节点没有这条分支，会把成员发起的 rev+1 拒掉 ——
 * 那是方案 §5.3 记录的混合版本窗口，不是本测试要修的对象。
 */
class GroupMergeSelfDeclarationTest {

    private val owner = "node-owner"
    private val member = "node-member"
    private val peer = "node-peer"

    /** 本地：群主建的群，成员在列。 */
    private fun localMeta(
        virtualAgents: List<VirtualAgentMember>? = null,
        name: String = "项目研发群",
    ) = GroupMeta(
        groupId = "g1",
        name = name,
        members = listOf(owner, member, peer),
        rev = 7,
        updatedBy = owner,
        updatedTs = 1_000L,
        ownerId = owner,
        adminIds = emptyList(),
        virtualAgents = virtualAgents,
    )

    /** 成员宿主发起的 rev+1。 */
    private fun incoming(
        base: GroupMeta,
        virtualAgents: List<VirtualAgentMember>?,
        actor: String = member,
        ts: Long = 2_000L,
    ) = base.copy(rev = base.rev + 1, updatedBy = actor, updatedTs = ts, virtualAgents = virtualAgents)

    private fun ownSeat(
        agentId: String = "dsh",
        wsId: String? = "ws-1",
        name: String = "dsh-teahouse-mobile",
        entityType: String = EntityTypes.SINGLE,
        teamMembers: List<TeamMemberInfo> = emptyList(),
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = member,
        name = name,
        entityType = entityType,
        teamMembers = teamMembers,
        defaultWsId = wsId,
    )

    private fun foreignSeat(agentId: String = "codex") = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = peer,
        name = "$agentId-workspace",
        defaultWsId = "ws-peer",
    )

    // ------------------------------------------------------------ own 增 / 改 / 删

    @Test
    fun aMemberMayDeclareItsOwnSeatForTheFirstTime() {
        val decision = GroupMerge.decide(localMeta(), incoming(localMeta(), listOf(ownSeat())), member)
        val applied = assertIs<GroupMergeDecision.Apply>(decision)
        assertEquals(listOf(ownSeat()), applied.meta.virtualAgents)
    }

    @Test
    fun aMemberMayTurnItsOwnSeatIntoATeam() {
        val team = ownSeat(
            entityType = EntityTypes.TEAM,
            teamMembers = listOf(
                TeamMemberInfo(agentId = "coder", name = "coder", role = "编码"),
                TeamMemberInfo(agentId = "reviewer", name = "reviewer", role = "审查"),
            ),
        )
        val local = localMeta(listOf(ownSeat()))
        val decision = GroupMerge.decide(local, incoming(local, listOf(team)), member)
        val applied = assertIs<GroupMergeDecision.Apply>(decision)
        assertEquals(EntityTypes.TEAM, applied.meta.virtualAgents?.single()?.entityType)
        assertEquals(2, applied.meta.virtualAgents?.single()?.teamMembers?.size)
    }

    @Test
    fun aMemberMayWithdrawItsOwnSeat() {
        val local = localMeta(listOf(ownSeat(), foreignSeat()))
        val decision = GroupMerge.decide(local, incoming(local, listOf(foreignSeat())), member)
        assertIs<GroupMergeDecision.Apply>(decision, "撤销自己的声明（含团队关闭）必须能过")
    }

    // ------------------------------------------------------------ foreign 动不得

    @Test
    fun aMemberMayNotTouchAnotherHostsDeclaration() {
        // 增
        val base = localMeta(listOf(ownSeat()))
        val added = incoming(base, listOf(ownSeat(), foreignSeat()))
        assertEquals("member-may-only-invite", (GroupMerge.decide(base, added, member) as GroupMergeDecision.Reject).why)

        // 删
        val withForeign = localMeta(listOf(ownSeat(), foreignSeat()))
        val removed = incoming(withForeign, listOf(ownSeat()))
        assertEquals("member-may-only-invite", (GroupMerge.decide(withForeign, removed, member) as GroupMergeDecision.Reject).why)

        // 改（把外来声明的工作区换掉）
        val mutatedForeign = foreignSeat().copy(defaultWsId = "ws-hacked")
        val mutated = incoming(withForeign, listOf(ownSeat(), mutatedForeign))
        assertEquals("member-may-only-invite", (GroupMerge.decide(withForeign, mutated, member) as GroupMergeDecision.Reject).why)
    }

    @Test
    fun reorderingDeclarationsDoesNotLookLikeATouch() {
        val local = localMeta(listOf(ownSeat(), foreignSeat()))
        val reordered = incoming(local, listOf(foreignSeat(), ownSeat()))
        assertIs<GroupMergeDecision.Apply>(GroupMerge.decide(local, reordered, member), "顺序不敏感（多重集合比较）")
    }

    // ------------------------------------------------------------ 其余字段必须一致

    @Test
    fun aRedeclarationMayNotRideAlongWithAnyOtherChange() {
        val local = localMeta()
        // 改群名
        val renamed = incoming(local, listOf(ownSeat())).copy(name = "新名字")
        assertEquals("member-may-only-invite", (GroupMerge.decide(local, renamed, member) as GroupMergeDecision.Reject).why)
        // 改成员表（邀请路径之外的变化：踢人）
        val kicked = incoming(local, listOf(ownSeat())).copy(members = local.members - peer)
        assertEquals("member-may-only-invite", (GroupMerge.decide(local, kicked, member) as GroupMergeDecision.Reject).why)
        // 改角色矩阵
        val promoted = incoming(local, listOf(ownSeat())).copy(adminIds = listOf(member))
        assertEquals("roles-changed-by-non-owner", (GroupMerge.decide(local, promoted, member) as GroupMergeDecision.Reject).why)
    }

    /** 邀请新人与改写自己的声明**可以同帧**发生：两条授权路都成立。 */
    @Test
    fun invitingAndRedeclaringInOneFrameIsAccepted() {
        val local = localMeta()
        val invited = incoming(local, listOf(ownSeat()))
            .copy(members = local.members + "node-new")
        assertIs<GroupMergeDecision.Apply>(GroupMerge.decide(local, invited, member))
    }

    // ------------------------------------------------------------ null / 空归一

    @Test
    fun nullAndEmptyVirtualAgentsAreTheSameFact() {
        val local = localMeta(virtualAgents = emptyList())
        assertIs<GroupMergeDecision.Apply>(GroupMerge.decide(local, incoming(local, null), member), "空 → null 是归一，不是清空别人的东西")
        val localNull = localMeta(virtualAgents = null)
        assertIs<GroupMergeDecision.Apply>(GroupMerge.decide(localNull, incoming(localNull, emptyList()), member))
    }

    /** 本地缺省字段 vs 对端显式默认值：encodeDefaults=false 的规范形把它们编码成同一个串。 */
    @Test
    fun omittedDefaultsAndExplicitDefaultsEncodeTheSame() {
        val local = localMeta(virtualAgents = listOf(VirtualAgentMember(agentId = "dsh", hostNodeId = member, name = "dsh")))
        val explicit = ownSeat(name = "dsh").let { listOf(it) }
        assertIs<GroupMergeDecision.Apply>(
            GroupMerge.decide(local, incoming(local, explicit), member),
            "缺省 vs 显式 single/空组员/空 ws 是同一条声明（规范形归一）",
        )
    }

    // ------------------------------------------------------------ 既有规则不回归

    @Test
    fun ownerAndAdminPathsAreUntouched() {
        val local = localMeta(listOf(foreignSeat()))
        // 群主整份改写（含别人的声明）照常 apply。
        val ownerRewrite = incoming(local, listOf(foreignSeat(), ownSeat()), actor = owner)
        assertIs<GroupMergeDecision.Apply>(GroupMerge.decide(local, ownerRewrite, member))
        // 成员动**别人宿主**的声明：peer 的主权只覆盖 hostNodeId == peer 的那条；
        // 这条声明属于另一个宿主（node-third-host），peer 动它仍然走既有拒因。
        val thirdHostSeat = foreignSeat(agentId = "opencode").copy(hostNodeId = "node-third-host")
        val withThird = localMeta(listOf(ownSeat(), thirdHostSeat))
        val foreignOnly = incoming(withThird, listOf(ownSeat(), thirdHostSeat.copy(name = "别人起的名")), actor = peer)
        assertEquals(
            "member-may-only-invite",
            (GroupMerge.decide(withThird, foreignOnly, peer) as GroupMergeDecision.Reject).why,
        )
    }

    @Test
    fun staleRevIsStillRejectedBeforeTheSovereigntyCheck() {
        // 本地必须**已有** virtualAgents：同 rev 的补字段例外（本地缺、对端有）先于
        // rev 判定，会让「同 rev + 首次声明」走 Apply —— 那是另一条路径，不是本用例。
        val local = localMeta(listOf(ownSeat()))
        val stale = incoming(local, listOf(ownSeat().copy(name = "改个名")), actor = member).copy(rev = local.rev)
        assertEquals("stale-rev", (GroupMerge.decide(local, stale, member) as GroupMergeDecision.Reject).why)
    }

    /** 管理身份（creatorId / adminSecretHash）动不得 —— 主权分支不绕过 sameManagementIdentity。 */
    @Test
    fun managementIdentityIsStillUntouchable() {
        val local = localMeta()
        val tampered = incoming(local, listOf(ownSeat())).copy(creatorId = "node-impersonator")
        assertEquals("management-identity", (GroupMerge.decide(local, tampered, member) as GroupMergeDecision.Reject).why)
    }

    @Test
    fun aNonMemberActorIsStillRejected() {
        val local = localMeta()
        val outsider = incoming(local, listOf(ownSeat()), actor = "node-outsider")
        assertEquals("actor-not-a-member", (GroupMerge.decide(local, outsider, member) as GroupMergeDecision.Reject).why)
    }

    /** 反向探针的对照面：同帧只动自己的声明时，删掉主权分支应得的拒因就是 member-may-only-invite。 */
    @Test
    fun theRejectionPathIsTheExistingInviteRule() {
        val local = localMeta()
        val decision = GroupMerge.decide(local, incoming(local, listOf(ownSeat())), member)
        assertTrue(
            decision is GroupMergeDecision.Apply,
            "这条用例与 aMemberMayDeclareItsOwnSeatForTheFirstTime 互为镜像：反向验证时" +
                "注释掉 memberMayRedeclareSelf 分支，它应当变成 member-may-only-invite 红。",
        )
    }
}
