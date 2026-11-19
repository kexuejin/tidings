// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BoundedOutputTail] 与 [CliSubprocessDriver.stderrTailOf] 的判据。
 *
 * 缺陷背景：裸 `StringBuilder` 的 outputTail 没有总量上限——单行有界（[BoundedLineReader]）
 * 挡不住几百万行短输出，长任务把宿主堆吃光，症状是「进程被杀」而非「输出超限」；
 * 终局错误那句 `tail.lines()` 又把整串复制成 List，判「只取 3 行」时峰值已经付过。
 * 这里钉的是「怎么存、怎么取」，不是「存完再看长度」。
 */
class BoundedOutputTailTest {

    @Test
    fun totalLengthNeverExceedsLimit() {
        val tail = BoundedOutputTail(limit = 128)
        repeat(10_000) { tail.appendLine("line-$it") }
        assertTrue(
            tail.toString().length <= 128,
            "写一万行后总量必须仍 ≤ 128，实际 ${tail.toString().length} —— 这是防 OOM 的核心判据",
        )
    }

    @Test
    fun keepsTheMostRecentTailNotTheHead() {
        val tail = BoundedOutputTail(limit = 64)
        repeat(10_000) { tail.appendLine("line-$it") }
        val kept = tail.toString()
        assertTrue("line-9999" in kept, "最近的行必须保留（瞬时判别与 stderr 尾巴都看尾部）")
        assertTrue("line-0\n" !in kept, "最早的行必须被丢掉，否则总量无界")
    }

    @Test
    fun stderrTailTakesLastThreeNonBlankLinesInOrder() {
        val tail = "warn\n\nerr-A\nerr-B\nerr-C\n"
        assertEquals(" ⏎ err-A ⏎ err-B ⏎ err-C", CliSubprocessDriver.stderrTailOf(tail))
    }

    @Test
    fun stderrTailHonorsThe200CharPerLineCap() {
        val tail = "x".repeat(500) + "\n"
        val result = CliSubprocessDriver.stderrTailOf(tail)
        assertTrue(result.length <= 3 + 200, "单行裁到 200 字，实际 ${result.length}")
        assertTrue(result.endsWith("x".repeat(200)), "保留的是每行前 200 字")
    }

    @Test
    fun stderrTailOnEmptyAndTruncatedHeadIsHonest() {
        assertEquals("", CliSubprocessDriver.stderrTailOf(""))
        assertEquals("", CliSubprocessDriver.stderrTailOf("\n\n"))
        // 缓冲从半行开始是常态（总量裁剪按字符不按行）；半行仍是一条如实诊断行。
        assertEquals(" ⏎ partial", CliSubprocessDriver.stderrTailOf("partial"))
    }

    @Test
    fun transientMarkersSurviveWithinTheBoundedTail() {
        // 判别语义不因有界化而丢：失败标记出现在尾部时仍被 toString() 带出。
        val tail = BoundedOutputTail(limit = 256)
        repeat(10_000) { tail.appendLine("filler-$it") }
        tail.appendLine("STREAM_CLOSED")
        assertTrue(
            CliSubprocessDriver.defaultTransientOf(1, tail.toString()),
            "尾部失败标记必须仍在判别可见范围内",
        )
    }
}
