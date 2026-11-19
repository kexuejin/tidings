// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 生产装配的 argv 守卫：**每个驱动在 `withModel` 之后，模型真的进了它要执行的命令行**。
 *
 * ## 为什么要有它
 *
 * `CliModelSelectionTest` 只测助手函数（直接调 `codexArgs(prompt, model = x)`），而那一直是
 * 对的 —— 错的是生产装配根本没把模型传给助手函数。于是「用户配了模型、CLI 跑默认模型、
 * 退出码 0、事件流正常」全链路无一处报错，测试全绿。
 *
 * 本守卫因此**不从助手函数取值**，而是：
 * 1. 调真正的装配入口 [cliDriverRegistrations]（沙箱 / 裸两条分支各跑一遍）；
 * 2. 对装配出来的实例调 [AgentDriver.withModel]；
 * 3. 从 [CliSubprocessDriver.attemptArgv] / [SandboxedCliDriver.attemptArgv] 取 argv ——
 *    这正是 `executeTask` 喂给 `ProcessBuilder` 的那个列表。
 *
 * 判据按该 CLI 的参数形态分开，不是「argv 里出现模型字符串」就算过：
 * - codex / opencode 的 prompt 是**末尾位置参数**，模型必须在它前面；
 * - dsh 没有 `--model`，模型写进 `--patch` 指向的文件（表通道，不是旗标通道）；
 * - zcode 不支持按次选模型，**不许**出现模型（出现才是缺陷）。
 *
 * ## 反向验证（改完必须做一次）
 *
 * 把任意一处装配退回「单参数 lambda」（`argSpec = { prompt -> codexArgs(prompt) }`），
 * 本用例必须红在「模型没进 argv」。实测过，见提交说明。
 */
class ProductionDriverArgvGuardTest {

    private val model = "probe-model-x"
    private val prompt = "probe-prompt-7f3a"

    /**
     * 装配清单。resolver 全部返回同一个不存在的假路径 —— 本用例**不执行**任何 CLI，
     * 只看装配出来的 argv，所以与「这台机器装没装」无关（`envDependentTestGate` 的口径）。
     */
    private fun registrations(sandboxed: Boolean): List<AgentDriver> {
        val root = Files.createTempDirectory("argv-guard").toFile()
        return cliDriverRegistrations(
            resolveClaude = { FAKE_CLI },
            resolveCodex = { FAKE_CLI },
            resolveAgy = { FAKE_CLI },
            resolveOpencode = { FAKE_CLI },
            resolveDsh = { FAKE_CLI },
            resolveCodebuddy = { FAKE_CLI },
            resolveZcode = { FAKE_CLI },
            hint = { },
            env = emptyMap(),
            sandbox = if (sandboxed) WorktreeSandboxExecutor(sandboxRoot = root) else null,
        )
    }

    private fun argvOf(driver: AgentDriver): List<String> = when (driver) {
        is CliSubprocessDriver -> driver.attemptArgv(prompt, null)
        is SandboxedCliDriver -> driver.attemptArgv(prompt, null)
        else -> error("未预期的驱动实现：" + driver::class.simpleName)
    }

