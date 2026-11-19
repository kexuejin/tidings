// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 链式工单「重试」的**接线守卫**：链执行器必须有恢复能力，编排器必须落链。
 *
 * 真机 UX 轮现场：链受阻卡上点「重新运行」——`retryByCard` 只认单兵工单表，
 * 链工单（没有 activeTickets 条目）接不住，卡翻成「已结束或宿主已重启」而工单
 * 明明活着。修复两半：链执行器保留**首发参数**（重试按同形状同参数重派，
 * 新 chainId）；编排器在单兵表未命中时落到该能力。两半断任何一环，
 * 按钮又回到「点了没反应」。
 */
class ChainRetryWiringGuardTest {

    private val chain = GuardSourceScan.stripComments(
        GuardSourceScan.read(
            "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopStageChain.kt",
        ),
    )
    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read(
            "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt",
        ),
    )

    @Test
    fun theChainDispatcherKeepsItsLaunchParamsForRetry() {
        assertTrue(
            chain.contains("object : AgentChainDispatcher, ChainRetry"),
            "链执行器必须同时实现恢复能力（AgentChainDispatcher + ChainRetry）",
        )
        assertTrue(
            chain.contains("private val launched =") &&
                chain.contains("launched[chainId] = ChainLaunch("),
            "首发参数必须进注册表：丢了它，重试无从谈起",
        )
        assertTrue(
            chain.contains("override fun retry(chainId: String): Boolean") &&
                chain.contains("RelayLog.info(\"CHAIN\", \"retry dispatched: \""),
            "重试必须重派（新 chainId）并留痕",
        )
    }

    @Test
    fun retryByCardFallsThroughToTheChainCapability() {
        val body = GuardSourceScan.bracedBlockAfter(orchestrator, "fun retryByCard(")
        assertTrue(
            body.contains("as? ChainRetry"),
            "单兵表未命中必须落到链恢复能力（探测式，老装配如实返回 false）",
        )
        assertTrue(
            body.contains("host.chainDispatcher"),
            "恢复能力从控制面的链执行器来",
        )
    }
}
