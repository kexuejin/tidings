// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── 品牌色 ──────────────────────────────────────────────
val RelayPrimary = Color(0xFF2D6A4F)        // 深茶绿 - 主色
val RelayPrimaryLight = Color(0xFF40916C)   // 浅茶绿 - 亮色模式辅助
val RelayPrimaryDark = Color(0xFF1B4332)    // 更深茶绿 - 暗色模式辅助
val RelayAccent = Color(0xFFD4A373)         // 暖木色 - 强调色
val RelayAccentLight = Color(0xFFE9C46A)    // 浅暖木色

// ── 语义色 ──────────────────────────────────────────────
val RelaySuccess = Color(0xFF2E9E5B)
val RelayError = Color(0xFFE63946)
val RelayInfo = Color(0xFF457B9D)

// ── 头像占位（主题无关） ─────────────────────────────────
// 原型 `prototype/relay.html:1271-1275` `.av-ph{background:linear-gradient(140deg,
// #9DB4C9 0%,#5E86AC 48%,#3D8B6B 100%)}`（`.avup-pv` 复用同一条）。亮暗两个主题画的是
// 同一条渐变，所以不进 `colorScheme`（塞进 `Accents.kt` 只会逼出两份重复真相）。
val RelayAvatarGradientFrom = Color(0xFF9DB4C9)
val RelayAvatarGradientMid = Color(0xFF5E86AC)
val RelayAvatarGradientTo = Color(0xFF3D8B6B)
// 原先这里还有一个 RelayWarning = #E9C46A：它和上面的 RelayAccentLight 是同一个值，
// 而且没有任何消费方（把黄色当 12sp 文字只有约 1.6:1，真要用得走 secondaryContainer 那对色），
// 于是「警告色」和「浅暖木强调色」变成两份真相。删掉，别再加回来。

// ── 亮色模式 ────────────────────────────────────────────
val RelayLightBackground = Color(0xFFFAFAF8)
val RelayLightSurface = Color(0xFFFFFFFF)
val RelayLightSurfaceVariant = Color(0xFFF0EFE9)
val RelayLightOnSurface = Color(0xFF1A1C1A)
val RelayLightOnSurfaceVariant = Color(0xFF5C5F5C)
val RelayLightOutline = Color(0xFFD4D4D0)

// ── 暗色模式 ────────────────────────────────────────────
val RelayDarkBackground = Color(0xFF121413)
val RelayDarkSurface = Color(0xFF1E201F)
val RelayDarkSurfaceVariant = Color(0xFF2A2C2B)
val RelayDarkOnSurface = Color(0xFFE8E8E6)
val RelayDarkOnSurfaceVariant = Color(0xFFA0A3A0)
val RelayDarkOutline = Color(0xFF3A3C3B)

