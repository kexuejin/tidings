// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 链形状裁决（Planner）的三层钉子：
 *
 * - [PlannerChainGate] 是**纯形状门**：成员来源、恰一干活棒且居首、**干活棒必须是
 *   `kind=worker` 的人**、棒数上限、无重复、指令预算；0 审查棒必须放行（它不是
 *   「新开的门」，编制本来就有这档）；
 * - [TeamPlannerLlm] 是不可用即 null 的**建议**（无 key / 超时 / 垃圾 JSON）；
 * - `TeamPlanCompiler.compile(suggestion = …)` 的**静默失效防线**：1 棒建议必须直接
 *   产出 `Compiled.Single`（不是 `Plan` → `Downgrade`，那条路会把 planner 写的 worker
 *   指令丢掉、退回原始目标），且选中的棒不在位时整份作废回落编译固定链。
 */
class TeamPlannerChainTest {

    private val seatMembers = listOf(
        TeamMemberInfo("m-coder", "coder", "编码"),
        TeamMemberInfo("m-backend", "backend", "后端"),
        TeamMemberInfo("m-reviewer", "reviewer", "审查"),
        TeamMemberInfo("m-tester", "tester", "测试"),
    )
    private val coderInfo = seatMembers[0]

    private val coderSpec = TeamExecutionMemberSpec("m-coder", TeamExecutionKinds.WORKER, "codex")
    private val backendSpec = TeamExecutionMemberSpec("m-backend", TeamExecutionKinds.WORKER, "claude")
    private val reviewerSpec = TeamExecutionMemberSpec("m-reviewer", TeamExecutionKinds.REVIEWER, "dsh")
    private val testerSpec = TeamExecutionMemberSpec("m-tester", TeamExecutionKinds.REVIEWER, "opencode")

    private val profile = TeamExecutionProfile(
        dirId = "dir-1", leaderAgentId = "dsh-lead",
        updatedAt = 0, members = listOf(coderSpec, backendSpec, reviewerSpec, testerSpec),
    )

    private fun suggestion(vararg stages: StageIntent, rationale: String? = null) =
        OrchestrationSuggestion(stages.toList(), rationale)

    // ---------------------------------------------------------------- 形状门

    @Test
    fun zeroReviewerSuggestionIsAccepted() {
        val accepted = PlannerChainGate.accept(
            suggestion(StageIntent("m-coder", asReviewer = false, instruction = "just do it")),
            profile.members,
        )
        assertNotNull(accepted)
        assertEquals(1, accepted.stages.size)
        assertEquals(0, accepted.reviewerCount)
    }

    @Test
    fun anotherWorkerCanBePicked() {
        // 点将：编制里有两位能干活的人，主理人可以把其中任何一位推到链首。
        val accepted = PlannerChainGate.accept(
            suggestion(StageIntent("m-backend", asReviewer = false, instruction = "take the API part")),
            profile.members,
        )
        assertNotNull(accepted)
        assertEquals("m-backend", accepted.stages.single().agentId)
    }

    @Test
    fun aReviewerCannotBePromotedToWorker() {
        // 换人 ≠ 谁都能上：审查员被塞进执行位时，他的授权、提示词、用户预期全是审查语义。
        assertNull(
            PlannerChainGate.accept(
                suggestion(StageIntent("m-tester", asReviewer = false, instruction = "x")),
                profile.members,
            ),
        )
    }

    @Test
    fun reviewersMustNotComeBeforeTheWorker() {
        assertNull(
            PlannerChainGate.accept(
                suggestion(
                    StageIntent("m-reviewer", asReviewer = true, instruction = "review first"),
                    StageIntent("m-coder", asReviewer = false, instruction = "then implement"),
                ),
                profile.members,
            ),
        )
    }

    @Test
    fun membersOutsideTheExecutionProfileAreRejected() {
        // 名册里有人没有 driverKey ⇒ 回查必失败，所以成员来源是执行档而不是群声明。
        assertNull(
            PlannerChainGate.accept(
                suggestion(
                    StageIntent("m-coder", asReviewer = false, instruction = "do"),
                    StageIntent("m-ghost", asReviewer = true, instruction = "review"),
                ),
                profile.members,
            ),
        )
    }

    @Test
    fun duplicateMembersAreRejected() {
        assertNull(
            PlannerChainGate.accept(
                suggestion(
                    StageIntent("m-coder", asReviewer = false, instruction = "do"),
                    StageIntent("m-reviewer", asReviewer = true, instruction = "review"),
                    StageIntent("m-reviewer", asReviewer = true, instruction = "review again"),
                ),
                profile.members,
            ),
        )
    }

