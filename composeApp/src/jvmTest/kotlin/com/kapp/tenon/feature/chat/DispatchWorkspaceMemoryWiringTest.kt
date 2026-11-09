// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫「派单工作区的记忆真的跨重启」。
 *
 * ## 真身（A22 第四轮真机现场）
 *
 * 药丸的选中项只活在界面态里（`rememberSaveable`）。宿主/手机 App 被系统回收或被用户
 * 从最近任务划掉之后，重启回来 `localActiveWsId` 是 null，回退链落到工作区列表**第一个**
 * —— 于是本来要写 `teahouse-mobile` 的一单落进了 `china-pass`。用户看不到任何提示，
 * 只有"文件去哪了"。
 *
 * 修法是两截，缺一截就退回原样：
 * 1. **记住**：药丸选择经壳写进 `RelaySettings.lastDispatchWsId`（落盘）；
 * 2. **用上**：生效工作区的回退链先看记住值（且校验它仍在本机列表里），
 *    并且**界面显示与派单入参用同一个值**。
 *
 * ## 为什么只能靠源码守卫
 *
 * 判据横跨三个文件、全在 Composable 内部状态，本仓没有 Compose UI 测试基建
 * （与 `ComposerSlotSendWiringTest` 同一取舍）。能断言的只有"调用点在不在、走哪一条"。
 *
 * ## 反向验证（每条能分别变红）
 *
 * 1. 把 `onSelectWorkspace` 里的 `controller.rememberDispatchWorkspace(wsId)` 删掉
 *    ⇒ [selectingAWorkspacePersistsIt] 红（记忆从没写进去，重启后又回第一个）；
 * 2. 把 `effectiveWsId` 的回退链改回 `localActiveWsId ?: availableWorkspaces.firstOrNull()?.wsId`
 *    ⇒ [thePillAndTheDispatchReadTheSameChain] 红；
 * 3. 把 `submitMessage` 里的 resolve 改回 `localActiveWsId ?: …firstOrNull()?.wsId`
 *    ⇒ [theDispatchPathUsesTheSameChain] 红（显示 A 仓、派到 B 仓）。
 */
class DispatchWorkspaceMemoryWiringTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /** 用户点了药丸，壳必须把它写进设置 —— 否则重启后这条选择就消失了。 */
    @Test
    fun selectingAWorkspacePersistsIt() {
        val app = live(APP)
        assertTrue(
            app.contains("controller.rememberDispatchWorkspace(wsId)"),
            "药丸选择没有落盘：这一屏关掉/App 被回收后，「上次选的仓」就没了，" +
                "重启后的第一单会落进工作区列表第一个仓",
        )
    }

    /** 落盘字段本身必须在设置模型里（不是内存字段）。 */
    @Test
    fun theRememberedWorkspaceHasAPersistedHome() {
        assertTrue(
            live(SETTINGS).contains("val lastDispatchWsId: String = \"\""),
            "RelaySettings 没有 lastDispatchWsId —— 记住的东西没有地方落盘",
        )
    }

    /**
     * 药丸画的那个值，必须走「记住值 → 校验 → 列表第一个」这条链。
     *
     * 反过来说：只要旧写法 `availableWorkspaces.firstOrNull()?.wsId` 还挂在
     * `effectiveWsId` 上，重启后药丸照旧显示第一个仓，而用户以为它记得。
     */
    @Test
    fun thePillAndTheDispatchReadTheSameChain() {
        val chat = live(CHAT_SCREEN)
        val at = chat.indexOf("val effectiveWsId =")
        assertTrue(at >= 0, "ChatScreen 里找不到 effectiveWsId 的定义")
        // 取定义处往后一屏（多行 if 表达式），断言落在**同一个表达式**里。
        val window = chat.substring(at, minOf(at + 400, chat.length))
        assertTrue(
            window.contains("DispatchWorkspaceChoice.resolve("),
            "药丸的生效工作区没有走统一回退链 —— 记住值/悬空校验都会被绕过",
        )
        assertTrue(
            window.contains("lastDispatchWsId"),
            "药丸的生效工作区没读记住值：重启后仍会回到列表第一个仓",
        )
        assertFalse(
            window.contains("availableWorkspaces.firstOrNull()?.wsId"),
            "药丸还挂着旧的裸回退链（列表第一个）—— 记住值形同不存在",
        )
    }

    /**
     * 派单入参必须用**同一条**回退链。
     *
     * 这是这一批最容易退回旧样的一处：`submitMessage` 里原本自己写了一遍
     * `localActiveWsId ?: ……firstOrNull()?.wsId`。两处各算一次，就会画出 A 仓、
     * 派到 B 仓，而且两个值都"看起来对"。
     */
    @Test
    fun theDispatchPathUsesTheSameChain() {
        val chat = live(CHAT_SCREEN)
        val outlet = GuardSourceScan.bracedBlockAfter(chat, "fun submitMessage(")
        assertTrue(
            outlet.contains("DispatchWorkspaceChoice.resolve("),
            "派单入参没有走统一回退链：界面显示与真正写文件的仓可能不是同一个",
        )
        assertTrue(
            outlet.contains("lastDispatchWsId"),
            "派单入参没读记住值：重启后的第一单会写进列表第一个仓",
        )
        assertFalse(
            outlet.contains("agentWorkspaces.values.firstOrNull { it.peerId == peer.nodeId }?.wsId"),
            "派单入参又用「列表第一个」当兜底了 —— 这是 D8 的原样",
        )
    }

    private companion object {
        const val CHAT_SCREEN =
            "composeApp/src/commonMain/kotlin/com/kapp/tenon/feature/chat/ChatScreen.kt"
        const val APP = "composeApp/src/commonMain/kotlin/com/kapp/tenon/App.kt"
        const val SETTINGS =
            "app/src/commonMain/kotlin/com/kapp/tenon/domain/RelaySettings.kt"
    }
}
