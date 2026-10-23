// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 拒绝原因。**字符串值与 TS 侧逐字一致**，因为这些是协议层的对外语义
 * （日志、诊断、未来的错误回包都用它）。
 */
object RejectReason {
    const val OVERSIZE = "oversize"
    const val BAD_JSON = "bad-json"
    const val NOT_OBJECT = "not-object"
    const val VERSION = "version"
    const val BAD_TYPE = "bad-type"
    const val BAD_ID = "bad-id"
    const val BAD_FROM = "bad-from"
    const val BAD_TS = "bad-ts"
    const val NO_PAYLOAD = "no-payload"

    fun badPayload(type: String): String = "bad-payload:$type"
}

sealed interface DecodeResult {
    /** 解码通过。[known] 为 false 表示这是一个本端不认识的消息类型 —— 老节点遇到
     *  `agent-*` 扩展帧时就是这个分支：**解码成功、静默忽略、不崩溃**。 */
    data class Ok(val env: Envelope, val known: Boolean) : DecodeResult

    data class Rejected(val reason: String) : DecodeResult
}

/**
 * 信封编解码 + 入站校验（protocol §1/§4）。
 *
 * 与 TS 侧 `src/main/net/codec.ts` 保持**逐条对齐**：同样的检查顺序、同样的
 * 拒绝原因字符串、同样的边界（长度按 UTF-16 码元数，文本上限按 UTF-8 字节数）。
 * 编码侧追求**字节级一致**，由 `GoldenVectorTest` 用真实 TS 输出的向量守住。
 *
 * 入站解码是手写的（从 [JsonObject] 逐字段取），不是 `@Serializable` 反序列化 ——
 * 因为要精确复刻 TS 的「先检查哪个字段」顺序和 reason 字符串。`Payloads.kt` 里的
 * `@Serializable` 类型只用于**出站**构造。
 *
 * 覆盖范围：`entry` / `alive` / `profile` / `presence` / `msg` / `ack` / `exit` 与 PPE-1 的
 * `agent-session` / `agent-event` 是精确实现的（后者按 §5 做**字段白名单**校验）；其余已知类型
 * （`peers` / `scan-ranges` / `group` / `avatar` / `update` / `share` / `screen` / `file-ctl`）
 * 只做「是对象」的宽松校验 —— 宽松通过好过误拒真实帧。
 */
@OptIn(ExperimentalSerializationApi::class)
object Codec {

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

    // -------------------------------------------------------------- payload 校验

    fun validatePayload(type: String, payload: JsonElement?, textLimit: Int = Protocol.TEXT_UDP_LIMIT): Boolean =
        CodecWire.validatePayload(type, payload, textLimit)
    // -------------------------------------------------------------- 各族门面

    // 施工图 §3 的硬约束：公开签名一字不动，调用点零改动。门面只做一层单行委托 ——
    // 每个成员的真实实现住在按「变化原因」切出的族文件里，新增一族时在这里加一行。
    // **`wireJson` 只有门面这一份**（见上），任何族都不许自己写第二个 `Json { }`。

    fun encode(env: Envelope) = CodecWire.encode(env)

    fun decode(text: String): DecodeResult = CodecWire.decode(text)

    fun encodeToBytes(env: Envelope) = CodecWire.encodeToBytes(env)

    fun decode(bytes: ByteArray) = CodecWire.decode(bytes)

    fun decodeEnvelopeObject(raw: JsonElement?, textLimit: Int = Protocol.TEXT_UDP_LIMIT) = CodecWire.decodeEnvelopeObject(raw, textLimit)

    fun intOf(x: JsonElement?) = CodecWire.intOf(x)

    fun ackPayload(ackFor: String) = CodecWire.ackPayload(ackFor)

    fun profilePayload(profile: Profile, probeId: String? = null) = CodecProfile.profilePayload(profile, probeId)

    fun presencePayload(seq: Int, profileRev: Int) = CodecProfile.presencePayload(seq, profileRev)

    fun avatarGetPayload(hash: String) = CodecProfile.avatarGetPayload(hash)

    fun avatarDataPayload(hash: String, data: String) = CodecProfile.avatarDataPayload(hash, data)

    fun avatarMissPayload(hash: String) = CodecProfile.avatarMissPayload(hash)

