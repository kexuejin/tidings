// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 守卫「两壳的草稿表活过转屏，且存档契约只有一处」。
 *
 * ## 真身（两段，都发生过）
 *
 * 1. 手机壳（`App.kt`）的 `draftsByConv` 是 `rememberSaveable(stateSaver = DraftsByConvSaver)`，
 *    桌面/平板壳（`DesktopShell.kt`）却曾经是裸 `remember`。Manifest 没有 configChanges，
 *    转一次屏整个 Activity 重建 —— 平板形态走的正是这条壳，于是同一个应用里
 *    "手机上转屏草稿还在、平板上转屏草稿全丢"。
 * 2. 后来两边都挂上了 Saveable，但各自**声明**了一个 `Saver` 实例（编解码同源、
 *    声明两处）。同一个"草稿表怎么存档"的事实有两个声明点就能各自漂移 ——
 *    漂移的后果不是编译错误，而是一份存档被另一种读法读出乱码/空表。
 *    现在两壳都引用 [DraftsByConvSaver] 这一个实例（声明在 `feature/chat`）。
 *
 * ## 为什么只能靠源码守卫
 *
 * "哪个 Saver 实例被挂上"、"全仓有几个声明点"都是结构事实，不是运行行为；
 * 本仓没有 Compose UI 测试基建，没法转一次屏再断言。源码层能钉住的正是这两条。
 *
 * ## 反向验证
 *
 * - 桌面壳的 `stateSaver = DraftsByConvSaver` 改回裸 `remember` ⇒
 *   [desktopDraftTableSurvivesRotation] 红；
 * - 在手机壳/桌面壳里重新 `private val ... = Saver(...)` 一份草稿 Saver ⇒
 *   [onlyOneDraftsSaverDeclarationInTheWholeRepo] 红（声明点从 1 变 2）；
 * - 在任一壳里抄一份 `draftsByConvToSaverValue` ⇒
 *   [theCodecHasASingleDeclarationToo] 红；
 * - 共享那个 Saver 的 `save` 改坏 ⇒ `AppDraftsSaverTest` 的 roundtrip 红
 *   （那条测的是共享实例本身，不是某份副本）。
 */
class DesktopDraftsSaveableGuardTest {

    private val desktopPath = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt"
    private val phonePath = "composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt"
    private val sharedPath = "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/DraftsByConvSaveable.kt"

    private fun desktop(): String = GuardSourceScan.stripComments(GuardSourceScan.read(desktopPath))

    private fun phone(): String = GuardSourceScan.stripComments(GuardSourceScan.read(phonePath))

    /**
     * 全仓生产源（跳过各平台的 `*Test/` 源集）。
     *
     * 扫描面必须非空 —— 目录写错/搬家时这里直接红，而不是"没有 offender"恒真
     * （本仓源码门禁最常见的假绿形态）。
     */
    private fun productionKotlinFiles(): List<String> {
        val files = GuardSourceScan.kotlinFiles(
            "core/src",
            "app/src",
            "composeApp/src",
            "desktopApp/src",
            "androidApp/src",
            "agentNode/src",
            "cli/src",
            "testsupport/src",
        ).filterNot { it.contains("Test/") }
        assertTrue(files.size > 300, "只扫到 ${files.size} 个生产源文件 —— 扫描根写错了？")
        return files
    }

    /** 某个片段在全部生产源里出现的**总次数**（不是命中的文件数：同一文件里两份也要算两次）。 */
    private fun occurrencesOf(needle: String): List<String> = productionKotlinFiles().mapNotNull { path ->
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(path))
        val n = text.split(needle).size - 1
        if (n > 0) "$path × $n" else null
    }

    @Test
    fun desktopDraftTableSurvivesRotation() {
        assertTrue(
            desktop().contains("var draftsByConv by rememberSaveable(stateSaver = DraftsByConvSaver)"),
            "桌面壳的草稿表又变回裸 remember（或换成了自己的 Saver）—— " +
                "平板转一次屏，打到一半的字全没",
        )
    }

    @Test
    fun thePhoneShellUsesTheSameSharedSaver() {
        // 两壳必须是**同一个实例**：同一个 import、同一个名字。名字相同但各自 private
        // 声明是另一种形态的发散（这正是这次收掉的东西）。
        assertTrue(
            phone().contains("var draftsByConv by rememberSaveable(stateSaver = DraftsByConvSaver)"),
            "手机壳的草稿表不再用共享的 DraftsByConvSaver",
        )
        assertTrue(
            phone().contains("import com.kapp.tidings.feature.chat.DraftsByConvSaver"),
            "手机壳没有引用 feature/chat 里那个共享 Saver",
        )
        assertTrue(
            desktop().contains("import com.kapp.tidings.feature.chat.DraftsByConvSaver"),
            "桌面壳没有引用 feature/chat 里那个共享 Saver",
        )
    }

    @Test
    fun onlyOneDraftsSaverDeclarationInTheWholeRepo() {
        // 类型签名拼起来而不是写字面量：守卫自己的源码就在被扫的树里，
        // 写死字面量会让它**自己绊倒自己**（本仓踩过这个形态）。
        val draftsSaverType = "Saver<Map<String, " + "String>, List<Any>>"
        assertEquals(
            listOf("$sharedPath × 1"),
            occurrencesOf(draftsSaverType),
            "草稿表的 Saver 只能有**一处**声明（feature/chat 里那个共享实例）。" +
                "多了就是两个可以各自漂移的真相来源 —— 一份存档被另一种读法读出空表时，" +
                "症状会以「平板上草稿丢了」的形态回来。",
        )
    }

    @Test
    fun theCodecHasASingleDeclarationToo() {
        // 「不得复制第二份编解码」：两个纯函数各只许有一处定义。
        assertTrue(
            occurrencesOf("fun draftsByConvToSaverValue(") == listOf("$sharedPath × 1"),
            "编码函数被复制了第二份 —— 两壳必须调同一份，否则一份存档两种写法",
        )
        assertTrue(
            occurrencesOf("fun draftsByConvFromSaverValue(") == listOf("$sharedPath × 1"),
            "解码函数被复制了第二份 —— 恢复端分叉的后果是草稿读出乱码或空表",
        )
    }
}
