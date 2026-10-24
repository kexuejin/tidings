// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.GlobalSearchResult
import com.kapp.tidings.domain.GlobalSearchScope
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.action_back
import com.kapp.tidings.generated.resources.action_clear_search
import com.kapp.tidings.generated.resources.global_search_scope_all
import com.kapp.tidings.generated.resources.global_search_scope_chats
import com.kapp.tidings.generated.resources.global_search_scope_contacts
import com.kapp.tidings.generated.resources.global_search_scope_files
import com.kapp.tidings.generated.resources.conv_search_hint_global
import com.kapp.tidings.generated.resources.ic_arrow_back
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.generated.resources.ic_search
import com.kapp.tidings.ui.components.AppButtonTone
import com.kapp.tidings.ui.components.AppIconButton
import com.kapp.tidings.ui.theme.IconSizes
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.accents
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * 移动端**全局搜索**（原型 `m-gsearch` / `m-gsearch-empty`）。
 *
 * 这是移动端唯一的搜索入口 —— 它搜全库（跨会话的消息体、文件、联系人），
 * 而会话列表上方那条只管标题与预览的框已经删掉：两条并排在窄屏上分不清哪条搜哪儿，
 * 所以只留范围更大的这条（`prototype/relay.html:2973-2976`）。
 *
 * 三段**不是三页，是一页里的三块** —— 与桌面那一屏共用同一份数据、同一套上限
 * （联系人 ≤20 / 聊天记录 ≤10 组 / 文件 ≤20，见 [GlobalSearchResult] 的 companion）。
 * 分组标题只有「联系人 / 聊天记录 / 文件」三个词，**没有数量徽标、没有筛选器、命中不高亮**。
 *
 * 两条容易画错的：
 * 1. 「聊天记录」段**一行是一个会话**（「与 老周 的聊天 · 7 条相关」），不是一条消息 ——
 *    点它进那个会话，再在会话内搜（`m-histsearch`）那套里读命中。
 * 2. **空关键词不等于无结果**：框里什么都没输是"还没开始搜"，画初始态；
 *    只有三块都空了才画那两行空态（`GlobalSearchResult.isEmpty` 的 KDoc 写了这条）。
 *
 * 顶栏那一行装的是输入框而不是标题，但顶栏高度只由 padding 与 `tbinner` 的
 * `min-height` 决定 ⇒ 进搜索**不额外占竖向空间**（原型实测 97px，比列表态那 105.8px 还矮 8.8px）。
 */
