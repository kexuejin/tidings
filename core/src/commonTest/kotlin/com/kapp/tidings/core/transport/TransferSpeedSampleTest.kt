// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 速度采样（EMA）的纯函数行为。采样按**整次传输**算：单文件口径在「换下一个
 * 文件」的瞬间会归零一次，界面速度会周期性跳 0 —— 这里钉住换文件不归零。
 */
class TransferSpeedSampleTest {

    @Test
    fun firstSampleHasNoSpeed() {
        val s = nextTransferSpeed(null, "f1", 0L, 1_000L)
        assertNull(s.emaBps, "首个采样没有基准，不该编一个速率")
        assertEquals(0L, s.totalDone)
    }

    @Test
    fun intervalTooShortKeepsPreviousReading() {
        val s1 = nextTransferSpeed(null, "f1", 0L, 1_000L)
        val s2 = nextTransferSpeed(s1, "f1", 600_000L, 1_200L)
        assertSame(s1, s2, "间隔不足 500ms 沿用上次采样（含速率），不重算也不抖动")
    }

    @Test
    fun firstIntervalGivesRawInstantaneousRate() {
        val s1 = nextTransferSpeed(null, "f1", 0L, 1_000L)
        val s2 = nextTransferSpeed(s1, "f1", 500_000L, 1_500L)
        assertEquals(1_000_000.0, s2.emaBps!!, 1.0)
    }

    @Test
    fun fileSwitchDoesNotResetTheWholeTransferRate() {
        var s = nextTransferSpeed(null, "f1", 0L, 0L)
        s = nextTransferSpeed(s, "f1", 1_000_000L, 1_000L)
        // f1 传完 1MB，f2 从 0 起步又传 0.5MB —— 整次口径下速度不该掉成 0
        s = nextTransferSpeed(s, "f2", 500_000L, 2_000L)
        // inst = (1.5MB - 1MB)/1s = 500KB/s；ema = 1_000_000*0.6 + 500_000*0.4
        assertEquals(800_000.0, s.emaBps!!, 1.0)
    }
}
