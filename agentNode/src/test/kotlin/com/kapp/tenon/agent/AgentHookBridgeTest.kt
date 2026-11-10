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
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
import com.kapp.tenon.agent.udpTestTransport

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
            messengerTransportFactory = udpTestTransport(),
            profile = com.kapp.tenon.core.protocol.Profile.mobile(
                nodeId = com.kapp.tenon.core.protocol.newId(), nick = "手机侧", host = "phone", ver = "0.61.1",
            ),
            udpPort = 0, bindHost = loopback,
        )
        agent = com.kapp.tenon.core.session.NodeRuntime(
            messengerTransportFactory = udpTestTransport(),
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
            // 空驱动表：只读通道的端到端用例要往里注册一个探针驱动（其余用例不解析
            // CLI，`resolveCliDriver` 返回 null 的既有结论不变）。
            cliDrivers = com.kapp.tenon.agent.drivers.DriverRegistry(),
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

    /**
     * 反向钉子：**没对账通过**的 sessionId 不许当会话键。
     *
     * `sessionId` 是请求体里的字符串，回环上任意进程都能填。旧实现直接拿它当键，
     * 于是「这条对话正在请求 git push」可以被伪造到别人的会话上 —— 比一张匿名审批卡
     * 更容易被点通过。现在只认本节点派单出去的会话，其余回退派生键（`agy-<convId>`）。
     */
    @Test
    fun hookApprovalIgnoresASessionIdThisHostNeverDispatched() {
        val forged = "sess-forged-" + System.nanoTime()
        Thread {
            for (i in 0 until 50) {
                val frame = desktopFrames.firstOrNull {
                    it["type"]?.jsonPrimitive?.content == "agent-approval"
                }
                if (frame != null) {
                    val payload = frame["payload"]!!.toJsonObject
                    registry.recordApproval(
                        nodeId = desktop.profile.nodeId,
                        sessionId = payload["sessionId"]!!.jsonPrimitive.content,
                        approvalId = payload["approvalId"]!!.jsonPrimitive.content,
                        expiresAt = Long.MAX_VALUE / 2, status = "approved", outbound = false,
                    )
                    return@Thread
                }
                Thread.sleep(100)
            }
        }.start()
        val (code, body) = post(
            "/v1/agent/hook/approval",
            """{"tool":"git push","summary":"冒名挂会话","conversationId":"c-7","sessionId":"$forged","timeoutMs":8000}""",
        )
        assertEquals(200, code, body)
        assertEquals("allow", field(obj(body), "decision"), body)

        val frame = desktopFrames.first { it["type"]?.jsonPrimitive?.content == "agent-approval" }
        assertEquals(
            "agy-c-7",
            frame["payload"]!!.toJsonObject["sessionId"]!!.jsonPrimitive.content,
            "没对账通过的 sessionId 必须被丢掉、回退到派生键（否则任意进程都能冒名挂会话）",
        )
        assertTrue(
            !body.contains("\"convId\""),
            "没对账通过就不许回带 convId —— 否则等于把「任意字符串 → 某条真实对话」的映射送出去：" + body,
        )
    }

    /**
     * 触发器派单**没有 convId**，但对齐键仍必须生效。
     *
     * `executeCliTask(convId = null)` 只 `recordSession(outbound = true)`、不
     * `bindConversation`。若对账判据只认 `conversationOf`，这类会话会被误判成伪造、
     * 静默退化回 `agy-*` 池键 —— C13 的对齐键就白做了。
     */
    @Test
    fun hookApprovalAcceptsASessionThisHostDispatchedEvenWithoutConvId() {
        val ours = "sess-trigger-" + System.nanoTime()
        registry.recordSession(
            nodeId = agent.profile.nodeId,
            sessionId = ours,
            agentKind = "claude",
            state = "busy",
            outbound = true,
        )
        Thread {
            for (i in 0 until 50) {
                val frame = desktopFrames.firstOrNull {
                    it["type"]?.jsonPrimitive?.content == "agent-approval"
                }
                if (frame != null) {
                    val payload = frame["payload"]!!.toJsonObject
                    registry.recordApproval(
                        nodeId = desktop.profile.nodeId,
                        sessionId = payload["sessionId"]!!.jsonPrimitive.content,
                        approvalId = payload["approvalId"]!!.jsonPrimitive.content,
                        expiresAt = Long.MAX_VALUE / 2, status = "approved", outbound = false,
                    )
                    return@Thread
                }
                Thread.sleep(100)
            }
        }.start()
        val (code, body) = post(
            "/v1/agent/hook/approval",
            """{"tool":"git push","summary":"触发器派单","conversationId":"c-11","sessionId":"$ours","timeoutMs":8000}""",
        )
        assertEquals(200, code, body)
        assertEquals("allow", field(obj(body), "decision"), body)

        val frame = desktopFrames.first { it["type"]?.jsonPrimitive?.content == "agent-approval" }
        assertEquals(
            ours,
            frame["payload"]!!.toJsonObject["sessionId"]!!.jsonPrimitive.content,
            "本节点派单出去的会话必须被认 —— 触发器任务没有 convId，不能因此退化回 agy-* 池键",
        )
        assertTrue(
            !body.contains("\"convId\""),
            "这条会话没有绑定会话键，就不该编一个 convId 出来：" + body,
        )
    }

    // ---- 只读通道的执行前否决（§7.1 第 3 条）------------------------------------
    //
    // 派单侧把白名单登记到 `readOnlyToolPolicies`（键 = 派单会话），hook 审批据此
    // 在工具落地**之前**拒绝。下面四条各钉一件事：拒绝真的发生、白名单内照旧走审批、
    // 免批表不许覆盖硬约束、别人的策略不许被冒名借用。

    /** 造一个「本节点派单出去」的会话 + 一条白名单，返回 (sessionId, 判据)。 */
    private fun readOnlySession(allow: Set<String>): Pair<String, ReadOnlyToolPolicy> {
        val sid = "sess-readonly-" + System.nanoTime()
        registry.recordSession(
            nodeId = agent.profile.nodeId,
            sessionId = sid,
            agentKind = "claude",
            state = "busy",
            outbound = true,
        )
        val policy = ReadOnlyToolPolicy { tool -> tool?.trim()?.lowercase() in allow }
        plane.readOnlyToolPolicies[sid] = policy
        return sid to policy
    }

    /**
     * 白名单外的工具：**一张卡都不弹**，直接 deny，并把工具记进判据。
     *
     * 旧实现只有事后判负 —— `tool.started` 到达时越界调用已经落地。装了 hook 的
     * CLI 会先问一次，那一次才是能拦住的时机。
     */
    @Test
    fun readOnlyDispatchDeniesANonWhitelistedToolWithoutBotheringThePhone() {
        val (sid, policy) = readOnlySession(setOf("read_file"))
        desktopFrames.clear()
        val (code, body) = post(
            "/v1/agent/hook/approval",
            """{"tool":"run_command","summary":"rm -rf build","conversationId":"c-ro","sessionId":"$sid","timeoutMs":5000}""",
        )
        assertEquals(200, code, body)
        assertEquals("deny", field(obj(body), "decision"), body)
        assertTrue(
            field(obj(body), "reason")!!.contains("read-only"),
            "拒绝理由要点明是只读通道（hook 脚本把它转述给模型）：" + body,
        )
        assertEquals("true", field(obj(body), "readOnly"))
        assertTrue(
            desktopFrames.none { it["type"]?.jsonPrimitive?.content == "agent-approval" },
            "越界调用不该有「问一下也许能过」的路径 —— 问就是给它机会",
        )
        assertEquals("run_command", policy.deniedTool(), "拒绝要记进判据，任务收场时据此判负")
    }

    /** 白名单内的工具照旧走手机审批：只读 ≠ 免批。 */
    @Test
    fun readOnlyDispatchStillAsksThePhoneForAWhitelistedTool() {
        val (sid, policy) = readOnlySession(setOf("read_file"))
        desktopFrames.clear()
        Thread {
            for (i in 0 until 50) {
                val frame = desktopFrames.firstOrNull {
                    it["type"]?.jsonPrimitive?.content == "agent-approval"
                }
                if (frame != null) {
                    val payload = frame["payload"]!!.toJsonObject
                    registry.recordApproval(
                        nodeId = desktop.profile.nodeId,
                        sessionId = payload["sessionId"]!!.jsonPrimitive.content,
                        approvalId = payload["approvalId"]!!.jsonPrimitive.content,
                        expiresAt = Long.MAX_VALUE / 2, status = "approved", outbound = false,
                    )
                    return@Thread
                }
                Thread.sleep(100)
            }
        }.start()
        val (code, body) = post(
            "/v1/agent/hook/approval",
            """{"tool":"read_file","summary":"看构建日志","conversationId":"c-ro2","sessionId":"$sid","timeoutMs":8000}""",
        )
        assertEquals(200, code, body)
        assertEquals("allow", field(obj(body), "decision"), body)
        assertTrue(
            desktopFrames.any { it["type"]?.jsonPrimitive?.content == "agent-approval" },
            "白名单内的工具照旧走手机审批（只读约束的是「能不能写」，不是「要不要问」）",
        )
        assertNull(policy.deniedTool(), "放行过的调用不留越界记录")
    }

    /**
     * 免批表**不许**覆盖只读约束。
     *
     * 顺序判据：免批表是机主以前点过的「这个工具以后别再问我」，属于便利；只读是宿主
     * 对这次派单的硬约束。便利排在硬约束之前时，一次「始终允许 git push」就能让之后
     * 每个只读触发任务都能推远端 —— 而且没有任何症状（卡不弹，任务报成功）。
     */
    @Test
    fun theReadOnlyVetoOutranksAnAlwaysAllowRule() {
        val (sid, policy) = readOnlySession(setOf("read_file"))
        toolStore.remember(desktop.profile.nodeId, "antigravity", "run_command", grantedBy = "test", at = 0L)
        val (code, body) = post(
            "/v1/agent/hook/approval",
            """{"tool":"run_command","summary":"免批表放行过它","conversationId":"c-ro3","sessionId":"$sid","timeoutMs":5000}""",
        )
        assertEquals(200, code, body)
        assertEquals(
            "deny",
            field(obj(body), "decision"),
            "免批表是便利、只读是硬约束：便利不许覆盖硬约束 —— " + body,
        )
        assertFalse(body.contains("always-allow"), "走的不是免批短路：" + body)
        assertEquals("run_command", policy.deniedTool())
    }

    /**
     * 没对账通过的 `sessionId` 取不到别人的白名单。
     *
     * 判据键是**对账通过**的会话。若拿请求体里的原始字符串去查表，回环上一句伪造的
     * sessionId 就能借到别人的策略（这里的方向是「借到 deny」，反方向就是借到放行）。
     */
    @Test
    fun anUnknownSessionIdCannotBorrowAnotherSessionsReadOnlyPolicy() {
        val (owned, policy) = readOnlySession(emptySet()) // 谁都不放行
        val forged = "sess-forged-ro-" + System.nanoTime()
        Thread {
            for (i in 0 until 50) {
                val frame = desktopFrames.firstOrNull {
                    it["type"]?.jsonPrimitive?.content == "agent-approval"
                }
                if (frame != null) {
                    val payload = frame["payload"]!!.toJsonObject
                    registry.recordApproval(
                        nodeId = desktop.profile.nodeId,
                        sessionId = payload["sessionId"]!!.jsonPrimitive.content,
                        approvalId = payload["approvalId"]!!.jsonPrimitive.content,
                        expiresAt = Long.MAX_VALUE / 2, status = "approved", outbound = false,
                    )
                    return@Thread
                }
                Thread.sleep(100)
            }
        }.start()
        val (code, body) = post(
            "/v1/agent/hook/approval",
            """{"tool":"git push","summary":"冒用别人的白名单","conversationId":"c-ro4","sessionId":"$forged","timeoutMs":8000}""",
        )
        assertEquals(200, code, body)
        assertEquals(
            "allow",
            field(obj(body), "decision"),
            "没对账通过的 sessionId 只能走自己的派生键，取不到 $owned 的策略：" + body,
        )
        assertNull(policy.deniedTool(), "别人的策略不许被这次请求改写")
    }

    /**
     * 端到端：hook 拒绝 → 任务判负 → 判据随派单清掉。
     *
     * 三件事只有连起来才有意义：登记必须在**执行体起来之前**（否则首个 PreToolUse
     * 到达时表里还没有这一条，等于又退回事后判负）；拒绝必须让派单方看到（结果卡与
     * 台账同一句）；`finally` 必须清掉（不清就把「只读」泄漏给同 id 的下一次任务）。
     *
     * 时序**不用固定 delay 赌**：探针驱动的 `executeTask` 里当场打一次审批请求 ——
     * 「登记早于执行」于是由构造保证，而不是由测试等出来。若登记晚了一步，审批路由
     * 查不到策略、这次调用会被放行，用例随即红。
     */
    @Test
    fun aReadOnlyDispatchFailsTheTaskWhenTheHookDeniesATool() = kotlinx.coroutines.runBlocking {
        val sid = "sess-readonly-e2e-" + System.nanoTime()
        val probe = HookProbeDriver(sessionId = sid, tool = "run_command")
        plane.cliDrivers!!.register(probe)
        assertTrue(
            plane.resolveCliDriver("ro-probe") != null,
            "夹具先得解析得到探针驱动，否则 executeCliTask 会在登记判据之前就 return",
        )
        val (sessionId, status, error) = plane.executeCliTask(
            agentKey = "ro-probe", prompt = "summarize the build log", wsPath = null,
            requestedSessionId = null,
            toolPolicy = { tool -> tool == "read_file" },
        ) { _, _, _, _ -> }
        val approval = probe.approvalBody ?: error("执行体没有发出 PreToolUse 审批")
        assertEquals("deny", field(obj(approval), "decision"), "执行前的拒绝没生效：" + approval)
        assertEquals(sid, sessionId)
        assertEquals("error", status, "越界即判负 —— 拦住了也要判负，口径与事后那一半一致")
        assertTrue(
            error != null && error.contains("run_command"),
            "失败原因要点名越界工具：" + error + " / 事件：" +
                registry.events(agent.profile.nodeId, sid).map { it.eventType + "=" + it.body },
        )
        assertEquals(
            "error",
            registry.sessionOrNull(agent.profile.nodeId, sid)?.state,
            "台账终态也是 error（手机看板读的是它）",
        )
        assertTrue(
            registry.events(agent.profile.nodeId, sid).any {
                it.eventType == "error" && it.body?.contains("read-only") == true
            },
            "台账里要留下失败原因，与结果卡上是同一句",
        )
        assertNull(
            plane.readOnlyToolPolicies[sid],
            "判据必须随派单清掉，否则会把「只读」泄漏给同 id 的下一次任务",
        )
    }

    /**
     * 探针驱动：会话 id 固定，`executeTask` 里**当场**打一次 PreToolUse 审批。
     *
     * 它替掉真 CLI 的「起子进程」，保留本用例真正要验的三段（登记 → 审批路由读到
     * 判据 → `executeCliTask` 收场判负）。用它而不是真脚本，是为了让「登记早于执行」
     * 成为**构造性质**：真脚本要靠测试等进程起来，那正是 `AGENTS.md §4` 禁止的
     * 「用固定 delay 赌同步」。
     */
    private inner class HookProbeDriver(
        private val sessionId: String,
        private val tool: String,
    ) : com.kapp.tenon.agent.drivers.AgentDriver {

        override val driverId: String = "ro-probe-cli"
        override val displayName: String = "read-only probe"

        /** 审批响应的原文（用例据此断言真的被拒）。 */
        var approvalBody: String? = null

        override suspend fun listCapabilities(): List<com.kapp.tenon.agent.drivers.DriverCapability> = emptyList()

        override suspend fun openSession(
            wsPath: String,
            resumeSessionId: String?,
        ): com.kapp.tenon.agent.drivers.SessionHandle =
            com.kapp.tenon.agent.drivers.SessionHandle(sessionId = sessionId)

        override suspend fun executeTask(
            sessionId: String,
            prompt: String,
            onEvent: suspend (com.kapp.tenon.agent.drivers.DriverEvent) -> Unit,
        ) {
            approvalBody = post(
                "/v1/agent/hook/approval",
                """{"tool":"$tool","summary":"rm -rf build","conversationId":"c-ro-e2e","sessionId":"$sessionId","timeoutMs":5000}""",
            ).second
            onEvent(com.kapp.tenon.agent.drivers.DriverEvent.Finished())
        }

        override suspend fun handleAction(sessionId: String, actionId: String, decidedBy: String) = Unit

        override suspend fun queryProgress(sessionId: String): com.kapp.tenon.agent.drivers.TaskProgressSnapshot? = null
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
