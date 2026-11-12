// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.MergeBaseRef
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 工单产物的**验收门**（P4 真机定论）。
 *
 * ## 为什么需要它
 *
 * 自愈循环原来的判据是「CLI 退出码 0 且交了 patch」——只看**有没有补丁**，不看补丁**合不合格**。
 * 真机实测（注入一个恒失败的测试后派单）：补丁只改了目标文件、`npm test` 仍是红的，
 * 工单照样翻「待合并」，红灯被原样带进工作区。卡上那根「自愈验证」棒显示的是 CLI
 * **自己跑测试的自述**，不是编排器的门。
 *
 * 这个组件把「自述」变成「门」：在同一份基线上重放本轮补丁，跑仓库自己的测试，
 * 退出码非 0 就是本轮失败 —— 交给自愈循环进下一轮提示词。
 *
 * ## 为什么在编排器侧、而不是沙箱里
 *
 * 沙箱 worktree 在 CLI 退出后就被无痕清理了（D-024），补丁是改动的唯一存世形态。
 * 在这里自建一个临时 worktree 重放补丁，有三条好处：
 * 1. 不动驱动链 —— 派单页/会话页那些非工单路径的语义一字不变；
 * 2. 重放的是**用户点合并时会得到的同一份结果**（同一基线 + 同一补丁）；
 * 3. 验收失败时宿主仓零变化（临时 worktree 用完即删），不会把半成品留在用户工作区。
 *
 * ## 刻意不做的两件事
 *
 * - **不调用 [DesktopPatchMerge.apply]**：它成功后会 `MergeBaseRef.snapshot` 推进滚动基线，
 *   而那一步只该发生在用户真的点「合并」时 —— 验收不能替用户改基线。
 * - **不把「跑不起来」当失败**：仓库没有 `test` 脚本、`npm` 不在 PATH、worktree 建不出来，
 *   一律**跳过**（返回 null）并打日志。验收门的职责是「判产物不合格」，不是把环境问题
 *   变成工单阻塞 —— 真机上 PATH 缺 node 那次的教训就在这里。
 */
object TicketVerification {

    /** 验收判定的原因（进卡面横幅与下一轮提示词）。 */
    data class Failure(val reason: String)

    /**
     * 一条验收检查项（Acceptance 报告面，编排模型适配设计）：
     * [passed]=false 时 [detail] 必须是**证据**（退出码 + 尾部输出），不是结论。
     * [skipped]=true 表示这一项没跑成（环境原因）——它不算通过也不算失败，
     * 上卡时单列，不许冒充「验过了」。
     */
    data class Item(
        val name: String,
        val passed: Boolean,
        val detail: String? = null,
        val skipped: Boolean = false,
    )

    /**
     * 一次验收的完整报告：review_ready 卡上的检查表就是它（<1 KiB，证据只带尾部）。
     * [failure] 的 reason 与旧 [verify] 的字符串**逐字一致** —— 自愈提示词和
     * [TicketVerificationTest] 钉着的文案都靠它。
     */
    data class Report(val items: List<Item>) {

        /** 门判据：第一个不合格项就是 Failure。 */
        val failure: Failure?
            get() = items.firstOrNull { !it.passed }?.let { Failure(it.detail ?: (it.name + " failed")) }

        /**
         * 上卡的检查行：有真跑过且通过 → ("验收","done")；有不合格 → ("验收","blocked")；
         * 全是跳过 → null（不撒谎也不添噪 —— 跳过的验收不该在卡上冒充通过）。
         */
        fun checklistRow(): Pair<String, String>? = when {
            items.any { !it.passed } -> VERIFICATION_ROW to "blocked"
            items.any { it.passed && !it.skipped } -> VERIFICATION_ROW to "done"
            else -> null
        }
    }

    /** 检查项名对编排器可见：飞行记录筛「真验收项」时要用它，别再抄一份字面量（cjkLiteralGate）。 */
    const val REPLAY_ITEM = "重放补丁"
    private const val TEST_ITEM = "仓库测试"
    private const val VERIFICATION_ROW = "验收"

