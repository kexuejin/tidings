// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task-86：二维码载荷在**带上 pin** 之后必须仍然装得下（长度 ≤ [QR_PAYLOAD_MAX_BYTES]）。
 *
 * 真机回归：pin ≈ 78 字节，长键名 + pin 会让载荷越过 213 字节 ⇒ QrEncoder 抛异常
 * ⇒ 桌面端只剩一句「二维码过长」，码画不出来。
 *
 * 断言一律绑**候选类型**（[PeerCandidateTypes.PUBLIC_V6]），不绑地址字面值：夹具里的公网 v6
 * 曾从某个运营商前缀换成文档段（脱敏），而断言当时写的是 `host.contains("2408:")` ——
 * 于是这条用例在脱敏当天就变红，却没人重跑测试，红了很久才被发现。类型是语义，前缀是夹具细节。
 */
class QrPayloadBudgetTest {

    private val macAddrs = listOf("192.168.2.82", "2001:db8:2020:cb44:4805:e245:f001:2")
    private val pin = "sha256:" + "ab".repeat(32)
    private val nodeId = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"

    @Test
    fun payloadWithPinFitsQrBudget() {
        val payload = qrPairingPayloadForAddresses(nodeId, macAddrs, 17878, tlsPin = pin)!!
        println("BUDGET len=" + payload.length + "  payload=" + payload)
        assertTrue(payload.length <= QR_PAYLOAD_MAX_BYTES, "载荷超预算: " + payload.length)
        // pin 与地址都是二进制形态（见 encodePin / encodeCandidate），所以**不能**再用
        // payload.contains(...) 判断"带没带"。判据要问解析器：它才是载荷语义的权威。
        val parsed = parseQr(payload) as? ParsedQr.LanPeer ?: error("载荷解析不出来: " + payload)
        assertEquals(pin, parsed.tlsPin, "必须带 pin（解析回 wire 形态）: " + payload)
        assertTrue(
            parsed.candidates.any { it.type == PeerCandidateTypes.PUBLIC_V6 },
            "必须留下公网 v6: " + payload,
        )
    }

    /**
     * 昵称必须真的进码（真机反馈：扫完只显示 `node-a1b2c3d4`，对方其实是 `demo-mac-97`）。
     *
     * 同时也钉住预算：昵称与 pin 一样，**必须算进裁剪**。否则"裁剪时按不带昵称估、生成时又带上"
     * 就会超预算，编码器抛异常 ⇒ 界面上只剩一句「二维码过长」。
     */
    @Test
    fun thePayloadCarriesTheNicknameWithinBudget() {
        val nick = "demo-mac-97"
        val payload = qrPairingPayloadForAddresses(nodeId, macAddrs, 17878, tlsPin = pin, nick = nick)!!
        assertTrue(payload.length <= QR_PAYLOAD_MAX_BYTES, "带昵称也不许超预算: " + payload.length)
        val parsed = parseQr(payload) as? ParsedQr.LanPeer ?: error("载荷解析不出来: " + payload)
        assertEquals(nick, parsed.nick, "昵称必须在码里：不带它，对方节点表就只能显示合成名")
        assertEquals(pin, parsed.tlsPin, "带昵称不能把 pin 挤掉")
        assertTrue(
            parsed.candidates.any { it.type == PeerCandidateTypes.PUBLIC_V6 },
            "也不能把公网 v6 挤掉",
        )
    }

    /** 没有昵称时行为不变（老调用点与截图夹具不该因为这条新参数而改变）。 */
    @Test
    fun withoutANicknameThePayloadIsUnchanged() {
        val withOut = qrPairingPayloadForAddresses(nodeId, macAddrs, 17878, tlsPin = pin)!!
        val blank = qrPairingPayloadForAddresses(nodeId, macAddrs, 17878, tlsPin = pin, nick = "  ")!!
        assertEquals(withOut, blank, "空白昵称必须与没传完全一致（不许往码里塞一个空格串）")
        assertNull((parseQr(withOut) as ParsedQr.LanPeer).nick)
    }
    @Test
    fun shortPayloadStillParses() {
        val payload = qrPairingPayloadForAddresses(nodeId, macAddrs, 17878, tlsPin = pin)!!
        val parsed = parseQr(payload) as? ParsedQr.LanPeer
        assertTrue(parsed != null, "二进制形态的载荷必须能被自己的解析器读回: " + payload)
        assertEquals(nodeId, parsed.nodeId, "nodeId 必须读回")
        assertEquals(pin, parsed.tlsPin, "pin 必须读回")
        assertTrue(
            parsed.candidates.any { it.type == PeerCandidateTypes.PUBLIC_V6 },
            "公网 v6 必须读回",
        )
    }
}
