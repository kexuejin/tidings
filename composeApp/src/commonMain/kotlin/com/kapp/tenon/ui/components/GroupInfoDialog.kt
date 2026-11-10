// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.core.protocol.representativeSeat
import com.kapp.tenon.core.protocol.seatsOfAgent
import com.kapp.tenon.core.protocol.isTeam
import com.kapp.tenon.core.protocol.AgentSenderId
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.core.protocol.TeamMemberDraft
import com.kapp.tenon.core.protocol.VirtualAgentMember
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.action_back
import com.kapp.tenon.generated.resources.ic_arrow_back
import com.kapp.tenon.generated.resources.chat_group_dissolve
import com.kapp.tenon.generated.resources.chat_group_add_member
import com.kapp.tenon.generated.resources.chat_group_admin_label
import com.kapp.tenon.generated.resources.chat_group_announce_label
import com.kapp.tenon.generated.resources.chat_group_copy_id
import com.kapp.tenon.generated.resources.chat_group_desc_label
import com.kapp.tenon.generated.resources.chat_group_edit_meta
import com.kapp.tenon.generated.resources.chat_group_id_copied
import com.kapp.tenon.generated.resources.chat_group_id_label
import com.kapp.tenon.generated.resources.chat_group_info_hint
import com.kapp.tenon.generated.resources.chat_group_info_sub
import com.kapp.tenon.generated.resources.chat_group_info_title
import com.kapp.tenon.generated.resources.chat_group_forget_local
import com.kapp.tenon.generated.resources.chat_group_leave
import com.kapp.tenon.generated.resources.chat_group_me
import com.kapp.tenon.generated.resources.chat_group_members_header
import com.kapp.tenon.generated.resources.chat_group_none
import com.kapp.tenon.generated.resources.chat_group_online
import com.kapp.tenon.generated.resources.chat_group_owner_label
import com.kapp.tenon.generated.resources.chat_group_remove_member
import com.kapp.tenon.generated.resources.common_close
import com.kapp.tenon.generated.resources.common_profile_unavailable
import com.kapp.tenon.generated.resources.group_auth_entry
import com.kapp.tenon.generated.resources.group_member_agent_badge
import com.kapp.tenon.generated.resources.team_roster_badge
import com.kapp.tenon.generated.resources.team_roster_button
import com.kapp.tenon.generated.resources.workspace_member_section_agents
import com.kapp.tenon.generated.resources.workspace_member_section_humans
import org.jetbrains.compose.resources.stringResource

import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.RelayShapes
import com.kapp.tenon.domain.SenderIdentity
import com.kapp.tenon.domain.SenderIdentityContext
import com.kapp.tenon.domain.groupParticipantRows
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.domain.resolveNodeIdentity
import com.kapp.tenon.ui.theme.SurfaceTiers
import com.kapp.tenon.domain.model.WorkspaceUi

/**
 * 群信息与成员面板：展示群名、公告、简介、群主与管理员、成员在线状态，
 * 并支持邀请新成员与群主/管理员移出成员、退出群聊。
 *
 * ## 两种外壳，一份内容（AGENTS §2.6）
 *
 * 这块内容在桌面是**居中弹窗**（[GroupInfoDialog]，原型 `.dlg`），在手机是
 * **整屏页面**（[GroupInfoPage]）—— 手机上叠着聊天页再弹一个 560dp 的居中框，
 * 内部还要再叠七个子对话框，是"手机形态层从未被审过"的现场之一。两种壳共用
 * [GroupInfoBody]（成员列表与资料卡）这一份实现：内容只有一份，外壳参数化。
 * 轻量确认（退群/解散/移出）在两种壳里都仍是弹窗 —— 那是它们本来就该有的形态。
 */
