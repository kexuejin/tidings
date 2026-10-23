// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task-26 ①：iOS 的 SPKI 拼装必须与 JVM 的 `PublicKey.encoded` **逐字节相同**。
 *
 * 这是 iOS 身份链上最便宜也最要命的一条：iOS 只拿得到 X9.63 原始点，SPKI 要自己拼；
 * 拼错一个字节，pin（= sha256(SPKI)）就两端不同 —— 真机表现是"配过对的设备突然连不上"，
 * 而且没有任何报错能指向这里。这条测试用 JVM 生成的真密钥做交叉验证。
 */
class LinkCertificateDerTest {

    @Test
    fun theSpkiWeBuildFromAnIosRawPointIsByteIdenticalToTheJvmOne() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val jvmSpki = keyPair.public.encoded

        // iOS 侧 SecKeyCopyExternalRepresentation 给的正是 SPKI 末尾那 65 字节（未压缩点）。
        val rawPoint = jvmSpki.copyOfRange(jvmSpki.size - 65, jvmSpki.size)
        assertEquals(0x04.toByte(), rawPoint[0], "前提：未压缩点以 0x04 开头")

        val rebuilt = LinkCertificateDer.ecP256SpkiFromRawPoint(rawPoint)
        assertEquals(jvmSpki.toList(), rebuilt.toList(), "拼出来的 SPKI 必须与 JVM 逐字节相同")
    }


    /**
     * 共享的纯 Kotlin 地址解析必须与平台的 InetAddress **逐字节相同**。
     *
     * 理由与 SPKI 那条一样：SAN 是证书字节的一部分，iOS 侧不能再用 inet_pton 解析一遍 ——
     * 两端差一个字节，pin 就不同。这里用 JVM 的 InetAddress 当参照物，覆盖 `::` 压缩、
     * 大小写、以及带 `%scope` 的链路本地地址（真机 localAddresses 里就有这种）。
     */
    @Test
    fun theSharedAddressParserAgreesWithThePlatformOnEveryAddressShape() {
        val addresses = listOf(
            "127.0.0.1",
            "192.168.2.82",
            "10.0.0.255",
            "0.0.0.0",
            "255.255.255.255",
            "::1",
            "2001:db8:2020:8494:d46b:34ff:fe9c:069b",
            "2001:db8:2020:CB44:1400:FDBB:F0D0:7679",
            "fe80::1",
            "fd00:0:0:0:0:0:0:1",
            "2001:db8::9",
            "fe80::a:b:c:d%wlan0",
        )
        for (address in addresses) {
            val platform = java.net.InetAddress.getByName(address.substringBefore('%')).address
            val shared = LinkCertificateDer.ipBytes(address)
            assertEquals(
                platform.toList(),
                shared?.toList(),
                "$address 的共享解析必须与平台逐字节相同（否则两端 pin 会不同）",
            )
        }
    }

    @Test
    fun hostnamesAndGarbageAreNotMistakenForAddresses() {
        assertEquals(null, LinkCertificateDer.ipBytes("mac-mini.local"))
        assertEquals(null, LinkCertificateDer.ipBytes(""))
        assertEquals(null, LinkCertificateDer.ipBytes("999.1.1.1"))
        assertEquals(null, LinkCertificateDer.ipBytes("2001:db8:::9"))
        assertEquals(null, LinkCertificateDer.ipBytes("1.2.3"))
    }

    @Test
    fun aMalformedPointIsRefusedInsteadOfProducingAWrongSpki() {
        val wrong = ByteArray(64) { 0x01 }
        val failed = runCatching { LinkCertificateDer.ecP256SpkiFromRawPoint(wrong) }.isFailure
        assertEquals(true, failed, "形状不对必须直接失败，绝不产出错的 SPKI")
    }
}
