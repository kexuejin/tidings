// SPDX-License-Identifier: GPL-3.0-only

package app.relay.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.relay.platform.AppPlatform
import java.io.File

/**
 * 打开桌面端的库：**按观察到的库状态决定动作，而不是按文件是否存在。**
 *
 * ## 旧实现错在哪
 *
 * 以前是 `if (!dbFile.exists() || dbFile.length() == 0L) Schema.create(driver)` ——
 * 文件在就什么都不做，「schema 是不是最新的」这件事从来没被问过。三条实测：
 *
 * 1. `PRAGMA user_version` 在一张 `Schema.create` 刚建出来的桌面库上是 **0**：桌面这条路上
 *    没有任何东西写版本号。（`AndroidSqliteDriver` 与 `NativeSqliteDriver` 都由驱动写，
 *    所以**三个平台里只有桌面缺这一段**。）
 * 2. 生成的查询是**显式点名列**的（`RelayDatabaseQueries.kt` 的
 *    `SELECT MessageEntity.id, …, MessageEntity.screen_json FROM MessageEntity`），
 *    所以一个 `screen_json` 之前建的库启动时会直接
 *    `no such column: MessageEntity.screen_json`。
 * 3. 后果：`composeApp/src/commonMain/sqldelight/app/relay/db/migrations/` 下那些 `.sqm`
 *    在 Android / iOS 上真的执行，**在桌面上是死代码**。
 *
 * ## `user_version = 0` 的语义是「未知」，不是「第 0 版」
 *
 * 老桌面端从不写版本号，所以读回 0 的库实际处在**第 1 版**（`1.sqm` 之前那张表）。
 * 但这个 0 也可能来自一张**表结构已经是最新**的库（例如本次修复之前、刚用新代码建出来的库），
 * 对后者再跑一次 `1.sqm` 就是 `duplicate column name: screen_json` —— 所以 0 这一档
 * 必须先看一眼列在不在，不能一律迁移。
 */
actual fun createDatabaseDriver(platform: AppPlatform): SqlDriver {
    val dir = File(platform.dataDirectory.dataDirectory())
    dir.mkdirs()
    val dbFile = File(dir, "relay.db")
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
    // 与 Android 走同一条路：桌面这份 sqlite-jdbc 带 fts5，探针为真 ⇒ 原样建表。
    // 统一用一个 schema 包装，是为了不靠"平台猜"来决定 fts5 在不在。
    openDesktopDatabase(driver)
    return driver
}

/**
 * 建表 / 迁移 / 盖章三步，抽出来是为了能在单测里拿一个 `JdbcSqliteDriver` 直接驱动，
 * 不必造一个 `AppPlatform`。
 */
internal fun openDesktopDatabase(
    driver: SqlDriver,
    schema: SqlSchema<QueryResult.Value<Unit>> = FtsTolerantSchema(),
) {
    val columns = columnsOf(driver, MESSAGE_TABLE)
    val current = readUserVersion(driver)
    when {
        // 空库（或只建了一半、连消息表都没有的库）：当新库建。
        columns.isEmpty() -> schema.create(driver)
        // 比当前 schema 还新（降级场景）：什么都不做，也别把版本号往回写。
        current >= schema.version -> return
        current == LEGACY_UNVERSIONED -> {
            // 0 号库有两种可能，靠「`1.sqm` 加的那一列在不在」区分：
            //  - 列不在 ⇒ 真的停在第 1 版：从第 1 版开始跑（`1.sqm` + 其后所有 `.sqm`）；
            //  - 列在   ⇒ 结构已等于第 2 版，只是没人盖过章：必须从第 2 版开始跑，
            //             否则再跑一遍 `1.sqm` 就是 `duplicate column name: screen_json`。
            // 不能简成「结构新就什么都不做」：第 2 版之后新增的表（`2.sqm` 的四张 agent 表）
            // 在这类库上还没建，跳过它一样开不了库。那些 `.sqm` 是**幂等**的
            // （`CREATE TABLE IF NOT EXISTS`），所以对已经最新的库重跑一遍是安全的。
            val from = if (SCREEN_COLUMN in columns) LEGACY_BASELINE_VERSION + 1 else LEGACY_BASELINE_VERSION
            schema.migrate(driver, from, schema.version)
        }
        else -> schema.migrate(driver, current, schema.version)
    }
    writeUserVersion(driver, schema.version)
}

/**
 * `PRAGMA table_info` 的列名集合。空集合 = 这张表不存在（`MessageEntity` 不可能零列）。
 */
private fun columnsOf(driver: SqlDriver, table: String): Set<String> {
    val columns = mutableSetOf<String>()
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA table_info($table)",
        mapper = { cursor ->
            while (cursor.next().value) cursor.getString(1)?.let(columns::add)
            QueryResult.Value(Unit)
        },
        parameters = 0,
    ).value
    return columns
}

internal fun readUserVersion(driver: SqlDriver): Long {
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

private fun writeUserVersion(driver: SqlDriver, version: Long) {
    driver.execute(null, "PRAGMA user_version = $version", 0)
}

/** 老桌面端从不写 `user_version`，所以 0 是「未知」。 */
private const val LEGACY_UNVERSIONED: Long = 0L

/** 0 号库的实际版本：`1.sqm` 之前那张表就是第 1 版。 */
private const val LEGACY_BASELINE_VERSION: Long = 1L

internal const val MESSAGE_TABLE: String = "MessageEntity"

/**
 * `1.sqm` 加的那一列。0 号库靠它在不在，判断该从第 1 版还是第 2 版开始跑迁移
 * （见 [openDesktopDatabase] 的 `LEGACY_UNVERSIONED` 分支）。
 */
internal const val SCREEN_COLUMN: String = "screen_json"
