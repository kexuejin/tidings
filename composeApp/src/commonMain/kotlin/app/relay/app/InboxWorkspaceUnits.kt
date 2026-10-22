// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.relay.app.model.AgentSessionKey
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.ConversationUi
import app.relay.app.model.PeerUi
import app.relay.core.nowMillis
/**
 * 消息页签「工作区」段的取数。
 *
 * ## 为什么单独一个文件，而不是写进 `RelayUiState.kt`
 *
 * 设计稿 §8 原本写的是「加在 `RelayUiState.kt`」。那一版定稿时它已经 1629 行
 * （`prototype-to-compose` 的硬红线是 700 行），再往里加代码等于把一个已经超线两倍的文件
 * 继续加长。所以这一份**派生**放在同包的新文件里：`RelayUiState` 一个字段都不动，
 * 语义（一次派生、一次排序、一次裁剪）与设计稿完全一致。
 *
 * ## 这一屏画的是什么
 *
 * 消息页签从这一轮起有两个分区：上面是会话流（[ConversationUi]，按最后一条消息排），
 * 下面是这一段（Agent / 工作区，按最近活动与待审批排）。两把尺子、两个红点，
 * 刻意不混排 —— 会话按消息时间排，工作区按待审批与最近活动排，两把尺子各自成立。
 *
 * ## 两阶段，一个键
 *
 * 行的身份是 `(nodeId, agentId ?? agentKind, wsId)`（父稿 §2.1）。
 *
 * PPE-1 增补 D1/D2/D6 已落地（`agent-session` 带 `agentId` / `wsId` / `wsTitle` /
 * `wsPath` 四个可选键，cap `aws1`）⇒ 分组键按完整四层走，**缺哪一层就退化到哪一层**：
 *
 * - 对端没报 `wsId` ⇒ [WorkspaceUnitUi.wsId] 为 null，该行是 Agent 行（阶段一形状），
 *   副行如实写「对端还没宣告过工作区」；
 * - 对端没报 `agentId` ⇒ 身份键退化为 `agentKind`；两个同类 Agent 并成一行（既定降级，
 *   父稿 §3.1），`agentKind` 与 `agentId` 都缺时落到"未上报类型"。
 *
 * ## 行从哪儿来：**并集**，不是只看会话
 *
 * PPE-1 增补 D3（`agent-workspace`）落地后，这一段的行来自**两个来源**：
 * `agentSessions` 派生的行 ∪ `agentWorkspaces` 宣告过的行（同一 `(agentKey, wsId)` 合成一行）。
 * 只有宣告、一条会话都没有的那些 = **空工作区行**（`sessionCount = 0`、`active = null`、
 * [WorkspaceUnitUi.declaredOnly] = true）。这一档正是 D3 存在的唯一理由（父稿 §2.2：
 * 「工作区已存在但还没有任何会话」在只从会话派生的世界里根本画不出来），
 * 所以派生里少了并集这一步，D3 就等于白收。
 */

/**
 * 「工作区」段最多画几行 —— 原型 `INBOX_WS_LIMIT`（3）。
 *
 * 理由在原型里是实测出来的：不设上限时夹具下段高 851.5px（会话区才 427.1px），
 * 真实数据下更无界（一台设备 N 个工作区 × M 台设备）。上限挡住的行走一行「还有 N 行」的尾巴。
 *
 * 它是**整段的**上限，不是每组各几行：同一把尺子才能保证"排在最前的就是最该被看见的"。
 */
public const val INBOX_WS_LIMIT: Int = 3

/**
 * 「工作区」段的一段（= 一台设备）。
 *
 * 组头用的是节点显示名与平台，**不是** Agent 名：一台设备可以有 N 个 Agent × M 个工作区，
 * 组头要回答的是"这些是谁的"。
 */
public data class WorkspaceUnitGroupUi(
    val peerId: String,
    val nodeName: String,
    val platform: String,
    val online: Boolean,
    val lastSeenAt: Long,
    /** 这台设备上的 Agent 行总数。 */
    val agentCount: Int,
    /** 这台设备上的工作区行总数（对端报了 `wsId` 的那些行）。 */
    val workspaceCount: Int,
    /** 已按上限裁剪后、这一段真正画出来的行。 */
    val units: List<WorkspaceUnitUi>,
    /**
     * 归不到任何 Agent 的待审批条数。
     *
     * `agent-approval` 帧里只有 `peerId` + `sessionId`，**没有** agent 层；要落到某个 Agent
     * 只能拿 `sessionId` 去 [RelayUiState.agentSessions] 里二次 join。join 不上时宁可
     * 只记到节点级、显示在组头上，也不要猜它是哪个 Agent 的（父稿 R1/R3）。
     */
    val unattributedApprovals: Int = 0,
)

/**
 * 「工作区」段的一行。
 *
 * 对端没报 `wsId`（阶段一形状）：一个 Agent 一行，[agentKind]（或 `agentId`）就是身份与标题。
 * 对端报了 `wsId`（阶段二）：一个工作区一行 —— 副行带 Agent 名。两阶段同一段可混排。
 */
