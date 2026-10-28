// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.chat

import com.kapp.tidings.domain.HistorySearchFilter
import com.kapp.tidings.domain.MessageSearchResult
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.RelayUiState
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.core.transport.LocalFile
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
    val initialStagedFiles: List<LocalFile> = emptyList(),
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
    /** 附件多选选取函数；为 null 时降级到 [onAttachFile]。 */
    val onPickFiles: (suspend () -> List<LocalFile>)? = null,
    /** 暂存附件同发函数；(peerId, files) -> Unit。 */
    val onSendFiles: ((peerId: String, files: List<LocalFile>) -> Unit)? = null,
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
    /**
     * 三参处置（「本会话免审」）：与看板/三屏同一个回调。
     *
     * 为什么两参版本还留着：聊天横幅在**旧调用点**（其它壳、预览）只认批准/拒绝。
     * 只接了 [onRespondApproval] 时，L3 确认与摘要照常工作，只是不画「本会话免审」。
     */
    val onRespondChoice: ((approvalId: String, allow: Boolean, allowSession: Boolean) -> Unit)? = null,
    /** 会话内历史搜索；null = 输入栏右端不画那颗搜索。 */
    val onSearchInConversation: ((query: String, filter: HistorySearchFilter) -> List<MessageSearchResult>)? = null,
    /**
     * 「中断正在跑的 agent」——**控制面动作**，不是聊天消息。
     *
     * 为什么必须有这个槽：群里那条「中断」按钮从前实现成
     * `onSend(peer.nodeId, "/cancel", null)` —— 点一下就把四个字母当**普通群文本**
     * 发给所有成员（每人都会看到一条 "/cancel" 气泡），而对端若不把斜杠命令当命令，
     * agent 根本没停。协议本来就有 §6.6 的 agent-cancel 帧（sessionId / runId / reason），
     * 那才是它的家。
     *
     * 参数：`(peerId, sessionId, runId)`。null = 这个壳没接控制面 ⇒ 按钮**置灰**
     * （宁可不能点，也不要发一条假消息）。
     */
    val onCancelAgentRun: ((peerId: String, sessionId: String, runId: String) -> Unit)? = null,
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
    /**
     * 群主**解散**群：对所有成员生效。null = 这个壳没有解散能力，群信息里不画那颗按钮。
     *
     * 与 [onLeaveGroup] 并列而不是复用：退出只动自己（群对别人继续存在），解散是
     * 一次性的全员移出 —— 两个动作后果不同，入口也不同。
     */
    val onDissolveGroup: ((groupId: String) -> Unit)? = null,
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

/**
 * 发送之后把**外部草稿表**里这一条也清掉。
 *
 * ## 为什么发送侧必须显式清
 *
 * `ChatScreen` 的 onSend 只复位自己那份 `remember(peerId)` 的 `draft`；而两个壳都把
 * 草稿**同时**落在外部表（[ChatScreenChrome.onDraftChange]，`updateDraft` 的语义是
 * 「空串 = 删除」）。输入过程中的每一次 `onDraftChange` 都会同步外部表，唯独发送这条
 * 路径绕过了它 —— 于是外部表里留着一份**已经发出去的正文**，下次回到这个会话时
 * [ChatScreenChrome.initialDraft] 把它喂回输入框：用户看到自己刚发的话「复活」在输入框里。
 *
 * 抽成函数是为了让「发送 ⇒ 外部表同步清空」这条 [ChatScreenChrome.onDraftChange] KDoc
 * 承诺的契约能被 jvmTest 钉住，而不只是在 Composable 里一行带过。
 */
internal fun notifyDraftCleared(
    convId: String?,
    onDraftChange: ((convId: String, text: String) -> Unit)?,
) {
    if (convId != null) onDraftChange?.invoke(convId, "")
}

