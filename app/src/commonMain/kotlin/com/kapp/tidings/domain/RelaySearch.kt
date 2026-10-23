// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.PeerUi
/**
 * 检索的**两套**东西，刻意分开而不是一套参数化。
 *
 * 原型 `prototype/relay.html:4454` 的注释把这件事写死了：
 * 「这一层跟全局搜索是两套代码。全局走 FTS5 全库（SearchService.query），
 * 这里是 `searchMessages(convId, kw)` 在当前会话里 LIKE 匹配，只取 50 条。
 * 所以在会话内搜得到不等于全局搜得到」。
 *
 * 落到这一层就是：会话内检索**先圈定会话**，全局检索**找遍所有会话**。
 * 两者的返回形状也不同 —— 全局的「聊天记录」段要的是"哪些会话命中了几条"（[ConversationSearchGroup]），
 * 会话内要的是"命中了哪几条"（[MessageSearchResult]）。
 *
 * 合并成一个 `search(convId: String?, ...)` 会得到一个返回类型随入参变化的函数，
 * 调用方每次都得先判空才知道拿到的是什么。
 */

/**
 * 会话内检索的**类型**筛选，对应原型那三个 chip（全部 / 图片 / 文件）。
 *
 * 只有三项，因为原型只画了三项（`screenHistSearch` 的 `.hskinds`）。
 * 没有「语音」「视频」不是因为它们不存在，而是原型没有这两个 chip —— 加一项
 * 就要多一个 chip、多一条筛选分支，而那条分支没有任何屏在验。
 */
enum class HistorySearchKind {
    All,
    Image,
    File,
    ;

    /**
     * 这条**附件**算不算这一档。
     *
     * 判据与气泡里的呈现同源（[mediaKindOf]），不另起一套 —— 一条在气泡里画成缩略图的图，
     * 在「图片」这一档里必须也找得到，否则同一个文件在两个地方有两种身份。
     */
    internal fun matches(file: FileCardUi?): Boolean = when (this) {
        All -> true
        Image -> file != null && mediaKindOf(file) == MediaKind.Image
        // 「文件」= 有附件、但不是图片。图片有自己那一档，两边都算会让一张图
        // 在「图片」和「文件」两个筛选下同时出现 —— 用户会以为筛错了。
        File -> file != null && mediaKindOf(file) != MediaKind.Image
    }
}

/**
 * 会话内检索的**条件**。
 *
 * 日期是闭区间 `[fromAt, toAt]`，且两端都可以缺省：
 * 原型那个月历「起止两点是点出来的」（`calHTML` 上方注释），所以正常操作下两端同时有值；
 * 但「清除」只清日期（`:734-748`），于是"只有关键词、没有日期"是常态。
 * 用两个可空 Long 而不是一个 `ClosedRange`，就是为了让"没筛日期"和"筛了某一天"
 * 在类型上分得开 —— `0L..Long.MAX_VALUE` 那种哨兵值会让"全部时间"和"从 1970 年开始"变成同一件事。
 */
data class HistorySearchFilter(
    val kind: HistorySearchKind = HistorySearchKind.All,
    val fromAt: Long? = null,
    val toAt: Long? = null,
) {
    /** 没有任何条件：只按关键词搜。 */
    val isEmpty: Boolean get() = kind == HistorySearchKind.All && fromAt == null && toAt == null

    fun accepts(at: Long): Boolean {
        if (fromAt != null && at < fromAt) return false
        if (toAt != null && at > toAt) return false
        return true
    }

    companion object {
        val None = HistorySearchFilter()
    }
}

