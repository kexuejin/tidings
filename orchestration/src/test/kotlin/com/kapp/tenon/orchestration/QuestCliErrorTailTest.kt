// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 失败原因可追溯（A1）：CLI 的 error 事件体必须活到 FAILED 便签的 summary 里。
 *
 * 现场：quest 跑挂后 `task.log` 随清场被删，唯一幸存的是 executeCliTask 的
 * 返回值与 onEvent 的 error 事件体。旧行为把事件体整段丢弃 —— 真机上便签只有
 * 一句 "cli task failed"，为什么挂不可追溯。这里把 capture/merge 两步各钉一条：
 * **反向验证**：把「丢弃事件体」的旧行为放回去（merge 只看 runError），
 * `mergesEventBodyIntoRunError` 立刻红 —— 这条测试确实在测东西。
 */
class QuestCliErrorTailTest {

    // ---- capture：事件泵侧 ----

    @Test
    fun capturesErrorEventAsSummaryAndBodyJoined() {
        assertEquals(
            "auth required · Error: authentication timed out",
            QuestCliErrorTail.capture("error", "auth required", "Error: authentication timed out"),
        )
    }

    @Test
    fun ignoresNonErrorEvents() {
        assertNull(QuestCliErrorTail.capture("progress", "step 1", "detail"), "非 error 事件不得进入失败尾部")
        assertNull(QuestCliErrorTail.capture("exit", null, null))
    }

    @Test
    fun blankErrorEventYieldsNull() {
        // 全空白的 error 事件不该产出一条空尾巴（上层会用占位文案，不是空白）。
        assertNull(QuestCliErrorTail.capture("error", "", "   "))
    }

    // ---- merge：终局侧 ----

    @Test
    fun mergesEventBodyIntoRunError() {
        assertEquals(
            "exit code 1 · cli: model not authorized",
            QuestCliErrorTail.merge("exit code 1", "cli: model not authorized"),
        )
    }

    @Test
    fun keepsRunErrorWhenNoEventWasCaptured() {
        assertEquals("exit code 1", QuestCliErrorTail.merge("exit code 1", null))
    }

    @Test
    fun keepsEventBodyWhenRunErrorIsBare() {
        assertEquals(
            "cli: stream closed before finish",
            QuestCliErrorTail.merge(null, "cli: stream closed before finish"),
        )
    }

    @Test
    fun bothAbsentStaysNullSoRunnerFallsBackToPlaceholder() {
        assertNull(QuestCliErrorTail.merge(null, null), "两条来源都空时应落回 QuestRunner 的 'cli task failed'")
    }

    // ---- 反向验证：旧行为（丢弃事件体）必须让上面的合并断言变红 ----

    @Test
    fun oldBehaviorOfDroppingTheEventBodyWouldFail() {
        val merged = QuestCliErrorTail.merge("exit code 1", "cli: model not authorized")
        // 旧行为的输出只剩 runError —— 断言它与「可追溯」的期望不同，证明本测试
        // 区分得出新旧两种世界。
        kotlin.test.assertNotEquals("exit code 1", merged)
    }
}
