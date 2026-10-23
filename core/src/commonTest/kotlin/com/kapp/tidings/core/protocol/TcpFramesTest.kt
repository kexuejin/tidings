// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TCP 控制帧的编解码护栏。
 *
 * 这个文件的存在理由是一个真实事故：六个帧类当初都写成
 * `val type: String = TcpFrameTypes.PULL`（带默认值），而 `TcpFrames` 用的是
 * `Codec.wireJson`（`encodeDefaults = false`）。于是 `encodeBody` 把 `type` 整条省略，
 * 对端 `decodeObject` 拿不到判别字段，直接判 `bad-frame-shape` 断链——
 * 现象是客户端报 `closed`，看起来像网络问题，实际是序列化配置问题。
 *
 * 所以这里逐帧断言：**每个帧编码出来都必须带正确的 `type`**，且能原样解回来。
 */
class TcpFramesTest {

    private val sha = "0".repeat(64)

    @Test
    fun everyControlFrameCarriesItsType() {
        val frames = listOf(
            PullFrame(
                type = TcpFrameTypes.PULL,
                from = "peer-1",
                transferId = "t-1",
                fileId = "f-1",
                offset = 0L,
            ) to TcpFrameTypes.PULL,
            PullOkFrame(type = TcpFrameTypes.PULL_OK, fileId = "f-1", len = 10L) to TcpFrameTypes.PULL_OK,
            DoneFrame(type = TcpFrameTypes.DONE, fileId = "f-1", sha256 = sha) to TcpFrameTypes.DONE,
            FinishFrame(type = TcpFrameTypes.FINISH, transferId = "t-1") to TcpFrameTypes.FINISH,
            ErrFrame(type = TcpFrameTypes.ERR, reason = "busy") to TcpFrameTypes.ERR,
            WaitFrame(type = TcpFrameTypes.WAIT) to TcpFrameTypes.WAIT,
        )
        for ((frame, expected) in frames) {
            val body = TcpFrames.encodeBody(frame)
            assertTrue(
                body.contains("\"type\":\"$expected\""),
                "帧 ${frame::class.simpleName} 的编码里没有 type 字段：$body",
            )
        }
    }

    @Test
    fun waitFrameIsNotAnEmptyObject() {
        // 曾经它真的是 `{}`——`type` 被 encodeDefaults=false 吞掉之后没有任何字段剩下。
        assertEquals("""{"type":"wait"}""", TcpFrames.encodeBody(WaitFrame(type = TcpFrameTypes.WAIT)))
    }

    @Test
    fun pullRoundTrips() {
        val decoded = TcpFrames.decodeBody(
            TcpFrames.encodeBody(
                PullFrame(
                    type = TcpFrameTypes.PULL,
                    from = "peer-1",
                    transferId = "t-1",
                    fileId = "f-1",
                    offset = 4096L,
                ),
            ),
        )
        val control = assertNotNull(decoded as? TcpFrameDecode.Control).frame
        assertEquals(TcpControlFrame.Pull("peer-1", "t-1", "f-1", 4096L), control)
    }

    @Test
    fun pullOkDoneFinishErrWaitRoundTrip() {
        assertEquals(
            TcpControlFrame.PullOk("f-1", 512L),
            (TcpFrames.decodeBody(TcpFrames.encodeBody(PullOkFrame(type = TcpFrameTypes.PULL_OK, fileId = "f-1", len = 512L))) as TcpFrameDecode.Control).frame,
        )
        assertEquals(
            TcpControlFrame.Done("f-1", sha),
            (TcpFrames.decodeBody(TcpFrames.encodeBody(DoneFrame(type = TcpFrameTypes.DONE, fileId = "f-1", sha256 = sha))) as TcpFrameDecode.Control).frame,
        )
        assertEquals(
            TcpControlFrame.Finish("t-1"),
            (TcpFrames.decodeBody(TcpFrames.encodeBody(FinishFrame(type = TcpFrameTypes.FINISH, transferId = "t-1"))) as TcpFrameDecode.Control).frame,
        )
        assertEquals(
            TcpControlFrame.Err("busy"),
            (TcpFrames.decodeBody(TcpFrames.encodeBody(ErrFrame(type = TcpFrameTypes.ERR, reason = "busy"))) as TcpFrameDecode.Control).frame,
        )
        assertEquals(
            TcpControlFrame.Wait,
            (TcpFrames.decodeBody(TcpFrames.encodeBody(WaitFrame(type = TcpFrameTypes.WAIT))) as TcpFrameDecode.Control).frame,
        )
    }

    @Test
    fun extraKeyRejectsTheWholeFrame() {
        // 对齐 TS `hasOnlyKeys`：多一个字段整帧丢弃，不能"宽容解析"。
        val rejected = TcpFrames.decodeBody("""{"type":"pull-ok","fileId":"f-1","len":1,"extra":true}""")
        assertEquals(TcpFrameDecode.Rejected(TcpRejectReason.BAD_FRAME_SHAPE), rejected)
    }

    @Test
    fun malformedJsonIsBadFrameJson() {
        assertEquals(
            TcpFrameDecode.Rejected(TcpRejectReason.BAD_FRAME_JSON),
            TcpFrames.decodeBody("{not json"),
        )
    }

    @Test
    fun missingTypeIsBadFrameShape() {
        assertEquals(
            TcpFrameDecode.Rejected(TcpRejectReason.BAD_FRAME_SHAPE),
            TcpFrames.decodeBody("""{"fileId":"f-1","len":1}"""),
        )
    }

    @Test
    fun msgFrameWrapsTheEnvelope() {
        val envelope = Envelope(
            v = Protocol.VERSION,
            type = MsgTypes.MSG,
            id = newId(),
            from = newId(),
            ts = 1_700_000_000_000L,
            payload = Codec.textPayload("hi"),
        )
        val body = TcpFrames.encodeMsgFrame(envelope)
        assertTrue(body.startsWith("""{"type":"msg","envelope":"""), body)
        val decoded = TcpFrames.decodeBody(body)
        val msg = assertNotNull(decoded as? TcpFrameDecode.Msg)
        assertEquals(envelope.id, msg.envelope.id)
    }
}
