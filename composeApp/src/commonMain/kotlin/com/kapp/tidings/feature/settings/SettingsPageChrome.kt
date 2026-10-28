// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import com.kapp.tidings.platform.PlatformScrollbar
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.set_theme_dark
import com.kapp.tidings.generated.resources.set_theme_light
import com.kapp.tidings.generated.resources.set_theme_system
import com.kapp.tidings.ui.ContentWidths
import com.kapp.tidings.ui.centerCapped
import com.kapp.tidings.ui.components.AppSwitch
import com.kapp.tidings.ui.components.LargeTitleBar
import com.kapp.tidings.ui.components.TitleBarDivider
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayElevations
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.theme.SurfaceTiers

/**
 * 设置各子页共用的「标题栏 + 滚动列」外壳，以及共用的行组件。
 *
 * 为什么要有这个文件
 * ------------------------------------------
 * 这一族页面以前是一张 803 行的 `SettingsMoreScreen.kt`：原型里**五张独立页**
 * （通知 / 外观 / 能力声明 / 数据与存储 / 诊断，`prototype/relay.html` 的
 * `screenSetNotify():3873` / `screenSetAppear():3888` / `screenSetCaps():3904` /
 * `screenSetData():3920` / `screenSetDiag():3948`）被合并成一张，
 * 于是那五行台账**拿不到任何一张本屏 `@Preview`** —— 一张帧拍不出五张页，
 * 这不是"缺图"，是"没有可拍的对象"。拆成一页一个 Composable 之后，
 * 每页各得一张本屏帧，配对门禁才有东西可判。
 *
 * 顺带修掉的两处真偏差：
 *  ① 合并页对六段一律用「设置 / 通知、外观、能力、数据与诊断」这一个标题栏，
 *     而原型每页有**自己的**标题与副标题（见各页 `SettingsPageScaffold(title, subtitle)`）；
 *  ② 桌面设置窗把五个 nav 项（Notifications / Appearance / Capabilities /
 *     DataStorage / Diagnostics）全路由到同一张合并页，选「通知」会看到全部五段。
 *
 * 这里只放**几何与行样式**，不放任何一段的业务内容 —— 业务在各页文件里。
 */

/** 子页的统一外壳：自己的标题栏、自己的滚动列、与 `SettingsScreen` 同一口径的宽度上限。 */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * 横幅槽：失败原因与保存回执（`state.banner` / `settingsNote`）。
     *
     * 为什么收在外壳上：这些回执的**数据源**在 `RelayUiState` 里，而五个子页里只有
     * 连接/数据/诊断拿着 state（通知/外观/能力连 state 都没有）。宿主传一次即可；
     * 不传 = 这一屏没有横幅位（预览/截图）。
     *
     * 位置：标题栏之下、滚动内容之上 —— 横幅不该跟着内容滚走，否则用户点完保存
     * 还要往上找半天。
     */
    banner: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 页头分形态：手机端是这一页自己的标题栏（大标题 + 副标题 + 返回键，原型各自的
        // `topbar(...)`）；桌面设置窗的右栏没有标题栏，只有一行小节标题 `.grphead`
        // （原型 prototype/relay.html:5284 `<div class="grphead" style="padding-top:0">数据与存储</div>`、
        //  :5285 同形 `<div class="grphead" style="padding-top:0">桌面</div>`；
        //  桌面窗变体 prototype/relay.html:136 `.win .grphead{padding:12px 3px 5px;font-size:11px;letter-spacing:.06em}`，
        //  行内 `padding-top:0` 覆盖后实际是 `0 3px 5px`）。
        // 判别式用 onBack == null：两条生产路径里只有桌面宿主传 null
        // （desktopApp/.../DesktopHosting.kt 的五个 subpage 分支都传 null；
        //  手机侧 composeApp/.../app/relay/App.kt 六个分支都传 { settingsPage = null }）。
        // 与 SettingsProfileSubpage 的分形态是同一手法（`SettingsProfileSubpage` 里的同一手法）。
        if (onBack == null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.06.em,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 3.dp, end = 3.dp, bottom = 5.dp),
            )
        } else {
            LargeTitleBar(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                contentMaxWidth = ContentWidths.Settings,
            )
            TitleBarDivider()
        }

        if (banner != null) {
            Box(Modifier.padding(horizontal = LocalAppSpacing.current.lg)) {
                banner()
            }
        }

        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 与 SettingsScreen 同一口径（原型 `.pad.set` 的 640，:1045）。
                    // 放 scroll 之前，理由同那里。
                    .centerCapped(ContentWidths.Settings)
                    .verticalScroll(scrollState)
                    .padding(
                        start = LocalAppSpacing.current.lg,
                        top = LocalAppSpacing.current.lg14,
                        end = LocalAppSpacing.current.lg,
                        bottom = LocalAppSpacing.current.lg,
                    ),
                verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
                content = content,
            )
            PlatformScrollbar(
                state = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/** 「标题 + 副标题 + 右侧控件」一行。副标题可空（原型里有些行没有副标）。 */
@Composable
internal fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    badgeTone: TagTone = TagTone.Plain,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = LocalAppSpacing.current.sm6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (badge != null) {
                    Spacer(Modifier.width(LocalAppSpacing.current.sm))
                    TagPill(badge, badgeTone)
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
                )
            }
        }
        trailing()
    }
}

