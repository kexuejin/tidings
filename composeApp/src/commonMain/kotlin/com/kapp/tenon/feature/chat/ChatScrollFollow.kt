// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.kapp.tenon.domain.model.MessageUi

/**
 * ChatScreen 的**贴底判据与未读记账**（ARP-14：从 ChatScreen.kt 移出，逐行搬移）。
 *
 * 三个状态（`unreadOnEntry` / `unseenWhileAway` / `previousMessageCount`）与两个
 * LaunchedEffect（新消息增长记账、贴底清零）整段内聚在这个值对象里；消费端只有
 * 三样：`isNearBottom`（画不画浮起胶囊）、`unseenTotal`（胶囊上的数字）、
 * `clearUnseen()`（点胶囊清零）。
 *
 * remember / LaunchedEffect 键逐条原样保留 —— 这段判据修过真机反馈 #5
 * （往上翻旧消息被拽回底部），动键前先读注释。
 */
internal class ChatScrollFollowState(
    val isNearBottom: State<Boolean>,
    unreadOnEntry: MutableState<Int>,
    unseenWhileAway: MutableState<Int>,
) {
    private val unreadOnEntryState = unreadOnEntry
    private val unseenWhileAwayState = unseenWhileAway

    /** 浮起胶囊上的数字：进入时屏外未读 + 进入后离底新增。两个都没有时胶囊只写「回到最新」。 */
    val unseenTotal: Int
        get() = unreadOnEntryState.value + unseenWhileAwayState.value

    /** 点胶囊：两个未读数都放掉（点它 = 用户已经看见了最新一条）。 */
    fun clearUnseen() {
        unreadOnEntryState.value = 0
        unseenWhileAwayState.value = 0
    }
}

@Composable
internal fun rememberChatScrollFollow(
    listState: LazyListState,
    peerId: String,
    messages: List<MessageUi>,
    initialUnreadOnEntry: Int,
): ChatScrollFollowState {
    // 新消息到达时**只在已经贴底时**才自动滚。原来的写法无条件滚，于是用户往上翻旧消息
    // 时，任何一条新消息（包括屏幕协助记下来的那条）都会把视图拽回底部 —— 读旧消息这件事
    // 就没法做了。判据提在 [isNearBottomAfterLayout]（纯函数、有单测）：它同时覆盖
    // "内容还没占满一屏"（本来就不能滚，视为贴底，短会话照样自动滚到底）、
    // "布局还没量过"（进入会话的头一两帧，此时也必须当成贴底，否则新到的消息永远不滚 ——
    // 真机反馈 #5 的根因就在旧判据那句 `?: 0` 兜底里）与"用户往上翻过了"（能往前滚 ⇒ 不打扰）。
    //
    // 用 derivedStateOf 而不是在 effect 里现读：effect 只在 messages.size 变化时重启，
    // 而"是否贴底"是随滚动持续变化的，必须在滚动时就更新，否则拿到的是上一次的快照。
    // 最后可见索引**不要用 `?: 0` 兜底**：那会把"还没量过"说成"可见的是第 0 条"。
    // **reverseLayout = true**：索引 0 是最新一条（画在最下面）。所以「最靠底的那条」
    // 是 visibleItemsInfo.firstOrNull()，不是 lastOrNull() —— 后者在倒序下是最早的一条。
    val bottomVisibleIndex by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index }
    }
    // key 必须带 peerId：只按条数记时，两个**条数相同**的会话互切不会重建，
    // `derivedStateOf` 里捕获的还是上一个会话的 itemCount ⇒ 贴底判据算错。
    val isNearBottomState = remember(peerId, messages.size) {
        derivedStateOf {
            isNearBottomAfterLayout(
                itemCount = messages.size,
                totalItemCount = listState.layoutInfo.totalItemsCount,
                firstVisibleIndex = bottomVisibleIndex,
                // 倒序下「继续往最新方向滚」是 canScrollBackward。
                canScrollBackward = listState.canScrollBackward,
            )
        }
    }
    // 进入这一屏时屏外的未读。数字只来自壳在清未读**之前**采下的值
    // （ChatScreenState.unreadOnEntry），拿不到就是 0 —— 指示条会退化成不带数字的
    // 「回到最新」，绝不编一个条数。
    val unreadOnEntryState = remember(peerId) { mutableStateOf(initialUnreadOnEntry) }
    // 进入之后、用户不在底部时新到的条数。
    val unseenWhileAwayState = remember(peerId) { mutableStateOf(0) }
    var previousMessageCount by remember(peerId) { mutableStateOf(messages.size) }
    // 「为**哪个会话**做过首次锚定」。用 peerId 而不是布尔量，是因为布尔量的复位时机
    // 依赖 `remember(peerId)` 的重建与 `LaunchedEffect` 重启谁先发生 —— 两个都有消息的
    // 会话互切时，`messages.isEmpty()` 不变（false→false），复位分支不触发，而布尔量
    // 还没随 remember 刷新 ⇒ 读到的仍是上一个会话的 true ⇒ **一次都不滚**。
    // 判据换成「这个 peerId 滚过没有」后，就不再依赖那两次重建的顺序。
    //
    // 进入会话：**倒序渲染后不需要锚定**。
    //
    // 正序版本要在这里反复 `scrollToItem(lastIndex)`，而 LazyColumn 在容器高度未稳定时
    // 会提前把 `canScrollForward` 报成 false —— 真机实测落点 firstVisibleItemIndex=74
    // （应为 83），于是「进会话停在中间」。三轮修补（布尔量 / peerId 记账 / 进入令牌）
    // 都是在修「何时该滚」，没解决「滚的时候还没量完」，所以一直没修好。
    //
    // `reverseLayout = true` 把问题从根上取消：索引 0 就是最新一条，进入即在底部，
    // 没有「要滚到哪」这件事。这里只保留「空会话时复位未读计数」那一支。
    LaunchedEffect(peerId, messages.size) {
        if (messages.isEmpty()) {
            // 会话被清空（或本来就空）：复位未读计数。
            previousMessageCount = 0
            unseenWhileAwayState.value = 0
        }
    }

    LaunchedEffect(messages.size) {
        val newAdded = (messages.size - previousMessageCount).coerceAtLeast(0)
        previousMessageCount = messages.size
        if (newAdded <= 0) return@LaunchedEffect
        val lastMsgIsMine = messages.lastOrNull()?.mine == true
        unseenWhileAwayState.value = unseenAfterGrowth(unseenWhileAwayState.value, newAdded, isNearBottomState.value, lastMsgIsMine)
        if (shouldFollowLatest(isNearBottomState.value, lastMsgIsMine)) {
            // reverseLayout：最新一条在索引 0，不是 lastIndex。
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(isNearBottomState.value) {
        if (!isNearBottomState.value) return@LaunchedEffect
        // 贴底 = 最新那条已经在眼前，进入时采下的 N 条未读**不再在屏幕外**，
        // 计数到此为止。放掉它而不是留着：否则用户之后往上翻旧消息时，胶囊会拿一个
        // 早就看过的旧数字来骗他。
        //
        // 倒序渲染后「进入会话」本身就在底部，所以这条不再需要「是否锚定过」那个前置。
        unseenWhileAwayState.value = 0
        unreadOnEntryState.value = 0
    }

    return ChatScrollFollowState(
        isNearBottom = isNearBottomState,
        unreadOnEntry = unreadOnEntryState,
        unseenWhileAway = unseenWhileAwayState,
    )
}
