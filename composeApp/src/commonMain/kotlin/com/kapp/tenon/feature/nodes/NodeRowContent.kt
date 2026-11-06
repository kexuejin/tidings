// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.nodes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.common_agent
import com.kapp.tenon.generated.resources.common_offline
import com.kapp.tenon.generated.resources.common_online
import com.kapp.tenon.generated.resources.disc_manual_unreachable
import com.kapp.tenon.ui.components.KindBadge
import com.kapp.tenon.ui.components.PeerAvatar
import com.kapp.tenon.ui.components.StatusDot
import com.kapp.tenon.ui.theme.IconSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.theme.accents
import org.jetbrains.compose.resources.stringResource

/**
 * 节点行的**内容原语**：头像 + 在场点 + 名字/Agent 标记/在场文案 + 副行。
 *
 * ## 为什么抽出来（AGENTS §2.6）
 *
 * 手机 [NodeRow] 与桌面 `NodeListRow` 画的是**同一件事**（这个节点是谁、在不在、地址）。
 * 此前两份实现各写一份：手机把「在线/离线」放第一行、桌面折进第二行「在线 · ip:port」；
 * 头像尺寸与状态点样式也各有一份。同一批数据在两个壳里长成两种行 —— 正是
 * `ConversationRow` 那次事故的同族形态。
 *
 * 抽出来之后：**内容**只有这一份，**外壳**（选中底色/hairline/点击/圆角）留在各自
 * 壳里；两壳的密度差异收敛成 [NodeRowDensity] 一个枚举（尺寸/间距/等宽副行/标记样式），
 * 不再由调用点传裸 dp 与 style。
 *
 * ## 两处按原型真实保留的差异
 *
 * - [inlinePresence]：手机把在场文案画在**标题行**（原型 `.row .t1 .st`），桌面把它
 *   折进**副行**（`.li` 的第二行「在线 · IP:端口」）。这是两套原型各自的形状，
 *   属于内容语义的一部分，不硬凑成一版。
 * - 头像首字：手机对「显示名就是地址」的行用 `?`（否则首字退化成 `1`/`2` 这类数字，
 *   没有信息量）；桌面列表按原型直接取显示名。
 */
internal enum class NodeRowDensity {
    /** 手机：卡片式行，头像 40dp，副行等宽地址。 */
    Phone,

    /** 桌面左栏：紧凑行，头像 32dp，副行「在场 · 地址」。 */
    Desktop,
}

/** 桌面左栏头像尺寸（原型 `.li .av{width:32px;height:32px}`）。 */
private val DesktopAvatarSize = 32.dp

@Composable
internal fun NodeRowContent(
    item: NodeListItem,
    density: NodeRowDensity,
    /** true = 在场文案画在标题行（手机）；false = 折进副行（桌面）。 */
    inlinePresence: Boolean,
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val address = "${item.host}:${item.port}"
    val nameIsAddress = item.displayName == address
    val avatarName = if (density == NodeRowDensity.Phone && nameIsAddress) "?" else item.displayName
    val stateText = stringResource(
        when {
            item.never -> Res.string.disc_manual_unreachable
            item.online -> Res.string.common_online
            else -> Res.string.common_offline
        },
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (density == NodeRowDensity.Phone) sp.lg else sp.sm,
                vertical = if (density == NodeRowDensity.Phone) sp.md else sp.md10,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (density == NodeRowDensity.Desktop) {
            Arrangement.spacedBy(sp.md10)
        } else {
            Arrangement.Start
        },
    ) {
        NodeRowAvatar(item = item, density = density, avatarName = avatarName, stateTextIsNever = item.never)
        // 桌面档不加内边距（外层行已带 sp.sm），写成条件 Modifier 而不是 `padding(start = 0.dp)`
        // —— 「零间距」不该以字面量的形态成为间距欠债。
        val contentModifier = if (density == NodeRowDensity.Phone) {
            Modifier.padding(start = sp.lg)
        } else {
            Modifier
        }
        Column(Modifier.weight(1f).then(contentModifier)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (density == NodeRowDensity.Desktop) {
                    Arrangement.spacedBy(sp.xs)
                } else {
                    Arrangement.Start
                },
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 手机：昵称吃掉剩余宽度，标记与在场文案靠右；桌面：昵称只占自己的宽，
                    // 徽标紧贴名字（原型 `.dh2` 里 badge 就写在标题旁边）。
                    modifier = if (density == NodeRowDensity.Phone) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.weight(1f, fill = false)
                    },
                )
                if (item.isAgent) {
                    if (density == NodeRowDensity.Phone) {
                        Text(
                            text = stringResource(Res.string.common_agent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = sp.sm),
                        )
                    } else {
                        KindBadge(isAgent = true)
                    }
                }
                if (inlinePresence) {
                    Text(
                        text = stateText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = sp.sm),
                    )
                }
            }
            if (inlinePresence) {
                // 标题已经是地址时不再重复同一串（原来这两行一字不差）。
                if (!nameIsAddress) {
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = sp.xs),
                    )
                }
            } else {
                Text(
                    text = stateText + " · " + address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 头像 + 右下角在场点。两档的**尺寸与描边**不同（原型各自的 `.av` 规格），
 * 状态点颜色三档同源：在线绿 / 离线灰 / 没有回应红。
 */
@Composable
private fun NodeRowAvatar(
    item: NodeListItem,
    density: NodeRowDensity,
    avatarName: String,
    stateTextIsNever: Boolean,
) {
    val sp = LocalAppSpacing.current
    if (density == NodeRowDensity.Phone) {
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
                    online = item.online && !stateTextIsNever,
                    size = sp.md10,
                    color = if (stateTextIsNever) MaterialTheme.colorScheme.error else null,
                )
            }
        }
    } else {
        Box {
            PeerAvatar(
                name = avatarName,
                isAgent = item.isAgent,
                size = DesktopAvatarSize,
                shape = MaterialTheme.shapes.medium,
            )
            Box(Modifier.align(Alignment.BottomEnd)) {
                StatusDot(
                    online = item.online && !stateTextIsNever,
                    size = IconSizes.Badge,
                    color = when {
                        stateTextIsNever -> MaterialTheme.colorScheme.error
                        item.online -> MaterialTheme.accents.online
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
