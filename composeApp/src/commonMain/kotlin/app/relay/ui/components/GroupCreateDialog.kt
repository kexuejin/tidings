// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package app.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.ui.theme.LocalAppSpacing
import app.relay.generated.resources.Res
import app.relay.generated.resources.action_create
import app.relay.generated.resources.common_cancel
import app.relay.generated.resources.common_offline
import app.relay.generated.resources.common_online
import app.relay.generated.resources.conv_group_members_hint
import app.relay.generated.resources.conv_group_name
import app.relay.generated.resources.conv_group_no_peers
import app.relay.generated.resources.conv_new_group
import app.relay.generated.resources.ic_check_double
import app.relay.app.model.PeerUi
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 建群对话框：一个名字 + 一份成员名单。
 *
 * 为什么是对话框而不是新开一屏：协议里**没有"创建群"这个报文**，建群就是造一份成员表
 * 再逐成员发出去。用户在这一步要做的决定只有两个（叫什么、拉谁），开一整屏只会让
 * "填完名字之后还要去哪"变成一个需要回答的问题。
 *
 * 住在 `ui/components` 而不是某一屏里面，是因为它现在有**两个**调用方：手机端的
 * 会话列表与桌面/平板的会话侧栏。抄一份到桌面壳里的话，成员排序、自己不在名单里、
 * "没有可拉的人时说清楚"这三条规矩就会有两份实现，迟早只改一边。
 *
 * 名单直接取节点表，**不排除 agent**：协议里成员就是 nodeId 列表，agent 也是节点，
 * 能不能有 agent 是协议层面的事，不该由这个对话框替用户决定。
 *
 * 自己不出现在名单里（也不能取消勾选）：成员表里没有自己的群在协议上是"我已经退群"，
 * 那种群发不出任何东西 —— 让用户能选出一个发不出话的群，是这里最不该犯的错。
 * 控制器侧还有一道（[app.relay.app.RelayController.createGroup] 会把自己补进成员表），
 * 但界面这一层就不该给出这个选项。
 *
 * @param peers 可选的成员（调用方传节点表；自己已经在外面被滤掉了）。
 * @param onConfirm 参数是**已 trim 的名字**与选中的 nodeId 列表；成员表的完整性由控制器负责。
 */
@Composable
public fun GroupCreateDialog(
    peers: List<PeerUi>,
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit,
    /**
     * 群名与已选成员的初值，**只给截图门禁用**。
     *
     * 两者都是屏内状态（`name` / `chosen`），静态帧点不了：没有这个接缝，参考图里
     * 永远是"群名空着、一个成员都没勾、创建按钮灰着"，而原型 `m05-groupcreate` /
     * `d03-groupcreate` 画的恰恰是**填好名字、勾了三个在线成员、创建可用**的样子 ——
     * 成员排序、勾选态、Agent 徽标这三条规矩只有那一版才看得见。
     * 生产调用方一律用默认值。
     */
    initialName: String = "",
    initialChosen: List<String> = emptyList(),
) {
    var name by remember { mutableStateOf(initialName) }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(initialChosen) } }
    // 在线的人排前面：建群时十有八九是想找现在能回话的那几个。
    val rows = remember(peers) {
        peers.sortedWith(compareByDescending<PeerUi> { it.online }.thenBy { it.displayName })
    }
    val canConfirm = name.isNotBlank() && chosen.isNotEmpty()
    val sp = LocalAppSpacing.current

    // 统一复用同目录 `RelayDialog.kt` 弹窗容器。
    RelayDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.conv_new_group)) },
        content = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.conv_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.conv_group_members_hint),
                    // 原型 `.avedit-lb{font-size:12px}`（`labelSmall` 是 11sp）。
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = sp.md, bottom = sp.xs),
                )
                if (rows.isEmpty()) {
                    // 没有可拉的人时**说清楚**，而不是给一个永远点不亮的「创建」。
                    Text(
                        text = stringResource(Res.string.conv_group_no_peers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(rows, key = { it.nodeId }) { peer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 原型 `.dlg .mem{border-radius:12px}` + `:hover{background:var(--surface-2)}`。
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable {
                                        if (!chosen.remove(peer.nodeId)) chosen.add(peer.nodeId)
                                    }
                                    // 原型 `.dlg .mem{padding:8px 4px}`（原来只有竖向 4dp）。
                                    .padding(horizontal = sp.xs, vertical = sp.sm),
                                // 原型 `.dlg .mem{gap:10px}`（原来靠 `padding(start=8dp)` 代替，少 2px）。
                                horizontalArrangement = Arrangement.spacedBy(sp.md10),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 整行可点，方框自己不接管手势：两处都能点的时候，点方框只切一次、
                                // 点行又切一次，手感上是"点了两下"。
                                MemberCheckbox(checked = peer.nodeId in chosen)
                                // 原型 `.mem` 里每一行都有一颗 30×30、圆角 9px 的首字头像；
                                // 原来的实现整颗漏掉了（`${p.ini?`<div class="av" style="width:30px;
                                // height:30px;border-radius:9px;…">`）。
                                PeerAvatar(
                                    name = peer.displayName,
                                    isAgent = peer.isAgent,
                                    size = 30.dp,
                                    shape = RoundedCornerShape(9.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = peer.displayName,
                                        // 原型 `.dlg .mem .nm{font-size:14px;font-weight:600}`。
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(
                                            if (peer.online) Res.string.common_online else Res.string.common_offline,
                                        ),
                                        // 原型 `.dlg .mem .st2{font-size:11px;margin-top:1px}`。
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 1.dp),
                                    )
                                }
                                // 原型 `.mem` 只给 agent 挂 `<span class="tag agent">`；
                                // 真人那一行**没有** tag（`p.isAgent?'…':''`）。
                                if (peer.isAgent) KindBadge(isAgent = true)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 原型 `.acts` 里两颗都是 `.btn`；主按钮多一条 `.btn.pri`（实心 + 600）。
            RelayDialogButton(
                text = stringResource(Res.string.action_create),
                onClick = { onConfirm(name.trim(), chosen.toList()) },
                primary = true,
                enabled = canConfirm,
            )
        },
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_cancel), onClick = onDismiss)
        },
    )
}

/**
 * 成员行左边的方框。原型是**自绘**的 `.cb`，不是 M3 `Checkbox`：
 * `.dlg .mem .cb{width:20px;height:20px;border-radius:5px;border:2px solid var(--outline)}`，
 * 勾上之后 `background/border-color:var(--primary)`、`color:#fff`、图标 `13×13`。
 * M3 `Checkbox` 是 18dp 容器、2dp 圆角、没有 2px 描边 —— 三处都不一样，换不出来。
 *
 * 用的图标是 `ic_check_double`：名字叫 double，`pathData` 却与原型 `i-check`
 * （`m4.5 12.5 5 5 10-11`）**逐字相同**（`design-icon-map.py` 判「复用」）。
 * 名字误导，几何没错 —— 换一个文件名不会让它更像原型。
 */
@Composable
private fun MemberCheckbox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(5.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                painter = painterResource(Res.drawable.ic_check_double),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}
