// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.desktop.testutil.RepoSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guard tests verifying DesktopWorkerOrchestrator wiring and DSH core alignment.
 */
class DesktopWorkerOrchestratorTest {

    @Test
    fun extensionWiresWorkerOrchestratorToOnAgentPrompt() {
        // 装配层已按职责拆成四个文件（ARP-5）：判据问的是「编排层有没有这条接线」⇒ 读整包。
        // 顺带摆脱 `File("../orchestration/…")` 的 cwd 依赖 —— RepoSource 从测试类自己的
        // codeSource 起算仓库根，Gradle / IDE / CI 下一致。
        val text = RepoSource.readDir("orchestration/src/main/kotlin/com/kapp/tenon/orchestration")

        assertTrue(
            text.contains("DesktopWorkerOrchestrator("),
            "DesktopWorkerOrchestrator not instantiated in DesktopExtension",
        )
        assertTrue(
            text.contains("workerOrchestrator.handlePrompt("),
            "onAgentPrompt not wired to workerOrchestrator.handlePrompt",
        )
    }

    @Test
    fun abortSignalsRecognizedAccurately() {
        // Source scan test for signal coverage and direct method check
        val source = listOf(
            File("../orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
            File("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertTrue(text.contains("\"stop\""), "Missing stop signal keyword")
        assertTrue(text.contains("\"abort\""), "Missing abort signal keyword")
        assertTrue(text.contains("\"cancel\""), "Missing cancel signal keyword")
        assertTrue(text.contains("active.job.cancel"), "Must cancel active job upon abort signal")
        assertTrue(text.contains("Resumed: processing follow-up"), "Must resume blocked ticket upon follow-up")
    }

    @Test
    fun orchestratorUsesExistingBroadcastChainCard() {
        val source = listOf(
            File("../orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
            File("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertTrue(
            text.contains("controller.broadcastChainCard("),
            "Worker card must be broadcast via existing broadcastChainCard channel",
        )
        assertTrue(
            text.contains("StageChain.parseStages(prompt)"),
            "Orchestrator must differentiate single-worker and multi-role chain via parseStages",
        )
    }

    /**
     * **两条路同一判据，且派不出去不许假装受理。**
     *
     * 手机 `@agent` 派单走 `handlePromptAwait`（invoke 路径），是真机上唯一的派单入口。
     * 它曾经不看 `dispatchMultiRoleChain` 的返回值就 `return "accepted"`：
     * 宿主没装链执行器（`chainDispatcher == null`）或群没绑仓时，链一棒都不会跑、
     * 群里连卡都没有，调用方却拿到「已受理」—— 用户等一张永不到来的卡。
     *
     * 守三件事：解析多棒、真的走链执行器、派不出去时回落单棒（不许静默吞单）。
     */
    @Test
    fun theInvokePathAlsoParsesMultiRoleStages() {
        val source = listOf(
            File("../orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
            File("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        val start = text.indexOf("suspend fun handlePromptAwait")
        val end = text.indexOf("private fun dispatchMultiRoleChain")
        assertTrue(start >= 0 && end > start, "handlePromptAwait / dispatchMultiRoleChain 找不到")
        val body = text.substring(start, end)

        assertTrue(
            body.contains("StageChain.parseStages"),
            "handlePromptAwait 必须解析多棒（真机唯一入口，缺了则多 agent 协作在手机上不可达）",
        )
        assertTrue(
            body.contains("dispatchMultiRoleChain"),
            "解析出多棒后必须真的走链执行器",
        )
        assertTrue(
            body.contains("dispatchSingleWorker"),
            "没有链执行器/没绑仓时仍要回落到单棒，不许把派单静默吞掉",
        )
    }

    @Test
    fun prototypeAndHealCapabilitiesRecognized() {
        val source = listOf(
            File("../orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
            File("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertTrue(text.contains("isPrototypeIntent"), "Must support prototype intent detection")
        assertTrue(text.contains("followUpIfActive"), "Must support follow-up activation")
        assertTrue(text.contains("approvePrototype"), "Must support prototype approval")
        assertTrue(text.contains("maxHealRounds"), "Must support maxHealRounds self-healing loop")
    }

    /**
     * 派单落点**只能**来自群绑定的工作区。
     *
     * 真机事故（2026-11-01）：同一个群连发两条派单，一条落进 `~/e2e-weather-app`、
     * 一条落进 `~/weather-relay`。当时 `resolveWorkspacePath` 的第二段兜底是
     * 「本机第一个工作区」，而 `agentWorkspaces` 是 Map —— 顺序随哈希，于是同一份
     * 配置给出两个答案，且本端卡片、进度、补丁卡全都正常（错的是"改哪个仓"）。
     * 这条守卫锚的正是那个兜底：它不在，只剩"绑定 / 退单"两种结局。
     */
    @Test
    fun dispatchTargetComesOnlyFromGroupBinding() {
        val source = listOf(
            File("../orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
            File("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertTrue(
            text.contains("workspaceOfGroup("),
            "派单落点必须由群绑定的工作区解析（RelayUiState.workspaceOfGroup）",
        )
        assertFalse(
            text.contains("firstOrNull { it.peerId == self }"),
            "不许再用「本机第一个工作区」兜底：Map 顺序不定，同一群会落到不同仓",
        )
    }

    /**
     * 工单卡必须挂**真实驱动键**。
     *
     * 真机事故：广播卡写死 `agentId="tidings-worker"`，用户点卡上的 Approve，
     * 动作帧按卡片的 agentId 过 `AgentExecAuthorizer` —— 假名过不了授权，
     * 卡片永远停在「已提交，等待宿主确认」，群里只回一条
     * `agent_action_denied_notice`（「没有权限驱动 [tidings-worker]」）。
     * 卡面的 `currentRole` 同理：写死 "Worker" 时用户看不出这张卡是谁干的。
     */
    @Test
    fun ticketCardsCarryTheRealAgentKey() {
        val source = listOf(
            File("../orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
            File("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertFalse(
            text.contains("\"tidings-worker\""),
            "卡片广播不许写死 tidings-worker：假名过不了 AgentExecAuthorizer，" +
                "卡上的 Approve 动作会被「没有权限驱动」拒绝",
        )
        assertTrue(
            text.contains("val agentKey: String") && text.contains("agentKey = defaultAgent"),
            "ActiveTicket 必须持有随帧的 agentKey，并传入广播身份",
        )
        assertFalse(
            text.contains("currentRole = \"Worker\""),
            "卡面角色不许写死 Worker：currentRole 应来自 roleNameOf(agentKey)",
        )
    }

    /**
     * prototype 待选态的**方向判据**（缺陷 B）：无 @ 文本不许再被无判别吞成 refine。
     *
     * 真机事故：待选卡在屏时发 "run npm test"，codex 收到的是
     * "Refine the prototype.html… user feedback: run npm test" —— 全新任务被当成
     * 微调，白跑一轮还顶掉了工单。"make it dark" 与 "run npm test" 文本特征上
     * 无法区分（都是短祈使句），所以正确的产品行为是发方向确认卡，不猜。
     */
    @Test
    fun prototypeStageAsksDirectionInsteadOfGuessing() {
        val source = listOf(
            File("../orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
            File("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertTrue(
            text.contains("askPrototypeDirection(") && text.contains("confirmPrototypeInput("),
            "prototype 待选态必须走方向确认卡（askPrototypeDirection/confirmPrototypeInput）",
        )
        assertTrue(
            text.contains("pendingPrototypeInputs"),
            "确认卡文本必须暂存：action 帧装不下长文本，点确认时取回执行",
        )
        assertTrue(
            text.contains("askPrototypeDirection(active, trimmed)"),
            "followUpIfActive 的 prototype 分支必须改道方向确认卡，不许直接 handler.invoke(trimmed)",
        )
    }

    /**
     * 派单必须经过**团队点将**（文档 §二 Proposed 态 / §3.5 卡片状态 1）。
     *
     * 此前派单无声地直接开跑：手机上第一张卡就是 working，看不出谁来干、分几步。
     * 现在派单先发 proposed 卡（roster 每棒挂承办人），倒计时自动开工；
     * 卡上的立即执行/取消由宿主接线（startByCard/cancelByCard）。
     */
    @Test
    fun dispatchShowsTeamRosterBeforeRunning() {
        val source = listOf(
            File("../orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
            File("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt"),
        ).firstOrNull { it.isFile }
        assertTrue(source != null, "DesktopWorkerOrchestrator.kt not found")
        val text = source.readText()

        assertTrue(
            text.contains("state = \"proposed\""),
            "派单必须先发 proposed 点将卡，不许无声直跑",
        )
        assertTrue(
            text.contains("assignees = plan.map { assignee }"),
            "点将卡的每一棒都要挂承办人（roster），不能只有棒名",
        )
        assertTrue(
            text.contains("startProposedTicket(ticket)"),
            "倒计时到点必须自动开工（极简注意力：点将不是审批门）",
        )
        assertTrue(
            // 不验可见性：下沉到 :orchestration 后这些入口要跨模块调用，internal 已改成 public。
            text.contains("fun startByCard(") && text.contains("fun cancelByCard("),
            "卡上的立即执行/取消必须有宿主落地（startByCard/cancelByCard）",
        )
        assertTrue(
            text.contains("Superseded by a new message"),
            "点将期来的正文按新派单处理：旧工单要先撤销，不许两张卡并行抢沙箱",
        )
    }

    /** 点将卡的两个动作 id 必须在宿主侧接线（没有落点的按钮比没有更糟）。 */
    @Test
    fun proposedCardActionsAreWiredInExtension() {
        // 装配层已按职责拆成四个文件（ARP-5）：判据问的是「编排层有没有这条接线」⇒ 读整包。
        // 顺带摆脱 `File("../orchestration/…")` 的 cwd 依赖 —— RepoSource 从测试类自己的
        // codeSource 起算仓库根，Gradle / IDE / CI 下一致。
        val text = RepoSource.readDir("orchestration/src/main/kotlin/com/kapp/tenon/orchestration")

        assertTrue(
            text.contains("\"start-task\"") && text.contains("startByCard(cardId)"),
            "start-task 动作必须接到 workerOrchestrator.startByCard",
        )
        assertTrue(
            text.contains("\"cancel-task\"") && text.contains("cancelByCard(cardId)"),
            "cancel-task 动作必须接到 workerOrchestrator.cancelByCard",
        )
    }

    /**
     * 方向确认卡的「派为新任务」腿必须继承原工单的显式工作区指针。
     *
     * 真机现场（2026-11-10 证据 §6 O7）：1:1 里原型待选期的追问被裁决为
     * dispatch-pending 后，新任务腿丢了 wsId —— 1:1 没有 groupId 可推导，
     * 多工作区宿主的「单工作区自然推导」又不唯一，`resolveWorkspacePath`
     * 返回 null，必退单「no git workspace bound to group」，而用户的药丸
     * 明明选着工作区。修复 = ActiveTicket 携带 wsId 并在派单腿原样带回。
     */
    @Test
    fun askCardNewTaskLegKeepsTheExplicitWorkspace() {
        val source = RepoSource.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt")

        assertTrue(
            source.contains("val wsId: String? = null,"),
            "ActiveTicket 必须携带派单时的显式 wsId（否则 ask 卡新任务腿无从继承）",
        )
        assertTrue(
            source.contains("wsId = wsId,"),
            "ActiveTicket 构造点必须把 dispatchSingleWorker 的 wsId 存进工单",
        )
        assertTrue(
            source.contains("dispatchSingleWorker(active.convId, active.groupId, pending.text, active.agentKey, active.wsId)"),
            "confirmPrototypeInput 的派为新任务腿必须把 active.wsId 传下去（丢了必退单 no git workspace bound to group）",
        )
    }

    /**
     * 「无 patch」轮次的重试提示词不许命令「确保 npm test 通过」。
     *
     * 真机现场（2026-11-10 证据 §6 O6）：问答式任务没有补丁是正常终态，统一
     * 喊「fix all issues and ensure npm test passes」会把模型逼到编造无关改动
     * 去凑补丁（真机抓到 +33 −2 与原任务毫无关系的测试改动）。重试提示词必须
     * 按「无 patch 且无验收判负」分化，明确允许「只答不改」收尾。
     */
    @Test
    fun patchlessRetryDoesNotDemandTestFixes() {
        val source = RepoSource.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt")

        assertTrue(
            source.contains("Previous attempt ended without a patch"),
            "无 patch 轮次必须有分化的重试提示词（不许复用「确保测试通过」话术）",
        )
        assertTrue(
            source.contains("do NOT create or modify files to satisfy a patch requirement"),
            "无 patch 重试必须明确禁止为凑补丁而造文件（O6 的编造通道）",
        )
        assertFalse(
            source.contains("currentPrompt = \"Previous attempt failed:"),
            "重试提示词不许再是无条件单一代——无 patch 轮次必须走分化分支",
        )
    }

    /**
     * 零补丁的只读任务必须**收敛成答案**，不许挂 `blocked`。
     *
     * 真机现场（`@dsh-assistant git log -1 --oneline` 这种只读任务）：
     * 每轮 `nothing to harvest — patchBytes=0` ⇒ 被判「没干活」⇒ 催满三轮，卡停 60%
     * 不收敛，用户只能手动点「停止」。判据本体在 [PatchlessAnswer]（纯函数，有真值表），
     * 这条只钉**接线**：判据真被调用、且**在**那句「No patch produced by worker」之前，
     * 否则那一行永远轮不到它。
     */
    @Test
    fun patchlessTaskConvergesAsAnsweredInsteadOfBlocking() {
        val source = RepoSource.read("orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt")

        val convergeAt = source.indexOf("PatchlessAnswer.verdictOf(")
        val roundErrorAt = source.indexOf("val roundError = when {")
        assertTrue(convergeAt > 0, "自愈循环必须咨询 PatchlessAnswer —— 否则只读任务还是每轮判失败")
        assertTrue(
            roundErrorAt > convergeAt,
            "收敛判定必须在「No patch produced by worker」之前；" +
                "放在后面等于永远走不到（那条路就是本轮要修的病）",
        )
        assertEquals(
            1,
            Regex(Regex.escape("""state = "done",""")).findAll(source).count(),
            "答完的单只该有一处发 state=done 的卡（多一处就是两条终局路，判据分叉）",
        )
        assertTrue(
            source.contains("answeredNoChangesBanner("),
            "横幅必须经文案桥取（手机与桌面共用一套措辞），不许在编排层拼中文",
        )
    }
}
