// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 装配侧的凭证注入：**从 `cliDriverRegistrations` 一路走到沙箱子进程**。
 *
 * [CliProviderEnvTest] 只验白名单这个纯函数；这里验的是它**真的接上了** ——
 * 中间隔着 `SandboxedCliDriver` → `WorktreeSandboxExecutor` → `ProcessBuilder`
 * 三层，任何一层把 env 丢了，沙箱里的 CLI 就又是「装是装了，一跑没凭证」。
 *
 * 探针是假 CLI 脚本：把两只变量的值写进 worktree 里的文件，再看 harvest 出来的
 * patch。真 git + 假 CLI，与 `WorktreeSandboxExecutorTest` 同一口径。
 */
class CliProviderEnvWiringTest {

    private fun tempDir(): File = Files.createTempDirectory("provider-env-test").toFile()

    private fun script(dir: File, name: String, body: String): String {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\n$body\n")
        assertTrue(f.setExecutable(true), "假 CLI 必须可执行")
        return f.absolutePath
    }

    private fun runGit(cwd: File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        val err = proc.errorStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor(), "git ${args.joinToString(" ")} 失败: $err")
    }

    private fun gitRepo(dir: File) {
        dir.mkdirs()
        runGit(dir, "init")
        runGit(dir, "config", "user.email", "test@example.invalid")
        runGit(dir, "config", "user.name", "Provider Env Test")
        File(dir, "seed.txt").writeText("seed\n")
        runGit(dir, "add", ".")
        runGit(dir, "commit", "-m", "init")
    }

    @Test
    fun allowlistedProviderEnvReachesTheSandboxedChildAndNothingElseDoes() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        // 非白名单变量的名字刻意取得「一定不会在别处存在」—— 用 AWS_SECRET_ACCESS_KEY
        // 这种通用名，跑测试的机器上万一真有值，断言就成了环境依赖。
        val cli = script(
            root,
            "cli",
            """
            printf '{"type":"text","text":"probe"}\n'
            printf '%s\n' "${'$'}ANTHROPIC_BASE_URL" > envprobe.txt
            printf '%s\n' "${'$'}{TENON_PROBE_SECRET_NOT_ALLOWLISTED-unset}" > envprobe-secret.txt
            exit 0
            """.trimIndent(),
        )
        val hints = mutableListOf<String>()
        val drivers = cliDriverRegistrations(
            resolveClaude = { cli },
            resolveCodex = { null },
            resolveAgy = { null },
            resolveOpencode = { null },
            resolveDsh = { null },
            hint = { hints += it },
            sandbox = WorktreeSandboxExecutor(sandboxRoot = root),
            env = mapOf(
                "ANTHROPIC_BASE_URL" to "https://gw.example/v1",
                "ANTHROPIC_API_KEY" to "sk-must-not-appear-anywhere",
                "TENON_PROBE_SECRET_NOT_ALLOWLISTED" to "must-not-reach-child",
            ),
        )
        assertEquals(1, drivers.size, "只发现 claude，就该只装配一个驱动")

        val events = mutableListOf<DriverEvent>()
        val driver = drivers.single()
        val session = driver.openSession(host.absolutePath, null)
        driver.executeTask(session.sessionId, "probe env") { events += it }

        assertTrue(
            events.any { it is DriverEvent.Finished },
            "任务必须跑完（否则注入与否无从判定）：" + events.joinToString { it.toString() },
        )
        val patchFile = File(
            events.filterIsInstance<DriverEvent.Artifact>().single { it.name == "patch.diff" }.path,
        )
        val patch = patchFile.readText()

        // 1. 白名单内的：真的到了子进程（值出现在 CLI 写出的文件里）。
        assertTrue(
            patch.contains("https://gw.example/v1"),
            "ANTHROPIC_BASE_URL 必须到得了沙箱里的 CLI，否则自定义上游等于没配：\n$patch",
        )
        // 2. 白名单外的：没到子进程。
        assertTrue(
            patch.contains("unset"),
            "非白名单变量不许进子进程（文件里该是 unset）：\n$patch",
        )
        assertFalse(
            patch.contains("must-not-reach-child"),
            "非白名单变量的值出现在沙箱产物里 = 白名单没生效：\n$patch",
        )
        // 3. 凭证值不落盘：产物里不许出现 key。
        assertFalse(
            patch.contains("sk-must-not-appear-anywhere"),
            "凭证值不许出现在产物文件里：\n$patch",
        )
        // 4. 提示行只报变量名。
        val announce = hints.single { it.contains("provider env") }
        assertTrue(announce.contains("ANTHROPIC_BASE_URL"), "提示行要能看出注入了什么：$announce")
        assertFalse(announce.contains("sk-must-not-appear-anywhere"), "提示行不许含值：$announce")
        assertFalse(announce.contains("gw.example"), "提示行不许含值：$announce")
    }

    @Test
    fun noProviderEnvConfiguredMeansNoInjectionAndNoHint() = runBlocking {
        val root = tempDir()
        val host = tempDir()
        gitRepo(host)
        val cli = script(
            root,
            "cli",
            """
            printf '%s\n' "${'$'}{ANTHROPIC_BASE_URL-unset}" > envprobe.txt
            exit 0
            """.trimIndent(),
        )
        val hints = mutableListOf<String>()
        val drivers = cliDriverRegistrations(
            resolveClaude = { cli },
            resolveCodex = { null },
            resolveAgy = { null },
            resolveOpencode = { null },
            resolveDsh = { null },
            hint = { hints += it },
            sandbox = WorktreeSandboxExecutor(sandboxRoot = root),
            env = emptyMap(),
        )
        val events = mutableListOf<DriverEvent>()
        val driver = drivers.single()
        val session = driver.openSession(host.absolutePath, null)
        driver.executeTask(session.sessionId, "probe env") { events += it }
        val patch = File(
            events.filterIsInstance<DriverEvent.Artifact>().single { it.name == "patch.diff" }.path,
        ).readText()
        assertTrue(patch.contains("unset"), "没配上游时不该凭空多出变量：\n$patch")
        assertTrue(
            hints.none { it.contains("provider env") },
            "空集不该打印提示行（没配自定义上游是常态）：$hints",
        )
    }
}
