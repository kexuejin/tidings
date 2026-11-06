// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 产物校验状态判据（2b，D-015 ②）。三钉：
 * 1. **两态不混判**（规格红线）：无 sha256（UNDECLARED）与文件缺失（FILE_MISSING）
 *    是两种不同的「没法校验」，界面上说的话完全不同 —— 混判等于把「没下载」说成
 *    「旧产物没报」；
 * 2. **MISMATCH 真的在比内容**：declared 与 actual 只差一个字符也必须红 —— 摘哈希
 *    比较（比如只比长度）时这条用例红；
 * 3. **坏形状凭据不作数**：declared 是大写/63 位 ⇒ UNDECLARED（没有凭据），
 *    而不是 MISMATCH —— 否则对端一个写坏的键就能把产物刷成「校验失败」噪声。
 *
 * 反向验证路径：判据里 declared/actual 比较换成 `declared.length == actualHex.length`
 * ⇒ 差一字符用例红；删掉 isSha256Hex 守卫 ⇒ 坏形状用例红。
 */
class ArtifactVerificationTest {

    private val declared = "a".repeat(64)
    private val actualSame = "a".repeat(64)
    private val actualOneBitOff = "b" + "a".repeat(63)

    @Test
    fun missingDeclarationIsUndeclaredEvenWhenFileIsAlsoAbsent() {
        assertEquals(
            ArtifactVerification.Status.UNDECLARED,
            ArtifactVerification.verify(declared = null, actualHex = null),
            "没报就是没报 —— 不是文件缺失",
        )
        assertEquals(
            ArtifactVerification.Status.UNDECLARED,
            ArtifactVerification.verify(declared = null, actualHex = actualSame),
            "帧没带凭据时文件在也谈不上校验",
        )
    }

    @Test
    fun declaredButUnreadableFileIsFileMissingNotUndeclared() {
        assertEquals(
            ArtifactVerification.Status.FILE_MISSING,
            ArtifactVerification.verify(declared = declared, actualHex = null),
            "有凭据、没文件：校验无从谈起 —— 与「没报」分开（规格红线）",
        )
    }

    @Test
    fun matchingDigestVerifiesAndOneCharacterOffFails() {
        assertEquals(
            ArtifactVerification.Status.OK,
            ArtifactVerification.verify(declared, actualSame),
        )
        assertEquals(
            ArtifactVerification.Status.MISMATCH,
            ArtifactVerification.verify(declared, actualOneBitOff),
            "差一个字符就是失败 —— 摘长度比较时这条红（判据真的在比内容）",
        )
        assertNotEquals(
            ArtifactVerification.Status.OK,
            ArtifactVerification.verify(declared, actualOneBitOff),
        )
    }

    @Test
    fun malformedDeclarationCountsAsUndeclaredNotMismatch() {
        assertEquals(
            ArtifactVerification.Status.UNDECLARED,
            ArtifactVerification.verify(declared.uppercase(), actualSame),
            "大写 hex = 坏凭据 = 没有凭据，不是校验失败",
        )
        assertEquals(
            ArtifactVerification.Status.UNDECLARED,
            ArtifactVerification.verify("a".repeat(63), actualSame),
            "63 位同理",
        )
    }
}
