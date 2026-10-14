// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop.capture

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import androidx.compose.ui.unit.sp
import app.relay.generated.resources.Res
import app.relay.generated.resources.capture_arrow_tool
import app.relay.generated.resources.capture_cancel
import app.relay.generated.resources.capture_copy
import app.relay.generated.resources.capture_drag_hint
import app.relay.generated.resources.capture_mosaic_tool
import app.relay.generated.resources.capture_rect_tool
import app.relay.generated.resources.capture_send
import app.relay.generated.resources.capture_text_tool
import app.relay.generated.resources.capture_undo
import app.relay.generated.resources.common_confirm
import app.relay.generated.resources.ic_check_double
import app.relay.generated.resources.ic_close
import app.relay.generated.resources.ic_copy
import app.relay.generated.resources.ic_scissors
import app.relay.generated.resources.ic_text_select
import app.relay.generated.resources.ic_capture_arrow
import app.relay.generated.resources.ic_capture_mosaic
import app.relay.ui.theme.RelayPrimary
import org.jetbrains.compose.resources.stringResource
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class CaptureTool {
    Select, Rect, Arrow, Text, Mosaic
}

sealed interface AnnotationItem {
    data class RectAnn(val rect: CaptureRect) : AnnotationItem
    data class ArrowAnn(val from: Offset, val to: Offset) : AnnotationItem
    data class TextAnn(val pos: Offset, val text: String) : AnnotationItem
    data class MosaicAnn(val rect: CaptureRect) : AnnotationItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureOverlayView(
    rawImage: BufferedImage,
    viewportWidth: Float,
    viewportHeight: Float,
    onConfirm: (BufferedImage) -> Unit,
    onCopy: (BufferedImage) -> Unit,
    onCancel: () -> Unit,
) {
    val composeBackground = remember(rawImage) { rawImage.toComposeImageBitmap() }
    val textMeasurer = rememberTextMeasurer()

    var selection by remember { mutableStateOf<CaptureRect?>(null) }
    var currentTool by remember { mutableStateOf(CaptureTool.Select) }
    val annotations = remember { mutableStateListOf<AnnotationItem>() }

    var isDraggingSelection by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragCurrent by remember { mutableStateOf(Offset.Zero) }

    // 文字输入弹窗
    var textInputPos by remember { mutableStateOf<Offset?>(null) }
    var pendingTextValue by remember { mutableStateOf("") }
    val textFocusRequester = remember { FocusRequester() }
    // 整层根节点自己拿焦点：`onPreviewKeyEvent` 只有挂在**有焦点的**节点上才会收到按键。
    // 覆盖窗是没有可聚焦子节点的全屏 Box，之前 Esc/Enter 收不到就是这个原因
    // （原型 screenDCapture 的 select 相位明写「拖拽框选区域 · Esc 取消」，Esc 必须真能退）。
    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }

