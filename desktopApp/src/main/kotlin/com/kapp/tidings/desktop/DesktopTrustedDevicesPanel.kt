// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kapp.tidings.agent.AgentGrantStore
import com.kapp.tidings.agent.GrantReplyOutcome
import com.kapp.tidings.domain.RelayController
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.domain.pairingPayloadForAddresses
import com.kapp.tidings.domain.qrPairingPayloadForAddresses
import com.kapp.tidings.core.protocol.AgentGrantAccess
import com.kapp.tidings.core.protocol.AgentGrantPolicies
import com.kapp.tidings.core.protocol.AgentGrantScopes
import com.kapp.tidings.core.protocol.AgentGrantStates
import com.kapp.tidings.core.qr.QrEncoder
import com.kapp.tidings.generated.resources.*
import com.kapp.tidings.ui.components.PeerAvatar
import com.kapp.tidings.ui.components.QrCodeView
import com.kapp.tidings.ui.theme.LocalAppSpacing
import com.kapp.tidings.ui.theme.RelayShapes
import com.kapp.tidings.ui.theme.accents
import com.kapp.tidings.ui.theme.IconSizes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * 面板上人按下的一个授权决定：**先写本机台账、再回帧**用的同一份事实。
 *
 * 抽成一个数据类而不是 8 个形参：它既是 [TrustedDeviceDecisions] 的入参，也是
 * `agent-grant` 帧的载荷形状 —— 两处用同一个值，就不会出现"台账记的和发出去的不是同一件事"。
 */
internal data class GrantDecision(
    val nodeId: String,
    val state: String,
    val scope: String,
    val agentKey: String? = null,
    val wsId: String? = null,
    val access: String? = null,
    val isMyDevice: Boolean? = null,
    val delegatedApproval: Boolean? = null,
)

/**
 * 把决定回给设备的出口。
 *
 * 实现是 `DesktopExtension.sendGrantReply` → `ControlPlane.sendGrantReply`（发帧只有那一份实现）。
 * 面板只拿这一个窄接口，而不是直接握 `ControlPlane`：单测里塞个假实现就能钉住
 * "面板的每个决定都真的发了帧"（见 `TrustedDeviceDecisionsTest`）。
 */
internal fun interface GrantReplySender {
    suspend fun send(decision: GrantDecision): GrantReplyOutcome

    companion object {
        /** 没接线时的出口：如实说"没发"，不假装成功。 */
        val NotWired: GrantReplySender = object : GrantReplySender {
            override suspend fun send(decision: GrantDecision): GrantReplyOutcome =
                GrantReplyOutcome.Failed(decision.nodeId, "grant-reply-not-wired")
        }
    }
}

/**
 * 「授信设备」面板的四个决定：**先写台账，再回帧**。
 *
 * 修的是这样一条真机缺陷：面板此前只调 `AgentGrantStore.grant / rejectRequest / revokeAll`，
 * 发 `agent-grant` 帧的逻辑埋在 `ControlPlane.writeGrants` 的 `grantReply` 分支里，界面够不到 ——
 * 于是用户在设置里点完"设为我的设备"，手机侧四档状态纹丝不动。
 *
 * 顺序是刻意的（与 `ControlPlane.writeGrants` 同一条纪律）：**台账先落盘**。决定是本机事实，
 * 帧发不出去（对端不认识 `agr1` / 控制面没起）也不该把人的按钮丢掉；回执只负责如实告诉
 * 调用方"发了没有"，绝不把"没发"报成成功。
 */
