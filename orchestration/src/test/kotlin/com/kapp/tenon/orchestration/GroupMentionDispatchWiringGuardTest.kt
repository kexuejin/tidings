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
        val body = handlePromptBody()
        assertTrue(
            body.contains("AgentProfiles.resolveKey(it, availableAgentKeys().orEmpty())"),
            "席位全名要归位成在位档案键，否则点名 dsh 的单会派给默认驱动",
        )
        val fallback = body.lineSequence().firstOrNull { it.contains("dispatchSingleWorker(") }
        assertTrue(
            fallback != null && fallback.contains("mentionedDriver") && fallback.contains("wsId"),
            "群单棒兜底必须把解析出的驱动与 wsId 一起向下传（实际：$fallback）",
        )
    }
}
