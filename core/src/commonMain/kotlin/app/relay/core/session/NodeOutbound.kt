// SPDX-License-Identifier: GPL-3.0-only

package app.relay.core.session

import app.relay.core.protocol.AgentEventAttachment
import app.relay.core.protocol.Codec
import app.relay.core.protocol.Limits
import app.relay.core.protocol.MsgTypes
import app.relay.core.protocol.ConvId
import app.relay.core.protocol.Profile
import app.relay.core.protocol.makeEnvelope
import app.relay.core.protocol.newId
import app.relay.core.transport.DIRECTION_OUT
import app.relay.core.transport.SendOutcome
import app.relay.core.protocol.Envelope
import app.relay.core.protocol.GroupMeta
import app.relay.core.transport.Messenger
import app.relay.core.transport.MessageStore
import app.relay.core.nowMillis
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 群扇出的总预算（毫秒）。
 *
 * 超过它就不再等剩下的成员：没完成的按 [SendOutcome.QUEUED] 计，并把同一条信封重新入队。
 * 取 10s 的依据：单个离线成员的退避 + TCP 兜底通常在这个量级内结束，所以正常离线群不会
 * 触碰上限；它挡的是病态情况（对端半死、多个端点各等一次 TCP 超时），让一条群消息不被
 * 一个慢成员无限拖住。
 */
internal const val GROUP_FANOUT_BUDGET_MS: Long = 10_000L

/**
 * 一个成员的扇出任务：他那一份信封的 JSON + 这次投递的结果。
 *
 * JSON 在扇出开始时编好并**留在任务里**，因为队列是按 `(peerId, msgId)` 分行的、
 * 而不同成员可能拿到**不同载荷**（见 [sendGroupText] 的能力门控）。预算到点要重放时，
 * 只有这份带着走的 JSON 能保证「重放的就是刚发出去的那一帧」。
 */
private class GroupFanOutJob(
    val member: String,
    val envelopeJson: String,
    val delivery: Deferred<SendOutcome>,
)

/**
 * 群文本的**并发扇出**：把 [members]（跳过 [selfId]）逐成员的信封同时投出去，并有总预算。
 *
 * 旧实现是 `for (member in members) { sendUserMessage(...) }` —— 串行 await，一个离线成员
 * 要等完整的退避 + TCP 兜底，N 人群里 M 个离线就是 M 倍。这里改成并发：墙钟时间从
 * 「成员数 × 单个超时」变成「最慢的那个」。
 *
 * ## 为什么先入队再取消
 *
 * 预算到点时，还在飞的成员必须先把**他那一份**信封的 JSON（[GroupFanOutJob.envelopeJson]）
 * 重新入队（[requeue]），再取消它的投递。反过来的话，那个成员会死在 `Messenger` 入队之前
 * —— 消息既没送达、也不在补发队列里，就是本仓最贵的那类静默丢失。重放的是**同一条**信封
 * （同一个 `id`）：万一原投递其实已经成功，对端的去重会挡掉第二份并照常回 ACK。
 *
 * ## 取消是可合作的
 *
 * `sendUserMessage` 在挂起点（退避 delay / TCP 超时）响应取消；[coroutineScope] 会等这些
 * 子任务收尾后才返回，所以本函数返回时不会留下还在跑的发送。
 *
 * @param envelopeFor 成员 → 他要收的那一份信封。**同一封装载不等于同一份字节**：
 *   带扩展键的群文本会给 legacy 成员去掉那两个键（见 [groupTextVariants]），
 *   但 `id`/`ts` 必须一致 —— 收端靠 id 去重，靠 ts 判断先后。
 * @param requeue 把原封不动的信封重新塞回待发队列；生产路径传 `Messenger.requeue`。
 * @param send 单个成员的投递动作；生产路径传 `Messenger.sendUserMessage`。
 */
