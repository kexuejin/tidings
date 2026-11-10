// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.qr.QrEncoder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress

/**
 * 自述与发现（help / healthz / pairing-qr / peers）。
 *
 * 控制面对外的「我是谁、我好不好、怎么连我」。四条路由都不读写业务台账，只读运行时
 * 邻居与本机配置 —— 所以它们在处理器池被写路由占满时也必须答得出来（见 `start()` 里
 * 关于线程池那段说明）。
 *
 * `/healthz` 的 CORS 只对回环来源开，且只在 GET/OPTIONS 上；这条豁免的来由与边界写在
 * `ControlPlane.allowLoopbackCors` 的 KDoc 里，放宽之前先读那一段。
 */

internal fun ControlPlane.healthzJson(): JsonObject {
    val peers = runtime.peerList()
    return buildJsonObject {
        put("ok", true)
        put("nodeId", runtime.nodeId)
        put("nick", runtime.profile.nick)
        put("platform", runtime.profile.platform)
        put("udpPort", runtime.boundPort)
        put("httpPort", boundPort)
        put("peers", peers.size)
        put("online", peers.count { it.online })
        put("pending", runtime.pendingCount())
        // **待批准的 agent 授权申请数。**
        //
        // 与 `pending`（离线补发队列）分开：那是「还没送达的消息」，这是「有人等我批准」。
        // 放进 healthz 是因为它就是宿主的健康状态之一 —— 一台机器攒着几条没人批的申请，
        // 与一条消息卡在队列里一样，都是「有东西没走完」。
        //
        // 顺带让 /healthz 成为 ③（托盘徽标）的**可观测证据**：徽标读的就是这个数。
        // 没有它，验证「徽标亮没亮」只能靠肉眼看屏幕截图 —— 而截屏本身有系统权限门槛。
        put("pendingGrants", grants.snapshot().requests.size)
        put("uptimeMs", System.currentTimeMillis() - startedAt)
    }
}

/**
 * `GET /v1/pairing-qr` —— 公网中继配对二维码。
 *
 * ⚠️ **这条路由会返回令牌本身**（`payload` 里带着 `token`），与本机其它路由都不一样 ——
 * 其它路由一个都不返回秘密。所以它必须守两条线，改动这两处前先读
 * 配对流程里的一条硬规则：
 *
 *   1. **只绑回环**（`start()` 里 `HttpServer.create(InetSocketAddress(ControlPlane.LOOPBACK, …))`）。
 *      将来任何人给控制面加"允许非回环访问"之类改动，必须把这条路由单独排除 ——
 *      否则等于把令牌挂到公网上。
 *   2. 结果**不进日志、不进任何截图产物**。
 *
 * 编码复用 `:core` 的 [QrEncoder]（字节模式、等级 M、版本 1–10，容量 213 字节），
 * 不引第三方包。超限由 [QrEncoder.encode] 抛 [IllegalArgumentException]（**不截断**）——
 * 截断出来的是一张能扫、内容却是错的码，那比不给更糟。
 */
internal fun ControlPlane.pairingQr(exchange: HttpExchange) {
    if (publicUrl.isBlank()) {
        respond(
            exchange,
            400,
            errorJson(
                "no-public-url",
                "没配公网入口，印不出配对码。启动时加 --public-url https://<域名>/relay（公网暴露的控制面地址）。",
            ),
        )
        return
    }
    // 载荷照 QrPairing.remotePairingPayload 的形状（手机侧 parseQr 认的就是这几个键）。
    // token 在这里编进去：扫码是为了让手机一次拿到 URL + token，不再手抄 ——
    // 代价是这张码本身就是一把钥匙，界面必须把这一点说清楚。
    val payload = buildJsonObject {
        put("type", "relay")
        put("url", publicUrl)
        put("token", token)
        put("nodeId", runtime.nodeId)
        put("nick", runtime.profile.nick)
    }
    val text = payload.toString()
    val code = try {
        QrEncoder.encode(text)
    } catch (t: IllegalArgumentException) {
        // 容量不够：把原文交回去让面板走「载荷原文 + 手动填」，绝不降级画半截码。
        respond(
            exchange,
            200,
            buildJsonObject {
                put("tooLong", true)
                put("payload", text)
                put("bytes", text.encodeToByteArray().size)
                put("capacity", 213)
            },
        )
        return
    }
    // 行优先喂给前端：QrCode[x, y] = modules[y * size + x]（QrEncoder.QrCode 的注释）。
    // 顺序搞反会画出一张能看、但谁都扫不出来的码。
    val modules = buildList {
        for (y in 0 until code.size) {
            for (x in 0 until code.size) add(code[x, y])
        }
    }
    respond(
        exchange,
        200,
        buildJsonObject {
            put("payload", text)
            put("version", code.version)
            put("size", code.size)
            put("mask", code.mask)
            putJsonArray("modules") { modules.forEach { add(JsonPrimitive(it)) } }
            put("needsToken", token.isNotEmpty())
        },
    )
}

