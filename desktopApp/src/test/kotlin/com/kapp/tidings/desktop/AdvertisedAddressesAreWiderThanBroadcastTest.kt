// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import java.net.NetworkInterface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **「宣告地址」必须包含隧道网卡地址 —— 否则跨网永远连不上。**
 *
 * ## 真机现场（2026-10-22）
 *
 * 红米走 Tailscale（`100.102.36.109`），本机 `100.66.238.121`，本机 `ping` 它 2.2 秒就通。
 * 但红米记的本机地址是 `192.168.2.82`（局域网），拨了 18 次全失败；
 * 反过来本机 30 分钟没收到红米一个包（`lastSeen` 停在半小时前）。
 *
 * 根因：`localCandidateAddresses()` **刻意过滤隧道网卡**（`utun` / `feth` / `tailscale`），
 * 因为那些地址发不出 UDP 广播。但 `Profile.addresses` 是**单播**告诉已配对的对端
 * 「该往哪儿拨」—— 隧道地址恰恰只能靠单播到达。共用一个列表 ⇒ 宣告里永远没有隧道地址。
 *
 * ## 判据为什么从网卡枚举出发，而不是从被测函数出发
 *
 * 第一版写成 `advertised.filter{...}.forEach{ assertTrue(it in advertised) }` —— **恒真**：
 * 列表为空时循环不执行、测试照样绿，反向验证因此抓不到。
 * 判据必须来自**独立事实**（这台机器真实有哪些隧道网卡地址），
 * 再断言它出现在/不出现在哪一份列表里。这样删掉实现才会红。
 */
class AdvertisedAddressesAreWiderThanBroadcastTest {

    /** 本机**真实存在**的隧道网卡地址（独立于任何被测函数枚举出来）。 */
    private fun tunnelAddressesOnThisHost(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { com.kapp.tidings.domain.isVirtualInterfaceName(it.name) }
            .flatMap { nic -> nic.inetAddresses.toList() }
            // **排除链路本地**：`fe80::/10` 只在同一段二层内有效，拨不过去。
            // 实现侧同样排除它（`isLinkLocalAddress`），所以这里必须一致 ——
            // 否则测试会要求宣告一批注定无用的地址（第一版就是这么红的一次）。
            .filterNot { addr -> addr.isLinkLocalAddress || addr.isLoopbackAddress || addr.isAnyLocalAddress }
            .map { it.hostAddress.orEmpty().substringBefore('%').trim() }
            .filter { it.isNotBlank() }
            .filter { !it.startsWith("127.") }
            .filter { !it.lowercase().startsWith("fe80:") }
            // **代理 TUN 的虚拟地址不算「该被宣告的隧道地址」。**
            // 198.18.0.0/15 是 RFC 2544 基准测试段，Clash/mihomo 之类开 TUN 增强模式时
            // 会把它挂在 utun 上当 fake-IP 网关、接管全部 IPv4 路由。它不对应任何可达
            // 网络：宣告它，对端会拿它当候选、白白重试（真实现场：双方都拿到对方地址、
            // 都拨不通）。生产侧由 isProxyTunAddress 排除 —— 这条判据必须跟着同一规则，
            // 否则「TUN 开不开」会决定测试绿不绿，而 TUN 是用户环境，测试无从得知。
            .filterNot { com.kapp.tidings.core.protocol.IpAddressUtils.isProxyTunAddress(it) }
            .distinct()
    }.getOrElse { emptyList() }

