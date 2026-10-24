// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.ScreenMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 自适应帧率 [ScreenRate] 的上下界与平滑行为（纯计算，不起任何连接）。
 *
 * ## 为什么值得钉
 *
 * 它决定的是「一秒钟向对端要多少张画面」：档位错了不会报错，只会让用户在蜂窝上
 * 花掉不该花的流量、或者在 Wi-Fi 上看到一卡一卡的画面。而它的状态机有三个容易
 * 写错的边界：
 * 1. 降档要**连续**三拍慢，中间拍一快就得清零（否则偶尔一次 GC 抖动就把画质降下来）；
 * 2. 降档有**地板** 3fps，不能无限往下掉（再低就不像"看得到对方在做什么"了）；
 * 3. 升档要「距上次降档 10 秒」且「持续快 10 秒」两个条件同时成立 ——
 *    只要一个不满足就停在原档（抖动式升降会比低帧率更难受）。
 *
 * 这里用假时钟（`nowMs` 是入参），所以每条都是确定性的，不赌真实时间。
 */
class ScreenRateTest {

    /** Auto 档 10fps 的慢拍阈值是 80ms：`(1000/10)*0.8`。取 81 保证越线。 */
    private val slowAt10 = 81L

    /** 5fps 的慢拍阈值是 160ms。 */
    private val slowAt5 = 161L

    /** 3fps 的慢拍阈值是 266ms。 */
    private val slowAt3 = 267L

    private fun degradedTo5(): ScreenRate = ScreenRate().apply {
        repeat(3) { observe(slowAt10, 1_000L + it) }
    }

    @Test
    fun theDefaultIsAutoAtTen() {
        val rate = ScreenRate()
        assertEquals(ScreenMode.Auto, rate.mode)
        assertEquals(10, rate.fps)
    }

    @Test
    fun everyFixedModePinsItsOwnFrameRate() {
        // 三档固定模式是"用户说了算"，不受采样快慢影响。
        ScreenRate().apply {
            setMode(ScreenMode.Economy)
            assertEquals(3, fps)
            setMode(ScreenMode.Standard)
            assertEquals(5, fps)
            setMode(ScreenMode.Smooth)
            assertEquals(10, fps)
            setMode(ScreenMode.Auto)
            assertEquals(10, fps, "回到 Auto 从最高档重新自适应")
        }
    }

    @Test
    fun fixedModesIgnoreTheSamplingFeedback() {
        val rate = ScreenRate().apply { setMode(ScreenMode.Smooth) }
        repeat(5) { rate.observe(slowAt3, 10_000L + it) }
        assertEquals(10, rate.fps, "用户指定了 Smooth，慢拍不许自己把档位降下来")
        assertEquals(ScreenMode.Smooth, rate.mode)
    }

    @Test
    fun threeConsecutiveSlowFramesDropOneStep() {
        assertEquals(5, degradedTo5().fps, "连续三拍慢应当降一档（10 → 5）")
    }

    @Test
    fun oneFastFrameInBetweenResetsTheSlowRun() {
        // 慢、慢、快、慢、慢 ⇒ 还不到三连，档位不动。GC 抖动不该降画质。
        val rate = ScreenRate()
        rate.observe(slowAt10, 1_000)
        rate.observe(slowAt10, 1_001)
        rate.observe(10, 1_002)
        rate.observe(slowAt10, 1_003)
        rate.observe(slowAt10, 1_004)
        assertEquals(10, rate.fps)
    }

    @Test
    fun degradationFloorsAtThreeFramesPerSecond() {
        val rate = degradedTo5()
        repeat(3) { rate.observe(slowAt5, 2_000L + it) }
        assertEquals(3, rate.fps, "第二档降完就是地板")
        // 再慢也不再往下掉：3fps 是产品地板，不是"还能更低"。
        repeat(12) { rate.observe(slowAt3, 3_000L + it) }
        assertEquals(3, rate.fps)
    }

    @Test
    fun recoveryNeedsBothTenSecondsAndASustainedFastWindow() {
        val rate = degradedTo5() // 最后一次降档发生在 now = 1002
        // 降档后立刻开始"每拍都很快"（40ms < 5fps 的 96ms 升档阈值）。
        // 第 1 秒起就开始攒稳定期，但要到「距降档 ≥10s」且「稳定 ≥10s」两个条件同时成立才升。
        rate.observe(40, 1_100)
        assertEquals(5, rate.fps, "刚过 100ms 就想升档 —— 抖动式升降比低帧率更难受")
        rate.observe(40, 11_001) // 距降档 9999ms，仍差 1ms
        assertEquals(5, rate.fps, "距上次降档不到 10 秒不许升")
        rate.observe(40, 11_003) // 距降档 10001ms；稳定期从 1100 起算已 9903ms
        assertEquals(5, rate.fps, "稳定窗口还不满 10 秒不许升")
        rate.observe(40, 11_200)
        assertEquals(10, rate.fps, "两个 10 秒都满足了应当升回 10fps")
    }

    @Test
    fun aSlowFrameDuringTheRecoveryWindowRestartsTheWait() {
        val rate = degradedTo5()
        rate.observe(40, 1_100) // 开始攒稳定期
        rate.observe(slowAt5, 6_000) // 中间慢了一拍 ⇒ 稳定期清零（并重新计入慢拍）
        rate.observe(40, 11_100)
        assertEquals(5, rate.fps, "慢拍把稳定期清零之后，剩下的窗口不够 10 秒")
        // 从 11_100 起重新攒满 10 秒后升档。
        rate.observe(40, 21_200)
        assertEquals(10, rate.fps, "稳定期攒满之后应当升回 10fps")
    }

    @Test
    fun setModeResetsTheAdaptationHistory() {
        // 用户手动改过档位之后，之前的"慢拍/稳定"账不能再用来决定下一次升降。
        val rate = degradedTo5()
        rate.setMode(ScreenMode.Auto)
        assertEquals(10, rate.fps, "手动切回 Auto 从最高档开始")
        // 若历史没清（slow 还是 0、lastDown 还是旧的），再拍几拍就会立刻降档。
        rate.observe(40, 100_000)
        assertEquals(10, rate.fps)
    }

    @Test
    fun theReceiverReportsTheMinimizedFloorAndFollowsTheRateElsewhere() = runBlocking {
        // [ScreenReceiver.targetFps] 是 Sharer 那一侧真正读到的数：
        // 最小化时必须压到地板 3，否则"窗口收起来还在按 10fps 推全屏"。
        val reported = ArrayList<Int>()
        val receiver = ScreenReceiver(
            attempts = emptyList(),
            selfId = "self",
            sessionId = "sess",
            token = "tok",
            display = { _, _, _, _ -> },
            onReady = {},
            onTarget = { reported += it },
            onEnd = {},
            scope = this,
        )
        assertEquals(10, receiver.targetFps)

        receiver.setMinimized(true)
        assertEquals(3, receiver.targetFps, "最小化必须压到地板")
        assertEquals(3, reported.last(), "帧率变化要通知出去，否则发送侧还按 10fps 推")

        // 最小化期间用户切档：仍然压在地板。
        receiver.setMode(ScreenMode.Economy)
        assertEquals(3, receiver.targetFps)

        // 恢复窗口后跟回自己的档位（Economy = 3）。
        receiver.setMinimized(false)
        assertEquals(3, receiver.targetFps)
        receiver.setMode(ScreenMode.Smooth)
        assertEquals(10, receiver.targetFps)
        assertEquals(10, reported.last())
    }
}
