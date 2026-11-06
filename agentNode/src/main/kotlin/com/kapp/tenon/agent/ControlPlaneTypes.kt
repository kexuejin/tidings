// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.AgentEventAttachment
import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 控制面的契约类型与纯件。
 *
 * 控制面对外承诺的值，以及几个不碰 `HttpExchange`、也不读写状态的纯函数。
 *
 * **为什么单独一个文件**：这些类型是 `:orchestration` / `:desktopApp` / 测试直接构造的
 * （`SandboxPatchRef` 要合流、`GrantReplyOutcome` 要显示成败、`LiveAgentEvent` 要订阅），
 * 它们是**契约**而不是某条路由的实现细节 —— 搬路由的时候不该跟着搬。
 */

/**
 * 一次授权决定回给设备（agent-grant 帧）的结局。
 *
 * 存在的理由：**发帧这件事有两个调用方** —— 控制面的 PUT /v1/agent/grants，以及桌面端
 * 「授信设备」面板上人按的按钮。面板此前只能写本机台账（AgentGrantStore），发帧的逻辑
 * 埋在 HTTP 处理器里，界面够不到 ⇒ 用户点完手机看不到。把实现抽到
 * [ControlPlane.sendGrantReply] 之后两处共用一份，而这个类型就是那份实现的返回值。
 *
 * 三个分支必须分开，因为它们对用户说的话完全不同：
 *  - [Sent]：帧发出去了（对端 ACK）；
 *  - [Unsupported]：对端没声明 agr1 —— **没发**。台账照写（那是本机事实），但界面不许说"已发送"；
 *  - [Failed]：发失败（传输异常）。同样只写台账，不假装成功。
 */
sealed interface GrantReplyOutcome {
    val nodeId: String

    /** 一句如实的回执（HTTP 的 notes 直接用它，面板也按它区分成败）。 */
    val note: String

    data class Sent(override val nodeId: String) : GrantReplyOutcome {
        override val note: String get() = "grantReply sent -> " + nodeId
    }

    data class Unsupported(override val nodeId: String, val cap: String) : GrantReplyOutcome {
        override val note: String
            get() = "grantReply 未发送：对方未声明 " + cap +
                "（不认识 grant 帧）。台账已写，对方升级后再申请会拿到当前清单"
    }

    data class Failed(override val nodeId: String, val reason: String) : GrantReplyOutcome {
        override val note: String
            get() = "grantReply failed: " + reason + "（台账已写，对方下次申请会拿到当前清单）"
    }
}

/**
 * 跨端派单的宿主侧委托（M4，规格 §6.2）。
 *
 * `POST /v1/agent/invoke` 只做 HTTP 解包：真正的派发（P2P 网道、租约、看板卡广播）
 * 由**装配了域层的那一侧**实现（desktopApp 把它接到 `RelayController.dispatchCrossHostInvoke`）。
 * agentNode 单进程没有域层（没有群、没有消息库），这个属性就是 null ⇒ 路由如实回 503，
 * 不假装接了单。
 */
fun interface AgentInvokeDispatcher {
    /** @return invokeId（派单已受理，租约已登记）；null = 群里没有这个 agent 的声明。 */
    suspend fun dispatch(groupId: String, agentId: String, prompt: String, wsId: String?, deadlineMs: Long): String?
}

/**
 * 合流要的三样本机事实：patch 在哪个会话、应用到哪个仓、patch 文件在哪。
 *
 * 三样必须一起给出 —— 只给 (仓, patch) 的话，合并结果（`merge-result` 事件）就落不回
 * 那个会话；只给会话的话，动作侧还得再查一次。合成一个值，调用点就没有漏一半的机会。
 */
data class SandboxPatchRef(
    val sessionId: String,
    val workspacePath: String,
    val patchPath: String,
)

/** 实时增量事件（T-G3）：进程内管道的载荷，不进任何协议帧。 */
data class LiveAgentEvent(
    val nodeId: String,
    val sessionId: String,
    val eventType: String,
    val body: String?,
    val at: Long,
)

/**
 * 附件闸门（fail-closed）：对端没声明 att1 ⇒ 整条附件不带。
 *
 * 抽成纯函数是为了让它可单测：这条判据的方向性（不声明 ⇒ 一定不带）比实现更重要 ——
 * 反了会让没声明 att1 的旧端收到一个它不认识的键，而 Codec 是严格白名单，整帧会被拒。
 */
internal fun gatedAttachment(parsed: AgentEventAttachment?, peerDeclaresAttachment: Boolean): AgentEventAttachment? =
    if (peerDeclaresAttachment) parsed else null

/**
 * POST /v1/agent/event 的 attachment 字段 → 类型化对象（只读协议白名单里的六个键）。
 *
 * 返回 null = 这个字段**不成形状**：不是对象、缺 attachmentId/mediaType、或某个可选键的
 * JSON 类型不对。调用方据此回 400（而不是当没带附件 —— 那会把一个错误洗成合法帧）。
 * 多出来的键不进这一帧：白名单由 Codec 的 validateAgentEventAttachment 兜底，
 * 这里按六个已知键重建，线上的帧因此永远是干净的。
 */
