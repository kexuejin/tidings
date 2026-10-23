// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain.model

import kotlinx.serialization.Serializable
import com.kapp.tidings.core.protocol.AgentGrantAccess
import com.kapp.tidings.core.protocol.AgentGrantStates
import com.kapp.tidings.core.protocol.AgentSessionStates

/** PK 互动卡片 UI 模型。 */
@Serializable
data class PkCardUi(
    val game: String,
    val value: String,
)

/** Agent 审批请求 UI 模型（PPE-1 §6.5）。 */
@Serializable
data class AgentApprovalUi(
    val approvalId: String,
    val sessionId: String,
    val peerId: String,
    val tool: String? = null,
    val summary: String? = null,
    val expiresAt: Long = 0L,
    val choices: String? = null,
    val status: String = "pending",
    /** 风险等级：L1（只读放行）、L2（修改确认）、L3（高危指令审批）。 */
    val riskLevel: String? = null,
    /** 参数或文本差异变更（Diff / JSON 参数预览）。 */
    val diff: String? = null,
)

/** Agent 队列任务 UI 模型（PPE-1 §6.3）。 */
@Serializable
data class AgentQueueTaskUi(
    val taskId: String,
    val sessionId: String? = null,
    val peerId: String,
    val op: String,
    val prompt: String? = null,
    val queuedAt: Long = 0L,
)

/**
 * Agent 执行状态 UI 模型（PPE-1 §6.4 的 `agent-run`）。
 *
 * `state` 与 [AgentSessionUi.state] 同一纪律：**原样保留字符串**，不映射成枚举。
 * 协议只定义了 `running` / `succeeded` / `failed` / `cancelled` 四档，但对端将来多一档
 * 不该让整帧作废；[isTerminal] 只用来决定界面上要不要继续显示"进行中"。
 */
@Serializable
data class AgentRunUi(
    val runId: String,
    val sessionId: String,
    val peerId: String,
    val state: String,
    /** 对端自报的发生时刻（epoch ms）。 */
    val at: Long = 0L,
    /** 失败摘要（`failed` 时通常非空）。 */
    val note: String? = null,
    /** 本端**收到**这一帧的时刻，用于排序 —— 与 [at] 分开，理由同 [AgentEventUi.at]。 */
    val updatedAt: Long = 0,
) {
    /** 是否已到终态（不再变化）。 */
    val isTerminal: Boolean get() = state in TERMINAL_RUN_STATES

    companion object {
        val TERMINAL_RUN_STATES: Set<String> = setOf("succeeded", "failed", "cancelled")
    }
}

/**
 * Agent 产物元数据 UI 模型（PPE-1 §6.7 的 `agent-artifact`）。
 *
 * **只有元数据，没有二进制** —— 要拿到内容得靠 [fileId] 指向的那条 `file-msg`。
 */
@Serializable
data class AgentArtifactUi(
    val artifactId: String,
    val sessionId: String,
    val peerId: String,
    val fileId: String? = null,
    val name: String,
    val bytes: Long? = null,
    val mime: String? = null,
    /** 本端收到这一帧的时刻。 */
    val at: Long = 0,
)

/**
 * 共享文件柜中浏览的文件/目录项。
 */
