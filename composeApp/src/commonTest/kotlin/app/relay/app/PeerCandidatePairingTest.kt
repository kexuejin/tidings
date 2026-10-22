// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.qr.QrEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 配对码的候选地址：类型判定、方括号、"没有公网地址就不编一个"、"先打哪个"的选择规则，
 * 以及**二维码容量**（这一条最容易漏：全量候选 870 字节，编码器版本 10 只有 213 字节）。
 *
 * 守的是一条真机缺陷：桌面端把候选写成 "lan"/"ipv6"，手机端找 "lan_v4"/"public_v6"，
 * 两边对不上 ⇒ 手机永远落到 candidates.first()（网卡枚举序），于是"同网段优先"那条规则
 * 从来没生效过。所以这里同时钉住生成端与解析端。
 */
class PeerCandidatePairingTest {

    private val lanV4 = "192.168.1.23"
    private val ulaV6 = "fd00:1234:5678::5"
    private val guaV6 = "2001:db8:2020:cb44:1400:fdbb:f0d0:7679"

    /**
     * 读回载荷里的候选 —— **走生产解析器**，不是另写一份拆包逻辑。
     *
     * 为什么不再自己 `Json.parseToJsonElement`：载荷已经是二进制形态（`a` 里是
     * `"p:<base64url>"` 字符串，见 [encodeCandidate]），自己再解一遍等于给编码格式写了第二份
     * 真相；那一份一旦与解析器不一致，测试就会以 NullPointerException 的形状失败，
     * 让人看不出是"生成端改了"还是"测试落后了"。这里直接问 [parseQr]。
     */
    private fun addrsOf(payload: String): List<Triple<String, String, Int>> {
        val parsed = parseQr(payload) as? ParsedQr.LanPeer ?: return emptyList()
        return parsed.candidates.map { Triple(it.type, it.host, it.port) }
    }

    // ------------------------------------------------------------ 类型判定

    @Test
    fun ipv4IsLanAndLoopbackOrLinkLocalIsNotACandidate() {
        assertEquals(PeerCandidateTypes.LAN_V4, peerCandidateTypeOf(lanV4))
        assertEquals(PeerCandidateTypes.LAN_V4, peerCandidateTypeOf("10.0.0.5"))
        assertEquals(PeerCandidateTypes.LAN_V4, peerCandidateTypeOf("172.16.3.9"))
        assertNull(peerCandidateTypeOf("127.0.0.1"), "回环只有本机能连")
        assertNull(peerCandidateTypeOf("169.254.10.20"), "链路本地不可路由")
        assertNull(peerCandidateTypeOf(""), "空串不是地址")
    }

    @Test
    fun ipv6SplitsIntoUlaAndPublicAndRejectsLinkLocal() {
        assertEquals(PeerCandidateTypes.LAN_V6, peerCandidateTypeOf(ulaV6), "ULA 是局域网 v6，不是公网")
        assertEquals(PeerCandidateTypes.LAN_V6, peerCandidateTypeOf("fc00::1"))
        assertEquals(PeerCandidateTypes.PUBLIC_V6, peerCandidateTypeOf(guaV6), "2000::/3 才是公网 v6")
        assertEquals(PeerCandidateTypes.PUBLIC_V6, peerCandidateTypeOf("[" + guaV6 + "]"), "方括号也要认")
        assertEquals(
            PeerCandidateTypes.PUBLIC_V6,
            peerCandidateTypeOf(guaV6 + "%en1"),
            "macOS 的 JDK 给每个 IPv6 都带 %en1；带着它会被一律判成链路本地，把公网地址冤枉掉",
        )
        assertNull(peerCandidateTypeOf("fe80::1"), "链路本地不许冒充公网")
        assertNull(peerCandidateTypeOf("fe80::1%en0"), "带网卡 scope 的链路本地同样不可路由")
        assertNull(peerCandidateTypeOf("::1"))
        assertNull(peerCandidateTypeOf("ff02::1"), "组播不是候选")
    }

    @Test
    fun hostnameIsDomainAndGarbageIsOther() {
        assertEquals(PeerCandidateTypes.DOMAIN, peerCandidateTypeOf("desktop-node.local"))
        assertEquals(PeerCandidateTypes.OTHER, peerCandidateTypeOf("weird!!"))
    }

