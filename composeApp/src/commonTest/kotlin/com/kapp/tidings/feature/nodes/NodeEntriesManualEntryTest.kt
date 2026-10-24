// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.nodes

import com.kapp.tidings.domain.ManualPeerEntry
import com.kapp.tidings.domain.RelaySettings
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.manualPeerStatuses
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.ui.desktop.nodeEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 桌面宽档的节点列表装配：**已经握过手的手动地址**必须仍然带着它的手动记录。
 *
 * 现场：`DesktopNodesPane.nodeEntries` 过去把所有对端的 `manualEntry` 硬置 `null`，
 * 于是同一行在手机详情有「移除「X」」、在桌面右栏详情没有 —— 两个壳两副面孔，
 * 而两个壳共用同一个 `NodeDetailBody`（闸就是 `manualEntry != null`）。
 *
 * 反向验证：把 `nodeEntries` 的 `manualEntry = manualByNodeId[peer.nodeId]`
 * 改回 `manualEntry = null`，[handshakedManualPeerKeepsItsEntry] 与
 * [phoneAndDesktopAgreeOnTheManualEntry] 变红。
 */
class NodeEntriesManualEntryTest {

    private val manualEntry = ManualPeerEntry(host = "192.168.1.31", port = 17_878, name = "老周的旧笔记本")

    private fun peer(
        nodeId: String = "laozhou-node",
        ip: String = "192.168.1.31",
        udpPort: Int = 17_878,
        online: Boolean = true,
    ) = PeerUi(nodeId = nodeId, nick = "老周", ip = ip, udpPort = udpPort, online = online)

    private fun state(peers: List<PeerUi>, manual: List<ManualPeerEntry>) = RelayUiState(
        settings = RelaySettings(nodeId = "me", manualPeers = manual),
        peers = peers,
    )

    @Test
    fun handshakedManualPeerKeepsItsEntry() {
        val entries = nodeEntries(state(peers = listOf(peer()), manual = listOf(manualEntry)))
        val row = entries.single { it.nodeId == "laozhou-node" }
        assertEquals(manualEntry, row.manualEntry, "握过手不等于「不是我手动加的」——移除的语义要靠这条记录")
        assertTrue(row.manual)
    }

    @Test
    fun phoneAndDesktopAgreeOnTheManualEntry() {
        // 手机侧那份装配（manualEntriesByNodeId）与桌面侧同一个输入必须给同一个答案。
        val statuses = manualPeerStatuses(listOf(manualEntry), listOf(peer()))
        val phone = manualEntriesByNodeId(statuses)["laozhou-node"]
        val desktop = nodeEntries(state(peers = listOf(peer()), manual = listOf(manualEntry)))
            .single { it.nodeId == "laozhou-node" }.manualEntry
        assertEquals(phone, desktop)
        assertEquals(manualEntry, desktop)
    }

    @Test
    fun lanOnlyPeerHasNoRemovalRecord() {
        // 局域网扫到的对端没有手动记录：详情页因此不画「移除」（没有可删的东西）。
        val entries = nodeEntries(state(peers = listOf(peer(ip = "192.168.1.77")), manual = emptyList()))
        val row = entries.single()
        assertNull(row.manualEntry)
        assertEquals(false, row.manual)
    }

    @Test
    fun neverAnsweredManualAddressStillListsItsEntry() {
        // 从没接通过的地址在节点表里没有对应行，但它的记录必须在，否则「移除」无处可去。
        val entries = nodeEntries(state(peers = emptyList(), manual = listOf(manualEntry)))
        val row = entries.single()
        assertNull(row.nodeId)
        assertTrue(row.never)
        assertEquals(manualEntry, row.manualEntry)
    }

    @Test
    fun sameHostOnAnotherPortIsNotTheSameEntry() {
        // 同一台机器的两个端口是两条记录：端口不撞就不算「这一条」，否则移除会删错。
        val entries = nodeEntries(
            state(peers = listOf(peer(udpPort = 17_891)), manual = listOf(manualEntry)),
        )
        val peerRow = entries.single { it.nodeId == "laozhou-node" }
        assertNull(peerRow.manualEntry)
        assertTrue(entries.any { it.never }, "端口不撞的那条手动地址应当自己一行「没有回应」")
    }

    @Test
    fun manualEntriesByNodeIdDropsUnmatchedStatuses() {
        val statuses = manualPeerStatuses(
            entries = listOf(manualEntry, ManualPeerEntry(host = "192.168.1.77", port = 17_878)),
            peers = listOf(peer()),
        )
        val map = manualEntriesByNodeId(statuses)
        assertEquals(setOf("laozhou-node"), map.keys)
        assertEquals(manualEntry, map["laozhou-node"])
    }
}
