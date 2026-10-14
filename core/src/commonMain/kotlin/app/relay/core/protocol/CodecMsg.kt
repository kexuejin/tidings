// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import app.relay.core.protocol.CodecPk.validatePk
import app.relay.core.protocol.CodecWire.hasOnlyKeys
import app.relay.core.protocol.CodecWire.intOf
import app.relay.core.protocol.CodecWire.isStr
import app.relay.core.protocol.CodecWire.isStrAllowEmpty
import app.relay.core.protocol.CodecWire.utf8ByteLength
import app.relay.core.protocol.CodecWire.wireJson

/**
 * 文本消息：单聊 `msg`、群聊 `groupText`、撤回、戳一碰。
 *
 * 从 `Codec.kt` 按「变化原因」切出：这一族的演化动力是「文本承载了什么」—— 加
 * 引用、加表情、加话题，改的都是这里；`validateMsg` 里的 `GROUP_TEXT_KEYS` 与
 * `hasOnlyKeys` 随之变。`validateGroupRef`（群引用的共用形态）也在这里，因为它只被
 * `validateMsg` 与 `validatePk` 两处使用。
 */
object CodecMsg {

    /** group-text 的严格字段白名单 —— 多一个字段整帧丢弃（对应 `hasOnlyKeys`）。 */
    private val GROUP_TEXT_KEYS = setOf(
        "kind", "text", "groupId", "groupRev", "mentions", "resend", "replyTo",
    )

    internal fun validateMsg(o: JsonObject, textLimit: Int): Boolean {
        val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (kind !in MsgKinds.ALL) return false

        if (o.containsKey("resend")) {
            val rp = o["resend"] as? JsonPrimitive ?: return false
            if (rp.isString || rp.booleanOrNull == null) return false
        }

        when (kind) {
            // nudge 是无正文消息：线上只有 {kind:"nudge"}，一旦带 resend 就不是它。
            // 必须在这里 return —— 往下掉会落进文本分支，被"text 不能为空"整个拒掉。
            MsgKinds.NUDGE -> return !o.containsKey("resend")

            MsgKinds.PK -> return validatePk(o)

            MsgKinds.RECALL -> {
                if (!isStr(o["targetId"], Limits.ID)) return false
                return validateGroupRef(o)
            }
        }

        // 文本分支（text / group-text）。
        val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text == null || text.isEmpty()) return false
        if (utf8ByteLength(text) > textLimit) return false

        if (kind != MsgKinds.GROUP_TEXT) return true

        if (!isStr(o["groupId"], Limits.ID)) return false
        val groupRev = intOf(o["groupRev"]) ?: return false
        if (groupRev < 0) return false

        if (o.containsKey("mentions")) {
            val mentions = o["mentions"] as? JsonArray ?: return false
            if (mentions.size > GROUP_MAX_MEMBERS) return false
            for (m in mentions) {
                if (!isStr(m, Limits.FROM)) return false
            }
        }

        if (o.containsKey("replyTo")) {
            if (!isStrAllowEmpty(o["replyTo"], Limits.ID)) return false
        }

        // 严格字段白名单：多一个字段整帧丢弃（neg-group-text-extra-field 就是这条）。
        return hasOnlyKeys(o, GROUP_TEXT_KEYS)
    }

    /** recall / pk 的群引用一致性：有 groupId 就必须有非负 groupRev，反之亦然。 */
    internal fun validateGroupRef(o: JsonObject): Boolean {
        if (o.containsKey("groupId")) {
            if (!isStr(o["groupId"], Limits.ID)) return false
            val rev = intOf(o["groupRev"]) ?: return false
            return rev >= 0
        }
        return !o.containsKey("groupRev")
    }

    fun textPayload(text: String, resend: Boolean? = null): JsonElement =
        wireJson.encodeToJsonElement(
            TextMsgPayload.serializer(),
            TextMsgPayload(kind = MsgKinds.TEXT, text = text, resend = resend),
        )

    fun groupTextPayload(
        text: String,
        groupId: String,
        groupRev: Int,
        mentions: List<String>? = null,
        resend: Boolean? = null,
        replyTo: String? = null,
    ): JsonElement = wireJson.encodeToJsonElement(
        GroupTextMsgPayload.serializer(),
        GroupTextMsgPayload(
            kind = MsgKinds.GROUP_TEXT,
            text = text,
            groupId = groupId,
            groupRev = groupRev,
            mentions = mentions,
            resend = resend,
            replyTo = replyTo,
        ),
    )

    /**
     * 撤回指令：`{kind:"recall", targetId}`，群聊再加 `groupId`/`groupRev`。
     *
     * `groupId` 与 `groupRev` 必须**成对**出现：`validateGroupRef` 只认「都有」或「都没有」，
     * 只给一个的载荷连我们自己的入站校验都过不去（对方更过不去）。
     */
    fun recallPayload(targetId: String, groupId: String? = null, groupRev: Int? = null): JsonElement {
        require((groupId == null) == (groupRev == null)) {
            "recall 的 groupId 与 groupRev 必须成对出现"
        }
        return wireJson.encodeToJsonElement(
            RecallMsgPayload.serializer(),
            RecallMsgPayload(kind = MsgKinds.RECALL, targetId = targetId, groupId = groupId, groupRev = groupRev),
        )
    }

    /** 私聊窗口震动：线上只有 `{kind:"nudge"}`。 */
    fun nudgePayload(): JsonElement =
        wireJson.encodeToJsonElement(NudgeMsgPayload.serializer(), NudgeMsgPayload(kind = MsgKinds.NUDGE))
}
