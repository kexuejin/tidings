// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.relay.core.protocol.CodecWire.intOf
import app.relay.core.protocol.CodecWire.isStr
import app.relay.core.protocol.CodecWire.wireJson

/**
 * 共享目录（share）一族：白名单校验、消息解码、四种 payload 构造器。
 *
 * 从 `Codec.kt` 按「变化原因」切出。协议文档里的 share 段与实现有两处对不上，
 * 具体是哪两处、我们为什么这样选，写在 `PROTOCOL-COVERAGE.md` 里，本文件不重复。
 */
object CodecShare {

    fun validateShare(o: JsonObject): Boolean {
        val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (!isStr(o["reqId"], Limits.ID)) return false
        return when (op) {
            ShareOps.LIST -> {
                val offset = intOf(o["offset"]) ?: return false
                if (offset < 0) return false
                if (o.containsKey("snapshotId") && !isStr(o["snapshotId"], Limits.ID)) return false
                true
            }
            ShareOps.LIST_OK -> {
                val offset = intOf(o["offset"]) ?: return false
                val total = intOf(o["total"]) ?: return false
                if (offset < 0 || total < 0) return false
                if (!isStr(o["snapshotId"], Limits.ID)) return false
                val perm = (o["perm"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
                if (perm != "read" && perm != "write") return false
                true
            }
            ShareOps.DENY -> {
                val reason = (o["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
                reason.isNotEmpty()
            }
            ShareOps.GET -> {
                val paths = o["paths"] as? JsonArray ?: return false
                paths.isNotEmpty()
            }
            else -> false
        }
    }

    fun decodeShareMessage(payload: JsonElement?): ShareMessage? {
        val o = payload as? JsonObject ?: return null
        val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return try {
            when (op) {
                ShareOps.LIST -> ShareMessage.ListReq(wireJson.decodeFromJsonElement(ShareListPayload.serializer(), o))
                ShareOps.LIST_OK -> ShareMessage.ListOk(wireJson.decodeFromJsonElement(ShareListOkPayload.serializer(), o))
                ShareOps.DENY -> ShareMessage.Deny(wireJson.decodeFromJsonElement(ShareDenyPayload.serializer(), o))
                ShareOps.GET -> ShareMessage.GetReq(wireJson.decodeFromJsonElement(ShareGetPayload.serializer(), o))
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun shareListPayload(reqId: String, path: String = "", offset: Int = 0, snapshotId: String? = null): JsonElement =
        wireJson.encodeToJsonElement(
            ShareListPayload.serializer(),
            ShareListPayload(op = ShareOps.LIST, reqId = reqId, path = path, offset = offset, snapshotId = snapshotId),
        )

    fun shareListOkPayload(
        reqId: String,
        path: String,
        perm: String,
        snapshotId: String,
        offset: Int,
        total: Int,
        truncated: Boolean,
        entries: List<ShareEntry>,
    ): JsonElement = wireJson.encodeToJsonElement(
        ShareListOkPayload.serializer(),
        ShareListOkPayload(
            op = ShareOps.LIST_OK,
            reqId = reqId,
            path = path,
            perm = perm,
            snapshotId = snapshotId,
            offset = offset,
            total = total,
            truncated = truncated,
            entries = entries,
        ),
    )

    fun shareDenyPayload(reqId: String, reason: String): JsonElement =
        wireJson.encodeToJsonElement(
            ShareDenyPayload.serializer(),
            ShareDenyPayload(op = ShareOps.DENY, reqId = reqId, reason = reason),
        )

    fun shareGetPayload(reqId: String, paths: List<String>): JsonElement =
        wireJson.encodeToJsonElement(
            ShareGetPayload.serializer(),
            ShareGetPayload(op = ShareOps.GET, reqId = reqId, paths = paths),
        )
}
