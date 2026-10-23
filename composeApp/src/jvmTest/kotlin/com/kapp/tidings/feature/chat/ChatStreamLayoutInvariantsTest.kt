// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.testsupport.GuardSourceScan

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 聊天流的两条**结构性**不变量。
 *
 * 这两条没有单测能覆盖（本仓没有 Compose UI 测试基建），但它们各对应一次真机缺陷，
 * 而且退化时**不会报错**：界面照样画出来，只是画在了不对的位置。
 *
 * 用源码级守卫是正当的 —— 与 `SystemBarsInsetGuardTest` / `PairingEntryPointsGuardTest` 同一取舍。
 */
class ChatStreamLayoutInvariantsTest {

    private val chatScreen =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt"

    private fun live(): String = GuardSourceScan.stripComments(GuardSourceScan.read(chatScreen))

    /**
     * **倒序渲染必须保持**。
     *
     * 正序时「最新在末尾」，进入会话要先量完整表才滚得准，而 LazyColumn 在容器高度未稳定
     * 时会把 `canScrollForward` 提前报成 false —— 真机实测落点 firstVisibleItemIndex=74
     * （应为 83），表现为「进会话停在中间」。三轮修补都在修「何时该滚」，没解决「滚的时候
     * 还没量完」。倒序后索引 0 就是最新一条，这个问题从根上不存在。
     *
     * 退化形态：有人把 reverseLayout 去掉「因为看着不对劲」—— 那条缺陷会立刻回来，
     * 而且看起来一切正常。
     */
    @Test
    fun theMessageListStaysReversed() {
        val src = live()
        assertTrue(
            src.contains("reverseLayout = true"),
            "聊天流又变回正序了 —— 「进会话不滚到底」会立刻复现（真机实测过 firstVis=74 而非 83）",
        )
    }

    /**
     * **倒序时数据也要倒过来**，而且 `verticalArrangement` 必须带底对齐。
     *
     * 两个都是 reverseLayout 的连带条件，漏任一个都会「看起来差不多、其实错了」：
     * - 只翻视觉不翻数据 ⇒ 最新在上、最旧在下（真机截图过一次）；
     * - 不带 `Alignment.Bottom` ⇒ 短会话气泡停在顶部、下方大片空白（真机截图过）。
     */
    @Test
    fun theReversedListAlsoReversesDataAndSticksToBottom() {
        val src = live()
        assertTrue(
            src.contains("messages.asReversed()"),
            "reverseLayout 下数据源没倒过来 —— 会变成「最新在上、最旧在下」",
        )
        // 断言的是**那个 LazyColumn 的 verticalArrangement 参数**，不是「文件里有没有
        // Alignment.Bottom」—— 后者会被同文件里别处的 `.align(Alignment.BottomCenter)`
        // 满足，守卫于是永远为真（反向验证时实测过：把参数去掉它照样绿）。
        // 这正是 AGENTS §4 说的「断言了不可区分的东西」。
        val arrangementLine = src.lines().firstOrNull { it.contains("verticalArrangement") }
        assertTrue(
            arrangementLine != null &&
                arrangementLine.contains("Alignment.Bottom") &&
                !arrangementLine.contains("BottomCenter"),
            "内容不足一屏时气泡会停在顶部、下方留大片空白（短会话真机现场）；" +
                "实得 verticalArrangement = " + arrangementLine,
        )
    }
}
