// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.AgentChainDispatcher
import com.kapp.tenon.agent.ChainProgress
import com.kapp.tenon.agent.ChainStage
import com.kapp.tenon.agent.ControlPlane
import com.kapp.tenon.agent.ReviewPipeline
import com.kapp.tenon.agent.ReviewVerdict
import com.kapp.tenon.agent.ReviewerAudit
import com.kapp.tenon.agent.StageChain
import com.kapp.tenon.agent.StageChainRunner
import com.kapp.tenon.agent.StageRun
import com.kapp.tenon.agent.executeCliTask
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.transport.LocalFile
import com.kapp.tenon.domain.RelayController
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 责任链的**宿主侧执行体**：把 [StageChainRunner] 的四个抽象动作接上真东西
 * （git / CLI 沙箱 / 群卡片广播）。
 *
 * ## 为什么在这里（:orchestration）而不在 :agentNode
 *
 * 这一层要读宿主仓、要起 CLI、要把卡发进群 —— 三样都挂在领域能力与控制面上，不是协议层。
 * :agentNode 只留纯逻辑（[com.kapp.tenon.agent.StageChain]）与 `POST /v1/agent/chain`
 * 这道门，单进程跑测试时门后是空的（503），不会假装接了单；本模块把这些动作接上真东西，
 * 桌面壳只负责注入 controller / scope / 端口。编排放在这里而不是壳里，手机与 :cli 才能
 * 复用同一条链 —— 否则「跑链」就只是桌面端的一个隐式能力。
 *
 * ## 三条安全线
 *
 * 1. **不碰主干**：链条从当前 HEAD 切出 `chain/<id>` 分支，**检出在独立的 worktree 里**跑，
 *    跑完 worktree 删掉、分支留着。机主不满意就 `git branch -D`，宿主仓的分支指针与工作树
 *    一根手指都不用动（P5：从前是直接在宿主仓 `checkout -b`，链一跑机主的工作树就被切走，
 *    而且 CLI 会顺 `.git` 的 gitdir 认出主干仓、把改动写回宿主仓 —— 越界写守卫必然停链）。
 * 2. **工作树必须干净才开工**：脏工作树 + `git apply` 会与既有改动混成一锅，
 *    之后谁也说不清哪个改动是哪一棒做的（文档 §4.4 的同一条纪律）。
 * 3. **一链一卡**：`cardId = chain-<id>`，rev 单调递增，走既有的 T-2d 原位替换 ——
 *    手机上同一个位置反复刷新，不刷屏。
 */
object DesktopStageChain {

    /** 一棒最多给下游 2000 字原文，剩下交给 [com.kapp.tenon.agent.StageChain.summarize] 压到 200。 */
    private const val LOG_TAIL_CHARS = 2000

    /** git 子进程上限：`git apply` 卡住比失败更糟（链会挂在那儿等）。 */
    private const val GIT_TIMEOUT_MS = 30_000L

    fun dispatcher(
        plane: () -> ControlPlane?,
        controller: RelayController,
        scope: CoroutineScope,
        /** L1 会话免审的查表器（桌面装配注入）：null = 没有台账，照常逐次审批。 */
        sessionExemptOf: ((sessionId: String) -> Boolean)? = null,
        /** 盲区账本记录器（G2）：链级 blocked 也是终局事实，只记录。 */
        onBlindSpot: (kind: String, title: String?, detail: String?) -> Unit = { _, _, _ -> },
    ): AgentChainDispatcher = AgentChainDispatcher { groupId, repoPath, title, stages, target, module, context ->
        val host = plane() ?: return@AgentChainDispatcher null
        val repo = repoPath?.takeIf { it.isNotBlank() } ?: return@AgentChainDispatcher null
        if (!isGitRepo(repo)) return@AgentChainDispatcher null
        val chainId = "c" + System.currentTimeMillis().toString(36)
        scope.launch {
            runCatching { runChain(host, controller, chainId, groupId, repo, title, stages, sessionExemptOf, onBlindSpot, target, module, context) }
                .onFailure {
                    val reason = it.message ?: it::class.simpleName ?: "unknown"
                    onBlindSpot("chain-crashed", title.take(60), reason.take(200))
                    RelayLog.info("CHAIN", chainId + " aborted: " + reason)
                }
        }
        chainId
    }

