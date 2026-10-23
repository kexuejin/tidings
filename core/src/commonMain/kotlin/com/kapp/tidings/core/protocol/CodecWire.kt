// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentAck
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentApproval
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentArtifact
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentCancel
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentEvent
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentQueue
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentGrant
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentGrantList
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentSessionPage
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentGrantRequest
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentRun
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentSession
import com.kapp.tidings.core.protocol.CodecAgent.validateAgentWorkspace
import com.kapp.tidings.core.protocol.CodecGroup.validateGroup
import com.kapp.tidings.core.protocol.CodecMsg.validateMsg
import com.kapp.tidings.core.protocol.CodecProfile.validatePresence
import com.kapp.tidings.core.protocol.CodecProfile.validateProfilePayload
import com.kapp.tidings.core.protocol.CodecScanRanges.validateScanRanges
import com.kapp.tidings.core.protocol.CodecShare.validateShare

/**
 * 线格式原语：JSON 配置、字段 guard、信封编解码。
 *
 * `wireJson` 是全仓唯一一份 `Json{}` 配置 —— 信封编解码与所有 `*Payload` 构造器
 * 共用它。**不要在族文件里再写第二份 `Json{}`**：两份配置的差异不产生任何编译
 * 错误，症状是「一部分消息解不出」，且极难定位。
 *
 * 从 `Codec.kt` 按「变化原因」切出：这一族的演化动力是线路本身的演进（长度上限、
 * 版本号、拒绝理由文案），与任何具体消息族的业务语义无关。
 */
object CodecWire {

    /**
     * 线格式 Json 配置。
     *
     * - `encodeDefaults = false`：等于默认值的字段**不输出**。这正是 `Envelope`
     *   与 `Profile` 里「必须出现在线上的字段一律不给默认值」的原因。
     * - `explicitNulls = false`：可选字段为 null 时整个键省略。
     */
    val wireJson: Json = Json {
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = false
    }

    // ---------------------------------------------------------------- 基础 guard

    /** 非空字符串且长度（UTF-16 码元）不超过 [max]。 */
    fun strOrNull(x: JsonElement?, max: Int): String? {
        val p = x as? JsonPrimitive ?: return null
        if (!p.isString) return null
        val s = p.content
        if (s.isEmpty() || s.length > max) return null
        return s
    }

    /** 允许空串的字符串且长度不超过 [max]（company / dept / team / ver）。 */
    fun strAllowEmptyOrNull(x: JsonElement?, max: Int): String? {
        val p = x as? JsonPrimitive ?: return null
        if (!p.isString) return null
        val s = p.content
        if (s.length > max) return null
        return s
    }

    fun isStr(x: JsonElement?, max: Int): Boolean = strOrNull(x, max) != null

    fun isStrAllowEmpty(x: JsonElement?, max: Int): Boolean = strAllowEmptyOrNull(x, max) != null

    /** 整数（JSON number 且为整数值）。[JsonNull] 与字符串一律不算。 */
    fun intOf(x: JsonElement?): Long? {
        val p = x as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.longOrNull
    }

    fun isInt(x: JsonElement?): Boolean = intOf(x) != null

    /**
     * 布尔值。[JsonNull] 与字符串一律不算 —— 与 [intOf] 同纪律：对端把 `true` 写成
     * `"true"` 是要在线上拦下来的坏载荷，不是可以顺手 coerce 的输入。
     */
    fun boolOf(x: JsonElement?): Boolean? {
        val p = x as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.booleanOrNull
    }

    /** 文本上限按 **UTF-8 字节**算，不是字符数（中文一个字 3 字节）。 */
    fun utf8ByteLength(text: String): Int = text.encodeToByteArray().size

    /** 严格字段白名单：`o` 里出现任何一个不在 [allowed] 里的键就整帧丢弃。 */
    internal fun hasOnlyKeys(o: JsonObject, allowed: Set<String>): Boolean {
        for (key in o.keys) {
            if (key !in allowed) return false
        }
        return true
    }

