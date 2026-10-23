// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.graphics.Shape
import com.kapp.tidings.ui.theme.ShapeRoles

/**
 * 按钮族 —— **唯一决定按钮字号、内边距、圆角与配色的地方**。
 *
 * ## 为什么要有这个文件
 *
 * 在它之前，本仓的按钮是散在各处的 Material3 原语：167 个调用点分布在 52 个文件里，
 * 其中 675 处自己写 `style = MaterialTheme.typography.*`、44 处自己写 `contentPadding`。
 * 于是「统一按钮字号」这件事**没有落点** —— 要么改 167 处，要么先抽出组件再改 1 处。
 * 这就是"细节优化"长期做不动的原因：不是数字没调好，是没有东西可调。
 *
 * ## 尺寸是**照原型量出来的**，不是选出来的
 *
 * 每一档都能追到 `prototype/relay.html` 的声明，而且**恰好落在既有 token 档上**：
 *
 * | 项 | 原型 | 本文件绑定 |
 * |---|---|---|
 * | 标准档内边距 | `--pad-btn: 9px 14px` | `spacing.osm9` × `spacing.lg14` |
 * | 标准档字号 | `--fs-btn: 14px` | `typography.labelLarge`（14sp / weight 500） |
 * | 小档内边距 | `.btn.sm{padding:6px 10px}` | `spacing.sm6` × `spacing.md10` |
 * | 小档字号 | `.btn.sm{font-size:12px}` | `typography.labelMedium`（12sp） |
 * | 圆角 | `border-radius: var(--r-ctl)` = 12px | `shape.medium`（= 12dp） |
 * | 图标 | `.btn svg{width:16px}` | `iconSizes.label`（16dp，语义即"按钮/横幅/工具栏"） |
 * | 图文间距（标准档） | `gap:6px` | `spacing.sm6` |
 *
 * ### 原型里**有、但本版刻意没建模**的四处
 *
 * 写清楚比省略好：省略会让人以为"已经全对上了"。
 *
 * | 原型 | 值 | 本版处置 | 理由 |
 * |---|---|---|---|
 * | `.btn.sm{gap:5px}` | 小档图文间距 5px | 两档都用 `spacing.sm6`(6dp) | 一个 1px 的档位差换一个新 spacing 档不划算；`sm6` 是最近的既有档 |
 * | `.win{--fs-btn:12px;--pad-btn:7px 12px}` | **桌面窗**标准档 12px / 7×12 | 未建模 | 桌面与本组件共用同一 API 时要不要分档，取决于 W3 的台面/手机分档决策，现在决定会返工 |
 * | `.win .btn.sm{padding:5px 9px;font-size:11px;border-radius:9px}` | 桌面小档 | 未建模 | 同上；且 9px 圆角在 `shape` 档外（档外半径已有 9 档欠债） |
 * | `.ibtn{width:34px;height:34px}` | 图标按钮 34×34 | [AppIconButton] 用 48dp | **刻意偏离**：34dp 低于 48dp 触控目标下限。宁可偏离原型也不做不可点的按钮 |
 *
 * 另外 `font-weight`：原型 `.btn` 是 500、`.btn.pri` 是 600，而 `labelLarge`/`labelMedium`
 * 的 weight 正是 medium(500) —— 主按钮的 600 没跟：本仓的字阶每个字号只有一档 weight，
 * **不为了一颗按钮新增一档字重**（那会让字阶层失去"一个字号一个档"的收敛）。
 *
 * ### 当前采用情况（诚实记一笔）
 *
 * 四个变体里**只有 [AppOutlinedButton] 有调用点**（迁移样板那一屏）。其余三个与
 * [PressableSurface] 目前是"先建好、待迁移" —— 记分板 `ui.migrated_button_calls` 与
 * `ui.press_feedback_adopted` 如实报 4 与 0，**不要用那两个数字给 J-0 签收**。
 *
 * ## token 一律写**规范形式**，不要起别名
 *
 * 本文件刻意不写 `val scheme = MaterialTheme.colorScheme` 再 `scheme.primary`。
 * 原因不是风格：`:designSpecDrift` 的 token 证据扫描按
 * `colorScheme\.(\w+)` / `typography\.(\w+)` / `(?:LocalAppSpacing\.current|\.spacing)\.(\w+)` /
 * `shapes\.(\w+)` 这几个正则认证据行。**起别名会让这个文件"看起来没用 token"**，
 * 于是 `componentSpecs` 里声明的 `requiredTokens` 全部被判"没有证据行"。
 * 写法上的啰嗦换来的是"哪一行用了哪个 token"可以机械回答。
 *
 * ## 色调
 *
 * [AppButtonTone.Primary] 实心主色、[AppButtonTone.Neutral] 描边中性、
 * [AppButtonTone.Danger] 危险。三者对应原型的 `.pri` / `.out` / `.danger-o`。
 *
 * ## 不做什么
 *
 * 不暴露 `contentPadding` / `style` / `shape` 参数。**那正是它要消灭的自由度** ——
 * 一旦留了口子，167 处会原样长回来。确有特殊需求时，先在这里加一档，别在调用点写覆盖。
 */
