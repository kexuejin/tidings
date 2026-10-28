// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.kapp.tidings.platform.PlatformScrollbar
import com.kapp.tidings.ui.components.PressableSurface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.kapp.tidings.core.protocol.DEFAULT_TIMINGS
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.ic_bell
import com.kapp.tidings.generated.resources.ic_clock_pending
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.ic_info
import com.kapp.tidings.generated.resources.ic_key
import com.kapp.tidings.generated.resources.ic_link
import com.kapp.tidings.generated.resources.ic_moon
import com.kapp.tidings.generated.resources.ic_shield
import com.kapp.tidings.generated.resources.ic_window
import com.kapp.tidings.generated.resources.desk_autostart_error_title
import com.kapp.tidings.generated.resources.desk_nav_appearance
import com.kapp.tidings.generated.resources.desk_nav_capabilities
import com.kapp.tidings.generated.resources.desk_nav_connection
import com.kapp.tidings.generated.resources.desk_nav_trusted_devices
import com.kapp.tidings.generated.resources.desk_nav_data
import com.kapp.tidings.generated.resources.desk_nav_desktop
import com.kapp.tidings.generated.resources.desk_nav_desktop_only
import com.kapp.tidings.generated.resources.desk_nav_diagnostics
import com.kapp.tidings.generated.resources.desk_nav_group_appear
import com.kapp.tidings.generated.resources.desk_nav_group_conn
import com.kapp.tidings.generated.resources.desk_nav_group_data
import com.kapp.tidings.generated.resources.desk_nav_group_desk
import com.kapp.tidings.generated.resources.desk_nav_notifications
import com.kapp.tidings.generated.resources.desk_nav_transfers
import com.kapp.tidings.generated.resources.desk_prefs_autostart_subtitle
import com.kapp.tidings.generated.resources.desk_prefs_autostart_title
import com.kapp.tidings.generated.resources.desk_prefs_autostart_unsupported
import com.kapp.tidings.generated.resources.desk_prefs_hint
import com.kapp.tidings.generated.resources.desk_prefs_power_subtitle
import com.kapp.tidings.generated.resources.desk_prefs_power_title
import com.kapp.tidings.generated.resources.desk_retry
import com.kapp.tidings.generated.resources.desk_settings_title
import com.kapp.tidings.generated.resources.desk_switch_unavailable
import com.kapp.tidings.generated.resources.ic_refresh
import com.kapp.tidings.generated.resources.set_profile_title
import com.kapp.tidings.generated.resources.set_update_title
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.LocalCardShape
import com.kapp.tidings.ui.theme.RelayElevations
import com.kapp.tidings.ui.theme.RelayShapes
import com.kapp.tidings.ui.components.AppSwitch
import com.kapp.tidings.ui.theme.accents
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.AppButtonSize

/**
 * 桌面设置窗里「桌面」档（[DesktopPrefsPanel]）需要的宿主能力。
 *
 * 这一层接口是为了**把设置窗从 `:desktopApp` 的 `DesktopHost` 上解耦**而抽出来的：
 * `DesktopHost` 是 `:desktopApp` 私有的（它持有 AWT 托盘、登录项、协程作用域等），
 * 而截图门禁只能渲染 `androidApp`（它只 `implementation(project(":composeApp"))`，
 * 依赖不到 `:desktopApp`）。没有这层接口，设置窗就永远出不了参考帧 —— 这正是
 * `d-settings` / `d-settings-desk` 两行在台账里卡了多轮的「模块边界」根因。
 *
 * 生产路径上 `DesktopHost`（`desktopApp` 的 `DesktopWindow.kt`）实现本接口，
 * 设置窗的 `:desktopApp` 调用点仍然传它；预览路径上则传一个纯内存假实现
 * （见 `androidApp` 的 `ScreenshotPreviewsDesktop.kt`）。
 */
public interface DesktopSettingsHost {
    val powerSaver: Boolean
    val launchAtLogin: Boolean
    val launchAtLoginSupported: Boolean
    val launchAtLoginError: String?
    fun applyPowerSaver(enabled: Boolean)
    fun applyLaunchAtLogin(enabled: Boolean)
}

