// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.AgentSessionRegistry
import com.kapp.tenon.agent.ControlPlane
import com.kapp.tenon.agent.abortRunningSandboxes
import com.kapp.tenon.agent.latestPatchRefFor
import com.kapp.tenon.agent.latestPatchRefForCard
import com.kapp.tenon.agent.latestSessionForAgent
import com.kapp.tenon.agent.pluginAction
import com.kapp.tenon.agent.pluginForAgent
import com.kapp.tenon.domain.RelayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * **手机上点了卡片上的按钮之后，宿主该做什么** —— 全部分支都在这里。
 *
 * 一行 `controller.onHostCardAction = { … }` 原来含着 340 行的分派（合并补丁 / 停止 /
 * 本会话免审 / 立即执行 / 取消 / 批准原型 / 方向确认 / 打回重做 / 重试 / 追问 / 插件转发），
 * 与「控制面怎么挂上去」挤在同一个函数里。两者的故障面完全不同：
 * 挂载失败是「整个 agent 面起不来」，动作分派出错是「某一个按钮点了没反应、且卡面还
 * 挂着已处理」—— 后者只在真机点下去那一刻才暴露。
 *
 * 状态一律走取值器（[plane] / [tickets] / [sessions] / [exempts]）：它们都随控制面重挂
 * 而变，副本会让「重启后点的按钮落到旧对象上」。
 */
