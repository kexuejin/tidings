// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.nio.file.Files
import com.kapp.tenon.agent.drivers.CliProviderEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **真 agent CLI** 的 A/B 两轮取证（默认跳过）。
 *
 * ## 它补的是哪一条
 *
 * §13.11 的「真 agent CLI 全链路」长期只能靠桩：桩验的是编排链路，验不了
 * 「真模型的产物能不能被 `extractFileFromPatch` / harvest 抽出来」。等 §13.14
 * 把网关打通、`PWD` 与凭证注入补上之后，这条才真的能跑。
 *
 * ## 为什么默认跳过
 *
 * 它要花真钱、真网络、真模型时间（两轮 × 数十秒），而且结论取决于「本机装没装
 * claude + 有没有配上游」—— 那正是 `envDependentTestGate` 要挡的东西。所以它
 * 只在 `TENON_REAL_CLI=1` 时执行，其余情况打一行跳过原因就返回；CI 的确定性
 * 不受影响。
 *
 * ## 怎么跑
 *
 * ```sh
 * ANTHROPIC_BASE_URL=https://opencode.ai/zen/go \
 * ANTHROPIC_API_KEY=... ANTHROPIC_MODEL=deepseek-v4.1-flash \
 * ANTHROPIC_SMALL_FAST_MODEL=deepseek-v4.1-flash \
 * ANTHROPIC_CUSTOM_HEADERS="x-opencode-session: claude-code" \
 * TENON_REAL_CLI=1 \
 *   ./gradlew :agentNode:test --tests "*RealCliChainEvidenceTest*" --info
 * ```
 *
 * 凭证走 [CliProviderEnv] 的白名单进子进程，**不落盘**（这也是它验的第二件事：
 * 沙箱把 `HOME` 隔离之后，注入的凭证确实是 CLI 唯一的凭证来源）。
 */
class RealCliChainEvidenceTest {

    /** 显式开关：不给就是跳过，绝不让「本机状态」决定测试结论。 */
    private val enabled: Boolean = System.getenv("TENON_REAL_CLI") == "1"

    private fun tempDir(): File = Files.createTempDirectory("real-cli-chain").toFile()

