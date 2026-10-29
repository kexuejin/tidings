// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.TeamMemberInfo
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.session.NodeRuntime
import com.kapp.tidings.agent.plugins.PluginManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ControlPlane.pluginForAgent] 的身份匹配（M4 执行桥的第一步）。
 *
 * 这条匹配在真机上**栽过**：老席位声明的 agentId 是 `DSH`（工作区 kind 的大小写
 * 原样入驻），而插件清单的 agentId 受发送侧不变量约束只能是小写 slug —— 第一版
 * 用 [com.kapp.tidings.core.protocol.AgentSenderId.slugForSenderId] 当匹配器，
 * `DSH` 被它整个抹成**空串**，于是「插件明明在线、HTTP 派单回了 invokeId，
 * 执行桥却答 no-plugin」—— 卡片不上群、台账零条会话，三处全静默。
 *
 * 修正：身份匹配只有**一个来源** —— core 的 `sameAgent`（大小写不敏感 + 「截到
 * `-` 之前」的精度容忍），与协议层为「入驻写 `DSH`、会话报 `dsh-029b`」定的是
 * 同一条规则。
 */
class PluginForAgentMatchTest {

    private lateinit var scope: CoroutineScope
    private lateinit var agent: NodeRuntime
    private lateinit var plane: ControlPlane

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        agent = NodeRuntime(
            profile = Profile.mobile(
                nodeId = newId(), nick = "Tidings Agent", host = "relay-agent", ver = "relay-test",
            ),
            udpPort = 0,
            bindHost = ControlPlane.LOOPBACK,
        )
        runBlocking { agent.start(scope) }
        plane = ControlPlane(agent, httpPort = 0, token = "", sessions = AgentSessionRegistry())
        plane.start()
    }

    @AfterTest
    fun tearDown() {
        plane.stop()
        runBlocking { agent.stop() }
        scope.cancel()
    }

    private fun registerBridge(pluginId: String, leaderAgentId: String, vararg members: String) {
        plane.plugins.register(
            manifest = PluginManifest(
                pluginId = pluginId,
                name = pluginId,
                leader = TeamMemberInfo(agentId = leaderAgentId, name = leaderAgentId, role = "lead"),
                members = members.map { TeamMemberInfo(agentId = it, name = it, role = "member") },
            ),
            baseUrl = "http://" + ControlPlane.LOOPBACK + ":1",
            httpPort = 1,
            agentSenderIds = emptyList(),
        )
    }

    @Test
    fun anUppercaseSeatDeclarationStillFindsTheLowercasePlugin() {
        // 真机现场的精确形状：席位 agentId = "DSH"（大写原样入驻），插件 = 小写 slug。
        registerBridge("dsh-echo-bridge", leaderAgentId = "dsh")
        assertEquals("dsh-echo-bridge", plane.pluginForAgent("DSH")?.pluginId)
        assertEquals("dsh-echo-bridge", plane.pluginForAgent("dsh")?.pluginId)
    }

    @Test
    fun aSessionStyleAgentIdHeadMatchesThePluginLeader() {
        // 会话帧报 `dsh-029bbbf3` 这类实例 id；sameAgent 的「截到 - 之前」把它归到 leader。
        registerBridge("bridge", leaderAgentId = "dsh")
        assertEquals("bridge", plane.pluginForAgent("dsh-029bbbf3")?.pluginId)
    }

    @Test
    fun aMemberAgentIdIsFoundWhenTheLeaderDoesNotMatch() {
        registerBridge("team-plugin", leaderAgentId = "team-lead", members = arrayOf("echo-coder"))
        assertEquals("team-plugin", plane.pluginForAgent("ECHO-CODER")?.pluginId, "组员兜底 + 大小写不敏感")
        assertNull(plane.pluginForAgent("nobody"), "谁都不承接的 agent 必须如实答 null（不猜）")
    }

    @Test
    fun anOfflinePluginIsNeverMatched() {
        registerBridge("sleepy", leaderAgentId = "dsh")
        plane.plugins.markOffline("sleepy", "test")
        assertNull(plane.pluginForAgent("dsh"), "离线插件不许接派单")
    }
}
