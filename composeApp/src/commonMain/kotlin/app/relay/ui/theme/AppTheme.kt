// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

// ── 自定义排版 ────────────────────────────────────────────
val RelayTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

// ── 自定义形状 ────────────────────────────────────────────
val RelayShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/**
 * 卡片圆角 —— 随**窗口上下文**变，不随调用点变。
 *
 * 原型里同一个 `--r-card` 变量有两套值，切换点是外壳而不是卡片自己：
 * - 手机 / 平板（`.app` 系，`prototype/relay.html:58`）`--r-card:16px`
 * - 桌面窗（`.win` 覆盖，`prototype/relay.html:584`）`--r-card:12px`
 *
 * 为什么必须是 CompositionLocal 而不是给 `SectionCard` 加形参：**桌面会复用手机屏**。
 * `DesktopNodesPane` 与壳里的工作区视图把 `NodesScreen` / `AgentConsoleScreen`
 * 直接塞进桌面窗 ⇒ 按调用点传参只能改到「恰好知道自己是桌面」
 * 的那几处，剩下约 20 处会在桌面窗里画成 16dp。按上下文取则一次到位。
 *
 * 先例：`ui.theme` 里的 `LocalAppSpacing` 就是同一个套路。
 *
 * 默认 = `RelayShapes.large`(16dp) = 手机/平板档，与原型 `.app` 一致；
 * 桌面壳在 `DesktopWindow.kt` / `DesktopPeerWindow.kt` / `DesktopShell`（桌面 chrome 档）
 * 里覆盖成 `RelayShapes.medium`(12dp)。
 */
val LocalCardShape = staticCompositionLocalOf<Shape> { RelayShapes.large }

// ── 自定义高度（阴影层级）────────────────────────────────────
// 设计规格 `design-spec.json` 的 `tokens.elevation` 只有三档：card / sheet / floating。
// 之前这些数字散落在各屏里手写（1 / 2 / 4 dp 都有），于是"卡片是 1dp"这件事没有任何地方
// 是权威的 —— 改一次视觉就得全仓库找 1.dp。收敛成三个具名常量后：
//   - [Card]   卡片、列表行、区块容器
//   - [Sheet]  底部弹层与浮在内容之上的底部栏
//   - [Floating] 输入栏这类始终压住内容、自身还带投影的浮层
// 三档值与 spec 一致：1 / 3 / 6 dp。
object RelayElevations {
    val Card: Dp = 1.dp
    val Sheet: Dp = 3.dp
    val Floating: Dp = 6.dp
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    spacing: AppSpacing = AppSpacing(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalAppSpacing provides spacing) {
        MaterialTheme(
            colorScheme = if (darkTheme) RelayDarkColors else RelayLightColors,
            typography = RelayTypography,
            shapes = RelayShapes,
        ) {
            // 扩展 token（M3 ColorScheme 里没有的键）跟 colorScheme 用同一个开关，
            // 这样「同一套 token 支持夜间模式」在代码里是一处判断而不是两处。
            ProvideRelayAccents(darkTheme = darkTheme) { content() }
        }
    }
}

/**
 * 「跟随系统 / 浅色 / 深色」三选一。
 *
 * 单独一个枚举而不是 `darkTheme: Boolean`，是因为布尔量表达不了「不表态」：
 * 要么跟随系统、要么强制某一档，两个页签设置（外观、状态栏图标亮度）都要求
 * 第三档存在。真收到不认识的值一律按 [System] 走 —— 设置项被改坏时退回系统
 * 主题，比停在某个半截状态安全。
 */
enum class ThemeMode {
    System,
    Light,
    Dark;

    /** 把枚举压成设置屏用的字符串（wire 形态，由设置屏的 THEME_MODE_* 常量定义）。 */
    val wire: String
        get() = when (this) {
            System -> app.relay.feature.settings.THEME_MODE_SYSTEM
            Light -> app.relay.feature.settings.THEME_MODE_LIGHT
            Dark -> app.relay.feature.settings.THEME_MODE_DARK
        }

    /** [systemDark] 是系统的深色开关；只有 [System] 会去读它。 */
    fun resolve(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        fun fromWire(value: String): ThemeMode = when (value) {
            app.relay.feature.settings.THEME_MODE_LIGHT -> Light
            app.relay.feature.settings.THEME_MODE_DARK -> Dark
            else -> System
        }
    }
}
