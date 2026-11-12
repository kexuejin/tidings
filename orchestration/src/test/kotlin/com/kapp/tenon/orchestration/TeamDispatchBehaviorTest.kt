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

    private fun teamSeat(hostNodeId: String, teamName: String? = "devteam") = VirtualAgentMember(
        agentId = "dsh-lead",
        hostNodeId = hostNodeId,
        name = "lead-seat",
        entityType = EntityTypes.TEAM,
        teamName = teamName,
        teamMembers = listOf(
            TeamMemberInfo("m-coder", "coder", "编码"),
            TeamMemberInfo("m-reviewer", "reviewer", "审查"),
        ),
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
    ): Triple<RelayController, DesktopWorkerOrchestrator, File> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val base = RelaySettings.firstRun().copy(
            udpPort = 0, tcpPort = 0,
            persistMessages = false, persistMessagesDecided = true,
        )
        val meta = group(teamSeat(base.nodeId), base.nodeId)
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
                    groupId = "g-team", dirId = "ws-1", leaderAgentId = "dsh-lead",
                    updatedAt = 0,
                    members = listOf(
                        TeamExecutionMemberSpec("m-coder", TeamExecutionKinds.WORKER, "codex"),
                        TeamExecutionMemberSpec("m-reviewer", TeamExecutionKinds.REVIEWER, "claude"),
                    ),
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
     * 反例：领队与 worker 都开放时，外部 `@团队` **通过授权闸**，落到一个非「授权拒绝」的卡
     * （单测里控制面为 null ⇒ 执行档编译期报「driver 不在位」）。证明上一条卡住的是**授权闸**，
     * 而不是控制面缺失等下游原因。
     */
    @Test
    fun aTeamWithOpenLeaderAndOpenWorkerPassesTheAuthGate() = runBlocking {
        val (controller, orch, tmp) = harness(
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
