// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.agent.drivers

import com.kapp.tidings.agent.plugins.MockExternalPlugin
import com.kapp.tidings.agent.testutil.Await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 中途死亡窗口的驱动层用例（计划书 §九.2 铺路）：插件**接了单、停在半途、被拔**，
 * 看宿主驱动这条链的真实表现。
 *
 * 「插件接单一秒内完成抓不到中途死亡窗口」的现状由 [MockExternalPlugin.hangMs]
 * 破掉：接单后停在执行中途（`received` 已有 task/execute，事件流一个字节未回），
 * [MockExternalPlugin.stop] 在窗口内拔掉 —— 半途的应答随服务器一起消失。
 *
 * 判据全部是**确定会发生的事实**（接单记录、Error 事件、断连回调、事件未回），
 * 等待用轮询 + withTimeout（[Await]），零固定 delay 赌时序 ——
 * AGENTS 测试纪律与 envDependentTestGate 的共同要求。
 */
class MidExecutionDeathWindowTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun driverFor(plugin: MockExternalPlugin, onConnectionLost: (String) -> Unit): ExternalPluginDriver =
        ExternalPluginDriver(
            driverId = "echo-team",
            displayName = "Echo Team",
            baseUrl = plugin.baseUrl,
            manifest = json.decodeFromString(MockExternalPlugin.Default.echoTeamManifest().toString()),
            onConnectionLost = onConnectionLost,
        )

    /**
     * 接单 → 挂住半途 → 拔掉：驱动把「没做成」经事件通道说出口（Error 事件）+
     * 断连回调真实触发。没有挂住开关时这个窗口根本不存在（应答毫秒级回完，
     * 拔掉发生在事件流终结之后）—— 这条用例因此也钉住开关本身的效果。
     */
    @Test
    fun pluginHangingMidExecutionThenUnpluggedYieldsErrorEventAndConnectionLost() = runBlocking {
        val plugin = MockExternalPlugin()
        plugin.hangMs = 5_000L
        plugin.start()
        var lostAt: String? = null
        val driver = driverFor(plugin) { lostAt = it }

        val events = kotlinx.coroutines.channels.Channel<DriverEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val execute = scope.launch {
            driver.executeTask("echo-hang-1", "die mid-flight") { events.trySend(it) }
        }

        // ① 接单事实：请求已到插件（此时事件流还没回一个字节 —— 挂住窗口开着的证据）。
        Await.condition("插件接到 task/execute（挂住窗口应已打开）", timeoutMs = 5_000) {
            plugin.received.any { it.path == "/task/execute" }
        }

        // ② 拔掉：模拟进程被杀 —— 半途的应答随服务器一起消失。
        plugin.stop()

        // ③ 驱动如实上报：Error 事件 + 断连回调。轮询 + withTimeout，不赌固定时序。
        val error = Await.value("挂住被拔后的 Error 事件（事件通道如实上报）", timeoutMs = 10_000) {
            events.tryReceive().getOrNull() as? DriverEvent.Error
        }
        assertTrue(error.message.isNotEmpty(), "断连必须经由事件通道如实说，实际：" + error.message)
        Await.condition("断连回调触发（宿主据此把插件翻成离线）", timeoutMs = 5_000) { lostAt == "echo-team" }
        execute.cancel()
        scope.cancel()
    }

    /**
     * 挂住期间事件流确实一个字节没回（「没来得及完成」的可断言事实），
     * 拔掉后调度回执翻成 error —— 台账不把半途的活记成 finished。
     */
    @Test
    fun hungPluginEmitsNothingUntilUnpluggedAndTheOutcomeIsError() = runBlocking {
        val plugin = MockExternalPlugin()
        plugin.hangMs = 5_000L
        plugin.start()
        val driver = driverFor(plugin) {}
        val events = kotlinx.coroutines.channels.Channel<DriverEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val execute = scope.launch {
            driver.executeTask("echo-hang-2", "silence first") { events.trySend(it) }
        }

        // 接单已发生；此刻流式应答还卡在挂住里 —— 静默本身就是被钉住的状态。
        Await.condition("插件接到 task/execute", timeoutMs = 5_000) {
            plugin.received.any { it.path == "/task/execute" }
        }
        assertEquals(null, events.tryReceive().getOrNull(), "挂住期间事件流必须一个字节都没有");

        plugin.stop()
        // Error 事件是这里的因果事实：先等到它，再看执行协程收尾（顺序反了会在
        // 收尾先于消费时把「没做成」漏判成静默终局 —— 实测第一版就栽在这里）。
        Await.condition("挂住被拔后的 Error 事件", timeoutMs = 10_000) {
            events.tryReceive().getOrNull() is DriverEvent.Error
        }
        Await.condition("executeTask 协程收尾", timeoutMs = 5_000) { execute.isCompleted }
        execute.cancel()
        scope.cancel()
    }

    /**
     * 开关口径：默认 0 = 现行为不变；env 值经注入的 map 读取，乱值/负值/0 回落默认。
     * （解析函数吃 Map，测试不碰真环境 —— envDependentTestGate 的口径。）
     */
    @Test
    fun hangSwitchDefaultsToZeroAndJunkEnvFallsBackToZero() {
        val plain = MockExternalPlugin()
        assertEquals(0L, plain.hangMs, "默认挂 0 = 现行为不变");
        assertEquals(0L, MockExternalPlugin.Default.hangMsFromEnv(emptyMap()));
        assertEquals(
            1_500L,
            MockExternalPlugin.Default.hangMsFromEnv(mapOf(MockExternalPlugin.Default.HANG_ENV to "1500")),
            "合法 env 值被读取",
        );
        for (junk in listOf("0", "-5", "abc", "  ")) {
            assertEquals(
                0L,
                MockExternalPlugin.Default.hangMsFromEnv(mapOf(MockExternalPlugin.Default.HANG_ENV to junk)),
                "junk 挂住值必须回落 0，不许把夹具变成永远挂死：" + junk,
            );
        }
    }
}