// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent

import com.kapp.tenon.core.protocol.Codec
import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.testsupport.GuardSourceScan
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject as toJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Antigravity hook 桥（形态 B）四条回环路由的全组用例 —— 沿 [ControlPlaneTest] 的
 * 双节点夹具（desktop = 声明 agq1 的手机侧对端，收帧断言宣告真的出门了）。
 *
 * 钉的四件事：
 * 1. SessionStart/Stop 落台账（手机看板的「正在运行」）且 agent-session 帧真的发到对端；
 * 2. PreToolUse：免批表**短路不打扰**（零帧出门）；非免批 → 审批帧出门 + ack 落台账后
 *    长轮询返回 allow；**超时 = deny（fail-closed）**；
 * 3. 注入池按 RFC §4 契约返回（injectSteps），拉取即清空；wsPath 别名寻池；
 * 4. feed 找不到活跃会话 = 404（不静默吞 —— 投丢了比拒绝更糟）。
 */
class AgentHookBridgeTest {

    private val loopback = "127.0.0.1"
    private lateinit var scope: CoroutineScope
    private lateinit var desktop: com.kapp.tenon.core.session.NodeRuntime
    private lateinit var agent: com.kapp.tenon.core.session.NodeRuntime
    private lateinit var registry: AgentSessionRegistry
    private lateinit var toolStore: AgentToolApprovalStore
    private lateinit var plane: ControlPlane
    private val desktopFrames = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
    private val client = HttpClient.newHttpClient()

