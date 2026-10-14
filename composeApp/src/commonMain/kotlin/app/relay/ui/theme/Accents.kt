// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Material3 的 `ColorScheme` 不含的几个品牌 token。
 *
 * 这里不放新色板，只放「设计规范里有、M3 里没有对应槽」的键：Agent 强调色（图形用）、
 * Agent 字母标签的文字色、计数徽标的底/字、在线状态点、原型的 `--surface-3` 填充色、
 * 卡片投影色（原型 `--shadow`，两态声明不同值）。
 * 它们以前是 `ui/components` 里两个顶层 `val`，只有一个浅色值，于是深色模式直接复用
 * 浅色值 —— Agent 头像在深色底上是一块刺眼亮白。
 *
 * **刻意不收进来的两类 token**（收进来就是"两套真相"）：
 *
 * - 发丝分隔线 → **留在** `RelayLightColors.outlineVariant` / `RelayDarkColors.outlineVariant`
 *   （对应原型 `--hair` 的真值：浅 `rgba(0,0,0,.07)` / 深 `rgba(255,255,255,.09)`，
 *   不再是只对纯白底成立的那个不透明近似值）。M3 默认值是紫调的（浅 `#CAC4D0` /
 *   深 `#49454F`），组件里到处直接写 `HorizontalDivider()` 或 `colorScheme.outlineVariant`，
 *   再挂一份 accents 只会有人用错那一份。
 * - 原型 `--surface-3` **收进来了**（[RelayAccents.surface3]）：它是**填充**色（开关轨道 /
 *   计数徽标 / 搜索清除圆钮 / 图标砖 / 3px 进度轨，全稿 12 处没有一处是描边），与做描边的
 *   `--hair` 是两个 token，历史上被当成同一个用错。M3 没有可借的槽：语义最近的
 *   `surfaceContainerHighest` 已经被桌面外壳的「第三级容器」占用
 *   （`DesktopChrome.kt:643`、`DesktopConversationWidgets.kt:96`、`DesktopComposer.kt:423`、
 *   `DesktopStatusBar.kt:187`），改它会连带挪走那些面的底色。
 * - 排队色 → 原型的 `--queued-c` / `--on-queued-c` 恰好就是 Material 的
 *   `secondaryContainer` / `onSecondaryContainer`（浅 `#F5E6D3`/`#5C3D1E`，深 `#5C3D1E`/`#F5E6D3`），
 *   所以从 `colorScheme` 取即可。剩下的黄底 `--queued`（浅 `#E9C46A` / 深 `#E3B75A`）**不对应**
 *   任何现成槽：浅色 `secondary` 是 `#D4A373`、深色 `secondary` 是 `#E9C46A`，两边都不相等。
 *   真要上排队徽标，得先把这两个槽的值定下来，而不是在 accents 里挂一个各说各话的。
 *   当文字用的是 `--on-queued-c`，不是 `--queued`：后者浅色下当小字只有约 1.6:1。
 *
 * 为什么用 CompositionLocal 而不是扩展 `ColorScheme`：跨端同一份代码里 Material3 版本
 * 与 API 稳定，构造带额外参数的 `ColorScheme` 要等 Compose 1.9+ 的实验性构造器，不值得赌。
 *
 * **值与深色的来源**：浅色取 `ui/theme/Color.kt` 与设计规范；深色取自设计原型
 * `prototype/relay.html:65` 的 `html[data-theme="dark"]` 变量块（`--agent`
 * `#B9A6FF`、`--agent-c` `#312A52`、`--online` `#6FD69A`）。浅色值**一个字节都没改**，
 * 所以浅色参考图不受这两个 token 的影响。
 *
 * **为什么 Agent 字母和计数徽标各要一对专门的色**：两处都是「小字压在色块上」，
 * 借现成的强调色/错误色当底色都差一点。
 * - Agent 字母：`--agent`（#6B4EE6）压在 `--agent-c`（#E6E0FB）上只有 4.26:1，
 *   只够大字（3:1）；11sp 的 KindBadge 必须再深一档 → 原型为此单列 `--agent-t`。
 *   纯图形（PeerAvatar 里的 agent 图标）只需 3:1，仍用 [agentAccent]，不跟着换。
 * - 计数徽标：M3 `Badge` 默认取 `error` / `onError`（#E63946 配白字）只有 4.17:1，
 *   原型为此单列 `--badge` / `--on-badge`（浅色 5.5:1；深色下 `--error` 本身是浅粉、
 *   白字只剩 1.7:1，所以整对翻成 #FFB4AB / #5C0011）。
 */
