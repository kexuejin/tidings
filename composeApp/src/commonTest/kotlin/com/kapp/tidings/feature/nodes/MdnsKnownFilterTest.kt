// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.nodes

import com.kapp.tidings.domain.ManualPeerEntry
import com.kapp.tidings.domain.model.MdnsServiceUi
import com.kapp.tidings.domain.model.PeerUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * mDNS 服务与「已经认识这台设备」的**两张表**的去重判据（NodesScreen 渲染前的过滤器）。
 *
 * 现场：对端握手入表之后 mDNS 还在广播它，列表出现同人两行；用户点下面那行的
 * 「连接」，控制器回「这个节点已经在列表里了」。**手动存过、还没接通**的地址是同一个
 * 症状的另一半：只比节点表时它照样两行并存。过滤器因此同时认 `peers` 与 `manualPeers`，
 * 地址用 [sameEndpointAddress]（方括号 / 大小写 / IPv6 压缩写法都算同一个地址）。
 *
 * 反向验证：
 *  - 把 `manualPeers` 那半边去掉 → [dropsManualAddressSavedButNotHandshaked] 变红；
 *  - 把 [sameEndpointAddress] 换回裸 `==` → 两条 IPv6 用例变红；
 *  - 换成 `IpAddressUtils.isSameHost`（IPv6 走 /64 前缀）→
 *    [keepsAnotherDeviceInTheSameIpv6Prefix] 变红。
 */
class MdnsKnownFilterTest {

    private fun svc(host: String, port: Int) = MdnsServiceUi("服务-" + host, host, port)

    private fun peer(
        nodeId: String,
        ip: String,
        host: String = "",
        udpPort: Int = 17_878,
    ) = PeerUi(nodeId = nodeId, nick = nodeId, ip = ip, host = host, udpPort = udpPort)

    private fun manual(host: String, port: Int = 17_878, name: String? = null) =
        ManualPeerEntry(host = host, port = port, name = name)

    @Test
    fun dropsServiceAlreadyHandshaked() {
        // 老周扫到过、也握过手：节点表里已经有 192.168.1.31:17878。
        val peers = listOf(peer("laozhou", ip = "192.168.1.31"))
        val services = listOf(svc("192.168.1.31", 17_878))
        assertEquals(emptyList(), filterKnownMdnsServices(services, peers))
    }

    @Test
    fun keepsServicesNobodyAnswered() {
        // 192.168.1.77 广播着但没人握过手 —— 它就是「连接」按钮要服务的那一条。
        val peers = listOf(peer("laozhou", ip = "192.168.1.31"))
        val services = listOf(svc("192.168.1.31", 17_878), svc("192.168.1.77", 17_878))
        assertEquals(listOf(svc("192.168.1.77", 17_878)), filterKnownMdnsServices(services, peers))
    }

    @Test
    fun emptyPeersAndNoManualKeepsEverything() {
        val services = listOf(svc("192.168.1.77", 17_878))
        assertEquals(services, filterKnownMdnsServices(services, emptyList()))
        assertEquals(services, filterKnownMdnsServices(services, emptyList(), emptyList()))
    }

    @Test
    fun sameHostDifferentPortIsNotTheSameNode() {
        // 同一台机器跑两个节点（茶话间 17878 / agentNode 17891）：端口不同就不是同一家，
        // 只看 IP 会把另一台也滤掉 —— 那一行还等着用户点「连接」。
        val peers = listOf(peer("laozhou", ip = "192.168.1.31", udpPort = 17_878))
        val services = listOf(svc("192.168.1.31", 17_891))
        assertEquals(services, filterKnownMdnsServices(services, peers))
    }

    @Test
    fun matchesPeerBySelfReportedHostToo() {
        // 用户填的是主机名（peer.host），mDNS 报的是同一台 —— host 撞上也算认识。
        val peers = listOf(peer("laozhou", ip = "", host = "zhou-pc", udpPort = 17_878))
        val services = listOf(svc("zhou-pc", 17_878))
        assertEquals(emptyList(), filterKnownMdnsServices(services, peers))
    }

