// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.relay.core.protocol.declaredWorkspaceOfSender
import app.relay.core.protocol.GroupMeta
import app.relay.generated.resources.Res
import app.relay.generated.resources.chat_group_add_member
import app.relay.generated.resources.chat_group_admin_label
import app.relay.generated.resources.chat_group_announce_label
import app.relay.generated.resources.chat_group_desc_label
import app.relay.generated.resources.chat_group_edit_meta
import app.relay.generated.resources.chat_group_id_label
import app.relay.generated.resources.chat_group_info_hint
import app.relay.generated.resources.chat_group_info_sub
import app.relay.generated.resources.chat_group_leave
import app.relay.generated.resources.chat_group_me
import app.relay.generated.resources.chat_group_members_header
import app.relay.generated.resources.chat_group_none
import app.relay.generated.resources.chat_group_online
import app.relay.generated.resources.chat_group_owner_label
import app.relay.generated.resources.chat_group_remove_member
import app.relay.generated.resources.common_close
import app.relay.generated.resources.common_profile_unavailable
import app.relay.generated.resources.workspace_member_add_agent
import app.relay.generated.resources.workspace_member_section_agents
import app.relay.generated.resources.workspace_member_section_humans
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.RelayShapes
import app.relay.app.SenderIdentity
import app.relay.app.SenderIdentityContext
import app.relay.app.groupParticipantRows
import app.relay.app.model.PeerUi
import app.relay.app.resolveNodeIdentity
import app.relay.ui.theme.SurfaceTiers
import app.relay.app.model.WorkspaceUi