@Immutable
data class RelayAccents(
    /** Agent 节点在界面上的强调色，对应规范 `tokens.colors.agentAccent`。 */
    val agentAccent: Color,
    /** Agent 头像/标签的容器底色，对应规范 `tokens.colors.agentAccentContainer`。 */
    val agentAccentContainer: Color,
    /** 在线状态点。深色下必须换值，否则深绿落在深底上几乎看不见。 */
    val online: Color,
    /**
     * 原型 `--surface-3`：**填充**底色，浅 `#E4E9E7` / 深 `#2B3331`。
     *
     * 与做描边的 `--hair`（在 `colorScheme.outlineVariant`）是两个 token，别把它当
     * 分隔线用：全稿 12 处引用都是图标砖 / 开关轨道 / 徽标这类小面积底色。
     */
    val surface3: Color,
    /**
     * 原型 `--agent-t`：Agent **字母标签的文字**色，浅 `#5333C9` / 深 `#C9BCFF`。
     *
     * 与 [agentAccent] 分工：那个是图形色（只要 3:1），这个是 11sp 小字色（要 4.5:1）。
     * 浅色对 [agentAccentContainer] 6.1:1、深色 7.6:1。别拿 [agentAccent] 当文字色。
     */
    val agentAccentText: Color,
    /** 原型 `--badge`：计数徽标的**底**色，浅 `#C1341F` / 深 `#FFB4AB`。 */
    val badge: Color,
    /** 原型 `--on-badge`：计数徽标文字色，与 [badge] 成对使用（浅白 / 深 #5C0011）。 */
    val onBadge: Color,
    /**
     * 卡片投影色，给 `Modifier.relayCardShadow`（`ui/theme/CardShadow.kt`）当 `spot/ambient` 用。
     *
     * 原型声明了两档：浅色 `rgba(16,24,20,.05) + rgba(16,24,20,.06)`（`prototype/relay.html:52`），
     * 深色 `rgba(0,0,0,.4) + rgba(0,0,0,.35)`（:84）。Compose 的 `Modifier.shadow` 只有**一层**，
     * 且同一 alpha 下比 CSS `box-shadow` 弱约一个数量级（浅色实测：alpha 0.65 才把卡下缘压到
     * 原型那一档），所以这里放的是**按实测对齐的等效值**，不是原型字面值：
     * 浅色 `#101814` @0.65、深色纯黑 @1.0。
     *
     * 深色必须换值：浅色那个 `rgba(16,24,20)` 压在深色页面 `#121413` 上几乎同色，投影直接消失。
     */
    val cardShadow: Color,
)

val RelayLightAccents = RelayAccents(
    agentAccent = Color(0xFF6B4EE6),
    agentAccentContainer = Color(0xFFE6E0FB),
    online = RelaySuccess,
    surface3 = Color(0xFFE4E9E7),
    agentAccentText = Color(0xFF5333C9),
    badge = Color(0xFFC1341F),
    onBadge = Color.White,
    cardShadow = Color(0xA6101814),
)

val RelayDarkAccents = RelayAccents(
    agentAccent = Color(0xFFB9A6FF),
    agentAccentContainer = Color(0xFF312A52),
    online = Color(0xFF6FD69A),
    surface3 = Color(0xFF2B3331),
    agentAccentText = Color(0xFFC9BCFF),
    badge = Color(0xFFFFB4AB),
    onBadge = Color(0xFF5C0011),
    cardShadow = Color(0xFF000000),
)

private val LocalRelayAccents: ProvidableCompositionLocal<RelayAccents> =
    staticCompositionLocalOf { RelayLightAccents }

/**
 * 读扩展 token。
 *
 * 用法与 `MaterialTheme.colorScheme` 同形，组件里不需要知道 token 存放在哪：
 * `MaterialTheme.accents.agentAccent`。
 *
 * 默认值是浅色一套而不是抛异常：漏包 `AppTheme` 的 Preview 会渲染成浅色（一眼能看出来），
 * 比崩掉更容易修。
 */
val MaterialTheme.accents: RelayAccents
    @Composable
    @ReadOnlyComposable
    get() = LocalRelayAccents.current

/** 与 [AppTheme] 配对使用：一次 provider 管住两态。 */
@Composable
internal fun ProvideRelayAccents(darkTheme: Boolean, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalRelayAccents provides if (darkTheme) RelayDarkAccents else RelayLightAccents,
        content = content,
    )
}