    fun textPayload(text: String, resend: Boolean? = null) = CodecMsg.textPayload(text, resend)

    fun groupTextPayload(
        text: String,
        groupId: String,
        groupRev: Int,
        mentions: List<String>? = null,
        resend: Boolean? = null,
        replyTo: String? = null,
        senderId: String? = null,
        reasoning: String? = null,
        agentSessionId: String? = null,
        agentCursor: Long? = null,
        agentSessionTitle: String? = null,
    ) = CodecMsg.groupTextPayload(
        text, groupId, groupRev, mentions, resend, replyTo, senderId, reasoning,
        agentSessionId, agentCursor, agentSessionTitle,
    )

    fun recallPayload(targetId: String, groupId: String? = null, groupRev: Int? = null) = CodecMsg.recallPayload(targetId, groupId, groupRev)

    fun nudgePayload() = CodecMsg.nudgePayload()

    fun groupInfoPayload(meta: GroupMeta) = CodecGroup.groupInfoPayload(meta)

    fun groupNeedPayload(groupId: String) = CodecGroup.groupNeedPayload(groupId)

    fun pkPayload(
        game: String,
        value: String,
        groupId: String? = null,
        groupRev: Int? = null,
    ) = CodecPk.pkPayload(game, value, groupId, groupRev)

    @Suppress("LongParameterList")
    fun agentSessionPayload(
        sessionId: String,
        alias: String? = null,
        agentKind: String? = null,
        state: String? = null,
        title: String? = null,
        startedAt: Long? = null,
        agentId: String? = null,
        wsId: String? = null,
        wsTitle: String? = null,
        wsPath: String? = null,
        updatedAt: Long? = null,
        origin: String? = null,
        blank: Boolean? = null,
        gitBranch: String? = null,
        segmentId: String? = null,
    ) = CodecAgent.agentSessionPayload(
        sessionId, alias, agentKind, state, title, startedAt,
        agentId, wsId, wsTitle, wsPath, updatedAt, origin, blank,
        gitBranch, segmentId,
    )

    fun agentEventPayload(
        sessionId: String,
        cursor: Long,
        eventType: String,
        summary: String? = null,
        body: String? = null,
        ref: String? = null,
        attachment: AgentEventAttachment? = null,
    ) = CodecAgent.agentEventPayload(sessionId, cursor, eventType, summary, body, ref, attachment)

    fun agentQueuePayload(
        taskId: String,
        sessionId: String? = null,
        op: String,
        prompt: String? = null,
        queuedAt: Long? = null,
    ) = CodecAgent.agentQueuePayload(taskId, sessionId, op, prompt, queuedAt)

    fun agentRunPayload(
        sessionId: String,
        runId: String,
        state: String,
        at: Long? = null,
        note: String? = null,
    ) = CodecAgent.agentRunPayload(sessionId, runId, state, at, note)

    fun agentApprovalPayload(
        approvalId: String,
        sessionId: String,
        tool: String? = null,
        summary: String? = null,
        expiresAt: Long,
        choices: String? = null,
        riskLevel: String? = null,
        diff: String? = null,
    ) = CodecAgent.agentApprovalPayload(approvalId, sessionId, tool, summary, expiresAt, choices, riskLevel, diff)

    fun agentCancelPayload(
        sessionId: String,
        runId: String? = null,
        reason: String? = null,
    ) = CodecAgent.agentCancelPayload(sessionId, runId, reason)

    fun agentAckPayload(
        ackOf: String,
        kind: String,
        at: Long? = null,
        decidedBy: String? = null,
    ) = CodecAgent.agentAckPayload(ackOf, kind, at, decidedBy)

    fun agentArtifactPayload(
        sessionId: String,
        artifactId: String,
        name: String,
        fileId: String? = null,
        bytes: Long? = null,
        mime: String? = null,
    ) = CodecAgent.agentArtifactPayload(sessionId, artifactId, name, fileId, bytes, mime)

    /** PPE-1 增补 D3：宣告一个工作区（cap `aws1`，尽力而为、不在 RELIABLE）。 */
    @Suppress("LongParameterList")
    fun agentWorkspacePayload(
        wsId: String,
        wsTitle: String? = null,
        wsPath: String? = null,
        agentId: String? = null,
        agentKind: String? = null,
        online: Boolean? = null,
        sessionCount: Long? = null,
        at: Long? = null,
        order: Long? = null,
    ) = CodecAgent.agentWorkspacePayload(wsId, wsTitle, wsPath, agentId, agentKind, online, sessionCount, at, order)

