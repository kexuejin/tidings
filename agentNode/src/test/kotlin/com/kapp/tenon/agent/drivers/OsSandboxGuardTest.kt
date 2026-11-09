// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.drivers

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OS 级轻量沙箱守卫（提案 §9 阶段三，opt-in）。
 *
 * 探活全部走注入的命令执行器——真实环境能不能跑 sandbox-exec/unshare 是
 * 「这台机器」的事实，测试结论不许依赖它（envDependentTestGate 同口径）。
 */
class OsSandboxGuardTest {

    @Test
    fun strictModeIsOptInOnly() {
        assertTrue(!OsSandboxGuard.isStrictRequested(emptyMap()), "不设开关 = 不启用（嵌套环境不破坏现状）")
        assertTrue(!OsSandboxGuard.isStrictRequested(mapOf("TENON_OS_SANDBOX" to "off")))
        assertTrue(!OsSandboxGuard.isStrictRequested(mapOf("TENON_OS_SANDBOX" to "  ")))
        assertTrue(OsSandboxGuard.isStrictRequested(mapOf("TENON_OS_SANDBOX" to "strict")))
        assertTrue(OsSandboxGuard.isStrictRequested(mapOf("TENON_OS_SANDBOX" to "STRICT")), "开关值大小写不敏感")
    }

    @Test
    fun seatbeltProfileDeniesNetworkButKeepsEverythingElse() {
        val profile = OsSandboxGuard.seatbeltProfile(emptyList())
        assertTrue(profile.contains("(allow default)"), "最小侵入：其余操作放行（任务要写 worktree、读工具链）")
        assertTrue(profile.contains("(deny network*)"), "唯一被收紧的是网络")
        assertTrue(!profile.contains("remote ip"), "无白名单时不该有空放行项")

        val withAllow = OsSandboxGuard.seatbeltProfile(listOf("203.0.113.7", "198.51.100.0/24"))
        assertTrue(withAllow.contains("(allow network-outbound (remote ip \"203.0.113.7\"))"))
        assertTrue(withAllow.contains("(allow network-outbound (remote ip \"198.51.100.0/24\"))"))
    }

    @Test
    fun probeReportsUnavailableWithActionableReasonOnNestedEnvironments() {
        // 模拟嵌套环境：sandbox_apply 被拒（CliSubprocessDriver 注释记录过的真实现场）。
        val probe = OsSandboxGuard.probe(
            osName = "Mac OS X",
            runCommand = { _, _ -> OsSandboxGuard.CommandResult(1, "", "sandbox_exec: sandbox_apply: Operation not permitted") },
        )
        assertTrue(probe is OsSandboxGuard.ProbeResult.Unavailable, "探不过必须如实报告，不许静默放行")
        val reason = (probe as OsSandboxGuard.ProbeResult.Unavailable).reason
        assertTrue(reason.contains("Operation not permitted") || reason.contains("probe failed"), reason)
    }

    @Test
    fun probeReportsAvailableWhenWrapperWorks() {
        val probe = OsSandboxGuard.probe(
            osName = "Mac OS X",
            runCommand = { cmd, _ ->
                assertTrue(cmd.first() == "sandbox-exec", "macOS 探活必须走 sandbox-exec")
                assertTrue(cmd.contains("/bin/echo"), "探活命令要能证明 profile 真的被执行")
                OsSandboxGuard.CommandResult(0, "tenon-probe-ok", "")
            },
        )
        assertEquals(OsSandboxGuard.ProbeResult.Available(OsSandboxGuard.Kind.SEATBELT), probe)
    }

    @Test
    fun unknownOsHasNoWrapper() {
        val probe = OsSandboxGuard.probe(osName = "Plan 9", runCommand = { _, _ -> OsSandboxGuard.CommandResult(0, "ok", "") })
        assertTrue(probe is OsSandboxGuard.ProbeResult.Unavailable, "没有包装器的 OS 必须如实说没有")
        assertTrue((probe as OsSandboxGuard.ProbeResult.Unavailable).reason.contains("no sandbox wrapper"))
    }

    @Test
    fun wrapProducesTheRightCommandShapes() {
        // profile 要真实写盘（sandbox-exec 读它），所以用真实临时目录。
        val worktree = File.createTempFile("ws-sbx-", "").let { f -> f.delete(); f.mkdirs(); f }
        val profileDir = File.createTempFile("ws-sbx-profile-", "").let { f -> f.delete(); f.mkdirs(); f }
        try {
            val seatbelt = OsSandboxGuard.wrap(
                listOf("claude", "--run"),
                OsSandboxGuard.Kind.SEATBELT,
                profileDir = profileDir,
            )
            assertEquals("sandbox-exec", seatbelt.first())
            assertEquals("-f", seatbelt[1])
            assertTrue(
                seatbelt[2].endsWith(OsSandboxGuard.PROFILE_FILE_NAME),
                "profile 文件名固定：$seatbelt",
            )
            assertTrue(File(seatbelt[2]).isFile, "profile 文件必须真实写盘（sandbox-exec 要读它）")
            assertTrue(
                File(seatbelt[2]).canonicalPath.startsWith(profileDir.canonicalPath),
                "profile 必须落在调用方给的 profileDir 里",
            )
            assertEquals(listOf("claude", "--run"), seatbelt.drop(3), "原命令原样跟在包装器后")

            val unshare = OsSandboxGuard.wrap(listOf("node", "main.js"), OsSandboxGuard.Kind.UNSHARE)
            assertEquals(listOf("unshare", "-n", "node", "main.js"), unshare)
        } finally {
            worktree.deleteRecursively()
            profileDir.deleteRecursively()
        }
    }

    /**
     * 反向钉子：策略文件**不许**落进 worktree。
     *
     * 旧实现写的是 `File(worktree, ".tenon-sandbox.sb")`，而 harvest 用 `git add -A`
     * 收集改动 —— 于是每个开了 strict 的任务都会产出一个「新增 .tenon-sandbox.sb」
     * 的假补丁，纯读任务也会看起来像改了文件。这条用例在旧实现下必然红。
     */
    @Test
    fun seatbeltProfileIsNeverWrittenIntoTheWorktree() {
        val worktree = File.createTempFile("ws-sbx-clean-", "").let { f -> f.delete(); f.mkdirs(); f }
        val profileDir = File.createTempFile("ws-sbx-profiledir-", "").let { f -> f.delete(); f.mkdirs(); f }
        try {
            OsSandboxGuard.wrap(
                listOf("claude", "--run"),
                OsSandboxGuard.Kind.SEATBELT,
                profileDir = profileDir,
            )
            val leftInWorktree = worktree.walkTopDown().drop(1).map { it.name }.toList()
            assertEquals(
                emptyList(),
                leftInWorktree,
                "worktree 里不许出现任何包装产物（否则会进 patch.diff）：$leftInWorktree",
            )
        } finally {
            worktree.deleteRecursively()
            profileDir.deleteRecursively()
        }
    }

    /** 探活超时不挂死测试（5s 上限 + destroyForcibly 在 execForProbe 内部）。 */
    @Test
    fun probeFailureViaExceptionIsUnavailable() {
        val probe = OsSandboxGuard.probe(
            osName = "Linux",
            runCommand = { _, _ -> throw java.io.IOException("unshare: Permission denied") },
        )
        assertTrue(
            probe is OsSandboxGuard.ProbeResult.Unavailable &&
                (probe as OsSandboxGuard.ProbeResult.Unavailable).reason.contains("not runnable"),
        )
    }
}