@Serializable
data class CabinetEntryUi(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

/**
 * 共享文件柜 UI 状态。
 */
@Serializable
data class CabinetUiState(
    /** 当前正在查看的目标：null 表示「我的文件柜」，非 null 表示对应同事的 nodeId。 */
    val activePeerId: String? = null,
    /** 当前浏览路径，空串表示根目录。 */
    val currentPath: String = "",
    /** 当前目录下的条目列表。 */
    val entries: List<CabinetEntryUi> = emptyList(),
    /** 当前目录下的总条目数。 */
    val totalEntries: Int = 0,
    /** 对端开放给我的权限：off / read / write。 */
    val currentPerm: String = "read",
    /** 是否正在加载远端目录。 */
    val loading: Boolean = false,
    /** 加载错误信息（若有）。 */
    val errorMessage: String? = null,
    /** 已选中的文件名集合。 */
    val selectedNames: Set<String> = emptySet(),
    /** 视图模式："list" | "grid"。默认 "list"。 */
    val viewMode: String = "list",
)

/**
 * Agent 会话与事件的复合主键 (peerId, sessionId)。
 *
 * 协议 (PPE-1 §6) 与存储 (RelayDatabase.sq) 均按 (node, session) 二元组隔离。
 * 运行时切勿只用 sessionId 单键索引，否则跨 peer 存在同名 sessionId 时会引发
 * 会话覆盖、事件流交错及 cursor 去重误删缺陷。
 */
data class AgentSessionKey(
    val peerId: String,
    val sessionId: String,
)

/**
 * 一次 agent 执行的复合主键 `(peerId, runId)`（PPE-1 §6.4）。
 *
 * `runId` 由**各节点自行分配**，两个节点完全可能报同一个值 —— 与 `sessionId` 同理，
 * 单键索引会互相覆盖。
 */
data class AgentRunKey(
    val peerId: String,
    val runId: String,
)

/** 一件产物的复合主键 `(peerId, artifactId)`（PPE-1 §6.7）。理由同 [AgentRunKey]。 */
data class AgentArtifactKey(
    val peerId: String,
    val artifactId: String,
)

/**
 * 一个 agent 会话的可见投影（PPE-1 §6.1 的 `agent-session`）。
 *
 * `state` 保持**原样字符串**而不是映射成枚举：协议规定的新取值必须放行（`Codec` 的取值域
 * 故意不封闭），UI 只翻译它认识的那四个（`idle`/`busy`/`done`/`error`），其余照原样显示 ——
 * 把不认识的值塞进枚举，等于让一条合法帧在界面上变成"未知错误"。
 */
data class AgentSessionUi(
    /** 会话归属于哪个对端（一个 agent 节点可以有多个会话）。 */
    val peerId: String,
    val sessionId: String,
    val alias: String? = null,
    val agentKind: String? = null,
    val state: String? = null,
    val title: String? = null,
    val startedAt: Long? = null,
    /**
     * PPE-1 增补 D6：agent 实例身份（`agentId`）。null = 对端没报，身份退化为 `agentKind`，
     * **不猜**（父稿 §3.2）。同一节点上同类两个 agent 靠它分开。
     */
    val agentId: String? = null,
    /** PPE-1 增补 D1：工作区稳定 id；null = 未归属（合法的一等状态，不许编一个默认工作区）。 */
    val wsId: String? = null,
    /** PPE-1 增补 D1：工作区展示名；null = 未命名（UI 不拿 path 末段顶替）。 */
    val wsTitle: String? = null,
    /** PPE-1 增补 D1：工作区路径，仅详情与消歧用，不进卡片主位。 */
    val wsPath: String? = null,
    /** 本端最后一次收到该会话帧的时间（用于排序与"刚刚"）。 */
    val updatedAt: Long = 0,
    /**
     * **宿主侧**这条会话的活动时刻（epoch 毫秒），= `max(createdAt, lastPromptAt)`。
     *
     * null = 对端没报（老宿主、或这条帧走的是还不认识该尾键的路径）。
     *
     * ## 为什么不复用 [updatedAt]
     *
     * [updatedAt] 是本端**收到帧**的时刻，它回答的是"我这边什么时候听说的"，被两处**必须**
     * 保留该语义的东西消费：一是 `pruneEndedSessions` 的 TTL 与每 peer 上限淘汰（问的是
     * "这条陈旧记录还在我这儿躺了多久"），二是卡片上那句"刚刚"（问的是"我多久前看到它动"）。
     * 而排序要问的是"**在 DSH 里**它什么时候动过" —— 三件事，一个字段答不了。
     *
     * 最刺眼的差别：历史分页一页 20 条是同一个 TCP tick 里到的，[updatedAt] 全落在同一毫秒，
     * 排序退化成到达顺序；而用户的原话是「工作区列表上显示的会话 session…跟 dsh 上显示的
     * 不一样，不是最新的」。DSH 自己的口径是活动倒序
     * （`packages/api/session-controller/src/list.ts` 的
     * `items.sort((left, right) => right.updatedAt - left.updatedAt)`）。
     *
     * ## 纪律
     *
     * 单独立字段、不改 [updatedAt] 的语义，也**不进**界面上任何"多久前"的文案 ——
     * 与协议对 `agent-event.at` 的既有纪律同一条：只用于排序，不用于显示时间
     * （`docs/PROTOCOL-AGENT-WORKSPACE.md` §2.2）。
     */
    val hostUpdatedAt: Long? = null,
    /**
     * **宿主侧**这条会话的来源标记（`agent-session` 尾键 `origin`）；已知唯一取值 `"subagent"`。
     *
     * null = 对端没报（老宿主、或这条帧走的是还不认识该尾键的路径）——**不猜**，按可见处理。
     *
     * ## 为什么必须由宿主报、不能在手机上猜
     *
     * 子代理会话的标题就是那条 prompt 的首行（「Route this bounded MEMORY.md archive…」
     * 「You are doing a read-only…」），与用户手开会话长得一模一样，标题里没有任何标记可认。
     * 判断它的唯一权威是 DSH 自己的 `origin`（会话日志首行才有的键）——
     * 官方工作区浏览器 `packages/client/ui-workspace/src/client/tree.ts` 的 `sessionVisible()`
     * 一律隐藏这类会话。
     */
    val hostOrigin: String? = null,
    /**
     * **宿主侧**这条会话是不是"空壳"（建出来但一轮都没跑过，`agent-session` 尾键 `blank`）。
     *
     * 三态：`true` / `false` / `null`（对端没报，不猜）。
     *
     * DSH 自己的口径同样是 `blank`（`packages/api/session-controller/src/list.ts` 的
     * `applySessionListMetadata`：投影初值 `blank: true`，`turn/start` 才变 false），
     * 工作区浏览器对"不是当前会话的空壳"一律隐藏 —— 真机上它在手机上长成一行「未命名」，
     * 而那一行背后没有任何内容可看。
     */
    val hostBlank: Boolean? = null,
    /**
     * 是否真的收到过 `agent-session` 宣告。
     *
     * false = 只收到了事件流条目（会话宣告丢在离线期、或对端在宣告前就发了事件）。
     * 这时**不能**编一个会话名出来，界面上要如实说"只看到事件，没收到宣告"。
     */
    val announced: Boolean = true,
    /** 工作区关联的 Git 分支名称（如 "main", "dev"）。 */
    val gitBranch: String? = null,
    /** 会话分段 ID（重置 Prompt 上下文后开启新分段）。 */
    val segmentId: String? = null,
) {
    /**
     * 会话在界面上的主标题；**两级回落 `alias` → `title`，都没有就是 `null`**。
     *
     * 两级与 null 的语义是协议定死的：
     *   · `alias` —— 人给这个会话起的展示名（最该当标题的那个）；
     *   · `title` —— 这个会话在干的活（DSH 侧的首条 prompt 摘要）；
     *   · `null` —— 两者都没有。**这不是一个标题，是「没有标题」这件事本身**。
     *
     * ## 为什么最后一级不是 `sessionId`
     *
     * 避免直接暴露内部机器键，优先取 title 或 null（由界面回退至未命名文案）。
     * 还剩最后一级 `?? sessionId`：它把**身份键**当成了标题。
     *
     * 身份键不是名字。DSH 自己遇到这种会话时说的是「未命名」
     * （`packages/client/ui-workspace/src/client/rows/Rows.tsx` 的 `node.title || t('session.untitled')`，
     * 文案 `session.untitled` = 「未命名」），而我们是把 `session-bd998e9d-…` 这种机器键
     * 直接印给用户看。真相是：确实有一批会话从没有过 `session/title` 事件 —— 开了就结束、
     * 一句 prompt 都没发（`foldSessionTitle()` 折不出来，`readTitleSnapshots` 只剩 id）。
     * 对这批会话，「未命名」是**如实**的，「`session-bd998e9d…`」才是编的。
     *
     * 所以末级从 `sessionId` 改成 `null`，由**调用方**翻成本地化的「未命名」
     * （`Res.string.agent_session_untitled`）。模型层不许出文案：`model/` 包里没有
     * `@Composable`、也没有 `stringResource`，这里写「未命名」等于把中文钉进数据层。
     * 返回类型因此从 `String` 变成 `String?` —— **让编译器把 6 个调用点全逼出来**，
     * 而不是靠人记得再改一遍（这条规则已经被漏掉过一次了）。
     */
    val displayTitle: String?
        get() = alias?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
    /**
     * 排序用的活动时刻：优先 [hostUpdatedAt]（DSH 里什么时候动过），没有才退到 [updatedAt]。
     *
     * 兜底是刻意的、不是凑合：老宿主不发这个尾键（或这条帧走的是还不认识它的路径），
     * 那时"我能拿到的最好信息"就是收帧时刻 —— 它至少是时间的**下界**，
     * 比编一个 0 让全场条目并列要强。
     *
     * 为什么不直接排序 [hostUpdatedAt] 了事：`sortedByDescending` 拿 `Long?` 比的是
     * `null` 最小 —— 没报活动时刻的会话会被一律甩到列表末尾，而它可能恰恰是最新的那条。
     */
    val sortActivityAt: Long get() = hostUpdatedAt ?: updatedAt

    /** 是否属于已结束/终态（done / error / archived / closed）。 */
    val isEnded: Boolean get() = state in AgentSessionStates.ENDED

    /** 是否属于已归档或已关闭状态。 */
    val isArchived: Boolean get() = state == AgentSessionStates.ARCHIVED || state == AgentSessionStates.CLOSED

    /** 是否属于子代理会话（hostOrigin == "subagent" 或子代理/内部会话特征）。 */
    val isSubagent: Boolean
        get() = hostOrigin == AGENT_ORIGIN_SUBAGENT ||
            title?.startsWith("Route this bounded") == true ||
            (agentKind.equals("DSH", ignoreCase = true) && RAW_UUID_REGEX.matches(sessionId))
}

private val RAW_UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


/**
 * 按 DSH 自己的口径，从一批会话里挑出**该显示在手机上**的那些（PPE-1 可见性两键）。
 *
 * 判据来源是官方工作区浏览器的 `sessionVisible()`
 * （`packages/client/ui-workspace/src/client/tree.ts`）：
 *
 * `if (session.origin === "subagent") return false;`
 * `if (session.blank && session.id !== current) return false;`
 *
 * ## 与官方的一处刻意差异（必须写清楚，否则后来人会以为写错了）
 *
 * 官方第二句拿的是「当前会话」身份 —— **手机侧没有这个东西**：一台手机同时看着 N 个节点的
 * 会话，没有任何一条是「当前」。所以这里把「不是当前」换成「**同一工作区里还有别的可见会话**」：
 * 一个工作区全是空壳时照旧把它们显示出来，否则那个工作区整行会变成 0 个会话
 * （「看起来是空」比「多一行未命名」更糟，也与 `dsh-tidings` 的 `visibleOnPhone()` 同一取舍）。
 *
 * ## 为什么必须放在**展示层**而不是只拦入库
 *
 * 保证历史存量数据在界面上也能即时生效过滤，对齐视图口径。
 */
fun List<AgentSessionUi>.visibleInDsh(): List<AgentSessionUi> {
    val byWorkspace = groupBy { it.wsId }
    return filter { session ->
        // 归档会话默认隐藏（对齐 DSH 的 hide-archived 默认视图口径）
        if (session.isArchived) return@filter false
        if (session.isSubagent) return@filter false
        if (session.sessionId.startsWith("probe-")) return@filter false
        if (session.title?.startsWith("Route this bounded") == true) return@filter false
        if (session.agentKind.equals("DSH", ignoreCase = true) && RAW_UUID_REGEX.matches(session.sessionId)) return@filter false
        if (session.hostBlank != true) return@filter true
        // 空壳：整组**全是空壳**时才放行（与 dsh-tidings 的 visibleOnPhone 的
        // `siblings.every(other => other.blank)` 逐字同口径）。只要同工作区里还有一条
        // 非空壳（或还没听宿主说过 blank 的）会话，这条空壳就该藏起来。
        byWorkspace[session.wsId].orEmpty().all { it.hostBlank == true }
    }
}

/** `agent-session` 的 `origin` 目前唯一已知取值；取值域不封闭（协议同 `state`/`agentKind`）。 */
const val AGENT_ORIGIN_SUBAGENT: String = "subagent"

/**
 * 一条 agent 事件流条目（PPE-1 §6.2 的 `agent-event`）。
 *
 * [at] 是本端**收到**的时间，不是 [cursor]：协议明确 cursor 只是同一 (node,session) 内的
 * 单调游标（epoch ms 生成），拿它当时间戳显示会在两端时钟偏差下说谎。
 */
data class AgentEventUi(
    val sessionId: String,
    val peerId: String,
    val cursor: Long,
    val eventType: String,
    val summary: String? = null,
    val body: String? = null,
    val ref: String? = null,
    val at: Long,
    /**
     * 附件卡片模型（task-45，cap `att1`）。null ⇒ 退回现有的文本占位。
     *
     * 这里只带**元数据**：字节走既有文件传输路径。卡片的显示文案由
     * [agentAttachmentCaption] 这个纯函数算，UI 不自己拼字符串。
     */
    val attachment: AgentAttachmentUi? = null,
)

/** agent 事件的附件卡片模型（与 DSH 侧 content 块同口径，task-45）。 */
data class AgentAttachmentUi(
    val attachmentId: String,
    val mediaType: String,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long? = null,
    val name: String? = null,
)

/**
 * 图片类附件可以就地预览（决定卡片左侧图标与「能不能直接打开」）。
 *
 * **对外契约**：附件卡片（`feature/agents/AgentSessionWidgets` 与 `ui/components`）
 * 按它选图标与打开方式。判 MIME 前缀的规则只能有一份。
 */
fun agentAttachmentIsImage(attachment: AgentAttachmentUi): Boolean =
    attachment.mediaType.startsWith("image/")

/**
 * 卡片上那一行说明：**名字 · 尺寸 · 大小**（纯函数，便于单测）。
 *
 * 名字缺失时退回 MIME 的子类型（`image/png` → `png`）：卡片上总得有个名字，
 * 而"未知文件"这种话对用户毫无信息量。
 */
fun agentAttachmentCaption(attachment: AgentAttachmentUi): String {
    val parts = mutableListOf<String>()
    parts += attachment.name?.takeIf { it.isNotBlank() }
        ?: attachment.mediaType.substringAfter('/', attachment.mediaType)
    val width = attachment.width
    val height = attachment.height
    if (width != null && height != null && width > 0 && height > 0) parts += "$width×$height"
    attachment.bytes?.takeIf { it >= 0 }?.let { parts += humanBytes(it) }
    return parts.joinToString(" · ")
}

/** 人类可读的大小：B / KB / MB（KB 及以上保留一位小数，整数时不显示 .0）。 */
internal fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> formatOneDecimal(bytes / 1024.0, "KB")
    else -> formatOneDecimal(bytes / (1024.0 * 1024.0), "MB")
}

