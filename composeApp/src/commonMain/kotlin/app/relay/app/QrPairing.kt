// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.IpAddressUtils
import app.relay.core.protocol.Ipv6Scope
import app.relay.core.protocol.Protocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 候选对端地址（用于多网络自适应直连）。
 */
data class PeerCandidate(
    val type: String, // "lan_v4", "public_v6", "domain", "other"
    val host: String,
    val port: Int,
)

/**
 * 解析后的二维码类型。
 *
 * 可以是直接单播节点（host + port，含 IPv4 与 IPv6，可选携带 TLS pin 与多候选池），
 * 也可以是公网中继端点（url + token + nodeId + nick）。
 */
sealed interface ParsedQr {
    data class LanPeer(
        val host: String,
        val port: Int,
        val tlsPin: String? = null,
        val candidates: List<PeerCandidate> = emptyList(),
    ) : ParsedQr

    data class RemoteRelay(
        val url: String,
        val token: String = "",
        val nodeId: String? = null,
        val nick: String? = null,
        val lan: String? = null,
    ) : ParsedQr
}

/**
 * 统一二维码解析入口。
 */
internal fun parseQr(content: String): ParsedQr? {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return null

    // 1. JSON 格式: {"type":"relay"|"remote"|"direct", "url":"...", "token":"...", "addrs":[...], ...}
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        try {
            val json = Json.parseToJsonElement(trimmed).jsonObject
            val type = json["type"]?.jsonPrimitive?.contentOrNull

            // 1.1 多候选直连格式: {"type":"direct", "pin":"...", "addrs":[...]}
            val addrsArray = json["addrs"]?.jsonArray
            if (addrsArray != null && addrsArray.isNotEmpty()) {
                val pin = json["pin"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                    ?: json["tlsPin"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                val candidates = mutableListOf<PeerCandidate>()
                for (item in addrsArray) {
                    val obj = item.jsonObject
                    val cType = obj["type"]?.jsonPrimitive?.contentOrNull ?: "other"
                    val host = obj["ip"]?.jsonPrimitive?.contentOrNull ?: obj["host"]?.jsonPrimitive?.contentOrNull
                    val port = obj["port"]?.jsonPrimitive?.intOrNull ?: Protocol.DEFAULT_UDP_PORT
                    if (!host.isNullOrBlank() && port in 1..65535) {
                        candidates.add(PeerCandidate(cType, IpAddressUtils.unbracket(host.trim()), port))
                    }
                }
                if (candidates.isNotEmpty()) {
                    // 首选优先级：优先局域网 IPv4，其次公网 IPv6，再退回第一个
                    val best = candidates.firstOrNull { it.type == "lan_v4" }
                        ?: candidates.firstOrNull { it.type == "public_v6" }
                        ?: candidates.first()
                    return ParsedQr.LanPeer(
                        host = best.host,
                        port = best.port,
                        tlsPin = pin,
                        candidates = candidates,
                    )
                }
            }

            // 1.2 中继远程格式
            val url = json["url"]?.jsonPrimitive?.contentOrNull ?: json["relay"]?.jsonPrimitive?.contentOrNull
            if (url != null && url.isNotBlank() && (type == null || type == "relay" || type == "remote")) {
                val token = json["token"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val nodeId = (json["nodeId"] ?: json["id"])?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                val nick = json["nick"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                val lan = json["lan"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
                return ParsedQr.RemoteRelay(url.trim(), token, nodeId, nick, lan)
            }
        } catch (_: Throwable) {
            // Not JSON
        }
    }

    // 2. URI 格式: relay://relay?... 或 relay://remote?... 或 relay-pair://...
    if (trimmed.startsWith("relay://relay?") || trimmed.startsWith("relay://remote?") || trimmed.startsWith("relay-pair://")) {
        val query = trimmed.substringAfter("?")
        val params = query.split("&").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq > 0) part.substring(0, eq).trim() to part.substring(eq + 1).trim() else null
        }.toMap()
        val url = params["url"] ?: params["relay"]
        if (!url.isNullOrBlank()) {
            val token = params["token"].orEmpty()
            val nodeId = params["nodeId"] ?: params["id"]
            val nick = params["nick"]
            val lan = params["lan"]
            return ParsedQr.RemoteRelay(
                url = url,
                token = token,
                nodeId = nodeId?.ifEmpty { null },
                nick = nick?.ifEmpty { null },
                lan = lan?.ifEmpty { null },
            )
        }
    }

    // 3. 直接节点 host:port 或 relay://host:port（含带 ?pin=... 查询参数）
    val lan = parseQrPayload(trimmed)
    if (lan != null) {
        val pin = if (trimmed.contains("?")) {
            val query = trimmed.substringAfter("?")
            query.split("&").firstNotNullOfOrNull { part ->
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val k = part.substring(0, eq).trim()
                    val v = part.substring(eq + 1).trim()
                    if (k == "pin" || k == "tlsPin") v else null
                } else null
            }
        } else null

        return ParsedQr.LanPeer(lan.first, lan.second, tlsPin = pin)
    }

    return null
}

/**
 * 二维码配对载荷的解析（支持 IPv4 与带方括号的 IPv6 地址，保持完全向后兼容）。
 */
internal fun parseQrPayload(content: String): Pair<String, Int>? {
    val trimmed = content.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("relay://relay?") || trimmed.startsWith("relay://remote?") || trimmed.startsWith("relay-pair://")) {
        return null
    }
    val withoutScheme = if (trimmed.startsWith("relay://")) {
        trimmed.removePrefix("relay://")
    } else {
        trimmed
    }

    // 剔除查询参数
    val base = withoutScheme.substringBefore('?').trim()

    // 1. 标准方括号 IPv6: [240e:...]:17878
    if (base.startsWith("[")) {
        val closeBracket = base.indexOf(']')
        if (closeBracket <= 1) return null
        val host = base.substring(1, closeBracket).trim()
        val rest = base.substring(closeBracket + 1).trim()
        if (!rest.startsWith(":")) return null
        val port = rest.removePrefix(":").trim().toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        // 校验 IPv6 有效性，严禁 Link-Local (fe80::) 伪装
        if (!IpAddressUtils.isIpv6(host)) return null
        if (IpAddressUtils.getIpv6Scope(host) == Ipv6Scope.LINK_LOCAL) return null

        return host to port
    }

    // 2. IPv4 或 主机名: host:port
    val parts = base.split(":")
    if (parts.size != 2) return null
    val host = parts[0].trim()
    val port = parts[1].trim().toIntOrNull() ?: return null
    if (host.isEmpty() || port !in 1..65535) return null
    if (host.contains(':')) return null
    return host to port
}

