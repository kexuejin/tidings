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
        assertTrue(desktopChain.contains("progress.reviewPassed"), "全棒 PASS 的合流信号要触发既有 publish(merged)")
        assertTrue(desktopChain.contains("publish(\"merged\""), "合流必须复用既有单槽广播，不另写一条发送")
    }

    /** 守卫自验证：内存摘线后断言要变红，否则只是字符串摆设。 */
    @Test
    fun removingTheAssemblyMakesTheGuardFail() {
        val broken = desktopChain.replace("review = ReviewPipeline(", "")
        assertFalse(broken.contains("review = ReviewPipeline("))
    }
}
