// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.settings

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.relay.generated.resources.Res
import app.relay.generated.resources.ic_chev
import app.relay.ui.SectionCard
import app.relay.ui.components.LargeTitleBar
import app.relay.ui.components.LabeledField
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.relayCardShadow

/**
 * 分组小标题 + 它下面那张列表卡的组合块。
 *
 * 原型里两者之间**没有间距**：`.grphead{padding:14px 4px 6px}` 的下边距就是 6px，紧接着的
 * `.setlist{margin-bottom:12px}` 直接起。而外层滚动列用的是 `Arrangement.spacedBy(md=12)`，
 * 它会在标题与卡片之间再插 12dp —— 于是标题离上一张卡近了 8dp、离自己的卡远了 12dp
 * （像素实测：原型 28.5 / 20.0 dp，代码 20.2 / 23.6 dp）。把这两件包进一个 Column，
 * 外层那 12dp 就只作用在「上一块 → 本块」之间：26 / 6，与原型逐值相同。
 */
@Composable
internal fun SettingsGroupBlock(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            // 原型 `.grphead{padding:14px 4px 6px;font-size:11px;color:var(--on-2);
            // font-weight:600;letter-spacing:.04em}`：11sp 灰字小标题，不是 14sp 主色标题。
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.04.em,
            modifier = Modifier.padding(
                start = LocalAppSpacing.current.xs,
                end = LocalAppSpacing.current.xs,
                top = LocalAppSpacing.current.lg14,
                bottom = LocalAppSpacing.current.sm6,
            ),
        )
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            // 原型 `.card{box-shadow:var(--shadow)}`：海拔归零、投影自绘。
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .relayCardShadow(shape = RoundedCornerShape(12.dp)),
        ) {
            content()
        }
    }
}

@Composable
// 第二轮把它从 private 提到 internal：诊断页那条「适配对照」也是原型里同一种 `.navrow`
// （`prototype/relay.html:3966-3971`），复用它才不会让两处各画一版。
internal fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    valText: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
    ) {
        // 原型 `.navrow .ni2{width:34px;height:34px;border-radius:12px;
        // background:var(--primary-c);color:var(--on-primary-c)}` + `.ni2 svg{width:18px}`。
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            // 原型 `.navrow .bv .t1{font-size:14px;font-weight:600}`：14px/600。
            // 原来是 bodyLarge(16sp/24) + Medium(500) —— 差集里 `font-size 只代码{16}`
            // 与 `font-weight 只代码{500}` 就是这一行。
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (!sub.isNullOrBlank()) {
                // 原型 `.navrow .bv .t2{font-size:12px;color:var(--on-2);margin-top:2px;
                // overflow:hidden;text-overflow:ellipsis;white-space:nowrap}`（`:1320-1321`）：
                // 单行、超出省略。原来没写 maxLines，诊断页那条长副标题被折成两行、
                // 卡片跟着变高 —— 原型那里是一行加省略号。
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!valText.isNullOrBlank()) {
            // 原型 `.navrow .val{font-size:12px;color:var(--on-2)}`：12sp。
            Text(valText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            painter = painterResource(Res.drawable.ic_chev),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 原型 `.chev svg{width:18px;height:18px}`。
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 顶栏：大标题 + 副标题 + 底部细分隔线。
 *
 * `LargeTitleBar` 落地后整个函数会被共享组件替掉；`onBack` 为 null 时不画返回键
 * （设置是页签，不需要）。
 */

/**
 * 带协议字段名的输入框：`控制面地址` + 等宽的 `baseUrl`。
 *
 * 故意不用 [LabeledField]：它没有「标签右侧挂一个字段名」的位置，而原型每个
 * 连接字段都带字段名（`baseUrl` / `timeoutMs` / …），那是与桌面端对齐的关键信息。
 */
@Composable
internal fun LabeledFieldWithKey(
    label: String,
    fieldKey: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    digitsOnly: Boolean = false,
    maxLength: Int? = null,
    hint: String? = null,
    supporting: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier.fillMaxWidth().padding(vertical = LocalAppSpacing.current.xs)) {
        // 原型 `.field label{display:flex;align-items:baseline;gap:8px;font-size:12px;
        // color:var(--on-2);margin-bottom:5px}`（`:320`）+ `.field label span{color:var(--on);
        // font-weight:600}`（`:321`）：字段名是 12px、`--on` 色、600 字重 —— 不是 14sp 的
        // bodyMedium + onSurfaceVariant。`.fk` 是 12px mono 顶到右端（`:322`）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (fieldKey.isNotEmpty()) {
                Text(
                    text = fieldKey,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                val filtered = if (digitsOnly) next.filter(Char::isDigit).take(6) else next
                onValueChange(if (maxLength != null) filtered.take(maxLength) else filtered)
            },
            modifier = Modifier.fillMaxWidth().padding(top = LocalAppSpacing.current.xs),
            singleLine = singleLine,
            enabled = enabled,
            placeholder = hint?.let { { Text(it) } },
            visualTransformation = visualTransformation,
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                // 禁用态提示用 RelayWarning #E9C46A 作前景只有约 1.6:1，改用 secondaryContainer 的 on 色。
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
            )
        }
    }
}

/** 原型 `.field label`（`:320-322`）那一行：字段名 + 右端 mono 协议字段名。头像这一档没有输入框，只要这一行。 */
@Composable
internal fun FieldLabelRow(label: String, fieldKey: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (fieldKey.isNotEmpty()) {
            Text(
                text = fieldKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 原型 `.psep`（`:1205-1207`）：11px/600、`.05em` 的小节标题 + 右侧一条撑满的 hairline。 */
@Composable
internal fun OrgSeparator(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = LocalAppSpacing.current.lg, bottom = LocalAppSpacing.current.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.05.em,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

// 第二轮删除了这里的 `CardHeaderAction`：它当年是因为「SectionCard 没有 trailing 插槽」才被
// 创造出来、把卡头右端的「恢复默认」画成内容第一行的；`SectionCard` 现在有 `trailing`，
// 连接页已改用它（原型 `.card>h3 .r` 的 11px 灰字）。
// 全仓已无其它调用点（`grep -rn CardHeaderAction` 只剩这里）。