internal suspend fun sendGroupTextFanOut(
    members: List<String>,
    selfId: String,
    budgetMs: Long,
    envelopeFor: (member: String) -> Envelope,
    requeue: suspend (member: String, envelopeJson: String, createdAt: Long) -> Boolean,
    send: suspend (member: String, envelope: Envelope) -> SendOutcome,
): SendOutcome {
    val targets = members.filter { it != selfId }.distinct()
    if (targets.isEmpty()) return SendOutcome.SENT
    // **不用 `coroutineScope` 包住扇出。**
    //
    // `coroutineScope` 的语义是「等所有子协程结束才返回」。而 `withTimeoutOrNull`
    // 超时只中断了 `await()`，**不会**让已经卡住的 `async` 结束 —— 于是 `coroutineScope`
    // 永远等下去，`sendGroupText` 永不返回，界面就永远停在「发送中」。
    //
    // 真机现场：单聊正常、群聊三条消息全部停在「发送中」**超过 60 秒**，
    // 而对端（红米）**已经收到了** —— 说明发送本身成功，卡的是「等所有成员结束」这件事。
    // 差别就在这一层：单聊没有扇出、没有 `coroutineScope`。
    //
    // 现在改用独立 [Job] 承载子协程：超时后 `cancel()` 它即可返回，不再等 join。
    // 代价是返回时可能还有投递在进行 —— 这对群聊是可接受的，`QUEUED` 的语义
    // 本来就是「至少有一个成员的那一份还在队列里」，从不承诺「所有成员都已结束」。
    val fanOutJob = SupervisorJob()
    val fanOutScope = CoroutineScope(fanOutJob + Dispatchers.Default)
    val jobs = targets.map { member ->
        val envelope = envelopeFor(member)
        GroupFanOutJob(member, Codec.encode(envelope), fanOutScope.async { send(member, envelope) })
    }
    val results = withTimeoutOrNull(budgetMs) {
        jobs.map { it.delivery.await() }
    }
    if (results != null) {
        fanOutJob.cancel()
        return if (results.all { it == SendOutcome.SENT }) SendOutcome.SENT else SendOutcome.QUEUED
    }
    // 预算到点：先把还在飞的成员重新入队，再取消它们的投递（顺序见 KDoc）。
    val unfinished = jobs.filter { !it.delivery.isCompleted }
    for (job in unfinished) {
        try {
            requeue(job.member, job.envelopeJson, nowMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            // 入队失败只能放弃这个成员；但不能让一个成员的异常改掉其余成员的结论。
        }
    }
    // **取消并返回，不等它结束。** 这正是本函数不再挂死的那一步。
    fanOutJob.cancel()
    return SendOutcome.QUEUED
}

/**
 * 一条群消息的**两个线上形态**：全量（带本仓扩展键）与旧形态（只含上游白名单字段）。
 *
 * 两者**共用同一个 `id` 与 `ts`** —— 变体只差 payload。这一点不是风格问题：
 * 收端按 `(id, from)` 去重、按 `ts` 排序，两个变体换了 id 就等于「同一条话在群里出现两次」，
 * 撤回也对不上（撤回按 targetId 找，只能撤掉其中一份）。
 *
 * [plain] 永远存在（所有成员都能收），[attributed] 只在真的带了扩展键时才与它不同。
 */
internal class GroupTextVariants(
    /** 只含上游白名单字段的那一份（`kind/text/groupId/groupRev/mentions/resend/replyTo`）。 */
    val plain: Envelope,
    /** 带 `senderId` / `reasoning` 的那一份；没有扩展键时它与 [plain] 是**同一个对象**。 */
    val attributed: Envelope,
) {
    /** 这条消息到底有没有本仓扩展键。没有 ⇒ 谁都不用做门控，字节完全一样。 */
    val hasExtensions: Boolean get() = attributed !== plain

    /** 该成员收哪一份。参数来自 `peerDeclares(member, CAP_GROUP_TEXT_ATTRIBUTED)`。 */
    fun forMember(supportsAttributed: Boolean): Envelope = if (supportsAttributed) attributed else plain
}

/**
 * 构造同 id 的两份群文本信封（见 [GroupTextVariants]）。
 *
 * 传进来的 [text] / [reasoning] 必须**已经截断**（[clampGroupText] / [clampGroupReasoning]）：
 * 这里只负责形状，不负责预算 —— 预算一旦有两个执行点，迟早有一处漏掉。
 */
internal fun groupTextVariants(
    from: String,
    text: String,
    groupId: String,
    groupRev: Int,
    mentions: List<String>? = null,
    replyTo: String? = null,
    senderId: String? = null,
    reasoning: String? = null,
    /**
     * 这条消息属于哪个 agent 会话（可选）。与 [agentCursor] 成对出现。
     *
     * 与 senderId / reasoning 同属**扩展键**：只有对端声明了 `Cap GroupTextAttributed`
     * 才带（见 GroupTextVariants）。旧对端不认识这些键，带过去就是整帧拒收。
     */
    agentSessionId: String? = null,
    /** 会话内游标（可选，与 [agentSessionId] 配对）。 */
    agentCursor: Long? = null,
    resend: Boolean? = null,
    id: String = newId(),
    ts: Long = nowMillis(),
): GroupTextVariants {
    val plain = makeEnvelope(
        MsgTypes.MSG,
        from,
        Codec.groupTextPayload(
            text, groupId, groupRev,
            mentions = mentions, resend = resend, replyTo = replyTo,
        ),
        id = id,
        ts = ts,
    )
    // **任何扩展键存在就必须走 attributed 那一份**。会话归属也是扩展键：
    // 只加进 senderId/reasoning 的判断而漏了它，带会话归属的消息就会被当成
    // 「没有扩展键」发 plain —— 接收端仍然画不出分段线，而发送侧一切正常。
    val hasExtendedKeys = senderId != null || reasoning != null ||
        (agentSessionId != null && agentCursor != null)
    if (!hasExtendedKeys) return GroupTextVariants(plain, plain)
    val attributed = makeEnvelope(
        MsgTypes.MSG,
        from,
        Codec.groupTextPayload(
            text, groupId, groupRev,
            mentions = mentions, resend = resend, replyTo = replyTo,
            senderId = senderId,
            reasoning = reasoning,
            agentSessionId = agentSessionId,
            agentCursor = agentCursor,
        ),
        id = id,
        ts = ts,
    )
    return GroupTextVariants(plain, attributed)
}

/**
 * 「重发」的输入：一条**已经发过**的消息，加上重建它所需的原字段。
 *
 * 为什么要调用方把 `text` / `mentions` / `senderId` 这些原样交回来：core 不替调用方
 * 反查历史（历史行可能已被裁剪），也不猜「这条当时是群消息还是私聊」。字段全在请求里，
 * 重放就是一次确定的、可测的映射。
 *
 * [msgId] **必须**是原来那条信封的 id —— 换一个 id 就是发了一条新消息（见
 * `NodeOutbound.replayOutgoing` 的 KDoc）。
 */
data class OutgoingReplay(
    /** 会话键：私聊 = 对端 nodeId；群聊 = `group:<groupId>`（见 [ConvId]）。 */
    val peerId: String,
    /** 原信封 id（= `MessageUi.id`）。重放沿用它，绝不新生成。 */
    val msgId: String,
    val text: String,
    /** 群聊必须同时给 [groupId]、[groupRev]、[members]；私聊三者都为 null。 */
    val groupId: String? = null,
    val groupRev: Int? = null,
    val members: List<String>? = null,
    val mentions: List<String>? = null,
    val replyTo: String? = null,
    val senderId: String? = null,
    val reasoning: String? = null,
    /** 原来入队的时刻（保住补发顺序与 TTL 起点）；默认现在。 */
    val createdAt: Long = nowMillis(),
)

/**
 * 出站：把一条意图变成一条协议帧，并落进消息历史。
 *
 * 切出来的理由：这些方法（22 个 `send*`）的演化动力是**协议在长** —— 每加一种
 * 新的可发送内容就多一个方法。而 [NodeRuntime] 剩下的部分是**生命周期与接线**
 * （start 的顺序即契约、端口自愈、收件箱、剪枝），两者的变化节奏完全不同。
 *
 * ## 为什么 selfId 是 `() -> String`
 *
 * [NodeRuntime.start] 会改写 `profile.tcpPort`（绑不上请求端口时退到系统分配的端口）。
 * 如果把 nodeId 捕获成一个 `String` 传进来，重启后就会用**旧** nodeId 发信 ——
 * 症状是「消息能发出去但被对端静默丢弃」，因为对端节点表里没有这条记录。
 * 传函数、每次现取，是为了让这个陷阱在类型上无法发生。
 *
 * @param peerDeclares 对端能力问句（生产路径接 `NodeRuntime.peerDeclares`）。
 *   收成函数而不是 [NodeRuntime]，是为了让**能力门控**能在没有真实网络/节点的单元测试里
 *   被逐成员验证 —— 门控是本仓最贵的一类静默（见 [sendGroupText] 的 gta1 一节）。
 */
internal class NodeOutbound(
    private val messenger: Messenger,
    private val messageStore: MessageStore,
    private val selfId: () -> String,
    private val selfProfile: () -> Profile,
    private val peerDeclares: (peerId: String, cap: String) -> Boolean = { _, _ -> false },
) {
    /**
     * 给一个 nodeId 发文本。
     *
     * 返回值语义（与茶话间一致，勿混）：
     * - [SendOutcome.SENT]：对方已 ACK；
     * - [SendOutcome.QUEUED]：**只代表已入队等对方上线**，不代表对方读过。
     */
    suspend fun sendText(peerId: String, text: String): SendOutcome =
        sendTextWithEnvelope(peerId, text).outcome

    /**
     * 给一个 nodeId 发文本，并把**这条信封本身**交回给调用方。
     *
     * 只想知道成不成功就用 [sendText]。这个重载多返回的那份 JSON 有两个用途：
     * core 自己写 agentNode 的 inbox 日志（见本方法体里的 `messageStore.append`），
     * 以及测试要断言"重放的是同一条信封"。
     *
     * **上层不需要拿它做持久化** —— 待发队列自己落盘（见 `core/persist/PersistStores`），
     * 返回 [SendOutcome.QUEUED] 的那条已经躺在 `<dataDir>/stores/queue.log` 里，
     * 重启后由队列自己继续补发。早期版本要求上层把信封原样存下来、重启时喂回
     * [requeueOutgoing]，那是因为队列一度只是进程内的（[InMemoryQueueStore]）。
     */
    suspend fun sendTextWithEnvelope(peerId: String, text: String): OutgoingText {
        val envelope = makeEnvelope(MsgTypes.MSG, selfId(), Codec.textPayload(text))
        val outcome = messenger.sendUserMessage(peerId, envelope)
        val envelopeJson = Codec.encode(envelope)
        messageStore.append(
            peerId = peerId,
            direction = DIRECTION_OUT,
            envelopeJson = envelopeJson,
            at = nowMillis(),
        )
        return OutgoingText(outcome = outcome, envelopeId = envelope.id, envelopeJson = envelopeJson)
    }

    /**
     * 撤回一条消息：发 `msg(kind:"recall", targetId)`。
     *
     * 投递语义与普通消息**完全一致**（协议文档 §8：「撤回指令与普通消息一样走 ACK / 重传 /
     * 离线补发」），所以走 [Messenger.sendUserMessage]：对端不在线时它会入队，等对端上线补发。
     * 群聊撤回要带上 `groupId`/`groupRev`（两个成对，见 [Codec.recallPayload]）。
     *
     * **不写 messageStore**：撤回是控制帧，不是一条聊天记录。写进去会让它出现在 `inbox()` /
     * `history()` 里，重启后界面上多出一条没有正文的"消息"。
     */
    suspend fun sendRecall(
        peerId: String,
        targetId: String,
        groupId: String? = null,
        groupRev: Int? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(MsgTypes.MSG, selfId(), Codec.recallPayload(targetId, groupId, groupRev)),
    )

    /**
     * 发一条群文本（`msg(kind:"group-text")`）给 [members] 里的每个人。
     *
     * ### 一条信封 id，扇出 N 份（载荷可能不同）
     * 群聊在协议上是**逐成员单播**，没有组播地址。扇出去的是**同一个信封 id 与 ts**：
     * 先 `makeEnvelope` 一次、本地入库一次，再对成员**并发**调用 `sendUserMessage`（见下）。
     * 每个成员各自 ACK、各自入队、各自重传，但**消息 id 只有一个**，所以：
     * - 每个收端入库的是同一条消息，多设备/多端看到的 id 一致，撤回才对得上；
     * - 不会出现"同一条话在 N 个成员那里有 N 个不同 id"这种没法撤回的状态。
     *
     * ### 扩展键按对端能力门控（`gta1`）—— 兼容性后果
     *
     * `senderId` / `reasoning` 是本仓加在 `group-text` 上的两个可选键，而上游茶话间对这份
     * 载荷做的是**严格字段白名单**：多一个键 ⇒ 整帧丢弃。所以「带扩展键的群消息发给老端」
     * 不是"对方少看到归属信息"，而是**这条消息对方根本收不到**（发送侧仍 SENT、本地仍落库，
     * 就是本仓 §3 那类静默丢失）。
     *
     * 因此每一份载荷都按**该成员**是否声明了 [MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED] 决定：
     * - 声明了 ⇒ 收全量那份（带 `senderId` / `reasoning`）；
     * - 没声明（茶话间、老 Tidings 构建）⇒ 收 [GroupTextVariants.plain]，**同一个 id**，
     *   只少这两个键；正文、mentions、replyTo、resend 一个不少。
     *
     * 判据只用 caps，**不看 `ver`、也不拿 `agn1` 当代理**：`agn1` 说的是 agent 会话帧，
     * 与群文本白名单毫无关系；版本号是自由字符串，拿它做能力判定等于让每个发送方各维护一份
     * 版本表。老 Tidings 节点即使有 `agn1`，只要没声明 `gta1` 就按 legacy 发。
     *
     * 队列按 `(peerId, msgId)` 分行存**各自那份** JSON，所以补发/重放时对 legacy 成员
     * 塞回去的仍是不带扩展键的版本 —— 不会因为"补发时重算了一次"把老端再次整帧拒收。
     *
     * ### 本地历史存哪一份
     *
     * 存**全量（带扩展键）那一份**。本地读者就是本构建（`GROUP_TEXT_KEYS` 认识这两个键），
     * 存裁剪版等于把自己写下的归属与思考过程从历史里抹掉；而线上形状是**逐成员**的协议事实，
     * 不属于"这条消息是什么"。这也让「本地落库用什么、界面显示什么」保持同一个来源。
     *
     * ### 不做按成员回执（简化策略）
     * 群消息不做按成员回执：本端入库即 sent，离线成员由补发队列保送达。本方法同样**不返回逐成员结果**，只回一个
     * [SendOutcome]：全部成员都 ACK 了才是 [SendOutcome.SENT]，否则 [SendOutcome.QUEUED]。
     *
     * 为什么不自己发明一套"3/5 已送达"：那要求给每个成员各造一条记录并逐条跟踪，
     * 而协议里没有任何字段能让对端知道"这是同一条群消息的第几份"。半套回执比没有回执更糟
     * —— 界面会显示一个看起来精确、实际上只统计到"我们发出去的那几份"的数字。
     *
     * ### 并发扇出 + 总预算（兼容性后果）
     *
     * 旧实现是 `for (member in members) { sendUserMessage(...) }` —— **串行 await**。
     * 一个离线成员要等完整的退避 + TCP 兜底（量级见 Timings），4 人群 3 人离线就是
     * 三倍。现在的实现（见 [sendGroupTextFanOut]）：
     * - 所有成员**同时**开始投递，墙钟时间从「成员数 × 单个超时」变成「最慢的那个」；
     * - 总预算 [GROUP_FANOUT_BUDGET_MS] 到点后，还没完成的成员按 [SendOutcome.QUEUED] 计，
     *   并且把**同一条信封**重新入队（`Messenger.requeue`）—— 先入队再取消，顺序不能反，
     *   否则那个成员会死在入队之前，就是本仓最贵的那类**静默丢失**。
     *
     * **返回值语义不变**：仍然「全部成员都 ACK 了才是 SENT，否则 QUEUED」。变的只有
     * 达成它所需的时间，以及超预算成员从「一直等到超时」变成「立即记 QUEUED 并等补发」。
     * 单成员群（[members] 里只有自己）仍直接回 SENT，与旧实现一致。
     *
     * [groupRev] 是发送时的群版本：对端据此判断自己的成员表是不是旧的，旧了就回
     * `group{op:"need"}`。**用本地当前 rev** —— 收端只比较大小。
     *
     * 本地历史写进 `group:<groupId>` 这个会话键（与点对点群聊 `conv_id` 规范一致），
     * 而不是收件人的 nodeId：按 nodeId 存会得到 N 条一模一样的记录散在 N 个私聊会话里。
     *
     * @param members 群成员表（含自己）；自己会被跳过。
     */
    suspend fun sendGroupText(
        text: String,
        groupId: String,
        groupRev: Int,
        members: List<String>,
        mentions: List<String>? = null,
        replyTo: String? = null,
        /** 真正的说话者（可选）。见 `GroupTextMsgPayload.senderId` 的 KDoc。 */
        senderId: String? = null,
        /**
         * 思考过程（可选）。见 `GroupTextMsgPayload.reasoning` 的 KDoc。
         *
         * 超长在这里截断（[Limits.GROUP_REASONING_BYTES]，UTF-8 字节）：接收侧把超限的帧
         * 整帧拒收，所以「不截就发」的后果不是显示变短，而是**这条消息彻底不到**。
         */
        reasoning: String? = null,
        /**
         * 这条消息属于哪个 agent 会话（可选，与 [agentCursor] 成对）。带上它，
         * 接收端才能画出「任务分段线」—— 在此之前那条线只存在于桌面端本地。
         */
        agentSessionId: String? = null,
        /** 会话内游标（可选，与 [agentSessionId] 配对）。 */
        agentCursor: Long? = null,
        /**
         * 这次发送用的信封 id；**不传就生成一个**。
         *
         * 为什么要能传：群聊的乐观插入需要一个**从一开始就等于信封 id** 的本地行 id ——
         * 否则「重发」只能按一个合成 id 重建，对端会把同一条话收成两条（真机现场），
         * 重启时的排队对账（按信封 id 匹配历史行）也永远对不上。
         */
        envelopeId: String? = null,
    ): SendOutcome {
        val variants = groupTextVariants(
            from = selfId(),
            id = envelopeId ?: newId(),
            text = clampGroupText(text),
            groupId = groupId,
            groupRev = groupRev,
            mentions = mentions,
            replyTo = replyTo,
            senderId = senderId,
            reasoning = clampGroupReasoning(reasoning),
            agentSessionId = agentSessionId,
            agentCursor = agentCursor,
        )
        messageStore.append(
            peerId = ConvId.group(groupId),
            direction = DIRECTION_OUT,
            envelopeJson = Codec.encode(variants.attributed),
            at = nowMillis(),
        )
        return sendGroupTextFanOut(
            members = members,
            selfId = selfId(),
            budgetMs = GROUP_FANOUT_BUDGET_MS,
            envelopeFor = { member ->
                variants.forMember(peerDeclares(member, MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED))
            },
            requeue = { member, json, createdAt -> messenger.requeue(member, json, createdAt) },
            send = { member, envelope -> messenger.sendUserMessage(member, envelope) },
        )
    }

    /**
     * 「重发」：按**原信封 id** 重放一条已经发过的消息，而不是把它当成一条新消息再发一遍。
     *
     * ## 为什么必须按原 id
     *
     * 换个 id 重发会让对方**入库两条**：收端按 `(id, from)` 去重，两个不同 id 就是两条不同的
     * 消息（桌面真机库读数：同一句话在 `MessageEntity` 里是 `f93a27cf…` 与 `0137bff5…` 两条）。
     * 而且第二条有自己的撤回、引用与搜索命中 —— 用户看到的是「我按了一次重发，群里多了一句话」。
     *
     * ## 两条路，优先级不能反
     *
     * 1. **队列里还有原信封**（`failed` / `queued` 的那条还躺着）：按 `(peerId, msgId)` 原样取出来
     *    重新入队并立刻排空一次。这条最忠实 —— 除了补发时统一加的 `resend` 之外，我们一个字节都不动。
     * 2. **队列里没有**（已被 ACK、被剪枝，或换了进程）：用 `id = 原 id` **重建**载荷，
     *    并打上 `resend = true`。重建只能靠调用方交回原字段（[OutgoingReplay]）。
     *
     * 两条路都**不写 messageStore**：这条消息的历史行已经在库里，再 append 一次就是本地的第二条。
     *
     * ## 群聊的「那一条」是逐成员的
     *
     * 群消息在队列里是**每个成员一行**（键 `(peerId, msgId)`，peerId 是成员 nodeId），
     * 没有「一条群消息」的行可查。所以群聊重放一律走第 2 条路按成员重建，并且逐成员
     * 继续做 `gta1` 门控 —— 对 legacy 成员塞回带扩展键的版本会被整帧拒收，也就是
     * 「重发点了没反应」的另一个成因。
     *
     * @return [SendOutcome.SENT] = 这次重放拿到了 ACK；[SendOutcome.QUEUED] = 仍在队列里等。
     */
    suspend fun replayOutgoing(request: OutgoingReplay): SendOutcome {
        val groupId = request.groupId
        if (groupId != null) {
            val members = request.members
                ?: error("群聊重放必须给出成员表：队列行是逐成员的，猜不出来")
            val groupRev = request.groupRev
                ?: error("群聊重放必须给出 groupRev：对端拿它判自己的成员表旧不旧")
            val variants = groupTextVariants(
                from = selfId(),
                text = clampGroupText(request.text),
                groupId = groupId,
                groupRev = groupRev,
                mentions = request.mentions,
                replyTo = request.replyTo,
                senderId = request.senderId,
                reasoning = clampGroupReasoning(request.reasoning),
                resend = true,
                id = request.msgId,
            )
            return sendGroupTextFanOut(
                members = members,
                selfId = selfId(),
                budgetMs = GROUP_FANOUT_BUDGET_MS,
                envelopeFor = { member ->
                    variants.forMember(peerDeclares(member, MsgTypes.CAP_GROUP_TEXT_ATTRIBUTED))
                },
                requeue = { member, json, createdAt -> messenger.requeue(member, json, createdAt) },
                send = { member, envelope -> messenger.sendUserMessage(member, envelope) },
            )
        }
        // 单聊：先看队列里有没有原信封。
        val queued = messenger.queuedMessage(request.peerId, request.msgId)
        if (queued != null) {
            // 用**原来那一行的入队时刻**：补发不是新消息，不该排到队尾、也不该把 TTL 重新算。
            messenger.requeue(request.peerId, queued.envelopeJson, queued.created)
            // requeue 在线时会自己排空一次；那一次可能已经有人在跑 ⇒ 这条会**等它结束**
            // 再回答，不能因为"别人正在排空"就报 QUEUED（见 Messenger.flushQueue）。
            messenger.flushQueue(request.peerId)
            return if (messenger.queuedMessage(request.peerId, request.msgId) == null) {
                SendOutcome.SENT
            } else {
                SendOutcome.QUEUED
            }
        }
        // 正文与 [sendText] 同口径**不截断**：单聊的 4096 上限是既有限制，在重发这一条路上
        // 偷偷截断会改掉用户可见内容；超长正文要么当初就发不出去，要么在这里照原样再试一次。
        val envelope = makeEnvelope(
            MsgTypes.MSG,
            selfId(),
            Codec.textPayload(request.text, resend = true),
            id = request.msgId,
        )
        return messenger.sendUserMessage(request.peerId, envelope)
    }

    suspend fun sendPk(
        peerId: String,
        game: String,
        value: String,
        groupId: String? = null,
        groupRev: Int? = null,
    ): OutgoingText {
        val envelope = makeEnvelope(
            MsgTypes.MSG,
            selfId(),
            Codec.pkPayload(game, value, groupId, groupRev),
        )
        val outcome = messenger.sendUserMessage(peerId, envelope)
        val envelopeJson = Codec.encode(envelope)
        messageStore.append(
            peerId = peerId,
            direction = DIRECTION_OUT,
            envelopeJson = envelopeJson,
            at = nowMillis(),
        )
        return OutgoingText(outcome = outcome, envelopeId = envelope.id, envelopeJson = envelopeJson)
    }

    suspend fun sendGroupPk(
        game: String,
        value: String,
        groupId: String,
        groupRev: Int,
        members: List<String>,
    ): OutgoingText {
        val envelope = makeEnvelope(
            MsgTypes.MSG,
            selfId(),
            Codec.pkPayload(game, value, groupId, groupRev),
        )
        val envelopeJson = Codec.encode(envelope)
        messageStore.append(
            peerId = ConvId.group(groupId),
            direction = DIRECTION_OUT,
            envelopeJson = envelopeJson,
            at = nowMillis(),
        )
        var allSent = true
        for (member in members) {
            if (member == selfId()) continue
            val outcome = messenger.sendUserMessage(member, envelope)
            if (outcome != SendOutcome.SENT) allSent = false
        }
        val finalOutcome = if (allSent) SendOutcome.SENT else SendOutcome.QUEUED
        return OutgoingText(outcome = finalOutcome, envelopeId = envelope.id, envelopeJson = envelopeJson)
    }

    /**
     * 广播一份群元数据（`group{op:"info"}`）给一个成员。
     *
     * 走 [Messenger.sendUserMessage]（可靠 + 离线入队），与规范广播策略一致。
     * 这个选择是**功能性的**不是顺手：建群时被拉进来的人很可能正离线，"回来即知道自己
     * 进了群"全靠这条队列。换成 [sendGroupInfoReply] 那种不等 ACK 的路子，离线成员就
     * 永远不知道有这个群，症状是"他被拉进群了，但他那边什么都没有"。
     */
    suspend fun sendGroupInfo(peerId: String, meta: GroupMeta): SendOutcome =
        messenger.sendUserMessage(
            peerId,
            makeEnvelope(MsgTypes.GROUP, selfId(), Codec.groupInfoPayload(meta)),
        )

    /**
     * 回一份群元数据给**刚刚**向我们要过的人（`op:"need"` 的应答）。
     *
     * 与 [sendGroupInfo] 的差别是投递语义，不是措辞：对方上一条消息刚到手，说明他此刻
     * 在线，所以不等 ACK、不入离线队列（使用 `sendReliableNoQueue`）。入队反而有害 ——
     * 一份过期快照在 7 天后被补发出去，会把对方的群成员表**改回旧版本**。
     */
    suspend fun sendGroupInfoReply(peerId: String, meta: GroupMeta): Boolean =
        messenger.sendReliableNoQueue(
            peerId,
            makeEnvelope(MsgTypes.GROUP, selfId(), Codec.groupInfoPayload(meta)),
        )

    /**
     * 请对方把某个群的元数据发我（`group{op:"need"}`）。
     *
     * 同样不入队：这条请求的触发条件是"我刚收到一条 rev 比我新的群消息"，对方此刻必然
     * 在线。它还是**幂等**的 —— 丢了就等下一次群消息再问。
     */
    suspend fun sendGroupNeed(peerId: String, groupId: String): Boolean =
        messenger.sendReliableNoQueue(
            peerId,
            makeEnvelope(MsgTypes.GROUP, selfId(), Codec.groupNeedPayload(groupId)),
        )

    /**
     * 不入队发送可靠单播信封（用于 control / scan-ranges / gossip 帧）。
     */
    suspend fun sendReliableNoQueue(peerId: String, envelope: Envelope): Boolean =
        messenger.sendReliableNoQueue(peerId, envelope)

    /**
     * 戳一碰（私聊窗口震动）：发 `msg(kind:"nudge")`。
     *
     * 用 [Messenger.sendReliableNoQueue] 而不是 [Messenger.sendUserMessage]：协议要求它
     * 「若对端无响应则失败且**不进入离线补发队列**」。
     *
     * @return 对端有没有 ACK 这一帧。`false` = 现在戳不到，调用方如实告诉用户。
     */
    suspend fun sendNudge(peerId: String): Boolean = messenger.sendReliableNoQueue(
        peerId,
        makeEnvelope(MsgTypes.MSG, selfId(), Codec.nudgePayload()),
    )

    /**
     * 发一条 agent 会话宣告（PPE-1 §6.1，类型 `agent-session`）。
     *
     * 投递语义与普通消息**一致**（走 ACK / 重传 / 离线补发）：§10 要求任何"已送达"都有真实
     * 回执，而 P0 能拿到的只有传输层 ACK（`agent-ack` 属 P1）。
     *
     * **不写 messageStore**：会话宣告是控制帧，不是一条聊天记录 —— 写进去会让它出现在
     * `inbox()` / `history()` 里，界面上多出一条没有正文的"消息"（与 [sendRecall] 同理）。
     *
     * **本方法不查对端 caps**：§4 要求发 `agent-*` 前先确认对方声明了 `agn1`，但这一层只
     * 负责发送。调用方必须先问 [peerDeclares]，否则对端会静默丢弃、本端一直重传到入队，
     * 表现成"对方离线"这个查不出来的假故障。
     */
    @Suppress("LongParameterList")
    suspend fun sendAgentSession(
        peerId: String,
        sessionId: String,
        alias: String? = null,
        agentKind: String? = null,
        state: String? = null,
        title: String? = null,
        startedAt: Long? = null,
        agentId: String? = null,
        wsId: String? = null,
        wsTitle: String? = null,
        wsPath: String? = null,
        updatedAt: Long? = null,
        origin: String? = null,
        blank: Boolean? = null,
    ): SendOutcome {
        // PPE-1 增补 §2.4 的 cap 门控，全仓只在这一处：四个新键只对声明了 `aws1` 的
        // 对端附带，没声明时**退回旧形状**（会话宣告照发，新键置 null）。
        // 放在这里而不是各调用方：手机、agentNode、桌面内嵌三个发送方共用这一层，
        // 门控写两遍迟早有一处漏 —— 漏了就是把对端没承诺过的能力替它宣称出去。
        val withWs = peerDeclares(peerId, MsgTypes.CAP_AGENT_WORKSPACE)
        return messenger.sendUserMessage(
            peerId,
            makeEnvelope(
                MsgTypes.AGENT_SESSION,
                selfId(),
                Codec.agentSessionPayload(
                    sessionId, alias, agentKind, state, title, startedAt,
                    agentId = if (withWs) agentId else null,
                    wsId = if (withWs) wsId else null,
                    wsTitle = if (withWs) wsTitle else null,
                    wsPath = if (withWs) wsPath else null,
                    // 不跟 `withWs` 门控：活动时刻属于**会话**，不属于工作区身份，
                    // 与 `startedAt` 同一档（对端若还不认识这个尾键，`tolerantKeys` 会容忍它）。
                    updatedAt = updatedAt,
                    // 与 `updatedAt` 同一档：可见性是**会话自身**的属性，不是工作区身份，
                    // 所以不跟 `withWs` 门控。
                    origin = origin,
                    blank = blank,
                ),
            ),
        )
    }

    /** 发一条 agent 事件（PPE-1 §6.2，类型 `agent-event`）。语义同 [sendAgentSession]。 */
    suspend fun sendAgentEvent(
        peerId: String,
        sessionId: String,
        cursor: Long,
        eventType: String,
        summary: String? = null,
        body: String? = null,
        ref: String? = null,
        /**
         * 附件元数据（task-45，cap `att1`）。**默认 null ⇒ 既有行为逐字不变**。
         *
         * 闸门不在这里：调用方（`agentNode` 的控制面）先按对端 caps 判断要不要带，
         * 只有对端声明了 `att1` 才传下来 —— 旧端因此不会收到带这个键的帧。
         */
        attachment: AgentEventAttachment? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_EVENT,
            selfId(),
            Codec.agentEventPayload(sessionId, cursor, eventType, summary, body, ref, attachment),
        ),
    )

    suspend fun sendAgentQueue(
        peerId: String,
        taskId: String,
        sessionId: String? = null,
        op: String,
        prompt: String? = null,
        queuedAt: Long? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_QUEUE,
            selfId(),
            Codec.agentQueuePayload(taskId, sessionId, op, prompt, queuedAt),
        ),
    )

    suspend fun sendAgentRun(
        peerId: String,
        sessionId: String,
        runId: String,
        state: String,
        at: Long? = null,
        note: String? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_RUN,
            selfId(),
            Codec.agentRunPayload(sessionId, runId, state, at, note),
        ),
    )

    suspend fun sendAgentApproval(
        peerId: String,
        approvalId: String,
        sessionId: String,
        tool: String? = null,
        summary: String? = null,
        expiresAt: Long,
        choices: String? = null,
        riskLevel: String? = null,
        diff: String? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_APPROVAL,
            selfId(),
            Codec.agentApprovalPayload(approvalId, sessionId, tool, summary, expiresAt, choices, riskLevel, diff),
        ),
    )

    suspend fun sendAgentCancel(
        peerId: String,
        sessionId: String,
        runId: String? = null,
        reason: String? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_CANCEL,
            selfId(),
            Codec.agentCancelPayload(sessionId, runId, reason),
        ),
    )

    suspend fun sendAgentAck(
        peerId: String,
        ackOf: String,
        kind: String,
        at: Long? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_ACK,
            selfId(),
            Codec.agentAckPayload(ackOf, kind, at),
        ),
    )

    /**
     * 发一条产物元数据（PPE-1 §6.7，类型 `agent-artifact`，cap `agn1`）。
     *
     * 归 `agn1` 而不是 `agq1`：它和 §6.1/§6.2 一样只描述会话，不带控制语义。
     * **调用方必须先发 `file-msg`**，这里的 `fileId` 只是引用；本方法不代发二进制。
     */
    suspend fun sendAgentArtifact(
        peerId: String,
        sessionId: String,
        artifactId: String,
        name: String,
        fileId: String? = null,
        bytes: Long? = null,
        mime: String? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_ARTIFACT,
            selfId(),
            Codec.agentArtifactPayload(sessionId, artifactId, name, fileId, bytes, mime),
        ),
    )

    /**
     * 发一条工作区宣告（PPE-1 增补 D3，类型 `agent-workspace`，cap `aws1`）。
     *
     * **可靠但不入队**（[Messenger.sendReliableNoQueue]）：这帧必须在 RELIABLE 里才会被
     * 对端投进 `inbound`（`Messenger.onDatagram` 只投 RELIABLE 类型），但它是一份**状态快照**，
     * 不是"必须送达一次"的指令 —— 改名 / 新增 / 移除时本来就会重发。
     *
     * 为什么不能用 `sendUserMessage`：避免在失败时误将对端置为离线。
     *
     * @return 对端有没有 ACK。`false` = 这一台没收到（多半是旧版本），调用方如实回执，
     *         但**台账照记** —— 宣告的是本宿主的工作区，与某一台收没收到无关。
     */
    @Suppress("LongParameterList")
    suspend fun sendAgentWorkspace(
        peerId: String,
        wsId: String,
        wsTitle: String? = null,
        wsPath: String? = null,
        agentId: String? = null,
        agentKind: String? = null,
        online: Boolean? = null,
        sessionCount: Long? = null,
        at: Long? = null,
        order: Long? = null,
    ): Boolean = messenger.sendReliableNoQueue(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_WORKSPACE,
            selfId(),
            Codec.agentWorkspacePayload(wsId, wsTitle, wsPath, agentId, agentKind, online, sessionCount, at, order),
        ),
    )

    /**
     * 向宿主要一页会话清单（PPE-1 增补 D8，设备 → 宿主，cap `agp1`）。
     *
     * 用 [Messenger.sendReliableNoQueue] 而不是 [Messenger.sendUserMessage]：这帧是请求，
     * 对端不认识它时不该把对方标离线、更不该把一条请求入队到 7 天后再发
     * （与 agent-workspace 同一条纪律，见 ReliableNoQueueTest）。
     */
    suspend fun sendAgentSessionPage(
        peerId: String,
        wsId: String,
        offset: Long? = null,
        limit: Long? = null,
        at: Long? = null,
    ): Boolean = messenger.sendReliableNoQueue(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_SESSION_PAGE,
            selfId(),
            Codec.agentSessionPagePayload(wsId, offset, limit, at),
        ),
    )

    /** 发一条授权申请（PPE-1 增补 D7，设备 → 宿主，cap `agr1`）。 */
    suspend fun sendAgentGrantRequest(
        peerId: String,
        agentKey: String? = null,
        wsId: String? = null,
        access: String? = null,
        reason: String? = null,
        at: Long? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_GRANT_REQUEST,
            selfId(),
            Codec.agentGrantRequestPayload(agentKey, wsId, access, reason, at),
        ),
    )

    /** 发一条授权决定（PPE-1 增补 D7，宿主 → 设备，cap `agr1`）。 */
    suspend fun sendAgentGrant(
        peerId: String,
        state: String,
        scope: String,
        agentKey: String? = null,
        wsId: String? = null,
        access: String? = null,
        isMyDevice: Boolean? = null,
        delegatedApproval: Boolean? = null,
        at: Long? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_GRANT,
            selfId(),
            Codec.agentGrantPayload(state, scope, agentKey, wsId, access, isMyDevice, delegatedApproval, at),
        ),
    )

    /** 发一条授权清单条目（PPE-1 增补 D7，宿主 → 设备，cap `agr1`）。 */
    suspend fun sendAgentGrantList(
        peerId: String,
        scope: String,
        agentKey: String? = null,
        wsId: String? = null,
        access: String? = null,
        total: Long? = null,
        at: Long? = null,
    ): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AGENT_GRANT_LIST,
            selfId(),
            Codec.agentGrantListPayload(scope, agentKey, wsId, access, total, at),
        ),
    )

    suspend fun sendAvatarGet(peerId: String, hash: String): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AVATAR,
            selfId(),
            Codec.avatarGetPayload(hash),
        ),
    )

    suspend fun sendAvatarData(peerId: String, hash: String, dataBase64: String): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AVATAR,
            selfId(),
            Codec.avatarDataPayload(hash, dataBase64),
        ),
    )

    suspend fun sendAvatarMiss(peerId: String, hash: String): SendOutcome = messenger.sendUserMessage(
        peerId,
        makeEnvelope(
            MsgTypes.AVATAR,
            selfId(),
            Codec.avatarMissPayload(hash),
        ),
    )
}

