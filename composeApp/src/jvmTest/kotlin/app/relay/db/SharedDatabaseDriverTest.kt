// SPDX-License-Identifier: GPL-3.0-only

package app.relay.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.relay.app.createAgentStore
import app.relay.app.createMessageStore
import app.relay.app.model.AgentSessionUi
import app.relay.app.model.MessageUi
import app.relay.platform.AppPlatform
import app.relay.platform.DataDirectoryProvider
import app.relay.platform.InMemoryAppPlatform
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 桌面端写库自锁的回归（task-14）。
 *
 * 真机症状：应用活着、界面能用，但 `relay.db` 被锁 —— journal 冻结数分钟、外部
 * `sqlite3 -readonly` 全部 `database is locked`、6+ 个 worker 卡在 `NativeDB.step`（低 CPU 空等）。
 * 根因是同一个库上开了**两条驱动/两条连接**（message store 与 agent store 各一条），
 * 且没有 WAL、没有 busy_timeout ⇒ 两个写者互等；建表/迁移也因此跑了两遍。
 *
 * 四条断言各钉住根因的一半：
 * 1. 同一库文件复用同一条驱动（[oneDriverPerDatabaseFilePerProcess]）；
 * 2. 两个 store 只打开一条驱动、迁移只跑一遍（[theTwoStoresShareOneDriverAndMigrateOnce]）；
 * 3. WAL 与 busy_timeout 落到**每一条**连接（含另一条线程新建的）
 *    （[walAndBusyTimeoutApplyToEveryConnection]）—— 这一条最关键：只跑一次 PRAGMA 是不够的；
 * 4. 多协程并发读写带超时跑完（[concurrentReadWriteAcrossBothStoresDoesNotWedge]）。
 */
class SharedDatabaseDriverTest {

    private lateinit var directory: File
    private lateinit var platform: AppPlatform

    @BeforeTest
    fun setUp() {
        // 缓存是进程级的：不清就会把上一个用例的驱动带进来（临时目录不同，键也不同，
        // 但显式清一次能让每个用例从零开始，断言才干净）。
        resetDatabaseDriverCacheForTests()
        directory = Files.createTempDirectory("relay-shared-driver").toFile()
        platform = platformAt(directory)
    }

    @AfterTest
    fun tearDown() {
        resetDatabaseDriverCacheForTests()
        directory.deleteRecursively()
    }

    private fun platformAt(dir: File): AppPlatform = InMemoryAppPlatform(
        dataDirectory = object : DataDirectoryProvider {
            override fun dataDirectory(): String = dir.absolutePath
            override fun cacheDirectory(): String = File(dir, "cache").absolutePath
        },
    )

