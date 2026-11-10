// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.transport

/**
 * 手动节点：跨网段 / VPN（Tailscale、ZeroTier）/ 本机联调时直填 ip:port。
 *
 * **`port` 是 UDP 口，不是 TCP 口。** 发现层全程只走 UDP（entry / alive / presence / exit），
 * 这个 port 最终落到 `transport.send(bytes, host, port)`，也就是 `DatagramSocket.send`。
 *
 * 写成 TCP 口不会有任何报错：桌面茶话间是 `TCP 17879` + `UDP 17878` 两个不同的监听，
 * 往只开了 TCP 的 17879 发 UDP，内核回一个 ICMP port unreachable，而未 connect 的
 * `DatagramSocket` 收下这个错误后只是让**下一次** receive 失败 —— 发送侧毫无感觉，
 * 接收侧根本没人在 UDP 上听，于是**对端一声不吭**，与"网络不通"完全无法区分。
 * 本机联调最容易踩：顺手把旁边那个正在 LISTEN 的 TCP 端口填进来。
 *
 * （原来与 legacy `Discovery` 同文件；广播发现删除后它独立成档 —— 它描述的是
 * "用户填的地址便条"，与传输形态无关，心跳与告别帧都还要按它直发。）
 */
data class ManualPeer(val host: String, val udpPort: Int)
