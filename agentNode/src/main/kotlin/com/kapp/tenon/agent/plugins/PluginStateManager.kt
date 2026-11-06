// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 插件状态与设置的持久化模型。
 */
@Serializable
data class PluginStateData(
    val version: Int = 1,
    val disabled: Set<String> = emptySet(),
    val settings: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * 插件状态管理器（P2-Step4 插件管理）：负责插件的启用/禁用状态与自定义配置的落盘。
 *
 * ## 设计纪律
 * 1. 默认状态文件落在 `<dataDirectory>/plugins_state.json`；
 * 2. 兼容两种构造形态：传**目录**（`ControlPlane` 默认装配）或传一个**以 `.json` 结尾的
 *    文件**（桌面端 `DesktopExtension` 为了钉住 `plugins-state.json` 这个名字而这么传）。
 *    后者绝不能被当成目录 `mkdirs()` —— 那会把状态文件本身建成空目录，随后所有写入
 *    抛异常，而旧实现把异常静默吞掉，于是 HTTP 200、内存生效、重启即丢。
 * 3. 线程安全：内存使用并发集合做缓存，落盘使用写临时文件 + 原子重命名；
 * 4. 故障隔离：状态文件损坏时安静回退为空白默认值，不崩溃主程序；但**写盘失败必须
 *    留痕**（日志 + [lastSaveError]），不许再无声吞掉。
 */
class PluginStateManager(
    private val dataDirectory: File,
) {
    companion object {
        const val STATE_FILE_NAME: String = "plugins_state.json"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val logger = Logger.getLogger(PluginStateManager::class.java.name)
    private val disabledPlugins = ConcurrentHashMap.newKeySet<String>()
    private val pluginSettings = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** 最近一次落盘失败的异常；成功写入后归零。给运维/测试一个不靠日志也能查的判据。 */
    @Volatile
    private var lastSaveError: Throwable? = null

    init {
        loadState()
    }

    /**
     * 以 `.json` 结尾的入参就是状态文件本身（桌面端历史装配），否则入参是目录。
     * 这条规则只决定**文件位置**，永远不代表可以对它调用 `mkdirs()`。
     */
    fun stateFile(): File =
        if (dataDirectory.name.endsWith(".json")) dataDirectory else File(dataDirectory, STATE_FILE_NAME)

    fun lastSaveError(): Throwable? = lastSaveError

    fun isPluginEnabled(pluginId: String): Boolean = !disabledPlugins.contains(pluginId)

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        if (enabled) {
            disabledPlugins.remove(pluginId)
        } else {
            disabledPlugins.add(pluginId)
        }
        saveState()
    }

    fun listDisabledPlugins(): Set<String> = disabledPlugins.toSet()

    fun getPluginSettings(pluginId: String): Map<String, String> {
        return pluginSettings[pluginId]?.toMap() ?: emptyMap()
    }

    fun updatePluginSettings(pluginId: String, newSettings: Map<String, String>) {
        val current = pluginSettings.computeIfAbsent(pluginId) { ConcurrentHashMap() }
        current.putAll(newSettings)
        saveState()
    }

    fun clearPluginSettings(pluginId: String) {
        pluginSettings.remove(pluginId)
        saveState()
    }

    @Synchronized
    private fun loadState() {
        val file = stateFile()
        if (!file.isFile) return
        try {
            val content = file.readText()
            val state = json.decodeFromString(PluginStateData.serializer(), content)
            disabledPlugins.clear()
            disabledPlugins.addAll(state.disabled)
            pluginSettings.clear()
            state.settings.forEach { (k, v) ->
                pluginSettings[k] = ConcurrentHashMap(v)
            }
        } catch (_: Throwable) {
            // Fall back to clean state on corruption
        }
    }

    @Synchronized
    private fun saveState() {
        val file = stateFile().absoluteFile
        val parent = file.parentFile
        var tempFile: File? = null
        try {
            require(parent != null) { "状态文件没有父目录：$file" }
            // 只创建**父目录**。旧实现对 `dataDirectory` 直接 mkdirs()，在 `.json` 文件形态下
            // 会把状态文件本身建成目录（真机上 `plugins-state.json` 就是这个空目录）。
            if (!parent.isDirectory && !parent.mkdirs()) {
                error("无法创建状态目录：$parent")
            }
            // 迁移旧事故现场：同名的空目录要拆掉换成普通 JSON 文件；非空目录不擅自删数据，
            // 明确报错并留痕，让运维决定怎么处理。
            if (file.isDirectory) {
                val leftover = file.list().orEmpty()
                require(leftover.isEmpty() && file.delete()) {
                    "状态路径被占用为目录且无法自动清理：$file（内容 ${leftover.size} 项）"
                }
            }
            tempFile = File(parent, "${file.name}.tmp")
            val state = PluginStateData(
                disabled = disabledPlugins.toSet(),
                settings = pluginSettings.mapValues { it.value.toMap() },
            )
            val content = json.encodeToString(PluginStateData.serializer(), state)
            tempFile.writeText(content)
            try {
                Files.move(
                    tempFile.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // 某些文件系统不支持同一分区的原子重命名，退化成覆盖写，但仍由临时文件落地。
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            lastSaveError = null
        } catch (t: Throwable) {
            tempFile?.delete()
            lastSaveError = t
            logger.log(
                Level.WARNING,
                "[plugin-state] 写入 $file 失败：内存状态仍生效，但不会跨重启保留",
                t,
            )
        }
    }
}
