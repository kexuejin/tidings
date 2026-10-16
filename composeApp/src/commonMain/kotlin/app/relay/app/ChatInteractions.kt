// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.core.protocol.Envelope
import app.relay.core.protocol.FilePurpose
import app.relay.core.protocol.MsgKinds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.relay.app.model.AgentEventUi
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.GrantUi
import app.relay.app.model.WorkspaceUi
import app.relay.core.protocol.AgentGrantStates
import app.relay.app.model.MessageUi
import app.relay.app.model.TransferState

/**
 * 撤回与戳一碰的**纯规则**：不碰状态、不碰网络，所以能被单测钉住。
 *
 * 这两条协议规则（撤回窗口、戳一碰限流）都同时出现在两个地方 —— 发送侧决定"能不能做"，
 * 接收侧决定"要不要理"。写在同一个函数里，是为了让两侧永远不可能漂移成两套数字。
 */

/**
 * 撤回窗口：5 分钟。
 *
 * 上游协议 §8：「自己的文本/群文本/PK/图片/文件消息在 **5 分钟内**可发
 * `msg(kind:"recall", targetId)`」。窗口是**发送侧**约束 —— 收端只校验"撤回者＝原消息发送者"
 * 与会话是否匹配，不拿本机时钟去卡（两端时钟本来就可能有偏差，卡了就是把能撤回的说成不能）。
 */
internal const val RECALL_WINDOW_MS: Long = 5 * 60_000L

/**
 * 这条消息现在还能不能撤回（发送侧判据）。
 *
 * 文本与媒体都放行，但**媒体要求我们发出的 `file-ctl offer` 带过 `msgId`**
 * （协议 §3）：对端只认那个 id，没带就没有 id 可报 —— 给一个按下去什么都不会
 * 发生的按钮比不给按钮更糟（见 [recallTargetIdOf]）。
 *
 * [MessageUi.notice] 非空的是系统提示行，不是消息，也不能撤回。
 */
internal fun canRecall(message: MessageUi, now: Long): Boolean {
    if (!message.mine) return false
    if (message.notice != null) return false
    if (recallTargetIdOf(message) == null) return false
    // 文本消息要有正文；文件消息的正文本来就是空的（内容在卡片上，见 upsertTransferCard）。
    if (message.file == null && message.text.isBlank()) return false
    return now - message.at in 0..RECALL_WINDOW_MS
}

/**
 * 撤回这条消息时该报哪个 id。
 *
 * 文本消息报信封 id（[MessageUi.id] 就是它）。媒体消息报**我们发出的 `file-ctl offer` 的
 * `msgId`** —— 协议 §3 要求收发两端用同一个 id 把这条消息入库，对端只认那个。
 * 返回 `null` = 这条消息没有可报的 id（媒体消息的 offer 没带 `msgId`），不该发撤回。
 */
internal fun recallTargetIdOf(message: MessageUi): String? {
    val file = message.file ?: return message.id
    return file.msgId
}

/**
 * 同一条消息的**媒体撤回**该不该被接受（接收侧判据）。
 *
 * 协议 §8：图片可在窗口内撤回并隐藏；**普通文件仅在本端尚未完成接收时**接受撤回
 * （收端要取消进行中的 TCP 拉取并清理 `.part`），已完成保存的文件必须忽略迟到撤回。
 */
internal fun acceptsMediaRecall(purpose: String?, state: TransferState): Boolean =
    purpose == FilePurpose.IMAGE || state != TransferState.Completed

/**
 * 戳一碰限流：**同一对端** 60 秒最多 2 次，且任意两次至少间隔 15 秒。
 *
 * 协议 §8要求**收发两端**都按同一组数字限流：发送侧挡在自己这里（用户会看到
 * 「戳得太勤」），接收侧超限时仍然回 ACK（那是 core 的事，它不知道限流），只是不产生本地动作。
 *
 * 时间由调用方传进来（`nowMillis()`），不是在这里读时钟：单测要能拨表。
 */
internal class NudgeThrottle(
    private val maxPerWindow: Int = MAX_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
    private val minGapMs: Long = MIN_GAP_MS,
) {
    private val recent = mutableMapOf<String, MutableList<Long>>()

    /** true = 放行，并记下这一次；false = 被挡下，且**不**记账。 */
    fun allow(peerId: String, now: Long): Boolean {
        val times = recent.getOrPut(peerId) { mutableListOf() }
        times.removeAll { now - it >= windowMs }
        val last = times.lastOrNull()
        if (last != null && now - last < minGapMs) return false
        if (times.size >= maxPerWindow) return false
        times += now
        return true
    }

    companion object {
        const val MAX_PER_WINDOW = 2
        const val WINDOW_MS = 60_000L
        const val MIN_GAP_MS = 15_000L
    }
}

