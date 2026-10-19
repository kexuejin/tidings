// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

/**
 * 消息流「贴底」判据与屏外未读的累加规则。
 *
 * ## 为什么把它们提成纯函数
 *
 * 真机反馈「进入会话详情没有跳到最新消息」的根因就在贴底判据里：布局还没量过时
 * `layoutInfo.visibleItemsInfo` 是空列表，旧实现用 `?: 0` 把它当成「最后可见的是第 0 条」，
 * 于是任何超过两条消息的会话在头一两帧都被判成「用户往上翻过了」，新到的消息只累加胶囊、
 * 不自动滚。这条判断是整屏滚动行为的开关，必须能被单测钉住 —— 组合测试要跑设备，
 * 这里在 jvmTest 上秒级验证。
 */

/** 「贴底」的容忍度：最后一条离可见区底部不超过两条就不打扰滚动。 */
internal const val NEAR_BOTTOM_TOLERANCE: Int = 2

/**
 * 列表当前是否算「贴底」（贴底 = 值得自动滚到最新、不值得弹新消息胶囊）。
 *
 * [totalItemCount] 与 [lastVisibleIndex] 都来自 `LazyListState.layoutInfo`：
 * 前者是已测量到的条目总数，后者是最后一个可见条目的索引。**两者都缺省时算贴底**，
 * 这是本函数唯一与直觉不同的地方，理由是这个缺省值本身就是 bug 的来源：
 *
 * - 进入会话的头一两帧、以及列表刚被重新测量时，布局信息是空的；
 * - 那时如果按「最后可见 = 0」去算，消息多于三条就会得出「用户正在上方阅读」，
 *   而真相是「我们还不知道他在哪」；
 * - 把「不知道」当成贴底是安全的那一侧：多滚一次只是把用户带到最新，
 *   而少滚一次会让他在以为看着最新的时候错过后面的消息（这正是用户报的现象）。
 *
 * 真正「用户主动往上翻」时，布局是有的、`canScrollForward` 是 true、最后可见索引离
 * 末尾超过 [NEAR_BOTTOM_TOLERANCE]，这时才返回 false。
 */
internal fun isNearBottomAfterLayout(
    itemCount: Int,
    totalItemCount: Int,
    lastVisibleIndex: Int?,
    canScrollForward: Boolean,
): Boolean {
    // 一条消息都没有：无所谓贴不贴底，按贴底处理（不会有"下方还有消息"这回事）。
    if (itemCount == 0) return true
    // 布局还没量过 / 量过但一个可见条目都没有：如实回答「不知道」，并取安全的贴底一侧。
    if (totalItemCount == 0 || lastVisibleIndex == null) return true
    // 已经滚不动了 = 内容都没占满一屏，或已经到底。
    if (!canScrollForward) return true
    return itemCount - 1 - lastVisibleIndex <= NEAR_BOTTOM_TOLERANCE
}

/**
 * 新消息到达后是否应该把视野带回最新。
 *
 * 两条都要带回去：
 * - 本来就贴底 —— 用户在看最新，新消息应该直接出现；
 * - 最后一条是**自己刚发的** —— 发送者必须能看见自己那条消息落到哪，
 *   否则「点了发送什么都没发生」。
 *
 * 其余情况（用户在中间读旧消息）不打扰，改由胶囊提示。
 */
internal fun shouldFollowLatest(nearBottom: Boolean, lastMessageIsMine: Boolean): Boolean =
    nearBottom || lastMessageIsMine

/**
 * 新消息到达后，屏外未读数该怎么变。
 *
 * 跟着最新走的时候清零（那些消息马上就会被看到），否则累加。
 * [added] 用 `coerceAtLeast(0)`：消息被撤回/替换时 size 可能变小，那不是「新消息」，
 * 不能倒扣出一个负数胶囊。
 */
internal fun unseenAfterGrowth(
    previousUnseen: Int,
    added: Int,
    nearBottom: Boolean,
    lastMessageIsMine: Boolean,
): Int = if (shouldFollowLatest(nearBottom, lastMessageIsMine)) {
    0
} else {
    previousUnseen + added.coerceAtLeast(0)
}
