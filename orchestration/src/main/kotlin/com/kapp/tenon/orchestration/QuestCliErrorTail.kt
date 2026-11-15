// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

/**
 * 失败原因可追溯（真机验收反馈）：CLI 跑挂了之后，`task.log` 会随清场一起删掉，
 * 唯一还活着的就是 `executeCliTask` 返回的 `error` 字符串与过程中 `onEvent`
 * 吐出来的 error 事件体（含 stderr 尾部）。
 *
 * 以前 runTask 只把返回值里的 `error` 塞进 `CliTaskRun`，onEvent 的 error 事件
 * 被整段丢弃 —— 真机上的 FAILED 便签只有一句干巴巴的 "cli task failed"，
 * 为什么挂、挂在哪一行，全都不可追溯。这里把两条来源归一：
 *
 *  - `capture`：事件泵侧。只认 `eventType == "error"`，summary 与 body 以
 *    `" · "` 连接；**后到的 error 事件覆盖先到的** —— 最后一条最贴近进程退出
 *    时刻，是终局原因。空内容（全空白）不覆盖已捕获值。
 *  - `merge`：终局侧。返回值 error 与事件捕获值拼接，两者都在时以 `" · "` 分隔；
 *    都不在时回到 `null`（上层 `QuestRunner` 会落回 "cli task failed"）。
 */
internal object QuestCliErrorTail {

    /** 事件泵侧：一个 error 事件 → 应记住的失败尾部（无内容返回 null）。 */
    fun capture(eventType: String, summary: String?, body: String?): String? =
        if (eventType != "error") {
            null
        } else {
            listOfNotNull(summary, body).filter { it.isNotBlank() }.joinToString(SEPARATOR).ifBlank { null }
        }

    /** 终局侧：executeCliTask 的 error + 捕获到的最后一条事件体 → CliTaskRun.error。 */
    fun merge(runError: String?, lastEventBody: String?): String? =
        listOfNotNull(runError, lastEventBody).filter { it.isNotBlank() }.joinToString(SEPARATOR).ifBlank { null }

    private const val SEPARATOR = " · "
}
