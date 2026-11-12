// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import com.kapp.tenon.agent.drivers.DriverKeys
import java.io.File

/**
 * 内置清单的**退役（retire）**，不是一个种子器。
 *
 * ## 为什么这个对象只剩「拆」这一半
 *
 * 它原来叫 seeder：启动时往 `<数据目录>/plugins/` 里写 5 份 `cli-*` 清单
 * （claude / codex / agy / dsh / opencode）外加一份 `echo-mock`。那不是插件——
 * 插件的语义是「一个常驻进程，启动时被拉起，然后 `POST /v1/plugin/register` 自注册」；
 * 而这些 CLI 是**按任务拉起的子进程**（`AgentProfiles` 探明在位，
 * `CliSubprocessDriver` / `WorktreeSandbox` 在派单时 spawn 一次，干完就退）。
 *
 * 把它们写成常驻清单的后果是**每台机器每次启动都必然发生**的一条缺陷链：
 * `PluginHost.start()` 扫到目录 → 拉起 `claude`（无参数、无 TTY）→ 进程立刻退出 →
 * `watchForImmediateExit` 往 `process.log` 记一条退出码 → 界面上永远是
 * `pluginPresence=OFFLINE`。用户看到的是「装了插件却一直离线」，而真因是它压根不该是插件。
 *
 * 种子停了，**存量环境不会自愈**：`seed()` 只在目标目录不存在时写清单，所以旧机器上那
 * 五份清单会一直躺在那儿，继续秒退。存量清理因此必须是一次**独立于种子**的迁移
 * ——就是 [retire]，由装配侧在 `PluginHost.start()` 之前调一次。
 *
 * ## 退役 = 停用 + 清单改名，两件都做
 *
 * - `PluginStateManager` 里置 disabled：让「插件管理」页如实显示它已被停用，用户可查；
 * - `manifest.json` → [RETIRED_MANIFEST]：让 `start()` 连「跳过」分支都不进（没有清单就
 *   不是插件目录）。只置 disabled 也能拦住拉起，但状态文件丢一次就复活；只改名则界面上
 *   看不出发生过什么。两条一起才同时满足「不再秒退」与「可诊断」。
 *
 * 幂等：清单已经叫 `.retired` 的目录直接跳过，不重复写日志、不重复改状态。
 */
object BuiltinPluginSeeder {

    /**
     * 曾经被种成常驻插件的 id。
     *
     * 两种历史写法都收：现行的是 `<档案键>-cli`（[DriverKeys.cliDriverId]），早期版本
     * 用过 `cli-<档案键>`。目录名与清单里的 `pluginId` 任何一个命中即退役——老机器上
     * 这两个值确实对不上（目录 `cli-dsh`、清单写 `dsh-cli`）。
     */
    val RETIRED_PLUGIN_IDS: Set<String> = buildSet {
        listOf("claude", "codex", "agy", "dsh", "opencode").forEach { raw ->
            val key = DriverKeys.stripCliSuffix(DriverKeys.cliDriverId(raw))
            add(DriverKeys.cliDriverId(raw))
            add("cli-" + key)
        }
        // 离线回显插件：entrypoint 是 `echo`，同样是一个「拉起即退」的假插件。
        add("echo-mock")
    }

    /** 退役后清单的新文件名：留在原目录里，可人工还原，但不再被 [PluginHost.start] 看见。 */
    const val RETIRED_MANIFEST: String = PluginHost.MANIFEST_FILE + ".retired"

    /**
     * 扫 `<pluginsDir>` 并把历史遗留的内置清单退役掉；返回被退役的 id（空 = 无事可做）。
     *
     * [state] 可为 null（测试或不带状态管理器的装配）：此时只改名，不写状态。
     */
    fun retire(pluginsDir: File, state: PluginStateManager?): List<String> {
        if (!pluginsDir.isDirectory) return emptyList()
        val retired = mutableListOf<String>()
        pluginsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { pluginDir ->
            val manifestFile = File(pluginDir, PluginHost.MANIFEST_FILE)
            if (!manifestFile.isFile) return@forEach
            // 清单解析失败也要能退役：判据先看目录名，再看能不能读出的 pluginId。
            val manifest = runCatching { PluginManifestJson.parse(manifestFile.readText()) }.getOrNull()
            val manifestId = manifest?.pluginId
            val hit = pluginDir.name in RETIRED_PLUGIN_IDS || manifestId in RETIRED_PLUGIN_IDS
            if (!hit) return@forEach
            val id = manifestId ?: pluginDir.name
            state?.setPluginEnabled(id, false)
            val parked = File(pluginDir, RETIRED_MANIFEST)
            val renamed = runCatching { manifestFile.renameTo(parked) }.getOrDefault(false)
            appendLog(
                pluginDir,
                "retired: \"" + id + "\" is a CLI tool, not a resident plugin — it is spawned per task " +
                    "(AgentProfiles / WorktreeSandbox), so keeping it here only produced an immediate exit " +
                    "on every start; manifest parked as " + RETIRED_MANIFEST + " (renamed=" + renamed + ")",
            )
            retired += id
        }
        return retired
    }

    /** 与 [PluginHost] 的 `appendProcessLog` 同一落点与格式：图形界面上唯一可诊断的地方。 */
    private fun appendLog(pluginDir: File, line: String) {
        runCatching {
            File(pluginDir, PluginHost.PROCESS_LOG_FILE).appendText(
                java.time.Instant.now().toString() + " [plugin-host] " + line + "\n",
            )
        }
    }
}