/**
 * 「输入焦点意图」的**空闲值**：没有任何一次请求（也是计数器复位后的值）。
 *
 * 为什么焦点要表达成一个计数：手机内置输入栏的 [androidx.compose.ui.focus.FocusRequester]
 * 挂在 `ChatScreen` 自己这一层，直接请求即可；桌面走 `composerSlot`，那一层**没有**
 * 挂载点 —— 对未挂载的 requester 调 `requestFocus()` 只会打印
 * `FocusRequester is not initialized` 并返回 false（静默失败，不崩）。
 * 于是桌面那条路改成"递增一个计数，由输入卡片自己的 requester 消费"：
 * `LaunchedEffect(focusRequest)` 的键变了才会重跑，所以每次请求都必须**换一个值**。
 */
internal const val COMPOSER_FOCUS_REQUEST_IDLE: Int = 0

/**
 * 下一条焦点意图的值：永远不等于 `current`，也永远不回到 [COMPOSER_FOCUS_REQUEST_IDLE]。
 *
 * 三条性质都是接线正确性的前提，单测钉在 `ComposerFocusRequestTest`：
 * 1. **每次调用都变** —— 值不变，`LaunchedEffect(focusRequest)` 不会重跑，
 *    同一会话里第二次点「回复」就又不聚焦了；
 * 2. **不回到空闲值** —— 空闲值表示"从来没有请求过"，交叠上去等于把意图吞掉；
 * 3. **越界回绕到 1** —— 计数器不该因为溢出变成负数或撞回 0。
 */
internal fun nextComposerFocusRequest(current: Int): Int =
    if (current >= Int.MAX_VALUE) 1 else current + 1

/**
 * [ChatScreenChrome.composerSlot] 收到的**一包上下文**。
 *
 * 为什么是 data class 而不是继续散参：slot 是「桌面壳自带输入卡片」的唯一接缝，
 * 手机内置输入栏（[com.kapp.tidings.ui.components.MessageComposer]）能拿到的每一份事实，
 * 桌面输入卡片都该拿到 —— 散参版本在「桌面回复点了没反应」「桌面长按头像 @ 静默丢失」
 * 「桌面新任务 chip 写死 @dsh」「桌面搜索命中只关窗」四件事上各漏了一次，全都是
 * 「手机侧有、桌面 slot 没接到」的同一种分叉。打包之后，加能力 = 加字段 +
 * 桌面壳自己决定接不接，不再靠改函数签名的位置记忆。
 */