private fun formatOneDecimal(value: Double, unit: String): String {
    val rounded = kotlin.math.round(value * 10) / 10
    val whole = rounded.toLong()
    return if (rounded == whole.toDouble()) "$whole $unit" else "$rounded $unit"
}

/**
 * 一个被宣告的工作区的复合主键 `(peerId, agentKey, wsId)`（PPE-1 增补 D3，父稿 §1 的四层模型）。
 *
 * 为什么键里要带 **agentKey**：父稿 §2.1 明写 `wsId` 只在同一 `(nodeId, agentId ?? agentKind)`
 * 内唯一 —— 一台设备上两个 agent 各自叫 `ws-1` 的两个工作区是两个对象，
 * 单键索引会互相覆盖（与 [AgentSessionKey] 同一条理由）。
 *
 * `agentKey` = `agentId ?? agentKind`（父稿 D6）。**null 如实保留**：两个都缺时
 * 帧里确实没有任何身份依据（降级成「未上报类型」那一档），把它折成空串等于
 * 假装存在一个真实取值 —— 同 `WorkspaceUnitUi.agentKind` 的纪律。
 */
data class WorkspaceKey(
    val peerId: String,
    /** Agent 身份键 = `agentId ?? agentKind`；null = 对端两个都没报，不猜。 */
    val agentKey: String?,
    /** 工作区稳定 id；协议必填（`wsId` ≤64），所以这里非空。 */
    val wsId: String,
)

