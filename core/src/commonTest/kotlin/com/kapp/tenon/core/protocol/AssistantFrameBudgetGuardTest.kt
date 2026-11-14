// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantFrameBudgetGuardTest {

    @Test
    fun clampNoticeWithHugeChineseAndEmojisGuaranteesBudgetAndNoUtf8Corruption() {
        val longChineseText = "【任务执行报告】" + "构建与门禁检测正在执行中，请稍候并查看各项测试指标。".repeat(30)
        val emojiString = "🚀🤖📦🔥✨🎉💡🛠️".repeat(10)
        val textWithNewlinesAndQuotes = "Error at \"File.kt:10\": \n\t`val x = \"nested string\"`\n\r" + emojiString

        val notice = AssistantQuestNotice(
            questId = "quest-8902-long-id-for-testing",
            title = "极长标题测试：重构数据库触发器并且处理所有潜在的并发问题和边界情况",
            status = QuestStatus.WAITING_INPUT,
            summary = longChineseText,
            convId = ConvId.ASSISTANT,
            clientToken = "uuid-12345678-abcd-ef01-2345-6789abcdef01",
            questionText = "请问是否将变更提交到主分支并打标？\n详细内容如上所示：" + textWithNewlinesAndQuotes,
            diffStats = "+1250, -450",
            patchSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            rev = 42L
        )

        val outcome = AssistantNoticeClamper.clampNotice(notice)
        assertTrue(outcome is AssistantNoticeClamper.ClampOutcome.Success, "Clamp 必须返回 Success")
        val success = outcome as AssistantNoticeClamper.ClampOutcome.Success

        // 1. 严格锁死帧预算 <= 650 字节
        assertTrue(
            success.bytesSize <= Limits.QUEST_NOTICE_FRAME_BUDGET,
            "Notice JSON byte size must not exceed ${Limits.QUEST_NOTICE_FRAME_BUDGET}, actual was ${success.bytesSize}"
        )

        // 2. 严禁出现 UTF-8 替换字符 \uFFFD (说明 UTF-16 代理对被劈裂)
        assertFalse(
            success.json.contains('\uFFFD'),
            "Notice JSON must not contain UTF-8 replacement character \\uFFFD"
        )
        assertFalse(
            success.notice.summary.contains('\uFFFD'),
            "Clamped summary must not contain UTF-8 replacement character \\uFFFD"
        )
        if (success.notice.questionText != null) {
            assertFalse(
                success.notice.questionText!!.contains('\uFFFD'),
                "Clamped questionText must not contain UTF-8 replacement character \\uFFFD"
            )
        }
    }
}
