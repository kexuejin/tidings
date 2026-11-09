// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.TriggerEventRecord
import java.util.concurrent.ConcurrentHashMap

/**
 * webhook 触发路由的定义注册表：sourceId → 派单参数（agentKey / prompt 模板 /
 * 回投群 / filter 规则链）。阶段三由插件 manifest 的 `triggers` 在注册通过后填充；
 * 阶段二先落结构与消费判据（DesktopExtension 的 handler 经这里解析路由，
 * 未注册的 sourceId 如实失败，绝不猜一个执行体）。
 *
 * filter 规则链（提案 §7.2）：**纯代码前置过滤**——type 前缀 + 关键词子串，
 * 毫秒级 0 Token；未命中的事件在消费侧翻 skipped，绝不唤醒模型。
 */
data class TriggerEventRoute(
    val sourceId: String,
    val agentKey: String,
    /** `{{field}}` 模板；占位符取值域在注册校验时锁定为 StandardSourceEvent 6 字段。 */
    val promptTemplate: String,
    /** 结果卡回投的群 id（裸 groupId，宿主必须是成员）；null = 不回投。 */
    val targetGroupId: String?,
    /** type 前缀白名单（如 `mail.inbound`）；空 = 全放行。 */
    val filterTypes: List<String> = emptyList(),
    /** subject/summary 关键词白名单；空 = 全放行。 */
    val filterKeywords: List<String> = emptyList(),
) {

    /**
     * §7.2 规则链判定：任一规则组为空 = 该组放行；两组都非空时必须**同时**命中
     * （类型限定 + 关键词过滤是「且」的关系——「只要类型对就放」会把垃圾邮件也喂给模型）。
     */
    fun matches(event: TriggerEventRecord): Boolean {
        val typeOk = filterTypes.isEmpty() || filterTypes.any { event.type.startsWith(it, ignoreCase = true) }
        if (!typeOk) return false
        if (filterKeywords.isEmpty()) return true
        val haystack = (event.subject + "\n" + event.summary).lowercase()
        return filterKeywords.any { haystack.contains(it.lowercase()) }
    }
}

class TriggerRegistry {
    private val routes = ConcurrentHashMap<String, TriggerEventRoute>()

    fun register(route: TriggerEventRoute) {
        routes[route.sourceId.trim().lowercase()] = route
    }

    fun resolve(sourceId: String): TriggerEventRoute? =
        routes[sourceId.trim().lowercase()]

    fun all(): List<TriggerEventRoute> = routes.values.toList()
}
