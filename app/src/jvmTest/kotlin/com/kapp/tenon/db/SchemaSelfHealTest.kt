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
