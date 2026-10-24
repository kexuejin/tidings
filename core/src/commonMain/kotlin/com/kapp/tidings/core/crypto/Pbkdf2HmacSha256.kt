// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

/**
 * PBKDF2-HMAC-SHA-256（RFC 8018 §5.2），恢复口令的密钥派生。
 *
 * 为什么纯 Kotlin 而不是平台实现：Android 默认 provider 的 `PBKDF2WithHmacSHA256`
 * 要 API 26（本仓 minSdk 24）；iOS 得走 CommonCrypto；三条 actual 又各有各的
 * 字节口径 —— 而 KDF 恰恰是"桌面导出、手机导入，两侧必须派生出同一把钥匙"的
 * 跨端契约。纯 commonMain 一份实现 + 公开测试向量钉死，与 [Sha256] 同一立场。
 *
 * 性能：每轮 2 次块压缩（ipad/opad 中间态已缓存，见 [HmacSha256.compute]）。
 * 六十万轮约 120 万次压缩，桌面 JVM 亚秒级；导出/导入各付一次，频率极低，可接受。
 *
 * 迭代次数不写死在实现里 —— 它是恢复包格式的**字段**（随文件存储），
 * 将来调参不影响旧文件导入。
 */
fun pbkdf2HmacSha256(
    passwordUtf8: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBytes: Int,
): ByteArray {
    require(iterations >= 1) { "iterations must be >= 1" }
    require(keyLengthBytes >= 1) { "keyLengthBytes must be >= 1" }
    val hmac = HmacSha256(passwordUtf8)
    val out = ByteArray(keyLengthBytes)
    var filled = 0
    var blockIndex = 1
    while (filled < keyLengthBytes) {
        val block = f(hmac, salt, blockIndex, iterations)
        val n = minOf(block.size, keyLengthBytes - filled)
        block.copyInto(out, filled, 0, n)
        filled += n
        blockIndex++
    }
    return out
}

/** 单个 DK 块：F(P, S, c, i) = U1 xor U2 xor … xor Uc，Ui = PRF(P, U(i-1))。 */
private fun f(hmac: HmacSha256, salt: ByteArray, blockIndex: Int, iterations: Int): ByteArray {
    val first = ByteArray(salt.size + 4)
    salt.copyInto(first)
    first[salt.size] = (blockIndex ushr 24).toByte()
    first[salt.size + 1] = (blockIndex ushr 16).toByte()
    first[salt.size + 2] = (blockIndex ushr 8).toByte()
    first[salt.size + 3] = blockIndex.toByte()

    var u = hmac.compute(first)
    val acc = u.copyOf()
    for (round in 1 until iterations) {
        u = hmac.compute(u)
        for (i in acc.indices) acc[i] = (acc[i].toInt() xor u[i].toInt()).toByte()
    }
    return acc
}
