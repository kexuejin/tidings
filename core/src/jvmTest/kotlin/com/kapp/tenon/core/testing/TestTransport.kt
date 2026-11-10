// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.testing

import com.kapp.tenon.core.transport.DatagramTransport
import com.kapp.tenon.core.transport.PeerRegistry
import com.kapp.tenon.core.transport.UdpTransport

/**
 * 测试用的传输工厂：真实 UDP 回环。
 *
 * ## 为什么测试还要写这一行
 *
 * `NodeRuntime` 的 `messengerTransportFactory` 已经收成**必需参数**（luciola 是唯一传输形态，
 * 默认的 `?: UdpTransport(...)` 回退已删）。测试不再能"什么都不传就得到一条明文 UDP 通路"，
 * 必须自己声明用什么传输 —— 这正是那次改动的目的：没有隐式回退。
 *
 * 这里用 [UdpTransport] 是有意的：这批测试验的是**协议行为**（消息、群、卡片、队列），
 * 不是传输形态。它们要的是一条能真发真收的通路，回环 UDP 最贴近生产语义且不需要
 * 原生库。luciola 的可信性由 `LuciolaSecureChannelTest` 等专门用例覆盖。
 *
 * @param port 0 = 由系统分配（默认，避免并行用例抢端口）。
 * @param host 绑定地址；只在本机内验时用 `127.0.0.1`。
 */
internal fun udpTestTransport(
    port: Int = 0,
    host: String = "127.0.0.1",
): (PeerRegistry) -> DatagramTransport = { _ -> UdpTransport(port, host) }

