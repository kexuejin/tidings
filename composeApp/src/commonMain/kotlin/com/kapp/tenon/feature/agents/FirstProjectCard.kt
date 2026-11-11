// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.domain.FirstProjectUi
import com.kapp.tenon.domain.model.WorkspaceUi
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_onboarding_bind_cta
import com.kapp.tenon.generated.resources.agent_onboarding_bind_target
import com.kapp.tenon.generated.resources.agent_onboarding_device_body
import com.kapp.tenon.generated.resources.agent_onboarding_device_title
import com.kapp.tenon.generated.resources.conv_inbox_ws_row_unnamed
import com.kapp.tenon.generated.resources.ic_bot
import com.kapp.tenon.generated.resources.ic_link
import com.kapp.tenon.ui.components.AppButton
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import com.kapp.tenon.ui.theme.relayCardShadow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 新手冷启动的引导卡（UX 规范 §2.6）。
 *
 * ## 它出现在哪一刻，以及为什么不画在空态里
 *
 * 规范原文写的是「未绑定任何项目群时」。照字面做成"空态替代品"会得到一颗死按钮 ——
 * 看板的空态成立时**一个目录都没有**（判据是 `hasVisibleContent`，而一条已宣告的目录
 * 本身就让它成立），「立即关联第一个项目」按下去无目录可绑。判据与理由都在
 * [com.kapp.tenon.domain.firstProjectCandidate] 里。
 *
 * 所以这张卡画在**已经有目录、但一个都没绑群**的那一刻：屏幕上列着几个目录名，
 * 用户不知道下一步是建群。按下去 = 既有的「精简建群弹窗」（`WorkspaceGroupPromptDialog`），
 * 一次点选就完成"建群 + 绑目录"，这就是规范要的「10 秒内完成第一个项目协同群」。
 *
 * ## 它自己不判断该不该出现
 *
 * 该不该画由调用方（看板）用投影判：候选为 null 或壳没给回调 ⇒ **整张不画**。
 * 组件只负责画一张必然成立的卡 —— 把"要不要"塞进组件，测试就只能测到像素。
 */
@Composable
internal fun FirstProjectCard(
    first: FirstProjectUi,
    /** 点 CTA → 交给壳去开「精简建群弹窗」。 */
    onBind: (WorkspaceUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    Card(
        shape = LocalCardShape.current,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth().relayCardShadow(),
    ) {
        Column(
            modifier = Modifier.padding(sp.lg),
            verticalArrangement = Arrangement.spacedBy(sp.sm6),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                // 原型那枚 🤖。**不**做成 32dp 圆角方块：本仓没有这个形状的共用件，
                // 再抄一份 Surface + RoundedCornerShape 只会给 designSpecDrift 多记两笔
                // 裸尺寸/裸形状债（`design-token-usage` 的 debt.dimension / debt.shape）。
                // 一行标题配一枚 token 尺寸的图标，读起来一样，债务为零。
                Icon(
                    painter = painterResource(Res.drawable.ic_bot),
                    contentDescription = null,
                    modifier = Modifier.size(IconSizes.Label),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(
                        Res.string.agent_onboarding_device_title,
                        first.deviceName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(Res.string.agent_onboarding_device_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                // 要绑的是**哪一个**目录必须写出来：只说"关联第一个项目"而不说是哪个，
                // 用户按下去只能靠猜。目录名缺失时如实写「未命名项目目录」，不拿 wsId 顶替。
                text = stringResource(
                    Res.string.agent_onboarding_bind_target,
                    first.workspace.wsTitle
                        ?: stringResource(Res.string.conv_inbox_ws_row_unnamed),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            AppButton(
                text = stringResource(Res.string.agent_onboarding_bind_cta),
                onClick = { onBind(first.workspace) },
                size = AppButtonSize.Small,
                leadingIcon = Res.drawable.ic_link,
            )
        }
    }
}
