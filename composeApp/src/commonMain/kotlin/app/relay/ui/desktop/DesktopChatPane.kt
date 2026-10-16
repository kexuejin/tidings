// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import app.relay.app.RelayUiState
import app.relay.ui.components.GroupInfoDialog
import app.relay.generated.resources.chat_group_me
import app.relay.generated.resources.chat_group_members
import app.relay.generated.resources.chat_group_members_action
import app.relay.generated.resources.chat_group_owner_label
import app.relay.generated.resources.chat_group_rev
import app.relay.core.protocol.GroupMeta
import app.relay.feature.chat.ChatScreen
import app.relay.feature.chat.ChatScreenActions
import app.relay.feature.chat.ChatScreenChrome
import app.relay.feature.chat.ChatScreenGroupActions
import app.relay.feature.chat.ChatScreenState
import app.relay.ui.ContentWidths
import app.relay.ui.EmptyState
import app.relay.ui.components.KindBadge
import app.relay.ui.components.PeerAvatar
import app.relay.ui.components.ScreenRequestConfirmDialog
import app.relay.ui.components.StatusDot
import app.relay.ui.components.TopBarIconAction
import app.relay.generated.resources.Res
import app.relay.generated.resources.ic_screen_share
import app.relay.generated.resources.screen_remote_view_action
import app.relay.generated.resources.desktop_action_copy_node_id_short
import app.relay.generated.resources.desktop_action_list
import app.relay.generated.resources.desktop_action_open_in_new_window
import app.relay.generated.resources.chat_group_online
import app.relay.generated.resources.ic_copy
import app.relay.generated.resources.ic_external
import app.relay.generated.resources.ic_groups
import app.relay.generated.resources.ic_list
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing
import app.relay.app.model.PeerUi