/**
 * 一条 `agent-workspace` 宣告的可见投影（PPE-1 增补 D3，父稿 §2.2）。
 *
 * 它存在的唯一理由：覆盖「工作区已经存在、但还没有任何会话」这一档 ——
 * `agent-session` 的工作区键只到"会话带着工作区身份来"，空工作区只能靠这帧
 * 显式宣告。界面怎么用它见 `inboxWorkspaceUnits()`（并集派生）。
 */
data class WorkspaceUi(
    /** 这条宣告来自哪个节点（= 哪台设备）。 */
    val peerId: String,
    /** 归属的 Agent 身份键 = `agentId ?? agentKind`；null = 对端没报，如实保留（不猜）。 */
    val agentKey: String?,
    /**
     * D6 的 agent 实例身份原值（`agentId`）；null = 对端没报。
     *
     * 与 [agentKey] 分开存是为了**改名可迁移**：宿主换 agentId 时它是同一个实例换了名字，
     * 归组键要跟着新值走（[agentKey] 是派生物），而合并 prev 时旧值还要读得回来。
     */
    val agentId: String? = null,
    val wsId: String,
    /**
     * 工作区展示名；null = **未命名**（对端没报 `wsTitle`）。
     *
     * UI 不许拿 `wsPath` 末段、host 冒充名字（父稿 §0「不做」清单）；空串按未命名处理，
     * 所以这里存的是已归一后的值：收到空串即 null。
     */
    val wsTitle: String?,
    /**
     * 工作区路径；null = 未知。只进详情行，不进卡片主位（父稿 §0 的隐私纪律）。
     *
     * 与 `wsTitle` 相反：路径是"机器话"，空串没有意义，所以按 null 归一。
     */
    val wsPath: String?,
    /**
     * 该工作区的**主要** agent 宿主/驱动类型（开放取值，`DSH`/`opencode`/`codex`…，
     * **不是模型名**）；null = 没报。零会话时这一枚就是类型徽标的唯一来源（父稿 §3.1）。
     */
    val agentKind: String?,
    /** 宿主自报的"此刻在不在这个工作区里活着"；null = 没报（**不等于** false：沉默 ≠ 离线）。 */
    val online: Boolean?,
    /**
     * 宿主自报的会话数；null = 没报。
     *
     * 界面显示时**本端自己数的优先**（父稿 §2.2 原话），所以这一格只在完全没有会话行时
     * 才被当"对端说有 N 个"的提示用，不参与行数计算。
     */
    val sessionCount: Long?,
    /**
     * 本端**收到**这一帧的时刻，用于排序与显示（与 [AgentEventUi.at] 同纪律）。
     *
     * 帧里的 `at` 是**宿主时钟**，只用于排序、不用于显示 —— 两端时钟有偏差时，
     * 拿宿主时间印"几点"会在用户自己的手机上说谎。所以这里存的是收到时刻。
     */
    val at: Long,
    /**
     * DSH 注册表里的**持久顺序**（0 起，越小越靠前）；null = 对端没报。
     *
     * 为什么必须显式送而不是靠到达序：DSH 的工作区分组顺序是**用户可手工拖动的**一份
     * 台账（`packages/api/workspace-controller/src/commands.ts` 的 `insertBefore`），
     * 与"最近活动"无关；而手机落库后 `selectAllAgentWorkspaces` 是 `ORDER BY updated_at DESC`
     * —— 到达序在重启后就被打乱了，只靠它复现不了用户定的顺序。
     *
     * 与 [at] 同纪律：**只用于排序，不用于显示时间**。
     */
    val hostOrder: Long? = null,
)

