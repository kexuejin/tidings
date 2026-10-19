// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.DeliveryState
import app.relay.app.model.MessageUi
import app.relay.app.model.PeerUi
import app.relay.core.nowMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 设置项的写入：把 UI 的开关与输入落到 store，再刷新能力声明与远程中继。
 *
 * 切出来的理由：这一族的演化动力与其它 7 个分片互不相干。过去它们都是
 * `internal fun RelayController.` 的顶层扩展，凑在一起约 3000 行；改其中一族要在
 * 另外六族里找它依赖了什么。
 *
 * @param host 提供本族用到的控制器成员与同包顶层扩展。逐个 `host.` 前缀而不是
 *   持有整个控制器，是为了让**依赖面完全显式**：读方法体就知道这一族碰了哪些状态。
 *   只对本族真正用到的名字加前缀 —— 同名的函数形参会遮蔽控制器成员（`state` 就是
 *   一例：控制器有 `state: StateFlow<RelayUiState>`，而 agent 帧形参叫 `state: String?`），
 *   给它加前缀会直接产出语法垃圾。
 */
internal class RelayControllerSettingsComponent(
    private val host: RelayController,
) {
fun handleUpdatePersistMessages(enabled: Boolean) {
    if (host.persistMessages == enabled) return
    host.persistMessages = enabled
    // persistMessagesDecided 必须一起写：它是"用户显式做过选择"的唯一记录，
    // 也是 migrateRelaySettings 唯一认的判据。只写值不写这一位的话，用户关掉之后
    // 下次启动仍会被当成"从没设过"而补回开启 —— 开关就又骗人了。
    host.handlePersist(
        host._state.value.settings.copy(persistMessages = enabled, persistMessagesDecided = true),
    )
    if (enabled) host.handlePersistThreads()
}


fun handleUpdateThemeMode(wire: String) {
    if (host.themeMode == wire) return
    host.handlePersist(host._state.value.settings.copy(themeMode = wire))
}


fun handleUpdateWaitFrameCapability(enabled: Boolean) {
    updateCapability { it.copy(waitFrameCapability = enabled) }
}


fun handleUpdateRecordMessageCapability(enabled: Boolean) {
    updateCapability { it.copy(recordMessageCapability = enabled) }
}

private fun updateCapability(transform: (RelaySettings) -> RelaySettings) {
    val current = host._state.value.settings
    val next = transform(current)
    if (next == current) return
    host.handlePersist(next.copy(profileRev = current.profileRev + 1))
    host._state.update { it.copy(settingsNote = BannerMessage.CapabilitiesSaved) }
    host.scope.launch { host.restart() }
}


fun handleUpdateNotifyAgentMessages(enabled: Boolean) {
    updateNotificationPreference { it.copy(notifyAgentMessages = enabled) }
}


fun handleUpdateNotifyMentionOnly(enabled: Boolean) {
    updateNotificationPreference { it.copy(notifyMentionOnly = enabled) }
}


fun handleUpdateNotifyQuietHours(enabled: Boolean) {
    updateNotificationPreference { it.copy(notifyQuietHours = enabled) }
}


/**
 * 「后台保持连接」开关（Android 专属能力，见 RelaySettings.keepConnectionAlive）。
 *
 * 两件事分开做、且都必须做：
 * 1. 落盘 —— 这是用户偏好，重启后必须还在这儿（否则开关在骗人）；
 * 2. 平台动作 —— 拉起/停掉常驻通知的前台服务。**关掉必须真的停服务**，
 *    设置页那句"关掉立即停止"就是这么承诺的。
 *
 * 平台动作用 host.scope.launch：setEnabled 是挂起函数（Android 侧要读 Compose
 * 资源当通知文案），而这里在 UI 的事件回调里，不能挂起。
 */
fun handleUpdateKeepConnectionAlive(enabled: Boolean) {
    val current = host._state.value.settings
    if (current.keepConnectionAlive == enabled) return
    host.handlePersist(current.copy(keepConnectionAlive = enabled))
    host.scope.launch { host.platform.backgroundConnection?.setEnabled(enabled) }
}

private fun updateNotificationPreference(transform: (RelaySettings) -> RelaySettings) {
    val current = host._state.value.settings
    val next = transform(current)
    if (next == current) return
    host.handlePersist(next)
}


fun handleSaveProfile(
    nick: String,
    company: String,
    dept: String,
    team: String,
    avatar: Int,
) {
    val trimmedNick = nick.trim()
    if (trimmedNick.isEmpty()) {
        host.warn(BannerMessage.NickEmpty)
        return
    }
    val settings = host._state.value.settings
    val next = settings.copy(
        nick = trimmedNick.take(RelaySettings.LIMIT_NICK),
        company = company.trim().take(RelaySettings.LIMIT_COMPANY),
        dept = dept.trim().take(RelaySettings.LIMIT_DEPT),
        team = team.trim().take(RelaySettings.LIMIT_TEAM),
        avatar = avatar.coerceIn(-1, 209),
        profileRev = settings.profileRev + 1,
    )
    host.handlePersist(next)
    host._state.update { it.copy(settingsNote = BannerMessage.ProfileSaved(next.profileRev)) }
    host.scope.launch { host.restart() }
}

/**
 * 保存**本机**给某个节点起的备注（茶话间 F-DISC-9）。
 *
 * ## 为什么空串是「删除」而不是「存一个空备注」
 *
 * 空备注与没有备注在界面上完全一样（[PeerUi.displayName] 会回落到昵称），
 * 但空串留在 map 里会让 `settings.json` 越攒越多死条目 —— 清掉即干净。
 * 茶话间同款：`src/main/index.ts:2652-2653` 逐字
 * `if (trimmed) remarks.set(nodeId, trimmed); else remarks.delete(nodeId)`。
 *
 * ## 为什么不 `host.restart()`
 *
 * 与改昵称/端口不同：备注**不进协议**，对端根本不知道我们叫他什么，
 * 运行时没有任何东西需要重读它。写盘 + 更新 `host._state.settings` 之后，
 * 下一次 [RelayController.refreshPeers]（每 2 秒的轮询）就会把备注贴到 `peers` 上，
 * 界面自动跟着变 —— 重启节点只会让连接闪一下，白白打断用户。
 *
 * 但**必须**主动补一次 `host.refreshPeers()`：轮询最长有 2 秒空窗，
 * 而用户点「保存备注」时期望立刻在列表里看到新名字。
 */


/**
 * 保存**本机**给某个节点起的备注（茶话间 F-DISC-9）。
 *
 * ## 为什么空串是「删除」而不是「存一个空备注」
 *
 * 空备注与没有备注在界面上完全一样（[PeerUi.displayName] 会回落到昵称），
 * 但空串留在 map 里会让 `settings.json` 越攒越多死条目 —— 清掉即干净。
 * 茶话间同款：`src/main/index.ts:2652-2653` 逐字
 * `if (trimmed) remarks.set(nodeId, trimmed); else remarks.delete(nodeId)`。
 *
 * ## 为什么不 `host.restart()`
 *
 * 与改昵称/端口不同：备注**不进协议**，对端根本不知道我们叫他什么，
 * 运行时没有任何东西需要重读它。写盘 + 更新 `host._state.settings` 之后，
 * 下一次 [RelayController.refreshPeers]（每 2 秒的轮询）就会把备注贴到 `peers` 上，
 * 界面自动跟着变 —— 重启节点只会让连接闪一下，白白打断用户。
 *
 * 但**必须**主动补一次 `host.refreshPeers()`：轮询最长有 2 秒空窗，
 * 而用户点「保存备注」时期望立刻在列表里看到新名字。
 */
fun handleSetPeerRemark(nodeId: String, remark: String) {
    val id = nodeId.trim()
    if (id.isEmpty()) return
    // 与茶话间 `maxlength=32` 对齐（`ProfileCard.vue:141`），也与 `LIMIT_NICK` 同量级：
    // 备注是拿来在列表里认人的，32 个字符足够，再长只会把行撑坏。
    val trimmed = remark.trim().take(RelaySettings.LIMIT_REMARK)
    val current = host._state.value.settings
    val next = if (trimmed.isEmpty()) {
        current.copy(peerRemarks = current.peerRemarks - id)
    } else {
        current.copy(peerRemarks = current.peerRemarks + (id to trimmed))
    }
    if (next == current) return
    host.handlePersist(next)
    host.refreshPeers()
}


fun handleSavePorts(rawUdp: String, rawTcp: String) {
    val udp = rawUdp.trim().toIntOrNull()
    val tcp = rawTcp.trim().toIntOrNull()
    if (udp == null || udp !in 1..65535) {
        host.warn(BannerMessage.InvalidPort)
        return
    }
    if (tcp == null || tcp !in 1..65535) {
        host.warn(BannerMessage.InvalidPort)
        return
    }
    val settings = host._state.value.settings
    val next = settings.copy(udpPort = udp, tcpPort = tcp, profileRev = settings.profileRev + 1)
    host.handlePersist(next)
    host._state.update { it.copy(settingsNote = BannerMessage.PortsSaved(udp, tcp)) }
    host.scope.launch { host.restart() }
}


fun handleSetRemoteRelay(
    url: String,
    token: String,
    nodeId: String? = null,
    nick: String? = null,
) {
    val config = RemoteRelayConfig(
        url = url.trim(),
        token = token.trim(),
        nodeId = nodeId?.trim()?.ifEmpty { null },
        nick = nick?.trim()?.ifEmpty { null },
        enabled = true,
    )
    val next = host._state.value.settings.copy(remoteRelay = config)
    host.handlePersist(next)
    val displayName = config.nick ?: config.url
    host._state.update { it.copy(banner = BannerUi(BannerMessage.RemoteRelayConfigured(displayName), BannerTone.Info)) }
    if (host.runtime != null) {
        handleStartRemoteRelay(config)
    } else {
        host.refreshPeers()
    }
}


fun handleClearRemoteRelay() {
    val next = host._state.value.settings.copy(remoteRelay = null)
    host.handlePersist(next)
    handleStopRemoteRelay()
    host._state.update { it.copy(banner = BannerUi(BannerMessage.RemoteRelayCleared, BannerTone.Info)) }
}


fun handleStartRemoteRelay(config: RemoteRelayConfig) {
    handleStopRemoteRelay()
    val client = RemoteRelayClient(config, host.platform.httpClient)
    host.remoteRelayClient = client
    host.refreshPeers()
    // 起点如实：还没探活之前不算在线（第一轮探活前显示离线，而不是先骗一次在线）。
    host.remoteRelayHealthy = false
    host.remotePollingJob = host.scope.launch {
        var tick = 0
        while (isActive) {
            runCatching {
                // 每 5 个周期（≈15s）探一次活。轮询本身只拉 inbox，而 fetchInbox 把失败
                // 吞成空列表 —— 与"真的没消息"不可分，所以判活只能靠 checkHealth。
                if (tick % 5 == 0) host.remoteRelayHealthy = client.checkHealth()
                tick++
                val items = client.fetchInbox(since = host.lastRemoteSeq)
                for (item in items) {
                    if (item.seq > host.lastRemoteSeq) {
                        host.lastRemoteSeq = item.seq
                    }
                    handleRemoteInboxItem(item, config)
                }
            }
            delay(3000)
        }
    }.also { host.jobs += it }
}


fun handleStopRemoteRelay() {
    host.remotePollingJob?.cancel()
    host.remotePollingJob = null
    host.remoteRelayClient = null
    // 停掉之后不能留着上一次的"健康"：否则那条注入的 PeerUi 会继续显示在线。
    host.remoteRelayHealthy = false
    host.refreshPeers()
}


fun handleRemoteInboxItem(item: RelayInboxItem, config: RemoteRelayConfig) {
    if (item.direction != "in") return
    val targetPeerId = item.peerId.ifEmpty { config.nodeId ?: "remote-relay" }
    val text = item.envelope?.get("text")?.jsonPrimitive?.contentOrNull
        ?: item.envelope?.get("body")?.jsonPrimitive?.contentOrNull
        ?: item.envelope?.get("content")?.jsonPrimitive?.contentOrNull
        ?: ""
    if (text.isEmpty()) return
    val msgId = "remote-${item.seq}"
    host.handleAppendMessage(
        MessageUi(
            id = msgId,
            peerId = targetPeerId,
            mine = false,
            text = text,
            at = if (item.at > 0) item.at else nowMillis(),
            delivery = DeliveryState.Sent,
        ),
        incoming = true,
    )
}
}
