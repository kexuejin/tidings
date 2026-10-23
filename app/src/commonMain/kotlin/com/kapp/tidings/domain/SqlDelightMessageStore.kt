// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.domain

import app.cash.sqldelight.db.SqlDriver
import com.kapp.tidings.core.nowMillis
import com.kapp.tidings.db.RelayDatabase
import kotlinx.serialization.encodeToString
import com.kapp.tidings.domain.model.AgentApprovalUi
import com.kapp.tidings.domain.model.AgentArtifactKey
import com.kapp.tidings.domain.model.AgentArtifactUi
import com.kapp.tidings.domain.model.AgentEventUi
import com.kapp.tidings.domain.model.AgentQueueTaskUi
import com.kapp.tidings.domain.model.AgentRunKey
import com.kapp.tidings.domain.model.AgentRunUi
import com.kapp.tidings.domain.model.AgentSessionKey
import com.kapp.tidings.domain.model.AgentSessionUi
import com.kapp.tidings.domain.model.DeliveryState
import com.kapp.tidings.domain.model.GrantUi
import com.kapp.tidings.domain.model.WorkspaceKey
import com.kapp.tidings.domain.model.WorkspaceUi
import com.kapp.tidings.core.protocol.AgentGrantAccess
import com.kapp.tidings.domain.model.FileCardUi
import com.kapp.tidings.domain.model.MessageUi
import com.kapp.tidings.domain.model.NoticeKind
import com.kapp.tidings.domain.model.NoticeDetail
import com.kapp.tidings.domain.model.parseThinkingContent

/**
 * 基于 SQLDelight + SQLite FTS5 (Trigram) 的消息持久化与全文检索存储。
 *
 * 核心特性：
 * 1. 结构化索引：基于 `conv_id + timestamp` 建立 B-Tree 索引，毫秒级快速加载；
 * 2. 全文检索：**有 fts5 的机器**上走 FTS5 `tokenize='trigram'`，支持高精度的中文连续字串与子串搜索，
 *    并提取高亮上下文片段；**没有 fts5 的机器**（Android 的框架 SQLite 就没有）自动退到 LIKE 子串匹配
 *    —— 语义一致，只是慢（见 [fts5] 与 `com.kapp.tidings.db.FtsTolerantSchema`）；
 * 3. 平滑迁移：首次使用若 SQLite 为空，自动从 [legacyStore]（旧版 JSON）拉取存量数据导入并清理旧文件，确保用户历史消息零丢失。
 */
