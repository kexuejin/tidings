// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import com.kapp.tidings.core.protocol.GroupWorkspaceInfo
import com.kapp.tidings.core.protocol.TeamMemberInfo
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.domain.model.WorkspaceUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 群设置授权面板（`SetGroupAgentWorkspacesDialog`，方案 §5.1，M3 交付 3）的判据。
 *
 * 规格的四条硬要求，各对应一次「面板说了谎」的现场：
 * 1. **仅宿主可编辑**：授权决定权在机主（§5.1）—— 非宿主只读；
 * 2. **非宿主不见路径**：路径不进协议（M1-T3 收口），面板是非宿主能看到的唯一
 *    「授权」面 —— 它若把宿主路径印出来，隐私收口就白做了；
 * 3. **保存一次成像**：勾选集合就是最终集合，一次 rev+1（M1-T4 的
 *    `handleSetGroupAgentWorkspaces` 契约），不做逐条 toggle 的中间态广播；
 * 4. **读侧不编**：非宿主的工作区行来自声明投影（`allowedWorkspaces` /
 *    `defaultWsId`），本地没有的标题退 wsId 本身，绝不现编一个名字。
 */
class GroupAgentWorkspacesPanelTest {

    private val self = "self-node"
    private val foreign = "foreign-node"

    private fun workspace(
        wsId: String,
        title: String? = null,
        path: String? = null,
    ) = WorkspaceUi(
        peerId = self,
        agentKey = "dsh",
        wsId = wsId,
        wsTitle = title,
        wsPath = path,
        agentKind = "DSH",
        online = true,
        sessionCount = null,
        at = 0L,
    )

    private fun member(
        host: String,
        name: String,
        defaultWsId: String? = null,
        allowed: List<GroupWorkspaceInfo> = emptyList(),
        teamMembers: List<TeamMemberInfo> = emptyList(),
    ) = VirtualAgentMember(
        agentId = name,
        hostNodeId = host,
        name = name,
        entityType = if (teamMembers.isEmpty()) "single" else "team",
        teamMembers = teamMembers,
        allowedWorkspaces = allowed,
        defaultWsId = defaultWsId,
    )

    // ── 行构造：宿主视角 ─────────────────────────────────────────────────

    @Test
    fun hostRowsCarryPathsAndAuthorization() {
        val rows = groupAuthWorkspaceRows(
            localWorkspaces = listOf(
                workspace("ws-a", title = "Teahouse Mobile", path = "/srv/teahouse-mobile"),
                workspace("ws-b", title = "Teahouse Backend", path = "/srv/teahouse-backend"),
            ),
            declaredAgents = emptyList(),
            exposedWsIds = setOf("ws-a"),
            defaultWsId = "ws-a",
        )
        assertEquals(listOf("ws-a", "ws-b"), rows.map { it.wsId })
        assertEquals("/srv/teahouse-mobile", rows[0].path, "宿主自己的路径：面板给机主看（不进协议）")
        assertTrue(rows[0].authorized)
        assertTrue(rows[0].isDefault, "群默认落在声明里 defaultWsId 指的那一行")
        assertTrue(!rows[1].authorized)
        assertTrue(!rows[1].isDefault)
    }

    @Test
    fun hostRowsFallBackTitleToWsIdAndNeverInventOne() {
        val rows = groupAuthWorkspaceRows(
            localWorkspaces = listOf(workspace("ws-e9b18063", title = null, path = "/srv/x")),
            declaredAgents = emptyList(),
            exposedWsIds = emptySet(),
            defaultWsId = null,
        )
        assertEquals("ws-e9b18063", rows.single().title, "没有标题就退 wsId，不现编一个")
    }

    // ── 行构造：非宿主（只读）视角 ───────────────────────────────────────

    @Test
    fun nonHostRowsComeFromDeclarationsWithoutPaths() {
        val rows = groupAuthWorkspaceRows(
            localWorkspaces = emptyList(),
            declaredAgents = listOf(
                member(
                    host = foreign,
                    name = "dev-lead",
                    defaultWsId = "ws-a",
                    allowed = listOf(
                        GroupWorkspaceInfo(wsId = "ws-a", title = "Teahouse Mobile"),
                        GroupWorkspaceInfo(wsId = "ws-b", title = "Teahouse Backend"),
                    ),
                ),
            ),
            exposedWsIds = setOf("ws-a"),
            defaultWsId = "ws-a",
        )
        assertEquals(listOf("ws-a", "ws-b"), rows.map { it.wsId })
        assertEquals("Teahouse Mobile", rows[0].title, "标题来自声明（随 group.info 广播）")
        assertNull(rows[0].path, "非宿主不画路径：路径本来就不进协议")
        assertNull(rows[1].path)
        assertTrue(rows[0].authorized)
        assertTrue(rows[1].authorized, "声明里 allowedWorkspaces 的条目都是授权过的")
    }

    @Test
    fun nonHostRowsCoverTheDefaultWsEvenWhenTheWhitelistIsEmpty() {
        // M1 模型里 allowedWorkspaces 常为空、只有 defaultWsId：读侧把 defaultWsId
        // 也算进白名单 —— 否则非宿主看到的授权面板是空的，而胶囊却明明能选工作区。
        val rows = groupAuthWorkspaceRows(
            localWorkspaces = emptyList(),
            declaredAgents = listOf(member(host = foreign, name = "dev-lead", defaultWsId = "ws-a")),
            exposedWsIds = emptySet(),
            defaultWsId = null,
        )
        assertEquals(listOf("ws-a"), rows.map { it.wsId })
        assertEquals("ws-a", rows.single().title, "声明没带标题（连本机工作区表也没有）：退 wsId")
        assertTrue(rows.single().authorized, "defaultWsId 本身就是一条授权事实")
    }

