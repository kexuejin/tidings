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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tidings.domain.GlobalSearchChatHit
import com.kapp.tidings.domain.GlobalSearchContactHit
import com.kapp.tidings.domain.GlobalSearchFileHit
import com.kapp.tidings.domain.GlobalSearchResult
import com.kapp.tidings.domain.GlobalSearchScope
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.protocol.ConvId
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.action_back
import com.kapp.tidings.generated.resources.action_clear_search
import com.kapp.tidings.generated.resources.common_offline
import com.kapp.tidings.generated.resources.global_search_more_messages
import com.kapp.tidings.generated.resources.global_search_section_messages
import com.kapp.tidings.generated.resources.global_search_section_contacts
import com.kapp.tidings.generated.resources.global_search_empty_hint
import com.kapp.tidings.generated.resources.global_search_scope_all
import com.kapp.tidings.generated.resources.global_search_scope_chats
import com.kapp.tidings.generated.resources.global_search_scope_contacts
import com.kapp.tidings.generated.resources.global_search_scope_files
import com.kapp.tidings.generated.resources.global_search_empty
import com.kapp.tidings.generated.resources.global_search_from_chat
import com.kapp.tidings.generated.resources.global_search_section_files
import com.kapp.tidings.generated.resources.global_search_contact_time
import com.kapp.tidings.generated.resources.global_search_failed
import com.kapp.tidings.generated.resources.global_search_loading
import com.kapp.tidings.generated.resources.conv_search_hint_global
import com.kapp.tidings.generated.resources.ic_chat_bubble
import com.kapp.tidings.generated.resources.ic_file
import com.kapp.tidings.generated.resources.ic_image
import com.kapp.tidings.generated.resources.ic_arrow_back
import com.kapp.tidings.generated.resources.ic_close
import com.kapp.tidings.generated.resources.ic_search
import com.kapp.tidings.ui.components.AppButtonTone
import com.kapp.tidings.ui.components.AppIconButton
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.PeerAvatar
import com.kapp.tidings.ui.components.formatClock
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
    LaunchedEffect(query, scope) {
        if (query.isBlank()) {
            outcome = GlobalSearchOutcome.Idle
            return@LaunchedEffect
        }
        // 初值那一帧已经算好，不要再走一遍 debounce 把画面翻成「正在搜索」。
        // 换筛选档时必须重查 —— 同一句话换「只看文件」结果不同。
        if (query == initialQuery && scope == GlobalSearchScope.All) return@LaunchedEffect
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

            when (val current = outcome) {
                // 初始态：还没输字，三段一块都不画（不是"没有结果"）。
                GlobalSearchOutcome.Idle -> Unit

                // 静默期与检索执行期间：给一个明确的「正在搜索」，而不是让界面像没反应。
                GlobalSearchOutcome.Loading -> SearchLoading()

                // 检索抛错：给一句失败说明与回到可搜状态的出口，不画一片空白。
                GlobalSearchOutcome.Failed -> SearchFailed(onClear = { query = "" })

                is GlobalSearchOutcome.Done -> {
                    val result = current.result
                    if (result.isEmpty) {
                        EmptyHits(query)
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = sp.md),
                        ) {
                            if (result.contacts.isNotEmpty()) {
                                SectionLabel(stringResource(Res.string.global_search_section_contacts))
                                result.contacts.forEach { hit ->
                                    ContactHitRow(
                                        hit = hit,
                                        // 联系人的命中键是 **nodeId**，而这里要的是**会话键** ——
                                        // 两者在私聊上恰好相等，但那是 `ConvId.single` 的约定、
                                        // 不是可以靠"它们一样"来省掉的一步（`ConvId` 的 KDoc
                                        // 明写「存在的意义是让调用方不必记'私聊不加前缀'这条约定」）。
                                        onClick = { onOpenChat(ConvId.single(hit.nodeId)) },
                                    )
                                }
                            }
                            if (result.chats.isNotEmpty()) {
                                SectionLabel(stringResource(Res.string.global_search_section_messages))
                                result.chats.forEach { hit ->
                                    ChatHitRow(
                                        hit = hit,
                                        now = now,
                                        onClick = { onOpenChat(hit.convId) },
                                    )
                                }
                            }
                            if (result.files.isNotEmpty()) {
                                SectionLabel(stringResource(Res.string.global_search_section_files))
                                result.files.forEach { hit ->
                                    FileHitRow(
                                        hit = hit,
                                        now = now,
                                        // 文件在协议里属文件柜那一套：点它该看到"这个文件在哪"，
                                        // 不只是"谁发过"（`prototype/relay.html:4437-4438`）。
                                        // 落点由调用方决定，这里只报会话键。
                                        onClick = { onOpenFile(hit.convId) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
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
                        .background(MaterialTheme.accents.surface3)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 分组标题。原型 `.spsec{font-size:11px;color:var(--on-2);font-weight:600;letter-spacing:.06em;padding:9px 12px 5px}`。 */
@Composable
private fun SectionLabel(text: String) {
    // 原型 `.spsec{padding:9px 12px 5px}` 里的 9 与 5 不在 AppSpacing 档上，保持字面量；
    // 12 是档上的 `md`，按本轮纪律必须消费 token（值不变 ⇒ 渲染逐字节不变）。
    val sp = LocalAppSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = sp.md, top = 9.dp, end = sp.md, bottom = 5.dp),
    )
}

/** 空态：两行字，标题加粗。原型 `.spph{font-size:12px;color:var(--on-2);text-align:center;margin-top:24px}`。 */
@Composable
private fun EmptyHits(query: String) {
    val sp = LocalAppSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = sp.xl, start = sp.lg, end = sp.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(sp.xs),
    ) {
        Text(
            text = stringResource(Res.string.global_search_empty, query),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.global_search_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 命中行的公共外壳。原型 `.spitem{display:flex;gap:10px;align-items:center;padding:8px 12px}`
 * + `.sptime{font-size:11px;color:var(--on-2);flex:none}`。
 *
 * 抽出来是因为三段的行**只有左侧那一块不同**（联系人放头像、聊天放会话图标、
 * 文件放文件/图片图标），右侧的时间与整体的骨架三行完全一样 —— 三份拷贝迟早会漂。
 */
@Composable
private fun HitRow(
    leading: @Composable () -> Unit,
    title: @Composable () -> Unit,
    subtitle: String,
    time: String,
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
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「联系人」段一行：34dp 圆角方头像（radius 12）+ 昵称 +「研发 · §后端」+ 右侧时间。 */
@Composable
private fun ContactHitRow(hit: GlobalSearchContactHit, onClick: () -> Unit) {
    HitRow(
        leading = {
            // 原型 `.spitem .spav{width:34px;height:34px;border-radius:12px}`，名字取首字。
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
                // 原型 `searchHit` 只在离线时挂这半句（`.t1 .off`），在线不写 —— 在线是常态。
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
        // 原型把联系人那行的时间**写死**成「今天 11:20」（`searchHit` 里是字面量，
        // 没有取真实时刻）—— 联系人没有"最近一条消息"可算。这里照原型写成同一句，
        // 不编一个假时间戳。**但它是用户可见文案** ⇒ 走资源（`:cjkLiteralGate` 判红的就是
        // 这里；写死在代码里还会让英文界面印中文）。
        time = stringResource(Res.string.global_search_contact_time),
        onClick = onClick,
    )
}

/** 「聊天记录」段一行：**一个会话**，标题「与 X 的聊天 · N 条相关」，副行是最新那条命中的摘要。 */
@Composable
private fun ChatHitRow(hit: GlobalSearchChatHit, now: Long, onClick: () -> Unit) {
    HitRow(
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

/** 「文件」段一行：副行写「来自与 X 的聊天」—— 文件本身没有标题可写，出处才是它的身份。 */
@Composable
private fun FileHitRow(hit: GlobalSearchFileHit, now: Long, onClick: () -> Unit) {
    HitRow(
        leading = {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (hit.isImage) Res.drawable.ic_image else Res.drawable.ic_file,
                    ),
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

/** 搜索中：静默期与检索执行期间画它，替掉「敲了字却像没反应」。 */
@Composable
private fun SearchLoading() {
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

/** 搜索失败：给一句失败说明与回到可搜状态的出口，不画一片空白。 */
@Composable
private fun SearchFailed(onClear: () -> Unit) {
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
            AppTextButton(
                text = stringResource(Res.string.action_clear_search),
                onClick = onClear,
            )
        }
    }
}
