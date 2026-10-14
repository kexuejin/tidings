// SPDX-License-Identifier: GPL-3.0-only

package app.relay.app

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.relay.db.RelayDatabase
import app.relay.db.openDesktopDatabase
import app.relay.db.readUserVersion
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.relay.app.model.AgentApprovalUi
import app.relay.app.model.AgentArtifactKey
import app.relay.app.model.AgentArtifactUi
import app.relay.app.model.AgentQueueTaskUi
import app.relay.app.model.AgentRunKey
import app.relay.app.model.AgentRunUi
import app.relay.app.model.AgentSessionKey
import app.relay.app.model.AgentSessionUi

/**
 * `AgentStore` 的**跨重启**契约（PPE-1 §6.3–§6.7 的四张表）。
 *
 * ## 它修的是什么
 *
 * 会话与事件一早就落盘了，审批 / 队列任务 / 执行状态 / 产物却只有内存态。后果不是"少几行记录"：
 * 审批的**处置结果**是本端产生的、对端无从重算的状态 —— 用户点完「允许」，面板改成 `approved`，
 * 一重启又回到「待审批」，而对端早已不再重发那条审批。所以这里断言的是"**重启之后它还在**"。
 *
 * ## 为什么敢叫"重启"
 *
 * 用的是**真文件**数据库，两次打开都走桌面端真正的那条路径 [openDesktopDatabase]
 * （建表 / 迁移 / 盖章三步），不是拿同一个内存驱动读两遍 —— 后者证明不了任何进程外的事情。
 * 第二次打开还会经过 `current >= schema.version` 那一档，顺带证明重开不会破坏结构。
 */
class AgentStorePersistenceTest {

    private val dbFile: File = File.createTempFile("relay-agent-store-", ".db").apply { delete() }

    @AfterTest
    fun cleanup() {
        dbFile.delete()
    }

