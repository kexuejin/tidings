// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.DelegationPlan
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 编制 → 责任链编译层（团队执行方案 §5.1/§5.2/§5.3）的矩阵钉子。
 *
 * 最要紧的三条：
 * - 恰好一个 worker：0/多 worker 在**读取期**也拒（保存期已拒；旧数据走到这里同样拒）；
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
        groupId = "g1", dirId = "ws-1", leaderAgentId = "dsh-lead",
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

    /** 0/多 worker：读取期也拒（旧数据/手改文件走到编译这里同样拒）。 */
    @Test
    fun zeroOrMultipleWorkersAreRejectedAtReadTime() {
        val noWorker = profile(reviewerSpec)
        val rejected = assertIs<TeamPlanCompiler.Compiled.Rejected>(
            TeamPlanCompiler.compile(noWorker, listOf(seatMembers[1]), reviewerInfo, "fix", null),
        )
        assertTrue(rejected.reason.contains("exactly one worker"))
        val twoWorkers = profile(
            coderSpec,
            TeamExecutionMemberSpec("m-b", TeamExecutionKinds.WORKER, "claude"),
            testerSpec,
        )
        assertIs<TeamPlanCompiler.Compiled.Rejected>(
            TeamPlanCompiler.compile(twoWorkers, seatMembers, coderInfo, "fix", null),
        )
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
}
