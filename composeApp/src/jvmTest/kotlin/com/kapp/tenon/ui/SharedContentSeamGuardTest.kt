// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.ui

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4 · Shell-Content 收敛的**接线守卫**：内容一份实现，两端壳各自参数化。
 *
 * 为什么只能源码守卫：「两端调的是同一个 Composable」这件事渲染不出来 —— 截图能证明
 * 两端都画出了行/头，但证明不了它们是**同一份代码**。两个壳各写一份也会出两帧绿的图
 * （ConversationRow 那次就是先有两条实现、再长成两副面孔）。
 *
 * 回退方式：把任一端改回自己的头像/标题排版，或删掉对共享内容的调用 ⇒ 本条红。
 */
class SharedContentSeamGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    private val nodeContent =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/nodes/NodeRowContent.kt"
    private val phoneNodeRows =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/nodes/NodeRows.kt"
    private val desktopNodePane =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopNodesPane.kt"
    private val chatContent =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatHeaderContent.kt"
    private val phoneChatHeader =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatHeader.kt"
    private val desktopChatPane =
        "composeApp/src/commonMain/kotlin/com/kapp/tenon/ui/desktop/DesktopChatPane.kt"

    /** 节点行：内容原语一份；手机/桌面各自只传密度与外壳参数。 */
    @Test
    fun nodeRowContentIsSharedByBothShells() {
        val content = live(nodeContent)
        assertTrue(
            content.contains("internal fun NodeRowContent(") && content.contains("enum class NodeRowDensity"),
            "节点行内容原语不见了（NodeRowContent + NodeRowDensity 必须住在 feature/nodes）",
        )
        val phone = live(phoneNodeRows)
        assertTrue(phone.contains("NodeRowContent("), "手机 NodeRow 没调共享内容 —— 它又在自己排版")
        assertTrue(phone.contains("NodeRowDensity.Phone"), "手机没传 Phone 密度档")
        val desk = live(desktopNodePane)
        assertTrue(desk.contains("NodeRowContent("), "桌面 NodeListRow 没调共享内容 —— 分叉从桌面重新长回来了")
        assertTrue(desk.contains("NodeRowDensity.Desktop"), "桌面没传 Desktop 密度档")
        assertFalse(
            desk.contains("private fun NodeAvatar("),
            "桌面又定义了一份头像实现 —— 头像/在场点必须只在 NodeRowContent 里",
        )
    }

    /** 聊天顶栏：内容原语一份；两端各自只传密度、窄栏与标题行插槽。 */
    @Test
    fun chatHeaderContentIsSharedByBothShells() {
        val content = live(chatContent)
        assertTrue(
            content.contains("internal fun ChatHeaderContent(") &&
                content.contains("enum class ChatHeaderDensity"),
            "聊天顶栏内容原语不见了（ChatHeaderContent + ChatHeaderDensity 必须住在 feature/chat）",
        )
        val phone = live(phoneChatHeader)
        assertTrue(phone.contains("ChatHeaderContent("), "手机 ChatHeader 没调共享内容")
        assertTrue(phone.contains("ChatHeaderDensity.Phone"), "手机没传 Phone 密度档")
        val desk = live(desktopChatPane)
        assertTrue(desk.contains("ChatHeaderContent("), "桌面 ChatHeader 没调共享内容")
        assertTrue(desk.contains("ChatHeaderDensity.Desktop"), "桌面没传 Desktop 密度档")
        assertFalse(
            desk.contains("PeerAvatar("),
            "桌面头部又自己画头像了 —— 头像/在场点必须只在 ChatHeaderContent 里",
        )
    }
}