    /**
     * 本机**真实存在**的代理 TUN 虚拟地址（同样来自网卡枚举这个独立事实）。
     *
     * 它们不是「该宣告的地址」，而是「**必须缺席**」的地址 —— 所以单独列出来断言反向。
     * 没有开 TUN 的机器上这个列表为空，测试自然退化为不做断言（而不是假装通过）。
     */
    private fun proxyTunAddressesOnThisHost(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList() }
            .map { it.hostAddress.orEmpty().substringBefore('%').trim() }
            .filter { it.isNotBlank() }
            .filter { com.kapp.tidings.core.protocol.IpAddressUtils.isProxyTunAddress(it) }
            .distinct()
    }.getOrElse { emptyList() }

    /**
     * **核心判据：本机每一条隧道地址都必须出现在宣告列表里。**
     *
     * 少了任何一条，那台设备在 VPN 上的对端就永远学不到该往哪儿拨 —— 死锁。
     * 反向验证会红，因为它断言的是**真实网卡上的地址**，不是被测对象的输出。
     */
    /**
     * C 类口径：本机没有可供断言的事实时，**不做断言、并说出跳过原因**。
     *
     * 静默 `return` 会让"跳过"和"通过"在测试报告里长得一样（都是绿），而这两条绿的含义
     * 完全不同：一条是"本机确实没有该被宣告的隧道地址"，另一条是"这台机器根本没有隧道
     * 网卡，什么都没验"。输出一句原因，跑的人才知道自己看的是哪一种。
     */
    private fun skipWithoutHostFact(testName: String, missing: String) {
        println("[env-skip] $testName：本机没有$missing —— 本条不做断言（这是跳过，不是通过）")
    }

    @Test
    fun everyTunnelAddressOnThisHostIsAdvertised() {
        val tunnel = tunnelAddressesOnThisHost()
        // 没有隧道网卡的机器（CI）上这条不成立：那时没有可断言的事实，跳过而不是假装通过。
        if (tunnel.isEmpty()) return skipWithoutHostFact("everyTunnelAddressOnThisHostIsAdvertised", "非链路本地的隧道网卡地址")
        // **必须断言接线结果，不是那个纯函数。**
        //
        // 第一版断言 `advertisedAddresses()`（函数本身），而反向验证改的是
        // `rawSnapshot` 里的接线 ⇒ 删掉接线测试照样绿（假绿）。
        // 真正决定「发出去的档案里有什么」的是快照，所以判据也必须在快照上。
        val advertised = DesktopNetworkMonitor().rawSnapshot().advertisedAddresses
        assertTrue(
            advertised.containsAll(tunnel),
            "本机的隧道地址必须全部进宣告列表，否则 VPN 上的对端永远拨不到；" +
                "缺失=" + (tunnel - advertised.toSet()) + " tunnel=" + tunnel + " advertised=" + advertised,
        )
    }

    /**
     * **隧道地址不得进入广播列表。**
     *
     * 这是既有设计（广播发了也到不了 VPN 段，留在列表里还会让同网段判断选错路）。
     * 这条同时钉住「不是把所有网卡无脑塞进一个列表」—— 两份列表的**用途不同**。
     */
    @Test
    fun tunnelAddressesStayOutOfTheBroadcastList() {
        val tunnel = tunnelAddressesOnThisHost()
        if (tunnel.isEmpty()) return skipWithoutHostFact("tunnelAddressesStayOutOfTheBroadcastList", "非链路本地的隧道网卡地址")
        val broadcast = DesktopNetworkMonitor().rawSnapshot().localAddresses
        val leaked = tunnel.filter { it in broadcast }
        assertEquals(
            emptyList(),
            leaked,
            "隧道地址不该出现在广播列表（发了也到不了，还会干扰同网段判断）；泄漏=" + leaked,
        )
    }

    /**
     * **代理 TUN 的虚拟地址一个都不许进宣告列表。**
     *
     * 与上一条正好相反：隧道地址里「真实可拨的」必须进，「fake-IP 网关」必须不进。
     * 分成两条而不是合成一条，是因为它们的判据来源不同 —— 前者是"本机有什么"，
     * 后者是"协议上哪些不可达"（由 `ProxyTunAddressTest` 用合成地址钉住）。
     * 这样无论用户开不开 Clash，两条都成立；开了 TUN 也不会把测试判红。
     */
    @Test
    fun proxyTunAddressesAreNeverAdvertised() {
        val proxy = proxyTunAddressesOnThisHost()
        if (proxy.isEmpty()) return skipWithoutHostFact("proxyTunAddressesAreNeverAdvertised", "代理 TUN 的 fake-IP 地址")
        val advertised = DesktopNetworkMonitor().rawSnapshot().advertisedAddresses
        val leaked = proxy.filter { it in advertised }
        assertEquals(
            emptyList(),
            leaked,
            "代理 TUN 的 fake-IP 地址不该被宣告（对端拨它永远失败，还会把 VPN 的包截走）；泄漏=" + leaked,
        )
    }

    /** 广播列表里的地址也都是可达的，宣告列表必须覆盖它（宣告是广播的超集）。 */
    @Test
    fun advertisedCoversTheBroadcastList() {
        val snapshot = DesktopNetworkMonitor().rawSnapshot()
        val broadcast = snapshot.localAddresses
        if (broadcast.isEmpty()) return skipWithoutHostFact("advertisedCoversTheBroadcastList", "任何可广播的本机地址")
        assertTrue(
            snapshot.advertisedAddresses.containsAll(broadcast),
            "宣告必须是广播的超集；broadcast=" + broadcast + " advertised=" + snapshot.advertisedAddresses,
        )
    }
}
