// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.relay.app.model.MessageUi
import app.relay.app.model.ScreenRecordUi
import app.relay.core.protocol.ScreenPhase
import app.relay.platform.AppPlatform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.relay.app.model.DeliveryState
import app.relay.app.model.FileCardUi
import app.relay.app.model.TransferState
import app.relay.app.model.SCREEN_RECORD_VERSION

// ------------------------------------------------------------------ 消息落盘

/**
 * 聊天记录的落盘契约。
 *
 * 为什么需要它：控制器里的消息流是纯内存 `mutableMapOf`，所以聊天页顶部那条
 * 「以下为昨天的消息 —— 本地未落盘，重启会丢失」不是文案夸张，而是对现状的如实描述 ——
 * 用户看到这句话的同时，历史确实正在被丢掉。要让它变成一个**能兑现的承诺**，
 * 唯一诚实的做法是把"存到哪儿"从控制器里抽出来变成可注入的契约：
 * 控制器只决定**什么时候存**，**怎么存**交给平台层。
 *
 * 刻意只依赖 commonMain：真正的落盘要文件 IO，那是平台层的事，
 * 控制器既不该碰、也碰不到（commonMain 里没有 expect/actual 的位置留给它）。
 */
interface MessageStore {
    /** 读回全部会话，key 是对端 nodeId。返回空 map 表示"没有历史"，不是错误。 */
    fun load(): Map<String, List<MessageUi>>

    /**
     * 整份覆盖写。
     *
     * 刻意不做增量/追加：消息流随时会改**已存在的**那几条（投递状态从 Queued 变 Sent、
     * 文件卡片换状态），增量写要先答对"哪几条变了"，比全量写更容易写错；
     * 而聊天记录本来就是"一条消息几百字节 × 一个会话几千条"的量级，全量写完全够用。
     */
    fun save(threads: Map<String, List<MessageUi>>)

    /**
     * 读回**每个会话最近** [limitPerConversation] 条。
     *
     * 为什么需要它：`load()` 是全量（真机 6656 条 / 约 15 MB 常驻），而界面一次
     * 只看得到最近几十条。分页读把常驻压到「会话数 × N」。
     *
     * **窗口必须包含每个会话的最新消息**：会话列表的预览、最后消息时间、排序全部
     * 依赖「该会话最后一条」。只加载当前打开的会话、把其它会话整个丢掉，会让
     * 会话列表的预览与排序集体失真。所以这里按会话各取一个尾部窗口，
     * 而不是只加载某一个会话。
     *
     * 默认实现对「整份读进内存」的存储（iOS 的 JSON 快照）是**事后截断**：
     * 峰值内存省不掉，但常驻量能降下来，行为与 SQLite 那份一致。
     */
    fun loadRecent(limitPerConversation: Int): Map<String, List<MessageUi>> =
        load()
            .mapValues { (_, list) -> list.takeLast(limitPerConversation.coerceAtLeast(0)) }
            .filterValues { it.isNotEmpty() }

    /**
     * 读某个会话里**比 [beforeSeq] 更早**的 [limit] 条（游标向上翻页）。
     *
     * 为什么用 seq 而不是时间戳：同一毫秒可能有多条（agent 流式回复尤甚），
     * 拿 at 当游标会重复或漏。seq 在会话内单调递增，游标天然无重无漏。
     *
     * 返回按时间升序（与 [loadRecent] 一致），调用方直接插到窗口头部即可。
     * 默认空实现：整份读的存储没有"更早"这一说（[loadRecent] 已经给了全部能给的）。
     */
    fun loadOlder(convId: String, beforeSeq: Long, limit: Int): List<MessageUi> = emptyList()


    /**
     * 清空全部历史。
     *
     * 与 `save(emptyMap())` 分开，是因为"删干净"对存储实现往往有额外含义
     * （删文件、清密钥、回收空间），把意图写进签名比靠调用方约定可靠。
     */
    fun clear()

