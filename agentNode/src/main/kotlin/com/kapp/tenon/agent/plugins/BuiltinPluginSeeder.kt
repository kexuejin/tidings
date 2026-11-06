// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import com.kapp.tenon.agent.drivers.DriverKeys
import com.kapp.tenon.core.protocol.TeamMemberInfo
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 默认内置插件种子装配器（P2-Step4 插件管理）：
 * 为新环境准备开箱即用的预装插件清单（如 Claude CLI、Codex CLI 与离线回显测试插件）。
 */
object BuiltinPluginSeeder {

    private val json = Json { prettyPrint = true }

    val BUILTIN_PLUGINS: List<PluginManifest> = listOf(
        PluginManifest(
            pluginId = DriverKeys.cliDriverId("claude"),
            name = "Claude Code",
            version = "1.0.0",
            type = "single",
            entrypoint = "claude",
            leader = TeamMemberInfo(agentId = "claude", name = "Claude", role = "architect"),
            capabilities = listOf("agent", "code-review", "terminal"),
        ),
        PluginManifest(
            pluginId = DriverKeys.cliDriverId("codex"),
            name = "Codex CLI",
            version = "1.0.0",
            type = "single",
            entrypoint = "codex",
            leader = TeamMemberInfo(agentId = "codex", name = "Codex", role = "coder"),
            capabilities = listOf("agent", "code-generation"),
        ),
        PluginManifest(
            pluginId = DriverKeys.cliDriverId("agy"),
            name = "Antigravity CLI",
            version = "1.0.0",
            type = "single",
            entrypoint = "agy",
            leader = TeamMemberInfo(agentId = "agy", name = "Antigravity", role = "worker"),
            capabilities = listOf("agent", "code-generation", "terminal"),
        ),
        PluginManifest(
            pluginId = DriverKeys.cliDriverId("dsh"),
            name = "DeepSeek Harness",
            version = "1.0.0",
            type = "single",
            entrypoint = "dsh",
            leader = TeamMemberInfo(agentId = "dsh", name = "DSH", role = "reviewer"),
            capabilities = listOf("agent", "code-review", "terminal"),
        ),
        PluginManifest(
            pluginId = DriverKeys.cliDriverId("opencode"),
            name = "OpenCode CLI",
            version = "1.0.0",
            type = "single",
            entrypoint = "opencode",
            leader = TeamMemberInfo(agentId = "opencode", name = "OpenCode", role = "worker"),
            capabilities = listOf("agent", "code-generation"),
        ),
        PluginManifest(
            pluginId = "echo-mock",
            name = "Echo Mock Agent",
            version = "1.0.0",
            type = "single",
            entrypoint = "echo",
            leader = TeamMemberInfo(agentId = "echo", name = "Echo", role = "tester"),
            capabilities = listOf("agent", "mock", "offline-test"),
        ),
    )

    fun seed(pluginsDir: File) {
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs()
        }
        for (manifest in BUILTIN_PLUGINS) {
            val pluginDir = File(pluginsDir, manifest.pluginId)
            if (!pluginDir.exists()) {
                pluginDir.mkdirs()
                val manifestFile = File(pluginDir, "manifest.json")
                manifestFile.writeText(json.encodeToString(PluginManifest.serializer(), manifest))
            }
        }
    }
}