/**
 * 一条授权状态（PPE-1 增补 D7，父稿 §3.3）。宿主 → 设备的 `agent-grant` / `agent-grant-list` 的投影。
 *
 * `state` 与 `scope` 保持**原样字符串**（与 [AgentRunUi.state] 同一纪律）：本端 codec
 * 对这两组取值按封闭集合校验（`AgentGrantStates.ALL` / `AgentGrantScopes.VALUES`），
 * 但对端实现可能先行扩一档；把不认识的取值塞进枚举 = 一条合法帧在界面上变成"未知错误"。
 */
data class GrantUi(
    /** 这条授权关于哪个宿主节点（= 谁的 agent 我能看）。 */
    val peerId: String,
    /**
     * 授权到单个 agent 时的身份键 = `agentId ?? agentKind`；
     * null = **整台设备**（`scope = "all"` 那一档没有 agentKey）。
     */
    val agentKey: String?,
    /** `all` | `agent` | `workspace`（原样保留，理由见类 KDoc）。 */
    val scope: String,
    /** `approved` | `denied` | `revoked`（原样保留）。 */
    val state: String,
    /** 本端收到这一刻（宿主 `at` 只用于排序，不显示 —— 同 [WorkspaceUi.at]）。 */
    val at: Long,
    /** 工作区范围时的 wsId；null = 非工作区范围（或全工作区）。 */
    val wsId: String? = null,
    /** 访问权限：ro (只读) | rw (读写) | admin (管理员) | owner (群主)。默认 rw。 */
    val access: String = AgentGrantAccess.RW,
    /** 是否属于我的设备（私有信任圈）。 */
    val isMyDevice: Boolean = false,
    /** 是否已开启高危终端执行委托审批。 */
    val delegatedApproval: Boolean = false,
)

