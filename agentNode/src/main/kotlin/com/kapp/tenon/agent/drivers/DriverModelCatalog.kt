// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 各 CLI 底座的**模型目录**只读探测。
 *
 * ## 为什么要它
 *
 * 编制里的「模型」一格此前只能手填：写错不报错，跑到 CLI 才失败（更糟的是静默跑默认
 * 模型）。界面上的推荐 chip 还是跨厂商混用的固定值。本对象回答「这家驱动当下真的列出
 * 哪些模型」，供 UI 画选项、供保存期做合法性判据。
 *
 * ## 判据：只报确定读到的
 *
 * 与 [CliAuthProbes] 同口径：**退出码 0 且解析出至少一条**才算
 * [ModelCatalogState.KNOWN]。跑通了却一条都没解析出来一律 [ModelCatalogState.UNKNOWN]
 * —— 把解析失败报成「这家没有模型」，会让保存校验拒掉一切合法模型（空集假绿的镜像形态）。
 *
 * ## 规格表只收实测过的
 *
 * [VERIFIED_SPECS] 只登记**本机实测过输出形态**的子命令；没登记的驱动返回 UNKNOWN，
 * 不去猜它有没有 models 子命令。猜一个不存在的子命令的代价很具体：每次打开编制多等一个
 * 超时，而结论永远错。新增一家的步骤：真机跑一次、按真实输出写解析、补进表与测试。
 *
 * ## 缓存
 *
 * 结果按驱动缓存 [ttlMs]；[invalidate] 清空。探测在 IO 线程跑，失败不抛。
 */
