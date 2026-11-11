// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.model.AgentArtifactKey
import com.kapp.tenon.domain.model.AgentArtifactUi
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
 * 文件柜「智能体产物」段的分组投影（UX 规范 §2.5③ 的「只做视图」档）。
 *
 * 钉的是四条会静默出错的规则：**先截断再分组**（否则旧产物挤掉新产物）、
 * **组序 = 组内最新那件**、**看不见的会话里的产物不进来**、**群名只认席位绑定**。
 */
class CabinetArtifactsTest {

    private val self = "self-node"

    private fun artifact(
        peerId: String,
        sessionId: String,
        id: String,
        name: String,
        at: Long,
        fileId: String? = "file-$id",
    ) = AgentArtifactUi(
        artifactId = id,
        sessionId = sessionId,
        peerId = peerId,
        fileId = fileId,
        name = name,
        bytes = 2048L,
        mime = "text/html",
        at = at,
    )

    private fun session(
        peerId: String,
        sessionId: String,
        wsId: String? = null,
        wsTitle: String? = null,
        hostOrigin: String? = null,
    ) = AgentSessionUi(
        peerId = peerId,
        sessionId = sessionId,
        wsId = wsId,
        wsTitle = wsTitle,
        hostOrigin = hostOrigin,
    )

    private fun declared(peerId: String, agentKey: String, wsId: String, wsTitle: String) = WorkspaceUi(
        peerId = peerId,
        agentKey = agentKey,
        wsId = wsId,
        wsTitle = wsTitle,
        wsPath = null,
        agentKind = null,
        online = null,
        sessionCount = null,
        at = 0L,
    )

    private fun slice(
        sessions: List<AgentSessionUi> = emptyList(),
        artifacts: List<AgentArtifactUi> = emptyList(),
        workspaces: List<WorkspaceUi> = emptyList(),
    ) = AgentSlice(
        agentSessions = sessions.associate { AgentSessionKey(it.peerId, it.sessionId) to it },
        agentArtifacts = artifacts.associateBy { AgentArtifactKey(it.peerId, it.artifactId) },
        agentWorkspaces = workspaces.associate { WorkspaceKey(it.peerId, it.agentKey, it.wsId) to it },
    )

    private fun settings(groups: List<GroupMeta> = emptyList()) =
        SettingsSlice(settings = RelaySettings.firstRun().copy(nodeId = self, groups = groups))

    private fun seat(agentId: String, hostNodeId: String, name: String, defaultWsId: String) = VirtualAgentMember(
        agentId = agentId,
        hostNodeId = hostNodeId,
        name = name,
        entityType = EntityTypes.SINGLE,
        defaultWsId = defaultWsId,
    )

    private fun group(groupId: String, name: String, seats: List<VirtualAgentMember>) =
        GroupMeta.create(groupId, name, members = emptyList(), selfId = self, updatedTs = 1L)
            .copy(virtualAgents = seats)

    @Test
    fun groupsByProjectDirectoryWithNewestFirst() {
        val s = slice(
            sessions = listOf(
                session("host-1", "s-a", wsId = "ws-a", wsTitle = "甲目录"),
                session("host-1", "s-b", wsId = "ws-b", wsTitle = "乙目录"),
            ),
            artifacts = listOf(
                artifact("host-1", "s-a", "a1", "old.html", at = 100),
                artifact("host-1", "s-b", "a2", "newest.html", at = 300),
                artifact("host-1", "s-a", "a3", "mid.html", at = 200),
            ),
        )
        val groups = cabinetArtifactGroups(s, settings())
        assertEquals(listOf("ws-b", "ws-a"), groups.map { it.wsId }, "组序 = 组内最新那件产物的先后")
        assertEquals(listOf("newest.html"), groups[0].rows.map { it.name })
        assertEquals(listOf("mid.html", "old.html"), groups[1].rows.map { it.name }, "组内也按收到时刻倒序")
        assertEquals("甲目录", groups[1].wsTitle)
    }

    @Test
    fun limitAppliesBeforeGroupingSoNewestSurvive() {
        val s = slice(
            sessions = listOf(
                session("host-1", "s-a", wsId = "ws-a", wsTitle = "甲目录"),
                session("host-1", "s-b", wsId = "ws-b", wsTitle = "乙目录"),
            ),
            artifacts = listOf(
                artifact("host-1", "s-a", "a1", "oldest.html", at = 100),
                artifact("host-1", "s-a", "a2", "second.html", at = 200),
                artifact("host-1", "s-b", "a3", "newest.html", at = 300),
            ),
        )
        val groups = cabinetArtifactGroups(s, settings(), limit = 2)
        assertEquals(2, groups.sumOf { it.rows.size }, "上限按**全局**件数算")
        assertEquals(listOf("newest.html", "second.html"), groups.flatMap { it.rows }.map { it.name })
        assertTrue(groups.none { it.wsId == "ws-a" && it.rows.any { row -> row.name == "oldest.html" } }, "先截断后分组：最旧那件不该因为分组而幸存")
    }

