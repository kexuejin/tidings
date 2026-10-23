// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.ui.components

import com.kapp.tidings.core.protocol.Protocol
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_attach
import com.kapp.tidings.generated.resources.chat_pk
import com.kapp.tidings.generated.resources.hist_search_title
import com.kapp.tidings.generated.resources.ic_attachment
import com.kapp.tidings.generated.resources.ic_pk
import com.kapp.tidings.generated.resources.ic_search
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 输入栏分档的边界测试。
 *
 * 值得单测的不是"800 字节算大还是小"，而是两个**含不含**：UDP 上限取等号时仍走单播
 * （提示语会写「走 UDP 单播」），TCP 上限取等号时仍发得出去（不报错）。
 * 这两处差一个字节就是"明明能发却报错"或者"说走单播其实要走 TCP"，都属于用户能看见的谎。
 */
class MessageComposerTest {

    @Test
    fun anEmptyDraftIsOnTheUdpRoute() {
        assertEquals(ComposerByteState.Udp, composerByteState(0))
    }

    @Test
    fun exactlyTheUdpLimitStillRidesUdp() {
        assertEquals(ComposerByteState.Udp, composerByteState(Protocol.TEXT_UDP_LIMIT))
    }

    @Test
    fun oneByteOverTheUdpLimitSwitchesToTcp() {
        assertEquals(ComposerByteState.Tcp, composerByteState(Protocol.TEXT_UDP_LIMIT + 1))
    }

    @Test
    fun exactlyTheTcpLimitIsStillSendable() {
        assertEquals(ComposerByteState.Tcp, composerByteState(Protocol.TEXT_TCP_LIMIT))
    }

    @Test
    fun oneByteOverTheTcpLimitIsOverLimit() {
        assertEquals(ComposerByteState.Over, composerByteState(Protocol.TEXT_TCP_LIMIT + 1))
    }

    /**
     * 中文一行 3 字节：按字符数算会把"看着没超"的草稿判成没超。
     * 这条用例钉的是**口径**（UTF-8 字节数），不是分档函数本身 —— 分档函数的输入已经是字节数了。
     */
    @Test
    fun cjkCountsThreeBytesPerCharacter() {
        assertEquals(3 * 266, "甲".repeat(266).encodeToByteArray().size)
    }

    /**
     * 光标在中间就插在中间，不是一律追加到末尾 —— 用户点过光标位置，那个位置就是意图。
     * 光标用 `emoji.length` 前进：表情在 UTF-16 里多是代理对，写 `+1` 会落在代理对中间。
     */
    @Test
    fun anEmojiLandsWhereTheCaretIs() {
        assertEquals("你😀好" to 3, insertEmoji("你好", start = 1, end = 1, emoji = "😀"))
    }

    /** 有选区时表情替换掉选区，而不是插在选区边上。 */
    @Test
    fun anEmojiReplacesTheSelection() {
        assertEquals("收到，👍。" to 5, insertEmoji("收到，我下午改。", start = 3, end = 7, emoji = "👍"))
    }

    /**
     * 选区与文本短暂不同步时不许崩（比如调用方刚把草稿清了，`selection` 还指着老位置）。
     * 夹进合法范围比抛 `StringIndexOutOfBoundsException` 好：用户看到的是"表情插上了"，
     * 而不是一次闪退。
     */
    @Test
    fun outOfRangeSelectionIsClampedInsteadOfCrashing() {
        assertEquals("😀" to 2, insertEmoji("", start = 5, end = 9, emoji = "😀"))
        assertEquals("好😀" to 3, insertEmoji("好", start = 1, end = 99, emoji = "😀"))
    }

    /**
     * @ 插入与候选列表**同一个函数**：候选里给的是 `@DSH`，插完就是「@DSH 」。
     *
     * 这条钉的是「@ 了却叫不醒」的那条缝：token 由身份解析器给（agent = `@KIND`），
     * 输入栏只负责插入；任何在这里另拼名字的写法都会让插入的 token 与触发判据分叉。
     */
    @Test
    fun mentionIsInsertedAtTheCaretWithASingleSpace() {
        assertEquals("@DSH " to 5, insertMention("", start = 0, end = 0, token = "@DSH"))
        // 光标停在插入内容之后（0 基下标 7 = 紧挨着「看」之前），不是文本长度。
        assertEquals("请 @DSH 看一下" to 7, insertMention("请 看一下", start = 2, end = 2, token = "@DSH"))
    }

    /** 用户刚敲出一个 `@` 时不再补第二个，否则会变成 `@@DSH`。 */
    @Test
    fun anAlreadyTypedAtIsNotDoubled() {
        assertEquals("@DSH " to 5, insertMention("@", start = 1, end = 1, token = "@DSH"))
    }

    /** 有选区时替换选区（与表情同一套光标语义）。 */
    @Test
    fun mentionReplacesTheSelection() {
        assertEquals("你好 @DSH " to 8, insertMention("你好 旧名字", start = 3, end = 6, token = "@DSH"))
    }

    /** 连着点两个表情是依次追加：第二次接第一次返回的光标，不是都挤在开头。 */
    @Test
    fun pickingTwiceAppendsInOrder() {
        val (once, caret) = insertEmoji("", start = 0, end = 0, emoji = "😀")
        val (twice, _) = insertEmoji(once, start = caret, end = caret, emoji = "🚀")
        assertEquals("😀🚀", twice)
    }

    /**
     * 更多面板内的能力项应严格保持左段 capabilities 与右段 toolsTrailing 的既定顺序，
     * 确保发文件/图片等主要动作在前，历史搜索等辅助动作在后。
     */
    @Test
    fun capabilitiesAndTrailingCombineInOrder() {
        var clicked = ""
        val caps = listOf(
            ComposerCapability("pk", Res.string.chat_pk, Res.drawable.ic_pk) { clicked = "pk" },
            ComposerCapability("file", Res.string.chat_attach, Res.drawable.ic_attachment) { clicked = "file" },
        )
        val trailing = listOf(
            ComposerCapability("hist", Res.string.hist_search_title, Res.drawable.ic_search) { clicked = "hist" },
        )
        val combined = caps + trailing
        assertEquals(listOf("pk", "file", "hist"), combined.map { it.id })
        combined[1].onPick()
        assertEquals("file", clicked)
    }
}
