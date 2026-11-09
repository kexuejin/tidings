// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TreeSet

/**
 * 5 字段 Cron 表达式（分 时 日 月 周）的解析与「下一次触发时刻」计算。
 *
 * 落点纪律：解析器只有这一份。清单校验（PluginRegistration.validate 的 triggers
 * 分支）与桌面宿主调度中枢（DesktopHostScheduler）都引用这里——两处各写一份
 * Cron 判定就是在制造「注册收了、调度永不跑」的静默丢失。
 *
 * 纯函数：同一表达式 + 同一时区 + 同一基准时刻永远同一个答案。
 * 支持星号、步长（星号或区间后接「斜杠 步长」）、区间、列表及其组合；月/周只认数字，
 * 周字段 0 与 7 都是周日（Vixie cron 口径）；「日」与「周」同时受限时按标准 cron
 * 取并集。不支持秒与年份字段。
 */
class CronExpression private constructor(
    /** 归一化后的原始表达式（多余空白已折叠），诊断信息用它。 */
    val expression: String,
    private val minutes: CronField,
    private val hours: CronField,
    private val daysOfMonth: CronField,
    private val months: CronField,
    private val daysOfWeek: CronField,
) {

    companion object {
        private const val MAX_YEARS = 5

        fun parse(expression: String): CronExpression {
            val normalized = expression.trim().replace(Regex("\\s+"), " ")
            val parts = normalized.split(" ")
            if (parts.size != 5) {
                throw IllegalArgumentException(
                    "cron expression must have 5 fields (minute hour day-of-month month day-of-week); " +
                        "got " + parts.size + " fields in \"" + expression + "\"",
                )
            }
            return CronExpression(
                expression = normalized,
                minutes = parseField("minute", parts[0], 0, 59),
                hours = parseField("hour", parts[1], 0, 23),
                daysOfMonth = parseField("day-of-month", parts[2], 1, 31),
                months = parseField("month", parts[3], 1, 12),
                daysOfWeek = parseField("day-of-week", parts[4], 0, 7),
            )
        }

        /** 解析单个字段；非法即 IllegalArgumentException，错误信息写清怎么改。 */
        private fun parseField(name: String, raw: String, min: Int, max: Int): CronField {
            val values = TreeSet<Int>()
            var sawValue = false
            for (part in raw.split(",")) {
                if (part.isEmpty()) {
                    throw IllegalArgumentException("cron $name field has an empty list item in \"$raw\"")
                }
                val step = part.split("/")
                if (step.size > 2) {
                    throw IllegalArgumentException("cron $name field has more than one step in \"$raw\"")
                }
                val rangeText = step[0]
                val stepValue = if (step.size == 2) step[1].toIntOrNull() ?: throw IllegalArgumentException(
                    "cron $name field has non-numeric step \"${step[1]}\" in \"$raw\"",
                ) else 1
                if (stepValue < 1) {
                    throw IllegalArgumentException("cron $name field step must be >= 1, got $stepValue in \"$raw\"")
                }
                val range = if (rangeText == "*") {
                    min..max
                } else if (rangeText.contains("-")) {
                    val bounds = rangeText.split("-")
                    if (bounds.size != 2) {
                        throw IllegalArgumentException("cron $name field has malformed range \"$rangeText\" in \"$raw\"")
                    }
                    val lo = parseValue(bounds[0]) ?: throw IllegalArgumentException(
                        "cron $name field has non-numeric value \"$bounds[0]\" in \"$raw\" (month/day names are not supported; use numbers)",
                    )
                    val hi = parseValue(bounds[1]) ?: throw IllegalArgumentException(
                        "cron $name field has non-numeric value \"$bounds[1]\" in \"$raw\"",
                    )
                    if (lo > hi) {
                        throw IllegalArgumentException(
                            "cron $name field range $lo-$hi is descending (wrap-around ranges are not supported) in \"$raw\"",
                        )
                    }
                    lo..hi
                } else {
                    val v = parseValue(rangeText) ?: throw IllegalArgumentException(
                        "cron $name field has non-numeric value \"$rangeText\" in \"$raw\" (month/day names are not supported; use numbers)",
                    )
                    if (step.size == 2) v..max else v..v
                }
                var v = range.first
                while (v <= range.last) {
                    if (v < min || v > max) {
                        throw IllegalArgumentException(
                            "cron $name field value $v out of range $min..$max in \"$raw\"",
                        )
                    }
                    values += if (name == "day-of-week" && v == 7) 0 else v
                    sawValue = true
                    v += stepValue
                }
            }
            if (!sawValue) {
                throw IllegalArgumentException("cron $name field matches nothing in \"$raw\"")
            }
            return CronField(values, raw.trim() == "*")
        }

        private fun parseValue(text: String): Int? = text.toIntOrNull()
    }

    /** 单字段解析结果：升序允许值集合 + 是否通配（通配与否决定日/周的并集语义）。 */
    private class CronField(val values: TreeSet<Int>, val wildcard: Boolean)

    /** [zoned] 这一分钟（秒须为 0）是否命中本表达式。 */
    fun matches(zoned: ZonedDateTime): Boolean {
        if (zoned.second != 0) return false
        if (zoned.minute !in minutes.values) return false
        if (zoned.hour !in hours.values) return false
        if (zoned.monthValue !in months.values) return false
        return dayMatches(zoned)
    }

    /** 标准 cron 的日/周语义：两者都受限取并集，只受其一时看那一个。 */
    private fun dayMatches(zoned: ZonedDateTime): Boolean {
        val domOk = zoned.dayOfMonth in daysOfMonth.values
        val dowOk = (zoned.dayOfWeek.value % 7) in daysOfWeek.values
        return when {
            !daysOfMonth.wildcard && !daysOfWeek.wildcard -> domOk || dowOk
            !daysOfMonth.wildcard -> domOk
            !daysOfWeek.wildcard -> dowOk
            else -> true
        }
    }

    /**
     * 严格晚于 [epochMs] 的下一次触发时刻（epoch ms，按 [zone] 的墙钟语义）。
     * 返回 null = 观测视野内不再触发（如 2 月 30 日这类不可能日期；5 年视野）。
     * DST 跳变交由 ZonedDateTime 的时区规则消解——gap 内的时刻自动顺移。
     */
    fun nextAfter(epochMs: Long, zone: ZoneId): Long? {
        var t = Instant.ofEpochMilli(epochMs).atZone(zone)
        // 对齐到「下一分钟整」：秒位归零后至少前进一分钟，保证严格晚于基准。
        t = t.plusMinutes(1).withSecond(0).withNano(0)
        var iterations = 0
        val maxIterations = MAX_YEARS * 366 * 24 * 60
        while (iterations++ < maxIterations) {
            if (t.monthValue !in months.values) {
                t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone)
                continue
            }
            if (!dayMatches(t)) {
                t = t.toLocalDate().plusDays(1).atStartOfDay(zone)
                continue
            }
            if (t.hour !in hours.values) {
                t = if (t.hour < 23) {
                    t.toLocalDate().atTime(t.hour + 1, 0).atZone(zone)
                } else {
                    t.toLocalDate().plusDays(1).atStartOfDay(zone)
                }
                continue
            }
            if (t.minute !in minutes.values) {
                t = t.plusMinutes(1)
                continue
            }
            return t.toInstant().toEpochMilli()
        }
        return null
    }
}
