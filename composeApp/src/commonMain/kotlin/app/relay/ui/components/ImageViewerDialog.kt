// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.relay.core.fs.readFileBytes
import app.relay.core.ioDispatcher
import app.relay.generated.resources.Res
import app.relay.generated.resources.chat_image_preview_close
import app.relay.generated.resources.chat_image_preview_title
import app.relay.generated.resources.file_share
import app.relay.generated.resources.ic_close
import app.relay.platform.ReceivedFileActions
import app.relay.platform.decodeImageBytes
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing

/**
 * 全屏大图查看器：点击聊天气泡中的图片时弹出，支持居中缩放自适应展示、关闭与外部导出/分享。
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
                Image(
                    bitmap = img,
                    contentDescription = stringResource(Res.string.chat_image_preview_title),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(LocalAppSpacing.current.lg)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}, // 阻止点击图片区域触发关闭
                        ),
                )
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
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = stringResource(Res.string.chat_image_preview_close),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