internal class HostCardActions(
    private val controller: RelayController,
    private val scope: CoroutineScope,
    /** 当前控制面（合并补丁要按会话反查 patch、插件转发要它、免审要它记的最近会话）。 */
    private val plane: () -> ControlPlane,
    /** 本机 nodeId：合并结果要落进台账，那条事件记录得说清是谁合流的。 */
    private val selfNodeId: String,
    private val tickets: () -> DesktopWorkerOrchestrator?,
    private val sessions: () -> AgentSessionRegistry?,
    private val exempts: () -> SessionExemptLedger?,
    private val delivery: ArtifactDelivery,
) {

    /** 卡片动作的唯一入口（签名与 `RelayController.onHostCardAction` 一致，装配处只转一手）。 */
    fun onHostCardAction(
        agentId: String,
        sessionId: String?,
        cardId: String,
        actionId: String,
        decidedBy: String,
    ) {
        // 审查合流（T-G1）：diff-review 卡的 [合并此补丁] 在宿主本机落地 ——
        // 按会话找回（工作区, patch 路径），git apply 两段式执行；
        // 结果落台账（merge-result 事件，会话详情可见）。不走插件转发：
        // 合流是宿主仓的写操作，不是插件的活。
        //
        // **必须留按卡片反查这条路**：动作帧（op=action）里只有 cardId，没有会话，
        // 上游那一跳传进来的 sessionId 恒为 null。从前判据写成 `sessionId != null`，
        // 于是合并分支在真机上永远进不去（实测卡面提示已处理，宿主仓零提交）。
        // 现在 null 就按卡片回台账里找会话。
        // L3 高危租约屏障（§九.2）：卡 payload 带 cmd 且词表判高危的动作，
        // 必须在签发租约（expiresAt = 签发 + TTL）内被机主点掉。过期才到的审批
        // fail-closed 拒绝并如实翻面 —— 「晚来的同意」不能变成放行。没有 expiresAt
        // 的高危卡同样拒绝（签发方没给租约 = 没有可放行的时限证据）。
        var highRiskBlocked = false
        runCatching {
            val cmdRow = controller.state.value.messages.values
                .asSequence()
                .flatten()
                .lastOrNull { row ->
                    (row.renderCard?.get("cardId") as? JsonPrimitive)?.contentOrNull == cardId
                }
            val cardJson = cmdRow?.renderCard
            val cmd = (cardJson?.get("cmd") as? JsonPrimitive)?.contentOrNull
            if (cmd != null && HighRiskCommands.classify(cmd) == HighRiskCommands.Verdict.HIGH_RISK) {
                val expiresAt = (cardJson?.get("expiresAt") as? JsonPrimitive)?.longOrNull
                // 租约口径：签发时刻 = expiresAt − TTL（卡上只带截止时刻这一个事实）。
                val verdict = if (expiresAt == null) {
                    LeaseBarrier.Verdict.EXPIRED
                } else {
                    LeaseBarrier.evaluate(
                        LeaseToken(
                            leaseId = cardId,
                            command = cmd,
                            issuedAtMs = expiresAt - LeaseBarrier.DEFAULT_TTL_MS,
                            ttlMs = LeaseBarrier.DEFAULT_TTL_MS,
                        ),
                        arrivedAtMs = System.currentTimeMillis(),
                    )
                }
                if (verdict != LeaseBarrier.Verdict.GRANTED) {
                    RelayLog.info("RELAY-INVOKE", "high-risk lease " + verdict + " (card " + cardId + ", cmd masked)")
                    delivery.flipStaleChainCard(cardId, agentId)
                    highRiskBlocked = true
                }
            }
        }
        if (highRiskBlocked) {
            // 闸住的动作不再进任何后续分支：没有可放行的租约，就一个分支都不给。
            return
        }
        sessions()?.recordCardAction(cardId, actionId, decidedBy)
        if (actionId == "merge-patch") {
            // **本机那一行卡片消息是唯一权威的会话来源。** 动作帧（op=action）不带会话，
            // 宿主重启后编排器登记簿又是空的 —— 只有落库的卡片行两头都在。
            // 它的 `peerId` 就是会话键本身（群 `group:<id>` / 1:1 对端 nodeId）：
            // 翻面卡直接拿它当投递目标（`broadcastBoardCard` 两种形状都认），
            // 别再经一手 `groupIdOf` —— 那对 1:1 恒为 null，正是「合并成功但卡面不翻面」
            // 与「重启后合不了」的两处共同成因。
            val actionRow = controller.state.value.messages.values
                .asSequence()
                .flatten()
                .lastOrNull { row ->
                    (row.renderCard?.get("cardId") as? JsonPrimitive)?.contentOrNull == cardId
                }
            // 四条判据，从「谁最清楚」排到「谁最兜底」：
            // 1. 编排器的登记簿 —— 发卡的那一刻就知道 patch 在哪（工单卡只此一条有效）；
            // 2. 动作帧带的会话（插件转发路径，上游常常给 null）；
            // 3. 控制面按会话台账反查卡片所属会话（历史路径，工单卡恒为 null）；
            // 4. **磁盘账簿**（[TicketPatchLedger]）—— 宿主重启后前三处全空，
            //    而补丁文件还在沙箱产物目录里。没有这一条，重启等于把所有待合并的卡报废。
            // 合流驱动（Phase 4 任务 1）：编排器登记簿优先 —— 工单卡发卡那一刻
            // 就登记了（工作区, patch），git apply 只有真正跑完且退出码为 0
            // 才会有 Applied，decidedBy 合流卡只允许在 Applied 上发。
            val ledgerEntry = TicketPatchLedger.lookup(cardId)
            val ref = tickets()?.patchRefForCard(cardId)
                ?: sessionId?.takeIf { it.isNotBlank() }
                    ?.let { sid ->
                        plane().latestPatchRefFor(sid)?.let { (ws, patch) ->
                            com.kapp.tenon.agent.SandboxPatchRef(sid, ws, patch)
                        }
                    }
                ?: plane().latestPatchRefForCard(cardId)
                ?: ledgerEntry?.let { e ->
                    com.kapp.tenon.agent.SandboxPatchRef(
                        sessionId = "",
                        workspacePath = e.workspacePath,
                        patchPath = e.patchPath,
                    )
                }
            val outcome = when {
                // **链卡先于沙箱登记簿**：链（多棒责任链）的产物是 `chain/<id>`
                // 分支上的提交，不在沙箱 patch 登记簿里 —— 走沙箱路径必然
                // `no sandbox patch recorded`（合并点不动）。
                // 产物按分支合入（[DesktopPatchMerge.applyBranch]），语义与
                // patch 合并同构：只改工作树、不 commit、冲突零变化。
                ref == null && cardId.startsWith("chain-") -> {
                    val branch = "chain/" + cardId.removePrefix("chain-")
                    // 会话键：登记簿（活着时最准）→ 卡片行 `peerId`（重启后唯一还在的）。
                    // 两处都是**会话键形状**，群是 `group:<id>`、1:1 是对端 nodeId。
                    val chainConv = tickets()?.groupForCard(cardId) ?: actionRow?.peerId
                    // 工作区：群 → 默认工作区（[DesktopWorkerOrchestrator.resolveWorkspacePath]
                    // 的判据）→ **仓库自己的 refs**（重启兜底，见 workspacePathForChainBranch）。
                    // 第二段不是锦上添花：登记簿重启即空，而 1:1 会话连 groupId 都没有 ——
                    // 少了它，重启后点旧卡的「合并」永远得到 `cannot resolve workspace`。
                    val ws = chainConv
                        ?.let { com.kapp.tenon.core.protocol.ConvId.groupIdOf(it) ?: it }
                        ?.let { tickets()?.workspacePathForGroup(it) }
                        ?: tickets()?.workspacePathForChainBranch(branch)
                    when {
                        ws == null ->
                            DesktopPatchMerge.Outcome.Conflict(
                                "cannot resolve workspace for chain card $cardId" +
                                    " (no local repo has branch $branch)",
                            )
                        else -> DesktopPatchMerge.applyBranch(
                            // cardId = `chain-<chainId>`，分支名 = `chain/<chainId>`
                            //（DesktopStageChain 的 branch 约定）—— 别把前缀当掉。
                            branch = branch,
                            workspacePath = ws,
                        )
                    }
                }
                ref == null ->
                    DesktopPatchMerge.Outcome.Conflict("no sandbox patch recorded for card " + cardId)
                else -> tickets()?.takeIf { it.hasTicketForCard(cardId) }
                    ?.let { it.mergePatchByCard(cardId) }
                    ?: DesktopPatchMerge.apply(patchPath = ref.patchPath, workspacePath = ref.workspacePath)
            }
            when (outcome) {
                is DesktopPatchMerge.Outcome.Applied -> {
                    // 滚动基线（D5）：合并只改工作树、不提交，下一轮沙箱自 HEAD
                    // 建起就看不见这批改动。快照 sha 落到日志，是「下一轮真的
                    // 站在上一轮肩膀上」的唯一外部证据。
                    RelayLog.info("RELAY-INVOKE",
                        "patch merged into workspace by " + decidedBy +
                            " (card " + cardId + ")" +
                            (
                                outcome.baseSnapshot?.let { "; next sandbox base=" + it }
                                    ?: "; base snapshot unavailable — next sandbox starts from HEAD"
                                ),
                    )
                    val appliedRow = controller.state.value.messages.values
                        .asSequence()
                        .flatten()
                        .lastOrNull { row ->
                            (row.renderCard?.get("cardId") as? JsonPrimitive)?.contentOrNull == cardId
                        }
                    val appliedGroupId = tickets()?.groupForCard(cardId) ?: actionRow?.peerId
                        ?: ledgerEntry?.convKey?.takeIf { it.isNotBlank() }
                    if (appliedGroupId == null) {
                        // 合并已经落地、仓已经改了，卡却发不出去 —— 必须留痕。
                        // 从前这里静默跳过，卡面永远停在「待合并」。
                        RelayLog.info("RELAY-INVOKE", "merged card has no group to publish to (card " + cardId + ")")
                    }
                    val base = appliedRow?.renderCard
                    // **在原卡上改，不另造一张。** 用户点的是这张卡上的按钮，翻面之后
                    // 应该还是它：标题、进度、diff 明细（`+115 −0 / 2 个文件`）、阶段项
                    // 都留着，只把状态换成「已合并」。细节与判据见 [BoardCardFlip]。
                    val mergedCard = BoardCardFlip.merged(cardId, base).toString()
                    if (appliedGroupId != null) {
                        delivery.publishCardNow(appliedGroupId, agentId, mergedCard)
                    }
                    val sid = ref?.sessionId
                    if (sid != null) {
                        sessions()?.recordEvent(
                            nodeId = selfNodeId,
                            sessionId = sid,
                            cursor = System.currentTimeMillis(),
                            eventType = "merge-result",
                            summary = "applied",
                            body = "Patch merged successfully by $decidedBy",
                        )
                    }
                }
                is DesktopPatchMerge.Outcome.Conflict -> {
                    RelayLog.info("RELAY-INVOKE", "patch merge FAILED (card " + cardId + "): " + outcome.detail)
                    // **失败必须回到卡上。** 判定的那一刻手机已经把卡标成「已由
                    // <设备> 处理」，而宿主这边一个字节都没变 —— 用户只有下次打开
                    // 文件时才发现没合上。把同一张卡改成「受阻」并给出下一步，
                    // 比任何日志都管用。
                    // 取不到「下一步怎么说」时退回原始 stderr 尾巴：细节比一句
                    // 通用提示有用，而空提示会让受阻卡看起来像没做判断。
                    val hint = bridgeBlocking("patchMerge.hint", fallback = outcome.detail) {
                        DesktopPatchMerge.hintFor(outcome.detail, controller.uiTexts)
                    }
                    // 与合并成功同一条纪律：**在原卡上改**。冲突时用户最想看的
                    // 就是「哪几行没合上」，从零 build 一张只剩标题和横幅的卡，
                    // 等于把唯一的线索扔掉（细节见 [BoardCardFlip.blocked]）。
                    val conflictRow = controller.state.value.messages.values
                        .asSequence()
                        .flatten()
                        .lastOrNull { row ->
                            (row.renderCard?.get("cardId") as? JsonPrimitive)?.contentOrNull == cardId
                        }
                    val conflictCard = BoardCardFlip.blocked(
                        cardId = cardId,
                        base = conflictRow?.renderCard,
                        hint = hint,
                        title = "没能合并这次改动",
                    ).toString()
                    // 失败卡回到**这张卡所在的会话**：动作帧（op=action）不带
                    // groupId，但卡片消息就落在本机 —— 按 cardId 反查会话键，
                    // 那是权威答案。此前按工作区名去猜群（groupsForSession），
                    // 派单群 ≠ 工作区同名群时失败卡会发到别的群，点合并的人
                    // 永远看不到「没能合并」。
                    // 会话键直接取 `peerId`（1:1 的 peerId 就是会话键，经 `groupIdOf`
                    // 会被折成 null ⇒ 私聊里的受阻卡一个字都发不出去）。
                    val conflictGroupId = tickets()?.groupForCard(cardId) ?: conflictRow?.peerId
                        ?: ledgerEntry?.convKey?.takeIf { it.isNotBlank() }
                    if (conflictGroupId == null) {
                        RelayLog.info("RELAY-INVOKE", "merge conflict has no group to report on (card " + cardId + ")")
                    } else {
                        delivery.publishCardNow(conflictGroupId, agentId, conflictCard)
                    }
                    // 冲突也要落台账；没有会话可落时如实打日志，不假装记过账。
                    val sid = ref?.sessionId
                    if (sid == null) {
                        RelayLog.info("RELAY-INVOKE", "patch merge has no session to record on (card " + cardId + ")")
                    } else {
                        sessions()?.recordEvent(
                            nodeId = selfNodeId,
                            sessionId = sid,
                            cursor = System.currentTimeMillis(),
                            eventType = "merge-result",
                            summary = "conflict",
                            body = outcome.detail,
                        )
                    }
                }
            }
        } else if (actionId == "abort-task") {
            // 卡上的「停止」：与群里发 stop 落到**同一个**动作（摘进程树），
            // 只是入口从「背下来的关键词」换成按钮。落地后由驱动把同一张卡
            // 收尾成 state=aborted，用户看到的是「已取消」而不是「失败」。
            scope.launch {
                // 工单卡（`chain-*`）优先交给编排器：停止必须是**终态**。只摘进程的话
                // CLI 以非零码退出，自愈循环会把它当「本轮失败」再跑两轮 —— 用户按了
                // 「停止」，等来的是又跑两轮加一张受阻卡（见 [abortByCard] 的 KDoc）。
                if (tickets()?.abortByCard(cardId) == true) {
                    RelayLog.info("RELAY-INVOKE",
                        "abort by card action " + decidedBy +
                            " (card " + cardId + "), ticket closed as aborted",
                    )
                    return@launch
                }
                val killed = runCatching { plane().abortRunningSandboxes() }.getOrDefault(0)
                RelayLog.info("RELAY-INVOKE",
                    "abort requested by " + decidedBy +
                        " (card " + cardId + "), sandbox tasks killed=" + killed,
                )
                if (killed == 0) {
                    // 没有在跑的沙箱：这张卡已经结束了，动作来晚了。如实记下来，
                    // 不要静默 —— 否则「点了没反应」又要被当成缺陷重查一遍。
                    RelayLog.info("RELAY-INVOKE", "abort had nothing to stop (card " + cardId + ")")
                    // 编排器也不认得这张卡（重启丢态/早已收尾）时，把滞留卡如实
                    // 翻面；工单还活着就只留日志，真实状态卡马上就到。
                    delivery.flipStaleChainCard(cardId, agentId)
                }
            }
        } else if (com.kapp.tenon.domain.isSessionExemptAction(actionId)) {
            // L1 会话免审（§4.2 / §八.2）：手机卡上勾选「本会话免审」→ 宿主台账落一条
            // （会话粒度 + 过期租约）→ 此后该会话的低危动作由 Reviewer 判据自动验讫，
            // 不再逐次弹审批。动作串只有 cardId 一个槽，会话键按 agent 最近会话解析
            // （动作帧 op=action 不带 sessionId，与 merge-patch 反查同一条兜底纪律）。
            val cardId0 = com.kapp.tenon.domain.sessionExemptCardIdOf(actionId) ?: cardId
            val agentKey0 = plane().latestSessionForAgent(agentId)?.let { it.agentId ?: it.agentKind } ?: agentId
            val sessionKey0 = plane().latestSessionForAgent(agentId)?.sessionId
            if (sessionKey0.isNullOrBlank()) {
                // 没有会话可挂：免审无处落，如实翻面并记日志 —— 不编一个键把
                // 「不知道哪个会话」记成「全部会话」（那等于全放行）。
                RelayLog.info("RELAY-INVOKE", "session-exempt has no session for agent " + agentId + " (card " + cardId0 + ")")
                delivery.flipStaleChainCard(cardId0, agentId)
            } else {
                val ok = exempts()?.grant(
                    sessionKey = sessionKey0,
                    agentKey = agentKey0,
                    grantedBy = decidedBy,
                    at = System.currentTimeMillis(),
                ) == true
                if (ok) {
                    RelayLog.info("RELAY-INVOKE",
                        "session exempt granted by " + decidedBy +
                            " (card " + cardId0 + ", session " + sessionKey0 + ", agent " + agentKey0 + ")",
                    )
                } else {
                    RelayLog.info("RELAY-INVOKE", "session-exempt rejected empty keys (card " + cardId0 + ")")
                }
            }
        } else if (actionId == "start-task") {
            // 团队点将卡的「立即执行」：把倒计时中的工单提前开跑。
            val started = tickets()?.startByCard(cardId) == true
            RelayLog.info("RELAY-INVOKE",
                if (started) {
                    "proposed team confirmed by $decidedBy, task started (card $cardId)"
                } else {
                    "start-task had nothing to start (card $cardId)"
                },
            )
            if (!started) delivery.flipStaleChainCard(cardId, agentId)
        } else if (actionId == "cancel-task") {
            // 团队点将卡的「取消」：撤销尚未开工的工单（无进程可摘，不走 abort 桥）。
            val cancelled = tickets()?.cancelByCard(cardId) == true
            RelayLog.info("RELAY-INVOKE",
                if (cancelled) {
                    "proposed team cancelled by $decidedBy (card $cardId)"
                } else {
                    "cancel-task had nothing to cancel (card $cardId)"
                },
            )
            if (!cancelled) delivery.flipStaleChainCard(cardId, agentId)
        } else if (actionId == "approve-prototype") {
            tickets()?.approvePrototypeByCard(cardId)
        } else if (actionId == "refine-pending" || actionId == "dispatch-pending") {
            // 方向确认卡（缺陷 B 的判据）：prototype 待选期来的无 @ 文本被暂存，
            // 由用户在这里裁决去向。落空（pending 已被消费/工单已结束）要留痕，
            // 否则「点了没反应」又要被当成缺陷重查一遍。
            val confirmed = tickets()?.confirmPrototypeInput(cardId, actionId == "dispatch-pending") == true
            if (confirmed) {
                RelayLog.info("RELAY-INVOKE", "direction-ask resolved: $actionId by $decidedBy (card $cardId)")
            } else {
                RelayLog.info("RELAY-INVOKE", "direction-ask had nothing pending (card $cardId)")
                delivery.flipStaleChainCard(cardId, agentId)
            }
        } else if (actionId.startsWith("reject-revision")) {
            // 打回反向注水（Phase 4 任务 2）：反馈文本以 `reject-revision|<文本>`
            // 编码进 actionId（编码侧在 composeApp 卡片层），由编排器拆包注入
            // 下一轮自愈提示词。接不住（工单已收尾/不能重跑）时如实翻面，
            // 不假装接下了反馈。
            val rejected = tickets()?.rejectRevisionByCard(cardId, actionId) == true
            RelayLog.info("RELAY-INVOKE",
                if (rejected) {
                    "reject-revision consumed as hard constraint by $decidedBy (card $cardId)"
                } else {
                    "reject-revision had nothing to revise (card $cardId)"
                },
            )
            if (!rejected) delivery.flipStaleChainCard(cardId, agentId)
        } else if (actionId == "abandon-heal") {
            // 熔断卡的第二颗按钮：不重跑、不清理现场，翻成终局让人去桌面端接管。
            val kept = tickets()?.abandonHealByCard(cardId) == true
            RelayLog.info("RELAY-INVOKE",
                if (kept) {
                    "abandon-heal: worktree kept, terminal aborted (card $cardId)"
                } else {
                    "abandon-heal had no blocked ticket (card $cardId)"
                },
            )
            if (!kept) delivery.flipStaleChainCard(cardId, agentId)
        } else if (actionId == "retry") {
            // 受阻卡的 [重试]：由编排器把这张工单的开发/自愈循环重跑一遍。
            // 必须排在 `followUpByCard` 之前 —— 否则 `retry` 会被当成一句用户
            // 文本喂进原型闭包（真机实测：点 Retry 出来的是「Refined prototype ready」），
            // 或者落到插件转发被丢掉（`card action retry ... has no local plugin`）。
            val restarted = tickets()?.retryByCard(cardId) == true
            RelayLog.info("RELAY-INVOKE",
                if (restarted) {
                    "retry restarted the dev loop (card $cardId)"
                } else {
                    "retry had nothing to restart (card $cardId)"
                },
            )
            if (!restarted) delivery.flipStaleChainCard(cardId, agentId)
        } else if (cardId.startsWith("question-")) {
            RelayLog.info("RELAY-INVOKE", "question card " + cardId + " answered with \"" + actionId + "\" by " + decidedBy)
        } else if (tickets()?.followUpByCard(cardId, actionId) == true) {
            RelayLog.info("RELAY-INVOKE", "card action \"$actionId\" consumed as follow-up by $decidedBy")
        } else {
            val entry = plane().pluginForAgent(agentId)
        if (entry == null) {
            RelayLog.info("RELAY-INVOKE", "card action " + actionId + " for agent " + agentId + " has no local plugin (dropped)")
            // 工单卡的按钮落到这里 = 宿主已不认得这张卡（重启丢态/早已收尾）。
            // 与其静默 drop 让手机挂着「已处理」的假象，不如把卡如实翻面。
            delivery.flipStaleChainCard(cardId, agentId)
        } else {
            val sid = sessionId?.takeIf { it.isNotBlank() }
                ?: plane().latestSessionForAgent(agentId)?.sessionId
            if (sid == null) {
                RelayLog.info("RELAY-INVOKE", "card action " + actionId + " for agent " + agentId + " has no session to land on (dropped)")
            } else {
                bridgeBlockingUnit("plugin.action." + actionId) {
                    plane().pluginAction(entry, sid, actionId, decidedBy)
                }
            }
        }
        }
    }
}