    private fun pragmaLong(driver: SqlDriver, sql: String): Long {
        var value = -1L
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) value = cursor.getLong(0) ?: -1L
                QueryResult.Value(Unit)
            },
            parameters = 0,
        ).value
        return value
    }

    private fun pragmaText(driver: SqlDriver, sql: String): String {
        var value = ""
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) value = cursor.getString(0).orEmpty()
                QueryResult.Value(Unit)
            },
            parameters = 0,
        ).value
        return value
    }

    @Test
    fun oneDriverPerDatabaseFilePerProcess() {
        val first = createDatabaseDriver(platform)
        val second = createDatabaseDriver(platform)
        assertSame(first, second, "同一数据根必须复用同一条驱动")

        val otherDir = Files.createTempDirectory("relay-shared-driver-b").toFile()
        try {
            assertNotSame(first, createDatabaseDriver(platformAt(otherDir)), "另一个数据根是另一条驱动")
        } finally {
            otherDir.deleteRecursively()
        }
    }

    @Test
    fun theTwoStoresShareOneDriverAndMigrateOnce() {
        createMessageStore(platform)
        createAgentStore(platform)
        assertEquals(
            1,
            databaseDriverOpenCountForTests(),
            "两个 store 只该打开一条驱动 —— 建表/迁移也只该跑一遍（旧实现跑两遍）",
        )
    }

    @Test
    fun walAndBusyTimeoutApplyToEveryConnection() {
        val driver = createDatabaseDriver(platform)
        val journalMode = pragmaText(driver, "PRAGMA journal_mode")
        val busyTimeout = pragmaLong(driver, "PRAGMA busy_timeout")
        // 这两行是 task-14 要求②的实测证据：报告里贴的就是它们印出来的值。
        println("PRAGMA journal_mode=$journalMode busy_timeout=$busyTimeout")
        assertEquals("wal", journalMode, "库要落在 WAL 上")
        assertEquals(SQLITE_BUSY_TIMEOUT_MS, busyTimeout)

        // JdbcSqliteDriver 的 ThreadedConnectionManager 是**每线程一条连接**：另一条线程
        // 新建的连接也必须带上 busy_timeout，否则并发写的那条线程会立刻 SQLITE_BUSY
        // —— 这正是自锁的一半。只跑一次 PRAGMA 是过不了这一条的。
        var otherThreadValue = -1L
        thread { otherThreadValue = pragmaLong(driver, "PRAGMA busy_timeout") }.join()
        assertEquals(SQLITE_BUSY_TIMEOUT_MS, otherThreadValue, "每一条新连接都要有 busy_timeout")
    }

    @Test
    fun concurrentReadWriteAcrossBothStoresDoesNotWedge() = runBlocking {
        val messageStore = createMessageStore(platform)
        val agentStore = createAgentStore(platform)
        // 带超时：旧实现在这里会互等（两个写者各持一条连接、没有 WAL/busy_timeout）。
        withTimeout(30_000) {
            coroutineScope {
                repeat(8) { worker ->
                    launch(Dispatchers.IO) {
                        repeat(20) { round ->
                            messageStore.save(
                                mapOf(
                                    "peer-$worker" to listOf(
                                        MessageUi(
                                            id = "m-$worker-$round",
                                            peerId = "peer-$worker",
                                            mine = false,
                                            text = "并发写 $round",
                                            at = round.toLong(),
                                        ),
                                    ),
                                ),
                            )
                            messageStore.load()
                            agentStore.saveSession(
                                AgentSessionUi(
                                    peerId = "peer-$worker",
                                    sessionId = "s-$worker",
                                    updatedAt = round.toLong(),
                                ),
                            )
                            agentStore.loadSessions()
                        }
                    }
                }
            }
        }
    }
    /**
     * 外部进程能读到库（task-14 要求⑤的另一半）。
     *
     * 真机症状里最刺眼的一条就是「外部 sqlite3 全部 database is locked」—— 这条测试真的起
     * 一个 sqlite3 进程去读，而不是只看代码。环境里没有 sqlite3 CLI 就跳过（不假装验过）。
     *
     * ## 为什么不是 `sqlite3 -readonly`
     *
     * 实测：库开着 WAL 时，`-readonly` 会以 `Error: in prepare, unable to open database file (14)`
     * 失败 —— 这是 SQLite 的**文档化限制**（只读连接不能写 `-wal/-shm` 共享内存索引），
     * 不是锁。换成一个普通连接（只跑 SELECT）能正常读到，说明库没有被锁住。
     */
    @Test
    fun anExternalReadOnlyReaderCanReadWhileTheStoresHoldTheDatabase() {
        val messageStore = createMessageStore(platform)
        val agentStore = createAgentStore(platform)
        messageStore.save(
            mapOf(
                "peer-x" to listOf(
                    MessageUi(id = "m1", peerId = "peer-x", mine = false, text = "外部读得到吗", at = 1),
                ),
            ),
        )
        agentStore.saveSession(AgentSessionUi(peerId = "peer-x", sessionId = "s1", updatedAt = 1))

        val result = runExternalSqlite("SELECT count(*) FROM MessageEntity;") ?: run {
            println("SKIP: 环境里没有 sqlite3 CLI，外部只读这一条未验证")
            return
        }
        assertEquals(0, result.exitCode, "外部只读查询不该被锁住，实际输出：" + result.output)
        assertTrue(result.output.contains("1"), "外部应读到 1 行，实际输出：" + result.output)
    }

    private data class ExternalResult(val exitCode: Int, val output: String)

    /** 起一个外部 sqlite3 进程打开同一个库文件；起不来返回 null。 */
    private fun runExternalSqlite(sql: String): ExternalResult? {
        val db = File(directory, "relay.db")
        if (!db.isFile) return null
        return try {
            val process = ProcessBuilder("sqlite3", db.absolutePath, sql)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            ExternalResult(process.waitFor(), output)
        } catch (_: Exception) {
            null
        }
    }
}
