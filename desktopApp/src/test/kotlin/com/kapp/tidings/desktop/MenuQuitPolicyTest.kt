// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「退出项只能有一个」的两条判据（task-56）。
 *
 * 真机证据（2026-10-18 23:17，Lead 用 System Events 读 Mac 菜单栏）：
 * 菜单栏四格 = Apple | Tidings（AWT 自带应用菜单，含 Quit 捎话 Tidings）
 * | 捎话 Tidings（我们的 Compose MenuBar 应用菜单，含 退出）| 文件 ⇒ **两个退出项**。
 *
 * AWT 那份是 macOS 应用菜单的强制部分、删不掉 ⇒ 我们这条在 macOS 上不画；
 * 但它的行为必须与我们的退出路径一致（JDK 默认策略会绕过 shutdown() 直接结束 JVM），
 * 所以要装 AWT 的退出处理器 —— 装不装得成取决于平台是否支持。
 */
class MenuQuitPolicyTest {

    @Test
    fun macosLeavesTheQuitItemToTheAwtApplicationMenu() {
        assertFalse(
            showsOwnQuitItem(isMacos = true),
            "macOS 上再画一条就是真机那两个退出项（AWT 应用菜单那份删不掉）",
        )
    }

    @Test
    fun windowsAndLinuxKeepOurQuitItem() {
        assertTrue(
            showsOwnQuitItem(isMacos = false),
            "Windows/Linux 没有 AWT 应用菜单：删了就没有退出入口",
        )
    }

    @Test
    fun theAwtQuitHandlerIsInstalledOnlyWhereItIsSupported() {
        assertTrue(shouldInstallAwtQuitHandler(desktopSupported = true, quitHandlerSupported = true))
        assertFalse(
            shouldInstallAwtQuitHandler(desktopSupported = true, quitHandlerSupported = false),
            "平台不支持 APP_QUIT_HANDLER 时调用会抛 UnsupportedOperationException（Windows/Linux）",
        )
        assertFalse(
            shouldInstallAwtQuitHandler(desktopSupported = false, quitHandlerSupported = true),
            "桌面 API 不可用（headless/精简 JRE）时不能碰它",
        )
    }
}