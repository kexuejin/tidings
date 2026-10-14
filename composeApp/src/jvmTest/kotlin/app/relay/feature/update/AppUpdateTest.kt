// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.update

import app.relay.app.InMemoryMessageStore
import app.relay.app.InMemorySettingsStore
import app.relay.app.RelayController
import app.relay.app.RelaySettings
import app.relay.platform.InMemoryAppPlatform
import app.relay.platform.InMemorySimpleHttpClient
import app.relay.platform.InMemoryUrlOpener
import app.relay.platform.SimpleHttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateTest {

    @Test
    fun testSemanticVersionComparison() {
        assertEquals(0, compareSemanticVersions("0.1.0", "0.1.0"))
        assertTrue(compareSemanticVersions("0.1.0", "0.2.0") < 0)
        assertTrue(compareSemanticVersions("0.2.1", "0.2.0") > 0)

        // 带前缀
        assertTrue(compareSemanticVersions("relay-0.1.0", "relay-0.2.0") < 0)
        assertTrue(compareSemanticVersions("v0.61.1", "v0.61.0") > 0)
        assertTrue(compareSemanticVersions("v1.0.0", "0.9.9") > 0)

        // 段数不同
        assertEquals(0, compareSemanticVersions("0.1", "0.1.0"))
        assertTrue(compareSemanticVersions("0.1.0.1", "0.1.0") > 0)
        assertTrue(compareSemanticVersions("0.1.0", "0.1.0.1") < 0)
    }

    @Test
    fun testDefaultUpdateCheckerFallback() = runTest {
        val platform = InMemoryAppPlatform()
        val checkerNoUpdate = DefaultAppUpdateChecker(
            platform = platform,
            fallbackLatestVersion = "0.1.0",
        )
        val info1 = checkerNoUpdate.check("0.1.0")
        assertFalse(info1.hasUpdate)
        assertEquals("0.1.0", info1.latestVersion)

        val checkerHasUpdate = DefaultAppUpdateChecker(
            platform = platform,
            fallbackLatestVersion = "0.2.0",
            fallbackDownloadUrl = "https://example.com/download",
        )
        val info2 = checkerHasUpdate.check("0.1.0")
        assertTrue(info2.hasUpdate)
        assertEquals("0.2.0", info2.latestVersion)
        assertEquals("https://example.com/download", info2.downloadUrl)
    }

    @Test
    fun testDefaultUpdateCheckerHttpRemote() = runTest {
        val httpClient = InMemorySimpleHttpClient { _, url, _, _ ->
            if (url.contains("latest")) {
                SimpleHttpResponse(
                    statusCode = 200,
                    body = """{"version":"0.5.0","notes":"New features and bug fixes","url":"https://example.com/release-0.5.0"}""",
                )
            } else {
                SimpleHttpResponse(statusCode = 404, body = "Not Found")
            }
        }
        val platform = InMemoryAppPlatform(httpClient = httpClient)
        val checker = DefaultAppUpdateChecker(
            platform = platform,
            updateCheckUrl = "https://api.example.com/latest",
        )

        val info = checker.check("0.1.0")
        assertTrue(info.hasUpdate)
        assertEquals("0.5.0", info.latestVersion)
        assertEquals("New features and bug fixes", info.releaseNotes)
        assertEquals("https://example.com/release-0.5.0", info.downloadUrl)
    }

    @Test
    fun testRelayControllerUpdateFlow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val urlOpener = InMemoryUrlOpener()
        val platform = InMemoryAppPlatform()
        // 覆盖 urlOpener
        val customPlatform = object : app.relay.platform.AppPlatform by platform {
            override val urlOpener = urlOpener
        }
        val store = InMemorySettingsStore(RelaySettings.firstRun())

        val mockChecker = object : AppUpdateChecker {
            override suspend fun check(currentVersion: String): AppVersionInfo {
                return AppVersionInfo(
                    currentVersion = currentVersion,
                    latestVersion = "0.3.0",
                    hasUpdate = true,
                    releaseNotes = "Major improvements",
                    downloadUrl = "https://example.com/app-v0.3.0.apk",
                )
            }
        }

        val controller = RelayController(
            platform = customPlatform,
            store = store,
            scope = scope,
            version = "0.1.0",
            messageStore = InMemoryMessageStore(),
            updateChecker = mockChecker,
        )

        assertEquals(UpdateCheckStatus.Idle, controller.state.value.updateState.status)

        controller.checkForUpdates()
        scope.advanceUntilIdle()

        val stateAfter = controller.state.value.updateState
        assertEquals(UpdateCheckStatus.UpdateAvailable, stateAfter.status)
        assertTrue(stateAfter.info?.hasUpdate == true)
        assertEquals("0.3.0", stateAfter.info?.latestVersion)

        // 测试点击更新打开 URL
        controller.openUpdateUrl()
        assertEquals(1, urlOpener.openedUrls.size)
        assertEquals("https://example.com/app-v0.3.0.apk", urlOpener.openedUrls.first())
    }
}
