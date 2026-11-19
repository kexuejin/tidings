// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.testsupport.GuardSourceScan
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * agy 登录态预检的分类逻辑（真机实测缺陷：未登录时每条 quest 白烧 worktree +
 * print 模式 60s 授权窗——预检命中未登录就快败，省掉整轮）。
 *
 * 分类纪律：**只拦「确定未登录」**——其余一切（探针超时、命令缺失、网络问题）
 * 都放行给正式运行与 auth-wait 检测兜底。预检自己不出错，也不替运行做决定。
 *
 * **反向验证**：把「exit 0 之外一律 UNKNOWN」的过度保守版放回去，
 * `notLoggedInIsPositivelyDetected` 立刻红——这条测试确实在区分两个世界。
 */
class AgyAuthProbeTest {

    @Test
    fun exitZeroIsAuthenticated() {
        assertEquals(
            AgyAuthProbe.State.AUTHENTICATED,
            AgyAuthProbe.classify(0, "gemini-3.8-flash-high\tGemini 3.8 Flash (High)\n"),
        )
    }

    @Test
    fun notLoggedInIsPositivelyDetected() {
        assertEquals(
            AgyAuthProbe.State.NOT_AUTHENTICATED,
            AgyAuthProbe.classify(
                1,
                "error getting token source: You are not logged into Antigravity.",
            ),
        )
    }

    @Test
    fun markerIsCaseInsensitive() {
        assertEquals(
            AgyAuthProbe.State.NOT_AUTHENTICATED,
            AgyAuthProbe.classify(1, "You Are Not Logged Into ANTIGRAVITY."),
        )
    }

    @Test
    fun otherFailuresStayUnknownSoTheRunIsNotBlocked() {
        // 网络问题 / 命令缺失 / 任意未知失败：放行——预检不替正式运行做决定。
        assertEquals(AgyAuthProbe.State.UNKNOWN, AgyAuthProbe.classify(1, "connection refused"))
        assertEquals(AgyAuthProbe.State.UNKNOWN, AgyAuthProbe.classify(2, ""))
        assertEquals(AgyAuthProbe.State.UNKNOWN, AgyAuthProbe.classify(127, "command not found"))
    }

    @Test
    fun negativeExitNeverCountsAsAuthenticated() {
        assertEquals(AgyAuthProbe.State.UNKNOWN, AgyAuthProbe.classify(-1, ""))
    }

    /** 真实日志形态（cli.log 摘录）端到端过一遍分类。 */
    @Test
    fun classifiesTheRealCliLogShape() {
        val realLog = """
            W cache.go:157] Cache(loadCodeAssistResponse): Singleflight refresh failed: error getting token source: You are not logged into Antigravity.
            E errorreport.go:307] error getting token source: You are not logged into Antigravity.
        """.trimIndent()
        assertEquals(AgyAuthProbe.State.NOT_AUTHENTICATED, AgyAuthProbe.classify(1, realLog))
    }

    // ---- 进程级行为（真实子进程，命令注入；无网络依赖，CI 可跑）----

    @Test
    fun hangingProcessTimesOutAndReturnsUnknown() {
        // 挂起进程（打印后睡 30s，不关 stdout——旧实现 readLine 到 EOF 才
        // waitFor，这里必挂）。探针必须按时返回 UNKNOWN，实测耗时 < 5s。
        val script = File.createTempFile("agy-probe-hang", ".sh").apply {
            writeText("#!/bin/sh\necho 'working'\nsleep 30\n")
            setExecutable(true)
        }
        try {
            val probe = AgyAuthProbe(command = listOf("/bin/sh", script.absolutePath), timeoutMs = 1_000)
            val started = System.currentTimeMillis()
            val state = kotlinx.coroutines.runBlocking { probe.probe() }
            val elapsed = System.currentTimeMillis() - started
            assertEquals(AgyAuthProbe.State.UNKNOWN, state, "挂起进程必须超时放行（不替运行做决定）")
            assertTrue(elapsed < 5_000, "超时必须生效（1s 预算 + destroy；实测 ${elapsed}ms）")
        } finally {
            script.delete()
        }
    }

    @Test
    fun missingCommandReturnsUnknownInsteadOfThrowing() {
        // 本机没装 agy 时 ProcessBuilder.start() 抛 IOException(error=2)。
        // 曾经的 bug：start() 在 try 外，异常冒到 UI 弹模态错误框、卡死配置页。
        // 命令缺失与超时同级 = UNKNOWN 放行，绝不当成「确定未登录」也不抛出。
        val probe = AgyAuthProbe(
            command = listOf("/nonexistent/tenon-agy-probe-command-does-not-exist"),
            timeoutMs = 5_000,
        )
        kotlinx.coroutines.runBlocking {
            assertEquals(AgyAuthProbe.State.UNKNOWN, probe.probe())
        }
    }

    @Test
    fun normalProcessIsAuthenticatedAndMemoized() {
        // 正常脚本退出 0 + 列表输出：AUTHENTICATED 且按会话记忆（第二次零执行）。
        val script = File.createTempFile("agy-probe-ok", ".sh").apply {
            writeText("#!/bin/sh\necho 'model-a\nmodel-b'\nexit 0\n")
            setExecutable(true)
        }
        try {
            var executions = 0
            val probe = AgyAuthProbe(command = listOf("/bin/sh", script.absolutePath), timeoutMs = 5_000).also {
                it.probeHook = { executions++ }
            }
            kotlinx.coroutines.runBlocking {
                assertEquals(AgyAuthProbe.State.AUTHENTICATED, probe.probe())
                assertEquals(AgyAuthProbe.State.AUTHENTICATED, probe.probe(), "记忆：第二次不重跑")
            }
            assertEquals(1, executions, "已登录结果按会话记忆（不重复 6s 探测）")
        } finally {
            script.delete()
        }
    }