// 分组标识：稳定、不进 UI，只用来把相邻条目归到同一个 `.dsg` 组头下。
// 组头文本是 [DesktopSettingsNav.groupLabel]（资源），原稿 `DSET_GROUPS` 的第一组
// `g:''` 没有组头，所以 Identity 的 `groupLabel` 是 null。
private const val GROUP_ROOT = ""
private const val GROUP_CONN = "conn"
private const val GROUP_APPEAR = "appear"
private const val GROUP_DATA = "data"
private const val GROUP_DESK = "desk"

/**
 * 桌面端设置导航项，与 prototype/relay.html 的 DSET_GROUPS 对齐。
 *
 * [icon] 对应原型 `DSET_GROUPS[].entries[].icon`，渲染成 `.li .fi2` 那个 26×26 的圆角图标块。
 */
public enum class DesktopSettingsNav(
    val id: String,
    val title: StringResource,
    val group: String,
    val groupLabel: StringResource?,
    val icon: DrawableResource,
    val note: StringResource? = null,
) {
    Identity("id", Res.string.set_profile_title, GROUP_ROOT, null, Res.drawable.ic_key),
    TrustedDevices(
        "trusted", Res.string.desk_nav_trusted_devices, GROUP_CONN,
        Res.string.desk_nav_group_conn, Res.drawable.ic_shield,
    ),
    Connection(
        "conn", Res.string.desk_nav_connection, GROUP_CONN,
        Res.string.desk_nav_group_conn, Res.drawable.ic_link,
    ),
    Notifications(
        "notify", Res.string.desk_nav_notifications, GROUP_CONN,
        Res.string.desk_nav_group_conn, Res.drawable.ic_bell,
    ),
    Appearance(
        "appear", Res.string.desk_nav_appearance, GROUP_APPEAR,
        Res.string.desk_nav_group_appear, Res.drawable.ic_moon,
    ),
    Capabilities(
        "caps", Res.string.desk_nav_capabilities, GROUP_APPEAR,
        Res.string.desk_nav_group_appear, Res.drawable.ic_shield,
    ),
    DataStorage(
        "data", Res.string.desk_nav_data, GROUP_DATA,
        Res.string.desk_nav_group_data, Res.drawable.ic_folder,
    ),
    Transfers(
        "transfers", Res.string.desk_nav_transfers, GROUP_DATA,
        Res.string.desk_nav_group_data, Res.drawable.ic_clock_pending,
    ),
    Diagnostics(
        "diag", Res.string.desk_nav_diagnostics, GROUP_DATA,
        Res.string.desk_nav_group_data, Res.drawable.ic_info,
    ),
    // 「版本与更新」：手机在设置首页「数据与诊断」组里有这一行，桌面此前**完全没有入口**
    // （桌面用户拿不到自己装的版本号，也没有手动检查更新这条路）。原型桌面侧栏没有这一项，
    // 这是**有意偏离**：它属于"代码自有页"（手机那页也没有原型），版本信息是排障刚需。
    Update(
        "update", Res.string.set_update_title, GROUP_DATA,
        Res.string.desk_nav_group_data, Res.drawable.ic_refresh,
    ),
    Desktop(
        "desk", Res.string.desk_nav_desktop, GROUP_DESK,
        Res.string.desk_nav_group_desk, Res.drawable.ic_window,
        note = Res.string.desk_nav_desktop_only,
    ),
    // 「输入法自检」不在侧栏：它是**诊断页里的一条二级入口**（`SettingsDiagSubpage`
    // 的 onOpenImeSmoke 行），与手机端同一条路径 —— 侧栏一级项让它看起来像个独立功能，
    // 实际上是诊断仪器。
}

/**
 * 桌面设置独立窗口标题栏。原型 `setTitlebar()` = `<div class="titlebar">…<span class="wtitle">捎话 设置</span></div>`。
 */
