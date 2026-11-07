// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.kapp.tenon.domain.RelayUiState
import com.kapp.tenon.domain.SenderIdentityContext
import com.kapp.tenon.domain.resolveNodeIdentity
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.ui.components.AppIconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import com.kapp.tenon.ui.components.ChatIbtnIconSize
import com.kapp.tenon.ui.components.ChatIbtnSize
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.components.KindBadge
import com.kapp.tenon.ui.components.StatusDot
import com.kapp.tenon.ui.components.ScreenRequestConfirmDialog
import com.kapp.tenon.ui.components.TopBarIconAction
import com.kapp.tenon.ui.components.TopBarOverflowAction
import com.kapp.tenon.ui.components.TopBarOverflowItem
import com.kapp.tenon.ui.components.topBarActionRowGap
import com.kapp.tenon.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.domain.model.AgentSessionUi
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.generated.resources.action_back
import com.kapp.tenon.generated.resources.agent_session_state_busy
import com.kapp.tenon.generated.resources.agent_session_state_done
import com.kapp.tenon.generated.resources.agent_session_state_error
import com.kapp.tenon.generated.resources.agent_session_state_idle
import com.kapp.tenon.generated.resources.agent_session_untitled
import com.kapp.tenon.generated.resources.chat_group_info_title
import com.kapp.tenon.generated.resources.chat_group_members
import com.kapp.tenon.generated.resources.chat_group_online
import com.kapp.tenon.generated.resources.chat_clear_local_action
import com.kapp.tenon.generated.resources.chat_close_and_clear_action
import com.kapp.tenon.generated.resources.chat_more_actions
import com.kapp.tenon.generated.resources.ic_close
import com.kapp.tenon.generated.resources.ic_folder
import com.kapp.tenon.generated.resources.chat_nudge
import com.kapp.tenon.generated.resources.chat_restored_offline
import com.kapp.tenon.generated.resources.common_offline
import com.kapp.tenon.generated.resources.common_online
import com.kapp.tenon.generated.resources.hist_search_title
import com.kapp.tenon.generated.resources.ic_arrow_back
import com.kapp.tenon.generated.resources.ic_groups
import com.kapp.tenon.generated.resources.ic_nudge
import com.kapp.tenon.generated.resources.ic_screen_share
import com.kapp.tenon.generated.resources.ic_search
import com.kapp.tenon.generated.resources.ic_trash
import com.kapp.tenon.generated.resources.screen_remote_view_action
import com.kapp.tenon.generated.resources.workspace_bar_switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ChatHeader(
    peer: PeerUi,
    /** Phase 4：身份解析上下文由调用方按切片算好传入（默认由 state 派生，预览路径不变）。 */
    identityContext: SenderIdentityContext?,
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
    /**
     * 「清空本会话聊天记录」。null = 不画这个入口。
     *
     * 它**只进 ⋮ 菜单**（见 [ChatHeaderAction.overflowOnly]）：破坏性动作不占常驻图标位 ——
     * 常驻位上的一次误触没有第二次确认的机会。真正删除前还会过一次确认框
     * （[ClearConversationDialog]，由 [ChatScreen] 持有）。
     */
    onClearMessages: (() -> Unit)? = null,
    /** 「关闭并清空会话」。null = 不画这个入口。只进 ⋮ 菜单。 */
    onCloseAndClearConversation: (() -> Unit)? = null,
    onOpenSessionPicker: (() -> Unit)? = null,
    /**
     * 用户是从哪个**工作区**点进来的；null = 直接在跟这个节点聊。
     *
     * ## 为什么副标题要以它开头
     *
     * 副标题展示工作区名称，明确当前对话所属的项目上下文。
     */
    workspaceName: String? = null,
    /** 当前宿主关联的工作区总数；大于 1 且有 picker 时药丸可点击弹出选择器。 */
    workspaceCount: Int = 1,
    /** 点击顶栏项目药丸时的回调；null = 不可点。 */
    onOpenWorkspacePicker: (() -> Unit)? = null,
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
    // 头部标题与头像也走**同一个身份解析器**：1:1 的 agent 会话标题应是 agent 名（DSH），
    // 不是那台宿主机器的昵称 —— 否则列表里是 DSH、点进去头部又叫 mac mini。
    // 群头部保持群名（`peer` 是占位，解析器对群键没有意义）。
    val identityState = identityContext
    val resolvedPeer = if (group == null && identityState != null) {
        remember(peer, identityState) {
            resolveNodeIdentity(peer.nodeId, identityState)
        }
    } else {
        null
    }
    val peerTitle = resolvedPeer?.displayName?.takeIf { it.isNotBlank() } ?: peer.displayName
    val peerIsAgent = resolvedPeer?.isAgent ?: peer.isAgent
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
            AppIconButton(
                icon = Res.drawable.ic_arrow_back,
                contentDescription = stringResource(Res.string.action_back),
                onClick = onBack,
            )
        }
        val secondaryText: String? = if (group != null) {
            stringResource(Res.string.chat_group_members, group.members.size)
        } else if (peerIsAgent && identityContext != null) {
            val sessionName = activeSession?.let {
                it.displayTitle ?: stringResource(Res.string.agent_session_untitled)
            }
            val sessionState = when (activeSession?.state) {
                "busy" -> stringResource(Res.string.agent_session_state_busy)
                "done" -> stringResource(Res.string.agent_session_state_done)
                "error" -> stringResource(Res.string.agent_session_state_error)
                else -> stringResource(Res.string.agent_session_state_idle)
            }
            // 副标题只放「工作区 · 会话 · 状态」，没有就**不渲染**。在线/离线在标题行的
            // titleTrailing 已有（StatusDot + 文字）—— 上一版无内容时回退到在线态，
            // 结果 agent 还没有会话时头部连读两行「在线」（真机用户标注）。
            // 原型 m03-chat 的副标题是 IP:端口·TCP·能力，也没有第二份在线态。
            listOf(
                workspaceName,
                sessionName?.let { "$sessionName · $sessionState" },
            ).filterNotNull().joinToString(" · ").ifEmpty { null }
        } else {
            buildString {
                if (restored) {
                    append(stringResource(Res.string.chat_restored_offline))
                } else {
                    append(peer.subtitle)
                }
            }
        }
        ChatHeaderContent(
            title = peerTitle,
            isAgent = group == null && peerIsAgent,
            isGroup = group != null,
            avatarOnline = null,
            secondaryText = secondaryText,
            density = ChatHeaderDensity.Phone,
            modifier = Modifier.weight(1f),
            titleTrailing = {
                if (group == null) {
                    KindBadge(peerIsAgent)
                    StatusDot(peer.online)
                    Text(
                        text = stringResource(
                            if (peer.online) Res.string.common_online else Res.string.common_offline,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
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
            },
            secondaryContent = if (group == null && workspaceName != null) {
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.xs),
                        modifier = Modifier
                            .then(
                                if (onOpenWorkspacePicker != null && workspaceCount > 1) {
                                    Modifier
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .clickable { onOpenWorkspacePicker() }
                                } else {
                                    Modifier
                                }
                            ),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_folder),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(IconSizes.Badge),
                        )
                        Text(
                            text = workspaceName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onOpenWorkspacePicker != null && workspaceCount > 1) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = stringResource(Res.string.workspace_bar_switch),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(IconSizes.Inline),
                            )
                        }
                    }
                }
            } else null,
        )
        // 顶栏动作：移动端为保证对端标题、身份标签与状态有充裕宽度展示不被截断，
        // 全部动作统一收进「⋯」更多菜单中，避免常驻多个图标挤压标题。
        val actions = mutableListOf<ChatHeaderAction>()
        if (group != null && onOpenGroupInfo != null) {
            actions += ChatHeaderAction(
                label = stringResource(Res.string.chat_group_info_title),
                onClick = onOpenGroupInfo,
            ) { size ->
                Icon(
                    painter = painterResource(Res.drawable.ic_groups),
                    contentDescription = stringResource(Res.string.chat_group_info_title),
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
                    contentDescription = stringResource(Res.string.chat_nudge),
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
                    contentDescription = stringResource(Res.string.screen_remote_view_action),
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
                    contentDescription = stringResource(Res.string.hist_search_title),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(size),
                )
            }
        }
        onClearMessages?.let { clear ->
            actions += ChatHeaderAction(
                label = stringResource(Res.string.chat_clear_local_action),
                onClick = clear,
                // 只进 ⋮，不抢直出图标位：见形参 KDoc。
                overflowOnly = true,
            ) { size ->
                Icon(
                    painter = painterResource(Res.drawable.ic_trash),
                    contentDescription = stringResource(Res.string.chat_clear_local_action),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(size),
                )
            }
        }
        onCloseAndClearConversation?.let { closeAndClear ->
            actions += ChatHeaderAction(
                label = stringResource(Res.string.chat_close_and_clear_action),
                onClick = closeAndClear,
                overflowOnly = true,
            ) { size ->
                Icon(
                    painter = painterResource(Res.drawable.ic_close),
                    contentDescription = stringResource(Res.string.chat_close_and_clear_action),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(size),
                )
            }
        }
        // 直出位只从**非** overflowOnly 的动作里取；overflowOnly 的一律进 ⋮。
        // 分开算而不是"整体按顺序 take"：否则动作总数少于直出上限时，破坏性动作会被顶到
        // 常驻图标位上（agent 会话只剩一个「切换会话」时就是这种情形）。
        val inlineActions = actions.filterNot { it.overflowOnly }
        val overflowActions = inlineActions.drop(ChatHeaderInlineActions) + actions.filter { it.overflowOnly }
        if (overflowActions.isNotEmpty() || inlineActions.take(ChatHeaderInlineActions).isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    topBarActionRowGap(LocalAppSpacing.current.xxxl, ChatIbtnSize, LocalAppSpacing.current.sm6),
                ),
            ) {
                inlineActions.take(ChatHeaderInlineActions).forEach { action ->
                    TopBarIconAction(
                        contentDescription = action.label,
                        onClick = action.onClick,
                        size = ChatIbtnSize,
                        icon = { action.icon(ChatIbtnIconSize) },
                    )
                }
                if (overflowActions.isNotEmpty()) {
                    TopBarOverflowAction(
                        items = overflowActions.map { action ->
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

/** 聊天页顶栏的直出上限：移动端统一收进「⋯」更多菜单以保障标题区可读性。 */
private const val ChatHeaderInlineActions = 0

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
    /**
     * true = 永远只进「⋯」菜单，不参与前 [ChatHeaderInlineActions] 个直出图标位。
     *
     * 为什么需要它：直出位是给"看这个会话"的语境动作留的（切换会话 / 群成员 / 戳一碰 /
     * 查看屏幕）。破坏性的「清空本会话记录」跟它们抢位置，就会长出一个"按一下、历史没了"
     * 且没有第二道门的常驻图标。
     */
    val overflowOnly: Boolean = false,
    val icon: @Composable (Dp) -> Unit,
)
