// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import com.kapp.tidings.core.protocol.MsgTypes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `fch1`（文件字节走加密信道）的**协商判据**。
 *
 * 这条能力的全部风险都在"只声明一边"：发送端推字节、收端还在等它拨号拉取，
 * 症状是"对方说发了、这边永远卡在接收中"。所以判据必须是**双向**的，且默认 false。
 */
class FileChannelNegotiationTest {

    private fun caps(vararg c: String): com.kapp.tidings.core.protocol.Profile =
        com.kapp.tidings.core.protocol.Profile(
            nodeId = "p", nick = "p", company = "", dept = "", team = "", avatar = 1,
            profileRev = 1, host = "h", platform = "linux", tcpPort = 17879,
            ver = "1", caps = c.toList(),
        )

    @Test
    fun bothSidesDeclaringFch1EnablesChannelDataPlane() {
        val self = caps(MsgTypes.CAP_FILE_CHANNEL)
        val peer = caps(MsgTypes.CAP_FILE_CHANNEL)
        assertTrue(FileChannelNegotiation.canUseChannel(self, peer))
    }

    @Test
    fun oneSideMissingFch1FallsBackToLegacy() {
        val withCap = caps(MsgTypes.CAP_FILE_CHANNEL)
        val without = caps()
        assertFalse(FileChannelNegotiation.canUseChannel(withCap, without), "对端没声明 ⇒ 必须回退 legacy")
        assertFalse(FileChannelNegotiation.canUseChannel(without, withCap), "本端没声明 ⇒ 不许推字节")
        assertFalse(FileChannelNegotiation.canUseChannel(without, without))
    }

    @Test
    fun fd1IsNotMistakenForFch1() {
        // 历史能力 vs 数据面能力是两件事：把 fd1 当 fch1 会让老端收到它读不懂的字节帧。
        val legacy = caps(MsgTypes.CAP_FILE_DIRECT)
        val modern = caps(MsgTypes.CAP_FILE_CHANNEL)
        assertFalse(FileChannelNegotiation.canUseChannel(legacy, modern))
        assertFalse(FileChannelNegotiation.canUseChannel(modern, legacy))
    }
}
