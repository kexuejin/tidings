// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.persist

import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.fs.use
import com.kapp.tidings.core.testing.createTempDir
import com.kapp.tidings.core.testing.removeRecursively
import com.kapp.tidings.core.transport.IosLinkIdentity
import com.kapp.tidings.core.transport.IosLinkIdentityKeys
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS 端到端：真身份（Security.framework 生成的 EC P-256 私钥）装进恢复包再拆出来，
 * 装回一个新容器目录，`ensure` 必须用**同一把钥匙**重签 —— pin 不变。
 *
 * 证据链：容器里的 X9.63 外部表示（97B）剥出标量 → 恢复包 round-trip →
 * 标量 + SPKI 拼回外部表示 → `ensure` 用同一把钥匙。
 */
class RecoveryBundleIosIdentityTest {

    private val password = "correct horse battery staple"

    private fun writeBytes(path: String, bytes: ByteArray) {
        openRandomAccess(path, forWrite = true).use { it.append(bytes, bytes.size) }
    }

    @Test
    fun realIdentityKeyRoundTripsThroughTheBundle() {
        val dir = createTempDir("relay-recovery-ios-src")
        val restoredDir = createTempDir("relay-recovery-ios-dst")
        try {
            val first = IosLinkIdentity.ensure(dir, listOf("127.0.0.1"))
            val keyExternal = readFileBytes(pathJoin(dir, "link-key.x963"))
            assertEquals(
                IosLinkIdentityKeys.EXTERNAL_BYTES,
                keyExternal?.size,
                "iOS 的 EC 私钥外部表示 = 公钥点 65 + 标量 32",
            )

            val payload = RecoveryIdentityPayload(
                nodeId = "01234567-89ab-cdef-0123-456789abcdef",
                ecPrivateScalar = IosLinkIdentityKeys.scalarFromExternal(keyExternal!!),
                spki = first.spki,
            )
            assertEquals(first.spki.toList(), payload.spki.toList(), "载荷里的 SPKI 就是身份的 SPKI")
            val bundle = RecoveryBundle.exportIdentity(payload, password, nowEpochMs = 1_700_000_000_000)
            val restored = RecoveryBundle.importIdentity(bundle, password)
            assertEquals(payload, restored, "恢复包 round-trip 必须逐字段相等")

            // 把恢复出来的标量 + SPKI 拼回外部表示，写进一个新容器（只写 key 与 spki，
            // 不给 cert —— 强制走重签路径），ensure 必须用同一把钥匙重签 ⇒ pin 与原身份一致。
            val rebuilt = IosLinkIdentityKeys.externalFromScalarAndSpki(restored.ecPrivateScalar, restored.spki)
            assertEquals(keyExternal.toList(), rebuilt.toList(), "拼回的外部表示必须与原来逐字节一致")
            writeBytes(pathJoin(restoredDir, "link-key.x963"), rebuilt)
            writeBytes(pathJoin(restoredDir, "link-pub.spki"), restored.spki)
            val reopened = IosLinkIdentity.ensure(restoredDir, listOf("127.0.0.1"))
            assertEquals(first.pin, reopened.pin, "恢复包拆出的标量必须还原出同一把钥匙（pin 不变）")
        } finally {
            removeRecursively(dir)
            removeRecursively(restoredDir)
        }
    }
}
