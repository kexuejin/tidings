// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.core.protocol.Limits
import java.io.File

/**
 * Reviewer 审计看板（三权分立闭环的第三权）：全部执行棒完成、验收棒合流前，对每根棒的
 * Diff/产物做三项审计，结论以 checks[] 挂上合流卡。任一不过 ⇒ 合流卡转 blocked，不自动
 * 合流 —— fail-closed。
 *
 * ## 判据来源（同款判据，不是另立一套）
 *
 * - **语法规范**（cjk 硬编码 + 施工痕迹）：形态判定取自 CJK 字面量门禁与施工痕迹门禁的
 *   判定核心 —— 这里是给 diff 行做的**最小复制**（patch.diff 是 unified diff 文本，没有
 *   词法上下文可依赖，只能对 `+` 行做保守的字面判定），权威源是仓库根 tools/ 下的两个
 *   门禁脚本；判定口径分歧时以门禁为准。
 * - **白名单键**：对照宿主注入的取值域清单（[ValueDomain]）。清单由宿主装配侧从协议取值域
 *   对象（`GROUP_TEXT_KEYS` / `AgentAckKinds` 这类「键 + 取值域只能有一份定义」的落点）
 *   生成 —— 本类不背协议事实，背了就会分叉出第二份定义。
 * - **帧预算**：合流卡整体字节 ≤ [Limits.CARD_BYTES]。
 *
 * ## 缺输入不是通过
 *
 * 白名单清单没注入 ⇒ 该项如实回 SKIP（[SKIP]），观察语写明原因。SKIP 不阻塞合流
 * （它不是「验了没过」，是「没验到」），但也绝不计成 done。
 */
object ReviewerAudit {

    /** 一份「键 → 合法取值域」清单；由宿主从协议取值域对象装配，本模块不自持协议事实。 */
    data class ValueDomain(
        /** 载荷名（如 group-text / agent-ack），只用于审计结论的可读性。 */
        val payload: String,
        /** 合法键集合（如 GROUP_TEXT_KEYS 的白名单）。 */
        val keys: Set<String>,
        /** 键内取值域（键 → 合法值集合）；空 = 只审键、不审值。 */
        val valueOf: Map<String, Set<String>> = emptyMap(),
    )

    /** 审计词表：status 复用手机端认的棒级五词；SKIP 借 pending 承载。 */
    const val DONE = "done"
    const val FAILED = "failed"
    const val SKIP = "pending"

    // ---------------------------------------------------------------- a) 语法规范

