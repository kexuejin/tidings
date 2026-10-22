// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.cash.sqldelight.db.SqlDriver
import app.relay.core.nowMillis
import app.relay.db.RelayDatabase
import kotlinx.serialization.encodeToString
import app.relay.app.model.AgentApprovalUi
import app.relay.app.model.AgentArtifactKey
import app.relay.app.model.AgentArtifactUi
import app.relay.app.model.AgentEventUi
import app.relay.app.model.AgentQueueTaskUi
import app.relay.app.model.AgentRunKey
import app.relay.app.model.AgentRunUi
import app.relay.app.model.AgentSessionKey
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.DeliveryState
import app.relay.app.model.GrantUi
import app.relay.app.model.WorkspaceKey
import app.relay.app.model.WorkspaceUi
import app.relay.core.protocol.AgentGrantAccess
import app.relay.app.model.FileCardUi
import app.relay.app.model.MessageUi
import app.relay.app.model.NoticeKind
import app.relay.app.model.NoticeDetail
import app.relay.app.model.parseThinkingContent

/**
 * 基于 SQLDelight + SQLite FTS5 (Trigram) 的消息持久化与全文检索存储。
 *
 * 核心特性：
 * 1. 结构化索引：基于 `conv_id + timestamp` 建立 B-Tree 索引，毫秒级快速加载；
 * 2. 全文检索：**有 fts5 的机器**上走 FTS5 `tokenize='trigram'`，支持高精度的中文连续字串与子串搜索，
 *    并提取高亮上下文片段；**没有 fts5 的机器**（Android 的框架 SQLite 就没有）自动退到 LIKE 子串匹配
 *    —— 语义一致，只是慢（见 [fts5] 与 `app.relay.db.FtsTolerantSchema`）；
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
     * Android 的框架 SQLite 没编译 fts5：`app.relay.db.FtsTolerantSchema` 会把
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

    override fun load(): Map<String, List<MessageUi>> {
        val existing = queries.selectAllMessages().executeAsList()
        if (existing.isNotEmpty()) {
            return existing.map { it.toUi() }
                .groupBy { it.peerId }
                .mapValues { (_, list) -> list.sortedBy { it.at } }
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

    override fun save(threads: Map<String, List<MessageUi>>) {
        queries.transaction {
            // **先处理"快照里已经没有了"的行** —— 否则删除只发生在调用方的内存里，
            // 库里那行连同它的 FTS 索引会一直留着。
            //
            // 真身：单条本地删除（`handleDeleteMessage`）走的就是"改内存 → 整份快照 save"，
            // 而 save 原来只做 upsert，**从不删任何东西**。于是被删的那条仍留在
            // MessageEntity 里、也仍被搜得到 —— 用户"删了还在"。
            //
            // FTS 必须显式清：这里用的是**逐 id 的 DELETE**（不是 REPLACE），
            // 它会激活 `message_after_delete` 触发器把索引按 'delete' 命令移除。
            // 若改用"REPLACE 掉整表"的方案，隐式删除不触发触发器 ⇒ 孤儿索引
            // （AGENTS.md §2.3 那 21 GB 就是这么来的）。
            val wanted = threads.values.flatten().mapTo(HashSet()) { it.id }
            queries.selectAllMessages().executeAsList().forEach { row ->
                if (row.id !in wanted) {
                    // **只删实体行**：deleteMessage 是一条真正的 DELETE，
                    // 会激活 message_after_delete 触发器，由它按 FTS5 的 delete 命令移除索引条目。
                    //
                    // 这里**不要**再先调 insertMessageDropStaleIndex：那句等于手工再删一遍索引，
                    // 两次删除会让 FTS 虚拟表与实际内容不一致 —— 实测直接抛
                    // SQLITE_CORRUPT_VTAB: Content in the virtual table is corrupt。
                    // （upsert 路径反过来：INSERT OR REPLACE 的隐式删除**不**触发触发器，
                    // 所以那里必须先显式删索引，见下面循环里那句。）
                    queries.deleteMessage(row.id)
                }
            }
            for ((peerId, list) in threads) {
                for (message in list) {
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
                    // SQLDelight 对可空单列也会生成 data class（SelectSeqById），
                    // 所以展开取它里面那个 long。firstOrNull 而不是 executeAsOneOrNull：
                    // 后者要求「恰好 0 或 1 行」，而这里用不到那条严格性。
                    val existingSeq: Long? = queries
                        .selectSeqById(storable.id)
                        .executeAsList()
                        .firstOrNull()
                        ?.seq
                    val seq: Long = existingSeq ?: queries.nextSeqForConv(storable.peerId).executeAsOne()
                    queries.insertMessage(
                        id = storable.id,
                        conv_id = storable.peerId,
                        peer_id = peerId,
                        sender_id = storable.senderId,
                        mine = if (storable.mine) 1L else 0L,
                        text = if (!storable.reasoning.isNullOrBlank()) "<think>\n${storable.reasoning}\n</think>\n${storable.text}" else storable.text,
                        timestamp = storable.at,
                        delivery = storable.delivery?.name,
                        notice = storable.notice?.name,
                        // 通知细节：JSON 一列。null 与 `"null"` 都要落成 null —— 老数据没有它。
                        notice_detail = storable.noticeDetail
                            ?.takeIf { !it.isEmpty }
                            ?.let { runCatching { MessageStore.json.encodeToString(NoticeDetail.serializer(), it) }.getOrNull() },
                        file_json = message.file?.let {
                            runCatching { MessageStore.json.encodeToString(it) }.getOrNull()
                        },
                        // 注意取的是 `message` 而不是 `storable`：`toStorable()` 会把 `file` 抹掉
                        // （文件卡不落盘），但它**刻意保留** `screen`（屏幕协助记录是"这件事发生过"
                        // 的唯一凭据，重启必须还在）。所以两者在这里写法不同不是笔误。
                        screen_json = message.screen?.let {
                            runCatching { MessageStore.json.encodeToString(it) }.getOrNull()
                        },
                        // **会话归属必须落库**：聊天流里那条「任务：xxx / 新会话」分段线
                        // 靠它判（`sessionIdOf` 优先读这个字段）。
                        //
                        // 以前它只活在内存的 MessageUi 里：实时看得到分段线，重新打开聊天
                        // 就没了。而手机端连 id 前缀那条回退也用不上（`agent-ev-` 是桌面端
                        // 本地构造消息的副产物，转发进群时不带过去）。
                        agent_session_id = message.agentSessionId,
                        // 标题必须一起落库：分段线重启后要还显示得出「任务：xxx」，
                        // 而不是退回「新会话」。它与 agent_session_id 同一条纪律 ——
                        // 只活在内存的字段，重新打开聊天就没了。
                        agent_session_title = message.agentSessionTitle,
                        seq = seq,
                    )
                }
            }
        }
        runCatching { legacyStore?.save(threads) }
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

    override fun loadEvents(): Map<AgentSessionKey, List<AgentEventUi>> {
        val list = queries.selectAllAgentEvents().executeAsList()
        return list.map { row ->
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
        }.groupBy { AgentSessionKey(peerId = it.peerId, sessionId = it.sessionId) }
            .mapValues { (_, events) -> events.sortedBy { it.cursor } }
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

private fun app.relay.db.MessageEntity.toUi(): MessageUi {
    val (parsedReasoning, cleanedText) = parseThinkingContent(text)
    return MessageUi(
        id = id,
        peerId = conv_id,
        mine = mine == 1L,
        text = cleanedText,
        at = timestamp,
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
private fun app.relay.db.SearchMessages.toSearchResult(): MessageSearchResult =
    toSearchResult(id, conv_id, peer_id, sender_id, mine, timestamp, snippetText, file_json)

private fun app.relay.db.SearchMessagesLike.toSearchResult(): MessageSearchResult =
    toSearchResult(id, conv_id, peer_id, sender_id, mine, timestamp, snippetText, file_json)

private fun app.relay.db.SearchMessagesInConv.toSearchResult(): MessageSearchResult =
    toSearchResult(id, conv_id, peer_id, sender_id, mine, timestamp, snippetText, file_json)

private fun app.relay.db.SearchMessagesInConvLike.toSearchResult(): MessageSearchResult =
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

private fun app.relay.db.SearchMessagesGrouped.toGroup(): ConversationSearchGroup =
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

private fun app.relay.db.SearchMessagesGroupedLike.toGroup(): ConversationSearchGroup =
    ConversationSearchGroup(
        convId = conv_id,
        matchCount = matchCount.toInt(),
        latestAt = latestAt ?: 0L,
        latestText = latestText.orEmpty(),
    )