class SqlDelightMessageStore(
    private val database: RelayDatabase,
    private val legacyStore: MessageStore? = null,
) : MessageStore {

    constructor(driver: SqlDriver, legacyStore: MessageStore? = null) : this(
        database = RelayDatabase(driver),
        legacyStore = legacyStore,
    )

    private val queries = database.relayDatabaseQueries

    /**
     * 本机 SQLite 有没有那个可用的 `MessageFts`（即有没有 fts5）。
     *
     * Android 的框架 SQLite 没编译 fts5：`com.kapp.tidings.db.FtsTolerantSchema` 会把
     * `MessageFts` 那几条 DDL 跳过，于是这张虚拟表根本不存在，FTS 那几条查询会抛
     * `no such table`。功能上不受影响 —— 到处都有 `runCatching` 退到 LIKE 的兜底 ——
     * 但用户每敲一个字就白抛一次异常，所以探一次记住结论。
     *
     * 用 `limit = 0` 探：查询只要能**准备出来**就说明表在，不需要取回任何行。
     * 探针串写成带引号的短语，保证在真有 fts5 的机器上也是一句合法 FTS 查询。
     */
    private val fts5: Boolean by lazy {
        runCatching {
            queries.searchMessages(query = "\"$FTS_PROBE\"", limit = 0L).executeAsList()
        }.isSuccess
    }

    override fun load(): Map<String, List<MessageUi>> = loadInternal(null)

    override fun loadRecent(limitPerConversation: Int): Map<String, List<MessageUi>> =
        loadInternal(limitPerConversation)

    override fun loadOlder(convId: String, beforeSeq: Long, limit: Int): List<MessageUi> =
        queries.selectMessagesBefore(convId, beforeSeq, limit.toLong()).executeAsList()
            // 与 loadRecent 同一种排序，调用方可以直接插到窗口头部。
            .map { it.toUi() }
            .sortedBy { it.at }

    override fun loadSummaries(): List<ConversationSummaryRow> =
        queries.selectAllConversationSummaries().executeAsList().map { row ->
            ConversationSummaryRow(
                convId = row.conv_id,
                lastMessageId = row.last_message_id,
                lastTextPreview = row.last_text_preview,
                lastMessageAt = row.last_message_at,
                lastMessageIsMine = row.last_message_is_mine == 1L,
            )
        }

    /**
     * 按库里的真实状态重算一个会话的摘要。
     *
     * 为什么是「重算」而不是「增量维护 last_message_id」：增量维护要在每条写入路径上
     * 都记得更新（包括删除、清会话、剪枝），漏一处就是「预览停在旧消息」——
     * 而那类缺陷只在真机上、只在某个特定操作后才出现。重算走 (conv_id, seq) 索引，
     * 一个会话一次查询，代价可以忽略，换来「永远不会不同步」。
     */
    private fun refreshSummary(convId: String) {
        val latest = queries.selectLatestMessageOfConv(convId).executeAsList().firstOrNull()
        if (latest == null) {
            // 这个会话已经没有消息了：摘要也得跟着消失，否则列表里会留下一个空行。
            queries.deleteConversationSummary(convId)
            return
        }
        queries.upsertConversationSummary(
            conv_id = convId,
            last_message_id = latest.id,
            // 与 ConversationUi.preview 的 text.ifBlank { file?.name } 同义：空正文落成 null。
            last_text_preview = latest.text.takeIf { it.isNotBlank() },
            last_message_at = latest.timestamp,
            last_message_is_mine = if (latest.mine == 1L) 1L else 0L,
        )
    }

    /**
     * [limitPerConversation] 为 null 时读全部（[load]），否则每个会话只读最近 N 条。
     *
     * 为什么按会话逐个查、而不是一条窗口函数查全表：Android 的框架 SQLite 在旧版本
     * 上没有 `row_number()`，而分页路径一旦抛异常，用户看到的是「历史全没了」。
     * 会话是几十的量级，逐个查（各走 `message_conv_seq` 索引）代价可以忽略。
     */
    private fun loadInternal(limitPerConversation: Int?): Map<String, List<MessageUi>> {
        val convIds = queries.selectDistinctConvIds().executeAsList()
        if (convIds.isNotEmpty()) {
            val result = LinkedHashMap<String, List<MessageUi>>(convIds.size)
            for (convId in convIds) {
                val rows = if (limitPerConversation == null) {
                    queries.selectMessagesByConv(convId).executeAsList()
                } else {
                    // ORDER BY seq DESC LIMIT N —— 取的是**最近**的 N 条（新消息永远进窗口）。
                    queries.selectRecentMessages(convId, limitPerConversation.toLong()).executeAsList()
                }
                if (rows.isEmpty()) continue
                // 与 load() 的老行为一致：组内按时间升序（UI 直接按这个顺序画）。
                result[convId] = rows.map { it.toUi() }.sortedBy { it.at }
            }
            return result
        }

        // SQLite 为空：尝试从旧版快照平滑迁移
        val legacy = legacyStore?.load().orEmpty()
        if (legacy.isNotEmpty()) {
            save(legacy)
            runCatching { legacyStore?.clear() }
            return legacy
        }

        return emptyMap()
    }

    /**
     * 旧版 JSON 镜像（messages.json）需要重写。
     *
     * 为什么用一个脏标记而不是"每次 save 都写"：那份镜像有 4 MB（真机 6656 条），
     * 每次 save 都整份重编码 + 落盘，比 SQLite 那几行贵一个数量级。
     *
     * 为什么删除也要把它标脏：删除走的是显式 deleteMessage，**不经过** save ——
     * 只按"有没有 upsert"判断的话，镜像会永远留着已删消息，变成一份撒谎的导出
     * （而 SQLite 为空时 load() 会拿它做一次性迁移，等于把删掉的消息复活）。
     */
    private var mirrorDirty = false

    override fun save(threads: Map<String, List<MessageUi>>) {
        var changed = false
        // 这一趟动过哪些会话 —— 只重算这些的摘要（会话数几十，全量重算也不贵，
        // 但按 touched 来更诚实：没动的会话没有任何理由被重写）。
        val touched = HashSet<String>()
        queries.transaction {
            // **这里不再按"快照里没有"反推删除。**
            //
            // 旧实现在这里扫全表、把"内存里没有的 id"整条 DELETE 掉。它当时是对的
            // （内存就是全部历史），但一旦内存只保留一个**窗口**，这条反推就会把
            // 窗口之外的历史整批删掉 —— 那不是性能问题，是数据丢失。
            //
            // 所以删除改成**调用方显式说出来**（deleteMessage / deleteConversation /
            // clear），save() 只做 upsert。这也顺带把「删了还在」那类缺陷的
            // 责任放回唯一的删除入口，而不是靠一次全表比对去猜。
            //
            // 这次查询仍然要留：它给出"库里现在长什么样"，用来判断每条消息有没有变。
            val existing = queries.selectAllMessages().executeAsList().associateBy { it.id }
            for ((peerId, list) in threads) {
                for (message in list) {
                    // 这一条相对库里那一行有没有变化。没变就整条跳过：
                    // 不删索引、不重写行、不改 FTS、不取号。
                    //
                    // 判据与写入**共用同一份 values**（见 toRowValues），所以两者
                    // 不可能各说各话 —— 若比对用的是另一套表达式，改了一个字段而
                    // 漏改另一个，症状会是"某些改动永远存不进去"，而且只在真机上。
                    val values = message.toRowValues(peerId)
                    val row = existing[message.id]
                    // seq 也要求非空：迁移后理论上都有值，但万一有遗留的 NULL，
                    // 跳过它就等于让它永远从分页里消失，所以这里宁可重写一次。
                    if (row != null && row.seq != null && row.matches(values)) continue
                    val storable = message.toStorable()
                    // 同 id 重写时，`INSERT OR REPLACE` 的**隐式删除不触发 DELETE 触发器**，
                    // 旧 FTS 条目会留下来 —— 真机上这条把索引写到 4,554,999 行、库长到 21 GB
                    // （见 RelayDatabase.sq 里那三条触发器的 KDoc）。所以先显式删掉旧索引条目，
                    // 再写新行；两句在同一个事务里，中途崩不会留下半套。
                    //
                    // 必须包 runCatching：**没有 fts5 的机器上这张表根本不存在**（Android 框架
                    // SQLite，见 FtsTolerantSchema），那时这一句会抛 no such table。吞掉它是
                    // 正确的 —— 那种机器本来就没有索引要维护，而写消息本身不能被它带崩。
                    runCatching { queries.insertMessageDropStaleIndex(storable.id) }
                    // seq：会话内游标。**不能省成 NULL**（NULL 会让这条消息在所有
                    // `seq < ?` / `seq > ?` 的分页查询里都消失），也不能靠 INSERT 自己算：
                    // INSERT OR REPLACE 是 DELETE + INSERT，旧行的 seq 会被一起删掉。
                    //
                    // 取号规则「该会话当前 MAX(seq)+1」对**新消息**是对的；对**重写同一 id**
                    // 却会把它挪到队尾 —— 一条 10:00 的消息改了交付状态就跳到 23:00 的位置，
                    // 聊天记录会自己乱序。所以已存在的 id 必须**沿用原 seq**。
                    //
                    // 原 seq 直接从上面那次 selectAllMessages 的索引里取（row?.seq），
                    // 不再每行单发一条 selectSeqById：6656 行就是 6656 次查询，
                    // 而那份数据整份已经在内存里了。
                    val seq: Long = row?.seq ?: queries.nextSeqForConv(storable.peerId).executeAsOne()
                    queries.insertMessage(
                        id = storable.id,
                        conv_id = values.convId,
                        peer_id = values.peerId,
                        sender_id = values.senderId,
                        mine = values.mine,
                        text = values.text,
                        timestamp = values.timestamp,
                        delivery = values.delivery,
                        notice = values.notice,
                        notice_detail = values.noticeDetail,
                        file_json = values.fileJson,
                        screen_json = values.screenJson,
                        agent_session_id = values.agentSessionId,
                        agent_session_title = values.agentSessionTitle,
                        seq = seq,
                    )
                    changed = true
                    touched += values.convId
                }
            }
            // 摘要按库里的真实状态重算（不是增量维护 last_message_id）：
            // 增量维护要在每条写入/删除路径上都记得更新，漏一处就是「预览停在旧消息」。
            for (convId in touched) refreshSummary(convId)
        }
        // 只在"真的动过库"时重写镜像：内容一字未变就把同样的 4 MB 再落一遍盘，
        // 是纯浪费（真机 6656 条 / 4 MB）。删除也会把 mirrorDirty 置脏，
        // 否则镜像会与库不一致。
        if (changed || mirrorDirty) {
            mirrorDirty = false
            runCatching { legacyStore?.save(threads) }
        }
    }

    override fun deleteMessage(id: String) {
        queries.transaction {
            // 先拿到它属于哪个会话：删掉之后就查不到了，而摘要必须跟着重算。
            val convId = queries.selectConvIdOfMessage(id).executeAsList().firstOrNull()
            // 一条真正的 DELETE：会激活 message_after_delete，由它按 FTS5 的 delete 命令
            // 把索引条目一并移除。**不要**再手写一次索引清理 —— 那是第二条真相来源，
            // 两次删除会让虚拟表与实际内容不一致（实测抛 SQLITE_CORRUPT_VTAB）。
            queries.deleteMessage(id)
            // 删掉的可能正是最后一条 ⇒ 预览要退回上一条；删空了 ⇒ 摘要行自己消失。
            if (convId != null) refreshSummary(convId)
        }
        mirrorDirty = true
    }

    override fun deleteConversation(convId: String) {
        queries.transaction {
            queries.clearConv(convId)
            // 会话已经空了，refreshSummary 会把摘要行删掉（否则列表里留一个空行）。
            refreshSummary(convId)
        }
        mirrorDirty = true
    }

    override fun clear() {
        queries.clearAll()
        runCatching { legacyStore?.clear() }
    }

    override fun search(query: String, limit: Long): List<MessageSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 两条不走 FTS5 的理由：
        // 1. SQLite FTS5 trigram 索引以 3 字符为最小 token，1~2 个字符的词（如单个汉字）匹配不到；
        // 2. 这台机器的 SQLite 干脆没有 fts5（Android 框架 SQLite 就是），`MessageFts` 不存在。
        // 两种都退到 LIKE 子串匹配 —— 慢，但语义与 trigram 的子串匹配一致。
        if (trimmed.length < 3 || !fts5) {
            val pattern = "%$trimmed%"
            return runCatching {
                queries.searchMessagesLike(pattern = pattern, limit = limit).executeAsList().map { row ->
                    row.toSearchResult()
                }
            }.getOrDefault(emptyList())
        }

        // 转义双引号以防语法注入
        val sanitized = trimmed.replace("\"", "\"\"")
        val ftsQuery = "\"$sanitized\""

        return runCatching {
            queries.searchMessages(query = ftsQuery, limit = limit).executeAsList().map { row ->
                row.toSearchResult()
            }
        }.getOrElse {
            // 兜底降级：若 FTS 语法解析异常，降级回退到 LIKE 模糊匹配
            val pattern = "%$trimmed%"
            runCatching {
                queries.searchMessagesLike(pattern = pattern, limit = limit).executeAsList().map { row ->
                    row.toSearchResult()
                }
            }.getOrDefault(emptyList())
        }
    }

    override fun searchInConversation(
        convId: String,
        query: String,
        filter: HistorySearchFilter,
        limit: Long,
    ): List<MessageSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 「只看带附件的」这一档：图片与文件两档都要附件，只有「全部」不要。
        // 把它落成一个整数参数交给 SQL，而不是取回来在 Kotlin 里按 kind 过滤 ——
        // 后者会让 LIMIT 先作用在未筛的结果上（见 MessageStore.searchInConversation 的注释）。
        val hasFile = if (filter.kind == HistorySearchKind.All) 0L else 1L

        val fromAt = filter.fromAt
        val toAt = filter.toAt
        val pattern = "%$trimmed%"

        val rawResults = if (trimmed.length < 3 || !fts5) {
            runCatching {
                queries.searchMessagesInConvLike(
                    convId = convId,
                    pattern = pattern,
                    fromAt = fromAt,
                    toAt = toAt,
                    hasFile = hasFile,
                    limit = limit,
                ).executeAsList().map { it.toSearchResult() }
            }.getOrDefault(emptyList())
        } else {
            val sanitized = trimmed.replace("\"", "\"\"")
            val ftsQuery = "\"$sanitized\""

            val textHits = runCatching {
                queries.searchMessagesInConv(
                    query = ftsQuery,
                    convId = convId,
                    fromAt = fromAt,
                    toAt = toAt,
                    hasFile = hasFile,
                    limit = limit,
                ).executeAsList().map { it.toSearchResult() }
            }.getOrElse {
                runCatching {
                    queries.searchMessagesInConvLike(
                        convId = convId,
                        pattern = pattern,
                        fromAt = fromAt,
                        toAt = toAt,
                        hasFile = hasFile,
                        limit = limit,
                    ).executeAsList().map { it.toSearchResult() }
                }.getOrDefault(emptyList())
            }

            // 文件消息的 text 通常为空串，FTS5 未建立其分词索引；补充按文件名 LIKE 检索的结果
            val fileHits = runCatching {
                queries.searchMessagesInConvLike(
                    convId = convId,
                    pattern = pattern,
                    fromAt = fromAt,
                    toAt = toAt,
                    hasFile = 1L,
                    limit = limit,
                ).executeAsList().map { it.toSearchResult() }
            }.getOrDefault(emptyList())

            (textHits + fileHits)
                .distinctBy { it.id }
                .sortedByDescending { it.timestamp }
                .take(limit.toInt())
        }

        return rawResults.filter { result ->
            when (filter.kind) {
                HistorySearchKind.All -> true
                HistorySearchKind.Image -> result.isImage
                HistorySearchKind.File -> result.fileName != null && !result.isImage
            }
        }
    }

    override fun searchGrouped(query: String, limit: Long): List<ConversationSearchGroup> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.length < 3 || !fts5) {
            val pattern = "%$trimmed%"
            return runCatching {
                queries.searchMessagesGroupedLike(pattern = pattern, limit = limit).executeAsList()
                    .map { it.toGroup() }
            }.getOrDefault(emptyList())
        }

        val sanitized = trimmed.replace("\"", "\"\"")
        val ftsQuery = "\"$sanitized\""

        return runCatching {
            queries.searchMessagesGrouped(query = ftsQuery, limit = limit).executeAsList()
                .map { it.toGroup() }
        }.getOrElse {
            val pattern = "%$trimmed%"
            runCatching {
                queries.searchMessagesGroupedLike(pattern = pattern, limit = limit).executeAsList()
                    .map { it.toGroup() }
            }.getOrDefault(emptyList())
        }
    }

    override fun searchFiles(query: String, limit: Long): List<MessageSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val pattern = "%$trimmed%"
        return runCatching {
            queries.searchFileMessages(pattern = pattern, limit = limit).executeAsList().map { row ->
                // 这一段的命中判据是**文件名**，而 `file_json` 整块都进了 LIKE
                // （见 .sq 里 searchFileMessages 的注释），所以这里必须再核一遍名字：
                // 不核的话，一个路径里带关键词的文件也会命中，而用户看到的
                // 是一个文件名跟关键词毫无关系的文件。
                val card = row.file_json
                    .let { runCatching { MessageStore.json.decodeFromString<FileCardUi>(it) }.getOrNull() }
                    ?: return@map null
                if (!card.name.contains(trimmed, ignoreCase = true)) return@map null
                MessageSearchResult(
                    id = row.id,
                    convId = row.conv_id,
                    peerId = row.conv_id,
                    senderId = null,
                    mine = false,
                    timestamp = row.timestamp,
                    snippetText = card.name,
                    fileName = card.name,
                    isImage = mediaKindOf(card) == MediaKind.Image,
                )
            }.filterNotNull()
        }.getOrDefault(emptyList())
    }

    private companion object {
        /**
         * 只为探"`MessageFts` 在不在"用的一句合法 FTS 短语，永远匹配不到东西（见 [fts5]）。
         */
        const val FTS_PROBE = "__relay_fts5_probe__"
    }
}