class DriverModelCatalog(
    private val specs: Map<String, ModelProbeSpec> = VERIFIED_SPECS,
    private val configSpecs: Map<String, ConfigProbeSpec> = VERIFIED_CONFIG_SPECS,
    private val resolver: (String) -> String? = { resolveProbeCli(it) },
    private val runner: suspend (List<String>) -> CliAuthProbes.Output =
        CliAuthProbes.jvmRunner(PROBE_TIMEOUT_MS),
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private data class Cached(val at: Long, val entry: ModelCatalogEntry)

    private val cache = HashMap<String, Cached>()

    /** 只读缓存（不探测）：null = 这家还没探过。答 UNKNOWN 也是有效结论。 */
    fun peek(driverKey: String, at: Long = now()): ModelCatalogEntry? = synchronized(cache) {
        val hit = cache[driverKey] ?: return null
        if (at - hit.at !in 0 until ttlMs) return null
        hit.entry
    }

    /**
     * 缓存里**确定已知**的模型 id 表：`driverKey → ids`。
     *
     * UNKNOWN 的驱动不进表（缺键 = 不知道）。保存校验据此区分「已知但没有」与
     * 「没探到」—— 前者拒、后者放行。
     */
    fun knownModels(): Map<String, Set<String>> = synchronized(cache) {
        cache.values.mapNotNull { cached ->
            cached.entry.takeIf {
                it.state == ModelCatalogState.KNOWN && it.models.isNotEmpty()
            }
        }.associate { entry ->
            entry.driverKey to entry.models.map { it.id }.toSet()
        }
    }

    fun invalidate(driverKey: String? = null) = synchronized(cache) {
        if (driverKey == null) cache.clear() else cache.remove(driverKey)
        Unit
    }

    /**
     * 逐驱动取目录（命中缓存不重跑）。
     *
     * **顺序执行**：本机通常只有一两家有规格，并发收益抵不上「同一时刻起多个子进程」的
     * 复杂度与真机噪声。缺失（本机没装）由 [resolver] 判 UNKNOWN，不去白起进程。
     */
    suspend fun snapshot(driverKeys: Collection<String>): Map<String, ModelCatalogEntry> {
        val out = LinkedHashMap<String, ModelCatalogEntry>()
        for (raw in driverKeys) {
            val key = DriverKeys.canonicalKey(raw)?.let(DriverKeys::stripCliSuffix) ?: continue
            if (key in out) continue
            out[key] = peek(key) ?: probe(key).also { entry ->
                synchronized(cache) { cache[key] = Cached(now(), entry) }
            }
        }
        return out
    }

    private suspend fun probe(key: String): ModelCatalogEntry {
        configSpecs[key]?.let { config ->
            return probeConfigured(key, config)
        }
        val spec = specs[key]
            ?: return ModelCatalogEntry(
                key, ModelCatalogState.UNKNOWN, note = NO_SPEC_NOTE,
            )
        val cli = resolver(key)
            ?: return ModelCatalogEntry(
                key, ModelCatalogState.UNKNOWN, note = NO_CLI_NOTE,
            )
        val argv = listOf(cli) + spec.args
        val output = runCatching {
            withContext(Dispatchers.IO) { runner(argv) }
        }.getOrElse {
            // 起不了进程（命令缺失 / 沙箱不许 spawn）≠ 没有模型：只报 UNKNOWN。
            return ModelCatalogEntry(key, ModelCatalogState.UNKNOWN, note = "probe threw")
        }
        if (output.exitCode != 0) {
            return ModelCatalogEntry(
                key, ModelCatalogState.UNKNOWN, note = "probe exit=" + output.exitCode,
            )
        }
        val models = runCatching { spec.parse(output.text) }.getOrDefault(emptyList())
        return if (models.isEmpty()) {
            ModelCatalogEntry(
                key, ModelCatalogState.UNKNOWN, note = "probe exit=0 but no model parsed",
            )
        } else {
            ModelCatalogEntry(key, ModelCatalogState.KNOWN, models)
        }
    }

    /**
     * 配置型探测（dsh）：读当前值，再按 provider 从**已有目录**里取候选。
     *
     * 与清单型同样的三态口径：读不到当前值 ⇒ UNKNOWN；读到了但推不出候选 ⇒ 仍然 KNOWN
     * 但只有当前那一个（「能看见当前用什么」本身就是有效信息，不该因为列不出候选而丢掉）。
     */
    private suspend fun probeConfigured(key: String, spec: ConfigProbeSpec): ModelCatalogEntry {
        val cli = resolver(key)
            ?: return ModelCatalogEntry(key, ModelCatalogState.UNKNOWN, note = NO_CLI_NOTE)
        val output = runCatching {
            withContext(Dispatchers.IO) { runner(listOf(cli) + spec.args) }
        }.getOrElse {
            return ModelCatalogEntry(key, ModelCatalogState.UNKNOWN, note = "probe threw")
        }
        if (output.exitCode != 0) {
            return ModelCatalogEntry(
                key, ModelCatalogState.UNKNOWN, note = "probe exit=" + output.exitCode,
            )
        }
        val current = runCatching { spec.parse(output.text) }.getOrNull()
            ?: return ModelCatalogEntry(
                key, ModelCatalogState.UNKNOWN, note = "current model not parsed",
            )
        // 候选来自「该 provider 的模型目录」：查表拿到源 CLI 与前缀，复用那份目录
        // （同一次 snapshot 里若已探过就命中缓存，不重复起进程）。
        val prefix = spec.providerToCatalogPrefix[current.provider]
        val sourceKey = PROVIDER_CATALOG_SOURCE[current.provider]
        val candidates = if (prefix != null && sourceKey != null) {
            val sourceEntry = peek(sourceKey) ?: probe(sourceKey).also { entry ->
                synchronized(cache) { cache[sourceKey] = Cached(now(), entry) }
            }
            if (sourceEntry.state == ModelCatalogState.KNOWN) {
                sourceEntry.models
                    .filter { it.id.startsWith(prefix) }
                    .map { ModelCatalogModel(it.id.removePrefix(prefix), it.name) }
            } else {
                null
            }
        } else {
            null
        }
        val models = if (!candidates.isNullOrEmpty()) {
            candidates
        } else {
            listOf(ModelCatalogModel(current.model, current.model))
        }
        return ModelCatalogEntry(key, ModelCatalogState.KNOWN, models, note = "current=" + current.model)
    }

    companion object {
        /** 目录变化很慢（账号/套餐级），10 分钟足够；换账号/换网重开编制即可重探。 */
        const val DEFAULT_TTL_MS: Long = 10L * 60L * 1000L

        /** 单次探测上限：列表命令是最轻的只读子命令，20s 足够慢机器冷启动。 */
        const val PROBE_TIMEOUT_MS: Long = 20_000L

        /**
         * dsh 的 provider → 哪家 CLI 的模型目录能列出它的候选。
         *
         * 实测：`provider: dsh-opencode-go` 的全部候选就在 `opencode models` 的
         * `opencode-go/` 前缀下。这条映射是**实测事实**，不是命名巧合的推测 ——
         * 换 provider 时必须重新实测，别按名字猜。
         */
        val PROVIDER_CATALOG_SOURCE: Map<String, String> = mapOf(
            "dsh-opencode-go" to "opencode",
        )

        const val NO_SPEC_NOTE: String = "no verified model-list probe"
        const val NO_CLI_NOTE: String = "cli not found"

        private fun resolveProbeCli(key: String): String? = when (key) {
            DriverKeys.AGY -> CliDiscovery.resolveAgyCli()
            "codex" -> CliDiscovery.resolveCodexCli()
            "opencode" -> CliDiscovery.resolveOpencodeCli()
            "dsh" -> CliDiscovery.resolveDshCli()
            else -> null
        }

        /**
         * 纯解析：**TAB 分隔两列**（id + 可读名）或单列 id 的列表。
         *
         * 输出里混着合并进来的 stderr 日志行，所以只认「第一列像模型 id」的行：没有
         * 分隔列时还要求 token 含 `-` / `.` / `/` 之一 —— 否则日志前缀（`I1117` 这类
         * 单 token）会被当成模型。解析不出任何一条时调用方报 UNKNOWN，不报「空目录」。
         *
         * 两家实测形态都走这里：
         * - `agy models` → `gemini-pro-high\tGemini Pro (High)`（TAB 两列）；
         * - `opencode models` → `openai/gpt-5`（单列，`provider/model`，无 TAB）。
         */
        fun parseTwoColumnList(text: String): List<ModelCatalogModel> {
            val out = LinkedHashMap<String, ModelCatalogModel>()
            for (rawLine in stripAnsi(text).lineSequence()) {
                if (out.size >= MAX_MODELS) break
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val head = line.substringBefore('\t').trim()
                val tail = if ('\t' in line) line.substringAfter('\t').trim() else ""
                if (!MODEL_ID.matches(head) || !looksLikeModelId(head)) continue
                if (head !in out) {
                    out[head] = ModelCatalogModel(head, (tail.ifEmpty { head }).take(MAX_LABEL))
                }
            }
            return out.values.toList()
        }

        /**
         * 纯解析：`codex debug models` 的 JSON（`{"models":[{"slug","display_name",…}]}`）。
         *
         * 这是唯一一份**厂家自己给的机读目录**（其余都是人读文本），字段稳定、带显示名。
         * 解析失败 / 形状不对一律返回空表 ⇒ 调用方报 UNKNOWN，不报「没有模型」。
         *
         * 只读 `slug` 与 `display_name`：其余字段（推理档位、提示词模板）不是本层的事。
         */
        fun parseCodexModelsJson(text: String): List<ModelCatalogModel> {
            val root = runCatching {
                Json.parseToJsonElement(text)
            }.getOrNull() as? JsonObject ?: return emptyList()
            val models = root["models"] as? JsonArray ?: return emptyList()
            val out = LinkedHashMap<String, ModelCatalogModel>()
            for (element in models) {
                if (out.size >= MAX_MODELS) break
                val obj = element as? JsonObject ?: continue
                val slug = (obj["slug"] as? JsonPrimitive)
                    ?.contentOrNull?.trim().orEmpty()
                if (slug.isEmpty() || !MODEL_ID.matches(slug)) continue
                val display = (obj["display_name"] as? JsonPrimitive)
                    ?.contentOrNull?.trim().orEmpty()
                if (slug !in out) {
                    out[slug] = ModelCatalogModel(slug, (display.ifEmpty { slug }).take(MAX_LABEL))
                }
            }
            return out.values.toList()
        }

        /**
         * 纯解析：`dsh --profile headless --dump-config` 里 `agent-default-model` 插件块的
         * `provider` / `model` 两行。
         *
         * dsh 的模型**不是命令行旗标**（`--help` 里没有 `--model`），它写在 profile 的插件
         * 配置里；`--patch` 是官方覆盖通道。所以「当前用什么模型」只能从 dump-config 读。
         *
         * 返回 null = 配置里没有这一段（profile 不同 / 结构变了）⇒ 调用方报 UNKNOWN，
         * 不猜。注意：只读**当前值**，候选清单来自该 provider 的模型目录（见 [dshCandidatesOf]）。
         */
        fun parseDshDefaultModel(text: String): DshDefaultModel? {
            var provider: String? = null
            var model: String? = null
            var inBlock = false
            for (rawLine in stripAnsi(text).lineSequence()) {
                val line = rawLine.trimEnd()
                if (line.startsWith("- id:")) {
                    val id = line.substringAfter("- id:").trim()
                    inBlock = id == "agent-default-model"
                    continue
                }
                if (!inBlock) continue
                val t = line.trim()
                if (t.startsWith("provider:")) provider = t.substringAfter("provider:").trim()
                else if (t.startsWith("model:")) {
                    model = t.substringAfter("model:").trim()
                    // provider 在 model 之前（实测顺序），读到 model 就可以收了。
                    if (provider != null) break
                }
            }
            val p = provider?.takeIf { it.isNotEmpty() && MODEL_ID.matches(it) } ?: return null
            val m = model?.takeIf { it.isNotEmpty() && MODEL_ID.matches(it) } ?: return null
            return DshDefaultModel(provider = p, model = m)
        }

        private val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:/@+-]{1,127}")

        private fun looksLikeModelId(id: String): Boolean =
            id.length >= 3 && (id.contains('-') || id.contains('.') || id.contains('/'))

        private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

        private fun stripAnsi(text: String): String = text.replace(ANSI, "")

        private const val MAX_LABEL: Int = 80
        private const val MAX_MODELS: Int = 200
    }
}

