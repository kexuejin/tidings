// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.TransferState
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.card_artifact_preview_not_delivered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 产物**端内预览**与**自描述契约**的判据测试（升级计划 §4.2-3 / §7.4）。
 *
 * 全部合成输入：路由只吃「名字 + mime」，文件行解析只吃消息列表 ——
 * 不读盘、不读时钟、不起 Compose，环境依赖门禁无从判红。
 *
 * 反向验证记录：把 ArtifactPreview.kindOf 的 `.diff` 分支拿掉，md/diff/txt 可渲染
 * 断言红；把 artifactSavedPathOf 的 Completed 判据拿掉，「还在传的文件不给入口」红。
 */
class ArtifactPreviewRoutingTest {

    // ── 预览路由：md / diff / txt 可渲染，其他如实不可 ──────────────────────

    @Test
    fun markdownDiffTextAreRenderable() {
        assertEquals(ArtifactPreview.Kind.Markdown, ArtifactPreview.kindOf("report.md", null))
        assertEquals(ArtifactPreview.Kind.Diff, ArtifactPreview.kindOf("patch.diff", null))
        assertEquals(ArtifactPreview.Kind.Text, ArtifactPreview.kindOf("task.log", null))
        assertEquals(ArtifactPreview.Kind.Text, ArtifactPreview.kindOf("notes.txt", null))
    }

    @Test
    fun mimeFallsBackWhenExtensionSilent() {
        assertEquals(
            ArtifactPreview.Kind.Diff,
            ArtifactPreview.kindOf("bundle-0715", "text/x-diff"),
            "扩展名沉默时 mime 兜底",
        )
        assertEquals(
            ArtifactPreview.Kind.Markdown,
            ArtifactPreview.kindOf("README", "text/markdown"),
        )
    }

    @Test
    fun unknownTypesAreHonestlyNotPreviewable() {
        // 二进制按文本解出来是乱码：乱码窗口比「不可预览」更伤信任。
        assertEquals(ArtifactPreview.Kind.None, ArtifactPreview.kindOf("app.bin", "application/octet-stream"))
        assertEquals(ArtifactPreview.Kind.None, ArtifactPreview.kindOf("image.png", "image/png"))
        assertEquals(ArtifactPreview.Kind.None, ArtifactPreview.kindOf("report.json", null).let {
            // .json 在词表里是文本：换一个真正认不出的
            ArtifactPreview.kindOf("mystery.xyz", null)
        })
    }

    @Test
    fun previewRequiresDeliveredAndSavedPath() {
        val kind = ArtifactPreview.Kind.Diff
        val delivered = AgentCardArtifact(name = "patch.diff", delivered = true)
        val notDelivered = AgentCardArtifact(name = "patch.diff", delivered = false)
        val legacy = AgentCardArtifact(name = "patch.diff") // 旧卡：没带 delivered 键

        assertTrue(ArtifactPreview.canPreview(delivered, kind, "/saved/patch.diff"))
        assertFalse(
            ArtifactPreview.canPreview(notDelivered, kind, "/saved/patch.diff"),
            "没投出去的文件在本端没有本体，入口是死路",
        )
        assertFalse(
            ArtifactPreview.canPreview(legacy, kind, "/saved/patch.diff"),
            "旧卡没声明 delivered：不画预览承诺（缺省 = 不给）",
        )
        assertFalse(
            ArtifactPreview.canPreview(delivered, kind, null),
            "文件行还没落盘：入口不画",
        )
        assertFalse(
            ArtifactPreview.canPreview(delivered, ArtifactPreview.Kind.None, "/saved/patch.diff"),
            "种类不可渲染：入口不画",
        )
    }