/**
 * 基于 SQLDelight 的 Agent 独立存储实现（PPE-1 §9）。
 */
class SqlDelightAgentStore(
    private val database: RelayDatabase,
) : AgentStore {

    constructor(driver: SqlDriver) : this(database = RelayDatabase(driver))

    private val queries = database.relayDatabaseQueries

    override fun loadSessions(): Map<AgentSessionKey, AgentSessionUi> {
        // 自愈历史存量脏数据：DSH 规范中子代理/探针会话不属于任何工作区，解绑 ws_id 并清理废弃探针
        runCatching {
            queries.cleanupDirtyDshSessions()
            queries.deleteProbeSessions()
        }
        return queries.selectAllAgentSessions().executeAsList().associate { row ->
            AgentSessionKey(peerId = row.node_id, sessionId = row.session_id) to AgentSessionUi(
                peerId = row.node_id,
                sessionId = row.session_id,
                alias = row.alias,
                agentKind = row.agent_kind,
                state = row.state,
                title = row.title,
                startedAt = row.started_at,
                agentId = row.agent_id,
                wsId = row.ws_id,
                wsTitle = row.ws_title,
                wsPath = row.ws_path,
                updatedAt = row.updated_at,
                // 两列同名不同义，读回时不要串：updated_at = 本端收帧时刻，host_updated_at = 宿主活动时刻。
                hostUpdatedAt = row.host_updated_at,
                // 可见性两键：NULL = 还没听宿主说过（老宿主 / 这条会话只走过实时宣告链路，
                // 那条路从不送这两键）—— 不是 false。展示层对 NULL 的处理是"放行、不猜"。
                hostOrigin = row.origin,
                hostBlank = row.blank?.let { it != 0L },
                announced = row.announced == 1L,
            )
        }
    }

    /**
     * 每个会话只取**最近** [limitPerSession] 条，不是全表。
     *
     * 为什么不能整表读：真机库 AgentEventEntity 有 57,644 行 / 79 个会话 / body 合计 5.2MB，
     * 全读进内存就是堆直方图里那 57,972 个 AgentEventUi（3.24MB 对象 + 它们指向的字符串）。
     * 而收帧路径（mergeAgentEvent 的 max）本来只留最近那批 —— 两侧口径必须一致，
     * 否则恢复进来的全量会把收帧路径的上限架空（上限只在 merge 时生效，恢复不经过 merge）。
     *
     * 分两步走（同消息侧 loadRecent + selectDistinctConvIds 的形状）：先取会话清单，
     * 再逐个会话 ORDER BY cursor DESC LIMIT N。**不用窗口函数一次查完** —— Android 框架
     * SQLite 的老版本没有 row_number()，这条路径抛异常就是「agent 历史全没了」，
     * 而 handleRestoreAgentData 外面是 runCatching，异常会被吞成空表。
     */
    override fun loadEvents(limitPerSession: Int): Map<AgentSessionKey, List<AgentEventUi>> {
        val limit = limitPerSession.coerceAtLeast(0).toLong()
        val result = mutableMapOf<AgentSessionKey, List<AgentEventUi>>()
        for (session in queries.selectAgentEventSessions().executeAsList()) {
            val events = queries.selectRecentAgentEvents(
                nodeId = session.node_id,
                sessionId = session.session_id,
                limit = limit,
            ).executeAsList()
            if (events.isEmpty()) continue
            // 与收帧路径合并后的形状一致：组内按 cursor 升序（UI 直接按这个顺序画）。
            result[AgentSessionKey(peerId = session.node_id, sessionId = session.session_id)] =
                events.map { row ->
                    AgentEventUi(
                        sessionId = row.session_id,
                        peerId = row.node_id,
                        cursor = row.cursor,
                        eventType = row.event_type,
                        summary = row.summary,
                        body = row.body,
                        ref = row.ref,
                        at = row.at,
                    )
                }.sortedBy { it.cursor }
        }
        return result
    }

    override fun saveSession(session: AgentSessionUi) {
        queries.insertAgentSession(
            node_id = session.peerId,
            session_id = session.sessionId,
            alias = session.alias,
            agent_kind = session.agentKind,
            state = session.state,
            title = session.title,
            started_at = session.startedAt,
            updated_at = session.updatedAt,
            announced = if (session.announced) 1L else 0L,
            agent_id = session.agentId,
            ws_id = session.wsId,
            ws_title = session.wsTitle,
            ws_path = session.wsPath,
            host_updated_at = session.hostUpdatedAt,
            origin = session.hostOrigin,
            blank = session.hostBlank?.let { if (it) 1L else 0L },
        )
    }

    override fun saveEvent(event: AgentEventUi) {
        queries.insertAgentEvent(
            node_id = event.peerId,
            session_id = event.sessionId,
            cursor = event.cursor,
            event_type = event.eventType,
            summary = event.summary,
            body = event.body,
            ref = event.ref,
            at = event.at,
        )
    }

    /**
     * 审批的键是 `approvalId`（§6.5 定义它是全局唯一的请求标识），与运行时那个 map 同键。
     *
     * 表上的主键仍写成 `(node_id, approval_id)`：磁盘上不丢信息，哪怕两个 peer 真的撞了同一个
     * `approvalId`，也只是**加载时**按运行时能表达的口径合并。想彻底按 peer 隔离要动 UI 的
     * map 键，那是另一件事，不夹在这一轮里做。
     */
    override fun loadApprovals(): Map<String, AgentApprovalUi> =
        queries.selectAllAgentApprovals().executeAsList().associate { row ->
            row.approval_id to AgentApprovalUi(
                approvalId = row.approval_id,
                sessionId = row.session_id,
                peerId = row.node_id,
                tool = row.tool,
                summary = row.summary,
                expiresAt = row.expires_at,
                choices = row.choices,
                // NULL 读回 "pending"：运行时模型里 "pending" 正是"还没处置"的取值，
                // 而 §10 不许把「没有回执」显示成任何结论。
                status = row.status ?: "pending",
            )
        }

    override fun loadTasks(): Map<String, AgentQueueTaskUi> =
        queries.selectAllAgentTasks().executeAsList().associate { row ->
            row.task_id to AgentQueueTaskUi(
                taskId = row.task_id,
                sessionId = row.session_id,
                peerId = row.node_id,
                op = row.op,
                prompt = row.prompt,
                queuedAt = row.queued_at,
            )
        }

    override fun loadRuns(): Map<AgentRunKey, AgentRunUi> =
        queries.selectAllAgentRuns().executeAsList().associate { row ->
            AgentRunKey(peerId = row.node_id, runId = row.run_id) to AgentRunUi(
                runId = row.run_id,
                sessionId = row.session_id,
                peerId = row.node_id,
                state = row.state,
                at = row.at,
                note = row.note,
                updatedAt = row.updated_at,
            )
        }

    override fun loadArtifacts(): Map<AgentArtifactKey, AgentArtifactUi> =
        queries.selectAllAgentArtifacts().executeAsList().associate { row ->
            AgentArtifactKey(peerId = row.node_id, artifactId = row.artifact_id) to AgentArtifactUi(
                artifactId = row.artifact_id,
                sessionId = row.session_id,
                peerId = row.node_id,
                fileId = row.file_id,
                name = row.name,
                bytes = row.bytes,
                mime = row.mime,
                at = row.at,
            )
        }

    override fun loadWorkspaces(): Map<WorkspaceKey, WorkspaceUi> {
        runCatching { queries.deleteProbeWorkspaces() }
        return queries.selectAllAgentWorkspaces().executeAsList()
            .filterNot { row -> isSyntheticWsId(row.ws_id) }
            .associate { row ->
                WorkspaceKey(
                    peerId = row.node_id,
                    // 空串与 NULL 都折成 null：协议里 agentKey 缺失 = 对端没报（不猜），
                    // 而磁盘上 SQLite 的 NULL 与空串会同时出现（老行 / 手写过）。
                    agentKey = row.agent_key?.takeIf { it.isNotBlank() },
                    wsId = row.ws_id,
                ) to WorkspaceUi(
                    peerId = row.node_id,
                    // agent_key 是**落库那一刻**的派生物，agent_id 是原值；两个都还原，
                    // 改名的场景靠 agent_key 跟随新值、agent_id 留原值（见 WorkspaceUi 的 KDoc）。
                    agentKey = row.agent_key?.takeIf { it.isNotBlank() },
                    agentId = row.agent_id?.takeIf { it.isNotBlank() },
                    wsId = row.ws_id,
                    wsTitle = row.ws_title,
                    wsPath = row.ws_path,
                    agentKind = row.agent_kind,
                    // NULL = 宿主没报 online；0/1 才是它真的说了"离线/在线"（沉默 ≠ 离线）。
                    online = row.online?.let { it != 0L },
                    sessionCount = row.session_count,
                    // NULL = 宿主没报顺序（老宿主 / 本地台账兜底路），如实保留三态。
                    // 折成 0 会让"没说"变成"排在第一个"，把用户拖过的顺序整个打乱。
                    hostOrder = row.host_order,
                    // 磁盘那列叫 updated_at（与其它 agent 表同列名），UI 模型叫 at
                    // （与 AgentEventUi 同名同义：本端**收到**的时刻，只用于排序不用于显示）。
                    at = row.updated_at,
                )
            }
    }

    override fun loadGrants(): Map<String, GrantUi> =
        queries.selectAllAgentGrants().executeAsList().associate { row ->
            // 空串是"整台设备"那一档的**编码**（见 .sq 的注释：SQLite NULL 不参与主键唯一性），
            // 读回来必须翻回 null，否则 UI 上会多出一条"agentKey = """ 的假条目。
            val key = row.agent_key.takeIf { it.isNotBlank() }
            val wsId = row.ws_id.takeIf { it.isNotBlank() }
            val access = row.access.takeIf { it.isNotBlank() } ?: AgentGrantAccess.RW
            grantKeyOf(row.node_id, key, wsId) to GrantUi(
                peerId = row.node_id,
                agentKey = key,
                scope = row.scope,
                state = row.state,
                at = row.at,
                wsId = wsId,
                access = access,
            )
        }

    override fun saveWorkspace(workspace: WorkspaceUi) {
        queries.insertAgentWorkspace(
            node_id = workspace.peerId,
            agent_key = workspace.agentKey,
            agent_id = workspace.agentId,
            ws_id = workspace.wsId,
            ws_title = workspace.wsTitle,
            ws_path = workspace.wsPath,
            agent_kind = workspace.agentKind,
            // 三态写库：null（没报）写 NULL，true/false 写 1/0。
            // 把 null 折成 0 会让"没说"变成"说离线了" —— 与 online 的 KDoc 同一纪律。
            online = workspace.online?.let { if (it) 1L else 0L },
            session_count = workspace.sessionCount,
            host_order = workspace.hostOrder,
            updated_at = workspace.at,
        )
    }

    override fun saveGrant(grant: GrantUi) {
        queries.insertAgentGrant(
            node_id = grant.peerId,
            // null（整台设备）写成空串：它是主键成员，SQLite 里 NULL 不参与唯一性判定，
            // 两次插入会在磁盘上留两行"设备级授权"。
            agent_key = grant.agentKey ?: "",
            scope = grant.scope,
            state = grant.state,
            at = grant.at,
            ws_id = grant.wsId ?: "",
            access = grant.access,
        )
    }

    override fun saveApproval(approval: AgentApprovalUi) {
        // `AgentApprovalUi` 没有 `updatedAt` 字段，这一列取"写盘的那一刻"——它只用于排序，
        // 不参与任何判定，所以不必反过来给 UI 模型加一个字段。
        queries.insertAgentApproval(
            node_id = approval.peerId,
            approval_id = approval.approvalId,
            session_id = approval.sessionId,
            tool = approval.tool,
            summary = approval.summary,
            expires_at = approval.expiresAt,
            choices = approval.choices,
            status = approval.status,
            updated_at = nowMillis(),
        )
    }

    override fun saveTask(task: AgentQueueTaskUi) {
        queries.insertAgentTask(
            node_id = task.peerId,
            task_id = task.taskId,
            session_id = task.sessionId,
            op = task.op,
            prompt = task.prompt,
            queued_at = task.queuedAt,
        )
    }

    override fun deleteTask(peerId: String, taskId: String) {
        queries.deleteAgentTask(nodeId = peerId, taskId = taskId)
    }

    override fun saveRun(run: AgentRunUi) {
        queries.insertAgentRun(
            node_id = run.peerId,
            run_id = run.runId,
            session_id = run.sessionId,
            state = run.state,
            at = run.at,
            note = run.note,
            updated_at = run.updatedAt,
        )
    }

    override fun saveArtifact(artifact: AgentArtifactUi) {
        queries.insertAgentArtifact(
            node_id = artifact.peerId,
            artifact_id = artifact.artifactId,
            session_id = artifact.sessionId,
            file_id = artifact.fileId,
            name = artifact.name,
            bytes = artifact.bytes,
            mime = artifact.mime,
            at = artifact.at,
        )
    }

    override fun clear() {
        queries.clearAllAgentData()
    }
}

