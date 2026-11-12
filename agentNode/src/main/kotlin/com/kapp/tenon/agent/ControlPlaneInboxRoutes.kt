// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.mentionedVirtualAgent
import com.kapp.tenon.core.protocol.AgentSessionPick
import com.kapp.tenon.core.protocol.sameAgent
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 收件箱（/v1/inbox）。
 *
 * 把散在协议帧里的事实拼成「一条会话现在长什么样」：会话行 + 未读 + 队列状态 +
 * 运行时执行面。宿主面板的一次轮询就读这一条。
 *
 * 拼装用 `AgentInboxEnricher` 注入的取值器，没有注入就如实缺省。
 *
 * 这条路由会往 [ControlPlane.inboxTrace] 里写两件事：**桥来取过件**（每次请求都记）与
 * 每条的**注入判定**。「接入与通道」页只读那份台账 —— 它是宿主侧唯一能证明「桥在不在干活」
 * 的东西（桥不是插件，没有心跳）。除此之外不写任何状态：这个接口是幂等的读。
 */

internal fun ControlPlane.inbox(exchange: HttpExchange) {
    val query = parseQuery(exchange.requestURI.rawQuery)
    val since = query["since"]?.toLongOrNull() ?: 0L
    val limit = (query["limit"]?.toIntOrNull() ?: ControlPlane.DEFAULT_LIMIT).coerceIn(1, ControlPlane.MAX_LIMIT)
    val rows = runtime.inbox(since, limit)
    // **桥来取过件**这件事本身要记：没有它，「一条可注入消息都没有」与「桥根本没在跑」
    // 在接入与通道页上长得一模一样（页面只能画一个说不出话的空态）。
    inboxTrace.recordPoll()
    respond(
        exchange,
        200,
        buildJsonObject {
            put("since", since)
            putJsonArray("messages") {
                rows.forEach { row ->
                    addJsonObject {
                        put("seq", row.seq)
                        put("peerId", row.peerId)
                        put("direction", row.direction)
                        put("at", row.at)
                        val envelopeElement = runCatching {
                            json.parseToJsonElement(row.envelopeJson)
                        }.getOrElse { JsonNull }
                        put("envelope", envelopeElement)
                        // **注入判定随消息一起给出**（见 [AgentInboxEnricher] 的 KDoc）。
                        // 只在 enricher 非空且真的判出「这是给 agent 的」时才写这个键 ——
                        // 不写比写 null 更有信息量：旧桥接侧看到的是「没有这个键」，
                        // 与「有键但为 null」区分得开。
                        inboxEnricher?.let { enricher ->
                            val payload = (envelopeElement as? JsonObject)
                                ?.get("payload") as? JsonObject
                            val view = JsonObjectLike(
                                text = (payload?.get("text") as? JsonPrimitive)
                                    ?.takeIf { it.isString }?.content,
                                groupId = (payload?.get("groupId") as? JsonPrimitive)
                                    ?.takeIf { it.isString }?.content,
                                agentSessionId = (payload?.get("agentSessionId") as? JsonPrimitive)
                                    ?.takeIf { it.isString }?.content,
                                senderId = (payload?.get("senderId") as? JsonPrimitive)
                                    ?.takeIf { it.isString }?.content,
                            )
                            // **发送者取「真正的说话者」，不是「发送这台设备」**。
                            //
                            // 三个字段的含义不同（真机实测）：
                            //
                            //   `from`               = 发送**这台设备**（永远是本机 nodeId）
                            //   `payload.senderId`   = **谁在说话**（原样透传，可能是 `agent:*`）
                            //   `row.peerId`         = 会话键（群里是 `group:xxx`）
                            //
                            // 原先只取 `from`，于是**agent 自己发的群消息**被判成「人发的」：
                            // `senderIsAgent=false` ⇒ 桥接侧的回声判据失效 ⇒ 它把自己刚才那段
                            // 回复里提到的 `@dsh` 当成新指令，**再注入给自己** —— 自激循环。
                            // 真机症状：群里出现大量 agent 的回复被反复转发，用户说「这不是我发的」。
                            //
                            // 与 AGENTS.md §2.4 同一条纪律：用户自己说的话 = 用户的 nodeId；
                            // 转发进群时**不要另算发送者**，原样用 payload.senderId。
                            //
                            // 老端不带 `senderId` 时退回 `from`（那时群里只有人，没有 agent 转发）。
                            val envelopeObj = envelopeElement as? JsonObject
                            val payloadObj = envelopeObj?.get("payload") as? JsonObject
                            val senderId = payloadObj
                                ?.get("senderId")?.jsonPrimitiveOrNull()?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?: envelopeObj?.get("from")?.jsonPrimitiveOrNull()?.contentOrNull
                                ?: row.peerId
                            // **判定在这里做**：本类持有权威授权表（[grants]），
                            // 是「投递前的门控」的执行点。桌面端只提供群元数据。
                            val meta = runCatching { enricher.groupMetaOf(view) }.getOrNull()
                            // 先算「@ 到的是哪个 agent」—— 它是台账里的 agentKey。
                            val grantsAgentKey = runCatching {
                                meta?.let { m ->
                                    val agents = m.virtualAgents.orEmpty()
                                    m.mentionedVirtualAgent(view.text.orEmpty(), agents.map { it.name })
                                        ?.let { hit -> hit.agentId.ifBlank { hit.name } }
                                }
                            }.getOrNull()
                            val hint = resolveAgentInboxHint(
                                meta = meta,
                                senderId = senderId,
                                text = view.text.orEmpty(),
                                // 显式授权那一档：`requireRw = true` —— 只读授权不含「下发任务」。
                                // **按 agent 授权**：能不能执行绑在 agent 上，不是绑在宿主上。
                                // 一台机器上跑着 dsh 与 codex，允许谁用哪一个必须分别配。
                                // `agentKey` 用 `@` 到的那个 token（与 VirtualAgentMember.name 同源）。
                                grantAllows = { nodeId ->
                                    grants.allows(
                                        nodeId = nodeId,
                                        agentKey = grantsAgentKey,
                                        wsId = null,
                                        requireRw = true,
                                        // **执行面**：`scope=ALL`（整台设备）不自动覆盖具体 agent。
                                        // 批准一台设备的初心可能是「让他用 dsh」；装了 codex 之后
                                        // 不该让它自动继承执行权。
                                        forExecution = true,
                                    )
                                },
                                // 宿主配的执行范围；没配过 = 最严一档（只有本机用户能执行）。
                                execPolicy = execPolicies.policyOf(grantsAgentKey),
                                // **本机 nodeId**：判据据此认「我在自己机器上」。
                                // 缺了它，本机用户在自己机器上 @ 自己的 agent 会被判成没授权。
                                selfNodeId = runtime.nodeId,
                                // **候选会话**：`AgentSessionPick` 要用它算 `targetSessionId`。
                                // 缺了它 targetSessionId 恒为 null ⇒ 注入永远被拒（「发消息不执行」）。
                                //
                                // 匹配口径要宽松：入驻时写进群的 agentId 可能是 `DSH`（agentKind 兜底），
                                // 而会话报的是 `dsh-029bbbf3`。两者是同一个 agent 的不同精度，
                                // 只认其中一个会让「刚入驻的 agent 找不到自己的会话」。
                                candidates = runCatching {
                                    // F4（C3，硬保证）：群派单候选**必须归属本群** ——
                                    // 内存映射丢了就查落盘台账；两边都没有（归属未知）
                                    // ⇒ 不进候选（fail-closed），绝不回退「全 agent 候选」。
                                    // 1:1（groupId 为空）不做群过滤，保持既有行为。
                                    val groupConvId = view.groupId?.let {
                                        com.kapp.tenon.core.protocol.ConvId.group(it)
                                    }
                                    sessions.sessions()
                                        .filter { rec ->
                                            !rec.synthesized && sameAgent(
                                                candidate = rec.agentId,
                                                kind = rec.agentKind,
                                                wanted = grantsAgentKey,
                                            )
                                        }
                                        .filter { rec ->
                                            groupConvId == null || run {
                                                val bound = sessions.conversationOf(rec.sessionId)
                                                    ?: conversationLedger.conversationOf(rec.sessionId)
                                                bound == groupConvId
                                            }
                                        }
                                        .sortedByDescending { rec -> rec.updatedAt }
                                        .map { rec ->
                                            AgentSessionPick.Candidate(
                                                sessionId = rec.sessionId,
                                                wsId = rec.wsId,
                                            )
                                        }
                                }.getOrElse { emptyList() },
                                // **用户点选的会话**：载荷的 `agentSessionId`（点着某条 agent
                                // 会话消息回复/续聊时带上）。此前这根线断着 —— hint 的
                                // requestedSessionId 恒为空、pick 永远走「最近活跃」默认，
                                // 无论用户点哪条都投进同一条。
                                requestedSessionId = view.agentSessionId,
                            )
                            if (hint != null) {
                                // 注入判定的读数（接入与通道页）：只记结论与成因，
                                // **不记正文、不记会话 id**（诊断台账不进任何用户内容）。
                                inboxTrace.record(peerId = row.peerId, seq = row.seq, hint = hint)
                                putJsonObject("agentHint") {
                                    put("addressedToAgent", hint.addressedToAgent)
                                    hint.boundWsId?.let { put("boundWsId", it) }
                                    hint.requestedSessionId?.let { put("requestedSessionId", it) }
                                    hint.agentName?.let { put("agentName", it) }
                                    // 授权结论随行：桥接侧据此决定投不投，并在拒绝时能说出为什么。
                                    put("senderMayExecute", hint.senderMayExecute)
                                    hint.denyReason?.let { put("denyReason", it) }
                                    // **目标会话**：判定已由 Tenon 侧算好（core 的 AgentSessionPick）。
                                    // 桥接侧直接用它，不用自己再算一遍策略。
                                    hint.targetSessionId?.let { put("targetSessionId", it) }
                                    // **发送者是不是 agent**：桥接侧用它判「回声」，而不是只看方向。
                                    // 本机用户 @ 自己的 agent 时 direction=out，但那不是回声 ——
                                    // 只看方向会让这条消息第一条就被跳过（真机症状）。
                                    put("senderIsAgent", hint.senderIsAgent)
                                    // F5（C4）：点选被拒的原因（稳定 token，桥接侧消费展示）——
                                    // 「点了别的工作区的会话」必须说得出为什么没投，不能只显「没反应」。
                                    hint.requestRejectReason?.let { put("requestRejectReason", it) }
                                }
                            }
                        }
                    }
                }
            }
            put("nextSince", rows.lastOrNull()?.at ?: since)
        },
    )
}
