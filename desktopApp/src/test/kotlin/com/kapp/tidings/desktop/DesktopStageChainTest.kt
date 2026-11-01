// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 一条源码守卫：**执行体写好了却没人装**。
 *
 * 这条不是形式主义。上一轮「合并此补丁」就是这么坏的 —— 判据在、分支在、接线不在，
 * 于是真机上点了没反应，而单测全绿（那个守卫只锚了 `latestPatchRefFor(`）。
 * 链执行体的等价失效形态是：`/v1/agent/chain` 一直回 503，或者跑了但卡片发不进群。
 */
class DesktopStageChainTest {

    @Test
    fun theShellInstallsTheChainDispatcherOnTheSamePlaneInstance() {
        val source = listOf(
            File("src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"),
            File("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "找不到 DesktopExtension.kt（当前目录 " + File(".").absolutePath + "）")
        val text = source.readText()

        assertTrue(
            text.contains("created.chainDispatcher = DesktopStageChain.dispatcher("),
            "链执行体没有装到 ControlPlane 上 —— /v1/agent/chain 会永远回 503",
        )
        val assignAt = text.indexOf("createdPlane = created")
        val wireAt = text.indexOf("created.chainDispatcher = ")
        assertTrue(assignAt in 0 until wireAt, "装配必须在 createdPlane 赋值之后（执行体要用同一个 ControlPlane 实例）")
    }

    @Test
    fun theCardGoesOutThroughTheExistingSingleSlotBroadcast() {
        val source = listOf(
            File("src/main/kotlin/com/kapp/tidings/desktop/DesktopStageChain.kt"),
            File("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopStageChain.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "找不到 DesktopStageChain.kt")
        val text = source.readText()
        assertTrue(
            text.contains("controller.broadcastChainCard("),
            "链卡必须走 RelayController 的既有广播（T-2d 原位替换只实现过一份，另写一份就会分叉）",
        )
    }

    /**
     * 越界写守卫：沙箱零改动、宿主仓却脏了 ⇒ 该棒判失败并停机。
     *
     * 真机 cmvx8q5ig 第一棒：codex 从 worktree 的 `.git` 里读到主干仓路径，用绝对路径
     * 直接改了宿主工作树。收割如实为空，链照常往下走，验收棒于是验了一份**没修的代码**
     * —— 表面「全绿」，实际开发棒的成果停在未提交的工作树里，谁也没拿到。
     */
    @Test
    fun anEscapedWriteStopsTheChainInsteadOfHandingAStaleBaseDownstream() {
        val source = listOf(
            File("src/main/kotlin/com/kapp/tidings/desktop/DesktopStageChain.kt"),
            File("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopStageChain.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "找不到 DesktopStageChain.kt")
        val text = source.readText()

        assertTrue(text.contains("escapedWrite"), "缺少越界写判据")
        assertTrue(
            text.contains("\"status\", \"--porcelain\""),
            "越界写必须靠宿主仓的 porcelain 状态判 —— 只看沙箱 harvest 永远看不见它",
        )
        assertTrue(
            text.contains("!didNothing && !escapedWrite"),
            "越界写必须让这一棒判失败（否则链条继续把旧基线交给下一棒）",
        )
    }
}
