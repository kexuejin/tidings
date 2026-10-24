// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 跨网**屏幕**通道的端口派生与候选计划（与 F1 文件拉取同一条纪律，端口偏移不同）。
 *
 * 独立成一个文件而不是塞进 `CrossNetworkTransferPolicyTest`：那个文件钉的是文件通道
 * （偏移 +1）的三条验收，这里钉的是屏幕通道（偏移 +2）。两条通道**共用同一个
 * `planDerived`**，所以真正会腐坏的地方是「偏移没被穿到底」—— 屏幕通道悄悄用了
 * 文件端口的 +1，两端就会在错端口上互相等超时，而单测与门禁全绿（本仓最贵的那类
 * 静默失效：拨号侧一切正常、对端什么都没收到）。因此这里每一条都同时断言
 * 「是 tlsPort + 2」与「不是 tlsPort + 1」。
 *
 * 纯逻辑：不起任何 socket，不依赖 `NodeRuntime`。
 */
class CrossNetworkScreenPlanTest {

    private val lan = PeerEndpoint("192.168.2.9", 17879)
    private val publicV6 = PeerEndpoint("2001:db8:2020:8494::9", 17878)
    private val publicV4 = PeerEndpoint("203.0.113.7", 17878)
    private val tlsPort = 17880
    private val screenPort = tlsPort + CROSS_NETWORK_SCREEN_PORT_OFFSET
    private val filePort = tlsPort + CROSS_NETWORK_FILE_PORT_OFFSET

    private fun policy(enabled: Boolean = true, tls: Boolean = true) =
        CrossNetworkTransferPolicy(enabled = enabled, canDialTls = tls)

    // ---------------------------------------------------------------------
    // 端口派生
    // ---------------------------------------------------------------------

    @Test
    fun crossNetworkScreenPortIsTlsPortPlusTwo() {
        assertEquals(17882, crossNetworkScreenPort(17880))
        assertEquals(3, crossNetworkScreenPort(1), "下界：tlsPort 最小合法值 1 → 3")
        assertNull(crossNetworkScreenPort(null), "没有 tlsPort ⇒ 这条通道不存在，不是回落到别的端口")
        assertNull(crossNetworkScreenPort(0), "0 不是合法监听端口")
        assertNull(crossNetworkScreenPort(-1))
        assertNull(crossNetworkScreenPort(65534), "65534 + 2 越界，绝不能产出一个非法端口")
        assertEquals(65535, crossNetworkScreenPort(65533), "上界是闭区间：65533 是最后一个能派生的值")
    }

    @Test
    fun theTwoCrossNetworkChannelsNeverShareAPort() {
        // 同一份 profile.tlsPort 派生两条独立监听：文件 +1、屏幕 +2。
        // 若某天有人把两个偏移写成同一个值，两条通道会抢同一个监听口 ——
        // 症状是「文件传得动、投屏永远超时」（或反过来），且日志里两边都说绑成功了。
        assertTrue(CROSS_NETWORK_SCREEN_PORT_OFFSET != CROSS_NETWORK_FILE_PORT_OFFSET)
        val sample = listOf(1, 2, 17880, 65532)
        sample.forEach { tls ->
            val file = crossNetworkFilePort(tls)
            val screen = crossNetworkScreenPort(tls)
            assertTrue(file != null && screen != null && file != screen, "tlsPort=$tls 的两条派生端口撞了：$file")
        }
    }

    // ---------------------------------------------------------------------
    // 候选顺序 / 去重 / fail-closed
    // ---------------------------------------------------------------------

    @Test
    fun lanPlaintextIsFirstAndKeepsItsOwnPort() {
        // 局域网原路径必须逐字保留：端口是 registry 里的 tcpPort（这里 17879），
        // **不是** tlsPort 派生值 —— 屏幕通道追加候选，不替换现有那条。
        val plan = policy().planScreen(lan, listOf(lan, publicV6), tlsPort, hasPin = true)
        assertEquals(PullAttempt("192.168.2.9", 17879, PullAttemptTransport.Plaintext), plan.first())
    }

    @Test
    fun tlsCandidatesUseTheScreenPortNotTheFilePort() {
        val plan = policy().planScreen(lan, listOf(publicV6, publicV4), tlsPort, hasPin = true)
        assertEquals(
            listOf(
                PullAttempt("192.168.2.9", 17879, PullAttemptTransport.Plaintext),
                PullAttempt("2001:db8:2020:8494::9", screenPort, PullAttemptTransport.Tls),
                PullAttempt("203.0.113.7", screenPort, PullAttemptTransport.Tls),
            ),
            plan,
        )
        assertTrue(plan.drop(1).none { it.port == filePort }, "屏幕候选拨到了文件端口")
        assertTrue(plan.drop(1).all { it.port == tlsPort + 2 })
    }

    @Test
    fun theFilePlanAndTheScreenPlanDifferOnlyInTheDerivedPort() {
        // 同一份输入、两个 plan：明文那条完全相同，TLS 那条只差偏移。
        // 这条是「偏移穿到底」的直接证据 —— 把 planScreen 改成复用文件的偏移，它变红。
        val file = policy().plan(lan, listOf(publicV4), tlsPort, hasPin = true)
        val screen = policy().planScreen(lan, listOf(publicV4), tlsPort, hasPin = true)
        assertEquals(file.first(), screen.first())
        assertEquals(file.size, screen.size)
        assertEquals(file[1].host, screen[1].host)
        assertEquals(file[1].port + 1, screen[1].port)
    }

