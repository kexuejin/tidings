// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.desktop.testutil.RepoSource
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

    private fun read(name: String): String =
        RepoSource.read("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/" + name)

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

    @Test
    fun 主窗未选会话时把拖放交给目标选择器而不是只弹通知() {
        // 空态文案承诺「捎话会问你要发给谁」（r2_shell_nosel_hint），旧行为却是
        // 只弹一条「请先选会话」系统通知 —— 文案在撒谎。守卫钉住补上的行为。
        // 反向验证：把 DesktopWindow 的 onPickTarget 参数删掉，这条立刻红。
        assertTrue(
            read("DesktopDropFiles.kt").contains("onPickTarget: ((DroppedPayload) -> Unit)? = null"),
            "handleDroppedFiles 缺 onPickTarget 接缝：宿主没有「问发给谁」的入口",
        )
        val window = read("DesktopWindow.kt")
        assertTrue(
            window.contains("onPickTarget = { payload ->"),
            "主窗拖放没接 onPickTarget —— 未选会话时只会弹通知，不会问「发给谁」",
        )
        assertTrue(
            window.contains("host.pendingDropFiles?.let { payload ->") &&
                window.contains("ForwardTargetDialog(") &&
                window.contains("title = stringResource(Res.string.chat_drop_target_title)"),
            "主窗没有为待发拖放文件弹目标选择器或标题未设为发送文件到",
        )
    }
}