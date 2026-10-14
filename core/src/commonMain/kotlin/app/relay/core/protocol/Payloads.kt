// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.protocol

import kotlinx.serialization.Serializable

/**
 * 出站载荷的 `@Serializable` 形状。
 *
 * 字段声明顺序 = 线上 JSON 的 key 顺序。三条不能违反的规则：
 *  1. 顺序不能随便调 —— TS 侧是 `JSON.stringify`，键序就是属性插入序，对齐靠字节比对验证；
 *  2. 真正可选的字段用 `= null`，配合 `explicitNulls = false` + `encodeDefaults = false` 在缺省时整条省略；
 *  3. 必须出现在线上的字段绝不给默认值 —— 给了默认值就会被 `encodeDefaults = false` 省略掉。
 *
 * 入站方向不用这些类（见 [Codec]）：解码是手工从 `JsonObject` 做的，为了精确复刻 TS 的
 * 拒绝理由字符串；这些类只负责出站编码。
 */

/** entry / alive / profile 的载荷：profile 必需，probeId 只在定向探测的 alive 回复里携带。 */
@Serializable
data class ProfilePayload(
    val profile: Profile,
    val probeId: String? = null,
)

/** presence 心跳。 */
@Serializable
data class PresencePayload(
    val seq: Int,
    val profileRev: Int,
)

/** msg kind=text。 */
@Serializable
data class TextMsgPayload(
    val kind: String,
    val text: String,
    val resend: Boolean? = null,
)

/**
 * msg kind=group-text。
 *
 * key 顺序对齐 TS 侧白名单 `kind|text|groupId|groupRev|mentions|resend|replyTo`：
 * 老节点对群消息做**严格字段白名单**校验，多一个 key 就整帧丢弃（golden vector
 * `neg-group-text-extra-field` 覆盖了这条）。
 */
@Serializable
data class GroupTextMsgPayload(
    val kind: String,
    val text: String,
    val groupId: String,
    val groupRev: Int,
    val mentions: List<String>? = null,
    val resend: Boolean? = null,
    val replyTo: String? = null,
)

/**
 * msg kind=recall。
 *
 * key 顺序对齐 TS 侧白名单 `kind|targetId|groupId|groupRev`（协议文档 §8 `msg.payload`）：
 * `targetId` 是要撤回的原消息 id，**媒体消息用的是 `offer.msgId`**；群聊撤回额外带
 * `groupId`/`groupRev`（两个要么都有、要么都没有，见 `Codec.validateGroupRef`）。
 */
@Serializable
data class RecallMsgPayload(
    val kind: String,
    val targetId: String,
    val groupId: String? = null,
    val groupRev: Int? = null,
)

/**
 * msg kind=nudge（私聊窗口震动）。
 *
 * 线上**只有 `kind` 一个键** —— 协议文档 §8 明确「payload 除 `kind` 外不带正文、群字段或
 * 文件引用」，所以这里不给任何可选字段。
 */
@Serializable
data class NudgeMsgPayload(
    val kind: String,
)

/** ack 回执。 */
@Serializable
data class AckPayload(
    val ackFor: String,
)

/**
 * PPE-1 §6.1 `agent-session`：宣告一个 agent 会话（cap `agn1`）。
 *
 * 已知键集合见 `Codec.AGENT_SESSION_EXPECTED_KEYS`；除 `sessionId` 外都可省，
 * 省掉的键在线上**整个不出现**（`explicitNulls = false` + `encodeDefaults = false`）。
 * 接收侧自 D2 起是「必需子集 + 忽略未知键（带预算）」，不再是「恰好白名单」。
 *
 * 尾部四个可选键是 PPE-1 增补 D1/D6（工作区身份与 agent 实例身份，
 * `docs/PROTOCOL-AGENT-WORKSPACE.md` §2.1）。**声明顺序即线上键序**，新键只许加在尾部，
 * 插进中间会改动既有帧的字节（黄金向量会红）。
 */