    @Test
    fun stageCountIsCappedByMembersAndByFour() {
        val four = TeamExecutionProfile(
            dirId = "dir-1", leaderAgentId = "dsh-lead", updatedAt = 0,
            members = listOf(
                coderSpec,
                reviewerSpec,
                testerSpec,
                TeamExecutionMemberSpec("m-qa", TeamExecutionKinds.REVIEWER, "zcode"),
            ),
        )
        val fiveStages = suggestion(
            StageIntent("m-coder", asReviewer = false, instruction = "do"),
            StageIntent("m-reviewer", asReviewer = true, instruction = "r1"),
            StageIntent("m-tester", asReviewer = true, instruction = "r2"),
            StageIntent("m-qa", asReviewer = true, instruction = "r3"),
            StageIntent("m-reviewer", asReviewer = true, instruction = "r4"),
        )
        assertNull(
            PlannerChainGate.accept(fiveStages, four.members),
            "上限 = min(成员数, 4)：5 棒一律作废",
        )
    }

    @Test
    fun blankAndOversizedInstructionsAreRejected() {
        assertNull(
            PlannerChainGate.accept(
                suggestion(StageIntent("m-coder", asReviewer = false, instruction = "   ")),
                profile.members,
            ),
        )
        val huge = "a".repeat(PlannerChainGate.INSTRUCTION_MAX + 1)
        assertNull(
            PlannerChainGate.accept(
                suggestion(StageIntent("m-coder", asReviewer = false, instruction = huge)),
                profile.members,
            ),
        )
    }

    @Test
    fun aChainWithoutAWorkerIsRejected() {
        assertNull(
            PlannerChainGate.accept(
                suggestion(
                    StageIntent("m-reviewer", asReviewer = true, instruction = "review"),
                    StageIntent("m-tester", asReviewer = true, instruction = "test"),
                ),
                profile.members,
            ),
        )
    }

    // ---------------------------------------------------------------- 建议

    @Test
    fun decideIsNullWithoutKey() {
        assertNull(TeamPlannerLlm.decide(input(), apiKey = null))
        assertNull(TeamPlannerLlm.decide(input(), apiKey = "  "))
    }

    @Test
    fun decideIsNullOnHttpFailureOrGarbage() {
        assertNull(
            TeamPlannerLlm.decide(
                input(), apiKey = "k",
                http = { _, _, _, _ -> throw IllegalStateException("timeout") },
            ),
        )
        assertNull(TeamPlannerLlm.decide(input(), apiKey = "k", http = { _, _, _, _ -> "not json" }))
        assertNull(
            TeamPlannerLlm.decide(
                input(), apiKey = "k",
                http = { _, _, _, _ -> chatResponse("""{"stages":[]}""") },
            ),
        )
    }

    @Test
    fun decideParsesAZeroReviewerAnswer() {
        val out = TeamPlannerLlm.decide(
            input(), apiKey = "k",
            http = { _, _, _, _ ->
                chatResponse(
                    """{"rationale":"single low-risk file","stages":[{"agentId":"m-coder",""" +
                        """"asReviewer":false,"instruction":"fix the typo and run the linter"}]}""",
                )
            },
        )
        assertNotNull(out)
        assertEquals(1, out.stages.size)
        assertEquals(0, out.reviewerCount)
        assertEquals("single low-risk file", out.rationale)
        assertFalse(out.stages.single().asReviewer)
    }

    @Test
    fun thePoolOnlyCarriesInstalledMembers() {
        // 侧证而不是断言内部实现：prompt 里不能出现不在位的驱动所对应的成员 ——
        // 列进去只会换来一次无效往返，再被门整份作废。
        val bodies = mutableListOf<String>()
        TeamPlannerLlm.decide(
            PlannerChainInput(
                goal = "fix it",
                defaultWorkerAgentId = "m-coder",
                members = listOf(
                    TeamPlannerLlm.briefOf(coderSpec, "coder", "编码"),
                    TeamPlannerLlm.briefOf(reviewerSpec, "reviewer", "审查"),
                ),
            ),
            apiKey = "k",
            http = { _, _, body, _ -> bodies.add(body); null },
        )
        val prompt = bodies.single()
        assertTrue(prompt.contains("m-coder"))
        assertTrue(prompt.contains("m-reviewer"))
        assertFalse(prompt.contains("m-tester"), "没进候选池的成员不得出现在提示词里")
    }

    @Test
    fun thePromptSaysTheDefaultWorkerIsNotBinding() {
        // 点将权在模型手里，但「不换人也对」必须写进提示词 —— 否则它会把换人当成
        // 展示能力的机会，每单都换一次。
        val bodies = mutableListOf<String>()
        TeamPlannerLlm.decide(
            input(), apiKey = "k",
            http = { _, _, body, _ -> bodies.add(body); null },
        )
        val prompt = bodies.single()
        assertTrue(prompt.contains("The default is"), prompt)
        assertTrue(prompt.contains("m-coder"), prompt)
        assertTrue(prompt.contains("can never implement"), prompt)
    }