public data class WorkspaceUnitUi(
    val peerId: String,
    /**
     * Agent 的**身份键** = `agentId ?? agentKind`。两者都没有时是 null = "未上报类型"
     * （**不是空串**：空串会被当成一个真实取值，null 才如实表示"没收到"）。
     * 语义为 Agent 身份键（`agentId ?? agentKind`），确保同一 Agent 下的会话正确归组。
     */
    val agentKind: String?,
    /** 工作区 id；阶段二才有，今天恒 null。 */
    val wsId: String? = null,
    /** 工作区名（`wsTitle`）；拿不到就是 null —— 这时标题写「未命名工作区」，**不拿路径顶替**。 */
    val wsTitle: String? = null,
    /** 工作区路径（`wsPath`）：只进详情与消歧，不进卡片主位（父稿 §0 的隐私纪律）。 */
    val wsPath: String? = null,
    /** 这一行下的会话条数。 */
    val sessionCount: Int = 0,
    /** 这一行的待审批条数（只数 join 得到的那些）。 */
    val pendingCount: Int = 0,
    /** 这一行最近一次活动（会话帧 / 事件帧 / 节点最后在线）。 */
    val lastActivityAt: Long = 0L,
    /**
     * 这一行在 **DSH 注册表里的持久顺序**（0 起）；null = 对端没报。
     *
     * 为什么它是排序的第一把尺子而不是"最近活动"：用户在 DSH 里看到的顺序是**他自己拖出来的**
     * （官方 `insertBefore`），跟哪个项目刚干过活无关。真机反馈「工作区的排序跟 DSH 不一样」
     * 说的就是这个 —— 手机此前只用待办数 / 最近活动两把尺子，同一批工作区在两端的排列不同。
     *
     * null 的行**排在所有已知顺序的行之后**（而不是当成 0 混进最前面）：沉默不猜，
     * 而且把"不知道"当成"排第一"会让老宿主的行插进用户手工排好的序列里。
     *
     * 缺失时的兜底键是 [workspaceSortName]（标题 → 路径末段 → wsId），**不再是**
     * 待审批 / 最近活动：那两把尺子都随实时帧变，宿主桥又不发 order（真机库里 15/15 是
     * NULL），于是它们从"次级判据"变成了实际主键 —— 列表每收一帧就重排。
     */
    val hostOrder: Long? = null,
    /**
     * 这一行「最关键的那条活跃会话」的快照 —— 与看板卡片同一口径。
     *
     * 挑选顺序（看板原文）：优先有未决审批的会话 → 其次 `state == "busy"` → 其次最近更新的那条。
     * 一个会话都没有时是 null，屏上写「暂无会话」。
     */
    val active: WorkspaceUnitActiveUi? = null,
    /**
     * 这个 Agent 名下的队列 / 执行 / 产物条数。
     *
     * 按 `peerId` 计数而**不是**按本行的会话集计数 —— 与看板卡片逐字一致：
     * 同一个 Agent 在消息页与看板必须印出同一个数。
     */
    val queueCount: Int = 0,
    val runCount: Int = 0,
    val artifactCount: Int = 0,
    /**
     * 这一行**只**由 `agent-workspace` 宣告而来，一个会话都没有（PPE-1 增补 D3）。
     *
     * 为什么要单独一档而不是让 `sessionCount == 0` 去表达：会话数为 0 的行有两种，
     * 语义完全相反 ——
     * - 宣告过、还没会话（这里，`declaredOnly = true`）：那个目录**存在**，只是今天没活干，
     *   屏上该写「已宣告 · 暂无会话」；
     * - 一个 Agent 一行、对端从没带过 `wsId`（`wsId == null` 那种）：我们**不知道**它有没有工作区，
     *   写「尚未收到工作区宣告」才对。
     * 合成一句就是拿一种情况的话去说另一种情况。D3 存在的唯一理由就是让第一档**可见**
     * （父稿 §2.2），所以这个标志位是那一档在界面上的落点，不是装饰。
     */
    val declaredOnly: Boolean = false,
    val isHost: Boolean = false,
    val access: String? = null,
) {
    /** 这一行代表的是工作区还是 Agent。 */
    val isWorkspace: Boolean get() = wsId != null

    /** 有 wsId、却没有 wsTitle —— 屏上要写「未命名工作区」并给出理由。 */
    val isUnnamedWorkspace: Boolean get() = wsId != null && wsTitle.isNullOrBlank()
}

/**
 * 「工作区」段一行里那条最活跃会话的快照。
 *
 * 消息页与看板**共用同一个组件**（`WorkspaceUnitCard`），取值口径也必须同一份 ——
 * 否则同一个 Agent 在两处会写出不同的会话名。
 */
public data class WorkspaceUnitActiveUi(
    /** 会话 id：下钻到会话详情要用。 */
    val sessionId: String,
    /**
     * 展示名：`alias` 优先，其次 `title`，两者都没有就是 **null**。
     *
     * null 不是"没算出来"，是"这个会话没有名字"这件事本身：派生层（本文件）是纯 Kotlin，
     * 没有 `stringResource`，所以它只许如实传 null，由 Composable 调用方
     * 翻成本地化的「未命名」（`Res.string.agent_session_untitled`）。
     * 这里绝不能退回 `sessionId`：身份键不是标题（见 `AgentSessionUi.displayTitle` 的 KDoc）。
     */
    val title: String?,
    /** 对端上报的状态字符串，原样保留（翻成文案在组件里做，未知取值不许丢）。 */
    val state: String? = null,
    /** 最新一条活动的文案：事件摘要 → 事件正文 → 会话标题。 */
    val summary: String? = null,
    /**
     * 卡片上那句「最新活动 %s」用的时刻。
     *
     * 取**最新一条事件的本端到达时刻**（事件是实时到的，本端时刻就是它的时刻）；
     * 没有事件时**退到宿主动作时刻** [AgentSessionUi.sortActivityAt]，而不是本端收帧时刻。
     *
     * ## 为什么无事件那一支必须用宿主动作时刻（这正是一个"说真话"的判据）
     *
     * 无事件的会话基本都是**分页拉回来的历史**。用本端收帧时刻的话，用户点开一个
     * 三天没动过的工作区，卡片会写「最新活动 刚刚」—— 因为那一页帧确实是刚刚到的。
     * 那句话是假的，而且假得**系统性地**：越久没碰的工作区越会这样说。
     * 用户在 DSH 里看到的是「3 天前」，两边不一致正是他抱怨的那件事。
     *
     * 老宿主不发这个尾键时 [AgentSessionUi.sortActivityAt] 自己会退回本端时刻 ——
     * 那里是知道的边界，不是这里判错的借口。
     */
    val at: Long = 0L,
)