internal fun parseAgentEventAttachment(value: kotlinx.serialization.json.JsonElement?): AgentEventAttachment? {
    val o = value as? kotlinx.serialization.json.JsonObject ?: return null
    // 必填两键必须是**字符串**：JsonPrimitive 的 contentOrNull 会把数字 123 读成 "123"，
    // 那等于把类型错误洗成合法帧（越界/形状都该由 Codec 一处裁决，不是在这里放宽）。
    val id = (o["attachmentId"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val mediaType = (o["mediaType"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!jsonShapeOk(o["width"], int = true) || !jsonShapeOk(o["height"], int = true)) return null
    if (!jsonShapeOk(o["bytes"], long = true)) return null
    if (!jsonShapeOk(o["name"], text = true)) return null
    return AgentEventAttachment(
        attachmentId = id,
        mediaType = mediaType,
        width = o["width"]?.jsonPrimitive?.intOrNull,
        height = o["height"]?.jsonPrimitive?.intOrNull,
        bytes = o["bytes"]?.jsonPrimitive?.longOrNull,
        name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content,
    )
}

/**
 * 宿主侧的审批回执授权判据 —— `AgentSessionRegistry.approvalAuthority` 的唯一实现。
 *
 * 取 `(approval.nodeId, approval.sessionId)` 对应的会话，从中拿 `agentKey`/`wsId`
 * 去查授权台账：授权是按 agent / 工作区授的，只按 nodeId 判会把「只批了这个 agent」
 * 当成「整台设备都能批」。会话还没宣告（拿不到 agentKey）时传 null ⇒ 只有 scope=all
 * 或 `isMyDevice` 的授权能命中 —— 缺信息就往严里判，不猜。
 */
internal fun approvalAuthorityFor(
    grants: AgentGrantStore,
    sessions: AgentSessionRegistry,
): (AgentSessionRegistry.ApprovalRecord) -> Boolean = { approval ->
    val session = sessions.sessionOrNull(approval.nodeId, approval.sessionId)
    grants.allowsApproval(
        nodeId = approval.nodeId,
        agentKey = session?.agentId ?: session?.agentKind,
        wsId = session?.wsId,
    )
}

/** 缺席 = 合法（可选键）；在场则类型必须匹配，否则整条 attachment 不成形状。 */
private fun jsonShapeOk(
    value: kotlinx.serialization.json.JsonElement?,
    int: Boolean = false,
    long: Boolean = false,
    text: Boolean = false,
): Boolean {
    if (value == null || value is kotlinx.serialization.json.JsonNull) return true
    val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return false
    return when {
        // !isString：JsonPrimitive("920").intOrNull 会把 JSON 字符串当数字解析，
        // 那样类型错误就被洗成合法了。
        int -> !primitive.isString && primitive.intOrNull != null
        long -> !primitive.isString && primitive.longOrNull != null
        text -> primitive.isString
        else -> false
    }
}
/**
 * 从在线对端里挑「收这条 hook」的那一台（`hook/approval` / `hook/session` 共用）。
 *
 * 为什么不是「nodeId 最小的在线对端」：同一台手机重装或重配对后，节点表里会留下两条指向
 * 同一个 `ip:udpPort` 的记录 —— 旧那条 `profileRev` 停在旧值，`lastSeen` 却仍被同一台设备
 * 的广播刷新，于是**两条都算在线**。审批按 `to` 这个 nodeId 落账，回执却由手机**当前**的
 * nodeId 发出（`applyAckToApproval` 按 `key.nodeId == envelope.from` 匹配），挑错那条等于
 * 把审批记在一个永远不会回执的身份上：手机明明点了批准，宿主却等到超时按 fail-closed 拒绝。
 *
 * 所以先按端点折叠 —— 同一 `ip:udpPort` 只留 `profileRev` 最大的一条（rev 随资料变更单调增，
 * 旧记录永远是停住的那条），并列取 `lastSeen` 更新的；折叠完再按 nodeId 取最小保确定性。
 */
internal fun pickHookPeer(peers: List<com.kapp.tenon.core.transport.PeerRecord>): String? =
    peers
        .filter { it.online && it.profile.caps.contains(MsgTypes.CAP_AGENT_QUEUE) }
        .groupBy { it.ip + ":" + it.udpPort }
        .map { (_, sameEndpoint) ->
            sameEndpoint.maxWithOrNull(
                compareBy<com.kapp.tenon.core.transport.PeerRecord> { it.profile.profileRev }
                    .thenBy { it.lastSeen },
            ) ?: sameEndpoint.first()
        }
        .map { it.profile.nodeId }
        .minOrNull()
