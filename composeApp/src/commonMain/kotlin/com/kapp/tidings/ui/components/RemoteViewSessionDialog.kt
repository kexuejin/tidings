// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.DialogProperties
import com.kapp.tidings.core.nowMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kapp.tidings.core.protocol.ScreenEndReasons
import com.kapp.tidings.core.protocol.ScreenMode
import com.kapp.tidings.core.protocol.ScreenPhase
import com.kapp.tidings.core.protocol.ScreenRole
import com.kapp.tidings.core.protocol.ScreenSessionState
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.common_close
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.generated.resources.ic_screen_share
import com.kapp.tidings.generated.resources.screen_remote_view_actual_size
import com.kapp.tidings.generated.resources.screen_remote_view_connecting
import com.kapp.tidings.generated.resources.screen_remote_view_consent_accept
import com.kapp.tidings.generated.resources.screen_remote_view_consent_desc
import com.kapp.tidings.generated.resources.screen_remote_view_consent_privacy
import com.kapp.tidings.generated.resources.screen_remote_view_consent_reject
import com.kapp.tidings.generated.resources.screen_remote_view_consent_title
import com.kapp.tidings.generated.resources.screen_remote_view_error_capture_ended
import com.kapp.tidings.generated.resources.screen_remote_view_error_protocol
import com.kapp.tidings.generated.resources.screen_remote_view_error_title
import com.kapp.tidings.generated.resources.screen_remote_view_error_unknown
import com.kapp.tidings.generated.resources.screen_remote_view_fit
import com.kapp.tidings.generated.resources.screen_remote_view_fps
import com.kapp.tidings.generated.resources.screen_remote_view_mode_auto
import com.kapp.tidings.generated.resources.screen_remote_view_mode_economy
import com.kapp.tidings.generated.resources.screen_remote_view_mode_smooth
import com.kapp.tidings.generated.resources.screen_remote_view_mode_standard
import com.kapp.tidings.generated.resources.screen_remote_view_request_banner
import com.kapp.tidings.generated.resources.screen_remote_view_request_cancel
import com.kapp.tidings.generated.resources.screen_remote_view_request_send
import com.kapp.tidings.generated.resources.screen_remote_view_request_title
import com.kapp.tidings.generated.resources.screen_remote_view_sharing_hint
import com.kapp.tidings.generated.resources.screen_remote_view_stop
import com.kapp.tidings.generated.resources.screen_remote_view_stale
import com.kapp.tidings.generated.resources.screen_remote_view_stop_viewing
import com.kapp.tidings.generated.resources.screen_remote_view_title
import com.kapp.tidings.generated.resources.screen_remote_view_viewer_footer
import com.kapp.tidings.generated.resources.screen_remote_view_waiting_consent
import com.kapp.tidings.generated.resources.screen_remote_view_waiting_frames
import com.kapp.tidings.platform.decodeImageBytes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.RelayElevations
import com.kapp.tidings.ui.theme.RelayShapes
import com.kapp.tidings.domain.model.ScreenSessionUiState

/**
 * 屏幕协助全屏/对话框组件：
 * - Viewer: 全屏实时画面渲染、帧率模式切换、主动结束；
 * - Sharer: 邀请同意/拒绝弹窗、置顶共享中状态条与停止共享。
 */

/**
 * Viewer 进入 Active 相之后，画面区这一轮该画什么（纯函数，可测）。
 *
 * 旧实现 Active 但还没解出第一帧时**什么都不画** —— 整屏纯黑（issue「已连接无帧
 * 纯黑屏」），用户分不清「正在连」与「已经断了」。新实现：没有帧就画占位
 * （[RemoteViewBody.WaitingFrames]，「等待画面…」），有帧才画 [RemoteViewBody.Frame]。
 * 非 Active 相（等同意/连接中）不归它管 —— 那两档由既有相位文案处理。
 */
internal enum class RemoteViewBody { WaitingFrames, Frame }

/** 超过这个间隔没有新帧就算「画面暂未更新」（3 秒 ≈ 最慢档 3fps 连续丢三帧）。 */
internal const val STALE_FRAME_AFTER_MS: Long = 3_000L

internal fun activeFrameBody(hasFrame: Boolean): RemoteViewBody =
    if (hasFrame) RemoteViewBody.Frame else RemoteViewBody.WaitingFrames

