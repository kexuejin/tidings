// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.relay.generated.resources.Res
import app.relay.generated.resources.ic_shield
import app.relay.generated.resources.set_cap_file_sub
import app.relay.generated.resources.set_cap_file_title
import app.relay.generated.resources.set_cap_record_sub
import app.relay.generated.resources.set_cap_record_title
import app.relay.generated.resources.set_cap_text_sub
import app.relay.generated.resources.set_cap_text_title
import app.relay.generated.resources.set_caps_card_title
import app.relay.generated.resources.set_caps_peer_visible
import app.relay.generated.resources.set_caps_saved
import app.relay.generated.resources.set_caps_title
import app.relay.ui.SectionCard
import org.jetbrains.compose.resources.stringResource

/**
 * 设置 · 能力声明（原型 `screenSetCaps()`，`prototype/relay.html:3904`）。
 *
 * 原型这一页：卡「声明」＋ `.r` 标注「对端可见」＋三个 `srow`
 * （排队保活 `tw1` 开 / 媒体撤回 `mrec1` 开 / 文件直传 `fd1` 关且禁用）＋一句 hint。
 * 标题栏是它自己的「能力声明 / 对端可见」——副标题与卡内标注同文，原型就是这么写的。
 *
 * `fd1` 恒为关且不可点：文件直传由**接收方**决定，手机这端不主动声明。
 * 这不能改成一个"能点但没用"的开关。
 */
@Composable
fun SettingsCapsSubpage(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    textCapabilityEnabled: Boolean = true,
    onTextCapabilityEnabledChange: ((Boolean) -> Unit)? = null,
    recordCapabilityEnabled: Boolean = true,
    onRecordCapabilityEnabledChange: ((Boolean) -> Unit)? = null,
) {
    var textCapability by rememberSaveable(textCapabilityEnabled) {
        mutableStateOf(textCapabilityEnabled)
    }
    var recordCapability by rememberSaveable(recordCapabilityEnabled) {
        mutableStateOf(recordCapabilityEnabled)
    }

    SettingsPageScaffold(
        title = stringResource(Res.string.set_caps_title),
        subtitle = stringResource(Res.string.set_caps_peer_visible),
        onBack = onBack,
        modifier = modifier,
    ) {
        SectionCard(
            title = stringResource(Res.string.set_caps_card_title),
            icon = Res.drawable.ic_shield,
            trailing = {
                Text(
                    text = stringResource(Res.string.set_caps_peer_visible),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            SettingSwitchRow(
                title = stringResource(Res.string.set_cap_text_title),
                subtitle = stringResource(Res.string.set_cap_text_sub),
                // 原型 `.tag ok`（绿底，`prototype/relay.html:294`）。
                badge = CAP_TAG_TW1,
                badgeTone = TagTone.Ok,
                checked = textCapability,
                onCheckedChange = { next ->
                    textCapability = next
                    onTextCapabilityEnabledChange?.invoke(next)
                },
            )
            SettingSwitchRow(
                title = stringResource(Res.string.set_cap_record_title),
                subtitle = stringResource(Res.string.set_cap_record_sub),
                // 原型 `.tag ok`（绿底，`prototype/relay.html:294`）。
                badge = CAP_TAG_MREC1,
                badgeTone = TagTone.Ok,
                checked = recordCapability,
                onCheckedChange = { next ->
                    recordCapability = next
                    onRecordCapabilityEnabledChange?.invoke(next)
                },
            )
            // fd1 由接收方决定，手机这端不主动声明：开关恒为关且不可点。
            SettingSwitchRow(
                title = stringResource(Res.string.set_cap_file_title),
                subtitle = stringResource(Res.string.set_cap_file_sub),
                badge = CAP_TAG_FD1,
                checked = false,
                onCheckedChange = {},
                enabled = false,
            )
            // 原型这一句在三条 srow **之后**（卡内 `.hint`），不是在卡头。
            CardHeaderNote(stringResource(Res.string.set_caps_saved))
        }
    }
}