@Composable
fun GroupInfoDialog(
    group: GroupMeta,
    selfNodeId: String,
    /** 本机昵称（`settings.nick`）。不传则兜底上下文里自己只能显示 nodeId 前 8 位。 */
    selfNick: String? = null,
    peers: List<PeerUi>,
    memberOnlineCount: Int,
    onDismiss: () -> Unit,
    onAddMembers: ((List<String>) -> Unit)? = null,
    onRemoveMember: ((String) -> Unit)? = null,
    onUpdateMeta: ((String, String?, String?) -> Unit)? = null,
    onLeaveGroup: (() -> Unit)? = null,
    /**
     * 群主**解散**这个群（对所有成员生效）。null = 这个壳没有解散能力。
     *
     * 只有群主才画那颗按钮：非群主调它会被控制器拒（`GroupNoPermission`），
     * 画出来只会骗用户点一次。
     */
    onDissolveGroup: (() -> Unit)? = null,
    /**
     * 身份解析上下文。传 null 时按 `peers + group` 现建一份（桌面壳与截图夹具用）。
     *
     * 聊天页会传带 `agentKindsByHost / agentSenderIds` 的那份 —— 只有它才能把
     * agent 显示成 `DSH`、把 agent 从人类成员里分出去、并给出可用的 @ 候选。
     */
    identityContext: SenderIdentityContext? = null,
    /**
     * 长按成员头像「直接 @」。null = 不提供该手势（没有编辑器接它的壳）。
     *
     * 回调里的 [SenderIdentity] 已经是解析产物（`mentionToken` 与触发判据同源），
     * 调用方不该在这里再判一次谁是 agent。
     */
    onMentionMember: ((SenderIdentity) -> Unit)? = null,
    /**
     * 本机可以入驻这个群的 agent（每个就是一条入驻候选）。
     *
     * 来自本机工作区台账：一台设备可以装多个 agent。空 = 不提供入驻清单（截图夹具）。
     */
    localWorkspaces: List<WorkspaceUi> = emptyList(),
    /**
     * 入驻动作通路（D6：入驻不问目录）：`(agentId, duty)`。
     *
     * null = 不提供该入口（授权面板只读）。与 [onAddMembers] 分开：那个加**人**，
     * 这个入驻**本机智能体**。
     */
    onStationAgent: ((agentId: String, duty: String?) -> Unit)? = null,
    /**
     * 重新解析本机席位的执行投影并广播（「去暴露」的落点）。null = 该壳没有通路。
     */
    onReparseSeats: (() -> Unit)? = null,
    /**
     * 移除本机席位（撤回入驻；机主主权）。null = 该壳没有通路 ⇒ 行内不画「移出」。
     */
    onRemoveGroupAgent: ((agentId: String) -> Unit)? = null,
    /**
     * 团队编制的保存通路（团队创建方案 §2.1/§4.3）：`(agentId, roster)`。
     *
     * 定位键是席位身份 `(groupId, hostNodeId, agentId)`（D4）。null = 这个壳没有保存
     * 通路 ⇒ agent 行**不画**「团队编制」按钮（一个按下去没反应的按钮比没有更糟）。
     */
    onSetTeamRoster: ((agentId: String, roster: List<TeamMemberDraft>?) -> Unit)? = null,
    /**
     * 团队执行档端口（团队执行方案 §6.3）：null = 该壳没有执行面（手机等非宿主）
     * ⇒ TeamRosterDialog 的执行配置段整段不画（声明编辑不受影响）。
     */
    teamExecutionPort: com.kapp.tenon.domain.TeamExecutionPort? = null,
    /**
     * 手机壳传 true：TeamRosterDialog 底部注解「执行驱动由桌面宿主托管」
     * （§2.2 双端形态契约 —— 参数化的是外壳差异，名册本体两端共用一份）。
     */
    showTeamHostManagedNotice: Boolean = false,
    /**
     * 宿主目录端口（项目目录决策稿 §5.1）：null = 本机不是宿主（手机常态）⇒
     * 「本项目目录」行只画声明投影（不画「更换」入口）。
     */
    hostDirectoryPort: com.kapp.tenon.domain.HostDirectoryPort? = null,
    /**
     * 非宿主的目录行显示值：席位声明投影（`workspaceOfGroup` 的 wsTitle）。
     * 路径永远不上屏（`group_auth_path_host_only` 语义）。
     */
    declaredDirectoryTitle: String? = null,
    /** 设 / 清本群项目目录指针（D5；宿主壳接，手机壳不画入口）。 */
    onSetProjectDirectory: ((dirId: String?) -> Unit)? = null,
) {
    val isSelfOwner = group.ownerId == selfNodeId || (group.ownerId == null && group.creatorId == selfNodeId)
    /** 本机是否还在这份成员表里。被移出/被解散后为 false —— 见退出按钮的文案分支。 */
    val isSelfMember = group.hasMember(selfNodeId)
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDissolve by remember { mutableStateOf(false) }

    // 骨架（底色 / 20dp 圆角 / 16sp·700 标题 / 描边按钮）见同目录 `RelayDialog.kt`。
    RelayDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                // 群名的样式由骨架给（16sp/700）；下面那行副标题自带 `labelSmall`，不受影响。
                Text(text = group.name)
                Text(
                    text = stringResource(Res.string.chat_group_info_sub),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        content = {
            RelayDialogContentHeight(defaultMaxHeight = 560.dp) { cap ->
                GroupInfoBody(
                    group = group,
                    selfNodeId = selfNodeId,
                    selfNick = selfNick,
                    peers = peers,
                    memberOnlineCount = memberOnlineCount,
                    onDismiss = onDismiss,
                    onAddMembers = onAddMembers,
                    onRemoveMember = onRemoveMember,
                    onUpdateMeta = onUpdateMeta,
                    identityContext = identityContext,
                    onMentionMember = onMentionMember,
                    localWorkspaces = localWorkspaces,
                    onStationAgent = onStationAgent,
                    onReparseSeats = onReparseSeats,
                    onRemoveGroupAgent = onRemoveGroupAgent,
                    onSetTeamRoster = onSetTeamRoster,
                    showTeamHostManagedNotice = showTeamHostManagedNotice,
                    teamExecutionPort = teamExecutionPort,
                    hostDirectoryPort = hostDirectoryPort,
                    declaredDirectoryTitle = declaredDirectoryTitle,
                    onSetProjectDirectory = onSetProjectDirectory,
                    modifier = Modifier.fillMaxWidth().heightIn(max = cap),
                )
            }
        },
        confirmButton = {
            // 危险动作**不包 Row**：M3 的动作区自会排列，包死一个 Row 会把「宽度不够
            // 时换行」这条路堵掉 —— 大字号 / 英文 locale 下三颗按钮横排会溢出。
            // 解散只对群主画：非群主点它只会得到「没有权限」，那是注定失败的按钮。
            if (onDissolveGroup != null && isSelfOwner) {
                RelayDialogButton(
                    text = stringResource(Res.string.chat_group_dissolve),
                    onClick = { confirmDissolve = true },
                    danger = true,
                )
            }
            if (onLeaveGroup != null) {
                // 原型 `.btn.danger-o`（描边 + `--on-error-c` 文字）。
                //
                // **已经不在成员表里时，这颗按钮的动作是「删掉本地这条」，不是「退出」。**
                // 措辞必须跟着变：对着一份「我不在里面的群」说「退出群聊」，用户会以为
                // 自己还在群里、只是要离开 —— 而这个群他其实早就进不去了。同一个回调
                // （`handleLeaveGroup` 现在也覆盖这一档），两种文案。
                RelayDialogButton(
                    text = stringResource(
                        if (isSelfMember) Res.string.chat_group_leave else Res.string.chat_group_forget_local,
                    ),
                    onClick = { confirmLeave = true },
                    danger = true,
                )
            }
        },
        // 「关闭」挪进 dismissButton 槽：它是中性动作，不该和两颗破坏性按钮挤在
        // 同一个 confirm 槽里排横排 —— 分开后宽度不够时 M3 也能把中性那颗折到下一行。
        dismissButton = {
            RelayDialogButton(text = stringResource(Res.string.common_close), onClick = onDismiss)
        },
    )

    // 确认解散群聊对话框（仅群主；与退出分成两个框，因为后果不同）。
    val dissolveGroup = onDissolveGroup
    if (confirmDissolve && dissolveGroup != null) {
        ConfirmDissolveGroupDialog(
            onDismiss = { confirmDissolve = false },
            onConfirm = {
                confirmDissolve = false
                onDismiss()
                dissolveGroup()
            },
        )
    }

    // 确认退出群聊对话框
    val leaveGroup = onLeaveGroup
    if (confirmLeave && leaveGroup != null) {
        ConfirmLeaveGroupDialog(
            onDismiss = { confirmLeave = false },
            onConfirm = {
                confirmLeave = false
                onDismiss()
                leaveGroup()
            },
        )
    }
}

