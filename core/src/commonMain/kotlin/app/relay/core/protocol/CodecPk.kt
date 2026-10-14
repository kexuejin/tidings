// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.relay.core.protocol.CodecMsg.validateGroupRef
import app.relay.core.protocol.CodecWire.hasOnlyKeys
import app.relay.core.protocol.CodecWire.intOf
import app.relay.core.protocol.CodecWire.strOrNull

/**
 * `pk` 帧（游戏值）：字段白名单、校验、构造与解码。
 *
 * 从 `Codec.kt` 按「变化原因」切出。`pkPayload` 与 `decodePkMsg` 中间夹着 `PkMsg`
 * 这个 data class —— 它是这个帧唯一的解码结果类型，所以跟着这一族走。
 */
object CodecPk {
    private val PK_KEYS = setOf("kind", "game", "value", "groupId", "groupRev", "resend")

    internal fun validatePk(o: JsonObject): Boolean {
        if (!hasOnlyKeys(o, PK_KEYS)) return false
        val game = (o["game"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        when (game) {
            PkGames.RPS -> {
                val value = (o["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
                if (value !in RpsValues.ALL) return false
            }
            PkGames.DICE -> {
                val intVal = intOf(o["value"])
                    ?: (o["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.toLongOrNull()
                    ?: return false
                if (intVal !in 1L..6L) return false
            }
            else -> return false
        }
        return validateGroupRef(o)
    }

    fun pkPayload(
        game: String,
        value: String,
        groupId: String? = null,
        groupRev: Int? = null,
    ): JsonElement {
        require((groupId == null) == (groupRev == null)) {
            "pk 的 groupId 与 groupRev 必须成对出现"
        }
        val builder = mutableMapOf<String, JsonElement>(
            "kind" to JsonPrimitive(MsgKinds.PK),
            "game" to JsonPrimitive(game),
        )
        if (game == PkGames.DICE) {
            val num = value.toLongOrNull() ?: 1L
            builder["value"] = JsonPrimitive(num)
        } else {
            builder["value"] = JsonPrimitive(value)
        }
        if (groupId != null && groupRev != null) {
            builder["groupId"] = JsonPrimitive(groupId)
            builder["groupRev"] = JsonPrimitive(groupRev)
        }
        return JsonObject(builder)
    }

    data class PkMsg(
        val game: String,
        val value: String,
        val groupId: String? = null,
        val groupRev: Int? = null,
    )

    fun decodePkMsg(payload: JsonElement?): PkMsg? {
        val o = payload as? JsonObject ?: return null
        if (!validatePk(o)) return null
        val game = (o["game"] as? JsonPrimitive)?.content ?: return null
        val value = when (val v = o["value"]) {
            is JsonPrimitive -> v.content
            else -> return null
        }
        val groupId = strOrNull(o["groupId"], Limits.ID)
        val groupRev = intOf(o["groupRev"])?.toInt()
        return PkMsg(game = game, value = value, groupId = groupId, groupRev = groupRev)
}
}
