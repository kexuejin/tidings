// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Antigravity（agy CLI）接入（形态 A，真 CLI 实测契约）：
 *
 * 行形状的**基准是真 CLI 的 stream-json 输出**（agy 1.2.x，`-p … --output-format
 * stream-json` 实测捕获，见 CliLineEventMapper 的实测注记）：init 信封 / step_update
 * （state + step_type + text_delta）/ result（status）。三件事：
 * 1. 映射按实测契约分流（event 键与 claude/codex 的 type 键无交集）；
 * 2. 发现器 = 显式 env > $PATH > 公开约定目录（含 `~/.local/bin`，agy 的默认安装位）；
 * 3. ModelEnvMapper 认识 agy（凭证继承 Google 登录态 = 空 env 映射，缺失即拒单）。
 */
class AgyCliDriverTest {

    // ↓↓↓ 真 CLI 实测行（2026-10 捕获，截取字段做夹具）—— 不手改形状。

    private val initLine =
        """{"event":"init","conversation_id":"80c23e92-3637-4243-bbab-3ad3abfadfce","init":{"cwd":"/tmp/agy-probe","tools":["ask_question","run_command"]}}"""

    private val userStepLine =
        """{"event":"step_update","step_update":{"conversation_id":"80c23e92","step_index":0,"state":"DONE","step_type":"user_input"}}"""

    private val textActiveLine =
        """{"event":"step_update","step_update":{"conversation_id":"80c23e92","step_index":1,"state":"ACTIVE","step_type":"agent_response","text_delta":"ok"}}"""

    private val toolActiveLine =
        """{"event":"step_update","step_update":{"conversation_id":"80c23e92","step_index":2,"state":"ACTIVE","step_type":"run_command"}}"""

    private val toolDoneLine =
        """{"event":"step_update","step_update":{"conversation_id":"80c23e92","step_index":2,"state":"DONE","step_type":"run_command","duration_seconds":0.4}}"""

    private val successResultLine =
        """{"event":"result","result":{"conversation_id":"80c23e92","status":"SUCCESS","response":"ok","num_turns":1}}"""

    private val failedResultLine =
        """{"event":"result","result":{"conversation_id":"80c23e92","status":"FAILED","response":"boom"}}"""

    @Test
    fun agyLinesMapPerTheRealCliContract() {
        // init：会话锚，裸驱动不用它寻址 → 跳过。
        assertEquals(null, CliLineEventMapper.map(initLine), "init 不产事件")
        // 文本步 ACTIVE 的 delta → TokenDelta。
        val text = CliLineEventMapper.map(textActiveLine)
        assertTrue(text is DriverEvent.TokenDelta && text.delta == "ok", "text_delta → TokenDelta，实际 $text")
        // 用户输入步 DONE：内容随输入走，无事件。
        assertEquals(null, CliLineEventMapper.map(userStepLine), "user_input 无 delta = 无事件")
        // 工具步 ACTIVE → ToolCall（step_index 为 id，step_type 为工具名）。
        val tool = CliLineEventMapper.map(toolActiveLine)
        assertTrue(tool is DriverEvent.ToolCall, "工具步 ACTIVE → ToolCall，实际 $tool")
        assertEquals("step-2", (tool as DriverEvent.ToolCall).toolCallId)
        assertEquals("run_command", tool.tool)
        // 工具步 DONE：完成与否不在行上（退出码裁决）→ 无事件。
        assertEquals(null, CliLineEventMapper.map(toolDoneLine), "工具步 DONE 不报完成")
        // result SUCCESS → null（裁决归退出码）；FAILED → Error（不为没发生的活报完成）。
        assertEquals(null, CliLineEventMapper.map(successResultLine), "SUCCESS 行不预支 Finished")
        val failed = CliLineEventMapper.map(failedResultLine)
        assertTrue(failed is DriverEvent.Error, "FAILED 自报 → Error，实际 $failed")
    }

    @Test
    fun discoveryFindsAgyViaEnvPathAndConventionDirs() {
        val home = Files.createTempDirectory("agy-home").toFile()
        val binDir = home.resolve(".local/bin").apply { mkdirs() }
        val bin = binDir.resolve("agy").apply {
            writeText("#!/bin/sh\nexit 0\n")
            assertTrue(setExecutable(true))
        }
        // 公开约定目录（$HOME/.local/bin 是 agy 的默认安装位）。
        assertEquals(
            bin.absolutePath,
            CliDiscovery.resolveAgyCli(env = mapOf("HOME" to home.absolutePath, "PATH" to "/nonexistent")),
            "约定目录 \$HOME/.local/bin 应命中",
        )
        // 显式 env 优先。
        val dirB = Files.createTempDirectory("agy-disc-b").toFile()
        val pinned = dirB.resolve("agy").apply {
            writeText("#!/bin/sh\nexit 0\n")
            assertTrue(setExecutable(true))
        }
        assertEquals(
            pinned.absolutePath,
            CliDiscovery.resolveAgyCli(
                env = mapOf(CliDiscovery.ENV_AGY_CLI to pinned.absolutePath, "HOME" to home.absolutePath),
            ),
            "显式 env 优先于约定目录",
        )
    }

