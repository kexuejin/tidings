// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * agent 写编制的授权档位 —— **全仓唯一一份取值表**。
 *
 * 三个地方要用它，而且必须说的是同一件事：
 * 1. 宿主侧策略（`:agentNode` 的 `RosterWritePolicy`，读盘 + 判定）；
 * 2. 设置页（`composeApp` 的 `RosterWriteSection`，显示与切换）；
 * 3. 落盘文件里的字面量（`roster-write.json` 的 `tier`）。
 *
 * 抄两份字符串的后果不是「重复代码」，是**漂**：界面写 `auto`、宿主认 `automatic` 时，
 * 用户点了「直接执行」而宿主仍按 `request` 运行 —— 一个只有翻文件才查得出来的静默失效。
 * 所以放在 `:core`（两端都依赖的 common 模块）。
 */
object RosterWriteTier {

    /** 每次写都弹审批卡，机主点了同意才执行（默认）。 */
    const val REQUEST: String = "request"

    /** 机主已明确放行：直接执行，但硬上限仍在（只能改本机档案）。 */
    const val AUTO: String = "auto"

    val VALUES: List<String> = listOf(REQUEST, AUTO)
}
