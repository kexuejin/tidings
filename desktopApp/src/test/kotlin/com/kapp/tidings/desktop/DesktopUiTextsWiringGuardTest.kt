// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 捎话 Tidings contributors

package com.kapp.tidings.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 桌面产品壳必须装上「文案桥」——真机抓到的缺陷的守卫。
 *
 * ## 为什么这条要写成源码扫描
 *
 * 它断的是「装配」而不是「行为」：`InstallRelayUiTexts(controller)` 少调一次，编译照过、
 * 单元测试全绿、界面也照常显示 —— 只有等到某条**跨端**文案要走协议时才炸，而那时
 * 症状是「对端一条都收不到」，不是崩溃。
 *
 * 真机现场（读数见 `design/runtime-evidence/` 下的 M5 验收矩阵）：桌面库出现
 * `agentState=failed` 且 `text=""`，
 * 手机库 `agentState` 行 **0 条**。原因链是
 * `uiTexts = NoUiTexts` → 文案空串 → `broadcastAgentState` 的「空正文不发帧」
 * （协议对群文本要求 `text` 非空）→ 全群静默。
 *
 * ## 为什么不能只靠「手机端 App.kt 已经装了」
 *
 * 桌面宿主**不经过 `RelayApp`**（它自己组合 `DesktopApp`），所以 `App.kt` 里那次装配
 * 与它无关 —— 这正是缺陷的成因。两个壳必须各调一次同一个函数。
 */
class DesktopUiTextsWiringGuardTest {

    /** Gradle 的测试工作目录就是模块目录（`desktopApp/`）。 */
    private fun read(path: String): String {
        val f = File(path)
        assertTrue(f.exists(), "找不到 $path（工作目录=${File(".").absolutePath}）")
        return f.readText()
    }

    /** 去掉注释行，避免「注释里写着 InstallRelayUiTexts」把判据骗过去。 */
    private fun stripComments(text: String): String =
        text.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    @Test
    fun desktopShellInstallsTheUiTextsBridge() {
        val text = stripComments(
            read("src/main/kotlin/com/kapp/tidings/desktop/DesktopWindow.kt"),
        )
        assertTrue(
            text.contains("InstallRelayUiTexts(controller)"),
            "桌面壳必须调 InstallRelayUiTexts(controller)：少了它 uiTexts 恒为 NoUiTexts，" +
                "agentState=failed 的正文就是空串，而空正文不发帧 ⇒ 对端永远收不到那条反馈。",
        )
    }

    @Test
    fun theBridgeIsImportedRatherThanFullyQualifiedInline() {
        val text = read("src/main/kotlin/com/kapp/tidings/desktop/DesktopWindow.kt")
        assertTrue(
            text.contains("import com.kapp.tidings.ui.InstallRelayUiTexts"),
            "导入缺失时 `InstallRelayUiTexts(controller)` 会被写成全限定名，" +
                "上一条判据就只剩下一个不起眼的字符串 —— 这条钉住它确实是被 import 进来的符号。",
        )
    }
}
