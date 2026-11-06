// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 契约级两条编排不变量的守卫（契约 KDoc 的代码侧对账）。
 *
 * ## 判据与它对应的事实
 *
 * 1. **交接靠提交，不靠原始日志**：拼 prompt 的唯一入口只吃 StageSeed（基线提交 +
 *    压缩摘要 + 产物指针）。这一条是**类型级**的 —— 签名里没有装原始 stdout 的参数。
 *    所以守卫冻结签名：一旦有人加一个 rawLog 参数，这条立刻红。行为侧另外钉住
 *    「第二棒的起点是第一棒落下的提交、能看到摘要」。
 * 2. **单卡单槽翻面刷新**：一次链在对方界面上只占一个 cardId 槽位，每次播报 rev
 *    严格递增 —— 手机端按 cardId 原位替换、按 rev 拒旧帧。守卫从
 *    StageChain.cardOf 取卡面再核对，避免只信任内部字段。
 *
 * 外加一条：契约模块的 KDoc 不许把这两条删掉（删了不会让任何实现红，只会让下一个人
 * 失去为什么这样设计的线索）。
 */
class OrchestrationContractInvariantsTest {

    private val stageChain = "agentNode/src/main/kotlin/com/kapp/tenon/agent/StageChain.kt"
    private val contract = "agent-contract/src/main/kotlin/com/kapp/tenon/agent/drivers/AgentDriver.kt"

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private fun stage(role: String, key: String) = ChainStage(
        role = role,
        agentKey = key,
        instruction = role + " 这一棒要做的事",
    )

    @Test
    fun handoverPromptTakesOnlyStageSeed() {
        val code = live(stageChain)
        assertTrue(
            Regex("""fun promptOf\(stage: ChainStage, seed: StageSeed\): String""").containsMatchIn(code),
            "promptOf 的签名变了：交接只许吃 StageSeed（基线提交 + 摘要 + 产物指针），" +
                "不许新增装原始日志的参数 —— 那正是「棒间传几千行 stdout」的老毛病",
        )
        assertEquals(
            1,
            Regex("""fun promptOf\(""").findAll(code).count(),
            "promptOf 出现了重载：多一个入口就能绕过交接纪律",
        )
    }

    @Test
    fun contractKdocKeepsBothInvariants() {
        val kdoc = GuardSourceScan.read(contract)
        listOf("交接靠提交", "StageSeed", "单卡单槽", "cardId", "rev").forEach { fragment ->
            assertTrue(kdoc.contains(fragment), "契约 KDoc 丢了不变量片段：" + fragment)
        }
    }

    @Test
    fun chainRunsWithOneCardSlotAndGrowingRev() = runBlocking {
        val reports = mutableListOf<ChainProgress>()
        val prompts = mutableListOf<String>()
        var head = "base0000"
        val runner = StageChainRunner(
            headSha = { head },
            runStage = { stage, prompt, _ ->
                prompts += prompt
                StageRun(
                    ok = true,
                    sessionId = "s-" + stage.role,
                    patchPath = "/tmp/" + stage.role + ".diff",
                    summary = stage.role + " 完成",
                )
            },
            land = { _, run, _ ->
                head = "sha-" + run.sessionId
                head
            },
            report = { reports += it },
        )
        runner.run(
            "c-slot",
            "/tmp/repo",
            "契约不变量",
            listOf(stage("原型", "agy"), stage("开发", "codex"), stage("验收", "claude")),
        )

        assertEquals("review_ready", reports.last().state, "三棒全绿应收在 review_ready")

        // 一链一卡：所有播报的卡面 cardId 必须同一个（手机端按它原位替换）。
        val cardIds = reports.map { report ->
            StageChain.cardOf(report)["cardId"]?.toString()?.trim('"')
        }.distinct()
        assertEquals(listOf("chain-c-slot"), cardIds, "所有播报必须落在同一张卡上：" + cardIds)

        // rev 严格递增：重复或回退会让手机端把新帧判旧丢弃，卡面永久冻结。
        val revs = reports.map { it.rev }
        assertEquals(revs.sorted(), revs, "rev 必须单调递增：" + revs)
        assertEquals(revs.distinct().size, revs.size, "同一卡的 rev 不许重复：" + revs)

        // 交接靠提交：第二棒的起点是第一棒落下的提交 + 摘要，而不是它的原始输出。
        assertTrue(prompts[1].contains("sha-s-原型"), "第二棒的起点必须是第一棒落下的提交")
        assertTrue(prompts[1].contains("原型 完成"), "第二棒要看到第一棒的压缩摘要")
    }
}
