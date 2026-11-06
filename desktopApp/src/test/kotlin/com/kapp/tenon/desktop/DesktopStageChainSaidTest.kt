// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

// 编排面（:orchestration）：从桌面壳下沉的工单/责任链/台账/卡片补丁。
import com.kapp.tenon.orchestration.DesktopStageChain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 链棒摘要的来源守卫：finished 事件的原因词不是「它说的话」。
 *
 * 真机现场：正常收工的棒拿不到 task.log（没有 patch，沙箱里没有日志文件），事件流
 * 最后一条是 finished(summary="stop")，而 DesktopStageChain 的 onEvent 用
 * (body ?: summary) 全收 —— 摘要于是成了 stop，随 StageRun.summary 进
 * ChainProgress.details 再到卡面 detail。值班的人看到的是「开发：stop」。
 *
 * 这两条判据都落在纯函数上，不需要起 CLI 也不需要真 git：摘要提取错的是**来源**，
 * 不是某一条驱动事件。
 */
class DesktopStageChainSaidTest {

    /** 按 runOneStage 的 onEvent 语义累积 lastSaid（入口就是被测的 saidFromEvent）。 */
    private fun lastSaidOf(events: List<Triple<String, String?, String?>>): String? {
        var lastSaid: String? = null
        events.forEach { (eventType, summary, body) ->
            DesktopStageChain.saidFromEvent(eventType, summary, body)?.let { lastSaid = it }
        }
        return lastSaid
    }

    @Test
    fun finishedReasonIsNotSomethingTheCliSaid() {
        val lastSaid = lastSaidOf(
            listOf(
                Triple("thought.delta", null, "先核对缓存键"),
                Triple("tool.started", "bash", "{\"cmd\":\"npm test\"}"),
                Triple("artifact", "patch.diff", "sha256=abc bytes=42 path=/tmp/x/patch.diff"),
                Triple("card", null, "{\"cardId\":\"diff-t-1\"}"),
                Triple("error", null, "cli exited with code 1"),
                Triple("finished", "stop", null),
            ),
        )
        assertEquals(
            "先核对缓存键",
            lastSaid,
            "只有增量文本算它说的话；工具参数 / 产物对账 / 卡片 JSON / 收工原因都不算",
        )
    }

    @Test
    fun anUnavailableTaskLogDoesNotFallBackToTheFinishReason() {
        // 这一棒没有 patch（审查棒常常不改文件）⇒ 没有 harvest 目录，task.log 拿不到；
        // 事件流里只有正常收工。修好之前这里的摘要会是 stop，didNothing 也会被它带成 false。
        val lastSaid = lastSaidOf(listOf(Triple("finished", "stop", null)))
        assertNull(
            DesktopStageChain.stageSummaryOf(logTail = null, lastSaid = lastSaid),
            "task.log 拿不到时摘要必须为空 —— stop 是收工原因，不是它说的话",
        )
        assertTrue(
            DesktopStageChain.stageSummaryOf(logTail = "   ", lastSaid = lastSaid).isNullOrBlank(),
            "空白日志同样不能退回收工原因；didNothing 判的就是 summary.isNullOrBlank()",
        )
    }

    @Test
    fun realSpeechStillFlowsWhenTheLogIsMissing() {
        val lastSaid = lastSaidOf(listOf(Triple("token.delta", null, "改成 cityId 了")))
        assertEquals("改成 cityId 了", lastSaid)
        assertEquals(
            "改成 cityId 了",
            DesktopStageChain.stageSummaryOf(logTail = null, lastSaid = lastSaid),
            "拿不到日志时，事件流里的真实发言仍然是合法的兜底摘要",
        )
    }
}
