// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.domain.pairingPayloadForAddresses

import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.ui.components.StatusDot
import com.kapp.tidings.ui.theme.RelayElevations
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.desktop_menu_diagnostics
import com.kapp.tidings.generated.resources.desktop_phase_failed
import com.kapp.tidings.generated.resources.desktop_phase_running
import com.kapp.tidings.generated.resources.desktop_phase_starting
import com.kapp.tidings.generated.resources.desktop_status_connected
import com.kapp.tidings.generated.resources.desktop_status_control_plane
import com.kapp.tidings.generated.resources.desktop_status_control_plane_off
import com.kapp.tidings.generated.resources.desktop_status_local
import com.kapp.tidings.generated.resources.desktop_status_pairing_link
import com.kapp.tidings.generated.resources.desktop_status_pending
import com.kapp.tidings.generated.resources.desktop_status_waiting
import com.kapp.tidings.generated.resources.node_state_stopped
import com.kapp.tidings.generated.resources.ic_eye
import com.kapp.tidings.generated.resources.ic_link
import com.kapp.tidings.domain.model.NodePhase
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// ------------------------------------------------------------------ 状态栏

@Composable
public fun DesktopStatusBar(
    state: RelayUiState,
    view: DesktopView,
    sizeClass: DesktopWindowSizeClass,
    controlPlanePort: Int,
    actions: DesktopShellActions,
) {
    val phaseColor = when (state.network.phase) {
        NodePhase.Running -> MaterialTheme.colorScheme.primary
        NodePhase.Starting -> MaterialTheme.colorScheme.tertiary
        NodePhase.Failed -> MaterialTheme.colorScheme.error
        NodePhase.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val compact = sizeClass == DesktopWindowSizeClass.Compact
    // 配对链接要从**自己**的地址与端口拼，而这两样都不在 `state.peers` 里：
    // `PeerRegistry` 的设计契约就是为了拒绝记录自己。
    // 早先这里用的是 `state.peer(state.selfNodeId)`，恒为 null ⇒ 「配对链接」这颗按钮
    // **在生产里从来没画出来过**（原型 `prototype/relay.html:2611-2622` 的 `dstatusbar()` 有它）。
    // 现照仓里同一份取法改写：`DesktopConversationsPane` 的首启空态、
    // `feature/nodes/NodeDetail.kt`、`feature/settings/SettingsHomeScreen.kt` 都是这么取的。
    // 端口取真正绑上的那个；`boundUdpPort == 0`（网络还没起来）时退回设置里配的那个
    // —— `feature/nodes/NodeDetail.kt` 里有个同款 `Int.ifZero`，现在它是 internal 的，
    // 这里仍然把同一个表达式写开（桌面状态栏与节点详情不是一个特性包）。
    val selfPort = state.network.boundUdpPort.takeIf { it != 0 } ?: state.settings.udpPort
    // 配对链接用**全部候选**（lan_v4 / lan_v6 / public_v6）：只印一个 IPv4 时，手机换到
    // 移动网就只剩"手动填地址"一条路。仍走权威构造器：它判类型、给 IPv6 加方括号、丢掉
    // link-local，并且**一个可用地址都没有时返回 null** —— 那时宁可不给这颗按钮，
    // 也不让用户复制走一条 `relay://:0`。
    val pairingLink = pairingPayloadForAddresses(
        nodeId = state.selfNodeId,
        nick = state.settings.nick,
        // 用「宣告」那份：含隧道地址。本地那份刻意过滤 utun/feth/tailscale，
        // 拿它生成链接会让「换到别的网络（或走 Tailscale）的手机」只能手动填地址。
        addresses = state.network.advertisedAddresses,
        port = selfPort,
        // task-86：复制链接这条路也要带 pin —— 公网目标要求 pin != null（fail-closed）。
        tlsPin = state.network.selfTlsPin,
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = RelayElevations.Card) {
        Row(
            Modifier.fillMaxWidth().height(DesktopMetrics.STATUSBAR_PX.dp).padding(horizontal = LocalAppSpacing.current.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.md10),
        ) {
            StatusDot(state.network.isRunning && state.onlineCount > 0, 8.dp, phaseColor)
            // 一个节点都没连上时不能说「已连接 0 个节点」—— 那是纸面上就自相矛盾的话
            // （原型 README:90-92 专门点了这一条：「不能写已连接 4 个节点」）。
            // 但「正在等待设备」只对"网络在跑、只是没人来"成立：网络本身没起来时
            // 该报的是那个失败原因，而不是一句让人以为一切正常的等待语。
            Text(
                when {
                    state.onlineCount > 0 -> stringResource(Res.string.desktop_status_connected, state.onlineCount)
                    state.network.isRunning -> stringResource(Res.string.desktop_status_waiting)
                    else -> stringResource(phaseLabel(state.network.phase))
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
            )
            // 原型 README:78-80：状态栏永不换行，「本机 …」这条长描述（.sbl）
            // 在 Compact 直接隐藏，而不是让它把整行挤到折行。
            if (!compact) {
                Text(
                    stringResource(Res.string.desktop_status_local, state.settings.nodeId.take(8), state.settings.nick),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (state.totalPending > 0) {
                // 与 DesktopConversationsPane 的排队徽标同一个理由：排队色是
                // secondaryContainer/onSecondaryContainer，tertiaryContainer 本 App 没赋值。
                Text(
                    stringResource(Res.string.desktop_status_pending, state.totalPending),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // 端口与控制面属「次要项」：原型在 Medium 就把次要项收起来（README:79-80）。
            // 两者都只在这一处出现 —— 早先额外渲染过一个裸的 boundUdpPort，
            // 和后面的「UDP … · TCP …」并排就成了「17878 UDP 17878」。
            if (sizeClass == DesktopWindowSizeClass.Expanded) {
                Text(
                    // TCP 端口同样不能从 `state.peers` 里找自己（见上面配对链接那段）：
                    // 那会恒读成 0。设置里那个才是配着要用的端口（`ScanRangeEntry`）。
                    "UDP $selfPort · TCP ${state.settings.tcpPort}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                // controlPlaneLabel 自带「控制面」前缀，这里不要再拼一次，
                // 否则读作「控制面 控制面 127.0.0.1:56744」。
                Text(
                    // 「控制面未启用」那条没有占位符，多传的参数不会渲染出来。
                    stringResource(controlPlaneLabel(controlPlanePort), controlPlanePort),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (pairingLink != null) {
                StatusBarAction(stringResource(Res.string.desktop_status_pairing_link), painterResource(Res.drawable.ic_link), compact) { actions.onCopyPairingLink(pairingLink) }
            }
            // 诊断包是桌面专属能力：调用方给 null 时整条动作不画。
            val exportDiagnostics = actions.onExportDiagnostics
            if (exportDiagnostics != null) {
                StatusBarAction(stringResource(Res.string.desktop_menu_diagnostics), painterResource(Res.drawable.ic_eye), compact, exportDiagnostics)
            }
        }
    }
}

/**
 * 状态栏右侧动作。
 *
 * Compact 下只留图标（原型 README:79-80「右侧动作在窄窗收成纯图标」）：状态栏高度固定，
 * 三个带文字的按钮在 560px 宽下必然互相挤压，而图标至少还能靠 tooltip/无障碍读出来。
 *
 * 形参类型是 `Painter` 而不是 `ImageVector`：图标一套化后调用方传的是
 * `painterResource(Res.drawable.ic_link)`（原型 stroke 资源），不是 Material 字形。
 * 这里保留 `Painter` 这个**两族共有的上层类型**，是为了让"图标即数据"的位置既能收
 * 资源、也能在过渡期收 ImageVector，而不是把调用点绑死在某一族上。
 */
@Composable
public fun StatusBarAction(
    label: String,
    icon: androidx.compose.ui.graphics.painter.Painter,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(LocalAppSpacing.current.sm6))
            .padding(horizontal = if (compact) LocalAppSpacing.current.sm6 else LocalAppSpacing.current.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            icon,
            // 收成图标后文字没了，语义只能靠 contentDescription 保住。
            contentDescription = if (compact) label else null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        if (!compact) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

public fun phaseLabel(phase: NodePhase): StringResource = when (phase) {
    NodePhase.Stopped -> Res.string.node_state_stopped
    NodePhase.Starting -> Res.string.desktop_phase_starting
    NodePhase.Running -> Res.string.desktop_phase_running
    NodePhase.Failed -> Res.string.desktop_phase_failed
}

/**
 * 控制面那一行。带端口的那条是 `%1$d`，端口由调用点自己传进来 ——
 * [StringResource] 只记得住"是哪条文案"，记不住参数。
 */
public fun controlPlaneLabel(port: Int): StringResource =
    if (port > 0) Res.string.desktop_status_control_plane else Res.string.desktop_status_control_plane_off
