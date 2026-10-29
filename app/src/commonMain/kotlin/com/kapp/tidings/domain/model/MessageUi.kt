// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.model

import com.kapp.tidings.core.protocol.ScreenPhase
import com.kapp.tidings.core.protocol.ScreenRole
import com.kapp.tidings.core.protocol.ScreenSessionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
/**
 * 一条消息**此刻**的投递状态。
 *
 * ## 为什么有 [Sending]
 *
 * 发送是**异步**的：`sendTextWithEnvelope` 要先在候选端点上跑 UDP 退避（1s/2s/4s）、
 * 再对每个端点试一次 TCP（各 7s 超时）。跨网最坏情况能到 40 秒量级。
 *
 * 在 [Sending] 之前，这整段时间里界面上**什么都没有** —— 气泡不出现、发送按钮还能再点，
 * 于是用户以为"没点上"，连点三次就发出去三条。真机上这是最容易让人认为"这软件坏了"的
 * 一条路径（它还恰好是每天用得最多的那条）。
 *
 * 所以现在的顺序是：点发送 ⇒ **立刻**用 [Sending] 插一个气泡 ⇒ 等发送路径返回后
 * 按结果把它换成 [Sent] / [Queued] / [Failed]（见 `RelayControllerMessagesComponent`
 * 的乐观插入与 `handleReplaceMessage`）。
 *
 * [Sending] **不是协议状态**：它不上线、不落库语义、重启后不会残留（重启时未发出的
 * 消息按 [Queued] 处理 —— 队列本来就是它的事实来源）。
 */
enum class DeliveryState { Sending, Sent, Queued, Failed, Recalled }

/**
 * 一次文件传输在 UI 上的状态。
 *
 * [Offered] 是「邀请已发出/已收到，等对方接受」——方向看 [FileCardUi.mine]。
 * 协议里传输真正开始于接受之后（`file-ctl accept`），之前只是邀请。
 */
@Serializable
enum class TransferState { Offered, Active, Completed, Failed, Declined }

/**
 * 聊天流里的文件卡片。
 *
 * 复用 [MessageUi] 的追加/替换机制，所以文件和文字在同一条时间线上 —— 这是产品形态
 * 而不是实现便利：用户回看时「那张图什么时候发的」和「那句话什么时候说的」是同一个问题。
 */
@Serializable
data class FileCardUi(
    val transferId: String,
    val name: String,
    val size: Long,
    val fileCount: Int = 1,
    val done: Long = 0L,
    /** 整次传输的采样速率（字节/秒，EMA 平滑）；null = 没有可信读数，界面不画。 */
    val speedBps: Long? = null,
    val mine: Boolean = false,
    val isFolder: Boolean = false,
    val state: TransferState = TransferState.Offered,
    val reason: String? = null,
    val savedPaths: List<String> = emptyList(),
    /**
     * **本端自己发出去时**这些文件在本机的位置（发端原始路径，用户选中的那个）。
     *
     * 与 [savedPaths] 是两件事，不能合并：[savedPaths] 是「收到的文件落到了哪」，
     * 界面据此说「已存到 <路径>」；这个是「我本来就有这个文件」，只在 [mine] 为真时有值，
     * 用途只有一个 —— 给自己发出去的图也画一张缩略图（否则同一条消息在两端长得不一样）。
     *
     * 不落盘：文件卡本身就不落盘（见 `MessageUi.toStorable`）。
     */
    /**
     * 本端是否**免确认接收**了这份文件（小图片等）。
     *
     * 由 core 的 TransferManager 判定、经 [TransferEvent.Offered] 带上来；界面据此
     * **不画**「接收/拒绝」按钮 —— 它是"要不要用户点头"的单一权威，不是界面自己算的。
     */
    val autoAccepted: Boolean = false,

    val localPaths: List<String> = emptyList(),
    /**
     * 这条文件消息的**协议消息 id**（`file-ctl offer` 的 `msgId`），媒体撤回就是靠它匹配的。
     *
     * 为什么不直接用 [MessageUi.id]：我们的消息 id 是本地生成的序号（`localMessageId()`），
     * 两端各不相同；而协议要求「收发两端用该 `msgId` 入库为同一消息 ID」——对端撤回时
     * 报的是它那边的 `msgId`，只有单独存下来才对得上。`null` = 对端（或我们自己）没带，
     * 这条文件消息就收不到媒体撤回。
     */
    val msgId: String? = null,
    /**
     * `file-ctl offer` 的 `purpose`（`image` / `sticker` / `update` / `share-*`）。
     *
     * 只影响媒体撤回的判据：图片可在窗口内撤回并隐藏，普通文件只在本端尚未收完时接受撤回
     * （见 `acceptsMediaRecall`）。`null` = 对端没带用途，按普通文件处理。
     */
    val purpose: String? = null,
    /**
     * 表格图片的**文字视图**（`file-ctl offer.tableText`，原始制表符文本，≤4096B UTF-8）。
     *
     * 协议 §3：表格粘图仍是普通 `purpose:"image"`，但发送端**只向声明了 `tbl1`
     * 的收件人**附带这段文字。收端把它存进图片消息，气泡里就能在图片与文字之间切换 ——
     * 表格里的数字要能选中复制，图片做不到这件事。
     *
     * `null` = 对端没带（旧端、或本端没声明 `tbl1`），那就只有图片。
     */
    val tableText: String? = null,
    /**
     * 发送端是否已按上限截断 [tableText]（`tableTextTruncated`）。
     *
     * 只在与 `tableText` 同时出现时合法（协议 §3 明写）。为真时界面必须说一句"文字已截断，
     * 图片是完整的"—— 否则用户会以为表格就这么点内容。
     */
    val tableTextTruncated: Boolean = false,
) {
    /** 0f..1f。总量为 0 时不做除零，直接算 0 —— 空目录也会走到这条路。 */
    val fraction: Float
        get() = if (size <= 0L) 0f else (done.toFloat() / size.toFloat()).coerceIn(0f, 1f)
}

