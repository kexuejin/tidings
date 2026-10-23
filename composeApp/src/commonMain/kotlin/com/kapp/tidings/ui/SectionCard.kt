// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import androidx.compose.ui.unit.dp
import com.kapp.tidings.ui.theme.LocalCardShape
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import com.kapp.tidings.ui.theme.relayCardShadow

/**
 * 带标题的圆角卡片分组容器：设置页列表的基本单元。
 *
 * 设计升级：使用更精致的阴影、圆角和颜色层次。
 *
 * 卡头对齐原型 `.card>h3`：`display:flex;align-items:center;gap:6px;color:var(--primary)`，
 * 图标 `14px`（见 `.card>h3 svg{width:14px;height:14px}`），标题右侧可选一枚
 * `.card>h3 .r`（`margin-left:auto;font-size:11px;font-weight:500;color:var(--on-2)`）——
 * 能力声明页的「对端可见」就是它。
 *
 * [icon] 与 [trailing] 缺省为 null：非设置页的消费者（节点、Agent、桌面各 pane）
 * 不加图标就不变，所以这是**加法**而不是一次全仓视觉改动。
 * */
@Composable
public fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: DrawableResource? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .relayCardShadow(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        // 海拔归零：投影改由 relayCardShadow() 自绘（原型 `.card{box-shadow:var(--shadow)}`，
        // prototype/relay.html:119）；留着 1dp 会与自绘阴影叠成两层。
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = LocalCardShape.current,
    ) {
        Column(Modifier.padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.lg)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = LocalAppSpacing.current.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(IconSizes.Inline),
                    )
                    Spacer(Modifier.width(LocalAppSpacing.current.sm6))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (trailing != null) {
                    Spacer(Modifier.weight(1f))
                    trailing()
                }
            }
            content()
        }
    }
}
