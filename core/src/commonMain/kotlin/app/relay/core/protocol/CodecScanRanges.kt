// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import app.relay.core.protocol.CodecWire.intOf
import app.relay.core.protocol.CodecWire.strOrNull
import app.relay.core.protocol.CodecWire.wireJson

/**
 * 局域网扫描区间（scan-ranges）一帧：校验、payload 构造、解码。
 *
 * 从 `Codec.kt` 按「变化原因」切出。它是三行的小族，独立成文件是为了让
 * `Codec.kt` 门面只剩「信封 + 路由」，新增一族时有一个明确的落点。
 */
object CodecScanRanges {

    /**
     * `scan-ranges` 报文校验（协议 §6.4，对应 TS codec.ts:386-403）。
     */
    fun validateScanRanges(o: JsonObject): Boolean {
        val ranges = o["ranges"] as? JsonArray ?: return false
        if (ranges.isEmpty() || ranges.size > SCAN_RANGES_PER_PACKET) return false
        return ranges.all { rEl ->
            val r = rEl as? JsonObject ?: return@all false
            val cidrStr = strOrNull(r["cidr"], 45) ?: return@all false
            if (CidrUtils.normalizeCidr(cidrStr) == null) return@all false
            val addedAt = intOf(r["addedAt"]) ?: return@all false
            addedAt > 0
        }
    }

    fun scanRangesPayload(ranges: List<ScanRangeSummary>): JsonElement =
        wireJson.encodeToJsonElement(
            ScanRangesPayload.serializer(),
            ScanRangesPayload(ranges = ranges),
        )

    fun decodeScanRanges(payload: JsonElement?): List<ScanRangeSummary>? {
        val o = payload as? JsonObject ?: return null
        return try {
            wireJson.decodeFromJsonElement(ScanRangesPayload.serializer(), o).ranges
        } catch (_: Exception) {
            null
}
}
}
