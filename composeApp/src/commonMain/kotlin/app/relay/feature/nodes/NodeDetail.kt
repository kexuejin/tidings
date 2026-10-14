// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.nodes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.relay.ui.theme.IconSizes
import app.relay.ui.theme.LocalAppSpacing
import app.relay.ui.theme.LocalCardShape
import app.relay.ui.theme.RelayElevations
import app.relay.generated.resources.Res
import app.relay.generated.resources.disc_manual_unreachable
import app.relay.ui.components.KindBadge
import app.relay.ui.components.PeerAvatar
import app.relay.ui.components.StatusDot
import app.relay.ui.theme.accents
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import app.relay.generated.resources.disc_manual_offline
import app.relay.generated.resources.disc_manual_online
import app.relay.generated.resources.ic_chat_bubble
import app.relay.generated.resources.node_detail_info_host
import app.relay.generated.resources.node_detail_info_ip
import app.relay.generated.resources.node_detail_info_platform
import app.relay.generated.resources.node_detail_info_recent
import app.relay.generated.resources.node_detail_info_section
import app.relay.generated.resources.node_detail_never_hint
import app.relay.generated.resources.node_detail_recent_just_now
import app.relay.generated.resources.node_detail_remark_hint
import app.relay.generated.resources.node_detail_remark_placeholder
import app.relay.generated.resources.node_detail_remark_save
import app.relay.generated.resources.node_detail_remark_section
import app.relay.generated.resources.node_detail_ungrouped
import app.relay.generated.resources.nodes_menu_message

// ─────────────────────────────────────────────────────────────────────────────
// 节点详情（手机档）
//
// 原型：`nodeDetailBody(p)` + `screenNodeDetail()`（`prototype/relay.html:3296-3336`）。
// 版式逐条对：`.pad{padding:14px 16px 16px}`（`:571`）→ 外层 14/16/16；
// `.ndhead{display:flex;gap:14px;align-items:center;padding:6px 2px 14px}`（`:300`）；
// `.av.xl{72px;font-size:26px}` + `.av.xl .p{18px;border:2px solid var(--surface)}`（`:301-302`）；
// 两张 `.card` 之间**没有间隙**（`m08a-nodedetail.png` 逐像素实测：备注卡 y420..799、
// 详细信息卡 y795..1239，中间只有 `--shadow` 的 5~6px 柔影，页面底色一次都没回来）
// —— 所以这里不能给 `spacedBy`，与列表页用 `spacedBy(sp.sm)` 隔卡**不是**同一手法。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 详情页的三种在场状态。原型 `nodeDetailBody()` 里状态点与状态词是同一套三档
 * （`prototype/relay.html:3298-3300`）：在线 / 离线 / 没有回应。
 */
private enum class NodePresence { Online, Offline, Never }

/**
 * 备注长度上界，与 `RelaySettings.LIMIT_REMARK`（32）同值
 * （协议定义昵称最长 32 字符）。
 *
 * 自带一份常量而不是引 `RelaySettings`：这里只要一个长度上界，
 * 引进来会把整个设置模型拖进节点页。桌面侧 `DesktopNodesPane` 的 `RemarkLimit` 同一取舍。
 */
private const val DetailRemarkLimit = 32

@Composable
private fun presenceLabel(presence: NodePresence): String = stringResource(
    when (presence) {
        NodePresence.Online -> Res.string.disc_manual_online
        NodePresence.Offline -> Res.string.disc_manual_offline
        NodePresence.Never -> Res.string.disc_manual_unreachable
    },
)

/**
 * 状态点的颜色。原型 `nodeDetailBody()` 逐字
 * `const dot = p.online ? 'var(--online)' : (p.never ? 'var(--error)' : 'var(--on-2)')`
 * （`prototype/relay.html:3298`）。
 *
 * 「离线」用 `onSurfaceVariant` 而不是 `outline`：原型这一档要的是正文次级色（`--on-2`），
 * 而列表页那颗点走的是 `StatusDot` 的 `outline` 默认值 —— 两处不共用一个兜底。
 */
@Composable
private fun presenceDotColor(presence: NodePresence): Color = when (presence) {
    NodePresence.Online -> MaterialTheme.accents.online
    NodePresence.Offline -> MaterialTheme.colorScheme.onSurfaceVariant
    NodePresence.Never -> MaterialTheme.colorScheme.error
}

/**
 * 详情页主体。调用方给 `Modifier.weight(1f)`，滚动在这里面，
 * 所以 `.pad` 的内边距必须写在 `verticalScroll` **之后**（写在前面会变成视口内边距，
 * 内容会从padding下面穿过去）。
 *
 * 备注卡只在 `item.nodeId` 非空时出现：原型 `saveRemark(id)` 自己也有这道闸
 * （`if (el && p.nodeId)`，`prototype/relay.html:3340`）—— 从没握过手的手动地址
 * 没有节点身份，备注存下去是一条挂在 `host:port` 上的孤儿。桌面侧同样只对有
 * `nodeId` 的行画备注卡。
 */
