// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import com.kapp.tenon.testsupport.GuardSourceScan
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CliChildEnv] 的装配判据（纯函数，合成输入，不读本机环境）。
 *
 * 这些断言看起来琐碎，但每一条都对应一次真机事故：PWD 不改 ⇒ opencode 把产物写进宿主
 * 仓库根；HOME 先换 ⇒ 找不到 nvm 里的 node ⇒ 127；sessionId 被覆盖 ⇒ 只读否决静默失效。
 * 顺序错一步，症状都不在本层显现。
 */
class CliChildEnvTest {

    private fun tempDir(): String = Files.createTempDirectory("tenon-child-env").toFile().absolutePath

    @Test
    fun inheritedEnvironmentSurvives() {
        // 继承是刻意的（git/ssh、LANG、TMPDIR 都靠它）：这条钉住「不要把继承当成漏洞删掉」。
        val composed = CliChildEnv.compose(
            CliChildEnv.Spec(inherited = mapOf("SSH_AUTH_SOCK" to "/tmp/agent.sock", "LANG" to "zh_CN.UTF-8")),
        )
        assertEquals("/tmp/agent.sock", composed["SSH_AUTH_SOCK"])
        assertEquals("zh_CN.UTF-8", composed["LANG"])
    }

    @Test
    fun pwdAndMcpWorkspaceFollowTheArguments() {
        val composed = CliChildEnv.compose(
            CliChildEnv.Spec(inherited = mapOf("PWD" to "/host/repo"), pwd = "/wt/t-1", mcpWorkspace = "/wt/t-1"),
        )
        assertEquals("/wt/t-1", composed["PWD"], "directory() 不改继承来的 PWD，必须显式覆盖")
        assertEquals("/wt/t-1", composed[CliChildEnv.ENV_MCP_WORKSPACE])
    }

    @Test
    fun barePathLeavesMcpWorkspaceAlone() {
        val composed = CliChildEnv.compose(CliChildEnv.Spec(inherited = emptyMap(), pwd = "/wt/t-1"))
        assertFalse(CliChildEnv.ENV_MCP_WORKSPACE in composed, "裸路径没有中介工具，不该出现工作区变量")
    }

    @Test
    fun explicitOverridesBeatPwdAndInherited() {
        val composed = CliChildEnv.compose(
            CliChildEnv.Spec(
                inherited = mapOf("PWD" to "/host", "ANTHROPIC_BASE_URL" to "https://host.example"),
                overrides = mapOf("PWD" to "/pinned", "ANTHROPIC_BASE_URL" to "https://gateway.example"),
                pwd = "/wt/t-1",
            ),
        )
        assertEquals("/pinned", composed["PWD"], "装配处的显式意图赢过 cwd 推导")
        assertEquals("https://gateway.example", composed["ANTHROPIC_BASE_URL"])
    }

    @Test
    fun pathIsFilledOnlyWhenNotInjected() {
        val filled = CliChildEnv.compose(CliChildEnv.Spec(inherited = mapOf("PATH" to "/usr/bin:/bin")))
        assertTrue(filled["PATH"]!!.contains("/usr/bin"), "补齐是在继承值上追加，不是替换")

        val injected = CliChildEnv.compose(
            CliChildEnv.Spec(inherited = mapOf("PATH" to "/usr/bin"), overrides = mapOf("PATH" to "/only/this")),
        )
        assertEquals("/only/this", injected["PATH"], "显式给了 PATH 就不许再补")
    }

    @Test
    fun pathIsResolvedAgainstTheHostHomeNotTheSandboxHome() {
        // 顺序钉子：childPath 会去 `$HOME/.nvm` 找 node。若先换 HOME 再补 PATH，沙箱里就
        // 找不到 node，脚本型 CLI 以 127 退出 —— 而 HOME 只链凭证目录，永远链不到 nvm。
        val hostHome = tempDir()
        Files.createDirectories(java.nio.file.Paths.get(hostHome, ".nvm/versions/node/v20.0.0/bin"))
        val sandboxHome = tempDir()

        val composed = CliChildEnv.compose(
            CliChildEnv.Spec(inherited = mapOf("HOME" to hostHome, "PATH" to "/usr/bin"), home = sandboxHome),
        )

        assertEquals(sandboxHome, composed["HOME"], "沙箱分支必须换 HOME")
        assertTrue(
            composed["PATH"]!!.contains("$hostHome/.nvm/versions/node/v20.0.0/bin"),
            "PATH 必须在换 HOME 之前补齐，实际：" + composed["PATH"],
        )
    }

    @Test
    fun explicitHomeWinsOverSandboxHome() {
        // agy 走 useRealHome：它自己给真 HOME，沙箱那个 .sandbox-home 必须让位。
        val composed = CliChildEnv.compose(
            CliChildEnv.Spec(
                inherited = emptyMap(),
                overrides = mapOf("HOME" to "/real/home"),
                home = "/wt/.sandbox-home",
            ),
        )
        assertEquals("/real/home", composed["HOME"])
    }

    @Test
    fun hookSessionIdIsWrittenLastAndCannotBeForged() {
        val composed = CliChildEnv.compose(
            CliChildEnv.Spec(
                inherited = mapOf(DriverKeys.ENV_HOOK_SESSION_ID to "inherited"),
                overrides = mapOf(DriverKeys.ENV_HOOK_SESSION_ID to "from-config"),
                hookSessionId = "dispatch-session",
            ),
        )
        assertEquals(
            "dispatch-session",
            composed[DriverKeys.ENV_HOOK_SESSION_ID],
            "派单 sessionId 是安全判据，配置层同名变量不许顶掉它",
        )
    }

    @Test
    fun applyToWritesTheComposedSnapshot() {
        val pb = ProcessBuilder("/usr/bin/true")
        val snapshot = CliChildEnv.applyTo(
            pb,
            CliChildEnv.Spec(
                inherited = pb.environment(),
                overrides = mapOf("TENON_TEST_KEY" to "v"),
                pwd = "/wt/t-9",
                hookSessionId = "s-1",
            ),
        )
        assertEquals(snapshot["TENON_TEST_KEY"], pb.environment()["TENON_TEST_KEY"])
        assertEquals("/wt/t-9", pb.environment()["PWD"])
        assertEquals("s-1", pb.environment()[DriverKeys.ENV_HOOK_SESSION_ID])
    }

    @Test
    fun productionLaunchSitesGoThroughThisOnePlace() {
        // 反向钉子：两处拉起子进程的地方都必须走 applyTo，且都不许自己 clear() 环境。
        // 前者删掉 = 顺序又回到各写一遍；后者出现 = 继承语义被静默改成白名单。
        val sites = listOf(
            "agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/CliSubprocessDriver.kt",
            "agentNode/src/main/kotlin/com/kapp/tenon/agent/drivers/WorktreeSandbox.kt",
        )
        for (path in sites) {
            val code = GuardSourceScan.stripComments(GuardSourceScan.read(path))
            assertTrue("CliChildEnv.applyTo(" in code, "$path 必须经 CliChildEnv.applyTo 装配子进程环境")
            assertFalse("environment().clear()" in code, "$path 不许清空继承环境（安全语义见 CliChildEnv）")
        }
    }
}
