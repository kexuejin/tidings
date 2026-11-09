// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.core.nowMillis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 通用进程智能体驱动（P2-Step4 插件扩展执行体）：
 * 支持声明式命令行模板（占位符替换）、环境凭证安全隔离、超时监控与事件流映射。
 *
 * ## 安全红线与不变式
 * 1. 凭据隔离：敏感配置项仅经由 `TENON_SECRET_*` 环境变量注入，严禁拼入命令行参数，防 `ps aux` 泄漏；
 * 2. 管道防死锁：子进程 stderr 异步排空并保留末尾 300 字符错误诊断缓冲；
 * 3. 超时看门狗：任务超时触发优雅中断，超时不退强制销毁；
 * 4. 故障隔离：认不出的标准输出行优雅跳过，退出码作为完成裁决。
 */
class ProcessAgentDriver(
    override val driverId: String,
    override val displayName: String,
    private val commandTemplate: List<String>,
    private val workingDirTemplate: String = "{workspace}",
    private val streamFormat: String = FORMAT_TENON_EVENT_STREAM,
    private val timeoutSec: Long = 300L,
    private val envOverrides: Map<String, String> = emptyMap(),
    private val newSessionId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val spawner: (command: List<String>, workDir: File, env: Map<String, String>) -> Process = ::defaultSpawner,
) : AgentDriver {

    companion object {
        const val FORMAT_TENON_EVENT_STREAM: String = "TENON_EVENT_STREAM"
        const val FORMAT_CLAUDE_STREAM_JSON: String = "CLAUDE_STREAM_JSON"
        const val FORMAT_RAW_TEXT: String = "RAW_TEXT"

        private val JSON_PARSER = Json { ignoreUnknownKeys = true }

        private fun defaultSpawner(command: List<String>, workDir: File, env: Map<String, String>): Process {
            val pb = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(false)
            pb.environment().putAll(env)
            return pb.start()
        }
    }

    private val sessions = ConcurrentHashMap<String, String>()
    private val runningProcesses = ConcurrentHashMap<String, Process>()

    override suspend fun listCapabilities(): List<DriverCapability> = listOf(
        DriverCapability(
            id = "process-agent",
            name = displayName,
            description = "Subprocess agent: $displayName",
            category = "model",
            enabled = true,
        ),
    )

    override suspend fun openSession(wsPath: String, resumeSessionId: String?): SessionHandle {
        val id = resumeSessionId ?: newSessionId()
        sessions[id] = wsPath
        return SessionHandle(
            sessionId = id,
            wsPath = wsPath.ifBlank { null },
            resumed = resumeSessionId != null,
            createdAt = nowMillis(),
        )
    }

    override suspend fun executeTask(
        sessionId: String,
        prompt: String,
        onEvent: suspend (DriverEvent) -> Unit,
    ) {
        val wsPath = sessions[sessionId]
        if (wsPath == null) {
            onEvent(DriverEvent.Error("unknown session \"$sessionId\" — call openSession first"))
            return
        }

        val workDir = resolveWorkDir(wsPath)
        val resolvedCommand = resolveCommand(prompt, sessionId, wsPath)
        // 会话对齐键（提案 §9 阶段三）：宿主派起的 CLI 在 hook 脚本里能如实报出
        // 自己的 sessionId——审批路由据此把审批卡归到派单会话。
        val processEnv = buildEnvironment().toMutableMap().apply {
            put(DriverKeys.ENV_HOOK_SESSION_ID, sessionId)
        }

        val process = try {
            spawner(resolvedCommand, workDir, processEnv)
        } catch (t: Throwable) {
            onEvent(DriverEvent.Error("failed to start process: ${t.message ?: t::class.simpleName}"))
            return
        }

        runningProcesses[sessionId] = process
        val stderrTail = StringBuilder()

        // 异步排空 stderr，防止子进程缓冲区满导致死锁。
        //
        // 这里同样要走有界读取：stderr 只留前 1000 字符，但 `useLines` 会把**整行**
        // 先读进堆 —— 一个往 stderr 刷 1 GB 单行的失控进程照样能 OOM 宿主。超限行
        // 只丢内容、不杀进程（诊断输出不该决定任务生死），流保持同步继续排空。
        val errReader = thread(name = "stderr-$driverId-$sessionId", isDaemon = true) {
            runCatching {
                val reader = BoundedLineReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8))
                while (true) {
                    when (val line = reader.readLine()) {
                        BoundedLine.Eof -> break
                        BoundedLine.TooLong -> {
                            if (stderrTail.length < 1000) {
                                stderrTail.append("[stderr line over ").append(DriverLineLimits.MAX_LINE_CHARS)
                                    .append(" chars, dropped]\n")
                            }
                        }
                        is BoundedLine.Text -> {
                            if (stderrTail.length < 1000) stderrTail.append(line.value).append('\n')
                        }
                    }
                }
            }
        }

        var finishedReported = false

        try {
            val reader = BoundedLineReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            while (true) {
                val line = when (val read = reader.readLine()) {
                    BoundedLine.Eof -> break
                    is BoundedLine.Text -> read.value
                    BoundedLine.TooLong -> {
                        // 单行熔断（§8.2 传输防火墙）：超限行**不再整行进堆**（旧写法用
                        // `useLines`，判据成立时 1 GB 的行已经在堆里了），并且**真的**收场。
                        //
                        // 旧实现只 `break` 出循环，注释却写着 "process will be reaped"：
                        // 进程没被杀，接着落到下面的 `waitFor(timeoutSec)`，于是一条超长行
                        // 换来 5 分钟的静默挂起（timeoutSec = 300）。这里显式杀掉再返回。
                        onEvent(
                            DriverEvent.Error(
                                "driver \"" + driverId + "\" emitted a line over " +
                                    DriverLineLimits.MAX_LINE_CHARS + " chars; process killed, task aborted",
                            ),
                        )
                        process.destroy()
                        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                        return
                    }
                }
                val event = parseLine(line)
                if (event != null) {
                    if (event is DriverEvent.Finished) {
                        finishedReported = true
                    }
                    onEvent(event)
                }
            }

            // 超时与等待结束
            val exited = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!exited) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                onEvent(DriverEvent.Error("process timed out after ${timeoutSec}s"))
                return
            }

            errReader.join(500)
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val detail = stderrTail.toString().trim().takeLast(300)
                val msg = if (detail.isEmpty()) "process exited with code $exitCode" else "process exited with code $exitCode: $detail"
                onEvent(DriverEvent.Error(msg))
            } else if (!finishedReported) {
                onEvent(DriverEvent.Finished(finishReason = "stop"))
            }
        } catch (t: Throwable) {
            process.destroyForcibly()
            onEvent(DriverEvent.Error("process execution failed: ${t.message ?: t::class.simpleName}"))
        } finally {
            runningProcesses.remove(sessionId)
        }
    }

    override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) {
        // CLI/Subprocess 驱动不接收卡片回调
        throw UnsupportedOperationException("action $actionId not supported for process driver $driverId")
    }

    override suspend fun queryProgress(sessionId: String): TaskProgressSnapshot? {
        val process = runningProcesses[sessionId] ?: return null
        return TaskProgressSnapshot(
            sessionId = sessionId,
            state = if (process.isAlive) "running" else "finished",
            updatedAt = nowMillis(),
        )
    }

    fun abortSession(sessionId: String): Boolean {
        val p = runningProcesses.remove(sessionId) ?: return false
        p.destroy()
        if (!p.waitFor(1, TimeUnit.SECONDS)) {
            p.destroyForcibly()
        }
        return true
    }

    private fun resolveWorkDir(wsPath: String): File {
        val raw = workingDirTemplate.replace("{workspace}", wsPath.ifBlank { "." })
        val f = File(raw)
        return if (f.isDirectory) f else File(".")
    }

    private fun resolveCommand(prompt: String, sessionId: String, wsPath: String): List<String> {
        return commandTemplate.map { part ->
            part.replace("{prompt}", prompt)
                .replace("{session}", sessionId)
                .replace("{workspace}", wsPath)
        }
    }

    private fun buildEnvironment(): Map<String, String> {
        val env = HashMap<String, String>(System.getenv())
        for ((k, v) in envOverrides) {
            env["TENON_SECRET_${k.uppercase()}"] = v
        }
        return env
    }

    private fun parseLine(line: String): DriverEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        return when (streamFormat) {
            FORMAT_RAW_TEXT -> DriverEvent.TokenDelta(line + "\n")
            FORMAT_CLAUDE_STREAM_JSON -> CliLineEventMapper.map(line)
            FORMAT_TENON_EVENT_STREAM -> parseTenonEventLine(trimmed) ?: CliLineEventMapper.map(line)
            else -> CliLineEventMapper.map(line) ?: DriverEvent.TokenDelta(line + "\n")
        }
    }

    private fun parseTenonEventLine(jsonLine: String): DriverEvent? {
        val obj = runCatching { JSON_PARSER.parseToJsonElement(jsonLine) }.getOrNull() as? JsonObject ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return null

        return when (type) {
            "thought" -> {
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: ""
                DriverEvent.ThoughtDelta(text)
            }
            "message" -> {
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: ""
                DriverEvent.TokenDelta(text)
            }
            "tool" -> {
                val toolName = (obj["tool"] as? JsonPrimitive)?.contentOrNull ?: "tool"
                val callId = (obj["callId"] as? JsonPrimitive)?.contentOrNull ?: "call-1"
                val inputJson = (obj["input"] as? JsonPrimitive)?.contentOrNull ?: ""
                DriverEvent.ToolCall(callId, toolName, inputJson)
            }
            "card" -> {
                val cardJson = (obj["cardJson"] as? JsonPrimitive)?.contentOrNull ?: jsonLine
                DriverEvent.Card(cardJson)
            }
            "artifact" -> {
                val path = (obj["path"] as? JsonPrimitive)?.contentOrNull ?: ""
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: File(path).name
                val bytes = (obj["bytes"] as? JsonPrimitive)?.longOrNull ?: 0L
                val mime = (obj["mime"] as? JsonPrimitive)?.contentOrNull ?: "application/octet-stream"
                val sha = (obj["sha256"] as? JsonPrimitive)?.contentOrNull ?: ""
                DriverEvent.Artifact(path, name, bytes, mime, sha)
            }
            "end" -> {
                DriverEvent.Finished(finishReason = "stop")
            }
            "error" -> {
                val msg = (obj["message"] as? JsonPrimitive)?.contentOrNull ?: "unspecified error"
                DriverEvent.Error(msg)
            }
            else -> null
        }
    }
}
