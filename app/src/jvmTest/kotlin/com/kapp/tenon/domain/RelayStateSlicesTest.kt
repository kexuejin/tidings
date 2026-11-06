// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import com.kapp.tenon.domain.model.MdnsServiceUi
import com.kapp.tenon.domain.model.TransferRecordUi
import com.kapp.tenon.platform.InMemoryAppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Phase 4 切片**语义**断言：跨面写入不产生该订阅面的新值。
 *
 * 这是本仓唯一能可判定地表达「切片真的切开了」的量具。不承诺重组率/帧率数字
 * （本仓没有 Compose UI 测试基建），只承诺这条语义：
 *
 * - 只写会话面字段 ⇒ 只有 chatFlow 发新值，其余六片零发射；
 * - 只写 agent / 节点 / 传输 / 设置 / 横幅 / 头像面 ⇒ 各自一片发新值，chatFlow 零发射；
 * - 写**相等值** ⇒ 所有片零发射。
 *
 * 反向验证（改坏实现会红）：
 *  - 往 ChatSlice 里加一个 agent 字段 ⇒ agent 写入那条断言变红；
 *  - 把某个 slice 的投影里混进 `settings` ⇒ 设置写入那条断言变红；
 *  - 去掉某片的 `distinctUntilChanged` ⇒ 由源码守卫 `RelayStateSlicesGuardTest` 变红。
 */
class RelayStateSlicesTest {

    private fun controller(scope: CoroutineScope): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = 0,
                tcpPort = 0,
                persistMessages = false,
                persistMessagesDecided = true,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    @Test
    fun crossSurfaceWritesOnlyEmitOnTheirOwnSlice() = runTest {
        val controller = controller(backgroundScope)
        val chat = mutableListOf<ChatSlice>()
        val agent = mutableListOf<AgentSlice>()
        val node = mutableListOf<NodeSlice>()
        val transfer = mutableListOf<TransferSlice>()
        val settings = mutableListOf<SettingsSlice>()
        val chrome = mutableListOf<ChromeSlice>()
        val avatar = mutableListOf<AvatarSlice>()
        watch(backgroundScope, controller.chatFlow, chat)
        watch(backgroundScope, controller.agentFlow, agent)
        watch(backgroundScope, controller.nodeFlow, node)
        watch(backgroundScope, controller.transferFlow, transfer)
        watch(backgroundScope, controller.settingsFlow, settings)
        watch(backgroundScope, controller.chromeFlow, chrome)
        watch(backgroundScope, controller.avatarFlow, avatar)
        runCurrent()
        assertEquals(List(7) { 1 }, sizes(chat, agent, node, transfer, settings, chrome, avatar), "每片订阅先拿到一次初值")

        var before = sizes(chat, agent, node, transfer, settings, chrome, avatar)
        controller._state.update { it.copy(unreadByPeer = it.unreadByPeer + ("peer-a" to 1)) }
        runCurrent()
        assertOnlyChanged(before, 0, sizes(chat, agent, node, transfer, settings, chrome, avatar), "只写会话面字段")

        before = sizes(chat, agent, node, transfer, settings, chrome, avatar)
        controller._state.update { it.copy(pendingGrantRequests = setOf("grant-1")) }
        runCurrent()
        assertOnlyChanged(before, 1, sizes(chat, agent, node, transfer, settings, chrome, avatar), "只写 agent 面字段")

        before = sizes(chat, agent, node, transfer, settings, chrome, avatar)
        controller._state.update { it.copy(mdnsServices = listOf(MdnsServiceUi("svc", "host", 1))) }
        runCurrent()
        assertOnlyChanged(before, 2, sizes(chat, agent, node, transfer, settings, chrome, avatar), "只写节点面字段")

        before = sizes(chat, agent, node, transfer, settings, chrome, avatar)
        controller._state.update {
            it.copy(transferLogs = listOf(TransferRecordUi("t-1", "a.txt", 10L, false, 1L, true)))
        }
        runCurrent()
        assertOnlyChanged(before, 3, sizes(chat, agent, node, transfer, settings, chrome, avatar), "只写传输面字段")

        before = sizes(chat, agent, node, transfer, settings, chrome, avatar)
        controller._state.update { it.copy(settings = it.settings.copy(nick = "nick-2")) }
        runCurrent()
        assertOnlyChanged(before, 4, sizes(chat, agent, node, transfer, settings, chrome, avatar), "只写设置面字段")

        before = sizes(chat, agent, node, transfer, settings, chrome, avatar)
        controller._state.update { it.copy(focusRequest = "peer-a") }
        runCurrent()
        assertOnlyChanged(before, 5, sizes(chat, agent, node, transfer, settings, chrome, avatar), "只写横幅/一次性请求字段")

        before = sizes(chat, agent, node, transfer, settings, chrome, avatar)
        controller._state.update { it.copy(pickedAvatarName = "a.png") }
        runCurrent()
        assertOnlyChanged(before, 6, sizes(chat, agent, node, transfer, settings, chrome, avatar), "只写头像面字段")

        before = sizes(chat, agent, node, transfer, settings, chrome, avatar)
        controller._state.update { it }
        runCurrent()
        assertEquals(before, sizes(chat, agent, node, transfer, settings, chrome, avatar), "写相等值不得产生任何切片新值")

        controller.stop()
    }

    private fun <T> watch(scope: CoroutineScope, flow: StateFlow<T>, sink: MutableList<T>) {
        scope.launch { flow.collect { sink += it } }
    }

    private fun sizes(
        chat: List<*>,
        agent: List<*>,
        node: List<*>,
        transfer: List<*>,
        settings: List<*>,
        chrome: List<*>,
        avatar: List<*>,
    ): List<Int> = listOf(chat.size, agent.size, node.size, transfer.size, settings.size, chrome.size, avatar.size)

    /** 只允许第 [onlyIndex] 片多一次发射，其余片必须零发射。 */
    private fun assertOnlyChanged(before: List<Int>, onlyIndex: Int, current: List<Int>, what: String) {
        for (i in current.indices) {
            val delta = current[i] - before[i]
            val expected = if (i == onlyIndex) 1 else 0
            assertEquals(expected, delta, what + "：第 " + i + " 片的发射增量为 " + delta + "，期望 " + expected + "（0=chat,1=agent,2=node,3=transfer,4=settings,5=chrome,6=avatar）")
        }
    }
}
