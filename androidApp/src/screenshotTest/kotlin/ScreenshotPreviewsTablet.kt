// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.relay.app.RelayUiState
import app.relay.feature.settings.SettingsScreen
import app.relay.ui.desktop.DesktopChromeMode
import app.relay.ui.desktop.DesktopPreviewActions
import app.relay.ui.desktop.DesktopShell
import app.relay.ui.desktop.DesktopShellActions
import app.relay.ui.desktop.DesktopView
import app.relay.ui.desktop.PREVIEW_NOW
import app.relay.ui.desktop.desktopPreviewCabinetPeerState
import app.relay.ui.desktop.desktopPreviewFailedState
import app.relay.ui.desktop.desktopPreviewFilesState
import app.relay.ui.desktop.desktopPreviewState
import app.relay.ui.desktop.linPeer
import app.relay.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * 平板壳的截图门禁参考图 —— 同一个 [DesktopShell]，只是 [DesktopChromeMode.Tablet]：
 * 菜单栏 / 标题栏 / 状态栏不画（那是桌面 chrome），工具栏保留并多出「更多」溢出菜单，
 * 原本挂在菜单栏和 ⌘ 快捷键上的动作都从这里进。
 *
 * 数据与时间跟 [ScreenshotPreviewsDesktop] 完全一致（`PreviewState.kt` + `PREVIEW_NOW`），
 * 这样两套参考图可以逐像素对照：同一份假数据、同一档宽度，差别只来自 chrome 模式。
 *
 * **尺寸不是随便取的。** 生产里这个壳只在「双栏」档出现：`App.kt` 的
 * `resolveAppLayoutMode` 要求 `width >= DesktopMetrics.MEDIUM_MIN_PX && height >= 600`
 * （MediumTwoPane）或 `width >= 1000`（ExpandedListDetail），两条都不满足就落到手机外壳
 * （`CompactScaffold`）。所以这里最小的一帧是 664×620 —— 它等于
 * `MEDIUM_RAIL_PX + SIDEBAR_PX + MIN_CHAT_PANE_PX`（64 + 300 + 300），
 * 也就是聊天栏刚拿到下限的那一档。再窄一档（620×620）生产走 `CompactSinglePane`，
 * 那样录出来的壳用户**永远到不了**，门禁绿着却在守一个不存在的画面。
 */
private val noTabletActions = DesktopShellActions()

@Composable
private fun Tablet(
    widthDp: Int,
    heightDp: Int,
    darkTheme: Boolean = false,
    state: RelayUiState = desktopPreviewState(),
    initialPeerId: String? = linPeer().nodeId,
    filesView: Boolean = false,
    nodesView: Boolean = false,
    cabinetView: Boolean = false,
    agentsView: Boolean = false,
) {
    AppTheme(darkTheme = darkTheme) {
        DesktopShell(
            state = state,
            controller = DesktopPreviewActions,
            windowWidthDp = widthDp,
            windowHeightDp = heightDp,
            darkTheme = darkTheme,
            controlPlanePort = 0,
            chromeMode = DesktopChromeMode.Tablet,
            initialView = when {
                filesView -> DesktopView.Files
                nodesView -> DesktopView.Nodes
                cabinetView -> DesktopView.Cabinet
                agentsView -> DesktopView.Agents
                else -> DesktopView.Conversations
            },
            initialPeerId = initialPeerId,
            now = PREVIEW_NOW,
            actions = noTabletActions,
        )
    }
}

