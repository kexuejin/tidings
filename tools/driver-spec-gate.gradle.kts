// SPDX-License-Identifier: GPL-3.0-only
//
// 驱动规格数据化门禁（driver-spec gates）。
//
// 由根 build.gradle.kts 通过 `apply(from = "tools/driver-spec-gate.gradle.kts")` 引入：
//
//   :driverSpecDrift        JSON 真源与生成物逐字节一致（默认入口，CI 跑它）
//   :driverSpecProject     从 driver-specs.json 重新投影 DriverSpecTable.generated.kt
//   :driverSpecSelfTest    投影器量具自检（喂已知坏规格，必须报红）
//
// 方向：JSON 是真源、Kotlin 是投影产物。校验前移到 codegen（未知键拒 + Spec 的两条
// require 不变式），Kotlin 侧 require 保留作双保险。生成物提交进 git、由 drift 抓手改；
// 与 design-spec（代码→JSON）方向相反，但「重新生成一遍、不一致就判红」两头通用。
//
// 工作目录 = project.projectDir = 仓库根。

import org.gradle.api.GradleException
import java.io.File

val driverSpecRepoRoot: File = project.projectDir
val driverSpecPython: String = (project.findProperty("driverSpecPython") as? String) ?: "python3"

fun runDriverSpecTool(script: String, vararg args: String): Int {
    val scriptFile = File(project.projectDir, "tools/$script")
    if (!scriptFile.isFile) {
        throw GradleException("驱动规格工具不存在：${scriptFile.path}（门禁脚本与工具必须成对提交）")
    }
    val cmd = listOf(driverSpecPython, scriptFile.path) + args
    logger.lifecycle("  \$ ${cmd.joinToString(" ")}  (cwd=${driverSpecRepoRoot.path})")
    val proc = ProcessBuilder(cmd)
        .directory(driverSpecRepoRoot)
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    if (output.isNotBlank()) {
        output.trimEnd().lineSequence().forEach { logger.lifecycle("  | $it") }
    }
    return proc.waitFor()
}

tasks.register("driverSpecDrift") {
    group = "verification"
    description = "驱动规格门禁：driver-specs.json 与生成物逐字节一致（漂移即红）"
    doLast {
        logger.lifecycle("驱动规格门禁 · JSON↔生成物漂移")
        val code = runDriverSpecTool("driver-spec-project.py", "--check", "--root", ".")
        if (code != 0) {
            throw GradleException(
                "driver-specs.json 与 DriverSpecTable.generated.kt 漂移或校验失败（退出码 $code）。" +
                    "改了真源就跑 `:driverSpecProject` 重新投影；生成物是 DO NOT EDIT，不许手改。"
            )
        }
    }
}

tasks.register("driverSpecProject") {
    group = "verification"
    description = "驱动规格：从 driver-specs.json 重新投影 DriverSpecTable.generated.kt"
    doLast {
        logger.lifecycle("驱动规格 · 重新投影生成物")
        val code = runDriverSpecTool("driver-spec-project.py", "--write", "--root", ".")
        if (code != 0) throw GradleException("投影失败（退出码 $code）")
    }
}

tasks.register("driverSpecSelfTest") {
    group = "verification"
    description = "驱动规格：投影器量具自检（已知坏规格必须报红）"
    doLast {
        logger.lifecycle("驱动规格 · 量具自检")
        val code = runDriverSpecTool("driver-spec-project.py", "--self-test")
        if (code != 0) throw GradleException("投影器量具自检失败 $code —— 门禁本身不可信")
    }
}
