// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.domain.EmployeeProfile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 执行桥的驱动键解析（真机现场回归）：
 *
 * 现场：MINT 建档的人的席位 `agentId` 是 `emp-…`（身份/路由键），驱动注册表认的是
 * `dsh` / `dsh-cli`（配置键）。执行桥只用 `resolveKey(agentId)` ⇒ 恒 null ⇒
 * `[OCGO-BRIDGE] … cliDriver=null` ⇒ CLI 分支整段跳过 ⇒ 群里只有「没有完成这次任务」。
 *
 * 四条判据钉住「配置优先、不猜、不替换」。
 */
class DispatchDriverKeyTest {

    private val available = setOf("dsh", "codex", "claude")

    private fun profile(
        employeeId: String = "emp-1",
        agentId: String = "",
        driverKey: String = "",
    ) = EmployeeProfile(
        employeeId = employeeId,
        agentId = agentId,
        name = "小工",
        token = "x",
        driverKey = driverKey,
    )

    @Test
    fun theProfilesDriverKeyWinsOverAnIdentityKeyThatTheRegistryDoesNotKnow() {
        // 现场那一档：agentId 是 emp-…，注册表不认识；档案说 dsh。
        val key = dispatchDriverKey(
            agentId = "emp-e2e-01",
            profiles = listOf(profile(agentId = "emp-e2e-01", driverKey = "dsh")),
            availableKeys = available,
        )
        assertEquals("dsh", key)
    }

    @Test
    fun aProfilesCliSuffixedKeyIsNormalisedToTheRegistryKey() {
        // 档案里存 `dsh-cli`（驱动 id）也要落到注册表键 `dsh` 上，否则解析器认不出。
        val key = dispatchDriverKey(
            agentId = "emp-1",
            profiles = listOf(profile(employeeId = "emp-1", driverKey = "dsh-cli")),
            availableKeys = available,
        )
        assertEquals("dsh", key)
    }

    @Test
    fun withoutAProfileTheLegacySeatIdStillResolves() {
        // 老数据：agentId 本身就是带驱动前缀的席位 ID（dsh-<host>-<ws>）。
        val key = dispatchDriverKey("dsh-a030882e", emptyList(), available)
        assertEquals("dsh", key)
    }

    @Test
    fun anUninstalledDeclaredDriverIsNotSilentlyReplaced() {
        // 档案写的驱动不在这台机器上 ⇒ 原样带下去，让执行桥如实报「运行时不在」，
        // 绝不换成列表里的第一家（那会在用户没配过的底座上跑真实命令）。
        val key = dispatchDriverKey(
            agentId = "emp-1",
            profiles = listOf(profile(driverKey = "zcode")),
            availableKeys = available,
        )
        assertEquals("zcode", key)
    }

    @Test
    fun nothingResolvableKeepsTheOriginalKeySoTheCallerCanRefuse() {
        // 解不出就得原样回：调用方据此得到 resolveCliDriver == null 并退单（不猜默认驱动）。
        val key = dispatchDriverKey("emp-unknown", emptyList(), available)
        assertEquals("emp-unknown", key)
    }
}
