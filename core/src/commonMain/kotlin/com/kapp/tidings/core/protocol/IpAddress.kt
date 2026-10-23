// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

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
     * 判断两个 IPv6 地址是否处于同一个前缀（如标准 /64，用于兼容 RFC 4941 临时/隐私地址与固定地址的匹配）。
     */
    fun sameIpv6Prefix(a: String, b: String, prefixBits: Int = 64): Boolean {
        val bytesA = parseIpv6Bytes(a) ?: return false
        val bytesB = parseIpv6Bytes(b) ?: return false
        val fullBytes = prefixBits / 8
        for (i in 0 until fullBytes) {
            if (bytesA[i] != bytesB[i]) return false
        }
        val remBits = prefixBits % 8
        if (remBits > 0) {
            val mask = ((0xFF shl (8 - remBits)) and 0xFF).toByte()
            if ((bytesA[fullBytes].toInt() and mask.toInt()) != (bytesB[fullBytes].toInt() and mask.toInt())) return false
        }
        return true
    }

    /**
     * 判断两个主机地址是否指向同一台主机（支持大小写无关、方括号剔除、以及 IPv6 /64 同前缀匹配）。
     */
    fun isSameHost(a: String, b: String): Boolean {
        val cleanA = unbracket(a).trim()
        val cleanB = unbracket(b).trim()
        if (cleanA.isEmpty() || cleanB.isEmpty()) return false
        if (cleanA.equals(cleanB, ignoreCase = true)) return true
        if (isIpv6(cleanA) && isIpv6(cleanB)) {
            return sameIpv6Prefix(cleanA, cleanB, 64)
        }
        return false
    }

    /**
     * 这个地址是不是「局域网/本机可达」—— K1 只允许在**局域网**上跑明文长连接。
     *
     * 判据刻意严格：IPv4 回环/链路本地/RFC1918；IPv6 回环/链路本地/ULA；带 %scope 的一律算
     * 链路本地。主机名、公网 IPv4、公网 IPv6(GUA) 一律 false —— 公网要等 K2 的 TLS + 双向 pin。
     */
    fun isPrivateOrLocal(host: String): Boolean {
        val clean = unbracket(host).trim()
        if (clean.isEmpty()) return false
        if (clean.contains('%')) return true
        if (isIpv4(clean)) {
            val parts = clean.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            val a = parts[0]
            val b = parts[1]
            return a == 127 || a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254)
        }
        val bytes = parseIpv6Bytes(clean) ?: return false
        return when (getIpv6Scope(bytes)) {
            Ipv6Scope.LOOPBACK, Ipv6Scope.LINK_LOCAL, Ipv6Scope.UNIQUE_LOCAL -> true
            else -> false
        }
    }

    /**
     * 这个地址是不是**代理软件 TUN 的虚拟地址**（`198.18.0.0/15`）。
     *
     * ## 为什么必须单独识别
     *
     * 代理软件开 TUN 增强模式时会在本机建一个 `utun` 接口、配一个 `198.18.x.x`
     * 的虚拟地址，并装上 `0.0.0.0/1` + `128.0.0.0/1` 两条路由 —— 那等于**接管了全部 IPv4**。
     *
     * 真机现场：把它当成一个可用地址宣告出去之后，对端会去拨它，而它**永远不通**
     * （它只是代理自己的虚拟口，不对应任何可达的网络）。更糟的是它把 Tailscale /
     * ZeroTier 的包也截走了 —— 双方都收到对方的地址、都拨不通。
     *
     * `198.18.0.0/15` 是 RFC 2544 保留给网络设备基准测试的段，家用/办公网络不会用它，
     * 所以拿它当「这是代理的虚拟口」的判据是安全的。
     */
    fun isProxyTunAddress(host: String): Boolean {
        val clean = unbracket(host).trim()
        if (!isIpv4(clean)) return false
        val parts = clean.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 198 && parts[1] in 18..19
    }

    /**
     * 这个对端地址**是不是局域网**（task-40 真机）。
     *
     * 为什么不能只看 [isPrivateOrLocal]：家庭 LAN 的 IPv6 常常是**全局单播 GUA**
     * （真机实测 `2001:db8:2020:cb44::/64`），按地址分类它"就是公网"，可它其实是本机同一段
     * 局域网 —— 于是"明文 + 公网对端"被误判、把一条合法的局域网明文连接摘掉（task-32 的复查）。
     *
     * 判据（任一成立即算局域网）：
     *  1. [isPrivateOrLocal]：RFC1918 / 回环 / 链路本地 / ULA；
     *  2. 对端 IPv6 与本机某个地址**同 /64** —— GUA 不等于"公网可达"，同前缀就是同一段网。
     * 取不到本机地址（空表）时只认第 1 条（保守：不做同前缀推断）。
     */
    fun isLanAddress(peerAddress: String, localAddresses: List<String>): Boolean {
        if (isPrivateOrLocal(peerAddress)) return true
        val peer = unbracket(peerAddress).trim()
        if (!isIpv6(peer)) return false
        return localAddresses.any { local ->
            isIpv6(local) && sameIpv6Prefix(peer, unbracket(local).trim(), 64)
        }
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
