// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫「桌面 @ 候选与手机共用同一份过滤判据」。
 *
 * ## 真身
 *
 * 手机输入栏在某一批修复里接上了共享纯函数 `mentionQueryBeforeCursor` /
 * `filterMentionTargets`（删掉 @ 收起、按前缀过滤）。桌面漏了：候选只在刚敲 `@` 时
 * 打开、之后既不过滤也不收起 —— 候选挂在那儿盖住正文，删了 @ 还赖着；@ 后面打了字
 * 也还是把全体成员摊出来。这正是本仓最想消灭的那种分叉（同一能力两份实现）。
 *
 * ## 判据
 *
 * 桌面必须**调用**那两个共享函数（不是复制一份实现），并且候选循环用的是过滤后的
 * 列表。纯函数本身的行为由 `MentionCandidateFilterTest` 钉住；这里只钉"桌面真的用了"。
 *
 * ## 反向验证
 *
 * 把桌面 DropdownMenu 改回 `mentionTargets.forEach { target -> … }` 并删掉两个函数调用，
 * [desktopFiltersMentionCandidatesWithTheSharedHelper] 立刻变红。
 */
class DesktopMentionFilterWiringTest {

    private val path = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopComposer.kt"

    private fun src(): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    @Test
    fun desktopFiltersMentionCandidatesWithTheSharedHelper() {
        val text = src()
        assertTrue(
            text.contains("mentionQueryBeforeCursor("),
            "桌面没有读「光标前那段 @ 输入」—— 删掉 @ 后候选不会收起、也不会按前缀过滤，" +
                "候选会一直挂着盖住正文（手机端早就修了）",
        )
        assertTrue(
            text.contains("filterMentionTargets(mentionTargets"),
            "桌面没有调用共享的 filterMentionTargets —— 打 @ 后仍然把全体成员摊出来，" +
                "而手机端按前缀收窄",
        )
        assertTrue(
            text.contains("visibleTargets.forEach"),
            "桌面候选循环没用过滤后的列表（visibleTargets）—— 上面两个调用成了死代码",
        )
    }

    /** 不许退回"不过滤"的那条循环：它是本条修复的反面。 */
    @Test
    fun desktopDoesNotListEveryCandidateUnconditionally() {
        assertFalse(
            src().contains("mentionTargets.forEach { target ->"),
            "桌面候选又变回全量摊开了 —— 与手机端的行为分叉回来了",
        )
    }

    /**
     * 手机侧也不许退回"不过滤"（同一条修复的另一半）。
     *
     * 这条防的是反向回退：有人只改桌面、把手机的文件改动丢掉（或反之）。
     * 两处必须都调共享函数，才算"同一份判据"。
     */
    @Test
    fun thePhoneComposerKeepsUsingTheSameHelper() {
        val phone = GuardSourceScan.stripComments(
            GuardSourceScan.read(
                "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/MessageComposer.kt",
            ),
        )
        assertTrue(
            phone.contains("mentionQueryBeforeCursor(next.text, next.selection.end)"),
            "手机输入栏没有在读光标前的 @ 片段 —— 删掉 @ 后候选不会收起",
        )
        assertTrue(
            phone.contains("filterMentionTargets(mentionTargets, query)"),
            "手机输入栏没有按前缀过滤候选 —— 两端又会分叉",
        )
    }

    /** 「@所有人」也必须参与前缀过滤（手机端同一条判据）。 */
    @Test
    fun mentionAllParticipatesInThePrefixFilter() {
        val text = src()
        assertTrue(
            text.contains("showMentionAll"),
            "「@所有人」没有参与前缀过滤 —— 打了 @ds 它仍然排在第一行",
        )
    }
}