    // ------------------------------------------------------------ 生成端

    @Test
    fun payloadDerivesTypesItselfInsteadOfTrustingTheLabel() {
        // 桌面端旧代码给的标签是 "lan"/"ipv6" —— 生成端必须自己重算，否则手机永远选不对。
        val payload = multiCandidatePairingPayload(
            nodeId = "n-1",
            candidates = listOf(
                PeerCandidate(type = "lan", host = lanV4, port = 17878),
                PeerCandidate(type = "ipv6", host = guaV6, port = 17878),
            ),
        )
        assertEquals(
            listOf(
                Triple(PeerCandidateTypes.LAN_V4, lanV4, 17878),
                Triple(PeerCandidateTypes.PUBLIC_V6, guaV6, 17878),
            ),
            addrsOf(payload),
            "类型由地址重算；解析出来的 IPv6 是裸地址（canonical 形态，不带方括号）",
        )
    }

    @Test
    fun sharedPortIsHoistedAndPerEntryPortStillWorks() {
        val same = multiCandidatePairingPayload(
            nodeId = "n-1",
            candidates = listOf(
                PeerCandidate(PeerCandidateTypes.LAN_V4, lanV4, 17878),
                PeerCandidate(PeerCandidateTypes.PUBLIC_V6, guaV6, 17878),
            ),
        )
        val sameJson = Json.parseToJsonElement(same).jsonObject
        // 17878 是协议缺省端口 ⇒ **连信封都不写**（解析端缺省回退，与写出来完全同义）。
        assertNull(sameJson["o"]?.jsonPrimitive?.content?.toInt(), "缺省端口不该占字节")
        assertTrue(
            sameJson["a"]!!.jsonArray.none { it.jsonPrimitive.content.count { ch -> ch == ':' } > 1 },
            "共用的端口提到信封上（或省掉），条目里不再重复",
        )
        assertEquals(17878, (parseQr(same) as ParsedQr.LanPeer).port, "信封上的 port 要能被解析成条目的缺省值")

        // 端口不一致时仍然逐条写（老语义），解析端也照旧。
        val mixed = multiCandidatePairingPayload(
            nodeId = "n-1",
            candidates = listOf(
                PeerCandidate(PeerCandidateTypes.LAN_V4, lanV4, 17878),
                PeerCandidate(PeerCandidateTypes.PUBLIC_V6, guaV6, 18000),
            ),
        )
        assertEquals(listOf(17878, 18000), addrsOf(mixed).map { it.third })
    }

    @Test
    fun payloadDropsUnusableCandidatesAndBadPorts() {
        val payload = multiCandidatePairingPayload(
            nodeId = "n-1",
            candidates = listOf(
                PeerCandidate(type = "lan_v6", host = "fe80::1", port = 17878),
                PeerCandidate(type = "lan_v4", host = lanV4, port = 0),
                PeerCandidate(type = "lan_v4", host = lanV4, port = 17878),
            ),
        )
        assertEquals(listOf(Triple(PeerCandidateTypes.LAN_V4, lanV4, 17878)), addrsOf(payload))
    }

    @Test
    fun noPublicAddressIsInventedWhenThereIsNone() {
        val payload = pairingPayloadForAddresses(
            nodeId = "n-1",
            addresses = listOf(lanV4, ulaV6, "fe80::1", "127.0.0.1"),
            port = 17878,
        )
        assertNotNull(payload)
        val types = addrsOf(payload).map { it.first }
        assertEquals(listOf(PeerCandidateTypes.LAN_V4, PeerCandidateTypes.LAN_V6), types)
        assertTrue(types.none { it == PeerCandidateTypes.PUBLIC_V6 }, "没有 GUA 就不许出现 public_v6")
        // ULA 在码里是二进制形态，所以不能再用 payload.contains(ulaV6) 断言 —— 问解析结果。
        assertTrue(addrsOf(payload).any { it.second == ulaV6 }, "ULA 进码，且解析回来是同一个地址：" + payload)
        assertTrue(addrsOf(payload).none { it.second.startsWith("fe80") }, "链路本地不进码")
    }

