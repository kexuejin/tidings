// SPDX-License-Identifier: GPL-3.0-only
//
// commonMain 的 JVM 专有 API 门禁（KMP 多目标能否编译）。
//
// 由根 build.gradle.kts 通过 `apply(from = "tools/common-jvm-api-gate.gradle.kts")` 引入：
//
//   :commonJvmApiGate   扫 common 源集，出现 JVM 专有符号即失败
//
// ## 为什么它是根任务，而不是「跑一次 iOS 编译」
//
// 权威判据当然是编译：`./gradlew :core:compileKotlinIosArm64 ...`。但那条要 Kotlin/Native
// 工具链（CI 上几分钟起步），所以它从来不在收工清单里 —— 于是 2026-11-11 引入的
// `putIfAbsent` / `@JvmName` 在 commonMain 里躺了两天，Android 与桌面全绿、只有 iOS 红。
//
// 这道门禁只读源码、几秒出结果，抓的是「这一行不可能在任何非 JVM 目标上编译」这一类
// 症状；它不取代编一次 iOS（expect/actual 漏写之类只有编译器知道）。

import java.io.File

val commonJvmApiTool = File(project.projectDir, "tools/common-jvm-api-guard.py")

tasks.register("commonJvmApiGate") {
    group = "verification"
    description = "commonMain/commonTest 不许出现 JVM 专有 API（iOS/Native 目标编不过，Android 桌面照常绿）"

    // 输入是源码：任何一个 common 文件改了都该重跑。
    inputs.files(
        project.fileTree("core/src") { include("commonMain/**/*.kt", "commonTest/**/*.kt") },
        project.fileTree("app/src") { include("commonMain/**/*.kt", "commonTest/**/*.kt") },
        project.fileTree("composeApp/src") { include("commonMain/**/*.kt", "commonTest/**/*.kt") },
        commonJvmApiTool,
    )
    outputs.upToDateWhen { false }

    doLast {
        val (text, code) = try {
            val proc = ProcessBuilder(
                "python3", commonJvmApiTool.absolutePath,
                "--root", project.projectDir.absolutePath,
            )
                .directory(project.projectDir)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            out.trim() to exit
        } catch (e: Exception) {
            throw GradleException("common JVM API 门禁执行失败：${e.message}")
        }
        if (text.isNotEmpty()) println(text)
        if (code != 0) {
            throw GradleException(
                "common 源集出现 JVM 专有 API。\n" +
                    "commonMain 会被 iOS/Native 目标一起编译：`java.*`、`kotlin.jvm.*`、" +
                    "`putIfAbsent`/`merge`/`computeIfAbsent`、`synchronized`、`String.format`、" +
                    "`@JvmName` 这类 JVM 专有符号在那里不存在。\n" +
                    "症状是「Android 与桌面全绿、iOS 一编就红」，而 iOS 不在收工清单里 —— " +
                    "所以它会静默存在很久。\n" +
                    "改法：换 common 写法（`getOrPut`、`if (k !in m)`、`Mutex`、字符串模板）；" +
                    "确实只能 JVM 做的，放进 jvmMain/jvmSharedMain，common 侧走 expect/actual。",
            )
        }
    }
}
