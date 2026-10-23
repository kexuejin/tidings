// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.ic_bell
import com.kapp.tidings.generated.resources.set_more_notif_agent
import com.kapp.tidings.generated.resources.set_more_notif_agent_sub
import com.kapp.tidings.generated.resources.set_more_notif_mention
import com.kapp.tidings.generated.resources.set_more_notif_mention_sub
import com.kapp.tidings.generated.resources.set_more_notif_quiet
import com.kapp.tidings.generated.resources.set_more_notif_quiet_sub
import com.kapp.tidings.generated.resources.set_more_notif_title
import com.kapp.tidings.generated.resources.set_notify_card_title
import com.kapp.tidings.generated.resources.set_notify_subtitle
import com.kapp.tidings.ui.SectionCard
import org.jetbrains.compose.resources.stringResource

/**
 * 设置 · 通知（原型 `screenSetNotify()`，`prototype/relay.html:3873`）。
 *
 * 原型这一页只有一张卡「提醒」＋三个 `srow` 开关，卡下一条 hint；
 * 标题栏是它**自己的**「通知 / 提醒规则与静默时段」——
 * 合并页时代六段共用「设置 / 通知、外观、能力、数据与诊断」，那是一处真实偏差。
 *
 * 三个开关的原型初值：Agent 消息通知 `true` / 仅被 @ 时提醒 `true` /
 * 静默时段 `22:00 – 08:00` `false`。
 */
@Composable
fun SettingsNotifySubpage(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    notificationsEnabled: Boolean = true,
    onNotificationsEnabledChange: ((Boolean) -> Unit)? = null,
    mentionOnlyEnabled: Boolean = true,
    onMentionOnlyEnabledChange: ((Boolean) -> Unit)? = null,
    quietHoursEnabled: Boolean = false,
    onQuietHoursEnabledChange: ((Boolean) -> Unit)? = null,
) {
    // 本地状态：回调接上后它会被外部的值覆盖，没接上时交互仍然是真的。
    var agentNotifications by rememberSaveable(notificationsEnabled) {
        mutableStateOf(notificationsEnabled)
    }
    var mentionOnly by rememberSaveable(mentionOnlyEnabled) {
        mutableStateOf(mentionOnlyEnabled)
    }
    var quietHours by rememberSaveable(quietHoursEnabled) {
        mutableStateOf(quietHoursEnabled)
    }

    SettingsPageScaffold(
        title = stringResource(Res.string.set_more_notif_title),
        subtitle = stringResource(Res.string.set_notify_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        SectionCard(
            title = stringResource(Res.string.set_notify_card_title),
            icon = Res.drawable.ic_bell,
        ) {
            SettingSwitchRow(
                title = stringResource(Res.string.set_more_notif_agent),
                subtitle = stringResource(Res.string.set_more_notif_agent_sub),
                checked = agentNotifications,
                onCheckedChange = { next ->
                    agentNotifications = next
                    onNotificationsEnabledChange?.invoke(next)
                },
            )
            SettingSwitchRow(
                title = stringResource(Res.string.set_more_notif_mention),
                subtitle = stringResource(Res.string.set_more_notif_mention_sub),
                checked = mentionOnly,
                onCheckedChange = { next ->
                    mentionOnly = next
                    onMentionOnlyEnabledChange?.invoke(next)
                },
            )
            SettingSwitchRow(
                title = stringResource(Res.string.set_more_notif_quiet),
                subtitle = stringResource(Res.string.set_more_notif_quiet_sub),
                checked = quietHours,
                onCheckedChange = { next ->
                    quietHours = next
                    onQuietHoursEnabledChange?.invoke(next)
                },
            )
        }
    }
}
