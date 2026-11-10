// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TeamRosterDialog 的**纯函数**钉子（对话框状态机的可测部分）。
 *
 * 最要紧的是编辑保全契约：既有组员的 `agentId` 打开→保存一个来回必须原样 ——
 * 重算 id 会让 v2 执行档索引失联（方案 §3.2）。
 */
class TeamRosterDialogFormTest {

    // unique 是**精确匹配**去重（AgentMemberName 的既有口径）：撞 "reviewer" 就让位；
    // "reviewer-other" 与 "reviewer" 可共存（@ 匹配按长 token 优先，两者不歧义）。
    private val taken = setOf("dev-lead", "remote-agent", "reviewer")

    private val presetRoles = listOf("编码", "审查", "测试")

    /** 既有组员打开：agentId / 名字 / 角色原样沿用，isNew = false。 */
    @Test
    fun existingMembersArePreservedVerbatim() {
        val seat = VirtualAgentMember(
            agentId = "dsh",
            hostNodeId = "self",
            name = "dsh-ws-a",
            entityType = EntityTypes.TEAM,
            teamMembers = listOf(
                TeamMemberInfo(agentId = "m-coder-9f2a", name = "coder", role = "编码"),
                TeamMemberInfo(agentId = "m-rev-1b3c", name = "reviewer", role = "审查"),
            ),
            defaultWsId = "ws-a",
        )
        val drafts = initialRosterDrafts(seat, taken, listOf("编码", "审查", "测试"))
        assertEquals(
            listOf(
                Triple("m-coder-9f2a", "coder", "编码"),
                Triple("m-rev-1b3c", "reviewer", "审查"),
            ),
            drafts.map { Triple(it.agentId, it.name, it.role) },
            "编辑保全：加载既有团队必须严格沿用原 agentId（改名绝不重算）",
        )
        assertTrue(drafts.none { it.isNew })
    }

    /** 没编过队（单兵）打开：空名册 —— 预设在「开启开关」那一刻才给。 */
    @Test
    fun anUnteamedSeatOpensWithAnEmptyRoster() {
        val seat = VirtualAgentMember(agentId = "dsh", hostNodeId = "self", name = "dsh-ws-a", defaultWsId = "ws-a")
        assertTrue(initialRosterDrafts(seat, taken, presetRoles).isEmpty())
        assertNull(initialRosterDrafts(null, taken, presetRoles).takeIf { it.isNotEmpty() })
    }

    /** 声明是团队但组员清单为空（手改帧/旧数据）：给预设，别让用户对着空白猜。 */
    @Test
    fun aDeclaredTeamWithEmptyRosterGetsThePreset() {
        val seat = VirtualAgentMember(
            agentId = "dsh", hostNodeId = "self", name = "dsh-ws-a",
            entityType = EntityTypes.TEAM, defaultWsId = "ws-a",
        )
        assertEquals(3, initialRosterDrafts(seat, taken, presetRoles).size)
    }

    /** 经典研发组预设：coder/reviewer/tester（编码/审查/测试）；撞群内 token 自动让位。 */
    @Test
    fun thePresetAvoidsExistingTokens() {
        val drafts = teamRosterPreset(taken, presetRoles)
        assertEquals(listOf("coder", "reviewer-2", "tester"), drafts.map { it.name }, "reviewer 已在池里 → reviewer 让位为 reviewer-2")
        assertEquals(presetRoles, drafts.map { it.role })
        assertTrue(drafts.all { it.isNew })
        // id 形状：m-<slug>-<4 位>；且三条互不相同。
        assertEquals(3, drafts.map { it.agentId }.toSet().size)
        assertTrue(drafts.all { it.agentId.startsWith("m-") })
    }

    /** 新建行：id 现在就定死，名字 slug 化并与既有名册错开。 */
    @Test
    fun aNewDraftGetsAStableIdAndAUniqueName() {
        val existing = listOf(TeamMemberDraft("m-coder-1", "coder", "编码", isNew = true))
        val draft = newDraft(name = "Coder", role = "架构", takenTokens = taken, existing = existing)
        assertEquals("coder-2", draft.name, "slug 归一 + 与既有名册错开")
        assertTrue(draft.agentId.startsWith("m-coder-2-"), "id 由当时的名字生成一次")
        assertTrue(draft.isNew)
    }

