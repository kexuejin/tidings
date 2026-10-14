// SPDX-License-Identifier: GPL-3.0-only

package app.relay.db

import app.cash.sqldelight.db.SqlDriver
import app.relay.platform.AppPlatform

/**
 * 跨平台数据库驱动构造工厂。
 *
 * 各平台分别映射到原生高性能驱动：
 * - Android: [AndroidSqliteDriver]
 * - Desktop (JVM): [JdbcSqliteDriver]
 * - iOS: [NativeSqliteDriver]
 */
expect fun createDatabaseDriver(platform: AppPlatform): SqlDriver
