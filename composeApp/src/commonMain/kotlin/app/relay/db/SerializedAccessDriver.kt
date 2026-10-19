// SPDX-License-Identifier: GPL-3.0-only

package app.relay.db

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.relay.app.StateLock

/**
 * 把**本进程对库的访问**（读 + 事务）串行化的驱动包装（task-14）。
 *
 * ## 为什么光靠 WAL + busy_timeout 还不够
 *
 * 实测（`SharedDatabaseDriverTest` + 两个临时探针）：同一条驱动、两条连接（sqlite-jdbc 的
 * `ThreadedConnectionManager` 是每线程一条）、`journal_mode=wal`、`busy_timeout=30000`
 * 都已经生效的前提下，两个线程并发写仍会拿到 `SQLITE_BUSY: database is locked` —— 而且
 * **不是等满超时**（实测 39ms 就抛）。原因是 SQLite 对「同进程两条连接、其中一条在事务里
 * 升级写锁」这类局面会**跳过 busy handler 直接返回 BUSY**（避免死锁），busy_timeout 救不了它。
 * 对照实验：裸 JDBC 两条连接各跑 `BEGIN` + `INSERT` 时 busy handler 是会等的（等了 5.36s）。
 * 也就是说这是 SQLDelight 事务用法下的固有冲突，不是超时值不够。
 *
 * ## 所以：本进程同一时刻只允许一个事务
 *
 * 这个库的写者**只有本进程**（消息落盘、agent 帧落盘、审批处置）。既然是我们自己的两个
 * 写者在抢，就不该交给 SQLite 的锁去解决 —— 在驱动这一层串行化，SQLite 根本看不到并发写。
 * 读不受影响：普通查询不经过 [newTransaction]，WAL 下与写事务并发仍然成立（这正是要 WAL 的原因）。
 *
 * ## 锁为什么能可靠地还回去
 *
 * SQLDelight 只在**最外层**事务上调用 `newTransaction()`（嵌套事务复用外层，见
 * `TransacterImpl.transactionWithWrapper` 的 `enclosingTransaction`），所以这里加的锁
 * 一个事务只加一次。释放挂在 `afterCommit` / `afterRollback` 上：
 * `TransacterImpl.postTransactionCleanup` 在提交、回滚、抛异常三条路径上都会走到，
 * 不会漏放（漏放等于把整个库钉死，比 BUSY 更糟）。
 *
 * 用 [StateLock] 而不是 `synchronized`：`commonMain` 里没有 `synchronized`（iOS 目标），
 * 而 [StateLock] 是本模块既有的可重入跨平台阻塞锁。可重入是必需的 —— 同一个线程里
 * 嵌套事务（SQLDelight 允许）会再次经过这里。
 */
internal class SerializedAccessDriver(
    private val delegate: SqlDriver,
    private val lock: StateLock,
) : SqlDriver by delegate {

    /**
     * 普通查询也在锁里跑。
     *
     * 实测：只串行化**事务**仍然会拿到 SQLITE_BUSY —— 因为真正的冲突是「读」与「写」：
     * 同一个线程的连接先跑过一次 SELECT（ThreadedConnectionManager 每线程复用一条连接），
     * 之后开事务写时，如果期间**另一条连接提交过**，这次写就是 WAL 里的
     * SQLITE_BUSY_SNAPSHOT —— 同样跳过 busy handler、立即返回。
     * 把读也串起来，本进程内就不存在「我读完之后别人提交」这个窗口了。
     *
     * 可重入锁让这件事没有代价：事务里的语句会重新进入同一把锁（同一线程），不会自锁。
     */
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (app.cash.sqldelight.db.SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        lock.lock()
        try {
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        } finally {
            lock.unlock()
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        lock.lock()
        try {
            return delegate.execute(identifier, sql, parameters, binders)
        } finally {
            lock.unlock()
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        lock.lock()
        val transaction = try {
            delegate.newTransaction().value
        } catch (t: Throwable) {
            // BEGIN 本身失败（例如库被外部进程锁住）时锁要立刻还回去。
            lock.unlock()
            throw t
        }
        var released = false
        val release = {
            if (!released) {
                released = true
                lock.unlock()
            }
        }
        transaction.afterCommit(release)
        transaction.afterRollback(release)
        return QueryResult.Value(transaction)
    }
}