    @Test
    fun registrationsPinAllThreeResolversInOrder() {
        val drivers = cliDriverRegistrations(
            resolveClaude = { null },
            resolveCodex = { null },
            resolveAgy = { "/bin/true" },
            hint = {},
        )
        assertEquals(listOf("agy-cli"), drivers.map { it.driverId }, "只装了 agy 的宿主只有 agy-cli")
        assertTrue(drivers.single() is CliSubprocessDriver, "无沙箱装配 = 裸驱动")
    }

    @Test
    fun modelEnvMapperKnowsAgyAndRefusesUnknown() {
        val config = ModelProviderConfig(
            providerId = "google",
            apiBaseUrl = "http://x", apiKey = "k", defaultModel = "gemini-pro",
        )
        assertEquals(
            emptyMap(),
            ModelEnvMapper.buildEnvironment(config, "agy"),
            "agy = 凭证继承 Google 登录态，空 env 映射即收编",
        )
        // 拒单判据仍在：未知 targetCli 照旧抛。
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ModelEnvMapper.buildEnvironment(config, "unknown-cli")
        }
    }

    @Test
    fun credentialsLoaderReadsNodeLocalFileOrStaysHonest() {
        val dir = java.nio.file.Files.createTempDirectory("model-cred").toFile()
        // 缺席 = 空表（没配凭证的节点不带模型环境，不编造）。
        assertEquals(emptyMap(), ModelEnvMapper.loadCredentials(dir))
        // 形状齐全的键装载；缺必填键的条目跳过（宁缺毋错）。
        dir.resolve(ModelEnvMapper.CREDENTIALS_FILE).writeText(
            """{"claude":{"apiBaseUrl":"http://x","apiKey":"k","defaultModel":"claude-4"},
               "codex":{"apiBaseUrl":"http://y"},"bad-entry":{}}""",
        )
        val loaded = ModelEnvMapper.loadCredentials(dir)
        assertEquals(setOf("claude"), loaded.keys, "缺 apiKey 的条目跳过")
        assertEquals("claude-4", loaded["claude"]?.defaultModel)
    }

    @Test
    fun sandboxedAgyStreamsTextAndDeliversDiff() {
        val root = Files.createTempDirectory("agy-sandbox").toFile()
        val host = Files.createTempDirectory("agy-host").toFile()
        host.resolve("seed.txt").writeText("seed\n")
        runGit(host, "init")
        runGit(host, "config", "user.email", "t@t.invalid")
        runGit(host, "config", "user.name", "t")
        runGit(host, "add", ".")
        runGit(host, "commit", "-m", "init")
        // 假 agy：吐真 CLI 的三行形状 + 改一个文件。
        val cli = root.resolve("agy").apply {
            writeText(
                "#!/bin/sh\n" +
                    "printf '%s\\n' '${initLine.replace("'", "'\\''")}'\n" +
                    "printf '%s\\n' '${textActiveLine.replace("'", "'\\''")}'\n" +
                    "printf '%s\\n' '${successResultLine.replace("'", "'\\''")}'\n" +
                    "echo change >> seed.txt\n" +
                    "exit 0\n",
            )
            assertTrue(setExecutable(true))
        }
        val sandbox = WorktreeSandboxExecutor(sandboxRoot = root.resolve("ws"))
        val driver = SandboxedCliDriver(
            driverId = "agy-cli",
            displayName = "Antigravity (agy CLI)",
            cliPath = cli.absolutePath,
            argSpec = { prompt -> listOf("-p", prompt, "--output-format", "stream-json") },
            sandbox = sandbox,
        )
        val collected = java.util.concurrent.LinkedBlockingQueue<DriverEvent>()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + kotlinx.coroutines.Dispatchers.Default)
            .launch {
                val session = driver.openSession(host.absolutePath, null)
                driver.executeTask(session.sessionId, "p") { collected.put(it) }
                done.complete(Unit)
            }
        runBlocking { kotlinx.coroutines.withTimeout(30_000) { done.await() } }
        val events = collected.toList()
        assertTrue(
            events.any { it is DriverEvent.TokenDelta && it.delta == "ok" },
            "agy 文本增量按实测契约上流：$events",
        )
        assertTrue(events.any { it is DriverEvent.Card }, "有 diff 就有交付卡")
        assertTrue(events.last() is DriverEvent.Finished, "退出码 0 = Finished 收尾")
    }

    private fun runGit(cwd: java.io.File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args.toList()).directory(cwd).start()
        proc.waitFor()
    }
}