@Serializable
data class AgentSessionPayload(
    val sessionId: String,
    val alias: String? = null,
    val agentKind: String? = null,
    val state: String? = null,
    val title: String? = null,
    val startedAt: Long? = null,
    /** D6：agent 实例身份（≤64）；缺失即身份退化为 `agentKind`，不猜。 */
    val agentId: String? = null,
    /** D1：工作区稳定 id（≤64）；缺失 = 未归属。惯例 `ws-` + 8~16 hex，别拿路径原文当 id。 */
    val wsId: String? = null,
    /** D1：工作区展示名；缺失 = 未命名（UI 不拿 path 末段顶替）。 */
    val wsTitle: String? = null,
    /** D1：工作区路径，仅详情与消歧用；`wsId`/`wsTitle` 双缺时接收端忽略此键（父稿 §2.1）。 */
    val wsPath: String? = null,
    /**
     * 宿主侧这条会话的**活动时刻**（epoch 毫秒）= `max(createdAt, lastPromptAt)`，
     * 与 DSH 自己的会话列表（`sessionController.list()`）同一个口径。
     *
     * ## 为什么不复用 `startedAt`
     *
     * `startedAt` 是「这个会话是什么时候开始的」，而 DSH 发出的实时帧填的是**帧发出那一刻**
     * （`dsh-tidings/src/announce.ts` 的 `sessionFrame(…, { startedAt: Date.now() })`）。
     * 拿它当排序键，等于让排序依据与界面文案（`agent_session_started`「开始于 %1$s」）
     * 抢同一个字段、必然有一边说假话。
     *
     * ## 只用于排序，不用于显示时间
     *
     * 与 `agent-event` 的 `at` 同规矩：接收端的「刚刚 / 3 分钟前」说的是**本端自己什么时候
     * 收到的**，那是接收端的时钟；换成对端时钟后，两端时钟不一致时会把「刚刚收到的一条」
     * 显示成「8 小时前」。
     *
     * ## 为什么必须加在 `wsPath` 之后（而不是中间）
     *
     * 声明顺序即线上键序，插进中间会改动既有帧的字节，黄金向量立刻判红。
     */
    val updatedAt: Long? = null,
    /**
     * 这条会话是**谁建出来的**：`subagent` = 子代理会话，目前只有 DSH 的子代理会这么写。
     *
     * ## 为什么要送这个键
     *
     * DSH 自己的工作区浏览器对子代理会话是一律隐藏的
     * （`packages/client/ui-workspace/src/client/tree.ts` 的 `sessionVisible()`）。
     * 手机要显示同一份清单，就得拿到同一个判据 —— 光靠标题猜不出来（子代理会话的
     * 标题是它那段 prompt 的首行，长得跟一次正常提问一模一样）。
     *
     * 取值**不封闭**（与 `state`/`agentKind` 同纪律）：今天只认 `subagent`，将来 DSH
     * 多出别的来源也不该让整帧被判非法。长度界借用 [Limits.AGENT_KIND]。
     *
     * ## 缺失不等于可见
     *
     * 缺这个键 = 对端没报，**不猜**：老宿主不发它，手机必须表现得与今天逐字相同。
     */
    val origin: String? = null,
    /**
     * 宿主侧这条会话是不是**空壳**（建出来之后从没跑过一轮对话）。
     *
     * 与 `origin` 同一个用途：DSH 的 `sessionVisible()` 除当前会话外不显示 blank 会话。
     * 为什么让 DSH 自己判断、而不是手机按「有没有事件」推：手机看到的只有它**收到过**的
     * 东西，一个建出来但还没说话的会话，在手机上与「一条帧都没收到的会话」长得一样 ——
     * 而后者手机根本不知道自己该隐藏它。
     *
     * 三态，与 [origin] 同规矩：缺键 = 对端没报，不猜。
     */
    val blank: Boolean? = null,
)

/**
 * PPE-1 §6.2 `agent-event`：一条会话事件（cap `agn1`）。
 *
 * `cursor` 是**事件流游标**，只在同一个 `(node, session)` 内比较单调，与 `ts` 不是一回事
 * （§8.2：`since=<ts>` 与 `cursor` 混用会丢事件或重复投递）。
 * `body` 一旦超出通道预算必须改走 `file-msg`（§6.2 / §7），所以这里不给它单独上限。
 */
@Serializable
data class AgentEventPayload(
    val sessionId: String,
    val cursor: Long,
    val eventType: String,
    val summary: String? = null,
    val body: String? = null,
    val ref: String? = null,
)

/** PPE-1 §6.3 `agent-queue`：任务入队 / 出队（cap `agq1`）。 */
@Serializable
data class AgentQueuePayload(
    val taskId: String,
    val sessionId: String? = null,
    val op: String,
    val prompt: String? = null,
    val queuedAt: Long? = null,
)

/** PPE-1 §6.4 `agent-run`：执行状态（cap `agq1`）。 */
@Serializable
data class AgentRunPayload(
    val sessionId: String,
    val runId: String,
    val state: String,
    val at: Long? = null,
    val note: String? = null,
)

/** PPE-1 §6.5 `agent-approval`：审批请求（cap `agq1`）。 */
@Serializable
data class AgentApprovalPayload(
    val approvalId: String,
    val sessionId: String,
    val tool: String? = null,
    val summary: String? = null,
    val expiresAt: Long,
    val choices: String? = null,
)

/** PPE-1 §6.6 `agent-cancel`：取消（cap `agq1`）。 */
@Serializable
data class AgentCancelPayload(
    val sessionId: String,
    val runId: String? = null,
    val reason: String? = null,
)

/** PPE-1 §6.8 `agent-ack`：队列 / 审批回执（cap `agq1`）。 */
@Serializable
data class AgentAckPayload(
    val ackOf: String,
    val kind: String,
    val at: Long? = null,
)

