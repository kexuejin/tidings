// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 演化规范 §6.4/§6.5 两条新行为的**接线守卫**。
 *
 * 被钉的行为都是 [DesktopWorkerOrchestrator] 的私有方法，行为级测试够不着；
 * 这里按 WiringGuard 族惯例钉源码形状 —— 摘掉任何一环必须变红：
 *
 * 1. **执行中纠偏 = 中止重派**：`working` 阶段的用户正文不许被静默吞掉 ——
 *    走 [DesktopWorkerOrchestrator.steerActiveTicket]（结束当前子进程 + 带原任务与新指令重派），
 *    文本路由与 invoke 桥两条路径都要接上；
 * 2. **中止优先于纠偏**：isAbortSignal 命中时不许被当成纠偏吞掉 —— 两处判据缺一不可；
 * 3. **卡片 Q&A 双端防重**：解析不出补丁归属且不是本机活跃工单时不许抢答；
 * 4. **Q&A 如实 + 宿主护栏**：补丁丢了要如实说产物不可读，不许编造 diff；只读不只靠
 *    prompt —— 工具白名单（CognitiveFlow.allowsTool）要在执行前拦、事后判负。
 */
class DesktopWorkerOrchestratorInFlightGuardTest {

    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
    )

    // ---------- 执行中纠偏（§6.5：中止重派，不是追加约束） ----------

    @Test
    fun workingStageIsPartOfTheActiveFollowUpStages() {
        assertTrue(
            orchestrator.contains("""it.stage in setOf("blocked", "prototype", "proposed", "working")"""),
            "followUpIfActive 的活跃阶段集合必须包含 working —— 执行中来的不是新派单",
        )
    }

    @Test
    fun workingStageSteeringGoesThroughSteerActiveTicketInBothRoutes() {
        // 文本路由（followUpIfActive 内）与 invoke 桥各一处调用；第三处是定义本身
        val callCount = Regex("""steerActiveTicket\(active, \w+\)""").findAll(orchestrator).count()
        assertEquals(3, callCount, "steerActiveTicket 应有 1 处定义 + 2 处路由调用（文本/invoke）")

        assertTrue(
            orchestrator.contains("""TicketOutcome("working", "Steering accepted: previous task aborted; redispatching with new instruction", null, null)"""),
            "invoke 桥的纠偏必须返回可等待的 working 终局，而不是继续往下走新派单",
        )
    }

    @Test
    fun steeringStopsTheRunningTaskBeforeRedispatching() {
        val body = orchestrator.substringAfter("private fun steerActiveTicket(")
            .substringBefore("private fun startProposalByCard(")
        assertTrue(body.contains("""if (active.stage != "working") return false"""), "只有执行中的工单可被纠偏")
        assertTrue(
            body.contains("""abortByCard("chain-" + active.ticketId, "Steered by user request")"""),
            "纠偏必须先结束当前子进程（abortByCard），不是并行再开一单",
        )
        assertTrue(
            body.contains("User steering instruction (the previous run was stopped and is being restarted):"),
            "重派提示词必须说清上一轮被停过 —— 原任务 + 新指令一起带",
        )
        assertTrue(body.contains("dispatchSingleWorker("), "纠偏后要真的重派（dispatchSingleWorker）")
        assertFalse(body.contains("deleteRecursively"), "纠偏不许清理现场")
    }

    @Test
    fun abortSignalOutranksInFlightSteering() {
        val abortCount = Regex(
            """active\.stage == "working" && !isAbortSignal\(prompt\)""",
        ).findAll(orchestrator).count()
        assertEquals(2, abortCount, "两条路由的工作中纠偏都必须先判中止信号 —— 中止不许被纠偏吞掉")
    }

    // ---------- 卡片 Q&A（§6.4） ----------

    @Test
    fun cardQaDoesNotAnswerTicketsThatAreNotOurs() {
        assertTrue(
            orchestrator.contains(
                """if (patchRef == null && !activeTickets.containsKey(cardId.removePrefix("chain-")))""",
            ),
            "双端防重：解析不出补丁归属且不是本机活跃工单时必须直接返回，不许抢答",
        )
    }

    @Test
    fun cardQaResolvesMessageIdToCardIdFromTheQuotedMessage() {
        val qaBody = orchestrator.substringAfter("private suspend fun handleCardFollowUpQa(")
            .substringBefore("private suspend fun handleCardFollowUpQaAwait(")
        assertTrue(qaBody.contains("quoted?.cardPayload ?: quoted?.card"), "Q&A 必须先解出被引用消息的卡片 JSON")
        assertTrue(qaBody.contains("?.get(\"cardId\")"), "replyTo 是消息 id，不是 cardId；必须从 cardPayload 取 cardId")
        assertFalse(qaBody.contains("val cardId = replyToId"), "不许再把消息 id 当卡片 id 用")
    }

    @Test
    fun cardQaIsHonestWhenThePatchFileIsGone() {
        assertTrue(
            orchestrator.contains("without inventing fake code diffs"),
            "补丁丢失时提示词必须要求如实说产物不可读，不许编造 diff",
        )
    }

    @Test
    fun cardQaIsEnforcedByHostSideToolPolicyNotJustPrompt() {
        val qaBody = orchestrator.substringAfter("private suspend fun handleCardFollowUpQa(")
            .substringBefore("private suspend fun handleCardFollowUpQaAwait(")
        assertTrue(
            qaBody.contains("CognitiveFlow.readOnlyPrompt(augmentedPrompt)"),
            "Q&A 会话必须走只读包裹（CognitiveFlow.readOnlyPrompt）",
        )
        assertTrue(
            qaBody.contains("""toolPolicy = { tool -> CognitiveFlow.allowsTool(tool) }"""),
            "只读问答必须挂宿主侧工具白名单 —— prompt 只是请求，白名单才是「不许写」",
        )
        assertTrue(
            qaBody.contains("Read-only Q&A aborted: attempted to use a write tool"),
            "工具越轨必须中断并如实回帖，不许静默继续",
        )
        assertTrue(
            qaBody.contains("qaWorkdir(wsId, patchRef, groupId) ?: return false"),
            "工作区解析不出来必须 fail-closed（不问答），不是猜一个目录",
        )
        assertFalse(
            qaBody.contains("createTicket") || qaBody.contains("dispatchTicket") ||
                qaBody.contains("startTicket"),
            "Q&A 分支不许开出新工单",
        )
    }

    /** 守卫自验证：内存摘线后断言要变红（防「永远为真」的守卫）。 */
    @Test
    fun removingTheGuardedWiringMakesTheseAssertionsFail() {
        assertEquals(
            0,
            Regex("""active\.stage == "working" && !isAbortSignal\(prompt\)""")
                .findAll(orchestrator.replace("!isAbortSignal(prompt)", "")).count(),
            "摘掉中止优先判据后计数必须归零",
        )
        assertEquals(
            0,
            Regex("""steerActiveTicket\(active, \w+\)""")
                .findAll(orchestrator.replace("steerActiveTicket(active, trimmed)", "")
                    .replace("steerActiveTicket(active, trimmedPrompt)", "")).count(),
            "摘掉两条纠偏调用后计数必须归零（只剩定义，不含调用形状）",
        )
    }
}
