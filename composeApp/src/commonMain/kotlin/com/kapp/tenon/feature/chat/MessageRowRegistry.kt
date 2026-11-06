// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.domain.model.ChatRowKind
import com.kapp.tenon.domain.model.ChatRowKinds
import com.kapp.tenon.domain.model.MessageUi

/**
 * 行级判定注册表（轮 16 / WP3）。
 *
 * 在它之前，`ChatMessageList` 的六支 if/else 既是类型表又是优先级表：
 * 系统行 > 屏幕历史卡 > 声明式卡 > 执行状态横幅 > 普通气泡，而卡片自己还有
 * 「画 / 旧 rev 收起 / 退成文本」三态。新增一种消息要往链子里插一支，漏插不会
 * 报编译错，只是静默落进气泡那一支。
 *
 * 这里只做**判定**，不画任何东西：渲染块仍留在 `ChatMessageList` 的作用域里，
 * 用 composable lambda 捕获现有变量即可，不必再造一个 30 参数的值对象。
 * 因此本文件不依赖 Compose —— 词条（如退档兜底文案）由调用侧取好后注入。
 */
sealed interface RowDecision {

    /** 按 [kind] 找渲染块；[card] 只有声明式卡那一行会带。 */
    data class Render(val kind: ChatRowKind, val card: AgentCardUi? = null) : RowDecision

    /** 旧 rev 原位收起：这一行刻意什么都不画，加占位都会把「收起」变成「换个方式显示」。 */
    data object Hidden : RowDecision

    /**
     * 委托给另一个渲染块，并允许改写正文。
     *
     * 卡片负载读不出来时走这里：退档文案或 `card_unreadable` 先写回 message，
     * 再交给 Bubble 渲染 —— **不是**再画一份气泡。普通气泡也走这里，两条路合一条。
     */
    data class Delegate(val kind: ChatRowKind, val message: MessageUi) : RowDecision
}

/** 判定所需的最小上下文。[unreadableText] 由 Compose 侧取词条后传入。 */
data class RowResolutionContext(
    val message: MessageUi,
    val visibleCardIds: Set<String>,
    val unreadableText: String,
)

/** 一种行身份的判据。返回 null = 这条消息不属于我，交给下一个适配器。 */
interface MessageRowAdapter {
    val kind: ChatRowKind
    fun decide(context: RowResolutionContext): RowDecision?
}

private class SimpleRowAdapter(override val kind: ChatRowKind) : MessageRowAdapter {
    override fun decide(context: RowResolutionContext): RowDecision? =
        if (ChatRowKinds.of(context.message) == kind) RowDecision.Render(kind) else null
}

private class CardRowAdapter : MessageRowAdapter {
    override val kind: ChatRowKind = ChatRowKind.AgentCard

    override fun decide(context: RowResolutionContext): RowDecision? {
        if (ChatRowKinds.of(context.message) != kind) return null
        // 三态判据不在这里重算，仍问 cardRowOutcome —— 新增卡片行为只有一处要改。
        return when (val outcome = cardRowOutcome(context.message, context.visibleCardIds)) {
            is CardRowOutcome.Render -> RowDecision.Render(kind, outcome.card)
            CardRowOutcome.Hidden -> RowDecision.Hidden
            is CardRowOutcome.FallbackToText -> RowDecision.Delegate(
                kind = ChatRowKind.Bubble,
                message = context.message.copy(
                    text = outcome.text.ifBlank { context.unreadableText },
                ),
            )
        }
    }
}

private class BubbleRowAdapter : MessageRowAdapter {
    override val kind: ChatRowKind = ChatRowKind.Bubble

    override fun decide(context: RowResolutionContext): RowDecision? =
        if (ChatRowKinds.of(context.message) == kind) {
            RowDecision.Delegate(kind, context.message)
        } else {
            null
        }
}

object MessageRowRegistry {

    /**
     * 顺序**只**由 [ChatRowKinds.ordered] 派生；[adapterFor] 的 when 是穷尽的，
     * 新增枚举值不补分支会直接编译红，不会静默少一行。
     */
    val adapters: List<MessageRowAdapter> = ChatRowKinds.ordered.map(::adapterFor)

    private fun adapterFor(kind: ChatRowKind): MessageRowAdapter = when (kind) {
        ChatRowKind.Notice -> SimpleRowAdapter(ChatRowKind.Notice)
        ChatRowKind.Screen -> SimpleRowAdapter(ChatRowKind.Screen)
        ChatRowKind.AgentCard -> CardRowAdapter()
        ChatRowKind.AgentState -> SimpleRowAdapter(ChatRowKind.AgentState)
        ChatRowKind.Bubble -> BubbleRowAdapter()
    }

    fun resolve(context: RowResolutionContext): RowDecision =
        adapters.firstNotNullOfOrNull { adapter -> adapter.decide(context) }
            ?: RowDecision.Delegate(ChatRowKind.Bubble, context.message)
}

/** 列表层的唯一入口。 */
fun resolveRow(
    message: MessageUi,
    visibleCardIds: Set<String>,
    unreadableText: String,
): RowDecision = MessageRowRegistry.resolve(
    RowResolutionContext(message, visibleCardIds, unreadableText),
)

/** Hidden 没有渲染块；其余两种都按 kind 取。 */
fun RowDecision.renderKind(): ChatRowKind? = when (this) {
    is RowDecision.Render -> kind
    is RowDecision.Delegate -> kind
    RowDecision.Hidden -> null
}
