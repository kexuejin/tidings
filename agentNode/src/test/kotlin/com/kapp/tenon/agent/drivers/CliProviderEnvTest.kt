// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

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
    fun dshGetsDeepSeekKeyAndOpencodeKeyForCustomRoutes() {
        val env = gateway + mapOf(
            "DEEPSEEK_API_KEY" to "dsh-key",
            "DSH_PERMISSION_MODE" to "danger-full-access",
        )
        val dsh = CliProviderEnv.forDsh(env)
        assertEquals(
            setOf("DEEPSEEK_API_KEY", "OPENCODE_API_KEY", "DSH_PERMISSION_MODE"),
            dsh.keys,
            "dsh 出厂路由需 DEEPSEEK_API_KEY，自定义路由（如 dsh-opencode-go）需 OPENCODE_API_KEY",
        )
        assertEquals("dsh-key", dsh["DEEPSEEK_API_KEY"])
        assertEquals("oc_sk_codex_secret", dsh["OPENCODE_API_KEY"])
    }

    /**
     * opencode 还要**出网路径**（`*_PROXY`）。
     *
     * 真机读数（2026-11-03，v2.2.5）：沙箱只递白名单 env 时，opencode 直连 zen 网关，
     * 网关回 `Model is unavailable`；把宿主的 `HTTPS_PROXY/HTTP_PROXY` 递进去就 200。
     * 它自己不读系统代理设置 —— 少这两只变量，症状长得像「模型不可用」，其实没走到线路上。
     *
     * 只给 opencode：codex / claude 在这台机器上不递代理也能通，别顺手放宽它们的面。
     */
    @Test
    fun opencodeAlsoGetsTheEgressProxyButOtherClisDoNot() {
        val env = gateway + mapOf(
            "HTTPS_PROXY" to "http://127.0.0.1:7890",
            "HTTP_PROXY" to "http://127.0.0.1:7890",
            "https_proxy" to "http://127.0.0.1:7890",
        )
        val opencode = CliProviderEnv.forOpencode(env)
        assertEquals("http://127.0.0.1:7890", opencode["HTTPS_PROXY"])
        assertEquals("http://127.0.0.1:7890", opencode["https_proxy"], "小写变体也要带（Node 侧读小写）")
        assertTrue("HTTPS_PROXY" !in CliProviderEnv.forClaude(env), "claude 不递代理：这轮实测它不需要")
        assertTrue("HTTPS_PROXY" !in CliProviderEnv.forCodex(env), "codex 不递代理：它在本机不递也能通")
    }

    /**
     * `OPENCODE_BASE_URL` **不许**递进子进程。
     *
     * 实测（2026-11-03，同一分钟两次对照）：给子进程这只变量后，opencode 会按它重建
     * provider、丢掉 `OPENCODE_CONFIG` 里的 `headers`，网关随即回 `Model is unavailable`；
     * 去掉它、只留配置路径就 200。上游地址由宿主读写进配置文件，不该再进子进程环境。
     */
    @Test
    fun opencodeBaseUrlStaysOnTheHostSide() {
        val env = gateway + mapOf("OPENCODE_BASE_URL" to "https://opencode.ai/zen/go/v1")
        assertTrue(
            "OPENCODE_BASE_URL" !in CliProviderEnv.forOpencode(env),
            "递进去会让 opencode 重建 provider 并丢掉配置里的 session 头（实测 Model is unavailable）",
        )
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
