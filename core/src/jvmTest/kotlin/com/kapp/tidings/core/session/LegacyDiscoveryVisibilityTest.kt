// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.core.session

import com.kapp.tidings.core.protocol.MsgTypes
import com.kapp.tidings.core.protocol.Profile
import com.kapp.tidings.core.transport.UdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **老用户共存的可观测前提**：luciola 模式下 legacy 发现关闭这个事实，必须能被读出来。
 *
 * ## 为什么这条值得一个测试
 *
 * 开启 luciola 模式后 `legacyDiscoveryEnabled = false` —— legacy 发现整体不启动。
 * 后果是**没升级的老设备既发现不到我们、我们也发现不到它**，而界面上它只是静静地
 * 显示成"离线"，与"设备关机"看起来完全一样。
 *
 * 转正（默认开）之前，这个后果必须**可见**：控制面的 peer 诊断靠
 * [NodeRuntime.legacyDiscoveryActive] 区分"关机"与"它还在老传输上"。这条测试守的就是
 * "这个标志真的跟着模式走"，而不是永远 true（那样诊断会说谎）。
 */
class LegacyDiscoveryVisibilityTest {

    private fun profile(id: String, caps: List<String> = emptyList()) = Profile(
        nodeId = id, nick = id, company = "", dept = "", team = "", avatar = 1,
        profileRev = 1, host = "h", platform = "linux", tcpPort = 17879,
        ver = "test", caps = caps,
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** 老端的档案：**没有** lnk1 / fch1 —— 未升级的设备就是这样。 */
    private fun legacyPeerProfile(id: String) = profile(id, caps = listOf(MsgTypes.CAP_MOBILE_TEXT))

    /** 新端的档案：声明了 luciola 家族的能力位。 */
    private fun migratedPeerProfile(id: String) =
        profile(id, caps = listOf(MsgTypes.CAP_LINK, MsgTypes.CAP_FILE_CHANNEL))

    @Test
    fun legacyModeReportsDiscoveryActive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = NodeRuntime(
            profile = profile("self"),
            udpPort = 0,
            tcpPort = 0,
        )
        try {
            runtime.start(scope)
            assertTrue(
                runtime.legacyDiscoveryActive,
                "legacy 模式（没有 luciola 传输工厂）必须还在跑 legacy 发现 —— " +
                    "否则诊断会把「能发现到的老设备」误报成不可见",
            )
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }

    /**
     * **真正决定 legacy 发现关不关的是"有没有自定义传输工厂"**，不是 fileChannelEnabled。
     *
     * fileChannelEnabled 管的是**数据面**（文件/画面走哪条路）；发现是不是 legacy 取决于
     * [NodeRuntime] 有没有拿到 messengerTransportFactory（RelayController 在 luciola 模式下
     * 把工厂设为 luciola 的传输）。两者在真机上同时成立，但**语义是两件事**。
     */
    @Test
    fun customTransportFactoryTurnsLegacyDiscoveryOff() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = NodeRuntime(
            profile = profile("self", caps = listOf(MsgTypes.CAP_FILE_CHANNEL)),
            udpPort = 0,
            tcpPort = 0,
            // 给一条**独立的 UDP 传输**当占位：判据只看"工厂非空"，不关心它是什么。
            messengerTransportFactory = { _ -> UdpTransport(0, "127.0.0.1") },
        )
        try {
            runtime.start(scope)
            assertFalse(
                runtime.legacyDiscoveryActive,
                "有自定义传输工厂时 legacy 发现必须关闭 —— 这是「老设备会失联」的根因，" +
                    "转正前必须能在诊断里看到它",
            )
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }

    /**
     * 只开数据面（fileChannelEnabled）而**不换传输**时，legacy 发现照样启动。
     *
     * 这条把两件事的耦合钉死：将来若有人想"只迁数据面、保留 legacy 发现"（那正是共存
     * 方案之一），这条测试告诉事实 —— 发现走哪条路由 messengerTransportFactory 决定。
     */
    @Test
    fun dataPlaneOnlyKeepsLegacyDiscoveryActive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = NodeRuntime(
            profile = profile("self", caps = listOf(MsgTypes.CAP_FILE_CHANNEL)),
            udpPort = 0,
            tcpPort = 0,
            fileChannelEnabled = true,
        )
        try {
            runtime.start(scope)
            assertTrue(
                runtime.legacyDiscoveryActive,
                "只开数据面、不换传输工厂时，legacy 发现仍在跑 —— " +
                    "这正是「双发现并行」那种共存方案的技术前提",
            )
        } finally {
            runtime.stop()
            scope.cancel()
        }
    }

    /**
     * 老端 vs 新端的能力位判据（控制面 `reachability` 那一格用的就是这条）。
     *
     * 不写在这条测试里断言控制面输出（那要起 HTTP），而是钉住判据本身：
     * 老端没有 `lnk1`/`fch1`，新端有 —— 诊断据此分"关机"与"它还在老传输上"。
     */
    @Test
    fun legacyAndMigratedPeersAreDistinguishableByCaps() {
        val legacy = legacyPeerProfile("old-device")
        val migrated = migratedPeerProfile("new-device")
        val isMigrated = { p: Profile ->
            p.caps.contains(MsgTypes.CAP_LINK) && p.caps.contains(MsgTypes.CAP_FILE_CHANNEL)
        }
        assertFalse(isMigrated(legacy), "没升级的老端不该被判成已迁移")
        assertTrue(isMigrated(migrated), "声明了 lnk1+fch1 的新端必须被判成已迁移")
    }
}
