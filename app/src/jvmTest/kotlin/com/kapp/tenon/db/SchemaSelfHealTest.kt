// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D4：开库自愈。
 *
 * ## 现场
 *
 * 宿主库 `PRAGMA user_version = 21`，而 `BoardCardRev` **不存在**；每张卡每帧一行
 * `[CARD] rev ledger save FAILED … no such table: BoardCardRev`。手机库同版本号**有**
 * 这张表 ⇒ 不是代码漏建，是这台机器的库血统把 19/20 两段整段跳过了。
 *
 * 修法不碰迁移血统，改成开库后问一句"表在不在"（`CREATE TABLE IF NOT EXISTS`）。
 * 所以这里的用例都围绕一件事：**版本号说什么不重要，表最终得在**。
 */
class SchemaSelfHealTest {

    private fun names(driver: SqlDriver): List<String> {
        val names = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            mapper = { cursor ->
                while (cursor.next().value) names += cursor.getString(0).orEmpty()
                QueryResult.Value(Unit)
            },
            parameters = 0,
        ).value
        return names
    }

    /**
     * 真实现场：库是完整的、版本号也到位，只**缺这一张表**。
     *
     * 造法就是照着现场做 —— 建完整 schema，再把那张表 DROP 掉，断言自愈把它补回来。
     * 这比"在空库上跑一次"更贴题：空库走的是 `Schema.create`，而现场根本不走迁移。
     */
    @Test
    fun aMissingRevTableIsRecreatedEvenThoughTheVersionSaysItExists() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            FtsTolerantSchema(fts5Available = { false }).create(driver)
            // 版本号照旧（模拟老库：SQLDelight 看到 user_version == version 就不再迁移）。
            driver.execute(null, "PRAGMA user_version = ${FtsTolerantSchema().version}", 0).value
            driver.execute(null, "DROP TABLE IF EXISTS BoardCardRev", 0).value
            assertTrue(!names(driver).contains("BoardCardRev"), "前提：造出来的库确实缺表")

            SchemaSelfHeal.run(driver)

            assertTrue(
                names(driver).contains("BoardCardRev"),
                "自愈没有补回 BoardCardRev —— rev 台账会继续每帧一行 FAILED",
            )
        } finally {
            driver.close()
        }
    }

    /** 补出来的表形状要能被 rev 台账的读写用上（列名/主键都对）。 */
    @Test
    fun theRecreatedTableIsUsableForTheRevLedger() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SchemaSelfHeal.run(driver)
            driver.execute(
                null,
                "INSERT OR REPLACE INTO BoardCardRev(card_id, last_rev, updated_at) VALUES ('c1', 7, 100)",
                0,
            ).value
            val rev = mutableListOf<Long>()
            driver.executeQuery(
                identifier = null,
                sql = "SELECT last_rev FROM BoardCardRev WHERE card_id = 'c1'",
                mapper = { cursor ->
                    while (cursor.next().value) rev += cursor.getLong(0) ?: 0L
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ).value
            assertEquals(listOf(7L), rev, "补出来的表结构与 upsert/select 的用法不一致")
        } finally {
            driver.close()
        }
    }

    /**
     * 幂等：每次开库都会跑一遍，第二次不能炸。
     *
     * 这条不是形式主义 —— Android/iOS 的驱动是每次进程启动新建的，用户一天开十次 App
     * 就跑十次；若它不是幂等的，第二次启动就崩在开库上。
     */
    @Test
    fun runningItAgainChangesNothing() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SchemaSelfHeal.run(driver)
            driver.execute(
                null,
                "INSERT INTO BoardCardRev(card_id, last_rev, updated_at) VALUES ('keep', 3, 1)",
                0,
            ).value
            SchemaSelfHeal.run(driver)
            val count = mutableListOf<Long>()
            driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM BoardCardRev",
                mapper = { cursor ->
                    while (cursor.next().value) count += cursor.getLong(0) ?: 0L
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ).value
            assertEquals(listOf(1L), count, "第二次自愈把已有数据洗掉了")
        } finally {
            driver.close()
        }
    }

    /**
     * **防漂移**：自愈写的 DDL 必须与 `20.sqm` 同形。
     *
     * 两处都描述同一张表，一处改了另一处不改，后果是"新库（走迁移）与老库（走自愈）
     * 拿到不同形状的表"—— 这类分歧只有到真机上、到某个特定用户的库上才会发作。
     * 所以两边归一化后逐字比。
     */
    @Test
    fun theHealingDdlMatchesTheMigrationScript() {
        val migration = repoFile("app/src/commonMain/sqldelight/com/kapp/tenon/db/migrations/20.sqm")
        assertTrue(migration.isFile, "读不到 20.sqm：${migration.absolutePath}")
        assertEquals(
            normalize(migration.readText()),
            normalize(SchemaSelfHeal.BOARD_CARD_REV),
            "自愈 DDL 与 20.sqm 漂移了 —— 有人的库走迁移、有人的库走自愈，会得到两种表形状",
        )
    }

    /**
     * **防漂移（21.sqm）**：触发链路那两张表也一样，自愈写的 DDL 必须与 `21.sqm` 同形。
     *
     * 和上一条同理，只是 `21.sqm` 有三条语句，没法整文件逐字比 —— 改成「每条自愈语句
     * 都必须出现在归一化后的迁移脚本里」。归一化去掉了注释与全部空白，所以比的是
     * 列名、类型、约束、顺序与主键，正是会分家的那些东西。
     */
    @Test
    fun theTriggerHealingDdlMatchesMigration21() {
        val migration = repoFile("app/src/commonMain/sqldelight/com/kapp/tenon/db/migrations/21.sqm")
        assertTrue(migration.isFile, "读不到 21.sqm：${migration.absolutePath}")
        val whole = normalize(migration.readText())
        SchemaSelfHeal.triggerStatements.forEach { sql ->
            assertTrue(
                whole.contains(normalize(sql)),
                "自愈 DDL 与 21.sqm 漂移了 —— 有人的库走迁移、有人的库走自愈，会得到两种表形状：$sql",
            )
        }
    }

    /**
     * **quest 三列的自愈**（Phase 1-3 落地后第一次真机验收现场）：
     * `card_payload` 哨兵把这台机器宿主老库的迁移起点抬到 `schemaVersion`，
     * 22/23 两段被判"早就跑过"——`user_version = 24` 而 MessageEntity 没有三列、
     * QuestSuspendedState / QuestLedger 两张表不在。造法：手建一个**老形状**的
     * MessageEntity（无 quest 列）+ 跑自愈，断言列、索引、表全部到位且幂等。
     */
    @Test
    fun questColumnsAndTablesAreHealedOnAnOldShapedDatabase() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // 老形状 MessageEntity：card_payload 在（哨兵触发器），quest 三列不在。
            driver.execute(
                null,
                "CREATE TABLE MessageEntity (" +
                    "id TEXT NOT NULL PRIMARY KEY, conv_id TEXT NOT NULL, card_payload TEXT)",
                0,
            ).value
            val columnsBefore = pragmaColumns(driver, "MessageEntity")
            assertTrue(!columnsBefore.contains("quest_id"), "前提：老库确实没有 quest 列")

            SchemaSelfHeal.run(driver)

            val columns = pragmaColumns(driver, "MessageEntity")
            for (c in listOf("quest_id", "quest_rev", "client_token")) {
                assertTrue(columns.contains(c), "补列缺 $c")
            }
            assertTrue(names(driver).contains("QuestSuspendedState"), "挂起状态表必须补建")
            assertTrue(names(driver).contains("QuestLedger"), "执行台账必须补建")
            // 索引在列之后补齐。
            val indexes = mutableListOf<String>()
            driver.executeQuery(
                identifier = null,
                sql = "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'index_message_%'",
                mapper = { cursor ->
                    while (cursor.next().value) indexes += cursor.getString(0).orEmpty()
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            ).value
            assertTrue(indexes.contains("index_message_quest_id"), "quest_id 索引必须补建")

            // 幂等：再跑一遍不炸、不重复（守卫拦住 ALTER）。
            SchemaSelfHeal.run(driver)
            assertEquals(columns, pragmaColumns(driver, "MessageEntity"))

            // 补出来的形状要能被 quest 路径用上（列名/类型都对）。
            driver.execute(
                null,
                "INSERT INTO MessageEntity(id, conv_id, quest_id, quest_rev) VALUES ('m1', 'assistant', 'q-1', 3)",
                0,
            ).value
        } finally {
            driver.close()
        }
    }

    /** 24.sqm 同款：QuestLedger 的重试列 heal（旧库由 23.sqm 建、无此列）。 */
    @Test
    fun questLedgerRetryColumnsAreHealedOnAnOldShapedDatabase() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            FtsTolerantSchema(fts5Available = { false }).create(driver)
            // 模拟 23.sqm 后的旧形状：直接重建无 24.sqm 两列的 QuestLedger。
            driver.execute(null, "DROP TABLE QuestLedger", 0).value
            driver.execute(null, "CREATE TABLE QuestLedger (quest_id TEXT NOT NULL PRIMARY KEY, branch TEXT NOT NULL, worktree_path TEXT NOT NULL, main_workspace TEXT NOT NULL, conv_id TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL, prompt TEXT NOT NULL DEFAULT '', agent_key TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL)", 0).value

            SchemaSelfHeal.run(driver)
            val columns = pragmaColumns(driver, "QuestLedger")
            assertTrue(columns.contains("client_token"), "client_token 必须被 heal 补上")
            assertTrue(columns.contains("auto_merge"), "auto_merge 必须被 heal 补上")
        } finally {
            driver.close()
        }
    }

    private fun pragmaColumns(driver: SqlDriver, table: String): List<String> {
        val columns = mutableListOf<String>()
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

    /**
     * **防漂移（22/23.sqm）**：quest 自愈的 DDL 必须与迁移脚本同形 ——
     * 逐条出现在归一化后的对应迁移脚本里（列的 ALTER 与两条 CREATE 都比）。
     */
    @Test
    fun theQuestHealingDdlMatchesMigrations22And23() {
        val migration22 = repoFile("app/src/commonMain/sqldelight/com/kapp/tenon/db/migrations/22.sqm")
        val migration23 = repoFile("app/src/commonMain/sqldelight/com/kapp/tenon/db/migrations/23.sqm")
        assertTrue(migration22.isFile && migration23.isFile, "读不到 22/23.sqm")
        val whole22 = normalize(migration22.readText())
        val whole23 = normalize(migration23.readText())
        // QuestLedger 列的 heal 对应 24.sqm（不在 22/23 的锚定范围内）。
        val migration24 = repoFile("app/src/commonMain/sqldelight/com/kapp/tenon/db/migrations/24.sqm")
        assertTrue(migration24.isFile, "读不到 24.sqm")
        val whole24 = normalize(migration24.readText())
        SchemaSelfHeal.questColumnStatements.forEach { c ->
            val inMigration22or24 = whole22.contains(normalize(c.ddl)) || whole24.contains(normalize(c.ddl))
            assertTrue(
                inMigration22or24,
                "补列 DDL 与 22/24.sqm 都漂移了：${c.ddl}",
            )
        }
        SchemaSelfHeal.questIndexStatements.forEach { sql ->
            assertTrue(whole22.contains(normalize(sql)), "quest 索引 DDL 与 22.sqm 漂移了：$sql")
        }
        assertTrue(whole22.contains(normalize(SchemaSelfHeal.QUEST_SUSPENDED_STATE)), "QuestSuspendedState 与 22.sqm 漂移")
        assertTrue(whole23.contains(normalize(SchemaSelfHeal.QUEST_LEDGER)), "QuestLedger 与 23.sqm 漂移")
    }

    /**
     * 从工作目录往上找仓库根。
     *
     * Gradle 跑 `:app:jvmTest` 时 cwd 是**模块目录**（`…/teahouse-mobile/app`），
     * 而 `.sqm` 的路径是相对仓库根的；写死相对路径会在换任务/换 IDE 时静默读错文件。
     */
    private fun repoFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val hit = File(dir, rel)
            if (hit.isFile) return hit
            dir = dir.parentFile
        }
        return File(rel)
    }

    /**
     * 去掉行注释、**去掉全部空白**、统一小写、去掉尾分号 —— 只留语句本身的形状。
     *
     * 空白全去（而不是压成一个）：两个来源的换行/缩进/括号前空格本来就不同，
     * 而这里要比的是"列名、类型、约束、顺序"，那些差异都在非空白字符上。
     */
    private fun normalize(sql: String): String = sql.lineSequence()
        .map { it.substringBefore("--") }
        .joinToString(" ")
        .replace(Regex("\\s+"), "")
        .removeSuffix(";")
        .lowercase()
}
