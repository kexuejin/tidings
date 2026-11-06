// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 控制面「按住载体线程」的唯一出口（[CarrierHold]）。
 *
 * 钉住五条：
 * 1. 返回值**原样**透传 —— 它是个透明看门狗，不是策略点；
 * 2. `null` 是合法结果，不是失败（"查不到对端"与"没等完"是两件事，混了就吃掉重试分支）；
 * 3. 异常原样抛出，不被吞成 fallback（处理器要靠它回 502）；
 * 4. 超预算的读数是**可判定的文本**：前缀 + 标签 + 耗时 + 预算，四样都在；
 * 5. 边界是 `>` 不是 `>=`，且未超预算**一个字都不打** —— 8 s 退化路径每天都在发生，
 *    它要是也刷日志，"超预算"第一次真正出现时就没人看了。
 *
 * 判据全部用**合成输入**（[CarrierHold.relayHoldLine]），不 `Thread.sleep` 赌时序 ——
 * `envDependentTestGate` 的 B 类抓的就是"断言前用固定 delay 同步"。
 *
 * 外加一条源码守卫：`agentNode/src/main` 里出现 `runBlocking` 的地方**只能**是
 * `CarrierHold.kt`。这条比"把 18 处逐条改掉"更值钱 —— 它防的是**下一次**随手写一处。
 */
class CarrierHoldTest {

    @Test
    fun returnsTheBlockValueUntouched() {
        assertEquals(42, CarrierHold.hold("test.returns", budgetMs = 5_000) { 42 })
    }

    @Test
    fun aNullResultIsAValueNotAFailure() {
        // 反向判据：hold 不许把 null 换成别的兜底值 —— 否则"解析不到"会被写成"超时"。
        assertNull(CarrierHold.hold<String?>("test.nullable", budgetMs = 5_000) { null })
    }

    @Test
    fun exceptionsPropagateAsThemselves() {
        val thrown = assertFailsWith<IllegalStateException> {
            CarrierHold.hold("test.throws", budgetMs = 5_000) { error("plugin is down") }
        }
        assertEquals("plugin is down", thrown.message)
    }

    @Test
    fun overBudgetLineCarriesEverythingNeededToLocateIt() {
        val line = assertNotNull(CarrierHold.relayHoldLine("send.text", elapsedMs = 9_001, budgetMs = 8_000))
        assertTrue(line.startsWith("[RELAY-HOLD] "), "读数要以固定前缀开头，才 grep 得到：" + line)
        assertTrue(line.contains("send.text"), "读数里必须有标签，否则无法定位是哪一处：" + line)
        assertTrue(line.contains("9001ms"), "读数里必须有实际耗时：" + line)
        assertTrue(line.contains("budget 8000ms"), "读数里必须有预算值（否则不知道超了多少倍）：" + line)
    }

    @Test
    fun exactlyAtBudgetIsSilent() {
        // 8 s 是"发给不可达对端"的正常退化值，按满 8 s 仍属按契约办事。
        assertNull(
            CarrierHold.relayHoldLine("send.text", elapsedMs = 8_000, budgetMs = 8_000),
            "正好按到预算不算超 —— 边界写成 >= 会让每天都在发生的退化路径变成日志噪声",
        )
        assertNull(CarrierHold.relayHoldLine("transfers.offerDeliveries", elapsedMs = 10, budgetMs = 50))
        assertNotNull(CarrierHold.relayHoldLine("transfers.offerDeliveries", elapsedMs = 51, budgetMs = 50))
    }

    @Test
    fun runBlockingLivesOnlyInTheCarrierHoldHelper() {
        val offenders = GuardSourceScan.kotlinFiles("agentNode/src/main")
            .filterNot { it.endsWith("/CarrierHold.kt") }
            .filter { path -> "runBlocking" in GuardSourceScan.stripComments(GuardSourceScan.read(path)) }
        assertTrue(
            offenders.isEmpty(),
            "agentNode 生产源里出现了 CarrierHold 之外的 runBlocking：" + offenders +
                "。处理器要按住线程等协程时请走 CarrierHold.hold(标签, 预算)：它带超预算读数，" +
                "而裸 runBlocking 按住多久没人知道（task-35 真机就是这么盲的 8 秒）。" +
                "若确有例外，把理由写进 CarrierHold 的 KDoc 再把它加进这里的白名单。",
        )
    }

    @Test
    fun theTraceIsActuallyPrinted() {
        // 上面几条测的是措辞与判定；这一条守"到底有没有走到 println"—— 删掉那个调用，
        // 判定函数照样全绿，而现场又回到"按住多久没人知道"。
        val source = GuardSourceScan.stripComments(
            GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/CarrierHold.kt"),
        )
        assertTrue(
            "relayHoldLine(what, elapsedMs, budgetMs)?.let(::println)" in source,
            "hold() 里必须把 relayHoldLine 的结果打出去，否则整条读数链是空的",
        )
    }
}
