// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.core.protocol.EventRouteReasons
import com.kapp.tenon.domain.model.PeerUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 接入与通道页的投影判据。
 *
 * 这一页的每一条都不能撒谎，所以断言都压在「界面会不会比事实更满」上：
 * 读不到 pin 时**不许**画「未配对」，桥读不到时**不许**画空统计。
 */
class AccessChannelProjectionTest {

    private fun peer(
        nodeId: String,
        nick: String = nodeId,
        ip: String = "192.168.1.10",
        port: Int = 17878,
        online: Boolean = false,
        lastSeen: Long = 0,
    ) = PeerUi(
        nodeId = nodeId,
        nick = nick,
        ip = ip,
        udpPort = port,
        online = online,
        lastSeenAt = lastSeen,
    )

    /** 一档桥读数。默认值是「桥没来过」——测试要显式写明自己想表达的那一档。 */
    private fun bridgeUi(
        lastPollAt: Long = 0,
        addressed: Int = 0,
        resolved: Int = 0,
        causes: List<BridgeCauseUi> = emptyList(),
    ) = BridgeChannelUi(
        windowCapacity = 20,
        lastPollAt = lastPollAt,
        lastAddressedAt = if (addressed > 0) lastPollAt else 0,
        lastInjectableAt = if (resolved > 0) lastPollAt else 0,
        addressedCount = addressed,
        resolvedCount = resolved,
        unresolvedCount = addressed - resolved,
        causes = causes,
    )

    @Test
    fun selfIsNotANodeRow() {
        val ui = accessChannelUi(
            selfNodeId = "me",
            selfNick = "我的电脑",
            listeningPort = 17878,
            peers = listOf(peer("me"), peer("phone")),
            bridge = null,
            pins = null,
        )
        assertEquals(listOf("phone"), ui.nodes.map { it.nodeId })
        assertEquals("我的电脑", ui.selfNick)
        assertEquals(17878, ui.listeningPort)
    }

    @Test
    fun onlineFirstThenMostRecentlySeen() {
        val ui = accessChannelUi(
            selfNodeId = "me",
            selfNick = "me",
            listeningPort = 17878,
            peers = listOf(
                peer("old-online", online = true, lastSeen = 100),
                peer("offline-recent", online = false, lastSeen = 900),
                peer("new-online", online = true, lastSeen = 800),
            ),
            bridge = null,
            pins = null,
        )
        assertEquals(
            listOf("new-online", "old-online", "offline-recent"),
            ui.nodes.map { it.nodeId },
            "在线优先；同档按最近见到排 —— 不跟发现顺序走（重启一次就变）",
        )
    }

    @Test
    fun addressIsOmittedRatherThanFakedWhenUnknown() {
        val ui = accessChannelUi(
            selfNodeId = "me",
            selfNick = "me",
            listeningPort = 17878,
            peers = listOf(peer("p", ip = "")),
            bridge = null,
            pins = null,
        )
        assertEquals("", ui.nodes.single().address, "不知道地址就留空，不能拼出 :0 这种像地址的东西")
    }

    @Test
    fun pinPrefixOfFormatsWirePinsAndRejectsGarbage() {
        assertEquals(
            "AB:CD:EF:01",
            pinPrefixOf("sha256:abcdef0100" + "00".repeat(20)),
            "wire 形态取前 4 字节，大写冒号分隔",
        )
        assertEquals("AB:CD:EF:01", pinPrefixOf("abcdef01"), "存量裸十六进制也认（不猜内容，只认字符集）")
        assertNull(pinPrefixOf(null))
        assertNull(pinPrefixOf(""))
        assertNull(pinPrefixOf("abc"), "短于 4 字节的指纹没有区分力，宁可说没有")
        assertNull(pinPrefixOf("sha256:zzzzzzzz"), "不是十六进制就不画 —— 错的指纹比没有更坏")
    }

    @Test
    fun unpairedIsOnlyClaimedWhenTheHostWasActuallyReadable() {
        val peers = listOf(peer("phone"))
        // 端口为 null（手机壳）：连「未配对」都不许说 —— 那是台式机信任库的结论。
        val unreadable = accessChannelUi("me", "me", 17878, peers, bridge = null, pins = null)
        assertNull(unreadable.pairedCount)
        assertNull(unreadable.nodes.single().pinPrefix)

        // 读到了、信任库是空的：这才是「未配对」。
        val readable = accessChannelUi("me", "me", 17878, peers, bridge = null, pins = { null })
        assertEquals(0, readable.pairedCount)
        assertNull(readable.nodes.single().pinPrefix)
    }

    @Test
    fun pairedCountAndRowPrefixComeFromTheSameTable() {
        val ui = accessChannelUi(
            selfNodeId = "me",
            selfNick = "me",
            listeningPort = 17878,
            peers = listOf(peer("phone"), peer("tablet")),
            bridge = null,
            pins = { nodeId -> if (nodeId == "phone") "sha256:11223344" else null },
        )
        assertEquals(1, ui.pairedCount)
        assertEquals("11:22:33:44", ui.nodes.first { it.nodeId == "phone" }.pinPrefix)
        assertNull(ui.nodes.first { it.nodeId == "tablet" }.pinPrefix)
    }

