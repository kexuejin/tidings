// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.domain.model.FileCardUi
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.TransferState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 原型评审入口的三条判据（阶段 A 人审守门的**客户端**一半）。
 *
 * 为什么这三条要被钉住：原型阶段的动作是「先看原型、再批准」。入口不画，
 * 用户就是在看不到原型的情况下点 Approve & Develop —— 盲批，而全生命周期
 * 验收里「人审」名义存在、实质空转。
 *
 * 反向验证记录：
 * - 把 `kindOf` 的 `.html` 分支拿掉 ⇒ [htmlPrototypeRoutesToTheSystemBrowser] 红；
 * - 把 `prototypeSavedPathOf` 的落盘判据放宽（进行中也给路径）⇒
 *   [inFlightPrototypeGivesNoPathToOpen] 红；
 * - 把 `isLocalCardAction` 改成恒 false ⇒ [openPrototypeIsAClientLocalAction] 红。
 */
class PrototypeReviewEntryTest {

    private fun parse(json: String): AgentCardUi =
        assertNotNull(parseAgentCard(Json.parseToJsonElement(json) as JsonObject))

    private fun messageWithFile(
        id: String,
        name: String,
        state: TransferState,
        savedPaths: List<String>,
        at: Long = 1_000L,
    ) = MessageUi(
        id = id,
        peerId = "group:g1",
        mine = false,
        text = "",
        at = at,
        file = FileCardUi(
            transferId = "t-" + id,
            name = name,
            size = 10L,
            state = state,
            savedPaths = savedPaths,
        ),
    )

    @Test
    fun htmlPrototypeRoutesToTheSystemBrowser() {
        assertEquals(ArtifactPreview.Kind.Html, ArtifactPreview.kindOf("prototype.html", null))
        assertEquals(ArtifactPreview.Kind.Html, ArtifactPreview.kindOf("index.htm", null))
        assertEquals(
            ArtifactPreview.Kind.Html,
            ArtifactPreview.kindOf("bundle-0715", "text/html"),
            "扩展名沉默时 mime 兜底（宿主就是这么发 prototype.html 的）",
        )
        // 端内不渲染 ≠ 不可打开：这一类交给系统浏览器，入口照样要画。
        assertTrue(ArtifactPreview.Kind.Html.opensExternally, "网页原型不在端内渲染")
        assertFalse(ArtifactPreview.Kind.Markdown.opensExternally, "文档仍然端内渲染")
        assertTrue(
            ArtifactPreview.canPreview(
                AgentCardArtifact(name = "prototype.html", mime = "text/html", delivered = true),
                ArtifactPreview.Kind.Html,
                "/saved/prototype.html",
            ),
            "已投递 + 已落盘 ⇒ 入口可点（点开交给系统浏览器）",
        )
        // 旧卡不声明 delivered：不给入口（缺省 = 不给，与 md/diff 同一条尺子）。
        assertFalse(
            ArtifactPreview.canPreview(
                AgentCardArtifact(name = "prototype.html"),
                ArtifactPreview.Kind.Html,
                "/saved/prototype.html",
            ),
        )
    }

    @Test
    fun prototypeEntryIsFoundByNameNotByAFixedFilename() {
        val card = parse(
            """{"cardId":"chain-t1","rev":2,"state":"prototype","artifacts":[
                 {"name":"README.md","delivered":true},
                 {"name":"weather-proto.html","mime":"text/html","delivered":true}
               ]}""",
        )
        assertEquals(
            "weather-proto.html",
            ArtifactPreview.prototypeOf(card.artifacts)?.name,
            "认的是「网页」不是固定文件名",
        )
        assertNull(
            ArtifactPreview.prototypeOf(parse("""{"cardId":"c","rev":1,"artifacts":[{"name":"a.md"}]}""").artifacts),
            "卡上没有网页就返回 null —— 按钮整颗不画，而不是指向一个不存在的东西",
        )
    }

    @Test
    fun prototypePathOnlyExistsAfterTheFileLands() {
        val card = parse(
            """{"cardId":"chain-t1","rev":2,"state":"prototype",
                "artifacts":[{"name":"prototype.html","mime":"text/html","delivered":true}]}""",
        )
        val savedPathOf: (AgentCardArtifact) -> String? = { artifact ->
            artifactSavedPathOf(
                listOf(
                    messageWithCard("m1", "chain-t1"),
                    messageWithFile("m2", artifact.name, TransferState.Completed, listOf("/saved/" + artifact.name)),
                ),
                "chain-t1",
                artifact.name,
                900L,
            )
        }
        assertEquals("/saved/prototype.html", prototypeSavedPathOf(card, savedPathOf))

        // 还在传：不给路径（按钮与入口都不画，落盘后下一帧自然出现）。
        val inFlight: (AgentCardArtifact) -> String? = { artifact ->
            artifactSavedPathOf(
                listOf(
                    messageWithCard("m1", "chain-t1"),
                    messageWithFile("m2", artifact.name, TransferState.Active, emptyList()),
                ),
                "chain-t1",
                artifact.name,
                900L,
            )
        }
        assertNull(prototypeSavedPathOf(card, inFlight), "文件还没落盘：不给死按钮")
    }

