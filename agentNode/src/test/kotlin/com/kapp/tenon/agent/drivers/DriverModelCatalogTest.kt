// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模型目录的纯函数 / 注入测试：全部合成输入，不读本机 CLI（envDependentTestGate 口径）。
 *
 * 反向验证点：把 [DriverModelCatalog.parseTwoColumnList] 的「0 条也算 KNOWN」放宽，
 * [exitZeroButNothingParsedStaysUnknown] 立刻红 —— 那条守的是「解析失败被当成没有模型」。
 */
class DriverModelCatalogTest {

    private val agySpec = ModelProbeSpec(
        args = listOf("models"),
        parse = DriverModelCatalog::parseTwoColumnList,
    )

    /**
     * 构造被测目录。`runner` **排在最后**，让 `catalog { … }` 的尾随 lambda 直接绑到它 ——
     * 放第一个参数位时尾随 lambda 会落到 `now: () -> Long` 上（编译器只报「返回类型不匹配」，
     * 看着完全不像参数顺序的错）。
     */
    private fun catalog(
        specs: Map<String, ModelProbeSpec> = mapOf(DriverKeys.AGY to agySpec),
        configSpecs: Map<String, ConfigProbeSpec> = emptyMap(),
        resolver: (String) -> String? = { "/fake/agy" },
        clock: () -> Long = { 0L },
        runner: suspend (List<String>) -> CliAuthProbes.Output,
    ) = DriverModelCatalog(
        specs = specs,
        configSpecs = configSpecs,
        resolver = resolver,
        runner = runner,
        ttlMs = 1_000L,
        now = clock,
    )

    // ---------- 纯解析 ----------

    @Test
    fun parsesTabSeparatedTwoColumnList() {
        val models = DriverModelCatalog.parseTwoColumnList(
            "gemini-pro-high\tGemini Pro (High)\ngpt-120b-medium\tGPT 120B (Medium)\n",
        )
        assertEquals(listOf("gemini-pro-high", "gpt-120b-medium"), models.map { it.id })
        assertEquals("Gemini Pro (High)", models.first().name)
    }

    @Test
    fun singleTokenIdsAreAcceptedButLogLinesAreNot() {
        val models = DriverModelCatalog.parseTwoColumnList(
            // 合并进来的 stderr 日志：首 token 含 `:` 与 `]`，不是模型 id 的形状。
            "I1117 20:22:34 24 main.go:784] failed to open log\n" +
                "claude-sonnet-4-6\n" +
                "ERROR: something happened\n" +
                "\n" +
                "\u001B[32mdeepseek-chat\tDeepSeek Chat\u001B[0m\n",
        )
        // 日志行（首列根本没解析成 token）被跳过；ANSI 转义先剥掉。
        assertEquals(listOf("claude-sonnet-4-6", "deepseek-chat"), models.map { it.id })
        // 有第二列就用它当显示名；只有一列时回落成 id 本身。
        assertEquals("claude-sonnet-4-6", models.first().name)
        assertEquals("DeepSeek Chat", models.last().name)
    }

    @Test
    fun duplicateIdsAreDedupedKeepingFirstLabel() {
        val models = DriverModelCatalog.parseTwoColumnList(
            "m-1\tFirst\nm-1\tSecond\n",
        )
        assertEquals(1, models.size)
        assertEquals("First", models.single().name)
    }

    // ---------- 真实输出形态（两家实测样本，取自本机一次真跑，只保留结构） ----------

    @Test
    fun parsesOpencodeSingleColumnProviderModelList() {
        // `opencode models` 实测：单列 `provider/model`，无 TAB，无表头，137 行。
        val models = DriverModelCatalog.parseTwoColumnList(
            """
            openai/chat-latest
            openai/gpt-4.1
            opencode-gpt-5.2-codex
            opencode-go/qwen3.8-max
            opencode/deepseek-v4.1-flash-free
            ollama/llama4:scout
            """.trimIndent() + "\n",
        )
        assertEquals(
            listOf(
                "openai/chat-latest",
                "openai/gpt-4.1",
                "opencode-gpt-5.2-codex",
                "opencode-go/qwen3.8-max",
                "opencode/deepseek-v4.1-flash-free",
                "ollama/llama4:scout",
            ),
            models.map { it.id },
        )
        // 单列没有可读名 ⇒ 显示名回落成 id（不画空标签）。
        assertTrue(models.all { it.name == it.id })
    }