// ── 颜色方案 ────────────────────────────────────────────
val RelayLightColors = lightColorScheme(
    primary = RelayPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7E4C7),
    onPrimaryContainer = RelayPrimaryDark,
    secondary = RelayAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E6D3),
    onSecondaryContainer = Color(0xFF5C3D1E),
    tertiary = RelayInfo,
    onTertiary = Color.White,
    background = RelayLightBackground,
    onBackground = RelayLightOnSurface,
    surface = RelayLightSurface,
    onSurface = RelayLightOnSurface,
    surfaceVariant = RelayLightSurfaceVariant,
    onSurfaceVariant = RelayLightOnSurfaceVariant,
    outline = RelayLightOutline,
    // M3 的 lightColorScheme() 默认 outlineVariant 是 #CAC4D0 —— 一条紫调发丝线，
    // 落在 Tidings 这套暖中性色板上会显脏。凡是用发丝分隔线的地方都是默认吃这个值，
    // 所以必须在这里钉死，否则两态都漏回 Material 默认。
    //
    // **语义说明：outlineVariant 对应原型的 --hair，而不是 --surface-3。**
    // prototype/relay.html:51 的 --hair 是 rgba(0,0,0,.07)：57 处引用里每一处都是
    // 描边或 1px 分隔线（.topbar/.toolbar/.nav/.dnav 的 border、.bub/.filecard/
    // .screencard/.entry 的描边、.tlist li+li 的分隔线），没有一处当底色用。
    // 原先钉的 #E4E9E7 是 --hair 叠在 #FFFFFF 上的不透明近似值，离开纯白底
    // （#FAFAF8 页面、深色面）就偏；改成真的半透明合成色后，任何底色上都是 7% 黑：
    // 白底 → #EDEDED，#FAFAF8 页面 → #E9E9E7。
    // 原型的 --surface-3（浅 #E4E9E7 / 深 #2B3331）是另一个 token，12 处全是
    // 填充（开关轨道 / 计数徽标 / 搜索清除圆钮 / 图标砖 / 3px 进度轨…），
    // 它落在 MaterialTheme.accents.surface3（ui/theme/Accents.kt）。
    outlineVariant = Color(0x12000000),
    // M3 1.2 起多出来的一组「容器」角色。**不显式赋值就会回退到基线紫色**：
    // tools/check-design-palette.py 首次跑出来，桌面外壳约 11% 的像素画在
    // #F3EDF7 / #F7F2FA / #E6E0E9 上 —— 那三个正是 M3 基线的 surfaceContainer /
    // surfaceContainerLow / surfaceContainerHighest，而 DesktopStatusBar 还直接点名用了
    // colorScheme.surfaceContainer。这里按现有暖中性阶梯补全：surface #FFFFFF →
    // background #FAFAF8 → surfaceVariant #F0EFE9，中间几级插在它们之间。
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = RelayLightBackground,
    surfaceContainer = Color(0xFFF6F5F0),
    surfaceContainerHigh = Color(0xFFF3F2EC),
    surfaceContainerHighest = RelayLightSurfaceVariant,
    surfaceDim = Color(0xFFEFEEE8),
    surfaceBright = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF2A2C2B),
    inverseOnSurface = RelayLightSurfaceVariant,
    inversePrimary = Color(0xFFB7E4C7),
    // surfaceTint 与 scrim 本来是「默认就跟着 primary / 就是纯黑」，钉死只为让这两条
    // 意图写在纸上：海拔叠层的颜色是 primary 而不是 Material 的紫。
    //
    // **surfaceTint 设置为等于 surface，避免色调叠层偏差。**
    // M3 的 Surface(color = surface, tonalElevation = e) 不是「加深色」，而是把
    // surfaceTint 按 alpha = (4.5*ln(e+1)+2)/100 叠在 surface 上
    // （ColorScheme.applyTonalElevation，material3 1.4.0 字节码逐条核对过）：
    //   1dp -> 5.12%： #FFFFFF 被染成 #F4F7F6（会话行 / 工作区行的卡片底色）
    //   3dp -> 8.24%： #EEF3F1（底栏 NavigationBar）
    //   6dp -> 10.76%：#E9EFEC（弹层 / ModalBottomSheet）
    // 原型的卡片是 .card{background:var(--surface)} = 纯白 + --shadow：**亮度靠白、
    // 层次靠阴影**，全稿没有一处「海拔染色」。把 surfaceTint 钉成 primary 的后果是
    // 卡片比页面**暗且偏绿**（#F4F7F6 压在 #FAFAF8 上），方向与原型相反 —— 卡片糊进
    // 底色，这正是「界面没对比度」的机械成因（223 张参考帧里 175 张含此染色）。
    // 令 surfaceTint == surface 之后叠层是恒等变换：elevation 只留阴影、不留颜色。
    surfaceTint = RelayLightSurface,
    scrim = Color(0xFF000000),
    error = RelayError,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
)

val RelayDarkColors = darkColorScheme(
    primary = Color(0xFF74C69D),
    onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF1B4332),
    onPrimaryContainer = Color(0xFFB7E4C7),
    secondary = RelayAccentLight,
    onSecondary = Color(0xFF3D2000),
    secondaryContainer = Color(0xFF5C3D1E),
    onSecondaryContainer = Color(0xFFF5E6D3),
    tertiary = Color(0xFF89B4C8),
    onTertiary = Color(0xFF003542),
    background = RelayDarkBackground,
    onBackground = RelayDarkOnSurface,
    surface = RelayDarkSurface,
    onSurface = RelayDarkOnSurface,
    surfaceVariant = RelayDarkSurfaceVariant,
    onSurfaceVariant = RelayDarkOnSurfaceVariant,
    outline = RelayDarkOutline,
    // 深色侧同理：--hair 是 rgba(255,255,255,.09)（prototype/relay.html:83），
    // 9% 白叠在 #1E201F 上 → #323433；#2B3331 是 --surface-3 的深色值，
    // 已改由 accents.surface3 提供。
    outlineVariant = Color(0x17FFFFFF),
    // 与浅色一侧同理：这一组不写就漏回 Material 基线的紫。深色阶梯是
    // background #121413 → surface #1E201F → surfaceVariant #2A2C2B。
    surfaceContainerLowest = Color(0xFF161817),
    surfaceContainerLow = RelayDarkSurface,
    surfaceContainer = Color(0xFF222423),
    surfaceContainerHigh = Color(0xFF262827),
    surfaceContainerHighest = RelayDarkSurfaceVariant,
    surfaceDim = RelayDarkBackground,
    surfaceBright = Color(0xFF353837),
    inverseSurface = RelayDarkOnSurface,
    inverseOnSurface = RelayDarkSurfaceVariant,
    inversePrimary = RelayPrimary,
    // 与浅色同理：surfaceTint == surface 之后海拔叠层是恒等变换。
    // 深色面 #1E201F 原被 5.12% 的 #74C69D 染成 #222825 —— 同样是原型
    // .card{background:var(--surface)} 里不存在的颜色。
    surfaceTint = RelayDarkSurface,
    scrim = Color(0xFF000000),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)
