// SPDX-License-Identifier: GPL-3.0-only
package com.kapp.tidings.feature.agents

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 事件流「显示更早」展开窗口（issue「事件流截断 20 条无展开」）。
 *
 * 旧行为：永远只画最近 20 条、只印一句「只显示最近 20 条（共 N 条）」，剩下的
 * 事件在界面上**没有任何办法看到**。新行为：基础窗口 20 条，每点一次「显示更早」
 * 多展开 [EVENTS_REVEAL_STEP] 条，直到装下全部。
 */
class AgentSessionWidgetsTest {

    @Test
    fun testVisibleEventCountBaseWindow() {
        // 未展开：最近 20 条（旧行为等价于此）。
        assertEquals(20, visibleEventCount(total = 120, revealed = 0))
        // 总数不足窗口：全画。
        assertEquals(10, visibleEventCount(total = 10, revealed = 0))
        assertEquals(0, visibleEventCount(total = 0, revealed = 0))
    }

    /**
     * 反向验证过：把实现改回「忽略 revealed、恒返回基础窗口」（旧的不展开行为），
     * 本测试变红；恢复后变绿。
     */
    @Test
    fun testVisibleEventCountExpandsByRevealStep() {
        assertEquals(70, visibleEventCount(total = 120, revealed = 50))
        assertEquals(120, visibleEventCount(total = 120, revealed = 100))
        // 展开量超过剩余条数：封顶在 total，不越界。
        assertEquals(120, visibleEventCount(total = 120, revealed = 1_000))
        // 小总额下展开一次就全画。
        assertEquals(30, visibleEventCount(total = 30, revealed = 50))
    }

    @Test
    fun testVisibleEventCountGuardsBadReveal() {
        // 防御：负数记账户（不可能发生，但纯函数不该给出越界值）退回基础窗口。
        assertEquals(20, visibleEventCount(total = 120, revealed = -1))
    }
}