    private suspend fun runChain(
        host: ControlPlane,
        controller: RelayController,
        chainId: String,
        groupId: String?,
        repo: String,
        title: String,
        stages: List<ChainStage>,
        sessionExemptOf: ((sessionId: String) -> Boolean)? = null,
        onBlindSpot: (kind: String, title: String?, detail: String?) -> Unit = { _, _, _ -> },
        target: String? = null,
        module: String? = null,
        context: String? = null,
    ) {
        var rev = 0
        val statuses = stages.map { it.role to "pending" }.toMutableList()

        var pendingChecks: List<com.kapp.tenon.agent.ChainCheck> = emptyList()
        // 每棒摘要（P1）：runner 的 report 带过来，随卡透传 —— 重新打包 ChainProgress 时
        // 曾把它丢掉，卡上于是只剩棒名+状态词（真机 rev6 实测 detail 全空的原因）。
        var pendingDetails: List<String> = emptyList()
        // 一链的产物清单：逐棒累积（不是某一棒的），卡面 artifacts 行是整条链的交付面。
        // 并发隔离棒在 Default 线程上同时投递，所以收口在这把锁里。
        val artifactLock = Any()
        var chainArtifacts: List<Pair<String, Long>> = emptyList()
        fun currentArtifacts(): List<Pair<String, Long>> =
            synchronized(artifactLock) { chainArtifacts }

        fun recordArtifacts(delivered: List<Pair<String, Long>>) {
            if (delivered.isEmpty()) return
            synchronized(artifactLock) {
                val merged = LinkedHashMap<String, Long>()
                chainArtifacts.forEach { (name, bytes) -> merged[name] = bytes }
                delivered.forEach { (name, bytes) -> merged[name] = bytes }
                chainArtifacts = merged.toList()
            }
        }

        suspend fun publish(
            state: String,
            banner: String?,
            current: String? = null,
            percent: Int = 0,
            details: List<String> = pendingDetails,
            // 产物行随每张卡透传：publish 重打包 ChainProgress 时曾经漏过 details
            // （真机 rev6 卡面 detail 全空），这里把 artifacts 一并显式带上。
            artifacts: List<Pair<String, Long>> = currentArtifacts(),
        ) {
            // 链级 blocked 也是终局事实（G2）：进盲区账本，只记录 —— 无论卡发没发出去。
            if (state == "blocked") {
                onBlindSpot(BlindSpotPatrol.KIND_BLOCKED, title.take(60), banner?.take(200))
            }
            if (groupId == null) return
            runCatching {
                controller.broadcastChainCard(
                    groupId = groupId,
                    agentId = CHAIN_AGENT_ID,
                    // Reviewer 审计结论随卡透传（checks[] 契约，宽松解析）：非合流卡
                    // pendingChecks 恒空、卡面零变化；合流卡/blocked 卡带全量审计项。
                    cardJson = com.kapp.tenon.agent.StageChain.cardJsonOf(
                        ChainProgress(chainId, ++rev, state, title, current, statuses.toList(),
                            percent, banner, checks = pendingChecks, details = details,
                            // 承办人：每棒的角色名（展示事实，由 cardOf 转工牌名与步骤行徽标）。
                            assignees = stages.map { it.role },
                            // 产物行（name + bytes，cardOf 已渲染）：本机绝对路径只活在
                            // ArtifactPipe 的投递参数里，绝不进这张卡的 JSON。
                            artifacts = artifacts,
                            target = target,
                            module = module,
                            context = context),
                    ),
                )
            }.onFailure { t -> RelayLog.info("CHAIN", chainId + " card publish failed: " + t.message) }
            // 每张卡发出后清空：审计项只属于触发它的那一张卡（一卡一事实）。
            pendingChecks = emptyList()
        }

        val dirty = git(repo, "status", "--porcelain")?.trim().orEmpty()
        if (dirty.isNotEmpty()) {
            publish("blocked", "Working tree not clean (" + dirty.lineSequence().count() + " changes); please commit or stash first")
            return
        }
        val origin = git(repo, "rev-parse", "--abbrev-ref", "HEAD")?.trim()
        val branch = "chain/" + chainId
        // **一链一 worktree**（P5）：从前是 `git checkout -b chain/<id>` **在宿主仓里**开跑，
        // 于是两件事同时发生 ——
        //   ① 机主的工作树被切到链分支，链失败后还停在那儿（真机：跑完 `~/weather-relay`
        //      的 HEAD 变成 `chain/cmw0dqn6y`，还留了个未跟踪文件）；
        //   ② CLI 的 cwd 虽是 worktree（executeCliTask 自己会建），但 codex 会顺着 `.git`
        //      的 `gitdir:` 认出主干仓，用绝对路径直接改宿主仓 —— 沙箱产物为空、宿主仓却脏，
        //      越界写守卫必然触发，**第 1 棒就停链**，多 agent 交接永远轮不到第 2 棒。
        // 现在链分支检出在独立的 worktree 里：cwd、写入、提交都在这一个目录内，
        // 机主的主干仓一根手指都不动（跑完只多一个分支）。
        val chainWorktree = File(chainWorktreeRoot(), chainId)
        chainWorktree.parentFile?.mkdirs()
        if (git(repo, "worktree", "add", "-b", branch, chainWorktree.absolutePath, "HEAD") == null) {
            publish("blocked", "Cannot create worktree for " + branch + " (already exists on " + (origin ?: "?") + "?)")
            return
        }
        try {

        // 审查棒被要求给出显式结论行：不给就 fail-closed，绝不把「没说」当成 PASS。
        // 这是 prompt 层的约束，不是协议字段 —— 线上字节一个不变。
        val reviewStages = stages.mapIndexed { i, stage ->
            if (i == 0) stage else stage.copy(
                instruction = stage.instruction +
                    "\nEnd with an explicit line: VERDICT: PASS or VERDICT: REJECT.",
            )
        }

        var handoffSpoken = false
        val runner = StageChainRunner(
            headSha = { repoPath -> git(repoPath, "rev-parse", "HEAD")?.trim() },
            runStage = { stage, prompt, repoPath ->
                val run = runOneStage(host, controller, groupId, stage, prompt, repoPath, repo) { delivered ->
                    recordArtifacts(delivered)
                }
                // 棒间交接发言（P0b）：Worker 棒（首棒）执行完毕且成功，且存在后续审查棒时，
                // 由 Worker 席位在群内广播 1 条拟人化交棒发言。整条责任链上限 1 条，避免刷屏。
                if (run.ok && groupId != null && !handoffSpoken && stages.size >= 2 && stage == stages[0]) {
                    handoffSpoken = true
                    val nextRole = stages.getOrNull(1)?.role ?: "Reviewer"
                    val speech = controller.uiTexts.stageHandoffSpeech(stage.role, nextRole)
                    if (speech.isNotBlank()) {
                        controller.broadcastAgentSpeech(groupId, stage.agentKey, speech)
                    }
                }
                run
            },
            land = { repoPath, run, message -> landStage(repoPath, run, message) },
            // Reviewer 白名单键审计的取值域清单：从 core 协议取值域对象装配（§九.1——
            // 取值域只能有一份定义，宿主引用它们而不是抄一份）。审计对 patch 中出现的
            // 键/值按清单核对；清单没覆盖的载荷形状如实 SKIP。
            valueDomains = listOf(
                ReviewerAudit.ValueDomain(
                    payload = "agent-ack",
                    keys = setOf("kind", "cardId", "actionId"),
                    valueOf = mapOf("kind" to com.kapp.tenon.core.protocol.AgentAckKinds.VALUES),
                ),
                ReviewerAudit.ValueDomain(
                    payload = "grant",
                    keys = setOf("access", "scope"),
                    valueOf = mapOf("scope" to com.kapp.tenon.core.protocol.AgentGrantScopes.VALUES),
                ),
                ReviewerAudit.ValueDomain(
                    payload = "exec-policy",
                    keys = setOf("policy"),
                    valueOf = mapOf("policy" to com.kapp.tenon.core.protocol.AgentExecPolicies.VALUES),
                ),
            ),
            report = { progress ->
                statuses.clear()
                statuses.addAll(progress.stages)
                // 合流卡 / 审计 blocked 卡上带着 checks[]：先存、随紧随其后的 publish 透传。
                pendingChecks = progress.checks
                // 每棒摘要（P1）：透传给 publish，重新打包 ChainProgress 时不再丢。
                pendingDetails = progress.details
                publish(progress.state, progress.banner, progress.currentRole, progress.percent, progress.details)
                // 线性多棒全 PASS ⇒ 自动合流：复用既有单槽广播，不另写一条发送。
                if (progress.reviewPassed && progress.state == "review_ready") {
                    RelayLog.info("CHAIN", chainId + " all reviewers passed: auto-merged")
                    publish("merged", "All reviewers passed: chain auto-merged")
                }
                // L1 会话免审的自动验讫（§八.2）：验收棒判 review_ready 且本会话免审时，
                // 逐棒检查低危判据 —— 全部低危（单测/文档/日志类）就自动合流，不再逐次
                // 弹审批。高危词一律不走免审（LowRiskReviewer 黑名单赢），判定是纯函数、
                // 两侧测试各钉一份。任何一棒不是低危 ⇒ 照常等机主 1-Click（fail-closed）。
                // 已经因全棒 PASS 合流过的链不再走免审，避免同一张卡翻两次 merged。
                if (progress.state == "review_ready" && !progress.reviewPassed &&
                    sessionExemptOf?.invoke(chainId) == true
                ) {
                    val allLow = stages.all { stage ->
                        LowRiskReviewer.isLowRisk(riskLevel = null, summaryOrCommand = stage.instruction + " " + stage.role)
                    }
                    if (allLow) {
                        RelayLog.info("CHAIN", chainId + " session exempt: low-risk stages auto-merged by reviewer")
                        publish("merged", "Session exempt: low-risk chain auto-merged by reviewer")
                    }
                }
            },
            review = ReviewPipeline(),
            verdictOf = { it.verdict },
        )

        try {
            // 每一棒都在**链自己的 worktree** 里跑：`repoPath` 从此是隔离目录，
            // 不是机主的主干仓（见上面 worktree 那段注释）。
            runner.run(chainId, chainWorktree.absolutePath, title, reviewStages)
        } catch (t: Throwable) {
            publish("blocked", "Stage chain interrupted: " + (t.message ?: t::class.simpleName))
            return
        }
        RelayLog.info("CHAIN", chainId + " finished on branch " + branch +
            " (origin " + (origin ?: "?") + " untouched)")
        } finally {
            // 收尾：worktree 删掉、**分支留着**（机主要看就是在 chain/<id> 上）。
            // 清理失败只留日志 —— 链的结论已经发出去了，不该因为清理不干净把结果吞掉。
            if (git(repo, "worktree", "remove", "--force", chainWorktree.absolutePath) == null) {
                RelayLog.info("CHAIN", chainId + " worktree cleanup failed: " + chainWorktree.absolutePath)
            }
            chainWorktree.deleteRecursively()
        }
    }