    /**
     * 同名文件有多份时，「打开」必须是**这张卡自己投递的那一份**。
     *
     * 真机 2026-11-10（天气原型）：`downloads/prototype.html/` 里躺着上一轮的
     * `prototype.html`（21 KB，`tinct`）与 `prototype(1).html`，本轮落的是
     * `prototype(2).html`（49,934 B，`Atmos`）。卡片写的是 48.7 KB，点开却是
     * `tinct` —— 因为解析取的是消息流里**第一条**同名文件行（上一轮那条）。
     *
     * 判据：卡首帧之后的**最早**一份才是它的投递；更旧的同名行必须被跳过。
     */
    @Test
    fun theCardOpensItsOwnDeliveryNotTheOldestFileWithTheSameName() {
        val messages = listOf(
            // 上一轮（很久以前）落下的同名文件，位置在卡片之前。
            messageWithFile(
                "m-old", "prototype.html", TransferState.Completed,
                listOf("/dl/prototype.html/prototype.html"), at = 1_000L,
            ),
            messageWithCard("m-card", "chain-t2", at = 2_000L),
            // 本轮投递。
            messageWithFile(
                "m-new", "prototype.html", TransferState.Completed,
                listOf("/dl/prototype(2).html"), at = 3_000L,
            ),
        )
        assertEquals(
            "/dl/prototype(2).html",
            artifactSavedPathOf(messages, "chain-t2", "prototype.html", 2_000L),
            "打开的必须是本轮那份，不是同名的上一轮文件",
        )
    }

    /** 同一会话里两张卡各投递一次：各开各的，不许串到对方的文件上。 */
    @Test
    fun twoCardsInOneConversationEachOpenTheirOwnArtifact() {
        val messages = listOf(
            messageWithCard("c1", "chain-a", at = 100L),
            messageWithFile(
                "f1", "prototype.html", TransferState.Completed,
                listOf("/dl/prototype.html"), at = 150L,
            ),
            messageWithCard("c2", "chain-b", at = 500L),
            messageWithFile(
                "f2", "prototype.html", TransferState.Completed,
                listOf("/dl/prototype(1).html"), at = 550L,
            ),
        )
        assertEquals(
            "/dl/prototype.html",
            artifactSavedPathOf(messages, "chain-a", "prototype.html", 100L),
            "第一张卡指向自己那次投递",
        )
        assertEquals(
            "/dl/prototype(1).html",
            artifactSavedPathOf(messages, "chain-b", "prototype.html", 500L),
            "第二张卡指向自己那次投递（不是第一张卡的那份）",
        )
    }

    /**
     * 跨端时钟差：卡片时间戳被对端时钟推到投递之后时，退回**最新**一份而不是最旧。
     *
     * 这一支存在的理由：卡片 `at` 取的是信封时钟（发送方），而投递时间是接收方时钟，
     * 两台机器的时钟不同步时 `at >= sinceAt` 可能一条都命中。此时给最新一份仍是对的
     * （本轮投递就是最新），给最旧一份会复现缺陷。
     */
    @Test
    fun clockSkewFallsBackToTheNewestNotTheOldest() {
        val messages = listOf(
            messageWithFile(
                "f-old", "prototype.html", TransferState.Completed,
                listOf("/dl/old/prototype.html"), at = 100L,
            ),
            messageWithFile(
                "f-new", "prototype.html", TransferState.Completed,
                listOf("/dl/new/prototype(3).html"), at = 200L,
            ),
            messageWithCard("m-card", "chain-t3", at = 9_000L),
        )
        assertEquals(
            "/dl/new/prototype(3).html",
            artifactSavedPathOf(messages, "chain-t3", "prototype.html", 9_000L),
            "一份都没过线时取最新，绝不取最旧",
        )
    }

    @Test
    fun openPrototypeIsAClientLocalAction() {
        assertTrue(isLocalCardAction(SYSTEM_ACTION_OPEN_PROTOTYPE), "本地动作：谁打开谁负责")
        assertFalse(isLocalCardAction("approve-prototype"), "批准必须回宿主判定，不是本地动作")
        assertFalse(isLocalCardAction("merge-patch"))
    }

    private fun messageWithCard(id: String, cardId: String, at: Long = 900L) = MessageUi(
        id = id,
        peerId = "group:g1",
        mine = false,
        text = "",
        at = at,
        card = Json.parseToJsonElement("""{"cardId":"$cardId","rev":1}""") as JsonObject,
    )
}
