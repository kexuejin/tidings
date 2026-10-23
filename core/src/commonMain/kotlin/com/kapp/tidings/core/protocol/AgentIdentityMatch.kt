// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

/**
 * 两个「agent 身份」是不是同一个 agent（容忍精度差异）。
 *
 * ## 为什么不能直接比字符串
 *
 * 同一个 agent 在不同帧里的身份精度**不一样**，真机数据就能看到：
 *
 * | 来源 | 值 |
 * |---|---|
 * | 会话帧 `agentId` | `dsh-029bbbf3` |
 * | 会话帧 `agentKind` | `DSH` |
 * | 群元数据 `VirtualAgentMember.agentId`（入驻时兜底到 kind） | `DSH` |
 *
 * 入驻写进去的是 `DSH`，会话报的是 `dsh-029bbbf3` —— 直接 `==` 永远对不上，
 * 于是「刚入驻的 agent 找不到自己的会话」，`targetSessionId` 恒为 null，
 * 注入永远被拒。
 *
 * ## 判定规则
 *
 * 命中任一即算同一个 agent：
 *
 * 1. 忽略大小写相等（`DSH` == `dsh`）；
 * 2. 一方是另一方的**前缀到 `-` 之前**（`dsh` ~ `dsh-029bbbf3`）。
 *
 * 第 2 条的实际代价（**说清楚，别再说反**）：截到 `-` 之前再比，等于**同 head 的实例
 * 全部视为同一个 agent** —— `dsh-old`、`dsh-1a2b`、`dsh` 三者两两都算相等。
 *
 * 上一版这里写的是「只比较 `-` 之前那段，才不会串到别的实例」，那句话是**错的**：
 * 正因为它停在 `-` 之前，所有同 head 的实例才会被判成同一个。
 *
 * 为什么仍然这样实现：这个函数只回答「这条消息**大概**是给哪个 agent 的」，用来把候选
 * 范围缩小；真正决定「投给哪个会话」的是 `AgentSessionPick`，它按 `wsId` 精确区分。
 * 所以这里把 `dsh-old` 与 `dsh-1a2b` 当成同一个是无害的 —— 它们随后会被工作区绑定的
 * 那一层分开。**但绝不能用这个函数做「是不是同一个实例」的判定**：它不是那个用途，
 * 也不具备那个精度。
 */
fun sameAgent(candidate: String?, kind: String?, wanted: String?): Boolean {
    fun clean(v: String?): String = v?.trim()?.lowercase().orEmpty()
    val want = clean(wanted)
    if (want.isEmpty()) return false
    // 候选的两条身份都试：会话可能只报了其中一条。
    for (raw in listOfNotNull(candidate, kind)) {
        val c = clean(raw)
        if (c.isEmpty()) continue
        if (c == want) return true
        // 比到第一个 `-` 之前的那段（没有 `-` 就是整串）。
        val cHead = c.substringBefore('-')
        val wHead = want.substringBefore('-')
        if (cHead.isNotEmpty() && cHead == wHead) return true
    }
    return false
}
