// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * luciola 段嵌进现有 Tidings 二维码:老端忽略未知键,新端读得到公钥与配对密钥。
 *
 * 三条纪律:
 *  - 同一个码双栈(不能为 luciola 另印一张码);
 *  - 码仍然能被版本 8 编码器画出来(否则真机扫不出,见 [QR_PAYLOAD_MAX_BYTES]);
 *  - luciola 段坏掉只丢这一段,整张码照旧可配(老主机的码本来就没有它)。
 */
class LuciolaQrTest {

    private val publicKey = ByteArray(32) { (it * 7 + 1).toByte() }
    private val secret = ByteArray(16) { (it * 13 + 3).toByte() }
    private val info = assertNotNull(LuciolaQrInfo.of(publicKey, secret))

    private fun payload(
        addresses: List<String> = listOf("192.168.1.10"),
        nick: String? = "mac-mini",
        tlsPin: String? = "sha256:" + "ab".repeat(32),
        luciola: LuciolaQrInfo? = info,
    ): String? = qrPairingPayloadForAddresses(
        nodeId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        addresses = addresses,
        port = 17878,
        tlsPin = tlsPin,
        nick = nick,
        luciola = luciola,
    )

    @Test
    fun luciolaSectionRoundTrips() {
        val text = assertNotNull(payload(), "带 luciola 段的码必须画得出来")
        assertTrue(text.length <= QR_PAYLOAD_MAX_BYTES, "超预算: ${text.length}")
        assertTrue(!text.contains("\"p\":"), "开了 luciola 就不印 legacy pin(预算装不下两者)")
        val parsed = assertNotNull(parseQr(text) as? ParsedQr.LanPeer)
        assertEquals(info.publicKey, parsed.luciola?.publicKey)
        assertEquals(info.secret, parsed.luciola?.secret)
        assertEquals(17878, parsed.port)
    }

    @Test
    fun legacyQrWithoutLuciolaStillParses() {
        val text = assertNotNull(payload(luciola = null))
        assertTrue(!text.contains("\"l\":"), "没开实验信道就不许写这个键")
        val parsed = assertNotNull(parseQr(text) as? ParsedQr.LanPeer)
        assertNull(parsed.luciola)
        assertTrue(text.contains("\"p\":"), "没开 luciola 时 legacy pin 必须照旧在码里")
    }

    @Test
    fun malformedLuciolaSectionDoesNotBreakTheCode() {
        val text = assertNotNull(payload())
        // 把公钥段改成非法 base64:解析端只该丢掉 luciola 段,其余照旧。
        val broken = text.replace(info.publicKey, "!!!not-base64!!!")
        val parsed = assertNotNull(parseQr(broken) as? ParsedQr.LanPeer, "坏 luciola 段不该废掉整张码")
        assertNull(parsed.luciola)
        assertTrue(parsed.candidates.isNotEmpty(), "坏 luciola 段不该带走候选")
    }

    @Test
    fun luciolaInfoRejectsWrongKeySizes() {
        assertNull(LuciolaQrInfo.of(ByteArray(31), secret))
        assertNull(LuciolaQrInfo.of(ByteArray(33), secret))
        assertNull(LuciolaQrInfo.of(publicKey, ByteArray(15)))
        assertNull(LuciolaQrInfo.of(publicKey, ByteArray(17)))
    }

    @Test
    fun luciolaSectionCostsCandidatesButStaysWithinBudget() {
        val many = listOf("192.168.1.10", "2001:db8:1100::5", "10.0.0.7", "100.101.102.103")
        val text = assertNotNull(payload(addresses = many, nick = null), "多候选时至少留得下一条")
        assertTrue(text.length <= QR_PAYLOAD_MAX_BYTES, "超预算: ${text.length}")
        assertTrue(text.contains("\"l\":"))
        val parsed = assertNotNull(parseQr(text) as? ParsedQr.LanPeer)
        assertTrue(parsed.candidates.isNotEmpty())
        assertEquals(info.publicKey, parsed.luciola?.publicKey)
    }
}
