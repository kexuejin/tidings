// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.chat

import com.kapp.tenon.domain.model.ChatRowKind
import com.kapp.tenon.domain.model.ChatRowKinds

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kapp.tenon.core.protocol.GroupMeta
import com.kapp.tenon.domain.AgentSlice
import com.kapp.tenon.domain.SenderIdentityContext
import com.kapp.tenon.domain.model.PeerUi
import com.kapp.tenon.platform.ReceivedFileActions
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kapp.tenon.domain.SenderIdentity
import com.kapp.tenon.domain.canRecall
import com.kapp.tenon.domain.ChatSenderAttribution
import com.kapp.tenon.domain.resolveNodeIdentity
import com.kapp.tenon.domain.formatRecallRemaining
import com.kapp.tenon.domain.isRecallUrgent
import com.kapp.tenon.domain.recallBlockedReason
import com.kapp.tenon.domain.recallRemainingMs
import com.kapp.tenon.domain.peer
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.ui.components.DayDivider
import com.kapp.tenon.ui.components.TimelineSeparator
import com.kapp.tenon.ui.components.ScreenHistoryCard
import com.kapp.tenon.ui.theme.ComponentSizes
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.ui.EmptyState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import com.kapp.tenon.domain.model.MessageUi
import com.kapp.tenon.domain.model.NoticeKind
import com.kapp.tenon.generated.resources.card_unreadable
import com.kapp.tenon.generated.resources.chat_recall_expired
import com.kapp.tenon.generated.resources.chat_empty_message
import com.kapp.tenon.generated.resources.chat_empty_title
import com.kapp.tenon.generated.resources.chat_first_message
import com.kapp.tenon.generated.resources.ic_chat_bubble
import com.kapp.tenon.generated.resources.ic_down
import com.kapp.tenon.generated.resources.chat_history_begin
import com.kapp.tenon.generated.resources.chat_history_loading
import com.kapp.tenon.generated.resources.chat_unread_jump
import com.kapp.tenon.generated.resources.chat_unread_jump_latest
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.painterResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 消息列表主体（ARP-14 后半 · ChatScreen 最后一块 god 区）。
 *
 * 内容 = 空态 + LazyColumn（时间/会话分隔线、卡片折叠、气泡、历史加载条）
 * + 「回到最新」胶囊。判据与分支逐字未动；与 ChatScreen 的接缝只有：
 * - 五个回写状态（转发/看图/回复/撤回文案/卡片待发动作）以 MutableState 下传，
 *   宿主内同名 var 委托 —— 赋值语句逐字保留；
 * - ChatScreen 的三个本地函数（requestComposerFocus/scrollToMessage/
 *   quotedSenderNameOf）以函数值传入；
 * - actions 的八个回调在宿主内解包成与 ChatScreen 同名的本地别名。
 *
 * 包一层 Box(fillMaxSize)：块体里胶囊的 `align(Alignment.BottomCenter)` 需要
 * BoxScope；外层 Box(weight(1f)) 是 ColumnScope 域留在 ChatScreen —— 内外两个
 * Box 几何等价（内层恰好填满外层），像素不变。
 */
