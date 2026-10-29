// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
 * key 顺序对齐 TS 侧白名单 `kind|text|groupId|groupRev|mentions|resend|replyTo`，
 * 之后是本仓扩展键（`senderId` 与 agent 意图/状态/卡片族）。
 *
 * 老节点对群消息做**严格字段白名单**校验，多一个 key 就整帧丢弃（golden vector
 * `neg-group-text-extra-field` 覆盖了这条）⇒ **每加一个可选键，必须同步加进
 * `CodecMsg.GROUP_TEXT_KEYS`**，否则本端发得出去、对端整帧拒收（`senderId` 就踩过这个坑：
 * 键加进了 data class 却漏进白名单，于是 agent 转发进群的每一条都被对端判成 extra-field
 * 丢掉，用户看到的是「桌面看得见、手机永远收不到」）。
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
    /**
     * 这条群消息**真正的说话者**，当它不等于信封 `from` 时使用（可选）。
     *
     * ## 为什么需要它（真机缺陷）
     *
     * 信封上的 `from` 永远是**发送这台设备**，而这台设备可能在**代别人说话**：
     * DSH 的 agent 事件由桌面端转发到群里，信封 `from` 是桌面端的 nodeId。
     * 于是手机端把 agent 说的话显示成「**桌面端**发的」——
     * 而桌面端自己是按 `agent:DSH` 落库的，**同一个群两端显示不同的发送者**。
     *
     * 取值约定：与桌面端本地落库的 `MessageUi.senderId` 同一形态 ——
     * 真人用 nodeId，agent 用 `agent:<kind>`。旧端不发这个键 ⇒ 接收方按 `from` 处理
     * （与以前完全一致，不会更差）。
     *
     * **安全边界**：这只是**显示归属**，不参与鉴权。任何成员本来就能在群里说任何话，
     * 把 senderId 写成别人的 nodeId 只会让那条气泡挂在别人名下 —— 与"冒名发言"同级，
     * 而这个协议本来就没有防冒名（群内成员互信）。所以这里不做额外校验，
     * 只限制长度形态（见 `CodecMsg.validateMsg`）。
     */
    val senderId: String? = null,
    /**
     * 这条群消息属于**哪个 agent 会话**（可选）。与 [agentCursor] 一起构成会话分段标识。
     *
     * ## 为什么需要它（用户可见缺陷）
     *
     * 手机端聊天流里那条「任务：xxx / 新会话」分段线**从来不出现**，而桌面端有。
     * 根因是分段判据绑在消息 id 前缀 `agent-ev-` 上 —— 那是**桌面端本地构造**消息时
     * 写进 id 的，转发到群里时并没有把这个事实带过去；手机端落库用的是信封 UUID，
     * 于是 `extractSessionId` 恒 null，一条线都画不出来。
     *
     * 会话归属是**事实**，应该由协议携带，而不是靠 id 字符串猜。
     *
     * **安全边界**：纯展示分段，不参与鉴权、不参与成员判定。
     * 旧接收端不认识这个键 ⇒ 整帧丢弃；两端需同版本（与 [senderId] 同一取舍）。
     */
    val agentSessionId: String? = null,
    /**
     * 该会话内的事件游标（可选，与 [agentSessionId] 配对）。
     *
     * 用来区分「同一会话的两条消息」与「换了一条会话」—— 只有 sessionId 时，
     * 同一会话连续多条会被正确地视为一段（那已经够了）；带上 cursor 是为了将来
     * 按游标排序/去重时不必再加字段。
     */
    val agentCursor: Long? = null,
    /**
     * 该 agent 会话的**标题**（可选，通常与 [agentSessionId] 同行）。
     *
     * ## 为什么让它跟着消息走，而不是另做一次「会话元数据同步」
     *
     * 跨设备的会话标题本来是**本机私有**的：`agentSessions` 只由本机 agent 宿主写入
     * （`saveSession`），从来没有同步过对端 —— 真机读数：手机侧 `agentSessions` 是**空的**，
     * 于是所有分段线都退化成「新会话」那句中性说明，而桌面端显示得出真标题。
     *
     * 两种消法：
     *  · 新增一条「会话元数据同步」通道 —— 要维护「哪个对端知道哪些会话」的状态，
     *    还会出现「元数据到了、消息没到」或反过来的两个时序缺口；
     *  · 让标题跟着消息走 —— 单帧自包含，分段线永远有它需要的那个标题。
     *
     * 取舍：后者。分段线本来就是**跟着消息**画的，标题属于同一条事实，不该分开同步。
     * 代价是同一会话的多条消息重复携带标题（几十字节），相对整帧预算可忽略。
     *
     * 长度上限 [Limits.GROUP_NAME]，发送侧超了截断；空串与缺键同义（视为没有标题）。
     * 旧接收端不认识这个键 ⇒ 整帧丢弃；两端需同版本（与 [agentSessionId] 同一取舍）。
     */
    val agentSessionTitle: String? = null,
    /**
     * 人类 → 插件的**单帧原子意图**（可选）。随正文一次发出，宿主原子化完成
     * 「开会话 + 派单执行」，乐观发送零网络等待 —— 不再有「先发消息、再调一次
     * 派单接口」的两步时序。
     *
     * 值必须是一个 JSON 对象（接收侧 `validateMsg` 按对象校验，其余形状整帧拒收）；
     * 内部字段（agentId / wsId / resumeSessionId / mode 等）由插件清单定义，协议层
     * 不逐字段校验。旧接收端不认识这个键 ⇒ 整帧丢弃；两端需同版本（与 [senderId] 同一取舍）。
     */
    val agentIntent: JsonObject? = null,
    /**
     * 插件 → 全群的**执行状态**（可选）：`accept` / `blocked` / `completed` / `failed` 四态。
     *
     * 四态之外一律拒收 —— 状态机是协议事实，不是自由文本；界面的状态徽标只画这四态，
     * 多出来的值等于 UI 上的一个空洞，不如在协议层就挡住。旧接收端不认识 ⇒ 整帧丢弃。
     */
    val agentState: String? = null,
    /**
     * **声明式卡片负载**（可选）：插件定义的 JSON，IM 用原生组件渲染，不进正文。
     *
     * 字节上限 [Limits.CARD_BYTES]（UTF-8，序列化后的对象）：
     * - **发送侧超限一律拒绝、绝不截断** —— 截断的 JSON 解不了，「截断后发出去」等于
     *   产出注定被拒的帧；宿主驱动回错误事件给插件，由插件瘦身重发。
     * - 接收侧超限判 bad-payload；队列重放走 `decodeTcp` 时校验同源（见 `validateMsg`）。
     * - `cardId` 是卡片身份（原位刷新的折叠键），`rev` 单调递增；卡片更新以**新消息**
     *   进流（同 cardId、rev+1 的新帧），不重写旧行。
     *
     * 旧接收端不认识这个键 ⇒ 整帧丢弃；两端需同版本（与 [senderId] 同一取舍）。
     */
    val card: JsonObject? = null,
)

