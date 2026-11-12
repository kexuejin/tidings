// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import com.kapp.tenon.agent.drivers.AgentProfiles
import com.kapp.tenon.agent.drivers.DriverKeys
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 插件注册 ↔ AgentProfile 动态档案的**联动守卫**。
 *
 * 插件注册进 [PluginRegistry] 的同时必须成为一等 Agent（档案可解析、vendor 统一、
 * 岗位 BOTH），否则异厂商调度（StageChainGate 的独立审核判据）看不见它；
 * 注销时档案必须同步消失，否则会有一个永远派不出去的幽灵 agent。
 *
 * [AgentProfiles] 是进程级全局单例 —— pluginId 用纳秒后缀隔离，finally 里清场，
 * 不污染同 JVM 里的其他用例。
 */
class PluginRegistryProfileLinkageTest {

    private val registry = PluginRegistry()
    private val pluginId = "linkage-probe-" + System.nanoTime()

    @AfterTest
    fun cleanUp() {
        registry.unregister(pluginId)
        AgentProfiles.unregisterProfile(pluginId)
        registry.dispose()
    }

    @Test
    fun registeringAPluginAlsoRegistersItsAgentProfile() {
        val entry = registry.register(
            PluginManifest(pluginId = pluginId, name = "Linkage Probe"),
            baseUrl = "http://127.0.0.1:1",
            httpPort = 1,
            agentSenderIds = listOf("peer:leader"),
        )

        assertNotNull(entry, "插件条目应注册成功")
        assertEquals(PluginRegistry.STATUS_ONLINE, entry.status)

        val profile = AgentProfiles.profileOf(pluginId)
        assertNotNull(profile, "插件注册后必须有可解析的 AgentProfile —— 否则异厂商调度看不见它")
        assertEquals(pluginId, profile.key)
        assertEquals(AgentProfiles.EXTERNAL_PLUGIN_VENDOR, profile.vendor,
            "插件 vendor 必须统一为 external-plugin —— 用 pluginId 当 vendor 会把同厂商两个插件误判成异厂商")
        assertEquals("Linkage Probe", profile.displayName)
        assertEquals(AgentProfiles.Role.BOTH, profile.defaultRole, "插件岗位是 BOTH：可干活可审核")
        assertTrue(
            AgentProfiles.knownKeys().contains(DriverKeys.canonicalKey(pluginId)),
            "knownKeys 必须收录插件档案键（派单入口的可用性列表靠它）",
        )
    }

    @Test
    fun unregisteringAPluginRemovesItsAgentProfile() {
        registry.register(
            PluginManifest(pluginId = pluginId, name = "Linkage Probe"),
            baseUrl = "http://127.0.0.1:1",
            httpPort = 1,
            agentSenderIds = emptyList(),
        )
        assertNotNull(AgentProfiles.profileOf(pluginId), "前置：注册后档案应在")

        val removed = registry.unregister(pluginId)

        assertTrue(removed, "注销应返回真的移除了条目")
        assertNull(registry.get(pluginId), "插件条目应消失")
        assertNull(AgentProfiles.profileOf(pluginId), "档案必须同步消失 —— 留着就是派不出去的幽灵 agent")
        assertFalse(
            AgentProfiles.knownKeys().contains(DriverKeys.canonicalKey(pluginId)),
            "knownKeys 也要同步摘掉",
        )
    }

    @Test
    fun reRegisteringTheSamePluginIdReplacesBothEntryAndProfile() {
        registry.register(
            PluginManifest(pluginId = pluginId, name = "Linkage Probe v1"),
            baseUrl = "http://127.0.0.1:1",
            httpPort = 1,
            agentSenderIds = emptyList(),
        )
        registry.register(
            PluginManifest(pluginId = pluginId, name = "Linkage Probe v2"),
            baseUrl = "http://127.0.0.1:2",
            httpPort = 2,
            agentSenderIds = emptyList(),
        )

        assertEquals("Linkage Probe v2", registry.get(pluginId)?.manifest?.name,
            "同 pluginId 重注册是替换语义，不长出第二个条目")
        assertEquals(1, registry.list().count { it.pluginId == pluginId })
        assertEquals("Linkage Probe v2", AgentProfiles.profileOf(pluginId)?.displayName,
            "档案跟着替换，displayName 用的是最新清单的 name")
    }
}