    @Test
    fun artifactsFromInvisibleSessionsAreDropped() {
        val s = slice(
            sessions = listOf(
                session("host-1", "s-live", wsId = "ws-a", wsTitle = "甲目录"),
                // 子代理静默会话：`visibleInDsh` 之外 —— 它在卡片流里本来就不存在。
                session("host-1", "s-sub", wsId = "ws-a", hostOrigin = "subagent"),
            ),
            artifacts = listOf(
                artifact("host-1", "s-live", "a1", "shown.html", at = 100),
                artifact("host-1", "s-sub", "a2", "hidden.html", at = 200),
            ),
        )
        val names = cabinetArtifactGroups(s, settings()).flatMap { it.rows }.map { it.name }
        assertEquals(listOf("shown.html"), names, "看不见的会话里的产物不该冒到文件柜")
    }

    @Test
    fun artifactWithoutKnownSessionFallsIntoUnassignedGroup() {
        val s = slice(
            sessions = emptyList(),
            artifacts = listOf(artifact("host-1", "s-unknown", "a1", "orphan.md", at = 100)),
        )
        val groups = cabinetArtifactGroups(s, settings())
        val only = groups.single()
        assertNull(only.wsId, "会话帧还没到：归到未归属那一档，不丢也不猜目录")
        assertNull(only.wsTitle)
        assertEquals("orphan.md", only.rows.single().name)
    }

    @Test
    fun groupNameOnlyWhenTheDirectoryIsSeatBound() {
        val s = slice(
            sessions = listOf(
                session("host-1", "s-a", wsId = "ws-a", wsTitle = "甲目录"),
                session("host-1", "s-b", wsId = "ws-b", wsTitle = "乙目录"),
            ),
            artifacts = listOf(
                artifact("host-1", "s-a", "a1", "in-group.html", at = 200),
                artifact("host-1", "s-b", "a2", "loose.html", at = 100),
            ),
        )
        val groups = cabinetArtifactGroups(
            s,
            settings(groups = listOf(group("g-1", "协同群", listOf(seat("dsh", "host-1", "小助", "ws-a"))))),
        )
        assertEquals("协同群", groups.single { it.wsId == "ws-a" }.groupName, "绑定群走席位声明（groupBoundToWorkspace 单源）")
        assertNull(groups.single { it.wsId == "ws-b" }.groupName, "没绑群就不写群名，不编造归属")
    }

    @Test
    fun directoryTitleFallsBackToTheDeclaredWorkspace() {
        val s = slice(
            // 会话自报了 wsId 但没报名字；`agent-workspace` 宣告里有 —— 用宣告的那份。
            sessions = listOf(session("host-1", "s-a", wsId = "ws-a")),
            artifacts = listOf(artifact("host-1", "s-a", "a1", "x.html", at = 100)),
            workspaces = listOf(declared("host-1", "dsh", "ws-a", "宣告的目录名")),
        )
        assertEquals("宣告的目录名", cabinetArtifactGroups(s, settings()).single().wsTitle)
    }

    @Test
    fun hasFileFollowsTheWireFact() {
        val s = slice(
            sessions = listOf(session("host-1", "s-a", wsId = "ws-a", wsTitle = "甲目录")),
            artifacts = listOf(
                artifact("host-1", "s-a", "a1", "with-file.html", at = 200, fileId = "file-a1"),
                artifact("host-1", "s-a", "a2", "meta-only.md", at = 100, fileId = null),
            ),
        )
        val rows = cabinetArtifactGroups(s, settings()).single().rows
        assertTrue(rows.single { it.name == "with-file.html" }.hasFile, "带 fileId = 有本体")
        assertFalse(rows.single { it.name == "meta-only.md" }.hasFile, "没 fileId = 只有元数据，UI 必须如实说")
    }

    @Test
    fun emptySliceProducesNoGroups() {
        assertTrue(cabinetArtifactGroups(slice(), settings()).isEmpty(), "一件产物都没有时整段不画")
        assertTrue(cabinetArtifactGroups(slice(), settings(), limit = 0).isEmpty(), "limit=0 不该退化成无上限")
    }
}
