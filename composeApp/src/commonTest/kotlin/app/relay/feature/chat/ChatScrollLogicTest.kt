// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 消息流滚动判据的单测。
 *
 * 钉的是真机反馈 #5 的根因：**布局还没量过时不能被当成「用户往上翻过了」**。
 * 旧实现写成 `visibleItemsInfo.lastOrNull()?.index ?: 0`，于是进入任何超过三条消息的
 * 会话时，头一两帧都被判成「不贴底」，新到的消息只累加胶囊、不自动滚 —— 用户看到的
 * 就是「没跳到最新」。组合层的滚动行为要跑设备，这里把那条判据完整钉在 jvmTest 上。
 */
class ChatScrollLogicTest {

    @Test
    fun emptyListCountsAsNearBottom() {
        assertTrue(
            isNearBottomAfterLayout(
                itemCount = 0,
                totalItemCount = 0,
                firstVisibleIndex = null,
                canScrollBackward = false,
            ),
        )
    }

    @Test
    fun unmeasuredLayoutIsTreatedAsNearBottom() {
        // 进入会话的头一两帧：layoutInfo 还是空的（totalItemsCount=0、没有可见条目）。
        // 长会话也必须判成贴底，否则新消息永远不自动滚（#5 的根因）。
        assertTrue(
            isNearBottomAfterLayout(
                itemCount = 30,
                totalItemCount = 0,
                firstVisibleIndex = null,
                canScrollBackward = true,
            ),
        )
        // 量到了条目、但可见列表这一刻是空的（测量间隙）：同样取安全的一侧。
        assertTrue(
            isNearBottomAfterLayout(
                itemCount = 30,
                totalItemCount = 30,
                firstVisibleIndex = null,
                canScrollBackward = true,
            ),
        )
    }

    @Test
    fun readingHistoryUpALongListIsNotNearBottom() {
        // 倒序下索引 0 是最新一条。用户往上翻历史时，**最靠底的可见项**已经远离 0
        // （这里是 20，看着的是更早的消息），且还能往回滚 ⇒ 真的在阅读历史。
        //
        // 注意这个断言的方向已经跟正序版本相反了：正序时「停在索引 0」= 在顶部 = 不贴底，
        // 倒序时「停在索引 0」= 在底部 = 贴底。把旧断言照搬过来会**看起来还绿**，
        // 但测的是相反的事。
        assertFalse(
            isNearBottomAfterLayout(
                itemCount = 30,
                totalItemCount = 30,
                firstVisibleIndex = 20,
                canScrollBackward = true,
            ),
        )
    }

    @Test
    fun cannotScrollForwardAlwaysCountsAsNearBottom() {
        // 内容没占满一屏（或已经到底）时 canScrollForward=false：短会话照样自动滚到底。
        assertTrue(
            isNearBottomAfterLayout(
                itemCount = 2,
                totalItemCount = 2,
                firstVisibleIndex = 0,
                canScrollBackward = false,
            ),
        )
        assertTrue(
            isNearBottomAfterLayout(
                itemCount = 100,
                totalItemCount = 100,
                firstVisibleIndex = 30,
                canScrollBackward = false,
            ),
        )
    }

    /**
     * 容忍度：倒序下「最靠底的索引」0 = 最新一条。所以离 0 越远 = 越往上翻。
     */
    @Test
    fun toleranceIsTwoItems() {
        fun near(firstVisible: Int) = isNearBottomAfterLayout(
            itemCount = 30,
            totalItemCount = 30,
            firstVisibleIndex = firstVisible,
            canScrollBackward = true,
        )
        assertTrue(near(0), "最新一条可见 = 贴底")
        assertTrue(near(1), "离底一条 = 贴底")
        assertTrue(near(2), "离底两条 = 贴底（容忍度边界）")
        assertFalse(near(3), "离底三条 = 已经算翻上去了")
    }

    @Test
    fun followingLatestHappensWhenNearBottomOrOwnMessage() {
        assertTrue(shouldFollowLatest(nearBottom = true, lastMessageIsMine = false))
        assertTrue(shouldFollowLatest(nearBottom = false, lastMessageIsMine = true))
        assertFalse(shouldFollowLatest(nearBottom = false, lastMessageIsMine = false))
    }

    @Test
    fun unseenResetsOnlyWhenWeFollowLatest() {
        assertEquals(
            0,
            unseenAfterGrowth(previousUnseen = 5, added = 3, nearBottom = true, lastMessageIsMine = false),
            "贴底收到新消息：马上会看到，计数清零",
        )
        assertEquals(
            0,
            unseenAfterGrowth(previousUnseen = 5, added = 3, nearBottom = false, lastMessageIsMine = true),
            "自己发的消息必须被看见，计数清零",
        )
        assertEquals(
            8,
            unseenAfterGrowth(previousUnseen = 5, added = 3, nearBottom = false, lastMessageIsMine = false),
            "在中间读旧消息：只累加、不打扰",
        )
    }

    @Test
    fun shrinkingThreadNeverYieldsNegativeUnseen() {
        // 撤回/替换会让 size 变小；那不是新消息，不能倒扣。
        assertEquals(
            4,
            unseenAfterGrowth(previousUnseen = 4, added = -3, nearBottom = false, lastMessageIsMine = false),
        )
    }
}