/**
 * PPE-1 §6.7 `agent-artifact`：产物元数据（cap `agn1`）。
 *
 * **只描述产物，不传二进制** —— 二进制走 `file-msg`，这里用 [fileId] 指向已发送的那条。
 * 字段顺序即线上键序，与 `Codec.AGENT_ARTIFACT_EXPECTED_KEYS` 一致。
 */
@Serializable
data class AgentArtifactPayload(
    val sessionId: String,
    val artifactId: String,
    val fileId: String? = null,
    val name: String,
    val bytes: Long? = null,
    val mime: String? = null,
)

/**
 * PPE-1 增补 D3 `agent-workspace`：宿主主动宣告**一个**工作区（cap `aws1`）。
 *
 * 一帧一个工作区，不做批量数组（父稿 §2.2：单帧预算好算；批量上界未证明）。
 * `at` 是宿主时钟，**只用于排序，不用于显示时间**（与 `agent-event.at` 同规矩）。
 * `online` = 宿主此刻在不在这个工作区里活着；`sessionCount` 是宿主自报数，
 * 手机端自己数的优先。父稿键表里的 `source?` 按 §0「不做」清单**不进帧**。
 */
@Serializable
data class AgentWorkspacePayload(
    val wsId: String,
    val wsTitle: String? = null,
    val wsPath: String? = null,
    /**
     * 为什么这帧**必须**带它：归组键是 `agentId ?? agentKind`，带上可以避免在客户端拆分成多行。
     */
    val agentId: String? = null,
    /** 该工作区的**主要** agentKind（零会话时类型徽标的来源之一）；开放取值。 */
    val agentKind: String? = null,
    val online: Boolean? = null,
    val sessionCount: Long? = null,
    val at: Long? = null,
    /**
     * DSH 注册表里的**持久顺序**（0 起），位置即 DSH 工作区浏览器的分组顺序。
     *
     * 与 `at` 同一条纪律：**只用于排序，不用于显示时间**。为什么必须显式送：手机端把
     * 工作区落库后 `selectAllAgentWorkspaces ORDER BY updated_at DESC` 会把到达序打乱，
     * 而到达序本身恰好就是注册表序（宿主按 `workspaceRegistry.list()` 的序逐条发）。
     * 只靠到达序，重启一次就丢 —— 用户在 DSH 里手工拖过的工作区顺序，手机上必须一致。
     */
    val order: Long? = null,
)

/**
 * PPE-1 增补 D7 `agent-grant-request`（cap `agr1`），设备 → 宿主。
 *
 * `agentKey`（= `agentId ?? agentKind`）缺失 = 申请整台设备。
 */
@Serializable
data class AgentGrantRequestPayload(
    val agentKey: String? = null,
    val reason: String? = null,
    val at: Long? = null,
)

/**
 * PPE-1 增补 D7 `agent-grant`（cap `agr1`），宿主 → 设备。
 *
 * 撤销 = 同一帧带 `state=revoked`（不另开帧）。**撤销不回收历史**：授权只拦
 * 未来的投递（父稿 §3.3 诚实边界 1，UI 文案必须带上这句）。
 */
@Serializable
data class AgentGrantPayload(
    val state: String,
    val scope: String,
    val agentKey: String? = null,
    val at: Long? = null,
)

/** PPE-1 增补 D7 `agent-grant-list`（cap `agr1`），宿主 → 设备，一帧一个条目。 */
@Serializable
data class AgentGrantListPayload(
    val scope: String,
    val agentKey: String? = null,
    /** 宿主自报的条目总数（让手机端能显示"共 N 条"而不必等齐）。 */
    val total: Long? = null,
    val at: Long? = null,
)
/**
 * PPE-1 增补 D8 `agent-session-page`（cap `agp1`），设备 → 宿主。
 *
 * 手机点开某个工作区（或点「加载更多」）时，用它向宿主要**一页**该工作区的会话清单。
 *
 * 三个字段的语义：
 * - [wsId] 必填：要哪个工作区的会话。请求方总是从某一个工作区里点出来的，
 *   而宿主按 wsId 过滤才是 O(1) 的事（DSH 的 Workspace 就带 sessionIds）。
 * - [offset] / [limit] 分页：宿主按其能装进一帧的量回（TCP 上限 4096 字节）。
 *   `limit` 缺失 = 宿主自定（它才知道一帧塞得下几条）。
 * - [at] 请求时刻，用于双向日志对齐。
 *
 * **响应不是这一帧**：宿主收到后直接用已有的 `agent-session` 逐条回填，
 * 所以这个 payload 没有响应形态、也没有 total 之类的回执字段 ——
 * 手机端能从落库的会话数自己判断到没到头。
 */
@Serializable
data class AgentSessionPagePayload(
    val wsId: String,
    val offset: Long? = null,
    val limit: Long? = null,
    val at: Long? = null,
)
