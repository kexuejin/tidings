// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「同一端点只许一条连接」的选取半（[servedByOtherPeer]）。
 *
 * 真机现场（验证轮抓到）：registry 里同一台桌面挂着两条身份（现役 + 重装/迁移留下的
 * 旧 nodeId），两条的拨号目标指向**同一个** host:port —— 各拨一条，服务端按 hello 里的
 * nodeId 只认一个对端，另一条每次都被 `replaced` 顶掉，两端永远在"建连→被顶→重拨"里
 * 打转（桌面侧 closeCount 十几分钟涨 78）。去重后：先建连的身份占住端点，其余身份
 * 如实跳过（skipReason 记 `endpoint-served-by-*`），抖动源头消失。
 */
class LinkEndpointDedupTest {

    private fun target(peerId: String, host: String = "192.168.2.82", port: Int = 17879, secure: Boolean = false) =
        LinkClient.LinkTarget(peerId = peerId, host = host, port = port, pin = null, secure = secure)

    @Test
    fun sameEndpointHeldByAnotherPeerIsReported() {
        val established = mapOf("ghost-node" to endpointKeyOf("192.168.2.82", 17879, secure = false))
        assertEquals(
            "ghost-node",
            servedByOtherPeer(listOf(target("host-node")), established, selfPeerId = "host-node"),
            "端点被别的身份占住 ⇒ 必须报出占住者，拨号循环据此跳过",
        )
    }

    @Test
    fun differentPortOrHostIsNotTheSameEndpoint() {
        val established = mapOf("ghost-node" to endpointKeyOf("192.168.2.82", 17879, secure = false))
        assertNull(
            servedByOtherPeer(listOf(target("host-node", port = 17880, secure = true)), established, "host-node"),
            "不同端口/不同安全档 = 不同端点（NAT 后两台机器各转发一个口是合法形态）",
        )
        assertNull(
            servedByOtherPeer(listOf(target("host-node", host = "192.168.2.22")), established, "host-node"),
            "不同主机 = 不同端点",
        )
    }

    @Test
    fun ownEndpointDoesNotCount() {
        val established = mapOf("host-node" to endpointKeyOf("192.168.2.82", 17879, secure = false))
        assertNull(
            servedByOtherPeer(listOf(target("host-node")), established, selfPeerId = "host-node"),
            "自己占着自己的端点是正常状态（isConnected 那条路管它）",
        )
    }

    @Test
    fun tlsAndPlaintextOnSameHostPortAreDifferentEndpoints() {
        val established = mapOf("ghost-node" to endpointKeyOf("192.168.2.82", 17879, secure = false))
        assertNull(
            servedByOtherPeer(listOf(target("host-node", port = 17879, secure = true)), established, "host-node"),
            "同口不同协议是两个监听者（luciola 模式与发现端口同号不同协议就是这一形态）",
        )
    }
}
