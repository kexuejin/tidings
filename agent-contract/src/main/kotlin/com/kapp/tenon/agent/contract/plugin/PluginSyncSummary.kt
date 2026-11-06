// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.agent.contract.plugin

import kotlinx.serialization.Serializable

@Serializable
data class PluginSummaryItem(
    val id: String,
    val name: String,
    val type: String = "agent",
    val ready: Boolean = true,
)

@Serializable
data class AgentPluginSyncPayload(
    val kind: String = KIND,
    val plugins: List<PluginSummaryItem> = emptyList(),
) {
    companion object {
        const val KIND: String = "agent-plugin-sync"
        const val MAX_SYNC_BYTES: Int = 1024
    }
}
