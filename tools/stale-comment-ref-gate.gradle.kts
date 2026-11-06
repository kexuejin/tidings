// SPDX-License-Identifier: GPL-3.0-only
//
// 禁止注释里的 `X.kt:NN` 行号引用（stale comment reference gate）。
//
// 由根 build.gradle.kts 通过 `apply(from = "tools/stale-comment-ref-gate.gradle.kts")`
// 引入，注册两个任务：
//
//   :staleCommentRefGate         扫描 Kotlin 源码，与棘轮基线比对（默认入口）
//   :staleCommentRefGateBaseline 重新生成基线（有意为之的棘轮调整）
//
// 为什么需要它：行号引用是这套仓库里最容易腐坏的一种注释 —— 文件插入或删除十行，
// 那条注释指的地方就悄悄变成了另一段代码，**而且没有任何编译错误**。
// 注释盘查（`docs/relay-comment-audit.md`）里人工确认的 10 组陈旧注释全部是这一种形态，
// 没有一条是作者判断错了。所以规则很简单：引用别的文件时写符号名，不写行号 ——
// 符号名会被编译器与 IDE 盯着，行号不会。
//
// 实现放在 tools/stale-comment-ref.py：裸 grep 在这个仓库里假阳性极多
// （`Codec.kt` 有九个、`App.kt`/`Platform.kt`/`Common.kt` 各有好几个，在不同包里），
// 必须先剥出注释、再按 basename 解析出全部同名候选、逐个候选判"那一行是否有意义"。
// 同名歧义若至少有一个候选有意义就不阻塞。
//
// 基线语义与 `cjkLiteralGate` 同构（只增不减的棘轮，按文件记条数）。

import org.gradle.api.GradleException
import java.io.File

val staleRefTool = File(project.projectDir, "tools/stale-comment-ref.py")
val staleRefBaseline = File(project.projectDir, "tools/stale-comment-ref-baseline.txt")

fun staleRefNote(): String {
    val head = try {
        val proc = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(project.projectDir).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
    val dirty = try {
        val proc = ProcessBuilder("git", "status", "--porcelain")
            .directory(project.projectDir).redirectErrorStream(true).start()
        if (proc.inputStream.bufferedReader().readText().isBlank()) "clean" else "dirty"
    } catch (_: Exception) {
        "unknown"
    }
    return "HEAD=$head ($dirty)"
}

tasks.register("staleCommentRefGate") {
    group = "verification"
    description = "禁止注释里的 X.kt:NN 行号引用（写符号名，写行号会随重构腐坏）"
    doLast {
        val proc = ProcessBuilder(
            "python3", staleRefTool.absolutePath,
            "--root", project.projectDir.absolutePath,
            "--baseline", staleRefBaseline.absolutePath,
            "--check",
        ).directory(project.projectDir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (out.isNotEmpty()) logger.lifecycle(out)
        if (proc.waitFor() != 0) {
            throw GradleException("陈旧行号引用门禁失败：注释里出现了新的 `X.kt:NN` 引用。\n" +
                "改法：把行号换成符号名（函数名/字段名），或删掉这个引用。")
        }
    }
}

tasks.register("staleCommentRefGateBaseline") {
    group = "verification"
    description = "重新生成陈旧行号引用门禁的棘轮基线（有意为之的棘轮调整）"
    doLast {
        val before = if (staleRefBaseline.exists()) {
            staleRefBaseline.readLines().count { it.isNotBlank() && !it.startsWith("#") }
        } else 0
        val proc = ProcessBuilder(
            "python3", staleRefTool.absolutePath,
            "--root", project.projectDir.absolutePath,
            "--baseline", staleRefBaseline.absolutePath,
            "--write",
        ).directory(project.projectDir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (out.isNotEmpty()) logger.lifecycle(out)
        if (proc.waitFor() != 0) throw GradleException("生成基线失败")
        val after = staleRefBaseline.readLines().count { it.isNotBlank() && !it.startsWith("#") }
        logger.lifecycle("基线条目数: $before → $after（${staleRefNote()}）")
        if (after > before) {
            logger.warn("警告：基线上升了 ${after - before} 条。棘轮只应在确有新增且经评审时上调。")
        }
    }
}