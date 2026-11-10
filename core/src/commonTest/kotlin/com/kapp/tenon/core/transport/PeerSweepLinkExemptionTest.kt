// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.Profile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **长连接活着就不该被判离线**。
 *
 * 真机读数（2026-11-10 16:5x，Pixel 6）：`/v1/peers` 同时返回
 * `linkConnected=true, linkCarrier=plaintext` 与 `online=false, lastSeen=114.9s 前`。
 * 界面据此挂「宿主设备睡眠 · 命令已安全排队挂起」横幅，点「接收」不发出，
 * 补发队列 `left=44` 只涨不降，用户只能重启 App（触发一次 announce）才恢复。
 *
 * 两条断言合起来才成立：豁免**真的**生效（第一条），且不是「sweep 干脆不翻面」
 * （第二条，把豁免拿去之后同一个 peer 必须翻）。
 */
class PeerSweepLinkExemptionTest {

    private fun profile(nodeId: String) = Profile(
        nodeId = nodeId,
        nick = nodeId,
        company = "",
        dept = "",
        team = "",
        avatar = 0,
        profileRev = 1,
        host = "h",
        platform = "linux",
        tcpPort = 17879,
        ver = "test",
        caps = listOf("msg.text"),
    )

    @Test
    fun sweepSparesPeersWhoseLinkIsStillUp() = runBlocking {
        val registry = PeerRegistry("self")
        val peerId = "linked-peer-0001"
        registry.touch(peerId, "10.0.0.5", 17878, profile(peerId), confirmed = true)
        // offlineAfter = 1ms：sleep 一下让 lastSeen 真的过期（同一毫秒内两次 sweep
        // 会让「没翻」看起来像豁免生效 —— 那是一次假绿）。
        delay(5)

        // offlineAfter = 1ms：不用等，这一次 sweep 必然把它列进候选。
        val flippedWithLink = registry.sweep(1L) { id -> id == peerId }
        assertTrue(flippedWithLink.isEmpty(), "有活跃长连接的节点不该被翻成离线")
        assertTrue(
            registry.get(peerId)?.online == true,
            "豁免必须体现在记录上，而不是只体现在返回值里",
        )

        // 反向探针：同一条记录、同一次 sweep，去掉豁免就必须翻 —— 否则说明
        // 「没翻」是因为 sweep 根本没跑，而不是豁免生效。
        val flippedWithoutLink = registry.sweep(1L)
        assertEquals(listOf(peerId), flippedWithoutLink, "没有长连接时该节点必须被翻成离线")
        assertFalse(registry.get(peerId)?.online == true, "翻面后记录必须如实说离线")
    }

    // 「上界」这一条**不做单测**：上一版给豁免加过 10 分钟上界，真机上它造出了
    // `[sweep] offline 46c23456 idle=600106ms link=true` 这种假离线（App 空闲十分钟
    // 就挂「宿主设备睡眠」）。伪造 10 分钟需要可注入时钟，而 `PeerRegistry` 没有 ——
    // 与其写一条用 1ms 冒充 10 分钟的假断言，不如交给真机判据
    // `peer-idle-stays-online`（读 link.json + `[sweep] offline` 日志）去守。
}
