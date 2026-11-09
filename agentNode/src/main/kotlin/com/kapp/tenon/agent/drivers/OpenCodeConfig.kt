// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File

/**
 * 给沙箱里的 opencode 递一份**能用的**配置。
 *
 * ## 为什么必须由宿主递
 *
 * opencode 的 provider / model 全在配置文件里（`~/.config/opencode/opencode.json`，
 * 或 `$OPENCODE_CONFIG` 指向的那份）。而沙箱会把 `HOME` 换成隔离目录
 * （[WorktreeSandboxExecutor] 的 `prepareSandboxHome`）—— 机主那份配置在沙箱里
 * **根本不可见**，于是 opencode 起来就是「一个 provider 都没有」。
 *
 * ## 为什么不能直接抄机主那份
 *
 * 实测：机主的配置把 provider 指向
 * `http://127.0.0.1:48760/v1`（一个本地网关），那个端口当时没人监听 ⇒ 每次调用
 * `provider.transport ECONNREFUSED`。抄一份死配置进沙箱，只会把「环境不通」装成
 * 「agent 不干活」。
 *
 * ## 生成什么
 *
 * 只写**指针**，不写密文：`apiKey` 用 opencode 自己的 `{env:OPENCODE_API_KEY}`
 * 占位（值由 [CliProviderEnv] 的白名单在子进程里给）。文件里因此没有任何密钥，
 * 落在临时目录也不构成泄漏面。
 *
 * `x-opencode-session` 这个头是实测必需的：zen 网关缺它直接 400
 * `{"type":"MissingSessionID"}` —— codex 的 `config.toml` 里也有同一只头
 * （`http_headers`），这里与它对齐。
 */
object OpenCodeConfig {

    /** opencode 认这只变量作为配置文件路径。 */
    const val ENV_CONFIG = "OPENCODE_CONFIG"

    /**
     * 用哪只模型（写 `deepseek-v4.1-flash`，即 `provider/model` 的**后半截**也照收）。
     *
     * 必须有：生成的配置里没有 `model` 时 opencode 会停在「选一个模型」的交互上，
     * headless 下等于卡住。刻意不给默认值 —— 猜一个本机不通的模型比报错更难查。
     */
    const val ENV_MODEL = "OPENCODE_MODEL"

    /**
     * 自定义 provider 的 id。
     *
     * **为什么不用内置的 `openai`**（实测）：沙箱只覆盖部分环境变量，
     * 宿主那份环境是**继承**下去的 —— 机主的 `OPENCODE_BASE_URL` 因此也在子进程里。
     * opencode 一见到它就用它重建内置 provider，把配置里的 `headers` 丢掉，网关随即回
     * `Model is unavailable`（同一份配置、同一只 key，去掉那只变量就 200；把它设成空串
     * 也不管用，只要是「存在」就会盖）。
     *
     * 换一个自建 provider 就绕开了这条内置路径：内置 provider 的 env 覆盖只作用于它自己。
     */
    private const val PROVIDER_ID = "tenon"

    /** 走 OpenAI 兼容面（`@ai-sdk/openai-compatible` 是 opencode 自带的那只适配器）。 */
    private const val PROVIDER_NPM = "@ai-sdk/openai-compatible"

    /** 网关侧要求的会话头（实测缺了 400）。 */
    private const val SESSION_HEADER = "x-opencode-session"
    private val SESSION_HEADER_VALUE = DriverKeys.cliDriverId("opencode")

    /**
     * 返回该喂给 opencode 的配置路径；null = 什么都不用喂。
     *
     * - 机主已显式给了 `OPENCODE_CONFIG` 且文件在 ⇒ **原样尊重**，不覆盖；
     * - 没给 `OPENCODE_BASE_URL` ⇒ null（让 opencode 用它自己的登录态，不硬造配置）；
     * - 其余情况写一份指针配置（幂等：内容不变就不重写），返回它的绝对路径。
     */
    fun ensure(env: Map<String, String> = System.getenv(), dir: File? = null): String? {
        env[ENV_CONFIG]?.takeIf { it.isNotBlank() }?.let { explicit ->
            return explicit.takeIf { File(it).isFile }
        }
        val baseUrl = env["OPENCODE_BASE_URL"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val model = env[ENV_MODEL]?.trim()?.takeIf { it.isNotEmpty() } ?: probeHostModel(env)
        val text = render(baseUrl = baseUrl, model = model)

        val target = File(dir ?: File(System.getProperty("java.io.tmpdir"), "tenon-opencode"), "opencode.json")
        return runCatching {
            target.parentFile?.mkdirs()
            if (!target.isFile || target.readText() != text) target.writeText(text)
            target.absolutePath
        }.getOrNull()
    }

    /** 配置文本（纯函数，测试直接钉它 —— 尤其是「不许出现密钥值」）。 */
    fun render(baseUrl: String, model: String?): String = buildString {
        // 允许写成 `provider/model`，只取后半截：provider 固定是我们自建的那只。
        val modelId = model?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        appendLine("{")
        appendLine("  \"\$schema\": \"https://opencode.ai/config.json\",")
        if (modelId != null) appendLine("  \"model\": " + quote(PROVIDER_ID + "/" + modelId) + ",")
        appendLine("  \"provider\": {")
        appendLine("    " + quote(PROVIDER_ID) + ": {")
        appendLine("      \"npm\": " + quote(PROVIDER_NPM) + ",")
        appendLine("      \"name\": \"Tidings gateway\",")
        appendLine("      \"options\": {")
        // 占位而不是值：密钥只走子进程环境，本文件零密文。
        appendLine("        \"apiKey\": \"{env:OPENCODE_API_KEY}\",")
        appendLine("        \"baseURL\": " + quote(baseUrl) + ",")
        appendLine("        \"headers\": { \"" + SESSION_HEADER + "\": \"" + SESSION_HEADER_VALUE + "\" }")
        appendLine("      },")
        // 自建 provider 必须显式声明模型清单，否则 opencode 认不出 `tidings/<id>`。
        appendLine(
            "      \"models\": { " + quote(modelId ?: "placeholder") + ": { \"name\": " +
                quote(modelId ?: "placeholder") + " } }",
        )
        appendLine("    }")
        appendLine("  }")
        append("}")
    }

    private fun quote(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * 宿主已有配置里的模型探测：当没有显式设置 [ENV_MODEL] 时，优先复用宿主机
     * `~/.config/opencode/opencode.json` 中已配通的模型（如 `openai/mimo-v2.6-flash`），
     * 避免生成缺少 `model` 的空指针配置导致 opencode 退化为默认免费模型报错。
     */
    internal fun probeHostModel(env: Map<String, String> = System.getenv()): String? {
        val home = env["HOME"]?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home") ?: return null
        val configFile = File(home, ".config/opencode/opencode.json")
        if (!configFile.isFile) return null
        return runCatching {
            val text = configFile.readText()
            val match = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(text)
            match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