/**
 * 全局检索「聊天记录」段的**按会话聚合**行。
 *
 * 为什么是聚合而不是一长串命中：原型那一段每行是「与 老周 的聊天 · 7 条相关」，
 * 一个会话一行。若改成把每条命中都列出来，一个聊了几百条的会话会淹掉另外两个分组，
 * 而用户在这一层的目的是"找到那个会话"，不是读完所有命中。
 *
 * [matchCount] 必须来自**全部**命中，不是被 LIMIT 截断后的那一段 ——
 * 所以它在 SQL 里 `COUNT(*)`（见 `RelayDatabase.sq` 的 `searchMessagesGrouped`），
 * 而不是取回 50 条再在 Kotlin 里数。后者会让一个几百条的会话永远显示成「50 条相关」，
 * 而 50 是 LIMIT 的值、不是命中的值。
 */
data class ConversationSearchGroup(
    val convId: String,
    val matchCount: Int,
    /** 最新一条命中的时间，段落按它倒序。 */
    val latestAt: Long,
    /**
     * 最新那条命中的正文。
     *
     * 空串是**正常值**：文件消息的 `text` 本来就是空串（见 `upsertTransferCard`），
     * 一个只命中文件消息的会话，这里就是空的。界面要退回文件名而不是画一行空白 ——
     * 所以这个字段不能设成 `String?` 再让调用方猜"是没取到还是本来就没有"。
     */
    val latestText: String = "",
)

/**
 * 全局检索的完整结果：**一页里的三块**，不是三页。
 *
 * 原型 `:3491` 的注释：「三段不是三页，是一页里的三块。SearchPanel 把联系人（≤20）、
 * 聊天记录（按最新命中倒序，≤10 组）、文件（≤20）三块纵向拼起来，分组标题只有
 * 「联系人 / 聊天记录 / 文件」三个词，没有「讨论组」、没有数量徽标。也没有筛选器」。
 *
 * 上限写在这里而不是让调用方传：三个 20/10/20 是原型的数字，
 * 做成参数只会多三个没人会传别的值的形参。
 */
/**
 * 全局检索要搜**哪几段**。
 *
 * 全局搜索原来只能做一件事：输关键词、三块一起搜。想找「上周某人发的那个文件」时，
 * 用户只能靠关键词碰运气 —— 而会话内检索早就有类型筛选（`HistorySearchKFilter`），
 * 这里是把同一份能力补齐到全局。
 *
 * **按段筛而不是按文件类型筛**：全局结果本来就是「联系人 / 聊天记录 / 文件」三段纵向拼的，
 * 用户想「只看文件」时指的是**那一段**，不是「所有段里 mediaType 是文件的行」。
 * 按段筛也更省：不要的段直接不查，不用先查出来再丢掉。
 */
enum class GlobalSearchScope {
    All,
    Contacts,
    Chats,
    Files,
}

/**
 * 这一次检索要不要包含 [section] 那一段。
 *
 * 抽成纯函数：三个段（联系人 / 聊天 / 文件）必须用**同一条**判据。
 * 各写一个 `scope == All || scope == X` 就是三处可以各自漂的真相来源 ——
 * 漏改一处，用户点「只看文件」却仍看到联系人，而且没有任何报错。
 */
internal fun GlobalSearchScope.includes(section: GlobalSearchScope): Boolean =
    this == GlobalSearchScope.All || this == section

data class GlobalSearchResult(
    val query: String,
    val contacts: List<GlobalSearchContactHit> = emptyList(),
    val chats: List<GlobalSearchChatHit> = emptyList(),
    val files: List<GlobalSearchFileHit> = emptyList(),
) {
    /**
     * 三块都空。
     *
     * 界面据此显示「没有找到「{0}」相关的内容」（原型 `d-search-empty` 的原文，
     * 后面还有半句「联系人、聊天记录、文件三个分组都空了」）。**空关键词不算空结果** ——
     * 那是"还没开始搜"，界面上是初始态不是无结果态，两者文案不同。
     */
    val isEmpty: Boolean get() = contacts.isEmpty() && chats.isEmpty() && files.isEmpty()

    companion object {
        const val CONTACT_LIMIT = 20
        const val CHAT_LIMIT = 10
        const val FILE_LIMIT = 20
    }
}