/**
 * 二维码配对载荷的生成 —— [parseQrPayload] 的逆。
 * 支持 IPv4 与公网/内网 IPv6。
 */
internal fun pairingPayload(host: String, port: Int): String? {
    val trimmed = host.trim()
    if (trimmed.isEmpty() || port !in 1..65535) return null

    val cleanHost = IpAddressUtils.unbracket(trimmed)
    if (cleanHost.contains(':')) {
        // IPv6 必须有效且非链路本地
        if (!IpAddressUtils.isIpv6(cleanHost)) return null
        if (IpAddressUtils.getIpv6Scope(cleanHost) == Ipv6Scope.LINK_LOCAL) return null
        return "relay://[$cleanHost]:$port"
    }

    return "relay://$cleanHost:$port"
}

/**
 * 生成多候选智能配对载荷（JSON）。
 */
internal fun multiCandidatePairingPayload(
    nodeId: String,
    nick: String? = null,
    tlsPin: String? = null,
    candidates: List<PeerCandidate>,
): String = buildJsonObject {
    put("type", "direct")
    put("nodeId", nodeId)
    if (!nick.isNullOrBlank()) put("nick", nick)
    if (!tlsPin.isNullOrBlank()) put("pin", tlsPin)
    put("addrs", buildJsonArray {
        for (c in candidates) {
            add(buildJsonObject {
                put("type", c.type)
                put("ip", c.host)
                put("port", c.port)
            })
        }
    })
}.toString()

/**
 * 生成公网中继配对载荷（JSON）。
 */
internal fun remotePairingPayload(
    url: String,
    token: String = "",
    nodeId: String? = null,
    nick: String? = null,
): String = buildJsonObject {
    put("type", "relay")
    put("url", url.trim())
    if (token.isNotBlank()) put("token", token.trim())
    nodeId?.takeIf { it.isNotBlank() }?.let { put("nodeId", it.trim()) }
    nick?.takeIf { it.isNotBlank() }?.let { put("nick", it.trim()) }
}.toString()