internal class TrustedDeviceDecisions(
    private val grants: AgentGrantStore,
    private val reply: GrantReplySender,
) {

    suspend fun markAsMyDevice(nodeId: String): GrantReplyOutcome = decide(
        GrantDecision(
            nodeId = nodeId,
            state = AgentGrantStates.APPROVED,
            scope = AgentGrantScopes.ALL,
            access = AgentGrantAccess.OWNER,
            isMyDevice = true,
            delegatedApproval = true,
        ),
    ) {
        grants.grant(
            nodeId = nodeId,
            scope = AgentGrantScopes.ALL,
            agentKey = null,
            access = AgentGrantAccess.OWNER,
            isMyDevice = true,
            delegatedApproval = true,
            grantedBy = GRANTED_BY,
            at = System.currentTimeMillis(),
        )
    }

    /**
     * 批准一条设备申请。`scope = all`（整台设备）与面板两个按钮的语义一致：
     * "批准为我的设备" / "批准协同" 说的都是这台设备，不是某一个 agent。
     *
     * 帧里同样不带 agentKey/wsId —— 与 [AgentGrantStore.grant] 对 `scope=all` 的归一化一致
     * （它也会把 agentKey/wsId 丢掉）。两边不一致时，手机看到的授权范围会比桌面记的更窄。
     */
    suspend fun approve(request: AgentGrantStore.Request, asMyDevice: Boolean): GrantReplyOutcome {
        // 「批准协同」**不照抄对端申请里的高权限档**（task-50 审查发现）。
        //
        // access 是**对端自己在帧里写的**（AgentInbound 把 p["access"] 原样收下），而面板
        // 那两个按钮的文字只说「批准为我的设备 / 批准协同」，界面上**没有**显示申请的是哪一档。
        // 照抄等于让申请方给自己挑权限：一台设备申请 owner，用户点「批准协同」就授出 owner ——
        // 而 owner 同时满足 requireApproval（等于连「能不能替别人批」一起给了）。
        //
        // 所以按**按钮的语义封顶**：批准为我的设备 ⇒ OWNER（那说的就是这台设备本身）；
        // 批准协同 ⇒ 至多 RW（申请只读就给只读，**不放大** —— 与既有测试同一条纪律）。
        val requested = request.access
        val access = when {
            asMyDevice -> AgentGrantAccess.OWNER
            requested == AgentGrantAccess.RO -> AgentGrantAccess.RO
            else -> AgentGrantAccess.RW
        }
        return decide(
            GrantDecision(
                nodeId = request.nodeId,
                state = AgentGrantStates.APPROVED,
                scope = AgentGrantScopes.ALL,
                access = access,
                isMyDevice = asMyDevice,
                delegatedApproval = false,
            ),
        ) {
            grants.grant(
                nodeId = request.nodeId,
                scope = AgentGrantScopes.ALL,
                agentKey = null,
                wsId = null,
                access = access,
                isMyDevice = asMyDevice,
                delegatedApproval = false,
                grantedBy = GRANTED_BY,
                at = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 拒绝一条申请：台账里摘掉那条待办，并回一条 `denied` —— "沉默 ≠ 拒绝"，
     * 不告诉对面的话，手机侧的"已申请待批"永远不翻面。
     */
    suspend fun reject(request: AgentGrantStore.Request): GrantReplyOutcome = decide(
        GrantDecision(
            nodeId = request.nodeId,
            state = AgentGrantStates.DENIED,
            scope = AgentGrantScopes.ALL,
        ),
    ) {
        grants.rejectRequest(request.nodeId, request.agentKey, request.wsId)
    }

    /** 撤销整台设备的信任：台账清空 + 回一条 `revoked`（只拦未来投递，见 AgentGrantStore 的边界 1）。 */
    suspend fun revoke(nodeId: String): GrantReplyOutcome = decide(
        GrantDecision(nodeId = nodeId, state = AgentGrantStates.REVOKED, scope = AgentGrantScopes.ALL),
    ) {
        grants.revokeAll(nodeId)
    }

    private suspend fun decide(decision: GrantDecision, write: () -> Unit): GrantReplyOutcome {
        write()
        return reply.send(decision)
    }

    private companion object {
        /** 台账写入方标记：与 `ControlPlane` 的 `grantedBy` 区分开，真机排查时能看出是谁批的。 */
        const val GRANTED_BY = "desktop-ui"
    }
}

/**
 * 申请卡片上「对端申请的档位」要显示成什么（task-50 审查补的另一半）。
 *
 * 为什么要有它：**封顶解决了「给多了」，没解决「用户不知道自己在批什么」** ——
 * `access` 是**对端自己在帧里写的**，而两个按钮的文字只有「批准协同 / 批准为我的设备」。
 * 所以卡片必须**如实显示**申请档位，并同时标出本机这个按钮最多给到哪一档。
 *
 * 归一化口径与 [AgentGrantStore] 的 `normalizedAccess` 一致：不认识的档一律按 rw
 * （既不许把脏值原样当事实显示，也不许让它变成"看起来更高"的档）。
 */
internal fun requestedAccessCode(access: String?): String =
    access?.takeIf { it in AgentGrantAccess.VALUES } ?: AgentGrantAccess.RW

/**
 * 本机那两个按钮各自**最多**授予哪一档 —— 必须与 [TrustedDeviceDecisions.approve] 的封顶
 * 逐字一致，否则卡片上的承诺会又一次与按钮的实际效果不符。
 */
internal fun approveCeilingCode(asMyDevice: Boolean): String =
    if (asMyDevice) AgentGrantAccess.OWNER else AgentGrantAccess.RW

/**
 * 档位代号 → 界面上那句话（中文里同时给出人话与代号，见两份 strings 的取值）。
 *
 * 显示的是**对端申请的原样档位**（经 [requestedAccessCode] 归一化），不是本机打算给的那一档 ——
 * 两者必须分开显示，否则用户看到的就是"我想给的"而不是"对方要的"。
 */
internal fun requestedAccessKey(access: String?): String = when (requestedAccessCode(access)) {
    AgentGrantAccess.RO -> "trusted_devices_access_ro"
    AgentGrantAccess.ADMIN -> "trusted_devices_access_admin"
    AgentGrantAccess.OWNER -> "trusted_devices_access_owner"
    else -> "trusted_devices_access_rw"
}

/**
 * 档位代号 → 界面上那句话（中文里同时给出人话与代号，见两份 strings 的取值）。
 *
 * 分成 [requestedAccessKey] + 这个查表两步，是为了让**映射本身可单测**：单测只断言键
 * （纯字符串），不需要把资源访问器搬进测试源集。
 */
@Composable
internal fun requestedAccessLabel(access: String?): String = when (requestedAccessKey(access)) {
    "trusted_devices_access_ro" -> stringResource(Res.string.trusted_devices_access_ro)
    "trusted_devices_access_admin" -> stringResource(Res.string.trusted_devices_access_admin)
    "trusted_devices_access_owner" -> stringResource(Res.string.trusted_devices_access_owner)
    else -> stringResource(Res.string.trusted_devices_access_rw)
}

@Composable
internal fun DesktopTrustedDevicesPanel(
    state: RelayUiState,
    controller: RelayController,
    grantStore: AgentGrantStore?,
    /**
     * 把决定回给设备的出口（实现在 DesktopExtension → ControlPlane）。
     *
     * 默认值是 [GrantReplySender.NotWired]（如实报"没发"），所以预览/截图这类不接线的
     * 调用点不会假装发了帧。生产调用点只有一个：DesktopHosting 的 DesktopSettingsPane。
     */
    grantReplySender: GrantReplySender = GrantReplySender.NotWired,
    onCopyNodeId: (String) -> Unit = { putTextOnClipboard(it) },
    onCopyPairingLink: (String) -> Unit = { putTextOnClipboard(it) },
    modifier: Modifier = Modifier,
) {
    val sp = LocalAppSpacing.current
    val uiScope = rememberCoroutineScope()
    // 上一次决定的回执。只活在界面里（不落盘）：它说的是"我刚按的那一下发了没有"，
    // 不是一条能跨重启的结论。
    var lastReply by remember { mutableStateOf<GrantReplyOutcome?>(null) }
    val decisions = remember(grantStore, grantReplySender) {
        grantStore?.let { TrustedDeviceDecisions(it, grantReplySender) }
    }
    val candidates = remember(state.network.phase) { localCandidateAddresses() }
    // 二维码与"复制链接"分工（同 DesktopConversationsPane 的取法）：二维码受 QrEncoder 的
    // 版本 10 / 等级 M 容量（213 字节）约束 —— 这台机器 12 个候选的全量载荷 763 字节，
    // 直接 `encode` 会抛异常 ⇒ 面板上**一张码都画不出来**（旧代码就是全量喂给编码器，
    // 只是当时候选只有 v4、超容量超得没那么明显）。链接不限长，仍然带全部候选。
    val qrPayload = remember(state.selfNodeId, candidates, state.settings.udpPort) {
        qrPairingPayloadForAddresses(
            nodeId = state.selfNodeId,
            addresses = candidates,
            port = state.settings.udpPort,
        )
    }
    val pairingPayload = remember(state.selfNodeId, state.settings.nick, candidates, state.settings.udpPort) {
        pairingPayloadForAddresses(
            nodeId = state.selfNodeId,
            nick = state.settings.nick,
            addresses = candidates,
            port = state.settings.udpPort,
        ).orEmpty()
    }
    val qrCode = remember(qrPayload) {
        qrPayload?.let { runCatching { QrEncoder.encode(it) }.getOrNull() }
    }

    var snapshotEpoch by remember { mutableIntStateOf(0) }
    // **外部变更也要刷新**：申请是从网络来的，不是用户在这页上点的。
    // 没有这一条，别人发来的申请要等用户手动离开再回来才看得见 —— 那等于
    // 「有东西在等批准」这件事永远不会自己冒出来。
    DisposableEffect(grantStore) {
        val off = grantStore?.addChangeListener { snapshotEpoch++ }
        onDispose { off?.invoke() }
    }
    val snapshot = remember(grantStore, snapshotEpoch) {
        grantStore?.snapshot()
    }
    val currentPolicy = snapshot?.policy ?: AgentGrantPolicies.REQUEST

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(sp.lg),
        verticalArrangement = Arrangement.spacedBy(sp.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(sp.xxs)) {
            Text(
                text = stringResource(Res.string.desk_nav_trusted_devices),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(Res.string.trusted_devices_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 上一次决定的回执：**如实**分"已送达"与"发送失败（含原因）"两档。
            // 面板此前只写本机台账、不发帧，用户点完手机看不到；现在发了，就必须把
            // "没发出去"（对端不认识 agr1 / 控制面没起）说出来，而不是默认已发送。
            lastReply?.let { outcome ->
                GrantReplyStatusLine(
                    outcome = outcome,
                    peerLabel = state.peer(outcome.nodeId)?.displayName ?: outcome.nodeId.take(8),
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RelayShapes.medium,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RelayShapes.medium),
        ) {
            Column(modifier = Modifier.padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.md)) {
                Text(
                    text = stringResource(Res.string.trusted_devices_qr_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(sp.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (qrCode != null) {
                        Surface(
                            color = Color.White,
                            shape = RelayShapes.medium,
                            shadowElevation = 2.dp,
                        ) {
                            QrCodeView(
                                code = qrCode,
                                contentDescription = stringResource(Res.string.trusted_devices_qr_title),
                                modifier = Modifier.padding(sp.sm).size(140.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(sp.xs),
                    ) {
                        Text(
                            text = "${state.settings.nick} (${state.selfNodeId.take(8)})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(Res.string.trusted_devices_qr_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(sp.sm)) {
                            OutlinedButton(
                                onClick = { onCopyNodeId(state.selfNodeId) },
                                contentPadding = PaddingValues(horizontal = sp.sm, vertical = sp.xxs),
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(IconSizes.Inline))
                                Spacer(Modifier.width(sp.xs))
                                Text(stringResource(Res.string.trusted_devices_copy_node_id), style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onCopyPairingLink(pairingPayload) },
                                contentPadding = PaddingValues(horizontal = sp.sm, vertical = sp.xxs),
                            ) {
                                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(IconSizes.Inline))
                                Spacer(Modifier.width(sp.xs))
                                Text(stringResource(Res.string.trusted_devices_copy_pairing_link), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RelayShapes.medium,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RelayShapes.medium),
        ) {
            Column(modifier = Modifier.padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.md)) {
                Text(
                    text = stringResource(Res.string.trusted_devices_policy_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val policies = listOf(
                    AgentGrantPolicies.REQUEST to stringResource(Res.string.trusted_devices_policy_request),
                    AgentGrantPolicies.AUTO to stringResource(Res.string.trusted_devices_policy_auto),
                    AgentGrantPolicies.BY_DEVICE to stringResource(Res.string.trusted_devices_policy_by_device),
                )
                Column(verticalArrangement = Arrangement.spacedBy(sp.xs)) {
                    policies.forEach { (pKey, pLabel) ->
                        val selected = pKey == currentPolicy
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RelayShapes.small)
                                .clickable {
                                    grantStore?.setPolicy(pKey)
                                    snapshotEpoch++
                                }
                                .padding(vertical = sp.xs, horizontal = sp.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(sp.sm),
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    grantStore?.setPolicy(pKey)
                                    snapshotEpoch++
                                },
                            )
                            Text(
                                text = pLabel,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        val pendingRequests = snapshot?.requests.orEmpty()
        if (pendingRequests.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RelayShapes.medium,
                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RelayShapes.medium),
            ) {
                Column(modifier = Modifier.padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.md)) {
                    Text(
                        text = stringResource(Res.string.trusted_devices_requests_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    pendingRequests.forEach { req ->
                        val peerNick = state.peer(req.nodeId)?.displayName ?: req.nodeId.take(8)
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RelayShapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(sp.md).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(sp.md),
                            ) {
                                PeerAvatar(name = peerNick, size = 40.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = peerNick, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text(text = "nodeId: ${req.nodeId.take(8)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    // task-50 审查补的那一半：**如实显示对端申请的是哪一档**，并标出
                                    // 本机两个按钮各自的上限。封顶只解决「给多了」，不解决「用户不知道
                                    // 自己在批什么」—— 后者正是「界面不许比事实更少」。
                                    Text(
                                        text = stringResource(
                                            Res.string.trusted_devices_request_access_label,
                                            requestedAccessLabel(req.access),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(
                                            Res.string.trusted_devices_ceiling_hint,
                                            requestedAccessLabel(approveCeilingCode(asMyDevice = false)),
                                            requestedAccessLabel(approveCeilingCode(asMyDevice = true)),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                                    Button(
                                        onClick = {
                                            decisions?.let { d ->
                                                uiScope.launch {
                                                    lastReply = d.approve(req, asMyDevice = true)
                                                    snapshotEpoch++
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = sp.sm, vertical = sp.xxs),
                                    ) {
                                        Text(stringResource(Res.string.trusted_devices_approve_as_my), style = MaterialTheme.typography.labelSmall)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            decisions?.let { d ->
                                                uiScope.launch {
                                                    lastReply = d.approve(req, asMyDevice = false)
                                                    snapshotEpoch++
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = sp.sm, vertical = sp.xxs),
                                    ) {
                                        Text(stringResource(Res.string.trusted_devices_approve_normal), style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(
                                        onClick = {
                                            decisions?.let { d ->
                                                uiScope.launch {
                                                    lastReply = d.reject(req)
                                                    snapshotEpoch++
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = sp.sm, vertical = sp.xxs),
                                    ) {
                                        Text(stringResource(Res.string.trusted_devices_reject), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RelayShapes.medium,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RelayShapes.medium),
        ) {
            Column(modifier = Modifier.padding(sp.lg), verticalArrangement = Arrangement.spacedBy(sp.md)) {
                Text(
                    text = stringResource(Res.string.trusted_devices_list_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val allGrants = snapshot?.grants.orEmpty()
                val knownPeers = state.peers.filter { it.nodeId != state.selfNodeId }
                val displayNodeIds = (allGrants.map { it.nodeId } + knownPeers.map { it.nodeId }).distinct()

                if (displayNodeIds.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.trusted_devices_list_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    displayNodeIds.forEach { nId ->
                        val grant = allGrants.firstOrNull { it.nodeId == nId }
                        val peer = state.peer(nId)
                        val name = peer?.displayName ?: nId.take(8)
                        val isOnline = peer?.online == true
                        val isMyDevice = grant?.isMyDevice == true

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = sp.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(sp.md),
                        ) {
                            PeerAvatar(name = name, size = 36.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(sp.xs),
                                ) {
                                    Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Box(
                                        modifier = Modifier
                                            .size(LocalAppSpacing.current.sm)
                                            .clip(CircleShape)
                                            // 在线绿改用主题 token：写死 0xFF10B981 在深色下是"深绿落深底"
                                            // （accents.online 的深色值就是为此换过的，见 Accents.kt 的 KDoc）。
                                            .background(if (isOnline) MaterialTheme.accents.online else MaterialTheme.colorScheme.outlineVariant),
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                                    // 地址标签先算成局部值再交给 Text：原来的写法把 if 和两个嵌套字符串
                                    // 塞进同一个 ${...} 里，而 CJK 门禁的词法器在"插值里再嵌字符串"上会失步
                                    // —— 失步后它把本行以下的整段源码当成字符串字面量，于是任何新加的中文
                                    // 注释都会被误报成"硬编码文案"。拆开既是可读性，也是给门禁拆雷。
                                    val addressLabel = if (peer != null) "${peer.ip}:${peer.udpPort}" else "node-${nId.take(8)}"
                                    Text(
                                        text = addressLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (isMyDevice) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.workspace_role_my_device),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
                                    )
                                }
                            } else if (grant != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = CircleShape,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.workspace_role_collaborator),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = sp.sm, vertical = sp.xxs),
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(sp.xs)) {
                                if (!isMyDevice) {
                                    Button(
                                        onClick = {
                                            decisions?.let { d ->
                                                uiScope.launch {
                                                    lastReply = d.markAsMyDevice(nId)
                                                    snapshotEpoch++
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = sp.sm, vertical = sp.xxs),
                                    ) {
                                        Text(stringResource(Res.string.trusted_devices_btn_mark_my), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (grant != null) {
                                    OutlinedButton(
                                        onClick = {
                                            decisions?.let { d ->
                                                uiScope.launch {
                                                    lastReply = d.revoke(nId)
                                                    snapshotEpoch++
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = sp.sm, vertical = sp.xxs),
                                    ) {
                                        Text(stringResource(Res.string.trusted_devices_btn_revoke), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

/**
 * 上一次授权决定的回执行：**如实**说"发了没有"。
 *
 * 为什么需要它：面板的每个按钮都只写本机台账（那是本机事实），而帧能不能发出去取决于
 * 对端是否声明 `agr1`、以及控制面在不在跑 —— 这两件事都会让"点了按钮"与"手机知道了"
 * 不是同一件事。没有这一行，用户只能假设它已经生效（那正是修之前的状态）。
 *
 * 文案复用既有的 `chat_delivery_sent` / `chat_delivery_failed`（交付语义与这里一致），
 * 失败时把**原因**以数据形式附在后面：对端不认识 `agr1` 时是能力名，发送异常时是异常消息。
 * 刻意不在本文件新增中文文案字面量：本文件的用户可见文案一律走 composeResources，
 * 而资源目录在 composeApp/（本轮改动范围之外，且新增 key 要同时动 values/ 与 values-en/）。
 * 那边补上 key 之后，这一行可以直接换成资源。
 */
@Composable
private fun GrantReplyStatusLine(outcome: GrantReplyOutcome, peerLabel: String) {
    val delivered = outcome is GrantReplyOutcome.Sent
    val stateLabel = stringResource(
        if (delivered) Res.string.chat_delivery_sent else Res.string.chat_delivery_failed,
    )
    val detail = when (outcome) {
        is GrantReplyOutcome.Sent -> null
        // 能力名本身就是那句原因：对端没声明 agr1 ⇒ 它不认识 grant 帧。
        is GrantReplyOutcome.Unsupported -> outcome.cap
        is GrantReplyOutcome.Failed -> outcome.reason.takeIf { it.isNotBlank() }
    }
    Text(
        text = listOfNotNull(peerLabel, stateLabel, detail).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = if (delivered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

