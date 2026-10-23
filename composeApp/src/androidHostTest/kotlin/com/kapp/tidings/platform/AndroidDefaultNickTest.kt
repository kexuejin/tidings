// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 默认名判定只认**逐字相等**（真机缺陷：静默改名）。
 *
 * 真机后果（2026-10-18，workspace-ia 审查发现）：AndroidPlatform.isDefaultNick 原来用
 * startsWith 判「是不是默认名」，于是用户把名字改成「Redmi Note 12 Pro 工作机」这类**以机型名
 * 开头**的自定义名后，下次启动 RelayController 的补默认名逻辑会 copy(nick = fallback) 并**落盘**
 * —— 用户改过的名字被静默改回机型名，界面上没有任何提示。
 *
 * 这份测试跑在 androidUnitTest（AGP 的 JVM 单测任务 :composeApp:testDebugUnitTest）上：
 * 被测实现住在 androidMain，jvmTest 那条编译看不到它。
 */
class AndroidDefaultNickTest {

    private val model = "Redmi Note 12 Pro"

    @Test
    fun blankOrExactDefaultCountsAsDefault() {
        assertTrue(isDefaultNickExact("", model), "空 = 未定制")
        assertTrue(isDefaultNickExact("   ", model), "只有空白也算未定制")
        assertTrue(isDefaultNickExact(model, model), "与默认名逐字相等 = 未定制")
    }

    @Test
    fun legacySharedDefaultNickIsStillRecognized() {
        assertTrue(
            isDefaultNickExact(LEGACY_SHARED_DEFAULT_NICK, model),
            "老库里的旧共享默认名要认出来（否则老文件永远升不到机型名）",
        )
    }

    @Test
    fun aCustomNameThatMerelyStartsWithTheDefaultIsKept() {
        // 这就是那条静默改名：前缀相同不等于没改过。
        assertFalse(
            isDefaultNickExact("Redmi Note 12 Pro 工作机", model),
            "以机型名开头的自定义名必须保留 —— 改了它等于静默改用户的名字并落盘",
        )
        assertFalse(
            isDefaultNickExact("My Phone 备用机", model),
            "以旧共享默认名开头的自定义名同样要保留",
        )
    }

    @Test
    fun unrelatedCustomNamesAreKept() {
        assertFalse(isDefaultNickExact("工作机", model))
        assertFalse(isDefaultNickExact("redmi note 12 pro", model), "大小写不同就是不同（不做模糊匹配）")
    }

    @Test
    fun blankPlatformDefaultDoesNotSwallowEverything() {
        // 宿主传空串（测试与纯逻辑宿主的走法）时，不能因此把任何非空名字都判成默认名。
        assertTrue(isDefaultNickExact("", ""))
        assertFalse(isDefaultNickExact("工作机", ""))
        assertFalse(isDefaultNickExact("My Phone 2", ""))
    }
}