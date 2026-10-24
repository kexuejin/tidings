// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.model.CabinetEntryUi
import com.kapp.tidings.domain.model.CabinetUiState
import com.kapp.tidings.domain.ManualPeerEntry
import com.kapp.tidings.domain.model.NetworkUi
import com.kapp.tidings.domain.model.NodePhase
import com.kapp.tidings.domain.model.PeerUi
import com.kapp.tidings.domain.RelaySettings
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.navBadges
import com.kapp.tidings.feature.cabinet.CabinetScreen
import com.kapp.tidings.feature.nodes.AddNodeSheet
import com.kapp.tidings.feature.nodes.MyQrSheet
import com.kapp.tidings.feature.nodes.NodesScreen
import com.kapp.tidings.navigation.AppNavItems
import com.kapp.tidings.platform.Transport
import com.kapp.tidings.ui.components.CompactScaffold
import com.kapp.tidings.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * W2 · 节点 / 文件柜两组里，**现有门禁没武装到的状态**。
 *
 * 为什么需要这个文件（2026-10-12 原型适配第二轮）：
 *
 * `m-cabinet-peer` 在 `design-screen-diff.py` 上报「0 处 · 逐维取值集合一致」，
 * 配对板却把 `m-cabinet-peer` 与「我的文件柜」摆在一起。两条同时成立的原因不是量具算错
 * —— 缺口在**结构分支**上：`PeerCabinetView` 的选中态（行底色 + 复选框 + 底栏
 * 「已选 N 项 · 大小 / 另存为… / 下载 N 项」）全仓**没有任何一帧画过**，
 * 所以那一支坏了也没人知道。原型 `m11c-cabinet-peer` 画的正是它（两行绿底 + 底栏两条按钮）。
 *
 * 这里放的是**补武装**的帧，不是新的屏幕：它们渲染的还是 `composeApp` 里真正被安装包调用的
 * 那个 Screen 组合函数（[CabinetScreen]），只是把状态喂到原型那一档。
 *
 * 与 `ScreenshotPreviews.kt` 的关系：那个文件是**冻结面**（本组不允许改），
 * `CabinetPeerPhonePreview` 住在里面。本文件是它的补集，不覆盖、不重命名任何既有帧；
 * 同一屏的两帧并排看时，**这一帧才是原型 `m11c-cabinet-peer` 的对应物**
 * （既有那帧画的是「一行都没选中」的那一档）。
 */

/**
 * 2025-01-01T00:00:00Z，按 UTC+8 算是 08:00 —— 与其余参考图同一个基准。
 *
 * 时间必须确定：同一帧两次渲染出不同的字，门禁会永远红灯（`lastSeenAt` 走 relativeTime，
 * 取 `nowMillis() - 偏移` ⇒ 文案恒定）。
 */


private val selfSettings = RelaySettings(
    nodeId = "44444444-4444-4444-8444-444444444444",
    nick = "我的手机",
    company = "产品",
    dept = "客户端",
    team = "移动端",
    avatar = 3,
    profileRev = 2,
)

private val runningNetwork = NetworkUi(
    phase = NodePhase.Running,
    transport = Transport.Wifi,
    localAddresses = listOf("192.168.1.57"),
    vpnActive = false,
    boundUdpPort = 17878,
    mdnsAdvertised = true,
)

/**
 * 原型 `m11c-cabinet-peer` 顶栏那个人：蓝头像「周」、`192.168.1.31:17878`、共 1,280 项。
 *
 * [host] 要跟原型 PEERS 里 `NID.ka` 那条对齐（`hostName: 'zhou-pc'`，
 * `prototype/relay.html:1666`）：这一位是**唯一**会渲染「主机」一行的夹具 ——
 * 节点详情页（`NodesDetailPhonePreview`）把它印在详细信息卡第三行。
 * 会话/文件柜那几屏只印 `ip:port`（`NodeListItem.host = ip.ifBlank { host }`），
 * 所以改它只动详情帧，不动别的参考图。
 */
