// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.plugins

import kotlinx.serialization.Serializable

/**
 * 插件清单的 `triggers[]` 增量定义（触发式编排提案 §5）。
 *
 * 结构形状（§5.2 字段约束）：
 * - `id`：复用 [PluginRegistration.PLUGIN_ID_PATTERN]，同一清单内唯一；
 * - `type`：`cron` / `webhook` 两态（[TriggerTypes] 唯一来源）；`fileWatch` 是
 *   候选形态，解析期**显式拒绝**并给可操作指引——候选语义绝不静默吞；
 * - cron 触发必填 `spec.schedule`；webhook 触发必填 `spec.sourceId`；
 * - `dispatch.prompt` 必填，`{{...}}` 占位符只许引用 StandardSourceEvent 的
 *   6 字段（[TriggerPromptsAllowedKeys]）；
 * - `dispatch.targetConvId` 必填（提案 §5.2），合法性（本机存在的群会话）
 *   由宿主在注册期经 [ControlPlane.triggerTargetConvKnown] 校验——fail-closed：
 *   校验器未接线时含 triggers 的清单拒绝注册。
 *
 * 严格解析纪律：本组类型与 [PluginManifest] 走同一个严格解码器——triggers 内
 * 的未知键一样抛错，不存在「清单主键严格、子键静默」的半吊子状态。
 */
@Serializable
data class PluginTriggerDef(
    val id: String,
    val name: String,
    val type: String,
    val spec: TriggerSpec? = null,
    val dispatch: TriggerDispatch,
    val filter: TriggerFilter? = null,
)

/** cron / webhook 两态的 spec 载荷（字段按 type 二选一必填，见 [PluginRegistration.validate]）。 */
@Serializable
data class TriggerSpec(
    // cron
    val schedule: String? = null,
    val timezone: String? = null,
    val jitterSeconds: Int? = null,
    val missedFirePolicy: String? = null,
    // webhook
    val sourceId: String? = null,
    val secretHeader: String? = null,
)

@Serializable
data class TriggerDispatch(
    val prompt: String,
    /** 结果卡回投的会话（提案 §5.2 必填）；宿主只放行本机已存在的**群**会话。 */
    val targetConvId: String,
)

/** §7.2 filter 规则链（纯代码前置过滤）：type 前缀 + subject/summary 关键词，均为白名单语义。 */
@Serializable
data class TriggerFilter(
    val types: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
)

/** trigger type 取值域唯一来源（AGENTS §3 #7：键 + 取值域只能有一份定义）。 */
object TriggerTypes {
    const val CRON = "cron"
    const val WEBHOOK = "webhook"
    val VALUES: Set<String> = setOf(CRON, WEBHOOK)
}

/** dispatch.prompt 占位符的合法取值域 = StandardSourceEvent 的 6 字段。 */
object TriggerPromptKeys {
    val ALLOWED: Set<String> = setOf("id", "type", "subject", "summary", "detail", "link")
    val PLACEHOLDER = Regex("\\{\\{\\s*([a-zA-Z]+)\\s*\\}\\}")
}

/** spec.secretHeader 锁定为现有验签实现识别的三个签名头（提案 §5.2）。 */
object TriggerSecretHeaders {
    val ALLOWED: Set<String> = setOf("x-hub-signature-256", "x-signature-256", "x-webhook-signature")
}