    /** PPE-1 增补 D7：授权申请（设备 → 宿主，cap `agr1`）。 */
    fun agentGrantRequestPayload(
        agentKey: String? = null,
        wsId: String? = null,
        access: String? = null,
        reason: String? = null,
        at: Long? = null,
    ) = CodecAgent.agentGrantRequestPayload(agentKey, wsId, access, reason, at)

    /** PPE-1 增补 D7：授权决定（宿主 → 设备，cap `agr1`）。 */
    fun agentGrantPayload(
        state: String,
        scope: String,
        agentKey: String? = null,
        wsId: String? = null,
        access: String? = null,
        isMyDevice: Boolean? = null,
        delegatedApproval: Boolean? = null,
        at: Long? = null,
    ) = CodecAgent.agentGrantPayload(state, scope, agentKey, wsId, access, isMyDevice, delegatedApproval, at)

    /** PPE-1 增补 D7：授权清单条目（宿主 → 设备，cap `agr1`）。 */
    fun agentGrantListPayload(
        scope: String,
        agentKey: String? = null,
        wsId: String? = null,
        access: String? = null,
        total: Long? = null,
        at: Long? = null,
    ) = CodecAgent.agentGrantListPayload(scope, agentKey, wsId, access, total, at)

    /** PPE-1 增补 D8：`agent-session-page` 的门面透传（见 CodecAgent 的同名 builder）。 */
    fun agentSessionPagePayload(
        wsId: String,
        offset: Long? = null,
        limit: Long? = null,
        at: Long? = null,
    ) = CodecAgent.agentSessionPagePayload(wsId, offset, limit, at)

    fun shareListPayload(reqId: String, path: String = "", offset: Int = 0, snapshotId: String? = null) = CodecShare.shareListPayload(reqId, path, offset, snapshotId)

    fun shareListOkPayload(
        reqId: String,
        path: String,
        perm: String,
        snapshotId: String,
        offset: Int,
        total: Int,
        truncated: Boolean,
        entries: List<ShareEntry>,
    ) = CodecShare.shareListOkPayload(reqId, path, perm, snapshotId, offset, total, truncated, entries)

    fun shareDenyPayload(reqId: String, reason: String) = CodecShare.shareDenyPayload(reqId, reason)

    fun shareGetPayload(reqId: String, paths: List<String>) = CodecShare.shareGetPayload(reqId, paths)


    fun decodeTcp(text: String) = CodecWire.decodeTcp(text)

    fun decodeTcp(bytes: ByteArray) = CodecWire.decodeTcp(bytes)

    fun utf8ByteLength(text: String) = CodecWire.utf8ByteLength(text)

    fun validateGroup(o: JsonObject) = CodecGroup.validateGroup(o)

    fun decodeGroupInfo(payload: JsonElement?) = CodecGroup.decodeGroupInfo(payload)

    fun decodeGroupNeed(payload: JsonElement?) = CodecGroup.decodeGroupNeed(payload)

    fun scanRangesPayload(ranges: List<ScanRangeSummary>) = CodecScanRanges.scanRangesPayload(ranges)

    fun decodeScanRanges(payload: JsonElement?) = CodecScanRanges.decodeScanRanges(payload)

    fun validateGroupRef(o: JsonObject) = CodecMsg.validateGroupRef(o)

    fun validateProfile(p: JsonElement?) = CodecProfile.validateProfile(p)

    /** 字段白名单。族内 `validateAgent*` 与之同文件；这里是对外可见的一份，
     *  供协议文档与测试直接断言"这一族到底收哪几个键"。 */
    internal val AGENT_SESSION_EXPECTED_KEYS = CodecAgent.AGENT_SESSION_EXPECTED_KEYS
    internal val AGENT_ARTIFACT_EXPECTED_KEYS = CodecAgent.AGENT_ARTIFACT_EXPECTED_KEYS

    fun decodePkMsg(payload: JsonElement?) = CodecPk.decodePkMsg(payload)

    fun decodeShareMessage(payload: JsonElement?) = CodecShare.decodeShareMessage(payload)
}
