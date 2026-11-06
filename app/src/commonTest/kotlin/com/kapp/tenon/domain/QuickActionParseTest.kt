// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * §9.2 快捷指令注册表：全词精确匹配；未知返回 null（调用方不得消费消息）。
 * `/stop` 的**同义词**只有一份（编排层 AbortSignals）—— 这里钉的是斜杠命令表，
 * 两张表不许混写。
 */
class QuickActionParseTest {

    @Test
    fun theFourCommandsMatchExactly() {
        assertEquals(QuickAction.STATUS, parseQuickAction("/status"))
        assertEquals(QuickAction.DIFF, parseQuickAction("/diff"))
        assertEquals(QuickAction.TEST, parseQuickAction("/test"))
        assertEquals(QuickAction.STOP, parseQuickAction("/stop"))
    }

    @Test
    fun whitespaceAndCaseAreAbsorbed() {
        assertEquals(QuickAction.STATUS, parseQuickAction(" /status "))
        assertEquals(QuickAction.STATUS, parseQuickAction("/STATUS"))
        assertEquals(QuickAction.STOP, parseQuickAction("  /Stop"))
    }

    @Test
    fun anythingElseIsNotACommandAndMustNotBeConsumed() {
        assertNull(parseQuickAction("/statuss"), "前缀沾边不算：拼错 = 普通消息，调用方不得消费")
        assertNull(parseQuickAction("status"))
        assertNull(parseQuickAction("/stat us"))
        assertNull(parseQuickAction("stopleft"))
        assertNull(parseQuickAction(""))
    }
}
