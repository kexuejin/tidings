// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.DelegationPlan
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.DispatchBlockedCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 编制 → 责任链编译层（团队执行方案 §5.1/§5.2/§5.3）的矩阵钉子。
 *
 * 最要紧的三条：
 * - 至少一个 worker：0 worker 在**读取期**也拒（保存期已拒；旧数据走到这里同样拒），
 *   多 worker 合法（点将候选池）；
 * - reviewer 直呼 = 纯审查单棒（不调度 worker 产生冗余代码）；
 * - 0 reviewer = 单棒 worker，**不经 StageChainGate** —— 编译时就做在位检查。
 */
class TeamPlanCompilerTest {

    private val seatMembers = listOf(
        TeamMemberInfo("m-coder", "coder", "编码"),
        TeamMemberInfo("m-reviewer", "reviewer", "审查"),
        TeamMemberInfo("m-tester", "tester", "测试"),
    )
    private val seat = VirtualAgentMember(
        agentId = "dsh-lead", hostNodeId = "host-self", name = "lead",
        entityType = "team", teamMembers = seatMembers, defaultWsId = "ws-1",
    )

    private fun profile(vararg members: TeamExecutionMemberSpec) = TeamExecutionProfile(
        dirId = "ws-1", leaderAgentId = "dsh-lead",
        updatedAt = 0, members = members.toList(),
    )

    private val coderSpec = TeamExecutionMemberSpec("m-coder", TeamExecutionKinds.WORKER, "codex")
    private val reviewerSpec = TeamExecutionMemberSpec("m-reviewer", TeamExecutionKinds.REVIEWER, "claude")
    private val testerSpec = TeamExecutionMemberSpec("m-tester", TeamExecutionKinds.REVIEWER, "dsh")

    private val coderInfo = seatMembers[0]
    private val reviewerInfo = seatMembers[1]

    @Test
    fun workerMentionCompilesWorkerPlusReviewersInOrder() {
        val compiled = TeamPlanCompiler.compile(
            profile(coderSpec, reviewerSpec, testerSpec), seatMembers, coderInfo,
            goal = "fix the login timeout", availableDrivers = setOf("codex", "claude", "dsh"),
        )
        val plan = assertIs<TeamPlanCompiler.Compiled.Plan>(compiled).plan
        assertEquals(3, plan.stages.size)
        assertEquals("codex", plan.stages[0].agentKey)
        assertEquals("编码", plan.stages[0].role, "worker 棒的角色 = 命中组员的中文标签")
        assertEquals("claude", plan.stages[1].agentKey)
        assertEquals("dsh", plan.stages[2].agentKey, "reviewer 按编制顺序")
        assertTrue(plan.fromTemplate, "人已显式选定团队 ⇒ 跳过 LLM planner（§5.2）")
        assertEquals(DelegationPlan.Source.AUTO, plan.source, "AUTO ⇒ 单棒时走 Downgrade 而不是 Reject")
        assertTrue(
            plan.stages[1].instruction.contains("fix the login timeout"),
            "审查棒指令复述目标",
        )
    }