@Composable
public fun ChatPane(
    state: RelayUiState,
    controller: DesktopPaneActions,
    selectedPeerId: String?,
    sizeClass: DesktopWindowSizeClass,
    now: Long,
    onSelect: (String) -> Unit,
    onGoToPairing: () -> Unit,
    onCopyPeerId: (String) -> Unit,
    onOpenInNewWindow: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /** 输入框预置草稿，只给截图门禁用（见 [ChatScreen.initialDraft]）。 */
    initialDraft: String = "",
) {
    // 群没有 PeerUi（协议里群不是节点），所以群会话要用占位对端。
    // 与手机端 `ChatScreen` 同一条路径：`chatPeer` 靠历史兜底，`groupPeer` 靠成员表。
    val group = state.groupOfConv(selectedPeerId)
    // **群优先**：`chatPeer` 对任何有历史的会话都回一个只有 nodeId 的占位，
    // 群会话键是 `group:<groupId>`，先问它就会把「group:g-」画到头上。
    // 手机端 `ChatScreen` 是同一条路径、同一个理由。
    val peer = state.groupPeer(group) ?: state.chatPeer(selectedPeerId)
    val chat: @Composable (Modifier) -> Unit = { chatModifier ->
        ChatScreen(
            data = ChatScreenState(
                state = state,
                peerId = selectedPeerId,
                cacheDir = null,
            ),
            actions = ChatScreenActions(
                onSend = controller::sendToConversation,
                onOpenAgentConsole = onSelect,
                onDismissBanner = controller::dismissBanner,
                onAttachFile = controller::pickAndSendFiles,
                onAcceptTransfer = controller::acceptTransfer,
                onDeclineTransfer = controller::declineTransfer,
                // 撤回自己发出的、还在传的文件。以前这条没接：桌面上文件卡片根本没有取消按钮，
                // 点了「发送」就只能等它自己失败（审计 P1-13）。移动端那条路一直有。
                onCancelTransfer = controller::cancelTransfer,
                onResumeTransfer = controller::resumeTransfer,
                onSearchInConversation = { query, filter ->
                    selectedPeerId?.let { convId -> controller.searchInConversation(convId, query, filter) } ?: emptyList()
                },
                onSendPk = controller::sendPk,
                // 撤回与戳一碰：两条都是协议动作，手机与桌面共用同一个聊天页，所以一起接上。
                onRecallMessage = controller::recall,
                onForwardMessage = controller::forwardMessage,
                // 群与 Agent 的判据在 ChatScreen 里面，这里不必再判一次。
                onNudge = controller::nudge,
                onRequestScreenView = controller::requestScreenView,
                onRespondApproval = controller::respondApproval,
            ),
            groupActions = ChatScreenGroupActions(
                onAddGroupMembers = controller::addGroupMembers,
                onRemoveGroupMember = controller::removeGroupMember,
                onUpdateGroupMeta = controller::updateGroupMeta,
                onLeaveGroup = { groupId ->
                    controller.leaveGroup(groupId)
                    onBack?.invoke()
                },
            ),
            chrome = ChatScreenChrome(
                receivedFileActions = controller.receivedFileActions,
                onBack = onBack,
                // 收到文件的开关/分享入口。null = 本平台没有这条出口（桌面、iOS），
                // 聊天页据此不画按钮；Android 传进来的是真实现。
                showHeader = false,
                // 桌面壳自己画右栏头（见 DesktopChatHeader）：手机端那个头的按钮是
                // 「返回 / 会话列表」，桌面上要的是「列表 / 复制节点 ID / 在新窗口打开」，
                // 语义不同，不能靠 showHeader = true 复用。
                //
                // 气泡比例按**外壳**定，不按窗口宽度算（推导见 feature/chat/BubbleWidth.kt）：
                // 这条路服务桌面壳，平板两栏也走同一条（App.kt 的 twoPane → DesktopShell →
                // 这里），而原型里 tabhost 那档同样是 form:'desktop' → 62%。所以一档到底，不必再分。
                bubbleFraction = ContentWidths.BUBBLE_FRACTION_WIDE,
                modifier = chatModifier,
                initialDraft = initialDraft,
                composerSlot = if (peer == null) {
                    null
                } else {
                    { draft, onDraftChange ->
                        DesktopComposer(peer, draft, onDraftChange, controller, isGroup = group != null)
                    }
                },
            ),
        )
    }
    if (peer == null) {
        // 一个会话都没选中（原型 `screenDNoSel`，`relay.html:5145-5151`）：桌面自己画居中的那张
        // （见 [DesktopNoSelectionEmpty] 的 KDoc：共享 `EmptyState` 是顶对齐的，手机端继续用它）。
        // 选中了会话却查不到对端（只剩历史）时仍走 `ChatScreen` 那条路，行为不变。
        if (selectedPeerId == null) DesktopNoSelectionEmpty(modifier) else chat(modifier)
        return
    }
    var showGroupInfo by remember(selectedPeerId) { mutableStateOf(false) }
    Column(modifier) {
        DesktopChatHeader(
            peer = peer,
            compact = sizeClass == DesktopWindowSizeClass.Compact,
            now = now,
            // 「列表」只有 Compact 用得上：宽窗下侧栏一直可见，原型也不给收起入口。
            onList = onBack,
            // 复制节点 ID 只对"是一个节点"的会话有意义：群不是节点，它的占位
            // `nodeId` 是会话键 `group:<groupId>`，复制出去对方拿去填地址只会失败。
            onCopyId = if (group == null) ({ onCopyPeerId(peer.nodeId) }) else null,
            onOpenInNewWindow = onOpenInNewWindow?.let { open -> { open(peer.nodeId) } },
            onRequestScreenView = controller::requestScreenView
                .takeIf { group == null && !peer.isAgent && peer.online }
                ?.let { req -> { req(peer.nodeId) } },
            avatarBytes = peer.avatarHash?.let { state.avatarCache[it] },
            // 「戳一碰」不在这里传：按原型它落在工具行（`DesktopComposer` 的 `ToolIcon`），
            // 判据仍是「只给单聊、且不是 Agent」（判断写在 `DesktopComposer` 里，
            // 与这一行的 `onRequestScreenView` 同源）。
            group = group,
            memberOnlineCount = group?.let { state.memberOnlineCount(it) } ?: 0,
            // 群主名：**本机群主先比对 `selfNodeId`，不能只查节点表** ——
            // `state.peers` 是远端节点表，本机不在里面，所以自己建的群在这里会静默丢掉
            // 整段「群主 X」（生产行为，不只是截图夹具）。回落链与手机端
            // `GroupInfoDialog` 对齐：本机用自己设的昵称（取不到再退回
            // 「我」），远端查不到就退 `nodeId` 前 8 字符，而不是整段消失。
            groupOwnerName = group?.ownerId?.let { owner ->
                when (owner) {
                    state.selfNodeId -> state.settings.nick.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.chat_group_me)
                    else -> state.peers.firstOrNull { it.nodeId == owner }?.displayName
                        // 空昵称要当"查不到"处理：否则会渲染出「群主 」这样半截的标签
                        // （远端刚上线、资料还没同步过来时 displayName 可能是空串）。
                        ?.takeIf { it.isNotBlank() }
                        ?: owner.take(8)
                }
            },
            onViewGroupInfo = if (group != null) ({ showGroupInfo = true }) else null,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        chat(Modifier.weight(1f).fillMaxWidth())
        if (showGroupInfo && group != null) {
            GroupInfoDialog(
                group = group,
                selfNodeId = state.selfNodeId,
                peers = state.peers,
                memberOnlineCount = state.memberOnlineCount(group),
                onDismiss = { showGroupInfo = false },
                onAddMembers = { members -> controller.addGroupMembers(group.groupId, members) },
                onRemoveMember = { member -> controller.removeGroupMember(group.groupId, member) },
                onUpdateMeta = controller::updateGroupMeta,
                onLeaveGroup = { controller.leaveGroup(group.groupId); onBack?.invoke() },
            )
        }
    }
}

