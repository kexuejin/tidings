// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.relay.core.protocol.ScreenEndReasons
import app.relay.core.protocol.ScreenMode
import app.relay.core.protocol.ScreenPhase
import app.relay.core.protocol.ScreenRole
import app.relay.core.protocol.ScreenSessionState
import app.relay.generated.resources.Res
import app.relay.generated.resources.common_close
import app.relay.generated.resources.ic_close
import app.relay.generated.resources.ic_screen_share
import app.relay.generated.resources.screen_remote_view_actual_size
import app.relay.generated.resources.screen_remote_view_connecting
import app.relay.generated.resources.screen_remote_view_consent_accept
import app.relay.generated.resources.screen_remote_view_consent_desc
import app.relay.generated.resources.screen_remote_view_consent_reject
import app.relay.generated.resources.screen_remote_view_consent_title
import app.relay.generated.resources.screen_remote_view_error_capture_ended
import app.relay.generated.resources.screen_remote_view_error_protocol
import app.relay.generated.resources.screen_remote_view_error_title
import app.relay.generated.resources.screen_remote_view_error_unknown
import app.relay.generated.resources.screen_remote_view_fit
import app.relay.generated.resources.screen_remote_view_fps
import app.relay.generated.resources.screen_remote_view_mode_auto
import app.relay.generated.resources.screen_remote_view_mode_economy
import app.relay.generated.resources.screen_remote_view_mode_smooth
import app.relay.generated.resources.screen_remote_view_mode_standard
import app.relay.generated.resources.screen_remote_view_request_banner
import app.relay.generated.resources.screen_remote_view_request_cancel
import app.relay.generated.resources.screen_remote_view_request_send
import app.relay.generated.resources.screen_remote_view_request_title
import app.relay.generated.resources.screen_remote_view_sharing_hint
import app.relay.generated.resources.screen_remote_view_stop
import app.relay.generated.resources.screen_remote_view_stop_viewing
import app.relay.generated.resources.screen_remote_view_title
import app.relay.generated.resources.screen_remote_view_viewer_footer
import app.relay.generated.resources.screen_remote_view_waiting_consent
import app.relay.platform.decodeImageBytes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.RelayElevations
import app.relay.ui.theme.RelayShapes
import app.relay.app.model.ScreenSessionUiState

/**
 * 屏幕协助全屏/对话框组件：
 * - Viewer: 全屏实时画面渲染、帧率模式切换、主动结束；
 * - Sharer: 邀请同意/拒绝弹窗、置顶共享中状态条与停止共享。
 */
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

    // 2. Sharer 共享中置顶条
    if (session.role == ScreenRole.Sharer && (session.phase == ScreenPhase.Preparing || session.phase == ScreenPhase.Connecting || session.phase == ScreenPhase.Active)) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
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
                    val frameBytes = state.latestFrameBytes
                    val bitmap = remember(frameBytes) {
                        frameBytes?.let { decodeImageBytes(it) }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(Res.string.screen_remote_view_title, session.peerName),
                            contentScale = if (zoomActualSize) ContentScale.None else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
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
                                .size(36.dp)
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
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = LocalAppSpacing.current.xl),
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
 * `requestScreenView(peerId)`（手机 [app.relay.feature.chat.ChatScreen]、
 * 桌面 [app.relay.ui.desktop.DesktopConversationsPane]）。
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
            .clickable(onClick = onClick)
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