    @Test
    fun unknownPeerPortStillMatchesByAddress() {
        // udpPort == 0（替身 / 预览数据）时与 manualPeerStatuses.udpPortMatches 同一口径：
        // 地址对上就算 —— 别把"不知道端口"说成"不是同一家"。
        val peers = listOf(peer("laozhou", ip = "192.168.1.31", udpPort = 0))
        val services = listOf(svc("192.168.1.31", 17_878))
        assertTrue(filterKnownMdnsServices(services, peers).isEmpty())
    }

    // ── 手动地址表那半边（修复的另一半） ───────────────────────────────────────

    @Test
    fun dropsManualAddressSavedButNotHandshaked() {
        // 用户手填过 192.168.1.31:17878，还没握手；mDNS 又广播同一台 —— 过去是两行，
        // 点下面那行的「连接」被控制器判重复。
        val services = listOf(svc("192.168.1.31", 17_878))
        val manual = listOf(manual("192.168.1.31", 17_878))
        assertEquals(emptyList(), filterKnownMdnsServices(services, emptyList(), manual))
    }

    @Test
    fun keepsManualAddressOnADifferentPort() {
        // 同 IP 不同端口是另一条记录（另一台节点），不能顺手滤掉。
        val services = listOf(svc("192.168.1.31", 17_878))
        val manual = listOf(manual("192.168.1.31", 17_891))
        assertEquals(services, filterKnownMdnsServices(services, emptyList(), manual))
    }

    @Test
    fun manualEntryAndHandshakedPeerBothCoverTheService() {
        val peers = listOf(peer("laozhou", ip = "192.168.1.31"))
        val manual = listOf(manual("192.168.1.31"))
        val services = listOf(svc("192.168.1.31", 17_878))
        assertEquals(emptyList(), filterKnownMdnsServices(services, peers, manual))
    }

    // ── 地址比较：方括号 / 大小写 / IPv6 压缩写法 ──────────────────────────────

    @Test
    fun ipv6BracketedAndExpandedFormsAreTheSameEndpoint() {
        val peers = listOf(peer("laozhou", ip = "2001:db8:1234:0:0:0:0:1"))
        val services = listOf(svc("[2001:db8:1234::1]", 17_878))
        assertEquals(emptyList(), filterKnownMdnsServices(services, peers))
    }

    @Test
    fun keepsAnotherDeviceInTheSameIpv6Prefix() {
        // 家用局域网通常就是一整段 /64。按前缀判重会把**另一台**设备也滤掉——
        // 那等于把一条用户要点的「连接」按钮藏起来（比多一行更难发现）。
        val peers = listOf(peer("laozhou", ip = "2001:db8:1234::a"))
        val services = listOf(svc("[2001:db8:1234::b]", 17_878))
        assertEquals(services, filterKnownMdnsServices(services, peers))
    }

    @Test
    fun hostnamesCompareCaseInsensitively() {
        val peers = listOf(peer("laozhou", ip = "", host = "Zhou-PC"))
        val services = listOf(svc("zhou-pc", 17_878))
        assertEquals(emptyList(), filterKnownMdnsServices(services, peers))
    }

    @Test
    fun emptyAddressNeverMatchesAnything() {
        // 没拿到地址（启动中 / 替身）说"不知道"，不说"是它"：否则一条空 ip 会把所有服务滤光。
        assertFalse(sameEndpointAddress("", ""))
        assertFalse(sameEndpointAddress("", "192.168.1.31"))
        val peers = listOf(peer("laozhou", ip = "", host = ""))
        val services = listOf(svc("192.168.1.31", 17_878))
        assertEquals(services, filterKnownMdnsServices(services, peers))
    }
}
