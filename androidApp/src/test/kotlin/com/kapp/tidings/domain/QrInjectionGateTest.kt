// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import android.content.pm.ApplicationInfo
import com.kapp.tidings.shouldRegisterTestQrReceiver
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 钉住 [shouldRegisterTestQrReceiver] 这条安全闸门（task-50 审查发现）。
 *
 * 修之前的样子：`ACTION_TEST_INJECT_QR` 这个**导出**广播**无条件注册**，处理函数把任意
 * 字符串交给 `RelayController.handleQrCodeAsync` —— 那条路会新增/改写手动节点、写候选池、
 * 落对端 TLS pin，且没有二次确认。所以设备上任意应用都能让本机与它控制的节点配对、
 * 并把它的 pin 固定成信任根。
 *
 * 这条测试钉的就是「发布版（不可调试）不许注册」：把闸门从 `if (true)` 改回无条件注册，
 * 它会立刻红。
 */
class QrInjectionGateTest {

    @Test
    fun aReleaseBuildNeverRegistersTheTestQrReceiver() {
        assertFalse(shouldRegisterTestQrReceiver(0), "不可调试的包不许注册测试注入口")
        assertFalse(
            shouldRegisterTestQrReceiver(ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_ALLOW_BACKUP),
            "带上别的 flag 也还是不可调试 ⇒ 仍然不许注册",
        )
    }

    @Test
    fun aDebuggableBuildKeepsTheAdbTestHook() {
        assertTrue(shouldRegisterTestQrReceiver(ApplicationInfo.FLAG_DEBUGGABLE), "debug 包要保留 adb 注入口")
        assertTrue(
            shouldRegisterTestQrReceiver(ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_ALLOW_BACKUP),
            "其它 flag 不该把这一位挤掉",
        )
    }
}
