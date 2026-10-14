// 宿主缺口帧（屏配对台账 2026-10-10：d-transferlog 的组件层补齐）。
//
// 为什么单独一个文件：这批帧来自「避线批次」——配对台账里 6 个「缺 Preview」的屏，
// 逐个核实后只剩这一屏是**能进截图门禁**的：
//   - m-myqr：假阴性。二维码卡挂在 NodesPhonePreview 的整帧滚动图里（目视参考图确认，
//     relay://192.168.1.57:17878 + 载荷原文都在），不需要新帧。
//   - d-capture / d-capture-tools：CaptureOverlayView 在 desktopApp 模块，
//     androidApp 的截图源集看不见它 → 宿主面清单（Step 7）真窗人工验收。
//   - d-tray / d-drop：托盘是 AWT、拖放遮罩在 DesktopHosting.kt:240 的 AWT 层
//     （「拖入时亮全屏遮罩 + 虚线框」），Layoutlib 走不到这些代码路径 → 同上。
//   - d-transferlog：DesktopTransferLogPane 是纯 Compose、参数是普通数据，
//     直接构造即可 —— 本文件的两条数据帧 + 一条空态帧。
//
// 这批帧**没有改任何生产代码**；台账里 d-transferlog 从「缺 Preview」转「强」由这三帧支撑。
// 参考图已录制（2026-10-10），命名范式见
// androidApp/src/screenshotTestDebug/reference/app/relay/app/screenshot/ScreenshotPreviewsHostGapKt/。
// 2026-10-10 修过一次夹具欠武装：三帧初版都没传 `onOpenFile`，导致行尾「打开」按钮
// 整支没被拍下来——详见下面 `DeskTransferLogFilledPreview` 的 KDoc。
package app.relay.app.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.relay.app.model.TransferRecordUi
import app.relay.ui.desktop.DesktopTransferLogPane
import app.relay.ui.desktop.PREVIEW_NOW
import app.relay.ui.theme.AppTheme
import com.android.tools.screenshot.PreviewTest

/** 传输记录的固定夹具：进/出 × 成功/失败 × 文件/文件夹 × 三档大小，时间全相对 PREVIEW_NOW。 */
private val transferLogRecords = listOf(
    TransferRecordUi(
        transferId = "t-01", name = "contract-v2.pdf", size = 2_516_582,
        isIncoming = true, at = PREVIEW_NOW - 3 * 60_000L, success = true,
        path = "/Users/me/Downloads/contract-v2.pdf",
    ),
    TransferRecordUi(
        transferId = "t-02", name = "季度报表-2026Q3-终稿-修订版.xlsx", size = 18_874_361,
        isIncoming = false, at = PREVIEW_NOW - 22 * 60_000L, success = true,
    ),
    TransferRecordUi(
        transferId = "t-03", name = "design-assets", size = 412_315_392,
        isIncoming = false, at = PREVIEW_NOW - 2 * 3_600_000L, success = false,
        isFolder = true,
    ),
    TransferRecordUi(
        transferId = "t-04", name = "IMG_2043.png", size = 341_772,
        isIncoming = true, at = PREVIEW_NOW - 26 * 3_600_000L, success = true,
        path = "/Users/me/Downloads/IMG_2043.png",
    ),
    TransferRecordUi(
        transferId = "t-05", name = "relay-desktop-0.9.dmg", size = 1_948_273_408,
        isIncoming = true, at = PREVIEW_NOW - 3 * 24 * 3_600_000L, success = false,
    ),
)

@Composable
private fun TransferLogBackdrop(darkTheme: Boolean, content: @Composable () -> Unit) {
    AppTheme(darkTheme = darkTheme) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxSize(),
        ) { content() }
    }
}

/**
 * 桌面设置窗·传输记录（d-transferlog）。
 *
 * 窗口口径：设置窗 720×560（Gen0 起沿用；shot 表 d14b 是 772×640 整窗，扣标题栏后
 * 内容区 ≈ 本帧）。长文件名（t-02）与文件夹条目（t-03）是这一屏的两个 stress 槽，
 * 和失败态（t-03/t-05）同帧点亮。
 *
 * **`onOpenFile` 必须传**（2026-10-10 修）：这三帧初版只传了 `records` / `onClear` / `now`，
 * 而 `onOpenFile` 的默认值是 `null`，于是 `DesktopTransferLogPane.kt:180` 那一支
 * `if (onOpenFile != null) { TextButton(...) { Text(file_open) } }` **整支不渲染**——
 * 行尾的「打开」按钮一次都没被拍下来过。而原型 `.tlist` 的行就是
 * 「图标 + 两行 + 一个打开按钮」，CSS 注释（`prototype/relay.html:1476`）还专门写明
 * 「没有 savedPath 时置灰」。夹具里 t-01/t-04 有 `path`、t-02/t-03/t-05 没有，
 * 正好能同时钉住「可点」与「置灰」两态——只要把这一支武装起来。
 *
 * 这是第八个量具洞（图里看不见那一支）的同一个形状：**夹具少传一个参数，
 * 验收图就不是验收对象**。判据：给一个屏拍图之前，先把它的参数表逐个问一遍
 * 「这个参数不传，会少渲染哪一支」。
 */
@PreviewTest
@Preview(name = "desk-transferlog-filled", widthDp = 720, heightDp = 560, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskTransferLogFilledPreview() {
    TransferLogBackdrop(darkTheme = false) {
        DesktopTransferLogPane(
            records = transferLogRecords,
            onClear = {},
            onOpenFile = {},
            now = PREVIEW_NOW,
        )
    }
}

/** 深色同帧：状态色（成功绿/失败红）在深底上的可读性只有这张能钉。 */
@PreviewTest
@Preview(name = "desk-transferlog-filled-dark", widthDp = 720, heightDp = 560, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskTransferLogFilledDarkPreview() {
    TransferLogBackdrop(darkTheme = true) {
        DesktopTransferLogPane(
            records = transferLogRecords,
            onClear = {},
            onOpenFile = {},
            now = PREVIEW_NOW,
        )
    }
}

/** 空态：「暂无传输记录」+ 清空按钮置灰（enabled=records.isNotEmpty() 的那条分支）。 */
@PreviewTest
@Preview(name = "desk-transferlog-empty", widthDp = 720, heightDp = 560, showBackground = true, locale = "zh-rCN")
@Composable
private fun DeskTransferLogEmptyPreview() {
    TransferLogBackdrop(darkTheme = false) {
        DesktopTransferLogPane(
            records = emptyList(),
            onClear = {},
            onOpenFile = {},
            now = PREVIEW_NOW,
        )
    }
}
