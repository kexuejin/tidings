// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 盲区巡检（Step 6，只记录）的判据：
 * 1. 记录按月落 JSONL，行可回读（将来导诊断包就是读它）；
 * 2. 聚合只数时间窗内的行，旧账不混进来；
 * 3. **观测面永远不抛** —— 目录建不出来、文件烂掉，记录与聚合都吞掉。
 */
class BlindSpotPatrolTest {

    private fun tempDir(): File = Files.createTempDirectory("blindspot").toFile()

    @Test
    fun recordedEntriesRoundTripThroughTheMonthlyFile() {
        val dir = tempDir()
        val entry = BlindSpotPatrol.Entry(at = 1_799_000_000_000, kind = BlindSpotPatrol.KIND_BLOCKED, title = "修登录", detail = "exited 1 — boom")
        BlindSpotPatrol.record(dir, entry)

        val file = assertNotNull(dir.listFiles()?.firstOrNull(), "记录必须落盘")
        assertTrue(file.name.endsWith(".jsonl"), "按月 JSONL：${file.name}")
        val back = assertNotNull(BlindSpotPatrol.entryOf(file.readLines().single()))
        assertEquals(entry.kind, back.kind)
        assertEquals(entry.title, back.title)
        assertEquals(entry.detail, back.detail)
        assertEquals(entry.at, back.at)
    }

    @Test
    fun entriesLandInTheFileOfTheirOwnMonth() {
        val dir = tempDir()
        // 2026-11-03 (GMT+8 本地时区语义由 Calendar 决定，这里只要求两个不同月份各归各的文件)
        val nov = BlindSpotPatrol.Entry(at = 1_799_000_000_000L, kind = "blocked", title = null, detail = null)
        val dec = BlindSpotPatrol.Entry(at = 1_799_000_000_000L + 31L * 24 * 3_600_000, kind = "blocked", title = null, detail = null)
        BlindSpotPatrol.record(dir, nov)
        BlindSpotPatrol.record(dir, dec)
        assertTrue(dir.listFiles()!!.size >= 2, "跨月记录要分文件：${dir.listFiles()?.map { it.name }}")
    }

    @Test
    fun summarizeCountsOnlyEntriesInsideTheWindow() {
        val dir = tempDir()
        // 合成时间：本用例只验证「窗口内计数」这一纯函数语义，不读这台机器的时钟（env 门禁）。
        val now = 1_799_000_000_000L
        BlindSpotPatrol.record(dir, BlindSpotPatrol.Entry(now - 1_000, "blocked", "t", "d"))
        BlindSpotPatrol.record(dir, BlindSpotPatrol.Entry(now - 2_000, "blocked", "t", "d"))
        BlindSpotPatrol.record(dir, BlindSpotPatrol.Entry(now - 3_000, BlindSpotPatrol.KIND_DELEGATION_REJECTED, "t", "d"))
        BlindSpotPatrol.record(dir, BlindSpotPatrol.Entry(now - 48 * 3_600_000L, "blocked", "两天前", "d"))

        val counts = BlindSpotPatrol.summarize(dir, now - 24 * 3_600_000L)
        assertEquals(2, counts["blocked"], "窗外的旧账不许混进来：$counts")
        assertEquals(1, counts[BlindSpotPatrol.KIND_DELEGATION_REJECTED])
    }

    @Test
    fun aBrokenStoreNeverThrowsAndNeverCounts() {
        val dir = tempDir()
        // 烂行、空行、非 JSON：跳过，不崩。
        dir.resolve("2026-11.jsonl").writeText("{broken\n\n{\"at\":\"x\",\"kind\":\"blocked\"}\n{\"kind\":\"blocked\"}\n")
        assertEquals(emptyMap(), BlindSpotPatrol.summarize(dir, 0))
        // 目录建不出来：记录吞掉。
        val blocked = File(dir, "file.jsonl")
        blocked.createNewText()
        BlindSpotPatrol.record(blocked, BlindSpotPatrol.Entry(1L, "blocked", null, null))
        assertTrue(true, "走到这里 = 没抛")
    }
}

/** 测试辅助：造一个「路径被文件占用」的目录，让 mkdirs 必然失败。 */
private fun File.createNewText(): File {
    writeText("i am a file, not a dir")
    return this
}