    /**
     * 跑一棒：CLI 在独立 worktree 里干，收割出 patch 与 task.log。
     *
     * 摘要优先取 `task.log` 的尾部 —— 那是 CLI 真说过的话；只有拿不到日志时才退回
     * 事件流里的最后一段文本。**绝不**把 task.log 整篇交给下一棒（见 StageChain 的 KDoc）。
     */
    private suspend fun runOneStage(
        host: ControlPlane,
        controller: RelayController,
        groupId: String?,
        stage: ChainStage,
        prompt: String,
        repoPath: String,
        /** 机主的主干仓（链 worktree 的父仓）。只有它变脏才算真越界。 */
        hostRepo: String,
        /** 本棒真实投递出去的产物（name + bytes）；空 = 这一棒没有可投的产物。 */
        onArtifacts: (List<Pair<String, Long>>) -> Unit = {},
    ): StageRun {
        var patchPath: String? = null
        var lastSaid: String? = null
        val (sessionId, status, error) = host.executeCliTask(
            agentKey = stage.agentKey,
            prompt = prompt,
            wsPath = repoPath,
            requestedSessionId = null,
            onArtifact = { path, name, _, _, _ -> if (name == "patch.diff") patchPath = path },
            // 只有增量文本是「它说的话」。其余事件的三元组都不是发言：finished 的
            // summary 是 finishReason（正常收工就是 "stop"），tool.started 的 body 是
            // 调用参数、artifact 是 SHA/字节/路径对账、card 是卡片 JSON、error 是诊断。
            // 从前一律 (body ?: summary) 全收，于是没有 task.log 的棒把 "stop" 当摘要
            // 交给下一棒、并写进卡面 detail（真机尾巴垃圾词的根因）。
            onEvent = { eventType, _, summary, body ->
                saidFromEvent(eventType, summary, body)?.let { lastSaid = it }
            },
        )
        // 一棒的 harvest 产物（非 patch：prototype.html、方案/报告 md 等）走既有
        // 免确认文件通路（purpose=artifact）投群。只有**真的发起过直投**的才上报 ——
        // 卡面 artifacts 行必须点得开，投递失败的行不许留在卡上。
        val targetGroup = groupId
        if (targetGroup != null) {
            val delivered = deliverStageArtifacts(collectStageArtifacts(patchPath)) { files ->
                controller.sendAgentArtifacts(ConvId.group(targetGroup), files)
            }
            onArtifacts(delivered)
        }
        val logTail = patchPath?.let { patch ->
            runCatching {
                File(patch).parentFile?.resolve("task.log")?.takeIf { it.isFile }
                    ?.readText()?.takeLast(LOG_TAIL_CHARS)
            }.getOrNull()
        }
        // 先把 CLI 的信封拆掉再当摘要：三个驱动的日志都是 JSON 流，直接截尾部会把
        // `"_response","text_delta"` 这类机器噪声交给下一棒（真机实测过）。
        val summary = stageSummaryOf(logTail, lastSaid)
        // 「退出码 0 但一个字的输出、一个字节的改动都没有」不算做完。
        //
        // 这个形态真机见过：CLI 挂在启动提示上（读 stdin 等 EOF），被外部信号打断后以 0
        // 退出，沙箱如实报 finished、harvest 空 —— 若按 status 判，这一棒就成了「成功且
        // 无改动」，把空改动一路传给下一棒，最后整条链「全绿但什么都没干」。
        // 「沙箱说没改动、宿主仓却脏了」= 这一棒越界写进了宿主工作树。
        //
        // 真机 cmvx8q5ig 第一棒实测：worktree 隔离只是 cwd，不是强制面 —— codex 从
        // worktree 的 `.git` 文件里读到 `gitdir: …/weather-relay/.git/worktrees/xxx`，
        // 顺着它认出了主干仓，于是用绝对路径直接改 `/Users/…/weather-relay/src/…`。
        // 收割如实为空（worktree 真的一字未改），链却把**旧基线**交给验收棒，
        // 最后出现「开发棒修了 AC5、验收棒验收的是没修的代码」这种假 FAIL。
        //
        // **P5 之后判据换了**：链现在把 `chain/<id>` 检出在 `/tmp/tenon-chains/<id>`，
        // 那个工作树**本身就是隔离区**。所以「工作树脏了」不再是越界，而是**它真的干了活**
        // （沙箱又套了一层 worktree，codex 再往上一层写，落点正好是链工作树）。这种改动
        // 直接就地收（见 [landStage] 的无 patch 分支），不判失败。
        //
        // 真正的越界只剩一条：**机主的主干仓被动过** —— 那才是跳出隔离区。
        val worktreeDirty = !git(repoPath, "status", "--porcelain").isNullOrBlank()
        val didNothing = patchPath == null && !worktreeDirty && summary.isNullOrBlank()
        val escapedWrite = !git(hostRepo, "status", "--porcelain").isNullOrBlank()
        return StageRun(
            ok = status == "finished" && !didNothing && !escapedWrite,
            sessionId = sessionId,
            patchPath = patchPath,
            summary = summary,
            error = when {
                status != "finished" -> error ?: "cli status=" + status
                escapedWrite ->
                    "Escaped write: host repo was modified outside the chain worktree (" + hostRepo + ") - " +
                        "artifacts did not enter chain; continuing would verify the wrong baseline"
                didNothing -> "cli exited with no output or patch (possibly blocked on prompt/stdin/permissions)"
                else -> null
            },
            // 审查结论只认驱动真的写下的显式行；解析不到 = null，流水线会 fail-closed。
            verdict = ReviewVerdict.parseOf(listOfNotNull(lastSaid, logTail).joinToString("\n")),
        )
    }

