// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

/**
 * IPv6 地址作用域分类。
 *
 * 对应 RFC 4291 / RFC 4193 标准。
 */
enum class Ipv6Scope {
    /** 2000::/3 全球单播公网地址 (GUA) —— 可用于跨网络直连 */
    GLOBAL,

    /** fc00::/7 唯一本地地址 (ULA) —— 仅在有对应路由的特定私网内可达 */
    UNIQUE_LOCAL,

    /** fe80::/10 链路本地地址 (Link-Local) —— 需网卡 scope (%wlan0)，不可跨设备路由 */
    LINK_LOCAL,

    /** ::1 回环地址 */
    LOOPBACK,

    /** 其他类型（如未指定 ::、IPv4-mapped、组播等） */
    OTHER,
}

/**
 * 纯 Kotlin 实现的跨平台 IP 地址工具集（无平台特定 socket/net 依赖）。
 */
object IpAddressUtils {

    /**
     * 去除方括号（若有），如 "[240e:...]" -> "240e:..."。
     */
    fun unbracket(host: String): String {
        val trimmed = host.trim()
        return if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
    }

    /**
     * 若为 IPv6 地址且未带方括号，则自动加上方括号；IPv4 或主机名保持原样。
     */
    fun bracketIfIpv6(host: String): String {
        val clean = host.trim()
        if (clean.startsWith("[") && clean.endsWith("]")) return clean
        return if (isIpv6(clean)) "[$clean]" else clean
    }

    /**
     * 判断是否为 IPv6 地址格式。
     */
    fun isIpv6(host: String): Boolean {
        val unbracketed = unbracket(host)
        if (unbracketed.isEmpty() || !unbracketed.contains(':')) return false
        return parseIpv6Bytes(unbracketed) != null
    }

    /**
     * 判断是否为 IPv4 地址格式。
     */
    fun isIpv4(host: String): Boolean {
        val trimmed = host.trim()
        val parts = trimmed.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val v = part.toIntOrNull() ?: return false
            v in 0..255 && (part == "0" || !part.startsWith("0"))
        }
    }

    /**
     * 判断是否为可用于外网直连的公网 IPv6 (GUA)，且无本地网卡 scope 后缀。
     */
    fun isPublicGuaIpv6(host: String): Boolean {
        val unbracketed = unbracket(host)
        if (unbracketed.contains('%')) return false
        val bytes = parseIpv6Bytes(unbracketed) ?: return false
        return getIpv6Scope(bytes) == Ipv6Scope.GLOBAL
    }

    /**
     * 获取指定 IPv6 地址的作用域。
     */
    fun getIpv6Scope(host: String): Ipv6Scope? {
        val unbracketed = unbracket(host)
        if (unbracketed.contains('%')) return Ipv6Scope.LINK_LOCAL
        val bytes = parseIpv6Bytes(unbracketed) ?: return null
        return getIpv6Scope(bytes)
    }

    private fun getIpv6Scope(bytes: ByteArray): Ipv6Scope {
        if (bytes.size != 16) return Ipv6Scope.OTHER

        // ::1 回环
        val isLoopback = (0..14).all { bytes[it] == 0.toByte() } && bytes[15] == 1.toByte()
        if (isLoopback) return Ipv6Scope.LOOPBACK

        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // fe80::/10 (Link-Local): 1111 1110 10xx xxxx
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return Ipv6Scope.LINK_LOCAL

        // fc00::/7 (ULA): 1111 110x
        if ((b0 and 0xFE) == 0xFC) return Ipv6Scope.UNIQUE_LOCAL

        // 2000::/3 (GUA): 001x xxxx
        if ((b0 and 0xE0) == 0x20) return Ipv6Scope.GLOBAL

        return Ipv6Scope.OTHER
    }

    /**
     * 解析 IPv6 地址为 16 字节数组。
     */
    fun parseIpv6Bytes(input: String): ByteArray? {
        val clean = unbracket(input).substringBefore('%').trim()
        if (clean.isEmpty()) return null

        val parts = clean.split("::")
        if (parts.size > 2) return null // 不能超过一个 ::

        val leftPart = parts[0]
        val rightPart = if (parts.size == 2) parts[1] else null

        val leftWords = if (leftPart.isEmpty()) emptyList() else leftPart.split(':')
        val rightWords = if (rightPart.isNullOrEmpty()) emptyList() else rightPart.split(':')

        val totalExplicitWords = leftWords.size + rightWords.size
        if (parts.size == 1 && totalExplicitWords != 8) return null
        if (parts.size == 2 && totalExplicitWords >= 8) return null

        val leftInts = leftWords.map { parseHexWord(it) ?: return null }
        val rightInts = rightWords.map { parseHexWord(it) ?: return null }

        val missingWords = 8 - (leftInts.size + rightInts.size)
        val allWords = ArrayList<Int>(8)
        allWords.addAll(leftInts)
        for (i in 0 until missingWords) {
            allWords.add(0)
        }
        allWords.addAll(rightInts)

        val bytes = ByteArray(16)
        for (i in 0 until 8) {
            val w = allWords[i]
            bytes[i * 2] = ((w ushr 8) and 0xFF).toByte()
            bytes[i * 2 + 1] = (w and 0xFF).toByte()
        }
        return bytes
    }

    private fun parseHexWord(s: String): Int? {
        if (s.isEmpty() || s.length > 4) return null
        return s.toIntOrNull(16)?.takeIf { it in 0..0xFFFF }
    }
}
