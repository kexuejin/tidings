// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.nodes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.kapp.tidings.ui.components.PressableSurface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.LocalCardShape
import com.kapp.tidings.ui.theme.RelayElevations
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.disc_connect
import com.kapp.tidings.generated.resources.nodes_mdns_row_desc
import com.kapp.tidings.generated.resources.nodes_mdns_row_desc_fmt
import com.kapp.tidings.generated.resources.disc_manual_unreachable
import com.kapp.tidings.ui.components.AppButtonSize
import com.kapp.tidings.ui.components.AppTextButton
import com.kapp.tidings.ui.components.PeerAvatar
import com.kapp.tidings.ui.components.StatusDot
import com.kapp.tidings.ui.theme.accents
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tidings.generated.resources.common_agent
import com.kapp.tidings.generated.resources.common_offline
import com.kapp.tidings.generated.resources.common_online
import com.kapp.tidings.domain.model.MdnsServiceUi

/**
 * 同一档里按显示名排。
 *
 * **为什么不直接用 `String.compareTo`**：原型排的是 `a.nick.localeCompare(b.nick)`
 * （`prototype/relay.html:3298-3299`），它把 `老周` 排在了 `Huang` 前面 —— 而 Kotlin 的
 * 码位比较（拉丁字母 U+0048… 都小于汉字 U+8001）会把它排到最后。两边并排看时，
 * 顺序不同会让「逐行对照」变成「逐行找」，而顺序本身并不是原型想表达的东西。
 *
 * `commonMain` 里没有 `Collator`（那是 JVM 的东西），所以这里用一条**显式、确定**的规则
 * 复现原型渲染出来的那个可观察顺序：汉字名在前、其余按大小写无关的名称序。
 * 排序稳定，两次渲染同一批数据顺序一致（截图门禁的硬要求）。
 */
internal val nodeNameOrder = Comparator<String> { a, b ->
    val aCjk = a.firstOrNull()?.code?.let { it >= 0x2E80 } == true
    val bCjk = b.firstOrNull()?.code?.let { it >= 0x2E80 } == true
    if (aCjk != bCjk) {
        if (aCjk) -1 else 1
    } else {
        a.lowercase().compareTo(b.lowercase())
    }
}
/**
 * 一个节点一行：头像 + 昵称 + 状态 +「地址 · 来源」。
 *
 * 三处不能省的：
 *  ① 「离线」与「没有回应」分开说；
 *  ② 来源（局域网 / 手动添加）降成副标题里的一小段 —— 它不再是分组；
 *  ③ 点整行进这个节点的**详情页**（`m-nodedetail` / 桌面右栏），主操作留在详情里。
 *
 * **行尾那颗 ⋯ 菜单已撤**（原型 `prototype/relay.html:3266` 逐字
 * 「- 行尾那颗 ⋯ 菜单（`m-nodemenu`）—— 管理动作不进主列表。」，`:2380` 注释
 * 「m-nodemenu 已撤」）。昵称优先显示备注（`p.remark || p.nick`，见 `displayName`）。
 */
