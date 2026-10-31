// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.desktop

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-1c（升级轮）桌面聊天头部收拢：直出 ≤ 原型 `dhead` 的 max=1（群=「成员」、
 * 私聊=「复制 ID」），其余动作收进溢出菜单；菜单**复用** [TopBarOverflowAction] 与
 * 手机 ChatHeader 同一条动作模型（AGENTS §2.6：同一条清单两个触发器，不写第二份）。
 *
 * 头部信息价值：群 badge = 「N/M 人在线 · K 个智能体就绪」；私聊副标题带能力段。
 *
 * Popup 不进截图门禁（Layoutlib 限制，DesktopToolbar KDoc 先例）—— 接线与上限
 * 只能源码守卫；渲染证据由头部常规帧承担。
 */
class DesktopChatHeaderSlimGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private val headerPath = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopChatPane.kt"

    /** 回退方式：把「在新窗口打开」等放回首排直出 ⇒ 本条红。 */
    @Test
    fun headerDirectRendersAtMostOneActionViaOverflow() {
        val header = live(headerPath)
        val body = header.substring(header.indexOf("fun DesktopChatHeader("))
        assertTrue(
            body.contains("TopBarOverflowAction("),
            "桌面聊天头部没有溢出菜单 —— T-1c 的收拢没落地",
        )
        // 直出的动作按钮必须是**一颗**（ToolbarButton/TopBarIconAction 的直出调用只剩
        // 群的「成员」或私聊的「复制 ID」两个分支）：数「直出 ToolbarButton(」出现次数 ≤ 2
        //（成员 / 复制ID 各一支）。
        val directButtons = Regex("ToolbarButton\\(").findAll(body).count()
        assertTrue(
            directButtons <= 2,
            "桌面头部直出的文字按钮还有 $directButtons 处 —— 超过「群=成员 / 私聊=复制ID」各一支的上限",
        )
    }

    /** 回退方式：把群 badge 的「智能体就绪」段删掉 ⇒ 本条红。 */
    @Test
    fun groupBadgeCarriesAgentReadyCount() {
        val header = live(headerPath)
        assertTrue(
            header.contains("chat_group_agents_ready"),
            "群头部 badge 缺「K 个智能体就绪」段 —— 头部信息价值判据（T-1c ②；键走资源）",
        )
    }
}