    /**
     * PPE-1 增补 D2（`docs/PROTOCOL-AGENT-WORKSPACE.md` §2.3）：**必需子集 + 忽略未知键**。
     *
     * 与 [hasOnlyKeys] 的分野：多一个未知键不再整帧丢弃，而是忽略并计数；但未知键
     * 个数与未知键 UTF-8 总长（键名 + 序列化值）受预算约束，超限仍丢帧 —— 宽容是给
     * 协议扩展用的，不是给放大攻击开的口子。
     *
     * 今天只对 `agent-session` 启用（D1 加键的那一帧）；其余 `agent-*` 与 TS parity 族
     * 保持严格，等各自真有加键需求再逐帧切换。`required` 里的键必须出现（存在性由
     * 调用方逐字段校验，这里只查在不在）。**测试侧保持严格**：黄金向量仍断言出站
     * 键序与字节，「发送端多写了键」在测试里照样判红 —— 宽容只作用于线上未知对端。
     */
    internal fun tolerantKeys(
        o: JsonObject,
        required: Set<String>,
        known: Set<String>,
        maxUnknownKeys: Int = Limits.UNKNOWN_KEYS_MAX,
        maxUnknownBytes: Int = Limits.UNKNOWN_KEY_BYTES_MAX,
    ): Boolean {
        for (key in required) if (!o.containsKey(key)) return false
        var unknownCount = 0
        var unknownBytes = 0
        for ((key, value) in o) {
            if (key in known) continue
            unknownCount += 1
            if (unknownCount > maxUnknownKeys) return false
            unknownBytes += key.encodeToByteArray().size + value.toString().encodeToByteArray().size
            if (unknownBytes > maxUnknownBytes) return false
        }
        return true
    }

    /** 字符串数组且每项都非空、不超过 [max]；长度不超过 [maxItems]。 */
    internal fun isStrArray(x: JsonElement?, max: Int, maxItems: Int): Boolean {
        val arr = x as? JsonArray ?: return false
        if (arr.size > maxItems) return false
        return arr.all { isStr(it, max) }
    }

    // -------------------------------------------------------------- payload 校验

    fun validatePayload(type: String, payload: JsonElement?, textLimit: Int = Protocol.TEXT_UDP_LIMIT): Boolean {
        val o = payload as? JsonObject ?: return false

        return when (type) {
            MsgTypes.ENTRY, MsgTypes.ALIVE, MsgTypes.PROFILE -> validateProfilePayload(o)
            MsgTypes.PRESENCE -> validatePresence(o)
            MsgTypes.MSG -> validateMsg(o, textLimit)
            MsgTypes.ACK -> isStr(o["ackFor"], Limits.ID)
            MsgTypes.AGENT_SESSION -> validateAgentSession(o)
            MsgTypes.AGENT_EVENT -> validateAgentEvent(o, textLimit)
            MsgTypes.AGENT_QUEUE -> validateAgentQueue(o)
            MsgTypes.AGENT_RUN -> validateAgentRun(o)
            MsgTypes.AGENT_APPROVAL -> validateAgentApproval(o)
            MsgTypes.AGENT_CANCEL -> validateAgentCancel(o)
            MsgTypes.AGENT_ACK -> validateAgentAck(o)
            MsgTypes.AGENT_ARTIFACT -> validateAgentArtifact(o)
            MsgTypes.AGENT_WORKSPACE -> validateAgentWorkspace(o)
            MsgTypes.AGENT_GRANT_REQUEST -> validateAgentGrantRequest(o)
            MsgTypes.AGENT_GRANT -> validateAgentGrant(o)
            MsgTypes.AGENT_GRANT_LIST -> validateAgentGrantList(o)
            MsgTypes.AGENT_SESSION_PAGE -> validateAgentSessionPage(o)
            MsgTypes.GROUP -> validateGroup(o)
            MsgTypes.SHARE -> validateShare(o)
            MsgTypes.SCAN_RANGES -> validateScanRanges(o)
            MsgTypes.SCREEN -> CodecScreen.validateScreen(o)
            MsgTypes.AVATAR -> validateAvatar(o)
            // exit 与其余已知类型：TS 侧就是 `return isRecord(payload)`。
            else -> true
        }
    }

