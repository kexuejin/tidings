// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [BoundedLineReader] 的判据。
 *
 * 这一组存在的理由：仓库此前三处读循环都写成「先 `useLines` 读出整行、再比
 * `line.length > MAX_LINE_CHARS`」——判据成立的时候，1 GB 的行**已经在堆里了**。
 * 所以判据不能落在「行有多长」，只能落在「怎么读」：本类累积到上限就停止累积，
 * 该行剩余字节边读边丢。下面每条都在钉这个语义。
 */
class BoundedLineReaderTest {

    private fun reader(input: String, limit: Int = DriverLineLimits.MAX_LINE_CHARS) =
        BoundedLineReader(input.reader(), limit)

    private fun textOf(line: BoundedLine): String {
        assertIs<BoundedLine.Text>(line, "期望一条完整行，实际是 $line")
        return line.value
    }

    @Test
    fun normalLinesAreReturnedVerbatim() {
        val r = reader("first\nsecond\n")
        assertEquals("first", textOf(r.readLine()))
        assertEquals("second", textOf(r.readLine()))
        assertEquals(BoundedLine.Eof, r.readLine())
    }

    @Test
    fun lineExactlyAtTheLimitIsAccepted() {
        val r = reader("abcde\nnext\n", limit = 5)
        assertEquals(
            "abcde",
            textOf(r.readLine()),
            "刚好等于上限必须放行——判据是「超过」而不是「达到」",
        )
        assertEquals("next", textOf(r.readLine()))
    }

    @Test
    fun oneCharOverTheLimitIsTooLong() {
        val r = reader("abcdef\n", limit = 5)
        assertEquals(BoundedLine.TooLong, r.readLine())
    }

    /**
     * 最关键的一条：超长行之后流必须仍然同步。
     *
     * 若不把该行剩余字节读完就返回，后半截会被当成下一条「新行」的开头，后续每条
     * 都被拼歪——症状是「一超限之后所有事件都解析失败」，比直接失败更难查。
     */
    @Test
    fun streamStaysInSyncAfterAnOverlongLine() {
        val r = reader("OVEROVEROVER\nok\n", limit = 4)
        assertEquals(BoundedLine.TooLong, r.readLine())
        assertEquals("ok", textOf(r.readLine()), "超长行之后的第一条完整行必须原样读出")
        assertEquals(BoundedLine.Eof, r.readLine())
    }

    @Test
    fun lastLineWithoutTrailingNewlineIsStillReturned() {
        val r = reader("a\nb")
        assertEquals("a", textOf(r.readLine()))
        assertEquals("b", textOf(r.readLine()), "末尾没有换行的最后一行也要读出来")
        assertEquals(BoundedLine.Eof, r.readLine())
    }

    /**
     * `\r\n` 与裸 `\r` 都算行终止符。
     *
     * 裸 `\r` 这条不是洁癖：curl / npm 的进度条用 `\r` 原地刷新，若只认 `\n`，一整段
     * 进度刷新会被拼成一条超长「行」，于是误判 TooLong 并杀掉一个完全正常的进程 ——
     * 「为了防 OOM 引入误杀」比原缺陷更糟。
     */
    @Test
    fun carriageReturnTerminatesALineToo() {
        val r = reader("crlf\r\nbare\rtail\n")
        assertEquals("crlf", textOf(r.readLine()))
        assertEquals("bare", textOf(r.readLine()))
        assertEquals("tail", textOf(r.readLine()))
        assertEquals(BoundedLine.Eof, r.readLine())
    }

    @Test
    fun emptyStreamIsEofImmediately() {
        assertEquals(BoundedLine.Eof, reader("").readLine())
    }

    /** 空行是合法的空字符串，不是 EOF、也不是 TooLong。 */
    @Test
    fun blankLineIsAnEmptyTextNotEof() {
        val r = reader("\n\nx\n")
        assertEquals("", textOf(r.readLine()))
        assertEquals("", textOf(r.readLine()))
        assertEquals("x", textOf(r.readLine()))
    }
}
