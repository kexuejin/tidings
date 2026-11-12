// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型选择「真的进了 argv」的行为判据。
 *
 * ## 为什么必须是行为判据
 *
 * 只断言 `withModel` 被调用过、或配置里存下了模型，都是假绿：本仓已经有过一次
 * 「`models` 存了没人读」。真正要证明的是**子进程收到的参数里有没有它**，
 * 所以这里用假 CLI 把 argv 落盘再比对。
 *
 * ## 位置为什么也要断言
 *
 * codex / opencode 的 prompt 是末尾位置参数。`--model x` 追加到 prompt 之后会被当成
 * prompt 的一部分 —— 不报错、也没有旗标，是最难查的一种。所以这两条额外钉「prompt 在最后」。
 */
class CliModelSelectionTest {

    private fun tempDir(): File =
        File.createTempFile("tenon-model", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    /** 把 argv 写进 `<dir>/argv.txt` 的假 CLI（退出码 0）。 */
    private fun capturingScript(dir: File): String {
        val f = File(dir, "cli")
        f.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"${dir.absolutePath}/argv.txt\"\nexit 0\n")
        f.setExecutable(true)
        return f.absolutePath
    }

    private fun argvOf(dir: File): List<String> =
        File(dir, "argv.txt").readLines().filter { it.isNotEmpty() }

    @Test
    fun supportedDriverPutsModelIntoTheChildArgv() = runBlocking {
        val dir = tempDir()
        val driver = CliSubprocessDriver.claude(capturingScript(dir)).withModel("claude-opus-4-1")
        val handle = driver.openSession("", null)
        driver.executeTask(handle.sessionId, "做点事") {}

        val argv = argvOf(dir)
        assertTrue(argv.contains("--model"), "argv 里没有模型旗标：${argv.joinToString(" ")}")
        assertTrue(argv.contains("claude-opus-4-1"), "模型 id 没进 argv：${argv.joinToString(" ")}")
        assertTrue(argv.contains("做点事"), "prompt 丢了：${argv.joinToString(" ")}")
    }

    @Test
    fun codexKeepsPromptLastAndModelBeforeIt() {
        // clap 的 trailing var-arg 会把 prompt 之后的 `--model x` 一起吞进 prompt。
        val argv = CliSubprocessDriver.codexArgs("do it", model = "gpt-5-codex")
        assertEquals("do it", argv.last(), "prompt 必须仍是最后一个位置参数：${argv.joinToString(" ")}")
        val flagAt = argv.indexOf("--model")
        assertTrue(flagAt > 0, "argv 里没有 --model：${argv.joinToString(" ")}")
        assertTrue(flagAt < argv.indexOf("do it"), "模型旗标跑到 prompt 后面了：${argv.joinToString(" ")}")
        assertTrue(argv.contains("gpt-5-codex"))
    }

    @Test
    fun opencodeKeepsPromptLastAndModelBeforeIt() {
        val argv = CliSubprocessDriver.opencodeArgs("do it", model = "deepseek/deepseek-chat")
        assertEquals("do it", argv.last(), "prompt 必须仍是最后一个位置参数：${argv.joinToString(" ")}")
        val flagAt = argv.indexOf("--model")
        assertTrue(flagAt > 0 && flagAt < argv.indexOf("do it"), "旗标位置不对：${argv.joinToString(" ")}")
    }

    @Test
    fun unsupportedDriverFailsLoudlyAndNeverSpawns() = runBlocking {
        val dir = tempDir()
        // dsh 的模型写在 profile 配置里，命令行没有旗标 —— 指定模型必须响亮失败。
        val driver = CliSubprocessDriver(
            "dsh-cli",
            "DeepSeek Harness (CLI)",
            capturingScript(dir),
            argSpec = { prompt -> listOf("--profile", "headless", "--json", prompt) },
        ).withModel("deepseek-chat")

        val events = mutableListOf<DriverEvent>()
        val handle = driver.openSession("", null)
        driver.executeTask(handle.sessionId, "做点事") { events.add(it) }

        val error = events.filterIsInstance<DriverEvent.Error>().singleOrNull()
        assertTrue(
            error?.message?.contains("cannot select a model per task") == true,
            "不支持模型的驱动必须给出可照着改的原因，实际事件：$events",
        )
        assertFalse(events.any { it is DriverEvent.Finished }, "不该为没发生的活报完成")
        assertFalse(File(dir, "argv.txt").exists(), "明明不支持却还是把进程拉起来了")
    }

    @Test
    fun sandboxedPlainBranchStillKnowsWhichDriverItIs() = runBlocking {
        // 裸路径委托实例的 id 是 `claude-cli-plain`，拿它查词表会查不到 ⇒ 合法的 claude 模型
        // 被判成「不支持」并响亮失败。这条钉住 modelKey 必须传基键。
        val dir = tempDir()
        val driver = SandboxedCliDriver(
            driverId = DriverKeys.cliDriverId("claude"),
            displayName = "Claude Code (CLI)",
            cliPath = capturingScript(dir),
            argSpec = { prompt, model ->
                listOf("-p", prompt) + DriverModelFlags.flagsFor("claude", model)
            },
            sandbox = WorktreeSandboxExecutor(sandboxRoot = tempDir()),
        ).withModel("claude-opus-4-1")

        val events = mutableListOf<DriverEvent>()
        val handle = driver.openSession("", null) // 空工作区 ⇒ 走裸路径委托
        driver.executeTask(handle.sessionId, "做点事") { events.add(it) }

        assertFalse(
            events.any { it is DriverEvent.Error },
            "空白工作区把 supported 驱动误判成不支持了：$events",
        )
        assertTrue(File(dir, "argv.txt").exists(), "根本没跑起来：$events")
        assertTrue(
            File(dir, "argv.txt").readText().contains("claude-opus-4-1"),
            "模型没进 argv：${File(dir, "argv.txt").readText()}",
        )
    }
}