/** 「工作区」段的全部取数结果。 */
public data class InboxWorkspaceUnitsUi(
    /** 已按上限裁剪、按"待审批降序 → 最近活动降序"排好的组。为空时整段不画。 */
    val groups: List<WorkspaceUnitGroupUi>,
    /**
     * 全量行数（**裁剪前**）。
     *
     * 头部的「N 个」取的是全量而不是画出来的那几行：头一排字在回答"你有多少"，
     * 而不是"我画了几行" —— 后者由尾巴那行「还有 N 行」负责（原型同此）。
     */
    val totalUnits: Int,
    /** 被上限挡住的行数。0 = 没有尾巴。 */
    val hiddenUnits: Int = 0,
    /** 被挡住的行里有多少项待审批 —— 尾巴上必须报出来，否则"还有 4 行"会藏掉最要紧的那条。 */
    val hiddenPending: Int = 0,
    /** 全量里的工作区行数（阶段二；今天恒 0）。 */
    val workspaceCount: Int = 0,
    /** 全量里的 Agent 行数（阶段一）。 */
    val agentCount: Int = 0,
    /** 全量待审批条数。 */
    val pendingTotal: Int = 0,
) {
    /** 整段是否不画（一个 ag1 节点都没有）。 */
    val isEmpty: Boolean get() = groups.isEmpty()

    /**
     * 这些行里有没有**能给人看的内容** —— 空态判据必须用它，不能用 [isEmpty] 的反面。
     *
     * ## 为什么需要一个独立判据
     *
     * 派生层在「这台设备什么都没宣告、也一条会话帧都没收到」时**仍会造一行**
     * 占位行（见构造处那条 `if (unitKeys.isEmpty()) unitKeys += WorkspaceRowKey(null, null)`，
     * 意图是对的：设备不该整台消失）。但那一行 `wsTitle = null`、`sessionCount = 0`、
     * `active = null` —— 它在界面上渲染成一张写着「未上报类型 / 对端还没宣告过工作区」的空卡。
     *
     * 于是 `isEmpty == false` ⇒ 空态被挡掉 ⇒ 屏幕上只剩一张**没有主体的卡**，
     * 用户看到的就是「工作区页面空白，没有空屏效果」。
     *
     * 判据换成「有没有可视内容」之后，任何新增的占位行都不会再把空态挡掉。
     */
    val hasVisibleContent: Boolean
        get() = groups.any { group ->
            group.units.any { unit ->
                unit.isWorkspace || unit.sessionCount > 0 || unit.active != null
            }
        }

    /**
     * 段头右端那个数该用的单位词。
     *
     * 阶段二有工作区行，就写「N 个工作区」；阶段一只有 Agent 行，写「N 个 Agent」。
     * 原型只有前一种（它的夹具已经有 `wsId`）—— 这个分叉是阶段一才出现的，
     * 不写它屏上会印出「0 个」而下面明明有行。
     */
    val countsWorkspaces: Boolean get() = workspaceCount > 0
}

/**
 * 把 [RelayUiState] 里的 Agent 会话 / 事件 / 审批取成「工作区」段要画的东西。
 *
 * 排序与裁剪都在这里做完（原型 `inboxWsItems()` + `inboxWsPlan()`），界面只负责画：
 * 一屏的数据派生只做一次，不留一半规则在 Composable 里。
 *
 * 排序口径（与原型逐字一致）：**待审批条数降序 → 最近活动降序**。组与行同一把尺子 ——
 * 组自己的 [WorkspaceUnitGroupUi.units] 是它排在最前的那几行，这个数也是组间排序的键。
 */
/**
 * 一个节点下的一个 **Agent 段**：agent 身份键 + 它名下的工作区行。
 *
 * ## 层级结构
 *
 * 派生层次为 **节点 → Agent → 工作区**。
 *
 * ## 只有**带工作区**的 agent 才成段
 *
 * 一条工作区都没报的会话不进这里 —— 它们归 [WorkspaceUnitGroupUi.unassignedSessions]，
 * 按父稿 §2.5 画成「一行计数、不造卡」。为什么不让它们也各成一段：一台设备上
 * "没报工作区的会话"可能同时来自几个 agentKey（真机实测：`DSH` 与一条被旧脚本写成
 * 模型名的 `deepseek-v4.1-flash`），各成一段等于用**没有工作区**这件事反过来造出
 * 三个"伪工作区卡"，而它们恰恰是同一个状态：不知道在哪个目录。
 */
public data class WorkspaceAgentSectionUi(
    /** Agent 身份键 = `agentId ?? agentKind`；null = 对端两个都没报（「未上报类型」）。 */
    val agentKey: String?,
    /** 该 Agent 名下的待审批条数。 */
    val pendingCount: Int,
    /** 该 Agent 名下最后一次活动。 */
    val lastActivityAt: Long,
    /** 该 Agent 名下**已上报**的工作区行；恒非空（空的那种根本不成段）。 */
    val workspaces: List<WorkspaceUnitUi>,
)

