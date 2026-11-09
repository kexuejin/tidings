// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「授权拒绝必须说出口」的**源码守卫**。
 *
 * 行为探针 [`IntentDeniedFeedbackTest`] 钉住了现象；这里钉住三处接线，防止有人
 * 把广播换回本地 notice、去掉去重、或让群帧全员可见时每台宿主各回一条。
 *
 * 抽掉任何一处，编译照样绿、单测也可能绿（本机落库仍看得到），但真机手机端
 * 会退回「消息到了、零卡片零横幅」—— 正是 T5 阻塞的形态。
 */
class IntentDeniedWiringGuardTest {

    private fun source(): String = GuardSourceScan.stripComments(
        GuardSourceScan.read(
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelayControllerAgentInvokeComponent.kt",
        ),
    )

    private fun deniedMethodBody(): String = GuardSourceScan.bracedBlockAfter(
        source(), "private fun announceIntentDispatchDenied(",
    )

    @Test
    fun theDeniedBranchCallsTheGroupAnnouncement() {
        // 文件里另有 `handleInvokeTask` 的跨宿主拒绝分支也用 `permitsExecution()`；
        // 全文取块会撞到那一处。先锁定 handleInboundAgentIntent 的函数体，再切出
        // 授权门到执行器之间的那一段（拒绝分支就住在这里）。
        val fn = GuardSourceScan.bracedBlockAfter(source(), "fun handleInboundAgentIntent(")
        val fromVerdict = fn.substringAfter("verdict.permitsExecution()")
        val branch = fromVerdict.substringBefore("val ex = executor")
        assertTrue(
            branch.contains("announceIntentDispatchDenied("),
            "群意图的授权拒绝分支必须额外发群回执；只落本地 AgentNotAuthorized notice 等于手机端全静默",
        )
        assertTrue(
            branch.contains("effectiveHost == self"),
            "只有托管该席位的宿主回执 —— 群帧全员可见，不收窄会 N 台机器各回一条",
        )
    }

    @Test
    fun theAnnouncementGoesThroughTheProtocolFramePath() {
        val body = deniedMethodBody()
        assertTrue(
            body.contains("broadcastAgentState(groupId, agentId, AGENT_STATE_FAILED, text)"),
            "回执必须走 broadcastAgentState（协议帧 + 本机同帧落库），不是再写一条本地 notice",
        )
        assertTrue(
            body.contains("host.uiTexts.agentActionDeniedNotice(actorName, agentName)"),
            "措辞必须来自文案桥（CJK 门禁 / 多语言对账同一条纪律），领域层不编中文",
        )
        assertFalse(
            body.contains("handleAppendMessage"),
            "announceIntentDispatchDenied 里再落一条本地行就退回缺陷本身了",
        )
    }

    @Test
    fun theDenialIsDeduplicatedAndDoesNotWeakenTheAuthorizer() {
        val body = deniedMethodBody()
        assertTrue(
            body.contains("deniedIntentDispatches.add(key)") && body.contains("messageId ?:"),
            "按 messageId 去重：重投 / 重放不许把群刷成一片红",
        )
        val src = source()
        assertTrue(
            src.contains("AgentExecAuthorizer.authorize(") &&
                src.contains("verdict.permitsExecution()"),
            "授权判据原样保留 —— 这里修的是『拒绝说不说得出口』，不是『谁能执行』",
        )
        val hostGuard = GuardSourceScan.bracedBlockAfter(src, "if (effectiveHost == self) {")
        assertTrue(
            hostGuard.contains("announceIntentDispatchDenied("),
            "席位宿主收窄必须真的包住广播调用，不能只留一个空 if",
        )
    }
}
