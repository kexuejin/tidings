// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.kapp.tenon.ui.components.AppIconButton
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.ic_close
import com.kapp.tenon.generated.resources.desktop_inspector_collapse
import com.kapp.tenon.generated.resources.desktop_inspector_title
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.ui.theme.LocalAppSpacing

/**
 * Wide 档的第三栏容器（整改方案 §2.1-1 / §3 WP-D3）。
 *
 * ## 它是外壳，不是内容
 *
 * AGENTS §2.6 的判据：「把外壳换掉之后，这块像素还应不应该一样？」inspector 的
 * 标题行/宽度/折叠按钮是桌面壳层独有的，内容（[content] 槽）全部复用 feature 层的
 * 既有组件 —— 节点视图放 NodeDetailBody、文件视图放 FileDetailPane、Agents 视图放
 * AgentApprovalsSection + AgentExecDeviceChecklist、会话视图放 peer 信息行。
 * **这里不出现第二份内容实现**（DesktopInspectorContentGuardTest 钉住）。
 *
 * ## 原型锚（D-019 边界声明）
 *
 * 原型 wide 画布（1440）当前与 Expanded 同型（`tier:'expanded'`），没有第三栏区块；
 * 外部在途材料冻结了 prototype/relay.html（lead 裁决 D-019：不进基线不动它）。
 * 因此本容器按「参数化外壳的既有资产重组」先行（lead 裁决 A）：宽度锚
 * [DesktopMetrics.INSPECTOR_MIN_PX]（= Expanded 侧栏宽 320，两个辅栏不一种密两种宽）、
 * 标题行照工具栏/侧栏的同一族版式；原型锚待外部材料提交后补（偏差登记，延期不是豁免）。
 *
 * @param onCollapse 收起按钮（壳持有折叠态，记忆进 window.json —— WP-D6② 的键位预留）。
 *   null = 这一档不显示收起钮（如平板宿主没有持久化通路）。
 */
@Composable
public fun DesktopInspectorPane(
    onCollapse: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.width(DesktopMetrics.INSPECTOR_MIN_PX.dp).fillMaxHeight(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(DesktopMetrics.TOOLBAR_PX.dp)
                    .padding(horizontal = sp.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 标题与侧栏头/文件柜头同族（labelLarge），不另开字阶。
                Text(
                    stringResource(Res.string.desktop_inspector_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (onCollapse != null) {
                    AppIconButton(
                        icon = Res.drawable.ic_close,
                        contentDescription = stringResource(Res.string.desktop_inspector_collapse),
                        onClick = onCollapse,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                content()
            }
        }
    }
}