    // -------------------------------------------------------------------- decode

    /** UDP 入站解码：先查 4096 字节硬上限，再解析。 */
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size > Protocol.UDP_MAX_INBOUND) return DecodeResult.Rejected(RejectReason.OVERSIZE)
        return parseAndDecode(bytes.decodeToString(), Protocol.TEXT_UDP_LIMIT)
    }

    fun decode(text: String): DecodeResult {
        if (utf8ByteLength(text) > Protocol.UDP_MAX_INBOUND) return DecodeResult.Rejected(RejectReason.OVERSIZE)
        return parseAndDecode(text, Protocol.TEXT_UDP_LIMIT)
    }

    /** TCP 入站解码：文本上限放宽到 4096 字符，不设 4096 字节硬上限。 */
    fun decodeTcp(text: String): DecodeResult = parseAndDecode(text, Protocol.TEXT_TCP_LIMIT)

    fun decodeTcp(bytes: ByteArray): DecodeResult = parseAndDecode(bytes.decodeToString(), Protocol.TEXT_TCP_LIMIT)

    private fun parseAndDecode(text: String, textLimit: Int): DecodeResult {
        val raw: JsonElement = try {
            wireJson.parseToJsonElement(text)
        } catch (_: Exception) {
            return DecodeResult.Rejected(RejectReason.BAD_JSON)
        }
        return decodeEnvelopeObject(raw, textLimit)
    }

    /**
     * 已解析对象的信封校验。检查顺序与 TS `decodeEnvelopeObject` 一致：
     * not-object → version → type → id → from → ts → payload。
     */
    fun decodeEnvelopeObject(raw: JsonElement?, textLimit: Int = Protocol.TEXT_UDP_LIMIT): DecodeResult {
        val o = raw as? JsonObject ?: return DecodeResult.Rejected(RejectReason.NOT_OBJECT)

        val v = intOf(o["v"]) ?: return DecodeResult.Rejected(RejectReason.VERSION)
        if (v != Protocol.VERSION.toLong()) return DecodeResult.Rejected(RejectReason.VERSION)

        val type = strOrNull(o["type"], Limits.TYPE) ?: return DecodeResult.Rejected(RejectReason.BAD_TYPE)
        val id = strOrNull(o["id"], Limits.ID) ?: return DecodeResult.Rejected(RejectReason.BAD_ID)
        val from = strOrNull(o["from"], Limits.FROM) ?: return DecodeResult.Rejected(RejectReason.BAD_FROM)

        val ts = intOf(o["ts"]) ?: return DecodeResult.Rejected(RejectReason.BAD_TS)
        if (ts <= 0) return DecodeResult.Rejected(RejectReason.BAD_TS)

        if (!o.containsKey("payload")) return DecodeResult.Rejected(RejectReason.NO_PAYLOAD)
        val payload: JsonElement = o["payload"] ?: JsonNull

        val known = type in MsgTypes.KNOWN
        if (known && !validatePayload(type, payload, textLimit)) {
            return DecodeResult.Rejected(RejectReason.badPayload(type))
        }

        return DecodeResult.Ok(
            Envelope(v = Protocol.VERSION, type = type, id = id, from = from, ts = ts, payload = payload),
            known = known,
        )
    }

    // -------------------------------------------------------------------- encode

    /** 编码为线上文本。字段顺序由 data class 声明顺序决定，必须与 TS 一致。 */
    fun encode(env: Envelope): String = wireJson.encodeToString(Envelope.serializer(), env)

    fun encodeToBytes(env: Envelope): ByteArray = encode(env).encodeToByteArray()

    fun ackPayload(ackFor: String): JsonElement =
        wireJson.encodeToJsonElement(AckPayload.serializer(), AckPayload(ackFor))
}
