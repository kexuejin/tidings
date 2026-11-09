// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.DelegationParser
import com.kapp.tenon.agent.DelegationTeam
import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 认知研讨流的**接线守卫**（P2 规格 1）。
 *
 * 这三件事编译期都不会红，只在真机上以「调研期间多了一个分支 / 两个 agent 互相客套到
 * 超时 / 说好只读却写了盘」的形式出现：
 * 1. 分诊必须排在派单解析**之前**（否则一句提问就被当成工单）；
 * 2. 讨论体里不许出现 worktree（否则「零副作用」就是一句口号）；
 * 3. 写工具拦截与轮次上限必须在体里（提示词是请求，代码才是约束）。
 */
class CognitiveDiscussionWiringGuardTest {

    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
    )

    private fun bodyOf(signature: String, endMarker: String): String {
        val start = orchestrator.indexOf(signature)
        assertTrue(start >= 0, "找不到签名：$signature")
        val end = orchestrator.indexOf(endMarker, start)
        assertTrue(end > start, "找不到 $signature 的结束标记：$endMarker")
        return orchestrator.substring(start, end)
    }

    private val discussion = bodyOf("private suspend fun runDiscussion(", "private fun trimProposals")

    @Test
    fun triageRunsBeforeDelegationParsingOnBothEntrances() {
        val first = orchestrator.indexOf("CognitiveFlow.classify(prompt) == CognitiveFlow.Intent.Discuss")
        assertTrue(first >= 0, "分诊口必须存在（规格 1.1：没带显式派单指令 ⇒ 只读）")
        val delegated = orchestrator.indexOf("val plan = parseDelegation(prompt, available, groupId)")
        assertTrue(delegated > first, "分诊必须排在派单解析之前，否则提问会被当成工单")
        // 两条入口、两种口径（A22 真机改判）：**文本路由**无条件分诊（没有药丸，
        // 句子语气是唯一意图线索）；**invoke 桥**（1:1 药丸）是用户选了工作区、
        // 按了发送的显式派单 —— 分诊门控到群路径（groupId != null），1:1 药丸
        // 单不再被 "how to" 之类的问句标记改判成只读聊天（真机：真实需求被
        // INQUIRY 命中，工单没了）。
        val occurrences = Regex(Regex.escape("CognitiveFlow.classify(prompt) == CognitiveFlow.Intent.Discuss"))
            .findAll(orchestrator).count()
        assertTrue(occurrences >= 2, "两条派单入口都要有分诊位，实际命中 $occurrences 处")
        val invokeTriage = orchestrator.indexOf(
            "groupId != null && CognitiveFlow.classify(prompt) == CognitiveFlow.Intent.Discuss",
        )
        assertTrue(invokeTriage > 0, "invoke 桥的分诊必须门控到群路径：1:1 药丸是显式派单，不分诊")
    }

    @Test
    fun theDiscussionBodyNeverTouchesAWorktree() {
        assertTrue(
            !discussion.contains("WorktreeSandbox") && !discussion.contains("DesktopStageChain"),
            "讨论流的函数体里不许出现 worktree —— 调研零副作用靠的是「根本没有那条路径」",
        )
        assertTrue(
            discussion.contains("wsPath = workdir"),
            "只读档的 cwd 必须是群绑定的普通目录（不建 worktree）",
        )
        assertTrue(
            orchestrator.contains("private fun discussionWorkdir("),
            "讨论用的目录解析必须独立于 resolveWorkspacePath（后者要求 git 仓，讨论不需要）",
        )
    }

    @Test
    fun everyAgentSpeaksAtMostOnceAndTheHostClosesTheTurn() {
        assertTrue(
            discussion.contains("for (turn in 0 until CognitiveFlow.MAX_AGENT_TURNS)"),
            "轮次上限必须来自 CognitiveFlow.MAX_AGENT_TURNS（唯一事实源）",
        )
        assertTrue(
            discussion.contains("RelayController.NO_WAKE_LOG_TAG") && discussion.contains("TURN_EXHAUSTED"),
            "轮次耗尽要有 NO_WAKE 诊断（TURN_EXHAUSTED）：这是「没被无限互撩拖死」的证据",
        )
    }

    @Test
    fun aWriteToolDuringTheReadOnlyTurnAbortsAndFlipsTheCard() {
        assertTrue(
            discussion.contains("CognitiveFlow.allowsTool(summary)"),
            "必须按驱动上报的工具名做白名单判定 —— 只靠提示词自觉等于没有拦截",
        )
        assertTrue(
            discussion.contains("READ_ONLY_LOG_TAG"),
            "越界要留诊断：没有日志的「已拦截」等于没发生过",
        )
        assertTrue(
            discussion.contains("break@outer"),
            "越界必须停掉后面的棒，不能继续跑完再把结果丢掉",
        )
        assertTrue(
            discussion.contains("Read-only discussion aborted"),
            "越界要有一张如实翻面的卡：静默不出卡 = 用户以为还在讨论",
        )
    }

    @Test
    fun theProposalCardIsAWaitingTicketNotAStartedOne() {
        assertTrue(
            discussion.contains("state = \"proposed\""),
            "提议卡必须停在 proposed（等人点头），不能自己开工",
        )
        assertTrue(
            discussion.contains("stageRows = plan?.stages?.map { it.role to \"pending\" }"),
            "卡面要亮出推荐团队（Worker ➔ Reviewer），人才谈得上点头",
        )
        assertTrue(
            discussion.contains("proposals[cardId] = Proposal("),
            "提议必须登记：否则点「开工」时无从取回目标",
        )
    }

    @Test
    fun clickingStartReparsesThroughTheTeamTemplateAndTheGate() {
        val start = bodyOf("private fun startProposalByCard(", "private fun cancelProposalByCard")
        assertTrue(
            start.contains("parseDelegation(DISCUSSION_GOAL_PREFIX + proposal.goal"),
            "点击开工必须重新过解析器（模板优先）：不能发布时就把计划焊死",
        )
        assertTrue(start.contains("delegateByPlan("), "开工要落到既有委派出口（代码门在其中）")
        assertTrue(
            orchestrator.contains("return startProposalByCard(tid)"),
            "卡片动作要真的走到提议分支：startByCard 里必须接上",
        )
        assertTrue(
            orchestrator.contains("return cancelProposalByCard(tid, reason)"),
            "取消也要走到提议分支：否则点取消会翻成「失效」",
        )
    }

    @Test
    fun aProposalGoalParsesThroughTheAutoTeamLaneWithTheGroupTemplate() {
        val text = "team: 新增 SqliteOfflineQueueStore，单写多读"
        val available = setOf("claude", "dsh", "codex")
        val viaTemplate = DelegationParser.parse(
            text,
            available,
            available,
            team = DelegationTeam(groupId = "g1", workerKey = "claude", reviewerKey = "dsh", updatedAt = 0L),
        )
        assertNotNull(viaTemplate, "提议目标必须能被解析器认成派单")
        assertTrue(viaTemplate.fromTemplate, "命中群模板时 fromTemplate=true（跳过 planner 的依据）")
        assertEquals("claude", viaTemplate.workerStage?.agentKey)
        assertEquals("dsh", viaTemplate.reviewerStage?.agentKey)
        // 没有模板也要给出两棒（启发式保底），不能因为没配模板就只给一棒以外的形状。
        val heuristic = DelegationParser.parse(text, available, available, team = null)
        assertNotNull(heuristic)
        assertTrue(heuristic.stages.size >= 2, "启发式至少两棒（干活 + 异厂商审核）")
    }
}