@Composable
internal fun NodeRow(
    item: NodeListItem,
    onOpen: (() -> Unit)?,
) {
    val sp = LocalAppSpacing.current
    val address = "${item.host}:${item.port}"
    // 还没被认出来的手动地址，displayName 就是 `host:port`。此时：
    // ① 头像首字会退化成「1」「2」这类数字（没有信息量）；
    // ② 副行再印一遍同一串地址（标题=副行，视觉上像重复渲染）。
    val nameIsAddress = item.displayName == address
    val avatarName = if (nameIsAddress) "?" else item.displayName
    // 三档三色，与详情页药丸/状态点同一套：在线 `accents.online`（绿）、
    // 「没有回应」`error`（红）、离线 `onSurfaceVariant`（灰）。
    // 上一版在线与离线同用灰字 —— 状态点已经是绿的了，文字却不认，
    // 两处口径不同；对齐后列表行与详情页说的是同一种颜色语言。
    // 文字**不用**彩色承载状态：`accents.online` 压白底实测 3.41:1、`error` 4.17:1，
    // 都低于 AA 的 4.5:1（11sp Normal 属正常文本），强光/低视力用户读不清这一行最要紧的字。
    // 状态由「头像右下角的三色点 + 文字本身（在线/离线/没有回应）」共同表达：颜色是辅助，
    // 文字才是判据（读屏也只读文字）。上一版"三档三色"把颜色压在了文字上。
    val stateColor = MaterialTheme.colorScheme.onSurfaceVariant
    PressableSurface(
        onClick = onOpen ?: {},
        enabled = onOpen != null,
        color = MaterialTheme.colorScheme.surface,
        // 节点行圆角 = 卡片圆角：原型 `.row{...border-radius:var(--r-card)}`
        // （`prototype/relay.html:147-148`），手机 16px / 桌面窗 12px —— 正好是
        // `LocalCardShape` 那两档。上一版写死 `shapes.medium`(12dp)，手机档就比
        // 原型少 4dp（差集 `m-nodes` 的「radius 只原型{16}」）。
        shape = LocalCardShape.current,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = sp.lg, vertical = sp.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像右下角的在线点：原型 `.av .p{position:absolute;right:0;bottom:0;
            // width:14px;height:14px;border:2px solid var(--surface)}`，三档颜色分别是
            // `never → var(--error)` / `online → var(--online)` / 其余 `var(--on-2)`。
            // 外圈那 2px surface 描边用 `padding(sp.xxs)` 实现（与 PeerCabinetView 的
            // `.hav` 同一个做法），于是 10dp 的点 + 两侧 2dp = 14dp，与原型同尺寸。
            // 头像本身（首字取法/配色）仍与原型不同：见报告的头像层缺口。
            // Box 不写尺寸：`PeerAvatar` 默认 40dp 会把 Box 撑到同一尺寸，而 40 在
            // `AppSpacing` 里没有档位，写死一个裸 `40.dp` 只会给
            // `:designTokenUsageGate` 的 dimension 棘轮多记一笔欠债。
            Box {
                PeerAvatar(name = avatarName, isAgent = item.isAgent)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(sp.xxs),
                ) {
                    StatusDot(
                        online = item.online && !item.never,
                        size = sp.md10,
                        // 只有「没有回应」那一档要覆写成 error；在线/离线交给 StatusDot 自己取
                        // （online→accents.online 绿、否则 outline 灰），正是原型 `.p` 的三档。
                        color = if (item.never) MaterialTheme.colorScheme.error else null,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = sp.lg)) {
                // 原型的这一行只有**昵称**是弹性的：`.row .t1.spread .ell{flex:1;min-width:0}`，
                // 徽标与状态都是 `flex:none`（`.st{margin-left:auto}` 只是给不弹性时的兜底）。
                // 于是空间不够时先挤昵称、昵称短时富余留在昵称与徽标之间 —— 与截图里
                // 「teahouse-mobile  Agent  在线」的排布一致。前一版把状态那一段也写成
                // `weight(1f)`，两端平分富余，昵称被压成 `teahouse-mo…`（原型里完整）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.isAgent) {
                        Text(
                            text = stringResource(Res.string.common_agent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = sp.sm),
                        )
                    }
                    Text(
                        // 三档纯文本，**不带时钟、不带相对时间**。
                        // 的新稿把这一行收敛成「在线 / 离线 / 没有回应」三点：
                        // `nodeRow` 逐字 `const state = p.never ? '没有回应' : (p.online ? '在线' : '离线')`
                        // （`prototype/relay.html:3278-3279`），KDoc 也明写「不区分"握过手又掉"
                        // 和"从来没通"—— 那是清理时才关心的工程细节，界面只用灰点表达」。
                        // 旧稿曾是 `'离线 ' + ic('clock') + p.lastSeen`（见 `relay.html.bak-9bcd1158ee1c`
                        // 的 `nodeRow`），所以上一版画了 `ic_clock_pending` + `nodes_row_offline_at`；
                        // 那一段在新稿里已整条砍掉，留着就会比原型多一颗图标、多一串时间。
                        // 相对时间没有丢：它搬进了详情页「详细信息 → 最近」（`NodeDetailBody`）。
                        text = when {
                            item.never -> stringResource(Res.string.disc_manual_unreachable)
                            item.online -> stringResource(Res.string.common_online)
                            else -> stringResource(Res.string.common_offline)
                        },
                        // 原型 `.st{font-weight:400;font-size:11px}`：labelSmall 的 11sp 对，
                        // 但它的 Medium 字重比原型重一档，所以这里压回 Normal。
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Normal,
                        ),
                        color = stateColor,
                        maxLines = 1,
                        modifier = Modifier.padding(start = sp.sm),
                    )
                }
                // 标题已经是地址时不再重复同一串（原来这两行一字不差）。
                if (!nameIsAddress) Text(
                    text = address,
                    // 原型 `.row .t2.mono`（`prototype/relay.html:3279`）：`${p.host}:${p.port}`
                    // 加等宽字体。那版把「· 局域网 / 手动添加」那半砍了 ——
                    // 来源是"它怎么进来的"，而用户在这一页问的是"谁现在能用"；
                    // 行里只留地址，与 `nodeRow` 的 KDoc 逐字一致。
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = sp.xs),
                )
            }
        }
    }
}