    /**
     * 开一次库：走真实打开路径，跑完关掉。**不用 `use`** —— `SqlDriver` 不是 `AutoCloseable`
     * （仓里既有测试也都是 try/finally）。
     */
    private fun <T> reopen(block: (SqlDriver) -> T): T {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            openDesktopDatabase(driver)
            return block(driver)
        } finally {
            driver.close()
        }
    }

    @Test
    fun approvalDecisionAndFriendsSurviveAReopen() {
        // ---- 第一次运行：写进去 ----
        reopen { driver ->
            val store = SqlDelightAgentStore(RelayDatabase(driver))
            store.saveApproval(
                AgentApprovalUi(
                    approvalId = "ap-1",
                    sessionId = "s-1",
                    peerId = "peer-A",
                    tool = "shell.exec",
                    summary = "要不要跑 rm -rf build",
                    expiresAt = 1_800_000_000_000L,
                    choices = "allow,deny",
                    status = "approved",
                ),
            )
            store.saveTask(
                AgentQueueTaskUi(
                    taskId = "t-1",
                    sessionId = "s-1",
                    peerId = "peer-A",
                    op = "enqueue",
                    prompt = "把撤回窗口补上",
                    queuedAt = 1_700_000_000_000L,
                ),
            )
            store.saveRun(
                AgentRunUi(
                    runId = "r-1",
                    sessionId = "s-1",
                    peerId = "peer-A",
                    state = "running",
                    at = 1_700_000_000_100L,
                    updatedAt = 1_700_000_000_200L,
                ),
            )
            store.saveArtifact(
                AgentArtifactUi(
                    artifactId = "a-1",
                    sessionId = "s-1",
                    peerId = "peer-A",
                    fileId = "f-1",
                    name = "patch.diff",
                    bytes = 2048,
                    mime = "text/x-diff",
                    at = 1_700_000_000_300L,
                ),
            )
        }

        // ---- 第二次运行：同一个文件，重新开 ----
        reopen { driver ->
            val store = SqlDelightAgentStore(RelayDatabase(driver))

            val approval = store.loadApprovals()["ap-1"]
            assertEquals(
                "approved",
                approval?.status,
                "**审批的处置结果是本条修复的核心**：重启后它必须还是 approved，不能退回 pending",
            )
            assertEquals("shell.exec", approval?.tool)
            assertEquals("peer-A", approval?.peerId)
            assertEquals(1_800_000_000_000L, approval?.expiresAt)

            val task = store.loadTasks()["t-1"]
            assertEquals("把撤回窗口补上", task?.prompt)
            assertEquals("peer-A", task?.peerId)

            val run = store.loadRuns()[AgentRunKey(peerId = "peer-A", runId = "r-1")]
            assertEquals("running", run?.state)
            assertEquals(1_700_000_000_200L, run?.updatedAt)

            val artifact = store.loadArtifacts()[AgentArtifactKey(peerId = "peer-A", artifactId = "a-1")]
            assertEquals("patch.diff", artifact?.name)
            assertEquals(2048L, artifact?.bytes)
            assertEquals("text/x-diff", artifact?.mime)
        }
    }

    @Test
    fun dequeuedTaskDoesNotComeBackAfterAReopen() {
        reopen { driver ->
            val store = SqlDelightAgentStore(RelayDatabase(driver))
            store.saveTask(
                AgentQueueTaskUi(taskId = "t-2", peerId = "peer-A", op = "enqueue", queuedAt = 1L),
            )
            // 与 `RelayControllerAgent.onAgentQueue(op = "dequeue")` 同一动作。
            store.deleteTask(peerId = "peer-A", taskId = "t-2")
            // 换个 peer 的同名 taskId 不该被误删：两个节点的 taskId 会重名。
            store.saveTask(
                AgentQueueTaskUi(taskId = "t-3", peerId = "peer-B", op = "enqueue", queuedAt = 2L),
            )
            store.deleteTask(peerId = "peer-A", taskId = "t-3")
        }

        reopen { driver ->
            val tasks = SqlDelightAgentStore(RelayDatabase(driver)).loadTasks()
            assertNull(tasks["t-2"], "撤掉的任务不该在重启后复活：$tasks")
            assertTrue("t-3" in tasks, "peer-B 的 t-3 不该被 peer-A 的删除误伤：$tasks")
        }
    }

    /**
     * 已经跑过第 2 版的**老库**（只有 session / event 两张表）打开后必须拿到那四张新表 ——
     * 这正是 `2.sqm` + `DatabaseDriverFactory.jvm.kt` 那条修法要保证的事。
     *
     * 夹具刻意造成「结构停在第 2 版、`user_version` 已经是 2」：`openDesktopDatabase` 会走
     * `migrate(2, schema.version)`，只跑 `2.sqm`（跑 `1.sqm` 会 `duplicate column`）。
     */
    @Test
    fun anOlderDatabaseGainsTheFourAgentTablesOnOpen() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            // 第 2 版的形状：消息表已带 screen_json，agent 侧只有 session / event。
            //
            // 这里必须建**完整的 v2 消息表**，不能图省事写 `MessageEntity(id, screen_json)`：
            // `3.sqm` 删 outgoing_envelope 那一列时是「建新表 + INSERT … SELECT 点名列 +
            // 换名」，SELECT 会点名 conv_id/peer_id/… —— 少了任何一列，迁移当场报
            // `no such column`，而症状是"打不开聊天记录库"，跟真实故障长得一模一样。
            // 夹具假一点，就测不出这类回归。
            driver.execute(
                null,
                """
                CREATE TABLE MessageEntity (
                    id TEXT NOT NULL PRIMARY KEY,
                    conv_id TEXT NOT NULL,
                    peer_id TEXT NOT NULL,
                    sender_id TEXT,
                    mine INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    delivery TEXT,
                    outgoing_envelope TEXT,
                    notice TEXT,
                    file_json TEXT,
                    screen_json TEXT
                )
                """.trimIndent(),
                0,
            )
            // 迁移不能把老行弄丢：这一行必须在升到第 4 版之后还能读出来。
            driver.execute(
                null,
                "INSERT INTO MessageEntity (id, conv_id, peer_id, mine, text, timestamp) " +
                    "VALUES ('old-1', 'peer-1', 'peer-1', 0, '删列之前写的消息', 10)",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE AgentSessionEntity (" +
                    "node_id TEXT NOT NULL, session_id TEXT NOT NULL, alias TEXT, agent_kind TEXT, " +
                    "state TEXT, title TEXT, started_at INTEGER, updated_at INTEGER NOT NULL, " +
                    "announced INTEGER NOT NULL, PRIMARY KEY (node_id, session_id))",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE AgentEventEntity (" +
                    "node_id TEXT NOT NULL, session_id TEXT NOT NULL, cursor INTEGER NOT NULL, " +
                    "event_type TEXT NOT NULL, summary TEXT, body TEXT, ref TEXT, at INTEGER NOT NULL, " +
                    "PRIMARY KEY (node_id, session_id, cursor))",
                0,
            )
            driver.execute(null, "PRAGMA user_version = 2", 0)
            assertTrue(
                tables(driver).none { it.startsWith("AgentApproval") },
                "夹具本身要先旧得对：${tables(driver)}",
            )

            openDesktopDatabase(driver)

            val after = tables(driver)
            for (t in listOf("AgentApprovalEntity", "AgentTaskEntity", "AgentRunEntity", "AgentArtifactEntity")) {
                assertTrue(t in after, "$t 没被 2.sqm 建出来，实际是 $after")
            }
            assertEquals(RelayDatabase.Schema.version, readUserVersion(driver), "迁移完要盖章")

            // 删掉 outgoing_envelope 那一列之后，老行必须还在（3.sqm 重建表不是重建数据）。
            val kept = RelayDatabase(driver).relayDatabaseQueries
                .selectMessagesByConv("peer-1").executeAsList().single()
            assertEquals("删列之前写的消息", kept.text, "3.sqm 换表时把老行弄丢了")
            assertNull(kept.screen_json, "没写过的列仍是 NULL ⇒ 不需要回填")

            // 光有表还不够：生成的查询必须真的能在这个库上跑通。
            val store = SqlDelightAgentStore(RelayDatabase(driver))
            store.saveApproval(
                AgentApprovalUi(approvalId = "ap-9", sessionId = "s-9", peerId = "peer-X", status = "denied"),
            )
            assertEquals("denied", store.loadApprovals()["ap-9"]?.status)
        } finally {
            driver.close()
        }
    }

    /**
     * PPE-1 增补 D1/D6 的四个新列必须**跨重启**存活（4.sqm 的落库面）。
     *
     * 判据是内容而不是文件存在：saveSession → 关库 → 重开 → loadSessions 逐字段比。
     */
    @Test
    fun theFourWorkspaceColumnsSurviveAReopen() {
        val full = AgentSessionUi(
            peerId = "peer-ws", sessionId = "s-ws-1", alias = "带工作区的会话", agentKind = "DSH",
            agentId = "dsh-cli", wsId = "ws-e9b18063d183", wsTitle = "teahouse-mobile",
            wsPath = "/workspace/src/teahouse-mobile", updatedAt = 10,
            // 6.sqm 的落库面。写一个与 updatedAt **明显不同**的值：两列同名不同义，
            // 若读写时串了，等值夹具会两边都通过、把缺陷放过去。
            hostUpdatedAt = 1_700_000_000_500L,
        )
        reopen { driver -> SqlDelightAgentStore(RelayDatabase(driver)).saveSession(full) }
        reopen { driver ->
            val back = SqlDelightAgentStore(RelayDatabase(driver))
                .loadSessions()[AgentSessionKey("peer-ws", "s-ws-1")]
            assertEquals(full.agentId, back?.agentId, "agent_id 没跨重启存活")
            assertEquals(full.wsId, back?.wsId, "ws_id 没跨重启存活")
            assertEquals(full.wsTitle, back?.wsTitle, "ws_title 没跨重启存活")
            assertEquals(full.wsPath, back?.wsPath, "ws_path 没跨重启存活")
            assertEquals(full.hostUpdatedAt, back?.hostUpdatedAt, "host_updated_at 没跨重启存活")
            assertEquals(full.sortActivityAt, back?.sortActivityAt, "排序键读回来变了")
        }
    }

    /**
     * 收尾帧（只带 state）不得把已落库的 wsPath 抹空 —— 端到端验证
     * （verify-dsh-workspace-session.py 第六步）在真机链路上判红过一次：
     * 第一版把「裸 wsPath 忽略」（父稿 §2.1）写成了「帧里没带 wsPath 就清空」。
     */
    @Test
    fun aStateOnlyFrameKeepsTheStoredWorkspacePath() {
        val prev = AgentSessionUi(
            peerId = "p", sessionId = "s", wsId = "ws-1", wsTitle = "t",
            wsPath = "/abs/path", updatedAt = 1,
        )
        // 只更新 state 的收尾帧：四个新键一个都没带。
        val closing = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"sessionId":"s","state":"done"}""",
        ) as kotlinx.serialization.json.JsonObject
        val merged = agentSessionFrom(closing, "p", 2, prev)
        assertEquals("/abs/path", merged?.wsPath, "收尾帧把已存的 wsPath 抹空了")
        assertEquals("ws-1", merged?.wsId)
        assertEquals("done", merged?.state)

        // 对照：真正违反 §2.1 的**裸 wsPath 帧**（同帧带 wsPath 但 wsId/wsTitle 双缺）
        // 仍然被忽略 —— 不许把路径变成归组依据。
        val bare = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"sessionId":"s","wsPath":"/other"}""",
        ) as kotlinx.serialization.json.JsonObject
        val afterBare = agentSessionFrom(bare, "p", 3, prev)
        assertEquals("/abs/path", afterBare?.wsPath, "裸 wsPath 帧不该覆盖已存的归组")
        // 全新会话 + 裸 wsPath：不落地。
        val fresh = agentSessionFrom(bare, "p", 4, null)
        assertNull(fresh?.wsPath, "没有 wsId/wsTitle 时路径不入库")
    }