    /** 测试命令的墙钟上限：验收是附加步骤，不能把工单拖成看门狗超时。 */
    private const val DEFAULT_TIMEOUT_MS = 180_000L

    /**
     * 这一单该跑什么命令验收；null = 这个仓没有可识别的测试入口（跳过验收）。
     *
     * 优先级策略（只认标准生态入口，不执行仓库内自声明命令）：
     * 1. 生态入口自适应探活：
     *    - Node.js: `package.json` 的 `scripts.test`
     *    - Gradle: `gradlew`
     *    - Maven: `mvnw`
     *    - Python: `pytest.ini` / `pyproject.toml`
     *    - Rust: `Cargo.toml`
     *    - Go: `go.mod`
     * 3. 均未命中时返回 null，验收门优雅跳过，交由沙箱内 Tester 自主结果把关。
     */
    fun testCommandFor(repo: File): List<String>? {
        // 1. Node.js (package.json)
        val manifest = File(repo, "package.json")
        if (manifest.isFile) {
            val text = runCatching { manifest.readText() }.getOrNull()
            if (text != null) {
                val scriptsAt = text.indexOf("\"scripts\"")
                if (scriptsAt >= 0) {
                    val window = text.substring(scriptsAt, minOf(text.length, scriptsAt + 2_000))
                    if (window.contains("\"test\"")) {
                        return listOf("npm", "test", "--silent")
                    }
                }
            }
        }

        // 2. 多语言生态自适应探活
        if (File(repo, "gradlew").isFile) {
            return listOf("./gradlew", "test", "-q")
        }
        if (File(repo, "mvnw").isFile) {
            return listOf("./mvnw", "test", "-q")
        }
        if (File(repo, "pytest.ini").isFile || File(repo, "pyproject.toml").isFile) {
            return listOf("pytest", "-q")
        }
        if (File(repo, "Cargo.toml").isFile) {
            return listOf("cargo", "test", "--quiet")
        }
        if (File(repo, "go.mod").isFile) {
            return listOf("go", "test", "./...")
        }

        return null
    }

