// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `TlsPin` 是后续"公网直连证书固定"（见 `docs/public-transport-security.md`）里
 * **唯一**一处定义"什么算合法 pin"的地方，所以校验的松紧必须钉死：
 * 松一档 = 把截断/乱码的指纹当成合法 pin 存下来，用户以为连的是那台机器。
 */
class TlsPinTest {

    // 标准 SHA-256 向量：sha256("abc")。用固定向量而不是"两次算出一样"，
    // 是为了在摘要实现被换掉时能红 —— 自比相等测不出摘要算法变了。
    private val ABC_SPKI = "abc".encodeToByteArray()
    private val ABC_PIN = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun ofSpkiUsesTheStandardSha256OfTheSpkiBytes() {
        assertEquals(ABC_PIN, TlsPin.ofSpki(ABC_SPKI).wire)
    }

    @Test
    fun aWellFormedPinRoundTripsThroughParse() {
        val pin = TlsPin.parse(ABC_PIN)
        assertEquals(ABC_PIN, pin?.wire)
        assertEquals(pin, TlsPin.parse(pin!!.wire), "解析出来的 wire 必须能再次解析成同一条 pin")
    }

    @Test
    fun surroundingWhitespaceAndUppercaseAreNormalised() {
        // 手抄/大写是常见输入。不归一的话，"看起来一样"的两条 pin 会不相等，
        // 而那种 bug 只会在真机握手时才暴露。
        val messy = "  SHA256:BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD  "
        assertEquals(ABC_PIN, TlsPin.parse(messy)?.wire)
    }

    @Test
    fun malformedPinsAreRejectedInsteadOfGuessed() {
        val bad = listOf(
            null,
            "",
            "   ",
            "sha256:", // 没有摘要
            "sha256:abc", // 截断（最危险的一种：前缀对、长度不对）
            ABC_PIN.dropLast(1), // 少一个字符
            ABC_PIN + "0", // 多一个字符
            "sha256:" + "g".repeat(64), // 非十六进制
            "sha256:" + "a".repeat(63) + "-", // 64 位但含非法字符
            ABC_PIN.removePrefix("sha256:"), // 少了前缀
            "sha512:" + "a".repeat(128), // 换了算法
            "sha256:aa:bb", // 带分隔符
        )
        for (raw in bad) {
            assertNull(TlsPin.parse(raw), "必须拒收：$raw")
        }
    }

    @Test
    fun matchesComparesTheSpkiNotTheCertOrItsText() {
        val pin = TlsPin.ofSpki(ABC_SPKI)
        assertTrue(pin.matches(ABC_SPKI), "同一份 SPKI 必须匹配")
        assertFalse(pin.matches("abd".encodeToByteArray()), "不同 SPKI 不能匹配")
        assertFalse(pin.matches(ABC_SPKI.copyOf(ABC_SPKI.size + 1)), "多一个字节也不能匹配")
    }

    @Test
    fun twoPinsAreEqualExactlyWhenTheirWireIsEqual() {
        assertEquals(TlsPin.parse(ABC_PIN), TlsPin.ofSpki(ABC_SPKI))
        assertNotEquals(TlsPin.ofSpki(ABC_SPKI), TlsPin.ofSpki("abd".encodeToByteArray()))
    }
}
