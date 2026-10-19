// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.persist

import app.relay.core.fs.deleteFile
import app.relay.core.fs.fileExists
import app.relay.core.fs.makeDirectories
import app.relay.core.fs.openRandomAccess
import app.relay.core.fs.pathJoin
import app.relay.core.fs.readFileBytes
import app.relay.core.protocol.Codec
import app.relay.core.protocol.DecodeResult
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.makeEnvelope
import app.relay.core.testing.createTempDir
import app.relay.core.testing.removeRecursively
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    fun messagesGetMonotonicSeqThatSurvivesARestart() {
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
    fun trimToDropsTheOldestAndKeepsTheSeqOfTheSurvivors() {
        val s = FileMessageStore(p("m.log"))
        repeat(10) { s.append("peer-a", "in", "{}", it.toLong()) }
        assertEquals(4, s.trimTo(6))
        val left = s.since("peer-a", 0, 100)
        assertEquals(6, left.size)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L, 9L), left.map { it.at })
        // **幸存者的 seq 保持原样**（这里 5..10），不按行号重排。
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 10L), left.map { it.seq })
        val after = FileMessageStore(p("m.log"))
        assertEquals(6, after.since("peer-a", 0, 100).size, "删完落盘后重启也对得上")
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 10L), after.since("peer-a", 0, 100).map { it.seq })
    }

    /**
     * 裁剪**不能**让调用方手里的游标失效。
     *
     * 这条钉的是「`seq` 是稳定游标」这个契约：原来 `load()` 与 `rewrite()` 都按**行号**重排
     * `seq`，于是裁掉最旧的一段之后，剩下每一行的 seq 整体前移 —— 调用方拿着「我读到第 8 条」
     * 再来问，会拿到错的行（重复或漏读）。现在 seq 写进文件、照读回来。
     */
    @Test
    fun trimmingDoesNotInvalidateACursorHeldByTheCaller() {
        val s = FileMessageStore(p("m.log"))
        repeat(10) { s.append("peer-a", "in", "{}", it.toLong()) }
        val cursor = s.latestSeq("peer-a") - 2
        s.trim(now = 0, ttl = 0, maxRows = 6)
        assertEquals(listOf(8L, 9L), s.since("peer-a", cursor, 100).map { it.at })
        val after = FileMessageStore(p("m.log"))
        assertEquals(listOf(8L, 9L), after.since("peer-a", cursor, 100).map { it.at },
            "重启后同一个游标仍然只回它之后那两条")
    }

    @Test
    fun trimDropsRowsOlderThanTheTtl() {
        val s = FileMessageStore(p("m.log"))
        s.append("peer-a", "in", "{}", 1_000)
        s.append("peer-a", "in", "{}", 5_000)
        s.append("peer-a", "in", "{}", 9_000)
        assertEquals(1, s.trim(now = 10_000, ttl = 5_000, maxRows = 100), "只丢 1000 那条")
        assertEquals(listOf(5_000L, 9_000L), s.since("peer-a", 0, 100).map { it.at })
    }

    @Test
    fun trimIsANoOpWhenNothingIsOverTheLimits() {
        val s = FileMessageStore(p("m.log"))
        s.append("peer-a", "in", "{}", 5_000)
        assertEquals(0, s.trim(now = 10_000, ttl = 60_000, maxRows = 100))
        val before = readRaw(p("m.log"))
        assertEquals(0, s.trim(now = 10_000, ttl = 60_000, maxRows = 100))
        assertEquals(before, readRaw(p("m.log")), "没什么可丢就不该重写整份文件")
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

    // ---- 非 ASCII：UTF-8 必须逐字节原样往返（真机群消息乱码的回归）
    //
    // 真机现象：桌面排队的一条中文群消息，重启后补发到手机上变成 `U+FFxx` 乱码
    // （`￯﾿ﾯ￯ﾾ﾿…`），而桌面 relay.db 里同一批消息是正常中文。根因在这两个 store：
    //   1. `readLines` 用 `chunk[i].toInt().toChar()`，Byte→Int 的**符号扩展**把每个
    //      ≥0x80 的字节变成 `U+FF00|b`（0xE7 → U+FFE7）；JSON 结构是 ASCII，照样解析得动，
    //      乱码于是被当成正文补发。
    //   2. `appendLine` 给 `append` 传的是 `line.length + 1`（字符数）而不是字节数，
    //      中文行被截断，重启后 JSON 解析失败、队列直接丢掉这条。
    // 下面几条都用**中文群文本的真实信封**，断言"逐字节原样"，ASCII 夹具永远测不出来。

    private fun groupTextJson(text: String): String =
        Codec.encode(makeEnvelope(MsgTypes.MSG, "desk-node", Codec.groupTextPayload(text, "g-1", 1)))

    @Test
    fun aNonAsciiQueueRowRoundTripsByteForByte() {
        val text = "群消息正常中文"
        val json = groupTextJson(text)
        FileQueueStore(p("q-cn.log")).enqueue("m-cn", "group:g-1", json, 100)

        val reopened = FileQueueStore(p("q-cn.log")).listByPeer("group:g-1").single()

        assertEquals(json, reopened.envelopeJson, "信封必须逐字节原样读回，不能被符号扩展成 U+FFxx")
        val decoded = Codec.decode(reopened.envelopeJson)
        assertTrue(decoded is DecodeResult.Ok, "读回来必须还是能解析的信封：$decoded")
        val payload = (decoded as DecodeResult.Ok).env.payload as JsonObject
        assertEquals(text, (payload["text"] as JsonPrimitive).content, "正文不能被符号扩展")
    }

    @Test
    fun aNonAsciiHistoryRowRoundTripsByteForByte() {
        val json = groupTextJson("群消息正常中文")
        FileMessageStore(p("m-cn.log")).append("group:g-1", "out", json, 100)

        val after = FileMessageStore(p("m-cn.log"))

        assertEquals(json, after.since("group:g-1", 0, 10).single().envelopeJson)
    }

    @Test
    fun aNonAsciiRowIsWrittenInFullNotTruncatedToItsCharacterCount() {
        val json = groupTextJson("群消息正常中文")
        FileQueueStore(p("q-full.log")).enqueue("m-cn", "group:g-1", json, 100)

        val raw = readFileBytes(p("q-full.log"))!!.decodeToString()

        assertTrue(raw.endsWith("}\n"), "整行必须以 JSON 收尾符加换行结束，实际结尾：${raw.takeLast(24)}")
        assertEquals(json, raw.removeSuffix("\n").substringAfterLast('\t'))
    }

    @Test
    fun aNonAsciiQueueRowSurvivesSeveralRestartsWithoutMojibake() {
        val json = groupTextJson("群消息正常中文")
        FileQueueStore(p("q-restart.log")).enqueue("m-cn", "group:g-1", json, 100)

        // 连开三次 = 连重启三次。旧实现每读一次就把字节再符号扩展一层，
        // 乱码会一层套一层（真机上那串 ￯﾿ﾯ… 正是这么来的）。
        var store = FileQueueStore(p("q-restart.log"))
        repeat(2) { store = FileQueueStore(p("q-restart.log")) }

        assertEquals(json, store.listByPeer("group:g-1").single().envelopeJson)
    }

    @Test
    fun readLinesDecodesUtf8AcrossTheChunkBoundary() {
        // 让一个三字节汉字正好跨在 64KiB 读块边界上：先解码半块再拼会得到 U+FFFD。
        val text = "a".repeat(CHUNK.toInt() - 1) + "群尾"
        writeRaw(p("boundary.log"), text + "\n")

        val handle = openRandomAccess(p("boundary.log"), forWrite = false)
        val lines = readLines(handle)
        handle.close()

        assertEquals(listOf(text), lines, "跨块的多字节字符必须按原始字节接到下一块再解码")
    }

    /**
     * 没有换行的超大行必须**线性读完**，而不是把尾巴越拼越长。
     *
     * 现场（2026-10-18）：桌面端 1.24GB 的 messages.log 几乎没有换行 —— 旧 appendLine 把
     * line.length + 1（字符数）当字节数传，行尾那个 \n 被一起截掉了。第一版修复用
     * carry + chunk 拼行，在这种文件上每轮都要重拷重扫越来越长的尾巴（O(n²)），于是启动时
     * 在 98% CPU 空转、节点运行时永远起不来。这条测试钉住"线性读完 + 超限行丢弃"。
     */
    @Test
    fun aNewlineSparseFileIsReadWithoutQuadraticBlowup() {
        val path = p("sparse.log")
        // 一条 9MiB 的"行"（超过单行上限 8MiB）+ 一条正常行。
        val huge = "a".repeat(9 * 1024 * 1024)
        writeRaw(path, huge + "\n" + "ok\tline\n")

        val handle = openRandomAccess(path, forWrite = true)
        val lines = try { readLines(handle) } finally { handle.close() }

        assertEquals(listOf("ok\tline"), lines, "超过单行上限的那一行要被丢掉，而不是拼成 String 把堆撑爆")
    }

    @Test
    fun aLineSpanningManyChunksKeepsItsUtf8Tail() {
        val path = p("longline.log")
        val body = "x".repeat(200 * 1024) + "中文尾巴"
        writeRaw(path, body + "\n")

        val handle = openRandomAccess(path, forWrite = true)
        val lines = try { readLines(handle) } finally { handle.close() }

        assertEquals(listOf(body), lines, "跨多个 64KiB 块的行必须按原始字节累积，尾部多字节字符不能变成 U+FFFD")
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
