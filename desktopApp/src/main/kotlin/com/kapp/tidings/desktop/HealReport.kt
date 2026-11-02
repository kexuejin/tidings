// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

/**
 * 自愈循环跑完还没成时，交回给工程师的**那句原因**。
 *
 * ## 它修的是什么
 *
 * 循环里每轮都会算一次 `executionError`，从前只有一个 `var` —— 后一轮直接盖掉前一轮。
 * 真机上首轮报的是 `cli exit=1`（模型侧挂了），末轮报的是 `No patch produced by worker`
 * （模型没交产物）。横幅只留下末轮那句，于是看横幅的人会以为「模型跑了、只是没交东西」，
 * 而真实原因是**它第一轮就没跑起来**。两个原因指向完全不同的修法，丢掉首因等于把人带偏。
 *
 * ## 三条判据
 *
 * 1. **首因必须在**：第一条原因带上它的轮次；
 * 2. **重复不重复说**：三轮都报同一句时只写一遍 —— 同一句话写三遍不增加信息，
 *    只会把横幅撑长到看不见重点；
 * 3. **轮次可核对**：出现多个原因时标出各自首次出现的轮次，人能自己回查日志。
 */
internal object HealReport {

    /**
     * @param rounds 每轮的失败原因，按轮次顺序；空列表返回 null（没有失败就没有交回）。
     * @param maxRounds 上限轮数，写进横幅让人知道「跑满了」。
     */
    fun bannerOf(rounds: List<String>, maxRounds: Int): String? {
        val cleaned = rounds.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        val head = "Handed back: exceeded max heal rounds (" + maxRounds + ")"
        // 首次出现的轮次（LinkedHashMap 保序：横幅读起来就是时间顺序）。
        val firstAt = LinkedHashMap<String, Int>()
        cleaned.forEachIndexed { index, reason -> firstAt.putIfAbsent(reason, index + 1) }
        if (firstAt.size == 1) return head + " — " + firstAt.keys.first()
        val parts = firstAt.map { (reason, round) -> "round " + round + ": " + reason }
        return head + " — " + parts.joinToString("; ")
    }
}