    /**
     * 角色标签属于**群声明**（展示事实），执行档只存执行配置（§3.2）：reviewer 棒的
     * role 必须按 agentId 从声明里取（编码/审查/测试…），缺失/空白才回退 "reviewer"。
     * 声明顺序也是展示顺序的来源（stages 与 seatMembers 对齐）。
     */
    @Test
    fun reviewerStagesCarryTheDeclaredRoleLabels() {
        val compiled = TeamPlanCompiler.compile(
            profile(coderSpec, reviewerSpec, testerSpec), seatMembers, coderInfo,
            goal = "fix the login timeout", availableDrivers = setOf("codex", "claude", "dsh"),
        )
        val plan = assertIs<TeamPlanCompiler.Compiled.Plan>(compiled).plan
        assertEquals("审查", plan.stages[1].role, "reviewer 棒 role = 声明里的中文标签")
        assertEquals("测试", plan.stages[2].role, "第二根 reviewer 棒同源")
        // 声明里没有这个 agentId 的角色（执行档有、声明没有）⇒ 回退 reviewer，不空串进卡面。
        val compilerOnlyProfile = profile(
            coderSpec,
            TeamExecutionMemberSpec("m-reviewer", TeamExecutionKinds.REVIEWER, "claude"),
        )
        val fallback = assertIs<TeamPlanCompiler.Compiled.Plan>(
            TeamPlanCompiler.compile(
                compilerOnlyProfile,
                listOf(seatMembers[0]), // 声明里只有 coder ⇒ reviewer 的角色标签查不到
                coderInfo, "fix", availableDrivers = setOf("codex", "claude"),
            ),
        )
        assertEquals("reviewer", fallback.plan.stages[1].role, "声明缺标签 ⇒ 回退 reviewer")
        // 空白标签同样回退（ifBlank 判据）。
        val blankRole = assertIs<TeamPlanCompiler.Compiled.Plan>(
            TeamPlanCompiler.compile(
                profile(coderSpec, reviewerSpec),
                listOf(seatMembers[0], seatMembers[1].copy(role = "  ")),
                coderInfo, "fix", availableDrivers = setOf("codex", "claude"),
            ),
        )
        assertEquals("reviewer", blankRole.plan.stages[1].role, "空白标签 ⇒ 回退 reviewer")
    }

    /**
     * 审查棒指令（§5.1）：角色标签进提示（`[role: 审查]` 前缀，卡面/日志的语义来源）、
     * 复述目标、且**不含** `VERDICT:` 结论行模板 —— 结论行由 `DesktopStageChain` 对
     * i>0 的棒自动追加，编译层重复一次就会在真 prompt 里出现两行结论要求。
     */
    @Test
    fun reviewInstructionCarriesTheRoleLineAndNeverDuplicatesTheVerdictLine() {
        val withRole = TeamPlanCompiler.reviewInstruction("修复登录超时", "审查")
        assertTrue(
            withRole.startsWith("[role: 审查] "),
            "角色标签要进审查提示（卡面/日志的语义来源）：$withRole",
        )
        assertTrue(withRole.contains("修复登录超时"), "指令复述目标")
        assertTrue(!withRole.contains("VERDICT"), "结论行由链执行器追加，编译层不重复")

        val blankRole = TeamPlanCompiler.reviewInstruction("修复登录超时", "  ")
        assertTrue(
            !blankRole.contains("[role:"),
            "空白角色不产生角色前缀（不输出空的 [role:] 残片）：$blankRole",
        )
        assertTrue(blankRole.contains("修复登录超时"))
        assertTrue(!blankRole.contains("VERDICT"))

        val noRole = TeamPlanCompiler.reviewInstruction("修复登录超时", null)
        assertTrue(!noRole.contains("[role:") && !noRole.contains("VERDICT"))

        val customReview = TeamPlanCompiler.reviewInstruction("修复登录超时", "审查", "聚焦 SQL 注入")
        assertTrue(customReview.startsWith("[role: 审查] 聚焦 SQL 注入\nGoal: 修复登录超时"))

        val customWorker = TeamPlanCompiler.workerInstruction("实现支付功能", "遵循 Google 代码规范")
        assertEquals("遵循 Google 代码规范\nGoal: 实现支付功能", customWorker)
    }