/**
 * 把思考过程截到 [Limits.GROUP_REASONING_BYTES] 个 UTF-8 字节以内（超长则保留开头 + 省略号）。
 *
 * ## 为什么必须截，而不是「发出去再说」
 *
 * 接收侧对 `reasoning` 做的是**字节上限校验**，超限 = 整帧 `badPayload` 丢弃。
 * 所以不截断的后果不是「对方显示得短一点」，而是**这条消息彻底不到** ——
 * 与「正文顶到 4096 上限被拒收」是同一类静默丢失。
 *
 * ## 为什么按字节切而不是 `take(n)`
 *
 * `take(n)` 数的是 UTF-16 码元，中文一个字 3 字节：同样 4096 个码元，中文是 12 KB、
 * 英文是 4 KB —— 用码元切会得到一个**比上限还大**的字符串。切点还必须落在码点边界上：
 * 从中间切开一个代理对会留下半个字符（界面显示成 U+FFFD 方块）。
 */
internal fun clampGroupReasoning(reasoning: String?): String? =
    clampUtf8(reasoning?.takeIf { it.isNotBlank() }, Limits.GROUP_REASONING_BYTES)

/**
 * 群文本**正文**在发送前的截断（[Limits.GROUP_TEXT_TCP_LIMIT]）。
 *
 * 与思考过程同一个理由，只是上限不同：接收侧的正文校验一旦不过就是整帧丢弃，
 * 而 4096 字节这个旧上限**真机上已经吃掉了 140 条群消息**（最大 9902 字节）。
 * 上限抬到 32 KiB 之后仍要留一道截断，是为了让「发送侧永远不产出注定被拒的帧」
 * 成为一条**不变量**，而不是「希望 agent 别写太长」。
 *
 * 只在超过上限时才截；正常长度的正文原样返回（不做任何 trim —— 那会改掉用户可见内容）。
 */
