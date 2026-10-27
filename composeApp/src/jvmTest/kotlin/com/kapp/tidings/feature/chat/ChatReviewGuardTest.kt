// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.testsupport.GuardSourceScan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 界面 review（聊天 + 会话列表）这一轮修复的**接线级守卫**。
 *
 * 判据为什么只能看源码：这些纪律都落在 Composable 内部或"调用点有没有用上"上，
 * 行为测试够不到（本仓没有 Compose UI 测试基建）。每条都写成**能区分对错**的形状：
 * 把修复回退，它就红。
 */
class ChatReviewGuardTest {

    private fun live(path: String): String = GuardSourceScan.stripComments(GuardSourceScan.read(path))

    /**
     * ① 群里的「中断」必须是**控制面动作**，不许再把 `/cancel` 当群文本发。
     *
     * 回退方式：把 `onInterrupt` 改回 `{ onSend(peer.nodeId, "/cancel", null) }` ⇒ 本条红。
     */
    @Test
    fun interruptIsAControlActionNotAChatMessage() {
        val chat = live(CHAT_SCREEN)
        assertFalse(
            chat.contains("\"/cancel\""),
            "聊天页又出现 /cancel 文本了：它会作为普通群消息广播给所有人，而且对端不认命令时根本没中断",
        )
        assertTrue(
            chat.contains("actions.onCancelAgentRun"),
            "「中断」没有接控制面（onCancelAgentRun）—— 要么按钮消失，要么又退回发假消息",
        )
        val bar = live(GROUP_PROGRESS)
        assertFalse(
            bar.contains("onSend("),
            "进度条自己发消息了 —— 控制意图一旦走聊天通道，用户就会在群里看到一条假消息",
        )
    }

    /**
     * ② 历史翻页必须有可见反馈与终点（设计规格声明了 loading_history 槽）。
     *
     * 回退方式：删掉 `item(key = "history-edge-loading")` / `history-edge-begin` 两条 ⇒ 本条红。
     */
    @Test
    fun historyEdgesAreVisible() {
        val chat = live(CHAT_SCREEN)
        // 「源码里有这个 key」不等于「它会画出来」：判据要看它**出现在哪个条件里**。
        // （不能用 bracedBlockAfter("if (historyLoading) {")：文件里还有一个同名条件的
        //  effect 负责"放弃等待"，会先命中那一个 —— 那正是本条守卫要防的假绿形态。）
        val keyAt = chat.indexOf("history-edge-loading")
        assertTrue(keyAt > 0, "没有「正在加载更早的消息」条目 —— 滚到顶屏幕一动不动")
        assertTrue(
            chat.substring(maxOf(0, keyAt - 240), keyAt).contains("if (historyLoading)"),
            "「正在加载更早的消息」没有挂在 historyLoading 条件上 —— 滚到顶屏幕一动不动",
        )
        assertTrue(chat.contains("history-edge-begin"), "没有「已经到最早一条了」—— 用户永远不知道翻到头了")
        assertTrue(
            chat.contains("chat_history_loading") && chat.contains("chat_history_begin"),
            "两条边界提示必须走资源文案（中文硬编码会被 CJK 门禁拦）",
        )
    }

    /**
     * ③ 草稿必须能出现在会话列表（接缝 + 行内呈现）。
     *
     * 回退方式：删掉 ConversationRow 的 `draftPreview ?:` 分支 ⇒ 本条红。
     */
    @Test
    fun draftShowsUpInTheConversationList() {
        assertTrue(
            live(CONV_ROW).contains("draftPreview?.let { stringResource(Res.string.conv_preview_draft, it) }"),
            "会话行没有优先显示草稿（带「草稿：」标记）—— 草稿保住了但列表看不见，用户只能逐个点进去找",
        )
        assertTrue(
            live(CONV_PANEL).contains("draftPreviewOf?.invoke"),
            "列表容器没把 draftPreviewOf 运到行 —— 接缝断在中间这一层",
        )
    }

    /**
     * ④ 静音预览不许再用透明色弱化（实算 2.66:1 不达标），且行必须可被键盘聚焦。
     *
     * 回退方式：把 `onSurfaceVariant.copy(alpha = …)` 放回预览；删掉 `focusable()` ⇒ 本条红。
     */
    @Test
    fun mutedPreviewStaysReadableAndRowsAreFocusable() {
        val row = live(CONV_ROW)
        assertFalse(
            row.contains("onSurfaceVariant.copy(alpha"),
            "预览又用 alpha 弱化了 —— 合成后 2.66:1（亮）/3.21:1（暗），12sp 小字读不出",
        )
        assertTrue(row.contains("MutedPreviewWeight"), "静音的弱化应当只走字重这一档")
        assertTrue(row.contains(".focusable()"), "会话行不可键盘聚焦 —— 桌面用户 Tab 进不了列表")
        assertTrue(row.contains("onFocusChanged"), "没有焦点环的钩子 —— 聚焦了也看不见焦点在哪")
    }

    /**
     * ⑤ 日期必须带年份（月历标题 + 选中/区间标签），且月历非本月格不可点。
     */
    @Test
    fun datesCarryTheYearAndCalendarOutOfMonthIsDisabled() {
        val hist = live(HISTORY_OVERLAY)
        assertTrue(hist.contains("hist_date_month_year"), "月历标题没有年份 —— 翻到同一个「10月」无法区分年份")
        assertTrue(hist.contains("hist_date_day_year"), "选中/区间日期没有年份 —— 跨年查找无从核对")
        assertTrue(hist.contains("dayCellSelectable(isCurrentMonth)"), "非本月日期格没有按判据禁用")
    }

