// SPDX-License-Identifier: GPL-3.0-only

package app.relay.feature.chat

import app.relay.app.HistorySearchFilter
import app.relay.app.MessageSearchResult
import app.relay.app.model.MessageUi
import app.relay.app.RelayUiState
import app.relay.core.nowMillis
import app.relay.platform.ReceivedFileActions
import app.relay.ui.ContentWidths
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
)

/**
 * 群管理动作。五个回调都以 `groupId` 为首参 —— 那是消息流的职责边界：
 * 它只知道「这是哪个群」，不知道群成员表怎么改。
 */
data class ChatScreenGroupActions(
    val onAddGroupMembers: ((groupId: String, memberIds: List<String>) -> Unit)? = null,
    val onRemoveGroupMember: ((groupId: String, memberId: String) -> Unit)? = null,
    val onUpdateGroupMeta: ((groupId: String, description: String?, announce: String?) -> Unit)? = null,
    val onLeaveGroup: ((groupId: String) -> Unit)? = null,
)

/**
 * 外壳与一次性初值。
 *
 * `initial*` 四个参数是**截图接缝**：让那些「只在用户点了什么之后才出现」的分支
 * 在首帧就开着，否则截图门禁对它们完全变瞎。默认值全部等于生产行为（关的）。
 */
data class ChatScreenChrome(
    val receivedFileActions: ReceivedFileActions? = null,
    val onBack: (() -> Unit)? = null,
    val onFindPeers: (() -> Unit)? = null,
    val modifier: Modifier = Modifier,
    val showHeader: Boolean = true,
    val bubbleFraction: Float = ContentWidths.BUBBLE_FRACTION_PHONE,
    val composerSlot: (@Composable (String, (String) -> Unit) -> Unit)? = null,
    val initialDraft: String = "",
    val initialEmojiOpen: Boolean = false,
    val initialHistorySearch: Boolean = false,
    val initialScreenRequestConfirmOpen: Boolean = false,
)
