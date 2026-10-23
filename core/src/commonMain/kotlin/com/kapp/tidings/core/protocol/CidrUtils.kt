// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

/**
 * CIDR 网段解析与主机地址展开（对齐茶话间 F-DISC-2 与 `cidr.ts`）。
 *
 * 限制：
 * - 只支持 IPv4（形如 a.b.c.d/prefix）；
 * - 前缀范围 8..30；
 * - 最多展开 1024 个主机地址（/22 及更小），防误填大段打爆网络。
 */
object CidrUtils {
    const val SCAN_MAX_HOSTS = 1024

    private data class ParsedCidr(
        val prefix: Int,
        val network: Long,
        val hostCount: Int,
    )

    private fun parseCidrBase(input: String): ParsedCidr? {
        val trimmed = input.trim()
        val parts = trimmed.split('/')
        if (parts.size != 2) return null
        val ipPart = parts[0]
        val prefixPart = parts[1]

        val prefix = prefixPart.toIntOrNull() ?: return null
        if (prefix !in 8..30) return null

        val octets = ipPart.split('.')
        if (octets.size != 4) return null
        val o = octets.map { it.toIntOrNull() ?: return null }
        if (o.any { it !in 0..255 }) return null

        val hostBits = 32 - prefix
        val hostCount = if (hostBits >= 31) 0 else (1 shl hostBits) - 2

        val base = ((o[0].toLong() shl 24) or (o[1].toLong() shl 16) or (o[2].toLong() shl 8) or o[3].toLong()) and 0xFFFFFFFFL
        val mask = (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
        val network = base and mask

        return ParsedCidr(prefix, network, hostCount)
    }

    private fun formatIpv4(addr: Long): String {
        val a = (addr ushr 24) and 0xFF
        val b = (addr ushr 16) and 0xFF
        val c = (addr ushr 8) and 0xFF
        val d = addr and 0xFF
        return "$a.$b.$c.$d"
    }

    /** 标准化 CIDR 字符串（例如将 192.168.1.55/24 规范化为 192.168.1.0/24）。非法返回 null。 */
    fun normalizeCidr(input: String): String? {
        val parsed = parseCidrBase(input) ?: return null
        return "${formatIpv4(parsed.network)}/${parsed.prefix}"
    }

    /** 展开网段下所有可探测主机 IPv4 地址。非法或超过上限（1024）返回 null。 */
    fun parseCidrHosts(input: String): List<String>? {
        val parsed = parseCidrBase(input) ?: return null
        if (parsed.hostCount <= 0 || parsed.hostCount > SCAN_MAX_HOSTS) return null
        val hosts = ArrayList<String>(parsed.hostCount)
        for (i in 1..parsed.hostCount) {
            hosts.add(formatIpv4(parsed.network + i))
        }
        return hosts
    }

    /** 对一组 CIDR 网段去重并展开全部主机地址。 */
    fun buildCidrHostPlan(inputs: List<String>): List<String> {
        val seenHosts = LinkedHashSet<String>()
        val seenRanges = HashSet<String>()
        for (input in inputs) {
            val cidr = normalizeCidr(input) ?: continue
            if (!seenRanges.add(cidr)) continue
            val hosts = parseCidrHosts(cidr) ?: continue
            seenHosts.addAll(hosts)
        }
        return seenHosts.toList()
    }
}
