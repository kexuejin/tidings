// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.core.protocol.AssistantQuestNotice
import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.core.protocol.QuestStatus
import com.kapp.tenon.db.RelayDatabase
import com.kapp.tenon.domain.TicketDedupStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 支线任务真实执行体（持久 worktree 生命周期）。
 *
 * ## 它补的是哪一环
 *
 * 评审收口后，自动化任务的执行有两条路：`workspacePath` 为空 → 走 [TriggerExecutor]
 * （零 worktree，行为不变）；**绑定了工作区 → 走本 runner 的完整支线生命周期**：
 *
 * ```
 * dispatch → 幂等认领(clientToken) → git worktree add -b quest/<questId>
 *          → CLI 在 worktree 里跑（沙箱内嵌一层，产物 patch 落回 worktree 落提交）
 *          → QuestAutomationExecutor.handleQuestCompletion
 *              ├─ autoMerge=true  → QuestSerialMergeLock（rebase + 门禁 + ff 合流）
 *              └─ autoMerge=false → WAITING_INPUT 卡等机主 → confirmMerge
 *          → 终态通知原位翻面 → worktree 清场
 * ```
 *
 * 机制与责任链同源（一链一 worktree / 落提交 / 分支是唯一存世形态），`landStage`
 * 的落提交判据在这里按支线语义复刻：patch 与就地改动都收，宿主仓被动过 = 越界，
 * fail-closed。
 *
 * ## 幂等
 *
 * [clientToken]（自动化 = `automation/<taskId>/<fireAt>`，机主手动 = `manual/…`）
 * 经 [TicketDedupStore.claim] 原子认领：重放/并发重入归并到已有单，绝不重复开单 ——
 * 该防线自此有了真实生产调用方（514a6b82 的宣称落地）。
 *
 * ## 并发
 *
 * 跨支线的合流经 [mergeMutex] 全局串行（ff 合流推进主仓 HEAD，两个并发合流就是
 * 一次竞态）。per-workspace 的细粒度串行是后续优化，不是正确性前提。
 *
 * ## 已知边界（如实）
 *
 * - 注册表落库（QuestLedger，见 [database]）；DB 不可用的宿主退化为内存态并如实缺
 *   重启恢复。开机调 [recoverFromLedger]：运行中（RUNNING）的支线翻 INTERRUPTED
 *   （进程死在中途），等确认（WAITING_INPUT）的原样收编——confirmMerge 重启后照常可用。
 * - 门禁挂点（[runGateChecks]）就位，真实门禁命令是宿主配置的事——默认不跑、
 *   也不谎报「门禁全绿」。
 */