/**
 * **一条系统通知的细节**：谁做的、涉及谁。
 *
 * ## 为什么通知需要带数据
 *
 * 原先只有 [NoticeKind] 一个枚举，于是「群里的人变了」这句话**不说是谁把谁加/移了**。
 * 真机现场：桌面端与手机上都只有「群成员有变化」这句，用户得自己去成员表里比对才知道
 * 发生了什么 —— 而同类产品（茶话间）写的是「张三邀请李四加入群聊」。
 *
 * ## 为什么成员存 nodeId 而不是昵称
 *
 * 昵称会改（`profileRev` 就是为了它），而这条通知是**历史事实**。存 nodeId、渲染时
 * 现查昵称，改过名之后看历史仍然是「当时那个人」，且不会出现两个说法。
 *
 * 查不到昵称时退回 8 位短 id —— 与成员面板同一取舍（如实，而不是编一个名字）。
 */
@Serializable
data class NoticeDetail(
    /** 执行者（nodeId）。退群那条是自己，加/移人是操作者。 */
    val actor: String? = null,
    /** 被加进群的成员（nodeId）。 */
    val added: List<String> = emptyList(),
    /** 被移出群/主动退出的成员（nodeId）。 */
    val removed: List<String> = emptyList(),
) {
    /** 没有任何可展示的细节 ⇒ 渲染端退回那句中性文案。 */
    val isEmpty: Boolean get() = actor == null && added.isEmpty() && removed.isEmpty()
}

/**
 * 时间线上的一条**系统提示行**（不是聊天气泡）。
 *
 * 协议把「撤回」「戳一碰」都定义成"本地插入一条 system 提示行"，所以这里给的是**原因**而不是
 * 正文：正文由 UI 层按 [NoticeKind] 翻文案，控制器不写死中文（同 [ConversationUi.preview] 的规矩）。
 */
@Serializable
enum class NoticeKind {
    RecalledByPeer,
    RecalledBySelf,
    NudgedByPeer,
    NudgedBySelf,

    /** 群成员表变了（有人进来或有人走了）。 */
    GroupMembersChanged,

    /**
     * **@ 到了 agent，但发送者没有执行权**，所以这条指令没有交给它。
     *
     * 为什么必须单独一档、而且要出现在会话里：在这条之前，这种情况在界面上**完全没有痕迹**。
     * 用户 @ 完、看到消息正常显示，然后什么都不发生 —— 只会以为 agent 收到了在忙、或者坏了。
     * 而系统其实知道确切原因（`AgentExecAuthorizer` 的档位）。
     *
     * 与 [GroupMembersChanged] 同一类：它是**系统的解释**，不是任何人说的话，所以走 notice 行。
     */
    AgentNotAuthorized,

    /** 群名改了。 */
    GroupRenamed,

    /**
     * 我们被移出了这个群。
     *
     * 与 [GroupMembersChanged] 分开：那是"群里的人变了"，这是"**我不在里面了**"，
     * 用户要做的事完全不同（前者不用管，后者是"我以后发不出话"）。
     */
    RemovedFromGroup,
}