/**
 * [SettingRow] 的开关版。
 *
 * [enabled] = false 的那一行是 fd1：它必须呈现成「关着且点不动」，
 * 不能是一个能点但没用的开关。
 *
 * **整行可点**：`toggleable` 挂在整行上（role = [Role.Switch]），开关本体只画状态
 * （`onCheckedChange = null`）—— 触达目标从那颗 52×32 的轨道扩大到整行，读屏软件
 * 也把它当"一个开关"报，而不是"一行字 + 一颗开关"。
 */
@Composable
internal fun SettingSwitchRow(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    badgeTone: TagTone = TagTone.Plain,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        badge = badge,
        badgeTone = badgeTone,
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    ) {
        // 与桌面共用同一个开关实现（§2.6）：轨道/圆点/配色/命中区只有一份定义。
        // 这里传 null：交互由外层 `SettingRow` 的 toggleable 承担（整行可点，命中区更大）。
        AppSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * 三档分段控件（跟随系统 / 浅色 / 深色）。
 *
 * 没用 M3 的 `SingleChoiceSegmentedButtonRow`：那个 API 还在实验性、跨 Compose
 * Multiplatform 版本形状不稳定，而这里只是三个等宽按钮，手写 15 行更稳。
 */
@Composable
internal fun ThemeSegments(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        THEME_MODE_SYSTEM to stringResource(Res.string.set_theme_system),
        THEME_MODE_LIGHT to stringResource(Res.string.set_theme_light),
        THEME_MODE_DARK to stringResource(Res.string.set_theme_dark),
    )
    // 外层圆角取 `shapes.medium`（12）：原型 `.seg{border-radius:12px}`。
    // 原来写的 `RoundedCornerShape(10.dp)` 既不是原型值、也不是声明档，是纯手写漂移。
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(LocalAppSpacing.current.xs),
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
        ) {
            options.forEach { (mode, label) ->
                val active = mode == selected
                Surface(
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    shadowElevation = if (active) RelayElevations.Card else 0.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            // 单选语义：读屏软件按「单选按钮，已选中/未选中」报这一档，
                            // 而不是一颗无名按钮。
                            .selectable(
                                selected = active,
                                role = Role.RadioButton,
                                onClick = { onSelect(mode) },
                            )
                            .padding(vertical = LocalAppSpacing.current.sm6),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            // 未选中那一档**不写权重**：原型 `.seg button` 没声明 font-weight，
                            // 写 `FontWeight.Normal` 会凭空多出一个代码侧 400（原型继承的是 400，
                            // 但"继承来的"和"写出来的"在差集里是两回事）。`null` = 继承样式自带的档。
                            fontWeight = if (active) FontWeight.SemiBold else null,
                            color = if (active) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 胶囊标签的三种底色，对应原型 `.tag` 的三个变体（`prototype/relay.html:291-297`）：
 * - [Plain] = `.tag`（`background:var(--surface-2);color:var(--on-2)`，灰底）—— `fd1`、`7 天`；
 * - [Ok] = `.tag.ok`（`--primary-c`/`--on-primary-c`，绿底）—— `tw1`、`mrec1`；
 * - [Queued] = `.tag.q`（`--queued-c`/`--on-queued-c`，琥珀底）—— 续传残片「3 个 · 18.2 MB」。
 *
 * `--queued-c`/`--on-queued-c` 在本 App 的映射是 `secondaryContainer`/`onSecondaryContainer`
 * （与 `ChatScreen.kt` 的「已排队」徽标同一对，不是新造的颜色角色）。
 */
internal enum class TagTone { Plain, Ok, Queued }

/**
 * 胶囊标签（`tw1` / `mrec1` / `fd1` / `7 天`）。
 *
 * 圆角用 [CircleShape] 而不是 `RoundedCornerShape(50)`：原型写的是
 * `.tag{border-radius:var(--r-chip)}`＝`999px`，而量具认「999」只认 `CircleShape`
 * 这个惯用写法（第十九个洞）。`50` 在渲染上和它一样，但在差集里是一个**对不上的值**。
 * 权重按原型 `.tag{font-weight:600}` 写实。
 */
@Composable
internal fun TagPill(text: String, tone: TagTone = TagTone.Plain) {
    // 第二轮修：原来**三档都画绿底**（写死 primaryContainer），于是 `fd1` 与「7 天」
    // 这两处原型 `.tag`（灰底）被画成了 `.tag.ok` 的绿底。
    val color = when (tone) {
        // 标签的「默认」档 → --surface-2（原型 .tag 用的就是这一档，见 SurfaceTiers）。
        TagTone.Plain -> SurfaceTiers.Raised
        TagTone.Ok -> MaterialTheme.colorScheme.primaryContainer
        TagTone.Queued -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (tone) {
        TagTone.Plain -> MaterialTheme.colorScheme.onSurfaceVariant
        TagTone.Ok -> MaterialTheme.colorScheme.onPrimaryContainer
        TagTone.Queued -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = color,
        contentColor = contentColor,
        shape = CircleShape,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm6, vertical = LocalAppSpacing.current.xxs),
        )
    }
}

/** 卡下的说明小字。第二轮：原型页级 `.hint` 左右还有 `padding:0 4px`，故补 [modifier]。 */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = LocalAppSpacing.current.xs),
    )
}

