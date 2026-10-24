// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.recovery

import com.kapp.tidings.core.crypto.EcP256Pkcs8
import com.kapp.tidings.core.fs.deleteFile
import com.kapp.tidings.core.fs.makeDirectories
import com.kapp.tidings.core.fs.openRandomAccess
import com.kapp.tidings.core.fs.pathJoin
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.fs.use

/**
 * 桌面 / Android（JVM 系）的钥匙材料互转：容器文件是 PKCS#8 的 `link-key.pk8`，
 * 与恢复包里的标量之间经 [EcP256Pkcs8] 往返。
 */
actual fun readIdentityKeyMaterial(linkIdentityDir: String): IdentityKeyMaterial? {
    val pkcs8 = readFileBytes(pathJoin(linkIdentityDir, "link-key.pk8")) ?: return null
    val spki = readFileBytes(pathJoin(linkIdentityDir, "link-pub.spki")) ?: return null
    val scalar = runCatching { EcP256Pkcs8.pkcs8ToScalar(pkcs8) }.getOrNull() ?: return null
    return IdentityKeyMaterial(scalar, spki)
}

actual fun writeIdentityKeyMaterial(linkIdentityDir: String, scalar: ByteArray, spki: ByteArray) {
    makeDirectories(linkIdentityDir)
    // 证书不写（见 expect 侧 KDoc）：传输层首启用同一把私钥重签，pin 不变。
    // 但**旧证书必须删掉** —— 它对应的是恢复前那把钥匙（或已被上一份身份用过），
    // 留着会被 LinkTls 当作"新鲜证书"直接用，与恢复进来的钥匙不一致。
    deleteFile(pathJoin(linkIdentityDir, "link-cert.der"))
    writeBytes(pathJoin(linkIdentityDir, "link-key.pk8"), EcP256Pkcs8.scalarToPkcs8(scalar))
    writeBytes(pathJoin(linkIdentityDir, "link-pub.spki"), spki)
}

private fun writeBytes(path: String, bytes: ByteArray) {
    deleteFile(path)
    openRandomAccess(path, forWrite = true).use { it.append(bytes, bytes.size) }
}
