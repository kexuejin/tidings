// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-G4 R3 兜底卡的接线守卫：指定标签无匹配节点时，**静默落回本机执行**是意图违背
 * （用户要的是「有 gpu 的那台」）—— 挂起成卡 + 两个动作各自可达。
 *
 * 为什么只能源码级守：无匹配路径在域层组件内部，行为测试要完整双节点夹具 + 桥注入
 * （成本高且易脆）；接线纪律（挂起而非回退 / 两动作 / 横幅照旧）钉在源码上。
 */
class TagBoardGuardTest {

    private fun live(path: String): String = com.kapp.tidings.testsupport.GuardSourceScan.stripComments(
        com.kapp.tidings.testsupport.GuardSourceScan.read(path),
    )

    private val component =
        "app/src/commonMain/kotlin/com/kapp/tidings/domain/RelayControllerAgentInvokeComponent.kt"

    @Test
    fun tagNoMatchSuspendsIntoACardInsteadOfSilentlyRunningLocally() {
        val body = live(component)
        // 挂起卡：无匹配分支在**派发之前**返回（不落进本机执行器路径）。
        assertTrue(
            body.contains("if (requiredTag != null && routed == null) {"),
            "无匹配分支没有独立出来 —— 会静默落回本机执行（意图违背）",
        )
        assertTrue(
            body.contains("tagBoards[cardId] = TagBoardEntry("),
            "无匹配卡没有登记动作上下文 —— 重试/降级两个按钮点了没落点",
        )
        // 两个动作各自可达。
        assertTrue(
            body.contains("TagBoardActions.RETRY ->") && body.contains("TagBoardActions.FALLBACK_LOCAL ->"),
            "标签卡的两个动作没有接进看板动作分发",
        )
        // 降级动作走的是与租约降级同一条本机执行通路（不写第二份执行器）。
        assertTrue(
            body.contains("executeLocallyAndBroadcast("),
            "标签降级没有复用 executeLocallyAndBroadcast —— 两份本机执行迟早漂",
        )
        // 横幅照旧（不点卡的人也有回声；S4-4 的「点了没反应」纪律）。
        assertTrue(
            body.contains("announceAgentGaveUp(groupId, agentId, seat.name, messageId, unavailable = true)"),
            "无匹配时横幅没了 —— 不点卡的人完全没有回声",
        )
    }

    /** T-G5 定向呼叫：显式宿主 = 只有被点名的机器处理（群帧全员可见，执行只有一家）。 */
    @Test
    fun explicitHostIntentIsProcessedOnlyByTheNamedHost() {
        val body = live(component)
        assertTrue(
            body.contains("val explicitHost = intent.strField(\"hostNodeId\")"),
            "接收侧没有读 hostNodeId —— 定向呼叫无人认领",
        )
        assertTrue(
            body.contains("if (explicitHost != null && explicitHost != self) return"),
            "非目标宿主没有让路 —— 每台有同名 agent 的机器都会抢着跑（§2.1 的原始困境）",
        )
    }
}