/** 一条模型目录探测规格：argv 的尾部（可执行文件由 resolver 给出）+ 输出解析。 */
data class ModelProbeSpec(
    val args: List<String>,
    val parse: (String) -> List<ModelCatalogModel>,
)

/**
 * dsh 的「当前默认模型」—— 从 profile 配置里读出来的事实。
 *
 * [provider] 形如 `dsh-opencode-go`；[model] 是裸 id（如 `deepseek-v4.1-flash`）。
 * 两者必须一起写回（`--patch` 是整块替换，只写 model 会把 provider 弄丢，dsh 启动即失败）。
 */
data class DshDefaultModel(val provider: String, val model: String)

/**
 * 一类**配置型**探测规格：先跑一条命令读当前值，再根据当前值推导候选清单。
 *
 * 与 [ModelProbeSpec] 的区别：后者是「一条命令直接列出全部可选模型」；dsh 不是这种
 * 形态 —— 它只有「当前用什么」，候选来自 provider 的模型目录。
 */
data class ConfigProbeSpec(
    /** argv 的尾部（可执行文件由 resolver 给出）。 */
    val args: List<String>,
    /** 输出 → (provider, 当前模型)；null = 读不到。 */
    val parse: (String) -> DshDefaultModel?,
    /**
     * provider → 该 provider 在**别处**目录里的 id 前缀。
     *
     * 实测：dsh 的 `provider: dsh-opencode-go` 对应 `opencode models` 里的 `opencode-go/`
     * 前缀（34 个）。候选从那份目录里取，去掉前缀即为 dsh 认的裸 id。
     */
    val providerToCatalogPrefix: Map<String, String>,
)

