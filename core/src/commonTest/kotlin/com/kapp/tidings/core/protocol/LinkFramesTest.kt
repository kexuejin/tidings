// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 长连接帧（K1）：形状、严格白名单、以及"旧端按未知类型拒绝"。
 *
 * 这三条都是跨端契约：多一个键就整帧拒（与 group-text 同一条纪律），旧端收到 link* 必须
 * 落到 Rejected 而不是崩溃 —— 否则加了长连接之后老对端会开始报错。
 */
class LinkFramesTest {

    @Test
    fun everyLinkFrameRoundTripsThroughTheTcpFrameDecoder() {
        val frames = listOf(
            LinkFrame.Hello("node-phone"),
            LinkFrame.Ok,
            LinkFrame.Deny(LinkFrames.REASON_NOT_LAN),
            LinkFrame.Ping,
            LinkFrame.Pong,
        )
        for (frame in frames) {
            val body = LinkFrames.body(frame)
            val decoded = TcpFrames.decodeBody(body)
            assertTrue(decoded is TcpFrameDecode.Link, "必须是 Link 分支：$body -> $decoded")
            assertEquals(frame, (decoded as TcpFrameDecode.Link).frame)
        }
    }

    @Test
    fun helloCarriesTheNodeIdAndVersion() {
        val body = LinkFrames.body(LinkFrame.Hello("node-phone"))
        assertEquals("""{"type":"link","nodeId":"node-phone","ver":1}""", body)
    }

    @Test
    fun anExtraKeyIsRejectedInsteadOfIgnored() {
        val decoded = TcpFrames.decodeBody("""{"type":"link","nodeId":"n","ver":1,"extra":1}""")
        assertTrue(decoded is TcpFrameDecode.Rejected, "多一个键必须整帧拒：$decoded")
    }

    @Test
    fun anOldPeerSeesLinkFramesAsRejectedNotAsACrash() {
        // 旧端没有 link 分支 —— 它的 else 会返回 Rejected。这条断言的是"未知类型不崩"。
        val decoded = TcpFrames.decodeBody("""{"type":"link-ping"}""")
        assertTrue(decoded is TcpFrameDecode.Link)
        val unknown = TcpFrames.decodeBody("""{"type":"link-future"}""")
        assertTrue(unknown is TcpFrameDecode.Rejected)
    }
}