    /**
     * 删一条消息。
     *
     * 为什么需要它：[save] 只做 upsert，**不再**从"内存里没有"反推"库里该删"。
     * 那条反推一旦遇到分页（内存只保留一个窗口）就会把窗口之外的历史整批删掉 ——
     * 那不是性能问题，是数据丢失。所以删除必须由调用方显式说出来。
     *
     * 默认空实现是**正当**的：整份覆盖型的存储（内存实现、iOS 的 JSON 快照）
     * 在下一次 [save] 时自然就不含这条了，删除语义由覆盖写本身完成。
     * SQLite 那种逐行 upsert 的存储必须覆盖它，否则删除不会真的发生。
     */
    fun deleteMessage(id: String) = Unit

    /**
     * 删一个会话的全部消息。语义与 [deleteMessage] 同源（都不靠 [save] 反推）。
     */
    fun deleteConversation(convId: String) = Unit

    /**
     * 基于 FTS 倒排索引全文搜索历史消息。默认空实现以兼容内存存储。
     */
    fun search(query: String, limit: Long = 50): List<MessageSearchResult> = emptyList()

    /**
     * **会话内**检索：只在 [convId] 这一个会话里找，可带类型与日期条件。
     *
     * 与 [search] 分成两个方法而不是加一个可空 `convId` 参数，是因为原型
     * （`prototype/relay.html:4538` 注释）明写这两条是**两套代码**：
     * 「全局走 FTS5 全库（SearchService.query），这里是 `searchMessages(convId, kw)`
     * 在当前会话里 LIKE 匹配，只取 50 条。所以在会话内搜得到不等于全局搜得到」。
     *
     * [HistorySearchFilter.kind] 的筛选**必须在存储层生效**（SQL 里 `file_json IS NOT NULL`），
     * 不能取回结果再在调用方过滤 —— 那样 LIMIT 会先作用在未筛的结果上，
     * 「只看图片」就变成「最近 50 条里恰好是图片的那几条」，一个聊了几百条的会话会搜出空列表。
     *
     * 默认实现走内存筛，好让 [InMemoryMessageStore] 与预览桩不用重写。
     */
    fun searchInConversation(
        convId: String,
        query: String,
        filter: HistorySearchFilter = HistorySearchFilter.None,
        limit: Long = 50,
    ): List<MessageSearchResult> = emptyList()

    /**
     * **全局**检索的「聊天记录」段：按会话聚合，每会话一行。
     *
     * 返回聚合而不是命中明细，是因为原型那一段每行是「与 老周 的聊天 · 7 条相关」——
     * 一个会话一行。若列明细，一个聊了几百条的会话会淹掉另外两个分组，
     * 而用户在这一层的目的是"找到那个会话"。
     */
    fun searchGrouped(query: String, limit: Long = 10): List<ConversationSearchGroup> = emptyList()

    /**
     * **全局**检索的「文件」段：按文件展示名匹配。
     *
     * 匹配的是 `file_json` 里序列化的 [FileCardUi]，见 `RelayDatabase.sq` 的 `searchFileMessages`
     * 注释（为什么不拆一个 `file_name` 列）。
     */
    fun searchFiles(query: String, limit: Long = 20): List<MessageSearchResult> = emptyList()

    companion object {
        /**
         * 启动时每个会话读进内存的窗口大小。
         *
         * 200 的依据：真机最大会话 6161 条、其余会话都在 300 条以内。200 条足够覆盖
         * 「打开就被看到的最近几屏」，同时把常驻消息对象从 6656 条压到约 700 条。
         * 更早的历史由游标（`selectMessagesBefore`）按需翻，不参与启动。
         */
        const val MESSAGE_PAGE_SIZE: Int = 200

        /**
         * 落盘用的 JSON 配置。
         *
         * 与 `SettingsStore.json` 分开，是因为两者诉求不同：设置是**给人看、给人改**的
         * （用户真会去翻那个文件），聊天记录是**给机器读**的（没人会去读自己的聊天记录），
         * 所以这里关掉 prettyPrint —— 少一半体积，也少一半写盘时间。
         *
         * `ignoreUnknownKeys` 是必须的：聊天记录跨版本留存，字段只会增不会删，
         * 老记录里出现新版本不认识的键时仍要读得回来，而不是整份作废。
         */
        internal val json = Json {
            prettyPrint = false
            ignoreUnknownKeys = true
        }
    }
}