    /**
     * 把一棒的 patch 落到宿主仓并提交。
     *
     * 空 patch / 打完没有变化 ⇒ 回 null（**不是**失败：审查棒常常一个字都不改也要判 PASS）。
     * `git apply` 真的失败 ⇒ 抛出去让链条停 —— 那说明这一棒的上下文与基线的假设不符，
     * 继续往下一棒传只会把错误滚大。
     */
    private suspend fun landStage(repoPath: String, run: StageRun, message: String): String? =
        withContext(Dispatchers.IO) {
            val patch = run.patchPath
            if (patch != null) {
                val file = File(patch)
                if (!file.isFile || file.length() == 0L) return@withContext null
                git(repoPath, "apply", "--whitespace=nowarn", patch)
                    ?: throw IllegalStateException("git apply failed for " + file.name + " - patch conflict with baseline")
            }
            // **无补丁但有就地改动**：codex 顺 `.git` 写到上一层时，落点正是链工作树
            // （见 runOneStage 的 worktreeDirty）。改动已经在位，直接提交即可，不必再
            // 绕一圈 patch → apply。
            if (git(repoPath, "status", "--porcelain")?.isBlank() != false) return@withContext null
            git(repoPath, "add", "-A")
            git(
                repoPath,
                "-c", "user.email=chain@tenon.local",
                "-c", "user.name=tenon-chain",
                "commit", "-q", "-m", message,
            ) ?: throw IllegalStateException("git commit failed on " + repoPath)
            git(repoPath, "rev-parse", "HEAD")?.trim()
        }

