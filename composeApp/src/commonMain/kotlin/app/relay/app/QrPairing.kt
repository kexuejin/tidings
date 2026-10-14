// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 解析后的二维码类型。
 *
 * 可以是局域网单播节点（host + port），也可以是公网中继端点（url + token + nodeId + nick）。
 */
sealed interface ParsedQr {
    data class LanPeer(val host: String, val port: Int) : ParsedQr
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

    // 1. JSON 格式: {"type":"relay"|"remote", "url":"...", "token":"...", ...}
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        try {
            val json = Json.parseToJsonElement(trimmed).jsonObject
            val type = json["type"]?.jsonPrimitive?.contentOrNull
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

    // 3. 局域网 host:port
    val lan = parseQrPayload(trimmed)
    if (lan != null) {
        return ParsedQr.LanPeer(lan.first, lan.second)
    }

    return null
}

/**
 * 二维码配对载荷的解析（局域网旧接口，保持完全向后兼容）。
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
    val parts = withoutScheme.split(":")
    if (parts.size != 2) return null
    val host = parts[0].trim()
    val port = parts[1].trim().toIntOrNull() ?: return null
    if (host.isEmpty() || port !in 1..65535) return null
    return host to port
}

/**
 * 二维码配对载荷的生成 —— [parseQrPayload] 的逆。
 */
internal fun pairingPayload(host: String, port: Int): String? {
    val trimmed = host.trim()
    if (trimmed.isEmpty() || trimmed.contains(':') || port !in 1..65535) return null
    return "relay://$trimmed:$port"
}

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