/**
 * 会话详情头（原型稿的 `.dhead`）。
 *
 * 这一行是原型里右栏最显眼的元素：头像 + 名字 + 平台/团队小标签 + 第二行的
 * 「地址 · 连接状态 · 最后在线」，右侧三个动作。此前桌面壳把手机端 `ChatScreen`
 * 以 `showHeader = false` 复用，等于整行都不存在。
 *
 * 两处**有意偏离原型稿**，都是为了不说假话：
 * - 原型按钮写「复制指纹」，这里写「复制节点 ID」。`:core` 没有公钥概念，
 *   `agentNode.fingerprintOf` 是资料指纹（推进 profileRev 用），不是加密身份指纹。
 * - 原型第二行写「已连接 2 小时 14 分」，这里不写连接时长：`PeerUi` 没有记录
 *   建连时刻，编一个数字就是假数据；能诚实给出的是 `lastSeenAt`。
 */
@Composable
public fun DesktopChatHeader(
    peer: PeerUi,
    compact: Boolean,
    now: Long,
    onList: (() -> Unit)?,
    /**
     * 复制节点 ID。null = 不画这个入口。
     *
     * 群会话传 null：群不是节点，它的占位 `nodeId` 是会话键 `group:<groupId>`，
     * 复制出去对方拿去「填地址」只会失败。与「戳一碰」同一个判据、同一种做法。
     */
    onCopyId: (() -> Unit)? = null,
    /**
     * 在新窗口打开当前会话。null = 本设备没有多窗口能力（手机/平板），整个入口不画；
     * 留一个永远灰着的按钮等于告诉用户「这里有个功能，只是你点不了」。
     */
    onOpenInNewWindow: (() -> Unit)? = null,
    onRequestScreenView: (() -> Unit)? = null,
    /**
     * 截图接缝：让「请求查看屏幕」确认表单在首帧就是**打开**的。
     *
     * 与 `AddNodeSheet.initialScanMode`（feature/nodes/NodeSheets.kt 的 `AddNodeSheet.initialScanMode`）同一类参数 ——
     * 确认表单只在用户点那颗图标按钮之后才出现，没有这个初值，那个分支就永远没有帧盯着，
     * 截图门禁对它完全变瞎。默认 false = 生产行为（关的）。
     */
    initialConfirmOpen: Boolean = false,
    avatarBytes: ByteArray? = null,
    /**
     * 群会话的成员表；null = 这是一个私聊。
     *
     * 有它的时候这一行头的画法与私聊**不同**：不画在线圆点、不画能力徽标、
     * 不画戳一碰（群没有"这一个对端在不在"这回事），第二行改成成员数。
     * 判据是"有没有成员表"，不是"peer.isAgent 是不是假" —— 群的对端是个占位。
     */
    group: GroupMeta? = null,
    /** 群成员里当前在线的人数（不含自己）。只在 [group] 非空时有意义。 */
    memberOnlineCount: Int = 0,
    /** 群主的显示名（`group.ownerId` 在节点表里的 `displayName`）。取不到时第二行只省掉「群主 X」那一段。 */
    groupOwnerName: String? = null,
    onViewGroupInfo: (() -> Unit)? = null,
) {
    // 「请求查看屏幕」的确认表单：以前点那颗图标按钮**直接发请求**，原型 d30-reqscreen
    // 在中间有一张确认卡（说明 + 取消 / 发送请求）。改成先开表单、确认后才发。
    var screenRequestConfirmOpen by remember { mutableStateOf(initialConfirmOpen) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints {
            // 窄栏里（右栏只剩 Medium 的 ~456dp，或 Compact 单栏）平台/团队标签
            // 会把名字挤成一个「…」——非权重子节点先测量，先把宽度吃光。窄的时候
            // 整枚撤掉团队标签，地址行也允许折到第二行。
            // Compact 是单栏布局：560dp 窗口去掉左右留白后这一行仍能测到 ~560dp，
            // 只看 maxWidth 会漏判，所以把 compact 本身也算窄。
            val narrow = compact || maxWidth < 420.dp
            // 带文字的两个动作（「复制节点 ID」「在新窗口打开」）实测要吃掉 ~200dp：
            // Medium 右栏只剩 ~456dp、平板竖屏 ~496dp 时，它们会把名字和地址挤成
            // 「…」。低于 560dp 或单栏 Compact 就只留图标，Expanded 仍保持文字。
            val iconOnlyActions = compact || maxWidth < 560.dp
            Row(
                Modifier.fillMaxWidth().padding(horizontal = LocalAppSpacing.current.lg, vertical = LocalAppSpacing.current.md10),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md),
            ) {
                Box {
                    // 群的对端是占位（nodeId 就是会话键），它的 isAgent 是假的，
                    // 传 false 让头像画成群而不是机器人。
                    PeerAvatar(peer.displayName, peer.isAgent && group == null, 44.dp, isGroup = group != null, avatar = peer.avatar, avatarBytes = avatarBytes)
                    if (group == null) {
                        Box(Modifier.align(Alignment.BottomEnd)) { StatusDot(peer.online, 11.dp) }
                    }
                }
                // 权重给文字列，标签与按钮因此不会被长名字挤出窗口（溢出只会截断文字）。
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm6),
                    ) {
                        Text(
                            peer.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // `fill = false`：名字只占自己的宽，**后面那几枚标签紧贴名字**
                            // （原型 `.dh2` 里 `o.badge` 就写在标题旁边，
                            // `prototype/relay.html:4891`；d02-chat-group 的参考图上
                            // 「3/5 人在线」就贴在群名右侧）。默认的 `fill = true` 会把名字
                            // 撑满整行，把徽标推到行尾 —— 群头那次实测到「1/3 人在线」被推到了
                            // 「成员」按钮左边。`weight` 仍给出「名字最多占多少」的上限，
                            // 所以长名字照样省略号截断。
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // 群的占位对端没有平台/团队，也不该带能力徽标：那几枚标签
                        // 说的是"这台机器怎么连、能不能收文件"，对群都不成立。
                        if (group == null) {
                            if (peer.platform.isNotBlank()) HeadChip(peer.platform)
                            val teams = listOf(peer.company, peer.dept, peer.team)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                            if (!narrow && teams.isNotBlank()) HeadChip(teams)
                            KindBadge(peer.isAgent)
                        } else {
                            // 原型 d02-chat-group 的群头：在线人数是**名字旁边那枚描边 pill**，
                            // 第二行留给「N 位成员 · rev N · 群主 X」。窄栏里 pill 会挤掉名字，
                            // 所以和团队标签同一条规矩：窄时不画，人数在第二行仍有出处
                            // （第二行第一段就是成员数，在线数则退化成看成员列表）。
                            if (!narrow) {
                                HeadChip(
                                    stringResource(
                                        Res.string.chat_group_online,
                                        memberOnlineCount,
                                        group.members.size,
                                    ),
                                )
                            }
                        }
                    }
                    // 群头第二行。`rev` 与群主都是协议事实（`GroupMeta.rev` / `ownerId`），
                    // 不是编出来的进度数字；群主名取不到时（对方不在节点表里）那一段整段省掉，
                    // 不留一个"群主 …"的空位。
                    val groupLine = group?.let { meta ->
                        val owner = groupOwnerName?.takeIf { it.isNotBlank() }
                        val ownerLabel = stringResource(Res.string.chat_group_owner_label)
                        listOfNotNull(
                            stringResource(Res.string.chat_group_members, meta.members.size),
                            stringResource(Res.string.chat_group_rev, meta.rev),
                            owner?.let { "$ownerLabel $it" },
                        ).joinToString(" · ")
                    }
                    Text(
                        if (groupLine == null) {
                            peerHeaderLine(peer, now)
                        } else {
                            groupLine
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (narrow) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 动作之间是原型 `.dhead .acts{gap:7px}`（`prototype/relay.html:755`）：
                // 34dp 的 `.ibtn` 方块换成共享件之后必须显式给间距，否则方块贴死。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    // 「列表」只在 Compact 出现：宽窗下左侧列表本来就在旁边，多一个按钮
                    // 只会让右栏头变宽；原型给这个按钮挂的类就叫 `.onlyc`（index.html:185-186）。
                    if (compact && onList != null) {
                        if (iconOnlyActions) {
                            TopBarIconAction(Res.drawable.ic_list, stringResource(Res.string.desktop_action_list), onList)
                        } else {
                            ToolbarButton(stringResource(Res.string.desktop_action_list), painterResource(Res.drawable.ic_list), onList)
                        }
                    }
                    // 「戳一碰」**不在头部**：原型把它放在工具行第 3 颗
                    // （`prototype/relay.html:3114` 的 `i-nudge`，语义注释见 `:1588`），
                    // 本端照此落到 `DesktopComposer` 的工具行（`ui/desktop/DesktopComposer.kt`）。
                    // 头部这一格腾出来之后，宽档直出的动作正好是原型那一排三颗 ——
                    // 查看屏幕 / 复制节点 ID / 在新窗口打开（`prototype/relay.html:4900-4905`
                    // 逐字「宽档三颗直出」，其中查看屏幕的入口在 `:4629` 有注解：就在私聊头部）。
                    onViewGroupInfo?.let { viewInfo ->
                        // 桌面这一档的标签是「成员」，不是手机那屏的长标签「群信息与成员」：
                        // 原型 `prototype/relay.html:4970` 桌面 `screenDChatGroup` 逐字是
                        // `{html:`<button class="btn sm">${ic('group')} 成员</button>`, icon:'group', label:'成员'}`，
                        // 而手机 `screenChatGroup`（`prototype/relay.html:3161`）用
                        // `{icon:'info', label:'群信息与成员', to:'m-groupinfo'}` —— 原型自己按形态做了选择。
                        if (iconOnlyActions) {
                            TopBarIconAction(Res.drawable.ic_groups, stringResource(Res.string.chat_group_members_action), viewInfo)
                        } else {
                            ToolbarButton(stringResource(Res.string.chat_group_members_action), painterResource(Res.drawable.ic_groups), viewInfo)
                        }
                    }
                    onRequestScreenView?.let { reqScreen ->
                        // 点一下先开确认表单；真正的发送在 onSend 里（原型 d30-reqscreen 的「发送请求」）。
                        val openConfirm = { screenRequestConfirmOpen = true }
                        if (iconOnlyActions) {
                            TopBarIconAction(Res.drawable.ic_screen_share, stringResource(Res.string.screen_remote_view_action), openConfirm)
                        } else {
                            ToolbarButton(stringResource(Res.string.screen_remote_view_action), org.jetbrains.compose.resources.painterResource(Res.drawable.ic_screen_share), openConfirm)
                        }
                        // Dialog 不进 Row 的布局（它画在另一个窗口里），放这儿只是为了贴着它的触发按钮。
                        if (screenRequestConfirmOpen) {
                            ScreenRequestConfirmDialog(
                                onCancel = { screenRequestConfirmOpen = false },
                                onSend = {
                                    screenRequestConfirmOpen = false
                                    reqScreen()
                                },
                            )
                        }
                    }
                    onCopyId?.let { copyId ->
                        if (iconOnlyActions) {
                            TopBarIconAction(Res.drawable.ic_copy, stringResource(Res.string.desktop_action_copy_node_id_short), copyId)
                        } else {
                            ToolbarButton(stringResource(Res.string.desktop_action_copy_node_id_short), painterResource(Res.drawable.ic_copy), copyId)
                        }
                    }
                    // 窄窗下再开一个窗口只会更挤；原型 Compact 的 .dhead 也只有前两个动作。
                    // onOpenInNewWindow 为 null（没有多窗口能力）时同样不画。
                    if (!compact && onOpenInNewWindow != null) {
                        if (iconOnlyActions) {
                            TopBarIconAction(Res.drawable.ic_external, stringResource(Res.string.desktop_action_open_in_new_window), onOpenInNewWindow)
                        } else {
                            ToolbarButton(stringResource(Res.string.desktop_action_open_in_new_window), painterResource(Res.drawable.ic_external), onOpenInNewWindow)
                        }
                    }
                }
            }
        }
    }
}

// 窄栏头部动作已统一到共享件 app.relay.ui.components.TopBarIconAction（原型 `.ibtn`）。
// 原来这里是两颗本地 HeadIconAction：34dp 的裸 IconButton、无底色、tint = onSurfaceVariant。
// 原型 `.dhead .acts` 经 actsHTML → actInline 渲染的是 `<button class="ibtn">` 方块
// （prototype/relay.html:5767 与 :2826），即 34×34、`--surface-2` 底、`--on` 图标。