internal fun clampGroupText(text: String): String =
    clampUtf8(text, Limits.GROUP_TEXT_TCP_LIMIT) ?: text

/**
 * 按 UTF-8 字节截断（超长则保留开头 + 省略号）。
 *
 * ## 为什么必须截，而不是「发出去再说」
 *
 * 接收侧对正文与思考过程做的都是**字节上限校验**，超限 = 整帧 `badPayload` 丢弃。
 * 所以不截断的后果不是「对方显示得短一点」，而是**这条消息彻底不到**。
 *
 * ## 为什么按字节切而不是 `take(n)`
 *
 * `take(n)` 数的是 UTF-16 码元，中文一个字 3 字节：同样 4096 个码元，中文是 12 KB、
 * 英文是 4 KB —— 用码元切会得到一个**比上限还大**的字符串。切点还必须落在码点边界上：
 * 从中间切开一个代理对会留下半个字符（界面显示成 U+FFFD 方块）。
 */
internal fun clampUtf8(text: String?, limit: Int): String? {
    val raw = text ?: return null
    if (Codec.utf8ByteLength(raw) <= limit) return raw

    val ellipsis = "…"
    val budget = limit - Codec.utf8ByteLength(ellipsis)
    val kept = StringBuilder()
    var used = 0
    var i = 0
    while (i < raw.length) {
        val head = raw[i]
        val pair = head.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()
        val codePoint = if (pair) {
            0x10000 + ((head.code - 0xD800) shl 10) + (raw[i + 1].code - 0xDC00)
        } else {
            head.code
        }
        val width = utf8Width(codePoint)
        if (used + width > budget) break
        val step = if (pair) 2 else 1
        kept.append(raw, i, i + step)
        used += width
        i += step
    }
    return kept.toString() + ellipsis
}

/** 一个码点编成 UTF-8 要几个字节（与 `Codec.utf8ByteLength` 同一口径）。 */
private fun utf8Width(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}
