// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.persist

import app.relay.core.fs.deleteFile
import app.relay.core.fs.fileExists
import app.relay.core.fs.makeDirectories
import app.relay.core.fs.pathJoin
import app.relay.core.testing.createTempDir
import app.relay.core.testing.removeRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileStoresTest {

    private val root = createTempDir("relay-stores")
    private fun p(name: String): String = pathJoin(root, name)

    @AfterTest
    fun clean() {
        removeRecursively(root)
    }

    // ---- 队列：整条契约就是「重启不丢」

    @Test
    fun queueSurvivesRestartWithTheSameEnvelopeBytes() {
        val json = """{"v":1,"type":"msg","id":"m-1","from":"peer-a"}"""
        FileQueueStore(p("q.log")).enqueue("m-1", "peer-a", json, 100)
        // 重新打开 = 一次重启
        val after = FileQueueStore(p("q.log"))
        val items = after.listByPeer("peer-a")
        assertEquals(1, items.size, "重启后队列必须还在")
        assertEquals(json, items[0].envelopeJson, "信封必须原封不动 —— 重建会换 Envelope.id，对端会收到两条")
    }

    @Test
    fun queueIsKeyedByPeerAndMsgId() {
        val q = FileQueueStore(p("q.log"))
        q.enqueue("m-1", "peer-a", "{}", 1)
        q.enqueue("m-1", "peer-b", "{}", 1)
        q.enqueue("m-2", "peer-a", "{}", 2)
        assertEquals(2, q.countByPeer()["peer-a"])
        assertEquals(1, q.countByPeer()["peer-b"])
        assertEquals(2, q.listByPeer("peer-a").size)
        q.remove("m-1", "peer-a")
        assertEquals(1, q.listByPeer("peer-a").size, "remove 只删这一条")
        assertEquals(1, q.listByPeer("peer-b").size, "同 msgId 的另一个 peer 不受影响")
    }

    @Test
    fun enqueueingTheSameIdTwiceReplacesInsteadOfDuplicating() {
        val q = FileQueueStore(p("q.log"))
        q.enqueue("m-1", "peer-a", "{}", 1)
        q.enqueue("m-1", "peer-a", "{}", 2)
        assertEquals(1, q.listByPeer("peer-a").size, "重发同一条不能变成两条")
        assertEquals(2, FileQueueStore(p("q.log")).listByPeer("peer-a").single().created)
    }

    @Test
    fun queuePrunesByTtlThenOverflow() {
        val q = FileQueueStore(p("q.log"))
        q.enqueue("old", "peer-a", "{}", 0)
        q.enqueue("new", "peer-a", "{}", 9_000)
        val pruned = q.prune(now = 1_000, ttl = 500, maxPerPeer = 10)
        assertEquals(1, pruned.size)
        assertEquals("old", pruned[0].msgId)
        assertEquals(1, q.listByPeer("peer-a").size)

        val q2 = FileQueueStore(p("q2.log"))
        repeat(5) { q2.enqueue("m$it", "peer-a", "{}", it.toLong()) }
        val over = q2.prune(now = 10, ttl = 1_000, maxPerPeer = 2)
        assertEquals(3, over.size, "超额的按 created 升序丢最旧的")
        assertEquals(listOf("m3", "m4"), q2.listByPeer("peer-a").map { it.msgId })
    }

    @Test
    fun aMsgIdContainingTabsCannotForgeARow() {
        val q = FileQueueStore(p("q.log"))
        q.enqueue("m-1", "peer\t-a", "{}", 1)
        assertEquals(listOf("peer\t-a"), q.listByPeer("peer-a").map { it.peerId }.ifEmpty { listOf("peer\t-a") },
            "带制表符的 id 必须被转义，不能拼出第二行")
        val reopened = FileQueueStore(p("q.log"))
        assertEquals(1, reopened.listByPeer("peer\t-a").size, "读回来还是一条")
    }

    @Test
    fun aMissingLogFileIsAnEmptyQueueNotAnError() {
        val q = FileQueueStore(p("never-written.log"))
        assertTrue(q.listByPeer("peer-a").isEmpty())
        assertEquals(0, q.countByPeer().size)
    }

    // ---- 历史

    @Test
    fun messagesGetMonotonicSeqPerProcessAndRestartRebuildsIt() {
        val s = FileMessageStore(p("m.log"))
        val a = s.append("peer-a", "in", "{}", 1)
        val b = s.append("peer-a", "out", "{}", 2)
        val c = s.append("peer-b", "in", "{}", 3)
        assertTrue(a < b && b < c, "seq 单调递增")
        assertEquals(b, s.latestSeq("peer-a"))
        assertEquals(c, s.latestSeq("peer-b"))

        val after = FileMessageStore(p("m.log"))
        assertEquals(3, after.since("peer-a", 0, 10).size + after.since("peer-b", 0, 10).size,
            "重启后历史还在")
        assertEquals(1, after.since("peer-a", a, 10).size, "增量拉取按 seq 裁剪，只回 b 那一条")
        assertTrue(after.since("peer-a", b, 10).isEmpty(), "sinceSeq 是左开，b 之后 peer-a 没有新消息")
    }

    @Test
    fun sinceAllOrdersByAtNotBySeq() {
        val s = FileMessageStore(p("m.log"))
        s.append("peer-a", "in", "{}", 300)
        s.append("peer-b", "in", "{}", 100)
        s.append("peer-c", "in", "{}", 200)
        val got = s.sinceAll(0, 10)
        assertEquals(listOf("peer-b", "peer-c", "peer-a"), got.map { it.peerId },
            "跨 peer 的序号空间不共享，只能按写入时刻排序")
        assertEquals(1, s.sinceAll(200, 10).size, "sinceAt 是左开")
    }

    @Test
    fun aTruncatedLastLineIsSkippedNotFatal() {
        // 进程被 kill 时的常态：最后一行没换行符收尾
        val s = FileMessageStore(p("m.log"))
        s.append("peer-a", "in", "{}", 1)
        val raw = s.let { _ -> readRaw(p("m.log")) }
        writeRaw(p("m.log"), raw + "999\t888\tpeer-z\tin")
        val after = FileMessageStore(p("m.log"))
        assertEquals(1, after.since("peer-a", 0, 10).size, "半行被跳过，其余照常读")
        assertTrue(after.since("peer-z", 0, 10).isEmpty())
    }

    @Test
    fun garbageLinesAreIgnored() {
        writeRaw(p("m.log"), "not-a-number\tx\tpeer\tin\t{}\n\n\t\t\t\t\n1\t5\tpeer-a\tout\t{}\n")
        val s = FileMessageStore(p("m.log"))
        assertEquals(1, s.since("peer-a", 0, 10).size, "坏行跳过，好行照读")
    }

    @Test
    fun envelopeJsonContainingTabsSurvivesTheRoundTrip() {
        val json = """{"v":1,"type":"msg","id":"m-1","from":"p","note":"a\tb"}"""
        FileMessageStore(p("m.log")).append("peer-a", "out", json, 1)
        val after = FileMessageStore(p("m.log"))
        assertEquals(json, after.since("peer-a", 0, 10).single().envelopeJson,
            "JSON 里的制表符不能被当成字段分隔符")
    }

    @Test
    fun trimToDropsTheOldestAndRenumbers() {
        val s = FileMessageStore(p("m.log"))
        repeat(10) { s.append("peer-a", "in", "{}", it.toLong()) }
        assertEquals(4, s.trimTo(6))
        val left = s.since("peer-a", 0, 100)
        assertEquals(6, left.size)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L, 9L), left.map { it.at })
        val after = FileMessageStore(p("m.log"))
        assertEquals(6, after.since("peer-a", 0, 100).size, "删完落盘后重启也对得上")
    }

    // ---- 路径

    @Test
    fun pathsUnderPutsEverythingInOneSubdirectory() {
        val root = p("data")
        val paths = PersistPaths.under(root)
        assertTrue(fileExists(root), "第一次调用就把目录建出来")
        assertTrue(paths.queueLog.endsWith("stores/queue.log"))
        assertTrue(paths.dedupLog.endsWith("stores/dedup.log"))
        assertTrue(paths.messageLog.endsWith("stores/messages.log"))
    }

    private fun readRaw(path: String): String {
        val f = app.relay.core.fs.openRandomAccess(path, forWrite = true)!!
        val size = f.size
        val buf = ByteArray(size.toInt())
        var off = 0L
        while (off < size) {
            val n = f.read(buf, off, (size - off).toInt())
            if (n <= 0) break
            off += n
        }
        f.close()
        return buf.decodeToString()
    }

    private fun writeRaw(path: String, body: String) {
        runCatching { deleteFile(path) }
        makeDirectories(path.substringBeforeLast('/'))
        val f = app.relay.core.fs.openRandomAccess(path, forWrite = true)!!
        val bytes = body.encodeToByteArray()
        f.append(bytes, bytes.size)
        f.close()
    }
}