@Serializable
data class MessageUi(
    val id: String,
    /**
     * 这条消息住在哪个**会话**里 —— 私聊是对方 nodeId，群聊是 `group:<groupId>`
     * （见 `com.kapp.tidings.core.protocol.ConvId`）。
     *
     * 名字仍是 `peerId` 是历史原因，但它现在的取值域已经是"会话键"。改名会牵动
     * `messages.json` 的键与几十处调用点，收益只是名字好看，所以留着；
     * **判断群聊要用 `ConvId.isGroup(peerId)`，不要靠这个字段名去猜语义**。
     */
    val peerId: String,
    val mine: Boolean,
    val text: String,
    val at: Long,
    /**
     * 落库时分配的会话内序号 —— 分页游标。**不是** UI 概念，只在「翻更早的消息」时用。
     *
     * 为什么必须让它跟着消息走进内存：向上翻页的游标是「当前窗口最老那条的 seq」。
     * 内存里没有它就只能拿 at（时间戳）当游标 —— 同一毫秒的多条消息会重复或漏掉，
     * 而那正是 agent 流式回复的常态（真机上 172 KB 那几条都落在同一秒内）。
     *
     * null 表示「这条还没落过库」：本地刚构造、尚未 save 的消息没有 seq，
     * 它必然在窗口尾部，不影响向上翻页。
     */
    val seq: Long? = null,
    val delivery: DeliveryState? = null,
    /** 文件消息。为 null 时是纯文本消息。 */
    val file: FileCardUi? = null,
    /**
     * 群消息里**是谁说的**（发送者 nodeId）。
     *
     * 私聊恒为 null：私聊的会话键就是发送者，再存一遍是同一件事的第二个说法。
     * 群聊必须存：会话键是 `group:<groupId>`，没有它就不知道气泡该署谁的名 ——
     * 协议里群文本是逐成员单播的普通 `msg`，`envelope.from` 是唯一线索，
     * 而那个字段在入库时就丢了。
     */
    val senderId: String? = null,
    /**
     * 这条消息属于**哪个 agent 会话**（协议 `agentSessionId`，群转发的 agent 事件才带）。
     *
     * ## 为什么现在是字段而不是从 id 里猜
     *
     * 聊天流里那条「任务：xxx / 新会话」分段线原来靠 id 前缀判（已随债#1 删除，只认协议字段），
     * 而那个前缀只有**桌面端本地构造消息**时才有；转发进群时没带过去，手机端落库用信封 UUID，
     * 于是 `extractSessionId` 恒 null —— 桌面有分段线、手机一条都没有。
     *
     * 会话归属是**事实**，由协议携带（`agentSessionId` + `agentCursor`），
     * 不再靠 id 字符串猜。null = 这条消息不属于任何 agent 会话（普通聊天）。
     */
    val agentSessionId: String? = null,
    /**
     * 该 agent 会话的**标题**（协议 `agentSessionTitle`）。
     *
     * 分段线显示「任务：xxx」用它；null 时显示「新会话」那句中性说明。
     *
     * 为什么标题跟着消息走：它本来是**本机私有**数据（`agentSessions` 只由本机 agent
     * 宿主写入、从没同步过对端），真机上手机侧那张表是空的 ⇒ 所有分段线只能显示
     * 「新会话」。跟着消息走则单帧自包含，不存在「元数据到了、消息没到」的时序缺口。
     */
    val agentSessionTitle: String? = null,
    /**
     * 出站排队时为 [DeliveryState.Queued]，其余为 null —— 一个徽标，别的什么都不存。
     *
     * 这里**曾经**有一个 `outgoingEnvelope: String?`，存的是整条已编码信封，
     * 因为 core 的待发队列一度只是进程内的 `InMemoryQueueStore`（重启即空）。
     * 现在队列自己落盘了（`core/persist/PersistStores`），信封只存在于队列里一份。
     *
     * 为什么两份凭据更难维护：UI 那份必然可能过期 —— 队列出队了 UI 还留着信封，
     * 下次启动就会把一条已经送达的消息重新塞回队列，**对端于是收到两条**。
     * 现在的规则是启动时问 core 要还在等的 msgId（`NodeRuntime.pendingOutgoingMessages()`），
     * 反过来贴 UI 那一行，见 `RelayControllerMessagesComponent.handleRestoreQueuedFrom`。
     */
    /** 非 null 时这条不画气泡，画一行居中的系统提示。 */
    val notice: NoticeKind? = null,
    /**
     * 通知的细节（谁把谁加/移了）。null = 老数据或没有细节 ⇒ 渲染端用中性文案。
     *
     * 与 [notice] 分开而不是塞进 `text`：`text` 进 FTS 索引，把一段结构化 JSON 塞进去
     * 会让搜索命中一堆 `{"actor":...}` 碎片。
     */
    val noticeDetail: NoticeDetail? = null,
    /** 被引用的源消息 ID（协议 §7.1 / Payloads.replyTo）。 */
    val replyTo: String? = null,
    /** PK 猜拳/掷骰子卡片。为 null 时不是 PK 消息。 */
    val pk: PkCardUi? = null,
    /**
     * 屏幕协助历史卡。为 null 时这条不是屏幕协助记录。
     *
     * 与 [file]、[pk] 是同一类东西（"这条消息画一张卡"），但有一个关键区别：**它落盘**。
     * 文件卡与 PK 卡都不落盘（见 [toStorable] 的 KDoc），重启后卡片就没了；屏幕协助卡是
     * 「这件事发生过」的唯一记录，重启必须还在 —— 所以它有自己的列（`screen_json`），
     * 而不是搭 `file_json` 的车（搭车会让 `searchFileMessages` 把屏幕记录当成文件命中）。
     */
    val screen: ScreenRecordUi? = null,
    /** 深度思考过程文本（PPE-2 / DS-Bot 模式）。为 null 时不折叠思考流。 */
    val reasoning: String? = null,
    /** 思考过程耗时（毫秒）。为 null 时显示"思考中"或不显示耗时。 */
    val reasoningElapsedMs: Long? = null,
    /**
     * 声明式卡片负载（方案 §4.3）：协议 `card` 键的原样 JSON。
     *
     * **落库是追加式新行**（同 cardId、rev+1 的新帧，见方案 §5.3）—— 不重写旧行，
     * 绕开 `INSERT OR REPLACE` / FTS 那套不变量；「不刷屏」是 UI 语义（按 cardId
     * 折叠、只渲染最新 rev），不是存储语义。UI 渲染引擎是 M3 的交付物。
     */
    val card: JsonObject? = null,
    /** 插件 → 全群的执行状态（方案 §4.1 四态）：accept / blocked / completed / failed。 */
    val agentState: String? = null,
)

