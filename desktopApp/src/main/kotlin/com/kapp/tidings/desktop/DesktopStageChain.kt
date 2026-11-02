// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.agent.AgentChainDispatcher
import com.kapp.tidings.agent.ChainProgress
import com.kapp.tidings.agent.ChainStage
import com.kapp.tidings.agent.ControlPlane
import com.kapp.tidings.agent.ReviewerAudit
import com.kapp.tidings.agent.StageChainRunner
import com.kapp.tidings.agent.StageRun
import com.kapp.tidings.domain.RelayController
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
 * ## 为什么在这里而不在 agentNode
 *
 * 这一层要读宿主仓、要起 CLI、要把卡发进群 —— 三样都只有桌面壳有。agentNode 只留
 * 纯逻辑（[com.kapp.tidings.agent.StageChain]）与 `POST /v1/agent/chain` 这道门，
 * 单进程跑测试时门后是空的（503），不会假装接了单。
 *
 * ## 三条安全线
 *
 * 1. **不碰主干**：链条从当前 HEAD 切出 `chain/<id>` 分支再提交，跑完停在分支上。
 *    机主不满意就 `git branch -D`，宿主仓的分支指针一根手指都不用动。
 * 2. **工作树必须干净才开工**：脏工作树 + `git apply` 会与既有改动混成一锅，
 *    之后谁也说不清哪个改动是哪一棒做的（文档 §4.4 的同一条纪律）。
 * 3. **一链一卡**：`cardId = chain-<id>`，rev 单调递增，走既有的 T-2d 原位替换 ——
 *    手机上同一个位置反复刷新，不刷屏。
 */
internal object DesktopStageChain {

    /** 一棒最多给下游 2000 字原文，剩下交给 [com.kapp.tidings.agent.StageChain.summarize] 压到 200。 */
    private const val LOG_TAIL_CHARS = 2000

    /** git 子进程上限：`git apply` 卡住比失败更糟（链会挂在那儿等）。 */
    private const val GIT_TIMEOUT_MS = 30_000L

