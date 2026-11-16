// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 登录态探针的纯函数测试：全部合成输入，不读本机 CLI / 凭证文件（envDependentTestGate 口径）。
 */
class DriverAuthProbesTest {

    @Test
    fun exitZeroMeansAuthenticated() {
        assertEquals(DriverAuthProbes.State.AUTHENTICATED, DriverAuthProbes.classify(0, ""))
    }

    @Test
    fun notLoggedInSignatureMeansNotAuthenticated() {
        val state = DriverAuthProbes.classify(
            1,
            "You are not logged into Antigravity. Please sign in.",
        )
        assertEquals(DriverAuthProbes.State.NOT_AUTHENTICATED, state)
    }

    @Test
    fun missingCommandOrTimeoutStaysUnknown() {
        assertEquals(DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.classify(127, "command not found"))
        assertEquals(DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.classify(-1, ""))
        assertEquals(DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.classify(1, "network unreachable"))
    }

    @Test
    fun hintNeverClaimsAuthenticated() {
        val present = DriverAuthProbes.hintSnapshot("dsh", credentialPresent = true)
        assertEquals(DriverAuthProbes.State.UNKNOWN, present.state)
        assertEquals(DriverAuthProbes.Evidence.HINT, present.evidence)
    }

    @Test
    fun hintAbsentCredentialIsNotAuthenticatedButStillHintEvidence() {
        val absent = DriverAuthProbes.hintSnapshot("dsh", credentialPresent = false)
        assertEquals(DriverAuthProbes.State.NOT_AUTHENTICATED, absent.state)
        assertEquals(DriverAuthProbes.Evidence.HINT, absent.evidence)
    }

    @Test
    fun agySnapshotPassesThroughNullWithoutInventingAState() {
        assertNull(DriverAuthProbes.agySnapshot(null))
        assertEquals(
            DriverAuthProbes.Snapshot(
                DriverKeys.AGY,
                DriverAuthProbes.State.NOT_AUTHENTICATED,
                DriverAuthProbes.Evidence.STRONG,
            ),
            DriverAuthProbes.agySnapshot(DriverAuthProbes.State.NOT_AUTHENTICATED),
        )
    }

    @Test
    fun hasCredentialTreatsBlankAsAbsent() {
        assertFalse(DriverAuthProbes.hasCredential(mapOf("K" to "   "), "K"))
        assertFalse(DriverAuthProbes.hasCredential(emptyMap(), "K"))
        assertTrue(DriverAuthProbes.hasCredential(mapOf("K" to "sk-x"), "K", "OTHER"))
    }

    private fun tempSetting(content: String): String {
        val f = kotlin.io.path.createTempFile("zcode-setting", ".json").toFile()
        f.writeText(content, Charsets.UTF_8)
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test
    fun zcodeEmptySelectionIsNotAuthenticated() {
        assertFalse(
            DriverAuthProbes.zcodeHasModelSelected(
                tempSetting("""{"modelProviderFamilySelectedKeys": {}, "locale": "zh-CN"}"""),
            ),
        )
    }

    @Test
    fun zcodeMissingKeyOrFileIsNotAuthenticated() {
        assertFalse(DriverAuthProbes.zcodeHasModelSelected(tempSetting("""{"locale": "zh-CN"}""")))
        assertFalse(DriverAuthProbes.zcodeHasModelSelected("/no/such/zcode/setting.json"))
        assertFalse(DriverAuthProbes.zcodeHasModelSelected(""))
    }

    @Test
    fun zcodeWithSelectedKeyIsOnlyHintNotAuthenticated() {
        val path = tempSetting(
            """{"modelProviderFamilySelectedKeys": {"bigmodel": "coding-plan:builtin:bigmodel-coding-plan"}}""",
        )
        assertTrue(DriverAuthProbes.zcodeHasModelSelected(path))
        // 选过模型也只是 HINT：在位不画绿灯（守住两维判据）
        assertEquals(
            DriverAuthProbes.State.UNKNOWN,
            DriverAuthProbes.hintSnapshot("zcode", DriverAuthProbes.zcodeHasModelSelected(path)).state,
        )
    }

    @Test
    fun zcodeSelectionWithEscapedQuotesStillParsed() {
        val path = tempSetting(
            """{"modelProviderFamilySelectedKeys": {"p": "a\\\"b"}}""",
        )
        assertTrue(DriverAuthProbes.zcodeHasModelSelected(path))
    }
}