private fun com.kapp.tidings.db.MessageEntity.toUi(): MessageUi {
    val (parsedReasoning, cleanedText) = parseThinkingContent(text)
    return MessageUi(
        id = id,
        peerId = conv_id,
        mine = mine == 1L,
        text = cleanedText,
        at = timestamp,
        // seq 带进内存：向上翻页靠它当游标（时间戳在同毫秒下会重复/漏）。
        seq = seq,
        delivery = delivery?.let { runCatching { DeliveryState.valueOf(it) }.getOrNull() },
        senderId = sender_id,
        notice = notice?.let { runCatching { NoticeKind.valueOf(it) }.getOrNull() },
        // 解不出来就当没有细节：渲染端会退回中性文案，不会因为一条坏数据画不出通知。
        noticeDetail = notice_detail?.let {
            runCatching { MessageStore.json.decodeFromString(NoticeDetail.serializer(), it) }.getOrNull()
        },
        file = null,
        // 屏幕协助记录**是**读回来的（与 `file` 相反）：它是本地历史，重启之后卡片还得在。
        // 解析失败当"不是屏幕协助记录"（`parseScreenRecordUi` 返回 null），不抛 —— 与
        // `decodeFileCard` 同一条规矩：老记录里可能有新版本不认识的形状，
        // 整条消息因此读不出来比少一张卡糟得多。
        screen = screen_json?.let { parseScreenRecordUi(it) },
        reasoning = parsedReasoning,
        // **会话归属读回来**：聊天流的分段线靠它判（`sessionIdOf`）。
        //
        // 缺这一行，重启后所有分段线都会消失 —— 桌面端还能靠 id 前缀 `agent-ev-` 回退，
        // 手机端连那条回退都没有（转发进群的消息 id 是信封 UUID）。
        // 老库这一列是 NULL（迁移补列、不回填）：那确实不知道，与「这条不属于任何会话」
        // 是同一档，`sessionIdOf` 会继续退回 id 前缀那条旧判据。
        agentSessionId = agent_session_id,
        agentSessionTitle = agent_session_title,
    )
}

