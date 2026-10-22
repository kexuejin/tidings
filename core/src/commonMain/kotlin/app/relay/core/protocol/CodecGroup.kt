// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.relay.core.protocol.CodecWire.intOf
import app.relay.core.protocol.CodecWire.isStr
import app.relay.core.protocol.CodecWire.isStrAllowEmpty
import app.relay.core.protocol.CodecWire.strAllowEmptyOrNull
import app.relay.core.protocol.CodecWire.strOrNull
import app.relay.core.protocol.CodecWire.wireJson

/**
 * 群元数据：`group` 帧的校验，以及 info/need 的构造与解码。
 *
 * 从 `Codec.kt` 按「变化原因」切出。`validateGroup` **刻意没有字段白名单**（与 `msg`
 * 不同），它自己的 KDoc 写了为什么：对端节点可能附带未扩展的已知/未知键，加严等于把新
 * 版本踢下线。加白名单之前请先读那段注释。
 */
object CodecGroup {

    /**
     * `group` 帧校验（协议 §7.4），逐条对齐 TS `codec.ts` 的 `MSG_TYPES.group` 分支。
     *
     * **没有字段白名单** —— 与 `msg` 不同，TS 这里不调 `hasOnlyKeys`，多一个键不会整帧丢弃。
     * 本函数刻意不"顺手加严"：对端节点可能附带未扩展的已知/未知键（v0.54 的 `description`/`announce`
     * 就是这么来的），加严等于单方面把新版本踢下线。
     */
    fun validateGroup(o: JsonObject): Boolean {
        val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (op == GroupOps.NEED) return isStr(o["groupId"], Limits.ID)
        if (op != GroupOps.INFO) return false

        val meta = o["group"] as? JsonObject ?: return false
        if (!isStr(meta["groupId"], Limits.ID)) return false
        if (!isStr(meta["name"], Limits.GROUP_NAME)) return false

        val membersEl = meta["members"] as? JsonArray ?: return false
        if (membersEl.isEmpty() || membersEl.size > GROUP_MAX_MEMBERS) return false
        if (!membersEl.all { isStr(it, Limits.FROM) }) return false
        val members = membersEl.map { (it as JsonPrimitive).content }

        val rev = intOf(meta["rev"]) ?: return false
        if (rev < 1) return false
        if (!isStr(meta["updatedBy"], Limits.FROM)) return false
        val updatedTs = intOf(meta["updatedTs"]) ?: return false
        if (updatedTs <= 0) return false

        if (meta.containsKey("creatorIp") && !isStrAllowEmpty(meta["creatorIp"], Limits.IP)) return false
        if (meta.containsKey("creatorId") && !isStrAllowEmpty(meta["creatorId"], Limits.FROM)) return false

        // memberNicks（可选）：nodeId → 昵称。只做**形状**校验（对象、键是成员、值是受限串），
        // 不做语义判断 —— 昵称是纯展示数据，越界的键在解码侧会被 members 过滤掉。
        if (meta.containsKey("memberNicks")) {
            val nicks = meta["memberNicks"] as? JsonObject ?: return false
            if (nicks.size > GROUP_MAX_MEMBERS) return false
            if (!nicks.all { (k, v) -> k in members && isStr(v, Limits.NICK) }) return false
        }

        // ownerId 与 adminIds 必须成对出现：只给一个就拒（TS: hasOwnerId !== hasAdminIds）。
        val hasOwnerId = meta.containsKey("ownerId")
        val hasAdminIds = meta.containsKey("adminIds")
        if (hasOwnerId != hasAdminIds) return false
        if (hasOwnerId) {
            val ownerId = strOrNull(meta["ownerId"], Limits.FROM) ?: return false
            if (ownerId !in members) return false
            val adminIds = (meta["adminIds"] as? JsonArray)?.mapNotNull {
                strOrNull(it, Limits.FROM)
            } ?: return false
            // mapNotNull 会悄悄吞掉非法项，长度对不上就说明吞过。
            if (adminIds.size != (meta["adminIds"] as JsonArray).size) return false
            if (adminIds.size > GROUP_MAX_MEMBERS) return false
            if (adminIds.toSet().size != adminIds.size) return false
            if (ownerId in adminIds) return false
            if (!adminIds.all { it in members }) return false
        }

        // avatarHash：空串是合法值（= 用默认群图标），非空必须是 hex64。
        if (meta.containsKey("avatarHash")) {
            val raw = strAllowEmptyOrNull(meta["avatarHash"], Limits.AVATAR_HASH) ?: return false
            if (raw.isNotEmpty() && !isHex64(raw)) return false
        }
        if (meta.containsKey("adminSecretHash")) {
            val raw = strAllowEmptyOrNull(meta["adminSecretHash"], Limits.GROUP_ADMIN_HASH) ?: return false
            if (raw.isNotEmpty() && !isHex64(raw)) return false
        }
        if (meta.containsKey("adminHint") && !isStrAllowEmpty(meta["adminHint"], Limits.GROUP_ADMIN_HINT)) return false
        if (meta.containsKey("description") && !isStrAllowEmpty(meta["description"], Limits.GROUP_DESCRIPTION)) return false
        if (meta.containsKey("announce") && !isStrAllowEmpty(meta["announce"], Limits.GROUP_ANNOUNCE)) return false

        return true
    }

    // ------------------------------------------------------------------ 群元数据

