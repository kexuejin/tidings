// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.domain

import com.kapp.tenon.core.transport.DatagramTransport
import com.kapp.tenon.core.transport.PeerRegistry
import com.kapp.tenon.core.transport.UdpTransport

/**
 * 测试用传输工厂：真实 UDP 回环。
 *
 * `NodeRuntime.messengerTransportFactory` 已是必需参数（luciola 是唯一传输形态，legacy
 * 回退已删），所以每个构造点都必须显式声明走什么。app 这层测的是**控制器行为**
 * （群、审批、注入），不是传输形态 —— 回环 UDP 最贴近生产语义。
 */
internal fun udpTestTransport(
    port: Int = 0,
    host: String = "127.0.0.1",
): (PeerRegistry) -> DatagramTransport = { _ -> UdpTransport(port, host) }

