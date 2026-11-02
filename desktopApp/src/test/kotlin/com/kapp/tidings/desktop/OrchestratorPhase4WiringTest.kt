// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.desktop

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 4 五条接线的**落点守卫**：能力写进了哪个符号、接在哪个入口 —— 每条都要
 * 能区分「实现了」与「只是注释里提过」（守卫读的是剥掉注释后的活代码）。
 *
 * 行为级判定在 [DesktopWorkerWatchdogTest]（注入时钟的纯判定件）与既有
 * [DesktopPatchMergeTest]（真 git 的合流件）；这里守的是「活没接上」——
 * 那类缺陷在真机上的形状是「单测全绿、现场不存在」。
 */
class OrchestratorPhase4WiringTest {

    private val orchestrator = GuardSourceScan.stripComments(
        GuardSourceScan.read("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopWorkerOrchestrator.kt"),
    )

    private val extension = GuardSourceScan.stripComments(
        GuardSourceScan.read("desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopExtension.kt"),
    )

    /** 任务 1：合并动作必须由编排器入口落 [DesktopPatchMerge]，git apply exitCode==0 才有 Applied。 */
    @Test
    fun mergeActionLandsThroughDesktopPatchMergeGate() {
        assertTrue(
            orchestrator.contains("internal fun mergePatchByCard(cardId: String): DesktopPatchMerge.Outcome"),
            "合并动作要有编排器入口（mergePatchByCard），返回 Outcome 让调用方按结果发卡",
        )
        assertTrue(
            orchestrator.contains("DesktopPatchMerge.apply(patchPath = ref.patchPath, workspacePath = ref.workspacePath)"),
            "合流必须真实调用 DesktopPatchMerge.apply（两段式 git apply）",
        )
        assertTrue(
            orchestrator.contains("no sandbox patch recorded for card"),
            "没有登记簿账目就是 Conflict —— 不许把『没找到 patch』包装成成功",
        )
    }

    /** 任务 2：打回动作解码出反馈并前置进自愈提示词。 */
    @Test
    fun rejectRevisionFeedsBackIntoTheNextPrompt() {
        assertTrue(
            orchestrator.contains("internal fun rejectRevisionByCard(cardId: String, actionId: String): Boolean"),
            "打回动作要有编排器入口（rejectRevisionByCard）",
        )
        assertTrue(
            orchestrator.contains("RevisionFeedback.feedbackOf(actionId)"),
            "反馈文本从 actionId 的前缀契约里解码（RevisionFeedback）",
        )
        assertTrue(
            orchestrator.contains("active.userConstraints = feedback"),
            "反馈要存进工单，重跑时随行",
        )
        assertTrue(
            orchestrator.contains("WorkerPrompt.build(devPrompt, initialConstraints)"),
            "自愈提示词必须经 WorkerPrompt 组装：硬约束前置，不是附录",
        )
        assertTrue(
            orchestrator.contains("initialConstraints: String? = null"),
            "自愈循环要接 initialConstraints 形参（每一轮都带着约束）",
        )
    }

    /** 任务 3：心跳探针挂在 tool.started 事件上，经节流器与预算闸门发 rev+1 卡。 */
    @Test
    fun heartbeatProbeIsWiredToToolEvents() {
        assertTrue(
            orchestrator.contains("if (eventType == \"tool.started\")"),
            "心跳探针要挂在 tool.started 事件（当前子动作的来源）",
        )
        assertTrue(
            orchestrator.contains("HeartbeatEmitter()"),
            "发射要经 HeartbeatEmitter 节流（无变化不发 + 最小间隔）",
        )
        assertTrue(
            orchestrator.contains("OrchestratorCards.heartbeatCardJson("),
            "探针卡走 OrchestratorCards 构建器（契约字段 + 24 KiB 闸门）",
        )
        assertTrue(
            orchestrator.contains("watchdog.feed()"),
            "每个驱动事件都要喂看门狗：事件 = 子进程输出管道来了新字节",
        )
    }

    /** 任务 4：看门狗判定要驱动熔断（SIGTERM → 宽限 → 超时卡）。 */
    @Test
    fun watchdogVerdictsDriveTheCircuitBreaker() {
        assertTrue(
            orchestrator.contains("SandboxIdleWatchdog(clock = { nowMillis() })"),
            "执行循环要起 I/O 空闲看门狗（注入墙钟）",
        )
        assertTrue(
            orchestrator.contains("SandboxIdleWatchdog.Verdict.TERMINATE ->"),
            "SIGTERM 档要接既有中止序（沙箱 abort，不另写第二套杀进程）",
        )
        assertTrue(
            orchestrator.contains("SandboxIdleWatchdog.Verdict.FORCE_KILL ->"),
            "宽限期满要升级 FORCE_KILL 熔断档",
        )
        assertTrue(
            orchestrator.contains("OrchestratorCards.timeoutCardJson("),
            "熔断后必须发 state=timeout 卡，不许挂在 running",
        )
        assertTrue(
            orchestrator.contains("var circuitBroken = false"),
            "熔断要立标志：该轮以看门狗因由收尾，不冒充普通失败",
        )
    }

    /** 任务 5：崩溃要发 failed 红卡并带可下钻抽屉，严禁静默。 */
    @Test
    fun crashesPublishTheFailedCardWithDrawer() {
        assertTrue(
            orchestrator.contains("CrashReport.drawerOf(t)"),
            "执行体抛出的异常要截现场进抽屉（CrashReport）",
        )
        assertTrue(
            orchestrator.contains("OrchestratorCards.withFailureCheck("),
            "失败现场以 failed 检查项追加到卡上（observation + drawer）",
        )
        assertTrue(
            orchestrator.contains("\"Worker crashed: \""),
            "崩了就发红卡横幅，严禁静默吞掉挂在 running",
        )
        assertTrue(
            orchestrator.contains("CrashReport.drawerOf(said.toString())"),
            "非零退出路径同样把累计输出尾部送进抽屉",
        )
    }

    /** 五条动作的宿主接线（DesktopExtension 侧）：没有落点的按钮比没有更糟。 */
    @Test
    fun extensionRoutesTheNewActions() {
        assertTrue(
            extension.contains("workerOrchestrator.mergePatchByCard(cardId)") &&
                extension.contains("hasTicketForCard(cardId) == true"),
            "工单卡的 merge-patch 必须走编排器登记簿入口（活工单 → mergePatchByCard）",
        )
        assertTrue(
            extension.contains("actionId.startsWith(\"reject-revision\")"),
            "打回动作（reject-revision|<文本>）要在宿主分支接住",
        )
        assertTrue(
            extension.contains("rejectRevisionByCard(cardId, actionId)"),
            "打回分支要交给编排器（解码 + 注入 + 重跑）",
        )
    }
}
