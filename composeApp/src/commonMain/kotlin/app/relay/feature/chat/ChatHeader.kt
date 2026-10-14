// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.relay.app.RelayUiState
import app.relay.core.protocol.GroupMeta
import app.relay.generated.resources.Res
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import app.relay.ui.components.ChatIbtnIconSize
import app.relay.ui.components.ChatIbtnSize
import app.relay.ui.components.KindBadge
import app.relay.ui.components.PeerAvatar
import app.relay.ui.components.StatusDot
import app.relay.ui.components.ScreenRequestConfirmDialog
import app.relay.ui.components.TopBarIconAction
import app.relay.ui.components.TopBarOverflowAction
import app.relay.ui.components.TopBarOverflowItem
import app.relay.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.PeerUi
import app.relay.generated.resources.action_back
import app.relay.generated.resources.agent_session_state_busy
import app.relay.generated.resources.agent_session_state_done
import app.relay.generated.resources.agent_session_state_error
import app.relay.generated.resources.agent_session_state_idle
import app.relay.generated.resources.agent_session_switch_title
import app.relay.generated.resources.agent_session_untitled
import app.relay.generated.resources.chat_caps_label
import app.relay.generated.resources.chat_group_info_title
import app.relay.generated.resources.chat_group_members
import app.relay.generated.resources.chat_group_online
import app.relay.generated.resources.chat_more_actions
import app.relay.generated.resources.chat_nudge
import app.relay.generated.resources.chat_restored_offline
import app.relay.generated.resources.chat_tcp_port
import app.relay.generated.resources.common_offline
import app.relay.generated.resources.common_online
import app.relay.generated.resources.hist_search_title
import app.relay.generated.resources.ic_arrow_back
import app.relay.generated.resources.ic_groups
import app.relay.generated.resources.ic_nudge
import app.relay.generated.resources.ic_screen_share
import app.relay.generated.resources.ic_search
import app.relay.generated.resources.screen_remote_view_action
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ChatHeader(
    peer: PeerUi,
    state: RelayUiState? = null,
    onOpenAgentConsole: (String) -> Unit,
    onBack: (() -> Unit)?,
    /** 对端记录还没出现、历史来自本机落盘；副标题要说清楚，不能装作还连着。 */
    restored: Boolean = false,
    /**
     * 戳一碰。null = 不画那个按钮（Agent 会话、或壳没有这个能力）。
     *
     * 放在头部而不是输入栏：它是"提醒对方看这个会话"，与打字这件事无关；
     * 输入栏里那一排是**发消息的能力**（附件、表情），混进去会让"打字"这条主路径变模糊。
     */
    onNudge: (() -> Unit)? = null,
    onRequestScreenView: (() -> Unit)? = null,
    /**
     * 截图接缝：确认表单在首帧就是打开的。默认 false = 生产行为（关的）。
     * 见 [ChatScreen] 的同名形参说明。
     */
    initialConfirmOpen: Boolean = false,
    /**
     * 群元数据。非 null 时头部画的是**群**：名字取 [GroupMeta.name]，在线那两字换成
     * 成员数，能力/IP/TCP 那行整个不画（群没有这些）。
     *
     * 判据是"这个会话是不是群"而不是"对端是不是 agent" —— 群会话的 [peer] 是个占位，
     * 它的字段没有一个是真实信息，照常渲染就会画出一串空串。
     */
    group: GroupMeta? = null,
    /** 群里当前在线人数（不含自己）。由调用方算好传进来，头部不碰 [RelayUiState]。 */
    memberOnlineCount: Int = 0,
    onOpenGroupInfo: (() -> Unit)? = null,
    onSearchHistory: (() -> Unit)? = null,
    onOpenSessionPicker: (() -> Unit)? = null,
    /**
     * 用户是从哪个**工作区**点进来的；null = 直接在跟这个节点聊。
     *
     * ## 为什么副标题要以它开头
     *
     * 副标题展示工作区名称，明确当前对话所属的项目上下文。
     */
    workspaceName: String? = null,
    /**
     * 当前会话（已按工作区收窄）。null = 这一屏没有会话可显示。
     *
     * ## 为什么由调用方给，而不是头部自己取
     *
     * 由外部统筹当前激活会话，保证头部信息与会话流的一致性。
     */
    activeSession: AgentSessionUi? = null,
    /**
     * 这一屏**可见**的会话条数（已按工作区收窄），由调用方算好传进来。
     *
     * ## 为什么不是头部自己数
     *
     * 避免节点级全局会话计数与工作区级展示数量产生偏差。
     */
    visibleSessionCount: Int? = null,
) {
    val sp = LocalAppSpacing.current
    // 「请求查看屏幕」的确认表单：以前点那颗图标按钮**直接发请求**，原型 d30-reqscreen
    // 在中间有一张确认卡（说明 + 取消 / 发送请求）。改成先开表单、确认后才发。
    var screenRequestConfirmOpen by remember { mutableStateOf(initialConfirmOpen) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 有返回按钮时把左边距交给 IconButton 自己的 48dp 热区，
            // 否则 16dp 边距叠在 48dp 热区上会把标题推得明显偏右。
            .padding(
                start = if (onBack != null) sp.xs else sp.lg,
                end = sp.lg,
                top = sp.sm,
                bottom = sp.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(Res.drawable.ic_arrow_back),
                    contentDescription = stringResource(Res.string.action_back),
                )
            }
        }
        // 原型 `.topbar.chat .hav{width:36px;height:36px;border-radius:12px;font-size:14px}`
        // —— 聊天顶栏头像是 **36dp 圆角方块**，不是 40dp 正圆。
        PeerAvatar(
            name = peer.displayName,
            isAgent = group == null && peer.isAgent,
            size = 36.dp,
            isGroup = group != null,
            shape = RoundedCornerShape(12.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // weight 不能省：Row 里没有 weight 的子项会按**整个**可用宽度去量，
                    // 于是一个 32 字上限的长昵称（RelaySettings.LIMIT_NICK）会把自己
                    // 撑到整行，把右边的「同事」标签、状态点和在线/离线挤成零宽 ——
                    // 实测在线那两字会竖着折成两行并被裁掉。
                    // fill = false 让短昵称仍然只占自己的宽度，不把这一行拉满。
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (group == null) {
                    KindBadge(peer.isAgent)
                    StatusDot(peer.online)
                    Text(
                        text = stringResource(
                            if (peer.online) Res.string.common_online else Res.string.common_offline,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 群不画在线点：那个点是"这个对端在不在"，而群是一群人 ——
                    // 用成员数说清楚"几个人在"，比一个不知道指谁的绿点有用。
                    Text(
                        text = stringResource(
                            Res.string.chat_group_online,
                            memberOnlineCount,
                            group.members.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = if (group != null) {
                    stringResource(Res.string.chat_group_members, group.members.size)
                } else if (peer.isAgent && state != null) {
                    // 会话名与状态都取调用方给的那个（已按工作区收窄），不再自己按 peerId 猜 ——
                    // 见 activeSession 形参的 KDoc。
                    // 判空的是**会话**不是名字：有会话但没名字时要写「未命名」，
                    // 否则副标题会整段少掉「会话名 · 状态」，而这一屏正高亮着那个会话。
                    val sessionName = activeSession?.let {
                        it.displayTitle ?: stringResource(Res.string.agent_session_untitled)
                    }
                    val sessionState = when (activeSession?.state) {
                        "busy" -> stringResource(Res.string.agent_session_state_busy)
                        "done" -> stringResource(Res.string.agent_session_state_done)
                        "error" -> stringResource(Res.string.agent_session_state_error)
                        else -> stringResource(Res.string.agent_session_state_idle)
                    }
                    buildString {
                        // 工作区名在最前：它回答的是「我在哪个项目里」，比会话名更外层。
                        // 从工作区列表点进来时它非空；直接跟节点聊时为空，那时这段不画。
                        if (workspaceName != null) {
                            append(workspaceName)
                            append(" · ")
                        }
                        if (sessionName != null) {
                            append(sessionName)
                            append(" · ")
                            append(sessionState)
                            append(" · ")
                        }
                        append(if (peer.online) stringResource(Res.string.common_online) else stringResource(Res.string.common_offline))
                        if (peer.tcpPort > 0) {
                            append(" · TCP ")
                            append(peer.tcpPort)
                        }
                    }
                } else {
                    buildString {
                        if (restored) {
                            // 只有 nodeId 的占位对端 subtitle 是空的，直接画出来就是一条
                            // 什么都没说的灰字；这里明确告诉用户"这是本机存下来的历史"。
                            append(stringResource(Res.string.chat_restored_offline))
                        } else {
                            append(peer.subtitle)
                        }
                        if (peer.tcpPort > 0) {
                            append(" · ")
                            append(stringResource(Res.string.chat_tcp_port, peer.tcpPort))
                        }
                        if (peer.caps.isNotEmpty()) {
                            append(" · ")
                            append(stringResource(Res.string.chat_caps_label, peer.caps.joinToString(" ")))
                        }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 顶栏动作：前 2 颗直出成 `.ibtn` 方块、第 3 颗起收进「⋯」菜单
        // （原型 `actsHTML(acts, 2)`，`prototype/relay.html:2834-2845`）。
        // 顺序照原型聊天页的槽位：第一颗是「会话 / 群」这个语境动作，第二颗是戳一碰。
        // 方块取**聊天页档** 30dp / 16dp 图标（`.topbar.chat .ibtn`，`:570-571`）。
        val actions = mutableListOf<ChatHeaderAction>()
        if (group == null && peer.isAgent && onOpenSessionPicker != null) {
            // 数的是**这一屏可见**的会话：① 子代理/空壳会话不算（否则一个只有 1 条
            // 真实会话的节点也会长出这颗按钮，点开却全是该藏的行）；② 必须是**本工作区**
            // 的那几条（否则按钮画得出来、点开只有 1 行）。两者都由调用方在同一集合上
            // 算好 —— 见 [visibleSessionCount] 的 KDoc。
            if ((visibleSessionCount ?: 0) > 1) {
                actions += ChatHeaderAction(
                    label = stringResource(Res.string.agent_session_switch_title),
                    onClick = onOpenSessionPicker,
                ) { size ->
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(size),
                    )
                }
            }
        }
        if (group != null && onOpenGroupInfo != null) {
            actions += ChatHeaderAction(
                label = stringResource(Res.string.chat_group_info_title),
                onClick = onOpenGroupInfo,
            ) { size ->
                Icon(
                    painter = painterResource(Res.drawable.ic_groups),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(size),
                )
            }
        }
        onNudge?.let { nudge ->
            actions += ChatHeaderAction(
                label = stringResource(Res.string.chat_nudge),
                onClick = nudge,
            ) { size ->
                Icon(
                    painter = painterResource(Res.drawable.ic_nudge),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(size),
                )
            }
        }
        onRequestScreenView?.let { req ->
            actions += ChatHeaderAction(
                label = stringResource(Res.string.screen_remote_view_action),
                // 点一下先开确认表单；真正的发送在 onSend 里（原型 d30-reqscreen 的「发送请求」）。
                onClick = { screenRequestConfirmOpen = true },
            ) { size ->
                Icon(
                    painter = painterResource(Res.drawable.ic_screen_share),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(size),
                )
            }
        }
        onSearchHistory?.let { search ->
            actions += ChatHeaderAction(
                label = stringResource(Res.string.hist_search_title),
                onClick = search,
            ) { size ->
                Icon(
                    painter = painterResource(Res.drawable.ic_search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(size),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 原型 `.topbar .acts{gap:6px}`（`:580`）—— 动作方块之间 6px，
            // 与最外层那颗 Row 的 12dp（`.tbinner` 的元素间距）不是同一件事。
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            actions.take(ChatHeaderInlineActions).forEach { action ->
                TopBarIconAction(
                    contentDescription = action.label,
                    onClick = action.onClick,
                    size = ChatIbtnSize,
                    icon = { action.icon(ChatIbtnIconSize) },
                )
            }
            if (actions.size > ChatHeaderInlineActions) {
                TopBarOverflowAction(
                    items = actions.drop(ChatHeaderInlineActions).map { action ->
                        TopBarOverflowItem(
                            label = action.label,
                            onClick = action.onClick,
                            leadingIcon = { action.icon(ChatIbtnIconSize) },
                        )
                    },
                    size = ChatIbtnSize,
                    iconSize = ChatIbtnIconSize,
                    contentDescription = stringResource(Res.string.chat_more_actions),
                )
            }
        }
        // Dialog 不进 Row 的布局（它画在另一个窗口里），放这儿只是为了贴着它的触发按钮。
        if (screenRequestConfirmOpen) {
            ScreenRequestConfirmDialog(
                onCancel = { screenRequestConfirmOpen = false },
                onSend = {
                    screenRequestConfirmOpen = false
                    onRequestScreenView?.invoke()
                },
            )
        }
    }
}

/** 聊天页顶栏的直出上限：原型 `actsHTML(acts, 2)`（`prototype/relay.html:2834-2845`）。 */
private const val ChatHeaderInlineActions = 2

/**
 * 聊天页顶栏的一颗动作。
 *
 * [icon] 是**可组合的图标槽**：本页的图标有两种来源（`Icons.Outlined.SwapHoriz` 与
 * `painterResource` 的资源），槽位让「直出的方块」与「菜单项的前置图标」共用同一份定义，
 * 不必为一个 `when` 把两种画法各写两遍。
 */
private class ChatHeaderAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: @Composable (Dp) -> Unit,
)
