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

/**
 * iOS 入口。
 *
 * 控制器挂在文件级单例上，而不是挂在 ViewController 上：和 Android 的
 * `RelayApplication` 同理，信使的监听应当与进程同寿命。挂在视图上会在视图重建时
 * 先 `stop()` 再立刻重 bind 同一个 UDP 端口，用户看到的是"切个页面就启动失败"。
 */
private val controller: RelayController by lazy {
    val platform = IosAppPlatform()
    val controller = RelayController(
        platform = platform,
        store = createSettingsStore(platform),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
    controller
}

fun MainViewController() = ComposeUIViewController {
    RelayApp(controller = controller)
}
