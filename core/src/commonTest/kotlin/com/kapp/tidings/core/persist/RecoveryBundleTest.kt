// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.persist

import com.kapp.tidings.core.crypto.AeadException
import com.kapp.tidings.core.crypto.aes256GcmSeal
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 恢复包容器的四条底线：
 *  1. round-trip 逐字段相等；
 *  2. 错口令 / 篡改 / 换头部 = 认证失败，绝不吐半截数据；
 *  3. manifest 与载荷**成套咬合**（§9 的"成套性断言"），只对上一半的包进不来；
 *  4. 缺了钥匙的"身份包"（只恢复 nodeId 的注入形态）在解码层就被拒。
 */
class RecoveryBundleTest {

    private val password = "correct horse battery staple"

    private fun payload(
        nodeId: String = "01234567-89ab-cdef-0123-456789abcdef",
        withPins: Boolean = true,
    ): RecoveryIdentityPayload = RecoveryIdentityPayload(
        nodeId = nodeId,
        ecPrivateScalar = ByteArray(32) { (it * 7 + 1).toByte() },
        spki = ByteArray(91) { (it * 3 + 5).toByte() },
        peerPins = if (withPins) {
            listOf(
                RecoveryIdentityPayload.PeerPin("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "sha256:" + "a1".repeat(32)),
                RecoveryIdentityPayload.PeerPin("11111111-2222-3333-4444-555555555555", "sha256:" + "0f".repeat(32)),
            )
        } else {
            emptyList()
        },
    )

    @Test
    fun roundTripsEveryField() {
        val bundle = RecoveryBundle.exportIdentity(payload(), password, nowEpochMs = 1_700_000_000_000)
        val restored = RecoveryBundle.importIdentity(bundle, password)
        assertTrue(restored == payload(), "round-trip 必须逐字段相等：$restored")
    }

    @Test
    fun twoExportsNeverHaveTheSameBytes() {
        val a = RecoveryBundle.exportIdentity(payload(), password, nowEpochMs = 1)
        val b = RecoveryBundle.exportIdentity(payload(), password, nowEpochMs = 1)
        assertFalse(a.contentEquals(b), "salt/nonce 是随机的：两份导出不可能逐字节相同")
    }

    @Test
    fun emptyPasswordIsRejectedOnBothSides() {
        val p = payload()
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.exportIdentity(p, "", nowEpochMs = 1)
        }.let { assertTrue(it.failure == RecoveryBundleFailure.PASSWORD_REQUIRED) }
        val bundle = RecoveryBundle.exportIdentity(p, password, nowEpochMs = 1)
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.importIdentity(bundle, "")
        }.let { assertTrue(it.failure == RecoveryBundleFailure.PASSWORD_REQUIRED) }
    }

    @Test
    fun wrongPasswordIsAuthFailure() {
        val bundle = RecoveryBundle.exportIdentity(payload(), password, nowEpochMs = 1)
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.importIdentity(bundle, "incorrect horse")
        }.let { assertTrue(it.failure == RecoveryBundleFailure.AUTH_FAILED) }
    }

    @Test
    fun tamperedBodyIsAuthFailure() {
        val bundle = RecoveryBundle.exportIdentity(payload(), password, nowEpochMs = 1)
        bundle[bundle.size - 3] = (bundle[bundle.size - 3].toInt() xor 0x40).toByte()
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.importIdentity(bundle, password)
        }.let { assertTrue(it.failure == RecoveryBundleFailure.AUTH_FAILED) }
    }

    @Test
    fun tamperedHeaderIsAuthFailure() {
        // 头部（salt/nonce/迭代次数）是 AAD：改一个字节 = 整帧认证失败，
        // 不存在"换一组 KDF 参数再试"的空间。
        val bundle = RecoveryBundle.exportIdentity(payload(), password, nowEpochMs = 1)
        bundle[12] = (bundle[12].toInt() xor 0x01).toByte()
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.importIdentity(bundle, password)
        }.let { assertTrue(it.failure == RecoveryBundleFailure.AUTH_FAILED) }
    }

    @Test
    fun badMagicIsFormatFailure() {
        val bundle = RecoveryBundle.exportIdentity(payload(), password, nowEpochMs = 1)
        bundle[0] = 'X'.code.toByte()
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.importIdentity(bundle, password)
        }.let { assertTrue(it.failure == RecoveryBundleFailure.BAD_FORMAT) }
    }

    @Test
    fun unknownVersionIsRejectedBeforeAuth() {
        val bundle = RecoveryBundle.exportIdentity(payload(), password, nowEpochMs = 1)
        // version 字段在头部 u16（offset 4..5）：改成 2 模拟"新版格式"。
        bundle[5] = 0x02
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.importIdentity(bundle, password)
        }.let { assertTrue(it.failure == RecoveryBundleFailure.UNSUPPORTED_VERSION) }
    }

    @Test
    fun truncatedBundleIsFormatFailure() {
        val bundle = RecoveryBundle.exportIdentity(payload(), password, nowEpochMs = 1)
        val truncated = bundle.copyOfRange(0, bundle.size - 5)
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.importIdentity(truncated, password)
        }
    }

    @Test
    fun manifestNodeIdMismatchIsRejected() {
        // 注入：manifest 说身份是 B，载荷装的是 A —— 成套校验必须在导入层拦下。
        val bytes = RecoveryBundle.sealBundle(
            manifest = RecoveryManifest(
                kind = RecoveryBundle.KIND_IDENTITY,
                nodeId = "99999999-9999-9999-9999-999999999999",
                createdAtEpochMs = 1,
                peerPinCount = 0,
            ),
            payloadBytes = RecoveryBundle.encodeIdentityPayload(payload(withPins = false)),
            password = password,
            salt = ByteArray(16) { it.toByte() },
            nonce = ByteArray(12) { it.toByte() },
        )
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.importIdentity(bytes, password)
        }.let { assertTrue(it.failure == RecoveryBundleFailure.MANIFEST_MISMATCH) }
    }

    @Test
    fun manifestPinCountMismatchIsRejected() {
        val bytes = RecoveryBundle.sealBundle(
            manifest = RecoveryManifest(
                kind = RecoveryBundle.KIND_IDENTITY,
                nodeId = "01234567-89ab-cdef-0123-456789abcdef",
                createdAtEpochMs = 1,
                peerPinCount = 1,
            ),
            payloadBytes = RecoveryBundle.encodeIdentityPayload(payload(withPins = true)),
            password = password,
            salt = ByteArray(16) { it.toByte() },
            nonce = ByteArray(12) { it.toByte() },
        )
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.importIdentity(bytes, password)
        }.let { assertTrue(it.failure == RecoveryBundleFailure.MANIFEST_MISMATCH) }
    }

    @Test
    fun payloadWithoutKeyIsRejectedAtDecode() {
        // "只恢复 nodeId"的注入形态：载荷里没有标量（长度 0）—— 解码层必须拒绝，
        // 不许把"看起来像身份"的半截包放进恢复流程。
        val nodeId = "01234567-89ab-cdef-0123-456789abcdef"
        val nodeIdBytes = nodeId.encodeToByteArray()
        val halfPayload = ByteArray(2 + nodeIdBytes.size).also {
            it[0] = ((nodeIdBytes.size ushr 8) and 0xff).toByte()
            it[1] = (nodeIdBytes.size and 0xff).toByte()
            nodeIdBytes.copyInto(it, 2)
        }
        val bytes = RecoveryBundle.sealBundle(
            manifest = RecoveryManifest(
                kind = RecoveryBundle.KIND_IDENTITY,
                nodeId = nodeId,
                createdAtEpochMs = 1,
                peerPinCount = 0,
            ),
            payloadBytes = halfPayload,
            password = password,
            salt = ByteArray(16) { (it + 1).toByte() },
            nonce = ByteArray(12) { (it + 2).toByte() },
        )
        assertFailsWith<RecoveryBundleException> {
            RecoveryBundle.importIdentity(bytes, password)
        }.let { assertTrue(it.failure == RecoveryBundleFailure.BAD_FORMAT) }
    }

    @Test
    fun malformedArgumentsFailFastWithIllegalArgument() {
        // 尺寸不对是编程错误（require），不是认证失败 —— 口径：AeadException 只留给 tag 校验不过。
        assertFailsWith<IllegalArgumentException> {
            aes256GcmSeal(ByteArray(32), ByteArray(11), ByteArray(0), ByteArray(0))
        }
    }
}