    fun dispatcher(
        plane: () -> ControlPlane?,
        controller: RelayController,
        scope: CoroutineScope,
        /** L1 会话免审的查表器（桌面装配注入）：null = 没有台账，照常逐次审批。 */
        sessionExemptOf: ((sessionId: String) -> Boolean)? = null,
    ): AgentChainDispatcher = AgentChainDispatcher { groupId, repoPath, title, stages ->
        val host = plane() ?: return@AgentChainDispatcher null
        val repo = repoPath?.takeIf { it.isNotBlank() } ?: return@AgentChainDispatcher null
        if (!isGitRepo(repo)) return@AgentChainDispatcher null
        val chainId = "c" + System.currentTimeMillis().toString(36)
        scope.launch { runCatching { runChain(host, controller, chainId, groupId, repo, title, stages, sessionExemptOf) }
            .onFailure { t -> println("[CHAIN] " + chainId + " aborted: " + (t.message ?: t::class.simpleName)) } }
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
    ) {
        var rev = 0
        val statuses = stages.map { it.role to "pending" }.toMutableList()

        var pendingChecks: List<com.kapp.tidings.agent.ChainCheck> = emptyList()

        suspend fun publish(state: String, banner: String?, current: String? = null, percent: Int = 0) {
            if (groupId == null) return
            runCatching {
                controller.broadcastChainCard(
                    groupId = groupId,
                    agentId = CHAIN_AGENT_ID,
                    // Reviewer 审计结论随卡透传（checks[] 契约，宽松解析）：非合流卡
                    // pendingChecks 恒空、卡面零变化；合流卡/blocked 卡带全量审计项。
                    cardJson = com.kapp.tidings.agent.StageChain.cardJsonOf(
                        ChainProgress(chainId, ++rev, state, title, current, statuses.toList(),
                            percent, banner, checks = pendingChecks),
                    ),
                )
            }.onFailure { t -> println("[CHAIN] " + chainId + " card publish failed: " + t.message) }
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
        if (git(repo, "checkout", "-b", branch) == null) {
            publish("blocked", "Cannot checkout branch " + branch + " (already exists on " + (origin ?: "?") + "?)")
            return
        }

        val runner = StageChainRunner(
            headSha = { repoPath -> git(repoPath, "rev-parse", "HEAD")?.trim() },
            runStage = { stage, prompt, repoPath -> runOneStage(host, stage, prompt, repoPath) },
            land = { repoPath, run, message -> landStage(repoPath, run, message) },
            // Reviewer 白名单键审计的取值域清单：从 core 协议取值域对象装配（§九.1——
            // 取值域只能有一份定义，宿主引用它们而不是抄一份）。审计对 patch 中出现的
            // 键/值按清单核对；清单没覆盖的载荷形状如实 SKIP。
            valueDomains = listOf(
                ReviewerAudit.ValueDomain(
                    payload = "agent-ack",
                    keys = setOf("kind", "cardId", "actionId"),
                    valueOf = mapOf("kind" to com.kapp.tidings.core.protocol.AgentAckKinds.VALUES),
                ),
                ReviewerAudit.ValueDomain(
                    payload = "grant",
                    keys = setOf("access", "scope"),
                    valueOf = mapOf("scope" to com.kapp.tidings.core.protocol.AgentGrantScopes.VALUES),
                ),
                ReviewerAudit.ValueDomain(
                    payload = "exec-policy",
                    keys = setOf("policy"),
                    valueOf = mapOf("policy" to com.kapp.tidings.core.protocol.AgentExecPolicies.VALUES),
                ),
            ),
            report = { progress ->
                statuses.clear()
                statuses.addAll(progress.stages)
                // 合流卡 / 审计 blocked 卡上带着 checks[]：先存、随紧随其后的 publish 透传。
                pendingChecks = progress.checks
                publish(progress.state, progress.banner, progress.currentRole, progress.percent)
                // L1 会话免审的自动验讫（§八.2）：验收棒判 review_ready 且本会话免审时，
                // 逐棒检查低危判据 —— 全部低危（单测/文档/日志类）就自动合流，不再逐次
                // 弹审批。高危词一律不走免审（LowRiskReviewer 黑名单赢），判定是纯函数、
                // 两侧测试各钉一份。任何一棒不是低危 ⇒ 照常等机主 1-Click（fail-closed）。
                if (progress.state == "review_ready" && sessionExemptOf?.invoke(chainId) == true) {
                    val allLow = stages.all { stage ->
                        LowRiskReviewer.isLowRisk(riskLevel = null, summaryOrCommand = stage.instruction + " " + stage.role)
                    }
                    if (allLow) {
                        println("[CHAIN] " + chainId + " session exempt: low-risk stages auto-merged by reviewer")
                        publish("merged", "Session exempt: low-risk chain auto-merged by reviewer")
                    }
                }
            },
        )

        try {
            runner.run(chainId, repo, title, stages)
        } catch (t: Throwable) {
            publish("blocked", "Stage chain interrupted: " + (t.message ?: t::class.simpleName))
            return
        }
        println("[CHAIN] " + chainId + " finished on branch " + branch +
            " (origin " + (origin ?: "?") + " untouched)")
    }

    /**
     * 跑一棒：CLI 在独立 worktree 里干，收割出 patch 与 task.log。
     *
     * 摘要优先取 `task.log` 的尾部 —— 那是 CLI 真说过的话；只有拿不到日志时才退回
     * 事件流里的最后一段文本。**绝不**把 task.log 整篇交给下一棒（见 StageChain 的 KDoc）。
     */
    private suspend fun runOneStage(
        host: ControlPlane,
        stage: ChainStage,
        prompt: String,
        repoPath: String,
    ): StageRun {
        var patchPath: String? = null
        var lastSaid: String? = null
        val (sessionId, status, error) = host.executeCliTask(
            agentKey = stage.agentKey,
            prompt = prompt,
            wsPath = repoPath,
            requestedSessionId = null,
            onArtifact = { path, name, _, _, _ -> if (name == "patch.diff") patchPath = path },
            onEvent = { _, _, summary, body -> (body ?: summary)?.takeIf { it.isNotBlank() }?.let { lastSaid = it } },
        )
        val logTail = patchPath?.let { patch ->
            runCatching {
                File(patch).parentFile?.resolve("task.log")?.takeIf { it.isFile }
                    ?.readText()?.takeLast(LOG_TAIL_CHARS)
            }.getOrNull()
        }
        // 先把 CLI 的信封拆掉再当摘要：三个驱动的日志都是 JSON 流，直接截尾部会把
        // `"_response","text_delta"` 这类机器噪声交给下一棒（真机实测过）。
        val summary = com.kapp.tidings.agent.StageChain.saidText(logTail) ?: lastSaid
        // 「退出码 0 但一个字的输出、一个字节的改动都没有」不算做完。
        //
        // 这个形态真机见过：CLI 挂在启动提示上（读 stdin 等 EOF），被外部信号打断后以 0
        // 退出，沙箱如实报 finished、harvest 空 —— 若按 status 判，这一棒就成了「成功且
        // 无改动」，把空改动一路传给下一棒，最后整条链「全绿但什么都没干」。
        val didNothing = patchPath == null && summary.isNullOrBlank()
        // 「沙箱说没改动、宿主仓却脏了」= 这一棒越界写进了宿主工作树。
        //
        // 真机 cmvx8q5ig 第一棒实测：worktree 隔离只是 cwd，不是强制面 —— codex 从
        // worktree 的 `.git` 文件里读到 `gitdir: …/weather-relay/.git/worktrees/xxx`，
        // 顺着它认出了主干仓，于是用绝对路径直接改 `/Users/…/weather-relay/src/…`。
        // 收割如实为空（worktree 真的一字未改），链却把**旧基线**交给验收棒，
        // 最后出现「开发棒修了 AC5、验收棒验收的是没修的代码」这种假 FAIL。
        //
        // 这种形态必须停机而不是继续：继续跑只会把「谁改的、改到哪去了」变成悬案。
        val escapedWrite = patchPath == null &&
            !git(repoPath, "status", "--porcelain").isNullOrBlank()
        return StageRun(
            ok = status == "finished" && !didNothing && !escapedWrite,
            sessionId = sessionId,
            patchPath = patchPath,
            summary = summary,
            error = when {
                status != "finished" -> error ?: "cli status=" + status
                escapedWrite ->
                    "Escaped write: sandbox worktree had zero changes but host repo was modified - " +
                        "artifacts did not enter chain; continuing would verify the wrong baseline"
                didNothing -> "cli exited with no output or patch (possibly blocked on prompt/stdin/permissions)"
                else -> null
            },
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
            val patch = run.patchPath ?: return@withContext null
            val file = File(patch)
            if (!file.isFile || file.length() == 0L) return@withContext null
            git(repoPath, "apply", "--whitespace=nowarn", patch)
                ?: throw IllegalStateException("git apply failed for " + file.name + " - patch conflict with baseline")
            if (git(repoPath, "status", "--porcelain")?.isBlank() != false) return@withContext null
            git(repoPath, "add", "-A")
            git(
                repoPath,
                "-c", "user.email=chain@tidings.local",
                "-c", "user.name=tidings-chain",
                "commit", "-q", "-m", message,
            ) ?: throw IllegalStateException("git commit failed on " + repoPath)
            git(repoPath, "rev-parse", "HEAD")?.trim()
        }

    /** 卡片发送方 id 里的 agentId。链不是任何一个席位，用固定串标出来源。 */
    private const val CHAIN_AGENT_ID = "tidings-chain"

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
            println("[CHAIN] git " + args.joinToString(" ") + " -> " + proc.exitValue() + ": " + out.take(200))
            null
        } else {
            out
        }
    }.getOrNull()
}
