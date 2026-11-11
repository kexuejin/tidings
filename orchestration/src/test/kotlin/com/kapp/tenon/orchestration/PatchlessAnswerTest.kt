// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 只读任务的收敛判据：**finished + 零补丁 + 有正文 = 答案**，不是「没干活」。
 *
 * 真机现场（`@dsh-assistant git log -1 --oneline` 这种只读任务）：每轮都
 * `nothing to harvest — patchBytes=0` ⇒ 被判失败 ⇒ 催满三轮 ⇒ 卡停在 60% 不收敛，
 * 用户只能手动点「停止」。
 *
 * 每条判据的**反向探针**写在对应用例的注释里 —— 改掉哪一条会红哪一条。
 */
class PatchlessAnswerTest {

    /**
     * 第一轮就说了话 ⇒ 立刻收敛。
     *
     * 这条是复验打回来重写的：最初的设计是「先催一轮再说」，真机跑出来那轮代价是
     * **283 秒 + 13 次工具调用**，模型为了找「任务到底是什么」去翻了别的任务的产物，
     * 最后给出同一个答案。催一个已经答完的只读任务，买到的只有里程。
     *
     * 反向探针：去掉 `saidSomething -> Answered` 这条分支（只看轮数），这条红。
     */
    @Test
    fun anAnswerOnTheFirstRoundConvergesImmediately() {
        assertEquals(
            PatchlessAnswer.Verdict.Answered,
            PatchlessAnswer.verdictOf(
                status = "finished",
                saidSomething = true,
                patchlessRoundsSoFar = 0,
                canNudge = true,
            ),
            "有答案就是答案：再催一轮只会让它去翻旧现场",
        )
    }

    /**
     * 一声不吭地 finished 才可疑，给一次重来的机会。
     *
     * 反向探针：把 `saidSomething` 恒当真（删掉无声这一档），这条红。
     */
    @Test
    fun aSilentFinishedRoundGetsExactlyOneNudge() {
        assertEquals(
            PatchlessAnswer.Verdict.Nudge,
            PatchlessAnswer.verdictOf(
                status = "finished",
                saidSomething = false,
                patchlessRoundsSoFar = 0,
                canNudge = true,
            ),
            "既没补丁也没正文：这单确实可疑，允许催一次",
        )
        assertEquals(
            PatchlessAnswer.Verdict.Answered,
            PatchlessAnswer.verdictOf(
                status = "finished",
                saidSomething = false,
                patchlessRoundsSoFar = 1,
                canNudge = true,
            ),
            "催过一次还是无声 ⇒ 就此收尾，不许催第三次、更不许挂 blocked",
        )
    }

    /**
     * 人工纠偏只给一轮（`maxHealRounds = 1`）：这一轮根本没有「催办提示词」可发，
     * 再判失败等于把一个已经答完的单挂成 blocked。
     *
     * 反向探针：把 `canNudge` 从判据里删掉（只看轮数），这条立刻红。
     */
    @Test
    fun withoutRoomToNudgeAFinishedRoundIsAlreadyTheAnswer() {
        assertEquals(
            PatchlessAnswer.Verdict.Answered,
            PatchlessAnswer.verdictOf(
                status = "finished",
                saidSomething = false,
                patchlessRoundsSoFar = 0,
                canNudge = false,
            ),
            "没得催的时候别催：没有下一轮可跑，判失败只是换个地方说「做完了」",
        )
    }

    /**
     * 反向探针：崩了 / 被中止的单不许被「零补丁」这条路径洗成成功 ——
     * 哪怕它说了一大段话。
     *
     * 去掉 `status != "finished"` 那条分支，这条红。
     */
    @Test
    fun aRunThatNeverFinishedIsNeverAnAnswer() {
        for (status in listOf("failed", "aborted", "error", "timeout", "")) {
            for (said in listOf(true, false)) {
                assertEquals(
                    PatchlessAnswer.Verdict.Nudge,
                    PatchlessAnswer.verdictOf(
                        status = status,
                        saidSomething = said,
                        patchlessRoundsSoFar = 5,
                        canNudge = true,
                    ),
                    "status=$status 不是 finished ⇒ 走既有失败路径（`finished` 才是 CLI 对本轮的承诺）",
                )
            }
        }
    }

    /**
     * 收敛性：按 `maxHealRounds = 3` 真跑一遍这个判据，最多只允许一次 Nudge，
     * 之后必须收敛 —— 这条防的是「判据自己让循环多跑一轮」。
     */
    @Test
    fun theLoopCannotSpendMoreThanOneRoundOnANudge() {
        val verdicts = (1..3).map { round ->
            PatchlessAnswer.verdictOf(
                status = "finished",
                saidSomething = false,
                patchlessRoundsSoFar = round - 1,
                canNudge = round < 3,
            )
        }
        assertEquals(
            listOf(PatchlessAnswer.Verdict.Nudge, PatchlessAnswer.Verdict.Answered),
            verdicts.take(2),
            "第二轮必须收敛，不许把三轮都花在「催一个只读任务」上；实际=$verdicts",
        )
    }

    @Test
    fun theAnswerIsSaidOnceWithoutItsCodeFence() {
        // dsh 的原始形状：`text` 流与收尾 `final` 行是同一句话，且被 ``` 包住。
        assertEquals(
            "2e2e10b2 fix: /status 不再误报",
            dedupeRepeatedSpeech("``` 2e2e10b2 fix: /status 不再误报 `````` 2e2e10b2 fix: /status 不再误报 ```"),
            "同一句话说了两遍只留一遍，围栏不是内容",
        )
        assertEquals(
            "just the answer",
            dedupeRepeatedSpeech("``` just the answer ```"),
            "单份答案只去围栏",
        )
    }

    /**
     * 真机形状：正文里先有一句开场白，随后**结论那段**被收尾行原样重复一遍。
     * 收的是重复的结论，开场白留着（那是它说过的话，不是重复）。
     */
    @Test
    fun aRepeatedTailIsCollapsedButThePreambleStays() {
        val answer = "That's the last commit: `2e2e10b2 fix: /status`"
        assertEquals(
            "I'll start by checking the repo state. $answer",
            dedupeRepeatedSpeech("I'll start by checking the repo state. $answer$answer"),
            "末尾整段被原样重复时只留一份",
        )
    }

    /**
     * 反向探针：只对**整段正好是两份相同**的才收一遍。
     *
     * 正文里本来就有代码块时（`说明 + 代码` 两段内容不同），一收就丢一半。
     */
    @Test
    fun aGenuineTwoPartAnswerIsNotCollapsed() {
        val text = "先看现状：```val a = 1```"
        assertEquals(
            "先看现状： val a = 1",
            dedupeRepeatedSpeech(text),
            "两段内容不同就不许当重复收掉（否则代码块会被吞）",
        )
        assertEquals(
            "abcabc",
            dedupeRepeatedSpeech("abcabc"),
            "词中间的重合不是重复说话，不许切坏",
        )
    }
}
