// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `EmployeeProfile` 新增字段的**向后兼容**守卫（方案 §3.9「零迁移的两个前提」）。
 *
 * ## 为什么这条测试比它看起来重要
 *
 * `driverKey` / `model` 是后加的字段，而 `settings.json` 是老版本**已经写出去**的文件。
 * kotlinx.serialization 把「没有默认值的字段」当成**必需字段**：老 JSON 里没有这个键时
 * 反序列化会抛 `MissingFieldException`。而 `SettingsStore.load()` 的实现是
 *
 * ```
 * runCatching { decodeFromString(...) }.getOrNull()
 * ```
 *
 * —— 异常被吞成 `null`，`RelayController` 于是落到 `RelaySettings.firstRun()`
 * **重新分配 nodeId**。症状不是崩溃，是这台设备的身份被静默换掉：对端全掉、
 * 聊天历史与它断链。所以「字段带默认值」不是风格问题，它守的是身份不丢。
 *
 * 这条测试只在**字段没有默认值**时变红 —— 反向验证：把 `= ""` 去掉，它立刻红。
 */
class EmployeeProfileSettingsCompatTest {

    /**
     * 老版本写出的最小 settings（只有 nodeId/nick）。
     *
     * 断言两件事：① 解码**不返回 null**（否则上层会 firstRun 重发身份）；
     * ② 缺失的新字段取默认值，而不是抛异常或变成脏值。
     */
    @Test
    fun legacySettingsWithoutDriverKeyStillDecodesAndKeepsNodeId() {
        val legacyJson = """{"nodeId": "old-node","nick": "old-nick"}"""

        val decoded = SettingsStore.json.decodeFromString(RelaySettings.serializer(), legacyJson)

        // ① 身份没被换掉：这是这条测试真正守的东西。
        assertEquals("old-node", decoded.nodeId, "老 settings 解码后 nodeId 必须原样保留，否则等于重发身份")
        // ② 老库没有员工档案，这里只是证明整份设置能读出来。
        assertTrue(decoded.employeeProfiles.isEmpty(), "老库没有员工档案")
    }

    /**
     * 老库里**已经有员工档案**、但档案里没有 `driverKey` / `model` 的那一档。
     *
     * 这一档才是真机上会遇到的：档案是上一个版本建的，字段是这一版加的。
     */
    @Test
    fun legacyEmployeeProfilesWithoutDriverKeyDecodeWithDefaults() {
        val legacyJson = """
            {
              "nodeId": "old-node",
              "employeeProfiles": [
                {
                  "employeeId": "emp-legacy",
                  "agentId": "dsh-029bbbf3",
                  "name": "老员工",
                  "token": "lao-yuan-gong"
                }
              ]
            }
        """.trimIndent()

        val decoded = SettingsStore.json.decodeFromString(RelaySettings.serializer(), legacyJson)

        assertEquals("old-node", decoded.nodeId)
        val profile = decoded.employeeProfiles.single()
        assertEquals("emp-legacy", profile.employeeId)
        assertEquals("dsh-029bbbf3", profile.agentId)
        // 关键：缺 driverKey 时取空串（= 未选/老数据），而不是解码失败。
        // 空串在派单期会试一次 resolveKey 反解，解不出就报 NO_DRIVER —— 不猜、也不永久坏掉。
        assertEquals("", profile.driverKey, "老档案缺 driverKey 时必须落在空串，而不是让整份设置解码失败")
        assertNull(profile.model, "老档案缺 model 时为 null（跟随驱动默认）")
    }

    /**
     * 新档案写出去再读回来，三个新字段原样往返（含空串与 null 两种「未选」）。
     */
    @Test
    fun newProfileWithDriverKeyRoundTrips() {
        val profile = EmployeeProfile(
            employeeId = "emp-new",
            agentId = "",
            name = "新员工",
            token = "xin-yuan-gong",
            driverKey = "claude",
            model = "claude-sonnet-4",
        )
        val settings = RelaySettings(nodeId = "self-node", nick = "self", udpPort = 0, tcpPort = 0)
            .copy(employeeProfiles = listOf(profile))

        val json = SettingsStore.json.encodeToString(RelaySettings.serializer(), settings)
        val back = SettingsStore.json.decodeFromString(RelaySettings.serializer(), json)

        val readBack = back.employeeProfiles.single()
        assertEquals("emp-new", readBack.employeeId)
        assertEquals("", readBack.agentId, "agentId 空串（还没派过单）必须能往返")
        assertEquals("claude", readBack.driverKey)
        assertEquals("claude-sonnet-4", readBack.model)
    }

    /**
     * 老构建读新 JSON：`ignoreUnknownKeys` 会让它忽略这两个新键，**不抛异常**。
     *
     * 这不能保证「写回兼容」—— 老构建下一次 `save()` 会按旧类重新序列化、把新键抹掉
     * （Debug 与 Release 同机共存时可达）。所以只断言「读得进」：它证明的是「不崩」，
     * 不是「配置不会丢」；写回抹键由派单期的如实回落兜住（空 driverKey ⇒ 反解一次 ⇒
     * 解不出报 NO_DRIVER，不当成「这个员工永久没有运行时」）。
     */
    @Test
    fun newJsonWithDriverKeyIsReadableByTolerantDecoder() {
        val newJson = """
            {
              "nodeId": "old-node",
              "employeeProfiles": [
                {
                  "employeeId": "emp-x",
                  "agentId": "",
                  "name": "x",
                  "token": "x",
                  "driverKey": "dsh",
                  "model": null
                }
              ]
            }
        """.trimIndent()

        // 同一个解码器（ignoreUnknownKeys = true）读到新键不会报错。
        val decoded = SettingsStore.json.decodeFromString(RelaySettings.serializer(), newJson)
        assertEquals("emp-x", decoded.employeeProfiles.single().employeeId)
        assertEquals("dsh", decoded.employeeProfiles.single().driverKey)
    }
}