/**
 * 「未归属会话」的汇总（父稿 §2.5：会话没有 `wsId`）。
 *
 * 规范原文的形态是「看板节点组内一行计数（>0 才画），**不造卡**」——所以它不是一个
 * [WorkspaceUnitUi]：没有目录、没有名字，也不参与"共 N 个工作区"那类计数。
 *
 * [sessions] 是这些会话里最近更新的那一条，供"点它进聊天"用（未归属会话确实有会话，
 * 只是没报它属于哪个目录）。
 */
public data class UnassignedSessionsUi(
    val sessionCount: Int,
    val pendingCount: Int,
    val lastActivityAt: Long,
    /** 最近更新的那条未归属会话；null = 一个都没有（这一行就不画）。 */
    val active: WorkspaceUnitActiveUi? = null,
    /** 那条会话属于哪个节点（点它进聊天要用）。 */
    val peerId: String? = null,
) {
    val isEmpty: Boolean get() = sessionCount == 0
}

/**
 * 把一个节点的行按 Agent 分层（父稿 §1：节点 → Agent → 工作区）。
 *
 * 只保留**带工作区**的行：一条工作区都没报的走 [WorkspaceUnitGroupUi.unassignedSessions]。
 * 顺序沿用 [WorkspaceUnitUi] 已经排好的顺序（待审批降序 → 最近活动降序），
 * **不另造一把尺子**（本仓反复抓过"两把尺子"那类分叉）。
 */
public fun WorkspaceUnitGroupUi.agentSections(): List<WorkspaceAgentSectionUi> {
    // 分组键用 `unit.agentKind` —— 它装的是**身份键**（`agentId ?? agentKind`，见该字段的 KDoc）。
    val withWorkspace = units.filter { it.wsId != null }
    val byAgent = LinkedHashMap<String?, MutableList<WorkspaceUnitUi>>()
    withWorkspace.forEach { unit -> byAgent.getOrPut(unit.agentKind) { mutableListOf() } += unit }
    return byAgent.map { (agentKey, rows) ->
        WorkspaceAgentSectionUi(
            agentKey = agentKey,
            pendingCount = rows.sumOf { it.pendingCount },
            lastActivityAt = rows.maxOfOrNull { it.lastActivityAt } ?: 0L,
            workspaces = rows,
        )
    }
}

/**
 * 把「没绑定工作区」的会话汇总成一行（父稿 §2.5）。
 *
 * 合并的是**状态**而不是身份：这些会话的共同点是"我们不知道它在哪个目录"，
 * 而它们各自报的 agentKey 不构成新的可分辨对象（真机上那三个 agentKey 里有两个
 * 是同一个 agent 的不同上报形态、还有一个是被旧脚本写错的模型名）。
 */
public fun WorkspaceUnitGroupUi.unassignedSessions(): UnassignedSessionsUi {
    val rows = units.filter { it.wsId == null }
    if (rows.isEmpty()) return UnassignedSessionsUi(0, 0, 0L)
    val newest = rows.maxByOrNull { it.lastActivityAt } ?: rows.first()
    return UnassignedSessionsUi(
        sessionCount = rows.sumOf { it.sessionCount },
        pendingCount = rows.sumOf { it.pendingCount },
        lastActivityAt = rows.maxOfOrNull { it.lastActivityAt } ?: 0L,
        active = newest.active,
        peerId = newest.peerId,
    )
}

/**
 * 一条会话的「最新活动时刻」——**全仓唯一口径**。
 *
 * = max(宿主动作时刻 [AgentSessionUi.sortActivityAt], 最新一条事件的到达时刻 [lastEventAt])。
 *
 * ## 为什么必须把事件也算进来
 *
 * 宿主只在 `agent-session` 帧里报 `hostUpdatedAt`，而 agent 连续干活时**推的是事件**：
 * 事件实时到、会话帧可能好几分钟没再来过。只看 `sortActivityAt` 会把「正在干活的那条」
 * 判成比「刚被分页拉回来、宿主动作很旧的会话」更旧 —— 用户看到卡片主位那条「不是最新的」，
 * 而 DSH 里明明是最新的。
 *
 * 反过来，事件时刻是本端到达时刻：它是实时推来的，那个「刚刚」是真的
 * （见 [WorkspaceUnitActiveUi.at] 的 KDoc），所以取两者较大者不失真。
 */
public fun AgentSessionUi.latestActivityAt(lastEventAt: Long): Long =
    maxOf(sortActivityAt, lastEventAt)

/**
 * 按「最新活动」降序排一批会话 —— 与 `RelayUiState.agentSessionsOf` 同一把尺子。
 *
 * 键序列逐字对齐那个方法：`max(sortActivityAt, lastEventAt)` → `startedAt` → `sessionId`。
 * 两处一旦分叉，同一个工作区在「卡片主位」与「会话详情第一条」就会是不同的会话 ——
 * 这正是用户反馈的那件事，所以它必须只有这一处实现。
 */
public fun List<AgentSessionUi>.sortedByLatestActivity(
    lastEventAtOf: (AgentSessionUi) -> Long,
): List<AgentSessionUi> =
    sortedWith(
        compareByDescending<AgentSessionUi> { it.latestActivityAt(lastEventAtOf(it)) }
            .thenByDescending { it.startedAt ?: 0L }
            .thenByDescending { it.sessionId },
    )