    @Test
    fun parsesCodexModelsJsonWithDisplayNames() {
        // `codex debug models` 实测：`{"models":[{"slug","display_name",…}]}`，8 条。
        // 其余字段（推理档位 / 提示词模板）不读 —— 只取派单要用的 slug 与显示名。
        val models = DriverModelCatalog.parseCodexModelsJson(
            """
            {"models":[
              {"slug":"gpt-5.6-sol","display_name":"GPT-5.6-Sol","description":"…"},
              {"slug":"gpt-5.5","display_name":"GPT-5.5"},
              {"slug":"codex-auto-review","display_name":"Codex Auto Review"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("gpt-5.6-sol", "gpt-5.5", "codex-auto-review"), models.map { it.id })
        assertEquals("GPT-5.6-Sol", models.first().name)
    }

    @Test
    fun codexJsonWithoutDisplayNameFallsBackToSlug() {
        val models = DriverModelCatalog.parseCodexModelsJson(
            """{"models":[{"slug":"gpt-5.5"}]}""",
        )
        assertEquals("gpt-5.5", models.single().name)
    }

    @Test
    fun malformedCodexJsonYieldsEmptyList() {
        // 解析失败返回空表 ⇒ probe 报 UNKNOWN，不报「这家没有模型」。
        assertTrue(DriverModelCatalog.parseCodexModelsJson("not json").isEmpty())
        assertTrue(DriverModelCatalog.parseCodexModelsJson("""{"nope":1}""").isEmpty())
        assertTrue(DriverModelCatalog.parseCodexModelsJson("""{"models":"x"}""").isEmpty())
    }

    // ---------- dsh：配置型探测（读当前值 + provider 目录候选） ----------

    @Test
    fun parsesDshDefaultModelFromDumpConfig() {
        // `dsh --profile headless --dump-config` 实测片段（只保留判据相关行）。
        val text = """
            - id: agent
              name: '@deepseek-ai/dsh-agent'
            # == @deepseek-ai/dsh-base, patched by /Users/x/.dsh/profiles/headless/cordis.patch.yml
            - id: agent-default-model
              name: '@deepseek-ai/dsh-agent-default-model'
              config:
                provider: dsh-opencode-go
                model: deepseek-v4.1-flash
                reasoningEffort: high
            # == @deepseek-ai/dsh-base
            - id: jobs
        """.trimIndent()
        val parsed = DriverModelCatalog.parseDshDefaultModel(text)
        assertEquals("dsh-opencode-go", parsed?.provider)
        assertEquals("deepseek-v4.1-flash", parsed?.model)
    }

    @Test
    fun dshConfigWithoutBlockYieldsNull() {
        // profile 结构变了 / 没有这一段 ⇒ null，调用方报 UNKNOWN，不猜。
        assertNull(DriverModelCatalog.parseDshDefaultModel("- id: jobs\n  name: x"))
        assertNull(DriverModelCatalog.parseDshDefaultModel(""))
        // 有块但缺 model ⇒ 也算读不到（不拿 provider 冒充当前模型）。
        val onlyProvider = """
            - id: agent-default-model
              config:
                provider: dsh-opencode-go
        """.trimIndent()
        assertNull(DriverModelCatalog.parseDshDefaultModel(onlyProvider))
    }

    @Test
    fun dshCandidatesComeFromProviderCatalogPrefix() = runBlocking {
        // dsh 的候选来自 opencode 目录里 `opencode-go/` 前缀的项，去掉前缀即为 dsh 认的裸 id。
        val c = catalog(
            specs = mapOf(
                DriverKeys.AGY to agySpec,
                "opencode" to ModelProbeSpec(listOf("models"), DriverModelCatalog::parseTwoColumnList),
            ),
            configSpecs = mapOf(
                "dsh" to ConfigProbeSpec(
                    args = listOf("--profile", "headless", "--dump-config"),
                    parse = DriverModelCatalog::parseDshDefaultModel,
                    providerToCatalogPrefix = mapOf("dsh-opencode-go" to "opencode-go/"),
                ),
            ),
            resolver = { "/fake/cli" },
            runner = { argv ->
                when {
                    argv.any { it == "models" } -> CliAuthProbes.Output(
                        0,
                        "opencode-go/deepseek-v4.1-flash\topencode-go model\n" +
                            "openai/gpt-5\topenai model\n",
                    )
                    else -> CliAuthProbes.Output(
                        0,
                        "- id: agent-default-model\n  config:\n" +
                            "    provider: dsh-opencode-go\n" +
                            "    model: deepseek-v4.1-flash\n",
                    )
                }
            },
        )
        val entry = c.snapshot(listOf("dsh")).getValue("dsh")
        assertEquals(ModelCatalogState.KNOWN, entry.state)
        // 只取该 provider 前缀下的项，并去掉前缀。
        assertEquals(listOf("deepseek-v4.1-flash"), entry.models.map { it.id })
    }

    @Test
    fun dshFallsBackToCurrentModelWhenProviderCatalogUnavailable() = runBlocking {
        // 源目录读不到 ⇒ 仍然 KNOWN，但只有当前那一个 —— 「能看见当前用什么」本身有效，
        // 不该因为列不出候选就整个降级成「列不出来」。
        val c = catalog(
            specs = mapOf(DriverKeys.AGY to agySpec),
            configSpecs = mapOf(
                "dsh" to ConfigProbeSpec(
                    args = listOf("--profile", "headless", "--dump-config"),
                    parse = DriverModelCatalog::parseDshDefaultModel,
                    providerToCatalogPrefix = mapOf("dsh-opencode-go" to "opencode-go/"),
                ),
            ),
            resolver = { "/fake/cli" },
            runner = {
                CliAuthProbes.Output(
                    0,
                    "- id: agent-default-model\n  config:\n" +
                        "    provider: dsh-opencode-go\n" +
                        "    model: deepseek-v4.1-flash\n",
                )
            },
        )
        val entry = c.snapshot(listOf("dsh")).getValue("dsh")
        assertEquals(ModelCatalogState.KNOWN, entry.state)
        assertEquals(listOf("deepseek-v4.1-flash"), entry.models.map { it.id })
    }

    // ---------- 三态 ----------

    @Test
    fun exitZeroWithModelsIsKnown() = runBlocking {
        val entry = catalog(runner = { CliAuthProbes.Output(0, "m-1\tOne\n") })
            .snapshot(listOf(DriverKeys.AGY))
            .getValue(DriverKeys.AGY)
        assertEquals(ModelCatalogState.KNOWN, entry.state)
        assertEquals(listOf("m-1"), entry.models.map { it.id })
    }

    @Test
    fun nonzeroExitStaysUnknown() = runBlocking {
        val entry = catalog(runner = { CliAuthProbes.Output(1, "") })
            .snapshot(listOf(DriverKeys.AGY))
            .getValue(DriverKeys.AGY)
        assertEquals(ModelCatalogState.UNKNOWN, entry.state)
        assertTrue(entry.models.isEmpty())
    }

    @Test
    fun exitZeroButNothingParsedStaysUnknown() = runBlocking {
        // 跑通了但解析不出模型 ⇒ UNKNOWN，**不是**「这家没有模型」。
        val entry = catalog(runner = { CliAuthProbes.Output(0, "not a model list at all\n") })
            .snapshot(listOf(DriverKeys.AGY))
            .getValue(DriverKeys.AGY)
        assertEquals(ModelCatalogState.UNKNOWN, entry.state)
        assertTrue(entry.note.isNotBlank())
    }

    @Test
    fun driverWithoutVerifiedSpecStaysUnknown() = runBlocking {
        var calls = 0
        val entry = catalog(runner = { calls++; CliAuthProbes.Output(0, "m-1\tOne\n") })
            .snapshot(listOf("dsh"))
            .getValue("dsh")
        assertEquals(ModelCatalogState.UNKNOWN, entry.state)
        assertEquals(DriverModelCatalog.NO_SPEC_NOTE, entry.note)
        assertEquals(0, calls, "没有规格就不该起子进程")
    }

    @Test
    fun missingCliStaysUnknownWithoutSpawning() = runBlocking {
        var calls = 0
        val entry = catalog(
            runner = { calls++; CliAuthProbes.Output(0, "m-1\tOne\n") },
            resolver = { null },
        ).snapshot(listOf(DriverKeys.AGY)).getValue(DriverKeys.AGY)
        assertEquals(ModelCatalogState.UNKNOWN, entry.state)
        assertEquals(DriverModelCatalog.NO_CLI_NOTE, entry.note)
        assertEquals(0, calls)
    }

    @Test
    fun throwingRunnerDegradesToUnknown() = runBlocking {
        val entry = catalog(runner = { error("spawn failed") })
            .snapshot(listOf(DriverKeys.AGY))
            .getValue(DriverKeys.AGY)
        assertEquals(ModelCatalogState.UNKNOWN, entry.state)
    }

    // ---------- 缓存 ----------

    @Test
    fun freshCacheSkipsProbeAndExpiredCacheReprobes() = runBlocking {
        var calls = 0
        var time = 0L
        val c = catalog(
            runner = { calls++; CliAuthProbes.Output(0, "m-1\tOne\n") },
            clock = { time },
        )
        c.snapshot(listOf(DriverKeys.AGY))
        assertEquals(1, calls)
        time = 500L
        c.snapshot(listOf(DriverKeys.AGY))
        assertEquals(1, calls, "TTL 内必须命中缓存")
        time = 1_500L
        c.snapshot(listOf(DriverKeys.AGY))
        assertEquals(2, calls, "TTL 过期必须重探")
    }

    @Test
    fun invalidateForcesReprobe() = runBlocking {
        var calls = 0
        val c = catalog(runner = { calls++; CliAuthProbes.Output(0, "m-1\tOne\n") })
        c.snapshot(listOf(DriverKeys.AGY))
        c.invalidate()
        assertNull(c.peek(DriverKeys.AGY))
        c.snapshot(listOf(DriverKeys.AGY))
        assertEquals(2, calls)
    }

    @Test
    fun knownModelsOnlyContainsKnownNonEmptyEntries() = runBlocking {
        var time = 0L
        val c = catalog(
            runner = { CliAuthProbes.Output(0, "m-1\tOne\n") },
            specs = mapOf(
                DriverKeys.AGY to agySpec,
                "opencode" to ModelProbeSpec(listOf("models"), DriverModelCatalog::parseTwoColumnList),
            ),
            clock = { time },
        )
        // agy 已知；opencode 的 spec 也给了 —— 用同一 runner 但输出按命令区分太麻烦，
        // 这里只验证表里只有 KNOWN 且非空的条目。
        c.snapshot(listOf(DriverKeys.AGY))
        val known = c.knownModels()
        assertEquals(setOf("m-1"), known[DriverKeys.AGY])
        assertNull(known["dsh"], "没探过的驱动不许出现在已知表里")
    }
}
