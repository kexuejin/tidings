// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 18.sqm（升级轮 T-2a，版本 18 → 19）的迁移夹具（DesktopDatabaseOpenTest 同款夹具风格）。
 *
 * 反向验证四钉：
 * 1. **v18 库跑 18.sqm**：WorkspaceBindingEntity / AgentGrantRuleEntity / card_payload
 *    全部到位；老行 card_payload 为 NULL（与「没刷新过」同义，回退读 card_json）；
 * 2. **哨兵库不被二次 ALTER**：表已是当前形状但 user_version=0（5c1d1b46 教训的那档）⇒
 *    补章跳迁移，duplicate column 不许出现 —— 缺 card_payload 哨兵时这条红；
 * 3. **盖章 19**；
 * 4. **1:1 防重唯一索引真的拦重绑**：同 (groupId, hostNodeId, wsPath) 第二条插入必须炸
 *    （防重的落点是数据库约束，不是应用层先查后插）。
 */
class Migration18WorkspaceBindingTest {

    private fun columnsOf(driver: SqlDriver, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                while (cursor.next().value) columns += cursor.getString(1).orEmpty()
                QueryResult.Value(Unit)
            },
            parameters = 0,
        ).value
        return columns
    }

    private fun setUserVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }

    private fun readUserVersion(driver: SqlDriver): Long {
        var version = 0L
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                if (cursor.next().value) version = cursor.getLong(0) ?: 0L
                QueryResult.Value(Unit)
            },
            parameters = 0,
        ).value
        return version
    }

    /** 停在 17 的库（17.sqm 已跑：AgentArtifactEntity 有 sha256；card_payload 不存在）。 */
    private fun createV17Database(driver: SqlDriver) {
        // 手写 v17 真实形状：MessageEntity 十八列（**不含** card_payload）+
        // AgentArtifactEntity（含 sha256 —— 17.sqm 已跑的凭证）。不借
        // FtsTolerantSchema().create 再拆：SQLite 对被触发器/FTS content 引用的表
        // 做 DROP COLUMN 会报 "error in table ... after drop column"，借建再拆的路
        // 走不通（实测）。18.sqm 只有 CREATE/ALTER、不依赖 FTS 与触发器，
        // 夹具省略它们与迁移语义无涉。
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
                notice_detail TEXT,
                file_json TEXT,
                screen_json TEXT,
                agent_session_id TEXT,
                agent_session_title TEXT,
                seq INTEGER,
                card_json TEXT,
                agent_state TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            CREATE TABLE AgentArtifactEntity (
                node_id TEXT NOT NULL,
                artifact_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                file_id TEXT,
                name TEXT NOT NULL,
                bytes INTEGER,
                mime TEXT,
                sha256 TEXT,
                summary TEXT,
                at INTEGER NOT NULL,
                PRIMARY KEY (node_id, artifact_id)
            )
            """.trimIndent(),
            0,
        )
        setUserVersion(driver, 17)
    }

    /**
     * D-028 的核心判据（**先红过**：哨兵值算成 17 时 migrate(17,19) 重跑 17.sqm，
     * duplicate sha256 红；列形状判 from=18 后绿）：user_version=17 的盖章库
     * （AgentArtifactEntity 已有 sha256、card_payload 不在）升 19 **只补 18.sqm 段**。
     */
    @Test
    fun stampedV17DatabaseRunsOnlySegment18() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            createV17Database(driver)
            openDesktopDatabase(driver)
            // 只补 18.sqm 段：三件产物到位（若重跑了 17.sqm，这里的 duplicate 在
            // openDesktopDatabase 内就炸，测试红在异常而不是断言）。
            assertTrue(
                columnsOf(driver, "WorkspaceBindingEntity").contains("workspaceId"),
                "绑定表由 18.sqm 建出",
            )
            assertTrue(columnsOf(driver, MESSAGE_TABLE).contains("card_payload"))
            assertEquals(19L, readUserVersion(driver), "盖章 19")
            // sha256 列原样保留（17.sqm 的产物不许被破坏）。
            assertTrue(
                columnsOf(driver, "AgentArtifactEntity").contains("sha256"),
                "17.sqm 的产物原样保留",
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun v18DatabaseMigratesTo19WithAllThreeArtifacts() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            createV17Database(driver)
            assertTrue(!columnsOf(driver, "WorkspaceBindingEntity").contains("workspaceId"), "夹具要先旧得对")

            openDesktopDatabase(driver)

            val ws = columnsOf(driver, "WorkspaceBindingEntity")
            assertTrue(ws.contains("workspaceId") && ws.contains("groupId") && ws.contains("wsPath"), "绑定表要建出来：$ws")
            assertTrue(
                columnsOf(driver, "AgentGrantRuleEntity").contains("toolPattern"),
                "免批规则表要建出来",
            )
            assertTrue(
                columnsOf(driver, MESSAGE_TABLE).contains("card_payload"),
                "card_payload 列要补出来",
            )
            assertEquals(19L, readUserVersion(driver), "迁移完盖章 19")
        } finally {
            driver.close()
        }
    }

    @Test
    fun unversionedCurrentSchemaIsNotReAlteredBy18() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // 「表已最新、没人盖章」：FtsTolerantSchema 建的就是当前形状（含 card_payload）。
            FtsTolerantSchema().create(driver)
            setUserVersion(driver, 0)
            val before = columnsOf(driver, MESSAGE_TABLE).toSet()

            openDesktopDatabase(driver)

            // 缺 card_payload 哨兵时：18.sqm 的 ALTER 重跑 ⇒ duplicate column 拦在启动前。
            assertEquals(before, columnsOf(driver, MESSAGE_TABLE).toSet(), "不许重复加列")
            assertEquals(19L, readUserVersion(driver), "补章 19")
        } finally {
            driver.close()
        }
    }

    /** S14：同宿主同路径**全局**唯一 —— 跨群重绑必须被 idx_ws_host_path 拦下。 */
    @Test
    fun sameHostSamePathIsGloballyUniqueAcrossGroups() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            openDesktopDatabase(driver)
            val row = "(workspaceId, groupId, hostNodeId, wsPath, gitBranch, isHostActive, createdAt, updatedAt) " +
                "VALUES ('%WID%', '%GRP%', 'host-1', '/repo/a', 'main', 1, 1, 1)"
            driver.execute(null, "INSERT INTO WorkspaceBindingEntity " + row.replace("%WID%", "w1").replace("%GRP%", "g-1"), 0)
            try {
                driver.execute(null, "INSERT INTO WorkspaceBindingEntity " + row.replace("%WID%", "w2").replace("%GRP%", "g-2"), 0)
                fail("g-2 重绑 g-1 已占的路径必须被 idx_ws_host_path 拦下（R4 全局防重）")
            } catch (e: Exception) {
                assertTrue(
                    (e.message ?: "").contains("UNIQUE", ignoreCase = true) ||
                        (e.cause?.message ?: "").contains("UNIQUE", ignoreCase = true),
                    "炸的原因必须是 UNIQUE 约束：" + e.message,
                )
            }
            // 不同宿主的同名路径互不相干（索引含 hostNodeId）。
            driver.execute(
                null,
                "INSERT INTO WorkspaceBindingEntity " +
                    "(workspaceId, groupId, hostNodeId, wsPath, gitBranch, isHostActive, createdAt, updatedAt) " +
                    "VALUES ('w3', 'g-2', 'host-2', '/repo/a', 'main', 1, 1, 1)",
                0,
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun legacyRowsHaveNullCardPayload() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            createV17Database(driver)
            driver.execute(
                null,
                "INSERT INTO MessageEntity (id, conv_id, peer_id, mine, text, timestamp) " +
                    "VALUES ('m1', 'c1', 'p1', 1, '老消息', 1000)",
                0,
            )
            openDesktopDatabase(driver)
            driver.executeQuery(
                identifier = null,
                sql = "SELECT card_payload, card_json FROM MessageEntity WHERE id = 'm1'",
                mapper = { cursor ->
                    assertTrue(cursor.next().value, "老行必须还在")
                    kotlin.test.assertNull(cursor.getString(0), "老行 card_payload 是 NULL = 未刷新（回退读 card_json）")
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ).value
        } finally {
            driver.close()
        }
    }

    @Test
    fun workspaceBindingUniqueIndexBlocksDuplicateBinding() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            openDesktopDatabase(driver)
            val insert = "INSERT INTO WorkspaceBindingEntity " +
                "(workspaceId, groupId, hostNodeId, wsPath, gitBranch, isHostActive, createdAt, updatedAt) " +
                "VALUES ('w1', 'g1', 'host-1', '/repo/a', 'main', 1, 1, 1)"
            driver.execute(null, insert, 0)
            try {
                driver.execute(null, insert.replace("'w1'", "'w2'"), 0)
                fail("同 (groupId, hostNodeId, wsPath) 的第二条绑定必须被唯一索引拦下")
            } catch (e: Exception) {
                // 预期：UNIQUE 约束炸（驱动层包装类型不进判据）—— 防重的落点是数据库约束本身。
                assertTrue(
                    (e.message ?: "").contains("UNIQUE", ignoreCase = true) ||
                        (e.cause?.message ?: "").contains("UNIQUE", ignoreCase = true),
                    "炸的原因必须是 UNIQUE 约束，而不是别的错：" + e.message,
                )
            }
        } finally {
            driver.close()
        }
    }
}
