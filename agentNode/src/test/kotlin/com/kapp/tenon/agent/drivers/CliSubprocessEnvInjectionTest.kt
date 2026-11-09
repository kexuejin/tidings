// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * envOverrides 注入的行为判据（升级轮 T-3a）：凭证环境变量**真的进子进程**。
 *
 * 探针形状：/bin/sh -c '[ -n "$TENON_PROBE_TOKEN" ]' —— 变量在 ⇒ 退出码 0（Finished），
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
            argSpec = { _ -> listOf("-c", "[ -n \"\$TENON_PROBE_TOKEN\" ]") },
            envOverrides = mapOf("TENON_PROBE_TOKEN" to "sk-injected-value"),
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

    /**
     * 会话对齐键探针：假 CLI 把子进程环境里的 `TENON_HOOK_SESSION_ID` **原值**打进
     * text 行，经行映射变成 TokenDelta 收回。
     *
     * 判据刻意是「值等于 sessionId」而不是「变量非空」：注入一个错值（比如合成的池键）
     * 同样能让 `[ -n "$VAR" ]` 退出 0，却对不上派单侧登记的 readOnlyToolPolicies，
     * 只读的执行前否决照样不生效。
     *
     * 反向验证：摘掉 CliSubprocessDriver 里的那次注入 ⇒ 探针读到 `<unset>` ⇒ 下面两条用例红。
     */
    private suspend fun probeHookSessionId(
        envOverrides: Map<String, String> = emptyMap(),
    ): Pair<String, String> {
        val events = mutableListOf<DriverEvent>()
        val driver = CliSubprocessDriver(
            driverId = "hook-session-probe",
            displayName = "Hook Session Probe",
            cliPath = "python3",
            argSpec = { _ ->
                listOf(
                    "-c",
                    "import os,json;print(json.dumps({'type':'text','text':os.environ.get('" +
                        DriverKeys.ENV_HOOK_SESSION_ID + "','<unset>')}))",
                )
            },
            envOverrides = envOverrides,
        )
        // 空工作区 = 触发式只读通道的形态（executeCliTask 传 wsPath=null 时落到这里）。
        val handle = driver.openSession("", null)
        driver.executeTask(handle.sessionId, "probe") { events += it }
        assertTrue(
            events.any { it is DriverEvent.Finished },
            "探针必须正常收尾；否则断言读到的是空串，等于没验。events=" +
                events.joinToString { it.toString() },
        )
        return handle.sessionId to
            events.filterIsInstance<DriverEvent.TokenDelta>().joinToString("") { it.delta }
    }

    @Test
    fun hookSessionIdReachesEveryCliSubprocess() = kotlinx.coroutines.runBlocking {
        val (sessionId, seen) = probeHookSessionId()
        assertEquals(
            sessionId,
            seen,
            "TENON_HOOK_SESSION_ID 必须等于派单 sessionId —— 触发通道 wsPath=null 走本裸驱动，" +
                "漏注入就等于执行前的只读否决整条失效",
        )
    }

    @Test
    fun hostSessionIdWinsOverAnEnvOverrideForTheSameKey() = kotlinx.coroutines.runBlocking {
        val (sessionId, seen) = probeHookSessionId(
            mapOf(DriverKeys.ENV_HOOK_SESSION_ID to "spoofed-by-config"),
        )
        assertEquals(
            sessionId,
            seen,
            "宿主派单的 sessionId 不许被 envOverrides 盖掉：盖掉后 hook 拿到的键对不上 " +
                "readOnlyToolPolicies，只读约束静默失效",
        )
    }
}
