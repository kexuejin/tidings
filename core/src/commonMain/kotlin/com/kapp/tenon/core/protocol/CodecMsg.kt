// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import com.kapp.tenon.core.protocol.CodecPk.validatePk
import com.kapp.tenon.core.protocol.CodecWire.hasOnlyKeys
import com.kapp.tenon.core.protocol.CodecWire.intOf
import com.kapp.tenon.core.protocol.CodecWire.isStr
import com.kapp.tenon.core.protocol.CodecWire.isStrAllowEmpty
import com.kapp.tenon.core.protocol.CodecWire.utf8ByteLength
import com.kapp.tenon.core.protocol.CodecWire.wireJson

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
     *
     * `reasoning` 已按规格 §4.1 从这里**退场**（CoT 禁止进群）—— 谁把它加回来，
     * `CodecTest.reasoningIsNoLongerAccepted` 会红。终态 14 键。
     */
    private val GROUP_TEXT_KEYS = setOf(
        "kind", "text", "groupId", "groupRev", "mentions", "resend", "replyTo",
        "senderId",
        // 会话归属（agent 事件的群转发用）：让接收端能画出「任务分段线」，
        // 而不是靠猜消息 id 前缀。**没加进这里的可选键 = 对端整帧拒收**。
        "agentSessionId", "agentCursor",
        // 会话标题：分段线要显示「任务：xxx」，而那个标题是宿主本机的数据，不跟着消息走
        // 就永远只能是「新会话」。
        "agentSessionTitle",
        // 单帧原子意图（人类 → 插件）：随正文一次发出，宿主原子化「开会话 + 派单」。
        "agentIntent",
        // 执行状态（插件 → 全群）与声明式卡片：四态枚举与对象校验见 [validateMsg]。
        "agentState", "card",
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

        // 会话归属（可选）：让接收端画出「任务分段线」。
        // 两者**必须成对**（与 recall 的 groupId/groupRev 同一条纪律）：
        // 只给 sessionId 而没有 cursor 的帧，接收端要么猜要么忽略，两种都不如直接拒。
        val hasSession = o.containsKey("agentSessionId")
        val hasCursor = o.containsKey("agentCursor")
        if (hasSession != hasCursor) return false
        if (hasSession) {
            if (!isStr(o["agentSessionId"], Limits.ID)) return false
            val cursor = intOf(o["agentCursor"]) ?: return false
            if (cursor < 0) return false
        }
        // 标题可选，但**不能在没有 agentSessionId 的帧里出现** —— 那句话没有主语，
        // 接收端只能忽略它（静默丢弃一段事实，比直接拒更糟）。
        if (o.containsKey("agentSessionTitle")) {
            if (!hasSession) return false
            if (!isStr(o["agentSessionTitle"], Limits.GROUP_NAME)) return false
        }

        // 严格字段白名单：多一个字段整帧丢弃（neg-group-text-extra-field 就是这条）。
        if (!hasOnlyKeys(o, GROUP_TEXT_KEYS)) return false

        // ---- 值类型校验（白名单只管键的存在，不管值的形状）----

        // 单帧原子意图（可选）：必须是一个 JSON 对象。字符串形态的「意图」是坏载荷 ——
        // 意图的内部字段由插件清单定义，协议层不逐字段校验，但「不是对象」连形状都不是。
        if (o.containsKey("agentIntent")) {
            if (o["agentIntent"] !is JsonObject) return false
        }

        // 声明式卡片（可选）：必须是对象，且受 [Limits.CARD_BYTES] 字节预算约束。
        // 发送侧超限**拒绝不截断**（`NodeOutbound.groupTextVariants`），接收侧按同一
        // 数值整帧拒收 —— 队列重放走 `decodeTcp`，同一份校验再跑一遍，三处同源。
        if (o.containsKey("card")) {
            val card = o["card"] as? JsonObject ?: return false
            if (utf8ByteLength(card.toString()) > Limits.CARD_BYTES) return false
        }

        // 执行状态（可选）：四态枚举。状态机是协议事实，不是自由文本 —— 界面的状态
        // 徽标只画这四态，多出来的值等于 UI 上的一个空洞，不如在协议层就挡住。
        if (o.containsKey("agentState")) {
            val state = (o["agentState"] as? JsonPrimitive)?.takeIf { it.isString } ?: return false
            if (state.content !in GROUP_AGENT_STATES) return false
        }
        return true
    }

    /** `agentState` 的四态（规格 §4.1）：界面的执行状态徽标只画这四个。 */
    private val GROUP_AGENT_STATES = setOf("accept", "blocked", "completed", "failed")

    /** recall / pk 的群引用一致性：有 groupId 就必须有非负 groupRev，反之亦然。 */
    internal fun validateGroupRef(o: JsonObject): Boolean {
        if (o.containsKey("groupId")) {
            if (!isStr(o["groupId"], Limits.ID)) return false
            val rev = intOf(o["groupRev"]) ?: return false
            return rev >= 0
        }
        return !o.containsKey("groupRev")
    }

    /**
     * 单聊文本载荷。
     *
     * @param senderId 真正的说话者（`agent:<宿主 nodeId>:<agentId>`）。**单聊同样需要它**：
     *   1:1 直派的反馈（「Ticket accepted…」/「改动已就绪…」）是宿主上的 agent 说的，
     *   不带这一格，接收端只看到信封 `from`（那台机器）⇒ 界面上就是「对端设备发的」，
     *   而宿主自己按 agent 落库 —— 同一句话两端署名不同。真机现场：手机库里这两条
     *   `sender_id = NULL`，与它们同一轮的工单卡（走 [textPayloadWithCard]）却有署名。
     *
     * null 时**逐字节与旧载荷相同**（不写 `senderId` 键）：老端不认这个键，
     * 而 golden 断言（`CodecTest`）钉的正是「不传就没有」。
     */
    fun textPayload(text: String, resend: Boolean? = null, senderId: String? = null): JsonElement {
        val base = wireJson.encodeToJsonElement(
            TextMsgPayload.serializer(),
            TextMsgPayload(kind = MsgKinds.TEXT, text = text, resend = resend),
        )
        if (senderId == null) return base
        val obj = base as? JsonObject ?: return base
        return JsonObject(obj + ("senderId" to JsonPrimitive(senderId)))
    }

    /**
     * 单聊文本 + 一张声明式卡片（可选）—— **1:1 的卡片落点**。
     *
     * ## 为什么单聊也要能带 card
     *
     * 群聊有看板卡（`group-text` 的 `card` 键），但 1:1 直派（`dispatchDirectInvoke`，
     * `groupId == null`）一直没有承载卡片的面：宿主只把卡落进**自己的**库，手机上什么
     * 都看不到 —— 用户「发了需求 → 任务真跑 20 秒 → 界面一字不变」（真机 A22 现场，
     * 证据见 `design/runtime-evidence/` 的 1:1 派单实测）。
     *
     * ## 为什么不需要动协议白名单
     *
     * [validateMsg] 在 `if (kind != MsgKinds.GROUP_TEXT) return true` 处就返回 —— 单聊帧
     * **不做字段白名单**，多带一个 `card` 不会被对端整帧拒收；接收侧
     * `RelayControllerMessagesComponent` 的 `payload?.get("card")` 也不分群/单聊。
     * 所以这里只补发送出口，收端天然认。**改这条早返回之前，先确认 1:1 卡片的收端渲染
     * 另有出口**，否则老端会从「少看到卡片」退化成「整条消息都收不到」。
     *
     * ## 为什么不复用 [TextMsgPayload]
     *
     * 那个 @Serializable 只有 kind/text/resend 三个字段，而 `encodeDefaults = false`
     * 会把等于默认值的字段整键省略 —— 往里加 `card: JsonObject? = null` 后，非空才有值，
     * 语义上没错但极易被下一个人当成「加了就生效」。这里直接拼 [JsonObject]：键的顺序
     * 与 [textPayload] 一致（kind/text 在前），`card` 追加在尾部，字节对拍只看新增部分。
     *
     * @param card 声明式卡片对象；调用方负责不超过 [Limits.CARD_BYTES]
     * @param senderId 真正的说话者（`agent:<宿主 nodeId>:<agentId>`）。**卡片动作靠它**：
     *   接收端用 `AgentSenderId.parse` 从这一格取出宿主，才知道把 `op=action` 发回哪台机器。
     *   不带 ⇒ 对方的按钮点了无处可发（真机 A22 现场）
     */
    fun textPayloadWithCard(text: String, card: JsonObject, senderId: String? = null): JsonObject = JsonObject(
        buildMap {
            put("kind", JsonPrimitive(MsgKinds.TEXT))
            put("text", JsonPrimitive(text))
            if (senderId != null) put("senderId", JsonPrimitive(senderId))
            put("card", card)
        },
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
         * 这条消息属于哪个 agent 会话（可选）。带上它，接收端才能画出「任务分段线」。
         * 旧接收端不认识 ⇒ 整帧丢弃，两端需同版本。
         */
        agentSessionId: String? = null,
        /** 会话内游标（可选，与 [agentSessionId] 配对）。 */
        agentCursor: Long? = null,
        /** 会话标题（可选）。分段线显示「任务：xxx」用它；缺省时界面显示「新会话」。 */
        agentSessionTitle: String? = null,
        /** 人类 → 插件的单帧原子意图（可选）。见 `GroupTextMsgPayload.agentIntent`。 */
        agentIntent: JsonObject? = null,
        /** 插件 → 全群的执行状态（可选，四态）。见 `GroupTextMsgPayload.agentState`。 */
        agentState: String? = null,
        /** 声明式卡片负载（可选）。见 `GroupTextMsgPayload.card` 的预算与拒绝纪律。 */
        card: JsonObject? = null,
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
            agentSessionId = agentSessionId,
            agentCursor = agentCursor,
            agentSessionTitle = agentSessionTitle,
            agentIntent = agentIntent,
            agentState = agentState,
            card = card,
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
