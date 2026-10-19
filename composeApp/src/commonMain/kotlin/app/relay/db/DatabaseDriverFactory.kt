// SPDX-License-Identifier: GPL-3.0-only

package app.relay.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.relay.app.StateLock
import app.relay.app.createStateLock
import app.relay.app.withLock
import app.relay.platform.AppPlatform

/**
 * 打开平台自己的库 —— **原始创建，不做任何缓存**。
 *
 * 各平台分别映射到原生高性能驱动：
 * - Android: `AndroidSqliteDriver`
 * - Desktop (JVM): `JdbcSqliteDriver`
 * - iOS: `NativeSqliteDriver`
 *
 * 生产代码**不要**直接调它：走 [createDatabaseDriver]（每进程每库只开一条）。
 * 它留在 `expect/actual` 里的唯一理由是「怎么开」是平台的事；「开几条」是全局的事。
 */
internal expect fun openPlatformDatabase(platform: AppPlatform): SqlDriver

/**
 * 每个库文件每进程**只开一个驱动** —— message store 与 agent store 共用同一条（task-14）。
 *
 * ## 它修的是什么
 *
 * `createMessageStore(platform)` 与 `createAgentStore(platform)` 原先**各自**调一次
 * [openPlatformDatabase]（当时的名字就是 `createDatabaseDriver`），于是同一个 `relay.db`
 * 上开了两条驱动、两条连接；而建表/迁移是在创建驱动时跑的，所以迁移也走了两遍 ——
 * 这本身就是一条竞态。桌面端没有 WAL、也没有 busy_timeout，两个写者只能互等：
 * 真机上表现为 journal 冻结数分钟、外部 `sqlite3 -readonly` 全部 `database is locked`、
 * 6+ 个 worker 卡在 `NativeDB.step`（低 CPU 空等），重启后半小时内复发。
 *
 * ## 为什么按路径缓存，而不是「由组合根注入一次」
 *
 * 组合根（`Main.kt` / `MainViewController.kt` / Android 的 Application）确实只该建一次，
 * 但**契约上没有任何东西阻止**第二次调用 `createMessageStore`（预览、测试、未来的
 * 第二个宿主入口都会这么干）。把不变式放在最靠近资源的地方（打开库的这一个函数），
 * 才不依赖「调用方记得只调一次」。
 *
 * ## 键为什么是数据目录
 *
 * 一个数据根下只有一个 `relay.db`（三个平台都是这个名字）。用数据目录当键，
 * 测试里每个临时目录各得一条驱动，生产里同一个目录只会开一次。
 *
 * ## 锁为什么用 [StateLock] 而不是 `synchronized`
 *
 * 这是 `commonMain`，`synchronized` / `ReentrantLock` 只在 JVM 上有（iOS 目标会编译失败）。
 * [createStateLock] 是本模块既有的跨平台阻塞锁（JVM/Android 是 `ReentrantLock`，
 * iOS 是 `NSRecursiveLock`），`expect/actual` 的写法与这里同源。
 */
fun createDatabaseDriver(platform: AppPlatform): SqlDriver {
    val key = databaseKey(platform)
    return driverCacheLock.withLock {
        driverCache.getOrPut(key) {
            openCount += 1
            // 两层包装缺一不可（task-14）：
            // · 缓存 ⇒ 每库每进程只开一条驱动（迁移只跑一遍、不再有第二条连接）；
            // · 事务串行化 ⇒ 本进程的两个写者不会同时进 SQLite 的写锁（见那个类的 KDoc：
            //   WAL + busy_timeout 挡不住"同进程两连接升级写锁"这种立即 BUSY）。
            SerializedAccessDriver(openPlatformDatabase(platform), writeLockFor(key))
        }
    }
}

/** 库的进程内唯一标识：同一数据根下的 `relay.db`。 */
internal fun databaseKey(platform: AppPlatform): String =
    platform.dataDirectory.dataDirectory().trimEnd('/', '\\') + "/relay.db"

/**
 * busy_timeout：5 秒。
 *
 * 为什么是 5 秒：写事务在真机上最长的一次（整份聊天记录覆盖写 + FTS 索引）在秒级，
 * 5 秒足够让第二个写者等到第一个提交；再长只会让「卡住」看起来像「死掉」。
 */