    /** 名字留空的新行：id 用 member 兜底，仍是合法 slug 形状。 */
    @Test
    fun anEmptyNewDraftStillGetsAWellFormedId() {
        val draft = newDraft(name = "", role = "", takenTokens = emptySet(), existing = emptyList())
        assertTrue(draft.agentId.startsWith("m-member-"))
    }

    /** 快捷角色：五个分工 chip，名字自动 slug 化并错开既有 token。 */
    @Test
    fun quickRolesProduceSluggedDraftsWithChineseRoleLabels() {
        assertEquals(5, quickRoles.size)
        val drafts = quickRoles.fold(emptyList<TeamMemberDraft>()) { acc, quick ->
            acc + quick.toDraft(roleLabel = quick.nameBase, takenTokens = taken, existing = acc)
        }
        assertEquals(listOf("architect", "frontend", "backend", "reviewer-2", "security"), drafts.map { it.name }, "审查撞池里的 reviewer → reviewer-2")
        assertEquals(
            listOf("architect", "frontend", "backend", "reviewer", "security"),
            drafts.map { it.role },
            "role = 调用方解析出的标签（生产里是 strings.xml 的中文，这里用占位；中文不进源码）",
        )
    }

    /** 团队徽标聚合：任一席位 isTeam 即显示，N = 那条团队的组员数；非团队返回 null。 */
    @Test
    fun theTeamBadgeAggregatesOverSeats() {
        val teamSeat = VirtualAgentMember(
            agentId = "dsh", hostNodeId = "self", name = "dsh-ws-a",
            entityType = EntityTypes.TEAM,
            teamMembers = listOf(TeamMemberInfo("c", "coder", "编码"), TeamMemberInfo("r", "reviewer", "审查")),
            defaultWsId = "ws-a",
        )
        val singleSeat = VirtualAgentMember(agentId = "dsh", hostNodeId = "self", name = "dsh-ws-b", defaultWsId = "ws-b")
        assertEquals(2, teamBadgeCountOf(listOf(singleSeat, teamSeat)))
        assertNull(teamBadgeCountOf(listOf(singleSeat)), "单兵不画徽标")
    }

    // ---------------------------------------------------------------- 席位选中（D4）

    /**
     * 席位选中按 **agentId** 定位（D4：席位身份 = (groupId, hostNodeId, agentId)）。
     * `defaultWsId` 不再参与 —— 未暴露项目目录的席位同样可选中、可编团队。
     */
    @Test
    fun seatSelectionLocatesByAgentIdAndIncludesUnexposedSeats() {
        val exposed = VirtualAgentMember(agentId = "dsh", hostNodeId = "self", name = "dsh-coder", defaultWsId = "ws-a")
        val unexposed = VirtualAgentMember(agentId = "codex", hostNodeId = "self", name = "codex-assistant", defaultWsId = null)
        val seats = listOf(exposed, unexposed)
        assertEquals("dsh", initialSelectedSeatOf(seats, "dsh")?.agentId)
        assertEquals(
            "codex",
            initialSelectedSeatOf(seats, "codex")?.agentId,
            "未暴露目录的席位同样可选中（defaultWsId 不再是身份的一半）",
        )
        assertEquals(
            "dsh",
            initialSelectedSeatOf(seats, "DSH")?.agentId,
            "agentId 大小写不敏感（声明侧通用口径）",
        )
        assertEquals(
            "dsh",
            initialSelectedSeatOf(seats, null)?.agentId,
            "没有初值 ⇒ 取第一条，不猜第二条",
        )
        assertNull(initialSelectedSeatOf(emptyList(), "dsh"), "空席位表 ⇒ 引导态")
        // 选中态定位与初始选中同一套匹配判据（两个入口不许各答各的）。
        assertEquals("codex", selectedSeatOf(seats, "codex")?.agentId)
        assertNull(selectedSeatOf(seats, "gone"))
    }
}
