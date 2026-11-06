// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui

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
import androidx.compose.ui.text.font.FontWeight
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import androidx.compose.ui.unit.dp
import com.kapp.tenon.ui.theme.LocalCardShape
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import com.kapp.tenon.ui.theme.relayCardShadow

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
                    // 标题**自己**吃满剩余宽度，而不是让下面的 `Spacer(weight=1f)` 去吃。
                    //
                    // 差别的证据在帧里：`PrimitivesSectionCardPhonePreview` 的第一版把 trailing
                    // 写成一枚 "对端可见"，于是「超长卡头 + trailing」那一行里 trailing 被挤成
                    // 「对/端/可/见」四行竖排 —— 因为 Row 先量不带 weight 的 trailing，
                    // 再把剩下的交给 weight 的 Spacer（它先把宽度吃干），标题反过来又去挤 trailing。
                    // 反过来做（标题带 weight、trailing 不带）时 Row 先量 trailing 的真实宽度，
                    // 标题在自己那一份里折行，trailing 永远是它本来的样子。
                    //
                    // 短标题下像素与旧写法一致：标题占满剩余宽度、文字靠左，trailing 贴右边。
                    modifier = Modifier.weight(1f),
                    // 原型 `.card>h3{font-size:var(--fs-ctitle)=12px;font-weight:600;color:var(--primary)}`
                    // （`prototype/relay.html:143`）。原来用 `titleSmall`（14sp / Medium 500）：
                    // 字号大一档、字重轻一档 —— 于是"卡头"和"行标题"（也是 14/600）看起来一样重，
                    // 一张卡里的层次被抹平。
                    //
                    // 用 `labelMedium`（12sp）+ 显式 `SemiBold` 落在同一个 token 上，
                    // **不写裸 TextStyle**（设计 token 门禁按 role 计采用率，裸字号会记成欠债）。
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (trailing != null) {
                    Spacer(Modifier.width(LocalAppSpacing.current.sm6))
                    trailing()
                }
            }
            content()
        }
    }
}