/**
 * 群信息的**手机壳**：整屏页面（顶栏返回 + 群信息内容 + 内容末尾的危险动作）。
 *
 * 为什么手机不弹窗：聊天页本身已是整屏详情，再叠一个 560dp 上限的居中框，内容
 * 被压在 ~330dp 宽里；而这块内容天然是「一屏信息 + 滚动列表」，整屏呈现才是它
 * 本来的形状（设置子页先例：页键整屏推入 + 返回）。与弹窗壳共用 [GroupInfoBody]
 * —— 内容一份，外壳两副。
 *
 * 子层的轻量确认（退群/解散/移出/撤回暴露）仍是弹窗：它们是要用户「核对一下再
 * 按」的小卡，弹窗形态正确；整屏化的只有**重内容**这一层。
 *
 * public 只有一个理由：截图门禁在 `:androidApp` 的 `screenshotTest` 源集里，是
 * 另一个模块，Kotlin 的 `internal` 对它不可见（与 `ConversationActionsMenuContent`
 * 同一条先例）。仓库外调用点为零，除截图门禁外不要新增调用。
 */
@Composable
public fun GroupInfoPage(
    group: GroupMeta,
    selfNodeId: String,
    /** 本机昵称。与弹窗壳同一条兜底：不传则群主显示成 nodeId 前 8 位。 */
    selfNick: String? = null,
    peers: List<PeerUi>,
    memberOnlineCount: Int,
    onDismiss: () -> Unit,
    onAddMembers: ((List<String>) -> Unit)? = null,
    onRemoveMember: ((String) -> Unit)? = null,
    onUpdateMeta: ((String, String?, String?) -> Unit)? = null,
    onLeaveGroup: (() -> Unit)? = null,
    onDissolveGroup: (() -> Unit)? = null,
    identityContext: SenderIdentityContext? = null,
    onMentionMember: ((SenderIdentity) -> Unit)? = null,
    localWorkspaces: List<WorkspaceUi> = emptyList(),
    onStationAgent: ((agentId: String, duty: String?) -> Unit)? = null,
    onReparseSeats: (() -> Unit)? = null,
    onRemoveGroupAgent: ((agentId: String) -> Unit)? = null,
    onSetTeamRoster: ((agentId: String, roster: List<TeamMemberDraft>?) -> Unit)? = null,
    showTeamHostManagedNotice: Boolean = false,
    teamExecutionPort: com.kapp.tenon.domain.TeamExecutionPort? = null,
    hostDirectoryPort: com.kapp.tenon.domain.HostDirectoryPort? = null,
    declaredDirectoryTitle: String? = null,
    onSetProjectDirectory: ((dirId: String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val isSelfOwner = group.ownerId == selfNodeId || (group.ownerId == null && group.creatorId == selfNodeId)
    val isSelfMember = group.hasMember(selfNodeId)
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDissolve by remember { mutableStateOf(false) }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 与 ChatHeader 同款边距：返回按钮自带 48dp 热区，左缘交给它。
                    .padding(start = sp.xs, end = sp.lg, top = sp.sm, bottom = sp.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.md),
            ) {
                AppIconButton(
                    icon = Res.drawable.ic_arrow_back,
                    contentDescription = stringResource(Res.string.action_back),
                    onClick = onDismiss,
                )
                Text(
                    text = stringResource(Res.string.chat_group_info_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            GroupInfoBody(
                group = group,
                selfNodeId = selfNodeId,
                selfNick = selfNick,
                peers = peers,
                memberOnlineCount = memberOnlineCount,
                onDismiss = onDismiss,
                onAddMembers = onAddMembers,
                onRemoveMember = onRemoveMember,
                onUpdateMeta = onUpdateMeta,
                identityContext = identityContext,
                onMentionMember = onMentionMember,
                localWorkspaces = localWorkspaces,
                onStationAgent = onStationAgent,
                onReparseSeats = onReparseSeats,
                onRemoveGroupAgent = onRemoveGroupAgent,
                onSetTeamRoster = onSetTeamRoster,
                showTeamHostManagedNotice = showTeamHostManagedNotice,
                teamExecutionPort = teamExecutionPort,
                hostDirectoryPort = hostDirectoryPort,
                declaredDirectoryTitle = declaredDirectoryTitle,
                onSetProjectDirectory = onSetProjectDirectory,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = sp.lg),
                footer = {
                    // 危险动作收在内容末尾（与主流 IM 的群资料页一致）：不占顶栏、
                    // 不做常驻底栏 —— 退群/解散是低频破坏性动作，滚动到底才见得到
                    // 恰好是「要三思」的一部分。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = sp.md, bottom = sp.lg),
                        verticalArrangement = Arrangement.spacedBy(sp.sm),
                    ) {
                        if (onDissolveGroup != null && isSelfOwner) {
                            AppOutlinedButton(
                                text = stringResource(Res.string.chat_group_dissolve),
                                onClick = { confirmDissolve = true },
                                modifier = Modifier.fillMaxWidth(),
                                tone = AppButtonTone.Danger,
                            )
                        }
                        if (onLeaveGroup != null) {
                            // 文案分支与弹窗壳同一句判据：不在成员表里时动作是
                            // 「删掉本地这条」，说「退出群聊」是在撒谎。
                            AppOutlinedButton(
                                text = stringResource(
                                    if (isSelfMember) Res.string.chat_group_leave else Res.string.chat_group_forget_local,
                                ),
                                onClick = { confirmLeave = true },
                                modifier = Modifier.fillMaxWidth(),
                                tone = AppButtonTone.Danger,
                            )
                        }
                    }
                },
            )
        }
    }

    // 确认解散群聊对话框（仅群主）
    val dissolveGroup = onDissolveGroup
    if (confirmDissolve && dissolveGroup != null) {
        ConfirmDissolveGroupDialog(
            onDismiss = { confirmDissolve = false },
            onConfirm = {
                confirmDissolve = false
                onDismiss()
                dissolveGroup()
            },
        )
    }

    // 确认退出群聊对话框
    val leaveGroup = onLeaveGroup
    if (confirmLeave && leaveGroup != null) {
        ConfirmLeaveGroupDialog(
            onDismiss = { confirmLeave = false },
            onConfirm = {
                confirmLeave = false
                onDismiss()
                leaveGroup()
            },
        )
    }
}

