// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateRef
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * P1 修复的反向验证：重装（容器丢失）后必须从钥匙串**收养**幸存旧钥，绝不允许
 * 生成新钥、更不允许删掉幸存的 key 项。真实 SecItem 在测试进程里不可用（-25291），
 * 所以决策逻辑用假实现测；SecItem 本身的口径由真机验收覆盖。
 */
@OptIn(ExperimentalForeignApi::class)
class IosLinkIdentityKeychainFallbackTest {

    /** 记录写入行为的假 keychain：真实 SecItem 在测试进程不可用，决策逻辑全靠它测。 */
    private class FakeKeychainStore(
        initial: KeychainIdentitySnapshot? = null,
    ) : LinkKeychainStore {
        var snapshot: KeychainIdentitySnapshot? = initial
            private set

        /** 「钥匙串里**已存在的不同钥匙**被换掉」的次数 —— 首次签发（原本为空）不算。 */
        var keyReplacedCount = 0
            private set
        var lastMode: KeychainWriteMode? = null
            private set

        override fun load(): KeychainIdentitySnapshot? = snapshot

        override fun write(
            key: SecKeyRef,
            certificate: SecCertificateRef,
            mode: KeychainWriteMode,
        ): KeychainWriteResult {
            lastMode = mode
            val keyExternal = exportData(SecKeyCopyExternalRepresentation(key, null))
            if (mode == KeychainWriteMode.ADD_KEY_AND_CERT) {
                val existing = snapshot
                if (existing != null && !existing.keyExternal.contentEquals(keyExternal)) keyReplacedCount++
            }
            snapshot = KeychainIdentitySnapshot(
                keyExternal = keyExternal,
                certDer = exportData(SecCertificateCopyData(certificate)),
            )
            // 假实现造不出真的 SecIdentityRef（那需要真 keychain），如实返回"成功但无 identity"。
            return KeychainWriteResult(identity = null, status = null)
        }
    }

    @Test
    fun reinstallFallsBackToTheSurvivingKeychainKey() {
        val dirA = createTempDir("relay-ios-kf-first")
        val firstFake = FakeKeychainStore()
        val first = IosLinkIdentity.ensure(dirA, listOf("127.0.0.1"), firstFake)
        val seed = firstFake.snapshot
        assertNotNull(seed, "首次 ensure 必须把身份写进 keychain store")
        removeRecursively(dirA)

        // 模拟重装：容器目录换新（旧目录已删），钥匙串里是幸存的旧身份。
        val dirB = createTempDir("relay-ios-kf-reinstall")
        try {
            val fake = FakeKeychainStore(seed)
            val restored = IosLinkIdentity.ensure(dirB, listOf("127.0.0.1"), fake)
            assertEquals(first.pin, restored.pin, "容器丢失后必须收养钥匙串幸存旧钥，pin 不变")
            assertEquals(IosIdentitySource.KEYCHAIN_FALLBACK, restored.source, "来源必须如实标成钥匙串回退")
            assertEquals(0, fake.keyReplacedCount, "收养路径绝不允许换掉幸存的 key 项")
            assertEquals(KeychainWriteMode.UPDATE_CERT_ONLY, fake.lastMode, "收养只刷证书，不重写 key")
            assertNotNull(
                readFileBytes(pathJoin(dirB, "link-key.x963")),
                "收养的钥匙必须落回容器，下次启动回到容器稳态",
            )
            assertNotNull(readFileBytes(pathJoin(dirB, "link-cert.der")), "重签的证书也要落回容器")
        } finally {
            removeRecursively(dirB)
        }
    }

    @Test
    fun renewalAndSteadyStateNeverTouchTheKeychainKey() {
        val dir = createTempDir("relay-ios-kf-renew")
        val fake = FakeKeychainStore()
        try {
            val first = IosLinkIdentity.ensure(dir, listOf("127.0.0.1"), fake)
            assertEquals(0, fake.keyReplacedCount, "首次签发时钥匙串为空，不存在替换")

            // 稳态：什么都不删，再进一次也只是刷证书。
            val steady = IosLinkIdentity.ensure(dir, listOf("127.0.0.1"), fake)
            assertEquals(first.pin, steady.pin, "稳态重启 pin 不变")
            assertEquals(0, fake.keyReplacedCount, "稳态绝不允许动 key 项（旧实现每次启动都重写）")
            assertEquals(KeychainWriteMode.UPDATE_CERT_ONLY, fake.lastMode)

            // 重签：只删容器证书，同一把私钥重签，key 项仍然不动。
            deleteFile(pathJoin(dir, "link-cert.der"))
            val renewed = IosLinkIdentity.ensure(dir, listOf("127.0.0.1"), fake)
            assertEquals(first.pin, renewed.pin, "同密钥重签 ⇒ pin 不变")
            assertEquals(0, fake.keyReplacedCount, "重签也不允许动 key 项")
            assertEquals(KeychainWriteMode.UPDATE_CERT_ONLY, fake.lastMode)
        } finally {
            removeRecursively(dir)
        }
    }

    @Test
    fun containerKeyBeatsADivergentKeychainKey() {
        val ips = listOf("127.0.0.1")
        val dirA = createTempDir("relay-ios-kf-ca")
        val dirB = createTempDir("relay-ios-kf-cb")
        try {
            val fakeA = FakeKeychainStore()
            val a = IosLinkIdentity.ensure(dirA, ips, fakeA)
            val fakeB = FakeKeychainStore()
            val b = IosLinkIdentity.ensure(dirB, ips, fakeB)
            assertNotEquals(a.pin, b.pin, "两套独立身份必须有不同 pin（前提校验）")

            // 容器 A 还在、钥匙串里却是 B 的身份（模拟"导入旧身份后钥匙串还留着别的钥匙"）：
            // 容器是权威 ⇒ 保留容器钥匙；钥匙串 key 项被替换 —— 这是唯一允许删 key 项的分支。
            val mixed = FakeKeychainStore(fakeB.snapshot)
            val again = IosLinkIdentity.ensure(dirA, ips, mixed)
            assertEquals(a.pin, again.pin, "容器钥匙必须胜过钥匙串里的陌生钥匙")
            assertEquals(IosIdentitySource.CONTAINER, again.source)
            assertEquals(KeychainWriteMode.ADD_KEY_AND_CERT, mixed.lastMode, "钥匙串与容器不一致 ⇒ 全量重写")
            assertEquals(1, mixed.keyReplacedCount, "轮换语义：B 的 key 项被 A 换掉（唯一允许的分支）")
        } finally {
            removeRecursively(dirA)
            removeRecursively(dirB)
        }
    }
}
