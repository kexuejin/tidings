// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kapp.tenon.agent.AgentToolApprovalStore
import com.kapp.tenon.feature.settings.ToolApprovalRow
import com.kapp.tenon.feature.settings.ToolApprovalsSection
import com.kapp.tenon.feature.settings.ToolApprovalsSource

/**
 * 宿主免批表（`AgentToolApprovalStore`）到 [ToolApprovalsSource] 缝的**薄适配器**。
 *
 * ARP-13②：区块 UI 与撤销纯逻辑搬进了 composeApp（`feature/settings/
 * ToolApprovalsSection.kt`，android 截图门够得着）；桌面侧只留这条 store 接线 ——
 * 除「store 三件事映射成缝的三件事」外没有任何判据。
 *
 * 撤销纪律（逐条撤、`agentKey = null` 两种含义不许混）的判据说明见
 * [ToolApprovalsSource.revokeExactly] 与 [ToolApprovalAdmin.revoke] 的 KDoc；
 * 行为不变量由 `ToolApprovalAdminTest`（经这条适配器端到端钉住）守着。
 */
internal class StoreToolApprovalsSource(private val store: AgentToolApprovalStore) : ToolApprovalsSource {

    override fun snapshotRows(): List<ToolApprovalRow> = store.snapshot().map {
        ToolApprovalRow(
            nodeId = it.nodeId,
            agentKey = it.agentKey,
            tool = it.tool,
            grantedAt = it.grantedAt,
        )
    }

    override fun revokeExactly(nodeId: String, agentKey: String?, tool: String): Int =
        store.revokeExactly(nodeId = nodeId, agentKey = agentKey, tool = tool)

    override fun addChangeListener(listener: () -> Unit): () -> Unit = store.addChangeListener(listener)
}

/** 「免批规则」区块的桌面入口：签名不变，[DesktopTrustedDevicesPanel] 的调用点零改动。 */
@Composable
internal fun DesktopToolApprovalsSection(
    store: AgentToolApprovalStore?,
    /** 设备名（对端昵称，取不到时由调用方给一个可读的替代）。 */
    peerLabel: (String) -> String,
    modifier: Modifier = Modifier,
) {
    ToolApprovalsSection(
        source = store?.let { StoreToolApprovalsSource(it) },
        peerLabel = peerLabel,
        modifier = modifier,
    )
}
