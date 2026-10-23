// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema

/**
 * 把 `RelayDatabase.Schema` 包一层：**本机 SQLite 没有 fts5 时，跳过 `MessageFts` 那几条 DDL。**
 *
 * 为什么非要有这一层（真机 logcat，Android）：
 *
 * ```
 * E SQLiteLog: (1) statement aborts at 29: [CREATE VIRTUAL TABLE MessageFts USING fts5(
 * E SQLiteLog:     text,
 * E SQLiteLog:     content='MessageEntity',
 * E SQLiteLog:     content_rowid='rowid',
 * E SQLiteLog:     tokenize='trigram'
 * E SQLiteLog: )] no such module: fts5
 * ```
 *
 * Android 的框架 SQLite 没有编译 fts5（桌面那条 `sqlite-jdbc` 与 iOS 的系统 libsqlite3 有）。
 * 后果不是"少一个检索索引"，而是**整库建不出来**：`Schema.create` 是一个事务，
 * 这一句抛异常 → 事务回滚 → 连 `MessageEntity` 都不存在 → `save`/`load`/`search`
 * 全部抛 `no such table`，而它们每一处都被 `runCatching` 吞掉。用户看到的只是
 * 「聊天记录重启就没了、搜什么都搜不到」，代码里一条日志都没有。
 * 那正是 e2e 夹具 `scripts/e2e/e2e_search.py` 在真机上逼出来的：
 * 消息 UDP 被 ACK 了（`target-text-acked-over-udp`），但会话里没有气泡、
 * 会话内检索 `0 条`、会话列表 `没有匹配…的会话`。
 *
 * 所以这里先探一句：建得出 fts5 就原样建（桌面 / iOS）；建不出就只把那几条
 * **引用 `MessageFts` 的语句**变成 no-op，其余表照常落地。检索侧不需要额外开关 ——
 * `SqlDelightMessageStore` 每条查询都有 `runCatching` 退化到 LIKE 的分支。
 *
 * 不用「换 fts4」来绕：fts4 没有 trigram 分词器，中文子串检索会直接失效，
 * 而 LIKE 兜底恰好保持了「子串匹配」这个语义（只是慢）。
 */
class FtsTolerantSchema(
    private val delegate: SqlSchema<QueryResult.Value<Unit>> = RelayDatabase.Schema,
    /**
     * 注入点只为测试：真机/单测里都用默认实现（真去建一句 fts5 试试）。
     */
    private val fts5Available: (SqlDriver) -> Boolean = ::probeFts5,
) : SqlSchema<QueryResult.Value<Unit>> {

    override val version: Long
        get() = delegate.version

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> =
        if (fts5Available(driver)) {
            delegate.create(driver)
        } else {
            delegate.create(FtsSkippingDriver(driver))
        }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> =
        if (fts5Available(driver)) {
            delegate.migrate(driver, oldVersion, newVersion, *callbacks)
        } else {
            delegate.migrate(FtsSkippingDriver(driver), oldVersion, newVersion, *callbacks)
        }

    /**
     * 只跳过 `MessageFts` 相关语句（虚拟表本身 + 三个同步触发器）。
     *
     * 用 `contains` 而不是正则/前缀匹配：这几条语句是 `.sq` 生成出来的，形态固定；
     * 而库里唯一提到 `MessageFts` 的地方就是它们（其余查询走 `executeQuery`，
     * 这里一概不拦 —— 被拦的只能是 DDL，DDL 走 `execute`）。
     */
    private class FtsSkippingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> =
            if (sql.contains(MessageFts.NAME)) QueryResult.Value(0L)
            else delegate.execute(identifier, sql, parameters, binders)
    }

    private companion object {
        /**
         * 造一张 temp 虚拟表来问"你这台机器有没有 fts5"。
         *
         * 三个细节都是有意的：
         * 1. `temp.` —— 探针不碰真实 schema，建/删都落在临时库里；
         * 2. `IF NOT EXISTS` + 探完就 DROP —— 同一进程里再进一次也不会残留；
         * 3. 建不出来时**只**让这一条语句失败：SQLite 里语法/模块错误只中止当前语句，
         *    不会连带回滚外层事务（会回滚的是 FULL/IOERR/BUSY 那几类），
         *    所以这个探针可以安全地跑在 `Schema.create` 之前。
         */
        fun probeFts5(driver: SqlDriver): Boolean {
            val created = runCatching {
                driver.execute(
                    null,
                    "CREATE VIRTUAL TABLE IF NOT EXISTS temp.__relay_fts5_probe USING fts5(x)",
                    0,
                ).value
            }.isSuccess
            if (created) {
                runCatching { driver.execute(null, "DROP TABLE IF EXISTS temp.__relay_fts5_probe", 0) }
            }
            return created
        }
    }
}

/**
 * `.sq` 里那张虚拟表的名字。只在这一处写成常量：DDL 的跳过判据与文档都指它。
 */
internal object MessageFts {
    const val NAME: String = "MessageFts"
}