/**
 * 历史消息全文搜索结果项。
 */
data class MessageSearchResult(
    val id: String,
    val convId: String,
    val peerId: String,
    val senderId: String?,
    val mine: Boolean,
    val timestamp: Long,
    val snippetText: String,
    /**
     * 这条命中带的附件名，没有附件时 null。
     *
     * 为什么结果项要带文件名：文件消息的 `text` 本来就是空串（见 `upsertTransferCard`），
     * 于是 `snippetText` 对一条文件消息也是空的 —— 只靠 [snippetText] 的话，
     * 会话内搜到一个文件会显示成一行空白。而原型那四行命中里有两行正是文件
     * （`HITS` 的 `.pdf` 与 `.png`），t1 直接就是文件名。
     */
    val fileName: String? = null,
    /**
     * 附件是不是图片。
     *
     * 只决定那一行画文件图标还是图片图标（原型 `HITS` 两种各一个），
     * 与 [HistorySearchKind] 的筛选判据同源（都走 [mediaKindOf]），不另立一套判断。
     */
    val isImage: Boolean = false,
)

/**
 * 纯内存实现：进程活着就有效，进程一死就没了。
 *
 * 它**不是**临时的占位 —— 它就是"不落盘"这一模式的正确实现。控制器默认
 * `persistMessages = false`，此实现下 app 行为与消息流纯内存的原型**逐字一致**。
 */
class InMemoryMessageStore : MessageStore {
    private var current: Map<String, List<MessageUi>> = emptyMap()

    override fun load(): Map<String, List<MessageUi>> = current.mapValues { (_, list) -> list.toList() }

    override fun save(threads: Map<String, List<MessageUi>>) {
        // 存副本：调用方手里那个 map 背后的 list 还挂在消息流上，不能让存储实现持有可变引用。
        current = threads.mapValues { (_, list) -> list.toList() }
    }

    override fun clear() {
        current = emptyMap()
    }

    override fun deleteMessage(id: String) {
        // 这个实现本来就是整份覆盖：删掉之后再 save 一次，内容自然就不含它了。
        // 仍然实现它，是为了让"删除"在**只调 deleteMessage、没接着 save** 的路径上
        // 语义与 SQLite 那份一致 —— 测试里恰恰最容易走这种路径。
        current = current
            .mapValues { (_, list) -> list.filter { it.id != id } }
            .filterValues { it.isNotEmpty() }
    }

    override fun deleteConversation(convId: String) {
        current = current.filterKeys { it != convId }
    }

    override fun search(query: String, limit: Long): List<MessageSearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val results = mutableListOf<MessageSearchResult>()
        for ((convId, list) in current) {
            for (msg in list) {
                if (msg.text.contains(q, ignoreCase = true)) {
                    results += msg.toSearchResult(convId)
                    if (results.size >= limit) return results
                }
            }
        }
        return results
    }

    override fun searchInConversation(
        convId: String,
        query: String,
        filter: HistorySearchFilter,
        limit: Long,
    ): List<MessageSearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val results = mutableListOf<MessageSearchResult>()
        // 与 SQL 侧同一套判据：关键词命中**正文**，类型与日期在命中之后再筛。
        // 内存实现里没有 LIMIT 与筛选的顺序问题（都在同一趟里做），
        // 但保留同样的语义 —— 两条路径给出不同结果是更难查的 bug。
        for (msg in current[convId].orEmpty()) {
            if (!msg.text.contains(q, ignoreCase = true)) continue
            if (!filter.accepts(msg.at)) continue
            if (!filter.kind.matches(msg.file)) continue
            results += msg.toSearchResult(convId)
            if (results.size >= limit) break
        }
        return results
    }

    override fun searchGrouped(query: String, limit: Long): List<ConversationSearchGroup> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return current.mapNotNull { (convId, list) ->
            val hits = list.filter { it.text.contains(q, ignoreCase = true) }
            if (hits.isEmpty()) {
                null
            } else {
                val latest = hits.maxByOrNull { it.at }
                ConversationSearchGroup(
                    convId = convId,
                    matchCount = hits.size,
                    latestAt = latest?.at ?: 0L,
                    // 文件消息的 text 是空串，所以它不可能在这里命中 ——
                    // latest 一定是一条有正文的消息，不用再退回文件名。
                    latestText = latest?.text.orEmpty(),
                )
            }
        }.sortedByDescending { it.latestAt }.take(limit.toInt())
    }

    override fun searchFiles(query: String, limit: Long): List<MessageSearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val results = mutableListOf<MessageSearchResult>()
        for ((convId, list) in current) {
            for (msg in list) {
                val card = msg.file ?: continue
                if (!card.name.contains(q, ignoreCase = true)) continue
                results += msg.toSearchResult(convId)
                if (results.size >= limit) return results
            }
        }
        return results
    }
}

