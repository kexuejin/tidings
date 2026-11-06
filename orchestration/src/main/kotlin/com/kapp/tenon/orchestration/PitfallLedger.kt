// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import java.io.File

/**
 * 避坑规约账本（计划书 §4.3.2 的最小形态）：打回反馈与自愈成功经验沉淀成
 * 仓库内结构化规约，后续同仓派单时按匹配注入提示词前置。
 *
 * ## 为什么是仓库内文件而不是全局库
 *
 * 规约的价值跟仓库绑定（「这个仓的测试入口是 npm test」「这个仓不许改 x」），
 * 挂在宿主全局会跨仓污染；落在 `<hostRepo>/.tenon/pitfalls.jsonl` 里，随仓走、
 * 人可直接看与编辑（它是工作材料不是协议面，进不进版本库由用户决定）。
 *
 * ## 结构化而非自由文本
 *
 * 每条规约必须有 `pattern`（匹配工单涉及路径/关键词，空 = 恒匹配）与 `rule`
 * （注入提示词的那句话）。没有 pattern 的规约永远注入会把提示词撑爆 —— 上限
 * [MAX_INJECTED] 条，按「pattern 命中数多的优先、新者优先」截断。
 */
object PitfallLedger {

    /** 账本文件名（仓库根相对）。 */
    const val FILE_NAME: String = ".tenon/pitfalls.jsonl"

    /** 注入提示词的规约条数上限：超过就按匹配优先级截断。 */
    const val MAX_INJECTED: Int = 8

    /** 单条规约正文上限：注入的是「约束」不是「日志」。 */
    const val MAX_RULE_CHARS: Int = 200

    /** 一条规约。@param pattern 命中判定用的小写关键词/路径片段；空 = 恒匹配。 */
    data class Pitfall(
        val source: String,   // "reject" | "heal"
        val pattern: String,
        val rule: String,
        val createdAt: Long,
    )

    /** 追加一条（文件不存在则建；父目录一并建）。IO 失败如实返回 false，不抛。 */
    fun append(dir: File, pitfall: Pitfall): Boolean = runCatching {
        val file = File(dir, FILE_NAME)
        file.parentFile?.mkdirs()
        val line = buildString {
            append("{\"source\":\"").append(jsonEscape(pitfall.source))
            append("\",\"pattern\":\"").append(jsonEscape(pitfall.pattern.lowercase()))
            append("\",\"rule\":\"").append(jsonEscape(pitfall.rule.take(MAX_RULE_CHARS)))
            append("\",\"createdAt\":").append(pitfall.createdAt)
            append("}")
        }
        file.appendText(line + "\n")
    }.isSuccess

    /** 读全部（缺失/损坏行跳过 —— 账本允许人手编辑，坏了别把派单也炸了）。 */
    fun load(dir: File): List<Pitfall> = runCatching {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return emptyList()
        file.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            runCatching { parseLine(trimmed) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /**
     * 从账本里挑出应该注入这单的规约。
     *
     * @param scope 小写化的匹配域：工单标题 + 涉及文件路径 + 派单原文。空 = 只有恒匹配条目生效。
     */
    fun selectFor(dir: File, scope: String): List<Pitfall> {
        val all = load(dir)
        if (all.isEmpty()) return emptyList()
        val scored = all.map { it to patternScore(it.pattern, scope) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                // 针对性优先（命中 ≥1 关键词），恒匹配（空 pattern）垫底；
                // 同层内按命中数、再按新者优先。
                compareByDescending<Pair<PitfallLedger.Pitfall, Int>> { (pitfall, score) ->
                    if (pitfall.pattern.isBlank()) 0 else 1
                }.thenByDescending { (_, score) -> score }
                    .thenByDescending { (pitfall, _) -> pitfall.createdAt },
            )
        return scored.take(MAX_INJECTED).map { (pitfall, _) -> pitfall }
    }

    /** 注入提示词的组装形态：每条一行「- rule」。 */
    fun promptBlockFor(pitfalls: List<Pitfall>): String? {
        if (pitfalls.isEmpty()) return null
        return pitfalls.joinToString(separator = "\n", prefix = "Hard constraints from this repo's pitfall ledger:\n") {
            "- " + it.rule
        }
    }

    /** pattern 命中分：空 pattern 视为恒匹配（1 分）；逐个关键词命中累加。 */
    private fun patternScore(pattern: String, scope: String): Int {
        val p = pattern.trim()
        if (p.isEmpty()) return if (scope.isBlank()) 1 else 1
        return p.split(Regex("[\\s,;]+")).count { kw ->
            kw.isNotEmpty() && scope.contains(kw)
        }
    }

    private fun parseLine(line: String): Pitfall? {
        // 最小 JSON 解析：固定四键的扁平对象，不值得为一个账本引序列化器。
        fun field(key: String): String {
            val marker = "\"" + key + "\":"
            val i = line.indexOf(marker)
            if (i < 0) return ""
            val from = i + marker.length
            if (line.getOrNull(from) != '"') return ""
            val sb = StringBuilder()
            var j = from + 1
            while (j < line.length) {
                val c = line[j]
                if (c == '\\' && j + 1 < line.length) {
                    sb.append(line[j + 1]); j += 2; continue
                }
                if (c == '"') break
                sb.append(c); j += 1
            }
            return sb.toString()
        }
        // createdAt 是裸数字（append 时不加引号）：单独读到非数字为止，不能走带引号的 field。
        val createdAtMarker = "\"createdAt\":"
        val ci = line.indexOf(createdAtMarker)
        val createdAt = if (ci < 0) 0L else {
            val digits = StringBuilder()
            var j2 = ci + createdAtMarker.length
            while (j2 < line.length && line[j2].isDigit()) { digits.append(line[j2]); j2 += 1 }
            digits.toString().toLongOrNull() ?: 0L
        }
        val source = field("source")
        val rule = field("rule")
        // 空行/碎行（无 source 无 rule）不是条目：load 的 mapNotNull 会把 null 跳过。
        if (source.isBlank() && rule.isBlank()) return null
        return Pitfall(
            source = source,
            pattern = field("pattern"),
            rule = rule,
            createdAt = createdAt,
        )
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
}
