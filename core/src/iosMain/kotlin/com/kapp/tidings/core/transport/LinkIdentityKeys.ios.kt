// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

/**
 * iOS 私钥外部表示 ↔ 平台无关标量 的互转。
 *
 * **形状（模拟器实测）**：`SecKeyCopyExternalRepresentation` 对 EC P-256 **私钥**返回
 * **97 字节**：65 字节非压缩公钥点（`04 || X || Y`）+ 32 字节标量 —— ANSI X9.63 的
 * 私钥 blob 形状，容器文件名 `link-key.x963` 即由此来。恢复包里装的是**标量**
 * （平台无关，桌面/Android 是 PKCS#8 里剥出来的同一个标量），所以在 iOS 落地时：
 *  · 导出：取外部表示的**后 32 字节**；
 *  · 导入：标量 + 恢复包里的 SPKI（尾部 65 字节就是公钥点）拼回外部表示，
 *    `SecKeyCreateWithData` 对这种形状照单全收 —— 容器文件保持原样，`ensure` 不用改。
 */
object IosLinkIdentityKeys {

    /** 外部表示总长 = 公钥点 65 + 标量 32。 */
    const val EXTERNAL_BYTES: Int = 97

    private const val POINT_BYTES = 65
    private const val SCALAR_BYTES = 32

    /** X9.63 外部表示 → 32 字节标量（恢复包导出路径）。 */
    fun scalarFromExternal(external: ByteArray): ByteArray {
        require(external.size == EXTERNAL_BYTES) { "EC private external repr must be $EXTERNAL_BYTES bytes, got ${external.size}" }
        return external.copyOfRange(POINT_BYTES, EXTERNAL_BYTES)
    }

    /** SPKI（91 字节）→ 65 字节原始公钥点（尾部）。 */
    fun pointFromSpki(spki: ByteArray): ByteArray {
        require(spki.size > POINT_BYTES) { "SPKI too short: ${spki.size}" }
        return spki.copyOfRange(spki.size - POINT_BYTES, spki.size)
    }

    /** 标量 + SPKI → X9.63 外部表示（恢复包导入路径）。 */
    fun externalFromScalarAndSpki(scalar: ByteArray, spki: ByteArray): ByteArray {
        require(scalar.size == SCALAR_BYTES) { "P-256 scalar must be $SCALAR_BYTES bytes, got ${scalar.size}" }
        return pointFromSpki(spki) + scalar
    }
}
