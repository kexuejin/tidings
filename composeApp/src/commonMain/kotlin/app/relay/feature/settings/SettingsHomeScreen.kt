// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.settings

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
import app.relay.app.BannerTone
import app.relay.app.BannerUi
import app.relay.app.RelayUiState
import app.relay.generated.resources.Res
import app.relay.generated.resources.r2_set_profile_rev_chip
import app.relay.generated.resources.set_conn_title
import app.relay.generated.resources.set_home_subtitle
import app.relay.generated.resources.set_title
import app.relay.generated.resources.set_conn_subtitle
import app.relay.generated.resources.set_home_broadcast_hint
import app.relay.generated.resources.set_theme_dark
import app.relay.generated.resources.set_theme_light
import app.relay.generated.resources.set_theme_system
import app.relay.generated.resources.ic_chev
import app.relay.ui.ContentWidths
import app.relay.ui.centerCapped
import app.relay.ui.components.AppBanner
import app.relay.ui.components.LargeTitleBar
import app.relay.ui.components.PeerAvatar
import app.relay.ui.components.TitleBarDivider
import org.jetbrains.compose.resources.stringResource
import app.relay.ui.theme.LocalAppSpacing

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
) {
    val settings = state.settings

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
                            text = settings.nick.ifBlank { "我的资料" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${settings.company} · ${settings.dept} · ${settings.team}".trim().ifBlank { "未填写组织信息" },
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
            SettingsGroupBlock(title = "连接与通知") {
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
                        title = "通知",
                        sub = "提醒规则与静默时段",
                        valText = when {
                            settings.notifyMentionOnly -> "仅被 @"
                            settings.notifyQuietHours -> "静默中"
                            else -> "全部"
                        },
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_NOTIFY) },
                    )
                }
            }

            // 分组 2：外观与能力
            SettingsGroupBlock(title = "外观与能力") {
                Column {
                    SettingsNavRow(
                        icon = Icons.Outlined.DarkMode,
                        title = "外观",
                        sub = "主题",
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
                        title = "能力声明",
                        sub = "对端可见",
                        valText = "tw1 · mrec1",
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_CAPS) },
                    )
                }
            }

            // 分组 3：数据与诊断
            SettingsGroupBlock(title = "数据与诊断") {
                Column {
                    SettingsNavRow(
                        icon = Icons.Outlined.Folder,
                        title = "数据与存储",
                        sub = "占用、残片与保留策略",
                        valText = "数据目录",
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_DATA) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = LocalAppSpacing.current.lg))
                    SettingsNavRow(
                        icon = Icons.Outlined.Info,
                        title = "诊断",
                        sub = "实时状态，非配置",
                        valText = "${state.peers.count { it.online }} 节点",
                        onClick = { onOpenPage?.invoke(SETTINGS_PAGE_DIAG) },
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
