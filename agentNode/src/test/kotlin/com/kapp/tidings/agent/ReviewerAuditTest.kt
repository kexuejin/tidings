// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reviewer 审计三判据的正向与摘红，与 blocked 翻面。
 *
 * 「摘红」的方式是把**旧行为放回去**看断言变红：预算判据用超预算卡原文、键判据用
 * 白名单外的键 —— 不是只测「好的时候绿」。
 */
class ReviewerAuditTest {

    private fun patchWith(vararg plusLines: String): String =
        plusLines.joinToString("\n") { "+" + it }

    private fun stage(role: String = "开发", iso: Boolean = false) =
        ChainStage(role = role, agentKey = "codex", instruction = "活", requiresIsolation = iso)

    /** patch 参数是 patch **正文**：落成临时文件再给路径（checksFor 读的是文件）。 */
    private fun executedWithPatch(patch: String?): List<Pair<ChainStage, StageRun>> {
        if (patch == null) return listOf(stage() to StageRun(ok = true, patchPath = null))
        val f = File.createTempFile("audit", ".diff").apply { deleteOnExit() }
        f.writeText(patch)
        return listOf(stage() to StageRun(ok = true, patchPath = f.absolutePath))
    }

    // ------------------------------------------------ a) 语法规范

    @Test
    fun aCleanPatchPassesTheSyntaxAudit() {
        val patch = patchWith(
            "private const val MAX_BYTES = 24 * 1024",
            "return clampUtf8(payload, maxBytes)",
        )
        assertEquals(null, ReviewerAudit.syntaxFailureOf(patch))
    }

    @Test
    fun aHardcodedCjkPlusLineFailsTheSyntaxAudit() {
        val patch = patchWith("return \"提交成功\"")
        val fail = assertNotNull(ReviewerAudit.syntaxFailureOf(patch), "新增行里的硬编码 CJK 必须被抓")
        assertTrue(fail.contains("CJK"), fail)
    }

    @Test
    fun aConstructionTracePlusLineFailsTheSyntaxAudit() {
        // 形态判定与施工痕迹门禁同款：本机绝对路径 / ISO 日期都算痕迹。
        val pathPatch = patchWith("val home = \"/Users/someone/relay/build\"")
        assertTrue(ReviewerAudit.syntaxFailureOf(pathPatch)!!.contains("local-path"))
        val datePatch = patchWith("// verified 2026-01-02")
        assertTrue(ReviewerAudit.syntaxFailureOf(datePatch)!!.contains("iso-date"))
    }

    @Test
    fun contextAndHeaderLinesAreNotAuditedAsNewContent() {
        // 上下文行（空格前缀）与 +++ 文件头不是这次施工的新增，不进判据。
        val patch = "diff --git a/A.kt b/A.kt\n" +
            "--- a/A.kt\n" +
            "+++ b/A.kt\n" +
            "@@ -1,3 +1,4 @@\n" +
            " 上下文带中文不影响\n" +
            "+return clampUtf8(payload, maxBytes)"
        assertEquals(null, ReviewerAudit.syntaxFailureOf(patch))
    }

    // ------------------------------------------------ b) 白名单键

    private val domains = listOf(
        ReviewerAudit.ValueDomain("group-text", keys = setOf("kind", "text", "cardId", "rev")),
    )

    @Test
    fun aDeclaredKeyPassesTheWhitelistAudit() {
        val patch = patchWith("{\"kind\": payloadKind, \"rev\": rev}")
        assertEquals(null, ReviewerAudit.whitelistFailureOf(patch, domains))
    }

    @Test
    fun anUndeclaredKeyFailsTheWhitelistAudit() {
        val patch = patchWith("{\"senderBadge\": badge}")
        val fail = assertNotNull(ReviewerAudit.whitelistFailureOf(patch, domains), "白名单外的键必须被抓")
        assertTrue(fail.contains("senderBadge"), fail)
    }

    @Test
    fun aMissingDomainListSkipsHonestlyInsteadOfPassing() {
        // 缺输入不是通过：无清单 ⇒ SKIP（pending），且观察语必须写明原因。
        val checks = ReviewerAudit.checksFor(executedWithPatch(patchWith("val x = 1")), null)
        val keys = checks.first { it.id.startsWith("keys-") }
        assertEquals(ReviewerAudit.SKIP, keys.status)
        assertTrue(keys.observation!!.contains("未提供取值域清单"), keys.observation)
    }

    // ------------------------------------------------ c) 帧预算

    @Test
    fun aCardWithinBudgetPasses() {
        val card = buildJsonObject { put("cardId", "c"); put("rev", 1) }.toString()
        assertEquals(null, ReviewerAudit.budgetFailureOf(card))
    }

