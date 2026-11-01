// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import com.kapp.tidings.core.nowMillis
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Git Worktree 沙箱执行器（升级轮 T-3b，D-024）。
 *
 * ## 它解决什么
 *
 * 群内 `@agent` 派的活以前直接落在宿主仓的工作树上 —— 一次未经审阅的重构就是一次
 * 未提交的污染。沙箱语义（D-024）：每个任务在宿主仓的**独立 worktree**（独立分支
 * `task-<taskId>`）里执行；宿主工作树零变化；任务结束提取 `git diff` 作为交付物；
 * 任务面（worktree + 分支）用完**无痕清理**。
 *
 * ## 产物提纯（detailed-design §7.7，两层）
 *
 * - **语义摘要层**：report.json 是结构化事实（终态 / 文件数 / +A −D / 时长），
 *   **绝不内联 CLI 原始输出** —— 上游消费者（卡片 / 群流）只读摘要，不读日志；
 * - **数据指针层**：全量 patch 落 `patch.diff`、原始输出落 `task.log`，report 里
 *   只放它们的 SHA256 与路径 —— 长内容按需取，不进信令。
 *
 * 产物目录在**沙箱外**（`<root>/artifacts/<taskId>/`）—— 无痕清理清的是工作面，
 * 不是交付物。
 *
 * ## 中止（D-024）的两条触发路径，同一段收拾现场
 *
 * 1. 外部 [abort]（宿主 HTTP 面）：SIGTERM → 至多 3s → SIGKILL → 清理 → 终局
 *    [Outcome.Aborted]；
 * 2. **协程取消**（租约超时的真实路径 —— `ChannelOrchestrator` 的 deadline 出队取消的
 *    正是这条挂起链）：输出行的读取是**阻塞 IO**，不响应取消 —— 挂在调用方 Job 上的
 *    `invokeOnCompletion` 看门狗在取消那一刻 TERM 进程树（宽限后由内部 watchdog
 *    SIGKILL），阻塞读随进程死亡解开，清理照跑。取消路径的现场收拾在
 *    `NonCancellable` 里 —— 取消不豁免收拾。
 *
 * ## 大声失败
 *
 * 宿主路径不是 git 仓 / worktree 建不出来 = [Outcome.Failed]（带 git 的 stderr
 * 尾巴）；taskId 是路径段与分支名，越界形状（穿越 / 分隔符 / 超长）在任何 git
 * 调用之前就抛 —— 一个未校验的 taskId 能写出仓外的路径，这不是沙箱。
 */
