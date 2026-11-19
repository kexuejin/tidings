// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * authProbe golden 夹具 × **生产判定**的交叉验证。
 *
 * `driver-specs.json` 每家的 `authProbe.golden` 是从真实脱敏输出抽出的样本 + 期望 verdict。
 * 本测试不重演判定逻辑（那是机制，留代码），而是把每条样本喂给**生产实际调用**的
 * `CliAuthProbes.classify`（CLI_CMD）/ `DriverAuthProbes.classify`（CLI_MARKER），
 * 断言 verdict 与夹具一致。
 *
 * 这样「改一行 JSON 正则就改了算不算已登录」的代价被锁住：正则改坏 ⇒ 这里红；
 * 且样本是静态字符串，不现场跑任何 CLI（过 envDependentTestGate）。
 *
 * 反向验证（手工）：把 codex 的 `not logged in` 信号删掉，本测试的 codex 未登录样本立刻红；
 * 把 opencode 的 blank 信号去掉，空输出样本立刻红。
 */
class AuthProbeGoldenTest {

    private fun CliAuthProbes.Verdict.toAuth(): CliDriverSpecs.AuthVerdict = when (this) {
        CliAuthProbes.Verdict.LOGGED_IN -> CliDriverSpecs.AuthVerdict.AUTHENTICATED
        CliAuthProbes.Verdict.LOGGED_OUT -> CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED
        CliAuthProbes.Verdict.UNKNOWN -> CliDriverSpecs.AuthVerdict.UNKNOWN
    }

    private fun DriverAuthProbes.State.toAuth(): CliDriverSpecs.AuthVerdict = when (this) {
        DriverAuthProbes.State.AUTHENTICATED -> CliDriverSpecs.AuthVerdict.AUTHENTICATED
        DriverAuthProbes.State.NOT_AUTHENTICATED -> CliDriverSpecs.AuthVerdict.NOT_AUTHENTICATED
        DriverAuthProbes.State.UNKNOWN -> CliDriverSpecs.AuthVerdict.UNKNOWN
    }

    /** 每家的 golden 都必须经生产 classify 判出期望值——这是「正则入表」的安全网。 */
    @Test
    fun everyGoldenSampleMatchesProductionClassify() {
        var samples = 0
        for (key in CliDriverSpecs.keys) {
            val spec = CliDriverSpecs.authProbeOf(key) ?: continue
            if (spec.golden.isEmpty()) continue
            for (g in spec.golden) {
                val actual = when (spec.kind) {
                    CliDriverSpecs.AuthProbeKind.CLI_CMD ->
                        CliAuthProbes.classify(key, CliAuthProbes.Output(g.exitCode, g.text)).toAuth()
                    CliDriverSpecs.AuthProbeKind.CLI_MARKER ->
                        // 按 key 判：第二家 CLI_MARKER 驱动用自己的 marker，不许套 agy 的签名。
                        DriverAuthProbes.classify(key, g.exitCode, g.text).toAuth()
                    CliDriverSpecs.AuthProbeKind.FILE, CliDriverSpecs.AuthProbeKind.NONE ->
                        continue // 弱判据/NONE 没有 golden（证据强度映射是上层机制）
                }
                assertEquals(
                    g.verdict, actual,
                    "$key golden[exit=${g.exitCode}, text=${g.text.take(40)}] 判错了：期望 ${g.verdict} 实得 $actual",
                )
                samples++
            }
        }
        assertTrue(samples >= 15, "golden 样本数 $samples 太少，不足以当安全网（CLI_CMD×3 + CLI_MARKER 至少 15 条）")
    }

    /** argv 模板必须以 ${cliPath} 打头且只出现一次——发现器注入的绝对路径是首项。 */
    @Test
    fun cliAuthArgvStartsWithCliPathPlaceholder() {
        for (key in CliDriverSpecs.keys) {
            val spec = CliDriverSpecs.authProbeOf(key) ?: continue
            if (spec.kind != CliDriverSpecs.AuthProbeKind.CLI_CMD &&
                spec.kind != CliDriverSpecs.AuthProbeKind.CLI_MARKER
            ) continue
            assertEquals("\${cliPath}", spec.argv.firstOrNull(), "$key 的 auth argv 首项必须是 cliPath 占位符")
            assertEquals(1, spec.argv.count { it == "\${cliPath}" }, "$key 的 auth argv 里 cliPath 必须恰好一次")
        }
    }

    /** 每家都有 authProbe 声明，且形态与既有判定口径对得上。 */
    @Test
    fun everyDriverHasAnAuthProbeDeclaration() {
        assertEquals(
            CliDriverSpecs.keys,
            CliDriverSpecs.keys.filter { CliDriverSpecs.authProbeOf(it) != null }.toSet(),
            "每家内置驱动都必须有 authProbe 声明（含 NONE——显式声明「不判」也是声明）",
        )
        assertEquals(
            CliDriverSpecs.AuthProbeKind.CLI_CMD,
            CliDriverSpecs.authProbeOf("codex")?.kind,
        )
        assertEquals(
            CliDriverSpecs.AuthProbeKind.CLI_MARKER,
            CliDriverSpecs.authProbeOf(DriverKeys.AGY)?.kind,
        )
        assertEquals(
            CliDriverSpecs.AuthProbeKind.FILE,
            CliDriverSpecs.authProbeOf("dsh")?.kind,
        )
    }
}