    @Test
    fun eachHostAppearsAtMostOnce() {
        val plan = policy().planScreen(
            direct = lan,
            // 直连那个地址又在候选池里（不同端口也算同一个地址）、候选池自己重复两次。
            candidates = listOf(lan, publicV4, publicV4, PeerEndpoint(" 203.0.113.7 ", 17878)),
            tlsPort = tlsPort,
            hasPin = true,
        )
        assertEquals(listOf("192.168.2.9", "203.0.113.7"), plan.map { it.host })
        assertEquals(1, plan.count { it.host == "203.0.113.7" }, "同名候选被排了两次")
    }

    @Test
    fun candidateHostsAreTrimmedBeforeUse() {
        // 候选池来自配对码/广播，历史上出现过带空格的地址。
        // 不 trim 的后果不是「连不上」而是拨了一个 DNS 名字 " 1.2.3.4"——解析失败同样表现为超时。
        val plan = policy().planScreen(null, listOf(PeerEndpoint(" 203.0.113.7 ", 1)), tlsPort, hasPin = true)
        assertEquals("203.0.113.7", plan.single().host)
    }

    @Test
    fun privateAndBlankCandidatesAreSkipped() {
        val plan = policy().planScreen(
            direct = null,
            candidates = listOf(
                PeerEndpoint("", 17878),
                PeerEndpoint("   ", 17878),
                PeerEndpoint("192.168.2.9", 17878),
                PeerEndpoint("10.0.0.9", 17878),
                PeerEndpoint("fd00::9", 17878),
                publicV4,
            ),
            tlsPort = tlsPort,
            hasPin = true,
        )
        // 私网地址属于局域网那条路（上面已经不再重复拨），空地址没有意义。
        assertEquals(listOf("203.0.113.7"), plan.map { it.host })
    }

    @Test
    fun noEndpointAtAllYieldsAnEmptyPlanInsteadOfAFabricatedAttempt() {
        // 空表是契约的一部分：调用方据此报「对端不在线」，绝不能凭空造一条注定超时的尝试。
        assertTrue(policy().planScreen(null, emptyList(), tlsPort, hasPin = true).isEmpty())
        // 直连端点非法（端口 0 / 空 host）也不该被当成一条可用尝试排进去。
        assertTrue(policy().planScreen(PeerEndpoint("192.168.2.9", 0), emptyList(), tlsPort, true).isEmpty())
        assertTrue(policy().planScreen(PeerEndpoint("", 17879), emptyList(), tlsPort, true).isEmpty())
    }

    @Test
    fun theSwitchOffLeavesExactlyTheLanPath() {
        val plan = policy(enabled = false).planScreen(lan, listOf(publicV6), tlsPort, hasPin = true)
        assertEquals(listOf(PullAttempt("192.168.2.9", 17879, PullAttemptTransport.Plaintext)), plan)
    }

    @Test
    fun everyTlsPreconditionIsFailClosed() {
        // 公网必须 TLS + 双向 pin：canDialTls / hasPin / tlsPort 缺一即无跨网候选，
        // 且**绝不降级成明文**去拨那个公网地址（那会把投屏画面送出加密边界）。
        val missingTls = policy(tls = false).planScreen(lan, listOf(publicV6), tlsPort, hasPin = true)
        val missingPin = policy().planScreen(lan, listOf(publicV6), tlsPort, hasPin = false)
        val missingPort = policy().planScreen(lan, listOf(publicV6), tlsPort = null, hasPin = true)
        val outOfRangePort = policy().planScreen(lan, listOf(publicV6), tlsPort = 65534, hasPin = true)
        listOf(missingTls, missingPin, missingPort, outOfRangePort).forEach { plan ->
            assertEquals(1, plan.size, "缺一个前置条件就得只剩局域网那条：$plan")
            assertEquals(PullAttemptTransport.Plaintext, plan.single().transport)
        }
    }

    // ---------------------------------------------------------------------
    // 计划与拨号口成对出现
    // ---------------------------------------------------------------------

    @Test
    fun aPlanWithTlsAttemptsCarriesTheRawConnector() {
        val connector: RawPullConnector = { _, _ -> null }
        val plan = policy().planWithConnector(
            direct = lan,
            candidates = listOf(publicV6),
            tlsPort = tlsPort,
            hasPin = true,
            connectRaw = connector,
        )
        assertTrue(plan.attempts.any { it.transport == PullAttemptTransport.Tls })
        assertTrue(plan.connectRaw === connector, "有 TLS 尝试却没有拨号口 ⇒ 那条尝试注定失败")
    }

    @Test
    fun aPlanWithoutTlsAttemptsNeverCarriesAConnector() {
        // 只有明文时不带拨号口：白拿一个连接器意味着别处会以为「跨网这条已经准备好了」。
        val connector: RawPullConnector = { _, _ -> null }
        val plan = policy(enabled = false).planWithConnector(
            direct = lan,
            candidates = listOf(publicV6),
            tlsPort = tlsPort,
            hasPin = true,
            connectRaw = connector,
        )
        assertEquals(1, plan.attempts.size)
        assertNull(plan.connectRaw)
    }
}