    // ------------------------------------------------------------------ 棒的产物

    /**
     * 一棒收割后可投递的产物。
     *
     * [path] 是**宿主本机**的绝对路径，只用于 LocalFile 交给传输层；协议帧里只有
     * [name] 与 [bytes]（cardOf 的 artifacts 条目）—— 路径到此为止，绝不进帧。
     */
    data class StageArtifact(
        val name: String,
        val path: String,
        val bytes: Long,
    )

    /**
     * 产物白名单：用户/审查者能**直接打开**、且 diff 能整份还原的文本产物。
     * 快照目录里同时躺着源码改动（.kt 等），那不是产物、也不该往群里灌。
     */
    private val DELIVERABLE_EXTENSIONS = setOf("html", "htm", "svg", "md")

    /**
     * 事件流里的**真实发言**：只有增量文本（token.delta / thought.delta）的 body 算。
     *
     * 判据与编排器那条白名单同源（那边已经踩过「黑名单会把卡片 JSON 摊给用户」）：
     * - finished：summary 是 finishReason（正常收工 = stop），不是话；
     * - tool.started：body 是调用参数 JSON；
     * - artifact：body 是 sha256/bytes/path 对账串；
     * - card：body 是卡片 JSON；
     * - error：body 是英文诊断。
     */
    fun saidFromEvent(eventType: String, summary: String?, body: String?): String? =
        if (eventType == "token.delta" || eventType == "thought.delta") {
            (body ?: summary)?.takeIf { it.isNotBlank() }
        } else {
            null
        }