/**
 * 一个工作区里「最关键的那条活跃会话」——**唯一一处挑选规则**。
 *
 * 顺序（看板原文口径，消息页与看板共用）：
 * 1. 有未决审批的会话 —— 「有一项等你批」比「正在干活」更该被看见，且不论它多旧；
 * 2. 未超时的 `busy` —— [isStaleBusy] 挡掉宿主异常退出时留下的僵死 busy，
 *    否则它会永远霸占主位；
 * 3. 最新活动的那条（同层内按 [sortedByLatestActivity] 排，所以它就是真正最新的那条）。
 *
 * ## 为什么必须收敛到这一处
 *
 * 此前有三份各自实现的挑选：这里、`WorkspaceDetailView`、`AgentConsoleScreen.onOpenChat`；
 * 三份的排序键与 busy 判定各差一点，于是同一个工作区在两屏显示不同的「最新会话」。
 * 现在三处都调这一个函数，行与行之间的差异只剩「传进来的候选集」。
 */
public fun pickActiveSession(
    sessions: List<AgentSessionUi>,
    lastEventAtOf: (AgentSessionUi) -> Long,
    pendingSessionIds: Set<String>,
    now: Long,
): AgentSessionUi? {
    if (sessions.isEmpty()) return null
    val ordered = sessions.sortedByLatestActivity(lastEventAtOf)
    return ordered.firstOrNull { it.sessionId in pendingSessionIds }
        ?: ordered.firstOrNull {
            it.state == AGENT_STATE_BUSY && !isStaleBusy(it.latestActivityAt(lastEventAtOf(it)), now)
        }
        ?: ordered.first()
}