class WorktreeSandboxExecutor(
    /** 沙箱根目录：worktree 与产物都住它下面。生产 = env 覆盖或公共约定目录，测试注入临时目录。 */
    private val sandboxRoot: File,
    private val git: GitRunner = ProcessGitRunner,
    private val clock: () -> Long = { nowMillis() },
) {

    /** taskId → 在跑的进程。abort 与看门狗按它寻址。 */
    private val running = ConcurrentHashMap<String, Process>()

    /** taskId → 已被要求中止（决定退出码非 0 时报 Failed 还是 Aborted）。 */
    private val aborted = ConcurrentHashMap.newKeySet<String>()

    /** taskId → 宿主仓（清理按它找回 `git worktree remove` 的落点）。 */
    private val hostRepos = ConcurrentHashMap<String, String>()

    /** 一条任务的全量交付物（无改动 = 无 diff 可交付，为 null）。 */
    data class Harvest(
        val filesChanged: Int,
        val additions: Int,
        val deletions: Int,
        val patchSha256: String,
        val logSha256: String,
        val patchPath: File,
        val reportPath: File,
        val logPath: File,
    )

    sealed interface Outcome {
        /** 退出码 0；[harvest] 为 null = 没有改动（不为没发生的活出 patch）。 */
        data class Finished(val harvest: Harvest?) : Outcome

        /** 退出码非 0：失败任务不产出交付物。 */
        data class Failed(val exitCode: Int, val message: String) : Outcome

        /** 被 [abort] 或协程取消中止（清理已跑完）。 */
        data class Aborted(val message: String) : Outcome
    }

    /**
     * 在独立 worktree 里执行一条命令。[onLine] 收原始输出行（信令走这里，文件走
     * task.log）。执行期间协程被取消 = 触发中止路径并上抛取消。
     */
    suspend fun run(
        taskId: String,
        hostRepo: String,
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        onLine: suspend (String) -> Unit,
    ): Outcome {
        require(TASK_ID.matches(taskId)) {
            "taskId \"$taskId\" must match ${TASK_ID.pattern} — it becomes a path segment and a branch name"
        }
        val worktree = File(sandboxRoot, taskId)
        val artifactsDir = File(File(sandboxRoot, ARTIFACTS_DIR), taskId).apply { mkdirs() }
        hostRepos[taskId] = hostRepo
        try {
            // worktree add：hostRepo 不是 git 仓 / 分支已存在 / 路径被占都会在这里炸，
            // stderr 尾巴随 Failed 上去 —— 大声失败，不静默回退到裸跑。
            var createdResult = git.run(
                listOf("worktree", "add", "-b", branchOf(taskId), worktree.absolutePath),
                cwd = File(hostRepo),
            )
            if (!createdResult.ok && createdResult.stderr.contains("already")) {
                // 一次性自愈（T-G11a）：上次异常留下的同名 worktree/分支（进程被
                // SIGKILL 时无痕清理没跑到）。与 cleanupTask 同序：remove --force
                // （它可能是**已注册**的工作树，prune 只清幽灵注册）→ prune →
                // branch -D → 重试一次；再失败才是真冲突（如实 Failed，不无限重试）。
                if (worktree.exists()) {
                    git.run(listOf("worktree", "remove", "--force", worktree.absolutePath), cwd = File(hostRepo))
                }
                git.run(listOf("worktree", "prune"), cwd = File(hostRepo))
                git.run(listOf("branch", "-D", branchOf(taskId)), cwd = File(hostRepo))
                createdResult = git.run(
                    listOf("worktree", "add", "-b", branchOf(taskId), worktree.absolutePath),
                    cwd = File(hostRepo),
                )
            }
            if (!createdResult.ok) {
                return Outcome.Failed(
                    -1,
                    "sandbox worktree create failed: " + createdResult.stderr.takeLast(400).trim(),
                )
            }
            val started = clock()
            val logFile = File(artifactsDir, "task.log")
            val exitCode = withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(command).directory(worktree)
                env.forEach { (k, v) -> pb.environment()[k] = v }
                // 同 CliSubprocessDriver：GUI 启动的宿主只有最小 PATH，脚本型 CLI
                // （`#!/usr/bin/env node`）会找不到解释器 → 127。env 显式给了 PATH 就不动它。
                if (env["PATH"] == null) pb.environment()["PATH"] = CliDiscovery.childPath(pb.environment())
                pb.redirectErrorStream(true)
                val proc = pb.start()
                // 同 CliSubprocessDriver：stdin 不关 = 一条永不 EOF 的管道，读 stdin 的
                // CLI（codex）会在这里静默挂死。prompt 走 argv，不需要 stdin。
                runCatching { proc.outputStream.close() }
                running[taskId] = proc
                // 读行不在当前线程上阻塞：子进程的输出由专用读线程灌进队列，本协程
                // 每 ≤100ms 醒一次（poll 超时）做 `ensureActive()` —— 取消在百毫秒级
                // 被观测，而不是等 30s 的阻塞 readLine 解锁（它不响应取消）。读线程
                // 退出以 POISON 哨兵收尾，正常路径也是它解锁主循环。
                val queue = LinkedBlockingQueue<Any?>()
                val reader = Thread {
                    try {
                        proc.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { queue.put(it) }
                        }
                    } finally {
                        queue.put(POISON)
                    }
                }.apply { isDaemon = true; name = "sandbox-$taskId-reader"; start() }
                val exit = logFile.bufferedWriter().use { log ->
                    try {
                        while (true) {
                            ensureActive()
                            val line = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                            if (line === POISON) break
                            log.appendLine(line as String)
                            onLine(line)
                        }
                        proc.waitFor()
                    } finally {
                        // 取消也要等到真死：不等 waitFor 的自然退出 —— 进程树摘掉后
                        // waitFor 才会返回（读线程随 EOF 结束）。
                        if (proc.isAlive) {
                            aborted.add(taskId)
                            proc.descendants().forEach { it.destroy() }
                            proc.destroy()
                        }
                        reader.join(2_000)
                    }
                }
                exit
            }
            running.remove(taskId)
            return when {
                aborted.remove(taskId) -> {
                    cleanupTask(taskId)
                    Outcome.Aborted("task aborted (SIGTERM/SIGKILL)")
                }
                exitCode == 0 -> {
                    val harvest = harvestAndWrite(taskId, worktree, artifactsDir, logFile, started)
                    cleanupTask(taskId)
                    Outcome.Finished(harvest)
                }
                else -> {
                    // 同一条纪律：非零退出也是一条**要看得见**的结局。CLI 起不来（账号/网络/
                    // 配额）时这一支原来静默清理，外面只看到「没有产物」，分不清是没人干活
                    // 还是干完了没收成 —— task.log 里才有真因（实测：上游 Bad Gateway）。
                    note(logFile, "[sandbox] task $taskId failed: cli exit=$exitCode (cause in task.log)")
                    cleanupTask(taskId)
                    Outcome.Failed(exitCode, "cli exited with code $exitCode in sandbox")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消落在轮询挂起点上的路径：D-024 中止序（SIGTERM → 3s → SIGKILL）+
            // 现场收拾在 NonCancellable 里跑，再如实上抛 —— 调用方自己知道是它取消的。
            withContext(NonCancellable) {
                running.remove(taskId)?.let { proc ->
                    aborted.add(taskId)
                    destroyProcessTree(proc)
                }
                cleanupTask(taskId)
            }
            throw e
        } finally {
            running.remove(taskId)
            hostRepos.remove(taskId)
        }
    }

    /** 外部中止（宿主 HTTP 面）。返回是否命中了一个在跑的任务。 */
    suspend fun abort(taskId: String): Boolean {
        val proc = running[taskId] ?: return false
        aborted.add(taskId)
        destroyProcessTree(proc)
        return true
    }

    /** worktree + 分支无痕清理（幂等：已不在时 git 报错被吞 —— 目标态是「不在」，
     *  但非「已不在」的失败要大声打印 —— 静默吞掉会让「无痕」变成没人验过的口号）。 */
    private fun cleanupTask(taskId: String) {
        val host = hostRepos[taskId] ?: return
        val worktree = File(sandboxRoot, taskId)
        if (worktree.exists()) {
            gitQuiet(listOf("worktree", "remove", "--force", worktree.absolutePath), host)
        }
        gitQuiet(listOf("worktree", "prune"), host)
        gitQuiet(listOf("branch", "-D", branchOf(taskId)), host)
    }

    private fun gitQuiet(args: List<String>, host: String) {
        val r = git.run(args, cwd = File(host))
        if (!r.ok && r.stderr.contains("not found").not()) {
            println("[sandbox] git ${args.joinToString(" ")} failed: ${r.stderr.takeLast(300).trim()}")
        }
    }

    /**
     * 诊断行**同时**写进 `task.log`：桌面端被 `open`/GUI 拉起时 stdout 没有人接（实测
     * `open --stdout` 不落盘），外部取证只剩产物目录。结论必须跟着产物走，否则同一句
     * 「没有交付物」在不同启动方式下一个看得见、一个看不见。
     */
    private fun note(logFile: File, line: String) {
        println(line)
        runCatching { logFile.appendText(line + "\n") }
    }

    /** `git diff HEAD` 提纯成 patch + report（两个文件 + 结构化摘要），写到产物目录。 */
    private fun harvestAndWrite(
        taskId: String,
        worktree: File,
        artifactsDir: File,
        logFile: File,
        started: Long,
    ): Harvest? {
        // CLI 新建的文件是 untracked —— 裸 `git diff` 看不见它们。worktree 是一次性
        // 工作面，先把一切 stage 进临时索引再取 stat 与 diff（两处都要在 add 之后，
        // 先 stat 后 add 会把统计读成全零 —— 提纯返回 null 的假空）。
        val add = git.run(listOf("add", "-A"), cwd = worktree)
        val stat = git.run(listOf("diff", "--stat", "HEAD"), cwd = worktree)
        val patch = git.run(listOf("diff", "HEAD"), cwd = worktree)
        if (!patch.ok) {
            // 这两条 null 分支原先**一行都不打** —— 「没有交付物」与「收割失败」在外部
            // 完全同形，一次 16 分钟的真实执行因此无法归因（幕 4 实测踩到）。
            note(
                logFile,
                "[sandbox] harvest $taskId: git diff HEAD failed exit=" + patch.exitCode +
                    " stderr=" + patch.stderr.takeLast(300).replace("\n", " "),
            )
            return null // 没有可 diff 的基线 = 没有交付物，如实为空（不是失败）。
        }
        val (files, adds, dels) = parseStat(stat.stdout)
        if (files == 0 && adds == 0 && dels == 0) {
            note(
                logFile,
                "[sandbox] harvest $taskId: nothing to harvest — add.exit=" + add.exitCode +
                    " add.stderr=" + add.stderr.takeLast(200).replace("\n", " ") +
                    " stat=" + stat.stdout.takeLast(200).replace("\n", " ") +
                    " patchBytes=" + patch.stdout.length,
            )
            return null
        }
        val patchFile = File(artifactsDir, "patch.diff").apply { writeText(patch.stdout) }
        val reportFile = File(artifactsDir, "report.json")
        val report: JsonObject = buildJsonObject {
            put("taskId", JsonPrimitive(taskId))
            put("outcome", JsonPrimitive("finished"))
            put("exitCode", JsonPrimitive(0))
            put("durationMs", JsonPrimitive(clock() - started))
            put("filesChanged", JsonPrimitive(files))
            put("additions", JsonPrimitive(adds))
            put("deletions", JsonPrimitive(dels))
            put("patchPath", JsonPrimitive(patchFile.absolutePath))
            put("patchSha256", JsonPrimitive(sha256Hex(patchFile)))
            put("logPath", JsonPrimitive(logFile.absolutePath))
            put("logSha256", JsonPrimitive(sha256Hex(logFile)))
        }
        reportFile.writeText(report.toString())
        note(logFile, "[sandbox] harvest $taskId: +$adds -$dels in $files file(s), patch=${patchFile.length()} B")
        return Harvest(
            filesChanged = files,
            additions = adds,
            deletions = dels,
            patchSha256 = (report["patchSha256"] as JsonPrimitive).content,
            logSha256 = (report["logSha256"] as JsonPrimitive).content,
            patchPath = patchFile,
            reportPath = reportFile,
            logPath = logFile,
        )
    }

    /** 进程树 SIGTERM → 至多 3s → SIGKILL（D-024 的中止序；挂起版，供显式 abort 走）。 */
    private suspend fun destroyProcessTree(proc: Process) {
        proc.descendants().forEach { it.destroy() }
        proc.destroy()
        val deadline = clock() + GRACE_MS
        while (clock() < deadline && proc.isAlive) delay(50)
        forceKill(proc)
    }

    private fun forceKill(proc: Process) {
        if (proc.isAlive) {
            proc.descendants().forEach { it.destroyForcibly() }
            proc.destroyForcibly()
        }
    }

    companion object {
        /** 沙箱根目录的显式覆盖 env 名（AGENTS §2.7-2：显式 env → 公共约定目录）。 */
        const val ENV_SANDBOX_ROOT = "TIDINGS_SANDBOX_ROOT"

        /** 沙箱根的公共约定位置（D-024 明文：/tmp/tidings-ws）。 */
        const val DEFAULT_ROOT = "/tmp/tidings-ws"

        /** 生产根目录：显式 env 优先，缺省公共约定 —— 绝不写个人绝对路径。 */
        fun defaultRoot(env: Map<String, String> = System.getenv()): File =
            File(env[ENV_SANDBOX_ROOT]?.takeIf { it.isNotBlank() } ?: DEFAULT_ROOT)

        /** taskId 白名单：分支名与路径段双用的安全形状（64 上限给 sha 级长 id 留位）。 */
        val TASK_ID = Regex("[A-Za-z0-9_-]{1,64}")

        const val ARTIFACTS_DIR = "artifacts"

        const val GRACE_MS = 3_000L

        /** 取消观测周期：主循环每这么久醒一次做 ensureActive()。 */
        const val POLL_MS = 100L

        /** 读线程收尾哨兵。 */
        private val POISON = Any()

        fun branchOf(taskId: String): String = "task-$taskId"

        /** `git diff --stat` 尾行：" 1 file changed, 2 insertions(+), 1 deletion(-)"。 */
        fun parseStat(text: String): Triple<Int, Int, Int> {
            val line = text.lineSequence().lastOrNull { it.contains("file changed") }
                ?: return Triple(0, 0, 0)
            fun num(marker: String): Int =
                Regex("(\\d+) $marker").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val files = Regex("(\\d+) files? changed").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return Triple(files, num("insertion"), num("deletion"))
        }

        fun sha256Hex(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** git 调用的最小抽象（生产 = 真进程；测试可注入替代实现）。 */
interface GitRunner {
    fun run(args: List<String>, cwd: File?): GitResult
}

data class GitResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

object ProcessGitRunner : GitRunner {
    override fun run(args: List<String>, cwd: File?): GitResult {
        val proc = ProcessBuilder(listOf("git") + args).apply { directory(cwd) }.start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        return GitResult(code, out, err)
    }
}
