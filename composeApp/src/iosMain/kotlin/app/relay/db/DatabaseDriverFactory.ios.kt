// SPDX-License-Identifier: GPL-3.0-only

package app.relay.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.relay.platform.AppPlatform

actual fun createDatabaseDriver(platform: AppPlatform): SqlDriver {
    // 系统 libsqlite3 通不通常带 fts5 不该靠猜：探针说有就原样建，没有就跳过那几张虚拟表。
    return NativeSqliteDriver(FtsTolerantSchema(), "relay.db")
}
