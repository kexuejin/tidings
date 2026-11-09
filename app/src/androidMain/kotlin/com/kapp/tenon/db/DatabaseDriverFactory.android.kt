// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.kapp.tenon.platform.AndroidDatabaseHost
import com.kapp.tenon.platform.AppPlatform

internal actual fun openPlatformDatabase(platform: AppPlatform): SqlDriver {
    // 走窄契约 [AndroidDatabaseHost] 而不是强转平台实现类：db/ 要搬进 :app，
    // 而 AndroidAppPlatform 带 Compose 类型（QrPreviewProvider）必须留在 UI 侧。
    // 直接强转会让 :app 反过来依赖 UI 模块 = 循环依赖。
    val context = (platform as? AndroidDatabaseHost)?.databaseContext
        ?: throw IllegalArgumentException("Android platform must implement AndroidDatabaseHost")
    // 用 FtsTolerantSchema 而不是 RelayDatabase.Schema：Android 的框架 SQLite 没有 fts5，
    // 原样 create 会在 `CREATE VIRTUAL TABLE MessageFts …` 上抛错并回滚整库事务，
    // 于是连 MessageEntity 都不存在 —— 聊天记录从不落盘、检索恒 0 条（真机定位）。
    val driver = AndroidSqliteDriver(FtsTolerantSchema(), context, "relay.db")
    // 开库后无条件补建「版本号说建好了、实际不在」的表（D4：老库血统把 19/20 段整体
    // 跳过，`BoardCardRev` 从未建出，每帧一行 rev ledger save FAILED）。幂等：
    // 健康库上每条 DDL 都是空操作。放在这里而不是 Schema 里，是因为这条路径在
    // `user_version == schema.version` 时**根本不调** create/migrate —— 那正是出问题的现场。
    SchemaSelfHeal.run(driver)
    // task-14：与桌面同一条口径 —— 开库即设 WAL 与 busy_timeout。
    // Android 的框架 SQLite 自己有一层连接池，这一次 PRAGMA 至少把库文件钉成 WAL
    // （持久属性）并给建驱动这条连接设上超时；真正的多连接并发由框架自己管。
    applySqlitePragmas(driver)
    return driver
}
