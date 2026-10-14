// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import app.relay.core.protocol.Protocol
import kotlin.math.roundToInt

/**
 * 当前窗口的**逻辑**尺寸（dp），随窗口缩放重组。
 *
 * 不能用 `window.width` / `window.height`：那是 AWT 的普通属性，不是 Compose 状态，
 * 只在组合时读一次。启动那一刻窗口尚未定型，首读值通常小于任何断点，于是整壳被
 * 永久判成 Compact —— 用户把窗口拖到全屏也不会变。实测：启动 1100×720 与外部
 * `setSize(1500, 950)` 后重截**都是** Compact 底导，而按 `desktopWindowSizeClass`
 * 的阈值这两档都该是 Expanded。`LocalWindowInfo.containerSize` 是随窗口尺寸变化的
 * 状态，才是正确来源。
 *
 * 换算成 dp 比对阈值可以顺带消掉一个单位陷阱：容器尺寸在某些实现里是物理像素、
 * 在另一些里已经是逻辑像素，除以 `LocalDensity` 后两种情况都得到原型 CSS px 的数值。
 */
@Composable
public fun rememberDesktopWindowSizeDp(): IntSize {
    val container = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) {
        IntSize(container.width.toDp().value.roundToInt(), container.height.toDp().value.roundToInt())
    }
}

/**
 * 桌面窗口分档。
 *
 * 分档阈值来自桌面设计稿的断点（原 `prototype/desktop/` 单端稿的 `@media`，该稿已并入
 * `prototype/relay.html` 并删除）：Expanded ≥1000px 双栏；Medium 竖导 + 列表详情；
 * Compact 底部三段。
 *
 * 合并后的原型不再用 CSS 断点，而是把桌面四档画成固定画布预设
 * （`prototype/relay.html?size=compact|medium|expanded|wide` → 560 / 760 / 1100 / 1440），
 * 四个预设正好落在下面这套阈值里（560 → Compact、760 → Medium、1100 / 1440 → Expanded），
 * 可以逐档对照。原型断点的单位是 CSS px，等价于 Compose 的 **dp**，所以这里量的是 dp，
 * 由 [rememberDesktopWindowSizeDp] 从可观察的容器尺寸换算，不是物理像素。
 *
 * 唯一偏离设计稿的是 Medium 的下界：设计稿写 600，这里取 [DesktopMetrics.MEDIUM_MIN_PX]，
 * 因为 600px 宽减去竖导与侧栏后聊天栏只剩 236px —— 桌面输入栏在那里是坏的，
 * 不是「窄一点」而是两条提示语叠在一起、发送按钮被挤出屏幕。窄窗退回单栏更诚实。
 */
public enum class DesktopWindowSizeClass {
    Compact,
    Medium,
    Expanded,
    ;

    /** Expanded 才双栏；Medium 用竖导 + 列表详情；Compact 退化成底导单栏。 */
    val twoPane: Boolean get() = this == Expanded

    /** Medium/Compact 都需要一块导航条，位置不同而已。 */
    val usesRail: Boolean get() = this == Medium
}

/**
 * 外壳要不要带桌面 chrome。
 *
 * 桌面和平板复用同一个 [DesktopShell]，但只有桌面有系统级外壳：菜单栏、标题栏、
 * 状态栏，以及（在 desktopApp 里）托盘、拖放导出、独立设置窗口。平板把这些全部拿掉，
 * 只保留工具栏——它是内容导航（三视图 + 搜索 + 新建），不是桌面 chrome。
 */
public enum class DesktopChromeMode {
    /** 桌面：菜单栏 + 标题栏 + 工具栏 + 状态栏。 */
    Desktop,

    /** 平板：只有工具栏，其余 chrome 全部不渲染。 */
    Tablet,
    ;

    /** 桌面 chrome 是否渲染；工具栏不受此控制。 */
    val showsDesktopChrome: Boolean get() = this == Desktop
}

