// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import com.kapp.tenon.core.protocol.DispatchPreflight
import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.model.WorkspaceUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 项目目录授权与入驻面板（`SetGroupAgentWorkspacesDialog`，项目目录决策稿 §4.1/§4.2）
 * 的判据。规格的硬要求，各对应一次「面板说了谎」的现场：
 *
 * 1. **仅宿主可编辑**：入驻/去暴露/移除/设指针是机主主权 —— 非宿主（无目录端口）
 *    只读，动作按钮一律不画；
 * 2. **入驻清单按本机 agent 去**：行来自本机工作区台账（`resolveVirtualAgentKey`
 *    去重），对上本机席位；同一 agent 多工作区宣告 = 一行（D6）；
 * 3. **状态三态**：未入驻 / 已解析（席位 + 投影非空）/ 不可执行（席位 + 投影空）；
 *    目录项的判据口径单源 [DispatchPreflight.evaluate]（§4.3 preflight 四问）；
 * 4. **不编名字**：已入驻行用席位名（@ token 不漂），未入驻退 agentKind，再退身份键。
 */
class GroupAgentWorkspacesPanelTest {

    private val self = "self-node"
    private val foreign = "foreign-node"

    private fun workspace(
        wsId: String,
        agentId: String? = "dsh",
        kind: String? = "DSH",
        title: String? = null,
        path: String? = null,
    ) = WorkspaceUi(
        peerId = self,
        agentKey = agentId,
        agentId = agentId,
        wsId = wsId,
        wsTitle = title,
        wsPath = path,
        agentKind = kind,
        online = true,
        sessionCount = null,
        at = 0L,
    )

    private fun member(
        host: String,
        agentId: String,
        name: String,
        defaultWsId: String? = null,
        teamMembers: List<TeamMemberInfo> = emptyList(),
    ) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = host,
        name = name,
        entityType = if (teamMembers.isEmpty()) EntityTypes.SINGLE else EntityTypes.TEAM,
        teamMembers = teamMembers,
        allowedWorkspaces = emptyList(),
        defaultWsId = defaultWsId,
    )

    // ── 入驻清单的行集 ───────────────────────────────────────────────────

    @Test
    fun stationRowsComeFromLocalWorkspacesDedupedByAgentKey() {
        val rows = groupAgentStationRows(
            localWorkspaces = listOf(
                workspace("ws-a", agentId = "dsh", kind = "DSH"),
                // 同一个 agent 的第二个工作区宣告 = 一行（不是两个可入驻对象）。
                workspace("ws-b", agentId = "dsh", kind = "DSH"),
                workspace("ws-c", agentId = null, kind = "opencode"),
            ),
            declaredAgents = listOf(
                member(host = self, agentId = "dsh", name = "dsh-coder", defaultWsId = "ws-a"),
            ),
            selfNodeId = self,
        )
        assertEquals(listOf("dsh", "opencode"), rows.map { it.agentId }, "按 resolveVirtualAgentKey 去重")
        assertEquals("dsh-coder", rows[0].displayToken, "已入驻行显示席位名（@ token 不漂）")
        assertEquals("opencode", rows[1].displayToken, "未入驻行退 agentKind，不编名字")
        assertTrue(rows[0].stationed)
        assertTrue(rows[0].resolved, "席位 + defaultWsId 非空 = 已解析")
        assertFalse(rows[1].stationed)
    }

    @Test
    fun seatWithoutResolvedPointerIsUnexposedNotResolved() {
        val rows = groupAgentStationRows(
            localWorkspaces = listOf(workspace("ws-a", agentId = "dsh")),
            declaredAgents = listOf(
                member(host = self, agentId = "dsh", name = "dsh-assistant", defaultWsId = null),
            ),
            selfNodeId = self,
        )
        val row = rows.single()
        assertTrue(row.stationed)
        assertFalse(row.resolved, "席位在、投影空 = 不可执行（未暴露），不是已解析")
        assertEquals(SeatDirectoryStatus.UNEXPOSED, seatDirectoryStatusOf(row.seat))
    }

    // ── 目录状态口径（preflight 四问的「目录」项，单源）─────────────────

    @Test
    fun directoryStatusFollowsThePreflightAnswer() {
        // 未入驻不进 preflight（还没有执行点可言）。
        assertEquals(SeatDirectoryStatus.NOT_STATIONED, seatDirectoryStatusOf(null))
        assertEquals(
            SeatDirectoryStatus.RESOLVED,
            seatDirectoryStatusOf(member(host = self, agentId = "dsh", name = "dsh", defaultWsId = "ws-a")),
        )
        assertEquals(
            SeatDirectoryStatus.UNEXPOSED,
            seatDirectoryStatusOf(member(host = self, agentId = "dsh", name = "dsh", defaultWsId = null)),
        )
        // 判据确实走的 DispatchPreflight：把解析答案直接问一遍，两处必须同答。
        val answer = DispatchPreflight.evaluate(
            hostOnline = true,
            agentPresent = true,
            directoryResolved = false,
        ).first { it.key == DispatchPreflight.Key.DIRECTORY_RESOLVED }
        assertFalse(answer.ok, "未暴露 = 目录一问不 ok（与 seatDirectoryStatusOf 的 UNEXPOSED 同答）")
    }

    // ── 只读语义（非宿主不见动作）───────────────────────────────────────

    @Test
    fun panelIsEditableOnlyForTheHostWithAnActionChannel() {
        // 真机现场（M5 的同款缺陷形状）：明说只读又给保存入口，比不说更糟。
        // 判据：可编辑 = 有目录端口 **且** 有入驻动作通路，缺一不可。
        assertFalse(groupAuthEditable(hasDirectoryPort = false, hasActionChannel = true), "非宿主（手机）只读")
        assertFalse(groupAuthEditable(hasDirectoryPort = true, hasActionChannel = false), "预览/截图夹具只读")
        assertTrue(groupAuthEditable(hasDirectoryPort = true, hasActionChannel = true))
    }

    @Test
    fun foreignSeatsStayOutOfTheStationingList() {
        val rows = groupAgentStationRows(
            localWorkspaces = listOf(workspace("ws-a", agentId = "dsh")),
            declaredAgents = listOf(
                member(host = self, agentId = "dsh", name = "dsh-coder", defaultWsId = "ws-a"),
                member(host = foreign, agentId = "gemini", name = "gemini-lead", defaultWsId = "ws-z"),
            ),
            selfNodeId = self,
        )
        assertEquals(listOf("dsh"), rows.map { it.agentId }, "入驻清单只收本机 agent（外来席位单独只读展示）")
        assertEquals(self, rows.single().seat?.hostNodeId, "对上的席位必须是本机席位")
        assertNull(
            rows.firstOrNull { it.agentId == "gemini" },
            "外来席位不进入驻清单（不可替别人声明/撤回）",
        )
    }
}
