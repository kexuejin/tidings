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
        assertEquals(
            DriverAuthProbes.State.AUTHENTICATED,
            DriverAuthProbes.classify(DriverKeys.AGY, 0, ""),
        )
    }

    @Test
    fun notLoggedInSignatureMeansNotAuthenticated() {
        val state = DriverAuthProbes.classify(
            DriverKeys.AGY,
            1,
            "You are not logged into Antigravity. Please sign in.",
        )
        assertEquals(DriverAuthProbes.State.NOT_AUTHENTICATED, state)
    }

    @Test
    fun missingCommandOrTimeoutStaysUnknown() {
        assertEquals(DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.classify(DriverKeys.AGY, 127, "command not found"))
        assertEquals(DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.classify(DriverKeys.AGY, -1, ""))
        assertEquals(DriverAuthProbes.State.UNKNOWN, DriverAuthProbes.classify(DriverKeys.AGY, 1, "network unreachable"))
    }

    /**
     * marker 判定按 key 取声明，不恒定用 agy 的那一份。
     *
     * codex 声明的是 CLI_CMD（没有 `authenticatedOn`/marker）⇒ 拿 agy 的「exit 0 = 已登录」
     * 去套它会得 AUTHENTICATED。这里要求 UNKNOWN：判据只能来自**被判定那家**的声明。
     */
    @Test
    fun classifyReadsTheDeclarationOfTheDriverBeingJudged() {
        assertEquals(
            DriverAuthProbes.State.UNKNOWN,
            DriverAuthProbes.classify("codex", 0, "gemini-3.8-flash-high"),
            "非 CLI_MARKER 的驱动不许套用 agy 的 exit-0 判据",
        )
        assertEquals(
            DriverAuthProbes.State.UNKNOWN,
            DriverAuthProbes.classify("no-such-driver", 0, "not logged into antigravity"),
            "没有声明的键一律 UNKNOWN（判不了 ≠ 已登录）",
        )
    }

    @Test
    fun strongSnapshotKeepsTheKeyItWasAskedAbout() {
        assertEquals(
            "dsh",
            DriverAuthProbes.strongSnapshot("dsh", DriverAuthProbes.State.AUTHENTICATED)?.driverKey,
            "快照归属必须是被探测的那家，不许借用 agy 的键",
        )
        assertNull(DriverAuthProbes.strongSnapshot("dsh", null))
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
    fun strongSnapshotPassesThroughNullWithoutInventingAState() {
        assertNull(DriverAuthProbes.strongSnapshot(DriverKeys.AGY, null))
        assertEquals(
            DriverAuthProbes.Snapshot(
                DriverKeys.AGY,
                DriverAuthProbes.State.NOT_AUTHENTICATED,
                DriverAuthProbes.Evidence.STRONG,
            ),
            DriverAuthProbes.strongSnapshot(DriverKeys.AGY, DriverAuthProbes.State.NOT_AUTHENTICATED),
        )
    }

    @Test
    fun hasCredentialTreatsBlankAsAbsent() {
        assertFalse(DriverAuthProbes.hasCredential(mapOf("K" to "   "), "K"))
        assertFalse(DriverAuthProbes.hasCredential(emptyMap(), "K"))
        assertTrue(DriverAuthProbes.hasCredential(mapOf("K" to "sk-x"), "K", "OTHER"))
    }

    /**
     * FILE 型声明的通用判据：env key 在位 / 凭证文件非空 ⇒ 有凭证物；两处都没有 ⇒ 没有。
     *
     * 判据读的是**声明**（`credentialKeys` + `credentialFile`），所以新增一家 FILE 驱动不必
     * 再往判定层加分支。这里用 dsh 的声明（唯一在册的 FILE）验证实现与声明对得上。
     */
    @Test
    fun declaredCredentialCoversEnvKeyAndCredentialFile() {
        val spec = CliDriverSpecs.authProbeOf("dsh") ?: throw AssertionError("dsh 缺 authProbe 声明")
        val home = kotlin.io.path.createTempDirectory("file-probe-home").toFile().apply { deleteOnExit() }
        val env = mapOf("HOME" to home.absolutePath)

        assertEquals(
            false, DriverAuthProbes.hasDeclaredCredential(env, spec),
            "空 HOME、无 env key ⇒ 两处都没有凭证物",
        )
        // HOME 读不到 = 不知道该查哪个目录 ⇒ null（判不了），不许折成 false（确定没有）。
        assertNull(
            DriverAuthProbes.hasDeclaredCredential(emptyMap(), spec),
            "没有 HOME ⇒ 判不了，不许冒充「确定未登录」",
        )
        assertEquals(
            true, DriverAuthProbes.hasDeclaredCredential(env + (DshCredentials.ENV_KEY to "sk-x"), spec),
            "声明的 env key 在位即算有",
        )
        assertEquals(
            false, DriverAuthProbes.hasDeclaredCredential(env + (DshCredentials.ENV_KEY to "  "), spec),
            "空白值当没有（与 CliProviderEnv.pick 同口径）",
        )

        val file = home.resolve(spec.credentialFile ?: throw AssertionError("dsh 缺 credentialFile"))
        file.parentFile.mkdirs()
        file.writeText("", Charsets.UTF_8)
        assertEquals(false, DriverAuthProbes.hasDeclaredCredential(env, spec), "空凭证文件不算有凭证")
        file.writeText("DEEPSEEK_API_KEY: sk-x\n", Charsets.UTF_8)
        assertEquals(true, DriverAuthProbes.hasDeclaredCredential(env, spec), "非空凭证文件算有")
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
