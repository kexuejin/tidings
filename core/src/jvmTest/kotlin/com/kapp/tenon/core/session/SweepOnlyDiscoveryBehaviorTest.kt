// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tenon.core.session

import com.kapp.tenon.core.protocol.MsgTypes
import com.kapp.tenon.core.protocol.Timings
import com.kapp.tenon.core.protocol.Profile
import com.kapp.tenon.core.transport.PeerRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.assertNotNull
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * luciola 模式下**在线状态**的判据：sweep 的翻面阈值、心跳续期、能力位区分。
 *
 * ## 这个文件原来叫 LegacyDiscoveryVisibilityTest
 *
 * 它原先守的是「`legacyDiscoveryActive` 真的跟着模式走」—— 那条属性已经随 legacy 发现
 * 一起删除（本机不可能在 legacy 上，剩下的判断只有「对端有没有 luciola 能力」）。
 * 留下的是三条与传输形态无关的判据：
 *  - sweep 必须活着（哑掉 = 静默死亡永不翻面，休眠横幅不可达）；
 *  - 翻面必须由阈值驱动（不是 sweep 一跑就翻）；
 *  - 有心跳时空闲不许翻、没心跳时必须翻（反向探针）。
 */
class SweepOnlyDiscoveryBehaviorTest {

    /** 轮询节奏（env-test B 类口径：节奏用常量，不用字面量赌时序）。 */
    private val POLL_MS = 20L

    /** 缺席断言的观察窗：15 个 sweep 周期。 */
    private val ABSENCE_WINDOW_MS = 300L

    /** 空闲心跳的观察窗：3 个 offlineAfter（200ms）+ 余量，够跑十几次心跳。 */
    private val IDLE_WINDOW_MS = 600L

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

    /**
     * **D12 的行为钉**：luciola 模式的哑发现（[SweepOnlyDiscoveryEngine]）虽然不做
     * 发现动作，但 sweep 必须活着 —— 它是注册表唯一的 `lastSeen > offlineAfter`
     * 翻面器。哑掉 sweep 的真机后果：对端静默死亡时「在线」永不翻转、休眠横幅
     * 在 Android 上不可达（SIGSTOP 宿主 5 分钟 UI 仍「在线」）。
     *
     * 反向探针：把 offlineAfter 抬到不可能的值，同一个 peer 必须**不**翻 ——
     * 翻面确实来自 sweep 阈值，而不是别的路径顺手做的。
     */
    @Test
    fun sweepOnlyDiscoveryEngineStillFlipsSilentPeersOffline() = runBlocking {
        val registry = PeerRegistry("self")
        val peerId = "silent-peer-0001"
        val before = assertNotNull(
            registry.touch(peerId, "10.0.0.9", 17878, profile(peerId), confirmed = true),
            "touch 进来的对端应当被注册表接受",
        )
        assertTrue(before.online, "刚 touch 过的对端是在线的")
        val engine = SweepOnlyDiscoveryEngine(
            registry,
            Timings(offlineAfter = 200, sweepInterval = 20),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            engine.start(scope)
            withTimeout(5_000) {
                while (registry.values().first { it.profile.nodeId == peerId }.online) {
                    delay(POLL_MS)
                }
            }
            assertFalse(
                registry.values().first { it.profile.nodeId == peerId }.online,
                "对端静默超过 offlineAfter 后必须被 sweep 翻成离线（D12）",
            )
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    @Test
    fun sweepRespectsTheThresholdInsteadOfFlippingEverything() = runBlocking {
        val registry = PeerRegistry("self")
        val peerId = "alive-peer-0001"
        registry.touch(peerId, "10.0.0.8", 17878, profile(peerId), confirmed = true)
        val engine = SweepOnlyDiscoveryEngine(
            registry,
            Timings(offlineAfter = Long.MAX_VALUE, sweepInterval = 20),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            engine.start(scope)
            // 缺席断言没有"等到发生"可轮询：等满 15 个 sweep 周期（300ms / 20ms）
            // 仍在线，才能说"翻面由阈值驱动而不是 sweep 本身"。周期数给足余量，
            // 不是赌一次时序。
            delay(ABSENCE_WINDOW_MS)
            assertTrue(
                registry.values().first { it.profile.nodeId == peerId }.online,
                "阈值内（刚 touch 过）的对端不允许被 sweep 翻掉 —— 翻面必须由阈值驱动",
            )
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    /**
     * **空闲不等于离线**：新传输路径上也必须有定向心跳。
     *
     * 真机读数（2026-11-10 16:4x，Pixel 6 前台、进程活着、UDP 可达）：`lastSeen`
     * 一路涨到 104.7 秒，宿主把手机判成 `online=false`，补发队列 `left=44` 只涨不降，
     * 界面挂「宿主设备睡眠」横幅；只有重启 App（触发一次 announce）才恢复。
     *
     * 判据是**整个窗口内一次都不许翻**，不是窗口末读一眼：闪断一次就够让补发停摆，
     * 只读末态会漏掉「翻了又翻回来」那种形态。
     */
    @Test
    fun idlePeerNeverFlicksOfflineWhenHeartbeatIsWired() = runBlocking {
        val registry = PeerRegistry("self")
        val peerId = "idle-peer-0001"
        registry.touch(peerId, "10.0.0.7", 17878, profile(peerId), confirmed = true)
        var beats = 0
        val engine = SweepOnlyDiscoveryEngine(
            registry,
            Timings(offlineAfter = 200, sweepInterval = 20, presenceInterval = 40),
            // 心跳真正做的事是「发一个包，对端回帧」。这里用一次 touch 代替那趟往返：
            // 被测的是「心跳有没有把 lastSeen 续上」，不是 UDP 本身。
            heartbeat = {
                beats++
                registry.touch(peerId, "10.0.0.7", 17878, profile(peerId), confirmed = false)
                1 to 1
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            engine.start(scope)
            val startMark = TimeSource.Monotonic.markNow()
            while (startMark.elapsedNow() < IDLE_WINDOW_MS.milliseconds) {
                assertTrue(
                    registry.values().first { it.profile.nodeId == peerId }.online,
                    "有心跳时空闲对端不得被判离线（真机 104.7 秒被翻成 false）",
                )
                delay(POLL_MS)
            }
            assertTrue(beats > 0, "心跳必须真的跑过：beats=$beats")
        } finally {
            engine.stop()
            scope.cancel()
        }
    }

    /**
     * 反向探针：把心跳摘掉（修改前的形状），同一个对端必须在同一窗口内被判离线。
     *
     * 没有这一条，上面那条可以被「sweep 根本没跑」蒙混过去。
     */
    @Test
    fun idlePeerGoesOfflineWhenHeartbeatIsMissing() = runBlocking {
        val registry = PeerRegistry("self")
        val peerId = "idle-peer-0002"
        registry.touch(peerId, "10.0.0.7", 17878, profile(peerId), confirmed = true)
        val engine = SweepOnlyDiscoveryEngine(
            registry,
            Timings(offlineAfter = 200, sweepInterval = 20, presenceInterval = 40),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            engine.start(scope)
            withTimeout(5_000) {
                while (registry.values().first { it.profile.nodeId == peerId }.online) {
                    delay(POLL_MS)
                }
            }
            assertFalse(
                registry.values().first { it.profile.nodeId == peerId }.online,
                "没有心跳时空闲对端必须翻离线 —— 否则说明 sweep 没在跑",
            )
        } finally {
            engine.stop()
            scope.cancel()
        }
    }
}