public fun desktopChromeModeFor(isDesktop: Boolean): DesktopChromeMode =
    if (isDesktop) DesktopChromeMode.Desktop else DesktopChromeMode.Tablet

/** 桌面 chrome 高度，逐条对齐原型 CSS 变量，不是随手取的数。 */
public object DesktopMetrics {
    const val MENUBAR_PX = 26
    const val TITLEBAR_PX = 40
    const val TOOLBAR_PX = 46
    const val STATUSBAR_PX = 26
    const val SIDEBAR_PX = 300

    /** 26 + 40 + 46 + 26 = 138。这条不是随手加的，是设计稿里明确要求纳入预算的。 */
    const val CHROME_PX = MENUBAR_PX + TITLEBAR_PX + TOOLBAR_PX + STATUSBAR_PX

    const val MEDIUM_RAIL_PX = 64
    const val COMPACT_NAV_PX = 60

    /** 1280×800 减去 chrome 与状态栏后，聊天区还剩约 660px；低于这个值输入区就该浮起来。 */
    const val MIN_CHAT_HEIGHT_PX = 420

    /** 双栏阈值。低于它就换竖导，因为侧栏 300px 加聊天区会把详情挤成一条缝。 */
    const val EXPANDED_MIN_PX = 1000

    /**
     * 聊天栏的下限宽度，[MEDIUM_MIN_PX] 由它反推（Kotlin 的 `const val` 不能前向引用，
     * 所以它必须排在 [MEDIUM_MIN_PX] 前面）。
     *
     * 取的是桌面输入栏**最窄**形态：草稿/路由提示（「草稿 0 / 800 B · 走 UDP 单播」约 130px）
     * + 发送按钮（约 72px）+ 两侧内边距，约 260px，留一点余量取 300。
     * 窄到连这条都装不下时，输入栏会先丢掉键盘提示（见 `DesktopComposer`）再谈截断。
     */
    const val MIN_CHAT_PANE_PX = 300

    /** 竖导阈值。再窄下去竖导本身放不下三个图标带文字，退化成底部三段。 */
    const val MEDIUM_MIN_PX = MEDIUM_RAIL_PX + SIDEBAR_PX + MIN_CHAT_PANE_PX

    /** 窗口最小尺寸，与 ComposeWindow 的 minSize 一致：560×620。 */
    const val MIN_WINDOW_WIDTH_PX = 560
    const val MIN_WINDOW_HEIGHT_PX = 620

    /** 验收尺寸 1100×720，也是首次启动默认大小。 */
    const val DEFAULT_WINDOW_WIDTH_PX = 1100
    const val DEFAULT_WINDOW_HEIGHT_PX = 720

    /** 独立设置窗。 */
    const val SETTINGS_WINDOW_WIDTH_PX = 720
    const val SETTINGS_WINDOW_HEIGHT_PX = 560

    /**
     * 平板只留工具栏，所以可用高度要按 46 扣而不是 138。
     * 不按 chromeMode 扣会让 800×1280 的平板凭空少 92dp，竖向判定跟着漂。
     */
    const val TABLET_CHROME_PX = TOOLBAR_PX

    /** 按外壳模式返回应当从窗口高度里扣掉的 chrome 高度。 */
    fun chromeHeightPx(chromeMode: DesktopChromeMode): Int = when (chromeMode) {
        DesktopChromeMode.Desktop -> CHROME_PX
        DesktopChromeMode.Tablet -> TABLET_CHROME_PX
    }
}