/** 信封载荷里的 `kind`（只有 `msg` 有）。非对象载荷返回 null。 */
internal fun msgKindOf(envelope: Envelope): String? = payloadString(envelope, "kind")

/** 撤回指令的 `targetId`。 */
internal fun recallTargetOf(envelope: Envelope): String? =
    payloadString(envelope, "targetId")?.takeIf { msgKindOf(envelope) == MsgKinds.RECALL }

/**
 * 取载荷里的一个字符串字段。
 *
 * 只认 `JsonPrimitive.isString`：JSON 里 `{"targetId":123}` 在宽松解析下会变成字符串 `"123"`，
 * 那会让一条畸形撤回指令命中一个碰巧叫 `123` 的消息 id。宁可丢掉，不可猜。
 */
private fun payloadString(envelope: Envelope, key: String): String? {
    val payload = envelope.payload as? JsonObject ?: return null
    val value = payload[key] as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

/** 载荷里取字符串字段，只认 JSON 字符串（理由同 [payloadString]）。 */
internal fun stringField(payload: JsonObject, key: String): String? {
    val value = payload[key] as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

/**
 * 载荷里取整数字段。
 *
 * 先排除字符串再 `toLongOrNull`：`JsonPrimitive.content.toLongOrNull()` 会把 `"42"` 也读成
 * 42，而协议里 `{"cursor":"42"}` 是坏载荷（`Codec` 会拒）—— 两边必须给同一个结论。
 */
internal fun longField(payload: JsonObject, key: String): Long? {
    val value = payload[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    return value.content.toLongOrNull()
}

/**
 * 载荷里取布尔字段，只认 JSON 布尔。
 *
 * 与 [longField] 同一条纪律：`{"blank":"true"}` 是坏载荷（`Codec` 的 `boolOf` 会拒），
 * 这里不能因为 `"true"` 长得像就把帧收下来 —— 两边必须给同一个结论。
 */
internal fun boolField(payload: JsonObject, key: String): Boolean? {
    val value = payload[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    return when (value.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/**
 * 解一条 `agent-session`（PPE-1 §6.1），并把上一次的会话合进来。
 *
 * 字段缺失时**保留上一次的值**：协议里 `alias`/`agentKind`/`state`/`title`/`startedAt` 全是
 * 可选的，一条只报 `state` 的状态变更帧不该把 `alias` 抹掉；显式传空串则视为清空。
 *
 * 形状（白名单、必填项、类型）由 `Codec.validateAgentSession` 把关；这里只负责
 * "缺 `sessionId` 就整帧不要"，因为它是唯一必填的定位键。
 */
internal fun agentSessionFrom(
    payload: JsonObject,
    peerId: String,
    at: Long,
    prev: AgentSessionUi?,
): AgentSessionUi? {
    val sessionId = stringField(payload, "sessionId") ?: return null

    fun text(key: String, previous: String?): String? =
        if (key in payload) stringField(payload, key)?.takeIf { it.isNotBlank() } else previous

    return AgentSessionUi(
        peerId = peerId,
        sessionId = sessionId,
        alias = text("alias", prev?.alias),
        agentKind = text("agentKind", prev?.agentKind),
        state = text("state", prev?.state),
        title = text("title", prev?.title),
        startedAt = if ("startedAt" in payload) longField(payload, "startedAt") else prev?.startedAt,
        agentId = text("agentId", prev?.agentId),
        wsId = text("wsId", prev?.wsId),
        wsTitle = text("wsTitle", prev?.wsTitle),
        // 父稿 §2.1 的归组纪律：一条**裸 wsPath**（同帧里 wsId 与 wsTitle 双缺）被忽略 ——
        // 无 id 无法归组，路径不许变成归组依据。忽略只作用于**入站的那一个键**：
        // 后续不带 wsPath 的帧（比如只更新 state 的收尾帧）仍走合并、保留已存的值。
        // 第一版在这里写成了 `if (双缺) null else text(...)`，收尾帧把已落库的 wsPath
        // 抹成了空 —— 端到端验证（verify-dsh-workspace-session.py 第六步）当场判红。
        wsPath = if ("wsPath" in payload && "wsId" !in payload && "wsTitle" !in payload)
            prev?.wsPath
        else text("wsPath", prev?.wsPath),
        updatedAt = at,
        // 线上尾键 `updatedAt`（宿主侧活动时刻）与 `startedAt` **逐字同形**：键在场才动、
        // 缺键保留 prev、显式 null 视为清空。两处刻意写成同一个形状，是因为它们语义同档
        // （都属于"会话自身的事实"，不是工作区身份，所以不受 wsId 归组那套规则影响）。
        //
        // 与 `updatedAt = at` 相邻是刻意的对照：上面那个是**本端收帧时刻**，这个是从对端
        // 抄来的**宿主动作时刻**。名字像、来源完全不同，混用会让"刚刚"和"最新活动"各说各话。
        // 排序用后者（DSH 的活动倒序），TTL 与"刚刚"用前者。
        hostUpdatedAt = if ("updatedAt" in payload) longField(payload, "updatedAt") else prev?.hostUpdatedAt,
        // PPE-1 可见性两键（`origin` / `blank`），与上面两个尾键**逐字同形**：键在场才动、
        // 缺键保留 prev、显式 null 视为清空。
        //
        // 为什么缺键必须保留 prev 而不是清成 null：可见性目前**只由分页那条路送**
        // （实时宣告链路在宿主侧就把子代理挡掉了，不发这两键）。若把"没带"当成"可见"，
        // 一条只更新 state 的收尾帧就会把已经判定为子代理的会话重新放回界面上。
        hostOrigin = if ("origin" in payload) stringField(payload, "origin") else prev?.hostOrigin,
        hostBlank = if ("blank" in payload) boolField(payload, "blank") else prev?.hostBlank,
        announced = true,
        gitBranch = text("gitBranch", prev?.gitBranch),
        segmentId = text("segmentId", prev?.segmentId),
    )
}

/**
 * 解一条 `agent-workspace`（PPE-1 增补 D3，父稿 §2.2），并把上一次的那行合进来。
 *
 * 合并纪律与 [agentSessionFrom] 逐字同口径：**键在场且非空才覆盖，键缺失保留 prev**；
 * 显式传空串的 `wsTitle` 视为"改名成未命名"（协议允许 `isStrAllowEmpty`，
 * 而 `wsTitle = ""` 在产品语义里就是没有名字 —— 见 `WorkspaceUi.wsTitle`）。
 *
 * 身份键 = `agentId ?? agentKind`，**与会话侧 [agentSessionFrom] 同一判据**。
 * 两侧判据必须逐字相同：避免同一个工作区在界面上被拆成两行。
 *
 * D3 帧里**有** `agentId`（见 core 的 AGENT_WORKSPACE_EXPECTED_KEYS）—— 父稿 §2.2 的键表漏了它，
 * 随实现补上。缺的那一半如实缺失：帧没带 `agentId` 时不许拿 `agentKind` 冒充 id，
 * 也就是这一路不做"猜"，只做"同一帧里两个键按同一顺序取"。
 *
 * 帧里的 `at` 是宿主时钟，父稿明写「只用于排序，不用于显示」，所以这里**不读它**：
 * [WorkspaceUi.at] 存的是本端收到时刻（与 [agentEventFrom] 的 `at` 同一纪律）。
 * 读它会做出一件界面上无法自证的事 —— 用户在自己手机上看到"10:03"，而那是对面机器的钟。
 */
internal fun workspaceFrom(
    payload: JsonObject,
    peerId: String,
    at: Long,
    prev: WorkspaceUi?,
): WorkspaceUi? {
    val wsId = stringField(payload, "wsId")?.takeIf { it.isNotBlank() } ?: return null

    fun text(key: String, previous: String?): String? =
        if (key in payload) stringField(payload, key)?.takeIf { it.isNotBlank() } else previous

    // `online` 与 `sessionCount` 是"宿主自报的事实"，形状由 codec 卡死（布尔 / 非负整数）。
    // 这里只在**键缺失**时保留 prev：宿主报 false 就是 false，不能因为上一帧报过 true 就不改。
    val online = if ("online" in payload) booleanField(payload, "online") else prev?.online
    // 归组键 = agentId ?? agentKind：必须与会话侧同判据，避免工作区拆行。
    val agentId = text("agentId", prev?.agentId)
    val agentKind = text("agentKind", prev?.agentKind)
    return WorkspaceUi(
        peerId = peerId,
        agentKey = agentId ?: agentKind,
        agentId = agentId,
        wsId = wsId,
        wsTitle = text("wsTitle", prev?.wsTitle),
        wsPath = text("wsPath", prev?.wsPath),
        agentKind = agentKind,
        online = online,
        sessionCount = if ("sessionCount" in payload) longField(payload, "sessionCount") else prev?.sessionCount,
        at = at,
        // 宿主序只在**键在场**时动（与 sessionCount 逐字同形）：缺键 = 这一帧没带顺序，
        // 保留上一帧的值；宿主端的会话触发路（announceWorkspace）拿不到 DSH 台账时
        // 刻意不带这个键，正是为了不要用 0 覆盖掉周期路已经送来的真顺序。
        hostOrder = if ("order" in payload) longField(payload, "order") else prev?.hostOrder,
    )
}

/**
 * 解一条 `agent-grant`（PPE-1 增补 D7，宿主 → 设备）。
 *
 * `state` / `scope` **原样透传**：本端 codec 按封闭集合校验（`AgentGrantStates.ALL` /
 * `AgentGrantScopes.VALUES`），但这里不做二次归一 —— 界面对不认识的取值要照原样显示，
 * 而不是把它变成"未知错误"（[agentSessionFrom] 的 `state` 同纪律）。
 *
 * `agentKey` 缺失 = 这一条说的是**整台设备**（`scope = "all"`），null 如实保留：
 * 折成空串会让"整台设备"与"一个 id 恰好是空串的 agent"看起来是同一件事。
 */
internal fun grantFrom(payload: JsonObject, peerId: String, at: Long): GrantUi? {
    val state = stringField(payload, "state") ?: return null
    val scope = stringField(payload, "scope") ?: return null
    return GrantUi(
        peerId = peerId,
        agentKey = stringField(payload, "agentKey")?.takeIf { it.isNotBlank() },
        scope = scope,
        state = state,
        at = at,
    )
}

/**
 * 解一条 `agent-grant-list` 条目（PPE-1 增补 D7），把它读成"已生效的授权"。
 *
 * 为什么 state 直接当 `approved`（帧里根本没有 state 键）：`agent-grant-list` 的语义是
 * 宿主把**当前生效**的授权逐条回给设备（父稿 §3.3「让手机端能显示'我被授权看哪些'」）——
 * 被拒的、被撤的都不在清单里。于是清单里的一条 = 一条 approved；把它读成
 * "状态未知"会让人对着一份已经批下来的清单去再发一次申请。
 *
 * 反过来这也划出边界：**没在清单里出现 ≠ 被拒**（父稿 §3.3 沉默 ≠ 拒绝），
 * 那一档是 `GrantRequestStatus.NONE`，由 `RelayUiState.agentGrants` 里查不到来表达。
 */
internal fun grantListEntryFrom(payload: JsonObject, peerId: String, at: Long): GrantUi? {
    val scope = stringField(payload, "scope") ?: return null
    return GrantUi(
        peerId = peerId,
        agentKey = stringField(payload, "agentKey")?.takeIf { it.isNotBlank() },
        scope = scope,
        state = AgentGrantStates.APPROVED,
        at = at,
    )
}

/**
 * 载荷里取布尔字段，只认 JSON 布尔字面量。
 *
 * 与 [longField] 同一道闸：`{"online":"true"}` 是坏载荷（本端 codec 会拒），
 * 这里必须给同一个结论 —— 宽松收下就会出现"两边对同一帧的判断不一样"。
 */
internal fun booleanField(payload: JsonObject, key: String): Boolean? {
    val value = payload[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    return when (value.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/**
 * 解一条 `agent-event`（PPE-1 §6.2）。
 *
 * [at] 是本端**收到**的时间，不是 `cursor`：协议明确 cursor 只是同一 (node,session) 内的单调
 * 游标（epoch ms 生成），拿它当时间戳显示会在两端时钟偏差下说谎。
 */
internal fun agentEventFrom(payload: JsonObject, peerId: String, at: Long): AgentEventUi? {
    val sessionId = stringField(payload, "sessionId") ?: return null
    val cursor = longField(payload, "cursor") ?: return null
    val eventType = stringField(payload, "eventType") ?: return null
    return AgentEventUi(
        sessionId = sessionId,
        peerId = peerId,
        cursor = cursor,
        eventType = eventType,
        summary = stringField(payload, "summary")?.takeIf { it.isNotBlank() },
        body = stringField(payload, "body")?.takeIf { it.isNotBlank() },
        ref = stringField(payload, "ref")?.takeIf { it.isNotBlank() },
        at = at,
    )
}

/**
 * 把一条事件并进会话的事件流：**按 cursor 去重 → 升序 → 只留最近 [max] 条**。
 *
 * 返回 null 表示这条是重传，整条丢弃。传输层的去重表按信封 `id` 去重，对端换一个 `id`
 * 重发同一条事件（或重连后从头补发）时只有这里挡得住，否则事件流会越长越花。
 */
internal fun mergeAgentEvent(
    existing: List<AgentEventUi>,
    incoming: AgentEventUi,
    max: Int,
): List<AgentEventUi>? {
    if (existing.any { it.cursor == incoming.cursor }) return null
    return (existing + incoming).sortedBy { it.cursor }.takeLast(max)
}
