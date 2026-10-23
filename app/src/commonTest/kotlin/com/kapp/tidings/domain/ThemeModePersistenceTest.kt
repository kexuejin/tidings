// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.platform.InMemoryAppPlatform
// ThemeMode 现在住在同一个包（领域侧），不再从 ui.theme 取 —— 这条 import 的消失
// 本身就是「设置持久化不依赖 UI 层」的证据。
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「外观档位」重启后还在不在。
 *
 * 这一条和 [MessagePersistenceTest] 守的是同一类承诺：设置屏上摆了一个三选一，
 * 用户点下去就认为**选定了**。如果档位只活在内存里，下次启动又跟着系统跑，
 * 那个选择器就是在骗人 —— 这正是审计里 P1-11 的原话（"themeMode 落盘"）。
 *
 * 这里刻意**不**用 `rememberSaveable` 来验：持久化必须走设置文件，
 * 否则配置变更时 Compose 快照和设置文件会各说各话，两个事实来源。
 */
class ThemeModePersistenceTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(store: RecordingSettingsStore) = RelayController(
        platform = InMemoryAppPlatform(),
        store = store,
        scope = scope,
    )

    @Test
    fun pickingAThemeModeWritesItToTheSettingsFile() = runTest {
        val settings = RecordingSettingsStore()
        val controller = controller(settings)

        controller.updateThemeMode(ThemeMode.Dark.wire)

        assertEquals(ThemeMode.Dark.wire, settings.current?.themeMode, "选的档位必须落到设置里")
        assertEquals(ThemeMode.Dark.wire, controller.themeMode, "控制器上的只读视图也要跟上")
    }

    @Test
    fun pickingTheSameThemeModeTwiceDoesNotWriteAgain() = runTest {
        val settings = RecordingSettingsStore()
        val controller = controller(settings)

        controller.updateThemeMode(ThemeMode.Light.wire)
        val saves = settings.saveCount
        controller.updateThemeMode(ThemeMode.Light.wire)

        assertEquals(saves, settings.saveCount, "值没变就不该再写一次设置文件")
    }

    @Test
    fun aControllerRebuiltFromSavedSettingsStartsWithTheThemeTheUserPicked() = runTest {
        val settings = RecordingSettingsStore().apply {
            current = RelaySettings(nodeId = "node-1", themeMode = ThemeMode.Dark.wire)
        }

        val controller = controller(settings)

        assertEquals(
            ThemeMode.Dark,
            ThemeMode.fromWire(controller.themeMode),
            "重启后必须是用户选过的那一档，而不是回落到跟随系统",
        )
    }

    @Test
    fun theFirstRunDefaultMeansFollowTheSystem() = runTest {
        // 全新安装：设置里没有这个键，落到 RelaySettings 的默认值（空串）。
        // 空串由 ThemeMode.fromWire 兜成 System —— 认不出的值一律安全回落，
        // 所以"文件被手改成垃圾"也不会让应用起不来。
        val controller = controller(RecordingSettingsStore())

        assertEquals("", controller.themeMode, "全新安装不该凭空写下任何档位")
        assertEquals(ThemeMode.System, ThemeMode.fromWire(controller.themeMode))
    }

    /** 设置侧的替身：既当"设置文件"，又能被测试读回来看存了什么。 */
    private class RecordingSettingsStore(
        var current: RelaySettings? = null,
    ) : SettingsStore {
        var saveCount = 0
            private set

        override fun load(): RelaySettings? = current

        override fun save(settings: RelaySettings) {
            saveCount++
            current = settings
        }
    }
}
