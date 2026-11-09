// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 派单工作区回退链。
 *
 * 这些用例守的是**一个具体后果**：往哪个仓写文件。
 * 真机现场（A22 第四轮）把这个值弄丢了一次 —— App 重启后药丸回到列表第一个，
 * 于是本来要写 `teahouse-mobile` 的一单落进了 `china-pass`。所以这里每条断言都
 * 不只是"算得对"，而是"文件会落在用户刚才指的那个仓里"。
 */
class DispatchWorkspaceChoiceTest {

    private val teahouse = "ws-e9b18063d183"
    private val chinaPass = "ws-3131b40ab6d5"
    private val available = listOf(chinaPass, teahouse)

    /** 用户选过的仓必须赢过「列表第一个」—— 这就是 D8 的正题。 */
    @Test
    fun rememberedWorkspaceWinsOverTheFirstInList() {
        assertEquals(teahouse, DispatchWorkspaceChoice.resolve(teahouse, available))
    }

    /**
     * 悬空 id 不采纳。
     *
     * 设置是**跨设备**的：换一台手机、或在桌面端解绑了某个工作区之后，
     * 落盘的 `lastDispatchWsId` 就指向一个本机已经没有的仓。照它派单 =
     * 往不存在的目录写字（或更糟：撞上同名的另一个仓）。
     */
    @Test
    fun aStaleRememberedIdFallsBackToTheFirst() {
        assertEquals(chinaPass, DispatchWorkspaceChoice.resolve("ws-deadbeef", available))
    }

    /** 从没选过 ⇒ 老行为（列表第一个），不能因为加了记忆就变成"不派单"。 */
    @Test
    fun noMemoryKeepsTheOldDefault() {
        assertEquals(chinaPass, DispatchWorkspaceChoice.resolve(null, available))
    }

    /**
     * 空串当"没记住"。
     *
     * 空串会**落盘**（`encodeDefaults = false` 只省掉等于默认值的字段，
     * 一旦用户选过又碰上解绑，读回来的就是空），如果它被当成一个 id，
     * 这里就会返回 `""` —— 调用方拿它去查工作区，查不到，于是静默不派单。
     */
    @Test
    fun blankIsTreatedAsNoMemory() {
        assertEquals(chinaPass, DispatchWorkspaceChoice.resolve("", available))
    }

    /** 一个工作区都没有 ⇒ 明确 null。调用方据此**不派单**，而不是派给一个空 wsId。 */
    @Test
    fun noWorkspaceMeansNoTarget() {
        assertNull(DispatchWorkspaceChoice.resolve(teahouse, emptyList()))
    }

    /** 只有一个工作区时，记不记得都是它 —— 顺手钉住"单工作区用户不受这次改动影响"。 */
    @Test
    fun singleWorkspaceIsAlwaysItself() {
        val only = listOf(teahouse)
        assertEquals(teahouse, DispatchWorkspaceChoice.resolve(null, only))
        assertEquals(teahouse, DispatchWorkspaceChoice.resolve(chinaPass, only))
    }
}
