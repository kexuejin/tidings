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
    /** 裸命令名的搜索目录；null = 宿主 PATH + 常见安装位（测试注入临时目录）。 */
    private val searchDirectories: List<String>? = null,
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
        // 每个不拉起的分支都要把原因**落进插件目录旁的 process.log**：桌面壳没有
        // 可看的 stdout，只靠返回值上报等于把诊断藏进没人读的地方（真机现场：
        // 目录名与 pluginId 不一致被跳过，图形界面上连一句解释都找不到）。
        fun skipped(reason: String, pluginId: String? = null, command: List<String> = emptyList()): SpawnReport {
            appendProcessLog(pluginDir, reason)
            return SpawnReport(pluginDir.name, pluginId, command, started = false, reason = reason)
        }

        val manifestFile = File(pluginDir, MANIFEST_FILE)
        if (!manifestFile.isFile) {
            return skipped("no " + MANIFEST_FILE)
        }
        val manifest = try {
            PluginManifestJson.parse(manifestFile.readText())
        } catch (t: Throwable) {
            return skipped("manifest unreadable: " + (t.message ?: "parse error"))
        }
        val pluginId = manifest.pluginId
        if (pluginId in seenIds || pluginId in managed) {
            // 已注册（守护进程模式自己连进来了）或本进程已经拉起过：不重复拉起。
            return skipped("already managed or registered", pluginId)
        }
        val entrypoint = manifest.entrypoint?.trim()
        if (entrypoint.isNullOrEmpty()) {
            return skipped("manifest has no entrypoint (daemon-mode plugins manage their own process)", pluginId)
        }
        val command = entrypoint.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (command.isEmpty()) {
            return skipped("entrypoint is blank", pluginId, command)
        }
        // 目录名必须与 pluginId 一致才拉起：目录就是插件的身份载体（清单里报另一个
        // id 会让台账与磁盘对不上账 —— 与「合成名要被真名顶掉」同一条对账纪律）。
        if (pluginDir.name != pluginId) {
            return skipped(
                "directory name \"" + pluginDir.name + "\" must match manifest pluginId \"" + pluginId + "\"",
                pluginId, command,
            )
        }
        val env = mapOf(
            ENV_CONTROL_URL to controlBaseUrl(),
            ENV_PLUGIN_TOKEN to token,
            // GUI 的 PATH 补偿：子进程里 `#!/usr/bin/env …` 也要能解析（execvp 用的是
            // 父进程 PATH，启动前替换命令头；子进程 PATH 再补上同一批目录）。
            "PATH" to ((System.getenv("PATH") ?: "/usr/bin:/bin") + ":" +
                (searchDirectories ?: defaultSearchDirectories()).joinToString(":")),
        )
        val resolved = resolveExecutable(command)
        if (resolved == null) {
            val searched = (searchDirectories ?: defaultSearchDirectories()).joinToString(" : ")
            val reason = "executable \"" + command.first() + "\" not found in " + searched +
                " (GUI processes get a minimal PATH; use an absolute path in the entrypoint, or install the runtime into one of these directories)"
            appendProcessLog(pluginDir, reason)
            return SpawnReport(pluginDir.name, pluginId, command, started = false, reason = reason)
        }
        val logFile = File(pluginDir, PROCESS_LOG_FILE)
        return try {
            val process = spawner(resolved, pluginDir, env, logFile)
            synchronized(processes) { processes += pluginId to process }
            seenIds += pluginId
            managed += pluginId
            SpawnReport(pluginDir.name, pluginId, resolved, started = true)
        } catch (t: Throwable) {
            val reason = "spawn failed: " + (t.message ?: t::class.simpleName)
            appendProcessLog(pluginDir, reason)
            SpawnReport(pluginDir.name, pluginId, resolved, started = false, reason = reason)
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

    // ---------------------------------------------------------------- 可执行文件解析

    /**
     * 把 entrypoint 的裸命令名解析成绝对路径。
     *
     * ## 为什么宿主要自己解析（GUI 的 PATH 补偿）
     *
     * 桌面壳由 launchd 拉起，PATH 只有系统最小组 —— 用户 shell 里的 pyenv/homebrew
     * 一律不在其中。`python3 main.py` 在终端里能跑、被 GUI 宿主拉起就 `error=2`，
     * 而且那次失败在图形界面上没有任何可见面（stdout 不可看）。所以宿主在拉起前按
     * 搜索目录自己找一遍，找到的绝对路径替换进命令头；子进程 PATH 也补上同一批目录
     * （见 [spawnOne] 的 env），让脚本内部的 `#!/usr/bin/env …` 同样能解析。
     * 裸命令找不到 ⇒ 返回 null：调用方拒绝拉起，把全量搜索清单写进拒绝理由并落
     * 插件目录旁的 process.log（图形界面上唯一可诊断的地方）—— 一个注定 error=2
     * 的 spawn 不值得发。
     */
    internal fun resolveExecutable(command: List<String>): List<String>? {
        val head = command.firstOrNull() ?: return command
        if (head.contains('/')) {
            return command
        }
        for (dir in searchDirectories ?: defaultSearchDirectories()) {
            val candidate = File(dir, head)
            if (candidate.isFile && candidate.canExecute()) {
                return listOf(candidate.absolutePath) + command.drop(1)
            }
        }
        return null
    }

    /** 裸命令名的搜索目录：宿主 PATH + 常见安装位（shell 与 GUI 的 PATH 差异的补偿）。 */
    private fun defaultSearchDirectories(): List<String> {
        val fromPath = (System.getenv("PATH") ?: "")
            .split(':')
            .filter { it.isNotBlank() }
        return (fromPath + listOf("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin")).distinct()
    }

    /** 拉起失败的原因落进插件目录的 process.log —— 图形界面上唯一可诊断的地方。 */
    private fun appendProcessLog(pluginDir: File, line: String) {
        runCatching {
            File(pluginDir, PROCESS_LOG_FILE).appendText(
                java.time.Instant.now().toString() + " [plugin-host] " + line + "\n",
            )
        }
    }

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