    @Test
    fun payloadIsNullWhenThereIsNoUsableAddress() {
        assertNull(pairingPayloadForAddresses("n-1", addresses = emptyList(), port = 17878))
        assertNull(
            pairingPayloadForAddresses("n-1", addresses = listOf("fe80::1", "127.0.0.1"), port = 17878),
            "只有不可路由的地址时宁可不画码，也不编一个",
        )
        assertNull(pairingPayloadForAddresses("n-1", addresses = listOf(lanV4), port = 0), "端口越界同样不给码")
    }

    // ------------------------------------------------------------ 二维码容量

    @Test
    fun qrCandidatesCollapseOnePrefixPerFamilyAndKeepOneOfEachType() {
        // 真机形状：一张网卡 5 个同 /64 的公网 GUA（隐私地址轮换）+ 2 个不同 /64 的 ULA + 5 个 v4。
        val addresses = listOf(
            "198.18.0.1", "100.66.238.121", "192.168.2.82", "172.26.115.93", "10.233.138.167",
            "fdfe:dcba:9876:0:0:0:0:1", "fd7a:115c:a1e0:0:0:0:8501:ee7c",
            "2001:db8:2020:cb44:310e:ffbe:ddc5:925d",
            "2001:db8:2020:cb44:3846:5ee9:5f4b:6597",
            "2001:db8:2020:cb44:4805:e245:f001:2",
            "2001:db8:2020:cb44:31f4:ca9a:d4e2:a54a",
            "2001:db8:2020:cb44:1400:fdbb:f0d0:7679",
        )
        val qr = qrCandidatesFor(addresses, 17878, "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6")
        // 同 /64 的 5 个 GUA 只留一条；类型轮转保证"内网一条 + 公网一条"都进得来。
        assertTrue(qr.any { it.type == PeerCandidateTypes.LAN_V4 }, "内网 v4 必须在")
        assertTrue(qr.any { it.type == PeerCandidateTypes.PUBLIC_V6 }, "公网 v6 必须在（这正是本功能的目的）")
        assertEquals(1, qr.count { it.type == PeerCandidateTypes.PUBLIC_V6 }, "同 /64 只留一条")
        // task-86：二维码只装得下一条地址时，**公网 v6 必须排在第一位**（同网段有 mDNS 兜底，
        // 公网那条不可替代）。所以这里钉的是"第一档 = 公网 v6、内网 v4 紧随其后"。
        assertEquals(PeerCandidateTypes.PUBLIC_V6, qr.first().type, "轮转第一档是公网 v6（跨网唯一不可替代的那条）")
        assertTrue(qr.getOrNull(1)?.type == PeerCandidateTypes.LAN_V4, "第二档是内网 v4：" + qr)
        // **断言性质，不钉条数。** 原来写的是 `qr.size <= 5` —— 那是按二进制候选 12–22 字节
        // 估出来的经验值，加一档 `tunnel_v4` 就假红了。真正的性质是「预算裁掉了东西」，
        // 而「生成的载荷一定能编码」由 `qrPayloadAlwaysFitsTheEncoder` 独立守着。
        assertTrue(qr.size < addresses.size, "预算必须真的裁过，别把 12 条全塞进去：" + qr)
    }

