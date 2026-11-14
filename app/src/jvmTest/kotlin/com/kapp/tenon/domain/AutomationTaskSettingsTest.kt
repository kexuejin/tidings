// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自动化任务与私人助理设置持久化闭环测试。
 */
class AutomationTaskSettingsTest {

    @Test
    fun testAutomationTaskSerialization() {
        val original = RelaySettings(
            nodeId = "self-node",
            assistantEnabled = true,
            assistantCustomName = "My AI Assistant",
            assistantRoleKey = "architect",
            allowedOperators = listOf("node-alpha", "node-beta"),
            automationTasks = listOf(
                AutomationTask(
                    id = "auto-1",
                    name = "Daily Gatekeeper",
                    cron = "0 9 * * *",
                    workspacePath = "/repo",
                    prompt = "Run testStabilityGate and report",
                    assignedPersona = "tester",
                    autoMerge = true,
                    enabled = true,
                    createdAt = 1000L,
                    updatedAt = 1000L,
                ),
            ),
        )

        val json = SettingsStore.json.encodeToString(RelaySettings.serializer(), original)
        val decoded = SettingsStore.json.decodeFromString(RelaySettings.serializer(), json)

        assertEquals("My AI Assistant", decoded.assistantCustomName)
        assertEquals("architect", decoded.assistantRoleKey)
        assertEquals(listOf("node-alpha", "node-beta"), decoded.allowedOperators)
        assertEquals(1, decoded.automationTasks.size)

        val task = decoded.automationTasks.first()
        assertEquals("auto-1", task.id)
        assertEquals("Daily Gatekeeper", task.name)
        assertEquals("0 9 * * *", task.cron)
        assertEquals("/repo", task.workspacePath)
        assertTrue(task.autoMerge)
        assertTrue(task.enabled)
    }

    @Test
    fun testLegacySettingsCompatDefaults() {
        // 模拟旧版本 settings.json 中没有新字段
        val legacyJson = """{"nodeId": "old-node", "nick": "old-nick"}"""
        val decoded = SettingsStore.json.decodeFromString(RelaySettings.serializer(), legacyJson)

        assertFalse(decoded.assistantEnabled)
        assertFalse(decoded.allowExternalPrivateChat)
        assertNull(decoded.assistantCustomName)
        assertNull(decoded.assistantRoleKey)
        assertTrue(decoded.allowedOperators.isEmpty())
        assertTrue(decoded.automationTasks.isEmpty())
    }
}