    /**
     * 测试棒默认指令与审查棒分流（复盘项：提示词雷同）。
     *
     * tester 与 reviewer 同为 REVIEWER kind，运行时能区分它们的只有声明里的角色
     * 标签 —— 同一段文案的后果是测试棒从不执行任何验证，名字叫测试、干的是审查。
     * 分流后两条既有约束不动：目标逐字（长目标那条钉着）、编译层不出现 `VERDICT`
     * 字样（结论行由链执行器追加）。
     */
    @Test
    fun testingRoleGetsVerificationInstructionWhileReviewStaysAudit() {
        val tester = TeamPlanCompiler.reviewInstruction("修复登录超时", "测试")
        val reviewer = TeamPlanCompiler.reviewInstruction("修复登录超时", "审查")
        assertTrue(
            tester.contains("running the available build/test commands"),
            "测试棒默认要求真的跑命令：$tester",
        )
        assertTrue(
            !reviewer.contains("running the available build/test commands"),
            "审查棒保持审计口径：$reviewer",
        )
        assertTrue(tester.contains("修复登录超时") && reviewer.contains("修复登录超时"), "两档都复述目标全文")
        assertTrue(!tester.contains("VERDICT") && !reviewer.contains("VERDICT"), "结论行仍由链执行器追加")
        assertTrue(tester.startsWith("[role: 测试] "), "角色标签照进提示")

        // ASCII 词表同流（标签是用户可改的自由文本，词表要宽）：Tester/QA 分流，
        // 非测试词不分流。
        assertTrue(
            TeamPlanCompiler.reviewInstruction("fix login", "Tester")
                .contains("running the available build/test commands"),
        )
        assertTrue(
            TeamPlanCompiler.reviewInstruction("fix login", "QA")
                .contains("running the available build/test commands"),
        )
        assertTrue(
            TeamPlanCompiler.reviewInstruction("fix login", "质检")
                .contains("running the available build/test commands"),
            "质检（QC）也是测试棒 —— 词表窄了分流就不发生",
        )
        assertTrue(
            !TeamPlanCompiler.reviewInstruction("fix login", "Coding")
                .contains("running the available build/test commands"),
        )

        // 自定义人设仍优先：分流只发生在回落档。
        val custom = TeamPlanCompiler.reviewInstruction("修复登录超时", "测试", "逐条核对验收标准")
        assertTrue(!custom.contains("running the available build/test commands"))
        assertTrue(custom.startsWith("[role: 测试] 逐条核对验收标准\nGoal: 修复登录超时"))

        // 空白标签回落审查档（不是测试档）—— 与角色前缀的空白口径一致。
        assertTrue(
            !TeamPlanCompiler.reviewInstruction("修复登录超时", "  ")
                .contains("running the available build/test commands"),
        )
    }

    /**
     * 反向验证（真机责任链跑出来的 P0）：长目标必须**全文**进每一根审查棒。
     *
     * 曾经审查指令写 `goal.take(80)`：worker 棒拿着六条 AC 全量实现，审查棒只看得到
     * "AC1 新建 we"，末尾的 AC5/AC6 根本不在它的输入里 ⇒ 只能判 REJECT ⇒ 回炉后
     * worker 认为活已干完、零补丁 ⇒ 整条链停摆。任务书不是摘要，截断必然制造假拒绝。
     */
    @Test
    fun longGoalReachesEveryReviewStageVerbatim() {
        val goal = listOf(
            "AC1 新建 weather.py，提供 c_to_f(c) 与 f_to_c(f) 两个纯函数。",
            "AC2 0C=32F，100C=212F，-40C=-40F 三个边界必须精确。",
            "AC3 提供 main()，命令行读入一个数字和一个单位，打印一行摘要。",
            "AC4 非法输入退出码为 2，并向 stderr 打印一行 usage 说明。",
            "AC5 新建 test_weather.py，用 unittest 覆盖 5 个测试方法。",
            "AC6 新建 README.md，写清运行示例与 python3 -m unittest -v。",
        ).joinToString("\n")
        assertTrue(goal.length > 80, "探针前提：目标必须长过旧的 80 字截断窗口")

        // 编译层：worker + 两根 reviewer，三棒的指令都要带完整任务书。
        val plan = assertIs<TeamPlanCompiler.Compiled.Plan>(
            TeamPlanCompiler.compile(
                profile(coderSpec, reviewerSpec, testerSpec), seatMembers, coderInfo,
                goal, availableDrivers = setOf("codex", "claude", "dsh"),
            ),
        ).plan
        plan.stages.forEachIndexed { i, stage ->
            assertTrue(stage.instruction.contains(goal), "第 $i 棒的指令丢了目标全文")
            assertTrue(stage.instruction.contains("AC6"), "第 $i 棒看不到最后一条验收标准")
        }

        // 直呼 reviewer 的纯审查单棒路径同样不许截断。
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(
                profile(coderSpec, reviewerSpec, testerSpec), seatMembers, reviewerInfo,
                goal, availableDrivers = setOf("codex", "claude", "dsh"),
            ),
        )
        assertTrue(single.instruction.contains("AC6"), "纯审查单棒也要拿到完整任务书")

