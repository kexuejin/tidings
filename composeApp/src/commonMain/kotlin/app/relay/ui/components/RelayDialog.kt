package app.relay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 对话框的**共享骨架**（原型 `.dlg`，`prototype/relay.html:1446-1449` + `:1465`）。
 *
 * 为什么要有这个件：原型里所有居中对话框共用一套骨架 ——
 * ```css
 * .dlg{background:var(--surface);border-radius:20px;box-shadow:0 20px 60px rgba(0,0,0,.4);
 *      padding:16px 16px 12px}
 * .dlg h2{font-size:16px;font-weight:700;margin-bottom:14px}
 * .dlg .acts{display:flex;justify-content:flex-end;gap:6px;padding-top:10px}
 * ```
 * 而 M3 `AlertDialog` 的默认值是另一套：容器 `surfaceContainerHigh`、圆角 `shapes.extraLarge`（本仓
 * 28dp）、标题槽被 `ProvideContentColorTextStyle(headlineSmall)` 包着（本仓 24sp）。三条都要逐个
 * 覆盖 —— 抄一遍就会有第二份，迟早只改一边（`GroupCreateDialog` 原来就是那份手抄的）。
 *
 * 两处**刻意不覆盖**：
 * 1. 内边距留 M3 的 24dp。原型作者自己在 `prototype/relay.html:1450-1455` 里写过：他把叠成 38px 的
 *    第二层内边距清掉，让内容内缩回到 20px ——「与 M3 `AlertDialog` 的 24dp 内容内边距同档」。
 *    这是设计侧明说「同档」的地方，不是偏差。
 * 2. 标题的 `margin-bottom` 用 M3 的（原型 14px / M3 16dp），差 2dp，肉眼不可分。
 *
 * @param title 标题槽。**样式由本件给**（`titleMedium` + `Bold` = 16sp/700），调用方只写 `Text(文案)`；
 *   传 `null` 则不画标题（原型的 `.dlg` 也没有标题也能成立）。
 * @param content 正文槽。滚动由调用方自己管（原型是 `.dbody{overflow-y:auto}`，各屏自己带 `verticalScroll`）。
 */
@Composable
internal fun RelayDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        // 原型 `.dlg{background:var(--surface)}`。M3 默认 `surfaceContainerHigh`（本仓浅 #F3F2EC /
        // 深 #262827），比原型暗一档。
        containerColor = MaterialTheme.colorScheme.surface,
        // 原型 `.dlg{border-radius:20px}`。M3 默认 `shapes.extraLarge`（本仓 28dp）。
        shape = RoundedCornerShape(20.dp),
        title = title?.let { titleContent ->
            {
                // 包住调用方给的内容：M3 在外层套了 `headlineSmall`（24sp），这里的 `ProvideTextStyle`
                // 更靠内，所以赢。
                ProvideTextStyle(
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                ) { titleContent() }
            }
        },
        text = content,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

/**
 * 对话框里的按钮（原型 `.btn` / `.btn.pri` / `.btn.out`，`prototype/relay.html:372-386`）。
 *
 * ```css
 * .btn{border-radius:var(--r-ctl);border:1px solid var(--outline);background:var(--surface);
 *      color:var(--on);font-weight:500;padding:var(--pad-btn)}   /* --r-ctl:12px --pad-btn:9px 14px */
 * .btn.pri{background:var(--primary);color:var(--on-primary);border-color:transparent;font-weight:600}
 * ```
 * 对应 M3 `OutlinedButton`：底色/内容色/描边/圆角/内边距四样都要显式给（M3 默认圆角是
 * `shapes.extraLarge`-ish 的全圆角、内边距 24dp、高度 40dp）。
 *
 * @param primary true = 实心主按钮（`.btn.pri`，创建/批准那一颗）；false = 描边按钮（取消/关闭）。
 * @param danger 危险动作的描边按钮（`.btn.danger-o{color:var(--on-error-c);border-color:var(--error);
 *   background:transparent}`，原型 :379）—— 退群那一颗。
 */
@Composable
internal fun RelayDialogButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                primary -> Color.Transparent
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = when {
                primary -> MaterialTheme.colorScheme.primary
                danger -> Color.Transparent
                else -> MaterialTheme.colorScheme.surface
            },
            contentColor = when {
                primary -> MaterialTheme.colorScheme.onPrimary
                danger -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