    @Test
    fun invalidationReprobesAfterAuthWaitDetected() {
        val script = File.createTempFile("agy-probe-inv", ".sh").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        try {
            val probe = AgyAuthProbe(command = listOf("/bin/sh", script.absolutePath), timeoutMs = 5_000)
            kotlinx.coroutines.runBlocking { assertEquals(AgyAuthProbe.State.AUTHENTICATED, probe.probe()) }
            probe.invalidate()
            var reprobeRuns = 0
            probe.probeHook = { reprobeRuns++ }
            kotlinx.coroutines.runBlocking { probe.probe() }
            assertTrue(reprobeRuns >= 1, "失效后必须重新探测（token 可能被清）")
        } finally {
            script.delete()
        }
    }

    /** 反向验证：旧的一刀切行为（非 0 退出一律 UNKNOWN）会让未登录检测失效。 */
    @Test
    fun oldAlwaysUnknownBehaviorWouldFailThePositiveDetection() {
        val oldBehavior: (Int, String) -> AgyAuthProbe.State = { code, _ ->
            if (code == 0) AgyAuthProbe.State.AUTHENTICATED else AgyAuthProbe.State.UNKNOWN
        }
        val state = oldBehavior(1, "You are not logged into Antigravity.")
        assertTrue(
            state != AgyAuthProbe.State.NOT_AUTHENTICATED,
            "旧行为确实漏检——证明本测试区分得出两个世界",
        )
    }

    // ---- 发现器装配（P2-b）：探针与执行/目录探针必须看到同一个 agy ----

    private fun fakeAgy(dir: String, name: String = CliDiscovery.AGY_BIN): String {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\nexit 0\n")
        f.setExecutable(true)
        return f.absolutePath
    }

    @Test
    fun explicitEnvOverridePicksThatAgy() {
        // TENON_AGY_CLI 显式指定：发现器最高优先级，探针必须用那只，不是 PATH 里的 agy。
        val dir = Files.createTempDirectory("agy-probe-env").toFile().absolutePath
        val chosen = fakeAgy(dir, "my-agy")
        val other = fakeAgy(dir, "other-agy")
        val command = AgyAuthProbe.resolvedCommand(
            env = mapOf(CliDiscovery.ENV_AGY_CLI to chosen),
            pathDirs = listOf(dir),
        )
        assertEquals(chosen, command.first())
        assertEquals(listOf(chosen, "models"), command)
    }

    @Test
    fun pathDirsAreSearchedWhenNoExplicitOverride() {
        // GUI 最小 PATH / ~/.local/bin：发现器按 pathDirs 找到可执行文件，探针用绝对路径启动。
        val dir = Files.createTempDirectory("agy-probe-path").toFile().absolutePath
        val found = fakeAgy(dir)
        val command = AgyAuthProbe.resolvedCommand(env = emptyMap(), pathDirs = listOf(dir))
        assertEquals(found, command.first(), "必须经发现器按 PATH 目录找到 agy 的绝对路径")
    }

    @Test
    fun missingAgyFallsBackToBareNameAndStartFailureIsUnknown() {
        // 没装时回落裸名 => start() 抛 IOException => catch 里 UNKNOWN 放行。
        val command = AgyAuthProbe.resolvedCommand(env = emptyMap(), pathDirs = listOf("/nonexistent/tenon-agy"))
        assertEquals(listOf(CliDiscovery.AGY_BIN, "models"), command)
        val probe = AgyAuthProbe(command = command)
        kotlinx.coroutines.runBlocking { assertEquals(AgyAuthProbe.State.UNKNOWN, probe.probe()) }
    }

    @Test
    fun productionConstructionSitesGoThroughDiscovery() {
        // 变异探针 1：把 resolvedCommand 改回 listOf("agy","models") ⇒ 下面两条红。
        val probeCode = GuardSourceScan.stripComments(
            GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/AgyAuthProbe.kt"),
        )
        assertTrue(
            "CliDiscovery.resolveAgyCli(env, pathDirs)" in probeCode,
            "resolvedCommand 必须走 CliDiscovery.resolveAgyCli",
        )
        assertTrue("command: List<String> = resolvedCommand()" in probeCode, "默认构造必须走发现器")

        // 变异探针 2：把 snapshotWithDefaults 的构造改回 AgyAuthProbe() 裸默认 ⇒ 此条红。
        // 裸默认读的是诊断进程自己的 env，合成 env 注入的 agy 路径就失效了（诊断假绿）。
        val doctorCode = GuardSourceScan.stripComments(
            GuardSourceScan.read("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/DriverAuthJudgement.kt"),
        )
        assertTrue(
            "AgyAuthProbe(command = AgyAuthProbe.resolvedCommand(env))" in doctorCode,
            "诊断装配必须把传入的 env 交给发现器，不能回落诊断进程自己的 PATH",
        )
    }
}