    @Test
    fun anOversizedCardFailsWithTheMeasuredBytes() {
        val card = buildJsonObject { put("cardId", "c"); put("pad", "x".repeat(30_000)) }.toString()
        val fail = assertNotNull(ReviewerAudit.budgetFailureOf(card))
        assertTrue(fail.contains("超出帧预算"), fail)
    }

    // ------------------------------------------------ checksFor 组装 + blocked 翻面

    @Test
    fun checksCarryRealPatchFindingsPerStage() {
        val patchFile = File.createTempFile("iso", ".diff").apply { deleteOnExit() }
        patchFile.writeText(patchWith("return clampUtf8(payload, maxBytes)"))
        val executed = listOf(
            stage("开发") to StageRun(ok = true, patchPath = patchFile.absolutePath),
            stage("验收") to StageRun(ok = true, patchPath = null),
        )
        val checks = ReviewerAudit.checksFor(executed, domains)
        assertEquals(4, checks.size, "每棒两条（语法/键）")
        assertTrue(checks[0].status == ReviewerAudit.DONE || checks[0].status == ReviewerAudit.FAILED,
            "有 patch 的棒必须真审（不许 SKIP）")
        assertEquals(ReviewerAudit.SKIP, checks[2].status, "无 patch 的棒如实 SKIP")
        assertTrue(checks[2].observation!!.contains("无从审"), checks[2].observation)
    }

    @Test
    fun aFailedAuditFlipsTheReadyCardToBlocked() = runBlocking {
        val patchFile = File.createTempFile("iso", ".diff").apply { deleteOnExit() }
        patchFile.writeText(patchWith("return \"已合并\""))
        val seen = mutableListOf<ChainProgress>()
        val runner = StageChainRunner(
            headSha = { "base" },
            runStage = { _, _, _ -> StageRun(ok = true, summary = "ok", patchPath = patchFile.absolutePath) },
            land = { _, _, _ -> "sha" },
            report = { p -> synchronized(seen) { seen += p } },
        )
        val out = runner.run("audit", "/tmp/repo", "t", listOf(stage()))
        assertEquals(1, out.size)
        assertEquals("blocked", seen.last().state, "审计不过必须翻 blocked，不出 review_ready")
        assertTrue(seen.last().banner!!.contains("审计未通过"), seen.last().banner)
        assertTrue(seen.last().checks.isNotEmpty(), "blocked 卡要带着失败原因的 checks[]")
        assertTrue(seen.last().checks.any { it.status == ReviewerAudit.FAILED },
            "checks 里必须有一项 failed（机主要能看到是哪条判据拦的）")
    }

    @Test
    fun aCleanChainLandsOnReviewReadyWithAllChecksAttached() = runBlocking {
        val patchFile = File.createTempFile("iso", ".diff").apply { deleteOnExit() }
        patchFile.writeText(patchWith("return clampUtf8(payload, maxBytes)"))
        val seen = mutableListOf<ChainProgress>()
        val runner = StageChainRunner(
            headSha = { "base" },
            runStage = { _, _, _ -> StageRun(ok = true, summary = "ok", patchPath = patchFile.absolutePath) },
            land = { _, _, _ -> "sha" },
            report = { p -> synchronized(seen) { seen += p } },
        )
        runner.run("audit-ok", "/tmp/repo", "t", listOf(stage()))
        val last = seen.last()
        assertEquals("review_ready", last.state)
        // 每棒两条 + 帧预算一条
        assertEquals(3, last.checks.size, "checks: " + last.checks)
        assertEquals(ReviewerAudit.DONE, last.checks.last().status)
        assertTrue(last.checks.last().observation!!.contains("实测"), last.checks.last().observation)
        // 卡面 JSON 真的带着 checks[].observation/status（手机解析契约）。
        val card = StageChain.cardOf(last).toString()
        assertTrue(card.contains("\"observation\""), card)
        assertTrue(card.contains("\"status\":\"done\""), card)
    }

    @Test
    fun theAuditCardJsonStaysWithinTheCardBudget() {
        // 常态链（5 棒）的审计合流卡必须在 24 KiB 内 —— 超了就是对端整帧拒收的形状。
        val executed = (1..5).map { i -> stage("棒" + i) to StageRun(ok = true, patchPath = null) }
        val checks = ReviewerAudit.checksFor(executed, null) + ReviewerAudit.budgetCheck(0)
        val card = StageChain.cardOf(
            ChainProgress("cbig", 9, "review_ready", "标题", null,
                executed.map { it.first.role to "done" }, 100, "全部 5 棒完成，等待机主终审",
                checks = checks),
        )
        assertTrue(card.toString().toByteArray(Charsets.UTF_8).size <= 24 * 1024)
    }
}