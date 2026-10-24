// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent

import com.kapp.tidings.core.protocol.AgentGrantPolicies
import com.kapp.tidings.core.protocol.Codec
import com.kapp.tidings.core.protocol.MsgKinds
import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.protocol.newId
import com.kapp.tidings.core.session.NodeRuntime
import com.kapp.tidings.agent.testutil.Await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 控制面的进程内端到端测试：**真的**起两个 [NodeRuntime]（一个当 agentNode，一个当
 * 普通桌面节点那一侧），真的走 UDP 握手与 ACK，再通过真的 HTTP 调控制面。
 *
 * 为什么不用 mock：控制面唯一的价值就是"把 HTTP 上的 to/text 变成协议上的握手+投递"，
 * 把这一层 mock 掉，测试就只剩下"我调用了我的函数"。这里两个节点都在回环上，
 * 端口传 0 由系统分配，所以能并行跑、不抢 17878。
 */
class ControlPlaneTest {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private lateinit var scope: CoroutineScope
    private lateinit var agent: NodeRuntime
    private lateinit var desktop: NodeRuntime
    private lateinit var plane: ControlPlane
    private lateinit var registry: AgentSessionRegistry
    private lateinit var workspaceRegistry: AgentWorkspaceRegistry
    private lateinit var grantStore: AgentGrantStore
    private val grantFile: File = File.createTempFile("relay-grants-test-", ".json").apply { delete() }
    private val desktopFrames = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // 普通桌面节点那一侧：只做协议对端，不起控制面。
        desktop = NodeRuntime(
            profile = Profile.mobile(
                nodeId = newId(),
                nick = "桌面端",
                host = "MacBook-Pro",
                ver = "0.61.1",
            ),
            udpPort = 0,
            bindHost = LOOPBACK,
        )

        // agentNode 那一侧。
        agent = NodeRuntime(
            profile = Profile.mobile(
                nodeId = newId(),
                nick = "Tidings Agent",
                host = "relay-agent",
                ver = "relay-0.1.0",
            ),
            udpPort = 0,
            bindHost = LOOPBACK,
        )

        runBlocking {
            desktop.start(scope)
            agent.start(scope)
        }

