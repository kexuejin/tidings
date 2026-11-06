// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kapp.tenon.platform.AppPlatform
import java.io.File
import java.util.Properties

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
internal actual fun openPlatformDatabase(platform: AppPlatform): SqlDriver {
    val dir = File(platform.dataDirectory.dataDirectory())
    dir.mkdirs()
    val dbFile = File(dir, "relay.db")
    // busy_timeout **必须经 JDBC 属性下发**，不能只跑一次 PRAGMA：
    // sqlite-jdbc 的 ThreadedConnectionManager 是每线程一条连接（ThreadLocal），
    // 而 busy_timeout 是连接级设置 —— 只对建驱动那条连接跑 PRAGMA，真正并发写的
    // worker 线程各自新建连接时它仍是 0，于是立刻 SQLITE_BUSY，两个写者互等。
    // 属性会随每一条新连接交给 DriverManager.getConnection(url, properties)（task-14）。
    // 注意：**只下发 busy_timeout**。journal_mode 是**库文件级**的持久属性（设一次就写进
    // 文件头），把它当"每条连接都设一遍"的属性下发，会在并发写时踩到"新连接要拿排他锁
    // 改 journal_mode、而旧连接正持有写锁" ⇒ SQLITE_BUSY **立即**返回（busy handler 不生效）。
    // WAL 由下面的 applySqlitePragmas 在建驱动这条连接上设一次。
    val properties = Properties().apply {
        setProperty("busy_timeout", SQLITE_BUSY_TIMEOUT_MS.toString())
    }
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", properties)
    // 与 Android 走同一条路：桌面这份 sqlite-jdbc 带 fts5，探针为真 ⇒ 原样建表。
    // 统一用一个 schema 包装，是为了不靠"平台猜"来决定 fts5 在不在。
    //
    // 建表/迁移在这里跑**一次**：createDatabaseDriver 按库路径缓存驱动，所以
    // message store 与 agent store 共用这一条，迁移不会再走第二遍（task-14 的要求③）。
    openDesktopDatabase(driver)
    // 兜底与自证：让建驱动这条连接上 PRAGMA 的返回值立刻可读（属性那条路若被
    // sqlite-jdbc 忽略，单测会在另一条线程上暴露出来）。
    applySqlitePragmas(driver)
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
        else -> {
            // **盖章库与 unversioned 库走同一张判别表**（D-028 / D-029 修正）：
            // SQLDelight 2.0.2 的段条件 oldVersion <= N 让 migrate(N, ...) 重跑 N.sqm
            // （实证 migrate(17,19) duplicate sha256；AgentStorePersistenceTest 实证
            // migrate(6,19) 重跑 5/6.sqm 洗掉 D1/D6 列）—— user_version 单独用会踩重跑。
            // 判据 = **max(user_version, 列形状下限)**：列形状只能「抬高」起点
            // （card_payload 在 ⇒ 库真实进度 ≥19），绝不降低 —— 降低会让换表重建段
            // 重跑把老列洗掉。两个分支共用 resolveMigrateFrom，不许漂移。
            val from = maxOf(
                current,
                resolveMigrateFrom(
                    messageColumns = columns,
                    artifactColumns = columnsOf(driver, ARTIFACT_TABLE),
                    schemaVersion = schema.version,
                ),
            )
            if (from < schema.version) schema.migrate(driver, from, schema.version)
        }
    }
    writeUserVersion(driver, schema.version)
}

/**
 * 按**列形状**判这个库的真实迁移起点（D-028）。
 *
 * 哨兵链按「最新优先」排列，**每个哨兵的值 = 该迁移跑完后库的真实版本**：
 *  - cardPayload（18.sqm 的 ALTER 产物，D-026 原位刷新载荷列）在 ⇒ 库已到
 *    schemaVersion，补章跳迁移；
 *  - sha256（17.sqm 的产物）在而 cardPayload 不在 ⇒ 库已到 18，从 18 跑 18.sqm
 *    到 19（只补 18.sqm 那一段）；
 *  - SCREEN_COLUMN 在 ⇒ 盖章机制上线后的历史库，从 2 开始跑（2.sqm 起的 CREATE
 *    全是 IF NOT EXISTS，重跑安全）；
 *  - 都不在 ⇒ 停在第 1 版，从 1 开始跑。
 *
 * 哨兵与版本号**写死**（与 ScreenHistoryRecordTest 的版本断言同一条哲学：逼加迁移的
 * 人想一遍每一档库形态 —— unversioned(0) 与盖章档现在共用这一张表，防两处漂移）。
 * 5c1d1b46 的教训：缺哨兵的库会被重跑 ALTER 拦在启动前；哨兵值算错是另一种炸法 ——
 * 18.sqm 首跑时把 sha256 哨兵算成 17，migrate(17,19) 重跑了 17.sqm，duplicate
 * sha256（迁移夹具当场抓住）。
 */
private fun resolveMigrateFrom(
    messageColumns: Set<String>,
    artifactColumns: Set<String>,
    schemaVersion: Long,
): Long = when {
    "card_payload" in messageColumns -> schemaVersion
    "sha256" in artifactColumns -> 18L
    SCREEN_COLUMN in messageColumns -> LEGACY_BASELINE_VERSION + 1
    else -> LEGACY_BASELINE_VERSION
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

/** `AgentArtifactEntity` 的 M7-T3 哨兵列（17.sqm）：在 = 结构已是当前形状。 */
internal const val ARTIFACT_TABLE: String = "AgentArtifactEntity"

/**
 * `1.sqm` 加的那一列。0 号库靠它在不在，判断该从第 1 版还是第 2 版开始跑迁移
 * （见 [openDesktopDatabase] 的 `LEGACY_UNVERSIONED` 分支）。
 */
internal const val SCREEN_COLUMN: String = "screen_json"