    fun buildAnnotatedCrop(): BufferedImage? {
        val sel = selection ?: return null
        val crop = mapCaptureRectToImage(
            rect = sel,
            viewport = CaptureSize(viewportWidth, viewportHeight),
            image = CaptureSize(rawImage.width.toFloat(), rawImage.height.toFloat()),
        ) ?: return null

        val cropped = rawImage.getSubimage(crop.x, crop.y, crop.width, crop.height)
        val result = BufferedImage(crop.width, crop.height, BufferedImage.TYPE_INT_ARGB)
        val g2 = result.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.drawImage(cropped, 0, 0, null)

        val strokeScale = (crop.scaleX + crop.scaleY) / 2f
        val strokeWidth = max(2f, 3f * strokeScale)
        g2.color = java.awt.Color(0x2D, 0x6A, 0x4F) // RelayPrimary
        g2.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        for (ann in annotations) {
            when (ann) {
                is AnnotationItem.RectAnn -> {
                    val relX = (ann.rect.x - sel.x) * crop.scaleX
                    val relY = (ann.rect.y - sel.y) * crop.scaleY
                    val relW = ann.rect.width * crop.scaleX
                    val relH = ann.rect.height * crop.scaleY
                    g2.drawRect(relX.roundToInt(), relY.roundToInt(), relW.roundToInt(), relH.roundToInt())
                }
                is AnnotationItem.ArrowAnn -> {
                    val x1 = (ann.from.x - sel.x) * crop.scaleX
                    val y1 = (ann.from.y - sel.y) * crop.scaleY
                    val x2 = (ann.to.x - sel.x) * crop.scaleX
                    val y2 = (ann.to.y - sel.y) * crop.scaleY
                    drawAwtArrow(g2, x1, y1, x2, y2, strokeScale)
                }
                is AnnotationItem.TextAnn -> {
                    val tx = (ann.pos.x - sel.x) * crop.scaleX
                    val ty = (ann.pos.y - sel.y) * crop.scaleY
                    g2.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, (18f * crop.scaleY).roundToInt())
                    g2.drawString(ann.text, tx.roundToInt(), ty.roundToInt() + (18f * crop.scaleY).roundToInt())
                }
                is AnnotationItem.MosaicAnn -> {
                    val rx = max(0, ((ann.rect.x - sel.x) * crop.scaleX).roundToInt())
                    val ry = max(0, ((ann.rect.y - sel.y) * crop.scaleY).roundToInt())
                    val rw = min(result.width - rx, (ann.rect.width * crop.scaleX).roundToInt())
                    val rh = min(result.height - ry, (ann.rect.height * crop.scaleY).roundToInt())
                    applyAwtMosaic(result, rx, ry, rw, rh, strokeScale)
                }
            }
        }
        g2.dispose()
        return result
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Escape -> {
                            onCancel()
                            true
                        }
                        Key.Enter -> {
                            val target = buildAnnotatedCrop() ?: rawImage
                            onConfirm(target)
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // 手势检测器的 key **只能是常量**：`onDragStart` 会写 `selection`，
                // 若把 `selection` 放进 key，第一次拖拽就会把正在跑的手势检测器取消重建，
                // 后续 `onDrag` 全部丢失 ⇒ 表相是「拖拽完全没反应、框不出选区」（用户实测）。
                // 局部 `var … by remember { mutableStateOf(…) }` 捕获的是 state 委托本身，
                // 所以在 `pointerInput(Unit)` 里读 `selection` / `currentTool` 拿到的仍是当前值。
                // A/B 实证见 design/runtime-evidence/r2-desktop-capture-drag-select.txt：
                // 旧 key（currentTool, selection）拖拽后选区内亮度 10.0→10.0（无选区）；
                // 新 key（Unit）拖拽后 13.5→24.2 = ÷0.55（选区成立 + 内区去遮罩）。
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            val target = buildAnnotatedCrop() ?: rawImage
                            onConfirm(target)
                        },
                        onTap = { offset ->
                            if (currentTool == CaptureTool.Text && selection != null) {
                                val sel = selection!!
                                if (offset.x in sel.x..(sel.x + sel.width) && offset.y in sel.y..(sel.y + sel.height)) {
                                    textInputPos = offset
                                    pendingTextValue = ""
                                }
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragCancel = { isDraggingSelection = false },
                        onDragStart = { offset ->
                            dragStart = offset
                            dragCurrent = offset
                            isDraggingSelection = true
                            if (currentTool == CaptureTool.Select || selection == null) {
                                selection = CaptureRect(offset.x, offset.y, 0f, 0f)
                                annotations.clear()
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragCurrent = change.position
                            if (currentTool == CaptureTool.Select || selection == null) {
                                val x = min(dragStart.x, dragCurrent.x)
                                val y = min(dragStart.y, dragCurrent.y)
                                val w = kotlin.math.abs(dragCurrent.x - dragStart.x)
                                val h = kotlin.math.abs(dragCurrent.y - dragStart.y)
                                selection = CaptureRect(x, y, w, h)
                            }
                        },
                        onDragEnd = {
                            isDraggingSelection = false
                            val sel = selection
                            if (sel != null && currentTool != CaptureTool.Select) {
                                val x = min(dragStart.x, dragCurrent.x).coerceIn(sel.x, sel.x + sel.width)
                                val y = min(dragStart.y, dragCurrent.y).coerceIn(sel.y, sel.y + sel.height)
                                val endX = max(dragStart.x, dragCurrent.x).coerceIn(sel.x, sel.x + sel.width)
                                val endY = max(dragStart.y, dragCurrent.y).coerceIn(sel.y, sel.y + sel.height)
                                val w = endX - x
                                val h = endY - y
                                when (currentTool) {
                                    CaptureTool.Rect -> {
                                        if (w > 4f && h > 4f) {
                                            annotations.add(AnnotationItem.RectAnn(CaptureRect(x, y, w, h)))
                                        }
                                    }
                                    CaptureTool.Arrow -> {
                                        if (kotlin.math.hypot((dragCurrent.x - dragStart.x).toDouble(), (dragCurrent.y - dragStart.y).toDouble()) > 6) {
                                            annotations.add(AnnotationItem.ArrowAnn(dragStart, dragCurrent))
                                        }
                                    }
                                    CaptureTool.Mosaic -> {
                                        if (w > 4f && h > 4f) {
                                            annotations.add(AnnotationItem.MosaicAnn(CaptureRect(x, y, w, h)))
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    )
                }
        ) {
            // 1. 底图
            drawImage(composeBackground, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))

            val sel = selection
            if (sel == null || sel.width <= 0f || sel.height <= 0f) {
                // 全屏遮罩
                drawRect(Color.Black.copy(alpha = 0.45f))
            } else {
                // 遮罩挖孔四块
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, 0f), size = Size(size.width, sel.y))
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, sel.y), size = Size(sel.x, sel.height))
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(sel.x + sel.width, sel.y), size = Size(size.width - (sel.x + sel.width), sel.height))
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, sel.y + sel.height), size = Size(size.width, size.height - (sel.y + sel.height)))

                // 选区边框
                drawRect(RelayPrimary, topLeft = Offset(sel.x, sel.y), size = Size(sel.width, sel.height), style = Stroke(width = 2.dp.toPx()))

                // 绘制已有的标注
                for (ann in annotations) {
                    drawAnnotation(ann, textMeasurer)
                }

                // 正在拖拽画标注中的预览
                if (isDraggingSelection && currentTool != CaptureTool.Select) {
                    when (currentTool) {
                        CaptureTool.Rect -> {
                            val rx = min(dragStart.x, dragCurrent.x)
                            val ry = min(dragStart.y, dragCurrent.y)
                            val rw = kotlin.math.abs(dragCurrent.x - dragStart.x)
                            val rh = kotlin.math.abs(dragCurrent.y - dragStart.y)
                            drawRect(RelayPrimary, topLeft = Offset(rx, ry), size = Size(rw, rh), style = Stroke(width = 2.dp.toPx()))
                        }
                        CaptureTool.Arrow -> {
                            drawComposeArrow(dragStart, dragCurrent)
                        }
                        CaptureTool.Mosaic -> {
                            val rx = min(dragStart.x, dragCurrent.x)
                            val ry = min(dragStart.y, dragCurrent.y)
                            val rw = kotlin.math.abs(dragCurrent.x - dragStart.x)
                            val rh = kotlin.math.abs(dragCurrent.y - dragStart.y)
                            drawRect(Color.Gray.copy(alpha = 0.6f), topLeft = Offset(rx, ry), size = Size(rw, rh))
                        }
                        else -> Unit
                    }
                }
            }
        }

        // 未框选时的引导：原型 select 相位的正文逐字「拖拽框选区域 · Esc 取消」
        // （prototype/relay.html:4611）；这条串早就在 strings.xml 里、也在本文件 import 过，
        // 但一直没有画出来。
        //
        // 字号/颜色照原型 `.capt .stage`（同文件 :1043-1044）：
        // `font-size:12px; color:rgba(232,239,236,.55)` ⇒ bodySmall(12sp) + 0.55 的 #E8EFEC。
        // （第一版误用了 bodyLarge/白 85%，比原型大一号还更亮。）
        val sel = selection
        val usableSelection = sel != null && sel.width > 20f && sel.height > 20f
        if (!usableSelection) {
            Text(
                text = stringResource(Res.string.capture_drag_hint),
                color = Color(0x8CE8EFEC),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 浮动工具栏：**没有选区也要画** —— 原型两个相位是同一根 bar，
        // 只是 `.captbar.below`（未框选，贴视口底部）与 `.captbar.top`（已框选，贴选区分）。
        if (!isDraggingSelection) {
            val toolbarPos = if (usableSelection) {
                placeCaptureToolbar(
                    selection = sel,
                    toolbar = CaptureSize(400f, 44f),
                    viewport = CaptureSize(viewportWidth, viewportHeight),
                )
            } else {
                placeCaptureToolbarAtBottom(
                    toolbar = CaptureSize(400f, 44f),
                    viewport = CaptureSize(viewportWidth, viewportHeight),
                )
            }
            Surface(
                modifier = Modifier
                    .offset { IntOffset(toolbarPos.left.roundToInt(), toolbarPos.top.roundToInt()) }
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = LocalAppSpacing.current.sm6,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm6, vertical = LocalAppSpacing.current.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { currentTool = if (currentTool == CaptureTool.Rect) CaptureTool.Select else CaptureTool.Rect },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_scissors),
                            contentDescription = stringResource(Res.string.capture_rect_tool),
                            tint = if (currentTool == CaptureTool.Rect) RelayPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(IconSizes.Body),
                        )
                    }
                    IconButton(
                        onClick = { currentTool = if (currentTool == CaptureTool.Arrow) CaptureTool.Select else CaptureTool.Arrow },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_capture_arrow),
                            contentDescription = stringResource(Res.string.capture_arrow_tool),
                            tint = if (currentTool == CaptureTool.Arrow) RelayPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(IconSizes.Title),
                        )
                    }
                    IconButton(
                        onClick = { currentTool = if (currentTool == CaptureTool.Text) CaptureTool.Select else CaptureTool.Text },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_text_select),
                            contentDescription = stringResource(Res.string.capture_text_tool),
                            tint = if (currentTool == CaptureTool.Text) RelayPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(IconSizes.Body),
                        )
                    }
                    IconButton(
                        onClick = { currentTool = if (currentTool == CaptureTool.Mosaic) CaptureTool.Select else CaptureTool.Mosaic },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_capture_mosaic),
                            contentDescription = stringResource(Res.string.capture_mosaic_tool),
                            tint = if (currentTool == CaptureTool.Mosaic) RelayPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(IconSizes.Body),
                        )
                    }
                    if (annotations.isNotEmpty()) {
                        IconButton(
                            onClick = { annotations.removeLastOrNull() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Undo,
                                contentDescription = stringResource(Res.string.capture_undo),
                                modifier = Modifier.size(IconSizes.Body),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .padding(horizontal = LocalAppSpacing.current.xs)
                            .size(1.dp, 20.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_close),
                            contentDescription = stringResource(Res.string.capture_cancel),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(IconSizes.Body),
                        )
                    }
                    IconButton(
                        onClick = {
                            val target = buildAnnotatedCrop() ?: rawImage
                            onCopy(target)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_copy),
                            contentDescription = stringResource(Res.string.capture_copy),
                            modifier = Modifier.size(IconSizes.Body),
                        )
                    }
                    IconButton(
                        onClick = {
                            val target = buildAnnotatedCrop() ?: rawImage
                            onConfirm(target)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painterResource(Res.drawable.ic_check_double),
                            contentDescription = stringResource(Res.string.capture_send),
                            tint = RelayPrimary,
                            modifier = Modifier.size(IconSizes.Title),
                        )
                    }
                }
            }
        }

        // 文字标注录入弹窗
        val pos = textInputPos
        if (pos != null) {
            BasicAlertDialog(
                onDismissRequest = { textInputPos = null }
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = LocalAppSpacing.current.sm6,
                    modifier = Modifier.padding(LocalAppSpacing.current.lg)
                ) {
                    Row(
                        modifier = Modifier.padding(LocalAppSpacing.current.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = pendingTextValue,
                            onValueChange = { pendingTextValue = it },
                            placeholder = { Text(stringResource(Res.string.capture_text_tool)) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(textFocusRequester)
                        )
                        TextButton(
                            onClick = {
                                if (pendingTextValue.isNotBlank()) {
                                    annotations.add(AnnotationItem.TextAnn(pos, pendingTextValue))
                                }
                                textInputPos = null
                            }
                        ) {
                            Text(stringResource(Res.string.common_confirm))
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                textFocusRequester.requestFocus()
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnotation(
    ann: AnnotationItem,
    textMeasurer: TextMeasurer,
) {
    when (ann) {
        is AnnotationItem.RectAnn -> {
            drawRect(
                color = RelayPrimary,
                topLeft = Offset(ann.rect.x, ann.rect.y),
                size = Size(ann.rect.width, ann.rect.height),
                style = Stroke(width = 2.5f)
            )
        }
        is AnnotationItem.ArrowAnn -> {
            drawComposeArrow(ann.from, ann.to)
        }
        is AnnotationItem.TextAnn -> {
            drawText(
                textMeasurer = textMeasurer,
                text = ann.text,
                topLeft = ann.pos,
                style = TextStyle(
                    color = RelayPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        is AnnotationItem.MosaicAnn -> {
            drawRect(
                color = Color.DarkGray.copy(alpha = 0.5f),
                topLeft = Offset(ann.rect.x, ann.rect.y),
                size = Size(ann.rect.width, ann.rect.height)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawComposeArrow(from: Offset, to: Offset) {
    val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val head = 14f
    drawLine(color = RelayPrimary, start = from, end = to, strokeWidth = 3f)

    val p1 = Offset(
        (to.x - head * cos(angle - Math.PI / 6)).toFloat(),
        (to.y - head * sin(angle - Math.PI / 6)).toFloat()
    )
    val p2 = Offset(
        (to.x - head * cos(angle + Math.PI / 6)).toFloat(),
        (to.y - head * sin(angle + Math.PI / 6)).toFloat()
    )
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        close()
    }
    drawPath(path, color = RelayPrimary)
}

private fun drawAwtArrow(g2: Graphics2D, x1: Float, y1: Float, x2: Float, y2: Float, scale: Float) {
    val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
    val head = 12.0 * scale
    g2.drawLine(x1.roundToInt(), y1.roundToInt(), x2.roundToInt(), y2.roundToInt())

    val x3 = (x2 - head * cos(angle - Math.PI / 6)).roundToInt()
    val y3 = (y2 - head * sin(angle - Math.PI / 6)).roundToInt()
    val x4 = (x2 - head * cos(angle + Math.PI / 6)).roundToInt()
    val y4 = (y2 - head * sin(angle + Math.PI / 6)).roundToInt()

    val polyX = intArrayOf(x2.roundToInt(), x3, x4)
    val polyY = intArrayOf(y2.roundToInt(), y3, y4)
    g2.fillPolygon(polyX, polyY, 3)
}

private fun applyAwtMosaic(img: BufferedImage, x: Int, y: Int, w: Int, h: Int, scale: Float) {
    val block = max(6, (10 * scale).roundToInt())
    for (by in y until (y + h) step block) {
        for (bx in x until (x + w) step block) {
            val bw = min(block, (x + w) - bx)
            val bh = min(block, (y + h) - by)
            val rgb = img.getRGB(min(bx, img.width - 1), min(by, img.height - 1))
            for (dy in 0 until bh) {
                for (dx in 0 until bw) {
                    img.setRGB(bx + dx, by + dy, rgb)
                }
            }
        }
    }
}
