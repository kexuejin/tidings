// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

/**
 * 报错信息蒸馏器（Task 3.4 & §7.8 裁决）。
 *
 * 核心目标：
 * 自愈重试循环中，原始编译器/测试日志可能长达数百行（包含大量无关 Gradle 下载、
 * 堆栈无关帧或流水线噪音）。将这些噪音完整塞入下一次重试 prompt 会迅速耗尽上下文窗口
 * 并分散模型注意力。
 *
 * 蒸馏策略：
 * 1. 抽取包含致命错误、异常类、编译失败、断言失败与源文件行定位的核心错误行；
 * 2. 保留紧随其后的上下文提示行（如编译指针或直接因果行）；
 * 3. 严格限制最大行数与字符数，消除冗余噪音；
 * 4. 原始未蒸馏日志始终在宿主侧落盘供事后回溯。
 */
object ErrorDistiller {

    private const val MAX_DISTILLED_LINES = 15
    private const val MAX_DISTILLED_CHARS = 1500

    private val ERROR_INDICATORS = listOf(
        "error:",
        "exception",
        "failed",
        "failure",
        "caused by:",
        "syntaxerror",
        "typeerror",
        "assertionerror",
        "compilation error",
        "unresolved reference",
        "cannot find symbol",
        "undefined reference",
        "fatal:",
    )

    /**
     * 对原始错误日志进行关键信息蒸馏。
     *
     * 关键取舍：**不能只取最前面的匹配**。Gradle/npm 日志前半段常有
     * "download failed, retrying" 之类的噪音，真正的 `Caused by:` / 断言失败在末尾；
     * 因此优先保留「首因（前几条匹配）+ 末因（最后一段匹配）」，中间噪音丢弃。
     */
    fun distill(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val lines = trimmed.lines()
        if (lines.size <= MAX_DISTILLED_LINES && trimmed.length <= MAX_DISTILLED_CHARS) {
            return trimmed
        }

        val matched = lines.mapIndexedNotNull { index, line ->
            val lower = line.lowercase()
            index.takeIf { ERROR_INDICATORS.any(lower::contains) }
        }

        val selected: List<Int> = if (matched.isEmpty()) {
            lines.indices.filter { lines[it].isNotBlank() }.takeLast(MAX_DISTILLED_LINES)
        } else if (matched.size <= MAX_DISTILLED_LINES) {
            matched
        } else {
            // 首因 3 条 + 末因 12 条：根因与最终失败都不丢。
            (matched.take(3) + matched.takeLast(MAX_DISTILLED_LINES - 3)).distinct()
        }

        val matchedSet = matched.toSet()
        val collected = mutableListOf<String>()
        for (index in selected) {
            if (collected.size >= MAX_DISTILLED_LINES) break
            collected.add(lines[index].trimEnd())
            // 只给非匹配行补上下文（编译指针/因果行）；匹配行已由 selected 覆盖，
            // 再当上下文会把「首因+末因」的额度挤成前 15 条。
            for (next in index + 1..minOf(index + 2, lines.lastIndex)) {
                if (collected.size >= MAX_DISTILLED_LINES) break
                if (next in matchedSet || next in selected) continue
                collected.add(lines[next].trimEnd())
            }
        }

        val result = collected.joinToString("\n")
        return if (result.length > MAX_DISTILLED_CHARS) {
            result.take(MAX_DISTILLED_CHARS) + "..."
        } else {
            result
        }
    }
}
