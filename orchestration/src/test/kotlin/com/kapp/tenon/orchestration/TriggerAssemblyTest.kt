// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.plugins.PluginManifest
import com.kapp.tenon.agent.plugins.PluginTriggerDef
import com.kapp.tenon.agent.plugins.TriggerDispatch
import com.kapp.tenon.agent.plugins.TriggerFilter
import com.kapp.tenon.agent.plugins.TriggerSpec
import com.kapp.tenon.agent.plugins.TriggerTypes
import com.kapp.tenon.agent.schedule.MissedFirePolicies
import com.kapp.tenon.core.protocol.TeamMemberInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 注册通过的清单 → 调度任务/事件路由的映射（提案 §9 阶段三）。
 *
 * 判据：taskId 命名、裸 groupId 剥取、filter 原样透传、策略归一、agentKey 取
 * leader——形状翻译只有一份实现，DesktopExtension 只消费它的输出。
 */
class TriggerAssemblyTest {

    private fun manifest(
        pluginId: String = "mail-assistant",
        triggers: List<PluginTriggerDef>,
    ) = PluginManifest(
        pluginId = pluginId,
        name = "Mail Assistant",
        type = "single",
        leader = TeamMemberInfo(agentId = "mail-bot", name = "Mail Bot", role = "lead"),
        triggers = triggers,
    )

    @Test
    fun cronTriggerMapsToCronTaskDef() {
        val set = TriggerAssembly.fromManifests(
            listOf(
                manifest(
                    triggers = listOf(
                        PluginTriggerDef(
                            id = "morning",
                            name = "晨报",
                            type = TriggerTypes.CRON,
                            spec = TriggerSpec(
                                schedule = "0 8 * * 1-5",
                                timezone = "Asia/Shanghai",
                                jitterSeconds = 30,
                                missedFirePolicy = "SKIP",
                            ),
                            dispatch = TriggerDispatch(prompt = "汇总 {{subject}}", targetConvId = "group:g1"),
                        ),
                    ),
                ),
            ),
        )
        val task = set.cronTasks.single()
        assertEquals("mail-assistant/morning", task.taskId)
        assertEquals("0 8 * * 1-5", task.schedule)
        assertEquals("Asia/Shanghai", task.timezoneId)
        assertEquals(30, task.jitterSeconds)
        assertEquals("SKIP", task.missedFirePolicy, "清单声明原样透传（结构校验已保证取值域）")
        assertEquals("g1", task.targetConvId, "群会话键剥成裸 groupId 供卡片广播")
        assertEquals("mail-bot", task.agentKey, "执行体 = 插件 leader")
        assertTrue(set.eventRoutes.isEmpty())
    }

    @Test
    fun webhookTriggerMapsToEventRouteWithFilter() {
        val set = TriggerAssembly.fromManifests(
            listOf(
                manifest(
                    triggers = listOf(
                        PluginTriggerDef(
                            id = "urgent",
                            name = "紧急邮件",
                            type = TriggerTypes.WEBHOOK,
                            spec = TriggerSpec(sourceId = "mail", secretHeader = "x-webhook-signature"),
                            dispatch = TriggerDispatch(prompt = "主题 {{subject}}", targetConvId = "group:ops"),
                            filter = TriggerFilter(types = listOf("mail.inbound"), keywords = listOf("urgent")),
                        ),
                    ),
                ),
            ),
        )
        val route = set.eventRoutes.single()
        assertEquals("mail", route.sourceId)
        assertEquals("mail-bot", route.agentKey)
        assertEquals("ops", route.targetGroupId)
        assertEquals(listOf("mail.inbound"), route.filterTypes)
        assertEquals(listOf("urgent"), route.filterKeywords)
        assertTrue(set.cronTasks.isEmpty())
    }

    @Test
    fun missedFirePolicyDefaultsToRunOnce() {
        val set = TriggerAssembly.fromManifests(
            listOf(
                manifest(
                    triggers = listOf(
                        PluginTriggerDef(
                            id = "t", name = "n", type = TriggerTypes.CRON,
                            spec = TriggerSpec(schedule = "0 8 * * *"),
                            dispatch = TriggerDispatch(prompt = "p", targetConvId = "group:g1"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MissedFirePolicies.RUN_ONCE, set.cronTasks.single().missedFirePolicy, "缺省 = RUN_ONCE（提案 §5.2）")
        assertEquals(0, set.cronTasks.single().jitterSeconds, "缺省 = 无扰动")
    }

    @Test
    fun triggerWithoutGroupTargetKeepsNullTarget() {
        val set = TriggerAssembly.fromManifests(
            listOf(
                manifest(
                    triggers = listOf(
                        PluginTriggerDef(
                            id = "t", name = "n", type = TriggerTypes.CRON,
                            spec = TriggerSpec(schedule = "0 8 * * *"),
                            // 注册校验只放行群会话；这里钉的是「非群形态剥不出裸 id 时不误投」。
                            dispatch = TriggerDispatch(prompt = "p", targetConvId = "peer-xxx"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(null, set.cronTasks.single().targetConvId, "剥不出裸 groupId 就不回投（注册校验本应拒掉它）")
    }

    @Test
    fun pluginWithoutTriggersYieldsEmptyTaskSet() {
        val set = TriggerAssembly.fromManifests(listOf(manifest(triggers = emptyList())))
        assertTrue(set.cronTasks.isEmpty() && set.eventRoutes.isEmpty())
    }

    // ---- 目标会话存在性判定（§5.2 宿主级事实）----

    @Test
    fun targetGroupKnownOnlyAcceptsConfiguredGroups() {
        val configured = listOf("g1", "ops")
        assertTrue(TriggerAssembly.targetGroupKnown("group:g1", configured))
        assertFalse(TriggerAssembly.targetGroupKnown("group:ghost", configured), "未配置的群不存在")
        assertFalse(TriggerAssembly.targetGroupKnown("peer-phone", configured), "1:1 会话不是回投目标（结果卡走群通道）")
        assertFalse(TriggerAssembly.targetGroupKnown("g1", configured), "裸 groupId 不是会话键形态")
    }
}
