// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.search

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.kapp.tidings.generated.resources.action_retry
import com.kapp.tidings.generated.resources.common_offline
import com.kapp.tidings.generated.resources.global_search_empty
import com.kapp.tidings.generated.resources.global_search_empty_hint
import com.kapp.tidings.generated.resources.global_search_failed
import com.kapp.tidings.generated.resources.global_search_from_chat
import com.kapp.tidings.generated.resources.global_search_loading
import com.kapp.tidings.generated.resources.global_search_more_messages
import com.kapp.tidings.generated.resources.global_search_section_contacts
import com.kapp.tidings.generated.resources.global_search_section_files
import com.kapp.tidings.generated.resources.global_search_section_messages
import com.kapp.tidings.generated.resources.ic_chat_bubble
import com.kapp.tidings.generated.resources.ic_file
import com.kapp.tidings.generated.resources.ic_image
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.PeerAvatar
import com.kapp.tidings.ui.components.formatClock
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 全局搜索的**结果区** —— 手机屏与桌面面板共用这一份实现。
 *
 * ## 为什么必须收成一份（AGENTS §2.6：内容共享、外壳参数化）
 *
 * 这两个壳过去各写了一套「loading / failed / empty / 三段结果」，于是同一个状态在两端
 * 给出不同答案，而且**没有任何量具会因此变红**：
 *  - 加载中：手机是「顶部锚定 + sp.xl 转圈」，桌面是「垂直居中 + 28dp 转圈」；
 *  - 失败：手机给了**两个**出口（重试同一个 query / 清除重输），桌面**只有清除** ——
 *    桌面用户想「再试一次」只能把关键词删掉重打；
 *  - 空结果：手机是 bodySmall+Bold、桌面是 titleSmall+SemiBold，两端字号字重都不同；
 *  - 结果行：手机三段各有专门的行（联系人带离线后缀、聊天带「N 条相关」、文件带
 *    「来自与 X 的聊天」），桌面另写了三份，只显示标题与时间。
 *
 * 收敛口径：**按原型**。原型 .spph b（14px/600）+ .spph（12px，--on-2）就是这里的空态，
 * .spsec 是分组标题，.spitem 是命中行 —— 两壳本来就该长得一样。
 *
 * ## 接缝形状
 *
 * 参数接**共享模型**（[GlobalSearchOutcome] / [GlobalSearchResult]）与**原语回调**，
 * 不接任何壳自己的类型。外壳（顶部输入框、筛选 chip、快捷键、宽度、空关键词是否画
 * 筛选条）留在各自壳里 —— 那是外壳。
 *
 * @param onOpenContact 联系人命中：参数是 **nodeId**（联系人没有会话键，映射成会话键是
 *   壳的事 —— 手机走 ConvId.single，桌面走「选中这个节点」）。
 * @param onOpenConversation 聊天记录命中：参数是**会话键**。
 * @param onOpenFile 文件命中：参数是它所属的**会话键**（文件在协议里属文件柜那一套，
 *   落点由壳决定）。
 */
@Composable
internal fun GlobalSearchResults(
    outcome: GlobalSearchOutcome,
    query: String,
    onOpenContact: (nodeId: String) -> Unit,
    onOpenConversation: (convId: String) -> Unit,
    onOpenFile: (convId: String) -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when (outcome) {
            // 还没输入关键词：给一句「这里能搜什么」，不是一整页空白。
            GlobalSearchOutcome.Idle -> GlobalSearchIdleHint()

            GlobalSearchOutcome.Loading -> GlobalSearchLoading()

            // 失败：一句说明 + **两个**出口（重试同一个 query / 清除重输）。
            // 桌面过去只有「清除」—— 那是把「再试一次」这条最自然的动作藏起来。
            GlobalSearchOutcome.Failed -> GlobalSearchFailed(onRetry = onRetry, onClear = onClear)

            is GlobalSearchOutcome.Done -> {
                if (outcome.result.isEmpty) {
                    GlobalSearchNoHits(query)
                } else {
                    GlobalSearchHitList(
                        result = outcome.result,
                        onOpenContact = onOpenContact,
                        onOpenConversation = onOpenConversation,
                        onOpenFile = onOpenFile,
                    )
                }
            }
        }
    }
}

