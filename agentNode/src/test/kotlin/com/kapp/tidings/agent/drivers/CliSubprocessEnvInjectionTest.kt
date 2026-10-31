// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * envOverrides 注入的行为判据（升级轮 T-3a）：凭证环境变量**真的进子进程**。
 *
 * 探针形状：/bin/sh -c '[ -n "$TIDINGS_PROBE_TOKEN" ]' —— 变量在 ⇒ 退出码 0（Finished），
 * 不在 ⇒ 退出码 1（Error）。退出码是子进程对环境的最诚实回执，不经 driver 的行解析。
 *
 * 反向验证：摘掉 envOverrides.forEach 注入 ⇒ 探针退出 1 ⇒ 用例红在 Error。
 */
class CliSubprocessEnvInjectionTest {

    @Test
    fun credentialEnvVarsReachTheSubprocess() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<DriverEvent>()
        val driver = CliSubprocessDriver(
            driverId = "env-probe",
            displayName = "Env Probe",
            cliPath = "/bin/sh",
            argSpec = { _ -> listOf("-c", "[ -n \"\$TIDINGS_PROBE_TOKEN\" ]") },
            envOverrides = mapOf("TIDINGS_PROBE_TOKEN" to "sk-injected-value"),
        )
        val handle = driver.openSession("/tmp", null)
        driver.executeTask(handle.sessionId, "probe") { events += it }
        assertTrue(
            events.any { it is DriverEvent.Finished },
            "注入的环境变量必须让探针退出 0（变量真的进了子进程环境）；events=" +
                events.joinToString { it.toString() },
        )
        assertTrue(
            events.none { it is DriverEvent.Error },
            "探针不许报错（报错 = 变量没进子进程环境）：events=" + events.joinToString { it.toString() },
        )
    }
}
