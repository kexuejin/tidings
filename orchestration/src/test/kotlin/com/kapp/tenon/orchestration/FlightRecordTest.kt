// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlightRecordTest {

    private val FAKE_HOME = "/Users/testdeveloper"

    @Test
    fun sanitizationMasksHomeDirectoryAndTokens() {
        val fakeHome = "/Users/testdeveloper"
        val input = "Target file at /Users/testdeveloper/workspace/app.kt with auth ghp_123456789012345678901234567890123456"
        val sanitized = FlightRecord.sanitize(input, userHome = fakeHome)

        assertFalse(sanitized.contains(fakeHome), "Home directory must be masked")
        assertTrue(sanitized.contains("~/workspace/app.kt"))
        assertFalse(sanitized.contains("123456789012345678901234567890123456"), "Token must be masked")
        assertTrue(sanitized.contains("ghp_***"))
    }

    @Test
    fun serializationProducesConformingJson() {
        val record = FlightRecord(
            taskId = "task-eval-42",
            targetDirectory = "/Users/testdeveloper/repo",
            metrics = FlightMetrics(
                totalDurationMs = 12000L,
                selfHealingRounds = 1,
                toolInvocationCount = 3,
                exitCode = 0,
                patchSizeBytes = 512L,
            ),
            phases = listOf(
                FlightPhase(phase = "survey", durationMs = 2000L, status = "ok"),
                FlightPhase(phase = "self_test", durationMs = 6000L, status = "healed"),
            ),
            hostEvidence = FlightEvidence(
                testCommand = "./gradlew test",
                finalExitCode = 0,
                verifiedBy = FlightRecord.VERIFIED_BY_HOST_PROCESS,
            ),
        )

        val json = FlightRecord.toJson(record, userHome = "/Users/testdeveloper")
        assertTrue(json.contains("\"\$schema\": \"tenon://eval/flight-record/v1\""))
        assertTrue(json.contains("\"targetDirectory\": \"~/repo\""))
        assertTrue(json.contains("\"verifiedBy\": \"host_process_exit\""))
        assertTrue(json.contains("\"selfHealingRounds\": 1"))
        assertTrue(json.contains("\"verifiedBy\": \"host_process_exit\""))
    }

    @Test
    fun oversizedRecordIsCompactedButStaysValidJson() {
        val record = FlightRecord(
            taskId = "task-huge",
            targetDirectory = "/Users/testdeveloper/repo",
            metrics = FlightMetrics(totalDurationMs = 1L, patchSizeBytes = 12L),
            phases = (1..4000).map { FlightPhase("phase-$it", 1L, "ok") },
            hostEvidence = FlightEvidence(
                testCommand = "x".repeat(20000),
                finalExitCode = null,
                verifiedBy = FlightRecord.VERIFIED_BY_AGENT_SELF_REPORT,
            ),
        )
        val json = FlightRecord.toJson(record, userHome = "/Users/testdeveloper")
        assertTrue(json.encodeToByteArray().size <= FlightRecord.MAX_RECORD_BYTES)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<FlightRecord>(json)
        assertTrue(decoded.truncated, "超限记录必须标 truncated，而不是写出非法 JSON")
    }

    @Test
    fun sanitizeAlsoMasksExternalVolumesAndTokens() {
        val sanitized = FlightRecord.sanitize(
            // 占位卷名保持 /Volumes 前缀形状：这条测试钉的是「脱敏器真会掩掉外接卷」，
            // 输入若是掩完之后的形状，脱敏器就无物可脱，断言空转（脚本替换时
            // replace-text 误伤此处，真机…不，本机跑测试时抓到）。
            "at /Volumes/<external-volume>/source/x and /home/other/y with ghp_123456789012345678901234567890123456",
            userHome = "/Users/testdeveloper",
        )
        assertFalse(sanitized.contains("<external-volume>"), "外接卷路径也要脱敏")
        assertFalse(sanitized.contains("/home/other"), "其它用户 home 也要脱敏")
        assertFalse(sanitized.contains("123456789012345678901234567890123456"))
    }

    @Test
    fun flightRecorderWritesAndRotatesFiles() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "flight-record-test-" + System.nanoTime())
        try {
            val record = FlightRecord(
                taskId = "task-record-1",
                targetDirectory = "/tmp/repo",
                metrics = FlightMetrics(totalDurationMs = 1000L),
                hostEvidence = FlightEvidence(verifiedBy = FlightRecord.VERIFIED_BY_HOST_PROCESS),
            )

            val written = FlightRecorder.record(record, tmpDir)
            assertTrue(written != null && written.isFile)
            assertTrue(written.readText().contains("task-record-1"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun recordsOver64KiBareTruncatedWithDiagnostic() {
        val bigPhases = (1..80).map { i ->
            FlightPhase(phase = "phase-" + i, durationMs = 1000L, status = "x".repeat(2000))
        }
        val record = FlightRecord(
            taskId = "task-big",
            targetDirectory = "/tmp/repo",
            metrics = FlightMetrics(totalDurationMs = 1L),
            phases = bigPhases,
            hostEvidence = FlightEvidence(verifiedBy = FlightRecord.VERIFIED_BY_HOST_PROCESS),
        )

        val json = FlightRecord.toJson(record, userHome = FAKE_HOME)
        val bytes = json.encodeToByteArray()

        // toJson 返回的是截断后的产物,永远 ≤ 上限 —— 夹具是否真的超限要看输入体量
        val rawBodyChars = record.phases.sumOf { it.phase.length + it.status.length }
        assertTrue(
            rawBodyChars > FlightRecord.MAX_RECORD_BYTES,
            "夹具体量必须真的超过 64KiB（实际 " + rawBodyChars + " 字符），否则这条用例没在测东西",
        )
        // 超限不再是「把 JSON 从中间截断」：必须降级成可解析的紧凑记录并标 truncated。
        assertTrue(
            bytes.size <= FlightRecord.MAX_RECORD_BYTES,
            "紧凑后不得超过物理上限，实际 " + bytes.size + " 字节",
        )
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<FlightRecord>(json)
        assertTrue(decoded.truncated, "超限记录必须标 truncated（合法 JSON），不是非法半截文档")
    }

    @Test
    fun recordsThatStayOverTheCapAfterDroppingOptionalsKeepTheMinimalFactSet() {
        // 丢掉 phases/testCommand 之后还超限（超长目标路径）⇒ 二级兜底：
        // 目标路径截到 240、丢可再生的 patchSizeBytes，但 taskId 与证据分级必须保住。
        val bigPhases = (1..80).map { i ->
            FlightPhase(phase = "phase-" + i, durationMs = 1000L, status = "y".repeat(2000))
        }
        val record = FlightRecord(
            taskId = "task-huge",
            // 不用 /Volumes|/Users|/home 前缀 —— 那会被脱敏先压成 ~，轮不到 240 截断
            targetDirectory = "/opt/" + "d".repeat(100_000),
            metrics = FlightMetrics(totalDurationMs = 1L, patchSizeBytes = 9_999L),
            phases = bigPhases,
            hostEvidence = FlightEvidence(verifiedBy = FlightRecord.VERIFIED_BY_HOST_PROCESS),
        )

        val json = FlightRecord.toJson(record, userHome = FAKE_HOME)
        assertTrue(
            json.encodeToByteArray().size <= FlightRecord.MAX_RECORD_BYTES,
            "二级兜底后必须压回物理上限内",
        )

        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<FlightRecord>(json)
        assertTrue(decoded.truncated)
        assertEquals(240, decoded.targetDirectory.length, "超长目标路径截到 240 字符")
        assertEquals(0L, decoded.metrics.patchSizeBytes, "体量指标是可再生的，兜底时丢弃")
        assertEquals("task-huge", decoded.taskId, "taskId 是最小事实集，不许丢")
        assertEquals(FlightRecord.VERIFIED_BY_HOST_PROCESS, decoded.hostEvidence.verifiedBy, "证据分级不许丢")
    }

    @Test
    fun recordsUnderTheCapAreNotTruncated() {
        val record = FlightRecord(
            taskId = "task-small",
            targetDirectory = "/tmp/repo",
            metrics = FlightMetrics(totalDurationMs = 1L),
            hostEvidence = FlightEvidence(verifiedBy = FlightRecord.VERIFIED_BY_HOST_PROCESS),
        )
        val json = FlightRecord.toJson(record, userHome = FAKE_HOME)
        assertFalse(json.contains("[TRUNCATED_OVER_64KB]"), "未超限的记录一个字节都不许动")
    }

    @Test
    fun recorderKeepsAtMostOneHundredNewestRecords() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "flight-record-rotate-" + System.nanoTime())
        try {
            repeat(105) { i ->
                val record = FlightRecord(
                    taskId = "task-rotate-" + i.toString().padStart(3, '0'),
                    targetDirectory = "/tmp/repo",
                    metrics = FlightMetrics(totalDurationMs = 1000L),
                    hostEvidence = FlightEvidence(verifiedBy = FlightRecord.VERIFIED_BY_HOST_PROCESS),
                )
                val written = FlightRecorder.record(record, tmpDir, userHome = FAKE_HOME)
                assertNotNull(written, "第 " + i + " 条记录写入不能失败")
                // 显式错开 mtime：文件系统 mtime 粒度可能比写入间隔粗，
                // 不错开的话「删哪 5 条」就是任意的，断言不了「最旧的先走」。
                written.setLastModified(1_000_000_000L + i * 60_000L)
            }

            val remaining = tmpDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            assertEquals(100, remaining.size, "滚动保留上限是 100 条")
            assertTrue(remaining.none { it.name == "task-rotate-000.json" }, "最旧的记录应被清理")
            assertTrue(remaining.none { it.name == "task-rotate-004.json" }, "最旧的 5 条都应被清理")
            assertTrue(remaining.any { it.name == "task-rotate-104.json" }, "最新的记录必须保留")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun recorderReturnsNullWhenPersistenceFails() {
        // 父路径是一个普通文件 ⇒ mkdirs 失败、写文件抛 IOException —— 必须被吞成 null
        val block = File(System.getProperty("java.io.tmpdir"), "flight-record-block-" + System.nanoTime())
        block.mkdirs()
        try {
            val notADir = File(block, "not-a-dir.txt").apply { writeText("i am a file") }
            val outputDir = File(notADir, "records")
            val record = FlightRecord(
                taskId = "task-doomed",
                targetDirectory = "/tmp/repo",
                metrics = FlightMetrics(totalDurationMs = 1L),
                hostEvidence = FlightEvidence(verifiedBy = FlightRecord.VERIFIED_BY_HOST_PROCESS),
            )

            val written = FlightRecorder.record(record, outputDir, userHome = FAKE_HOME)
            assertNull(written, "持久化失败必须返回 null（产物可见性降级），不许抛异常")
        } finally {
            block.deleteRecursively()
        }
    }

    @Test
    fun sanitizeMasksAllTokenFormsCaseInsensitively() {
        val ghpToken = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        assertTrue(ghpToken.length == 40, "夹具 ghp token 应为 4 前缀 + 36 字符")
        val ghpUpper = "GHP_" + "Z9Y8x7W6v5U4t3S2r1Q0p9O8n7M6b5V4c3X2"
        val input = "auth1=$ghpToken auth2=$ghpUpper curl -H 'Authorization: Bearer eyJhbGciOiJIz1.2.3' " +
            "https://ci.example.com/upload?token=abc_def-123"

        val sanitized = FlightRecord.sanitize(input, userHome = FAKE_HOME)

        assertFalse(sanitized.contains("a1B2c3D4e5F6"), "ghp_ token 主体必须被剥掉")
        assertFalse(sanitized.contains("Z9Y8x7W6v5U4"), "大写 GHP_ 前缀同样要剥（正则不区分大小写）")
        assertTrue(sanitized.contains("ghp_***"))
        assertTrue(sanitized.contains("GHP_***"))
        assertFalse(sanitized.contains("eyJhbGciOiJIz1"), "Bearer token 主体必须被剥掉")
        assertTrue(sanitized.contains("Bear***"))
        assertFalse(sanitized.contains("abc_def-123"), "token= 形态的凭据必须被剥掉")
        assertTrue(sanitized.contains("toke***"))
    }

    @Test
    fun sanitizeIsDeterministicAndSurvivesBlankUserHome() {
        val input = "call ghp_123456789012345678901234567890123456 twice ghp_123456789012345678901234567890123456"

        val once = FlightRecord.sanitize(input, userHome = FAKE_HOME)
        val twice = FlightRecord.sanitize(input, userHome = FAKE_HOME)
        assertEquals(once, twice, "同一输入必须产出同一输出（确定性脱敏）")
        assertEquals(2, Regex("ghp_\\*\\*\\*").findAll(once).count(), "多次出现的 token 都要剥")

        val withoutHome = FlightRecord.sanitize("/etc/hosts ghp_123456789012345678901234567890123456", userHome = "")
        assertTrue(withoutHome.contains("/etc/hosts"), "空 userHome 时跳过家目录替换，其余内容原样")
        assertTrue(withoutHome.contains("ghp_***"), "空 userHome 不影响 token 剥离")
    }
}