/**
     * 停在第 6 版的**老库**（`AgentSessionEntity` 还是 13 列）打开后必须拿到第 14 列
     * `host_updated_at`，且老行一行不丢 —— 这正是 `6.sqm` 要保证的事。
     *
     * 夹具刻意只造 `AgentSessionEntity` + `MessageEntity`、`user_version = 6`：
     * `openDesktopDatabase` 会走 `migrate(6, 7)`，只跑 `6.sqm`（跑更早的会 duplicate column）。
     * 断言「列在不在」而不是「读得出来吗」：重建表写错时最容易出现的症状是
     * `no such column: host_updated_at`，而那是**打开库那一刻**的事，不等到第一次查询。
     */
    @Test
    fun anOlderSessionTableGainsTheActivityColumnOnOpen() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            driver.execute(
                null,
                """
                CREATE TABLE MessageEntity (
                    id TEXT NOT NULL PRIMARY KEY,
                    conv_id TEXT NOT NULL,
                    peer_id TEXT NOT NULL,
                    sender_id TEXT,
                    mine INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    delivery TEXT,
                    notice TEXT,
                    file_json TEXT,
                    screen_json TEXT
                )
                """.trimIndent(),
                0,
            )
            // 第 5/6 版的形状：D1/D6 四列都在，就是没有 host_updated_at。
            driver.execute(
                null,
                "CREATE TABLE AgentSessionEntity (" +
                    "node_id TEXT NOT NULL, session_id TEXT NOT NULL, alias TEXT, agent_kind TEXT, " +
                    "state TEXT, title TEXT, started_at INTEGER, updated_at INTEGER NOT NULL, " +
                    "announced INTEGER NOT NULL, agent_id TEXT, ws_id TEXT, ws_title TEXT, " +
                    "ws_path TEXT, PRIMARY KEY (node_id, session_id))",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO AgentSessionEntity " +
                    "(node_id, session_id, alias, state, updated_at, announced, ws_id) " +
                    "VALUES ('peer-old', 's-old', '加列之前的会话', 'busy', 42, 1, 'ws-old')",
                0,
            )
            driver.execute(null, "PRAGMA user_version = 6", 0)

            openDesktopDatabase(driver)

            val columns = columns(driver, "AgentSessionEntity")
            assertTrue("host_updated_at" in columns, "6.sqm 没把新列建出来，实际是 " + columns)
            assertEquals(RelayDatabase.Schema.version, readUserVersion(driver), "迁移完要盖章")

            // 重建表不是重建数据：老行必须原样还在，新列如实为 NULL（不是 0）。
            val store = SqlDelightAgentStore(RelayDatabase(driver))
            val kept = store.loadSessions()[AgentSessionKey("peer-old", "s-old")]
            assertEquals("加列之前的会话", kept?.alias, "6.sqm 换表时把老行弄丢了")
            assertEquals("busy", kept?.state)
            assertEquals(42L, kept?.updatedAt)
            assertEquals("ws-old", kept?.wsId, "四个 D1/D6 列不该被这次重建洗掉")
            assertNull(kept?.hostUpdatedAt, "老行的新列是 NULL ⇒ 不是 0（0 会被当成 1970 年动过）")
        } finally {
            driver.close()
        }
    }

    /**
     * 停在第 7 版的**老库**（`AgentSessionEntity` 是 14 列）打开后必须拿到第 15/16 列
     * `origin` / `blank`，且老行一行不丢 —— 这正是 `7.sqm` 要保证的事。
     *
     * 夹具刻意只造 `user_version = 7`（跑更早的会 duplicate column）。两条断言都是"NULL 不等于 false"：
     * 把"没听宿主说过"读成"可见"正是要把存量脏行留在界面上的那种错。
     */
    @Test
    fun anOlderSessionTableGainsTheVisibilityColumnsOnOpen() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            driver.execute(
                null,
                """
                CREATE TABLE MessageEntity (
                    id TEXT NOT NULL PRIMARY KEY,
                    conv_id TEXT NOT NULL,
                    peer_id TEXT NOT NULL,
                    sender_id TEXT,
                    mine INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    delivery TEXT,
                    notice TEXT,
                    file_json TEXT,
                    screen_json TEXT
                )
                """.trimIndent(),
                0,
            )
            // 第 6 版的形状：host_updated_at 在，就是没有 origin / blank。
            driver.execute(
                null,
                "CREATE TABLE AgentSessionEntity (" +
                    "node_id TEXT NOT NULL, session_id TEXT NOT NULL, alias TEXT, agent_kind TEXT, " +
                    "state TEXT, title TEXT, started_at INTEGER, updated_at INTEGER NOT NULL, " +
                    "announced INTEGER NOT NULL, agent_id TEXT, ws_id TEXT, ws_title TEXT, " +
                    "ws_path TEXT, host_updated_at INTEGER, PRIMARY KEY (node_id, session_id))",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO AgentSessionEntity " +
                    "(node_id, session_id, alias, state, updated_at, announced, ws_id, host_updated_at) " +
                    "VALUES ('peer-v', 's-v', '加列之前的会话', 'done', 42, 1, 'ws-v', 7)",
                0,
            )
            driver.execute(null, "PRAGMA user_version = 7", 0)

            openDesktopDatabase(driver)

            val columns = columns(driver, "AgentSessionEntity")
            assertTrue("origin" in columns, "7.sqm 没把 origin 建出来，实际是 " + columns)
            assertTrue("blank" in columns, "7.sqm 没把 blank 建出来，实际是 " + columns)
            assertEquals(RelayDatabase.Schema.version, readUserVersion(driver), "迁移完要盖章")

            // 重建表不是重建数据：老行必须原样还在，两列如实为 NULL（不是 false）。
            val store = SqlDelightAgentStore(RelayDatabase(driver))
            val kept = store.loadSessions()[AgentSessionKey("peer-v", "s-v")]
            assertEquals("加列之前的会话", kept?.alias, "7.sqm 换表时把老行弄丢了")
            assertEquals(7L, kept?.hostUpdatedAt, "6.sqm 加的那列不该被这次重建洗掉")
            assertNull(kept?.hostOrigin, "老行的 origin 是 NULL ⇒ 不是 \"subagent\"")
            assertNull(kept?.hostBlank, "老行的 blank 是 NULL ⇒ 不是 false（false 会被读成\"确认可见\"）")
        } finally {
            driver.close()
        }
    }

    /**
     * 可见性两键必须**跨重启**存活，且 `false` 与 `null` 读回来仍然是两回事。
     *
     * 夹具把 `hostBlank` 设成 `false`（而不是 `true`）：三态里最容易写错的一档就是它 ——
     * 落库写成 `if (blank) 1 else 0` 会把 null 也压成 0，读回来变成"宿主说它可见"。
     */
    @Test
    fun theVisibilityColumnsSurviveAReopen() {
        val known = AgentSessionUi(
            peerId = "peer-vis", sessionId = "s-vis-1", alias = "子代理会话", agentKind = "DSH",
            updatedAt = 10, hostOrigin = "subagent", hostBlank = false,
        )
        val unknown = AgentSessionUi(
            peerId = "peer-vis", sessionId = "s-vis-2", alias = "没听宿主说过", agentKind = "DSH",
            updatedAt = 11,
        )
        reopen { driver ->
            val store = SqlDelightAgentStore(RelayDatabase(driver))
            store.saveSession(known)
            store.saveSession(unknown)
        }
        reopen { driver ->
            val store = SqlDelightAgentStore(RelayDatabase(driver))
            val backKnown = store.loadSessions()[AgentSessionKey("peer-vis", "s-vis-1")]
            val backUnknown = store.loadSessions()[AgentSessionKey("peer-vis", "s-vis-2")]
            assertEquals("subagent", backKnown?.hostOrigin, "origin 没跨重启存活")
            assertEquals(false, backKnown?.hostBlank, "blank=false 读回来变成了 " + backKnown?.hostBlank)
            assertNull(backUnknown?.hostOrigin, "没听宿主说过的会话 origin 该是 NULL")
            assertNull(backUnknown?.hostBlank, "没听宿主说过的会话 blank 该是 NULL，不是 false")
        }
    }

    /** `PRAGMA table_info` 的列名（加列类迁移的判据要问"列在不在"，不是"读得出来吗"）。 */
    private fun columns(driver: SqlDriver, table: String): List<String> {
        val names = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info(" + table + ")",
            mapper = { cursor ->
                while (cursor.next().value) names += cursor.getString(1).orEmpty()
                QueryResult.Value(Unit)
            },
            parameters = 0,
        ).value
        return names
    }

    private fun tables(driver: SqlDriver): List<String> {

        val names = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            mapper = { cursor ->
                while (cursor.next().value) names += cursor.getString(0).orEmpty()
                QueryResult.Value(Unit)
            },
            parameters = 0,
        ).value
        return names
    }
}
