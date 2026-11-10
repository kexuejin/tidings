// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.domain.model.TeamMemberUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.team_folder_badge
import com.kapp.tenon.generated.resources.team_folder_collapse
import com.kapp.tenon.generated.resources.team_folder_expand
import com.kapp.tenon.generated.resources.team_folder_idle
import com.kapp.tenon.generated.resources.team_folder_members
import com.kapp.tenon.generated.resources.team_folder_running
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.SurfaceTiers
import org.jetbrains.compose.resources.stringResource

/**
 * Team Folder 的行内标记与展开内容（渲染侧）。
 *
 * ## 为什么这里没有"组头"
 *
 * 上一版有一个 `TeamFolderHeader` 替代会话行，问题是它画不了**最后一条消息与时间**，
 * 而它替代掉的会话行两样都有 —— 折叠就丢信息，展开还要把同一条会话补回来。现在：
 *
 * - 会话行由 `ConversationRow` 原样画（唯一实现，滑动/长按/置顶/免打扰全继承）；
 * - 本文件的 [TeamBadge] 只作为**行内一枚标记**塞进它的标题行尾部：状态点 + 折叠符号；
 * - 展开后，[TeamLabelRow]（团队名 = leader 的 `@` token + 人数）与 [TeamMemberRow] 缩进排在会话行下面。
 *
 * ## 标记的视觉语言（二次修正）
 *
 * 第一版画的是"裸字符 `● +`"悬在标题后，用户反馈**跟其他行不一样、看着怪** ——
 * 这行列表的既有语言就是**药丸**（`工作区` / `Agent` / `N 待批`），散字符不属于它。
 * 所以标记改成与它们同款的一枚药丸：`团队 N ▸/▾`。
 *
 * - **运行态用药丸颜色表达**（primaryContainer / onPrimaryContainer），空闲用中性色 ——
 *   与 Agent 药丸用 agent 色是同一招；不再单独画点（颜色-only 的状态由读屏补足）。
 * - **药丸本身就是开关**（整块可点，热区比一个字符大），不再有独立的 `+`/`−`。
 * - 人数回到行上：前缀「团队」把它与副标的「n/m 人在线」（人）区分开；
 *   展开态的标签行仍带 `@token` 与人数。
 *
 * 颜色一律走 MaterialTheme 语义色，不新增 token、不写字面色值。
 */
@Composable
internal fun TeamBadge(
    convId: String,
    running: Boolean,
    expanded: Boolean,
    memberCount: Int,
    /** 折叠回调（参数是**会话键**）。null = 不可折叠（预览 / 只展示）。 */
    onToggle: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val stateLabel = stringResource(
        if (running) Res.string.team_folder_running else Res.string.team_folder_idle,
    )
    val toggleLabel = stringResource(
        if (expanded) Res.string.team_folder_collapse else Res.string.team_folder_expand,
    )
    Surface(
        color = if (running) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            SurfaceTiers.Raised
        },
        shape = CircleShape,
        modifier = modifier.then(
            if (onToggle == null) {
                Modifier
            } else {
                Modifier
                    // 内层 clickable 会消费事件，不会冒泡到整行（整行是进会话）。
                    .clickable { onToggle(convId) }
                    // 读屏要能听到「几个成员、在不在跑、按下去会展开什么」。
                    .semantics {
                        contentDescription = stateLabel + " · " + toggleLabel
                    }
            },
        ),
    ) {
        Text(
            // 符号是折叠指示：▸ = 能展开，▾ = 能收起。读屏用 contentDescription。
            text = stringResource(Res.string.team_folder_badge, memberCount) +
                "  " + (if (expanded) "\u25be" else "\u25b8"),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (running) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
        )
    }
}

@Composable
internal fun TeamLabelRow(
    token: String,
    memberCount: Int,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = sp.xl, end = sp.lg14, top = sp.sm6, bottom = sp.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm6),
    ) {
        Text(
            // 加 `@`：这个 token 就是点成员后会预填进输入框的那串字，显示成原样。
            text = "@" + token,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(Res.string.team_folder_members, memberCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TeamMemberRow(
    member: TeamMemberUi,
    /** 点击整行：打开所属群会话并预填 `@<leader>`。null = 不可点（预览 / 壳没有草稿能力）。 */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable { onClick() })
            .padding(start = sp.xl, end = sp.lg14, top = sp.xxs, bottom = sp.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 28dp：24dp + 12sp 的迷你行在手机上已到可读下限（会话行的手机档头像已提到
        // 48dp，团队下挂成员行提一档保持缩进层级）。名字同步升 bodyMedium——
        // 与主行标题同号不同重（Normal vs SemiBold），层级靠字重不靠字号。
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = member.name.take(1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(sp.sm6))
        Text(
            text = member.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(sp.sm6))
        Text(
            text = member.role,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
