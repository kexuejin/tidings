// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import com.kapp.tenon.platform.InMemoryAppPlatform

/**
 * WP-A0/B1：执行名单勾选必须 ≤1 帧回显。
 *
 * 病灶（overhaul-plan 诊断 B1）：行数据经 `onExecDevices` 桥来自宿主侧存储，不在
 * [RelayUiState] 里；`setExecDeviceAllowed` 写完桥后只转发不发布 —— 勾选后没有
 * 任何一次「值真的变了」的状态发布，重组不发生，勾选框停在旧值（安全语义：
 * 用户以为没勾上，再勾一次或干脆不信这块面板）。
 *
 * 修法判据：桥写完**同一次调用内** `externalStoreTick` 必须推一档（同步可断言，
 * 不赌时序）；重组后重新拉桥必须看到新值。空桥（手机壳）不动心跳、不炸。
 */
class RelayControllerExecDeviceChecklistTest {

    private val scope = CoroutineScope(Job())

    private fun controller(): RelayController = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(
            RelaySettings.firstRun().copy(
                udpPort = 0, tcpPort = 0,
                persistMessages = false, persistMessagesDecided = true,
            ),
        ),
        messageStore = InMemoryMessageStore(),
        agentStore = InMemoryAgentStore(),
        scope = scope,
    )

    private val peerNode = "33333333-3333-4333-8333-333333333333"

    @Test
    fun checkingAExecDeviceRepublishesWithinTheSameCall() = runBlocking {
        val host = controller()
        val checked = java.util.concurrent.atomic.AtomicBoolean(false)
        host.onExecDevices = { _ ->
            listOf(ExecDeviceRow(nodeId = peerNode, label = "peer", trusted = true, checked = checked.get(), isSelf = false))
        }
        host.onSetExecDeviceAllowed = { _, _, allowed -> checked.set(allowed) }

        val before = host.state.value.externalStoreTick
        host.setExecDeviceAllowed("agent-1", peerNode, allowed = true)

        assertEquals(before + 1, host.state.value.externalStoreTick, "桥写完必须同一次调用内推心跳（≤1 帧回显的前提）")
        assertTrue(
            host.execDevicesOf("agent-1")!!.single().checked,
            "重组后重新拉桥必须看到新值 —— 否则勾选就是不回显",
        )
        host.stop()
    }

    @Test
    fun uncheckingRepublishesToo() = runBlocking {
        val host = controller()
        val checked = java.util.concurrent.atomic.AtomicBoolean(true)
        host.onExecDevices = { _ ->
            listOf(ExecDeviceRow(nodeId = peerNode, label = "peer", trusted = true, checked = checked.get(), isSelf = false))
        }
        host.onSetExecDeviceAllowed = { _, _, allowed -> checked.set(allowed) }

        val before = host.state.value.externalStoreTick
        host.setExecDeviceAllowed("agent-1", peerNode, allowed = false)

        assertEquals(before + 1, host.state.value.externalStoreTick)
        assertTrue(!host.execDevicesOf("agent-1")!!.single().checked)
        host.stop()
    }

    @Test
    fun withoutTheBridgeNothingMovesAndNothingCrashes() = runBlocking {
        val host = controller() // 手机壳形态：不装桥
        val before = host.state.value.externalStoreTick
        host.setExecDeviceAllowed("agent-1", peerNode, allowed = true)
        assertEquals(before, host.state.value.externalStoreTick, "没桥就没有变更，心跳不该动")
        assertEquals(null, host.execDevicesOf("agent-1"), "没桥时界面不画配置区（null 合同）")
        host.stop()
    }
}