/** 三段命中：联系人 / 聊天记录 / 文件。空段整段不画（分组标题也不画）。 */
@Composable
private fun GlobalSearchHitList(
    result: GlobalSearchResult,
    onOpenContact: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (result.contacts.isNotEmpty()) {
            item(key = "section_contacts") {
                GlobalSearchSectionLabel(stringResource(Res.string.global_search_section_contacts))
            }
            items(result.contacts, key = { "contact_" + it.nodeId }) { hit ->
                GlobalSearchContactRow(hit, onClick = { onOpenContact(hit.nodeId) })
            }
        }
        if (result.chats.isNotEmpty()) {
            item(key = "section_chats") {
                GlobalSearchSectionLabel(stringResource(Res.string.global_search_section_messages))
            }
            items(result.chats, key = { "chat_" + it.convId }) { hit ->
                GlobalSearchChatRow(hit, onClick = { onOpenConversation(hit.convId) })
            }
        }
        if (result.files.isNotEmpty()) {
            item(key = "section_files") {
                GlobalSearchSectionLabel(stringResource(Res.string.global_search_section_files))
            }
            items(result.files, key = { "file_" + it.messageId }) { hit ->
                GlobalSearchFileRow(hit, onClick = { onOpenFile(hit.convId) })
            }
        }
    }
}

/** 分组标题。原型 .spsec：11px / --on-2 / 600 / padding 9-12-5。 */
@Composable
private fun GlobalSearchSectionLabel(text: String) {
    // 原型 9/12/5 里的 9 与 5 不在 AppSpacing 档上，保持字面量；12 是档上的 md。
    val sp = LocalAppSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = sp.md, top = 9.dp, end = sp.md, bottom = 5.dp),
    )
}

/**
 * 命中行的公共外壳。原型 .spitem：gap 10 / padding 8-12 / 居中；
 * .sptime：11px / --on-2 / 不收缩。
 *
 * 三段只有**左侧那一块**不同（头像 / 会话图标 / 文件图标），骨架完全一样。
 * [time] 传 null = 这一格不画（联系人没有「最近一条消息」，编一个时间就是在说谎）。
 */
@Composable
private fun GlobalSearchHitRow(
    leading: @Composable () -> Unit,
    title: @Composable () -> Unit,
    subtitle: String,
    time: String?,
    onClick: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = sp.md, vertical = sp.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.md10),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            title()
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = sp.xxs),
            )
        }
        if (time != null) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 联系人一行：34dp 圆角方头像 + 昵称（离线时挂半句）+ 副标题；右侧不画时间（见 [GlobalSearchHitRow]）。 */
