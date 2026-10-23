// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kapp.tidings.core.testing

import com.kapp.tidings.core.protocol.newId
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

actual fun createTempDir(prefix: String): String {
    val base = NSTemporaryDirectory().trimEnd('/')
    val path = "$base/$prefix${newId()}"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

/** `removeItemAtPath` 自带递归语义；失败不抛，理由同 JVM 侧。 */
actual fun removeRecursively(path: String) {
    runCatching { NSFileManager.defaultManager.removeItemAtPath(path, error = null) }
}
