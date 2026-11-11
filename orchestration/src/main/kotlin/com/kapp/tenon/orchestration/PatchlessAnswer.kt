// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

/**
 * **CLI 说它干完了、却一行文件都没改** —— 这一轮到底算成功还是失败？
 *
 * ## 它修的是什么
 *
 * 编排器的成功判据只有一个：`status == "finished" && patch != null`。于是「帮我看看
 * git log」「这个接口怎么设计」这类只读任务永远没有补丁 ⇒ 每轮都被判「没干活」⇒
 * 催一轮、再催一轮、三轮跑满翻 `blocked`，横幅写
 * `Handed back: exceeded max heal rounds (3) — No patch produced by worker`。
 *
 * 真机现场（`@dsh-assistant git log -1 --oneline` 这种只读任务）：卡停在
 * 60% 不动、自愈不收敛，用户只能点「停止」。这不是 agent 失败，是**判据把「答完」当成了
 * 「没干活」**—— 而 agent 自己那句 `finished` 才是它对本轮的唯一承诺。
 *
 * ## 三条判据
 *
 * 1. **有话说了就不用再催。** 真机复验（`@dsh-assistant git log -1 --oneline`）：
 *    第一轮已经正确答出那句话，催办那轮模型反而去翻别的任务的产物找「任务到底是什么」，
 *    花掉 283 秒、13 次工具调用，最后给出同一个答案。**催一个已答完的只读任务，
 *    买到的只有里程**。
 * 2. **一声不吭地 finished 才催一次。** 既没补丁也没正文 —— 这单确实可疑（可能只是
 *    掐了），给模型一次重来的机会，仅一次。
 * 3. **没得催的时候就别催。** 人工纠偏只给一轮（`maxHealRounds = 1`），此时催办提示词
 *    根本发不出去，再判「失败」等于把一个已经答完的单挂成 `blocked`。
 *
 * `status != "finished"` 一律不算答案：那是 CLI 自己没跑完，走既有的失败/重试路径 ——
 * 这条不放宽，否则「进程崩了但一句没说」会被当成答完。
 */
object PatchlessAnswer {

    /** 一轮 `finished` + 零补丁之后该怎么办。 */
    enum class Verdict {
        /** 还在自愈循环里：催一轮（或把本轮如实记为失败）。 */
        Nudge,

        /** 收敛：这单是**答完**了，零改动是正常终态，卡上如实说「没改文件」。 */
        Answered,
    }

    /**
     * 判据。**纯函数** —— 同一组输入永远同一个答案，自愈循环不许自己再发明一套。
     *
     * @param status CLI 自己报的本轮终态（`executeCliTask` 的返回值）
     * @param saidSomething 这一轮有没有说出正文（`token.delta` 拼起来非空；思考不算）
     * @param patchlessRoundsSoFar 本轮之前已经出现过几次「finished 且零补丁且没正文」
     * @param canNudge 循环还允许再跑一轮吗（`healRound < maxHealRounds`）
     */
    fun verdictOf(
        status: String,
        saidSomething: Boolean,
        patchlessRoundsSoFar: Int,
        canNudge: Boolean,
    ): Verdict = when {
        // CLI 没跑完就不叫答完：崩了 / 被中止的单不许被这句话洗成成功。
        status != "finished" -> Verdict.Nudge
        // 说了话 ⇒ 那就是答案。再催一轮只会让它去翻旧现场找「任务是什么」。
        saidSomething -> Verdict.Answered
        // 一声不吭还 finished：可疑，给一次重来的机会。
        patchlessRoundsSoFar <= 0 && canNudge -> Verdict.Nudge
        else -> Verdict.Answered
    }
}

/**
 * 把 CLI 的原始发言收成**卡面那一句**。
 *
 * 两件只在这一层做的事，都是真机上看出来的：
 *
 * 1. **去代码围栏**：dsh 会把整段结论包在 ``` 里，卡面横幅于是以三个反引号开头。
 *    围栏是格式不是内容。
 * 2. **去重**:同一段话说了两遍 —— `text` 是流式正文、`final` 是收尾那行，两者内容相同，
 *    而行语义把它们都算成发言（[com.kapp.tenon.agent.drivers.CliLineEventMapper]）。
 *    卡面于是念两遍同一句 commit。判据是**整段正好是两份相同的话**才收一遍：
 *    只对完全重复生效，正文里本来就有的代码块不受影响。
 */
internal fun dedupeRepeatedSpeech(raw: String): String {
    val cleaned = raw.replace("```", " ").replace(Regex("\\s+"), " ").trim()
    val n = cleaned.length
    // 从最长的候选往下试：末尾那一段是否**紧挨着**又说了一遍。
    // 只认足够长（[MIN_REPEAT]）的重复 —— 模型真会重复的是整句结论，不是 "abcabc"。
    for (len in n / 2 downTo MIN_REPEAT) {
        // 两份之间可能有一个空格（分句），也可能一个都没有（增量直接拼上）。
        for (gap in intArrayOf(0, 1)) {
            val headStart = n - 2 * len - gap
            if (headStart < 0) continue
            if (gap == 1 && cleaned[n - len - 1] != ' ') continue
            if (!cleaned.regionMatches(headStart, cleaned, n - len, len)) continue
            return cleaned.substring(0, n - len).trim()
        }
    }
    return cleaned
}

/** 只有整句级别的重复才收：12 个字符以下的重合基本是词内部形状，不是「又说了一遍」。 */
private const val MIN_REPEAT = 12