@Composable
internal fun NodeDetailBody(
    item: NodeListItem,
    onSetPeerRemark: ((String, String) -> Unit)?,
    onOpenChat: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val presence = when {
        item.never -> NodePresence.Never
        item.online -> NodePresence.Online
        else -> NodePresence.Offline
    }
    val nodeId = item.nodeId
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = sp.lg, end = sp.lg, top = sp.lg14, bottom = sp.lg),
    ) {
        NodeDetailHeader(item = item, presence = presence)

        if (nodeId != null) {
            RemarkCard(nodeId = nodeId, initial = item.remark, onSave = onSetPeerRemark)
        }

        DetailCard {
            CardGroupHeader(stringResource(Res.string.node_detail_info_section))
            DetailRow(
                label = stringResource(Res.string.node_detail_info_recent),
                value = item.lastSeenText ?: stringResource(Res.string.node_detail_recent_just_now),
            )
            DetailRow(
                label = stringResource(Res.string.node_detail_info_ip),
                value = "${item.host}:${item.port}",
                monospace = true,
            )
            DetailRow(
                label = stringResource(Res.string.node_detail_info_host),
                // 手动地址没有对端声明的主机名（构造 [NodeListItem] 时写的空串，
                // 「而不是编一个「—」出来」）—— 但详情页这一格是**值列**，
                // 空着会让人以为界面坏了，所以这里印占位符；与列表页那个字段的原意不冲突。
                value = item.hostName.ifBlank { "—" },
                monospace = true,
            )
            DetailRow(
                label = stringResource(Res.string.node_detail_info_platform),
                value = item.platform.ifBlank { "—" },
                showDivider = false,
            )
        }

        SendMessageButton(target = nodeId, onOpenChat = onOpenChat)
    }
}

/**
 * 详情页头部：72dp 头像 + 名字 + Agent 标 + 三态药丸 + 「未分组」。
 *
 * `.ndtags` 里那两颗（`prototype/relay.html:305-310`）是**并列**的两种东西：
 * 前者是在场状态（`.pill`，有 on/err 两个变体），后者是分组（`.tag.muted`，
 * 本端还没有分组概念，所以恒印「未分组」—— 规范中对应公司/部门/团队未分组）。
 */
@Composable
private fun NodeDetailHeader(item: NodeListItem, presence: NodePresence) {
    val sp = LocalAppSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = sp.xxs, end = sp.xxs, top = sp.sm6, bottom = sp.lg14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(sp.lg14),
    ) {
        Box {
            PeerAvatar(name = item.displayName, isAgent = item.isAgent, size = 72.dp)
            // `.av .p{border:2px solid var(--surface)}`：描边靠给状态点垫一层
            // surface 底色的圆形内边距做出来（14 + 2×2 = 18px，正是 `.av.xl .p` 的 18px）。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(sp.xxs),
            ) {
                StatusDot(
                    online = presence == NodePresence.Online,
                    size = IconSizes.Inline,
                    color = presenceDotColor(presence),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm),
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.isAgent) {
                    KindBadge(isAgent = true)
                }
            }
            Row(
                modifier = Modifier.padding(top = sp.sm6),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(sp.sm6),
            ) {
                NodeStatePill(presence)
                MutedTag(stringResource(Res.string.node_detail_ungrouped))
            }
        }
    }
}

/**
 * 在场状态药丸。原型 `.pill{font-size:11px;font-weight:600;padding:2px 9px;border-radius:999px}`
 * + `.pill.on{background:color-mix(in srgb, var(--online) 18%, transparent);color:var(--online)}`
 * + `.pill.err{background:var(--error-c);color:var(--on-error-c)}`（`prototype/relay.html:306-309`）。
 *
 * `color-mix(… 18%, transparent)` 在 Compose 里就是同一个颜色的 18% 透明度。
 */
@Composable
private fun NodeStatePill(presence: NodePresence) {
    val sp = LocalAppSpacing.current
    val colorScheme = MaterialTheme.colorScheme
    val container: Color
    val content: Color
    when (presence) {
        NodePresence.Online -> {
            container = MaterialTheme.accents.online.copy(alpha = 0.18f)
            content = MaterialTheme.accents.online
        }
        NodePresence.Offline -> {
            container = colorScheme.surfaceVariant
            content = colorScheme.onSurfaceVariant
        }
        NodePresence.Never -> {
            container = colorScheme.errorContainer
            content = colorScheme.onErrorContainer
        }
    }
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Text(
            text = presenceLabel(presence),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
        )
    }
}

/** `.tag.muted{background:var(--surface-2);color:var(--on-2)}`（`prototype/relay.html:310`）。 */
@Composable
private fun MutedTag(text: String) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = sp.sm6, vertical = sp.xxs),
        )
    }
}

