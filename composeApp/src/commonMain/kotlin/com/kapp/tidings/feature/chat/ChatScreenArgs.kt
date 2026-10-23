// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.domain.HistorySearchFilter
import com.kapp.tidings.domain.MessageSearchResult
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.platform.ReceivedFileActions
import com.kapp.tidings.ui.ContentWidths
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kapp.tidings.ui.components.MentionTarget

/**
 * [ChatScreen] 的四个参数组。
 *
 * 为什么分组：原签名 35 个参数，其中 27 个是 `() -> Unit` 族回调。它们不是同一类东西 ——
 * 有的随「这个屏画什么」变（外壳），有的随「协议层能做什么」变（动作），混在一起时
 * 调用方只能靠数位置传参，加一个回调就要给全部 37 个调用点补默认值。
 *
 * 分组轴是**变化原因**，不是类型：
 *
 * - [ChatScreenState] —— 数据。它的变化跟着「用户在哪个会话」。
 * - [ChatScreenActions] —— 协议动作。跟着「这个壳能不能把消息发出去」。
 * - [ChatScreenGroupActions] —— 群管理。跟着「这个会话是不是群」。
 * - [ChatScreenChrome] —— 外壳与一次性初值。跟着「手机还是桌面、要不要画头部」。
 *
 * 四个 holder 都有默认值 ⇒ 调用方只写自己关心的那几项。
 */

/**
 * 单聊消息流要用的数据。
 *
 * `now` 与 `cacheDir` 也归这里：两者都是「这个壳在什么条件下渲染」，不是用户动作。
 */
data class ChatScreenState(
    val state: RelayUiState,
    val peerId: String?,
    /**
     * 用户是从哪个**工作区**进来的；null = 直接在跟这个节点聊天（不按工作区收窄）。
     *
     * ## 为什么聊天页需要它
     *
     * 区分节点全局会话与特定工作区下的会话，确保顶部及筛选仅展示归属该工作区的内容。
     */
    val wsId: String? = null,
    /**
     * 用户当前选中的会话 id（导航状态里的 `activeAgentSessionId`）；null = 还没选。
     *
     * ## 为什么它必须传进来
     *
     * 明确指定当前处于激活高亮状态的会话，避免仅以列表首项作为激活项导致状态失准。
     */
    val activeSessionId: String? = null,
    /**
     * 进入这个会话**那一刻**，它还有多少条没看过（真机反馈 #5 的屏外未读指示）。
     *
     * ## 为什么必须由壳在「清未读之前」采下来
     *
     * 这个数的真相来源是 `RelayUiState.unreadByPeer`，而壳在进入会话时会调
     * `controller.selectConversation(convId)`，它**同步**把 `core.unread[convId]` 清零并
     * publish —— ChatScreen 首次组合时那个数已经是 0，从这一层读不到「进入前」的值。
     * 所以采集只能发生在壳里（App.kt 的 openChat / DesktopShell 的 onSelect），
     * 这里是消费侧。
     *
     * 0 = 没有未读**或**壳没采到。两者都不编数字：指示条在拿不到数字时只显示「回到最新」，
     * 不会写出一个假条数。
     */
    val unreadOnEntry: Int = 0,
    /** 默认真实时钟；截图夹具传固定值（见 [ChatScreen] 的 `now` 说明）。 */
    val now: Long = nowMillis(),
    val cacheDir: String? = null,
)

/**
 * 协议层动作。
 *
 * 全部可空，空值一律 = **不画对应的入口**（见 [ChatScreen] 的
 * `onAttachFile` / `onCancelTransfer` / `receivedFileActions` 说明）：
 * 一个按下去没反应的按钮比没有更糟。
 */