/** 目录三态里只有两档：读到了（KNOWN）/ 没读到（UNKNOWN）。「读不到」不是「没有」。 */
enum class ModelCatalogState { KNOWN, UNKNOWN }

/** 一条可选模型：`id` 是派单时真正传给 CLI 的值，`name` 只用于显示。 */
data class ModelCatalogModel(val id: String, val name: String)

/** 一个驱动的目录结论。`note` 是诊断串（日志/诊断面用），**不是** UI 文案。 */
data class ModelCatalogEntry(
    val driverKey: String,
    val state: ModelCatalogState,
    val models: List<ModelCatalogModel> = emptyList(),
    val note: String = "",
)

/**
 * 已实测过的探测规格（每一条都在本机跑过真实输出、按真实形态写解析）。
 *
 * - `agy models` → TAB 分隔两列（id + 可读名）；
 * - `opencode models` → 单列 `provider/model`，本机实测一次返回百余行；
 * - `codex debug models` → 结构化 JSON，`models[].slug` / `display_name`（本机实测有值）。
 *
 * 其余三家不在此表，理由各自不同、都**不是「猜它没有」**：
 * - `claude`：`--help` 里没有任何列模型的子命令（只有 `auth`），模型名只出现在
 *   `--model` 的说明文案里（`fable` / `opus` / `sonnet` 这类别名），属于**别名不是目录**；
 * - `dsh`：模型写在 profile 的插件配置里，没有列表子命令 —— 但**有** [VERIFIED_CONFIG_SPECS]
 *   这条配置型探测（读 dump-config 拿当前值）；
 * - `codebuddy` / `zcode`：本机未安装，无法取得可信输出。
 *
 * 要加一家：先真机跑一次、按真实输出写解析、补进表与测试 —— 不要按「应该有」写。
 */