    @Test
    fun qrPayloadAlwaysFitsTheEncoder() {
        val addresses = listOf(
            "198.18.0.1", "100.66.238.121", "192.168.2.82", "172.26.115.93", "10.233.138.167",
            "fdfe:dcba:9876:0:0:0:0:1", "fd7a:115c:a1e0:0:0:0:8501:ee7c",
            "2001:db8:2020:cb44:310e:ffbe:ddc5:925d",
            "2001:db8:2020:cb44:3846:5ee9:5f4b:6597",
            "2001:db8:2020:cb44:1400:fdbb:f0d0:7679",
        )
        val nodeId = "a1b2c3d4-6e74-4a7c-aab1-20a1d1284cd6"
        val payload = qrPairingPayloadForAddresses(nodeId, addresses, 17878)
        assertNotNull(payload)
        assertTrue(
            payload.length <= QR_PAYLOAD_MAX_BYTES,
            "二维码载荷必须落在编码器容量内，实际 " + payload.length + " 字节",
        )
        // 真编码一次：容量算错了会在这里抛 IllegalArgumentException，而不是在用户面前"码不出现"。
        assertNotNull(QrEncoder.encode(payload), "二维码载荷必须真的编得出来")
        // 手机端：同网段选内网 v4，跨网络选公网 v6 —— 这就是这一整套的落点。
        assertEquals(
            "192.168.2.82",
            (parseQr(payload, myAddresses = listOf("192.168.2.50")) as ParsedQr.LanPeer).host,
        )
        assertEquals(
            "2001:db8:2020:cb44:310e:ffbe:ddc5:925d",
            (parseQr(payload, myAddresses = listOf("10.1.186.117")) as ParsedQr.LanPeer).host,
        )
    }

    @Test
    fun fullLinkPayloadKeepsEveryCandidateEvenWhenTheQrCannot() {
        val addresses = listOf(lanV4, ulaV6, guaV6, "2001:db8:2020:cb44::2")
        val link = pairingPayloadForAddresses("n-1", "MyMac", addresses, 17878)
        assertNotNull(link)
        assertEquals(4, addrsOf(link).size, "复制链接不限长：全部候选都要在里面")
        // 两者的分工是"链接不限长、二维码按预算裁"，不是"链接一定超长"：二进制形态之后
        // 4 条候选也装进了版本 8，这是好事（能扫的那张码里候选更多）。这里只在**装不下时**
        // 才要求两者不同，所以断言写成"二维码裁完不多于全量"，而不是钉死某个长度关系。
        assertTrue(
            qrCandidatesFor(addresses, 17878, "n-1").size <= 4,
            "二维码候选不能比全量还多",
        )
    }

    // ------------------------------------------------------------ 选择规则

    @Test
    fun sameSubnetLanWinsOverPublicIpv6() {
        val payload = multiCandidatePairingPayload(
            nodeId = "n-1",
            candidates = listOf(
                PeerCandidate(PeerCandidateTypes.PUBLIC_V6, guaV6, 17878),
                PeerCandidate(PeerCandidateTypes.LAN_V4, "192.168.1.99", 17878),
            ),
        )
        val parsed = parseQr(payload, myAddresses = listOf("192.168.1.50")) as ParsedQr.LanPeer
        assertEquals("192.168.1.99", parsed.host, "同网段（v4 /24）优先，哪怕公网 v6 排在前面")
    }

    @Test
    fun sameIpv6PrefixAlsoCountsAsSameNetwork() {
        val other = "fd00:1234:5678::99"
        val payload = multiCandidatePairingPayload(
            nodeId = "n-1",
            candidates = listOf(
                PeerCandidate(PeerCandidateTypes.PUBLIC_V6, guaV6, 17878),
                PeerCandidate(PeerCandidateTypes.LAN_V6, other, 17878),
            ),
        )
        val parsed = parseQr(payload, myAddresses = listOf(ulaV6)) as ParsedQr.LanPeer
        assertEquals(other, parsed.host, "同 /64 的 ULA 也算同网段")
    }

    @Test
    fun remoteNetworkPicksPublicIpv6() {
        val payload = multiCandidatePairingPayload(
            nodeId = "n-1",
            candidates = listOf(
                PeerCandidate(PeerCandidateTypes.LAN_V4, lanV4, 17878),
                PeerCandidate(PeerCandidateTypes.PUBLIC_V6, guaV6, 17878),
            ),
        )
        // 手机只剩移动网：本机地址表非空、但和桌面的 /24 对不上 ⇒ 该选公网 v6。
        val remote = parseQr(payload, myAddresses = listOf("10.1.186.117")) as ParsedQr.LanPeer
        assertEquals(guaV6, remote.host)
    }