    /**
     * 一棒的摘要：优先 task.log 里它真说过的话，其次事件流里的真实发言。
     *
     * 日志拿不到（没有 patch / task.log 不存在 / 空白）**不等于**它说了 finished 的原因：
     * 退回上一段文本时，那段文本必须来自 [saidFromEvent]，不能是 stop 这类收工词。
     * 返回值参与 didNothing 判据（summary.isNullOrBlank()），所以空必须保持 null。
     */
    fun stageSummaryOf(logTail: String?, lastSaid: String?): String? =
        StageChain.saidText(logTail) ?: lastSaid?.takeIf { it.isNotBlank() }

    /**
     * 从一棒的 harvest 目录收集**非 patch 的产物**，喂给 [deliverStageArtifacts]。
     *
     * 两个来源（都对应「用户手里能打开的东西」这个判据）：
     * 1. harvest 目录下 files/ 里收割层为**改过的文件**存的全文快照 —— 多 hunk 修改
     *    也能整份拿到（微调类任务改的就是上一轮的 prototype.html）；
     * 2. patch 里**新建**的文本产物（html/svg/md）：新文件的 + 行就是全文，
     *    用 StageChain.extractFileFromPatch 还原后落到宿主临时目录再投。
     *
     * 刻意**不投** patch.diff / report.json / task.log：补丁另有 landStage 落地、
     * report 是机器对账（里面带本机路径）、日志不是交付物。宁缺毋假。
     */
    fun collectStageArtifacts(patchPath: String?): List<StageArtifact> {
        if (patchPath.isNullOrBlank()) return emptyList()
        val patchFile = File(patchPath)
        val harvestDir = patchFile.parentFile ?: return emptyList()
        val found = LinkedHashMap<String, StageArtifact>()

        fun offer(file: File, displayName: String) {
            if (!file.isFile || file.length() <= 0L) return
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext !in DELIVERABLE_EXTENSIONS) return
            if (found.containsKey(displayName)) return
            found[displayName] = StageArtifact(displayName, file.absolutePath, file.length())
        }

