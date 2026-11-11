// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.desktop

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 显式指定的 luciola 原生库目录**必须按仓库根解析**，不能按 Gradle 守护进程的 cwd。
 *
 * ## 现场（2026-11-11 本轮实测，不是推测）
 *
 * 为了让真引擎用例跑起来，我敲了一条最自然的命令：
 *
 * ```
 * ./gradlew :core:jvmTest --tests '*LuciolaDiscoveryEngineTest*' \
 *     -PluciolaNativeDir=desktopApp/resources/common/native
 * ```
 *
 * 结果 5 条用例全红，报错是：
 *
 * ```
 * 已配置 luciola 原生库目录，但里面没有 libluciola_uniffi.dylib/…：
 * <gradle-user-home>/daemon/<版本>/desktopApp/resources/common/native
 * ```
 *
 * **那个路径我一个字符都没打过。** 根因是 `File(explicit)` 把相对路径解析到了
 * Gradle 守护进程的工作目录（`<gradle-user-home>/daemon/<版本>/`），而报错信息
 * 又把解析后的绝对路径当成"你配的那个目录"打印出来。症状指向"你路径写错了"，
 * 真因是"解析基准错了" —— 排查方向被引到反方向。
 *
 * ## 为什么只能在源码层守
 *
 * 这段逻辑活在 `build.gradle.kts` 里，不是可单测的函数：Gradle 配置脚本没法在
 * JUnit 里直接调用（要 TestKit 起一个真实构建，几秒一次的代价换一条判据不划算）。
 * 本仓对这类纪律的既定做法就是读构建脚本源码（见 `:cli` 的 `CliHasNoComposeTest`
 * 读 `app/build.gradle.kts`）。
 *
 * ## 两条判据
 *
 * 1. `:core` 与 `:desktopApp` **两处**解析器都要 `project.rootDir.resolve(...)` ——
 *    两份实现必须同规则，否则 `:core` 的测试和桌面打包会指向不同目录；
 * 2. `:desktopApp` 给 `JavaExec` 设 `jna.library.path` 的那一支同样要解析 ——
 *    `./gradlew :desktopApp:run` 走的是这条，漏了它就只有"测试能跑、开发跑不起来"。
 */
class LuciolaNativeDirResolutionTest {

    private val coreScript = "core/build.gradle.kts"
    private val desktopScript = "desktopApp/build.gradle.kts"

    /** 判据里唯一的"正确写法"标记。换写法可以，但两处必须同时换 —— 见 [bothScriptsAgree]。 */
    private val marker = "project.rootDir.resolve("

    private fun liveCore(): String = GuardSourceScan.stripComments(GuardSourceScan.read(coreScript))

    private fun liveDesktop(): String = GuardSourceScan.stripComments(GuardSourceScan.read(desktopScript))

    /**
     * 判据本体：两份脚本都按仓库根解析，且 `JavaExec` 那一支也解析。
     *
     * 单独提出来是为了让变异自检能喂它各种形状的文本（真实源码的删改、合成脚本），
     * 而不是只能对着磁盘上那份文件断言一次。
     */
    private fun judge(core: String, desktop: String): Boolean {
        if (core.isBlank() || desktop.isBlank()) return false
        return resolvesInside(core, "fun resolveLuciolaNativeDir()") &&
            resolvesInside(desktop, "fun desktopLuciolaNativeDir()") &&
            resolvesInside(desktop, "tasks.withType<JavaExec>().configureEach")
    }

    /** 取 `marker` 之后第一对平衡花括号（即函数/配置块体），要求体内出现解析标记。 */
    private fun resolvesInside(text: String, marker: String): Boolean {
        val block = runCatching { GuardSourceScan.bracedBlockAfter(text, marker) }.getOrNull()
            ?: return false
        return block.contains(this.marker)
    }

    @Test
    fun bothScriptsResolveTheExplicitNativeDirAgainstTheRepoRoot() {
        assertTrue(
            judge(liveCore(), liveDesktop()),
            "luciola 原生库目录的解析基准必须是仓库根：\n" +
                "  :core        $coreScript 的 resolveLuciolaNativeDir()\n" +
                "  :desktopApp  $desktopScript 的 desktopLuciolaNativeDir()\n" +
                "两处都要有 `$marker…`。裸 `File(explicit)` 会把相对路径解析到 Gradle 守护进程的 " +
                "cwd（<gradle-user-home>/daemon/<版本>/），而报错信息又会把那个你没打过的路径" +
                "当成\"你配的目录\"打印出来 —— 症状指向路径写错，真因是解析基准错。",
        )
    }