internal const val SQLITE_BUSY_TIMEOUT_MS: Long = 5_000

/**
 * 把 WAL 与 busy_timeout 落到驱动上（task-14）。
 *
 * ## 为什么不是「打开后跑一次 PRAGMA」就完事
 *
 * `JdbcSqliteDriver` 的 `ThreadedConnectionManager` 是**每线程一条连接**
 * （`ThreadLocal<Connection>`，见 sqlite-driver 的 `getConnection()`），`busy_timeout`
 * 又是**连接级**设置 —— 只在建驱动那条连接上跑一次 PRAGMA，真正并发写的那些 worker
 * 线程各自新建连接时仍然是 0（立刻 `SQLITE_BUSY`）。所以 JVM 那边把 `busy_timeout`
 * 经 JDBC 属性下发（`DriverManager.getConnection(url, properties)` 对每条新连接生效）；
 * 这里这一次是兜底与自证（单测会在另一条线程上断言它确实生效）。
 *
 * `journal_mode` 刻意**不**走属性：它是**库文件级**的持久属性，设一次就写进文件头；
 * 当「每条新连接都设一遍」下发，会在并发写时踩到「新连接要拿锁改 journal_mode、而旧连接
 * 正持有写锁」⇒ 立即 `SQLITE_BUSY`。所以 WAL 只在这里设一次。
 *
 * Android/iOS 的驱动是单连接语义，这一次就够了。
 */
internal fun applySqlitePragmas(driver: SqlDriver) {
    // 为什么不是 driver.execute()：这两条 PRAGMA **都会返回一行**（回显设置后的值），
    // 而 Android 驱动的 execute() 落到 SQLiteStatement.executeUpdateDelete() —— 它对
    // 返回行的语句直接抛
    //   SQLiteException: Queries can be performed using SQLiteDatabase query or rawQuery methods only
    // 真机上的表现是**启动即崩**（createMessageStore 建库那条路上，见 ADR-021）。
    // 走 executeQuery 并把结果丢掉，三个平台都接受。
    pragma(driver, "PRAGMA journal_mode = WAL")
    pragma(driver, "PRAGMA busy_timeout = $SQLITE_BUSY_TIMEOUT_MS")
}

/**
 * 跑一条**会返回行**的 PRAGMA，把结果丢掉。
 *
 * executeQuery 在三个驱动上都是「允许返回行」的那条路；末尾取 .value 是必须的 ——
 * JVM 驱动的查询是惰性的，不取就不会真的执行。
 */
private fun pragma(driver: SqlDriver, sql: String) {
    driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            while (cursor.next().value) {
                // 结果（journal_mode / busy_timeout 的回显）刻意丢掉。
            }
            QueryResult.Value(Unit)
        },
        parameters = 0,
    ).value
}

private val driverCacheLock: StateLock = createStateLock()
private val driverCache = mutableMapOf<String, SqlDriver>()

/**
 * 每个库一条**访问锁**：本进程的读与事务在驱动层串行化（见 [SerializedAccessDriver]）。
 *
 * 与驱动缓存同一把锁保护这张表；锁本身按库路径分（同一个进程里只有一个 relay.db，
 * 测试里每个临时目录各一把，互不拖累）。
 */
private val writeLocks = mutableMapOf<String, StateLock>()
private fun writeLockFor(key: String): StateLock = writeLocks.getOrPut(key) { createStateLock() }

/**
 * 进程内真正打开过几条驱动。
 *
 * 只给测试用：它把「两个 store 共用一个驱动 / 迁移只跑一遍」从「看代码相信」变成
 * 「可断言」。生产不读它。
 */
private var openCount = 0
internal fun databaseDriverOpenCountForTests(): Int = driverCacheLock.withLock { openCount }

/**
 * 只给测试：清空缓存并关掉已开的驱动（同一个路径要重开一条全新驱动时用）。
 *
 * 关驱动是必须的：不关就留下一个仍持有文件锁的连接，后续「重开」的测试会看见
 * 上一轮的残留状态。
 */
internal fun resetDatabaseDriverCacheForTests() {
    driverCacheLock.withLock {
        driverCache.values.forEach { runCatching { it.close() } }
        driverCache.clear()
        openCount = 0
    }
}
