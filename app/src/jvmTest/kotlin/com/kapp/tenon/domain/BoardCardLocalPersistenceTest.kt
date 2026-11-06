// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.ConvId
import com.kapp.tenon.platform.InMemoryAppPlatform
import com.kapp.tenon.testutil.awaitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    private fun controller(messageStore: MessageStore = InMemoryMessageStore()): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = 0, tcpPort = 0,
                persistMessages = false, persistMessagesDecided = true,
            ),
        ),
        messageStore = messageStore,
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    /** 带台账的内存存储：模拟 SQLite 的 BoardCardRev 行为（内存线程之外 rev 仍存活）。 */
    private class LedgerStore : MessageStore by InMemoryMessageStore() {
        val ledger = mutableMapOf<String, Int>()
        override fun loadBoardCardRev(cardId: String): Int? = ledger[cardId]
        override fun saveBoardCardRev(cardId: String, rev: Int) {
            ledger[cardId] = rev
        }
    }

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

    /**
     * **判定帧与终态翻面帧不许共用同一个 rev。**
     *
     * 真机现场（2026-11-03，D-NEW-1 复验）：点「合并此补丁」后宿主同时发出两张同 cardId
     * 的卡 —— `broadcastDecision` 的「已由…处理」帧（rev 5→6）与合并失败的如实翻面帧
     * （也从 rev 5 算出 6）。手机端只认**严格递增**的 rev，后到的诚实翻面被当旧帧丢掉：
     * 宿主日志写着 `patch merge FAILED`，用户面前的卡却永远停在「待合并」。
     *
     * 判据两条，缺一都不够：
     * 1. 终态翻面**最后落库**（rev 3 > 判定帧的 2）—— 否则手机停在中间态；
     * 2. 每一次推进都严格 +1（1 → 2 → 3），不是两次写同一个号。
     */
    @Test
    fun theDecisionFrameAndTheTerminalFlipNeverShareARev() = runBlocking {
        val host = controller()
        val groupId = host.createGroup("mesh", listOf(phoneNode))!!
        val self = host.state.value.selfNodeId
        host.invokeCtl.broadcastBoardCard(groupId, "gpu-runner", cardOf("diff-t1", 1, "review_ready"))

        // 宿主的动作处理：与 DesktopExtension 同形 —— 在回调里异步补一张终态翻面卡。
        host.invokeCtl.onHostCardAction = { _, _, cardId, _, _ ->
            scope.launch {
                host.invokeCtl.broadcastBoardCard(groupId, "gpu-runner", cardOf(cardId, 1, "blocked"))
            }
        }
        host.invokeCtl.handleHostCardAction(groupId, "gpu-runner", "diff-t1", "merge-patch", self)

        awaitUntil("终态翻面压在判定帧之上", timeoutMs = 5_000) {
            (host.cardRows(groupId).singleOrNull()?.renderCard?.get("state") as? JsonPrimitive)?.content == "blocked"
        }
        val finalCard = assertNotNull(host.cardRows(groupId).single().renderCard)
        assertEquals(
            "blocked",
            (finalCard["state"] as? JsonPrimitive)?.content,
            "终态翻面必须压在判定帧之上（否则用户停在「已由…处理」的中间态）",
        )
        assertEquals(
            "3",
            (finalCard["rev"] as? JsonPrimitive)?.content,
            "rev 必须严格递增（1 → 判定帧 2 → 终态翻面 3）；同号会让手机丢掉后到的诚实翻面",
        )
        host.stop()
    }

    /**
     * **重启后 rev 延续不依赖内存窗口**（root cause #3，2026-11-03 记账）。
     *
     * 现场：广播侧的 rev 下限从前只看内存里的卡片槽位 —— 槽位来自启动恢复的
     * 200 条窗口（[MessageStore.MESSAGE_PAGE_SIZE]）加「恢复已跑完」的时机。重启后
     * 卡片行落在窗口之外时 `existingSlot == null`，调用方传入的低 rev 原样放行；
     * 手机端只认 `rev > existingRev` 的严格递增 ⇒ 低 rev 帧被当旧帧**静默丢弃**：
     * 卡面永远停在旧状态，宿主侧连一行失败日志都没有。
     *
     * 判据：rev 延续必须以**落库台账**（`loadBoardCardRev`）为事实源 —— 台账与
     * 消息窗口无关，重启后（同一库、全新内存流）依然把 rev 顶到「台账 + 1」。
     */
    @Test
    fun revContinuesAcrossARestartEvenWhenTheCardRowLeftTheRestoreWindow() = runBlocking {
        val ledger = LedgerStore()
        val before = controller(messageStore = ledger)
        val groupId = before.createGroup("mesh", listOf(phoneNode))!!
        for (state in listOf("review_ready", "running", "review_ready")) {
            before.invokeCtl.broadcastBoardCard(groupId, "gpu-runner", cardOf("diff-t1", 1, state))
        }
        assertEquals(3, ledger.ledger["diff-t1"], "前提：台账记到了广播到的最高 rev")
        before.stop()

        // 「重启」：同一个库（台账共享），一块全新的内存消息流 —— 卡片行不在任何
        // 恢复窗口里，同卡广播在这里看起来就像首帧。
        val after = controller(messageStore = ledger)
        val groupIdAfter = after.createGroup("mesh", listOf(phoneNode))!!
        after.invokeCtl.broadcastBoardCard(groupIdAfter, "gpu-runner", cardOf("diff-t1", 1, "merged"))

        val card = assertNotNull(after.cardRows(groupIdAfter).single().renderCard)
        assertEquals("merged", (card["state"] as? JsonPrimitive)?.content)
        assertEquals(
            "4",
            (card["rev"] as? JsonPrimitive)?.content,
            "重启后传入 rev=1 也必须落到台账+1=4；落成 1 手机就会判旧帧丢弃",
        )
        after.stop()
    }
}
