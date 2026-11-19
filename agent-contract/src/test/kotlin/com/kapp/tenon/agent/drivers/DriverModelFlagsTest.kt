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
 * 把 dsh 的 mechanism 改回 `NONE` ⇒ `dshIsSupportedButHasNoFlag` 红；
 * 给 dsh 补一个假 `--model` 旗标 ⇒ 同上（构造约束会先拒绝）；
 * 把 `"agy"` 删掉，`supportedKeysAreExplicitlyListed` 必须变红。
 */
class DriverModelFlagsTest {

    @Test
    fun supportedKeysAreExplicitlyListed() {
        // **dsh 在集合里**：它没有 `--model` 旗标，但支持按次指定模型 —— 走官方 `--patch`
        // 配置覆盖（本机实测：patch 写对时 dsh 真的用新模型跑完，拿到 "OK"；写不存在的 id
        // 当场报 UNKNOWN_MODEL）。「支持」与「有旗标」是两件事，见 ModelMechanism。
        // zcode 不在：它只能在应用内选模型，命令行没有任何通道。
        assertEquals(
            setOf("claude", "codex", "agy", "opencode", "codebuddy", "dsh"),
            DriverModelFlags.supportedKeys,
            "能按次指定模型的驱动集合变了：执行配置页的可用性与保存校验都读它",
        )
    }

    @Test
    fun dshIsSupportedButHasNoFlag() {
        // 这条钉住「支持 ≠ 有旗标」这条接缝：把 dsh 的 mechanism 改回 NONE，
        // 或给它补一个假旗标，都会让这里红。
        assertTrue(DriverModelFlags.supports("dsh"), "dsh 支持按次指定模型（走 --patch）")
        assertEquals(
            CliDriverSpecs.ModelMechanism.CONFIG_PATCH,
            DriverModelFlags.mechanismOf("dsh"),
            "dsh 的传参通道是配置覆盖，不是旗标",
        )
        assertTrue(
            DriverModelFlags.flagsFor("dsh", "claude-haiku-5-5").isEmpty(),
            "dsh 没有旗标：给它拼一个 --model 会被它当成任务文本吞掉（比不生效更糟）",
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
        // dsh 不在这条列表里：它支持按次指定（走 --patch），见 dshIsSupportedButHasNoFlag。
        listOf(null, "", "   ", "native", "no-such-cli", "zcode").forEach { raw ->
            assertTrue(
                !DriverModelFlags.supports(raw),
                "未知驱动被当成支持模型（$raw）：缺席即不支持，不许有默认值",
            )
        }
    }

    @Test
    fun flagsAreAppendedForTheFourSupportedDrivers() {
        listOf("claude", "codex", "agy", "opencode", "codebuddy").forEach { key ->
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
    fun unsupportedDriverStillGetsAReason() {
        // zcode 是真正没有按次通道的一家：只能在应用内选模型，命令行无参数。
        // （dsh 曾在这条里，实测发现它走 --patch 能按次指定 —— 判据搬到
        // dshIsSupportedButHasNoFlag，这条留给真正不支持的。）
        assertTrue(!DriverModelFlags.supports("zcode"), "zcode 被登记成支持模型了 —— 它没有命令通道")

        val reason = DriverModelFlags.unsupportedReason("zcode", "glm-5.3")
        assertNotNull(reason, "zcode 带模型没有给出拒绝原因：那就是静默跑默认模型")
        assertTrue(reason.contains("cannot select a model per task"), "诊断文案要能照着改：$reason")
        assertTrue(reason.contains("zcode"), "诊断文案要点名是哪个驱动：$reason")
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
