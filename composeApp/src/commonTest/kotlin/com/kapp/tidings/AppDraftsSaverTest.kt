// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings

import com.kapp.tidings.feature.chat.draftsByConvFromSaverValue
import com.kapp.tidings.feature.chat.draftsByConvToSaverValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 壳里草稿表（`draftsByConv`）的 Saveable 映射。
 *
 * 草稿是用户打到一半的字，存档映射错一个字段就是「转个屏草稿串到别的会话」——
 * 比清空更糟。roundtrip 在这里钉死，格式契约见 `feature/chat` 下
 * `DraftsByConvSaveable.kt` 里 `draftsByConvToSaverValue` 的 KDoc（手机壳与桌面壳
 * 共用那一份；"全仓只有一个声明点"由 `DesktopDraftsSaveableGuardTest` 守）。
 */
class AppDraftsSaverTest {

    @Test
    fun roundtripPreservesEveryDraft() {
        val drafts = mapOf(
            "node-aaaaaaaa" to "打到一半的话",
            "group:abc123" to "group draft",
        )
        assertEquals(drafts, draftsByConvFromSaverValue(draftsByConvToSaverValue(drafts)))
    }

    @Test
    fun roundtripOfEmptyMapStaysEmpty() {
        val empty = emptyMap<String, String>()
        assertEquals(empty, draftsByConvFromSaverValue(draftsByConvToSaverValue(empty)))
    }

    @Test
    fun savedValueStartsWithTheEntryCount() {
        // 格式第一项就是条数：恢复端靠它核对整张表的形状。
        val drafts = mapOf("a" to "1", "b" to "2")
        val saved = draftsByConvToSaverValue(drafts)
        assertEquals(2, saved.first())
        assertEquals(1 + 2 * 2, saved.size)
        assertTrue(saved.all { it is String || it is Int })
    }

    @Test
    fun malformedArchiveIsRejectedRatherThanGuessed() {
        assertNull(draftsByConvFromSaverValue(emptyList<Any>()))
        // 条数说有两条、实际只带了一条 —— 表残了，放弃恢复。
        assertNull(draftsByConvFromSaverValue(listOf(2, "a", "1")))
        // 非法表：首项不是条数。
        assertNull(draftsByConvFromSaverValue(listOf("not-a-count")))
    }
}
