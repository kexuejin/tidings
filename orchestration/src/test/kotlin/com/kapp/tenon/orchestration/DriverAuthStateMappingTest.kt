// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.DriverAuthProbes
import com.kapp.tenon.agent.drivers.DriverKeys
import com.kapp.tenon.domain.DriverAuthEvidenceUi
import com.kapp.tenon.domain.DriverAuthRemediationUi
import com.kapp.tenon.domain.DriverAuthStateUi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 驱动登录态映射的判据测试（合成输入，不读本机状态）。
 *
 * 重点钉两件最贵的事：
 * 1. 弱证据的「没看到凭证」在路径不可靠的驱动上**不许**冒充「确定未登录」；
 * 2. 在位是 Set，输出行序必须稳定，否则界面每次重组都跳。
 */
class DriverAuthStateMappingTest {

    private val noAgy: suspend () -> DriverAuthProbes.State? = { null }

    @Test
    fun emptyCatalogMeansEmptyMapNotNull() = runBlocking {
        val result = DriverAuthStateMapping.map(emptySet(), emptyMap(), noAgy)
        assertTrue(result.isEmpty())
    }

    @Test
    fun codebuddyNeverClaimsDefiniteMissing() = runBlocking {
        // auth session 路径哈希不可靠 ⇒ 缺凭证也只报 UNKNOWN，不冒充确定未登录。
        val result = DriverAuthStateMapping.map(
            inServiceDriverIds = setOf("codebuddy-cli"),
            env = emptyMap(),
            agyState = noAgy,
        )
        val cb = result["codebuddy"]
        assertNotNull(cb)
        assertEquals(DriverAuthStateUi.UNKNOWN, cb.state)
        assertEquals(DriverAuthEvidenceUi.HINT, cb.evidence)
    }

    @Test
    fun zcodeNoSettingMeansNoModelSelected() = runBlocking {
        // 没有 setting 路径 = 读不到选模型记录 ⇒ 确定选不出模型（快败提示成立）。
        val result = DriverAuthStateMapping.map(
            inServiceDriverIds = setOf("zcode-cli"),
            env = emptyMap(),
            agyState = noAgy,
            zcodeSettingPath = "",
        )
        val zc = result["zcode"]
        assertNotNull(zc)
        assertEquals(DriverAuthStateUi.NOT_AUTHENTICATED, zc.state)
        assertEquals(DriverAuthEvidenceUi.HINT, zc.evidence)
        assertEquals(DriverAuthRemediationUi.APP_MODEL, zc.remediation)
    }

    @Test
    fun zcodeWithSelectedModelIsOnlyHint() = runBlocking {
        val f = kotlin.io.path.createTempFile("zcode-setting", ".json").toFile()
        f.writeText("""{"modelProviderFamilySelectedKeys": {"bigmodel": "coding-plan:builtin:x"}}""")
        f.deleteOnExit()
        val result = DriverAuthStateMapping.map(
            inServiceDriverIds = setOf("zcode-cli"),
            env = emptyMap(),
            agyState = noAgy,
            zcodeSettingPath = f.absolutePath,
        )
        assertEquals(DriverAuthStateUi.UNKNOWN, result["zcode"]?.state)
    }

    @Test
    fun dshWithNoCredentialAnywhereIsDefiniteMissing() = runBlocking {
        val result = DriverAuthStateMapping.map(
            inServiceDriverIds = setOf("dsh-cli"),
            env = emptyMap(),   // 无 HOME、无 env key ⇒ 两处都没有凭证
            agyState = noAgy,
        )
        val dsh = result["dsh"]
        assertNotNull(dsh)
        assertEquals(DriverAuthStateUi.NOT_AUTHENTICATED, dsh.state)
        assertEquals(DriverAuthEvidenceUi.HINT, dsh.evidence)
        assertEquals(DriverAuthRemediationUi.TOKEN_ENV, dsh.remediation)
    }

    @Test
    fun agyStrongProbeWinsOverHintEvidence() = runBlocking {
        val result = DriverAuthStateMapping.map(
            inServiceDriverIds = setOf("agy-cli"),
            env = emptyMap(),
            agyState = { DriverAuthProbes.State.NOT_AUTHENTICATED },
        )
        val agy = result[DriverKeys.AGY]
        assertNotNull(agy)
        assertEquals(DriverAuthStateUi.NOT_AUTHENTICATED, agy.state)
        assertEquals(DriverAuthEvidenceUi.STRONG, agy.evidence)
        assertEquals(DriverAuthRemediationUi.TERMINAL_LOGIN, agy.remediation)
    }

