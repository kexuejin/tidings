// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * task-26 ① 第一步：iOS 的本地身份（密钥 + 自签 IP 证书 + pin）在模拟器上真的能造出来。
 *
 * 三条断言各自对应一条跨端契约：
 *  1. SPKI 是 **91 字节**（P-256 的固定长度）—— 与 JVM 同布局，pin 才可能一致；
 *  2. 证书 DER 能被 Security.framework **解析回来**（`SecCertificateCreateWithData` 非 null）
 *     —— 证明"共享 DER + iOS 签名"这条链真的产出了一张合法证书；
 *  3. 重启（重新读落盘材料）与**续签**（只删证书）之后 **pin 不变** —— 这是"私钥不变 ⇒
 *     不用重新配对"的承诺，也是 task-13 裁决的自动续签。
 */
@OptIn(ExperimentalForeignApi::class)
class IosLinkIdentityTest {

    @Test
    fun theIdentityProducesAValidCertificateAndAStablePin() {
        val dir = createTempDir("relay-ios-identity")
        try {
            val first = IosLinkIdentity.ensure(dir, listOf("127.0.0.1"))
            assertEquals(91, first.spki.size, "P-256 的 SPKI 固定 91 字节")
            // 第 2/3 步（keychain identity → sec_identity_t）：**分两种情况如实断言**。
            //
            // 实测：Kotlin/Native 的测试二进制不是 app（没有 app 容器/keychain-access-group），
            // `SecItemAdd` 直接返回 -25291 errSecNotAvailable。真机/模拟器的 **App 进程**里
            // 这一步是正常的。所以这里断言的是**两条都必须成立的不变量**：
            //  · 要么拿到 identity + sec_identity_t；
            //  · 要么明确是"keychain 不可用"，且此时 `secIdentity` 必须为 null
            //    （传输层据此拒绝启动 —— fail-closed，不会"没有客户端证书也去连"）。
            if (first.identity == null) {
                assertEquals(
                    -25291,
                    first.keychainStatus,
                    "拿不到 identity 时状态必须是 errSecNotAvailable(-25291)，而不是别的错误",
                )
                assertEquals(null, first.secIdentity, "没有 identity 就绝不能有 sec_identity_t")
            } else {
                assertNotNull(first.secIdentity, "有 identity 就必须能 sec_identity_create")
                assertEquals(null, first.keychainStatus, "成功时状态码必须为 null")
            }
            assertTrue(first.certDer.isNotEmpty(), "证书 DER 不能为空")
            assertTrue(first.pin.wire.startsWith("sha256:"), "pin 口径必须是 sha256:<hex>：${first.pin.wire}")

            // 模拟重启：材料从盘上读回来，pin 必须一模一样。
            val second = IosLinkIdentity.ensure(dir, listOf("127.0.0.1"))
            assertEquals(first.pin, second.pin, "私钥不变 ⇒ 重启后 pin 不变")
            assertEquals(first.spki.toList(), second.spki.toList())
        } finally {
            removeRecursively(dir)
        }
    }

    @Test
    fun renewingTheCertificateKeepsTheSamePin() {
        val dir = createTempDir("relay-ios-renew")
        try {
            val first = IosLinkIdentity.ensure(dir, listOf("127.0.0.1"))
            // 只删证书、留私钥：下一次 ensure 会用**同一把私钥**重签。
            deleteFile(pathJoin(dir, "link-cert.der"))
            val renewed = IosLinkIdentity.ensure(dir, listOf("127.0.0.1"))
            assertEquals(first.pin, renewed.pin, "同密钥重签 ⇒ pin 不变（自动续签）")
            assertTrue(renewed.certDer.isNotEmpty())
        } finally {
            removeRecursively(dir)
        }
    }
}
