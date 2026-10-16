// SPDX-License-Identifier: GPL-3.0-only

package app.relay.agent

import app.relay.core.protocol.AgentGrantPolicies
import app.relay.core.protocol.AgentGrantScopes
import app.relay.core.protocol.AgentGrantStates
import app.relay.core.protocol.AgentSessionStates
import app.relay.core.protocol.Codec
import app.relay.core.protocol.DecodeResult
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Protocol
import app.relay.core.protocol.makeEnvelope
import app.relay.core.session.NodeRuntime
import app.relay.core.transport.LocalFile
import app.relay.core.transport.SendOutcome
import app.relay.core.qr.QrEncoder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * agentNode 的 localhost HTTP 控制面。
 *
 * 存在的理由是**隔离**：宿主 agent 插件 / Codex / Claude 只发 HTTP，解耦底层点对点通信的
 * UDP/TCP 协议、不碰 nodeId、不碰握手与 ACK。协议的一切复杂度留在 [NodeRuntime] 里，
 * 于是给 agent 的工具描述可以简单到"POST 一个 to 和一个 text"。
 *
 * 安全模型（默认按最保守解释）：只绑 `127.0.0.1`，不监听任何外部网卡。默认**不要求**
 * token —— 回环上的进程本来就与你有同等权限，加一个默认 token 只会变成"必须手写配置
 * 文件"（用户明确拒绝过这种形态）。一旦 `--token` 或 `RELAY_AGENT_TOKEN` 提供了值，
 * 则每个请求都必须带 `Authorization: Bearer <token>` 或 `X-Tidings-Token: <token>`。
 *
 * 路由：
 *  - `GET  /`                 用法说明（给人看的）
 *  - `GET  /healthz`          存活 + nodeId + 端口 + 节点数 + 待补发数
 *  - `GET  /v1/peers`         节点表快照
 *  - `POST /v1/send`          `{"to":"<IP 或 nodeId>","text":"…"}`
 *  - `POST /v1/send-file`     `{"to":"…","path":"<本机绝对路径>","name":"…","purpose":"share",`
 *    `"groupId":"…","groupRev":7,"tableText":"…"}`（后三个可选；群引用成对出现 = 群聊媒体投递，
 *    `tableText` = 表格图片的文字视图）
 *  - `POST /v1/nudge`         `{"to":"…"}`（戳一碰：只回 ACK 或 unreachable，**不排队**）
 *  - `POST /v1/agent/session` `{"to":"…","sessionId":"…","alias":"…","state":"busy"}`（PPE-1 §6.1）
 *  - `POST /v1/agent/event`   `{"to":"…","sessionId":"…","cursor":42,"eventType":"tool.started"}`（§6.2）
 *  - `GET  /v1/inbox?since=`  跨会话增量拉取（`/v1/messages` 为同义路由）
 *
 * 两条 agent 路由受 §4 约束：**对方没声明 `agn1` 就不发**，返回 409 并建议改用 `/v1/send`。
 *
 * @param httpPort 监听端口；传 0 表示由系统分配，实际端口读 [boundPort]。
 * @param token    空字符串 = 回环放行。
 */
