// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.GlobalSearchResult
import com.kapp.tidings.feature.search.GlobalSearchOutcome
import com.kapp.tidings.feature.search.GlobalSearchResults
import com.kapp.tidings.feature.search.SEARCH_DEBOUNCE_MILLIS
import com.kapp.tidings.feature.search.debouncedGlobalSearch
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.conv_new_group
import com.kapp.tidings.generated.resources.desktop_search_session_count
import com.kapp.tidings.generated.resources.desktop_view_conversations
import com.kapp.tidings.generated.resources.ic_add_node
import com.kapp.tidings.generated.resources.ic_search
import org.jetbrains.compose.resources.stringResource

import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.components.AppIconButton

/**
 * 桌面全局三段聚合搜索面板（原型 d-search / d-search-empty）。
 *
 * 替换左侧会话列表，分「联系人 / 聊天记录 / 文件」三段展示。
 * 三态齐：搜索中、搜索失败、无结果。
 *
 * `initialResult` 是**冷启动接缝**，生产永远不传（null）。
 * 面板的初态是共享的 [GlobalSearchOutcome.Loading]，唯一能把它推到 Done / Failed 的是
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
    // 桌面与手机共用同一个状态模型（GlobalSearchOutcome）——
    // 过去这里自己有一套 GlobalSearchState 四态枚举，与手机的 outcome 互相翻译，
    // 于是 loading / failed / empty 各写一遍（桌面失败态连「重试」都没有）。
    var retryTick by remember { mutableStateOf(0) }
    val initialOutcome: GlobalSearchOutcome = remember(query) {
        if (initialResult == null) GlobalSearchOutcome.Loading else GlobalSearchOutcome.Done(initialResult)
    }
    var outcome by remember(query) { mutableStateOf(initialOutcome) }

    LaunchedEffect(query, retryTick) {
        // 冷启动接缝（截图门禁）：初值就是终值，渲染器不执行协程体，别让它停在「搜索中」。
        if (retryTick == 0 && initialResult != null) return@LaunchedEffect
        if (query.isBlank()) {
            outcome = GlobalSearchOutcome.Idle
            return@LaunchedEffect
        }
        outcome = GlobalSearchOutcome.Loading
        when (val next = debouncedGlobalSearch(query, SEARCH_DEBOUNCE_MILLIS, onSearch)) {
            GlobalSearchOutcome.Idle -> outcome = GlobalSearchOutcome.Idle
            GlobalSearchOutcome.Loading -> Unit
            GlobalSearchOutcome.Failed -> outcome = GlobalSearchOutcome.Failed
            is GlobalSearchOutcome.Done -> outcome = next
        }
    }

    // 结果区与手机**共用这一份**（loading / failed / 空结果 / 三段命中）。
    // 桌面只保留外壳：标题、大搜索框、宽度与快捷键（在 DesktopGlobalSearchPage 里）。
    GlobalSearchResults(
        outcome = outcome,
        query = query,
        onOpenContact = onSelectPeer,
        onOpenConversation = onSelectMessage,
        onOpenFile = onSelectMessage,
        onRetry = { retryTick++ },
        onClear = onClearQuery,
        modifier = modifier.fillMaxSize(),
    )
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
