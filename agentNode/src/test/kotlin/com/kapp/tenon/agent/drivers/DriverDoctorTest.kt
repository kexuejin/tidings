// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 驱动自检面的行为守卫。
 *
 * 这里验的不是「渲染好不好看」，而是三件会决定 doctor 有没有用的判据：
 *
 * 1. **argv 必须取自装配实例** —— 直调 `codexArgs()` 的老毛病（测了助手函数、没测装配）
 *    不能在诊断面上重演；
 * 2. **判红必须真会红** —— 把模型退回「末尾追加」，doctor 必须当场报 ERROR，而不是印一行
 *    看起来正常的 argv 让人自己比对；
 * 3. **报告不许成为泄露面** —— 凭证**值**绝不出现，只出现键名。
 */
class DriverDoctorTest {

    private val fakeCli = "/nonexistent/doctor-fixture"

    /** 全部 resolver 都命中一个不存在的假路径：本用例不执行任何 CLI（`envDependentTestGate` 口径）。 */
    private fun allResolved(): Map<String, String?> = CliDriverSpecs.keys.associateWith { fakeCli }

    private class FakeProbe(
        private val paths: Map<String, String?> = emptyMap(),
        private val envMap: Map<String, String> = emptyMap(),
        private val homeDir: File = File("/nonexistent-doctor-home"),
    ) : DriverDoctor.Probe {
        override fun resolveCli(key: String): String? = paths[key]
        override fun env(): Map<String, String> = envMap
        override fun home(): File = homeDir
        override suspend fun versionOf(key: String, cliPath: String): String? = "1.2.3"
    }

    private fun productionDrivers(): List<AgentDriver> = cliDriverRegistrations(
        resolveClaude = { fakeCli },
        resolveCodex = { fakeCli },
        resolveAgy = { fakeCli },
        resolveOpencode = { fakeCli },
        resolveDsh = { fakeCli },
        resolveCodebuddy = { fakeCli },
        resolveZcode = { fakeCli },
        hint = { },
        env = emptyMap(),
        sandbox = null,
    )

    @Test
    fun reportCoversEveryDriverInTheIdentityTable() {
        val report = kotlinx.coroutines.runBlocking {
            DriverDoctor.diagnose(productionDrivers(), FakeProbe(allResolved()), sandboxed = false)
        }
        assertEquals(
            CliDriverSpecs.keys,
            report.rows.map { it.key }.toSet(),
            "报告行集合必须等于身份卡键集 —— 少一行就是一家驱动在诊断面上不存在",
        )
        assertTrue(report.rows.all { it.assembled }, "七家都已装配，报告里不该有 MISSING")
    }

    /** 生产装配（当前是正确形态）⇒ 不该有「模型没进 argv」这类 ERROR。 */
    @Test
    fun productionAssemblyPassesTheModelChecks() {
        val report = kotlinx.coroutines.runBlocking {
            DriverDoctor.diagnose(productionDrivers(), FakeProbe(allResolved()), sandboxed = false)
        }
        val errors = report.findings.filter { it.severity == DriverDoctor.Severity.ERROR }
        assertTrue(errors.isEmpty(), "生产装配不该有 ERROR，实际：" + errors.map { it.message })

        val codex = report.rows.first { it.key == "codex" }
        assertTrue(codex.argv.isNotEmpty(), "argv 必须取自装配实例，空 = 又回到「没测装配」")
        val modelAt = codex.argv.indexOf(DriverDoctor.PROBE_MODEL)
        assertTrue(modelAt > 0, "codex 的 argv 里必须出现模型：argv=" + codex.argv)
        assertTrue(
            modelAt < codex.argv.indexOf(DriverDoctor.PROBE_PROMPT),
            "codex 的 --model 必须在 prompt 之前：argv=" + codex.argv,
        )
    }

    /**
     * 变异探针：把 codex 退回「末尾追加」。
     *
     * 这正是被修掉的那个缺陷形态。doctor 必须报 ERROR 并点名「会被当成 prompt 的一部分」，
     * 否则它就只是另一张让人自己看 argv 的表。
     */
    @Test
    fun swallowedModelIsReportedAsAnError() {
        val broken = CliSubprocessDriver(
            driverId = DriverKeys.cliDriverId("codex"),
            displayName = "Codex",
            cliPath = fakeCli,
            // 旗标追加在 prompt 之后 —— prompt 是末尾位置参数 ⇒ 被吞掉。
            argSpec = { prompt, model -> listOf("exec", prompt) + DriverModelFlags.flagsFor("codex", model) },
        )
        val report = kotlinx.coroutines.runBlocking {
            DriverDoctor.diagnose(listOf(broken), FakeProbe(allResolved()), sandboxed = false)
        }
        val codex = report.rows.first { it.key == "codex" }
        assertTrue(codex.assembled, "手工传入的实例必须被认成已装配")
        val messages = codex.findings.filter { it.severity == DriverDoctor.Severity.ERROR }.map { it.message }
        assertTrue(
            messages.any { it.contains("swallow", ignoreCase = true) },
            "模型被追加到 prompt 之后必须判红并点明原因，实际：" + messages,
        )
    }