class ControlPlane(
    private val runtime: NodeRuntime,
    private val httpPort: Int,
    private val token: String,
    private val startedAt: Long = System.currentTimeMillis(),
    /**
     * 公网入口（隧道暴露的控制面地址），空串 = 没配 ⇒ 配对二维码不生成。
     *
     * 只影响 `GET /v1/pairing-qr` 一条路由。
     */
    private val publicUrl: String = "",
    /**
     * agent 会话台账。控制面在这里记下**发出**的 agent 帧，`GET /v1/agent/sessions`
     * 从它读 —— 因为 agent 帧既不进 `messageStore`（出站不写）也不该混进 `/v1/inbox`（噪声），
     * 没有这个台账，面板就问不到「本机有哪些 agent 会话」。见 [AgentSessionRegistry] 的 KDoc。
     *
     * 由 `Main` 建好传进来（而不是在这里 `= AgentSessionRegistry()`），因为**入站**的
     * agent 帧是 `Main` 里那条 `runtime.inbound` 订阅写的：两边必须是同一个实例，
     * 否则收发的会话各记一份，面板只能看到一半。
     */
    private val sessions: AgentSessionRegistry,
    /**
     * 工作区台账（PPE-1 增补 D3）。与 [sessions] 同一条「两边必须同一实例」的纪律：
     * 出站由本控制面写，入站由宿主进程订阅写。
     */
    private val workspaces: AgentWorkspaceRegistry = AgentWorkspaceRegistry(),
    /**
     * 授权策略（PPE-1 增补 D7）。**执行点在这里**：投递前按策略过滤，
     * 「没授权」才等于「没数据」（父稿 §3.3）。决定在桌面端（面板经
     * `GET/PUT /v1/agent/grants` 写），这里只拦。
     */
    private val grants: AgentGrantStore = AgentGrantStore(defaultGrantFile()),
    /** PPE-1 增补 D8：设备发来的会话分页请求，留给宿主答（见 AgentSessionPageStore 的 KDoc）。 */
    private val sessionPages: AgentSessionPageStore = AgentSessionPageStore(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private var server: HttpServer? = null

    /** 实际监听端口。`httpPort` 传 0 时在 [start] 之后才有效。 */
    var boundPort: Int = httpPort
        private set

    fun start() {
        if (server != null) return
        val s = HttpServer.create(InetSocketAddress(LOOPBACK, httpPort), 16)
        s.executor = Executors.newFixedThreadPool(4)
        s.createContext("/") { exchange -> handle(exchange) }
        s.start()
        server = s
        boundPort = s.address.port
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /**
     * 订阅本运行时的入站流，把三类 agent 帧记进三张台账（会话 / 工作区 / 授权待办）。
     *
     * 存在的理由与 `recordInbound` 同一条：解析规则只有这一份。`Main`（agentNode 进程）、
     * 桌面端内嵌控制面（`DesktopExtension`）与本类的测试都调它 —— 谁自己手写一段
     * `runtime.inbound.collect { ... }`，谁就会在下一帧类型加入时漏接一个分支
     * （症状都是"宣告发了、面板看不到"，且只在真机上暴露）。
     *
     * 授权申请的回包（`agent-grant-list`）也在这里：设备问一次，宿主答一次当前清单，
     * 让手机端四档状态里「已授权」与「未申请」可区分（父稿 §3.3：沉默 ≠ 拒绝）。
     * 回包失败不影响记账 —— 台账是本机事实，对方下次申请还会拿到清单。
     */
    fun observeInbound(scope: CoroutineScope): Job = scope.launch {
            runtime.inbound.collect { envelope ->
                sessions.recordInbound(envelope)
                workspaces.recordInboundWorkspace(envelope)
                // 会话历史在宿主侧，节点答不了：只排队，等宿主来读（见该 store 的 KDoc）。
                sessionPages.recordInbound(envelope)
                if (grants.recordInboundGrant(envelope)) {
                    val snap = grants.snapshot()
                    // 能发来 grant-request 就说明对方认识 grant 帧（cap 门控在它的发送侧做），
                    // 这里直接回当前清单：沉默 ≠ 拒绝，四档状态必须可区分（父稿 §3.3）。
                    // 一条都没授权过时回一条 total=0 的清单 —— 对方据此显示「未申请/待批」
                    // 而不是空列表（空列表分不清"没权限"与"没数据"）。
                    runCatching {
                        val mine = snap.grants.filter { it.nodeId == envelope.from }
                        if (mine.isEmpty()) {
                            runtime.sendAgentGrantList(envelope.from, AgentGrantScopes.ALL, null, 0)
                        } else {
                            mine.forEach { g ->
                                runtime.sendAgentGrantList(
                                    // total 是**给这台设备看的**清单长度（对方据此显示「共 N 条」）。
                                    // 传整份台账的 size 会把别台设备的授权算进来 ——
                                    // 同胞 /v1/agent/sessions 的 total 用的就是本设备的行数。
                                    envelope.from, g.scope, g.agentKey, mine.size.toLong(),
                                )
                            }
                        }
                    }
                }
            }
        }

    // ---------------------------------------------------------------- routing

    private fun handle(exchange: HttpExchange) {
        try {
            if (!authorized(exchange)) {
                respond(exchange, 401, errorJson("unauthorized", "缺少或错误的 token"))
                return
            }
            val path = exchange.requestURI.path.trimEnd('/').ifEmpty { "/" }
            val method = exchange.requestMethod.uppercase()
            when {
                path == "/" && method == "GET" -> respond(exchange, 200, helpJson())
                path == "/healthz" && method == "GET" -> respond(exchange, 200, healthzJson())
                path == "/v1/pairing-qr" && method == "GET" -> pairingQr(exchange)
                path == "/v1/peers" && method == "GET" -> respond(exchange, 200, peersJson())
                path == "/v1/send" && method == "POST" -> send(exchange)
                path == "/v1/send-file" && method == "POST" -> sendFile(exchange)
                path == "/v1/nudge" && method == "POST" -> nudge(exchange)
                path == "/v1/agent/session" && method == "POST" -> agentSession(exchange)
                path == "/v1/agent/event" && method == "POST" -> agentEvent(exchange)
                path == "/v1/agent/queue" && method == "POST" -> agentQueue(exchange)
                path == "/v1/agent/run" && method == "POST" -> agentRun(exchange)
                path == "/v1/agent/approval" && method == "POST" -> agentApproval(exchange)
                path == "/v1/agent/cancel" && method == "POST" -> agentCancel(exchange)
                path == "/v1/agent/artifact" && method == "POST" -> agentArtifact(exchange)
                path == "/v1/agent/ack" && method == "POST" -> agentAck(exchange)
                path == "/v1/agent/sessions" && method == "GET" -> agentSessions(exchange)
                path == "/v1/agent/workspace" && method == "POST" -> agentWorkspace(exchange)
                path == "/v1/agent/workspaces" && method == "GET" -> agentWorkspaces(exchange)
                // 宿主读待答的会话分页请求，答完再销掉（PPE-1 增补 D8）。
                path == "/v1/agent/session-pages" && method == "GET" -> agentSessionPages(exchange)
                path == "/v1/agent/session-pages/resolve" && method == "POST" -> resolveAgentSessionPage(exchange)
                path == "/v1/agent/grants" && method == "GET" -> readGrants(exchange)
                path == "/v1/agent/grants" && method == "PUT" -> writeGrants(exchange)
                (path == "/v1/inbox" || path == "/v1/messages") && method == "GET" -> inbox(exchange)
                else -> respond(
                    exchange,
                    404,
                    errorJson("not-found", "未知路由 $method $path（GET / 看用法）"),
                )
            }
        } catch (t: Throwable) {
            // 已经回过包再抛（例如客户端提前断开）不应把整个 handler 线程带走。
            runCatching {
                respond(exchange, 500, errorJson("internal", t.message ?: t.toString()))
            }
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        if (token.isEmpty()) return true
        val bearer = exchange.requestHeaders.getFirst("Authorization")
        if (bearer != null && bearer.removePrefix("Bearer ").trim() == token) return true
        return exchange.requestHeaders.getFirst("X-Tidings-Token") == token
    }

    // --------------------------------------------------------------- handlers

    private fun send(exchange: HttpExchange) {
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

        val outcome = try {
            runBlocking { runtime.sendText(nodeId, text) }
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
    private fun nudge(exchange: HttpExchange) {
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
            runBlocking { runtime.sendNudge(nodeId) }
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
    private fun sendFile(exchange: HttpExchange) {
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

        val transferId = try {
            runBlocking {
                runtime.offerFiles(
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
                put("status", "offered")
                put("to", nodeId)
                put("toHost", to)
                put("transferId", transferId)
                put("name", name)
                put("size", file.length())
                put("note", "只是报价：对方接受后才开始传，进度与结果在对方界面上")
            },
        )
    }

    /**
     * 把 `to` 解析成 nodeId，失败时**自己回包**并返回 null。
     *
     * 抽出来是因为 `/v1/send` 与 `/v1/send-file` 的解析规则必须逐字相同 —— 包括那句
     * "给了端口就必须匹配上"的注释所说的语义：复制一份迟早会漂。
     */
    private fun resolveNodeIdOrRespond(exchange: HttpExchange, to: String, port: Int?): String? {
        // 先当 nodeId 查，查不到再当 IP 去握手 —— 调用方两种都该能用。
        val target =
            if (port != null) "$to:$port" else "$to（未指定端口，按默认 ${Protocol.DEFAULT_UDP_PORT}）"
        val nodeId = runtime.getPeer(to)?.profile?.nodeId
            ?: runBlocking { runtime.resolvePeerByHost(to, port) }?.profile?.nodeId
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

    // ---------------------------------------------------------------- agent 会话帧
    // PPE-1 §6.1 `agent-session` / §6.2 `agent-event` 的发送入口。
    //
    // 与 `/v1/send` 最大的区别是 §4 的那道门：`agent-*` 帧**只有在对方声明了 `agn1`
    // 时才允许发**。对方看不懂时我们返回 409 并给出降级路径，而不是把帧丢进重传队列 ——
    // 那样本端会一直重传到入队，表现成"对方离线"，是个从日志里查不出来的假故障。

    /** `POST /v1/agent/session` —— 宣告一个会话，或它的状态变更。 */
    private fun agentSession(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: return
        val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_SESSION) ?: return
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sessionId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 sessionId（§6.1 的必填字段）"))
            return
        }
        val alias = obj["alias"]?.jsonPrimitive?.contentOrNull
        val agentKind = obj["agentKind"]?.jsonPrimitive?.contentOrNull
        val state = obj["state"]?.jsonPrimitive?.contentOrNull
        // 出站收紧、收端仍然开放：`Codec.validateAgentSession` 的 KDoc 解释了收端为什么必须
        // 宽容（拒收整帧会让对端一直重传到队列剪枝）。控制面是**发送方**，`state` 是我们自己
        // 写的字符串，收紧的代价只是一个可操作的 400 —— 而不是让一个拼错/超长的值走到自检
        // 那一步，换回一句「这一帧我们自己都拒收：…」。取值集复用 `AgentSessionStates.ALL`，
        // 不另造第二套真源（`agentRun` 的 `RUN_STATES` 是同一写法）。
        if (state != null && state !in AgentSessionStates.ALL) {
            respond(
                exchange,
                400,
                errorJson(
                    "bad-request",
                    "state 只能是 ${AgentSessionStates.ALL.joinToString(" / ")}（§6.1）；收到「$state」",
                ),
            )
            return
        }
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
        val startedAt = numberOrNull(obj["startedAt"])
        // PPE-1 增补 D1/D6：控制面是**转发方**，四个新键原样透传。cap 门控
        // （只向声明 aws1 的对端附带新键）住在投递层 NodeOutbound.sendAgentSession，
        // 不在这里；这里只把形状交给 codec 自检。
        val agentId = obj["agentId"]?.jsonPrimitive?.contentOrNull
        val wsId = obj["wsId"]?.jsonPrimitive?.contentOrNull
        val wsTitle = obj["wsTitle"]?.jsonPrimitive?.contentOrNull
        val wsPath = obj["wsPath"]?.jsonPrimitive?.contentOrNull
        // 会话的**活动时刻**（epoch 毫秒）= max(createdAt, lastPromptAt)，DSH 自己的会话
        // 列表就是用它排序的。它属于**会话**、不属于工作区身份，所以和 startedAt 同一档
        // （不像那四个 D1/D6 键那样受 aws1 门控）。台账**不落**它：AgentSessionRegistry 的
        // updatedAt 是"本节点记账时刻"、是淘汰的唯一依据，混进来会让淘汰判错。
        val updatedAt = numberOrNull(obj["updatedAt"])
        // 可见性两键（PPE-1 增补）：`origin` 缺省 undefined、`blank` 三态。控制面是转发方，
        // 不做取值收紧 —— 取值不封闭，由 codec 按形状校验（见 AgentSessionPayload 的 KDoc）。
        val origin = obj["origin"]?.jsonPrimitive?.contentOrNull
        val blank = boolOrNull(obj["blank"])
        deliverAgentFrame(
            exchange = exchange,
            nodeId = nodeId,
            type = MsgTypes.AGENT_SESSION,
            payload = Codec.agentSessionPayload(
                sessionId, alias, agentKind, state, title, startedAt,
                agentId, wsId, wsTitle, wsPath, updatedAt, origin, blank,
            ),
            send = {
                runtime.sendAgentSession(
                    nodeId, sessionId, alias, agentKind, state, title, startedAt,
                    agentId, wsId, wsTitle, wsPath, updatedAt, origin, blank,
                )
            },
            // 只在这一帧真的投出去之后才记账：`deliverAgentFrame` 的 `onSent` 是成功回调，
            // 失败（自检拒收 / 502）时台账里不该留下一个从没上过线的会话。
            onSent = {
                sessions.recordSession(
                    nodeId = nodeId,
                    sessionId = sessionId,
                    alias = alias,
                    agentKind = agentKind,
                    state = state,
                    title = title,
                    startedAt = startedAt,
                    agentId = agentId,
                    wsId = wsId,
                    wsTitle = wsTitle,
                    wsPath = wsPath,
                    outbound = true,
                )
            },
        )
    }

    /**
     * `POST /v1/agent/workspace` —— 宣告**一个**工作区（PPE-1 增补 D3，cap `aws1`）。
     *
     * 与 session/event 两条路由的差别：这帧**尽力而为**（不在 RELIABLE），所以回执里没有
     * sent/queued 之分 —— 返回 `delivered`（已交给传输层）并如实注明"无送达回执"。
     * 台账（出站那一半）在这里记；入站那一半由宿主进程的订阅走 `recordInboundWorkspace`，
     * 两边共用同一实例（与 sessions 台账同一条纪律）。
     */
    private fun agentWorkspace(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: return
        val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_WORKSPACE) ?: return
        val wsId = obj["wsId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (wsId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 wsId（增补 D3 的必填字段）"))
            return
        }
        val wsTitle = obj["wsTitle"]?.jsonPrimitive?.contentOrNull
        val wsPath = obj["wsPath"]?.jsonPrimitive?.contentOrNull
        val agentKind = obj["agentKind"]?.jsonPrimitive?.contentOrNull
        val agentId = obj["agentId"]?.jsonPrimitive?.contentOrNull
        // 出站收紧、收端仍然开放（与 agentSession 的 state 同一纪律）：`online` 写成字符串
        // 时**必须 400** —— 第一版用 takeIf 静默丢键，调用方传了 `"online":"true"` 却收到
        // 一份没有 online 的帧，那是"把用户要的东西悄悄改成别的东西"。
        val online: Boolean? = if (!obj.containsKey("online")) null else {
            val p = obj["online"] as? JsonPrimitive
            if (p == null || p.isString || (p.content != "true" && p.content != "false")) {
                respond(
                    exchange, 400,
                    errorJson("bad-request", "online 必须是布尔字面量 true / false（宿主自报的事实，形状错了就是假数据）"),
                )
                return
            }
            p.content == "true"
        }
        val sessionCount: Long? = if (!obj.containsKey("sessionCount")) null else numberOrNull(obj["sessionCount"])
        if (obj.containsKey("sessionCount") && sessionCount == null) {
            respond(exchange, 400, errorJson("bad-request", "sessionCount 必须是非负整数"))
            return
        }
        if (sessionCount != null && sessionCount < 0) {
            respond(exchange, 400, errorJson("bad-request", "sessionCount 必须是非负整数；收到 $sessionCount"))
            return
        }
        // 注册表持久序（`AgentWorkspacePayload.order`）。与 sessionCount 同款：错了就是假数据 400，
        // 而不是静默丢键 —— 丢了手机就只能退回按到达序排，重启一次顺序就乱。
        val order: Long? = if (!obj.containsKey("order")) null else numberOrNull(obj["order"])
        if (obj.containsKey("order") && order == null) {
            respond(exchange, 400, errorJson("bad-request", "order 必须是非负整数"))
            return
        }
        if (order != null && order < 0) {
            respond(exchange, 400, errorJson("bad-request", "order 必须是非负整数；收到 $order"))
            return
        }
        val at = numberOrNull(obj["at"])
        // 归组键 = agentId ?? agentKind（与会话侧同判据，见 AgentInbound 的注释）。
        val agentKey = agentId ?: agentKind

        val frame = Codec.encode(
            makeEnvelope(
                MsgTypes.AGENT_WORKSPACE, runtime.nodeId,
                Codec.agentWorkspacePayload(wsId, wsTitle, wsPath, agentId, agentKind, online, sessionCount, at, order),
            ),
        )
        val back = Codec.decodeTcp(frame)
        if (back is DecodeResult.Rejected) {
            respond(exchange, 400, errorJson("bad-request", "这一帧我们自己都拒收：" + back.reason))
            return
        }
        // cap 门控（aws1）已由 resolveAgentTarget 按帧类型查过（没声明 ⇒ 409 提前拒绝）。
        val acked = try {
            runBlocking {
                runtime.sendAgentWorkspace(
                    nodeId, wsId, wsTitle, wsPath, agentId, agentKind, online, sessionCount, at, order,
                )
            }
        } catch (t: Throwable) {
            respond(exchange, 502, errorJson("send-failed", t.message ?: "发送失败"))
            return
        }
        workspaces.record(
            nodeId = runtime.nodeId,
            agentKey = agentKey,
            wsId = wsId,
            wsTitle = wsTitle,
            wsPath = wsPath,
            agentKind = agentKind,
            online = online,
            sessionCount = sessionCount,
            outbound = true,
            at = at ?: System.currentTimeMillis(),
        )
        respond(
            exchange,
            200,
            buildJsonObject {
                // 可靠但不入队：回执只有 sent / not-acked 两档，**没有 queued** ——
                // 状态快照不补发（下一份快照会盖掉它），也不该把在线对端拖进离线队列。
                put("status", if (acked) "sent" else "not-acked")
                put("to", nodeId)
                put("type", MsgTypes.AGENT_WORKSPACE)
                put("note", "台账已记本宿主的工作区（宣告与送达无关）；" +
                    if (acked) "对方已 ACK" else "对方没 ACK（多半是不认识这帧的旧版本）；不入队、下次宣告自然覆盖")
            },
        )
    }

    /** `GET /v1/agent/workspaces` —— 本宿主的工作区清单（面板「第三面」的读数）。 */
    private fun agentWorkspaces(exchange: HttpExchange) {
        val query = parseQuery(exchange.requestURI.rawQuery)
        val wantedNode = query["nodeId"]?.trim().orEmpty()
        val rows = workspaces.mergeSessionCounts(sessions)
            .filter { wantedNode.isEmpty() || it.nodeId == wantedNode }
        respond(
            exchange,
            200,
            buildJsonObject {
                putJsonArray("workspaces") {
                    rows.forEach { w ->
                        addJsonObject {
                            put("nodeId", w.nodeId)
                            put("agentKey", w.agentKey)
                            put("wsId", w.wsId)
                            put("wsTitle", w.wsTitle)
                            put("wsPath", w.wsPath)
                            put("agentKind", w.agentKind)
                            put("online", w.online)
                            put("sessionCount", w.sessionCount)
                            put("updatedAt", w.updatedAt)
                        }
                    }
                }
                put("total", rows.size)
            },
        )
    }

    /**
     * 待回答的会话分页请求（宿主读）。
     *
     * 只读；宿主读完用 `POST /v1/agent/session` 把那一页会话逐条回填，再调
     * `/v1/agent/session-pages/resolve` 销掉这条。销不掉也不会重复答 —— 队列里同一
     * `(nodeId, wsId)` 只有最新一条（见 AgentSessionPageStore.record）。
     */
    private fun agentSessionPages(exchange: HttpExchange) {
        val rows = sessionPages.snapshot()
        respond(
            exchange,
            200,
            buildJsonObject {
                put("total", rows.size)
                putJsonArray("requests") {
                    rows.forEach { r ->
                        addJsonObject {
                            put("id", r.id)
                            put("nodeId", r.nodeId)
                            put("wsId", r.wsId)
                            put("offset", r.offset)
                            put("limit", r.limit)
                            put("at", r.at)
                        }
                    }
                }
            },
        )
    }

    /** 宿主办完一条后销掉它（body `{ id }`）。幂等：找不到也回 ok。 */
    private fun resolveAgentSessionPage(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: run {
            respond(exchange, 400, errorJson("bad-request", "请求体不是 JSON 对象"))
            return
        }
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: run {
            respond(exchange, 400, errorJson("bad-request", "缺少 id"))
            return
        }
        val removed = sessionPages.resolve(id)
        respond(
            exchange,
            200,
            buildJsonObject {
                put("ok", true)
                put("removed", removed)
            },
        )
    }

    /** `GET /v1/agent/grants` —— 本宿主的策略与白名单 + 待办申请（桌面端授权页读数）。 */
    private fun readGrants(exchange: HttpExchange) {
        val snap = grants.snapshot()
        respond(
            exchange,
            200,
            buildJsonObject {
                put("policy", snap.policy)
                // 非 null = 这次启动没能读回台账、已按最严档运行。桌面端授权页必须
                // 显式显示它 —— 否则用户看到的是一台"策略被改了"的宿主，实际是文件坏了。
                put("loadError", snap.loadError)
                putJsonArray("grants") {
                    snap.grants.forEach { g ->
                        addJsonObject {
                            put("nodeId", g.nodeId)
                            put("scope", g.scope)
                            put("agentKey", g.agentKey)
                            put("grantedAt", g.grantedAt)
                            put("grantedBy", g.grantedBy)
                        }
                    }
                }
                putJsonArray("requests") {
                    snap.requests.forEach { r ->
                        addJsonObject {
                            put("nodeId", r.nodeId)
                            put("agentKey", r.agentKey)
                            put("reason", r.reason)
                            put("at", r.at)
                        }
                    }
                }
            },
        )
    }

    /**
     * `PUT /v1/agent/grants` —— 写策略 / 授权 / 撤销（**写入者 = 桌面端**，父稿 §3.3）。
     *
     * body：`{policy?, grant?:{nodeId,scope,agentKey?,grantedBy?},
     * revoke?:{nodeId,scope,agentKey?}, grantReply?:{nodeId,state,scope,agentKey?}}`
     *
     * `grantReply` 把决定**回给设备**（`agent-grant` 帧，宿主 → 设备）：批准 / 拒绝 / 撤销
     * 都是"有人按了按钮"，必须让对面知道 —— 否则手机端的四档状态永远停在「已申请待批」。
     * 撤销只拦未来投递（边界 1），回执文案如实写。
     */
    private fun writeGrants(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: return
        var changed = false
        val notes = mutableListOf<String>()

        obj["policy"]?.jsonPrimitive?.contentOrNull?.let { next ->
            if (!grants.setPolicy(next)) {
                respond(
                    exchange, 400,
                    errorJson("bad-request", "policy 只能是 " + AgentGrantPolicies.VALUES.joinToString(" / ") + "；收到「" + next + "」"),
                )
                return
            }
            changed = true
            notes += "policy=" + next
        }

        (obj["grant"] as? JsonObject)?.let { g ->
            val nodeId = g["nodeId"]?.jsonPrimitive?.contentOrNull
            val scope = g["scope"]?.jsonPrimitive?.contentOrNull
            if (nodeId == null || scope == null || scope !in AgentGrantScopes.VALUES) {
                respond(exchange, 400, errorJson("bad-request", "grant 需要 nodeId 与 scope（all / agent）"))
                return
            }
            val agentKey = g["agentKey"]?.jsonPrimitive?.contentOrNull
            if (scope == AgentGrantScopes.AGENT && agentKey.isNullOrEmpty()) {
                respond(exchange, 400, errorJson("bad-request", "scope=agent 时 agentKey 必填（缺了它无法归组，不许按整台设备放行）"))
                return
            }
            grants.grant(nodeId, scope, agentKey, g["grantedBy"]?.jsonPrimitive?.contentOrNull, System.currentTimeMillis())
            changed = true
            notes += "granted " + nodeId + "/" + scope + if (agentKey != null) "/" + agentKey else ""
        }

        (obj["revoke"] as? JsonObject)?.let { r ->
            val nodeId = r["nodeId"]?.jsonPrimitive?.contentOrNull
            val scope = r["scope"]?.jsonPrimitive?.contentOrNull
            if (nodeId == null || scope == null || scope !in AgentGrantScopes.VALUES) {
                respond(exchange, 400, errorJson("bad-request", "revoke 需要 nodeId 与 scope（all / agent）"))
                return
            }
            val removed = grants.revoke(nodeId, scope, r["agentKey"]?.jsonPrimitive?.contentOrNull)
            changed = true
            notes += "revoked " + nodeId + "/" + scope + "（移除 " + removed + " 条；只拦未来投递，已送达的数据不回收）"
        }

        (obj["grantReply"] as? JsonObject)?.let { rp ->
            val nodeId = rp["nodeId"]?.jsonPrimitive?.contentOrNull
            val state = rp["state"]?.jsonPrimitive?.contentOrNull
            val scope = rp["scope"]?.jsonPrimitive?.contentOrNull
            if (nodeId == null || state == null || state !in AgentGrantStates.ALL ||
                scope == null || scope !in AgentGrantScopes.VALUES
            ) {
                respond(exchange, 400, errorJson("bad-request", "grantReply 需要 nodeId、state 与 scope（取值见 AgentGrantStates / AgentGrantScopes）"))
                return
            }
            val agentKey = rp["agentKey"]?.jsonPrimitive?.contentOrNull
            // cap 门控（父稿 §3.3）：对端没声明 agr1 就**不发**这帧。发了它也不认，
            // 而 grant 帧在 RELIABLE 里 ⇒ 等不到 ACK 会把一台**在线设备标成离线**并
            // 入离线补发队列（与 agent-workspace 首版同一个坑）。授权决定照样写台账 ——
            // 那是本机事实；对方升级到认识 agr1 之后再申请时会拿到当前清单。
            if (!runtime.peerDeclares(nodeId, MsgTypes.CAP_AGENT_GRANT)) {
                notes += "grantReply 未发送：对方未声明 " + MsgTypes.CAP_AGENT_GRANT +
                    "（不认识 grant 帧）。台账已写，对方升级后再申请会拿到当前清单"
            } else {
                try {
                    runBlocking {
                        runtime.sendAgentGrant(nodeId, state, scope, agentKey, System.currentTimeMillis())
                    }
                    notes += "grantReply sent -> " + nodeId
                } catch (t: Throwable) {
                    notes += "grantReply failed: " + (t.message ?: "发送失败") + "（台账已写，对方下次申请会拿到当前清单）"
                }
            }
        }

        if (!changed) {
            respond(exchange, 400, errorJson("bad-request", "body 至少要带 policy / grant / revoke / grantReply 之一"))
            return
        }
        val snap = grants.snapshot()
        respond(
            exchange,
            200,
            buildJsonObject {
                put("ok", true)
                put("policy", snap.policy)
                putJsonArray("notes") { notes.forEach { add(JsonPrimitive(it)) } }
            },
        )
    }

    /** `POST /v1/agent/event` —— 追加一条事件（游标由调用方给，本端不代生成）。 */
    private fun agentEvent(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: return
        val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_SESSION) ?: return
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sessionId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 sessionId（§6.2 的必填字段）"))
            return
        }
        val eventType = obj["eventType"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (eventType.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 eventType（§6.2 的必填字段）"))
            return
        }
        // cursor 必须是**数字**：字符串 "42" 会被 Codec 拒收（`validateAgentEvent` 要求整数），
        // 在这里就拦下来，免得走到自检那一步才报一个绕口的理由。
        val cursor = numberOrNull(obj["cursor"])
        if (cursor == null || cursor < 0) {
            respond(
                exchange,
                400,
                errorJson("bad-request", "cursor 必须是非负整数（§6.2：同一会话内的单调游标，不是时间戳）"),
            )
            return
        }
        val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
        val body = obj["body"]?.jsonPrimitive?.contentOrNull
        val ref = obj["ref"]?.jsonPrimitive?.contentOrNull
        deliverAgentFrame(
            exchange = exchange,
            nodeId = nodeId,
            type = MsgTypes.AGENT_EVENT,
            payload = Codec.agentEventPayload(sessionId, cursor, eventType, summary, body, ref),
            send = { runtime.sendAgentEvent(nodeId, sessionId, cursor, eventType, summary, body, ref) },
            onSent = {
                sessions.recordEvent(
                    nodeId = nodeId,
                    sessionId = sessionId,
                    cursor = cursor,
                    eventType = eventType,
                    summary = summary,
                    body = body,
                    ref = ref,
                )
            },
        )
    }

    /**
     * `POST /v1/agent/queue` —— 投递一条队列帧（§6.3，cap `agq1`）。
     *
     * `op` 只收 `enqueue` / `dequeue`（§6.3 的两个取值）。**取消一个排队中的任务就是
     * `dequeue`**，不是第三个 op 值 —— 早先移动端在这里发过 `op="cancel"`，会被
     * `Codec.validateAgentQueue` 拒收，`deliverAgentFrame` 的自检于是回 400
     * 「这一帧我们自己都拒收」。取消**一个执行中的 run** 走 §6.6 的 `/v1/agent/cancel`。
     */
    private fun agentQueue(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: return
        val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_QUEUE) ?: return
        val taskId = obj["taskId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (taskId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 taskId（§6.3 的必填字段）"))
            return
        }
        val op = obj["op"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (op !in QUEUE_OPS) {
            respond(
                exchange,
                400,
                errorJson("bad-request", "op 只能是 ${QUEUE_OPS.joinToString(" / ")}（§6.3）；收到「$op」"),
            )
            return
        }
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull
        val queuedAt = numberOrNull(obj["queuedAt"])
        deliverAgentFrame(
            exchange = exchange,
            nodeId = nodeId,
            type = MsgTypes.AGENT_QUEUE,
            payload = Codec.agentQueuePayload(taskId, sessionId, op, prompt, queuedAt),
            send = { runtime.sendAgentQueue(nodeId, taskId, sessionId, op, prompt, queuedAt) },
            onSent = {
                sessions.recordQueue(
                    nodeId = nodeId,
                    taskId = taskId,
                    sessionId = sessionId,
                    op = op,
                    prompt = prompt,
                    queuedAt = queuedAt,
                    outbound = true,
                )
            },
        )
    }

    /** `POST /v1/agent/run` —— 宣告一次执行的开始/结束（§6.4，cap `agq1`）。 */
    private fun agentRun(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: return
        val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_QUEUE) ?: return
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sessionId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 sessionId（§6.4 的必填字段）"))
            return
        }
        val runId = obj["runId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (runId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 runId（§6.4 的必填字段）"))
            return
        }
        val state = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (state !in RUN_STATES) {
            respond(
                exchange,
                400,
                errorJson("bad-request", "state 只能是 ${RUN_STATES.joinToString(" / ")}（§6.4）；收到「$state」"),
            )
            return
        }
        val at = numberOrNull(obj["at"])
        val note = obj["note"]?.jsonPrimitive?.contentOrNull
        deliverAgentFrame(
            exchange = exchange,
            nodeId = nodeId,
            type = MsgTypes.AGENT_RUN,
            payload = Codec.agentRunPayload(sessionId, runId, state, at, note),
            send = { runtime.sendAgentRun(nodeId, sessionId, runId, state, at, note) },
            onSent = {
                sessions.recordRun(
                    nodeId = nodeId,
                    sessionId = sessionId,
                    runId = runId,
                    state = state,
                    at = at,
                    note = note,
                    outbound = true,
                )
            },
        )
    }

    /**
     * `POST /v1/agent/approval` —— 请对端确认一个受控动作（§6.5，cap `agq1`）。
     *
     * §6.5 把它标成「⚠️ 关键控制路径」，并规定审批**不得**降级为「已送达」：所以
     * 这里的回执仍是 `deliverAgentFrame` 的 `sent` / `queued`（那是**传输**状态），
     * 审批本身的状态在台账里是 `status`，由 §6.8 的 `agent-ack` 推进。
     */
    private fun agentApproval(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: return
        val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_QUEUE) ?: return
        val approvalId = obj["approvalId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (approvalId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 approvalId（§6.5 的必填字段）"))
            return
        }
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sessionId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 sessionId（§6.5 的必填字段）"))
            return
        }
        val expiresAt = numberOrNull(obj["expiresAt"])
        if (expiresAt == null || expiresAt < 0) {
            respond(
                exchange,
                400,
                errorJson("bad-request", "expiresAt 必须是非负整数（§6.5：审批的过期时刻，epoch ms）"),
            )
            return
        }
        val tool = obj["tool"]?.jsonPrimitive?.contentOrNull
        val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
        val choices = obj["choices"]?.jsonPrimitive?.contentOrNull
        val riskLevel = obj["riskLevel"]?.jsonPrimitive?.contentOrNull
        val diff = obj["diff"]?.jsonPrimitive?.contentOrNull
        deliverAgentFrame(
            exchange = exchange,
            nodeId = nodeId,
            type = MsgTypes.AGENT_APPROVAL,
            payload = Codec.agentApprovalPayload(approvalId, sessionId, tool, summary, expiresAt, choices, riskLevel, diff),
            send = { runtime.sendAgentApproval(nodeId, approvalId, sessionId, tool, summary, expiresAt, choices, riskLevel, diff) },
            onSent = {
                sessions.recordApproval(
                    nodeId = nodeId,
                    sessionId = sessionId,
                    approvalId = approvalId,
                    tool = tool,
                    summary = summary,
                    expiresAt = expiresAt,
                    choices = choices,
                    outbound = true,
                )
            },
        )
    }

    /**
     * `POST /v1/agent/cancel` —— 取消一次执行或整个会话（§6.6，cap `agq1`）。
     *
     * `runId` 省略表示取消整个会话。§6.6 明确**禁止**复用 `file-msg.ctl` 的
     * accept/decline/cancel —— 那三个值的语义是文件领取，与任务取消无关。
     */
    private fun agentCancel(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: return
        val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_QUEUE) ?: return
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sessionId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 sessionId（§6.6 的必填字段）"))
            return
        }
        val runId = obj["runId"]?.jsonPrimitive?.contentOrNull
        val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
        deliverAgentFrame(
            exchange = exchange,
            nodeId = nodeId,
            type = MsgTypes.AGENT_CANCEL,
            payload = Codec.agentCancelPayload(sessionId, runId, reason),
            send = { runtime.sendAgentCancel(nodeId, sessionId, runId, reason) },
            onSent = {
                sessions.recordCancel(
                    nodeId = nodeId,
                    sessionId = sessionId,
                    runId = runId,
                    reason = reason,
                    outbound = true,
                )
            },
        )
    }

    /**
     * `POST /v1/agent/artifact` —— 宣告一件产物（§6.7，cap **`agn1`**）。
     *
     * 归 `agn1` 不归 `agq1`：§6.7 与 §6.1/§6.2 同类，只描述会话，不带控制语义。
     * **只描述产物，不传二进制** —— 二进制要先自己发 `file-msg`，这里只带它的 `id`。
     */
    private fun agentArtifact(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: return
        val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_SESSION) ?: return
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sessionId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 sessionId（§6.7 的必填字段）"))
            return
        }
        val artifactId = obj["artifactId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (artifactId.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 artifactId（§6.7 的必填字段）"))
            return
        }
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (name.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 name（§6.7 的必填字段）"))
            return
        }
        val fileId = obj["fileId"]?.jsonPrimitive?.contentOrNull
        val bytes = numberOrNull(obj["bytes"])
        val mime = obj["mime"]?.jsonPrimitive?.contentOrNull
        deliverAgentFrame(
            exchange = exchange,
            nodeId = nodeId,
            type = MsgTypes.AGENT_ARTIFACT,
            payload = Codec.agentArtifactPayload(sessionId, artifactId, name, fileId, bytes, mime),
            send = { runtime.sendAgentArtifact(nodeId, sessionId, artifactId, name, fileId, bytes, mime) },
            onSent = {
                sessions.recordArtifact(
                    nodeId = nodeId,
                    sessionId = sessionId,
                    artifactId = artifactId,
                    name = name,
                    fileId = fileId,
                    bytes = bytes,
                    mime = mime,
                    outbound = true,
                )
            },
        )
    }

    /**
     * `POST /v1/agent/ack` —— 对一条收到的帧给回执（§6.8，cap `agq1`）。
     *
     * `ackOf` 是被确认帧的 `msgId`。`kind` 里的 `approved` / `denied` / `cancelled`
     * 会顺带推进台账里那条审批的 `status`（见 `AgentSessionRegistry.applyAckToApproval`）——
     * 审批的"已处置"只有这一条路能产生，§10 不许它由"送达"冒充。
     */
    private fun agentAck(exchange: HttpExchange) {
        val obj = readJsonObject(exchange) ?: return
        val nodeId = resolveAgentTarget(exchange, obj, MsgTypes.CAP_AGENT_QUEUE) ?: return
        val ackOf = obj["ackOf"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (ackOf.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 ackOf（§6.8 的必填字段）"))
            return
        }
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (kind !in ACK_KINDS) {
            respond(
                exchange,
                400,
                errorJson("bad-request", "kind 只能是 ${ACK_KINDS.joinToString(" / ")}（§6.8）；收到「$kind」"),
            )
            return
        }
        val at = numberOrNull(obj["at"])
        deliverAgentFrame(
            exchange = exchange,
            nodeId = nodeId,
            type = MsgTypes.AGENT_ACK,
            payload = Codec.agentAckPayload(ackOf, kind, at),
            send = { runtime.sendAgentAck(nodeId, ackOf, kind, at) },
            onSent = {
                sessions.recordAck(nodeId = nodeId, ackOf = ackOf, kind = kind, at = at, outbound = true)
            },
        )
    }

    /**
     * `GET /v1/agent/sessions` —— 本节点的 agent 会话台账（面板的数据源）。
     *
     * 存在的理由：agent 帧出站不写 `messageStore`、也不进 `/v1/inbox`，所以「本机有哪些
     * agent 会话」在控制面上原本**问不到**，面板只能画一个空列表。这个只读路由把
     * [AgentSessionRegistry] 暴露出来。
     *
     * 可选 `sessionId` 查询参数：带上时额外返回该会话的事件明细（面板点进会话要看的事件流）；
     * 不带时只回会话列表，事件条数用 `eventCount` 给一个数（列表页够用，且避免一次拉全部事件）。
     *
     * 可选 `nodeId` 查询参数：把列表与事件明细都限制在这一个节点内。`sessionId` 由各节点
     * 自行分配，两个节点完全可能报同一个 id（`AgentInbound` 的幂等键正是 `(nodeId, sessionId)`），
     * 所以只按 `sessionId` 过滤会把对方的同名会话一并返回，调用方无法分辨。
     */
    private fun agentSessions(exchange: HttpExchange) {
        val query = parseQuery(exchange.requestURI.rawQuery)
        val wanted = query["sessionId"]?.trim().orEmpty()
        val wantedNode = query["nodeId"]?.trim().orEmpty()
        // 先按 nodeId 限定范围，再按 sessionId 收敛——**只算一次**，`total` 与下面的各个数组
        // 共用同一个 `rows`。此前 `total` 用的是「只按 nodeId 过滤」的那份，于是带 sessionId
        // 点名一个会话时：数组回 1 条，`total` 却回该节点的全部会话数，两个口径打架。
        val wantedWs = query["wsId"]?.trim().orEmpty()
        val scoped = sessions.sessions()
            .filter { wantedNode.isEmpty() || it.nodeId == wantedNode }
            // PPE-1 增补 §4：按工作区收敛（面板「第三面」的读数口径）。
            .filter { wantedWs.isEmpty() || it.wsId == wantedWs }
        // 带 sessionId 时只列那一个会话，免得面板拿到一份它没要的全量列表。
        val rows = if (wanted.isEmpty()) scoped else scoped.filter { it.sessionId == wanted }
        respond(
            exchange,
            200,
            buildJsonObject {
                putJsonArray("sessions") {
                    rows.forEach { s ->
                        addJsonObject {
                            put("nodeId", s.nodeId)
                            put("sessionId", s.sessionId)
                            // 可空字段直接交给 put：kotlinx.serialization 的 `put(key, String?)` /
                            // `put(key, Number?)` 会自己写 JsonNull。写成 `s.alias ?: JsonNull`
                            // 反而编不过 —— String 与 JsonNull 的公共父类是 Any，put 收不了。
                            put("alias", s.alias)
                            put("agentKind", s.agentKind)
                            put("state", s.state)
                            put("title", s.title)
                            put("startedAt", s.startedAt)
                            // PPE-1 增补 D1/D6：四个新键（未上报 = null）。
                            put("agentId", s.agentId)
                            put("wsId", s.wsId)
                            put("wsTitle", s.wsTitle)
                            put("wsPath", s.wsPath)
                            put("announcedOut", s.announcedOut)
                            put("announcedIn", s.announcedIn)
                            // true = 这条会话是**推断**出来的占位（两侧都没宣告过，见
                            // `AgentSessionRegistry.ensureSession`）。它的 state 一般为 null，
                            // 面板应降级显示，而不是当成一个正常会话。
                            put("synthesized", s.synthesized)
                            put("updatedAt", s.updatedAt)
                            put("eventCount", sessions.events(s.nodeId, s.sessionId).size)
                        }
                    }
                }
                // 事件明细只在点名要的时候给，且同样受 `wanted` 过滤。
                if (wanted.isNotEmpty()) {
                    putJsonArray("events") {
                        rows.forEach { s ->
                            sessions.events(s.nodeId, s.sessionId).forEach { e ->
                                addJsonObject {
                                    put("nodeId", e.nodeId)
                                    put("sessionId", e.sessionId)
                                    put("cursor", e.cursor)
                                    put("eventType", e.eventType)
                                    put("summary", e.summary)
                                    put("body", e.body)
                                    put("ref", e.ref)
                                    put("at", e.at)
                                }
                            }
                        }
                    }
                    // P1 的五个 agq1 类型 + §6.7 的产物：同样只在点名某个会话时给明细。
                    // 面板点进一个会话要看的就是「它排了什么、跑了什么、在等谁同意、被谁取消、
                    // 产出了什么」，这五张表合起来才是 §6 的完整语义。
                    putJsonArray("queues") {
                        rows.forEach { s ->
                            // 队列是会话级的，但"待分配"那一档挂在空串键下（§6.3 的
                            // `sessionId` 可为空），所以这里按 nodeId 取全部再筛。
                            sessions.queues(s.nodeId).filter { it.sessionId == null || it.sessionId == wanted }
                                .forEach { q ->
                                    addJsonObject {
                                        put("nodeId", q.nodeId)
                                        put("taskId", q.taskId)
                                        put("sessionId", q.sessionId)
                                        put("op", q.op)
                                        put("prompt", q.prompt)
                                        put("queuedAt", q.queuedAt)
                                        put("at", q.at)
                                        put("outbound", q.outbound)
                                    }
                                }
                        }
                    }
                    putJsonArray("runs") {
                        rows.forEach { s ->
                            sessions.runs(s.nodeId, s.sessionId).forEach { r ->
                                addJsonObject {
                                    put("nodeId", r.nodeId)
                                    put("sessionId", r.sessionId)
                                    put("runId", r.runId)
                                    put("state", r.state)
                                    put("at", r.at)
                                    put("note", r.note)
                                    put("updatedAt", r.updatedAt)
                                    put("outbound", r.outbound)
                                }
                            }
                        }
                    }
                    putJsonArray("approvals") {
                        rows.forEach { s ->
                            sessions.approvals(s.nodeId, s.sessionId).forEach { a ->
                                addJsonObject {
                                    put("nodeId", a.nodeId)
                                    put("sessionId", a.sessionId)
                                    put("approvalId", a.approvalId)
                                    put("tool", a.tool)
                                    put("summary", a.summary)
                                    put("expiresAt", a.expiresAt)
                                    put("choices", a.choices)
                                    // `status` 为 null = 还没处置（≠ 被拒）。§10 不许把
                                    // "没有回执" 显示成任何结论，所以这个 null 必须原样传出去。
                                    put("status", a.status)
                                    put("updatedAt", a.updatedAt)
                                    put("outbound", a.outbound)
                                }
                            }
                        }
                    }
                    putJsonArray("cancels") {
                        rows.forEach { s ->
                            sessions.cancels(s.nodeId, s.sessionId).forEach { c ->
                                addJsonObject {
                                    put("nodeId", c.nodeId)
                                    put("sessionId", c.sessionId)
                                    put("runId", c.runId)
                                    put("reason", c.reason)
                                    put("at", c.at)
                                    put("outbound", c.outbound)
                                }
                            }
                        }
                    }
                    putJsonArray("artifacts") {
                        rows.forEach { s ->
                            sessions.artifacts(s.nodeId, s.sessionId).forEach { a ->
                                addJsonObject {
                                    put("nodeId", a.nodeId)
                                    put("sessionId", a.sessionId)
                                    put("artifactId", a.artifactId)
                                    put("fileId", a.fileId)
                                    put("name", a.name)
                                    put("bytes", a.bytes)
                                    put("mime", a.mime)
                                    put("at", a.at)
                                    put("outbound", a.outbound)
                                }
                            }
                        }
                    }
                }
                put("total", rows.size)
            },
        )
    }

    /**
     * 解析 `to` / `port`、握手拿到 nodeId，并完成 §4 的 caps 前置检查。
     *
     * 返回 `null` 表示**已经回过响应**（错误），调用方直接 return。
     */
    private fun resolveAgentTarget(
        exchange: HttpExchange,
        obj: JsonObject,
        /**
         * 这一类帧要求的 cap（§4 的 caps 表）。
         *
         * 必须**逐类**传，不能统一按 `agn1` 放行：`agq1` 的五个类型（queue/run/approval/
         * cancel/ack）要求对方真的实现了队列与审批回路，`agn1` 只保证它懂会话基线。
         * 拿 `agn1` 给 `agq1` 的帧开门，等于替对端宣称了一个它没声明过的能力 ——
         * 帧会发出去、被 ACK、然后在对端静默消失。
         */
        requiredCap: String,
    ): String? {
        val to = obj["to"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (to.isEmpty()) {
            respond(exchange, 400, errorJson("bad-request", "缺少 to（对方 IP 或 nodeId）"))
            return null
        }
        val port = obj["port"]?.jsonPrimitive?.intOrNull
        if (port != null && port !in 1..65535) {
            respond(exchange, 400, errorJson("bad-request", "port 越界：$port"))
            return null
        }
        val target = if (port != null) "$to:$port" else to
        val nodeId = runtime.getPeer(to)?.profile?.nodeId
            ?: runBlocking { runtime.resolvePeerByHost(to, port) }?.profile?.nodeId
        if (nodeId == null) {
            respond(
                exchange,
                404,
                errorJson(
                    "peer-unreachable",
                    "3 秒内无法与 $target 建立握手（对方设备是否已启动？IP 与端口是否正确？）",
                ),
            )
            return null
        }
        if (!runtime.peerDeclares(nodeId, MsgTypes.CAP_AGENT_SESSION)) {
            respond(
                exchange,
                409,
                errorJson(
                    "peer-unsupported",
                    "对方（$target）未声明 ${MsgTypes.CAP_AGENT_SESSION}（agent 会话语义基线），" +
                        "按 PPE-1 §4 不发这一帧；可改用 POST /v1/send 以普通文本送达",
                ),
            )
            return null
        }
        if (!runtime.peerDeclares(nodeId, requiredCap)) {
            respond(
                exchange,
                409,
                errorJson(
                    "peer-unsupported",
                    "对方（$target）未声明 $requiredCap（这一帧所属能力组），" +
                        "按 PPE-1 §4 不发这一帧；可改用 POST /v1/send 以普通文本送达",
                ),
            )
            return null
        }
        // PPE-1 增补 D7 的**执行点**：投递前按授权策略过滤 —— 这里不放行，数据就根本
        // 不出门（父稿 §3.3「没授权 = 没数据」只有在宿主进程过滤才成立）。
        // agentKey = agentId ?? agentKind（转发方知道自己在转发哪个 agent）；
        // 两者都没报时按"整台设备"判（scope=agent 的授权不放行无身份的帧，不猜）。
        val agentKey = obj["agentId"]?.jsonPrimitive?.contentOrNull
            ?: obj["agentKind"]?.jsonPrimitive?.contentOrNull
        if (!grants.allows(nodeId, agentKey)) {
            val snap = grants.snapshot()
            respond(
                exchange,
                403,
                errorJson(
                    "not-granted",
                    "对方（$target）未被授权看这台宿主的 agent（策略 ${snap.policy}）。" +
                        // 台账读不回来时，对方看到的是"被拒"，但真实原因是"宿主自己丢了决定"。
                        // 不说这件事，人会去改一台好设备的授权，而问题在这台宿主的文件上。
                        (snap.loadError?.let { "注意：本宿主这次启动没能读回授权台账（$it），因此按最严档运行。" } ?: "") +
                        // 申请是 PPE-1 的 **协议帧**（agent-grant-request，设备 → 宿主），
                        // 不是 HTTP 路由 —— 控制面只对外开 GET/PUT /v1/agent/grants 两条。
                        // 从前这里写"经 POST /v1/agent/grant-request 申请"，那条路由不存在。
                        "在桌面端「捎话 → 授权」里批准，或让对方重发一次 agent-grant-request 申请" +
                        "（申请走 PPE-1 协议帧，控制面没有对应的 HTTP 路由）。",
                ),
            )
            return null
        }
        return nodeId
    }

    /**
     * 自检 → 发送 → 回执。
     *
     * 自检复用**收端的校验器**：把要发的帧编码出来再 `Codec.decodeTcp` 解回去，我们自己
     * 都不收就绝不发出去。手抄一份字段规则迟早与 `Codec` 漂移，那样"发得出去、收不回来"
     * 会变成一个只在真机上才暴露的问题。
     */
    private fun deliverAgentFrame(
        exchange: HttpExchange,
        nodeId: String,
        type: String,
        payload: JsonElement,
        send: suspend () -> SendOutcome,
        /**
         * 帧**真的投出去之后**才调（自检拒收 / 502 都不会走到这里）。
         *
         * 放在这里而不是各调用方里，是为了让「记账」与「投递成功」在代码上不可能分叉：
         * 两处调用各写一遍 `if (outcome == SENT)`，迟早有一处漏写或写成 `!= FAILED`，
         * 那样台账里会出现一个从没上过线的会话 —— 面板显示它「busy」，而线上什么都没有。
         */
        onSent: () -> Unit = {},
    ) {
        val frame = Codec.encode(makeEnvelope(type, runtime.nodeId, payload))
        val back = Codec.decodeTcp(frame)
        if (back is DecodeResult.Rejected) {
            respond(exchange, 400, errorJson("bad-request", "这一帧我们自己都拒收：${back.reason}"))
            return
        }
        val outcome = try {
            runBlocking { send() }
        } catch (t: Throwable) {
            respond(exchange, 502, errorJson("send-failed", t.message ?: "发送失败"))
            return
        }
        // queued 也记账：帧已经交给重传队列，会话确实"存在"，只是还没送达。
        // 不记的话，对端离线时发的宣告在面板上凭空消失，用户会以为没发出去。
        runCatching { onSent() }
        respond(
            exchange,
            200,
            buildJsonObject {
                put("status", if (outcome == SendOutcome.SENT) "sent" else "queued")
                put("to", nodeId)
                put("type", type)
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

    private fun inbox(exchange: HttpExchange) {
        val query = parseQuery(exchange.requestURI.rawQuery)
        val since = query["since"]?.toLongOrNull() ?: 0L
        val limit = (query["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val rows = runtime.inbox(since, limit)
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
                            put(
                                "envelope",
                                runCatching { json.parseToJsonElement(row.envelopeJson) }
                                    .getOrElse { JsonNull },
                            )
                        }
                    }
                }
                put("nextSince", rows.lastOrNull()?.at ?: since)
            },
        )
    }

    private fun healthzJson(): JsonObject {
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
     *   1. **只绑回环**（`start()` 里 `HttpServer.create(InetSocketAddress(LOOPBACK, …))`）。
     *      将来任何人给控制面加"允许非回环访问"之类改动，必须把这条路由单独排除 ——
     *      否则等于把令牌挂到公网上。
     *   2. 结果**不进日志、不进任何截图产物**。
     *
     * 编码复用 `:core` 的 [QrEncoder]（字节模式、等级 M、版本 1–10，容量 213 字节），
     * 不引第三方包。超限由 [QrEncoder.encode] 抛 [IllegalArgumentException]（**不截断**）——
     * 截断出来的是一张能扫、内容却是错的码，那比不给更糟。
     */
    private fun pairingQr(exchange: HttpExchange) {
        if (publicUrl.isBlank()) {
            respond(
                exchange,
                400,
                errorJson(
                    "no-public-url",
                    "没配公网入口，印不出配对码。启动时加 --public-url https://<域名>/relay" +
                        "（隧道暴露的控制面地址，见 tools/CROSS-NET-RUNBOOK.md）。",
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

    private fun peersJson(): JsonObject = buildJsonObject {
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
                }
            }
        }
    }

    private fun helpJson(): JsonObject = buildJsonObject {
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
            add(JsonPrimitive("GET  /v1/messages  （/v1/inbox 的同义路由）"))
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

    // ---------------------------------------------------------------- helpers

    private fun respond(exchange: HttpExchange, code: Int, body: JsonElement) {
        val bytes = body.toString().encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }

    private fun readJsonObject(exchange: HttpExchange): JsonObject? {
        val raw = exchange.requestBody.readBytes().decodeToString()
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (obj == null) {
            respond(exchange, 400, errorJson("bad-json", "请求体必须是 JSON 对象"))
            return null
        }
        return obj
    }

    /**
     * 取一个 JSON **数字**（不是"能被解析成数字的字符串"）。
     *
     * `jsonPrimitive.longOrNull` 会把 `"42"` 也读成 42，而 `Codec` 对 `cursor` 这类字段要求
     * 真整数 —— 两边不一致就会出现"HTTP 收了、帧却被自检拒收"。这里先排除字符串。
     */
    /** 布尔值：字符串与 null 一律不算（与 [numberOrNull] 同纪律）。 */
    private fun boolOrNull(element: JsonElement?): Boolean? {
        val primitive = element?.jsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.booleanOrNull
    }

    private fun numberOrNull(element: JsonElement?): Long? {
        val primitive = element?.jsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.longOrNull
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val eq = pair.indexOf('=')
            if (eq < 0) {
                decode(pair) to ""
            } else {
                decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
            }
        }.toMap()
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    companion object {
        const val LOOPBACK: String = "127.0.0.1"
        const val DEFAULT_HTTP_PORT: Int = 8788

        /**
         * 授权台账的默认落点（只在调用方没显式传 [AgentGrantStore] 时用到）。
         *
         * 曾经落在 `java.io.tmpdir`：那是**重启即失**的目录，而这份文件记的是人点过的
         * 「哪台设备可以看我的 agent」。丢了它，下次启动 [AgentGrantStore.load] 走
         * 「文件不在」分支、策略退回出厂 `auto`（全放行）—— 一个能自己消失的授权台账
         * 比没有台账更坏，因为它看起来像配置过。现在跟桌面端同一套规矩，落到用户数据目录。
         *
         * 注意：**正常路径不经过这里**。桌面端在 `DesktopExtension` 里显式传
         * `dataDirectory/agent-grants.json`；这个默认值只服务于不传 grants 的调用方
         * （测试、以及将来可能有的独立 CLI）。
         */
        internal fun defaultGrantFile(): File {
            val home = System.getProperty("user.home") ?: "."
            val os = System.getProperty("os.name").orEmpty()
            val base = if (os.contains("Mac")) {
                "$home/Library/Application Support"
            } else {
                System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
            }
            return File("$base/捎话 Tidings", "agent-grants.json")
        }
        private const val DEFAULT_LIMIT: Int = 200
        private const val MAX_LIMIT: Int = 1000

        /** §6.3 的 `op` 取值。取消排队中的任务是 `dequeue`，不是第三个值。 */
        private val QUEUE_OPS = listOf("enqueue", "dequeue")

        /** §6.4 的 `state` 取值。 */
        private val RUN_STATES = listOf("running", "succeeded", "failed", "cancelled")

        /** §6.8 的 `kind` 取值。 */
        private val ACK_KINDS = listOf("queued", "approved", "denied", "cancelled", "received")
    }
}
