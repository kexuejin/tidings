// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 内置 CLI 驱动的**覆盖守卫**。
 *
 * [CliDriverSpecs] 是「每家驱动是什么」的单一来源。凡是列得出表的消费面，都必须与它对齐：
 * 少一家 = 装了却派不到 / 配了模型不生效；多一家 = 表里没有的野驱动没人管。两类都是
 * 编译通过、测试全绿、只在真机暴露的缺陷，所以在这里一次性钉死。
 *
 * 派生面（[AgentProfiles] / [DriverModelFlags]）直接读身份卡，本守卫钉「没有改回手写」；
 * 其余四处（凭证白名单、装配、发现、UI 显示名）是行为/敏感值，不搬进身份卡，用源码扫描
 * 比对其键集。判据用合成输入 + 固定源码路径，不读本机环境，过 envDependentTestGate。
 */
class CliDriverCoverageGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /** 取函数体（从签名到**行首无缩进**的收尾大括号；when 的收尾有缩进，不会误截）。 */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature).takeIf { it >= 0 }
            ?: throw AssertionError("找不到函数签名：$signature")
        val tail = source.substring(start)
        val end = tail.indexOf("\n}").takeIf { it >= 0 } ?: tail.length
        return tail.substring(0, end)
    }

    @Test
    fun derivedConsumersStillReadTheSpec() {
        val profiles = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/AgentProfiles.kt")
        assertTrue("CliDriverSpecs.builtins" in profiles, "AgentProfiles.known 必须从身份卡派生")
        assertTrue("CliDriverSpecs.workerKeys" in profiles, "workerPreference 必须从身份卡派生，别再手写列表")
        assertTrue("CliDriverSpecs.reviewerKeys" in profiles, "reviewerPreference 必须从身份卡派生，别再手写列表")

        val flags = live("agent-contract/src/main/kotlin/com/kapp/tenon/agent/drivers/DriverModelFlags.kt")
        assertTrue("CliDriverSpecs.builtins" in flags, "DriverModelFlags 必须从身份卡派生，别再手写模型旗标表")
    }

    @Test
    fun providerEnvCoversEverySpecKey() {
        val code = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliProviderEnv.kt")
        val keys = Regex("""cliDriverId\("([a-z0-9]+)"\)""").findAll(code)
            .map { it.groupValues[1] }.toSet()
        assertEquals(CliDriverSpecs.keys, keys, "CliProviderEnv.forDriver 必须与身份卡键集严格相等")
    }

    @Test
    fun registrationCoversEverySpecKey() {
        val code = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliSubprocessDriver.kt")
        val keys = Regex("""cliDriverId\("([a-z0-9]+)"\)""").findAll(code)
            .map { it.groupValues[1] }.toSet()
        assertEquals(CliDriverSpecs.keys, keys, "cliDriverRegistrations 必须与身份卡键集严格相等")
    }

    @Test
    fun discoveryCoversEverySpecKey() {
        val code = live("agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliDiscovery.kt")
        val keys = Regex("fun resolve([A-Z][A-Za-z]*)Cli\\(").findAll(code)
            .map { it.groupValues[1].lowercase() }.toSet()
        assertEquals(CliDriverSpecs.keys, keys, "CliDiscovery 必须为每家内置 CLI 提供 resolve 入口")
    }

    @Test
    fun uiDisplayNamesCoverEverySpecKey() {
        // UI 显示名是通用函数，允许为历史/外部 agentKind 多留映射；但每家内置 CLI 必须有。
        val code = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/TeamRosterDialog.kt")
        val body = bodyOf(code, "internal fun formatDriverName")
        val keys = Regex("\"([a-z0-9]+)\" ->").findAll(body).map { it.groupValues[1] }.toSet()
        val missing = CliDriverSpecs.keys - keys
        assertTrue(missing.isEmpty(), "formatDriverName 缺了内置 CLI 的友好名：$missing")
    }
}