internal fun ControlPlane.peersJson(): JsonObject = buildJsonObject {
    // 长连接快照（只读，task-26 ③）：让"下行走的哪条路"一眼可见，而不是靠挡网络去猜。
    val links = runtime.linkSnapshot()
    putJsonArray("peers") {
        runtime.peerList().forEach { peer ->
            addJsonObject {
                put("nodeId", peer.profile.nodeId)
                put("nick", peer.profile.nick)
                put("ip", peer.ip)
                put("udpPort", peer.udpPort)
                put("tcpPort", peer.profile.tcpPort)
                put("platform", peer.profile.platform)
                put("online", peer.online)
                put("lastSeen", peer.lastSeen)
                put("profileRev", peer.profile.profileRev)
                // 这条 peer 现在是否有一条活着的长连接；有的话承载是 tls 还是明文。
                // null = 没有长连接（下行只能走 UDP/TCP 原路径）。
                val carrier = links.carrierOf(peer.profile.nodeId)
                put("linkConnected", carrier != null)
                put("linkCarrier", carrier)
                // 拨号诊断（只读，task-32 ③）：attempts=0 + skipReason = "没拨"（策略跳过）；
                // attempts>0 + failures>0 + lastError = "拨了连不上"（并给出最后拨的 host:port）。
                val dial = links.dialOf(peer.profile.nodeId)
                // 主机侧拒绝原因（只读，task-36 ①）：对端连进来了但我们为什么拒。
                // 与手机 diag 的 lastError 同口径 —— 两边对照就能定位是网络层还是策略层。
                put("linkDenyReason", links.denialOf(peer.profile.nodeId))
                // 连接结束原因 + 本端主动关闭次数（只读，task-40 ①）：
                // 真机上"谁先关的"就靠这两个字段判 —— dead-timeout/peer-closed 是对方或超时，
                // replaced/recheck/stopped 是我们自己关的。
                put("linkCloseReason", links.closeOf(peer.profile.nodeId))
                put("linkCloseCount", links.closeCountOf(peer.profile.nodeId))
                // 主动关闭时的**判据两元组**（task-40）：一眼看出是不是"明文 + 公网地址"这条。
                put("linkCloseDetail", links.closeDetailOf(peer.profile.nodeId))
                // pin 来源（task-38 底线③）：pairing = 配对带来（可信）；tofu = 降级路径（有竞态）。
                put("linkPinSource", links.pinSourceOf(peer.profile.nodeId))
                put("linkDialAttempts", dial?.attempts ?: 0)
                put("linkDialFailures", dial?.failures ?: 0)
                put("linkDialLastTarget", dial?.lastTarget)
                put("linkLastError", dial?.lastError)
                put("linkSkipReason", dial?.skipReason)
                // **可达性判据（goal 第 5 步的共存可见性）**：
                // 本机只跑 luciola 发现（legacy 已移除），所以「发现不到」只剩一种解释：
                // 对端还在老传输上。这一格把原因说出来，让运维/用户能一眼分清
                // "设备关机"与"它还在老传输上、需要我们帮它升级"。
                put(
                    "reachability",
                    when {
                        // **陈旧档案不许当成老端。** 真机实测过这个误报：
                        // 一条从磁盘恢复的旧 peer 记录（profileRev=0、caps 全空）
                        // 被这条判据打成 legacy-peer-invisible —— 而那个对端当时
                        // 正走加密信道传 50MB。caps 为空 = **不知道**，不是"没有"。
                        peer.profile.caps.isEmpty() -> "unknown"
                        !ControlPlane.posesLuciolaCaps(peer.profile) -> "legacy-peer-invisible"
                        else -> "ok"
                    },
                )
                // 恒 off —— 属性本身已经删了（本机不存在别的形态），但诊断字段保留，
                // 免得读控制面的人以为它「以前是 on、现在改了」。
                put("legacyDiscovery", "off")
            }
        }
    }
    // 本端当前长连接总数 + 对端清单（主机侧看这里 = "有没有人连上我的下行通道"）。
    putJsonObject("links") {
        put("count", links.total)
        putJsonArray("peers") { links.carriers.keys.forEach { add(JsonPrimitive(it)) } }
    }
}

