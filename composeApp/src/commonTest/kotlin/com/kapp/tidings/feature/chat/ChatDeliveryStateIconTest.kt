// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.generated.resources.Res
import com.kapp.tidings.generated.resources.ic_check_double
import com.kapp.tidings.generated.resources.ic_clock_pending
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 投递状态图标的单测。
 *
 * 真身：「发送中」这一档从前只有文字没有图标 —— 状态行里 Sent/Queued 都有一枚小图标，
 * 唯独 Sending 光秃秃，读起来像「状态行少画了东西」。修法是给 Sending 接上既有的
 * 自绘时钟图标（ic_clock_pending，与 Queued 同族语义：都还没到对方手里）。
 *
 * 反向验证：把 stateIcon() 里的 `DeliveryState.Sending -> ic_clock_pending` 分支删掉
 * （退回 null），[sendingHasAnIcon] 立刻变红。
 */
class ChatDeliveryStateIconTest {

    @Test
    fun sendingHasAnIcon() {
        assertEquals(
            Res.drawable.ic_clock_pending,
            DeliveryState.Sending.stateIcon(),
            "「发送中」要有图标：它是挂在发送侧的在途态，与「已排队」同族",
        )
    }

    @Test
    fun queuedAndSentKeepTheirIcons() {
        assertEquals(
            Res.drawable.ic_clock_pending,
            DeliveryState.Queued.stateIcon(),
        )
        assertEquals(
            Res.drawable.ic_check_double,
            DeliveryState.Sent.stateIcon(),
        )
    }

    @Test
    fun failedAndRecalledStayTextOnly() {
        assertNull(DeliveryState.Failed.stateIcon(), "失败没有对应素材：走纯文案，不拿别的图标凑数")
        assertNull(DeliveryState.Recalled.stateIcon())
    }
}
