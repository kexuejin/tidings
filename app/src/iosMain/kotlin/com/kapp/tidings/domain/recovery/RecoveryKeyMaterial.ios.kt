// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.recovery

import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.makeDirectories
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.fs.use
import com.kapp.tidings.core.transport.IosLinkIdentityKeys

/**
 * iOS 的钥匙材料互转：容器文件是 X9.63 外部表示 `link-key.x963`
 * （公钥点 65 + 标量 32，见 [IosLinkIdentityKeys] 的 KDoc），与恢复包里的标量互拆。
 */
actual fun readIdentityKeyMaterial(linkIdentityDir: String): IdentityKeyMaterial? {
    val external = readFileBytes(pathJoin(linkIdentityDir, "link-key.x963")) ?: return null
    val spki = readFileBytes(pathJoin(linkIdentityDir, "link-pub.spki")) ?: return null
    val scalar = runCatching { IosLinkIdentityKeys.scalarFromExternal(external) }.getOrNull() ?: return null
    return IdentityKeyMaterial(scalar, spki)
}

actual fun writeIdentityKeyMaterial(linkIdentityDir: String, scalar: ByteArray, spki: ByteArray) {
    makeDirectories(linkIdentityDir)
    // 证书不写（同 JVM 侧理由）：首启用同一把私钥重签；旧证书必须删，理由同 JVM 侧。
    deleteFile(pathJoin(linkIdentityDir, "link-cert.der"))
    val external = IosLinkIdentityKeys.externalFromScalarAndSpki(scalar, spki)
    writeBytes(pathJoin(linkIdentityDir, "link-key.x963"), external)
    writeBytes(pathJoin(linkIdentityDir, "link-pub.spki"), spki)
}

private fun writeBytes(path: String, bytes: ByteArray) {
    deleteFile(path)
    openRandomAccess(path, forWrite = true).use { it.append(bytes, bytes.size) }
}