    @Test
    fun pairedCountFollowsTheRowItCounts() {
        // 反向探针：合计改回 `pin.isNotBlank()`，这条必红。
        // 形态不认识的 pin 在行上说「未配对」，合计就不能说「已配对 1 台」。
        val ui = accessChannelUi(
            selfNodeId = "me",
            selfNick = "me",
            listeningPort = 17878,
            peers = listOf(peer("phone"), peer("tablet")),
            bridge = null,
            pins = { nodeId -> if (nodeId == "phone") "not-a-pin" else null },
        )
        assertNull(ui.nodes.first { it.nodeId == "phone" }.pinPrefix)
        assertEquals(0, ui.pairedCount, "合计与行必须同源 —— 同一页两处口径不一致时用户只信更乐观的那个")
    }

    @Test
    fun emptyBridgeIsADistinctStateFromMissingBridge() {
        val empty = bridgeUi(lastPollAt = 0, addressed = 0)
        assertTrue(empty.isEmpty, "读到了但没判定过 ⇒ isEmpty，页面画空态而不是 0/0/0")
        val missing = accessChannelUi("me", "me", 17878, emptyList(), bridge = null, pins = null)
        assertNull(missing.bridge, "读不到就是 null，与 isEmpty 分开")
    }

    @Test
    fun bridgeBlockTellsNoPollApartFromNoMessage() {
        // 三档必须分开，因为页面要说三句不同的话：
        //   「桥从来没取过件」= 故障（去查它跑没跑）；
        //   「取过件但没有 @ 消息」= 正常空态（等消息）；
        //   「有判定」= 给统计与成因。
        //
        // **反向探针**：把 NeverPolled 折进 Empty（只判 isEmpty），第一条断言必红。
        assertEquals(
            BridgeBlockState.NeverPolled,
            bridgeBlockStateOf(bridgeUi(lastPollAt = 0, addressed = 0)),
            "没取过件是故障态，不是「还没有消息」",
        )
        assertEquals(
            BridgeBlockState.Empty,
            bridgeBlockStateOf(bridgeUi(lastPollAt = 5_000, addressed = 0)),
            "取过件但一条 @ 都没有 —— 这才是正常空态",
        )
        assertEquals(
            BridgeBlockState.Judged,
            bridgeBlockStateOf(bridgeUi(lastPollAt = 5_000, addressed = 3)),
        )
    }

    @Test
    fun pairingStateNeverSaysUnpairedWithoutAReadableTrustStore() {
        // **反向探针**：把 Unknown 一支删掉（退回「非空即已配对、否则未配对」），
        // 第三条断言必红。
        assertEquals(PairingState.Paired, pairingStateOf("AB:CD:EF:01", trustStoreReadable = true))
        assertEquals(PairingState.Unpaired, pairingStateOf(null, trustStoreReadable = true))
        assertEquals(
            PairingState.Unknown,
            pairingStateOf(null, trustStoreReadable = false),
            "读不到信任库 ≠ 没配过 —— 手机壳说「未配对」就是给界面装一盏反向的假红灯",
        )
    }

    @Test
    fun routingHidesNormalPathsButNeverDropsAnUnknownToken() {
        val ui = accessChannelUi(
            selfNodeId = "me",
            selfNick = "me",
            listeningPort = 17878,
            peers = emptyList(),
            bridge = null,
            pins = null,
            routingCounts = mapOf(
                EventRouteReasons.DISPATCH to 12L,
                EventRouteReasons.DUPLICATE_DELIVERY to 3L,
                EventRouteReasons.NO_CONV_BOUND to 5L,
                EventRouteReasons.AMBIGUOUS_GROUP to 2L,
                "some-token-from-a-newer-host" to 1L,
            ),
        )
        assertEquals(
            listOf(
                EventRouteReasons.NO_CONV_BOUND,
                EventRouteReasons.AMBIGUOUS_GROUP,
                "some-token-from-a-newer-host",
            ),
            ui.routing?.causes?.map { it.reason },
            "正常路径（投递成功/去重）不列，否则真问题被淹；未知 token 必须留下 —— " +
                "界面看不见的那一档恰恰是「宿主比界面新」的证据",
        )
    }

    @Test
    fun routingUnreadableIsNotTheSameAsNoRoutingProblem() {
        val missing = accessChannelUi(
            "me", "me", 17878, emptyList(), bridge = null, pins = null, routingCounts = null,
        )
        assertNull(missing.routing, "读不到 ⇒ null，页面只能闭嘴")

        val clean = accessChannelUi(
            "me", "me", 17878, emptyList(), bridge = null, pins = null, routingCounts = emptyMap(),
        )
        assertTrue(clean.routing?.isEmpty == true, "读到了、一条都没拦下 ⇒ 这是个好消息，可以说出来")

        // 只有正常路径时也是「干净」：别让 dispatch 把 isEmpty 顶成 false。
        val onlyNormal = accessChannelUi(
            "me", "me", 17878, emptyList(), bridge = null, pins = null,
            routingCounts = mapOf(EventRouteReasons.DISPATCH to 9L),
        )
        assertTrue(onlyNormal.routing?.isEmpty == true, "成功投递不算成因 —— 否则页面永远显示「有事件没进群」")
    }

    @Test
    fun routingCausesSortByCountThenByVocabularyOrder() {
        // 五档全给同一次数：输出必须逐字等于 BLOCKING 的次序（最该被看见的在前）。
        val tie = EventRouteReasons.BLOCKING.associateWith { 4L }
        val ui = accessChannelUi(
            "me", "me", 17878, emptyList(), bridge = null, pins = null, routingCounts = tie,
        )
        assertEquals(
            EventRouteReasons.BLOCKING,
            ui.routing?.causes?.map { it.reason },
            "同次数按词表次序；若这里跟着 map 的插入序走，页面上「哪个更严重」就变成了运气",
        )
    }
}