/**
 * 配置型探测规格（当前只有 dsh）。
 *
 * dsh 的模型写在 profile 的 `agent-default-model` 插件配置里，**没有 `--model` 旗标**；
 * `--dump-config` 是唯一能读到当前值的通道，`--patch` 是唯一能改的通道（官方覆盖机制）。
 *
 * 候选来自 opencode 的目录：dsh 的 provider `dsh-opencode-go` 对应 `opencode-go/` 前缀
 * （本机实测该前缀下 34 个模型，去掉前缀即为 dsh 认的裸 id）。
 */
internal val VERIFIED_CONFIG_SPECS: Map<String, ConfigProbeSpec> = mapOf(
    "dsh" to ConfigProbeSpec(
        args = listOf("--profile", "headless", "--dump-config"),
        parse = DriverModelCatalog::parseDshDefaultModel,
        providerToCatalogPrefix = mapOf("dsh-opencode-go" to "opencode-go/"),
    ),
)

internal val VERIFIED_SPECS: Map<String, ModelProbeSpec> = mapOf(
    DriverKeys.AGY to ModelProbeSpec(
        args = listOf("models"),
        parse = DriverModelCatalog::parseTwoColumnList,
    ),
    "opencode" to ModelProbeSpec(
        args = listOf("models"),
        parse = DriverModelCatalog::parseTwoColumnList,
    ),
    "codex" to ModelProbeSpec(
        args = listOf("debug", "models"),
        parse = DriverModelCatalog::parseCodexModelsJson,
    ),
)
