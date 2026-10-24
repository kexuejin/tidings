// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.ui.components

import com.kapp.tidings.domain.RelayController
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 建群对话框「全新目录」分支下发的宿主 agent 种类（issue 11③：显示 "DSH…" 与传参
 * "Codex" 不同源）。
 *
 * 表单上只读展示的是「DSH（默认本地执行助理）」，onCreateNew 的实参就必须是同一个
 * 宿主 —— 领域侧唯一认的默认常驻种类是 [RelayController.DEFAULT_AGENT_KINDS] 里的
 * `dsh`。把实参改回硬编码 "Codex"（旧行为），本测试变红（反向验证过）。
 */
class WorkspaceGroupCreateDialogTest {

    @Test
    fun defaultAgentKindMatchesDomainDefaultResidentHost() {
        assertTrue(
            WORKSPACE_CREATE_DEFAULT_AGENT_KIND in RelayController.DEFAULT_AGENT_KINDS,
            "建群对话框下发的 agentKind 必须是领域侧认的默认常驻宿主（显示 DSH、传参也必须是 dsh），" +
                "实际是 ${WORKSPACE_CREATE_DEFAULT_AGENT_KIND}",
        )
    }
}
