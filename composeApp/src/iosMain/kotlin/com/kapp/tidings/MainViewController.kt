// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings

import androidx.compose.ui.window.ComposeUIViewController
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.domain.createAgentStore
import com.kapp.tidings.domain.createMessageStore
import com.kapp.tidings.domain.createSettingsStore
import com.kapp.tidings.domain.recovery.IosKeychainRecoveryStore
import com.kapp.tidings.platform.IosAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * iOS 入口。
 *
 * 控制器挂在文件级单例上，而不是挂在 ViewController 上：和 Android 的
 * `RelayApplication` 同理，信使的监听应当与进程同寿命。挂在视图上会在视图重建时
 * 先 `stop()` 再立刻重 bind 同一个 UDP 端口，用户看到的是"切个页面就启动失败"。
 */
private val controller: RelayController by lazy {
    val platform = IosAppPlatform()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = RelayController(
        platform = platform,
        store = createSettingsStore(platform),
        scope = scope,
        // 「消息落盘」开关背后必须是真的文件存储，否则它活不过一次重启。
        messageStore = createMessageStore(platform),
        agentStore = createAgentStore(platform),
        // L0：身份包进钥匙串（本地层 + iCloud 同步层）。收养决策在控制器里
        //（settings 不存在才收养，Q10），这里只递存取实现。
        platformRecovery = IosKeychainRecoveryStore(),
    )
    // 通知被点开时，把 showNotification 收到的 focusPeerId 变成一次「定位到这个会话」
    // 的请求（协议 §8）。接在这里，是因为只有这里同时拿得到 platform 与 controller；
    // Android 走的是 PendingIntent → MainActivity，两边终点是同一个 requestFocus。
    platform.pushNotificationHandler.setOnNotificationTap { peerId ->
        controller.requestFocus(peerId)
    }
    startScreenViewProbeIfRequested(controller, scope)
    controller
}

/**
 * 验收用：`TIDINGS_SCREEN_VIEW=<对端 nodeId>` 时，启动后自动向该对端发起一次
 * 屏幕协助（Viewer 侧）。
 */
private fun startScreenViewProbeIfRequested(
    controller: RelayController,
    scope: CoroutineScope,
) {
    val env = platform.Foundation.NSProcessInfo.processInfo.environment
    val peerId = (env["TIDINGS_SCREEN_VIEW"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val seconds = (env["TIDINGS_SCREEN_VIEW_SECONDS"] as? String)?.toLongOrNull() ?: 30L
    scope.launch {
        val deadline = com.kapp.tidings.core.nowMillis() + 60_000
        while (com.kapp.tidings.core.nowMillis() < deadline) {
            val online = controller.state.value.peers.any { it.nodeId == peerId && it.online }
            if (online) break
            kotlinx.coroutines.delay(500)
        }
        println("[screen] ios probe start peer=" + peerId + " seconds=" + seconds)
        controller.requestScreenView(peerId)
        kotlinx.coroutines.delay(seconds * 1000)
        controller.stopScreenView()
        println("[screen] ios probe stop")
    }
}

fun MainViewController() = ComposeUIViewController {
    RelayApp(controller = controller)
}