private val zhou = PeerUi(
    nodeId = "77777777-7777-4777-8777-777777777777",
    nick = "老周",
    company = "产品",
    dept = "客户端",
    avatar = 1,
    host = "zhou-pc",
    ip = "192.168.1.31",
    // 平台也要跟原型对齐：PEERS 里 `NID.ka` 那条是 `plat:'linux'`（`prototype/relay.html:1665`）。
    // 节点详情帧把这一位印在「详细信息 → 平台」最后一行，写 `mac` 就与并排的原型差一格。
    // 这一位同样只进详情帧：会话/文件柜/合并列表都不印平台。
    platform = "linux",
    udpPort = 17878,
    tcpPort = 17879,
    caps = listOf("fd1", "tw1"),
    online = true,
    lastSeenAt = at(24),
)

/**
 * 同事文件柜的**选中态**：五行里选中前两行，其中第一行是**目录**。
 *
 * 数据照原型 `m11c-cabinet-peer` 逐行抄（需求文档 / contract-v2.pdf / interview-notes.md /
 * 图纸 / board.png），因为这些值直接决定并排看的四件事：行底色、勾选框、
 * 「大小」列（目录印「—」）、底栏那句「已选 N 项 · 大小」。
 *
 * 选中一个**目录**是这一帧的关键：`PeerCabinetView` 的行体对目录是「进目录」，
 * 所以目录只能靠勾选框自己的点击目标选上（见 `CabinetCheckbox` 的 `onCheckedChange`）。
 * 原型那一屏第一行就是选中的目录 —— 这一帧同时钉住那条修好的路径。
 */
private val cabinetPeerSelectedState = RelayUiState(
    settings = selfSettings,
    network = runningNetwork,
    peers = listOf(zhou),
    cabinet = CabinetUiState(
        activePeerId = zhou.nodeId,
        currentPath = "需求文档",
        entries = listOf(
            // 目录的 size 也进底栏合计：原型 4.4 MB = contract-v2.pdf 2.4 MB + **需求文档目录自身 2.0 MB**
            // （`PeerCabinetView.kt:374-376` 与原型 `cabRow` 用的是同一口径）。
            // 这里的字节数是按**印出来的字**反推的：`formatBytes` 保留一位小数、进制 1024
            // ⇒ 2.4 MB = 2.4×1048576 = 2_516_582、2.0 MB = 2_097_152、1.9 MB = 1_992_294，
            // 合计 4_613_734 ⇒ 底栏「已选 2 项 · 4.4 MB」，与原型逐字相同。
            CabinetEntryUi(name = "需求文档", isDir = true, size = 2_097_152L, mtime = at(0)),
            // 38 KB 保持整数（原型 KB 一律不带小数）。
            CabinetEntryUi(name = "contract-v2.pdf", isDir = false, size = 2_516_582L, mtime = at(0)),
            CabinetEntryUi(name = "interview-notes.md", isDir = false, size = 38_912L, mtime = at(0)),
            CabinetEntryUi(name = "图纸", isDir = true, size = 0L, mtime = at(0)),
            CabinetEntryUi(name = "board.png", isDir = false, size = 1_992_294L, mtime = at(0)),
        ),
        totalEntries = 1280,
        currentPerm = "write",
        selectedNames = setOf("需求文档", "contract-v2.pdf"),
    ),
)

/**
 * `m-cabinet-peer` 的规范帧：**同事文件柜 + 选中态 + 底部选择条**。
 *
 * 与原型 `prototype/screenshots/relay/m11c-cabinet-peer.png` 并排看。
 * 既有帧 `CabinetPeerPhonePreview`（冻结文件里）画的是同一屏的**无选中**那一档，
 * 底栏是「单击选中，双击进文件夹」提示 —— 两帧互补，缺任何一帧都有一支没被盯住。
 */