    @Test
    fun withoutLocalAddressInfoLanIsTriedFirst() {
        val payload = multiCandidatePairingPayload(
            nodeId = "n-1",
            candidates = listOf(
                PeerCandidate(PeerCandidateTypes.PUBLIC_V6, guaV6, 17878),
                PeerCandidate(PeerCandidateTypes.LAN_V4, lanV4, 17878),
            ),
        )
        // 拿不到本机地址（刚切网）："两台机器同处一网"仍是更常见的场景。
        val parsed = parseQr(payload) as ParsedQr.LanPeer
        assertEquals(lanV4, parsed.host)
    }

    @Test
    fun roundTripKeepsEveryCandidateAndUnbracketsTheChosenHost() {
        val payload = pairingPayloadForAddresses(
            nodeId = "n-1",
            nick = "MyMac",
            addresses = listOf(lanV4, ulaV6, guaV6),
            port = 17878,
        )
        assertNotNull(payload)
        val parsed = parseQr(payload, myAddresses = listOf("192.168.1.7")) as ParsedQr.LanPeer
        assertEquals(lanV4, parsed.host, "选中的 host 是裸地址（不带方括号），连接层要的就是这个")
        assertEquals(3, parsed.candidates.size, "三条候选都要留在码里（连不上就落下一个）")
        assertEquals("n-1", parsed.nodeId)
        assertEquals("MyMac", parsed.nick)
        val v6 = parsed.candidates.single { it.type == PeerCandidateTypes.PUBLIC_V6 }
        assertEquals(guaV6, v6.host, "候选里的 IPv6 也要还原成裸地址")
    }

    // ------------------------------------------------------------ 假候选过滤（task-17）

    @Test
    fun proxyFakeIpIsNeverACandidateButTunnelIsTheLowestTier() {
        // **这两段现在处置不同**（曾经都是 null）：
        //  · 198.18/15 是代理 fake-IP，照它拨只会打到代理自己的网关 ⇒ 任何情况都不进候选；
        //  · 100.64/10 是 Tailscale / ZeroTier 的隧道地址，**同网可达** ⇒ 单独一档、优先级最低。
        //
        // 为什么改：手机卸载重装后 nodeId 变了、known-peers 空了，广播与 mDNS 都出不了本机
        // 子网，`utun` 又是 /32 点对点没有广播语义 —— 唯一剩下的引导通道只有扫二维码，
        // 而隧道地址被整段挡掉意味着「走 Tailscale 的那台 Mac」永远进不了码。
        assertNull(peerCandidateTypeOf("198.18.0.1"), "198.18/15 是代理 fake-IP 网关")
        assertNull(peerCandidateTypeOf("198.19.255.254"), "198.18.0.0/15 的上边界")
        assertEquals(PeerCandidateTypes.TUNNEL_V4, peerCandidateTypeOf("100.64.0.1"), "隧道段单独一档")
        assertEquals(PeerCandidateTypes.TUNNEL_V4, peerCandidateTypeOf("100.127.255.254"), "100.64/10 的上边界")
        // 边界外必须放行：过滤过宽会把用户真实的私网/公网地址也挡掉。
        assertEquals(PeerCandidateTypes.LAN_V4, peerCandidateTypeOf("198.17.0.1"))
        assertEquals(PeerCandidateTypes.LAN_V4, peerCandidateTypeOf("198.20.0.1"))
        assertEquals(PeerCandidateTypes.LAN_V4, peerCandidateTypeOf("100.128.0.1"))
        assertEquals(PeerCandidateTypes.LAN_V4, peerCandidateTypeOf("10.0.0.5"), "10/8 是真私网，必须放行")
        assertEquals(PeerCandidateTypes.LAN_V4, peerCandidateTypeOf("192.168.2.82"))
        assertEquals(PeerCandidateTypes.LAN_V4, peerCandidateTypeOf("172.26.115.93"), "地址本身合法；feth 靠接口名挡")
    }

    @Test
    fun virtualInterfaceNamesAreRecognized() {
        assertTrue(isVirtualInterfaceName("utun5"), "macOS 隧道口")
        assertTrue(isVirtualInterfaceName("feth2730"), "macOS fake ethernet")
        assertTrue(isVirtualInterfaceName("tailscale0"))
        assertTrue(isVirtualInterfaceName("tun0"), "Android 的 VPN 口")
        assertTrue(isVirtualInterfaceName("UTUN5"), "大小写无关")
        assertFalse(isVirtualInterfaceName("en1"), "真网卡一律放行")
        assertFalse(isVirtualInterfaceName("wlan0"))
        assertFalse(isVirtualInterfaceName("eth0"))
    }

