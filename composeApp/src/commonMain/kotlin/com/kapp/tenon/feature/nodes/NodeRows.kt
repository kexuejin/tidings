// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.nodes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.kapp.tenon.ui.components.PressableSurface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.LocalCardShape
import com.kapp.tenon.ui.theme.RelayElevations
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.disc_connect
import com.kapp.tenon.generated.resources.nodes_mdns_row_desc
import com.kapp.tenon.generated.resources.nodes_mdns_row_desc_fmt
import com.kapp.tenon.ui.components.AppButtonSize
import com.kapp.tenon.ui.components.AppTextButton
import com.kapp.tenon.ui.components.PeerAvatar
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.kapp.tenon.domain.model.MdnsServiceUi

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
    PressableSurface(
        onClick = onOpen ?: {},
        enabled = onOpen != null,
        color = MaterialTheme.colorScheme.surface,
        shape = LocalCardShape.current,
        modifier = Modifier.fillMaxWidth(),
    ) {
        NodeRowContent(
            item = item,
            density = NodeRowDensity.Phone,
            inlinePresence = true,
        )
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