/**
 * 把 `file_json` 解回 [FileCardUi]。
 *
 * 解不出来当没有附件，而不是抛：这一列是**跨版本**留下来的，老记录里可能有
 * 新版本不认识的形状，整条搜索因此失败比少一个附件名糟得多。
 * 与 `toUi()` 对同一列的处理保持一致。
 */
private fun decodeFileCard(fileJson: String?): FileCardUi? =
    fileJson?.let { runCatching { MessageStore.json.decodeFromString<FileCardUi>(it) }.getOrNull() }

/**
 * FTS 路径与 LIKE 路径的列名不同（前者是 `MessageEntity.xxx` 的限定名，后者是裸名），
 * 但两者选出的**列集合完全一样**，所以这里收两个重载而不是四份复制粘贴。
 *
 * 附件那两个派生字段（[MessageSearchResult.fileName] / [MessageSearchResult.isImage]）
 * 从这里一处算出来：会话内与全局两条路径对"这条命中长什么样"必须同源，
 * 各算一遍的结果是同一个文件在会话内搜索里有图标、在全局搜索里没有。
 */
private fun com.kapp.tidings.db.SearchMessages.toSearchResult(): MessageSearchResult =
    toSearchResult(id, conv_id, peer_id, sender_id, mine, timestamp, snippetText, file_json)

