// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.guards

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Agent 控制台内部的切片接线守卫（4.5 的「防回退」钉子）。
 *
 * 背景：迁移前 `AgentConsoleScreen` 的 `state` 参数恒为 null，导致它把 null 继续传给
 * `AgentWorkspaceBoard` / `SessionDetailView` / `WorkspaceDetailView`，这三个子屏按空切片
 * 渲染 —— 桌面 Agent 页的工作区板与会话详情一直是空白（蓝图 §4.4.7.1 有完整根因）。
 *
 * 4.5 删掉可选 `state` 后内部调用改传本地切片。**不许**为了「让参考帧保持不变」把空构造
 * 塞回去：那等于把已定位的缺陷重新焊死，而且下一轮迁移会再踩一次。
 *
 * 反向验证：把任一调用改成 `agentSlice = AgentSlice()`，本用例变红。
 */
class AgentConsoleSliceWiringGuardTest {

    private val source = GuardSourceScan.stripComments(
        GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/agents/AgentConsoleScreen.kt"),
    )

    private val callees = listOf("WorkspaceBoardView(", "SessionDetailView(", "WorkspaceDetailView(")

    @Test
    fun internalChildScreensReceiveLocalSliceVariables() {
        for (callee in callees) {
            val at = source.indexOf(callee)
            assertTrue(at >= 0, "AgentConsoleScreen 不再调用 " + callee + "：若组件已改名，请同步更新本守卫")
            // 取调用点到下一个空行之间的参数块（这些调用都写在多行内）
            val end = source.indexOf("\n\n", at)
            val block = source.substring(at, if (end < 0) source.length else end)
            assertTrue(
                block.contains("agentSlice = agentSlice") || block.contains("chatSlice = chatSlice"),
                callee + " 的调用没有传本地切片变量（必须 agentSlice/chatSlice/... 直接透传）",
            )
            for (empty in listOf("AgentSlice()", "ChatSlice()", "NodeSlice()", "SettingsSlice()", "TransferSlice()", "AvatarSlice()")) {
                assertTrue(
                    !block.contains(empty),
                    callee + " 的调用出现空构造 " + empty + "：那是「为保旧帧」把已修复的空白缺陷焊死，见蓝图 §4.4.7.1",
                )
            }
        }
    }
}
