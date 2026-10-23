// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 落盘去重表的护栏。
 *
 * 这个文件存在的理由只有一条：**重启之后对方重放同一帧，不能再被当成新消息收一次。**
 * 所以每一条"落盘了"的用例都配一条"换个实例重新打开还认得"的断言 —— 只在同一个实例上
 * 断言 `has`，内存实现也能全绿，那测的就不是落盘。
 *
 * 另一半是**行格式的输入校验**：`peerId` / `msgId` 都是对端发来的字符串，没有格式校验。
 * 不转义的话，对端只要发一个带换行的 id 就能往日志里插一整行伪造记录，把去重表污染成
 * "某些消息永远收不到"。见 [forgedLineFromAHostileIdCannotSurviveAReload]。
 *
 * 放在 commonTest 而不是 jvmTest：`createTempDir` 三个平台都有实现，JVM 与 iOS 都能跑，
 * 而这条落盘逻辑正是"平台文件语义不一样"最容易出事的地方。
 */
class FileDedupStoreTest {

    private val dirs = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        dirs.forEach { removeRecursively(it) }
        dirs.clear()
    }

    private fun newLogPath(): String = dedupLogPath(createTempDir("relay-dedup").also { dirs += it })

    // ------------------------------------------------------------------ 落盘与恢复

    @Test
    fun aReplayedFrameIsStillRecognisedAfterARestart() {
        val path = newLogPath()
        FileDedupStore(path).add("msg-1", "peer-a", NOW)

        val afterRestart = FileDedupStore(path)
        assertTrue(afterRestart.has("msg-1", "peer-a"), "重启后必须还认得这一帧，否则会重复投递")
    }

    @Test
    fun afreshStoreOnAnEmptyPathKnowsNothing() {
        val store = FileDedupStore(newLogPath())
        assertFalse(store.has("msg-1", "peer-a"))
    }

    @Test
    fun theSameMsgIdForAnotherPeerIsADifferentEntry() {
        // 队列与去重都以 (msg_id, peer_id) 为复合键：同一条消息发给两个 peer 是两件事。
        val path = newLogPath()
        FileDedupStore(path).add("msg-1", "peer-a", NOW)

        val afterRestart = FileDedupStore(path)
        assertTrue(afterRestart.has("msg-1", "peer-a"))
        assertFalse(afterRestart.has("msg-1", "peer-b"), "换个 peer 就是另一条，不能串")
    }

    @Test
    fun manyEntriesSurviveAndTheLogKeepsGrowing() {
        val path = newLogPath()
        val store = FileDedupStore(path, compactEvery = Int.MAX_VALUE)
        repeat(50) { store.add("msg-$it", "peer-a", NOW + it) }

        val afterRestart = FileDedupStore(path)
        for (i in 0 until 50) {
            assertTrue(afterRestart.has("msg-$i", "peer-a"), "第 $i 条应该还在")
        }
        // 每次 add 都是一次追加，所以行数就是条数 —— 没有把整份重写 50 次。
        assertEquals(50, readLog(path).trimEnd('\n').split('\n').size)
    }

    // ------------------------------------------------------------------ 剪枝

    @Test
    fun pruneDropsExpiredEntriesFromMemoryAndFromDisk() {
        val path = newLogPath()
        val store = FileDedupStore(path)
        store.add("old", "peer-a", NOW - 10_000)
        store.add("fresh", "peer-a", NOW)

        assertEquals(1, store.prune(NOW, ttl = 5_000))

        val afterRestart = FileDedupStore(path)
        assertFalse(afterRestart.has("old", "peer-a"), "过期的那条不该从磁盘上复活")
        assertTrue(afterRestart.has("fresh", "peer-a"), "没到期的必须留着")
    }

    @Test
    fun pruneRewritesTheFileInsteadOfOnlyForgettingInMemory() {
        val path = newLogPath()
        val store = FileDedupStore(path)
        repeat(20) { store.add("old-$it", "peer-a", NOW - 10_000) }
        store.add("fresh", "peer-a", NOW)

        store.prune(NOW, ttl = 5_000)

        // 磁盘上只剩一条：剪枝如果只动内存，这个文件会一直长下去。
        assertEquals(listOf("fresh"), entryIdsInLog(path))
    }

    // ------------------------------------------------------------------ 行格式是输入校验

    @Test
    fun forgedLineFromAHostileIdCannotSurviveAReload() {
        val path = newLogPath()
        val hostile = "\n$NOW\tpeer-b\tforged"
        FileDedupStore(path).add(hostile, "peer-a", NOW)

        val afterRestart = FileDedupStore(path)
        assertTrue(afterRestart.has(hostile, "peer-a"), "带换行的 id 本身还是要能去重")
        assertFalse(
            afterRestart.has("forged", "peer-b"),
            "对端用一个带换行的 id 就能伪造出一整行记录 —— 不转义就会这样",
        )
    }

    @Test
    fun idsWithTabsAndBackslashesRoundTripExactly() {
        val path = newLogPath()
        val ids = listOf("a\tb", "c\\d", "e\r\nf", "带中文的 id", "\\t 不是制表符")
        val store = FileDedupStore(path)
        ids.forEachIndexed { i, id -> store.add(id, "peer-$i", NOW) }

        val afterRestart = FileDedupStore(path)
        ids.forEachIndexed { i, id ->
            assertTrue(afterRestart.has(id, "peer-$i"), "「$id」应该原样往返")
        }
        assertFalse(afterRestart.has("a", "peer-0"), "转义不能把 id 截断成前半截")
    }

    // ------------------------------------------------------------------ 坏数据不拦路

    @Test
    fun garbageLinesAreSkippedAndGoodOnesStillLoad() {
        val path = newLogPath()
        writeLog(path, "这不是一行记录\n\n$NOW\tpeer-a\tmsg-1\n没有制表符\n$NOW\tpeer-b\t坏\\x转义\n")

        val store = FileDedupStore(path)
        assertTrue(store.has("msg-1", "peer-a"), "好行必须读得回来")
        assertFalse(store.has("坏\\x转义", "peer-b"), "认不出的转义序列不许猜")
    }

    @Test
    fun duplicateLinesCollapseToTheLaterTimestamp() {
        val path = newLogPath()
        writeLog(path, "${NOW - 1}\tpeer-a\tmsg-1\n${NOW}\tpeer-a\tmsg-1\n")

        val store = FileDedupStore(path)
        assertTrue(store.has("msg-1", "peer-a"))
        // ttl=1 让 cutoff 正好落在 NOW：存了更晚那次就不该被删，存了 NOW-1 那次就会被删。
        // 取更晚那次是必须的 —— 用错的时间戳，剪枝会把还该留的条目提前删掉。
        assertEquals(0, store.prune(NOW + 1, ttl = 1), "应该按更晚的 NOW 记，而不是 NOW-1")
    }

    @Test
    fun anUnwritablePathDegradesToMemoryOnlyAndDoesNotThrow() {
        val dir = createTempDir("relay-dedup-blocked").also { dirs += it }
        // 拿一个普通文件当目录用：建目录建不出来，打开必然失败。
        writeLog("$dir/blocker", "x")
        val store = FileDedupStore("$dir/blocker/dedup.log")

        store.add("msg-1", "peer-a", NOW)

        assertTrue(store.has("msg-1", "peer-a"), "写不进去也要在内存里去重，聊天不能被存储拦住")
    }

    @Test
    fun theLastLineWithoutATrailingNewlineIsStillLoaded() {
        // 追加写入天然会产生"最后一行没有换行符"的文件（进程被杀在 flush 之前），
        // 所以这不是异常输入，是常态。
        val path = newLogPath()
        writeLog(path, "$NOW\tpeer-a\tmsg-1")

        assertTrue(FileDedupStore(path).has("msg-1", "peer-a"), "最后一行没有换行符也要认")
    }

    // ------------------------------------------------------------------ 工具

    private fun readLog(path: String): String {
        val handle = openRandomAccess(path, forWrite = false)
        return try {
            val buffer = ByteArray(handle.size.toInt())
            var filled = 0
            while (filled < buffer.size) {
                val read = handle.read(buffer, filled.toLong(), buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            buffer.decodeToString(0, filled)
        } finally {
            handle.close()
        }
    }

    private fun writeLog(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        val handle = openRandomAccess(path, forWrite = true)
        try {
            if (bytes.isNotEmpty()) handle.append(bytes, bytes.size)
            handle.flush()
        } finally {
            handle.close()
        }
    }

    /** 日志里所有条目的 `msgId`（已反转义），顺序即文件顺序。 */
    private fun entryIdsInLog(path: String): List<String> =
        readLog(path).trimEnd('\n').split('\n').filter { it.isNotEmpty() }.map { line ->
            line.substringAfter('\t').substringAfter('\t')
        }

    private companion object {
        const val NOW = 1_789_000_000_000L
    }
}