    @Test
    fun agyProbeUnavailableFallsBackToUnknownNotMissing() = runBlocking {
        val result = DriverAuthStateMapping.map(
            inServiceDriverIds = setOf("agy-cli"),
            env = emptyMap(),
            agyState = { null },
        )
        assertEquals(DriverAuthStateUi.UNKNOWN, result[DriverKeys.AGY]?.state)
    }

    @Test
    fun outputOrderIsStableRegardlessOfInputSetOrder() = runBlocking {
        val ids = setOf("zcode-cli", "agy-cli", "codebuddy-cli", "dsh-cli", "claude-cli", "codex-cli")
        val a = DriverAuthStateMapping.map(ids, emptyMap(), noAgy).keys.toList()
        val reversedOrder = ids.toList().reversed().toSet()
        val b = DriverAuthStateMapping.map(reversedOrder, emptyMap(), noAgy).keys.toList()
        assertEquals(a, b)
    }

    @Test
    fun nativeIsNotReportable() = runBlocking {
        val result = DriverAuthStateMapping.map(setOf("native"), emptyMap(), noAgy)
        assertTrue(result.isEmpty())
    }

    @Test
    fun remediationVocabularyIsSingleSourced() {
        assertEquals(DriverAuthRemediationUi.TERMINAL_LOGIN, DriverAuthStateMapping.remediationOf("codebuddy"))
        assertEquals(DriverAuthRemediationUi.APP_MODEL, DriverAuthStateMapping.remediationOf("zcode"))
        assertEquals(DriverAuthRemediationUi.TOKEN_ENV, DriverAuthStateMapping.remediationOf("dsh"))
    }

    @Test
    fun cliStatusProbeReplacesTheHardcodedUnknown() = runBlocking {
        // 这三家原本被写死 UNKNOWN —— 界面永远只能说「未判定」。真探针结果必须进来，
        // 但 opencode 的 stored 仍不许画绿灯（存过凭证 ≠ 凭证有效）。
        val result = DriverAuthStateMapping.map(
            inServiceDriverIds = setOf("codex-cli", "claude-cli", "opencode-cli", "codebuddy-cli"),
            env = emptyMap(),
            agyState = noAgy,
            cliAuthState = { key ->
                when (key) {
                    "codex" -> DriverAuthProbes.State.NOT_AUTHENTICATED
                    "claude" -> DriverAuthProbes.State.AUTHENTICATED
                    "opencode" -> DriverAuthProbes.State.AUTHENTICATED
                    else -> null
                }
            },
        )
        assertEquals(DriverAuthStateUi.NOT_AUTHENTICATED, result["codex"]?.state)
        assertEquals(DriverAuthEvidenceUi.STRONG, result["codex"]?.evidence)
        assertEquals(DriverAuthStateUi.AUTHENTICATED, result["claude"]?.state)
        assertEquals(DriverAuthEvidenceUi.STRONG, result["claude"]?.evidence)
        assertEquals(DriverAuthStateUi.UNKNOWN, result["opencode"]?.state)
        assertEquals(DriverAuthEvidenceUi.HINT, result["opencode"]?.evidence)
        assertEquals(DriverAuthStateUi.UNKNOWN, result["codebuddy"]?.state)
    }

    @Test
    fun everyReportableDriverCarriesAnExactAction() = runBlocking {
        // 「未判定」必须给出能照着敲的一条命令/一个 env key —— 只有类别没有动作，
        // 界面就只剩一句「未读到可靠凭证线索」的空话，用户依然不知道去哪里登录。
        val ids = setOf(
            "agy-cli", "claude-cli", "codex-cli", "opencode-cli",
            "dsh-cli", "codebuddy-cli", "zcode-cli",
        )
        val result = DriverAuthStateMapping.map(ids, emptyMap(), noAgy, zcodeSettingPath = "")
        assertEquals(7, result.size)
        result.values.forEach { entry ->
            assertTrue(
                entry.remediationDetail.isNotBlank(),
                "${entry.driverKey} 没有确切补凭证动作",
            )
        }
        assertEquals("codex login", result["codex"]?.remediationDetail)
        assertEquals("claude auth", result["claude"]?.remediationDetail)
        assertEquals("opencode auth", result["opencode"]?.remediationDetail)
        assertEquals("DEEPSEEK_API_KEY", result["dsh"]?.remediationDetail)
        assertEquals("ZCODE_API_KEY", result["zcode"]?.remediationDetail)
    }
}
