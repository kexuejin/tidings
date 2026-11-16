// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 只读登录态探针的判据测试：合成输出，不读本机装没装 CLI。
 *
 * 三家命令不同、输出格式不同，最容易各自抄错一处却都「能编译」。这里把实测的
 * 输出形状钉成用例，改命令行或正则必须先改这里。
 */
class CliAuthProbesTest {

    @Test
    fun codexReadsItsRealStatusWording() {
        assertEquals(
            CliAuthProbes.Verdict.LOGGED_OUT,
            CliAuthProbes.classify("codex", CliAuthProbes.Output(0, "Not logged in\n")),
        )
        assertEquals(
            CliAuthProbes.Verdict.LOGGED_IN,
            CliAuthProbes.classify("codex", CliAuthProbes.Output(0, "Logged in as user@example.com\n")),
        )
    }

    @Test
    fun claudeReadsTheLoggedInJsonField() {
        assertEquals(
            CliAuthProbes.Verdict.LOGGED_OUT,
            CliAuthProbes.classify(
                "claude",
                CliAuthProbes.Output(0, "{\"loggedIn\": false, \"authMethod\": \"none\"}"),
            ),
        )
        assertEquals(
            CliAuthProbes.Verdict.LOGGED_IN,
            CliAuthProbes.classify(
                "claude",
                CliAuthProbes.Output(0, "{\"loggedIn\": true, \"authMethod\": \"oauth\"}"),
            ),
        )
    }

    @Test
    fun opencodeTreatsStoredCredentialsAsHintOnly() {
        // 这里只判「存过」；映射层再把 LOGGED_IN 降级成 HINT/UNKNOWN，不画绿灯。
        assertEquals(
            CliAuthProbes.Verdict.LOGGED_IN,
            CliAuthProbes.classify("opencode", CliAuthProbes.Output(0, "OpenCode Go  OpenCode Go  stored\n")),
        )
        assertEquals(
            CliAuthProbes.Verdict.LOGGED_OUT,
            CliAuthProbes.classify("opencode", CliAuthProbes.Output(0, "")),
        )
    }

    @Test
    fun unrecognisedOutputAndFailedProcessFallBackToUnknown() {
        val unknown = CliAuthProbes.Verdict.UNKNOWN
        assertEquals(unknown, CliAuthProbes.classify("codex", CliAuthProbes.Output(0, "some noise")))
        assertEquals(unknown, CliAuthProbes.classify("dsh", CliAuthProbes.Output(0, "Not logged in")))
    }

    @Test
    fun nonZeroExitStillReportsNotLoggedIn() {
        // 最贵的一处：codex / claude 未登录时**故意**返回 exit=1（本机实测），
        // 把非 0 当「命令失败」丢掉，界面就又变成「全未判定」。
        assertEquals(
            CliAuthProbes.Verdict.LOGGED_OUT,
            CliAuthProbes.classify("codex", CliAuthProbes.Output(1, "Not logged in\n")),
        )
        assertEquals(
            CliAuthProbes.Verdict.LOGGED_OUT,
            CliAuthProbes.classify("claude", CliAuthProbes.Output(1, "{\"loggedIn\": false}")),
        )
    }

    @Test
    fun probeRunsTheReadOnlySubcommandAndDegradesOnFailure() = runBlocking {
        val argvSeen = mutableListOf<List<String>>()
        val runner: suspend (List<String>) -> CliAuthProbes.Output = { argv ->
            argvSeen += argv
            CliAuthProbes.Output(0, "Not logged in")
        }
        assertEquals(
            CliAuthProbes.Verdict.LOGGED_OUT,
            CliAuthProbes.probe("codex", "/bin/codex", runner),
        )
        assertEquals(listOf("/bin/codex", "login", "status"), argvSeen.single())

        assertEquals(
            CliAuthProbes.Verdict.UNKNOWN,
            CliAuthProbes.probe("codex", null, runner),
        )
        assertEquals(
            CliAuthProbes.Verdict.UNKNOWN,
            CliAuthProbes.probe("claude", "/bin/claude") { throw IllegalStateException("boom") },
        )
    }
}