data class ChatScreenActions(
    val onSend: (targetId: String, text: String, replyTo: String?) -> Unit,
    /**
     * 失败气泡上的「重发」。
     *
     * 与 [onSend] 分开是必须的：`onSend(targetId, 原文本, null)` 会生成**新信封 id**，
     * 而对端按 id 去重 —— 换 id 重发就是「群里多了一条」（真机现场）。null = 这个壳
     * 没有按原 id 重放的能力，那就退回旧行为（红字仍可点，但会新发一条）。
     */
    /**
     * 「申请授权」——参数是**被 @ 的 agent 名**（notice 里带的那一个）。
     *
     * 从「那一行提示」直达申请，而不是让用户去某个页面自己找：
     * 用户读到「没有执行」的地方，就是他想授权的那个时刻。
     */
    val onRequestAgentGrant: ((agentName: String) -> Unit)? = null,

    val onRetryMessage: ((MessageUi) -> Unit)? = null,
    /**
     * 向上翻页：把更早的一页读进内存。null = 这个壳不做分页（预览/截图夹具）。
     *
     * 为什么由壳传进来而不是 ChatScreen 自己调控制器：聊天屏是**共享内容层**，
     * 它不认识 RelayController（手机与桌面各自装配）。读库是宿主的动作，
     * 所以走这条已有的 actions 接缝。
     */
    val onLoadOlder: (() -> Unit)? = null,
    val onOpenAgentConsole: ((String) -> Unit)? = null,
    val onDismissBanner: (() -> Unit)? = null,
    /** null = 输入栏不画附件图标。 */
    val onAttachFile: ((String) -> Unit)? = null,
    val onAcceptTransfer: ((String) -> Unit)? = null,
    val onDeclineTransfer: ((String) -> Unit)? = null,
    /** null = 文件卡片不画取消按钮。 */
    val onCancelTransfer: ((String) -> Unit)? = null,
    /** null = 失败的文件卡片不画续传按钮。 */
    val onResumeTransfer: ((String) -> Unit)? = null,
    /** null = 文件卡片不画「打开 / 分享」。 */
    val onRecallMessage: ((MessageUi) -> Unit)? = null,
    /**
     * **只删本机**这一条：(会话键, 消息 id) → 删除。
     *
     * 与 [onRecallMessage] 是两件事：撤回有时限、**会告诉对方**；
     * 删除是纯本机行为 ——「我自己不想再看到它」。null = 这个壳不提供删除入口。
     */
    val onDeleteMessage: ((convId: String, messageId: String) -> Unit)? = null,
    /** null = 长按菜单不画「转发」。 */
    val onForwardMessage: ((message: MessageUi, targetConvId: String) -> Unit)? = null,
    /** null = 头部与输入栏都不画戳一碰。 */
    val onNudge: ((String) -> Unit)? = null,
    /** null = 头部不画「请求查看屏幕」。 */
    val onRequestScreenView: ((String) -> Unit)? = null,
    /** null = 输入栏不画 PK 入口。 */
    val onSendPk: ((targetId: String, game: String, value: String) -> Unit)? = null,
    /** null = 输入栏不画录音入口。 */
    val onSendVoice: ((peerId: String, filePath: String, durationMs: Long) -> Unit)? = null,
    /** null = 会话不画待审批条。 */
    val onRespondApproval: ((approvalId: String, allow: Boolean) -> Unit)? = null,
    /** 会话内历史搜索；null = 输入栏右端不画那颗搜索。 */
    /** 会话内历史搜索；null = 输入栏右端不画那颗搜索。 */
    val onSearchInConversation: ((query: String, filter: HistorySearchFilter) -> List<MessageSearchResult>)? = null,
    /**
     * 打开与某个 agent 的会话时，向宿主要该工作区**第一页**历史会话
     * （PPE-1 增补 D8）。null = 不发（预览、非 agent 对端）。
     *
     * 为什么在"打开"时触发而不是启动时全量推：用户的原话 ——「正常其实用户只有在需要
     * 打开这个工作区的时候，同步下会话就好吧」。历史会话可能上百条，启动时全推既慢又
     * 没必要；打开时才要，代价恰好付在想看的东西上。
     */
    val onOpenAgentHistory: ((peerId: String, wsId: String) -> Unit)? = null,
    /**
     * 「加载更多」：再要下一页（offset = 本地已知条数）。null = 选择器里不画那一行。
     *
     * 与 [onOpenAgentHistory] 分开而不是"同一个带 offset 的回调"：这两个动作的**门控不同**
     * （打开时只要有 agp1 就发；加载更多还要"确实还有下一页"），合成一个就要在调用点
     * 传一个自己算的 offset，而那个数只能在界面层估 —— 分离之后界面只问
     * [hasMoreSessions] 一次。
     */
    val onLoadMoreAgentSessions: ((peerId: String, wsId: String) -> Unit)? = null,
    /**
     * 「切换会话」浮层里选中了某一条会话。
     *
     * ## 为什么必须是一个独立回调
     *
     * 接收选中的 sessionId 并推进导航与激活会话状态，避免点击仅关闭浮层而不生效。
     * `onPick(session.sessionId)`，但界面上**点任何一行都只关浮层、不换会话**，
     * 于是「切换会话」这四个字名不副实。
     *
     * null = 不画这个浮层（预览、非 agent 对端不走这条路）。
     */
    val onSelectAgentSession: ((sessionId: String) -> Unit)? = null,
    /**
     * 「清空本会话聊天记录」—— 只清**本机**这个会话的历史，对方不受影响。
     *
     * ## 为什么是无参回调
     *
     * 会话键由**壳**掌握（手机是 `nav.activeSessionId`，桌面是 `selectedPeerId`）——
     * 与"当前在看哪个会话"是同一个真相来源。在这一层再算一次（比如用 `peer.nodeId`）
     * 就等于给同一个问题留了第二个答案，将来群会话键或占位对端的形状一变就会清错会话。
     * 所以这里只表达"用户确认要清"，清哪一个由接线的那一层闭包决定。
     *
     * 当前实现走既有的 [com.kapp.tidings.domain.RelayController.clearMessages]（不是设置页那条
     * `clearAllMessages`）。null = 头部不画这个入口（预览 / 没有这条能力的壳）。
     */
    val onClearMessages: (() -> Unit)? = null,
)