        // 自定义人设分支与默认模板分支，两条都不能截。
        assertTrue(TeamPlanCompiler.reviewInstruction(goal, "审查", "逐条核对验收标准").contains("AC6"))
        assertTrue(TeamPlanCompiler.reviewInstruction(goal, null).contains("AC6"))
        // 与 worker 棒对称：同一个 goal，两处都必须是全文（不是一处全文一处摘要）。
        assertTrue(TeamPlanCompiler.workerInstruction(goal).contains("AC6"))
    }

    /** 命中 reviewer：纯审查单棒 —— 不给 worker 派冗余实现任务。 */
    @Test
    fun reviewerMentionCompilesASingleFocusedReviewTask() {
        val compiled = TeamPlanCompiler.compile(
            profile(coderSpec, reviewerSpec, testerSpec), seatMembers, reviewerInfo,
            goal = "audit the login timeout fix", availableDrivers = setOf("codex", "claude", "dsh"),
        )
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(compiled)
        assertEquals("claude", single.agentKey)
        assertTrue(single.reviewOnly)
        assertTrue(
            single.instruction.contains("audit the login timeout fix"),
            "审查指令复述目标",
        )
    }

    /** 0 reviewer：单棒 worker，不经门 —— 但**在位检查**在编译时就做（§5.3）。 */
    @Test
    fun zeroReviewersCompilesSingleWorkerWithAvailabilityCheck() {
        val onlyWorker = profile(coderSpec)
        val ok = TeamPlanCompiler.compile(onlyWorker, listOf(seatMembers[0]), coderInfo, "fix", availableDrivers = setOf("codex"))
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(ok)
        assertEquals("codex", single.agentKey)
        assertTrue(!single.reviewOnly)
        // worker 驱动不在位 ⇒ blocked 卡点名缺谁，不交给 dispatchSingleWorker 撞通用错误。
        val missing = TeamPlanCompiler.compile(onlyWorker, listOf(seatMembers[0]), coderInfo, "fix", availableDrivers = setOf("claude"))
        val rejected = assertIs<TeamPlanCompiler.Compiled.Rejected>(missing)
        assertTrue(rejected.reason.contains("codex"), rejected.reason)
    }

    /**
     * 0 worker：读取期也拒（旧数据/手改文件走到编译这里同样拒）。
     * **多 worker 合法**：`kind` 只声明「谁能干」，这一单谁下场由主理人点将；没有可用
     * 建议时回落锚点本人（见下一条）。
     */
    @Test
    fun aProfileWithoutAnyWorkerIsRejectedAtReadTime() {
        val noWorker = profile(reviewerSpec)
        val rejected = assertIs<TeamPlanCompiler.Compiled.Rejected>(
            TeamPlanCompiler.compile(noWorker, listOf(seatMembers[1]), reviewerInfo, "fix", null),
        )
        assertTrue(rejected.reason.contains("at least one worker"), rejected.reason)
    }

    /** 多 worker + 没有点将建议 ⇒ 锚点本人干活（旧行为逐字保留），不是拒单。 */
    @Test
    fun severalWorkersFallBackToTheAnchorWhenNoSuggestionIsGiven() {
        val twoWorkers = profile(
            coderSpec,
            TeamExecutionMemberSpec("m-b", TeamExecutionKinds.WORKER, "claude"),
        )
        val compiled = TeamPlanCompiler.compile(
            twoWorkers,
            listOf(seatMembers[0], TeamMemberInfo("m-b", "b", "后端")),
            coderInfo,
            "fix",
            availableDrivers = setOf("codex", "claude"),
        )
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(compiled)
        assertEquals("codex", single.agentKey, "锚点 = 编制首位 worker")
        assertEquals("m-coder", single.agentId)
    }

    /** 控制面不可用（available = null）+ 单棒 ⇒ blocked（不假装能派）。 */
    @Test
    fun unavailableControlPlaneRejectsSingleWorkerCompile() {
        val onlyWorker = profile(coderSpec)
        val rejected = assertIs<TeamPlanCompiler.Compiled.Rejected>(
            TeamPlanCompiler.compile(onlyWorker, listOf(seatMembers[0]), coderInfo, "fix", availableDrivers = null),
        )
        assertTrue(rejected.reason.contains("not installed"), rejected.reason)
    }

    /**
     * 能力简报（P3 工具面）要追加到**每一棒**：工单里的审查棒不干活，但它要能判断
     * 「这一版是不是绕过工具直接改了设置」。只给干活棒等于让审查棒瞎判。
     *
     * 反向探针：把 `briefSuffix` 从审查棒的 instruction 上摘掉，这一条会红。
     */
    @Test
    fun theCapabilityBriefReachesEveryStage() {
        val brief = "本机工具：team.roster.read / team.create / team.update"
        val compiled = TeamPlanCompiler.compile(
            profile(coderSpec, reviewerSpec, testerSpec), seatMembers, coderInfo,
            goal = "fix the login timeout",
            availableDrivers = setOf("codex", "claude", "dsh"),
            capabilityBrief = brief,
        )
        val plan = assertIs<TeamPlanCompiler.Compiled.Plan>(compiled).plan
        assertEquals(3, plan.stages.size)
        plan.stages.forEach { stage ->
            assertTrue(brief in stage.instruction, "每一棒都要知道有哪些动作可调：" + stage.agentKey)
        }
        // 单棒路径同样带（reviewer 直呼 / 无 reviewer 的两条腿）。
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(
                profile(coderSpec), listOf(seatMembers[0]), coderInfo, "fix",
                availableDrivers = setOf("codex"), capabilityBrief = brief,
            ),
        )
        assertTrue(brief in single.instruction)
    }

    /** 没装配档案面（brief = null）时**一个字都不加**：不能让 agent 照着空说明书去调路由。 */
    @Test
    fun withoutABriefTheInstructionIsUntouched() {
        val bare = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(
                profile(coderSpec), listOf(seatMembers[0]), coderInfo, "fix",
                availableDrivers = setOf("codex"),
            ),
        )
        val withBlank = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(
                profile(coderSpec), listOf(seatMembers[0]), coderInfo, "fix",
                availableDrivers = setOf("codex"), capabilityBrief = "   ",
            ),
        )
        assertEquals(bare.instruction, withBlank.instruction, "空白简报不许在指令尾部留空行")
    }

    // ------------------------------------------------- 入口语义：@组员 = 单兵（甲案）

    /**
     * `@组员` = 只让它办这一件事：编制里明明有两根审查棒也不展开。
     *
     * 反向探针：把 `entry` 传成缺省的 [DispatchEntry.TEAM]，同一个输入立刻变成
     * 3 棒的 `Plan` —— 差异只来自入口，不来自编制或目标。
     */
    @Test
    fun memberEntryCompilesASingleSoldierWithoutReviewers() {
        val full = profile(coderSpec, reviewerSpec, testerSpec)
        val drivers = setOf("codex", "claude", "dsh")
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(
                full, seatMembers, coderInfo, goal = "fix the login timeout",
                availableDrivers = drivers, entry = DispatchEntry.MEMBER,
            ),
        )
        assertEquals("codex", single.agentKey, "单兵派的就是被点名那个人的底座")
        assertFalse(single.reviewOnly, "它是真干活，不是纯审查单")
        assertTrue(single.instruction.contains("fix the login timeout"))
        assertFalse(
            single.instruction.contains("Review the current workspace"),
            "单兵指令里不许出现审查棒的那套话：${single.instruction}",
        )
        // 同一份编制、同一个目标，只把入口换回整队 ⇒ 立刻是 3 棒链。
        val chained = assertIs<TeamPlanCompiler.Compiled.Plan>(
            TeamPlanCompiler.compile(
                full, seatMembers, coderInfo, goal = "fix the login timeout",
                availableDrivers = drivers, entry = DispatchEntry.TEAM,
            ),
        )
        assertEquals(3, chained.plan.stages.size)
    }

    /** 单兵**不问 Planner**：现场给一份多棒建议也不能改变形状。 */
    @Test
    fun memberEntryIgnoresThePlannerSuggestion() {
        val full = profile(coderSpec, reviewerSpec)
        val drivers = setOf("codex", "claude")
        val suggestion = OrchestrationSuggestion(
            stages = listOf(
                StageIntent("m-coder", asReviewer = false, instruction = "先调研再改"),
                StageIntent("m-reviewer", asReviewer = true, instruction = "逐条核验"),
            ),
            rationale = "有两个独立面要查",
        )
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(
                full, seatMembers, coderInfo, goal = "fix", availableDrivers = drivers,
                suggestion = suggestion, entry = DispatchEntry.MEMBER,
            ),
        )
        assertFalse(single.plannedByLlm, "单兵形状不来自 LLM")
        assertEquals("fix", single.instruction, "单兵指令 = 目标本身，不是 Planner 写的那句")
        // 对照组：整队入口采纳同一份建议 ⇒ 链与指令都来自建议。
        val planned = assertIs<TeamPlanCompiler.Compiled.Plan>(
            TeamPlanCompiler.compile(
                full, seatMembers, coderInfo, goal = "fix", availableDrivers = drivers,
                suggestion = suggestion, entry = DispatchEntry.TEAM,
            ),
        )
        assertTrue(planned.plannedByLlm)
        assertEquals("先调研再改", planned.plan.stages[0].instruction)
    }

    /** 单兵的底座不在位：编译期就拒（带 DRIVER_MISSING），不交给派单撞通用错误。 */
    @Test
    fun aMemberWhoseDriverIsMissingIsRejectedAtCompileTime() {
        val rejected = assertIs<TeamPlanCompiler.Compiled.Rejected>(
            TeamPlanCompiler.compile(
                profile(coderSpec, reviewerSpec), seatMembers, coderInfo, goal = "fix",
                availableDrivers = setOf("claude"), entry = DispatchEntry.MEMBER,
            ),
        )
        assertEquals(DispatchBlockedCode.DRIVER_MISSING, rejected.code)
    }

    /** 点名审查员仍是纯审查单棒（单兵语义不改变「它本来就不干活」这件事）。 */
    @Test
    fun memberEntryForAReviewerStaysAPureReviewTask() {
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(
                profile(coderSpec, reviewerSpec), seatMembers, reviewerInfo, goal = "看一眼前面的改动",
                availableDrivers = setOf("codex", "claude"), entry = DispatchEntry.MEMBER,
            ),
        )
        assertTrue(single.reviewOnly)
        assertEquals("claude", single.agentKey)
    }

    // --------------------------------------------- 参与人（授权的输入）

    /**
     * `participants` = 这次**真的会被调起的人**，与链序一致 —— 授权必须按它逐个判。
     *
     * 反向探针：把 `Plan.participants` 换成 `stages.map { it.agentKey }`，第一条就会红
     * （驱动键不是身份：两个人可以共用同一个底座）。
     */
    @Test
    fun participantsListThePeopleActuallyInvokedInChainOrder() {
        val full = profile(coderSpec, reviewerSpec, testerSpec)
        val drivers = setOf("codex", "claude", "dsh")
        val plan = assertIs<TeamPlanCompiler.Compiled.Plan>(
            TeamPlanCompiler.compile(full, seatMembers, coderInfo, "fix", drivers),
        )
        assertEquals(listOf("m-coder", "m-reviewer", "m-tester"), plan.participants)
        assertEquals(
            plan.plan.stages.size,
            plan.participants.size,
            "参与人与棒表必须同长：授权漏一个人 = 那条棒在没授权的情况下跑命令",
        )

        val single = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(profile(coderSpec), listOf(seatMembers[0]), coderInfo, "fix", setOf("codex")),
        )
        assertEquals(listOf("m-coder"), single.participants)

        // 纯审查单棒：被调起的是审查员本人（不是编制里的 worker）。
        val reviewOnly = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(full, seatMembers, reviewerInfo, "audit", drivers),
        )
        assertEquals(listOf("m-reviewer"), reviewOnly.participants)

        // 单兵：只有它自己，哪怕编制里还有两个人。
        val soldier = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(
                full, seatMembers, coderInfo, "fix", drivers, entry = DispatchEntry.MEMBER,
            ),
        )
        assertEquals(listOf("m-coder"), soldier.participants)

        // 拒绝：没有人会被调起 —— 空集不是「跳过授权」，是没有授权对象。
        val rejected = assertIs<TeamPlanCompiler.Compiled.Rejected>(
            TeamPlanCompiler.compile(profile(reviewerSpec), listOf(seatMembers[1]), reviewerInfo, "fix", drivers),
        )
        assertTrue(rejected.participants.isEmpty())
    }

    /**
     * 参与人**保留执行档里的大小写**：授权台账与执行范围都按字符串精确命中，
     * 归一成小写会把「配过策略的人」判成「没配过」（fail-closed 变成全员不能用）。
     */
    @Test
    fun participantsKeepTheProfileCasing() {
        val mixedSeat = listOf(TeamMemberInfo("M-Coder", "coder", "编码"))
        val mixedProfile = TeamExecutionProfile(
            dirId = "ws-1", leaderAgentId = "dsh-lead", updatedAt = 0,
            members = listOf(TeamExecutionMemberSpec("M-Coder", TeamExecutionKinds.WORKER, "codex")),
        )
        val single = assertIs<TeamPlanCompiler.Compiled.Single>(
            TeamPlanCompiler.compile(mixedProfile, mixedSeat, mixedSeat[0], "fix", setOf("codex")),
        )
        assertEquals(listOf("M-Coder"), single.participants, "授权键必须与档案里写的一字不差")
    }

    /** 采纳 Planner 后，参与人跟着**它排的顺序**走（不是编制顺序）。 */
    @Test
    fun plannedParticipantsFollowTheSuggestionOrder() {
        val full = profile(coderSpec, reviewerSpec, testerSpec)
        val suggestion = OrchestrationSuggestion(
            stages = listOf(
                StageIntent("m-coder", asReviewer = false, instruction = "先调研再改"),
                StageIntent("m-tester", asReviewer = true, instruction = "跑测试"),
            ),
        )
        val plan = assertIs<TeamPlanCompiler.Compiled.Plan>(
            TeamPlanCompiler.compile(
                full, seatMembers, coderInfo, "fix", setOf("codex", "claude", "dsh"),
                suggestion = suggestion,
            ),
        )
        assertEquals(listOf("m-coder", "m-tester"), plan.participants, "审查棒是 tester，不是编制里的第一个 reviewer")
        assertEquals(2, plan.plan.stages.size)
    }
}
