// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.relay.app.RelayUiState
import app.relay.app.model.MessageUi
import app.relay.app.navBadges
import app.relay.navigation.AppNavItems
import app.relay.ui.components.CompactScaffold
import app.relay.ui.desktop.agentPeer
import app.relay.ui.desktop.desktopPreviewState
import app.relay.ui.desktop.linPeer
import app.relay.ui.desktop.zhouPeer
import app.relay.ui.theme.AppTheme

/**
 * 聊天预览共用的壳与三个对端。
 *
 * `ChatShell` / `BareScreen` / `chatBase` / `linId` / `zhouId` / `stateWith`
 * 原来在 `ScreenshotPreviewsChat.kt` 与 `ScreenshotPreviewsR2Chat.kt` 里各写
 * 一份，**逐字相同** —— 只是 R2Chat 那份没带解释「为什么必须套 `AppTheme`」
 * 的注释。两份注释不同这件事本身就说明复制会漂。
 */

/**
 * 手机壳（带底导）。
 *
 * **必须自己套 [AppTheme]**：`CompactScaffold` 不套主题（它只画 Scaffold + 底导，
 * 主题由调用方给，生产里是 `App.kt` 里的 `AppTheme(darkTheme = dark)`）。
 * 漏掉这一层的症状是**整帧掉进 M3 默认配色**：底导选中药丸、图标底托、
 * 聊天气泡全变成基线的紫，而布局、文案、尺寸全对 —— 量具与配对都判不出来，
 * 只有开图能看见。本仓栽过一次（`AgentsPhoneShell`）。
 */
@Composable
internal fun ChatShell(
    selectedScreenId: String,
    state: RelayUiState,
    content: @Composable (Modifier) -> Unit,
) {
    AppTheme(darkTheme = false) {
        CompactScaffold(
            selectedScreenId = selectedScreenId,
            navItems = AppNavItems,
            onScreenSelected = {},
            badges = navBadges(state),
        ) { inner -> content(inner) }
    }
}

/**
 * 裸屏（不画底导）：内容自己铺满。
 *
 * 哪些屏用它写在 `ScreenshotPreviewsChat.kt` 的 KDoc 里 —— `m-chat` /
 * `m-chat-solo` / `m-chat-group` / `m-msgmenu` / `m-groupinfo` / `m-histsearch` /
 * `m-histdate` 在原型的 SCREENS 表里标了 `full:true`，原型注释写明「聊天详情在
 * 紧凑布局下是整屏，底下不画导航栏」。`ScreenshotPreviews.kt` 的
 * `ChatPhonePreview` KDoc 自己承认过这一点没做对，这里按原型来。
 */
@Composable
internal fun BareScreen(content: @Composable (Modifier) -> Unit) {
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            content(Modifier.fillMaxSize())
        }
    }
}

// ── 假数据 ────────────────────────────────────────────────────────────────
//
// 一律由 `desktopPreviewState()` 派生。三个对端的性格是现成的：
// 林岚在线、老周离线、agent-node 是 Agent（`caps` 带 `CAP_AGENT`）。

internal val chatBase: RelayUiState = desktopPreviewState()
internal val linId: String = linPeer().nodeId
internal val zhouId: String = zhouPeer().nodeId
internal val agentId: String = agentPeer().nodeId

/** 往 [chatBase] 上叠一个会话。 */
internal fun stateWith(convId: String, messages: List<MessageUi>): RelayUiState =
    chatBase.copy(messages = chatBase.messages + (convId to messages))
