// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 分级声明（D-018 方案 b）后的协商判据矩阵：**本端数据面事实 × 对端声明**四组合。
 *
 * 为什么 self 侧从「caps 声明」改成「数据面事实」：桌面 caps 16/16 满额（Limits.CAPS=16），
 * luciola-on 时 fch1/scr1 把广播撑到 18 项 ⇒ 对端整份拒收 profile（task-10 实锤，
 * 存量自拒自 56688102 起）。fch1/scr1 停止广播后，本端「能不能走信道」由**本地事实**
 * （luciola 数据面是否真的起来了）承担 —— 判据的远端一半（对端声明 fch1/scr1）不变。
 * 两侧语义分开说清：
 *  - 对端声明 = 「它解得动这个数据面」；
 *  - 本端事实 = 「我这条数据面真的起来了」（开关 + 绑定成功，不经广播）。
 *
 * 矩阵（luciola-on/off × 对端新 caps/旧 caps）：
 *  on  + 对端有 ⇒ 接管；on  + 对端无 ⇒ legacy（对端解不动）；
 *  off + 对端有 ⇒ legacy（本端数据面没起）；off + 对端无 ⇒ legacy。
 */
class TieredChannelNegotiationMatrixTest {

    private val peerWithFch1 = listOf(MsgTypes.CAP_MOBILE_TEXT, MsgTypes.CAP_FILE_CHANNEL)
    private val peerLegacy = listOf(MsgTypes.CAP_MOBILE_TEXT)

    @Test
    fun luciolaOnWithFch1PeerTakesOver() {
        assertTrue(
            FileChannelNegotiation.canUseChannel(
                selfChannelAvailable = true,
                peerCaps = peerWithFch1,
            ),
            "on × 新：数据面起了 + 对端声明 ⇒ 信道接管",
        )
    }

    @Test
    fun luciolaOnWithLegacyPeerFallsBack() {
        assertFalse(
            FileChannelNegotiation.canUseChannel(
                selfChannelAvailable = true,
                peerCaps = peerLegacy,
            ),
            "on × 旧：对端解不动 ⇒ legacy（对端解不动是它的事实，不能推字节过去）",
        )
    }

    @Test
    fun luciolaOffWithFch1PeerFallsBack() {
        assertFalse(
            FileChannelNegotiation.canUseChannel(
                selfChannelAvailable = false,
                peerCaps = peerWithFch1,
            ),
            "off × 新：本端数据面没起 ⇒ legacy（声明与现实不符的那次死锁教训）",
        )
    }

    @Test
    fun luciolaOffWithLegacyPeerFallsBack() {
        assertFalse(
            FileChannelNegotiation.canUseChannel(
                selfChannelAvailable = false,
                peerCaps = peerLegacy,
            ),
            "off × 旧：双 legacy，原路径",
        )
    }

    @Test
    fun nullPeerCapsAlwaysFallsBack() {
        assertFalse(
            FileChannelNegotiation.canUseChannel(selfChannelAvailable = true, peerCaps = null),
        )
    }
}