/**
 * 在后台 dispatcher 上解一帧屏幕画面（issue 9①：帧解码挪出组合期）。
 *
 * 旧实现 `remember(frameBytes) { decodeImageBytes(it) }` 在**组合期的 UI 线程**上同步
 * 解码整张位图 —— 3-10fps 的流每帧一次组合期解码，直接吃掉 UI 帧率。这里把解码放到
 * [dispatcher]（生产是 [Dispatchers.Default]），坏帧折成 null（占位会接管）而不是
 * 抛异常炸掉 produceState 的协程。
 *
 * [decoder] 参数只是测试接缝（默认真实现），让「解码真的不在调用方线程上」
 * 这条不变量可以被钉住。
 */
internal suspend fun decodeFrameBackground(
    bytes: ByteArray?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    decoder: suspend (ByteArray) -> ImageBitmap? = { decodeImageBytes(it) },
): ImageBitmap? = bytes?.let {
    withContext(dispatcher) { runCatching { decoder(it) }.getOrNull() }
}

@Composable
fun RemoteViewSessionDialog(
    state: ScreenSessionUiState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onStop: () -> Unit,
    onSetMode: (ScreenMode) -> Unit,
) {
    val session = state.session ?: return
    // 正常终态直接退场（会话结束了就没什么可画的）；失败终态必须留下：用户得看见
    // 「为什么画面没了」。以前失败也写成 Ended，这一行就把弹窗抹掉了，于是
    // 「对方拒绝了」「连接断了」与「我自己按了结束」在屏幕上完全一样 —— 什么都看不到。
    if (session.isTerminal && !session.isFailed) return

    // 0. 失败收场（ScreenPhase.Error）：拒绝、占线、对方不支持、抓屏失败、超时、断链、协议出错。
    //    这是 ScreenPhase.Error 的渲染消费点；原相位机里没有这一档，这屏此前不存在。
    if (session.isFailed) {
        RemoteViewErrorDialog(session = session, onDismiss = onStop)
        return
    }

    // 1. Sharer 收到邀请等待同意弹窗
    if (session.role == ScreenRole.Sharer && session.phase == ScreenPhase.AwaitingConsent) {
        Dialog(
            onDismissRequest = onReject,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Surface(
                shape = RelayShapes.large,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = RelayElevations.Floating,
                modifier = Modifier.padding(LocalAppSpacing.current.lg),
            ) {
                Column(
                    modifier = Modifier.padding(LocalAppSpacing.current.xl),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg),
                ) {
                    // 标题逐字照原型 d31-sharescreen：`{0} 想查看你的屏幕`（带发起方昵称）。
                    // 以前是无参的「收到屏幕协助邀请」——同一件事，但原型说的是"谁想看你"。
                    Text(
                        text = stringResource(Res.string.screen_remote_view_consent_title, session.peerName),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.screen_remote_view_consent_desc, session.peerName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 同意前必须说清的第二件事（issue 9③）：共享的是「整块屏幕」，
                    // 弹出来的通知、消息预览都会被看到 —— 只说「查看你的屏幕」
                    // 不足以让用户在点「开始共享」前想到这一层。
                    Text(
                        text = stringResource(Res.string.screen_remote_view_consent_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        AppOutlinedButton(
                            text = stringResource(Res.string.screen_remote_view_consent_reject),
                            onClick = onReject,
                        )
                        Spacer(Modifier.width(LocalAppSpacing.current.md))
                        AppButton(
                            text = stringResource(Res.string.screen_remote_view_consent_accept),
                            onClick = onAccept,
                        )
                    }
                }
            }
        }
        return
    }

    // 2. Sharer 共享中置顶条 —— **非模态**：共享期间用户还要继续用 App（回聊天给对方指路、
    //    切页面展示内容）。这里是 Dialog 的时候，一块模态窗把整个界面挡死，"共享屏幕"实际
    //    变成了"只能盯着一块共享条"。Popup(focusable = false) 不抢焦点、不拦输入，位置仍在顶部。
    if (session.role == ScreenRole.Sharer && (session.phase == ScreenPhase.Preparing || session.phase == ScreenPhase.Connecting || session.phase == ScreenPhase.Active)) {
        Popup(
            alignment = Alignment.TopCenter,
            properties = PopupProperties(focusable = false),
        ) {
            Surface(
                shape = RelayShapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(LocalAppSpacing.current.lg),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(LocalAppSpacing.current.lg),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(Res.string.screen_remote_view_sharing_hint, session.peerName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    AppButton(
                        text = stringResource(Res.string.screen_remote_view_stop),
                        onClick = onStop,
                        tone = AppButtonTone.Danger,
                    )
                }
            }
        }
        return
    }

    // 3. Viewer 全屏屏幕查看器
    if (session.role == ScreenRole.Viewer) {
        // 「适应窗口 / 100%」：原型 d32-viewscreen 控制条第二行左边的分段控件。
        // 默认「适应窗口」= 原来的 ContentScale.Fit；「100%」= 1:1 不缩放（ContentScale.None）。
        // 只影响画面，不影响控制条。
        var zoomActualSize by remember { mutableStateOf(false) }
        Dialog(
            onDismissRequest = onStop,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                // 等待对方同意 / 正在建立连接。
                // 这两件事在原型上是**两句不同的话**（d30-reqscreen 讲"要等对方同意"，
                // d32-viewscreen 画面区讲"正在连接屏幕…"），以前共用一句
                // screen_remote_view_waiting_consent，连接中也在说"等对方同意"。
                if (session.phase != ScreenPhase.Active) {
                    val waitingText = when (session.phase) {
                        ScreenPhase.Preparing, ScreenPhase.Connecting ->
                            stringResource(Res.string.screen_remote_view_connecting)
                        else -> stringResource(Res.string.screen_remote_view_waiting_consent)
                    }
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg),
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = waitingText,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    // 帧解码挪出组合期（issue 9①）：produceState 在后台 dispatcher 解码，
                    // 解出的 ImageBitmap 缓存在 state 里 —— 下一帧解码期间上一帧继续显示
                    // （produceState 换 key 重启 producer 时 value 不清空），不会黑一下。
                    val frameBytes = state.latestFrameBytes
                    val bitmap by produceState<ImageBitmap?>(initialValue = null, frameBytes) {
                        value = decodeFrameBackground(frameBytes)
                    }
                    // 判定走纯函数 activeFrameBody（可测）：有帧画帧，没帧画占位。
                    val frame = if (activeFrameBody(hasFrame = bitmap != null) == RemoteViewBody.Frame) {
                        bitmap
                    } else {
                        null
                    }

                    // 画面新鲜度（issue「冻结帧与实时不可分」）：帧停了之后画面会一直停在最后
                    // 一帧上，与"对方没动"完全一样。原型把「画面暂未更新」明确列为与「正在连接」
                    // 不同的一态。这里只在观看窗本地记账（不新开领域状态）：最近一次拿到帧的时刻
                    // 与"现在"的差超过 [STALE_FRAME_AFTER_MS] 就盖一条提示。
                    var lastFrameAt by remember { mutableStateOf(nowMillis()) }
                    LaunchedEffect(frameBytes) {
                        if (frameBytes != null) lastFrameAt = nowMillis()
                    }
                    var staleSeconds by remember { mutableStateOf(0) }
                    LaunchedEffect(frame != null) {
                        while (true) {
                            delay(1_000)
                            staleSeconds = ((nowMillis() - lastFrameAt) / 1_000L).toInt()
                        }
                    }

                    // 「100%」档的平移（issue「1:1 看不到别处」）：ContentScale.None 之后画面
                    // 只显示中心一块，没有手势就永远停在那儿 —— 比「适应窗口」更没用。
                    var pan by remember { mutableStateOf(Offset.Zero) }

                    if (frame != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (zoomActualSize) {
                                        Modifier.pointerInput(Unit) {
                                            detectDragGestures { _, dragAmount -> pan += dragAmount }
                                        }
                                    } else {
                                        Modifier
                                    }
                                ),
                        ) {
                            Image(
                                bitmap = frame,
                                contentDescription = stringResource(Res.string.screen_remote_view_title, session.peerName),
                                contentScale = if (zoomActualSize) ContentScale.None else ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset { IntOffset(pan.x.toInt(), pan.y.toInt()) },
                            )
                            if (staleSeconds * 1_000L >= STALE_FRAME_AFTER_MS) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.72f),
                                    shape = RelayShapes.medium,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(LocalAppSpacing.current.lg),
                                ) {
                                    Text(
                                        text = stringResource(Res.string.screen_remote_view_stale, staleSeconds),
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(
                                            horizontal = LocalAppSpacing.current.lg,
                                            vertical = LocalAppSpacing.current.md,
                                        ),
                                    )
                                }
                            }
                        }
                    } else {
                        // 已连接但第一帧还没到/没解出来：画占位（issue 9②），
                        // 不能什么都不画 —— 整屏纯黑与「断了」无法区分。
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg),
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Text(
                                text = stringResource(Res.string.screen_remote_view_waiting_frames),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }

                // 顶部控制条：
                //   第一行 —— 标题 /「目标 N 帧/秒」/ 清晰度模式菜单 / 关闭
                //   第二行 —— 适应窗口·100% 分段 /「结束查看」（原型 d32-viewscreen 的 .rvseg + .rvstop）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.screen_remote_view_title, session.peerName),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )

                        // 「目标 N 帧/秒」：原型 d32-viewscreen 控制条第一行右侧就是这一条。
                        // `session.targetFps` 在此之前只被写（RelayControllerScreen:93/236/292/379，
                        // 初值见 ScreenPayload.kt:196），从来没有被画出来过 —— 用户看不到当前目标帧率。
                        Text(
                            text = stringResource(Res.string.screen_remote_view_fps, session.targetFps),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )

                        Spacer(Modifier.width(LocalAppSpacing.current.md))

                        var modeMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { modeMenuExpanded = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            ) {
                                Text(
                                    text = when (session.mode) {
                                        ScreenMode.Auto -> stringResource(Res.string.screen_remote_view_mode_auto)
                                        ScreenMode.Smooth -> stringResource(Res.string.screen_remote_view_mode_smooth)
                                        ScreenMode.Standard -> stringResource(Res.string.screen_remote_view_mode_standard)
                                        ScreenMode.Economy -> stringResource(Res.string.screen_remote_view_mode_economy)
                                    },
                                )
                            }
                            DropdownMenu(
                                expanded = modeMenuExpanded,
                                onDismissRequest = { modeMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.screen_remote_view_mode_auto)) },
                                    onClick = {
                                        onSetMode(ScreenMode.Auto)
                                        modeMenuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.screen_remote_view_mode_smooth)) },
                                    onClick = {
                                        onSetMode(ScreenMode.Smooth)
                                        modeMenuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.screen_remote_view_mode_standard)) },
                                    onClick = {
                                        onSetMode(ScreenMode.Standard)
                                        modeMenuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.screen_remote_view_mode_economy)) },
                                    onClick = {
                                        onSetMode(ScreenMode.Economy)
                                        modeMenuExpanded = false
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.width(LocalAppSpacing.current.md))

                        IconButton(
                            onClick = onStop,
                            modifier = Modifier
                                // 48dp 触达（视觉圆仍由 background 画在 IconButton 的默认盒子里）：
                                // 远程查看是全屏覆盖层，关闭是唯一出口，36dp 在手持时点不到。
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_close),
                                contentDescription = stringResource(Res.string.common_close),
                                tint = Color.White,
                                modifier = Modifier.size(IconSizes.Body),
                            )
                        }
                    }

                    Spacer(Modifier.height(LocalAppSpacing.current.sm))

                    // 第二行：缩放分段 +「结束查看」。缩放只作用于画面本身（`ContentScale`），
                    // 不动控制条 —— 控制条要是也跟着缩放，100% 时它会比画面还大。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.12f), RelayShapes.small)
                                .padding(LocalAppSpacing.current.xxs),
                        ) {
                            RemoteViewZoomOption(
                                label = stringResource(Res.string.screen_remote_view_fit),
                                selected = !zoomActualSize,
                                onClick = { zoomActualSize = false },
                            )
                            RemoteViewZoomOption(
                                label = stringResource(Res.string.screen_remote_view_actual_size),
                                selected = zoomActualSize,
                                onClick = { zoomActualSize = true },
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        AppButton(
                            text = stringResource(Res.string.screen_remote_view_stop_viewing),
                            onClick = onStop,
                            tone = AppButtonTone.Danger,
                        )
                    }
                }

                // 底部说明行：原型 d32-viewscreen 底部那一句（"只看画面、不能操作"）。
                Text(
                    text = stringResource(Res.string.screen_remote_view_viewer_footer),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                    // 这行压在**任意**画面内容上：白字落在亮色画面（浅色主题的文档、
                    // 白色终端）上会消失。加一层深色底是唯一不依赖内容颜色的做法。
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = LocalAppSpacing.current.xl)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = LocalAppSpacing.current.sm, vertical = LocalAppSpacing.current.xxs),
                )
            }
        }
    }
}

