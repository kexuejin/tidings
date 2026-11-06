// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.domain.RelayController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDshAgentInteractionTest {

    /** 测试样本种类：kind 是声明数据的替身，不是生产默认（债#6）。 */
    private val KINDS = setOf("dsh")

    @Test
    fun testDshMentionDetectionAndPromptExtraction() {
        assertTrue(RelayController.isMentioningAgent("@DSH please refactor code", KINDS))
        assertTrue(RelayController.isMentioningAgent("Hey @dsh what is the weather?", KINDS))
        assertTrue(!RelayController.isMentioningAgent("Hello world without mention", KINDS))
        assertTrue(!RelayController.isMentioningAgent("@dsh_extra", KINDS))

        assertEquals("please refactor code", RelayController.extractAgentPrompt("@DSH please refactor code", KINDS))
        // 期望侧**不许**再对实际值做 replace/trim —— 那种断言即使函数原样返回整句也会绿。
        // 产品口径（lead 裁决）：去掉 mention、其余原样保留、**归一化空白**（删 mention 会留下双空格）。
        assertEquals("Hey what is the weather?", RelayController.extractAgentPrompt("Hey @dsh what is the weather?", KINDS))
    }
}
