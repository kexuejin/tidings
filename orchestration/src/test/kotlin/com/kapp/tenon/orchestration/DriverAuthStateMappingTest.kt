// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.agent.drivers.CliDriverSpecs
import com.kapp.tenon.agent.drivers.DriverAuthProbes
import com.kapp.tenon.agent.drivers.DriverKeys
import com.kapp.tenon.domain.DriverAuthEvidenceUi
import com.kapp.tenon.domain.DriverAuthRemediationUi
import com.kapp.tenon.domain.DriverAuthStateUi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        // HOME 已知、目录里没有凭证文件，env 也没有 key ⇒ 两处都没有，才能说「确定未登录」。
        val home = kotlin.io.path.createTempDirectory("mapping-empty-home").toFile().apply { deleteOnExit() }
        val result = DriverAuthStateMapping.map(
            inServiceDriverIds = setOf("dsh-cli"),
            env = mapOf("HOME" to home.absolutePath),
            agyState = noAgy,
        )
        val dsh = result["dsh"]
        assertNotNull(dsh)
        assertEquals(DriverAuthStateUi.NOT_AUTHENTICATED, dsh.state)
        assertEquals(DriverAuthEvidenceUi.HINT, dsh.evidence)
        assertEquals(DriverAuthRemediationUi.TOKEN_ENV, dsh.remediation)
    }

    @Test
    fun dshWithoutHomeIsUndecidableNotMissing() = runBlocking {
        // 读不到 HOME = 不知道该查哪个目录 ⇒ UNKNOWN（放行），不许冒充「确定未登录」。
        val result = DriverAuthStateMapping.map(
            inServiceDriverIds = setOf("dsh-cli"),
            env = emptyMap(),
            agyState = noAgy,
        )
        val dsh = result["dsh"]
        assertNotNull(dsh)
        assertEquals(DriverAuthStateUi.UNKNOWN, dsh.state)
        assertEquals(DriverAuthEvidenceUi.HINT, dsh.evidence)
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
    fun reportableSetDoesNotAdmitUnknownDrivers() = runBlocking {
        // 上面那条钉「身份卡有的都在表里」，这条钉「表里没有身份卡外的键」。两个方向都要堵：
        // 多出来的键会让 remediationOf / remediationDetailOf 长出死分支，也说明有人在按记忆
        // 补名单——而面板的可见集只有一个真源（driver-specs.json）。
        val result = DriverAuthStateMapping.map(setOf("gemini"), emptyMap(), noAgy)
        assertTrue(
            result.isEmpty(),
            "身份卡里没有的驱动不许出现在登录态面板（REPORTABLE 多了键：${result.keys}）",
        )
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
    fun everySpecDriverIsReportableWithAnExactAction() = runBlocking {
        // 面板可见集与两张引导表都必须以**身份卡**为基准 —— 新增一家只改 JSON，这里不红才算
        // 「声明即生效」。从前 REPORTABLE 是 7 个字面量、这条测试也写死 7 条，于是身份卡新增
        // 驱动时永远绿，而界面上那家根本不出现；remediationOf / remediationDetailOf 的 else 兜底
        // 还会让它即使出现也「有行无话」。
        val result = DriverAuthStateMapping.map(
            inServiceDriverIds = CliDriverSpecs.keys.toSet(),
            env = emptyMap(),
            agyState = noAgy,
            zcodeSettingPath = "",
        )
        assertEquals(
            CliDriverSpecs.keys, result.keys.toSet(),
            "身份卡里每家驱动都必须出现在登录态面板（REPORTABLE 漏一家 = 那家在界面上彻底消失）",
        )
        result.values.forEach { entry ->
            assertNotEquals(
                DriverAuthRemediationUi.NONE, entry.remediation,
                "${entry.driverKey} 有面板行但没有引导类别（remediationOf 落到 else = 有行无话）",
            )
            assertTrue(
                entry.remediationDetail.isNotBlank(),
                "${entry.driverKey} 没有确切补凭证动作（remediationDetailOf 落到 else = 有行无话）",
            )
        }
        // 具体动作是逐家实测值（界面直接填进文案模板），改名时别随手改。
        assertEquals("codex login", result["codex"]?.remediationDetail)
        assertEquals("claude auth", result["claude"]?.remediationDetail)
        assertEquals("opencode auth", result["opencode"]?.remediationDetail)
        assertEquals("DEEPSEEK_API_KEY", result["dsh"]?.remediationDetail)
        assertEquals("ZCODE_API_KEY", result["zcode"]?.remediationDetail)
    }
}