private fun com.kapp.tidings.db.SearchMessagesLike.toSearchResult(): MessageSearchResult =
    toSearchResult(id, conv_id, peer_id, sender_id, mine, timestamp, snippetText, file_json)

private fun com.kapp.tidings.db.SearchMessagesInConv.toSearchResult(): MessageSearchResult =
    toSearchResult(id, conv_id, peer_id, sender_id, mine, timestamp, snippetText, file_json)

private fun com.kapp.tidings.db.SearchMessagesInConvLike.toSearchResult(): MessageSearchResult =
    toSearchResult(id, conv_id, peer_id, sender_id, mine, timestamp, snippetText, file_json)

private fun toSearchResult(
    id: String,
    convId: String,
    peerId: String,
    senderId: String?,
    mine: Long,
    timestamp: Long,
    snippetText: String?,
    fileJson: String?,
): MessageSearchResult {
    val card = decodeFileCard(fileJson)
    return MessageSearchResult(
        id = id,
        convId = convId,
        peerId = peerId,
        senderId = senderId,
        mine = mine == 1L,
        timestamp = timestamp,
        // 文件消息的 text 是空串，所以 FTS 的 snippet() 对它也是空的 ——
        // 退回文件名，否则会话内搜到一个文件会显示成一行空白（原型 HITS 里
        // 那两行文件命中的 t1 正是文件名）。
        snippetText = snippetText?.takeIf { it.isNotBlank() } ?: card?.name.orEmpty(),
        fileName = card?.name,
        isImage = card != null && mediaKindOf(card) == MediaKind.Image,
    )
}

