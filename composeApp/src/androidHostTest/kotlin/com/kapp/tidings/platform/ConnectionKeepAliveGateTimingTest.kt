// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 保活前台服务的「时机判定」纯函数（Android 12+ 后台启动限制）。
 *
 * 真机现场：锁屏下被拉起（am start / 自启）时，节点启动路径上的保活请求被系统拒
 * （getFgsAllowStart=DENIED、uidState=TPSL）—— 异常被兜住进程不崩了，但保活服务
 * 在那一次启动里缺席，常驻通知消失。判「现在能不能起」的规则抽成 [ConnectionKeepAliveGate.canStartForegroundServiceNow]，
 * 三个输入全部注入：这份测试不碰 Context、不读真实系统状态，任何机器上同一结论。
 */
class ConnectionKeepAliveGateTimingTest {

    @Test
    fun beforeTwelveAlwaysAllows() {
        // 后台启动限制是 12 才引入的；老系统无论什么状态都放行。
        for (sdk in intArrayOf(24, 26, 30)) {
            assertTrue(
                ConnectionKeepAliveGate.canStartForegroundServiceNow(
                    sdkInt = sdk,
                    hasSystemAlertWindow = false,
                    processForeground = false,
                ),
                "API $sdk 没有后台启动限制，无任何豁免也应放行",
            )
            assertTrue(
                ConnectionKeepAliveGate.canStartForegroundServiceNow(
                    sdkInt = sdk,
                    hasSystemAlertWindow = true,
                    processForeground = true,
                ),
            )
        }
    }

    @Test
    fun twelvePlusWithoutExemptionIsDenied() {
        // 这正是锁屏拉起被拒的现场：进程虽在栈顶，屏是灭的，系统不认它在前台。
        for (sdk in intArrayOf(31, 33, 35)) {
            assertFalse(
                ConnectionKeepAliveGate.canStartForegroundServiceNow(
                    sdkInt = sdk,
                    hasSystemAlertWindow = false,
                    processForeground = false,
                ),
                "API $sdk 从后台起 FGS 应判为不许",
            )
        }
    }

    @Test
    fun twelvePlusAllowsWhenProcessIsForeground() {
        // 豁免名单里的「应用恰好在前台」：正常解锁进应用的路径必须放行，
        // 否则 promote 在前台重发也会被误判成不许起。
        assertTrue(
            ConnectionKeepAliveGate.canStartForegroundServiceNow(
                sdkInt = 31,
                hasSystemAlertWindow = false,
                processForeground = true,
            ),
        )
        assertTrue(
            ConnectionKeepAliveGate.canStartForegroundServiceNow(
                sdkInt = 35,
                hasSystemAlertWindow = false,
                processForeground = true,
            ),
        )
    }

    @Test
    fun twelvePlusAllowsWithSystemAlertWindow() {
        // 豁免名单里的 SYSTEM_ALERT_WINDOW：那是用户显式给过的能力，任何时候都行。
        assertTrue(
            ConnectionKeepAliveGate.canStartForegroundServiceNow(
                sdkInt = 31,
                hasSystemAlertWindow = true,
                processForeground = false,
            ),
        )
        assertTrue(
            ConnectionKeepAliveGate.canStartForegroundServiceNow(
                sdkInt = 35,
                hasSystemAlertWindow = true,
                processForeground = false,
            ),
        )
    }
}
