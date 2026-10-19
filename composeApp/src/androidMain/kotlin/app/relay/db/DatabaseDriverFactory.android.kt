// SPDX-License-Identifier: GPL-3.0-only

package app.relay.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.relay.platform.AndroidAppPlatform
import app.relay.platform.AppPlatform

internal actual fun openPlatformDatabase(platform: AppPlatform): SqlDriver {
    val context = (platform as? AndroidAppPlatform)?.context
        ?: throw IllegalArgumentException("Android platform must provide AndroidAppPlatform context")
    // 用 FtsTolerantSchema 而不是 RelayDatabase.Schema：Android 的框架 SQLite 没有 fts5，
    // 原样 create 会在 `CREATE VIRTUAL TABLE MessageFts …` 上抛错并回滚整库事务，
    // 于是连 MessageEntity 都不存在 —— 聊天记录从不落盘、检索恒 0 条（真机定位）。
    val driver = AndroidSqliteDriver(FtsTolerantSchema(), context, "relay.db")
    // task-14：与桌面同一条口径 —— 开库即设 WAL 与 busy_timeout。
    // Android 的框架 SQLite 自己有一层连接池，这一次 PRAGMA 至少把库文件钉成 WAL
    // （持久属性）并给建驱动这条连接设上超时；真正的多连接并发由框架自己管。
    applySqlitePragmas(driver)
    return driver
}
