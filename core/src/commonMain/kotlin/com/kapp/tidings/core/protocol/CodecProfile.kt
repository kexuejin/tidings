// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.kapp.tidings.core.protocol.CodecWire.intOf
import com.kapp.tidings.core.protocol.CodecWire.isStr
import com.kapp.tidings.core.protocol.CodecWire.isStrAllowEmpty
import com.kapp.tidings.core.protocol.CodecWire.strAllowEmptyOrNull
import com.kapp.tidings.core.protocol.CodecWire.strOrNull
import com.kapp.tidings.core.protocol.CodecWire.wireJson

/**
 * 节点身份与在线状态：profile / presence 帧的校验与构造，外加三个 avatar payload。
 *
 * 从 `Codec.kt` 按「变化原因」切出：这一族随「节点能上报哪些字段」而变，与消息、
 * 群、传输无关；`validateProfile` 里的 avatar guard（`isAvatarPresetValue` /
 * `isAvatarHash`）也归在这里，因为它们校验的就是 profile 帧里的字段。
 */
object CodecProfile {

    fun isAvatarPresetValue(x: JsonElement?): Boolean {
        val n = intOf(x) ?: return false
        return n >= AVATAR_LEGACY_INITIAL_VALUE.toLong() && n <= AVATAR_INITIAL_COLOR_MAX.toLong()
    }

    fun isAvatarHash(x: JsonElement?): Boolean {
        val s = strOrNull(x, Limits.AVATAR_HASH) ?: return false
        return isHex64(s)
    }

    // -------------------------------------------------------------- profile 校验

    fun validateProfile(p: JsonElement?): Boolean {
        val o = p as? JsonObject ?: return false

        if (!isStr(o["nodeId"], Limits.FROM)) return false
        if (!isStr(o["nick"], Limits.NICK)) return false
        if (!isStrAllowEmpty(o["company"], Limits.COMPANY)) return false
        if (!isStrAllowEmpty(o["dept"], Limits.DEPT)) return false
        if (!isStrAllowEmpty(o["team"], Limits.TEAM)) return false
        if (!isAvatarPresetValue(o["avatar"])) return false

        // 注意：`avatarHash` 存在即必须是合法 hex64 —— 显式 null 也算「存在」。
        if (o.containsKey("avatarHash") && !isAvatarHash(o["avatarHash"])) return false

        val rev = intOf(o["profileRev"]) ?: return false
        if (rev < 0) return false

        if (!isStr(o["host"], Limits.HOST)) return false

        val platform = (o["platform"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (platform == null || platform !in Platforms.ALL) return false

        val tcpPort = intOf(o["tcpPort"]) ?: return false
        if (tcpPort < 1 || tcpPort > 65535) return false

        if (!isStrAllowEmpty(o["ver"], Limits.VER)) return false
        // task-38：可选的本机 TLS pin（配对时随资料带过来）。旧端没有这个键 ⇒ 跳过。
        if (o.containsKey("tlsPin") && !isStr(o["tlsPin"], Limits.TLS_PIN)) return false

        val caps = o["caps"] as? JsonArray ?: return false
        if (caps.size > Limits.CAPS) return false
        // TS 允许 caps 里出现空串项，所以用 allow-empty 变体。
        for (c in caps) {
            strAllowEmptyOrNull(c, Limits.CAP_ITEM) ?: return false
        }

        // 可选键：两个 luciola 数据面端口。老端没有这些键 ⇒ 跳过。
        //
        // **必须与 tcpPort 同一条纪律做范围校验**。它们是**拨号目标**：越界值不会被拒，
        // 而是原样进 registry，再由 `port.toUShort()` 截断成另一个端口 —— 表现是
        // 「消息发不出去、也没有任何错误」，正是 §3 那类静默丢失。
        // 注意：发现层解码 `peer.extra` 时**不走这里**（那是 native 引擎给的原始 JSON），
        // 所以消费端（LuciolaDiscoveryEngine.applyPeer）另有一道 range 护栏，两处都要有。
        if (o.containsKey("luciolaPort") && !isPortNumber(o["luciolaPort"])) return false
        if (o.containsKey("screenPort") && !isPortNumber(o["screenPort"])) return false

        // 可选键：本机全部可路由地址（含 Tailscale / ZeroTier 段）。老端没有这个键 ⇒ 跳过。
        //
        // 与 `caps` 同款逐项校验：元素是 IP 字面量（IPv6 最长 45），数量封顶
        // [Limits.CAPS]。**不校验它是否真的可达** —— 那会变成「拿外部网络当门禁」，
        // 而本仓对那类判据的纪律是「没验到 ≠ 验坏了」，不为它编造结论。
        if (o.containsKey("addresses")) {
            val addrs = o["addresses"] as? JsonArray ?: return false
            if (addrs.size > Limits.CAPS) return false
            for (a in addrs) {
                if (!isStr(a, Limits.IP)) return false
            }
        }

        return true
    }

    /** 端口字面量：整数且落在 1..65535。显式 null 视为非法（与 avatarHash 同口径）。 */
    private fun isPortNumber(x: JsonElement?): Boolean {
        val p = intOf(x) ?: return false
        return p in 1..65535
    }

    internal fun validateProfilePayload(o: JsonObject): Boolean {
        if (!validateProfile(o["profile"])) return false
        if (o.containsKey("probeId") && !isStr(o["probeId"], Limits.ID)) return false
        return true
    }

    internal fun validatePresence(o: JsonObject): Boolean {
        val seq = intOf(o["seq"]) ?: return false
        if (seq < 0) return false
        val rev = intOf(o["profileRev"]) ?: return false
        if (rev < 0) return false
        return true
    }

    // ------------------------------------------------------------ payload 构造器

    fun profilePayload(profile: Profile, probeId: String? = null): JsonElement =
        wireJson.encodeToJsonElement(ProfilePayload.serializer(), ProfilePayload(profile, probeId))

    fun presencePayload(seq: Int, profileRev: Int): JsonElement =
        wireJson.encodeToJsonElement(PresencePayload.serializer(), PresencePayload(seq, profileRev))

    fun avatarGetPayload(hash: String): JsonElement = wireJson.encodeToJsonElement(
        AvatarGetPayload.serializer(),
        AvatarGetPayload(hash = hash),
    )

    fun avatarDataPayload(hash: String, data: String): JsonElement = wireJson.encodeToJsonElement(
        AvatarDataPayload.serializer(),
        AvatarDataPayload(hash = hash, data = data),
    )

    fun avatarMissPayload(hash: String): JsonElement = wireJson.encodeToJsonElement(
        AvatarMissPayload.serializer(),
        AvatarMissPayload(hash = hash),
    )
}
