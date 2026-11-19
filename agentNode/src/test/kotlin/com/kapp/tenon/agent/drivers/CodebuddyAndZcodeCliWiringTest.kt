// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CodeBuddy / ZCode 接成宿主驱动的装配闭环，与 [DshCliWiringTest] 同一口径：
 *
 * 1. **发现**：codebuddy 走 PATH；zcode 既可能是 PATH 上的 bin，也可能是桌面应用包里的 cjs；
 * 2. **启动形态**：cjs 交给 node，参数前缀插在 prompt 之前（下行 argSpec 不分叉）；
 * 3. **参数**：codebuddy 是 claude 形态（-p + stream-json + -y），zcode 是 -p/--json/--mode yolo；
 * 4. **行语义**：codebuddy 的 result 错误行不能被退出码 0 吞掉；zcode 的一次性信封要出正文。
 *
 * 全部注入式（假二进制 / 合成 env / 临时目录），不依赖本机装没装、登没登录。
 */
class CodebuddyAndZcodeCliWiringTest {

    private fun tempDir(): File = Files.createTempDirectory("new-cli-wiring").toFile()

    /** 假 CLI：把收到的 argv 逐行写进文件，再打一行可映射的输出，退出码 0。 */
    private fun recordingCli(dir: File, name: String, argsFile: File, outputLine: String): String {
        val file = File(dir, name)
        file.writeText(
            "#!/bin/sh\n" +
                "printf '%s\\n' \"\$@\" > '" + argsFile.absolutePath + "'\n" +
                "printf '%s\\n' '" + outputLine + "'\n" +
                "exit 0\n",
        )
        assertTrue(file.setExecutable(true), "假二进制必须可执行")
        return file.absolutePath
    }

    @Test
    fun discoveryCoversBinAndAppBundledRuntime() {
        val dir = tempDir()
        val bin = recordingCli(dir, "codebuddy", File(dir, "a.txt"), "{}")
        assertEquals(
            bin,
            CliDiscovery.resolveCodebuddyCli(
                env = mapOf("HOME" to tempDir().absolutePath),
                pathDirs = listOf(dir.absolutePath),
            ),
        )
        assertEquals(bin to emptyList<String>(), CliDiscovery.launchOf(bin))

        val home = tempDir()
        val cjs = File(home, "Applications/" + CliDiscovery.ZCODE_CJS_RELATIVE).apply {
            parentFile.mkdirs()
            writeText("// fake runtime\n")
        }
        assertEquals(
            cjs.absolutePath,
            CliDiscovery.resolveZcodeCli(env = mapOf("HOME" to home.absolutePath), pathDirs = emptyList()),
            "应用包里的官方运行时必须被发现（PATH 上没有 zcode 命令的常态）",
        )
        assertEquals(
            cjs.absolutePath,
            CliDiscovery.resolveZcodeCli(env = mapOf(CliDiscovery.ENV_ZCODE_CLI to cjs.absolutePath), pathDirs = emptyList()),
            "显式 env 可以指向不可执行的 cjs",
        )
        val (launcher, prefix) = CliDiscovery.launchOf(cjs.absolutePath)
        assertEquals("node", launcher)
        assertEquals(listOf(cjs.absolutePath), prefix, "cjs 路径必须作为首个参数交给 node")
    }

    @Test
    fun registrationWiresHeadlessArgsAndHonestEvents() = runBlocking {
        val dir = tempDir()
        val argsFile = File(dir, "argv.txt")
        val codebuddy = recordingCli(
            dir, "codebuddy", argsFile,
            """{"type":"result","subtype":"success","is_error":false}""",
        )
        val zcode = recordingCli(
            dir, "zcode", argsFile,
            """{"sessionId":"sess_1","text":"OK","stopReason":"end_turn"}""",
        )
        val drivers = cliDriverRegistrations(
            resolveClaude = { null },
            resolveCodex = { null },
            resolveAgy = { null },
            resolveOpencode = { null },
            resolveDsh = { null },
            resolveCodebuddy = { codebuddy },
            resolveZcode = { zcode },
            hint = {},
            env = emptyMap(),
        )
        assertEquals(listOf("codebuddy-cli", "zcode-cli"), drivers.map { it.driverId })

        val cb = drivers.first()
        val cbEvents = mutableListOf<DriverEvent>()
        cb.executeTask(cb.openSession(dir.absolutePath, null).sessionId, "do it") { cbEvents += it }
        assertEquals(listOf<DriverEvent>(DriverEvent.Finished()), cbEvents, "成功的 result 行不产事件，完成由退出码裁决")
        assertEquals(
            listOf("-p", "do it", "--output-format", "stream-json", "--verbose"),
            argsFile.readLines(),
            "裸路径不加 -y：工作区是机主自己的仓，不能静默放开",
        )

        val zc = drivers.last()
        val zcEvents = mutableListOf<DriverEvent>()
        zc.executeTask(zc.openSession(dir.absolutePath, null).sessionId, "do it") { zcEvents += it }
        assertEquals(listOf<DriverEvent>(DriverEvent.TokenDelta("OK"), DriverEvent.Finished()), zcEvents)
        assertEquals(
            listOf("-p", "do it", "--mode", "yolo", "--json", "--no-color"),
            argsFile.readLines(),
        )

        // 沙箱分支才给 -y（写操作只落一次性 worktree，守门点是合流审查）。
        assertEquals(
            true,
            CliSubprocessDriver.codebuddyArgs("do it", "fast-model", true)
                .containsAll(listOf("-y", "--model", "fast-model")),
        )
    }