/**
 * 群信息与成员面板（对话框形态）：展示群名、公告、简介、群主与管理员、成员在线状态，
 * 并支持邀请新成员与群主/管理员移出成员、退出群聊。
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
     * 本机可以暴露给这个群的 workspace（每个就是一个可被 @ 的成员）。
     *
     * 暴露单位是 `(agentId, wsId)`：一台设备可以装多个 agent，不同 agent 也可能
     * 操作同一个 workspace。空 = 不提供入口（桌面壳/截图夹具）。
     */
    localWorkspaces: List<WorkspaceUi> = emptyList(),
    /**
     * 「添加智能体」确认后的回调：参数是**要暴露的 wsId 列表**。
     *
     * null = 不提供该入口。与 [onAddMembers] 分开：那个是加**人**，这个暴露**本机项目**。
     */
    onExposeWorkspaces: ((List<String>) -> Unit)? = null,
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

    var showAddDialog by remember { mutableStateOf(false) }
    // 「本机 agent 入驻」对话框。与加人分开：那个加**人**，这个暴露**本机项目**。
    var showExposeDialog by remember { mutableStateOf(false) }
    var showEditMetaDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<String?>(null) }
    val isSelfOwner = group.ownerId == selfNodeId || (group.ownerId == null && group.creatorId == selfNodeId)
    val isSelfAdmin = group.adminIds?.contains(selfNodeId) == true
    val canManageMembers = isSelfOwner || isSelfAdmin

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
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
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
                                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RelayShapes.extraSmall) {
                                            Text(
                                                text = "rev ${group.rev}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = LocalAppSpacing.current.xxs),
                                            )
                                        }
                                        Surface(color = MaterialTheme.colorScheme.surface, shape = RelayShapes.extraSmall) {
                                            Text(
                                                text = "${stringResource(Res.string.chat_group_owner_label)} $ownerNick",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = LocalAppSpacing.current.xs, vertical = LocalAppSpacing.current.xxs),
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
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(text = desc, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            val ann = group.announce
                            if (!ann.isNullOrBlank()) {
                                Row {
                                    Text(
                                        text = "${stringResource(Res.string.chat_group_announce_label)}: ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(text = ann, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${stringResource(Res.string.chat_group_id_label)}: ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = group.groupId,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    )
                                }
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
                            // **这个按钮是「加 agent」，不是「加人」。**
                            //
                            // 原先它写的文案是「邀请新智能体入驻」，点开却弹出加成员的对话框
                            // （`showAddDialog`）—— 而真正的暴露入口 `onExposeWorkspaces`
                            // 从未被渲染过（全文只出现一次，就是声明）。
                            // 于是「群内加减 agent」这条能力在界面上根本不存在。
                            if (onExposeWorkspaces != null) {
                                AppTextButton(
                                    text = stringResource(Res.string.workspace_member_add_agent),
                                    onClick = { showExposeDialog = true },
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
                                MemberBadge(
                                    "🤖 Agent Bot",
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    MaterialTheme.colorScheme.tertiary,
                                )
                            },
                            // 「移出」分两种，**不能合成一个按钮**：
                            //
                            //  · agent 是 `virtualAgents` 里的一条声明（不是 `group.members` 成员）；
                            //    移出 = 撤回那条暴露，走 `onExposeWorkspaces`（传不含它的列表）。
                            //    原先这里要求 `identity.id in group.members` —— agent 永远不满足，
                            //    于是**旧 agent 没有任何移除入口**，暴露了就撤不掉。
                            //  · 宿主节点是真正的群成员，移出 = 让群主踢人，走 `onRemoveMember`。
                            trailing = {
                                // 这个 agent 的暴露来自**本机**吗？只有本机才能撤回自己的暴露
                                // （那是机主主权，与「执行名单」同一方向）。
                                //
                                // **匹配要比 `id` 里的 host + kind，不能比 `name`。**
                                // `SenderIdentity.id` 的形态是 `agent:<host>:<kind>`
                                // （如 `agent:a1b2c3d4-…:dsh`），而 `VirtualAgentMember`
                                // 记的是 `hostNodeId` + `agentId`/`name`。
                                // 拿 `name`（`dsh-teahouse-mobile`）去比 `id` 永远不等，
                                // 于是「旧 agent 没有移除入口」—— 真机上就是这个症状。
                                // 规则抽在 core 的 `declaredWorkspaceOfSender`（可测；全仓一份实现）。
                                val ownWsId = declaredWorkspaceOfSender(
                                    senderId = identity.id,
                                    declaredAgents = group.virtualAgents,
                                    hostNodeId = selfNodeId,
                                )
                                if (ownWsId != null && onExposeWorkspaces != null) {
                                    AppTextButton(
                                        text = stringResource(Res.string.chat_group_remove_member),
                                        onClick = {
                                            // 撤回 = 传一份**不含它**的列表给同一个回调。
                                            val keep = group.virtualAgents.orEmpty()
                                                .filter { it.hostNodeId == selfNodeId }
                                                .mapNotNull { it.wsId }
                                                .filterNot { it == ownWsId }
                                            onExposeWorkspaces(keep)
                                        },
                                        tone = AppButtonTone.Danger,
                                        size = AppButtonSize.Small,
                                    )
                                } else if (canManageMembers && onRemoveMember != null &&
                                    identity.id in group.members
                                ) {
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
                }

                item {
                    Text(
                        text = stringResource(Res.string.chat_group_info_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = LocalAppSpacing.current.xs),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm)) {
                if (onLeaveGroup != null) {
                    // 原型 `.btn.danger-o`（描边 + `--on-error-c` 文字）。
                    RelayDialogButton(
                        text = stringResource(Res.string.chat_group_leave),
                        onClick = { showLeaveConfirm = true },
                        danger = true,
                    )
                }
                RelayDialogButton(text = stringResource(Res.string.common_close), onClick = onDismiss)
            }
        },
    )

    // 暴露本机 agent 对话框（群内加减 agent 的入口）。
    val expose = onExposeWorkspaces
    if (showExposeDialog && expose != null) {
        ExposeAgentDialog(
            localWorkspaces = localWorkspaces,
            exposedWsIds = group.virtualAgents.orEmpty()
                .filter { it.hostNodeId == selfNodeId }
                .mapNotNull { it.wsId }
                .toSet(),
            onDismiss = { showExposeDialog = false },
            onConfirm = { chosen -> expose(chosen) },
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

    // 确认退出群聊对话框
    val leaveGroup = onLeaveGroup
    if (showLeaveConfirm && leaveGroup != null) {
        ConfirmLeaveGroupDialog(
            onDismiss = { showLeaveConfirm = false },
            onConfirm = {
                showLeaveConfirm = false
                onDismiss()
                leaveGroup()
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
                    Modifier.pointerInput(identity.displayName, onLongPressMention) {
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
 * 三个字段与 [app.relay.app.model.PeerUi.subtitle] 同源，但不回落 IP / 主机名 ——
 * 地址同样是实现细节，不该出现在成员行上。
 */
internal fun memberOrgPath(peer: PeerUi?): String? =
    listOfNotNull(
        peer?.company?.takeIf { it.isNotBlank() },
        peer?.dept?.takeIf { it.isNotBlank() },
        peer?.team?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