    private fun runGit(cwd: File, vararg args: String): String {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "git ${args.joinToString(" ")} 失败: $err")
        return out
    }

    private fun gitRepo(dir: File) {
        dir.mkdirs()
        runGit(dir, "init")
        runGit(dir, "config", "user.email", "test@example.invalid")
        runGit(dir, "config", "user.name", "Real CLI Chain")
        File(dir, "seed.txt").writeText("seed\n")
        runGit(dir, "add", ".")
        runGit(dir, "commit", "-m", "init")
    }

    private data class Round(val finished: Boolean, val patchFile: File?, val patchText: String?)

    private suspend fun runRound(driver: AgentDriver, host: File, prompt: String): Round {
        val events = mutableListOf<DriverEvent>()
        val session = driver.openSession(host.absolutePath, null)
        driver.executeTask(session.sessionId, prompt) { events += it }
        events.filterIsInstance<DriverEvent.Error>().forEach { println("[real-cli] error: ${it.message}") }
        val patch = events.filterIsInstance<DriverEvent.Artifact>().firstOrNull { it.name == "patch.diff" }
        return Round(
            finished = events.any { it is DriverEvent.Finished },
            patchFile = patch?.let { File(it.path) },
            patchText = patch?.let { File(it.path).readText() },
        )
    }

    @Test
    fun twoRoundsAccumulateThroughTheRollingBase() = runBlocking {
        val cli = CliDiscovery.resolveClaudeCli()
        // 凭证也是外部资源：沙箱把 HOME 换成隔离目录，登录态看不见 —— 不注入
        // ANTHROPIC_*（见本文件头部的运行命令），真 CLI 装是装了、一跑就没凭证，
        // 只会在沙箱深处失败而不是如实 skip。门必须把这两件事都问到。
        val credentials = CliProviderEnv.forClaude()
        if (!enabled || cli == null || credentials.isEmpty()) {
            // 用 assumption 而不是「return 掉算通过」：后者在报告里是一个绿点，
            // 而这一轮什么都没验 —— 那就是「为没发生的活报完成」。
            // assumption 让它如实显示为 skipped，并带上跳过原因。
            throw org.junit.AssumptionViolatedException(
                "需要 TENON_REAL_CLI=1、本机能找到 claude CLI 且宿主环境带 ANTHROPIC_* 凭证" +
                    "（enabled=$enabled, cli=$cli, credentialKeys=${credentials.keys})",
            )
        }
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val driver = cliDriverRegistrations(
            resolveClaude = { cli },
            resolveCodex = { null },
            resolveAgy = { null },
            // opencode/dsh 的 resolver 不覆盖的话，装了这两个 CLI 的机器会让注册列表
            // 长出第二个元素，下面的 .single() 直接崩 —— 门开了才暴露的机器依赖缺陷
            // （AGENTS.md §2.7：测试结论不许依赖这台机器）。这里只要合成的那一根 claude。
            resolveOpencode = { null },
            resolveDsh = { null },
            hint = { println(it) },
            sandbox = WorktreeSandboxExecutor(sandboxRoot = root),
        ).single()

        // ── A 轮：从 HEAD 建沙箱，让模型写一个文件 ─────────────────────────
        val roundA = runRound(
            driver,
            host,
            "Create a file named a.txt in the current working directory containing exactly " +
                "the text ALPHA on a single line. Do not create or modify any other files.",
        )
        assertTrue(roundA.finished, "A 轮必须跑完（否则后面无意义）")
        val patchA = requireNotNull(roundA.patchText) {
            "A 轮没有 harvest —— 真模型没写出可收割的改动（这正是本用例要抓的形态）"
        }
        assertTrue(patchA.contains("a.txt"), "A 轮产物该含 a.txt：\n$patchA")

        // 模拟编排器的「合流」：apply 进宿主工作树 + 打一份滚动基线快照。
        // （DesktopPatchMerge 在 desktopApp 模块，agentNode 的测试不能依赖它，
        //  这两步就是它做的那两步。）
        assertEquals(0, applyPatch(host, requireNotNull(roundA.patchFile)), "A 轮补丁必须能干净落进宿主工作树")
        val base = MergeBaseRef.snapshot(host)
        assertTrue(base != null, "合流后必须能打出基线快照，否则 B 轮会退回 HEAD")
        assertEquals("ALPHA", File(host, "a.txt").readText().trim(), "A 轮的活真的在宿主工作树上")

        // ── B 轮：起点该是上一轮合并后的快照，不是 HEAD ──────────────────────
        val roundB = runRound(
            driver,
            host,
            "Read the file a.txt in the current working directory. Then create a file named " +
                "b.txt whose content is the exact content of a.txt followed by a new line " +
                "containing BETA. Do not modify a.txt and do not create any other files.",
        )
        assertTrue(roundB.finished, "B 轮必须跑完")
        val patchB = requireNotNull(roundB.patchText) { "B 轮没有 harvest" }

        // 判据 1：B 轮**看得见** A 轮的产物（b.txt 里带着 a.txt 的内容）。
        assertTrue(
            patchB.contains("ALPHA"),
            "B 轮读不到 A 轮写的 a.txt ⇒ 沙箱不是从合并后的基线拉起的（D5 回归）：\n$patchB",
        )
        // 判据 2：B 轮的补丁**只含它自己的改动**，不把 A 轮重打一遍。
        assertFalse(
            patchB.contains("diff --git a/a.txt"),
            "a.txt 没动，不该出现在 B 轮补丁里（说明 harvest 是相对开工基线 diff 的）：\n$patchB",
        )
        assertTrue(patchB.contains("b.txt"), "B 轮产物该含 b.txt：\n$patchB")
        println("[real-cli] A/B 两轮通过：B 轮在合并基线上读到了 A 轮的 a.txt，且只交付自己的改动")
    }

    private fun applyPatch(host: File, patch: File): Int =
        ProcessBuilder(listOf("git", "apply", patch.absolutePath))
            .directory(host)
            .redirectErrorStream(true)
            .start()
            .also { it.inputStream.bufferedReader().readText() }
            .waitFor()
}
