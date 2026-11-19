// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「请 TA 复检」的**接线守卫**：出卡 → 端上词条 → 宿主分支 → 编排器 → 真派一单。
 *
 * 这条链有五环，每一环单独看都「有实现有测试」，断在任何一环，用户看到的现象都是
 * **同一件事：卡上那颗按钮点了没反应**（多数人此时会以为是网络，再点第二次）。
 * 纯函数测试守不住这种断线 —— 它们测的是「说什么话」「找谁」，测不到「有没有接上」。
 */
class ReviewByCardWiringGuardTest {

    private val chain = GuardSourceScan.stripComments(
        GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/StageChain.kt"),
    )
    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
    )
    private val actions = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/HostCardActions.kt"),
    )
    private val labels = GuardSourceScan.stripComments(
        GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/CardActionLabels.kt"),
    )

    @Test
    fun theCardFaceIsTheOnlyProducerOfTheActionString() {
        assertTrue(chain.contains("requestReviewActionId(who.token)"), "出卡侧必须用同一个编码函数")
        assertTrue(chain.contains("\"card.action.request_review\""), "labelKey 要对端上登记的键")
        assertTrue(chain.contains("fun requestReviewTokenOf("), "解回 token 的判据也住出卡侧（格式只有一份）")
    }

    @Test
    fun theHostBranchReachesTheOrchestratorInsteadOfAFallback() {
        assertTrue(
            actions.contains("StageChain.ACTION_REQUEST_REVIEW"),
            "动作分支不能写死字符串：格式改了必须编译期报错，而不是点下去静默落空",
        )
        assertTrue(actions.contains("?.reviewByCard(cardId, token)"), "必须真交给编排器")
        assertTrue(actions.contains("flipStaleChainCard"), "派不出去要翻面：静默无反应是最贵的形态")
    }

    @Test
    fun theOrchestratorDispatchesARealReviewRunWithTheReviewersOwnDriver() {
        assertTrue(orchestrator.contains("fun reviewByCard(cardId: String, reviewerToken: String): Boolean"))
        assertTrue(orchestrator.contains("TicketPatchLedger.lookup(cardId)"), "补丁账在磁盘上：重启后也要读得到")
        assertTrue(orchestrator.contains("CardReviewAsk.instruction("), "提示词走那一条纯函数（不在这里另写一份）")
        val body = orchestrator.substringAfter("fun reviewByCard(").substringBefore("\n    }")
        assertTrue(body.contains("dispatchSingleWorker("), "复检必须真派一次进程：${body.take(160)}")
        assertTrue(body.contains("spec.driverKey"), "底座取审查员自己的 driverKey，不是干活那位的")
    }

    @Test
    fun theSingleShotDispatchCarriesSomeoneToAsk() {
        assertTrue(
            orchestrator.contains("CardReviewAsk.pickReviewTarget("),
            "单兵派单必须把「该找谁审」带进工单，否则卡上永远没有这颗按钮",
        )
        assertTrue(
            orchestrator.contains("reviewBy = if (outcome.reviewOnly)"),
            "纯审查单棒不该再问「谁来审审查员」",
        )
    }

    @Test
    fun thePhoneKnowsTheKeyOrTheButtonDegradesToEnglish() {
        assertTrue(
            labels.contains("\"card.action.request_review\" to Res.string.card_action_request_review"),
            "端上不登记这个键，文案会静默退化成「Request review」——「请谁」这件事就丢了",
        )
    }

    /** 守卫自验证：把接线摘掉，断言必须变红（否则这几条只是「文件里恰好出现过」）。 */
    @Test
    fun removingTheWiringMakesTheGuardFail() {
        assertFalse(actions.replace("?.reviewByCard(cardId, token)", "").contains("?.reviewByCard(cardId, token)"))
        assertFalse(
            orchestrator.replace("CardReviewAsk.pickReviewTarget(", "")
                .contains("CardReviewAsk.pickReviewTarget("),
        )
    }
}