/**
 * 详情页的一张卡：`.card{background:var(--surface);border-radius:var(--r-card);box-shadow}`
 * + `.card .padc{padding:10px 14px 14px}`（`prototype/relay.html:119`、`:126`，
 * 移动档 `--padc:10px 14px 14px`）。圆角走 `LocalCardShape`（手机 16dp，
 * 正是移动档的 `--r-card:16px`）。
 */
@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = LocalCardShape.current,
        shadowElevation = RelayElevations.Card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = sp.lg14,
                end = sp.lg14,
                top = sp.md10,
                bottom = sp.lg14,
            ),
            content = content,
        )
    }
}

/**
 * 卡内小节标题：`.grphead.sm{padding:10px 0 4px;font-size:12px;opacity:.92}`
 * （`prototype/relay.html:311`）。字号走 `bodySmall`（12sp），不是 `labelSmall`（11sp）——
 * 原型 `.sm` 这一档特意从 11px 提到 12px。
 */
@Composable
private fun CardGroupHeader(text: String) {
    val sp = LocalAppSpacing.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = sp.md10, bottom = sp.xs),
    )
}

/**
 * 详细信息里的一行：`.kv{display:flex;gap:12px;padding:9px 14px;border-bottom:1px solid var(--hair)}`
 * + `.kv .k{width:var(--kv-k)}`（移动档 118px）+ `.kv .v.mono{font-size:12px;font-family:ui-monospace}`
 * （`prototype/relay.html:277-284`）。
 *
 * 键列是**固定宽**而不是按内容撑开：四行的值列左缘因此对齐在同一个 x
 * （`m08a-nodedetail.png` 实测四行值列都在 x350）。
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    showDivider: Boolean = true,
) {
    val sp = LocalAppSpacing.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.lg14, vertical = sp.sm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(sp.md),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.width(DetailKeyWidth),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                modifier = Modifier.weight(1f),
            )
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** `.kv-k` 移动档 118px（`prototype/relay.html:59`）；桌面窗覆盖成 104px，那是桌面档的事。 */
private val DetailKeyWidth = 118.dp

/**
 * 备注卡：`.grphead.sm 备注` + `.hint 仅自己可见，重名时好认` + `<textarea>` + `.btn.sm 保存备注`
 * （`prototype/relay.html:3310-3316`）。
 *
 * `onSave == null` 表示「这个壳不提供改备注这条路」（见 `NodesScreen` 形参 KDoc）：
 * 此时输入框只读、保存按钮不画 —— 而不是画一颗按下去什么都不发生的按钮。
 *
 * 原型那颗按钮里的 `${ic('save')}` 是个**空 svg**（原型 sprite 里根本没有 `i-save` 符号，
 * `grep -c 'i-save' prototype/relay.html` = 0），所以这里不补图标是等价的，
 * 不是漏画。
 */
@Composable
private fun RemarkCard(nodeId: String, initial: String, onSave: ((String, String) -> Unit)?) {
    val sp = LocalAppSpacing.current
    var text by remember(nodeId, initial) { mutableStateOf(initial) }
    DetailCard {
        CardGroupHeader(stringResource(Res.string.node_detail_remark_section))
        Text(
            text = stringResource(Res.string.node_detail_remark_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = sp.sm),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { next -> if (next.length <= DetailRemarkLimit) text = next },
            placeholder = { Text(stringResource(Res.string.node_detail_remark_placeholder)) },
            singleLine = false,
            minLines = 2,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onSave != null) {
            OutlinedButton(
                onClick = { onSave(nodeId, text.trim()) },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(top = sp.sm),
            ) {
                Text(stringResource(Res.string.node_detail_remark_save))
            }
        }
    }
}

/**
 * 「发消息」：`.btn.block.pri`（整宽、primary 实心、`--r-ctl` 12dp）
 * + `.btn.pri:disabled{opacity:.4}`（`prototype/relay.html:357-361`）。
 *
 * 原型是 `onclick="go('m-chat')"`（切到会话）、`p.never` 时整颗禁用并在下面补一行
 * 居中提示。这里「能不能发」的判据是**有没有节点身份**（`target == null`）：
 * 手动填的地址在握手之前没有 `nodeId`，会话列表里也没有它的位置
 * —— 原型这条闸（`p.never`）与它是同一件事的两种写法。
 */
@Composable
private fun SendMessageButton(target: String?, onOpenChat: ((String) -> Unit)?) {
    val sp = LocalAppSpacing.current
    val enabled = target != null && onOpenChat != null
    Button(
        onClick = { if (target != null) onOpenChat?.invoke(target) },
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = sp.lg14),
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_chat_bubble),
            contentDescription = null,
            modifier = Modifier.size(IconSizes.Label),
        )
        Spacer(Modifier.width(sp.sm6))
        Text(stringResource(Res.string.nodes_menu_message))
    }
    if (!enabled) {
        Text(
            text = stringResource(Res.string.node_detail_never_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = sp.sm),
        )
    }
}

internal fun Int.ifZero(fallback: Int): Int = if (this == 0) fallback else this