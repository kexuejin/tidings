// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S4-2f 收口：宿主兜底的 `choices` CSV 是**出站帧上远端能看到的取值**，钉死它 = 钉死 wire 事实。
 *
 * 决议（用户 2026-10-31 拍板）：宿主兜底改说**生态词**——`choices` 读它的是第三方，
 * 照文档 §6.5 写 `allow/deny` 的插件，遇上旧的 `approved,denied` 兜底会把一次
 * 「明确声明」读成「沉默」（本仓最贵缺陷的取值域形态：键对、值不在对方的认识里）。
 */
class ApprovalChoicesFallbackTest {

    @Test
    fun hostFallbackSpeaksTheEcosystemVocabulary() {
        assertEquals(
            "allow,deny,allow_session,always", defaultApprovalChoicesCsv(),
            "前两档必须是生态词 allow/deny（S4-2f）；allow_session/always 是本端增补的回执取值，照旧附带",
        )
    }

    @Test
    fun receiptVocabularyIsUntouchedByTheChoicesFork() {
        // 词汇分叉只收在 choices 字段上：回执 agent-ack.kind 的取值域一格都不动。
        assertEquals("approved", AgentAckKinds.APPROVED)
        assertEquals("denied", AgentAckKinds.DENIED)
        assertEquals("always", AgentAckKinds.ALWAYS)
        assertEquals("allow_session", AgentAckKinds.ALLOW_SESSION)
    }

    @Test
    fun ecosystemWordsAreNotReceiptKinds() {
        // 两个词汇表必须保持可区分：谁要是把生态词加进回执取值域（或反之），
        // 这里先红 —— 两套词各有各的门，混了就是下一场「取值域多人书写」。
        kotlin.test.assertTrue(!AgentAckKinds.isKnown(ApprovalChoiceWords.ALLOW))
        kotlin.test.assertTrue(!AgentAckKinds.isKnown(ApprovalChoiceWords.DENY))
    }
}
