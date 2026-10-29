// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.VirtualAgentMember
import com.kapp.tidings.core.protocol.makeEnvelope
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * 群与工作区的绑定是**声明**（`virtualAgents` 的 `defaultWsId`），不是子串猜测。
 *
 * 旧实现（债#2）：`workspaceOfGroup` / `isWorkspaceGroup` 拿群简介/群名对工作区路径、
 * id、标题做**子串匹配** —— 简介被人编辑过，绑定就悄悄断了；反过来，简介里恰好
 * 提到路径的普通群会被误判成工作区群。
 *
 * 新判据（方案 §3）：群本身不绑定工作区，**声明进群的 agent 带默认工作区**；
 * 绑定 = 声明里 `defaultWsId` 对工作区表的精确 id 匹配，一个字符都不猜。
 * 暴露/收起的入口收拢为 `setGroupAgentWorkspaces`：一次调用把**本机**的声明集合
 * 整体改写为所选集合 —— 撤回是机主主权，只覆盖本机自己的声明。
 */
class GroupAgentWorkspacesBindingTest {

    private val self = "abc12345-0000-4000-8000-000000000000"
    private val lin = "11111111-1111-4111-8111-111111111111"

    private fun CoroutineScope.controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = MemorySettingsStore(self),
        scope = this,
        version = "relay-test",
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

    /** 简介里写着工作区路径，但**没有任何声明**的普通群：不许再靠子串猜出绑定。 */
    @Test
    fun aDescriptionOnlyGroupIsNoLongerBoundToAWorkspace() = runBlocking {
        val controller = controller()
        controller.recordLocalWorkspace(
            wsId = "ws-alpha",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )
        // 旧 quickCreate 的形态：description = 路径、无 agent 声明。
        val groupId = assertNotNull(
            controller.createGroup("某个群", listOf(lin), description = "/srv/teahouse-mobile"),
            "前提：群建出来了",
        )
        assertNull(
            controller.state.value.workspaceOfGroup(groupId),
            "只凭简介子串猜出的绑定必须消失：那正是债#2 的模糊匹配",
        )
    }

    /** 声明里的 `defaultWsId` 是唯一绑定判据：精确 id 匹配，改名/改简介都断不了。 */
    @Test
    fun aDeclaredWorkspaceIsResolvedByIdAndSurvivesRenaming() = runBlocking {
        val controller = controller()
        controller.recordLocalWorkspace(
            wsId = "ws-alpha",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )
        val groupId = assertNotNull(
            controller.createWorkspaceGroup(
                name = "跟标题毫不相干的名字",
                wsId = "ws-alpha",
                memberIds = listOf(lin),
                description = "跟路径毫不相干的简介",
            ),
            "前提：工作区群建出来了",
        )
        val ws = assertNotNull(
            controller.state.value.workspaceOfGroup(groupId),
            "声明了默认工作区的群必须解析出那行工作区（id 精确匹配，不看简介与群名）",
        )
        assertEquals("ws-alpha", ws.wsId)

        // 改简介：声明不动 ⇒ 绑定不动（旧实现按简介匹配，改完就断）。
        controller.updateGroupMeta(groupId, description = "换个新简介", announce = null)
        assertEquals("ws-alpha", controller.state.value.workspaceOfGroup(groupId)?.wsId, "改简介不断绑定")
    }

    /** 没有声明 ⇒ 不是工作区群；有声明 ⇒ 回复 agent 也算点名（触发语义）。 */
    @Test
    fun theWorkspaceGroupPredicateFollowsTheDeclarations() = runBlocking {
        val controller = controller()
        controller.recordLocalWorkspace(
            wsId = "ws-alpha",
            wsTitle = "teahouse-mobile",
            wsPath = "/srv/teahouse-mobile",
            agentKey = "dsh",
            agentKind = "DSH",
        )
        val plain = assertNotNull(
            controller.createGroup("纯聊天", listOf(lin), description = "/srv/teahouse-mobile"),
            "前提：群建出来了",
        )
        assertFalse(
            controller.groupHasDeclaredAgents(plain),
            "只有路径子串、没有声明 ⇒ 不是工作区群（旧判据在这里会误判 true）",
        )
        val bound = assertNotNull(
            controller.createWorkspaceGroup(name = "协同群", wsId = "ws-alpha", memberIds = listOf(lin)),
            "前提：工作区群建出来了",
        )
        assertTrue(
            controller.groupHasDeclaredAgents(bound),
            "带 agent 声明的群就是工作区群：回复 agent 不必再 @ 一遍",
        )
    }

