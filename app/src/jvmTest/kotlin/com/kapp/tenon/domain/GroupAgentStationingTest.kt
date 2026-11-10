// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupWorkspaceInfo
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 智能体入驻写路径的钉子（项目目录决策稿 D4/D5/D6/D14）：
 *
 * - **入驻不问目录**（D6）：`handleAddGroupAgent` 就地建席位并继承群项目目录的解析
 *   结果；解析不出 = 席位照常建立（`defaultWsId` 为 null），不猜；
 * - **席位身份 = (groupId, hostNodeId, agentId)**（D4）：同 agentId 再入驻沿用既有
 *   名字与团队字段（@ token 不漂）；换指针不改名；
 * - **移除席位**（撤回入驻）：只摘本机自己的声明，外来席位原样保留 —— 机主主权；
 * - **指针写路径**（D5）：设 / 清指针必须同步重建本机席位投影并广播（换目录 = 新会话）；
 *   手机端（无端口）拒绝写指针。
 *
 * 反向验证：把入驻的「沿用既有席位」退回「无条件新建」⇒ 团队字段用例红；
 * 把移除的 host 过滤去掉 ⇒ 外来席位用例红；把指针投影重建去掉 ⇒ 解析用例红。
 */
class GroupAgentStationingTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val peer = "11111111-1111-4111-8111-111111111111"

    /** 目录端口假件：目录台账 + 指针 + 可注入的解析结果（判据真身在各 handler）。 */
    private class FakeDirectoryPort : HostDirectoryPort {
        var directories: List<HostDirectoryUi> = emptyList()
        val pointers = mutableMapOf<String, String>()
        var resolved: String? = null

        override fun snapshot(): HostDirectorySnapshotUi = HostDirectorySnapshotUi(
            directories = directories,
            groupPointers = pointers.toMap(),
            whitelist = directories.map { WhitelistUi(it.dirId, readOnly = false) },
        )

        override fun register(path: String, displayName: String?): HostDirectoryUi? {
            if (path.isBlank()) return null
            val existing = directories.firstOrNull { it.path == path }
            if (existing != null) return existing
            val dir = HostDirectoryUi(
                dirId = "dir-" + (directories.size + 1),
                path = path,
                displayName = displayName ?: path.substringAfterLast('/'),
                gitRemote = null,
            )
            directories = directories + dir
            return dir
        }

        override fun setGroupPointer(groupId: String, dirId: String?): Boolean {
            if (dirId != null && directories.none { it.dirId == dirId }) return false
            if (dirId == null) pointers.remove(groupId) else pointers[groupId] = dirId
            return true
        }

        override fun setWhitelist(dirId: String, readOnly: Boolean, allowed: Boolean): Boolean = true

        /** 解析以指针为前提（与 DesktopHostDirectoryPort 的装配语义一致）：没设指针 ⇒ 未解析。 */
        override fun resolveForGroup(groupId: String, agentKey: String): String? =
            if (pointers.containsKey(groupId)) resolved else null
    }

    private class MemorySettingsStore(private val nodeId: String) : SettingsStore {
        private var current: RelaySettings? = RelaySettings(
            nodeId = nodeId,
            nick = "station-test",
            udpPort = 0,
            tcpPort = 0,
        )

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            current = settings
        }
    }

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(self),
        scope = scope,
        version = "relay-test",
    )

    /** 一条本机工作区行（agentId 由 agentKind 兜出，`resolveVirtualAgentKey` 口径）。 */
    private fun RelayController.givenLocalAgent(agentId: String, kind: String = "DSH") {
        recordLocalWorkspace(
            wsId = "ws-$agentId",
            wsTitle = "ws-$agentId 标题",
            wsPath = "/srv/$agentId",
            agentKey = agentId,
            agentKind = kind,
        )
    }

    private fun RelayController.groupWithPointer(port: FakeDirectoryPort): String {
        val dir = assertNotNull(port.register("/srv/proj", "proj"), "前提：目录已登记")
        val gid = assertNotNull(
            createGroup("协同群", listOf(peer)),
            "前提：群建出来了",
        )
        assertTrue(port.setGroupPointer(gid, dir.dirId), "前提：指针已写")
        port.pointers[gid] = dir.dirId
        return gid
    }

    // ---------------------------------------------------------------- 入驻（D6）

    /** 入驻不问目录：席位就地建立，名字 = kind + 职责（D13），执行点 = 群指针解析结果。 */
    @Test
    fun stationingDoesNotAskForADirectoryAndInheritsThePointer() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort().also { it.resolved = "ws-resolved" }
        controller.hostDirectoryPort = port
        controller.givenLocalAgent("dsh")
        val gid = controller.groupWithPointer(port)

        val result = controller.handleAddGroupAgent(gid, "dsh", "coder")
        val seat = assertNotNull(
            (result as? AddGroupAgentResult.Added)?.let {
                controller.state.value.settings.groups.single { it.groupId == gid }
                    .virtualAgents.orEmpty().single { it.hostNodeId == self }
            },
            "入驻必须建出席位",
        )
        assertEquals("dsh-coder", seat.name, "D13：席位名 = kind + 职责")
        assertEquals("ws-resolved", seat.defaultWsId, "执行点 = 群项目目录对该 agent 的解析结果")
        assertEquals(
            listOf(GroupWorkspaceInfo("ws-resolved", "proj")),
            seat.allowedWorkspaces,
            "白名单投影 = 解析出的执行点（标题退群指针的目录名）",
        )
        assertEquals(EntityTypes.SINGLE, seat.entityType, "新席位默认单兵")
    }

    /** agent 没宣告这个目录（解析 null）：允许加入，席位标「不可执行」—— 不猜、不撤。 */
    @Test
    fun stationingWithoutAResolvedPointerStillCreatesTheSeat() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort().also { it.resolved = null }
        controller.hostDirectoryPort = port
        controller.givenLocalAgent("dsh")
        val gid = controller.groupWithPointer(port)

        val result = controller.handleAddGroupAgent(gid, "dsh", null)
        assertTrue(result is AddGroupAgentResult.Added, "解析不出也允许加入（D6）")
        assertNull(
            (result as AddGroupAgentResult.Added).resolvedWsId,
            "解析结果如实是 null，不编一个执行点",
        )
        val seat = controller.state.value.settings.groups.single { it.groupId == gid }
            .virtualAgents.orEmpty().single { it.hostNodeId == self }
        assertNull(seat.defaultWsId, "未暴露 = 投影为空（界面据此显示「不可执行」）")
        assertTrue(seat.allowedWorkspaces.isEmpty())
        assertEquals("dsh-assistant", seat.name, "职责缺省归一到单兵默认（D13）")
    }

    /** 同一 agent 再入驻：沿用既有名字与团队字段（@ token 不能漂），只重写投影。 */
    @Test
    fun restationingKeepsTheExistingNameAndTeamFields() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort().also { it.resolved = null }
        controller.hostDirectoryPort = port
        controller.givenLocalAgent("dsh")
        val gid = controller.groupWithPointer(port)
        controller.handleAddGroupAgent(gid, "dsh", null)
        // 编个团队再入驻（模拟换指针 / 重新解析）。
        controller.handleSetGroupTeamDeclaration(
            gid, "dsh",
            listOf(com.kapp.tenon.core.protocol.TeamMemberDraft("m-1", "coder", "编码")),
        )
        val teamBefore = controller.state.value.settings.groups.single { it.groupId == gid }
            .virtualAgents.orEmpty().single { it.hostNodeId == self }

        port.resolved = "ws-resolved"
        controller.handleAddGroupAgent(gid, "dsh", "coder")

        val seat = controller.state.value.settings.groups.single { it.groupId == gid }
            .virtualAgents.orEmpty().single { it.hostNodeId == self }
        assertEquals("dsh-assistant", seat.name, "既有席位沿用既有名字（职责参数不再参与命名）")
        assertEquals("ws-resolved", seat.defaultWsId, "投影按新解析重写")
        assertEquals(EntityTypes.TEAM, seat.entityType, "团队字段不因重入驻被抹掉")
        assertEquals(teamBefore.teamMembers, seat.teamMembers, "组员清单原样保留")
    }

    /** 本机不是这个 agent 的宿主 / 群不存在：结构化拒绝，什么都没写。 */
    @Test
    fun stationingRejectsUnknownAgentsAndGroups() = runBlocking {
        val controller = controller()
        controller.givenLocalAgent("dsh")
        assertEquals(
            AddGroupAgentResult.Rejected(AddGroupAgentResult.Reason.GROUP_UNKNOWN),
            controller.handleAddGroupAgent("g-none", "dsh", null),
            "群不存在 ⇒ GROUP_UNKNOWN",
        )
        // 本机不在成员表里的群（元数据还在）：NOT_MEMBER。
        // 直接构造而不是 create —— create 会把 selfId 补进成员表，造不出这个前提。
        val meta = com.kapp.tenon.core.protocol.GroupMeta(
            groupId = "g-not-member",
            name = "普通群",
            members = listOf(peer),
            rev = 1,
            updatedBy = peer,
            updatedTs = 1_000L,
        )
        controller.handlePersist(
            controller.state.value.settings.copy(
                groups = controller.state.value.settings.groups + meta,
            ),
        )
        assertEquals(
            AddGroupAgentResult.Rejected(AddGroupAgentResult.Reason.NOT_MEMBER),
            controller.handleAddGroupAgent("g-not-member", "dsh", null),
            "不在成员表 ⇒ NOT_MEMBER",
        )
    }

    // ---------------------------------------------------------------- 移除（D6 另一半）

    /** 移除席位只摘本机自己的声明；外来席位与群本身不动。 */
    @Test
    fun removingASeatOnlyTouchesThisHostsDeclaration() = runBlocking {
        val controller = controller()
        controller.givenLocalAgent("dsh")
        val gid = assertNotNull(controller.createGroup("协同群", listOf(peer)))
        controller.handleAddGroupAgent(gid, "dsh", null)
        val before = controller.state.value.settings.groups.single { it.groupId == gid }
        val foreign = com.kapp.tenon.core.protocol.VirtualAgentMember(
            agentId = "remote", hostNodeId = peer, name = "remote-agent",
        )
        val seeded = before.copy(
            virtualAgents = before.virtualAgents.orEmpty() + foreign,
            rev = before.rev + 1,
            updatedBy = self,
            updatedTs = com.kapp.tenon.core.nowMillis() + 1,
        )
        controller.handlePersist(
            controller.state.value.settings.copy(
                groups = controller.state.value.settings.groups.map { if (it.groupId == gid) seeded else it },
            ),
        )

        assertTrue(controller.handleRemoveGroupAgent(gid, "dsh"), "本机席位存在 ⇒ 移除成功")
        val after = controller.state.value.settings.groups.single { it.groupId == gid }
        assertTrue(
            after.virtualAgents.orEmpty().none { it.hostNodeId == self },
            "本机席位已摘除",
        )
        assertTrue(
            after.virtualAgents.orEmpty().contains(foreign),
            "外来席位原样保留（机主主权覆盖不到别人）",
        )
        assertTrue(after.rev > seeded.rev, "移除也是一次 rev+1 广播")

        assertFalse(controller.handleRemoveGroupAgent(gid, "dsh"), "已移除后再移除 = false（幂等拒绝）")
    }

    // ---------------------------------------------------------------- 指针（D5）

    /** 设指针同步重建投影；清指针清投影但保留席位（席位照常对话，执行面标未解析）。 */
    @Test
    fun pointerWritesRebuildTheSeatProjection() = runBlocking {
        val controller = controller()
        val port = FakeDirectoryPort()
        controller.hostDirectoryPort = port
        controller.givenLocalAgent("dsh")
        val gid = controller.groupWithPointer(port)
        controller.handleAddGroupAgent(gid, "dsh", null)
        port.resolved = "ws-resolved"

        assertTrue(controller.handleSetGroupProjectDirectory(gid, port.directories.single().dirId))
        assertEquals(
            "ws-resolved",
            controller.state.value.settings.groups.single { it.groupId == gid }
                .virtualAgents.orEmpty().single { it.hostNodeId == self }.defaultWsId,
            "指针写下后席位投影按解析重建",
        )

        assertTrue(controller.handleSetGroupProjectDirectory(gid, null), "清除指针也是写路径")
        val seat = controller.state.value.settings.groups.single { it.groupId == gid }
            .virtualAgents.orEmpty().single { it.hostNodeId == self }
        assertNull(seat.defaultWsId, "指针清了 = 投影清空")
        assertTrue(
            controller.state.value.settings.groups.any { it.groupId == gid } &&
                controller.state.value.settings.groups.single { it.groupId == gid }
                    .virtualAgents.orEmpty().any { it.hostNodeId == self },
            "席位还在群里（能对话，只是不可执行）",
        )
    }

    /** 手机端（无端口）：指针拒绝写入 —— 指针是宿主本机事实，手机不能替宿主写。 */
    @Test
    fun pointerWritesWithoutAPortAreRejected() = runBlocking {
        val controller = controller()
        controller.givenLocalAgent("dsh")
        val gid = assertNotNull(controller.createGroup("协同群", listOf(peer)))
        assertFalse(
            controller.handleSetGroupProjectDirectory(gid, "dir-1"),
            "没有目录端口 ⇒ 拒绝，什么都没改",
        )
        assertEquals(ConvId.group(gid), ConvId.group(gid))
    }
}