private fun com.kapp.tidings.db.SearchMessagesGrouped.toGroup(): ConversationSearchGroup =
    // `latestAt` 在生成的类型里是 `Long?`，因为 SQLite 的 `MAX()` 在**空分组**上返回 NULL。
    // 这里 `GROUP BY conv_id` 保证了每个分组至少有一行，所以它实际不会为空 ——
    // 但类型上不能假装，兜 0 而不是 `!!`：真出现空分组时按"很久以前"排到末尾，
    // 比让整个搜索抛异常好。
    ConversationSearchGroup(
        convId = conv_id,
        matchCount = matchCount.toInt(),
        latestAt = latestAt ?: 0L,
        latestText = latestText.orEmpty(),
    )

private fun com.kapp.tidings.db.SearchMessagesGroupedLike.toGroup(): ConversationSearchGroup =
    ConversationSearchGroup(
        convId = conv_id,
        matchCount = matchCount.toInt(),
        latestAt = latestAt ?: 0L,
        latestText = latestText.orEmpty(),
    )

/**
 * 一条消息**将要**写进 MessageEntity 的那一组列值（不含 seq —— 它是身份，不是内容）。
 *
 * 存在的唯一理由：**比对与写入必须是同一份逻辑**。
 * save() 要靠「这一行有没有变」决定跳不跳过；若比对另写一套表达式，两套判据迟早漂移 ——
 * 那时要么永远不跳过（白干），要么把一条**真的变了**的消息判成没变（丢更新，
 * 而且只在真机上、只在改动某个特定字段时才会出现）。所以两边共用 [toRowValues]。
 */
