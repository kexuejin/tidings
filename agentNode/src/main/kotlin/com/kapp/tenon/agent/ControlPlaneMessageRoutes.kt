// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.OfferDeliveryStates
import com.kapp.tenon.core.protocol.Protocol
import com.kapp.tenon.core.session.NodeRuntime
import com.kapp.tenon.core.transport.LocalFile
import com.kapp.tenon.core.transport.SendOutcome
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * 消息路由（send / send-file / transfers / nudge / dev pair）。
 *
 * 把「一条消息怎么发出去」的四条入口，加上目标解析的失败记忆。
 *
 * 目标解析只有一份（`resolveTargetNodeId` + `resolveNodeIdOrRespond`）：`/v1/send` 与
 * `/v1/send-file` 的解析规则必须逐字相同，包括「给了端口就必须匹配上」那一条。
 */

internal fun ControlPlane.send(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val to = obj["to"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (to.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 to（对方 IP 或 nodeId）"))
        return
    }
    val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (text.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 text（协议不允许空文本帧）"))
        return
    }
    // 端口可选。给了就必须匹配上这个 UDP 口（见 NodeRuntime.resolvePeerByHost）：
    // 不给才退回只按 IP —— "没这个信息"和"这个信息对不上"是两回事，后者若也静默
    // 退回只按 IP，消息就会静默投给同机的另一个节点。
    val port = obj["port"]?.jsonPrimitive?.intOrNull
    if (port != null && port !in 1..65535) {
        respond(exchange, 400, errorJson("bad-request", "port 越界：$port"))
        return
    }

    // 先当 nodeId 查，查不到再当 IP 去握手 —— 调用方两种都该能用。
    val nodeId = resolveNodeIdOrRespond(exchange, to, port) ?: return

    // **这就是 task-35 那个 8.00 s**：对端不可达时这条路要走完 UDP 1+2+4 s 退避 + TCP 兜底。
    // 预算按这个已知最坏值给 —— 超过它说明退避链变长了（回归），不是"这次网络不好"。
    // 不加超时：超了之后这一帧到底进没进待发队列是不可知的，硬猜一档就是丢帧
    // （详见 CarrierHold 的 KDoc）。
    val outcome = try {
        CarrierHold.hold("send.text", CarrierHold.SEND_MS) { runtime.sendText(nodeId, text) }
    } catch (t: Throwable) {
        respond(exchange, 502, errorJson("send-failed", t.message ?: "发送失败"))
        return
    }

    respond(
        exchange,
        200,
        buildJsonObject {
            put("status", if (outcome == SendOutcome.SENT) "sent" else "queued")
            put("to", nodeId)
            put("toHost", to)
            // task-62：把「为什么没送到」一起交出去（逐端点等了多久、有没有回退 TCP、
            // 长连接当时通不通）—— 以前只有一句 queued，看不出卡在哪。
            runtime.lastReliableSendDiagnostics()?.let { put("lastSend", it.summary()) }
            put(
                "note",
                if (outcome == SendOutcome.SENT) {
                    "对方已 ACK"
                } else {
                    "已入队，等对方上线后补发（不代表已读）"
                },
            )
        },
    )
}

/**
 * POST /v1/dev/pair {host, port} —— 仅开发可用。
 *
 * 只做「发配对自证帧」，等价于界面上扫码之后的动作；它**不写入信任** ——
 * 信任来自二维码（公钥 + 配对密钥已进宿主状态）。没有码里那份密钥，
 * 对端的 PairingAdmission 会拒，所以它不能被用来绕过配对。
 */
internal fun ControlPlane.devPair(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val host = obj["host"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val port = obj["port"]?.jsonPrimitive?.intOrNull
    if (host.isEmpty() || port == null || port !in 1..65535) {
        respond(exchange, 400, errorJson("bad-request", "需要 host 与 1..65535 的 port"))
        return
    }
    val ok = try {
        CarrierHold.hold("dev.pair", CarrierHold.SEND_MS) { runtime.requestLuciolaPairing(host, port) }
    } catch (t: Throwable) {
        respond(exchange, 502, errorJson("pair-failed", t.message ?: "配对自证失败"))
        return
    }
    respond(
        exchange,
        200,
        buildJsonObject {
            put("ok", ok)
            put("host", host)
            put("port", port)
        },
    )
}

/**
 * `POST /v1/nudge` —— 戳一碰（私聊窗口震动）。
 *
 * 为什么要单独一条路由：`nudge` 是 `msg` 的一个 kind，但它**没有正文**，而且投递语义与
 * 普通消息不同 —— 协议要求「对端无响应就失败，**不进离线补发队列**」（[NodeRuntime.sendNudge]
 * 用 `sendReliableNoQueue`）。所以它不能塞进 `/v1/send` 的 `text` 里。
 *
 * 回 `{"status":"sent"}` 的含义是**对端真的 ACK 了这一帧**；`{"status":"unreachable"}`
 * 是「现在戳不到」，调用方如实转述即可 —— 协议不允许把戳一碰悄悄排队。
 *
 * 这条路由的存在也让 C8（戳一碰收到时弹通知并落到那个会话）可以被人或脚本驱动，
 * 而不必先起一个桌面宿主、再用鼠标点它的头部手指图标。
 */

internal fun ControlPlane.nudge(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val to = obj["to"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (to.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 to（对方 IP 或 nodeId）"))
        return
    }
    val port = obj["port"]?.jsonPrimitive?.intOrNull
    if (port != null && port !in 1..65535) {
        respond(exchange, 400, errorJson("bad-request", "port 越界：$port"))
        return
    }

    val nodeId = resolveNodeIdOrRespond(exchange, to, port) ?: return
    val acked = try {
        CarrierHold.hold("send.nudge", CarrierHold.SEND_MS) { runtime.sendNudge(nodeId) }
    } catch (t: Throwable) {
        respond(exchange, 502, errorJson("send-failed", t.message ?: "戳一碰失败"))
        return
    }

    respond(
        exchange,
        200,
        buildJsonObject {
            put("status", if (acked) "sent" else "unreachable")
            put("to", nodeId)
            put("toHost", to)
            put(
                "note",
                if (acked) {
                    "对方已 ACK；按协议戳一碰不排队，没 ACK 就是没送到"
                } else {
                    "对方没有 ACK，按协议不进入离线补发队列"
                },
            )
        },
    )
}

/**
 * `POST /v1/send-file` —— 把一个本机文件报价给对端。
 *
 * 与 `/v1/send` 的区别是**这里只是报价**：对方接受后才在 TCP 上真正传，因此回的是
 * `transferId` 而不是"已送达"。进度与结果不在这里轮询 —— 事件流在
 * [NodeRuntime.transfers]，界面上的卡片才是用户看到的东西。
 *
 * 为什么要有这条路由：`file-ctl offer` 是协议里的一等能力，而在此之前控制面只能发文本，
 * 于是"agent 能不能发个文件"这件事只能靠人手点界面。验收清单里的文件方向（收到的文件
 * 能不能打开/分享、大文件续传）也全都因此卡在"没法从命令行造出一个报价"。
 *
 * `name` 缺省取路径的末段：协议里的相对路径永远用 `/`，Windows 反斜杠在这里先归一化。
 *
 * `groupId`/`groupRev` 可选，非空表示这是一次**群聊媒体的点对点投递**（协议 §3）：
 * 收端据此把内联上限从 20MB 收到 10MB（`FileTransfer.GROUP_IMG_AUTO_ACCEPT`）。两者必须
 * 成对出现 —— 协议只认「都有」或「都没有」（`Codec.validateGroupRef`），缺一个就 400。
 *
 * `tableText` 可选，是表格图片的文字视图：表格里的数字要能选中复制，图片
 * 做不到。这里原样转发，超上限的截断与 `tableTextTruncated` 标记由传输层按 UTF-8 边界做。
 */
internal fun ControlPlane.sendFile(exchange: HttpExchange) {
    val obj = readJsonObject(exchange) ?: return
    val to = obj["to"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (to.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 to（对方 IP 或 nodeId）"))
        return
    }
    val rawPath = obj["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (rawPath.isEmpty()) {
        respond(exchange, 400, errorJson("bad-request", "缺少 path（本机绝对路径）"))
        return
    }
    val port = obj["port"]?.jsonPrimitive?.intOrNull
    if (port != null && port !in 1..65535) {
        respond(exchange, 400, errorJson("bad-request", "port 越界：$port"))
        return
    }

    // 路径先落到 File 上校验，再谈发送：不存在的路径不该换来一次 3 秒握手。
    val file = File(rawPath)
    if (!file.isFile) {
        respond(
            exchange,
            400,
            errorJson("bad-request", "path 不是一个可读的普通文件：$rawPath"),
        )
        return
    }
    val normalized = rawPath.replace('\\', '/')
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: normalized.substringAfterLast('/')
    val purpose = obj["purpose"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    // 群聊媒体的群 id / 版本号：非空就是一次群聊点对点投递，收端的内联上限随之减半
    // （10MB，见 FileTransfer.GROUP_IMG_AUTO_ACCEPT）。成对出现是协议的硬要求
    // （`Codec.validateGroupRef` 只认「都有」或「都没有」），所以这里先拦一次。
    val groupId = obj["groupId"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val groupRev = obj["groupRev"]?.jsonPrimitive?.intOrNull
    if ((groupId == null) != (groupRev == null)) {
        respond(
            exchange,
            400,
            errorJson("bad-request", "groupId 与 groupRev 必须成对出现（协议只认都有或都没有）"),
        )
        return
    }
    if (groupRev != null && groupRev < 0) {
        respond(exchange, 400, errorJson("bad-request", "groupRev 不能为负：$groupRev"))
        return
    }

    // 表格图片的文字视图。这里**不截断** —— 截断与置 truncated 由传输层
    // 按 UTF-8 边界做（`truncateTableText`），控制面只负责把用户给的原文递下去。
    // 协议只对 `purpose:"image"` 的单图 offer 定义了它，但那是收端的判据；
    // 控制面转发不猜语义，发错了由对端按协议忽略。
    val tableText = obj["tableText"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    val nodeId = resolveNodeIdOrRespond(exchange, to, port) ?: return

    // 协议要求发送端**只向声明了 tbl1 的对端**附带这段 TSV。对端不声明还硬发，
    // 结果是"我发了、他那边什么都没有"，而控制面回的是 status:"offered" ——
    // 一个看起来成功的失败。这里按 agn1 那条的先例直接拦掉。
    if (tableText != null && !runtime.peerDeclares(nodeId, MsgTypes.CAP_TABLE_TEXT)) {
        respond(
            exchange,
            409,
            errorJson(
                "peer-unsupported",
                "对方（$to）未声明 ${MsgTypes.CAP_TABLE_TEXT}（表格图片文字视图），" +
                    "按协议不附带 tableText；去掉这个字段后图片本身仍可正常发送",
            ),
        )
        return
    }

    // task-62：**登记即返回**。原来这里调 offerFiles —— 它会逐片等 ACK（对端不可达时走完整
    // 退避 + TCP 兜底，几十秒），于是这条路由在请求线程上阻塞到调用方超时。现在只做「登记」
    // （不碰网络），投递在后台；结果从 GET /v1/transfers 与事件流读。
    val transferId = try {
        // "登记即返回"之后这里不再等 ACK（退避几十秒的那种等待已随 task-62 挪到后台），
        // 但 prepareOffer 要对一批文件做 stat —— 所以预算按一次发送给，仍不碰网络。
        CarrierHold.hold("sendFile.register", CarrierHold.SEND_MS) {
            runtime.offerFilesAsync(
                nodeId,
                listOf(LocalFile(normalized, name, file.length())),
                purpose,
                groupId = groupId,
                groupRev = groupRev,
                tableText = tableText,
            )
        }
    } catch (t: Throwable) {
        respond(exchange, 502, errorJson("send-failed", t.message ?: "报价失败"))
        return
    }
    if (transferId == null) {
        // offerFiles 返回 null 只有一种原因：没有可用的 TCP 通道（对端没给 tcpPort，
        // 或传输层没起来）。这不是"对方拒绝"，而是"现在报不了价"。
        respond(
            exchange,
            502,
            errorJson("no-transfer-channel", "对端没有可用的 TCP 通道，文件报价发不出去"),
        )
        return
    }

    respond(
        exchange,
        200,
        buildJsonObject {
            // offering 而不是 offered：此刻只保证**已登记**，清单有没有送到对端由后台投递决定。
            // 如实区分这两个词，是 async 之后不许再说谎的那一半。
            put("status", OfferDeliveryStates.OFFERING)
            put("to", nodeId)
            put("toHost", to)
            put("transferId", transferId)
            put("name", name)
            put("size", file.length())
            put("note", "已登记，投递在后台；GET /v1/transfers 读投递状态（offered/ack-timeout）")
        },
    )
}

/**
 * `GET /v1/transfers` —— 报价的**投递状态**（只读，task-62）。
 *
 * 为什么必须有它：`/v1/send-file` 改成「登记即返回 offering」之后，「发出去没有」不能再靠
 * 那次响应回答 —— 没有这个出口，async 就等于把「发不出去」变成「看起来发出去了」。
 *
 * 每条给：状态（offering / offered / ack-timeout）、投递耗时、对端有没有来拉（accepted）、
 * 失败原因；再附一条**最近一次可靠投递的逐端点诊断**（哪个端点 no-ack、各花多久、有没有
 * 回退 TCP、长连接通不通）—— 与 `link*` 同口径。
 */
internal fun ControlPlane.transfers(exchange: HttpExchange) {
    // 纯本地台账快照，不碰网络 —— 超预算只可能是抢锁，不是网络。
    val deliveries = CarrierHold.hold("transfers.offerDeliveries", CarrierHold.LOCAL_MS) {
        runtime.offerDeliveries()
    }
    val last = runtime.lastReliableSendDiagnostics()
    respond(
        exchange,
        200,
        buildJsonObject {
            putJsonArray("transfers") {
                deliveries.forEach { d ->
                    addJsonObject {
                        put("transferId", d.transferId)
                        put("peerId", d.peerId)
                        put("rootName", d.rootName)
                        put("totalSize", d.totalSize)
                        put("state", d.state)
                        put("elapsedMs", d.elapsedMs)
                        put("attempts", d.attempts)
                        put("accepted", d.accepted)
                        d.detail?.let { put("detail", it) }
                        // 这一条**自己**的逐端点诊断（与全局 lastSend 区分：全局那个会被
                        // 随后的成功投递覆盖 —— 真机上失败那两次就是这样被盖掉的）。
                        d.lastSendSummary?.let { put("lastSend", it) }
                        // task-65：**首次**尝试的诊断单独一个键（不合成）—— 否则「首次成功率」
                        // 这个数看不出可行动的方向（首次是链路超时？还是 UDP 无应答？）。
                        d.firstAttemptSummary?.let { put("firstAttempt", it) }
                    }
                }
            }
            last?.let { diag ->
                putJsonObject("lastSend") {
                    put("peerId", diag.peerId)
                    put("outcome", diag.outcome)
                    put("elapsedMs", diag.elapsedMs)
                    put("linkConnected", diag.linkConnected)
                    put("peerOnline", diag.peerOnline)
                    put("tcpTried", diag.tcpTried)
                    put("tcpOk", diag.tcpOk)
                    put("tcpElapsedMs", diag.tcpElapsedMs)
                    put("summary", diag.summary())
                    putJsonArray("endpoints") {
                        diag.endpoints.forEach { e ->
                            addJsonObject {
                                put("host", e.host)
                                put("port", e.port)
                                put("acked", e.acked)
                                put("elapsedMs", e.elapsedMs)
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * 解析「to[:port]」到底是谁 —— **带失败记忆**。
 *
 * 为什么不是直接 `runBlocking { resolvePeerByHost(...) }`：那条路对一个不可达的
 * 主机要等满 3 秒。手机离线时它每隔几秒重试一次 agent 帧，控制面的处理器池就被
 * 这 3 秒一个个吃掉 —— 真机现场 16 条 `pool-1-thread` 全卡在这里，连 `/healthz`
 * 与 `/v1/peers` 都打不开，"控制面整个挂了"。这里给"解析失败"记 5 秒：
 * 同一个死对端的连续重试只付一次代价，其余立刻如实返回"解析不到"
 * （调用方本来就按 404 peer-unreachable 回）。
 *
 * 只记失败、不记成功：成功之后对端已经进了 registry，`runtime.getPeer` 直接命中。
 */
internal fun ControlPlane.resolveTargetNodeId(to: String, port: Int?): String? {
    runtime.getPeer(to)?.profile?.nodeId?.let { return it }
    val key = if (port != null) to + ":" + port else to
    val now = System.currentTimeMillis()
    resolveFailures[key]?.let { until -> if (until > now) return null }
    // 这条就是上面 KDoc 说的「3 秒握手」：预算照 resolvePeerByHost 自己的窗口给。
    val resolved = CarrierHold.hold("resolvePeerByHost", CarrierHold.RESOLVE_MS) {
        runtime.resolvePeerByHost(to, port)
    }?.profile?.nodeId
    if (resolved == null) {
        resolveFailures[key] = now + ControlPlane.RESOLVE_FAILURE_TTL_MS
        if (resolveFailures.size > ControlPlane.RESOLVE_FAILURE_MAX) {
            resolveFailures.entries.removeIf { it.value <= now }
        }
    } else {
        resolveFailures.remove(key)
    }
    return resolved
}

/**
 * 把 `to` 解析成 nodeId，失败时**自己回包**并返回 null。
 *
 * 抽出来是因为 `/v1/send` 与 `/v1/send-file` 的解析规则必须逐字相同 —— 包括那句
 * "给了端口就必须匹配上"的注释所说的语义：复制一份迟早会漂。
 */

private fun ControlPlane.resolveNodeIdOrRespond(exchange: HttpExchange, to: String, port: Int?): String? {
    // 先当 nodeId 查，查不到再当 IP 去握手 —— 调用方两种都该能用。
    val target =
        if (port != null) "$to:$port" else "$to（未指定端口，按默认 ${Protocol.DEFAULT_UDP_PORT}）"
    val nodeId = resolveTargetNodeId(to, port)
    if (nodeId == null) {
        respond(
            exchange,
            404,
            errorJson(
                "peer-unreachable",
                "3 秒内无法与 $target 建立握手（对方设备是否已启动？IP 与端口是否正确？）",
            ),
        )
    }
    return nodeId
}
