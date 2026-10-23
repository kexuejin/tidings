// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.GlobalSearchChatHit
import com.kapp.tidings.domain.GlobalSearchContactHit
import com.kapp.tidings.domain.GlobalSearchFileHit
import com.kapp.tidings.domain.GlobalSearchResult
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.action_clear_search
import com.kapp.tidings.generated.resources.common_offline
import com.kapp.tidings.generated.resources.conv_new_group
import com.kapp.tidings.generated.resources.desktop_search_session_count
import com.kapp.tidings.generated.resources.desktop_view_conversations
import com.kapp.tidings.generated.resources.global_search_empty
import com.kapp.tidings.generated.resources.global_search_empty_hint
import com.kapp.tidings.generated.resources.global_search_failed
import com.kapp.tidings.generated.resources.global_search_from_chat
import com.kapp.tidings.generated.resources.global_search_loading
import com.kapp.tidings.generated.resources.global_search_more_messages
import com.kapp.tidings.generated.resources.global_search_section_contacts
import com.kapp.tidings.generated.resources.global_search_section_files
import com.kapp.tidings.generated.resources.global_search_section_messages
import com.kapp.tidings.generated.resources.ic_add_node
import com.kapp.tidings.generated.resources.ic_chat_bubble
import com.kapp.tidings.generated.resources.ic_file
import com.kapp.tidings.generated.resources.ic_image
import com.kapp.tidings.generated.resources.ic_search
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.PeerAvatar
import com.kapp.tidings.ui.components.formatClock
import org.jetbrains.compose.resources.stringResource

import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.components.AppIconButton

private enum class GlobalSearchState {
    Loading,
    Success,
    Empty,
    Failed,
}

/**
 * 桌面全局三段聚合搜索面板（原型 d-search / d-search-empty）。
 *
 * 替换左侧会话列表，分「联系人 / 聊天记录 / 文件」三段展示。
 * 三态齐：搜索中、搜索失败、无结果。
 *
 * `initialResult` 是**冷启动接缝**，生产永远不传（null）。
 * 面板的初态是 [GlobalSearchState.Loading]，唯一能把它推到 Success / Empty / Failed 的是
 * 下面那个 `LaunchedEffect(query)`；而**截图渲染器不执行 `LaunchedEffect` 的协程体**
 * ，于是任何一张 `query` 非空的参考图都只能录到"搜索中…"。
 * 一张标着搜索成功、画面却是转圈的图，比没有图更坏 —— 所以这里给门禁留一个
 * "第一帧就已经有结果"的入口，形状与 `ScanScreen` 的 `attemptAlreadyFailed`、
 * `AddNodeSheet` 的 `initialScanMode` 一致。传了它，`result` 与 `state` 的初值一起就位，
 * 组合第一帧就是最终画面；传一个**空结果**则第一帧就是「没有找到…」那一支。
 */
