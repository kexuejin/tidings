// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

import com.kapp.tidings.feature.conversations.ConversationListDivider
import com.kapp.tidings.feature.conversations.ConversationListPanel
import com.kapp.tidings.feature.conversations.ConversationRow
import com.kapp.tidings.ui.EmptyState
import com.kapp.tidings.ui.LoadingState
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppOutlinedButton
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.relativeTime
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.action_clear_search
import com.kapp.tidings.generated.resources.common_offline
import com.kapp.tidings.generated.resources.conv_loading
import com.kapp.tidings.generated.resources.conv_new_group
import com.kapp.tidings.generated.resources.conv_search_messages_section
import com.kapp.tidings.generated.resources.desktop_conv_empty
import com.kapp.tidings.generated.resources.desktop_conv_empty_hint
import com.kapp.tidings.generated.resources.desktop_conv_go_pairing
import com.kapp.tidings.generated.resources.desktop_no_match
import com.kapp.tidings.generated.resources.desktop_conv_no_match_hint
import com.kapp.tidings.generated.resources.ic_folder
import com.kapp.tidings.generated.resources.desktop_peer_connected
import com.kapp.tidings.generated.resources.desktop_peer_last_seen
import com.kapp.tidings.generated.resources.desktop_view_conversations
import com.kapp.tidings.generated.resources.workspace_group_label
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.generated.resources.ic_groups
import org.jetbrains.compose.resources.stringResource

import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.domain.model.ConversationUi
import com.kapp.tidings.domain.model.NodePhase
import com.kapp.tidings.domain.model.PeerUi


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
    onSearchMessages: ((String) -> List<com.kapp.tidings.domain.MessageSearchResult>)? = null,
    /** 长按会话行的三个动作（与手机壳同一份实现，见 ConversationRow）。 */
    onTogglePin: ((String) -> Unit)? = null,
    onToggleMute: ((String) -> Unit)? = null,
    onDeleteConversation: ((String) -> Unit)? = null,
    onSearchGlobal: ((String) -> com.kapp.tidings.domain.GlobalSearchResult)? = null,
    /**
     * 冷启动时预置的全局搜索结果，只给截图门禁用。
     *
     * 与 [onSearchGlobal] 的分工：那个是「怎么搜」，这个是「第一帧就已经搜到了什么」。
     * 面板的 `Success` 支只能靠后者才拍得到（原因见 `DesktopGlobalSearch.kt` 的
     * `initialResult`）。生产调用方一律用默认值（null）。
     */
    initialGlobalSearch: com.kapp.tidings.domain.GlobalSearchResult? = null,
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
                AppTextButton(
                    text = stringResource(Res.string.workspace_group_label),
                    onClick = createWs,
                    size = AppButtonSize.Small,
                    leadingIcon = Res.drawable.ic_folder,
                )
            }
            onCreateGroup?.let { create ->
                // 图标 + 文字都要：这一列里没有任何别的按钮，光一个「+」说不清是加会话
                // 还是加节点。窄侧栏（240dp）也放得下两个字。
                AppTextButton(
                    text = stringResource(Res.string.conv_new_group),
                    onClick = create,
                    size = AppButtonSize.Small,
                    leadingIcon = Res.drawable.ic_groups,
                )
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
                        AppOutlinedButton(
                            text = stringResource(Res.string.action_clear_search),
                            onClick = onClearQuery,
                            leadingIcon = Res.drawable.ic_close,
                        )
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
            // **这里不许再画分割线。**
            //
            // 以前有一条 `outlineVariant.copy(alpha = 0.4f)` 的 40% 淡线，理由是"原型左栏是
            // `.li{border-radius:12px}` 的圆角行 + `.sidelist{gap:1px}`、没有分隔线，
            // 而这里渲染的是整幅宽平铺行，所以留一条线代替"。**那是对症错了**：
            // 缺的不是线，是圆角行本身。现在 `ConversationRow` 只有一份实现、并且按原型做了
            // 行间分隔用**内缩**的极淡线（[ConversationListDivider]），不用通栏线：
            // 行是 12dp 圆角，通栏会顶到圆角上；内缩到文字左沿才与文字对齐。
            // 手机壳用同一条线、同一个内缩量（见 ConversationsScreen），两侧必须一致。
            // 行的容器是**共享组件**（`ConversationListPanel`）—— 手机屏用的是同一个。
            // 这里只提供外壳：选中态、以及尾部那一段"搜到的消息"（插槽）。
            ConversationListPanel(
                feed = sessions.map { com.kapp.tidings.feature.conversations.InboxFeedItem.Conversation(it) },
                now = now,
                onOpenChat = onSelect,
                onTogglePin = onTogglePin,
                onToggleMute = onToggleMute,
                onDeleteConversation = onDeleteConversation,
                modifier = Modifier.fillMaxSize(),
                selectedConvId = selectedPeerId,
                trailingContent = if (searchResults.isEmpty()) {
                    null
                } else {
                    {
                        item(key = "desktop_section_search_messages") {
                            Text(
                                text = stringResource(Res.string.conv_search_messages_section),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    horizontal = LocalAppSpacing.current.lg14,
                                    vertical = LocalAppSpacing.current.sm,
                                ),
                            )
                            ConversationListDivider()
                        }
                        items(searchResults, key = { "desktop_msg_${it.id}" }) { result ->
                            val conv = sessions.firstOrNull { it.convId == result.convId }
                            val title = conv?.title ?: result.peerId
                            DesktopMessageSearchRow(result, title, now, onSelect)
                            // 同一个内缩量：搜索结果与上方的会话行属于同一条列表。
                            ConversationListDivider()
                        }
                    }
                },
            )
        }
    }
}

@Composable
public fun DesktopMessageSearchRow(
    result: com.kapp.tidings.domain.MessageSearchResult,
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