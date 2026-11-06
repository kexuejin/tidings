// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.domain.model

import com.kapp.tenon.domain.isQuickActionReceipt

/**
 * 一条消息在聊天流里**画成哪一行**（轮 15 / 消息行注册表 WP1）。
 *
 * 在它之前，类型散在 [MessageUi] 的一堆可空字段里：「notice 非空是系统行」
 * 「screen 非空是历史卡」「renderCard 非空是声明式卡」…… 每加一种消息，
 * 撤回、引用预览、回复条、连续发件编组都各写一遍裸字段判据。两把尺子分叉时
 * 不报编译错，只是静默少一个功能。
 *
 * 这一层只认**行级身份**。气泡内部画纯图、文件卡、PK 还是思考块，由
 * [BubbleContentKind] 描述 —— 那是同一行里的第二种选择，不是第二种行。
 *
 * 与协议 `MsgKinds` 分开：`MsgKinds.ALL` 只有信封 kind（text/group-text/recall/
 * nudge/pk），而 card / screen / agentState 根本不走信封 kind。硬把两者合成一套
 * 会让「协议不认识的类型」在界面上无处安放。
 */
enum class ChatRowKind {
    Notice,
    Screen,
    AgentCard,
    AgentState,
    Bubble,
}

/** 气泡内部的载荷种类；顺序与 [ChatRowKinds.bubbleContentOf] 的判据一致。 */
enum class BubbleContentKind { File, Pk, Thinking, Text }

/** 撤回该报哪个 id。媒体消息报协议 msgId，其余报本地消息 id。 */
enum class RecallTarget { Text, Media }

/** 正文为空时，列表/回复条拿什么兜底。当前所有行都用文件名，声明出来供新类型改写。 */
enum class PreviewFallback { None, FileName }

/**
 * 一行的**非渲染**能力。放在 domain 是因为撤回与预览由非 Compose 的调用点消费，
 * 而领域层不许依赖 Compose（`:domainComposeFreeGate`）。
 */
data class RowCapabilities(
    /** 是否参与「连续发件」圆角编组。系统行与历史卡不参与。 */
    val groupsWithSender: Boolean,
    /** 压根不该有撤回入口（系统提示行）。时间窗口是另一回事，见 `canRecall`。 */
    val recallBlocked: Boolean,
    val previewFallback: PreviewFallback,
)

object ChatRowKinds {

    /**
     * 显式优先级，等价于改造前 `ChatMessageList` 那串 if/else 的书写顺序：
     * 系统行 > 屏幕协助历史卡 > 声明式卡 > 执行状态横幅 > 普通气泡。
     *
     * 一行只允许一种身份：同一帧既带卡又带状态时卡是主体，横幅不重复画。
     * 新增 kind 必须同时出现在这里与 [capabilities]，守卫会数。
     */
    val ordered: List<ChatRowKind> = listOf(
        ChatRowKind.Notice,
        ChatRowKind.Screen,
        ChatRowKind.AgentCard,
        ChatRowKind.AgentState,
        ChatRowKind.Bubble,
    )

    /** 唯一的行级判别式。卡片的三态（画 / 旧 rev 收起 / 退成文本）不在这一层判，
     * 它需要整表的可见 rev 集合，属于渲染前的布局派生。 */
    fun of(message: MessageUi): ChatRowKind = when {
        message.notice != null -> ChatRowKind.Notice
        message.screen != null -> ChatRowKind.Screen
        message.renderCard != null -> ChatRowKind.AgentCard
        message.agentState != null -> ChatRowKind.AgentState
        isQuickActionReceipt(message.text) -> ChatRowKind.Notice
        else -> ChatRowKind.Bubble
    }

    /** 气泡里画哪一块。与 `MessageBubble` 的空气泡防线、撤回目标同源。 */
    fun bubbleContentOf(message: MessageUi): BubbleContentKind = when {
        message.file != null -> BubbleContentKind.File
        message.pk != null -> BubbleContentKind.Pk
        !message.reasoning.isNullOrBlank() -> BubbleContentKind.Thinking
        else -> BubbleContentKind.Text
    }

    fun capabilities(kind: ChatRowKind): RowCapabilities = when (kind) {
        ChatRowKind.Notice -> RowCapabilities(
            groupsWithSender = false,
            recallBlocked = true,
            previewFallback = PreviewFallback.FileName,
        )
        ChatRowKind.Screen -> RowCapabilities(
            groupsWithSender = false,
            recallBlocked = false,
            previewFallback = PreviewFallback.FileName,
        )
        ChatRowKind.AgentCard,
        ChatRowKind.AgentState,
        ChatRowKind.Bubble -> RowCapabilities(
            groupsWithSender = true,
            recallBlocked = false,
            previewFallback = PreviewFallback.FileName,
        )
    }

    fun recallTargetOf(message: MessageUi): RecallTarget =
        if (bubbleContentOf(message) == BubbleContentKind.File) RecallTarget.Media else RecallTarget.Text

    /**
     * 回复条 / 引用条的一行摘要。手机与桌面 Composer 此前各抄了一份
     * `text.ifBlank { file?.name }`，现在只留这一份，兜底策略问能力表。
     * 与落库那份 `conversationPreviewOf` 不是同一件事：后者要清洗 `<think>` 包装
     * 且输入只有 DB 行（text + file_json），拿不到完整消息。
     */
    fun previewTextOf(message: MessageUi): String = message.text.ifBlank {
        if (capabilities(of(message)).previewFallback == PreviewFallback.FileName) {
            message.file?.name.orEmpty()
        } else {
            ""
        }
    }
}
