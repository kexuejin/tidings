// SPDX-License-Identifier: GPL-3.0-only
//
// 测试稳定性门禁：同一套测试连跑 N 轮，任何一轮红即失败。
//
//   :testStabilityGate                 默认 3 轮（core + app 的异步重灾区）
//   -PstabilityRuns=5 :testStabilityGate
//
// 为什么是「重复跑」而不是「多跑几条断言」：并发缺陷的表现是低频、轮换——单轮全绿时
// 你无法区分「没有缺陷」和「这一轮没撞上」。本仓真实读数：修 InMemoryQueueStore 之前
// 每 3 轮必红、且每次红的是不同的一条（GroupText / TransferPeerCancel / FileMessageStore）。
//
// 代价：N 轮 ≈ N × 40s（串行）。所以它不进默认门禁链，改并发/存储/测试替身时手动跑。

import java.io.File

val repeatScript = File(project.projectDir, "tools/repeat-tests.sh")

tasks.register("testStabilityGate") {
    group = "verification"
    description = "同一套测试连跑 N 轮（默认 3），任何一轮红即失败。"
    outputs.upToDateWhen { false }

    doLast {
        val runs = (project.findProperty("stabilityRuns") as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: "3"
        val proc = ProcessBuilder("bash", repeatScript.absolutePath, runs)
            .directory(project.projectDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (out.isNotBlank()) println(out.trim())
        if (code != 0) {
            throw GradleException(
                "测试稳定性门禁失败：连跑 " + runs + " 轮里有失败。" +
                    "先看是哪几条 —— 修测试替身的线程安全，而不是把断言放宽。",
            )
        }
    }
}
