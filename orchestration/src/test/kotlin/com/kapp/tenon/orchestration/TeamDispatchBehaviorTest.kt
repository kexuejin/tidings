// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.AgentExecPolicies
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.EntityTypes
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberInfo
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.domain.InMemoryAgentStore
import com.kapp.tenon.domain.InMemoryMessageStore
import com.kapp.tenon.domain.NoUiTexts
import com.kapp.tenon.domain.RelayController
import com.kapp.tenon.domain.RelaySettings
import com.kapp.tenon.domain.RelayUiTexts
import com.kapp.tenon.platform.InMemoryAppPlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 团队执行分支的**行为**探针（团队执行方案 §4.3/§4.4/§5.5）：真编排器 + 真控制器
 * + 临时执行档文件，走真 `handlePrompt` 入口。
 *
 * 判据（每条都能区分对错，AGENTS §4）：
 * - `@组员` 落进团队分支：**未配置 ⇒ blocked 卡**（绝不回落 codex 兜底 —— 那是
 *   §2 要堵的洞，反向验证：抽掉 blocked 分支它就红）；
 * - **未授权 ⇒ blocked 卡**（P0-0.3 的收口；抽取授权检查它就红）;
 * - 控制面不可用 ⇒ blocked 卡说明原因（不假装派成功）;
 * - 配好执行档 + 控制面可用但链执行器缺失 ⇒ blocked 卡（§5.5：派不出去也要可见）;
 * - reviewer 直呼 = 纯审查单棒（不派 worker），控制面缺失同样 blocked（§5.1）;
 * - **invoke 桥入口同判据**：handlePromptAwait 未授权席位直呼 ⇒ blocked 回执 + 卡
 *   （§4.3「两个执行入口必须同源」）。
 *
 * 控制面在单测里不可用（planeProvider = { null }）⇒ 全部走到 blocked 卡是**确定性**
 * 的；链真正跑起来的端到端由真机验收（手机 `@coder 任务` → 链卡推流）覆盖。
 */
class TeamDispatchBehaviorTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        // Unconfined + 每用例独立 harness，无共享后台任务需要回收。
    }

    /** 只给「派单失败」一句非空正文（同 GroupMentionDispatchBehaviorTest 的教训）。 */
    private object Texts : RelayUiTexts by NoUiTexts {
        override suspend fun dispatchFailedNotice(title: String, agentName: String, reason: String): String =
            "dispatch failed: $title / $agentName / $reason"
    }

    /** 席位宿主 = **控制器自己的 nodeId**（卡片发布有成员表校验，成员必须包含 self）。 */
    private val sender = "member-node"

    /** 默认编制：一位可干活的人 + 一位异厂商审查。 */
    private val DEFAULT_SEAT_MEMBERS = listOf(
        TeamMemberInfo("m-coder", "coder", "编码"),
        TeamMemberInfo("m-reviewer", "reviewer", "审查"),
    )
    private val DEFAULT_EXEC_MEMBERS = listOf(
        TeamExecutionMemberSpec("m-coder", TeamExecutionKinds.WORKER, "codex"),
        TeamExecutionMemberSpec("m-reviewer", TeamExecutionKinds.REVIEWER, "claude"),
    )

    private fun teamSeat(
        hostNodeId: String,
        teamName: String? = "devteam",
        members: List<TeamMemberInfo> = DEFAULT_SEAT_MEMBERS,
    ) = VirtualAgentMember(
        agentId = "dsh-lead",
        hostNodeId = hostNodeId,
        name = "lead-seat",
        entityType = EntityTypes.TEAM,
        teamName = teamName,
        teamMembers = members,
        defaultWsId = "ws-1",
    )

    private fun group(seat: VirtualAgentMember, hostNodeId: String) = GroupMeta(
        groupId = "g-team", name = "团队群",
        members = listOf(sender, hostNodeId, "host-other"), rev = 2,
        updatedBy = hostNodeId, updatedTs = 1,
        ownerId = hostNodeId, adminIds = emptyList(),
        virtualAgents = listOf(seat),
    )

    /** harness：控制器注入该群的元数据（本机 = 席位宿主），返回 (controller, orchestrator, tmpDir)。 */
    private fun harness(
        withProfile: Boolean,
        policyOf: ((String) -> String?)? = null,
        seatMembers: List<TeamMemberInfo> = DEFAULT_SEAT_MEMBERS,
        execMembers: List<TeamExecutionMemberSpec> = DEFAULT_EXEC_MEMBERS,
        /** 在位底座清单：null = 没有控制面（既有用例的口径）。 */
        available: Set<String>? = null,
        /** 主理人的审核面裁决（注入路径 = 生产 `teamPlannerChain`）。 */
        planner: (suspend (PlannerChainInput) -> OrchestrationSuggestion?)? = null,
    ): Triple<RelayController, DesktopWorkerOrchestrator, File> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val base = RelaySettings.firstRun().copy(
            udpPort = 0, tcpPort = 0,
            persistMessages = false, persistMessagesDecided = true,
        )
        val meta = group(teamSeat(base.nodeId, members = seatMembers), base.nodeId)
        val controller = RelayController(
            platform = InMemoryAppPlatform(),
            store = SeededSettingsStore(base, listOf(meta)),
            messageStore = InMemoryMessageStore(),
            agentStore = InMemoryAgentStore(),
            scope = scope,
        )
        controller.uiTexts = Texts
        controller.invokeExecPolicyOf = policyOf
        val tmp = Files.createTempDirectory("team-exec-test").toFile()
        val store = TeamExecutionStore(File(tmp, "team-execution.json"))
        if (withProfile) {
            val verdict = store.save(
                TeamExecutionProfile(
                    dirId = "ws-1", leaderAgentId = "dsh-lead",
                    updatedAt = 0,
                    members = execMembers,
                ),
            )
            assertTrue(verdict is TeamExecutionSaveVerdict.Saved, "前置：执行档必须能落盘")
        }
        val orch = DesktopWorkerOrchestrator(
            planeProvider = { null },
            controller = controller,
            scope = scope,
            teamExecutionStore = store,
            // D16：执行档键的 dirId 一半 —— 测试档案都落在 dir-1/ws-1 这条指针上。
            groupDirIdOf = { "ws-1" },
            // 点将需要候选池：没有它 `decideChainShape` 直接回 null（= 没人可点），
            // 「主理人换人」这条路径在单测里就永远走不到。
            availableDriversHint = { available },
            teamPlannerChain = planner,
        )
        return Triple(controller, orch, tmp)
    }

    private fun convId(): String = ConvId.group("g-team")

    /** 预置群元数据的设置存储（注入路径 = 生产 load()，不绕任何内部口）。 */
    private class SeededSettingsStore(
        private val base: RelaySettings,
        private val groups: List<GroupMeta>,
    ) : com.kapp.tenon.domain.SettingsStore {
        @Volatile private var current: RelaySettings? = base.copy(groups = groups)

        override fun load(): RelaySettings? = current
        override fun save(settings: RelaySettings) {
            current = settings
        }
    }

    /** 走真 handlePrompt（member token 已由路由层从 @ 文本剥进 mentionedAgentKey）。 */
    private fun dispatch(orch: DesktopWorkerOrchestrator, mentionedAgentKey: String?) = runBlocking {
        orch.handlePrompt(
            convId = convId(),
            senderPeerId = sender,
            prompt = "fix the login timeout",
            replyToId = null,
            wsId = null,
            mentionedAgentKey = mentionedAgentKey,
        )
    }

    /** 被拒的团队派单必须留下一条**可批准的申请**（键 = 授权判据用的 m-* agentId）。 */
    @Test
    fun deniedTeamDispatchRecordsAnApprovalRequest() = runBlocking {
        // withProfile=true：NotConfigured（缺配置）是另一条拒绝路，不产生审批申请 ——
        // 批准救不了没配置。审批回流只挂在**授权拒绝**（MemberOnly）上。
        val (controller, orch, _) = harness(withProfile = true, policyOf = { null })
        val recorded = mutableListOf<Triple<String, String?, String>>()
        controller.invokeRecordGrantRequest = { nodeId, agentKey, reason ->
            recorded += Triple(nodeId, agentKey, reason)
        }
        dispatch(orch, "coder") // @coder → 组员 token → 团队分支 → 授权拒绝（MemberOnly）
        assertTrue(
            recorded.isNotEmpty(),
            "被拒的团队派单必须记一条授权申请（没有它，m-* 编制行永远打不开）",
        )
        val (nodeId, agentKey, reason) = recorded.single()
        assertEquals(sender, nodeId, "申请人是发起派单的手机节点")
        assertEquals("m-coder", agentKey, "键必须与授权判据同一把（target.member.agentId）")
        assertTrue("team dispatch" in reason, "原因要能看懂是哪条派单")
    }

    /** 席位直呼被拒同样要回流（与 1:1 invoke 的被拒口径一致）。 */
    @Test
    fun deniedSeatDispatchRecordsAnApprovalRequest() = runBlocking {
        val (controller, orch, _) = harness(withProfile = false, policyOf = { null })
        val recorded = mutableListOf<Triple<String, String?, String>>()
        controller.invokeRecordGrantRequest = { nodeId, agentKey, reason ->
            recorded += Triple(nodeId, agentKey, reason)
        }
        dispatch(orch, "lead-seat") // @领队席位 → deniedSeatDispatch 分支
        assertTrue(recorded.isNotEmpty(), "席位直呼被拒也要记申请")
        assertEquals("dsh-lead", recorded.single().second, "键 = 席位 agentId")
    }

    private fun blockedCards(controller: RelayController) =
        controller.state.value.messages[convId()].orEmpty().filter { it.card != null }

    private fun debugRows(controller: RelayController): String =
        controller.state.value.messages[convId()].orEmpty()
            .joinToString(" || ") { "card=" + (it.card != null) + " notice=" + it.notice + " text=" + it.text.take(40) }

    private fun blockedCard(controller: RelayController) =
        blockedCards(controller).singleOrNull()

    @Test
    fun aMemberMentionWithoutExecutionProfileYieldsABlockedCardNotACodexFallback() = runBlocking {
        val (controller, orch, tmp) = harness(withProfile = false)
        try {
            dispatch(orch, mentionedAgentKey = "coder")
            val card = assertNotNull(
                blockedCard(controller),
                "未配置执行档的 @组员 必须出 blocked 卡 —— 静默回落 codex 单棒是 §2 要堵的洞",
            )
            assertTrue(
                card.toString().contains("Team dispatch blocked"),
                "卡面 banner 要说清是团队派单被阻（实际：$card；rows=${debugRows(controller)}）",
            )
            assertTrue(
                card.toString().contains("not configured"),
                "卡面要写明原因（未配置）：$card",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /** 反向探针：抽掉 NotConfigured 分支 ⇒ 同一输入不会出卡（守卫的是分支本身）。 */
    @Test
    fun removingTheNotConfiguredBranchWouldSilenceTheBlockCard() {
        val code = com.kapp.tenon.testsupport.GuardSourceScan.stripComments(
            com.kapp.tenon.testsupport.GuardSourceScan.read(
                "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt",
            ),
        )
        assertTrue(
            code.contains("is TeamExecutionResolver.Target.NotConfigured ->"),
            "团队分支的 NotConfigured 分支不许被抽掉 —— 抽掉 = 未配置静默回落 codex 兜底",
        )
    }

    @Test
    fun anUnauthorizedMemberMentionIsBlockedVisibly() = runBlocking {
        val (controller, orch, tmp) = harness(withProfile = true)
        try {
            // sender 不是群主/管理员、不在台账（grantAllows 未装配）⇒ MemberOnly ⇒ 拒。
            dispatch(orch, mentionedAgentKey = "coder")
            val card = assertNotNull(blockedCard(controller), "未授权必须出 blocked 卡")
            assertTrue(
                card.toString().contains("not authorized"),
                "卡面要写明是授权拒绝：$card",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /**
     * **整队入口的闸看「队里有没有人能干活」，不是锚点本人**（点将之后这两件事分开了）。
     *
     * 这里验的是反向：两位可干活的人都被设成「仅自己可用」时仍必须拒 —— 放宽成
     * 「只要有一个人被授权」不能变成「谁都不需要授权」。审批申请按**锚点**记：
     * 批了它，没有点将建议时的默认落点就能跑。
     */
    @Test
    fun aTeamEntryIsBlockedOnlyWhenNoWorkerMayRun() = runBlocking {
        val (controller, orch, tmp) = harness(
            withProfile = true,
            policyOf = { AgentExecPolicies.LIST },
            seatMembers = listOf(
                TeamMemberInfo("m-coder", "coder", "编码"),
                TeamMemberInfo("m-backend", "backend", "后端"),
                TeamMemberInfo("m-reviewer", "reviewer", "审查"),
            ),
            execMembers = listOf(
                TeamExecutionMemberSpec("m-coder", TeamExecutionKinds.WORKER, "codex"),
                TeamExecutionMemberSpec("m-backend", TeamExecutionKinds.WORKER, "claude"),
                TeamExecutionMemberSpec("m-reviewer", TeamExecutionKinds.REVIEWER, "dsh"),
            ),
        )
        val recorded = mutableListOf<Pair<String, String?>>()
        controller.invokeRecordGrantRequest = { nodeId, agentKey, _ -> recorded += nodeId to agentKey }
        try {
            dispatch(orch, mentionedAgentKey = "devteam")
            val card = assertNotNull(blockedCard(controller), "两位可干活的人都没授权时必须出 blocked 卡")
            assertTrue(
                card.toString().contains("not authorized"),
                "卡面要写明是授权拒绝：$card ；rows=${debugRows(controller)}",
            )
            assertEquals(
                "m-coder",
                recorded.single().second,
                "审批按锚点记：批了它，没有点将建议时的默认落点就能跑",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /**
     * **点将的正例**：锚点（默认落点）只对自己开放，而主理人点了另一位可干活的人 ⇒
     * 整队入口**不许**被锚点一个人挡死。
     *
     * 这是「至少一位 worker 可被调起」那档的真实路径：没有候选池就没有点将（`decideChainShape`
     * 直接回 null），所以用例必须同时给在位清单与裁决 —— 只有这两样齐了，
     * 「换个授权的人顶上」才在代码里真的发生。
     *
     * 断言的是**没有**授权拒绝：单测没有链执行器 ⇒ 后面仍会出卡（渠道/目录那条原因），
     * 但卡面不该再出现 `not authorized` —— 说明授权闸放的是被点中的那个人。
     */
    @Test
    fun anOwnerOnlyAnchorDoesNotBlockTheTeamWhenTheLeadPicksSomeoneElse() = runBlocking {
        val (controller, orch, tmp) = harness(
            withProfile = true,
            // 锚点（m-coder）仅自己可用；被点中的 m-backend 与审查棒对群成员开放。
            policyOf = { id ->
                if (id == "m-coder") AgentExecPolicies.LIST else AgentExecPolicies.ANY_MEMBER
            },
            seatMembers = TWO_WORKER_SEAT_MEMBERS,
            execMembers = TWO_WORKER_EXEC_MEMBERS,
            available = setOf("codex", "claude", "dsh"),
            planner = { pickWorker("m-backend") },
        )
        val recorded = mutableListOf<String?>()
        controller.invokeRecordGrantRequest = { _, agentKey, _ -> recorded += agentKey }
        try {
            dispatch(orch, mentionedAgentKey = "devteam")
            val card = assertNotNull(blockedCard(controller), "单测没有链执行器 ⇒ 仍会出卡")
            assertTrue(
                !card.toString().contains("not authorized"),
                "锚点没授权不该挡住整队：主理人点的是 m-backend，它已授权。$card ；rows=${debugRows(controller)}",
            )
            assertTrue(
                recorded.isEmpty(),
                "既然放行了，就不该记「锚点的」授权申请：$recorded",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /**
     * **点将的反例**：主理人点到的人自己没授权 ⇒ 仍要拒，并且**点名他**（不是锚点）。
     *
     * 授权跟着编译结果走，不是跟着 `@` 锚点走：否则「允许 A 用 codex」会顺带允许
     * 「A 拉的队让只对自己开放的 B 跑」。审批申请也按被拒的那个人记 —— 批了他，
     * 下一次同样的点将就能跑。
     */
    @Test
    fun pickingAMemberWhoMayNotRunBlocksAndNamesThatMember() = runBlocking {
        val (controller, orch, tmp) = harness(
            withProfile = true,
            policyOf = { id ->
                if (id == "m-backend") AgentExecPolicies.LIST else AgentExecPolicies.ANY_MEMBER
            },
            seatMembers = TWO_WORKER_SEAT_MEMBERS,
            execMembers = TWO_WORKER_EXEC_MEMBERS,
            available = setOf("codex", "claude", "dsh"),
            planner = { pickWorker("m-backend") },
        )
        val recorded = mutableListOf<String?>()
        controller.invokeRecordGrantRequest = { _, agentKey, _ -> recorded += agentKey }
        try {
            dispatch(orch, mentionedAgentKey = "devteam")
            val card = assertNotNull(blockedCard(controller), "被点中的人没授权必须出 blocked 卡")
            assertTrue(
                card.toString().contains("not authorized"),
                "卡面要写明是授权拒绝：$card ；rows=${debugRows(controller)}",
            )
            assertTrue(
                card.toString().contains("delegated member"),
                "被拒的不是锚点时必须点名是谁（否则用户以为是锚点被拒）：$card",
            )
            assertEquals(
                "m-backend",
                recorded.singleOrNull(),
                "审批按**被点中的人**记：批了锚点救不了这一单",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /** 两位可干活的人 + 一位审查：点将的最小编制（审查棒换厂商，过保存期厂商门）。 */
    private val TWO_WORKER_SEAT_MEMBERS = listOf(
        TeamMemberInfo("m-coder", "coder", "编码"),
        TeamMemberInfo("m-backend", "backend", "后端"),
        TeamMemberInfo("m-reviewer", "reviewer", "审查"),
    )
    private val TWO_WORKER_EXEC_MEMBERS = listOf(
        TeamExecutionMemberSpec("m-coder", TeamExecutionKinds.WORKER, "codex"),
        TeamExecutionMemberSpec("m-backend", TeamExecutionKinds.WORKER, "claude"),
        TeamExecutionMemberSpec("m-reviewer", TeamExecutionKinds.REVIEWER, "dsh"),
    )

    /** 主理人的裁决：点 `workerAgentId` 下场，再让审查棒验一遍。 */
    private fun pickWorker(workerAgentId: String): OrchestrationSuggestion = OrchestrationSuggestion(
        stages = listOf(
            StageIntent(workerAgentId, asReviewer = false, instruction = "Fix the login timeout."),
            StageIntent("m-reviewer", asReviewer = true, instruction = "Verify the fix. End with VERDICT: PASS."),
        ),
        rationale = "the picked member owns this area",
    )

    /**
     * **逐成员可见性（mulica 式）**：领队把整队设为「对外开放」，但真正干活的 worker 设为
     * 「仅自己可用」时，外部成员 `@团队` 必须被授权闸拦下 —— 闸按**执行成员**判，不是按领队。
     * 若闸按领队（旧逻辑），领队开放 ⇒ 外部直接落到「控制面不可用」卡，授权闸形同虚设。
     */
    @Test
    fun aTeamWithOpenLeaderButOwnerOnlyWorkerBlocksExternalCallerOnTheWorker() = runBlocking {
        val (controller, orch, tmp) = harness(
            withProfile = true,
            policyOf = { id ->
                if (id == "dsh-lead") AgentExecPolicies.ANY_MEMBER else AgentExecPolicies.LIST
            },
        )
        try {
            dispatch(orch, mentionedAgentKey = "devteam") // 外部成员 @ 团队名
            val card = assertNotNull(blockedCard(controller), "外部 @ 团队（worker 仅自己可用）必须出 blocked 卡")
            assertTrue(
                card.toString().contains("not authorized"),
                "卡面要写明是授权拒绝（闸按 worker 生效）： $card ；rows=${debugRows(controller)}",
            )
            assertTrue(
                !card.toString().contains("control plane"),
                "不能是『控制面不可用』—— 那是已经过了授权才会到的地方：$card",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /**
     * **审查棒也要过授权闸**（与 worker 同一份判据）。
     *
     * 审查棒同样会在宿主上跑命令（读工作区、跑测试、给 VERDICT），把它排除在授权之外，
     * 等于「允许小张用 codex」顺带允许了「小张拉的队可以让只对自己开放的老王跑测试」。
     *
     * 反向探针：把 `firstDenied(compiled.participants)` 换回锚点单判，这张卡就不会出现，
     * 而是落到「控制面不可用」——那说明授权闸放行了整条链。
     */
    @Test
    fun aReviewerWhosePolicyIsOwnerOnlyBlocksTheWholeTeamDispatch() = runBlocking {
        val (controller, orch, tmp) = harness(
            withProfile = true,
            policyOf = { id ->
                if (id == "m-coder") AgentExecPolicies.ANY_MEMBER else AgentExecPolicies.LIST
            },
        )
        try {
            dispatch(orch, mentionedAgentKey = "devteam") // 锚点（worker）开放，审查棒仅自己可用
            val card = assertNotNull(blockedCard(controller), "审查棒没授权时整条链不许开跑")
            assertTrue(
                card.toString().contains("not authorized"),
                "卡面要写明是授权拒绝：$card ；rows=${debugRows(controller)}",
            )
            assertTrue(
                card.toString().contains("delegated member"),
                "被拒的不是锚点时必须点名是谁：$card",
            )
            assertTrue(
                !card.toString().contains("control plane"),
                "不能落成『控制面不可用』—— 那说明授权闸把整条链放过去了：$card",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /**
     * 反例：领队与 worker 都开放时，外部 `@团队` **通过授权闸**，落到一个非「授权拒绝」的卡
     * （单测里控制面为 null ⇒ 执行档编译期报「driver 不在位」）。证明上一条卡住的是**授权闸**，
     * 而不是控制面缺失等下游原因。
     */
    @Test
    fun aTeamWithOpenLeaderAndOpenWorkerPassesTheAuthGate() = runBlocking {        val (controller, orch, tmp) = harness(
            withProfile = true,
            policyOf = { AgentExecPolicies.ANY_MEMBER },
        )
        try {
            dispatch(orch, mentionedAgentKey = "devteam")
            val card = assertNotNull(blockedCard(controller), "开放团队外部 @ 团队必须出卡")
            assertTrue(
                !card.toString().contains("not authorized"),
                "授权应通过（不含 not authorized）： $card ；rows=${debugRows(controller)}",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /** 反向探针（P0-0.3）：抽掉 deniedSeatDispatch 的授权调用 ⇒ 授权拒绝卡消失。 */
    @Test
    fun removingTheAuthorizationCallWouldSilenceTheDenial() {
        val code = com.kapp.tenon.testsupport.GuardSourceScan.stripComments(
            com.kapp.tenon.testsupport.GuardSourceScan.read(
                "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt",
            ),
        )
        assertTrue(
            code.contains("AgentExecAuthorizer.authorize(") &&
                code.contains("permitsExecution()"),
            "团队分支与席位直呼的授权调用不许被抽掉 —— 那是文本派单授权收口的本体",
        )
    }

    @Test
    fun aConfiguredTeamWithoutControlPlaneIsBlockedWithAReason() = runBlocking {
        val (controller, orch, tmp) = harness(withProfile = true)
        try {
            // 机主本人发 ⇒ 授权通过（AgentHost 档）；控制面不可用 ⇒ blocked（编译期在位检查）。
            val selfId = controller.state.value.selfNodeId
            orch.handlePrompt(
                convId = convId(),
                senderPeerId = selfId,
                prompt = "fix the login timeout",
                replyToId = null,
                wsId = null,
                mentionedAgentKey = "coder",
            )
            val card = assertNotNull(blockedCard(controller), "控制面不可用必须出 blocked 卡，不假装派成功")
            // 卡面来源是门（§5.2 代码门是权威）：reason = 门的原话（"不在位；当前可用：…"）。
            // 断言不锁措辞，锁「blocked 卡写明了缺谁/当前有什么」这件事。
            val cardText = card.toString()
            assertTrue(
                "blocked" in cardText && ("不在位" in cardText || "not installed" in cardText || "unavailable" in cardText),
                "卡面要写明原因（驱动不在位/控制面不可用）：$card",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /**
     * **领队 token 的授权收口**（P0-0.3 的行为变更本体）：未授权成员 `@leader` 现在也
     * 出 blocked 卡 —— 与 @组员 同一份判据（§10.1 推荐的同期修；否则同一群里
     * "@领队可跑、@组员不可跑" 语义不一致）。
     */
    @Test
    fun unauthorizedLeaderMentionIsNowDeniedToo() = runBlocking {
        val (controller, orch, tmp) = harness(withProfile = true)
        try {
            dispatch(orch, mentionedAgentKey = "lead-seat")
            val card = assertNotNull(blockedCard(controller), "未授权的领队直呼必须出 blocked 卡")
            assertTrue(card.toString().contains("not authorized"), "卡面写明授权拒绝：$card")
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /** 领队 token + 授权发送者（机主本人）：既有单 agent 路径逐字不变（不走团队分支）。 */
    @Test
    fun leaderMentionByTheHostStillTakesTheLegacyPath() = runBlocking {
        val (controller, orch, tmp) = harness(withProfile = true)
        try {
            val selfId = controller.state.value.selfNodeId
            orch.handlePrompt(
                convId = convId(),
                senderPeerId = selfId,
                prompt = "fix the login timeout",
                replyToId = null,
                wsId = null,
                mentionedAgentKey = "lead-seat",
            )
            val cards = blockedCards(controller)
            assertTrue(
                cards.all { "Team dispatch blocked" !in it.toString() },
                "机主 @ 领队走既有单 agent 路径，不出团队 blocked 卡：$cards",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /**
     * reviewer 直呼（§5.1）：编译为**纯审查单棒**（不派 worker），运行期控制面不可用
     * ⇒ blocked 卡 —— 钉住 [TeamPlanCompiler.Compiled.Single] 的运行时出口：
     * `available == null` 时必须可见地拒绝，不假装派成功。
     */
    @Test
    fun aReviewerMentionByTheOwnerCompilesAReviewOnlySingleAndBlocksOnMissingControlPlane() = runBlocking {
        val (controller, orch, tmp) = harness(withProfile = true)
        try {
            val selfId = controller.state.value.selfNodeId
            orch.handlePrompt(
                convId = convId(),
                senderPeerId = selfId,
                prompt = "audit the login timeout fix",
                replyToId = null,
                wsId = null,
                mentionedAgentKey = "reviewer",
            )
            val card = assertNotNull(blockedCard(controller), "审查直呼 + 无控制面必须 blocked，不假装派成功")
            assertTrue(
                card.toString().contains("unavailable") || card.toString().contains("not installed"),
                "卡面写明原因（控制面/驱动不在位）：$card",
            )
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }

    /**
     * **invoke 桥入口同判据**（§4.3「两个执行入口必须同源」）：`handlePromptAwait`
     * 的 agentKey 点名本机托管席位时同样过授权 —— 未授权 ⇒ TicketOutcome(state=blocked)
     * 且卡广播出去。领队直呼在文本路被收口后，invoke 路不同期收口就是同一条命令两条结果。
     */
    @Test
    fun theInvokeBridgeDeniesAnUnauthorizedSeatMentionWithTheSameVerdict() = runBlocking {
        val (controller, orch, tmp) = harness(withProfile = true)
        try {
            val outcome = orch.handlePromptAwait(
                convId = convId(),
                senderPeerId = sender, // 未授权成员
                prompt = "fix the login timeout",
                replyToId = null,
                agentKey = "lead-seat",
            )
            assertEquals("blocked", outcome.state, "invoke 桥的授权拒绝必须以 blocked 终态回执")
            assertTrue(
                outcome.error?.contains("not authorized") == true,
                "回执写明授权拒绝：${outcome.error}",
            )
            val card = assertNotNull(blockedCard(controller), "拒绝必须出卡（全群可见），不许只有回执")
            assertTrue(card.toString().contains("not authorized"), "卡面同因：$card")
        } finally {
            tmp.deleteRecursively()
            controller.stop()
        }
    }
}
