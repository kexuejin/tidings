// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.conversations

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.conv_menu_mark_all_read
import com.kapp.tidings.generated.resources.conv_menu_new_session
import com.kapp.tidings.generated.resources.conv_menu_only_unread
import com.kapp.tidings.generated.resources.conv_menu_sort_recent
import com.kapp.tidings.generated.resources.conv_new_group
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.ic_groups
import com.kapp.tidings.generated.resources.workspace_create_group_title
import com.kapp.tidings.generated.resources.ic_list
import com.kapp.tidings.generated.resources.ic_refresh
import com.kapp.tidings.generated.resources.ic_sort
import com.kapp.tidings.generated.resources.ic_user

// 从 `ConversationsScreen.kt` 原样搬到这里（那一屏 597 行，先拆再加）：
// 搬迁不改行为、不改签名。下面这份 KDoc 是原处的原文。

/**
 * 顶栏「⋮」菜单的**内容本体**，从 `DropdownMenu` 里抽出来单独成函数。
 *
 * 为什么要抽：`DropdownMenu` 内部是 `Popup`，而 Layoutlib 只合成 Popup 的容器、
 * 不合成它的内容 ⇒ 任何被 `DropdownMenu` 包住的菜单在参考图里**永远是空的**，
 * `m01b-home-more` 这一屏因此一直是台账里的 ⚠️「无本屏预览」。
 * 抽成 public 函数之后，帧可以直接渲染菜单体（`ScreenshotPreviewsR2Chat.kt` 里
 * 用 `MenuPanel` 壳给它一个定位与投影），菜单项于是第一次有了参考图。
 *
 * 与 `EmojiPanel`（`ui/desktop/DesktopComposer.kt` 的 `EmojiPanel`）是同一条先例：
 * 把「Popup 里的内容」与「Popup 本身」分开，前者可拍、后者负责定位。
 *
 * **函数名里不能出现「More」这个词**（原来叫 `ConversationsMoreMenu`，已改）：
 * `tools/design-screen-diff.py` 用「原型渲染函数名的分词 ∩ 候选 Composable 名的分词」
 * 来消歧「这一屏到底由哪个函数画」。原型这一屏叫 `screenHomeMore`，分词是 `{home, more}`；
 * 原名 `ConversationsMoreMenu` 的分词里**正好有一个 `more`** ⇒ 得 1 分胜出，量具于是把
 * 「菜单体」当成了整屏，只量那一个函数，而真正画这一屏的 `ConversationsScreen` 得 0 分落选。
 * 后果不是保真度下降，而是**量具的切片变小**：`ConversationsScreen` 供的间距/圆角全部消失，
 * 被报成「只原型」，`design-screen-diff.py --check` 因此多出 `spacing|proto 新增 ['1.5','6']`。
 * 去掉 `More` 后分词交集为空，量具回落到改动前那条整文件路径，差集与基线逐键一致。
 * 同一个坑的另一半见 `MessagePressActions`（原名含 `Menu`，撞上 `screenMsgMenu` 的 `{menu,msg}`）。
 */

@Composable
public fun ConversationsOverflowItems(
    onCreateGroup: (() -> Unit)?,
    onGoToPairing: () -> Unit,
    onOnlyUnread: () -> Unit,
    onMarkAllRead: (() -> Unit)?,
    /**
     * 「关联工作区建群」。
     *
     * 为什么它要出现在**手机**的 ⋮ 菜单里：这条路径此前只接在两栏布局的会话侧栏与桌面
     * 工作区看板上，compact 布局既没有侧栏、工作区看板的按钮又只由两栏那个回调点亮 ——
     * 于是手机上**根本没有**建工作区群的入口（真机：工作区页只有「刷新」）。手机与桌面
     * 共用同一个对话框（[com.kapp.tidings.ui.components.WorkspaceGroupCreateDialog]），
     * 补的只是「从哪儿点开」。
     *
     * null = 这个壳不提供该入口（预览 / 没有工作区能力的宿主）。
     */
    onCreateWorkspaceGroup: (() -> Unit)? = null,
) {
    // 项序与分隔线照原型 `m01b-home-more`（`screenHomeMore`）逐条对齐。
    if (onCreateGroup != null) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.conv_new_group)) },
            leadingIcon = {
                Icon(painterResource(Res.drawable.ic_groups), contentDescription = null)
            },
            onClick = onCreateGroup,
        )
    }
    // 紧跟普通建群：两者是同一个动作的两档（拉人 / 绑工作区），分开放会让用户以为
    // 后者是别处的功能。图标用文件夹 —— 与工作区卡片一致，不另造一枚。
    if (onCreateWorkspaceGroup != null) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.workspace_create_group_title)) },
            leadingIcon = {
                Icon(painterResource(Res.drawable.ic_folder), contentDescription = null)
            },
            onClick = onCreateWorkspaceGroup,
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.conv_menu_new_session)) },
        leadingIcon = {
            Icon(painterResource(Res.drawable.ic_user), contentDescription = null)
        },
        onClick = onGoToPairing,
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.conv_menu_only_unread)) },
        leadingIcon = {
            Icon(painterResource(Res.drawable.ic_list), contentDescription = null)
        },
        onClick = onOnlyUnread,
    )
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.conv_menu_mark_all_read)) },
        leadingIcon = {
            Icon(painterResource(Res.drawable.ic_refresh), contentDescription = null)
        },
        // 「全部标为已读」画灰不消失：入口在那里、只是此刻没得标（原型同一颗也是灰的）。
        enabled = onMarkAllRead != null,
        onClick = { onMarkAllRead?.invoke() },
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.conv_menu_sort_recent)) },
        leadingIcon = {
            Icon(painterResource(Res.drawable.ic_sort), contentDescription = null)
        },
        enabled = false,
        onClick = {},
    )
}