    /** `group{op:"info"}`：全量元数据快照。可靠 + 离线入队（"成员回来即知道自己进了群"）。 */
    fun groupInfoPayload(meta: GroupMeta): JsonElement =
        wireJson.encodeToJsonElement(
            GroupInfoPayload.serializer(),
            GroupInfoPayload(op = GroupOps.INFO, group = meta),
        )

    /** `group{op:"need"}`：请把 [groupId] 的全量元数据发我。尽力而为，不入离线队列。 */
    fun groupNeedPayload(groupId: String): JsonElement =
        wireJson.encodeToJsonElement(
            GroupNeedPayload.serializer(),
            GroupNeedPayload(op = GroupOps.NEED, groupId = groupId),
        )

    /**
     * 从**已通过** [validateGroup] 的载荷里取出元数据。
     *
     * 与入站解码的其余部分同一纪律：手写逐字段取，不用 `@Serializable` 反序列化 ——
     * 后者遇到"字段类型不对"会抛，而这里要的是"安静地返回 null"（坏帧不该让收包线程炸）。
     *
     * 调用方**必须先校验**：本函数只做"取"，不做"判"。`null` 只表示形状不是 info 帧。
     */
    fun decodeGroupInfo(payload: JsonElement?): GroupMeta? {
        val o = payload as? JsonObject ?: return null
        val meta = o["group"] as? JsonObject ?: return null
        val members = (meta["members"] as? JsonArray)
            ?.mapNotNull { strOrNull(it, Limits.FROM) } ?: return null
        return GroupMeta(
            groupId = strOrNull(meta["groupId"], Limits.ID) ?: return null,
            name = strOrNull(meta["name"], Limits.GROUP_NAME) ?: return null,
            members = members,
            rev = (intOf(meta["rev"]) ?: return null).toInt(),
            updatedBy = strOrNull(meta["updatedBy"], Limits.FROM) ?: return null,
            updatedTs = intOf(meta["updatedTs"]) ?: return null,
            creatorIp = strAllowEmptyOrNull(meta["creatorIp"], Limits.IP),
            creatorId = strAllowEmptyOrNull(meta["creatorId"], Limits.FROM),
            ownerId = strOrNull(meta["ownerId"], Limits.FROM),
            adminIds = (meta["adminIds"] as? JsonArray)?.mapNotNull { strOrNull(it, Limits.FROM) },
            avatarHash = strAllowEmptyOrNull(meta["avatarHash"], Limits.AVATAR_HASH),
            adminSecretHash = strAllowEmptyOrNull(meta["adminSecretHash"], Limits.GROUP_ADMIN_HASH),
            adminHint = strAllowEmptyOrNull(meta["adminHint"], Limits.GROUP_ADMIN_HINT),
            description = strAllowEmptyOrNull(meta["description"], Limits.GROUP_DESCRIPTION),
            announce = strAllowEmptyOrNull(meta["announce"], Limits.GROUP_ANNOUNCE),
            // 只收**属于 members** 的键：越界键一律丢掉（它们不可能是这个群的成员，
            // 留着只会在界面上凭空多出一行）。一个都没有就是 null —— 与"旧端不发"同义。
            memberNicks = (meta["memberNicks"] as? JsonObject)
                ?.mapNotNull { (k, v) ->
                    val nick = strOrNull(v, Limits.NICK) ?: return@mapNotNull null
                    if (k in members) k to nick else null
                }
                ?.takeIf { it.isNotEmpty() }
                ?.toMap(),
            // **`virtualAgents` 必须在这里解出来。**
            //
            // 本函数是**手写逐字段解析**，而编码走 `@Serializable`（`Codec.groupInfoPayload`）——
            // 加字段时只加进了 data class、忘了这里，于是：
            //
            //   发送端编码出 `virtualAgents: [...]`（完整）
            //   接收端解码成 `virtualAgents = null`（被漏掉）
            //
            // 后果是整条链路的判据全部失效：`GroupMerge` 里「本地缺、对方有」那个例外永远
            // 看到 `incomingHasAgents = false`，同 rev 的补全被当成 `stale-rev` 丢弃 ——
            // 手机上群里永远 0 个 agent、输入 `@` 找不到、气泡署名停在 `dsh`。
            //
            // 真机排查了很久：帧确实到了、数据确实完整（`messages.log` 里那个数组清清楚楚），
            // 但落库的结果一直是空。**编码与解码各写一遍，就是会漏在这一边。**
            virtualAgents = (meta["virtualAgents"] as? JsonArray)?.mapNotNull { item ->
                val a = item as? JsonObject ?: return@mapNotNull null
                VirtualAgentMember(
                    agentId = strOrNull(a["agentId"], Limits.ID) ?: return@mapNotNull null,
                    hostNodeId = strOrNull(a["hostNodeId"], Limits.FROM) ?: return@mapNotNull null,
                    name = strOrNull(a["name"], Limits.NICK) ?: return@mapNotNull null,
                    role = strOrNull(a["role"], Limits.ID),
                    wsId = strOrNull(a["wsId"], Limits.ID),
                )
            }?.takeIf { it.isNotEmpty() },
        )
    }

    /** 从 `group{op:"need"}` 载荷里取 groupId。同样要求调用方先校验。 */
    fun decodeGroupNeed(payload: JsonElement?): String? {
        val o = payload as? JsonObject ?: return null
        return strOrNull(o["groupId"], Limits.ID)
}
}
