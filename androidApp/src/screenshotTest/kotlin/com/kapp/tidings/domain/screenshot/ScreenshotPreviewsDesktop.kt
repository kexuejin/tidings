// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kapp.tidings.domain.GlobalSearchResult
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.feature.settings.SettingsDataSubpage
import com.kapp.tidings.feature.settings.SettingsProfileSubpage
import com.kapp.tidings.domain.DesktopPaneActions
import com.kapp.tidings.ui.desktop.DesktopPrefsPanel
import com.kapp.tidings.ui.desktop.DesktopPreviewActions
import com.kapp.tidings.ui.desktop.DesktopPreviewSearchActions
import com.kapp.tidings.ui.desktop.DesktopSettingsChrome
import com.kapp.tidings.ui.desktop.DesktopSettingsHost
import com.kapp.tidings.ui.desktop.DesktopSettingsNav
import com.kapp.tidings.ui.desktop.DesktopShell
import com.kapp.tidings.ui.desktop.DesktopShellActions
import com.kapp.tidings.ui.desktop.DesktopView
import com.kapp.tidings.ui.desktop.PREVIEW_NOW
import com.kapp.tidings.ui.desktop.desktopPreviewCabinetPeerLoadingState
import com.kapp.tidings.ui.desktop.desktopPreviewCabinetPeerState
import com.kapp.tidings.ui.desktop.desktopPreviewCabinetState
import com.kapp.tidings.ui.desktop.desktopPreviewCabinetUnsetState
import com.kapp.tidings.ui.desktop.desktopPreviewEmptyState
import com.kapp.tidings.ui.desktop.desktopPreviewFailedState
import com.kapp.tidings.ui.desktop.desktopPreviewFilesBannerState
import com.kapp.tidings.ui.desktop.desktopPreviewFilesState
import com.kapp.tidings.ui.desktop.desktopPreviewFolderState
import com.kapp.tidings.ui.desktop.desktopPreviewGroupState
import com.kapp.tidings.ui.desktop.desktopPreviewNoFilesState
import com.kapp.tidings.ui.desktop.desktopPreviewNodesBannerState
import com.kapp.tidings.ui.desktop.desktopPreviewOfflinePeerState
import com.kapp.tidings.ui.desktop.desktopPreviewStartingState
import com.kapp.tidings.ui.desktop.desktopPreviewState
import com.kapp.tidings.ui.desktop.linPeer
import com.kapp.tidings.ui.desktop.zhouPeer
import com.kapp.tidings.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * 桌面壳的截图门禁参考图。
 *
 * 移动端那一套在 [ScreenshotPreviews]；这里是同一件事的桌面版本，和它遵守同样的
 * 三条纪律，只是换成桌面外壳的名字：
 *
 * 1. **渲染真实生产 Composable。** 每一帧都直接调 `DesktopShell` —— 菜单栏、
 *    标题栏、工具栏、竖导、状态栏全是壳自己画的。只截某一个 Pane 等于把壳删掉再
 *    说「看，Pane 没问题」。
 * 2. **参考图先评审再录制。** `updateDebugScreenshotTest` 会直接覆盖参考图；先
 *    `validateDebugScreenshotTest` 看 diff，人看过再录。
 * 3. **完全确定。** 假数据来自 `composeApp` 的 `PreviewState.kt`（它才是桌面预览的
 *    唯一数据源），时间基准写死成 [PREVIEW_NOW] 并通过 `DesktopShell` 的 `now` 参数
 *    传进去 —— 壳里的 `relativeTime` 全走这个参数，不传就等于每次渲染都是「刚刚」。
 *
 * **三个窗口尺寸对三档断点**，取自 `DesktopLayout.kt` 的实测分档：
 * `1100×720` → Expanded（工具栏 + 双栏）、`760×720` → Medium（左侧 64px 竖导 +
 * 双栏）、`560×620` → Compact（底部三段导航 + 单栏）。平板的 Medium 形态因此有真实
 * 参考图，而不是靠原型稿 `prototype/relay.html?size=medium`（`:1425`）猜。
 *
 * 交互入口无法在静态帧里表达，所以用壳自己的**冷启动初值**搭初始态：`initialView` 决定
 * 首屏落在会话/文件/文件柜/节点/工作区哪个视图，`initialPeerId` 是初始选中会话，
 * `initialQuery` 预置搜索词，`initialTransferId` 预置选中的传输项。
 * 都在 `remember { }` 里读一次，静态帧不需要等副作用；生产调用点一律用默认值。
 */
private val noActions = DesktopShellActions(
    // 桌面宿主真的能开文件管理器（`DesktopWindow.kt` 里接的是 `openDownloadsFolder`），
    // 所以参考图必须照这个能力画：不传的话文件屏空态那个「打开保存目录」不会出现，
    // 而那一支正是 `desk-files-empty-expanded` 存在的理由。
    onOpenDownloadsFolder = {},
)

/**
 * 输入栏那三支状态用的草稿（与手机端 `ScreenshotPreviewsExtra.kt` 同量级）。
 *
 * 一句话 13 字 = 39 字节：×30 ⇒ 1170 字节，过了单播上限（800）但远没到硬上限（4096），
 * 所以走 TCP 分包；×110 ⇒ 4290 字节，过了硬上限，是真的发不出去。
 */
private val tcpRouteDraft = "先把结论写清楚，再补细节。".repeat(30)
private val overLimitDraft = "先把结论写清楚，再补细节。".repeat(110)

