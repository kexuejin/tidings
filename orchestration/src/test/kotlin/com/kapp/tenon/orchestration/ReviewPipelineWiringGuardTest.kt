// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2 规格 3 的**接线守卫**：状态机只有被 runner 和宿主装配真的用上才算落地。
 * 摘掉任何一环，纯函数测试照样全绿 —— 真机形状是「审查棒 REJECT 了，链还是照常合流」。
 */
class ReviewPipelineWiringGuardTest {

    private val chain = GuardSourceScan.stripComments(
        GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/StageChain.kt"),
    )
    private val desktopChain = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopStageChain.kt"),
    )

    @Test
    fun theRunnerConsumesThePipelineAdvanceSignal() {
        assertTrue(chain.contains("private val review: ReviewPipeline? = null"),
            "runner 必须接受可选流水线（缺省 null = 旧行为逐字不变）")
        assertTrue(chain.contains("BackToWorker") && chain.contains("Advance.Merge"),
            "REJECT 打回与全 PASS 合流两条分支都要真的接进棒序循环")
        assertTrue(chain.contains("reviewPassed = reviewMerge"), "最终卡要带出合流信号")
    }

    @Test
    fun theHostAssemblyParsesVerdictsAndPublishesTheMerge() {
        assertTrue(desktopChain.contains("review = ReviewPipeline("), "宿主装配必须开启审查流水线")
        assertTrue(desktopChain.contains("ReviewVerdict.parseOf("), "驱动输出要解析出 PASS/REJECT，不能恒 null")
        assertTrue(desktopChain.contains("progress.reviewPassed"), "全棒 PASS 的合流信号要读出来")
        // 审查通过 ≠ 已合入：合流必须由机主的 merge-patch 真正执行 applyBranch。
        // 从前这里自行 publish("merged")，卡面翻「已合并」而项目目录一行未动（假绿）。
        assertFalse(desktopChain.contains("publish(\"merged\""),
            "审查通过不得自行翻 merged —— 合流只能由 merge-patch 落盘后翻面")
    }

    /** 守卫自验证：内存摘线后断言要变红，否则只是字符串摆设。 */
    @Test
    fun removingTheAssemblyMakesTheGuardFail() {
        val broken = desktopChain.replace("review = ReviewPipeline(", "")
        assertFalse(broken.contains("review = ReviewPipeline("))
    }
}