public fun desktopWindowSizeClass(
    widthPx: Int,
    heightPx: Int,
    chromeMode: DesktopChromeMode = DesktopChromeMode.Desktop,
): DesktopWindowSizeClass {
    val usableHeight = (heightPx - DesktopMetrics.chromeHeightPx(chromeMode)).coerceAtLeast(0)
    return when {
        // 高度太矮时双栏没有意义：列表和详情各分一半，谁都放不下。
        widthPx >= DesktopMetrics.EXPANDED_MIN_PX && usableHeight >= DesktopMetrics.MIN_CHAT_HEIGHT_PX ->
            DesktopWindowSizeClass.Expanded
        widthPx >= DesktopMetrics.MEDIUM_MIN_PX -> DesktopWindowSizeClass.Medium
        else -> DesktopWindowSizeClass.Compact
    }
}

/**
 * 输入框提示：按 UTF-8 字节数算，不是字符数。
 *
 * 中文一行 3 字节，字符数看着不到 800 的草稿经常超 UDP 限制，
 * 所以 UI 必须显示真实字节数，并说清楚下一步会自动走 TCP。
 *
 * **三态，不是两态**：[Tcp] 是「换条路」（仍然发得出去），[Over] 是「发不出去」。
 * 早先只有 `Udp`/`Tcp` 两态、并把 `overLimit` 定义成 `this == Tcp`，于是
 * 「超过 800 字节」与「超过 4096 字节」共用一个标志位 —— 发送按钮没法只对后者失效，
 * 用户按下去了才由 `RelayController` 弹一句「太长了」拦下来。
 * 手机端（`ui/components/MessageComposer.kt` 的 `ComposerByteState`）一直是三态，
 * 这里补上，两端才谈得上「同一种语义」。
 */
public enum class ComposeByteMode {
    Udp,
    Tcp,
    Over,
    ;

    /** 真的发不出去，不是「换条路」。发送按钮与红字都只看这一条。 */
    val overLimit: Boolean get() = this == Over
}

public fun composeByteMode(bytes: Int): ComposeByteMode = when {
    bytes > Protocol.TEXT_TCP_LIMIT -> ComposeByteMode.Over
    bytes <= Protocol.TEXT_UDP_LIMIT -> ComposeByteMode.Udp
    else -> ComposeByteMode.Tcp
}

// encodeToByteArray() 默认就是 UTF-8，跟 Codec.utf8ByteLength 同一口径；
// 不能写 toByteArray(Charsets.UTF_8) —— 那是 JVM 专有 API，这里是 commonMain。
public fun draftByteCount(text: String): Int = text.encodeToByteArray().size

/**
 * 输入区高度比例（0.28–0.72），拖动手柄改动后落在这里。
 *
 * 不用 split 控件：桌面只有这一个可拖分隔条，为它引一整个第三方库不值。
 */
public data class ComposerSplit(val ratio: Float) {
    init {
        require(ratio in MIN_RATIO..MAX_RATIO) { "输入区比例 $ratio 超出 $MIN_RATIO..$MAX_RATIO" }
    }

    val fraction: Float get() = ratio

    /**
     * 按可用高度算出输入区像素高度。
     *
     * 先按比例算，再依次「不超过可用高度」「不塌成 0」。
     * 注意不能写成 `coerceIn(MIN_HEIGHT_PX, availablePx)`：可用高度本来就小于
     * 最小高度时（窗口被压到极窄），上下界会反过来，`coerceIn` 直接抛
     * IllegalArgumentException——那是在拖拽/缩放过程中抛的，会打断整个输入区布局。
     */
    fun heightFor(availablePx: Int): Int {
        if (availablePx <= 0) return 0
        val raw = (availablePx * ratio).toInt()
        return raw.coerceAtMost(availablePx).coerceAtLeast(MIN_HEIGHT_PX.coerceAtMost(availablePx))
    }

    companion object {
        const val DEFAULT_RATIO = 0.42f
        const val MIN_RATIO = 0.28f
        const val MAX_RATIO = 0.72f
        const val MIN_HEIGHT_PX = 120

        /** 拖动时先夹一次再构造，避免中间态抛异常导致拖拽中断。 */
        fun clamped(ratio: Float): Float = ratio.coerceIn(MIN_RATIO, MAX_RATIO)
    }
}