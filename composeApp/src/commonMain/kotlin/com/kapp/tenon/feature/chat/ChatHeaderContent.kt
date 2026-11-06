// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.ui.components.StatusDot
import com.kapp.tenon.ui.theme.LocalAppSpacing

/**
 * 聊天顶栏的**内容原语**：头像（+ 桌面的在场点）+ 标题行 + 副行。
 *
 * ## 为什么抽出来（AGENTS §2.6）
 *
 * 手机 [ChatHeader] 与桌面 `DesktopChatHeader` 画的是同一件事：这个会话是跟谁、
 * 在不在、下一行是什么。此前两个壳各写一份头像/标题/副行的排版，于是同一个会话
 * 在两端长着两张脸（头像尺寸、标题字阶、副行位置各写各的）。
 *
 * 抽出来之后：**内容**只有这一份；**外壳**（整行高度/内边距、返回按钮、多窗口
 * 入口、动作区、确认框）留在各自壳里。两端的差异收敛成 [ChatHeaderDensity] 一个
 * 枚举与一个 `narrow` 形参，调用点不再传裸 dp / style。
 *
 * [titleTrailing] 是标题行右侧的**插槽**：手机放「Agent 徽标 + 在线点 + 在线/离线」，
 * 桌面放「平台/团队 chip + 徽标」或群在线人数 pill。它们回答的是各自壳才有的事实
 * （桌面有平台/团队字段，手机在群头只报人数），所以由壳提供，而不是塞进内容原语
 * 再传一堆布尔。
 */
internal enum class ChatHeaderDensity {
    /** 手机：头像 36dp 圆角方块，标题 titleSmall。 */
    Phone,

    /** 桌面右栏：头像 44dp 正圆 + 右下角在场点，标题 titleMedium。 */
    Desktop,
}

@Composable
internal fun ChatHeaderContent(
    title: String,
    isAgent: Boolean,
    isGroup: Boolean,
    /** 桌面头像右下角的在场点；null = 不画（手机不画头像点、群也不画）。 */
    avatarOnline: Boolean?,
    /** 副行文案。两端各自的取舍（会话名/工作区/成员数/能力 token）在调用方算好。 */
    secondaryText: String,
    density: ChatHeaderDensity,
    avatar: Int? = null,
    avatarBytes: ByteArray? = null,
    /** 桌面窄栏：副行允许折两行（手机恒单行）。 */
    narrow: Boolean = false,
    modifier: Modifier = Modifier,
    titleTrailing: @Composable RowScope.() -> Unit = {},
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        Box {
            if (density == ChatHeaderDensity.Phone) {
                PeerAvatar(
                    name = title,
                    isAgent = isAgent,
                    size = 36.dp,
                    isGroup = isGroup,
                    avatar = avatar,
                    avatarBytes = avatarBytes,
                    shape = RoundedCornerShape(12.dp),
                )
            } else {
                PeerAvatar(
                    name = title,
                    isAgent = isAgent,
                    size = 44.dp,
                    isGroup = isGroup,
                    avatar = avatar,
                    avatarBytes = avatarBytes,
                )
            }
            if (density == ChatHeaderDensity.Desktop && !isGroup && avatarOnline != null) {
                Box(Modifier.align(Alignment.BottomEnd)) { StatusDot(avatarOnline, 11.dp) }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    if (density == ChatHeaderDensity.Phone) sp.sm else sp.sm6,
                ),
            ) {
                Text(
                    text = title,
                    style = if (density == ChatHeaderDensity.Phone) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    fontWeight = if (density == ChatHeaderDensity.Phone) FontWeight.SemiBold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // weight 不能省：没有 weight 的子项会按整行可用宽度去量，把右边的
                    // 徽标/状态点挤成零宽（实测在线两字会竖折并被裁掉）。fill=false
                    // 让短名字只占自己的宽，标签紧贴名字（原型 .dh2 里 badge 就在标题旁）。
                    modifier = Modifier.weight(1f, fill = false),
                )
                titleTrailing()
            }
            Text(
                text = secondaryText,
                style = if (density == ChatHeaderDensity.Phone) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (density == ChatHeaderDensity.Desktop && narrow) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
