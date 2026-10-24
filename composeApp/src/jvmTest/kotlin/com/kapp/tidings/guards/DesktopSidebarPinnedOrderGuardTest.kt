// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.guards

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守卫「[P1] 置顶在桌面也要生效」这条数据流纪律。
 *
 * 置顶的第一排序键只存在于 `orderInboxFeed`。手机端在 `ConversationsScreen` 的 feed
 * 构建处过它；桌面壳（`DesktopShell`）**曾经**对 `state.conversations` 只做查询词过滤、
 * 把列表原样 map 进行的容器 —— 于是同一个置顶动作在手机上生效、在桌面上点了没反应。
 * 修法是桌面也走 `orderConversationList`（= 筛选 + `orderInboxFeed`）。
 *
 * ## 为什么是源码级守卫
 *
 * `remember { }` 里的数据流没有单测能钉住（本仓没有 Compose UI 测试基建），
 * 而"桌面列表必须过同一把排序尺"恰恰是"两处必须一致"的纪律 ——
 * 单测只守住了 `orderConversationList` 本身（见 commonTest 的
 * `ConversationsFeedOrderTest`），守不住**调用点还在不在**。与
 * [DeadButtonGuardTest] 同一取舍。
 */
class DesktopSidebarPinnedOrderGuardTest {

    private val shell = "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopShell.kt"

    @Test
    fun desktopSessionsFlowGoesThroughTheSharedOrdering() {
        val code = GuardSourceScan.stripComments(GuardSourceScan.read(shell))
        // 只看 `val sessions = remember(...)` 那一块 —— 文件里**注释提到**旧写法不算数
        //（stripComments 已剥注释，这里再限定块，防止把别的 remember 误算进来）。
        val block = GuardSourceScan.bracedBlockAfter(code, "val sessions = remember(")

        assertTrue(
            block.contains("orderConversationList("),
            "桌面会话列表的数据流必须过 orderConversationList（查询词筛选 + orderInboxFeed），" +
                "否则置顶在桌面上点了没效果 —— 那正是本次修掉的缺陷",
        )
        assertTrue(
            block.contains("state.conversations"),
            "桌面列表的数据源必须是 state.conversations（与手机同一份派生）",
        )
    }
}