public fun RelayUiState.inboxWorkspaceUnits(
    limit: Int = INBOX_WS_LIMIT,
    now: Long = 0L,
): InboxWorkspaceUnitsUi {
    val hostNode = if (agentWorkspaces.keys.any { it.peerId == selfNodeId }) {
        PeerUi(
            nodeId = selfNodeId,
            nick = settings.nick.ifBlank { selfNodeId.take(8) },
            platform = "",
            caps = listOf(app.relay.core.protocol.MsgTypes.CAP_AGENT, app.relay.core.protocol.MsgTypes.CAP_AGENT_WORKSPACE),
            online = true,
        )
    } else null
    val agentNodes = peers.filter { it.isAgent } + listOfNotNull(hostNode)
    if (agentNodes.isEmpty()) return InboxWorkspaceUnitsUi(groups = emptyList(), totalUnits = 0)

    val maxActivity = agentSessions.values.maxOfOrNull { it.sortActivityAt } ?: 0L
    val referenceNow = when {
        now > 0L -> now
        maxActivity in 1L until 100_000_000_000L -> maxActivity
        else -> nowMillis()
    }

    val pendingApprovals = agentApprovals.values.filter { it.status == APPROVAL_PENDING }

    val full = mutableListOf<WorkspaceUnitGroupUi>()
    for (node in agentNodes) {
        // 可见性过滤在这一处统一做（**不是**在每行里逐条判）：DSH 的 blank 那一档问的是
        // 「同一工作区里还有没有别的可见会话」，要看着整个节点的会话才能回答。
        // 真机实测 111 行落库会话里 68 行是 DSH 自己一定不显示的（子代理静默会话 + 空壳），
        // 它们此前会在这里派生出成批的工作区行/看板卡片。
        val nodeSessions = visibleAgentSessionsOfNode(node.nodeId)

        // PPE-1 增补 D3：`agent-workspace` 宣告台账里属于这个节点的那些行。
        // 它们是**并集的另一半** —— 「工作区已存在、但还没有任何会话」这一档只有这帧能表达，
        // 光从会话派生就会让那种工作区整行消失（父稿 §2.2 的立论）。
        val declared = agentWorkspaces.values.filter {
            it.peerId == node.nodeId && !isSyntheticWsId(it.wsId)
        }

        // 行的分组键 = `(agentId ?? agentKind, wsId)`（父稿 §1 的四层模型）。
        // D1/D6 上线前 wsId 恒 null、agentId 恒 null ⇒ 键退化得与阶段一逐字相同
        // （一个 Agent 一行）；对端一旦带上 wsId，同一个 Agent 的每个工作区各成一行。
        // 顺序 = 会话帧的上报顺序；一个会话都没有时仍然给一行「未上报」，
        // 否则"声明了 ag1 却一条帧都没收到"的设备会整台消失（原型 agentsOfNode 同此）。
        val unitKeys = LinkedHashSet<WorkspaceRowKey>()
        nodeSessions.forEach { s ->
            val wsId = normalizeWsId(s.wsId)
            if (isSyntheticWsId(wsId)) {
                return@forEach
            }
            unitKeys += WorkspaceRowKey(
                agentKey = s.agentId ?: normalizeAgentKind(s.agentKind),
                wsId = wsId,
            )
        }
        // 宣告行并入同一个键集：同一 (agentKey, wsId) 撞在一起就是**同一行**（LinkedHashSet
        // 按值去重），于是"会话派生的行 ∪ 宣告的行"天然是并集而不是两份列表拼起来。
        // wsId 恒非空（协议必填），所以宣告永远落在工作区行那一档，不会造出 Agent 行。
        declared.forEach { w ->
            unitKeys += WorkspaceRowKey(agentKey = w.agentKey, wsId = normalizeWsId(w.wsId))
        }
        if (unitKeys.isEmpty()) unitKeys += WorkspaceRowKey(null, null)

        val units = unitKeys.map { rowKey ->
            val kind = rowKey.agentKey
            // 这一行名下会话的**唯一**排序口径：最新活动在前 —— 宿主动作时刻与最新事件
            // 到达时刻取大（见 sortedByLatestActivity）。此前这里只按 `sortActivityAt` 排，
            // 而 agentSessionsOf 用的是含事件的键：agent 连续推事件、会话帧却久未更新时，
            // 两处会排出两个不同的"第一条"，同一个工作区在两屏显示不同会话。
            //
            // 键必须与看板/会话详情**同源**：这里若退回本端收帧时刻 `updatedAt`，历史分页那
            // 20 条（同一个 TCP tick 到达）会全部并列，而"第一条"= 卡片主位那句会话名 ——
            // 于是卡片标题会随 map 的插入序乱跳，与用户在 DSH 里看到的不是同一条。
            val lastEventAtOf: (AgentSessionUi) -> Long = { session ->
                agentEvents[AgentSessionKey(node.nodeId, session.sessionId)]
                    .orEmpty().maxOfOrNull { it.at } ?: 0L
            }
            val sessions = nodeSessions
                .filter {
                    (it.agentId ?: normalizeAgentKind(it.agentKind)) == kind &&
                        normalizeWsId(it.wsId) == rowKey.wsId
                }
                .sortedByLatestActivity(lastEventAtOf)
            // 这一行的宣告侧（同键的那条 `agent-workspace` 帧）；没有会话、只有它 = 空工作区行。
            val announcement = declared.firstOrNull {
                it.agentKey == kind && normalizeWsId(it.wsId) == rowKey.wsId
            }
            val sessionIds = sessions.map { it.sessionId }.toSet()
            val pending = pendingApprovals.count { it.peerId == node.nodeId && it.sessionId in sessionIds }
            // 最近活动 = 会话/事件的最近时刻 ∪ 宣告帧的收到时刻：一个刚宣告出来的空工作区
            // 也有"什么时候出现在我屏幕上的"这件事，恒 0 会让它永远排在有活动的行后面。
            //
            // 会话项用 `latestActivityAt`（宿主动作时刻与最新事件时刻取大），**不是**本端收帧
            // 时刻 —— 这一列既要排序又会被消息页那一行直接印成"N 分钟前"。用本端时刻的话，
            // 用户点开一个三天没动过的
            // 工作区，那一页 20 条帧"刚刚"到达，行上就会写「刚刚」；而且这是**系统性**的假话：
            // 越久没碰的工作区越会这样说，而用户在 DSH 里看到的是「3 天前」。
            // 两台机器的钟差是各仓都在用的既有前提（agent-workspace.at 一直是宿主时钟），
            // 量级是秒；而"我什么时候拉到这一页"可以是几天 —— 两害相权，取宿主时刻。
            // 事件项 `at` 是本端到达时刻：事件是实时推的，那个"刚刚"是真的。
            val lastAt = maxOf(
                sessions.maxOfOrNull { it.latestActivityAt(lastEventAtOf(it)) } ?: 0L,
                announcement?.at ?: 0L,
            )

            // 最关键的那条活跃会话：唯一一处挑选规则（见本文件顶部的 pickActiveSession）。
            // 此前这里、WorkspaceDetailView、AgentConsoleScreen 各算一遍，三份的排序键与
            // busy 判定各差一点 —— 同一个工作区在两屏显示不同会话就是这么来的。
            val active = pickActiveSession(
                sessions = sessions,
                lastEventAtOf = lastEventAtOf,
                pendingSessionIds = pendingApprovals
                    .filter { it.peerId == node.nodeId }
                    .map { it.sessionId }
                    .toSet(),
                now = referenceNow,
            )
            val activeEvent = active
                ?.let { agentEvents[AgentSessionKey(node.nodeId, it.sessionId)].orEmpty().lastOrNull() }

            // 工作区名/路径取组内第一个非空值（同一 wsId 的多条会话理应同名；
            // 不一致时先到先得，与 recordSession 的合并纪律同口径）；
            // 会话侧一个都没有时（零会话行）退回宣告帧带来的那一份 —— 名字与路径
            // 不要求先有一条会话才存在。
            val wsTitle = sessions.firstOrNull { !it.wsTitle.isNullOrBlank() }?.wsTitle
                ?: announcement?.wsTitle
            val wsPath = sessions.firstOrNull { !it.wsPath.isNullOrBlank() }?.wsPath
                ?: announcement?.wsPath
            WorkspaceUnitUi(
                peerId = node.nodeId,
                agentKind = kind,
                wsId = rowKey.wsId,
                wsTitle = wsTitle,
                wsPath = wsPath,
                // 会话数：手机本地统计优先，宣告帧自报作为兜底（支持尚未拉取详情的静默工作区）。
                //
                // 取较小值而不是「有本地就忽略自报」：本地数是子集（只含收到帧的那些），
                // 宿主台账自报数（declared）是 DSH 的权威成员数：
                // 1. 若宿主自报了 >0 的数值，以宿主台账为准（台账是 DSH 真实成员账，
                //    老版本本地残余的存量非成员会话不应把这个数字撑大，刚拉取部分分页时也不会报小）；
                // 2. 若宿主自报为 0 或未自报，退回本地实际会话数 sessions.size。
                sessionCount = announcement?.sessionCount?.toInt()?.let { declared ->
                    if (declared > 0) declared else sessions.size
                } ?: sessions.size,
                pendingCount = pending,
                lastActivityAt = lastAt,
                // 顺序只有宣告帧带得回来（会话帧没有这个键）—— 同键的那条 agent-workspace 帧
                // 就是这一行在宿主台账里的那一份。多帧同名时取先到的那份（与 wsTitle 同纪律）。
                hostOrder = announcement?.hostOrder,
                active = active?.let { activeSession ->
                    val lastActivity = activeSession.latestActivityAt(lastEventAtOf(activeSession))
                    val effectiveState = if (activeSession.state == AGENT_STATE_BUSY && isStaleBusy(lastActivity, referenceNow)) {
                        "idle"
                    } else {
                        activeSession.state
                    }
                    WorkspaceUnitActiveUi(
                        sessionId = activeSession.sessionId,
                        // 回落（alias → title，都没有即 null）只在 `AgentSessionUi.displayTitle`
                        // 里实现一处 —— 这条线的第 5 个调用点（前 4 个在 UI 层，这处在派生层）。
                        // 漏掉 title 时，工作区卡片上那句「在干什么」就会显示成 session-3f2a…。
                        title = activeSession.displayTitle,
                        state = effectiveState,
                        summary = activeEvent?.summary ?: activeEvent?.body ?: activeSession.title,
                        // 事件优先（它是实时到的，"最新活动"说的就是它）；无事件时用宿主动作时刻，
                        // 让分页拉回的历史会话说真话（详见 WorkspaceUnitActiveUi.at 的 KDoc）。
                        at = activeEvent?.at ?: activeSession.sortActivityAt,
                    )
                },
                queueCount = agentTasks.values.count { it.peerId == node.nodeId },
                runCount = agentRuns.values.count { it.peerId == node.nodeId },
                artifactCount = agentArtifacts.values.count { it.peerId == node.nodeId },
                // 空工作区行的判据：**有宣告、零会话**。有会话时宣告只是补充信息，
                // 那一行按普通工作区行画（副行有会话数、底栏有活跃会话）。
                declaredOnly = announcement != null && sessions.isEmpty(),
                isHost = node.nodeId == selfNodeId || node.ip == "127.0.0.1" || node.host == "localhost" || node.host == "127.0.0.1",
                access = grantFor(node.nodeId, kind, rowKey.wsId)?.access,
            )
        }.sortedWith(unitOrder)

        if (units.isEmpty()) continue

        val joined = nodeSessions.map { it.sessionId }.toSet()
        val unattributed = pendingApprovals.count { it.peerId == node.nodeId && it.sessionId !in joined }

        full += WorkspaceUnitGroupUi(
            peerId = node.nodeId,
            nodeName = node.displayName,
            platform = node.platform,
            online = node.online,
            lastSeenAt = node.lastSeenAt,
            agentCount = units.count { !it.isWorkspace },
            workspaceCount = units.count { it.isWorkspace },
            units = units,
            unattributedApprovals = unattributed,
        )
    }

    // 跨组排序：组的**宿主顺序最小值**决定它在段里的位置 —— 一个节点自己从 0 编号，
    // 所以"这个节点里排第一的那个工作区"就是这一组的序（多节点同时接入时按这个序交错，
    // 每个节点的 0 号工作区并列，由后面那把尺子分开，不会让某台设备的全部工作区挤成一坨）。
    //
    // 组内没有一条带顺序时（宿主桥不发 order / 只有会话没有宣告）退回**同一把稳定尺子**：
    // 组内最小的稳定名（= 这一组排第一的那个工作区的名字）→ 节点 id。
    // 与行内排序同源，也与 RelayUiState.workspacesOf 同源 —— 两屏各用一把尺子正是本仓
    // 反复抓过的那类分叉。待审批与最近活动**不参与**（理由见 unitOrder 的 KDoc）。
    val ordered = full.sortedWith(
        compareBy<WorkspaceUnitGroupUi> { group ->
            group.units.minOf { unit -> unit.hostOrder ?: Long.MAX_VALUE }
        }
            .thenBy { group -> group.units.minOf { it.sortName() } }
            .thenBy { group -> group.peerId },
    )

    val totalUnits = ordered.sumOf { it.units.size }
    val shown = if (limit >= totalUnits) ordered else capGroups(ordered, limit)
    val hidden = totalUnits - shown.sumOf { it.units.size }

    return InboxWorkspaceUnitsUi(
        groups = shown,
        totalUnits = totalUnits,
        hiddenUnits = hidden,
        hiddenPending = if (hidden == 0) 0 else shownHiddenPending(ordered, shown),
        workspaceCount = ordered.sumOf { group -> group.units.count { it.isWorkspace } },
        agentCount = ordered.sumOf { group -> group.units.count { !it.isWorkspace } },
        pendingTotal = agentApprovals.values.count { it.status == APPROVAL_PENDING },
    )
}

