// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import com.kapp.tidings.agent.plugins.MockExternalPlugin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `ExternalPluginDriver` 的驱动层契约（方案 §2.4，M2-T3）。
 *
 * 主包内唯一的通用插件驱动：它**不含任何业务代码**，只把 AgentDriver 的四个
 * 生命周期成员转发给进程外插件的回环 HTTP IPC（session/open、task/execute 流式、
 * task/action、task/queryProgress）。对端是 [MockExternalPlugin] —— 与真插件
 * 同一份 HTTP 契约的进程内夹具。
 *
 * 两条硬规则在这里钉死：
 * 1. **插件的事件流进总线时保持事件语义**（思考/正文/工具/卡片/完成），卡片以
 *    [DriverEvent.Card] 承载（原样 JSON，宿主构群帧时才校验字节预算）；
 * 2. **热拔不出声也不崩**：插件进程没了 ⇒ executeTask 回 [DriverEvent.Error]
 *    （经由事件通道如实说，绝不抛到调用方）并触发断连回调（宿主据此翻离线），
 *    其余三个成员抛 IO 异常由调用方（控制面）转 502 —— 它们没有事件通道可说谎。
 */
class ExternalPluginDriverTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun driverFor(plugin: MockExternalPlugin, onConnectionLost: (String) -> Unit = {}): ExternalPluginDriver =
        ExternalPluginDriver(
            driverId = "echo-team",
            displayName = "Echo Team",
            baseUrl = plugin.baseUrl,
            manifest = json.decodeFromString(
                MockExternalPlugin.Default.echoTeamManifest().toString(),
            ),
            onConnectionLost = onConnectionLost,
        )

    @AfterTest
    fun tearDown() {
        // 夹具由各用例自行 stop；这里兜底不再需要。
    }

    @Test
    fun openSessionForwardsToThePluginAndReturnsItsHandle() = MockExternalPlugin().use { plugin ->
            plugin.start()
            val handle = driverFor(plugin).openSession("/ws/demo", null)
            assertTrue(handle.sessionId.startsWith("echo-"), "插件给的 sessionId 要透传回来：" + handle.sessionId)
            assertEquals(false, handle.resumed)
            val open = plugin.received.firstOrNull { it.path == "/session/open" }
            assertNotNull(open, "插件必须收到 session/open")
            assertTrue(open.body.contains("/ws/demo"), "wsPath 要转发给插件：" + open.body)
    }

    @Test
    fun resumedSessionKeepsThePluginSideIdentity() = MockExternalPlugin().use { plugin ->
            plugin.start()
            val handle = driverFor(plugin).openSession("/ws/demo", "echo-old-session")
            assertEquals(true, handle.resumed)
            assertEquals("echo-resumed-session", handle.sessionId, "接续会话复用插件侧的既有身份")
    }

    @Test
    fun executeTaskStreamsPluginEventsOntoTheBus() = MockExternalPlugin().use { plugin ->
            plugin.start()
            val events = mutableListOf<DriverEvent>()
            driverFor(plugin).executeTask("echo-1", "hello plugin") { events += it }
            assertTrue(events.any { it is DriverEvent.ThoughtDelta }, "思考增量要进总线")
            assertTrue(events.any { it is DriverEvent.TokenDelta }, "正文增量要进总线")
            assertTrue(events.any { it is DriverEvent.Finished }, "完成事件要进总线")
            val execute = plugin.received.firstOrNull { it.path == "/task/execute" }
            assertNotNull(execute, "插件必须收到 task/execute")
            assertTrue(execute.body.contains("echo-1") && execute.body.contains("hello plugin"), execute.body)
    }

    @Test
    fun cardEventsCarryThePluginCardJsonVerbatim() = MockExternalPlugin().use { plugin ->
            plugin.start()
            val events = mutableListOf<DriverEvent>()
            driverFor(plugin).executeTask("echo-1", "draw a card") { events += it }
            val card = events.filterIsInstance<DriverEvent.Card>().single()
            val obj = json.parseToJsonElement(card.cardJson).jsonObject
            assertEquals("card-echo-1", obj["cardId"]?.jsonPrimitive?.content, "卡片 JSON 原样透传（宿主构群帧时才校验预算）")
            assertEquals("team-task-board", obj["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun handleActionAndQueryProgressRoundTrip() = MockExternalPlugin().use { plugin ->
            plugin.start()
            val driver = driverFor(plugin)
            plugin.queryProgressReply = "{\"sessionId\":\"echo-1\",\"state\":\"running\",\"progressPercent\":70}"
            driver.handleAction("echo-1", "pause-task", "alice")
            val action = plugin.received.firstOrNull { it.path == "/task/action" }
            assertNotNull(action, "插件必须收到 task/action")
            assertTrue(action.body.contains("pause-task") && action.body.contains("alice"), action.body)

            val snap = driver.queryProgress("echo-1")
            assertEquals(70, snap?.progressPercent)
            assertEquals("running", snap?.state)
            plugin.queryProgressReply = null
            assertEquals(null, driver.queryProgress("echo-1"), "插件的 null = 没有可报告的进度")
    }

    @Test
    fun aDeadPluginYieldsAnErrorEventAndConnectionLostNeverAThrow() = runBlocking {
        val plugin = MockExternalPlugin()
        plugin.start()
        var lostAt: String? = null
        val driver = driverFor(plugin) { lostAt = it }
        plugin.stop() // 热拔：进程没了
        val events = mutableListOf<DriverEvent>()
        driver.executeTask("echo-1", "anyone there?") { events += it }
        val error = events.filterIsInstance<DriverEvent.Error>().single()
        assertTrue(error.message.isNotEmpty(), "断连必须经由事件通道如实说")
        assertNotNull(lostAt, "断连回调必须触发（宿主据此把插件翻成离线）")
        assertEquals("echo-team", lostAt)
    }

    @Test
    fun nonStreamingMembersReportUnreachableByThrowingAfterConnectionLost() = runBlocking {
        val plugin = MockExternalPlugin()
        plugin.start()
        var lostCount = 0
        val driver = driverFor(plugin) { lostCount += 1 }
        plugin.stop()
        try {
            runBlocking { driver.openSession("/ws/demo", null) }
            fail("openSession 对不可达插件必须抛出（控制面转 502），不能编一个句柄")
        } catch (expected: java.io.IOException) {
            // 预期路径
        }
        assertEquals(1, lostCount, "断连回调在非流式成员上同样触发")
    }

    private fun MockExternalPlugin.use(block: suspend (MockExternalPlugin) -> Unit) =
        try {
            runBlocking { block(this@use) }
        } finally {
            stop()
        }
}
