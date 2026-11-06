// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ARP-8 的三条判据：**缓冲可读**、**输出不变**、**以后只能走门面**。
 *
 * 为什么三条都要有：这个改动本身不改变任何业务行为，摘掉任何一半也照样全绿 ——
 * - 只留 `println`（即摘掉环）⇒ 单测发现不了，用户拿到的诊断包少一节；
 * - 只留环（即改掉输出格式）⇒ 排障脚本与 `grep '\[verify\]'` 的肌肉记忆同时失效；
 * - 放着裸 `println` 不管 ⇒ 下一个人照抄最近的一处，几个月后又回到 60 处散落。
 */
class RelayLogTest {

    /** 判据 1：写进去的行能在缓冲里原样读回来（旧 → 新）。这就是 ARP-8 的验收点。 */
    @Test
    fun whatIsWrittenIsReadableBackInOrder() {
        RelayLog.clear()
        RelayLog.info("RELAY-TEST", "first")
        RelayLog.info("CHAIN", "second")

        val lines = RelayLog.lines()
        assertEquals(listOf("[RELAY-TEST] first", "[CHAIN] second"), lines)
    }

    /**
     * 判据 1 的后半：超过容量只留最后 [RelayLog.CAPACITY] 行。
     *
     * 断言写成「最早的滚掉了、最新的还在、长度正好等于容量」，而不是「第 6 行是 X」：
     * 这个 object 是进程级的，别的测试类可能同时在往里写。断言窗口而不是断言下标，
     * 才不会变成一条看心情红的用例。
     */
    @Test
    fun theRingKeepsOnlyTheMostRecentLines() {
        RelayLog.clear()
        val overflow = 5
        repeat(RelayLog.CAPACITY + overflow) { i ->
            RelayLog.info("RELAY-TEST", "ring-" + i)
        }

        val lines = RelayLog.lines()
        assertEquals(RelayLog.CAPACITY, lines.size, "环必须封顶在 CAPACITY")
        assertFalse(lines.contains("[RELAY-TEST] ring-0"), "最早的行该被滚掉，否则环没有上界")
        assertTrue(
            lines.contains("[RELAY-TEST] ring-" + (RelayLog.CAPACITY + overflow - 1)),
            "最后写进去的那行必须在 —— 滚错方向就是把现场丢在最需要它的时候",
        )
        assertTrue(lines.last().endsWith("ring-" + (RelayLog.CAPACITY + overflow - 1)), "顺序必须是旧 → 新")
    }

    /**
     * 判据 2：**stdout 那一行逐字不变**。
     *
     * 这一条守的是排障习惯：`[RELAY-INVOKE]` / `[CHAIN]` / `[verify]` 这些前缀被人
     * 在终端里 grep 了好几个月，改造不能顺手把它们换成新格式（哪怕是加个级别前缀）。
     */
    @Test
    fun stdoutStillCarriesTheBracketedTagVerbatim() {
        val original = System.out
        val captured = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(captured, true, "UTF-8"))
            RelayLog.info("RELAY-INVOKE", "high-risk lease DENIED (card c-1, cmd masked)")
        } finally {
            System.setOut(original)
        }

        val printed = captured.toString("UTF-8")
        assertTrue(
            printed.contains("[RELAY-INVOKE] high-risk lease DENIED (card c-1, cmd masked)"),
            "stdout 必须逐字是 `[TAG] message`；实际打印：" + printed,
        )
    }

    /** 清空要真的清空 —— 否则「上一个用例的行」会出现在用户导出的诊断包里。 */
    @Test
    fun clearEmptiesTheBuffer() {
        RelayLog.info("RELAY-TEST", "something")
        RelayLog.clear()
        assertTrue(RelayLog.lines().isEmpty(), "clear 之后必须一行不剩")
    }

    /**
     * 判据 3：**编排层生产代码里不许再有裸 `println`**。
     *
     * 唯一的例外是 `RelayLog.kt` 自己 —— 它要 `println` 才不会把 stdout 那一路弄丢。
     * 先剥注释再匹配：这个文件族里到处是写着「从前这里 println 到 /dev/null」的说明，
     * 不剥注释的守卫会被自己的注释绊倒（AGENTS §4）。
     */
    @Test
    fun orchestrationHasExactlyOnePrintlnSite() {
        val root = "orchestration/src/main"
        val files = GuardSourceScan.kotlinFiles(root)
        assertTrue(files.size >= 10, "只解析出 " + files.size + " 个 .kt —— 路径过期了，守卫会空转")
        val offenders = files
            .filter { it.substringAfterLast('/') != "RelayLog.kt" }
            .filter { path ->
                val live = GuardSourceScan.stripComments(GuardSourceScan.read(path))
                live.contains("println(") || live.contains("System.out.print")
            }
        assertTrue(
            offenders.isEmpty(),
            "编排层的日志必须走 RelayLog（诊断包要能导出「刚才发生了什么」）；这些文件又写回去了：" +
                offenders.joinToString("、"),
        )
    }
}
