// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

/**
 * 5 字段 Cron 表达式（分 时 日 月 周）校验工具。
 *
 * 遵循与 [CronExpression] 严格一致的规则（纯 Kotlin 实现，适配 commonMain 跨端源集）：
 * 1. 严格 5 个空格隔开的字段；
 * 2. 字段范围：minute (0..59), hour (0..23), day-of-month (1..31), month (1..12), day-of-week (0..7)；
 * 3. 支持星号、步长、区间、逗号列表及其组合；
 * 4. 非法表达式返回清晰错误原因，合法返回 null。
 */
object CronValidation {

    fun validate(expression: String): String? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return "cron expression cannot be empty"
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size != 5) {
            return "cron expression must have 5 fields (minute hour day-of-month month day-of-week), got ${parts.size}"
        }

        val fields = listOf(
            Triple("minute", parts[0], 0..59),
            Triple("hour", parts[1], 0..23),
            Triple("day-of-month", parts[2], 1..31),
            Triple("month", parts[3], 1..12),
            Triple("day-of-week", parts[4], 0..7),
        )

        for ((name, raw, range) in fields) {
            val err = validateField(name, raw, range.first, range.last)
            if (err != null) return err
        }
        return null
    }

    private fun validateField(name: String, raw: String, min: Int, max: Int): String? {
        val items = raw.split(",")
        var sawValue = false
        for (part in items) {
            if (part.isEmpty()) {
                return "cron $name field has an empty list item in \"$raw\""
            }
            val step = part.split("/")
            if (step.size > 2) {
                return "cron $name field has more than one step in \"$raw\""
            }
            val stepValue = if (step.size == 2) {
                step[1].toIntOrNull() ?: return "cron $name field has non-numeric step \"${step[1]}\" in \"$raw\""
            } else {
                1
            }
            if (stepValue < 1) {
                return "cron $name field step must be >= 1, got $stepValue in \"$raw\""
            }

            val rangeText = step[0]
            val lo: Int
            val hi: Int
            if (rangeText == "*") {
                lo = min
                hi = max
            } else if (rangeText.contains("-")) {
                val bounds = rangeText.split("-")
                if (bounds.size != 2) {
                    return "cron $name field has malformed range \"$rangeText\" in \"$raw\""
                }
                lo = bounds[0].toIntOrNull()
                    ?: return "cron $name field has non-numeric value \"${bounds[0]}\" in \"$raw\""
                hi = bounds[1].toIntOrNull()
                    ?: return "cron $name field has non-numeric value \"${bounds[1]}\" in \"$raw\""
                if (lo > hi) {
                    return "cron $name field range $lo-$hi is descending in \"$raw\""
                }
            } else {
                val v = rangeText.toIntOrNull()
                    ?: return "cron $name field has non-numeric value \"$rangeText\" in \"$raw\""
                lo = v
                hi = if (step.size == 2) max else v
            }

            var v = lo
            while (v <= hi) {
                if (v < min || v > max) {
                    return "cron $name field value $v out of range $min..$max in \"$raw\""
                }
                sawValue = true
                v += stepValue
            }
        }
        if (!sawValue) {
            return "cron $name field matches nothing in \"$raw\""
        }
        return null
    }
}