@PreviewTest
@Preview(
    name = "cabinet-peer-selected-phone",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun CabinetPeerSelectedPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("cabinet", badges = navBadges(cabinetPeerSelectedState)) { inner ->
            CabinetScreen(
                state = cabinetPeerSelectedState,
                onSetShareRoot = {},
                onSetShareMode = {},
                onRemoveShareGrant = {},
                onSelectPeer = {},
                onLoadPath = {},
                onToggleSelection = {},
                onDownloadEntries = { _ -> },
                // 必须传：生产 `App.kt:417 CabinetScreen(` 恒传
                // `onUploadToCabinet = controller::uploadToCabinet`（`App.kt:427`），而
                // `PeerCabinetView.kt:107` 的闸门是 `canUpload = currentPerm == "write" &&
                // onUploadToCabinet != null && activePeerId != null` ⇒ 不传的帧画的不是产品
                // （底栏那颗新增的「上传」按钮会凭空消失），截图门禁会对这个元素变瞎。
                // 那颗按钮是平台被迫的新增元素（原型靠拖放，手机没有拖放），已记在
                // `design/fidelity-deviations.yaml:264-265`；本帧是它唯一的视觉证据。
                onUploadToCabinet = {},
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * 套一层真正的 [CompactScaffold]。
 *
 * 每个预览文件各自持有一份：`ScreenshotPreviews.kt` 里那个是 `private`
 * （同一模块里各域文件本来就互不可见，这是既有约定）。
 */
@Composable
private fun Screen(
    selectedScreenId: String,
    badges: Map<String, Int> = emptyMap(),
    landscape: Boolean = false,
    content: @Composable (Modifier) -> Unit,
) {
    CompactScaffold(
        selectedScreenId = selectedScreenId,
        navItems = AppNavItems,
        onScreenSelected = {},
        badges = badges,
        landscape = landscape,
    ) { inner -> content(inner) }
}

// ---------------------------------------------------------------------------
// m-nodes / m-nodemenu / m-myqr
//
// 数据照原型 `m08-nodes` 逐行抄：6 行 = 4 在线（老周 · Huang · Lin · teahouse-mobile
// 带 Agent 徽标）+ 1 离线手动（ci-runner）+ 1 从没回应过的地址（老周的旧笔记本）。
// 副标题因此算得出原型那句「6 个已知 · 4 在线 · 2 离线」—— 帧与原型差的只剩画法。
// ---------------------------------------------------------------------------

private val huang = PeerUi(
    nodeId = "88888888-8888-4888-8888-888888888888",
    nick = "Huang",
    company = "产品",
    dept = "设计",
    avatar = 2,
    host = "Huang-PC",
    ip = "192.168.1.42",
    platform = "win",
    udpPort = 17878,
    tcpPort = 17879,
    caps = listOf("tw1"),
    online = true,
    lastSeenAt = at(24),
)

private val lin = PeerUi(
    nodeId = "99999999-9999-4999-8999-999999999991",
    nick = "Lin",
    company = "产品",
    dept = "设计",
    avatar = 4,
    host = "Lin-MBP",
    ip = "192.168.1.18",
    platform = "mac",
    udpPort = 17878,
    tcpPort = 17879,
    caps = listOf("tw1", "fd1"),
    online = true,
    lastSeenAt = at(24),
)

private val teahouseMobileAgent = PeerUi(
    nodeId = "22222222-2222-4222-8222-222222222222",
    nick = "teahouse-mobile",
    host = "MacBook-Pro",
    ip = "192.168.1.5",
    platform = "linux",
    udpPort = 17879,
    tcpPort = 17879,
    caps = listOf("ag1", "tw1"),
    online = true,
    lastSeenAt = at(24),
)

/** 离线的那条：**手动添加**，12 分钟前还见过 —— `at(13)` 在 `PREVIEW_NOW` 下正是 12 分钟。 */
private val ciRunner = PeerUi(
    nodeId = "66666666-6666-4666-8666-666666666666",
    nick = "ci-runner",
    host = "ci-runner",
    ip = "192.168.1.77",
    platform = "linux",
    udpPort = 17878,
    tcpPort = 17878,
    caps = listOf("tw1"),
    online = false,
    lastSeenAt = at(13),
)

/**
 * 两条手动地址：一条已经握上手（`ci-runner`，所以它在 `peers` 里、行里标「手动添加」），
 * 一条从没回应过（`192.168.1.90`，标「没有回应」）。
 *
 * 第二条给了名字 —— 名字这一支的**显示侧**已经实现（`ManualPeerEntry.name`），
 * 输入侧还没接线（见报告的账目）。原型那一行写的正是「老周的旧笔记本」，
 * 不给名字就只能印 `192.168.1.90:17878`，那会让这一帧多出一条假差异。
 */
private val nodesState = RelayUiState(
    settings = selfSettings.copy(
        manualPeers = listOf(
            ManualPeerEntry(host = "192.168.1.77", port = 17878, name = "ci-runner"),
            ManualPeerEntry(host = "192.168.1.90", port = 17878, name = "老周的旧笔记本"),
        ),
    ),
    network = runningNetwork,
    peers = listOf(teahouseMobileAgent, zhou, lin, huang, ciRunner),
)

/**
 * `m-nodes` 的规范帧：**一条合并列表** —— 在线 / 离线 / 没有回应同排，行尾一颗 ⋯。
 *
 * 与原型 `prototype/screenshots/relay/m08-nodes.png` 并排看。这一帧钉住的是**结构**：
 * 上一版把节点拆成「局域网发现」+「已保存的节点」两个分组、二维码常驻正文，
 * 于是"我有哪些节点"这个主任务被挤到第二屏；原型 Gen2 把列表并成一条、
 * 二维码搬去设置·连接，只在「添加节点」里留三条入口（本帧底部可见）。
 */
@PreviewTest
@Preview(
    name = "nodes-merged-phone",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun NodesMergedPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("nodes", badges = navBadges(nodesState)) { inner ->
            NodesScreen(
                state = nodesState,
                onAddPeer = { _, _ -> },
                onRemovePeer = {},
                onCleanupUnreachable = {},
                onRetryStart = {},
                onDismissBanner = {},
                onOpenChat = {},
                // 生产手机壳（`App.kt`）两条都传：`onOpenPeerDetail` 决定「行可点」，
                // 不传时 `NodeRow` 连 `clickable` 都不挂（见形参 KDoc「不是给一个点了
                // 没反应的入口」）。帧要拍生产的样子，所以这里必须传。
                onOpenPeerDetail = {},
                onSetPeerRemark = { _, _ -> },
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * `m-myqr` 的规范帧：**我的二维码弹层**，背后是变暗的节点页。
 *
 * 与原型 `prototype/screenshots/relay/m08b-myqr.png` 并排看。这一帧的存在本身就是一个结论：
 * 原型的二维码**不是**节点页正文里的一张常驻卡（那正是上一版代码的样子），
 * 而是「添加节点 → 出示我的二维码」后面的一层弹层；常驻位置搬到设置·连接。
 *
 * 弹层在这里直接组合（而不是靠 `NodesScreen` 内部的 `myQrOpen`）：一帧抓拍录不到
 * "还没展开"的 ModalBottomSheet（默认 Hidden、靠动画展开），必须显式给 Expanded ——
 * 和 `AddNodeSheet` 那几个帧同一个理由。
 */
@OptIn(ExperimentalMaterial3Api::class)
@PreviewTest
@Preview(
    name = "nodes-my-qr-phone",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun NodesMyQrSheetPhonePreview() {
    val density = LocalDensity.current
    val sheetState = remember(density) {
        SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { with(density) { 56.dp.toPx() } },
            velocityThreshold = { with(density) { 1250.dp.toPx() } },
            initialValue = SheetValue.Expanded,
        )
    }
    AppTheme(darkTheme = false) {
        Screen("nodes", badges = navBadges(nodesState)) { inner ->
            Box(Modifier.fillMaxSize()) {
                NodesScreen(
                    state = nodesState,
                    onAddPeer = { _, _ -> },
                    onRemovePeer = {},
                    onCleanupUnreachable = {},
                    onRetryStart = {},
                    onDismissBanner = {},
                    onOpenChat = {},
                    modifier = inner,
                    now = PREVIEW_NOW,
                )
                MyQrSheet(
                    host = "192.168.1.57",
                    port = 17878,
                    onDismiss = {},
                    sheetState = sheetState,
                )
            }
        }
    }
}


/**
 * `m-addnode-fill` 的规范帧：**「填地址」段 + 两个框都填好**。
 *
 * 与原型 `prototype/screenshots/relay/m09b-addnode-fill.png` 并排看。为什么必须补这一帧：
 * 既有那三个弹层帧（`AddNodeSheetPhonePreview` / `AddNodeSheetScanPhonePreview` /
 * `FillPhonePreview`）录的都是**空框**，而原型 `m09b` 的框里已经写着
 * `192.168.1.31` / `17878` —— 「填好之后长什么样」（尤其「连接」从灰变绿）这一档
 * 全仓没有帧盯着。夹具参数是 `AddNodeSheet` 的 `initialHost` / `initialPort`
 * （只给门禁喂状态，生产调用一律不传 = 空框）。
 *
 * 提示条：原型这一段**没有**「已识别」卡（那张卡在 `m09` 扫码段），代码里它却挂在
 * `host` 非空上 ⇒ 这一帧会把它画出来。这是**状态归宿不同**、已报 Lead 裁，
 * 不是这一帧画错了（见报告 §差集残留）。
 *
 * **为什么这一帧传了 `onConnectNamed`**（Lead 裁决 (a)）：帧要如实画**生产**的样子。
 * 生产手机壳（`App.kt`）传的是 `controller::addNamedManualPeer`，于是这一段会比原型
 * `m09b-addnode-fill` **多一格「名字（可选）」**。原型真值：`screenAddNode(seg)`
 * （`prototype/relay.html:3357`）的**扫码段**只有「已识别 / 地址 / 握手」三个 kv、
 * **填地址段**只有「对方地址 / 端口」两个 `.field` —— **两段都没有名字输入框**。
 * 但原型**演示了结果、没给入口**：`ManualPeerEntry.name` 的 KDoc 明说这是"本机给这条
 * 地址起的名字"（`m08-nodes` 里的 `ci-runner` / `老周的旧笔记本` 就是这种名字）。
 * 所以这一格是**代码侧新增**，已登记进 `design/fidelity-deviations.yaml`。
 * 它排在第**三**个框 ⇒ 393×852 视口下「连接」会被推到折线以下（弹层是
 * `verticalScroll`，生产里滚得到），这一帧也把这件事拍下来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@PreviewTest
@Preview(
    name = "nodes-add-sheet-filled",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun AddNodeSheetFilledPhonePreview() {
    val density = LocalDensity.current
    val sheetState = remember(density) {
        SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { with(density) { 56.dp.toPx() } },
            velocityThreshold = { with(density) { 1250.dp.toPx() } },
            initialValue = SheetValue.Expanded,
        )
    }
    AppTheme(darkTheme = false) {
        Screen("nodes", badges = navBadges(nodesState)) { inner ->
            Box(Modifier.fillMaxSize()) {
                NodesScreen(
                    state = nodesState,
                    onAddPeer = { _, _ -> },
                    onRemovePeer = {},
                    onCleanupUnreachable = {},
                    onRetryStart = {},
                    onDismissBanner = {},
                    onOpenChat = {},
                    modifier = inner,
                    now = PREVIEW_NOW,
                )
                AddNodeSheet(
                    state = nodesState,
                    onConnect = { _, _ -> },
                    onDismiss = {},
                    sheetState = sheetState,
                    initialScanMode = false,
                    initialHost = "192.168.1.31",
                    initialPort = "17878",
                    // 传了才画「名字（可选）」—— 生产壳就是这么传的（`App.kt`）。
                    onConnectNamed = { _, _, _ -> },
                )
            }
        }
    }
}

/**
 * 原型 `m10-nodes-empty` 真正画的那个状态：**广播已停 + 检测到 VPN + 端口还没绑上**。
 *
 * 为什么另建一帧而不是改 `NodesEmptyPhonePreview`：那一帧是**冻结文件**里的，
 * 而且它刻意用 `NodePhase.Running` + `mdnsAdvertised = true` 录「同步正常但没发现到人」。
 * 原型这一屏是另一档 —— `broadcasting`（`NodesScreen.kt:265`）=
 * `phase == Running && discoverable == true`，所以这里给 `Starting` + 无地址，
 * 同步条才会落到停用档（`nodes_syncbar_down`「广播已停」+ 右端「重新监听」，
 * 出处 `prototype/relay.html:3399`），本机卡「状态」那行出 `nodes_state_starting`
 * （「正在监听端口…」，`:3408`），`vpnActive = true` 让 `disc_vpn_warning` 那条横幅出现
 * （`:3401-3402` 的 `banner warn`）。
 */
private val nodesEmptyOffState = RelayUiState(
    settings = selfSettings,
    network = NetworkUi(
        phase = NodePhase.Starting,
        transport = Transport.Wifi,
        localAddresses = emptyList(),
        vpnActive = true,
        boundUdpPort = 17878,
        // 注册失败（不是"还没启动"）：原型 `m10-nodes-empty` 的「发现方式」那一行写的是
        // 「没有在广播，别人的节点页里看不到你」，同步条也写着「广播已停」—— 那是
        // `discoverable == false` 这一档，而 `mdnsAdvertised = null`（未启动）会走到
        // `discoverable == null`，界面印「—」，与原型不符。首版我填的就是 null，
        // 打开图后逐行比对才发现这一处，改成 false。
        mdnsAdvertised = false,
    ),
)

/**
 * `m-nodes-empty` 的规范帧（第二档）：广播停用 + VPN 警告 + 启动中。
 *
 * 与原型 `prototype/screenshots/relay/m10-nodes-empty.png` 并排看。它钉住三件事：
 * ① 同步条**停用档**与右端「重新监听」（原型有、上一版代码完全没有）；
 * ② 空态卡是**无条件**画的 —— 上一版 `phase == Starting` 会走 `LoadingState`，
 * 那是原型没有的东西，已按裁决删掉；③ VPN 横幅的位置在「节点（0）」标题之上。
 */
@PreviewTest
@Preview(
    name = "nodes-empty-off",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun NodesEmptyOffPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("nodes", badges = navBadges(nodesEmptyOffState)) { inner ->
            NodesScreen(
                state = nodesEmptyOffState,
                onAddPeer = { _, _ -> },
                onRemovePeer = {},
                onCleanupUnreachable = {},
                onRetryStart = {},
                onDismissBanner = {},
                onOpenChat = {},
                onOpenPeerDetail = {},
                onSetPeerRemark = { _, _ -> },
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * `m-nodedetail` 的规范帧：**点进一个在线节点的详情**（老周）。
 *
 * 与原型 `prototype/screenshots/relay/m08a-nodedetail.png` 并排看。这一屏是 2026-10-12
 * 原型新加的一整屏（`nodeDetailBody`，`prototype/relay.html:3296-3331`），
 * 在此之前点节点是**直冲会话** —— 于是「备注」这件事在界面上根本没有落点。
 *
 * 钉住的四件事：① 顶栏返回键回列表（详情是**页签内部分支**，底栏照画，
 * 见 `AppNavState.detailPeerId` 的 KDoc）；② 详情页两张卡之间**没有间隙**
 * （逐像素实测 `m08a-nodedetail.png`：备注卡 y420..799、详细信息卡 y795..1239，
 * 中间只有阴影渐变，页面底色一次都没回来）；③ 备注卡只在有 `nodeId` 时出现；
 * ④ 底部「发消息」是整宽实心 primary。
 *
 * 备注框留空 = 照原型（`m08a` 里是 placeholder「备注这个节点…」，不是填好的值）。
 */
@PreviewTest
@Preview(
    name = "nodes-detail-phone",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun NodesDetailPhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("nodes", badges = navBadges(nodesState)) { inner ->
            NodesScreen(
                state = nodesState,
                onAddPeer = { _, _ -> },
                onRemovePeer = {},
                onCleanupUnreachable = {},
                onRetryStart = {},
                onDismissBanner = {},
                onOpenChat = {},
                onOpenPeerDetail = {},
                onSetPeerRemark = { _, _ -> },
                detailPeerId = zhou.nodeId,
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}

/**
 * `m-nodedetail` 的**第二档**：从没握过手的手动地址（`老周的旧笔记本`）。
 *
 * 原型只画了在线那一档，但代码里这一档有**两条不同的归宿**，都要钉住：
 * ① 备注卡整张不出现 —— 这条地址没有 `nodeId`，原型 `saveRemark(id)` 自己也有
 * 这道闸（`if (el && p.nodeId)`，`prototype/relay.html:3340`）；
 * ② 「发消息」禁用并在下面补一行居中提示（原型是 `p.never` 这一档，`:3322-3323`）。
 *
 * 定位用它自己的 `host:port`（手动行没有节点身份，`NodeListItem.key` 就是地址）。
 */
@PreviewTest
@Preview(
    name = "nodes-detail-unreachable-phone",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun NodesDetailUnreachablePhonePreview() {
    AppTheme(darkTheme = false) {
        Screen("nodes", badges = navBadges(nodesState)) { inner ->
            NodesScreen(
                state = nodesState,
                onAddPeer = { _, _ -> },
                onRemovePeer = {},
                onCleanupUnreachable = {},
                onRetryStart = {},
                onDismissBanner = {},
                onOpenChat = {},
                onOpenPeerDetail = {},
                onSetPeerRemark = { _, _ -> },
                detailPeerId = "192.168.1.90:17878",
                modifier = inner,
                now = PREVIEW_NOW,
            )
        }
    }
}
