// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「`:cli` 里不许出现 Compose」—— 这条守卫是阶段 2 的判据本身。
 *
 * ## 为什么这条比"分了模块"更值得钉
 *
 * 分模块只说明**目录**动了。它不说明领域层里没有 UI 耦合 —— 只要有人给 `:app`
 * 加一行 `implementation(compose.runtime)`，`import androidx.compose.*` 就重新合法，
 * 而所有既有测试仍然全绿。
 *
 * `:cli` 是那份耦合的**外部探测点**：它只链接 `:app`（+ `:core`）。如果 Compose
 * 从 `:app` 漏过来，这个模块的运行时 classpath 上就会出现 Compose 的 jar ——
 * 那条路走不通，验收才成立。
 *
 * ## 两条判据
 *
 * 1. **构建脚本**：`:cli` 的依赖块里不许出现 `compose` / `:composeApp`；
 * 2. **`:app` 的构建脚本**：同上（它一旦引 Compose，`:cli` 那条就形同虚设）。
 *
 * 第 1 条能抓到「有人为了方便给 cli 加了个 UI 依赖」，第 2 条能抓到
 * 「有人给领域层加了 Compose 依赖」—— 后者才是会悄悄毁掉整件事的那一种。
 */
class CliHasNoComposeTest {

    /**
     * 仓库根，**从测试类自己的代码源往上找**，不看进程工作目录。
     *
     * 为什么不用 `File("")`（工作目录）：那会让这条守卫的结论取决于"谁在哪里跑它" ——
     * Gradle 把测试工作目录设成模块目录、IDE 常常设成模块或仓库根、CI 也可能是别处。
     * 同一条纪律在不同入口下"有时找不到文件"，症状是"偶发红"，而根因是环境。
     * 类文件的位置则跟着构建产物走，任何入口都一样。
     */
    private fun repoRoot(): File {
        val codeSource = runCatching {
            File(CliHasNoComposeTest::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val starts = listOfNotNull(codeSource)
        for (start in starts) {
            var dir: File? = start.absoluteFile.normalize()
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
        }
        throw AssertionError(
            "找不到仓库根：从类代码源 " + starts.joinToString { it.path } + " 向上都没有 settings.gradle.kts",
        )
    }

    @Test
    fun theOffenderJudgementItselfCanTellRightFromWrong() {
        // 合成用例：判据必须**能红也能绿**。没有这一条，一个恒返回空列表的实现
        // 会让上面两条守卫全绿 —— 而它们恰恰是"领域层不许有 UI"的唯一证据。
        assertTrue(
            cliComposeOffenders(
                listOf(
                    "implementation(project(\":core\"))",
                    "implementation(\"org.jetbrains.compose.runtime:runtime\")",
                ),
            ).isNotEmpty(),
            "判据抓不到 Compose 依赖 —— 两条守卫会永远绿",
        )
        assertTrue(
            cliComposeOffenders(listOf("implementation(project(\":core\"))", "implementation(project(\":app\"))")).isEmpty(),
            "判据把普通依赖当成了 Compose —— 它会永远红",
        )
        assertTrue(
            appComposeOffenders(listOf("api(\"org.jetbrains.compose.ui:ui\")")).isNotEmpty(),
            ":app 那条判据抓不到 Compose",
        )
        assertTrue(
            appComposeOffenders(listOf("implementation(project(\":core\"))")).isEmpty(),
            ":app 那条判据把普通依赖当成了 Compose",
        )
    }

    @Test
    fun `cli 依赖里没有 Compose`() {
        val file = File(repoRoot(), "cli/build.gradle.kts")
        assertTrue(file.isFile, ":cli 的构建脚本不存在 —— 守卫的路径过期了，它现在永远为真")
        val offenders = cliComposeOffenders(file.readLines())
        assertTrue(
            offenders.isEmpty(),
            ":cli 必须是「没有 UI」的那个程序，但它的依赖里出现了 Compose：" + offenders +
                " —— 加进去之后，这条验收就变成自证了（它证明的是 cli 里有 UI，不是领域层没有）",
        )
    }

    @Test
    fun `领域层 app 的依赖里没有 Compose`() {
        val file = File(repoRoot(), "app/build.gradle.kts")
        assertTrue(file.isFile, ":app 的构建脚本不存在 —— 守卫的路径过期了")
        val offenders = appComposeOffenders(file.readLines())
        assertTrue(
            offenders.isEmpty(),
            ":app 一旦引 Compose，:cli 那条验收就形同虚设（Compose 会顺着传递依赖进来）：" + offenders +
                " —— 文案走 RelayUiTexts、相机槽走 QrPreviewProvider、解码走 ImageDecoding，" +
                "它们都在 composeApp 里实现",
        )
    }
}
// ---------------------------------------------------------------- 判据（纯函数）

/**
 * 依赖声明行：`implementation(...)` / `api(...)`，**排除 :core**。
 *
 * 排除 :core 是 cli 那条判据特有的口径：cli 本来就允许依赖领域内核。
 * 之后再看里面有没有 Compose。
 */
internal fun dependencyLines(lines: List<String>): List<String> =
    lines
        .filter { it.trimStart().startsWith("implementation(") || it.trimStart().startsWith("api(") }
        .filterNot { it.contains(":core") }

/**
 * **cli 的判据：依赖里不许出现 Compose（或 UI 模块 :composeApp）。**
 *
 * 抽成纯函数（输入是一份文本行）是为了让"这条判据能不能区分对错"能被合成用例钉住：
 * 恒返回空列表的实现会让两条守卫全绿，而它们正是"领域层真的没有 UI"的唯一证据。
 */
internal fun cliComposeOffenders(lines: List<String>): List<String> =
    dependencyLines(lines).filter { it.contains("compose") || it.contains(":composeApp") }

/** **:app 的判据：领域层依赖里不许出现 Compose**（:composeApp 那条同理由上面兜住）。 */
internal fun appComposeOffenders(lines: List<String>): List<String> =
    dependencyLines(lines).filter { it.contains("compose") }
