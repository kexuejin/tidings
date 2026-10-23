// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.ui.desktop.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import java.awt.Component
import java.awt.Cursor
import java.awt.EventQueue
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DragGestureEvent
import java.awt.dnd.DragGestureListener
import java.awt.dnd.DragSource
import java.awt.dnd.DragSourceAdapter
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.File
import javax.imageio.ImageIO
import java.awt.Window
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * 当前窗口的 AWT [Window]，供需要原生句柄的桌面能力使用。
 *
 * ## 为什么不直接用 Compose 的 `LocalWindow`
 * `androidx.compose.ui.window.LocalWindow` 在 1.11.1 里的 **Kotlin 声明是 internal**：
 * 字节码上那个 `LocalWindowKt.getLocalWindow()` 是 public，跨模块却取不到（Kotlin 的
 * internal 会改名 + 加 `@JvmSynthetic` 之外的限制），编译期直接报
 * `Cannot access 'val LocalWindow: ProvidableCompositionLocal<Window?>': it is internal in file.`。
 * `androidx.compose.ui.platform.WindowInfo` 又没有 AWT 访问器。所以这里自建一个，
 * 由真正持有 `FrameWindowScope.window` 的地方（`DesktopWindow.kt`）提供值 ——
 * 既不用 Compose 的内部 API，也不用把 `Window` 一路穿过
 * `DesktopShell → FilesPane → FileRowCard` 的签名。
 */
internal val LocalAwtWindow = compositionLocalOf<Window?> { null }

/**
 * 把已落盘的文件从文件列表**拖出去**。
 *
 * ## 为什么不用 Compose 的拖放 API
 * Compose Multiplatform 1.11.1 里有 `androidx.compose.ui.draganddrop.*`，但没有给应用
 * 层用的 `Modifier.dragAndDropSource`：真正能用的 `DragAndDropSourceModifierNode` 是内部
 * 节点，包它的 `RootDragAndDropElement` 是 package-private。所以「从 Compose 里发起一次
 * 原生拖拽」在这套库里没有公开入口。
 * 于是走反向：Compose 只负责**识别手势**（`detectDragGestures`），真正的拖拽交给
 * `java.awt.dnd.DragSource` —— 那是 JDK 自带的，零新增依赖。
 *
 * ## 为什么全部包在 runCatching 里
 * 拖拽是那种「失败得很安静」的交互：`startDrag` 返回 false、系统拒绝跨进程传输、
 * 无 AWT 组件可依附 —— 都不会抛异常。一个文件视图因为拖不出文件而崩掉，比拖不出
 * 文件本身糟糕得多，所以每个会碰到 AWT 的地方都单独兜住。
 */
internal object DesktopDragOut {

    /**
     * 把 [FileCardUi.savedPaths] 变成真正可拖的文件列表。
     *
     * 存在的理由：文件卡片上的 `savedPaths` 只是**记录**，传输还在进行、文件被手工删掉、
     * 或路径来自另一台机器时都会指向不存在的文件。把这些直接交给 AWT 会得到一个
     * 拖出去空空如也的手柄 —— 用户只会觉得「这功能坏了」。所以这里只留下真正存在的
     * 普通文件，并按绝对路径去重（同一份文件可能既在消息里又在消息里重复出现）。
     */
    fun resolveFiles(savedPaths: List<String>): List<File> {
        val unique = LinkedHashMap<String, File>()
        savedPaths.forEach { raw ->
            val path = raw.trim()
            if (path.isEmpty()) return@forEach
            val file = File(path)
            if (!file.isFile) return@forEach
            unique.putIfAbsent(file.absolutePath, file)
        }
        return unique.values.toList()
    }

    /**
     * 系统剪贴板格式。Finder / Explorer / 编辑器认的是 `javaFileListFlavor`；
     * 文本这份是兜底 —— 有些程序（例如终端里 `cd`）只认纯文本路径。
     */
    fun transferable(files: List<File>): Transferable = object : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> =
            arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.javaFileListFlavor || flavor == DataFlavor.stringFlavor

