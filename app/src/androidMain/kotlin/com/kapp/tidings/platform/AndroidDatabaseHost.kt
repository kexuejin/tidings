// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import android.content.Context

/**
 * 「本平台能给出一个 Android [Context]，用它开 SQLite 库」这件事。
 *
 * ## 为什么需要它（而不是直接强转 `AndroidAppPlatform`）
 *
 * `db/` 的 Android 实现要一个 `Context` 才能 `AndroidSqliteDriver`。它原来写的是
 * `platform as? AndroidAppPlatform` —— 那是**领域层依赖平台实现类**，而不是契约。
 *
 * 在单模块里这没问题；按模块拆开之后它是硬伤：`db/` 要进 `:app`，而
 * `AndroidAppPlatform` 带着 `QrPreviewProvider`（Compose 类型）必须留在 UI 侧 ——
 * 于是 `:app` 反过来依赖 UI 模块，循环。
 *
 * 所以把「我要一个 Context」抽成这条**窄契约**：它只声明领域真正需要的那一件事，
 * 不含任何 Compose 类型。平台实现类额外实现它；`db/` 只认这个接口。
 *
 * ## 契约
 *
 * 不实现本接口的平台 = 「本平台没有 Android SQLite 这条路」。`db/` 那时如实抛错
 * （那条路径本来只会在 Android 上被走到）。
 */
interface AndroidDatabaseHost {
    /** 开库用的 Context（通常是 Application context）。 */
    val databaseContext: Context
}
