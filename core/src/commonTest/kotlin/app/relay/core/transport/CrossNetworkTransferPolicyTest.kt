// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1 跨网文件拉取的**策略**（task-39）：纯逻辑，不起任何连接。
 *
 * 三条验收各自钉在这里：
 * 1. 开关关着 ⇒ 只剩局域网那条（可回滚）；
 * 2. 公网必须 TLS + pin（缺任何一个都 fail-closed，绝不降级明文）；
 * 3. 局域网路径永远第一、且永远在表里（不失效）。
 */
class CrossNetworkTransferPolicyTest {

    private val lan = PeerEndpoint("192.168.2.9", 17879)
    private val publicV6 = PeerEndpoint("2001:db8:2020:8494::9", 17878)
    private val publicV4 = PeerEndpoint("203.0.113.7", 17878)
    private val tlsPort = 17880
    private val filePort = tlsPort + CROSS_NETWORK_FILE_PORT_OFFSET

    private fun policy(enabled: Boolean = true, tls: Boolean = true) =
        CrossNetworkTransferPolicy(enabled = enabled, canDialTls = tls)

    @Test
    fun lanFirstAndCrossNetworkTlsAfter() {
        val plan = policy().plan(
            direct = lan,
            candidates = listOf(lan, publicV6, publicV4),
            tlsPort = tlsPort,
            hasPin = true,
        )
        assertEquals(3, plan.size)
        assertEquals(PullAttempt("192.168.2.9", 17879, PullAttemptTransport.Plaintext), plan[0])
        assertEquals(PullAttempt("2001:db8:2020:8494::9", filePort, PullAttemptTransport.Tls), plan[1])
        assertEquals(PullAttempt("203.0.113.7", filePort, PullAttemptTransport.Tls), plan[2])
        assertTrue(plan.drop(1).all { it.transport == PullAttemptTransport.Tls }, "公网一律 TLS")
    }

    @Test
    fun theSwitchOffLeavesExactlyTodaysPath() {
        val plan = policy(enabled = false).plan(lan, listOf(lan, publicV6), tlsPort, hasPin = true)
        assertEquals(listOf(PullAttempt("192.168.2.9", 17879, PullAttemptTransport.Plaintext)), plan)
    }

    @Test
    fun noTlsPlatformNeverGetsAPublicAttempt() {
        // iOS 的 raw 工厂在 task-26 ① 落地前返回 null ⇒ canDialTls = false ⇒ 公网一条都不排。
        val plan = policy(tls = false).plan(lan, listOf(lan, publicV6), tlsPort, hasPin = true)
        assertEquals(1, plan.size)
        assertEquals(PullAttemptTransport.Plaintext, plan.single().transport)
    }

    @Test
    fun withoutAPinThePublicPathIsNotTried() {
        // 没有 pin 就不能验对端证书 ⇒ 只能不拨（fail-closed，绝不降级明文）。
        val plan = policy().plan(lan, listOf(lan, publicV6), tlsPort, hasPin = false)
        assertEquals(1, plan.size)
    }

    @Test
    fun withoutATlsPortThePublicPathIsNotTried() {
        val plan = policy().plan(lan, listOf(lan, publicV6), tlsPort = null, hasPin = true)
        assertEquals(1, plan.size)
    }

    @Test
    fun privateCandidatesAreNotDupedIntoTheCrossNetworkList() {
        val plan = policy().plan(
            direct = lan,
            candidates = listOf(lan, PeerEndpoint("10.0.0.9", 17878), publicV6),
            tlsPort = tlsPort,
            hasPin = true,
        )
        assertEquals(listOf("192.168.2.9", "2001:db8:2020:8494::9"), plan.map { it.host })
    }

    @Test
    fun aMissingDirectEndpointStillYieldsTheCrossNetworkAttempts() {
        val plan = policy().plan(null, listOf(publicV6), tlsPort, hasPin = true)
        assertEquals(listOf(PullAttempt("2001:db8:2020:8494::9", filePort, PullAttemptTransport.Tls)), plan)
    }

    @Test
    fun crossNetworkFilePortIsTlsPortPlusOne() {
        assertEquals(17881, crossNetworkFilePort(17880))
        assertEquals(2, crossNetworkFilePort(1))
        assertEquals(null, crossNetworkFilePort(null))
        assertEquals(null, crossNetworkFilePort(0))
        assertEquals(null, crossNetworkFilePort(65535), "65535 + 1 会越界")
    }
}
