// SPDX-License-Identifier: GPL-3.0-only
//
// 施工痕迹扫描：找出只对"当时在做什么"有意义、对下一个人没意义的注释形态。
//
// 由根 build.gradle.kts 通过 `apply(from = "tools/construction-trace-gate.gradle.kts")`
// 引入，注册两个任务：
//
//   :constructionTraceGate         扫描生产源码，与棘轮基线比对（默认入口）
//   :constructionTraceGateBaseline 重新生成基线（有意为之的棘轮调整）
//
// 为什么需要它：`docs/relay-comment-audit.md` 的 §4 立了七条注释政策，其中六条都是
// "禁止出现某种形态"，而这六种形态有个共同特点：**它们都很短、很像正常内容**。
// "2026-10-10 在 Pixel 6 上实测" 读起来完全像一句技术说明，人工 review 不会拦它。
// 只有把它变成一条会报红的门禁，政策才真的生效。
//
// 七条形态（每条对应政策里的一项）：
//   1. ISO 日期        20XX-XX-XX
//   2. 真机型号        Pixel N / iPhone N / iPad N / 小米 / Redmi
//   3. 内网地址        192.168.x.x（**排除**私网网段判据本身与明显是文档示例的写法）
//   4. 本机绝对路径    /Users/<名字>、/Volumes/<名字>
//   5. 决议号          决议 #NNN
//   6. 内部文件标记    PLAN.md / ARCHITECTURE.md / docs/plans/
//   7. 时间戳痕迹      "北京时间 HH:MM:SS"、"第 N 次"这类进度记录
//
// 棘轮语义与 cjkLiteralGate / staleCommentRefGate 同构：基线记每个文件允许的条数，
// 只增不减；新增才失败，下调只提示。
//
// 扫描根与 cjkLiteralGate 一致（生产源码），但**不含测试源集** —— 测试里写
// `192.168.2.82:17878` 是被测对象本身，不是注释里的施工痕迹。

import org.gradle.api.GradleException
import java.io.File

val traceTool = File(project.projectDir, "tools/construction-trace.py")
val traceBaseline = File(project.projectDir, "tools/construction-trace-baseline.txt")

val traceRoots = listOf(
    "core/src/commonMain",
    "composeApp/src/commonMain",
    "composeApp/src/androidMain",
    "composeApp/src/iosMain",
    "composeApp/src/jvmMain",
    "agentNode/src/main",
    "desktopApp/src/main",
    "androidApp/src/main",
)

fun traceNote(): String {
    val head = try {
        val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(project.projectDir).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
    return "HEAD=$head"
}

tasks.register("constructionTraceGate") {
    group = "verification"
    description = "注释里的施工痕迹（日期 / 真机型号 / 内网地址 / 本机路径 / 决议号 / 内部文件标记 / 时间戳）"
    doLast {
        val args = mutableListOf(
            "python3", traceTool.absolutePath,
            "--root", project.projectDir.absolutePath,
            "--baseline", traceBaseline.absolutePath,
            "--check",
        )
        traceRoots.forEach { args.add("--root-dir"); args.add(it) }
        val proc = ProcessBuilder(args)
            .directory(project.projectDir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (out.isNotEmpty()) logger.lifecycle(out)
        if (proc.waitFor() != 0) {
            throw GradleException("施工痕迹门禁失败：注释里出现了新的施工痕迹形态。\n" +
                "为什么禁：这种注释只对'当时在做什么'有意义，而日期与型号会让下一个人以为\n" +
                "这条结论有时效。要留就留结论本身，去掉日期与机型；需要引用真机缺陷史的，\n" +
                "去 docs/adr/ 建一条 ADR，而不是在代码里写日期。")
        }
    }
}

tasks.register("constructionTraceGateBaseline") {
    group = "verification"
    description = "重新生成施工痕迹门禁的棘轮基线（有意为之的棘轮调整）"
    doLast {
        val args = mutableListOf(
            "python3", traceTool.absolutePath,
            "--root", project.projectDir.absolutePath,
            "--baseline", traceBaseline.absolutePath,
            "--write",
        )
        traceRoots.forEach { args.add("--root-dir"); args.add(it) }
        val proc = ProcessBuilder(args)
            .directory(project.projectDir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (out.isNotEmpty()) logger.lifecycle(out)
        if (proc.waitFor() != 0) throw GradleException("生成基线失败")
        logger.lifecycle("基线生成于 ${traceNote()}")
    }
}