// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 智能体域**依赖面**守卫：两个 agent 分片只能建在窄缝 [AgentDomainHost] 上，状态写入
 * 只能走 [AgentDomainHost.updateAgentState]，主类仍作门面委托。
 *
 * ## 为什么需要它
 *
 * Phase 3 把十三个分片从「构造在 `RelayController` 上」收敛成域服务。这层收敛的**全部价值**
 * 在类型上：`internal` 在同一模块内对任何文件可见，所以「agent 域不碰消息表 / 传输表」这条
 * 纪律过去只能靠人读代码守，一旦有人把构造参数改回去（或用回 `host._state` 直接写流），
 * 行为测试**不会红** —— 因为它们测的是业务行为，不是依赖方向。
 *
 * 这正是本仓点名的「测试绿不等于测到了」：拆分的意义必须由一条能区分对错的守卫钉住，
 * 否则下一次重构会把窄缝重新放宽，而所有用例全绿。
 *
 * 三条判据，都只读活代码（[GuardSourceScan.stripComments] 先剥注释 —— 注释里正当提到
 * 这些名字不该判红，本仓踩过「守卫被自己的注释绊倒」）：
 * 1. 两个分片的构造参数是 `AgentDomainHost`，不是 `RelayController`；
 * 2. 分片不直接读写跨域状态（`host._state` / `host.core` / `host.stateLock`）；
 * 3. 主类仍以门面成员暴露两族（`agentDomain.agent` / `agentDomain.invoke`），且不再自己构造分片。
 */
class AgentDomainWiringGuardTest {

    private val domainPrefix = "app/src/commonMain/kotlin/com/kapp/tenon/domain/"

    private val agentFamily = listOf(
        "RelayControllerAgentComponent.kt",
        "RelayControllerAgentInvokeComponent.kt",
    )

    @Test
    fun agentFamilyIsBuiltOnTheNarrowHost() {
        for (file in agentFamily) {
            val source = live(domainPrefix + file)
            assertTrue(
                source.contains("host: AgentDomainHost"),
                "$file 的宿主必须是 AgentDomainHost：改回 RelayController 等于把整类依赖面重新对 agent 域开放，" +
                    "而行为用例察觉不到（它们测业务行为，不测依赖方向）",
            )
            assertTrue(
                !source.contains("host: RelayController"),
                "$file 不得把宿主类型写回 RelayController —— 那会让本域重新能碰任意跨域状态",
            )
        }
    }

    @Test
    fun agentFamilyDoesNotTouchCrossDomainState() {
        for (file in agentFamily) {
            val source = live(domainPrefix + file)
            for (forbidden in listOf("host._state", "host.core", "host.stateLock", "host.groupsCtl")) {
                assertTrue(
                    !source.contains(forbidden),
                    "$file 出现 \"$forbidden\"：状态写入必须走 host.updateAgentState、跨域动作必须走宿主收口方法；" +
                        "直接拿跨域状态等于绕过域边界",
                )
            }
        }
    }

    @Test
    fun controllerKeepsFacadeDelegation() {
        val source = live(domainPrefix + "RelayController.kt")
        assertTrue(
            source.contains("agentDomain.agent") && source.contains("agentDomain.invoke"),
            "主类必须继续以同名成员暴露两族（:orchestration 与 UI 直接连它，公开 API 不能变）",
        )
        assertTrue(
            !source.contains("RelayControllerAgentComponent(") && !source.contains("RelayControllerAgentInvokeComponent("),
            "主类不再自己构造 agent 分片：构造点只能在 AgentDomainService（否则域收口只是换了个变量名）",
        )
    }

    private fun live(relativePath: String): String =
        GuardSourceScan.stripComments(GuardSourceScan.read(relativePath))
}