/**
 * 从文本中解析 `<think>...</think>` 推理内容与正式回答文本。
 *
 * 针对 DeepSeek-R1、Claude 等思考模型：
 * - 如果包含 `<think>...</think>` 闭合标签，返回 `(reasoning, remainingText)`。
 * - 如果包含未闭合的 `<think>...`（如流式传输中断），返回 `(reasoning, "")`。
 * - 如果没有 `<think>` 标签，返回 `(null, rawText)`。
 */
fun parseThinkingContent(rawText: String): Pair<String?, String> {
    val startIndex = rawText.indexOf("<think>")
    if (startIndex < 0) return null to rawText
    val endIndex = rawText.indexOf("</think>", startIndex + 7)
    return if (endIndex >= 0) {
        val reasoning = rawText.substring(startIndex + 7, endIndex).trim().takeIf { it.isNotBlank() }
        val remaining = (rawText.substring(0, startIndex) + rawText.substring(endIndex + 8)).trim()
        reasoning to remaining
    } else {
        val reasoning = rawText.substring(startIndex + 7).trim().takeIf { it.isNotBlank() }
        val remaining = rawText.substring(0, startIndex).trim()
        reasoning to remaining
    }
}

/**
 * 文本里是否**有可见内容**（即"值得在界面上留一条消息"）。
 *
 * ## 为什么不能直接用 isNotBlank()
 *
 * Kotlin 的 isBlank() 只认 Char.isWhitespace()，而零宽字符族（U+200B 零宽空格、
 * U+200C 零宽非连接符、U+200D 零宽连接符、U+FEFF 字节序标记）都不在其中：
 * 一个只含 U+200B 的字符串，isNotBlank() 是 **true**。于是"只有零宽字符"的事件会被
 * 当成有内容的消息落库、落群 —— 真机反馈「部分消息还是显示空消息」就是它，
 * 库里那些 length=1 的文本正是 U+200B。
 *
 * ## 为什么住在模型层
 *
 * 写它的是**产消息**的一侧（桌面 DSH 桥 DesktopExtension），消费的却是**画消息**的一侧
 * （MessageBubble 的空气泡防线）。同一条判据在两处各写一遍迟早分叉 —— 本仓反复抓的
 * 就是这类"两把尺子"，所以只留这一份。
 */
