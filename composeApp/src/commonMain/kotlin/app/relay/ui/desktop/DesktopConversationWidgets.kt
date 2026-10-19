// SPDX-License-Identifier: GPL-3.0-only

package app.relay.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import app.relay.ui.EmptyState
import app.relay.ui.LoadingState
import app.relay.ui.components.PeerAvatar
import app.relay.ui.components.StatusDot
import app.relay.ui.components.relativeTime
import app.relay.generated.resources.Res
import app.relay.generated.resources.action_clear_search
import app.relay.generated.resources.common_offline
import app.relay.generated.resources.conv_loading
import app.relay.generated.resources.conv_new_group
import app.relay.generated.resources.conv_search_messages_section
import app.relay.generated.resources.desktop_conv_empty
import app.relay.generated.resources.desktop_conv_empty_hint
import app.relay.generated.resources.desktop_conv_go_pairing
import app.relay.generated.resources.desktop_no_match
import app.relay.generated.resources.desktop_conv_no_match_hint
import app.relay.generated.resources.desktop_conv_no_preview
import app.relay.generated.resources.chat_group_online
import app.relay.generated.resources.desktop_conv_pending
import app.relay.generated.resources.ic_folder
import app.relay.generated.resources.desktop_peer_connected
import app.relay.generated.resources.desktop_peer_last_seen
import app.relay.generated.resources.desktop_view_conversations
import app.relay.generated.resources.workspace_group_label
import app.relay.generated.resources.ic_close
import app.relay.generated.resources.ic_groups
import app.relay.ui.theme.IconSizes
import org.jetbrains.compose.resources.stringResource

import app.relay.ui.theme.LocalAppSpacing
import app.relay.app.model.ConversationUi
import app.relay.app.model.NodePhase
import app.relay.app.model.PeerUi


/** `.dhead` 第二行：地址 · 连接状态 · 最后在线。缺项不占位，不留 `:0` 这种残句。 */
@Composable
public fun peerHeaderLine(peer: PeerUi, now: Long): String {
    val host = peer.ip.ifBlank { peer.host }
    val address = if (peer.udpPort > 0) "$host:${peer.udpPort}" else host
    val seen = if (peer.lastSeenAt > 0L) stringResource(Res.string.desktop_peer_last_seen, relativeTime(peer.lastSeenAt, now)) else ""
    return listOf(address, if (peer.online) stringResource(Res.string.desktop_peer_connected) else stringResource(Res.string.common_offline), seen)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/**
 * `.dhead` 上的小标签（平台、团队），以及文件详情头那颗状态 chip。
 *
 * 形状是**药丸**：原型 `.chip{...border-radius:var(--r-chip)}`（`prototype/relay.html:474-476`）
 * 与 `.tag{...border-radius:var(--r-chip)}`（`:310`）都吃 `--r-chip:999px`（`:56`），
 * 实例见 `screenDFiles` 的 `<span class="chip on">已完成</span>`（`:5076-5081`）。
 */
@Composable
public fun HeadChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = CircleShape,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.sm6, vertical = LocalAppSpacing.current.xxs),
        )
    }
}

