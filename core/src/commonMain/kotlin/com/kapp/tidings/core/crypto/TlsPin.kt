// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.crypto

import kotlin.jvm.JvmInline

/**
 * 「证书固定」用的 **SPKI 指纹**，wire 形态固定为 `sha256:` + 64 位小写十六进制。
 *
 * ## 为什么固定的是 SPKI 而不是整张证书
 *
 * 证书会重签（快到期、SAN 里缺了本机新地址 —— 参考实现 DS-Bot 的
 * `ds-bot/src/mobile/local-certificate.js` 就是这么做的）。只要**私钥不变**，重签后
 * SPKI 不变 ⇒ pin 不变 ⇒ 用户不需要重新配对。反过来，固定整张证书会让每次重签都要求
 * 用户重扫二维码，那个功能最后一定会被用户当成"又坏了"。
 *
 * ## 为什么值得单独一个值类型
 *
 * pin 会同时出现在三个地方：配对二维码的解析（`QrPairing`）、落盘的信任记录、TLS
 * 握手后的校验。三处各写一遍"是不是 sha256: + 64 位 hex"，迟早会出现一处松一处严 ——
 * 而**松的那一处就是漏洞**（例如把 `sha256:abc` 这种截断值当成合法 pin）。
 * 校验只在这里定义一次。
 *
 * ## 这一版刻意**不做**什么
 *
 * 不校验证书、不连网、不被任何传输路径调用。它只是把"什么算一条合法的 pin"钉下来，
 * 让后续分期（见 `docs/public-transport-security.md`）有一个可单测的接缝。
 * 因此它的引入对现有明文路径**零影响**。
 */
@JvmInline
value class TlsPin private constructor(val wire: String) {

    /**
     * 这条 pin 是否与给定的 SPKI（DER 字节）匹配。
     *
     * 只接受 **DER** 形态的 SubjectPublicKeyInfo（与 DS-Bot 的 `tlsPin` 同口径）：
     * PEM 文本、整张证书、裸公钥点都不是这个入参。
     */
    fun matches(spkiDer: ByteArray): Boolean = wire == ofSpki(spkiDer).wire

    override fun toString(): String = wire

    companion object {
        /** wire 前缀。大小写不敏感地接受，但一律归一成小写。 */
        const val PREFIX: String = "sha256:"

        /** 十六进制摘要的字符数（SHA-256 = 32 字节）。 */
        const val HEX_LENGTH: Int = 64

        /**
         * 严格解析一条 pin；**任何**不满足的都返回 null。
         *
         * 调用方拿到 null 时的正确动作是"丢弃这个安全声明"，而不是让整个配对失败 ——
         * 宁可少一条 pin，也不要因为二维码里多写了一个字符就配不上对。
         *
         * 大小写与两侧空白归一：手抄/大写是常见输入，不归一就会变成"看起来一样的
         * 两条 pin 其实不相等"，而那种 bug 只会在真机握手时才暴露。
         */
        fun parse(raw: String?): TlsPin? {
            val text = raw?.trim().orEmpty()
            if (!text.startsWith(PREFIX, ignoreCase = true)) return null
            val hex = text.substring(PREFIX.length)
            if (hex.length != HEX_LENGTH) return null
            if (hex.any { !it.isHexDigit() }) return null
            return TlsPin(PREFIX + hex.lowercase())
        }

        /**
         * 由 SubjectPublicKeyInfo（DER）算一条 pin —— 与 DS-Bot 的 `sha256:SPKI` 同口径。
         *
         * 这里用 [sha256Hex] 而不是另写一份摘要实现：本仓的 SHA-256 已经过标准向量测试，
         * 复制一份出来只会多一处可能漂移的地方。
         */
        fun ofSpki(spkiDer: ByteArray): TlsPin = TlsPin(PREFIX + sha256Hex(spkiDer))
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
