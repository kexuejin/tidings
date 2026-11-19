// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * 判定层的**路由**测试：`authProbe.kind` 决定走哪条判据，不靠驱动名分支。
 *
 * 这一层过去有三处 `key == "..."`（agy / dsh / zcode）。其中 agy 与 dsh 已经改成按声明路由，
 * 于是「新增一家 CLI_MARKER / FILE 驱动自动生效」；这个文件钉住那两条路由的**证据强度**：
 * 弱判据（凭证物在位）只能给 HINT，够不到绿灯。
 *
 * 全部合成输入（临时 HOME / 注入的 agy 探针），不跑任何 CLI，过 envDependentTestGate。
 */
class DriverAuthJudgementTest {

    private suspend fun snapshot(key: String, env: Map<String, String>): DriverAuthProbes.Snapshot =
        DriverAuthJudgement.snapshotOf(
            key = key,
            env = env,
            // 探针没跑成 = 本机没有该 CLI：路由仍要走正确分支，结论降级 UNKNOWN 而不是「未登录」。
            agyState = { null },
            zcodeSettingPath = "",
            cliAuthState = { null },
        )

    @Test
    fun fileDeclarationWithoutAnyCredentialIsADefiniteMiss() = runBlocking {
        // HOME 已知、目录里确实没有凭证文件 ⇒ 才能说「确定未登录」。
        val home = kotlin.io.path.createTempDirectory("judgement-empty-home").toFile().apply { deleteOnExit() }
        val s = snapshot("dsh", mapOf("HOME" to home.absolutePath))
        assertEquals(DriverAuthProbes.State.NOT_AUTHENTICATED, s.state, "凭证物两处都没有 ⇒ 确定未登录")
        assertEquals(DriverAuthProbes.Evidence.HINT, s.evidence, "弱判据只配 HINT")
        assertEquals("dsh", s.driverKey)
    }

    @Test
    fun fileDeclarationWithoutHomeIsUndecidableNotMissing() = runBlocking {
        // 没有 HOME = 连该查哪个目录都不知道（launchd / 精简 env / 测试夹具）。
        // 折成「确定未登录」会让界面指着其实存在的凭证文件要用户去配 key —— 读不到 ≠ 没有。
        val s = snapshot("dsh", emptyMap())
        assertEquals(DriverAuthProbes.State.UNKNOWN, s.state, "HOME 读不到 ⇒ 判不了，不是未登录")
        assertEquals(DriverAuthProbes.Evidence.HINT, s.evidence)
    }

    @Test
    fun fileDeclarationWithEnvKeyInPlaceIsNotAGreenLight() = runBlocking {
        val s = snapshot("dsh", mapOf(DshCredentials.ENV_KEY to "sk-x"))
        assertEquals(DriverAuthProbes.State.UNKNOWN, s.state, "凭证在位 ≠ 已登录（弱证据不画绿灯）")
        assertEquals(DriverAuthProbes.Evidence.HINT, s.evidence)
    }

    @Test
    fun fileDeclarationWithCredentialFileInPlaceIsNotAGreenLight() = runBlocking {
        val home = kotlin.io.path.createTempDirectory("judgement-home").toFile().apply { deleteOnExit() }
        val rel = CliDriverSpecs.authProbeOf("dsh")?.credentialFile
            ?: throw AssertionError("dsh 缺 credentialFile 声明")
        val file = home.resolve(rel)
        file.parentFile.mkdirs()
        file.writeText("DEEPSEEK_API_KEY: sk-x\n", Charsets.UTF_8)

        val s = snapshot("dsh", mapOf("HOME" to home.absolutePath))
        assertEquals(DriverAuthProbes.State.UNKNOWN, s.state)
        assertEquals(DriverAuthProbes.Evidence.HINT, s.evidence)
    }

    @Test
    fun markerDeclarationWithoutAProbeResultStaysUnknownWithStrongEvidence() = runBlocking {
        val s = snapshot(DriverKeys.AGY, emptyMap())
        assertEquals(DriverAuthProbes.State.UNKNOWN, s.state, "探针没跑成 = 读不到，不是未登录")
        assertEquals(DriverAuthProbes.Evidence.STRONG, s.evidence, "CLI_MARKER 是强判据口径（真跑子命令）")
    }
}
