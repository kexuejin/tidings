// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.plugins

import java.io.File
import java.io.IOException

/**
 * 托管模式插件宿主（方案 §2.3 模式一）：扫描插件目录、读清单、拉起子进程并注入
 * 环境变量。插件就绪后**自己**向控制面注册 —— 宿主只负责「把它带起来」，入驻与否
 * 由注册契约（token / 清单校验）决定。
 *
 * ## 故障隔离（这是本类最重要的行为）
 *
 * 一个插件目录坏了（manifest 缺键 / JSON 解不了 / entrypoint 拉不起来）**绝不能**
 * 拖垮宿主：[start] 逐目录处理，坏的那份如实记进 [SpawnReport]（`started = false`
 * + 原因）并继续下一份；进程拉起失败也只记不抛。桌面端调用方再把整个 [start] 包进
 * runCatching —— 插件是加装能力，与 §1.4 之前的控制面同一待遇。
 *
 * ## 测试性
 *
 * 进程拉起经 [spawner] 注入：测试传一个**只记录不执行**的假 spawner（零环境依赖，
 * 不撞 envDependentTestGate）；真进程语义（子进程崩溃、僵尸回收）由桌面冒烟以
 * echo 插件真进程验，证据进 runtime-evidence。
 */
class PluginHost(
    /** 宿主数据目录（插件目录是它下面的 `plugins/`，与 token 受控文件同一处）。 */
    private val dataDirectory: File,
    /** 控制面基座（宿主 start 之后才知道实际端口，所以是惰性求值）。 */
    private val controlBaseUrl: () -> String,
    private val plugins: PluginRegistry,
    /** 拉起进程的出口；默认真 [ProcessBuilder]（输出追加到插件目录旁的日志文件）。 */
    private val spawner: (command: List<String>, workDir: File, env: Map<String, String>, logFile: File) -> Process = ::defaultSpawner,
) {

    /** 一个插件目录的处理结果：`pluginId` 来自 manifest（解析失败时为 null）。 */
    data class SpawnReport(
        val dirName: String,
        val pluginId: String?,
        val command: List<String>,
        val started: Boolean,
        val reason: String? = null,
    )

    private val processes = mutableListOf<Pair<String, Process>>()

    /** 插件目录：<数据目录>/plugins/（与 token 受控文件同处，方案 §2.1）。 */
    fun pluginsDirectory(): File = File(dataDirectory, "plugins")

    /**
     * 扫描并拉起。幂等由调用方保证（桌面端每拍只在挂接时调一次）；
     * 重复调用不会重复拉起同一份 manifest（按 pluginId 去重）。
     */
    fun start(): List<SpawnReport> {
        // token 受控文件无论有没有插件都要备好：守护进程模式的插件靠它注册，
        // 它缺席等于所有 daemon 模式插件集体入不了驻。
        val token = PluginTokenGate.ensureTokenFile(dataDirectory).readText().trim()
        val dir = pluginsDirectory()
        if (!dir.isDirectory) {
            // 没装任何插件是常态，不是错误：建好目录后安静返回。
            dir.mkdirs()
            return emptyList()
        }
        val reports = mutableListOf<SpawnReport>()
        val seenIds = plugins.list().map { it.pluginId }.toMutableSet()
        val managed = managedPluginIds.toMutableSet()
        dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { pluginDir ->
            val report = spawnOne(pluginDir, token, seenIds, managed)
            reports += report
        }
        return reports
    }

    private fun spawnOne(
        pluginDir: File,
        token: String,
        seenIds: MutableSet<String>,
        managed: MutableSet<String>,
    ): SpawnReport {
        val manifestFile = File(pluginDir, MANIFEST_FILE)
        if (!manifestFile.isFile) {
            return SpawnReport(pluginDir.name, null, emptyList(), started = false, reason = "no " + MANIFEST_FILE)
        }
        val manifest = try {
            PluginManifestJson.parse(manifestFile.readText())
        } catch (t: Throwable) {
            return SpawnReport(pluginDir.name, null, emptyList(), started = false, reason = "manifest unreadable: " + (t.message ?: "parse error"))
        }
        val pluginId = manifest.pluginId
        if (pluginId in seenIds || pluginId in managed) {
            // 已注册（守护进程模式自己连进来了）或本进程已经拉起过：不重复拉起。
            return SpawnReport(pluginDir.name, pluginId, emptyList(), started = false, reason = "already managed or registered")
        }
        val entrypoint = manifest.entrypoint?.trim()
        if (entrypoint.isNullOrEmpty()) {
            return SpawnReport(pluginDir.name, pluginId, emptyList(), started = false, reason = "manifest has no entrypoint (daemon-mode plugins manage their own process)")
        }
        val command = entrypoint.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (command.isEmpty()) {
            return SpawnReport(pluginDir.name, pluginId, command, started = false, reason = "entrypoint is blank")
        }
        // 目录名必须与 pluginId 一致才拉起：目录就是插件的身份载体（清单里报另一个
        // id 会让台账与磁盘对不上账 —— 与「合成名要被真名顶掉」同一条对账纪律）。
        if (pluginDir.name != pluginId) {
            return SpawnReport(
                pluginDir.name, pluginId, command, started = false,
                reason = "directory name \"" + pluginDir.name + "\" must match manifest pluginId \"" + pluginId + "\"",
            )
        }
        val env = mapOf(
            ENV_CONTROL_URL to controlBaseUrl(),
            ENV_PLUGIN_TOKEN to token,
        )
        val logFile = File(pluginDir, PROCESS_LOG_FILE)
        return try {
            val process = spawner(command, pluginDir, env, logFile)
            synchronized(processes) { processes += pluginId to process }
            seenIds += pluginId
            managed += pluginId
            SpawnReport(pluginDir.name, pluginId, command, started = true)
        } catch (t: Throwable) {
            SpawnReport(pluginDir.name, pluginId, command, started = false, reason = "spawn failed: " + (t.message ?: t::class.simpleName))
        }
    }

    /** 停机：先 SIGTERM（插件会注销再退），超时不退再强杀。 */
    fun stop() {
        val snapshot = synchronized(processes) { processes.toList() }
        processes.clear()
        snapshot.forEach { (_, process) ->
            runCatching { process.destroy() }
        }
        snapshot.forEach { (_, process) ->
            runCatching {
                if (process.isAlive && !process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
        }
    }

    /** 本宿主当前拉起着的插件 id（诊断读数）。 */
    fun managedPluginIdsSnapshot(): List<String> = synchronized(processes) { processes.map { it.first } }

    private val managedPluginIds: Set<String>
        get() = synchronized(processes) { processes.map { it.first }.toSet() }

    companion object {
        const val MANIFEST_FILE: String = "manifest.json"
        const val ENV_CONTROL_URL: String = "TIDINGS_CONTROL_URL"
        const val ENV_PLUGIN_TOKEN: String = "TIDINGS_PLUGIN_TOKEN"

        /** 子进程输出落点：插件目录旁一份（数据目录里的运行产物，不进版本库）。 */
        const val PROCESS_LOG_FILE: String = "process.log"

        private fun defaultSpawner(command: List<String>, workDir: File, env: Map<String, String>, logFile: File): Process {
            val builder = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
            builder.environment().putAll(env)
            // 输出追加到文件而不是 inherit：桌面形态没有可看的控制台，
            // 而「插件为什么没起来」几乎只在它的 stdout 里（注册被拒的原因）。
            logFile.parentFile?.mkdirs()
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            return try {
                builder.start()
            } catch (e: IOException) {
                throw IOException("cannot start " + command.joinToString(" ") + " in " + workDir + ": " + e.message, e)
            }
        }
    }
}

/** manifest.json 文本 → [PluginManifest]（磁盘文件的读取口只有这一个）。 */
internal object PluginManifestJson {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun parse(text: String): PluginManifest = json.decodeFromString(PluginManifest.serializer(), text)
}
