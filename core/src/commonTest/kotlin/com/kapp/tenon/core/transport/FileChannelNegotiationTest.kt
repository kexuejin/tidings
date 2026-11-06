// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.transport

import com.kapp.tenon.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `fch1`（文件字节走加密信道）的**协商判据**（分级声明版，D-018 方案 b）。
 *
 * 这条能力的全部风险都在"只匹配一边"：发送端推字节、收端还在等它拨号拉取，
 * 症状是"对方说发了、这边永远卡在接收中"。所以判据必须是**双向对齐**的：
 * 本端数据面事实 × 对端声明 —— 分级后 self 侧不再是 caps 声明（fch1 停止广播，
 * 广播面被 Limits.CAPS=16 卡死，见 TieredChannelNegotiationMatrixTest 的 KDoc），
 * 而是调用方注入的**数据面事实**。
 */
class FileChannelNegotiationTest {

    private val peerWithFch1 = listOf(MsgTypes.CAP_FILE_CHANNEL)
    private val peerWithout = emptyList<String>()

    @Test
    fun channelAvailableWithFch1PeerEnablesDataPlane() {
        assertTrue(
            FileChannelNegotiation.canUseChannel(selfChannelAvailable = true, peerCaps = peerWithFch1),
            "本端数据面起了 + 对端声明 ⇒ 信道接管",
        )
    }

    @Test
    fun anySideMissingTheDataPlaneFallsBackToLegacy() {
        assertFalse(
            FileChannelNegotiation.canUseChannel(selfChannelAvailable = true, peerCaps = peerWithout),
            "对端没声明 ⇒ 必须回退 legacy（推过去它读不懂）",
        )
        assertFalse(
            FileChannelNegotiation.canUseChannel(selfChannelAvailable = false, peerCaps = peerWithFch1),
            "本端数据面没起 ⇒ 不许登记收字节（声明与现实不符的死锁教训）",
        )
        assertFalse(
            FileChannelNegotiation.canUseChannel(selfChannelAvailable = false, peerCaps = peerWithout),
        )
    }

    @Test
    fun fd1IsNotMistakenForFch1() {
        // 历史能力 vs 数据面能力是两件事：把 fd1 当 fch1 会让老端收到它读不懂的字节帧。
        val legacy = listOf(MsgTypes.CAP_FILE_DIRECT)
        assertFalse(
            FileChannelNegotiation.canUseChannel(selfChannelAvailable = true, peerCaps = legacy),
            "fd1 不是 fch1：只有 fd1 的对端解不动信道字节",
        )
    }

    @Test
    fun nullPeerCapsNeverEnablesTheDataPlane() {
        assertFalse(
            FileChannelNegotiation.canUseChannel(selfChannelAvailable = true, peerCaps = null),
            "对端还没握手（caps 未知）⇒ 不能猜它支持",
        )
    }
}