@Composable
private fun GlobalSearchContactRow(hit: GlobalSearchContactHit, onClick: () -> Unit) {
    GlobalSearchHitRow(
        leading = {
            PeerAvatar(
                name = hit.title,
                isAgent = hit.isAgent,
                size = 34.dp,
                shape = MaterialTheme.shapes.medium,
            )
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = hit.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!hit.online) {
                    Text(
                        text = "· " + stringResource(Res.string.common_offline),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        subtitle = hit.subtitle,
        time = null,
        onClick = onClick,
    )
}

/** 聊天记录一行：**一个会话**，标题「N 条相关」，副行是最新那条命中的摘要。 */
@Composable
private fun GlobalSearchChatRow(hit: GlobalSearchChatHit, onClick: () -> Unit) {
    GlobalSearchHitRow(
        leading = {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_chat_bubble),
                    contentDescription = null,
                    modifier = Modifier.size(IconSizes.Label),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        title = {
            Text(
                text = stringResource(Res.string.global_search_more_messages, hit.title, hit.matchCount),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        subtitle = hit.snippet,
        time = formatClock(hit.latestAt),
        onClick = onClick,
    )
}

/** 文件一行：文件本身没有标题，出处才是它的身份 ⇒ 副行写「来自与 X 的聊天」。 */
@Composable
private fun GlobalSearchFileRow(hit: GlobalSearchFileHit, onClick: () -> Unit) {
    GlobalSearchHitRow(
        leading = {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(if (hit.isImage) Res.drawable.ic_image else Res.drawable.ic_file),
                    contentDescription = null,
                    modifier = Modifier.size(IconSizes.Label),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        title = {
            Text(
                text = hit.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        subtitle = stringResource(Res.string.global_search_from_chat, hit.convTitle),
        time = formatClock(hit.at),
        onClick = onClick,
    )
}

/**
 * 搜索中：静默期与检索执行期间画它，替掉「敲了字却像没反应」。
 *
 * 顶部锚定（与空态、提示同一个锚点）：这一屏的参照物是上面的搜索框，
 * 不是整块面板的几何中心 —— 垂直居中会让同一个状态在两种高度上落到两个位置。
 */
@Composable
private fun GlobalSearchLoading() {
    val sp = LocalAppSpacing.current
    Box(
        modifier = Modifier.fillMaxSize().padding(top = sp.xl),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(sp.xl))
            Spacer(Modifier.height(sp.md))
            Text(
                text = stringResource(Res.string.global_search_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 搜索失败：一句说明 + 两个出口（重试同一个 query / 清除重输），不画一片空白。 */
@Composable
private fun GlobalSearchFailed(onRetry: () -> Unit, onClear: () -> Unit) {
    val sp = LocalAppSpacing.current
    Box(
        modifier = Modifier.fillMaxSize().padding(top = sp.xl),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(sp.sm),
        ) {
            Text(
                text = stringResource(Res.string.global_search_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(sp.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(text = stringResource(Res.string.action_retry), onClick = onRetry)
                AppTextButton(text = stringResource(Res.string.action_clear_search), onClick = onClear)
            }
        }
    }
}

/**
 * 搜到了但没有命中 —— 与「还没输入」是两件事（后者由 [GlobalSearchIdleHint] 负责）。
 *
 * 版式逐字对齐原型 prototype/relay.html:926-927：
 *  - .spph b{color:var(--on);display:block;margin-bottom:4px;font-size:14px}
 *    ⇒ 标题 14sp + **粗体（<b> 的浏览器默认 700，不是 600）** + 4px（2dp）下间距；
 *  - .spph{font-size:12px;color:var(--on-2);text-align:center;margin-top:24px}
 *    ⇒ 副行 12sp/次级色/居中，整块距搜索框 24px。
 * 顶部锚定（不是垂直居中）：参考图 d27-search-empty 里两行文字就落在搜索框下约 34px。
 *
 * 字重这一档是 designScreenDiffGate 抓出来的：上一版按桌面那份写 SemiBold(600)，
 * 而原型（与手机旧实现）是 700 —— 收敛时以原型为准。
 */
@Composable
private fun GlobalSearchNoHits(query: String) {
    val sp = LocalAppSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = sp.lg14, end = sp.lg14, top = sp.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.global_search_empty, query),
            style = MaterialTheme.typography.titleSmall,
            // 原型 .spph b 的 700（<b> 默认粗体），不是 600 —— 见 KDoc。
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        // margin-bottom:4px ⇒ 2dp（原型用 px，本仓 dp=px/2）。
        Spacer(Modifier.height(sp.xxs))
        Text(
            text = stringResource(Res.string.global_search_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // 这里**不画**「清除搜索」按钮：原型该帧只有两行居中文字，清除入口是搜索框里的 ✕。
    }
}
