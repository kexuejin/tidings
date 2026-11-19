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

    /**
     * 取函数体：从签名起按**括号配对**收到与之配对的收尾大括号。
     *
     * 早先按「行首无缩进的 `}`」截断，只够用在类里独占一行的顶层函数上；换成
     * `= when (…) { … }` 这种表达式体后，`when` 的收尾也缩进、类收尾才是列 0，
     * 于是一路吃到文件末尾，把邻居函数也算了进来（守卫会因别人的表而变绿）。
     */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature).takeIf { it >= 0 }
            ?: throw AssertionError("找不到函数签名：$signature")
        var depth = 0
        for (i in start until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        throw AssertionError("函数体没有收尾大括号：$signature")
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
        // 回落显示名只有一份手写表：`DriverBrand.displayNameOf`（名册读不到时才用得上）。
        // 从前 `formatDriverName` 里还抄了一份 `when`，两份表必然漂（`Codex CLI` vs `Codex`），
        // 所以那份抄件被删了 —— 这张表就是唯一的落点。
        // UI 显示名是通用函数，允许为历史/外部 agentKind 多留映射；但每家内置 CLI 必须有。
        val code = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/theme/DriverBrand.kt")
        val body = bodyOf(code, "fun displayNameOf(driverKey: String): String = when")
        val keys = Regex("\"([a-z0-9]+)\" ->").findAll(body).map { it.groupValues[1] }.toSet()
        val missing = CliDriverSpecs.keys - keys
        assertTrue(missing.isEmpty(), "DriverBrand.displayNameOf 缺了内置 CLI 的回落名：$missing")

        // 反向钉子：`formatDriverName` 不许再长出第二份手写表（只许读名册 + 回落这一处）。
        val roster = live("composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/components/TeamRosterDialog.kt")
        val format = bodyOf(roster, "internal fun formatDriverName")
        assertTrue(
            "DriverBrand.displayNameOf" in format,
            "formatDriverName 必须回落到 DriverBrand.displayNameOf，别再手写第二份表",
        )
        assertTrue(
            Regex("\"([a-z0-9]+)\" ->").findAll(format).none(),
            "formatDriverName 里不许再出现手写键表（两份表必然漂）",
        )
    }
}
