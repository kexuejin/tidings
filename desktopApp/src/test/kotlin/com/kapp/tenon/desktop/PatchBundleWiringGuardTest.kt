// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.desktop

import com.kapp.tenon.desktop.testutil.RepoSource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 三个**结构性**结论的守卫：它们都曾经"看起来在、其实没接上"。
 *
 * 源码级守卫在这里不是偷懒 —— 这三件事的判据都是「调用点还在不在」，
 * 而它们的失效方式恰恰是**静默**的：不抛异常、不报错，只是卡面和手机上少了一件事。
 * 单元测试覆盖不到（要起沙箱、要真跑 CLI 才走得到那一行），所以退一步钉住调用点。
 *
 * 与 `DesktopInvokeWiringGuardTest` 同一套路：读 `DesktopExtension.kt` / 编排器的源码。
 */
class PatchBundleWiringGuardTest {

    private val orchestrator = RepoSource.read(
        "orchestration/src/main/kotlin/com/kapp/tenon/orchestration/DesktopWorkerOrchestrator.kt",
    )
    // 两个翻面点分住 ArtifactDelivery / HostCardActions（ARP-5 拆出）⇒ 判据读整包。
    private val extension = RepoSource.readDir("orchestration/src/main/kotlin/com/kapp/tenon/orchestration")

    /**
     * §6.3：审阅卡上写着「产物 patch.diff」，那个文件就必须真的走 P2P 投出去。
     *
     * 摘掉这一跳的症状：手机上那一行永远点不开，补丁只躺在宿主 `/tmp` 里 ——
     * 而卡面、日志、测试全绿（因为没人断言过「文件离开过本机」）。
     */
    @Test
    fun theReviewPathActuallyDispatchesThePatchBundle() {
        assertTrue(
            orchestrator.contains("deliverPatchBundle(groupId, ticketId, patchPath)"),
            "审阅卡发布前没有投递 patch.diff —— 卡片宣称的产物永远到不了对端（§6.3 断链）",
        )
        assertTrue(
            orchestrator.contains("name = \"patch.diff\""),
            "投递的文件名不是 patch.diff —— 对端收到的是一张名字对不上的文件卡",
        )
        assertTrue(
            orchestrator.contains("controller.sendAgentArtifacts("),
            "patch bundle 没有走产物通道（sendAgentArtifacts）—— 手搓一条私有通路会绕开免确认与传输台账",
        )
    }

    /**
     * D6：单棒工单卡的阶段项状态必须**翻译**，不许把卡级 `state` 直接塞进 `items[].status`。
     *
     * 塞进去的后果是静默的：`review_ready` / `merged` / `prototype` 不在棒级词表里，
     * 渲染层一律退成「未开始」，而卡上写着「在做原型」。
     */
    @Test
    fun ticketCardsTranslateTheirStageStatusInsteadOfCopyingTheState() {
        assertTrue(
            orchestrator.contains("StageChain.stageStatusOf(state)"),
            "工单卡又把 state 直接当棒级状态用了 —— 阶段项会显示成「未开始」（D6）",
        )
        assertTrue(
            !orchestrator.contains("stages = listOf(roleName to state)"),
            "`stages = listOf(roleName to state)` 是 D6 的原始写法，必须翻译成 stageStatusOf",
        )
    }

    /**
     * 翻面（合并成功 / 受阻）必须走 [BoardCardFlip]。
     *
     * 内联重建的症状：合并成功后 `diff` 明细整块消失，或者 `state=merged` 而
     * `items` 还写着 `review_ready` —— 两者都不会让任何测试变红，只会让用户看到一张
     * 自相矛盾的卡。
     */
    @Test
    fun bothFlipsGoThroughThePureBuilder() {
        assertTrue(
            extension.contains("BoardCardFlip.merged(cardId, base)"),
            "合并成功没有走 BoardCardFlip.merged —— 内联重建会丢 diff 与阶段项（D6 复发）",
        )
        assertTrue(
            extension.contains("BoardCardFlip.blocked("),
            "合并受阻没有走 BoardCardFlip.blocked —— 内联重建会丢掉「哪几行没合上」",
        )
    }
}
