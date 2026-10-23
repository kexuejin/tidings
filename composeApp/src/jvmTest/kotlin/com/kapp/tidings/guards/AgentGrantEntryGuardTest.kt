// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **「申请授权」入口必须挂在那条 no권한 notice 上，而且只在那一档上。**
 *
 * ## 为什么值得源码级守卫
 *
 * 入口与提示分开在两个文件里：`ChatScreen` 决定「要不要把动作交给组件」、`ChatBubbles`
 * 决定「要不要画那颗按钮」。任何一侧单独改动都会让按钮出现在**不该出现的通知**上
 * （撤回、戳一碰、群改名…），或让「没有执行」那一行**没有动作可点** —— 两种都是
 * 用户看得见的坏形态，而单测覆盖不到（都在 Composable 里）。
 *
 * 判据刻意简单：两个条件必须**同时**出现 ——
 * `kind == NoticeKind.AgentNotAuthorized` 且 `onRequestGrant != null`。
 */
class AgentGrantEntryGuardTest {

    // 走 GuardSourceScan 而不是自己拼 `../` / `../../`：后者依赖"测试工作目录是模块根"
    // 这个隐含事实，换模块、换任务就跑不到了。
    private fun source(rel: String): String = GuardSourceScan.read(rel)

    /**
     * 按钮只在无权限那一档上出现。
     *
     * 断言的是**条件表达式**而不只是「提到了 AgentNotAuthorized」：后者在注释里也成立。
     */
    @Test
    fun theGrantButtonOnlyAppearsOnTheUnauthorizedNotice() {
        val text = source("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatBubbles.kt")
        val live = text.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue(
            live.contains("kind == NoticeKind.AgentNotAuthorized && onRequestGrant != null"),
            "申请入口必须同时受「无权限那一档」与「调用方给了动作」两个条件约束 ——" +
                "少任何一个都会在别的通知上冒出一颗按钮，或者让提示点了没反应",
        )
    }

    /** 调用侧必须把动作传进组件，否则按钮永远不画。 */
    @Test
    fun theChatScreenPassesTheGrantAction() {
        val text = source("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt")
        val live = text.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue(
            live.contains("onRequestGrant = actions.onRequestAgentGrant"),
            "ChatScreen 必须把动作交给 NoticeLine —— 否则「申请授权」永远画不出来",
        )
    }

    /** 两端都要把真实动作接上（手机 + 桌面），否则那个按钮点了没反应。 */
    @Test
    fun bothShellsWireTheRealAction() {
        val hosts = listOf(
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt",
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopChatPane.kt",
        )
        for (path in hosts) {
            val live = source(path).lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
            assertTrue(
                live.contains("requestAgentGrantFromNotice("),
                "$path 必须把「申请授权」接到真实动作上 —— 只画按钮不接线等于点了没反应",
            )
        }
    }
}
