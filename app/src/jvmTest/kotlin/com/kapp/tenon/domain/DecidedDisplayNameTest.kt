// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 卡片上「已由 **X** 处理」那一格的名字怎么取（[decidedDisplayName]）。
 *
 * ## 这条判据是被真机像素逼出来的
 *
 * 桌面端点了自己那张卡的动作，卡上写的是一串节点 UUID（形如「已由 3f1d2c9a-… 处理」），
 * 而这台机器在「设置」里明明有名字（`settings.nick`），「可信设备」列表也早就用它
 * 兜过底 —— 只有卡片这一格漏了。
 *
 * 根因：传进广播的 `decidedBy` 是 nodeId，而**本机自己**不在对端表里，群也不保证把
 * 自己写进 `memberNicks` ⇒ 两级查找全部落空，直接露出 nodeId。
 *
 * ## 为什么单独钉一个纯函数
 *
 * 广播那条路要真节点（runtime）才走得到。把一条「名字从哪来」的判据埋进网道测试里，
 * 读的人得先读懂两个节点才能看懂断言。这里只钉取值顺序，接线由调用点那行
 * `decidedDisplayName(...)` 承担。
 *
 * ## 一个刻意的边界
 *
 * 显示名**只用于显示**。台账（`AgentCardActionLedger`）与资格判定仍然用 nodeId：
 * 把名字当身份，两台同名设备就会互相当成对方。
 */
class DecidedDisplayNameTest {

    private val selfNode = "11111111-1111-4111-8111-111111111111"
    private val selfNick = "demo-mac-97"
    private val phoneNode = "22222222-2222-4222-8222-222222222222"

    @Test
    fun selfDecisionShowsTheLocalNickInsteadOfTheNodeId() {
        // 现场：群里没有自己的昵称（建群方点自己那张卡）。
        assertEquals(
            selfNick,
            decidedDisplayName(selfNode, memberNicks = null, selfNodeId = selfNode, selfNick = selfNick),
            "自己点的卡片必须写自己起的名字 —— 否则界面上就是那串 UUID",
        )
        // 另一种现场：群里有 memberNicks，但就是没有自己这一项。
        assertEquals(
            selfNick,
            decidedDisplayName(selfNode, mapOf(phoneNode to "Pixel 6"), selfNode, selfNick),
            "memberNicks 里没有自己 ≠ 自己没有名字",
        )
    }

    @Test
    fun aMemberNickStillWins() {
        assertEquals(
            "Pixel 6",
            decidedDisplayName(phoneNode, mapOf(phoneNode to "Pixel 6"), selfNode, selfNick),
            "别人点的，用群成员昵称 —— 这条是原本就对的行为，不许被上面那条挤掉",
        )
    }

    @Test
    fun anUnnamedDeviceDegradesToItsNodeId() {
        val stranger = "33333333-3333-4333-8333-333333333333"
        assertEquals(
            stranger,
            decidedDisplayName(stranger, mapOf(phoneNode to "Pixel 6"), selfNode, selfNick),
            "既不是自己、群里也没昵称：nodeId 是唯一能说的事实，不许猜",
        )
        assertEquals(
            selfNode,
            decidedDisplayName(selfNode, null, selfNode, selfNick = ""),
            "自己也没起名字时才退 nodeId（空串不算名字）",
        )
        assertEquals(
            selfNick,
            decidedDisplayName(selfNode, mapOf(selfNode to "   "), selfNode, selfNick),
            "成员昵称是空白串时不算名字，要继续往下找 —— 这里落到自己的昵称",
        )
    }
}
