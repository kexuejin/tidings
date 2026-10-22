// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 诊断包的边界测试。
 *
 * 三件必须钉住的事：
 * 1. **条目齐全**：解压后正好是 summary.txt / environment.json / link.json / logs/<name>.jsonl 这几项；
 * 2. **不含聊天正文与文件名**：信封只留结构字段，认不出的行整行丢弃；
 * 3. **有上限**：日志按行数 + 字节数限，保留最新的一段（真机 messages.log 到过十几 MB）。
 *
 * 断言的量要能区分对错：不是"包里有个 messages 文件"，而是"这条正文的字串在**解压后的每一个
 * 字节里**都不出现"——后者才是用户把包发出去时真正的保证。
 */
class DiagnosticsPackageTest {

    private val secretText = "这是绝不该进诊断包的聊天正文"
    private val secretFileName = "护照照片-机密.png"

    private fun envelope(id: String, kind: String = "text", ts: Long = 1_760_000_000_000L): String =
        """{"v":1,"type":"msg","id":"$id","from":"node-a","ts":$ts,""" +
            """"payload":{"kind":"$kind","text":"$secretText","name":"$secretFileName","groupId":"g1"}}"""

    private fun environment(): DiagnosticsEnvironment = DiagnosticsEnvironment(
        appVersion = "relay-test",
        nodeId = "node-self",
        deviceNick = "Pixel",
        transport = "Wifi",
        localAddresses = listOf("192.168.1.10"),
        vpnActive = false,
        peerTotal = 2,
        peerOnline = 1,
        pending = 3,
        persistMessages = true,
        peers = listOf(
            DiagPeer("node-a", "Mac", "mac", online = true, isAgent = false, tcpPort = 17879),
            DiagPeer("node-b", "DSH", "linux", online = false, isAgent = true, tcpPort = 17879),
        ),
    )

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
            }
        }
        return out
    }

    @Test
    fun zipHoldsExactlyTheDiagnosticsEntries() {
        val bundle = buildDiagnosticsBundle(
            environment = environment(),
            linkJson = """{"self":"node-self","linkEnabled":true}""",
            queueLog = "1760000000456\tnode-a\tm-2\t" + envelope("m-2"),
            messagesLog = "1\t1760000000123\tnode-a\tout\t" + envelope("m-1"),
            generatedAtMillis = 1_760_928_000_123L,
        )
        val zip = buildDiagnosticsZip(bundle.entries, 1_760_928_000_123L)
        val files = unzip(zip)

        assertEquals(
            listOf("summary.txt", "environment.json", "link.json", "logs/queue.jsonl", "logs/messages.jsonl"),
            files.keys.toList(),
            "诊断包的条目集合变了：加/删条目都要同步这条断言（解压的人靠它知道包里有什麼）",
        )
        assertTrue(zip.size > 200, "空包不像话：至少要有 summary + environment")
        assertEquals("summary.txt", bundle.entries.first().path, "summary 必须是第一条，解压的人先看到它")
        val summary = files.getValue("summary.txt").decodeToString()
        assertTrue(summary.contains("No chat or file content is included"), "summary 必须明确声明包里不含聊天内容")
        assertTrue(summary.contains("logs/messages.jsonl"), "summary 必须列出包里有哪些条目")
        assertTrue(summary.contains("kept=1"), "summary 必须报告日志保留条数")
    }

    @Test
    fun noChatTextOrFileNameSurvivesInAnyEntryOfTheZip() {
        val bundle = buildDiagnosticsBundle(
            environment = environment(),
            linkJson = """{"self":"node-self"}""",
            queueLog = "1760000000456\tnode-a\tm-2\t" + envelope("m-2", kind = "file-offer"),
            messagesLog = "1\t1760000000123\tnode-a\tout\t" + envelope("m-1"),
            generatedAtMillis = 1_760_928_000_123L,
        )
        val files = unzip(buildDiagnosticsZip(bundle.entries, 1_760_928_000_123L))

        // 逐个条目按字节检查：正文/文件名在这份包的**任何地方**都不许出现。
        for ((name, bytes) in files) {
            val text = bytes.decodeToString()
            assertFalse(text.contains(secretText), "条目 $name 里出现了聊天正文 —— 这是诊断包的红线")
            assertFalse(text.contains(secretFileName), "条目 $name 里出现了文件名 —— 同样是内容泄漏")
        }
        // 而结构字段必须还在，否则这个包就没有诊断价值。
        val messages = files.getValue("logs/messages.jsonl").decodeToString()
        assertTrue(messages.contains("\"id\":\"m-1\""), "信封 id 必须保留")
        assertTrue(messages.contains("\"direction\":\"out\""), "方向必须保留")
        assertTrue(messages.contains("\"at\":1760000000123"), "落库时刻 at 必须保留")
        assertTrue(messages.contains("\"kind\":\"text\""), "payload.kind 必须保留（区分 text/group-text/agent-event）")
    }

    @Test
    fun unparsableLinesAreDroppedInsteadOfGuessed() {
        val messages = "1\t1760000000123\tnode-a\tin\t" + envelope("m-1") +
            "\nnot a log line at all: " + secretText +
            "\n2\t1760000000124\tnode-a\tin\t{\"id\":\"m-2\"}"

        val sanitized = sanitizeMessageLog(messages)
        assertEquals(1, sanitized.kept)
        assertEquals(2, sanitized.dropped)
        assertFalse(sanitized.text.contains(secretText))
        assertFalse(sanitized.text.contains("m-2"), "缺 type/from/ts/payload 的 JSON 不算信封，整行丢弃")
    }

    @Test
    fun logsKeepTheNewestLinesWhenBounded() {
        val lines = (1..400).map { index ->
            index.toString() + "\t" + (1_700_000_000_000 + index) + "\tnode-a\tout\t" + envelope("m-" + index)
        }
        val sanitized = sanitizeMessageLog(lines.joinToString("\n"), maxLines = 10_000, maxBytes = 2_048)

        assertTrue(sanitized.truncated, "超过字节上限必须如实标记 truncated")
        assertTrue(sanitized.kept in 1..30, "字节上限 2048 下保留条数应当是个位数到几十，实际 " + sanitized.kept)
        assertTrue(sanitized.text.contains("\"id\":\"m-400\""), "必须保留最新的一条")
        assertFalse(sanitized.text.contains("\"id\":\"m-1\""), "最旧的一条应当被截掉")
        assertTrue(
            sanitized.text.encodeToByteArray().size <= 2_048 + 64,
            "字节上限是硬约束（允许最后一条略微越过的余量），实际 " + sanitized.text.encodeToByteArray().size,
        )
    }

    @Test
    fun crc32MatchesTheReferenceImplementation() {
        for (sample in listOf("", "a", "diagnostics", "测试 UTF-8")) {
            val bytes = sample.encodeToByteArray()
            val reference = CRC32().apply { update(bytes) }.value
            assertEquals(reference, crc32(bytes), "CRC-32 与 java.util.zip 不一致：" + sample)
        }
    }

    @Test
    fun isoUtcIsStableAndFileStampHasNoColon() {
        assertEquals("1970-01-01T00:00:00.000Z", isoUtc(0L))
        assertEquals("2023-11-14T22:13:20.000Z", isoUtc(1_700_000_000_000L))
        assertEquals("2025-10-20T05:20:00.123Z", isoUtc(1_760_937_600_123L))
        // 文件名里不能有冒号：Android 的 FileProvider / Windows 都不接受。
        assertFalse(diagStampUtc(1_760_937_600_123L).contains(':'))
        assertEquals("20251020-052000", diagStampUtc(1_760_937_600_123L))
    }

    @Test
    fun missingSourcesAreReportedInSummaryInsteadOfInvented() {
        val bundle = buildDiagnosticsBundle(
            environment = environment(),
            linkJson = null,
            queueLog = null,
            messagesLog = null,
            generatedAtMillis = 1_760_928_000_123L,
        )
        val files = unzip(buildDiagnosticsZip(bundle.entries, 1_760_928_000_123L))
        assertFalse(files.containsKey("link.json"), "源文件缺失时不许编一个空 link.json 进包")
        val summary = files.getValue("summary.txt").decodeToString()
        assertTrue(summary.contains("diag/link.json: missing"), "缺失的源文件必须在 summary 里如实写 missing")
        assertTrue(summary.contains("stores/messages.log: kept=0"), "缺失的日志必须报 kept=0")
        assertEquals("", files.getValue("logs/messages.jsonl").decodeToString(), "没有日志就是空文件，不是假记录")
    }

    @Test
    fun zipStoredEntriesCarryTheirExactBytes() {
        val entries = listOf(
            DiagnosticsEntry("a.txt", "alpha".encodeToByteArray()),
            DiagnosticsEntry("nested/b.json", "{\"k\":1}".encodeToByteArray()),
        )
        val files = unzip(buildDiagnosticsZip(entries, 0L))
        assertEquals(listOf("a.txt", "nested/b.json"), files.keys.toList())
        assertEquals("alpha", files.getValue("a.txt").decodeToString())
        assertEquals("{\"k\":1}", files.getValue("nested/b.json").decodeToString())
    }

    @Test
    fun sanitizeEnvelopeRequiresTheFullEnvelopeShape() {
        assertNotNull(sanitizeEnvelope(envelope("m-1")))
        assertEquals(null, sanitizeEnvelope("hello, this is free text"))
        assertEquals(null, sanitizeEnvelope("""{"id":"x","type":"msg"}"""))
        assertEquals(null, sanitizeEnvelope("""{"id":"x","type":"msg","from":"a","ts":1}"""))
    }

    /**
     * **诊断包必须同时导出两份地址，且语义分明。**
     *
     * 排查「连不上」时看的是 `advertisedAddresses`（实际宣告给对端的，含隧道口）。
     * 只导 `localAddresses`（广播/同网段判断用的窄列表）会把方向带错：
     * 真机上红米与桌面端分处不同的局域网段，窄列表里互相都不含对方网段，
     * 而宽列表里的 VPN 地址才是唯一可达路径。
     */
    @Test
    fun environmentExportsBothAddressLists() {
        val addrEnv = environment().copy(
            localAddresses = listOf("192.168.1.10"),
            advertisedAddresses = listOf("192.168.1.10", "100.66.238.121"),
        )
        val bundle = buildDiagnosticsBundle(
            environment = addrEnv,
            linkJson = """{"self":"node-self"}""",
            queueLog = "",
            messagesLog = "",
            generatedAtMillis = 1_760_928_000_123L,
        )
        val env = unzip(buildDiagnosticsZip(bundle.entries, 1_760_928_000_123L))
            .getValue("environment.json").decodeToString()
        assertTrue(
            env.contains("advertisedAddresses"),
            "必须导出宣告地址：排查跨网连不上时，窄列表看不出对方是不是只在 VPN 上可达",
        )
        assertTrue(env.contains("100.66.238.121"), "宣告地址里的隧道口必须原样出现")
        assertTrue(
            env.contains("\"localAddresses\""),
            "窄列表也要留着：它才是同网段判断的依据",
        )
    }
}