    /** 模型根本没拼进 argv（agy 那处缺陷的形态）⇒ 必须报「配了却没生效」。 */
    @Test
    fun missingModelIsReportedAsAnError() {
        val broken = CliSubprocessDriver(
            driverId = DriverKeys.cliDriverId("agy"),
            displayName = "Antigravity",
            cliPath = fakeCli,
            argSpec = { prompt, _ -> listOf("-p", prompt, "--output-format", "stream-json") },
        )
        val report = kotlinx.coroutines.runBlocking {
            DriverDoctor.diagnose(listOf(broken), FakeProbe(allResolved()), sandboxed = false)
        }
        val agy = report.rows.first { it.key == "agy" }
        assertTrue(
            agy.findings.any { it.severity == DriverDoctor.Severity.ERROR && it.message.contains("no model") },
            "模型声明支持却没进 argv 必须判红，实际：" + agy.findings.map { it.message },
        )
    }

    /** 「发现得到却没装配」是静默不可用的一类，必须是 ERROR 而不是一行没人注意的 MISSING。 */
    @Test
    fun discoveredButNotAssembledIsAnError() {
        val report = kotlinx.coroutines.runBlocking {
            DriverDoctor.diagnose(emptyList(), FakeProbe(allResolved()), sandboxed = false)
        }
        assertTrue(report.rows.none { it.assembled })
        val errors = report.findings.filter { it.severity == DriverDoctor.Severity.ERROR }
        assertEquals(
            CliDriverSpecs.keys.size,
            errors.size,
            "七家全部「找得到但没装配」都该判红，实际：" + errors.map { it.message },
        )
        assertTrue(errors.all { it.message.contains("no driver instance") })
    }

    /** 凭证**值**不许进报告；键名要进（否则运维看不出「声明了 10 只、宿主给了 1 只」）。 */
    @Test
    fun reportCarriesCredentialKeyNamesButNeverTheirValues() {
        val secret = "sk-doctor-must-not-print-me"
        val probe = FakeProbe(
            paths = allResolved(),
            envMap = mapOf("DEEPSEEK_API_KEY" to secret, "OPENCODE_API_KEY" to secret),
        )
        val report = kotlinx.coroutines.runBlocking {
            DriverDoctor.diagnose(productionDrivers(), probe, sandboxed = false)
        }
        val dsh = report.rows.first { it.key == "dsh" }
        assertTrue(
            dsh.envPresent.contains("DEEPSEEK_API_KEY"),
            "键名要出现在报告里（值不出现），实际：" + dsh.envPresent,
        )
        assertTrue(dsh.envDeclared > dsh.envPresent.size, "声明的键数应当多于宿主实际给的键数")
        assertFalse(DriverDoctor.renderJson(report).contains(secret), "JSON 报告泄露了凭证值")
        assertFalse(DriverDoctor.renderText(report).contains(secret), "文本报告泄露了凭证值")
    }

    /** sandbox HOME 白名单只报「在不在」，缺失项要能被看见（缺一项 = 对应驱动在沙箱里必死认证）。 */
    @Test
    fun sandboxHomeWhitelistReportsPresenceOnly() {
        val home = Files.createTempDirectory("doctor-home").toFile()
        File(home, ".claude").mkdirs()
        val report = kotlinx.coroutines.runBlocking {
            DriverDoctor.diagnose(productionDrivers(), FakeProbe(allResolved(), homeDir = home), sandboxed = true)
        }
        val links = report.homeLinks.associate { it.name to it.present }
        assertEquals(CliDriverSpecs.keys.size > 0, links.isNotEmpty())
        assertTrue(links[".claude"] == true, "建过的目录必须判在")
        assertTrue(links[".codex"] == false, "没建的目录必须判不在，不许当成在")
        assertTrue(
            report.findings.any { it.severity == DriverDoctor.Severity.WARN && it.message.contains(".codex") },
            "沙箱分支下缺失的 HOME 条目要给 WARN（否则只在真派单时才炸）",
        )
    }

    /** JSON 是给别的产品消费的契约：schemaVersion 与分支必须可读。 */
    @Test
    fun jsonCarriesSchemaVersionAndBranch() {
        val report = kotlinx.coroutines.runBlocking {
            DriverDoctor.diagnose(productionDrivers(), FakeProbe(allResolved()), sandboxed = true)
        }
        val json = DriverDoctor.renderJson(report)
        assertContains(json, "\"schemaVersion\": " + DriverDoctor.SCHEMA_VERSION)
        assertContains(json, "\"sandboxed\": true")
        assertContains(json, "\"key\": \"codex\"")
        // 字段缺席 ≠ 字段为 null：消费方要能区分「这家没有续跑能力」与「这个版本没这字段」。
        assertContains(json, "\"driverKey\": null", message = "全局结论必须显式写出 driverKey=null，不能省掉字段")
        assertContains(json, "\"argvResume\": null", message = "无续跑能力的驱动必须显式给出 null")
    }

    /**
     * 环境继承模式必须出现在报告里 —— 它是安全语义，不许只活在注释里。
     *
     * 唯一来源是 [CliChildEnv.MODE_INHERIT]：谁把继承改成 `clear()` 就得同时改那里，
     * 于是这一列会立刻跟着变，而不是等真机派单失败才发现「子进程看不到 ssh-agent」。
     */
    @Test
    fun reportExposesTheEnvironmentMode() {
        val report = kotlinx.coroutines.runBlocking {
            DriverDoctor.diagnose(productionDrivers(), FakeProbe(allResolved()), sandboxed = false)
        }
        assertEquals(CliChildEnv.MODE_INHERIT, report.envMode)
        assertContains(DriverDoctor.renderText(report), "env=" + CliChildEnv.MODE_INHERIT)
        assertContains(DriverDoctor.renderJson(report), "\"envMode\": \"" + CliChildEnv.MODE_INHERIT + "\"")
    }
}