        // 1) 改过文件的全文快照。
        val snapshotRoot = File(harvestDir, "files")
        if (snapshotRoot.isDirectory) {
            snapshotRoot.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(snapshotRoot).invariantSeparatorsPath }
                .forEach { offer(it, it.name) }
        }
        // 2) patch 里新建的文本产物：diff 只带 + 行，必须先整份还原成实体文件。
        newTextArtifactPaths(patchFile).forEach { rel ->
            val text = StageChain.extractFileFromPatch(patchFile, rel) ?: return@forEach
            val out = extractedArtifactFile(harvestDir, rel) ?: return@forEach
            val written = runCatching {
                out.parentFile?.mkdirs()
                out.writeText(text)
            }.isSuccess
            if (!written) {
                RelayLog.info("CHAIN", "artifact extract write failed: " + out.absolutePath)
                return@forEach
            }
            offer(out, out.name)
        }
        return found.values.toList()
    }

    /** patch 里新建、且扩展名在产物白名单内的文件路径（仓库相对）。 */
    private fun newTextArtifactPaths(patchFile: File): List<String> {
        if (!patchFile.isFile) return emptyList()
        val paths = LinkedHashSet<String>()
        var newFile = false
        var sawTarget = false
        patchFile.forEachLine { line ->
            when {
                line.startsWith("diff --git ") -> {
                    newFile = false
                    sawTarget = false
                }
                line.startsWith("new file mode ") -> newFile = true
                !sawTarget && line.startsWith("--- ") &&
                    line.removePrefix("--- ").trim() == "/dev/null" -> newFile = true
                !sawTarget && line.startsWith("+++ ") -> {
                    sawTarget = true
                    val path = line.removePrefix("+++ ").trim().removePrefix("b/")
                    val ext = path.substringAfterLast('.', "").lowercase()
                    if (newFile && ext in DELIVERABLE_EXTENSIONS) paths.add(path)
                }
            }
        }
        return paths.sorted()
    }

    /**
     * 新建产物的宿主落点：系统临时目录按 harvest 目录名分一层，保留 patch 里的相对
     * 路径避免同名覆盖。解析后的路径必须仍在根内（patch 来自 CLI，不可信）。
     */
    private fun extractedArtifactFile(harvestDir: File, rel: String): File? {
        val root = File(
            File(System.getProperty("java.io.tmpdir"), "tenon-chain-artifacts"),
            harvestDir.name,
        )
        val target = File(root, rel).canonicalFile
        if (!target.path.startsWith(root.canonicalFile.path + File.separator)) return null
        return target
    }

    /**
     * 把一棒产物经既有免确认文件通路（purpose=artifact）投群，返回**真的发起直投**的
     * (name, bytes)，供卡面 artifacts 行使用。
     *
     * 三条纪律（与 DesktopWorkerOrchestrator 的 deliverOversizedArtifacts 同一口径）：
     * - **meta 超卡预算即拒绝、不截断**：整卡 24 KiB 超限会被对端整帧拒收，发送侧
     *   不生产注定被拒的帧；宁可一条都不投、一条都不上卡。
     * - **没投出去的不上卡**：[send] 返回 false（节点没起 / 群不认识）时返回空清单。
     * - **本机路径不进帧**：返回的只有 (name, bytes)，路径留在 LocalFile 参数里。
     */
    suspend fun deliverStageArtifacts(
        artifacts: List<StageArtifact>,
        send: suspend (List<LocalFile>) -> Boolean,
    ): List<Pair<String, Long>> {
        if (artifacts.isEmpty()) return emptyList()
        val requests = artifacts.map {
            ArtifactPipe.PipeRequest(name = it.name, path = it.path, bytes = it.bytes)
        }
        val metaBytes = ArtifactPipe.requestsCardBytes(requests)
        if (metaBytes > ArtifactPipe.CARD_BYTES) {
            RelayLog.info("CHAIN",
                "artifact meta " + metaBytes + " B exceeds card budget " +
                    ArtifactPipe.CARD_BYTES + " B - refused (no truncation), nothing dispatched",
            )
            return emptyList()
        }
        val dispatched = runCatching {
            send(artifacts.map { LocalFile(path = it.path, name = it.name, size = it.bytes) })
        }.onFailure { t -> RelayLog.info("CHAIN", "artifact delivery failed: " + t.message) }.getOrDefault(false)
        if (!dispatched) {
            RelayLog.info("CHAIN", "artifact delivery not dispatched - " + artifacts.size + " file(s) stay on the host")
            return emptyList()
        }
        return artifacts.map { it.name to it.bytes }
    }

    /** 卡片发送方 id 里的 agentId。链不是任何一个席位，用固定串标出来源。 */
    private const val CHAIN_AGENT_ID = "tenon-chain"

    /**
     * 链 worktree 的根：系统临时目录下按产品分一层。
     *
     * 放在仓库外是硬要求 —— 放进去会被 `git status` 当成未跟踪、也会被下一棒的
     * patch 收割看见。链结束即删，不留残骸。
     */
    private fun chainWorktreeRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "tenon-chains")

    private fun isGitRepo(path: String): Boolean =
        git(path, "rev-parse", "--git-dir") != null

    /**
     * 跑一条 git 命令。成功回 stdout，**任何失败都回 null**（含超时与非零退出）。
     *
     * 超时是必须有的：`git apply` 撞上索引锁会一直等，链条就挂在那里一动不动，
     * 而卡面还停在「执行中」—— 比报错更难查。
     */
    private fun git(repoPath: String, vararg args: String): String? = runCatching {
        val proc = ProcessBuilder(listOf("git", "-C", repoPath) + args.toList())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(GIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            return@runCatching null
        }
        if (proc.exitValue() != 0) {
            RelayLog.info("CHAIN", "git " + args.joinToString(" ") + " -> " + proc.exitValue() + ": " + out.take(200))
            null
        } else {
            out
        }
    }.getOrNull()
}
