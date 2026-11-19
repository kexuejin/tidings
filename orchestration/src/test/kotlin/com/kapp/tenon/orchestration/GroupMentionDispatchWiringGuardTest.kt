// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D18 的**贯通守卫**：群 `@` 工单句必须真的落到工单，而不是被认知分诊吞成只读讨论。
 *
 * 三处抽掉任何一处，编译照样绿、真机照样「点了没反应」：
 * 1. 文本路由入口接住路由点带来的 `mentionedAgentKey`；
 * 2. 分诊豁免用这个事实，不许再从剥净 `@` 的 prompt 里重判一遍；
 * 3. 单棒兜底把点名键归位成在位驱动键，不许恒落 codex。
 */
class GroupMentionDispatchWiringGuardTest {

    private fun orchestratorSource(): String = GuardSourceScan.stripComments(
        GuardSourceScan.read(
            "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt",
        ),
    )

    private fun extensionSource(): String = GuardSourceScan.stripComments(
        GuardSourceScan.read(
            "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopExtension.kt",
        ),
    )

    private fun handlePromptBody(): String = GuardSourceScan.bracedBlockAfter(
        orchestratorSource(), "suspend fun handlePrompt(",
    )

    @Test
    fun theMentionFactIsWiredFromTheRoutingPoint() {
        assertTrue(
            orchestratorSource().contains("mentionedAgentKey: String? = null"),
            "handlePrompt 必须接住路由点带来的点名事实（参数列表不在花括号体里，读整份源码）",
        )
        assertTrue(
            extensionSource().contains("mentionedAgentKey = mentionedAgentKey"),
            "装配层必须把 onAgentPrompt 的第五参原样传给 handlePrompt",
        )
    }

    @Test
    fun triageUsesTheFactInsteadOfReReadingTheStrippedPrompt() {
        val body = handlePromptBody()
        assertTrue(
            body.contains("val explicitMention = groupId != null && mentionedAgentKey != null"),
            "分诊豁免必须判随帧带来的点名事实",
        )
        assertFalse(
            body.contains("isMentioningAgent(prompt"),
            "prompt 已剥净 @：在这里重判点名恒为 false（D18 的死分支不许复活）",
        )
        assertTrue(
            body.contains("CognitiveFlow.classify(prompt) == CognitiveFlow.Intent.Discuss"),
            "没点名的文本仍要照旧分诊（只读讨论的默认档不许被移除）",
        )
    }

    @Test
    fun singleWorkerFallbackHonoursTheAddressedDriver() {
        // A5 之后驱动解析收口进 dispatchSingleWorker（执行档 > 员工档案 > 键归一 > 拒绝）：
        // handlePrompt 必须**点名原文原样下传**——先 resolveKey 再丢 null 的旧写法会把
        // 「档案配了底座」的席位打回默认驱动。
        val body = handlePromptBody()
        val fallback = body.lineSequence().firstOrNull { it.contains("dispatchSingleWorker(") }
        assertTrue(
            fallback != null && fallback.contains("mentionedAgentKey") && fallback.contains("wsId"),
            "群单棒兜底必须把点名原文与 wsId 一起向下传（解析在 dispatchSingleWorker 收口）（实际：$fallback）",
        )
        assertFalse(
            fallback!!.contains("resolveKey"),
            "handlePrompt 不许再自行 resolveKey（双处解析必然漂移，A5 收口的反面）",
        )
    }
}
