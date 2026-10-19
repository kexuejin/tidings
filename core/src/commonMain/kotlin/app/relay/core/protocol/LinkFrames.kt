// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.builtins.serializer

/**
 * 长连接（K1）的握手与心跳帧 —— 见 docs/cross-network-keepalive-design.md。
 *
 * 为什么复用 TCP 的 4 字节长度前缀 + JSON：长连接只是"又一条字节流"，上面跑的仍是
 * 现有帧格式，于是 msg / msg-ack / 去重 / 队列全都不用动（文档 §2）。
 *
 * 旧端收到 `link*` 会按未知类型拒绝（TcpFrames.decodeObject 的 else 分支），不影响它们。
 */
sealed interface LinkFrame {
    /** 手机 → 主机：我是谁、协议版本。主机据此鉴权（必须是已知/授信节点）。 */
    data class Hello(val nodeId: String, val ver: Int = LinkFrames.VERSION) : LinkFrame

    /** 主机 → 手机：接受，可以开始用了。 */
    data object Ok : LinkFrame

    /** 主机 → 手机：拒绝（未授信 / 非局域网明文 / 连接数满）。 */
    data class Deny(val reason: String) : LinkFrame

    /** 应用层心跳：15s 一发；45s 无任何字节判死（NAT 映射常见 30–120s 回收）。 */
    data object Ping : LinkFrame

    data object Pong : LinkFrame
}

object LinkFrames {
    const val VERSION: Int = 1

    /** 拒绝原因（诊断用；对端只看"拒了"这件事，不做分支）。 */
    const val REASON_UNKNOWN_PEER: String = "unknown-peer"
    const val REASON_NOT_LAN: String = "not-lan"

    /** 帧体（不含长度前缀）。key 顺序固定，便于逐字节比对。 */
    fun body(frame: LinkFrame): String = when (frame) {
        is LinkFrame.Hello ->
            """{"type":"${TcpFrameTypes.LINK}","nodeId":${json(frame.nodeId)},"ver":${frame.ver}}"""
        LinkFrame.Ok -> """{"type":"${TcpFrameTypes.LINK_OK}"}"""
        is LinkFrame.Deny ->
            """{"type":"${TcpFrameTypes.LINK_DENY}","reason":${json(frame.reason)}}"""
        LinkFrame.Ping -> """{"type":"${TcpFrameTypes.LINK_PING}"}"""
        LinkFrame.Pong -> """{"type":"${TcpFrameTypes.LINK_PONG}"}"""
    }

    /** 完整帧字节：4 字节大端长度前缀 + JSON 体。 */
    fun encode(frame: LinkFrame): ByteArray = TcpFrame.encode(body(frame).encodeToByteArray())

    private fun json(value: String): String = Codec.wireJson.encodeToString(String.serializer(), value)
}
