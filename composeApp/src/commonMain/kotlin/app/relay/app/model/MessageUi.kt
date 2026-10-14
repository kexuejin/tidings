// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app.model

import app.relay.core.protocol.ScreenPhase
import app.relay.core.protocol.ScreenRole
import app.relay.core.protocol.ScreenSessionState
import kotlinx.serialization.Serializable
enum class DeliveryState { Sent, Queued, Failed, Recalled }

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
)

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