@Composable
public fun DesktopGlobalSearchPanel(
    query: String,
    onClearQuery: () -> Unit,
    onSearch: (String) -> GlobalSearchResult,
    onSelectPeer: (String) -> Unit,
    onSelectMessage: (convId: String) -> Unit,
    modifier: Modifier = Modifier,
    initialResult: GlobalSearchResult? = null,
) {
    var state by remember(query) {
        mutableStateOf(
            when {
                initialResult == null -> GlobalSearchState.Loading
                // 空结果本身就是「无结果」态的事实。若把它放进 `Success`，三段分组
                // 会全被 `if (…isNotEmpty())` 跳过，画出一片空白 —— 一张标着
                // 「搜索无结果」却是白板的图比没有图更坏。所以冷启动接缝要把
                // `Empty` 一并摆对：这也是 d-search-empty（原型 `d27-search-empty`）
                // 唯一能静态拍到的分支。
                initialResult.isEmpty -> GlobalSearchState.Empty
                else -> GlobalSearchState.Success
            },
        )
    }
    var result by remember(query) {
        mutableStateOf(initialResult ?: GlobalSearchResult(query = query))
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            state = GlobalSearchState.Empty
            result = GlobalSearchResult(query = "")
            return@LaunchedEffect
        }
        state = GlobalSearchState.Loading
        try {
            val res = onSearch(q)
            result = res
            state = if (res.isEmpty) GlobalSearchState.Empty else GlobalSearchState.Success
        } catch (_: Throwable) {
            state = GlobalSearchState.Failed
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when (state) {
            GlobalSearchState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(LocalAppSpacing.current.md))
                        Text(
                            text = stringResource(Res.string.global_search_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            GlobalSearchState.Failed -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(LocalAppSpacing.current.xxl),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(Res.string.global_search_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(LocalAppSpacing.current.sm))
                        AppTextButton(
                            text = stringResource(Res.string.action_clear_search),
                            onClick = onClearQuery,
                        )
                    }
                }
            }

            GlobalSearchState.Empty -> {
                // `.spph{font-size:12px;color:var(--on-2);text-align:center;margin-top:24px}`
                // + `.spph b{color:var(--on);display:block;margin-bottom:4px;font-size:14px}`
                // （`prototype/relay.html:899-900`）⇒ 空态是**顶部锚定**：贴着搜索框下方 24px
                // （框自己还有 10px `margin-bottom`），水平居中；**不是**在整块面板里垂直居中。
                // 参考图 `prototype/screenshots/relay/d27-search-empty.png` 亦然 —— 两行文字落在框下约 34px。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = LocalAppSpacing.current.lg14,
                            end = LocalAppSpacing.current.lg14,
                            top = LocalAppSpacing.current.xl,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(Res.string.global_search_empty, query),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(LocalAppSpacing.current.xs))
                    Text(
                        text = stringResource(Res.string.global_search_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    // 这里**不画**「清除搜索」按钮：原型 `d27-search-empty` 的
                    // 空态只有上面两行居中文字，清除入口是搜索框里的那个 ✕
                    // （`prototype/relay.html:2602` 的 `.clr`，代码侧对应
                    // `DesktopToolbar` 搜索框的 trailingIcon）。
                }
            }

            GlobalSearchState.Success -> {
                // 不设 `contentPadding`：原型从 `.spbox` 的 10px `margin-bottom` 直接进
                // `.spsec{padding:9px 12px 5px}`（`prototype/relay.html:883/886`），中间没有过渡带。
                //
                // 每条命中后面那根 `outlineVariant.copy(alpha = 0.3f)` 是**代码侧**的分组线：
                // 原型 `.spitem{padding:8px 12px;border-radius:12px;min-height:48px}`
                // （`prototype/relay.html:893-895`）是圆角行 + hover 换底色，**没有分隔线**；
                // 这里三段的命中行是整幅宽的平排（没有圆角、也没有 hover 底），所以留一条
                // 极淡的线代替。它是 `--hair` 再压 30%，不是 `--hair` 的另一种写法。
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 联系人分组
                    if (result.contacts.isNotEmpty()) {
                        item(key = "section_contacts") {
                            GlobalSearchSectionHeader(stringResource(Res.string.global_search_section_contacts))
                        }
                        items(result.contacts, key = { "contact_${it.nodeId}" }) { contact ->
                            GlobalSearchContactRow(contact, onClick = { onSelectPeer(contact.nodeId) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }

                    // 聊天记录分组
                    if (result.chats.isNotEmpty()) {
                        item(key = "section_chats") {
                            GlobalSearchSectionHeader(stringResource(Res.string.global_search_section_messages))
                        }
                        items(result.chats, key = { "chat_${it.convId}" }) { chat ->
                            GlobalSearchChatRow(chat, onClick = { onSelectMessage(chat.convId) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }

                    // 文件分组
                    if (result.files.isNotEmpty()) {
                        item(key = "section_files") {
                            GlobalSearchSectionHeader(stringResource(Res.string.global_search_section_files))
                        }
                        items(result.files, key = { "file_${it.messageId}" }) { file ->
                            GlobalSearchFileRow(file, onClick = { onSelectMessage(file.convId) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 整幅宽的全局搜索页（原型 `screenDSearch` / `screenDSearchEmpty`，`prototype/relay.html:4454-4472`）。
 *
 * 与 [DesktopGlobalSearchPanel] 的分工：那个是「三段结果本身」，这个是「结果所在的那一页」。
 * 原型这两屏**不调 `dwin(...)`** —— 它们是「一条 topbar + 一整幅 detail」，既没有侧栏、
 * 也没有右栏聊天区，所以这里画的是整幅宽的一页：
 *
 * 1. 头部：标题「会话」+ 副标题「N 个会话」+ 一颗 `+`
 *    （原型 `topbar('会话', \`${CONVS.length} 个会话\`, 发起讨论组按钮)`，`:4455-4456`）；
 * 2. 一条大搜索框 `prototype/relay.html:848-851` 的 `.spbox`
 *    （`height:36px;border-radius:var(--r-ctl);background:var(--surface-2);border:1px solid var(--hair);gap:8px`，
 *    外面套一层 `padding:14px 16px` 的 body，`:4457`）。**它只显示搜索词、不接收输入** ——
 *    原型那个 `<input>` 自己就是 `readonly`，真正的输入框是外壳工具条里那一个
 *    （`DesktopChrome.kt` 的 `DesktopToolbar`，带 `prototype/relay.html:2602` 的 `.clr` 清除 ✕）；
 * 3. 下面才是 [DesktopGlobalSearchPanel] 的三段 / 空态。
 *
 * `initialResult` 的语义与面板完全一致：渲染器不执行 `LaunchedEffect` 的协程体
 * ，冷启动接缝是静态拍到 Success / Empty 的唯一入口。
 *
 * 登记在案的一处结构差（原型自身两处说法互相矛盾，见报告 §14）：
 * `prototype/relay.html:844-847` 的 CSS 注释说这块面板「直接替换 `<ConvList>`，不是独立页面」
 * （即只占左栏、右栏聊天区照旧），而 `screenDSearch` 与参考图 `d26-search.png` / `d27-search-empty.png`
 * 画的是整幅宽、没有右栏。代码照**参考图**，因为验收口径是「原型图 ↔ 代码渲染图」并排。
 */
@Composable
public fun DesktopGlobalSearchPage(
    query: String,
    sessionCount: Int,
    onClearQuery: () -> Unit,
    onSearch: (String) -> GlobalSearchResult,
    onSelectPeer: (String) -> Unit,
    onSelectMessage: (String) -> Unit,
    /**
     * 头部那颗 `+`（发起讨论组）。null = 不画，与 [ConversationsPane] 的建群入口同义。
     *
     * 图标用**素 `+`**而不是 `Icons.Outlined.GroupAdd`：原型这一格逐字是
     * `${ic('plus')}`（`prototype/relay.html:4455` 的 `topbar` acts，`aria-label="发起讨论组"`），
     * 参考图 `d26-search.png` 里也是一个小方块里的加号；`GroupAdd`（人+加号）是侧栏
     * 「建群」入口那套的图标，两处别混。无障碍名仍用它真实的动作 `conv_new_group`。
     */
    onCreateGroup: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    initialResult: GlobalSearchResult? = null,
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.sm6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.desktop_view_conversations),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.desktop_search_session_count, sessionCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            onCreateGroup?.let { create ->
                AppIconButton(
                    icon = Res.drawable.ic_add_node,
                    contentDescription = stringResource(Res.string.conv_new_group),
                    onClick = create,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // `.spbox{background:var(--surface-2);border:1px solid var(--hair);border-radius:var(--r-ctl);
        //   padding:0 10px;height:36px;margin-bottom:10px}`（`prototype/relay.html:882-883`）
        // ⇒ 底色是 `--surface-2` = `surfaceVariant`（#F0EFE9）。原先写的 `surfaceContainerLow`
        //   在本主题里等于 `background`（#FAFAF8），原型**没有**这个值 ⇒ 真缺陷，本轮已改。
        // 外形尺寸：外层 body 的 `padding:14px 16px`（`:4555`）落成 top = 14（`lg14`）/ h = 16（`lg`），
        // 那 10px 的 `margin-bottom` 落成 bottom = `md10`；内层 `.spbox` 自己的 `padding:0 10px` 是
        // 下面 `Row` 的 `md10`。圆角 `var(--r-ctl)` = 12px（`:58`）⇒ `shapes.medium`。
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = LocalAppSpacing.current.lg,
                    end = LocalAppSpacing.current.lg,
                    top = LocalAppSpacing.current.lg14,
                    bottom = LocalAppSpacing.current.md10,
                )
                .height(36.dp),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = LocalAppSpacing.current.md10),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.sm),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSizes.Label),
                )
                Text(
                    text = query,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DesktopGlobalSearchPanel(
            query = query,
            onClearQuery = onClearQuery,
            onSearch = onSearch,
            onSelectPeer = onSelectPeer,
            onSelectMessage = onSelectMessage,
            modifier = Modifier.weight(1f),
            initialResult = initialResult,
        )
    }
}

@Composable
private fun GlobalSearchSectionHeader(title: String) {
    // `.spsec{font-size:11px;color:var(--on-2);font-weight:600;letter-spacing:.06em;padding:9px 12px 5px}`
    // （`prototype/relay.html:886`）⇒ 分组标题是**灰的**（`--on-2` = `onSurfaceVariant`）、600 字重，
    // 不是主色绿 + Bold。参考图 `d26-search.png` 里「联系人 / 聊天记录 / 文件」三个词也都是灰的。
    // 内边距：原型 9/12/5，代码用 8/14/4（三处都 ≤2dp 的档位就近 snap）。
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = LocalAppSpacing.current.lg14,
            end = LocalAppSpacing.current.lg14,
            top = LocalAppSpacing.current.sm,
            bottom = LocalAppSpacing.current.xs,
        ),
    )
}

@Composable
private fun GlobalSearchContactRow(
    contact: GlobalSearchContactHit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
    ) {
        // `.spitem .spav{width:34px;height:34px;border-radius:12px}`（`prototype/relay.html:890`）
        // ⇒ 三段的行头都是 **34 的圆角方块（12px）**，不是 36 的圆。参考图 `d26-search.png` 亦然。
        PeerAvatar(contact.title, contact.isAgent, 34.dp, shape = MaterialTheme.shapes.medium)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!contact.online) {
                    Text(
                        text = " · ${stringResource(Res.string.common_offline)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (contact.subtitle.isNotBlank()) {
                Text(
                    text = contact.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GlobalSearchChatRow(
    chat: GlobalSearchChatHit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
    ) {
        // `gscChat` 的行头逐字：`<div class="spav" style="background:var(--primary)">${ic('chat')}</div>`
        // （`prototype/relay.html:4529`）+ `.spitem .spav{…border-radius:12px} / .spav{color:#fff}`
        // （`:890-891`）⇒ **实底主色绿 + 白图标**、34 的圆角方块；原先画的是浅绿容器 + 主色图标。
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(Res.drawable.ic_chat_bubble),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(IconSizes.Label),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.global_search_more_messages, chat.title, chat.matchCount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = formatClock(chat.latestAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (chat.snippet.isNotBlank()) {
                Text(
                    text = chat.snippet,
                    // `.spitem .t2{font-size:11px}`（`prototype/relay.html:896`）⇒ 11sp（`labelSmall`）
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GlobalSearchFileRow(
    file: GlobalSearchFileHit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LocalAppSpacing.current.lg14, vertical = LocalAppSpacing.current.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
    ) {
        // `gscFile` 的行头逐字：`<div class="spav" style="background:var(--surface-3);color:var(--on-2)">`
        // （`prototype/relay.html:4538`）—— `.spitem .spav` 给的是 34 / 圆角 12 / `svg{16px}`（`:890-892`）。
        // `--surface-3`（#E4E9E7）在本主题没有对应档位，用最近的 `surfaceVariant`（= `--surface-2` #F0EFE9）登记。
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = if (file.isImage) painterResource(Res.drawable.ic_image) else painterResource(Res.drawable.ic_file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSizes.Label),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = formatClock(file.at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                // 原型 ``d26-search`` 的文件行副标题是「来自与 Lin 的聊天」
                // （`prototype/relay.html:4424-4426`），与 `GlobalSearchFileHit.convTitle`
                // 的 KDoc 一致；此前这里直接画 `convTitle`，把出处写成了光秃秃的会话名。
                text = stringResource(Res.string.global_search_from_chat, file.convTitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
