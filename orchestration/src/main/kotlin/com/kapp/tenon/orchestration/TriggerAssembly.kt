// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.plugins.PluginManifest
import com.kapp.tenon.agent.plugins.TriggerTypes
import com.kapp.tenon.agent.schedule.MissedFirePolicies
import com.kapp.tenon.core.protocol.ConvId

/**
 * 注册通过的插件清单 → 调度中枢输入的**纯映射**（提案 §9 阶段三）。
 *
 * 判据（§5.2）：cron trigger → [CronTaskDef]（taskId = pluginId/trigger.id）；
 * webhook trigger → [TriggerEventRoute]（sourceId 路由 + filter 规则链原样透传）。
 * 结构合法性已由 PluginRegistration.validate 与 validateTriggerHostFacts 两道门
 * 保证——这里只做形状翻译，不再校验（两道门之外出现第三份判定就是在制造分叉）。
 *
 * targetConvId 是群会话键（`group:<id>`）：注册校验只放行群会话（结果卡走群通道，
 * 见 [TriggerExecutor]），这里负责剥回裸 groupId 供卡片广播使用。
 */
object TriggerAssembly {

    data class TriggerTaskSet(
        val cronTasks: List<CronTaskDef>,
        val eventRoutes: List<TriggerEventRoute>,
    )

    fun fromManifests(manifests: List<PluginManifest>): TriggerTaskSet {
        val cronTasks = mutableListOf<CronTaskDef>()
        val eventRoutes = mutableListOf<TriggerEventRoute>()
        manifests.forEach { manifest ->
            val agentKey = manifest.leader?.agentId ?: return@forEach
            manifest.triggers.forEach { trigger ->
                val targetGroupId = ConvId.groupIdOf(trigger.dispatch.targetConvId)
                when (trigger.type) {
                    TriggerTypes.CRON -> {
                        val spec = trigger.spec ?: return@forEach
                        val schedule = spec.schedule ?: return@forEach
                        cronTasks += CronTaskDef(
                            taskId = manifest.pluginId + "/" + trigger.id,
                            schedule = schedule,
                            timezoneId = spec.timezone?.takeIf { it.isNotBlank() },
                            jitterSeconds = spec.jitterSeconds ?: 0,
                            missedFirePolicy = MissedFirePolicies.normalize(spec.missedFirePolicy),
                            prompt = trigger.dispatch.prompt,
                            targetConvId = targetGroupId,
                            agentKey = agentKey,
                        )
                    }

                    TriggerTypes.WEBHOOK -> {
                        val sourceId = trigger.spec?.sourceId ?: return@forEach
                        eventRoutes += TriggerEventRoute(
                            sourceId = sourceId,
                            agentKey = agentKey,
                            promptTemplate = trigger.dispatch.prompt,
                            targetGroupId = targetGroupId,
                            filterTypes = trigger.filter?.types.orEmpty(),
                            filterKeywords = trigger.filter?.keywords.orEmpty(),
                        )
                    }
                }
            }
        }
        return TriggerTaskSet(cronTasks, eventRoutes)
    }

    /**
     * 回投目标的存在性判定（宿主级事实，ControlPlane.triggerTargetConvKnown 的
     * 实参）：只放行本机 settings 里配置过的**群**会话。
     */
    fun targetGroupKnown(convId: String, configuredGroupIds: Collection<String>): Boolean {
        if (!ConvId.isGroup(convId)) return false
        val raw = ConvId.groupIdOf(convId) ?: return false
        return configuredGroupIds.contains(raw)
    }
}

/**
 * 触发任务对账的指纹：整份清单的 `toString()` 按 pluginId 排序后串起来。
 *
 * **为什么是整份清单而不是手挑字段**：旧实现只取 `pluginId#triggerId:triggerType`，
 * 于是改 schedule / prompt / filter / targetConvId 都不改变指纹，对账被跳过，
 * 新配置永远不生效 —— 症状是「我改了插件清单但触发器没变」，而且没有任何日志。
 * 手挑字段的写法每加一个字段就漏一次；data class 的 `toString()` 覆盖全部声明
 * 属性，加字段自动跟上。
 *
 * **为什么要排序**：指纹必须是**内容**的函数，不是枚举顺序的函数。插件列表来自
 * 一个 map，顺序不该影响「配置有没有变」的判定 —— 否则顺序抖一下就会白跑一次
 * 对账（清空路由表 + 重同步 cron）。
 */
internal fun triggerManifestFingerprint(manifests: List<PluginManifest>): String =
    manifests.sortedBy { it.pluginId }.joinToString("|") { it.toString() }