        desktopFrames.clear()
        registry = AgentSessionRegistry()
        // 每个用例一份**独立**的授权文件：策略是持久化状态，共用一个文件会让
        // "A 用例把策略改成 byDevice"泄漏进 B 用例（同一条纪律：夹具假一点就测不出真故障）。
        workspaceRegistry = AgentWorkspaceRegistry()
        grantStore = AgentGrantStore(File(grantFile.absolutePath + "." + System.nanoTime()))
        grantStore.setPolicy(AgentGrantPolicies.AUTO)
        scope.launch {
            desktop.inbound.collect { env ->
                desktopFrames += Codec.wireJson.parseToJsonElement(Codec.encode(env)).jsonObject
            }
        }
        plane = ControlPlane(
            agent, httpPort = 0, token = "",
            sessions = registry, workspaces = workspaceRegistry, grants = grantStore,
        )
        plane.start()
        // 与 Main / DesktopExtension 同一条路：三张台账的入站解析只有 observeInbound 这一份。
        // 不接上它，grant-request 进不了待办、工作区宣告进不了台账 —— 测试就会把
        // "生产路径漏订阅"这种真故障测成绿的。
        plane.observeInbound(scope)
    }

    @AfterTest
    fun tearDown() {
        desktopFrames.clear()
        plane.stop()
        // NodeRuntime.stop() 是挂起的：要等 TCP 监听器真正关掉，端口才算释放。
        runBlocking {
            agent.stop()
            desktop.stop()
        }
        scope.cancel()
    }

    // ------------------------------------------------------------------ 正常路径

    @Test
    fun healthzReportsTheRealNodeIdentity() {
        val body = get("/healthz")
        assertEquals(200, body.first)
        val json = obj(body.second)
        assertEquals(true, json["ok"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(agent.nodeId, json["nodeId"]?.jsonPrimitive?.contentOrNull)
        assertEquals(agent.boundPort, json["udpPort"]?.jsonPrimitive?.int)
        assertEquals(plane.boundPort, json["httpPort"]?.jsonPrimitive?.int)
        // 协议里没有 mobile，手机端必须伪装成 linux，否则桌面端不认。
        assertEquals("linux", json["platform"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun sendByIpHandshakesThenDeliversAndGetsAcked() {
        val res = post(
            "/v1/send",
            """{"to":"$LOOPBACK","port":${desktop.boundPort},"text":"你好，来自 agentNode"}""",
        )
        assertEquals(200, res.first, "控制面应回 200，实际：${res.second}")
        val json = obj(res.second)
        // "sent" 的含义是对方真的回了 ACK —— 这才是端到端送达的证据。
        assertEquals("sent", json["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(desktop.nodeId, json["to"]?.jsonPrimitive?.contentOrNull)

        val received = desktop.inbox(0L).filter { it.direction == "in" }
        assertEquals(1, received.size)
        val payload = obj(received[0].envelopeJson)["payload"]!!.jsonObject
        assertEquals("你好，来自 agentNode", payload["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("text", payload["kind"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun inboxSurfacesBothDirectionsAndPeersShowsTheHandshakedNode() {
        post("/v1/send", """{"to":"$LOOPBACK","port":${desktop.boundPort},"text":"出去的信"}""")
        // 反向：桌面端主动发一封给 agentNode。
        runBlocking { desktop.sendText(agent.nodeId, "回来的信") }

        val inbound = Await.value("agentNode 收件箱里出现入站帧") {
            array(get("/v1/inbox?since=0").second)
                .filter { it["direction"]?.jsonPrimitive?.contentOrNull == "in" }
                .takeIf { it.isNotEmpty() }
        }
        assertEquals(1, inbound.size, "入站应恰好一封")
        assertEquals("in", inbound[0]["direction"]?.jsonPrimitive?.contentOrNull)
        assertEquals(desktop.nodeId, inbound[0]["peerId"]?.jsonPrimitive?.contentOrNull)
        val payload = inbound[0]["envelope"]!!.jsonObject["payload"]!!.jsonObject
        assertEquals("回来的信", payload["text"]?.jsonPrimitive?.contentOrNull)

        // 出站那封也必须在 inbox 里（agent 需要看到自己发过什么）。
        val all = array(get("/v1/inbox?since=0").second)
        assertEquals(2, all.size)
        assertEquals(
            setOf("in", "out"),
            all.map { it["direction"]?.jsonPrimitive?.contentOrNull }.toSet(),
        )

        val peers = array(get("/v1/peers").second)
        val peer = peers.firstOrNull {
            it["nodeId"]?.jsonPrimitive?.contentOrNull == desktop.nodeId
        }
        assertTrue(peer != null, "握手后的桌面端应出现在节点表里")
        assertEquals(true, peer["online"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(desktop.boundPort, peer["udpPort"]?.jsonPrimitive?.int)
        // 来源地址必须是数值 IP：被反解成 "localhost" 会让按 IP 查找永远 404。
        assertEquals(LOOPBACK, peer["ip"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun sendToNodeIdSkipsHandshakeEntirely() {
        // 先握手一次，让 nodeId 进入节点表。
        post("/v1/send", """{"to":"$LOOPBACK","port":${desktop.boundPort},"text":"第一次"}""")
        val res = post("/v1/send", """{"to":"${desktop.nodeId}","text":"按 nodeId 发"}""")
        assertEquals(200, res.first, res.second)
        assertEquals("sent", obj(res.second)["status"]?.jsonPrimitive?.contentOrNull)
    }

    // ------------------------------------------------------------------ 错误路径

    @Test
    fun badRequestsAreRejectedWithActionableMessages() {
        assertEquals(400, post("/v1/send", "not json").first)
        assertEquals(400, post("/v1/send", """{"text":"缺 to"}""").first)
        assertEquals(400, post("/v1/send", """{"to":"$LOOPBACK"}""").first, "空 text 协议不认")
        assertEquals(400, post("/v1/send", """{"to":"$LOOPBACK","text":"x","port":99999}""").first)
        assertEquals(404, get("/nope").first)
        // GET 打在只收 POST 的路由上，必须报错而不是被当成一次空发送。
        val onGet = get("/v1/send").first
        assertTrue(onGet == 404 || onGet == 405, "GET /v1/send 回了 $onGet")
    }

    @Test
    fun unreachablePeerIsReportedNotSilentlyQueued() {
        // 192.0.2.0/24 是 TEST-NET-1，保证不可达。
        val res = post("/v1/send", """{"to":"192.0.2.1","text":"发不出去"}""")
        assertNotEquals(200, res.first)
        assertTrue(res.first == 404 || res.first == 502, "实际 ${res.first}: ${res.second}")
        assertTrue(obj(res.second)["ok"]?.jsonPrimitive?.content == "false")
    }

    @Test
    fun tokenIsEnforcedWhenConfiguredAndInvisibleInResponses() {
        val guarded = ControlPlane(agent, httpPort = 0, token = "s3cret-token", sessions = AgentSessionRegistry())
        guarded.start()
        try {
            assertEquals(401, get("/healthz", port = guarded.boundPort).first)
            assertEquals(
                200,
                get("/healthz", port = guarded.boundPort, token = "s3cret-token").first,
            )
            // token 本身绝不能回显到任何响应里。
            val body = get("/", port = guarded.boundPort, token = "s3cret-token").second
            assertTrue(!body.contains("s3cret-token"), "响应体泄漏了 token：$body")
        } finally {
            guarded.stop()
        }
    }

    // ------------------------------------------------------------------ agent 会话帧（PPE-1）

    @Test
    fun agentSessionAndEventArriveAsTypedFramesWithTheRightPayload() {
        val session = post(
            "/v1/agent/session",
            """{"to":"$LOOPBACK","port":${desktop.boundPort},"sessionId":"s-1a97a6f3",""" +
                """"alias":"refactor runner","agentKind":"codex","state":"busy","title":"把撤回窗口补上"}""",
        )
        assertEquals(200, session.first, session.second)
        val sessionJson = obj(session.second)
        assertEquals("sent", sessionJson["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("agent-session", sessionJson["type"]?.jsonPrimitive?.contentOrNull)

        val event = post(
            "/v1/agent/event",
            """{"to":"$LOOPBACK","port":${desktop.boundPort},"sessionId":"s-1a97a6f3","cursor":42,""" +
                """"eventType":"tool.started","summary":"跑门禁","ref":"tool-1"}""",
        )
        assertEquals(200, event.first, event.second)
        assertEquals("agent-event", obj(event.second)["type"]?.jsonPrimitive?.contentOrNull)

        // 两帧都必须真的落到对端：status=sent 的含义是对方回了 ACK，不是"我们发出去了"。
        val expectedTypes = setOf("agent-session", "agent-event")
        val frames = Await.value("对端收到 $expectedTypes") {
            desktopFrames
                .filter { it["type"]?.jsonPrimitive?.contentOrNull in expectedTypes }
                .takeIf { seen ->
                    seen.mapNotNull { it["type"]?.jsonPrimitive?.contentOrNull }.containsAll(expectedTypes)
                }
        }
        val sessionPayload = frames
            .first { it["type"]?.jsonPrimitive?.contentOrNull == "agent-session" }
            .let { it["payload"]!!.jsonObject }
        assertEquals("s-1a97a6f3", sessionPayload["sessionId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("refactor runner", sessionPayload["alias"]?.jsonPrimitive?.contentOrNull)
        assertEquals("busy", sessionPayload["state"]?.jsonPrimitive?.contentOrNull)
        // 没给的字段必须整条省略，而不是发一个 null（协议靠 explicitNulls=false 省略）。
        assertTrue(!sessionPayload.containsKey("startedAt"), "没给的字段不该上线：$sessionPayload")

        val eventPayload = frames
            .first { it["type"]?.jsonPrimitive?.contentOrNull == "agent-event" }
            .let { it["payload"]!!.jsonObject }
        assertEquals(42L, eventPayload["cursor"]?.jsonPrimitive?.long)
        assertEquals("tool.started", eventPayload["eventType"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tool-1", eventPayload["ref"]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * `nodeId` 过滤是必需的，不是锦上添花：`sessionId` 由各节点自行分配，两个节点撞成同一个
     * 是合法的（`AgentInbound` 的幂等键正是 `(nodeId, sessionId)`）。只按 `sessionId` 问
     * 明细，调用方拿到的是两条同 id 的会话混在一起、且分不出哪些事件是自己的 ——
     * 面板会因此把 A 节点的事件画在 B 节点的会话卡下面。
     */
    @Test
    fun agentSessionsQueryNarrowsToOneNodeBecauseTwoNodesMayShareASessionId() {
        registry.recordSession(
            nodeId = agent.nodeId,
            sessionId = "shared-id",
            alias = "本机那个",
            outbound = true,
        )
        registry.recordSession(
            nodeId = desktop.nodeId,
            sessionId = "shared-id",
            alias = "对面那个",
            outbound = false,
        )
        registry.recordEvent(
            nodeId = agent.nodeId,
            sessionId = "shared-id",
            cursor = 1,
            eventType = "message.delta",
            summary = "本机事件",
        )
        registry.recordEvent(
            nodeId = desktop.nodeId,
            sessionId = "shared-id",
            cursor = 1,
            eventType = "message.delta",
            summary = "对面事件",
        )

        // 不带 nodeId：两条都回 —— 调用方只凭 sessionId 分不出谁是谁。这就是要加过滤的理由。
        val unfiltered = obj(get("/v1/agent/sessions?sessionId=shared-id").second)
        assertEquals(2, unfiltered["sessions"]!!.jsonArray.size)

        // 带上 nodeId：列表与事件明细都只剩那一个节点。
        val narrowed = obj(get("/v1/agent/sessions?sessionId=shared-id&nodeId=${desktop.nodeId}").second)
        val rows = narrowed["sessions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, rows.size)
        assertEquals(desktop.nodeId, rows.single()["nodeId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("对面那个", rows.single()["alias"]?.jsonPrimitive?.contentOrNull)

        val events = narrowed["events"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, events.size)
        assertEquals("对面事件", events.single()["summary"]?.jsonPrimitive?.contentOrNull)
        assertEquals(desktop.nodeId, events.single()["nodeId"]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * `total` 必须与 `sessions` 数组**同口径**：两者都由同一个「先按 nodeId、再按 sessionId」
     * 收敛过的列表算出。此前 `total` 用的是只按 `nodeId` 过滤的那一份，于是点名一个会话时
     * 数组回 1 条、`total` 却回该节点的全部会话数 —— 面板的「共 N 个会话」会算错。
     */
    @Test
    fun agentSessionsTotalMatchesWhatTheQueryActuallyReturned() {
        registry.recordSession(nodeId = desktop.nodeId, sessionId = "s-a", outbound = false)
        registry.recordSession(nodeId = desktop.nodeId, sessionId = "s-b", outbound = false)

        val one = obj(get("/v1/agent/sessions?nodeId=${desktop.nodeId}&sessionId=s-a").second)
        assertEquals(1, one["sessions"]!!.jsonArray.size)
        assertEquals(1, one["total"]?.jsonPrimitive?.int, "total 与数组必须同口径")

        val both = obj(get("/v1/agent/sessions?nodeId=${desktop.nodeId}").second)
        assertEquals(2, both["sessions"]!!.jsonArray.size)
        assertEquals(2, both["total"]?.jsonPrimitive?.int)
    }

    /**
     * `agent-session` 的 `state` 是出站唯一需要白名单的字段：它是我们自己写的字符串，
     * 取值域有 `AgentSessionStates.ALL` 这个现成真源。以前不校验，拼错或超长的值会一路走到
     * `deliverAgentFrame` 的自检，换回一句不可操作的「这一帧我们自己都拒收：…」。
     *
     * 断言「报错里回列了合法取值」是有判别力的：走自检那条路的消息里不会有 `archived`。
     */
    @Test
    fun agentSessionWithAnUnknownStateIsRefusedWithAnActionableMessage() {
        val typo = post(
            "/v1/agent/session",
            """{"to":"$LOOPBACK","port":${desktop.boundPort},"sessionId":"s-bad-state","state":"busy-ish"}""",
        )
        assertEquals(400, typo.first, typo.second)
        val typoJson = obj(typo.second)
        assertEquals("bad-request", typoJson["error"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            typoJson["message"]?.jsonPrimitive?.contentOrNull?.contains("archived") == true,
            "报错要回列合法取值：${typo.second}",
        )

        // 拼对了但超过 `Limits.AGENT_STATE = 16`：以前只有自检会打回它（措辞不可操作）。
        val tooLong = post(
            "/v1/agent/session",
            """{"to":"$LOOPBACK","port":${desktop.boundPort},"sessionId":"s-bad-state",""" +
                """"state":"waiting-for-approval"}""",
        )
        assertEquals(400, tooLong.first, tooLong.second)
        assertTrue(
            obj(tooLong.second)["message"]?.jsonPrimitive?.contentOrNull?.contains("archived") == true,
            "超长取值也应当走可操作的白名单拒绝，而不是自检：${tooLong.second}",
        )
    }

    @Test
    fun aPeerWithoutAgn1IsRefusedWithADowngradePath() {
        // §4 的老节点：caps 里没有 agn1，就是"看不懂 agent 帧"。
        val plain = NodeRuntime(
            profile = Profile.mobile(
                nodeId = newId(),
                nick = "只会文本",
                host = "plain",
                ver = "0.61.1",
                caps = listOf("tw1"),
            ),
            udpPort = 0,
            bindHost = LOOPBACK,
        )
        runBlocking { plain.start(scope) }
        try {
            val res = post(
                "/v1/agent/session",
                """{"to":"$LOOPBACK","port":${plain.boundPort},"sessionId":"s-1"}""",
            )
            assertEquals(409, res.first, res.second)
            val json = obj(res.second)
            assertEquals("peer-unsupported", json["error"]?.jsonPrimitive?.contentOrNull)
            // 拒绝必须给降级路径，否则调用方只看到"失败"，只能猜。
            assertTrue(
                json["message"]?.jsonPrimitive?.contentOrNull?.contains("/v1/send") == true,
                "409 应提示改用 /v1/send：${res.second}",
            )
            // 真的什么都没发出去 —— 而不是"发了、对方静默丢了"。
            assertTrue(plain.inbox(0L).none { it.direction == "in" }, "被拒的帧不该出现在对端")
        } finally {
            runBlocking { plain.stop() }
        }
    }

    @Test
    fun malformedAgentFramesAreRefusedBeforeTheyLeave() {
        val base = """"to":"$LOOPBACK","port":${desktop.boundPort}"""
        assertEquals(400, post("/v1/agent/session", "{$base}").first, "缺 sessionId")
        assertEquals(400, post("/v1/agent/event", "{$base,\"sessionId\":\"s-1\"}").first, "缺 cursor")
        assertEquals(
            400,
            post(
                "/v1/agent/event",
                "{$base,\"sessionId\":\"s-1\",\"cursor\":\"42\",\"eventType\":\"x\"}",
            ).first,
            "字符串 cursor：Codec 只认整数，必须在这里就拦下",
        )
        assertEquals(
            400,
            post(
                "/v1/agent/event",
                "{$base,\"sessionId\":\"s-1\",\"cursor\":-1,\"eventType\":\"x\"}",
            ).first,
            "负游标",
        )
        // 这一帧 HTTP 层看不出问题，但 Codec 会拒（agentKind 上限 32 字符）—— 自检必须拦住，
        // 并把收端的理由原样带回来，而不是发出去再等一个永远不来的 ACK。
        //
        // 这里**故意不用 `state`**：`state` 现在由 HTTP 层按 `AgentSessionStates.ALL` 先拦下来
        // （见 `agentSessionWithAnUnknownStateIsRefusedWithAnActionableMessage`），已经走不到自检。
        // 换成同样「HTTP 层看不出问题」的 `agentKind`，才能继续证明自检这条路是活的。
        val tooLongKind = post(
            "/v1/agent/session",
            "{$base,\"sessionId\":\"s-1\",\"agentKind\":\"${"k".repeat(40)}\"}",
        )
        assertEquals(400, tooLongKind.first, tooLongKind.second)
        assertTrue(
            obj(tooLongKind.second)["message"]?.jsonPrimitive?.contentOrNull
                ?.contains("bad-payload:agent-session") == true,
            "自检失败应带回收端的理由：${tooLongKind.second}",
        )
        assertTrue(desktop.inbox(0L).none { it.direction == "in" }, "被拒的帧一帧都不该出去")
    }

    // ------------------------------------------------------------------ 文件报价

    @Test
    fun sendFileTurnsAPathIntoARealOfferOnThePeer() {
        val file = File.createTempFile("relay-controlplane-", ".txt").apply {
            writeText("报价里应该带上真实字节数，而不是 0")
            deleteOnExit()
        }
        val res = post(
            "/v1/send-file",
            """{"to":"$LOOPBACK","port":${desktop.boundPort},"path":"${file.absolutePath}"}""",
        )
        assertEquals(200, res.first, res.second)
        val json = obj(res.second)
        // task-62：这条路由改成「登记即返回」——响应只能说 offering（此刻只保证已登记），
        // 清单有没有真的送到由**后台投递**决定。所以这里**不能**再断言 offered；
        // 投递结果从只读出口 /v1/transfers 读（下面那段），那条断言比原来更强：
        // 原来只验了响应里的一个字，现在验的是投递真的完成。
        assertEquals("offering", json["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(desktop.nodeId, json["to"]?.jsonPrimitive?.contentOrNull)
        // name 缺省取路径末段：调用方不该被迫重复一次文件名。
        assertEquals(file.name, json["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(file.length(), json["size"]?.jsonPrimitive?.long)
        assertTrue(
            json["transferId"]?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true,
            "应回一个 transferId：调用方要靠它对应界面上的卡片",
        )

        // 关键断言①：**投递真的完成**（offering → offered），从只读出口读 ——
        // 没有这条，「登记即返回」就可能把「发不出去」变成「看起来发出去了」。
        val transferId = json["transferId"]!!.jsonPrimitive.content
        val delivery = Await.value("$transferId 的投递不再停在 offering") {
            obj(get("/v1/transfers").second)["transfers"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["transferId"]?.jsonPrimitive?.contentOrNull == transferId }
                ?.takeIf { it["state"]?.jsonPrimitive?.contentOrNull != "offering" }
        }
        assertEquals("offered", delivery["state"]?.jsonPrimitive?.contentOrNull, "投递应到达 offered：" + delivery)

        // 关键断言②：对端真的挂上了一个待处理报价，字段与协议一致。
        // 探测在轮询里重跑，所以 `runBlocking` 放在 probe 内（不是包住整个轮询）。
        val offer = Await.value("对端挂上一条待处理报价") {
            runBlocking { desktop.pendingTransferOffers().firstOrNull() }
        }
        assertEquals(file.name, offer.rootName)
        assertEquals(file.length(), offer.totalSize)
        assertEquals(agent.nodeId, offer.peerId)
    }

    @Test
    fun sendFileCarriesTheGroupReferenceWhenItIsAskedFor() {
        val file = File.createTempFile("relay-controlplane-group-", ".bin").apply {
            writeText("群引用要一路走到对面的报价上")
            deleteOnExit()
        }
        val res = post(
            "/v1/send-file",
            """{"to":"$LOOPBACK","port":${desktop.boundPort},"path":"${file.absolutePath}",""" +
                """"purpose":"share","groupId":"g-1","groupRev":7}""",
        )
        assertEquals(200, res.first, res.second)

        // 这里断言的是"群引用真的到了对面"，不是自动接收那条阈值规则：故意用 share 而不是
        // image —— 小图带 purpose:image 会被对面直接收下，报价随即从待处理里消失，反而看不见。
        // 阈值行为（群图 10MB vs 私聊 20MB）由 core 的 TransferAutoAcceptTest 钉，
        // 发端把群引用写上帧由 TransferOfferGroupRefTest 钉，这里只钉 HTTP 这一层不吞字段。
        // 探测在轮询里重跑，所以 `runBlocking` 放在 probe 内（不是包住整个轮询）。
        val offer = Await.value("对端挂上一条待处理报价") {
            runBlocking { desktop.pendingTransferOffers().firstOrNull() }
        }
        assertEquals("g-1", offer.groupId, "群 id 没走到对面，收端只会当成私聊媒体")
        assertEquals(7, offer.groupRev)
    }

    @Test
    fun sendFileCarriesTheTableTextViewWhenItIsAskedFor() {
        val file = File.createTempFile("relay-controlplane-table-", ".webp").apply {
            writeText("表格图（内容不重要，这条钉的是文字视图那段）")
            deleteOnExit()
        }
        // JSON 字符串里不能直接出现制表符/换行（控制字符必须转义），所以先在 Kotlin 里写成
        // 真实的 \t/\n，再转义进请求体 —— 断言时比的仍是那两个真实的空白字符。
        val tsv = "名称\t数量\n甲\t3"
        val jsonTsv = tsv.replace("\t", "\\t").replace("\n", "\\n")
        val res = post(
            "/v1/send-file",
            """{"to":"$LOOPBACK","port":${desktop.boundPort},"path":"${file.absolutePath}",""" +
                """"tableText":"$jsonTsv"}""",
        )
        assertEquals(200, res.first, res.second)

        // 故意不带 purpose:image —— 1KB 的图带 purpose:image 会被对面自动收下，
        // 报价随即从待处理里消失，反而看不见。这里钉的是 HTTP 这一层不吞字段，
        // 不是自动接收那条规则（阈值行为见 core 的 TransferAutoAcceptTest）。
        // 探测在轮询里重跑，所以 `runBlocking` 放在 probe 内（不是包住整个轮询）。
        val offer = Await.value("对端挂上一条待处理报价") {
            runBlocking { desktop.pendingTransferOffers().firstOrNull() }
        }
        assertEquals(tsv, offer.tableText, "表格文字没走到对面，界面上那个开关就永远不出现")
        // 没超上限，就不该带 truncated 标记。
        assertNotEquals(true, offer.tableTextTruncated)
    }

    @Test
    fun nudgeSendsANudgeFrameAndNeverQueuesIt() {
        val res = post("/v1/nudge", """{"to":"$LOOPBACK","port":${desktop.boundPort}}""")
        assertEquals(200, res.first, "控制面应回 200，实际：${res.second}")
        val json = obj(res.second)
        assertEquals("sent", json["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(desktop.nodeId, json["to"]?.jsonPrimitive?.contentOrNull)

        // 对端收到的必须真的是 nudge，而不是一条空文本消息 —— 两者在线上完全不同：
        // nudge 是无正文帧（`{kind:"nudge"}`），多带一个 text 键它就不是它了
        // （Codec 的校验器会把这种帧整帧丢掉）。
        val received = desktop.inbox(0L).filter { it.direction == "in" }
        assertEquals(1, received.size)
        val payload = obj(received[0].envelopeJson)["payload"]!!.jsonObject
        assertEquals(MsgKinds.NUDGE, payload["kind"]?.jsonPrimitive?.contentOrNull)
        assertTrue(payload.keys.none { it == "text" }, "nudge 不该带正文：${payload.keys}")

        // 协议要求它不进离线补发队列。戳一碰能送达就不该留下任何待补发的东西。
        assertEquals(0, obj(get("/healthz").second)["pending"]?.jsonPrimitive?.int)
    }

    @Test
    fun sendFileRefusesTableTextForAPeerThatDoesNotDeclareIt() {
        // 协议要求发送端只向声明 tbl1 的对端附带这段 TSV。造一个"未声明 tbl1 的老版本对端"：
        // 只声明 tw1，不声明 tbl1。
        val legacy = NodeRuntime(
            profile = Profile.mobile(
                nodeId = newId(),
                nick = "老版本对端",
                host = "legacy-mac",
                ver = "0.44.0",
                caps = listOf(MsgTypes.CAP_MOBILE_TEXT),
            ),
            udpPort = 0,
            bindHost = LOOPBACK,
        )
        runBlocking { legacy.start(scope) }
        try {
            val file = File.createTempFile("relay-controlplane-legacy-", ".webp").apply {
                writeText("图")
                deleteOnExit()
            }
            val res = post(
                "/v1/send-file",
                """{"to":"$LOOPBACK","port":${legacy.boundPort},"path":"${file.absolutePath}",""" +
                    """"tableText":"名称\t数量"}""",
            )
            assertEquals(409, res.first, "对端没声明 tbl1 就不该发，实际：${res.second}")
            assertEquals(
                "peer-unsupported",
                obj(res.second)["error"]?.jsonPrimitive?.contentOrNull,
            )
            // 关键：不是"回了 409 但帧其实发了"。
            assertTrue(
                runBlocking { legacy.pendingTransferOffers() }.isEmpty(),
                "被拦下的请求不该在对端留下任何报价",
            )
        } finally {
            runBlocking { legacy.stop() }
        }
    }

    @Test
    fun sendFileRefusesAHalfGroupReferenceInsteadOfSendingIt() {
        val file = File.createTempFile("relay-controlplane-halfgroup-", ".txt").apply {
            writeText("x")
            deleteOnExit()
        }
        val base = """"to":"$LOOPBACK","port":${desktop.boundPort}","path":"${file.absolutePath}""""
        // 协议只认「都有」或「都没有」（Codec.validateGroupRef）：只给一个就 400，
        // 而不是替调用方猜一个版本号发出去。
        assertEquals(
            400,
            post("/v1/send-file", "{$base,\"groupId\":\"g-1\"}").first,
            "只给 groupId",
        )
        assertEquals(
            400,
            post("/v1/send-file", "{$base,\"groupRev\":7}").first,
            "只给 groupRev",
        )
        assertEquals(
            400,
            post("/v1/send-file", "{$base,\"groupId\":\"g-1\",\"groupRev\":-1}").first,
            "groupRev 为负",
        )
        assertTrue(
            runBlocking { desktop.pendingTransferOffers().isEmpty() },
            "被拒的请求不该在对面留下报价",
        )
    }

    @Test
    fun sendFileRefusesBadInputBeforeItTouchesTheNetwork() {
        val file = File.createTempFile("relay-controlplane-ok-", ".txt").apply {
            writeText("x")
            deleteOnExit()
        }
        // 「不存在的路径」与「目录」都**现造**，不借口机器上的 `/tmp`：
        // 前者若那台机器恰好有这个文件，就会变成"存在的路径"（校验仍 400，但走的是另一支）；
        // 后者在没有 `/tmp` 的机器上会以"路径不存在"过掉同一条断言 —— 两种都是假绿。
        val dir = File.createTempFile("relay-controlplane-dir-", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val missing = File(dir, "relay-does-not-exist-" + dir.name.hashCode() + ".bin")
        assertTrue(!missing.exists(), "临时目录下不该已有这个文件：" + missing)
        val base = """"to":"$LOOPBACK","port":${desktop.boundPort}"""
        assertEquals(400, post("/v1/send-file", "{$base}").first, "缺 path")
        assertEquals(
            400,
            post("/v1/send-file", "{$base,\"path\":\"${missing.absolutePath}\"}").first,
            "不存在的路径",
        )
        assertEquals(
            400,
            post("/v1/send-file", "{$base,\"path\":\"${dir.absolutePath}\"}").first,
            "目录不是普通文件",
        )
        assertEquals(
            400,
            post("/v1/send-file", "{\"path\":\"${file.absolutePath}\"}").first,
            "缺 to",
        )
        assertEquals(
            400,
            post("/v1/send-file", "{$base,\"path\":\"${file.absolutePath}\",\"port\":99999}").first,
            "port 越界",
        )
        // 一个坏请求都不该让对端看到报价 —— 校验必须在握手之前。
        assertTrue(
            runBlocking { desktop.pendingTransferOffers().isEmpty() },
            "被拒的请求不该在对面留下报价",
        )
    }

    @Test
    fun sendFileReportsAnUnreachablePeerInsteadOfPretendingItWasSent() {
        val file = File.createTempFile("relay-controlplane-unreachable-", ".txt").apply {
            writeText("x")
            deleteOnExit()
        }
        // TEST-NET-1 保证不可达。
        val res = post(
            "/v1/send-file",
            """{"to":"192.0.2.1","path":"${file.absolutePath}"}""",
        )
        assertNotEquals(200, res.first)
        assertEquals("false", obj(res.second)["ok"]?.jsonPrimitive?.content)
    }

    // ------------------------------------------------------------------ 辅助

    /** 量一次 `GET /v1/peers` 的耗时（毫秒），并顺带断言它必须是 200。 */
    private fun measurePeersMs(): Long {
        val startedAt = Await.nowMillis()
        val res = get("/v1/peers")
        assertEquals(200, res.first, "阻塞发送占满处理器时 /v1/peers 也必须回 200")
        return Await.nowMillis() - startedAt
    }

    /**
     * 等**对端**（[desktop]）的库里出现这些类型的帧。
     *
     * 注意不能查 agentNode 自己的 `/v1/inbox`：那里记的是它**发出**的行（direction=out），
     * 而这几个用例要验证的是"帧真的到了对面"。
     */

    // ------------------------------------------------- PPE-1 增补 D3 / D7（2026-10-14）

    @Test
    fun agentWorkspaceAnnounceLandsReliablyAndInLedger() {
        val res = post(
            "/v1/agent/workspace",
            "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"wsId\":\"ws-abc123\",\"wsTitle\":\"teahouse-mobile\",\"wsPath\":\"/src/teahouse\",\"agentKind\":\"DSH\",\"online\":true,\"sessionCount\":2}",
        )
        assertEquals(200, res.first, res.second)
        val json = obj(res.second)
        // 这帧在 RELIABLE 里（见 MsgTypes.AGENT_WORKSPACE 的声明处）：回执与 session/event 同语义。
        assertEquals("sent", json["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(MsgTypes.AGENT_WORKSPACE, json["type"]?.jsonPrimitive?.contentOrNull)

        val frames = Await.value("桌面收到 " + MsgTypes.AGENT_WORKSPACE) { desktopFrames.filter { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_WORKSPACE }.takeIf { it.isNotEmpty() } }
        assertEquals(1, frames.size, "桌面侧应收到恰好一条 agent-workspace 帧")
        val payload = frames[0]["payload"]!!.jsonObject
        assertEquals("ws-abc123", payload["wsId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("teahouse-mobile", payload["wsTitle"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, payload["online"]?.jsonPrimitive?.content?.toBoolean())

        // 出站台账（GET /v1/agent/workspaces 的读数）。
        val listed = obj(get("/v1/agent/workspaces").second)
        val rows = listed["workspaces"]!!.jsonArray.map { it.jsonObject }
        val row = rows.firstOrNull { it["wsId"]?.jsonPrimitive?.contentOrNull == "ws-abc123" }
        assertTrue(row != null, "台账里应有这个工作区：$rows")
        // sessionCount 现算：本节点会话台账里没有挂着 ws-abc123 的会话 ⇒ **保留自报的 2**
        // （mergeSessionCounts：现算 0 且自报非空时保留自报 —— "工作区在、会话还没开"
        //  正是 D3 要覆盖的那一档；自报被 0 顶掉就等于这档白宣告了）。
        assertEquals(2, row["sessionCount"]?.jsonPrimitive?.int)
    }

    @Test
    fun agentWorkspaceRejectsMissingWsIdAndBadOnlineShape() {
        assertEquals(400, post("/v1/agent/workspace", "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"wsTitle\":\"没有 id\"}").first)
        // 出站收紧（与 agentSession 的 state 同一纪律）：online 写成字符串 ⇒ 400。
        // 第一版这里用 takeIf 静默丢键 —— 调用方传了值却被悄悄改成"没传"，比拒收更坏。
        assertEquals(
            400,
            post("/v1/agent/workspace", "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"wsId\":\"w\",\"online\":\"true\"}").first,
        )
        // sessionCount 写成字符串同样 400（形状错的自报数 = 假数据）。
        assertEquals(
            400,
            post("/v1/agent/workspace", "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"wsId\":\"w\",\"sessionCount\":\"2\"}").first,
        )
    }

    @Test
    fun grantsReadDefaultsAndWritePolicyGrantRevokeRoundTrip() {
        // 先握手：真实链路里"设备能发来 grant-request"就意味着它已经在节点表里，
        // grantReply 的回包发得出去。本用例直接 PUT grantReply 跳过了申请那步，
        // 所以必须自己把握手补上 —— 否则帧只会入队（QUEUED），桌面侧永远收不到。
        post("/v1/send", "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"text\":\"握手\"}")
        // 重设为出厂默认策略（request，闭合申请制）以检验默认读数
        grantStore.setPolicy(AgentGrantPolicies.REQUEST)
        val before = obj(get("/v1/agent/grants").second)
        assertEquals("request", before["policy"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0, before["grants"]!!.jsonArray.size)
        assertEquals(0, before["requests"]!!.jsonArray.size)

        // 切 byDevice：此后未授权设备收不到 agent 帧（执行点判据在下一个用例里守）。
        val put = putJson("/v1/agent/grants", "{\"policy\":\"byDevice\"}")
        assertEquals(200, put.first, put.second)
        assertEquals("byDevice", obj(put.second)["policy"]?.jsonPrimitive?.contentOrNull)

        // 授权（scope=agent 缺 agentKey 必须 400 —— 缺了它无法归组，不许按整台设备放行）。
        assertEquals(400, putJson("/v1/agent/grants", "{\"grant\":{\"nodeId\":\"n-1\",\"scope\":\"agent\"}}").first)
        val granted = putJson("/v1/agent/grants", "{\"grant\":{\"nodeId\":\"" + desktop.nodeId + "\",\"scope\":\"agent\",\"agentKey\":\"dsh-main\"}}")
        assertEquals(200, granted.first, granted.second)
        val mid = obj(get("/v1/agent/grants").second)
        assertEquals(1, mid["grants"]!!.jsonArray.size)
        val g = mid["grants"]!!.jsonArray[0].jsonObject
        assertEquals("agent", g["scope"]?.jsonPrimitive?.contentOrNull)
        assertEquals("dsh-main", g["agentKey"]?.jsonPrimitive?.contentOrNull)

        // 撤销 + 回给设备（grantReply 带 agent-grant 帧，手机端四档状态靠它翻面）。
        val revoked = putJson(
            "/v1/agent/grants",
            "{\"revoke\":{\"nodeId\":\"" + desktop.nodeId + "\",\"scope\":\"agent\",\"agentKey\":\"dsh-main\"}," +
                "\"grantReply\":{\"nodeId\":\"" + desktop.nodeId + "\",\"state\":\"revoked\",\"scope\":\"agent\",\"agentKey\":\"dsh-main\"}}",
        )
        assertEquals(200, revoked.first, revoked.second)
        assertEquals(0, obj(get("/v1/agent/grants").second)["grants"]!!.jsonArray.size)
        val notes = obj(revoked.second)["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(notes.any { it.contains("只拦未来投递") }, "撤销的诚实边界必须写进回执：$notes")

        // 方向：agent-grant 是宿主 → 设备的帧，判据在桌面侧收下的帧里，不在 agent 自己的 inbox。
        val frames = Await.value("桌面收到 " + MsgTypes.AGENT_GRANT) { desktopFrames.filter { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_GRANT }.takeIf { it.isNotEmpty() } }
        assertEquals(1, frames.size)
        assertEquals("revoked", frames[0]["payload"]!!.jsonObject["state"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun workspaceScopeAndReadOnlyAccessRejectsMutation() {
        post("/v1/send", "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"text\":\"握手\"}")
        putJson("/v1/agent/grants", "{\"policy\":\"byDevice\"}")

        // 授权只读工作区
        val grantRo = putJson(
            "/v1/agent/grants",
            "{\"grant\":{\"nodeId\":\"${desktop.nodeId}\",\"scope\":\"workspace\",\"agentKey\":\"dsh\",\"wsId\":\"ws-1\",\"access\":\"ro\"}}",
        )
        assertEquals(200, grantRo.first, grantRo.second)

        // 读操作（例如 session 宣告）允许通过
        val sessionRes = post(
            "/v1/agent/session",
            "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"sessionId\":\"s-ro\",\"agentKind\":\"dsh\",\"wsId\":\"ws-1\"}",
        )
        assertEquals(200, sessionRes.first, "只读设备允许读取/监听 session：${sessionRes.second}")

        // 写操作（例如 agent/queue 提交任务）必须被 403 拒绝
        val queueRes = post(
            "/v1/agent/queue",
            "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"taskId\":\"t-ro\",\"sessionId\":\"s-ro\",\"op\":\"enqueue\",\"agentKind\":\"dsh\",\"wsId\":\"ws-1\"}",
        )
        assertEquals(403, queueRes.first, "只读设备不允许提交 queue 任务")
        assertTrue(queueRes.second.contains("只读权限"), "错误文案指明只读限制：${queueRes.second}")

        // 升级为读写权限
        val grantRw = putJson(
            "/v1/agent/grants",
            "{\"grant\":{\"nodeId\":\"${desktop.nodeId}\",\"scope\":\"workspace\",\"agentKey\":\"dsh\",\"wsId\":\"ws-1\",\"access\":\"rw\"}}",
        )
        assertEquals(200, grantRw.first, grantRw.second)

        // 再次提交 queue 任务，成功放行
        val queueRwRes = post(
            "/v1/agent/queue",
            "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"taskId\":\"t-rw\",\"sessionId\":\"s-ro\",\"op\":\"enqueue\",\"agentKind\":\"dsh\",\"wsId\":\"ws-1\"}",
        )
        assertEquals(200, queueRwRes.first, "读写设备允许提交 queue 任务：${queueRwRes.second}")
    }

    @Test
    fun notGrantedDeviceIsRefusedBeforeTheFrameEverLeaves() {
        // byDevice + 空白名单 ⇒ 会话宣告 403，而且**一个字节都不出门**（执行点判据）。
        assertEquals(200, putJson("/v1/agent/grants", "{\"policy\":\"byDevice\"}").first)
        val res = post(
            "/v1/agent/session",
            "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"sessionId\":\"s-blocked\",\"agentKind\":\"DSH\"}",
        )
        assertEquals(403, res.first, "未授权必须 403：${res.second}")
        assertEquals("not-granted", obj(res.second)["error"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            desktopFrames.none { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_SESSION },
            "没授权 = 没数据：帧不得出现在对端",
        )
        // 台账同样不许记（403 在 deliverAgentFrame 之前，onSent 根本没机会跑）。
        assertEquals(
            0,
            obj(get("/v1/agent/sessions?sessionId=s-blocked").second)["sessions"]!!.jsonArray.size,
        )

        // 批准后同一条请求必须成功（策略是门，不是墙），而且帧真的到对端。
        assertEquals(200, putJson("/v1/agent/grants", "{\"grant\":{\"nodeId\":\"" + desktop.nodeId + "\",\"scope\":\"all\"}}").first)
        val ok = post(
            "/v1/agent/session",
            "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"sessionId\":\"s-blocked\",\"agentKind\":\"DSH\"}",
        )
        assertEquals(200, ok.first, ok.second)
        val arrived = Await.value("桌面收到 " + MsgTypes.AGENT_SESSION) { desktopFrames.filter { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_SESSION }.takeIf { it.isNotEmpty() } }
        assertEquals("s-blocked", arrived.last()["payload"]!!.jsonObject["sessionId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun grantRequestFromDeviceLandsInTodoAndAnswersWithCurrentList() {
        // 设备（这里借 desktop 节点）主动申请 → 宿主进待办 + 回一份当前清单。
        // 先借一次 /v1/send 握手：desktop 的节点表里没有 agent 时，sendUserMessage
        // 只会入队（QUEUED），申请帧永远到不了 —— 握手是这条链路的前提，不是被测行为。
        post("/v1/send", "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"text\":\"握手\"}")
        runBlocking { desktop.sendAgentGrantRequest(agent.nodeId, "dsh-main", "想在手机上看 DSH 会话") }
        val snap = Await.value("待办里出现设备发来的授权申请") {
            val s = obj(get("/v1/agent/grants").second)
            if (s["requests"]!!.jsonArray.isNotEmpty()) s else null
        }
        val req = snap["requests"]!!.jsonArray[0].jsonObject
        assertEquals(desktop.nodeId, req["nodeId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("dsh-main", req["agentKey"]?.jsonPrimitive?.contentOrNull)

        // 先批一条再让设备申请一次：宿主必须回 agent-grant-list（沉默 ≠ 拒绝，四档可区分）。
        assertEquals(200, putJson("/v1/agent/grants", "{\"grant\":{\"nodeId\":\"" + desktop.nodeId + "\",\"scope\":\"all\"}}").first)
        runBlocking { desktop.sendAgentGrantRequest(agent.nodeId, "dsh-main", "再问一次") }
        val lists = Await.value("桌面收到 " + MsgTypes.AGENT_GRANT_LIST) { desktopFrames.filter { it["type"]?.jsonPrimitive?.contentOrNull == MsgTypes.AGENT_GRANT_LIST }.takeIf { it.isNotEmpty() } }
        assertTrue(lists.isNotEmpty(), "申请后设备应收到当前授权清单（agent-grant-list）")
        assertEquals("all", lists.last()["payload"]!!.jsonObject["scope"]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * 轮询桌面侧收下的帧直到出现 [type]（agent → desktop 方向的帧判据在这里 ——
     * 读 agent 自己收件箱的那个方向反了，测的就是空气）。
     */

    private fun putJson(path: String, body: String): Pair<Int, String> {
        val res = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${plane.boundPort}" + path))
                .timeout(Duration.ofSeconds(30))
                .method("PUT", HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return res.statusCode() to res.body()
    }

    private fun get(path: String, port: Int = plane.boundPort, token: String? = null): Pair<Int, String> {
        val builder = HttpRequest.newBuilder(URI("http://$LOOPBACK:$port$path"))
            .timeout(Duration.ofSeconds(30))
            .GET()
        if (token != null) builder.header("Authorization", "Bearer $token")
        val res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to res.body()
    }

    private fun post(path: String, body: String): Pair<Int, String> {
        val res = client.send(
            HttpRequest.newBuilder(URI("http://$LOOPBACK:${plane.boundPort}$path"))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return res.statusCode() to res.body()
    }

    private fun obj(json: String): JsonObject =
        Codec.wireJson.parseToJsonElement(json).jsonObject

    private fun array(json: String): List<JsonObject> {
        val root = obj(json)
        val arr: JsonArray = root["messages"]?.jsonArray ?: root["peers"]!!.jsonArray
        return arr.map { it.jsonObject }
    }

    /**
     * task-35：**阻塞式 POST 占满处理器线程池时，/v1/peers 仍必须快**。
     *
     * 真机现场：手机切到移动网后，本机 127.0.0.1:8788 连续 5 次 HTTP 000、每次卡满 8.00s，
     * 切回 WiFi 立刻恢复。**8.00s 正是"给一个不可达对端发消息"的完整退避**（UDP 1+2+4 秒
     * 再加 TCP 兜底），而控制面的 POST 处理器是 `runBlocking { ... }` —— 一个线程被按住整整
     * 8 秒。池子只有 4 条线程，几条这样的请求就能把**读**路由（/v1/peers）一起饿死，
     * 而那正是观测跨网状态的唯一手段（"暂不可读"就是这么来的）。
     */
    @Test
    fun peersStaysFastEvenWhenBlockingSendsFillTheHandlerPool() {
        // 预算相对**本机基线**：绝对阈值（原来写死 1s）在慢机器/并发 CI 上是假红 ——
        // 那台机器空载读一次也要 300ms 时，"< 1s" 测的就不是池子饿死而是机器快慢。
        // 上限仍钉在 [MAX_PEERS_MS]（1s），远小于黑洞退避（约 8s），所以「真被饿死」照样红。
        val baselineMs = (1..3).minOf { measurePeersMs() }
        val budgetMs = minOf(MAX_PEERS_MS, maxOf(baselineMs * 20, 1_000L))

        val entered = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = java.util.concurrent.CountDownLatch(1)
        val sender = java.util.concurrent.Executors.newFixedThreadPool(STORM_SENDERS)
        val futures = (1..STORM_SENDERS).map {
            sender.submit {
                gate.await()
                // 计数放在 submit 之后、post 之前：post 会一直卡到黑洞退避结束（约 8s），
                // 所以「计到 6」= 六条都真的在等回包，线程池已经被占住。
                entered.incrementAndGet()
                runCatching { post("/v1/send", STORM_SEND_BODY) }
            }
        }
        gate.countDown()
        try {
            // **不睡固定时长。** 过去是 Thread.sleep(800) 赌「800ms 足够让它们进到处理器」：
            // 慢机器上赌输就读到"池子还没满"的假绿，快机器上白等。现在等到可观测信号。
            Await.condition(
                "${STORM_SENDERS} 条阻塞 POST 都已进入",
                timeoutMs = STORM_ENTER_TIMEOUT_MS,
            ) { entered.get() >= STORM_SENDERS }

            val worst = (1..5).maxOf { measurePeersMs() }

            // 采样时这六条必须**仍然在途**：否则测的是"风暴过去之后读很快"，与缺陷无关。
            assertEquals(
                STORM_SENDERS,
                futures.count { !it.isDone },
                "采样时阻塞发送必须仍在途 —— 都结束了这条测试就证明不了「池子被占满时读还快」",
            )
            assertTrue(
                worst < budgetMs,
                "阻塞发送占满处理器时 /v1/peers 最慢 ${worst}ms（基线 ${baselineMs}ms，预算 ${budgetMs}ms）",
            )
        } finally {
            futures.forEach { it.cancel(true) }
            sender.shutdownNow()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /** 读路由的单次上限（真机验收是 < 200ms；这里给 CI 留余量）。也是相对预算的封顶。 */
        const val MAX_PEERS_MS = 1_000L

        /** 黑洞风暴的并发数：控制面处理器池只有 4 条线程，6 条足以压满并溢出。 */
        const val STORM_SENDERS = 6

        /** 等六条阻塞请求都进到 `send` 的上限（只是"线程起来"，不是等退避结束）。 */
        const val STORM_ENTER_TIMEOUT_MS = 10_000L

        /** TEST-NET-1（RFC 5737）保证不可达：不会有 ACK，请求会一直卡到退避结束。 */
        const val STORM_SEND_BODY = """{"to":"192.0.2.1","port":17878,"text":"黑洞"}"""

        /** 三次「加载更多」逐步落队列的上限。 */
        const val SESSION_PAGE_TIMEOUT_MS = 5_000L
    }


    // ------------------------------------------------- PPE-1 增补 D8（2026-10-14）

    /**
     * 设备发来的 `agent-session-page` 必须**留在队列里等宿主来读**。
     *
     * 为什么这条值得单测：这帧的应答方**不是节点** —— 会话历史只有宿主（DSH 插件）知道，
     * 节点只转发。所以"收到就记下来"这一环断了，表现是手机点了「加载更多」永远没反应，
     * 而节点侧一切正常（帧收到、ACK 回了、日志干净）。
     */
    @Test
    fun agentSessionPageRequestQueuesForHost() {
        // 先借一次 /v1/send 握手：desktop 的节点表里没有 agent 时 sendReliableNoQueue
        // 拿不到对方的 ip/port（registry.get 为空 ⇒ 直接 false），请求帧根本发不出去。
        // 握手是这条链路的前提，不是被测行为（与 grant 那条测试同一条注记）。
        post("/v1/send", "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"text\":\"握手\"}")
        runBlocking {
            desktop.sendAgentSessionPage(agent.nodeId, wsId = "ws-page-1", offset = 0, limit = 10)
        }
        val listed = Await.value("宿主队列里出现会话分页请求") {
            val body = obj(get("/v1/agent/session-pages").second)
            body.takeIf { (it["total"]?.jsonPrimitive?.int ?: 0) > 0 }
        }
        val rows = listed["requests"]!!.jsonArray.map { it.jsonObject }
        val row = rows.first { it["wsId"]?.jsonPrimitive?.contentOrNull == "ws-page-1" }
        assertEquals(0, row["offset"]?.jsonPrimitive?.int)
        assertEquals(10, row["limit"]?.jsonPrimitive?.int)
        assertEquals(desktop.profile.nodeId, row["nodeId"]?.jsonPrimitive?.contentOrNull)

        // 宿主答完销掉它；再销一次幂等（removed=false，不报错）。
        val id = row["id"]!!.jsonPrimitive.long
        val first = obj(post("/v1/agent/session-pages/resolve", "{\"id\":$id}").second)
        assertEquals(true, first["removed"]?.jsonPrimitive?.content?.toBoolean())
        val second = obj(post("/v1/agent/session-pages/resolve", "{\"id\":$id}").second)
        assertEquals(false, second["removed"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(0, obj(get("/v1/agent/session-pages").second)["total"]?.jsonPrimitive?.int)
    }

    /**
     * 同一个 `(nodeId, wsId)` 连点「加载更多」只留**最新那条** —— 否则用户在手机上
     * 快速点几下就会在队列里堆一串互相比老的请求，宿主逐条答完还会发出重复页。
     */
    @Test
    fun agentSessionPageKeepsOnlyTheNewestPerWorkspace() {
        post("/v1/send", "{\"to\":\"127.0.0.1\",\"port\":${desktop.boundPort},\"text\":\"握手\"}")
        // **每一发都先看到它进了队列，再发下一发。**
        //
        // 过去是 `send; Thread.sleep(120)` 连发三次，再 `awaitUntil { total > 0 }` 收尾：
        // 慢机器上第三发可能还没到，而 total>0 早被第一发满足了 —— 断言拿到的是 offset=0，
        // 于是这条用例以"队列只留最新"的名字红在一个与时序无关的地方。
        for (offset in listOf(0L, 10L, 20L)) {
            runBlocking {
                desktop.sendAgentSessionPage(agent.nodeId, wsId = "ws-page-2", offset = offset, limit = 10)
            }
            Await.condition("ws-page-2 的请求落在 offset=$offset", SESSION_PAGE_TIMEOUT_MS) {
                sessionPageOffsetOrNull("ws-page-2") == offset
            }
        }

        val rows = obj(get("/v1/agent/session-pages").second)["requests"]!!.jsonArray.map { it.jsonObject }
        val mine = rows.filter { it["wsId"]?.jsonPrimitive?.contentOrNull == "ws-page-2" }
        assertEquals(1, mine.size, "同一工作区只该留最新一条：$mine")
        assertEquals(20, mine[0]["offset"]?.jsonPrimitive?.int, "留下的该是最后那条（offset=20）")
    }

    /** 队列里该 ws 那条请求的 offset；还没有就返回 null（给 [Await.condition] 轮询）。 */
    private fun sessionPageOffsetOrNull(wsId: String): Long? =
        obj(get("/v1/agent/session-pages").second)["requests"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["wsId"]?.jsonPrimitive?.contentOrNull == wsId }
            ?.get("offset")?.jsonPrimitive?.long

    /** 控制面读数在空队列时也要是合法形状（否则手机/宿主读到 null 会当成"端点坏了"）。 */
    @Test
    fun agentSessionPageListIsReadableWhenEmpty() {
        val body = obj(get("/v1/agent/session-pages").second)
        assertEquals(0, body["total"]?.jsonPrimitive?.int)
        assertEquals(0, body["requests"]!!.jsonArray.size)
    }

    /** resolve 缺 id ⇒ 400（形状错的请求别当成"销掉了"）。 */
    @Test
    fun agentSessionPageResolveRejectsMissingId() {
        assertEquals(400, post("/v1/agent/session-pages/resolve", "{}").first)
    }

}
