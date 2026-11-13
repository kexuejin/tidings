// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kapp.tenon.agent.RosterWritePolicy
import com.kapp.tenon.feature.settings.RosterWriteSection
import com.kapp.tenon.feature.settings.RosterWriteTierSource

/**
 * 写面档位（`RosterWritePolicy`）到 [RosterWriteTierSource] 缝的**薄适配器**。
 *
 * 与 `StoreToolApprovalsSource` 同一条做法：区块 UI 与切换纯逻辑住 composeApp
 * （android 截图门够得着），桌面侧只留「策略三件事映射成缝的三件事」，没有任何判据。
 *
 * 传的是控制面上的**同一个实例**（`DesktopExtension.rosterWritePolicy`）：另开一个
 * `RosterWritePolicy` 指同一个文件的话，界面切到「直接执行」之后控制面内存里还是
 * `request`，用户下一次写照样被弹卡 —— 而界面上写着「直接执行」。
 */
internal class PolicyRosterWriteTierSource(private val policy: RosterWritePolicy) : RosterWriteTierSource {

    override fun tier(): String = policy.tier()

    override fun error(): String? = policy.error()

    override fun setTier(next: String): Boolean = policy.setTier(next)
}

/** 「agent 写编制授权」区块的桌面入口。 */
@Composable
internal fun DesktopRosterWriteSection(
    policy: RosterWritePolicy?,
    modifier: Modifier = Modifier,
) {
    RosterWriteSection(
        source = policy?.let { PolicyRosterWriteTierSource(it) },
        modifier = modifier,
    )
}
