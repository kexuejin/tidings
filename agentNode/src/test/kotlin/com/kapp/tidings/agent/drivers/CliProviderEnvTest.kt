// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CliProviderEnv] 的白名单判据（纯函数，合成输入，不读本机环境）。
 *
 * 钉住三件事：
 * 1. **只搬白名单里的**：`AWS_SECRET_ACCESS_KEY` 之类不许跟着进子进程；
 * 2. **按 CLI 分离**：claude 拿不到 opencode 的 key，反之亦然（少给一只就少一个泄漏面）；
 * 3. **值不进任何可打印文本**：[describe] 只出变量名 —— 它进的是日志与提示行。
 */
class CliProviderEnvTest {

    private val gateway = mapOf(
        "ANTHROPIC_BASE_URL" to "https://opencode.ai/zen/go",
        "ANTHROPIC_API_KEY" to "sk-claude-secret",
        "ANTHROPIC_MODEL" to "deepseek-v4.1-flash",
        "OPENCODE_API_KEY" to "oc_sk_codex_secret",
        "CODEX_HOME" to "/tmp/codex-home",
        "AWS_SECRET_ACCESS_KEY" to "aws-must-not-leak",
        "PATH" to "/usr/bin:/bin",
        "HOME" to "/Users/someone",
    )

    @Test
    fun claudeOnlyGetsItsOwnAllowlist() {
        val injected = CliProviderEnv.forClaude(gateway)
        assertEquals(
            setOf("ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY", "ANTHROPIC_MODEL"),
            injected.keys,
            "只该带 Anthropic 那几只",
        )
        assertEquals("https://opencode.ai/zen/go", injected["ANTHROPIC_BASE_URL"])
        for (leak in listOf("AWS_SECRET_ACCESS_KEY", "PATH", "HOME", "OPENCODE_API_KEY", "CODEX_HOME")) {
            assertTrue(leak !in injected, "$leak 不许进 claude 的子进程环境")
        }
    }

    @Test
    fun codexGetsConfigDirAndGatewayKeyButNoAnthropicVars() {
        val injected = CliProviderEnv.forCodex(gateway)
        assertEquals(setOf("CODEX_HOME", "OPENCODE_API_KEY"), injected.keys)
        assertTrue("ANTHROPIC_API_KEY" !in injected, "codex 不认 Anthropic 变量，给了只是多一个泄漏面")
    }

    @Test
    fun opencodeGetsItsOwnProviderVars() {
        val injected = CliProviderEnv.forOpencode(gateway)
        assertEquals(setOf("OPENCODE_API_KEY"), injected.keys)
    }

    @Test
    fun agyInjectsNothingBecauseItHasNoCustomUpstream() {
        assertTrue(CliProviderEnv.forAgy(gateway).isEmpty(), "agy 走 Google 登录态，没有可配的上游")
    }

    @Test
    fun unknownDriverGetsNothing() {
        assertTrue(CliProviderEnv.forDriver("some-future-cli", gateway).isEmpty(), "认不出的 driverId 不猜、不乱给")
        assertEquals(setOf("ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY", "ANTHROPIC_MODEL"), CliProviderEnv.forDriver("claude-cli", gateway).keys)
    }

    @Test
    fun blankValuesAreTreatedAsAbsent() {
        val injected = CliProviderEnv.forClaude(
            mapOf("ANTHROPIC_BASE_URL" to "  ", "ANTHROPIC_API_KEY" to ""),
        )
        assertTrue(injected.isEmpty(), "空值送进去等于把 CLI 指向空上游 —— 当没有处理")
    }

    @Test
    fun nothingConfiguredMeansEmptyMapNotGarbage() {
        assertTrue(CliProviderEnv.forClaude(emptyMap()).isEmpty(), "没配自定义上游是常态，必须是空集")
    }

    @Test
    fun describeReportsNamesOnlyNeverValues() {
        val injected = CliProviderEnv.forClaude(gateway)
        val text = requireNotNull(CliProviderEnv.describe(injected))
        assertTrue(text.contains("ANTHROPIC_BASE_URL"), "要能看出注入了什么：$text")
        assertTrue(text.contains("ANTHROPIC_MODEL"))
        // 这一行会进 stdout / 日志，值一旦进来就是泄漏。
        assertTrue("sk-claude-secret" !in text, "提示行不许含值：$text")
        assertTrue("opencode.ai" !in text, "提示行不许含值：$text")
        assertEquals(
            "ANTHROPIC_API_KEY, ANTHROPIC_BASE_URL, ANTHROPIC_MODEL",
            text,
            "变量名按字母序，输出稳定（便于比对日志）",
        )
    }

    @Test
    fun describeIsNullWhenNothingWasInjected() {
        assertNull(CliProviderEnv.describe(emptyMap()), "空集不该打印一行噪声")
    }
}
