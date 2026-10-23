package com.kapp.tidings.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 卡片投影：原型 `--shadow` 的 Compose 等效。
 *
 * 原型是两层 CSS，且**两态各声明一次**：
 *
 * ```
 * 浅色 prototype/relay.html:52  --shadow: 0 1px 2px rgba(16,24,20,.05), 0 8px 24px rgba(16,24,20,.06);
 * 深色 prototype/relay.html:84  --shadow: 0 1px 2px rgba(0,0,0,.4),    0 8px 24px rgba(0,0,0,.35);
 * ```
 *
 * 性格是「宽而浅」。M3 的海拔只有一个旋钮 —— 越高越宽、也越深：`shadowElevation = 1.dp`
 * 实测最深像素 #DFDFDD（比页面 #FAFAF8 深 10.8%）且 9px 内就结束；原型最深只有 #E7E7E6
 * （深 7.6%）且跨 16px 才收回。想靠近原型得**更宽、同时更浅**，单靠海拔方向相反。
 *
 * 所以不用 `Surface(shadowElevation = …)`，改用 [Modifier.shadow] 的两个颜色参数：
 * 海拔取 8dp（对齐原型那层 `24px` 模糊的宽度），颜色用扩展 token
 * [RelayAccents.cardShadow]（Android 侧 `View#setOutlineSpotShadowColor` 的语义是
 * 「the alpha of the color is used to blend the shadow」，alpha 参与混合）。
 * 为什么 token 里放的是「实测对齐的等效值」而不是原型字面 alpha，见该字段的 KDoc。
 *
 * 实测（`ChatHomeRecentPhonePreview`，x=300 直边、避开 16dp 圆角，scale 2.626）：
 * 卡片下缘起最深 #E5E6E4（229），21px 的卡片间距内渐回 #ECEDEB；原型同位置是 #E7E7E6（231）
 * 渐回 #F1F1EF —— 深浅与「深核浅边」的走向都对上了。改前（M3 1dp）是 #DFDFDD 的硬边。
 *
 * 已知边界：
 * - 深色档仍弱于原型：原型深色那两层（黑 0.4 + 0.35）压在 `#121413` 上实测到 #090A0A~#0E0F0E，
 *   而 Compose 单层 8dp 即使 alpha 拉满也只到 #101110 上下（同一 alpha 下 Compose 投影比 CSS
 *   弱约一个数量级，一层也叠不出 CSS 的两层）。这是平台差异，已记入台账。
 * - **用法**：页面级白卡一律 `modifier = …relayCardShadow(shape = <与 Card 的 shape 逐字相同>)`
 *   **并把 `elevation` 归零** —— 否则 M3 的 1dp 平台阴影会叠在自绘阴影上；`clip = false` 下
 *   shape 不一致还会在圆角外露一圈更宽的阴影。原型 `.card` 原子自带 `box-shadow:var(--shadow)`
 *   （`prototype/relay.html:119`），所以判据是「这个元素在原型的原型里是不是 `.card`/`.row`/`.entry`」。
 *   已切：`ui/SectionCard.kt`、`ui/components/WorkspaceUnitCard.kt`、`feature/settings/SettingsProfileSubpage.kt`、
 *   `feature/settings/SettingsWidgets.kt`、`feature/settings/SetAvatarPage.kt`、`feature/cabinet/CabinetScreen.kt`（5 张）、
 *   `ui/desktop/CabinetPane.kt`（未配置卡）、`feature/agents/AgentApprovalCards.kt`（4 张）、
 *   `feature/agents/AgentWorkspaceBoard.kt`（空态卡）。
 * - **刻意不切**（原型里不是 `.card`，或本来就不是白卡）：`ui/desktop/DesktopComposer.kt` 的输入条与表情面板
 *   （浮层，走 `RelayElevations.Floating` 6dp）、`ui/desktop/CabinetPane.kt` 的选择卡、`feature/cabinet/CabinetScreen.kt`
 *   的对端卡、`feature/settings/SettingsHomeScreen.kt` 的资料卡（`surfaceVariant` 底）、
 *   `feature/agents/AgentWorkspaceBoard.kt` 的审批横幅（`errorContainer` 底）。
 *   逐条记在 `design/fidelity-deviations.yaml`。
 */
@Composable
fun Modifier.relayCardShadow(shape: Shape = LocalCardShape.current): Modifier =
    shadow(
        elevation = 8.dp,
        shape = shape,
        clip = false,
        ambientColor = MaterialTheme.accents.cardShadow,
        spotColor = MaterialTheme.accents.cardShadow,
    )
