// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.core.nowMillis
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
import kotlinx.serialization.json.buildJsonArray
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

        /**
         * 跑过 [timeoutMs] 仍未结束：进程树按 D-024 中止序摘掉、worktree 无痕清理。
         *
         * 为什么单列一档而不是并进 [Aborted]：中止是**外部要求**（宿主 HTTP / 调用方取消），
         * 超时是**宿主自己止损**。混在一档里，审计会把一次自主止损读成一次人为中断，
         * 而「是谁结束的」正是复盘时最先要看的那一格。
         */
        data class TimedOut(val timeoutMs: Long) : Outcome
    }

    /**
     * 在独立 worktree 里执行一条命令。[onLine] 收原始输出行（信令走这里，文件走
     * task.log）。执行期间协程被取消 = 触发中止路径并上抛取消。
     *
     * [timeoutMs] 是**墙钟**止损：从拉起进程算起，超过它就按 D-024 中止序摘掉进程树、
     * 无痕清理、如实回 [Outcome.TimedOut]。不设这一条时，一个挂住的 CLI（本轮实测两次）
     * 会一直占着 worktree 与分支，直到人去 kill —— 无人值守的流水线就卡在这里不动。
     * 默认值走 [timeoutMsOf]（env 可覆盖），测试用短值注入。
     */
    suspend fun run(
        taskId: String,
        hostRepo: String,
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        onLine: suspend (String) -> Unit,
        timeoutMs: Long = timeoutMsOf(env),
    ): Outcome {
        require(TASK_ID.matches(taskId)) {
            "taskId \"$taskId\" must match ${TASK_ID.pattern} — it becomes a path segment and a branch name"
        }
        val worktree = File(sandboxRoot, taskId)
        val artifactsDir = File(File(sandboxRoot, ARTIFACTS_DIR), taskId).apply { mkdirs() }
        hostRepos[taskId] = hostRepo
        val hostDir = File(hostRepo)
        if (File(hostDir, ".git").isDirectory) {
            bootstrapIfGreenfield(hostDir, git)
        }
        // 起点是「上一轮合并后的工作树快照」，不是写死的 HEAD（D5）。合并只
        // `git apply` 不 commit，HEAD 永远停在机主最后一次提交上；若沙箱自 HEAD
        // 建，本轮任务就看不到上一轮合进去的代码，A/B/C 三轮各干各的、基线不累计。
        // MergeBaseRef 在机主自己提交过之后会自动回落到 HEAD（快照已过时）。
        val baseRef = MergeBaseRef.baseFor(hostDir)
        // 立刻解析成**不可变 sha**：并发工单里另一条链合并会推进 `refs/tenon/base`；
        // 若 harvest 时再读 ref，就会拿「别人的新基线」去 diff 自己的工作树 ——
        // 补丁里凭空出现一段回退。sha 一旦定下就与后续 ref 前移无关。
        val base = git.run(
            listOf("rev-parse", "--verify", "--quiet", baseRef),
            cwd = File(hostRepo),
        ).stdout.trim().ifEmpty { baseRef }
        if (baseRef != "HEAD") println("[sandbox] task $taskId starts from merged base $base")
        try {
            // worktree add：hostRepo 不是 git 仓 / 分支已存在 / 路径被占都会在这里炸，
            // stderr 尾巴随 Failed 上去 —— 大声失败，不静默回退到裸跑。
            var createdResult = git.run(
                listOf("worktree", "add", "-b", branchOf(taskId), worktree.absolutePath, base),
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
                    listOf("worktree", "add", "-b", branchOf(taskId), worktree.absolutePath, base),
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
            val deadlineAt = started + timeoutMs
            // 跨上下文可见：赋值发生在 Dispatchers.IO 的工作线程上，判定在与它不同的
            // 线程上做 —— 普通 Boolean 在这里是不可靠的（读方可能永远看不到写）。
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            // 单行熔断标记（§8.2）：读线程置位、主循环判定——跨线程可见性同 timedOut。
            val lineLimitBroken = java.util.concurrent.atomic.AtomicBoolean(false)
            // 单行熔断「杀根之前」拍下的进程树快照。根一死，proc.descendants() 恒空
            // （后代被过继给 init），主循环再枚举已经来不及；这份快照让清场按 pid
            // 收掉那些孤儿。AtomicReference：读线程写、协程线程读。
            val lineLimitTree = java.util.concurrent.atomic.AtomicReference<Set<Long>>(emptySet())
            // OS 级轻量沙箱（提案 §9 阶段三，opt-in）：TENON_OS_SANDBOX=strict 时先探活
            // 再包装；探不过**大声失败**——「要求了隔离却拿到裸跑」比「明确失败」危险
            // 得多（D-024 同判据，不静默回退）。探活必须在起进程之前：失败的门不该
            // 先放任务跑一遍再拦。
            var effectiveCommand = command
            if (OsSandboxGuard.isStrictRequested(env) || OsSandboxGuard.isStrictRequested(System.getenv())) {
                when (val probe = OsSandboxGuard.probe()) {
                    is OsSandboxGuard.ProbeResult.Available -> {
                        // profileDir 传产物目录，**不传 worktree**：策略文件落在 worktree 里
                        // 会被 harvest 的 `git add -A` 当成用户改动收进 patch.diff。
                        effectiveCommand = OsSandboxGuard.wrap(command, probe.kind, profileDir = artifactsDir)
                    }

                    is OsSandboxGuard.ProbeResult.Unavailable -> {
                        note(
                            logFile,
                            "[sandbox] task $taskId OS sandbox requested but unavailable: " + probe.reason,
                        )
                        cleanupTask(taskId)
                        return Outcome.Failed(
                            exitCode = -1,
                            message = "OS sandbox requested (TENON_OS_SANDBOX=strict) but unavailable: " +
                                probe.reason,
                        )
                    }
                }
            }
            val exitCode = withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(effectiveCommand).directory(worktree)
                // PWD 必须跟着改：`directory()` 只改真实 cwd，继承来的 PWD 仍指向宿主目录。
                // opencode 解析工作区读的是 `$PWD` 而不是 getcwd —— 不补这一行，它会
                // 把产物写进宿主仓库根（实测：proof.txt 落在 repo 根，worktree 里空）。
                // 先设、后 env.forEach，显式注入的 PWD 仍然赢。
                pb.environment()["PWD"] = worktree.absolutePath
                // 宿主中介 MCP 工具的工作区边界：显式传沙箱 worktree，绝不靠进程 cwd 猜。
                pb.environment()["TENON_MCP_WORKSPACE"] = worktree.absolutePath
                env.forEach { (k, v) -> pb.environment()[k] = v }
                // 同 CliSubprocessDriver：GUI 启动的宿主只有最小 PATH，脚本型 CLI
                // （`#!/usr/bin/env node`）会找不到解释器 → 127。env 显式给了 PATH 就不动它。
                if (env["PATH"] == null) pb.environment()["PATH"] = CliDiscovery.childPath(pb.environment())
                if (env["HOME"] == null) {
                    val sandboxHome = prepareSandboxHome(artifactsDir)
                    pb.environment()["HOME"] = sandboxHome.absolutePath
                }
                pb.redirectErrorStream(true)
                // stdin **在内核层**接 /dev/null，而不是"启动后关流"。
                //
                // 关流那条（`proc.outputStream.close()`）理论上会给出 EOF，实测不够：
                // 真机上 codex 打了 `Reading additional input from stdin...` 之后
                // **永久阻塞**（责任链首棒曾出现零输出、无 socket、
                // 无 session 文件，只有杀掉那一刻缓冲区才随进程退出刷出来）。读 stdin 的 CLI
                // 拿到的是"还没 EOF 的管道"，不是"已关闭的 stdin"。
                // `Redirect.from(/dev/null)` 让子进程从打开那一刻起就读到 EOF，与关流无关，
                // 也与 JDK 的缓冲实现无关 —— prompt 走 argv，本来就不需要 stdin。
                pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                val proc = pb.start()
                running[taskId] = proc
                // 读行不在当前线程上阻塞：子进程的输出由专用读线程灌进队列，本协程
                // 每 ≤100ms 醒一次（poll 超时）做 `ensureActive()` —— 取消在百毫秒级
                // 被观测，而不是等 30s 的阻塞 readLine 解锁（它不响应取消）。读线程
                // 退出以 POISON 哨兵收尾，正常路径也是它解锁主循环。
                val queue = LinkedBlockingQueue<Any?>()
                val reader = Thread {
                    try {
                        val lines = BoundedLineReader(proc.inputStream.bufferedReader())
                        while (true) {
                            when (val read = lines.readLine()) {
                                BoundedLine.Eof -> break
                                // 单行熔断（§8.2）：超限行不进队列不进日志（旧写法用
                                // `useLines`，整行已经在堆里了），摘掉进程树并以 Failed
                                // 收场——响亮失败，不静默跳过。
                                BoundedLine.TooLong -> {
                                    lineLimitBroken.set(true)
                                    // **先拍快照再 SIGTERM 根**：`destroyProcessTree` 靠
                                    // `proc.descendants()` 枚举后代，而它对已死的根恒空。
                                    // 旧顺序「先 destroy() 再让主循环枚举」正好把整层
                                    // 子进程留给 init —— 卡片翻成 Failed，下面的进程还在改
                                    // worktree。快照在杀根之前落进 [lineLimitTree]，主循环
                                    // 把它并进待杀 pid 集合（D-024 同款，换了入口）。
                                    lineLimitTree.set(trackTree(proc))
                                    note(
                                        logFile,
                                        "[sandbox] task $taskId line limit broken: a stdout line over " +
                                            DriverLineLimits.MAX_LINE_CHARS + " chars; killing process tree",
                                    )
                                    // 读线程里只能做非挂起的止损（SIGTERM 根进程）：主循环
                                    // 还卡在 `proc.waitFor()`，不杀根它就永远回不到清场段落。
                                    runCatching { proc.destroy() }
                                    break
                                }
                                is BoundedLine.Text -> queue.put(read.value)
                            }
                        }
                    } finally {
                        queue.put(POISON)
                    }
                }.apply { isDaemon = true; name = "sandbox-$taskId-reader"; start() }
                val exit = logFile.bufferedWriter().use { log ->
                    try {
                        while (true) {
                            ensureActive()
                            // 墙钟止损与取消观测同一个周期（≤POLL_MS），所以「挂住」
                            // 最多比 timeoutMs 多等一个 tick 就被收场。
                            if (clock() >= deadlineAt) {
                                timedOut.set(true)
                                break
                            }
                            val line = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                            if (line === POISON) break
                            log.appendLine(line as String)
                            onLine(line)
                        }
                        // 超时分支不取退出码：进程是被我们杀的，它的退出码没有信息量
                        // （外层 when 先判 timedOut，不会把它误读成 Failed）。
                        if (timedOut.get()) EXIT_TIMEOUT else proc.waitFor()
                    } finally {
                        // 取消也要等到真死：不等 waitFor 的自然退出 —— 进程树摘掉后
                        // waitFor 才会返回（读线程随 EOF 结束）。
                        if (proc.isAlive || lineLimitBroken.get()) {
                            // **超时/单行熔断不写 aborted 标记**：Aborted 的语义是「外部
                            // 要求中止」（宿主 HTTP / 调用方取消），超时与熔断是宿主自主
                            // 止损。混成一档，复盘时会把一次自主止损读成一次人为中断。
                            if (!timedOut.get() && !lineLimitBroken.get()) aborted.add(taskId)
                            // 这里原来只发 SIGTERM 就交还控制权。**取消路径上 SIGKILL 升级
                            // 执行不到**：`ensureActive()` 抛出后异常已经离开 `use{}`，下面
                            // 那段 `forceKill` 被跳过，忽略 SIGTERM 的子进程就此活下来 ——
                            // 与 `abort()` 修掉的是同一个洞，只是换了入口。
                            // 单行熔断同病：读线程只 SIGTERM 了根进程，子进程要靠这段
                            // 完整的进程树摘除收场（D-024 同款）。
                            // `NonCancellable`：取消中再调 suspend 会立刻又抛一次。
                            withContext(NonCancellable) {
                                val t0 = System.currentTimeMillis()
                                val tree = destroyProcessTree(proc, lineLimitTree.get())
                                val alive = tree.filter { isAlive(it) }
                                note(
                                    logFile,
                                    "[sandbox] task $taskId teardown (timedOut=${timedOut.get()}, lineLimitBroken=${lineLimitBroken.get()}): " +
                                        "killed ${tree.size - alive.size}/${tree.size} pid(s) in " +
                                        "${System.currentTimeMillis() - t0}ms" +
                                        if (alive.isEmpty()) "" else ", still alive: ${alive.joinToString(",")}",
                                )
                            }
                        }
                        // 取消路径走不到 `withContext` 之后那行 `running.remove`（异常已经抛出）。
                        // 留下一条死进程条目会让 `hasRunningSandboxes()` 恒真 —— 下一轮 stop
                        // 就去打一具尸体，而真正活着的进程仍在跑。
                        running.remove(taskId)
                        reader.join(2_000)
                    }
                }
                // 宽限后升级 SIGKILL，并**等它真死**再交还：worktree 里的文件还被占着时
                // `git worktree remove` 会失败 —— 那样「无痕清理」就成了口号。
                if (proc.isAlive) {
                    val killBy = System.currentTimeMillis() + GRACE_MS
                    while (System.currentTimeMillis() < killBy && proc.isAlive) {
                        Thread.sleep(50)
                    }
                    if (proc.isAlive) {
                        forceKill(proc)
                    }
                    runCatching { proc.waitFor() }
                }
                exit
            }
            running.remove(taskId)
            return when {
                // 单行熔断优先于退出码：任务是被宿主主动终止的（超长行），退出码
                // 没有信息量——真因在 task.log 的 line-limit-broken 一行里；进程树
                // 摘除已在 finally 的清场段落完成（D-024 同款）。
                lineLimitBroken.get() -> {
                    cleanupTask(taskId)
                    Outcome.Failed(
                        exitCode = -1,
                        message = "a stdout line over " + DriverLineLimits.MAX_LINE_CHARS +
                            " chars broke the line limit; process tree killed (see task.log)",
                    )
                }
                timedOut.get() -> {
                    // 超时是结局，不是一个说不清的退出码：真因（跑了多久、上限多少、
                    // 已摘进程树）写进 task.log，外部取证只看产物目录也读得到。
                    note(
                        logFile,
                        "[sandbox] task $taskId timeout: ran ${clock() - started}ms, limit=${timeoutMs}ms, " +
                            "process tree killed (D-024 abort order), worktree cleaned",
                    )
                    cleanupTask(taskId)
                    Outcome.TimedOut(timeoutMs)
                }
                aborted.remove(taskId) -> {
                    cleanupTask(taskId)
                    // 中止的任务没有产物可留：patch 没生成，task.log 记的是「被叫停」
                    // 这一件事 —— 而那个人正是按停止的人，他已经知道了。
                    // 留着只会让沙箱根目录越积越多（真机实测：一次中止留一个目录，
                    // 验收 §10d 第 5 条要求「目录消失」）。
                    deleteArtifactsSafely(artifactsDir)
                    Outcome.Aborted("task aborted (SIGTERM/SIGKILL)")
                }
                exitCode == 0 -> {
                    val harvest = harvestAndWrite(taskId, worktree, artifactsDir, logFile, started, base)
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
                // 与 aborted 分支同一条：取消掉的活没有产物，目录不该留
                // （验收 §10d 第 5 条「目录消失」）。
                deleteArtifactsSafely(artifactsDir)
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
        // 入口时刻与耗时必须落到任务自己的日志里。宿主 stdout 重定向后不刷行，
        // 「stop 什么时候进来、树什么时候摘干净」这两件事在事发时一个字都取不到，
        // 事后只能靠人回忆 —— 而这两件事正是判断「打断到底慢在哪」的唯一依据。
        val log = taskLog(taskId)
        val t0 = System.currentTimeMillis()
        note(log, "[sandbox] abort $taskId: entered at $t0, destroying process tree")
        val tree = destroyProcessTree(proc)
        // 幸存者必须**看得见**。「一次 stop 没杀干净」这个现场（同群并发派单，一条被收掉、
        // 另一条活到第二次 stop）事后只能靠人回忆，就是因为这条路上一个字都没打。
        val survivors = tree.filter { isAlive(it) }
        note(
            log,
            "[sandbox] abort $taskId: killed ${tree.size - survivors.size}/${tree.size} pid(s) in " +
                "${System.currentTimeMillis() - t0}ms" +
                if (survivors.isEmpty()) "" else ", still alive: ${survivors.joinToString(",")}",
        )
        return true
    }

    /**
     * 把当前**所有**在跑的沙箱任务摘掉，返回摘掉的数量。
     *
     * 为什么需要这条「一刀切」的入口：手机的「停」到达时，宿主未必知道那条任务挂在哪个
     * taskId 上 —— taskId 是驱动内部生成的，编排器的工单只认会话/群。真机实测：卡片已经
     * 翻成 `Aborted by user request`，而同一沙箱里的 `codex exec` 七分钟后仍在跑 ——
     * 因为取消只发了协程取消，没落到进程上；而 [abort] 这条确定性的路从来没被调用过
     * （HTTP 面只发协议帧，不杀本地进程）。
     *
     * 语义上这是对的：用户说「停」要停的是**这台机器正在干的活**，不是某个内部 id。
     * 代价是一次停掉全部在跑任务 —— 但宿主同一时刻本来就是一条队列。
     *
     * ## 为什么要重扫而不是取一次快照
     *
     * `running` 的条目是在 `pb.start()` **之后**才写进去的。并发派单（一条消息被两个执行者
     * 各派一次的那轮，见 §9.3）时，第二条的 `pb.start()` 完全可能落在本方法取快照之后 ——
     * 于是「一次 stop 只摘掉一个」。真机形态：16:14 那次 stop 摘掉 `t-12`，`t-11` 还活着，
     * 直到 16:17 第二次 stop 才收掉。
     *
     * 所以这里按「还没处理过的 taskId」反复扫，直到没有新的为止（有界，不会自旋）。
     */
    suspend fun abortAll(): Int {
        val seen = mutableSetOf<String>()
        var killed = 0
        repeat(ABORT_ROUNDS) {
            val fresh = running.keys.filter { it !in seen }
            if (fresh.isEmpty()) return killed
            for (id in fresh) {
                seen += id
                if (abort(id)) killed++
            }
            // 给"正在 pb.start() 的那条"一点时间把条目写进来，再扫一轮。
            if (running.keys.none { it !in seen }) return killed
            delay(ABORT_RESCAN_MS)
        }
        val leftover = running.keys.filter { it !in seen }
        if (leftover.isNotEmpty()) {
            // 同样落盘：stdout 重定向后这行是取不到的，而「重扫都没抓到」恰恰是最需要证据的一种。
            for (id in leftover) {
                note(
                    taskLog(id),
                    "[sandbox] abortAll: task $id registered too late, still running after $ABORT_ROUNDS rounds",
                )
            }
        }
        return killed
    }

    // deleteArtifactsSafely 真身在 companion object：产物清理必须全量清空
    // .sandbox-home 且不跟随符号链接（AGENTS §2.8）。放进 companion 是为了让回归
    // 钉子能直接调用，不必为了一个纯文件操作去构造执行器实例。

    /** 当前在跑的任务数（「停」要不要消费这条消息，看它）。 */
    fun runningCount(): Int = running.size

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

    /**
     * 某个任务的产物日志路径。
     *
     * `abort()` 手里只有 taskId（taskId 是驱动内部生成的，编排器的工单只认会话/群），
     * 但取证要落在**同一条时间线**上 —— 任务日志里已经有 `turn.started` / 逐条命令，
     * 打断的入口时刻只有追加到同一份文件里才能和它们对齐。
     */
    private fun taskLog(taskId: String): File =
        File(File(sandboxRoot, ARTIFACTS_DIR), taskId).resolve("task.log")

    /**
     * `git diff <base>` 提纯成 patch + report（两个文件 + 结构化摘要），写到产物目录。
     *
     * 基线是**开工时解析好的不可变 sha**，不是 `HEAD`：CLI 在沙箱里自己 commit 过，
     * `HEAD` 就前移了，`git diff HEAD` 会把它的活读成「没有改动」；并发合并推进
     * `refs/tenon/base` 时，读 ref 又会拿别人的新基线来 diff。用固定的 sha，
     * 两种情形都只回答一个问题：**这次任务相对它的起点改了什么**。
     */
    private fun harvestAndWrite(
        taskId: String,
        worktree: File,
        artifactsDir: File,
        logFile: File,
        started: Long,
        baseCommit: String,
    ): Harvest? {
        // CLI 新建的文件是 untracked —— 裸 `git diff` 看不见它们。worktree 是一次性
        // 工作面，先把一切 stage 进临时索引再取 stat 与 diff（两处都要在 add 之后，
        // 先 stat 后 add 会把统计读成全零 —— 提纯返回 null 的假空）。
        val add = git.run(listOf("add", "-A"), cwd = worktree)
        val stat = git.run(listOf("diff", "--stat", baseCommit), cwd = worktree)
        val patch = git.run(listOf("diff", baseCommit), cwd = worktree)
        if (!patch.ok) {
            // 这两条 null 分支原先**一行都不打** —— 「没有交付物」与「收割失败」在外部
            // 完全同形，一次 16 分钟的真实执行因此无法归因（幕 4 实测踩到）。
            note(
                logFile,
                "[sandbox] harvest $taskId: git diff $baseCommit failed exit=" + patch.exitCode +
                    " stderr=" + patch.stderr.takeLast(300).replace("\n", " "),
            )
            return null // 没有可 diff 的基线 = 没有交付物，如实为空（不是失败）。
        }
        // 「有没有交付物」的判据是 **patch 本体**，不是 stat 的文案解析结果。
        // 判据曾经是 `parseStat(...) == (0,0,0)`，而 parseStat 当时只认单数
        // "file changed"（git 多文件打的是 "3 files changed"），于是 3 个文件的真实改动
        // 被判成假空、整批丢弃（真机 cmvx822p7 第一棒，AC5 修复就这么没了）。
        // patch 非空就是有货；统计口径只影响报告里的数字，不该有权决定丢不丢。
        if (patch.stdout.isBlank()) {
            note(
                logFile,
                "[sandbox] harvest $taskId: nothing to harvest — add.exit=" + add.exitCode +
                    " add.stderr=" + add.stderr.takeLast(200).replace("\n", " ") +
                    " stat=" + stat.stdout.takeLast(200).replace("\n", " ") +
                    " patchBytes=" + patch.stdout.length,
            )
            return null
        }
        var (files, adds, dels) = parseStat(stat.stdout)
        if (files == 0 && adds == 0 && dels == 0) {
            // stat 认不出来时退回 patch 自数，读数照实上报，不再"解析失败即无改动"。
            val fallback = countsFromPatch(patch.stdout)
            files = fallback.first
            adds = fallback.second
            dels = fallback.third
            note(
                logFile,
                "[sandbox] harvest $taskId: stat unparsed, counted from patch" +
                    " (files=$files +$adds -$dels)",
            )
        }
        val patchFile = File(artifactsDir, "patch.diff").apply { writeText(patch.stdout) }
        // 被修改文件的**全文快照**：diff 对改动文件只带 hunk，离开工作树就拼不回整份
        // 文件（extractFileFromPatch 因此只认新文件/单 hunk 两种形状）。而 refine 类任务
        // 恰恰改的是已存在的产物（第二轮的 prototype.html），抽不出来 = 编排器宣称
        // 「改好了」但用户手里没有可打开的东西（真机 refine 轮实测踩到）。
        // 收割时工作树还活着 —— 这里把改动文件的全文按原相对路径存进 files/，
        // 沙箱无痕清理之后调用方仍拿得到整份新文件。
        val snapshots = snapshotModifiedFiles(taskId, worktree, artifactsDir, patch.stdout, logFile)
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
            if (snapshots.isNotEmpty()) {
                put("snapshots", buildJsonArray { snapshots.forEach { add(JsonPrimitive(it)) } })
            }
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

    /**
     * 把 patch 里**被修改**（非新建）的文件全文快照进 `<artifactsDir>/files/<相对路径>`。
     *
     * 只快照 `--- a/<path>` + `+++ b/<path>` 成对出现的修改目标：新建文件（`--- /dev/null`）
     * 由 `StageChain.extractFileFromPatch` 从 `+` 行整份还原，不需要占磁盘。快照的前提是
     * 工作树里该文件真实存在 —— CLI 删掉的文件不出快照（交付语义里它就是没了）。
     *
     * 防线：路径逐条过 [safeWorktreeFile]，单文件超过 [SNAPSHOT_MAX_BYTES] 跳过、
     * 总数到 [SNAPSHOT_MAX_FILES] 截停 —— 快照是给产物投递兜底的，不是备份整个仓库。
     *
     * @return 快照成功的相对路径清单（与 files/ 下落盘一一对应）。
     */
    private fun snapshotModifiedFiles(
        taskId: String,
        worktree: File,
        artifactsDir: File,
        patch: String,
        logFile: File,
    ): List<String> {
        val saved = mutableListOf<String>()
        for (rel in modifiedPathsFromPatch(patch)) {
            if (saved.size >= SNAPSHOT_MAX_FILES) break
            val src = safeWorktreeFile(worktree, rel) ?: continue
            if (!src.isFile) continue
            if (src.length() > SNAPSHOT_MAX_BYTES) {
                note(logFile, "[sandbox] harvest $taskId: snapshot skipped (too large): $rel")
                continue
            }
            val dst = File(File(artifactsDir, "files"), rel)
            runCatching {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }.onSuccess {
                saved.add(rel)
            }.onFailure { t ->
                note(logFile, "[sandbox] harvest $taskId: snapshot copy failed: $rel (${t.message})")
            }
        }
        if (saved.isNotEmpty()) {
            note(logFile, "[sandbox] harvest $taskId: snapshot ${saved.size} modified file(s): $saved")
        }
        return saved
    }

    /**
     * 从 unified diff 文本里解析**被修改**（非新建）文件的目标路径清单。
     *
     * 逐 `diff --git` 段落判：`--- /dev/null` = 新建（跳过）；`+++ b/<path>` 才是本任务
     * 改过的既有文件。同一文件多 hunk 只记一次。
     */
    internal fun modifiedPathsFromPatch(patch: String): List<String> {
        val out = LinkedHashSet<String>()
        var oldSide: String? = null
        var sawHeader = false
        for (raw in patch.lineSequence()) {
            when {
                raw.startsWith("diff --git ") -> {
                    if (sawHeader) {
                        oldSide?.let { out.add(it) }
                        oldSide = null
                        sawHeader = false
                    }
                }
                raw.startsWith("--- ") && !sawHeader -> {
                    val p = raw.removePrefix("--- ").trim()
                    oldSide = if (p == "/dev/null") null else p.removePrefix("a/").removePrefix("./")
                }
                raw.startsWith("+++ ") && !sawHeader -> {
                    val p = raw.removePrefix("+++ ").trim().removePrefix("b/")
                    // --- /dev/null 的新文件不进快照（extractFileFromPatch 能整份还原）。
                    if (oldSide != null && p == oldSide) out.add(p)
                    oldSide = null
                    sawHeader = true
                }
            }
        }
        if (sawHeader) oldSide?.let { out.add(it) }
        return out.filter { it.isNotBlank() && it != "/dev/null" }
    }

    /** 快照的路径安全检查：拒绝绝对路径与 `..` 穿越，落点必须还在工作树内。 */
    private fun safeWorktreeFile(worktree: File, rel: String): File? {
        if (rel.isBlank() || rel.startsWith("/")) return null
        val root = worktree.canonicalFile
        val f = File(root, rel).canonicalFile
        return if (f.path.startsWith(root.path + File.separator)) f else null
    }

    /** 单文件快照上限：prototype.html 量级是几十 KB，2 MB 已是宽余量。 */
    val SNAPSHOT_MAX_BYTES: Long = 2L * 1024L * 1024L

    /** 单任务快照文件数上限：防一个跑飞的任务把产物目录写爆。 */
    val SNAPSHOT_MAX_FILES = 20

    /**
     * 进程树 SIGTERM → 至多 3s → SIGKILL（D-024 的中止序；挂起版，供显式 abort 走）。
     *
     * 返回**本次记下的整棵树**（含根），供调用方事后核对死活。
     *
     * ## 为什么不靠 `proc.descendants()`
     *
     * `ProcessHandle.descendants()` 有两个性质决定了它单独用会漏杀：
     *
     * 1. **是快照**。调用那一刻之后 fork 出来的层不在里面。
     * 2. **只对活着的祖先有效**。根进程一退出，它的后代被过继给 init，
     *    `proc.descendants()` 从此恒空 —— 那些进程还在跑，但再也没有句柄能枚举到它们。
     *
     * 真机形态（一次 stop 之后）：`codex` 这一层被 SIGTERM 干净地收掉，
     * `proc.isAlive` 立刻为 false，于是宽限循环直接退出、`forceKill(proc)` 被跳过，
     * 而它下面起的那层还活着 —— 卡片已经翻 `Aborted`，工作树还在被改。
     *
     * 所以这里先把整棵树的 pid 记下来，之后**按 pid 逐个核对死活**，与根的死活解耦。
     *
     * 单行熔断是上面第 2 条的**另一种入口**：读线程为了让 `proc.waitFor()` 返回，
     * 必须先 SIGTERM 根，而主循环随后才走到这里 —— 那时 `trackTree(proc)` 已经
     * 枚举不到后代了。[preKilledSnapshot] 就是读线程在杀根之前拍下的那份快照，
     * 并进来之后同样按 pid 收，不靠祖先是否还活着。
     */
    private suspend fun destroyProcessTree(
        proc: Process,
        /** 根已死之前拍下的后代 pid（单行熔断路径）；空集合 = 正常路径。 */
        preKilledSnapshot: Set<Long> = emptySet(),
    ): List<Long> {
        val tracked = trackTree(proc)
        // 单行熔断时读线程在 SIGTERM 根**之前**拍的快照：此刻再调 trackTree(proc)
        // 只会拿到根自己的 pid，没有这份快照就等于把整层后代留给 init。
        tracked += preKilledSnapshot
        tracked.forEach { pid -> aliveHandle(pid)?.destroy() }
        val deadline = clock() + GRACE_MS
        while (clock() < deadline && tracked.any { isAlive(it) }) delay(50)
        // 宽限期内可能又 fork 过一层：再取一次并进同一个集合，然后统一 SIGKILL。
        tracked += trackTree(proc)
        tracked.forEach { pid -> aliveHandle(pid)?.destroyForcibly() }
        return tracked.toList()
    }

    /** 记下 [proc] 及其当前后代的 pid（根恒在集合里，即使它已死）。 */
    private fun trackTree(proc: Process): MutableSet<Long> {
        val pids = linkedSetOf(proc.pid())
        // 进程可能在我们取快照的同时就退了 —— 拿不到后代不是错误，只是"没有后代"。
        runCatching { proc.descendants().forEach { pids += it.pid() } }
        return pids
    }

    /** 按 pid 取句柄；已退出或不存在返回 null（不抛）。 */
    private fun aliveHandle(pid: Long): ProcessHandle? =
        ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun forceKill(proc: Process) {
        if (proc.isAlive) {
            proc.descendants().forEach { it.destroyForcibly() }
            proc.destroyForcibly()
        }
    }

    companion object {
        /** 沙箱根目录的显式覆盖 env 名（AGENTS §2.7-2：显式 env → 公共约定目录）。 */
        const val ENV_SANDBOX_ROOT = "TENON_SANDBOX_ROOT"

        /** 沙箱根的公共约定位置（D-024 明文：/tmp/tenon-ws）。 */
        const val DEFAULT_ROOT = "/tmp/tenon-ws"

        /** 生产根目录：显式 env 优先，缺省公共约定 —— 绝不写个人绝对路径。 */
        fun defaultRoot(env: Map<String, String> = System.getenv()): File =
            File(env[ENV_SANDBOX_ROOT]?.takeIf { it.isNotBlank() } ?: DEFAULT_ROOT)

        /** taskId 白名单：分支名与路径段双用的安全形状（64 上限给 sha 级长 id 留位）。 */
        val TASK_ID = Regex("[A-Za-z0-9_-]{1,64}")

        const val ARTIFACTS_DIR = "artifacts"

        const val GRACE_MS = 3_000L

        /** `abortAll` 的重扫轮数上限：只为了吃掉"快照之后才注册"的那一两条，不该自旋。 */
        const val ABORT_ROUNDS = 5

        /** 两轮重扫之间的间隔：够 `pb.start()` 把条目写进 `running`。 */
        const val ABORT_RESCAN_MS = 100L

        /** 派单止损的 env 名（毫秒）。显式覆盖优先于默认值。 */
        const val ENV_TIMEOUT_MS = "TENON_SANDBOX_TIMEOUT_MS"

        /**
         * 派单墙钟上限，默认 30 分钟。
         *
         * 取值依据是本轮真机读数，不是拍脑袋：幕 4 实现 395 s、幕 5 审查约 10 min、
         * 幕 2 设计 201 s。30 min 给最慢一幕留了 ~3 倍余量，同时把「挂住」的代价从
         * 「无限期占着 worktree，等人发现」压到「半小时后自动收场」。
         * 真跑大重构需要更长时，用 env 显式抬高 —— 但**不会**默认无限。
         */
        const val DEFAULT_TIMEOUT_MS = 30L * 60L * 1000L

        /**
         * 从 env 解析超时。**认不出的值一律回落默认**：`0`、负数、乱写都不许变成
         * 「立刻杀掉」—— 那会把一个配置笔误变成一次全量派单失败。
         */
        fun timeoutMsOf(env: Map<String, String> = System.getenv()): Long {
            val raw = env[ENV_TIMEOUT_MS]?.trim()?.toLongOrNull()
            return raw?.takeIf { it > 0L } ?: DEFAULT_TIMEOUT_MS
        }

        /** 取消观测周期：主循环每这么久醒一次做 ensureActive()。 */
        const val POLL_MS = 100L

        /** 读线程收尾哨兵。 */
        private val POISON = Any()

        /** 超时分支的退出码哨兵（见 `run` 里的注释：外层先判 timedOut，不读它）。 */
        const val EXIT_TIMEOUT = -2

        fun branchOf(taskId: String): String = "task-$taskId"

        /**
         * `git diff --stat` 尾行：` 1 file changed, 2 insertions(+), 1 deletion(-)`。
         *
         * **单复数都要认**：多文件时 git 打 `3 files changed`，而 `"files changed"`
         * 里并不包含 `"file changed"` 这个子串 —— 曾经只用单数做判据，导致凡是
         * 改动 ≥2 个文件的派单全被判「无交付物」（真机 cmvx822p7 第一棒实测）。
         */
        fun parseStat(text: String): Triple<Int, Int, Int> {
            val line = text.lineSequence().lastOrNull { STAT_SUMMARY.containsMatchIn(it) }
                ?: return Triple(0, 0, 0)
            fun num(marker: String): Int =
                Regex("(\\d+) $marker").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val files = STAT_FILES.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return Triple(files, num("insertion"), num("deletion"))
        }

        /**
         * 直接从 unified diff 自数（stat 认不出来时的兜底口径）。
         *
         * 比解析 stat 更抗环境：不依赖 git 的本地化文案与汇总行格式。
         * 只数 `+`/`-` 正文行，`+++`/`---` 文件头不算；二进制块计 1 个文件、0 行。
         */
        fun countsFromPatch(patch: String): Triple<Int, Int, Int> {
            var files = 0
            var adds = 0
            var dels = 0
            patch.lineSequence().forEach { line ->
                when {
                    line.startsWith("diff --git ") -> files++
                    line.startsWith("+++") || line.startsWith("---") -> Unit
                    line.startsWith("+") -> adds++
                    line.startsWith("-") -> dels++
                }
            }
            return Triple(files, adds, dels)
        }

        /** stat 汇总行判据：`N file changed` 或 `N files changed`。 */
        private val STAT_SUMMARY = Regex("\\b\\d+ files? changed\\b")

        /** stat 汇总行里的文件数。 */
        private val STAT_FILES = Regex("(\\d+) files? changed")

        /**
         * 为沙箱准备隔离的 HOME 目录（防止 CLI 进程扫描读取宿主家目录敏感文件）。
         *
         * 只白名单符号链接必要的工具配置，绝不暴露宿主私有敏感目录。
         */
        fun prepareSandboxHome(
            artifactsDir: File,
            hostHome: File = File(System.getProperty("user.home") ?: ""),
        ): File {
            val sandboxHome = File(artifactsDir, ".sandbox-home").apply { mkdirs() }
            if (!hostHome.isDirectory) return sandboxHome
            for (name in SANDBOX_CREDENTIAL_ENTRIES) {
                val src = File(hostHome, name)
                val dst = File(sandboxHome, name)
                if (src.exists() && !dst.exists()) {
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath())
                    }
                }
            }
            val srcOpencode = File(hostHome, ".config/opencode")
            if (srcOpencode.exists()) {
                val dstConfigDir = File(sandboxHome, ".config").apply { mkdirs() }
                val dstOpencode = File(dstConfigDir, "opencode")
                if (!dstOpencode.exists()) {
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(dstOpencode.toPath(), srcOpencode.toPath())
                    }
                }
            }
            val claudeJson = File(sandboxHome, ".claude.json")
            if (!claudeJson.exists() && !java.nio.file.Files.isSymbolicLink(claudeJson.toPath())) {
                val mcpScript = McpServerDiscovery.resolveScript()
                if (mcpScript != null && mcpScript.isFile) {
                    runCatching {
                        claudeJson.writeText(McpServerDiscovery.renderClaudeMcpConfig(mcpScript))
                    }
                }
            }
            return sandboxHome
        }

        /**
         * 空目录 / 未建仓的「绿场」工作区自愈初始化。
         *
         * 当用户绑定的宿主目录尚未执行 git init 或尚无任何提交时，git worktree 无法
         * 从 HEAD 衍生任务分支。本函数幂等建立首个提交，使沙箱 worktree 可以顺畅建出。
         */
        fun bootstrapIfGreenfield(repoDir: File, git: GitRunner = ProcessGitRunner): Boolean {
            if (!repoDir.exists()) repoDir.mkdirs()
            if (!repoDir.isDirectory) return false
            val gitDir = File(repoDir, ".git")
            var needsCommit = false
            if (!gitDir.exists()) {
                val initRes = git.run(listOf("init"), cwd = repoDir)
                if (!initRes.ok) return false
                needsCommit = true
            } else {
                val revRes = git.run(listOf("rev-parse", "--verify", "--quiet", "HEAD"), cwd = repoDir)
                if (!revRes.ok || revRes.stdout.isBlank()) {
                    needsCommit = true
                }
            }
            if (needsCommit) {
                git.run(listOf("config", "--local", "user.name", "Tenon Agent"), cwd = repoDir)
                git.run(listOf("config", "--local", "user.email", "tenon-agent@local"), cwd = repoDir)
                val readme = File(repoDir, "README.md")
                if (!readme.exists()) {
                    readme.writeText("# Project\n\nInitialized by Tenon Agent.\n")
                }
                val gitignore = File(repoDir, ".gitignore")
                if (!gitignore.exists()) {
                    gitignore.writeText(".DS_Store\nbuild/\ntarget/\nnode_modules/\n")
                }
                git.run(listOf("add", "-A"), cwd = repoDir)
                val commitRes = git.run(listOf("commit", "-m", "chore: initialize project repository"), cwd = repoDir)
                return commitRes.ok
            }
            return false
        }

        /**
         * 产物目录清理：对**整个 artifactsDir** 做一次不跟随符号链接的遍历删除。
         *
         * [prepareSandboxHome] 把宿主凭据目录以符号链接挂进 .sandbox-home，其中
         * `.config/opencode` 这类挂载在**二级目录**下。Kotlin 的
         * File.deleteRecursively() 会跟随符号链接目录递归，直接删会把真实 HOME 下
         * 这些目录逐文件清空（AGENTS §2.8 血泪禁令）。因此任何
         * artifactsDir.deleteRecursively() 都必须走这里。
         *
         * **不要补回 `Files.isSymbolicLink()` 顶层判断**：旧写法只扫 `.sandbox-home`
         * 顶层 `listFiles()`，抓不到二级目录下的软链接挂载，随后那次
         * deleteRecursively() 照样沿它下钻真实 HOME。这里用不传 FOLLOW_LINKS 的
         * walkFileTree 对**所有条目**无差别 deleteIfExists：链接本体在遍历中被删，
         * 遍历又不跟随链接，所以既覆盖顶层也覆盖嵌套，且不会下钻。
         *
         * 遍历范围是**整个 artifactsDir** 而不是只清 .sandbox-home：只清前者、末尾
         * 再来一次 `artifactsDir.deleteRecursively()` 的旧写法，对 .sandbox-home 之外
         * 的链接毫无防护（任务进程能写产物目录，留一个指向 $HOME 的软链就够了）。
         */
        internal fun deleteArtifactsSafely(artifactsDir: File) {
            val root = artifactsDir.toPath()
            if (!java.nio.file.Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            // 整棵树一次 NOFOLLOW 遍历，而不是「先清 .sandbox-home、再 deleteRecursively」。
            // 旧写法只保证 .sandbox-home 内部不被跟随，**最后那次 deleteRecursively()
            // 仍然会跟随 artifactsDir 下任何别的链接**（任务进程能写产物目录，它完全可以
            // 在那里留一个指向 $HOME 的软链）。现在遍历不传 FOLLOW_LINKS：链接在
            // visitFile 里作为「文件」被删掉本体，遍历不下钻，整个目录树不可能触到宿主。
            runCatching {
                java.nio.file.Files.walkFileTree(
                    root,
                    object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                        override fun visitFile(
                            file: java.nio.file.Path,
                            attrs: java.nio.file.attribute.BasicFileAttributes,
                        ): java.nio.file.FileVisitResult {
                            java.nio.file.Files.deleteIfExists(file)
                            return java.nio.file.FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: java.nio.file.Path,
                            exc: java.io.IOException,
                        ): java.nio.file.FileVisitResult {
                            // 读不了（权限/竞态）也要尽力删；删不掉就继续，不中断整棵树。
                            runCatching { java.nio.file.Files.deleteIfExists(file) }
                            return java.nio.file.FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(
                            dir: java.nio.file.Path,
                            exc: java.io.IOException?,
                        ): java.nio.file.FileVisitResult {
                            java.nio.file.Files.deleteIfExists(dir)
                            return java.nio.file.FileVisitResult.CONTINUE
                        }
                    },
                )
            }
            // 兜底：遍历半途失败时根可能还在。这里只对根做一次 NOFOLLOW 的单条删除，
            // **绝不**退回 deleteRecursively() —— 那正是会跟随链接的那一个。
            runCatching { java.nio.file.Files.deleteIfExists(root) }
        }

        /**
         * 沙箱 HOME 的凭据/配置挂载白名单 —— **CLI 驱动的登录态就在这些目录里**，
         * 缺一个那个驱动就在沙箱里必死于认证（真机验收实测：清单漏了
         * `.agy`，antigravity 驱动在沙箱里全部 `authentication failed or timed out`，
         * 而同一台机器同一用户在真 HOME 下跑通 —— 红证 t-1-64fef602/task.log）。
         *
         * 反向纪律：这张清单**不许**扩进敏感目录（.ssh / .gnupg / .aws 一类）——
         * 「能跑通」不能靠把宿主私有凭据整个挂进去来换。
         */
        internal val SANDBOX_CREDENTIAL_ENTRIES = listOf(
            ".codex",
            ".claude",
            ".claude.json",
            ".gemini",
            ".agy",
            ".dsh",
            ".npm",
            ".nvm",
            ".gitconfig",
        )

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