        override fun getTransferData(flavor: DataFlavor): Any = when {
            // javaFileListFlavor 的表示类是 java.util.List<File>，**必须是 List**。
            // 这里曾经给的是数组（files.toTypedArray()），类型对不上：接收方
            // （Finder / 资源管理器）按 List 取会直接拿不到数据，拖出去等于白拖。
            flavor == DataFlavor.javaFileListFlavor -> files.toList()
            flavor == DataFlavor.stringFlavor -> files.joinToString("\n") { it.absolutePath }
            else -> throw IllegalArgumentException("不支持的数据格式：$flavor")
        }
    }

    /**
     * 手势位置 → 屏幕坐标。
     *
     * AWT 的 [DragGestureEvent] 要的是屏幕坐标，而 `detectDragGestures` 给的是**窗口内**
     * 坐标。少了窗口左上角的这一加，拖拽手柄就会飘到窗口左上角附近 —— 表现是
     * 「拖出去了，但文件掉在了奇怪的地方」。取整在这里做，因为 AWT 事件只收 int。
     */
    fun screenOrigin(originInWindow: Offset, pointerInWindow: Offset, windowLocationOnScreen: Point): Offset =
        Offset(
            x = windowLocationOnScreen.x + originInWindow.x + pointerInWindow.x,
            y = windowLocationOnScreen.y + originInWindow.y + pointerInWindow.y,
        )

    fun toPixels(offset: Offset): Point = Point(
        offset.x.roundToInt(),
        offset.y.roundToInt(),
    )

    /**
     * 拖拽过程中显示的手型。
     *
     * 注意 `java.awt.Cursor` **没有** `COPY_CURSOR` 这个常量（只有 DEFAULT/CROSSHAIR/
     * TEXT/WAIT/各向 RESIZE/HAND/MOVE）。文件拖出的「复制」光标由 DnD 自己提供，就是
     * `DragSource.DefaultCopyDrop`；用它而不是自己拼一个不存在的常量。
     */
    internal fun dragCursor(): Cursor = DragSource.DefaultCopyDrop

    /**
     * 拖拽跟手小图：把文件名画成一张小图挂在光标下。
     *
     * 没有它的话，拖拽时用户看不到自己拖的是几个文件、多少大小 —— 这在 Finder 里
     * 是基本预期，缺了会让人以为没拖起来。
     */
    internal fun dragImage(fileNames: List<String>, width: Int, height: Int): BufferedImage? = runCatching {
        val image = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.85f)
            graphics.color = java.awt.Color(255, 255, 255, 235)
            graphics.fillRoundRect(0, 0, image.width, image.height, 8, 8)
            graphics.color = java.awt.Color(40, 40, 40)
            val label = when (fileNames.size) {
                0 -> ""
                1 -> fileNames.first()
                else -> "${fileNames.first()} 等 ${fileNames.size} 个文件"
            }
            graphics.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11)
            val metrics = graphics.fontMetrics
            val text = if (metrics.stringWidth(label) <= image.width - 8) {
                label
            } else {
                // 省略号自己加：java.awt 的 Label 不会替我们截断。
                var cut = label.length
                while (cut > 1 && metrics.stringWidth(label.substring(0, cut) + "…") > image.width - 8) cut--
                label.substring(0, cut) + "…"
            }
            graphics.drawString(text, 4, metrics.ascent + 4)
        } finally {
            graphics.dispose()
        }
        image
    }.getOrNull()

    /** 调试/测试用：确认图片能编出来（headless 下 ImageIO 仍可用）。 */
    internal fun dragImageOrNull(fileNames: List<String>): BufferedImage? = dragImage(fileNames, 160, 24)

    internal fun isHeadless(): Boolean = runCatching { java.awt.GraphicsEnvironment.isHeadless() }
        .getOrDefault(true)

    internal fun imageIoUsable(): Boolean = runCatching { ImageIO.getImageReadersByFormatName("png").hasNext() }
        .getOrDefault(false)
}

/**
 * 让文件卡片成为**拖拽源**：按住卡片拖动，把 [savedPaths] 指向的文件拖到
 * Finder / 资源管理器 / 任何接受文件拖放的地方。
 *
 * 没有可拖的文件时**不挂**任何手势 —— 一个什么都拖不出来的手柄比没有手柄更糟。
 */