/**
 * 「请求查看屏幕」确认表单（原型 `d30-reqscreen` = `prototype/relay.html:4620 screenDReqScreen`）。
 *
 * 为什么要有它：查看方的入口原来是一颗图标按钮，点一下**直接发请求**。原型在这一步有
 * 一张确认表单（标题 + 一句"要等对方同意、你只能看不能操作"的说明 + 取消 / 发送请求），
 * 少了它，用户按下按钮的瞬间请求就出门了，没有反悔的余地。
 *
 * 文案逐字照原型：标题「请求查看屏幕」、说明「对方同意并选择屏幕后才会开始共享。你只能
 * 查看画面，无法操作对方电脑；双方都可以随时结束。」，按钮「取消」「发送请求」。
 *
 * 这张卡只负责"确认"，不发请求 —— 发送由调用方通过 [onSend] 接上它自己的
 * `requestScreenView(peerId)`（手机 [com.kapp.tidings.feature.chat.ChatScreen]、
 * 桌面 [com.kapp.tidings.ui.desktop.DesktopConversationsPane]）。
 */
@Composable
fun ScreenRequestConfirmDialog(
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RelayShapes.large,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = RelayElevations.Floating,
            modifier = Modifier.padding(LocalAppSpacing.current.lg),
        ) {
            Column(
                modifier = Modifier.padding(LocalAppSpacing.current.xl),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.screen_remote_view_request_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCancel) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = stringResource(Res.string.common_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(IconSizes.Body),
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(LocalAppSpacing.current.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_screen_share),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(IconSizes.Label),
                        )
                        Text(
                            text = stringResource(Res.string.screen_remote_view_request_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AppOutlinedButton(
                    text = stringResource(Res.string.screen_remote_view_request_cancel),
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppButton(
                    text = stringResource(Res.string.screen_remote_view_request_send),
                    onClick = onSend,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 「适应窗口 / 100%」里的一格。选中态既有底色也有字色差，不只靠颜色深浅。 */
@Composable
private fun RemoteViewZoomOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            // 分段控件的选中态要进语义树：只靠底色的话，读屏用户听到两个可点文字，
            // 不知道当前选的是「适应窗口」还是「100%」。
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = LocalAppSpacing.current.md, vertical = LocalAppSpacing.current.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
        )
    }
}

/**
 * 屏幕协助失败卡片（[ScreenPhase.Error]）。
 *
 * 为什么 Viewer 铺满黑底、Sharer 用普通对话框：Viewer 刚才整屏都是实时画面，失败时
 * 画面消失，用户需要一个"刚才那扇窗"的连续感（黑底 + 居中卡片）；Sharer 从来没有全屏
 * 窗口，弹一层全黑反而突兀。
 *
 * 原因文案复用 `screenReasonText`（ui/components/Common.kt:347）那套已经本地化的
 * 拒绝/结束原因词表，不再另造一套 —— 与横幅 `banner_screen_view_ended` 说同一句话。
 */
@Composable
private fun RemoteViewErrorDialog(session: ScreenSessionState, onDismiss: () -> Unit) {
    val viewer = session.role == ScreenRole.Viewer
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = !viewer,
            // 终态错误卡（对方拒绝 / 断链 / 超时）必须能按返回键关掉：
            // 一张按返回键没反应的卡会被读成"卡死了"。等待同意与共享中置顶条
            // 那两处仍拦返回键（它们不是终态，误关会丢掉进行中的会话）。
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = if (viewer) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier
            },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RelayShapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = RelayElevations.Floating,
                modifier = Modifier.padding(LocalAppSpacing.current.lg),
            ) {
                Column(
                    modifier = Modifier.padding(LocalAppSpacing.current.xl),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.lg),
                ) {
                    Text(
                        text = stringResource(Res.string.screen_remote_view_error_title, session.peerName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = remoteViewErrorReason(session.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        AppButton(
                            text = stringResource(Res.string.common_close),
                            onClick = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 失败原因 → 用户可见文案。
 *
 * 词表复用 [screenReasonText]（declined / busy / unsupported / permission-denied /
 * capture-failed / timeout / disconnected / user 都已经本地化）。这里只补它没有的
 * 两个结束码和一个兜底 —— 否则用户会看到 `protocol-error` 这种协议原文。
 */
@Composable
private fun remoteViewErrorReason(reason: String?): String = when (reason) {
    ScreenEndReasons.PROTOCOL_ERROR -> stringResource(Res.string.screen_remote_view_error_protocol)
    ScreenEndReasons.CAPTURE_ENDED -> stringResource(Res.string.screen_remote_view_error_capture_ended)
    null -> stringResource(Res.string.screen_remote_view_error_unknown)
    else -> screenReasonText(reason)
}
