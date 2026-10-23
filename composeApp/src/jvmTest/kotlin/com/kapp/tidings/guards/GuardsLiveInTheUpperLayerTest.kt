// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「跨层守卫住在上层，下层不许反向读上层源码」。
 *
 * ## 这条守的是什么
 *
 * `:composeApp` 依赖 `:app`，所以能同时读两边的只有 `:composeApp`。
 * 但测试代码**没有依赖方向**（就是读文件），于是写在 `:app` 里的测试照样能读到
 * `composeApp/src/.../ui/Feature.kt` —— 只要它拼对相对路径。
 *
 * 这就是本次拆模块后留下的实际状态：`:app` 里有 9 个测试在读 `:composeApp` 的
 * 源码，其中一个连 `iosApp/iOSApp.swift` 都读。它靠 `File("..")` 侥幸成立 ——
 * 换任务、换工作目录、再搬一次模块，症状是 `error("读不到 …")`，看起来像文件被删了。
 *
 * 这些测试已搬到 `com.kapp.tidings.guards` 包（在 `:composeApp` 里）。这条守卫防止它们
 * 长回 `:app`。
 *
 * ## 判据
 *
 * `:app` 的测试源码里不许出现 `composeApp/` 开头的路径字面量。
 * 它只扫 `:app` 的 `*Test.kt`（`:composeApp` 的测试读两边是**允许**且必要的）。
 */
class GuardsLiveInTheUpperLayerTest {

    @Test
    fun `app 模块的测试不许反向读 UI 侧源码`() {
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (root in listOf("app/src/jvmTest", "app/src/commonTest")) {
            val dir = GuardSourceScan.resolveDir(root)
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .forEach { file ->
                    scanned++
                    val rel = file.relativeTo(GuardSourceScan.scaffoldRoot()).path.replace('\\', '/')
                    // 只看活代码里的路径字面量：注释里解释「这条为什么在上层」是合法的。
                    val live = GuardSourceScan.stripComments(file.readText())
                    if (live.contains("\"composeApp/")) offenders += rel
                }
        }
        assertTrue(scanned > 50, "只扫到 " + scanned + " 个 :app 测试文件，明显不对")
        assertTrue(
            offenders.isEmpty(),
            "这些 :app 的测试在反向读 UI 侧源码：" + offenders +
                " —— :composeApp 依赖 :app，所以能同时读两边的只有 :composeApp。" +
                "把它们搬到 com.kapp.tidings.guards（:composeApp 的 jvmTest）去。" +
                "在 :app 里读 UI 源码只能靠相对路径侥幸成立，换个工作目录就会静默失效。",
        )
    }
}