/**
 * 手机端对一个宿主节点的申请状态（PPE-1 增补 D7）。
 *
 * **四档都必须能被表达**（父稿 §3.3 硬要求）：`NONE` 与 `DENIED` 绝不能合并 ——
 * 「沉默 ≠ 拒绝」。合并之后界面上只剩三档，用户看到"已拒绝"会去问一个从来没回答过
 * 他的人；而 `auto` 档的宿主根本不会发 grant 帧，那一档就是 `NONE`。
 * `PENDING` 同理：它是「我已经发了申请、宿主还没表态」，与前后两档都不同。
 *
 * 落点：内存态（重启退回 `NONE`），理由见 `RelayControllerAgentComponent.handleSendGrantRequest`。
 */
enum class GrantRequestStatus {
    /** 从来没申请过，也没收到任何授权帧（= 沉默，**不是**"没权限"的结论）。 */
    NONE,

    /** 已发出 `agent-grant-request`，等宿主表态。 */
    PENDING,

    /** 收到 `state = "approved"`（`agent-grant-list` 的条目也按这一档处理）。 */
    GRANTED,

    /** 收到 `state = "denied"`（宿主明确拒绝过这次申请）。 */
    DENIED,
}

/**
 * 把 `agent-grant` 的 `state` 字符串折算成界面上的申请状态档位（[GrantRequestStatus]）。
 *
 * `revoked` 走 [GrantRequestStatus.NONE]，**不是** DENIED：撤销说的是"过去授权过、现在收回"，
 * 显示成「已拒绝」是在说一件没发生过的事（宿主确实批过一次）。四档里没有第五档，
 * 而界面对"现在不生效"能做的判断只有一个 —— 申请入口可以再点一次，那正是 NONE 的动作语义。
 * 要区分"被撤回过"与"从没申请过"，读 [GrantUi.state] 的原样字符串就够了：枚举管动作，字符串管历史。
 *
 * 认不出的取值退回 [GrantRequestStatus.NONE]（沉默）而不是编一个结论 —— 与父稿 §3.3
 * 「沉默 ≠ 拒绝」同一条纪律：一条我们看不懂的帧不该产出任何一个带判断的徽标。
 */