/**
 * 工作区的**稳定排序名** —— 全仓唯一口径（[unitOrder]、组间排序、`RelayUiState.workspacesOf`
 * 三处都调它）。
 *
 * 取 `wsTitle` → `wsPath` 末段 → `wsId`。三段都是**宣告帧自带、与活动无关**的值。
 *
 * ## 为什么排序需要它（真机反馈「列表变来变去」）
 *
 * 宿主侧的桥**从不发 `order`**（真机库里 15/15 工作区的 `host_order` 是 NULL），于是行的
 * 第一把尺子全体并列，真正决定顺序的退化成「待审批 → 最近活动」。而最近活动是**实时事件**
 * 驱动的：谁刚干活谁窜到最前，下一秒又换。标题（或路径末段）只在工作区真的改名时才变，
 * 用它当兜底键，顺序才是可预期的。
 *
 * ## 为什么末段要同时吃 `/` 与 `\`
 *
 * `wsPath` 是宿主报的本地路径，Windows 宿主报的是反斜杠。只切 `/` 会把整条
 * `C:\src\foo` 当成名字 —— 多数时候它与目录名同序、看不出错，直到两台机器的路径前缀不同。
 */
public fun workspaceSortName(wsTitle: String?, wsPath: String?, wsId: String?): String {
    wsTitle?.takeIf { it.isNotBlank() }?.let { return it }
    wsPath?.takeIf { it.isNotBlank() }?.let { path ->
        val segment = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        if (segment.isNotBlank()) return segment
    }
    return wsId?.takeIf { it.isNotBlank() } ?: ""
}

