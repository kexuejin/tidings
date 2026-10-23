// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 桌面输入框**进入会话即获得焦点**。
 *
 * ## 守的是什么
 *
 * 没有这一步，窗口打开后焦点在根容器上（它有 `.focusable()` 用来收快捷键）：
 * 用户先打字或按空格，按键进不了输入框。真机上表现为
 * **「输入框不支持输入空格」+ 某些区域出现点击高亮** ——
 * 而根容器那个高亮正是焦点落点，看起来像「点中了什么」。
 *
 * ## 为什么这条只能靠源码守卫
 *
 * 它描述的是「焦点在哪」，渲染出来之前无从断言；本仓没有 Compose UI 测试基建。
 * 与 `SystemBarsInsetGuardTest` / `PairingEntryPointsGuardTest` 同一取舍。
 */
class DesktopComposerFocusGuardTest {

    private val path = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopComposer.kt"

    private fun src(): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /**
     * 进入会话要主动请求焦点（键是会话身份，不是每条 draft），**而且要按返回值重试**。
     *
     * ## 这条守卫原来的形态是假绿（真机代价：用户三次报「打不进空格」）
     *
     * 旧断言只要求源码里出现 `focusRequester.requestFocus()`。那句话确实一直在，
     * 测试也就一直是绿的 —— 但 `FocusRequester.requestFocus()` 的签名是
     * `Boolean`，而输入框在两层 `BoxWithConstraints`（子组合）里，头两帧它
     * **返回 false 且不抛异常**。旧代码写的是 `runCatching { requestFocus() }`，
     * 把那个返回值丢了：异常没有、日志没有，聚焦**每次都静默失败**。
     * 于是用户必须先用鼠标点一下输入框才能打字，不点则空格与字母全部落空 ——
     * 症状被反复描述成"输入框不支持空格"，而点击之后就一切正常。
     *
     * 所以判据必须是「**返回值被用上**」，不是「**这个调用存在**」：
     *   · 必须出现 `if (focusRequester.requestFocus())`（拿到 true 就停）；
     *   · 必须出现 `withFrameNanos`（拿不到就等下一帧再试）；
     *   · **不许**再出现被 `runCatching` 包住的那种丢弃返回值的写法。
     * 这三条能区分对错：把旧写法放回去，本测试变红。
     */
    @Test
    fun enteringAConversationRequestsFocus() {
        val text = src()
        assertTrue(
            text.contains("LaunchedEffect(peer.nodeId)"),
            "没有「进入会话即聚焦」的效果 —— 焦点会留在根容器上，按键进不了输入框",
        )
        assertTrue(
            text.contains("if (focusRequester.requestFocus())"),
            "聚焦的返回值没被用上 —— requestFocus() 在首帧返回 false，" +
                "只调用一次并把结果丢掉等于从来没有聚焦过（旧缺陷的原样）",
        )
        assertTrue(
            text.contains("withFrameNanos"),
            "拿不到焦点后没有等下一帧再试 —— 首帧必然失败，只试一次就是必然失败",
        )
        assertTrue(
            !text.contains("runCatching { focusRequester.requestFocus() }"),
            "又回到「丢弃返回值」的写法了 —— 它不报错，只会让用户必须先用鼠标点一下输入框",
        )
    }

    /**
     * **不能每条 draft 都抢焦点。**
     *
     * 键若是 `draft` 或 `Unit`，用户每打一个字都会重新聚焦；更要紧的是
     * 点开表情面板/候选列表后会被立刻拽回输入框 —— 那些面板就点不动了。
     */
    @Test
    fun focusIsKeyedToTheConversationNotEveryKeystroke() {
        val text = src()
        val bad = text.contains("LaunchedEffect(draft)") || text.contains("LaunchedEffect(Unit) {")
        assertTrue(
            !bad,
            "聚焦效果的键错了 —— 每打一个字抢一次焦点会让表情/候选面板点不动",
        )
    }

    /** 根容器保留快捷键能力（不要为了这次修复把它摘掉）。 */
    @Test
    fun theShellKeepsItsShortcutHandler() {
        val shell = GuardSourceScan.stripComments(
            GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt"),
        )
        assertTrue(
            shell.contains("onPreviewKeyEvent") && shell.contains("handleShortcut"),
            "桌面壳的快捷键入口没了 —— 修焦点不该顺手拆掉它",
        )
    }
}