@Composable
fun GlobalSearchScreen(
    /**
     * 检索入口。第二参数是**只要哪一段**（见 `GlobalSearchScope`）。
     *
     * 为什么把 scope 交给调用方而不是在这里过滤结果：不要的段应当**直接不查** ——
     * 这个搜索同步跑在 UI 路径上，先查再丢等于白付一次全库扫描。
     */
    onSearch: (String, GlobalSearchScope) -> GlobalSearchResult,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 渲染相对时间所用的当前时刻，理由与 [com.kapp.tidings.feature.conversations.ConversationsScreen] 的
     * `now` 完全一样：渲染时取真实挂钟，同一帧两次运行就会渲染出不同的字，截图门禁永远红灯。
     */
    now: Long = nowMillis(),
    /**
     * 搜索框的**初值**，默认空，**只给截图门禁用**。
     *
     * `query` 是屏内状态，静态帧点不了输入框：没有这个接缝，`m-gsearch`（有三段命中）
     * 与 `m-gsearch-empty`（三块都空）两张图都录不到，而"空关键词 ≠ 无结果"这条
     * 正好要靠这两张图对照着看。生产调用方一律用默认值 —— 搜索框的初值就该是空的。
     */
    initialQuery: String = "",
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    // 只看哪一段。默认全部 —— 与原型「没有筛选器」的默认口径一致；
    // 这里多出来的是一层可选收窄，不改变默认行为。
    var scope by remember { mutableStateOf(GlobalSearchScope.All) }
    // 「重试」的计数器：失败后重跑**同一个 query**（不清输入）。把计数加进
    // LaunchedEffect 的 key，自增一次就重放一次检索 —— query 本身没变，靠它触发。
    var retryTick by remember { mutableStateOf(0) }

    // 静态帧接缝：初值非空时当帧直接出结果（截图门禁要的第一帧就是最终画面）。
    // 生产路径的初值为空，走下面的 debounce。
    val initialOutcome: GlobalSearchOutcome = remember(initialQuery) {
        if (initialQuery.isBlank()) {
            GlobalSearchOutcome.Idle
        } else {
            runCatching { onSearch(initialQuery, GlobalSearchScope.All) }
                .fold({ GlobalSearchOutcome.Done(it) }, { GlobalSearchOutcome.Failed })
        }
    }
    var outcome by remember(initialQuery) { mutableStateOf(initialOutcome) }

    // 击键不再逐字打一次全库：静默 [SEARCH_DEBOUNCE_MILLIS] 之后才查一次。
    // `LaunchedEffect(query)` 在 query 变化时取消上一次协程，于是只有最后一次真的落地。
    LaunchedEffect(query, scope, retryTick) {
        if (query.isBlank()) {
            outcome = GlobalSearchOutcome.Idle
            return@LaunchedEffect
        }
        // 初值那一帧已经算好，不要再走一遍 debounce 把画面翻成「正在搜索」。
        // 换筛选档时必须重查 —— 同一句话换「只看文件」结果不同。
        // 「重试」(retryTick > 0) 必须绕过这条：哪怕 query 与初值相同也要真重跑。
        if (retryTick == 0 && query == initialQuery && scope == GlobalSearchScope.All) return@LaunchedEffect
        outcome = GlobalSearchOutcome.Loading
        when (val next = debouncedGlobalSearch(query, SEARCH_DEBOUNCE_MILLIS, onSearch, scope)) {
            GlobalSearchOutcome.Idle -> outcome = GlobalSearchOutcome.Idle
            GlobalSearchOutcome.Loading -> Unit
            GlobalSearchOutcome.Failed -> outcome = GlobalSearchOutcome.Failed
            is GlobalSearchOutcome.Done -> outcome = next
        }
    }

    val sp = LocalAppSpacing.current
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            SearchTopBar(
                query = query,
                onQueryChange = { query = it },
                onBack = onBack,
            )
            HorizontalDivider()

            // 筛选条：**只在真的搜过之后才画**。初始态（还没输字）画一排 chip
            // 等于给一个「还没开始」的屏加配置，而用户此刻要的是输入框。
            if (outcome !is GlobalSearchOutcome.Idle) {
                SearchScopeRow(
                    selected = scope,
                    onSelect = { scope = it },
                )
            }

            // **结果区与桌面共用同一份实现**（loading / failed / 空结果 / 三段命中）。
            //
            // 这一屏只保留外壳：顶部输入框、筛选条、宽度、以及"空关键词时画不画筛选条"。
            // 过去这里自己写了一套，与桌面各走各的，于是同一个状态两端给出不同答案
            // （桌面失败态没有「重试」、空态字号字重不同、结果行信息更少）。
            GlobalSearchResults(
                outcome = outcome,
                query = query,
                // 联系人的命中键是 nodeId，而这一屏要的是**会话键** —— 两者的映射是壳的事。
                onOpenContact = { nodeId -> onOpenChat(ConvId.single(nodeId)) },
                onOpenConversation = onOpenChat,
                onOpenFile = onOpenFile,
                onRetry = { retryTick++ },
                onClear = { query = "" },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 搜索态顶栏：返回钮 + 吃满整行的搜索框（框内左放大镜、右清除）。
 *
 * 原型 `.topbar.gs`（`prototype/relay.html:640-646`）：返回钮与输入框**必须同轴**
 * （`.tbinner{align-items:center}` + `.ibtn.plain{align-self:center;margin-top:0}`），
 * 而 `.search.fill{max-width:none;min-width:0}` 把输入框放开吃满整行。
 */

/**
 * 全局搜索的筛选条：全部 / 联系人 / 聊天记录 / 文件。
 *
 * 抽成独立组件而不是内联，理由与 `ConversationsOverflowItems` 同一条：
 * 截图门禁要能单独渲染这一条。
 *
 * 「全部」是**默认档**，且与原型的「没有筛选器」那一版等价 —— 这一条不改默认口径，
 * 只是把「只看某一段」这条常被用来碰运气的操作变成一次点击。
 */
@Composable
private fun SearchScopeRow(
    selected: GlobalSearchScope,
    onSelect: (GlobalSearchScope) -> Unit,
) {
    val sp = LocalAppSpacing.current
    val options = listOf(
        GlobalSearchScope.All to Res.string.global_search_scope_all,
        GlobalSearchScope.Contacts to Res.string.global_search_scope_contacts,
        GlobalSearchScope.Chats to Res.string.global_search_scope_chats,
        GlobalSearchScope.Files to Res.string.global_search_scope_files,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sp.lg, vertical = sp.sm)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (option, label) ->
            val active = option == selected
            Surface(
                color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .clickable { onSelect(option) }
                        .padding(vertical = sp.sm6),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else null,
                        color = if (active) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        // 筛选档名超出等分宽度时收尾省略，而不是互相顶挤（硬截断）
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sp.md, vertical = sp.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.sm),
    ) {
        AppIconButton(
            icon = Res.drawable.ic_arrow_back,
            contentDescription = stringResource(Res.string.action_back),
            onClick = onBack,
            tone = AppButtonTone.Primary,
        )
        Row(
            // 原型 `.search input{border:1px solid var(--outline);background:var(--bg);
            // border-radius:9px}`（relay.html:625-627）。底色是 `--bg`（#FAFAF8）不是
            // `--surface-2`，且带一条 `--outline` 描边 —— 代码原来用 `surfaceVariant`
            // 做填充底、没有描边，视觉上是「灰底块」而不是「白底细框」。
            // 圆角 9px 落在档位之间，取 `shapes.small`(8dp)，差 1dp ≤2dp（归宿 2）。
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                .padding(horizontal = sp.md, vertical = sp.sm6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.sm),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_search),
                contentDescription = null,
                modifier = Modifier.size(IconSizes.Label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.conv_search_hint_global),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                // 命中区是**外层 48dp 触控目标**（无障碍下限，与 `AppIconButton` /
                // `TopBars` 的 IbtnBox 同一个两层结构：外层吃命中区，视觉砖居中在内）。
                // 过去是一颗 18dp 的裸图标块，既没有 contentDescription（读屏用户
                // 听到的是一个无名可点块），真机上拇指也点不中。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = { onQueryChange("") }),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        // 原型 `.search .clr{width:18px;height:18px;border-radius:50%;
                        // background:var(--surface-3)}` + `.clr svg{width:11px;height:11px}`
                        // （relay.html:632-635）。代码原来是一颗没有底色、也没有圆形的裸图标，
                        // 在浅色输入框里看不出"这是一颗可以点的清除钮"。
                        // `--surface-3` 是**填充** token（`MaterialTheme.accents.surface3`）；
                        // 别退回 `outlineVariant` —— 那个现在专做描边用的 `--hair`。
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.accents.surface3),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = stringResource(Res.string.action_clear_search),
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 全局搜索的输入静默期：击键停下来这么久才真的查一次全库。 */
internal const val SEARCH_DEBOUNCE_MILLIS: Long = 200

/**
 * 全局搜索的状态。
 *
 * `Loading` 只由界面在等待静默期 / 检索时置上；[debouncedGlobalSearch] 永远不返回它 ——
 * 检索本身的结局只有「空关键词没搜」「搜到了」「搜失败了」三种。
 */
internal sealed interface GlobalSearchOutcome {
    /** 空关键词：还没开始搜，画初始态（与「搜了但没命中」不是同一件事）。 */
    data object Idle : GlobalSearchOutcome

    /** 正在等静默期或正在检索。 */
    data object Loading : GlobalSearchOutcome

    /** 检索完成；结果可能为空（空结果 = 无命中态）。 */
    data class Done(val result: GlobalSearchResult) : GlobalSearchOutcome

    /** 检索抛错。 */
    data object Failed : GlobalSearchOutcome
}

/**
 * 静默 [debounceMillis] 之后执行一次检索。
 *
 * 调用方每次 query 变化都取消上一个协程（`LaunchedEffect(query)` 天然如此），
 * 于是**只有最后一次**真的落到 [search] —— 这就是 debounce 的全部含义。
 * 空关键词直接返回 [GlobalSearchOutcome.Idle]，一次检索都不发。
 */
internal suspend fun debouncedGlobalSearch(
    rawQuery: String,
    debounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
    search: (String, GlobalSearchScope) -> GlobalSearchResult,
    scope: GlobalSearchScope = GlobalSearchScope.All,
): GlobalSearchOutcome {
    val q = rawQuery.trim()
    if (q.isEmpty()) return GlobalSearchOutcome.Idle
    delay(debounceMillis)
    return try {
        GlobalSearchOutcome.Done(search(q, scope))
    } catch (_: Throwable) {
        GlobalSearchOutcome.Failed
    }
}

/**
 * 兼容重载：只关心关键词、不要筛选的调用点（老调用点与既有单测）。
 *
 * 有它就不必让每个老调用点都写 `{ q, _ -> ... }` —— 那种为了凑签名而写的
 * 丢弃参数，正是下一次改动时最容易漏的地方。
 */
internal suspend fun debouncedGlobalSearch(
    rawQuery: String,
    debounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
    search: (String) -> GlobalSearchResult,
): GlobalSearchOutcome = debouncedGlobalSearch(rawQuery, debounceMillis, { q, _ -> search(q) })