/** 全局检索「联系人」段的一行。 */
data class GlobalSearchContactHit(
    val nodeId: String,
    /** 显示名（昵称 → host → ip → id 前缀，与 [PeerUi.displayName] 同源）。 */
    val title: String,
    /** 副标题：公司 · 部门 · 团队，为空时退回 ip/host。 */
    val subtitle: String,
    val isAgent: Boolean,
    val online: Boolean,
)

/** 全局检索「聊天记录」段的一行。 */
data class GlobalSearchChatHit(
    val convId: String,
    /** 会话名（群名 → 对端昵称 → id 前缀，与 `RelayUiState.convTitle` 同源）。 */
    val title: String,
    val matchCount: Int,
    val latestAt: Long,
    /**
     * 最新那条命中的正文摘要，一行。
     *
     * 只给**一条**（最新的那条），因为原型那行的 `.t2` 就是一句话。
     * 取的是原文而不是 `snippet()` 那种带 `【】` 的片段：全局这一段没有高亮
     * （原型 `:3491` 明写「命中不会高亮：代码里没有 `<mark>`」），
     * 而 `【】` 是高亮的替代品，在没有高亮的列表里它只会显得像正文的一部分。
     */
    val snippet: String,
)

/** 全局检索「文件」段的一行。 */
data class GlobalSearchFileHit(
    val messageId: String,
    val convId: String,
    /** 文件所在的会话名，界面上是「来自与 Lin 的聊天」。 */
    val convTitle: String,
    /** 文件展示名（`FileCardUi.name`）。 */
    val name: String,
    val at: Long,
    /** 是不是图片 —— 决定那一行画文件图标还是图片图标。 */
    val isImage: Boolean,
)

/**
 * 全局检索「联系人」段的匹配规则，抽成纯函数。
 *
 * 抽出来是为了**能单独测**：它是三段里唯一一段不碰存储、只碰内存里那份 peer 表的规则，
 * 而它恰好有一条容易被"顺手加一项"破坏的约束 —— **不含主机名**。
 * 挂在 `RelayController.searchGlobal` 里的话，要验这一条就得起一个真控制器
 * 并把 peer 塞进 `_state`，而 `_state` 只由节点发现回填 —— 测试会变成
 * "先起一个假节点"，那测的就不是这条规则了。
 *
 * **为什么不搜 `host`**：主机名是本机给对端起的外号（`PeerUi.host` 来自 mDNS 服务名），
 * 两台机器可能各叫各的，用它检索会让同一个人在两台设备上搜出不同结果。
 * IP 反而是双方共见的。这条是协议要求的检索口径。
 *
 * **备注要搜**（补上）：`PeerUi.remark` 是本机给人起的名，它**只在本地存在**
 * （不进协议），所以拿它检索不会造成"两台设备搜出不同结果"那个问题 —— 恰恰相反，
 * 不搜它才是问题：用户刚给「mac mini」起了备注叫「机房那台」，回头搜「机房」搜不到，
 * 那条备注就白写了。茶话间同款：`utils/group-member-picker.ts:45` 把 `peer.remark`
 * 排在搜索字段的第一位。原注释说「这是缺字段不是漏实现」，那个字段现在有了。
 */
internal fun matchContacts(peers: List<PeerUi>, query: String): List<GlobalSearchContactHit> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return peers
        .asSequence()
        .filter { peer ->
            peer.remark.contains(q, ignoreCase = true) ||
                peer.nick.contains(q, ignoreCase = true) ||
                peer.company.contains(q, ignoreCase = true) ||
                peer.dept.contains(q, ignoreCase = true) ||
                peer.team.contains(q, ignoreCase = true) ||
                peer.ip.contains(q, ignoreCase = true)
        }
        .take(GlobalSearchResult.CONTACT_LIMIT)
        .map { peer ->
            GlobalSearchContactHit(
                nodeId = peer.nodeId,
                title = peer.displayName,
                subtitle = peer.subtitle,
                isAgent = peer.isAgent,
                online = peer.online,
            )
        }
        .toList()
}
