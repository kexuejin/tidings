// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

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

    /**
     * 时间正序下标 → 倒序渲染视觉下标的镜像换算。
     *
     * 真身：点引用卡 / 搜索命中跳转，旧代码把正序下标直接喂给 `scrollToItem`，
     * 而列表是「数据倒序 + reverseLayout」双翻结构 —— 跳到的是**镜像位置**
     * （消息总数对称的另一头）。反向验证：把 [visualIndexOf] 改成原样返回
     * `chronologicalIndex`（不做换算），本组测试立刻变红。
     */
    @Test
    fun singleItemListMapsToItself() {
        // 单条消息的会话：正序第 0 条（也是最新一条）= 视觉第 0 条。
        // 空表永远不会走到换算（两处调用点都先判 idx >= 0），这里只钉「一条」的下限情形。
        assertEquals(0, visualIndexOf(0, 1))
    }

    @Test
    fun newestMessageInChronologicalOrderIsVisualIndexZero() {
        // 正序的最后一条 = 最新一条 = 倒序渲染的第 0 条（画在最下面、离输入区最近）。
        assertEquals(0, visualIndexOf(chronologicalIndex = 9, size = 10))
    }

    @Test
    fun oldestMessageInChronologicalOrderIsTheLastVisualIndex() {
        // 正序的第 0 条 = 最旧一条 = 倒序渲染的最后一条（历史顶端）。
        assertEquals(9, visualIndexOf(chronologicalIndex = 0, size = 10))
    }

    @Test
    fun middleMessageMirrorsAroundTheCenter() {
        // 镜像换算满足 idx + visual == size - 1：中条对称、首尾互换。
        assertEquals(4, visualIndexOf(5, 10))
        assertEquals(5, visualIndexOf(4, 10))
        assertEquals(1, visualIndexOf(1, 3))
        // 换算再走一遍要回到原点（自反性）。
        for (size in listOf(1, 2, 7, 50)) {
            for (idx in 0 until size) {
                assertEquals(idx, visualIndexOf(visualIndexOf(idx, size), size))
            }
        }
    }
}