fun grantStateToStatus(state: String?): GrantRequestStatus = when (state) {
    AgentGrantStates.APPROVED -> GrantRequestStatus.GRANTED
    AgentGrantStates.DENIED -> GrantRequestStatus.DENIED
    else -> GrantRequestStatus.NONE
}

/** 会话行：由"有消息往来或已在节点表里"的对端聚合出来。 */
data class ConversationUi(
    /**
     * 会话键：私聊 = 对方 nodeId，群聊 = `group:<groupId>`。
     *
     * 以前叫 `peerId`。改名是因为它现在的取值域里有一半**不是节点 id** ——
     * 一个叫 `peerId` 却可能装着 `group:xxx` 的字段，正是这个仓库反复抓的那类谎话。
     */
    val convId: String,
    val title: String,
    /** null 表示“还没聊过”；文案由 UI 层翻译，不在这里写死中文。 */
    val preview: String?,
    /**
     * 会话行右侧那一格"最近时间"的**兼容兜底值**：有消息时 = [lastMessageAt]，
     * 没消息时 = [presenceAt]。
     *
     * 为什么还留着一个语义混着的字段：桌面会话面板（`ui/desktop/DesktopConversationWidgets.kt`）
     * 直接 `relativeTime(conv.at, now)` 且**没有 0 守卫**，把它改成 0 会把"还没聊过"印成
     * 1970 年。手机端的 `ConversationRow` 已改读 [lastMessageAt]，所以**新代码不要再用它
     * 判断"有没有聊过"** —— 那正是"没聊过的在线对端被印成刚刚"的旧口径。
     */
    val at: Long,
    val unread: Int,
    val isAgent: Boolean,
    /**
     * 群聊恒为 false，且**不代表"这个群没人"**：群没有单一的在线状态，
     * 这里回答的是"这个会话是不是一个节点"。群的活跃度看 [memberOnlineCount]。
     */
    val online: Boolean,
    val pendingCount: Int,
    /** 这是一个群会话（成员表在 `settings.groups` 里）。 */
    val isGroup: Boolean = false,
    /**
     * 群成员里当前在线的人数（**不含自己**）。非群会话恒为 0。
     *
     * 存在的理由：群行上如果直接复用 [online] 那个圆点，它只能表达"是/否"，
     * 而一个群"5 个人里 2 个在线"和"5 个人全离线"是完全不同的处境。
     */
    val memberOnlineCount: Int = 0,
    /** 群成员总数（含自己）。非群会话恒为 0。 */
    val memberCount: Int = 0,
    /** 对端头像（仅对端为真实节点时有效，群为 null）。 */
    val avatar: Int? = null,
    /**
     * 最近一条消息的时间；**0 = 这个会话一条消息都没有**（"还没聊过"）。
     *
     * 会话排序的第一把尺子（有消息的排在没消息的前面，组内按它倒序），也是手机端
     * 决定"要不要印时间"的判据。单独一格而不是复用 [at]：`at` 里混着对端的
     * `lastSeenAt`，而局域网发现会持续刷新它 —— 混用正是"从没聊过被当成刚刚"的根源。
     */
    val lastMessageAt: Long = 0L,
    /**
     * 对端的最后在线时间（群取 `updatedTs`，协议里没有"群最后在线"）；**0 = 不知道**。
     *
     * 只在 [lastMessageAt] == 0 的那一档里当排序尺子：没聊过的会话不能因为对端在线
     * 就冒到有消息的会话前面，但它们之间仍要有个说得通的先后（最近还在线的靠前）。
     * 也是 [at] 在没有消息时的兜底来源。
     */
    val presenceAt: Long = 0L,
    /**
     * 最后一条消息是不是**自己发的**。
     *
     * 用途只有一个：会话行的预览要区分「他说的」与「我说的」—— 后者在列表里加「你：」前缀。
     * 不判断这一点时，用户看到一句熟悉的话，分不清是自己说的还是对方说的。
     *
     * **只用来看"这是谁的话"**，不参与排序、不参与未读判定。
     */
    val lastMessageIsMine: Boolean = false,
    /**
     * 这一行被**置顶**（用户手动钉在列表最上面）。纯本机偏好，不进 wire。
     *
     * 排序口径见 `orderInboxFeed`：置顶优先于「有未处理」和「最近活动」——
     * 用户钉它上去的意义就是"不管多旧、多安静，都在最上面"。
     */
    val isPinned: Boolean = false,
    /** 这一行被**静音**：消息照收，但不产生通知。 */
    val isMuted: Boolean = false,
)

/**
 * 屏幕协助客户端/服务端当前会话 UI 状态。
 */
data class ScreenSessionUiState(
    val session: com.kapp.tidings.core.protocol.ScreenSessionState? = null,
    val latestFrameBytes: ByteArray? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameSeq: Long = 0,
    val isSharingScreen: Boolean = false,
)


/**
 * 网段探测进度条 UI 状态。
 */
data class ScanProgressUi(
    val currentCidr: String,
    val scannedHosts: Int,
    val totalHosts: Int,
    val discoveredCount: Int = 0,
)
