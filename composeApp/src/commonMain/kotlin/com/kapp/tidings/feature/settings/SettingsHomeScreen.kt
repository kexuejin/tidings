// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.settings

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.APP_VERSION
import com.kapp.tidings.domain.BannerTone
import com.kapp.tidings.domain.BannerUi
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.desk_nav_group_appear
import com.kapp.tidings.generated.resources.desk_nav_group_conn
import com.kapp.tidings.generated.resources.desk_nav_group_data
import com.kapp.tidings.generated.resources.ic_chev
import com.kapp.tidings.generated.resources.ic_image
import com.kapp.tidings.generated.resources.ic_refresh
import com.kapp.tidings.generated.resources.r2_set_profile_avatar_label
import com.kapp.tidings.generated.resources.r2_set_profile_rev_chip
import com.kapp.tidings.generated.resources.set_appear_subtitle
import com.kapp.tidings.generated.resources.set_avatar_page_subtitle
import com.kapp.tidings.generated.resources.set_caps_none
import com.kapp.tidings.generated.resources.set_caps_peer_visible
import com.kapp.tidings.generated.resources.set_caps_title
import com.kapp.tidings.generated.resources.set_conn_subtitle
import com.kapp.tidings.generated.resources.set_conn_title
import com.kapp.tidings.generated.resources.set_data_dir
import com.kapp.tidings.generated.resources.set_data_subtitle
import com.kapp.tidings.generated.resources.set_diag_subtitle
import com.kapp.tidings.generated.resources.set_diag_title
import com.kapp.tidings.generated.resources.set_home_broadcast_hint
import com.kapp.tidings.generated.resources.set_home_notify_all
import com.kapp.tidings.generated.resources.set_home_notify_mention_only
import com.kapp.tidings.generated.resources.set_home_notify_quiet
import com.kapp.tidings.generated.resources.set_home_online_nodes
import com.kapp.tidings.generated.resources.set_home_subtitle
import com.kapp.tidings.generated.resources.set_more_appearance_title
import com.kapp.tidings.generated.resources.set_more_data_title
import com.kapp.tidings.generated.resources.set_more_notif_title
import com.kapp.tidings.generated.resources.set_notify_subtitle
import com.kapp.tidings.generated.resources.set_profile_org_empty
import com.kapp.tidings.generated.resources.set_profile_title
import com.kapp.tidings.generated.resources.set_theme_dark
import com.kapp.tidings.generated.resources.set_theme_light
import com.kapp.tidings.generated.resources.set_theme_system
import com.kapp.tidings.generated.resources.set_title
import com.kapp.tidings.generated.resources.set_update_subtitle
import com.kapp.tidings.generated.resources.set_update_title
import com.kapp.tidings.ui.ContentWidths
import com.kapp.tidings.ui.centerCapped
import com.kapp.tidings.ui.components.AppBanner
import com.kapp.tidings.ui.components.LargeTitleBar
import com.kapp.tidings.ui.components.PeerAvatar
import com.kapp.tidings.ui.components.TitleBarDivider
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.ui.theme.LocalAppSpacing