/**
 * 一条内存消息折算成搜索结果。
 *
 * 抽出来是因为 [InMemoryMessageStore] 的三条检索路径都要它 —— 各写一遍的结果
 * 是同一个文件在「全局文件」段里有图标、在会话内搜索里没有。
 */
private fun MessageUi.toSearchResult(convId: String): MessageSearchResult = MessageSearchResult(
    id = id,
    convId = convId,
    peerId = peerId,
    senderId = senderId,
    mine = mine,
    timestamp = at,
    // 文件消息的 text 是空串，退回文件名 —— 否则搜到一个文件是一行空白。
    snippetText = text.ifBlank { file?.name.orEmpty() },
    fileName = file?.name,
    isImage = file != null && mediaKindOf(file) == MediaKind.Image,
)


/**
 * 落盘用的消息投影：**只保留纯值字段**。
 *
 * 丢一样东西，理由是宁可少显示，不可显示错：
 *
 * 1. [MessageUi.file]（文件卡片）整张丢掉：
 *    - [FileCardUi.savedPaths] 指向真实文件路径，重启后可能已被系统清理或被用户删掉，
 *      渲染出一张点开是死文件的卡片，比不显示更糟；
 *    - [TransferState] 描述的是**本次运行内**的传输进度（Offered/Active/Completed），
 *      跨进程没有意义 —— 重启后不可能还有下载在跑；
 *    - 恢复出来的卡片带着一个旧 transferId，传输管理器并不认它，
 *      用户点「接收」会得到一个永远转不起来的界面。
 *
 * 投递状态一律照实保留，包括 [DeliveryState.Queued]：「当时在排队」是**发生过的事**，
 * 磁盘只负责记录它；而「重启后那个队列已经不在了」是**下一次启动**才知道的事 ——
 * 所以这个折算放在 [toRestored] 里，而不是在写盘时提前替下次启动做决定。
 *
 * 排队态照实留着（见上面的"投递状态"段）：重启后它是"曾经排过队"的事实，
 * 而"那个队列还在不在"由启动流程另外问 core（`handleRestoreQueuedFrom`）。
 *
 * 已知代价（写在这里是为了下次有人看到时不用再踩一遍）：文件消息的 `text` 本来就是空串，
 * 所以重启后那张卡片彻底消失，会话列表的预览也随之变空
 * （[RelayUiState.conversations] 的 `it.text.ifBlank { it.file?.name }` 两条都落空）。
 * 真要显示"曾经发过一个文件"，正确做法是给 [MessageUi] 加一个纯值的 `fileName` 字段，
 * 而不是把 [FileCardUi] 整个序列化回去 —— 那是字段模型的改动，不该混在落盘功能里。
 */
internal fun MessageUi.toStorable(): MessageUi = copy(file = null)

