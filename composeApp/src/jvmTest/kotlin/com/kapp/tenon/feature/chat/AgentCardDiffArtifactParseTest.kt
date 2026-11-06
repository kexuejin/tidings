// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.feature.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 宿主合流卡 diff/artifacts 的**解析契约**（与 agentNode 侧
 * `StageChainTest.theReviewReadyCardCarriesTheDiffPreviewAndTheArtifact` 成对）。
 *
 * 背景（2026-11-06 用户验收意见）：合流卡此前只有「标题 + 进度 + 按钮」，原型稿的
 * 文档预览（diff hunk）与交付产物块全缺 —— 宿主侧补发射后，本测试钉住手机端解析
 * 必须把这两块吃进 [AgentCardUi]，渲染层（CardDiffViewerRow / CardArtifactRows）
 * 才有得画。键名写错一处的后果与所有卡片键相同：两端都不报错，卡面悄悄少一块。
 */
class AgentCardDiffArtifactParseTest {

    @Test
    fun diffBlockParsesIntoTheModelWithHunkAndNote() {
        val card = json(
            """
            {
              "cardId": "chain-cabc",
              "rev": 8,
              "type": "task-board",
              "state": "review_ready",
              "title": "天气应用三棒",
              "progress": 100,
              "diff": {
                "path": "core/Outbound.kt",
                "summary": "2 files changed",
                "additions": 4,
                "deletions": 1,
                "note": "preview-truncated",
                "hunk": [
                  "@@ -45,7 +45,8 @@ fun clamp():",
                  "-    if (payload.length > 800) return payload.take(800)",
                  "+    val maxBytes = Limits.GROUP_TEXT_BYTES"
                ]
              },
              "artifacts": [
                { "name": "patch.diff", "bytes": 1517, "mime": "text/x-diff", "delivered": true }
              ]
            }
            """.trimIndent(),
        )
        val ui = assertNotNull(parseAgentCard(card), "合法卡片必须解析成功")
        val diff = assertNotNull(ui.diff, "diff 块必须进模型 —— 渲染层的文档预览靠它")
        assertEquals("core/Outbound.kt", diff.path)
        assertEquals("2 files changed", diff.summary)
        assertEquals(4, diff.additions)
        assertEquals(1, diff.deletions)
        assertEquals("preview-truncated", diff.note)
        assertEquals(3, diff.hunk.size, "hunk 原文行逐行进模型")
        assertTrue(diff.hunk.first().startsWith("@@"), "hunk 首行是 @@ 头（渲染按它分色）")

        assertEquals(1, ui.artifacts.size)
        val artifact = assertNotNull(ui.artifacts.first())
        assertEquals("patch.diff", artifact.name)
        assertEquals(1517L, artifact.bytes)
        assertEquals("text/x-diff", artifact.mime)
        assertEquals(true, artifact.delivered)
    }

    @Test
    fun botChipsAndCheckTimeParseIntoTheModel() {
        // 原型稿卡片头行三件套：工牌（bot 名/分支/状态点）、状态胶囊行、
        // 验收清单的耗时标签 —— 宿主从既有事实派生（轮 70 补记三），解析契约成对钉住。
        val card = json(
            """
            {
              "cardId": "chain-cabc",
              "rev": 3,
              "type": "task-board",
              "state": "executing",
              "title": "天气应用三棒",
              "progress": 40,
              "bot": { "name": "codex", "branch": "chain/chain-cabc", "bdot": "run" },
              "chips": [ { "text": "第 2/3 棒 · 40%", "tone": "pri" } ],
              "checks": [
                { "id": "syntax-1", "label": "语法规范 · 开发", "status": "done", "observation": "无命中", "time": "1.4s" }
              ]
            }
            """.trimIndent(),
        )
        val ui = assertNotNull(parseAgentCard(card))
        val bot = assertNotNull(ui.bot, "工牌块必须进模型")
        assertEquals("codex", bot.name)
        assertEquals("chain/chain-cabc", bot.branch)
        assertEquals("run", bot.bdot)
        assertEquals(1, ui.chips.size)
        assertEquals("第 2/3 棒 · 40%", ui.chips.first().text)
        assertEquals("pri", ui.chips.first().tone)
        assertEquals("1.4s", assertNotNull(ui.checks.first().time, "验收清单耗时标签必须进模型"))
    }

    @Test
    fun cardWithoutDiffKeepsDiffNullInsteadOfAnEmptyBlock() {
        val ui = assertNotNull(
            parseAgentCard(
                json(
                    """
                    {
                      "cardId": "chain-x",
                      "rev": 1,
                      "type": "task-board",
                      "state": "executing",
                      "title": "t",
                      "progress": 10
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(null, ui.diff, "没有 diff 键就不许造空块（渲染层按 null 跳过）")
        assertEquals(0, ui.artifacts.size)
    }

    private fun json(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject
}