    // ---------------------------------------------------------------- 编译

    @Test
    fun aOneStageSuggestionCompilesToSingleWithThePlannerInstruction() {
        val compiled = TeamPlanCompiler.compile(
            profile, seatMembers, coderInfo,
            goal = "fix the typo",
            availableDrivers = setOf("codex", "claude", "dsh", "opencode"),
            suggestion = suggestion(
                StageIntent("m-coder", asReviewer = false, instruction = "planner worker brief"),
                rationale = "low risk",
            ),
        )
        // 关键：Single，不是 Plan。经 Plan → Downgrade 会把 instruction 换成原始 goal。
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(compiled)
        assertEquals("codex", single.agentKey)
        assertTrue(single.instruction.startsWith("planner worker brief"), single.instruction)
        assertFalse(single.reviewOnly)
        assertTrue(single.plannedByLlm)
    }

    @Test
    fun aPickedWorkerCompilesWithItsOwnDriverAndIdentity() {
        // 点将之后「执行的那一棒」与 `@` 锚点（m-coder）是两个人：底座、身份、授权
        // 对象都必须跟着被点中的那位走。
        val compiled = TeamPlanCompiler.compile(
            profile, seatMembers, coderInfo,
            goal = "take the API part",
            availableDrivers = setOf("codex", "claude", "dsh", "opencode"),
            suggestion = suggestion(
                StageIntent("m-backend", asReviewer = false, instruction = "backend brief"),
            ),
        )
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(compiled)
        assertEquals("claude", single.agentKey)
        assertEquals("m-backend", single.agentId)
        assertEquals(listOf("m-backend"), single.participants)
    }

    @Test
    fun aMultiStageSuggestionCarriesIdentityNamesAndModels() {
        val withModel = TeamExecutionMemberSpec(
            "m-reviewer", TeamExecutionKinds.REVIEWER, "dsh", models = listOf("deepseek-chat"),
        )
        val compiled = TeamPlanCompiler.compile(
            TeamExecutionProfile("dir-1", "dsh-lead", 0L, listOf(coderSpec, withModel)),
            seatMembers, coderInfo,
            goal = "fix the login timeout",
            availableDrivers = setOf("codex", "dsh"),
            suggestion = suggestion(
                StageIntent("m-coder", asReviewer = false, instruction = "worker brief"),
                StageIntent("m-reviewer", asReviewer = true, instruction = "review brief"),
            ),
        )
        val plan = assertIs<TeamPlanCompiler.Compiled.Plan>(compiled)
        assertTrue(plan.plannedByLlm)
        assertEquals(2, plan.plan.stages.size)
        assertEquals("codex", plan.plan.stages[0].agentKey)
        assertEquals("dsh", plan.plan.stages[1].agentKey)
        assertEquals("reviewer", plan.plan.stages[1].assignee, "卡面承办人取群声明里的名字，不是 agentId")
        assertEquals("审查", plan.plan.stages[1].role)
        assertEquals("deepseek-chat", plan.plan.stages[1].model)
        assertTrue(plan.plan.stages[1].instruction.startsWith("review brief"))
        assertEquals(listOf("m-coder", "m-reviewer"), plan.participants)
    }

    @Test
    fun aSuggestionNamingAnUninstalledMemberFallsBackToTheCompiledChain() {
        // opencode 不在位：planner 的这份建议整份作废，回落编译链（worker + 两根 reviewer），
        // 不是把 reviewer 悄悄摘掉，也不是出 blocked 卡。
        val compiled = TeamPlanCompiler.compile(
            profile, seatMembers, coderInfo,
            goal = "fix it",
            availableDrivers = setOf("codex", "claude", "dsh"),
            suggestion = suggestion(
                StageIntent("m-coder", asReviewer = false, instruction = "worker brief"),
                StageIntent("m-tester", asReviewer = true, instruction = "test brief"),
            ),
        )
        val plan = assertIs<TeamPlanCompiler.Compiled.Plan>(compiled)
        assertFalse(plan.plannedByLlm, "回落编译链 ⇒ 不是 planner 的形状")
        assertEquals(3, plan.plan.stages.size, "编译链按编制展开（含不在位的 opencode，交给门裁决）")
    }

    @Test
    fun aSuggestionThatPromotesAReviewerFallsBackToTheCompiledChain() {
        val compiled = TeamPlanCompiler.compile(
            profile, seatMembers, coderInfo,
            goal = "fix it",
            availableDrivers = setOf("codex", "claude", "dsh", "opencode"),
            suggestion = suggestion(StageIntent("m-tester", asReviewer = false, instruction = "do it")),
        )
        val plan = assertIs<TeamPlanCompiler.Compiled.Plan>(compiled)
        assertFalse(plan.plannedByLlm)
        assertEquals("codex", plan.plan.stages.first().agentKey, "回落编译链 ⇒ 锚点本人干活")
    }

