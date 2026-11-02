// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * **卡片先落库、再发帧**：运行时没挂时，本机也必须看得见这张卡。
 *
 * ## 现场
 *
 * 合并补丁在宿主本机落地（`git apply` 成功），随后 `broadcastBoardCard` 被调用来翻面。
 * 从前它的第一个动作是 `host.runtime ?: return` —— 运行时（P2P 传输）没起来时整张卡
 * 被丢掉，只留一行 `[CARD] dropped: no runtime attached`。
 *
 * 后果不是"少一条日志"：**仓库已经改了，卡却停在「待合并」**。用户看着一张还在等他
 * 点合并的卡，而工作树里那份补丁早就合进去了 —— 数据与界面对不上，且没有任何界面提示。
 *
 * ## 判据
 *
 * 这张卡属于**本机用户**（是他在本机点的合并），所以落库不该依赖运行时。运行时只决定
 * "别人看不看得见"。这与 `broadcastAgentState` 那句「先落库再发」是同一条纪律，
 * 也是 `CardActionAuthorizationTest` 里那段注释早就写下的口径。
 */
class BoardCardLocalPersistenceTest {

    private val scope = CoroutineScope(Job())

    private val phoneNode = "22222222-2222-4222-8222-222222222222"

    /** 未 start 的宿主：没有 runtime —— 正是真机上「传输没起来但本机在干活」的形态。 */
    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = 0, tcpPort = 0,
                persistMessages = false, persistMessagesDecided = true,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    private fun cardOf(cardId: String, rev: Int, state: String): JsonObject = buildJsonObject {
        put("cardId", cardId)
        put("rev", rev)
        put("type", "diff-review")
        put("state", state)
        put("title", "repo 改好了，等你合并")
    }

    private fun RelayController.cardRows(groupId: String) =
        state.value.messages[ConvId.group(groupId)].orEmpty().filter { it.renderCard != null }

    @Test
    fun aCardLandsLocallyEvenWithoutARuntimeAttached() = runBlocking {
        val host = controller()
        assertEquals(null, host.runtime, "前提：这个宿主没有挂运行时")
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!

        host.invokeCtl.broadcastBoardCard(groupId, "gpu-runner", cardOf("diff-t1", 1, "review_ready"))

        val rows = host.cardRows(groupId)
        assertEquals(1, rows.size, "运行时没挂也必须落库 —— 否则「合并成功但卡停在待合并」")
        val card = assertNotNull(rows.single().renderCard, "落库的是卡片帧，不是纯文本")
        assertEquals("diff-t1", (card["cardId"] as? JsonPrimitive)?.content)
    }

    /**
     * 翻面 = **同一张卡原位替换**，不是新增一行。
     *
     * 这正是用户点「合并此补丁」之后该看到的样子：卡片自己变成「已合并」，
     * 而不是列表里多出一条。
     */
    @Test
    fun flippingACardReplacesTheSameRowInsteadOfAppending() = runBlocking {
        val host = controller()
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!

        host.invokeCtl.broadcastBoardCard(groupId, "gpu-runner", cardOf("diff-t1", 1, "review_ready"))
        host.invokeCtl.broadcastBoardCard(groupId, "gpu-runner", cardOf("diff-t1", 1, "merged"))

        val rows = host.cardRows(groupId)
        assertEquals(1, rows.size, "同 cardId 必须原位替换，不许新增行")
        // 读 `renderCard`（= `cardPayload ?: card`）而不是 `card`：翻面写的是
        // `cardPayload`，`card` 是第一帧的锚，永远停在 review_ready。断言读错字段
        // 就会把「翻面成功」判成「没翻」。
        val card = assertNotNull(rows.single().renderCard)
        assertEquals("merged", (card["state"] as? JsonPrimitive)?.content, "状态要翻成 merged")
        // 传进来的 rev=1 不比既有大 ⇒ 广播侧 +1，避免翻面卡被旧帧盖回去。
        assertEquals(
            "2",
            (card["rev"] as? JsonPrimitive)?.content,
            "rev 必须单调推进（传 1、既有 1 ⇒ 落成 2）",
        )
    }
}
