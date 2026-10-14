// SPDX-License-Identifier: GPL-3.0-only

// 第二轮「节点 / 文件柜组」精修帧（R2-W2，屏配对台账 2026-10-13 task-16）。
//
// 这一批与既有帧的分工：
//
// 1. **第一轮那批继续是规范帧**（`ScreenshotPreviews.kt` / `ScreenshotPreviewsNodesCabinet.kt`，
//    都在禁改清单里）。它们回答「这块屏默认长什么样」，也是量具
//    （`tools/design-preview-pairing.py`）判定落点的那一张。本轮**只重录、不改**。
// 2. **本文件只放"既有帧参数不同、画面确实不一样"的补武装帧** —— 也就是某一支
//    渲染路径此前**没有任何一帧画过**的情况。参数相同的重复帧会被
//    `render-preview.py --dup` 判重复（那条护栏是上一轮用一张真重复的 PNG 换来的）。
//
// 为什么必须补：`design-preview-pairing.py:296 preview_armed()` 会扫出
// 「某个 @Composable 的可空闸门形参，被某帧**没传**」—— 没传 = 那一支在参考图里
// 根本不存在，坏了也没人知道。本轮 m-addnode 的「从图片导入二维码」
// （`onImportQrFromImage`）正是这种：规范帧 `AddNodeSheetScanPhonePreview` 传了
// `onScanQr = {}` 却没传它，于是那颗按钮在参考图里永远是缺席的。
package app.relay.app.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.relay.app.model.NetworkUi
import app.relay.app.model.NodePhase
import app.relay.app.RelaySettings
import app.relay.app.RelayUiState
import app.relay.feature.nodes.AddNodeSheet
import app.relay.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/**
 * 2025-01-01T00:00:00Z，按 UTC+8 算是 08:00 —— 与其余参考图同一个基准。
 *
 * 时间必须确定：同一帧两次渲染出不同的字，门禁会永远红灯。
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

private val emptyState = RelayUiState(
    settings = selfSettings,
    // 空态用 Running：`NodePhase.Starting` 会让节点页画「正在启动节点…」的加载行，
    // 而这一帧要证明的是扫码段本身，与节点启动状态无关。
    network = NetworkUi(phase = NodePhase.Running),
)

/**
 * `m-addnode` 扫码段的**补武装帧**：三颗入口齐全的那一档。
 *
 * 与原型 `prototype/screenshots/relay/m09-addnode.png` 并排看。原型那一屏从上到下是
 * 「分段（扫码配对选中）→ 虚线取景框 → 绿实心『打开相机扫码』→ 通栏描边
 * 『从图片导入二维码』→ `.banner.info` 相机权限提示」（`prototype/relay.html:3364-3375`）。
 *
 * **为什么不能只靠规范帧**：既有那帧 `AddNodeSheetScanPhonePreview` 只传了
 * `onScanQr = {}`，`onImportQrFromImage` 走默认 `null` ⇒ 第二颗按钮不画。
 * 那是"桌面/平板"的形态（两个回调都是 null），不是手机形态 —— 拿它当手机屏的基线，
 * 这颗按钮将来被改坏、甚至被删掉，参考图都不会变。这一帧把手机那一档钉住。
 *
 * 夹具状态与规范帧一致（`emptyState`），唯一差别就是多传了导入回调，
 * 这样两张图并排时差异只剩那颗按钮本身。
 */
@OptIn(ExperimentalMaterial3Api::class)
@PreviewTest
@Preview(
    name = "r2-add-node-sheet-scan-import",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
    locale = "zh-rCN",
)
@Composable
private fun R2AddNodeSheetScanImportPreview() {
    val density = LocalDensity.current
    AppTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            AddNodeSheet(
                state = emptyState,
                onConnect = { _, _ -> },
                onDismiss = {},
                onScanQr = {},
                // 这一帧存在的全部理由：把导入回调武装上。
                onImportQrFromImage = { false },
                initialScanMode = true,
                sheetState = remember(density) {
                    SheetState(
                        skipPartiallyExpanded = true,
                        positionalThreshold = { with(density) { 56.dp.toPx() } },
                        velocityThreshold = { with(density) { 1250.dp.toPx() } },
                        initialValue = SheetValue.Expanded,
                    )
                },
            )
        }
    }
}
