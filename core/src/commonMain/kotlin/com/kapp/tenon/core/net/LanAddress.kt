// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.net

/**
 * 「这个地址像不像局域网」——**只看字面量**的近似判据。
 *
 * ## 它替代的是什么，又没替代什么
 *
 * 真正的「同网段」要拿本机地址表去比（`/24` 或 `/64`），那份表在平台层、且随网络切换变化。
 * 设备接入的 `auto` 档（局域网自动互信）必须先有个判据才能落地，于是这里先用
 * "RFC1918 / ULA / link-local" 这个**地址类别**近似 —— 私网地址段是「局域网」最常见的形态。
 *
 * **没替代的**：同一台机器上两条不同私网（例如公司 VPN 的 10.x 与家里的 192.168.x）会被
 * 一起当成"局域网"。所以 `auto` 档的默认值是关的、且这一档只放宽到「无密钥入站先按策略判」，
 * 真正的网段比较接上本机地址表之后这一层就会被替换掉。
 *
 * 解析不出来（主机名、空串、非法字面量）一律 **false**：不认识的东西不算局域网。
 */
public fun isLanAddressLiteral(raw: String?): Boolean {
    val host = stripPortAndBrackets(raw) ?: return false
    if (host.isEmpty()) return false
    // IPv6：ULA fc00::/7、link-local fe80::/10。
    if (host.contains(':')) {
        val lower = host.lowercase()
        // **IPv4-mapped / compatible 的 IPv6 按内嵌的 IPv4 判**：luciola 的入站回调给的就是
        // 这种形态（真机日志 `[::ffff:192.168.2.22]:43852`）。不剥这一层，一台同网段的
        // 手机在 `auto` 档下会被判成"不在局域网" ⇒ 永远走"问人"，自动信任档等于没生效。
        val embedded = lower.substringBefore('%').substringAfterLast(':')
        if (embedded.contains('.') && !lower.endsWith(".")) return isLanAddressLiteral(embedded)
        if (lower.startsWith("fe8") || lower.startsWith("fe9") ||
            lower.startsWith("fea") || lower.startsWith("feb")
        ) {
            return isHexGroups(lower)
        }
        if (lower.startsWith("fc") || lower.startsWith("fd")) return isHexGroups(lower)
        return false
    }
    // IPv4：10/8、172.16/12、192.168/16、127/8（同机）。
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    return when {
        octets[0] == 10 -> true
        octets[0] == 127 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        else -> false
    }
}

/** `203.0.113.7:27878` / `[fe80::1]:27878` / `fe80::1` → 纯地址；认不出返回 null。 */
private fun stripPortAndBrackets(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (text.startsWith("[")) {
        val end = text.indexOf(']')
        return if (end > 0) text.substring(1, end) else null
    }
    // 只有一个冒号 ⇒ host:port；多个冒号 ⇒ IPv6 字面量（不带方括号）。
    val colons = text.count { it == ':' }
    if (colons == 1) {
        val host = text.substringBefore(':')
        val port = text.substringAfter(':').toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host
    }
    return text
}

/** IPv6 字面量的粗校验：只允许十六进制、冒号、点（内嵌 IPv4）与 `%zone`。 */
private fun isHexGroups(text: String): Boolean {
    val core = text.substringBefore('%')
    if (core.isEmpty()) return false
    return core.all { c ->
        c == ':' || c == '.' || c in '0'..'9' || c in 'a'..'f'
    }
}