@Composable
public fun ConversationSidebar(
    sessions: List<ConversationUi>,
    /**
     * 节点当前阶段：只用来把「还在启动」和「真的没有会话」分开。
     *
     * 历史还在从盘上恢复、节点还在启动时，列表本来就是空的；这时说「还没有会话」
     * 是把过程说成了结论。移动端同一处早就这么分了（`ConversationsScreen.kt` 用
     * `NodePhase.Starting` 画「正在恢复会话…」），桌面端一直缺这一支。
     */
    phase: NodePhase,
    selectedPeerId: String?,
    /**
     * 当前搜索词。
     *
     * [sessions] 传进来时**已经是筛过的**，所以光看 `isEmpty()` 分不出
     * 「一个会话都没有」和「搜了没匹配上」。这两句都用「还没有会话」的话，
     * 正在搜索的用户会以为数据丢了。只用于空态文案，列表本身不读它。
     */
    query: String,
    onClearQuery: () -> Unit,
    /**
     * 选中某个对端会话（入参是 peerId，不是视图 id）。
     *
     * 旧代码的「去配对节点」动作调的是 `onSelect("")`：那只把选中态设成空串，
     * 视图根本没变，用户点了像没反应，还会往标签栏里塞一个空 id。
     * 跳视图要走 [onGoToPairing]。
     */
    onSelect: (String) -> Unit,
    onGoToPairing: () -> Unit,
    now: Long,
    modifier: Modifier = Modifier,
    /**
     * 建群入口。null = 不画（预览、或宿主没有这条能力）。
     *
     * 按钮画在这一行的标题旁，而不是列表某一节里：侧栏没有"节标题"这个概念
     * （它是一列会话，不是手机端那种分节列表），把入口挂在标题行上，
     * 用户在列表为空时也看得见它 —— 而这恰恰是最需要它的时候。
     */
    onCreateGroup: (() -> Unit)? = null,
    onCreateWorkspaceGroup: (() -> Unit)? = null,
    /**
     * 历史消息全文搜索接口。null = 预览或不启用。
     */
    onSearchMessages: ((String) -> List<app.relay.app.MessageSearchResult>)? = null,
    onSearchGlobal: ((String) -> app.relay.app.GlobalSearchResult)? = null,
    /**
     * 冷启动时预置的全局搜索结果，只给截图门禁用。
     *
     * 与 [onSearchGlobal] 的分工：那个是「怎么搜」，这个是「第一帧就已经搜到了什么」。
     * 面板的 `Success` 支只能靠后者才拍得到（原因见 `DesktopGlobalSearch.kt` 的
     * `initialResult`）。生产调用方一律用默认值（null）。
     */
    initialGlobalSearch: app.relay.app.GlobalSearchResult? = null,
) {
    if (query.isNotBlank() && onSearchGlobal != null) {
        Column(modifier.background(MaterialTheme.colorScheme.surface)) {
            DesktopGlobalSearchPanel(
                query = query,
                onClearQuery = onClearQuery,
                onSearch = onSearchGlobal,
                onSelectPeer = onSelect,
                onSelectMessage = onSelect,
                modifier = Modifier.fillMaxSize(),
                initialResult = initialGlobalSearch,
            )
        }
        return
    }

    val searchResults = remember(query, onSearchMessages) {
        if (query.isNotBlank() && onSearchMessages != null) {
            onSearchMessages(query)
        } else {
            emptyList()
        }
    }

    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.sm6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.desktop_view_conversations), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            onCreateWorkspaceGroup?.let { createWs ->
                TextButton(onClick = createWs) {
                    Icon(painterResource(Res.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(IconSizes.Label))
                    Spacer(Modifier.width(LocalAppSpacing.current.xs))
                    Text(stringResource(Res.string.workspace_group_label), style = MaterialTheme.typography.labelMedium)
                }
            }
            onCreateGroup?.let { create ->
                // 图标 + 文字都要：这一列里没有任何别的按钮，光一个「+」说不清是加会话
                // 还是加节点。窄侧栏（240dp）也放得下两个字。
                TextButton(onClick = create) {
                    Icon(painterResource(Res.drawable.ic_groups), contentDescription = null, modifier = Modifier.size(IconSizes.Label))
                    Spacer(Modifier.width(LocalAppSpacing.current.xs))
                    Text(stringResource(Res.string.conv_new_group), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("${sessions.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (sessions.isEmpty() && searchResults.isEmpty()) {
            // 三种空态：还在启动 / 没数据 / 筛完没匹配上。最后一种给出「清除搜索」而不是
            // 「去配对节点」，否则用户搜不到东西会被引导去配一个已经配好的节点。
            val filtering = query.isNotBlank()
            if (filtering) {
                // 「筛完没匹配上」这一支在**剩余空间里两轴居中**，不是贴着头部左对齐。
                // 原型 `screenDNoMatch`（`prototype/relay.html:5171`）把这套空态放进列表区，
                // 用的仍是 `.emptyd{flex:1;min-height:0;display:flex;flex-direction:column;
                // align-items:center;justify-content:center;…}`（`:772`），`padding:22px 14px`；
                // 按钮是 `.btn.sm` 带一颗 `ic('close')`（✕）。d10-nomatch 截图也是居中。
                // 不沿用共用 `EmptyState`（`app/relay/ui/AppStates.kt:23` → `MessageState` `:87`）：
                // 它按 `fillMaxWidth()` 顶对齐排、按钮也没有图标口子，而它是全 app 共用的
                // （动它会把手机端多屏空态一起挪走）。写法与 d-filesempty 那支
                // （`DesktopFilesPane.kt:205-245`）、d-search-empty 那支
                // （`DesktopGlobalSearch.kt:171-194`）保持一致。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = LocalAppSpacing.current.lg14,
                            vertical = LocalAppSpacing.current.xl,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(Res.string.desktop_no_match, query),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(LocalAppSpacing.current.sm6))
                        Text(
                            text = stringResource(Res.string.desktop_conv_no_match_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = EmptyLedeMaxWidth),
                        )
                        Spacer(Modifier.height(LocalAppSpacing.current.lg14))
                        OutlinedButton(onClick = onClearQuery) {
                            Icon(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(IconSizes.Label))
                            Spacer(Modifier.width(LocalAppSpacing.current.sm6))
                            Text(stringResource(Res.string.action_clear_search))
                        }
                    }
                }
            } else if (phase == NodePhase.Starting) {
                // 与移动端同一句文案（`conv_loading`），两端的说法不该在这里分叉。
                LoadingState(
                    label = stringResource(Res.string.conv_loading),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                EmptyState(
                    title = stringResource(Res.string.desktop_conv_empty),
                    message = stringResource(Res.string.desktop_conv_empty_hint),
                    actionLabel = stringResource(Res.string.desktop_conv_go_pairing),
                    onAction = onGoToPairing,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            // 每条会话后面那根 `outlineVariant.copy(alpha = 0.4f)` 是**代码侧**的分组线：
            // 原型左栏是 `.li{padding:7px 9px;border-radius:12px}` 的圆角行、`.sidelist` 只给
            // `gap:1px`、**没有分隔线**（`prototype/relay.html:709/713`），而这里渲染的是整幅宽的
            // ConversationRow，所以留一条 40% 的极淡线代替。它是 `--hair` 再压 40%，
            // 不是 `--hair` 的另一种写法。
            LazyColumn(Modifier.fillMaxSize()) {
                items(sessions, key = { it.convId }) { conv ->
                    ConversationRow(conv, conv.convId == selectedPeerId, now, onSelect)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }

                if (searchResults.isNotEmpty()) {
                    item(key = "desktop_section_search_messages") {
                        Text(
                            text = stringResource(Res.string.conv_search_messages_section),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.sm),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                    items(searchResults, key = { "desktop_msg_${it.id}" }) { result ->
                        val conv = sessions.firstOrNull { it.convId == result.convId }
                        val title = conv?.title ?: result.peerId
                        DesktopMessageSearchRow(result, title, now, onSelect)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
public fun ConversationRow(conv: ConversationUi, selected: Boolean, now: Long, onSelect: (String) -> Unit) {
    // 选中态用 primaryContainer：secondaryContainer 的暖色在本 App 里是
    // 排队/告警的语义色，复用到普通选中会与「排队 1」这类 badge 撞色
    // （深色主题下更明显，选中行会变成一整块褐色）。
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val fgMuted = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onSelect(conv.convId) }
            .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.md10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
    ) {
        Box {
            PeerAvatar(conv.title, conv.isAgent, 38.dp, isGroup = conv.isGroup)
            // 群不画在线点：那个点说的是"这一个对端在不在"，群是一群人，
            // `ConversationUi.online` 对群恒 false —— 画出来就是每个群都显示离线。
            if (!conv.isGroup) Box(Modifier.align(Alignment.BottomEnd)) { StatusDot(conv.online, 8.dp) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                conv.title,
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 群把成员数接在预览后面（预览不删：它回答的是"这个群最近在说什么"）。
                // 群行上真正影响决策的是"现在发出去有没有人立刻看到"，那是一个数字，
                // 而 StatusDot 能表达的只有一个布尔。
                text = buildString {
                    append(conv.preview ?: stringResource(Res.string.desktop_conv_no_preview))
                    if (conv.isGroup) {
                        append(" · ")
                        append(
                            stringResource(
                                Res.string.chat_group_online,
                                conv.memberOnlineCount,
                                conv.memberCount,
                            ),
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = fgMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                // 与手机侧 `ConversationRow` 同一口径：只用**最近一条消息**的时间。
                // `conv.at` 只是兼容兜底值（没消息时会退回对端 lastSeenAt / 群 updatedTs），
                // 局域网发现一刷新就变成「刚刚」—— 那正是"没聊过的会话一直顶在最上面"的一半。
                // 没聊过（lastMessageAt == 0）就整格留空，不拿 presenceAt 去冒充活跃。
                text = if (conv.lastMessageAt > 0) relativeTime(conv.lastMessageAt, now) else "",
                style = MaterialTheme.typography.labelSmall,
                color = fgMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs), verticalAlignment = Alignment.CenterVertically) {
                if (conv.pendingCount > 0) {
                    // 排队徽标取 secondaryContainer，不取 tertiaryContainer。
                    // 原型里 --queued-c/--on-queued-c 就是这两个角色（Accents.kt 的 KDoc 与
                    // design-spec.json 的 colorNotes 都这么写），而 tertiaryContainer 本 App
                    // 从没赋过值 —— 取它等于取 Material 基线的粉 #FFD8E4/#633B48。
                    Text(
                        stringResource(Res.string.desktop_conv_pending, conv.pendingCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                            .padding(horizontal = LocalAppSpacing.current.xs),
                    )
                }
                if (conv.unread > 0) {
                    Text(
                        "${conv.unread}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
public fun DesktopMessageSearchRow(
    result: app.relay.app.MessageSearchResult,
    title: String,
    now: Long,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(result.convId) }
            .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                result.snippetText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (result.timestamp > 0) relativeTime(result.timestamp, now) else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}