    @Test
    fun candidatePoolDropsFakeAddressesButKeepsRealOnes() {
        val pool = peerCandidatesFrom(
            listOf("198.18.0.1", "100.66.238.121", "192.168.2.82", guaV6, "fe80::1"),
            17878,
        )
        assertEquals(
            listOf(PeerCandidateTypes.TUNNEL_V4, PeerCandidateTypes.LAN_V4, PeerCandidateTypes.PUBLIC_V6),
            pool.map { it.type },
            "fake-IP 与链路本地进不了候选池；隧道地址保留但自成最低一档，真 v4 与公网 v6 照旧",
        )
        assertEquals(listOf("100.66.238.121", "192.168.2.82", guaV6), pool.map { it.host })
    }

    // ------------------------------------------------------------ 入口共用选择（task-17）

    @Test
    fun sharedEntryHelperPrefersMultiCandidateAndFallsBackToSingleHost() {
        val addresses = listOf("192.168.2.82", guaV6)
        // 有候选 + nodeId ⇒ 多候选（二维码预算版）：手机跨网络时能选到公网 v6。
        val multi = pairingQrPayloadFor(
            host = "192.168.2.82",
            addresses = addresses,
            port = 17878,
            nodeId = "n-1",
        )
        assertNotNull(multi)
        // 码里是二进制形态，不能再用 payload.contains("public_v6") 断言 —— 问解析器。
        val multiParsed = parseQr(multi) as ParsedQr.LanPeer
        assertTrue(
            multiParsed.candidates.any { it.type == PeerCandidateTypes.PUBLIC_V6 },
            "多候选载荷必须带公网 v6：" + multi,
        )
        assertEquals(2, multiParsed.candidates.size)
        // 没有候选（或没有 nodeId）⇒ 退回单地址形态，行为与旧调用点一致。
        assertEquals("relay://192.168.2.82:17878", pairingQrPayloadFor("192.168.2.82", emptyList(), 17878, "n-1"))
        assertEquals("relay://192.168.2.82:17878", pairingQrPayloadFor("192.168.2.82", addresses, 17878, ""))
        // 连地址都没有 ⇒ null（调用方据此画「没有可用地址」，而不是编一个）。
        assertNull(pairingQrPayloadFor(null, emptyList(), 17878, "n-1"))
        assertNull(pairingQrPayloadFor("  ", emptyList(), 17878, "n-1"))
    }

    @Test
    fun sharedEntryHelperAlsoDropsFakeAddresses() {
        // 入口拿到的是整张本机地址表（含 fake-IP / 隧道口），过滤必须发生在这一层之内，
        // 否则五个入口各自都要记得过滤一遍 —— 那正是会漏的地方。
        val payload = pairingQrPayloadFor(
            host = "198.18.0.1",
            addresses = listOf("198.18.0.1", "100.66.238.121", "192.168.2.82", guaV6),
            port = 17878,
            nodeId = "n-1",
        )
        assertNotNull(payload)
        val parsed = parseQr(payload) as ParsedQr.LanPeer
        val hosts = parsed.candidates.map { it.host }
        assertTrue(hosts.none { it.startsWith("198.18") }, "fake-IP 不许进码：" + hosts)
        assertTrue(hosts.any { it.startsWith("100.66") }, "隧道地址要进码（重装后唯一能引导的那条）" + hosts)
        assertTrue(hosts.contains("192.168.2.82") && hosts.contains(guaV6), "真候选必须在：" + hosts)
    }

    @Test
    fun onlyFakeAddressesFallBackToSingleHostForm() {
        // 整张表都是假候选 ⇒ 多候选载荷为空 ⇒ 退回 host 的单地址形态（不是 null、也不是假码）。
        assertEquals(
            "relay://198.18.0.1:17878",
            pairingQrPayloadFor("198.18.0.1", listOf("198.18.0.1"), 17878, "n-1"),
        )
    }

}