    @Test
    fun theDevRunJavaExecAlsoResolvesAgainstTheRepoRoot() {
        val desktop = liveDesktop()
        assertTrue(
            resolvesInside(desktop, "tasks.withType<JavaExec>().configureEach"),
            "`./gradlew :desktopApp:run` 的 jna.library.path 也必须按仓库根解析：" +
                "否则只有测试能加载引擎、开发进程加载不到，症状是 run 一启动就 " +
                "UnsatisfiedLinkError，而 :desktopApp:test 全绿。",
        )
    }

    @Test
    fun everyCriticalFragmentIsRemovalSensitive() {
        val core = liveCore()
        val desktop = liveDesktop()
        assertTrue(judge(core, desktop), "前提：真实源码当前应判绿")

        // 每一处出现都必须"拿掉就红"。只断言"至少有一处"会漏掉第二份实现 ——
        // 而两份实现的漂移正是这条守卫存在的理由。
        val coreHits = core.indicesOf(marker)
        assertTrue(coreHits.isNotEmpty(), "$coreScript 里找不到 `$marker`")
        coreHits.forEach { at ->
            assertFalse(
                judge(core.removeRange(at, at + marker.length), desktop),
                "$coreScript 第 ${core.lineOf(at)} 行的 `$marker` 拿掉之后判据仍绿 —— 它没被真正检查",
            )
        }

        val desktopHits = desktop.indicesOf(marker)
        assertTrue(desktopHits.isNotEmpty(), "$desktopScript 里找不到 `$marker`")
        desktopHits.forEach { at ->
            assertFalse(
                judge(core, desktop.removeRange(at, at + marker.length)),
                "$desktopScript 第 ${desktop.lineOf(at)} 行的 `$marker` 拿掉之后判据仍绿 —— 它没被真正检查",
            )
        }
    }

    /**
     * 反面对照：**只在注释里**提到正确写法，判据必须红。
     *
     * 这不是凑数的用例。这条守卫最可能的退化方式，就是有人把 `project.rootDir.resolve(…)`
     * 写进注释解释"应该这么写"、而代码仍是裸 `File(explicit)`；那时任何"全文 contains"
     * 式的判据都会绿着放行。真实源码里剥注释之后才能喂判据，这里合成一份**未剥注释**
     * 的脚本来钉住这条：`stripComments` 之后只剩裸写法，判据必须为假。
     */
    @Test
    fun aCommentThatMentionsTheRightWayIsNotEnough() {
        val fake = """
            // 注意：显式路径应该用 project.rootDir.resolve(explicit) 解析，别用裸 File(explicit)
            fun resolveLuciolaNativeDir(): File? {
                val dir = File(explicit)
                return dir
            }
            fun desktopLuciolaNativeDir(): File? {
                val dir = File(explicit)
                return dir
            }
            tasks.withType<JavaExec>().configureEach {
                systemProperty("jna.library.path", it)
            }
        """.trimIndent()
        val stripped = GuardSourceScan.stripComments(fake)
        assertFalse(
            judge(stripped, stripped),
            "注释里写着正确写法、代码里却是裸 File(explicit)，判据必须红 —— " +
                "否则这条守卫会被一句\"以后要改\"的注释骗过去。",
        )
    }

    /** 空源码不得判绿：门禁读到空文件时"没有 offender"恒真，是本仓最常见的假绿形态。 */
    @Test
    fun emptySourceIsRed() {
        assertFalse(judge("", ""), "空源码必须判红")
        assertFalse(judge(liveCore(), ""), "只有一半源码时必须判红")
    }

    private fun String.indicesOf(needle: String): List<Int> {
        val out = ArrayList<Int>()
        var from = 0
        while (true) {
            val at = indexOf(needle, from)
            if (at < 0) return out
            out += at
            from = at + needle.length
        }
    }

    private fun String.lineOf(index: Int): Int = substring(0, index).count { it == '\n' } + 1
}
