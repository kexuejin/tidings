// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 这一轮 UI review 修复里**可被单测钉住**的判据。
 *
 * 为什么这些能测：它们都被抽成了纯函数（不碰平台能力、不读机器状态）。
 * 留在 Composable 里的那部分由源码级守卫盯（见 ChatReviewGuardTest 等）。
 */
class ChatReviewLogicTest {

    // ------------------------------------------------ 纯文本链接识别（人发的消息）

    @Test
    fun httpAndHttpsInPlainTextAreRecognized() {
        val text = "看 https://10.0.0.2:17879/invite 和 http://example.test/a?b=1 都是地址"
        val urls = plainTextUrls(text)
        assertEquals(2, urls.size, "两条链接都该被认出来：$urls")
        assertEquals("https://10.0.0.2:17879/invite", text.substring(urls[0].first, urls[0].second))
        assertEquals("http://example.test/a?b=1", text.substring(urls[1].first, urls[1].second))
    }

    @Test
    fun onlyHttpSchemesAreRecognized() {
        // file: / intent: / ftp: 不该给用户一条能点开本地的路。
        assertTrue(plainTextUrls("file:///etc/passwd").isEmpty(), "file: 不许被当成可点链接")
        assertTrue(plainTextUrls("ftp://example.test/x").isEmpty(), "ftp: 不在白名单")
        assertTrue(plainTextUrls("mailto:a@b.c").isEmpty())
    }

    @Test
    fun plainTextWithoutUrlsHasNoAnnotations() {
        val annotated = linkifyPlainText("就是一句话，没有链接")
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty(), "无链接时不许凭空标一段可点区域")
    }

    @Test
    fun urlAtTheVeryStartAndEndIsCovered() {
        val text = "https://a.test/x"
        val urls = plainTextUrls(text)
        assertEquals(1, urls.size)
        assertEquals(0 to text.length, urls.single(), "整条都是链接时区间必须首尾都盖到")
    }

    // ------------------------------------------------ 月历：非本月不可点

    @Test
    fun onlyCurrentMonthDaysAreSelectable() {
        assertTrue(dayCellSelectable(true), "本月日期可点")
        assertFalse(dayCellSelectable(false), "非本月日期（灰格）必须不可点 —— 点了会选中一个标题没变的日期")
    }

    // ------------------------------------------------ 历史尽头：只认 seq 这个事实

    @Test
    fun historyBeginningOnlyFromTheSeqFact() {
        assertTrue(isHistoryBeginning(1L), "seq == 1 是会话最早一条")
        assertFalse(isHistoryBeginning(2L), "seq > 1 说明前面还有")
        assertFalse(isHistoryBeginning(null), "没有 seq（还没落库）不许宣称到头")
    }
}