    @Test
    fun blockedReasonExplainsUndeliveredOnly() {
        val kind = ArtifactPreview.Kind.Diff
        val reason = assertNotNull(
            ArtifactPreview.blockedReasonOf(
                AgentCardArtifact(name = "patch.diff", delivered = false),
                kind,
                null,
            ),
            "未投递是暂态：给一句「为什么看不了」而不是沉默",
        )
        // 直接比较资源对象：同一份生成的访问器是稳定单例，恒等即可判。
        assertEquals(Res.string.card_artifact_preview_not_delivered, reason)
        // 种类不可渲染：入口整个不画，无需解释。
        assertNull(ArtifactPreview.blockedReasonOf(AgentCardArtifact("x.bin"), ArtifactPreview.Kind.None, null))
        // 文件行未落盘：入口不画（暂态但文件行没到，没有可解释的对象）。
        assertNull(
            ArtifactPreview.blockedReasonOf(AgentCardArtifact(name = "patch.diff", delivered = true), kind, null),
        )
    }

    // ── 自描述条目解析：intent / delivered 进模型 ────────────────────────────

    private fun parse(json: String): AgentCardUi =
        assertNotNull(parseAgentCard(Json.parseToJsonElement(json) as JsonObject))

    @Test
    fun selfDescribingEntriesParse() {
        val card = parse(
            """{"cardId":"chain-t1","rev":2,
                "artifacts":[{"name":"patch.diff","bytes":48000,"mime":"text/x-diff",
                              "intent":"patch-full","delivered":true}]}""",
        )
        val artifact = assertNotNull(card.artifacts.firstOrNull())
        assertEquals("patch-full", artifact.intent)
        assertEquals(true, artifact.delivered)
        assertEquals(48000L, artifact.bytes)
    }

    @Test
    fun legacyEntriesParseWithoutNewKeys() {
        val card = parse(
            """{"cardId":"chain-t2","rev":1,
                "artifacts":[{"name":"patch.diff","bytes":100}]}""",
        )
        val artifact = assertNotNull(card.artifacts.firstOrNull())
        assertNull(artifact.intent, "旧卡没有 intent 键：解析不编造")
        assertNull(artifact.delivered, "旧卡没有 delivered 键：解析不编造")
    }

    // ── 文件行解析：同卡关联的已落盘文件 ────────────────────────────────────

    private fun messageWithFile(
        id: String,
        name: String,
        state: TransferState,
        savedPaths: List<String>,
    ) = MessageUi(
        id = id,
        peerId = "group:g1",
        mine = false,
        text = "",
        at = 1_000L,
        file = com.kapp.tidings.domain.model.FileCardUi(
            transferId = "t-" + id,
            name = name,
            size = 10L,
            state = state,
            savedPaths = savedPaths,
        ),
    )

    private fun messageWithCard(id: String, cardId: String) = MessageUi(
        id = id,
        peerId = "group:g1",
        mine = false,
        text = "",
        at = 900L,
        card = Json.parseToJsonElement("""{"cardId":"$cardId","rev":1}""") as JsonObject,
    )

    @Test
    fun savedPathResolvesFromCompletedFileRow() {
        val messages = listOf(
            messageWithCard("m1", "chain-t1"),
            messageWithFile("m2", "patch.diff", TransferState.Completed, listOf("/saved/patch.diff")),
        )
        assertEquals(
            "/saved/patch.diff",
            artifactSavedPathOf(messages, "chain-t1", "patch.diff"),
        )
    }

    @Test
    fun inFlightFileRowGivesNoPath() {
        val messages = listOf(
            messageWithCard("m1", "chain-t1"),
            messageWithFile("m2", "patch.diff", TransferState.Active, listOf()),
        )
        assertNull(
            artifactSavedPathOf(messages, "chain-t1", "patch.diff"),
            "还在传的文件给了路径也读不到全文：入口不画",
        )
    }

    @Test
    fun missingCardOrNameGivesNothing() {
        val messages = listOf(
            messageWithFile("m2", "patch.diff", TransferState.Completed, listOf("/saved/patch.diff")),
        )
        assertNull(
            artifactSavedPathOf(messages, "chain-t1", "patch.diff"),
            "卡不在这条会话里：文件行无从归属，不做孤立路径展示",
        )
        assertNull(artifactSavedPathOf(emptyList(), "chain-t1", "patch.diff"))
        assertNull(artifactSavedPathOf(emptyList(), "chain-t1", ""))
    }
}
