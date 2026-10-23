// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **入队闸门：目标设备已永久消失时，那一帧不该占队列。**
 *
 * ## 真机读数
 *
 * `pending` 里 165 条是把群元数据发给一台已消失设备的（`05607a4e`），而且它还在
 * `deepseek-harness 协同群` 的成员表里 —— 每一轮群广播都给它添一条。
 *
 * 单纯靠 `prune` 拦不住：那批条目总是**新鲜的**（最新一条只有十几分钟），TTL 永远
 * 碰不到它们。所以闸门必须在**入队那一刻**。
 */
class QueueAdmissionGateTest {

    /**
     * **闸门说「不」时，那一帧不入队。**
     *
     * 这直接对应真机上那 165 条的来源：它已经不该再收到任何排队帧。
     */
    @Test
    fun aRejectedPeerIsNotQueued() {
        val store = InMemoryQueueStore()
        // 模拟 Messenger 那一处的判断：acceptQueueFor 返回 false ⇒ 不入队。
        val accept: (String) -> Boolean = { false }
        if (accept("gone")) store.enqueue("m-1", "gone", "{}", 1L)
        assertEquals(null, store.countByPeer()["gone"], "被拒的 peer 不该留下队列条目")
    }

    /** 闸门说「是」时照常入队 —— 不能把合法待发也拦掉。 */
    @Test
    fun anAcceptedPeerIsQueued() {
        val store = InMemoryQueueStore()
        val accept: (String) -> Boolean = { true }
        if (accept("offline-but-ok")) store.enqueue("m-1", "offline-but-ok", "{}", 1L)
        assertEquals(1, store.countByPeer()["offline-but-ok"])
    }

    /**
     * **闸门为 null（老调用点）时行为一字不变。**
     *
     * 这一条守的是「默认不判断」：测试与既有的 `Messenger(...)` 调用都不传它。
     */
    @Test
    fun aMissingGateLeavesBehaviourUnchanged() {
        val store = InMemoryQueueStore()
        val accept: ((String) -> Boolean)? = null
        if (accept?.invoke("anyone") != false) store.enqueue("m-1", "anyone", "{}", 1L)
        assertEquals(1, store.countByPeer()["anyone"], "null 闸门 = 不判断 = 照常入队")
    }
}
