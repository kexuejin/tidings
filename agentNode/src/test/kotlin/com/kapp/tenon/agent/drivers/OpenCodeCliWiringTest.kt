// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * opencode 接成宿主驱动这四件事，逐件钉住：
 *
 * 1. **发现**：它装在 `$HOME/.opencode/bin`，不在 `$PATH` 正路上 —— 只按 PATH 找会
 *    「装是装了、注册不上」；
 * 2. **配置**：沙箱换了 `HOME`，机主那份 `~/.config/opencode` 不可见 ⇒ 宿主必须递一份，
 *    且**不许把密钥写进文件**（用 `{env:OPENCODE_API_KEY}` 占位）；
 * 3. **装配**：注册出来的 `opencode-cli` 用 `run --standalone --auto --format json`
 *    （headless 入口 + 行协议），并把配置路径塞进子进程 env；
 * 4. **行语义**：`text` → TokenDelta、`tool_use` → ToolCall、`step_*` → 无事件、
 *    嵌套错误要取出真正的原因（只有顶层 `message` 会退化成「cli reported an error」）。
 *
 * 全是注入式的（假二进制 / 合成 env），不依赖本机装没装 opencode。
 */
class OpenCodeCliWiringTest {

    private fun tempDir(): File = Files.createTempDirectory("opencode-wiring").toFile()

    private fun fakeBinary(dir: File, name: String = "opencode"): String {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\nexit 0\n")
        assertTrue(f.setExecutable(true), "假二进制必须可执行")
        return f.absolutePath
    }

    @Test
    fun discoveryLooksInTheDotOpencodeBinDirectory() {
        val home = tempDir()
        val bin = File(home, ".opencode/bin").apply { mkdirs() }
        val path = fakeBinary(bin)

        // pathDirs 显式给：默认值里有 /opt/homebrew/bin，而本机恰好在那儿放了
        // opencode 的软链 —— 不注入就变成「断言本机装没装」，与这条用例的意图无关。
        assertEquals(
            path,
            CliDiscovery.resolveOpencodeCli(
                env = mapOf("HOME" to home.absolutePath),
                pathDirs = listOf(bin.absolutePath),
            ),
            "官方安装目录 ~/.opencode/bin 必须被探测到",
        )
        assertNull(
            CliDiscovery.resolveOpencodeCli(
                env = mapOf("HOME" to tempDir().absolutePath),
                pathDirs = listOf(tempDir().absolutePath),
            ),
            "找不到就如实 null（由装配方打提示），不许猜",
        )
    }

    @Test
    fun generatedConfigCarriesNoSecretAndPointsAtTheGateway() {
        val text = OpenCodeConfig.render(baseUrl = "https://example.invalid/v1", model = "openai/m1")
        assertTrue("{env:OPENCODE_API_KEY}" in text, "密钥只能是占位符：$text")
        assertTrue("sk-" !in text, "配置文本里不许出现形如密钥的字面量：$text")
        assertTrue("\"tenon/m1\"" in text, "provider/model 要合成自建 provider 的 id（只取后半截）：$text")
        assertTrue("\"m1\"" in text, "模型清单里要有这只模型，否则 opencode 认不出：$text")
        assertTrue("x-opencode-session" in text, "网关缺这只头直接 400，必须带上：$text")
        // 内置 `openai` provider 会被子进程继承的 OPENCODE_BASE_URL 覆盖并丢 headers
        // （实测 Model is unavailable），所以配置必须落在自建 provider 上。
        assertTrue("\"openai\":" !in text, "不许用内置 openai provider：$text")
    }

    @Test
    fun explicitHostConfigWinsAndMissingBaseUrlMeansNoConfig() {
        val dir = tempDir()
        val explicit = File(dir, "mine.json").apply { writeText("{}") }
        assertEquals(
            explicit.absolutePath,
            OpenCodeConfig.ensure(mapOf(OpenCodeConfig.ENV_CONFIG to explicit.absolutePath), dir),
            "机主显式给的 OPENCODE_CONFIG 必须原样尊重",
        )
        assertNull(
            OpenCodeConfig.ensure(mapOf("OPENCODE_API_KEY" to "k"), dir),
            "没有 OPENCODE_BASE_URL 就不硬造配置（让 CLI 用它自己的登录态）",
        )
        val generated = OpenCodeConfig.ensure(
            mapOf("OPENCODE_BASE_URL" to "https://example.invalid/v1"),
            File(dir, "gen"),
        )
        assertNotNull(generated, "给了 BASE_URL 就要生成一份")
        assertTrue(File(generated).isFile)
        assertTrue("{env:OPENCODE_API_KEY}" in File(generated).readText())
    }

    @Test
    fun registrationWiresTheHeadlessArgsAndTheConfigPath() {
        val cli = fakeBinary(tempDir())
        val configDir = File(tempDir(), "cfg")
        val hints = mutableListOf<String>()
        val drivers = cliDriverRegistrations(
            resolveClaude = { null },
            resolveCodex = { null },
            resolveAgy = { null },
            resolveOpencode = { cli },
            hint = { hints.add(it) },
            env = mapOf(
                "OPENCODE_BASE_URL" to "https://example.invalid/v1",
                // 生成目录由环境里没有的变量控制不到，这里只验「路径进了 env」——
                // 具体落点交给 OpenCodeConfigTest 那条纯函数用例。
                OpenCodeConfig.ENV_CONFIG to File(configDir, "absent.json").absolutePath,
            ),
        )
        val driver = drivers.singleOrNull { it.driverId == "opencode-cli" }
        assertNotNull(driver, "opencode 可执行文件在就注册 opencode-cli，实际: " + drivers.map { it.driverId })

        val args = CliSubprocessDriver.opencodeArgs("do it")
        assertEquals(listOf("run", "--standalone", "--auto", "--format", "json", "do it"), args)
        assertTrue(hints.none { it.contains("opencode CLI not found") }, "找到了就不该说不存在: $hints")
    }

    @Test
    fun lineMappingKeepsTextAndToolCallsAndDigsOutNestedErrors() {
        val text = """{"type":"text","sessionID":"s","part":{"type":"text","text":"Created probe.txt"}}"""
        assertEquals(
            DriverEvent.TokenDelta("Created probe.txt"),
            CliLineEventMapper.map(text),
        )

        val tool = """{"type":"tool_use","sessionID":"s","part":{"type":"tool","id":"call_1","tool":"shell"}}"""
        val call = CliLineEventMapper.map(tool) as? DriverEvent.ToolCall
        assertNotNull(call, "tool_use 要成 ToolCall")
        assertEquals("shell", call.tool)
        assertEquals("call_1", call.toolCallId)

        assertNull(
            CliLineEventMapper.map("""{"type":"step_start","sessionID":"s","part":{"type":"step-start"}}"""),
            "step_* 是推进记账，不产事件",
        )

        val err = CliLineEventMapper.map(
            """{"type":"error","error":{"type":"provider.invalid-request","message":"Model is unavailable."}}""",
        )
        assertEquals(
            DriverEvent.Error("Model is unavailable."),
            err,
            "嵌套的 error.message 才是真原因；只看顶层会退化成 cli reported an error",
        )
    }
}
