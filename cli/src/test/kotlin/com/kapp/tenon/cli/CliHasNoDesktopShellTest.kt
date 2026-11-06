// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * :cli 的 **classpath 守卫**（与 CliHasNoComposeTest 同一形状，按阶段 1 的验收口径扩展）。
 *
 * ## 守的是什么
 *
 * 阶段 1 的验收是「编排面下沉后，一个没有 UI 的宿主也能建链跑通」。这条结论只有在
 * **编排层不把桌面壳拖回来**时成立：
 *
 * - :cli 的**主源集**仍然只链接 :app（Main.kt 的 KDoc 把这条写成验收条件本身）；
 * - :orchestration 只允许出现在 **testImplementation**（假驱动建链的验收测试用它）；
 * - 谁把 :desktopApp / :composeApp 加进主依赖，这条就红。
 *
 * 判据抽成纯函数，并用合成用例证明它「能红也能绿」—— 恒返回空列表的实现会让这条守卫
 * 永远绿，而它正是「验收物没有变成自证」的唯一证据。
 */
class CliHasNoDesktopShellTest {

    private fun repoRoot(): File {
        val codeSource = runCatching {
            File(CliHasNoDesktopShellTest::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val starts = listOfNotNull(codeSource)
        for (start in starts) {
            var dir: File? = start.absoluteFile.normalize()
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
        }
        throw AssertionError("找不到仓库根（起点：" + starts.joinToString { it.path } + "）")
    }

    @Test
    fun theOffenderJudgementCanTellRightFromWrong() {
        assertTrue(
            cliShellOffenders(
                listOf(
                    "implementation(project(\":app\"))",
                    "implementation(project(\":desktopApp\"))",
                ),
            ).isNotEmpty(),
            "判据抓不到桌面壳依赖 —— 这条守卫会永远绿",
        )
        assertTrue(
            cliShellOffenders(listOf("implementation(project(\":app\"))", "implementation(project(\":core\"))")).isEmpty(),
            "判据把正常依赖当成了桌面壳 —— 它会永远红",
        )
    }

    @Test
    fun mainSourceSetDoesNotLinkTheDesktopShell() {
        val lines = File(repoRoot(), "cli/build.gradle.kts").readLines()
        val offenders = cliShellOffenders(lines)
        assertTrue(
            offenders.isEmpty(),
            "cli 主源集依赖里出现了桌面壳 / UI / 编排实现：" + offenders +
                " —— 那样「没有 UI 的验收物」就变成自证了（编排层只能进 testImplementation）",
        )
    }

    @Test
    fun acceptanceTestGetsOrchestrationOnTheTestClasspath() {
        val text = File(repoRoot(), "cli/build.gradle.kts").readText()
        assertTrue(
            text.contains("testImplementation(project(\":orchestration\"))"),
            "假驱动建链的验收测试需要 :orchestration 在测试依赖里；否则它证明不了下沉成功",
        )
    }
}

/**
 * 判据（纯函数）：依赖声明行里出现桌面壳 / UI 栈 / 编排实现，就是 offender。
 *
 * 只看 implementation( 与 api( 两种主源集声明；testImplementation( 不在判据内 ——
 * 验收测试拿编排层是**必要**的，主程序拿它才是问题。
 */
internal fun cliShellOffenders(lines: List<String>): List<String> =
    lines.map { it.trim() }
        .filter { it.startsWith("implementation(") || it.startsWith("api(") }
        .filter { line ->
            listOf(":desktopApp", ":composeApp", ":orchestration", "compose").any { banned ->
                line.contains(banned)
            }
        }
