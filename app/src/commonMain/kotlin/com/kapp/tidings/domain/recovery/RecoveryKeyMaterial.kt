// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.recovery

/**
 * 本机 TLS 身份的**平台无关**钥匙材料：P-256 标量（32 字节）+ SPKI。
 *
 * 恢复包里装的就是这两样（见 `RecoveryBundle` 的载荷）。各平台的落盘形状不同
 * （桌面/Android 是 PKCS#8 的 `link-key.pk8`，iOS 是 X9.63 的 `link-key.x963`），
 * 互转收在每个平台的 actual 里 —— 导出读、导入写，调用方只见标量。
 */
data class IdentityKeyMaterial(
    val ecPrivateScalar: ByteArray,
    val spki: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is IdentityKeyMaterial &&
            other.ecPrivateScalar.contentEquals(ecPrivateScalar) &&
            other.spki.contentEquals(spki)

    override fun hashCode(): Int = ecPrivateScalar.contentHashCode() * 31 + spki.contentHashCode()
}

/** 读当前身份的钥匙材料；身份还没生成（首启前）返回 null。 */
expect fun readIdentityKeyMaterial(linkIdentityDir: String): IdentityKeyMaterial?

/**
 * 把恢复出的钥匙材料写成平台的容器文件。
 *
 * 只写 key 与 spki，**不写证书**：自签证书由传输层首启时用同一把私钥重签
 * （私钥不变 ⇒ pin 不变），恢复流程不需要证书参与。
 */
expect fun writeIdentityKeyMaterial(linkIdentityDir: String, scalar: ByteArray, spki: ByteArray)