@Composable
private fun Desk(
    widthDp: Int,
    heightDp: Int,
    darkTheme: Boolean = false,
    state: RelayUiState = desktopPreviewState(),
    initialPeerId: String? = linPeer().nodeId,
    filesView: Boolean = false,
    nodesView: Boolean = false,
    cabinetView: Boolean = false,
    agentsView: Boolean = false,
    draft: String = "",
    query: String = "",
    transferId: String? = null,
    /**
     * 这一帧用哪个控制器。
     *
     * 默认值**必须**是 [DesktopPreviewActions]：它是「什么都不做」的实现，既有 38 帧
     * 全建立在它之上。换默认值等于把那些帧的语义一起改掉，而它们不会因此变红 ——
     * 参考图是静态的，只有 `validateDebugScreenshotTest` 跑出来才知道，很容易漏。
     *
     * 只有全局搜索那几帧需要换成 [DesktopPreviewSearchActions]：接口默认的
     * `searchGlobal` 返回空结果（`DesktopPaneActions.kt:143`），用它拍搜索永远只能拍到
     * 「没找到」——那个空态是**实现决定的**，不是数据决定的，钉不住任何东西。
     */
    searchActions: DesktopPaneActions = DesktopPreviewActions,
    /**
     * 第一帧就摆出来的全局搜索结果。
     *
     * 光有 [searchActions] 拍不到 `Success` 支：面板的 `state` 初值是 `Loading`，
     * 唯一能推到 `Success` 的是 `LaunchedEffect` 的协程体，而截图渲染器不执行它
     * （`ARCHITECTURE.md` §14.5，`DesktopShell.initialGlobalSearch` 有详细说明）。
     * 所以这一支要靠这个形参把结果直接塞进第一帧。
     */
    globalSearch: GlobalSearchResult? = null,
) {
    AppTheme(darkTheme = darkTheme) {
        DesktopShell(
            state = state,
            controller = searchActions,
            windowWidthDp = widthDp,
            windowHeightDp = heightDp,
            darkTheme = darkTheme,
            controlPlanePort = 0,
            // 用壳的 initialView 冷启动落视图，不用 external*Request 计数器：
            // 计数器靠 LaunchedEffect 切视图，静态帧在副作用之前就抓完了，
            // 实测 files/nodes 帧会渲成会话视图（和 conversations 帧 sha256 相同）。
            initialView = when {
                filesView -> DesktopView.Files
                nodesView -> DesktopView.Nodes
                cabinetView -> DesktopView.Cabinet
                agentsView -> DesktopView.Agents
                else -> DesktopView.Conversations
            },
            initialPeerId = initialPeerId,
            initialDraft = draft,
            // 搜索词与选中的传输项同理：都是壳/面板内部状态，静态帧点不了，
            // 不预置的话「有会话但搜不到」和「一条会话都没有」在参考图里长得一模一样。
            initialQuery = query,
            initialTransferId = transferId,
            initialGlobalSearch = globalSearch,
            now = PREVIEW_NOW,
            actions = noActions,
        )
    }
}

// ── Expanded：1100×720，桌面主力形态 ───────────────────────────────────────

/** Expanded 的基准帧：工具栏 + 会话列表 + 聊天双栏。桌面最高频的那一屏。 */
@PreviewTest
@Preview(name = "desk-conversations-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsExpandedPreview() {
    Desk(1100, 720)
}

