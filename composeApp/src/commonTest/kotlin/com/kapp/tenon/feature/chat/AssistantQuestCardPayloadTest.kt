// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.core.protocol.AgentSenderId
import com.kapp.tenon.core.protocol.AssistantQuestNotice
import com.kapp.tenon.core.protocol.QuestStatus
import com.kapp.tenon.domain.AssistantQuestCardPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 支线通知 → 卡片载荷的**跨模块契约**对拍：:app 构建方的产物必须能被
 * composeApp 的真解析器 [parseAgentCard] 读回 —— 构建方住 :app（与落库同层）、
 * 解析真源住 composeApp（与渲染同层），这份测试就是两个模块之间的形状契约钉子。
 */
class AssistantQuestCardPayloadTest {

    @Test
    fun noticeConvertsToAgentCardAndParsesCorrectly() {
        val notice = AssistantQuestNotice(
            questId = "quest-ui-101",
            convId = "assistant",
            status = QuestStatus.WAITING_INPUT,
            title = "Migrate Database Schema",
            summary = "Waiting for human review on column dropped",
            rev = 3L,
            questionText = "Drop column safely?",
            diffStats = "+10 -2",
        )

        val jsonObj = AssistantQuestCardPayload.build(notice)
        val parsed = parseAgentCard(jsonObj)

        assertNotNull(parsed)
        assertEquals("quest-ui-101", parsed.cardId)
        assertEquals(3, parsed.rev)
        assertEquals("Migrate Database Schema", parsed.title)
        assertEquals("blocked", parsed.state)
        assertEquals(AssistantQuestCardPayload.TYPE_ASSISTANT_QUEST, parsed.type)
        assertEquals(1, parsed.steps.size)
        assertEquals("Drop column safely?", parsed.steps[0].label)
        assertNotNull(parsed.diff)
        assertNull(parsed.diff.path)
        assertEquals("+10 -2", parsed.diff.summary)
        assertEquals(1, parsed.actions.size)
        assertEquals("reply-tune", parsed.actions[0].actionId)
        assertEquals("card.action.reply", parsed.actions[0].labelKey)
    }

    @Test
    fun completedNoticeGeneratesMergeAction() {
        val notice = AssistantQuestNotice(
            questId = "quest-ui-102",
            convId = "assistant",
            status = QuestStatus.COMPLETED,
            title = "All tests green",
            summary = "Ready to merge",
            rev = 4L,
        )

        val parsed = parseAgentCard(AssistantQuestCardPayload.build(notice))
        assertNotNull(parsed)
        assertEquals("review_ready", parsed.state)
        assertEquals(100, parsed.progress)
        assertTrue(parsed.actions.any {
            it.actionId == "merge-patch" && it.approval && it.labelKey == "card.action.merge"
        })
    }

    @Test
    fun waitingInputWithStructuredMergeKindGeneratesMergeAction() {
        // 结构化 questionKind 是主路径：不从自然语言里嗅探（评审点名的假语义）。
        val notice = AssistantQuestNotice(
            questId = "quest-ui-103",
            convId = "assistant",
            status = QuestStatus.WAITING_INPUT,
            title = "All tests green",
            summary = "Waiting for confirmation",
            rev = 5L,
            questionText = "Proceed?",
            questionKind = AssistantQuestNotice.QUESTION_MERGE_APPROVAL,
        )

        val parsed = parseAgentCard(AssistantQuestCardPayload.build(notice))
        assertNotNull(parsed)
        assertTrue(parsed.actions.any { it.actionId == "merge-patch" && it.approval })
    }

    @Test
    fun legacyNoticeWithoutKindFallsBackToKeywordSniff() {
        // 老通知兼容：questionKind 缺省时才回退关键词匹配 —— 是兼容路径不是主路径。
        val notice = AssistantQuestNotice(
            questId = "quest-ui-104",
            convId = "assistant",
            status = QuestStatus.WAITING_INPUT,
            title = "All tests green",
            summary = "Waiting for merge confirmation",
            rev = 5L,
            questionText = "Confirm merge to main branch?",
        )

        val parsed = parseAgentCard(AssistantQuestCardPayload.build(notice))
        assertNotNull(parsed)
        assertTrue(parsed.actions.any { it.actionId == "merge-patch" && it.approval })
    }

    @Test
    fun questionMentioningMergeWithoutKindDoesNotGetMergeAction() {
        // 反向钉子：questionText 顺带提到 merge 一词、又没有结构化 kind 的**新**语义
        // 不成立 —— 但兼容路径仍在。这条钉住的是「questionKind 明确为非 merge」时
        // 不出合并按钮（生产侧发普通问题必须给 null 而不是含 merge 字样的文本）。
        val notice = AssistantQuestNotice(
            questId = "quest-ui-105",
            convId = "assistant",
            status = QuestStatus.WAITING_INPUT,
            title = "Conflict triage",
            summary = "Needs human decision",
            rev = 6L,
            questionText = "How should we resolve the merge conflict in File.kt?",
            questionKind = "plain_question",
        )

        val parsed = parseAgentCard(AssistantQuestCardPayload.build(notice))
        assertNotNull(parsed)
        assertTrue(parsed.actions.none { it.actionId == "merge-patch" })
        assertEquals("reply-tune", parsed.actions.single().actionId)
    }

    @Test
    fun runningNoticeGeneratesOpenQuestAction() {
        val notice = AssistantQuestNotice(
            questId = "quest-ui-106",
            convId = "assistant",
            status = QuestStatus.RUNNING,
            title = "Executing background checks",
            summary = "Running in worktree sandbox",
            rev = 1L,
        )

        val parsed = parseAgentCard(AssistantQuestCardPayload.build(notice))
        assertNotNull(parsed)
        assertEquals("working", parsed.state)
        assertTrue(parsed.actions.any { it.actionId == "open-quest" && it.labelKey == "card.action.open_quest" })
    }

    @Test
    fun pausedNoticeGeneratesResumeAction() {
        val notice = AssistantQuestNotice(
            questId = "quest-ui-107",
            convId = "assistant",
            status = QuestStatus.PAUSED,
            title = "Timed out",
            summary = "Waiting timed out",
            rev = 2L,
        )

        val parsed = parseAgentCard(AssistantQuestCardPayload.build(notice))
        assertNotNull(parsed)
        assertTrue(parsed.actions.any { it.actionId == "resume-quest" })
    }

    @Test
    fun payloadRoundTripsThroughJsonText() {
        // 落库走 JsonObject，但消息帧/日志可能走文本序列化 —— 文本链路也要无损。
        val notice = AssistantQuestNotice(
            questId = "quest-ui-108",
            convId = "assistant",
            status = QuestStatus.FAILED,
            title = "Boom",
            summary = "It failed",
            rev = 9L,
        )
        val json = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), AssistantQuestCardPayload.build(notice))
        val reparsed = parseAgentCard(Json.parseToJsonElement(json).jsonObject)
        assertNotNull(reparsed)
        assertEquals("aborted", reparsed.state)
        assertTrue(reparsed.actions.any { it.actionId == "retry-quest" && it.style == "danger" })
    }

    @Test
    fun assistantSenderIdIsParseableForCardActionChain() {
        // 卡片动作入口依赖 senderId 解析出 (host, agentId)（RelayControllerAgentCardActions
        // 的 `AgentSenderId.parse(...) ?: return`）—— 助理卡的身份必须能走通这条链。
        val senderId = AgentSenderId.of("node-abcd1234", agentId = "assistant")
        val (host, agentId) = AgentSenderId.parse(senderId)!!
        assertEquals("node-abcd1234", host)
        assertEquals("assistant", agentId)
    }
}