enum class AppButtonTone {
    /** 实心主色（原型 `.pri`）：一行里的主行动。 */
    Primary,

    /** 描边中性（原型 `.out`）：次要行动。 */
    Neutral,

    /** 危险（原型 `.danger-o`）：删除、清空、移出。 */
    Danger,
}

/** 按钮尺寸档。**只有两档**，各自绑定一个字阶与一组内边距。 */
enum class AppButtonSize {
    /** 标准档：原型 `--pad-btn: 9px 14px` / `--fs-btn: 14px`。 */
    Medium,

    /** 小档：原型 `.btn.sm{padding:6px 10px;font-size:12px}`。对话框动作、行内动作。 */
    Small,

    /**
     * 紧凑档：原型 **桌面**的 `.win .btn.sm{padding:5px 9px;font-size:11px;border-radius:9px}`
     * （`prototype/relay.html` 的窗口覆盖段）。
     *
     * 为什么它和 [Small] 是两档：手机与桌面的 `.btn.sm` **本来就是两个值** ——
     * 手机 6×10 / 12sp / 圆角 12，桌面 9×5 / 11sp / 圆角 9。以前只有 [Small]（手机那套），
     * 于是桌面上那一批小按钮迁不过来（换了会同时改内边距、字号和圆角）。
     */
    Compact,
}

/** 该尺寸档对应的字阶。读的是组合局部，所以必须 `@Composable`。 */
@Composable
private fun AppButtonSize.textStyle(): TextStyle = when (this) {
    AppButtonSize.Medium -> MaterialTheme.typography.labelLarge
    AppButtonSize.Small -> MaterialTheme.typography.labelMedium
    AppButtonSize.Compact -> MaterialTheme.typography.labelSmall
}

/** 该尺寸档对应的内容内边距（值来自原型，见文件头表格）。 */
@Composable
private fun AppButtonSize.contentPadding(): PaddingValues = when (this) {
    AppButtonSize.Medium -> PaddingValues(
        horizontal = LocalAppSpacing.current.lg14,
        vertical = LocalAppSpacing.current.osm9,
    )

    AppButtonSize.Small -> PaddingValues(
        horizontal = LocalAppSpacing.current.md10,
        vertical = LocalAppSpacing.current.sm6,
    )

    AppButtonSize.Compact -> PaddingValues(
        horizontal = LocalAppSpacing.current.osm9,
        vertical = LocalAppSpacing.current.oxs5,
    )
}

/** 该尺寸档对应的圆角：紧凑档是桌面 `.win .btn.sm{border-radius:9px}`（= [ShapeRoles.Avatar]）。 */
@Composable
private fun AppButtonSize.buttonShape(): Shape = when (this) {
    AppButtonSize.Compact -> ShapeRoles.Avatar
    else -> MaterialTheme.shapes.medium
}

/**
 * 按钮内容：可选前置图标 + 文案。
 *
 * 图标固定 [IconSizes.Label]（16dp）与 `spacing.sm6` 间距 —— 与原型
 * `.btn svg{width:16px;height:16px}` / `gap:6px` 一致。
 */
