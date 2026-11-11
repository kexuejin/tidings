// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * dsh（DeepSeek Harness CLI）接成宿主驱动的四件事，与 [OpenCodeCliWiringTest] 同一口径：
 *
 * 1. **发现**：`dsh` 在 PATH（wrapper 指向的 node 必须活着，否则「发现得到、跑不起来」）；
 * 2. **凭证**：headless 路由只认 env 的 `DEEPSEEK_API_KEY`（实测不读 ~/.dsh 凭证服务），
 *    宿主要从 `~/.dsh/.credentials.yaml` 解析后递进子进程 —— 值只进 env，不进任何文本；
 * 3. **装配**：`--profile headless --json <prompt>`（一次性 + NDJSON 行协议）；
 * 4. **行语义**：text/final → TokenDelta、tool_call → ToolCall、
 *    status(turn_end, reason.kind=error) → Error（错误正文只在这行里）。
 *
 * 全部注入式（假二进制 / 合成 env / 临时凭证文件），不依赖本机装没装。
 */
class DshCliWiringTest {

    private fun tempDir(): File = Files.createTempDirectory("dsh-wiring").toFile()

    private fun fakeBinary(dir: File, name: String = "dsh"): String {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\nexit 0\n")
        assertTrue(f.setExecutable(true), "假二进制必须可执行")
        return f.absolutePath
    }

    @Test
    fun discoveryResolvesDshFromPath() {
        val dir = tempDir()
        val path = fakeBinary(dir)
        assertEquals(
            path,
            CliDiscovery.resolveDshCli(env = mapOf("HOME" to tempDir().absolutePath), pathDirs = listOf(dir.absolutePath)),
            "PATH 里的 dsh 必须被探测到",
        )
        assertNull(
            CliDiscovery.resolveDshCli(env = mapOf("HOME" to tempDir().absolutePath), pathDirs = listOf(tempDir().absolutePath)),
            "找不到就如实 null",
        )
    }

    @Test
    fun credentialsResolveFromEnvFirstThenTheDshFile() {
        // env 优先：显式给的赢，不读文件。
        assertEquals("env-key", DshCredentials.resolve(mapOf("DEEPSEEK_API_KEY" to "env-key")))
        // 文件兜底：行首锚定的 `DEEPSEEK_API_KEY: value`。
        val home = tempDir()
        val dshDir = File(home, ".dsh").apply { mkdirs() }
        File(dshDir, ".credentials.yaml").writeText(
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: file-key\n  OTHER_KEY: ignored\n",
        )
        assertEquals(
            "file-key",
            DshCredentials.resolve(mapOf("HOME" to home.absolutePath)),
            "宿主 env 没有时从 ~/.dsh/.credentials.yaml 解析",
        )
        assertNull(DshCredentials.resolve(mapOf("HOME" to tempDir().absolutePath)), "没有凭证就如实 null，不编空串")
        assertNull(
            DshCredentials.resolve(
                mapOf("HOME" to File(home, "nope").absolutePath),
            ),
            "HOME 无效不炸",
        )
    }

    @Test
    fun registrationWiresHeadlessArgsAndTheResolvedKey() {
        val cli = fakeBinary(tempDir())
        val home = tempDir()
        val dshDir = File(home, ".dsh").apply { mkdirs() }
        File(dshDir, ".credentials.yaml").writeText("refs:\n  DEEPSEEK_API_KEY: file-key\n")
        val hints = mutableListOf<String>()
        val drivers = cliDriverRegistrations(
            resolveClaude = { null },
            resolveCodex = { null },
            resolveAgy = { null },
            resolveOpencode = { null },
            resolveDsh = { cli },
            hint = { hints.add(it) },
            env = mapOf("HOME" to home.absolutePath),
        )
        val driver = drivers.singleOrNull { it.driverId == "dsh-cli" }
        assertNotNull(driver, "dsh 可执行文件在就注册 dsh-cli，实际: " + drivers.map { it.driverId })
        assertEquals(
            listOf("--profile", "headless", "--json", "do it"),
            CliSubprocessDriver.dshArgs("do it"),
        )
        assertTrue(
            hints.none { it.contains("dsh CLI not found") },
            "找到了就不该说不存在: $hints",
        )
        assertTrue(
            hints.none { it.contains("no DEEPSEEK_API_KEY") },
            "凭证文件有 key 就不该提示缺凭证: $hints",
        )
    }

    @Test
    fun lineMappingKeepsTextToolsAndSurfacesTurnErrors() {
        // 顶层 text / final → TokenDelta（现有 textOf 路径就吃）。
        assertEquals(
            DriverEvent.TokenDelta("Created probe.txt"),
            CliLineEventMapper.map("""{"type":"text","text":"Created probe.txt"}"""),
        )
        assertEquals(
            DriverEvent.TokenDelta("done"),
            CliLineEventMapper.map("""{"type":"final","text":"done"}"""),
        )
        // tool_call → ToolCall（callId/tool 是顶层键）。
        val call = CliLineEventMapper.map(
            """{"type":"tool_call","callId":"call_1","tool":"write","input":{"file_path":"/tmp/x"}}""",
        ) as? DriverEvent.ToolCall
        assertNotNull(call, "tool_call 要成 ToolCall")
        assertEquals("write", call.tool)
        assertEquals("call_1", call.toolCallId)
        // 空 thinking 行不产噪声事件。
        assertNull(CliLineEventMapper.map("""{"type":"thinking","text":""}"""), "空文本不许发空 TokenDelta")
        // 非空 thinking 是**内心独白**，不是发言：必须成 ThoughtDelta。
        // 真机现场：兜底路径把它当 TokenDelta，卡面横幅就变成了「The user's message is
        // just: git log -1 --oneline…」——模型的自言自语顶替了那一行 commit。
        assertEquals(
            DriverEvent.ThoughtDelta("The task is to run that command"),
            CliLineEventMapper.map("""{"type":"thinking","text":"The task is to run that command"}"""),
            "thinking 不许被当成它说的话（兜底分支吃掉的正是这一行）",
        )
        // turn_end 的 error reason 要挖出正文 —— 退出码之外唯一的失败原因来源。
        // 原始 CLI 的 message 里带的是真引号（JSON 里转义为 \"），解析后应还原。
        val errLine = """{"type":"status","phase":"turn_end","turn":1,"reason":{"kind":"error","error":{"message":"llm-deepseek: no API key for provider route \"deepseek-official\""}}}"""
        assertEquals(
            DriverEvent.Error("llm-deepseek: no API key for provider route \"deepseek-official\""),
            CliLineEventMapper.map(errLine),
        )
        assertNull(
            CliLineEventMapper.map("""{"type":"status","phase":"turn_end","reason":{"kind":"completed"}}"""),
            "正常收尾不产事件",
        )
    }
}
