// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package app.relay.feature.scan

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.relay.app.RelayUiState
import app.relay.generated.resources.Res
import app.relay.generated.resources.common_close
import app.relay.generated.resources.nodes_scan_frame_hint
import app.relay.generated.resources.nodes_scan_title
import app.relay.generated.resources.scan_import_image
import app.relay.generated.resources.scan_manual_hint
import app.relay.generated.resources.scan_no_preview
import app.relay.generated.resources.scan_retry
import app.relay.generated.resources.scan_starting
import app.relay.generated.resources.ic_close
import app.relay.ui.components.AppBanner
import app.relay.ui.theme.LocalAppSpacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * 常驻扫码页：全屏取景框 + 一条真的会去扫的协程。
 *
 * ## 为什么是"一整页"而不是"一个弹层里的取景框"
 *
 * 上一版把扫码做成「弹层里画一个取景框，点了按钮再调 `scan()`」。那条路是死的：
 * Android 的检测跑在预览那条 `ImageAnalysis` 的分析器里、iOS 的会话就是预览层的数据源，
 * 而 `scan()` 要等预览把结果回调进来 —— 弹层一关、预览一销毁，等待就再也不会结束。
 * 所以取景框必须是**页面级**的常驻物，生命周期正好等于那次 `scan()`。
 *
 * ## 相机预览是参数传进来的
 *
 * [preview] 为 `null` = 这个平台画不出预览（桌面没有相机），页面就画一句诚实的说明，
 * 而不是一块黑框。截图预览里传的是一块带标注的占位 —— **不伪造相机画面**。
 *
 * ## 出口
 *
 * 扫到了由 [onPaired] 关页（控制器已经把人加进节点表）；扫不到就停在这一页看横幅，
 * 并给两条出路：「再试一次」和「回节点页填地址」。扫码不是唯一的路，
 * 一台没有摄像头、或者系统不给权限的设备，照样要能把对方加进来。
 */
@Composable
fun ScanScreen(
    state: RelayUiState,
    preview: (@Composable (Modifier) -> Unit)?,
    scan: suspend () -> Boolean,
    onPaired: () -> Unit,
    onCancel: () -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 「从图片导入二维码」：选一张图，从里面读码。
     *
     * `null` = 这个平台读不了图里的码，这一页就不画那个按钮 —— 与 [preview] 同一道闸
     * （手机端两者都有；桌面两者都没有），也是同一条规矩：按下去没反应的按钮比没有更糟。
     *
     * 它**不需要相机权限**，所以在相机被拒、取景框起不来的设备上，这是真的出路而不是安慰。
     */
    importFromImage: (suspend () -> Boolean)? = null,
    /**
     * 这一页是否**从「上一次尝试已经失败」的样子**开始画。
     *
     * 生产永远是 false：进这一页就是新的一次尝试，先画"正在启动相机…"。
     * 截图门禁需要 true —— 一次 `scan()` 结束后 `waiting` 才翻到 false，而截图抓的是
     * 组合完成后的第一帧，`LaunchedEffect` 还没跑完，于是失败帧会录成"正在启动相机…"
     * 加一颗永远不出现的重试按钮（**踩过**：`scan-failed` 第一版就是这样）。
     * 与 `AddNodeSheet` 的 `initialScanMode`、`SheetState` 同一个做法。
     */
    attemptAlreadyFailed: Boolean = false,
) {
    val sp = LocalAppSpacing.current
    // 每次「再试一次」都换一个 key：预览会连同 AndroidView / AVCaptureSession 一起重建，
    // 相机重新绑一次。不重建的话失败过的那条绑定不会自己好起来 ——
    // 按钮就成了"按了没反应"。
    var attempt by remember { mutableIntStateOf(0) }
    var waiting by remember { mutableStateOf(!attemptAlreadyFailed) }

    // 导入是**另一条**并发的路：取景框那条 `scan()` 还挂在那儿等（相机照常在扫），
    // 用户完全可以一边扫一边去翻相册。所以它有自己的进行中标志，不复用 `waiting` ——
    // 复用的话，导入失败会把取景框的状态也改掉。
    val importScope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        waiting = true
        val paired = scan()
        waiting = false
        if (paired) onPaired()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = sp.xs, vertical = sp.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    painter = painterResource(Res.drawable.ic_close),
                    contentDescription = stringResource(Res.string.common_close),
                )
            }
            Text(
                text = stringResource(Res.string.nodes_scan_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = sp.xs),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // 取景区永远是深色底：浅色底上白色取景框看不清，而预览本身可能是亮的。
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                key(attempt) { preview(Modifier.fillMaxSize()) }
            } else {
                Text(
                    text = stringResource(Res.string.scan_no_preview),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(sp.xxl),
                )
            }
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(sp.lg),
            verticalArrangement = Arrangement.spacedBy(sp.sm),
        ) {
            // 真实原因：权限被拒、相机被占用、码不是我们的格式，各有各的横幅。
            state.banner?.let { AppBanner(it, onDismissBanner) }
            // 「把对方的节点码放进框里」**常显**：这句话在扫描期间才最有用，而上一版用
            // waiting 把它换成了"正在启动相机…"，于是真正在扫的时候用户反而看不到该做什么。
            Text(
                text = stringResource(Res.string.nodes_scan_frame_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (waiting && preview != null) {
                // 启动中只是一条状态，不替代上面那句"该做什么"。
                // 没有预览的平台上不画它：一边说"这台设备没有可用的相机预览"、
                // 一边说"正在启动相机…"，是自相矛盾。
                Text(
                    text = stringResource(Res.string.scan_starting),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 「再试一次」**扫描期间也在**：相机静默起不来时（CameraX 走 CameraState 而不抛
            // 异常，我们收不到任何失败信号）`scan()` 会一直等下去，上一版这时页面上既没有
            // 提示也没有按钮，只能关掉整页。重试会取消这次等待、重建预览、重绑一次相机。
            OutlinedButton(
                onClick = { attempt += 1 },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.scan_retry))
            }
            // 第二条路：不从相机读，从一张已有的图里读。它**不需要相机权限**，所以相机被拒、
            // 或者"码在另一块屏幕上但镜头就是对不准"的时候，这是真的出路。
            if (importFromImage != null) {
                OutlinedButton(
                    onClick = {
                        importScope.launch {
                            importing = true
                            val paired = importFromImage()
                            importing = false
                            if (paired) onPaired()
                        }
                    },
                    // 只在导入期间禁用自己：取景框那条 `scan()` 照常跑着，不该被这里挡住。
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.scan_import_image))
                }
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.scan_manual_hint))
            }
        }
    }
}