/**
 * 这一行在稳定排序里的名字：工作区行 = [workspaceSortName]；Agent 行（没有 wsId）=
 * 身份键（`agentKind`）—— 那种行没有目录名可排，身份键就是它的名字。
 */
public fun WorkspaceUnitUi.sortName(): String =
    if (isWorkspace) workspaceSortName(wsTitle, wsPath, wsId) else agentKind.orEmpty()

/**
 * 行的排序：**宿主顺序 → 稳定名（标题 → 路径末段 → wsId）→ 最终稳定键**。
 *
 * ## 为什么不再用「待审批 / 最近活动」（用户裁决）
 *
 * 两者都是**实时变化**的量：审批来一条、某个 agent 推一条事件，行的位置就变一次。
 * 真机库里 15/15 工作区的 `host_order` 是 NULL（宿主桥不发 `order`），于是这两把尺子从
 * "次级判据"变成了**实际主键** —— 列表每收一帧就重排，用户看到的就是"变来变去"。
 *
 * 待审批**不置顶**是有意的，不是漏了：它已经有两处不受行序影响的露出 —— 看板顶部的
 * `AgentApprovalsSection` 逐条画全部待审批，消息页段头的「· N 项待审批」与段尾的
 * 「还有 N 行 · 其中 M 项待审批」；行内 `pendingLabel` 徽标也照画。把它当排序键换不来
 * 更多可见性，只会换来跳动。
 *
 * 第一把尺子（宿主序）保留原语义：有顺序的行按它排，null 的行排在其后 —— 沉默不猜，
 * 也不让老宿主的行插进用户手工排好的序列里。最后一档 `wsId ?: agentKind` 是**不可变**的
 * 身份键，专门用来把同名行钉死（同名工作区在排序里必须仍然有确定次序）。
 */
private val unitOrder: Comparator<WorkspaceUnitUi> =
    compareBy<WorkspaceUnitUi> { it.hostOrder ?: Long.MAX_VALUE }
        .thenBy { it.sortName() }
        .thenBy { it.wsId ?: it.agentKind ?: "" }

/** 取全量顺序里的前 [limit] 行，按组收回去（组一旦没有行就不再出现）。 */
private fun capGroups(
    ordered: List<WorkspaceUnitGroupUi>,
    limit: Int,
): List<WorkspaceUnitGroupUi> {
    var left = limit
    val out = mutableListOf<WorkspaceUnitGroupUi>()
    for (group in ordered) {
        if (left <= 0) break
        val take = group.units.take(left)
        if (take.isEmpty()) continue
        left -= take.size
        out += group.copy(units = take)
    }
    return out
}

/** 被挡住的那些行的待审批条数之和。 */
private fun shownHiddenPending(
    ordered: List<WorkspaceUnitGroupUi>,
    shown: List<WorkspaceUnitGroupUi>,
): Int {
    val shownPerGroup = shown.associate { it.peerId to it.units.size }
    return ordered.sumOf { group ->
        val visible = shownPerGroup[group.peerId] ?: 0
        group.units.drop(visible).sumOf { it.pendingCount }
    }
}

/** 空串与只有空白的 `agentKind` 与"没有"是一回事；这里统一成 null。 */
private fun normalizeAgentKind(kind: String?): String? = kind?.takeIf { it.isNotBlank() }

/** `wsId` 同规矩：空串不是"一个真实的工作区 id"，是"没报"。 */
private fun normalizeWsId(id: String?): String? = id?.takeIf { it.isNotBlank() }

/** 一行的分组键：Agent 身份键（`agentId ?? agentKind`）× 工作区 id（null = 未归属/阶段一）。 */
private data class WorkspaceRowKey(val agentKey: String?, val wsId: String?)

/** 审批的"待处理"取值。协议里就这一个字面量，`status` 其余取值表示已决。 */
private const val APPROVAL_PENDING: String = "pending"

/** 会话状态里表示「正在干活」的取值（PPE-1 §6.5）。看板挑活跃会话时用的就是它。 */
private const val AGENT_STATE_BUSY: String = "busy"

/**
 * 僵死会话超时门限（默认 30 分钟）。
 * 当会话标记为 busy 却在门限内没有任何事件或活动时，不再将其视为正在干活，
 * 避免因宿主异常退出漏推收尾帧而永久霸占看板主位。
 */
public const val STALE_BUSY_TIMEOUT_MS: Long = 30 * 60 * 1000L

public fun isStaleBusy(
    lastActivityAt: Long,
    now: Long,
    timeoutMs: Long = STALE_BUSY_TIMEOUT_MS,
): Boolean {
    if (lastActivityAt <= 0L || now <= 0L) return false
    return (now - lastActivityAt) > timeoutMs
}

public fun isSyntheticWsId(wsId: String?): Boolean {
    if (wsId == null) return false
    return wsId.startsWith("ws-probe-") || wsId.startsWith("ws-plugin-") ||
        wsId.startsWith("ws-tool-") || wsId.startsWith("ws-verify-") ||
        wsId.startsWith("ws-port-") || wsId.startsWith("ws-deliver-") ||
        wsId.startsWith("ws-full-")
}