data class ComposerSlotArgs(
    /** 当前草稿全文（ChatScreen 持有的唯一事实来源，切会话重建）。 */
    val draft: String,
    /** 草稿变化；空串 = 草稿被清掉（发送后）。 */
    val onDraftChange: (String) -> Unit,
    /**
     * 发送出口：`(正文, 被回复的消息 id)`。
     *
     * 为什么 slot 必须自己有发送出口：桌面输入卡片从前直接调
     * `controller.sendToConversation(peer.nodeId, text)`，于是它绕过了
     * [ChatScreen] 的 onSend —— 「回复」那条消息的 replyId 到不了发送侧（发出去是
     * 一条普通消息），发完 `replyingToMessage` 也没人清（引用条赖在输入框上方，
     * 下一条又变成"回复"）。手机内置输入栏能做的这两件事，桌面都做不到，根因就是
     * 这一层只有"读"没有"写"。
     *
     * 传进来的实现必须是 [ChatScreen] 里那个**与手机内置输入栏共用的**出口：
     * 复位草稿、清外部草稿表、清引用态、带 replyId 调发送 —— 四件事一起发生；
     * 分成两份实现就必然有一份漏掉（这正是本接缝存在的理由）。
     */
    val onSend: (body: String, replyToId: String?) -> Unit,
    /** `@` 候选（群成员 + 可叫醒的 agent），与手机输入栏同一份解析结果。 */
    val mentionTargets: List<MentionTarget>,
    /**
     * 正在引用的消息；null = 不画引用条。
     *
     * 桌面此前不收它：气泡菜单点「回复」只改了 ChatScreen 的状态，桌面输入卡片
     * 看都不看 —— 用户点了没反应。
     */
    val replyingTo: MessageUi? = null,
    /** [replyingTo] 的发送者显示名（ChatScreen 用身份解析器算好）；null = 资料未到。 */
    val replySenderName: String? = null,
    /** 取消引用；空实现 = 引用条不画取消按钮。 */
    val onCancelReply: () -> Unit = {},
    /**
     * 「把焦点落到输入框」的意图计数（[COMPOSER_FOCUS_REQUEST_IDLE] = 没有请求）。
     *
     * 桌面此前没有这条通路：`ChatScreen` 在「回复」「长按头像直接 @」这些动作里
     * 只调自己那份 `composerFocus`，而桌面走的是本卡片、那个 requester 没有挂载点
     * —— `requestFocus()` 返回 false、打印一行警告，用户看到的是"点了回复，光标还在
     * 消息列表上，得自己再点一下输入框"。
     *
     * 键必须是**递增计数**而不是布尔：连续两次「回复」也得各触发一次
     * （布尔从 true 到 true 不产生新键，第二次就丢了）。
     */
    val focusRequest: Int = COMPOSER_FOCUS_REQUEST_IDLE,
    /**
     * 「新任务」chip 要点名的 agent token（如 `@DSH`），由 ChatScreen 按**这个群里的
     * agent kind** 解析（与手机内置输入栏同一个值）。null = 不是工作区群，chip 不画。
     * 桌面 chip 从前写死 `@dsh`，在别的 agent 群里叫不醒。
     */
    val newTaskToken: String? = null,
    /**
     * 按消息 id 跳到消息流里的那条（历史搜索命中的落点）。null = 这个会话不支持跳转。
     *
     * 桌面此前把命中整个丢掉（`onSelectHit = { _ -> 关窗 }`）：窗口关了，
     * 用户却不知道要找的那条在哪。
     */
    val onJumpToMessage: ((messageId: String) -> Unit)? = null,
    /**
     * 只读原因；null = 可以正常输入。
     *
     * 被移出群时非空（见 ChatScreen 的 leftGroup）：桌面输入卡片刻画这句话、收起输入框，
     * 与手机内置输入栏同一语义。
     */
    val readOnlyReason: String? = null,
    val stagedFiles: List<LocalFile> = emptyList(),
    val onRemoveStagedFile: ((LocalFile) -> Unit)? = null,
    val onAttachFiles: (() -> Unit)? = null,
    val onStageFiles: ((List<LocalFile>) -> Unit)? = null,
)

data class ChatScreenChrome(
    val receivedFileActions: ReceivedFileActions? = null,
    val onBack: (() -> Unit)? = null,
    val onFindPeers: (() -> Unit)? = null,
    val modifier: Modifier = Modifier,
    val showHeader: Boolean = true,
    val bubbleFraction: Float = ContentWidths.BUBBLE_FRACTION_PHONE,
    /**
     * 外壳自带的输入卡片（桌面壳用它）。参数打包成 [ComposerSlotArgs]：
     * 桌面输入卡片与手机内置输入栏要接的是**同一份上下文**（草稿、@ 候选、引用条、
     * 「新任务」token、搜索命中跳转）—— 逐个散参每加一个能力就要改一遍签名，
     * 而且很容易漏接（桌面「回复点了没反应」的根因就是 slot 不收 replyingTo）。
     */
    val composerSlot: (@Composable (ComposerSlotArgs) -> Unit)? = null,
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
    val onStagedFilesChange: ((convId: String, files: List<LocalFile>) -> Unit)? = null,
    val initialDraft: String = "",
    val initialEmojiOpen: Boolean = false,
    val initialHistorySearch: Boolean = false,
    val initialScreenRequestConfirmOpen: Boolean = false,
)