/**
 * 广播到但还没握手的 mDNS 服务。
 *
 * mDNS 只知道地址，真正入表要等一次 entry/ACK 往返，所以这一行不能画成「已知节点」，
 * 只能给一颗「连接」——它点下去就是一次握手，握上了下一帧就会以真节点的样子出现在上面。
 */
@Composable
internal fun MdnsServiceRow(service: MdnsServiceUi, onConnect: () -> Unit) {
    val sp = LocalAppSpacing.current
    // 读屏描述："叫什么 + 它是什么"（文案在 strings.xml，中英各一份）。
    val mdnsRowDescription = stringResource(
        Res.string.nodes_mdns_row_desc_fmt,
        service.serviceName,
        "${service.host}:${service.port}",
        stringResource(Res.string.nodes_mdns_row_desc),
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        // 与 NodeRow 同一档：原型节点行的圆角 = 卡片圆角（`--r-card`，手机 16 / 桌面窗 12）。
        // 上一版写死 `shapes.medium`(12dp)，同一条列表里 mDNS 行与节点行圆角不同档。
        shape = LocalCardShape.current,
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = RelayElevations.Card,
    ) {
        Row(
            // 读屏把"服务名 / 地址 / 连接"读成**一整个**可点单元，而不是三个独立停顿：
            // 这一行的语义是"发现到一台还没配对的设备，点连接去握手"（完整描述文案待新字符串）。
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    // 读屏要说出「这是什么」：只报服务名+地址时，用户不知道点下去是配对握手。
                    contentDescription = mdnsRowDescription
                }
                .padding(horizontal = sp.lg, vertical = sp.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(name = service.serviceName, isAgent = false)
            Column(Modifier.weight(1f).padding(start = sp.md)) {
                Text(
                    text = service.serviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${service.host}:${service.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 行尾动作（原型节点行的行尾一律 `.btn sm`）：列表行内，走小档。
            AppTextButton(
                text = stringResource(Res.string.disc_connect),
                onClick = onConnect,
                size = AppButtonSize.Small,
                // 小档自身只有 ~32dp；命中区抬到 48dp，视觉不变（同 NodeDetail 的复制/移除）。
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
        }
    }
}
/**
 * 「一个节点都没有」时那张**居中的卡**（原型 `m10-nodes-empty`）。
 *
 * 与 [EmptyHint] 的区别不是好看与否，而是**它是不是一个元素**：原型这一块是一张
 * 与其它卡同款的 `Surface`，里面一段居中正文；`EmptyHint` 是一行靠左的 12sp 小字。
 * 两者在屏上占据的高度、对齐方式、字号都不同，而 `design-screen-diff.py` 只比
 * 「取值集合」，看不见「这里本该有一张卡」。
 *
 * 尺寸逐条来自原型（`prototype/relay.html:126,366-369`）：
 * `.card .padc{padding:var(--padc)}`（`--padc:10px 14px 14px`）+ `.empty{padding:22px 12px}`
 * ⇒ 上下 10+22 / 14+22、左右 14+12 = 26。所以这里是 top 32 / bottom 36 / 左右 26。
 *
 * `max-width:330px` **故意不落**：本机卡宽 = 393 − 页面左右各 16 = 361，再减左右各 26
 * 只剩 309 < 330 ⇒ 这条上限在 393dp 的手机上根本不收（原型同样收不到）。
 * 为一个不起作用的约束新增一条 dimension 字面量，只会让 `:designTokenUsageGate` 的
 * 维度欠债多一条 —— 量具红着的时候不该顺手加债。
 */
@Composable
internal fun EmptyCard(text: String) {
    val sp = LocalAppSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = RelayElevations.Card,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = sp.xl + sp.xxs,
                    end = sp.xl + sp.xxs,
                    top = sp.xxl,
                    bottom = sp.xxl + sp.xs,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 分组下方的解释行。空状态必须给原因和出路，不能只留一片空白。 */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = LocalAppSpacing.current.xs, vertical = LocalAppSpacing.current.xs),
    )
}
