// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * mDNS 服务名是**唯一标识**，却由用户随时可改的昵称拼成 —— 所以它必须被钉住，而不是靠
 * 在真机上试出来"为什么对方搜不到我"。
 *
 * 两条不变量：
 * 1. 名字里永远带着 nodeId 的短前缀 ⇒ 两台同昵称的设备不会撞名被 Bonjour 改名；
 * 2. 整个 label ≤ 63 **字节**（中文一字 3 字节，emoji 4 字节）⇒ 不会被 `registerService` 拒。
 */
class MdnsServiceLabelTest {

    private val nodeId = "b5e20a15-2319-440e-944c-d9a3a84eded1"

    @Test
    fun nickIsKeptAndNodeIdSuffixMakesItUnique() {
        assertEquals("我的手机-b5e20a", mdnsServiceLabel("我的手机", nodeId))
    }

    @Test
    fun twoDevicesWithTheSameNickStillGetDifferentLabels() {
        val other = "9f3c77aa-0000-4000-8000-000000000000"
        assertFalse(mdnsServiceLabel("我的手机", nodeId) == mdnsServiceLabel("我的手机", other))
    }

    @Test
    fun emptyOrBlankNickFallsBackToTheNodeIdOnly() {
        assertEquals("relay-b5e20a", mdnsServiceLabel("", nodeId))
        assertEquals("relay-b5e20a", mdnsServiceLabel("   ", nodeId))
    }

    @Test
    fun longAsciiNickIsTruncatedToTheByteBudget() {
        val label = mdnsServiceLabel("x".repeat(100), "abcdef01-0000-4000-8000-000000000000")
        assertEquals("${"x".repeat(56)}-abcdef", label)
        assertEquals(MDNS_LABEL_MAX_BYTES, label.encodeToByteArray().size)
    }

    @Test
    fun chineseNickIsMeasuredInBytesNotCharacters() {
        val label = mdnsServiceLabel("信".repeat(40), "abcdef01-0000-4000-8000-000000000000")
        // 56 字节日语预算 ÷ 3 = 18 个汉字，第 19 个会越界。
        assertEquals("${"信".repeat(18)}-abcdef", label)
        assertTrue(label.encodeToByteArray().size <= MDNS_LABEL_MAX_BYTES)
    }

    @Test
    fun surrogatePairIsNeverSplitInHalf() {
        // 回退点正好落在代理对中间：截断必须把半个 emoji 丢掉，而不是编码成替换字符。
        val label = mdnsServiceLabel("a" + "🐦".repeat(30), "abcdef01-0000-4000-8000-000000000000")
        assertFalse(hasLoneSurrogate(label), "服务名里出现了孤立代理项：$label")
        assertTrue(label.encodeToByteArray().size <= MDNS_LABEL_MAX_BYTES)
    }

    @Test
    fun truncateUtf8LeavesShortTextAlone() {
        assertEquals("abc", truncateUtf8("abc", 10))
        assertEquals("", truncateUtf8("abc", 0))
    }

    /** 检查有没有落单的高/低代理项。 */
    private fun hasLoneSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return true
                    i += 2
                }
                c.isLowSurrogate() -> return true
                else -> i++
            }
        }
        return false
    }
}
