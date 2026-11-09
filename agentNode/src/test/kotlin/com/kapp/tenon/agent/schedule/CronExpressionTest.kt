// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.schedule

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cron 解析与 nextAfter 的时间原语测试。判据按「真实日历事实」断言
 * （周几、跨周、月末），不赌实现细节。
 */
class CronExpressionTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun nextAfter(cron: String, from: String): Long =
        CronExpression.parse(cron).nextAfter(at(from), zone)!!

    @Test
    fun dailyAtEightOnWeekdaysAdvancesAcrossWeekend() {
        val cron = "0 8 * * 1-5"
        // 周五 07:00 → 当天 08:00
        assertEquals(at("2026-11-06T08:00"), nextAfter(cron, "2026-11-06T07:00"))
        // 周五 08:00 整之后的下一触发 = 下周一（2026-11-09）08:00
        assertEquals(at("2026-11-09T08:00"), nextAfter(cron, "2026-11-06T08:00"))
        // 周六任意时刻 → 周一 08:00
        assertEquals(at("2026-11-09T08:00"), nextAfter(cron, "2026-11-07T15:30"))
    }

    @Test
    fun stepAndListAndRangeFieldsAreHonored() {
        // */15 分钟：10:00 → 10:15
        assertEquals(at("2026-11-06T10:15"), nextAfter("*/15 * * * *", "2026-11-06T10:00"))
        // 列表 1,3,5（周一三五）：周五 09:00 → 下一个命中是周一? 周五本身 9 点整之后 → 下周一
        assertEquals(at("2026-11-09T09:00"), nextAfter("0 9 * * 1,3,5", "2026-11-06T09:00"))
        // 范围带步长：9-17 点每 2 小时的整点，16:30 → 17:00
        assertEquals(at("2026-11-06T17:00"), nextAfter("0 9-17/2 * * *", "2026-11-06T16:30"))
    }

    @Test
    fun dayOfMonthAndDayOfWeekUnionSemantics() {
        // 日与周同时受限 = 并集（标准 cron）：每月 3 号 + 每周日
        val cron = "0 12 3 * 0"
        // 2026-11-01（周日）12:00 整之后：最近的命中是 11-03（周二，dom 单独命中）——
        // 并集语义下 dom 分支比下一个周日（11-08）更早。
        assertEquals(at("2026-11-03T12:00"), nextAfter(cron, "2026-11-01T12:00"))
        // 2026-11-03 之后：下一个命中是周日 11-08（dow 单独命中）。
        assertEquals(at("2026-11-08T12:00"), nextAfter(cron, "2026-11-03T12:00"))
    }

    @Test
    fun dowZeroAndSevenAreBothSunday() {
        val base = "2026-11-08T11:00" // 周日
        assertEquals(at("2026-11-08T12:00"), nextAfter("0 12 * * 0", base))
        assertEquals(at("2026-11-08T12:00"), nextAfter("0 12 * * 7", base), "7 与 0 同为周日（Vixie 口径）")
    }

    @Test
    fun minuteGranularityIsStrictlyAfterBase() {
        // 基准恰在触发点上：返回下一次而非本刻（时区统一用 UTC 构造期望值）
        val utc = ZoneId.of("UTC")
        val atUtc: (String) -> Long = { text -> LocalDateTime.parse(text).atZone(utc).toInstant().toEpochMilli() }
        val cron = CronExpression.parse("30 * * * *")
        assertEquals(
            atUtc("2026-11-06T11:30"),
            cron.nextAfter(atUtc("2026-11-06T10:30"), utc),
        )
        // 基准带秒：对齐到下一分钟整再判
        val withSeconds = LocalDateTime.parse("2026-11-06T10:29:30").atZone(utc).toInstant().toEpochMilli()
        assertEquals(atUtc("2026-11-06T10:30"), cron.nextAfter(withSeconds, utc))
    }

    @Test
    fun impossibleDateReturnsNullWithinHorizon() {
        // 2 月 30 日不存在：5 年视野内无解
        assertNull(CronExpression.parse("0 12 30 2 *").nextAfter(at("2026-11-06T00:00"), zone))
        // 2 月 29 日存在但四年一次：5 年视野内必然命中一次
        assertNotNull(CronExpression.parse("0 12 29 2 *").nextAfter(at("2026-11-06T00:00"), zone))
    }

    /**
     * 观测视野是**墙钟 5 年**，不是「5 年的循环次数」。
     *
     * 判别力的来源：2100 年不是闰年（能被 100 整除、不能被 400 整除），所以从
     * 2097-03-01 起，下一个 2 月 29 日在 **2104-02-29**，距基准 8 年 —— 超出 5 年视野。
     * 旧实现拿 `MAX_YEARS * 366 * 24 * 60` 次迭代当 5 年，而跳月/跳日各只算一次迭代
     * （每年约 40 次），260 万次迭代实际能扫几万年，于是会把 8 年后的解当成「视野内」
     * 返回，与文档承诺的 5 年不符。
     */
    @Test
    fun observationHorizonIsFiveWallClockYearsNotFiveYearsOfIterations() {
        assertNull(
            CronExpression.parse("0 12 29 2 *").nextAfter(at("2097-03-01T00:00"), zone),
            "2104-02-29 距基准 8 年，超出 5 年墙钟视野，必须如实返回 null（旧实现会返回 2104-02-29）",
        )
        // 视野内的同一个表达式仍要命中——证明不是把整条路径改成永远 null。
        assertEquals(at("2028-02-29T12:00"), nextAfter("0 12 29 2 *", "2026-11-06T00:00"))
    }

    @Test
    fun matchesIsMinuteExact() {
        val cron = CronExpression.parse("0 8 * * 1-5")
        assertTrue(cron.matches(LocalDateTime.parse("2026-11-06T08:00").atZone(zone)))
        assertTrue(!cron.matches(LocalDateTime.parse("2026-11-06T08:00:30").atZone(zone)), "秒位非零不算命中")
        assertTrue(!cron.matches(LocalDateTime.parse("2026-11-07T08:00").atZone(zone)), "周六不命中（1-5 周一至周五）")
        assertTrue(!cron.matches(LocalDateTime.parse("2026-11-06T09:00").atZone(zone)))
    }

    @Test
    fun invalidExpressionsFailWithActionableMessages() {
        val cases = mapOf(
            "0 8 *" to "5 fields", // 字段数不对
            "60 8 * * *" to "out of range", // 分钟越界
            "a 8 * * *" to "non-numeric", // 非数字
            "0 8 * 13 *" to "out of range", // 月份越界
            "0 8 5-2 * *" to "descending", // 降序区间
            "0 8 * * 1/0" to "step", // 步长非法
        )
        cases.forEach { (expr, expectedFragment) ->
            val error = assertFailsWith<IllegalArgumentException>("\"$expr\" 应被拒绝") {
                CronExpression.parse(expr)
            }
            assertTrue(
                error.message!!.contains(expectedFragment),
                "\"$expr\" 的错误信息应包含「$expectedFragment」：" + error.message,
            )
        }
    }
}
