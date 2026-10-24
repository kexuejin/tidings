// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.desktop.testutil.RepoSource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「再点一次」必须有反馈。
 *
 * ## 为什么这条值得守
 *
 * 主窗是懒建的（关闭 = 销毁），所以**窗口没了是正常状态**。于是程序坞单击、
 * Finder 里双击已运行的 .app、`open -a` 不带参数 —— 这些动作如果只 activate 进程
 * 而不把窗口建出来，用户看到的就是"点了没反应"。
 *
 * 实测过缺这一环的形态：`open -a /Applications/Tidings.app` 前后 footprint
 * 278.4M → 279.2M、IOSurface 仍为 0，也就是什么都没发生。补上
 * `AppReopenedListener` 之后同一条命令：326.9M/IOSurface 0 → 521.5M/IOSurface 1。
 *
 * ## 这条守卫能区分对错
 *
 * 把 `addAppEventListener` 那一句删掉，本测试立刻红。它是源码级断言而不是运行时断言：
 * 触发 AppReopened 需要真的点一次程序坞图标，没有可靠的方式在单测里合成。
 */
class ReopenRaisesMainWindowTest {

    private val source: String by lazy {
        RepoSource.read("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/Main.kt")
    }

    @Test
    fun `注册了 AppReopened 回调并请求唤回主窗`() {
        assertTrue(
            source.contains("addAppEventListener"),
            "必须注册系统「重新打开」回调；没有它，程序坞单击 / open -a 不带参数都会是「点了没反应」",
        )
        assertTrue(
            source.contains("AppReopenedListener"),
            "注册的必须是 AppReopenedListener（不是别的监听器）",
        )
        // 回调里必须真的**请求建窗**，而不是只打印一行。
        val at = source.indexOf("AppReopenedListener")
        val tail = source.substring(at, minOf(source.length, at + 220))
        assertTrue(
            tail.contains("requestShowMain"),
            "回调必须调用 requestShowMain()：主窗是懒建的，只 activate 进程不会让它出现",
        )
    }
}
