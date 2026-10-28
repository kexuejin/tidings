// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kapp.tidings.core.fs.readFileBytes
import com.kapp.tidings.core.ioDispatcher
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.chat_image_preview_close
import com.kapp.tidings.generated.resources.chat_image_preview_title
import com.kapp.tidings.generated.resources.file_share
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.platform.ReceivedFileActions
import com.kapp.tidings.platform.decodeImageBytes
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

import com.kapp.tidings.ui.theme.LocalAppSpacing

/**
 * 缩放的上限。6 倍足以看清手机截图里的小字，再大就开始看到像素点而不是信息。
 */
internal const val IMAGE_VIEWER_MAX_SCALE: Float = 6f

/**
 * 一次捏合手势后应落到的新缩放值：夹在 [1, IMAGE_VIEWER_MAX_SCALE]。
 *
 * **下限是 1 而不是更小**：缩小到比「完整看到」更小没有信息增量，只会让用户在
 * 一片黑底上找图。抽成纯函数是为了让这条边界能被单测钉住（组合测试要跑设备）。
 */
internal fun imageViewerScaleAfter(gestureZoom: Float, current: Float): Float =
    (current * gestureZoom).coerceIn(1f, IMAGE_VIEWER_MAX_SCALE)

/**
 * 缩放状态下是否该把拖动位移清零。
 *
 * 缩回 1 倍时必须清零：否则图会停在偏心的位置，看起来像「放大了又没完全还原」。
 */
internal fun imageViewerShouldResetPan(scale: Float): Boolean = scale <= 1f

/**
 * 全屏大图查看器：点击聊天气泡中的图片时弹出，支持捏合缩放 / 拖动 / 关闭与外部导出分享。
 */
@Composable
fun ImageViewerDialog(
    imagePath: String,
    onDismiss: () -> Unit,
    receivedFileActions: ReceivedFileActions? = null,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, imagePath) {
        value = withContext(ioDispatcher) {
            readFileBytes(imagePath)?.let { decodeImageBytes(it) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val img = bitmap
            if (img != null) {
                // 缩放与拖动状态。`scale` 上限 6 倍够看清截图里的小字；`offset` 在
                // 缩放态下才生效（1 倍时拖动没有意义，只会让图轻微飘）。
                var scale by remember(imagePath) { mutableFloatStateOf(1f) }
                var offset by remember(imagePath) { mutableStateOf(Offset.Zero) }
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = img,
                        contentDescription = stringResource(Res.string.chat_image_preview_title),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(LocalAppSpacing.current.lg)
                            // 双指缩放 + 拖动。与「点击关闭」的冲突处理：单击仍关闭（未缩放时），
                            // 缩放后单击先复位而不是关闭 —— 否则用户想还原大小会把整个查看器关掉。
                            .pointerInput(imagePath) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val next = imageViewerScaleAfter(gestureZoom = zoom, current = scale)
                                    // 缩回 1 倍时把位移清零：否则图会停在偏心的位置，
                                    // 看起来像「放大了又没完全还原」。
                                    offset = if (imageViewerShouldResetPan(next)) Offset.Zero else offset + pan
                                    scale = next
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    // 缩放后单击 = 复位；未缩放时单击 = 不做事（阻止穿透到背景关闭）。
                                    if (scale > 1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    }
                                },
                            ),
                    )
                }
            }

            // 顶部操作栏（关闭 + 外部分享）
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(LocalAppSpacing.current.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (receivedFileActions != null) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = LocalAppSpacing.current.md),
                    ) {
                        IconButton(
                            onClick = { receivedFileActions.share(imagePath) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Text(
                                text = stringResource(Res.string.file_share),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f),
                ) {
                    AppIconButton(
                        icon = Res.drawable.ic_close,
                        contentDescription = stringResource(Res.string.chat_image_preview_close),
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }
}