fun hasVisibleText(text: String?): Boolean =
    text != null && text.replace(INVISIBLE_ONLY, "").isNotBlank()

/** 零宽字符族。显式剔掉才判得出"看不见"，见 [hasVisibleText]。 */
private val INVISIBLE_ONLY = Regex("[\\u200B\\u200C\\u200D\\uFEFF]")


/**
 * 屏幕协助历史卡的数据（茶话间 `ScreenHistoryCard.vue` 的 `ScreenRecord`）。
 *
 * 与 `core` 的 [com.kapp.tidings.core.protocol.ScreenSessionState] 是两件事，不能互相替代：
 * - `ScreenSessionState` 是**本次运行**的活会话（带 token/mode/fps，驱动接收器与发送器）；
 * - 这个只留**历史**要用的几个字段（谁发起的、走到哪个相位、为什么结束、三个时间戳），
 *   因为它要落盘、要跨重启、要作为一条聊天消息长期存在。
 *
 * **`v` 是给以后改结构用的**：茶话间 `parseScreenRecord` 只认 `v === 1`，认不出就整条作废
 * （退回纯文本渲染）。我们沿用同一个规矩 —— 版本对不上时宁可少一张卡，也不要把旧字段
 * 按新含义解释。
 *
 * 没有 `peerId`/`sessionId`：它们已经在**消息 id** 里（见 [screenHistoryMessageId]），
 * 再存一遍就是同一件事的第二个说法（茶话间 `ScreenRecord` 同样不存）。
 */
@Serializable
data class ScreenRecordUi(
    // **不许给 `v` 默认值**：`MessageStore.json` 没开 `encodeDefaults`，带默认值的字段
    // 编码时会被**整个丢掉** —— 于是自家写出去的 JSON 里没有 `v`，而解析端的
    // `v != SCREEN_RECORD_VERSION` 又靠默认值把它补成 1 ⇒ 那道版本闸对自家数据永远为真、
    // 缺 `v` 的坏数据也会被放行（独立验证者实测）。
    // 去掉默认值后：① 编码必写（必填字段不受 encodeDefaults 影响）；
    // ② 解析时缺 `v` 会抛 MissingFieldException ⇒ 被 runCatching 拦下、整条作废 ——
    // 这正是版本闸该有的行为。`MessageSnapshot.version` 反过来**要保留**默认值：
    // 那个字段要能读**旧版**messages.json（老文件里没有 `version`），去掉默认值会让
    // 老数据直接解析失败。
    val v: Int,
    val role: ScreenRole,
    val phase: ScreenPhase,
    val reason: String? = null,
    val requestedAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val durationMs: Long? = null,
) {
    /**
     * 终态。
     *
     * 茶话间的记录模型里没有 `Error` 相位（只有 `ended`），我们是后加的，所以「已结束」
     * 在这边必须同时含 [ScreenPhase.Error]：否则一条失败收场的历史卡永远被当成"还在进行中"，
     * 卡片会一直画着"连接中"（`ScreenSessionState.isTerminal` 的 KDoc 里有同一段教训）。
     */
    val isTerminal: Boolean get() = phase == ScreenPhase.Ended || phase == ScreenPhase.Error
}

/** 屏幕协助记录的当前结构版本。改字段含义时必须 +1（旧记录会整条作废、退回纯文本）。 */
const val SCREEN_RECORD_VERSION: Int = 1

/**
 * 这条记录在聊天流里的**消息 id**。
 *
 * 形如 `screen:<会话键>:<会话 id>`（确定性会话键规范 `screen:${peerId}:${sessionId}`）。必须是**确定性**
 * 的：每次相位变化都要 upsert 同一条，不能每次生成新 id（那会在聊天里留下一串半截记录）。
 * 会话 id 是 UUIDv4，所以跨会话、跨重启都不会撞。
 */
fun screenHistoryMessageId(peerId: String, sessionId: String): String = "screen:$peerId:$sessionId"

/** 活会话 → 可落盘的历史记录。 */
fun ScreenSessionState.toScreenRecordUi(): ScreenRecordUi = ScreenRecordUi(
    v = SCREEN_RECORD_VERSION,
    role = role,
    phase = phase,
    reason = reason,
    requestedAt = requestedAt,
    startedAt = startedAt,
    endedAt = endedAt,
    durationMs = durationMs,
)
