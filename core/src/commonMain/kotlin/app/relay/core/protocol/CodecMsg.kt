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

    /**
     * group-text 的严格字段白名单 —— 多一个字段整帧丢弃（对应 `hasOnlyKeys`）。
     *
     * **加可选键时必须同时加进这里**。只加 data class 属性而不加白名单，本端编码得出、
     * 对端 `hasOnlyKeys` 直接判成 extra-field 整帧拒收 —— 表现为「发送侧一切正常、
     * 接收侧什么都没有」，比编译错误难查得多（`senderId` 曾经就是这样漏掉的）。
     */
    private val GROUP_TEXT_KEYS = setOf(
        "kind", "text", "groupId", "groupRev", "mentions", "resend", "replyTo",
        "senderId", "reasoning",
    )

    /**
     * 正文实际上限：**群文本在 TCP 通道上放宽到 [Limits.GROUP_TEXT_TCP_LIMIT]**。
     *
     * 判据是 `textLimit > Protocol.TEXT_UDP_LIMIT`（即「这一帧是从 TCP 进来的」），
     * 与 `CodecAgent` 给 `agent-event` 的 body 放宽用的是同一条判据 —— 两族文本在
     * 「UDP 800 / TCP 放宽」这件事上不该有两套规矩。UDP 侧仍按调用方给的 800 卡死。
     *
     * 只放宽 `group-text`：单聊文本是用户手打的，4096 足够，没必要跟着放大攻击面。
     */
    internal fun textLimitFor(kind: String, textLimit: Int): Int =
        if (kind == MsgKinds.GROUP_TEXT && textLimit > Protocol.TEXT_UDP_LIMIT) {
            maxOf(textLimit, Limits.GROUP_TEXT_TCP_LIMIT)
        } else {
            textLimit
        }

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
        if (utf8ByteLength(text) > textLimitFor(kind, textLimit)) return false

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

        // 真正的说话者（可选）：真人 = nodeId，agent = `agent:<kind>`。
        if (o.containsKey("senderId")) {
            if (!isStr(o["senderId"], Limits.ID)) return false
        }

        // 思考过程（可选）：按**字节**而非码元校验，与发送侧的截断口径一致。
        if (o.containsKey("reasoning")) {
            val reasoning = (o["reasoning"] as? JsonPrimitive)?.takeIf { it.isString } ?: return false
            if (reasoning.content.isEmpty()) return false
            if (utf8ByteLength(reasoning.content) > Limits.GROUP_REASONING_BYTES) return false
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
        /**
         * 真正的说话者（可选）。非空且与信封 `from` 不同时，接收方用它做发送者归属。
         * agent 转发的消息用 `agent:<kind>`，与桌面端本地落库同形态。
         */
        senderId: String? = null,
        /**
         * 思考过程（可选）。调用方应保证已按 [Limits.GROUP_REASONING_BYTES] 截断 ——
         * 这里不截，超限的帧接收侧会整帧拒收。
         */
        reasoning: String? = null,
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
            senderId = senderId,
            reasoning = reasoning,
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
