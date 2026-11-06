// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.domain.model.MessageUi

/**
 * 一屏消息**渲染前**的整表派生（轮 15 / WP2）。
 *
 * 以前这些量在 `ChatMessageList` 里各挂一个 `remember(messages)`：五个缓存各自失效、
 * 各自重算，加一种「要扫全表才知道怎么画」的判据就顺手挂第六个。现在它们同一次
 * 派生、同一次失效，行级注册表（WP3）也从这里拿整表上下文，而不是每个适配器
 * 自己去扫一遍 messages。
 *
 * 三个集合的算法本体仍分别住在 `sessionTransitionIds` / `timelineSeparatorIds` /
 * `latestRevCardMessageIds`：判据只有一份，这里只做装配。byId 与 positions 原本是
 * 两次遍历，合成一次循环。
 */
internal data class ChatListLayout(
    /** 该消息的位置要画会话分段线。 */
    val sessionTransitions: Set<String>,
    /** 该消息的位置要画日期/时间分隔线。 */
    val timelineSeparators: Set<String>,
    /** 引用气泡按 id 找源消息。 */
    val messagesById: Map<String, MessageUi>,
    /** 原序下标（倒序渲染时用来找前一条/后一条）。 */
    val messagePositions: Map<String, Int>,
    /** 按 cardId 折叠后只画最新 rev 的那些行。 */
    val visibleCardIds: Set<String>,
)

internal fun chatListLayoutOf(messages: List<MessageUi>, dayKey: (Long) -> String): ChatListLayout {
    val byId = LinkedHashMap<String, MessageUi>(messages.size)
    val positions = HashMap<String, Int>(messages.size)
    for ((index, message) in messages.withIndex()) {
        // 与旧 `associateBy` / `withIndex().associate` 同语义：重复 id 保留最后一条。
        byId[message.id] = message
        positions[message.id] = index
    }
    return ChatListLayout(
        sessionTransitions = sessionTransitionIds(messages),
        timelineSeparators = timelineSeparatorIds(messages, dayKey),
        messagesById = byId,
        messagePositions = positions,
        visibleCardIds = latestRevCardMessageIds(messages),
    )
}