@Composable
private fun Modifier.fileRowDragSource(savedPaths: List<String>): Modifier {
    val files = DesktopDragOut.resolveFiles(savedPaths)
    if (files.isEmpty()) return this

    val window = LocalAwtWindow.current
    // java.awt.Window 本身就是 Component（Window -> Container -> Component），
    // 不需要 Swing 的 contentPane —— 那是 RootPaneContainer 的成员，java.awt.Window 上没有。
    val component: Component? = window
    var originInWindow by remember { mutableStateOf(Offset.Zero) }

    val dragSource = remember {
        DragSource()
    }
    // JDK 21 里 DragGestureEvent 只有
    // (DragGestureRecognizer, int, Point, List<? extends InputEvent>) 这一个公开构造器，
    // 必须先从同一个 DragSource 上拿到识别器，getDragSource() 才是同一个源，
    // DragSource.startDrag(...) 才会接受这个事件。
    val gestureRecognizer = remember(component) {
        val c = component ?: return@remember null
        runCatching {
            dragSource.createDefaultDragGestureRecognizer(
                c,
                DnDConstants.ACTION_COPY,
                // 合成手势没有真实识别回调，AWT 原生手势由系统自己分发。
                object : DragGestureListener {
                    override fun dragGestureRecognized(e: DragGestureEvent) = Unit
                },
            )
        }.getOrNull()
    }
    val transferable = remember(files) { DesktopDragOut.transferable(files) }
    // BufferedImage 本身就是 java.awt.Image，直接交给 startDrag 的 image 重载。
    val dragImage = remember(files) {
        DesktopDragOut.dragImageOrNull(files.map { it.name })
    }

    return this
        .onGloballyPositioned { coordinates ->
            originInWindow = coordinates.positionInWindow()
        }
        .pointerInput(files) {
            detectDragGestures(
                onDragStart = { pointerInWindow ->
                    val awtWindow = window ?: return@detectDragGestures
                    val target = component ?: return@detectDragGestures
                    val recognizer = gestureRecognizer ?: return@detectDragGestures
                    if (DesktopDragOut.isHeadless()) return@detectDragGestures
                    val screen = DesktopDragOut.screenOrigin(
                        originInWindow = originInWindow,
                        pointerInWindow = pointerInWindow,
                        windowLocationOnScreen = runCatching { awtWindow.locationOnScreen }.getOrElse { Point(0, 0) },
                    )
                    val pixels = DesktopDragOut.toPixels(screen)
                    val origin = Point(pixels.x, pixels.y)
                    // JDK 21 的公开构造器：合成一个按下事件当触发器，识别器是真的，
                    // 但鼠标轨迹来自我们给的坐标。
                    val trigger = MouseEvent(
                        target,
                        MouseEvent.MOUSE_PRESSED,
                        System.currentTimeMillis(),
                        InputEvent.BUTTON1_DOWN_MASK,
                        pixels.x,
                        pixels.y,
                        1,
                        false,
                        MouseEvent.BUTTON1,
                    )
                    // Compose Desktop 的回调不在 AWT 事件线程上，startDrag 必须在 EDT 上发起。
                    EventQueue.invokeLater {
                        runCatching {
                            val event = DragGestureEvent(
                                recognizer,
                                DnDConstants.ACTION_COPY,
                                origin,
                                listOf<InputEvent>(trigger),
                            )
                            // BufferedImage 直接就是 java.awt.Image；没有图就退回无图重载，
                            // 让系统画默认的文件图标。
                            val image = dragImage
                            if (image != null) {
                                dragSource.startDrag(
                                    event,
                                    DesktopDragOut.dragCursor(),
                                    image,
                                    Point(0, 0),
                                    transferable,
                                    object : DragSourceAdapter() {},
                                )
                            } else {
                                dragSource.startDrag(
                                    event,
                                    DesktopDragOut.dragCursor(),
                                    transferable,
                                    object : DragSourceAdapter() {},
                                )
                            }
                        }
                    }
                },
                // AWT 已经接管这次拖拽；Compose 侧只消费剩余的移动事件，
                // 免得同一个手势被两套系统各解释一遍。
                onDrag = { change, _ -> change.consume() },
            )
        }
}

/**
 * 宿主接缝：desktopApp 提供真正的 java.awt.dnd 拖出实现，
 * commonMain 的 [com.kapp.tidings.ui.desktop.LocalFileRowDrag] 默认空实现。
 */
@Composable
internal fun ProvideDesktopFileDragSource(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFileRowDrag provides { paths -> Modifier.fileRowDragSource(paths) }) {
        content()
    }
}
