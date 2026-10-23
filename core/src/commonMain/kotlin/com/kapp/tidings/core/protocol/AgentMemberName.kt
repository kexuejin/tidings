// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

/**
 * 群里一个可被 @ 的智能体身份 = **(设备, agent, workspace) 三元组**。
 *
 * ## 为什么是三元组
 *
 * 一台设备可以装多个 agent（`codex` / `opencode` / `dsh`…），不同的 agent 也可能操作
 * **同一个** workspace。所以任何一个单项都不足以定位"用户想 @ 的那个东西"：
 *
 * | 只取… | 会撞车的情况 |
 * |---|---|
 * | workspace | 同一目录上跑着 codex 和 opencode |
 * | agent | 一个 agent 在 15 个项目目录里都在（真机数据：`dsh-029bbbf3` 有 15 个 ws） |
 * | 两者 | 两台设备都装了 codex、都开着同名目录（极端但真实：两台机器 clone 同一个仓库） |
 *
 * 所以暴露单位就是库里 `AgentWorkspaceEntity` 已有的主键，不多不少。
 *
 * ## 名字：默认生成，进群后不许冲突
 *
 * **@ 语法永远只有一级**（`@codex-teahouse`）—— 消歧在**添加那一刻**做完，
 * 不在 @ 的时候才暴露给用户。否则用户得记住"什么时候该写设备名、什么时候不用"，
 * 而且最坏情况（两台设备 + 同名 agent + 同名 ws）根本没有更长的退路。
 *
 * 名字的作用域是**群内**（与 `memberNicks` 同一条纪律）：同一个 workspace 在 A 群叫
 * `codex-teahouse`，在 B 群可以叫别的；本地目录改名不会让群里的名字跟着变。
 */
object AgentMemberName {

    /**
     * 默认暴露名：`<agent>-<workspace>`，两边都归一化。
     *
     * - workspace 缺失（agent 没有绑定目录）时只用 agent 名；
     * - 归一化规则见 [slug]：小写、空格与下划线转 `-`、只保留字母数字与 `-`。
     */
    fun base(agentKind: String?, agentId: String?, wsTitle: String?, wsId: String?): String {
        val agent = slug(agentId?.takeIf { it.isNotBlank() } ?: agentKind).ifBlank { "agent" }
        // 名字优先 wsTitle（`teahouse-mobile` 这种用户认得出的），退回 wsId（`ws-e9b18063d183`
        // 这种内部 id 不该当名字，但比空着好）。
        val ws = slug(wsTitle?.takeIf { it.isNotBlank() } ?: wsId)
        return if (ws.isBlank()) agent else "$agent-$ws"
    }

    /**
     * 在 [taken] 里挑一个不冲突的名字：`base` 被占了就依次试 `base-2`、`base-3`…
     *
     * 这就是"默认生成一个唯一名字，后面再加的不允许冲突"的全部实现 ——
     * **不提供手改入口**（用户原话：「默认生成一个唯一名字，后面再加的不允许冲突」）。
     */
    fun unique(base: String, taken: Set<String>): String {
        val clean = base.ifBlank { "agent" }
        if (clean !in taken) return clean
        var n = 2
        while ("$clean-$n" in taken) n++
        return "$clean-$n"
    }

    /**
     * 归一化成一个能直接打出来的 @ token。
     *
     * 只保留小写字母、数字、`-`；其它字符（空格、下划线、中文、斜杠…）转成 `-`，
     * 连续 `-` 合并，首尾 `-` 去掉。
     *
     * 为什么把中文也转掉：`@` 之后是**输入法打出来的字**，中文名在 @ 补全里会出现
     * "打了一半对不上"的问题（组合输入期间每次按键都在过滤）。用 ASCII 短名最稳，
     * 而显示用的中文名仍然由 `displayName` 提供 —— 两者不必是同一个字符串。
     */
    fun slug(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length)
        var lastDash = false
        for (ch in s.lowercase()) {
            val ok = ch in 'a'..'z' || ch in '0'..'9'
            if (ok) {
                sb.append(ch)
                lastDash = false
            } else if (!lastDash && sb.isNotEmpty()) {
                sb.append('-')
                lastDash = true
            }
        }
        return sb.toString().trimEnd('-')
    }

    /**
     * **触发判据要认的 token 名**：与 [base] 产出的暴露名同一套字符集（小写 ASCII）。
     *
     * 为什么单列一个函数而不是复用 [base]：`base` 拼的是 `agent-ws` 两个成分，
     * 而触发侧可能只有一个 kind（老形态、或没有 workspace 的 agent）。
     * 两者必须**同一套归一化**（都走 [slug]），否则 `@` 插入的名字与正则认的名字会分叉 ——
     * 那就是本仓反复出现过的「@ 了却叫不醒」。
     */
    fun token(vararg parts: String?): String {
        val joined = parts.filterNotNull().map { slug(it) }.filter { it.isNotBlank() }
        return if (joined.isEmpty()) "agent" else joined.joinToString("-")
    }
}

