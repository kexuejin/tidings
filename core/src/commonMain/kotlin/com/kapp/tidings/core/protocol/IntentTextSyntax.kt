// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

/**
 * 群文本里的派单指令语法（T-G2，D-031；方案 §2.3 模式 C 的聊天入口）：
 *
 * ```
 * @Architect 重构网络层 require:gpu,linux
 * ```
 *
 * `require:` 后接逗号分隔的标签（形状按 [ChannelTags.isWellFormed] 校验）。
 * 语义：这是**派单指令**不是聊天内容 —— 解析出的标签进 `agentIntent.requiredTag`，
 * 指令本体从正文剥掉（发出去的群文本不带它，@ 到的 agent 才消费它）。
 *
 * 纯函数：解析可单测，不碰发送路径。
 */
object IntentTextSyntax {

    const val REQUIRE_MARKER = "require:"

    /**
     * @return (剥掉指令后的正文, 标签列表)；没有指令 = (原文, 空表)。
     *  只认**末尾**的 require: 段（正文中间的 `require:` 是普通文本）；
     *  任一标签形状不合法 ⇒ 整段按普通文本保留（宁可不路由也不猜用户意图）。
     */
    fun parse(text: String): Pair<String, List<String>> {
        val marker = text.lastIndexOf(REQUIRE_MARKER)
        if (marker < 0) return text to emptyList()
        val before = text.substring(0, marker)
        if (before.isNotBlank() && !before.endsWith(' ')) return text to emptyList()
        val tagPart = text.substring(marker + REQUIRE_MARKER.length).trim()
        if (tagPart.isEmpty()) return text to emptyList()
        val tags = tagPart.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isEmpty() || tags.any { !ChannelTags.isWellFormed(it) }) return text to emptyList()
        val cleaned = before.trimEnd()
        return cleaned to tags
    }
}
