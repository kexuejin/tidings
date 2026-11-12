// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.orchestration

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **模型从执行档到 argv 的接线守卫**。
 *
 * 为什么需要源码守卫：这条链跨四个文件、五跳，任何一跳断掉都**编译通过、单测全绿**，
 * 症状只有「执行配置里明明选了模型，跑起来还是默认模型」—— 本仓最贵的那类静默失效。
 * 纯行为测试只能钉两头（驱动层 argv 由 `CliModelSelectionTest` 钉、编译层携带由
 * `TeamModelPropagationTest` 钉），中间这三跳就得靠断言接线本身。
 *
 * 反向探针：
 * - 删掉 `executeCliTask` 里的 `.withModel(model)` ⇒ 第 1 条红；
 * - 删掉 `DesktopStageChain` 的 `model = stage.model` ⇒ 第 2 条红；
 * - 把工单里任一处 `model = ticket.model` 删掉 ⇒ 第 3 条的计数掉到阈值下；
 * - 把 `seatOverride` 那条链拆了 ⇒ 第 4 条红。
 */
class ModelWiringGuardTest {

    private val orchestration = GuardSourceScan.livePackageOf(
        "orchestration/src/main/kotlin/com/kapp/tenon/orchestration",
    )
    private val agent = GuardSourceScan.livePackageOf("agentNode/src/main/kotlin/com/kapp/tenon/agent")

    @Test
    fun executeCliTaskAppliesTheModelToTheResolvedDriver() {
        // 全仓唯一的 CLI 执行出口：model 必须**紧跟解析**应用（换模型 = 换驱动实例）。
        assertTrue(
            agent.contains("resolveCliDriver(agentKey)?.withModel(model)"),
            "executeCliTask 没有把 model 应用到驱动 —— 上游带得再对，argv 里也不会有 --model",
        )
        assertTrue(
            agent.contains("model: String? = null"),
            "executeCliTask 没有 model 入参 —— 调用方传不进来",
        )
    }

    @Test
    fun chainStagesPassTheirModelDown() {
        assertTrue(
            orchestration.contains("model = stage.model"),
            "责任链的每一棒没把 stage.model 传给 executeCliTask —— 多棒路径全军覆没（单棒却绿）",
        )
    }

    @Test
    fun ticketsKeepTheirModelForEveryCliCall() {
        // 一张工单里 CLI 调用不止一处：原型立项、原型微调、开发/自愈循环。
        // 少接一处 = 那条分支静默跑默认模型，而卡面看起来一模一样。
        val wired = Regex("model = ticket\\.model").findAll(orchestration).count()
        assertTrue(
            wired >= 2,
            "工单的 CLI 调用没有都带上 model（原型 / 微调 / 开发循环），实际接到 " + wired + " 处",
        )
    }

    @Test
    fun singleSeatOverrideFeedsTheTicket() {
        assertTrue(
            orchestration.contains("val effectiveModel = seatOverride?.second ?: model"),
            "单兵底座覆盖的模型没有并进工单 —— 只有团队路径能选模型，单兵等于没配",
        )
        assertTrue(
            orchestration.contains("model = effectiveModel"),
            "工单构造没吃 effectiveModel —— 覆盖算出来了却没人用",
        )
        assertTrue(
            orchestration.contains("val defaultAgent = seatOverride?.first ?: rawKey?.let"),
            "单兵底座覆盖没接到 defaultAgent（先覆盖、后归一的顺序也不能反）",
        )
    }
}