private data class MessageRowValues(
    val convId: String,
    val peerId: String,
    val senderId: String?,
    val mine: Long,
    val text: String,
    val timestamp: Long,
    val delivery: String?,
    val notice: String?,
    val noticeDetail: String?,
    val fileJson: String?,
    val screenJson: String?,
    val agentSessionId: String?,
    val agentSessionTitle: String?,
)

/**
 * 把消息落成列值。[peerId] 是**会话键**（`threads` 的键），不是消息自带的那个。
 *
 * 两个来源刻意不合并：`conv_id` 取消息自报的 `peerId`，`peer_id` 取调用方传进来的
 * 会话键 —— 这是 save() 一直以来的写法，改掉会让群聊/私聊的归属在某些边界上错位。
 *
 * `text` 里那条 `<think>` 包装也是既有行为：reasoning 与正文同列落库，
 * 读回来时由 `parseThinkingContent` 拆开。
 */
private fun MessageUi.toRowValues(peerId: String): MessageRowValues {
    val storable = toStorable()
    return MessageRowValues(
        convId = storable.peerId,
        peerId = peerId,
        senderId = storable.senderId,
        mine = if (storable.mine) 1L else 0L,
        text = if (!storable.reasoning.isNullOrBlank()) {
            "<think>\n" + storable.reasoning + "\n</think>\n" + storable.text
        } else {
            storable.text
        },
        timestamp = storable.at,
        delivery = storable.delivery?.name,
        notice = storable.notice?.name,
        noticeDetail = storable.noticeDetail
            ?.takeIf { !it.isEmpty }
            ?.let { runCatching { MessageStore.json.encodeToString(NoticeDetail.serializer(), it) }.getOrNull() },
        fileJson = file?.let { runCatching { MessageStore.json.encodeToString(it) }.getOrNull() },
        screenJson = screen?.let { runCatching { MessageStore.json.encodeToString(it) }.getOrNull() },
        agentSessionId = agentSessionId,
        agentSessionTitle = agentSessionTitle,
    )
}

/**
 * 库里那一行与 [values] 是否一个字段都不差。
 *
 * 不比 `id`（它是索引键，两边必然相同）与 `seq`（排序身份，不参与内容判定）。
 * 少比任何**会被写入**的列，都会让那条列的更新永远存不进去 —— 这正是
 * 本函数必须与 [toRowValues] 的字段表逐一对应的原因。
 */
private fun com.kapp.tidings.db.MessageEntity.matches(values: MessageRowValues): Boolean =
    conv_id == values.convId &&
        peer_id == values.peerId &&
        sender_id == values.senderId &&
        mine == values.mine &&
        text == values.text &&
        timestamp == values.timestamp &&
        delivery == values.delivery &&
        notice == values.notice &&
        notice_detail == values.noticeDetail &&
        file_json == values.fileJson &&
        screen_json == values.screenJson &&
        agent_session_id == values.agentSessionId &&
        agent_session_title == values.agentSessionTitle