/** 深色主题。颜色表是同一份（`ui/theme/Color.kt`），但对比度必须单独看一遍。 */
@PreviewTest
@Preview(name = "desk-conversations-expanded-dark", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsExpandedDarkPreview() {
    Desk(1100, 720, darkTheme = true)
}

/**
 * 冷启动的空选态。
 *
 * 主窗故意不传 `initialPeerId`，所以「会话列表有 3 条、右边空白」是用户真的会遇到
 * 的第一帧 —— 只截选中态会把这个状态永远漏在参考图之外。
 */
@PreviewTest
@Preview(name = "desk-nosel-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskNoSelectionExpandedPreview() {
    Desk(1100, 720, initialPeerId = null)
}

/** 「文件」平级视图：跨会话附件的投影。五种传输态各一条，跨三个对端。 */
@PreviewTest
@Preview(name = "desk-files-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewFilesState(), filesView = true)
}

/** 「节点」平级视图：peer 卡片 + 节点运行状态。 */
@PreviewTest
@Preview(name = "desk-nodes-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskNodesExpandedPreview() {
    Desk(1100, 720, nodesView = true)
}

/** 「工作区」平级视图：左侧竖导第五格，内容是工作区看板。 */
@PreviewTest
@Preview(name = "desk-agents-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskAgentsExpandedPreview() {
    Desk(1100, 720, agentsView = true)
}

/** 离线会话：列表行、时间戳、状态栏都要显示离线语义，且不能和「待发」混淆。 */
@PreviewTest
@Preview(name = "desk-offline-peer-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskOfflinePeerExpandedPreview() {
    Desk(
        1100,
        720,
        state = desktopPreviewOfflinePeerState(),
        initialPeerId = zhouPeer().nodeId,
    )
}

/** 一个对端都没有：空态文案与「新建会话」入口。这是配对完成后的第一屏。 */
@PreviewTest
@Preview(name = "desk-empty-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskEmptyExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewEmptyState(), initialPeerId = null)
}

/**
 * 节点还在启动、会话还没恢复：会话侧栏画的是「正在恢复会话…」而不是「还没有会话」。
 *
 * 与 `desk-empty-expanded` 只差一个 `NodePhase`，所以两帧必须并排看 —— 这正是
 * 「把过程说成了结论」那条判据的对照图。移动端同一支早在 `conversations-loading`
 * 里钉住了，桌面端此前一直缺这一支（见状态槽表 D-C8）。
 */
@PreviewTest
@Preview(name = "desk-starting-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskStartingExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewStartingState(), initialPeerId = null)
}

/**
 * 节点启动失败：`NodePhase.Failed` + 无本机地址，验证失败横幅和状态栏两处措辞。
 *
 * 必须选中一个会话：横幅挂在 `ChatScreen` 上（`ChatScreen.kt:404`），
 * 没选中会话时右栏只有「选一个会话」占位，失败就只剩状态栏一个字，
 * 这一帧就白测了 —— 这正是它第一次渲染时看不出失败的原因。
 */
@PreviewTest
@Preview(name = "desk-failed-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFailedExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewFailedState(), initialPeerId = linPeer().nodeId)
}

/**
 * 输入栏：草稿过了单播上限，改走 TCP 分包。
 *
 * 空草稿时输入栏那一整行状态根本不画，所以「字节数 / 走哪条路 / 超限」三支只能靠预置草稿
 * 才录得到 —— 与手机端 `chat-composer-tcp`（`ScreenshotPreviewsExtra.kt`）同一个草稿、
 * 同一个判据，两张图放在一起才是「两端语义一致」的证据。
 *
 * 桌面上这一支的提示语与手机不同（`desktop_composer_draft` vs `chat_byte_hint`），
 * 被钉住的是**判据**：仍然发得出去，所以发送键亮着、颜色是中性的。
 */
@PreviewTest
@Preview(name = "desk-composer-tcp", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskComposerTcpPreview() {
    Desk(1100, 720, draft = tcpRouteDraft)
}

/**
 * 输入栏：草稿过了**单条硬上限**（4096 字节），真的发不出去。
 *
 * 这一支以前桌面上是错的：两态的 `ComposeByteMode` 把「换条路」和「发不出去」压成同一个
 * `overLimit`，于是草稿 5000 字节时发送键照样亮着、提示还是那句「超 800B · 走 TCP 分包」，
 * 用户按下发送才被 `RelayController` 弹一句「太长了」。现在 `Over` 是独立一态：
 * 路由行整行不画（没有路可说），草稿行换成 `chat_byte_over` 并转成 error 色，发送键置灰。
 */
@PreviewTest
@Preview(name = "desk-composer-over", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskComposerOverPreview() {
    Desk(1100, 720, draft = overLimitDraft)
}

// ── Medium：760×720，平板形态 ──────────────────────────────────────────────

/**
 * Medium 就是平板形态：左侧 64px 竖导 + 双栏，和手机底栏那套完全不是一回事。
 *
 * 这一帧是整个桌面门禁里最该盯的一张 —— 平板一直是「原型画了、代码没验证」的那一档。
 */
@PreviewTest
@Preview(name = "desk-conversations-medium", widthDp = 760, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsMediumPreview() {
    Desk(760, 720)
}

/** Medium 深色。窄栏里头像、状态点、chip 的对比度在深色下更容易塌。 */
@PreviewTest
@Preview(name = "desk-conversations-medium-dark", widthDp = 760, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsMediumDarkPreview() {
    Desk(760, 720, darkTheme = true)
}

/** Medium 下没有工具栏，视图切换靠竖导 —— 所以竖导本身必须进参考图。 */
@PreviewTest
@Preview(name = "desk-files-medium", widthDp = 760, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesMediumPreview() {
    Desk(760, 720, state = desktopPreviewFilesState(), filesView = true)
}

/** Medium 的节点视图：窄栏下节点卡片换行，是最容易被忽略的断点。 */
@PreviewTest
@Preview(name = "desk-nodes-medium", widthDp = 760, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskNodesMediumPreview() {
    Desk(760, 720, nodesView = true)
}

/** Medium 的工作区视图：左侧 64px 竖导 + 单栏工作区看板。 */
@PreviewTest
@Preview(name = "desk-agents-medium", widthDp = 760, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskAgentsMediumPreview() {
    Desk(760, 720, agentsView = true)
}

// ── Compact：560×620，窄窗 ────────────────────────────────────────────────

/**
 * Compact 不画窗口级标题栏（`DesktopShell` 明确跳过），身份信息由右栏 `.dhead` 承担。
 *
 * 这一帧专门盯那条规则有没有被回退 —— 一旦有人把标题栏改成无条件渲染，
 * 窄窗就会凭空少 40px 内容高度。
 */
@PreviewTest
@Preview(name = "desk-conversations-compact", widthDp = 560, heightDp = 620, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsCompactPreview() {
    Desk(560, 620)
}

/** Compact 空选：底部三段导航 + 「选一个会话」的单栏形状。 */
@PreviewTest
@Preview(name = "desk-nosel-compact", widthDp = 560, heightDp = 620, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskNoSelectionCompactPreview() {
    Desk(560, 620, initialPeerId = null)
}

/** Compact 深色 + 会话：三段导航与状态栏在 620px 高度上还剩多少空间。 */
@PreviewTest
@Preview(name = "desk-conversations-compact-dark", widthDp = 560, heightDp = 620, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsCompactDarkPreview() {
    Desk(560, 620, darkTheme = true)
}

/** Compact 的工作区视图：最挤的一档，底导 + 单栏看板。 */
@PreviewTest
@Preview(name = "desk-agents-compact", widthDp = 560, heightDp = 620, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskAgentsCompactPreview() {
    Desk(560, 620, agentsView = true)
}

// ── Wide：1440×900，原型 wide 档 ──────────────────────────────────────────
//
// 这一档此前**一帧都没有**，而原型断点把 1440×900 当作桌面宽屏的基准尺寸。
// 缺它不是漏拍一张图那么简单：`MIN_CHAT_HEIGHT_PX` 那个高度预算从来没人量过
// （1440×900 扣掉 chrome 138 还剩 762px，见 `DesktopLayoutTest`）。
//
// 内容刻意与 1100×720 那组一一对应（同一会话、同一文件、同一节点、同一错误态），
// 这样两组帧能直接并排对账：宽了 340×180 之后，多出来的空间到底被谁拿走了。
// 平板那组的同一批内容在 `ScreenshotPreviewsTablet.kt`，三方共用同一份 fixture。

/** Wide 基准帧：与 `desk-conversations-expanded` 同一份状态，只换窗口尺寸。 */
@PreviewTest
@Preview(name = "desk-conversations-wide", widthDp = 1440, heightDp = 900, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskConversationsWidePreview() {
    Desk(1440, 900)
}

/** Wide 的文件视图：宽屏下表格列数够不够撑满，是这一档最值得看的地方。 */
@PreviewTest
@Preview(name = "desk-files-wide", widthDp = 1440, heightDp = 900, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesWidePreview() {
    Desk(1440, 900, state = desktopPreviewFilesState(), filesView = true)
}

/** Wide 的节点视图：1440 宽时节点卡片会退化成稀疏的单列，得看一眼是不是空的。 */
@PreviewTest
@Preview(name = "desk-nodes-wide", widthDp = 1440, heightDp = 900, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskNodesWidePreview() {
    Desk(1440, 900, nodesView = true)
}

/** Wide 的失败态：与 `desk-failed-expanded` 同一份状态，验证横幅在宽屏下不被拉长失真。 */
@PreviewTest
@Preview(name = "desk-failed-wide", widthDp = 1440, heightDp = 900, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFailedWidePreview() {
    Desk(1440, 900, state = desktopPreviewFailedState(), initialPeerId = linPeer().nodeId)
}

// ── 补齐「已实现·证据不足」的七个槽 ────────────────────────────────────────
//
// 这些分支生产代码里都在（行号见 `docs/plans/2026-10-05-relay-state-slot-completion.md` §2.2–2.4），
// 但此前没有任何一帧能证明它们渲染正确 —— 缺的是 fixture 与预置初值，不是量具。
//
// 共同前提：搜索词与选中的传输项都是内部状态，静态帧没有输入事件，
// 只能靠壳的冷启动初值（`initialQuery` / `initialTransferId`）预置，否则那几支永远拍不到。

/** D-C5：有会话，但搜索词一条都没匹配上 —— 与「一条会话都没有」是两支不同的空态。 */
@PreviewTest
@Preview(name = "desk-no-match-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskNoMatchExpandedPreview() {
    Desk(1100, 720, initialPeerId = null, query = "不存在的关键词")
}

/** D-F3：Compact 档的文件屏（560 宽）。窄档是单栏，详情靠返回键退回列表。 */
@PreviewTest
@Preview(name = "desk-files-compact", widthDp = 560, heightDp = 620, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesCompactPreview() {
    Desk(560, 620, state = desktopPreviewFilesState(), filesView = true)
}

/** D-F4：文件屏空态 —— 有会话但一条带文件的消息都没有，出路是「打开保存目录」。 */
@PreviewTest
@Preview(name = "desk-files-empty-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesEmptyExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewNoFilesState(), filesView = true)
}

/** D-F5：文件屏搜索无命中 —— 出路换成「清除搜索」，不是指向保存目录。 */
@PreviewTest
@Preview(name = "desk-files-no-match-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesNoMatchExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewFilesState(), filesView = true, query = "不存在的关键词")
}

/**
 * D-F6：文件屏自己那条横幅。
 *
 * 既有 `desk-failed-expanded` 也带横幅，但它落在**会话屏**上 —— 文件屏自己的
 * `state.banner?.let { AppBanner(it, onDismiss = onDismissBanner) }` 从来没有帧证明过。
 */
@PreviewTest
@Preview(name = "desk-files-banner-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesBannerExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewFilesBannerState(), filesView = true)
}

/**
 * D-F8：已完成但 `savedPaths` 为空 —— 详情里那句「还没落盘」。
 *
 * 必须同时预置选中的传输项：这一支只在选中某一行时才渲染，列表本身看不出差别。
 */
@PreviewTest
@Preview(name = "desk-files-unsaved-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesUnsavedExpandedPreview() {
    Desk(
        1100,
        720,
        state = desktopPreviewFilesState(),
        filesView = true,
        transferId = "t-done-unsaved",
    )
}

/** D-N3：节点屏自己那条横幅（节点起不来）。同样是别屏的 `desk-failed-*` 证明不了的。 */
@PreviewTest
@Preview(name = "desk-nodes-banner-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskNodesBannerExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewNodesBannerState(), nodesView = true)
}
/**
 * D-G1：群会话被选中 —— 桌面壳里的群聊。
 *
 * 为什么必须有一张：群没有 `PeerUi`，`ChatPane` 走的是 `state.chatPeer(id) ?: state.groupPeer(group)`
 * 这条兜底路径，头部另有一整支 `group != null` 的分支。不拍这张的话，那条路径
 * 只能靠"打开桌面端、建个群、点进去"才验得到。
 *
 * 侧栏与右栏同框：左边要看得见群行（群名 + `n/3 人在线`、**不画**压角的在线圆点），
 * 右边要看得见群名头部、别人的署名、以及那条灰字的群成员变更提示。
 * 侧栏标题行上的「建群」按钮也在这张里 —— 它是群唯一的入口。
 */
@PreviewTest
@Preview(name = "desk-group-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskGroupExpandedPreview() {
    val state = desktopPreviewGroupState()
    Desk(
        1100,
        720,
        state = state,
        initialPeerId = state.conversations.first { it.isGroup }.convId,
    )
}

// ── 文件柜：审计 §9 缺口① ─────────────────────────────────────────────────
//
// 文件柜此前是**唯一一个在任何形态都没有截图门禁的一级页面**。补五帧：
// Expanded 与 Compact 各覆盖「我的」与「同事」两种右栏，因为这两支的分栏行为
// 完全不同 —— Expanded 是左栏 300 + 右栏并排，Compact 是左栏与右栏**互斥**
// （`CabinetPane` 的 `compactDetailOpen`），后者是本轮 V3 唯一会改变形状的改动，
// 不拍就等于没验。假数据来自 `PreviewState.kt` 的 `desktopPreviewCabinet*`。

/**
 * 文件柜 Expanded、**我的**文件柜：四张设置卡片一屏排完。
 *
 * 为什么这一帧值得单独存在：它是文件柜的默认落点，而四张卡片（共享目录 /
 * 默认权限 / 单独设过的同事 / 最近有人放进来）的间距、卡片圆角、以及左栏
 * 「我的文件柜」被选中时的高亮底色，全部只在这一帧里能看到。
 */
@PreviewTest
@Preview(name = "desk-cabinet-mine-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskCabinetMineExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewCabinetState(), cabinetView = true)
}

/**
 * 文件柜 Expanded、**同事**的文件柜：左栏选中林岚，右栏是目录浏览。
 *
 * 与 `desk-cabinet-mine-expanded` 只差一个 `activePeerId`，但画出来是两套完全
 * 不同的组件（`MineCabinetPanel` vs `PeerCabinetBrowser`）—— 两帧必须并排看。
 * 这一帧也是 `GridCells.Adaptive(120.dp)`（全仓唯一的网格）唯一的门禁：
 * 五个条目（两目录三文件）在右栏宽度下排成多列，列数变了这一帧就会红。
 */
@PreviewTest
@Preview(name = "desk-cabinet-peer-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskCabinetPeerExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewCabinetPeerState(), cabinetView = true)
}

/**
 * 文件柜 Compact（560×620）：左栏与右栏**互斥**，且带一个回列表的按钮。
 *
 * 这是 V3（`CabinetPane` 补 `sizeClass`）唯一会改变形状的档位：改前左栏恒 300dp、
 * 右栏被挤成一条缝；改后窄档只画一栏。审计 §8.1 已声明 `d-cabinet` 在原型里
 * **没有** compact 档，所以这一帧记录的是**代码欠账的修法**，不是原型要求 ——
 * 参考图录下来是给下一个人看「我们选了哪一种」，而不是「原型要这样」。
 */
@PreviewTest
@Preview(name = "desk-cabinet-peer-compact", widthDp = 560, heightDp = 620, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskCabinetPeerCompactPreview() {
    Desk(560, 620, state = desktopPreviewCabinetPeerState(), cabinetView = true)
}

/**
 * 文件柜 Medium（760×720）：左栏从 Expanded 的 300dp 收窄到 240dp，右栏仍并排。
 *
 * 审计 §11.3 要求形态矩阵覆盖「文件柜 × { phone, tablet 竖, tablet 横, 桌面
 * compact/medium/expanded }」，而 Medium 是其中唯一被漏掉的一格 —— 它既不是
 * Compact 的「左右互斥」，也不是 Expanded 的宽左栏，`CabinetPane` 的
 * `ContentWidths.SidebarNarrow`（240dp）这条分支**只在这一档才渲染**。
 *
 * 与 `desk-cabinet-peer-compact`（560×620）和 `desk-cabinet-peer-expanded`
 * （1100×720）用的是**同一份** `desktopPreviewCabinetPeerState()`：三帧同数据、
 * 只差宽度，可以直接并排看出左栏 300 → 240 → 单栏的收窄过程。
 *
 * 左栏宽度由 `CabinetPane` **自己按面板宽度**再判一次（`CabinetPane.kt:169-170`，
 * 理由同 `DesktopFilesPane`）：壳已在左侧切掉 64dp 竖导，这里判的是 696 而不是 760。
 * 两档都在 `MEDIUM_MIN_PX`（664）之上、`EXPANDED_MIN_PX`（1000）之下，结论一致。
 */
@PreviewTest
@Preview(name = "desk-cabinet-medium", widthDp = 760, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskCabinetMediumPreview() {
    Desk(760, 720, state = desktopPreviewCabinetPeerState(), cabinetView = true)
}

/**
 * 文件柜、**还没配置过共享目录**：只有那张「还没设置」的卡片。
 *
 * 用户第一次进文件柜必然看到这一屏，而它此前连一张图都没有。
 * 与 `desk-cabinet-mine-expanded` 是一对：那一帧证明「配好之后长什么样」，
 * 这一帧证明「没配之前长什么样」—— 两者都不可省。
 */
@PreviewTest
@Preview(name = "desk-cabinet-unset-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskCabinetUnsetExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewCabinetUnsetState(), cabinetView = true)
}

/**
 * 文件柜、同事目录**正在加载**：列表区画转圈，不能画成「这个目录是空的」。
 *
 * 「加载中」与「空目录」画成一样是这类页面最常见的谎，而 `CabinetUiState.loading`
 * 在此之前没有任何一帧证明它被用上了。
 */
@PreviewTest
@Preview(name = "desk-cabinet-loading-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskCabinetLoadingExpandedPreview() {
    Desk(1100, 720, state = desktopPreviewCabinetPeerLoadingState(), cabinetView = true)
}

// ── 文件屏：审计 §9 缺口④（transfer / resume / folder）────────────────────
//
// §9 缺口第 4 条原文：「无 transfer/resume/folder 相关预览（只有 `chat-transfer-failed`）」。
// 这一节补文件屏里那三支**只靠 `state` + `initialTransferId` 就能拍到**的分支：
// 文件夹行、以及失败行详情里的续传出口。
//
// 为什么这三支静态帧拍得到、而搜索那几支拍不到：详情面板的开合是 `FilesPane` 自己的
// `remember`，但它的**初值**可以从壳一路透进来（`initialTransferId` → `initialTransferId`），
// 所以不需要任何副作用就已经是「选中」态。搜索不是这样 —— 见本节末尾的说明。

/**
 * 文件屏、**文件夹行被选中**：列表里的角标与详情里的目录分支。
 *
 * 为什么必须单独一帧：`desktopPreviewFolderState()` 这个 fixture 在
 * `PreviewState.kt:594` 早就写好了，但**全仓没有任何一帧用它** —— 也就是说
 * `isFolder` 那两条分支（列表行的目录角标；详情卡里 `DesktopFilesPane.kt:487` 的
 * `FileDetailRow(transfer_folder_badge, transfer_folder_count)`）一直没有门禁。
 *
 * `fileCount = 3` 不是随手取的：`transfer_folder_count` 是
 * 「%1$d 个文件」，只有 `fileCount > 1` 才会走目录计数那支而不是单文件那支。
 *
 * 必须同时预置 `transferId`：这一支只在选中该行时才渲染，光看列表两行分不出差别
 * （与 `desk-files-unsaved-expanded` 同一条理由）。选中项从**未过滤**的 `rows` 现查
 * （`DesktopFilesPane.kt:170`），所以这里不需要动 `query`。
 */
@PreviewTest
@Preview(name = "desk-files-folder-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesFolderExpandedPreview() {
    Desk(
        1100,
        720,
        state = desktopPreviewFolderState(),
        filesView = true,
        transferId = "t-folder",
    )
}

/**
 * 文件屏、**失败项被选中**：详情里那条「可续传 + 继续」的出口。
 *
 * 为什么必须单独一帧：`DesktopFilesPane.kt:500` 的
 * `if (file.state == TransferState.Failed && !file.mine && onResumeTransfer != null)`
 * 是**三个条件同时成立**才画的一支 —— 失败、且不是我发的、且宿主提供了续传能力。
 * 三者缺一就整块消失，而它此前没有任何参考图。
 * `sampleFileCard(TransferState.Failed)`（`PreviewState.kt:259`）正好满足前两条
 * （`mine = false`），第三条由 `DesktopShell.kt:475` 传 `controller::resumeTransfer` 满足。
 *
 * **诚实边界**：这一帧证明的是**按钮画得对**，不是「点了会重传」。
 * 静态帧里 `controller` 是 `DesktopPreviewActions`，它的 `resumeTransfer`
 * （`DesktopPaneActions.kt:232`）是**空实现**；真的续传要连上 `RelayController` 才验得到，
 * 而本仓没有 compose ui-test 依赖（§9 缺口 3 已记过这件事）。不要把这帧读成端到端证据。
 *
 * 顺带钉住失败原因那一行：`reason = "hash-mismatch"` 走
 * `file.reason?.let { FileDetailRow(desktop_files_row_reason, it) }`。
 */
@PreviewTest
@Preview(name = "desk-files-resume-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskFilesResumeExpandedPreview() {
    Desk(
        1100,
        720,
        state = desktopPreviewFilesState(),
        filesView = true,
        transferId = "t-fail",
    )
}

// ── 搜索：曾经拍不出来的一支，以及它是怎么被接上的 ─────────────────────────
//
// 全局搜索面板的三段命中（联系人 / 聊天记录 / 文件）一度是**零门禁**，而且
// 不是靠加 fixture 或换 controller 就能补的 —— 缺的是一个冷启动接缝。
// 记在这里，因为这条链路值得下一个改搜索的人先读一遍。
//
// 链路：`Desk(query = "设计")` → `DesktopShell(initialQuery)` →
// `ConversationSidebar`。侧栏有一句提前返回：
//
//     if (query.isNotBlank() && onSearchGlobal != null) { DesktopGlobalSearchPanel(...); return }
//
// 而三个 sizeClass 分支都把 `onSearchGlobal = controller::searchGlobal` **无条件**透下去。
// 所以 `query` 一非空，侧栏**必定**走全局搜索面板，不可能落到下面那个同步的
// `remember(query, onSearchMessages)` 分支。
//
// 而面板自己的状态初值是 `Loading`，唯一能把它推到 `Success` 的是 `LaunchedEffect(query)` ——
// 而截图渲染器**根本不执行 `LaunchedEffect` 的协程体**。
//
// 这不是推断，是本仓写在案上的硬教训，`ARCHITECTURE.md` §14.5 原话：
//
//   「**结论：Compose 截图渲染器根本不执行 `LaunchedEffect` 的协程体。**
//     凡是「只存在于 effect 里的行为」都永远无法被参考图钉住 —— 门禁会绿，
//     而真机上是另一回事。」
//
// 同文件 §18.3 第 1 条又把它落成规矩：「请求计数器不能用来驱动冷启动……
// 截图渲染器**不执行协程体**（§14.5 已记）。一开始用它们决定初始视图，
// 结果 Files 与 Nodes 两帧渲染出来和 Conversations **逐字节相同**」。
// 本文件 `:111-113` 与 `DesktopShell.kt` 的 `initialView` KDoc 记的是同一件事。
//
// 所以全仓那一批 `initial*` 接缝（`initialView` / `initialQuery` / `initialTransferId` /
// `initialDraft` / `initialAgentConsoleOpen` / `initialScanMode` / `initialHistorySearch` /
// `attemptAlreadyFailed` / `initialGlobalSearch`）存在的唯一理由就是这件事 ——
// 它们把「只存在于 effect 里的行为」搬进**第一帧就能算出来**的地方。
//
// 光加 `searchActions`（能返回非空结果的 controller）**不够**：那解决的只是
// `DesktopPreviewActions.searchGlobal` 落默认实现返回空结果的问题（那个会让面板
// 永远走 `Empty`），拍摄时机的问题仍然在。所以补了 `initialResult` 这条接缝
// （`DesktopGlobalSearch.kt`），再经 `DesktopShell.initialGlobalSearch` /
// `ConversationsPane.initialGlobalSearch` 透到这里 —— 与本文件其余 `initial*` 同形。
//
// 下面两帧用的假数据直接复用 `DesktopPreviewSearchActions.searchGlobal`（它 already
// 返回 1 联系人 + 1 聊天记录 + 2 文件，其中一条 `isImage = true`，用来区分文件行的
// 两种图标）—— 不在这里另抄一份，否则两处数据迟早分叉。
//
// 另需注意（本轮接线的副作用）：既有 `desk-no-match-expanded`（`:415`）钉的是
// 侧栏的 `desktop_no_match` 空态，而新接线让 `query` 非空时该分支不可达 ——
// 它会改渲成全局搜索面板。那张参考图（`2026-10-07 20:38` 录制）早于本次接线，
// 所以 `validateDebugScreenshotTest` 下它会变红。这不是回归，是「代码改了门禁没改」，
// 正是审计 §10 结尾警告的那种假红，需与代码改动**串行**重录。

/**
 * 全局搜索的 `Success` 态：三段命中同屏。
 *
 * 钉的是 `GlobalSearchState.Success` 那一支（`DesktopGlobalSearch.kt` 的三段分组 +
 * 两条文件行按 `isImage` 分画的两种图标）。这一支此前**一张图都没有**，
 * 且不是漏了 —— 是拍摄时机上够不着（推导见上面那段注释）。
 */
@PreviewTest
@Preview(name = "desk-search-success-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskSearchSuccessExpandedPreview() {
    Desk(
        1100,
        720,
        searchActions = DesktopPreviewSearchActions,
        query = "设计",
        globalSearch = DesktopPreviewSearchActions.searchGlobal("设计"),
    )
}

/**
 * 同一份结果的深色版。
 *
 * 命中行用的是 `primaryContainer` / `outlineVariant` 这类主题色，分组头用 `primary`，
 * 而三段之间还夹着 `HorizontalDivider` —— 深色下最容易出现「分割线看不见」或
 * 「次级文字糊在背景上」，这种对比度问题在浅色下完全看不出来。
 */
@PreviewTest
@Preview(name = "desk-search-success-dark", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskSearchSuccessDarkPreview() {
    Desk(
        1100,
        720,
        darkTheme = true,
        searchActions = DesktopPreviewSearchActions,
        query = "设计",
        globalSearch = DesktopPreviewSearchActions.searchGlobal("设计"),
    )
}

/**
 * 设置窗「身份与连接」页（[DesktopSettingsNav.Identity]），深色。
 *
 * 这一帧直接调共享壳 [DesktopSettingsChrome]（不再依赖 `:desktopApp` 的 `DesktopHost`，
 * 见 `composeApp/.../ui/desktop/DesktopSettingsChrome.kt`），右侧内容槽放生产子页
 * [SettingsProfileSubpage] —— 它内嵌 [com.kapp.tidings.ui.components.AvatarPicker]，所以这一帧
 * 同时覆盖 `m-set-avatar`（头像编辑整页）。假数据来自 `desktopPreviewState()`（昵称「我的桌面」、
 * 头像 0），与桌面预览同源。
 *
 * 这是 `d-settings` 那一行卡了多轮的「模块边界」根因修复：截图门禁只能渲染
 * `androidApp`，依赖不到 `:desktopApp`，于是设置窗整屏此前永远出不了参考帧。
 */
@PreviewTest
@Preview(name = "desk-settings-identity-dark", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskSettingsIdentityPreview() {
    AppTheme(darkTheme = true) {
        DesktopSettingsChrome(
            selected = DesktopSettingsNav.Identity,
            onSelect = {},
        ) {
            SettingsProfileSubpage(
                state = desktopPreviewState(),
                onSaveProfile = { _, _, _, _, _ -> },
                onBack = null,
                // 与手机帧同理：第三档「上传图片」在生产侧有出口（`App.kt` 的
                // `SETTINGS_PAGE_AVATAR`），不传就是画置灰态 —— 那不是产品。
                onOpenAvatarPage = {},
            // 武装这一支：可空出口不传则依赖它的界面根本不渲染（截图证据里没有它）。
            onRemovePickedAvatar = {},
            )
        }
    }
}

/**
 * 设置窗「桌面」页（[DesktopSettingsNav.Desktop]），深色。
 *
 * 右侧内容槽放共享的 [DesktopPrefsPanel]，宿主传一个纯内存假实现
 * [FakeSettingsHost]（生产路径上这个槽位是 `:desktopApp` 的 `DesktopHost`，它实现
 * 了同一个 [com.kapp.tidings.ui.desktop.DesktopSettingsHost] 接口）。这一帧覆盖 `d-settings-desk`。
 */
@PreviewTest
@Preview(name = "desk-settings-desk-dark", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskSettingsDeskPreview() {
    AppTheme(darkTheme = true) {
        DesktopSettingsChrome(
            selected = DesktopSettingsNav.Desktop,
            onSelect = {},
        ) {
            DesktopPrefsPanel(host = FakeSettingsHost)
        }
    }
}

/**
 * 截图门禁用的纯内存 [com.kapp.tidings.ui.desktop.DesktopSettingsHost] 假实现。
 *
 * 生产路径上这个接口由 `:desktopApp` 的 `DesktopHost` 实现（它持有 AWT 托盘、登录项、
 * 协程作用域等），而 `androidApp` 的依赖图里根本没有 `:desktopApp`，所以预览只能喂一个
 * 不碰任何平台能力的最小假对象。默认值：省电关、自启关、系统支持自启、无错误。
 */
private object FakeSettingsHost : DesktopSettingsHost {
    override val powerSaver: Boolean = false
    override val launchAtLogin: Boolean = false
    override val launchAtLoginSupported: Boolean = true
    override val launchAtLoginError: String? = null
    override fun applyPowerSaver(enabled: Boolean) = Unit
    override fun applyLaunchAtLogin(enabled: Boolean) = Unit
}

/**
 * 设置窗「数据与存储」页（[DesktopSettingsNav.DataStorage]），深色 —— `d-settings-data` 的**桌面形态**帧。
 *
 * 为什么补：`d-settings-data` 这一行此前只填了三张 **393×1600 的手机帧**
 * （`SettingsMoreStoragePhonePreview` / `SettingsMoreLoadingPhonePreview` /
 * `SettingsMoreLongTextPhonePreview`），桌面宽度（>600dp）的帧一张都没有 —— 桌面形态没有证据。
 *
 * 数字逐字取自原型 `screenDSettingsData()`（`prototype/relay.html:5282-5303`）：数据目录
 * `~/Library/Application Support/捎话`（`:5287`）、占用 `86.2 MB · 其中缓存 12.0 MB`（`:5289`）、
 * 续传残片 `3 个 · 18.2 MB`（`:5299`）。这三串数与手机那屏**不同**（`screenSetData()`，`:3920`
 * 是 `12.4 MB · 其中缓存 3.1 MB`），所以不能照抄手机帧的夹具。
 *
 * 形参对齐生产桌面宿主（`desktopApp/.../DesktopHosting.kt:592-611`）而不是照抄原型：
 * `onOpenDataDirectory` 传（原型 `:5294` 有「打开」），`onChangeDataDirectory` **不传**
 * —— 生产没接（`SettingsDataSubpage.kt:65-67` 解释 SAF / security-scoped bookmark 取回来的路径
 * 不能直接当数据目录用），所以原型 `:5293` 那颗「更改目录」在代码里根本不存在。
 *
 * 本帧会如实暴露两处已知缺口（都不在本单写域，见报告 §12）：①`formatBytes` 省掉 `.0`
 * ⇒ 缓存渲染成 `12 MB`，而原型写的是 `12.0 MB`；②[SettingsDataSubpage] 走
 * `SettingsPageScaffold`，它在桌面仍画手机大标题栏，而原型桌面页只有一行 `.grphead`
 * 「数据与存储」（`:5284`）。
 */
@PreviewTest
@Preview(name = "desk-settings-data-dark", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskSettingsDataPreview() {
    AppTheme(darkTheme = true) {
        DesktopSettingsChrome(
            selected = DesktopSettingsNav.DataStorage,
            onSelect = {},
        ) {
            SettingsDataSubpage(
                dataDirectory = "~/Library/Application Support/捎话",
                dataUsageBytes = DESK_DATA_USAGE_BYTES,
                cacheUsageBytes = DESK_CACHE_USAGE_BYTES,
                leftoverPartCount = 3,
                leftoverPartBytes = DESK_LEFTOVER_BYTES,
                onClearLeftoverParts = {},
                onBack = null,
                onOpenDataDirectory = {},
                persistMessagesEnabled = true,
                onPersistMessagesEnabledChange = {},
                onClearHistory = {},
                onExperimentalLuciolaChange = {},
            )
        }
    }
}

/** 原型 `d11b-settings-data` 的「占用」：`86.2 MB`（`prototype/relay.html:5289`）。 */
private const val DESK_DATA_USAGE_BYTES = 90_387_251L

/**
 * 同上：`12.0 MB`。
 *
 * 注意 `formatBytes` → `tenths`（`composeApp/.../ui/components/Common.kt:557-563`）的
 * `frac == 0L` 分支会省掉小数 ⇒ 帧上实际渲染成 `12 MB`。这里仍按原型的字节数喂，
 * 不改成 12.1 MB 去凑那个字面量。
 */
private const val DESK_CACHE_USAGE_BYTES = 12_582_912L

/** 同上：续传残片 `18.2 MB`（`prototype/relay.html:5299`）。 */
private const val DESK_LEFTOVER_BYTES = 19_084_083L
