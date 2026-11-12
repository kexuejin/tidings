// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「哪个驱动能按次指定模型」的词表守卫。
 *
 * ## 为什么这条不能只是「能配上就行」
 *
 * 词表一旦把某个不支持的驱动写成支持，症状是「配了模型、跑的还是默认模型」——
 * 保存不报错、派单不报错、卡面还显示「claude · opus」，只有对比 argv 才发现没有。
 * 所以两侧都要钉：支持的**必须有旗标**，不支持的**必须给出拒绝原因**。
 *
 * ## 反向探针
 *
 * 把 `"dsh"` 加进 `flagsByProfileKey`，`dshCannotSelectModelPerTask` 必须变红；
 * 把 `"agy"` 删掉，`supportedKeysAreExactlyTheFour` 必须变红。
 */
class DriverModelFlagsTest {

    @Test
    fun supportedKeysAreExactlyTheFour() {
        // 出厂的五个 CLI 里，只有 dsh 没有模型旗标（模型写在 profile 的 agent-default-model）。
        // 增删这一行都必须同时确认「argv 里真的会出现」与「执行配置页的置灰逻辑」。
        assertEquals(
            setOf("claude", "codex", "agy", "opencode"),
            DriverModelFlags.supportedKeys,
            "能按次指定模型的驱动集合变了：执行配置页的可用性与保存校验都读它",
        )
    }

    @Test
    fun aliasesResolveToTheSameDriver() {
        listOf("claude", "claude-cli", "Claude", " codex-cli ", "agy", "antigravity", "opencode-cli")
            .forEach { raw ->
                assertTrue(DriverModelFlags.supports(raw), "别名没归一：$raw 应解析到同一个档案键")
            }
    }

    @Test
    fun unknownDriverIsNotSupported() {
        listOf(null, "", "   ", "native", "no-such-cli").forEach { raw ->
            assertTrue(
                !DriverModelFlags.supports(raw),
                "未知驱动被当成支持模型（$raw）：缺席即不支持，不许有默认值",
            )
        }
    }

    @Test
    fun flagsAreAppendedForTheFourSupportedDrivers() {
        listOf("claude", "codex", "agy", "opencode").forEach { key ->
            assertEquals(
                listOf("--model", "claude-opus-4-1"),
                DriverModelFlags.flagsFor(key, "claude-opus-4-1"),
                "$key 的模型旗标形态变了（真 CLI 实测为 --model <id>）",
            )
        }
    }

    @Test
    fun blankModelProducesNoFlags() {
        listOf(null, "", "  ").forEach { model ->
            assertTrue(
                DriverModelFlags.flagsFor("claude", model).isEmpty(),
                "空模型不该产生旗标（会产生 `--model ` 这种空值参数）",
            )
            assertNull(DriverModelFlags.unsupportedReason("dsh", model), "没指定模型就不算不支持")
        }
    }

    @Test
    fun dshCannotSelectModelPerTask() {
        // dsh 的模型在 profile 配置里，命令行没有旗标：实测 `dsh --profile headless --help` 无 model 项。
        assertTrue(!DriverModelFlags.supports("dsh"), "dsh 被登记成支持模型了 —— 它没有命令行旗标")

        val reason = DriverModelFlags.unsupportedReason("dsh", "deepseek-chat")
        assertNotNull(reason, "dsh 带模型没有给出拒绝原因：那就是静默跑默认模型")
        assertTrue(reason.contains("cannot select a model per task"), "诊断文案要能照着改：$reason")
        assertTrue(reason.contains("dsh"), "诊断文案要点名是哪个驱动：$reason")
    }

    @Test
    fun unsupportedDriverGetsNoFlagsSoCallersMustCheckFirst() {
        // flagsFor 对不支持的驱动返回空表 —— 这**不是**降级，而是逼调用方先问 unsupportedReason。
        assertTrue(
            DriverModelFlags.flagsFor("dsh", "deepseek-chat").isEmpty(),
            "给 dsh 造出了旗标：真 CLI 会把它当未知参数直接退出",
        )
    }
}