    /**
     * 在「当前滚动基线 + 本轮补丁」上跑一次验收。
     *
     * @return null = 通过或**不适用**（跳过，日志里能看到原因）；非 null = 本轮不合格。
     */
    fun verify(
        repoPath: String,
        patchPath: String,
        command: List<String>? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Failure? = verifyReport(repoPath, patchPath, command, timeoutMs).failure

    /**
     * 同 [verify]，但把每一项的判定与证据都交出来（上卡检查表用）。
     * 判定逻辑与字符串与 [verify] 完全同源 —— 两份判据迟早漂，门必须只有一张嘴。
     */
    fun verifyReport(
        repoPath: String,
        patchPath: String,
        command: List<String>? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Report {
        val repo = File(repoPath)
        if (!repo.isDirectory) return skipped("repo missing: $repoPath")
        val patch = File(patchPath)
        if (!patch.isFile) return skipped("patch missing: $patchPath")
        val cmd = command ?: testCommandFor(repo) ?: return skipped("no test script in ${repo.name}")
        val base = runCatching { MergeBaseRef.baseFor(repo) }.getOrNull()
            ?: return skipped("no merge base for ${repo.name}")
        val resolved = git(repo, "rev-parse", "--verify", "--quiet", base).stdout.trim()
        if (resolved.isEmpty()) return skipped("cannot resolve base ref $base")

        val tmp = File.createTempFile("tenon-verify-", "").let { probe ->
            probe.delete()
            File(probe.absolutePath + "-wt")
        }
        val added = git(repo, "worktree", "add", "--detach", tmp.absolutePath, resolved)
        if (added.exitCode != 0) {
            return skipped("worktree add failed: " + added.stderr.takeLast(200).trim())
        }
        try {
            val check = git(tmp, "apply", "--check", patch.absolutePath)
            if (check.exitCode != 0) {
                // 补丁自身对不上基线：这不是「测试不合格」，是本该在合并时才暴露的冲突。
                // 提前在这里说出来，比让用户点合并才吃到 `git apply` 报错好。
                return Report(
                    listOf(
                        Item(REPLAY_ITEM, passed = false, detail =
                            "verification could not replay the patch on the current base: " +
                                check.stderr.takeLast(300).trim()),
                    ),
                )
            }
            val applied = git(tmp, "apply", patch.absolutePath)
            if (applied.exitCode != 0) {
                return Report(
                    listOf(
                        Item(REPLAY_ITEM, passed = false, detail =
                            "verification could not apply the patch: " + applied.stderr.takeLast(300).trim()),
                    ),
                )
            }
            // 真实仓库的依赖装在宿主仓里（worktree 是干净 checkout，没有 node_modules）。
            // 软链过去，让验收跑的是「这单的代码 + 这仓的依赖」。
            runCatching {
                val deps = File(repo, "node_modules")
                if (deps.isDirectory && !File(tmp, "node_modules").exists()) {
                    java.nio.file.Files.createSymbolicLink(
                        File(tmp, "node_modules").toPath(),
                        deps.toPath(),
                    )
                }
            }
            val result = exec(tmp, cmd, timeoutMs)
                ?: return skipped("could not start " + cmd.firstOrNull().orEmpty() + " (not on PATH?)")
            val testName = TEST_ITEM + " (" + cmd.joinToString(" ") + ")"
            if (result.exitCode == 0) {
                RelayLog.info("verify", "passed in " + (result.millis) + "ms (" + cmd.joinToString(" ") + ")")
                return Report(
                    listOf(
                        Item(REPLAY_ITEM, passed = true),
                        Item(testName, passed = true, detail = "exit 0 in " + result.millis + "ms"),
                    ),
                )
            }
            val tail = result.output.takeLast(600).trim()
            RelayLog.info("verify", "FAILED exit=" + result.exitCode + " (" + cmd.joinToString(" ") + ")")
            return Report(
                listOf(
                    Item(REPLAY_ITEM, passed = true),
                    Item(
                        testName,
                        passed = false,
                        detail = "verification failed: " + cmd.joinToString(" ") + " exited " + result.exitCode +
                            (if (tail.isEmpty()) "" else " — " + tail),
                    ),
                ),
            )
        } finally {
            git(repo, "worktree", "remove", "--force", tmp.absolutePath)
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    /** 跳过验收：必须留痕，否则「门没生效」会以「工单莫名通过」的形式重新查一遍。 */
    private fun skipped(reason: String): Report {
        RelayLog.info("verify", "skipped: $reason")
        return Report(listOf(Item(VERIFICATION_ROW, passed = true, detail = "skipped: $reason", skipped = true)))
    }

    private data class ExecResult(val exitCode: Int, val output: String, val millis: Long)

    /** 起子进程跑命令；起不来（IOException）返回 null —— 那是环境问题，不是产物问题。 */
    private fun exec(cwd: File, cmd: List<String>, timeoutMs: Long): ExecResult? {
        val started = System.currentTimeMillis()
        val process = runCatching {
            ProcessBuilder(cmd)
                .directory(cwd)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // 只留尾部：失败原因通常在最后几行，全量会撑爆卡面横幅。
                        if (output.length < 8_000) output.appendLine(line)
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            process.destroyForcibly()
            return ExecResult(124, "timed out after ${timeoutMs}ms", System.currentTimeMillis() - started)
        }
        reader.join(2_000)
        return ExecResult(process.exitValue(), output.toString(), System.currentTimeMillis() - started)
    }

    private data class GitResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun git(cwd: File, vararg args: String): GitResult = runCatching {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(cwd)
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        GitResult(process.exitValue(), out, err)
    }.getOrDefault(GitResult(-1, "", "git unavailable"))
}