    /** 删除单条必须先确认；复制必须有反馈（且没有可复制文本时不画死项）。 */
    @Test
    fun destructiveDeleteConfirmsAndCopyGivesFeedback() {
        val bubbles = live(CHAT_BUBBLES)
        assertTrue(
            bubbles.contains("DeleteChatMessageConfirmDialog("),
            "长按菜单的「删除本机」没有前置确认 —— 误触即永久丢一条消息",
        )
        assertTrue(bubbles.contains("common_copied"), "复制没有反馈 —— 用户不知道成没成，会重复点")
        assertTrue(
            bubbles.contains("if (copyText.isNotEmpty())"),
            "没有可复制文本时仍画「复制」—— 点了什么都不发生的死项",
        )
    }

    /** 回复预览不许按 UTF-16 预截断（会劈开 emoji）；取消键触达 48dp。 */
    @Test
    fun replyPreviewAndTouchTargetsAreFixed() {
        assertFalse(live(MESSAGE_COMPOSER).contains("replyText.take("), "手机回复预览又按码元截断了 —— emoji 会被劈开")
        assertFalse(live(DESKTOP_COMPOSER).contains("replyText.take("), "桌面回复预览又按码元截断了")
        assertTrue(
            live(MESSAGE_COMPOSER).contains("IconButton(onClick = onCancelReply, modifier = Modifier.size(48.dp))"),
            "回复条的取消键不是 48dp 触达 —— 24dp 拇指按不中",
        )
    }

    /**
     * 被移出群后必须只读（否则用户打完字只得到一条「发送失败」）。
     *
     * ## 为什么要守**整条链**，而不是只守两端
     *
     * 只读是由四段接力完成的，断任何一段症状都一样（输入框照常可用、点发送才失败）：
     * `ChatScreen` 算出 `leftGroup` → 塞进 `ComposerSlotArgs.readOnlyReason`
     * → 桥梁 `DesktopChatPane` 原样透传 → 两个输入区各自把输入区整块换掉。
     * 这条守卫此前只断言了「`ChatScreen` 有这个事实」与「手机输入栏有这个参数」，
     * **桌面那一半是碰巧接上的**（没人守 `DesktopComposer` 与那段透传）。
     *
     * 回退方式（任一条都会让这条用例变红）：删掉 `leftGroup` 的派生 / 不把
     * `readOnlyReason` 交给 slot / 桥梁不再透传 / 任一输入区删掉只读分支。
     */
    @Test
    fun removedFromGroupMakesTheComposerReadOnly() {
        val chat = live(CHAT_SCREEN)
        assertTrue(chat.contains("val leftGroup ="), "聊天页没有「我不在群里了」这个事实")
        assertTrue(chat.contains("chat_group_readonly_left"), "只读原因没有交给输入栏")

        // ① 手机内置输入栏：声明了参数**并且**真的用它换掉输入区。
        val mobile = live(MESSAGE_COMPOSER)
        assertTrue(
            mobile.contains("readOnlyReason: String? = null"),
            "内置输入栏没有只读态 —— 只读原因传进去也没人用",
        )
        assertTrue(
            mobile.contains("if (readOnlyReason != null)"),
            "内置输入栏收到了只读原因却不判它 —— 输入框照旧可用",
        )

        // ② 桌面输入卡片：同样的两条。此前这一段没有任何守卫。
        val desktop = live(DESKTOP_COMPOSER)
        assertTrue(
            desktop.contains("readOnlyReason: String? = null"),
            "桌面输入卡片没有只读态 —— 被移出后桌面仍能打字、点发送才失败",
        )
        assertTrue(
            desktop.contains("if (readOnlyReason != null)"),
            "桌面输入卡片收到了只读原因却不判它",
        )

        // ③ 桥梁：`ComposerSlotArgs` 带着它、桌面聊天面板原样透传。
        //    少了这一跳，ChatScreen 传的东西到不了桌面输入卡片（空接线）。
        assertTrue(
            live(CHAT_ARGS).contains("readOnlyReason"),
            "ComposerSlotArgs 没有 readOnlyReason —— 只读原因过不了壳的边界",
        )
        assertTrue(
            live(DESKTOP_CHAT_PANE).contains("readOnlyReason = args.readOnlyReason"),
            "桌面聊天面板没有把 readOnlyReason 交给输入卡片（接了不用 = 空接线）",
        )
    }

    private companion object {
        const val CHAT_SCREEN =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt"
        const val CHAT_BUBBLES =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatBubbles.kt"
        const val GROUP_PROGRESS =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/GroupProgressBar.kt"
        const val HISTORY_OVERLAY =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/HistorySearchOverlay.kt"
        const val CONV_ROW =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/conversations/ConversationRow.kt"
        const val CONV_PANEL =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/conversations/ConversationListPanel.kt"
        const val MESSAGE_COMPOSER =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/MessageComposer.kt"
        const val DESKTOP_COMPOSER =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopComposer.kt"
        const val DESKTOP_CHAT_PANE =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopChatPane.kt"
        const val CHAT_ARGS =
            "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreenArgs.kt"
    }
}