/**
 * 恢复出来的消息：把**跨进程已经不成立**的进程内状态折成既成事实。
 *
 * [DeliveryState.Queued] 的含义是"已入队、**本次运行内**等对方上线"（见 [DeliveryState]）。
 * 而 core 侧那份待发队列默认是**进程内**的（`InMemoryQueueStore`），重启即空 ——
 * 所以光恢复一个徽标是没用的，必须有人把这条消息重新送回队列。
 *
 * 那个人是启动流程（见 `RelayController.start()`）：它问 core 要还在等的 msgId
 * （`NodeRuntime.pendingOutgoingMessages()`），把**这些**行贴回 [DeliveryState.Queued]，
 * 并把自称 [DeliveryState.Queued] 却不在队列里的行折成失败。
 * 队列里存的是原封不动的信封，**不重建**是关键：原信可能已经送到、只是 ACK 丢了，
 * 换个 id 重发会让对方收到两条；原样重放由对端的去重挡掉第二条并照常回 ACK。
 *
 * 折成 [DeliveryState.Failed] 而不是落成 `null`（无徽标）只在**信封不在**时发生：
 * 那是旧快照（功能上线前写的），或者重注入时解码失败。
 * 无徽标读起来像"已送达"，而这条消息既没送达、也不会重发，两边都在骗用户；
 * 红字「发送失败」至少让用户知道该自己再发一遍。
 *
 * 只动**出站**（[MessageUi.mine]）：入站消息本来就没有投递态，也不会带信封；
 * 而 `mine == true && delivery == Queued` 在内存里是唯一的排队形态，不会认错人。
 *
 * 乐观插入（[DeliveryState.Sending]）现在有**三条**入口，全部落在同一个 [MessageUi] 上，
 * 所以上面这条折算对它们一视同仁：私聊文本（`RelayControllerMessagesComponent.handleSend`）、
 * 群聊文本（`RelayControllerGroupsComponent.sendGroupTextOptimistic`）、以及文件卡插入前那段
 * 窗口。文件卡本身另有一层保障：卡片状态（[TransferState.Offered]）**不落盘** ——
 * [toStorable] 会把 [MessageUi.file] 整张剥掉，所以重启后不会复活一张传输管理器不认的卡片。
 *
 * 已知代价（写在这里是因为一定会有人来问）：重注入失败而显示出的「发送失败」是**终态** ——
 * 用户想重发只能自己再发一条。
 */
internal fun MessageUi.toRestored(): MessageUi =
    when {
        // 重启后还停在「已排队」= 队列已经没了，它不会再发出去 ⇒ 折成既成事实的失败。
        mine && delivery == DeliveryState.Queued -> copy(delivery = DeliveryState.Failed)
        // 重启后还停在「发送中」是**更严重**的谎：那个状态连重试出口都没有（界面上只有
        // Failed 才挂「重发」），用户会一直看着一条永远转下去的消息。进程都换了一个，
        // 那次发送**必然**已经结束（成功或失败），无从恢复 ⇒ 如实折成失败。
        //
        // 这条不变量由 `Sending` 的 KDoc 承诺（"重启后不会残留"），这里就是它的实现点。
        mine && delivery == DeliveryState.Sending -> copy(delivery = DeliveryState.Failed)
        else -> this
    }

/**
 * 这条消息的队列项被剪枝丢掉了 —— 它**永远不会再发出去**，所以折成既成事实的失败。
 *
 * `PrunedMessage` 的契约原文就是"上层据此把这些消息标记为 failed（延迟失败，不是即时失败）"，
 * 而在此之前没有任何上层实现它：队列项没了、卡片却永远停在「已排队」，是 README 里点名的
 * 那类"界面状态过期没自愈"。红字至少让用户知道该自己再发一遍（[DeliveryState.Failed]
 * 是聊天页唯一挂「重发」出口的状态）。
 *
 * **只对 [DeliveryState.Queued] 有权改判**，其余一律原样返回（同一个实例）：
 * - [DeliveryState.Sent]：ACK 与剪枝是两条独立的路，完全可能擦肩而过 —— 消息刚送达、
 *   剪枝那一轮才跑到，把「已送达」改回「失败」就是撒谎；
 * - [DeliveryState.Recalled]：用户自己撤回的，不该因为队列里那条也没了而变成"发送失败"；
 * - `null`：本来就没有徽标，更不该凭空长出一个红字。
 *
 * 与 [toRestored] 里那条"重启后还在排队 = 失败"是同一条规则、两个触发点：
 * 那条按"重启后队列已清"折算，这一条按"TTL 到点"折算。
 */
internal fun MessageUi.afterPruned(): MessageUi =
    if (delivery == DeliveryState.Queued) {
        copy(delivery = DeliveryState.Failed)
    } else {
        this
    }