    @Test
    fun everyDriverCarriesItsPerTaskModelIntoTheArgvItWillExecute() {
        listOf(false, true).forEach { sandboxed ->
            val branch = if (sandboxed) "沙箱分支" else "裸路径"
            val drivers = registrations(sandboxed)
            assertEquals(
                7,
                drivers.size,
                "$branch 必须装配出七家 —— 少一家说明 resolver 注入没生效，本用例就成了空转",
            )

            for (driver in drivers) {
                val key = DriverKeys.stripCliSuffix(driver.driverId)
                val argv = argvOf(driver.withModel(model))

                assertEquals(
                    1,
                    argv.count { it == prompt },
                    "$branch/$key：prompt 必须恰好出现一次（多一次 = 被当成旗标值或重复发送）；argv=$argv",
                )

                if (!DriverModelFlags.supports(key)) {
                    assertFalse(
                        argv.contains(model),
                        "$branch/$key：该驱动不支持按次选模型，argv 里出现模型说明存在假装支持的旁路；argv=$argv",
                    )
                    continue
                }

                if (key == "dsh") {
                    // dsh 没有 --model：模型由 --patch 指向的文件承载（整块替换语义）。
                    val patchAt = argv.indexOf("--patch")
                    assertTrue(
                        patchAt >= 0 && patchAt + 1 < argv.size,
                        "$branch/dsh：配了模型却没带 --patch，dsh 会静默跑默认模型；argv=$argv",
                    )
                    val patch = File(argv[patchAt + 1])
                    assertTrue(patch.isFile, "$branch/dsh：--patch 指向的文件不存在：" + patch.absolutePath)
                    assertTrue(
                        patch.readText().contains(model),
                        "$branch/dsh：patch 内容里没有本次任务的模型（整块替换会丢 provider，也丢模型）；" +
                            "内容=" + patch.readText(),
                    )
                    continue
                }

                val at = argv.indexOf(model)
                assertTrue(
                    at >= 0,
                    "$branch/$key：配了模型但 argv 里没有它 —— 用户会看到「配了却没生效」；argv=$argv",
                )
                assertEquals(
                    "--model",
                    argv.getOrNull(at - 1),
                    "$branch/$key：模型必须紧跟 --model 作为它的值，否则会被当成别的东西；argv=$argv",
                )

                if (key == "codex" || key == "opencode") {
                    // 这两家的 prompt 是末尾位置参数：模型旗标落在 prompt 之后会被算进 prompt。
                    assertTrue(
                        at < argv.indexOf(prompt),
                        "$branch/$key：--model 落在 prompt **之后** —— 会被当作 prompt 的一部分吞掉，" +
                            "CLI 不报错、跑默认模型；argv=$argv",
                    )
                }
            }
        }
    }

    /**
     * 重试跳同样不许丢模型：续跑参数走的是另一条拼装路径，首跳对了不代表续跳也对。
     *
     * dsh / codebuddy 有续跑能力（前者 `--session-id`，后者 `--resume`），其余驱动
     * `attemptArgv` 会回落到首跳参数 —— 所以这里只断言「有续跑能力的两家」。
     */
    @Test
    fun resumeAttemptsKeepTheModelToo() {
        listOf(false, true).forEach { sandboxed ->
            val branch = if (sandboxed) "沙箱分支" else "裸路径"
            registrations(sandboxed)
                .filter { DriverKeys.stripCliSuffix(it.driverId) in setOf("dsh", "codebuddy") }
                .forEach { driver ->
                    val key = DriverKeys.stripCliSuffix(driver.driverId)
                    val withModel = driver.withModel(model)
                    val argv = when (withModel) {
                        is CliSubprocessDriver -> withModel.attemptArgv(prompt, "resumed-session-1")
                        is SandboxedCliDriver -> withModel.attemptArgv(prompt, "resumed-session-1")
                        else -> error("未预期的驱动实现：" + withModel::class.simpleName)
                    }
                    assertTrue(
                        argv.any { it == "resumed-session-1" },
                        "$branch/$key：续跑跳没带上 CLI 侧会话 id，等于从零重跑；argv=$argv",
                    )
                    val carried = if (key == "dsh") {
                        argv.indexOf("--patch").let { at ->
                            at >= 0 && at + 1 < argv.size && File(argv[at + 1]).readText().contains(model)
                        }
                    } else {
                        argv.contains(model)
                    }
                    assertTrue(
                        carried,
                        "$branch/$key：续跑跳丢了本次任务的模型 —— 首跳失败、次跳成功，" +
                            "用户只看到「重试成功」，模型已悄悄回到默认；argv=$argv",
                    )
                }
        }
    }

    private companion object {
        /** 不存在的路径：本用例只看 argv，不执行；用真实路径反而会引入「本机装了什么」的依赖。 */
        const val FAKE_CLI = "/nonexistent/tenon-argv-guard"
    }
}
