// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.platform.InMemoryAppPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 戳一碰的「定位到该会话」（协议 §8）。
 *
 * 真正收到戳一碰只有起真节点才会走（[RelayController.onNudged] 是私有的），所以这里钉的是
 * 它落进状态后的那条**一次性**契约：放进去、外壳消费掉、消费完不再重复。
 *
 * 为什么"不再重复"值得一条测试：请求留在状态里的话，用户手动切到别的会话后，下一次重组
 * 又会被拽回被戳的那个会话 —— 那时他已经自己选过了，这就不再是"定位"而是"抢方向盘"。
 */
class NudgeFocusTest {

    private val scope = CoroutineScope(Job())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun controller() = RelayController(
        platform = InMemoryAppPlatform(),
        store = InMemorySettingsStore(),
        scope = scope,
    )

    @Test
    fun theFocusRequestIsClearedOnceTheShellHasConsumedIt() {
        val controller = controller()
        assertNull(controller.state.value.focusRequest, "冷启动不该有定位请求")

        controller.requestFocus("peer-1")
        assertEquals("peer-1", controller.state.value.focusRequest)

        controller.consumeFocusRequest()
        assertNull(controller.state.value.focusRequest, "消费过就必须清掉")
    }

    @Test
    fun aSecondNudgeReplacesThePendingTarget() {
        val controller = controller()
        controller.requestFocus("peer-1")
        controller.requestFocus("peer-2")
        assertEquals("peer-2", controller.state.value.focusRequest, "后到的请求说了算，不排队")
    }
}
