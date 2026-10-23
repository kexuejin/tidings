// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.kapp.tidings.platform.AppPlatform

internal actual fun openPlatformDatabase(platform: AppPlatform): SqlDriver {
    // 系统 libsqlite3 通不通常带 fts5 不该靠猜：探针说有就原样建，没有就跳过那几张虚拟表。
    val driver = NativeSqliteDriver(FtsTolerantSchema(), "relay.db")
    // task-14：与桌面同一条口径 —— 开库即设 WAL 与 busy_timeout。
    applySqlitePragmas(driver)
    return driver
}
