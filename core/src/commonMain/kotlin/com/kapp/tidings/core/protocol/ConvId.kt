// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

/**
 * 会话键（点对点协议 `conv_id` 规范）。
 *
 * 两种会话，**两种键**：
 * - 私聊：键就是对方 nodeId，**不加前缀**；
 * - 群聊：键是 `group:<groupId>`。
 *
 * ### 为什么私聊不加 `single:` 前缀
 * 部分实现两种都加前缀（`single:<nodeId>`），这里刻意不跟。本仓的 `messages.json`、
 * `threads`、`unreadByPeer`、`pendingByPeer`、`MessageStore` 的键**从第一版起就是裸
 * nodeId**，加前缀等于给一份已经落盘的格式做迁移，而迁移的收益只是"好看"。群聊是新东西，
 * 用新前缀不会和任何既有键冲突 —— 判据是 [isGroup]，不是"有没有前缀"。
 *
 * 这也意味着**任何键都不能靠 `split(':')` 取字段**：群 id 由对端给，可以自己含冒号。
 * 所以 [groupIdOf] 用的是 `removePrefix` 而不是切分。
 */
object ConvId {
    const val GROUP_PREFIX: String = "group:"

    /** 私聊会话键。存在的意义是让调用方不必记"私聊不加前缀"这条约定。 */
    fun single(peerId: String): String = peerId

    fun group(groupId: String): String = GROUP_PREFIX + groupId

    fun isGroup(convId: String): Boolean = convId.startsWith(GROUP_PREFIX)

    /** 群会话键对应的 groupId；私聊键返回 null。 */
    fun groupIdOf(convId: String): String? =
        if (isGroup(convId)) convId.removePrefix(GROUP_PREFIX) else null
}
