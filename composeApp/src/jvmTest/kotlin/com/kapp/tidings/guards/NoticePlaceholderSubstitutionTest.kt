// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **带占位符的提示必须把参数传进去。**
 *
 * ## 真机现场
 *
 * 屏幕上一行灰字原样显示：
 *
 *     %1$s 没有执行：你不在它的授权名单里
 *
 * 抬头看代码完全正常 —— 枚举分支对、资源名对、文案对。**只有跑起来看屏幕**才发现
 * 参数没传。这就是本仓「测试绿 ≠ 测到了」的又一例：
 * 编译通过 + 单测全绿 + 库里数据正确，而用户看到的是半个占位符。
 *
 * 这条守卫钉住的是「带了参数」这个事实本身 —— 它没法从类型系统得到，
 * 因为 `stringResource` 有多个重载，不传参也能编译。
 */
class NoticePlaceholderSubstitutionTest {

    private fun source(): String =
        GuardSourceScan.read("composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatBubbles.kt")

    /** 只看活代码（剥掉 `//` 注释），否则注释里提到资源名也会让守卫假红。 */
    private fun live(): String =
        source().lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun theUnauthorizedNoticePassesTheAgentName() {
        val live = live()
        assertTrue(
            live.contains("chat_agent_not_authorized, agentName") ||
                live.contains("chat_agent_not_authorized,\n                    agentName") ||
                live.contains("chat_agent_not_authorized, actor"),
            "「没有执行」那条文案带占位符，必须把 agent 名传进去 ——" +
                "不传的话界面会原样显示占位符（真机上就是这样）",
        )
    }

    /**
     * **不许退回「无参」那条路径。**
     *
     * `when` 里仍留着无参分支做兜底（名字真的拿不到时），但它不能是唯一路径 ——
     * 否则又回到占位符裸露。
     */
    @Test
    fun theFallbackIsNotTheOnlyPath() {
        val live = live()
        val withArg = live.contains("chat_agent_not_authorized, agentName")
        val bare = live.contains("NoticeKind.AgentNotAuthorized -> Res.string.chat_agent_not_authorized")
        assertTrue(
            withArg || !bare,
            "无参分支只能是兜底：带参数那条必须存在",
        )
    }
}
