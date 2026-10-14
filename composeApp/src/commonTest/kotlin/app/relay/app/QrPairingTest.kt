// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「扫一下就能配对」是用户唯一看得见的一句话 —— 解析对了才有下文，解析错了用户只看到
 * 「二维码内容不认识」，而码是谁印的、错在哪，他无从判断。所以这里钉住两件事：
 *
 * 1. 我们自己印出来的那两种写法**必须**认（`relay://host:port` 与裸 `host:port`）；
 * 2. 认不出来的时候**返回 null，不猜** —— 猜出来的 host 会被真的连过去，比报错更糟。
 *
 * 边界值（端口 1 / 65535 / 0 / 65536）单列，因为 `toIntOrNull()` 会放过 `0` 和
 * `65536`，只有 `port !in 1..65535` 那一句拦得住。
 */
class QrPairingTest {

    @Test
    fun bothDocumentedFormsParseToTheSamePair() {
        val expected = "192.168.1.23" to 17878
        assertEquals(expected, parseQrPayload("relay://192.168.1.23:17878"))
        assertEquals(expected, parseQrPayload("192.168.1.23:17878"))
    }

    @Test
    fun hostnameAndWhitespaceAreTolerated() {
        // 主机名同样合法（桌面端就是 desktop-node.local 这种）。
        assertEquals(
            "desktop-node.local" to 17878,
            parseQrPayload("relay://desktop-node.local:17878"),
        )
        // 扫出来的字符串常带换行/空格；`relay:// host : 17878` 这种也得认。
        assertEquals("192.168.1.23" to 17878, parseQrPayload("  relay://192.168.1.23:17878\n"))
        assertEquals("192.168.1.23" to 17878, parseQrPayload("192.168.1.23 : 17878 "))
    }

    @Test
    fun portMustBeInRange() {
        assertEquals("h" to 1, parseQrPayload("h:1"))
        assertEquals("h" to 65535, parseQrPayload("h:65535"))
        for (bad in listOf("h:0", "h:65536", "h:-1", "h:99999999999999")) {
            assertNull(parseQrPayload(bad), "端口 $bad 不该被接受")
        }
    }

    @Test
    fun nonNumericPortIsRejected() {
        assertNull(parseQrPayload("relay://192.168.1.23:abc"))
        assertNull(parseQrPayload("relay://192.168.1.23:"))
        assertNull(parseQrPayload("relay://192.168.1.23:17a"))
    }

    @Test
    fun emptyHostIsRejected() {
        assertNull(parseQrPayload("relay://:17878"))
        assertNull(parseQrPayload(":17878"))
        assertNull(parseQrPayload("relay://   :17878"))
    }

    @Test
    fun garbageIsRejected() {
        for (bad in listOf("", "   ", "relay://", "hello", "relay://192.168.1.23")) {
            assertNull(parseQrPayload(bad), "「$bad」不该被解析成一对 host:port")
        }
    }

    @Test
    fun extraColonsAreRejectedIncludingBracketedIpv6() {
        // 多一段就是错的，不能取前两段凑合 —— 那会把 `a:b:c` 连到 `a:b`。
        assertNull(parseQrPayload("relay://192.168.1.23:17878:extra"))
        // 已知不支持的写法（见 QrPairing.kt 的说明）：本应用只广播 IPv4，
        // 自家二维码永远不会长成这样，所以这里钉的是「拒绝」而不是「支持」。
        assertNull(parseQrPayload("[fe80::1]:17878"))
    }

    @Test
    fun whatWePrintIsWhatWeParse() {
        // 生成与解析是同一套规则的两半：我们印出去的码，自己必须第一个认得出。
        // 这条不测二维码图形（那由 core 的 QrEncoderTest 对着 libqrencode 逐位钉），
        // 只测"码里那串字"能不能原样回到一对 host:port。
        val cases = listOf(
            "192.168.1.23" to 17878,
            "10.0.0.7" to 1,
            "192.168.1.23" to 65535,
            "desktop-node.local" to 17878,
        )
        for ((host, port) in cases) {
            val payload = pairingPayload(host, port)
            assertEquals("relay://$host:$port", payload)
            assertEquals(host to port, parseQrPayload(payload!!))
        }
    }

    @Test
    fun pairingRefusesToPrintSomethingWeWouldNotParse() {
        // 拼不出"自己认得出"的载荷时不印码 —— 印一张自己解析不了的码比不印更糟。
        assertNull(pairingPayload("", 17878))
        assertNull(pairingPayload("   ", 17878))
        assertNull(pairingPayload("192.168.1.23", 0))
        assertNull(pairingPayload("192.168.1.23", 65536))
        // IPv6 字面量：解析器明确不支持（见上一条），所以这里也不能拼。
        assertNull(pairingPayload("fe80::1", 17878))
        assertNull(pairingPayload("[fe80::1]", 17878))
    }

    @Test
    fun parseQrRecognizesLanPeers() {
        val result = parseQr("relay://192.168.1.23:17878")
        assertEquals(ParsedQr.LanPeer("192.168.1.23", 17878), result)
    }

    @Test
    fun parseQrRecognizesJsonRemoteRelay() {
        val json = """{"type":"relay","url":"https://openai.auroramedia.icu/relay","token":"tok123","nodeId":"agent-007","nick":"MyAgent","lan":"192.168.1.80:17878"}"""
        val result = parseQr(json)
        assertEquals(
            ParsedQr.RemoteRelay(
                url = "https://openai.auroramedia.icu/relay",
                token = "tok123",
                nodeId = "agent-007",
                nick = "MyAgent",
                lan = "192.168.1.80:17878",
            ),
            result,
        )
    }

    @Test
    fun parseQrRecognizesUriRemoteRelay() {
        val uri = "relay://relay?url=https://relay.example.com&token=t1&nodeId=n1&nick=Bot"
        val result = parseQr(uri)
        assertEquals(
            ParsedQr.RemoteRelay(
                url = "https://relay.example.com",
                token = "t1",
                nodeId = "n1",
                nick = "Bot",
            ),
            result,
        )
    }

    @Test
    fun parseQrRecognizesRelayPairUri() {
        val uri = "relay-pair://v1?id=agent-1&nick=Bot&relay=https://relay.io&token=secret&lan=192.168.1.50:17878"
        val result = parseQr(uri)
        assertEquals(
            ParsedQr.RemoteRelay(
                url = "https://relay.io",
                token = "secret",
                nodeId = "agent-1",
                nick = "Bot",
                lan = "192.168.1.50:17878",
            ),
            result,
        )
    }

    @Test
    fun remotePairingPayloadRoundtrip() {
        val payload = remotePairingPayload(
            url = "https://relay.example.com",
            token = "secret-token",
            nodeId = "node-42",
            nick = "Server Agent",
        )
        val parsed = parseQr(payload)
        assertEquals(
            ParsedQr.RemoteRelay(
                url = "https://relay.example.com",
                token = "secret-token",
                nodeId = "node-42",
                nick = "Server Agent",
            ),
            parsed,
        )
    }
}
