// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.PitfallLedger

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 避坑规约账本的判据：追加可读回、损坏行跳过、匹配按关键词计分、
 * 注入块是「每条一行约束」。空 pattern 恒匹配的语义也在钉。
 */
class PitfallLedgerTest {

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "pitfall-" + System.nanoTime())

    @Test
    fun appendThenLoadRoundTrips() {
        val dir = tempDir()
        assertTrue(PitfallLedger.append(dir, PitfallLedger.Pitfall("reject", "tests/", "Always run npm test before merging", 1_000L)))
        val loaded = PitfallLedger.load(dir)
        assertEquals(1, loaded.size)
        assertEquals("reject", loaded[0].source)
        assertEquals("tests/", loaded[0].pattern)
        assertEquals("Always run npm test before merging", loaded[0].rule)
        assertEquals(1_000L, loaded[0].createdAt)
        dir.deleteRecursively()
    }

    @Test
    fun corruptLinesAreSkippedNotFatal() {
        val dir = tempDir()
        val file = File(dir, PitfallLedger.FILE_NAME)
        file.parentFile.mkdirs()
        file.writeText(
            "{\"source\":\"heal\",\"pattern\":\"udp\",\"rule\":\"Keep frame budgets in sync\",\"createdAt\":5}\n" +
            "not json at all\n" +
            "\n" +
            "{\"source\":\"reject\",\"pattern\":\"\",\"rule\":\"No hardcoded zh\",\"createdAt\":9}\n",
        )
        val loaded = PitfallLedger.load(dir)
        assertEquals(2, loaded.size)
        assertEquals("udp", loaded[0].pattern)
        assertEquals("No hardcoded zh", loaded[1].rule)
        dir.deleteRecursively()
    }

    @Test
    fun selectMatchesByKeywordCountAndBreaksTiesByRecency() {
        val dir = tempDir()
        PitfallLedger.append(dir, PitfallLedger.Pitfall("reject", "transport udp", "Budget A", 1L))
        PitfallLedger.append(dir, PitfallLedger.Pitfall("heal", "transport", "Budget B", 2L))
        PitfallLedger.append(dir, PitfallLedger.Pitfall("heal", "ui theme", "Unrelated", 3L))
        PitfallLedger.append(dir, PitfallLedger.Pitfall("heal", "", "Always applies", 4L))
        val picked = PitfallLedger.selectFor(dir, "fix the transport udp pump")
        // transport udp 命中 2 词排最前；transport 1 词次之；恒匹配条目也生效；ui theme 0 分不进。
        assertEquals(listOf("Budget A", "Budget B", "Always applies"), picked.map { it.rule })
        dir.deleteRecursively()
    }

    @Test
    fun selectCappedAtMaxInjected() {
        val dir = tempDir()
        repeat(12) { i ->
            PitfallLedger.append(dir, PitfallLedger.Pitfall("heal", "core", "Rule " + i, i.toLong()))
        }
        assertEquals(PitfallLedger.MAX_INJECTED, PitfallLedger.selectFor(dir, "core work").size)
        dir.deleteRecursively()
    }

    @Test
    fun promptBlockFormatsConstraintsAndEmptyGivesNull() {
        val block = PitfallLedger.promptBlockFor(
            listOf(
                PitfallLedger.Pitfall("reject", "p", "Run npm test first", 1L),
                PitfallLedger.Pitfall("heal", "q", "Keep hunk under budget", 2L),
            ),
        )
        assertTrue(block!!.startsWith("Hard constraints from this repo's pitfall ledger:"))
        assertTrue(block.contains("- Run npm test first"))
        assertTrue(block.contains("- Keep hunk under budget"))
        assertNull(PitfallLedger.promptBlockFor(emptyList()))
    }

    @Test
    fun ruleIsTruncatedToMaxChars() {
        val dir = tempDir()
        val long = "x".repeat(PitfallLedger.MAX_RULE_CHARS + 500)
        PitfallLedger.append(dir, PitfallLedger.Pitfall("heal", "", long, 1L))
        assertEquals(PitfallLedger.MAX_RULE_CHARS, PitfallLedger.load(dir)[0].rule.length)
        dir.deleteRecursively()
    }
}