    /**
     * CJK 区段判定（与 CJK 字面量门禁的区段表同源；弯引号刻意不收，同一条注记）。
     * patch 的 `+` 行是**即将进生产源码的字面量正文候选**，保守起见正文任何位置出现
     * CJK 即命中 —— diff 行没有词法状态（看不出它是不是注释），宁可多报让人看一眼。
     */
    fun containsCjk(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val cp = if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                val full = Character.toCodePoint(ch, text[i + 1])
                i += 2
                full
            } else {
                i += 1
                ch.code
            }
            if (cp in 0x2E80..0x2EFF || cp in 0x3000..0x303F || cp in 0x3040..0x30FF ||
                cp in 0x3100..0x312F || cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF ||
                cp in 0xAC00..0xD7AF || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F ||
                cp in 0xFF00..0xFFEF || cp in 0x20000..0x2FA1F
            ) return true
        }
        return false
    }

    /** 施工痕迹的七条形态（与施工痕迹门禁的 RULES 同款判据的最小复制）。 */
    private val TRACE_RULES = listOf(
        "iso-date" to Regex("\\b20[0-9]{2}-[01][0-9]-[0-3][0-9]\\b"),
        "device-model" to Regex("\\b(?:Pixel\\s?[0-9]+|iPhone\\s?[0-9A-Za-z]+|iPad\\s?[0-9A-Za-z]+|小米\\s?[0-9]*|Redmi\\s?[0-9A-Za-z]*)\\b"),
        "lan-ip" to Regex("\\b192\\.168\\.\\d{1,3}\\.\\d{1,3}\\b"),
        "local-path" to Regex("/(?:Users|Volumes)/[A-Za-z]"),
        "decision-no" to Regex("决议\\s*#\\s*\\d+"),
        "internal-file" to Regex("(?:^|[^\\w/])(?:PLAN\\.md|ARCHITECTURE\\.md|docs/plans/)"),
        "timestamp-trace" to Regex("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d:[0-5]\\d\\b"),
    )

    /**
     * 对一根棒的 patch 做语法规范审计：只看 `+` 正文行（新增内容才是这次施工），跳过
     * `+++` 文件头。
     */
    fun syntaxFailureOf(patch: String?): String? {
        if (patch.isNullOrBlank()) return null
        patch.lineSequence().forEachIndexed { idx, line ->
            if (!line.startsWith("+") || line.startsWith("+++")) return@forEachIndexed
            val body = line.removePrefix("+")
            if (containsCjk(body)) {
                return "新增行含硬编码 CJK 文案（应进资源文件）：L" + (idx + 1) + " " + body.take(40)
            }
            TRACE_RULES.firstOrNull { it.second.containsMatchIn(body) }?.let { (id, _) ->
                return "新增行含施工痕迹（" + id + "）：L" + (idx + 1) + " " + body.take(40)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- b) 白名单键

    /**
     * 对一根棒的 patch 里的 JSON 键改动做白名单对照。
     *
     * 判据（与「发送侧不产出注定被拒的帧」同一条不变量）：新增行里出现 `"key":` 形状
     * 且该键不在任何已注入清单里 ⇒ 视为未声明键。**这只对改动行生效** —— 既有代码里
     * 早就在用的键不归这一棒管。返回失败观察语；过了返回 null。
     */
    fun whitelistFailureOf(patch: String?, domains: List<ValueDomain>?): String? {
        if (patch.isNullOrBlank()) return null
        if (domains.isNullOrEmpty()) return null // 调用方负责在无清单时产出 SKIP 项
        val allKeys = domains.flatMap { it.keys }.toSet()
        patch.lineSequence().forEachIndexed { idx, line ->
            if (!line.startsWith("+") || line.startsWith("+++")) return@forEachIndexed
            KEY_LITERAL.findAll(line).forEach { m ->
                val key = m.groupValues[1]
                if (key in allKeys) return@forEach
                if (key in IGNORED_KEY_SHAPES) return@forEach
                return "新增键 \"" + key + "\" 不在任何取值域清单：L" + (idx + 1)
            }
        }
        return null
    }

    /** 键名字面量："key" 后跟冒号（JSON 键的形状）。 */
    private val KEY_LITERAL = Regex("\"([A-Za-z][A-Za-z0-9_.-]{0,63})\"\\s*:")

    /** 形状上像键但明确不进协议帧对照的（注释性文字、URL、mime 等自由文本键）。 */
    private val IGNORED_KEY_SHAPES = setOf("http", "https", "mailto", "text", "image")

    // ---------------------------------------------------------------- c) 帧预算

    /** 合流卡整体字节 ≤ CARD_BYTES。观察语如实带量得值；超限返回失败观察语，否则 null。 */
    fun budgetFailureOf(cardJson: String): String? {
        val bytes = cardJson.toByteArray(Charsets.UTF_8).size
        if (bytes > Limits.CARD_BYTES) {
            return "合流卡 " + bytes + " B 超出帧预算 " + Limits.CARD_BYTES + " B（超限拒收，不截断）"
        }
        return null
    }

    /** 帧预算 checks[] 项：[measuredBytes] 为 0 表示探针量测前的占位（对外只发量过的）。 */
    fun budgetCheck(measuredBytes: Int): ChainCheck {
        if (measuredBytes <= 0) {
            return ChainCheck("budget", "帧预算 ≤ " + Limits.CARD_BYTES + " B", SKIP,
                "量测中")
        }
        return ChainCheck(
            "budget",
            "帧预算 ≤ " + Limits.CARD_BYTES + " B",
            if (measuredBytes <= Limits.CARD_BYTES) DONE else FAILED,
            "合流卡实测 " + measuredBytes + " B",
        )
    }

    // ---------------------------------------------------------------- 组装

    /**
     * 全部执行棒的审计结论：每棒两条（语法规范 / 白名单键）+ 帧预算一条（由调用方量测
     * 后追加）。无 patch 的棒（审查棒只读）两条都如实 SKIP —— 没有可审的输入不是通过。
     */
    fun checksFor(
        executed: List<Pair<ChainStage, StageRun>>,
        domains: List<ValueDomain>?,
    ): List<ChainCheck> = executed.flatMapIndexed { i, (stage, run) ->
        val patch = run.patchPath?.let { p -> runCatching { File(p).readText() }.getOrNull() }
        val ordinal = "第 " + (i + 1) + " 棒"
        val syntax = if (patch == null) {
            ChainCheck("syntax-" + (i + 1), "语法规范 · " + stage.role, SKIP, ordinal + " 无 patch（只读或无改动），无从审")
        } else {
            val fail = syntaxFailureOf(patch)
            ChainCheck("syntax-" + (i + 1), "语法规范 · " + stage.role,
                if (fail == null) DONE else FAILED, fail ?: "patch " + patch.length + " B 无 CJK/施工痕迹命中")
        }
        val whitelist = if (patch == null) {
            ChainCheck("keys-" + (i + 1), "白名单键 · " + stage.role, SKIP, ordinal + " 无 patch（只读或无改动），无从审")
        } else if (domains.isNullOrEmpty()) {
            ChainCheck("keys-" + (i + 1), "白名单键 · " + stage.role, SKIP,
                "宿主未提供取值域清单，跳过对照（缺输入不是通过）")
        } else {
            val fail = whitelistFailureOf(patch, domains)
            ChainCheck("keys-" + (i + 1), "白名单键 · " + stage.role,
                if (fail == null) DONE else FAILED, fail ?: "新增键均在 " + domains.size + " 份清单内")
        }
        listOf(syntax, whitelist)
    }
}