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

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile.normalize()
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到仓库根（工作目录 " + File("").absolutePath + "）")
    }

    /** 只看依赖声明段：注释里解释「为什么不用 Compose」是合法的。 */
    private fun dependencyLines(file: File): List<String> =
        file.readLines()
            .filter { it.trimStart().startsWith("implementation(") || it.trimStart().startsWith("api(") }
            .filterNot { it.contains(":core") }

    @Test
    fun `cli 依赖里没有 Compose`() {
        val file = File(repoRoot(), "cli/build.gradle.kts")
        assertTrue(file.isFile, ":cli 的构建脚本不存在 —— 守卫的路径过期了，它现在永远为真")
        val offenders = dependencyLines(file).filter { line ->
            line.contains("compose") || line.contains(":composeApp")
        }
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
        val offenders = dependencyLines(file).filter { it.contains("compose") }
        assertTrue(
            offenders.isEmpty(),
            ":app 一旦引 Compose，:cli 那条验收就形同虚设（Compose 会顺着传递依赖进来）：" + offenders +
                " —— 文案走 RelayUiTexts、相机槽走 QrPreviewProvider、解码走 ImageDecoding，" +
                "它们都在 composeApp 里实现",
        )
    }
}