@PreviewTest
@Preview(name = "tablet-expanded", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletConversationsExpandedPreview() {
    Tablet(1100, 720)
}

@PreviewTest
@Preview(name = "tablet-medium", widthDp = 760, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletConversationsMediumPreview() {
    Tablet(760, 720)
}

@PreviewTest
@Preview(name = "tablet-portrait", widthDp = 800, heightDp = 1280, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletConversationsPortraitPreview() {
    Tablet(800, 1280)
}

@PreviewTest
@Preview(name = "tablet-landscape", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletConversationsLandscapePreview() {
    Tablet(1280, 800)
}

/**
 * 平板横屏的暗色版。
 *
 * 这一帧补的是 `ScreenshotPreviews.kt` 里那张 `conversations-chat-tablet-land-dark`：
 * 它名里写着 tablet，画的却是手搓的 `Row(会话列表 + ChatScreen)` —— 而 1280×800 在生产
 * 走的是 [DesktopShell]，两个外壳**不嵌套**（见 `App.kt` 的说明），所以那张图里的双栏
 * 是搭出来的，不是跑出来的。这里换成真壳、真暗色 token，名字也不再自称是手机输入栏的帧。
 */
@PreviewTest
@Preview(name = "tablet-landscape-dark", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletConversationsLandscapeDarkPreview() {
    Tablet(1280, 800, darkTheme = true)
}

/**
 * 平板能取到的最小双栏窗口：664×620。
 *
 * 断点就是这一档，所以这一帧钉的是"贴着断点"的画面 —— 工具栏、溢出菜单、左侧导航栏，
 * 以及**只剩 300px 的聊天栏**（桌面输入栏在这里已经丢掉键盘提示，靠省略号截断草稿行）。
 * 曾经这一帧录的是 620×620，那时断点写 600：聊天栏只剩 256px，底行两条提示语叠在一起、
 * 发送按钮被挤出屏幕 —— 图录下来了，但那是"窄窗不该分栏"的证据，不是可接受的画面。
 * 现在 620×620 生产走 `CompactSinglePane`（手机外壳），这里只录真正的双栏下限。
 */
@PreviewTest
@Preview(name = "tablet-two-pane-min", widthDp = 664, heightDp = 620, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletConversationsTwoPaneMinPreview() {
    Tablet(664, 620)
}

@PreviewTest
@Preview(name = "tablet-files-portrait", widthDp = 800, heightDp = 1280, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletFilesPortraitPreview() {
    Tablet(800, 1280, state = desktopPreviewFilesState(), filesView = true)
}

// ── 文件柜：审计 §11.3 形态矩阵 ─────────────────────────────────────────────
//
// §11.3 要求形态矩阵覆盖「文件柜 × { phone, tablet 竖, tablet 横, 桌面
// compact/medium/expanded }」。平板这两格此前**一帧都没有** —— 文件柜的
// 平板形态从来没有被验过，而它恰好是「同一份 `CabinetPane`，壳不同」的样本：
// 平板只扣工具栏（`TABLET_CHROME_PX = 46`），桌面扣满 112（菜单栏改由系统菜单栏承载后
// 少了 26px）—— 同样的窗口高度，平板留给内容的高度多 66px，档位判定可能因此不同。左栏该取 300 还是 240
// 取决于 `desktopWindowSizeClass` 判出来的档位（见下两帧的 KDoc）。

/**
 * 平板竖屏（800×1280）的文件柜：`desktopWindowSizeClass` 判 **Medium**。
 *
 * 可达性不是猜的：`DesktopLayout.kt:144-157` 在 Tablet chrome 下只扣
 * `TABLET_CHROME_PX = TOOLBAR_PX = 46`（`:135`），800×1280 的可用高度是 1234、
 * 宽度 800 —— 宽度不到 `EXPANDED_MIN_PX`（1000）但过 `MEDIUM_MIN_PX`（664），
 * 所以落 Medium，左栏取 `ContentWidths.SidebarNarrow`（240dp）。
 *
 * 与 `tablet-cabinet-land` 是一对，但**起决定作用的是宽度不是高度**：这一帧 800 宽
 * 过不了 `EXPANDED_MIN_PX`（1000），所以判 Medium；那一帧 1280 宽过了，所以判 Expanded。
 * 两帧的可用高度（1234 / 754）都远在 `MIN_CHAT_HEIGHT_PX`（420）之上，高度不参与判定。
 */
@PreviewTest
@Preview(name = "tablet-cabinet-portrait", widthDp = 800, heightDp = 1280, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletCabinetPortraitPreview() {
    Tablet(800, 1280, state = desktopPreviewCabinetPeerState(), cabinetView = true)
}

/**
 * 平板横屏（1280×800）的文件柜：`desktopWindowSizeClass` 判 **Expanded**。
 *
 * 1280 ≥ `EXPANDED_MIN_PX`（1000）且可用高度 754 ≥ `MIN_CHAT_HEIGHT_PX`（420），
 * 所以是 Expanded，左栏取 `ContentWidths.Sidebar`（300dp）、右栏目录网格拿到最宽的一档。
 * 这一帧是文件柜在**平板**上唯一能拿到宽左栏的形态，也是 `GridCells.Adaptive(120.dp)`
 * 那个网格在平板宽度下的列数证据（桌面 1100×720 的 `desk-cabinet-peer-expanded`
 * 扣掉 112px chrome 后可用宽度比这一帧少，列数可能不同）。
 *
 * 注意左栏宽度由 `CabinetPane` **自己按面板宽度**再判一次（`CabinetPane.kt:169-170`）：
 * 壳已在左侧切掉 64dp 竖导，所以这里判的是 1216 而不是 1280 —— 两档结论一致。
 */
@PreviewTest
@Preview(name = "tablet-cabinet-land", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletCabinetLandscapePreview() {
    Tablet(1280, 800, state = desktopPreviewCabinetPeerState(), cabinetView = true)
}

/**
 * 平板横屏的节点视图与失败态 —— 补齐 `docs/plans/2026-10-05-relay-tablet-desktop-layout-handoff.md`
 * §7 那条「桌面 1100×720 / 1440×900 与平板同屏同状态（会话/文件/节点/错误）对账」。
 *
 * 在那之前平板这一套只有会话和文件：节点页和错误横幅在平板上**一帧都没有**，于是
 * 「同一个状态在三种壳下长得一样吗」这个问题在平板上根本没法回答 —— 缺的那两帧正是
 * 最可能出问题的地方：节点页在宽栏里会退化成稀疏单列，错误横幅会随可用宽度拉伸，
 * 而平板比桌面少扣 66px chrome（`TABLET_CHROME_PX` 只等于工具栏），留给内容的
 * 高度反而更多。两帧都用与桌面同一份 fixture，除 chrome 外的差异都算布局漂移。
 */
@PreviewTest
@Preview(name = "tablet-nodes-land", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletNodesLandscapePreview() {
    Tablet(1280, 800, nodesView = true)
}

@PreviewTest
@Preview(name = "tablet-failed-land", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletFailedLandscapePreview() {
    Tablet(1280, 800, state = desktopPreviewFailedState(), initialPeerId = linPeer().nodeId)
}

/**
 * 平板横屏上的工作区视图。
 *
 * 这一对帧替换了原来的 `agents-tablet-land` / `agents-tablet-land-dark`：那两张走的是
 * `CompactScaffold` + `AppRoutes.AGENTS`，而 1280×800 在生产只会渲染 [DesktopShell]。
 * 2026-10-14 起工作区在壳里是**第五个平级视图**（`DesktopView.Agents`），平板宿主没有
 * 菜单栏，入口是工具栏溢出菜单那一项（→ 与竖导/底导同一格）。所以这一对帧走
 * `initialView = DesktopView.Agents`，等价于点开溢出菜单。
 *
 * `initialPeerId` 预选那个已离线的 Agent（否则看板兜底会挑中在线的那个，图就名不副实）。
 * 浅色守布局、暗色守暗色 token（`RelayDarkAccents` 的 `agentAccent`/`agentAccentContainer`
 * 在暗面上的对比度、离线状态卡会不会发糊）；两张用同一个 fixture，除颜色外的任何差异都是布局漂移。
 *
 * 手机上的 Agent 页仍由 `ScreenshotPreviewsExtra.kt` 的 `agents-*` 帧守着 ——
 * 那边 `CompactScaffold` 是真的。
 */
@PreviewTest
@Preview(name = "tablet-agents-land", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletAgentsLandscapePreview() {
    Tablet(
        1280,
        800,
        state = agentWithOfflineSelectedState,
        initialPeerId = offlineAgent.nodeId,
        agentsView = true,
    )
}

@PreviewTest
@Preview(name = "tablet-agents-land-dark", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletAgentsLandscapeDarkPreview() {
    Tablet(
        1280,
        800,
        darkTheme = true,
        state = agentWithOfflineSelectedState,
        initialPeerId = offlineAgent.nodeId,
        agentsView = true,
    )
}

/**
 * 平板宿主、**差额带**里的那一档：800×580。
 *
 * 这是 A1（断点归一）唯一改变过形状的窗口。两栏的高度下限原本是独立写死的 `600`，
 * 而桌面外壳要的是 `420 + 46 = 466`（`MIN_CHAT_HEIGHT_PX + TABLET_CHROME_PX`）——
 * A 比 B 严 134px。于是 `800×580` 落在差额带里：旧判据 `580 < 600` 不成立两栏，
 * 又因为 `width > height` 落 `CompactLandscape`，**一个 800dp 宽的平板分屏窗口
 * 画着一条手机底导**（审计 §2.3 反例一，样本数 45024）。
 *
 * A1 把下限并到 466 之后，这一档按当前判据落 `MediumTwoPane`，走的就是本文件的
 * [DesktopShell] + Tablet chrome。所以这一帧录的是**改后**的样子 ——
 * 审计 §12.3 原本要求「动手前先留一张改动前的图」，那条窗口在本轮开工时已经错过
 * （A1 先行落地），这里如实记下，不假装它是改动前的记录。
 *
 * 它现在的价值是**守住差额带**：谁再把高度下限调回 600 一类的数字，
 * 或者改动 `resolveAppLayoutMode` 的分支顺序，这一帧会立刻从平板壳变回手机壳而变红。
 *
 * 高度取 580 而不是 620：它比 [TabletConversationsTwoPaneMinPreview] 的 620 更靠近
 * 下限 466，是「两栏还成立、但已经很矮」的那一格 —— 工具栏会不会把内容挤掉，
 * 只有这一档看得见。
 */
@PreviewTest
@Preview(name = "tablet-host-800x580", widthDp = 800, heightDp = 580, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletHost800x580Preview() {
    Tablet(800, 580)
}

/**
 * 平板上的设置覆盖层 —— **宽屏收列（工作包 V1）唯一看得见的那一档**。
 *
 * ## 为什么非得是这一帧
 * V1 把设置内容收进 `ContentWidths.Settings`(640) 一列居中。`centerCapped` 的语义是
 * 「容器窄于上限时自动退化成容器宽」（`AdaptiveContent.kt:110-118`），所以：
 * - 手机设置页（393 宽）→ 640 夹不住，**收列是空操作**，`settings-phone` 那张图
 *   在改动前后逐像素相同，它证明不了 V1 做了任何事；
 * - 桌面设置窗（`SETTINGS_WINDOW_WIDTH_PX = 720` 减去 220 侧栏 ≈ 500）→ 同样夹不住。
 *
 * 生产里唯一宽过 672（640 + 2×16）的设置承载就是**平板工具栏「设置」打开的整屏覆盖层**
 * （`App.kt:481-488`：`Surface(fillMaxSize)` 里放 `settingsContent(fillMaxSize)`，
 * 而 `tabletSettingsOpen` 由 `onOpenSettings` 触发，`App.kt:447`）——
 * 它在 1100/1280 宽下真的把内容收到 640、两侧留出空白。
 * **在这一帧之前，那条路径一张图都没有**：改动落地了，却没有任何参考图能红。
 *
 * ## 这一帧画的就是那条路径
 * 覆盖层除了一层 `Surface(fillMaxSize)` 之外没有别的东西 —— 平板 `DesktopShell` 的
 * 菜单栏/标题栏/状态栏**不参与**（覆盖层盖住它们），所以这里不需要 [Tablet] helper，
 * 直接摆 `SettingsScreen` 与生产同形即可。也正因为盖住了外壳，这一帧里**不该**出现
 * 工具栏或左侧导航栏；出现了就说明覆盖层的层级搞错了。
 *
 * 标题栏那条 640 与内容列那条 640 必须左右对齐 —— 这是 V1 里最容易改错的一处：
 * 原型 `.tbinner` 写 608 是因为它自身没有横向 padding，而这里 `LargeTitleBar`
 * **自带** `padding(horizontal = 16.dp)`，所以两边都传 640 才对齐（传 608 会窄 32dp）。
 * 这一帧是那条判断的视觉证据：顶栏文字左边缘与卡片左边缘应当落在同一条竖线上。
 */
@Composable
private fun TabletSettingsOverlay(darkTheme: Boolean = false) {
    AppTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SettingsScreen(
                state = desktopPreviewState(),
                dataDirectory = "/data/user/0/app.relay/files/relay",
                cacheDirectory = "/data/user/0/app.relay/cache/relay",
                onSaveProfile = { _, _, _, _, _ -> },
                onSavePorts = { _, _ -> },
                onRestart = {},
                onDismissBanner = {},
                onClearNote = {},
                // 武装这一支：可空出口不传则依赖它的界面根本不渲染（截图证据里没有它）。
                onRemovePickedAvatar = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@PreviewTest
@Preview(name = "tablet-settings-overlay", widthDp = 1100, heightDp = 720, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletSettingsOverlayPreview() {
    TabletSettingsOverlay()
}

/**
 * 同上，暗色 + 更宽的一档（1280×800，与 `tablet-landscape-dark` 同尺寸以便逐像素对照）。
 *
 * 暗色下要额外确认的是 `centerCapped` 两侧留白露出来的是 `background` 而不是
 * `surface` —— 设置覆盖层自己铺了一层 `Surface(color = background)`，而卡片用的是
 * `surfaceVariant`；浅色下这两者差别很小、看不出来，暗色下差一大截。
 * 留白颜色错了，这一帧会先红。
 */
@PreviewTest
@Preview(name = "tablet-settings-overlay-dark", widthDp = 1280, heightDp = 800, showBackground = true, locale = "zh-rCN")
@Composable
private fun TabletSettingsOverlayDarkPreview() {
    TabletSettingsOverlay(darkTheme = true)
}
