// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.ui.components.displayMillis

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
 * 时间正序下标 → 倒序渲染下的**视觉下标**。
 *
 * ## 为什么必须有这一层换算
 *
 * 消息流的列表是「数据倒序（`messages.asReversed()`）+ `reverseLayout = true`」双翻结构
 * （见 `ChatScreen` 里 LazyColumn 的 KDoc）：LazyColumn 的条目下标 0 是**最新一条**，
 * 而 `messages` 这个 List 本身仍是最旧在前。于是任何「按消息在 `messages` 里找下标、
 * 再拿去 `scrollToItem`」的调用 —— 点引用卡跳源消息、搜索命中跳原文 —— 都必须先做
 * `size - 1 - idx` 的镜像换算；直接把正序下标喂给 LazyColumn 会滚到**镜像位置**
 * （消息总数对称的另一头），看起来像「跳错了地方」。
 *
 * 抽成纯函数并单测：换算错了没有任何编译期信号，两端各自「看起来能跳」，
 * 只有对准某一条看落点才看得出来。
 *
 * @param chronologicalIndex 消息在**时间正序**列表（`messages`）里的下标。
 * @param size 列表条目总数。
 * @return 同一条消息在 LazyColumn（倒序渲染）里的下标。
 */
internal fun visualIndexOf(chronologicalIndex: Int, size: Int): Int = size - 1 - chronologicalIndex

/**
 * 这条消息是不是**会话里最早的一条**（`seq` 是会话内自增游标，第一条恒为 1）。
 *
 * 用来决定消息流顶端要不要写「已经到最早一条了」。判据必须是**事实**（seq），不能靠
 * "这一页没返回新消息" —— 域层的 loadOlder 在尽头时什么信号都不发，那会得到"永远转圈"
 * 或"永远不说到头"。`null`（本地刚构造、还没落库）不宣称到头。
 */
internal fun isHistoryBeginning(oldestSeq: Long?): Boolean = oldestSeq != null && oldestSeq <= 1L

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
 * 真正「用户主动往上翻」时，布局是有的，且最靠底的那条可见消息离底部超过
 * [NEAR_BOTTOM_TOLERANCE]，这时才返回 false。
 *
 * **`reverseLayout = true` 之后（聊天流的标准结构）**：索引 0 是最新一条（画在最下面），
 * 「贴底」= 第一可见项就是索引 0，或离它很近。既然方向翻了，判据也要跟着翻：
 * - 取的是 `visibleItemsInfo.firstOrNull()`（最靠底的那条）而不是 `lastOrNull()`；
 * - 「还能不能往最新方向滚」在倒序下是 `canScrollBackward`；
 * - 距离用 `firstVisibleIndex` 本身，不是 `itemCount - 1 - lastVisibleIndex`（倒序下方向相反）。
 */
internal fun isNearBottomAfterLayout(
    itemCount: Int,
    totalItemCount: Int,
    /** 最靠底部那条消息的索引。reverseLayout 下 0 就是最新一条。 */
    firstVisibleIndex: Int?,
    /** 还能不能往**更早**的方向滚（reverseLayout 下即 canScrollBackward）。 */
    canScrollBackward: Boolean,
): Boolean {
    // 一条消息都没有：无所谓贴不贴底，按贴底处理（不会有"下方还有消息"这回事）。
    if (itemCount == 0) return true
    // 布局还没量过 / 量过但一个可见条目都没有：如实回答「不知道」，并取安全的贴底一侧。
    if (totalItemCount == 0 || firstVisibleIndex == null) return true
    // 往回也滚不动了 = 内容都没占满一屏，或已经贴底。
    if (!canScrollBackward) return true
    return firstVisibleIndex <= NEAR_BOTTOM_TOLERANCE
}



/**
 * 把时间戳归到「哪一天」，与 [formatClock] **同一套 UTC 偏移口径**（+8 粗算）。
 *
 * 为什么不引日期库：这个文件里既有的时间做法就是纯算术 —— `formatClock` 用
 * `((atMillis / 60_000) + 8*60) % (24*60)`。日期分隔如果另用 `kotlinx-datetime` 的
 * `LocalDate`，**同一个时刻在气泡上显示 08:30、却可能被分到与它不符的一天**（两套口径）。
 * 一处口径、两处消费，才不会出现「时间说今天、分隔线说昨天」。
 *
 * 返回值是「自纪元起的第几天」的字符串，只当 key 用，不给人看。
 */
internal fun localDayKey(atMillis: Long): String =
    (displayMillis(atMillis) / 86_400_000L).toString()
/**
 * 消息流里「日期分隔」的落点：每**换一天**的第一条消息 id。
 *
 * ## 为什么需要它
 *
 * 消息只按时间戳排列，没有日期分组头 —— 翻历史时无法快速定位「这是哪天说的」，
 * 跨天的长对话读起来是一整片。`SessionDivider`（会话分段）解决的是「换了一段任务」，
 * 这一条解决的是「换了一天」，两者是同族组件、不同维度。
 *
 * ## 为什么判据是 `at` 而不是 id
 *
 * `SessionDivider` 的判据绑在消息 id 前缀上（`agent-ev-`），那件事本身有缺陷
 * （见 task-16：手机端拿不到那个前缀）。日期分隔**不重复那个错误** ——
 * 它只看 `message.at`，两端永远一致。
 *
 * @param dayKey 把时间戳归到「哪一天」的函数。传进来而不是在这里算时区，
 *   因为时区是平台事实，而纯函数要能被 jvmTest 用固定时区钉住。
 */
internal fun dayDividerIds(messages: List<MessageUi>, dayKey: (Long) -> String): Set<String> {
    val out = mutableSetOf<String>()
    var lastDay: String? = null
    for (m in messages) {
        val day = dayKey(m.at)
        if (day != lastDay) {
            out += m.id
            lastDay = day
        }
    }
    return out
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