    private val grantFile: java.io.File = java.io.File.createTempFile("hook-grants-", ".json").apply { delete() }
    private val toolFile: java.io.File = java.io.File.createTempFile("hook-tools-", ".json").apply { delete() }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        desktop = com.kapp.tenon.core.session.NodeRuntime(
            profile = com.kapp.tenon.core.protocol.Profile.mobile(
                nodeId = com.kapp.tenon.core.protocol.newId(), nick = "手机侧", host = "phone", ver = "0.61.1",
            ),
            udpPort = 0, bindHost = loopback,
        )
        agent = com.kapp.tenon.core.session.NodeRuntime(
            profile = com.kapp.tenon.core.protocol.Profile.mobile(
                nodeId = com.kapp.tenon.core.protocol.newId(), nick = "宿主", host = "host", ver = "relay-0.1.0",
            ),
            udpPort = 0, bindHost = loopback,
        )
        runBlocking {
            desktop.start(scope)
            agent.start(scope)
        }
        registry = AgentSessionRegistry()
        toolStore = AgentToolApprovalStore(java.io.File(toolFile.absolutePath + "." + System.nanoTime()))
        scope.launch { desktop.inbound.collect { env -> desktopFrames += Codec.encode(env).let(Codec.wireJson::parseToJsonElement).toJsonObject } }
        plane = ControlPlane(
            agent, httpPort = 0, token = "", loadProbe = { null },
            sessions = registry,
            grants = AgentGrantStore(java.io.File(grantFile.absolutePath + "." + System.nanoTime())),
            toolApprovals = toolStore,
        )
        plane.start()
        // hook 审批的收件人解析按 registry 的在线对端找 —— 先让两侧握手成邻居
        // （/v1/send 的既有路径：一帧 text，ACK 回来 = 对端注册 + 在线）。
        val (code, body) = post(
            "/v1/send",
            """{"to":"$loopback","port":${desktop.boundPort},"text":"hook-bridge-handshake"}""",
        )
        check(code == 200) { "夹具握手失败：$code $body" }
        // 在线状态是 ACK 之后 registry 的事 —— 给协议栈一点收敛时间（轮询，不赌时序）。
        // 上限用协程 withTimeout，不读机器时钟（envDependentTestGate 的 A 类口径）。
        runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                while (
                    agent.peerList().none {
                        it.online && it.profile?.caps?.contains(com.kapp.tenon.core.protocol.MsgTypes.CAP_AGENT_QUEUE) == true
                    }
                ) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        plane.stop()
        runBlocking {
            agent.stop()
            desktop.stop()
        }
        scope.cancel()
    }

    private fun post(path: String, body: String): Pair<Int, String> {
        val res = client.send(
            HttpRequest.newBuilder(URI("http://$loopback:${plane.boundPort}$path"))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return res.statusCode() to res.body()
    }

    private fun obj(json: String): JsonObject = Codec.wireJson.parseToJsonElement(json).toJsonObject

    private fun field(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.content

    @Test
    fun sessionStartRecordsLocallyAndAnnouncesToThePhonePeer() {
        desktopFrames.clear()
        val (code, body) = post(
            "/v1/agent/hook/session",
            """{"event":"start","conversationId":"c-1","wsPaths":["/repo/tea"],"title":"Antigravity 会话"}""",
        )
        assertEquals(200, code, body)
        assertEquals("agy-c-1", field(obj(body), "sessionId"))
        assertEquals(field(obj(body), "announcedTo"), desktop.profile.nodeId, "宣告命中了声明 agq1 的对端")
        val record = registry.sessionOrNull(runtimeNodeId(), "agy-c-1")
        assertTrue(record != null, "会话落本机台账（手机看板的数据源）")
        assertTrue(desktopFrames.any { it["type"]?.jsonPrimitive?.content == "agent-session" }, "agent-session 帧真的出门")
        // Stop：状态翻 done，注入池回收。
        val (stopCode, stopBody) = post("/v1/agent/hook/session", """{"event":"stop","conversationId":"c-1"}""")
        assertEquals(200, stopCode, stopBody)
        assertEquals("done", registry.sessionOrNull(runtimeNodeId(), "agy-c-1")?.state)
    }

    @Test
    fun preToolUseShortCircuitsOnAlwaysAllowWithoutTouchingThePhone() {
        toolStore.remember(desktop.profile.nodeId, "antigravity", "run_command", grantedBy = "test", at = 0L)
        desktopFrames.clear()
        val (code, body) = post(
            "/v1/agent/hook/approval",
            """{"tool":"run_command","summary":"ls","timeoutMs":3000}""",
        )
        assertEquals(200, code, body)
        assertEquals("allow", field(obj(body), "decision"))
        assertTrue(field(obj(body), "reason")!!.contains("always-allow"))
        assertTrue(desktopFrames.isEmpty(), "免批短路 = 一帧都不打扰手机")
    }

    @Test
    fun preToolUseWaitsForTheAckThenAllows() {
        // 模拟手机点批准：监听桌面上到的 agent-approval 帧，取**真 approvalId**，
        // 按入站 ack 的落账路径推进 status（长轮询看到的正是它）。
        Thread {
            for (i in 0 until 50) {
                val frame = desktopFrames.firstOrNull {
                    it["type"]?.jsonPrimitive?.content == "agent-approval"
                }
                if (frame != null) {
                    val approvalId = frame["payload"]!!.toJsonObject["approvalId"]!!.jsonPrimitive.content
                    registry.recordApproval(
                        nodeId = desktop.profile.nodeId, sessionId = "agy-c-2", approvalId = approvalId,
                        expiresAt = Long.MAX_VALUE / 2, status = "approved", outbound = false,
                    )
                    return@Thread
                }
                Thread.sleep(100)
            }
        }.start()
        val (code, body) = post(
            "/v1/agent/hook/approval",
            """{"tool":"git push","summary":"推到远端","conversationId":"c-2","timeoutMs":8000}""",
        )
        assertEquals(200, code, body)
        assertEquals("allow", field(obj(body), "decision"), body)
        assertTrue(desktopFrames.any { it["type"]?.jsonPrimitive?.content == "agent-approval" }, "审批卡推到了手机")
    }

    /** 对齐键（提案 §9 阶段三）：hook 带真实 sessionId 时，审批帧挂在派单会话上并回带 convId。 */
    @Test
    fun hookApprovalHonorsInjectedSessionIdAndReportsConvId() {
        // 派单侧绑定（executeCliTask/executePluginTask 落账的形状）。
        registry.bindConversation("sess-real-1", "group:g1")
        Thread {
            for (i in 0 until 50) {
                val frame = desktopFrames.firstOrNull {
                    it["type"]?.jsonPrimitive?.content == "agent-approval"
                }
                if (frame != null) {
                    val payload = frame["payload"]!!.toJsonObject
                    if (payload["sessionId"]!!.jsonPrimitive.content == "sess-real-1") {
                        registry.recordApproval(
                            nodeId = desktop.profile.nodeId, sessionId = "sess-real-1",
                            approvalId = payload["approvalId"]!!.jsonPrimitive.content,
                            expiresAt = Long.MAX_VALUE / 2, status = "approved", outbound = false,
                        )
                    }
                    return@Thread
                }
                Thread.sleep(100)
            }
        }.start()
        val (code, body) = post(
            "/v1/agent/hook/approval",
            """{"tool":"git push","summary":"对齐键透传","conversationId":"c-9","sessionId":"sess-real-1","timeoutMs":8000}""",
        )
        assertEquals(200, code, body)
        assertEquals("allow", field(obj(body), "decision"), body)
        assertEquals("group:g1", field(obj(body), "convId"), "响应回带派单侧绑定的会话键：" + body)
        // 审批帧的 sessionId 是真实会话——不是 agy-c-9 合成池键（手机才能归位审批卡）。
        val approvalFrame = desktopFrames.first { it["type"]?.jsonPrimitive?.content == "agent-approval" }
        assertEquals(
            "sess-real-1",
            approvalFrame["payload"]!!.toJsonObject["sessionId"]!!.jsonPrimitive.content,
            "审批帧必须带派单会话（对齐键的意义就在这）",
        )
    }

    @Test
    fun preToolUseTimesOutToDenyFailClosed() {
        val (code, body) = post(
            "/v1/agent/hook/approval",
            """{"tool":"rm -rf /","summary":"危险动作没人批","timeoutMs":1500}""",
        )
        assertEquals(200, code, body)
        assertEquals("deny", field(obj(body), "decision"), "没人批 = deny（fail-closed）")
        // 「真的等了窗口」不用真实时长断言（那是赌时序）—— 1.5s 的租约 + deny 结果
        // 已经把「超时出队」这条路径钉住，长轮询实现是生产代码里的 deadline 循环。
    }

    @Test
    fun hookQuestionWaitsForCardActionThenReturnsAnswer() {
        var publishedGroup: String? = null
        var publishedCardJson: String? = null
        plane.groupCardPublisher = { gid, _, json ->
            publishedGroup = gid
            publishedCardJson = json
        }
        Thread {
            for (i in 0 until 50) {
                val cardJson = publishedCardJson
                if (cardJson != null) {
                    val cardId = field(obj(cardJson), "cardId")
                    if (cardId != null) {
                        Thread.sleep(50)
                        registry.recordCardAction(cardId, actionId = "深色科技 (推荐)", decidedBy = desktop.profile.nodeId)
                        return@Thread
                    }
                }
                Thread.sleep(50)
            }
        }.start()

        val (code, body) = post(
            "/v1/agent/hook/question",
            """{"groupId":"group-weather","question":"请选择方案","options":["深色科技 (推荐)","浅色极简"],"timeoutMs":8000}""",
        )
        assertEquals(200, code, body)
        assertEquals("group-weather", publishedGroup)
        assertTrue(publishedCardJson?.contains("ask_question") == true, "发布了 ask_question 卡片")
        assertEquals("answered", field(obj(body), "status"), body)
        assertEquals("深色科技 (推荐)", field(obj(body), "answer"), body)
        assertEquals(desktop.profile.nodeId, field(obj(body), "decidedBy"), body)
    }

    @Test
    fun hookQuestionTimesOutFailClosed() {
        val (code, body) = post(
            "/v1/agent/hook/question",
            """{"groupId":"group-weather","question":"没人选的选项","options":["A","B"],"timeoutMs":1200}""",
        )
        assertEquals(200, code, body)
        assertEquals("timeout", field(obj(body), "status"), "没人选 = timeout（fail-closed）")
        assertEquals("deny", field(obj(body), "decision"))
    }

    /**
     * 控制面源码的**整包**活代码（剥注释）。
     *
     * ARP-6 把 `ControlPlane` 按资源拆成了 10 个文件（卡片动作 / 租约 / 各资源路由），
     * 于是「读某个文件」的守卫会在拆分时红，而它红的原因与它守的性质无关。
     * 判据问的是「这个模块有没有」，不是「那个文件的第几行有没有」。
     */
    private fun planeSources(): String =
        GuardSourceScan.livePackageOf("agentNode/src/main/kotlin/com/kapp/tenon/agent")

    @Test
    fun dispatchPromptClampIsWiredAtTheEntry() {
        // T-G6：预算裁剪的唯一消费点 = executePluginTask 入口（§7.7 第一层）。
        assertTrue(
            planeSources().contains("TaskPromptBudget.clamp(rawPrompt)"),
            "派单 prompt 没过预算裁剪 —— 上游长上下文会原样灌进执行侧",
        )
    }

    @Test
    fun cliTaskExecutionResolvesByAliasAndRecordsTheLedger() = kotlinx.coroutines.runBlocking {
        // E1：@antigravity → agy-cli 别名解析 + 事件映射 + 台账终态。
        // 夹具吐真 NDJSON 行（/bin/echo 的裸文本不是 JSON，行映射如实跳过）。
        val cliBin = java.nio.file.Files.createTempDirectory("e1-cli").toFile().resolve("agy")
        cliBin.writeText("#!/bin/sh\nprintf '{\"type\":\"text\",\"text\":\"cli hello\"}\\n'\nexit 0\n")
        kotlin.test.assertTrue(cliBin.setExecutable(true))
        val cliRegistry = com.kapp.tenon.agent.drivers.DriverRegistry().also { r ->
            r.register(com.kapp.tenon.agent.drivers.CliSubprocessDriver(
                driverId = "agy-cli", displayName = "Antigravity (agy CLI)",
                cliPath = cliBin.absolutePath, argSpec = { listOf(it) },
            ))
        }
        val planeWithCli = ControlPlane(
            agent, httpPort = 0, token = "", sessions = registry,
            loadProbe = { null }, cliDrivers = cliRegistry,
        )
        val events = mutableListOf<Triple<String, Long, String?>>()
        val (sessionId, status, error) = planeWithCli.executeCliTask(
            "antigravity", "hello from e2e", null, null,
        ) { type, cursor, summary, body -> events.add(Triple(type, cursor, body)) }
        assertTrue(sessionId != null, "别名解析命中（antigravity → agy-cli）：$error")
        assertEquals("finished", status, "退出码 0 = finished（$error）")
        assertTrue(events.any { it.first == "token.delta" }, "行事件进台账")
        val record = registry.sessionOrNull(agent.profile.nodeId, sessionId!!)
        assertEquals("agy-cli", record?.agentKind, "会话台账记驱动身份")
        // 反向验证锚：解析不到的 agentKey = 如实 error 不静默。
        val (sid2, status2, error2) = planeWithCli.executeCliTask(
            "no-such-agent", "x", null, null,
        ) { _, _, _, _ -> }
        assertTrue(sid2 == null && status2 == "error", "未知 agent 如实报错：$error2")
    }

    /** 派单侧绑定（§9 阶段三）：executeCliTask 带 convId 时，sessionId → 会话键入账。 */
    @Test
    fun cliTaskBindsConversationForApprovalAlignment() = kotlinx.coroutines.runBlocking {
        val cliBin = java.nio.file.Files.createTempDirectory("align-cli").toFile().resolve("agy")
        cliBin.writeText("#!/bin/sh\nexit 0\n")
        kotlin.test.assertTrue(cliBin.setExecutable(true))
        val cliRegistry = com.kapp.tenon.agent.drivers.DriverRegistry().also { r ->
            r.register(com.kapp.tenon.agent.drivers.CliSubprocessDriver(
                driverId = "agy-cli", displayName = "Antigravity (agy CLI)",
                cliPath = cliBin.absolutePath, argSpec = { listOf(it) },
            ))
        }
        val planeWithCli = ControlPlane(
            agent, httpPort = 0, token = "", sessions = registry,
            loadProbe = { null }, cliDrivers = cliRegistry,
        )
        val (sessionId, status, error) = planeWithCli.executeCliTask(
            "antigravity", "alignment probe", null, null,
            convId = "group:g1",
        ) { _, _, _, _ -> }
        assertEquals("finished", status, error)
        assertEquals(
            "group:g1",
            registry.conversationOf(sessionId!!),
            "派单侧的会话键必须入账——hook 审批按它归位",
        )
    }

    @Test
    fun hookApprovalDefaultTimeoutAlignsTheFiveMinuteTtl() {
        // D-032：hook 审批的缺省等待预算 = Limits.APPROVAL_TTL_MS（5 分钟）。
        // 判据走「超时语义」：timeoutMs 缺省时用例不传 —— 但 5 分钟太长，这里改为
        // 直接断言常量被消费（源码级：缺省表达式引用 Limits.APPROVAL_TTL_MS）。
        assertTrue(
            planeSources().contains("?: Limits.APPROVAL_TTL_MS.toDouble()"),
            "hook 审批缺省等待没有对齐 5 分钟 TTL（D-032）",
        )
    }

    @Test
    fun approvalListenerFiresOnDecisionAndUnsubscribes() {
        var received: Pair<String, String>? = null
        val dispose = registry.addApprovalListener { id, status -> received = id to status }
        registry.recordApproval(
            nodeId = desktop.profile.nodeId, sessionId = "s-listen", approvalId = "a-1",
            expiresAt = Long.MAX_VALUE / 2, status = "approved", outbound = false,
        )
        assertEquals("a-1" to "approved", received, "决定落账即触发监听（长轮询的唤醒源）")
        received = null
        dispose()
        registry.recordApproval(
            nodeId = desktop.profile.nodeId, sessionId = "s-listen", approvalId = "a-2",
            expiresAt = Long.MAX_VALUE / 2, status = "denied", outbound = false,
        )
        assertEquals(null, received, "注销后不再触发（订阅/注销成对）")
        // pending/sent 是中间态：不触发（只有决定才唤醒长轮询）。
        var midState: Pair<String, String>? = null
        val dispose2 = registry.addApprovalListener { id, status -> midState = id to status }
        registry.recordApproval(
            nodeId = desktop.profile.nodeId, sessionId = "s-listen", approvalId = "a-3",
            expiresAt = Long.MAX_VALUE / 2, status = "sent", outbound = true,
        )
        assertEquals(null, midState, "中间态（sent）不触发")
        dispose2()
    }

    @Test
    fun injectPoolFeedsByWsAliasAndDrainsOnPull() {
        post("/v1/agent/hook/session", """{"event":"start","conversationId":"c-3","wsPaths":["/repo/tea"]}""")
        val (feedCode, feedBody) = post(
            "/v1/agent/hook/inject-feed",
            """{"wsPath":"/repo/tea","message":"顺便加一下单测"}""",
        )
        assertEquals(202, feedCode, feedBody)
        post("/v1/agent/hook/inject-feed", """{"wsPath":"/repo/tea","message":"保持风格一致"}""")
        val (pullCode, pullBody) = post("/v1/agent/hook/inject", """{"conversationId":"c-3"}""")
        assertEquals(200, pullCode, pullBody)
        val payload = obj(pullBody)
        assertEquals("continue", field(payload, "decision"))
        val steps = payload["injectSteps"].toString()
        assertTrue(steps.contains("顺便加一下单测") && steps.contains("保持风格一致"), "两条都按 RFC §4 契约返回：$steps")
        // 拉取即清空：第二次是空的。
        val (_, second) = post("/v1/agent/hook/inject", """{"conversationId":"c-3"}""")
        assertEquals("[]", obj(second)["injectSteps"].toString(), "拉取即清空（同一条注入绝不重复喂给模型）")
    }

    @Test
    fun hookCardLandsInTheSessionLedgerAndRefusesTheBadShapes() {
        post("/v1/agent/hook/session", """{"event":"start","conversationId":"c-4","wsPaths":["/repo/tea"]}""")
        val card = "{\"cardId\":\"k-1\",\"rev\":1,\"type\":\"status\",\"title\":\"编译完成\"}"
        val (code, body) = post(
            "/v1/agent/hook/card",
            "{\"conversationId\":\"c-4\",\"card\":\"" + card.replace("\"", "\\\"") + "\"}",
        )
        assertEquals(200, code, body)
        assertEquals("true", field(obj(body), "recorded"))
        val cardEvents = registry.events(runtimeNodeId(), "agy-c-4").filter { it.eventType == "card" }
        assertEquals(1, cardEvents.size, "card 事件落会话台账（手机会话详情的渲染源）")
        assertTrue(cardEvents.single().body!!.contains("编译完成"))
        // 没有宣告的会话 → 404，不替它造会话。
        val (missCode, missBody) = post(
            "/v1/agent/hook/card",
            """{"conversationId":"c-missing","card":"{}"}""",
        )
        assertEquals(404, missCode, missBody)
        // 预算：超限拒绝不截断（§6.12 同一把尺子）。
        val oversized = "x".repeat(com.kapp.tenon.core.protocol.Limits.CARD_BYTES + 10)
        val (tooBigCode, tooBigBody) = post(
            "/v1/agent/hook/card",
            """{"conversationId":"c-4","card":"{\"cardId\":\"k-2\",\"pad\":\"$oversized\"}"}""",
        )
        assertEquals(400, tooBigCode, tooBigBody.take(120))
        assertTrue(tooBigBody.contains("card-too-large"))
    }

    @Test
    fun injectFeedWithoutALiveSessionIsRejectedLoudly() {
        val (code, body) = post("/v1/agent/hook/inject-feed", """{"wsPath":"/no/such/ws","message":"hello"}""")
        assertEquals(404, code, body)
        assertTrue(field(obj(body), "error")!!.contains("no-session"))
    }

    private fun runtimeNodeId(): String = agent.profile.nodeId
}
