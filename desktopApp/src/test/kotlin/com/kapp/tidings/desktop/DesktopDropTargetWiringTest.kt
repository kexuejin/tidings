// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「两扇窗都接了拖放」的源码级守卫。
 *
 * ## 为什么需要它
 *
 * 拖入行为在真机上报过一次「完全没反应」，而**当时整个 desktopApp 的测试目录里
 * 没有任何一条测试提到拖入** —— 只有拖出（`DesktopDragOutTest`）。也就是说这条链路
 * 从写下来那天起就没有守卫：搬窗口、改组合结构、把 `DesktopDropTarget` 挪进某个
 * 条件分支，都能让它静默失效，而编译、其余测试全绿。
 *
 * 拖放又不能靠单元测试验：它需要真实的 NSView 命中与 AWT DnD 会话。所以这里守的是
 * **接线**（组合结构），和 [LazyMainWindowGuardTest] 同一取舍。
 *
 * ## 判据能区分对错吗
 *
 * 1. 主窗与独立会话窗的文件里都必须出现 `DesktopDropTarget(` —— 删掉接线就红；
 * 2. 两处的 `enabled` 都必须是 `true` 字面量 —— 改成 `false` 或变量就红；
 * 3. `onFiles` 必须真的把文件交给 `handleDroppedFiles(` —— 只装个空壳（收下就丢）
 *    也会红，而那种写法在界面上同样表现为「拖了没反应」。
 */
class DesktopDropTargetWiringTest {

    private val root: File by lazy {
        var dir = File("").absoluteFile
        repeat(6) {
            val candidate = File(dir, "desktopApp/src/main/kotlin/com/kapp/tidings/desktop")
            if (candidate.isDirectory) return@lazy candidate
            dir = dir.parentFile ?: dir
        }
        error("找不到 desktopApp 源码目录")
    }

    private fun read(name: String): String = File(root, name).readText()

    @Test
    fun 主窗接了拖放且真的会发送() {
        val source = read("DesktopWindow.kt")
        assertTrue(
            source.contains("DesktopDropTarget("),
            "主窗没有接 DesktopDropTarget —— 拖文件进主窗会完全没反应",
        )
        assertTrue(
            source.contains("handleDroppedFiles("),
            "主窗接了拖放却没有把文件交给 handleDroppedFiles —— 收下就丢，同样是「拖了没反应」",
        )
    }

    @Test
    fun 独立会话窗接了拖放且发给自己的会话() {
        val source = read("DesktopPeerWindow.kt")
        assertTrue(
            source.contains("DesktopDropTarget("),
            "独立会话窗没有接 DesktopDropTarget —— 拖到那扇窗上会没反应",
        )
        assertTrue(
            source.contains("handleDroppedFiles("),
            "独立会话窗没有走共用的展开/降级逻辑",
        )
    }

    @Test
    fun 两处都必须显式启用() {
        for (name in listOf("DesktopWindow.kt", "DesktopPeerWindow.kt")) {
            val source = read(name)
            val at = source.indexOf("DesktopDropTarget(")
            assertTrue(at >= 0, "$name 没有接拖放")
            // 取调用点后一小段就够：`enabled` 是第一个参数。
            val call = source.substring(at, minOf(source.length, at + 200))
            assertTrue(
                call.contains("enabled = true"),
                "$name 的 DesktopDropTarget 没有显式 enabled = true —— 关掉它时界面毫无提示",
            )
        }
    }
}