@Composable
internal fun ChatMessageList(
    messages: List<MessageUi>,
    group: GroupMeta?,
    peer: PeerUi,
    peerId: String?,
    now: Long,
    agentSlice: AgentSlice,
    listState: LazyListState,
    isNearBottom: Boolean,
    historyAtBeginning: Boolean,
    highlightedMessageId: String?,
    historyLoading: Boolean,
    scrollFollow: ChatScrollFollowState,
    bubbleCtx: SenderIdentityContext,
    bubbleFraction: Float,
    /** 群聊气泡内头像边长（外壳密度档，经 [ChatScreenChrome] 传入）。 */
    messageAvatarSize: Dp = ComponentSizes.MessageAvatar,
    composerSlot: (@Composable (ComposerSlotArgs) -> Unit)?,
    composerFocus: FocusRequester,
    requestComposerFocus: () -> Unit,
    scrollToMessage: (String) -> Unit,
    quotedSenderNameOf: (MessageUi) -> String?,
    mentionSender: (SenderIdentity) -> Unit,
    coroutineScope: CoroutineScope,
    actions: ChatScreenActions,
    receivedFileActions: ReceivedFileActions?,
    onDraftChangeExternal: ((String, String) -> Unit)?,
    forwardingMessageState: MutableState<MessageUi?>,
    previewingImagePathState: MutableState<String?>,
    replyingToMessageState: MutableState<MessageUi?>,
    lastRecalledTextState: MutableState<String?>,
    pendingCardActionsState: MutableState<Set<String>>,
    draftState: MutableState<String>,
) {
    // ChatScreen 顶部的本地解包（actions 同名回调）与五个回写状态的 var 委托：
    // 全部为了块体逐字保留。
    val onSend = actions.onSend
    val onAcceptTransfer = actions.onAcceptTransfer
    val onDeclineTransfer = actions.onDeclineTransfer
    val onCancelTransfer = actions.onCancelTransfer
    val onResumeTransfer = actions.onResumeTransfer
    val onRecallMessage = actions.onRecallMessage
    val onRetryMessage = actions.onRetryMessage
    val onDeleteMessage = actions.onDeleteMessage
    val onForwardMessage = actions.onForwardMessage
    var forwardingMessage by forwardingMessageState
    var previewingImagePath by previewingImagePathState
    var replyingToMessage by replyingToMessageState
    var lastRecalledText by lastRecalledTextState
    var pendingCardActions by pendingCardActionsState
    var draft by draftState
    val sp = LocalAppSpacing.current
    Box(modifier = Modifier.fillMaxSize()) {
            if (messages.isEmpty()) {
                // 空会话的下一步是打字：这个出口把光标直接送进下面的输入框，
                // 比"这里什么都没有"更清楚。桌面壳自带的输入卡片（composerSlot）
                // 有自己的焦点管理，那时候不给这个按钮，免得按下去什么都没发生。
                val focusComposer = composerSlot == null
                EmptyState(
                    title = stringResource(Res.string.chat_empty_title),
                    message = stringResource(Res.string.chat_empty_message),
                    actionLabel = if (focusComposer) stringResource(Res.string.chat_first_message) else null,
                    onAction = if (focusComposer) ({ composerFocus.requestFocus() }) else null,
                    icon = painterResource(Res.drawable.ic_chat_bubble),
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                )
            } else {
                // 整表派生只走一次：五个集合/索引同源、同一次失效；行级注册表也从
                // 这里拿 layout，不再各自扫 messages（判据本体仍住在各自的纯函数里）。
                val layout = remember(messages) { chatListLayoutOf(messages, ::localDayKey) }
                val sessionTransitions = layout.sessionTransitions
                // 时间/日期分隔线：列表首条、自然日切换或相邻消息间隔 >= 5 分钟。
                val timelineSeparators = layout.timelineSeparators
                // 引用气泡按 id 找源消息：建一次 map 后每次查 O(1)。
                val messagesById = layout.messagesById
                val messagePositions = layout.messagePositions
                // 卡片原位刷新的**可见集**（方案 §5.3）：按 cardId 折叠、只渲染最新 rev。
                // 落库是追加式新行（同 cardId、rev+1），「不刷屏」在这里成立 ——
                // 旧 rev 的行还在库里（历史翻得到），只是不再渲染。
                val visibleCardIds = layout.visibleCardIds
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 原型 `.msgs{...padding:12px 14px 6px;...gap:10px}`（桌面 `.win .msgs` 为
                    // `14px 16px 6px`/`gap:9px`，手机取 `:root` 那一条）。
                    contentPadding = PaddingValues(start = sp.lg14, top = sp.md, end = sp.lg14, bottom = sp.sm6),
                    // **内容不足一屏时要贴底**（气泡贴着输入区，不是贴着顶）。
                    //
                    // reverseLayout 只决定「从哪头开始排」，不决定「排不满时贴哪边」：
                    // 少了 `Alignment.Bottom` 这一档，短会话（只有两三条）的气泡会停在顶部、
                    // 下方留出一大片空白，而输入框贴在屏幕底部 —— 真机现场就是这样。
                    // 正序时这个现象不明显（内容和输入区都在上方），倒序把它放大了。
                    verticalArrangement = Arrangement.spacedBy(sp.md10, Alignment.Bottom),
                    // **倒序渲染**（聊天流的标准结构，WeChat/Slack/Telegram 都是这条）。
                    //
                    // 为什么：正序时「最新在末尾」，进入会话必须先量完整个列表才滚得准，
                    // 而 LazyColumn 在容器高度未稳定时 `canScrollForward` 会提前变 false ——
                    // 真机实测停在 firstVisibleItemIndex=74（应为 83）。
                    // 倒序后索引 0 就是最新一条，**进入即在底部**，根本不需要锚定；
                    // 新消息到达时它天然出现在可见区顶部（视觉上是最下方）。
                    reverseLayout = true,
                ) {
                    // **数据也要倒过来**：`reverseLayout` 只翻视觉，若数据仍是最旧在前，
                    // 屏幕上就变成「最新在上、最旧在下」—— 真机截图正是这个样子。
                    // 两者必须同时翻：数据倒序 + reverseLayout，才等价于原来的「最旧在上」。
                    items(messages.asReversed(), key = { it.id }) { message ->
                        val notice = message.notice
                        val unreadableText = stringResource(Res.string.card_unreadable)
                        // 行级判定只问注册表：画哪一种 / 旧 rev 收起 / 退档成文本，
                        // 优先级由 ChatRowKinds.ordered 决定，不再靠这串 if/else 的书写顺序。
                        // **缓存键 = 判定函数的全部输入**，一个不能少。
                        //
                        // 这里踩过两次同一个坑，两次的症状都是「数据对了、屏上是旧的」：
                        //  · T-2d 单卡槽（§6.2）：键里只有 id 时，rev 推进（renderCard 变）
                        //    缓存停在首帧 —— 判定横幅、步骤推进都不上屏；
                        //  · 文件卡（真机 defect A）：键里补了 renderCard/text 还不够 ——
                        //    传输推进时变的是 `message.file`（state/done/speedBps），
                        //    而 id/renderCard/text 三者一动不动。于是 `RowDecision.Delegate`
                        //    一直抱着**旧那条 MessageUi**，`bubbleMessage` 就是它，
                        //    文件卡永远画 `Offered`（真机：字节已落盘、accepted=true，
                        //    卡面仍写「等你接收」，退出会话再进来才对）。
                        //
                        // 所以键直接给 `message` 本体：`resolveRow` 读的是整条消息
                        // （ChatRowKinds.of 看 notice/screen/card/file，cardRowOutcome 看卡片负载），
                        // 任何一处变了都必须重判。MessageUi 是 data class，等值比较是结构化的，
                        // 「无关重组」（比如 `now` 每秒跳一次）仍然命中缓存，不会白重算。
                        val decision = remember(message, visibleCardIds, unreadableText) {
                            resolveRow(message, visibleCardIds, unreadableText)
                        }
                        val render = decision as? RowDecision.Render
                        val delegate = decision as? RowDecision.Delegate
                        if (render?.kind == ChatRowKind.Notice) {
                            if (notice != null) {
                                // 系统提示行不是消息：撤回与戳一碰都只留一行居中的灰字（协议 §8）。
                                NoticeLine(
                                    kind = notice,
                                    // 细节（谁把谁加/移了）。老库记录没有它 ⇒ 组件退回中性文案。
                                    detail = message.noticeDetail,
                                    // 名字在**这一层**解析：身份规则全在 SenderIdentityContext，
                                    // 组件自己不该知道。查不到就退回 8 位短 id（成员面板同一取舍）。
                                    displayNameOf = { id -> resolveNodeIdentity(id, bubbleCtx).displayName },
                                    // **入口只在「确实是这条消息让你没权」时给**：
                                    // 只有 agent 名拿得到、且调用方愿意接这个动作，才画那颗按钮。
                                    // 否则会出现一颗点了没反应的按钮 —— 比没有更糟。
                                    onRequestGrant = actions.onRequestAgentGrant
                                        ?.let { req ->
                                            {
                                                message.noticeDetail?.actor
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?.let(req)
                                            }
                                        },
                                    onReEdit = if (notice == NoticeKind.RecalledBySelf && !lastRecalledText.isNullOrBlank()) {
                                        {
                                            val textToRestore = lastRecalledText.orEmpty()
                                            draft = textToRestore
                                            peerId?.let { onDraftChangeExternal?.invoke(it, textToRestore) }
                                            requestComposerFocus()
                                        }
                                    } else null,
                                )
                            } else if (message.text.isNotBlank()) {
                                NoticeLine(text = message.text)
                            }
                        } else if (render?.kind == ChatRowKind.Screen) {
                            // 跨模块 public 属性先落成局部量，否则 smart cast 不成立。
                            // 上面的 `else if` 已经判过非空；`!!` 只在这里把那个判据固化下来 ——
                            // 换成 ?.let 会把这一整块包进一层 lambda，diff 比收益大。
                            val screenRecord = message.screen!!
                            // 屏幕协助历史卡：它是**独立的一条消息**，不是气泡的附件
                            // （交互规范中将其渲染成 `.message-stack` 的第一个
                            // 孩子）。左右归属与气泡同一条规矩 —— `mine` 在生产者那边就是
                            // "本侧是 viewer"，所以这里直接用。
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                ScreenHistoryCard(record = screenRecord, now = now)
                            }
                        } else if (render?.kind == ChatRowKind.AgentCard) {
                            val card = checkNotNull(render.card)
                            // 声明式卡片（方案 §4.3）：数据源是消息库（`MessageUi.card`），
                            // 与其它消息同一条读回路径。画不画由 `cardRowOutcome` 一处判：
                            //  · 解析不出身份键（cardId/rev 缺失）⇒ 退成普通文本（S21），
                            //    这一支**接不到**那种消息 —— 它落到下面的文本分支去了；
                            //  · 不是本 cardId 的最新 rev ⇒ 原位刷新把它收起（§5.3）。
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                // 产物 → 消息流里同卡关联的已落盘路径。三处判据共用这一份
                                // （条目入口 / 本地动作可见性 / 本地动作执行），各写一遍
                                // 迟早各差一个字（本仓最常见的分叉形态）。
                                // `message.at` = 这张卡在这一侧的时间锚：同名文件可能存了
                                // 好几轮，只有「卡之后最早落盘的那份」是它自己的投递。
                                val savedPathOf: (AgentCardArtifact) -> String? = {
                                    artifactSavedPathOf(messages, card.cardId, it.name, message.at)
                                }
                                AgentCardBubble(
                                    card = card,
                                    pendingActions = pendingCardActions,
                                    // D7：decidedBy（拍板节点的 nodeId）走与气泡同一套名字
                                    // 解析 —— 气泡口径（裸 nodeId = 人）正是这行的口径：
                                    // 拍板的是"人点了按钮"，不是那台机器上的 agent。
                                    decidedByCtx = bubbleCtx,
                                    // 产物预览（升级计划 §4.2-3）：同 cardId 的文件行里
                                    // 找「同名字 + 已落盘」的那份 —— 找不到入口不画，
                                    // 不做孤立路径展示。
                                    artifactSavedPathFor = savedPathOf,
                                    // 端内渲染不了的产物（网页原型 .html）交给平台出口 ——
                                    // 三端 `ReceivedFileActions` 现成（Android `ACTION_VIEW` +
                                    // FileProvider / 桌面 `Desktop.open` / iOS Quick Look）。
                                    // 刻意**不**拼成 `file://` 可点链接：那条路被
                                    // `ChatReviewLogicTest` 明令禁止（本机文件路径不该成为
                                    // 文本里的可点目标），这里走的是系统应用打开通路。
                                    onPreviewArtifact = receivedFileActions?.let { fileActions ->
                                        { _, path -> fileActions.open(path) }
                                    },
                                    // 「在浏览器打开原型」是**本地动作**：产物还没落到本机时
                                    // 整颗不画（画一颗点了没反应的比没有更糟）。
                                    isActionVisible = { action ->
                                        action.actionId != SYSTEM_ACTION_OPEN_PROTOTYPE ||
                                            prototypeSavedPathOf(card, savedPathOf) != null
                                    },
                                    // 会话里已经有一份时钟（屏幕协助卡在用同一个）：
                                    // 过期子态跟着它走，组件自己不读表 —— 可测、可截帧。
                                    now = now,
                                    // 可空槽：null = 这个壳没有动作通路（跨端派发 M4 才接），
                                    // 按钮组整块不画 —— 按下去没反应的按钮比没有更糟。
                                    onAction = actions.onCardAction?.let { dispatch ->
                                        { card: AgentCardUi, actionId: String ->
                                            val localPath = if (isLocalCardAction(actionId)) {
                                                prototypeSavedPathOf(card, savedPathOf)
                                            } else {
                                                null
                                            }
                                            if (localPath != null) {
                                                // 本地兑现：不置 pending（宿主不会回这一帧，
                                                // 置灰会永久留着），也不派发（宿主没有这条语义，
                                                // 发过去只会被丢并顺手把卡翻成 stale）。
                                                receivedFileActions?.open(localPath)
                                            } else {
                                                // 本机乐观置灰先行（§5.3）：即使动作派发失败，
                                                // 「我点过了」也是本机事实；宿主判定到达后三态接管。
                                                pendingCardActions = pendingCardActions + "${card.cardId}|$actionId"
                                                dispatch(peer.nodeId, card, actionId)
                                            }
                                        }
                                    },
                                )
                            }
                        } else if (decision is RowDecision.Hidden) {
                            // 旧 rev：库里的旧行还在（历史翻得到），只是不再渲染（§5.3）。
                            // 这一支刻意留空且**不留注释以外的东西** —— 加任何占位都会把
                            // 「收起」变成「换一种方式显示」。
                        } else if (render?.kind == ChatRowKind.AgentState) {
                            // 执行状态轻横幅（设计稿 v2 §5「失败如实」）：宿主在
                            // 「插件接不了活 / 执行没做成」时发的那条 agent 署名群文本。
                            // 判在**卡片之后**：同一帧既带卡又带状态时卡才是主体，
                            // 横幅再画一遍等于同一条事实说两遍。
                            val state = message.agentState
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                AgentStateBannerRow(state = state.orEmpty(), text = message.text)
                            }
                        } else {
                            // S21 退档：卡片负载读不出来时，原文照常按普通消息渲染。
                            // 连正文都没有时补一句本地兜底 —— 否则「退档」只画出一个空气泡，
                            // 与原来的「整行不画」一样是「什么都没说」。
                            // 退档文案与兜底词条已经在注册表里写回正文，这里只管画气泡。
                            val bubbleMessage = delegate?.message ?: message
                            val originalIndex = messagePositions[message.id] ?: -1
                            val prevMsg = if (originalIndex > 0) messages.getOrNull(originalIndex - 1) else null
                            val nextMsg = if (originalIndex in 0 until messages.size - 1) messages.getOrNull(originalIndex + 1) else null

                            val isSameSenderWith = { a: MessageUi, b: MessageUi ->
                                ChatRowKinds.capabilities(ChatRowKinds.of(a)).groupsWithSender &&
                                    ChatRowKinds.capabilities(ChatRowKinds.of(b)).groupsWithSender &&
                                    a.mine == b.mine &&
                                    (a.mine || a.senderId == b.senderId) &&
                                    kotlin.math.abs(a.at - b.at) < 90_000L
                            }

                            val hasPrevSender = prevMsg != null &&
                                isSameSenderWith(prevMsg, message) &&
                                message.id !in timelineSeparators &&
                                message.id !in sessionTransitions

                            val hasNextSender = nextMsg != null &&
                                isSameSenderWith(message, nextMsg) &&
                                nextMsg.id !in timelineSeparators &&
                                nextMsg.id !in sessionTransitions

                            // 「重发」接的是已有的 onSend：以原文本再发一条，不是服务端重试 ——
                            // core 没有按 messageId 重投的接口，而让用户自己照着抄一遍是白给的摩擦。
                            // 所以这里只在有真文本时给出口：文件消息重发不了（本地路径早就没了）。
                            val quotedMsg = message.replyTo?.let { replyId ->
                                messagesById[replyId]
                            }
                            // 这条消息的发送者身份：署名 / 头像 / Markdown / 长按 @ **共用一份**。
                            // 判据全部在 `ChatSenderAttribution`（单聊里 agent 以 `agent:<宿主>:<kind>`
                            // 署名说的话，也必须解析成 agent，而不是"对端那台设备"）。
                            //
                            // `identityCtx` 由 `bubbleCtx` 还原：两者只差 `bareNodeIdIsHuman`
                            //（见 ChatScreen 里的 `bubbleCtx = identityCtx.copy(...)`）。传一份而不是
                            // 再加形参，是为了不让"同一个上下文两个副本"在调用链上多一处可能走失的地方。
                            val senderIdentity = ChatSenderAttribution.identityOf(
                                message = message,
                                isGroup = group != null,
                                peerNodeId = peer.nodeId,
                                identityCtx = bubbleCtx.copy(bareNodeIdIsHuman = false),
                                bubbleCtx = bubbleCtx,
                            )
                            MessageBubble(
                                message = bubbleMessage,
                                bubbleFraction = bubbleFraction,
                                messageAvatarSize = messageAvatarSize,
                                onAcceptTransfer = onAcceptTransfer ?: {},
                                onDeclineTransfer = onDeclineTransfer ?: {},
                                onCancelTransfer = onCancelTransfer,
                                onResumeTransfer = onResumeTransfer,
                                receivedFileActions = receivedFileActions,
                                // 「重发」必须按**原消息 id** 重放：换新 id 重发在收端就是
                                // 另一条消息（真机现场：同一句话在桌面库里两个 id）。
                                // 没有这条能力的壳（预览）退回旧行为，红字仍然可点。
                                onRetry = if (message.file == null && message.text.isNotBlank()) {
                                    onRetryMessage?.let { retry -> { _: String -> retry(message) } }
                                        ?: { text: String -> onSend(peer.nodeId, text, null) }
                                } else {
                                    null
                                },
                                // 撤回入口的判据在 canRecall 里（自己的、5 分钟内、媒体要有 msgId）。
                                // 超时后**入口置 null（点不动）但这一项仍然显示** ——
                                // 只剩"不能撤回"这一个事实时，把它藏起来等于不解释（见 recallBlockedReason）。
                                onRecall = onRecallMessage
                                    ?.takeIf { canRecall(message, now) }
                                    ?.let { recall ->
                                        {
                                            if (message.text.isNotBlank()) {
                                                lastRecalledText = message.text
                                            }
                                            recall(message)
                                        }
                                    },
                                recallVisible = onRecallMessage != null &&
                                    recallBlockedReason(message) == null,
                                recallMeta = formatRecallRemaining(recallRemainingMs(message, now))
                                    ?: stringResource(Res.string.chat_recall_expired),
                                recallUrgent = isRecallUrgent(recallRemainingMs(message, now)),
                                onReply = {
                                    replyingToMessage = message
                                    // 回复是"立刻要打字"的动作：焦点必须跟着进输入框。
                                    // 桌面走计数意图（本层 requester 在桌面没有挂载点）。
                                    requestComposerFocus()
                                },
                                onForward = onForwardMessage?.let { { forwardingMessage = message } },
                                // 只删本机这一条：不动协议、不通知对方。没有这一项时，
                                // 用户想清掉一条只有「撤回」（有时限）或「清空整个会话」两条路。
                                onDelete = onDeleteMessage?.let { del ->
                                    { peerId?.let { pid -> del(pid, message.id) } }
                                },
                                // 群里必须署名：会话键是 `group:<groupId>`，气泡左右只分得出
                                // "是不是我发的"，分不出"是哪个人发的"。
                                // 单聊只在**对方是 agent** 时署名（"这句话是 dsh 说的"），
                                // 人对端不署 —— 会话标题已经写了他是谁。
                                senderName = if (
                                    ChatSenderAttribution.showsName(message, group != null, senderIdentity)
                                ) {
                                    senderIdentity?.displayName
                                } else {
                                    null
                                },
                                // 原型 `.msg .mav` 只在**别人发的**消息上出现（演示数据里
                                // 自己发的几条一条 `ini` 都没有）。头像与署名**同源**：
                                // agent 的 `agent:<kind>` 走解析器后 isAgent 为真，于是它画的是
                                // agent 头像（以前 peer() 查不到人就退化成会话对端的样子）。
                                senderAvatar = senderIdentity?.let { identity ->
                                    ChatAvatar(
                                        name = identity.displayName,
                                        avatar = identity.avatar,
                                        isAgent = identity.isAgent,
                                    )
                                },
                                // 长按发送者头像 = 直接 @ 这个人 / 这个 agent（自己除外）。
                                // **不再限定群**：单聊里对方若是 agent，长按它的头像同样该能 @ ——
                                // 此前这里是 `group != null && ...`，单聊恒为 null，于是
                                // `ChatBubbles` 连手势都没注册（它的 `if (onMentionSender != null)`）。
                                // 判据只留「这条消息不是自己发的」+ `canMention`（它已排除自己）。
                                onMentionSender = if (!message.mine) {
                                    senderIdentity
                                        ?.takeIf { it.canMention }
                                        ?.let { identity -> { mentionSender(identity) } }
                                } else {
                                    null
                                },
                                quotedMessage = quotedMsg,
                                // 引用卡上的发送者名：与气泡署名同一个解析器，不在组件里
                                // 拿 senderId 前 8 位凑数（那是协议标识，不是人名）。
                                quotedSenderName = quotedMsg?.let { quotedSenderNameOf(it) },
                                onScrollToQuoted = quotedMsg?.let { target ->
                                    {
                                        // 走镜像换算后的视觉下标（见 scrollToMessage 的 KDoc）。
                                        scrollToMessage(target.id)
                                    }
                                },
                                onImageClick = { previewingImagePath = it },
                                // agent 的回复本身就是 Markdown（标题/列表/代码块/表格/链接），
                                // 纯文本渲染会让用户看到 ** 与 - 的字面量。人发的消息不转，
                                // 否则用户打的星号会被当成强调。
                                renderMarkdown = shouldRenderMarkdown(
                                    mine = message.mine,
                                    senderIsAgent = senderIdentity?.isAgent == true,
                                    senderId = message.senderId,
                                ),
                                isHighlighted = message.id == highlightedMessageId,
                                hasPrevSender = hasPrevSender,
                                hasNextSender = hasNextSender,
                            )
                        }
                        // **两条分隔线必须画在气泡之后，不能画在之前。**
                        //
                        // 这个列表是 `reverseLayout = true` + 数据倒序（见上面那段 KDoc）。
                        // 正序下「画在消息之前」= 视觉上方，是对的；一旦翻了向，同一个写法
                        // 就变成**视觉下方** —— 真机现场：`任务：…` 那条线落在它所标记的消息
                        // 的下面，读起来像「这条消息结束后换了新会话」。
                        //
                        // 判据：在 reverseLayout 里，**后画的元素在视觉上方**。所以日期线与
                        // 会话线都要写在 MessageBubble 之后。改顺序时请连这条注释一起想。
                        if (message.id in timelineSeparators) {
                            TimelineSeparator(at = message.at, now = now)
                        }
                        if (message.id in sessionTransitions) {
                            // **必须与 `sessionTransitionIds` 用同一个判据取 sid。**
                            //
                            // 这里原来写的是按 id 前缀取会话（`extractSessionId`，已随债#1 删除）：
                            // 它只认 `agent-ev-` 开头的 id —— 那是桌面端本地构造消息的副产物，
                            // 对**手机端**（id = 信封 UUID）恒为 null，于是「判定说这里是切换点、
                            // 渲染说自己取不到 sid 就跳过」—— 手机端永远画不出那条线，而数据是对的。
                            // 这正是用户报的「桌面有、移动端没有」。现在两边同源：协议字段。
                            val sid = sessionIdOf(message)
                            if (sid != null) {
                                // **标题优先用消息自带的那一份。**
                                //
                                // `agentSlice.agentSessions` 是**本机私有**数据：它只由本机 agent 宿主
                                // 写入（`saveSession`），从来没有同步到对端 —— 真机读数：手机侧那张
                                // 表是**空的**（count=0），所以它那边每条线都只能显示「新会话」。
                                //
                                // 转发时把标题一并带上之后，对端也拿得到真标题。查本地表那一步
                                // 保留着：桌面上 agent 会话就是本机的，表里有更权威（会随标题更新
                                // 而刷新）。两者都没有才退回「新会话」那句中性说明。
                                val s = agentSlice.agentSessions.values.firstOrNull { it.sessionId == sid && it.title?.isNotBlank() == true }
                                    ?: agentSlice.agentSessions.values.firstOrNull { it.sessionId == sid }
                                SessionDivider(sessionTitle = message.agentSessionTitle ?: s?.title)
                            }
                        }
                    }
                    // 历史这一端的两条边界提示（设计规格的 loading_history 槽 + 到达最早的终点）。
                    //
                    // **必须声明在 items(...) 之后**：reverseLayout 里"后声明的条目在视觉上方"
                    // （与 DayDivider 画在气泡之后是同一条规矩），而这两条属于消息流的最顶端。
                    if (historyLoading) {
                        item(key = "history-edge-loading") {
                            HistoryEdgeMarker(text = stringResource(Res.string.chat_history_loading))
                        }
                    } else if (historyAtBeginning) {
                        item(key = "history-edge-begin") {
                            HistoryEdgeMarker(text = stringResource(Res.string.chat_history_begin))
                        }
                    }
                }
            }

            // **下方还有消息**时的浮起胶囊（真机反馈 #5）。判据就是「贴底」的反面：
            // 已经贴底 = 最新那条在眼前、下方没有东西，这时不画（画了会盖住最新那条气泡）。
            // - 进入时采下的未读（unreadOnEntry）> 0 = 初始锚定没落到底（旧 bug 或竞态），
            //   此时下面确实压着 N 条没看过 → 「N 条未读 · 回到最新」；
            // - 进入后、用户不在底部时新到的（unseenWhileAway）同理；
            // - 两个数字都没有、只是用户往上翻 → 只说「回到最新」，**不写条数**（不编）。
            // 点它或自己滚到底即清除。
            val unseenTotal = scrollFollow.unseenTotal
            if (messages.isNotEmpty() && !isNearBottom) {
                // 触达 48dp、视觉仍是那颗矮胶囊：外层透明 Box 吃命中区，内层 Surface 只画外观。
                // 此前整颗就是可点区域（约 30dp 高），拇指要试两次；而它是"回到最新"的唯一切入口。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = sp.md)
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable {
                            scrollFollow.clearUnseen()
                            coroutineScope.launch {
                                // reverseLayout：最新一条在索引 0。
                                if (messages.isNotEmpty()) listState.animateScrollToItem(0)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = sp.lg14, vertical = sp.sm6),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(sp.xs),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_down),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (unseenTotal > 0) {
                                stringResource(Res.string.chat_unread_jump, unseenTotal)
                            } else {
                                stringResource(Res.string.chat_unread_jump_latest)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                }
            }
}
}