    @Test
    fun resultErrorsAndZcodeEnvelopesAreMappedHonestly() {
        val errorLine = CliLineEventMapper.map(
            """{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["Authentication required"]}""",
        )
        assertEquals(DriverEvent.Error("Authentication required"), errorLine, "退出码 0 也不能把失败说成成功")
        assertEquals(
            null,
            CliLineEventMapper.map("""{"type":"result","subtype":"success","is_error":false}"""),
        )
        assertEquals(
            DriverEvent.TokenDelta("done"),
            CliLineEventMapper.map("""{"type":"assistant","message":{"content":[{"type":"text","text":"done"}]}}"""),
            "claude/codebuddy 同源的 assistant 信封沿用现有映射",
        )
        assertEquals(
            DriverEvent.TokenDelta("partial progress"),
            CliLineEventMapper.map("""{"sessionId":"sess_1","text":"partial progress","stopReason":"max_turns"}"""),
            "护栏停止仍是真实进展，不报成失败",
        )
        assertEquals(
            DriverEvent.Error("no api key"),
            CliLineEventMapper.map("""{"sessionId":"sess_1","stopReason":"error","error":{"message":"no api key"}}"""),
        )
        assertEquals(
            DriverEvent.TokenDelta("OK"),
            CliLineEventMapper.map("""{"response":"OK"}"""),
            "无 type 的单次信封也要出正文——zcode 机读输出的键名未逐字核实，不能只认 text",
        )
        assertNotNull(
            CliSubprocessDriver.codebuddyResume(autoApprove = false)
                .sessionIdFromLine("""{"type":"system","session_id":"01a1-e1fe"}"""),
            "重试跳必须能从首行拿到会话 id",
        )
        assertEquals(
            listOf("--resume", "01a1-e1fe", "-p", "again", "--output-format", "stream-json", "--verbose"),
            CliSubprocessDriver.codebuddyResume(autoApprove = false).args("01a1-e1fe", "again", null),
            "裸路径的续跑跳不许带 -y：首跳没放开权限，重试跳放开等于一次失败就把权限面放宽",
        )
        assertEquals(
            listOf("--resume", "01a1-e1fe", "-p", "again", "--output-format", "stream-json", "--verbose", "-y", "--model", "m-1"),
            CliSubprocessDriver.codebuddyResume(autoApprove = true).args("01a1-e1fe", "again", "m-1"),
            "沙箱分支的续跑跳必须同时保住 -y 与本次任务的模型（重试跳不许丢模型）",
        )
    }

    @Test
    fun profileModelFlagCredentialAndSandboxHomeStayAligned() {
        assertEquals("tencent", AgentProfiles.vendorOf("codebuddy"))
        assertEquals("zhipu", AgentProfiles.vendorOf("zcode"))
        assertTrue(AgentProfiles.knownKeys().containsAll(setOf("codebuddy", "zcode")), "否则驱动注册了也不进驱动下拉")
        assertTrue(DriverModelFlags.supports("codebuddy"), "本机 --help 有 --model")
        assertFalse(DriverModelFlags.supports("zcode"), "本机 --help 没有按次选模型，不许静默忽略")
        assertTrue(
            CliProviderEnv.forCodebuddy(
                mapOf(
                    "CODEBUDDY_API_KEY" to "sk-probe",
                    "CODEBUDDY_INTERNET_ENVIRONMENT" to "internal",
                    "SECRET_NOT_ALLOWLISTED" to "must-not-reach-child",
                ),
            ).let { it["CODEBUDDY_API_KEY"] == "sk-probe" && it["CODEBUDDY_INTERNET_ENVIRONMENT"] == "internal" && "SECRET_NOT_ALLOWLISTED" !in it },
        )
        assertTrue(
            WorktreeSandboxExecutor.SANDBOX_CREDENTIAL_ENTRIES.containsAll(listOf(".codebuddy", ".zcode")),
            "沙箱换了 HOME，没有登录态挂载就会变成认证失败",
        )
    }
}