    /**
     * `setGroupAgentWorkspaces` 一次成像：本机声明集合被**整体改写**成所选集合，
     * 其它设备的声明原样保留，已声明工作区的名字不换（@ 提及的 token 不能漂）。
     */
    @Test
    fun setGroupAgentWorkspacesRewritesThisHostsDeclarationsInOneShot() = runBlocking {
        val controller = controller()
        for (ws in listOf("ws-a" to "Alpha", "ws-b" to "Beta")) {
            controller.recordLocalWorkspace(
                wsId = ws.first,
                wsTitle = ws.second,
                wsPath = "/srv/" + ws.second,
                agentKey = "dsh",
                agentKind = "DSH",
            )
        }
        val groupId = assertNotNull(
            controller.createWorkspaceGroup(name = "协同群", wsId = "ws-a", memberIds = listOf(lin)),
            "前提：工作区群建出来了",
        )
        // 别的设备声明的一条（不可动）：经真实的 group.info 合并进来的（rev+1），
        // 撤回是机主主权，只覆盖本机自己的声明。
        val local = controller.state.value.settings.groups.single { it.groupId == groupId }
        val foreign = VirtualAgentMember(agentId = "remote", hostNodeId = lin, name = "remote-agent", defaultWsId = "ws-z")
        // 群元数据只认**管理员级**的改写（普通成员只能加人）：外来声明经群主 side
        // 的 info 快照进来 —— 现实里这就是群主转发/合并后的那一份。
        val incoming = local.copy(
            virtualAgents = local.virtualAgents.orEmpty() + foreign,
            rev = local.rev + 1,
            updatedBy = self,
            updatedTs = nowMillis() + 1,
        )
        controller.groupsCtl.handleOnGroupInfo(
            makeEnvelope(MsgTypes.GROUP, self, Codec.groupInfoPayload(incoming)),
            incoming,
        )
        val seeded = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertTrue(seeded.virtualAgents.orEmpty().contains(foreign), "前提：外来声明已合并进来")

        // 一次调用：选 ws-a + ws-b。
        controller.setGroupAgentWorkspaces(groupId, listOf("ws-a", "ws-b"))
        val after = controller.state.value.settings.groups.single { it.groupId == groupId }
        val mine = after.virtualAgents.orEmpty().filter { it.hostNodeId == self }
        assertEquals(
            setOf("ws-a", "ws-b"),
            mine.mapNotNull { it.defaultWsId }.toSet(),
            "本机声明必须整体等于所选集合",
        )
        assertTrue(
            after.virtualAgents.orEmpty().contains(foreign),
            "其它设备的声明原样保留：撤回是机主主权，只覆盖本机",
        )
        assertTrue(after.rev > seeded.rev, "元数据改动必须递增 rev，否则对端判 stale")

        // 收窄到只剩 ws-b：ws-a 的声明消失，ws-b 的名字保持（@ token 不漂）。
        val nameOfB = mine.single { it.defaultWsId == "ws-b" }.name
        controller.setGroupAgentWorkspaces(groupId, listOf("ws-b"))
        val narrowed = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertEquals(
            setOf("ws-b"),
            narrowed.virtualAgents.orEmpty().filter { it.hostNodeId == self }.mapNotNull { it.defaultWsId }.toSet(),
            "收窄后本机只剩 ws-b",
        )
        assertEquals(
            nameOfB,
            narrowed.virtualAgents.orEmpty().single { it.hostNodeId == self && it.defaultWsId == "ws-b" }.name,
            "已声明工作区的名字不许换：换了它，别人正在打的 @ 就失效了",
        )
        assertTrue(
            narrowed.virtualAgents.orEmpty().contains(foreign),
            "外来声明仍在",
        )
    }

    /** 撤掉全部所选 = 清空**本机**声明（外来声明仍保留）。 */
    @Test
    fun clearingTheSelectionRemovesOnlyThisHostsDeclarations() = runBlocking {
        val controller = controller()
        controller.recordLocalWorkspace(
            wsId = "ws-a",
            wsTitle = "Alpha",
            wsPath = "/srv/alpha",
            agentKey = "dsh",
            agentKind = "DSH",
        )
        val groupId = assertNotNull(
            controller.createWorkspaceGroup(name = "协同群", wsId = "ws-a", memberIds = listOf(lin)),
            "前提：工作区群建出来了",
        )
        val local = controller.state.value.settings.groups.single { it.groupId == groupId }
        val foreign = VirtualAgentMember(agentId = "remote", hostNodeId = lin, name = "remote-agent")
        val incoming = local.copy(
            virtualAgents = local.virtualAgents.orEmpty() + foreign,
            rev = local.rev + 1,
            updatedBy = self,
            updatedTs = nowMillis() + 1,
        )
        controller.groupsCtl.handleOnGroupInfo(
            makeEnvelope(MsgTypes.GROUP, self, Codec.groupInfoPayload(incoming)),
            incoming,
        )

        controller.setGroupAgentWorkspaces(groupId, emptyList())

        val after = controller.state.value.settings.groups.single { it.groupId == groupId }
        assertTrue(
            after.virtualAgents.orEmpty().none { it.hostNodeId == self },
            "清空选择 = 本机声明全撤",
        )
        assertTrue(
            after.virtualAgents.orEmpty().any { it.hostNodeId == lin },
            "别人的声明仍在",
        )
        assertEquals(ConvId.group(groupId), ConvId.group(groupId))
    }
}
