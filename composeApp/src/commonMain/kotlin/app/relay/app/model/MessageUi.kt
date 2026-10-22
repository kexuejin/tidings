// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app.model

import app.relay.core.protocol.ScreenPhase
import app.relay.core.protocol.ScreenRole
import app.relay.core.protocol.ScreenSessionState
import kotlinx.serialization.Serializable
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
     * （见 `app.relay.core.protocol.ConvId`）。
     *
     * 名字仍是 `peerId` 是历史原因，但它现在的取值域已经是"会话键"。改名会牵动
     * `messages.json` 的键与几十处调用点，收益只是名字好看，所以留着；
     * **判断群聊要用 `ConvId.isGroup(peerId)`，不要靠这个字段名去猜语义**。
     */
    val peerId: String,
    val mine: Boolean,
    val text: String,
    val at: Long,
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
     * 聊天流里那条「任务：xxx / 新会话」分段线原来靠 `id.startsWith("agent-ev-")` 判，
     * 而那个前缀只有**桌面端本地构造消息**时才有；转发进群时没带过去，手机端落库用信封 UUID，
     * 于是 `extractSessionId` 恒 null —— 桌面有分段线、手机一条都没有。
     *
     * 会话归属是**事实**，由协议携带（`agentSessionId` + `agentCursor`），
     * 不再靠 id 字符串猜。null = 这条消息不属于任何 agent 会话（普通聊天）。
     */
    val agentSessionId: String? = null,
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
 * 与 `core` 的 [app.relay.core.protocol.ScreenSessionState] 是两件事，不能互相替代：
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