internal fun ControlPlane.helpJson(): JsonObject = buildJsonObject {
    put("name", "relay-agentNode")
    put("nodeId", runtime.nodeId)
    putJsonArray("routes") {
        add(JsonPrimitive("GET  /healthz"))
        add(JsonPrimitive("GET  /v1/peers"))
        add(JsonPrimitive("POST /v1/send   {\"to\":\"<IP 或 nodeId>\",\"text\":\"…\",\"port\":17878}"))
        add(
            JsonPrimitive(
                "POST /v1/send-file   {\"to\":\"…\",\"path\":\"/tmp/a.png\",\"name\":\"a.png\"," +
                    "\"purpose\":\"share\",\"groupId\":\"g-1\",\"groupRev\":7," +
                    "\"tableText\":\"名称\\t数量\"}   " +
                    "（groupId/groupRev 成对 = 群聊媒体；tableText = 表格图片的文字视图）",
            ),
        )
        add(
            JsonPrimitive(
                "POST /v1/nudge   {\"to\":\"…\",\"port\":17878}   " +
                    "（戳一碰；按协议只回 ACK 或 unreachable，不进离线补发队列）",
            ),
        )
        add(
            JsonPrimitive(
                "POST /v1/agent/session   {\"to\":\"…\",\"sessionId\":\"s-1\",\"alias\":\"…\"," +
                    "\"agentKind\":\"codex\",\"state\":\"busy\",\"title\":\"…\"}",
            ),
        )
        add(
            JsonPrimitive(
                "POST /v1/agent/event   {\"to\":\"…\",\"sessionId\":\"s-1\",\"cursor\":42," +
                    "\"eventType\":\"tool.started\",\"summary\":\"…\"}",
            ),
        )
        add(JsonPrimitive("GET  /v1/inbox?since=<epochMillis>&limit=<n>"))
        add(
            JsonPrimitive(
                "GET  /v1/agent/sessions   本节点 agent 会话台账（可加 &sessionId=<id> 取该会话事件明细）" +
                    "；出站不写 messageStore、也不进 /v1/inbox，故单列一条只读路由",
            ),
        )
        add(
            JsonPrimitive(
                "POST /v1/agent/workspace   {\"to\":\"…\",\"wsId\":\"ws-…\",\"wsTitle\":\"…\",\"wsPath\":\"…\"}" +
                    "   工作区宣告（增补 D3，可靠投递；台账与送达无关）",
            ),
        )
        add(JsonPrimitive("GET  /v1/agent/workspaces   工作区台账（可加 &nodeId=<id>；面板「第三面」）"))
        add(JsonPrimitive("GET  /v1/agent/grants   授权策略与白名单 + 待办申请（增补 D7，桌面端授权页）"))
        add(
            JsonPrimitive(
                "PUT  /v1/agent/grants   {\"policy\":\"auto|byDevice|request\"} 或 " +
                    "{\"grant\":{\"nodeId\":\"…\",\"scope\":\"all|agent\",\"agentKey\":\"…\"}} 或 " +
                    "{\"revoke\":…} 或 {\"grantReply\":…}（决定回给设备）",
            ),
        )
        add(JsonPrimitive("GET  /v1/pairing-qr   公网中继配对二维码（响应含令牌，只走回环）"))
        add(
            JsonPrimitive(
                "POST /v1/plugin/register   {\"manifest\":{…§2.2…},\"httpPort\":<1..65535>}   " +
                    "插件入驻（强制 TENON_PLUGIN_TOKEN；回环 IPC 基座，M2 宿主网关）",
            ),
        )
        add(JsonPrimitive("POST /v1/plugin/unregister   {\"pluginId\":\"…\"}   插件注销（同 token 门）"))
        add(JsonPrimitive("GET  /v1/plugins   已注册插件台账（状态/端口/能力声明/healthz 探测结果）"))
        if (devRoutesEnabled) {
            add(JsonPrimitive("POST /v1/dev/pair      仅开发：向已扫码的对端发起配对自证"))
        }
        add(JsonPrimitive("GET  /v1/messages  （/v1/inbox 的同义路由）"))
        add(
            JsonPrimitive(
                "GET  /v1/host/directories   host-local project directory snapshot " +
                    "(directories + groupPointers + whitelist)",
            ),
        )
        add(
            JsonPrimitive(
                "POST /v1/host/directories   {\"path\":\"…\",\"displayName\":\"…\",\"gitRemote\":\"…\"}   " +
                    "register a project directory (idempotent; auto-allowlisted read-write)",
            ),
        )
        add(
            JsonPrimitive(
                "POST /v1/host/group-directory   {\"groupId\":\"…\",\"dirId\":\"…\"}   " +
                    "set a group's project pointer (dirId omitted/null clears it)",
            ),
        )
        add(
            JsonPrimitive(
                "POST /v1/host/whitelist   {\"dirId\":\"…\",\"allowed\":true,\"readOnly\":false}   " +
                    "allow / revoke a directory for agents (default deny)",
            ),
        )
    }
    putJsonArray("examples") {
        add(JsonPrimitive("curl -s http://127.0.0.1:$boundPort/healthz"))
        add(
            JsonPrimitive(
                "curl -s -X POST http://127.0.0.1:$boundPort/v1/send " +
                    "-d '{\"to\":\"192.168.1.5\",\"text\":\"hi\"}'",
            ),
        )
        add(
            JsonPrimitive(
                "curl -s -X POST http://127.0.0.1:$boundPort/v1/send-file " +
                    "-d '{\"to\":\"192.168.1.5\",\"path\":\"/tmp/a.png\"}'",
            ),
        )
        add(
            JsonPrimitive(
                "curl -s -X POST http://127.0.0.1:$boundPort/v1/nudge " +
                    "-d '{\"to\":\"192.168.1.5\"}'",
            ),
        )
        add(
            JsonPrimitive(
                "curl -s -X POST http://127.0.0.1:$boundPort/v1/agent/event " +
                    "-d '{\"to\":\"192.168.1.5\",\"sessionId\":\"s-1\",\"cursor\":1," +
                    "\"eventType\":\"session.started\"}'",
            ),
        )
    }
}
