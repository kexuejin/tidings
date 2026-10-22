// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **群元数据补发的去重判据 —— 它是「补发不会变成无限循环」的唯一保障。**
 *
 * ## 真机事故
 *
 * 第一版补发没有去重，直接挂在「在线对端集合」的状态流上。而那个集合**真的会抖**
 * （`/v1/peers` 连查三次，在 `[A,B]` 与 `[A,B,C,D]` 之间来回跳，因为对端的 `lastSeen`
 * 卡在离线阈值附近）—— `distinctUntilChanged()` 因此每次都放行，补发自己触发下一轮。
 *
 * 现场读数：每 15 秒一轮，队列在几分钟内涨到 **286 条** `group.info` 帧，全是同一个 rev。
 * 用户那边看到的是桌面端一直占着网络、手机上却仍然没有 agent。
 */
class GroupMetaRebroadcastDedupeTest {

    /** 台账里全没记过 ⇒ 所有目标都要发。 */
    @Test
    fun everyTargetIsFreshWhenNothingWasSentYet() {
        val fresh = freshRecipients(
            targets = listOf("pixel", "redmi"),
            groupId = "g-1",
            rev = 7,
            hasAgents = true,
            alreadySent = { null },
            alreadySentHadAgents = { null },
        )
        assertEquals(listOf("pixel", "redmi"), fresh)
    }

    /**
     * **内容升过级就要再发一次** —— 这是红米那个现场的直接判据。
     *
     * 红米收到过 100 条 group.info，一条都不带 agent 声明（修复前编的那份），
     * 而台账记着「rev 7 已发」⇒ 永远不再补。同一个 rev 的内容是可以变的：
     * 解码修复前编出来的那份缺字段，修复后编出来的有。
     */
    @Test
    fun anUpgradedPayloadIsSentAgainOnTheSameRevision() {
        val fresh = freshRecipients(
            targets = listOf("redmi"),
            groupId = "g-1",
            rev = 7,
            hasAgents = true,                      // 这次带声明
            alreadySent = { 7 },                   // 发过 rev 7
            alreadySentHadAgents = { false },      // 但那次不带声明
        )
        assertEquals(
            listOf("redmi"),
            fresh,
            "上次发的不带 agent 声明、这次带 ⇒ 必须再发一次，否则那台设备永远拿不到",
        )
    }

    /** 内容与上次完全一样（同 rev、同带声明）⇒ 不重发，防循环。 */
    @Test
    fun anIdenticalPayloadIsNotSentTwice() {
        val fresh = freshRecipients(
            targets = listOf("pixel"),
            groupId = "g-1",
            rev = 7,
            hasAgents = true,
            alreadySent = { 7 },
            alreadySentHadAgents = { true },
        )
        assertEquals(emptyList(), fresh)
    }

    /** **同一个 rev 已经发过 ⇒ 不再发**（这一条挡住那个循环）。 */
    @Test
    fun anAlreadySentRevisionIsSkipped() {
        val sent = mapOf("pixel" to 7)
        val fresh = freshRecipients(
            targets = listOf("pixel", "redmi"),
            groupId = "g-1",
            rev = 7,
            hasAgents = true,
            alreadySent = { sent[it] },
            alreadySentHadAgents = { true },
        )
        assertEquals(listOf("redmi"), fresh, "同 rev 同内容重复发就是那个 286 帧循环的入口")
    }

    /** 群元数据真的更新了（rev 更大）⇒ 要重新补。 */
    @Test
    fun aNewerRevisionIsSentAgain() {
        val sent = mapOf("pixel" to 7)
        val fresh = freshRecipients(
            targets = listOf("pixel"),
            groupId = "g-1",
            rev = 8,
            hasAgents = true,
            alreadySent = { sent[it] },
            alreadySentHadAgents = { true },
        )
        assertEquals(listOf("pixel"), fresh)
    }

    /**
     * **台账按「群 × 对端」分开**：给 A 群发过 rev 7，不代表 B 群的 rev 7 也发过。
     *
     * 这一条防的是「用一个全局整数当游标」那种写法 —— 多群场景下会漏发。
     */
    @Test
    fun theLedgerIsScopedPerGroupAndPeer() {
        val ledger = mutableMapOf<Pair<String, String>, Int>()
        fun fresh(group: String) = freshRecipients(
            targets = listOf("pixel"),
            groupId = group,
            rev = 7,
            hasAgents = true,
            // **台账必须按 (对端, 群) 查** —— 写成固定 g-1 就等于没有分群，
            // 那正是这一条要防的漏发。
            alreadySent = { member -> ledger[member to group] },
            alreadySentHadAgents = { true },
        )
        assertEquals(listOf("pixel"), fresh("g-1"), "没发过，要发")
        ledger["pixel" to "g-1"] = 7
        assertEquals(emptyList(), fresh("g-1"), "发了同 rev 同内容，不再发")
        assertEquals(listOf("pixel"), fresh("g-2"), "别的群没发过，仍要发")
    }
}