    @Test
    fun hostRowsUniteLocalWorkspacesWithForeignDeclarations() {
        // 宿主打开面板：本机工作区可编辑；别的设备声明进来的行也要看得见（只读），
        // 否则「群里还有谁声明了什么」在这台机器上无迹可寻。
        val rows = groupAuthWorkspaceRows(
            localWorkspaces = listOf(workspace("ws-a", title = "Alpha", path = "/srv/alpha")),
            declaredAgents = listOf(
                member(host = foreign, name = "remote", defaultWsId = "ws-z"),
            ),
            exposedWsIds = setOf("ws-a", "ws-z"),
            defaultWsId = "ws-a",
        )
        assertEquals(listOf("ws-a", "ws-z"), rows.map { it.wsId })
        assertNull(rows[1].path, "外来声明的路径本机就没有")
        assertTrue(rows[1].authorized)
        assertTrue(!rows[1].fromLocalMachine, "外来行不可勾：保存集合只装本机声明")
        assertTrue(rows[0].fromLocalMachine)
    }

    // ── agent 行 ─────────────────────────────────────────────────────────

    @Test
    fun agentRowsShowTeamShapeAndMembership() {
        val rows = groupAuthAgentRows(
            declaredAgents = listOf(
                member(host = self, name = "dev-lead", defaultWsId = "ws-a"),
                member(
                    host = foreign,
                    name = "gpu-team",
                    defaultWsId = "ws-b",
                    allowed = listOf(GroupWorkspaceInfo("ws-b", "Backend")),
                    teamMembers = listOf(
                        TeamMemberInfo("coder", "coder", "FullStack"),
                        TeamMemberInfo("tester", "tester", "QA"),
                        TeamMemberInfo("reviewer", "reviewer", "Review"),
                    ),
                ),
            ),
            selfNodeId = self,
            wsTitleOf = { wsId -> if (wsId == "ws-a") "Alpha" else null },
        )
        assertEquals(listOf("dev-lead", "gpu-team"), rows.map { it.name })
        assertTrue(!rows[0].isTeam)
        assertTrue(rows[0].hostIsSelf)
        assertEquals("Alpha", rows[0].defaultWsTitle)
        assertTrue(rows[1].isTeam, "teamMembers 非空就是团队（entityType 只是声明，组员清单是事实）")
        assertEquals(3, rows[1].teamMemberCount)
        assertTrue(!rows[1].hostIsSelf)
        assertEquals("Backend", rows[1].defaultWsTitle, "defaultWsId 在声明白名单里时标题随声明走")
    }

    @Test
    fun agentRowWithoutWorkspaceTitleFallsBackToNothing() {
        val rows = groupAuthAgentRows(
            declaredAgents = listOf(member(host = self, name = "solo", defaultWsId = "ws-unknown")),
            selfNodeId = self,
            wsTitleOf = { null },
        )
        assertNull(rows.single().defaultWsTitle, "查不到标题就不显示，不把 wsId 当名字编")
    }

    // ── 保存契约 ─────────────────────────────────────────────────────────

    @Test
    fun initialCheckedSetMirrorsTheCurrentAuthorization() {
        // 面板打开时的勾选 = 本机当前声明（exposedWsIds）。保存 = 把勾选集合整体
        // 交给 handleSetGroupAgentWorkspaces（一次成像），不做差集运算 ——
        // 差集属于域层：调用方交最终集合，控制器一次 rev+1、一次广播。
        val rows = groupAuthWorkspaceRows(
            localWorkspaces = listOf(
                workspace("ws-a", title = "Alpha"),
                workspace("ws-b", title = "Beta"),
            ),
            declaredAgents = emptyList(),
            exposedWsIds = setOf("ws-b"),
            defaultWsId = null,
        )
        assertEquals(setOf("ws-b"), initialCheckedWsIds(rows))
    }

    @Test
    fun emptySelectionIsAValidSave() {
        // 全不勾 = 撤掉本机全部声明（M1-T4 测试钉过域层这一档）：面板必须允许
        // 「保存一个空集合」，而不是把保存按钮禁掉 —— 撤回是机主主权。
        assertEquals(emptySet(), initialCheckedWsIds(emptyList()))
    }

    @Test
    fun groupDefaultFollowsTheFirstDeclaration() {
        // 群默认的读侧判据：声明表里第一条带 defaultWsId 的声明（与 workspaceOfGroup
        // 的解析方向一致 —— 精确 id 匹配，一个字符都不猜）。
        assertEquals(
            "ws-b",
            groupDefaultWsIdOf(
                listOf(
                    member(host = self, name = "a", defaultWsId = null),
                    member(host = foreign, name = "b", defaultWsId = "ws-b"),
                ),
            ),
        )
        assertNull(groupDefaultWsIdOf(listOf(member(host = self, name = "a"))), "没有任何声明带默认 ⇒ 没有群默认")
        assertNull(groupDefaultWsIdOf(emptyList()))
    }
}