/** 卡片右上角的只读标注。 */
@Composable
internal fun CardHeaderNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = LocalAppSpacing.current.xs),
    )
}

/**
 * 与 `ui/components/Common.kt` 的 [com.kapp.tidings.ui.components.InfoRow] 同形状、但值可以带颜色 /
 * 等宽 / 加重的「标签 — 值」一行（`group-text` 那行需要显示成一个状态，而不是又一条灰字）。
 * 两者的几何刻意一致，所以诊断卡里两种行看不出差别 —— 改这里就同步改 `InfoRow`，
 * 包括值那一侧的 `weight(2.5f)`：没有它，长值会把标签挤成一个字宽竖着折下去。
 *
 * 纵向内边距是 `sm`（8）：原型 `.kv{padding:9px 14px}` 是 9px，而 8 是声明档里最近的一档
 * （差 1px，已登记为档位对齐的取整）。原来写的是 `3.dp` —— 既不是原型值也不是声明档，
 * 是纯手写漂移，而且它会让差集里恒挂一条「只代码 3」。
 */
@Composable
internal fun KvRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    mono: Boolean = false,
    emphasize: Boolean = false,
    /**
     * 值列的行数上限。默认 1（单行省略，与原型 `.kv` 一致）；像「我的二维码」的载荷串
     * 这种读得懂整串才有用的值，调用方传 [Int.MAX_VALUE] 让它完整展开。
     */
    valueMaxLines: Int = 1,
    /** 行尾动作槽（如「复制」的图标按钮）。null = 没有动作，与原型 `.kv` 同形。 */
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = LocalAppSpacing.current.sm),
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontFamily = if (mono) FontFamily.Monospace else null,
            fontWeight = if (emphasize) FontWeight.SemiBold else null,
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2.5f),
        )
        if (trailing != null) {
            trailing()
        }
    }
}