/**
 * agent 作为群消息发送者时的**前缀**（完整形态 `agent:<小写 kind>`）。
 *
 * 为什么提到 core：这个字符串有**三个生产点**（宿主侧的 `agentSenderIdFor`、
 * 应用侧两条群内播报）与**两个消费点**（判断「这条是不是 agent 说的」、
 * `shouldTriggerAgent` 的接话判据）。各写一份已经漂过一次 —— 真机库里
 * `agent:DSH` 与 `agent:dsh` 各 801 / 113 条，同一个 agent 显示成两个人。
 */
const val AGENT_SENDER_PREFIX: String = "agent:"

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
    /** 工作区关联的 Git 分支名称（如 "main", "dev"）。 */
    val gitBranch: String? = null,
    /** 会话分段 ID（重置 Prompt 上下文后开启新分段）。 */
    val segmentId: String? = null,
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
    /**
     * 附件元数据（task-45，cap `att1`）—— **追加在最后**，旧字段的线上键序一字不变。
     *
     * 为什么需要它：在此之前图片只能以**文本占位**（"🖼 图片: image.png (920×412, 30 KB)"）
     * 出现在手机上。占位比丢帧好，但用户至少要知道「它是什么」。这里只带**元数据**
     * （id/类型/尺寸/大小/文件名），与 DSH 侧 `user/message` 的 content 块同口径。
     *
     * **字节通道没有接通 —— 这条以前写成「字节仍走既有的文件传输路径（file-msg / file-ctl）」，
     * 那是一句没有兑现的意图，读起来像已经接好了。事实是：
     * - `file-msg` / `file-ctl` 要的是 `transferId` / `fileId`（见 `FileCardUi.transferId`），
     * - 而这里只有 `attachmentId`，两者不是同一个标识，也没有映射关系。
     * 所以界面只能画一张信息卡（文件名 + 尺寸 + 大小），**点不开**。
     * 要做「保存 / 预览」需先定义一条取件消息（按 `attachmentId` 请求字节），
     * 那是产品级新增，不是补一行 UI（见 `agent-board.md` 的 F-AGENT 系列与此处同源）。
     *
     * 缺这个键 ⇒ 退回文本占位（旧端兼容）；带了这个键但形状不对 ⇒ 整帧拒（见
     * [com.kapp.tidings.core.protocol.CodecAgent.validateAgentEvent]）。
     */
    val attachment: AgentEventAttachment? = null,
)

/** agent 事件的附件元数据（与 DSH 侧 content 块同口径，task-45）。 */
@Serializable
data class AgentEventAttachment(
    val attachmentId: String,
    val mediaType: String,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long? = null,
    val name: String? = null,
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
    val riskLevel: String? = null,
    val diff: String? = null,
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
    val decidedBy: String? = null,
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
    val wsId: String? = null,
    val access: String? = null,
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
    val wsId: String? = null,
    val access: String? = null,
    val isMyDevice: Boolean? = null,
    val delegatedApproval: Boolean? = null,
    val at: Long? = null,
)

/** PPE-1 增补 D7 `agent-grant-list`（cap `agr1`），宿主 → 设备，一帧一个条目。 */
@Serializable
data class AgentGrantListPayload(
    val scope: String,
    val agentKey: String? = null,
    val wsId: String? = null,
    val access: String? = null,
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
