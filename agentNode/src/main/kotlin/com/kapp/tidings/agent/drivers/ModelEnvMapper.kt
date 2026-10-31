// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

/**
 * 一个模型 Provider 的**节点本地**凭证配置（升级轮 T-3a，设计 §5.5）。
 *
 * [apiKey] 只落盘于节点本地（凭证文件 / 加密区），**绝对禁止**进入任何 P2P 报文
 * （group-text / agent-event / agent-channel）—— 守卫测试见
 * `ModelEnvMapperSecurityTest`（报文序列化含 Key 字样即红）。
 */
data class ModelProviderConfig(
    val providerId: String,
    val apiBaseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val reasonerModel: String? = null,
)

/**
 * 把节点本地的模型凭证映射为各 CLI 的专属环境变量（升级轮 T-3a，设计 §5.5-4）。
 *
 * 融合 OpenBot（角色绑模型）与 Paseo（模型即标签）：
 *  - 逻辑层：Agent 角色带推荐模型预设（defaultModel / reasonerModel）；
 *  - 物理层：`model:<provider>` 标签寻路持有凭证的节点，凭证在**节点本地**注入子进程。
 *
 * ## 安全不变量
 *
 * 1. **凭证永不离机**：返回的 Map 只进 `ProcessBuilder.environment()`（子进程环境），
 *    不进任何协议帧、不进日志 —— 调用方不得把它序列化到台账或报文。
 * 2. **映射缺失 ⇒ 拒单**：[buildEnvironment] 对不认识的 targetCli 抛
 *    [IllegalArgumentException] —— 静默用错模型比拒单更糟（D-023 同一条哲学）。
 * 3. **nullable model 不下发**：`reasonerModel == null` 时对应键直接缺席
 *    （与帧纪律 encodeDefaults=false 同一条哲学：不下发空值）。
 */
object ModelEnvMapper {

    /** 子进程环境注入的统一入口。 */
    fun buildEnvironment(config: ModelProviderConfig, targetCli: String): Map<String, String> {
        val base = when (targetCli) {
            "claude" -> mapOf(
                "ANTHROPIC_BASE_URL" to config.apiBaseUrl,
                "ANTHROPIC_API_KEY" to config.apiKey,
                "ANTHROPIC_MODEL" to config.defaultModel,
            )
            "codex" -> mapOf(
                "OPENAI_BASE_URL" to config.apiBaseUrl,
                "OPENAI_API_KEY" to config.apiKey,
                "CODEX_MODEL" to config.defaultModel,
            )
            "opencode" -> mapOf(
                "OPENCODE_API_KEY" to config.apiKey,
                "OPENCODE_MODEL" to config.defaultModel,
            )
            else -> throw IllegalArgumentException(
                "unknown targetCli \"$targetCli\" — refusing to guess env mapping " +
                    "(silently using the wrong model is worse than refusing the task)",
            )
        }
        return if (config.reasonerModel == null) {
            base
        } else {
            base + ("ANTHROPIC_REASONER_MODEL" to config.reasonerModel!!)
        }
    }
}
