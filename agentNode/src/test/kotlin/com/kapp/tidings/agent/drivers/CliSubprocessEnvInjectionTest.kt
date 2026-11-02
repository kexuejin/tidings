// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * envOverrides 注入的行为判据（升级轮 T-3a）：凭证环境变量**真的进子进程**。
 *
 * 探针形状：/bin/sh -c '[ -n "$TIDINGS_PROBE_TOKEN" ]' —— 变量在 ⇒ 退出码 0（Finished），
 * 不在 ⇒ 退出码 1（Error）。退出码是子进程对环境的最诚实回执，不经 driver 的行解析。
 *
 * 反向验证：摘掉 envOverrides.forEach 注入 ⇒ 探针退出 1 ⇒ 用例红在 Error。
 */
class CliSubprocessEnvInjectionTest {

    @Test
    fun credentialEnvVarsReachTheSubprocess() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<DriverEvent>()
        val driver = CliSubprocessDriver(
            driverId = "env-probe",
            displayName = "Env Probe",
            cliPath = "/bin/sh",
            argSpec = { _ -> listOf("-c", "[ -n \"\$TIDINGS_PROBE_TOKEN\" ]") },
            envOverrides = mapOf("TIDINGS_PROBE_TOKEN" to "sk-injected-value"),
        )
        val handle = driver.openSession("/tmp", null)
        driver.executeTask(handle.sessionId, "probe") { events += it }
        assertTrue(
            events.any { it is DriverEvent.Finished },
            "注入的环境变量必须让探针退出 0（变量真的进了子进程环境）；events=" +
                events.joinToString { it.toString() },
        )
        assertTrue(
            events.none { it is DriverEvent.Error },
            "探针不许报错（报错 = 变量没进子进程环境）：events=" + events.joinToString { it.toString() },
        )
    }

    /**
     * `PWD` 跟着工作区走，而不是继承来的宿主目录。
     *
     * `ProcessBuilder.directory()` 只改真实 cwd；`PWD` 是**继承来的环境变量**，不会
     * 自己更新。opencode 按 `$PWD` 解析工作区 —— 实测它把 proof.txt 写进了宿主仓库根，
     * worktree 里空空如也。
     *
     * 探针用 python3 而不是 `/bin/sh`：POSIX shell 启动时会拿 getcwd 校验并**重写**
     * PWD，用它来测等于自证（不修也能过）。`os.environ` 是进程拿到的那份原始环境。
     * 输出必须是 JSON 才穿得过 CliLineEventMapper（非 JSON 行按设计如实丢弃）。
     */
    @Test
    fun pwdFollowsTheWorkspaceInsteadOfTheHostDirectory() = kotlinx.coroutines.runBlocking {
        val workspace = java.nio.file.Files.createTempDirectory("pwd-probe").toFile()
        val events = mutableListOf<DriverEvent>()
        val driver = CliSubprocessDriver(
            driverId = "pwd-probe",
            displayName = "PWD Probe",
            cliPath = "python3",
            argSpec = { _ ->
                listOf(
                    "-c",
                    "import os,json;print(json.dumps({'type':'text','text':os.environ.get('PWD','')}))",
                )
            },
        )
        val handle = driver.openSession(workspace.absolutePath, null)
        driver.executeTask(handle.sessionId, "probe") { events += it }
        val seen = events.filterIsInstance<DriverEvent.TokenDelta>().joinToString("") { it.delta }
        assertEquals(
            workspace.absolutePath,
            seen,
            "子进程的 PWD 必须是工作区 —— 否则按 \$PWD 解析工作区的 CLI 会把产物写到宿主目录",
        )
    }
}