@Composable
private fun AppButtonContent(
    text: String,
    size: AppButtonSize,
    leadingIcon: DrawableResource?,
    iconTint: Color,
) {
    if (leadingIcon == null) {
        Text(text = text, style = size.textStyle())
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
    ) {
        Icon(
            painter = painterResource(leadingIcon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(IconSizes.Label),
        )
        Text(text = text, style = size.textStyle())
    }
}

/** 实心按钮。 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: AppButtonSize = AppButtonSize.Medium,
    tone: AppButtonTone = AppButtonTone.Primary,
    leadingIcon: DrawableResource? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = size.buttonShape(),
        colors = when (tone) {
            AppButtonTone.Danger -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )

            else -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        contentPadding = size.contentPadding(),
    ) {
        AppButtonContent(text, size, leadingIcon, Color.Unspecified)
    }
}

/** 描边按钮。 */
@Composable
fun AppOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: AppButtonSize = AppButtonSize.Medium,
    tone: AppButtonTone = AppButtonTone.Neutral,
    leadingIcon: DrawableResource? = null,
) {
    val contentColor = when (tone) {
        AppButtonTone.Danger -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = size.buttonShape(),
        colors = ButtonDefaults.outlinedButtonColors(
            // Danger 用**透明底**（原型 `.danger-o{background:transparent}`），不是 errorContainer 粉底。
            //
            // 为什么改掉仓库现状：填充色会被读成"被强调的主行动"，而破坏性动作通常恰恰是
            // 次要行动（"清空"旁边的真正主行动是"取消"）。粉底让它和主按钮抢注意力，
            // 也会让 Danger 在视觉层级上高于 Primary —— 语义反了。
            // 强调该由**确认框**承担（那里才是不可逆动作真正发生的地方）。
            containerColor = Color.Transparent,
            contentColor = contentColor,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (tone == AppButtonTone.Danger) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
        contentPadding = size.contentPadding(),
    ) {
        AppButtonContent(text, size, leadingIcon, contentColor)
    }
}

/** 纯文字按钮（无边框、无底色）。 */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: AppButtonSize = AppButtonSize.Medium,
    tone: AppButtonTone = AppButtonTone.Neutral,
    leadingIcon: DrawableResource? = null,
) {
    val contentColor = when (tone) {
        AppButtonTone.Danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = size.buttonShape(),
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        contentPadding = size.contentPadding(),
    ) {
        AppButtonContent(text, size, leadingIcon, contentColor)
    }
}

/**
 * 图标按钮。
 *
 * 触控目标**默认** `spacing.xxxl`（48dp，无障碍下限），图标本身 [IconSizes.Label]。
 * 与文字按钮不同，它没有内边距档 —— 图标按钮的"档"就是触控目标大小。
 *
 * ## 为什么是 `defaultMinSize` 而不是 `size`
 *
 * 第一版写的是 `Modifier.size(48dp)`，而 `size` 会把 min 与 max **都**钉死，
 * 于是调用方再传 `Modifier.size(28.dp)` 也压不过 —— 结果全仓 8 处 28/36dp 的行内
 * 图标按钮**一颗都迁不动**（独立验证者实测）。`AppIconButton` 因此长期 0 调用点。
 *
 * `defaultMinSize` 只给下限：默认 48dp，调用方**显式**传更小的尺寸时以调用方为准。
 * 这样"无障碍下限"是默认值而不是铁律 —— 行内密排的场景能自己决定，
 * 而且那个决定是**写出来的**（能 review），不是被组件悄悄挡掉的。
 *
 * 复用 spacing 的 xxxl 而不是写 48.dp：同一个 48 在两个 token 组里都有意义
 * （间距上限 / 触控目标），写两处就多一处会漂移的真相。
 */
@Composable
fun AppIconButton(
    icon: DrawableResource,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: AppButtonTone = AppButtonTone.Neutral,
) {
    val tint = when (tone) {
        AppButtonTone.Danger -> MaterialTheme.colorScheme.error
        AppButtonTone.Primary -> MaterialTheme.colorScheme.primary
        AppButtonTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 走 [PressableSurface] 而不是 M3 的 IconButton：图标按钮是"按下去要形变"的典型
    // （没有文字可以变色，也没有边距可以挪），而且这样 [PressableSurface] 就有了一个
    // **真实使用者** —— 一个建好却零调用点的原语，等于把死代码伪装成基础设施。
    PressableSurface(
        onClick = onClick,
        // `size` 而不是 `defaultMinSize`：内层是 `Box(Modifier.fillMaxSize())`，而
        // `defaultMinSize` **不限制上限** —— 父容器高度可伸缩时（Column 里没有 weight 的槽、
        // 或整屏 Column），`fillMaxSize` 会把按钮撑到整列可用高度，其余内容被挤成 0，
        // 视觉上表现为"整屏空白 + 一颗居中的图标"。
        //
        // 真身：扫码屏与全局搜索屏换成 AppIconButton 后，三张状态参考图**塌成同一张空白**，
        // 被 `:designDupFrameGate` 抓出来。`size` 同时给上下限，内层只填这 48dp；
        // 调用方传自己的 `size(...)` 时外层约束先生效，仍然覆盖得住。
        modifier = modifier.size(LocalAppSpacing.current.xxxl),
        enabled = enabled,
        shape = CircleShape,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(IconSizes.Label),
            )
        }
    }
}
