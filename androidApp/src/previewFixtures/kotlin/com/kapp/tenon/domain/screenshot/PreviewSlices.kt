// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain.screenshot

import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.AvatarSlice
import com.kapp.tenon.domain.ChatSlice
import com.kapp.tenon.domain.ChromeSlice
import com.kapp.tenon.domain.NodeSlice
import com.kapp.tenon.domain.RelayUiState
import com.kapp.tenon.domain.SettingsSlice
import com.kapp.tenon.domain.TransferSlice

/**
 * 预览/截图夹具的切片包（7 片，与 `RelayStateSlices.kt` 一一对应）。
 *
 * ## 为什么逐字段手写，而不是调 `toXSlice()`
 *
 * 预览路径是「帧不变」的**证据面**。如果夹具内部调 `toXSlice()`，那么将来改投影实现（甚至删掉投影）
 * 会让证据跟着一起变 —— 证据不能依赖被它证明的东西。所以这里每个字段都从 [RelayUiState]
 * **逐一取出**，与 `RelayStateSlices.kt` 的投影保持逐字同义，并由
 * `PreviewSliceFixtureEquivalenceTest` 断言 `previewSlicesOf(state).x == state.toXSlice()`。
 *
 * `toXSlice()` 只许在测试/预览里出现（生产源码禁用的守卫见 `NoDeprecatedPhase4RefsGuardTest` 的兄弟用例）。
 */
internal data class PreviewSlices(
    val chat: ChatSlice,
    val agent: AgentSlice,
    val node: NodeSlice,
    val transfer: TransferSlice,
    val settings: SettingsSlice,
    val chrome: ChromeSlice,
    val avatar: AvatarSlice,
)

internal fun previewSlicesOf(state: RelayUiState): PreviewSlices = PreviewSlices(
    chat = ChatSlice(
        messages = state.messages,
        conversationSummaries = state.conversationSummaries,
        unreadByPeer = state.unreadByPeer,
        pendingByPeer = state.pendingByPeer,
    ),
    agent = AgentSlice(
        agentSessions = state.agentSessions,
        agentEvents = state.agentEvents,
        agentApprovals = state.agentApprovals,
        agentTasks = state.agentTasks,
        agentRuns = state.agentRuns,
        agentInvokes = state.agentInvokes,
        agentArtifacts = state.agentArtifacts,
        agentWorkspaces = state.agentWorkspaces,
        agentGrants = state.agentGrants,
        pendingGrantRequests = state.pendingGrantRequests,
        sessionPageLoading = state.sessionPageLoading,
        sessionPageExhausted = state.sessionPageExhausted,
    ),
    node = NodeSlice(
        network = state.network,
        // `selfNodeId` 是 `RelayUiState` 的**派生属性**（`get() = settings.nodeId`），
        // 不是存储字段 —— 夹具曾经漏了它，于是这里取到的是 `NodeSlice` 的默认 `""`，
        // 与投影的 `settings.nodeId` 不相等。`PreviewSliceFixtureEquivalenceTest`
        // 的 node 那一条就是为这种漂移写的（它在 `:androidApp:testDebugUnitTest`
        // 输入变化后才重跑，所以能一直红着不被人看见 —— 别用"上次是绿的"当证据）。
        selfNodeId = state.selfNodeId,
        peers = state.peers,
        mdnsServices = state.mdnsServices,
        scanProgress = state.scanProgress,
    ),
    transfer = TransferSlice(
        transferLogs = state.transferLogs,
        cabinet = state.cabinet,
        screenSession = state.screenSession,
    ),
    settings = SettingsSlice(
        settings = state.settings,
        updateState = state.updateState,
        restarting = state.restarting,
    ),
    chrome = ChromeSlice(
        banner = state.banner,
        settingsNote = state.settingsNote,
        focusRequest = state.focusRequest,
        externalStoreTick = state.externalStoreTick,
    ),
    avatar = AvatarSlice(
        avatarCache = state.avatarCache,
        pickedAvatarBytes = state.pickedAvatarBytes,
        pickedAvatarHash = state.pickedAvatarHash,
        pickedAvatarName = state.pickedAvatarName,
    ),
)
