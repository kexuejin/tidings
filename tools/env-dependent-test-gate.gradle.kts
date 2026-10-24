// SPDX-License-Identifier: GPL-3.0-only
//
// 环境依赖测试门禁（env dependent test gate）。
//
// 由根 build.gradle.kts 通过 apply(from = ...) 引入：
//
//   :envDependentTestGate   测试结论不许依赖机器状态；例外必须进 allowlist
//
// ## 它守什么
//
// 一类缺陷在 CI 上表现为「偶发红」，在别人机器上表现为「我这绿了」，两者都不指向真根因：
// 测试的结论来自**这台机器**——网卡在不在、有没有 Clash TUN、系统时钟、以及「睡够 200ms
// 应该就好了」。本门禁用 tools/env-test-scan.py 找出 A（断言读机器取值）与 B（断言前用固定
// delay 同步）两类，要求：能抽成纯函数就用合成输入、能换探针就换、确实必须真环境的写进
// tools/env-test-allowlist.txt 并写清理由。
//
// ## 为什么是根任务
//
// 与 cjkLiteralGate / domainComposeFreeGate 同一理由：只读源码、不需要编译产物，几秒出结果。
// 放进某个模块的单元测试里会要求先编译，而「编译失败」会伪装成「门禁红了」。

import java.io.File

val envTestScanTool = File(project.projectDir, "tools/env-test-scan.py")
val envTestAllowlist = File(project.projectDir, "tools/env-test-allowlist.txt")

tasks.register("envDependentTestGate") {
    group = "verification"
    description = "测试不许依赖机器状态（网卡/时钟/固定 sleep 同步）；例外必须写进 allowlist。"

    inputs.files(
        project.fileTree(project.projectDir) {
            include("*/src/**/*Test.kt", "*/src/**/*Tests.kt")
            exclude("**/build/**")
        },
        envTestScanTool,
        envTestAllowlist,
    )
    // 门禁只读源码，没有输出可声明 —— 关掉 up-to-date 检查以免它被跳过。
    outputs.upToDateWhen { false }

    doLast {
        val (text, code) = try {
            val proc = ProcessBuilder(
                "python3", envTestScanTool.absolutePath, "--check",
                "--allowlist", envTestAllowlist.absolutePath,
            )
                .directory(project.projectDir)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            out.trim() to proc.waitFor()
        } catch (e: Exception) {
            throw GradleException("环境依赖测试门禁执行失败：" + e.message)
        }
        if (text.isNotEmpty()) println(text)
        if (code != 0) {
            throw GradleException(
                "环境依赖测试门禁失败：有测试的结论取决于这台机器。\n" +
                    "改法：① 抽成纯函数 + 合成输入（首选）；② 用「确定会发生的事件」做探针，" +
                    "不要用固定 delay 赌同步；③ 确实必须真环境的，写进 tools/env-test-allowlist.txt " +
                    "并写清理由（不许写「暂时保留」）。\n" +
                    "分类口径见 python3 tools/env-test-scan.py --help 的 A/B/C/D 四类。",
            )
        }
    }
}
