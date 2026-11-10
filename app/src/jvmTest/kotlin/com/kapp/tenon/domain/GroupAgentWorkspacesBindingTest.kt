// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.platform.InMemoryAppPlatform
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
 * 旧实现（债#2 / 一致性修订清单 F2）：`workspaceOfGroup` / `isWorkspaceGroup` 拿群简介/
 * 群名对工作区路径、id、标题做**子串匹配** —— 简介被人编辑过，绑定就悄悄断了；反过来，
 * 简介里恰好提到路径的普通群会被误判成工作区群。
 *
 * 新判据（方案 §3 / 项目目录决策稿 D2）：群本身不绑定工作区，**声明进群的 agent 带默认
 * 工作区**；绑定 = 声明里 `defaultWsId` 对工作区表的精确 id 匹配，一个字符都不猜。
 * 声明的写入路径 = 入驻（`handleAddGroupAgent`，D6）与指针投影重建（D5）——
 * 「勾选 wsIds 一次成像」的旧三参保存流已随项目目录模型删除，其专有用例一并退役；
 * 入驻/移除/指针的钉子见 `GroupAgentStationingTest`。
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
}