@Composable
internal fun SettingsHomeScreen(
    state: RelayUiState,
    onNavigateProfile: () -> Unit,
    onNavigateConnection: () -> Unit,
    onOpenPage: ((String) -> Unit)?,
    onDismissBanner: () -> Unit,
    onClearNote: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * 「版本与更新」行值列显示的当前版本。默认取领域侧的 [APP_VERSION] 常量 ——
     * 与 `RelayController.version` 的默认值同源，手机组合根没传时也是真值。
     */
    appVersion: String? = APP_VERSION,
) {
    val settings = state.settings
    // 「能力声明」这一行读**真实开关**：以前写死 tw1 · mrec1，用户在子页关掉后
    // 首页仍宣称开着 —— 界面在说假话。全关时说「未声明」，不假报。
    val declaredCaps = declaredCapabilityCodes(
        waitFrameCapability = settings.waitFrameCapability,
        recordMessageCapability = settings.recordMessageCapability,
    )
    val capsText = if (declaredCaps.isEmpty()) {
        stringResource(Res.string.set_caps_none)
    } else {
        declaredCaps.joinToString(" · ")
    }
    val onlinePeerCount = state.peers.count { it.online }

    Column(modifier = modifier.fillMaxSize()) {
        LargeTitleBar(
            title = stringResource(Res.string.set_title),
            subtitle = stringResource(Res.string.set_home_subtitle),
            onBack = onBack,
            contentMaxWidth = ContentWidths.Settings,
        )
        TitleBarDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                // 宽屏收列（原型 `.pad.set{max-width:640px;margin:0 auto}`，:1045）。
                // 放在 verticalScroll **之前**：被夹过的这一列就是滚动容器本身，
                // 于是滚动条、overscroll 与内容列边界三者重合，不会出现"能滚的比能看的宽"。
                .centerCapped(ContentWidths.Settings)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = LocalAppSpacing.current.lg,
                    top = LocalAppSpacing.current.lg14,
                    end = LocalAppSpacing.current.lg,
                    bottom = LocalAppSpacing.current.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
        ) {
            state.banner?.let { AppBanner(it, onDismissBanner) }
            state.settingsNote?.let { note ->
                AppBanner(banner = BannerUi(note, BannerTone.Info), onDismiss = onClearNote)
            }

            // 资料卡入口
            Card(
                onClick = onNavigateProfile,
                modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.sm),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(LocalAppSpacing.current.lg14),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
                ) {
                    PeerAvatar(
                        name = settings.nick,
                        size = 44.dp,
                        avatar = settings.avatar,
                        // 原型 `.navrow .navav{width:44px;height:44px;font-size:16px;
                        // border-radius:12px}`：44×44 的圆角方块，不是正圆（PeerAvatar 默认圆形）。
                        shape = RoundedCornerShape(12.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        // 原型这张卡是 `.navrow.tint`，标题走的是同一套 `.navrow .bv .t1`
                        // （`prototype/relay.html:1319`，14px/600）—— 不是 16sp 的 titleMedium，
                        // 那正是差集里最后一条 `font-size 只代码{16}`。
                        Text(
                            text = settings.nick.ifBlank { stringResource(Res.string.set_profile_title) },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val orgInfo = listOf(settings.company, settings.dept, settings.team)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString(" · ")
                        Text(
                            text = orgInfo.ifEmpty { stringResource(Res.string.set_profile_org_empty) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(Res.string.r2_set_profile_rev_chip, settings.profileRev),
                        style = MaterialTheme.typography.labelMedium,
                        // 原型 `.navrow .val.pri{color:var(--primary);font-weight:600}`：
                        // 12px / 600 / primary（labelMedium 自带 12sp，只差字重）。
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        painter = painterResource(Res.drawable.ic_chev),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        // 原型 `.navrow .chev svg{width:18px;height:18px}`（:1323）。
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // 分组 1：连接与通知
            SettingsGroupBlock(title = stringResource(Res.string.desk_nav_group_conn)) {
                Column {
                    SettingsNavRow(
                        icon = Icons.Outlined.Link,
                        title = stringResource(Res.string.set_conn_title),
                        sub = stringResource(Res.string.set_conn_subtitle),
                        valText = "UDP ${settings.udpPort}",
                        onClick = onNavigateConnection,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = LocalAppSpacing.current.lg))
                    SettingsNavRow(
                        icon = Icons.Outlined.Notifications,
                        title = stringResource(Res.string.set_more_notif_title),
                        sub = stringResource(Res.string.set_notify_subtitle),
                        // 三个开关可能同时开着；只报其中一个（以前「仅@」优先）会让另一个
                        // 看起来"没生效"。两个都开时并列，一个都没开时报「全部提醒」。
                        // 文案先取出来再拼：`stringResource` 只能在可组合作用域里调，
                        // 别把它塞进非内联 lambda（编译期会拦，但那种改法也没法读）。
                        valText = listOfNotNull(
                            stringResource(Res.string.set_home_notify_mention_only)
                                .takeIf { settings.notifyMentionOnly },
                            stringResource(Res.string.set_home_notify_quiet)
                                .takeIf { settings.notifyQuietHours },
                        ).ifEmpty { listOf(stringResource(Res.string.set_home_notify_all)) }
                            .joinToString(" · "),
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_NOTIFY) },
                    )
                }
            }

            // 分组 2：外观与能力
            SettingsGroupBlock(title = stringResource(Res.string.desk_nav_group_appear)) {
                Column {
                    // 「头像」这一行是上传头像页在**设置首页**的门。此前 `SETTINGS_PAGE_AVATAR`
                    // 这条路由分支有、却全仓没有一个写入点：资料页分段控件第三档被接成
                    // `controller::pickAvatar`（选图，不换页），于是那一页 259 行的诚实化
                    // 只在截图帧里存在。标题/副标题复用资料页与头像页自己的键，不另造说法。
                    SettingsNavRow(
                        icon = Res.drawable.ic_image,
                        title = stringResource(Res.string.r2_set_profile_avatar_label),
                        sub = stringResource(Res.string.set_avatar_page_subtitle),
                        valText = null,
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_AVATAR) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = LocalAppSpacing.current.lg))
                    SettingsNavRow(
                        icon = Icons.Outlined.DarkMode,
                        title = stringResource(Res.string.set_more_appearance_title),
                        sub = stringResource(Res.string.set_appear_subtitle),
                        valText = when (settings.themeMode) {
                            THEME_MODE_DARK -> stringResource(Res.string.set_theme_dark)
                            THEME_MODE_LIGHT -> stringResource(Res.string.set_theme_light)
                            else -> stringResource(Res.string.set_theme_system)
                        },
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_APPEAR) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = LocalAppSpacing.current.lg))
                    SettingsNavRow(
                        icon = Icons.Outlined.Security,
                        title = stringResource(Res.string.set_caps_title),
                        sub = stringResource(Res.string.set_caps_peer_visible),
                        valText = capsText,
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_CAPS) },
                    )
                }
            }

            // 分组 3：数据与诊断
            SettingsGroupBlock(title = stringResource(Res.string.desk_nav_group_data)) {
                Column {
                    SettingsNavRow(
                        icon = Icons.Outlined.Folder,
                        title = stringResource(Res.string.set_more_data_title),
                        sub = stringResource(Res.string.set_data_subtitle),
                        valText = stringResource(Res.string.set_data_dir),
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_DATA) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = LocalAppSpacing.current.lg))
                    SettingsNavRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(Res.string.set_diag_title),
                        sub = stringResource(Res.string.set_diag_subtitle),
                        valText = stringResource(Res.string.set_home_online_nodes, onlinePeerCount),
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_DIAG) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = LocalAppSpacing.current.lg))
                    SettingsNavRow(
                        // 「版本与更新」此前只有 when 分支、没有任何入口（死路由）：
                        // 这一行是它唯一的门。值列显示当前应用版本。
                        icon = Res.drawable.ic_refresh,
                        title = stringResource(Res.string.set_update_title),
                        sub = stringResource(Res.string.set_update_subtitle),
                        valText = appVersion,
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_UPDATE) },
                    )
                }
            }

            Text(
                text = stringResource(Res.string.set_home_broadcast_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = LocalAppSpacing.current.sm),
            )

        }
    }
}

/**
 * 首页「能力声明」这一行要显示的能力码，按开关**如实**给出。
 *
 * 以前这里写死 tw1 · mrec1：用户在能力声明子页把两个开关都关掉后，首页仍然宣称开着 ——
 * 界面在说假话。空列表表示一个都没声明，调用点据此显示「未声明」而不是画一个空串。
 * 顺序与 [com.kapp.tidings.domain.RelaySettings.caps] 的追加顺序一致（tw1 在前、mrec1 在后）。
 */
internal fun declaredCapabilityCodes(
    waitFrameCapability: Boolean,
    recordMessageCapability: Boolean,
): List<String> = buildList {
    if (waitFrameCapability) add("tw1")
    if (recordMessageCapability) add("mrec1")
}
