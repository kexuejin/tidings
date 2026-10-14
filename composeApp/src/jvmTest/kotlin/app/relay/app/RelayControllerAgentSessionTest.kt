// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.nowMillis
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.Profile
import app.relay.core.protocol.newId
import app.relay.core.session.NodeRuntime
import app.relay.core.transport.SendOutcome
import app.relay.platform.InMemoryAppPlatform
import app.relay.platform.MdnsAdvertiser
import app.relay.platform.MdnsBrowser
import app.relay.platform.NetworkMonitor
import app.relay.platform.NetworkSnapshot
import app.relay.platform.Transport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import app.relay.app.model.AgentEventUi
import app.relay.app.model.AgentSessionKey
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.DeliveryState

/**
 * 验证 Agent Session 协议（PPE-1 P0）在客户端控制器与 Agent 节点之间的端到端双向闭环。
 *
 * 覆盖的关键契约：
 *  1. 握手与能力协商：双方确认声明了 `agn1`（[MsgTypes.CAP_AGENT_SESSION]），Agent 具备 `ag1`；
 *  2. 会话宣告（`agent-session`）：Agent 发出宣告后，客户端 [RelayController.state] 实时感知会话元数据，
 *     且控制帧严格不污染普通聊天消息列表（遵循 PPE-1 §9）；
 *  3. 事件流投递与去重排序（`agent-event`）：多条事件按 cursor 单调排序，乱序到达能自愈，重复 cursor 被过滤；
 *  4. 双向交互（Client -> Agent）：客户端向 Agent 回复文本消息，网络层送达并收到 ACK，Agent 能够如实拉取；
 *  5. 会话状态迁移：会话状态更新（如 `state: busy` -> `done`）保留原有元数据（alias, title 等）；
 *  6. 未宣告会话的事件兜底：先行到达的事件生成 `announced = false` 的占位会话；
 *  7. 客户端出站发送能力与未声明 `agn1` 的协商防御拦截。
 */
class RelayControllerAgentSessionTest {

    private lateinit var scope: CoroutineScope
    private lateinit var agentRuntime: NodeRuntime
    private lateinit var controller: RelayController

    private val clientNodeId = "11111111-0000-4000-8000-000000000001"
    private val agentNodeId = "22222222-0000-4000-8000-000000000002"

    @BeforeTest
    fun setUp() = runBlocking {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // 1. 启动 Agent 节点（声明 ag1 + tw1 + agn1）
        agentRuntime = NodeRuntime(
            profile = Profile.agent(
                nodeId = agentNodeId,
                nick = "Tidings Agent Bot",
                host = "test-agent-host",
                ver = "relay-0.1.0",
                platform = "mac",
            ),
            udpPort = 0,
            bindHost = "127.0.0.1",
        )
        agentRuntime.start(scope)

        // 2. 启动客户端控制器（RelayController），将 Agent 设为手动节点
        val store = AgentTestSettingsStore(
            clientNodeId = clientNodeId,
            manualPeers = listOf(ManualPeerEntry("127.0.0.1", agentRuntime.boundPort)),
        )
        controller = RelayController(
            platform = InMemoryAppPlatform(
                networkMonitor = StaticNetworkMonitor,
                mdnsAdvertiser = NoopAdvertiser,
                mdnsBrowser = NoopBrowser,
            ),
            store = store,
            scope = scope,
            version = "relay-test",
        )
        controller.start()

        // 3. 等待双方完成 UDP 握手与能力学习
        awaitUntil("客户端与 Agent 完成双向握手") {
            val peer = controller.state.value.peer(agentNodeId)
            peer != null && peer.online && agentRuntime.getPeer(clientNodeId)?.online == true
        }
    }

    @AfterTest
    fun tearDown() = runBlocking {
        controller.stop()
        agentRuntime.stop()
        scope.cancel()
    }