    @Test
    fun aSuggestionIsIgnoredWhenAReviewerIsMentionedDirectly() {
        // 命中 reviewer = 对当前工作区做纯审查，链形状没有可裁决的东西。
        val compiled = TeamPlanCompiler.compile(
            profile, seatMembers, seatMembers[2],
            goal = "audit this",
            availableDrivers = setOf("codex", "claude", "dsh", "opencode"),
            suggestion = suggestion(StageIntent("m-coder", asReviewer = false, instruction = "do it")),
        )
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(compiled)
        assertTrue(single.reviewOnly)
        assertFalse(single.plannedByLlm)
    }

    // ---------------------------------------------------------------- 可见性

    @Test
    fun theUnreviewedNoticeOnlyMarksZeroReviewerDispatches() {
        val zero = FlightPlanDecision(FlightPlanDecision.SOURCE_LLM, 1, 0)
        assertEquals("ready · 未审", PlanDecisionBanner.compose("ready", " 未审 ", zero))
        // 有审查棒、纯审查单、1:1 单席位（decision=null）都不打标。
        assertEquals("ready", PlanDecisionBanner.compose("ready", "未审", zero.copy(reviewerCount = 1)))
        assertEquals("ready", PlanDecisionBanner.compose("ready", "未审", null))
        // 取不到文案（没装桥的宿主）：不拼分隔符，也不编中文。
        assertEquals("ready", PlanDecisionBanner.compose("ready", "   ", zero))
        assertEquals("未审", PlanDecisionBanner.compose("", "未审", zero))
    }

    @Test
    fun thePickedWorkerIsAnnouncedOnlyWhenSomebodyElseRunsIt() {
        val switched = FlightPlanDecision(
            source = FlightPlanDecision.SOURCE_LLM,
            stageCount = 1,
            reviewerCount = 0,
            workerAgentId = "m-b",
            workerName = "bob",
            anchorAgentId = "m-a",
        )
        assertTrue(PlanDecisionBanner.workerSwitched(switched))
        assertEquals(
            "ready · 本单由 bob 承办 · 未审",
            PlanDecisionBanner.compose("ready", "未审", switched, "本单由 bob 承办"),
        )
        // 锚点就是承办人（绝大多数单）⇒ 不拼这句：重复同一个名字只是噪音。
        assertFalse(PlanDecisionBanner.workerSwitched(switched.copy(workerAgentId = "m-a")))
        // 大小写不同不是换人（名册里 agentId 大小写不统一是既有事实）。
        assertFalse(PlanDecisionBanner.workerSwitched(switched.copy(workerAgentId = "M-A")))
        // 没有锚点 = 该入口本来就没点人（纯审查），不是"换了个别人"。
        assertFalse(PlanDecisionBanner.workerSwitched(switched.copy(anchorAgentId = null)))
        // 没有名字就没有「由 X 承办」这句 —— 不许拼出「本单由  承办」。
        assertFalse(PlanDecisionBanner.workerSwitched(switched.copy(workerName = "  ")))
        // 文案桥没装（空串）⇒ 不拼分隔符。
        assertEquals("ready · 未审", PlanDecisionBanner.compose("ready", "未审", switched, "   "))
    }

    @Test
    fun theProposedCardUsesTheNoticeAwareBanner() {
        // 结构钉子：把横幅换回裸 teamReadyBanner，标注就静默消失（记录还在日志里，
        // 但用户永远看不到）—— 这正是本仓最贵的一类缺陷。
        val source = GuardSourceScan.stripComments(
            GuardSourceScan.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        )
        assertTrue(
            source.contains("banner = teamReadyBannerWithReviewNotice(ticket)"),
            "点将卡横幅必须走带「未经独立审查」标注的组装函数",
        )
    }

    private fun input() = PlannerChainInput(
        goal = "fix the login timeout",
        defaultWorkerAgentId = "m-coder",
        members = listOf(
            TeamPlannerLlm.briefOf(coderSpec, "coder", "编码"),
            TeamPlannerLlm.briefOf(backendSpec, "backend", "后端"),
            TeamPlannerLlm.briefOf(reviewerSpec, "reviewer", "审查"),
            TeamPlannerLlm.briefOf(testerSpec, "tester", "测试"),
        ),
    )

    private fun chatResponse(content: String): String = buildJsonObject {
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("message", buildJsonObject { put("content", content) })
                    },
                )
            },
        )
    }.toString()
}