@Composable
public fun DesktopSettingsTitleBar(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .padding(horizontal = LocalAppSpacing.current.olg13),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.desk_settings_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 桌面设置统一左侧列表（含「这台电脑」）。每个分组一个 `.dsg` 头，条目一个 `.li`，
 * 选中态底色是 surface + 一圈发丝线，铺主色的是图标块。
 */
@Composable
public fun DesktopSettingsSidebar(
    selected: DesktopSettingsNav,
    onSelect: (DesktopSettingsNav) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.width(220.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = sp.sm6),
            // 原型 `.sidelist{…gap:1px}`（prototype/relay.html:670）：条目之间是容器的 1px
            // 间隙，不是每条的 0.5px 外衬 —— 那 0.5 在档位表里不存在，也不是原型真值。
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            DesktopSettingsNav.values().groupBy { it.group }.forEach { (_, items) ->
                items.first().groupLabel?.let { label ->
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.06.em,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = LocalAppSpacing.current.osm9, end = LocalAppSpacing.current.osm9, top = sp.xs, bottom = sp.xxs),
                    )
                }
                items.forEach { nav ->
                    val sel = nav == selected
                    val bg = if (sel) MaterialTheme.colorScheme.surface else Color.Transparent
                    val stroke = if (sel) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    } else {
                        null
                    }
                    PressableSurface(
                        onClick = { onSelect(nav) },
                        color = bg,
                        shape = RelayShapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 6 / 8 / 8 全部落在档上（`AppSpacing` 的 sm6 / sm），必须消费 token。
                            // 原型 `.winset .li{min-height:40px;padding:5px 8px;gap:8px}`（relay.html:1516）：
                            // 内层横向 8px 与 gap 8px 是逐字真值，外层 6dp 是条目外边距（snap 1dp，政策②）。
                            // 值一字未改 ⇒ 渲染逐字节不变。
                            .padding(horizontal = sp.sm6)
                            .then(if (stroke != null) Modifier.border(stroke, RelayShapes.medium) else Modifier),
                    ) {
                        Row(
                            modifier = Modifier
                                .height(40.dp)
                                .padding(horizontal = sp.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        ) {
                            Surface(
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.accents.surface3,
                                shape = RelayShapes.small,
                                modifier = Modifier.size(26.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        painter = painterResource(nav.icon),
                                        contentDescription = null,
                                        tint = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(nav.title),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            nav.note?.let {
                                Text(
                                    text = stringResource(it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.alpha(0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置窗外壳：标题栏 + 左侧列表 + 右侧内容槽。
 *
 * 把「外壳」从 `:desktopApp` 搬进 `composeApp/ui/desktop/`，让截图门禁（只能渲染
 * `androidApp`，依赖不到 `:desktopApp`）也能拍到设置窗的整屏 —— 这是 `d-settings`
 * 那一行缺帧的根因修复。生产调用点（`desktopApp` 的 `DesktopHosting.kt`）保留自己的
 * `DesktopSettingsPane`，它直接调这里的 [DesktopSettingsTitleBar] / [DesktopSettingsSidebar]
 * 与子页，行为不变。
 *
 * [content] 是右侧内容区；传入哪一页就渲染哪一页（身份与连接 / 桌面 / …）。
 */
@Composable
public fun DesktopSettingsChrome(
    selected: DesktopSettingsNav,
    onSelect: (DesktopSettingsNav) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // 设置窗是独立窗口，不经 `DesktopShell`，所以在这里自己把卡片圆角钉成桌面档 12dp
        // （原型 `.win{--r-card:12px}`，`prototype/relay.html:584`）。不钉的话它吃
        // [LocalCardShape] 的默认值 16dp —— 那是 `.app`（手机/平板）的值。
        CompositionLocalProvider(LocalCardShape provides RelayShapes.medium) {
        Column(Modifier.fillMaxSize()) {
            DesktopSettingsTitleBar()
            HorizontalDivider()
            Row(Modifier.fillMaxSize()) {
                DesktopSettingsSidebar(
                    selected = selected,
                    onSelect = onSelect,
                    modifier = Modifier.width(220.dp),
                )
                // 竖分隔线。**必须是 VerticalDivider**：HorizontalDivider 默认 fillMaxWidth()，
                // 放进 Row 会把整行宽度吃掉，右侧 content 的 weight(1f) 被挤成 0 宽 ⇒ 内容区整片空白
                // （d-settings-desk 的参考帧就是这样一片黑，不是 AppTheme 穿透问题）。
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    content()
                }
            }
        }
        }
    }
}

/**
 * 设置窗「桌面」档（[DesktopSettingsNav.Desktop]）。
 *
 * 原型 `screenDSettingsDesk`：一张卡，里面两条 `.setrow`（省电后台档 / 开机自启）。
 * 这一页的内容此前已按原型逐值落地，缺的只是「能出帧」—— 现在它不再依赖 `DesktopHost`，
 * 而是吃 [DesktopSettingsHost]（见文件头注释），于是 `androidApp` 的截图门禁也能渲染。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
public fun DesktopPrefsPanel(
    host: DesktopSettingsHost,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val scrollState = rememberScrollState()
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(sp.lg),
            verticalArrangement = Arrangement.spacedBy(sp.md),
        ) {
        Column {
            Text(
                text = stringResource(Res.string.desk_nav_desktop),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.04.em,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = sp.xs, end = sp.xs, bottom = sp.sm6),
            )
            Text(
                text = stringResource(Res.string.desk_prefs_hint),
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 1.5.em,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = sp.md10),
            )
        }

        val supported = host.launchAtLoginSupported
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = RelayElevations.Card,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = LocalAppSpacing.current.olg13, top = sp.sm6, end = LocalAppSpacing.current.olg13, bottom = LocalAppSpacing.current.olg13),
            ) {
                PrefRow(
                    title = stringResource(Res.string.desk_prefs_power_title),
                    subtitle = stringResource(
                        Res.string.desk_prefs_power_subtitle,
                        seconds(DEFAULT_TIMINGS.presenceInterval),
                        seconds(DEFAULT_TIMINGS.presenceInterval * 3),
                        seconds(DEFAULT_TIMINGS.offlineAfter),
                        seconds(DEFAULT_TIMINGS.offlineAfter * 3),
                    ),
                    last = false,
                ) {
                    AppSwitch(
                        checked = host.powerSaver,
                        onCheckedChange = host::applyPowerSaver,
                    )
                }

                PrefRow(
                    title = stringResource(Res.string.desk_prefs_autostart_title),
                    subtitle = if (supported) {
                        stringResource(Res.string.desk_prefs_autostart_subtitle)
                    } else {
                        stringResource(Res.string.desk_prefs_autostart_unsupported)
                    },
                    last = true,
                ) {
                    if (supported) {
                        AppSwitch(
                            checked = host.launchAtLogin,
                            onCheckedChange = host::applyLaunchAtLogin,
                        )
                    } else {
                        Text(
                            stringResource(Res.string.desk_switch_unavailable),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        host.launchAtLoginError?.let { reason ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = RelayElevations.Card,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = LocalAppSpacing.current.olg13, top = sp.sm, end = LocalAppSpacing.current.olg13, bottom = LocalAppSpacing.current.olg13),
                ) {
                    Text(
                        text = stringResource(Res.string.desk_autostart_error_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = sp.xs),
                    )
                    AppOutlinedButton(
                        text = stringResource(Res.string.desk_retry),
                        onClick = { host.applyLaunchAtLogin(true) },
                        size = AppButtonSize.Compact,
                        modifier = Modifier.padding(top = sp.sm),
                    )
                }
            }
        }
        }
        PlatformScrollbar(
            state = scrollState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun PrefRow(
    title: String,
    subtitle: String,
    last: Boolean,
    trailing: @Composable () -> Unit,
) {
    val sp = LocalAppSpacing.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = LocalAppSpacing.current.osm9),
            horizontalArrangement = Arrangement.spacedBy(sp.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 1.45.em,
                    modifier = Modifier.padding(top = sp.xxs),
                )
            }
            trailing()
        }
        if (!last) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** 毫秒 → 秒，只用于文案；不引入新依赖。 */
private fun seconds(millis: Long): Long = millis / 1000