class QuestRunner(
    /** 支线 worktree 的根目录：仓库外（放进去会被 git status 当未跟踪）。 */
    private val worktreeRoot: File,
    private val onNotice: suspend (AssistantQuestNotice) -> Unit,
    /**
     * 持久台账（QuestLedger 表）：in-flight 支线的 quest_id → worktree/分支/主仓
     * 映射落库——重启后 [recoverFromLedger] 收编回内存、[confirmMerge] 照常可用。
     * null（无 SqlDelight 的宿主）= 退化为纯内存注册表，重启后 confirmMerge
     * 不可用——如实缺一块，不静默装作有。
     */
    private val database: RelayDatabase? = null,
    /** CLI 步：在支线 worktree 里跑真实任务。生产 = ControlPlane.executeCliTask；测试注入。 */
    private val runTask: suspend (questId: String, worktreeDir: File, prompt: String, agentKey: String) -> CliTaskRun,
    private val runGateChecks: suspend (worktreeDir: File) -> Boolean = { true },
    private val gitTimeoutMs: Long = 30_000L,
    /** 门禁命令的墙钟止损：门禁是测试套件，分钟级是正常量级。 */
    private val gateTimeoutMs: Long = 600_000L,
) {
    data class CliTaskRun(
        val ok: Boolean,
        val patchPath: String?,
        val summary: String?,
        val error: String?,
    )

    sealed interface DispatchResult {
        /** 已受理（终态见 [Started.terminalStatus]；null = 等机主确认中）。 */
        data class Started(val questId: String, val terminalStatus: String?) : DispatchResult

        /** 重放/并发重入：归并到已有单，本次没有开新单。 */
        data class Duplicate(val questId: String) : DispatchResult

        /** 前置校验拒绝（非 git 仓 / worktree 建不出来等）， fail-closed。 */
        data class Rejected(val reason: String) : DispatchResult
    }

    private data class QuestHandle(
        val questId: String,
        val branch: String,
        val worktreeDir: File,
        val convId: String,
        val title: String,
        val mainWorkspace: File,
        val runGateChecks: suspend (worktreeDir: File) -> Boolean,
        val prompt: String = "",
        val agentKey: String = "",
        val autoMerge: Boolean = true,
        /**
         * 派单端的便签回执键（wire 派单才有）。合流问卡之后的**批准**走
         * [confirmMerge] —— 那条路的 COMPLETED 通知也从这里拿 token，
         * 不然派单端看不到合并结果（真机实测缺陷）。
         */
        val clientToken: String? = null,
    )

    private val active = ConcurrentHashMap<String, QuestHandle>()
    private val dedup = TicketDedupStore()
    private val mergeMutex = Mutex()

    /** in-flight dispatch 的协程句柄：cancelQuest 按 questId 取消真运行（取消沿
     * 挂起链传播进沙箱看门狗，TERM 掉 CLI 进程树——WorktreeSandboxExecutor 的
     * 取消路径）。任务结束（任何终态）即摘除。 */
    private val inFlightJobs = ConcurrentHashMap<String, Job>()

    fun find(questId: String): Boolean = active.containsKey(questId)

    // ------------------------------------------------------------------ 持久台账

    private suspend fun ledgerSave(handle: QuestHandle, status: String) {
        database ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                database.relayDatabaseQueries.insertOrReplaceQuestLedger(
                    quest_id = handle.questId,
                    branch = handle.branch,
                    worktree_path = handle.worktreeDir.absolutePath,
                    main_workspace = handle.mainWorkspace.absolutePath,
                    conv_id = handle.convId,
                    title = handle.title,
                    status = status,
                    prompt = handle.prompt,
                    agent_key = handle.agentKey,
                    client_token = handle.clientToken,
                    auto_merge = if (handle.autoMerge) 1L else 0L,
                    updated_at = com.kapp.tenon.core.nowMillis(),
                )
            }.onFailure {
                RelayLog.info("RELAY-QUEST", "quest ledger save failed for " + handle.questId + ": " + (it.message ?: "unknown"))
            }
        }
    }

    private suspend fun ledgerUpdateStatusRaw(questId: String, status: String) {
        database ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                database.relayDatabaseQueries.updateQuestLedgerStatus(
                    status = status,
                    updated_at = com.kapp.tenon.core.nowMillis(),
                    quest_id = questId,
                )
            }
        }
    }

    private suspend fun ledgerUpdateStatus(handle: QuestHandle, status: String) {
        database ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                database.relayDatabaseQueries.updateQuestLedgerStatus(
                    status = status,
                    updated_at = com.kapp.tenon.core.nowMillis(),
                    quest_id = handle.questId,
                )
            }
        }
    }

    /**
     * 开机恢复：把台账里的 in-flight 支线收编回内存。
     *
     * - RUNNING（进程死在任务中途）→ 翻 INTERRUPTED 并通知；worktree 保留（可能
     *   有未落提交的 CLI 现场供取证），重跑属后续「一键恢复」的范围。
     * - WAITING_INPUT（等机主确认合流）→ 原样收编：confirmMerge 重启后照常可用——
     *   这是持久台账换来的核心能力。
     *
     * @return 收编的支线数（含翻面）。
     */
    suspend fun recoverFromLedger(): Int {
        val db = database ?: return 0
        val rows = withContext(Dispatchers.IO) {
            runCatching { db.relayDatabaseQueries.selectAllQuestLedgers().executeAsList() }
                .onFailure { RelayLog.info("RELAY-QUEST", "quest ledger recovery failed: " + (it.message ?: "unknown")) }
                .getOrElse { emptyList() }
        }
        var recovered = 0
        for (row in rows) {
            if (active.containsKey(row.quest_id)) continue
            val worktree = File(row.worktree_path)
            active[row.quest_id] = QuestHandle(
                questId = row.quest_id,
                branch = row.branch,
                worktreeDir = worktree,
                convId = row.conv_id,
                title = row.title,
                mainWorkspace = File(row.main_workspace),
                runGateChecks = runGateChecks,
                clientToken = row.client_token,
            )
            recovered++
            when (row.status) {
                QuestStatus.RUNNING.name -> {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            db.relayDatabaseQueries.updateQuestLedgerStatus(
                                status = QuestStatus.INTERRUPTED.name,
                                updated_at = com.kapp.tenon.core.nowMillis(),
                                quest_id = row.quest_id,
                            )
                        }
                    }
                    onNotice(
                        AssistantQuestNotice(
                            questId = row.quest_id,
                            title = row.title,
                            status = QuestStatus.INTERRUPTED,
                            summary = "Quest was interrupted by a restart; the worktree is kept for inspection.",
                            convId = row.conv_id,
                            rev = 0L,
                        ),
                    )
                }
                // WAITING_INPUT 等确认态原样收编（confirmMerge 重启后可用）；其余状态
                // 收编但不再翻面——它们要么已在卡面有终态，要么等机主动作。
                else -> Unit
            }
        }
        return recovered
    }

    /**
     * 派一条支线。**绝不抛业务异常**（取消除外）：失败以 FAILED 通知 + [DispatchResult.Started]
     * 的终态返回，让调用方（调度器/手动触发）总能拿到一个可记账的结局。
     */
    suspend fun dispatch(
        prompt: String,
        workspacePath: String,
        title: String,
        agentKey: String,
        autoMerge: Boolean,
        clientToken: String? = null,
        convId: String = ConvId.ASSISTANT,
        /** 宿主配置的门禁命令（如 `./gradlew test`）：非空 = 合流前在 worktree 里执行，退出 0 才放行。 */
        gateCommand: String? = null,
    ): DispatchResult {
        val ws = File(workspacePath)
        if (!ws.isDirectory || !File(ws, ".git").exists()) {
            return DispatchResult.Rejected("workspace is not a git repository: " + workspacePath)
        }

        val questId: String
        if (clientToken != null) {
            val claimed = dedup.claim(clientToken) { newQuestId() }
            if (claimed != null) return DispatchResult.Duplicate(claimed)
            questId = dedup.findQuestId(clientToken)!!
        } else {
            questId = newQuestId()
        }
        val noticeToken = clientToken

        notify(questId, title, QuestStatus.RUNNING, prompt.take(160), rev = 0L, noticeClientToken = noticeToken)

        val branch = "quest/" + questId
        val worktree = File(worktreeRoot, questId)
        val effectiveGates: suspend (File) -> Boolean = if (gateCommand.isNullOrBlank()) {
            runGateChecks
        } else {
            { dir -> executeGateCommand(dir, gateCommand) }
        }
        val created = git(ws, "worktree", "add", "-b", branch, worktree.absolutePath, "HEAD")
        if (created.exitCode != 0) {
            // 台账行先落（FAILED + 原 prompt/agentKey/clientToken）：没有行，重试
            // 按钮就是静默 no-op（resumeQuest 查无此行）。此前这条路径连行都没写。
            ledgerSave(
                QuestHandle(
                    questId = questId,
                    branch = branch,
                    worktreeDir = worktree,
                    convId = convId,
                    title = title,
                    mainWorkspace = ws,
                    runGateChecks = effectiveGates,
                    prompt = prompt,
                    agentKey = agentKey,
                    autoMerge = autoMerge,
                    clientToken = noticeToken,
                ),
                QuestStatus.FAILED.name,
            )
            notify(questId, title, QuestStatus.FAILED, "worktree create failed: " + created.stderr.takeLast(300).trim(), rev = 1L, noticeClientToken = noticeToken)
            return DispatchResult.Rejected("worktree create failed: " + created.stderr.takeLast(200).trim())
        }

        val handle = QuestHandle(
            questId = questId,
            branch = branch,
            worktreeDir = worktree,
            convId = convId,
            title = title,
            mainWorkspace = ws,
            runGateChecks = effectiveGates,
            prompt = prompt,
            agentKey = agentKey,
            autoMerge = autoMerge,
            clientToken = noticeToken,
        )
        active[questId] = handle
        ledgerSave(handle, QuestStatus.RUNNING.name)

        try {
            inFlightJobs[questId] = coroutineContext.job
            try {
                // clientToken 必须跟到执行半边：RUNNING 之后的所有终态通知
                // （FAILED / 无产出 / 合流问卡 / COMPLETED）都在 executeQuestLifecycle
                // 里发 —— 不带 token，onNotice 的回执查找就落空，派单端的便签永远
                // 停在 RUNNING（真机实测：桌面卡翻了 failed，手机卡纹丝不动）。
                val terminal = executeQuestLifecycle(handle, prompt, agentKey, autoMerge, noticeToken)
                return DispatchResult.Started(questId, terminalStatus = terminal)
            } finally {
                inFlightJobs.remove(questId)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                notify(questId, title, QuestStatus.CANCELLED, "Quest cancelled.", rev = 1L, noticeClientToken = noticeToken)
                cleanupTerminal(handle, removeBranch = false)
            }
            throw e
        } catch (t: Throwable) {
            notify(questId, title, QuestStatus.FAILED, (t.message ?: t::class.simpleName ?: "unknown").take(200), rev = 1L, noticeClientToken = noticeToken)
            ledgerUpdateStatus(handle, QuestStatus.FAILED.name)
            cleanupTerminal(handle, removeBranch = false, keepLedgerForRetry = true)
            return DispatchResult.Started(questId, terminalStatus = "FAILED")
        }
    }

    /** 测试专用：读一条台账行（FAILED 留存/重试链路的断言面）。 */
    suspend fun ledgerRowForTest(questId: String): com.kapp.tenon.db.QuestLedger? {
        val db = database ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { db.relayDatabaseQueries.selectQuestLedgerById(quest_id = questId).executeAsOneOrNull() }
                .getOrNull()
        }
    }

    /** dispatch 的执行半边（worktree 已就位、句柄已登记）：跑 → 落提交 → 合流。 */
    private suspend fun executeQuestLifecycle(
        handle: QuestHandle,
        prompt: String,
        agentKey: String,
        autoMerge: Boolean,
        noticeClientToken: String? = null,
    ): String? {
        val questId = handle.questId
        val title = handle.title
        val worktree = handle.worktreeDir
        val ws = handle.mainWorkspace
        // 机主主仓在任务开始前的脏态：结束后对比，判定「越界写」而不是误判。
        val hostDirtyBefore = git(ws, "status", "--porcelain").stdout.isNotBlank()

        val run = runTask(questId, worktree, prompt, agentKey)
        val committed = landCommit(worktree, run.patchPath, title)

        // 越界判据（与责任链同源）：worktree 毫无动静 + 主仓从干净变脏 = CLI 顺着
        // .git 指针改了宿主仓 —— 沙箱隔离被穿墙，绝不把它当成功合流。
        val worktreeQuiet = committed == null
        val hostDirtyAfter = git(ws, "status", "--porcelain").stdout.isNotBlank()
        val escapedWrite = worktreeQuiet && !hostDirtyBefore && hostDirtyAfter

        if (!run.ok || escapedWrite) {
            val reason = if (escapedWrite) {
                "Escaped write: host repo was modified outside the quest worktree"
            } else {
                run.error ?: "cli task failed"
            }
            notify(questId, title, QuestStatus.FAILED, reason, rev = 1L, noticeClientToken = noticeClientToken)
            ledgerUpdateStatus(handle, QuestStatus.FAILED.name)
            cleanupTerminal(handle, removeBranch = false, keepLedgerForRetry = true)
            return "FAILED"
        }
        if (committed == null) {
            // 「退出码 0 但什么都没干」不算做完（责任链同判据）：没有提交就没有
            // 可合流的东西，报 COMPLETED 是假绿 —— 本仓最贵的那类缺陷。
            notify(
                questId, title, QuestStatus.FAILED,
                "Quest produced no changes to merge (cli exited without output or patch).",
                rev = 1L,
                noticeClientToken = noticeClientToken,
            )
            ledgerUpdateStatus(handle, QuestStatus.FAILED.name)
            cleanupTerminal(handle, removeBranch = false, keepLedgerForRetry = true)
            return "FAILED"
        }

        val terminal = mergeMutex.withLock {
            completeQuest(handle, worktree, autoMerge, run.summary ?: "", noticeClientToken)
        }
        return terminal
    }

    /**
     * 门禁命令：宿主配置的一条 shell 命令，在支线 worktree 里执行，退出 0 = 放行。
     * 输出尾巴留宿主日志（卡面只报过/不过——长输出按需看日志，不进信令）。
     */
    private suspend fun executeGateCommand(worktree: File, command: String): Boolean =
        withContext(Dispatchers.IO) {
            val proc = ProcessBuilder(listOf("/bin/sh", "-c", command))
                .directory(worktree)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(gateTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val output = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")
            if (!finished) {
                proc.destroyForcibly()
                RelayLog.info("RELAY-QUEST", "gate command timed out after " + gateTimeoutMs + " ms: " + command)
                return@withContext false
            }
            val ok = proc.exitValue() == 0
            if (!ok) {
                RelayLog.info("RELAY-QUEST", "gate command failed (exit " + proc.exitValue() + "): " + output.takeLast(500))
            }
            ok
        }

    /**
     * 机主批准合流（merge-patch 卡片动作 → 端口）：对确认模式（autoMerge=false）
     * 停在 WAITING_INPUT 的支线执行真合流。
     */
    suspend fun confirmMerge(questId: String): Boolean {
        val handle = active[questId] ?: return false
        ledgerUpdateStatus(handle, QuestStatus.RUNNING.name)
        val terminal = mergeMutex.withLock {
            completeQuest(handle, handle.worktreeDir, autoMerge = true, summary = "", handle.clientToken)
        }
        return terminal == "COMPLETED"
    }

    /**
     * 机主主动叫停。in-flight（CLI 还在跑）→ 取消 dispatch 协程：取消沿挂起链传播
     * 进沙箱看门狗，TERM 掉 CLI 进程树（WorktreeSandboxExecutor 的取消路径），
     * dispatch 的 CancellationException 分支负责 CANCELLED 通知与清场；已停在
     * 等确认/阻断态的 → 直接清场并通知。
     */
    suspend fun cancelQuest(questId: String, reason: String = "Cancelled by owner."): Boolean {
        inFlightJobs[questId]?.let { job ->
            job.cancel()
            return true
        }
        val handle = active.remove(questId) ?: return false
        notify(questId, handle.title, QuestStatus.CANCELLED, reason, rev = 1L, noticeClientToken = handle.clientToken)
        cleanupTerminal(handle, removeBranch = false)
        return true
    }

    /**
     * 一键恢复：INTERRUPTED（重启打断）/ FAILED 的支线从台账拿 prompt/agentKey
     * **原 questId 重跑**（卡面链路不断，通知原位翻面）。旧现场（worktree/分支）
     * 先清场再从头建。PAUSED 等确认态不走这里——那是 [confirmMerge] 的语义。
     *
     * 门禁用宿主默认挂点（原任务的 gateCommand 未持久化，见类 KDoc 边界）。
     */
    suspend fun resumeQuest(questId: String, autoMerge: Boolean = true): Boolean {
        val db = database ?: return false
        if (inFlightJobs.containsKey(questId) || active.containsKey(questId)) return false
        val row = withContext(Dispatchers.IO) {
            runCatching { db.relayDatabaseQueries.selectQuestLedgerById(quest_id = questId).executeAsOneOrNull() }
                .getOrNull()
        } ?: return false
        // PAUSED（主仓脏阻断）等确认态：恢复 = 重新走合流（worktree 原样在）。
        if (row.status == QuestStatus.PAUSED.name) return confirmMerge(questId)
        if (row.status != QuestStatus.INTERRUPTED.name && row.status != QuestStatus.FAILED.name) return false
        if (row.prompt.isBlank()) {
            // 台账行没有 prompt 就没有重跑的输入——如实拒绝，不拿空 prompt 假跑。
            RelayLog.info("RELAY-QUEST", "quest " + questId + " resume rejected: ledger prompt is blank")
            return false
        }
        val ws = File(row.main_workspace)
        val worktree = File(row.worktree_path)
        // 旧现场清场：worktree 里可能有未落提交的 CLI 改动，重跑必须从头来。
        if (worktree.exists()) {
            git(ws, "worktree", "remove", "--force", worktree.absolutePath)
        }
        git(ws, "worktree", "prune")
        git(ws, "branch", "-D", row.branch)

        notify(questId, row.title, QuestStatus.RUNNING, "Resuming quest from ledger.", rev = 0L)
        // 重跑 = 全新现场：worktree 与分支都以同名重建（旧的上一步已清）。
        val created = git(ws, "worktree", "add", "-b", row.branch, worktree.absolutePath, "HEAD")
        if (created.exitCode != 0) {
            notify(questId, row.title, QuestStatus.FAILED, "worktree recreate failed: " + created.stderr.takeLast(300).trim(), rev = 1L)
            ledgerUpdateStatusRaw(questId, QuestStatus.FAILED.name)
            return false
        }
        val handle = QuestHandle(
            questId = questId,
            branch = row.branch,
            worktreeDir = worktree,
            convId = row.conv_id,
            title = row.title,
            mainWorkspace = ws,
            runGateChecks = runGateChecks,
            prompt = row.prompt,
            agentKey = row.agent_key,
            autoMerge = autoMerge,
            clientToken = row.client_token,
        )
        active[questId] = handle
        ledgerSave(handle, QuestStatus.RUNNING.name)
        try {
            inFlightJobs[questId] = coroutineContext.job
            try {
                executeQuestLifecycle(handle, row.prompt, row.agent_key, autoMerge)
                return true
            } finally {
                inFlightJobs.remove(questId)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                notify(questId, row.title, QuestStatus.CANCELLED, "Quest cancelled.", rev = 1L)
                cleanupTerminal(handle, removeBranch = false)
            }
            throw e
        } catch (t: Throwable) {
            notify(questId, row.title, QuestStatus.FAILED, (t.message ?: "unknown").take(200), rev = 1L)
            ledgerUpdateStatus(handle, QuestStatus.FAILED.name)
            cleanupTerminal(handle, removeBranch = false, keepLedgerForRetry = true)
            return false
        }
    }

    /**
     * 合流（[QuestAutomationExecutor] 是唯一执行者）。返回主仓可记账的终态：
     * COMPLETED / FAILED（终态，清场）；PAUSED / CONFLICT（等机主，worktree 保留）。
     */
    private suspend fun completeQuest(
        handle: QuestHandle,
        worktree: File,
        autoMerge: Boolean,
        summary: String,
        noticeClientToken: String? = null,
    ): String? {
        val automation = QuestAutomationExecutor(
            mergeLock = QuestSerialMergeLock(
                mainWorkspace = handle.mainWorkspace,
                runGateChecks = handle.runGateChecks,
                gitTimeoutMs = gitTimeoutMs,
            ),
            onNotice = onNotice,
            clientToken = noticeClientToken,
        )
        val outcome = automation.handleQuestCompletion(
            questId = handle.questId,
            convId = handle.convId,
            title = handle.title,
            worktreeDir = worktree,
            autoMerge = autoMerge,
            summary = summary,
        )
        return when (outcome) {
            is QuestAutomationExecutor.AutomationOutcome.Merged -> {
                cleanupTerminal(handle, removeBranch = true)
                "COMPLETED"
            }
            is QuestAutomationExecutor.AutomationOutcome.Failed -> {
                // 合流阶段失败也是 FAILED 家族：台账留存（重跑数据源）+ 状态与通知
                // 一致——与 dispatch 级 FAILED 路径同规（review #4 的完整补齐）。
                ledgerUpdateStatus(handle, QuestStatus.FAILED.name)
                cleanupTerminal(handle, removeBranch = false, keepLedgerForRetry = true)
                "FAILED"
            }
            is QuestAutomationExecutor.AutomationOutcome.AwaitingConfirmation -> {
                ledgerUpdateStatus(handle, QuestStatus.WAITING_INPUT.name)
                null
            }
            is QuestAutomationExecutor.AutomationOutcome.HostWorkspaceDirty -> {
                ledgerUpdateStatus(handle, QuestStatus.PAUSED.name)
                "PAUSED"
            }
            is QuestAutomationExecutor.AutomationOutcome.Conflict -> {
                ledgerUpdateStatus(handle, QuestStatus.CONFLICT.name)
                "CONFLICT"
            }
        }
    }

    /**
     * 把 CLI 产物落成 quest 分支上的**真提交**（判据与责任链 [DesktopStageChain] 的
     * landStage 同源）：patch 先 apply、就地改动直接收；没有改动 = null（不是失败）。
     */
    private suspend fun landCommit(worktree: File, patchPath: String?, message: String): String? =
        withContext(Dispatchers.IO) {
            patchPath?.let { path ->
                val patch = File(path)
                if (patch.isFile && patch.length() > 0L) {
                    val applied = git(worktree, "apply", "--whitespace=nowarn", patch.absolutePath)
                    if (applied.exitCode != 0) {
                        throw IllegalStateException("git apply failed for patch: " + applied.stderr.takeLast(300).trim())
                    }
                }
            }
            val dirty = git(worktree, "status", "--porcelain")
            if (dirty.stdout.isBlank()) return@withContext null
            git(worktree, "add", "-A")
            val commit = git(
                worktree,
                "-c", "user.email=quest@tenon.local",
                "-c", "user.name=tenon-quest",
                "commit", "-q", "-m", message,
            )
            if (commit.exitCode != 0) {
                throw IllegalStateException("git commit failed on " + worktree.absolutePath + ": " + commit.stderr.takeLast(200).trim())
            }
            git(worktree, "rev-parse", "HEAD").stdout.trim()
        }

    /**
     * 终态清场：worktree 无痕移除（`git worktree remove` 是 git 原生清场，**不是**
     * `deleteRecursively` —— §2.8 的铁律在这里同样成立）。分支：合流成功后删除
     * （已并入 main，留着是误导）；失败/叫停保留（取证与重试的现场）。
     */
    private suspend fun cleanupTerminal(
        handle: QuestHandle,
        removeBranch: Boolean,
        keepLedgerForRetry: Boolean = false,
    ) = withContext(NonCancellable + Dispatchers.IO) {
        active.remove(handle.questId)
        // FAILED 支线**保留台账行**（prompt/agentKey 都在）：FAILED 卡的「重新运行」
        // 按钮走台账原 questId 重跑，行删了按钮就变成静默 no-op（真机实测：点重试
        // 后卡停在「已提交等待宿主确认」，桌面端 resumeQuest 查无此行直接返回）。
        // COMPLETED/CANCELLED 没有重试语义，照旧删行。
        if (!keepLedgerForRetry) {
            runCatching { database?.relayDatabaseQueries?.deleteQuestLedgerById(quest_id = handle.questId) }
        }
        git(handle.mainWorkspace, "worktree", "remove", "--force", handle.worktreeDir.absolutePath)
        git(handle.mainWorkspace, "worktree", "prune")
        if (removeBranch) {
            git(handle.mainWorkspace, "branch", "-D", handle.branch)
        }
    }

    private suspend fun notify(
        questId: String,
        title: String,
        status: QuestStatus,
        summary: String,
        rev: Long,
        questionText: String? = null,
        noticeClientToken: String? = null,
    ) {
        onNotice(
            AssistantQuestNotice(
                questId = questId,
                title = title,
                status = status,
                summary = summary,
                convId = ConvId.ASSISTANT,
                questionText = questionText,
                // 便签闭环：派发时的 clientToken 随通知回去，消费侧据此把机主的
                // 离线便签原位翻面成支线卡（bindPendingNoteToQuest），消除双卡片。
                clientToken = noticeClientToken,
                rev = rev,
            ),
        )
    }



    private fun newQuestId(): String = "q" + UUID.randomUUID().toString().replace("-", "").take(12)

    private suspend fun git(cwd: File, vararg args: String): QuestGitCli.GitOutput =
        QuestGitCli.run(cwd, timeoutMs = gitTimeoutMs, args = args)
}