/**
 * 群信息的**内容本体**：资料卡 + 人类/agent 成员列表 + 授权入口 + 各子层入口，
 * 以及全部子对话框的宿主。弹窗壳（[GroupInfoDialog]）与页面壳（[GroupInfoPage])
 * 各给它一个 modifier 与 footer —— 内容只有这一份。
 *
 * @param modifier 套在内容 LazyColumn 上：弹窗壳传 `heightIn(max = 比例上限)`，
 *   页面壳传 `weight(1f)`（填满页面剩余高度）。
 * @param footer 内容末尾的**可选**插槽：页面壳放危险动作（退群/解散），弹窗壳
 *   的对应动作在对话框动作条里（不进滚动区），传 null。
 */
@Composable
private fun GroupInfoBody(
    group: GroupMeta,
    selfNodeId: String,
    selfNick: String?,
    peers: List<PeerUi>,
    memberOnlineCount: Int,
    onDismiss: () -> Unit,
    onAddMembers: ((List<String>) -> Unit)?,
    onRemoveMember: ((String) -> Unit)?,
    onUpdateMeta: ((String, String?, String?) -> Unit)?,
    identityContext: SenderIdentityContext?,
    onMentionMember: ((SenderIdentity) -> Unit)?,
    localWorkspaces: List<WorkspaceUi>,
    onStationAgent: ((agentId: String, duty: String?) -> Unit)?,
    onReparseSeats: (() -> Unit)?,
    onRemoveGroupAgent: ((agentId: String) -> Unit)?,
    onSetTeamRoster: ((agentId: String, roster: List<TeamMemberDraft>?) -> Unit)?,
    showTeamHostManagedNotice: Boolean,
    teamExecutionPort: com.kapp.tenon.domain.TeamExecutionPort?,
    hostDirectoryPort: com.kapp.tenon.domain.HostDirectoryPort?,
    declaredDirectoryTitle: String?,
    onSetProjectDirectory: ((dirId: String?) -> Unit)?,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    // 名字解析只有一份实现（[groupParticipantRows]）：agent 宿主显示 agent 名（DSH）、
    // 人类按 档案昵称 → 备注 → 群昵称 → 短 id 回落；这里不再自己 `peerMap[id]?.displayName`。
    val ctx = remember(group, peers, selfNodeId, identityContext) {
        identityContext ?: SenderIdentityContext(
            selfNodeId = selfNodeId,
            // 兜底路径也要有 selfNick，否则群主显示成 nodeId 前 8 位。
            selfNick = selfNick,
            peers = peers,
            group = group,
        )
    }
    val rows = remember(ctx) { groupParticipantRows(ctx) }
    val peerMap = remember(peers) { peers.associateBy { it.nodeId } }
    val meText = stringResource(Res.string.chat_group_me)
    val noneText = stringResource(Res.string.chat_group_none)
    // 资料未到时的说明：仍然显示 8 位短 id（用户至少能对上"是那台设备"），
    // 但副行如实说一句「未获取到资料」，不让裸 id 冒充真名。
    val profileUnavailableText = stringResource(Res.string.common_profile_unavailable)
    // 群主是**人**，不是它机器上的 agent：这里优先用对端自己报的名字。
    // 用 resolveNodeIdentity 会把 agent 宿主显示成 agent 名（实测群主一栏显示成会话别名）。
    val ownerNick = group.ownerId
        ?.let { ownerId ->
            if (ownerId == selfNodeId) {
                meText
            } else {
                peerMap[ownerId]?.displayName?.takeIf { it.isNotBlank() }
                    ?: resolveNodeIdentity(ownerId, ctx).displayName
            }
        }
        ?: noneText

    // 「授权管理」对话框。与加人分开：那个加**人**，这个是项目目录授权与入驻。
    var showAuthDialog by remember { mutableStateOf(false) }
    var showEditMetaDialog by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<String?>(null) }
    // 撤回**本机** agent 席位前也要过一道确认：它对全群生效（群里的人立刻 @ 不到它），
    // 与移出人类成员同一档破坏性 —— 过去一按就生效、没有回头路。
    var agentToRevoke by remember { mutableStateOf<String?>(null) }
    // 团队编制对话框的目标（行身份, 该 agent 的本机席位）；null = 关着。
    var teamRosterFor by remember { mutableStateOf<Pair<SenderIdentity, List<VirtualAgentMember>>?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    val isSelfAdmin = group.adminIds?.contains(selfNodeId) == true
    val canManageMembers = group.ownerId == selfNodeId ||
        (group.ownerId == null && group.creatorId == selfNodeId) || isSelfAdmin

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RelayShapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(LocalAppSpacing.current.md), verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PeerAvatar(name = group.name, isAgent = false, size = 44.dp, isGroup = true)
                        Column(Modifier.padding(start = LocalAppSpacing.current.md)) {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(
                                    Res.string.chat_group_online,
                                    memberOnlineCount,
                                    group.members.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
                                modifier = Modifier.padding(top = LocalAppSpacing.current.xxs),
                            ) {
                                // 「rev N」曾经也挤在这一排 —— 它是协议内部版本号，
                                // 与同文件 KDoc 自己说的 groupId/UUID 一样是一级技术泄漏，
                                // 用户没有任何事要拿它做，删掉（本文件 KDoc 的同一条理由）。
                                Surface(color = MaterialTheme.colorScheme.surface, shape = RelayShapes.extraSmall) {
                                    Text(
                                        text = "${stringResource(Res.string.chat_group_owner_label)} $ownerNick",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = LocalAppSpacing.current.xxs),
                                        // 长群主昵称会把徽标撑成多行、把描述/公告区顶乱 ——
                                        // 成员行的名字都做过让位，这处是同一类缺陷的漏网点。
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    val desc = group.description
                    if (!desc.isNullOrBlank()) {
                        Row(Modifier.padding(top = LocalAppSpacing.current.xs)) {
                            Text(
                                text = "${stringResource(Res.string.chat_group_desc_label)}: ",
                                // 群描述与公告是**用户要读的主要内容**：11sp 的 labelSmall
                                // 是注释字号，主体信息统一 12sp 的 bodySmall。
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(text = desc, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val ann = group.announce
                    if (!ann.isNullOrBlank()) {
                        Row {
                            Text(
                                text = "${stringResource(Res.string.chat_group_announce_label)}: ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(text = ann, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // 「本项目目录」行（§4.1）：宿主显示本机指针指向的目录名 +「更换/设置」；
                    // 非宿主只显示席位声明投影或「由宿主授权」，路径永远不上屏。
                    // 与授权面板顶部共用 [GroupProjectDirectoryRow] 一份实现（AGENTS §2.6）。
                    GroupProjectDirectoryRow(
                        groupId = group.groupId,
                        hostDirectoryPort = hostDirectoryPort,
                        declaredTitle = declaredDirectoryTitle,
                        onSetDirectory = onSetProjectDirectory,
                        modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.chat_group_id_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 36 字符的 groupId 曾经**原样上屏**：真机宽度的对话框里
                        // 它占掉整行，是纯技术泄漏（本文件 KDoc 自己写过这条）。
                        // 收进「复制群 ID」次级动作 —— 需要对 ID 的人（报障、对账）
                        // 仍然一键可取，不关心的人不再被迫看一串十六进制。
                        var copied by remember { mutableStateOf(false) }
                        LaunchedEffect(copied) {
                            if (copied) {
                                delay(2_000)
                                copied = false
                            }
                        }
                        val clipboard = LocalClipboardManager.current
                        AppTextButton(
                            text = stringResource(
                                if (copied) Res.string.chat_group_id_copied else Res.string.chat_group_copy_id,
                            ),
                            onClick = {
                                clipboard.setText(AnnotatedString(group.groupId))
                                copied = true
                            },
                            size = AppButtonSize.Small,
                        )
                    }
                    // 入口**另起一行**，不跟 groupId 挤同一行：groupId 是 36 字符定宽
                    // （等宽字体约 464px），手机宽度下同一行只剩 ~95px，按钮的文字会被
                    // 压成 0 宽 —— 真机 uiautomator 里能看到一个 `clickable=true`、
                    // 却**没有任何文字**的 126x344 空块（真机实测，
                    // 1080px 宽的对话框：[802,806]-[928,1150]），界面上就是一个空按钮，
                    // 谁也点不出「编辑群公告与简介」。
                    if (canManageMembers && onUpdateMeta != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            AppTextButton(
                                text = stringResource(Res.string.chat_group_edit_meta),
                                onClick = { showEditMetaDialog = true },
                                size = AppButtonSize.Small,
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = LocalAppSpacing.current.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (rows.agents.isNotEmpty()) {
                        stringResource(Res.string.workspace_member_section_humans, rows.humans.size)
                    } else {
                        stringResource(Res.string.chat_group_members_header, group.members.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onAddMembers != null) {
                    AppTextButton(
                        text = stringResource(Res.string.chat_group_add_member),
                        onClick = { showAddDialog = true },
                        size = AppButtonSize.Small,
                    )
                }
            }
        }

        items(rows.humans, key = { it.id }) { identity ->
            val isMe = identity.isSelf
            val peer = peerMap[identity.id]
            val isOwner = identity.id == group.ownerId
            val isAdmin = group.adminIds?.contains(identity.id) == true
            GroupMemberRow(
                identity = identity,
                // 自己恒在线（本机就是自己）。
                online = if (isMe) true else identity.online == true,
                showStatus = !isMe,
                // 副行是**组织路径**；资料没到时不编一个组织，只如实说「未获取到资料」。
                subtitle = memberOrgPath(peer)
                    ?: if (identity.needsProfileNote) profileUnavailableText else null,
                // 长按头像 = 直接 @（自己的头像不给这个入口）。
                onLongPressMention = onMentionMember
                    ?.takeIf { identity.canMention }
                    ?.let { mention -> { mention(identity) } },
                badges = {
                    if (isMe) MemberBadge(meText, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
                    if (isOwner) {
                        MemberBadge(stringResource(Res.string.chat_group_owner_label), SurfaceTiers.Raised, MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (isAdmin) {
                        MemberBadge(stringResource(Res.string.chat_group_admin_label), SurfaceTiers.Raised, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                // 移出成员按钮（仅管理员/群主且不是自己、不是群主）
                trailing = {
                    if (canManageMembers && !isMe && !isOwner && onRemoveMember != null) {
                        AppTextButton(
                            text = stringResource(Res.string.chat_group_remove_member),
                            onClick = { memberToRemove = identity.id },
                            tone = AppButtonTone.Danger,
                            size = AppButtonSize.Small,
                        )
                    }
                },
            )
        }

        if (rows.agents.isNotEmpty() || canManageMembers) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LocalAppSpacing.current.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.workspace_member_section_agents, rows.agents.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // **这个按钮是「授权管理」，不是「加人」。**
                    //
                    // 入口画不画的判据与旧版同形：有动作通路（可编辑）或群里有 agent
                    // （非宿主也值得打开看只读投影）。面板本体见 SetGroupAgentWorkspacesDialog。
                    if (onStationAgent != null || rows.agents.isNotEmpty()) {
                        AppTextButton(
                            text = stringResource(Res.string.group_auth_entry),
                            onClick = { showAuthDialog = true },
                            size = AppButtonSize.Small,
                        )
                    }
                }
            }

            // Agent 的**名字来自 agentKind / alias**（`DSH`），不是宿主机器的昵称：
            // 用户原话「入住智能体显示的名字是桌面端的（如 mac mini），而不是 dsh」。
            // 宿主成员与"只在消息里说过话"的 agent 由解析器合并去重。
            items(rows.agents, key = { it.id }) { identity ->
                val peer = peerMap[identity.id]
                // 该 agent 的**本机席位**（团队编制入口的编辑对象）与团队徽标的聚合口径。
                // 身份键走 identity 里的 host + kind —— 与 representativeSeat 同一条判据。
                val identityHost = AgentSenderId.parse(identity.id)?.first
                val identityAgent = identity.agentKind
                    ?: AgentSenderId.parse(identity.id)?.second
                val rowSeats = remember(group, identity.id) {
                    val host = identityHost
                    val agent = identityAgent
                    if (host == null || agent == null) emptyList()
                    else seatsOfAgent(group.virtualAgents, host, agent)
                }
                val teamSeat = remember(rowSeats) { rowSeats.firstOrNull { it.isTeam } }
                // 这个 agent 的席位声明来自**本机**吗？只有本机才能撤回自己的入驻
                // （那是机主主权，与「执行名单」同一方向）。
                //
                // **匹配要比 `id` 里的 host + kind，不能比 `name`。**
                // `SenderIdentity.id` 的形态是 `agent:<host>:<kind>`
                // （如 `agent:a1b2c3d4-…:dsh`），而 `VirtualAgentMember`
                // 记的是 `hostNodeId` + `agentId`/`name`。
                // 拿 `name`（`dsh-teahouse-mobile`）去比 `id` 永远不等，
                // 于是「旧 agent 没有移除入口」—— 真机上就是这个症状。
                // 席位定位走 core 的 `seatsOfAgent`（与代表席位同一套判据）；
                // 撤回按 agentId（D4 的席位身份键）。
                val ownSeat = rowSeats.firstOrNull { it.hostNodeId == selfNodeId }
                GroupMemberRow(
                    identity = identity,
                    online = identity.online == true,
                    // **副行显示「打什么能叫到它」。**
                    //
                    // 主行是 `DSH`（agentKind 大写），而真正能 @ 的是 token
                    // `dsh-teahouse-mobile` —— 两者长得不一样，用户看不出关系。
                    // 真机反馈：「界面显示的 dsh 跟我点击邀请的 agent 是一个吗，
                    // 看起来名字不一样？」。把 token 写在副行，一眼就知道该打什么。
                    subtitle = identity.mentionToken,
                    onLongPressMention = onMentionMember
                        ?.takeIf { identity.canMention }
                        ?.let { mention -> { mention(identity) } },
                    badges = {
                        // 过去硬编码 "🤖 Agent Bot"：英文界面印英文是碰巧、中文界面
                        // 也印英文，且 emoji 是私货。用户可见文案只住 strings.xml。
                        MemberBadge(
                            stringResource(Res.string.group_member_agent_badge),
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.tertiary,
                        )
                        // 「团队 (N)」徽标按该 agent 的席位聚合（§2.1）：任一席位
                        // isTeam 即显示，N = 那条团队的组员数。
                        if (teamSeat != null) {
                            MemberBadge(
                                stringResource(Res.string.team_roster_badge, teamSeat.teamMembers.size),
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    // 「移出」分两种，**不能合成一个按钮**：
                    //
                    //  · agent 是 `virtualAgents` 里的一条本机席位声明（不是 `group.members`
                    //    成员）：移出 = 撤回入驻，走 `onRemoveGroupAgent`（按 agentId 摘除，
                    //    D4 的席位身份键）。原先这里要求 `identity.id in group.members` ——
                    //    agent 永远不满足，于是**旧 agent 没有任何移除入口**。
                    //  · 宿主节点是真正的群成员，移出 = 让群主踢人，走 `onRemoveMember`。
                    //
                    // 双动作**竖排**：两颗 Small 文字按钮横排时（团队编制 ~68dp + 移出
                    // ~48dp），360dp 宽的内容区里名字列只剩 ~130dp，4-5 字的 agentKind
                    // 会被截成 1-2 字 —— 行的语义（这是哪个 agent）被动作挤没了。
                    trailing = {
                        val rosterEntry: (@Composable () -> Unit)? =
                            if (onSetTeamRoster != null && rowSeats.isNotEmpty()) {
                                {
                                    AppTextButton(
                                        text = stringResource(Res.string.team_roster_button),
                                        onClick = { teamRosterFor = identity to rowSeats },
                                        size = AppButtonSize.Small,
                                    )
                                }
                            } else {
                                null
                            }
                        val removeEntry: (@Composable () -> Unit)? = when {
                            ownSeat != null && onRemoveGroupAgent != null -> {
                                {
                                    AppTextButton(
                                        text = stringResource(Res.string.chat_group_remove_member),
                                        onClick = { agentToRevoke = ownSeat.agentId },
                                        tone = AppButtonTone.Danger,
                                        size = AppButtonSize.Small,
                                    )
                                }
                            }
                            canManageMembers && onRemoveMember != null &&
                                identity.id in group.members -> {
                                {
                                    AppTextButton(
                                        text = stringResource(Res.string.chat_group_remove_member),
                                        onClick = { memberToRemove = identity.id },
                                        tone = AppButtonTone.Danger,
                                        size = AppButtonSize.Small,
                                    )
                                }
                            }
                            else -> null
                        }
                        when {
                            rosterEntry != null && removeEntry != null -> {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xxs),
                                    horizontalAlignment = Alignment.End,
                                ) {
                                    rosterEntry()
                                    removeEntry()
                                }
                            }
                            rosterEntry != null -> rosterEntry()
                            removeEntry != null -> removeEntry()
                        }
                    },
                )
            }
        }

        item {
            Text(
                text = stringResource(Res.string.chat_group_info_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
            )
        }

        if (footer != null) {
            item(key = "group_info_footer") { footer() }
        }
    }

    // 授权管理面板（项目目录决策稿 §4.1/§4.2）：顶部本群项目目录 + 本机 agent 入驻清单。
    // 宿主可编辑（入驻 / 去暴露 / 移除 / 设指针，动作即时成像）；其它成员只读，
    // 看声明投影 —— 路径本来就不进协议，非宿主根本没有。
    if (showAuthDialog) {
        SetGroupAgentWorkspacesDialog(
            group = group,
            selfNodeId = selfNodeId,
            localWorkspaces = localWorkspaces,
            hostDirectoryPort = hostDirectoryPort,
            declaredDirectoryTitle = declaredDirectoryTitle,
            onStation = onStationAgent,
            onReparse = onReparseSeats,
            onRemove = onRemoveGroupAgent,
            onSetDirectory = onSetProjectDirectory,
            onDismiss = { showAuthDialog = false },
        )
    }

    // 团队编制对话框（§2.2）。目标在 agent 行上选中；默认席位用「代表席位」判据
    //（与行显示名、撤回按钮同源 —— representativeSeat，§3.5 的单一判据）。
    // 选中按 **agentId**（D4 的席位身份键；defaultWsId 只是执行投影，可为 null）。
    // token 池 = 全部声明的名字/agentId + 组员名 + mentionKeys（组员名 v1 不在
    // mention 池里，但要拦它撞既有 token —— 未来它要成为 token）。
    val rosterTarget = teamRosterFor
    val saveTeam = onSetTeamRoster
    if (rosterTarget != null && saveTeam != null) {
        val (targetIdentity, targetSeats) = rosterTarget
        val targetHost = AgentSenderId.parse(targetIdentity.id)?.first
        val targetAgent = targetIdentity.agentKind ?: AgentSenderId.parse(targetIdentity.id)?.second
        val repSeatAgentId = if (targetHost != null && targetAgent != null) {
            representativeSeat(group.virtualAgents, targetHost, targetAgent)?.agentId
        } else {
            null
        }
        val takenTokens = remember(group, ctx) {
            buildSet {
                group.virtualAgents.orEmpty().forEach { va ->
                    add(va.name)
                    add(va.agentId)
                    va.teamMembers.forEach { add(it.name) }
                }
                addAll(rows.mentionKeys)
            }.filter { it.isNotBlank() }.toSet()
        }
        TeamRosterDialog(
            agentName = targetIdentity.displayName,
            seats = targetSeats,
            initialSeatAgentId = repSeatAgentId,
            takenTokens = takenTokens,
            showHostManagedNotice = showTeamHostManagedNotice,
            selfNodeId = selfNodeId,
            groupId = group.groupId,
            executionPort = teamExecutionPort,
            onDismiss = { teamRosterFor = null },
            onSave = { agentId, roster ->
                teamRosterFor = null
                saveTeam(agentId, roster)
            },
        )
    }

    // 邀请新成员对话框
    val addMembers = onAddMembers
    if (showAddDialog && addMembers != null) {
        AddGroupMembersDialog(
            peers = peers,
            existingMembers = group.members,
            onDismiss = { showAddDialog = false },
            onConfirm = { toAdd ->
                showAddDialog = false
                addMembers(toAdd)
            },
        )
    }

    // 确认移出成员对话框
    val removeMember = onRemoveMember
    val targetId = memberToRemove
    if (targetId != null && removeMember != null) {
        val targetNick = resolveNodeIdentity(targetId, ctx).displayName
        ConfirmRemoveMemberDialog(
            targetNick = targetNick,
            onDismiss = { memberToRemove = null },
            onConfirm = {
                memberToRemove = null
                removeMember(targetId)
            },
        )
    }

    // 确认撤回本机 agent 的席位（与移出成员同款确认框；撤回动作按 agentId 走
    // onRemoveGroupAgent —— 席位身份键，见 D4）。
    val removeAgent = onRemoveGroupAgent
    val seatToRevoke = agentToRevoke
    if (seatToRevoke != null && removeAgent != null) {
        val revoked = group.virtualAgents.orEmpty().firstOrNull {
            it.hostNodeId == selfNodeId && it.agentId.equals(seatToRevoke, ignoreCase = true)
        }
        ConfirmRemoveMemberDialog(
            targetNick = revoked?.name ?: seatToRevoke,
            onDismiss = { agentToRevoke = null },
            onConfirm = {
                agentToRevoke = null
                removeAgent(seatToRevoke)
            },
        )
    }

    // 编辑群简介与群公告对话框
    val updateMeta = onUpdateMeta
    if (showEditMetaDialog && updateMeta != null) {
        EditGroupMetaDialog(
            initialDesc = group.description.orEmpty(),
            initialAnnounce = group.announce.orEmpty(),
            onDismiss = { showEditMetaDialog = false },
            onConfirm = { desc, announce ->
                showEditMetaDialog = false
                updateMeta(group.groupId, desc, announce)
            },
        )
    }
}

/**
 * 成员行（人类与 agent **共用这一份**）：同一块像素不该有两份实现。
 *
 * 长按头像 = 直接 @：入口挂在头像上而不是整行 —— 整行点击留给"以后可能有的成员详情"，
 * 一个手势绑两件事迟早会打架。token 由调用方从解析结果里取，这里不拼名字。
 */
@Composable
private fun GroupMemberRow(
    identity: SenderIdentity,
    online: Boolean,
    subtitle: String?,
    onLongPressMention: (() -> Unit)?,
    badges: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    showStatus: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LocalAppSpacing.current.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
    ) {
        Box {
            Box(
                modifier = if (onLongPressMention != null) {
                    // 只注册长按，不挂 onClick：combinedClickable 必须给单击槽，而
                    // "点了没反应"的空实现会进 DeadButtonGuard 的欠账簿（那个白名单只收
                    // 三处有理由的用法，且每文件一处）。成员行本来就没有单击动作。
                    // 补齐 semantics 声明，无障碍服务与自动化测试（uiautomator）均可识别为可长按节点。
                    Modifier
                        .semantics {
                            onLongClick(label = "Mention " + identity.displayName) {
                                onLongPressMention()
                                true
                            }
                        }
                        .pointerInput(identity.displayName, onLongPressMention) {
                            detectTapGestures(onLongPress = { onLongPressMention() })
                        }
                } else {
                    Modifier
                },
            ) {
                PeerAvatar(
                    name = identity.displayName,
                    isAgent = identity.isAgent,
                    size = 32.dp,
                    avatar = identity.avatar,
                )
            }
            // 状态归头像，与 ConversationRow 同一套：在线圆点压在头像右下角。
            // 自己恒在线，不画这个点 —— 它回答的是"这个人在不在"，不是"我在不在"。
            if (showStatus) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(LocalAppSpacing.current.lg14)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { StatusDot(online) }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
            ) {
                Text(
                    text = identity.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // **名字必须让位给徽标**（weight(1f, fill=false)）：Row 先量非加权
                    // 子项 —— 徽标先拿自己的宽度，名字拿剩下的并按需省略。不加权时
                    // 名字先按偏好宽度占满，徽标被挤出裁剪边界：真机（411dp 宽的对话框）
                    // 上「团队 (3)」徽标整个不可见，数据明明是团队（AGENTS §3 的
                    // 「看得见才算修好」）。
                    modifier = Modifier.weight(1f, fill = false),
                )
                badges()
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/** 成员行上的一枚小徽标（我 / 群主 / 管理员 / Agent）。 */
@Composable
private fun MemberBadge(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Surface(color = container, shape = RelayShapes.extraSmall) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = 1.dp),
        )
    }
}

/**
 * 群成员副行 = **组织路径**（公司 · 部门 · 团队）。三个字段都空时返回 null，
 * 调用点据此整行不画 —— 副行不是"必须占一行"，而是一行**有语义**的内容。
 *
 * 为什么不再显示成员 id：完整 UUID 对用户零信息量，是界面技术化的一级泄漏。
 * 在线状态改由头像右下角的圆点表达（见 [StatusDot] 与 ConversationRow 的做法）。
 * 三个字段与 [com.kapp.tenon.domain.model.PeerUi.subtitle] 同源，但不回落 IP / 主机名 ——
 * 地址同样是实现细节，不该出现在成员行上。
 */
internal fun memberOrgPath(peer: PeerUi?): String? =
    listOfNotNull(
        peer?.company?.takeIf { it.isNotBlank() },
        peer?.dept?.takeIf { it.isNotBlank() },
        peer?.team?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