/**
 * 群管理动作。五个回调都以 `groupId` 为首参 —— 那是消息流的职责边界：
 * 它只知道「这是哪个群」，不知道群成员表怎么改。
 */
data class ChatScreenGroupActions(
    val onAddGroupMembers: ((groupId: String, memberIds: List<String>) -> Unit)? = null,
    val onRemoveGroupMember: ((groupId: String, memberId: String) -> Unit)? = null,
    /**
     * 把**本机的一个 workspace** 暴露/收起进群（`virtualAgents` 的成员入口）。
     *
     * `wsId` 为 null 时表示收起。与 `onAddGroupMembers` 是两件事：那个加**人**，
     * 这个暴露**本机项目**（暴露单位是 `(agentId, wsId)`）。
     */
    val onExposeWorkspaces: ((groupId: String, wsId: String?, agentId: String?) -> Unit)? = null,
    val onUpdateGroupMeta: ((groupId: String, description: String?, announce: String?) -> Unit)? = null,
    val onLeaveGroup: ((groupId: String) -> Unit)? = null,
)

/**
 * 外壳与一次性初值。
 *
 * `initial*` 四个参数是**截图接缝**：让那些「只在用户点了什么之后才出现」的分支
 * 在首帧就开着，否则截图门禁对它们完全变瞎。默认值全部等于生产行为（关的）。
 */
/**
 * 按会话存草稿的那张表 —— 手机壳与桌面壳**共用这一份**（AGENTS.md §2.6：
 * 同一个事实只许有一处实现，两端各写一份必然漂）。
 *
 * ## 为什么需要它
 *
 * `ChatScreen` 自己的 `draft` 是 `remember(peerId)` 的：**切会话就重建**。
 * 用户写到一半去另一个会话确认一件事，回来字数全没了 —— 那是丢用户的输入，
 * 性质与丢消息接近，只是没那么显眼。
 *
 * ## 语义
 *
 * - **空串 = 删除这一条**：发送后 `onDraftChange(convId, "")`，表里不该留一个空草稿
 *   （留着会让"切回来是空白"与"没草稿"无法区分，且表会越攒越大）。
 * - **只活在进程内，不落盘**：产品语义是"切页面不丢"，不是"重启还留着"。
 */
internal fun updateDraft(drafts: Map<String, String>, convId: String, text: String): Map<String, String> =
    if (text.isEmpty()) drafts - convId else drafts + (convId to text)

/** 取某个会话的草稿；没有就是空串（绝不返回 null —— 调用点直接喂给输入框）。 */
internal fun draftOf(drafts: Map<String, String>, convId: String?): String =
    convId?.let { drafts[it] }.orEmpty()

data class ChatScreenChrome(
    val receivedFileActions: ReceivedFileActions? = null,
    val onBack: (() -> Unit)? = null,
    val onFindPeers: (() -> Unit)? = null,
    val modifier: Modifier = Modifier,
    val showHeader: Boolean = true,
    val bubbleFraction: Float = ContentWidths.BUBBLE_FRACTION_PHONE,
    /**
     * 外壳自带的输入卡片（桌面壳用它）。
     *
     * 第三个参数是 `@` 候选：**必须由这一层传下去**，否则桌面输入框没有候选列表 ——
     * 真机反馈「桌面端还是无法输入 @」就是这条：`DesktopComposer` 从来没接过候选。
     */
    val composerSlot: (@Composable (String, (String) -> Unit, List<MentionTarget>) -> Unit)? = null,
    /**
     * 草稿的**外部存放处**：壳可以接住，把草稿按会话记下来，切换会话时再喂回 [initialDraft]。
     *
     * 为什么需要：草稿原来是 `remember(peerId)` —— **切走就重建**，回到原会话草稿已空。
     * 用户写到一半去另一个会话确认一件事，回来字数全没了。那是**丢用户的输入**，
     * 性质和丢消息接近，只是没那么显眼。
     *
     * 为什么做成可空槽而不是让 ChatScreen 自己持久化：草稿的寿命该跟着**壳**走
     * （桌面窗口常驻 / 手机进程内），而且不同壳的存放位置不同（见桌面 `DesktopChatPane`）。
     * 传 null = 这个壳不关心草稿保留，行为与改动前一致。
     *
     * @param convId 会话键（私聊 = 对端 nodeId、群 = `group:<id>`）。
     * @param text 当前草稿全文；空串表示草稿被清掉（发送后）。
     */
    val onDraftChange: ((convId: String, text: String) -> Unit)? = null,
    val initialDraft: String = "",
    val initialEmojiOpen: Boolean = false,
    val initialHistorySearch: Boolean = false,
    val initialScreenRequestConfirmOpen: Boolean = false,
)