/**
 * 写进文件的那个形状。
 *
 * 外面裹一层 `version`，不是为了好看：聊天记录是**跨版本**留下来的，字段只会增不会删。
 * 真出现不认识的字段时，`ignoreUnknownKeys` 让老记录仍能读回来，而不是整份作废。
 */
@Serializable
internal data class MessageSnapshot(val version: Int = SNAPSHOT_VERSION, val threads: Map<String, List<MessageUi>>) {
    companion object {
        const val SNAPSHOT_VERSION = 1
    }
}

/** 平台壳提供落盘实现；预览与单元测试用 [InMemoryMessageStore]。 */
expect fun createMessageStore(platform: AppPlatform): MessageStore

/** 平台壳提供 Agent 落盘实现；预览与单元测试用 [InMemoryAgentStore]。 */
expect fun createAgentStore(platform: AppPlatform): AgentStore

/**
 * Compose 侧拿 store 的惯用写法：`rememberMessageStore()` 在重组之间只创建一次。
 *
 * 与 `createSettingsStore` 保持同一套形状，平台层接线时一眼能对上。
 */
@Composable
fun rememberMessageStore(platform: AppPlatform): MessageStore = remember(platform) { createMessageStore(platform) }

@Composable
fun rememberAgentStore(platform: AppPlatform): AgentStore = remember(platform) { createAgentStore(platform) }

/**
 * 从落盘的 JSON 里恢复一条屏幕协助记录。返回 null = 这条记录不可用（按纯文本渲染）。
 *
 * 校验规则逐条对齐茶话间 `parseScreenRecord`，每条都有具体理由：
 * - 长度 > [SCREEN_RECORD_MAX_CHARS]：这个字段只装一个结构体，超长说明它根本不是记录
 *   （或是被塞了别的东西），不值得解析；
 * - `v != 1`：结构对不上，**宁可少一张卡也不要猜**；
 * - 时间戳为负：时钟回拨或数据损坏，画出来是"1970 年请求的屏幕协助"；
 * - `Active` 必须有 `startedAt`：没有开始时间的"协助中"推不出副行该写什么；
 * - 终态（且原因不是 `interrupted`）必须有 `endedAt`：茶话间的规矩是"异常退出不能补造
 *   结束时间"，所以 `interrupted` 是唯一允许没有 `endedAt` 的终态；
 * - 有 `durationMs` 必须有 `startedAt`：时长是"从开始算起"，没有开始就无从谈起。
 */
fun parseScreenRecordUi(json: String): ScreenRecordUi? {
    if (json.length > SCREEN_RECORD_MAX_CHARS) return null
    val record = runCatching { MessageStore.json.decodeFromString<ScreenRecordUi>(json) }.getOrNull()
        ?: return null
    if (record.v != SCREEN_RECORD_VERSION) return null
    if (record.requestedAt <= 0L) return null
    if (record.startedAt != null && record.startedAt <= 0L) return null
    if (record.endedAt != null && record.endedAt <= 0L) return null
    if (record.phase == ScreenPhase.Active && record.startedAt == null) return null
    if (record.isTerminal && record.reason != SCREEN_INTERRUPTED_REASON && record.endedAt == null) return null
    if (record.durationMs != null && record.startedAt == null) return null
    return record
}

/** 落盘记录的长度上限。茶话间 `parseScreenRecord` 用的是同一个数（4096）。 */
const val SCREEN_RECORD_MAX_CHARS: Int = 4096

/**
 * 「本次运行之前留下的、没走完的会话」的原因码。
 *
 * 它**不在**协议的两个原因集合（`ScreenEndReasons` / `ScreenRejectReasons`）里：那两个是
 * **协议**取值域（对端也会发），这个是纯粹的本地状态，永远不会出现在信封里。所以单独一个
 * 常量，不去扩协议的枚举 —— 扩了就等于告诉对端"这个码你可以发给我"，而它根本没有对应的
 * 线上语义。
 */
const val SCREEN_INTERRUPTED_REASON: String = "interrupted"

/** PK 互动卡片 UI 模型。 */
