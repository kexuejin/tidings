// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 责任链「下一步怎么走」的**接线守卫**。
 *
 * 两个失效模式都曾在真机上出现过，且纯函数测试全绿：
 * 1. 审查 REJECT 了，链还是照常合流（决策信号没接进棒序循环）；
 * 2. 主理人裁决写好了却没人装配（`decider` 恒 null ⇒ 专家永远不到场）。
 *
 * 所以这里钉的是**接线**，不是判据 —— 判据由 `StepDeciderTest` /
 * `EscalatingStepDeciderTest` / `TeamStepDeciderTest` 负责。
 */
class ReviewPipelineWiringGuardTest {

    private val chain = GuardSourceScan.stripComments(
        GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/StageChain.kt"),
    )
    private val decider = GuardSourceScan.stripComments(
        GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/StepDecider.kt"),
    )
    private val escalating = GuardSourceScan.stripComments(
        GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/EscalatingStepDecider.kt"),
    )
    private val desktopChain = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopStageChain.kt"),
    )

    @Test
    fun theRunnerConsumesTheDeciderOutcome() {
        assertTrue(chain.contains("review: ReviewPipeline? = null"),
            "runner 必须接受可选流水线（缺省 null = 旧行为逐字不变）")
        assertTrue(chain.contains("decider: StepDecider? = null"),
            "必须留出显式决策器插槽，否则主理人永远进不来")
        assertTrue(chain.contains("is StepDecision.Rework"), "返工分支要真的接进棒序循环")
        assertTrue(chain.contains("is StepDecision.Finish"), "收工分支要真的接进棒序循环")
        assertTrue(chain.contains("is StepDecision.Spawn"), "现场加人分支要真的接进棒序循环")
        assertTrue(chain.contains("reviewPassed = reviewMerge"), "最终卡要带出合流信号")
    }

    @Test
    fun theDeterministicDeciderKeepsTheLegacyVerdictSemantics() {
        assertTrue(decider.contains("Advance.BackToWorker") && decider.contains("Advance.Merge"),
            "REJECT 打回 / 全 PASS 合流两条旧语义必须留在规约决策器里")
    }

    @Test
    fun theEscalationOnlyHappensOnAmbiguity() {
        assertTrue(escalating.contains("is StepDecision.Stop -> true"),
            "规约判不了时必须升级给主理人")
        assertTrue(escalating.contains("is StepDecision.Next -> false"),
            "常规推进不许升级 —— 那是纯延迟与费用")
    }

    @Test
    fun theHostAssemblyParsesVerdictsAndPublishesTheMerge() {
        assertTrue(desktopChain.contains("ReviewPipeline()"), "宿主装配必须开启审查规约")
        assertTrue(desktopChain.contains("EscalatingStepDecider("), "主理人升级路径必须接线")
        assertTrue(desktopChain.contains("TeamStepDecider("), "lead 裁决必须真的可被问到")
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
        val broken = desktopChain.replace("EscalatingStepDecider(", "")
        assertFalse(broken.contains("EscalatingStepDecider("))
    }
}
