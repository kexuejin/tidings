// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 飞行记录仪模型与存证规范（§8.1 飞行记录仪规范）。
 *
 * 核心安全边界：
 * 1. 唯一写入方：由宿主（Host）单向客观记录执行事实，严禁交由模型自述修改；
 * 2. 证据可信度分级：verifiedBy 严格区分宿主真实退出码与智能体自述；
 * 3. 确定性脱敏：落盘前自动剥离个人根路径与认证 Token；
 * 4. 物理上限：单条记录严格限制在 64 KiB 以内，超额阻断并保留截断诊断。
 */
@Serializable
data class FlightRecord(
    @SerialName("\$schema")
    val schema: String = SCHEMA_V1,
    val taskId: String,
    val targetDirectory: String,
    val metrics: FlightMetrics,
    val phases: List<FlightPhase> = emptyList(),
    val hostEvidence: FlightEvidence,
    /** 超 64 KiB 时丢弃可选字段并置 true；JSON 始终可解析。 */
    val truncated: Boolean = false,
) {
    companion object {
        const val SCHEMA_V1: String = "tenon://eval/flight-record/v1"
        const val MAX_RECORD_BYTES: Int = 65536 // 64 KiB
        const val VERIFIED_BY_HOST_PROCESS: String = "host_process_exit"
        const val VERIFIED_BY_TOOL_CHANNEL: String = "host_mediated_tool_channel"
        const val VERIFIED_BY_AGENT_SELF_REPORT: String = "agent_self_report"

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val TOKEN_REGEX = Regex(
            """(?i)(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|""" +
                """sk-[A-Za-z0-9_\-]{16,}|AKIA[0-9A-Z]{16}|Bearer\s+[A-Za-z0-9_\-\.]+|token=[A-Za-z0-9_\-]+)""",
        )

        /** 外接卷 / 其它用户 home / 本机 home：统一脱敏成 `~`，不保留可识别身份的前缀。 */
        private val ABSOLUTE_HOME_REGEX = Regex("""(?<![\w.-])(?:/Users|/home|/Volumes)/[^/\s]+""")

        /**
         * 确定性路径与 Token 脱敏。
         */
        fun sanitize(text: String, userHome: String = System.getProperty("user.home").orEmpty()): String {
            var result = text
            if (userHome.isNotBlank()) {
                result = result.replace(userHome, "~")
            }
            result = ABSOLUTE_HOME_REGEX.replace(result, "~")
            result = TOKEN_REGEX.replace(result) { match ->
                val prefix = match.value.take(4)
                "$prefix***"
            }
            return result
        }

        fun toJson(record: FlightRecord, userHome: String = System.getProperty("user.home").orEmpty()): String {
            val sanitizedEvidence = record.hostEvidence.copy(
                testCommand = record.hostEvidence.testCommand?.let { sanitize(it, userHome) },
            )
            val sanitized = record.copy(
                targetDirectory = sanitize(record.targetDirectory, userHome),
                hostEvidence = sanitizedEvidence,
            )
            val encoded = json.encodeToString(sanitized)
            if (encoded.encodeToByteArray().size <= MAX_RECORD_BYTES) return encoded

            // 超限：先丢可选字段再编码；绝不把 JSON 从中间截断（那会产出无法解析的文档）。
            val compact = sanitized.copy(
                phases = emptyList(),
                hostEvidence = sanitizedEvidence.copy(testCommand = null),
                truncated = true,
            )
            val compactJson = json.encodeToString(compact)
            if (compactJson.encodeToByteArray().size <= MAX_RECORD_BYTES) return compactJson

            // 最后一道：只保留最小事实集（taskId/目标/指标/证据分级）。
            return json.encodeToString(
                compact.copy(
                    targetDirectory = compact.targetDirectory.take(240),
                    metrics = compact.metrics.copy(patchSizeBytes = 0L),
                ),
            )
        }
    }
}

@Serializable
data class FlightMetrics(
    val totalDurationMs: Long,
    val selfHealingRounds: Int = 0,
    val toolInvocationCount: Int = 0,
    /** null = 宿主没有拿到真实退出码；不要用 0 冒充通过。 */
    val exitCode: Int? = null,
    val patchSizeBytes: Long = 0L,
)

@Serializable
data class FlightPhase(
    val phase: String,
    val durationMs: Long,
    val status: String,
)

@Serializable
data class FlightEvidence(
    val testCommand: String? = null,
    val finalExitCode: Int? = null,
    val verifiedBy: String,
)

/**
 * 宿主飞行记录持久化落地与保留上限治理。
 */
object FlightRecorder {

    private const val MAX_RETAINED_RECORDS = 100

    fun record(
        record: FlightRecord,
        outputDir: File,
        userHome: String = System.getProperty("user.home").orEmpty(),
    ): File? {
        return try {
            outputDir.mkdirs()
            val file = File(outputDir, "${record.taskId}.json")
            val content = FlightRecord.toJson(record, userHome)
            file.writeText(content)
            pruneStaleRecords(outputDir)
            file
        } catch (t: Throwable) {
            // 写失败必须看得见：静默吞掉会让「没有飞行记录」被读成「任务没有发生」。
            RelayLog.info("FLIGHT", "flight record write failed for ${record.taskId}: ${t.message ?: t::class.simpleName}")
            null
        }
    }

    private fun pruneStaleRecords(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size > MAX_RETAINED_RECORDS) {
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_RETAINED_RECORDS)
                .forEach { it.delete() }
        }
    }
}
