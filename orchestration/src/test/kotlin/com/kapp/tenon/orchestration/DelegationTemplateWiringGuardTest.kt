// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §16.5 模板接线的**源码级守卫**（§13.3 第一重证据）。
 *
 * 为什么源码层：跑模板短路不需要网络，但要驱动编排器的完整派单面（控制面 / 沙箱 /
 * 卡片广播）—— 在测试里搭整套代价远超收益；而「接线在不在」恰恰是源码文本能判定的
 * （与 [OrchestratorPhase4WiringTest] 同一取舍）。守卫必须能区分对错：
 * 摘掉 fromTemplate 短路 / 删掉 teamStore 装配 / 旁路直调解析器，这里都要红。
 */
class DelegationTemplateWiringGuardTest {

    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
    )

    /** 判据问的是「装配层有没有这条接线」—— 读整包，不跟文件搬家。 */
    private val extension = GuardSourceScan.livePackageOf("orchestration/src/main/kotlin/com/kapp/tenon/orchestration")

    /** §15 第 9 条：命中模板先过团队 LLM 重排（失败回落编译链），仍不落进通用 PlannerLlm。 */
    @Test
    fun templatePlansGoThroughTheTeamRefinerBeforeTheGenericPlanner() {
        val refineBranch = orchestrator.indexOf("if (plan.fromTemplate) return teamPlanRefiner?.invoke(plan) ?: plan")
        val planner = orchestrator.indexOf("val planner = autoPlanner")
        assertTrue(
            refineBranch >= 0,
            "refinedPlan 的 fromTemplate 分支必须先问 teamPlanRefiner、?: 回落编译固定链 —— " +
                "摘掉 refine 调用，@团队名 永远是名册顺序的固定链（leader 动态编排不存在）",
        )
        assertTrue(
            refineBranch < planner,
            "fromTemplate 分支必须在 autoPlanner 之前 —— 模板计划哪怕重排失败也回落编译链，" +
                "不该再白付一次通用 planner 调用",
        )
    }

    /** §16.4：两条派单路（文本路由 / invoke 桥）都必须走单源 parseDelegation。 */
    @Test
    fun bothDispatchPathsGoThroughTheSingleSource() {
        assertEquals(
            2,
            Regex(Regex.escape("parseDelegation(prompt, available, groupId)")).findAll(orchestrator).count(),
            "handlePrompt 与 handlePromptAwait 必须各调一次 parseDelegation：少一处 = 那条路没有模板能力",
        )
    }

    /** 装配：store 随 attach 建、detach 清；对话配置桥随装配装上、随 detach 撤。 */
    @Test
    fun theStoreAndTheConfigBridgeAreAssembledAndCleared() {
        assertTrue(
            extension.contains("DelegationTeamStore(java.io.File(controller.dataDirectory, \"delegation-teams.json\"))"),
            "模板存储必须落在 dataDirectory（§16.3）：硬编码 home dot-dir 破坏零 UI 模块的可移植性",
        )
        assertTrue(
            extension.contains("teamStore = delegationTeams"),
            "编排器必须拿到 teamStore：不装配 = 模板在真机上不存在（单测假绿、现场没有）",
        )
        assertTrue(
            extension.contains("controller.onDelegationTeamConfig = { convId, text ->"),
            "入口 A 的拦截桥必须装上：不装 = 对话配置在真机上不存在",
        )
        assertTrue(
            extension.contains("delegationTeamStore = null") && extension.contains("controller.onDelegationTeamConfig = null"),
            "detach 必须清掉模板存储与配置桥（与其它桥同纪律）",
        )
    }
}