    @Test
    fun agentSessionAndEventsFullBidirectionalWorkflow() = runBlocking {
        // 验证 Agent 节点识别
        val agentPeer = controller.state.value.peer(agentNodeId)
        assertNotNull(agentPeer)
        assertTrue(agentPeer.isAgent, "Agent 节点应被识别为 isAgent=true")
        assertTrue(controller.state.value.agentPeers.any { it.nodeId == agentNodeId })

        // ==========================================
        // 步骤 1：Agent 发起会话宣告（agent-session）
        // ==========================================
        val sessionId = "s-test-${System.currentTimeMillis()}"
        val sessionOutcome = agentRuntime.sendAgentSession(
            peerId = clientNodeId,
            sessionId = sessionId,
            alias = "Code Reviewer",
            agentKind = "gemini",
            state = "busy",
            title = "审查 PR #42",
            startedAt = nowMillis(),
        )
        assertEquals(SendOutcome.SENT, sessionOutcome, "会话宣告帧应收到客户端 ACK")

        // 客户端状态机应更新 agentSessions
        val sessionKey = AgentSessionKey(agentNodeId, sessionId)
        awaitUntil("客户端感知 agent-session") {
            controller.state.value.agentSessions.containsKey(sessionKey)
        }
        val sessionUi = controller.state.value.agentSessions[sessionKey]!!
        assertEquals("Code Reviewer", sessionUi.alias)
        assertEquals("gemini", sessionUi.agentKind)
        assertEquals("busy", sessionUi.state)
        assertEquals("审查 PR #42", sessionUi.title)
        assertTrue(sessionUi.announced, "收到会话宣告后 announced 应为 true")
        assertEquals(0, controller.state.value.messagesWith(agentNodeId).size, "agent-session 绝不应进入聊天消息列表")

        // ==========================================
        // 步骤 2：Agent 发送结构化事件流（agent-event）
        // ==========================================
        val e1 = agentRuntime.sendAgentEvent(
            peerId = clientNodeId,
            sessionId = sessionId,
            cursor = 10L,
            eventType = "session.started",
            summary = "开始代码静态分析",
        )
        assertEquals(SendOutcome.SENT, e1)

        val e2 = agentRuntime.sendAgentEvent(
            peerId = clientNodeId,
            sessionId = sessionId,
            cursor = 20L,
            eventType = "tool.started",
            summary = "执行 linter",
            ref = "tool-ktlint",
        )
        assertEquals(SendOutcome.SENT, e2)

        val e3 = agentRuntime.sendAgentEvent(
            peerId = clientNodeId,
            sessionId = sessionId,
            cursor = 30L,
            eventType = "tool.finished",
            summary = "检查通过，0 错误",
        )
        assertEquals(SendOutcome.SENT, e3)

        // 验证客户端正确按 cursor 收集事件
        awaitUntil("客户端收到前三条事件") {
            controller.state.value.agentEventsOf(agentNodeId, sessionId).size == 3
        }
        val initialEvents = controller.state.value.agentEventsOf(agentNodeId, sessionId)
        assertEquals(listOf(10L, 20L, 30L), initialEvents.map { it.cursor })
        assertEquals("tool.started", initialEvents[1].eventType)
        assertEquals("tool-ktlint", initialEvents[1].ref)

        // ==========================================
        // 步骤 3：乱序到达与重复游标自愈验证
        // ==========================================
        // 先发 cursor 50，再发 cursor 40
        agentRuntime.sendAgentEvent(clientNodeId, sessionId, cursor = 50L, eventType = "artifact.ready", summary = "报告就绪")
        agentRuntime.sendAgentEvent(clientNodeId, sessionId, cursor = 40L, eventType = "report.generating", summary = "汇总结论")

        awaitUntil("收到乱序事件后重新排序") {
            controller.state.value.agentEventsOf(agentNodeId, sessionId).size == 5
        }
        val sortedEvents = controller.state.value.agentEventsOf(agentNodeId, sessionId)
        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), sortedEvents.map { it.cursor }, "事件流必须按 cursor 升序排列")

        // 重复发送已存在的 cursor 20
        agentRuntime.sendAgentEvent(clientNodeId, sessionId, cursor = 20L, eventType = "tool.started", summary = "重复帧")
        delay(100)
        assertEquals(5, controller.state.value.agentEventsOf(agentNodeId, sessionId).size, "相同 cursor 重复帧必须被去重")

        // ==========================================
        // 步骤 4：客户端向 Agent 回复双向消息
        // ==========================================
        controller.send(agentNodeId, "收到审查结果，同意合并")

        // 客户端消息状态应变为 Sent（已收到 Agent 传输层 ACK）
        awaitUntil("客户端发出的消息状态变为 Sent") {
            controller.state.value.messagesWith(agentNodeId).any { it.mine && it.delivery == DeliveryState.Sent }
        }

        // Agent 的 inbox 应收到客户端回复
        awaitUntil("Agent 端 inbox 收到客户端消息") {
            agentRuntime.inbox(0L).any { it.direction == "in" && it.peerId == clientNodeId }
        }
        val receivedReply = agentRuntime.inbox(0L).first { it.direction == "in" && it.peerId == clientNodeId }
        assertTrue(receivedReply.envelopeJson.contains("收到审查结果，同意合并"))

        // ==========================================
        // 步骤 5：Agent 更新会话状态为 done
        // ==========================================
        val updateOutcome = agentRuntime.sendAgentSession(
            peerId = clientNodeId,
            sessionId = sessionId,
            state = "done",
            // 其余字段省略，协议要求保留上一次的值
        )
        assertEquals(SendOutcome.SENT, updateOutcome)

        awaitUntil("客户端收到会话更新") {
            controller.state.value.agentSessions[sessionKey]?.state == "done"
        }
        val updatedSession = controller.state.value.agentSessions[sessionKey]!!
        assertEquals("done", updatedSession.state)
        assertEquals("Code Reviewer", updatedSession.alias, "状态更新不应冲掉原有 alias")
        assertEquals("审查 PR #42", updatedSession.title, "状态更新不应冲掉原有 title")

        // ==========================================
        // 步骤 6：未宣告会话的孤儿事件兜底
        // ==========================================
        val orphanSessionId = "s-orphan-999"
        val orphanKey = AgentSessionKey(agentNodeId, orphanSessionId)
        agentRuntime.sendAgentEvent(clientNodeId, orphanSessionId, cursor = 1L, eventType = "log.notice", summary = "后台日志")

        awaitUntil("孤儿事件生成 announced=false 的占位会话") {
            controller.state.value.agentSessions[orphanKey] != null
        }
        val orphanSession = controller.state.value.agentSessions[orphanKey]!!
        assertFalse(orphanSession.announced, "未收到会话宣告的占位会话 announced 必须为 false")
        assertEquals(1, controller.state.value.agentEventsOf(agentNodeId, orphanSessionId).size)

        // ==========================================
        // 步骤 7：控制器主动调用 sendAgentSession / sendAgentEvent
        // ==========================================
        val clientSessionOutcome = controller.sendAgentSession(
            peerId = agentNodeId,
            sessionId = "s-client-session",
            alias = "Mobile Agent",
            state = "idle",
        )
        assertEquals(SendOutcome.SENT, clientSessionOutcome, "客户端控制器直接发出的 agent-session 应成功")

        val clientEventOutcome = controller.sendAgentEvent(
            peerId = agentNodeId,
            sessionId = "s-client-session",
            cursor = 1L,
            eventType = "ping",
            summary = "探活",
        )
        assertEquals(SendOutcome.SENT, clientEventOutcome, "客户端控制器直接发出的 agent-event 应成功")

        // ==========================================
        // 步骤 8：向未声明 agn1 的节点发送时的协商防御
        // ==========================================
        val plainRuntime = NodeRuntime(
            profile = Profile.mobile(
                nodeId = newId(),
                nick = "普通老节点",
                host = "plain-host",
                ver = "0.61.1",
                caps = listOf("tw1"), // 故意不声明 agn1
            ),
            udpPort = 0,
            bindHost = "127.0.0.1",
        )
        plainRuntime.start(scope)
        try {
            // 让客户端认识该老节点
            awaitUntil("客户端与老节点握手") {
                runBlocking { controller.activeRuntime()?.resolvePeerByHost("127.0.0.1", plainRuntime.boundPort) } != null
            }
            val refused = controller.sendAgentSession(
                peerId = plainRuntime.nodeId,
                sessionId = "s-refused",
                alias = "Should Fail",
            )
            assertEquals(null, refused, "未声明 agn1 的对端必须拦截，禁止发出")
        } finally {
            plainRuntime.stop()
        }
    }

    @Test
    fun agentSessionsAndEventsArePersistedAcrossRestart() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        app.relay.db.RelayDatabase.Schema.create(driver)
        val database = app.relay.db.RelayDatabase(driver)
        val persistentAgentStore = SqlDelightAgentStore(database)
        val sessionA = AgentSessionUi(
            peerId = agentNodeId,
            sessionId = "sess-persisted-1",
            alias = "build-pipeline",
            agentKind = "devops",
            state = "busy",
            title = "Continuous Integration Run",
            startedAt = 1000L,
            updatedAt = 2000L,
            announced = true,
        )
        val event1 = AgentEventUi(
            sessionId = "sess-persisted-1",
            peerId = agentNodeId,
            cursor = 101L,
            eventType = "step.compile",
            summary = "Compile Kotlin sources",
            body = "100% success",
            ref = null,
            at = 1500L,
        )
        persistentAgentStore.saveSession(sessionA)
        persistentAgentStore.saveEvent(event1)

        val restoredController = RelayController(
            platform = InMemoryAppPlatform(),
            store = AgentTestSettingsStore(clientNodeId, emptyList()),
            agentStore = persistentAgentStore,
            scope = scope,
        )
        restoredController.start()

        val persistedKey = AgentSessionKey(agentNodeId, "sess-persisted-1")
        awaitUntil("Agent 会话与事件从 SQLite 恢复到控制器状态") {
            restoredController.state.value.agentSessions[persistedKey] != null &&
                restoredController.state.value.agentEvents[persistedKey]?.size == 1
        }

        val restoredSession = restoredController.state.value.agentSessions[persistedKey]
        assertNotNull(restoredSession)
        assertEquals("build-pipeline", restoredSession.alias)
        assertEquals("busy", restoredSession.state)
        assertEquals("Continuous Integration Run", restoredSession.title)

        val restoredEvents = restoredController.state.value.agentEvents[persistedKey].orEmpty()
        assertEquals(1, restoredEvents.size)
        assertEquals(101L, restoredEvents.first().cursor)
        assertEquals("step.compile", restoredEvents.first().eventType)

        restoredController.stop()
    }

    @Test
    fun crossPeerSameSessionIdIsolationAndDeduplication() = runBlocking {
        val agentNodeId2 = "33333333-0000-4000-8000-000000000003"
        val agentRuntime2 = NodeRuntime(
            profile = Profile.agent(
                nodeId = agentNodeId2,
                nick = "Second Agent Bot",
                host = "test-agent-host-2",
                ver = "relay-0.1.0",
                platform = "mac",
            ),
            udpPort = 0,
            bindHost = "127.0.0.1",
        )
        agentRuntime2.start(scope)
        try {
            controller.addManualPeer("127.0.0.1", agentRuntime2.boundPort.toString())
            awaitUntil("客户端与第二个 Agent 完成握手") {
                val p = controller.state.value.peer(agentNodeId2)
                p != null && p.online && agentRuntime2.getPeer(clientNodeId)?.online == true
            }

            val sharedSessionId = "s-shared-collision"

            // 两 peer 分别宣告同一 sessionId
            val outA = agentRuntime.sendAgentSession(
                peerId = clientNodeId,
                sessionId = sharedSessionId,
                alias = "Agent A Session",
                state = "busy",
                title = "Work A",
                startedAt = 1000L,
            )
            val outB = agentRuntime2.sendAgentSession(
                peerId = clientNodeId,
                sessionId = sharedSessionId,
                alias = "Agent B Session",
                state = "idle",
                title = "Work B",
                startedAt = 2000L,
            )
            assertEquals(SendOutcome.SENT, outA)
            assertEquals(SendOutcome.SENT, outB)

            val keyA = AgentSessionKey(agentNodeId, sharedSessionId)
            val keyB = AgentSessionKey(agentNodeId2, sharedSessionId)

            awaitUntil("客户端同时感知两节点的同名会话") {
                controller.state.value.agentSessions.containsKey(keyA) &&
                    controller.state.value.agentSessions.containsKey(keyB)
            }

            val sessA = controller.state.value.agentSessions[keyA]!!
            val sessB = controller.state.value.agentSessions[keyB]!!
            assertEquals("Agent A Session", sessA.alias)
            assertEquals("Work A", sessA.title)
            assertEquals("Agent B Session", sessB.alias)
            assertEquals("Work B", sessB.title)

            // 两 peer 发送事件：包含相同的 cursor=1L
            agentRuntime.sendAgentEvent(clientNodeId, sharedSessionId, cursor = 1L, eventType = "step.a1", summary = "A step 1")
            agentRuntime.sendAgentEvent(clientNodeId, sharedSessionId, cursor = 2L, eventType = "step.a2", summary = "A step 2")

            agentRuntime2.sendAgentEvent(clientNodeId, sharedSessionId, cursor = 1L, eventType = "step.b1", summary = "B step 1")
            agentRuntime2.sendAgentEvent(clientNodeId, sharedSessionId, cursor = 3L, eventType = "step.b3", summary = "B step 3")

            awaitUntil("两端各自的事件全部送达") {
                controller.state.value.agentEventsOf(agentNodeId, sharedSessionId).size == 2 &&
                    controller.state.value.agentEventsOf(agentNodeId2, sharedSessionId).size == 2
            }

            // 验证：事件流严格隔离，没有交错；相同 cursor=1L 没有因为去重逻辑而丢弃另一 peer 的事件
            val eventsA = controller.state.value.agentEventsOf(agentNodeId, sharedSessionId)
            val eventsB = controller.state.value.agentEventsOf(agentNodeId2, sharedSessionId)

            assertEquals(listOf("step.a1", "step.a2"), eventsA.map { it.eventType })
            assertEquals(listOf("step.b1", "step.b3"), eventsB.map { it.eventType })

            // 按 peer 查询会话互不干扰
            assertEquals(1, controller.state.value.agentSessionsOf(agentNodeId).size)
            assertEquals(1, controller.state.value.agentSessionsOf(agentNodeId2).size)
        } finally {
            agentRuntime2.stop()
        }
    }

    @Test
    fun crossPeerSameSessionIdRestartRecovery() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        app.relay.db.RelayDatabase.Schema.create(driver)
        val database = app.relay.db.RelayDatabase(driver)
        val persistentAgentStore = SqlDelightAgentStore(database)

        val sharedSessionId = "s-shared-restart"
        val peerA = "peer-AAA"
        val peerB = "peer-BBB"

        val now = nowMillis()
        val sessionA = AgentSessionUi(
            peerId = peerA,
            sessionId = sharedSessionId,
            alias = "Session A",
            state = "busy",
            title = "Title A",
            startedAt = now - 2000L,
            updatedAt = now - 1000L,
            announced = true,
        )
        // peerB 的 updatedAt 较新 (now)
        val sessionB = AgentSessionUi(
            peerId = peerB,
            sessionId = sharedSessionId,
            alias = "Session B",
            state = "done",
            title = "Title B",
            startedAt = now - 1000L,
            updatedAt = now,
            announced = true,
        )

        val eventA = AgentEventUi(
            sessionId = sharedSessionId,
            peerId = peerA,
            cursor = 10L,
            eventType = "event.a",
            at = now - 1000L,
        )
        val eventB = AgentEventUi(
            sessionId = sharedSessionId,
            peerId = peerB,
            cursor = 10L,
            eventType = "event.b",
            at = now,
        )

        persistentAgentStore.saveSession(sessionA)
        persistentAgentStore.saveSession(sessionB)
        persistentAgentStore.saveEvent(eventA)
        persistentAgentStore.saveEvent(eventB)

        val restoredController = RelayController(
            platform = InMemoryAppPlatform(),
            store = AgentTestSettingsStore(clientNodeId, emptyList()),
            agentStore = persistentAgentStore,
            scope = scope,
        )
        restoredController.start()

        val keyA = AgentSessionKey(peerA, sharedSessionId)
        val keyB = AgentSessionKey(peerB, sharedSessionId)

        awaitUntil("跨 Peer 同名会话与事件从 SQLite 恢复到控制器状态") {
            restoredController.state.value.agentSessions[keyA] != null &&
                restoredController.state.value.agentSessions[keyB] != null &&
                restoredController.state.value.agentEvents[keyA]?.size == 1 &&
                restoredController.state.value.agentEvents[keyB]?.size == 1
        }

        // 验证：更新的那条（peerB）没有因为 associate 单键覆盖而丢失
        val loadedA = restoredController.state.value.agentSessions[keyA]
        val loadedB = restoredController.state.value.agentSessions[keyB]
        assertNotNull(loadedA)
        assertNotNull(loadedB)
        assertEquals("Session A", loadedA.alias)
        assertEquals("Session B", loadedB.alias)

        val loadedEventsA = restoredController.state.value.agentEvents[keyA].orEmpty()
        val loadedEventsB = restoredController.state.value.agentEvents[keyB].orEmpty()
        assertEquals(1, loadedEventsA.size)
        assertEquals("event.a", loadedEventsA.first().eventType)
        assertEquals(1, loadedEventsB.size)
        assertEquals("event.b", loadedEventsB.first().eventType)

        restoredController.stop()
    }

    @Test
    fun agentSessionLifecycleAndPruningTest() {
        val now = 1_000_000_000L
        val peerA = "peer-alpha"
        val peerB = "peer-beta"

        val sActive = AgentSessionUi(peerId = peerA, sessionId = "s-act", state = "busy", updatedAt = now)
        val sIdle = AgentSessionUi(peerId = peerA, sessionId = "s-idle", state = "idle", updatedAt = now)
        val sDone = AgentSessionUi(peerId = peerA, sessionId = "s-done", state = "done", updatedAt = now)
        val sError = AgentSessionUi(peerId = peerA, sessionId = "s-err", state = "error", updatedAt = now)
        val sArchived = AgentSessionUi(peerId = peerA, sessionId = "s-arc", state = "archived", updatedAt = now)
        val sClosed = AgentSessionUi(peerId = peerA, sessionId = "s-cls", state = "closed", updatedAt = now)

        assertFalse(sActive.isEnded)
        assertFalse(sIdle.isEnded)
        assertTrue(sDone.isEnded)
        assertTrue(sError.isEnded)
        assertTrue(sArchived.isEnded)
        assertTrue(sArchived.isArchived)
        assertTrue(sClosed.isEnded)
        assertTrue(sClosed.isArchived)

        // 测试 RelayUiState 的 active / ended 分组筛选
        val allSessions = mapOf(
            AgentSessionKey(peerA, "s-act") to sActive,
            AgentSessionKey(peerA, "s-idle") to sIdle,
            AgentSessionKey(peerA, "s-done") to sDone,
            AgentSessionKey(peerA, "s-err") to sError,
            AgentSessionKey(peerB, "s-b-done") to AgentSessionUi(peerId = peerB, sessionId = "s-b-done", state = "done", updatedAt = now),
        )
        val uiState = RelayUiState(agentSessions = allSessions)
        val activeA = uiState.activeAgentSessionsOf(peerA)
        val endedA = uiState.endedAgentSessionsOf(peerA)
        assertEquals(2, activeA.size)
        assertTrue(activeA.all { !it.isEnded })
        assertEquals(2, endedA.size)
        assertTrue(endedA.all { it.isEnded })

        // 测试 pruneEndedSessions：
        // 1. 活跃会话绝不淘汰
        // 2. 超出 7 天 TTL 的已结束会话自动清理
        // 3. 超过 20 条配额的已结束会话保留最新的 20 条，淘汰更老的
        val sessionsToPrune = mutableMapOf<AgentSessionKey, AgentSessionUi>()
        val eventsToPrune = mutableMapOf<AgentSessionKey, List<AgentEventUi>>()

        // 加入 2 个活跃会话
        sessionsToPrune[AgentSessionKey(peerA, "act-1")] = AgentSessionUi(peerId = peerA, sessionId = "act-1", state = "busy", updatedAt = now - 30 * 86400 * 1000L)
        sessionsToPrune[AgentSessionKey(peerA, "act-2")] = AgentSessionUi(peerId = peerA, sessionId = "act-2", state = "idle", updatedAt = now)

        // 加入 25 个已结束会话（各不同时间）
        for (i in 1..25) {
            val sId = "ended-$i"
            val key = AgentSessionKey(peerA, sId)
            val updated = now - (i * 1000L)
            sessionsToPrune[key] = AgentSessionUi(peerId = peerA, sessionId = sId, state = "done", updatedAt = updated)
            eventsToPrune[key] = listOf(AgentEventUi(sessionId = sId, peerId = peerA, cursor = 1, eventType = "test", at = updated))
        }

        // 加入 1 个已结束但超过 7 天 TTL 的会话
        val expiredKey = AgentSessionKey(peerA, "expired")
        sessionsToPrune[expiredKey] = AgentSessionUi(peerId = peerA, sessionId = "expired", state = "done", updatedAt = now - (8 * 86400 * 1000L))
        eventsToPrune[expiredKey] = listOf(AgentEventUi(sessionId = "expired", peerId = peerA, cursor = 1, eventType = "test", at = now - (8 * 86400 * 1000L)))

        val (keptSessions, keptEvents) = pruneEndedSessions(sessionsToPrune, eventsToPrune, now)

        // 活跃会话都在
        assertTrue(keptSessions.containsKey(AgentSessionKey(peerA, "act-1")))
        assertTrue(keptSessions.containsKey(AgentSessionKey(peerA, "act-2")))

        // 过期的 expired 会话已被删除
        assertFalse(keptSessions.containsKey(expiredKey))
        assertFalse(keptEvents.containsKey(expiredKey))

        // 已结束会话最多只留 20 条（25 条里最老的那 5 条被淘汰）
        val remainingEnded = keptSessions.filterKeys { it.peerId == peerA }.values.filter { it.isEnded }
        assertEquals(20, remainingEnded.size)
        // 最老的 ended-21 ~ ended-25 应该被移除了
        for (i in 21..25) {
            val removedKey = AgentSessionKey(peerA, "ended-$i")
            assertFalse(keptSessions.containsKey(removedKey))
            assertFalse(keptEvents.containsKey(removedKey))
        }
    }

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (deadline.elapsedNow().inWholeMilliseconds > TIMEOUT_MS) error("等了 ${TIMEOUT_MS}ms 仍未发生：$what")
            delay(10)
        }
    }

    private class AgentTestSettingsStore(
        clientNodeId: String,
        manualPeers: List<ManualPeerEntry>,
    ) : SettingsStore {
        var current: RelaySettings? = RelaySettings(
            nodeId = clientNodeId,
            nick = "我的手机",
            udpPort = 0,
            tcpPort = 0,
            manualPeers = manualPeers,
        )
        override fun load(): RelaySettings? = current
        override fun save(settings: RelaySettings) { current = settings }
    }

    private object StaticNetworkMonitor : NetworkMonitor {
        override fun rawSnapshot(): NetworkSnapshot = NetworkSnapshot(
            online = true,
            transport = Transport.Wifi,
            localAddresses = listOf("127.0.0.1"),
            vpnActive = false,
        )
        override fun observe(onChange: (NetworkSnapshot) -> Unit): () -> Unit = {}
    }

    private object NoopAdvertiser : MdnsAdvertiser {
        override suspend fun start(serviceName: String, port: Int): Boolean = true
        override fun stop() = Unit
    }

    private object NoopBrowser : MdnsBrowser {
        override fun startBrowsing(
            onServiceFound: (String, String, Int) -> Unit,
            onServiceLost: (String) -> Unit,
        ) = Unit
        override fun stopBrowsing() = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
