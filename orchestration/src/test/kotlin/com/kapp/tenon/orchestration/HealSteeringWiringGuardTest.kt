// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2 规格 4 的**接线守卫**：熔断卡的动作是数据驱动的，UI 复用既有输入抽屉。
 * 纯函数测试全绿不等于真机上能点 —— 少了任何一环，用户面对的仍是一张只能推倒重来的卡。
 */
class HealSteeringWiringGuardTest {

    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
    )
    private val actions = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/HostCardActions.kt"),
    )
    private val cards = GuardSourceScan.stripComments(
        GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/AgentCards.kt"),
    )
    private val chainStage = GuardSourceScan.stripComments(
        GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/StageChain.kt"),
    )
    private val desktopChain = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopStageChain.kt"),
    )

    @Test
    fun theExhaustedCardIsGivenTheSteeringActions() {
        assertTrue(orchestrator.contains("cardWithSteeringActions("), "熔断卡必须换上纠偏动作，不是旧的 Retry")
        assertTrue(orchestrator.contains("humanSteering = !ticket.steeringUsed"),
            "终验机会用过后不再给输入槽（第二次请求不许无限循环）")
        assertTrue(orchestrator.contains("humanSteering: Boolean = false"),
            "缺省 false：其它状态的卡片形状一字不变")
    }

    @Test
    fun theHostGrantsExactlyOneFinalRound() {
        assertTrue(orchestrator.contains("HealSteering.grantOnce("), "人工指引必须过单次授权")
        assertTrue(orchestrator.contains("maxHealRounds: Int = HealSteering.BASE_ROUNDS"),
            "自愈上限要有单一事实源，缺省仍是 3 轮")
        assertTrue(orchestrator.contains("maxHealRounds = 1"), "纠偏重跑只允许 1 轮终验")
        assertTrue(orchestrator.contains("steeringUsed = true"), "用过的机会必须落账")
    }

    @Test
    fun anAbandonActionKeepsTheWorkspaceInsteadOfDeletingIt() {
        assertTrue(actions.contains("\"abandon-heal\""), "放弃按钮要有宿主分支，不能落到插件转发被静默丢弃")
        assertTrue(orchestrator.contains("abandonHealByCard"), "放弃要走编排器（保留现场 + 终局翻面）")
        val body = orchestrator.substringAfter("fun abandonHealByCard(").substringBefore("\n    }")
        assertFalse(body.contains("cleanupTask") || body.contains("deleteRecursively") ||
            body.contains("worktree remove"), "放弃分支不许清理现场：${body.take(120)}")
    }

    @Test
    fun theInputDrawerIsTheExistingOneNotANewUiComponent() {
        assertTrue(cards.contains("SYSTEM_ACTION_REJECT_REVISION"),
            "输入槽复用既有抽屉触发常量：零 UI 代码改动、零像素变化")
        assertTrue(cards.contains("CardRevisionDrawer("), "抽屉必须真的挂在卡片上")
    }

    /** 守卫自验证：内存摘线后断言要变红。 */
    @Test
    fun removingTheGrantMakesTheGuardFail() {
        val broken = orchestrator.replace("HealSteering.grantOnce(", "")
        assertFalse(broken.contains("HealSteering.grantOnce("))
    }

    /**
     * review_ready 终态卡的两个出口：打回与放弃。
     *
     * 卡面在 review_ready 发了「打回」按钮，横幅还承诺「等你合并或打回」；宿主若只认
     * blocked，点了就翻 stale —— 承诺断在卡面上（真机 UX 轮现场）。判据读剥注释后的
     * 活代码：注释里大段写了 review_ready，不算数。
     */
    private fun admitsReviewReady(exit: String, source: String): Boolean {
        val body = source.substringAfter("fun $exit(").substringBefore("\n    }")
        return body.contains("active.stage != \"review_ready\"")
    }

    @Test
    fun reviewReadyCardsAreAdmittedByBothSteeringExits() {
        for (exit in listOf("rejectRevisionByCard", "abandonHealByCard")) {
            assertTrue(
                admitsReviewReady(exit, orchestrator),
                "$exit 必须放行 review_ready 终态卡（只认 blocked 会让卡面承诺的打回按钮点了翻 stale）",
            )
        }
    }

    /** 守卫自验证：摘掉 review_ready 分支后，判据要对两个出口都变红。 */
    @Test
    fun removingReviewReadyAdmissionMakesTheGuardFail() {
        val broken = orchestrator.replace(" && active.stage != \"review_ready\"", "")
        assertFalse(admitsReviewReady("rejectRevisionByCard", broken))
        assertFalse(admitsReviewReady("abandonHealByCard", broken))
    }

    // ---- review_ready 卡打回的「查表」闭环守卫 ------------------------------------
    // 上面那组只钉 stage 放行，钉不住「工单此刻还在不在表里」。真机现场正是：
    // 发 review_ready 前工单已从 activeTickets 移除，打回第一步查表就 return false，
    // 卡翻 stale。这里把「快照 → 复活 → 链 fallback」三段都钉死。

    /** 终态卡发出前的快照写入必须在活跃表移除之前 —— 顺序反了快照就丢了。 */
    @Test
    fun reviewReadyTicketIsSnapshottedBeforeLeavingTheActiveTable() {
        val snapshotAt = orchestrator.indexOf("reviewableTickets[ticket.ticketId] = ticket")
        val removeAt = orchestrator.indexOf("activeTickets.remove(ticket.ticketId)", snapshotAt + 1)
        assertTrue(snapshotAt >= 0, "发 review_ready 前必须把工单存进可打回快照表")
        assertTrue(removeAt > snapshotAt, "快照写入必须在 activeTickets.remove 之前，否则打回查表恒空")
    }

    /** 两个打回出口都走 steerableTicket（活跃表 + 快照表），不是裸 activeTickets 查表。 */
    @Test
    fun bothSteeringExitsLookUpTheSnapshotViaSteerableTicket() {
        for (exit in listOf("rejectRevisionByCard", "abandonHealByCard")) {
            val body = orchestrator.substringAfter("fun $exit(").substringBefore("\n    }")
            assertTrue(
                body.contains("steerableTicket(tid)"),
                "$exit 必须经 steerableTicket 查表（活跃表 miss 时回落快照表复活工单）",
            )
        }
        val steer = orchestrator.substringAfter("private fun steerableTicket(").substringBefore("\n    }")
        assertTrue(steer.contains("reviewableTickets.remove(tid)"), "复活必须从快照表摘除")
        assertTrue(steer.contains("activeTickets[tid] = active"), "复活必须放回活跃表供重跑")
    }

    /** 团队责任链卡的打回：单兵表查不到时回落 ChainRetry.reject，接口+实现+反馈前置缺一不可。 */
    @Test
    fun chainCardsAreRejectedThroughTheRecoveryRegistry() {
        assertTrue(chainStage.contains("fun reject(chainId: String, feedback: String)"),
            "ChainRetry 接口要有带反馈的打回重派（老装配默认 false 保兼容）")
        assertTrue(orchestrator.contains("rejectViaChain(tid, feedback)"),
            "rejectRevisionByCard 在单兵表查不到时必须回落链打回")
        val viaChain = orchestrator.substringAfter("private fun rejectViaChain(").substringBefore("\n    }")
        assertTrue(viaChain.contains("as? com.kapp.tenon.agent.ChainRetry"),
            "链打回用探测式 as? 取能力，老装配没实现就如实翻 stale")
        assertTrue(viaChain.contains(".reject(chainId, feedback)"), "探测到能力后真调 reject")

        val rejectBody = desktopChain.substringAfter("override fun reject(").substringBefore("\n        }")
        assertTrue(rejectBody.contains("HealSteering.grantOnce(feedback, launch.revisionUsed)"),
            "链打回与单兵同一套单次授权：用过/空反馈一律拒收")
        assertTrue(desktopChain.contains("revision = grant.instruction"), "打回反馈要写进恢复注册表")

        val runBody = desktopChain.substringAfter("private suspend fun runChain(").substringBefore("\n    }")
        assertTrue(runBody.contains("rawStages.map { stage ->"), "打回反馈要 copy 派生 stages，不改原数组")
        assertTrue(runBody.contains("HUMAN REJECTION FEEDBACK"), "反馈必须前置进每棒 instruction 当验收标准")
    }

    /** 守卫自验证：摘掉链 fallback 后，团队链判据要变红。 */
    @Test
    fun removingChainRejectFallbackMakesTheGuardFail() {
        val broken = orchestrator.replace("return rejectViaChain(tid, feedback)", "return false")
        assertFalse(broken.contains("rejectViaChain(tid, feedback)"))
    }

    // ---- 打回后旧卡收口守卫 --------------------------------------------------------
    // 团队链打回生成新 cardId，旧 review_ready 卡若留在群里，它的「采纳并应用修改」
    // 仍能合入已被打回的旧分支（patch 登记无清理 + merge 对链卡有 applyBranch 兜底）。
    // 真机 UX 轮现场：两张卡同写「等你合并或打回」，用户无从分辨。

    private val relayUiTexts = GuardSourceScan.stripComments(
        GuardSourceScan.read("app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayUiTexts.kt"),
    )
    private val stringsZho = GuardSourceScan.read(
        "composeApp/src/commonMain/composeResources/values/strings.xml",
    )
    private val stringsEn = GuardSourceScan.read(
        "composeApp/src/commonMain/composeResources/values-en/strings.xml",
    )

    private fun rejectBranchOf(source: String): String = source
        .substringAfter("} else if (actionId.startsWith(\"reject-revision\")) {")
        .substringBefore("} else if (actionId == \"abandon-heal\")")

    /** 打回成功后链卡必须就地翻成不可操作终态；单兵同 cardId 原位刷新，靠 hasTicketForCard 保护跳过。 */
    @Test
    fun aRejectedChainCardIsFlippedSoItCannotMergeTheStalePatch() {
        val branch = rejectBranchOf(actions)
        assertTrue(branch.contains("rejected && cardId.startsWith(\"chain-\")"),
            "只有打回**成功**的链卡才翻面：失败路径已有翻面，单兵路径靠活跃表保护")
        assertTrue(branch.contains("delivery.flipStaleChainCard(cardId, agentId, hint = supersededHint)"),
            "旧链卡必须就地翻成 aborted（BoardCardFlip.stale 摘掉 actions），否则仍能合入旧分支")
        assertTrue(branch.contains("controller.uiTexts.revisionSupersedesCard()"),
            "翻面横幅走 uiTexts 桥：编排层零硬编码文案，且必须明说以新卡为准")
    }

    /** 本地化词条三处齐（接口默认空串 + 两语言资源），缺一处真机就露出英文或空横幅。 */
    @Test
    fun supersededCardHintIsLocalizedInBothLocales() {
        assertTrue(relayUiTexts.contains("suspend fun revisionSupersedesCard(): String"),
            "RelayUiTexts 要有该文案的桥方法（缺省空串，老壳回落通用措辞）")
        assertTrue(stringsZho.contains("name=\"orchestration_revision_supersedes_card\""),
            "中文词条缺失：打回后旧卡横幅会退回英文")
        assertTrue(stringsEn.contains("name=\"orchestration_revision_supersedes_card\""),
            "英文词条缺失：设计LocaleParityGate 会红")
    }

    /** 守卫自验证：摘掉翻面调用后，判据要变红。 */
    @Test
    fun removingTheOldCardFlipMakesTheGuardFail() {
        val broken = actions.replace(
            "delivery.flipStaleChainCard(cardId, agentId, hint = supersededHint)",
            "",
        )
        assertFalse(rejectBranchOf(broken).contains("hint = supersededHint"))
    }
}