/** 能力声明的三个 badge：对应原型 `.srow` 上的 `tw1` / `mrec1` / `fd1`。 */
internal const val CAP_TAG_TW1 = "tw1"
internal const val CAP_TAG_MREC1 = "mrec1"
internal const val CAP_TAG_FD1 = "fd1"

/**
 * [SettingsAppearSubpage] 的 `themeMode` 三个取值，供调用方与本地状态比较。
 *
 * **权威源在领域侧**（`app/ThemeMode.kt`）：这三个串会写进 `settings.json`、走过控制面，
 * 所以它们的格式属于可持久化事实，不属于设置页。这里只留别名，让既有的设置页代码
 * 不必改 import；**声明只有一个**，不会漂成两套。
 */
const val THEME_MODE_SYSTEM = com.kapp.tidings.domain.THEME_MODE_SYSTEM
const val THEME_MODE_LIGHT = com.kapp.tidings.domain.THEME_MODE_LIGHT
const val THEME_MODE_DARK = com.kapp.tidings.domain.THEME_MODE_DARK

/**
 * 六个设置子页的页键。
 *
 * 一级首页的五行下钻时各传自己的页键，`App.kt`（移动端）与 `DesktopHosting.kt`
 * （桌面设置窗左栏）按它选页。用字符串而不是布尔 `showSettingsMore`，
 * 是因为拆分后"进第二屏"已经是**六选一**：一个布尔表达不了"进的是哪一页"，
 * 而合并页时代那一个布尔正是"五张原型页共用一张 Composable"的同一件事。
 */
const val SETTINGS_PAGE_NOTIFY = "notify"
const val SETTINGS_PAGE_APPEAR = "appear"
const val SETTINGS_PAGE_CAPS = "caps"
const val SETTINGS_PAGE_DATA = "data"
const val SETTINGS_PAGE_DIAG = "diag"
const val SETTINGS_PAGE_UPDATE = "update"

/**
 * 「输入法自检」独立页。
 *
 * 它此前是一个**孤儿页**：`ImeSmokePane` 与桌面那套 `DesktopSettingsNav.ImeSmoke` 都写好了，
 * 但 `ImeSmokePane` 在全仓**零处调用**，`DesktopSettingsChrome` 也没有任何宿主（桌面实际渲染的是
 * 与手机共用的 `SettingsScreen`）。用户按文档「设置 → 诊断 → 输入法自检」找过去，根本到不了。
 * 现在它有了真实路由，不是一个只能读代码想象的面板。
 */
const val SETTINGS_PAGE_IME = "ime"

/**
 * 「上传头像」独立页（台账 `m-set-avatar`）。
 *
 * 它不是合并页里的第七个开关，而是**资料页头像分段控件第三档的出口**：原型
 * `avSeg()`（`prototype/relay.html:2149-2156`）把「图标头像 / 昵称首字 / 上传图片」
 * 画成一条三段等宽药丸，第三档 `onclick="avOpenUpload()"`（`prototype/relay.html:2122`
 * 逐字 `function avOpenUpload(){ go('m-set-avatar'); }`）跳到的就是这一屏。
 * 在此之前 [SetAvatarPage] 全仓没有生产调用点，是张孤儿页；这个常量就是把它接上
 * 生产的那根线（[AvatarPicker] 的 `onOpenAvatarPage` 形参为 null 时，第三档画置灰态）。
 *
 * 注意它**不在** [SettingsScreen] 的 `subpage` 二选一里：那一层是「资料 / 连接」两个
 * 内嵌子页，而这一档是整页替换，所以走 [com.kapp.tidings.App] 的 `settingsPage` 这一层。
 */
const val SETTINGS_PAGE_AVATAR = "avatar"

internal const val DEFAULT_DATA_DIRECTORY = "/data/user/0/com.kapp.tidings/files"
internal const val DEFAULT_RETENTION_DAYS = 7
